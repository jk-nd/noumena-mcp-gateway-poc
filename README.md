# Noumena MCP Gateway

A transparent MCP (Model Context Protocol) proxy that enables AI agents and users to securely access upstream MCP services through NPL policy enforcement, JWT authentication, credential injection, and tool namespacing.

## Architecture

```
                         ┌──────────────┐
                         │   Keycloak   │
                         │   (OIDC)     │
                         └──────┬───────┘
                                │
   Agents / Users ──────► ┌─────┴──────┐        ┌──────────────┐
   (JWT auth)              │  Gateway   │◄──────►│  NPL Engine  │
                           │  MCP Proxy │        │  (Policies)  │
                           └─────┬──────┘        └──────────────┘
                                 │
              ┌──────────────────┼──────────────────┐
              │                  │                   │
        ┌─────┴─────┐    ┌──────┴─────┐     ┌──────┴──────┐
        │ DuckDuckGo │    │   GitHub   │     │   Gemini    │ ...
        │ MCP Server │    │ MCP Server │     │ MCP Server  │
        └───────────┘    └────────────┘     └─────────────┘
```

**Network isolation**: Agents can only reach the Gateway and Keycloak. Upstream MCPs are isolated on backend-net. NPL Engine is on policy-net. The Gateway spans all three networks.

## Components

| Component | Role |
|-----------|------|
| **Gateway** | Transparent MCP proxy -- authenticates, checks policy, injects credentials, forwards |
| **NPL Engine** | Policy evaluation via ServiceRegistry, ToolPolicy, UserToolAccess |
| **Keycloak** | OIDC authentication provider with user/role management |
| **Credential Proxy** | Fetches secrets from Vault, injects into upstream MCP calls |
| **Vault** | Secret storage (API keys, tokens, passwords) |
| **TUI** | Interactive CLI wizard for managing the Gateway |
| **Upstream MCPs** | Docker/NPM/HTTP MCP servers (DuckDuckGo, GitHub, Gemini, etc.) |

## Quick Start

### Prerequisites

- Docker & Docker Compose
- Node.js 18+ (for TUI wizard and MCP Inspector)

### 1. Start the Stack

```bash
cd deployments
docker compose up -d

# Wait for all services to be healthy
docker compose ps
```

### 2. Start the TUI Wizard

```bash
cd tui
npm install
npm start
```

Log in with admin credentials (`admin` / `Welcome123`).

### 3. First-Time Setup

The TUI guides you through:

1. **NPL Bootstrap** -- Creates protocol instances (ServiceRegistry, UserRegistry)
2. **Add a service** -- e.g., Quick Start > DuckDuckGo, or search Docker Hub
3. **Enable the service** -- Activates it in NPL ServiceRegistry
4. **Enable tools** -- Select which tools to activate in NPL ToolPolicy
5. **Register users** -- Add Keycloak users for Gateway access
6. **Grant tool access** -- Assign tools to users via NPL UserToolAccess
7. **Set up credentials** (optional) -- Store API keys in Vault via TUI

### 4. Connect with MCP Inspector

```bash
npx @modelcontextprotocol/inspector
```

- **Transport**: Streamable HTTP
- **URL**: `http://localhost:8000/mcp`
- **Authentication**: OAuth (redirects to Keycloak login)
- Log in as a registered user (e.g., `jarvis` / `Welcome123`)

### Access UIs

| Service | URL | Credentials |
|---------|-----|-------------|
| Keycloak Admin | http://localhost:11000 | admin / welcome |
| NPL Inspector | http://localhost:12100 | admin / Welcome123 |

## Agent Connectivity

| Endpoint | Transport | Best For |
|----------|-----------|----------|
| `POST /mcp` | Streamable HTTP | MCP Inspector, LangChain, ADK |
| `WS /mcp/ws` | WebSocket | Streaming agents, long-running connections |
| `GET /sse` | SSE | Legacy MCP clients, browser EventSource |

All endpoints require JWT authentication (Bearer token or OAuth 2.0 flow).

## NPL Policy Enforcement

NPL is the **runtime source of truth** for all policy decisions. The TUI and `services.yaml` are the declarative configuration layer -- all changes are synced to NPL atomically.

### Protocol Hierarchy

```
ServiceRegistry              Which services are enabled org-wide
    │
ToolPolicy (per service)     Which tools are enabled per service
    │
UserRegistry                 Which users/agents are registered
    │
UserToolAccess (per user)    Which tools each user can access
    │
CredentialInjectionPolicy    Which credentials to inject per request
```

### Policy Check Flow

```
1. Agent → Gateway           POST /mcp: tools/call "duckduckgo.search"
2. Gateway                   Validates JWT (identity, roles)
3. Gateway                   Parses namespace: service=duckduckgo, tool=search
4. Gateway → NPL             ServiceRegistry: is duckduckgo enabled?
5. Gateway → NPL             ToolPolicy: is "search" enabled?
6. Gateway → NPL             UserToolAccess: does user have access?
7. NPL → Gateway             Allowed / Denied
8. Gateway → Upstream MCP    Forward tools/call (if allowed)
9. Upstream → Gateway        Result
10. Gateway → Agent          JSON-RPC response
```

**Fail-closed**: If NPL is unavailable, all requests are denied.

## Credential Injection

Secrets (API keys, tokens) are stored in Vault and injected transparently at runtime.

### Two Credential Scopes

| Scope | Vault Path Pattern | Use Case |
|-------|-------------------|----------|
| **Tenant-level** | `secret/data/tenants/{tenant}/services/SERVICE/...` | Shared org credentials (e.g., company Slack workspace) |
| **User-level** | `secret/data/tenants/{tenant}/users/{user}/SERVICE/...` | Personal credentials (e.g., individual GitHub tokens) |

### Injection Flow

1. Admin stores secrets in Vault via TUI (choose tenant-level or user-level scope)
2. At runtime, the JWT provides `{tenant}` and `{user}` values
3. Gateway resolves credential name via NPL CredentialInjectionPolicy
4. Credential Proxy fetches from Vault, injects as environment variables or headers

See [docs/CREDENTIAL_INJECTION.md](docs/CREDENTIAL_INJECTION.md) for architecture details.

## TUI Wizard

The interactive CLI manages all Gateway operations with atomic operations and rollback:

```bash
cd tui && npm start
```

### Capabilities

- **Service management** -- Add, enable/disable, remove services (Docker Hub, NPM, custom)
- **Tool management** -- Enable/disable individual tools per service
- **User management** -- Register/deregister Keycloak users, grant/revoke tool access
- **Credential management** -- Create credential mappings, choose scope, store secrets in Vault
- **Gateway configuration** -- Sync config to/from NPL, export/import YAML
- **Container control** -- Pull, start, stop Docker containers

### Atomic Operations

All TUI operations that modify state are **atomic with rollback**:

1. Changes are written to `services.yaml`
2. NPL is synced via the Gateway
3. If NPL sync fails, `services.yaml` is rolled back and an error is shown
4. Success is only reported when NPL confirms

> **Principle**: "If the engine doesn't confirm, you cannot write to services.yaml."

## Configuration

### services.yaml

Defines upstream MCP services, their tools, and user access:

```yaml
services:
  - name: "duckduckgo"
    displayName: "DuckDuckGo"
    type: "MCP_STDIO"
    enabled: true
    command: "docker run -i --rm mcp/duckduckgo"
    tools:
      - name: "search"
        enabled: true

user_access:
  users:
    - userId: "jarvis@acme.com"
      keycloakId: "..."
      tools:
        duckduckgo:
          - "search"
```

### credentials.yaml

Maps credential names to Vault paths and injection targets:

```yaml
credentials:
  google_gemini:
    vault_path: secret/data/tenants/{tenant}/services/gemini/api
    injection:
      type: env
      mapping:
        api_key: GEMINI_API_KEY
```

### Environment Variables (Gateway)

| Variable | Default | Description |
|----------|---------|-------------|
| `NPL_URL` | `http://npl-engine:12000` | NPL Engine URL |
| `KEYCLOAK_URL` | `http://keycloak:11000` | Keycloak URL (internal) |
| `KEYCLOAK_EXTERNAL_URL` | `http://keycloak:11000` | Keycloak URL (browser-facing) |
| `KEYCLOAK_REALM` | `mcpgateway` | Keycloak realm |
| `DEV_MODE` | `false` | Bypass NPL policy checks |
| `SERVICES_CONFIG_PATH` | `/app/configs/services.yaml` | Path to services config |

> **Note**: For local dev with Docker, add `127.0.0.1 keycloak` to `/etc/hosts` so both the browser and Docker containers resolve the same hostname.

## Project Structure

```
noumena-mcp-gateway/
├── gateway/                 # MCP proxy server (Kotlin/Ktor)
│   └── src/main/kotlin/
│       ├── Application.kt   # Routing, OAuth, SSE/WebSocket
│       ├── server/          # McpServerHandler (proxy logic)
│       ├── policy/          # NplClient (NPL policy checks)
│       ├── upstream/        # UpstreamRouter, SessionManager
│       ├── credentials/     # UserContext, CredentialProxyClient
│       └── auth/            # JWT validation
│
├── credential-proxy/        # Credential injection service
│   └── src/main/kotlin/     # VaultClient, CredentialSelector
│
├── npl/                     # NPL protocol definitions
│   └── src/main/npl-1.0/
│       ├── registry/        # ServiceRegistry
│       ├── services/        # ToolPolicy
│       ├── users/           # UserRegistry, UserToolAccess
│       └── policies/        # CredentialInjectionPolicy
│
├── tui/                     # Gateway Wizard (interactive CLI)
│   └── src/
│       ├── cli.ts           # Main wizard
│       └── lib/             # Config, API, Docker clients
│
├── configs/                 # Runtime configuration
│   ├── services.yaml        # Service & user definitions
│   ├── credentials.yaml     # Credential mappings
│   └── gateway.yaml         # Gateway settings
│
├── deployments/             # Docker Compose
│   └── docker-compose.yml   # Full stack with network isolation
│
├── keycloak-provisioning/   # Terraform for Keycloak setup
├── integration-tests/       # Integration test suite
└── docs/                    # Documentation
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

# Run integration tests (requires Docker stack)
./gradlew :integration-tests:test
```

## Documentation

| Document | Description |
|----------|-------------|
| [Architecture](docs/ARCHITECTURE_V2.md) | Full architecture: transparent proxy, message flows, OAuth |
| [Credential Injection](docs/CREDENTIAL_INJECTION.md) | Credential injection system design and Vault integration |
| [User Access Control](docs/USER_ACCESS_CONTROL.md) | Per-user/agent tool access control via NPL |
| [NPL as Source of Truth](docs/NPL_AS_SOURCE_OF_TRUTH.md) | NPL as runtime source of truth for policies |
| [Declarative NPL Sync](docs/DECLARATIVE_NPL_SYNC.md) | YAML-to-NPL declarative sync model |
| [NPL Party Strategy](docs/NPL_PARTY_ASSIGNMENT_STRATEGY.md) | Party assignment strategy across protocols |
| [TUI Credential Management](docs/TUI_CREDENTIAL_MANAGEMENT.md) | Credential management via TUI |
| [TUI Add Service Guide](docs/TUI_ADD_SERVICE_GUIDE.md) | Adding MCP services via TUI |

## License

Copyright 2025-2026 Noumena Digital AG. All rights reserved.
