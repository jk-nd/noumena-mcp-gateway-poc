# Noumena MCP Gateway

A policy-enforced MCP (Model Context Protocol) gateway that enables AI agents to securely access external services through NPL governance, Vault credential management, and async execution.

## Architecture

```
                                                                    ┌─────────────┐
                                                                    │   VAULT     │
                                                                    │  (secrets)  │
┌───────────┐                                                       └──────▲──────┘
│   Agent   │                                                              │
│   (LLM)   │                                                              │
└─────┬─────┘                                                              │
      │ HTTP/WS                                                            │
      ▼                                                                    │
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                              NOUMENA MCP GATEWAY                                    │
│                                                                                     │
│  ┌─────────────┐      ┌─────────────┐      ┌──────────┐      ┌─────────────────┐   │
│  │   Gateway   │─────▶│ NPL Engine  │─────▶│ RabbitMQ │─────▶│    Executor     │───┼──┐
│  │             │      │             │      │          │      │                 │   │  │
│  │ POST /mcp   │      │ Policy:     │      │          │      │ Vault Client    │   │  │
│  │ WS /mcp/ws  │      │ - Approve   │      │          │      │ Upstream Router │   │  │
│  │             │◀─────│ - Deny      │      │          │◀─────│ (STDIO/HTTP)    │   │  │
│  └──────┬──────┘      │ - Audit     │      │          │      └─────────────────┘   │  │
│         │             └─────────────┘      └──────────┘                            │  │
│         │                                                                          │  │
│         ▼                                                                          │  │
│  ┌─────────────┐                                                                   │  │
│  │  Keycloak   │                                                                   │  │
│  │   (auth)    │                                                                   │  │
│  └─────────────┘                                                                   │  │
│                                                                                     │  │
│  Security: Gateway has NO Vault access • Only Executor can fetch secrets           │  │
│                                                                                     │  │
└─────────────────────────────────────────────────────────────────────────────────────┘  │
                                                                                         │
      ┌──────────────────────────────────────────────────────────────────────────────────┘
      │
      ▼
┌──────────────────────────────────────────────────────────────┐
│                    UPSTREAM MCP SERVICES                     │
│                                                              │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐           │
│  │ DuckDuckGo  │  │    Fetch    │  │   Slack     │  ...      │
│  │   (STDIO)   │  │   (STDIO)   │  │   (REST)    │           │
│  └─────────────┘  └─────────────┘  └─────────────┘           │
│                                                              │
└──────────────────────────────────────────────────────────────┘
```

## Agent Connectivity

The Gateway supports multiple transport options for maximum agent compatibility:

| Endpoint | Transport | Best For |
|----------|-----------|----------|
| `POST /mcp` | HTTP | LangChain, ADK, Sligo.ai, simple agents |
| `WS /mcp/ws` | WebSocket | Streaming agents, long-running connections |

Both endpoints accept standard MCP JSON-RPC messages and return the same responses.

## Components

| Component | Role | Vault Access | Network Exposure |
|-----------|------|--------------|------------------|
| **Gateway** | MCP HTTP + WebSocket server, token validation, policy checks | ❌ No | Public (agents connect here) |
| **NPL Engine** | Policy evaluation via ServiceRegistry & ToolExecutionPolicy | ❌ No | Internal only |
| **Executor** | Fetches secrets from Vault, spawns MCP subprocesses | ✅ Yes | Internal only |
| **RabbitMQ** | Async message broker between Gateway and Executor | ❌ No | Internal only |
| **Vault** | Secure credential storage | N/A | Internal only |
| **Keycloak** | OIDC authentication provider | N/A | Public (auth) |

## Features

### Real MCP Tool Execution
- **DuckDuckGo Search** - Real search results via `duckduckgo-mcp-server`
- **Web Fetch** - Fetch URLs via `mcp-server-fetch`
- STDIO-based execution (no HTTP bridges needed)

### NPL Policy Enforcement
- ServiceRegistry controls which services are enabled/disabled
- ToolExecutionPolicy validates requests before execution
- Policy denial returns clear errors: "Service is disabled by administrator"
- Full audit trail via NPL protocol state

### Vault Credential Management
- Credentials stored at: `secret/data/tenants/{tenant}/users/{user}/{service}`
- Injected into MCP subprocess environment
- Never exposed over HTTP or to Gateway

## Quick Start

### Prerequisites

- Docker & Docker Compose
- Python 3.x (for demo scripts)
- JDK 17+ (for building from source)

### Run with Docker Compose

```bash
cd deployments
docker compose up -d

# Wait for services to be healthy
docker compose ps
```

### Run the Demo

```bash
# Create Python virtual environment
python3 -m venv .venv
source .venv/bin/activate
pip install websockets requests

# Run comprehensive demo (WebSocket)
python3 deployments/full_demo.py

# Run HTTP demo (for LangChain, ADK, Sligo.ai, etc.)
python3 deployments/http_demo.py

# Run verbose message flow demo
python3 deployments/verbose_flow_demo.py
```

### Access UIs

| Service | URL | Credentials |
|---------|-----|-------------|
| Keycloak Admin | http://localhost:11000 | admin / admin |
| Vault | http://localhost:8200 | Token: `dev-token` |
| RabbitMQ | http://localhost:15672 | guest / guest |

## Demo Output

The demo shows:

1. **Tool Discovery** - List available MCP tools
2. **Policy Approval** - DuckDuckGo search (enabled service) → SUCCESS
3. **Policy Denial** - Slack message (disabled service) → BLOCKED
4. **Real Results** - Actual search results from DuckDuckGo

```
[SUCCESS] Gateway exposes 6 tools:
       [MOCK] google_gmail_send_email
       [MOCK] slack_send_message  
       [REAL MCP] web_fetch
       [REAL MCP] duckduckgo_search

[SUCCESS] Search completed in 1920ms
[POLICY] Policy check: APPROVED
       Search Results:
       1. Model Context Protocol - Wikipedia
       2. What is MCP? - Official docs

[POLICY] Policy check: DENIED
       Error: "Service is disabled by administrator"
```

## Project Structure

```
noumena-mcp-gateway/
├── gateway/                 # MCP WebSocket server (Kotlin/Ktor)
│   └── src/main/kotlin/
│       ├── Application.kt   # Main entry, tool definitions
│       ├── server/          # McpServerHandler
│       ├── policy/          # NplClient
│       ├── messaging/       # RabbitMQ publisher
│       └── context/         # Request context store
│
├── executor/                # Secret handler & MCP executor (Kotlin/Ktor)
│   └── src/main/kotlin/
│       ├── Application.kt   # Main entry
│       ├── upstream/        # UpstreamRouter, StdioMcpClient
│       ├── secrets/         # VaultClient
│       ├── messaging/       # RabbitMQ consumer
│       └── npl/             # NPL completion reporting
│
├── shared/                  # Shared models
│
├── npl/                     # NPL protocol definitions
│   └── src/main/npl-1.0/
│       ├── registry/        # ServiceRegistry
│       └── services/        # ToolExecutionPolicy
│
├── deployments/             # Docker Compose & demo scripts
│   ├── docker-compose.yml
│   ├── full_demo.py
│   └── verbose_flow_demo.py
│
├── keycloak-provisioning/   # Terraform for Keycloak setup
├── rabbitmq/                # RabbitMQ configuration
└── docs/                    # Architecture documentation
```

## Configuration

### Environment Variables

#### Gateway
| Variable | Default | Description |
|----------|---------|-------------|
| `NPL_URL` | `http://npl-engine:12000` | NPL Engine URL |
| `KEYCLOAK_URL` | `http://keycloak:11000` | Keycloak URL |
| `RABBITMQ_HOST` | `rabbitmq` | RabbitMQ host |
| `DEV_MODE` | `false` | Bypass NPL policy checks |

#### Executor
| Variable | Default | Description |
|----------|---------|-------------|
| `VAULT_ADDR` | `http://vault:8200` | Vault address |
| `VAULT_TOKEN` | `dev-token` | Vault token |
| `RABBITMQ_HOST` | `rabbitmq` | RabbitMQ host |
| `DEV_MODE` | `false` | Use mock credentials |

## Message Flow

### Successful Request (duckduckgo_search)

```
1. Agent → Gateway         WebSocket: tools/call {name: "duckduckgo_search"}
2. Gateway → NPL Engine    POST /checkAndApprove {service: "duckduckgo"}
3. NPL Engine              Check ServiceRegistry.isServiceEnabled("duckduckgo") → true
4. NPL Engine → RabbitMQ   Publish ExecutionNotificationMessage
5. Executor ← RabbitMQ     Consume message
6. Executor → Vault        GET /v1/secret/data/tenants/.../duckduckgo
7. Executor                Spawn duckduckgo-mcp-server subprocess
8. Executor → MCP          stdin: JSON-RPC tools/call
9. MCP Server              Perform real DuckDuckGo search
10. MCP → Executor         stdout: JSON-RPC result
11. Executor → RabbitMQ    Publish result
12. Gateway ← RabbitMQ     Receive result
13. Gateway → Agent        WebSocket: result with search data
```

### Denied Request (slack_send_message)

```
1. Agent → Gateway         WebSocket: tools/call {name: "slack_send_message"}
2. Gateway → NPL Engine    POST /checkAndApprove {service: "slack"}
3. NPL Engine              Check ServiceRegistry.isServiceEnabled("slack") → false
4. NPL Engine → Gateway    Error: "Service is disabled by administrator"
5. Gateway → Agent         WebSocket: error (POLICY_DENIED)

   ❌ Request never reaches Executor - blocked at policy layer
```

## Building from Source

```bash
# Build all modules
./gradlew build

# Build Docker images
cd deployments
docker compose build
```

## Testing

```bash
# Run unit tests
./gradlew test

# Run integration tests (requires Docker)
./gradlew :integration-tests:test
```

## License

Copyright 2025 Noumena Digital AG. All rights reserved.
