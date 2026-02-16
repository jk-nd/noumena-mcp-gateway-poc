# Security Policy Schema Reference

Complete reference for the `mcp-security.yaml` file format, tool annotations, classifiers, and policy rules.

> This is a reference companion to the [How-To Guide](HOWTO.md). See [Section 5](HOWTO.md#5-writing-a-security-policy-mcp-securityyaml) for a walkthrough with examples.

---

## Table of Contents

1. [Top-Level Schema](#top-level-schema)
2. [Tenant Configuration](#tenant-configuration)
3. [Profiles](#profiles)
4. [Tool Overrides](#tool-overrides)
5. [Tool Annotations](#tool-annotations)
6. [Argument Classifiers](#argument-classifiers)
7. [Value Extractors](#value-extractors)
8. [Policy Rules](#policy-rules)
9. [Verb Inference](#verb-inference)
10. [Evaluation Flow](#evaluation-flow)
11. [Community Profile Format](#community-profile-format)

---

## Top-Level Schema

```yaml
version: "1.0"                    # Required. Schema version.

tenant:                           # Required. Tenant configuration.
  company_domain: "acme.com"      # Template variable for classifiers.

profiles:                         # Optional. List of community profiles to import.
  - "@openclaw/security-gmail"

tool_overrides:                   # Optional. Override profile-provided annotations.
  service_name:
    tool_name:
      readOnlyHint: true

policies:                         # Required. List of policy rules.
  - name: "Rule name"
    when: {}
    action: allow
    priority: 0
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `version` | string | Yes | Schema version. Currently `"1.0"`. |
| `tenant` | object | Yes | Tenant-level configuration and template variables. |
| `profiles` | string[] | No | Community security profiles to import. |
| `tool_overrides` | object | No | Per-service, per-tool annotation overrides. |
| `policies` | object[] | Yes | Ordered list of policy rules. |

---

## Tenant Configuration

```yaml
tenant:
  company_domain: "acme.com"
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `company_domain` | string | Yes | Company domain. Available as `{company_domain}` in classifier templates. |

The `company_domain` value is interpolated into classifier conditions at evaluation time. For example, a classifier with `contains: "@{company_domain}"` becomes `contains: "@acme.com"`.

---

## Profiles

```yaml
profiles:
  - "@openclaw/security-gmail"
  - "@openclaw/security-github"
```

Profiles are references to community security profile files. When listed, the profile's tool annotations and classifiers are loaded and merged with the security policy.

Profile files are resolved from the `security-profiles/` directory. The naming convention is:

| Reference | File |
|-----------|------|
| `@openclaw/security-gmail` | `security-profiles/gmail.yaml` |
| `@openclaw/security-github` | `security-profiles/github.yaml` |

---

## Tool Overrides

```yaml
tool_overrides:
  gmail:
    send_email:
      destructiveHint: false
      openWorldHint: true
    delete_email:
      destructiveHint: true
```

Override specific annotation values from imported profiles. Structure: `service_name > tool_name > field: value`.

Any field from the [Tool Annotations](#tool-annotations) section can be overridden.

---

## Tool Annotations

Each tool in a security profile is annotated with the following fields:

### MCP Standard Hints

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `readOnlyHint` | boolean | `false` | Tool only reads data, never modifies anything. |
| `destructiveHint` | boolean | `false` | Tool deletes or irreversibly modifies data. |
| `openWorldHint` | boolean | `false` | Tool sends data outside the system boundary (email, API). |
| `idempotentHint` | boolean | `false` | Calling twice with same arguments produces same result. |

These are the four standard MCP tool annotation hints. They can be used as conditions in policy rules.

### Verb

| Field | Type | Values | Description |
|-------|------|--------|-------------|
| `verb` | string | `get`, `list`, `create`, `update`, `delete` | Kubernetes RBAC-style verb describing the tool's action. |

If not set explicitly, the verb is inferred from the tool name. See [Verb Inference](#verb-inference).

### Labels

| Field | Type | Description |
|-------|------|-------------|
| `labels` | string[] | Static labels assigned to the tool. Namespaced `key:value` format. |

Common label namespaces:

| Namespace | Examples | Meaning |
|-----------|----------|---------|
| `scope:` | `scope:internal`, `scope:external` | Data destination scope |
| `category:` | `category:communication`, `category:storage` | Tool functional category |
| `data:` | `data:pii`, `data:bcc-used`, `data:attachment` | Data sensitivity markers |

Labels can also be added dynamically by [Argument Classifiers](#argument-classifiers).

### Full Tool Annotation Example

```yaml
send_email:
  readOnlyHint: false
  destructiveHint: false
  openWorldHint: true
  idempotentHint: false
  verb: create
  labels: [category:communication]
  value_extractors:
    - field: to
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

The `value_extractors` section generates labels like `arg:to:user@example.com` from the actual argument values. See [Value Extractors](#value-extractors) for details.

---

## Argument Classifiers

Classifiers dynamically assign labels based on the actual arguments passed to a tool call. They make policies context-aware — the same tool can match different rules depending on its arguments.

### Classifier Structure

```yaml
classify:
  - field: "argument_name"    # Required. Name of the argument field to inspect.
    contains: "value"         # Condition: field contains this string.
    set_labels: [label:value] # Labels to add when condition matches.
```

### Condition Types

| Condition | Type | Matches When |
|-----------|------|-------------|
| `contains` | string | The field value contains the specified string |
| `not_contains` | string | The field value does NOT contain the specified string |
| `present: true` | boolean | The field exists in the tool arguments |
| `present: false` | boolean | The field does NOT exist in the tool arguments |
| `greater_than` | number | The field's numeric value is greater than the specified threshold |
| `less_than` | number | The field's numeric value is less than the specified threshold |
| `equals_value` | number | The field's numeric value equals the specified value exactly |

Only one condition type per classifier entry. Each classifier produces zero or more labels.

Numeric conditions (`greater_than`, `less_than`, `equals_value`) attempt to parse the argument value as a number. If the value cannot be parsed, the condition does not match (silently skipped).

### Template Variables

Classifier string values support template interpolation:

| Variable | Source | Example |
|----------|--------|---------|
| `{company_domain}` | `tenant.company_domain` | `contains: "@{company_domain}"` → `contains: "@acme.com"` |

### Evaluation Order

Classifiers are evaluated in order for each tool call. Labels from all matching classifiers are accumulated. A tool call can have labels from:

1. Static `labels` on the tool annotation
2. Dynamic labels from matching classifiers

All accumulated labels are available for policy rule matching.

### Examples

**Classify by recipient domain:**

```yaml
classify:
  - field: to
    contains: "@{company_domain}"
    set_labels: [scope:internal]
  - field: to
    not_contains: "@{company_domain}"
    set_labels: [scope:external]
```

**Detect BCC usage:**

```yaml
classify:
  - field: bcc
    present: true
    set_labels: [data:bcc-used]
```

**Detect missing required field:**

```yaml
classify:
  - field: approval_code
    present: false
    set_labels: [missing:approval-code]
```

**Flag high-value transactions (numeric):**

```yaml
classify:
  - field: amount
    greater_than: 10000
    set_labels: [risk:high-value]
  - field: amount
    less_than: 100
    set_labels: [risk:low-value]
```

**Critical priority match (numeric):**

```yaml
classify:
  - field: priority
    equals_value: 1
    set_labels: [priority:critical]
```

---

## Value Extractors

Value extractors read argument field values and generate labels in `arg:<field>:<value>` format. Unlike classifiers (which test conditions and emit fixed labels), extractors pass through the actual argument value as a label, making it available for policy rule matching.

### Extractor Structure

```yaml
value_extractors:
  - field: "argument_name"    # Required. Name of the argument field to read.
```

### Schema

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `field` | string | Yes | Name of the tool argument field to extract. |

### How It Works

For each extractor, OPA reads the named field from the tool call arguments and generates a label `arg:<field>:<value>`. If the field is missing, no label is generated (silently skipped). The value is converted to a string.

### Example

**Profile definition:**

```yaml
tools:
  transfer_funds:
    verb: create
    labels: [category:finance]
    value_extractors:
      - field: currency
      - field: recipient_country
    classify:
      - field: amount
        greater_than: 10000
        set_labels: [risk:high-value]
```

**Tool call arguments:** `{"currency": "USD", "recipient_country": "CH", "amount": 50000}`

**Generated labels:** `category:finance`, `arg:currency:USD`, `arg:recipient_country:CH`, `risk:high-value`

**Policy rule using extracted values:**

```yaml
policies:
  - name: Block transfers to sanctioned countries
    when:
      labels: [arg:recipient_country:NK]
    action: deny
    priority: 0

  - name: Require approval for large CHF transfers
    when:
      labels: [arg:currency:CHF, risk:high-value]
    match: all
    action: npl_evaluate
    approvers: [treasury]
    priority: 10
```

### Evaluation Order

Value extractors run after static labels are applied and in parallel with argument classifiers. All generated labels (static, classifier, extractor) are accumulated into a single set available for policy rule matching.

---

## Policy Rules

Policy rules are evaluated in priority order. The first matching rule determines the action.

### Rule Structure

```yaml
- name: "Rule name"                    # Required. Human-readable identifier.
  description: "What and why"          # Optional. Explanation for auditors.
  when:                                # Required. Matching conditions.
    verb: create                       #   Match on verb
    labels: [scope:external]           #   Match on labels
    readOnlyHint: true                 #   Match on MCP hint
    destructiveHint: false             #   Match on MCP hint
    openWorldHint: true                #   Match on MCP hint
    idempotentHint: false              #   Match on MCP hint
  match: all                           # Optional. "all" (AND) or "any" (OR). Default: "all".
  action: allow                        # Required. "allow", "deny", or "npl_evaluate".
  approvers: [admin, manager]          # Required if action is "npl_evaluate".
  timeout: 60m                         # Optional. Approval timeout (for npl_evaluate).
  priority: 10                         # Required. Lower number = higher priority.
```

### Fields Reference

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `name` | string | Yes | Rule identifier. Returned in `x-sp-rule` response header. |
| `description` | string | No | Human-readable explanation. |
| `when` | object | Yes | Conditions to match. Empty `{}` matches everything. |
| `match` | string | No | `"all"` (AND, default) or `"any"` (OR). |
| `action` | string | Yes | `"allow"`, `"deny"`, or `"npl_evaluate"`. |
| `approvers` | string[] | Conditional | Required when action is `npl_evaluate`. List of approver identities/roles. |
| `timeout` | string | No | Approval timeout duration (e.g., `"30m"`, `"1h"`). |
| `priority` | number | Yes | Evaluation order. Lowest number wins. |

### When Conditions

All fields inside `when` are optional. Only specified fields are checked.

| Condition | Type | Matches |
|-----------|------|---------|
| `verb` | string | Tool's classified verb equals this value |
| `labels` | string[] | Tool has ALL listed labels (when `match: all`) or ANY (when `match: any`) |
| `readOnlyHint` | boolean | Tool's readOnlyHint equals this value |
| `destructiveHint` | boolean | Tool's destructiveHint equals this value |
| `openWorldHint` | boolean | Tool's openWorldHint equals this value |
| `idempotentHint` | boolean | Tool's idempotentHint equals this value |

### Match Semantics

| Match | Behavior |
|-------|----------|
| `all` (default) | ALL conditions in `when` must be true for the rule to match |
| `any` | ANY condition in `when` being true is sufficient for the rule to match |

**Example with `match: all`** (AND):

```yaml
when:
  labels: [scope:external]
  openWorldHint: true
match: all
```

Matches only when the tool has the `scope:external` label AND `openWorldHint` is `true`.

**Example with `match: any`** (OR):

```yaml
when:
  destructiveHint: true
  labels: [data:pii]
match: any
```

Matches when the tool has `destructiveHint: true` OR the `data:pii` label (or both).

### Actions

| Action | Effect | Response |
|--------|--------|----------|
| `allow` | Tool call proceeds to the backend MCP server | 200 (proxied response) |
| `deny` | Tool call is blocked | 403 with `x-authz-reason` header |
| `npl_evaluate` | Tool call is delegated to the registered contextual routing protocol (Layer 2). OPA calls the NPL protocol's `evaluate()` endpoint. Returns `"allow"`, `"deny"`, or `"pending:<id>"` depending on the protocol (e.g., ApprovalPolicy returns pending, RateLimitPolicy returns allow/deny immediately). | 403 with `x-approval-id` header (if pending) or `x-authz-reason` (if denied) |

### Priority

- Rules are sorted by priority number (ascending)
- The first matching rule wins — later rules are not evaluated
- Use low numbers (0-10) for critical security blocks
- Use high numbers (50-100) for general allows
- Use 999 for the default fallback rule

**Recommended priority ranges:**

| Range | Purpose |
|-------|---------|
| 0-9 | Hard blocks (BCC, PII, compliance) |
| 10-29 | Approval requirements (external comms, destructive ops) |
| 30-49 | Conditional allows (specific scenarios) |
| 50-99 | General allows (read-only, internal, drafts) |
| 999 | Default fallback (deny or allow everything else) |

### Fallback Rule

Always include a fallback rule with `when: {}` at the highest priority number:

```yaml
# Default deny — block anything not explicitly allowed
- name: Default deny
  when: {}
  action: deny
  priority: 999
```

Or for a permissive default:

```yaml
# Default allow — permit anything not explicitly blocked
- name: Default allow
  when: {}
  action: allow
  priority: 999
```

---

## Verb Inference

When a tool annotation doesn't specify an explicit `verb`, OPA infers one from the tool name using prefix matching:

| Tool Name Prefix | Inferred Verb |
|-----------------|---------------|
| `read_`, `get_` | `get` |
| `list_`, `search_`, `fetch_` | `get` |
| `download_` | `get` |
| `create_`, `send_`, `add_` | `create` |
| `draft_`, `compose_` | `create` |
| `update_`, `edit_`, `modify_` | `update` |
| `batch_modify_` | `update` |
| `delete_`, `remove_`, `revoke_` | `delete` |
| `batch_delete_` | `delete` |

If no prefix matches, the verb defaults to an empty string and verb-based conditions in policy rules won't match.

---

## Evaluation Flow

When OPA evaluates a security policy for a tool call, the following steps occur:

### Step 1: Classify

1. Look up the tool's annotations from the security policy (profile + overrides)
2. Apply the tool's static labels
3. Determine the verb (explicit or inferred from tool name)
4. Run argument classifiers against the actual tool call arguments
5. Accumulate all labels (static + dynamic)

### Step 2: Match

1. Sort policy rules by priority (ascending)
2. For each rule, check if the `when` conditions match:
   - **Verb check:** If `when.verb` is set, does the tool's verb match?
   - **Label check:** If `when.labels` is set, does the tool have the required labels?
   - **Hint checks:** If any hint is set in `when`, does the tool's hint match?
3. Apply match semantics (`all` or `any`)
4. The first matching rule wins

### Step 3: Decide

Based on the matched rule's action:

- **`allow`**: OPA returns an allow decision. The tool call proceeds.
- **`deny`**: OPA returns a deny decision with the rule name in `x-sp-rule` and reason in `x-authz-reason`.
- **`npl_evaluate`**: OPA checks for a contextual route. If found, calls NPL Engine's `evaluate()` endpoint (Layer 2). Returns either `pending:APR-N` (first time) or `allow`/`deny` (after human decision).

### Response Headers

Every security policy evaluation sets these response headers:

| Header | Value |
|--------|-------|
| `x-sp-action` | The matched rule's action (`allow`, `deny`, `npl_evaluate`) |
| `x-sp-rule` | The matched rule's `name` field |
| `x-sp-verb` | The classified verb for the tool call |
| `x-sp-labels` | Comma-separated list of all accumulated labels |

---

## Community Profile Format

Community security profiles define tool annotations for a specific MCP service.

### File Structure

```yaml
service: gmail                     # Required. Service name (must match registered name).
description: >                     # Optional. Profile description.
  Security profile for Gmail MCP server

tools:                             # Required. Map of tool name → annotations.
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
```

### Profile Fields

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `service` | string | Yes | Service name. Must match the service name registered in the PolicyStore. |
| `description` | string | No | Human-readable description of what this profile covers. |
| `tools` | object | Yes | Map of tool names to their annotations. |

Each tool entry follows the [Tool Annotations](#tool-annotations) schema.

### Writing a New Profile

1. Create a file in `security-profiles/` named after the service (e.g., `slack.yaml`)
2. Set the `service` field to the registered service name
3. List every tool with appropriate hints, verb, labels, and classifiers
4. Test by loading a security policy that references the profile

**Tips:**
- Mark all read-only tools with `readOnlyHint: true` — this enables blanket "allow read-only" rules
- Use `openWorldHint: true` for any tool that sends data outside the system
- Add classifiers for fields that determine scope (internal vs. external, sensitive vs. non-sensitive)
- Use verbs consistently: `get` for reads, `create` for new entities, `update` for modifications, `delete` for removals

---

**See also:**
- [How-To Guide](HOWTO.md) — step-by-step configuration walkthrough
- [OPA Policy Internals](OPA_POLICY_INTERNALS.md) — how security policies are evaluated in Rego
- [Approval Workflow Deep Dive](APPROVAL_WORKFLOW.md) — what happens when `npl_evaluate` fires
