"""
MCP Gateway Dashboard — web UI for v4 three-layer governance.

Serves a single-page dashboard and proxies authenticated API calls to:
  - NPL Engine (GatewayStore + ServiceGovernance)
  - Keycloak (user management)
  - OPA / Envoy (health checks)
  - Docker Hub (MCP server discovery)
  - Docker Engine (container management for service wiring)
  - MCP Aggregator (dynamic backend registration)
"""

import hashlib
import json
import logging
import os
import secrets
import threading
import time
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler
from socketserver import ThreadingMixIn
import http.server
from pathlib import Path
from urllib.parse import urlparse, parse_qs
from http.cookies import SimpleCookie

import requests

try:
    import docker
    DOCKER_AVAILABLE = True
except ImportError:
    DOCKER_AVAILABLE = False

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(message)s",
    datefmt="%Y-%m-%dT%H:%M:%S",
)
log = logging.getLogger("dashboard")

# --- Configuration ---
NPL_URL = os.environ.get("NPL_URL", "http://localhost:12000")
KEYCLOAK_URL = os.environ.get("KEYCLOAK_URL", "http://localhost:11000")
KEYCLOAK_REALM = os.environ.get("KEYCLOAK_REALM", "mcpgateway")
ADMIN_USERNAME = os.environ.get("ADMIN_USERNAME", "admin")
ADMIN_PASSWORD = os.environ.get("ADMIN_PASSWORD", "Welcome123")
GATEWAY_URL = os.environ.get("GATEWAY_URL", "http://envoy-gateway:8000")
OPA_URL = os.environ.get("OPA_URL", "http://opa:8181")
BUNDLE_SERVER_URL = os.environ.get("BUNDLE_SERVER_URL", "http://bundle-server:8282")
AGGREGATOR_URL = os.environ.get("AGGREGATOR_URL", "http://mcp-aggregator:8000")
ENVOY_ADMIN_URL = os.environ.get("ENVOY_ADMIN_URL", "http://envoy-gateway:9901")
GOVERNANCE_EVALUATOR_URL = os.environ.get("GOVERNANCE_EVALUATOR_URL", "http://governance-evaluator:8090")
DOCKER_NETWORK = os.environ.get("DOCKER_NETWORK", "gateway_backend-net")
PORT = int(os.environ.get("PORT", "8888"))

STATIC_DIR = Path(__file__).parent / "static"
MCP_REGISTRY_URL = "https://registry.modelcontextprotocol.io/v0.1/servers"

# --- Session management ---
_sessions: dict[str, dict] = {}  # token → {username, expires}
SESSION_TTL = 3600  # 1 hour


def create_session(username: str) -> str:
    token = secrets.token_urlsafe(32)
    _sessions[token] = {"username": username, "expires": time.time() + SESSION_TTL}
    return token


def validate_session(token: str) -> str | None:
    session = _sessions.get(token)
    if not session:
        return None
    if time.time() > session["expires"]:
        del _sessions[token]
        return None
    return session["username"]


# --- Token management (server-side Keycloak tokens) ---
_token_lock = threading.Lock()
_cached_token: str | None = None
_token_expires: float = 0


def get_admin_token() -> str:
    global _cached_token, _token_expires
    with _token_lock:
        if _cached_token and time.time() < _token_expires - 30:
            return _cached_token
        resp = requests.post(
            f"{KEYCLOAK_URL}/realms/{KEYCLOAK_REALM}/protocol/openid-connect/token",
            data={
                "grant_type": "password",
                "client_id": "mcpgateway",
                "username": ADMIN_USERNAME,
                "password": ADMIN_PASSWORD,
            },
            timeout=10,
        )
        resp.raise_for_status()
        data = resp.json()
        _cached_token = data["access_token"]
        _token_expires = time.time() + data.get("expires_in", 300)
        return _cached_token


_gw_token_lock = threading.Lock()
_cached_gw_token: str | None = None
_gw_token_expires: float = 0


def _get_gateway_token() -> str:
    """Get a Keycloak token for the 'gateway' user (has pGateway party)."""
    global _cached_gw_token, _gw_token_expires
    with _gw_token_lock:
        if _cached_gw_token and time.time() < _gw_token_expires - 30:
            return _cached_gw_token
        resp = requests.post(
            f"{KEYCLOAK_URL}/realms/{KEYCLOAK_REALM}/protocol/openid-connect/token",
            data={
                "grant_type": "password",
                "client_id": "mcpgateway",
                "username": "gateway",
                "password": ADMIN_PASSWORD,
            },
            timeout=10,
        )
        resp.raise_for_status()
        data = resp.json()
        _cached_gw_token = data["access_token"]
        _gw_token_expires = time.time() + data.get("expires_in", 300)
        return _cached_gw_token


# --- GatewayStore discovery ---
_store_id: str | None = None


def get_store_id() -> str | None:
    global _store_id
    if _store_id:
        return _store_id
    try:
        token = get_admin_token()
        resp = requests.get(
            f"{NPL_URL}/npl/store/GatewayStore/",
            headers={"Authorization": f"Bearer {token}"},
            timeout=10,
        )
        resp.raise_for_status()
        items = resp.json().get("items", [])
        if items:
            _store_id = items[0]["@id"]
            return _store_id
    except Exception as e:
        log.warning("GatewayStore discovery failed: %s", e)
    return None


def ensure_store_id() -> str:
    sid = get_store_id()
    if sid:
        return sid
    token = get_admin_token()
    resp = requests.post(
        f"{NPL_URL}/npl/store/GatewayStore/",
        headers={"Authorization": f"Bearer {token}", "Content-Type": "application/json"},
        json={"@parties": {}},
        timeout=10,
    )
    resp.raise_for_status()
    global _store_id
    _store_id = resp.json()["@id"]
    log.info("Created GatewayStore: %s", _store_id)
    return _store_id


# --- ServiceGovernance discovery ---
def get_governance_instances() -> dict:
    try:
        token = get_admin_token()
        resp = requests.get(
            f"{NPL_URL}/npl/governance/ServiceGovernance/",
            headers={"Authorization": f"Bearer {token}"},
            timeout=10,
        )
        resp.raise_for_status()
        result = {}
        for item in resp.json().get("items", []):
            svc = item.get("serviceName", "")
            iid = item.get("@id", "")
            if svc and iid:
                result[svc] = iid
        return result
    except Exception as e:
        log.warning("ServiceGovernance discovery failed: %s", e)
        return {}


# --- Docker client ---
def get_docker_client():
    if not DOCKER_AVAILABLE:
        return None
    try:
        return docker.from_env()
    except Exception as e:
        log.warning("Docker client unavailable: %s", e)
        return None


def find_supergateway_image():
    """Find the supergateway image from existing compose containers."""
    client = get_docker_client()
    if not client:
        return None
    try:
        containers = client.containers.list(all=True)
        for c in containers:
            if "supergateway" in c.name or (c.name.startswith("gateway-") and c.name.endswith("-mcp-1")):
                return c.image
        # Fallback: look for the image by name pattern
        for img in client.images.list():
            for tag in (img.tags or []):
                if "supergateway" in tag or "duckduckgo-mcp" in tag:
                    return img
    except Exception as e:
        log.warning("Could not find supergateway image: %s", e)
    return None


# --- Envoy stats parser ---
def _parse_envoy_stats(stats_text, clusters_text):
    """Parse Envoy admin stats and clusters into structured metrics."""
    result = {"stats": {}, "clusters": {}}

    # Parse stats (key: value lines)
    if isinstance(stats_text, str):
        for line in stats_text.strip().splitlines():
            if ": " in line:
                key, _, val = line.partition(": ")
                key = key.strip()
                try:
                    result["stats"][key] = int(val.strip())
                except ValueError:
                    result["stats"][key] = val.strip()

    # Extract key metrics from stats
    s = result["stats"]
    result["downstream_rq_total"] = s.get(
        "http.mcp_gateway.downstream_rq_total", 0
    )
    result["downstream_rq_active"] = s.get(
        "http.mcp_gateway.downstream_rq_active", 0
    )
    result["downstream_cx_total"] = s.get(
        "http.mcp_gateway.downstream_cx_total", 0
    )
    result["downstream_cx_active"] = s.get(
        "http.mcp_gateway.downstream_cx_active", 0
    )
    # Response codes
    for code in ["2xx", "4xx", "5xx"]:
        result[f"downstream_rq_{code}"] = s.get(
            f"http.mcp_gateway.downstream_rq_{code}", 0
        )
    # ext_authz stats
    for key in ["ok", "denied", "error", "failure_mode_allowed"]:
        result[f"ext_authz_{key}"] = s.get(
            f"http.mcp_gateway.ext_authz.ok", 0
        ) if key == "ok" else s.get(
            f"http.mcp_gateway.ext_authz.{key}", 0
        )

    # Parse clusters text
    if isinstance(clusters_text, str):
        current_cluster = None
        for line in clusters_text.strip().splitlines():
            line = line.strip()
            if line.endswith("::default_priority::max_connections::"):
                # cluster header line pattern
                continue
            if "::" in line and not line.startswith(" "):
                # Cluster name line, e.g. "opa_service::..."
                parts = line.split("::")
                current_cluster = parts[0]
                if current_cluster not in result["clusters"]:
                    result["clusters"][current_cluster] = {}
            elif current_cluster and "::" in line:
                parts = line.split("::")
                key = parts[-2] if len(parts) >= 2 else ""
                val = parts[-1] if len(parts) >= 1 else ""
                if key in ("membership_healthy", "membership_total", "rq_total",
                           "rq_success", "rq_error"):
                    try:
                        result["clusters"][current_cluster][key] = int(val)
                    except ValueError:
                        pass

    # Remove raw stats to keep response size small
    del result["stats"]
    return result


# --- API handler ---
class ThreadingHTTPServer(ThreadingMixIn, http.server.HTTPServer):
    daemon_threads = True


class DashboardHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        pass

    def do_HEAD(self):
        """Support HEAD requests (same as GET but no body)."""
        self.do_GET(_head=True)

    def send_json(self, data, status=200):
        body = json.dumps(data).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Connection", "close")
        self.end_headers()
        self.wfile.write(body)

    def send_error_json(self, status, message):
        self.send_json({"error": message}, status)

    def read_body(self) -> dict:
        length = int(self.headers.get("Content-Length", 0))
        if length == 0:
            return {}
        return json.loads(self.rfile.read(length))

    def npl_post(self, path, body=None):
        token = get_admin_token()
        return requests.post(
            f"{NPL_URL}{path}",
            headers={"Authorization": f"Bearer {token}", "Content-Type": "application/json"},
            json=body or {},
            timeout=15,
        )

    def get_session_user(self) -> str | None:
        """Check session cookie for authenticated user."""
        cookie = SimpleCookie(self.headers.get("Cookie", ""))
        token_morsel = cookie.get("session")
        if not token_morsel:
            return None
        return validate_session(token_morsel.value)

    def require_auth(self) -> str | None:
        """Returns username if authenticated, else sends 401 and returns None."""
        user = self.get_session_user()
        if not user:
            self.send_error_json(401, "Not authenticated")
            return None
        return user

    def do_OPTIONS(self):
        self.send_response(204)
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type")
        self.send_header("Content-Length", "0")
        self.send_header("Connection", "close")
        self.end_headers()

    def do_GET(self, _head=False):
        parsed = urlparse(self.path)
        path = parsed.path
        params = parse_qs(parsed.query)

        # --- Public routes (no auth) ---
        if path == "/api/auth/check":
            user = self.get_session_user()
            return self.send_json({"authenticated": user is not None, "username": user})

        # --- Protected API routes ---
        if path.startswith("/api/"):
            if not self.require_auth():
                return

            if path == "/api/metrics":
                return self.handle_metrics()
            if path == "/api/status":
                return self.handle_status()
            if path == "/api/bundle":
                return self.handle_bundle()
            if path == "/api/approvals":
                return self.handle_get_approvals()
            if path == "/api/users":
                return self.handle_get_users()
            if path == "/api/governance":
                return self.handle_get_governance()
            if path == "/api/dockerhub/search":
                return self.handle_dockerhub_search(params)
            if path == "/api/registry/search":
                return self.handle_registry_search(params)
            if path == "/api/docker/containers":
                return self.handle_docker_containers()
            if path == "/api/backends":
                return self.handle_get_backends()
            if path == "/api/suggestions":
                return self.handle_suggestions()
            if path == "/api/tools/schemas":
                return self.handle_tool_schemas(params)
            if path == "/api/constraints":
                return self.handle_get_constraints(params)
            if path == "/api/sse/npl":
                return self.handle_sse_proxy()
            return self.send_error_json(404, "Not found")

        # --- Static files ---
        if path == "/" or path == "":
            path = "/index.html"
        file_path = STATIC_DIR / path.lstrip("/")
        if file_path.is_file():
            content = file_path.read_bytes()
            content_type = "text/html"
            if path.endswith(".css"):
                content_type = "text/css"
            elif path.endswith(".js"):
                content_type = "application/javascript"
            elif path.endswith(".svg"):
                content_type = "image/svg+xml"
            elif path.endswith(".png"):
                content_type = "image/png"
            self.send_response(200)
            self.send_header("Content-Type", content_type)
            self.send_header("Content-Length", str(len(content)))
            self.send_header("Connection", "close")
            self.end_headers()
            if not _head:
                self.wfile.write(content)
        else:
            self.send_error_json(404, "Not found")

    def do_POST(self):
        path = urlparse(self.path).path
        body = self.read_body()

        # --- Login (no auth required) ---
        if path == "/api/auth/login":
            return self.handle_login(body)

        if path == "/api/auth/logout":
            return self.handle_logout()

        # --- Protected routes ---
        if not self.require_auth():
            return

        routes = {
            "/api/services/register": self.handle_register_service,
            "/api/services/enable": self.handle_enable_service,
            "/api/services/disable": self.handle_disable_service,
            "/api/services/remove": self.handle_remove_service,
            "/api/tools/register": self.handle_register_tool,
            "/api/tools/set-tag": self.handle_set_tag,
            "/api/tools/remove": self.handle_remove_tool,
            "/api/access-rules/add": self.handle_add_access_rule,
            "/api/access-rules/remove": self.handle_remove_access_rule,
            "/api/revoked/add": self.handle_revoke_subject,
            "/api/revoked/remove": self.handle_reinstate_subject,
            "/api/approvals/approve": self.handle_approve,
            "/api/approvals/deny": self.handle_deny,
            "/api/governance/create": self.handle_create_governance,
            "/api/docker/pull": self.handle_docker_pull,
            "/api/docker/wire": self.handle_docker_wire,
            "/api/docker/stop": self.handle_docker_stop,
            "/api/docker/discover": self.handle_docker_discover,
            "/api/users/create": self.handle_create_user,
            "/api/users/update": self.handle_update_user,
            "/api/users/delete": self.handle_delete_user,
            "/api/constraints/add": self.handle_add_constraint,
            "/api/constraints/remove": self.handle_remove_constraint,
            "/api/constraints/clear": self.handle_clear_constraints,
            "/api/governance/set-approval": self.handle_set_approval,
            "/api/governance/set-deadline": self.handle_set_deadline,
            "/api/governance/set-description": self.handle_set_description,
        }

        handler = routes.get(path)
        if handler:
            return handler(body)
        self.send_error_json(404, "Not found")

    # === Auth ===
    def handle_login(self, body):
        username = body.get("username", "")
        password = body.get("password", "")
        try:
            resp = requests.post(
                f"{KEYCLOAK_URL}/realms/{KEYCLOAK_REALM}/protocol/openid-connect/token",
                data={
                    "grant_type": "password",
                    "client_id": "mcpgateway",
                    "username": username,
                    "password": password,
                },
                timeout=10,
            )
            if resp.status_code != 200:
                return self.send_error_json(401, "Invalid credentials")

            # Check if user has admin role
            import base64
            token_data = resp.json()["access_token"]
            # JWT base64 padding fix
            payload_b64 = token_data.split(".")[1]
            payload_b64 += "=" * (4 - len(payload_b64) % 4)
            payload = json.loads(base64.b64decode(payload_b64))
            role = payload.get("role", "")
            # role can be a string or list
            roles = role if isinstance(role, list) else [role]
            if "admin" not in roles:
                return self.send_error_json(403, "Admin access required")

            session_token = create_session(username)
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Set-Cookie", f"session={session_token}; Path=/; HttpOnly; SameSite=Strict; Max-Age={SESSION_TTL}")
            self.send_header("Connection", "close")
            body = json.dumps({"ok": True, "username": username}).encode()
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            log.info("Login: %s", username)
        except Exception as e:
            log.error("Login failed: %s", e)
            self.send_error_json(500, str(e))

    def handle_logout(self):
        # Invalidate server-side session
        cookie = SimpleCookie(self.headers.get("Cookie", ""))
        token_morsel = cookie.get("session")
        if token_morsel and token_morsel.value in _sessions:
            del _sessions[token_morsel.value]
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Set-Cookie", "session=; Path=/; HttpOnly; Max-Age=0")
        self.send_header("Connection", "close")
        body = json.dumps({"ok": True}).encode()
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    # === Status ===
    def handle_status(self):
        status = {"timestamp": datetime.now(timezone.utc).isoformat()}
        checks = {
            "npl_engine": f"{NPL_URL}/actuator/health",
            "gateway": f"{GATEWAY_URL}/health",
            "opa": f"{OPA_URL}/health",
            "bundle_server": f"{BUNDLE_SERVER_URL}/health",
        }
        for name, url in checks.items():
            try:
                r = requests.get(url, timeout=3)
                status[name] = "healthy" if r.status_code < 400 else "unhealthy"
            except Exception:
                status[name] = "unreachable"
        try:
            r = requests.get(f"{KEYCLOAK_URL}/realms/{KEYCLOAK_REALM}", timeout=3)
            status["keycloak"] = "healthy" if r.status_code < 400 else "unhealthy"
        except Exception:
            status["keycloak"] = "unreachable"
        self.send_json(status)

    # === Metrics ===
    def handle_metrics(self):
        from concurrent.futures import ThreadPoolExecutor, as_completed

        def fetch(name, url):
            try:
                r = requests.get(url, timeout=3)
                ct = r.headers.get("content-type", "")
                if "text/plain" in ct:
                    return name, r.text, r.status_code
                return name, r.json(), r.status_code
            except Exception as e:
                return name, {"error": str(e)}, 0

        sources = {
            "envoy_stats": f"{ENVOY_ADMIN_URL}/stats?filter=http.mcp_gateway",
            "envoy_clusters": f"{ENVOY_ADMIN_URL}/clusters",
            "opa": f"{OPA_URL}/health",
            "bundle_server": f"{BUNDLE_SERVER_URL}/health",
            "governance_evaluator": f"{GOVERNANCE_EVALUATOR_URL}/health",
            "aggregator": f"{AGGREGATOR_URL}/health",
            "npl_engine": f"{NPL_URL}/actuator/health",
        }
        results = {}
        with ThreadPoolExecutor(max_workers=7) as pool:
            futures = {pool.submit(fetch, n, u): n for n, u in sources.items()}
            for f in as_completed(futures, timeout=5):
                try:
                    name, data, status = f.result()
                    results[name] = data
                except Exception:
                    pass
        results["envoy"] = _parse_envoy_stats(
            results.pop("envoy_stats", ""), results.pop("envoy_clusters", "")
        )
        self.send_json(results)

    # === Bundle data ===
    def handle_bundle(self):
        sid = get_store_id()
        if not sid:
            return self.send_json({"catalog": {}, "accessRules": [], "revokedSubjects": [], "storeId": None})
        try:
            gw_token = _get_gateway_token()
            resp = requests.post(
                f"{NPL_URL}/npl/store/GatewayStore/{sid}/getBundleData",
                headers={"Authorization": f"Bearer {gw_token}", "Content-Type": "application/json"},
                json={},
                timeout=10,
            )
            resp.raise_for_status()
            data = resp.json()
            data["storeId"] = sid
            self.send_json(data)
        except Exception as e:
            log.error("getBundleData failed: %s", e)
            self.send_error_json(500, str(e))

    # === Service CRUD ===
    def handle_register_service(self, body):
        sid = ensure_store_id()
        resp = self.npl_post(f"/npl/store/GatewayStore/{sid}/registerService", {"serviceName": body["serviceName"]})
        if resp.status_code < 400:
            self.npl_post(f"/npl/store/GatewayStore/{sid}/enableService", {"serviceName": body["serviceName"]})
            self.send_json({"ok": True})
        else:
            self.send_error_json(resp.status_code, resp.text)

    def handle_enable_service(self, body):
        sid = ensure_store_id()
        resp = self.npl_post(f"/npl/store/GatewayStore/{sid}/enableService", {"serviceName": body["serviceName"]})
        self.send_json({"ok": resp.status_code < 400}, resp.status_code if resp.status_code >= 400 else 200)

    def handle_disable_service(self, body):
        sid = ensure_store_id()
        resp = self.npl_post(f"/npl/store/GatewayStore/{sid}/disableService", {"serviceName": body["serviceName"]})
        self.send_json({"ok": resp.status_code < 400}, resp.status_code if resp.status_code >= 400 else 200)

    def handle_remove_service(self, body):
        sid = ensure_store_id()
        resp = self.npl_post(f"/npl/store/GatewayStore/{sid}/removeService", {"serviceName": body["serviceName"]})
        self.send_json({"ok": resp.status_code < 400}, resp.status_code if resp.status_code >= 400 else 200)

    # === Tool CRUD ===
    def handle_register_tool(self, body):
        sid = ensure_store_id()
        resp = self.npl_post(f"/npl/store/GatewayStore/{sid}/registerTool", {
            "serviceName": body["serviceName"], "toolName": body["toolName"], "tag": body.get("tag", "open"),
        })
        self.send_json({"ok": resp.status_code < 400}, resp.status_code if resp.status_code >= 400 else 200)

    def handle_set_tag(self, body):
        sid = ensure_store_id()
        resp = self.npl_post(f"/npl/store/GatewayStore/{sid}/setTag", {
            "serviceName": body["serviceName"], "toolName": body["toolName"], "tag": body["tag"],
        })
        self.send_json({"ok": resp.status_code < 400}, resp.status_code if resp.status_code >= 400 else 200)

    def handle_remove_tool(self, body):
        sid = ensure_store_id()
        resp = self.npl_post(f"/npl/store/GatewayStore/{sid}/removeTool", {
            "serviceName": body["serviceName"], "toolName": body["toolName"],
        })
        self.send_json({"ok": resp.status_code < 400}, resp.status_code if resp.status_code >= 400 else 200)

    # === Access rules ===
    def handle_add_access_rule(self, body):
        sid = ensure_store_id()
        resp = self.npl_post(f"/npl/store/GatewayStore/{sid}/addAccessRule", {
            "id": body["id"], "matchType": body["matchType"],
            "matchClaims": body.get("matchClaims", {}), "matchIdentity": body.get("matchIdentity", ""),
            "allowServices": body.get("allowServices", []), "allowTools": body.get("allowTools", ["*"]),
        })
        self.send_json({"ok": resp.status_code < 400}, resp.status_code if resp.status_code >= 400 else 200)

    def handle_remove_access_rule(self, body):
        sid = ensure_store_id()
        resp = self.npl_post(f"/npl/store/GatewayStore/{sid}/removeAccessRule", {"id": body["id"]})
        self.send_json({"ok": resp.status_code < 400}, resp.status_code if resp.status_code >= 400 else 200)

    # === Revocation ===
    def handle_revoke_subject(self, body):
        sid = ensure_store_id()
        resp = self.npl_post(f"/npl/store/GatewayStore/{sid}/revokeSubject", {"subjectId": body["subjectId"]})
        self.send_json({"ok": resp.status_code < 400}, resp.status_code if resp.status_code >= 400 else 200)

    def handle_reinstate_subject(self, body):
        sid = ensure_store_id()
        resp = self.npl_post(f"/npl/store/GatewayStore/{sid}/reinstateSubject", {"subjectId": body["subjectId"]})
        self.send_json({"ok": resp.status_code < 400}, resp.status_code if resp.status_code >= 400 else 200)

    # === Governance ===
    def handle_get_governance(self):
        self.send_json({"instances": get_governance_instances()})

    def handle_create_governance(self, body):
        svc = body["serviceName"]
        instances = get_governance_instances()
        if svc in instances:
            return self.send_json({"ok": True, "instanceId": instances[svc]})
        token = get_admin_token()
        resp = requests.post(
            f"{NPL_URL}/npl/governance/ServiceGovernance/",
            headers={"Authorization": f"Bearer {token}", "Content-Type": "application/json"},
            json={"@parties": {}}, timeout=10,
        )
        resp.raise_for_status()
        instance_id = resp.json()["@id"]
        requests.post(
            f"{NPL_URL}/npl/governance/ServiceGovernance/{instance_id}/setup",
            headers={"Authorization": f"Bearer {token}", "Content-Type": "application/json"},
            json={"name": svc}, timeout=10,
        )
        self.send_json({"ok": True, "instanceId": instance_id})

    # === Approvals ===
    def handle_get_approvals(self):
        instances = get_governance_instances()
        all_pending = []
        token = get_admin_token()
        for svc, iid in instances.items():
            try:
                resp = requests.post(
                    f"{NPL_URL}/npl/governance/ServiceGovernance/{iid}/getPendingRequests",
                    headers={"Authorization": f"Bearer {token}", "Content-Type": "application/json"},
                    json={}, timeout=10,
                )
                if resp.status_code < 400:
                    for req in resp.json():
                        req["_serviceName"] = svc
                        req["_instanceId"] = iid
                        all_pending.append(req)
            except Exception as e:
                log.warning("getPendingRequests failed for %s: %s", svc, e)
        self.send_json({"pending": all_pending})

    def handle_approve(self, body):
        token = get_admin_token()
        resp = requests.post(
            f"{NPL_URL}/npl/governance/ServiceGovernance/{body['instanceId']}/approve",
            headers={"Authorization": f"Bearer {token}", "Content-Type": "application/json"},
            json={"requestId": body["requestId"]}, timeout=10,
        )
        self.send_json({"ok": resp.status_code < 400}, resp.status_code if resp.status_code >= 400 else 200)

    def handle_deny(self, body):
        token = get_admin_token()
        resp = requests.post(
            f"{NPL_URL}/npl/governance/ServiceGovernance/{body['instanceId']}/deny",
            headers={"Authorization": f"Bearer {token}", "Content-Type": "application/json"},
            json={"requestId": body["requestId"], "reason": body.get("reason", "Denied by admin")}, timeout=10,
        )
        self.send_json({"ok": resp.status_code < 400}, resp.status_code if resp.status_code >= 400 else 200)

    # === Docker Hub search ===
    def handle_dockerhub_search(self, params):
        """Search Docker Hub for mcp/* images."""
        query = params.get("q", [""])[0].strip()
        try:
            images = []
            if query:
                # Use Docker Hub search API for text queries
                resp = requests.get(
                    "https://hub.docker.com/v2/search/repositories",
                    params={"query": f"mcp {query}", "page_size": 50},
                    timeout=15,
                )
                resp.raise_for_status()
                for r in resp.json().get("results", []):
                    repo = r.get("repo_name", "")
                    if not repo.startswith("mcp/"):
                        continue
                    name = repo.split("/", 1)[1] if "/" in repo else repo
                    images.append({
                        "name": name,
                        "namespace": "mcp",
                        "full_name": repo,
                        "description": r.get("short_description", "") or r.get("description", ""),
                        "pull_count": r.get("pull_count", 0),
                        "star_count": r.get("star_count", 0),
                        "last_updated": "",
                    })
            else:
                # Browse all mcp/* images (paginated, sorted by popularity)
                for page in range(1, 4):  # up to 300 images
                    resp = requests.get(
                        "https://hub.docker.com/v2/repositories/mcp/",
                        params={"page_size": 100, "ordering": "-pull_count", "page": page},
                        timeout=15,
                    )
                    if resp.status_code != 200:
                        break
                    results = resp.json().get("results", [])
                    if not results:
                        break
                    for r in results:
                        images.append({
                            "name": r.get("name", ""),
                            "namespace": "mcp",
                            "full_name": f"mcp/{r.get('name', '')}",
                            "description": r.get("description", ""),
                            "pull_count": r.get("pull_count", 0),
                            "star_count": r.get("star_count", 0),
                            "last_updated": r.get("last_updated", ""),
                        })
            self.send_json({"images": images, "count": len(images)})
        except Exception as e:
            log.warning("Docker Hub search failed: %s", e)
            self.send_error_json(502, f"Docker Hub unavailable: {e}")

    # === MCP Registry search ===
    def handle_registry_search(self, params):
        try:
            query_params = {"limit": "30", "version": "latest"}
            search = params.get("q", [""])[0]
            if search:
                query_params["search"] = search
            resp = requests.get(MCP_REGISTRY_URL, params=query_params, timeout=15)
            resp.raise_for_status()
            self.send_json(resp.json())
        except Exception as e:
            log.warning("MCP Registry search failed: %s", e)
            self.send_error_json(502, f"Registry unavailable: {e}")

    # === Docker container management ===
    def handle_docker_containers(self):
        """List running MCP sidecar containers."""
        client = get_docker_client()
        if not client:
            return self.send_json({"containers": [], "docker_available": False})
        try:
            containers = client.containers.list(all=True)
            mcp_containers = []
            for c in containers:
                env = {e.split("=", 1)[0]: e.split("=", 1)[1] for e in (c.attrs.get("Config", {}).get("Env", []) or []) if "=" in e}
                mcp_cmd = env.get("MCP_COMMAND", "")
                if mcp_cmd or "mcp" in c.name.lower():
                    mcp_containers.append({
                        "id": c.short_id,
                        "name": c.name,
                        "status": c.status,
                        "image": c.image.tags[0] if c.image.tags else str(c.image.id)[:20],
                        "mcp_command": mcp_cmd,
                    })
            self.send_json({"containers": mcp_containers, "docker_available": True})
        except Exception as e:
            log.error("Docker containers list failed: %s", e)
            self.send_error_json(500, str(e))

    def handle_docker_pull(self, body):
        """Pull a Docker image."""
        image = body.get("image", "")
        if not image:
            return self.send_error_json(400, "image required")
        client = get_docker_client()
        if not client:
            return self.send_error_json(503, "Docker not available")
        try:
            log.info("Pulling image: %s", image)
            client.images.pull(image)
            self.send_json({"ok": True, "image": image})
        except Exception as e:
            log.error("Docker pull failed for %s: %s", image, e)
            self.send_error_json(500, f"Pull failed: {e}")

    def handle_docker_wire(self, body):
        """Full wire flow with SSE streaming progress."""
        image = body.get("image", "")  # e.g. "mcp/slack"
        service_name = body.get("serviceName", "")  # e.g. "slack"
        if not image or not service_name:
            return self.send_error_json(400, "image and serviceName required")

        client = get_docker_client()
        if not client:
            return self.send_error_json(503, "Docker not available")

        # Stream progress as SSE
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Cache-Control", "no-cache")
        self.send_header("Connection", "close")
        self.end_headers()

        def emit(step, status="progress"):
            msg = json.dumps({"step": step, "status": status})
            self.wfile.write(f"data: {msg}\n\n".encode())
            self.wfile.flush()

        container_name = f"gateway-{service_name}-mcp-1"
        tools = []

        try:
            # Step 1: Pull MCP image
            emit(f"Pulling {image}...")
            log.info("Wire: pulling %s", image)
            # Stream pull progress
            for line in client.api.pull(image, stream=True, decode=True):
                status = line.get("status", "")
                prog = line.get("progress", "")
                layer = line.get("id", "")
                if prog:
                    emit(f"  {layer}: {status} {prog}", "pulling")
                elif status:
                    emit(f"  {status} {layer}", "pulling")
            emit(f"Pulled {image}", "done")

            # Step 2: Find supergateway image
            emit("Finding supergateway image...")
            sg_image = find_supergateway_image()
            if not sg_image:
                emit("Supergateway image not found. Ensure the gateway stack is running.", "error")
                return
            emit("Found supergateway image", "done")

            # Step 3: Stop existing container (and its inner MCP container) if any
            try:
                inner = client.containers.list(
                    filters={"label": f"gateway.parent={container_name}"})
                for c in inner:
                    log.info("Removing old inner MCP container %s", c.name)
                    c.remove(force=True)
            except Exception:
                pass
            try:
                old = client.containers.get(container_name)
                emit(f"Removing old container {container_name}...")
                old.remove(force=True)
                emit("Removed old container", "done")
            except docker.errors.NotFound:
                pass

            # Step 4: Start supergateway container
            emit(f"Starting {container_name}...")
            log.info("Wire: starting %s", container_name)
            client.containers.run(
                sg_image,
                name=container_name,
                environment={
                    "MCP_COMMAND": f"docker run -i --rm --label gateway.parent={container_name} {image}",
                    "PORT": "8000",
                },
                volumes={"/var/run/docker.sock": {"bind": "/var/run/docker.sock", "mode": "rw"}},
                network=DOCKER_NETWORK,
                detach=True,
            )
            emit(f"Started {container_name}", "done")

            # Step 5: Wait for container to be ready
            emit("Waiting for container to initialize (5s)...")
            time.sleep(5)
            emit("Container ready", "done")

            # Step 6: Register backend with aggregator
            backend_url = f"http://{container_name}:8000"
            emit(f"Registering backend with aggregator...")
            try:
                requests.post(
                    f"{AGGREGATOR_URL}/backends",
                    json={"name": service_name, "url": backend_url},
                    timeout=10,
                )
                emit(f"Registered backend: {service_name}", "done")
            except Exception as e:
                emit(f"Aggregator registration failed: {e}", "warn")

            # Step 7: Discover tools via MCP handshake
            emit("Discovering tools via MCP handshake...")
            try:
                tools = self._discover_tools(backend_url)
                emit(f"Discovered {len(tools)} tools: {', '.join(t['name'] for t in tools)}", "done")
            except Exception as e:
                emit(f"Tool discovery failed (service may need configuration): {e}", "warn")

            # Step 8: Register service + tools in GatewayStore
            emit("Registering in governance catalog...")
            sid = ensure_store_id()
            self.npl_post(f"/npl/store/GatewayStore/{sid}/registerService", {"serviceName": service_name})
            self.npl_post(f"/npl/store/GatewayStore/{sid}/enableService", {"serviceName": service_name})
            for tool in tools:
                self.npl_post(f"/npl/store/GatewayStore/{sid}/registerTool", {
                    "serviceName": service_name, "toolName": tool["name"], "tag": "gated",
                })
            emit(f"Registered {service_name} with {len(tools)} tools (all gated) in catalog", "done")

            # Step 9: Auto-create governance instance for approval workflow
            emit("Creating governance instance...")
            try:
                token = get_admin_token()
                instances = get_governance_instances()
                if service_name not in instances:
                    resp = requests.post(
                        f"{NPL_URL}/npl/governance/ServiceGovernance/",
                        headers={"Authorization": f"Bearer {token}", "Content-Type": "application/json"},
                        json={"@parties": {}}, timeout=10,
                    )
                    resp.raise_for_status()
                    instance_id = resp.json()["@id"]
                    requests.post(
                        f"{NPL_URL}/npl/governance/ServiceGovernance/{instance_id}/setup",
                        headers={"Authorization": f"Bearer {token}", "Content-Type": "application/json"},
                        json={"name": service_name}, timeout=10,
                    )
                    emit(f"Governance enabled for {service_name}", "done")
                else:
                    emit("Governance already active", "done")
            except Exception as e:
                emit(f"Governance creation failed: {e}", "warn")

            # Final success message
            emit(f"Service \"{service_name}\" wired successfully!", "complete")

        except (BrokenPipeError, ConnectionResetError):
            pass
        except Exception as e:
            log.error("Wire failed for %s: %s", service_name, e)
            try:
                emit(f"Error: {e}", "error")
            except Exception:
                pass

    def handle_docker_stop(self, body):
        """Stop and remove a wired service container and its inner MCP container."""
        container_name = body.get("containerName", "")
        service_name = body.get("serviceName", "")
        client = get_docker_client()
        if not client:
            return self.send_error_json(503, "Docker not available")
        try:
            # Remove inner MCP container spawned by supergateway (labeled)
            try:
                inner = client.containers.list(
                    filters={"label": f"gateway.parent={container_name}"})
                for c in inner:
                    log.info("Removing inner MCP container %s", c.name)
                    c.remove(force=True)
            except Exception as e:
                log.warning("Failed to clean inner containers for %s: %s", container_name, e)
            # Remove the supergateway container itself
            container = client.containers.get(container_name)
            container.remove(force=True)
            # Remove from aggregator
            if service_name:
                try:
                    requests.delete(f"{AGGREGATOR_URL}/backends/{service_name}", timeout=10)
                except Exception:
                    pass
            self.send_json({"ok": True})
        except docker.errors.NotFound:
            self.send_error_json(404, f"Container {container_name} not found")
        except Exception as e:
            self.send_error_json(500, str(e))

    def handle_docker_discover(self, body):
        """Discover tools from a running MCP service container."""
        url = body.get("url", "")
        if not url:
            return self.send_error_json(400, "url required")
        try:
            tools = self._discover_tools(url)
            self.send_json({"tools": tools})
        except Exception as e:
            self.send_error_json(500, f"Discovery failed: {e}")

    def _discover_tools(self, backend_url: str) -> list:
        """MCP handshake to discover tools from a Streamable HTTP backend."""
        mcp_url = f"{backend_url}/mcp"
        headers = {"Content-Type": "application/json", "Accept": "application/json, text/event-stream"}

        # Initialize
        init_resp = requests.post(mcp_url, headers=headers, json={
            "jsonrpc": "2.0", "id": 1, "method": "initialize",
            "params": {"protocolVersion": "2025-03-26", "clientInfo": {"name": "dashboard", "version": "1.0"}, "capabilities": {}},
        }, timeout=15)
        session_id = init_resp.headers.get("mcp-session-id", "")

        # Parse response (may be SSE)
        init_body = self._parse_mcp_response(init_resp)
        if not init_body.get("result"):
            return []

        # Send initialized notification
        hdrs = {**headers, "Mcp-Session-Id": session_id} if session_id else headers
        requests.post(mcp_url, headers=hdrs, json={
            "jsonrpc": "2.0", "method": "notifications/initialized",
        }, timeout=10)

        # List tools
        tools_resp = requests.post(mcp_url, headers=hdrs, json={
            "jsonrpc": "2.0", "id": 2, "method": "tools/list", "params": {},
        }, timeout=15)
        tools_body = self._parse_mcp_response(tools_resp)
        raw_tools = tools_body.get("result", {}).get("tools", [])
        return [{"name": t["name"], "description": t.get("description", ""), "inputSchema": t.get("inputSchema", {})} for t in raw_tools]

    def _parse_mcp_response(self, resp) -> dict:
        """Parse a response that may be JSON or SSE."""
        ct = resp.headers.get("content-type", "")
        if "text/event-stream" in ct:
            for line in resp.text.split("\n"):
                if line.startswith("data: "):
                    try:
                        return json.loads(line[6:])
                    except json.JSONDecodeError:
                        continue
            return {}
        return resp.json()

    # === Aggregator backends ===
    def handle_get_backends(self):
        try:
            resp = requests.get(f"{AGGREGATOR_URL}/backends", timeout=10)
            self.send_json(resp.json())
        except Exception as e:
            self.send_error_json(502, f"Aggregator unreachable: {e}")

    # === Suggestions (for form dropdowns) ===
    def handle_suggestions(self):
        """Return catalog data for populating form dropdowns."""
        suggestions = {"services": [], "tools": {}, "users": [], "departments": [], "organizations": [], "roles": []}
        try:
            # Get catalog
            sid = get_store_id()
            if sid:
                gw_token = _get_gateway_token()
                resp = requests.post(
                    f"{NPL_URL}/npl/store/GatewayStore/{sid}/getBundleData",
                    headers={"Authorization": f"Bearer {gw_token}", "Content-Type": "application/json"},
                    json={}, timeout=10,
                )
                if resp.status_code < 400:
                    data = resp.json()
                    catalog = data.get("catalog", {})
                    suggestions["services"] = list(catalog.keys())
                    for svc, info in catalog.items():
                        suggestions["tools"][svc] = list((info.get("tools") or {}).keys())

            # Get users for identity suggestions
            admin_resp = requests.post(
                f"{KEYCLOAK_URL}/realms/master/protocol/openid-connect/token",
                data={"grant_type": "password", "client_id": "admin-cli", "username": ADMIN_USERNAME, "password": "welcome"},
                timeout=10,
            )
            admin_token = admin_resp.json()["access_token"]
            users_resp = requests.get(
                f"{KEYCLOAK_URL}/admin/realms/{KEYCLOAK_REALM}/users",
                headers={"Authorization": f"Bearer {admin_token}"},
                params={"max": 100}, timeout=10,
            )
            if users_resp.status_code < 400:
                depts = set()
                orgs = set()
                roles = {"user", "admin", "gateway"}
                for u in users_resp.json():
                    email = u.get("email", "")
                    if email:
                        suggestions["users"].append(email)
                    attrs = u.get("attributes", {})
                    dept = (attrs.get("department") or [""])[0]
                    org = (attrs.get("organization") or [""])[0]
                    role = (attrs.get("role") or [""])[0]
                    if dept:
                        depts.add(dept)
                    if org:
                        orgs.add(org)
                    if role:
                        roles.add(role)
                suggestions["departments"] = sorted(depts)
                suggestions["organizations"] = sorted(orgs)
                suggestions["roles"] = sorted(roles)
        except Exception as e:
            log.warning("Suggestions fetch failed: %s", e)

        self.send_json(suggestions)

    # === SSE proxy (NPL Engine event stream) ===
    def handle_sse_proxy(self):
        """Proxy SSE stream from NPL Engine to browser.

        Re-emits upstream events as standard 'message' events so
        EventSource.onmessage fires in the browser.
        """
        upstream = None
        try:
            token = get_admin_token()
            upstream = requests.get(
                f"{NPL_URL}/api/streams/states",
                headers={"Authorization": f"Bearer {token}", "Accept": "text/event-stream"},
                stream=True,
                timeout=(5, None),  # 5s connect timeout, no read timeout
            )
            self.send_response(200)
            self.send_header("Content-Type", "text/event-stream")
            self.send_header("Cache-Control", "no-cache")
            self.send_header("X-Accel-Buffering", "no")
            self.send_header("Connection", "close")
            self.end_headers()

            # Collect lines into complete SSE events, re-emit as 'message' type
            buf = []
            for line in upstream.iter_lines(decode_unicode=True):
                if line is None:
                    continue
                if line == "":
                    # End of event — emit collected data lines as a 'message' event
                    data_parts = [l.split(":", 1)[1].strip() if l.startswith("data:") else ""
                                  for l in buf if l.startswith("data:")]
                    if data_parts:
                        for dp in data_parts:
                            self.wfile.write(f"data: {dp}\n".encode())
                        self.wfile.write(b"\n")
                        self.wfile.flush()
                    buf = []
                else:
                    buf.append(line)
        except (BrokenPipeError, ConnectionResetError, OSError):
            pass  # Client disconnected — normal for SSE
        except Exception as e:
            log.warning("SSE proxy error: %s", e)
            try:
                self.send_response(200)
                self.send_header("Content-Type", "text/event-stream")
                self.send_header("Cache-Control", "no-cache")
                self.send_header("Connection", "close")
                self.end_headers()
                self.wfile.write(f"data: {{\"error\": \"{e}\"}}\n\n".encode())
                self.wfile.flush()
            except Exception:
                pass
        finally:
            if upstream:
                upstream.close()

    # === Tool schemas ===
    def handle_tool_schemas(self, params):
        """Discover tool schemas from a running MCP backend service."""
        service = params.get("service", [""])[0]
        if not service:
            return self.send_error_json(400, "service parameter required")
        # Find backend URL from aggregator
        try:
            resp = requests.get(f"{AGGREGATOR_URL}/backends", timeout=10)
            backends = resp.json().get("backends", resp.json()) if resp.status_code < 400 else {}
            backend_url = backends.get(service)
            if not backend_url:
                return self.send_error_json(404, f"No backend for service '{service}'")
            tools = self._discover_tools(backend_url)
            self.send_json({"service": service, "tools": tools})
        except Exception as e:
            self.send_error_json(500, f"Schema discovery failed: {e}")

    # === Constraints CRUD ===
    def handle_get_constraints(self, params):
        """Get all tool configs/constraints for a service."""
        service = params.get("service", [""])[0]
        instances = get_governance_instances()
        if service:
            iid = instances.get(service)
            if not iid:
                return self.send_json({"configs": [], "service": service})
            try:
                token = _get_gateway_token()
                resp = requests.post(
                    f"{NPL_URL}/npl/governance/ServiceGovernance/{iid}/getToolConfigs",
                    headers={"Authorization": f"Bearer {token}", "Content-Type": "application/json"},
                    json={}, timeout=10,
                )
                if resp.status_code < 400:
                    return self.send_json({"configs": resp.json(), "service": service})
                return self.send_json({"configs": [], "service": service})
            except Exception as e:
                return self.send_error_json(500, str(e))
        # All services
        all_configs = {}
        token = _get_gateway_token()
        for svc, iid in instances.items():
            try:
                resp = requests.post(
                    f"{NPL_URL}/npl/governance/ServiceGovernance/{iid}/getToolConfigs",
                    headers={"Authorization": f"Bearer {token}", "Content-Type": "application/json"},
                    json={}, timeout=10,
                )
                if resp.status_code < 400:
                    all_configs[svc] = resp.json()
            except Exception:
                pass
        self.send_json({"configs": all_configs})

    def handle_add_constraint(self, body):
        """Add a constraint to a tool in a ServiceGovernance instance."""
        service = body.get("serviceName", "")
        instances = get_governance_instances()
        iid = instances.get(service)
        if not iid:
            return self.send_error_json(404, f"No governance instance for '{service}'")
        token = get_admin_token()
        resp = requests.post(
            f"{NPL_URL}/npl/governance/ServiceGovernance/{iid}/addConstraint",
            headers={"Authorization": f"Bearer {token}", "Content-Type": "application/json"},
            json={
                "toolName": body.get("toolName", ""),
                "paramName": body.get("paramName", ""),
                "operator": body.get("operator", ""),
                "values": body.get("values", []),
                "description": body.get("description", ""),
            },
            timeout=10,
        )
        self.send_json({"ok": resp.status_code < 400}, resp.status_code if resp.status_code >= 400 else 200)

    def handle_remove_constraint(self, body):
        service = body.get("serviceName", "")
        instances = get_governance_instances()
        iid = instances.get(service)
        if not iid:
            return self.send_error_json(404, f"No governance instance for '{service}'")
        token = get_admin_token()
        resp = requests.post(
            f"{NPL_URL}/npl/governance/ServiceGovernance/{iid}/removeConstraint",
            headers={"Authorization": f"Bearer {token}", "Content-Type": "application/json"},
            json={"toolName": body.get("toolName", ""), "paramName": body.get("paramName", "")},
            timeout=10,
        )
        self.send_json({"ok": resp.status_code < 400}, resp.status_code if resp.status_code >= 400 else 200)

    def handle_clear_constraints(self, body):
        service = body.get("serviceName", "")
        instances = get_governance_instances()
        iid = instances.get(service)
        if not iid:
            return self.send_error_json(404, f"No governance instance for '{service}'")
        token = get_admin_token()
        resp = requests.post(
            f"{NPL_URL}/npl/governance/ServiceGovernance/{iid}/clearConstraints",
            headers={"Authorization": f"Bearer {token}", "Content-Type": "application/json"},
            json={"toolName": body.get("toolName", "")},
            timeout=10,
        )
        self.send_json({"ok": resp.status_code < 400}, resp.status_code if resp.status_code >= 400 else 200)

    def handle_set_approval(self, body):
        service = body.get("serviceName", "")
        instances = get_governance_instances()
        iid = instances.get(service)
        if not iid:
            return self.send_error_json(404, f"No governance instance for '{service}'")
        token = get_admin_token()
        resp = requests.post(
            f"{NPL_URL}/npl/governance/ServiceGovernance/{iid}/setRequiresApproval",
            headers={"Authorization": f"Bearer {token}", "Content-Type": "application/json"},
            json={"toolName": body.get("toolName", ""), "required": body.get("required", True)},
            timeout=10,
        )
        self.send_json({"ok": resp.status_code < 400}, resp.status_code if resp.status_code >= 400 else 200)

    def handle_set_deadline(self, body):
        service = body.get("serviceName", "")
        instances = get_governance_instances()
        iid = instances.get(service)
        if not iid:
            return self.send_error_json(404, f"No governance instance for '{service}'")
        token = get_admin_token()
        resp = requests.post(
            f"{NPL_URL}/npl/governance/ServiceGovernance/{iid}/setApprovalDeadline",
            headers={"Authorization": f"Bearer {token}", "Content-Type": "application/json"},
            json={"deadlineHours": body.get("deadlineHours", 168)},
            timeout=10,
        )
        self.send_json({"ok": resp.status_code < 400}, resp.status_code if resp.status_code >= 400 else 200)

    def handle_set_description(self, body):
        service = body.get("serviceName", "")
        instances = get_governance_instances()
        iid = instances.get(service)
        if not iid:
            return self.send_error_json(404, f"No governance instance for '{service}'")
        token = get_admin_token()
        resp = requests.post(
            f"{NPL_URL}/npl/governance/ServiceGovernance/{iid}/setGovernanceDescription",
            headers={"Authorization": f"Bearer {token}", "Content-Type": "application/json"},
            json={"desc": body.get("description", "")},
            timeout=10,
        )
        self.send_json({"ok": resp.status_code < 400}, resp.status_code if resp.status_code >= 400 else 200)

    # === User management (Keycloak admin) ===
    def _get_kc_admin_token(self) -> str:
        resp = requests.post(
            f"{KEYCLOAK_URL}/realms/master/protocol/openid-connect/token",
            data={"grant_type": "password", "client_id": "admin-cli", "username": ADMIN_USERNAME, "password": "welcome"},
            timeout=10,
        )
        return resp.json()["access_token"]

    def handle_create_user(self, body):
        try:
            token = self._get_kc_admin_token()
            user_data = {
                "username": body["username"],
                "email": body.get("email", ""),
                "firstName": body.get("firstName", ""),
                "lastName": body.get("lastName", ""),
                "enabled": True,
                "emailVerified": False,
                "attributes": {
                    "department": [body.get("department", "")],
                    "organization": [body.get("organization", "")],
                    "role": [body.get("role", "user")],
                },
            }
            password = body.get("password", "")
            if password:
                user_data["credentials"] = [{"type": "password", "value": password, "temporary": False}]

            resp = requests.post(
                f"{KEYCLOAK_URL}/admin/realms/{KEYCLOAK_REALM}/users",
                headers={"Authorization": f"Bearer {token}", "Content-Type": "application/json"},
                json=user_data, timeout=10,
            )
            if resp.status_code == 409:
                return self.send_error_json(409, "User already exists")
            resp.raise_for_status()
            location = resp.headers.get("Location", "")
            user_id = location.split("/")[-1] if location else ""
            self.send_json({"ok": True, "userId": user_id})
        except Exception as e:
            self.send_error_json(500, str(e))

    def handle_update_user(self, body):
        try:
            token = self._get_kc_admin_token()
            user_id = body["userId"]
            update = {}
            if "email" in body:
                update["email"] = body["email"]
            if "firstName" in body:
                update["firstName"] = body["firstName"]
            if "lastName" in body:
                update["lastName"] = body["lastName"]
            if "enabled" in body:
                update["enabled"] = body["enabled"]
            attrs = {}
            if "department" in body:
                attrs["department"] = [body["department"]]
            if "organization" in body:
                attrs["organization"] = [body["organization"]]
            if "role" in body:
                attrs["role"] = [body["role"]]
            if attrs:
                update["attributes"] = attrs

            resp = requests.put(
                f"{KEYCLOAK_URL}/admin/realms/{KEYCLOAK_REALM}/users/{user_id}",
                headers={"Authorization": f"Bearer {token}", "Content-Type": "application/json"},
                json=update, timeout=10,
            )
            resp.raise_for_status()

            # Update password if provided
            if body.get("password"):
                requests.put(
                    f"{KEYCLOAK_URL}/admin/realms/{KEYCLOAK_REALM}/users/{user_id}/reset-password",
                    headers={"Authorization": f"Bearer {token}", "Content-Type": "application/json"},
                    json={"type": "password", "value": body["password"], "temporary": False},
                    timeout=10,
                )

            self.send_json({"ok": True})
        except Exception as e:
            self.send_error_json(500, str(e))

    def handle_delete_user(self, body):
        try:
            token = self._get_kc_admin_token()
            resp = requests.delete(
                f"{KEYCLOAK_URL}/admin/realms/{KEYCLOAK_REALM}/users/{body['userId']}",
                headers={"Authorization": f"Bearer {token}"},
                timeout=10,
            )
            resp.raise_for_status()
            self.send_json({"ok": True})
        except Exception as e:
            self.send_error_json(500, str(e))

    # === Users list ===
    def handle_get_users(self):
        try:
            admin_resp = requests.post(
                f"{KEYCLOAK_URL}/realms/master/protocol/openid-connect/token",
                data={"grant_type": "password", "client_id": "admin-cli", "username": ADMIN_USERNAME, "password": "welcome"},
                timeout=10,
            )
            admin_token = admin_resp.json()["access_token"]
            resp = requests.get(
                f"{KEYCLOAK_URL}/admin/realms/{KEYCLOAK_REALM}/users",
                headers={"Authorization": f"Bearer {admin_token}"},
                params={"max": 100}, timeout=10,
            )
            resp.raise_for_status()
            users = []
            for u in resp.json():
                attrs = u.get("attributes", {})
                users.append({
                    "id": u["id"],
                    "username": u.get("username", ""),
                    "email": u.get("email", ""),
                    "firstName": u.get("firstName", ""),
                    "lastName": u.get("lastName", ""),
                    "enabled": u.get("enabled", False),
                    "department": (attrs.get("department") or [""])[0],
                    "organization": (attrs.get("organization") or [""])[0],
                    "role": (attrs.get("role") or [""])[0],
                })
            self.send_json({"users": users})
        except Exception as e:
            log.error("Keycloak user fetch failed: %s", e)
            self.send_error_json(500, str(e))


def main():
    log.info("MCP Gateway Dashboard starting on port %d", PORT)
    log.info("  NPL Engine:     %s", NPL_URL)
    log.info("  Keycloak:       %s", KEYCLOAK_URL)
    log.info("  Aggregator:     %s", AGGREGATOR_URL)
    log.info("  Docker network: %s", DOCKER_NETWORK)
    log.info("  Docker SDK:     %s", "available" if DOCKER_AVAILABLE else "NOT AVAILABLE")
    log.info("  Static dir:     %s", STATIC_DIR)
    server = ThreadingHTTPServer(("0.0.0.0", PORT), DashboardHandler)
    log.info("Dashboard ready at http://localhost:%d", PORT)
    server.serve_forever()


if __name__ == "__main__":
    main()
