# MCP Gateway Packaging Strategy — Single Image Distribution

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

1. **One image, one install.** A developer should go from zero to secured MCP gateway with `brew install` + `mcpgw up`.

2. **Layer 2 included from the start.** Stateful policy (approvals, session velocity, taint tracking) is the differentiator. Pure tool-level allow/deny (Layer 1) doesn't require this architecture — any OPA deployment can do that. The value is Layer 2. Ship it to everyone.

3. **Setup-time toggles, not packaging splits.** One image includes all components. The user enables what they need during `mcpgw init`. No separate "community" vs "enterprise" images to build, test, or maintain.

4. **Envoy stays.** Envoy is enterprise-grade, has full MCP protocol support (Streamable HTTP, SSE), and provides the ext_authz integration with OPA. Same proxy in all deployment modes.

5. **Credential isolation matters for everyone.** The OpenClaw security incidents (plaintext API keys, malicious skill exfiltration) demonstrate that credential isolation is not an enterprise-only concern. Individual developers need their API keys protected from agents and malicious tools.

---

## 3. All-in-One Docker Image

### 3.1 What's Inside

```
noumena/mcp-gateway:latest
├── s6-overlay (process supervisor)
├── PostgreSQL (one instance, up to two databases)
├── NPL Engine (JVM)
├── Keycloak (JVM, conditionally started)
├── OPA (binary)
├── Envoy (binary)
├── Node.js gateway (proxy + aggregator + bundle server + JWT minting)
├── Vault (binary, conditionally started)
└── Credential Proxy (JVM, conditionally started, shares JVM with NPL Engine)
```

All processes managed by [s6-overlay](https://github.com/just-containers/s6-overlay), which handles startup ordering, conditional startup based on config, health checks, and clean shutdown.

### 3.2 Estimated Image Size

| Component | Approximate Size |
|-----------|-----------------|
| Base OS + s6-overlay | ~50MB |
| PostgreSQL | ~100MB |
| NPL Engine (JVM) | ~300MB |
| Keycloak (JVM, shares base JVM) | ~250MB |
| OPA | ~50MB |
| Envoy | ~50MB |
| Node.js + gateway apps | ~100MB |
| Vault | ~150MB |
| Credential Proxy (JAR only, shares JVM) | ~20MB |
| **Total (compressed)** | **~800MB-1GB** |

Comparable to GitLab Omnibus (~1GB) or Supabase (~500MB). Components that are not enabled are present on disk but never started — acceptable tradeoff for a single image.

### 3.3 Volumes

```
mcpgw-data:/var/lib/mcpgw
├── postgresql/       ← PostgreSQL data (NPL state, Keycloak state)
├── vault/            ← Vault storage backend (file-based)
├── keys/             ← JWT signing key pair (API key mode)
└── config/           ← Runtime configuration
```

One named volume. Survives container restarts and upgrades.

---

## 4. Setup-Time Toggles

### 4.1 Interactive Setup

```bash
mcpgw init
```

Three toggles, each independent:

```
? Authentication mode:
  > API Key (local development, single user, small teams)
    Keycloak (enterprise, OIDC/SAML, user federation)

? Enable credential vault? (isolates API keys from agents)
  > Yes (recommended — agents never see your secrets)
    No (you manage credentials yourself)

? Enable TUI management console?
  > Yes (interactive terminal UI for policy management)
    No (CLI-only)
```

### 4.2 What Runs in Each Configuration

**Minimal setup** (API key, no vault, no TUI):

| Process | Status |
|---------|--------|
| PostgreSQL | Running (engine db only) |
| NPL Engine | Running |
| OPA | Running |
| Bundle Server | Running |
| MCP Aggregator | Running |
| Envoy | Running |
| Node.js gateway | Running (includes JWT minting + JWKS) |
| Keycloak | Not started |
| Vault | Not started |
| Credential Proxy | Not started |

**Full setup** (Keycloak, vault, TUI):

| Process | Status |
|---------|--------|
| PostgreSQL | Running (engine db + keycloak db) |
| NPL Engine | Running |
| OPA | Running |
| Bundle Server | Running |
| MCP Aggregator | Running |
| Envoy | Running |
| Node.js gateway | Running (no JWT minting) |
| Keycloak | Running |
| Vault | Running |
| Credential Proxy | Running |

### 4.3 PostgreSQL: One Instance, Shared by All

All components that need PostgreSQL share a single PostgreSQL process with separate logical databases:

```
PostgreSQL (one process, port 5432)
├── database: engine      ← NPL Engine (always created)
└── database: keycloak    ← Keycloak (created only in keycloak mode)
```

Separate databases, separate users, full isolation. One init script creates what's needed based on the active toggles.

---

## 5. Toggle 1: Authentication Mode

### 5.1 API Key Mode (Default)

For individual developers and small teams. Zero external dependencies.

**Setup:**

```bash
mcpgw init
# ? Authentication mode: > API Key
# ✓ Generated API key: mcpgw_sk_a1b2c3d4...
# ✓ Generated JWT signing key pair
#   Add the API key to your OpenClaw MCP server config.
```

**Runtime flow:**

```
OpenClaw                        Gateway Container
   │                            ┌──────────────────────────┐
   │  Authorization:            │                          │
   │  Bearer mcpgw_sk_...      │  1. Gateway validates    │
   │ ──────────────────────────>│     API key              │
   │                            │                          │
   │                            │  2. Mints internal JWT:  │
   │                            │     {sub: "developer",   │
   │                            │      iss: "mcpgw"}       │
   │                            │                          │
   │                            │  3. Envoy validates JWT  │
   │                            │     against gateway JWKS │
   │                            │                          │
   │                            │  4. OPA + NPL evaluate   │
   │                            │     (standard JWT flow)  │
   │                            └──────────────────────────┘
```

The Node.js gateway serves a `/.well-known/jwks.json` endpoint with the public key. Envoy validates JWTs against this endpoint — identical to how it validates against Keycloak, just a different URL.

**NPL Engine configuration:**
```
ENGINE_ALLOWED_ISSUERS=http://localhost:<gateway-port>
```

**Multi-user support:**
```bash
mcpgw user add alice
# ✓ API key for alice: mcpgw_sk_x7y8z9...

mcpgw user add bob
# ✓ API key for bob: mcpgw_sk_m4n5o6...

mcpgw user list
# NAME        API KEY PREFIX    GRANTS
# developer   mcpgw_sk_a1b2    3 services, 12 tools
# alice       mcpgw_sk_x7y8    1 service, 4 tools
# bob         mcpgw_sk_m4n5    2 services, 8 tools
```

Each user gets separate grants, separate approval queues, separate audit trails.

### 5.2 Keycloak Mode

For enterprise deployments that need OIDC/SAML, user federation, or integration with existing identity infrastructure.

**Setup:**

```bash
mcpgw init
# ? Authentication mode: > Keycloak
# ✓ Keycloak will start on port 11000
# ✓ Default realm: mcpgateway
# ✓ Admin console: http://localhost:11000/admin
```

**What changes:**
- Keycloak process starts alongside everything else
- PostgreSQL creates the `keycloak` database
- Envoy points JWKS at Keycloak instead of the Node.js gateway
- Node.js gateway does not mint JWTs

**NPL Engine configuration:**
```
ENGINE_ALLOWED_ISSUERS=http://localhost:11000/realms/mcpgateway
```

### 5.3 Downstream Impact: None

The auth mode is fully transparent to OPA and NPL:

| Component | API Key Mode | Keycloak Mode | Change Required |
|-----------|-------------|---------------|-----------------|
| OPA Rego policies | Reads JWT `sub` | Reads JWT `sub` | None |
| NPL protocols | Validates JWT | Validates JWT | None |
| `mcp-security.yaml` | Same format | Same format | None |
| Approval workflows | Same | Same | None |
| Audit trail | Same | Same | None |

### 5.4 Switching Modes

```bash
mcpgw auth switch keycloak
# ✓ Creating keycloak database...
# ✓ Starting Keycloak...
# ✓ Migrated 2 users from API key config
# ✓ Reconfiguring Envoy JWKS endpoint...
# ✓ Restart complete. Keycloak admin: http://localhost:11000
```

No re-download. Same container, config change + restart.

---

## 6. Toggle 2: Credential Vault

### 6.1 Why This Matters for OpenClaw

The OpenClaw security incidents documented in the Security Strategy (Section 1.1) make this clear:

- **Hundreds of exposed instances** with plaintext API keys, tokens, and conversation histories
- **~900 malicious skills** on ClawHub (~20% of all packages) that can exfiltrate credentials
- Invariant Labs demonstrated a malicious MCP server **silently exfiltrating a user's WhatsApp history**

Without credential isolation, API keys sit in environment variables or config files where the agent — and any malicious tool — can read them. The credential vault ensures **the agent can use GitHub, Gmail, and Slack without ever seeing the API keys**.

### 6.2 Vault Enabled (Recommended)

**Setup:**

```bash
mcpgw init
# ? Enable credential vault? > Yes
# ✓ Vault initialized (file storage backend)
# ✓ Credential Proxy ready
```

**Storing secrets:**

```bash
mcpgw secret set github GITHUB_TOKEN
# Enter value: ********
# ✓ Stored in vault: secret/data/services/github
# ✓ Mapped: GITHUB_TOKEN → github service

mcpgw secret set gmail GMAIL_OAUTH_TOKEN
# Enter value: ********
# ✓ Stored in vault: secret/data/services/gmail
# ✓ Mapped: GMAIL_OAUTH_TOKEN → gmail service

mcpgw secret list
# SERVICE     VARIABLE            STORED
# github      GITHUB_TOKEN        ✓
# gmail       GMAIL_OAUTH_TOKEN   ✓
```

**Runtime flow:**

```
MCP Server Container Startup
         │
         ▼
Supergateway entrypoint
         │
         ▼
POST /inject-credentials ──> Credential Proxy ──> Vault
         │                                           │
         ▼                                           │
Receives: {GITHUB_TOKEN: "ghp_..."}  <──────────────┘
         │
         ▼
export GITHUB_TOKEN=ghp_...
         │
         ▼
MCP tool starts (credentials in env)
```

The agent's request path (Envoy → OPA → NPL) never touches the secrets network. Vault and the Credential Proxy are on an isolated network. Four-tier network isolation is maintained even within the single container via process-level access control.

**What runs when vault is enabled:**

| Process | Status |
|---------|--------|
| Vault | Running (file storage backend on data volume) |
| Credential Proxy | Running (shares JVM with NPL Engine) |

### 6.3 Vault Disabled

Secrets are the user's responsibility. MCP server containers receive credentials via whatever mechanism the user configures externally (environment variables, mounted files, etc.). The gateway provides no credential isolation.

Suitable for environments where an external secret management system already handles injection (e.g., Kubernetes with External Secrets Operator, AWS ECS with Secrets Manager integration).

### 6.4 CLI Credential Management

The `mcpgw` CLI replaces the parked TUI credential management feature with a simpler interface:

```bash
mcpgw secret set <service> <variable>     # Store a secret (prompted for value)
mcpgw secret list                          # List stored credential mappings
mcpgw secret delete <service> <variable>   # Remove a secret
mcpgw secret test <service>                # Test credential injection for a service
```

This covers the essential workflow without the complexity of the full credential management UI (vault path templates, injection types, field mapping). The CLI uses sensible defaults:

- Vault path: `secret/data/services/<service>`
- Injection type: environment variable
- Mapping: direct (vault field name = env var name)

Advanced users who need custom vault paths, header injection, or multi-tenant credential routing can edit `credentials.yaml` directly or use the TUI.

---

## 7. Toggle 3: TUI Management Console

### 7.1 TUI Enabled

The interactive terminal UI for detailed policy management — service catalog, user grants, tool policies, pending approvals.

```bash
mcpgw tui
# Launches the TUI connected to the running gateway
```

The TUI is the same application currently in `tui/` — it connects to the gateway's NPL Engine and Keycloak (or API key store) APIs.

### 7.2 TUI Disabled

CLI-only management via `mcpgw` commands. Sufficient for simple setups and automation/CI workflows.

### 7.3 Relationship Between CLI and TUI

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

Both tools talk to the same APIs. They coexist — use `mcpgw` for quick operations and scripting, the TUI for interactive management.

---

## 8. Preset Configurations

For users who don't want to answer three questions, offer presets:

```bash
mcpgw init --preset openclaw
# Equivalent to: API key + vault enabled + TUI enabled
# Optimized for: individual OpenClaw developers

mcpgw init --preset team
# Equivalent to: API key + vault enabled + TUI enabled + multi-user
# Optimized for: small development teams

mcpgw init --preset enterprise
# Equivalent to: Keycloak + vault enabled + TUI enabled
# Optimized for: organizations with existing IdP infrastructure
```

The presets are shortcuts — they produce the same config file that the interactive flow generates. Users can modify the config after init.

---

## 9. Process Architecture in the Container

### 9.1 s6-overlay Service Tree

```
s6-overlay (PID 1)
├── postgresql (always)
│   ├── init: create databases based on config
│   └── healthcheck: pg_isready
├── npl-engine (always, depends on postgresql healthy)
│   └── healthcheck: HTTP /health
├── keycloak (if auth.mode == keycloak, depends on postgresql healthy)
│   └── healthcheck: HTTP /health
├── vault (if vault.enabled == true)
│   ├── init: vault operator init (first run only)
│   └── healthcheck: vault status
├── credential-proxy (if vault.enabled == true, depends on vault + npl-engine healthy)
│   └── healthcheck: HTTP /health
├── bundle-server (depends on npl-engine healthy)
│   └── healthcheck: HTTP /health
├── opa (depends on bundle-server)
│   └── healthcheck: HTTP /health/live
├── node-gateway (depends on npl-engine healthy)
│   ├── MCP aggregator
│   ├── JWKS endpoint (if auth.mode == api-key)
│   └── healthcheck: HTTP /health
└── envoy (depends on opa + node-gateway)
    └── healthcheck: HTTP /ready
```

### 9.2 Startup Ordering

```
Phase 1: PostgreSQL
         │
Phase 2: NPL Engine + Keycloak* + Vault*     (* = conditional)
         │
Phase 3: Credential Proxy* + Bundle Server + Node.js Gateway
         │
Phase 4: OPA
         │
Phase 5: Envoy
         │
         ✓ Gateway ready on port 8000
```

Total startup time target: <30 seconds on first run, <15 seconds on subsequent runs.

### 9.3 Single Port

Only port 8000 is exposed externally. All internal communication happens over localhost within the container.

| Endpoint | Port | Accessible |
|----------|------|------------|
| Envoy (MCP + management) | 8000 | External (the only exposed port) |
| PostgreSQL | 5432 | Internal only |
| NPL Engine | 12000 | Internal only |
| Keycloak | 11000 | Internal only (or optionally exposed for admin UI) |
| Vault | 8200 | Internal only |
| Credential Proxy | 9002 | Internal only |
| OPA | 8181/9191 | Internal only |
| Bundle Server | 8282 | Internal only |
| Node.js Gateway | 3000 | Internal only |

Optional: expose Keycloak admin UI on a second port for enterprise users who need direct access.

---

## 10. Distribution via Homebrew

### 10.1 The CLI: `mcpgw`

A lightweight CLI distributed via Homebrew that manages the Docker container lifecycle and communicates with the gateway API.

```bash
brew install noumena/tap/mcpgw
```

### 10.2 CLI Commands

**Lifecycle:**

| Command | Description |
|---------|-------------|
| `mcpgw init [--preset <name>]` | Interactive setup or preset configuration |
| `mcpgw up` | Pulls image (if needed), starts container |
| `mcpgw down` | Stops container gracefully |
| `mcpgw status` | Shows running state, active toggles, policy revision |
| `mcpgw logs [--follow]` | Container logs |
| `mcpgw version` | CLI + image versions |

**Policy:**

| Command | Description |
|---------|-------------|
| `mcpgw apply [file]` | Hot-reloads `mcp-security.yaml` into PolicyStore |
| `mcpgw profile add <name>` | Imports a community security profile |
| `mcpgw profile list` | Lists active profiles |

**Users:**

| Command | Description |
|---------|-------------|
| `mcpgw user add <name>` | Creates user (API key mode: generates key) |
| `mcpgw user list` | Lists users and grant summary |
| `mcpgw user grant <user> <service>` | Grants a user access to a service |

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
| `mcpgw auth switch <mode>` | Switch between api-key and keycloak |
| `mcpgw vault enable` | Enable credential vault |
| `mcpgw vault disable` | Disable credential vault |
| `mcpgw tui` | Launch the interactive management console |

### 10.3 Developer Experience: OpenClaw Quick Start

```bash
# Install
brew install noumena/tap/mcpgw

# Initialize with OpenClaw preset
mcpgw init --preset openclaw
# ✓ Auth mode: API key
# ✓ Credential vault: enabled
# ✓ Generated API key: mcpgw_sk_a1b2c3d4...
# ✓ Config written: ./mcp-security.yaml

# Start
mcpgw up
# ✓ Pulling noumena/mcp-gateway:latest...
# ✓ PostgreSQL ready
# ✓ NPL Engine ready
# ✓ Vault ready
# ✓ Gateway running on localhost:8000

# Store API keys securely
mcpgw secret set github GITHUB_TOKEN
# Enter value: ********
# ✓ Stored and mapped to github service

mcpgw secret set gmail GMAIL_OAUTH_TOKEN
# Enter value: ********
# ✓ Stored and mapped to gmail service

# Apply a security profile
mcpgw profile add @noumena/security-gmail
# ✓ Gmail profile: 6 tools classified, 2 argument classifiers

# Point OpenClaw at the gateway
# → openclaw config: gateway_url=http://localhost:8000, api_key=mcpgw_sk_a1b2c3d4...

# Agent tries to send an external email → approval required
mcpgw pending
# ID       TOOL               USER        LABELS              AGE
# ap-001   gmail:send_email   developer   [scope:external]    2m

mcpgw approve ap-001
# ✓ Approved
```

### 10.4 Homebrew Tap Structure

```
noumena/homebrew-tap
├── Formula/
│   └── mcpgw.rb          ← Homebrew formula
└── README.md
```

The formula installs a prebuilt Go binary. Release pipeline: tag → cross-compile (macOS arm64, macOS amd64, Linux amd64) → update formula SHA.

---

## 11. Enterprise Deployment Path

The all-in-one image is the quickstart path. Enterprise deployments that need horizontal scaling, high availability, or integration with existing infrastructure decompose back into the full service architecture:

| Concern | All-in-One | Enterprise |
|---------|-----------|------------|
| Deployment | `mcpgw up` | Docker Compose / Kubernetes / Helm |
| PostgreSQL | Embedded in container | Managed (RDS, Cloud SQL, etc.) |
| Auth | API key or embedded Keycloak | External IdP (Okta, Azure AD, etc.) |
| Credentials | Embedded Vault | External Vault / AWS SM / Azure KV |
| OPA | Embedded | Sidecar or standalone |
| Scaling | Single instance | Envoy + OPA horizontal, NPL single-writer |
| Config | `mcp-security.yaml` via CLI | Same file, applied via CI/CD |

The `mcp-security.yaml` format is identical across all deployment modes. Policy written for the all-in-one image works unchanged in an enterprise Helm deployment.

---

## 12. Implementation Outline

### Step 1: All-in-One Dockerfile

- Base image with s6-overlay
- Install PostgreSQL, JVM, Node.js, OPA binary, Envoy binary, Vault binary
- Add NPL Engine JAR, Keycloak distribution, Credential Proxy JAR, Node.js gateway bundle
- s6 service definitions with conditional startup based on config toggles
- PostgreSQL init script that creates databases based on active toggles
- Envoy config template with configurable JWKS URI
- Single `EXPOSE 8000`

### Step 2: Node.js Gateway Consolidation

- Merge bundle-server and mcp-aggregator into a single Node.js process
- Add JWKS endpoint (`/.well-known/jwks.json`) for API key mode
- Add API key validation middleware with JWT minting
- Key pair generation at first startup (persisted to data volume)

### Step 3: `mcpgw` CLI

- Go binary for cross-platform distribution
- Docker lifecycle management (pull, run, stop, logs)
- Interactive init flow with toggles and presets
- API calls to gateway for apply, approve, deny, status, secret management
- Config file scaffolding

### Step 4: Homebrew Tap

- `noumena/homebrew-tap` repository
- Formula for `mcpgw` CLI binary (macOS arm64, macOS amd64, Linux amd64)
- Automated release pipeline: tag → build → update formula

### Step 5: Testing

- CI matrix: all toggle combinations (2 × 2 × 2 = 8 configurations)
- Startup time benchmarks (<30s first run, <15s subsequent)
- Mode switching tests (API key → Keycloak, vault enable/disable)
- End-to-end: `mcpgw init --preset openclaw` → store secret → apply policy → tool call → approval

---

## 13. Open Questions

1. **Node.js gateway consolidation scope.** Bundle server and mcp-aggregator are currently separate applications. How much refactoring is needed to merge them into one process?

2. **Envoy config templating.** Envoy needs different JWKS URIs depending on auth mode. Template rendered at startup vs. two config files switched by s6?

3. **Image base.** Alpine (smaller, ~100MB savings) vs Debian (broader compatibility, easier debugging)? JVM, PostgreSQL, and Vault all have Alpine support.

4. **Vault initialization.** Dev mode (unsealed, in-memory) vs production mode (sealed, file backend requiring unseal keys)? Dev mode is simpler for single-user. Production mode is more secure but adds unseal key management complexity.

5. **CLI language.** Go is standard for Homebrew-distributed CLIs (cross-compilation, single binary, no runtime dependencies). Alternative: Rust (same benefits, smaller binary) or shell script (simpler, less portable).

6. **Keycloak admin UI exposure.** In Keycloak mode, should port 11000 be exposed alongside 8000? Required for user management, but adds attack surface. Could be opt-in via flag.

7. **Credential Proxy refactoring.** Currently hardcoded to Vault. For the all-in-one image this is fine (Vault is embedded). For enterprise deployments using external secret managers, the proxy would need a backend abstraction. Separate effort or part of this work?
