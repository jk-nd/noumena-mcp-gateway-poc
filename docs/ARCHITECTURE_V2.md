# MCP Gateway v2 — Transparent Proxy Architecture

## Overview

This document describes the target architecture for the Noumena MCP Gateway, refactored from a tool-definition-owning gateway into a **transparent MCP proxy** with interceptor-based authentication, policy enforcement, and credential injection.

The Gateway sits between agents/humans and upstream MCP servers. It does not own tool definitions — it discovers and forwards them from upstream services. All MCP messages flow through the proxy, where they are authenticated, authorized via NPL, and optionally enriched with credentials before reaching the upstream.

---

## Design Principles

1. **Transparent proxy** — the Gateway should behave as if it's not there, except for security
2. **Agents never see credentials** — secrets flow Vault → Credential Proxy → upstream only, never toward the agent
3. **NPL as pure decision engine** — NPL decides allow/deny and credential mapping, but never handles secrets
4. **Bidirectional streaming** — designed for SSE/WebSocket end-to-end from day one, supporting upstream notifications
5. **Fail-closed** — if NPL is unreachable, all requests are denied
6. **Per-session isolation** — each agent connection gets its own set of upstream MCP sessions
7. **Convention over configuration** — Vault paths, tool namespaces, and service registration follow predictable patterns

---

## Component Architecture

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
│  │              │      │          │                                │
│  │  Protocols:  │      └────┬─────┘                                │
│  │  - ToolPolicy│           │                                      │
│  │  - CredMap   │           │                                      │
│  │  - SvcReg    │           │                                      │
│  └──────────────┘           │                                      │
│                              │                                      │
├──────────────────────────────┼──────────────────────────────────────┤
│  secrets-net                 │                                      │
│                              │                                      │
│  ┌──────────────┐      ┌────┴──────────┐                           │
│  │    Vault     │◄─────│  Credential   │                           │
│  │  (per tenant)│      │  Proxy        │                           │
│  └──────────────┘      └────┬──────────┘                           │
│                              │                                      │
├──────────────────────────────┼──────────────────────────────────────┤
│  backend-net                 │                                      │
│                              │                                      │
│  ┌──────────┐  ┌──────────┐ │ ┌──────────┐                        │
│  │ DuckDuck │  │  Slack   │◄┘ │  Gmail   │  ...                   │
│  │ Go MCP   │  │  MCP     │   │  MCP     │                        │
│  └──────────┘  └──────────┘   └──────────┘                        │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

**Network isolation**: Each component sits on only the networks it needs. The Gateway spans public-net, policy-net, and connects to the Credential Proxy on secrets-net. Upstream MCP containers are on backend-net. No component spans more than two networks except the Gateway.

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
  ├── Instance F1: JWT sub=F1, act_on_behalf_of=alice  → Alice's credentials
  ├── Instance F2: JWT sub=F2, act_on_behalf_of=bob    → Bob's credentials
  └── Instance F3: JWT sub=F3, act_on_behalf_of=self   → service account credentials
```

The Gateway doesn't care about the program — it cares about the JWT. The JWT subject determines the session; `act_on_behalf_of` determines whose credentials to fetch.

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

When the Gateway aggregates `tools/list` from upstream MCPs, it prefixes each tool with the service name. When routing `tools/call`, it strips the prefix to determine the target service and the actual tool name.

---

## Message Flow — Forward Direction (Agent → Upstream)

```
1. Agent sends tools/call to Gateway:
   Authorization: Bearer <agent-JWT>
   {"method":"tools/call","params":{"name":"slack.send_message","arguments":{...}}}

2. Gateway:
   a. Validates JWT → identity = finance-agent, on_behalf_of = alice
   b. Parses namespace → service = slack, tool = send_message
   c. NPL Check (ToolPolicy): Can finance-agent call send_message on slack for alice?
      → Fail-closed if NPL unreachable
      → Denied → return error to agent
      → Allowed → continue

3. Gateway → Credential Proxy:
   Forwards request with identity headers:
     X-Agent-Session: A1
     X-User-Identity: alice
     X-Service: slack
   Body: original tools/call (with namespace stripped)

4. Credential Proxy:
   a. Compute Vault path by convention: secret/services/slack/users/alice/default
   b. Vault lookup: GET secret/services/slack/users/alice/default
      → Returns: { access_token: "xoxb-...", refresh_token: "..." }
   c. If access_token expired → refresh via OAuth token endpoint → store new tokens in Vault
   d. Open/reuse upstream MCP session for (A1, slack) with Authorization header
   e. Forward tools/call to upstream Slack MCP
   
   (Future: NPL CredentialMapping could gate step (a) and select the variant)

5. Upstream Slack MCP executes, returns result

6. Response flows back: Upstream → Credential Proxy → Gateway → Agent
   (no credentials in the response)
```

---

## Message Flow — Reverse Direction (Upstream → Agent)

Upstream MCP servers can send notifications (e.g., `notifications/resources/updated`). These flow back through the same bidirectional session:

```
1. Upstream Gmail MCP sends notification on session X

2. Credential Proxy:
   - Session X belongs to agent session A1
   - Pass through notification as-is (trust upstream for POC; sanitize later)
   - Forward to Gateway

3. Gateway:
   - Session A1 maps to the agent's SSE/WebSocket connection
   - Forward notification to agent

4. Agent receives notification
   - May choose to call tools/call in response (goes through forward flow)
```

**Key rule**: Each agent connection to the Gateway maps 1:1 to its own set of upstream sessions. Notifications on an upstream session go to the agent that established it. No fan-out, no routing decisions — the session binding is the routing.

---

## OAuth 2.0 Authentication Flow

The Gateway implements the MCP authentication specification, acting as both the protected resource and an OAuth proxy to Keycloak. This enables browser-based clients (like MCP Inspector) to authenticate without manual token management.

### Discovery Endpoints

| Endpoint | RFC | Purpose |
|----------|-----|---------|
| `GET /.well-known/oauth-protected-resource` | RFC 9728 | Returns resource metadata pointing to the Gateway as authorization server |
| `GET /.well-known/oauth-authorization-server` | RFC 8414 | Returns OAuth server metadata (endpoints, supported grants, PKCE) |

### OAuth Proxy Endpoints

| Endpoint | Purpose |
|----------|---------|
| `GET /authorize` | Redirects to Keycloak's authorization endpoint (browser-facing URL) |
| `POST /token` | Proxies token exchange to Keycloak (internal URL) |
| `POST /register` | Dynamic client registration (RFC 7591) — returns pre-configured client info |

### Flow

```
1. Client sends POST /mcp (no token)
2. Gateway returns 401 with:
   WWW-Authenticate: Bearer resource_metadata="/.well-known/oauth-protected-resource"

3. Client discovers OAuth endpoints:
   GET /.well-known/oauth-protected-resource → authorization_servers: [gateway-url]
   GET /.well-known/oauth-authorization-server → endpoints, PKCE config

4. Client registers (optional):
   POST /register → client_id, redirect_uris

5. Client initiates authorization code flow with PKCE:
   GET /authorize?response_type=code&client_id=...&code_challenge=...
   → Gateway redirects to Keycloak login page

6. User authenticates at Keycloak
   → Keycloak redirects to client callback with authorization code

7. Client exchanges code for token:
   POST /token (code, code_verifier)
   → Gateway proxies to Keycloak, returns JWT

8. Client reconnects with JWT:
   POST /mcp with Authorization: Bearer <JWT>
   → Authenticated ✓
```

### URL Routing

The Gateway uses two Keycloak URLs:
- **Internal** (`KEYCLOAK_URL`): Used for JWT validation (JWKS) and token proxy — resolved via Docker DNS
- **External** (`KEYCLOAK_EXTERNAL_URL`): Used for browser redirects in `/authorize` — must be reachable by the browser

For local development, both resolve to `keycloak:11000` (via `/etc/hosts: 127.0.0.1 keycloak`). This ensures cookies set during the Keycloak login page stay on the same domain throughout the flow.

---

## Multi-Transport Upstream Support

The Gateway connects to upstream MCP services using the transport appropriate for each service. Transport type is configured per-service in `services.yaml`.

| Type | Config Value | How It Works |
|------|-------------|--------------|
| **STDIO** | `MCP_STDIO` | Gateway runs the configured command (e.g., `docker run -i --rm <image>` or `docker run -i --rm node:22-slim npx -y <package>`) and communicates via stdin/stdout. Containers are ephemeral — spawned on first tool call, destroyed after. |
| **HTTP** | `MCP_HTTP` | Gateway sends HTTP POST requests to the upstream's endpoint URL. Uses MCP Streamable HTTP transport. |
| **WebSocket** | `MCP_WS` | Gateway opens a persistent WebSocket connection to the upstream's endpoint URL. |

### STDIO Transport Details

For STDIO services, the Gateway:
1. Spawns a Docker container on first tool call using the configured command (e.g., `docker run -i --rm <image>` for Docker images, or `docker run -i --rm node:22-slim npx -y <package>` for NPM packages)
2. Communicates using the MCP SDK's `StdioClientTransport`
3. Manages the process lifecycle (tracks PID, cleans up on shutdown)
4. Requires Docker socket mounted in the Gateway container (`/var/run/docker.sock`)
5. Requires Docker CLI installed in the Gateway image
6. For services requiring credentials, injects them as `-e KEY=VALUE` Docker flags (inserted after `docker run`)

### Configuration Example

```yaml
services:
  - name: duckduckgo
    displayName: DuckDuckGo
    type: MCP_STDIO
    command: docker run -i --rm mcp/duckduckgo
    enabled: true
    tools:
      - name: search
        enabled: true

  - name: gemini
    displayName: Google Gemini
    type: MCP_STDIO
    command: "docker run -i --rm node:22-slim npx"
    args: ["-y", "@houtini/gemini-mcp"]
    requiresCredentials: true
    enabled: true
    tools:
      - name: gemini_chat
        enabled: true

  - name: github
    displayName: GitHub
    type: MCP_HTTP
    endpoint: http://github-mcp:8080/mcp
    enabled: true
    tools:
      - name: get_issue
        enabled: true
```

---

## Lazy Connection with "Waking Up" Indicator

Upstream MCP connections are established **lazily** on first `tools/call`, not eagerly at connect time.

- `tools/list` returns tools from the ServiceRegistry / NPL configuration (not live-discovered)
- On first `tools/call` for a service, the upstream connection is established
- The agent may see a brief delay; the Gateway can indicate "waking up tool" via progress notifications

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
1. All three protocol instances → `become removed`
2. Upstream sessions closed
3. Docker container stopped
4. Vault credentials optionally retained for audit

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

**Handling**: Store in Vault, inject as HTTP header. Simple lookup, no refresh.

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
      manifest.json              ← auth type, credential schema, OAuth config
      shared/
        default                  ← org-wide credential (if applicable)
      users/
        {user-sub}/
          default                ← user's primary credential
          {variant}              ← alternatives (readonly, full, etc.)
  app/
    {provider}/                  ← OAuth app registrations (client_id, client_secret)
```

The Credential Proxy constructs paths from convention:

```
resolve(user=alice, service=slack, variant=readonly)
  → secret/services/slack/users/alice/readonly

resolve(service=duckduckgo, shared=true)
  → secret/services/duckduckgo/shared/default
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
6. Use new access_token for upstream MCP session
```

All server-to-server. Agent is not involved and never sees any tokens.

---

## Security Properties

| Property | Enforcement |
|----------|-------------|
| Agent never sees upstream credentials | Credentials flow Vault → Credential Proxy → upstream only, never toward agent |
| NPL never handles secrets | NPL returns vault paths / policy decisions, never credential values |
| Fail-closed on NPL outage | All requests denied if NPL is unreachable |
| Per-session isolation | Each agent connection gets independent upstream sessions |
| Network isolation | Four Docker networks; no component spans more than two (except Gateway) |
| Credential direction is one-way | Secrets flow toward upstream; notifications flow toward agent; the two never mix |
| Tenant isolation | Separate Vault per tenant; NPL policies scoped by organization claim |

---

## Technology Stack

| Component | Technology |
|-----------|------------|
| Gateway | Kotlin, Ktor, SSE/WebSocket |
| NPL Engine | Noumena Platform |
| Credential Proxy | Kotlin, Ktor |
| Secrets | HashiCorp Vault |
| Auth | Keycloak (OIDC) |
| Upstream MCP | Docker containers / NPM packages via Docker-wrapped npx (any MCP server) |
| TUI | TypeScript, @clack/prompts |
| Container Orchestration | Docker Compose |

---

---

## Current Architecture (V2)

```
Agent → Gateway (transparent proxy, intercepts)
          → NPL (policy check + credential selection)
          → Credential Proxy → Vault (credential fetch + inject)
          → Upstream MCP container (direct connection)
```

- Gateway discovers tools from upstream MCPs
- Direct MCP-to-MCP connections (STDIO/Streamable HTTP/WebSocket)
- Bidirectional streaming for notifications
- Credential injection isolated from Gateway and agents
- NPL as runtime source of truth for all policy decisions
- Atomic TUI operations with rollback on NPL sync failure
