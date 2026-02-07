#!/bin/bash
# Script to demonstrate ephemeral STDIO containers in action

echo "=== Watching Docker Events for MCP Containers ==="
echo "This will show you containers being created and destroyed in real-time"
echo ""
echo "In another terminal, run:"
echo "  docker run -i --rm mcp/duckduckgo"
echo "Then type: {\"jsonrpc\":\"2.0\",\"method\":\"tools/list\",\"id\":1}"
echo ""
echo "Press Ctrl+C to stop"
echo ""

# Watch for docker events filtering for mcp images
docker events --filter "image=mcp/duckduckgo" --filter "image=mcp/github" --filter "image=mcp/slack" --format "{{.Time}} {{.Status}} {{.ID}} ({{.Attributes.name}})"
