# Noumena MCP Gateway

An MCP (Model Context Protocol) gateway that enables AI agents and users to securely access upstream MCP services through Envoy-based routing, two-tier authorization (fast RBAC + stateful NPL governance), JWT authentication, and credential injection.

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
                   │Governance │ │  │                       │
                   │ Service   │ │  │  supergateway sidecars│
                   │(ext_authz)│ │  │ ┌─────────┐ ┌───────┐│
                   └─────┬─────┘ │  │ │DuckDuck │ │GitHub ││
                         │       │  │ │Go  MCP  │ │ MCP   ││...
                   ┌─────┴─────┐ │  │ └─────────┘ └───────┘│
                   │NPL Engine │ │  └──────────────────────┘
                   │(Policies) │ │
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

**Four-tier network isolation**: Agents can only reach Envoy and Keycloak (public-net). NPL Engine and Governance Service are on policy-net. Vault and Credential Proxy are on secrets-net. Backend MCP services (supergateway sidecars) are on backend-net.

## Components

| Component | Role |
|-----------|------|
| **Envoy AI Gateway** | MCP protocol handling, JWT validation (Keycloak JWKS), routing to backends, SSE streaming |
| **Governance Service** | Two-tier ext_authz: fast RBAC from `services.yaml` (sub-ms) + stateful NPL governance |
| **NPL Engine** | Stateful policy evaluation via ServiceRegistry, ToolPolicy, UserToolAccess |
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

Envoy routes GET /mcp with `timeout: 0s` (never timeout) so notification streams stay open indefinitely. JWT auth and ext_authz governance checks happen once at stream setup.

## Two-Tier Authorization

Every MCP request passes through Envoy's `ext_authz` filter, which calls the Governance Service. Authorization happens in two tiers:

### Tier 1: Fast RBAC (sub-ms, no network call)

The governance service checks `services.yaml` `user_access` config locally:
- Does this user exist in the RBAC config?
- Does this user have access to this service/tool?
- Wildcard (`*`) support for granting all tools in a service

If denied at this tier, NPL is never called. Response time: ~5ms.

### Tier 2: Stateful NPL Governance (network call)

Only for `tools/call` requests that pass the fast path:
- `ToolPolicy.checkAccess` -- Is this tool enabled at the service level?
- `UserToolAccess.hasAccess` -- Does this user have dynamic/stateful access?
- Supports approval workflows, audit trail, dynamic policy changes

### Authorization Flow

```
1. Agent → Envoy            POST /mcp: tools/call "search"
2. Envoy                    Validates JWT (Keycloak JWKS)
3. Envoy → Governance       ext_authz: forwards JSON-RPC body + JWT
4. Governance               Parses JSON-RPC: method=tools/call, tool=search
5. Governance               Decodes JWT: userId=jarvis@acme.com
6. Governance               FAST PATH: services.yaml RBAC check (sub-ms)
7. Governance → NPL         SLOW PATH: ToolPolicy.checkAccess (stateful)
8. Governance → NPL         SLOW PATH: UserToolAccess.hasAccess (stateful)
9. Governance → Envoy       200 (allow) or 403 (deny)
10. Envoy → Backend MCP     Routes to supergateway sidecar (if allowed)
11. Backend → Envoy → Agent SSE response streamed back
```

**Fail-closed**: If NPL or the governance service is unavailable, all requests are denied.

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

### services.yaml

Defines upstream MCP services, their tools, and user access (used for fast RBAC):

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

The `user_access` section is read by the governance service for fast RBAC lookups (Tier 1).

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

#### Governance Service

| Variable | Default | Description |
|----------|---------|-------------|
| `PORT` | `8090` | HTTP port |
| `NPL_URL` | `http://npl-engine:12000` | NPL Engine URL |
| `KEYCLOAK_URL` | `http://keycloak:11000` | Keycloak URL |
| `KEYCLOAK_REALM` | `mcpgateway` | Keycloak realm |
| `GATEWAY_USERNAME` | `gateway` | Service account for NPL calls |
| `GATEWAY_PASSWORD` | `Welcome123` | Service account password |
| `DEV_MODE` | `false` | Bypass NPL policy checks |
| `SERVICES_CONFIG_PATH` | `/app/configs/services.yaml` | Path to RBAC config |

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
├── governance-service/     # ext_authz backend (Kotlin/Ktor)
│   └── src/main/kotlin/
│       ├── Application.kt  # Two-tier auth: fast RBAC + NPL
│       └── NplGovernanceClient.kt  # NPL Engine communication
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
│   ├── services.yaml       # Service definitions + user RBAC
│   └── credentials.yaml    # Credential mappings
│
├── deployments/            # Docker Compose + Envoy config
│   ├── docker-compose.yml  # Full stack with network isolation
│   ├── envoy/
│   │   └── envoy-config.yaml  # Envoy static config (JWT, ext_authz, routing)
│   └── docker/
│       ├── Dockerfile.governance     # Governance service image
│       ├── Dockerfile.supergateway   # Supergateway sidecar image
│       ├── Dockerfile.credential-proxy
│       └── Dockerfile.mock-calendar  # HTTP-native MCP server image
│
├── keycloak/               # Custom Keycloak image
├── keycloak-provisioning/  # Terraform for Keycloak setup
├── integration-tests/      # Integration test suite
└── docs/                   # Documentation
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

> **Warning:** The test suite has not been updated to reflect the Envoy + governance service refactoring. Integration and E2E tests will likely fail. Test update is pending.

```bash
# Run unit tests
./gradlew test

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
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"search","arguments":{"query":"hello"}}}'

# 3. Test denial (user without access should get 403)
```

## Documentation

| Document | Description |
|----------|-------------|
| [Architecture](docs/ARCHITECTURE_V2.md) | Full architecture: Envoy gateway, governance service, message flows |
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
