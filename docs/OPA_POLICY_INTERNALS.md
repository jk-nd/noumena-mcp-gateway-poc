> **DEPRECATED**: This document describes the v3 OPA policy internals. The v4 policy is significantly simpler (~240 lines vs ~1000 lines). See [DESIGN_V4_SIMPLIFIED_GOVERNANCE.md](DESIGN_V4_SIMPLIFIED_GOVERNANCE.md) for the current architecture.

   # OPA Rego Policy Internals

Technical reference for the OPA authorization policy (`policies/mcp_authz.rego`), bundle structure, and testing.

> This is a reference companion to the [How-To Guide](HOWTO.md). See [Architecture Reference](ARCHITECTURE.md) for the system topology.

---

## Table of Contents

1. [Two-Layer Architecture](#two-layer-architecture)
2. [Bundle Structure](#bundle-structure)
3. [How the Bundle is Built](#how-the-bundle-is-built)
4. [Rego Policy Structure](#rego-policy-structure)
5. [JWT Handling](#jwt-handling)
6. [JSON-RPC Parsing](#json-rpc-parsing)
7. [Allow Rules](#allow-rules)
8. [Security Policy Evaluation in Rego](#security-policy-evaluation-in-rego)
9. [Multi-Route AND/OR Evaluation (Route Groups)](#multi-route-andor-evaluation-route-groups)
10. [Session ID Extraction](#session-id-extraction)
11. [Value Extractors](#value-extractors)
12. [Numeric Classifier Conditions](#numeric-classifier-conditions)
13. [Envoy ext_authz gRPC Behavior](#envoy-ext_authz-grpc-behavior)
14. [Response Headers Reference](#response-headers-reference)
15. [Running and Writing Rego Tests](#running-and-writing-rego-tests)

---

## Two-Layer Architecture

OPA uses a two-layer design to balance speed and flexibility:

### Layer 1: Bundle Data (~1ms)

- Evaluates catalog, grants, and security policy from in-memory bundle data
- **Zero network I/O** — all data is pre-loaded from the OPA bundle
- Handles ~99% of requests
- Checks: service enabled? tool enabled? user granted? security policy action?

### Layer 2: NPL http.send (~60ms)

- Triggered only when a contextual route exists for the service/tool combination
- Calls the NPL Engine's `evaluate()` endpoint via `http.send()`
- Used for stateful decisions (e.g., approvals, rate limiting, constraints, preconditions, flow governance, identity governance) that can't be pre-computed
- Any protocol implementing `evaluate()` can be plugged in via contextual routing
- Handles ~1% of requests

**Layer 2 is skipped when:**
- No contextual route exists for the service/tool
- The security policy explicitly returns `allow` (the allow action overrides contextual routing)

---

## Bundle Structure

The OPA bundle is a `tar.gz` file containing `data.json` and `.manifest`. The `data.json` has this structure:

```json
{
  "catalog": {
    "duckduckgo": {
      "enabled": true,
      "enabledTools": ["search"],
      "suspended": false,
      "metadata": {"command": "docker run -i --rm mcp/duckduckgo"}
    },
    "gmail": {
      "enabled": true,
      "enabledTools": ["send_email", "read_email", "delete_email"],
      "suspended": false,
      "metadata": {}
    }
  },
  "grants": {
    "jarvis@acme.com": [
      {"serviceName": "duckduckgo", "allowedTools": ["*"]},
      {"serviceName": "gmail", "allowedTools": ["send_email", "read_email"]}
    ]
  },
  "contextual_routing": {
    "gmail": {
      "send_email": {
        "policyProtocol": "ApprovalPolicy",
        "instanceId": "abc-123",
        "endpoint": "/npl/policies/ApprovalPolicy/abc-123/evaluate"
      }
    },
    "duckduckgo": {
      "*": {
        "policyProtocol": "RateLimitPolicy",
        "instanceId": "def-456",
        "endpoint": "/npl/policies/RateLimitPolicy/def-456/evaluate"
      }
    }
  },
  "security_policy": {
    "version": "1.0",
    "tenant": {"company_domain": "acme.com"},
    "profiles": ["@openclaw/security-gmail"],
    "tool_overrides": {},
    "policies": [...]
  },
  "gateway_token": "eyJhbGciOiJSUzI1NiIs...",
  "metadata": {
    "built_at": "2025-01-15T10:30:00Z",
    "revision": "a1b2c3d4e5f6g7h8",
    "sse_event_id": "42",
    "security_policy_version": "d4e5f6a7b8c9d0e1..."
  }
}
```

### Bundle Fields

| Field | Type | Description |
|-------|------|-------------|
| `catalog` | object | Service availability: enabled/suspended status and enabled tools per service |
| `grants` | object | User access rights: maps subject ID to list of service grants |
| `contextual_routing` | object | Layer 2 routes: maps service → tool → NPL endpoint |
| `security_policy` | object | The loaded security policy (from `mcp-security.yaml`) |
| `gateway_token` | string | Bearer token for OPA → NPL http.send calls (Layer 2) |
| `metadata` | object | Build metadata for debugging and traceability |

### Catalog Entry

| Field | Type | Description |
|-------|------|-------------|
| `enabled` | boolean | Whether the service accepts requests |
| `enabledTools` | string[] | List of enabled tool names |
| `suspended` | boolean | Emergency kill switch — overrides `enabled` |
| `metadata` | object | Arbitrary key-value metadata |

### Grant Entry

| Field | Type | Description |
|-------|------|-------------|
| `serviceName` | string | Service this grant applies to |
| `allowedTools` | string[] | Specific tool names, or `["*"]` for all tools |

### Contextual Route Entry

| Field | Type | Description |
|-------|------|-------------|
| `protocolName` | string | NPL protocol name (e.g., `"ApprovalPolicy"`) |
| `instanceId` | string | NPL protocol instance ID |
| `endpoint` | string | Full HTTP path for the evaluate call |

Routes are resolved by specificity: a tool-specific route (`send_email`) takes precedence over a wildcard route (`*`).

---

## How the Bundle is Built

The bundle server (`bundle-server/server.py`) builds bundles through this process:

1. **SSE trigger**: Receives a state change event from NPL Engine's SSE stream (`/api/streams/states`)
2. **Debounce**: Waits 100ms after the last SSE signal to batch rapid changes
3. **Fetch data**: Makes 2 HTTP calls to NPL Engine:
   - `GET /npl/store/PolicyStore/` — find the singleton instance ID
   - `POST /npl/store/PolicyStore/{id}/getPolicyData` — fetch all policy data
4. **Transform**: Converts NPL response into the bundle data structure
5. **Hash**: Computes `revision` as SHA256 of the policy data (first 16 chars)
6. **Package**: Creates `tar.gz` with `data.json` + `.manifest`
7. **Serve**: OPA polls `GET /bundles/mcp/data.tar.gz` every 1-2 seconds, with ETag-based caching (304 Not Modified when unchanged)

A reconciliation loop runs every 30 seconds as a fallback to catch any missed SSE events.

---

## Rego Policy Structure

The policy is in `policies/mcp_authz.rego` under the package `envoy.authz`:

```
envoy.authz
├── result                    # Main decision object (for ext_authz gRPC)
│   ├── allowed               # Boolean: allow or deny
│   ├── response_headers_to_add  # Headers to add to upstream response
│   └── headers               # Headers to add to upstream request
├── allow                     # Boolean: computed allow decision
├── headers                   # Map: response/request headers
└── helper rules              # Internal computation rules
```

The entry point is `result` — this is what the Envoy ext_authz gRPC plugin calls (configured via `path: envoy/authz/result` in OPA config).

---

## JWT Handling

OPA extracts the JWT from the `Authorization` header:

1. Finds `Authorization: Bearer <token>` (case-insensitive header match)
2. Base64-decodes the JWT payload (no signature verification — Envoy's `jwt_authn` filter already validated the token)
3. Resolves user identity in priority order: `email` > `preferred_username` > `sub`

```rego
bearer_token := t if {
    auth_header := input.attributes.request.http.headers.authorization
    startswith(lower(auth_header), "bearer ")
    t := substring(auth_header, 7, -1)
}

jwt_payload := payload if {
    parts := split(bearer_token, ".")
    payload := json.unmarshal(base64url.decode(parts[1]))
}

user_id := jwt_payload.email           # first priority
user_id := jwt_payload.preferred_username  # fallback
user_id := jwt_payload.sub             # last resort
```

---

## JSON-RPC Parsing

OPA parses the JSON-RPC request body to extract the method and tool name:

```rego
parsed_body := json.unmarshal(input.attributes.request.http.body)
rpc_method := parsed_body.method           # e.g., "tools/call"
tool_name := parsed_body.params.name       # e.g., "duckduckgo.search"
```

### Tool Namespace Resolution

Tools are namespaced as `service.tool`:

```
"duckduckgo.search" → service: "duckduckgo", tool: "search"
"gmail.send_email"  → service: "gmail",      tool: "send_email"
```

The first dot separates the service name from the tool name.

---

## Allow Rules

OPA has three main allow paths:

### Rule 1: Stream Setup (GET /mcp)

```
Method: GET
Path: /mcp
Requires: valid JWT + at least one service grant
```

Allows SSE stream connections for users who have access to at least one service.

### Rule 2: Non-Tool Methods

```
Methods: initialize, tools/list, ping, notifications/*, completion/complete
Requires: valid JWT only
```

These methods are always allowed for authenticated users. No catalog or grant checks.

### Rule 3: Tool Calls (tools/call)

Two sub-paths depending on whether a contextual route exists:

**3a: Fast Path (no contextual route, or security policy explicitly allows)**

```
Checks: service enabled + not suspended + tool enabled + user granted + security policy
No network call
```

When the security policy action is `allow`, the fast path is taken even if a contextual route exists.

**3b: Contextual Route (Layer 2)**

```
Checks: same as 3a + NPL evaluate() via http.send
Network call to NPL Engine
```

OPA looks up the contextual route for the service/tool (or service/*), constructs the HTTP request, and calls the NPL endpoint. If the route is a route group (multiple protocols), OPA evaluates all routes according to the group's AND/OR mode (see [Multi-Route AND/OR Evaluation](#multi-route-andor-evaluation-route-groups)). The NPL response determines the final decision:

| NPL Response | OPA Decision | Typical Source |
|-------------|-------------|----------------|
| `"allow"` | Allow | ApprovalPolicy (consumed approval), RateLimitPolicy (under limit), ConstraintPolicy (within budget), PreconditionPolicy (conditions met), FlowPolicy (no flow violation), IdentityPolicy (identity rules satisfied) |
| `"deny"` | Deny | ApprovalPolicy (consumed denial), RateLimitPolicy (over limit), ConstraintPolicy (budget exceeded), PreconditionPolicy (condition not met), FlowPolicy (flow violation), IdentityPolicy (identity rule violation) |
| `"pending:APR-N"` | Deny with `x-approval-id: APR-N` header | ApprovalPolicy (awaiting human decision) |

---

## Security Policy Evaluation in Rego

When a security policy is loaded, OPA evaluates it for every `tools/call` request:

### 1. Resolve Annotations

```rego
# Look up tool annotations from security policy
tool_annotations := security_policy.profiles[service][tool]

# Apply tool_overrides (if any)
merged_annotations := object.union(tool_annotations, tool_overrides[service][tool])
```

### 2. Determine Verb

```rego
# Use explicit verb if set
verb := merged_annotations.verb

# Otherwise infer from tool name prefix
verb := "get"    if startswith(tool_name, "read_")
verb := "get"    if startswith(tool_name, "get_")
verb := "create" if startswith(tool_name, "create_")
# ... etc
```

### 3. Run Classifiers

```rego
# For each classifier on the tool
classifier_labels := {label |
    some classifier in merged_annotations.classify
    # Check condition (contains, not_contains, present)
    condition_matches(classifier, tool_arguments)
    label := classifier.set_labels[_]
}

# Merge with static labels
all_labels := merged_annotations.labels | classifier_labels
```

### 4. Match Policy Rules

```rego
# Sort rules by priority
sorted_rules := sort_by(security_policy.policies, "priority")

# Find first matching rule
matched_rule := sorted_rules[i] if {
    # Check verb condition
    (not rule.when.verb) or (rule.when.verb == verb)
    # Check label conditions
    (not rule.when.labels) or labels_match(rule, all_labels)
    # Check hint conditions
    (not rule.when.readOnlyHint) or (rule.when.readOnlyHint == annotations.readOnlyHint)
    # ... etc for each hint
}
```

### 5. Apply Action

The matched rule's `action` field determines the response:

- `allow`: Sets headers and allows the request
- `deny`: Returns deny with reason headers
- `npl_evaluate`: Checks for contextual route, may trigger Layer 2

---

## Multi-Route AND/OR Evaluation (Route Groups)

By default, each service/tool combination maps to a single contextual route. Route Groups extend this to support multiple protocols per tool, composed with AND or OR semantics.

### Bundle representation

When a tool has a route group, the `contextual_routing` entry contains an array of routes plus a `mode` field instead of a single route object:

```json
{
  "contextual_routing": {
    "banking": {
      "transfer_funds": {
        "mode": "and",
        "routes": [
          {
            "policyProtocol": "RateLimitPolicy",
            "instanceId": "rl-123",
            "endpoint": "/npl/policies/RateLimitPolicy/rl-123/evaluate"
          },
          {
            "policyProtocol": "ConstraintPolicy",
            "instanceId": "cp-456",
            "endpoint": "/npl/policies/ConstraintPolicy/cp-456/evaluate"
          }
        ]
      }
    }
  }
}
```

### Rego evaluation logic

OPA detects whether a contextual route is a single route or a route group by checking for the `routes` array field. For route groups:

- **AND mode** (`"mode": "and"`): OPA calls each protocol's `evaluate()` endpoint sequentially. All must return `"allow"` for the request to be allowed. If any returns `"deny"` or `"pending:..."`, evaluation short-circuits with that result.
- **OR mode** (`"mode": "or"`): OPA calls each protocol's `evaluate()` endpoint. If any returns `"allow"`, the request is allowed. The request is denied only if all protocols deny it. The last non-allow response is used as the denial reason.

Single routes (no `routes` array) continue to work as before — backward compatible.

---

## Session ID Extraction

Layer 2 protocols that track per-session state (FlowPolicy, RateLimitPolicy) need a session identifier. OPA extracts the session ID from the `mcp-session-id` HTTP header:

```rego
session_id := sid if {
    sid := input.attributes.request.http.headers["mcp-session-id"]
}
```

The `mcp-session-id` header is set by MCP clients during the session initialization handshake (the `initialize` JSON-RPC method response includes the session ID, and subsequent requests include it as a header). If the header is absent, OPA passes an empty string to the protocol's `evaluate()` call.

This session ID is forwarded to all Layer 2 protocol calls as the `sessionId` parameter, enabling protocols like FlowPolicy to track cross-call history within a single agent session.

---

## Value Extractors

Value extractors generate labels from tool call arguments at evaluation time, making argument values available to policy rules and contextual protocols as labels. Unlike classifiers (which test conditions and emit fixed labels), extractors read a field value and produce a label in `arg:<field>:<value>` format.

### How extractors work

When a tool annotation includes a `value_extractors` section, OPA reads the specified argument fields and generates labels:

```yaml
# In a security profile or tool_overrides
tools:
  transfer_funds:
    value_extractors:
      - field: currency
      - field: recipient_country
```

If a tool call includes `{"currency": "USD", "recipient_country": "CH"}`, OPA generates the labels:

- `arg:currency:USD`
- `arg:recipient_country:CH`

These labels are added to the tool's accumulated label set alongside static labels and classifier-generated labels. They can be referenced in policy rules:

```yaml
policies:
  - name: Block transfers to sanctioned countries
    when:
      labels: [arg:recipient_country:NK]
    action: deny
    priority: 0
```

### Extractor schema

```yaml
value_extractors:
  - field: "argument_name"    # Required. Name of the argument field to read.
```

If the field is missing from the tool call arguments, no label is generated for that extractor (it silently skips). Field values are converted to strings for label generation.

---

## Numeric Classifier Conditions

In addition to the string-based classifier conditions (`contains`, `not_contains`, `present`), classifiers support numeric comparison conditions for arguments that represent quantities, thresholds, or scores.

### Numeric condition types

| Condition | Type | Matches When |
|-----------|------|-------------|
| `greater_than` | number | The field's numeric value is greater than the specified threshold |
| `less_than` | number | The field's numeric value is less than the specified threshold |
| `equals_value` | number | The field's numeric value equals the specified value exactly |

### Examples

**Flag high-value transactions:**

```yaml
classify:
  - field: amount
    greater_than: 10000
    set_labels: [risk:high-value]
  - field: amount
    less_than: 100
    set_labels: [risk:low-value]
```

**Exact threshold match:**

```yaml
classify:
  - field: priority
    equals_value: 1
    set_labels: [priority:critical]
```

### Evaluation details

OPA attempts to parse the argument value as a number. If the value cannot be parsed as a number, the numeric condition does not match (it silently fails). This prevents errors when a field contains non-numeric data.

Numeric conditions can be combined with string conditions in the same classifier list. Each classifier entry uses exactly one condition type.

---

## Envoy ext_authz gRPC Behavior

OPA communicates with Envoy via the gRPC ext_authz protocol. The `result` object structure:

### Allow Response

```json
{
  "allowed": true,
  "headers": {
    "x-user-id": "user@acme.com",
    "x-mcp-service": "gmail"
  },
  "response_headers_to_add": {
    "x-bundle-revision": "a1b2c3d4e5f6g7h8",
    "x-sp-action": "allow",
    "x-sp-rule": "Allow read-only operations"
  }
}
```

### Deny Response

```json
{
  "allowed": false,
  "response_headers_to_add": {
    "x-authz-reason": "security policy: deny by rule 'Block BCC usage'",
    "x-sp-action": "deny",
    "x-sp-rule": "Block BCC usage",
    "x-sp-verb": "create",
    "x-sp-labels": "category:communication,data:bcc-used"
  }
}
```

### Header Types

| Field | Direction | Purpose |
|-------|-----------|---------|
| `headers` | Request → upstream | Added to the request forwarded to the backend (e.g., `x-user-id`) |
| `response_headers_to_add` | Response → client | Added to the response returned to the calling agent |

**Important:** In the Envoy ext_authz gRPC protocol, `response_headers_to_add` works differently than the REST API. Headers must be returned as part of the gRPC check response structure, not as regular HTTP response headers. The Rego policy builds this correctly in the `result` object.

---

## Response Headers Reference

| Header | Value | When Set |
|--------|-------|----------|
| `x-user-id` | User identity from JWT | Every authorized request |
| `x-mcp-service` | Resolved service name | Tool call requests |
| `x-granted-services` | CSV of service names with grants | `tools/list` requests |
| `x-bundle-revision` | Bundle SHA256 hash (16 chars) | Every request |
| `x-sp-action` | `allow`, `deny`, `npl_evaluate` | When security policy is active |
| `x-sp-rule` | Matched rule name | When security policy matched |
| `x-sp-verb` | Classified verb | When security policy is active |
| `x-sp-labels` | Comma-separated labels | When security policy is active |
| `x-approval-id` | `APR-N` | When approval is pending |
| `x-authz-reason` | Detailed denial reason | On deny (403) |

---

## Running and Writing Rego Tests

### Running Tests

```bash
opa test policies/ -v
```

This runs all `*_test.rego` files in the `policies/` directory. The test suite covers 74 cases.

### Test File Structure

Tests are in `policies/mcp_authz_test.rego` and follow this pattern:

```rego
package envoy.authz

# Test helpers — mock inputs and data

mock_input(method, path, body, auth_header) := {
    "attributes": {
        "request": {
            "http": {
                "method": method,
                "path": path,
                "body": body,
                "headers": {"authorization": auth_header}
            }
        }
    }
}

mock_catalog := {
    "duckduckgo": {
        "enabled": true,
        "enabledTools": ["search"],
        "suspended": false
    }
}

mock_grants := {
    "jarvis@acme.com": [
        {"serviceName": "duckduckgo", "allowedTools": ["*"]}
    ]
}
```

### Writing a Test

**Positive test (allow):**

```rego
test_allow_search_tool if {
    allow with input as mock_input(
        "POST", "/mcp",
        "{\"method\":\"tools/call\",\"params\":{\"name\":\"duckduckgo.search\"}}",
        mock_bearer(mock_jwt_jarvis)
    )
        with catalog as mock_catalog
        with grants as mock_grants
}
```

**Negative test (deny):**

```rego
test_deny_unknown_user if {
    not allow with input as mock_input(
        "POST", "/mcp",
        "{\"method\":\"tools/call\",\"params\":{\"name\":\"duckduckgo.search\"}}",
        mock_bearer(mock_jwt_unknown)
    )
        with catalog as mock_catalog
        with grants as mock_grants
}
```

**Header assertion:**

```rego
test_user_id_header if {
    result := headers with input as mock_input(
        "POST", "/mcp",
        "{\"method\":\"initialize\"}",
        mock_bearer(mock_jwt_jarvis)
    )
    result["x-user-id"] == "jarvis@acme.com"
}
```

### Test Categories

| Category | What It Tests |
|----------|--------------|
| Non-tool methods | initialize, tools/list, ping, notifications always allowed |
| Stream setup | GET /mcp access control |
| Namespaced tools | `service.tool` format parsing |
| Denied requests | Missing auth, unknown user, disabled service, suspended service |
| Fail-closed | Empty data denies everything |
| Wildcard grants | `*` grants all tools |
| Specific grants | Per-tool access control |
| Contextual routing | Layer 2 route lookup and fast-path logic |
| Security policy | Classification, rule matching, annotation hints |
| Approval workflow | Pending states, approval routes, npl_evaluate action |

---

**See also:**
- [How-To Guide](HOWTO.md) — step-by-step configuration walkthrough
- [Architecture Reference](ARCHITECTURE.md) — system topology and network design
- [Security Policy Reference](SECURITY_POLICY_REFERENCE.md) — YAML schema for security policies
- [Approval Workflow Deep Dive](APPROVAL_WORKFLOW.md) — approval state machine and protocol
