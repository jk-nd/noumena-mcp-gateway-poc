#!/bin/bash
set -e

PORT="${PORT:-8000}"
MCP_COMMAND="${MCP_COMMAND:?MCP_COMMAND environment variable is required}"

# Optional: inject credentials from Credential Proxy at startup
if [ -n "$CREDENTIAL_PROXY_URL" ] && [ -n "$SERVICE_NAME" ]; then
    echo "Fetching credentials from $CREDENTIAL_PROXY_URL for service '$SERVICE_NAME'..."
    CREDS=$(curl -sf "$CREDENTIAL_PROXY_URL/inject-credentials" \
        -H "Content-Type: application/json" \
        -d "{
            \"service\": \"$SERVICE_NAME\",
            \"operation\": \"startup\",
            \"metadata\": {},
            \"tenantId\": \"${TENANT_ID:-acme}\",
            \"userId\": \"system\"
        }" 2>/dev/null || echo "")

    if [ -n "$CREDS" ]; then
        # Export each injected field as an environment variable
        for key in $(echo "$CREDS" | jq -r '.injectedFields // {} | keys[]' 2>/dev/null); do
            value=$(echo "$CREDS" | jq -r ".injectedFields[\"$key\"]")
            export "$key"="$value"
            echo "Injected credential: $key"
        done
    else
        echo "Warning: No credentials returned from proxy (service may not require them)"
    fi
fi

echo "Starting supergateway on port $PORT"
echo "MCP command: $MCP_COMMAND"

# Start supergateway wrapping the STDIO command as Streamable HTTP
exec supergateway \
    --stdio "$MCP_COMMAND" \
    --outputTransport streamableHttp \
    --port "$PORT" \
    --streamableHttpPath "/mcp" \
    --cors
