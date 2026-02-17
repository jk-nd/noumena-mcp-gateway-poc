# System Architecture Reference

This document describes the MCP Gateway's service topology, network isolation design, and data flow patterns.

> This is a reference companion to the [How-To Guide](HOWTO.md).

---

## Service Topology

```
                        ┌─────────────────────────────────────────────────┐
                        │                  public-net                     │
                        │                                                 │
  Agents/Users ────────►│  ┌──────────────┐     ┌────────────┐           │
                        │  │ Envoy Gateway │     │  Keycloak  │           │
                        │  │  :8000 :9901  │     │   :11000   │           │
                        │  └──────┬────────┘     └────────────┘           │
                        │         │                                       │
                        │  ┌──────┴──────┐                                │
                        │  │  Inspector  │                                │
                        │  │    :8080    │                                │
                        │  └─────────────┘                                │
                        └─────────┬───────────────────────────────────────┘
                                  │ ext_authz (gRPC)
                        ┌─────────▼───────────────────────────────────────┐
                        │                 policy-net                       │
                        │                                                  │
                        │  ┌──────────┐  ┌──────────────┐  ┌───────────┐  │
                        │  │   OPA    │  │ Bundle Server │  │NPL Engine │  │
                        │  │:9191:8181│  │    :8282      │  │  :12000   │  │
                        │  └──────────┘  └──────────────┘  └───────────┘  │
                        │                                                  │
                        │  ┌────────────────┐  ┌──────────────────┐       │
                        │  │Credential Proxy│  │ NPL CORS Proxy   │       │
                        │  │     :9002      │  │     :12001       │       │
                        │  └────────────────┘  └──────────────────┘       │
                        └─────────┬───────────────────────────────────────┘
                                  │ route (if allowed)
                        ┌─────────▼───────────────────────────────────────┐
                        │                backend-net                       │
                        │                                                  │
                        │  ┌────────────────┐                              │
                        │  │ MCP Aggregator │                              │
                        │  │     :8000      │                              │
                        │  └───────┬────────┘                              │
                        │          │                                       │
                        │  ┌───────┼─────────────────┐                    │
                        │  │       │                  │                    │
                        │  ▼       ▼                  ▼                   │
                        │ DuckDuckGo  Mock Calendar  GitHub MCP           │
                        │  MCP :8000   MCP :8000     :8000                │
                        └─────────────────────────────────────────────────┘

                        ┌─────────────────────────────────────────────────┐
                        │                secrets-net                       │
                        │                                                  │
                        │  ┌──────────┐      ┌────────────────┐           │
                        │  │  Vault   │◄─────│Credential Proxy│           │
                        │  │  :8200   │      │     :9002      │           │
                        │  └──────────┘      └────────────────┘           │
                        └─────────────────────────────────────────────────┘
```

---

## Component Table

| Service | Language | Port(s) | Network(s) | Purpose |
|---------|----------|---------|------------|---------|
| Envoy Gateway | C++ | 8000 (MCP), 9901 (admin) | public, backend, policy | MCP protocol termination, JWT validation, ext_authz routing |
| Keycloak | Java | 11000 (HTTP), 9000 (health) | public | OIDC identity provider (user auth, JWT issuance) |
| OPA Sidecar | Go | 9191 (gRPC), 8181 (HTTP) | policy | Rego policy evaluation, Envoy ext_authz |
| Bundle Server | Python | 8282 | policy, public | SSE-driven OPA bundle builder and server |
| NPL Engine | Kotlin | 12000 (API), 12400 (debug) | policy, public | Policy state manager (PolicyStore, ApprovalPolicy, RateLimitPolicy, ConstraintPolicy, PreconditionPolicy, FlowPolicy, IdentityPolicy) |
| Engine DB | PostgreSQL | 5432 | policy | NPL Engine persistence |
| MCP Aggregator | Node.js | 8000 | backend | Multi-backend MCP request routing |
| DuckDuckGo MCP | Node.js | 8000 | backend | Supergateway sidecar for DuckDuckGo search |
| GitHub MCP | Node.js | 8000 | backend, secrets | Supergateway sidecar for GitHub (needs credentials) |
| Mock Calendar MCP | Node.js | 8000 | backend | Streamable HTTP MCP server for testing |
| Credential Proxy | Python | 9002 | secrets, policy | Vault-to-env credential injection |
| Vault | Go | 8200 | secrets | Secret storage (dev mode) |
| NPL Inspector | React | 8080 | policy, public | NPL state inspection UI |
| NPL CORS Proxy | Nginx | 12001 | policy, public | CORS proxy for Inspector browser access to NPL |
| Keycloak DB | PostgreSQL | 5432 | — | Keycloak persistence |

---

## Data Flow: Request Path

A tool call flows through the following services:

```
Agent                 Envoy              OPA              NPL Engine        MCP Aggregator     Backend
  │                    │                  │                    │                  │                │
  │ POST /mcp          │                  │                    │                  │                │
  │ (Bearer JWT)       │                  │                    │                  │                │
  │───────────────────►│                  │                    │                  │                │
  │                    │ Validate JWT     │                    │                  │                │
  │                    │ (Keycloak JWKS)  │                    │                  │                │
  │                    │                  │                    │                  │                │
  │                    │ ext_authz(gRPC)  │                    │                  │                │
  │                    │─────────────────►│                    │                  │                │
  │                    │                  │ Layer 1:           │                  │                │
  │                    │                  │ catalog+grants     │                  │                │
  │                    │                  │ +security policy   │                  │                │
  │                    │                  │ (~1ms, in-memory)  │                  │                │
  │                    │                  │                    │                  │                │
  │                    │                  │ [if contextual     │                  │                │
  │                    │                  │  route exists]     │                  │                │
  │                    │                  │ Layer 2:           │                  │                │
  │                    │                  │ http.send ────────►│                  │                │
  │                    │                  │   evaluate()       │                  │                │
  │                    │                  │ ◄─────────────────│                  │                │
  │                    │                  │ (~60ms, network)   │                  │                │
  │                    │                  │                    │                  │                │
  │                    │ allow/deny       │                    │                  │                │
  │                    │◄─────────────────│                    │                  │                │
  │                    │                  │                    │                  │                │
  │                    │ [if allowed]     │                    │                  │                │
  │                    │ route to backend │                    │                  │                │
  │                    │────────────────────────────────────────────────────────►│                │
  │                    │                  │                    │                  │ route to svc   │
  │                    │                  │                    │                  │───────────────►│
  │                    │                  │                    │                  │◄───────────────│
  │                    │◄────────────────────────────────────────────────────────│                │
  │◄───────────────────│                  │                    │                  │                │
  │ JSON-RPC response  │                  │                    │                  │                │
```

### Layer 1 vs Layer 2

- **Layer 1** (~1ms): OPA evaluates catalog, grants, and security policy entirely from in-memory bundle data. No network I/O. Handles ~99% of requests.
- **Layer 2** (~60ms): When a contextual route exists (e.g., approvals, rate limiting), OPA calls the NPL Engine via `http.send()`. Handles ~1% of requests.

---

## Data Flow: Bundle Propagation

The OPA bundle is rebuilt whenever policy state changes in the NPL Engine:

```
PolicyStore (NPL)                Bundle Server                    OPA
      │                               │                           │
      │ SSE push notification          │                           │
      │ (state change event)           │                           │
      │──────────────────────────────►│                           │
      │                               │ debounce (100ms)          │
      │                               │                           │
      │ GET PolicyStore/              │                           │
      │◄──────────────────────────────│                           │
      │ getPolicyData()               │                           │
      │──────────────────────────────►│                           │
      │ {catalog, grants, routes,     │                           │
      │  security_policy, token}      │                           │
      │◄──────────────────────────────│                           │
      │                               │ build bundle              │
      │                               │ (data.json + manifest)    │
      │                               │ → tar.gz                  │
      │                               │                           │
      │                               │ OPA polls (1-2s interval) │
      │                               │◄──────────────────────────│
      │                               │ If-None-Match: {etag}     │
      │                               │                           │
      │                               │ 200 + bundle.tar.gz       │
      │                               │──────────────────────────►│
      │                               │                           │ load into memory
      │                               │                           │
```

The bundle server also runs a reconciliation loop (every 30s by default) as a fallback in case SSE events are missed.

---

## Data Flow: Credential Injection

```
Admin                 Vault              Credential Proxy        Supergateway        MCP Tool
  │                    │                      │                      │                  │
  │ Store secret       │                      │                      │                  │
  │───────────────────►│                      │                      │                  │
  │                    │                      │                      │                  │
  │                    │                      │                      │                  │
  │                    │  [on container start] │                      │                  │
  │                    │                      │◄─────────────────────│                  │
  │                    │                      │  GET /credentials    │                  │
  │                    │                      │  (service, user)     │                  │
  │                    │                      │                      │                  │
  │                    │◄─────────────────────│                      │                  │
  │                    │  GET vault_path      │                      │                  │
  │                    │──────────────────────►│                      │                  │
  │                    │  {token: "ghp_..."}  │                      │                  │
  │                    │                      │──────────────────────►│                  │
  │                    │                      │  {GITHUB_TOKEN:      │                  │
  │                    │                      │   "ghp_..."}         │                  │
  │                    │                      │                      │ export env vars  │
  │                    │                      │                      │ spawn process    │
  │                    │                      │                      │─────────────────►│
  │                    │                      │                      │  (GITHUB_TOKEN   │
  │                    │                      │                      │   available)     │
```

---

## Network Isolation

The four Docker networks provide defense-in-depth:

| Network | Purpose | Accessible from outside? |
|---------|---------|------------------------|
| **public-net** | Agent-facing services. Only Envoy, Keycloak, and Inspector are reachable from external clients. | Yes (ports 8000, 11000, 8080) |
| **policy-net** | Policy evaluation. OPA, Bundle Server, NPL Engine, and Credential Proxy communicate here. Not reachable from agents. | No (internal only) |
| **backend-net** | Tool execution. MCP servers run here, isolated from policy infrastructure. Only the Aggregator and Envoy can reach them. | No (internal only) |
| **secrets-net** | Credential storage. Only Vault and the Credential Proxy live here. Minimal attack surface. | No (except Vault dev UI at :8200) |

**Key isolation properties:**

- Agents never talk directly to OPA, NPL, or MCP backends — Envoy is the single entry point
- MCP backends cannot reach the policy layer — a compromised tool server can't modify policies
- Vault is only accessible from the Credential Proxy — secrets never traverse the policy or backend networks
- The Credential Proxy bridges secrets-net and policy-net but has no route to public-net

---

## OPA Configuration

OPA is configured to poll the bundle server for policy data updates:

```yaml
services:
  bundle-server:
    url: http://bundle-server:8282

bundles:
  mcp-policy-data:
    service: bundle-server
    resource: /bundles/mcp/data.tar.gz
    polling:
      min_delay_seconds: 1
      max_delay_seconds: 2

plugins:
  envoy_ext_authz_grpc:
    addr: ":9191"
    path: envoy/authz/result

decision_logs:
  console: true
```

The `envoy_ext_authz_grpc` plugin exposes a gRPC endpoint on port 9191 that Envoy calls for every request. The `path` maps to the Rego rule at `envoy.authz.result` in `policies/mcp_authz.rego`.

---

## NPL Party-to-Role Mappings

JWT claims are mapped to NPL protocol parties via `npl/src/main/yaml/rules.yml`:

| NPL Party | JWT Role Claim | Purpose |
|-----------|---------------|---------|
| `pAdmin` | `admin` | Organization administrator — manages policies, views all state |
| `pGateway` | `gateway` | Gateway service account — runtime policy enforcement |
| `pApprover` | `admin` | Human approver — approves/denies pending requests (ApprovalPolicy only) |

> **Note:** `pAdmin` and `pGateway` are shared across all contextual routing protocols (ApprovalPolicy, RateLimitPolicy, ConstraintPolicy, PreconditionPolicy, FlowPolicy, IdentityPolicy). `pApprover` is specific to ApprovalPolicy.

> Tool users (humans and AI agents) are NOT NPL parties. They are governed by grants inside the PolicyStore, identified by `subjectId`.

---

**See also:**
- [How-To Guide](HOWTO.md) — step-by-step configuration walkthrough
- [OPA Policy Internals](OPA_POLICY_INTERNALS.md) — Rego policy details and bundle structure
- [Approval Workflow Deep Dive](APPROVAL_WORKFLOW.md) — approval state machine and store-and-forward
