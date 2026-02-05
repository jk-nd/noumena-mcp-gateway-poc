# NPL MCP Gateway Architecture v2

## Overview

This document describes the architecture for a secure, auditable MCP Gateway that integrates with NPL for policy enforcement and upstream MCP services for tool execution.

## Design Principles

1. **Full MCP Compliance** - End-to-end JSON-RPC 2.0 from LLM to upstream and back
2. **NPL as Policy Control** - All requests validated and logged by NPL
3. **Network Isolation** - Executor cannot be bypassed (RabbitMQ only)
4. **Separation of Concerns** - Secrets never touch Gateway or NPL
5. **Complete Audit Trail** - NPL logs approval, validation, and completion

---

## Component Architecture

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                                                                                 │
│  ┌───────┐                                                                      │
│  │  LLM  │                                                                      │
│  └───┬───┘                                                                      │
│      │                                                                          │
│      │ JSON-RPC 2.0 (MCP)                                                       │
│      ▼                                                                          │
│  ┌─────────────────────────────────────────────────────────────────┐            │
│  │                         GATEWAY                                 │            │
│  │  ┌───────────────────────────────────────────────────────────┐  │            │
│  │  │ Pending Requests Store (in-memory)                        │  │            │
│  │  │   requestId: "abc-123"                                    │  │            │
│  │  │   body: { to, subject, content, _meta }                   │  │            │
│  │  │   mcpId: 1                                                │  │            │
│  │  │   resultChannel: Channel<Result>                          │  │            │
│  │  └───────────────────────────────────────────────────────────┘  │            │
│  │                                                                 │            │
│  │  Endpoints:                                                     │            │
│  │    - MCP (JSON-RPC)      ← LLM connects here                    │            │
│  │    - GET /context/{id}   ← Executor fetches body                │            │
│  │    - POST /callback/{id} ← Executor returns result              │            │
│  └─────────────────────────────────────────────────────────────────┘            │
│      │                                                                          │
│      │ REST (metadata only - no body, no secrets)                               │
│      ▼                                                                          │
│  ┌─────────────────────────────────────────────────────────────────┐            │
│  │                         NPL ENGINE                              │            │
│  │                                                                 │            │
│  │  - AuthZ: Is user allowed?                                      │            │
│  │  - Policy: Rate limits, business rules                          │            │
│  │  - Audit: Logs approval + completion                            │            │
│  │  - State: Tracks request lifecycle                              │            │
│  │                                                                 │            │
│  │  Uses: notify ... resume callback pattern                       │            │
│  └─────────────────────────────────────────────────────────────────┘            │
│      │                                                                          │
│      │ Notification via RabbitMQ                                                │
│      ▼                                                                          │
│  ┌──────────────┐                                                               │
│  │   RabbitMQ   │  ← Only NPL can publish                                       │
│  └──────┬───────┘                                                               │
│         │                                                                       │
│         │ Message                                                               │
│         ▼                                                                       │
│  ┌─────────────────────────────────────────────────────────────────┐            │
│  │                         EXECUTOR                                │            │
│  │              (network isolated - no inbound HTTP)               │            │
│  │                                                                 │            │
│  │  Inbound:  RabbitMQ only                                        │            │
│  │  Outbound: Gateway, Vault, NPL, Upstream MCP                    │            │
│  └─────────────────────────────────────────────────────────────────┘            │
│      │                                                                          │
│      ├──► Gateway: GET /context/{id} → body                                     │
│      ├──► Vault: GET secrets                                                    │
│      ├──► Upstream MCP: JSON-RPC 2.0 tool call                                  │
│      ├──► NPL: Resume API (audit completion)                                    │
│      └──► Gateway: POST /callback/{id} → result                                 │
│                                                                                 │
│  ┌─────────────────────────────────────────────────────────────────┐            │
│  │                      UPSTREAM MCP SERVER                        │            │
│  │                                                                 │            │
│  │  - Actual tool implementation (email, database, etc.)           │            │
│  │  - Full MCP server (JSON-RPC 2.0)                               │            │
│  │  - Returns CallToolResult with content array                    │            │
│  └─────────────────────────────────────────────────────────────────┘            │
│                                                                                 │
└─────────────────────────────────────────────────────────────────────────────────┘
```

---

## Complete Request Flow

```
┌─────┐     ┌─────────┐     ┌─────┐     ┌──────────┐     ┌──────────┐     ┌──────────┐
│ LLM │     │ Gateway │     │ NPL │     │ RabbitMQ │     │ Executor │     │ Upstream │
└──┬──┘     └────┬────┘     └──┬──┘     └────┬─────┘     └────┬─────┘     └────┬─────┘
   │             │             │              │                │                │
   │ ① MCP Req   │             │              │                │                │
   │────────────►│             │              │                │                │
   │             │             │              │                │                │
   │             │ ② Store     │              │                │                │
   │             │ context     │              │                │                │
   │             │             │              │                │                │
   │             │ ③ Policy    │              │                │                │
   │             │────────────►│              │                │                │
   │             │             │              │                │                │
   │             │             │ ④ Log        │                │                │
   │             │             │ approval     │                │                │
   │             │             │              │                │                │
   │             │             │ ⑤ notify     │                │                │
   │             │             │─────────────►│                │                │
   │             │             │ resume cb    │                │                │
   │             │             │              │                │                │
   │             │ ⑥ 202       │              │                │                │
   │             │◄────────────│              │                │                │
   │             │             │              │                │                │
   │             │ ⑦ Suspend   │              │ ⑧ Message      │                │
   │             │ on Channel  │              │───────────────►│                │
   │             │             │              │                │                │
   │             │             │              │                │                │
   │             │             │ ⑨ Validate   │                │                │
   │             │             │◄─────────────────────────────│                │
   │             │             │ "Is req-abc  │                │                │
   │             │             │  approved?"  │                │                │
   │             │             │              │                │                │
   │             │             │─────────────────────────────►│                │
   │             │             │ ⑩ "Yes,      │                │                │
   │             │             │  approved"   │                │                │
   │             │             │              │                │                │
   │             │             │              │                │ ⑪ GET body     │
   │             │◄────────────────────────────────────────────│                │
   │             │────────────────────────────────────────────►│                │
   │             │             │              │                │                │
   │             │             │              │                │ ⑫ GET secrets  │
   │             │             │              │                │───────► Vault  │
   │             │             │              │                │◄───────        │
   │             │             │              │                │                │
   │             │             │              │                │ ⑬ MCP call     │
   │             │             │              │                │───────────────►│
   │             │             │              │                │                │
   │             │             │              │                │ ⑭ MCP result   │
   │             │             │              │                │◄───────────────│
   │             │             │              │                │                │
   │             │             │ ⑮ Resume     │                │                │
   │             │             │◄─────────────────────────────│                │
   │             │             │ (log done)   │                │                │
   │             │             │              │                │                │
   │             │ ⑯ Callback  │              │                │                │
   │             │◄────────────────────────────────────────────│                │
   │             │             │              │                │                │
   │             │ ⑰ Unblock   │              │                │                │
   │             │ Channel     │              │                │                │
   │             │             │              │                │                │
   │ ⑱ MCP Resp │             │              │                │                │
   │◄────────────│             │              │                │                │
   │             │              │              │                │                │
```

---

## Step-by-Step Flow with Certainty

| Step | From | To | Action | Certainty |
|------|------|-----|--------|-----------|
| ① | LLM | Gateway | MCP tool call (JSON-RPC 2.0) | MCP SDK handles |
| ② | Gateway | - | Store body + mcpId in pending requests | In-memory store |
| ③ | Gateway | NPL | Policy check (metadata only) | REST call |
| ④ | NPL | - | Log approval event | NPL audit log |
| ⑤ | NPL | RabbitMQ | `notify ... resume callback` | NPL notification |
| ⑥ | NPL | Gateway | 202 Accepted + requestId | Immediate return |
| ⑦ | Gateway | - | Suspend coroutine on Channel | Kotlin coroutine |
| ⑧ | RabbitMQ | Executor | Message delivery | Only source is NPL |
| ⑨ | Executor | NPL | Validate: "Is req approved?" | Defense in depth |
| ⑩ | NPL | Executor | Confirm: "Yes, approved" + log | NPL confirms & logs |
| ⑪ | Executor | Gateway | GET /context/{id} → body | Known requestId |
| ⑫ | Executor | Vault | Fetch secrets | Vault access |
| ⑬ | Executor | Upstream | MCP tool call (JSON-RPC 2.0) | MCP client SDK |
| ⑭ | Upstream | Executor | CallToolResult | Full MCP response |
| ⑮ | Executor | NPL | Resume API (completion audit) | NPL state update |
| ⑯ | Executor | Gateway | POST /callback/{id} + result | Callback endpoint |
| ⑰ | Gateway | - | Write to Channel, coroutine resumes | Channel unblocks |
| ⑱ | Gateway | LLM | MCP response (original id) | JSON-RPC 2.0 |

---

## MCP Request/Response Format

### Incoming (LLM → Gateway)

```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "tools/call",
  "params": {
    "name": "email.send",
    "arguments": {
      "to": "alice@example.com",
      "subject": "Hello",
      "body": "Message content"
    },
    "_meta": {
      "progressToken": "optional-token"
    }
  }
}
```

### Outgoing (Gateway → LLM)

```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "result": {
    "content": [
      {
        "type": "text",
        "text": "Email sent successfully to alice@example.com"
      }
    ],
    "isError": false,
    "_meta": {
      "requestId": "req-abc-123",
      "policyApproved": true
    }
  }
}
```

---

## NPL Protocol Pattern

```npl
@api
protocol[gateway, executor] ToolExecutionPolicy(
    var requestId: Text,
    var service: Text,
    var operation: Text,
    var userId: Text
) {
    initial state pending;
    state approved;
    state validatedForExecution;
    state executing;
    final state completed;
    final state failed;
    final state rejected;

    /**
     * Gateway requests policy check
     */
    @api
    permission[gateway] checkPolicy() | pending {
        // Validate user permissions
        require(isAuthorized(userId, service, operation), "Not authorized");
        
        // Check rate limits, business rules, etc.
        require(withinRateLimit(userId), "Rate limit exceeded");
        
        // Log approval
        become approved;
        
        // Send notification to Executor via RabbitMQ
        notify ExecutionRequest(
            requestId = requestId,
            service = service,
            operation = operation
        ) resume handleExecutionComplete;
    };

    /**
     * Executor validates before execution (defense in depth)
     * This confirms NPL really approved this request
     */
    @api
    permission[executor] validateForExecution() returns Boolean | approved {
        // Log that executor is about to execute
        become validatedForExecution;
        return true;
    };

    /**
     * Callback when Executor completes (via resume API)
     */
    function handleExecutionComplete(response: ExecutionResponse) -> {
        if (response.success) {
            become completed;
        } else {
            become failed;
        };
    };
}
```

---

## Component Responsibilities

### Gateway

| Responsibility | Implementation |
|----------------|----------------|
| MCP Server | Kotlin MCP SDK |
| Context Store | ConcurrentHashMap<RequestId, PendingRequest> |
| Sync-over-async | Kotlin Channels |
| NPL Client | REST client |
| Executor endpoints | Ktor HTTP server |

### NPL Engine

| Responsibility | Implementation |
|----------------|----------------|
| Policy evaluation | NPL permissions |
| Audit logging | NPL events/state |
| Notification dispatch | notify...resume pattern |
| State management | NPL state machine |

### Executor

| Responsibility | Implementation |
|----------------|----------------|
| Message consumption | RabbitMQ client |
| NPL validation | HTTP client → NPL validateForExecution |
| Body retrieval | HTTP client → Gateway |
| Secret retrieval | Vault client |
| Upstream MCP calls | MCP Client SDK |
| Completion reporting | HTTP client → NPL resume |
| Result delivery | HTTP client → Gateway callback |

---

## Security Properties

| Property | Enforcement |
|----------|-------------|
| Only NPL-approved requests execute | Network isolation (RabbitMQ only) + NPL validation callback |
| Request body never in NPL | Gateway stores, Executor fetches |
| Secrets never in Gateway/NPL | Executor fetches from Vault |
| Full audit trail | NPL logs: approval → validated → completed |
| MCP response fidelity | Executor preserves full upstream content |
| Request correlation | requestId + original MCP id mapping |
| One-time execution | Request consumed after callback |
| Defense in depth | Executor validates with NPL before execution |

---

## Audit Trail (in NPL)

```
┌─────────────────────────────────────────────────────────────────┐
│  requestId: "req-abc-123"                                       │
│                                                                 │
│  [2024-01-15 10:00:00] STATE: pending → approved                │
│    userId: user@example.com                                     │
│    service: email, operation: send                              │
│    notification sent to executor                                │
│                                                                 │
│  [2024-01-15 10:00:01] STATE: approved → validatedForExecution  │
│    executor called validateForExecution()                       │
│    confirmed: request is approved                               │
│                                                                 │
│  [2024-01-15 10:00:02] STATE: validatedForExecution → completed │
│    resume received                                              │
│    upstreamSuccess: true                                        │
│    duration: 1.8s                                               │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

---

## Error Handling

| Error | Handler | Response to LLM |
|-------|---------|-----------------|
| Policy denied | NPL | MCP error: "Not authorized" |
| Rate limit exceeded | NPL | MCP error: "Rate limit exceeded" |
| Timeout (no callback) | Gateway | MCP error: "Execution timeout" |
| Upstream MCP error | Executor | MCP error with upstream message |
| Vault unavailable | Executor | MCP error: "Service unavailable" |

---

## Tool Registration & Discovery

### Overview

Tools are dynamically discovered from upstream MCP servers and filtered based on NPL ServiceRegistry.
This enables a TUI for administrators to select which tools to expose to agents.

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                        TOOL DISCOVERY FLOW                                      │
│                                                                                 │
│  ┌──────────────────────────────────────────────────────────────────────────┐   │
│  │  STARTUP / REFRESH                                                       │   │
│  │                                                                          │   │
│  │  Gateway ──► Upstream MCP 1: tools/list ──► [tool1, tool2, ...]          │   │
│  │  Gateway ──► Upstream MCP 2: tools/list ──► [tool3, tool4, ...]          │   │
│  │  Gateway ──► Upstream MCP N: tools/list ──► [toolN, ...]                 │   │
│  │                                                                          │   │
│  │  Gateway stores: ToolRegistry (in-memory cache)                          │   │
│  │    { name, description, inputSchema, upstreamEndpoint, authType }        │   │
│  │                                                                          │   │
│  └──────────────────────────────────────────────────────────────────────────┘   │
│                                                                                 │
│  ┌──────────────────────────────────────────────────────────────────────────┐   │
│  │  TOOLS/LIST (from LLM)                                                   │   │
│  │                                                                          │   │
│  │  1. LLM ──► Gateway: tools/list                                          │   │
│  │  2. Gateway ──► NPL ServiceRegistry: getEnabledServices()                │   │
│  │  3. Gateway filters ToolRegistry by enabled services                     │   │
│  │  4. Gateway ──► LLM: filtered tool list                                  │   │
│  │                                                                          │   │
│  └──────────────────────────────────────────────────────────────────────────┘   │
│                                                                                 │
│  ┌──────────────────────────────────────────────────────────────────────────┐   │
│  │  TOOLS/CALL (from LLM)                                                   │   │
│  │                                                                          │   │
│  │  1. LLM ──► Gateway: tools/call { name: "gmail.send", args }             │   │
│  │  2. Gateway ──► NPL: isServiceEnabled("gmail") [double-check]            │   │
│  │  3. Gateway ──► NPL: checkPolicy(...) [full policy check]                │   │
│  │  4. ... (continue with execution flow)                                   │   │
│  │                                                                          │   │
│  └──────────────────────────────────────────────────────────────────────────┘   │
│                                                                                 │
└─────────────────────────────────────────────────────────────────────────────────┘
```

### TUI Tool Selection

Administrators can enable/disable services via a TUI that:
1. Fetches available tools from all configured upstream MCPs
2. Displays them grouped by service
3. Calls NPL ServiceRegistry to enable/disable

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│  ADMIN TUI: Service Selection                                                   │
│                                                                                 │
│  Upstream: google-mcp (http://google-mcp:8080)                                  │
│  ──────────────────────────────────────────────                                 │
│  [x] gmail.send_email        - Send email via Gmail                             │
│  [x] gmail.read_emails       - Read emails from Gmail                           │
│  [ ] gmail.delete_email      - Delete email (disabled)                          │
│  [x] calendar.create_event   - Create calendar event                            │
│  [x] calendar.list_events    - List calendar events                             │
│                                                                                 │
│  Upstream: slack-mcp (http://slack-mcp:8080)                                    │
│  ──────────────────────────────────────────────                                 │
│  [x] slack.send_message      - Send Slack message                               │
│  [ ] slack.delete_message    - Delete Slack message (disabled)                  │
│                                                                                 │
│  Upstream: stripe-mcp (http://stripe-mcp:8080)                                  │
│  ──────────────────────────────────────────────                                 │
│  [x] stripe.create_charge    - Create payment charge                            │
│  [x] stripe.list_customers   - List customers                                   │
│                                                                                 │
│  [Save] [Refresh] [Cancel]                                                      │
│                                                                                 │
└─────────────────────────────────────────────────────────────────────────────────┘
```

### Gateway ToolRegistry

```kotlin
data class RegisteredTool(
    val name: String,                    // e.g., "gmail.send_email"
    val service: String,                 // e.g., "gmail" (for ServiceRegistry lookup)
    val description: String,
    val inputSchema: JsonObject,         // JSON Schema for arguments
    val upstreamEndpoint: String,        // e.g., "http://google-mcp:8080"
    val upstreamToolName: String,        // Original tool name in upstream
    val authType: AuthType,              // OAUTH, API_KEY, NONE
    val credentialInjection: CredentialInjection  // HEADER, ARGUMENT, BOTH
)

enum class AuthType { OAUTH, API_KEY, NONE }
enum class CredentialInjection { HEADER, ARGUMENT, BOTH }
```

---

## Upstream Authentication

### Auth Types

| Type | Vault Path | Scope | Use Case |
|------|------------|-------|----------|
| **OAuth** | `secret/tenants/{tenant}/users/{user}/{service}` | Per-user | Gmail, Calendar, Slack |
| **API Key** | `secret/tenants/{tenant}/api_keys/{service}` | Per-tenant | Stripe, internal APIs |
| **None** | N/A | N/A | Internal services, no auth |

### Credential Injection Methods

Different upstream MCP servers expect credentials in different ways:

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│  CREDENTIAL INJECTION PATTERNS                                                  │
│                                                                                 │
│  Method 1: HTTP Headers (Standard)                                              │
│  ─────────────────────────────────                                              │
│  POST /mcp HTTP/1.1                                                             │
│  Authorization: Bearer ya29.xxx...                                              │
│  Content-Type: application/json                                                 │
│                                                                                 │
│  { "jsonrpc": "2.0", "method": "tools/call", ... }                              │
│                                                                                 │
│                                                                                 │
│  Method 2: In Arguments (Some MCPs)                                             │
│  ──────────────────────────────────                                             │
│  POST /mcp HTTP/1.1                                                             │
│  Content-Type: application/json                                                 │
│                                                                                 │
│  {                                                                              │
│    "jsonrpc": "2.0",                                                            │
│    "method": "tools/call",                                                      │
│    "params": {                                                                  │
│      "name": "send_email",                                                      │
│      "arguments": {                                                             │
│        "to": "alice@example.com",                                               │
│        "_oauth_token": "ya29.xxx...",    ← Injected by Executor                 │
│        "_api_key": "sk_live_xxx..."      ← Or API key                           │
│      }                                                                          │
│    }                                                                            │
│  }                                                                              │
│                                                                                 │
│                                                                                 │
│  Method 3: Both (Maximum Compatibility)                                         │
│  ──────────────────────────────────────                                         │
│  Executor sends credentials in BOTH header AND arguments.                       │
│  Upstream MCP uses whichever it prefers.                                        │
│                                                                                 │
└─────────────────────────────────────────────────────────────────────────────────┘
```

### Upstream Configuration

```yaml
# configs/upstreams.yaml

upstreams:
  google-mcp:
    endpoint: "http://google-mcp:8080"
    auth:
      type: oauth
      vault_path: "secret/tenants/{tenant}/users/{user}/google"
      injection: header    # Use Authorization header
    
  slack-mcp:
    endpoint: "http://slack-mcp:8080"
    auth:
      type: oauth
      vault_path: "secret/tenants/{tenant}/users/{user}/slack"
      injection: both      # Header + arguments (for compatibility)
    
  stripe-mcp:
    endpoint: "http://stripe-mcp:8080"
    auth:
      type: api_key
      vault_path: "secret/tenants/{tenant}/api_keys/stripe"
      injection: header    # X-API-Key header
    
  internal-docs:
    endpoint: "http://docs-mcp:8080"
    auth:
      type: none           # No auth needed
```

### Executor Credential Injection

```kotlin
// Executor injects credentials based on config
suspend fun callUpstreamMcp(
    request: ExecuteRequest,
    credentials: ServiceCredentials,
    config: UpstreamConfig
): McpCallResult {
    
    val headers = mutableMapOf<String, String>()
    val extraArgs = mutableMapOf<String, String>()
    
    // Inject based on config
    when (config.auth.injection) {
        CredentialInjection.HEADER -> {
            injectToHeaders(credentials, config.auth.type, headers)
        }
        CredentialInjection.ARGUMENT -> {
            injectToArguments(credentials, config.auth.type, extraArgs)
        }
        CredentialInjection.BOTH -> {
            injectToHeaders(credentials, config.auth.type, headers)
            injectToArguments(credentials, config.auth.type, extraArgs)
        }
    }
    
    // Make MCP call with injected credentials
    return mcpClient.call(
        endpoint = config.endpoint,
        tool = request.operation,
        arguments = request.params + extraArgs,
        headers = headers
    )
}

private fun injectToHeaders(creds: ServiceCredentials, type: AuthType, headers: MutableMap<String, String>) {
    when (type) {
        AuthType.OAUTH -> creds.accessToken?.let { headers["Authorization"] = "Bearer $it" }
        AuthType.API_KEY -> creds.apiKey?.let { headers["X-API-Key"] = it }
        AuthType.NONE -> { /* no-op */ }
    }
}

private fun injectToArguments(creds: ServiceCredentials, type: AuthType, args: MutableMap<String, String>) {
    when (type) {
        AuthType.OAUTH -> creds.accessToken?.let { args["_oauth_token"] = it }
        AuthType.API_KEY -> creds.apiKey?.let { args["_api_key"] = it }
        AuthType.NONE -> { /* no-op */ }
    }
}
```

### Vault Structure

```
secret/
└── tenants/
    └── {tenant_id}/
        ├── users/
        │   └── {user_id}/
        │       ├── google/          # OAuth tokens
        │       │   ├── access_token
        │       │   ├── refresh_token
        │       │   └── expires_at
        │       ├── slack/           # OAuth tokens
        │       └── github/          # OAuth tokens
        │
        └── api_keys/
            ├── stripe/              # API key
            ├── sendgrid/            # API key
            └── internal_crm/        # API key
```

---

## Technology Stack

| Component | Technology |
|-----------|------------|
| Gateway | Kotlin, Ktor, MCP SDK |
| NPL Engine | Noumena Platform |
| Executor | Kotlin, RabbitMQ client, MCP Client SDK |
| Message Queue | RabbitMQ |
| Secrets | HashiCorp Vault |
| Upstream MCP | Any MCP-compliant server |

---

## Next Steps for Implementation

### Phase 1: Core Flow
1. **Gateway**: Add context store, callback endpoint, Channel-based waiting
2. **NPL**: Create ToolExecutionPolicy protocol with notify...resume
3. **Executor**: Refactor to consume RabbitMQ, remove inbound HTTP
4. **Integration**: Wire up full flow end-to-end

### Phase 2: Tool Discovery & Auth
5. **Gateway**: Implement ToolRegistry with dynamic discovery from upstream MCPs
6. **Gateway**: Integrate ServiceRegistry checks at tools/list and tools/call
7. **Executor**: Implement configurable credential injection (header/argument/both)
8. **Config**: Define upstream configuration schema with auth settings
9. **TUI**: Build admin tool selection interface

### Phase 3: Testing & Hardening
10. **Testing**: Verify audit trail, error handling, MCP compliance
11. **Testing**: Test OAuth and API key flows with real upstream MCPs
12. **Docs**: Update with final implementation details
