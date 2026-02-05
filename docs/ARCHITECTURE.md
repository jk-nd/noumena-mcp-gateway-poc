# NPL MCP Gateway Architecture

## Overview

This document describes the architecture for a secure, auditable MCP Gateway that integrates with NPL for policy enforcement, Vault for credential management, and STDIO-based MCP servers for tool execution.

## Design Principles

1. **Full MCP Compliance** - End-to-end JSON-RPC 2.0 from LLM to upstream and back
2. **NPL as Policy Control** - All requests validated and logged by NPL
3. **Network Isolation** - Executor cannot be bypassed (RabbitMQ only)
4. **Separation of Concerns** - Secrets never touch Gateway or NPL
5. **Complete Audit Trail** - NPL logs approval, validation, and completion
6. **Multi-Transport Support** - STDIO, HTTP, and REST upstreams supported

---

## Component Architecture

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                                                                                 │
│  ┌───────┐                                                       ┌───────────┐ │
│  │ Agent │                                                       │  VAULT    │ │
│  │ (LLM) │                                                       │           │ │
│  └───┬───┘                                                       │ Secrets:  │ │
│      │                                                           │ OAuth     │ │
│      │ WebSocket (JSON-RPC 2.0)                                  │ API Keys  │ │
│      ▼                                                           └─────┬─────┘ │
│  ┌─────────────────────────────────────────────────────────────────┐   │       │
│  │                         GATEWAY                                 │   │       │
│  │  ┌───────────────────────────────────────────────────────────┐  │   │       │
│  │  │ Context Store (in-memory)                                 │  │   │       │
│  │  │   requestId → { body, mcpId, responseChannel }            │  │   │       │
│  │  └───────────────────────────────────────────────────────────┘  │   │       │
│  │                                                                 │   │       │
│  │  Endpoints:                                                     │   │       │
│  │    - WebSocket /mcp/ws    ← Agents connect here                 │   │       │
│  │    - GET /context/{id}    ← Executor fetches body               │   │       │
│  │    - POST /callback/{id}  ← Executor returns result             │   │       │
│  └─────────────────────────────────────────────────────────────────┘   │       │
│      │                                                                 │       │
│      │ REST (metadata only - no body, no secrets)                      │       │
│      ▼                                                                 │       │
│  ┌─────────────────────────────────────────────────────────────────┐   │       │
│  │                         NPL ENGINE                              │   │       │
│  │                                                                 │   │       │
│  │  Protocols:                                                     │   │       │
│  │    - ServiceRegistry: Enable/disable services                   │   │       │
│  │    - ToolExecutionPolicy: Validate and approve requests         │   │       │
│  │                                                                 │   │       │
│  │  Functions:                                                     │   │       │
│  │    - AuthZ: Is service enabled?                                 │   │       │
│  │    - Audit: Log approval + completion                           │   │       │
│  │    - State: Track request lifecycle                             │   │       │
│  └─────────────────────────────────────────────────────────────────┘   │       │
│      │                                                                 │       │
│      │ RabbitMQ (ExecutionNotificationMessage)                         │       │
│      ▼                                                                 │       │
│  ┌──────────────┐                                                      │       │
│  │   RabbitMQ   │  ← Only NPL/Gateway can publish                      │       │
│  └──────┬───────┘                                                      │       │
│         │                                                              │       │
│         │ Message                                                      │       │
│         ▼                                                              │       │
│  ┌─────────────────────────────────────────────────────────────────┐   │       │
│  │                         EXECUTOR                                │   │       │
│  │              (network isolated - no inbound HTTP)               │◄──┘       │
│  │                                                                 │           │
│  │  ┌─────────────┐  ┌─────────────┐  ┌─────────────────────────┐  │           │
│  │  │ Vault       │  │ NPL Client  │  │ Upstream Router         │  │           │
│  │  │ Client      │  │             │  │ (STDIO/HTTP/REST)       │  │           │
│  │  └─────────────┘  └─────────────┘  └─────────────────────────┘  │           │
│  │                                                                 │           │
│  │  Inbound:  RabbitMQ only                                        │           │
│  │  Outbound: Gateway, Vault, NPL, Upstream MCPs                   │           │
│  └─────────────────────────────────────────────────────────────────┘           │
│                                                                                 │
│  Access Legend:                                                                 │
│    Gateway  ─────► NPL, RabbitMQ                    (NO Vault access)           │
│    NPL      ─────► RabbitMQ                         (NO Vault access)           │
│    Executor ─────► Vault, Gateway, NPL, Upstreams   (HAS Vault access)          │
│                                                                                 │
└─────────────────────────────────────────────────────────────────────────────────┘
```

---

## Upstream Transport Types

The Executor supports **three transport types** for connecting to upstream services:

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                         EXECUTOR - UPSTREAM ROUTER                              │
│                                                                                 │
│  ┌─────────────────────────────────────────────────────────────────────────┐    │
│  │                         UpstreamRouter                                  │    │
│  │                                                                         │    │
│  │  Incoming request: { service: "X", operation: "Y", params: {...} }      │    │
│  │                                                                         │    │
│  │  1. Look up ServiceConfig for service                                   │    │
│  │  2. Fetch credentials from Vault                                        │    │
│  │  3. Route to appropriate transport                                      │    │
│  │                                                                         │    │
│  └─────────────────────────────────────────────────────────────────────────┘    │
│         │                                                                       │
│         ├──────────────────┬──────────────────┬───────────────────┐             │
│         ▼                  ▼                  ▼                   │             │
│  ┌─────────────┐    ┌─────────────┐    ┌─────────────┐            │             │
│  │  MCP_STDIO  │    │  MCP_HTTP   │    │ DIRECT_REST │            │             │
│  │             │    │             │    │             │            │             │
│  │ Subprocess  │    │ HTTP POST   │    │ REST API    │            │             │
│  │ stdin/stdout│    │ JSON-RPC    │    │ calls       │            │             │
│  └──────┬──────┘    └──────┬──────┘    └──────┬──────┘            │             │
│         │                  │                  │                   │             │
│         ▼                  ▼                  ▼                   │             │
│  ┌─────────────┐    ┌─────────────┐    ┌─────────────┐            │             │
│  │ duckduckgo- │    │ google-mcp  │    │ SAP OData   │            │             │
│  │ mcp-server  │    │ slack-mcp   │    │ API         │            │             │
│  │ mcp-server- │    │ stripe-mcp  │    │ etc.        │            │             │
│  │ fetch       │    │ (containers)│    │             │            │             │
│  └─────────────┘    └─────────────┘    └─────────────┘            │             │
│                                                                                 │
└─────────────────────────────────────────────────────────────────────────────────┘
```

### Transport Type Comparison

| Type | Description | Use Case | Credentials |
|------|-------------|----------|-------------|
| **MCP_STDIO** | Spawn subprocess, JSON-RPC via stdin/stdout | Python/Node MCP servers | Env vars |
| **MCP_HTTP** | HTTP POST to container, JSON-RPC body | Containerized MCP servers | Headers |
| **DIRECT_REST** | Standard REST API calls | Non-MCP services (SAP, etc.) | Headers |

### MCP_STDIO (Preferred for new integrations)

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│  STDIO Transport                                                                │
│                                                                                 │
│  Executor spawns MCP server as subprocess:                                      │
│                                                                                 │
│    1. Spawn: duckduckgo-mcp-server                                              │
│    2. Set env: MCP_OAUTH_TOKEN=xxx, MCP_API_KEY=xxx                             │
│    3. Write to stdin: {"jsonrpc":"2.0","method":"tools/call",...}               │
│    4. Read from stdout: {"jsonrpc":"2.0","result":{...}}                        │
│    5. Process pool keeps subprocess alive for reuse                             │
│                                                                                 │
│  Benefits:                                                                      │
│    - No HTTP exposure (credentials stay in process)                             │
│    - No separate container needed                                               │
│    - Lower latency (no network)                                                 │
│    - Works with any STDIO-compatible MCP server                                 │
│                                                                                 │
└─────────────────────────────────────────────────────────────────────────────────┘
```

### MCP_HTTP (For containerized MCP servers)

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│  HTTP Transport                                                                 │
│                                                                                 │
│  Executor calls MCP container via HTTP:                                         │
│                                                                                 │
│    POST http://google-mcp:8080/mcp                                              │
│    Headers:                                                                     │
│      Authorization: Bearer ya29.xxx...                                          │
│      Content-Type: application/json                                             │
│    Body:                                                                        │
│      {"jsonrpc":"2.0","method":"tools/call","params":{...}}                     │
│                                                                                 │
│  Benefits:                                                                      │
│    - Standard HTTP/container deployment                                         │
│    - Works with Docker MCP servers                                              │
│    - Easy to scale independently                                                │
│                                                                                 │
└─────────────────────────────────────────────────────────────────────────────────┘
```

### DIRECT_REST (For non-MCP services)

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│  REST Transport                                                                 │
│                                                                                 │
│  Executor calls REST API directly (no MCP wrapping):                            │
│                                                                                 │
│    GET https://sap.example.com/odata/v4/Products                                │
│    Headers:                                                                     │
│      Authorization: Bearer xxx                                                  │
│      X-API-Key: sk_xxx                                                          │
│                                                                                 │
│  Benefits:                                                                      │
│    - Direct integration with existing APIs                                      │
│    - No MCP wrapper needed                                                      │
│    - Standard REST semantics                                                    │
│                                                                                 │
└─────────────────────────────────────────────────────────────────────────────────┘
```

### Service Configuration

```kotlin
// UpstreamRouter.kt - Service configurations
private val serviceConfigs = mapOf(
    // STDIO: Spawn subprocess (preferred for new integrations)
    "duckduckgo" to ServiceConfig(
        type = UpstreamType.MCP_STDIO,
        command = "duckduckgo-mcp-server"
    ),
    "web" to ServiceConfig(
        type = UpstreamType.MCP_STDIO,
        command = "mcp-server-fetch"
    ),
    
    // HTTP: Call MCP containers
    "google_gmail" to ServiceConfig(
        type = UpstreamType.MCP_HTTP,
        endpoint = "http://google-mcp:8080"
    ),
    "slack" to ServiceConfig(
        type = UpstreamType.MCP_HTTP,
        endpoint = "http://slack-mcp:8080"
    ),
    
    // REST: Direct API calls (non-MCP)
    "sap" to ServiceConfig(
        type = UpstreamType.DIRECT_REST
    )
)
```

---

## Complete Request Flow

```
┌─────┐     ┌─────────┐     ┌─────┐     ┌──────────┐     ┌──────────┐     ┌──────────┐
│Agent│     │ Gateway │     │ NPL │     │ RabbitMQ │     │ Executor │     │STDIO MCP │
└──┬──┘     └────┬────┘     └──┬──┘     └────┬─────┘     └────┬─────┘     └────┬─────┘
   │             │             │              │                │                │
   │ ① WebSocket │             │              │                │                │
   │────────────►│             │              │                │                │
   │             │             │              │                │                │
   │             │ ② Store     │              │                │                │
   │             │ context     │              │                │                │
   │             │             │              │                │                │
   │             │ ③ Policy    │              │                │                │
   │             │────────────►│              │                │                │
   │             │             │              │                │                │
   │             │             │ ④ Check      │                │                │
   │             │             │ ServiceReg   │                │                │
   │             │             │              │                │                │
   │             │             │ ⑤ Approve    │                │                │
   │             │             │────────────►│                │                │
   │             │             │              │                │                │
   │             │ ⑥ RequestId │              │ ⑦ Message      │                │
   │             │◄────────────│              │───────────────►│                │
   │             │             │              │                │                │
   │             │ ⑧ Suspend   │              │                │ ⑨ GET secrets  │
   │             │ on Channel  │              │                │───────► Vault  │
   │             │             │              │                │◄───────        │
   │             │             │              │                │                │
   │             │             │              │                │ ⑩ Spawn MCP    │
   │             │             │              │                │───────────────►│
   │             │             │              │                │                │
   │             │             │              │                │ ⑪ JSON-RPC     │
   │             │             │              │                │◄───────────────│
   │             │             │              │                │                │
   │             │             │ ⑫ Report     │                │                │
   │             │             │◄─────────────────────────────│                │
   │             │             │ completion   │                │                │
   │             │             │              │                │                │
   │             │ ⑬ Callback  │              │                │                │
   │             │◄────────────────────────────────────────────│                │
   │             │             │              │                │                │
   │             │ ⑭ Unblock   │              │                │                │
   │             │ Channel     │              │                │                │
   │             │             │              │                │                │
   │ ⑮ Response │             │              │                │                │
   │◄────────────│             │              │                │                │
   │             │              │              │                │                │
```

---

## Step-by-Step Flow

| Step | From | To | Action |
|------|------|-----|--------|
| ① | Agent | Gateway | WebSocket: tools/call (JSON-RPC 2.0) |
| ② | Gateway | - | Store body + mcpId in context store |
| ③ | Gateway | NPL | POST checkAndApprove (metadata only) |
| ④ | NPL | ServiceRegistry | Check isServiceEnabled(service) |
| ⑤ | NPL | RabbitMQ | Publish ExecutionNotificationMessage |
| ⑥ | NPL | Gateway | Return requestId |
| ⑦ | RabbitMQ | Executor | Deliver message |
| ⑧ | Gateway | - | Suspend coroutine on Channel |
| ⑨ | Executor | Vault | Fetch credentials |
| ⑩ | Executor | - | Spawn MCP subprocess |
| ⑪ | MCP | Executor | JSON-RPC response via stdout |
| ⑫ | Executor | NPL | Report completion (audit) |
| ⑬ | Executor | Gateway | POST /callback/{id} with result |
| ⑭ | Gateway | - | Write to Channel, coroutine resumes |
| ⑮ | Gateway | Agent | WebSocket: result |

---

## NPL Protocols

### ServiceRegistry

Controls which services are enabled/disabled:

```npl
@api
protocol[admin] ServiceRegistry() {
    private var enabledServices = setOf<Text>();
    
    @api
    permission[admin] enableService(serviceName: Text) {
        enabledServices = enabledServices.with(serviceName);
    };
    
    @api
    permission[admin] disableService(serviceName: Text) {
        enabledServices = enabledServices.without(serviceName);
    };
    
    function isServiceEnabled(serviceName: Text) returns Boolean -> {
        return enabledServices.contains(serviceName);
    };
}
```

### ToolExecutionPolicy

Validates and approves tool execution requests:

```npl
@api
protocol[gateway, executor] ToolExecutionPolicy() {
    initial state ready;
    
    @api
    permission[gateway] checkAndApprove(
        service: Text,
        operation: Text,
        metadata: Text,
        callbackUrl: Text
    ) returns Text | ready {
        // Check ServiceRegistry
        require(registry.isServiceEnabled(service), 
                "Service is disabled by administrator");
        
        // Generate request ID
        var reqId = generateRequestId();
        
        // Publish to RabbitMQ for Executor
        publishExecutionMessage(reqId, service, operation, callbackUrl);
        
        return reqId;
    };
    
    @api
    permission[executor] reportCompletion(
        reqId: Text,
        wasSuccessful: Boolean,
        failureMessage: Text,
        execDurationMs: Number
    ) | ready {
        // Log completion for audit trail
    };
}
```

---

## Security Properties

| Property | Enforcement |
|----------|-------------|
| Only NPL-approved requests execute | ServiceRegistry check + RabbitMQ isolation |
| Request body never in NPL | Gateway stores, Executor fetches via callback |
| Secrets never in Gateway/NPL | Executor fetches from Vault, injects to subprocess env |
| Full audit trail | NPL logs: service, operation, duration, success/failure |
| STDIO isolation | MCP servers run as sandboxed subprocesses |
| Credential containment | Credentials only exist in Executor process memory |

---

## Vault Credential Structure

```
secret/
└── data/
    └── tenants/
        └── {tenant_id}/
            └── users/
                └── {user_id}/
                    ├── duckduckgo/
                    │   └── api_key
                    ├── google/
                    │   ├── access_token
                    │   ├── refresh_token
                    │   └── expires_at
                    ├── slack/
                    │   └── access_token
                    └── web/
                        └── api_key
```

---

## Current Implementation Status

### Working Features

| Feature | Status | Notes |
|---------|--------|-------|
| WebSocket MCP Server | ✅ | Gateway accepts agent connections |
| NPL Policy Enforcement | ✅ | ServiceRegistry + ToolExecutionPolicy |
| RabbitMQ Messaging | ✅ | Async execution flow |
| Vault Integration | ✅ | Credential fetching |
| STDIO MCP Execution | ✅ | duckduckgo-mcp-server, mcp-server-fetch |
| Keycloak Auth | ✅ | OIDC token validation |
| Docker Compose | ✅ | Full local deployment |

### Real MCP Tools

| Tool | MCP Server | Description |
|------|------------|-------------|
| `duckduckgo_search` | duckduckgo-mcp-server | Real DuckDuckGo search |
| `duckduckgo_fetch_content` | duckduckgo-mcp-server | Fetch webpage content |
| `web_fetch` | mcp-server-fetch | Fetch any URL |

### Mock Tools (for demo)

| Tool | Description |
|------|-------------|
| `google_gmail_send_email` | Simulated email sending |
| `slack_send_message` | Simulated Slack message |
| `google_calendar_create_event` | Simulated calendar event |

---

## Technology Stack

| Component | Technology |
|-----------|------------|
| Gateway | Kotlin, Ktor, WebSocket |
| NPL Engine | Noumena Platform |
| Executor | Kotlin, Ktor, RabbitMQ client |
| Message Queue | RabbitMQ 4.0 |
| Secrets | HashiCorp Vault |
| Auth | Keycloak (OIDC) |
| MCP Servers | Python (duckduckgo-mcp-server, mcp-server-fetch) |
| Container | Docker Compose |

---

## Running the Demo

```bash
# Start all services
cd deployments
docker compose up -d

# Run comprehensive demo
source .venv/bin/activate
python3 full_demo.py

# Run verbose message flow demo
python3 verbose_flow_demo.py
```

See [README.md](../README.md) for full setup instructions.
