> **DEPRECATED**: This document describes the v3 policy architecture which has been replaced by v4 Three-Layer Governance. See [DESIGN_V4_SIMPLIFIED_GOVERNANCE.md](DESIGN_V4_SIMPLIFIED_GOVERNANCE.md) for the current architecture.

# Policy Architecture v3 — NPL + OPA Redesign

## Status

**Deprecated** — Replaced by v4 Three-Layer Governance. Originally implemented (Phases 1-3) 2026-02-14 through 2026-02-16.

Phases 1-3 are complete: bundle server with SSE push + PolicyStore singleton replacing the old 3-layer model + six contextual evaluation protocols (ApprovalPolicy, RateLimitPolicy, ConstraintPolicy, PreconditionPolicy, FlowPolicy, IdentityPolicy) with route group AND/OR composition. Phase 4 (decision audit trail) is not yet started.

**Implementation notes**: The original design proposed two protocols (ToolCatalog + AccessGrant). The actual implementation consolidated further into a single **PolicyStore** singleton that holds catalog, grants, revoked subjects, and contextual routing in one protocol. Bundle server reads everything in 2 HTTP calls (list singleton + `getPolicyData()`), constant time regardless of user/service count.

---

## Current Architecture (v2)

### How it works today

```
Agent → Envoy (JWT authn) → OPA ext_authz → upstream MCP server
                               │
                               ├── ServiceRegistry:  "Is mock-calendar enabled?"
                               ├── ToolPolicy:       "Is list_events enabled?"
                               └── UserToolAccess:   "Can jarvis@acme.com use it?"
```

OPA fetches NPL state via `http.send()` with a 5-second response cache. Three NPL protocols, three network calls (when cache misses), one allow/deny decision back to Envoy.

### The three NPL protocols

| Protocol | Path | Purpose | Party model |
|----------|------|---------|-------------|
| ServiceRegistry | `/npl/registry/ServiceRegistry/` | Org-level service enable/disable | pAdmin writes, pGateway reads |
| ToolPolicy | `/npl/services/ToolPolicy/` | Per-service tool enable/disable | pAdmin writes, pGateway reads |
| UserToolAccess | `/npl/users/UserToolAccess/` | Per-user tool grants | pAdmin writes, pGateway reads |

### Measured performance (v2, before redesign)

| Path | Steady state (cached) | Cold (cache miss) |
|------|----------------------|-------------------|
| Envoy JWT validation only | 1.5 ms | 1.5 ms |
| OPA ext_authz deny | 11 ms | 110 ms |
| Full tool call (Envoy + OPA + backend) | 15-20 ms | 110-150 ms |
| NPL direct action call | 44-80 ms | — |

The 110ms cold call happened every 5 seconds when the OPA cache expired.

### Measured performance (v3, after redesign — bundle-based)

| Path | Avg | Min | Max |
|------|-----|-----|-----|
| E2E tool call ALLOW (Envoy → OPA → backend) | 11 ms | 7 ms | 23 ms |
| E2E tool call DENY (Envoy → OPA short-circuit) | 7 ms | 4 ms | 13 ms |
| E2E no-auth (Envoy JWT reject) | 2 ms | 1 ms | 4 ms |
| OPA direct ALLOW (no Envoy) | 11 ms | 7 ms | 25 ms |
| OPA direct DENY (no Envoy) | 6 ms | 4 ms | 12 ms |
| NPL getPolicyData | 10 ms | 7 ms | 15 ms |
| Bundle download | 1 ms | 1 ms | 2 ms |

**No cold calls.** OPA always has data in memory. Bundle rebuilds are SSE-triggered (near-instant propagation).

### What's wrong with the current model

1. **Three protocols answer one question.** ServiceRegistry, ToolPolicy, and UserToolAccess are three granularities of "can subject X use tool Y on service Z?" In standard RBAC/ABAC, this is one evaluation.

2. **Shaped by TUI, not by access control theory.** The decomposition mirrors TUI screens (manage services → manage tools → manage users) rather than access control primitives.

3. **No contextual evaluation.** The model is purely static — grants are binary on/off. No support for session-aware constraints, request argument inspection, or approval workflows.

4. **OPA polls NPL on a timer.** The `http.send` + `force_cache_duration_seconds` pattern means policy changes take up to 5 seconds to propagate. There's no push-based invalidation and no audit trail of OPA decisions.

---

## Architecture (v3) — Implemented

### Design principles

1. **Two clean layers** — static access (fast, cached) and contextual evaluation (stateful, on-demand)
2. **NPL is the single source of truth** — all policy configuration and stateful evaluation lives in NPL
3. **OPA is the enforcement engine** — reads policy data from NPL bundles, calls NPL for contextual checks
4. **Bundle-based data loading** — decouple data sync from request evaluation; zero network I/O in the fast path
5. **Plugin architecture for contextual policies** — common interface, custom implementations per use case
6. **Refactor NPL freely** — clean abstractions take priority over backward compatibility

### The two layers

```
┌─────────────────────────────────────────────────────────────────┐
│  Layer 1: Static Access Control (OPA bundles, in-memory)        │
│                                                                  │
│  "Can this user use this tool on this service?"                  │
│  Pure data lookup. No network I/O. ~1-2ms.                       │
│                                                                  │
│  Data source: NPL → Bundle Server → OPA                         │
│  Propagation: near-instant (bundle long-poll on NPL mutations)   │
└─────────────────────────────────────────────────────────────────┘
                              │
                    passes? ──┤── no → deny
                              │
                             yes
                              │
                    contextual rule? ──── no → allow (fast path, ~1-2ms)
                              │
                             yes
                              │
┌─────────────────────────────────────────────────────────────────┐
│  Layer 2: Contextual Evaluation (NPL sync call, stateful)        │
│                                                                  │
│  "Given the session history, request arguments, and business     │
│   rules, should this specific call proceed?"                     │
│                                                                  │
│  OPA calls NPL evaluate() → allow / deny / pending              │
│  ~60ms per call. Only fires when a contextual rule matches.      │
└─────────────────────────────────────────────────────────────────┘
```

99% of requests resolve in Layer 1 (pure memory, ~1-2ms). Layer 2 only fires for tool calls that match a contextual rule.

---

## Layer 1: Static Access Control (Implemented)

### Original design: 2 protocols → Actual implementation: 1 singleton

The original design below proposed two protocols (ToolCatalog + AccessGrant). The actual implementation went further and consolidated into a **single PolicyStore singleton** (see `npl/src/main/npl-1.0/store/policy_store.npl`). The PolicyStore holds catalog, grants, revoked subjects, and contextual routing in one protocol with 2 parties (pAdmin, pGateway). The bundle shape (catalog/grants/contextual_routing) is the same as proposed.

#### ToolCatalog (original design — merged into PolicyStore)

Defines what exists and what's enabled. This is the **resource side** of access control.

```npl
@api
protocol[pAdmin, pGateway] ToolCatalog() {

    struct ServiceEntry {
        serviceName: Text,
        enabled: Boolean,
        enabledTools: Set<Text>,
        metadata: Map<Text, Text>       // e.g., {"dataClassification": "PII"}
    };

    private var services: List<ServiceEntry> = listOf();

    // Admin manages the catalog
    action[pAdmin] registerService(serviceName: Text);
    action[pAdmin] enableService(serviceName: Text);
    action[pAdmin] disableService(serviceName: Text);
    action[pAdmin] enableTool(serviceName: Text, toolName: Text);
    action[pAdmin] disableTool(serviceName: Text, toolName: Text);
    action[pAdmin] setMetadata(serviceName: Text, key: Text, value: Text);

    // Gateway/OPA reads the catalog
    action[pGateway] getCatalog(): List<ServiceEntry>;
    action[pGateway] isToolEnabled(serviceName: Text, toolName: Text): Boolean;
}
```

One protocol, one instance, one bundle endpoint. Combines the org-level enable/disable (ServiceRegistry) with per-service tool management (ToolPolicy) because they're the same administrative concern: "what tools are available in this organization?"

#### AccessGrant (original design — merged into PolicyStore)

Defines who can use what. This is the **subject-resource binding**.

```npl
@api
protocol[pAdmin, pGateway] AccessGrant(
    var subjectId: Text              // email, e.g., "jarvis@acme.com"
) {

    struct GrantEntry {
        serviceName: Text,
        allowedTools: Set<Text>      // specific tools or ["*"] for wildcard
    };

    private var grants: List<GrantEntry> = listOf();

    // Admin manages grants
    action[pAdmin] grant(serviceName: Text, toolName: Text);
    action[pAdmin] grantAll(serviceName: Text);           // wildcard *
    action[pAdmin] revoke(serviceName: Text, toolName: Text);
    action[pAdmin] revokeService(serviceName: Text);
    action[pAdmin] revokeAll();                            // emergency kill

    // Gateway/OPA reads grants
    action[pGateway] hasAccess(serviceName: Text, toolName: Text): Boolean;
    action[pGateway] getGrants(): List<GrantEntry>;
}
```

Same as today's UserToolAccess but with a clearer name that reflects its role in access control theory.

### Bundle structure

A bundle server (endpoint on NPL or a sidecar) packages both protocols into a single JSON payload:

```json
{
    "catalog": {
        "mock-calendar": {
            "enabled": true,
            "enabledTools": ["list_events", "create_event"],
            "metadata": {}
        },
        "duckduckgo": {
            "enabled": true,
            "enabledTools": ["search", "fetch_content"],
            "metadata": {}
        },
        "slack": {
            "enabled": true,
            "enabledTools": ["send_message", "list_channels"],
            "metadata": {"dataClassification": "external"}
        }
    },
    "grants": {
        "jarvis@acme.com": [
            {"serviceName": "mock-calendar", "allowedTools": ["*"]},
            {"serviceName": "duckduckgo", "allowedTools": ["search"]}
        ],
        "alice@acme.com": []
    },
    "contextual_routing": {
        ...
    }
}
```

### OPA Rego (Layer 1)

With bundle data loaded into memory, the Rego becomes pure data lookups — no `http.send`:

```rego
package mcp.authz

import rego.v1

default allow := false

# Extract user identity from JWT (email > preferred_username > sub)
user_id := jwt_payload.email

# Layer 1: static access check (all in-memory, ~1ms)
tool_enabled if {
    data.catalog[service_name].enabled
    data.catalog[service_name].enabledTools[_] == tool_name
}

user_granted if {
    some grant in data.grants[user_id]
    grant.serviceName == service_name
    grant.allowedTools[_] == "*"
}

user_granted if {
    some grant in data.grants[user_id]
    grant.serviceName == service_name
    grant.allowedTools[_] == tool_name
}

static_allow if {
    tool_enabled
    user_granted
}
```

### OPA bundle configuration

Replace the current `http.send` polling with OPA's native bundle mechanism:

```yaml
services:
  npl-bundles:
    url: http://npl-engine:12000

bundles:
  policy-data:
    service: npl-bundles
    resource: /opa/bundle           # New NPL endpoint
    polling:
      min_delay_seconds: 1
      max_delay_seconds: 1          # Or use long-polling for instant propagation
```

OPA loads data in the background. Request evaluation is pure memory — zero network I/O. No more 110ms cold calls.

---

## Layer 2: Contextual Evaluation

### The problem

Static access control answers "can this user use this tool?" — yes or no. But some decisions depend on context:

| Scenario | Why static isn't enough |
|----------|------------------------|
| Agent accessed PII via tool A, now wants to post to Slack | Session history matters — data flow constraints |
| Agent calls `create_order` with amount $150k | Request arguments matter — threshold-based approval |
| Agent calls `transfer_funds` at 3am from unusual location | Environment matters — anomaly detection |
| Team has spent $800k of $1M monthly budget | Aggregate state matters — budget tracking |
| Order over $100k needs manager approval before execution | Human-in-the-loop — approval workflows |

These all require **stateful evaluation** — the policy needs to know what happened before, inspect the request content, and potentially trigger workflows.

### Design: NPL protocols as policy plugins

Each contextual policy is its own NPL protocol with its own state machine and logic. What's generic is the **interface**, not the implementation.

#### Common interface

Every contextual policy protocol exposes a single entry point that OPA calls:

```npl
// The contract between OPA and any contextual policy
action[pGateway] evaluate(
    toolName: Text,
    callerIdentity: Text,
    sessionId: Text,
    arguments: Text          // raw JSON of tool call arguments
): Text;                     // "allow" | "deny" | "pending:<id>"
```

OPA doesn't know or care what's behind this interface. It calls `evaluate()` and gets back a decision.

#### Example: PII session guard

Tracks data categories an agent has been exposed to in a session. Blocks cross-service data leaks.

```npl
@api
protocol[pAdmin, pGateway] PiiGuardPolicy() {

    struct ExclusionRule {
        ifExposedTo: Text,          // data category, e.g., "PII"
        thenBlockService: Text      // service to block, e.g., "slack"
    };

    struct SessionTaint {
        sessionId: Text,
        exposedCategories: Set<Text>
    };

    private var rules: List<ExclusionRule> = listOf();
    private var sessions: Map<Text, Set<Text>> = mapOf();  // sessionId → categories

    // Admin configures exclusion rules
    action[pAdmin] addRule(ifExposedTo: Text, thenBlockService: Text);
    action[pAdmin] removeRule(ifExposedTo: Text, thenBlockService: Text);

    // Gateway calls after each successful tool call to record exposure
    action[pGateway] recordExposure(sessionId: Text, dataCategories: Set<Text>);

    // OPA calls before routing a tool call
    action[pGateway] evaluate(toolName: Text, callerIdentity: Text,
                               sessionId: Text, arguments: Text): Text {
        // Check: has this session been exposed to a category
        // that blocks the target service?
        // If so → "deny"
        // Otherwise → "allow"
    };
}
```

#### Example: Approval workflow

Blocks high-value operations until a human approves.

```npl
@api
protocol[pAdmin, pGateway, pApprover] ApprovalPolicy(
    var policyServiceName: Text
) {
    initial state active;

    struct ApprovalRule {
        toolName: Text,
        conditionField: Text,       // JSON path in arguments
        conditionOp: Text,          // "gt", "lt", "eq"
        conditionValue: Text,       // threshold
        approverRole: Text          // who can approve
    };

    struct PendingApproval {
        approvalId: Text,
        requestor: Text,
        toolName: Text,
        arguments: Text,
        status: Text                // "pending" | "approved" | "denied"
    };

    private var rules: List<ApprovalRule> = listOf();
    private var approvals: List<PendingApproval> = listOf();

    // Admin configures approval rules
    action[pAdmin] addRule(rule: ApprovalRule);
    action[pAdmin] removeRule(toolName: Text);

    // OPA calls before routing
    action[pGateway] evaluate(toolName: Text, callerIdentity: Text,
                               sessionId: Text, arguments: Text): Text {
        // Parse arguments, check rules
        // No matching rule → "allow"
        // Rule matches, pending approval exists and approved → "allow"
        // Rule matches, pending approval exists and denied → "deny"
        // Rule matches, no pending approval → create one, return "pending:<id>"
    };

    // Human approver acts on pending approvals
    action[pApprover] approve(approvalId: Text);
    action[pApprover] deny(approvalId: Text, reason: Text);

    // Query pending approvals (for TUI/dashboard)
    action[pAdmin] getPendingApprovals(): List<PendingApproval>;
}
```

#### Example: Budget tracking

Enforces aggregate spending limits per team/department.

```npl
@api
protocol[pAdmin, pGateway] BudgetPolicy(
    var teamId: Text
) {
    private var monthlyBudget: Number = 0;
    private var currentSpend: Number = 0;
    private var periodStart: Text = "";

    action[pAdmin] setBudget(amount: Number);
    action[pAdmin] resetPeriod();

    action[pGateway] evaluate(toolName: Text, callerIdentity: Text,
                               sessionId: Text, arguments: Text): Text {
        // Parse cost/amount from arguments
        // If currentSpend + amount > monthlyBudget → "deny"
        // Otherwise → "allow"
    };

    action[pGateway] recordSpend(amount: Number);
}
```

### Contextual routing table

The bundle includes a routing table that tells OPA which contextual policy to call for which tools:

```json
{
    "contextual_routing": {
        "concord": {
            "create_order": {
                "protocol": "ApprovalPolicy",
                "instanceId": "abc-123",
                "endpoint": "/npl/policies/ApprovalPolicy/abc-123/evaluate"
            }
        },
        "slack": {
            "*": {
                "protocol": "PiiGuardPolicy",
                "instanceId": "def-456",
                "endpoint": "/npl/policies/PiiGuardPolicy/def-456/evaluate"
            }
        },
        "procurement": {
            "*": {
                "protocol": "BudgetPolicy",
                "instanceId": "ghi-789",
                "endpoint": "/npl/policies/BudgetPolicy/ghi-789/evaluate"
            }
        }
    }
}
```

The routing table itself comes from NPL — admins configure it through the TUI, it flows into the bundle, OPA picks it up. No Rego changes needed when new policies are added.

### OPA Rego (Layer 2)

```rego
# Check if a contextual rule exists for this tool
contextual_route := data.contextual_routing[service_name][tool_name] if {
    data.contextual_routing[service_name][tool_name]
}
contextual_route := data.contextual_routing[service_name]["*"] if {
    not data.contextual_routing[service_name][tool_name]
    data.contextual_routing[service_name]["*"]
}

# Fast path: static check passes, no contextual rule
allow if {
    static_allow
    not contextual_route
}

# Fast path: static check passes, contextual rule exists but evaluates to allow
allow if {
    static_allow
    contextual_route
    npl_eval := http.send({
        "method": "POST",
        "url": sprintf("%s%s", [npl_url, contextual_route.endpoint]),
        "headers": {"Authorization": npl_auth_header, "Content-Type": "application/json"},
        "raw_body": json.marshal({
            "toolName": tool_name,
            "callerIdentity": user_id,
            "sessionId": session_id,
            "arguments": json.marshal(tool_params)
        })
    })
    npl_eval.body == "allow"
}
```

The Rego is a **generic router**. It reads the routing table from the bundle, calls the NPL endpoint, and returns the result. New contextual policy types require zero Rego changes.

---

## OPA Integration: From Polling to Bundles (Implemented)

### Previous: `http.send` with response cache

```
Every request:
  OPA → http.send GET /npl/.../ServiceRegistry/    (cached 5s)
  OPA → http.send GET /npl/.../ToolPolicy/         (cached 5s)
  OPA → http.send GET /npl/.../UserToolAccess/      (cached 5s)
  OPA → http.send POST .../getAccessList            (cached 5s)
```

Problems: network I/O during evaluation, 5s staleness window, 110ms cold-call penalty.

### Implemented: OPA bundle API

```
Background (decoupled from requests):
  OPA ←poll/long-poll→ Bundle Server ←reads→ NPL state
  Refresh: near-instant on mutation, or 1s poll

Every request:
  OPA evaluates against in-memory data (zero network I/O)
  Layer 1: ~1-2ms
  Layer 2 (if triggered): one http.send to NPL evaluate() (~60ms)
```

### Bundle server (Implemented)

A Python HTTP service (`bundle-server/server.py`) that:

1. Subscribes to NPL's SSE event stream for real-time mutation notifications
2. On SSE event (or startup): calls `PolicyStore.getPolicyData()` (2 HTTP calls total)
3. Builds an OPA bundle (tar.gz with `data.json` + `.manifest`)
4. Serves at `GET /bundles/mcp/data.tar.gz` with `ETag` header
5. OPA polls the bundle endpoint; `304 Not Modified` if nothing changed

Policy changes propagate near-instantly: NPL mutation → SSE event → bundle rebuild → OPA reload.

---

## Decision Audit Trail

### OPA decision logs

OPA has a built-in decision log plugin that POSTs every authorization decision to an HTTP endpoint:

```yaml
decision_logs:
  service: npl-audit
  reporting:
    min_delay_seconds: 0
    max_delay_seconds: 1
```

Each log entry contains: user, service, tool, arguments, decision (allow/deny/pending), timestamp, policy version.

### NPL as the audit store

NPL receives OPA decision logs and can:
- Record every tool call for compliance
- Detect stale-cache decisions (OPA allowed, but NPL state had changed)
- Feed `requestCounter` fields on ToolCatalog and AccessGrant
- Power the TUI dashboard with real-time access analytics

### Post-factum verification

For high-security deployments, a verification service can replay each "allow" decision against the authoritative NPL state:

```
OPA decision log → Verification Service → NPL hasAccess() (authoritative)
                                            │
                                            ├── matches → audit ✓
                                            └── mismatch → alert, flag, remediate
```

This is the credit card model: approve fast (OPA cache), verify after (NPL authoritative check).

---

## Migration Path

### Phase 1: Bundle API (no protocol changes) — DONE

Built bundle server with SSE push. Replaced `http.send` polling with OPA bundles. Eliminated 110ms cold calls and 5s staleness window. The original 3 NPL protocols were used initially.

### Phase 2: Protocol refactor — DONE

Replaced all 4 NPL protocols (ServiceRegistry, ToolPolicy, UserToolAccess, UserRegistry) with a single **PolicyStore** singleton. Bundle server reads everything in 2 HTTP calls. The original design proposed ToolCatalog + AccessGrant (2 protocols), but implementation consolidated into PolicyStore (1 protocol). Rego updated to use `catalog`/`grants` data paths. Integration tests fully rewritten (27 tests passing).

### Phase 3: Contextual evaluation — DONE

The routing table is in PolicyStore (`contextualRoutes`). Admin can register/remove routes via `registerRoute()`/`removeRoute()`. Routes flow into the bundle as `contextual_routing`. The Rego Layer 2 router evaluates single routes and route groups (AND/OR composition). Six contextual policy protocols are implemented:

| Protocol | Type | Purpose |
|----------|------|---------|
| **ApprovalPolicy** | Human-in-the-loop | Human approval before sensitive actions |
| **RateLimitPolicy** | Automated | Per-user call limits per service |
| **ConstraintPolicy** | Automated | Tool-level budget constraints per caller |
| **PreconditionPolicy** | Automated | System state flags gating tool calls |
| **FlowPolicy** | Automated | Cross-call data flow governance per session |
| **IdentityPolicy** | Automated | Identity governance (segregation of duties, four-eyes, exclusive actor) |

Route groups allow composing multiple protocols per tool with AND (all must allow) or OR (any can allow) semantics via `addRouteToGroup()`, `removeRouteFromGroup()`, and `setRouteGroupMode()` on the PolicyStore.

### Phase 4: Decision audit trail — NOT STARTED

Enable OPA decision logs. Build the NPL audit ingestion endpoint. Wire up the TUI dashboard.

---

## Summary

```
┌──────────────────────────────────────────────────────────────────────┐
│                        NPL (single source of truth)                   │
│                                                                       │
│  PolicyStore (singleton)                  Contextual Policies          │
│  ├── Catalog (what exists)                ├── ApprovalPolicy           │
│  ├── Grants (who can use it)              ├── RateLimitPolicy          │
│  ├── Revoked Subjects (emergency kill)    ├── ConstraintPolicy         │
│  └── Contextual Routes (Layer 2 routing)  ├── PreconditionPolicy       │
│     (incl. Route Groups AND/OR)           ├── FlowPolicy               │
│                                           └── IdentityPolicy           │
│                                                                       │
└───────────────┬───────────────────────────────┬──────────────────────┘
                │ bundle (SSE-triggered rebuild)  │ http.send (on-demand)
                v                                v
┌──────────────────────────────────┐  ┌──────────────────────────────┐
│  OPA Layer 1: Static Access       │  │  OPA Layer 2: Contextual     │
│  In-memory data lookups           │  │  Calls NPL evaluate()        │
│  ~11ms E2E, zero network I/O     │  │  ~60ms, only when triggered   │
│  99% of requests resolve here     │  │  6 protocols implemented      │
│  ✅ IMPLEMENTED                   │  │  Route groups (AND/OR)        │
└──────────────────────────────────┘  └──────────────────────────────┘
                │                                │
                └────────── single decision ─────┘
                                │
                                v
                        Envoy ext_authz
                        allow / deny / pending
```

NPL is the single source of truth for all policy — static grants, contextual rules, and stateful evaluation. OPA is the enforcement engine. Envoy asks one question and gets one answer. Clean separation, scalable architecture.
