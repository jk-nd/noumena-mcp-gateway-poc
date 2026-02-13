# Noumena MCP Gateway

An MCP (Model Context Protocol) gateway that enables AI agents and users to securely access upstream MCP services through Envoy-based routing, OPA policy evaluation with cached NPL state, JWT authentication, and credential injection.

## Architecture

```
                         ┌──────────────┐
                         │   Keycloak   │
                         │   (OIDC)     │
                         └──────┬───────┘
                                │ public-net
   Agents / Users ──────► ┌─────┴──────┐
   (JWT auth)              │   Envoy    │
                           │  AI Gateway│
                           └──┬──┬──┬───┘
                              │  │  │
                   policy-net │  │  │ backend-net
                              │  │  │
                   ┌──────────┴┐ │  ├──────────────────────┐
                   │   OPA     │ │  │                       │
                   │ Sidecar   │ │  │  supergateway sidecars│
                   │(ext_authz)│ │  │ ┌─────────┐ ┌───────┐│
                   └─────┬─────┘ │  │ │DuckDuck │ │GitHub ││
                         │       │  │ │Go  MCP  │ │ MCP   ││...
                   ┌─────┴─────┐ │  │ └─────────┘ └───────┘│
                   │NPL Engine │ │  └──────────────────────┘
                   │(State Mgr)│ │
                   └───────────┘ │ secrets-net
                                 │
                          ┌──────┴──────┐
                          │ Credential  │
                          │   Proxy     │
                          └──────┬──────┘
                          ┌──────┴──────┐
                          │    Vault    │
                          │  (secrets)  │
                          └─────────────┘
```

**Four-tier network isolation**: Agents can only reach Envoy and Keycloak (public-net). OPA and NPL Engine are on policy-net. Vault and Credential Proxy are on secrets-net. Backend MCP services (supergateway sidecars) are on backend-net.

## Components

| Component | Role |
|-----------|------|
| **Envoy AI Gateway** | MCP protocol handling, JWT validation (Keycloak JWKS), routing to backends, SSE streaming |
| **OPA Sidecar** | ext_authz policy evaluation via Rego; self-polls NPL for state (cached 5s) |
| **NPL Engine** | Policy state manager: ServiceRegistry, ToolPolicy, UserToolAccess (admin mutations via TUI/API) |
| **Supergateway Sidecars** | Wrap STDIO MCP tools as Streamable HTTP endpoints |
| **Mock Calendar MCP** | HTTP-native MCP server for bilateral streaming tests (SSE notifications) |
| **Keycloak** | OIDC authentication provider with user/role management |
| **Credential Proxy** | Fetches secrets from Vault, injects into supergateway containers at startup |
| **Vault** | Secret storage (API keys, tokens, passwords) |
| **TUI** | Interactive CLI wizard for managing services, users, and credentials |

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
5. **Register users** -- Add Keycloak users for gateway access
6. **Grant tool access** -- Assign tools to users via NPL UserToolAccess
7. **Set up credentials** (optional) -- Store API keys in Vault via TUI

### 4. Connect with MCP Inspector

```bash
npx @modelcontextprotocol/inspector
```

- **Transport**: Streamable HTTP
- **URL**: `http://localhost:8000/mcp`
- **Authentication**: OAuth (automatic via built-in OAuth proxy endpoints)
- The gateway exposes RFC 9728/8414 OAuth discovery, redirects `/authorize` to Keycloak, and proxies `/token` — MCP Inspector handles the flow automatically
- Log in as a registered user (e.g., `jarvis` / `Welcome123`)

### Access UIs

| Service | URL | Credentials |
|---------|-----|-------------|
| Envoy Admin | http://localhost:9901 | (none) |
| OPA API | http://localhost:8181 | (none) |
| Keycloak Admin | http://localhost:11000 | admin / welcome |
| NPL Inspector | http://localhost:8080 | admin / Welcome123 |
| Vault UI | http://localhost:8200 | Token: dev-token |

## Agent Connectivity

| Endpoint | Transport | Auth |
|----------|-----------|------|
| `POST /mcp` | Streamable HTTP (SSE responses) | Bearer JWT |
| `GET /mcp` | SSE notification stream (server-initiated) | Bearer JWT |
| `GET /sse` | SSE (legacy) | Bearer JWT |

All endpoints require JWT authentication via Keycloak. Envoy validates tokens using Keycloak's JWKS endpoint.

### Bilateral Streaming

The gateway supports **bilateral (bidirectional) streaming** via MCP Streamable HTTP:
- **POST /mcp** — agent-to-server: JSON-RPC requests (initialize, tools/list, tools/call)
- **GET /mcp** — server-to-agent: long-lived SSE stream for server-initiated notifications

Envoy routes GET /mcp with `timeout: 0s` (never timeout) so notification streams stay open indefinitely. JWT auth and ext_authz checks happen once at stream setup.

## OPA Policy Authorization

Every MCP request passes through Envoy's `ext_authz` filter, which calls OPA via gRPC. OPA evaluates a Rego policy (`policies/mcp_authz.rego`) that fetches NPL state via `http.send()` with response caching.

### How It Works

```
Envoy (port 8000)
  │ ext_authz (gRPC)
  ▼
OPA Sidecar (port 9191 gRPC / 8181 HTTP)
  ├─ Fast path: Rego rules + cached NPL data via http.send() (~0.1ms from cache)
  │   Cache refreshes every 5s — OPA polls NPL directly, no sync service needed
  │
  └─ Slow path: http.send() → NPL Engine (uncached, ~50ms)
      Only for first request or after cache expires
```

**Only two components in the policy chain: OPA + NPL. No governance service. No policy-sync service.**

### Authorization Flow

```
1. Agent → Envoy            POST /mcp: tools/call "duckduckgo.search"
2. Envoy                    Validates JWT (Keycloak JWKS)
3. Envoy → OPA              ext_authz (gRPC): forwards JSON-RPC body + headers
4. OPA                      Decodes JWT: userId=jarvis@acme.com
5. OPA                      Parses JSON-RPC: method=tools/call, tool=duckduckgo.search
6. OPA                      Fetches NPL data via http.send() (cached 5s):
                              - ServiceRegistry → enabled services
                              - ToolPolicy → enabled tools per service
                              - UserToolAccess → user-to-service-to-tool matrix
7. OPA                      Evaluates: service enabled? tool enabled? user has access?
8. OPA → Envoy              allow or deny
9. Envoy → Backend MCP      Routes to supergateway sidecar (if allowed)
10. Backend → Envoy → Agent SSE response streamed back
```

### Policy Rules

| Request Type | Rule |
|-------------|------|
| `initialize`, `tools/list`, `ping`, `notifications/*` | Always allowed (non-tool methods) |
| `GET /mcp` (stream setup) | Allowed if user has any tool access |
| `tools/call` | Service must be enabled, tool must be enabled, user must have access |
| Missing JWT or unknown user | Denied |
| NPL unavailable (cache expired) | Denied (fail-closed) |

### Cache Behavior

- **NPL data**: cached 5s — OPA polls NPL on next request after expiry
- **Keycloak gateway token**: cached 60s
- **On NPL unavailable**: serves stale cache until TTL expires, then fail-closed
- **Admin mutations propagate**: disable a tool in NPL → within 5s, OPA reflects the change

### NPL Protocol Hierarchy

```
ServiceRegistry              Which services are enabled org-wide
    |
ToolPolicy (per service)     Which tools are enabled per service
    |
UserRegistry                 Which users/agents are registered
    |
UserToolAccess (per user)    Which tools each user can access
    |
CredentialInjectionPolicy    Which credentials to inject per request
```

## Credential Injection

Secrets (API keys, tokens) are stored in Vault and injected into supergateway containers at startup.

### Injection Flow

1. Admin stores secrets in Vault via TUI
2. Supergateway container starts and calls Credential Proxy
3. Credential Proxy fetches from Vault, returns injected fields
4. Supergateway exports fields as environment variables
5. STDIO MCP tool spawned with credentials in environment

### Two Credential Scopes

| Scope | Vault Path Pattern | Use Case |
|-------|-------------------|----------|
| **Tenant-level** | `secret/data/tenants/{tenant}/services/SERVICE/...` | Shared org credentials |
| **User-level** | `secret/data/tenants/{tenant}/users/{user}/SERVICE/...` | Personal credentials |

See [docs/CREDENTIAL_INJECTION.md](docs/CREDENTIAL_INJECTION.md) for architecture details.

## TUI Wizard

The interactive CLI manages all gateway operations with atomic operations and rollback:

```bash
cd tui && npm start
```

### Capabilities

- **Service management** -- Add, enable/disable, remove services (Docker Hub, NPM, custom)
- **Tool discovery** -- Discover tools from Docker and NPM/command-based services
- **Tool management** -- Enable/disable individual tools per service
- **User management** -- Register/deregister Keycloak users, grant/revoke tool access
- **Credential management** -- Create credential mappings, choose scope, store secrets in Vault
- **Configuration management** -- View, edit, backup, import YAML config

### NPL-First Operations

All TUI operations that modify state follow the **NPL-first** pattern:

1. Changes are written to NPL (source of truth) first
2. On success, `services.yaml` is updated as a persistent cache
3. If the NPL write fails, `services.yaml` is unchanged

> **Principle**: "NPL is the source of truth. YAML is a persistent cache."

## Configuration

### services.yaml (Bootstrap Only)

Defines upstream MCP services and their tools. Used by the TUI for service management and as a persistent cache of NPL state. **Not read at runtime** — OPA fetches all policy data directly from NPL.

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
  default_template:
    enabled: false
  users:
    - userId: "jarvis@acme.com"
      keycloakId: "..."
      tools:
        duckduckgo:
          - "*"       # Wildcard: all tools
```

### credentials.yaml

Maps credential names to Vault paths and injection targets:

```yaml
mode: simple
tenant: acme

credentials:
  google_gemini:
    vault_path: secret/data/tenants/{tenant}/services/gemini/api
    injection:
      type: env
      mapping:
        api_key: GEMINI_API_KEY

service_defaults:
  gemini: google_gemini
```

### Environment Variables

#### OPA Sidecar

| Variable | Default | Description |
|----------|---------|-------------|
| `NPL_URL` | `http://npl-engine:12000` | NPL Engine URL |
| `KEYCLOAK_URL` | `http://keycloak:11000` | Keycloak URL |
| `KEYCLOAK_REALM` | `mcpgateway` | Keycloak realm |
| `GATEWAY_USERNAME` | `gateway` | Service account for NPL calls |
| `GATEWAY_PASSWORD` | `Welcome123` | Service account password |

#### Credential Proxy

| Variable | Default | Description |
|----------|---------|-------------|
| `PORT` | `9002` | HTTP port |
| `CREDENTIAL_MODE` | `simple` | `simple` or `expert` |
| `VAULT_ADDR` | `http://vault:8200` | Vault address |
| `VAULT_TOKEN` | `dev-token` | Vault auth token |

## Project Structure

```
noumena-mcp-gateway/
├── policies/              # OPA Rego policies (ext_authz)
│   ├── mcp_authz.rego     # Authorization policy: JWT decode, JSON-RPC parse, NPL data fetch, RBAC
│   └── mcp_authz_test.rego # Rego unit tests (opa test policies/ -v)
│
├── credential-proxy/       # Credential injection service (Kotlin/Ktor)
│   └── src/main/kotlin/    # VaultClient, CredentialSelector
│
├── shared/                 # Shared config models and policy types
│   └── src/main/kotlin/    # ServicesConfig, PolicyModels
│
├── npl/                    # NPL protocol definitions
│   └── src/main/npl-1.0/
│       ├── registry/       # ServiceRegistry
│       ├── services/       # ToolPolicy
│       ├── users/          # UserRegistry, UserToolAccess
│       └── policies/       # CredentialInjectionPolicy
│
├── tui/                    # Gateway Wizard (interactive CLI)
│   └── src/
│       ├── cli.ts          # Main wizard
│       └── lib/            # Config, API, Docker clients
│
├── mock-calendar-mcp/      # HTTP-native MCP server (bilateral streaming tests)
│   └── server.js           # Express: POST /mcp + GET /mcp SSE stream
│
├── configs/                # Runtime configuration
│   ├── services.yaml       # Service definitions + user access (bootstrap/cache)
│   └── credentials.yaml    # Credential mappings
│
├── deployments/            # Docker Compose + Envoy config
│   ├── docker-compose.yml  # Full stack with network isolation
│   ├── envoy/
│   │   └── envoy-config.yaml  # Envoy static config (JWT, ext_authz → OPA, routing)
│   └── docker/
│       ├── Dockerfile.supergateway   # Supergateway sidecar image
│       ├── Dockerfile.credential-proxy
│       └── Dockerfile.mock-calendar  # HTTP-native MCP server image
│
├── keycloak/               # Custom Keycloak image
├── keycloak-provisioning/  # Terraform for Keycloak setup
├── integration-tests/      # Integration test suite
└── docs/                   # Documentation
```

## Running OPA Tests

```bash
# Install OPA (macOS)
brew install opa

# Run Rego unit tests
opa test policies/ -v
```

## Testing

> **Warning:** The integration test suite has not been updated to reflect the OPA refactoring. Integration and E2E tests will likely fail. Test update is pending.

```bash
# Manual verification (requires Docker stack running)
# 1. Get a token
TOKEN=$(curl -sf -X POST 'http://localhost:11000/realms/mcpgateway/protocol/openid-connect/token' \
  -d 'grant_type=password&client_id=mcpgateway&username=jarvis&password=Welcome123' \
  | python3 -c "import sys,json; print(json.load(sys.stdin)['access_token'])")

# 2. Call a tool (should 200 if jarvis has access)
curl -X POST 'http://localhost:8000/mcp' \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"duckduckgo.search","arguments":{"query":"hello"}}}'

# 3. Test denial (user without access should get 403)

# 4. Inspect OPA data (debugging)
curl http://localhost:8181/v1/data
```

## Documentation

| Document | Description |
|----------|-------------|
| [Architecture](docs/ARCHITECTURE_V2.md) | Full architecture: Envoy gateway, OPA policy, NPL state management |
| [Credential Injection](docs/CREDENTIAL_INJECTION.md) | Credential injection system design and Vault integration |
| [User Access Control](docs/USER_ACCESS_CONTROL.md) | Per-user/agent tool access control via NPL |
| [NPL as Source of Truth](docs/NPL_AS_SOURCE_OF_TRUTH.md) | NPL as runtime source of truth for policies |
| [Declarative NPL Sync](docs/DECLARATIVE_NPL_SYNC.md) | YAML-to-NPL declarative sync model |
| [NPL Party Strategy](docs/NPL_PARTY_ASSIGNMENT_STRATEGY.md) | Party assignment strategy across protocols |
| [TUI Credential Management](docs/TUI_CREDENTIAL_MANAGEMENT.md) | Credential management via TUI |
| [TUI Add Service Guide](docs/TUI_ADD_SERVICE_GUIDE.md) | Adding MCP services via TUI |
| [CIQ Offer Scope](docs/CIQ_OFFER_SCOPE.md) | Phase 1/Phase 2 architecture for CIQ deployment |

## License

Copyright 2025-2026 Noumena Digital AG. All rights reserved.
