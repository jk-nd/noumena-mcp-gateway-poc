# MCP Gateway Packaging Strategy

## Status

**Draft** — Strategy captured 2026-02-15. Pending implementation.

---

## 1. The Problem: Too Many Services to Get Started

The current development deployment runs 15+ Docker services across four networks:

| Service | Runtime | Purpose |
|---------|---------|---------|
| envoy-gateway | Envoy | MCP proxy, JWT validation, ext_authz |
| opa | Go | Policy evaluation (Layer 1) |
| bundle-server | Node.js | NPL → OPA data bridge |
| mcp-aggregator | Node.js | Multi-backend tool routing |
| npl-engine | JVM | Stateful policy — Layer 2 (approvals, velocity, taint) |
| engine-db | PostgreSQL | NPL state persistence |
| keycloak | JVM | OAuth2/OIDC identity provider |
| keycloak-db | PostgreSQL | Keycloak state persistence |
| keycloak-provisioning | Terraform | Seed users and clients |
| credential-proxy | JVM | Vault → env var injection |
| vault | Go | Secret storage |
| inspector | React | NPL state viewer |
| npl-cors-proxy | Nginx | Browser CORS for inspector |
| duckduckgo-mcp | Supergateway | Example backend tool |
| mock-calendar-mcp | Node.js | Example backend tool |

This is appropriate for enterprise deployment and development, but prohibitive for an OpenClaw developer who just wants to secure their MCP tools. Downloading and orchestrating 15 containers is not a viable onboarding experience.

---

## 2. Design Principles

1. **Zero Docker for OpenClaw developers.** The community tier runs entirely as native processes managed by the `mcpgw` CLI, installed via Homebrew. No Docker required.

2. **Layer 2 included from the start.** Stateful policy (approvals, session velocity, taint tracking) is the differentiator. Pure tool-level allow/deny (Layer 1) doesn't require this architecture — any OPA deployment can do that. The value is Layer 2. Ship it to everyone.

3. **Same repo, different distribution targets.** One codebase produces both the native brew distribution (community) and the Docker/Helm distribution (enterprise). Same code, same policy format, different packaging.

4. **Envoy stays.** Envoy is enterprise-grade, has full MCP protocol support (Streamable HTTP, SSE), and provides the ext_authz integration with OPA. Same proxy in all deployment modes.

5. **Proper OIDC from day one.** Dex (CNCF, Go binary) provides real OIDC with a federation upgrade path — not dev mode hacks or gateway-minted JWTs. Keycloak is available as an enterprise option.

6. **Credential isolation matters for everyone.** The OpenClaw security incidents (plaintext API keys, malicious skill exfiltration) demonstrate that credential isolation is not an enterprise-only concern. Individual developers need their API keys protected from agents and malicious tools.

7. **MCP servers spawn natively.** OpenClaw developers run MCP servers via `npx` as STDIO child processes. The gateway spawns MCP servers the same way — no Docker required for individual tools.

---

## 3. Two Distribution Tiers, One Codebase

### 3.1 Overview

| | Community (brew) | Enterprise (Docker / Helm) |
|---|---|---|
| Target | OpenClaw developers, small teams | Platform teams, enterprise |
| Install | `brew install noumena/tap/mcpgw` | Docker Compose / Helm chart |
| Docker required | **No** | Yes |
| All components | Native binaries + brew services | Containers |
| Identity provider | Dex (Go binary, OIDC + federation) | Keycloak or external IdP |
| NPL Engine | GraalVM native binary | Docker image (JVM) |
| MCP server spawning | Native STDIO child processes | Docker + supergateway |
| Network isolation | Process-level | Docker networks (4-tier) |
| Startup time | **<5 seconds** | ~30 seconds |
| Policy format | `mcp-security.yaml` | Same `mcp-security.yaml` |

### 3.2 Repository Structure

```
noumena-mcp-gateway/
├── npl/                      ← NPL protocols (shared across all targets)
├── policies/                 ← OPA Rego (shared)
├── mcp-aggregator/           ← Node.js gateway (shared)
├── bundle-server/            ← Node.js bundle server (shared)
├── credential-proxy/         ← Kotlin credential proxy (shared)
├── tui/                      ← TUI (shared)
├── configs/                  ← Default configs (shared)
│
├── cmd/mcpgw/                ← Go CLI (brew-distributed binary)
│   ├── main.go
│   ├── native.go             ← Native process management (brew target)
│   └── docker.go             ← Docker container management (enterprise target)
│
├── packaging/
│   ├── brew/                 ← Homebrew formula + native config
│   ├── docker/               ← Docker Compose (enterprise)
│   ├── all-in-one/           ← Single Docker image (enterprise quickstart)
│   └── helm/                 ← Kubernetes chart (enterprise)
│
└── deployments/              ← Current development Docker Compose
```

---

## 4. Community Tier: Fully Native via Homebrew

### 4.1 The Native Stack

Every component in the gateway has a native binary available via Homebrew:

| Component | Binary type | Install | Startup | RAM |
|-----------|-------------|---------|:-------:|:---:|
| PostgreSQL | C | `brew install postgresql@14` | ~1s | ~30MB |
| NPL Engine | GraalVM native | `brew install NoumenaDigital/tools/npl-engine` | <1s | ~80MB |
| OPA | Go | `brew install opa` | <1s | ~30MB |
| Envoy | C++ | `brew install envoy` | <1s | ~20MB |
| Dex | Go | `brew install dex` | <1s | ~30MB |
| Vault | Go | `brew install vault` | <1s | ~30MB |
| Bundle Server | Node.js | Managed by mcpgw | <1s | ~50MB |
| Gateway + MCP servers | Node.js | Managed by mcpgw | <2s | ~80MB |
| **Total** | | | **<5s** | **~350MB** |

Zero Docker. Zero JVM. All native binaries plus Node.js (which every OpenClaw user already has).

**Dependency on Noumena:** The NPL Engine must be available as a GraalVM native binary via the existing `NoumenaDigital/tools` brew tap. Noumena already compiles the `npl` CLI to GraalVM native (same Kotlin stack, same build pipeline). The engine binary would be published alongside it.

### 4.2 What `brew install` Provides

```bash
brew install noumena/tap/mcpgw
```

The formula declares dependencies:

```ruby
class Mcpgw < Formula
  depends_on "postgresql@14"
  depends_on "NoumenaDigital/tools/npl-engine"
  depends_on "opa"
  depends_on "envoy"
  depends_on "dex"
  depends_on "vault"   # optional, for credential vault toggle
  # Node.js already present (OpenClaw prerequisite)
end
```

Installs the `mcpgw` CLI (Go binary), the Node.js gateway app, and all native dependencies. Brew handles installation and version management.

### 4.3 What `mcpgw up` Does (Native Mode)

```bash
mcpgw up
```

The CLI starts and manages all processes:

```
Phase 1: PostgreSQL
         brew services start postgresql
         Wait for pg_isready
         First run: create databases, users, schemas (db_init scripts)

Phase 2: NPL Engine + Dex + Vault
         npl-engine --db-url=jdbc:postgresql://localhost:5432/engine \
                    --allowed-issuers=http://localhost:5556/dex ...
         dex serve ~/.mcpgw/dex-config.yaml
         vault server -config=~/.mcpgw/vault-config.hcl  (if enabled)

Phase 3: Bundle Server + OPA + Node.js Gateway
         node bundle-server.js
         opa run --server --config-file=opa-config.yaml
         node gateway.js  (aggregator + MCP spawning)

Phase 4: Envoy
         envoy -c ~/.mcpgw/envoy.yaml

Phase 5: MCP Servers
         npx @modelcontextprotocol/server-github (PID 12345)
         npx @modelcontextprotocol/server-gmail (PID 12346)
         ...

✓ Gateway ready on localhost:8000 (<5 seconds)
  5 MCP servers running, 3 with vault credentials
```

`mcpgw down` stops everything in reverse order. `mcpgw status` shows PIDs and health.

### 4.4 Data Storage

```
~/.mcpgw/
├── data/
│   └── postgresql/       ← PostgreSQL data directory
├── vault/                ← Vault file storage backend
├── keys/                 ← JWT signing keys (if API key mode)
├── config/
│   ├── mcpgw.yaml        ← Main configuration (toggles, services)
│   ├── dex-config.yaml   ← Dex OIDC configuration
│   ├── envoy.yaml        ← Envoy config (generated)
│   └── opa-config.yaml   ← OPA config (generated)
├── npl/                  ← NPL protocol sources (deployed to engine)
└── mcp-security.yaml     ← Security policy (user-edited)
```

---

## 5. Identity: Dex as the OIDC Provider

### 5.1 Why Dex

Dex is a CNCF sandbox project — a lightweight OIDC identity broker written in Go. It provides **proper OIDC** (real token issuance, real JWKS, standard flows) with a built-in **federation upgrade path** via pluggable connectors. Used by ArgoCD, Kubernetes, and Terraform Enterprise.

| | Dex | Keycloak |
|---|---|---|
| Binary | Go, ~30MB | JVM, ~300MB+ |
| Startup | <1 second | ~10-15 seconds |
| RAM | ~30MB | ~500MB+ |
| Database | SQLite (dev) or PostgreSQL | Dedicated PostgreSQL |
| OIDC / OAuth2 | Yes | Yes |
| Federation (LDAP, SAML) | Yes (built-in connectors) | Yes (more extensive) |
| Social login (GitHub, Google) | Yes (built-in connectors) | Yes |
| User management UI | None (config + API) | Full admin console |
| brew installable | Yes (single Go binary) | No (JVM + database) |
| Docker required | No | Practically yes |

### 5.2 The Federation Upgrade Path

Dex's connector model allows the identity backend to evolve without changing anything in the gateway:

**Day 1 — Solo OpenClaw developer:**

```yaml
# dex-config.yaml
connectors:
  - type: local
    id: local
    name: Local Users
    config:
      users:
        - email: developer@local
          hash: "$2a$10$..."   # bcrypt, managed by mcpgw user add
```

`mcpgw user add` writes to this config. Proper OIDC tokens, real JWKS, nothing hacky.

**Day 30 — Small team, GitHub login:**

```yaml
connectors:
  - type: github
    id: github
    name: GitHub
    config:
      clientID: $GITHUB_CLIENT_ID
      clientSecret: $GITHUB_CLIENT_SECRET
      orgs:
        - name: my-team
```

Team members log in with GitHub identity. Same Dex, one connector config change.

**Day 90 — Enterprise, corporate SSO:**

```yaml
connectors:
  - type: saml
    id: corporate
    name: Corporate SSO
    config:
      ssoURL: https://sso.company.com/saml
      ca: /path/to/ca.pem
      redirectURI: http://localhost:5556/dex/callback
```

Or swap Dex for Keycloak / external IdP entirely — just change `ENGINE_ALLOWED_ISSUERS`.

### 5.3 How Dex Integrates

```
OpenClaw ──→ Envoy ──→ OPA ──→ NPL Engine
                │                    │
                │  validates JWT     │  validates JWT
                │  against Dex JWKS  │  against Dex JWKS
                │                    │
                └───── Dex ──────────┘
                       │
                       ├── Local users (config file)
                       ├── GitHub connector
                       ├── SAML connector
                       └── LDAP connector
```

- Envoy validates JWTs against Dex's `/.well-known/openid-configuration` JWKS endpoint
- NPL Engine: `ENGINE_ALLOWED_ISSUERS=http://localhost:5556/dex`
- OPA reads JWT `sub` claim — same as with any IdP
- The gateway Node.js process handles API key → Dex token exchange for the simple local-user case

### 5.4 Dex Database

For the community tier, Dex uses **SQLite** — zero additional infrastructure. It stores refresh tokens and connector state in a local file.

For enterprise (if using Dex rather than Keycloak), Dex can share the PostgreSQL instance with the NPL Engine — separate database, same approach as the Keycloak case.

---

## 6. OpenClaw Integration Model

### 6.1 How OpenClaw Runs MCP Servers Today

OpenClaw configures MCP servers in its settings file (`openclaw.json` or `settings.json`). Each server is either a local STDIO process or a remote HTTP endpoint:

```json
{
  "mcpServers": {
    "github": {
      "command": "npx",
      "args": ["-y", "@modelcontextprotocol/server-github"],
      "env": { "GITHUB_TOKEN": "ghp_abc123..." }
    },
    "gmail": {
      "command": "npx",
      "args": ["-y", "@modelcontextprotocol/server-gmail"],
      "env": { "GMAIL_OAUTH_TOKEN": "ya29.xxx..." }
    },
    "memory": {
      "command": "npx",
      "args": ["-y", "@modelcontextprotocol/server-memory@0.3.0"]
    }
  }
}
```

**The security problem:** API keys sit in plaintext in this config file. Every MCP server — including malicious skills from ClawHub — can read the process environment and access all credentials.

### 6.2 After Gateway Integration

The gateway replaces all individual MCP server entries with a single endpoint. OpenClaw's config becomes:

```json
{
  "mcpServers": {
    "gateway": {
      "transport": "streamable-http",
      "url": "http://localhost:8000/mcp",
      "headers": {
        "Authorization": "Bearer mcpgw_sk_a1b2c3d4..."
      }
    }
  }
}
```

One entry. No credentials in the config. The gateway aggregates all backends, enforces policy, and handles credential injection.

```
BEFORE:
  OpenClaw ──→ github (npx, GITHUB_TOKEN in env — exposed)
           ──→ gmail (npx, GMAIL_TOKEN in env — exposed)
           ──→ slack (npx, SLACK_TOKEN in env — exposed)
           ──→ memory (npx)
           ──→ filesystem (npx)

AFTER:
  OpenClaw ──→ Gateway (single endpoint, API key only)
                   │
                   ├── Policy enforcement (OPA + NPL)
                   ├── Credential injection (Vault → isolated env)
                   │
                   ├──→ github (npx, GITHUB_TOKEN injected from Vault)
                   ├──→ gmail (npx, GMAIL_TOKEN injected from Vault)
                   ├──→ slack (npx, SLACK_TOKEN injected from Vault)
                   ├──→ memory (npx)
                   └──→ filesystem (npx)
```

### 6.3 The Import Flow: `mcpgw import`

The gateway takes over MCP server management from OpenClaw by importing its existing config:

```bash
mcpgw import openclaw
```

**Step 1: Read OpenClaw's config**

The CLI locates and parses OpenClaw's settings file (standard path or user-specified).

**Step 2: Show what was found**

```
Found 5 MCP servers in ~/.openclaw/settings.json:

  SERVICE      COMMAND    CREDENTIALS
  github       npx        GITHUB_TOKEN (will be moved to vault)
  gmail        npx        GMAIL_OAUTH_TOKEN (will be moved to vault)
  slack        npx        SLACK_BOT_TOKEN (will be moved to vault)
  memory       npx        (none)
  filesystem   npx        (none)

? Import all 5 services? (Y/n)
```

**Step 3: Split the config into three parts**

| What | Where it goes |
|------|---------------|
| Service definitions (command, args) | `services.yaml` → PolicyStore (gateway learns how to spawn each MCP server) |
| Credentials (env vars with secrets) | Vault (if enabled) — secrets extracted from config, stored securely |
| OpenClaw config | Rewritten to single gateway entry |

**Step 4: Apply**

```
✓ Imported 5 services into gateway
✓ 3 credentials moved to vault
✓ OpenClaw config updated (backup: ~/.openclaw/settings.json.bak)
✓ Restart OpenClaw to connect through the gateway
```

**Step 5: Ongoing management**

After import, the developer manages MCP servers through the gateway:

```bash
mcpgw service add brave-search --command "npx" --args "-y @anthropic/mcp-server-brave"
mcpgw secret set brave-search BRAVE_API_KEY
mcpgw user grant developer brave-search
```

New services are added through `mcpgw`, not through OpenClaw's config. The gateway is now the source of truth for which MCP servers exist and how they're configured.

---

## 7. MCP Server Spawning: Native STDIO

### 7.1 Native STDIO Spawning

OpenClaw developers already have Node.js and `npx` installed — OpenClaw requires it. Most MCP servers are npm packages run via `npx`. The gateway spawns MCP servers the same way: as **native STDIO child processes**.

The Node.js gateway process (aggregator) manages MCP servers directly:

```
Node.js Gateway (host process)
├── spawn("npx", ["-y", "@modelcontextprotocol/server-github"])
│   ├── stdin/stdout: MCP STDIO protocol
│   └── env: { GITHUB_TOKEN: "ghp_..." } (injected from Vault)
├── spawn("npx", ["-y", "@modelcontextprotocol/server-gmail"])
│   ├── stdin/stdout: MCP STDIO protocol
│   └── env: { GMAIL_OAUTH_TOKEN: "ya29...." } (injected from Vault)
├── spawn("npx", ["-y", "@modelcontextprotocol/server-memory"])
│   └── stdin/stdout: MCP STDIO protocol
└── MCP aggregation: combines all tools, routes calls by namespace
```

The aggregator already understands the MCP protocol. Instead of talking Streamable HTTP to supergateway containers, it talks STDIO directly to child processes. This is the same mechanism OpenClaw uses.

### 7.2 Credential Injection for STDIO Processes

When Vault is enabled, the gateway fetches credentials from Vault at process spawn time and injects them into the child process environment:

```
1. Gateway needs to spawn github MCP server
2. Calls Credential Proxy → Vault
3. Gets: { GITHUB_TOKEN: "ghp_abc123..." }
4. Spawns: npx -y @modelcontextprotocol/server-github
   with env: { GITHUB_TOKEN: "ghp_abc123...", PATH: "...", ... }
5. Credential lives only in the child process environment
6. OpenClaw agent NEVER sees the credential
7. Other MCP server processes NEVER see each other's credentials
```

Each MCP server child process gets only its own credentials — isolated from other tools and from the agent.

### 7.3 Community vs Enterprise Spawning

| Aspect | Community (native STDIO) | Enterprise (Docker + supergateway) |
|--------|--------------------------|-------------------------------------|
| MCP server runtime | Native child process | Docker container |
| Protocol to aggregator | STDIO (direct) | Streamable HTTP |
| Docker required for tools | No | Yes |
| Host filesystem access | Native (just works) | Needs Docker volume mount |
| Credential injection | Process spawn env | Container startup env |
| Network isolation | Process-level only | Docker networks (4-tier) |
| Startup per tool | ~1-2s (npx) | ~2-5s (container) |

Enterprise deployments use Docker-per-tool with supergateway for full network isolation. Community uses native STDIO.

---

## 8. Setup-Time Toggles

### 8.1 Interactive Setup

```bash
mcpgw init
```

Three toggles, each independent:

```
? Identity provider:
  > Dex (lightweight OIDC, local users, federation-ready)
    Keycloak (enterprise, full admin console, OIDC/SAML)
    External (bring your own IdP — provide OIDC issuer URL)

? Enable credential vault? (isolates API keys from agents)
  > Yes (recommended — agents never see your secrets)
    No (you manage credentials yourself)

? Enable TUI management console?
  > Yes (interactive terminal UI for policy management)
    No (CLI-only)
```

### 8.2 What Runs in Each Configuration

**OpenClaw developer** (Dex + vault):

| Process | Binary | Status |
|---------|--------|--------|
| PostgreSQL | brew | Running (engine db) |
| NPL Engine | GraalVM native | Running |
| Dex | Go native | Running (local users, SQLite) |
| OPA | Go native | Running |
| Envoy | C++ native | Running |
| Bundle Server | Node.js | Running |
| Vault | Go native | Running |
| Credential Proxy | GraalVM native | Running |
| Node.js gateway | Node.js | Running (MCP spawning) |
| MCP servers | npx | Running (STDIO child processes) |

**Enterprise** (Keycloak, Docker):

| Process | Runtime | Status |
|---------|---------|--------|
| PostgreSQL | Docker | Running (engine db + keycloak db) |
| NPL Engine | Docker (JVM) | Running |
| Keycloak | Docker (JVM) | Running |
| OPA | Docker | Running |
| Envoy | Docker | Running |
| Bundle Server | Docker | Running |
| Vault | External | External secret manager |
| MCP servers | Docker + supergateway | Running (container isolation) |

### 8.3 PostgreSQL: Shared Instance

All components that need PostgreSQL share a single PostgreSQL instance with separate logical databases:

```
PostgreSQL (one instance, port 5432)
├── database: engine      ← NPL Engine (always)
├── database: keycloak    ← Keycloak (keycloak mode only)
└── database: dex         ← Dex (dex mode, only if using PostgreSQL instead of SQLite)
```

Separate databases, separate users, full isolation.

---

## 9. Credential Vault

### 9.1 Why This Matters for OpenClaw

The OpenClaw security incidents documented in the Security Strategy (Section 1.1) make this clear:

- **Hundreds of exposed instances** with plaintext API keys, tokens, and conversation histories
- **~900 malicious skills** on ClawHub (~20% of all packages) that can exfiltrate credentials
- Invariant Labs demonstrated a malicious MCP server **silently exfiltrating a user's WhatsApp history**

Without credential isolation, API keys sit in environment variables or config files where the agent — and any malicious tool — can read them. The credential vault ensures **the agent can use GitHub, Gmail, and Slack without ever seeing the API keys**.

### 9.2 Vault Enabled (Recommended)

```bash
mcpgw secret set github GITHUB_TOKEN
# Enter value: ********
# ✓ Stored in vault: secret/data/services/github
# ✓ Mapped: GITHUB_TOKEN → github service

mcpgw secret list
# SERVICE     VARIABLE            STORED
# github      GITHUB_TOKEN        ✓
# gmail       GMAIL_OAUTH_TOKEN   ✓
```

### 9.3 Vault Disabled

Secrets are the user's responsibility. Suitable for environments with external secret management.

### 9.4 CLI Credential Management

```bash
mcpgw secret set <service> <variable>     # Store a secret (prompted for value)
mcpgw secret list                          # List stored credential mappings
mcpgw secret delete <service> <variable>   # Remove a secret
mcpgw secret test <service>                # Test credential injection for a service
```

---

## 10. TUI Management Console

### 10.1 Relationship Between CLI and TUI

| Operation | CLI (`mcpgw`) | TUI |
|-----------|:---:|:---:|
| Start/stop gateway | Yes | No |
| Apply security policy | Yes | Yes |
| Add/remove users | Yes | Yes |
| Manage grants | Basic | Full (multi-select, bulk) |
| Approve/deny requests | Yes | Yes (with detail view) |
| Service catalog browsing | List only | Full (enable/disable, metadata) |
| Credential management | Yes | Parked |
| Quick start (add common services) | No | Yes |
| Import from OpenClaw | Yes | No |

Both tools talk to the same APIs. Use `mcpgw` for quick operations and scripting, the TUI for interactive management.

---

## 11. Preset Configurations

```bash
mcpgw init --preset openclaw
# Dex (local users) + vault enabled + TUI enabled
# Optimized for: individual OpenClaw developers

mcpgw init --preset team
# Dex (GitHub connector) + vault enabled + TUI enabled
# Optimized for: small development teams

mcpgw init --preset enterprise
# Keycloak + vault enabled + TUI enabled
# Optimized for: organizations with existing IdP infrastructure
```

---

## 12. Process Architecture (Native Mode)

### 12.1 Process Tree

```
mcpgw (Go CLI, process manager)
├── postgresql (brew services)
│   └── databases: engine [+ keycloak] [+ dex]
├── npl-engine (GraalVM native binary)
│   └── connects to: postgresql:5432, validates JWT from dex
├── dex (Go binary)
│   ├── serves: OIDC discovery, JWKS, token endpoint
│   ├── storage: SQLite (community) or PostgreSQL (scaling)
│   └── connectors: local / github / saml / ldap
├── vault (Go binary, if enabled)
│   └── storage: file backend in ~/.mcpgw/vault/
├── credential-proxy (GraalVM native, if vault enabled)
│   └── connects to: vault, npl-engine
├── bundle-server (Node.js)
│   └── connects to: npl-engine (SSE subscription)
├── opa (Go binary)
│   └── connects to: bundle-server (bundle polling), npl-engine (Layer 2)
├── node-gateway (Node.js)
│   ├── MCP aggregator
│   ├── credential fetching (if vault enabled)
│   └── MCP server child processes:
│       ├── github (npx, PID 12345)
│       ├── gmail (npx, PID 12346)
│       └── memory (npx, PID 12347)
└── envoy (C++ binary)
    └── connects to: opa (ext_authz gRPC), node-gateway, dex (JWKS)
```

### 12.2 Startup Sequence

```
Phase 1: PostgreSQL (brew services)         ~1s
Phase 2: NPL Engine + Dex + Vault           <1s (all native binaries)
Phase 3: Bundle Server + Credential Proxy   <1s
Phase 4: OPA                                <1s
Phase 5: Node.js Gateway + MCP servers      ~2s
Phase 6: Envoy                              <1s
                                            ─────
Total:                                      <5 seconds
```

### 12.3 Port Allocation

| Service | Port | Purpose |
|---------|------|---------|
| Envoy | 8000 | Agent-facing MCP endpoint (the only external port) |
| PostgreSQL | 5432 | Database |
| NPL Engine | 12000 | Policy state management |
| Dex | 5556 | OIDC provider (JWKS, token endpoint) |
| Vault | 8200 | Secret storage |
| Credential Proxy | 9002 | Credential injection |
| OPA | 8181 / 9191 | Policy evaluation (HTTP / gRPC) |
| Bundle Server | 8282 | OPA bundle serving |
| Node.js Gateway | 3000 | Aggregator + MCP management |

All on localhost. Only port 8000 is agent-facing.

---

## 13. Distribution via Homebrew

### 13.1 CLI Commands

**Lifecycle:**

| Command | Description |
|---------|-------------|
| `mcpgw init [--preset <name>]` | Interactive setup or preset configuration |
| `mcpgw up` | Starts all services as native processes |
| `mcpgw down` | Stops everything gracefully |
| `mcpgw status` | Shows running state, PIDs, health, policy revision |
| `mcpgw logs [--follow]` | Aggregated logs from all services |
| `mcpgw version` | CLI + component versions |

**OpenClaw integration:**

| Command | Description |
|---------|-------------|
| `mcpgw import openclaw` | Import MCP servers from OpenClaw config, move credentials to vault |
| `mcpgw service add <name>` | Add a new MCP service (command, args) |
| `mcpgw service list` | List configured MCP services and their status |
| `mcpgw service remove <name>` | Remove an MCP service |

**Policy:**

| Command | Description |
|---------|-------------|
| `mcpgw apply [file]` | Hot-reloads `mcp-security.yaml` into PolicyStore |
| `mcpgw profile add <name>` | Imports a community security profile |
| `mcpgw profile list` | Lists active profiles |

**Users & Identity:**

| Command | Description |
|---------|-------------|
| `mcpgw user add <name>` | Creates user in Dex (local connector) + generates API key |
| `mcpgw user list` | Lists users and grant summary |
| `mcpgw user grant <user> <service>` | Grants a user access to a service |
| `mcpgw auth add-connector <type>` | Add a Dex connector (github, saml, ldap) |

**Approvals (Layer 2):**

| Command | Description |
|---------|-------------|
| `mcpgw pending` | Lists pending approval requests |
| `mcpgw approve <id>` | Approves a pending request |
| `mcpgw deny <id> [reason]` | Denies a pending request |

**Credentials (vault mode only):**

| Command | Description |
|---------|-------------|
| `mcpgw secret set <service> <variable>` | Store a secret (prompted for value) |
| `mcpgw secret list` | List stored credential mappings |
| `mcpgw secret delete <service> <variable>` | Remove a secret |
| `mcpgw secret test <service>` | Test credential injection |

**Configuration:**

| Command | Description |
|---------|-------------|
| `mcpgw vault enable` | Enable credential vault |
| `mcpgw vault disable` | Disable credential vault |
| `mcpgw tui` | Launch the interactive management console |

### 13.2 Full OpenClaw Quick Start

```bash
# Install (one command, all native dependencies)
brew install noumena/tap/mcpgw

# Initialize
mcpgw init --preset openclaw
# ✓ Identity: Dex (local users)
# ✓ Credential vault: enabled
# ✓ Generated API key: mcpgw_sk_a1b2c3d4...
# ✓ Config written: ~/.mcpgw/mcp-security.yaml

# Import existing OpenClaw MCP servers
mcpgw import openclaw
# Found 5 MCP servers in ~/.openclaw/settings.json:
#   github       npx    GITHUB_TOKEN → vault
#   gmail        npx    GMAIL_OAUTH_TOKEN → vault
#   slack        npx    SLACK_BOT_TOKEN → vault
#   memory       npx    (no credentials)
#   filesystem   npx    (no credentials)
#
# ✓ 5 services imported
# ✓ 3 credentials moved to vault
# ✓ OpenClaw config updated (backup saved)

# Start (all native, no Docker)
mcpgw up
# ✓ PostgreSQL ready
# ✓ NPL Engine ready
# ✓ Dex ready (OIDC on localhost:5556)
# ✓ Vault ready
# ✓ OPA + Envoy ready
# ✓ MCP servers:
#   github:     npx @modelcontextprotocol/server-github (PID 12345)
#   gmail:      npx @modelcontextprotocol/server-gmail (PID 12346)
#   slack:      npx @modelcontextprotocol/server-slack (PID 12347)
#   memory:     npx @modelcontextprotocol/server-memory (PID 12348)
#   filesystem: npx @modelcontextprotocol/server-filesystem (PID 12349)
# ✓ Gateway ready on localhost:8000 (4.2s)

# Apply a security profile
mcpgw profile add @noumena/security-gmail
# ✓ Gmail profile: 6 tools classified, 2 argument classifiers

# Agent tries to send an external email → approval required
mcpgw pending
# ID       TOOL               USER        LABELS              AGE
# ap-001   gmail:send_email   developer   [scope:external]    2m

mcpgw approve ap-001
# ✓ Approved

# Later: add GitHub connector for team login
mcpgw auth add-connector github
# ✓ GitHub OAuth configured
# ✓ Team members can now log in with GitHub
```

### 13.3 Homebrew Tap

```
NoumenaDigital/homebrew-tools    (existing tap)
├── Formula/
│   ├── npl.rb                   ← NPL CLI (already exists)
│   ├── npl-engine.rb            ← NPL Engine native binary (new)
│   └── mcpgw.rb                 ← MCP Gateway CLI (new)
└── README.md
```

Release pipeline: tag → cross-compile Go CLI (macOS arm64/amd64, Linux amd64) → update formula SHA.

---

## 14. Enterprise Tier: Docker / Helm

### 14.1 Docker Compose

The existing `deployments/docker-compose.yml` continues to serve enterprise and development use cases. Keycloak replaces Dex. Supergateway replaces native STDIO. Full 4-tier network isolation.

### 14.2 All-in-One Docker Image

For enterprise quickstart without full Docker Compose:

```
noumena/mcp-gateway:latest
├── s6-overlay (process supervisor)
├── PostgreSQL (engine db + keycloak db)
├── NPL Engine (JVM)
├── Keycloak (JVM, conditionally started)
├── OPA, Envoy, Vault, Bundle Server
└── Credential Proxy
```

Setup-time toggles (Keycloak vs Dex, vault on/off) work the same way.

### 14.3 Helm Chart

Kubernetes deployment with each component as a separate deployment/statefulset. Connects to external PostgreSQL, external IdP, external Vault.

### 14.4 Community → Enterprise Upgrade

| What changes | Community | Enterprise |
|-------------|-----------|------------|
| Identity | Dex (local/GitHub) → Keycloak or external IdP | Change `ENGINE_ALLOWED_ISSUERS` |
| Database | brew PostgreSQL → managed (RDS, Cloud SQL) | Change connection string |
| Vault | Local Vault → enterprise Vault / AWS SM | Change credential proxy config |
| MCP servers | Native STDIO → Docker + supergateway | Change spawning mode |
| Policy | `mcp-security.yaml` | **Same file, no changes** |
| NPL protocols | Same | **Same, no changes** |
| OPA Rego | Same | **Same, no changes** |

The `mcp-security.yaml` format is identical across all deployment modes. Policy written on a developer laptop works unchanged in an enterprise Helm deployment.

---

## 15. Implementation Outline

### Step 1: NPL Engine Native Binary (Noumena dependency)

- Request Noumena publish the NPL Engine as a GraalVM native binary via the existing `NoumenaDigital/tools` brew tap
- They already have the GraalVM pipeline for the `npl` CLI (same Kotlin stack)
- Publish `db_init` scripts as part of the release artifact

### Step 2: Dex Integration

- Write default Dex config with local user connector
- Integrate Dex token issuance with `mcpgw user add` (write to Dex config + restart or use Dex API)
- Configure Envoy JWKS to point at Dex
- Configure NPL Engine `ALLOWED_ISSUERS` for Dex
- Test: user login → Dex token → Envoy validates → OPA evaluates → NPL Engine validates

### Step 3: Node.js Gateway with Native STDIO Spawning

- Merge mcp-aggregator functionality with STDIO process management
- Implement child process spawning, lifecycle management, restart on crash
- Add credential injection: fetch from Credential Proxy, inject into child process env
- Test: import OpenClaw config → spawn MCP servers → tool call through gateway

### Step 4: `mcpgw` CLI (Go binary)

- Native process management: start/stop/health-check all components
- `mcpgw import openclaw`: parse config, split services/credentials, rewrite config
- Interactive init flow with toggles and presets
- API calls to gateway for apply, approve, deny, status, secret management
- Dex connector management (`mcpgw auth add-connector`)

### Step 5: Homebrew Formula

- Add `mcpgw` and `npl-engine` to `NoumenaDigital/tools` tap
- Declare brew dependencies (postgresql, opa, envoy, dex, vault)
- Automated release pipeline

### Step 6: Testing

- Full native stack: `mcpgw init --preset openclaw` → import → up → tool call → approval
- All toggle combinations (3 identity × 2 vault × 2 TUI = 12 configurations)
- Federation: local users → add GitHub connector → team login
- Startup time benchmarks (target: <5s)
- End-to-end credential isolation: secret in vault → injected into child process → agent cannot access

---

## 16. Open Questions

1. **NPL Engine GraalVM binary.** Noumena already compiles the `npl` CLI with GraalVM (Kotlin → native). Can they apply the same pipeline to the Engine runtime? This is the critical dependency for the zero-Docker community tier.

2. **Credential Proxy as GraalVM native.** The Credential Proxy is also Kotlin. If Noumena provides the Engine as native, the same pipeline could produce a native Credential Proxy. Otherwise it runs on JVM (requiring `brew install openjdk`).

3. **Dex API vs config reload.** `mcpgw user add` needs to create users in Dex. Options: write to Dex config YAML and send SIGHUP, or use Dex's gRPC API (if enabled). The gRPC API is cleaner but adds complexity.

4. **STDIO process lifecycle.** How to handle MCP server crashes? Auto-restart with backoff? Idle timeout?

5. **OpenClaw config location.** Varies by platform and installation method. Auto-detect or ask?

6. **Ongoing sync with OpenClaw.** After import, if the user adds a new MCP server via OpenClaw, should the gateway detect this? Options: file watcher, manual `mcpgw sync`, or all changes go through `mcpgw`.

7. **Docker-based MCP servers.** Some MCP servers are distributed as Docker images (`docker run mcp/github`). For native mode, spawn via `docker run -i` (requires Docker) or document as enterprise-only?

8. **Vault initialization.** Dev mode (unsealed, in-memory) for simplicity vs production mode (sealed, file backend) for security. Dev mode is simpler for single-user laptop use.

9. **Envoy config generation.** Envoy needs different JWKS URIs (Dex vs Keycloak) and different upstreams (native gateway vs Docker backends). Generate config at `mcpgw init` time, or template at startup?

10. **CLI language.** Go is standard for Homebrew CLIs (cross-compilation, single binary). The existing `npl` CLI is Kotlin/GraalVM. Consistency with Noumena tooling vs Go ecosystem conventions?
