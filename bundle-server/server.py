"""
OPA Bundle Server — serves NPL policy data as an OPA bundle (v4 governance).

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
from datetime import datetime, timezone
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
RECONCILIATION_INTERVAL = int(os.environ.get("RECONCILIATION_INTERVAL", "30"))
STALENESS_THRESHOLD = int(os.environ.get("STALENESS_THRESHOLD", "60"))

# --- Shared state ---
bundle_lock = threading.Lock()
current_bundle: bytes | None = None
current_etag: str | None = None
current_revision: str | None = None
current_built_at: float | None = None
current_sse_event_id: str | None = None
sse_connected: bool = False
last_sse_event_at: float | None = None
rebuild_count: int = 0
rebuild_error_count: int = 0
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


# --- NPL data fetching (v4: GatewayStore + ServiceGovernance discovery) ---
def fetch_npl_data() -> dict:
    """Fetch all policy data from NPL GatewayStore singleton + discover ServiceGovernance instances."""
    headers = auth_header()

    # 1. Find the GatewayStore singleton
    resp = requests.get(
        f"{NPL_URL}/npl/store/GatewayStore/", headers=headers, timeout=10
    )
    resp.raise_for_status()
    items = resp.json().get("items", [])
    if not items:
        log.warning("No GatewayStore singleton found — returning empty policy data")
        return {
            "catalog": {},
            "access_rules": [],
            "revoked_subjects": [],
            "governance_instances": {},
            "npl_url": NPL_URL,
            "gateway_token": token_manager.get_token(),
        }

    store_id = items[0]["@id"]

    # 2. Get bundle data in one call
    data_resp = requests.post(
        f"{NPL_URL}/npl/store/GatewayStore/{store_id}/getBundleData",
        headers={**headers, "Content-Type": "application/json"},
        json={},
        timeout=10,
    )
    data_resp.raise_for_status()
    bundle_data = data_resp.json()

    # Transform catalog: CatalogEntry → {enabled, tools: {name: {tag}}}
    catalog = {}
    for svc_name, entry in bundle_data.get("catalog", {}).items():
        tools = {}
        for tool_name, tool_entry in entry.get("tools", {}).items():
            tools[tool_name] = {"tag": tool_entry.get("tag", "open")}
        catalog[svc_name] = {
            "enabled": entry.get("enabled", False),
            "tools": tools,
        }

    # Transform access rules: flatten match struct
    access_rules = []
    for rule in bundle_data.get("accessRules", []):
        access_rules.append({
            "id": rule.get("id", ""),
            "matcher": {
                "matchType": rule.get("matcher", {}).get("matchType", "claims"),
                "claims": rule.get("matcher", {}).get("claims", {}),
                "identity": rule.get("matcher", {}).get("identity", ""),
            },
            "allow": {
                "services": rule.get("allow", {}).get("services", []),
                "tools": rule.get("allow", {}).get("tools", []),
            },
        })

    revoked_subjects = list(bundle_data.get("revokedSubjects", []))

    # 3. Discover ServiceGovernance instances
    governance_instances = {}
    try:
        gov_resp = requests.get(
            f"{NPL_URL}/npl/governance/ServiceGovernance/",
            headers=headers,
            timeout=10,
        )
        gov_resp.raise_for_status()
        gov_items = gov_resp.json().get("items", [])
        for item in gov_items:
            instance_id = item.get("@id", "")
            # ServiceGovernance has a constructor param 'serviceName'
            svc_name = item.get("serviceName", "")
            if svc_name and instance_id:
                governance_instances[svc_name] = instance_id
    except Exception as e:
        log.warning("Failed to discover ServiceGovernance instances: %s", e)

    return {
        "catalog": catalog,
        "access_rules": access_rules,
        "revoked_subjects": revoked_subjects,
        "governance_instances": governance_instances,
        "npl_url": NPL_URL,
        "gateway_token": token_manager.get_token(),
    }


# --- Bundle building ---
def build_bundle(policy_data: dict) -> tuple[bytes, str, str]:
    """Build an OPA bundle tar.gz containing data.json and .manifest.

    Returns (bundle_bytes, etag, revision).
    """
    # Compute revision from policy data before adding metadata
    data_for_hash = json.dumps(policy_data, separators=(",", ":"), sort_keys=True)
    revision = hashlib.sha256(data_for_hash.encode("utf-8")).hexdigest()[:16]

    built_at = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")

    # Enrich data with bundle metadata (available to OPA as data._bundle_metadata)
    policy_data["_bundle_metadata"] = {
        "version": "4.0",
        "built_at": built_at,
        "revision": revision,
        "sse_event_id": current_sse_event_id,
    }

    data_json = json.dumps(policy_data, separators=(",", ":"), sort_keys=True)
    data_bytes = data_json.encode("utf-8")

    manifest = json.dumps(
        {
            "revision": revision,
            "roots": [
                "catalog",
                "access_rules",
                "revoked_subjects",
                "governance_instances",
                "npl_url",
                "gateway_token",
                "_bundle_metadata",
            ],
            "metadata": {"built_at": built_at},
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
    return bundle_bytes, etag, revision


def rebuild():
    """Fetch NPL data and rebuild the bundle."""
    global current_bundle, current_etag, current_revision, current_built_at
    global rebuild_count, rebuild_error_count
    try:
        policy_data = fetch_npl_data()
        prev_revision = current_revision
        bundle_bytes, etag, revision = build_bundle(policy_data)
        built_at = time.time()
        built_at_iso = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
        with bundle_lock:
            current_bundle = bundle_bytes
            current_etag = etag
            current_revision = revision
            current_built_at = built_at
        rebuild_count += 1
        data_ready.set()
        log.info(
            json.dumps({
                "event": "bundle_rebuilt",
                "revision": revision,
                "previous_revision": prev_revision,
                "built_at": built_at_iso,
                "sse_event_id": current_sse_event_id,
                "catalog_count": len(policy_data["catalog"]),
                "access_rules_count": len(policy_data["access_rules"]),
                "governance_instances_count": len(policy_data["governance_instances"]),
                "changed": revision != prev_revision,
            })
        )
    except Exception as e:
        rebuild_error_count += 1
        log.error(
            json.dumps({
                "event": "bundle_rebuild_failed",
                "error": str(e),
                "sse_event_id": current_sse_event_id,
            })
        )


# --- SSE listener thread ---
def sse_listener():
    """Subscribe to NPL SSE /api/streams/states and trigger rebuilds on state events."""
    global sse_connected, last_sse_event_at, current_sse_event_id
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
            sse_connected = True
            log.info("SSE connected")

            for event in client.events():
                if event.event == "state":
                    last_sse_event_at = time.time()
                    if event.id:
                        last_event_id = event.id
                        current_sse_event_id = event.id
                    log.info("SSE state event received (id=%s), signalling rebuild", event.id)
                    rebuild_signal.set()
                elif event.event == "tick":
                    pass  # Heartbeat, ignore

        except Exception as e:
            sse_connected = False
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


def reconciliation_loop():
    """Periodic fallback: re-fetch NPL data even without SSE trigger.

    Catches silent SSE failures, lost events, and drift.
    """
    while True:
        time.sleep(RECONCILIATION_INTERVAL)
        log.info("Reconciliation poll triggered")
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
        now = time.time()
        bundle_age = round(now - current_built_at, 1) if current_built_at else None
        stale = bundle_age is not None and bundle_age > STALENESS_THRESHOLD

        status = "healthy"
        if not data_ready.is_set():
            status = "initializing"
        elif stale:
            status = "degraded"

        last_sse_iso = None
        if last_sse_event_at is not None:
            last_sse_iso = datetime.fromtimestamp(
                last_sse_event_at, tz=timezone.utc
            ).strftime("%Y-%m-%dT%H:%M:%SZ")

        body_obj = {
            "status": status,
            "revision": current_revision,
            "bundle_age_seconds": bundle_age,
            "sse_connected": sse_connected,
            "last_sse_event_at": last_sse_iso,
            "rebuild_count": rebuild_count,
            "rebuild_error_count": rebuild_error_count,
            "staleness_threshold_seconds": STALENESS_THRESHOLD,
        }

        http_status = 200 if status in ("healthy", "degraded") else 503
        self.send_response(http_status)
        body = json.dumps(body_obj).encode()
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
    log.info(
        "NPL_URL=%s  KEYCLOAK_URL=%s  REALM=%s  RECONCILIATION_INTERVAL=%ds  STALENESS_THRESHOLD=%ds",
        NPL_URL, KEYCLOAK_URL, KEYCLOAK_REALM, RECONCILIATION_INTERVAL, STALENESS_THRESHOLD,
    )

    # Initial data fetch
    log.info("Performing initial data fetch...")
    rebuild()

    # Start SSE listener thread (daemon — exits with main)
    sse_thread = threading.Thread(target=sse_listener, daemon=True, name="sse-listener")
    sse_thread.start()

    # Start rebuild loop thread (debounces SSE signals)
    rebuild_thread = threading.Thread(target=rebuild_loop, daemon=True, name="rebuild-loop")
    rebuild_thread.start()

    # Start reconciliation loop thread (periodic fallback for SSE failures)
    recon_thread = threading.Thread(
        target=reconciliation_loop, daemon=True, name="reconciliation-loop"
    )
    recon_thread.start()

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
