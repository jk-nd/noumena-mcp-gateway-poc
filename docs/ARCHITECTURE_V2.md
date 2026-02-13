# MCP Gateway v2 — Envoy AI Gateway + NPL Governance Architecture

## Overview

This document describes the architecture for the Noumena MCP Gateway, built on **Envoy AI Gateway** for MCP protocol handling and routing, with a dedicated **Governance Service** for two-tier policy enforcement via NPL.

The system sits between agents/humans and upstream MCP servers. Envoy handles the MCP protocol (Streamable HTTP), JWT validation, and routing. The Governance Service implements Envoy's `ext_authz` contract to enforce access control: fast RBAC from local config (sub-millisecond, no network) followed by stateful NPL governance checks (ToolPolicy + UserToolAccess). Upstream STDIO MCP tools run as **supergateway sidecars** that wrap STDIO as Streamable HTTP.

---

## Design Principles

1. **Envoy as the edge** — all agent traffic enters through Envoy; no custom protocol handling code
2. **Agents never see credentials** — secrets flow Vault -> Credential Proxy -> supergateway sidecar -> upstream only, never toward the agent
3. **NPL as pure decision engine** — NPL decides allow/deny and credential mapping, but never handles secrets
4. **Two-tier authorization** — fast local RBAC (sub-ms) gates expensive NPL calls; fail-closed at both tiers
5. **Fail-closed** — if the Governance Service or NPL is unreachable, all requests are denied
6. **Sidecar pattern** — each STDIO MCP tool runs in its own supergateway container, isolated on backend-net
7. **Convention over configuration** — Vault paths, tool namespaces, and service registration follow predictable patterns

---

## Component Architecture

```
+---------------------------------------------------------------------+
|  public-net                                                          |
|                                                                      |
|  +--------------+      +-------------------+                         |
|  |   Keycloak   |      | Envoy AI Gateway  |<---- Agents / Humans   |
|  |   (OIDC)     |      | (port 8000)       |      (JWT auth)        |
|  +--------------+      | - MCP Streamable   |                        |
|                         |   HTTP             |                        |
|                         | - JWT validation   |                        |
|                         |   (jwt_authn)      |                        |
|                         | - ext_authz        |                        |
|                         | - SSE streaming    |                        |
|                         +--------+----------+                        |
|                                  |                                    |
+----------------------------------+------------------------------------+
|  policy-net                      |  ext_authz                        |
|                                  v                                    |
|  +--------------+      +-------------------+                         |
|  |  NPL Engine  |<-----|  Governance Svc   |                         |
|  |              |      |  (Kotlin/Ktor)    |                         |
|  |  Protocols:  |      |  port 8090        |                         |
|  |  - ToolPolicy|      |                   |                         |
|  |  - UserTool  |      | 1. Fast RBAC      |                         |
|  |    Access    |      |    (services.yaml)|                         |
|  |  - CredMap   |      | 2. NPL governance |                         |
|  |  - SvcReg    |      +-------------------+                         |
|  +--------------+                                                    |
|                                                                      |
+----------------------------------------------------------------------+
|  secrets-net                                                         |
|                                                                      |
|  +--------------+      +-------------------+                         |
|  |    Vault     |<-----|  Credential Proxy |                         |
|  |  (per tenant)|      |  (port 9002)      |                         |
|  +--------------+      +-------------------+                         |
|                                  ^                                    |
+----------------------------------+------------------------------------+
|  backend-net                     |  credential fetch at startup      |
|                                  |                                    |
|  +------------------+  +------------------+  +------------------+    |
|  | duckduckgo-mcp   |  |  github-mcp      |  | mock-calendar-   |   |
|  | (supergateway    |  |  (supergateway   |  |  mcp             |   |
|  |  sidecar)        |  |   sidecar)       |  | (HTTP-native)    |   |
|  | wraps STDIO as   |  |  wraps STDIO as  |  | POST /mcp +      |   |
|  | Streamable HTTP  |  |  Streamable HTTP |  | GET /mcp (SSE)   |   |
|  +------------------+  +------------------+  +------------------+   |
|                                                                      |
+----------------------------------------------------------------------+
```

### Network Isolation

Each component sits on only the networks it needs:

| Component | Networks | Rationale |
|-----------|----------|-----------|
| **Envoy AI Gateway** | public-net, backend-net, policy-net | Receives agent traffic, routes to backends, sends ext_authz to Governance Service |
| **Governance Service** | policy-net, public-net | ext_authz from Envoy; calls NPL Engine on policy-net; calls Keycloak on public-net for service tokens |
| **Keycloak** | public-net | Agent-facing OIDC provider; JWKS endpoint for Envoy and NPL Engine |
| **NPL Engine** | policy-net, public-net | Policy decisions; Keycloak for token validation |
| **Supergateway sidecars** | backend-net (+ secrets-net if credentials needed) | Only reachable by Envoy; credential fetch at startup |
| **Mock Calendar MCP** | backend-net | HTTP-native MCP server for bilateral streaming tests |
| **Credential Proxy** | secrets-net, policy-net | Fetches from Vault, serves credentials to sidecars |
| **Vault** | secrets-net | Credential storage only |

---

## Identity Model

### Agents Have Their Own Identity

Agents authenticate with **their own JWT**, not the user's. The JWT carries a delegation claim:

```json
{
  "sub": "finance-agent-instance-123",
  "act_on_behalf_of": "alice",
  "agent_type": "finance",
  "scope": "gmail.read slack.read",
  "organization": "acme"
}
```

The agent **never impersonates** the user. It has its own identity with a claim that says who authorized it. This maps cleanly to NPL parties.

### Multiple Instances of the Same Agent

The same agent program can be spawned for different users. Each instance is a separate session:

```
Finance Agent (program)
  +-- Instance F1: JWT sub=F1, act_on_behalf_of=alice  -> Alice's credentials
  +-- Instance F2: JWT sub=F2, act_on_behalf_of=bob    -> Bob's credentials
  +-- Instance F3: JWT sub=F3, act_on_behalf_of=self   -> service account credentials
```

The JWT subject determines the session; `act_on_behalf_of` determines whose credentials to fetch.

---

## Tool Namespacing

Tools are namespaced by service to avoid collisions:

```
slack.send_message
slack.list_channels
gmail.search_emails
gmail.send_email
github.create_issue
duckduckgo.search
```

When Envoy aggregates `tools/list` from upstream MCP services, tools are prefixed with the service name. When routing `tools/call`, the prefix determines the target supergateway sidecar and the actual tool name.

---

## Message Flow — Forward Direction (Agent -> Upstream)

```
1. Agent sends tools/call to Envoy AI Gateway (port 8000):
   Authorization: Bearer <agent-JWT>
   POST /mcp
   {"method":"tools/call","params":{"name":"slack.send_message","arguments":{...}}}

2. Envoy — JWT validation (jwt_authn filter):
   a. Validates JWT signature against Keycloak JWKS
   b. Checks issuer, audience claims
   c. Forwards JWT in Authorization header to downstream filters
   d. If invalid -> 401 Unauthorized (agent never reaches backend)

3. Envoy — ext_authz to Governance Service:
   a. Sends full request (headers + JSON-RPC body) to governance-service:8090
   b. Governance Service parses JSON-RPC body:
      - Extracts method ("tools/call"), tool name ("slack.send_message")
   c. Governance Service decodes JWT from Authorization header:
      - Extracts userId (email > preferred_username > sub)
   d. Non-tool-call methods (initialize, tools/list, ping) -> allow immediately
   e. For tools/call:

      FAST PATH (sub-ms, no network):
        Check services.yaml user_access config:
        - Find user by userId
        - Parse namespace: "slack.send_message" -> service=slack, tool=send_message
        - Check if user has access to slack.send_message (or wildcard "slack.*")
        - If denied -> 403 (never hits NPL)

      SLOW PATH (NPL, only if fast path passed):
        - ToolPolicy.checkAccess(service=slack, tool=send_message, user=alice)
        - UserToolAccess.hasAccess(service=slack, tool=send_message)
        - Both must pass -> allow
        - Either fails -> 403

   f. Governance Service returns 200 (allow) or 403 (deny) to Envoy

4. Envoy — route to backend:
   a. Routes request to the appropriate supergateway sidecar (e.g., slack-mcp:8000)
   b. Sidecar receives Streamable HTTP request
   c. Sidecar forwards to the wrapped STDIO MCP tool via stdin/stdout
   d. STDIO tool executes and returns result via stdout

5. Response flows back:
   STDIO tool -> supergateway sidecar -> Envoy -> Agent
   (no credentials in the response — they were injected at sidecar startup)
```

---

## Message Flow — Reverse Direction (Upstream -> Agent)

Upstream MCP servers can send notifications (e.g., `notifications/resources/updated`). These flow back through SSE streaming via **bilateral (bidirectional) streaming**:

### Via Supergateway Sidecars (STDIO tools)

```
1. STDIO MCP tool sends notification via stdout

2. Supergateway sidecar:
   - Receives notification from STDIO process
   - Forwards as SSE event on the Streamable HTTP connection

3. Envoy:
   - Streams SSE event back to the agent's connection
   - No authorization check on reverse direction (trust upstream)

4. Agent receives notification
   - May choose to call tools/call in response (goes through forward flow)
```

### Via HTTP-native MCP Servers (e.g., mock-calendar-mcp)

HTTP-native MCP servers support bilateral streaming directly without supergateway:

```
1. Agent opens GET /mcp with Accept: text/event-stream
   (JWT auth + ext_authz governance check at stream setup)

2. Envoy routes to HTTP-native MCP server (timeout: 0s — never timeout)

3. MCP server pushes SSE notifications:
   event: message
   data: {"jsonrpc":"2.0","method":"notifications/message","params":{...}}

4. Agent receives notifications as long as SSE connection is open
   - May call POST /mcp tools/call in response (separate forward flow)
```

The mock-calendar-mcp server demonstrates this pattern: it sends periodic calendar notifications ("Meeting starting in 5 minutes") over GET /mcp while handling normal JSON-RPC requests on POST /mcp.

**Key rule**: Each agent connection to Envoy maps to its own set of upstream sessions via Envoy's routing. Notifications on an upstream session go to the agent that established it.

---

## JWT Authentication

Envoy handles JWT validation directly using its `jwt_authn` HTTP filter. There are no OAuth proxy endpoints implemented in the gateway.

### How It Works

1. Envoy's `jwt_authn` filter validates the JWT signature against Keycloak's JWKS endpoint
2. Claims (issuer, audience) are verified against the Envoy configuration
3. The validated JWT is forwarded in the `Authorization` header to downstream filters and backends
4. Invalid or missing JWTs on protected routes result in a 401 response from Envoy itself

### Envoy jwt_authn Configuration

```yaml
providers:
  keycloak:
    issuer: "http://keycloak:11000/realms/mcpgateway"
    audiences: ["account", "mcpgateway"]
    remote_jwks:
      http_uri:
        uri: "http://keycloak:11000/realms/mcpgateway/protocol/openid-connect/certs"
        cluster: keycloak
        timeout: 5s
      cache_duration: { seconds: 300 }
    forward: true
    payload_in_metadata: "jwt_payload"
rules:
  - match: { prefix: "/mcp" }
    requires: { provider_name: "keycloak" }
  - match: { prefix: "/sse" }
    requires: { provider_name: "keycloak" }
  - match: { path: "/health" }
    requires: { allow_missing_or_failed: {} }
```

### Token Acquisition

Agents and users obtain JWTs from Keycloak directly (e.g., via the Keycloak token endpoint or browser-based OIDC flow). The gateway itself does not proxy OAuth endpoints — it only validates the resulting JWT.

For local development, Keycloak runs on `keycloak:11000` (via `/etc/hosts: 127.0.0.1 keycloak`), and pre-provisioned users (gateway, jarvis, alice, etc.) can obtain tokens via the Keycloak password grant.

---

## Supergateway Sidecar Architecture

The gateway no longer spawns STDIO processes directly. Instead, each STDIO-based MCP tool runs as a **supergateway sidecar container** that wraps the STDIO interface as Streamable HTTP.

### How It Works

1. Each STDIO MCP tool gets its own Docker container running supergateway (Node.js)
2. Supergateway starts the STDIO MCP tool as a child process
3. Supergateway exposes the tool's MCP interface as Streamable HTTP on a port (typically 8000)
4. Envoy routes to these sidecar containers on backend-net

### Benefits Over Direct STDIO

| Aspect | Old (Direct STDIO) | New (Supergateway Sidecar) |
|--------|--------------------|-----------------------------|
| Process management | Gateway spawns/manages Docker processes, needs Docker socket | Each sidecar manages its own child process |
| Credential injection | Per-request by Gateway via Docker `-e` flags | At container startup via entrypoint calling Credential Proxy |
| Protocol | Mixed (STDIO, HTTP, WebSocket in Gateway code) | Uniform Streamable HTTP from Envoy's perspective |
| Scaling | Single Gateway bottleneck for process management | Each sidecar is independent; Envoy load-balances |
| Isolation | Shared Gateway process space | Separate container per tool |

### Configuration Example

In `docker-compose.yml`, each sidecar is defined as a service:

```yaml
# DuckDuckGo MCP — web search (no credentials needed)
duckduckgo-mcp:
  build:
    context: ..
    dockerfile: deployments/docker/Dockerfile.supergateway
  environment:
    MCP_COMMAND: "docker run -i --rm mcp/duckduckgo"
    PORT: "8000"
  volumes:
    - /var/run/docker.sock:/var/run/docker.sock
  networks:
    - backend-net

# GitHub MCP — with credential injection at startup
github-mcp:
  build:
    context: ..
    dockerfile: deployments/docker/Dockerfile.supergateway
  environment:
    MCP_COMMAND: "docker run -i --rm -e GITHUB_TOKEN mcp/github"
    PORT: "8000"
    CREDENTIAL_PROXY_URL: "http://credential-proxy:9002"
    SERVICE_NAME: "github"
    TENANT_ID: "acme"
  volumes:
    - /var/run/docker.sock:/var/run/docker.sock
  networks:
    - backend-net
    - secrets-net  # For credential proxy access
```

Envoy routes to these sidecars via cluster definitions:

```yaml
clusters:
  - name: duckduckgo_mcp
    type: STRICT_DNS
    load_assignment:
      endpoints:
        - lb_endpoints:
            - endpoint:
                address:
                  socket_address:
                    address: duckduckgo-mcp
                    port_value: 8000
```

---

## Credential Injection

Credentials are injected at **supergateway container startup**, not per-request by the gateway.

### Flow

```
1. Supergateway sidecar starts
2. Entrypoint script checks if CREDENTIAL_PROXY_URL is set
3. If set, calls Credential Proxy:
     GET http://credential-proxy:9002/credentials?service=github&tenant=acme
4. Credential Proxy:
   a. Computes Vault path: secret/data/tenants/acme/services/github/...
   b. Fetches credentials from Vault
   c. Returns credentials as environment variables
5. Sidecar exports credentials as env vars (e.g., GITHUB_TOKEN)
6. Sidecar starts the STDIO MCP tool with those env vars
7. All subsequent tool calls use the pre-injected credentials
```

This means credentials are fetched once at startup, not on every request. For services requiring per-user credentials, a different model will be needed (future work).

---

## NPL Protocols

Three generic protocols, deployed once, instantiated per service at runtime:

### ServiceRegistry

Tracks which services exist and their status. Already implemented; extended with tool namespace metadata.

### ToolPolicy

Governs whether a specific tool call is allowed:

```npl
@api
protocol[pAdmin, pGateway] ToolPolicy(
    var serviceName: Text
) {
    initial state active;
    final state removed;

    var toolRules = listOf<ToolRule>();

    permission[pAdmin] setToolAccess(
        toolName: Text,
        agentType: Text,
        allowed: Boolean
    ) | active {
        // Add or update rule
    };

    permission[pGateway] checkToolAccess(
        toolName: Text,
        agentType: Text,
        userName: Text
    ) returns Boolean | active {
        // Evaluate rules, return allowed/denied
    };
};
```

### CredentialInjectionPolicy

NPL governs credential selection at runtime. The `CredentialInjectionPolicy` protocol determines which credential to use for each request, while the Credential Proxy handles the actual Vault fetch and injection. NPL never sees or handles the credentials themselves.

**Two credential scopes:**
- **Tenant-level**: Shared across all users in an organization (e.g., company Slack workspace)
  - Vault path: `secret/data/tenants/{tenant}/services/SERVICE/...`
- **User-level**: Per-user credentials (e.g., personal GitHub tokens)
  - Vault path: `secret/data/tenants/{tenant}/users/{user}/SERVICE/...`

At runtime, the JWT provides `{tenant}` and `{user}` values, and the Credential Proxy interpolates the Vault path accordingly.

### Runtime Instance Lifecycle

When a service is added via TUI/API:
1. Create `ServiceRegistry` instance
2. Create `ToolPolicy` instance
3. Create `CredentialMapping` instance
4. Admin configures rules via permissions

When a service is removed:
1. All three protocol instances -> `become removed`
2. Supergateway sidecar container stopped
3. Vault credentials optionally retained for audit

**No NPL redeployment required** — protocols are the schema, instances are the data.

---

## Credential Categories

Analysis of popular MCP servers reveals three authentication patterns:

### Category 1: Static API Key / PAT (~20% of services)

| Service | Credential | Notes |
|---------|-----------|-------|
| GitHub | Personal Access Token | Long-lived |
| Brave Search | API Key | Per-org |
| DuckDuckGo | None | Free API |

**Handling**: Store in Vault, inject at supergateway startup as environment variables. Simple lookup, no refresh.

### Category 2: OAuth 2.0 (User-Delegated) (~70% of services)

| Service | Access Token TTL | Refresh Token TTL | Notes |
|---------|-----------------|-------------------|-------|
| Google (Gmail, Drive, Calendar) | ~1 hour | Indefinite* | *Expires if unused 6 months |
| Slack (user token) | 12 hours | Rotates on refresh | Must store new refresh token atomically |
| Slack (bot token) | Indefinite | N/A | No refresh needed |
| Atlassian/Jira | 1 hour | 90 days | |
| Salesforce | ~2 hours | Indefinite* | *Until admin revokes |

**Handling**: Store refresh token in Vault. Credential Proxy refreshes access token when expired. Server-to-server — agent never involved.

**One-time setup**: User authorizes via browser (OAuth consent screen). Tokens stored in Vault. User never needs to interact again unless they revoke access.

### Category 3: Session-Based / Interactive (~10% of services)

| Service | Auth Method | Notes |
|---------|-----------|-------|
| WhatsApp | QR code scan | Re-auth every ~20 days, persistent bridge process |

**Handling**: Per-user stateful bridge instances. Different deployment model — deferred.

---

## Vault Structure (Convention-Based)

Separate Vault instance per tenant for hard isolation.

```
secret/
  services/
    {service-name}/
      manifest.json              <- auth type, credential schema, OAuth config
      shared/
        default                  <- org-wide credential (if applicable)
      users/
        {user-sub}/
          default                <- user's primary credential
          {variant}              <- alternatives (readonly, full, etc.)
  app/
    {provider}/                  <- OAuth app registrations (client_id, client_secret)
```

The Credential Proxy constructs paths from convention:

```
resolve(user=alice, service=slack, variant=readonly)
  -> secret/services/slack/users/alice/readonly

resolve(service=duckduckgo, shared=true)
  -> secret/services/duckduckgo/shared/default
```

No hardcoded paths. The path is computed from `(service, user, variant)`.

---

## OAuth Token Refresh Flow

When an access token expires (runtime, no user interaction):

```
1. Credential Proxy detects expired access_token (expires_at < now)
2. Fetch from Vault: refresh_token + app credentials (client_id, client_secret)
3. POST to provider's token endpoint:
     grant_type=refresh_token
     &refresh_token=<stored_refresh_token>
     &client_id=<app_client_id>
     &client_secret=<app_client_secret>
4. Provider returns: new access_token (+ optionally new refresh_token for Slack)
5. Store updated tokens in Vault
6. Use new access_token for upstream MCP tool
```

All server-to-server. Agent is not involved and never sees any tokens.

---

## Security Properties

| Property | Enforcement |
|----------|-------------|
| Agent never sees upstream credentials | Credentials injected at sidecar startup; flow Vault -> Credential Proxy -> supergateway sidecar -> STDIO tool only |
| NPL never handles secrets | NPL returns vault paths / policy decisions, never credential values |
| Two-tier fail-closed | Fast RBAC denies first; NPL denies second; both must pass for access |
| Fail-closed on governance outage | Envoy `failure_mode_allow: false` — all requests denied if Governance Service or NPL is unreachable |
| Network isolation | Four Docker networks; Envoy spans three (public, backend, policy); all other components span at most two |
| Credential direction is one-way | Secrets flow toward upstream tools; responses flow toward agent; the two never mix |
| Tenant isolation | Separate Vault per tenant; NPL policies scoped by organization claim |
| No Docker socket in gateway | Envoy has no Docker socket; only supergateway sidecars mount it for spawning STDIO tool containers |

---

## Technology Stack

| Component | Technology |
|-----------|------------|
| Edge Gateway | Envoy AI Gateway (`envoyproxy/envoy:v1.32-latest`), port 8000 |
| Governance Service | Kotlin, Ktor (`ext_authz` backend), port 8090 |
| NPL Engine | Noumena Platform |
| Credential Proxy | Kotlin, Ktor |
| Secrets | HashiCorp Vault |
| Auth | Keycloak (OIDC) — JWT validation via Envoy `jwt_authn` filter |
| Backend MCP | Supergateway (Node.js) wrapping STDIO tools as Streamable HTTP; HTTP-native MCP servers (Express/Node.js) for bilateral streaming |
| TUI | TypeScript, @clack/prompts |
| Container Orchestration | Docker Compose |

---

## Current Architecture Summary

```
Agent -> Envoy AI Gateway (JWT validated, Streamable HTTP)
           -> Governance Service (ext_authz: fast RBAC + NPL governance)
              -> NPL Engine (ToolPolicy.checkAccess, UserToolAccess.hasAccess)
           -> Supergateway Sidecar (Streamable HTTP -> STDIO MCP tool)

Credential injection:
  Sidecar startup -> Credential Proxy -> Vault -> env vars -> STDIO tool
```

- Envoy handles MCP protocol, JWT validation, SSE streaming, and routing
- Governance Service provides two-tier authorization via Envoy ext_authz
- Supergateway sidecars wrap STDIO tools as uniform Streamable HTTP endpoints
- Credential injection happens at container startup, not per-request
- NPL remains the runtime source of truth for stateful policy decisions
- Fast RBAC from services.yaml gates expensive NPL calls (sub-ms, no network)
