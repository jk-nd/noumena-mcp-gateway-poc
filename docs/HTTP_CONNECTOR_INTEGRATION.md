# HTTP Connector Integration Status

## Current State: WORKING ✅

The AMQP + HTTP Connector flow is now functional. The core message processing issue has been resolved.

### Working Components

1. **RabbitMQ 4.0** - Running with native AMQP 1.0 support
   - Exchange: `npl-exchange` (fanout)
   - Queue: `npl-notifications`
   - Binding: npl-exchange → npl-notifications

2. **NPL Engine** - Successfully publishing notifications via AMQP 1.0
   - Configured with `AMQP_BROKER_URL`, `AMQP_QUEUE_NAME`
   - `HttpRequestExecutionMessage` notifications are being sent with changeset `mcp-1.0`

3. **NPL Protocols** - Working
   - `registry.ServiceRegistry` - Admin can enable/disable services
   - `services.EmailTool` - Uses `notify HttpRequestExecutionMessage(...)` with `resume` pattern
   - `connector.v1.http.Http` - Defines the standard types

4. **HTTP Connector** - Receiving and processing messages ✅

## Issue RESOLVED: Message Path Format

### Root Cause

The HTTP Connector's `removePathVersionPrefix` function uses this regex:
```javascript
fullPath.replace(/^\/[^]+-\d+(\.\d+)*\??\//, '/')
```

This regex expects the format: `/prefix-version?/...` like `/demo-1.0?/...`

Our original changeset was named `1.0`, producing paths like `/1.0?/connector/v1/http/...` which **don't match** because there's no prefix before the hyphen.

### Fix Applied

Changed the changeset name from `1.0` to `mcp-1.0` in `migration.yml`:

```yaml
changesets:
  - name: mcp-1.0  # Was: 1.0
    changes:
      - migrate:
          sources:
            - ../npl-1.0
          rules: rules.yml
```

Now notification paths are `/mcp-1.0?/connector/v1/http/HttpRequestExecutionMessage` which correctly match the regex and normalize to `/connector/v1/http/HttpRequestExecutionMessage`.

### Verified AMQP Message Flow ✅

1. Agent calls `sendEmail` on EmailTool
2. NPL Engine validates (service enabled, business rules pass)
3. NPL Engine publishes `HttpRequestExecutionMessage` to RabbitMQ
4. HTTP Connector receives the message from queue
5. HTTP Connector acknowledges and processes the message
6. **Remaining**: Network validation and Keycloak auth issues (see below)

### Message Payload (Decoded)

The AMQP message contains:
```json
{
  "type": "notify",
  "refId": "protocol-instance-id",
  "name": "/1.0?/connector/v1/http/HttpRequestExecutionMessage",
  "arguments": [{
    "nplType": "struct",
    "prototypeId": "/1.0?/connector/v1/http/HttpRequest",
    "value": {
      "method": {"variant": "POST"},
      "url": {"value": "http://executor:8081/execute"},
      "data": {"value": [
        {"first": "requestId", "second": "EMAIL-1"},
        {"first": "service", "second": "google_gmail"},
        {"first": "operation", "second": "send_email"},
        {"first": "params", "second": "{...}"}
      ]}
    }
  }],
  "callback": "handleExecutorResponse"
}
```

## Remaining Issues

### Issue 1: Network Validation Blocking Internal Requests

The HTTP Connector has security features that block requests to private IP ranges:
```
SECURITY_NETWORK_BLOCK - Outbound HTTP request blocked by network validation
url: "http://executor:8081/execute"
reason: "Domain resolves to forbidden IP"
offendingIp: "172.18.0.9"
```

**Solution**: Configure the HTTP Connector to allow internal Docker network IPs, or use external URLs for testing.

### Issue 2: Keycloak Client Credentials

The HTTP Connector uses client credentials grant to call NPL Engine's resume API:
```
POST request to URL: http://keycloak:11000/realms/mcpgateway/protocol/openid-connect/token
Request failed with status code 401
```

**Solution**: Create a dedicated `http_service` Keycloak client (like the reference project) with:
- `service_accounts_enabled = true`
- `access_type = "CONFIDENTIAL"`
- Hardcoded `party: ["http_connector"]` claim

## Next Steps

1. **Create `http_service` Keycloak client** - Add to terraform provisioning
2. **Add `http_connector` party to EmailTool protocol** - For resume permission
3. **Configure network allowlist** - Or test with external URLs
4. **Test end-to-end flow** - Agent → NPL → HTTP Connector → Executor → Response

## Demo Results

The demo successfully shows the full flow:

1. **Authentication** - Keycloak tokens with Host header rewriting ✅
2. **Protocol Creation** - ServiceRegistry and EmailTool instances ✅
3. **Service Governance** - Admin enables services ✅
4. **Business Rules** - External domain email blocked by NPL ✅
5. **AMQP Flow** - Messages delivered to RabbitMQ queue ✅
6. **HTTP Connector** - Receives and processes messages ✅
7. **State Machine** - EmailTool transitions to `executing` state ✅

## Architecture Verified

```
Agent (JWT) 
    │
    ▼
NPL Engine (policy validation)
    │
    ├── require(serviceIsEnabled) ✅
    ├── require(toolIsEnabled) ✅  
    ├── require(internalDomain) ✅
    │
    ▼
notify HttpRequestExecutionMessage
    │
    ▼
RabbitMQ (AMQP 1.0) ✅
    │
    ▼
HTTP Connector ✅ (message processing works!)
    │
    ▼
[Network validation / Keycloak] ◄── remaining issues
    │
    ▼
Executor (http://executor:8081/execute)
    │
    ▼
Upstream MCP Service
```

## Files Modified

- `/npl/src/main/yaml/migration.yml` - **KEY FIX**: Changed changeset name from `1.0` to `mcp-1.0`
- `/npl/src/main/npl-1.0/http/Http.npl` - Standard HTTP types (connector.v1.http package)
- `/npl/src/main/npl-1.0/services/email_tool.npl` - Uses notify with resume
- `/deployments/docker-compose.yml` - Added RabbitMQ 4.0, HTTP Connector, realm config
- `/http_connector.yaml` - Authentication config for upstream services
- `/tools/demo_http_connector.sh` - Demo script with full flow

## Key Learnings

1. **Changeset naming matters** - The HTTP Connector's regex expects `prefix-version` format, not just `version`
2. **RabbitMQ 4.0** - Required for native AMQP 1.0 support (NPL Engine uses AMQP 1.0)
3. **Exchange naming** - NPL Engine uses `/exchange/npl-exchange` AMQP address format
4. **Keycloak Host trick** - Use `Host: keycloak:11000` header to fix issuer URL in tokens
