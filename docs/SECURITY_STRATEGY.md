# MCP Gateway Security Strategy — Enterprise Policy Enforcement for the Agent Era

## Status

**Draft** — Research and strategy captured 2026-02-15. Pending implementation.

---

## 1. The Problem: MCP Security Is Unsolved

### 1.1 The OpenClaw Wake-Up Call

OpenClaw (formerly Clawdbot/Moltbot) is an open-source AI agent with full local system access, browser control, and 100+ MCP integrations. It has become the canonical case study for MCP security failures:

- **30,000+ instances** observed online between Jan 27 - Feb 8, 2026 (Bitsight)
- **~900 malicious skills** identified on ClawHub, ~20% of all packages (Authmind)
- **Hundreds of exposed instances** with plaintext API keys, tokens, and conversation histories (Jamieson O'Reilly)
- Invariant Labs demonstrated a malicious MCP server **silently exfiltrating a user's entire WhatsApp history**
- The official GitHub MCP server was exploited via prompt injection in a public issue to leak private repo data

### 1.2 The 11 MCP Security Risks (Checkmarx Framework)

| # | Risk | Description |
|---|------|-------------|
| 1 | Prompt Injection | Malicious prompts coerce models into unintended tool calls |
| 2 | Tool Poisoning | Malicious logic hidden in tool descriptions, invisible to users |
| 3 | Confused Deputy | Privilege confusion and token misuse across users |
| 4 | Supply Chain / Rug Pulls | Compromised tools exploit trust; behavior changes after approval |
| 5 | Code-Level Vulnerabilities | Traditional SQLi, command injection, SSRF, path traversal |
| 6 | Credential Exposure | Secrets leaked through model responses, logs, tool outputs |
| 7 | Excessive Privilege | Tools granted overly broad permissions beyond necessity |
| 8 | Insecure Deserialization | Unsafe deserialization of tool responses/configs |
| 9 | Cross-Agent Context Abuse | Shared context between agents poisoned to influence behavior |
| 10 | Misconfiguration | Exposed endpoints, disabled auth, default credentials |
| 11 | Dependency Hijacking | Compromised upstream dependencies propagate through ecosystem |

### 1.3 Emerging Standards

- **OWASP Top 10 for Agentic Applications** (Dec 2025): ASI02 (Tool Misuse), ASI03 (Identity & Privilege Abuse), ASI09 (Human-Agent Trust Exploitation)
- **MCP Authorization Spec** (June/Nov 2025): Servers as OAuth 2.1 Resource Servers, but **per-tool scopes are not defined** — left to the gateway layer
- **MCP Tool Annotations** (MCP spec, Nov 2025): `readOnlyHint`, `destructiveHint`, `idempotentHint`, `openWorldHint` — the only standard tool-level classification
- **MCP Elicitation** (draft): Mechanism for servers to request structured user input mid-execution
- **SGNL MCP Profiles** (SEP-1004): Standardized capability/behavior declarations — proposed, not adopted
- **SEP-1913** (open PR): Proposes `sensitiveHint`, `privateHint`, namespaced labels — community converging on extensible label scheme

---

## 2. Competitive Landscape

### 2.1 What Exists

| Product | Policy Format | Tool-Level | Argument-Level | Human-in-the-Loop | Stateful | Open Source |
|---------|:---:|:---:|:---:|:---:|:---:|:---:|
| **Traefik Hub TBAC** | YAML CRDs + expressions | Yes | Yes (CEL-like) | No | No | No |
| **Kong AI Gateway** | YAML plugin config | Yes | No | No | No | Partial |
| **Lunar.dev MCPX** | YAML ACLs | Yes | No | No | No | No |
| **Cerbos** | YAML + CEL | Yes | Yes (CEL) | No | No | Yes |
| **Permit.io** | GUI + OPA/Cedar | Yes | Yes | LangChain adapters | Partial | Partial |
| **Acuvity/Minibridge** | Rego policies | Partial | Content only | No | No | Yes |
| **Red Hat/Kuadrant** | K8s CRDs + Rego | Yes | No | No | No | Yes |
| **SGNL** | Proprietary SaaS | Yes | Yes (context) | No | No | No |
| **Docker MCP Gateway** | CLI flags | Container-level | No | No | No | Yes |
| **Lasso Security** | JSON + natural language | Content layer | Content only | No | No | Yes |
| **agentgateway (LF)** | YAML config | Server-level | No | No | No | Yes |
| **Noumena MCP Gateway** | **YAML -> OPA -> NPL** | **Yes** | **Layer 2 (NPL)** | **Yes (NPL)** | **Yes** | **Planned** |

### 2.2 Key Gaps in the Market

1. **No two-layer architecture exists.** Every competitor is single-layer: either stateless gateway rules (Traefik, Kong, Lunar) or application-level SDK calls (Cerbos, Permit.io). Nobody separates fast-path (in-memory, ~1ms) from slow-path (stateful, ~60ms).

2. **Permit.io is the closest on human-in-the-loop** — but requires the agent framework to integrate their SDK (LangChain/LangGraph adapters). The agent must be aware of the approval system. Our approach is transparent at the gateway layer — the agent doesn't know approvals exist.

3. **Acuvity is the only other OPA/Rego user for MCP** — but they use Rego for content guardrails (prompt injection detection), not authorization. Different problem space.

4. **No community security profile ecosystem exists.** This is an open opportunity.

5. **The MCP spec doesn't define per-tool authorization.** OAuth scopes are server-level. Tool-level policy is explicitly the gateway's responsibility.

### 2.3 Our Unique Position

| Capability | Unique? | Detail                                                        |
|-----------|---------|---------------------------------------------------------------|
| Two-layer OPA architecture | Yes | Fast path (bundle, ~1ms) + slow path (NPL callout, ~60ms)     |
| NPL as stateful policy engine | Yes | Pending queues, multi-party approval, timeouts                |
| Contextual routing to policy protocols | Yes | Per-tool routing to arbitrary NPL evaluators, no Rego changes |
| SSE-triggered bundle rebuilds | Yes | Near-instant policy propagation, bounded staleness            |
| Credential isolation via Vault | Yes | 4-tier network isolation, secrets never touch Envoy/OPA       |
| PolicyStore as single source of truth | Yes | Catalog, grants, routes, and contextual state in one protocol |

---

## 3. What Our Gateway Already Solves

| Checkmarx Risk | Status | How |
|---------------|--------|-----|
| 3. Confused Deputy | **Solved** | Per-user JWT -> OPA grant check -> per-user, per-service, per-tool authorization |
| 6. Credential Exposure | **Solved** | Vault + Credential Proxy, 4-tier network isolation, secrets never touch Envoy/OPA |
| 7. Excessive Privilege | **Solved** | Per-user, per-service, per-tool grants in PolicyStore. Fail-closed default deny |
| 9. Cross-Agent Context | **Solved** | Per-session routing in mcp-aggregator, OPA checks per request |
| 10. Misconfiguration | **Mostly solved** | Fail-closed defaults, network isolation. Missing: config validation tooling |
| 1. Prompt Injection | **Layer 2 ready** | Infrastructure for argument inspection via contextual routing, no concrete policy yet |
| 2. Tool Poisoning | **Not addressed** | Need tool manifest hashing/pinning at registration |
| 4. Supply Chain / Rug Pulls | **Partial** | Catalog tracks enabled tools. Need manifest change detection |
| 5. Code-Level Vulns | Out of scope | Tool-internal; mitigated by container isolation |
| 8. Insecure Deserialization | Out of scope | Tool-internal |
| 11. Dependency Hijacking | Not addressed | Could scan MCP server images at registration |

---

## 4. Strategy: Standards-Aligned Policy with Stateful Enforcement

### 4.1 Design Principles

1. **Align with existing standards.** Use MCP tool annotations as the primary classification vocabulary. Extend with Kubernetes-style verbs and namespaced labels where the spec has gaps. Don't invent nomenclature.

2. **YAML is the developer interface.** Developers configure security in files they understand, version-controlled in their repos, reviewed in PRs. They never see Rego or NPL.

3. **NPL is invisible plumbing.** NPL powers the stateful bits (approvals, session tracking, taint graphs) but developers don't interact with it directly.

4. **Community profiles handle the long tail.** Service-specific knowledge lives in community-maintained YAML packs.

5. **Classifiers are data, not code.** Adding a new classification rule is a YAML change, not a Rego or NPL code change.

### 4.2 Nomenclature: What We Use and Why

We adopt three established vocabularies rather than inventing our own:

#### Layer A: MCP Tool Annotations (Official MCP Spec)

The MCP spec (Nov 2025) defines these annotations on every tool. They are the **only** standard classification for MCP tools and are already consumed by Claude Desktop, VS Code Copilot, and other clients:

| Annotation | Type | Default | Semantics |
|---|---|---|---|
| `readOnlyHint` | boolean | `false` | Tool does not modify its environment |
| `destructiveHint` | boolean | `true` | Tool may perform destructive updates (only meaningful when `readOnlyHint: false`) |
| `idempotentHint` | boolean | `false` | Repeated calls with same arguments have no additional effect |
| `openWorldHint` | boolean | `true` | Tool may interact with external entities |

These are **hints** provided by the MCP server. The spec explicitly says "don't blindly trust them." Our gateway consumes them as the first signal, then allows operator overrides.

**Proposed extensions we track** (not yet standard):
- `secretHint` (SEP-1560): Tool return value may contain sensitive information
- `sensitiveHint` (SEP-1913): Granular sensitivity levels for data
- `privateHint` (SEP-1913): Marks internal/private data

#### Layer B: Kubernetes-Style Verbs (for Fine-Grained Operations)

When policies need to distinguish between operation types beyond the boolean hints, we use the Kubernetes RBAC verb set — the most universally understood operation taxonomy in infrastructure:

| Verb | Semantics | MCP Tool Examples |
|---|---|---|
| `get` | View a single resource | `get_issue`, `read_email`, `get_file_contents` |
| `list` | View a list of resources | `list_channels`, `search_emails`, `list_tables` |
| `create` | Create new resources | `send_email`, `create_issue`, `push_files` |
| `update` | Modify existing resources | `update_issue`, `edit_file`, `move_card` |
| `delete` | Delete resources | `delete_email`, `drop_database`, `kubectl_delete` |

These verbs are assigned per tool in community profiles or operator overrides.

#### Layer C: Namespaced Labels (for Domain-Specific Classification)

For classifications that go beyond operations (data sensitivity, environment, audience), we use namespaced labels following the pattern emerging in SEP-1913 and consistent with Kubernetes label conventions:

| Namespace | Label | Semantics |
|---|---|---|
| `scope` | `scope:internal`, `scope:external` | Audience boundary (inside/outside org) |
| `env` | `env:production`, `env:staging` | Target environment |
| `category` | `category:communication`, `category:financial` | Business domain |
| `data` | `data:pii`, `data:confidential` | Data classification |

Labels are additive — a tool call can carry multiple labels. They're assigned by community profiles, operator overrides, or argument classifiers.

### 4.3 The Classification Hierarchy

```
1. MCP tool annotations    (from server metadata)    <- standard, consumed directly
2. Community profiles       (published YAML packs)    <- verbs + labels per tool
3. Operator overrides       (per-deployment YAML)     <- human judgment, highest authority
4. Tool-name patterns       (regex fallback)          <- convention-based, last resort
5. Argument classifiers     (field + regex, opt-in)   <- for the 10% that need context
```

Layers 1-4 cover ~90% of classification with zero argument parsing. Layer 5 is opt-in.

### 4.4 The Developer-Facing Format: `mcp-security.yaml`

```yaml
# mcp-security.yaml — checked into the project repo
version: "1.0"

# Tenant-specific values interpolated into classifier patterns
tenant:
  company_domain: acme.com
  github_org: acme-corp

# Community-maintained security profiles (optional, composable)
profiles:
  - "@openclaw/security-gmail"
  - "@openclaw/security-github"
  - "@openclaw/security-stripe"

# Operator overrides: per-tool annotations, verbs, and labels
# These take precedence over MCP server annotations and community profiles
tool_overrides:
  github:
    merge_pull_request:
      destructiveHint: true
      verb: update
      labels: [category:code]
    run_workflow:
      destructiveHint: true
      verb: create
      labels: [category:ci-cd]
  custom-internal-tool:
    run_query:
      readOnlyHint: true
      verb: list

# Argument-level classifiers (opt-in, only where same tool has variable risk)
classifiers:
  gmail:
    send_email:
      - field: to
        contains: "@acme.com"
        set_labels: [scope:internal]
      - field: to
        not_contains: "@acme.com"
        set_labels: [scope:external]

# Policies: rules referencing standard annotations + verbs + labels
# Evaluated in priority order (lowest = highest priority). First match wins.
policies:
  # Hard denials (priority 0-9)
  - name: Block secrets in outbound messages
    when:
      openWorldHint: true
      labels: [data:secret]
    match: all
    action: deny
    priority: 0

  # Approval requirements (priority 10-49)
  - name: Approve external communication
    when:
      labels: [scope:external]
      verb: create
    match: all
    action: require_approval
    approvers: [manager]
    timeout: 60m
    priority: 10

  - name: Approve destructive operations
    when:
      destructiveHint: true
    action: require_approval
    approvers: [admin]
    timeout: 60m
    priority: 20

  - name: Approve production changes
    when:
      labels: [env:production]
      readOnlyHint: false
    match: all
    action: require_approval
    approvers: [ops, admin]
    timeout: 30m
    priority: 20

  # Auto-allows (priority 50+)
  - name: Allow read-only tools
    when:
      readOnlyHint: true
    action: allow
    priority: 50

  - name: Allow internal communication
    when:
      labels: [scope:internal]
    action: allow
    priority: 50

  # Fallback
  - name: Default allow
    when: {}
    action: allow
    priority: 999
```

### 4.5 Community Security Profiles

Per-service YAML packs maintained by the community, versioned and published:

```yaml
# @openclaw/security-gmail v1.0.0
service: gmail
description: "Security profile for Gmail MCP server"

tools:
  send_email:
    # Supplement/override MCP server annotations
    readOnlyHint: false
    destructiveHint: false
    openWorldHint: true
    # Kubernetes-style verb
    verb: create
    # Namespaced labels
    labels: [category:communication]
    # Argument classifiers (use tenant template variables)
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

  delete_email:
    destructiveHint: true
    verb: delete

  batch_delete_emails:
    destructiveHint: true
    verb: delete

  search_emails:
    readOnlyHint: true
    verb: list

  read_email:
    readOnlyHint: true
    verb: get

  draft_email:
    readOnlyHint: false
    destructiveHint: false
    verb: create
    labels: [category:communication]
    classify:
      - field: to
        not_contains: "@{company_domain}"
        set_labels: [scope:external]
```

Profiles are composable: a deployment imports multiple profiles and layers operator overrides on top. When Gmail changes its argument schema, the community profile is updated once and all deployments benefit.

### 4.6 Runtime Architecture

```
Developer writes               Gateway processes             Runtime enforcement
─────────────────             ──────────────────             ────────────────────

mcp-security.yaml ──> CLI/API ──> PolicyStore ──> SSE ──> Bundle Server ──> OPA Bundle
                                    (NPL)                                       │
Community profiles ──> merged ──────┘                                           │
                                                                                v
                                                                    ┌─── OPA Layer 1 ───┐
MCP server metadata ─────────────────────────────────────────────> │  Annotations       │
  (tool annotations)                                                │  + verbs + labels  │
                                                                    │  + classifiers     │
                                                                    │  -> policy match   │
                                                                    └────────┬───────────┘
                                                                             │
                                                              policy rule ───┤── no match
                                                              matches?       │   -> allow (fast path)
                                                                             │
                                                              ┌──────────────v──────────────┐
                                                              │  action: allow  -> allow     │
                                                              │  action: deny   -> deny      │
                                                              │  action: require_approval     │
                                                              │         │                     │
                                                              │         v                     │
                                                              │  OPA Layer 2 -> NPL evaluate()│
                                                              │  -> allow / deny / pending    │
                                                              └───────────────────────────────┘
```

### 4.7 Where Each Concern Lives

| Concern | Where | Format | Who Manages It |
|---------|-------|--------|---------------|
| Service-specific classifiers | Community profiles | YAML | Open-source community |
| Deployment-specific policy | `mcp-security.yaml` | YAML | Developer, via repo + PR review |
| Tool risk annotations | MCP server metadata | JSON (MCP spec standard) | Server authors |
| Annotation/verb/label evaluation | OPA (Rego) | Rego | Ships with gateway (nobody edits) |
| Stateful decisions | NPL protocols | NPL | Ships with gateway (nobody edits) |
| Approval state | NPL on-ledger | NPL state machine | Managed via TUI / API |
| Audit trail | OPA decision logs + bundle metadata | Structured JSON | Monitoring / SIEM |

---

## 5. Contextual Policy Protocols (NPL Layer 2)

These are the stateful policies that no competitor can replicate at the gateway layer. They share a common `evaluate()` interface and operate on standard annotations + labels, never raw tool arguments.

### 5.1 Updated evaluate() Contract

```
evaluate(
    toolName:        Text,          -- the MCP tool being called
    callerIdentity:  Text,          -- user ID from JWT
    sessionId:       Text,          -- MCP session identifier
    verb:            Text,          -- k8s-style verb (get, list, create, update, delete)
    labels:          List<Text>,    -- namespaced labels (e.g., ["scope:external", "category:communication"])
    annotations:     Map<Text,Text>,-- MCP annotations (e.g., {"destructiveHint": "true"})
    argumentDigest:  Text           -- SHA-256 of raw arguments (for audit, not inspection)
) returns Text                      -- "allow" | "deny" | "pending:<approval-id>"
```

NPL receives standard vocabulary (annotations, verbs, labels) — never raw tool arguments. This makes every NPL policy protocol tool-agnostic.

### 5.2 Approval Policy

The most universally requested enterprise feature. High-risk tool calls are held pending until a human approves.

**Use cases:**
- External email requires manager approval
- Production deployments require ops approval
- Financial transactions require finance approval
- Destructive operations require admin approval

**State managed:**
- Pending approval queue (who requested what, when, with what labels)
- Approval/denial records (who approved, when, reason)
- Timeout and expiry (auto-deny after configurable timeout)

**What makes this hard without NPL:** Pending state must survive process restarts, multiple approvers may be required, timeout logic needs durable scheduling. A stateless gateway can only allow or deny — it cannot hold a request pending.

### 5.3 Session Velocity / Anomaly Detection

Runtime defense against indirect prompt injection — the attack CrowdStrike called "the most dangerous."

**Use cases:**
- Burst detection: >N calls to the same tool per minute
- Exfiltration pattern: `verb:get` on `data:confidential` then `verb:create` on `scope:external` in same session
- Novel tool usage: first-time `destructiveHint:true` tool in an elevated-risk session
- Re-authentication: require re-auth after N destructive operations

**State managed:**
- Per-session call history (tool, verb, labels, timestamp)
- Sliding window counters
- Session risk score

### 5.4 Cross-Tool Data Flow Taint Tracking

Prevents the exact attack Invariant Labs demonstrated (GitHub private repo data exfiltrated to public repo).

**Use cases:**
- Data from `scope:internal` sources cannot flow to `scope:external` sinks
- `data:pii`-tainted sessions cannot write to external services
- `data:confidential` requires approval before leaving the org boundary

**State managed:**
- Per-session taint labels (which `data:*` classifications the session has been exposed to)
- Source/sink classification per service
- Flow constraint rules

---

## 6. Default Security Catalog

The gateway ships with a default catalog covering the most common MCP services. Provides out-of-the-box security with zero configuration beyond tenant variables.

### 6.1 Tool-Name Pattern Fallback

When no community profile or operator override exists, tool-name patterns provide baseline classification. These map to MCP annotations and k8s verbs:

| Tool Name Pattern | MCP Annotation | Verb | Rationale |
|-------------------|---------------|------|-----------|
| `delete\|remove\|drop\|destroy\|purge` | `destructiveHint: true` | `delete` | Destructive operations |
| `send\|post\|reply\|forward` | `openWorldHint: true` | `create` | Outbound communication |
| `create\|insert\|add\|write\|upload\|push` | `readOnlyHint: false` | `create` | Resource creation |
| `update\|edit\|modify\|patch\|rename\|move` | `readOnlyHint: false` | `update` | Resource modification |
| `merge\|approve\|finalize\|deploy\|apply` | `destructiveHint: true, idempotentHint: false` | `update` | Irreversible operations |
| `share\|publish\|grant\|invite` | `openWorldHint: true` | `create` | Access sharing |
| `execute\|eval\|run\|exec` | `destructiveHint: true, openWorldHint: true` | `create` | Arbitrary execution |
| `list\|get\|search\|read\|describe\|fetch\|query\|find\|count` | `readOnlyHint: true` | `get` or `list` | Read-only operations |

These patterns are a **last resort** — community profiles and operator overrides always take precedence.

### 6.2 Default Policies

Policies referencing standard MCP annotations and labels:

| Rule | Condition | Action | Approvers | Timeout |
|------|----------|--------|-----------|---------|
| Block SSRF to cloud metadata | `labels: [infra:cloud-metadata]` | deny | — | — |
| Block secrets in outbound | `openWorldHint: true` + `labels: [data:secret]` | deny | — | — |
| Approve external communication | `verb: create` + `labels: [scope:external, category:communication]` | require_approval | manager | 60m |
| Approve financial actions | `labels: [category:financial]` + `readOnlyHint: false` | require_approval | finance | 24h |
| Approve production changes | `labels: [env:production]` + `readOnlyHint: false` | require_approval | ops | 30m |
| Approve destructive operations | `destructiveHint: true` | require_approval | admin | 60m |
| Approve arbitrary execution | `verb: create` + `labels: [category:exec]` | require_approval | admin | 15m |
| Allow read-only | `readOnlyHint: true` | allow | — | — |
| Allow internal scope | `labels: [scope:internal]` | allow | — | — |
| Default allow | (fallback) | allow | — | — |

### 6.3 Community Profile Coverage

Profiles to be developed for:

| Category | Services | Key Sensitive Tools |
|----------|----------|-------------------|
| Communication | Gmail, Outlook, Slack, Discord, Teams | `send_email`, `post_message`, `send_message` |
| Code/Dev | GitHub (77 tools), GitLab, Linear, Jira/Confluence | `push_files`, `merge_pull_request`, `run_workflow` |
| Cloud/Infra | Kubernetes, AWS S3, GCP, Docker | `kubectl_delete`, `kubectl_apply`, `delete_bucket` |
| Data | PostgreSQL, MongoDB, Redis, Supabase | `execute_sql`, `drop_database`, `delete-many` |
| File/Storage | Google Drive, Dropbox, Filesystem | `share_file`, `write_file`, `delete_file` |
| Finance | Stripe, Plaid | `create_refund`, `finalize_invoice`, `cancel_subscription` |
| Social | Twitter/X, LinkedIn | `post_tweet`, `create_post` |
| Browser/Web | Puppeteer, Fetch | `puppeteer_evaluate`, `fetch` (SSRF protection) |

---

## 7. Implementation Roadmap

### Step 0: Bug Fix — `tools/list` Leaks Unauthorized Tools

**Problem:** OPA Rule 2 allows all non-`tools/call` methods through without checking grants. The mcp-aggregator fans out `tools/list` to all backends and returns all tools. Users see tools they cannot call.

**Fix:** Pass the user's granted services from OPA to the mcp-aggregator via an `x-granted-services` response header. The aggregator filters `tools/list` results to only include tools from granted services.

**Files:** `policies/mcp_authz.rego`, `mcp-aggregator/server.js`

**Test:** Disable a service for a user in TUI → verify `tools/list` no longer returns that service's tools in MCP Inspector.

---

### Step 1: `mcp-security.yaml` Schema + Ingestion

**Goal:** Developers can write a YAML policy file and apply it to the gateway.

**Tasks:**
1. Define JSON Schema for `mcp-security.yaml` (validates structure, annotation names, verb vocabulary, label format)
2. Add `security_policies` and `classifiers` data structures to PolicyStore NPL protocol
3. Build CLI command or API endpoint: `POST /api/security-policy` that accepts YAML, validates, and writes to PolicyStore
4. Bundle server includes security policy data in the OPA bundle alongside catalog/grants

**Test:** Write a sample `mcp-security.yaml` → apply via CLI → verify it appears in the OPA bundle → verify `curl /health` shows policy revision.

---

### Step 2: OPA Tag Evaluation (Rego)

**Goal:** OPA classifies every `tools/call` request into MCP annotations + verb + labels before policy evaluation.

**Tasks:**
1. Add Rego rules that read tool annotations, verbs, and labels from the bundle (loaded from community profiles + operator overrides)
2. Add tool-name pattern fallback rules for unclassified tools
3. Add argument classifier evaluation (iterate over classifier rules from bundle, match against `parsed_body.params.arguments`)
4. Produce `resolved_annotations`, `resolved_verb`, `resolved_labels` variables
5. Add policy rule matching: iterate over policy rules from bundle, match conditions against resolved classification, determine action

**Test:** `opa test policies/ -v` — add test cases for:
- Tool with `readOnlyHint: true` → matches "Allow read-only" policy
- Tool with `destructiveHint: true` → matches "Approve destructive" policy
- Tool with `labels: [scope:external]` from classifier → matches "Approve external" policy
- Fallback: unclassified tool → matches default allow

---

### Step 3: NPL ApprovalPolicy Protocol

**Goal:** Stateful approval workflow — tool calls can be held pending until a human approves.

**Tasks:**
1. Write NPL `ApprovalPolicy` protocol with:
   - `evaluate(toolName, callerIdentity, sessionId, verb, labels, annotations, argumentDigest) → Text`
   - `approve(approvalId) → Text`
   - `deny(approvalId, reason) → Text`
   - `getPendingApprovals() → List<PendingApproval>`
   - Internal state: pending queue, approval records, timeout tracking
2. Register the ApprovalPolicy instance in PolicyStore contextual routes
3. OPA Layer 2: update `http.send` body to pass verb + labels + annotations instead of raw arguments

**Test:** Deploy ApprovalPolicy → register route for a test tool → call tool → verify `pending:<id>` response → approve via API → retry call → verify `allow`.

---

### Step 4: Wire End-to-End + TUI Integration

**Goal:** Complete flow from YAML policy through to approval in the TUI.

**Tasks:**
1. TUI: add "Pending Approvals" screen (list pending, approve/deny with reason)
2. TUI: add "Security Policy" screen (view active policies, import YAML, export YAML)
3. Envoy: handle `pending` response (return 403 with `Retry-After` header and `x-approval-id`)
4. Bundle metadata: include security policy version in `_bundle_metadata` for traceability

**Test:** Full end-to-end:
1. Apply `mcp-security.yaml` with "approve destructive operations" policy
2. Call a `destructiveHint: true` tool via MCP Inspector
3. Verify 403 response with approval ID
4. Approve in TUI
5. Retry the call → verify 200

---

### Step 5: Community Profile Format + Default Catalog

**Goal:** Ship default security profiles for top MCP services.

**Tasks:**
1. Define community profile YAML schema (JSON Schema)
2. Write profiles for: Gmail, GitHub, Slack, Kubernetes, PostgreSQL, Stripe (6 services)
3. Implement profile import in CLI: `mcpgw profile add @openclaw/security-gmail`
4. Implement tenant variable interpolation (`{company_domain}`, `{github_org}`)
5. Write profile for the test services in the repo (duckduckgo, mock-calendar)

**Test:** Apply Gmail profile → send email to external address → verify approval required → send to internal address → verify auto-allow.

---

### Step 6: Session Velocity Policy

**Goal:** Detect and block anomalous tool usage patterns within a session.

**Tasks:**
1. Write NPL `SessionVelocityPolicy` protocol (per-session call history, sliding window counters, burst detection)
2. Propagate MCP session ID from aggregator through to OPA (via request header)
3. Register velocity policy for high-risk services

**Test:** Call the same tool 20 times in 10 seconds → verify rate limit triggers → wait → verify calls resume.

---

### Step 7: Taint Tracking Policy

**Goal:** Prevent cross-boundary data flow (private → public exfiltration).

**Tasks:**
1. Write NPL `TaintTrackingPolicy` protocol (per-session taint labels, source/sink classification, flow rules)
2. Define source/sink labels in community profiles (`data:private-read`, `data:public-write`)

**Test:** Read from a `scope:internal` tool → attempt write to `scope:external` tool → verify deny.

---

### Step 8: Decision Audit Trail

**Goal:** Every authz decision is traceable to the exact policy revision and classifiable for compliance.

**Tasks:**
1. OPA decision logs: include resolved annotations, verb, labels, matched policy rule, and bundle revision in every log entry
2. Build audit query endpoint or forward to SIEM
3. TUI dashboard: recent decisions, error rates, approval latency

**Test:** Make 10 tool calls → query audit log → verify each entry contains policy revision, matched rule, and classification.

---

### Step 9: Packaging + Developer Experience

**Goal:** One-command setup for OpenClaw users and enterprises.

**Tasks:**
1. `Makefile` with `make up`, `make down`, `make test`, `make quickstart`
2. `.env.example` with documented defaults
3. `mcpgw` CLI: `init` (scaffold `mcp-security.yaml`), `apply` (push to PolicyStore), `audit` (query decisions), `profile add/list`
4. OpenClaw integration guide: "Add this to your OpenClaw config"
5. Docker image published to registry

**Test:** Fresh machine → `git clone` → `make quickstart` → working gateway with default security policies.

---

## 8. Positioning

### For OpenClaw Users (Personal/Developer)

"Drop `mcp-security.yaml` into your project. Get approval workflows for external communication, destructive operations, and financial actions out of the box. Standard MCP annotations. No code, no vendor lock-in."

### For Enterprise

"YAML-driven policy reviewed in PRs. OPA enforcement aligned with MCP tool annotations. NPL stateful protocols for human-in-the-loop approval, session anomaly detection, and data flow constraints. Full audit trail with decision-to-policy-revision traceability. Aligned with OWASP Agentic AI top 10."

### vs. Competitors

"Traefik gives you CEL expressions. Kong gives you ACL lists. We give you stateful policy protocols — approval workflows, session tracking, and data flow enforcement at the gateway layer, using standard MCP annotations. The agent doesn't need to know security exists."

---

## References

### Standards & Specifications
- MCP Tool Annotations Specification (Nov 2025)
- MCP Authorization Specification (June/Nov 2025)
- MCP SEP-1560: `secretHint` proposal
- MCP SEP-1913: Trust and Sensitivity Annotations proposal
- MCP SEP-1004: SGNL MCP Profiles proposal
- MCP Elicitation Draft Specification
- OWASP Top 10 for Agentic Applications (Dec 2025)
- Kubernetes RBAC Authorization
- AWS IAM Access Level Classifications

### Security Research
- Bitsight: OpenClaw Security Risks — Exposed Instances (Feb 2026)
- CrowdStrike: What Security Teams Need to Know About OpenClaw (Feb 2026)
- Invariant Labs: MCP Prompt Injection Demonstrations (2025)
- 1Password: From Magic to Malware — OpenClaw Skills Attack Surface (Feb 2026)
- JFrog: Giving OpenClaw the Keys to Your Kingdom (Feb 2026)
- Cisco: Personal AI Agents Like OpenClaw Are a Security Nightmare (Feb 2026)
- Checkmarx: 11 Emerging AI Security Risks with MCP (2025)

### Competitive Analysis
- Traefik Hub TBAC Documentation
- Kong MCP Tool ACLs
- Cerbos MCP Authorization
- Permit.io MCP Permissions + HITL for AI Agents
- Acuvity/Minibridge (GitHub)
- SGNL Securing MCP / MCP Profiles
- Lunar.dev MCPX ACLs
- Red Hat/Kuadrant MCP Gateway AuthPolicy
- agentgateway (Linux Foundation)
- Docker MCP Gateway
- Natoma: OPA vs Cedar — The Definitive Guide
