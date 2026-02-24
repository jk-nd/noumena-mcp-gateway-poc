# Three-Tier Governance Model

This document describes the conceptual governance model of the MCP Gateway.
It focuses on *what* the model is and *why* it is structured this way, not on
implementation details. For implementation specifics, see
[v4 Governance Design](DESIGN_V4_SIMPLIFIED_GOVERNANCE.md) and the
[How-To Guide](HOWTO.md).

---

## Overview — The Three Questions

Every tool call that enters the gateway is evaluated against three tiers of
governance. Each tier answers one question:

| Tier | Question |
|------|----------|
| **Access** | Is this user or agent allowed to use this tool? |
| **Guardrails** | Are the parameters of this request within acceptable bounds? |
| **Workflows** | Given the current state of the world, may this action proceed? |

The tiers are evaluated in order. Each can independently deny a request. Only
when all three pass does the request proceed to the backend.

---

## Decision Matrix

|  | **Access** | **Guardrails** | **Workflows** |
|---|---|---|---|
| **Question** | *Who* can use this tool? | *How* can they use it? | *May* this specific action proceed? |
| **Scope** | User/group to service/tool | Parameter constraints, optionally per user/group | Individual request evaluated against live state |
| **Nature** | Static mapping | Static rules (deterministic) | Stateful, dynamic |
| **Authored per** | User/group | Tool (optionally scoped to user/group) | Tool / service |
| **Defined in** | NPL | NPL | NPL |
| **Evaluated in** | OPA (in-memory) | OPA (in-memory) | NPL (at request time) |

---

## Tier 1: Access

Access controls **who** can use **what**. It maps users and groups to services
and tools.

**How rules are authored.** Access rules are written from the caller's
perspective: *"the sales team can use mock-calendar.\*"*. Each rule specifies a
match condition (claims or identity) and the set of services and tools it grants
access to. Rules are OR'd — if any rule matches, access is granted.

**Matching.** Rules support two match types:

- **Claim-based** — matches JWT claims such as department, role, or organization.
  Multiple claim predicates within one rule are AND'd; multiple rules are OR'd.
- **Identity-based** — matches a specific user by email or subject claim.

Both match types participate in the same evaluation. An admin can freely combine
group-level and individual-level rules.

**Key properties:**

- Stateless: the same input always produces the same result
- Fail-closed: no matching rule means denied
- In-memory: evaluated by OPA from bundle data, no network hop

---

## Tier 2: Guardrails

Guardrails control **how** a tool can be used by constraining its parameters.

**How rules are authored.** Guardrail rules are written from the tool's
perspective: *"send_email.to must match \*@acme.com"*. Each rule targets a
specific tool parameter and defines acceptable values. Rules can optionally be
scoped to a user or group, but the primary authoring unit is the tool.

**Constraint types:**

| Constraint | Example |
|------------|---------|
| Value allowlist (`in`) | `environment` must be one of `[dev, staging]` |
| Value blocklist (`not_in`) | `region` must not be `us-gov-west-1` |
| Pattern match (`regex`) | `to` must match `.*@acme\.com` |
| Content check (`contains` / `not_contains`) | `body` must not contain `CONFIDENTIAL` |
| Length limit (`max_length`) | `query` must be at most 500 characters |

**Key properties:**

- Deterministic: given the same configuration and the same request, the result
  is always the same
- Can be scoped per user/group for differentiated enforcement
- Supports one-shot overrides (ToolAuthorization) for exceptional cases — an
  admin can authorize a specific caller to bypass a specific constraint once
- In-memory: evaluated by OPA from bundle data, no network hop

---

## Tier 3: Workflows

Workflows determine whether a specific action **may proceed given the current
state of the system**. Unlike the first two tiers, workflows are stateful — the
same request can produce different results at different times.

**How rules are authored.** Workflow rules are written from the tool's or
service's perspective: *"send_email requires approval"*. The workflow protocol
defines the business logic; the tool is the attachment point.

Workflows encompass **any stateful business logic**, not just approval loops:

| Pattern | Question answered | State involved |
|---------|-------------------|----------------|
| Human-in-the-loop approval | Has a human approved this specific action? | Pending request state machine |
| Budget check | Is there budget remaining for this operation? | Running cost counters |
| NDA / contract verification | Is there a valid agreement in place for this counterparty? | Agreement registry |
| Rate limiting | Has this agent exceeded its quota? | Call counters, time windows |
| Time-windowed access | Is this within the approved access window? | Temporal grants |
| Escalation chains | Have all required approvers signed off? | Multi-party approval state |
| Audit-and-allow | (Always allows, but records for review) | Audit log |

**Key properties:**

- Workflows are the **only tier that calls NPL at request time** (network hop)
- The `evaluate()` action is the universal entry point — what happens inside NPL
  is up to the workflow protocol
- Workflows return `allow`, `deny`, or `pending` (with workflow-specific metadata)
- The same request can produce different results at different times (stateful)
- Only invoked for tools explicitly tagged as requiring workflow evaluation

---

## How the Tiers Compose

```
Request arrives
    |
    +-- Tier 1: Access check (in-memory) ---- deny --> STOP
    |
    +-- Tier 2: Guardrails check (in-memory) - deny --> STOP (or override)
    |
    +-- Tier 3: Workflow check (NPL call) ---- deny/pending --> STOP
    |   (only if tool requires it)
    |
    +-- ALLOW --> route to backend
```

Tiers are evaluated in order. Each tier can independently deny. Only if all
tiers pass does the request proceed. This means:

- Access failures are caught before guardrails are evaluated
- Guardrail failures are caught before NPL is called (saving the network hop)
- Workflows only run for requests that already passed Access + Guardrails

---

## Authoring Perspective: What Scales

Each tier uses a different primary authoring unit, because each answers a
different kind of question:

| Tier | Primary authoring unit | Why |
|------|----------------------|-----|
| **Access** | User / group | Access is about *who*. An admin thinks "the sales team needs calendar access" — the natural starting point is the group. |
| **Guardrails** | Tool | Guardrails are about *how*. An admin thinks "send_email must only go to @acme.com addresses" — the natural starting point is the tool and its parameters. |
| **Workflows** | Tool / service | Workflows are about *whether, right now*. An admin thinks "send_email needs approval" — the natural starting point is the tool that triggers the workflow. |

This is deliberate. A single authoring unit for all three tiers would force
unnatural modeling. Access rules group naturally by team; guardrails group
naturally by tool; workflows attach to the operations they govern.

**Scaling.** Each tier scales along its natural axis:

- **Access** scales with organizational structure. Adding a new team is one rule.
  Adding a new service to an existing team's access is an update to their rule.
- **Guardrails** scale with tool complexity. A tool with sensitive parameters
  gets constraints; a simple read-only tool needs none.
- **Workflows** scale with business process complexity. A tool that needs human
  approval gets a workflow; most tools never need one.

---

## Design Principles

- **Fail-closed.** Every tier defaults to deny. Absence of a rule is not
  permission.

- **Stateless tiers are fast.** Access and Guardrails run in-memory from
  pre-loaded bundle data. Combined evaluation is ~1ms.

- **Stateful logic is isolated.** Only Workflows touch NPL at request time.
  The network hop cost is paid only when business logic requires it.

- **NPL is the source of truth.** All three tiers are configured in NPL.
  OPA consumes a snapshot of tiers 1 and 2 via the bundle; tier 3 is
  evaluated live.

- **Composability.** Tiers are independent. Adding a workflow does not change
  access rules or guardrails. Tightening a guardrail does not affect who has
  access.

---

## Current Limitations and Future Direction

- **ApprovedRecipients** is currently too domain/email-specific. It should be
  generalized into either Guardrails (static allowlists for any parameter value)
  or Workflows (dynamic approval for specific values).

- **Workflow patterns** beyond approval loops are designed for but not yet
  implemented. The `evaluate()` contract is deliberately general to support
  arbitrary workflow logic (budget checks, rate limiting, NDA verification, etc.).

- **Guardrail scoping** per user/group works but the authoring UX could be
  improved to make differentiated enforcement more discoverable.

---

**See also:**
- [Architecture Reference](ARCHITECTURE.md) — service topology and data flow
- [v4 Governance Design](DESIGN_V4_SIMPLIFIED_GOVERNANCE.md) — implementation design
- [How-To Guide](HOWTO.md) — step-by-step configuration walkthrough
- [Approval Workflow](APPROVAL_WORKFLOW.md) — deep dive into the approval workflow pattern
