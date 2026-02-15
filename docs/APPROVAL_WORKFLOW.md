# Approval Workflow Deep Dive

Technical reference for the human-in-the-loop approval system, including the ApprovalPolicy state machine, store-and-forward replay, and the NPL protocol API.

> This is a reference companion to the [How-To Guide](HOWTO.md). See [Section 6](HOWTO.md#6-setting-up-approval-workflows) for setup instructions.

---

## Table of Contents

1. [Overview](#overview)
2. [ApprovalPolicy State Machine](#approvalpolicy-state-machine)
3. [Sequence Diagram: Basic Approval](#sequence-diagram-basic-approval)
4. [Sequence Diagram: Store-and-Forward](#sequence-diagram-store-and-forward)
5. [Deduplication via Lookup Key](#deduplication-via-lookup-key)
6. [One-Time Consumption](#one-time-consumption)
7. [Scoped Approvers](#scoped-approvers)
8. [Replay Worker Architecture](#replay-worker-architecture)
9. [NPL Protocol API Reference](#npl-protocol-api-reference)
10. [PendingApproval Data Structure](#pendingapproval-data-structure)

---

## Overview

The approval workflow provides human-in-the-loop control for tool calls that the security policy marks as `require_approval`. It is implemented as an NPL (Noumena Protocol Language) protocol called `ApprovalPolicy`.

**Key design principles:**
- **One-time consumption** — each approval can only be used once, preventing replay attacks
- **Lookup deduplication** — the same (user, tool, arguments) combination maps to the same approval until consumed
- **Scoped approvers** — security policy can restrict who is allowed to approve specific actions
- **Store-and-forward** — approved requests can be replayed automatically without agent retry

---

## ApprovalPolicy State Machine

```
                                   evaluate()
                                   (first call)
                                       │
                                       ▼
                              ┌─────────────────┐
                              │     pending      │
                              │                  │
                              │  approvalId:     │
                              │  APR-{counter}   │
                              └────────┬─────────┘
                                       │
                          ┌────────────┼────────────┐
                          │                         │
                     approve()                  deny()
                          │                         │
                          ▼                         ▼
                 ┌─────────────────┐      ┌─────────────────┐
                 │    approved     │      │     denied      │
                 │                 │      │                 │
                 │ executionStatus │      │ executionStatus │
                 │ = "queued"     │      │ = "none"        │
                 └────────┬───────┘      └────────┬────────┘
                          │                       │
                    evaluate()               evaluate()
                    (next call)              (next call)
                          │                       │
                          ▼                       ▼
                 ┌─────────────────┐      ┌─────────────────┐
                 │    consumed     │      │    consumed     │
                 │  returns:      │      │  returns:      │
                 │  "allow"       │      │  "deny"        │
                 └────────┬───────┘      └─────────────────┘
                          │
                    [if store-and-forward]
                          │
                          ▼
                 ┌─────────────────┐
                 │   executing     │
                 │                 │
                 │ executionStatus │
                 │ = "completed"  │
                 │   or "failed"  │
                 └─────────────────┘
```

### States

| Status | executionStatus | Meaning |
|--------|----------------|---------|
| `pending` | `none` | Awaiting human decision |
| `approved` | `queued` | Approved, waiting for replay or agent retry |
| `approved` | `completed` | Replayed successfully by store-and-forward |
| `approved` | `failed` | Replay failed (backend error) |
| `denied` | `none` | Denied by approver |

---

## Sequence Diagram: Basic Approval

Without store-and-forward, the agent must retry after approval:

```
Agent              Envoy        OPA           NPL Engine       Admin
  │                  │            │               │               │
  │ POST /mcp        │            │               │               │
  │ tools/call       │            │               │               │
  │─────────────────►│            │               │               │
  │                  │ ext_authz  │               │               │
  │                  │───────────►│               │               │
  │                  │            │ security      │               │
  │                  │            │ policy:       │               │
  │                  │            │ require_      │               │
  │                  │            │ approval      │               │
  │                  │            │               │               │
  │                  │            │ http.send     │               │
  │                  │            │ evaluate()    │               │
  │                  │            │──────────────►│               │
  │                  │            │               │ create        │
  │                  │            │               │ PendingApproval
  │                  │            │               │ APR-1         │
  │                  │            │ "pending:     │               │
  │                  │            │  APR-1"       │               │
  │                  │            │◄──────────────│               │
  │                  │            │               │               │
  │                  │ 403        │               │               │
  │                  │ x-approval │               │               │
  │                  │ -id: APR-1 │               │               │
  │◄─────────────────│            │               │               │
  │                  │            │               │               │
  │                  │            │               │  [Admin reviews]
  │                  │            │               │               │
  │                  │            │               │ approve()     │
  │                  │            │               │◄──────────────│
  │                  │            │               │ "approved"    │
  │                  │            │               │──────────────►│
  │                  │            │               │               │
  │ [Agent retries]  │            │               │               │
  │ POST /mcp        │            │               │               │
  │─────────────────►│            │               │               │
  │                  │ ext_authz  │               │               │
  │                  │───────────►│               │               │
  │                  │            │ http.send     │               │
  │                  │            │ evaluate()    │               │
  │                  │            │──────────────►│               │
  │                  │            │               │ consume APR-1 │
  │                  │            │ "allow"       │               │
  │                  │            │◄──────────────│               │
  │                  │ allow      │               │               │
  │                  │◄───────────│               │               │
  │                  │            │               │               │
  │                  │ route to   │               │               │
  │                  │ backend    │               │               │
  │◄─────────────────│            │               │               │
  │ tool result      │            │               │               │
```

---

## Sequence Diagram: Store-and-Forward

With store-and-forward enabled, the replay worker replays the request automatically:

```
Agent              Envoy        OPA           NPL Engine    Replay Worker   Backend
  │                  │            │               │               │            │
  │ POST /mcp        │            │               │               │            │
  │─────────────────►│            │               │               │            │
  │                  │ ext_authz  │               │               │            │
  │                  │───────────►│               │               │            │
  │                  │            │ evaluate()    │               │            │
  │                  │            │──────────────►│               │            │
  │                  │            │               │ create APR-1  │            │
  │                  │            │               │ (stores full  │            │
  │                  │            │               │  request      │            │
  │                  │            │               │  payload)     │            │
  │                  │            │ "pending:     │               │            │
  │                  │            │  APR-1"       │               │            │
  │                  │            │◄──────────────│               │            │
  │                  │ 403        │               │               │            │
  │◄─────────────────│            │               │               │            │
  │                  │            │               │               │            │
  │                  │            │               │  [Admin approves]          │
  │                  │            │               │ status →      │            │
  │                  │            │               │ approved,     │            │
  │                  │            │               │ exec → queued │            │
  │                  │            │               │               │            │
  │                  │            │               │ [Replay worker polls]      │
  │                  │            │               │               │            │
  │                  │            │               │ getQueued     │            │
  │                  │            │               │ ForExecution()│            │
  │                  │            │               │◄──────────────│            │
  │                  │            │               │ [APR-1]       │            │
  │                  │            │               │──────────────►│            │
  │                  │            │               │               │            │
  │                  │            │               │               │ replay     │
  │                  │            │               │               │ original   │
  │                  │            │               │               │ request    │
  │                  │            │               │               │───────────►│
  │                  │            │               │               │◄───────────│
  │                  │            │               │               │ result     │
  │                  │            │               │               │            │
  │                  │            │               │ record        │            │
  │                  │            │               │ Execution()   │            │
  │                  │            │               │◄──────────────│            │
  │                  │            │               │ status:       │            │
  │                  │            │               │ completed     │            │
  │                  │            │               │ result: {...} │            │
  │                  │            │               │               │            │
  │ [Agent can poll for result]   │               │               │            │
  │ getExecutionResult("APR-1")   │               │               │            │
```

---

## Deduplication via Lookup Key

Each pending approval is identified by a composite **lookup key**:

```
{callerIdentity}:{toolName}:{argumentDigest}
```

| Component | Source | Example |
|-----------|--------|---------|
| `callerIdentity` | JWT user identity | `alice@acme.com` |
| `toolName` | JSON-RPC tool name | `send_email` |
| `argumentDigest` | SHA-256 of tool arguments | `a1b2c3d4...` |

**Deduplication behavior:**

- If an approval with the same lookup key already exists and is `pending`: returns the same `pending:APR-N` ID (no duplicate created)
- If an approval was previously `approved` and consumed: a new approval is created (new APR-N ID)
- If an approval was previously `denied` and consumed: a new approval is created

This prevents duplicate approvals when an agent retries the same call while waiting, but allows new approvals for previously resolved requests.

---

## One-Time Consumption

Approvals are **consumed** when the `evaluate()` function returns `"allow"` or `"deny"` after a human decision. This is critical for security:

1. Admin approves APR-1 → status becomes `approved`
2. Agent retries → `evaluate()` finds APR-1 is approved → returns `"allow"` → **consumes APR-1**
3. Agent tries again with same arguments → new APR-2 is created (previous was consumed)

This prevents:
- **Replay attacks** — a consumed approval can't be reused
- **Privilege escalation** — each tool call needs its own approval cycle
- **Stale approvals** — approvals don't accumulate indefinitely

---

## Scoped Approvers

The security policy can restrict who is allowed to approve requests:

```yaml
policies:
  - name: Approve external email
    when:
      labels: [scope:external]
    action: require_approval
    approvers: [manager, compliance]
```

**Enforcement rules:**

| `approvers` list | Who can approve |
|-----------------|----------------|
| `[manager, compliance]` | Only users with identity matching `manager` or `compliance` |
| `[]` (empty) | Anyone with the `pApprover` party role |
| Not specified | Anyone with the `pApprover` party role |

The `pAdmin` party can always approve regardless of the approvers list.

When `approve()` is called, the NPL protocol checks:
1. If `approvers` is non-empty: `approverIdentity` must be in the list (or caller must be `pAdmin`)
2. If `approvers` is empty: any caller with `pApprover` or `pAdmin` role can approve

---

## Replay Worker Architecture

The replay worker runs inside the bundle server process when `REPLAY_ENABLED=true`.

### Configuration

| Environment Variable | Default | Description |
|---------------------|---------|-------------|
| `REPLAY_ENABLED` | `false` | Enable the replay worker |
| `BACKENDS` | `{}` | JSON map of service names to backend URLs |
| `REPLAY_POLL_INTERVAL` | `5` | Poll interval in seconds |

**BACKENDS format:**

```json
{"gmail": "http://gmail-mcp:8000/mcp", "slack": "http://slack-mcp:8000/mcp"}
```

### Worker Loop

The replay worker runs as a separate thread:

1. **Poll**: Calls `getQueuedForExecution()` on the ApprovalPolicy singleton
2. **Filter**: Only processes approvals where `status == "approved"` and `executionStatus == "queued"`
3. **Route**: Looks up the backend URL from `BACKENDS` using the approval's `serviceName`
4. **Initialize**: Opens an MCP session with the backend (sends `initialize` request)
5. **Replay**: Sends the stored `requestPayload` (the original JSON-RPC body) to the backend
6. **Record**: Calls `recordExecution(approvalId, status, result)` with:
   - `status`: `"completed"` on success, `"failed"` on error
   - `result`: The backend's JSON-RPC response or error message
7. **Sleep**: Waits `REPLAY_POLL_INTERVAL` seconds before next poll

### Error Handling

- If the backend URL is not in `BACKENDS`: logs error, skips the approval
- If the backend returns an error: records `executionStatus: "failed"` with the error
- If the MCP initialization fails: records failure
- Network timeouts: 30 seconds per replay request

---

## NPL Protocol API Reference

The ApprovalPolicy protocol has three party roles:

| Party | JWT Role | Capabilities |
|-------|----------|-------------|
| `pAdmin` | `admin` | Full access: approve, deny, view all, clear, manage execution |
| `pGateway` | `gateway` | Evaluate requests, get queued approvals, record execution |
| `pApprover` | `admin` | Approve, deny, view pending, get execution results |

### evaluate()

**Called by:** OPA (Layer 2 via http.send)
**Permission:** `pGateway`

```
POST /npl/policies/ApprovalPolicy/{id}/evaluate
```

**Request body:**

```json
{
  "toolName": "send_email",
  "callerIdentity": "alice@acme.com",
  "sessionId": "session-123",
  "verb": "create",
  "labels": "scope:external,category:communication",
  "annotations": "{\"readOnlyHint\":false,\"openWorldHint\":true}",
  "argumentDigest": "a1b2c3d4e5f6...",
  "approvers": ["manager"],
  "requestPayload": "{\"jsonrpc\":\"2.0\",\"method\":\"tools/call\",...}",
  "serviceName": "gmail"
}
```

**Returns:** String
- `"allow"` — previously approved (consumed)
- `"deny"` — previously denied (consumed)
- `"pending:APR-N"` — awaiting human decision

### approve()

**Called by:** Admin or Approver (TUI or API)
**Permission:** `pApprover | pAdmin`

```
POST /npl/policies/ApprovalPolicy/{id}/approve
```

**Request body:**

```json
{
  "approvalId": "APR-1",
  "approverIdentity": "manager@acme.com"
}
```

**Returns:** String (`"approved"`)

**Side effects:**
- Sets `status` to `"approved"`
- Sets `decidedBy` to `approverIdentity`
- Sets `executionStatus` to `"queued"` (for store-and-forward)

### deny()

**Called by:** Admin or Approver (TUI or API)
**Permission:** `pApprover | pAdmin`

```
POST /npl/policies/ApprovalPolicy/{id}/deny
```

**Request body:**

```json
{
  "approvalId": "APR-1",
  "approverIdentity": "manager@acme.com",
  "reason": "External emails require VP approval for this recipient"
}
```

**Returns:** String (`"denied"`)

**Side effects:**
- Sets `status` to `"denied"`
- Sets `decidedBy` to `approverIdentity`
- Sets `reason` to the provided reason
- Sets `executionStatus` to `"none"`

### getPendingApprovals()

**Called by:** Admin or Approver
**Permission:** `pAdmin | pApprover`

```
POST /npl/policies/ApprovalPolicy/{id}/getPendingApprovals
```

**Returns:** List of `PendingApproval` objects where `status == "pending"`

### getAllApprovals()

**Called by:** Admin only
**Permission:** `pAdmin`

```
POST /npl/policies/ApprovalPolicy/{id}/getAllApprovals
```

**Returns:** List of all `PendingApproval` objects (pending, approved, denied)

### getQueuedForExecution()

**Called by:** Replay worker
**Permission:** `pGateway | pAdmin`

```
POST /npl/policies/ApprovalPolicy/{id}/getQueuedForExecution
```

**Returns:** List of `PendingApproval` objects where `status == "approved"` AND `executionStatus == "queued"`

### recordExecution()

**Called by:** Replay worker
**Permission:** `pGateway | pAdmin`

```
POST /npl/policies/ApprovalPolicy/{id}/recordExecution
```

**Request body:**

```json
{
  "approvalId": "APR-1",
  "execStatus": "completed",
  "execResult": "{\"jsonrpc\":\"2.0\",\"result\":{\"content\":[...]}}"
}
```

**Side effects:**
- Sets `executionStatus` to `execStatus` (`"completed"` or `"failed"`)
- Sets `executionResult` to `execResult`

### getExecutionResult()

**Called by:** Admin, Approver, or via TUI
**Permission:** `pGateway | pAdmin | pApprover`

```
POST /npl/policies/ApprovalPolicy/{id}/getExecutionResult
```

**Request body:**

```json
{
  "approvalId": "APR-1"
}
```

**Returns:** The full `PendingApproval` object including `executionStatus` and `executionResult`

### clearResolved()

**Called by:** Admin only
**Permission:** `pAdmin`

```
POST /npl/policies/ApprovalPolicy/{id}/clearResolved
```

Removes all non-pending approvals from the list. Pending approvals are preserved.

---

## PendingApproval Data Structure

```json
{
  "approvalId": "APR-1",
  "lookupKey": "alice@acme.com:send_email:a1b2c3d4...",
  "callerIdentity": "alice@acme.com",
  "toolName": "send_email",
  "verb": "create",
  "labels": "scope:external,category:communication",
  "argumentDigest": "a1b2c3d4e5f6...",
  "approvers": ["manager"],
  "status": "pending",
  "reason": "",
  "decidedBy": "",
  "requestPayload": "{\"jsonrpc\":\"2.0\",\"method\":\"tools/call\",...}",
  "serviceName": "gmail",
  "executionStatus": "none",
  "executionResult": ""
}
```

| Field | Type | Description |
|-------|------|-------------|
| `approvalId` | string | Unique ID (auto-incrementing: `APR-1`, `APR-2`, ...) |
| `lookupKey` | string | Composite key: `callerIdentity:toolName:argumentDigest` |
| `callerIdentity` | string | User who triggered the tool call |
| `toolName` | string | Tool name (e.g., `send_email`) |
| `verb` | string | Classified verb (get, create, update, delete) |
| `labels` | string | Comma-separated labels from classification |
| `argumentDigest` | string | SHA-256 hash of tool arguments |
| `approvers` | string[] | Required approver identities (empty = anyone) |
| `status` | string | `pending`, `approved`, or `denied` |
| `reason` | string | Denial reason (empty if approved or pending) |
| `decidedBy` | string | Identity of the approver/denier |
| `requestPayload` | string | Full JSON-RPC request body (for replay) |
| `serviceName` | string | MCP service name (for replay routing) |
| `executionStatus` | string | `none`, `queued`, `completed`, or `failed` |
| `executionResult` | string | Backend response JSON (after replay) |

---

**See also:**
- [How-To Guide](HOWTO.md) — step-by-step setup instructions
- [Architecture Reference](ARCHITECTURE.md) — system topology and data flow
- [Security Policy Reference](SECURITY_POLICY_REFERENCE.md) — writing `require_approval` rules
- [OPA Policy Internals](OPA_POLICY_INTERNALS.md) — how OPA triggers Layer 2 evaluation
