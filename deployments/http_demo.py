#!/usr/bin/env python3
"""
HTTP POST Demo - Shows how agents can use HTTP instead of WebSocket
This approach works well for LangChain, ADK, Sligo.ai, and other agent frameworks.
"""

import requests
import json

GATEWAY_URL = "http://localhost:8000/mcp"

def send_mcp_request(method: str, params: dict = None, request_id: int = 1) -> dict:
    """Send an MCP JSON-RPC request via HTTP POST"""
    payload = {
        "jsonrpc": "2.0",
        "id": request_id,
        "method": method,
        "params": params or {}
    }
    
    print(f"\n{'='*60}")
    print(f"📤 REQUEST: {method}")
    print(f"{'='*60}")
    print(json.dumps(payload, indent=2))
    
    response = requests.post(
        GATEWAY_URL,
        json=payload,
        headers={"Content-Type": "application/json"}
    )
    
    result = response.json()
    
    print(f"\n📥 RESPONSE:")
    print(json.dumps(result, indent=2))
    
    return result

def main():
    print("╔═══════════════════════════════════════════════════════════════╗")
    print("║  🌐 MCP Gateway - HTTP POST Demo                              ║")
    print("║  Compatible with: LangChain, ADK, Sligo.ai, etc.              ║")
    print("╚═══════════════════════════════════════════════════════════════╝")
    
    # 1. Initialize
    print("\n\n🔷 STEP 1: Initialize MCP Session")
    send_mcp_request("initialize", {
        "protocolVersion": "2024-11-05",
        "capabilities": {},
        "clientInfo": {"name": "http-demo", "version": "1.0"}
    }, 1)
    
    # 2. List tools
    print("\n\n🔷 STEP 2: List Available Tools")
    tools_response = send_mcp_request("tools/list", {}, 2)
    
    tools = tools_response.get("result", {}).get("tools", [])
    print(f"\n📋 Found {len(tools)} tools:")
    for tool in tools:
        print(f"   • {tool['name']}: {tool.get('description', '')[:50]}...")
    
    # 3. Call a tool
    print("\n\n🔷 STEP 3: Call DuckDuckGo Search")
    send_mcp_request("tools/call", {
        "name": "duckduckgo_search",
        "arguments": {"query": "MCP protocol anthropic", "max_results": 3}
    }, 3)
    
    print("\n\n✅ HTTP POST demo complete!")
    print("   This same approach works for any HTTP-based agent framework.")

if __name__ == "__main__":
    main()
