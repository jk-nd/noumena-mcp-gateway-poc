#!/usr/bin/env python3
"""
NPL Policy Denial Demo
Demonstrates enabling/disabling services and policy enforcement
"""

import requests
import asyncio
import json
import websockets
import sys

# Configuration
KEYCLOAK_URL = "http://keycloak:11000" if "--docker" in sys.argv else "http://localhost:11000"
NPL_URL = "http://npl-engine:12000" if "--docker" in sys.argv else "http://localhost:12000"
GATEWAY_WS = "ws://gateway:8080/mcp/ws" if "--docker" in sys.argv else "ws://localhost:8000/mcp/ws"

def get_token():
    """Get admin token from Keycloak"""
    resp = requests.post(
        f"{KEYCLOAK_URL}/realms/mcpgateway/protocol/openid-connect/token",
        data={
            "grant_type": "password",
            "client_id": "mcpgateway",
            "username": "admin",
            "password": "Welcome123"
        }
    )
    return resp.json().get("access_token")

def get_registry_id(token):
    """Get the ServiceRegistry ID"""
    headers = {"Authorization": f"Bearer {token}", "Content-Type": "application/json"}
    resp = requests.get(f"{NPL_URL}/npl/registry/ServiceRegistry/", headers=headers)
    items = resp.json().get("items", [])
    return items[0]["@id"] if items else None

def enable_service(token, registry_id, service_name):
    """Enable a service in the registry"""
    headers = {"Authorization": f"Bearer {token}", "Content-Type": "application/json"}
    resp = requests.post(
        f"{NPL_URL}/npl/registry/ServiceRegistry/{registry_id}/enableService",
        headers=headers,
        json={"serviceName": service_name}
    )
    return resp.status_code in [200, 204]

def disable_service(token, registry_id, service_name):
    """Disable a service in the registry"""
    headers = {"Authorization": f"Bearer {token}", "Content-Type": "application/json"}
    resp = requests.post(
        f"{NPL_URL}/npl/registry/ServiceRegistry/{registry_id}/disableService",
        headers=headers,
        json={"serviceName": service_name}
    )
    return resp.status_code in [200, 204]

def get_enabled_services(token, registry_id):
    """Get list of enabled services"""
    headers = {"Authorization": f"Bearer {token}", "Content-Type": "application/json"}
    resp = requests.get(f"{NPL_URL}/npl/registry/ServiceRegistry/{registry_id}", headers=headers)
    return resp.json().get("enabledServices", [])

async def call_web_fetch():
    """Call web_fetch tool via WebSocket"""
    async with websockets.connect(GATEWAY_WS) as ws:
        msg = {
            "jsonrpc": "2.0",
            "id": 1,
            "method": "tools/call",
            "params": {
                "name": "web_fetch",
                "arguments": {"url": "https://example.com"}
            }
        }
        await ws.send(json.dumps(msg))
        resp = json.loads(await ws.recv())
        
        is_error = resp.get("result", {}).get("isError", False)
        content = resp.get("result", {}).get("content", [])
        
        if is_error:
            for c in content:
                if "text" in c and "Service is disabled" in c["text"]:
                    return "DENIED", "Service is disabled by administrator"
                if "text" in c and "status" in c["text"]:
                    return "DENIED", c["text"]
            return "DENIED", str(content)
        else:
            return "SUCCESS", "Fetched content from example.com"

def main():
    print("=" * 70)
    print("           NPL POLICY ENFORCEMENT DEMO")
    print("=" * 70)
    
    # Get token
    print("\n[1] Authenticating as admin...")
    token = get_token()
    if not token:
        print("    ERROR: Failed to get token")
        return
    print("    ✓ Token obtained")
    
    # Get registry
    print("\n[2] Getting ServiceRegistry...")
    registry_id = get_registry_id(token)
    print(f"    ✓ Registry ID: {registry_id}")
    
    # Show current state
    enabled = get_enabled_services(token, registry_id)
    print(f"    Current enabled services: {enabled}")
    
    # Step 1: Enable web service
    print("\n" + "=" * 70)
    print(" STEP 1: ENABLE 'web' SERVICE")
    print("=" * 70)
    if enable_service(token, registry_id, "web"):
        print("    ✓ web service ENABLED")
    enabled = get_enabled_services(token, registry_id)
    print(f"    Enabled services: {enabled}")
    
    # Test - should succeed
    print("\n[3] Testing web_fetch (should SUCCEED)...")
    status, msg = asyncio.run(call_web_fetch())
    if status == "SUCCESS":
        print(f"    ✓ {status}: {msg}")
    else:
        print(f"    ✗ {status}: {msg}")
    
    # Step 2: Disable web service
    print("\n" + "=" * 70)
    print(" STEP 2: DISABLE 'web' SERVICE")
    print("=" * 70)
    if disable_service(token, registry_id, "web"):
        print("    ✓ web service DISABLED")
    enabled = get_enabled_services(token, registry_id)
    print(f"    Enabled services: {enabled}")
    
    # Test - should fail
    print("\n[4] Testing web_fetch (should be DENIED)...")
    status, msg = asyncio.run(call_web_fetch())
    if status == "DENIED":
        print(f"    ✓ {status}: Policy correctly denied the request!")
    else:
        print(f"    ✗ Unexpected: {status}: {msg}")
    
    # Re-enable for future tests
    print("\n[5] Re-enabling web service...")
    enable_service(token, registry_id, "web")
    enabled = get_enabled_services(token, registry_id)
    print(f"    Enabled services: {enabled}")
    
    print("\n" + "=" * 70)
    print("           DEMO COMPLETE")
    print("=" * 70)
    print("""
KEY TAKEAWAYS:
  - NPL ServiceRegistry controls which services are available
  - ToolExecutionPolicy checks service status before approval
  - Disabled services return "Service is disabled by administrator"
  - All policy decisions are recorded in immutable audit trail
""")

if __name__ == "__main__":
    main()
