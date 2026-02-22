# Noumena MCP Gateway

An MCP (Model Context Protocol) gateway that enables AI agents and users to securely access upstream MCP services through Envoy-based routing, OPA policy evaluation with NPL-backed bundles, JWT authentication, and credential injection.

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
                   │  Bundle   │ │  └──────────────────────┘
                   │  Server   │ │
                   └─────┬─────┘ │ secrets-net
                         │       │
                   ┌─────┴─────┐ ┌──────┴──────┐
                   │NPL Engine │ │ Credential  │
                   │(PolicyStore)│ │   Proxy     │
                   └───────────┘ └──────┬──────┘
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
| **OPA Sidecar** | ext_authz policy evaluation via Rego; loads policy data from OPA bundles (in-memory, zero network I/O) |
| **NPL Engine** | Policy state manager: GatewayStore (catalog + access rules + revocation) + ServiceGovernance (per-service workflows) |
| **Bundle Server** | Reads NPL GatewayStore, serves OPA bundles; SSE-triggered rebuild on NPL mutations |
| **Supergateway Sidecars** | Wrap STDIO MCP tools as Streamable HTTP endpoints |
| **Mock Calendar MCP** | HTTP-native MCP server for bilateral streaming tests (SSE notifications) |
| **Keycloak** | OIDC authentication provider with user/role management |
| **Governance Evaluator** | Constraint evaluation sidecar: argument-level rules (regex, in/not_in, max_length) + NPL approval routing |
| **Dashboard** | Admin web UI: service catalog, access rules, governance rules, approvals, user management, Docker discovery, real-time metrics |
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

1. **NPL Bootstrap** -- Creates the PolicyStore singleton
2. **Add a service** -- e.g., Quick Start > DuckDuckGo, or search Docker Hub
3. **Enable the service** -- Activates it in the PolicyStore catalog
4. **Enable tools** -- Select which tools to activate in the PolicyStore catalog
5. **Register users** -- Add Keycloak users for gateway access
6. **Grant tool access** -- Assign per-user tool grants in the PolicyStore
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
| **Dashboard** | http://localhost:8888 | admin / Welcome123 |
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

Every MCP request passes through Envoy's `ext_authz` filter, which calls OPA via gRPC. OPA evaluates a Rego policy (`policies/mcp_authz.rego`) using in-memory bundle data — zero network I/O in the request path.

### How It Works

```
NPL PolicyStore ──SSE──► Bundle Server ──bundle──► OPA (in-memory data)

Envoy (port 8000)
  │ ext_authz (gRPC)
  ▼
OPA Sidecar (port 9191 gRPC / 8181 HTTP)
  └─ Layer 1: Rego rules + in-memory bundle data (~11ms avg E2E)
     Zero network I/O. Bundle rebuilt on NPL mutation via SSE push.
```

**Three components in the policy chain: OPA + Bundle Server + NPL. Bundle server bridges NPL state into OPA bundles. No polling, no cache expiry — SSE push on every mutation.**

### Authorization Flow

```
1. Agent → Envoy            POST /mcp: tools/call "duckduckgo.search"
2. Envoy                    Validates JWT (Keycloak JWKS)
3. Envoy → OPA              ext_authz (gRPC): forwards JSON-RPC body + headers
4. OPA                      Decodes JWT: userId=jarvis@acme.com
5. OPA                      Parses JSON-RPC: method=tools/call, tool=duckduckgo.search
6. OPA                      Three-layer evaluation (in-memory bundle data):
                              - Layer 1 (Catalog): service enabled? tool registered?
                              - Layer 2 (Access Rules): caller matches a rule for this service/tool?
                              - Layer 3 (NPL): if tag=gated, governance evaluator check
7. OPA → Envoy              allow or deny (with X-OPA-Reason header)
8. Envoy → Backend MCP      Routes to supergateway sidecar (if allowed)
9. Backend → Envoy → Agent  SSE response streamed back
```

### Policy Rules

| Request Type | Rule |
|-------------|------|
| `initialize`, `tools/list`, `ping`, `notifications/*` | Always allowed (non-tool methods) |
| `GET /mcp` (stream setup) | Allowed if user has any matching access rules |
| `tools/call` (open tag) | Service enabled, tool in catalog, caller matches access rule |
| `tools/call` (gated tag) | Above + governance evaluator must return allow |
| Missing JWT or unknown user | Denied |

### Bundle Propagation

- **NPL mutation** (e.g., admin disables a tool) → SSE event → bundle server rebuilds → OPA reloads
- **Propagation latency**: near-instant (SSE push, not polling)
- **No cold calls**: OPA always has data in memory after first bundle load

### GatewayStore + ServiceGovernance (v4)

```
GatewayStore                    Single protocol, single instance
  ├── Catalog                   Services + tools with open/gated tags
  ├── Access Rules              Claim-based and identity-based authorization
  └── Revoked Subjects          Emergency kill switch

ServiceGovernance               One instance per governed service
  ├── Tool Configs              Per-tool constraints (regex, in/not_in, max_length)
  ├── Pending Requests          Gated tool calls awaiting approval
  └── Approval Settings         Per-tool approval requirements + deadlines
```

Bundle server reads GatewayStore in one call (`getBundleData()`). ServiceGovernance instances are evaluated at request time by the governance evaluator for gated tools.

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

1. Changes are written to NPL PolicyStore (source of truth) first
2. On success, `services.yaml` is updated as a persistent cache
3. If the NPL write fails, `services.yaml` is unchanged

> **Principle**: "NPL is the source of truth. YAML is a persistent cache."

## Configuration

### services.yaml (Bootstrap Only)

Defines upstream MCP services and their tools. Used by the TUI for service management and as a persistent cache of NPL state. **Not read at runtime** — OPA loads policy data from bundles built by the bundle server.

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

#### Bundle Server

| Variable | Default | Description |
|----------|---------|-------------|
| `NPL_URL` | `http://npl-engine:12000` | NPL Engine URL |
| `KEYCLOAK_URL` | `http://keycloak:11000` | Keycloak URL |
| `KEYCLOAK_REALM` | `mcpgateway` | Keycloak realm |
| `GATEWAY_USERNAME` | `gateway` | Service account for NPL calls |
| `GATEWAY_PASSWORD` | `Welcome123` | Service account password |
| `BUNDLE_PORT` | `8282` | Bundle server HTTP port |

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
│   ├── mcp_authz.rego     # v4 authorization: three-layer catalog/access/governance
│   └── mcp_authz_test.rego # Rego unit tests (opa test policies/ -v)
│
├── bundle-server/          # OPA bundle server (Python)
│   └── server.py           # Reads NPL GatewayStore, serves OPA bundles, SSE-triggered rebuild
│
├── dashboard/              # Admin web UI (Python + single-page HTML)
│   ├── server.py           # API server: proxies to NPL, Keycloak, OPA, Docker, metrics
│   └── static/index.html   # SPA: catalog, rules, approvals, users, discover, metrics
│
├── governance-evaluator/   # Constraint evaluation sidecar (Python)
│   └── server.py           # Argument-level constraints + NPL approval routing
│
├── mcp-aggregator/         # Multi-backend MCP routing (Node.js)
│   └── server.js           # Aggregates tools from multiple backends, routes by namespace
│
├── credential-proxy/       # Credential injection service (Kotlin/Ktor)
│   └── src/main/kotlin/    # VaultClient, CredentialSelector
│
├── npl/                    # NPL protocol definitions
│   └── src/main/npl-1.0/
│       ├── store/          # GatewayStore (catalog + access rules + revocation)
│       └── governance/     # ServiceGovernance (per-service workflows + constraints)
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
│   ├── services.yaml       # Service definitions (bootstrap/cache)
│   └── credentials.yaml    # Credential mappings
│
├── deployments/            # Docker Compose + Envoy config
│   ├── docker-compose.yml  # Full stack with network isolation
│   ├── envoy/
│   │   └── envoy-config.yaml  # Envoy static config (JWT, ext_authz → OPA, routing)
│   └── docker/
│       ├── Dockerfile.supergateway        # Supergateway sidecar image
│       ├── Dockerfile.dashboard           # Admin dashboard image
│       ├── Dockerfile.governance-evaluator
│       ├── Dockerfile.mcp-aggregator
│       ├── Dockerfile.credential-proxy
│       └── Dockerfile.mock-calendar       # HTTP-native MCP server image
│
├── keycloak/               # Custom Keycloak image
├── keycloak-provisioning/  # Terraform for Keycloak setup
├── integration-tests/      # Integration test suite (JUnit5: NPL + E2E)
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

### Integration Tests (JUnit5)

```bash
# Requires Docker stack running (docker compose up -d)
cd integration-tests
./gradlew test
```

The test suite covers:
- **NPL Tests** (16 tests): PolicyStore CRUD — register/enable services, enable tools, grant/revoke access, suspend/resume, contextual routes, getPolicyData
- **E2E Tests** (11 tests): Full pipeline through Envoy → OPA → backend — initialize, tools/list, tools/call allow/deny, dynamic grant-revoke pipeline, suspended service denial

### Manual Verification

```bash
# 1. Get a token
TOKEN=$(curl -sf -X POST 'http://localhost:11000/realms/mcpgateway/protocol/openid-connect/token' \
  -d 'grant_type=password&client_id=mcpgateway&username=jarvis&password=Welcome123' \
  | python3 -c "import sys,json; print(json.load(sys.stdin)['access_token'])")

# 2. Call a tool (should 200 if jarvis has access)
curl -X POST 'http://localhost:8000/mcp' \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"list_events","arguments":{"date":"2026-02-14"}}}'

# 3. Inspect OPA bundle data
curl http://localhost:8181/v1/data
```

## Documentation

| Document | Description |
|----------|-------------|
| [Architecture Reference](docs/ARCHITECTURE.md) | Service topology, network isolation, data flow diagrams |
| [v4 Governance Design](docs/DESIGN_V4_SIMPLIFIED_GOVERNANCE.md) | Three-layer governance: catalog, access rules, NPL workflows |
| [How-To Guide](docs/HOWTO.md) | Step-by-step configuration walkthrough |
| [Approval Workflow](docs/APPROVAL_WORKFLOW.md) | Human-in-the-loop approval system deep dive |
| [Credential Injection](docs/CREDENTIAL_INJECTION.md) | Credential injection system design and Vault integration |
| [Security Strategy](docs/SECURITY_STRATEGY.md) | Enterprise security strategy and MCP risk framework |
| [Packaging Strategy](docs/PACKAGING_STRATEGY.md) | Distribution strategy (CLI tier + Docker/Helm tier) |
| [TUI Add Service Guide](docs/TUI_ADD_SERVICE_GUIDE.md) | Adding MCP services via TUI |
| [TUI Credential Management](docs/TUI_CREDENTIAL_MANAGEMENT.md) | Credential management via TUI |

## License

Copyright 2025-2026 Noumena Digital AG. All rights reserved.
