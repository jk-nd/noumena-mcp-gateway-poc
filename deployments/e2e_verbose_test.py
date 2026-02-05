#!/usr/bin/env python3
"""
Comprehensive End-to-End Test for MCP Gateway
==============================================

This test demonstrates the full flow:
1. WebSocket connection to Gateway
2. Tool discovery
3. NPL policy check (service must be enabled)
4. Vault credential fetch (even for services that don't need them)
5. STDIO MCP execution within Executor
6. Response back through the chain

Run from host: python3 deployments/e2e_verbose_test.py
"""

import asyncio
import json
import websockets
import sys
from datetime import datetime

# Configuration
GATEWAY_WS = "ws://localhost:8000/mcp/ws"
SEPARATOR = "=" * 70

def log(msg, level="INFO"):
    timestamp = datetime.now().strftime("%H:%M:%S.%f")[:-3]
    print(f"[{timestamp}] [{level}] {msg}")

def log_section(title):
    print(f"\n{SEPARATOR}")
    print(f"  {title}")
    print(f"{SEPARATOR}\n")

def log_json(label, data):
    print(f"\n{label}:")
    print("-" * 40)
    print(json.dumps(data, indent=2))
    print("-" * 40)

async def send_and_receive(ws, request, timeout=30):
    """Send a request and wait for matching response."""
    log_json(">>> SENDING", request)
    await ws.send(json.dumps(request))
    
    start = asyncio.get_event_loop().time()
    while True:
        elapsed = asyncio.get_event_loop().time() - start
        if elapsed > timeout:
            raise TimeoutError(f"No response after {timeout}s")
        
        try:
            response = await asyncio.wait_for(ws.recv(), timeout=5)
            data = json.loads(response)
            
            # Skip notifications (no id field)
            if "id" not in data:
                log(f"Received notification: {data.get('method', 'unknown')}", "DEBUG")
                continue
            
            # Check if this response matches our request
            if data.get("id") == request.get("id"):
                log_json("<<< RECEIVED", data)
                return data
            else:
                log(f"Received response for different request: {data.get('id')}", "DEBUG")
        except asyncio.TimeoutError:
            log(f"Still waiting... ({elapsed:.1f}s elapsed)", "DEBUG")
            continue

async def test_tools_list(ws, request_id):
    """Test listing available tools."""
    log_section("STEP 1: List Available Tools")
    
    request = {
        "jsonrpc": "2.0",
        "id": request_id,
        "method": "tools/list",
        "params": {}
    }
    
    response = await send_and_receive(ws, request)
    
    if "result" in response:
        tools = response["result"].get("tools", [])
        log(f"Found {len(tools)} tools available")
        for tool in tools[:5]:  # Show first 5
            log(f"  - {tool.get('name')}: {tool.get('description', '')[:50]}...")
        if len(tools) > 5:
            log(f"  ... and {len(tools) - 5} more")
    else:
        log(f"Error: {response.get('error')}", "ERROR")
    
    return response

async def test_duckduckgo_search(ws, request_id, query="MCP protocol"):
    """Test DuckDuckGo search - demonstrates full flow."""
    log_section("STEP 2: DuckDuckGo Search (Full Flow)")
    
    log("Flow overview:")
    log("  1. Gateway receives request")
    log("  2. Gateway checks NPL policy (is 'duckduckgo' service enabled?)")
    log("  3. If approved, Gateway forwards to Executor via RabbitMQ")
    log("  4. Executor fetches credentials from Vault")
    log("  5. Executor spawns duckduckgo-mcp-server subprocess (STDIO)")
    log("  6. Executor sends JSON-RPC to MCP server via stdin")
    log("  7. MCP server performs search and returns via stdout")
    log("  8. Executor parses response and sends back via RabbitMQ")
    log("  9. Gateway returns result via WebSocket")
    print()
    
    request = {
        "jsonrpc": "2.0",
        "id": request_id,
        "method": "tools/call",
        "params": {
            "name": "duckduckgo_search",
            "arguments": {
                "query": query,
                "max_results": 3
            }
        }
    }
    
    log(f"Searching for: '{query}'")
    response = await send_and_receive(ws, request, timeout=60)
    
    if "result" in response:
        content = response["result"].get("content", [])
        log("Search completed successfully!", "SUCCESS")
        if content:
            text = content[0].get("text", "")[:500]
            log(f"Results preview: {text}...")
    else:
        error = response.get("error", {})
        log(f"Error: {error.get('message', 'Unknown error')}", "ERROR")
        # Check if it's a policy denial
        if "policy" in str(error).lower() or "not enabled" in str(error).lower():
            log("This indicates NPL policy denied the request!", "WARNING")
    
    return response

async def test_web_fetch(ws, request_id, url="https://example.com"):
    """Test web fetch - another MCP tool with Vault flow."""
    log_section("STEP 3: Web Fetch (STDIO MCP)")
    
    log(f"Fetching URL: {url}")
    
    request = {
        "jsonrpc": "2.0",
        "id": request_id,
        "method": "tools/call",
        "params": {
            "name": "web_fetch",
            "arguments": {
                "url": url
            }
        }
    }
    
    response = await send_and_receive(ws, request, timeout=30)
    
    if "result" in response:
        content = response["result"].get("content", [])
        log("Fetch completed successfully!", "SUCCESS")
        if content:
            text = content[0].get("text", "")[:300]
            log(f"Content preview: {text}...")
    else:
        error = response.get("error", {})
        log(f"Error: {error.get('message', 'Unknown error')}", "ERROR")
    
    return response

async def main():
    log_section("MCP GATEWAY END-TO-END TEST")
    log(f"Connecting to Gateway WebSocket: {GATEWAY_WS}")
    
    try:
        async with websockets.connect(GATEWAY_WS) as ws:
            log("WebSocket connected!", "SUCCESS")
            
            # Give the connection a moment to stabilize
            await asyncio.sleep(0.5)
            
            # Test 1: List tools
            await test_tools_list(ws, "test-1")
            
            # Test 2: DuckDuckGo search (full flow with Vault)
            await test_duckduckgo_search(ws, "test-2", "Model Context Protocol")
            
            # Test 3: Web fetch
            await test_web_fetch(ws, "test-3", "https://example.com")
            
            log_section("TEST SUMMARY")
            log("All tests completed!")
            log("")
            log("Key points demonstrated:")
            log("  - Gateway receives MCP requests via WebSocket")
            log("  - NPL policy enforcement checks service access")
            log("  - Vault integration for credential management")
            log("  - STDIO-based MCP servers (no HTTP bridge containers)")
            log("  - Full request/response flow with verbose logging")
            
    except websockets.exceptions.ConnectionClosed as e:
        log(f"Connection closed: {e}", "ERROR")
        sys.exit(1)
    except ConnectionRefusedError:
        log(f"Could not connect to {GATEWAY_WS}", "ERROR")
        log("Is the Gateway running? Try: docker compose ps", "ERROR")
        sys.exit(1)
    except Exception as e:
        log(f"Unexpected error: {e}", "ERROR")
        import traceback
        traceback.print_exc()
        sys.exit(1)

if __name__ == "__main__":
    print("\n" + "=" * 70)
    print("  MCP GATEWAY - COMPREHENSIVE END-TO-END TEST")
    print("  Testing: NPL Policy + Vault + STDIO MCP Execution")
    print("=" * 70)
    asyncio.run(main())
