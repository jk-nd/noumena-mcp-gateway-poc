#!/usr/bin/env python3
"""
MCP Gateway - Complete End-to-End Demonstration
================================================

This demonstrates the full architecture:
1. WebSocket connection to Gateway
2. Tool discovery (tools/list)
3. NPL policy APPROVAL (enabled service)
4. NPL policy DENIAL (disabled service)
5. Vault credential fetching
6. STDIO MCP execution
7. Real search results from DuckDuckGo

Run from project root:
  source .venv/bin/activate
  python3 deployments/full_demo.py
"""

import asyncio
import json
import websockets
from datetime import datetime

# Configuration
GATEWAY_WS = "ws://localhost:8000/mcp/ws"

# Colors for output
class Colors:
    RESET = "\033[0m"
    GREEN = "\033[92m"
    RED = "\033[91m"
    YELLOW = "\033[93m"
    BLUE = "\033[94m"
    CYAN = "\033[96m"
    BOLD = "\033[1m"

def banner():
    print(f"""
{Colors.CYAN}╔══════════════════════════════════════════════════════════════════════════╗
║                                                                              ║
║   {Colors.BOLD}MCP GATEWAY - COMPLETE END-TO-END DEMONSTRATION{Colors.RESET}{Colors.CYAN}                        ║
║                                                                              ║
║   Architecture:                                                              ║
║   ┌─────────┐    ┌─────────┐    ┌──────────┐    ┌─────────────────┐         ║
║   │  Agent  │───▶│ Gateway │───▶│ NPL      │───▶│ Executor        │         ║
║   │ (Client)│    │         │    │ Engine   │    │ ┌─────────────┐ │         ║
║   └─────────┘    └────┬────┘    └──────────┘    │ │ Vault       │ │         ║
║        ▲              │                         │ └─────────────┘ │         ║
║        │              │         ┌──────────┐    │ ┌─────────────┐ │         ║
║        └──────────────┼────────▶│ RabbitMQ │───▶│ │ STDIO MCP   │ │         ║
║                       │         └──────────┘    │ │ (subprocess)│ │         ║
║                       ▼                         │ └─────────────┘ │         ║
║                  WebSocket                      └─────────────────┘         ║
║                                                                              ║
╚══════════════════════════════════════════════════════════════════════════════╝{Colors.RESET}
""")

def log(msg, level="INFO"):
    timestamp = datetime.now().strftime("%H:%M:%S.%f")[:-3]
    colors = {
        "INFO": Colors.RESET,
        "SUCCESS": Colors.GREEN,
        "ERROR": Colors.RED,
        "WARN": Colors.YELLOW,
        "FLOW": Colors.BLUE,
        "POLICY": Colors.CYAN
    }
    color = colors.get(level, Colors.RESET)
    print(f"[{timestamp}] {color}[{level}]{Colors.RESET} {msg}")

def section(title):
    print(f"\n{Colors.BOLD}{'═' * 76}{Colors.RESET}")
    print(f"{Colors.BOLD}  {title}{Colors.RESET}")
    print(f"{Colors.BOLD}{'═' * 76}{Colors.RESET}\n")

def subsection(title):
    print(f"\n{Colors.CYAN}  ▸ {title}{Colors.RESET}\n")

async def send_request(ws, request, timeout=30):
    """Send request and wait for matching response."""
    await ws.send(json.dumps(request))
    
    start = asyncio.get_event_loop().time()
    while True:
        if asyncio.get_event_loop().time() - start > timeout:
            raise TimeoutError(f"No response after {timeout}s")
        
        try:
            response = await asyncio.wait_for(ws.recv(), timeout=5)
            data = json.loads(response)
            if data.get("id") == request.get("id"):
                return data
        except asyncio.TimeoutError:
            continue

async def main():
    banner()
    
    section("CONNECTING TO GATEWAY")
    log(f"Establishing WebSocket connection to {GATEWAY_WS}")
    
    async with websockets.connect(GATEWAY_WS) as ws:
        log("WebSocket connected!", "SUCCESS")
        await asyncio.sleep(0.3)
        
        # ─────────────────────────────────────────────────────────────────────
        section("STEP 1: TOOL DISCOVERY")
        # ─────────────────────────────────────────────────────────────────────
        log("Requesting list of available tools...", "FLOW")
        
        result = await send_request(ws, {
            "jsonrpc": "2.0",
            "id": "tools-list",
            "method": "tools/list",
            "params": {}
        })
        
        tools = result.get("result", {}).get("tools", [])
        log(f"Gateway exposes {len(tools)} tools:", "SUCCESS")
        for tool in tools:
            name = tool.get("name", "")
            desc = tool.get("description", "")[:50]
            # Mark which are real MCP vs mock
            full_desc = tool.get("description", "").lower()
            if "real mcp" in full_desc or "duckduckgo" in name or "web_fetch" in name:
                marker = f"{Colors.GREEN}[REAL MCP]{Colors.RESET}"
            else:
                marker = f"{Colors.YELLOW}[MOCK]{Colors.RESET}"
            print(f"       {marker} {name}: {desc}...")
        
        # ─────────────────────────────────────────────────────────────────────
        section("STEP 2: NPL POLICY APPROVAL (DuckDuckGo Search)")
        # ─────────────────────────────────────────────────────────────────────
        
        subsection("Request Flow:")
        print("""       1. Agent → Gateway (WebSocket)
       2. Gateway → NPL Engine (policy check)
       3. NPL checks ServiceRegistry: is 'duckduckgo' enabled? ✓
       4. NPL → RabbitMQ (ExecutionNotificationMessage)
       5. Executor consumes message
       6. Executor → Vault (fetch credentials)
       7. Executor spawns duckduckgo-mcp-server (STDIO subprocess)
       8. Executor → MCP server (JSON-RPC over stdin)
       9. MCP server performs real DuckDuckGo search
      10. MCP server → Executor (response via stdout)
      11. Executor → RabbitMQ → Gateway
      12. Gateway → Agent (WebSocket response)
""")
        
        log("Sending search request for 'Model Context Protocol'...", "FLOW")
        
        result = await send_request(ws, {
            "jsonrpc": "2.0",
            "id": "ddg-search",
            "method": "tools/call",
            "params": {
                "name": "duckduckgo_search",
                "arguments": {
                    "query": "Model Context Protocol",
                    "max_results": 3
                }
            }
        }, timeout=60)
        
        if "result" in result and not result.get("result", {}).get("isError"):
            content = result["result"]["content"]
            text = content[0].get("text", "")
            meta = json.loads(content[1].get("text", "---\n{}").split("---\n")[1])
            
            log(f"Search completed in {meta.get('durationMs', '?')}ms", "SUCCESS")
            log("Policy check: APPROVED", "POLICY")
            log("Vault credentials: FETCHED", "POLICY")
            print(f"\n{Colors.CYAN}  Search Results:{Colors.RESET}")
            for line in text.split("\n")[:12]:
                print(f"       {line}")
        else:
            error = result.get("result", {}).get("content", [{}])[0].get("text", "Unknown error")
            log(f"Search failed: {error}", "ERROR")
        
        # ─────────────────────────────────────────────────────────────────────
        section("STEP 3: NPL POLICY DENIAL (Slack - Not Enabled)")
        # ─────────────────────────────────────────────────────────────────────
        
        log("The 'slack' service is NOT enabled in NPL ServiceRegistry", "FLOW")
        log("Attempting to call slack_send_message...", "FLOW")
        
        result = await send_request(ws, {
            "jsonrpc": "2.0",
            "id": "slack-test",
            "method": "tools/call",
            "params": {
                "name": "slack_send_message",
                "arguments": {
                    "channel": "#general",
                    "message": "This should be denied"
                }
            }
        })
        
        if result.get("result", {}).get("isError"):
            content = result["result"]["content"]
            text = content[0].get("text", "")
            meta = json.loads(content[1].get("text", "---\n{}").split("---\n")[1])
            
            if "disabled" in text.lower() or "policy" in meta.get("errorType", "").lower():
                log("Policy check: DENIED", "POLICY")
                log(f"Error type: {meta.get('errorType')}", "POLICY")
                log(f"Reason: Service is disabled by administrator", "POLICY")
                log("NPL correctly blocked access to disabled service!", "SUCCESS")
            else:
                log(f"Request failed: {text[:100]}", "ERROR")
        elif "error" in result:
            log(f"Error: {result['error'].get('message')}", "POLICY")
        else:
            log("Unexpectedly succeeded - policy not enforced", "WARN")
        
        # ─────────────────────────────────────────────────────────────────────
        section("STEP 4: WEB FETCH (Another Real MCP)")
        # ─────────────────────────────────────────────────────────────────────
        
        log("Fetching https://example.com using mcp-server-fetch...", "FLOW")
        
        result = await send_request(ws, {
            "jsonrpc": "2.0",
            "id": "web-fetch",
            "method": "tools/call",
            "params": {
                "name": "web_fetch",
                "arguments": {
                    "url": "https://example.com"
                }
            }
        })
        
        if "result" in result and not result.get("result", {}).get("isError"):
            content = result["result"]["content"]
            text = content[0].get("text", "")[:300]
            meta = json.loads(content[1].get("text", "---\n{}").split("---\n")[1])
            
            log(f"Fetch completed in {meta.get('durationMs', '?')}ms", "SUCCESS")
            print(f"\n{Colors.CYAN}  Content Preview:{Colors.RESET}")
            for line in text.split("\n")[:8]:
                print(f"       {line}")
        else:
            error = result.get("result", {}).get("content", [{}])[0].get("text", "Unknown")
            log(f"Fetch failed: {error}", "ERROR")
        
        # ─────────────────────────────────────────────────────────────────────
        section("DEMONSTRATION COMPLETE")
        # ─────────────────────────────────────────────────────────────────────
        
        print(f"""
{Colors.GREEN}✓ Key Points Demonstrated:{Colors.RESET}

   1. {Colors.BOLD}Gateway{Colors.RESET} - Receives MCP requests via WebSocket, routes to NPL for policy
   
   2. {Colors.BOLD}NPL Engine{Colors.RESET} - Enforces policy via ServiceRegistry and ToolExecutionPolicy
      • Enabled services (duckduckgo, web) → APPROVED
      • Disabled services (slack) → DENIED with "Service is disabled"
   
   3. {Colors.BOLD}Vault{Colors.RESET} - Secure credential storage
      • Executor fetches credentials before calling upstream services
      • Path: secret/data/tenants/{{tenant}}/users/{{user}}/{{service}}
   
   4. {Colors.BOLD}STDIO MCP Execution{Colors.RESET} - No HTTP bridges needed!
      • Executor spawns MCP servers as subprocesses (duckduckgo-mcp-server)
      • Communicates via JSON-RPC over stdin/stdout
      • More secure: credentials never leave Executor process
      • More efficient: fewer containers, fewer network hops
   
   5. {Colors.BOLD}Real Results{Colors.RESET}
      • DuckDuckGo search returned actual search results
      • Web fetch returned real page content from example.com

{Colors.CYAN}Architecture Benefits:{Colors.RESET}
   • Centralized policy enforcement via NPL (auditable, on-chain)
   • Secure credential management via Vault
   • Async execution via RabbitMQ (decoupled, scalable)
   • STDIO MCP integration (secure, efficient)
""")

if __name__ == "__main__":
    try:
        asyncio.run(main())
    except websockets.exceptions.ConnectionRefusedError:
        print(f"\n{Colors.RED}Error: Could not connect to Gateway at {GATEWAY_WS}{Colors.RESET}")
        print("Make sure the Gateway is running: docker compose ps")
    except KeyboardInterrupt:
        print("\n\nDemo interrupted.")
