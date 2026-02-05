#!/usr/bin/env python3
"""
Verbose End-to-End Test for MCP Gateway
Shows the complete flow through the system with all request/response details.
"""

import asyncio
import json
import websockets
from datetime import datetime

def log(step, message, data=None):
    """Pretty print a step with optional data."""
    timestamp = datetime.now().strftime("%H:%M:%S.%f")[:-3]
    print(f"\n{'='*80}")
    print(f"[{timestamp}] STEP {step}: {message}")
    print('='*80)
    if data:
        if isinstance(data, (dict, list)):
            print(json.dumps(data, indent=2))
        else:
            print(data)

async def run_e2e_test():
    # Connect to Gateway exposed on host port 8000
    uri = 'ws://localhost:8000/mcp/ws'
    
    # =========================================================================
    # STEP 1: Connect to Gateway WebSocket
    # =========================================================================
    log(1, "Connecting to MCP Gateway WebSocket", {"uri": uri})
    
    async with websockets.connect(uri) as ws:
        log(1, "Connected successfully!", {"connection": "established"})
        
        # =====================================================================
        # STEP 2: List available tools
        # =====================================================================
        list_tools_request = {
            "jsonrpc": "2.0",
            "id": 1,
            "method": "tools/list",
            "params": {}
        }
        
        log(2, "Sending tools/list request to Gateway", list_tools_request)
        await ws.send(json.dumps(list_tools_request))
        
        response = await ws.recv()
        tools_response = json.loads(response)
        
        # Extract tool names for summary
        tools = tools_response.get("result", {}).get("tools", [])
        tool_names = [t.get("name") for t in tools]
        
        log(2, f"Received tools/list response ({len(tools)} tools available)", {
            "available_tools": tool_names,
            "full_response": tools_response
        })
        
        # =====================================================================
        # STEP 3: Call web_fetch tool (Real MCP Service)
        # =====================================================================
        web_fetch_request = {
            "jsonrpc": "2.0",
            "id": 2,
            "method": "tools/call",
            "params": {
                "name": "web_fetch",
                "arguments": {
                    "url": "https://httpbin.org/json"
                }
            }
        }
        
        log(3, "Sending web_fetch tool call to Gateway", web_fetch_request)
        print("\n" + "-"*40)
        print("FLOW: LLM → Gateway → NPL Policy Check → Executor → MCP Fetch → Web")
        print("-"*40)
        
        await ws.send(json.dumps(web_fetch_request))
        
        response = await ws.recv()
        web_fetch_response = json.loads(response)
        
        log(3, "Received web_fetch response from Gateway", web_fetch_response)
        
        # Check if successful
        is_error = web_fetch_response.get("result", {}).get("isError", True)
        if not is_error:
            content = web_fetch_response.get("result", {}).get("content", [])
            if content:
                # Parse the metadata
                for item in content:
                    if "status" in item.get("text", ""):
                        try:
                            metadata = json.loads(item["text"].split("---\n")[1] if "---" in item["text"] else item["text"])
                            log(3, "Execution Metadata", metadata)
                        except:
                            pass
        
        # =====================================================================
        # STEP 4: Call google_gmail_send tool (Mock MCP Service)
        # =====================================================================
        gmail_request = {
            "jsonrpc": "2.0",
            "id": 3,
            "method": "tools/call",
            "params": {
                "name": "google_gmail_send",
                "arguments": {
                    "to": "recipient@example.com",
                    "subject": "Test Email from E2E Test",
                    "body": "This is a test email sent through the MCP Gateway."
                }
            }
        }
        
        log(4, "Sending google_gmail_send tool call to Gateway", gmail_request)
        print("\n" + "-"*40)
        print("FLOW: LLM → Gateway → NPL Policy Check → Executor → Mock Gmail MCP")
        print("-"*40)
        
        await ws.send(json.dumps(gmail_request))
        
        response = await ws.recv()
        gmail_response = json.loads(response)
        
        log(4, "Received google_gmail_send response from Gateway", gmail_response)
        
        # =====================================================================
        # SUMMARY
        # =====================================================================
        print("\n" + "="*80)
        print(" "*30 + "TEST SUMMARY")
        print("="*80)
        
        web_status = "✅ SUCCESS" if not web_fetch_response.get("result", {}).get("isError") else "❌ FAILED"
        gmail_status = "✅ SUCCESS" if not gmail_response.get("result", {}).get("isError") else "❌ FAILED"
        
        print(f"""
┌─────────────────────────────────────────────────────────────────────────────┐
│ Test Results                                                                 │
├─────────────────────────────────────────────────────────────────────────────┤
│ tools/list          : ✅ Listed {len(tools)} tools                               │
│ web_fetch           : {web_status} (real mcp/fetch service)                  │
│ google_gmail_send   : {gmail_status} (mock MCP service)                      │
└─────────────────────────────────────────────────────────────────────────────┘

ARCHITECTURE FLOW:
                                                                            
  ┌─────────┐    ┌─────────────┐    ┌───────────────┐    ┌──────────┐    ┌─────────────┐
  │   LLM   │───▶│   GATEWAY   │───▶│  NPL ENGINE   │───▶│ EXECUTOR │───▶│  MCP SERVER │
  │         │◀───│  (WebSocket)│◀───│ (Policy Check)│◀───│  (Vault) │◀───│  (Upstream) │
  └─────────┘    └─────────────┘    └───────────────┘    └──────────┘    └─────────────┘
                        │                                       │
                        └───────────────────────────────────────┘
                                  RabbitMQ Message Queue
""")

if __name__ == "__main__":
    asyncio.run(run_e2e_test())
