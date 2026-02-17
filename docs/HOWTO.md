# How-To Guide: Configuring the MCP Gateway

A hands-on walkthrough for configuring and operating the MCP Gateway security pipeline. Follow these steps in order for a complete setup, or jump to the section you need.

**Contents**

1. [Prerequisites & Starting the Stack](#1-prerequisites--starting-the-stack)
2. [First-Time Setup (TUI Wizard)](#2-first-time-setup-tui-wizard)
3. [Adding an MCP Service](#3-adding-an-mcp-service)
4. [Managing Users & Access](#4-managing-users--access)
5. [Writing a Security Policy](#5-writing-a-security-policy-mcp-securityyaml)
6. [Contextual Routing Protocols](#6-contextual-routing-protocols)
   - 6.1 [ApprovalPolicy](#61-approvalpolicy-human-in-the-loop)
   - 6.2 [RateLimitPolicy](#62-ratelimitpolicy-automated-rate-limiting)
   - 6.3 [ConstraintPolicy](#63-constraintpolicy-tool-level-budget-constraints)
   - 6.4 [PreconditionPolicy](#64-preconditionpolicy-system-state-gates)
   - 6.5 [FlowPolicy](#65-flowpolicy-cross-call-data-flow-governance)
   - 6.6 [IdentityPolicy](#66-identitypolicy-identity-governance)
   - 6.7 [Route Groups (AND/OR Composition)](#67-route-groups-andor-composition)
7. [Credential Injection](#7-credential-injection-optional)
8. [Debugging & Troubleshooting](#8-debugging--troubleshooting)
9. [Configuration Reference](#9-configuration-reference)
10. [Running Tests](#10-running-tests)

---

## 1. Prerequisites & Starting the Stack

**Requirements:** Docker and Docker Compose.

### Start everything

```bash
cd deployments
docker compose up -d
```

### Wait for healthy services

Keycloak and the NPL Engine take the longest to initialize. Wait for them:

```bash
docker compose ps --format "table {{.Name}}\t{{.Status}}"
```

All services should show `(healthy)` in their status. If Keycloak is still starting, give it up to 60 seconds.

### Verify with health endpoints

```bash
# Envoy gateway
curl http://localhost:8000/health

# OPA bundle server
curl http://localhost:8282/health

# Keycloak
curl http://localhost:9000/health
```

### Service URLs

| Service | URL | Credentials |
|---------|-----|-------------|
| Envoy Gateway (MCP endpoint) | `http://localhost:8000/mcp` | Bearer JWT |
| Envoy Admin | `http://localhost:9901` | (none) |
| OPA HTTP API | `http://localhost:8181` | (none) |
| OPA Bundle Server | `http://localhost:8282` | (none) |
| Keycloak Admin Console | `http://localhost:11000` | admin / welcome |
| NPL Engine API | `http://localhost:12000` | Bearer JWT |
| NPL Inspector | `http://localhost:8080` | admin / Welcome123 |
| Vault UI | `http://localhost:8200` | Token: `dev-token` |

---

## 2. First-Time Setup (TUI Wizard)

The TUI is the recommended way to configure the gateway.

### Start the TUI

```bash
cd tui
npm install   # first time only
npm start
```

### Log in

Use the default admin credentials:

- **Username:** `admin`
- **Password:** `Welcome123`

### NPL Bootstrap

On first launch, the TUI creates a **PolicyStore singleton** — the single source of truth for all policy data. This happens automatically when the TUI connects.

The PolicyStore holds:
- **Catalog** — registered services and their enabled tools
- **Grants** — per-user tool access permissions
- **Revoked Subjects** — emergency kill switch entries
- **Contextual Routes** — Layer 2 policy routing (approval workflows)
- **Security Policy** — tool annotations, classifiers, and policy rules

> **Note:** `configs/services.yaml` is a cache file used for bulk import/export only. The PolicyStore is always authoritative.

---

## 3. Adding an MCP Service

There are three ways to add a service.

### Option A: TUI Wizard (recommended)

From the main menu:

1. Select **+ Search Docker Hub** to browse the `mcp/*` namespace
2. Or select **+ Add MCP service** to enter a custom Docker command, NPM package, or template

The TUI will:
- Register the service in the PolicyStore
- Discover available tools from the MCP server
- Let you enable/disable individual tools

### Option B: Manual via NPL API

Register and configure a service using curl:

```bash
# Get a token
TOKEN=$(curl -s -X POST "http://localhost:11000/realms/mcpgateway/protocol/openid-connect/token" \
  -d "grant_type=password&client_id=mcpgateway&username=admin&password=Welcome123" \
  | jq -r '.access_token')

# Find the PolicyStore instance
POLICY_ID=$(curl -s -H "Authorization: Bearer $TOKEN" \
  "http://localhost:12000/npl/store/PolicyStore/" \
  | jq -r '.[0].id')

# Register a service
curl -s -X POST "http://localhost:12000/npl/store/PolicyStore/$POLICY_ID/registerService" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"serviceName": "my-service"}'

# Enable the service
curl -s -X POST "http://localhost:12000/npl/store/PolicyStore/$POLICY_ID/enableService" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"serviceName": "my-service"}'

# Enable a tool
curl -s -X POST "http://localhost:12000/npl/store/PolicyStore/$POLICY_ID/enableTool" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"serviceName": "my-service", "toolName": "my-tool"}'
```

### Option C: Bulk import from services.yaml

Edit `configs/services.yaml` with your services:

```yaml
services:
  - name: "my-service"
    displayName: "My Service"
    type: "MCP_STDIO"
    enabled: true
    command: "docker run -i --rm mcp/my-service"
    requiresCredentials: false
    description: "Description of my service"
    tools:
      - name: "my-tool"
        description: "What this tool does"
        inputSchema:
          type: "object"
          properties: {}
          required: []
        enabled: true

user_access:
  users:
    - userId: "user@example.com"
      tools:
        my-service:
          - "*"
```

Then import via TUI: **Import / Export > Import from YAML**.

### Adding a backend container

To run a new MCP server as part of the Docker stack, add a supergateway sidecar to `deployments/docker-compose.yml`:

```yaml
  my-service-mcp:
    build:
      context: ..
      dockerfile: deployments/docker/Dockerfile.supergateway
    environment:
      MCP_COMMAND: "docker run -i --rm mcp/my-service"
    volumes:
      - /var/run/docker.sock:/var/run/docker.sock
    networks:
      - backend-net
```

Then register it in the MCP aggregator's `BACKENDS` environment variable:

```yaml
  mcp-aggregator:
    environment:
      BACKENDS: "duckduckgo:http://duckduckgo-mcp:8000,my-service:http://my-service-mcp:8000"
```

Restart the stack: `docker compose up -d`.

---

## 4. Managing Users & Access

### Creating users

**Via TUI:** Main menu > **Manage users & tool access** > Register a new user.

**Via Keycloak Admin UI:**
1. Go to `http://localhost:11000`
2. Log in with admin / welcome
3. Navigate to the `mcpgateway` realm
4. Users > Add user

### Granting tool access

From the TUI, select a user and grant access to tools. You can grant:

- **Wildcard (`*`)** — access to all tools in a service
- **Specific tools** — e.g., `search`, `list_events`

**Via API:**

```bash
# Grant all tools for a service
curl -s -X POST "http://localhost:12000/npl/store/PolicyStore/$POLICY_ID/grantAllToolsForService" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"subjectId": "user@example.com", "serviceName": "duckduckgo"}'

# Grant a specific tool
curl -s -X POST "http://localhost:12000/npl/store/PolicyStore/$POLICY_ID/grantTool" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"subjectId": "user@example.com", "serviceName": "duckduckgo", "toolName": "search"}'
```

### Revoking access

```bash
# Revoke a specific tool
curl -s -X POST "http://localhost:12000/npl/store/PolicyStore/$POLICY_ID/revokeTool" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"subjectId": "user@example.com", "serviceName": "duckduckgo", "toolName": "search"}'

# Revoke all access to a service
curl -s -X POST "http://localhost:12000/npl/store/PolicyStore/$POLICY_ID/revokeServiceAccess" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"subjectId": "user@example.com", "serviceName": "duckduckgo"}'
```

### Emergency kill switch

`revokeSubject` blocks ALL access for a user instantly — across every service:

```bash
curl -s -X POST "http://localhost:12000/npl/store/PolicyStore/$POLICY_ID/revokeSubject" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"subjectId": "compromised-user@example.com"}'
```

To restore access later:

```bash
curl -s -X POST "http://localhost:12000/npl/store/PolicyStore/$POLICY_ID/reinstateSubject" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"subjectId": "compromised-user@example.com"}'
```

### How grants propagate

When you change a grant, the update flows through the pipeline:

```
PolicyStore (NPL) → SSE event → Bundle Server → OPA bundle rebuild → Envoy ext_authz
```

Typical propagation time: **2-6 seconds**. The bundle server subscribes to SSE push notifications from the NPL Engine and rebuilds the OPA bundle on every state change.

---

## 5. Writing a Security Policy (`mcp-security.yaml`)

Security policies add fine-grained control on top of the basic catalog/grants model. They classify tool calls by intent, annotate them with metadata, and enforce rules like "external emails require approval" or "destructive operations are blocked."

> [-> Security Policy Reference](SECURITY_POLICY_REFERENCE.md) for the complete schema specification.

### 5.1 The Security Policy File

Create a file called `mcp-security.yaml` (any location — the TUI uploads it to the PolicyStore):

```yaml
version: "1.0"

tenant:
  company_domain: acme.com

profiles:
  - "@openclaw/security-gmail"

tool_overrides:
  gmail:
    send_email:
      destructiveHint: false

policies:
  - name: Block BCC usage
    description: >
      BCC is a social engineering risk — block tool calls that use BCC.
    when:
      labels: [data:bcc-used]
    action: deny
    priority: 0

  - name: Approve external email
    description: >
      Sending email to recipients outside @acme.com requires manager approval.
    when:
      labels: [scope:external]
      openWorldHint: true
    match: all
    action: npl_evaluate
    approvers: [manager]
    timeout: 60m
    priority: 10

  - name: Approve destructive operations
    description: >
      Deleting emails or filters requires admin approval.
    when:
      destructiveHint: true
    action: npl_evaluate
    approvers: [admin]
    timeout: 30m
    priority: 20

  - name: Allow read-only operations
    description: Searching, reading, and listing are always allowed.
    when:
      readOnlyHint: true
    action: allow
    priority: 50

  - name: Allow internal email
    description: Emails to @acme.com addresses are auto-approved.
    when:
      labels: [scope:internal]
      verb: create
    match: all
    action: allow
    priority: 50

  - name: Allow email drafts
    description: >
      Creating email drafts is always allowed — they're not sent yet.
    when:
      verb: create
      openWorldHint: false
      labels: [category:communication]
    match: all
    action: allow
    priority: 60

  - name: Default deny
    description: Any tool call not explicitly allowed is denied.
    when: {}
    action: deny
    priority: 999
```

The file has four top-level sections:

| Section | Purpose |
|---------|---------|
| `tenant` | Template variables (e.g., `{company_domain}` used in classifiers) |
| `profiles` | Community security profiles to import |
| `tool_overrides` | Override specific hint values from profiles |
| `policies` | Ordered rules that determine allow/deny/npl_evaluate |

### 5.2 Tool Annotations (MCP Standard)

Each tool can be annotated with four MCP-standard hints:

| Hint | Type | Meaning |
|------|------|---------|
| `readOnlyHint` | boolean | Tool only reads data, never modifies |
| `destructiveHint` | boolean | Tool deletes or irreversibly modifies data |
| `openWorldHint` | boolean | Tool sends data outside the system (email, API call) |
| `idempotentHint` | boolean | Calling the tool twice with same args has same effect |

In addition, each tool gets:

**Verbs** (Kubernetes RBAC style): `get`, `list`, `create`, `update`, `delete`

**Labels** (namespaced key:value pairs):
- `scope:internal` / `scope:external` — data destination
- `category:communication` — tool category
- `data:pii` / `data:bcc-used` — data sensitivity

If no verb is explicitly set, OPA infers one from the tool name:

| Tool name prefix | Inferred verb |
|-----------------|---------------|
| `read_`, `get_`, `list_`, `search_`, `fetch_`, `download_` | `get` |
| `create_`, `send_`, `add_`, `draft_`, `compose_` | `create` |
| `update_`, `edit_`, `modify_`, `batch_modify_` | `update` |
| `delete_`, `remove_`, `revoke_`, `batch_delete_` | `delete` |

### 5.3 Community Security Profiles

Security profiles are curated tool annotation packages — they define the hints, verbs, labels, and classifiers for a specific MCP service so you don't have to write them from scratch.

**File format** (`security-profiles/gmail.yaml`):

```yaml
service: gmail
description: Security profile for Gmail MCP server

tools:
  send_email:
    readOnlyHint: false
    destructiveHint: false
    openWorldHint: true
    idempotentHint: false
    verb: create
    labels: [category:communication]
    classify:
      - field: to
        contains: "@{company_domain}"
        set_labels: [scope:internal]
      - field: to
        not_contains: "@{company_domain}"
        set_labels: [scope:external]
      - field: bcc
        present: true
        set_labels: [data:bcc-used]

  read_email:
    readOnlyHint: true
    destructiveHint: false
    openWorldHint: false
    idempotentHint: true
    verb: get
    labels: [category:communication]

  delete_email:
    readOnlyHint: false
    destructiveHint: true
    openWorldHint: false
    idempotentHint: true
    verb: delete
    labels: [category:communication]
  # ... more tools
```

**Importing a profile** — add it to the `profiles` array in your security policy:

```yaml
profiles:
  - "@openclaw/security-gmail"
```

**Overriding profile values** — use `tool_overrides` to change specific hints:

```yaml
tool_overrides:
  gmail:
    send_email:
      destructiveHint: false    # override profile's default
```

### 5.4 Argument Classifiers

Classifiers dynamically add labels based on the actual arguments in a tool call. This is what makes policies context-aware — the same tool can be allowed or denied depending on its arguments.

**Example:** Classify emails by recipient domain:

```yaml
classify:
  - field: to
    contains: "@{company_domain}"
    set_labels: [scope:internal]
  - field: to
    not_contains: "@{company_domain}"
    set_labels: [scope:external]
  - field: bcc
    present: true
    set_labels: [data:bcc-used]
```

**Classifier types:**

| Type | Syntax | Matches when |
|------|--------|-------------|
| `contains` | `contains: "value"` | Field value contains the string |
| `not_contains` | `not_contains: "value"` | Field value does not contain the string |
| `present` | `present: true` | Field exists in the arguments |
| `present` | `present: false` | Field does not exist in the arguments |

**Template variables:** Use `{company_domain}` in classifier values — replaced at evaluation time with the value from the `tenant` config section.

### 5.5 Policy Rules

Each rule in the `policies` array has this structure:

```yaml
- name: Rule name
  description: What this rule does and why
  when:
    verb: create
    labels: [scope:external]
    readOnlyHint: true
    destructiveHint: false
    openWorldHint: true
    idempotentHint: false
  match: all          # "all" (AND, default) or "any" (OR)
  action: allow       # "allow", "deny", or "npl_evaluate"
  approvers: [admin]  # only for npl_evaluate
  timeout: 60m        # only for npl_evaluate
  priority: 10        # lowest number wins (evaluated first)
```

**Priority-based evaluation:** Rules are evaluated in priority order (lowest number first). The first matching rule wins. Always include a fallback rule with `when: {}` (matches everything) at the highest priority number.

**Match semantics:**
- `all` (default) — ALL conditions in `when` must be true (AND)
- `any` — ANY condition in `when` must be true (OR)

**Actions:**

| Action | Effect |
|--------|--------|
| `allow` | Tool call proceeds to the backend |
| `deny` | Tool call is blocked with 403 |
| `npl_evaluate` | Tool call is held pending human approval |

**Walking through the acme-corp example:**

1. **Priority 0 — Block BCC** (`deny`): If the tool call has the `data:bcc-used` label (set by classifier when `bcc` field is present), block it immediately. No exceptions.

2. **Priority 10 — Approve external email** (`npl_evaluate`): If the tool call has `scope:external` AND `openWorldHint: true` (both must match — `match: all`), require manager approval. Drafts (`openWorldHint: false`) skip this rule.

3. **Priority 20 — Approve destructive ops** (`npl_evaluate`): If `destructiveHint: true`, require admin approval. Catches `delete_email`, `delete_filter`, etc.

4. **Priority 50 — Allow read-only** (`allow`): If `readOnlyHint: true`, allow. Catches `read_email`, `search_emails`, `list_filters`, etc.

5. **Priority 50 — Allow internal email** (`allow`): If `scope:internal` AND `verb: create`, allow. Internal emails don't need approval.

6. **Priority 60 — Allow drafts** (`allow`): If `verb: create` AND `openWorldHint: false` AND `category:communication`, allow. Drafts are safe — they'll be caught when actually sent.

7. **Priority 999 — Default deny** (`deny`): `when: {}` matches everything. Anything not explicitly allowed is denied.

### 5.6 Loading the Security Policy

**Via TUI:** Main menu > **Security Policy** > Load a YAML file.

The TUI reads the YAML, converts it to JSON, and calls `setSecurityPolicy(jsonString)` on the PolicyStore.

**Via API:**

```bash
# Convert YAML to JSON and set the policy
POLICY_JSON=$(python3 -c "import yaml, json, sys; print(json.dumps(yaml.safe_load(open('mcp-security.yaml'))))")

curl -s -X POST "http://localhost:12000/npl/store/PolicyStore/$POLICY_ID/setSecurityPolicy" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d "{\"jsonString\": $(echo "$POLICY_JSON" | jq -Rs .)}"
```

**Clearing the security policy:**

```bash
curl -s -X POST "http://localhost:12000/npl/store/PolicyStore/$POLICY_ID/clearSecurityPolicy" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{}'
```

**Propagation path:**

```
mcp-security.yaml → TUI/API → PolicyStore (NPL) → SSE → Bundle Server → OPA bundle → Rego evaluation
```

Once loaded, the policy is embedded in the OPA bundle and evaluated in-memory on every tool call (~1ms).

---

## 6. Contextual Routing Protocols

When a security policy rule uses `action: npl_evaluate`, the gateway delegates the decision to a **contextual routing protocol** (Layer 2). OPA calls the registered NPL protocol's `evaluate()` endpoint, which returns `"allow"`, `"deny"`, or `"pending:<id>"`. Any protocol implementing this contract can be plugged in.

The gateway ships with six protocols:

| Protocol | Type | Response | Use Case |
|----------|------|----------|----------|
| **ApprovalPolicy** | Human-in-the-loop | `"allow"`, `"deny"`, `"pending:APR-N"` | Human approval before sensitive actions |
| **RateLimitPolicy** | Automated | `"allow"`, `"deny"` | Per-user call limits |
| **ConstraintPolicy** | Automated | `"allow"`, `"deny"` | Tool-level budget constraints per caller |
| **PreconditionPolicy** | Automated | `"allow"`, `"deny"` | System state flags gating tool calls |
| **FlowPolicy** | Automated | `"allow"`, `"deny"` | Cross-call data flow governance per session |
| **IdentityPolicy** | Automated | `"allow"`, `"deny"` | Identity governance (segregation of duties, four-eyes, exclusive actor) |

**TUI:** Main menu > **Contextual Policies** to manage all protocols and routes.

### Registering a contextual route

For any contextual protocol to work, OPA needs to know where to send evaluation requests. Register a route on the PolicyStore:

```bash
# Find the protocol instance ID (ApprovalPolicy or RateLimitPolicy)
INSTANCE_ID=$(curl -s -H "Authorization: Bearer $TOKEN" \
  "http://localhost:12000/npl/policies/ApprovalPolicy/" \
  | jq -r '.[0].id')

# Register a route for a specific tool
curl -s -X POST "http://localhost:12000/npl/store/PolicyStore/$POLICY_ID/registerRoute" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d "{
    \"serviceName\": \"gmail\",
    \"toolName\": \"send_email\",
    \"routeProtocol\": \"ApprovalPolicy\",
    \"instanceId\": \"$INSTANCE_ID\",
    \"endpoint\": \"/npl/policies/ApprovalPolicy/$INSTANCE_ID/evaluate\"
  }"

# Or register a wildcard route for all tools in a service
curl -s -X POST "http://localhost:12000/npl/store/PolicyStore/$POLICY_ID/registerRoute" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d "{
    \"serviceName\": \"gmail\",
    \"toolName\": \"*\",
    \"routeProtocol\": \"ApprovalPolicy\",
    \"instanceId\": \"$INSTANCE_ID\",
    \"endpoint\": \"/npl/policies/ApprovalPolicy/$INSTANCE_ID/evaluate\"
  }"
```

The TUI automates this via **Contextual Policies > Manage routes > Register route**.

### 6.1 ApprovalPolicy (Human-in-the-Loop)

> [-> Approval Workflow Deep Dive](APPROVAL_WORKFLOW.md) for the complete state machine and protocol reference.

**Prerequisites:**

1. A security policy with at least one `npl_evaluate` rule (see [Section 5](#5-writing-a-security-policy-mcp-securityyaml))
2. A contextual route pointing to an ApprovalPolicy instance

**The approval flow:**

```
1. Agent calls tool
   POST /mcp  {"method":"tools/call","params":{"name":"gmail.send_email",...}}

2. Envoy → OPA ext_authz
   OPA evaluates security policy → action: "npl_evaluate"

3. OPA → NPL evaluate() (Layer 2, via http.send)
   NPL creates a PendingApproval record → returns "pending:APR-1"

4. OPA → Envoy: 403 Forbidden
   Headers: x-approval-id: APR-1, x-sp-action: npl_evaluate

5. Admin reviews pending approvals (TUI or API)
   Sees: APR-1 | user@acme.com | send_email | scope:external | pending

6. Admin approves (or denies with reason)
   TUI: Contextual Policies > ApprovalPolicy > Approve
   API: POST .../approve {"approvalId":"APR-1","approverIdentity":"admin"}

7. Agent retries the same tool call
   OPA → NPL evaluate() → finds approved record → returns "allow"
   OPA → Envoy: allow → request proceeds to backend
   (Approval is consumed — cannot be replayed)
```

**Store-and-Forward (Automatic Replay):**

Instead of requiring the agent to retry, the gateway can automatically replay approved requests:

1. When `npl_evaluate` triggers, the original JSON-RPC request payload is stored in the PendingApproval record.
2. When an admin approves, `executionStatus` changes to `queued`.
3. The bundle server's replay worker picks up queued approvals, replays the request, and records the result.
4. The result is available via `getExecutionResult(approvalId)`.

Enable store-and-forward on the bundle server (`deployments/docker-compose.yml`):

```yaml
  bundle-server:
    environment:
      REPLAY_ENABLED: "true"
      REPLAY_POLL_INTERVAL: "5"
      BACKENDS: '{"gmail": "http://gmail-mcp:8000/mcp"}'
```

**Managing approvals:**

**Via TUI:** Main menu > **Contextual Policies** > **ApprovalPolicy**

- View all pending approvals with details
- Approve or deny with optional reason
- View history and clear resolved records

**Via API:**

```bash
# List pending approvals
curl -s -X POST "http://localhost:12000/npl/policies/ApprovalPolicy/$APPROVAL_ID/getPendingApprovals" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{}'

# Approve
curl -s -X POST "http://localhost:12000/npl/policies/ApprovalPolicy/$APPROVAL_ID/approve" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"approvalId": "APR-1", "approverIdentity": "admin"}'

# Deny with reason
curl -s -X POST "http://localhost:12000/npl/policies/ApprovalPolicy/$APPROVAL_ID/deny" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"approvalId": "APR-1", "approverIdentity": "admin", "reason": "Not authorized for external comms"}'
```

### 6.2 RateLimitPolicy (Automated Rate Limiting)

RateLimitPolicy enforces per-user call limits. Unlike ApprovalPolicy, it is fully automated — returning `"allow"` or `"deny"` immediately with no human intervention.

**Setup:**

1. Register a contextual route pointing to a RateLimitPolicy instance (via TUI or API)
2. Configure a limit for the service

```bash
# Find the RateLimitPolicy instance ID
RL_ID=$(curl -s -H "Authorization: Bearer $TOKEN" \
  "http://localhost:12000/npl/policies/RateLimitPolicy/" \
  | jq -r '.[0].id')

# Register a route for duckduckgo
curl -s -X POST "http://localhost:12000/npl/store/PolicyStore/$POLICY_ID/registerRoute" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d "{
    \"serviceName\": \"duckduckgo\",
    \"toolName\": \"*\",
    \"routeProtocol\": \"RateLimitPolicy\",
    \"instanceId\": \"$RL_ID\",
    \"endpoint\": \"/npl/policies/RateLimitPolicy/$RL_ID/evaluate\"
  }"

# Set a rate limit: 5 calls per session
curl -s -X POST "http://localhost:12000/npl/policies/RateLimitPolicy/$RL_ID/setLimit" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"serviceName": "duckduckgo", "maxCalls": 5, "windowLabel": "per-session"}'
```

**Managing rate limits:**

**Via TUI:** Main menu > **Contextual Policies** > **RateLimitPolicy**

- View configured limits with usage stats
- Add/update/remove limits
- View per-user usage records
- Reset counters (individual or all)

**Via API:**

```bash
# Get all limits
curl -s -X POST "http://localhost:12000/npl/policies/RateLimitPolicy/$RL_ID/getAllLimits" \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d '{}'

# Get all usage records
curl -s -X POST "http://localhost:12000/npl/policies/RateLimitPolicy/$RL_ID/getAllUsage" \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d '{}'

# Reset usage for a specific user/service
curl -s -X POST "http://localhost:12000/npl/policies/RateLimitPolicy/$RL_ID/resetUsage" \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"callerIdentity": "user@acme.com", "serviceName": "duckduckgo"}'

# Reset all usage counters
curl -s -X POST "http://localhost:12000/npl/policies/RateLimitPolicy/$RL_ID/resetAllUsage" \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d '{}'
```

### 6.3 ConstraintPolicy (Tool-Level Budget Constraints)

ConstraintPolicy enforces per-caller occurrence limits on specific tools. Unlike RateLimitPolicy (which limits by service), ConstraintPolicy operates at the individual tool level — e.g., "user X can call `transfer_funds` at most 3 times."

**Setup:**

1. Register a contextual route pointing to a ConstraintPolicy instance (via TUI or API)
2. Configure constraints for specific tools

```bash
# Find the ConstraintPolicy instance ID
CP_ID=$(curl -s -H "Authorization: Bearer $TOKEN" \
  "http://localhost:12000/npl/policies/ConstraintPolicy/" \
  | jq -r '.[0].id')

# Register a route
curl -s -X POST "http://localhost:12000/npl/store/PolicyStore/$POLICY_ID/registerRoute" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d "{
    \"serviceName\": \"banking\",
    \"toolName\": \"transfer_funds\",
    \"routeProtocol\": \"ConstraintPolicy\",
    \"instanceId\": \"$CP_ID\",
    \"endpoint\": \"/npl/policies/ConstraintPolicy/$CP_ID/evaluate\"
  }"

# Set a constraint: max 3 calls to transfer_funds per caller
curl -s -X POST "http://localhost:12000/npl/policies/ConstraintPolicy/$CP_ID/setConstraint" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"serviceName": "banking", "toolName": "transfer_funds", "maxOccurrences": 3, "description": "Max 3 transfers per caller"}'
```

**Managing constraints:**

```bash
# View all constraints
curl -s -X POST "http://localhost:12000/npl/policies/ConstraintPolicy/$CP_ID/getConstraints" \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d '{}'

# View all usage counters
curl -s -X POST "http://localhost:12000/npl/policies/ConstraintPolicy/$CP_ID/getAllCounters" \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d '{}'

# Reset counter for a specific caller/tool
curl -s -X POST "http://localhost:12000/npl/policies/ConstraintPolicy/$CP_ID/resetCounter" \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"callerIdentity": "user@acme.com", "serviceName": "banking", "toolName": "transfer_funds"}'

# Reset all counters
curl -s -X POST "http://localhost:12000/npl/policies/ConstraintPolicy/$CP_ID/resetAllCounters" \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d '{}'

# Remove a constraint
curl -s -X POST "http://localhost:12000/npl/policies/ConstraintPolicy/$CP_ID/removeConstraint" \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"serviceName": "banking", "toolName": "transfer_funds"}'

# View statistics
curl -s -X POST "http://localhost:12000/npl/policies/ConstraintPolicy/$CP_ID/getStatistics" \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d '{}'
```

### 6.4 PreconditionPolicy (System State Gates)

PreconditionPolicy gates tool calls on named system state flags. Administrators set conditions (key-value pairs representing system state), then define rules that require a specific condition value before a tool can be called. For example, "the `deploy` tool can only be called when `maintenance_window` is `open`."

**Setup:**

```bash
# Find the PreconditionPolicy instance ID
PP_ID=$(curl -s -H "Authorization: Bearer $TOKEN" \
  "http://localhost:12000/npl/policies/PreconditionPolicy/" \
  | jq -r '.[0].id')

# Register a route
curl -s -X POST "http://localhost:12000/npl/store/PolicyStore/$POLICY_ID/registerRoute" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d "{
    \"serviceName\": \"infrastructure\",
    \"toolName\": \"deploy\",
    \"routeProtocol\": \"PreconditionPolicy\",
    \"instanceId\": \"$PP_ID\",
    \"endpoint\": \"/npl/policies/PreconditionPolicy/$PP_ID/evaluate\"
  }"

# Set a condition (system state flag)
curl -s -X POST "http://localhost:12000/npl/policies/PreconditionPolicy/$PP_ID/setCondition" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"conditionName": "maintenance_window", "value": "open"}'

# Add a rule: deploy requires maintenance_window == "open"
curl -s -X POST "http://localhost:12000/npl/policies/PreconditionPolicy/$PP_ID/addRule" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"conditionName": "maintenance_window", "requiredValue": "open", "serviceName": "infrastructure", "toolName": "deploy"}'
```

**Managing preconditions:**

```bash
# View all conditions
curl -s -X POST "http://localhost:12000/npl/policies/PreconditionPolicy/$PP_ID/getConditions" \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d '{}'

# View all rules
curl -s -X POST "http://localhost:12000/npl/policies/PreconditionPolicy/$PP_ID/getRules" \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d '{}'

# Remove a condition
curl -s -X POST "http://localhost:12000/npl/policies/PreconditionPolicy/$PP_ID/removeCondition" \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"conditionName": "maintenance_window"}'

# Remove a rule
curl -s -X POST "http://localhost:12000/npl/policies/PreconditionPolicy/$PP_ID/removeRule" \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"conditionName": "maintenance_window", "serviceName": "infrastructure", "toolName": "deploy"}'

# View statistics
curl -s -X POST "http://localhost:12000/npl/policies/PreconditionPolicy/$PP_ID/getStatistics" \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d '{}'
```

### 6.5 FlowPolicy (Cross-Call Data Flow Governance)

FlowPolicy governs data flow between tools within a session. It tracks which tools a session has called and enforces rules about what can be called next. For example, "if an agent read PII via `read_customer`, it cannot then call `slack.send_message` in the same session."

**Setup:**

```bash
# Find the FlowPolicy instance ID
FP_ID=$(curl -s -H "Authorization: Bearer $TOKEN" \
  "http://localhost:12000/npl/policies/FlowPolicy/" \
  | jq -r '.[0].id')

# Register a route (wildcard for all tools on slack)
curl -s -X POST "http://localhost:12000/npl/store/PolicyStore/$POLICY_ID/registerRoute" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d "{
    \"serviceName\": \"slack\",
    \"toolName\": \"*\",
    \"routeProtocol\": \"FlowPolicy\",
    \"instanceId\": \"$FP_ID\",
    \"endpoint\": \"/npl/policies/FlowPolicy/$FP_ID/evaluate\"
  }"

# Set a flow rule: after calling crm.read_customer, block slack.send_message
curl -s -X POST "http://localhost:12000/npl/policies/FlowPolicy/$FP_ID/setFlowRule" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"sourceService": "crm", "sourceTool": "read_customer", "targetService": "slack", "targetTool": "send_message", "description": "Block PII exfiltration via Slack after reading customer data"}'
```

**Managing flow rules:**

```bash
# View all flow rules
curl -s -X POST "http://localhost:12000/npl/policies/FlowPolicy/$FP_ID/getFlowRules" \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d '{}'

# View session history (what tools were called in a session)
curl -s -X POST "http://localhost:12000/npl/policies/FlowPolicy/$FP_ID/getSessionHistory" \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"sessionId": "session-abc-123"}'

# Clear history for a specific session
curl -s -X POST "http://localhost:12000/npl/policies/FlowPolicy/$FP_ID/clearSessionHistory" \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"sessionId": "session-abc-123"}'

# Clear all session history
curl -s -X POST "http://localhost:12000/npl/policies/FlowPolicy/$FP_ID/clearAllHistory" \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d '{}'

# Remove a flow rule
curl -s -X POST "http://localhost:12000/npl/policies/FlowPolicy/$FP_ID/removeFlowRule" \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"sourceService": "crm", "sourceTool": "read_customer", "targetService": "slack", "targetTool": "send_message"}'

# View statistics
curl -s -X POST "http://localhost:12000/npl/policies/FlowPolicy/$FP_ID/getStatistics" \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d '{}'
```

### 6.6 IdentityPolicy (Identity Governance)

IdentityPolicy enforces identity-based governance rules on tool calls. It supports three rule types:

| Rule Type | Meaning |
|-----------|---------|
| `segregation_of_duties` | The same actor who performed `primaryVerb` on a tool cannot perform `secondaryVerb` on it. E.g., the person who creates an order cannot approve it. |
| `four_eyes` | A tool call requires that two different actors have acted on the entity (both `primaryVerb` and `secondaryVerb` must have been performed by different people). |
| `exclusive_actor` | Only the actor who first performed `primaryVerb` on an entity can subsequently perform `secondaryVerb`. |

**Setup:**

```bash
# Find the IdentityPolicy instance ID
IP_ID=$(curl -s -H "Authorization: Bearer $TOKEN" \
  "http://localhost:12000/npl/policies/IdentityPolicy/" \
  | jq -r '.[0].id')

# Register a route
curl -s -X POST "http://localhost:12000/npl/store/PolicyStore/$POLICY_ID/registerRoute" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d "{
    \"serviceName\": \"procurement\",
    \"toolName\": \"approve_order\",
    \"routeProtocol\": \"IdentityPolicy\",
    \"instanceId\": \"$IP_ID\",
    \"endpoint\": \"/npl/policies/IdentityPolicy/$IP_ID/evaluate\"
  }"

# Add a segregation of duties rule:
# the person who creates an order cannot approve it
curl -s -X POST "http://localhost:12000/npl/policies/IdentityPolicy/$IP_ID/addIdentityRule" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"serviceName": "procurement", "toolName": "approve_order", "ruleType": "segregation_of_duties", "primaryVerb": "create", "secondaryVerb": "approve"}'
```

**Managing identity rules:**

```bash
# View all identity rules
curl -s -X POST "http://localhost:12000/npl/policies/IdentityPolicy/$IP_ID/getIdentityRules" \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d '{}'

# View actor history for an entity
curl -s -X POST "http://localhost:12000/npl/policies/IdentityPolicy/$IP_ID/getActorHistory" \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"entityKey": "procurement:approve_order"}'

# Clear actor history for an entity
curl -s -X POST "http://localhost:12000/npl/policies/IdentityPolicy/$IP_ID/clearActorHistory" \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"entityKey": "procurement:approve_order"}'

# Clear all actor history
curl -s -X POST "http://localhost:12000/npl/policies/IdentityPolicy/$IP_ID/clearAllHistory" \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d '{}'

# Remove an identity rule
curl -s -X POST "http://localhost:12000/npl/policies/IdentityPolicy/$IP_ID/removeIdentityRule" \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"serviceName": "procurement", "toolName": "approve_order", "ruleType": "segregation_of_duties"}'

# View statistics
curl -s -X POST "http://localhost:12000/npl/policies/IdentityPolicy/$IP_ID/getStatistics" \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d '{}'
```

### 6.7 Route Groups (AND/OR Composition)

By default, each tool can have one contextual route. **Route Groups** allow composing multiple protocols for the same tool with AND or OR semantics. For example, a tool call might need to pass both a RateLimitPolicy AND a ConstraintPolicy, or either an ApprovalPolicy OR an automated ConstraintPolicy.

Route groups are managed on the PolicyStore:

```bash
# Add a route to a group (tool can have multiple routes)
curl -s -X POST "http://localhost:12000/npl/store/PolicyStore/$POLICY_ID/addRouteToGroup" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d "{
    \"serviceName\": \"banking\",
    \"toolName\": \"transfer_funds\",
    \"routeProtocol\": \"RateLimitPolicy\",
    \"instanceId\": \"$RL_ID\",
    \"endpoint\": \"/npl/policies/RateLimitPolicy/$RL_ID/evaluate\"
  }"

# Add a second route to the same group
curl -s -X POST "http://localhost:12000/npl/store/PolicyStore/$POLICY_ID/addRouteToGroup" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d "{
    \"serviceName\": \"banking\",
    \"toolName\": \"transfer_funds\",
    \"routeProtocol\": \"ConstraintPolicy\",
    \"instanceId\": \"$CP_ID\",
    \"endpoint\": \"/npl/policies/ConstraintPolicy/$CP_ID/evaluate\"
  }"

# Set the composition mode: "and" (all must allow) or "or" (any can allow)
curl -s -X POST "http://localhost:12000/npl/store/PolicyStore/$POLICY_ID/setRouteGroupMode" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"serviceName": "banking", "toolName": "transfer_funds", "mode": "and"}'

# Remove a route from a group
curl -s -X POST "http://localhost:12000/npl/store/PolicyStore/$POLICY_ID/removeRouteFromGroup" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"serviceName": "banking", "toolName": "transfer_funds", "routeProtocol": "ConstraintPolicy"}'
```

**Composition modes:**

| Mode | Behavior |
|------|----------|
| `and` | ALL protocols in the group must return `"allow"`. If any returns `"deny"` or `"pending"`, the request is denied/held. This is the default. |
| `or` | ANY protocol in the group returning `"allow"` is sufficient. The request is only denied if all protocols deny it. |

Route groups appear in the OPA bundle as an array of routes (instead of a single route object), with a `mode` field. OPA evaluates each route in the group and combines results according to the mode.

---

## 7. Credential Injection (Optional)

Some MCP servers need API keys or tokens to work (GitHub, Slack, etc.). The gateway supports credential injection via HashiCorp Vault.

### When to use

Use credential injection when:
- An MCP server requires environment variables like `GITHUB_TOKEN` or `SLACK_TOKEN`
- You want secrets managed centrally, not baked into Docker images
- You need per-user or per-tenant credential isolation

### Storing secrets in Vault

**Via Vault UI:** Go to `http://localhost:8200`, log in with token `dev-token`, and create secrets.

**Via CLI:**

```bash
# Store a tenant-level secret
curl -s -X POST "http://localhost:8200/v1/secret/data/tenants/acme/services/github/work" \
  -H "X-Vault-Token: dev-token" \
  -H "Content-Type: application/json" \
  -d '{"data": {"token": "ghp_your_github_token_here"}}'

# Store a user-level secret
curl -s -X POST "http://localhost:8200/v1/secret/data/tenants/acme/users/alice/github/work" \
  -H "X-Vault-Token: dev-token" \
  -H "Content-Type: application/json" \
  -d '{"data": {"token": "ghp_alice_personal_token"}}'
```

### Configuring credentials.yaml

Edit `configs/credentials.yaml` to define how Vault secrets map to environment variables:

```yaml
mode: simple
tenant: acme

credentials:
  work_github:
    vault_path: secret/data/tenants/{tenant}/users/{user}/github/work
    injection:
      type: env
      mapping:
        token: GITHUB_TOKEN

  prod_slack:
    vault_path: secret/data/tenants/{tenant}/services/slack/prod
    injection:
      type: env
      mapping:
        token: SLACK_TOKEN

  admin_db:
    vault_path: secret/data/tenants/{tenant}/services/database/admin
    injection:
      type: env
      mapping:
        username: DB_USER
        password: DB_PASS

service_defaults:
  github: work_github
  slack: prod_slack
  database: readonly_db

default_credential:
  vault_path: secret/data/tenants/{tenant}/users/{user}/default
  injection:
    type: env
    mapping:
      api_key: API_KEY
```

### Two scopes

| Scope | Vault path pattern | Use case |
|-------|-------------------|----------|
| Tenant-level | `secret/data/tenants/{tenant}/services/SERVICE/...` | Shared org-wide keys (Slack bot token, DB credentials) |
| User-level | `secret/data/tenants/{tenant}/users/{user}/SERVICE/...` | Per-user keys (personal GitHub tokens) |

Template variables `{tenant}` and `{user}` are replaced at runtime.

### How it works

1. Admin stores secrets in Vault (via TUI or Vault UI)
2. Supergateway sidecar calls the credential proxy at startup
3. Credential proxy looks up `configs/credentials.yaml`, resolves the vault path, fetches from Vault
4. Supergateway exports the values as environment variables
5. The STDIO MCP tool is spawned with those environment variables available

---

## 8. Debugging & Troubleshooting

### Inspecting OPA bundle data

View the full policy data that OPA is using:

```bash
# All policy data
curl -s http://localhost:8181/v1/data | jq .

# Just the catalog
curl -s http://localhost:8181/v1/data/catalog | jq .

# Just the grants
curl -s http://localhost:8181/v1/data/grants | jq .

# Security policy
curl -s http://localhost:8181/v1/data/security_policy | jq .

# Contextual routing
curl -s http://localhost:8181/v1/data/contextual_routing | jq .
```

### OPA decision logs

```bash
docker compose logs opa --tail 100 -f
```

Decision logs show every authorization decision with the input, result, and any errors.

### Bundle server health

```bash
curl -s http://localhost:8282/health | jq .
```

Response fields:
- `status`: `healthy`, `initializing`, or `degraded`
- `revision`: Current bundle SHA256 hash (first 16 chars)
- `bundle_age_seconds`: How old the current bundle is
- `sse_connected`: Whether SSE subscription to NPL is active
- `last_sse_event_at`: Last SSE event timestamp
- `rebuild_count` / `rebuild_error_count`: Bundle build metrics

### Envoy ext_authz stats

```bash
curl -s http://localhost:9901/stats | grep ext_authz
```

Key metrics:
- `ext_authz.denied`: Number of denied requests
- `ext_authz.ok`: Number of allowed requests
- `ext_authz.error`: Number of OPA errors (should be 0)

### Response headers for tracing

Every request through the gateway gets tracing headers in the response:

| Header | Value | When |
|--------|-------|------|
| `x-authz-reason` | Denial reason | On 403 |
| `x-sp-action` | `allow`, `deny`, `npl_evaluate` | When security policy is active |
| `x-sp-rule` | Name of matched policy rule | When security policy matched |
| `x-sp-verb` | Classified verb (get, create, etc.) | When security policy is active |
| `x-sp-labels` | Comma-separated labels | When security policy is active |
| `x-approval-id` | `APR-N` | When approval is pending |
| `x-user-id` | User identity from JWT | On every authorized request |
| `x-granted-services` | CSV of granted services | On tools/list requests |
| `x-bundle-revision` | Bundle hash | On every request |

**Example — inspecting a denied request:**

```bash
TOKEN=$(curl -s -X POST "http://localhost:11000/realms/mcpgateway/protocol/openid-connect/token" \
  -d "grant_type=password&client_id=mcpgateway&username=admin&password=Welcome123" \
  | jq -r '.access_token')

curl -v -X POST http://localhost:8000/mcp \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"gmail.send_email","arguments":{"to":"external@other.com","subject":"test"}}}'
```

Look for `x-sp-action`, `x-sp-rule`, and `x-authz-reason` in the response headers.

### Common issues

**Bundle not rebuilding:**
- Check SSE connection: `curl -s http://localhost:8282/health | jq .sse_connected`
- If `false`, restart the bundle server: `docker compose restart bundle-server`
- Check bundle server logs: `docker compose logs bundle-server --tail 50`

**403 without reason headers:**
- OPA might not be returning headers correctly. Check OPA logs for errors.
- Verify OPA gRPC ext_authz is running: `curl -s http://localhost:8181/health`
- Verify OPA has bundle data: `curl -s http://localhost:8181/v1/data/catalog | jq .`

**Security policy not taking effect:**
- Verify the policy is loaded: `curl -s http://localhost:8181/v1/data/security_policy | jq .`
- Check if the bundle was rebuilt after loading: compare `x-bundle-revision` header with `curl -s http://localhost:8282/health | jq .revision`

**Contextual routing not working (approvals, rate limits, constraints, etc.):**
- Verify the contextual route is registered: `curl -s http://localhost:8181/v1/data/contextual_routing | jq .`
- Check that the protocol singleton exists: `curl -s -H "Authorization: Bearer $TOKEN" http://localhost:12000/npl/policies/ApprovalPolicy/` (or `RateLimitPolicy`, `ConstraintPolicy`, `PreconditionPolicy`, `FlowPolicy`, `IdentityPolicy`)
- Verify the route points to the correct protocol and instance ID
- For route groups, verify the mode and all routes are registered: check `contextual_routing` for an array with `mode` field

---

## 9. Configuration Reference

| File | Purpose | Read by | When |
|------|---------|---------|------|
| `mcp-security.yaml` | Security policy (tool annotations, classifiers, rules) | TUI or API | Loaded on demand, pushed to PolicyStore |
| `configs/services.yaml` | Service definitions (cache for bulk import/export) | TUI | Import/export only; PolicyStore is authoritative |
| `configs/credentials.yaml` | Vault-to-env-var credential mappings | Credential Proxy | At supergateway startup |
| `deployments/docker-compose.yml` | Service orchestration (add backend containers here) | Docker Compose | `docker compose up` |
| `deployments/envoy/envoy-config.yaml` | Envoy routing, JWT, CORS, ext_authz config | Envoy | At startup (rarely needs editing) |
| `deployments/opa/opa-config.yaml` | OPA bundle polling and plugin config | OPA | At startup |
| `security-profiles/*.yaml` | Community tool annotation profiles | Security policy loader | When referenced in `profiles` array |
| `npl/src/main/yaml/rules.yml` | NPL party-to-role mappings (JWT claims → NPL parties) | NPL Engine | At startup (rarely needs editing) |
| `policies/mcp_authz.rego` | OPA Rego authorization policy | OPA | At startup |

> [-> Architecture Reference](ARCHITECTURE.md) for the full system topology and network design.

---

## 10. Running Tests

### OPA Rego unit tests

```bash
opa test policies/ -v
```

This runs 74 test cases covering authentication, authorization, security policies, contextual routing, and approval workflows. Tests use mock data and don't require the Docker stack.

### Integration tests

Requires the full Docker stack to be running:

```bash
cd integration-tests
./gradlew test
```

Or run via Docker Compose profile:

```bash
cd deployments
docker compose --profile test run test-runner
```

### Manual verification with curl

**Get a token:**

```bash
TOKEN=$(curl -s -X POST "http://localhost:11000/realms/mcpgateway/protocol/openid-connect/token" \
  -d "grant_type=password&client_id=mcpgateway&username=admin&password=Welcome123" \
  | jq -r '.access_token')
```

**Initialize an MCP session:**

```bash
curl -s -X POST http://localhost:8000/mcp \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"test","version":"1.0"}}}'
```

**List available tools:**

```bash
curl -s -X POST http://localhost:8000/mcp \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/list"}'
```

**Call a tool:**

```bash
curl -s -X POST http://localhost:8000/mcp \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"duckduckgo.search","arguments":{"query":"MCP protocol"}}}'
```

---

**See also:**
- [Architecture Reference](ARCHITECTURE.md) — system topology, network tiers, data flow
- [Security Policy Reference](SECURITY_POLICY_REFERENCE.md) — complete YAML schema and evaluation internals
- [OPA Policy Internals](OPA_POLICY_INTERNALS.md) — two-layer architecture, bundle structure, Rego details
- [Approval Workflow Deep Dive](APPROVAL_WORKFLOW.md) — state machine, store-and-forward, protocol API
