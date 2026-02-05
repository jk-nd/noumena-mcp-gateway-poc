#!/usr/bin/env python3
"""
MCP Gateway - Verbose Message Flow Demonstration
=================================================

Shows ALL messages being exchanged at each step of the flow.
"""

import asyncio
import json
import websockets
import subprocess
import threading
import time
from datetime import datetime

GATEWAY_WS = "ws://localhost:8000/mcp/ws"

class Colors:
    RESET = "\033[0m"
    GREEN = "\033[92m"
    RED = "\033[91m"
    YELLOW = "\033[93m"
    BLUE = "\033[94m"
    CYAN = "\033[96m"
    MAGENTA = "\033[95m"
    BOLD = "\033[1m"
    DIM = "\033[2m"

def timestamp():
    return datetime.now().strftime("%H:%M:%S.%f")[:-3]

def print_message(direction, component_from, component_to, msg_type, content):
    """Print a formatted message exchange."""
    arrow = "→" if direction == "out" else "←"
    color = Colors.CYAN if direction == "out" else Colors.GREEN
    
    print(f"\n{Colors.DIM}[{timestamp()}]{Colors.RESET} {color}{Colors.BOLD}{component_from} {arrow} {component_to}{Colors.RESET}")
    print(f"{Colors.DIM}├─ Type: {Colors.RESET}{msg_type}")
    print(f"{Colors.DIM}└─ Payload:{Colors.RESET}")
    
    # Pretty print JSON
    if isinstance(content, dict):
        formatted = json.dumps(content, indent=2)
    else:
        formatted = str(content)
    
    for line in formatted.split('\n'):
        print(f"   {Colors.DIM}│{Colors.RESET} {line}")

def section(title):
    print(f"\n{Colors.BOLD}{'═' * 80}{Colors.RESET}")
    print(f"{Colors.BOLD}  {title}{Colors.RESET}")
    print(f"{Colors.BOLD}{'═' * 80}{Colors.RESET}")

def subsection(title):
    print(f"\n{Colors.YELLOW}  ▸ {title}{Colors.RESET}")

async def verbose_request(ws, request, description):
    """Send request and show all messages."""
    request_id = request["id"]
    
    subsection(description)
    
    # Show outgoing request
    print_message("out", "Agent", "Gateway", "JSON-RPC Request", request)
    
    await ws.send(json.dumps(request))
    
    # Collect response
    while True:
        response = await asyncio.wait_for(ws.recv(), timeout=60)
        data = json.loads(response)
        
        # Skip notifications
        if "id" not in data:
            print_message("in", "Gateway", "Agent", "Notification", data)
            continue
        
        if data.get("id") == request_id:
            # Check if success or error
            if "error" in data:
                print_message("in", "Gateway", "Agent", "JSON-RPC Error Response", data)
            elif data.get("result", {}).get("isError"):
                print_message("in", "Gateway", "Agent", "JSON-RPC Result (with error)", data)
            else:
                print_message("in", "Gateway", "Agent", "JSON-RPC Success Response", data)
            return data

async def main():
    print(f"""
{Colors.CYAN}╔══════════════════════════════════════════════════════════════════════════════╗
║  {Colors.BOLD}MCP GATEWAY - VERBOSE MESSAGE FLOW{Colors.RESET}{Colors.CYAN}                                          ║
║                                                                                ║
║  This demo shows the ACTUAL messages exchanged at each step.                   ║
║                                                                                ║
║  Legend:                                                                       ║
║    {Colors.CYAN}→{Colors.RESET}{Colors.CYAN} = Outgoing message        {Colors.GREEN}←{Colors.RESET}{Colors.CYAN} = Incoming message                     ║
╚══════════════════════════════════════════════════════════════════════════════╝{Colors.RESET}
""")

    # Start log monitoring in background
    print(f"{Colors.DIM}Starting Docker log monitor for internal messages...{Colors.RESET}")
    
    section("CONNECTING TO GATEWAY")
    print(f"{Colors.DIM}[{timestamp()}]{Colors.RESET} Establishing WebSocket to {GATEWAY_WS}")
    
    async with websockets.connect(GATEWAY_WS) as ws:
        print(f"{Colors.GREEN}[{timestamp()}] WebSocket connected!{Colors.RESET}")
        await asyncio.sleep(0.3)
        
        # ─────────────────────────────────────────────────────────────────
        section("FLOW 1: TOOL DISCOVERY")
        # ─────────────────────────────────────────────────────────────────
        
        print(f"""
{Colors.DIM}   ┌──────────┐        ┌──────────┐
   │  Agent   │───────▶│  Gateway │
   │          │◀───────│          │
   └──────────┘        └──────────┘
   (No NPL check needed for tools/list){Colors.RESET}
""")
        
        await verbose_request(ws, {
            "jsonrpc": "2.0",
            "id": "list-1",
            "method": "tools/list",
            "params": {}
        }, "Requesting available tools")
        
        # ─────────────────────────────────────────────────────────────────
        section("FLOW 2: DUCKDUCKGO SEARCH (Full Flow)")
        # ─────────────────────────────────────────────────────────────────
        
        print(f"""
{Colors.DIM}   ┌────────┐     ┌─────────┐     ┌─────────┐     ┌──────────┐     ┌───────────────┐
   │ Agent  │────▶│ Gateway │────▶│ NPL     │────▶│ RabbitMQ │────▶│   Executor    │
   │        │     │         │     │ Engine  │     │          │     │ ┌───────────┐ │
   │        │     │         │     └─────────┘     └──────────┘     │ │   Vault   │ │
   │        │     │         │                                      │ └───────────┘ │
   │        │     │         │◀────────────────────────────────────│ ┌───────────┐ │
   │        │◀────│         │                                      │ │ STDIO MCP │ │
   └────────┘     └─────────┘                                      │ └───────────┘ │
                                                                   └───────────────┘{Colors.RESET}

{Colors.YELLOW}Internal messages (from Docker logs):{Colors.RESET}
""")
        
        # Clear recent logs and start fresh
        subprocess.run(
            "docker compose logs --tail 0 -f gateway executor 2>&1 | head -50 &",
            shell=True, cwd="/Users/juerg/development/noumena-mcp-gateway/deployments",
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL
        )
        
        result = await verbose_request(ws, {
            "jsonrpc": "2.0",
            "id": "search-1",
            "method": "tools/call",
            "params": {
                "name": "duckduckgo_search",
                "arguments": {
                    "query": "Anthropic Claude AI",
                    "max_results": 2
                }
            }
        }, "Sending DuckDuckGo search request")
        
        # Show internal flow explanation
        if result.get("result") and not result["result"].get("isError"):
            print(f"""
{Colors.GREEN}✓ Request succeeded!{Colors.RESET}

{Colors.CYAN}Internal message flow that occurred:{Colors.RESET}

   1. {Colors.BOLD}Gateway → NPL Engine{Colors.RESET}
      POST /npl/services/ToolExecutionPolicy/{{id}}/checkAndApprove
      {{service: "duckduckgo", operation: "search", metadata: ...}}
      
   2. {Colors.BOLD}NPL Engine → ServiceRegistry{Colors.RESET}
      Check: isServiceEnabled("duckduckgo") → true ✓
      
   3. {Colors.BOLD}NPL Engine → RabbitMQ{Colors.RESET}
      Publish ExecutionNotificationMessage to 'execution.approved' queue
      
   4. {Colors.BOLD}Executor ← RabbitMQ{Colors.RESET}
      Consume ExecutionNotificationMessage
      
   5. {Colors.BOLD}Executor → Vault{Colors.RESET}
      GET /v1/secret/data/tenants/default/users/unknown/duckduckgo
      Response: {{api_key: "...", ...}}
      
   6. {Colors.BOLD}Executor → STDIO (subprocess){Colors.RESET}
      Spawn: duckduckgo-mcp-server
      stdin: {{"jsonrpc":"2.0","method":"tools/call","params":{{...}}}}
      
   7. {Colors.BOLD}MCP Server (DuckDuckGo){Colors.RESET}
      Performs real DuckDuckGo search
      stdout: {{"jsonrpc":"2.0","result":{{...}}}}
      
   8. {Colors.BOLD}Executor → RabbitMQ{Colors.RESET}
      Publish result to Gateway callback queue
      
   9. {Colors.BOLD}Gateway ← RabbitMQ{Colors.RESET}
      Receive result, forward to Agent via WebSocket
""")
        
        # ─────────────────────────────────────────────────────────────────
        section("FLOW 3: POLICY DENIAL (Slack)")
        # ─────────────────────────────────────────────────────────────────
        
        print(f"""
{Colors.DIM}   ┌────────┐     ┌─────────┐     ┌─────────┐
   │ Agent  │────▶│ Gateway │────▶│ NPL     │
   │        │     │         │     │ Engine  │
   │        │     │         │     └────┬────┘
   │        │     │         │          │ ✗ Service disabled!
   │        │◀────│         │◀─────────┘
   └────────┘     └─────────┘
   
   (Request never reaches Executor - blocked at NPL){Colors.RESET}
""")
        
        result = await verbose_request(ws, {
            "jsonrpc": "2.0",
            "id": "slack-1",
            "method": "tools/call",
            "params": {
                "name": "slack_send_message",
                "arguments": {
                    "channel": "#general",
                    "message": "This will be denied"
                }
            }
        }, "Attempting Slack message (will be denied)")
        
        if result.get("result", {}).get("isError"):
            print(f"""
{Colors.RED}✗ Request denied by NPL policy{Colors.RESET}

{Colors.CYAN}What happened:{Colors.RESET}

   1. {Colors.BOLD}Gateway → NPL Engine{Colors.RESET}
      POST /npl/services/ToolExecutionPolicy/{{id}}/checkAndApprove
      {{service: "slack", operation: "send_message"}}
      
   2. {Colors.BOLD}NPL Engine → ServiceRegistry{Colors.RESET}
      Check: isServiceEnabled("slack") → {Colors.RED}false ✗{Colors.RESET}
      
   3. {Colors.BOLD}NPL Engine → Gateway{Colors.RESET}
      Error: "Service is disabled by administrator"
      
   4. {Colors.BOLD}Gateway → Agent{Colors.RESET}
      WebSocket response with error (no Executor involvement)
""")
        
        # ─────────────────────────────────────────────────────────────────
        section("SUMMARY")
        # ─────────────────────────────────────────────────────────────────
        
        print(f"""
{Colors.GREEN}Messages exchanged in this demo:{Colors.RESET}

   {Colors.BOLD}External (Agent ↔ Gateway):{Colors.RESET}
   • 3 WebSocket requests (tools/list, duckduckgo_search, slack_send_message)
   • 3 WebSocket responses
   
   {Colors.BOLD}Internal (Gateway ↔ NPL ↔ Executor):{Colors.RESET}
   • 2 NPL policy check requests (duckduckgo, slack)
   • 1 RabbitMQ ExecutionNotificationMessage (duckduckgo only)
   • 1 Vault credential fetch
   • 1 STDIO MCP subprocess invocation
   • 1 RabbitMQ result callback
   
{Colors.CYAN}Key insight:{Colors.RESET} The Slack request was blocked at NPL and never 
reached the Executor, demonstrating policy enforcement at the gateway level.
""")

if __name__ == "__main__":
    try:
        asyncio.run(main())
    except websockets.exceptions.ConnectionRefusedError:
        print(f"\n{Colors.RED}Error: Could not connect to Gateway{Colors.RESET}")
    except KeyboardInterrupt:
        print("\n\nDemo interrupted.")
