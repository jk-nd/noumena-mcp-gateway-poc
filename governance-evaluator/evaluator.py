"""
Governance Evaluator — constraint evaluation sidecar for Layer 3.

Sits between OPA and NPL ServiceGovernance. Evaluates argument-level
constraints (regex, contains, in/not_in, max_length) that NPL cannot
handle, then routes to NPL for approval workflows when needed.

Endpoint: POST /evaluate
  - Same request shape OPA sends to NPL
  - Same response shape {decision, requestId, message}

Cache: Fetches ToolConfig from all ServiceGovernance instances every
CACHE_REFRESH_SECONDS. Keyed by serviceName.
"""

import json
import logging
import os
import re
import threading
import time
from http.server import BaseHTTPRequestHandler, HTTPServer
from socketserver import ThreadingMixIn

import requests

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(message)s",
    datefmt="%Y-%m-%dT%H:%M:%S",
)
log = logging.getLogger("governance-evaluator")

# --- Configuration ---
NPL_URL = os.environ.get("NPL_URL", "http://localhost:12000")
KEYCLOAK_URL = os.environ.get("KEYCLOAK_URL", "http://localhost:11000")
KEYCLOAK_REALM = os.environ.get("KEYCLOAK_REALM", "mcpgateway")
GATEWAY_USERNAME = os.environ.get("GATEWAY_USERNAME", "gateway")
GATEWAY_PASSWORD = os.environ.get("GATEWAY_PASSWORD", "Welcome123")
CACHE_REFRESH_SECONDS = int(os.environ.get("CACHE_REFRESH_SECONDS", "30"))
PORT = int(os.environ.get("PORT", "8090"))

# --- Token management ---
_token_lock = threading.Lock()
_cached_token: str | None = None
_token_expires: float = 0


def get_gateway_token() -> str:
    global _cached_token, _token_expires
    with _token_lock:
        if _cached_token and time.time() < _token_expires - 10:
            return _cached_token
        resp = requests.post(
            f"{KEYCLOAK_URL}/realms/{KEYCLOAK_REALM}/protocol/openid-connect/token",
            data={
                "grant_type": "password",
                "client_id": "mcpgateway",
                "username": GATEWAY_USERNAME,
                "password": GATEWAY_PASSWORD,
            },
            timeout=10,
        )
        resp.raise_for_status()
        data = resp.json()
        _cached_token = data["access_token"]
        _token_expires = time.time() + data.get("expires_in", 60)
        log.info("Refreshed gateway token (expires in %ds)", data.get("expires_in", 60))
        return _cached_token


def auth_header() -> dict:
    return {"Authorization": f"Bearer {get_gateway_token()}"}


# --- Cache: serviceName → {instanceId, toolConfigs: {toolName: ToolConfig}} ---
_cache_lock = threading.Lock()
_config_cache: dict[str, dict] = {}


def refresh_cache():
    """Fetch all ServiceGovernance instances and their tool configs."""
    global _config_cache
    try:
        headers = auth_header()

        # Discover all ServiceGovernance instances
        resp = requests.get(
            f"{NPL_URL}/npl/governance/ServiceGovernance/",
            headers=headers,
            timeout=10,
        )
        resp.raise_for_status()
        items = resp.json().get("items", [])

        new_cache = {}
        for item in items:
            instance_id = item.get("@id", "")
            svc_name = item.get("serviceName", "")
            if not svc_name or not instance_id:
                continue

            # Fetch tool configs for this instance
            tool_configs = {}
            try:
                cfg_resp = requests.post(
                    f"{NPL_URL}/npl/governance/ServiceGovernance/{instance_id}/getToolConfigs",
                    headers={**headers, "Content-Type": "application/json"},
                    json={},
                    timeout=10,
                )
                if cfg_resp.status_code < 400:
                    for tc in cfg_resp.json():
                        tool_name = tc.get("toolName", "")
                        if tool_name:
                            tool_configs[tool_name] = tc
            except Exception as e:
                log.warning("Failed to fetch tool configs for %s: %s", svc_name, e)

            new_cache[svc_name] = {
                "instanceId": instance_id,
                "toolConfigs": tool_configs,
            }

        with _cache_lock:
            _config_cache = new_cache
        log.info(
            "Cache refreshed: %d services, %d tool configs total",
            len(new_cache),
            sum(len(v["toolConfigs"]) for v in new_cache.values()),
        )
    except Exception as e:
        log.error("Cache refresh failed: %s", e)


def cache_loop():
    """Periodically refresh the config cache."""
    while True:
        refresh_cache()
        time.sleep(CACHE_REFRESH_SECONDS)


# --- Constraint evaluation ---
def evaluate_constraints(tool_config: dict, arguments: dict) -> tuple[bool, str]:
    """Evaluate all constraints in a ToolConfig against parsed arguments.

    Returns (passed, message). If passed=False, message describes the violation.
    """
    constraints = tool_config.get("constraints", [])
    for c in constraints:
        param_name = c.get("paramName", "")
        operator = c.get("operator", "")
        values = c.get("values", [])
        description = c.get("description", "")

        arg_value = arguments.get(param_name)
        # If the argument is not present, skip (constraint only applies when arg is provided)
        if arg_value is None:
            continue

        arg_str = str(arg_value)
        passed = True
        violation_detail = ""

        if operator == "in":
            if arg_str not in values:
                passed = False
                violation_detail = f"'{param_name}' value '{arg_str}' not in allowed list {values}"

        elif operator == "not_in":
            if arg_str in values:
                passed = False
                violation_detail = f"'{param_name}' value '{arg_str}' is in blocked list"

        elif operator == "contains":
            if not any(sub in arg_str for sub in values):
                passed = False
                violation_detail = f"'{param_name}' must contain one of {values}"

        elif operator == "not_contains":
            found = [sub for sub in values if sub in arg_str]
            if found:
                passed = False
                violation_detail = f"'{param_name}' must not contain {found}"

        elif operator == "regex":
            if not any(re.search(pattern, arg_str) for pattern in values):
                passed = False
                violation_detail = f"'{param_name}' does not match any allowed pattern"

        elif operator == "max_length":
            max_len = int(values[0]) if values else 0
            if len(arg_str) > max_len:
                passed = False
                violation_detail = f"'{param_name}' length {len(arg_str)} exceeds max {max_len}"

        if not passed:
            msg = f"Constraint violated: {description}" if description else f"Constraint violated: {violation_detail}"
            return False, msg

    return True, "Constraints satisfied"


# --- NPL forwarding ---
def forward_to_npl(instance_id: str, body: dict) -> dict:
    """Forward evaluate request to NPL ServiceGovernance."""
    headers = {**auth_header(), "Content-Type": "application/json"}
    npl_body = {
        "toolName": body.get("toolName", ""),
        "callerIdentity": body.get("callerIdentity", ""),
        "callerClaims": body.get("callerClaims", {}),
        "arguments": body.get("arguments", "{}"),
        "sessionId": body.get("sessionId", ""),
        "requestPayload": body.get("requestPayload", "{}"),
    }
    resp = requests.post(
        f"{NPL_URL}/npl/governance/ServiceGovernance/{instance_id}/evaluate",
        headers=headers,
        json=npl_body,
        timeout=5,
    )
    resp.raise_for_status()
    return resp.json()


# --- HTTP server ---
class ThreadingHTTPServer(ThreadingMixIn, HTTPServer):
    daemon_threads = True


class EvaluatorHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        pass

    def send_json(self, data: dict, status: int = 200):
        body = json.dumps(data).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Connection", "close")
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path == "/health":
            with _cache_lock:
                svc_count = len(_config_cache)
            self.send_json({"status": "healthy", "cached_services": svc_count})
        else:
            self.send_json({"error": "Not found"}, 404)

    def do_POST(self):
        if self.path != "/evaluate":
            return self.send_json({"error": "Not found"}, 404)

        length = int(self.headers.get("Content-Length", 0))
        if length == 0:
            return self.send_json({"error": "Empty body"}, 400)

        body = json.loads(self.rfile.read(length))
        service_name = body.get("serviceName", "")
        tool_name = body.get("toolName", "")
        arguments_str = body.get("arguments", "{}")

        if not service_name:
            return self.send_json({"error": "serviceName required"}, 400)

        # Look up service in cache
        with _cache_lock:
            svc_entry = _config_cache.get(service_name)

        if not svc_entry:
            # No governance instance for this service — deny (fail-closed)
            return self.send_json({
                "decision": "deny",
                "requestId": "",
                "message": f"No governance instance for service '{service_name}'",
            })

        instance_id = svc_entry["instanceId"]
        tool_config = svc_entry["toolConfigs"].get(tool_name)

        # Parse arguments
        try:
            arguments = json.loads(arguments_str) if isinstance(arguments_str, str) else arguments_str
        except (json.JSONDecodeError, TypeError):
            arguments = {}

        # If no tool config exists, fall through to NPL directly (default: requires approval)
        if not tool_config:
            try:
                npl_result = forward_to_npl(instance_id, body)
                return self.send_json(npl_result)
            except Exception as e:
                log.error("NPL forward failed for %s.%s: %s", service_name, tool_name, e)
                return self.send_json({
                    "decision": "deny",
                    "requestId": "",
                    "message": f"Governance evaluation failed: {e}",
                })

        # Evaluate constraints
        constraints_pass, constraint_msg = evaluate_constraints(tool_config, arguments)

        if not constraints_pass:
            # Constraint violation → deny immediately (no NPL call needed)
            log.info("Constraint denied: %s.%s — %s", service_name, tool_name, constraint_msg)
            return self.send_json({
                "decision": "deny",
                "requestId": "",
                "message": constraint_msg,
            })

        # Constraints pass
        requires_approval = tool_config.get("requiresApproval", True)

        if not requires_approval:
            # Auto-allow: constraints satisfied and no approval needed
            return self.send_json({
                "decision": "allow",
                "requestId": "",
                "message": "Constraints satisfied",
            })

        # Requires approval → forward to NPL
        try:
            npl_result = forward_to_npl(instance_id, body)
            return self.send_json(npl_result)
        except Exception as e:
            log.error("NPL forward failed for %s.%s: %s", service_name, tool_name, e)
            return self.send_json({
                "decision": "deny",
                "requestId": "",
                "message": f"Governance evaluation failed: {e}",
            })


# --- Main ---
def main():
    log.info("Governance Evaluator starting on port %d", PORT)
    log.info("  NPL_URL=%s  CACHE_REFRESH=%ds", NPL_URL, CACHE_REFRESH_SECONDS)

    # Initial cache load
    log.info("Performing initial cache refresh...")
    refresh_cache()

    # Start cache refresh thread
    cache_thread = threading.Thread(target=cache_loop, daemon=True, name="cache-refresh")
    cache_thread.start()

    # Start HTTP server
    server = ThreadingHTTPServer(("0.0.0.0", PORT), EvaluatorHandler)
    log.info("Evaluator ready at http://0.0.0.0:%d", PORT)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        log.info("Shutting down")
        server.shutdown()


if __name__ == "__main__":
    main()
