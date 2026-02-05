#!/usr/bin/env python3
"""
HTTP-to-STDIO bridge for MCP Fetch server.
Translates HTTP JSON-RPC requests to STDIO calls to mcp-server-fetch.
"""

import asyncio
import json
import logging
import sys
from typing import Optional
from aiohttp import web

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

# Global process handle for the MCP server
mcp_process: Optional[asyncio.subprocess.Process] = None
request_id_counter = 0


async def start_mcp_server():
    """Start the mcp-server-fetch process."""
    global mcp_process
    mcp_process = await asyncio.create_subprocess_exec(
        "mcp-server-fetch",
        "--ignore-robots-txt",
        stdin=asyncio.subprocess.PIPE,
        stdout=asyncio.subprocess.PIPE,
        stderr=asyncio.subprocess.PIPE
    )
    logger.info("Started mcp-server-fetch process")
    
    # Start stderr reader task
    asyncio.create_task(read_stderr())
    
    # Initialize the MCP server
    await send_initialize()


async def read_stderr():
    """Read and log stderr from the MCP server."""
    while mcp_process and mcp_process.stderr:
        line = await mcp_process.stderr.readline()
        if not line:
            break
        logger.warning(f"MCP stderr: {line.decode().strip()}")


async def send_initialize():
    """Send initialize request to MCP server."""
    global request_id_counter
    request_id_counter += 1
    
    init_request = {
        "jsonrpc": "2.0",
        "id": request_id_counter,
        "method": "initialize",
        "params": {
            "protocolVersion": "2024-11-05",
            "capabilities": {},
            "clientInfo": {
                "name": "mcp-http-bridge",
                "version": "1.0.0"
            }
        }
    }
    
    response = await send_request(init_request)
    logger.info(f"Initialize response: {response}")
    
    # Send initialized notification
    initialized = {
        "jsonrpc": "2.0",
        "method": "notifications/initialized"
    }
    await send_notification(initialized)


async def send_notification(notification: dict):
    """Send a notification (no response expected)."""
    if not mcp_process or not mcp_process.stdin:
        raise Exception("MCP server not running")
    
    message = json.dumps(notification) + "\n"
    mcp_process.stdin.write(message.encode())
    await mcp_process.stdin.drain()
    logger.debug(f"Sent notification: {notification['method']}")


async def send_request(request: dict) -> dict:
    """Send a request to the MCP server and wait for response."""
    if not mcp_process or not mcp_process.stdin or not mcp_process.stdout:
        raise Exception("MCP server not running")
    
    message = json.dumps(request) + "\n"
    mcp_process.stdin.write(message.encode())
    await mcp_process.stdin.drain()
    logger.debug(f"Sent request: {request['method']}")
    
    # Read response
    response_line = await mcp_process.stdout.readline()
    if not response_line:
        raise Exception("No response from MCP server")
    
    response = json.loads(response_line.decode())
    logger.debug(f"Received response: {response}")
    return response


async def handle_jsonrpc(request: web.Request) -> web.Response:
    """Handle incoming JSON-RPC requests."""
    try:
        body = await request.json()
        logger.info(f"Received request: {body.get('method', 'unknown')}")
        
        method = body.get("method", "")
        params = body.get("params", {})
        request_id = body.get("id")
        
        # Handle tools/list
        if method == "tools/list":
            response = await send_request({
                "jsonrpc": "2.0",
                "id": request_id,
                "method": "tools/list",
                "params": {}
            })
            return web.json_response(response)
        
        # Handle tools/call
        if method == "tools/call":
            response = await send_request({
                "jsonrpc": "2.0",
                "id": request_id,
                "method": "tools/call",
                "params": params
            })
            return web.json_response(response)
        
        # Unknown method
        return web.json_response({
            "jsonrpc": "2.0",
            "id": request_id,
            "error": {
                "code": -32601,
                "message": f"Method not found: {method}"
            }
        })
        
    except Exception as e:
        logger.error(f"Error handling request: {e}")
        return web.json_response({
            "jsonrpc": "2.0",
            "id": None,
            "error": {
                "code": -32603,
                "message": str(e)
            }
        }, status=500)


async def health_check(request: web.Request) -> web.Response:
    """Health check endpoint."""
    if mcp_process and mcp_process.returncode is None:
        return web.json_response({"status": "healthy"})
    return web.json_response({"status": "unhealthy"}, status=503)


async def on_startup(app: web.Application):
    """Start the MCP server on app startup."""
    await start_mcp_server()


async def on_shutdown(app: web.Application):
    """Cleanup on shutdown."""
    global mcp_process
    if mcp_process:
        mcp_process.terminate()
        await mcp_process.wait()


def main():
    app = web.Application()
    # Listen on both root and /mcp for compatibility
    app.router.add_post("/", handle_jsonrpc)
    app.router.add_post("/mcp", handle_jsonrpc)
    app.router.add_get("/health", health_check)
    
    app.on_startup.append(on_startup)
    app.on_shutdown.append(on_shutdown)
    
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8080
    logger.info(f"Starting HTTP bridge on port {port}")
    web.run_app(app, host="0.0.0.0", port=port)


if __name__ == "__main__":
    main()
