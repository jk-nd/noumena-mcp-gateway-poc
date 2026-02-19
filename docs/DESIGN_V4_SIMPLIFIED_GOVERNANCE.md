# Design v4 — Simplified Three-Layer Governance

## Status

**Design** — Conversation captured 2026-02-19. Not yet implemented.

Supersedes the v3 architecture (security policy YAML, per-user grants, classifier DSL,
contextual route groups with six policy protocols, ~1000-line OPA Rego).

---

## Motivation

The v3 architecture works but is hard to configure and reason about. An admin wanting to
enable Gmail for the sales team must touch: service registration, tool enablement, per-user
grants, security policy YAML (verb inference, classifier DSL, priority rules), and contextual
route registration. The OPA Rego is ~1000 lines. The security policy YAML has its own DSL.
Documentation is perpetually out of date because the surface area is too large.

**Goal**: Three clean layers. Each layer answers one question. An admin can configure the
entire system by understanding three concepts.

---

## Architecture Overview

```
Agent request
    │
    ▼
┌─────────────────────────────────────────────┐
│  Envoy AI Gateway                           │
│  ┌────────────┐  ┌────────────┐             │
│  │ JWT AuthN  │→ │ OPA AuthZ  │→ upstream   │
│  └────────────┘  └─────┬──────┘             │
│                        │                    │
│              ┌─────────┴─────────┐          │
│              │  Layer 1: Catalog │          │
│              │  Layer 2: Access  │          │
│              │  Layer 3: NPL     │          │
│              └───────────────────┘          │
└─────────────────────────────────────────────┘
```

| Layer | Question | Who configures | Data lives in |
|-------|----------|----------------|---------------|
| **1. Catalog** | Is this service/tool available at all? | Admin | NPL (OPA bundle) |
| **2. Access Rules** | Is this caller allowed to use it? | Admin | NPL (OPA bundle) |
| **3. NPL Workflows** | Does this call require stateful governance? | NPL (runtime) | NPL (live evaluation) |

**Fail-closed at every layer.** If a tool is not in the catalog, deny. If no access rule
matches, deny. If a tool is tagged "gated" and NPL does not explicitly allow, deny.

---

## Layer 1: Service & Tool Catalog

**Question**: *Is this service and tool available in the gateway at all?*

The admin registers services and their tools. Each tool gets a **tag**:

| Tag | Meaning | OPA behavior |
|-----|---------|--------------|
| `open` | Stateless check — access rules sufficient | Allow if Layer 2 passes |
| `gated` | Stateful check — requires NPL evaluation | Allow only if Layer 2 passes AND NPL returns allow |

The tag is the only classification. No verb inference, no classifier DSL, no priority rules.
The admin decides. If in doubt, tag it `gated`.

### Catalog data structure (in OPA bundle)

```json
{
  "catalog": {
    "mock-calendar": {
      "enabled": true,
      "tools": {
        "list_events":  { "tag": "open" },
        "create_event": { "tag": "gated" },
        "read_inbox":   { "tag": "open" },
        "send_email":   { "tag": "gated" }
      }
    },
    "duckduckgo": {
      "enabled": true,
      "tools": {
        "search":       { "tag": "open" },
        "fetch_page":   { "tag": "open" }
      }
    },
    "github": {
      "enabled": true,
      "tools": {
        "list_repos":       { "tag": "open" },
        "create_issue":     { "tag": "gated" },
        "create_pull_request": { "tag": "gated" },
        "push_files":       { "tag": "gated" }
      }
    }
  }
}
```

### What "open" vs "gated" means

- **Open**: The tool call is allowed as soon as the caller passes Layer 2 access rules.
  No NPL call needed. Fast path. Use for read-only, low-risk, idempotent operations.

- **Gated**: The tool call requires NPL evaluation at runtime. NPL can implement any
  stateful workflow: immediate allow/deny based on rules, multi-party approval with
  obligations, rate limiting, time-based access windows, audit-and-allow, etc.
  Default is **deny** — NPL must explicitly allow.

Both open and gated tools get NPL representation (see Layer 3). The difference is whether
OPA calls NPL at request time.

---

## Layer 2: Access Rules

**Question**: *Is this caller allowed to use this service/tool?*

Access rules match on JWT claims. Rules can target groups of users (by claim patterns)
or individual users (by identity). The admin defines rules; OPA evaluates them from the
bundle.

### Rule structure

```json
{
  "access_rules": [
    {
      "id": "sales-calendar",
      "match": {
        "claims": { "organization": "acme", "department": "sales" }
      },
      "allow": {
        "services": ["mock-calendar"],
        "tools": ["*"]
      }
    },
    {
      "id": "engineering-all",
      "match": {
        "claims": { "organization": "acme", "department": "engineering" }
      },
      "allow": {
        "services": ["mock-calendar", "github", "duckduckgo"],
        "tools": ["*"]
      }
    },
    {
      "id": "jarvis-github-readonly",
      "match": {
        "identity": "jarvis@acme.com"
      },
      "allow": {
        "services": ["github"],
        "tools": ["list_repos", "search_code", "get_file_contents"]
      }
    },
    {
      "id": "compliance-override",
      "match": {
        "claims": { "role": "compliance_officer" }
      },
      "allow": {
        "services": ["*"],
        "tools": ["*"]
      }
    }
  ]
}
```

### Matching semantics

- **`claims`**: Every key-value pair must match the caller's JWT. This is an AND within
  one rule. Multiple rules are OR'd — if any rule matches, access is granted.
- **`identity`**: Matches a specific user by `email` (or `sub`) claim. Takes precedence
  alongside claim-based rules — both participate in the same OR evaluation.
- **`services`**: List of service names. `"*"` means all services.
- **`tools`**: List of tool names. `"*"` means all tools on the matched services.

An admin can combine group-level and individual-level rules freely. The system evaluates
all rules and allows if any rule matches.

### Deny rules (optional extension)

For completeness, a `deny` variant can be added later:

```json
{
  "id": "block-intern-github-write",
  "match": { "claims": { "role": "intern" } },
  "deny": {
    "services": ["github"],
    "tools": ["create_issue", "create_pull_request", "push_files"]
  }
}
```

Deny rules are evaluated after allow rules and take precedence. This is optional for v4.0
and can be added in a follow-up.

---

## Layer 3: NPL Governance (Stateful Workflows)

**Question**: *Does this specific tool call comply with business rules?*

Every service/tool registered in the catalog gets an NPL representation — even open ones.
This gives NPL full visibility into the tool landscape for auditing, analytics, and future
workflow attachment.

### NPL representation for all tools

```
┌─────────────────────────────────────┐
│  ServiceGovernance protocol         │
│  (one instance per service)         │
│                                     │
│  tools:                             │
│    list_events  → tag: open         │
│    create_event → tag: gated        │
│    read_inbox   → tag: open         │
│    send_email   → tag: gated        │
│                                     │
│  Gated tools:                       │
│    → workflow attached              │
│    → evaluate() called by OPA       │
│                                     │
│  Open tools:                        │
│    → tracked (audit, analytics)     │
│    → OPA does NOT call evaluate()   │
│    → workflow can be attached later  │
│      (changing tag to gated)        │
└─────────────────────────────────────┘
```

The key insight: **tagging a tool as "gated" activates the OPA→NPL call path for that tool**.
Changing a tag from `open` to `gated` instantly puts it under NPL governance. Changing from
`gated` to `open` removes the runtime NPL check (but keeps the NPL representation).

### Workflow model

Layer 3 is **generic**. NPL can implement any stateful workflow for gated tools. The
`evaluate()` action is the entry point that OPA calls. What happens inside NPL is up to the
workflow protocol.

Possible workflow patterns:

| Pattern | Description | Example |
|---------|-------------|---------|
| **Immediate allow/deny** | Stateless rules evaluated in NPL | Domain blocklists, business hours restrictions |
| **Multi-party approval** | Request stored, approver notified, agent confirms | Compliance officer approves `send_email` to external domains |
| **Rate limiting** | Counter-based throttling in NPL state | Max 10 `create_issue` calls per hour |
| **Time-windowed access** | Access granted for a time window after approval | "You have 1 hour to use `push_files`" |
| **Audit-and-allow** | Allow immediately but log for review | All `read_inbox` calls logged with full arguments |
| **Escalation chain** | Deny with escalation path | Manager approves, then VP approves for large amounts |

The `evaluate()` response tells OPA what to do:

```json
{ "decision": "allow" }
{ "decision": "deny", "reason": "Outside business hours" }
{ "decision": "pending", "requestId": "req-abc123", "message": "Awaiting compliance approval" }
```

- `allow` → OPA allows the request through to the upstream MCP server
- `deny` → OPA blocks with a reason
- `pending` → OPA blocks but signals to the agent that a workflow is in progress

---

## Example Workflow: Multi-Party Approval with Obligations

This section illustrates **one** possible Layer 3 workflow using NPL obligations. This is not
the only pattern — it is an example of what "gated" can mean.

### Scenario

> Jarvis (sales agent) calls `send_email` to `dave@external-vendor.com`. Company policy
> requires compliance officer approval for external emails.

### Three-party handshake

```
  Agent (Jarvis)          Gateway/NPL              Approver (Compliance)
       │                       │                           │
  1.   │── tools/call ────────▶│                           │
       │   send_email(...)     │                           │
       │                       │── evaluate() ────────────▶│
       │                       │   stores exact payload     │
       │◀── 202 pending ──────│                           │
       │   "awaiting approval" │                           │
       │                       │                           │
  2.   │                       │◀── approve(req-123) ─────│
       │                       │   obligation chain starts  │
       │                       │                           │
  3.   │◀── SSE notification ──│                           │
       │   "approved, confirm?"│                           │
       │                       │                           │
  4.   │── confirm(req-123) ──▶│                           │
       │                       │── execute stored payload ─▶ upstream MCP
       │◀── result ───────────│                           │
       │                       │                           │
  ALT: │── cancel(req-123) ───▶│                           │
       │                       │   payload discarded        │
```

### NPL obligation chain

```
Agent calls send_email
    │
    ▼
obligation[pCompliance] reviewApproval(requestId, deadline: 7 days)
    │
    ├── approve() → obligation[pGateway] notifyAgent(requestId, deadline: 1 hour)
    │                    │
    │                    └── SSE notification sent to agent
    │                        │
    │                        └── agent confirms → obligation[pGateway] executePayload(deadline: 5 min)
    │                                                │
    │                                                └── stored payload sent to upstream MCP
    │
    ├── deny(reason) → agent notified, payload discarded
    │
    └── deadline breach → auto-deny, payload discarded
```

Each obligation has a deadline. If the deadline passes without fulfillment, NPL triggers
breach handling (auto-deny, cleanup, notification). The agent always gets the last word —
even after approval, the agent must confirm before the gateway executes.

### Store-and-forward

The gateway stores the **exact request payload** in NPL when the pending request is created.
On confirmation, the gateway replays that exact payload to the upstream MCP server. This:

- Prevents the agent from modifying the payload between approval and execution
- Handles agent disconnection (the payload survives in NPL state)
- Provides a complete audit trail (what was requested, what was approved, what was executed)

---

## OPA Rego (Simplified)

The entire OPA policy reduces to ~60 lines:

```rego
package envoy.authz

import rego.v1

default allow := false

# ── JWT extraction ────────────────────────────────────────────────────
bearer_token := t if {
    auth_header := input.attributes.request.http.headers.authorization
    startswith(auth_header, "Bearer ")
    t := substring(auth_header, 7, -1)
}

jwt_payload := payload if {
    [_, payload, _] := io.jwt.decode(bearer_token)
}

user_id := jwt_payload.email

# ── Parse JSON-RPC body ──────────────────────────────────────────────
parsed_body := json.unmarshal(input.attributes.request.http.body)

is_tool_call if parsed_body.method == "tools/call"

qualified_name := parsed_body.params.name if is_tool_call
name_parts := split(qualified_name, ".")
service_name := name_parts[0]
tool_name := name_parts[1]

# ── Layer 1: Catalog check ───────────────────────────────────────────
service_enabled if data.catalog[service_name].enabled == true
tool_in_catalog if data.catalog[service_name].tools[tool_name]
tool_tag := data.catalog[service_name].tools[tool_name].tag

# ── Layer 2: Access rules ────────────────────────────────────────────
caller_authorized if {
    some rule in data.access_rules
    rule.allow
    access_matches(rule, service_name, tool_name)
}

access_matches(rule, svc, tool) if {
    # Claim-based match
    rule.match.claims
    every k, v in rule.match.claims { jwt_payload[k] == v }
    service_match(rule.allow.services, svc)
    tool_match(rule.allow.tools, tool)
}

access_matches(rule, svc, tool) if {
    # Identity-based match
    rule.match.identity == user_id
    service_match(rule.allow.services, svc)
    tool_match(rule.allow.tools, tool)
}

service_match(services, svc) if { "*" in services }
service_match(services, svc) if { svc in services }

tool_match(tools, tool) if { "*" in tools }
tool_match(tools, tool) if { tool in tools }

# ── Non-tool-call methods: allow if authenticated ────────────────────
allow if {
    bearer_token
    not is_tool_call
}

# ── Open tools: allow if catalog + access rules pass ─────────────────
allow if {
    is_tool_call
    service_enabled
    tool_in_catalog
    caller_authorized
    tool_tag == "open"
}

# ── Gated tools: allow only if NPL says so ───────────────────────────
allow if {
    is_tool_call
    service_enabled
    tool_in_catalog
    caller_authorized
    tool_tag == "gated"
    npl_allows
}

npl_allows if {
    resp := http.send({
        "method": "POST",
        "url": sprintf("%s/api/governance/%s/evaluate", [data.npl_url, service_name]),
        "headers": {
            "Authorization": sprintf("Bearer %s", [data.gateway_token]),
            "Content-Type": "application/json",
        },
        "body": {
            "toolName": tool_name,
            "callerIdentity": user_id,
            "callerClaims": jwt_payload,
            "arguments": parsed_body.params.arguments,
            "sessionId": input.attributes.request.http.headers["mcp-session-id"],
        },
        "timeout": "2s",
    })
    resp.status_code == 200
    resp.body.decision == "allow"
}
```

Compare: v3 is ~1000 lines with verb inference, classifier DSL, priority matching, security
policy evaluation, and six contextual policy protocol types. v4 is ~60 lines with two clear
code paths (open / gated).

---

## NPL Protocol Design

### ServiceGovernance (one instance per service)

```
protocol[pAdmin, pGateway] ServiceGovernance(serviceName: Text) {

    // Tool registry with tags
    var tools: Map<Text, ToolEntry>

    // Pending requests (for gated workflows)
    var pendingRequests: Map<Text, PendingRequest>

    // Workflow configuration
    var workflowConfig: WorkflowConfig

    // ── Admin actions ────────────────────────────────────────
    permission[pAdmin] registerTool(name, tag)
    permission[pAdmin] setTag(toolName, tag)        // open ↔ gated
    permission[pAdmin] configureWorkflow(config)

    // ── Gateway evaluation (called by OPA for gated tools) ──
    permission[pGateway] evaluate(toolName, callerIdentity, callerClaims, arguments, sessionId)
        returns EvaluationResult   // { decision: allow | deny | pending }

    // ── Workflow actions (depend on workflow type) ────────────
    // Approval workflow example:
    permission[pApprover] approve(requestId)
    permission[pApprover] deny(requestId, reason)
    permission[pAgent]    confirm(requestId)
    permission[pAgent]    cancel(requestId)
}
```

### GatewayStore (singleton, replaces PolicyStore)

```
protocol[pAdmin, pGateway] GatewayStore() {

    // Catalog: service → { enabled, tools: { name → tag } }
    var catalog: Map<Text, CatalogEntry>

    // Access rules
    var accessRules: List<AccessRule>

    // Emergency revocation
    var revokedSubjects: Set<Text>

    // ── Bundle export (one call) ──
    permission[pGateway] getBundleData() returns BundleData
}
```

The `GatewayStore` replaces `PolicyStore`. It holds the catalog, access rules, and
revocation list. The bundle server reads it in one call and serves it to OPA.

`ServiceGovernance` instances are **separate** from the store. Each service gets its own
protocol instance with its own state. This separation means:

- The bundle (catalog + access rules) is static data, served efficiently
- Governance workflows are live NPL state, evaluated at request time
- Adding a new workflow type does not change the bundle format or OPA policy

---

## OPA Bundle Format (v4)

```json
{
  "catalog": { ... },
  "access_rules": [ ... ],
  "revoked_subjects": ["compromised@acme.com"],
  "npl_url": "http://npl-engine:12000",
  "gateway_token": "<JWT for pGateway>",
  "_bundle_metadata": {
    "version": "4.0",
    "generated_at": "2026-02-19T10:00:00Z",
    "npl_state_hash": "abc123"
  }
}
```

The bundle is rebuilt whenever `GatewayStore` state changes (SSE push from NPL engine).
OPA polls the bundle server every 1-2 seconds. Zero network I/O during policy evaluation
for open tools. Gated tools make one synchronous HTTP call to NPL.

---

## Migration from v3

### What gets removed

- `SecurityPolicy` (the entire YAML DSL: verb inference, classifier, priority rules)
- Per-user `grants` map (replaced by access rules with claim matching)
- `ContextualRoute` / `RouteGroup` (replaced by ServiceGovernance instances)
- Six contextual policy protocols (ApprovalPolicy, RateLimitPolicy, ConstraintPolicy,
  PreconditionPolicy, FlowPolicy, IdentityPolicy) — replaced by generic workflows
  inside ServiceGovernance
- ~940 lines of OPA Rego

### What stays

- Envoy AI Gateway (JWT + ext_authz + MCP routing) — unchanged
- OPA sidecar (ext_authz gRPC) — unchanged, just simpler policy
- NPL Engine — unchanged infrastructure, new protocol design
- Bundle Server — simplified (reads GatewayStore instead of PolicyStore)
- Keycloak — unchanged
- MCP Aggregator — unchanged

### Migration steps

1. Deploy `GatewayStore` + `ServiceGovernance` protocols alongside `PolicyStore`
2. Migrate catalog data (services + tools) to new format, adding tags
3. Convert per-user grants to access rules
4. Switch OPA to v4 Rego
5. Remove `PolicyStore` and old policy protocols
6. Remove security policy YAML infrastructure

---

## Configuration Example (End-to-End)

### Step 1: Admin registers services and tools

```bash
# Register mock-calendar service
POST /api/gateway-store/registerService
{ "serviceName": "mock-calendar" }

# Register tools with tags
POST /api/gateway-store/registerTool
{ "serviceName": "mock-calendar", "toolName": "list_events", "tag": "open" }

POST /api/gateway-store/registerTool
{ "serviceName": "mock-calendar", "toolName": "send_email", "tag": "gated" }
```

### Step 2: Admin creates access rules

```bash
# Sales team can use calendar
POST /api/gateway-store/addAccessRule
{
  "id": "sales-calendar",
  "match": { "claims": { "organization": "acme", "department": "sales" } },
  "allow": { "services": ["mock-calendar"], "tools": ["*"] }
}

# Jarvis specifically gets read-only GitHub
POST /api/gateway-store/addAccessRule
{
  "id": "jarvis-github",
  "match": { "identity": "jarvis@acme.com" },
  "allow": { "services": ["github"], "tools": ["list_repos", "search_code"] }
}
```

### Step 3: Admin configures workflow for gated tools

```bash
# Configure approval workflow for send_email
POST /api/governance/mock-calendar/configureWorkflow
{
  "pattern": "approval",
  "approverClaims": { "role": "compliance_officer" },
  "deadline": "7d",
  "defaultDecision": "deny"
}
```

### Result

- Jarvis calls `list_events` → Layer 1 (catalog) pass → Layer 2 (access rule) pass → tag is `open` → **allowed** (no NPL call)
- Jarvis calls `send_email` → Layer 1 pass → Layer 2 pass → tag is `gated` → OPA calls NPL → NPL starts approval workflow → **pending** → compliance officer approves → Jarvis confirms → **executed**
- Random user with no matching access rule calls anything → Layer 2 → **denied**
- Jarvis calls `push_files` on GitHub → Layer 2 (only has `list_repos`, `search_code`) → **denied**

---

## Open Questions

1. **Bundle refresh for access rule changes**: Access rules live in the bundle. Changes
   propagate via SSE → bundle rebuild → OPA poll (1-2s). Is this fast enough for
   emergency access revocation? (Emergency subject revocation already exists as a fast path.)

2. **Workflow configuration DSL**: How much should workflow configuration be structured
   (typed fields) vs freeform (JSON config blob)? Typed is safer but less flexible.

3. **Agent notification transport**: The three-party handshake requires notifying the agent.
   MCP SSE (`GET /mcp`) is the natural channel. Need to confirm MCP Inspector and SDK
   clients handle server-initiated notifications correctly.

4. **Gated tool timeout**: When OPA calls NPL for a gated tool and the workflow returns
   `pending`, how long should the HTTP response to the agent be held? Current thinking:
   return immediately with 202/pending status and let the agent poll or listen on SSE.

5. **Audit trail**: Should open tool calls also be logged through NPL (not evaluated, just
   recorded)? This would give complete visibility but adds latency to the fast path.
