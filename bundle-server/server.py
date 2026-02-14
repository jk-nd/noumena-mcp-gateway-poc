"""
OPA Bundle Server — serves NPL policy data as an OPA bundle.

Subscribes to NPL Engine's SSE Streams API for push notifications of protocol
state changes. Rebuilds the OPA bundle on every mutation and serves it via the
native OPA bundle protocol (tar.gz with data.json + .manifest).

Two threads:
  1. SSE listener — connects to NPL /api/streams/states, triggers rebuild on state events
  2. HTTP server  — serves GET /bundles/mcp/data.tar.gz (with ETag) and GET /health
"""

import hashlib
import io
import json
import logging
import os
import tarfile
import threading
import time
from http.server import BaseHTTPRequestHandler, HTTPServer

import requests
import sseclient

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(message)s",
    datefmt="%Y-%m-%dT%H:%M:%S",
)
log = logging.getLogger("bundle-server")

# --- Configuration from environment ---
NPL_URL = os.environ.get("NPL_URL", "http://localhost:12000")
KEYCLOAK_URL = os.environ.get("KEYCLOAK_URL", "http://localhost:11000")
KEYCLOAK_REALM = os.environ.get("KEYCLOAK_REALM", "mcpgateway")
GATEWAY_USERNAME = os.environ.get("GATEWAY_USERNAME", "gateway")
GATEWAY_PASSWORD = os.environ.get("GATEWAY_PASSWORD", "Welcome123")
PORT = int(os.environ.get("PORT", "8282"))

# --- Shared state ---
bundle_lock = threading.Lock()
current_bundle: bytes | None = None
current_etag: str | None = None
data_ready = threading.Event()
rebuild_signal = threading.Event()


# --- Keycloak token management ---
class TokenManager:
    """Fetches and caches gateway JWT from Keycloak."""

    def __init__(self):
        self._token: str | None = None
        self._expires_at: float = 0
        self._lock = threading.Lock()

    def get_token(self) -> str:
        with self._lock:
            if self._token and time.time() < self._expires_at:
                return self._token
            self._refresh()
            return self._token

    def _refresh(self):
        url = f"{KEYCLOAK_URL}/realms/{KEYCLOAK_REALM}/protocol/openid-connect/token"
        resp = requests.post(
            url,
            data={
                "grant_type": "password",
                "client_id": "mcpgateway",
                "username": GATEWAY_USERNAME,
                "password": GATEWAY_PASSWORD,
            },
            timeout=10,
        )
        resp.raise_for_status()
        body = resp.json()
        self._token = body["access_token"]
        # Refresh 10s before expiry (tokens are typically 60s)
        expires_in = body.get("expires_in", 60)
        self._expires_at = time.time() + expires_in - 10
        log.info("Refreshed gateway token (expires in %ds)", expires_in)


token_manager = TokenManager()


def auth_header() -> dict:
    return {"Authorization": f"Bearer {token_manager.get_token()}"}


# --- NPL data fetching ---
def fetch_npl_data() -> dict:
    """Fetch all policy data from NPL Engine REST API."""
    headers = auth_header()

    # 1. ServiceRegistry → enabled_services
    enabled_services = {}
    try:
        resp = requests.get(
            f"{NPL_URL}/npl/registry/ServiceRegistry/", headers=headers, timeout=10
        )
        resp.raise_for_status()
        for item in resp.json().get("items", []):
            for svc in item.get("enabledServices", []):
                enabled_services[svc] = True
    except Exception as e:
        log.error("Failed to fetch ServiceRegistry: %s", e)

    # 2. ToolPolicy → tool_policies (need action call for private enabledTools)
    tool_policies = {}
    try:
        resp = requests.get(
            f"{NPL_URL}/npl/services/ToolPolicy/", headers=headers, timeout=10
        )
        resp.raise_for_status()
        for item in resp.json().get("items", []):
            service_name = item.get("policyServiceName")
            if not service_name:
                continue
            # Fetch enabledTools via action endpoint
            try:
                tools_resp = requests.post(
                    f"{NPL_URL}/npl/services/ToolPolicy/{item['@id']}/getEnabledTools",
                    headers={**headers, "Content-Type": "application/json"},
                    json={},
                    timeout=10,
                )
                tools_resp.raise_for_status()
                enabled_tools = tools_resp.json()
            except Exception as e:
                log.error("Failed to fetch enabledTools for %s: %s", service_name, e)
                enabled_tools = []

            tool_policies[service_name] = {
                "@state": item.get("@state", "active"),
                "policyServiceName": service_name,
                "enabledTools": enabled_tools,
            }
    except Exception as e:
        log.error("Failed to fetch ToolPolicy: %s", e)

    # 3. UserToolAccess → user_access_entries (need action call for private accessList)
    user_access_entries = {}
    try:
        resp = requests.get(
            f"{NPL_URL}/npl/users/UserToolAccess/", headers=headers, timeout=10
        )
        resp.raise_for_status()
        for item in resp.json().get("items", []):
            uid = item.get("userId")
            if not uid:
                continue
            # Fetch serviceAccess via action endpoint
            try:
                access_resp = requests.post(
                    f"{NPL_URL}/npl/users/UserToolAccess/{item['@id']}/getAccessList",
                    headers={**headers, "Content-Type": "application/json"},
                    json={},
                    timeout=10,
                )
                access_resp.raise_for_status()
                service_access = access_resp.json()
            except Exception as e:
                log.error("Failed to fetch accessList for %s: %s", uid, e)
                service_access = []

            user_access_entries[uid] = {
                "userId": uid,
                "serviceAccess": service_access,
            }
    except Exception as e:
        log.error("Failed to fetch UserToolAccess: %s", e)

    return {
        "enabled_services": enabled_services,
        "tool_policies": tool_policies,
        "user_access_entries": user_access_entries,
    }


# --- Bundle building ---
def build_bundle(policy_data: dict) -> tuple[bytes, str]:
    """Build an OPA bundle tar.gz containing data.json and .manifest."""
    data_json = json.dumps(policy_data, separators=(",", ":"), sort_keys=True)
    data_bytes = data_json.encode("utf-8")
    revision = hashlib.sha256(data_bytes).hexdigest()[:16]

    manifest = json.dumps(
        {
            "revision": revision,
            "roots": ["enabled_services", "tool_policies", "user_access_entries"],
        },
        separators=(",", ":"),
    )

    buf = io.BytesIO()
    with tarfile.open(fileobj=buf, mode="w:gz") as tar:
        # data.json
        data_info = tarfile.TarInfo(name="data.json")
        data_info.size = len(data_bytes)
        tar.addfile(data_info, io.BytesIO(data_bytes))

        # .manifest
        manifest_bytes = manifest.encode("utf-8")
        manifest_info = tarfile.TarInfo(name=".manifest")
        manifest_info.size = len(manifest_bytes)
        tar.addfile(manifest_info, io.BytesIO(manifest_bytes))

    bundle_bytes = buf.getvalue()
    etag = f'"{revision}"'
    return bundle_bytes, etag


def rebuild():
    """Fetch NPL data and rebuild the bundle."""
    global current_bundle, current_etag
    try:
        policy_data = fetch_npl_data()
        bundle_bytes, etag = build_bundle(policy_data)
        with bundle_lock:
            current_bundle = bundle_bytes
            current_etag = etag
        data_ready.set()
        log.info(
            "Bundle rebuilt: etag=%s services=%d policies=%d users=%d",
            etag,
            len(policy_data["enabled_services"]),
            len(policy_data["tool_policies"]),
            len(policy_data["user_access_entries"]),
        )
    except Exception as e:
        log.error("Failed to rebuild bundle: %s", e)


# --- SSE listener thread ---
def sse_listener():
    """Subscribe to NPL SSE /api/streams/states and trigger rebuilds on state events."""
    last_event_id = None
    backoff = 1

    while True:
        try:
            headers = auth_header()
            if last_event_id is not None:
                headers["Last-Event-ID"] = str(last_event_id)

            log.info(
                "Connecting to SSE %s/api/streams/states (Last-Event-ID=%s)",
                NPL_URL,
                last_event_id,
            )
            resp = requests.get(
                f"{NPL_URL}/api/streams/states",
                headers=headers,
                stream=True,
                timeout=(10, None),  # 10s connect, no read timeout
            )
            resp.raise_for_status()

            client = sseclient.SSEClient(resp)
            backoff = 1  # Reset backoff on successful connection
            log.info("SSE connected")

            for event in client.events():
                if event.event == "state":
                    if event.id:
                        last_event_id = event.id
                    log.info("SSE state event received (id=%s), signalling rebuild", event.id)
                    rebuild_signal.set()
                elif event.event == "tick":
                    pass  # Heartbeat, ignore

        except Exception as e:
            log.warning("SSE connection lost: %s — reconnecting in %ds", e, backoff)
            time.sleep(backoff)
            backoff = min(backoff * 2, 30)


def rebuild_loop():
    """Debounce rebuild signals: wait 100ms after last signal before rebuilding."""
    while True:
        rebuild_signal.wait()
        rebuild_signal.clear()
        # Debounce: wait 100ms for more events to arrive
        time.sleep(0.1)
        # Drain any additional signals accumulated during debounce
        rebuild_signal.clear()
        rebuild()


# --- HTTP server ---
class BundleHandler(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path == "/bundles/mcp/data.tar.gz":
            self.serve_bundle()
        elif self.path == "/health":
            self.serve_health()
        else:
            self.send_error(404)

    def serve_bundle(self):
        with bundle_lock:
            bundle = current_bundle
            etag = current_etag

        if bundle is None:
            self.send_error(503, "Bundle not ready")
            return

        # ETag-based conditional request
        if_none_match = self.headers.get("If-None-Match")
        if if_none_match and if_none_match == etag:
            self.send_response(304)
            self.send_header("ETag", etag)
            self.end_headers()
            return

        self.send_response(200)
        self.send_header("Content-Type", "application/gzip")
        self.send_header("Content-Length", str(len(bundle)))
        self.send_header("ETag", etag)
        self.end_headers()
        self.wfile.write(bundle)

    def serve_health(self):
        if data_ready.is_set():
            self.send_response(200)
            body = json.dumps({"status": "healthy"}).encode()
        else:
            self.send_response(503)
            body = json.dumps({"status": "initializing"}).encode()
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, format, *args):
        # Suppress default stderr logging for every request
        pass


# --- Main ---
def main():
    log.info("OPA Bundle Server starting on port %d", PORT)
    log.info("NPL_URL=%s  KEYCLOAK_URL=%s  REALM=%s", NPL_URL, KEYCLOAK_URL, KEYCLOAK_REALM)

    # Initial data fetch
    log.info("Performing initial data fetch...")
    rebuild()

    # Start SSE listener thread (daemon — exits with main)
    sse_thread = threading.Thread(target=sse_listener, daemon=True, name="sse-listener")
    sse_thread.start()

    # Start rebuild loop thread (debounces SSE signals)
    rebuild_thread = threading.Thread(target=rebuild_loop, daemon=True, name="rebuild-loop")
    rebuild_thread.start()

    # Start HTTP server (blocks main thread)
    server = HTTPServer(("0.0.0.0", PORT), BundleHandler)
    log.info("HTTP server listening on 0.0.0.0:%d", PORT)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        log.info("Shutting down")
        server.shutdown()


if __name__ == "__main__":
    main()
