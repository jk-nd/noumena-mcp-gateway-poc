# Noumena MCP Gateway

A transparent MCP (Model Context Protocol) proxy that enables AI agents to securely access upstream MCP services through NPL policy enforcement, JWT authentication, and tool namespacing.

## Architecture (V2 — Transparent Proxy)

```
┌─────────────────────────────────────────────────────────────────────┐
│  public-net                                                         │
│                                                                     │
│  ┌──────────────┐      ┌──────────┐                                │
│  │   Keycloak   │      │ Gateway  │◄──── Agents / Humans           │
│  │   (OIDC)     │      │ MCP Proxy│     (JWT auth)                 │
│  └──────────────┘      └────┬─────┘                                │
│                              │                                      │
├──────────────────────────────┼──────────────────────────────────────┤
│  policy-net                  │                                      │
│                              │                                      │
│  ┌──────────────┐      ┌────┴─────┐                                │
│  │  NPL Engine  │◄─────│ Gateway  │                                │
│  │  ToolPolicy  │      │          │                                │
│  │  SvcRegistry │      └────┬─────┘                                │
│  └──────────────┘           │                                      │
│                              │                                      │
├──────────────────────────────┼──────────────────────────────────────┤
│  backend-net                 │                                      │
│                              │                                      │
│  ┌──────────┐  ┌──────────┐ │ ┌──────────┐                        │
│  │ DuckDuck │  │  GitHub   │◄┘ │  Slack   │  ...                   │
│  │ Go MCP   │  │  MCP      │   │  MCP     │                        │
│  └──────────┘  └──────────┘   └──────────┘                        │
└─────────────────────────────────────────────────────────────────────┘
```

**Network isolation**: Agents can only reach the Gateway and Keycloak. Upstream MCPs are isolated on backend-net. NPL Engine is on policy-net. The Gateway spans all three.

See [docs/ARCHITECTURE_V2.md](docs/ARCHITECTURE_V2.md) for the full architecture document.

## Agent Connectivity

| Endpoint | Transport | Best For |
|----------|-----------|----------|
| `POST /mcp` | Streamable HTTP | MCP Inspector, LangChain, ADK, Sligo.ai |
| `WS /mcp/ws` | WebSocket | Streaming agents, long-running connections |
| `GET /sse` | SSE | Legacy MCP clients, browser-based EventSource |

All endpoints accept standard MCP JSON-RPC messages and return the same responses. All require JWT authentication (via Bearer token or OAuth 2.0 flow).

## Components

| Component | Role | Network |
|-----------|------|---------|
| **Gateway** | Transparent MCP proxy — authenticates, checks policy, forwards | public, policy, backend |
| **NPL Engine** | Policy evaluation via ServiceRegistry & per-service ToolPolicy | policy |
| **Keycloak** | OIDC authentication provider | public |
| **Upstream MCPs** | Docker containers running MCP servers (DuckDuckGo, GitHub, etc.) | backend |

## Features

### Transparent Proxy
- Gateway discovers tools from upstream MCP services
- Tool namespacing: `duckduckgo.search`, `github.create_issue`
- Lazy connection: upstream sessions created on first tool call
- Fail-closed: all requests denied if NPL is unavailable

### NPL Policy Enforcement
- **ServiceRegistry** — controls which services are enabled/disabled
- **ToolPolicy** (per service) — granular per-tool access control
- Default-deny: tools must be explicitly enabled
- Audit trail via NPL request counting

### Authentication & OAuth 2.0
- Keycloak OIDC with role-based access (admin, agent, user)
- **Full OAuth 2.0 authorization code flow** with PKCE for browser-based clients
- OAuth discovery endpoints: `/.well-known/oauth-protected-resource` (RFC 9728), `/.well-known/oauth-authorization-server` (RFC 8414)
- Dynamic client registration via `/register` (RFC 7591)
- Gateway proxies `/authorize` and `/token` to Keycloak (no direct Keycloak exposure needed)
- `WWW-Authenticate` header on 401 responses triggers automatic OAuth in compliant clients
- MCP Inspector connects via OAuth — just click Connect, log in, done

### Multi-Transport Upstream Support
- **STDIO** — Gateway spawns Docker containers on-demand (`docker run -i --rm`)
- **Streamable HTTP** — Direct HTTP POST to upstream MCP servers
- **WebSocket** — Persistent WebSocket connections to upstream MCPs
- Configured per-service in `services.yaml` via the `type` field (`MCP_STDIO`, `MCP_HTTP`, `MCP_WS`)

## Quick Start

### Prerequisites

- Docker & Docker Compose
- JDK 17+ (for building from source)
- Node.js 18+ (for TUI wizard)

### Run with Docker Compose

```bash
cd deployments
docker compose up -d

# Wait for services to be healthy
docker compose ps
```

### Bootstrap NPL

Use the TUI wizard to create protocol instances:

```bash
cd tui
npm install
npm start
```

Select "NPL Bootstrap" from the menu to create ServiceRegistry and ToolPolicy instances.

### Access UIs

| Service | URL | Credentials |
|---------|-----|-------------|
| Keycloak Admin | http://localhost:11000 | admin / welcome |
| NPL Inspector | http://localhost:8080 | admin / Welcome123 |

### MCP Inspector (with OAuth)

```bash
npx @modelcontextprotocol/inspector
```

In the Inspector UI:
1. **Transport Type**: Streamable HTTP
2. **URL**: `http://localhost:8000/mcp`
3. **Connection Type**: Direct
4. Click **Connect** — you'll be redirected to Keycloak to log in
5. Sign in with `admin` / `Welcome123`
6. You're connected with a valid OAuth token — no manual token management

> **Tip**: If you see "Failed to construct headers: Invalid name", open **Authentication** and delete any stale custom headers.

## Message Flow

### Successful Request (duckduckgo.search)

```
1. Agent → Gateway         POST /mcp: tools/call {name: "duckduckgo.search"}
2. Gateway                 Validates JWT → identity, roles
3. Gateway                 Parses namespace → service=duckduckgo, tool=search
4. Gateway → NPL Engine    ToolPolicy.checkAccess("search", agent-id)
5. NPL Engine → Gateway    Allowed ✓
6. Gateway → Upstream MCP  Forward tools/call {name: "search"} to duckduckgo container
7. Upstream → Gateway      Result with search data
8. Gateway → Agent         JSON-RPC result
```

### Denied Request (disabled tool)

```
1. Agent → Gateway         POST /mcp: tools/call {name: "slack.send_message"}
2. Gateway                 Validates JWT
3. Gateway → NPL Engine    ToolPolicy.checkAccess("send_message", agent-id)
4. NPL Engine → Gateway    Denied ✗ (tool not enabled)
5. Gateway → Agent         Error: POLICY_DENIED

   ❌ Request never reaches upstream — blocked at policy layer
```

## Project Structure

```
noumena-mcp-gateway/
├── gateway/                 # MCP proxy server (Kotlin/Ktor)
│   └── src/main/kotlin/
│       ├── Application.kt   # Main entry, routing, SSE/WebSocket
│       ├── server/          # McpServerHandler (proxy logic)
│       ├── policy/          # NplClient (ToolPolicy checks)
│       ├── upstream/        # UpstreamRouter, UpstreamSessionManager
│       └── auth/            # JWT validation helpers
│
├── shared/                  # Shared config loaders and models
│
├── npl/                     # NPL protocol definitions
│   └── src/main/npl-1.0/
│       ├── registry/        # ServiceRegistry
│       └── services/        # ToolPolicy (per-service access control)
│
├── deployments/             # Docker Compose & demo scripts
│   ├── docker-compose.yml   # Main stack (3-tier network isolation)
│   └── docker-compose.mcp.yml  # Upstream MCP containers
│
├── tui/                     # MCP Gateway Wizard (CLI)
│   └── src/
│       ├── cli.ts           # Main wizard (Clack-based)
│       └── lib/             # Config, API, Docker clients
│
├── configs/                 # Service configurations
│   └── services.yaml        # Upstream MCP service definitions
│
├── keycloak-provisioning/   # Terraform for Keycloak setup
├── integration-tests/       # Integration test suite
└── docs/                    # Architecture documentation
```

## MCP Gateway Wizard (TUI)

An interactive CLI for managing MCP Gateway services:

```bash
cd tui
npm install
npm start
```

Features:
- **Service management** — Enable/disable services (syncs with NPL policy)
- **Container control** — Pull, start, stop Docker containers for MCP servers
- **Tool management** — Enable/disable individual tools per service
- **Docker Hub search** — Find and add MCP servers from the `mcp/*` namespace
- **NPL Bootstrap** — Create ServiceRegistry and per-service ToolPolicy instances

See [tui/README.md](tui/README.md) for detailed documentation.

## Configuration

### Environment Variables (Gateway)

| Variable | Default | Description |
|----------|---------|-------------|
| `NPL_URL` | `http://npl-engine:12000` | NPL Engine URL |
| `KEYCLOAK_URL` | `http://keycloak:11000` | Keycloak URL (internal, for JWT validation & token proxy) |
| `KEYCLOAK_EXTERNAL_URL` | `http://keycloak:11000` | Keycloak URL (browser-facing, for OAuth redirects) |
| `KEYCLOAK_REALM` | `mcpgateway` | Keycloak realm |
| `KEYCLOAK_ISSUER` | auto | JWT issuer URL |
| `DEV_MODE` | `false` | Bypass NPL policy checks |
| `SERVICES_CONFIG_PATH` | `/app/configs/services.yaml` | Path to services config |

> **Note**: `KEYCLOAK_EXTERNAL_URL` must be reachable by the user's browser. For local dev with Docker, add `127.0.0.1 keycloak` to `/etc/hosts` so both the browser and Docker containers resolve the same hostname.

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

## License

Copyright 2025 Noumena Digital AG. All rights reserved.
