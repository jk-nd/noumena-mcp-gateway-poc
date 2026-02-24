# Unit tests for MCP Gateway v5 authorization policy
#
# Run with: opa test policies/ -v
#
# Tests the three-tier governance model:
#   Tier 1 — Access (catalog + access rules)
#   Tier 2 — Guardrails (constraints + allowlists, in-memory)
#   Tier 3 — Workflow (approval state machine, NPL call)

package envoy.authz

import rego.v1

# --- Helper: build ext_authz input ---

mock_input(method, path, auth_header, body) := {"attributes": {"request": {"http": {
	"method": method,
	"path": path,
	"headers": {"authorization": auth_header},
	"body": body,
}}}}

mock_input_no_auth(method, path, body) := {"attributes": {"request": {"http": {
	"method": method,
	"path": path,
	"headers": {},
	"body": body,
}}}}

# Test JWTs (HS256 with dummy key — Envoy already validated signature)

mock_jwt_jarvis := token if {
	header := {"alg": "HS256", "typ": "JWT"}
	payload := {
		"sub": "04c28d5a-7ac3-4ce8-b51e-04b4a36ba4d2",
		"email": "jarvis@acme.com",
		"preferred_username": "jarvis",
		"organization": "acme",
		"department": "sales",
		"role": "user",
	}
	token := io.jwt.encode_sign(header, payload, {"kty": "oct", "k": "dGVzdC1zZWNyZXQta2V5LWZvci1vcGEtdW5pdC10ZXN0cw"})
}

mock_jwt_alice := token if {
	header := {"alg": "HS256", "typ": "JWT"}
	payload := {
		"sub": "64d75a3e-25b1-4455-ab7c-21aee1259ed3",
		"email": "alice@acme.com",
		"preferred_username": "alice",
		"organization": "acme",
		"department": "engineering",
		"role": "user",
	}
	token := io.jwt.encode_sign(header, payload, {"kty": "oct", "k": "dGVzdC1zZWNyZXQta2V5LWZvci1vcGEtdW5pdC10ZXN0cw"})
}

mock_jwt_unknown := token if {
	header := {"alg": "HS256", "typ": "JWT"}
	payload := {
		"sub": "unknown-user-id",
		"email": "unknown@external.com",
		"preferred_username": "unknown",
		"organization": "external",
		"department": "none",
		"role": "user",
	}
	token := io.jwt.encode_sign(header, payload, {"kty": "oct", "k": "dGVzdC1zZWNyZXQta2V5LWZvci1vcGEtdW5pdC10ZXN0cw"})
}

mock_bearer(jwt) := sprintf("Bearer %s", [jwt])

# --- Mock v5 catalog (services + tools, no tags) ---

mock_catalog := {
	"mock-calendar": {
		"enabled": true,
		"tools": {
			"list_events": {},
			"create_event": {},
			"send_email": {},
		},
	},
	"duckduckgo": {
		"enabled": true,
		"tools": {
			"search": {},
			"fetch_page": {},
		},
	},
}

# --- Mock v5 access rules ---

mock_access_rules := [
	{
		"id": "sales-calendar",
		"matcher": {"matchType": "claims", "claims": {"organization": "acme", "department": "sales"}, "identity": ""},
		"allow": {"services": ["mock-calendar"], "tools": ["*"]},
	},
	{
		"id": "engineering-all",
		"matcher": {"matchType": "claims", "claims": {"organization": "acme", "department": "engineering"}, "identity": ""},
		"allow": {"services": ["mock-calendar", "duckduckgo"], "tools": ["*"]},
	},
	{
		"id": "jarvis-duckduckgo",
		"matcher": {"matchType": "identity", "claims": {}, "identity": "jarvis@acme.com"},
		"allow": {"services": ["duckduckgo"], "tools": ["search"]},
	},
]

mock_revoked := []

# --- Mock governance data (empty defaults for most tests) ---

mock_guardrails_empty := {}

mock_workflow_config_empty := {}

mock_workflow_instances_empty := {}

mock_tool_authorizations_empty := []

# ============================================================================
# 1. Non-tool-call methods (allow if authenticated)
# ============================================================================

test_allow_initialize if {
	allow with input as mock_input(
		"POST", "/mcp",
		mock_bearer(mock_jwt_jarvis),
		"{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}",
	)
		with catalog as mock_catalog
		with access_rules as mock_access_rules
		with revoked_subjects as mock_revoked
}

test_allow_tools_list if {
	allow with input as mock_input(
		"POST", "/mcp",
		mock_bearer(mock_jwt_jarvis),
		"{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}",
	)
		with catalog as mock_catalog
		with access_rules as mock_access_rules
		with revoked_subjects as mock_revoked
}

test_allow_ping if {
	allow with input as mock_input(
		"POST", "/mcp",
		mock_bearer(mock_jwt_jarvis),
		"{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"ping\"}",
	)
		with catalog as mock_catalog
		with access_rules as mock_access_rules
		with revoked_subjects as mock_revoked
}

test_allow_notifications if {
	allow with input as mock_input(
		"POST", "/mcp",
		mock_bearer(mock_jwt_jarvis),
		"{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}",
	)
		with catalog as mock_catalog
		with access_rules as mock_access_rules
		with revoked_subjects as mock_revoked
}

# ============================================================================
# 2. Catalog checks: missing service/tool, disabled service
# ============================================================================

test_deny_tool_call_missing_service if {
	not allow with input as mock_input(
		"POST", "/mcp",
		mock_bearer(mock_jwt_jarvis),
		"{\"jsonrpc\":\"2.0\",\"id\":10,\"method\":\"tools/call\",\"params\":{\"name\":\"github__create_issue\",\"arguments\":{}}}",
	)
		with catalog as mock_catalog
		with access_rules as mock_access_rules
		with revoked_subjects as mock_revoked
}

test_deny_tool_call_missing_tool if {
	not allow with input as mock_input(
		"POST", "/mcp",
		mock_bearer(mock_jwt_jarvis),
		"{\"jsonrpc\":\"2.0\",\"id\":11,\"method\":\"tools/call\",\"params\":{\"name\":\"mock-calendar__delete_event\",\"arguments\":{}}}",
	)
		with catalog as mock_catalog
		with access_rules as mock_access_rules
		with revoked_subjects as mock_revoked
}

test_deny_tool_call_disabled_service if {
	disabled_catalog := {
		"mock-calendar": {
			"enabled": false,
			"tools": {"list_events": {}},
		},
	}

	not allow with input as mock_input(
		"POST", "/mcp",
		mock_bearer(mock_jwt_jarvis),
		"{\"jsonrpc\":\"2.0\",\"id\":12,\"method\":\"tools/call\",\"params\":{\"name\":\"mock-calendar__list_events\",\"arguments\":{}}}",
	)
		with catalog as disabled_catalog
		with access_rules as mock_access_rules
		with revoked_subjects as mock_revoked
}

test_deny_tool_call_empty_catalog if {
	not allow with input as mock_input(
		"POST", "/mcp",
		mock_bearer(mock_jwt_jarvis),
		"{\"jsonrpc\":\"2.0\",\"id\":13,\"method\":\"tools/call\",\"params\":{\"name\":\"mock-calendar__list_events\",\"arguments\":{}}}",
	)
		with catalog as {}
		with access_rules as mock_access_rules
		with revoked_subjects as mock_revoked
}

# ============================================================================
# 3. Access rules: claim match, identity match, wildcard, no match, OR, revoked
# ============================================================================

# Jarvis (sales) matches "sales-calendar" rule -> allowed on mock-calendar
test_access_rule_claim_match if {
	allow with input as mock_input(
		"POST", "/mcp",
		mock_bearer(mock_jwt_jarvis),
		"{\"jsonrpc\":\"2.0\",\"id\":20,\"method\":\"tools/call\",\"params\":{\"name\":\"mock-calendar__list_events\",\"arguments\":{}}}",
	)
		with catalog as mock_catalog
		with access_rules as mock_access_rules
		with revoked_subjects as mock_revoked
}

# Jarvis matches identity rule for duckduckgo__search
test_access_rule_identity_match if {
	allow with input as mock_input(
		"POST", "/mcp",
		mock_bearer(mock_jwt_jarvis),
		"{\"jsonrpc\":\"2.0\",\"id\":21,\"method\":\"tools/call\",\"params\":{\"name\":\"duckduckgo__search\",\"arguments\":{\"query\":\"test\"}}}",
	)
		with catalog as mock_catalog
		with access_rules as mock_access_rules
		with revoked_subjects as mock_revoked
}

# Alice (engineering) matches "engineering-all" -> allowed on duckduckgo
test_access_rule_wildcard_tool if {
	allow with input as mock_input(
		"POST", "/mcp",
		mock_bearer(mock_jwt_alice),
		"{\"jsonrpc\":\"2.0\",\"id\":22,\"method\":\"tools/call\",\"params\":{\"name\":\"duckduckgo__search\",\"arguments\":{\"query\":\"test\"}}}",
	)
		with catalog as mock_catalog
		with access_rules as mock_access_rules
		with revoked_subjects as mock_revoked
}

# Unknown user (external org) -> no matching rule -> denied
test_access_rule_no_match if {
	not allow with input as mock_input(
		"POST", "/mcp",
		mock_bearer(mock_jwt_unknown),
		"{\"jsonrpc\":\"2.0\",\"id\":23,\"method\":\"tools/call\",\"params\":{\"name\":\"mock-calendar__list_events\",\"arguments\":{}}}",
	)
		with catalog as mock_catalog
		with access_rules as mock_access_rules
		with revoked_subjects as mock_revoked
}

# Jarvis identity-rule only allows search, not fetch_page -> denied
test_access_rule_identity_tool_mismatch if {
	identity_only_rules := [
		{
			"id": "jarvis-duckduckgo",
			"matcher": {"matchType": "identity", "claims": {}, "identity": "jarvis@acme.com"},
			"allow": {"services": ["duckduckgo"], "tools": ["search"]},
		},
	]

	not allow with input as mock_input(
		"POST", "/mcp",
		mock_bearer(mock_jwt_jarvis),
		"{\"jsonrpc\":\"2.0\",\"id\":24,\"method\":\"tools/call\",\"params\":{\"name\":\"duckduckgo__fetch_page\",\"arguments\":{\"url\":\"http://example.com\"}}}",
	)
		with catalog as mock_catalog
		with access_rules as identity_only_rules
		with revoked_subjects as mock_revoked
}

# Multiple rules OR semantics — any match allows
test_access_rule_or_semantics if {
	allow with input as mock_input(
		"POST", "/mcp",
		mock_bearer(mock_jwt_jarvis),
		"{\"jsonrpc\":\"2.0\",\"id\":25,\"method\":\"tools/call\",\"params\":{\"name\":\"mock-calendar__list_events\",\"arguments\":{}}}",
	)
		with catalog as mock_catalog
		with access_rules as mock_access_rules
		with revoked_subjects as mock_revoked
}

# Revoked subject -> denied even with matching access rule
test_revoked_subject_denied if {
	not allow with input as mock_input(
		"POST", "/mcp",
		mock_bearer(mock_jwt_jarvis),
		"{\"jsonrpc\":\"2.0\",\"id\":26,\"method\":\"tools/call\",\"params\":{\"name\":\"mock-calendar__list_events\",\"arguments\":{}}}",
	)
		with catalog as mock_catalog
		with access_rules as mock_access_rules
		with revoked_subjects as ["jarvis@acme.com"]
}

# ============================================================================
# 4. Simple tool path: no guardrails, no workflow → access sufficient
# ============================================================================

test_simple_tool_allowed if {
	allow with input as mock_input(
		"POST", "/mcp",
		mock_bearer(mock_jwt_jarvis),
		"{\"jsonrpc\":\"2.0\",\"id\":30,\"method\":\"tools/call\",\"params\":{\"name\":\"mock-calendar__list_events\",\"arguments\":{\"date\":\"2026-02-14\"}}}",
	)
		with catalog as mock_catalog
		with access_rules as mock_access_rules
		with revoked_subjects as mock_revoked
}

test_simple_tool_denied_no_rule if {
	not allow with input as mock_input(
		"POST", "/mcp",
		mock_bearer(mock_jwt_unknown),
		"{\"jsonrpc\":\"2.0\",\"id\":31,\"method\":\"tools/call\",\"params\":{\"name\":\"mock-calendar__list_events\",\"arguments\":{}}}",
	)
		with catalog as mock_catalog
		with access_rules as mock_access_rules
		with revoked_subjects as mock_revoked
}

# ============================================================================
# 5. Tool with workflow: approval paths
# ============================================================================

# No guardrails, no workflow → allow (simple tool)
test_tool_no_governance_allow if {
	allow with input as mock_input(
		"POST", "/mcp",
		mock_bearer(mock_jwt_jarvis),
		"{\"jsonrpc\":\"2.0\",\"id\":40,\"method\":\"tools/call\",\"params\":{\"name\":\"mock-calendar__create_event\",\"arguments\":{\"title\":\"test\"}}}",
	)
		with catalog as mock_catalog
		with access_rules as mock_access_rules
		with revoked_subjects as mock_revoked
		with guardrails as mock_guardrails_empty
		with workflow_config as mock_workflow_config_empty
		with workflow_instances as mock_workflow_instances_empty
		with tool_authorizations as mock_tool_authorizations_empty
}

# Workflow returns allow -> allowed
test_tool_workflow_allow if {
	allow with input as mock_input(
		"POST", "/mcp",
		mock_bearer(mock_jwt_jarvis),
		"{\"jsonrpc\":\"2.0\",\"id\":41,\"method\":\"tools/call\",\"params\":{\"name\":\"mock-calendar__create_event\",\"arguments\":{\"title\":\"test\"}}}",
	)
		with catalog as mock_catalog
		with access_rules as mock_access_rules
		with revoked_subjects as mock_revoked
		with guardrails as mock_guardrails_empty
		with workflow_config as {"mock-calendar": {"create_event": true}}
		with workflow_instances as {"mock-calendar": "wf-1"}
		with tool_authorizations as mock_tool_authorizations_empty
		with npl_workflow_decision as {"decision": "allow", "requestId": "REQ-1", "message": "Approved"}
}

# Workflow returns deny -> denied
test_tool_workflow_deny if {
	not allow with input as mock_input(
		"POST", "/mcp",
		mock_bearer(mock_jwt_jarvis),
		"{\"jsonrpc\":\"2.0\",\"id\":42,\"method\":\"tools/call\",\"params\":{\"name\":\"mock-calendar__create_event\",\"arguments\":{\"title\":\"test\"}}}",
	)
		with catalog as mock_catalog
		with access_rules as mock_access_rules
		with revoked_subjects as mock_revoked
		with guardrails as mock_guardrails_empty
		with workflow_config as {"mock-calendar": {"create_event": true}}
		with workflow_instances as {"mock-calendar": "wf-1"}
		with tool_authorizations as mock_tool_authorizations_empty
		with npl_workflow_decision as {"decision": "deny", "requestId": "REQ-1", "message": "Not allowed"}
}

# Workflow returns pending -> denied
test_tool_workflow_pending if {
	not allow with input as mock_input(
		"POST", "/mcp",
		mock_bearer(mock_jwt_jarvis),
		"{\"jsonrpc\":\"2.0\",\"id\":43,\"method\":\"tools/call\",\"params\":{\"name\":\"mock-calendar__create_event\",\"arguments\":{\"title\":\"test\"}}}",
	)
		with catalog as mock_catalog
		with access_rules as mock_access_rules
		with revoked_subjects as mock_revoked
		with guardrails as mock_guardrails_empty
		with workflow_config as {"mock-calendar": {"create_event": true}}
		with workflow_instances as {"mock-calendar": "wf-1"}
		with tool_authorizations as mock_tool_authorizations_empty
		with npl_workflow_decision as {"decision": "pending", "requestId": "REQ-1", "message": "Awaiting approval"}
}

# ============================================================================
# 6. Constraint operators — in, not_in, contains, not_contains, regex, max_length
# ============================================================================

# --- "in" operator ---

test_constraint_in_pass if {
	allow with input as mock_input(
		"POST", "/mcp",
		mock_bearer(mock_jwt_jarvis),
		"{\"jsonrpc\":\"2.0\",\"id\":50,\"method\":\"tools/call\",\"params\":{\"name\":\"mock-calendar__create_event\",\"arguments\":{\"priority\":\"high\"}}}",
	)
		with catalog as mock_catalog
		with access_rules as mock_access_rules
		with revoked_subjects as mock_revoked
		with guardrails as {"mock-calendar": {"create_event": {"constraints": [{"paramName": "priority", "operator": "in", "values": ["high", "medium", "low"], "description": "Must be valid priority"}], "allowlists": []}}}
		with workflow_config as mock_workflow_config_empty
		with workflow_instances as mock_workflow_instances_empty
		with tool_authorizations as mock_tool_authorizations_empty
}

test_constraint_in_fail if {
	not allow with input as mock_input(
		"POST", "/mcp",
		mock_bearer(mock_jwt_jarvis),
		"{\"jsonrpc\":\"2.0\",\"id\":51,\"method\":\"tools/call\",\"params\":{\"name\":\"mock-calendar__create_event\",\"arguments\":{\"priority\":\"critical\"}}}",
	)
		with catalog as mock_catalog
		with access_rules as mock_access_rules
		with revoked_subjects as mock_revoked
		with guardrails as {"mock-calendar": {"create_event": {"constraints": [{"paramName": "priority", "operator": "in", "values": ["high", "medium", "low"], "description": "Must be valid priority"}], "allowlists": []}}}
		with workflow_config as mock_workflow_config_empty
		with workflow_instances as mock_workflow_instances_empty
		with tool_authorizations as mock_tool_authorizations_empty
}

# --- "not_in" operator ---

test_constraint_not_in_pass if {
	allow with input as mock_input(
		"POST", "/mcp",
		mock_bearer(mock_jwt_jarvis),
		"{\"jsonrpc\":\"2.0\",\"id\":52,\"method\":\"tools/call\",\"params\":{\"name\":\"mock-calendar__create_event\",\"arguments\":{\"category\":\"work\"}}}",
	)
		with catalog as mock_catalog
		with access_rules as mock_access_rules
		with revoked_subjects as mock_revoked
		with guardrails as {"mock-calendar": {"create_event": {"constraints": [{"paramName": "category", "operator": "not_in", "values": ["secret", "classified"], "description": "No secret categories"}], "allowlists": []}}}
		with workflow_config as mock_workflow_config_empty
		with workflow_instances as mock_workflow_instances_empty
		with tool_authorizations as mock_tool_authorizations_empty
}

test_constraint_not_in_fail if {
	not allow with input as mock_input(
		"POST", "/mcp",
		mock_bearer(mock_jwt_jarvis),
		"{\"jsonrpc\":\"2.0\",\"id\":53,\"method\":\"tools/call\",\"params\":{\"name\":\"mock-calendar__create_event\",\"arguments\":{\"category\":\"secret\"}}}",
	)
		with catalog as mock_catalog
		with access_rules as mock_access_rules
		with revoked_subjects as mock_revoked
		with guardrails as {"mock-calendar": {"create_event": {"constraints": [{"paramName": "category", "operator": "not_in", "values": ["secret", "classified"], "description": "No secret categories"}], "allowlists": []}}}
		with workflow_config as mock_workflow_config_empty
		with workflow_instances as mock_workflow_instances_empty
		with tool_authorizations as mock_tool_authorizations_empty
}

# --- "contains" operator ---

test_constraint_contains_pass if {
	allow with input as mock_input(
		"POST", "/mcp",
		mock_bearer(mock_jwt_jarvis),
		"{\"jsonrpc\":\"2.0\",\"id\":54,\"method\":\"tools/call\",\"params\":{\"name\":\"mock-calendar__create_event\",\"arguments\":{\"attendees\":\"alice@acme.com\"}}}",
	)
		with catalog as mock_catalog
		with access_rules as mock_access_rules
		with revoked_subjects as mock_revoked
		with guardrails as {"mock-calendar": {"create_event": {"constraints": [{"paramName": "attendees", "operator": "contains", "values": ["@acme.com"], "description": "Must be Acme email"}], "allowlists": []}}}
		with workflow_config as mock_workflow_config_empty
		with workflow_instances as mock_workflow_instances_empty
		with tool_authorizations as mock_tool_authorizations_empty
}

test_constraint_contains_fail if {
	not allow with input as mock_input(
		"POST", "/mcp",
		mock_bearer(mock_jwt_jarvis),
		"{\"jsonrpc\":\"2.0\",\"id\":55,\"method\":\"tools/call\",\"params\":{\"name\":\"mock-calendar__create_event\",\"arguments\":{\"attendees\":\"bob@evil.com\"}}}",
	)
		with catalog as mock_catalog
		with access_rules as mock_access_rules
		with revoked_subjects as mock_revoked
		with guardrails as {"mock-calendar": {"create_event": {"constraints": [{"paramName": "attendees", "operator": "contains", "values": ["@acme.com"], "description": "Must be Acme email"}], "allowlists": []}}}
		with workflow_config as mock_workflow_config_empty
		with workflow_instances as mock_workflow_instances_empty
		with tool_authorizations as mock_tool_authorizations_empty
}

# --- "not_contains" operator ---

test_constraint_not_contains_pass if {
	allow with input as mock_input(
		"POST", "/mcp",
		mock_bearer(mock_jwt_jarvis),
		"{\"jsonrpc\":\"2.0\",\"id\":56,\"method\":\"tools/call\",\"params\":{\"name\":\"mock-calendar__create_event\",\"arguments\":{\"notes\":\"regular meeting\"}}}",
	)
		with catalog as mock_catalog
		with access_rules as mock_access_rules
		with revoked_subjects as mock_revoked
		with guardrails as {"mock-calendar": {"create_event": {"constraints": [{"paramName": "notes", "operator": "not_contains", "values": ["confidential", "restricted"], "description": "No sensitive content"}], "allowlists": []}}}
		with workflow_config as mock_workflow_config_empty
		with workflow_instances as mock_workflow_instances_empty
		with tool_authorizations as mock_tool_authorizations_empty
}

test_constraint_not_contains_fail if {
	not allow with input as mock_input(
		"POST", "/mcp",
		mock_bearer(mock_jwt_jarvis),
		"{\"jsonrpc\":\"2.0\",\"id\":57,\"method\":\"tools/call\",\"params\":{\"name\":\"mock-calendar__create_event\",\"arguments\":{\"notes\":\"this is confidential info\"}}}",
	)
		with catalog as mock_catalog
		with access_rules as mock_access_rules
		with revoked_subjects as mock_revoked
		with guardrails as {"mock-calendar": {"create_event": {"constraints": [{"paramName": "notes", "operator": "not_contains", "values": ["confidential", "restricted"], "description": "No sensitive content"}], "allowlists": []}}}
		with workflow_config as mock_workflow_config_empty
		with workflow_instances as mock_workflow_instances_empty
		with tool_authorizations as mock_tool_authorizations_empty
}

# --- "regex" operator ---

test_constraint_regex_pass if {
	allow with input as mock_input(
		"POST", "/mcp",
		mock_bearer(mock_jwt_jarvis),
		"{\"jsonrpc\":\"2.0\",\"id\":58,\"method\":\"tools/call\",\"params\":{\"name\":\"mock-calendar__create_event\",\"arguments\":{\"email\":\"user@acme.com\"}}}",
	)
		with catalog as mock_catalog
		with access_rules as mock_access_rules
		with revoked_subjects as mock_revoked
		with guardrails as {"mock-calendar": {"create_event": {"constraints": [{"paramName": "email", "operator": "regex", "values": ["^[a-zA-Z0-9._%+-]+@acme\\.com$"], "description": "Must be Acme email"}], "allowlists": []}}}
		with workflow_config as mock_workflow_config_empty
		with workflow_instances as mock_workflow_instances_empty
		with tool_authorizations as mock_tool_authorizations_empty
}

test_constraint_regex_fail if {
	not allow with input as mock_input(
		"POST", "/mcp",
		mock_bearer(mock_jwt_jarvis),
		"{\"jsonrpc\":\"2.0\",\"id\":59,\"method\":\"tools/call\",\"params\":{\"name\":\"mock-calendar__create_event\",\"arguments\":{\"email\":\"user@evil.com\"}}}",
	)
		with catalog as mock_catalog
		with access_rules as mock_access_rules
		with revoked_subjects as mock_revoked
		with guardrails as {"mock-calendar": {"create_event": {"constraints": [{"paramName": "email", "operator": "regex", "values": ["^[a-zA-Z0-9._%+-]+@acme\\.com$"], "description": "Must be Acme email"}], "allowlists": []}}}
		with workflow_config as mock_workflow_config_empty
		with workflow_instances as mock_workflow_instances_empty
		with tool_authorizations as mock_tool_authorizations_empty
}

# --- "max_length" operator ---

test_constraint_max_length_pass if {
	allow with input as mock_input(
		"POST", "/mcp",
		mock_bearer(mock_jwt_jarvis),
		"{\"jsonrpc\":\"2.0\",\"id\":60,\"method\":\"tools/call\",\"params\":{\"name\":\"mock-calendar__create_event\",\"arguments\":{\"title\":\"Short\"}}}",
	)
		with catalog as mock_catalog
		with access_rules as mock_access_rules
		with revoked_subjects as mock_revoked
		with guardrails as {"mock-calendar": {"create_event": {"constraints": [{"paramName": "title", "operator": "max_length", "values": ["50"], "description": "Title max 50 chars"}], "allowlists": []}}}
		with workflow_config as mock_workflow_config_empty
		with workflow_instances as mock_workflow_instances_empty
		with tool_authorizations as mock_tool_authorizations_empty
}

test_constraint_max_length_fail if {
	not allow with input as mock_input(
		"POST", "/mcp",
		mock_bearer(mock_jwt_jarvis),
		"{\"jsonrpc\":\"2.0\",\"id\":61,\"method\":\"tools/call\",\"params\":{\"name\":\"mock-calendar__create_event\",\"arguments\":{\"title\":\"This is a very long title that exceeds the maximum allowed length of fifty characters\"}}}",
	)
		with catalog as mock_catalog
		with access_rules as mock_access_rules
		with revoked_subjects as mock_revoked
		with guardrails as {"mock-calendar": {"create_event": {"constraints": [{"paramName": "title", "operator": "max_length", "values": ["50"], "description": "Title max 50 chars"}], "allowlists": []}}}
		with workflow_config as mock_workflow_config_empty
		with workflow_instances as mock_workflow_instances_empty
		with tool_authorizations as mock_tool_authorizations_empty
}

# ============================================================================
# 7. Constraint edge cases
# ============================================================================

# Missing argument (not in request) -> skip constraint, pass
test_constraint_missing_arg_passes if {
	allow with input as mock_input(
		"POST", "/mcp",
		mock_bearer(mock_jwt_jarvis),
		"{\"jsonrpc\":\"2.0\",\"id\":70,\"method\":\"tools/call\",\"params\":{\"name\":\"mock-calendar__create_event\",\"arguments\":{\"title\":\"test\"}}}",
	)
		with catalog as mock_catalog
		with access_rules as mock_access_rules
		with revoked_subjects as mock_revoked
		with guardrails as {"mock-calendar": {"create_event": {"constraints": [{"paramName": "nonexistent_arg", "operator": "in", "values": ["a", "b"], "description": "Only when present"}], "allowlists": []}}}
		with workflow_config as mock_workflow_config_empty
		with workflow_instances as mock_workflow_instances_empty
		with tool_authorizations as mock_tool_authorizations_empty
}

# No guardrails exists -> pass (no constraints)
test_constraint_no_guardrails_passes if {
	allow with input as mock_input(
		"POST", "/mcp",
		mock_bearer(mock_jwt_jarvis),
		"{\"jsonrpc\":\"2.0\",\"id\":71,\"method\":\"tools/call\",\"params\":{\"name\":\"mock-calendar__create_event\",\"arguments\":{\"title\":\"test\"}}}",
	)
		with catalog as mock_catalog
		with access_rules as mock_access_rules
		with revoked_subjects as mock_revoked
		with guardrails as {}
		with workflow_config as mock_workflow_config_empty
		with workflow_instances as mock_workflow_instances_empty
		with tool_authorizations as mock_tool_authorizations_empty
}

# Multiple constraints, one fails -> deny
test_constraint_multiple_one_fails if {
	not allow with input as mock_input(
		"POST", "/mcp",
		mock_bearer(mock_jwt_jarvis),
		"{\"jsonrpc\":\"2.0\",\"id\":72,\"method\":\"tools/call\",\"params\":{\"name\":\"mock-calendar__create_event\",\"arguments\":{\"priority\":\"high\",\"category\":\"secret\"}}}",
	)
		with catalog as mock_catalog
		with access_rules as mock_access_rules
		with revoked_subjects as mock_revoked
		with guardrails as {"mock-calendar": {"create_event": {"constraints": [
			{"paramName": "priority", "operator": "in", "values": ["high", "medium", "low"], "description": "Valid priority"},
			{"paramName": "category", "operator": "not_in", "values": ["secret", "classified"], "description": "No secret categories"},
		], "allowlists": []}}}
		with workflow_config as mock_workflow_config_empty
		with workflow_instances as mock_workflow_instances_empty
		with tool_authorizations as mock_tool_authorizations_empty
}

# ============================================================================
# 8. Allowlists (replaces Approved Recipients)
# ============================================================================

# Domain match -> allow
test_allowlist_domain_match_allow if {
	allow with input as mock_input(
		"POST", "/mcp",
		mock_bearer(mock_jwt_jarvis),
		"{\"jsonrpc\":\"2.0\",\"id\":80,\"method\":\"tools/call\",\"params\":{\"name\":\"mock-calendar__send_email\",\"arguments\":{\"to\":\"alice@acme.com\",\"body\":\"hello\"}}}",
	)
		with catalog as mock_catalog
		with access_rules as mock_access_rules
		with revoked_subjects as mock_revoked
		with guardrails as {"mock-calendar": {"send_email": {"constraints": [], "allowlists": [{"paramName": "to", "matchMode": "domain", "callerScope": "jarvis@acme.com", "allowedValues": [], "allowedPatterns": ["acme.com"], "description": "Approved domains"}]}}}
		with workflow_config as mock_workflow_config_empty
		with workflow_instances as mock_workflow_instances_empty
		with tool_authorizations as mock_tool_authorizations_empty
}

# Exact email match -> allow
test_allowlist_email_match_allow if {
	allow with input as mock_input(
		"POST", "/mcp",
		mock_bearer(mock_jwt_jarvis),
		"{\"jsonrpc\":\"2.0\",\"id\":81,\"method\":\"tools/call\",\"params\":{\"name\":\"mock-calendar__send_email\",\"arguments\":{\"to\":\"dave@external-vendor.com\",\"body\":\"hello\"}}}",
	)
		with catalog as mock_catalog
		with access_rules as mock_access_rules
		with revoked_subjects as mock_revoked
		with guardrails as {"mock-calendar": {"send_email": {"constraints": [], "allowlists": [{"paramName": "to", "matchMode": "domain", "callerScope": "jarvis@acme.com", "allowedValues": ["dave@external-vendor.com"], "allowedPatterns": ["acme.com"], "description": "Approved recipients"}]}}}
		with workflow_config as mock_workflow_config_empty
		with workflow_instances as mock_workflow_instances_empty
		with tool_authorizations as mock_tool_authorizations_empty
}

# Not approved -> deny
test_allowlist_not_approved_deny if {
	not allow with input as mock_input(
		"POST", "/mcp",
		mock_bearer(mock_jwt_jarvis),
		"{\"jsonrpc\":\"2.0\",\"id\":82,\"method\":\"tools/call\",\"params\":{\"name\":\"mock-calendar__send_email\",\"arguments\":{\"to\":\"stranger@evil.com\",\"body\":\"hello\"}}}",
	)
		with catalog as mock_catalog
		with access_rules as mock_access_rules
		with revoked_subjects as mock_revoked
		with guardrails as {"mock-calendar": {"send_email": {"constraints": [], "allowlists": [{"paramName": "to", "matchMode": "domain", "callerScope": "jarvis@acme.com", "allowedValues": ["dave@external-vendor.com"], "allowedPatterns": ["acme.com"], "description": "Approved recipients"}]}}}
		with workflow_config as mock_workflow_config_empty
		with workflow_instances as mock_workflow_instances_empty
		with tool_authorizations as mock_tool_authorizations_empty
}

# Agent-specific: different caller bypasses allowlist -> allow (no allowlist applies)
test_allowlist_different_agent_bypasses if {
	allow with input as mock_input(
		"POST", "/mcp",
		mock_bearer(mock_jwt_alice),
		"{\"jsonrpc\":\"2.0\",\"id\":83,\"method\":\"tools/call\",\"params\":{\"name\":\"mock-calendar__send_email\",\"arguments\":{\"to\":\"stranger@evil.com\",\"body\":\"hello\"}}}",
	)
		with catalog as mock_catalog
		with access_rules as mock_access_rules
		with revoked_subjects as mock_revoked
		with guardrails as {"mock-calendar": {"send_email": {"constraints": [], "allowlists": [{"paramName": "to", "matchMode": "domain", "callerScope": "jarvis@acme.com", "allowedValues": [], "allowedPatterns": ["acme.com"], "description": "Approved domains"}]}}}
		with workflow_config as mock_workflow_config_empty
		with workflow_instances as mock_workflow_instances_empty
		with tool_authorizations as mock_tool_authorizations_empty
}

# No allowlist for service -> no effect (simple tool passes)
test_allowlist_no_guardrails_allows if {
	allow with input as mock_input(
		"POST", "/mcp",
		mock_bearer(mock_jwt_jarvis),
		"{\"jsonrpc\":\"2.0\",\"id\":84,\"method\":\"tools/call\",\"params\":{\"name\":\"mock-calendar__send_email\",\"arguments\":{\"to\":\"anyone@anywhere.com\",\"body\":\"hello\"}}}",
	)
		with catalog as mock_catalog
		with access_rules as mock_access_rules
		with revoked_subjects as mock_revoked
		with guardrails as {}
		with workflow_config as mock_workflow_config_empty
		with workflow_instances as mock_workflow_instances_empty
		with tool_authorizations as mock_tool_authorizations_empty
}

# ============================================================================
# 9. ToolAuthorization override
# ============================================================================

# Constraint fails + override exists -> allow
test_tool_authorization_override_allows if {
	allow with input as mock_input(
		"POST", "/mcp",
		mock_bearer(mock_jwt_jarvis),
		"{\"jsonrpc\":\"2.0\",\"id\":90,\"method\":\"tools/call\",\"params\":{\"name\":\"mock-calendar__create_event\",\"arguments\":{\"priority\":\"critical\"}}}",
	)
		with catalog as mock_catalog
		with access_rules as mock_access_rules
		with revoked_subjects as mock_revoked
		with guardrails as {"mock-calendar": {"create_event": {"constraints": [{"paramName": "priority", "operator": "in", "values": ["high", "medium", "low"], "description": "Must be valid priority"}], "allowlists": []}}}
		with workflow_config as mock_workflow_config_empty
		with workflow_instances as mock_workflow_instances_empty
		with tool_authorizations as [{"instanceId": "auth-1", "serviceName": "mock-calendar", "toolName": "create_event", "agentIdentity": "jarvis@acme.com", "scope": "one-shot"}]
		with has_tool_authorization_override as true
}

# Constraint fails + no override -> deny
test_constraint_fail_no_override_denies if {
	not allow with input as mock_input(
		"POST", "/mcp",
		mock_bearer(mock_jwt_jarvis),
		"{\"jsonrpc\":\"2.0\",\"id\":91,\"method\":\"tools/call\",\"params\":{\"name\":\"mock-calendar__create_event\",\"arguments\":{\"priority\":\"critical\"}}}",
	)
		with catalog as mock_catalog
		with access_rules as mock_access_rules
		with revoked_subjects as mock_revoked
		with guardrails as {"mock-calendar": {"create_event": {"constraints": [{"paramName": "priority", "operator": "in", "values": ["high", "medium", "low"], "description": "Must be valid priority"}], "allowlists": []}}}
		with workflow_config as mock_workflow_config_empty
		with workflow_instances as mock_workflow_instances_empty
		with tool_authorizations as []
}

# ============================================================================
# 10. Combined paths
# ============================================================================

# Constraints pass + no workflow + no allowlist -> allow (fully in-memory)
test_combined_constraints_pass_no_governance if {
	allow with input as mock_input(
		"POST", "/mcp",
		mock_bearer(mock_jwt_jarvis),
		"{\"jsonrpc\":\"2.0\",\"id\":100,\"method\":\"tools/call\",\"params\":{\"name\":\"mock-calendar__create_event\",\"arguments\":{\"priority\":\"high\",\"attendees\":\"bob@acme.com\"}}}",
	)
		with catalog as mock_catalog
		with access_rules as mock_access_rules
		with revoked_subjects as mock_revoked
		with guardrails as {"mock-calendar": {"create_event": {"constraints": [
			{"paramName": "priority", "operator": "in", "values": ["high", "medium", "low"], "description": "Valid priority"},
			{"paramName": "attendees", "operator": "contains", "values": ["@acme.com"], "description": "Acme only"},
		], "allowlists": []}}}
		with workflow_config as mock_workflow_config_empty
		with workflow_instances as mock_workflow_instances_empty
		with tool_authorizations as mock_tool_authorizations_empty
}

# Constraints pass + allowlist denied -> deny
test_combined_constraints_pass_allowlist_denied if {
	not allow with input as mock_input(
		"POST", "/mcp",
		mock_bearer(mock_jwt_jarvis),
		"{\"jsonrpc\":\"2.0\",\"id\":101,\"method\":\"tools/call\",\"params\":{\"name\":\"mock-calendar__send_email\",\"arguments\":{\"to\":\"stranger@evil.com\",\"body\":\"hi\"}}}",
	)
		with catalog as mock_catalog
		with access_rules as mock_access_rules
		with revoked_subjects as mock_revoked
		with guardrails as {"mock-calendar": {"send_email": {"constraints": [], "allowlists": [{"paramName": "to", "matchMode": "domain", "callerScope": "jarvis@acme.com", "allowedValues": [], "allowedPatterns": ["acme.com"], "description": "Approved domains"}]}}}
		with workflow_config as mock_workflow_config_empty
		with workflow_instances as mock_workflow_instances_empty
		with tool_authorizations as mock_tool_authorizations_empty
}

# Constraints pass + workflow pending -> pending with headers
test_combined_constraints_pass_workflow_pending if {
	not allow with input as mock_input(
		"POST", "/mcp",
		mock_bearer(mock_jwt_jarvis),
		"{\"jsonrpc\":\"2.0\",\"id\":102,\"method\":\"tools/call\",\"params\":{\"name\":\"mock-calendar__create_event\",\"arguments\":{\"title\":\"test\"}}}",
	)
		with catalog as mock_catalog
		with access_rules as mock_access_rules
		with revoked_subjects as mock_revoked
		with guardrails as mock_guardrails_empty
		with workflow_config as {"mock-calendar": {"create_event": true}}
		with workflow_instances as {"mock-calendar": "wf-1"}
		with tool_authorizations as mock_tool_authorizations_empty
		with npl_workflow_decision as {"decision": "pending", "requestId": "REQ-42", "message": "Awaiting"}
}

# ============================================================================
# 11. Response headers
# ============================================================================

test_user_id_header if {
	h := headers with input as mock_input(
		"POST", "/mcp",
		mock_bearer(mock_jwt_jarvis),
		"{\"jsonrpc\":\"2.0\",\"id\":110,\"method\":\"tools/list\",\"params\":{}}",
	)
		with catalog as mock_catalog
		with access_rules as mock_access_rules
		with revoked_subjects as mock_revoked

	h["x-user-id"] == "jarvis@acme.com"
}

test_granted_services_header if {
	h := headers with input as mock_input(
		"POST", "/mcp",
		mock_bearer(mock_jwt_jarvis),
		"{\"jsonrpc\":\"2.0\",\"id\":111,\"method\":\"tools/list\",\"params\":{}}",
	)
		with catalog as mock_catalog
		with access_rules as mock_access_rules
		with revoked_subjects as mock_revoked

	# Jarvis matches sales-calendar (mock-calendar) and jarvis-duckduckgo (duckduckgo)
	contains(h["x-granted-services"], "mock-calendar")
	contains(h["x-granted-services"], "duckduckgo")
}

test_pending_headers if {
	rh := response_headers with input as mock_input(
		"POST", "/mcp",
		mock_bearer(mock_jwt_jarvis),
		"{\"jsonrpc\":\"2.0\",\"id\":112,\"method\":\"tools/call\",\"params\":{\"name\":\"mock-calendar__create_event\",\"arguments\":{\"title\":\"test\"}}}",
	)
		with catalog as mock_catalog
		with access_rules as mock_access_rules
		with revoked_subjects as mock_revoked
		with guardrails as mock_guardrails_empty
		with workflow_config as {"mock-calendar": {"create_event": true}}
		with workflow_instances as {"mock-calendar": "wf-1"}
		with tool_authorizations as mock_tool_authorizations_empty
		with npl_workflow_decision as {"decision": "pending", "requestId": "REQ-42", "message": "Awaiting"}

	rh["x-request-id"] == "REQ-42"
	rh["retry-after"] == "30"
}

test_reason_header_authorized if {
	r := reason with input as mock_input(
		"POST", "/mcp",
		mock_bearer(mock_jwt_jarvis),
		"{\"jsonrpc\":\"2.0\",\"id\":113,\"method\":\"tools/call\",\"params\":{\"name\":\"mock-calendar__list_events\",\"arguments\":{}}}",
	)
		with catalog as mock_catalog
		with access_rules as mock_access_rules
		with revoked_subjects as mock_revoked

	r == "Authorized"
}

test_reason_header_no_access_rule if {
	r := reason with input as mock_input(
		"POST", "/mcp",
		mock_bearer(mock_jwt_unknown),
		"{\"jsonrpc\":\"2.0\",\"id\":114,\"method\":\"tools/call\",\"params\":{\"name\":\"mock-calendar__list_events\",\"arguments\":{}}}",
	)
		with catalog as mock_catalog
		with access_rules as mock_access_rules
		with revoked_subjects as mock_revoked

	contains(r, "not authorized")
}

# Constraint denied — reason header includes constraint message
test_constraint_denied_reason_header if {
	r := reason with input as mock_input(
		"POST", "/mcp",
		mock_bearer(mock_jwt_jarvis),
		"{\"jsonrpc\":\"2.0\",\"id\":115,\"method\":\"tools/call\",\"params\":{\"name\":\"mock-calendar__create_event\",\"arguments\":{\"priority\":\"critical\"}}}",
	)
		with catalog as mock_catalog
		with access_rules as mock_access_rules
		with revoked_subjects as mock_revoked
		with guardrails as {"mock-calendar": {"create_event": {"constraints": [{"paramName": "priority", "operator": "in", "values": ["high", "medium", "low"], "description": "Must be valid priority"}], "allowlists": []}}}
		with workflow_config as mock_workflow_config_empty
		with workflow_instances as mock_workflow_instances_empty
		with tool_authorizations as []

	contains(r, "Constraint violated")
}

# Allowlist denied — reason header
test_allowlist_denied_reason_header if {
	r := reason with input as mock_input(
		"POST", "/mcp",
		mock_bearer(mock_jwt_jarvis),
		"{\"jsonrpc\":\"2.0\",\"id\":116,\"method\":\"tools/call\",\"params\":{\"name\":\"mock-calendar__send_email\",\"arguments\":{\"to\":\"stranger@evil.com\",\"body\":\"hi\"}}}",
	)
		with catalog as mock_catalog
		with access_rules as mock_access_rules
		with revoked_subjects as mock_revoked
		with guardrails as {"mock-calendar": {"send_email": {"constraints": [], "allowlists": [{"paramName": "to", "matchMode": "domain", "callerScope": "jarvis@acme.com", "allowedValues": [], "allowedPatterns": ["acme.com"], "description": "Approved domains"}]}}}
		with workflow_config as mock_workflow_config_empty
		with workflow_instances as mock_workflow_instances_empty
		with tool_authorizations as mock_tool_authorizations_empty

	contains(r, "Allowlist denied")
}

test_no_pending_headers_when_allowed if {
	rh := response_headers with input as mock_input(
		"POST", "/mcp",
		mock_bearer(mock_jwt_jarvis),
		"{\"jsonrpc\":\"2.0\",\"id\":117,\"method\":\"tools/call\",\"params\":{\"name\":\"mock-calendar__create_event\",\"arguments\":{\"title\":\"test\"}}}",
	)
		with catalog as mock_catalog
		with access_rules as mock_access_rules
		with revoked_subjects as mock_revoked
		with guardrails as mock_guardrails_empty
		with workflow_config as {"mock-calendar": {"create_event": true}}
		with workflow_instances as {"mock-calendar": "wf-1"}
		with tool_authorizations as mock_tool_authorizations_empty
		with npl_workflow_decision as {"decision": "allow", "requestId": "REQ-1", "message": "OK"}

	not rh["x-request-id"]
	not rh["retry-after"]
}

# ============================================================================
# 12. Edge cases
# ============================================================================

test_deny_empty_bundle if {
	not allow with input as mock_input(
		"POST", "/mcp",
		mock_bearer(mock_jwt_jarvis),
		"{\"jsonrpc\":\"2.0\",\"id\":120,\"method\":\"tools/call\",\"params\":{\"name\":\"mock-calendar__list_events\",\"arguments\":{}}}",
	)
		with catalog as {}
		with access_rules as []
		with revoked_subjects as []
}

test_deny_no_auth if {
	not allow with input as mock_input_no_auth(
		"POST", "/mcp",
		"{\"jsonrpc\":\"2.0\",\"id\":121,\"method\":\"tools/call\",\"params\":{\"name\":\"mock-calendar__list_events\",\"arguments\":{}}}",
	)
		with catalog as mock_catalog
		with access_rules as mock_access_rules
		with revoked_subjects as mock_revoked
}

test_deny_missing_body if {
	not allow with input as mock_input("POST", "/mcp", "", "")
		with catalog as mock_catalog
		with access_rules as mock_access_rules
		with revoked_subjects as mock_revoked
}

# ============================================================================
# 13. Service name resolution and granted services
# ============================================================================

test_service_name_from_qualified_tool if {
	result := service_name with input as mock_input(
		"POST", "/mcp",
		mock_bearer(mock_jwt_jarvis),
		"{\"jsonrpc\":\"2.0\",\"id\":130,\"method\":\"tools/call\",\"params\":{\"name\":\"mock-calendar__list_events\",\"arguments\":{}}}",
	)

	result == "mock-calendar"
}

test_tool_name_from_qualified_tool if {
	result := tool_name with input as mock_input(
		"POST", "/mcp",
		mock_bearer(mock_jwt_jarvis),
		"{\"jsonrpc\":\"2.0\",\"id\":131,\"method\":\"tools/call\",\"params\":{\"name\":\"mock-calendar__list_events\",\"arguments\":{}}}",
	)

	result == "list_events"
}

test_granted_services_excludes_disabled if {
	disabled_catalog := {
		"mock-calendar": {"enabled": true, "tools": {"list_events": {}}},
		"duckduckgo": {"enabled": false, "tools": {"search": {}}},
	}

	wildcard_rules := [
		{
			"id": "all-access",
			"matcher": {"matchType": "claims", "claims": {"organization": "acme"}, "identity": ""},
			"allow": {"services": ["*"], "tools": ["*"]},
		},
	]

	result := granted_service_names with input as mock_input(
		"POST", "/mcp",
		mock_bearer(mock_jwt_jarvis),
		"{\"jsonrpc\":\"2.0\",\"id\":132,\"method\":\"tools/list\",\"params\":{}}",
	)
		with catalog as disabled_catalog
		with access_rules as wildcard_rules
		with revoked_subjects as mock_revoked

	"mock-calendar" in result
	not "duckduckgo" in result
}

# ============================================================================
# 14. Stream setup
# ============================================================================

test_allow_stream_setup if {
	allow with input as mock_input("GET", "/mcp", mock_bearer(mock_jwt_jarvis), "")
		with catalog as mock_catalog
		with access_rules as mock_access_rules
		with revoked_subjects as mock_revoked
}

test_deny_stream_setup_no_auth if {
	not allow with input as mock_input_no_auth("GET", "/mcp", "")
		with catalog as mock_catalog
		with access_rules as mock_access_rules
		with revoked_subjects as mock_revoked
}

# ============================================================================
# 15. Wildcard service access rule
# ============================================================================

test_wildcard_service_access if {
	wildcard_rules := [
		{
			"id": "admin-all",
			"matcher": {"matchType": "claims", "claims": {"role": "user", "organization": "acme"}, "identity": ""},
			"allow": {"services": ["*"], "tools": ["*"]},
		},
	]

	allow with input as mock_input(
		"POST", "/mcp",
		mock_bearer(mock_jwt_jarvis),
		"{\"jsonrpc\":\"2.0\",\"id\":150,\"method\":\"tools/call\",\"params\":{\"name\":\"mock-calendar__list_events\",\"arguments\":{}}}",
	)
		with catalog as mock_catalog
		with access_rules as wildcard_rules
		with revoked_subjects as mock_revoked
}

# ============================================================================
# 16. Array-valued JWT claims (Keycloak multi-valued attributes)
# ============================================================================

mock_jwt_jarvis_array_claims := token if {
	header := {"alg": "HS256", "typ": "JWT"}
	payload := {
		"sub": "04c28d5a-7ac3-4ce8-b51e-04b4a36ba4d2",
		"email": "jarvis@acme.com",
		"preferred_username": "jarvis",
		"organization": ["acme"],
		"department": "sales",
		"role": ["user"],
	}
	token := io.jwt.encode_sign(header, payload, {"kty": "oct", "k": "dGVzdC1zZWNyZXQta2V5LWZvci1vcGEtdW5pdC10ZXN0cw"})
}

test_array_claims_match_access_rule if {
	allow with input as mock_input(
		"POST", "/mcp",
		mock_bearer(mock_jwt_jarvis_array_claims),
		"{\"jsonrpc\":\"2.0\",\"id\":160,\"method\":\"tools/call\",\"params\":{\"name\":\"mock-calendar__list_events\",\"arguments\":{}}}",
	)
		with catalog as mock_catalog
		with access_rules as mock_access_rules
		with revoked_subjects as mock_revoked
}

test_array_claims_wildcard_service if {
	wildcard_rules := [
		{
			"id": "all-users",
			"matcher": {"matchType": "claims", "claims": {"role": "user", "organization": "acme"}, "identity": ""},
			"allow": {"services": ["*"], "tools": ["*"]},
		},
	]

	allow with input as mock_input(
		"POST", "/mcp",
		mock_bearer(mock_jwt_jarvis_array_claims),
		"{\"jsonrpc\":\"2.0\",\"id\":161,\"method\":\"tools/call\",\"params\":{\"name\":\"mock-calendar__list_events\",\"arguments\":{}}}",
	)
		with catalog as mock_catalog
		with access_rules as wildcard_rules
		with revoked_subjects as mock_revoked
}

test_array_claims_granted_services if {
	result := granted_service_names with input as mock_input(
		"POST", "/mcp",
		mock_bearer(mock_jwt_jarvis_array_claims),
		"{\"jsonrpc\":\"2.0\",\"id\":162,\"method\":\"tools/list\",\"params\":{}}",
	)
		with catalog as mock_catalog
		with access_rules as mock_access_rules
		with revoked_subjects as mock_revoked

	"mock-calendar" in result
}

# ============================================================================
# 17. MCP Session ID extraction
# ============================================================================

test_mcp_session_id_extracted if {
	result := mcp_session_id with input as {"attributes": {"request": {"http": {
		"method": "POST",
		"path": "/mcp",
		"headers": {
			"authorization": mock_bearer(mock_jwt_jarvis),
			"mcp-session-id": "session-abc-123",
		},
		"body": "",
	}}}}

	result == "session-abc-123"
}

test_mcp_session_id_default_empty if {
	result := mcp_session_id with input as mock_input(
		"POST", "/mcp",
		mock_bearer(mock_jwt_jarvis),
		"",
	)

	result == ""
}

# ============================================================================
# 18. Comma-separated claim values (multi-value per key in Map<Text,Text>)
# ============================================================================

test_comma_claims_match_first_value if {
	comma_rules := [
		{
			"id": "multi-dept",
			"matcher": {"matchType": "claims", "claims": {"organization": "acme", "department": "sales,engineering"}, "identity": ""},
			"allow": {"services": ["mock-calendar"], "tools": ["*"]},
		},
	]

	allow with input as mock_input(
		"POST", "/mcp",
		mock_bearer(mock_jwt_jarvis),
		"{\"jsonrpc\":\"2.0\",\"id\":180,\"method\":\"tools/call\",\"params\":{\"name\":\"mock-calendar__list_events\",\"arguments\":{}}}",
	)
		with catalog as mock_catalog
		with access_rules as comma_rules
		with revoked_subjects as mock_revoked
}

test_comma_claims_match_second_value if {
	comma_rules := [
		{
			"id": "multi-dept",
			"matcher": {"matchType": "claims", "claims": {"organization": "acme", "department": "sales,engineering"}, "identity": ""},
			"allow": {"services": ["mock-calendar"], "tools": ["*"]},
		},
	]

	allow with input as mock_input(
		"POST", "/mcp",
		mock_bearer(mock_jwt_alice),
		"{\"jsonrpc\":\"2.0\",\"id\":181,\"method\":\"tools/call\",\"params\":{\"name\":\"mock-calendar__list_events\",\"arguments\":{}}}",
	)
		with catalog as mock_catalog
		with access_rules as comma_rules
		with revoked_subjects as mock_revoked
}

test_comma_claims_no_match if {
	comma_rules := [
		{
			"id": "multi-dept",
			"matcher": {"matchType": "claims", "claims": {"organization": "acme", "department": "sales,engineering"}, "identity": ""},
			"allow": {"services": ["mock-calendar"], "tools": ["*"]},
		},
	]

	not allow with input as mock_input(
		"POST", "/mcp",
		mock_bearer(mock_jwt_unknown),
		"{\"jsonrpc\":\"2.0\",\"id\":182,\"method\":\"tools/call\",\"params\":{\"name\":\"mock-calendar__list_events\",\"arguments\":{}}}",
	)
		with catalog as mock_catalog
		with access_rules as comma_rules
		with revoked_subjects as mock_revoked
}

test_comma_claims_array_jwt if {
	comma_rules := [
		{
			"id": "multi-dept",
			"matcher": {"matchType": "claims", "claims": {"organization": "acme", "department": "sales,engineering"}, "identity": ""},
			"allow": {"services": ["mock-calendar"], "tools": ["*"]},
		},
	]

	allow with input as mock_input(
		"POST", "/mcp",
		mock_bearer(mock_jwt_jarvis_array_claims),
		"{\"jsonrpc\":\"2.0\",\"id\":183,\"method\":\"tools/call\",\"params\":{\"name\":\"mock-calendar__list_events\",\"arguments\":{}}}",
	)
		with catalog as mock_catalog
		with access_rules as comma_rules
		with revoked_subjects as mock_revoked
}
