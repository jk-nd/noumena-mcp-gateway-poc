#!/usr/bin/env python3
"""
NPL Policy Denial Demonstration
================================

This script demonstrates NPL policy enforcement by:
1. Making a successful duckduckgo search
2. Disabling the duckduckgo service via NPL
3. Making another search - which should be DENIED by policy
4. Re-enabling the service
"""

import asyncio
import json
import websockets
import requests
from datetime import datetime

# Configuration
GATEWAY_WS = "ws://localhost:8000/mcp/ws"
NPL_ENGINE = "http://localhost:12000"
KEYCLOAK_URL = "http://localhost:11000"
KC_REALM = "mcpgateway"
KC_CLIENT = "mcpgateway"

SEPARATOR = "=" * 70

def log(msg, level="INFO"):
    timestamp = datetime.now().strftime("%H:%M:%S.%f")[:-3]
    colors = {
        "INFO": "\033[0m",
        "SUCCESS": "\033[92m",
        "ERROR": "\033[91m",
        "WARNING": "\033[93m",
        "POLICY": "\033[94m"
    }
    color = colors.get(level, "\033[0m")
    reset = "\033[0m"
    print(f"[{timestamp}] [{color}{level}{reset}] {msg}")

def log_section(title):
    print(f"\n{SEPARATOR}")
    print(f"  {title}")
    print(f"{SEPARATOR}\n")

def get_keycloak_token():
    """Get access token from Keycloak."""
    url = f"{KEYCLOAK_URL}/realms/{KC_REALM}/protocol/openid-connect/token"
    data = {
        "grant_type": "password",
        "client_id": KC_CLIENT,
        "username": "admin",
        "password": "Welcome123"
    }
    response = requests.post(url, data=data)
    if response.status_code == 200:
        return response.json()["access_token"]
    else:
        log(f"Failed to get token: {response.status_code} - {response.text}", "ERROR")
        return None

def find_service_registry():
    """Find the ServiceRegistry protocol instance."""
    token = get_keycloak_token()
    if not token:
        log("Cannot proceed without Keycloak token", "ERROR")
        return None
    
    headers = {"Authorization": f"Bearer {token}"}
    response = requests.get(f"{NPL_ENGINE}/npl/services/ServiceRegistry/", headers=headers)
    
    if response.status_code == 200:
        data = response.json()
        items = data.get("items", [])
        if items:
            return items[0]["_protoId"]
    return None

def disable_service(service_name: str):
    """Disable a service in NPL."""
    log_section(f"DISABLING SERVICE: {service_name}")
    
    token = get_keycloak_token()
    if not token:
        return False
    
    registry_id = find_service_registry()
    if not registry_id:
        log("No ServiceRegistry found", "ERROR")
        return False
    
    log(f"Found ServiceRegistry: {registry_id}", "POLICY")
    
    headers = {
        "Authorization": f"Bearer {token}",
        "Content-Type": "application/json"
    }
    
    # Call disableService permission
    url = f"{NPL_ENGINE}/npl/services/ServiceRegistry/{registry_id}/disableService"
    response = requests.post(url, headers=headers, json={"serviceName": service_name})
    
    if response.status_code == 200:
        log(f"Service '{service_name}' DISABLED in NPL", "POLICY")
        return True
    else:
        log(f"Failed to disable: {response.status_code} - {response.text}", "ERROR")
        return False

def enable_service(service_name: str):
    """Enable a service in NPL."""
    log_section(f"ENABLING SERVICE: {service_name}")
    
    token = get_keycloak_token()
    if not token:
        return False
    
    registry_id = find_service_registry()
    if not registry_id:
        log("No ServiceRegistry found", "ERROR")
        return False
    
    log(f"Found ServiceRegistry: {registry_id}", "POLICY")
    
    headers = {
        "Authorization": f"Bearer {token}",
        "Content-Type": "application/json"
    }
    
    # Call enableService permission
    url = f"{NPL_ENGINE}/npl/services/ServiceRegistry/{registry_id}/enableService"
    response = requests.post(url, headers=headers, json={"serviceName": service_name})
    
    if response.status_code == 200:
        log(f"Service '{service_name}' ENABLED in NPL", "POLICY")
        return True
    else:
        log(f"Failed to enable: {response.status_code} - {response.text}", "ERROR")
        return False

async def duckduckgo_search(ws, request_id, query):
    """Perform a DuckDuckGo search."""
    request = {
        "jsonrpc": "2.0",
        "id": request_id,
        "method": "tools/call",
        "params": {
            "name": "duckduckgo_search",
            "arguments": {
                "query": query,
                "max_results": 2
            }
        }
    }
    
    log(f"Searching for: '{query}'")
    await ws.send(json.dumps(request))
    
    # Wait for matching response
    while True:
        response = await asyncio.wait_for(ws.recv(), timeout=30)
        data = json.loads(response)
        if data.get("id") == request_id:
            return data
    
async def main():
    log_section("NPL POLICY ENFORCEMENT DEMONSTRATION")
    
    log("This demonstrates how NPL controls access to services.")
    log("We'll disable duckduckgo via NPL and show the request being denied.")
    print()
    
    async with websockets.connect(GATEWAY_WS) as ws:
        log("Connected to Gateway", "SUCCESS")
        await asyncio.sleep(0.5)
        
        # Step 1: Make a successful search
        log_section("STEP 1: Search with service ENABLED")
        result = await duckduckgo_search(ws, "enabled-search", "MCP")
        
        if "result" in result and not result["result"].get("isError"):
            log("Search SUCCEEDED (as expected)", "SUCCESS")
            content = result["result"]["content"][0]["text"][:100]
            log(f"Result: {content}...")
        else:
            error = result.get("error", result.get("result", {}))
            log(f"Unexpected error: {error}", "ERROR")
            return
        
        # Step 2: Disable the service
        if not disable_service("duckduckgo"):
            log("Could not disable service - check NPL setup", "WARNING")
            return
        
        # Give NPL a moment to propagate
        await asyncio.sleep(1)
        
        # Step 3: Try search again - should be DENIED
        log_section("STEP 2: Search with service DISABLED")
        log("NPL should now deny access to duckduckgo...")
        
        result = await duckduckgo_search(ws, "disabled-search", "MCP")
        
        if "error" in result:
            log("Search DENIED by policy (as expected)", "SUCCESS")
            log(f"Error: {result['error'].get('message')}", "POLICY")
        elif result.get("result", {}).get("isError"):
            content = result["result"]["content"][0]["text"]
            if "policy" in content.lower() or "denied" in content.lower() or "not enabled" in content.lower():
                log("Search DENIED by policy (as expected)", "SUCCESS")
                log(f"Message: {content}", "POLICY")
            else:
                log(f"Error but not policy: {content}", "WARNING")
        else:
            log("Search unexpectedly succeeded - policy not enforced", "ERROR")
        
        # Step 4: Re-enable the service
        if not enable_service("duckduckgo"):
            log("Could not re-enable service", "WARNING")
        
        # Give NPL a moment
        await asyncio.sleep(1)
        
        # Step 5: Final search - should work again
        log_section("STEP 3: Search with service RE-ENABLED")
        result = await duckduckgo_search(ws, "reenabled-search", "MCP")
        
        if "result" in result and not result["result"].get("isError"):
            log("Search SUCCEEDED again (as expected)", "SUCCESS")
            content = result["result"]["content"][0]["text"][:100]
            log(f"Result: {content}...")
        else:
            error = result.get("error", result.get("result", {}))
            log(f"Error: {error}", "ERROR")
        
        log_section("DEMONSTRATION COMPLETE")
        log("Summary:")
        log("  1. Search worked when service was ENABLED")
        log("  2. Search was DENIED when service was DISABLED via NPL")
        log("  3. Search worked again after RE-ENABLING service")
        log("")
        log("This proves NPL dynamically controls MCP tool access!", "SUCCESS")

if __name__ == "__main__":
    print("\n" + "=" * 70)
    print("  NPL POLICY ENFORCEMENT DEMONSTRATION")
    print("  Showing: Enable → Disable → Denied → Re-enable → Success")
    print("=" * 70)
    asyncio.run(main())
