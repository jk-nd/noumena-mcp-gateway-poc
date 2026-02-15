# Credential Injection Architecture

## Overview

The Noumena MCP Gateway uses a **startup-time credential injection pattern** to securely inject credentials into MCP tool containers. Credentials are resolved once at container startup -- not per-request. The system supports two modes:

- **Simple Mode** (default): YAML-based, one credential per service
- **Expert Mode** (opt-in): NPL-based with conditional rules and full audit trail

## Architecture

**At startup (credential injection):**

```
Supergateway sidecar starts
         ↓
  Entrypoint script
         ↓
  Credential Proxy → Vault
         ↓
  Environment variables exported
         ↓
  STDIO MCP tool spawned (with credentials in env)
```

**At runtime (request flow):**

```
Agent → Envoy → Supergateway sidecar → STDIO MCP tool
        (JWT)    (credentials already in environment)
```

```
┌────────────────────────────────────────────────────────────────┐
│  Envoy (NO Vault access)                                       │
│  - Authenticates agents (JWT validation)                       │
│  - Routes requests to supergateway sidecars                    │
└────────────────────────────────────────────────────────────────┘
                            ↓
┌────────────────────────────────────────────────────────────────┐
│  Supergateway sidecar (NO Vault access at runtime)             │
│  - Entrypoint calls Credential Proxy once at startup           │
│  - Spawns STDIO MCP tool with credentials in env               │
│  - Proxies SSE/streamable-HTTP ↔ STDIO                        │
└────────────────────────────────────────────────────────────────┘

┌────────────────────────────────────────────────────────────────┐
│  Credential Proxy (HAS Vault access)                           │
│  - Called at container startup (not per-request)               │
│  - Selects which credential to use                             │
│  - Fetches secrets from Vault                                  │
│  - Returns credentials for env var injection                   │
└────────────────────────────────────────────────────────────────┘

┌────────────────────────────────────────────────────────────────┐
│  STDIO MCP tool (github, slack, etc.)                          │
│  - Runs inside supergateway sidecar container                  │
│  - Receives credentials via environment variables              │
└────────────────────────────────────────────────────────────────┘
```

## Network Isolation

The system uses a four-tier network architecture:

| Network | Purpose | Components |
|---------|---------|------------|
| `public-net` | Agent-facing | Envoy, Keycloak |
| `policy-net` | Policy checks | Governance Service ↔ NPL Engine, Credential Proxy |
| `secrets-net` | Credential injection | Supergateway sidecars ↔ Credential Proxy ↔ Vault |
| `backend-net` | Upstream MCPs | Envoy ↔ Supergateway sidecars |

Note: Credential Proxy is on both `secrets-net` (for Vault access) and `policy-net` (for NPL credential policy evaluation in expert mode).

**Security properties:**
- ✅ Envoy has NO Vault access (only Credential Proxy does)
- ✅ Governance Service has NO Vault access
- ✅ NPL Engine has NO secret access (policy only)
- ✅ Upstream MCPs isolated from agents and policy layer
- ✅ Credentials only exist in Credential Proxy memory and supergateway container env

---

## Simple Mode (Default)

### Configuration

Edit `configs/credentials.yaml`:

```yaml
mode: simple
tenant: acme

credentials:
  work_github:
    vault_path: "secret/data/tenants/{tenant}/users/{user}/github/work"
    injection:
      type: env
      mapping:
        token: "GITHUB_TOKEN"

  personal_github:
    vault_path: "secret/data/tenants/{tenant}/users/{user}/github/personal"
    injection:
      type: env
      mapping:
        token: "GITHUB_TOKEN"

  google_gemini:
    vault_path: "secret/data/tenants/{tenant}/services/gemini/api"
    injection:
      type: env
      mapping:
        api_key: "GEMINI_API_KEY"

service_defaults:
  github: work_github
  slack: prod_slack
  gemini: google_gemini
  duckduckgo: default
```

The `tenant` field specifies the default tenant for Vault path resolution. The `{tenant}` placeholder in `vault_path` is replaced with this value at runtime.

### How It Works

1. **Supergateway container starts** for `github` service
2. **Entrypoint script calls Credential Proxy** with service name
3. **Credential Proxy** looks up `service_defaults[github]` → `"work_github"`
4. **Credential Proxy** looks up `credentials[work_github]` → vault path
5. **Credential Proxy** fetches from Vault and returns credentials
6. **Credentials exported as environment variables**, STDIO tool spawned
7. **At runtime**, agent requests flow through Envoy → supergateway → STDIO tool (credentials already in env)

### When to Use

- ✅ Development and testing
- ✅ Single-tenant deployments
- ✅ One credential per service
- ✅ Simple, predictable behavior
- ✅ Fast (no NPL credential call)

---

## Expert Mode

### Configuration

1. Set mode in docker-compose or via TUI:
```bash
CREDENTIAL_MODE=expert
```

2. Bootstrap NPL with credential rules (via TUI or API):
```bash
# TUI: System > Credential Mode > Expert
# Or via NPL API:
POST /npl/policies/CredentialInjectionPolicy/addInjectionRule
{
  "serviceName": "github",
  "credentialName": "work_github",
  "condition": "metadata.repo startsWith 'acme/'",
  "priority": 10
}
```

3. Define credential mappings in `credentials.yaml` (same as simple mode)

### How It Works

1. **Supergateway container starts** for `github` service with metadata `{repo: "acme/foo"}`
2. **Entrypoint script calls Credential Proxy** with service, operation, metadata
3. **Credential Proxy calls NPL** `CredentialInjectionPolicy.selectCredential()`
4. **NPL evaluates rules**:
   - Rule (priority 10): `metadata.repo startsWith 'acme/'` → MATCH → `"work_github"`
   - Rule (priority 1): `true` → SKIP (lower priority)
5. **NPL returns** `"work_github"` + **logs decision to audit trail**
6. **Credential Proxy** looks up `credentials[work_github]` → vault path
7. **Credential Proxy** fetches from Vault and returns credentials
8. **Credentials exported as environment variables**, STDIO tool spawned
9. **At runtime**, agent requests flow through Envoy → supergateway → STDIO tool (credentials already in env)

### Advanced Rules

**Multiple GitHub accounts:**
```npl
// Use work account for company repos
addInjectionRule(
  service: "github",
  credentialName: "work_github",
  condition: "metadata.repo startsWith 'acme/'",
  priority: 10
)

// Use personal account for everything else
addInjectionRule(
  service: "github",
  credentialName: "personal_github",
  condition: "true",
  priority: 1
)
```

**Environment-specific Slack:**
```npl
// Production Slack for alerts
addInjectionRule(
  service: "slack",
  credentialName: "prod_slack",
  condition: "metadata.channel == '#alerts'",
  priority: 10
)

// Dev Slack for testing
addInjectionRule(
  service: "slack",
  credentialName: "dev_slack",
  condition: "true",
  priority: 1
)
```

**Operation-based database access:**
```npl
// Admin credentials for write operations
addInjectionRule(
  service: "database",
  credentialName: "admin_db",
  condition: "operation in ['create', 'update', 'delete']",
  priority: 10
)

// Read-only credentials for queries
addInjectionRule(
  service: "database",
  credentialName: "readonly_db",
  condition: "true",
  priority: 1
)
```

### When to Use

- ✅ Multi-tenant SaaS
- ✅ Compliance requirements (SOC 2, HIPAA, PCI)
- ✅ Complex credential selection logic
- ✅ Need full audit trail
- ✅ Dynamic rule updates without restart
- ✅ Stateful logic (failure tracking, rate limits)

### Audit Trail

Every credential selection is logged in NPL:

```
[CredentialInjectionPolicy] Selected credential for github.create_issue: work_github
[CredentialInjectionPolicy] Selected credential for slack.send_message: prod_slack
```

Query the audit log:
```bash
# Via NPL Inspector or API
GET /npl/policies/CredentialInjectionPolicy/{id}/getStatistics
```

---

## Credential Storage (Vault)

### Path Convention

```
secret/data/tenants/{tenant}/users/{user}/{service}/{credential_name}
secret/data/tenants/{tenant}/services/{service}/{credential_name}
```

**Examples:**
- User credential: `secret/data/tenants/acme/users/alice/github/work`
- Service credential: `secret/data/tenants/acme/services/slack/prod`

### Vault Structure

```json
{
  "data": {
    "data": {
      "token": "ghp_xxxxxxxxxxxx",
      "api_key": "sk_xxxxxxxxxxxx"
    }
  }
}
```

### Provision Credentials

**Via Vault CLI:**
```bash
# User credential
vault kv put secret/tenants/acme/users/alice/github/work \
  token=ghp_xxxxxxxxxxxx

# Service credential
vault kv put secret/tenants/acme/services/slack/prod \
  token=xoxb-xxxxxxxxxxxx
```

**Via Vault UI:**
1. Navigate to `secret/tenants/acme/users/alice`
2. Create secret: `github/work`
3. Add key-value: `token` = `ghp_xxx`

---

## Injection Patterns

### Environment Variables (STDIO Transport)

Most common for STDIO MCP servers:

```yaml
credentials:
  work_github:
    injection:
      type: env
      mapping:
        token: "GITHUB_TOKEN"      # Vault field -> Env var name
        api_key: "GITHUB_API_KEY"
```

**Result:** Docker STDIO services run inside supergateway sidecar containers. The supergateway entrypoint script calls the Credential Proxy at startup and exports the returned credentials as environment variables before spawning the STDIO tool:
```bash
# Inside supergateway container entrypoint:
export GITHUB_TOKEN=ghp_xxx
export GITHUB_API_KEY=sk_xxx
exec npx -y supergateway --stdio "npx -y @modelcontextprotocol/server-github"
```

The STDIO tool inherits the environment variables from its parent supergateway process.

### HTTP Headers (HTTP/WebSocket Transport)

For HTTP-based MCP servers:

```yaml
credentials:
  work_github:
    injection:
      type: header
      mapping:
        token: "Authorization"
        api_key: "X-API-Key"
```

**Result:** HTTP request with:
```
Authorization: Bearer ghp_xxx
X-API-Key: sk_xxx
```

---

## Quick Start

### 1. Choose Mode

**Via TUI:**
```bash
cd tui
npm start
# Select: System > Credential Mode > Simple or Expert
```

**Via Environment:**
```bash
export CREDENTIAL_MODE=simple  # or expert
docker compose up -d
```

### 2. Configure Credentials

Edit `configs/credentials.yaml`:

```yaml
mode: simple
tenant: acme

credentials:
  my_github:
    vault_path: "secret/data/tenants/{tenant}/users/me/github/personal"
    injection:
      type: env
      mapping:
        token: "GITHUB_TOKEN"

service_defaults:
  github: my_github
```

### 3. Store Secrets in Vault

```bash
vault kv put secret/tenants/default/users/me/github/personal \
  token=ghp_your_token_here
```

### 4. Test

```bash
# Make request to Gateway
curl -X POST http://localhost:8000/mcp \
  -H "Authorization: Bearer YOUR_JWT" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"github.create_issue","arguments":{...}}}'

# Gateway automatically injects credentials!
```

---

## Migration from V1

### V1 (Executor Pattern)

```
Gateway → RabbitMQ → Executor → Vault → Upstream
                         ↓
                    Static injection
```

### V2 (Startup Injection Pattern)

```
Supergateway sidecar startup → Credential Proxy → Vault → env vars → STDIO tool
                                      ↓
                                Dynamic injection (at startup)

Agent → Envoy → Supergateway sidecar → STDIO tool (at runtime)
```

**Benefits:**
- ✅ True streaming (no RabbitMQ)
- ✅ Faster (direct connection)
- ✅ Simpler (one proxy vs Executor + RabbitMQ)
- ✅ Policy-driven credential selection

**Migration steps:**
1. Deploy credential-proxy service
2. Update Gateway to use credential proxy
3. Move credential mappings to `credentials.yaml`
4. Remove Executor and RabbitMQ services
5. Test credential injection

---

## Troubleshooting

### Credential Proxy Not Responding

```bash
# Check if service is running
docker compose ps credential-proxy

# Check logs
docker compose logs credential-proxy

# Restart
docker compose restart credential-proxy
```

### Credentials Not Found

```bash
# Check Vault
vault kv get secret/tenants/default/users/alice/github/work

# Check credentials.yaml mapping
cat configs/credentials.yaml | grep work_github

# Check credential proxy logs
docker compose logs credential-proxy | grep "work_github"
```

### Mode Not Taking Effect

```bash
# Verify mode in docker-compose
grep CREDENTIAL_MODE deployments/docker-compose.yml

# Restart credential-proxy
docker compose restart credential-proxy

# Check health endpoint
curl http://localhost:9002/health
# Should show: {"status":"ok","mode":"simple"} or "expert"
```

### NPL Credential Selection Failing (Expert Mode)

```bash
# Check if NPL protocol is bootstrapped
curl http://localhost:12000/npl/policies/CredentialInjectionPolicy/

# Bootstrap if needed
cd tui && npm start
# Select: System > NPL Bootstrap
```

---

## Performance

### Latency Breakdown

Credential injection now happens **once at container startup**, not per-request. Per-request overhead for credentials is **0ms**.

**Startup (one-time, Simple Mode):**
- Entrypoint → Credential Proxy: ~1-2ms
- YAML lookup: ~1ms
- Vault fetch: ~5-50ms (first fetch, no cache)
- Export env vars + spawn STDIO tool: ~20ms
- **Total startup overhead: ~27-73ms** (one-time)

**Startup (one-time, Expert Mode):**
- Entrypoint → Credential Proxy: ~1-2ms
- Credential Proxy → NPL: ~10ms
- YAML lookup: ~1ms
- Vault fetch: ~5-50ms (first fetch, no cache)
- Export env vars + spawn STDIO tool: ~20ms
- **Total startup overhead: ~37-83ms** (one-time)

**Per-request (both modes):**
- Credential resolution: **0ms** (already in environment)
- Agent → Envoy → Supergateway → STDIO tool: normal request latency
- **Total per-request credential overhead: 0ms**

### Caching

- **Vault credentials**: 5 minute TTL
- **NPL policy results**: 60 second TTL (in NPL sidecar if used)
- **Upstream MCP sessions**: Kept alive between requests

---

## Security Model

### Zero Trust Principles

1. **Envoy has NO Vault access**
   - Only routes requests to supergateway sidecars
   - Never sees actual secrets

2. **Governance Service has NO Vault access**
   - Only evaluates tool-level policy via NPL
   - Never sees actual secrets

3. **NPL has NO Vault access**
   - Only makes policy decisions
   - Never sees actual secrets

4. **Credential Proxy is isolated**
   - Only accessible via `secrets-net` (and `policy-net` for NPL in expert mode)
   - Only component with Vault credentials
   - Called by supergateway sidecar entrypoint scripts at startup
   - Credentials cached in memory only (5min TTL)

5. **Credentials injected at container startup**
   - Exported as environment variables before STDIO tool spawns
   - Never logged or persisted outside the container
   - Scoped to supergateway container lifetime

### Compliance

**SOC 2 / HIPAA / PCI:**
- ✅ Complete audit trail (expert mode)
- ✅ Principle of least privilege (per-operation credentials)
- ✅ Separation of duties (policy vs secrets)
- ✅ Credential usage logging

---

## Future Enhancements

### Credential Path Standardization (TODO)

The Vault path convention needs further analysis and standardization. Currently, different services may use different path structures (e.g., `tenants/{tenant}/services/{service}/api` vs `tenants/{tenant}/users/{user}/{service}/work`). Key questions to resolve:

- Should all service-level credentials follow `tenants/{tenant}/services/{service}/{credential_name}`?
- Should user-level credentials follow `tenants/{tenant}/users/{user}/{service}/{credential_name}`?
- How should the TUI's credential onboarding flow map service names to Vault paths consistently?
- Should the `service_defaults` mapping in `credentials.yaml` match credential names exactly, or should there be a fuzzy lookup?

This analysis should align the TUI onboarding flow, the Credential Proxy's path resolution, and the Vault storage conventions.

### Planned for V3 (Enterprise)

**Credential Registry Protocol:**
```npl
protocol CredentialRegistry {
    // Store credential metadata in NPL
    provisionCredential(
        vaultPath: "...",
        scope: {
            operations: ["read"],
            validUntil: "2024-12-31",
            repositories: ["acme/*"]
        }
    )
}
```

**Benefits:**
- Centralized credential registry
- Fine-grained scoping (read-only, time-limited)
- Dynamic provisioning via API
- Enhanced audit trail

**Migration:** Simple mode → Expert mode → Registry mode (all backward compatible)

---

## OpenClaw Contribution

This architecture is designed for contribution to OpenClaw with:

1. **Simple by default** - YAML config, easy to understand
2. **Expert opt-in** - NPL-based for advanced use cases
3. **Clean separation** - Policy vs secrets
4. **Well-documented** - Clear upgrade path
5. **Production-ready** - Tested with real MCP servers

### Key Innovation

**Policy-driven credential injection** - Making credential selection a first-class policy concern rather than hardcoded logic.

This enables:
- Multi-account support (work/personal GitHub)
- Environment separation (prod/dev credentials)
- Operation-based access (read-only vs admin)
- Complete audit trail
- Zero trust architecture

---

## See Also

- [Architecture V2](ARCHITECTURE_V2.md) - Full system architecture
- [User Access Control](USER_ACCESS_CONTROL.md) - Per-user tool permissions
- [credentials.yaml](../configs/credentials.yaml) - Example configuration
