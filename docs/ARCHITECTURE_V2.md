# MCP Gateway v2 — Envoy AI Gateway + OPA + NPL Architecture

## Overview

This document describes the architecture for the Noumena MCP Gateway, built on **Envoy AI Gateway** for MCP protocol handling and routing, with **OPA (Open Policy Agent)** as the fast policy evaluation engine and **NPL Engine** as the policy state manager.

The system sits between agents/humans and upstream MCP servers. Envoy handles the MCP protocol (Streamable HTTP), JWT validation, and routing. OPA implements Envoy's `ext_authz` contract via gRPC and evaluates Rego policy rules against NPL-sourced data cached via `http.send()`. NPL serves as the source of truth for policy state — admin mutations flow through TUI/API to NPL, and OPA self-polls NPL with a 5-second cache TTL. Upstream STDIO MCP tools run as **supergateway sidecars** that wrap STDIO as Streamable HTTP.

**Only two components in the policy chain: OPA + NPL. No governance service. No policy-sync service.**

---

## Design Principles

1. **Envoy as the edge** — all agent traffic enters through Envoy; no custom protocol handling code
2. **Agents never see credentials** — secrets flow Vault -> Credential Proxy -> supergateway sidecar -> upstream only, never toward the agent
3. **OPA for fast policy evaluation** — Rego rules with cached NPL data; sub-millisecond from cache, ~50ms on cache miss
4. **NPL as policy state manager** — NPL stores and manages policy state; OPA reads it via `http.send()` with caching
5. **Fail-closed** — if OPA or NPL is unreachable (after cache expires), all requests are denied
6. **Sidecar pattern** — each STDIO MCP tool runs in its own supergateway container, isolated on backend-net
7. **Convention over configuration** — Vault paths, tool namespaces, and service registration follow predictable patterns
8. **No sync service** — OPA self-polls NPL via `http.send()` response caching; no separate sync/polling process needed

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
|                         | - ext_authz (gRPC) |                        |
|                         | - SSE streaming    |                        |
|                         +--------+----------+                        |
|                                  |                                    |
+----------------------------------+------------------------------------+
|  policy-net                      |  ext_authz (gRPC)                 |
|                                  v                                    |
|  +--------------+      +-------------------+                         |
|  |  NPL Engine  |<-----|  OPA Sidecar      |                         |
|  |              | http  |  (Rego policy)    |                         |
|  |  Protocols:  | .send |  port 9191 gRPC   |                         |
|  |  - ToolPolicy|  (5s  |  port 8181 HTTP   |                         |
|  |  - UserTool  | cache)|                   |                         |
|  |    Access    |       | Cached NPL data   |                         |
|  |  - SvcReg    |       | + JWT decode      |                         |
|  +--------------+       | + JSON-RPC parse   |                         |
|                         +-------------------+                         |
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
| **Envoy AI Gateway** | public-net, backend-net, policy-net | Receives agent traffic, routes to backends, sends ext_authz to OPA |
| **OPA Sidecar** | policy-net, public-net | ext_authz from Envoy; fetches NPL data on policy-net; fetches Keycloak tokens on public-net |
| **Keycloak** | public-net | Agent-facing OIDC provider; JWKS endpoint for Envoy and NPL Engine |
| **NPL Engine** | policy-net, public-net | Policy state storage; Keycloak for token validation |
| **Supergateway sidecars** | backend-net (+ secrets-net if credentials needed) | Only reachable by Envoy; credential fetch at startup |
| **Mock Calendar MCP** | backend-net | HTTP-native MCP server for bilateral streaming tests |
| **Credential Proxy** | secrets-net, policy-net | Fetches from Vault, serves credentials to sidecars |
| **Vault** | secrets-net | Credential storage only |

---

## OPA Policy Evaluation

### How OPA Self-Polls NPL (No Sync Service)

OPA's `http.send()` has built-in response caching. The Rego policy (`policies/mcp_authz.rego`) fetches NPL data lazily:

```rego
# Cached for 5 seconds — only hits NPL when cache expires
npl_service_registry_response := http.send({
    "method": "GET",
    "url": sprintf("%s/npl/registry/ServiceRegistry/", [npl_url]),
    "headers": {"Authorization": npl_auth_header},
    "force_cache": true,
    "force_cache_duration_seconds": 5
})

npl_tool_policy_response := http.send({
    "method": "GET",
    "url": sprintf("%s/npl/services/ToolPolicy/", [npl_url]),
    "headers": {"Authorization": npl_auth_header},
    "force_cache": true,
    "force_cache_duration_seconds": 5
})

npl_user_access_response := http.send({
    "method": "GET",
    "url": sprintf("%s/npl/users/UserToolAccess/", [npl_url]),
    "headers": {"Authorization": npl_auth_header},
    "force_cache": true,
    "force_cache_duration_seconds": 5
})
```

**Request flow:**
1. First request (or after cache expires): OPA calls NPL APIs (~50ms), caches responses
2. All subsequent requests within 5s: OPA uses cached data (~0.1ms)
3. After 5s: Next request triggers fresh NPL fetch, re-caches

**On NPL unavailable**: OPA serves from stale cache until TTL expires. After that, `http.send()` returns error -> Rego evaluates to `default allow = false` -> fail-closed.

**Keycloak token**: OPA also fetches a gateway service account token via `http.send()` to Keycloak's token endpoint, cached with a 60s TTL.

### What Stays in NPL vs. Moves to OPA

| NPL Protocol | Role | Runtime Query |
|---|---|---|
| `ServiceRegistry` | State manager: which services are enabled | OPA reads via `http.send()` (cached 5s) |
| `ToolPolicy` | State manager: which tools are enabled per service. `suspendPolicy()` = emergency kill | OPA reads via `http.send()` (cached 5s) |
| `UserToolAccess` | State manager: per-user tool access grants. `revokeAllAccess()` = emergency revoke | OPA reads via `http.send()` (cached 5s) |
| `UserRegistry` | Admin-only: user management | OPA reads via `http.send()` (cached 5s) |
| `CredentialInjectionPolicy` | Startup-time credential selection | **Unchanged** — not in ext_authz path |

**NPL protocols are NOT modified.** Their `checkAccess()` / `hasAccess()` permissions remain for admin testing via TUI. OPA reads the underlying data directly via the list/get APIs.

### Policy Decision Logic

The Rego policy (`policies/mcp_authz.rego`) implements:

1. **JWT decode**: Extract userId from `email > preferred_username > sub` claims
2. **JSON-RPC parse**: Extract method and tool name from request body
3. **Stream setup** (GET /mcp): Allow if user has any tool access
4. **Non-tool methods** (initialize, tools/list, ping, notifications/*): Always allow
5. **tools/call**: Check service enabled -> tool enabled -> user has access
6. **Default deny**: `default allow = false` — fail-closed on any error or missing data

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
   {"method":"tools/call","params":{"name":"duckduckgo.search","arguments":{...}}}

2. Envoy — JWT validation (jwt_authn filter):
   a. Validates JWT signature against Keycloak JWKS
   b. Checks issuer, audience claims
   c. Forwards JWT in Authorization header to downstream filters
   d. If invalid -> 401 Unauthorized (agent never reaches backend)

3. Envoy — ext_authz to OPA (gRPC):
   a. Sends full request (headers + JSON-RPC body) to OPA sidecar on port 9191
   b. OPA decodes JWT: extracts userId (email > preferred_username > sub)
   c. OPA parses JSON-RPC body:
      - Extracts method ("tools/call"), tool name ("duckduckgo.search")
      - Parses namespace: service=duckduckgo, tool=search
   d. Non-tool-call methods (initialize, tools/list, ping) -> allow immediately
   e. For tools/call, OPA fetches NPL data via http.send() (cached 5s):
      - ServiceRegistry: is "duckduckgo" enabled?
      - ToolPolicy: is "search" in enabledTools for "duckduckgo"?
      - UserToolAccess: does this user have access to duckduckgo.search?
   f. OPA returns allow or deny to Envoy

4. Envoy — route to backend:
   a. Routes request to the appropriate supergateway sidecar (e.g., duckduckgo-mcp:8000)
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
   (JWT auth + ext_authz OPA check at stream setup)

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

Envoy handles JWT validation directly using its `jwt_authn` HTTP filter.

### How It Works

1. Envoy's `jwt_authn` filter validates the JWT signature against Keycloak's JWKS endpoint
2. Claims (issuer, audience) are verified against the Envoy configuration
3. The validated JWT is forwarded in the `Authorization` header to downstream filters and backends
4. Invalid or missing JWTs on protected routes result in a 401 response from Envoy itself

### Token Acquisition

Agents and users obtain JWTs from Keycloak directly (e.g., via the Keycloak token endpoint or browser-based OIDC flow). The gateway exposes OAuth discovery endpoints (RFC 9728/8414) and proxies `/token` to Keycloak for MCP Inspector compatibility.

For local development, Keycloak runs on `keycloak:11000` (via `/etc/hosts: 127.0.0.1 keycloak`), and pre-provisioned users (gateway, jarvis, alice, etc.) can obtain tokens via the Keycloak password grant.

---

## Supergateway Sidecar Architecture

Each STDIO-based MCP tool runs as a **supergateway sidecar container** that wraps the STDIO interface as Streamable HTTP.

### How It Works

1. Each STDIO MCP tool gets its own Docker container running supergateway (Node.js)
2. Supergateway starts the STDIO MCP tool as a child process
3. Supergateway exposes the tool's MCP interface as Streamable HTTP on a port (typically 8000)
4. Envoy routes to these sidecar containers on backend-net

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

Generic protocols, deployed once, instantiated per service at runtime. OPA reads their state via HTTP APIs.

### ServiceRegistry

Tracks which services exist and their status. OPA reads `enabledServices` via `GET /npl/registry/ServiceRegistry/`.

### ToolPolicy

Per-service tool access policy. OPA reads `enabledTools` and `@state` (active/suspended) via `GET /npl/services/ToolPolicy/`. Admin can suspend a policy as an emergency kill switch.

### UserToolAccess

Per-user tool access grants. OPA reads `serviceAccess` (list of service+tools) via `GET /npl/users/UserToolAccess/`. Supports wildcard (`"*"`) for granting all tools in a service.

### CredentialInjectionPolicy

NPL governs credential selection at runtime. The `CredentialInjectionPolicy` protocol determines which credential to use for each request, while the Credential Proxy handles the actual Vault fetch and injection. NPL never sees or handles the credentials themselves. **Not in the ext_authz path.**

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

## Security Properties

| Property | Enforcement |
|----------|-------------|
| Agent never sees upstream credentials | Credentials injected at sidecar startup; flow Vault -> Credential Proxy -> supergateway sidecar -> STDIO tool only |
| NPL never handles secrets | NPL returns vault paths / policy decisions, never credential values |
| Fail-closed on OPA/NPL outage | Envoy `failure_mode_allow: false` — all requests denied if OPA unreachable; OPA `default allow = false` — all requests denied if NPL data unavailable after cache expires |
| Network isolation | Four Docker networks; Envoy spans three (public, backend, policy); all other components span at most two |
| Credential direction is one-way | Secrets flow toward upstream tools; responses flow toward agent; the two never mix |
| Tenant isolation | Separate Vault per tenant; NPL policies scoped by organization claim |
| No Docker socket in gateway | Envoy and OPA have no Docker socket; only supergateway sidecars mount it for spawning STDIO tool containers |
| Policy propagation within 5s | Admin mutations in NPL are reflected in OPA within 5 seconds (cache TTL) |

---

## Technology Stack

| Component | Technology |
|-----------|------------|
| Edge Gateway | Envoy AI Gateway (`envoyproxy/envoy:v1.32-latest`), port 8000 |
| Policy Engine | OPA (`openpolicyagent/opa:latest-envoy`), port 9191 (gRPC) / 8181 (HTTP) |
| Policy State | NPL Engine (Noumena Platform), port 12000 |
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
           -> OPA Sidecar (ext_authz gRPC: Rego policy + cached NPL data)
              -> NPL Engine (http.send, cached 5s: ServiceRegistry, ToolPolicy, UserToolAccess)
           -> Supergateway Sidecar (Streamable HTTP -> STDIO MCP tool)

Credential injection:
  Sidecar startup -> Credential Proxy -> Vault -> env vars -> STDIO tool
```

- Envoy handles MCP protocol, JWT validation, SSE streaming, and routing
- OPA evaluates Rego policy with NPL data cached via `http.send()` (~0.1ms from cache)
- NPL is the source of truth for policy state (admin mutations via TUI/API)
- Supergateway sidecars wrap STDIO tools as uniform Streamable HTTP endpoints
- Credential injection happens at container startup, not per-request
- No governance service, no policy-sync service — just OPA + NPL
