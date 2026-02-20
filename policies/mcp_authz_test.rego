# Unit tests for MCP Gateway v4 authorization policy
#
# Run with: opa test policies/ -v
#
# Tests the three-layer governance model:
#   Layer 1 — Catalog (open/gated tags)
#   Layer 2 — Access Rules (claim-based + identity-based)
#   Layer 3 — Governance Evaluator → NPL (gated tool evaluation)

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

# --- Mock v4 catalog (services + tools with tags) ---

mock_catalog := {
	"mock-calendar": {
		"enabled": true,
		"tools": {
			"list_events": {"tag": "open"},
			"create_event": {"tag": "gated"},
		},
	},
	"duckduckgo": {
		"enabled": true,
		"tools": {
			"search": {"tag": "open"},
			"fetch_page": {"tag": "open"},
		},
	},
}

# --- Mock v4 access rules ---

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

mock_governance_evaluator_url := "http://governance-evaluator:8090"

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
		with governance_evaluator_url as mock_governance_evaluator_url
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
		with governance_evaluator_url as mock_governance_evaluator_url
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
		with governance_evaluator_url as mock_governance_evaluator_url
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
		with governance_evaluator_url as mock_governance_evaluator_url
}

# ============================================================================
# 2. Catalog checks: missing service/tool, disabled service
# ============================================================================

test_deny_tool_call_missing_service if {
	not allow with input as mock_input(
		"POST", "/mcp",
		mock_bearer(mock_jwt_jarvis),
		"{\"jsonrpc\":\"2.0\",\"id\":10,\"method\":\"tools/call\",\"params\":{\"name\":\"github.create_issue\",\"arguments\":{}}}",
	)
		with catalog as mock_catalog
		with access_rules as mock_access_rules
		with revoked_subjects as mock_revoked
		with governance_evaluator_url as mock_governance_evaluator_url
}

test_deny_tool_call_missing_tool if {
	not allow with input as mock_input(
		"POST", "/mcp",
		mock_bearer(mock_jwt_jarvis),
		"{\"jsonrpc\":\"2.0\",\"id\":11,\"method\":\"tools/call\",\"params\":{\"name\":\"mock-calendar.delete_event\",\"arguments\":{}}}",
	)
		with catalog as mock_catalog
		with access_rules as mock_access_rules
		with revoked_subjects as mock_revoked
		with governance_evaluator_url as mock_governance_evaluator_url
}

test_deny_tool_call_disabled_service if {
	disabled_catalog := {
		"mock-calendar": {
			"enabled": false,
			"tools": {"list_events": {"tag": "open"}},
		},
	}

	not allow with input as mock_input(
		"POST", "/mcp",
		mock_bearer(mock_jwt_jarvis),
		"{\"jsonrpc\":\"2.0\",\"id\":12,\"method\":\"tools/call\",\"params\":{\"name\":\"mock-calendar.list_events\",\"arguments\":{}}}",
	)
		with catalog as disabled_catalog
		with access_rules as mock_access_rules
		with revoked_subjects as mock_revoked
		with governance_evaluator_url as mock_governance_evaluator_url
}

test_deny_tool_call_empty_catalog if {
	not allow with input as mock_input(
		"POST", "/mcp",
		mock_bearer(mock_jwt_jarvis),
		"{\"jsonrpc\":\"2.0\",\"id\":13,\"method\":\"tools/call\",\"params\":{\"name\":\"mock-calendar.list_events\",\"arguments\":{}}}",
	)
		with catalog as {}
		with access_rules as mock_access_rules
		with revoked_subjects as mock_revoked
		with governance_evaluator_url as mock_governance_evaluator_url
}

# ============================================================================
# 3. Access rules: claim match, identity match, wildcard, no match, OR, revoked
# ============================================================================

# Jarvis (sales) matches "sales-calendar" rule → allowed on mock-calendar open tools
test_access_rule_claim_match if {
	allow with input as mock_input(
		"POST", "/mcp",
		mock_bearer(mock_jwt_jarvis),
		"{\"jsonrpc\":\"2.0\",\"id\":20,\"method\":\"tools/call\",\"params\":{\"name\":\"mock-calendar.list_events\",\"arguments\":{}}}",
	)
		with catalog as mock_catalog
		with access_rules as mock_access_rules
		with revoked_subjects as mock_revoked
		with governance_evaluator_url as mock_governance_evaluator_url
}

# Jarvis matches identity rule for duckduckgo.search
test_access_rule_identity_match if {
	allow with input as mock_input(
		"POST", "/mcp",
		mock_bearer(mock_jwt_jarvis),
		"{\"jsonrpc\":\"2.0\",\"id\":21,\"method\":\"tools/call\",\"params\":{\"name\":\"duckduckgo.search\",\"arguments\":{\"query\":\"test\"}}}",
	)
		with catalog as mock_catalog
		with access_rules as mock_access_rules
		with revoked_subjects as mock_revoked
		with governance_evaluator_url as mock_governance_evaluator_url
}

# Alice (engineering) matches "engineering-all" → allowed on duckduckgo open tools
test_access_rule_wildcard_tool if {
	allow with input as mock_input(
		"POST", "/mcp",
		mock_bearer(mock_jwt_alice),
		"{\"jsonrpc\":\"2.0\",\"id\":22,\"method\":\"tools/call\",\"params\":{\"name\":\"duckduckgo.search\",\"arguments\":{\"query\":\"test\"}}}",
	)
		with catalog as mock_catalog
		with access_rules as mock_access_rules
		with revoked_subjects as mock_revoked
		with governance_evaluator_url as mock_governance_evaluator_url
}

# Unknown user (external org) → no matching rule → denied
test_access_rule_no_match if {
	not allow with input as mock_input(
		"POST", "/mcp",
		mock_bearer(mock_jwt_unknown),
		"{\"jsonrpc\":\"2.0\",\"id\":23,\"method\":\"tools/call\",\"params\":{\"name\":\"mock-calendar.list_events\",\"arguments\":{}}}",
	)
		with catalog as mock_catalog
		with access_rules as mock_access_rules
		with revoked_subjects as mock_revoked
		with governance_evaluator_url as mock_governance_evaluator_url
}

# Jarvis identity-rule only allows search, not fetch_page → denied
test_access_rule_identity_tool_mismatch if {
	# Only identity rule matches for duckduckgo, and it allows only "search"
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
		"{\"jsonrpc\":\"2.0\",\"id\":24,\"method\":\"tools/call\",\"params\":{\"name\":\"duckduckgo.fetch_page\",\"arguments\":{\"url\":\"http://example.com\"}}}",
	)
		with catalog as mock_catalog
		with access_rules as identity_only_rules
		with revoked_subjects as mock_revoked
		with governance_evaluator_url as mock_governance_evaluator_url
}

# Multiple rules OR semantics — any match allows
test_access_rule_or_semantics if {
	# Jarvis matches both "sales-calendar" (claims) AND "jarvis-duckduckgo" (identity)
	# Either should work independently
	allow with input as mock_input(
		"POST", "/mcp",
		mock_bearer(mock_jwt_jarvis),
		"{\"jsonrpc\":\"2.0\",\"id\":25,\"method\":\"tools/call\",\"params\":{\"name\":\"mock-calendar.list_events\",\"arguments\":{}}}",
	)
		with catalog as mock_catalog
		with access_rules as mock_access_rules
		with revoked_subjects as mock_revoked
		with governance_evaluator_url as mock_governance_evaluator_url
}

# Revoked subject → denied even with matching access rule
test_revoked_subject_denied if {
	not allow with input as mock_input(
		"POST", "/mcp",
		mock_bearer(mock_jwt_jarvis),
		"{\"jsonrpc\":\"2.0\",\"id\":26,\"method\":\"tools/call\",\"params\":{\"name\":\"mock-calendar.list_events\",\"arguments\":{}}}",
	)
		with catalog as mock_catalog
		with access_rules as mock_access_rules
		with revoked_subjects as ["jarvis@acme.com"]
		with governance_evaluator_url as mock_governance_evaluator_url
}

# ============================================================================
# 4. Open tool path: allowed, denied by access rules
# ============================================================================

test_open_tool_allowed if {
	allow with input as mock_input(
		"POST", "/mcp",
		mock_bearer(mock_jwt_jarvis),
		"{\"jsonrpc\":\"2.0\",\"id\":30,\"method\":\"tools/call\",\"params\":{\"name\":\"mock-calendar.list_events\",\"arguments\":{\"date\":\"2026-02-14\"}}}",
	)
		with catalog as mock_catalog
		with access_rules as mock_access_rules
		with revoked_subjects as mock_revoked
		with governance_evaluator_url as mock_governance_evaluator_url
}

test_open_tool_denied_no_rule if {
	not allow with input as mock_input(
		"POST", "/mcp",
		mock_bearer(mock_jwt_unknown),
		"{\"jsonrpc\":\"2.0\",\"id\":31,\"method\":\"tools/call\",\"params\":{\"name\":\"mock-calendar.list_events\",\"arguments\":{}}}",
	)
		with catalog as mock_catalog
		with access_rules as mock_access_rules
		with revoked_subjects as mock_revoked
		with governance_evaluator_url as mock_governance_evaluator_url
}

# ============================================================================
# 5. Gated tool path: evaluator allow, deny, pending, unreachable
# ============================================================================

# Gated tool + evaluator returns allow → allowed
test_gated_tool_npl_allow if {
	allow with input as mock_input(
		"POST", "/mcp",
		mock_bearer(mock_jwt_jarvis),
		"{\"jsonrpc\":\"2.0\",\"id\":40,\"method\":\"tools/call\",\"params\":{\"name\":\"mock-calendar.create_event\",\"arguments\":{\"title\":\"test\"}}}",
	)
		with catalog as mock_catalog
		with access_rules as mock_access_rules
		with revoked_subjects as mock_revoked
		with governance_evaluator_url as mock_governance_evaluator_url
		with npl_decision as {"decision": "allow", "requestId": "REQ-1", "message": "Approved"}
}

# Gated tool + evaluator returns deny → denied
test_gated_tool_npl_deny if {
	not allow with input as mock_input(
		"POST", "/mcp",
		mock_bearer(mock_jwt_jarvis),
		"{\"jsonrpc\":\"2.0\",\"id\":41,\"method\":\"tools/call\",\"params\":{\"name\":\"mock-calendar.create_event\",\"arguments\":{\"title\":\"test\"}}}",
	)
		with catalog as mock_catalog
		with access_rules as mock_access_rules
		with revoked_subjects as mock_revoked
		with governance_evaluator_url as mock_governance_evaluator_url
		with npl_decision as {"decision": "deny", "requestId": "REQ-1", "message": "Not allowed"}
}

# Gated tool + evaluator returns pending → denied (with pending headers)
test_gated_tool_npl_pending if {
	not allow with input as mock_input(
		"POST", "/mcp",
		mock_bearer(mock_jwt_jarvis),
		"{\"jsonrpc\":\"2.0\",\"id\":42,\"method\":\"tools/call\",\"params\":{\"name\":\"mock-calendar.create_event\",\"arguments\":{\"title\":\"test\"}}}",
	)
		with catalog as mock_catalog
		with access_rules as mock_access_rules
		with revoked_subjects as mock_revoked
		with governance_evaluator_url as mock_governance_evaluator_url
		with npl_decision as {"decision": "pending", "requestId": "REQ-1", "message": "Awaiting approval"}
}

# Gated tool + evaluator unreachable (no URL) → denied
test_gated_tool_npl_unreachable if {
	not allow with input as mock_input(
		"POST", "/mcp",
		mock_bearer(mock_jwt_jarvis),
		"{\"jsonrpc\":\"2.0\",\"id\":43,\"method\":\"tools/call\",\"params\":{\"name\":\"mock-calendar.create_event\",\"arguments\":{\"title\":\"test\"}}}",
	)
		with catalog as mock_catalog
		with access_rules as mock_access_rules
		with revoked_subjects as mock_revoked
}

# Gated tool + constraint denied with reason in message
test_gated_tool_constraint_denied if {
	not allow with input as mock_input(
		"POST", "/mcp",
		mock_bearer(mock_jwt_jarvis),
		"{\"jsonrpc\":\"2.0\",\"id\":44,\"method\":\"tools/call\",\"params\":{\"name\":\"mock-calendar.create_event\",\"arguments\":{\"title\":\"test\"}}}",
	)
		with catalog as mock_catalog
		with access_rules as mock_access_rules
		with revoked_subjects as mock_revoked
		with governance_evaluator_url as mock_governance_evaluator_url
		with npl_decision as {"decision": "deny", "requestId": "", "message": "Constraint violated: Only allowed domains"}
}

# Constraint denied — reason header includes constraint message
test_constraint_denied_reason_header if {
	r := reason with input as mock_input(
		"POST", "/mcp",
		mock_bearer(mock_jwt_jarvis),
		"{\"jsonrpc\":\"2.0\",\"id\":45,\"method\":\"tools/call\",\"params\":{\"name\":\"mock-calendar.create_event\",\"arguments\":{\"title\":\"test\"}}}",
	)
		with catalog as mock_catalog
		with access_rules as mock_access_rules
		with revoked_subjects as mock_revoked
		with governance_evaluator_url as mock_governance_evaluator_url
		with npl_decision as {"decision": "deny", "requestId": "", "message": "Constraint violated: Only allowed domains"}

	contains(r, "Constraint violated")
}

# ============================================================================
# 6. Response headers
# ============================================================================

test_user_id_header if {
	h := headers with input as mock_input(
		"POST", "/mcp",
		mock_bearer(mock_jwt_jarvis),
		"{\"jsonrpc\":\"2.0\",\"id\":50,\"method\":\"tools/list\",\"params\":{}}",
	)
		with catalog as mock_catalog
		with access_rules as mock_access_rules
		with revoked_subjects as mock_revoked
		with governance_evaluator_url as mock_governance_evaluator_url

	h["x-user-id"] == "jarvis@acme.com"
}

test_granted_services_header if {
	h := headers with input as mock_input(
		"POST", "/mcp",
		mock_bearer(mock_jwt_jarvis),
		"{\"jsonrpc\":\"2.0\",\"id\":51,\"method\":\"tools/list\",\"params\":{}}",
	)
		with catalog as mock_catalog
		with access_rules as mock_access_rules
		with revoked_subjects as mock_revoked
		with governance_evaluator_url as mock_governance_evaluator_url

	# Jarvis matches sales-calendar (mock-calendar) and jarvis-duckduckgo (duckduckgo)
	contains(h["x-granted-services"], "mock-calendar")
	contains(h["x-granted-services"], "duckduckgo")
}

test_pending_headers if {
	rh := response_headers with input as mock_input(
		"POST", "/mcp",
		mock_bearer(mock_jwt_jarvis),
		"{\"jsonrpc\":\"2.0\",\"id\":52,\"method\":\"tools/call\",\"params\":{\"name\":\"mock-calendar.create_event\",\"arguments\":{\"title\":\"test\"}}}",
	)
		with catalog as mock_catalog
		with access_rules as mock_access_rules
		with revoked_subjects as mock_revoked
		with governance_evaluator_url as mock_governance_evaluator_url
		with npl_decision as {"decision": "pending", "requestId": "REQ-42", "message": "Awaiting"}

	rh["x-request-id"] == "REQ-42"
	rh["retry-after"] == "30"
}

test_reason_header_authorized if {
	r := reason with input as mock_input(
		"POST", "/mcp",
		mock_bearer(mock_jwt_jarvis),
		"{\"jsonrpc\":\"2.0\",\"id\":53,\"method\":\"tools/call\",\"params\":{\"name\":\"mock-calendar.list_events\",\"arguments\":{}}}",
	)
		with catalog as mock_catalog
		with access_rules as mock_access_rules
		with revoked_subjects as mock_revoked
		with governance_evaluator_url as mock_governance_evaluator_url

	r == "Authorized"
}

test_reason_header_no_access_rule if {
	r := reason with input as mock_input(
		"POST", "/mcp",
		mock_bearer(mock_jwt_unknown),
		"{\"jsonrpc\":\"2.0\",\"id\":54,\"method\":\"tools/call\",\"params\":{\"name\":\"mock-calendar.list_events\",\"arguments\":{}}}",
	)
		with catalog as mock_catalog
		with access_rules as mock_access_rules
		with revoked_subjects as mock_revoked
		with governance_evaluator_url as mock_governance_evaluator_url

	contains(r, "not authorized")
}

# ============================================================================
# 7. Edge cases
# ============================================================================

test_deny_empty_bundle if {
	not allow with input as mock_input(
		"POST", "/mcp",
		mock_bearer(mock_jwt_jarvis),
		"{\"jsonrpc\":\"2.0\",\"id\":60,\"method\":\"tools/call\",\"params\":{\"name\":\"mock-calendar.list_events\",\"arguments\":{}}}",
	)
		with catalog as {}
		with access_rules as []
		with revoked_subjects as []
}

test_deny_no_auth if {
	not allow with input as mock_input_no_auth(
		"POST", "/mcp",
		"{\"jsonrpc\":\"2.0\",\"id\":61,\"method\":\"tools/call\",\"params\":{\"name\":\"mock-calendar.list_events\",\"arguments\":{}}}",
	)
		with catalog as mock_catalog
		with access_rules as mock_access_rules
		with revoked_subjects as mock_revoked
		with governance_evaluator_url as mock_governance_evaluator_url
}

test_deny_missing_body if {
	not allow with input as mock_input("POST", "/mcp", "", "")
		with catalog as mock_catalog
		with access_rules as mock_access_rules
		with revoked_subjects as mock_revoked
		with governance_evaluator_url as mock_governance_evaluator_url
}

# ============================================================================
# 8. Service name resolution and granted services
# ============================================================================

test_service_name_from_qualified_tool if {
	result := service_name with input as mock_input(
		"POST", "/mcp",
		mock_bearer(mock_jwt_jarvis),
		"{\"jsonrpc\":\"2.0\",\"id\":70,\"method\":\"tools/call\",\"params\":{\"name\":\"mock-calendar.list_events\",\"arguments\":{}}}",
	)

	result == "mock-calendar"
}

test_tool_name_from_qualified_tool if {
	result := tool_name with input as mock_input(
		"POST", "/mcp",
		mock_bearer(mock_jwt_jarvis),
		"{\"jsonrpc\":\"2.0\",\"id\":71,\"method\":\"tools/call\",\"params\":{\"name\":\"mock-calendar.list_events\",\"arguments\":{}}}",
	)

	result == "list_events"
}

test_granted_services_excludes_disabled if {
	disabled_catalog := {
		"mock-calendar": {"enabled": true, "tools": {"list_events": {"tag": "open"}}},
		"duckduckgo": {"enabled": false, "tools": {"search": {"tag": "open"}}},
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
		"{\"jsonrpc\":\"2.0\",\"id\":72,\"method\":\"tools/list\",\"params\":{}}",
	)
		with catalog as disabled_catalog
		with access_rules as wildcard_rules
		with revoked_subjects as mock_revoked
		with governance_evaluator_url as mock_governance_evaluator_url

	"mock-calendar" in result
	not "duckduckgo" in result
}

# ============================================================================
# 9. Stream setup
# ============================================================================

test_allow_stream_setup if {
	allow with input as mock_input("GET", "/mcp", mock_bearer(mock_jwt_jarvis), "")
		with catalog as mock_catalog
		with access_rules as mock_access_rules
		with revoked_subjects as mock_revoked
		with governance_evaluator_url as mock_governance_evaluator_url
}

test_deny_stream_setup_no_auth if {
	not allow with input as mock_input_no_auth("GET", "/mcp", "")
		with catalog as mock_catalog
		with access_rules as mock_access_rules
		with revoked_subjects as mock_revoked
		with governance_evaluator_url as mock_governance_evaluator_url
}

# ============================================================================
# 10. Wildcard service access rule
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
		"{\"jsonrpc\":\"2.0\",\"id\":80,\"method\":\"tools/call\",\"params\":{\"name\":\"mock-calendar.list_events\",\"arguments\":{}}}",
	)
		with catalog as mock_catalog
		with access_rules as wildcard_rules
		with revoked_subjects as mock_revoked
		with governance_evaluator_url as mock_governance_evaluator_url
}

# ============================================================================
# 10b. Array-valued JWT claims (Keycloak multi-valued attributes)
# ============================================================================

# Keycloak encodes some attributes as arrays, e.g. role: ["user"], organization: ["acme"]
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

# Array claims should match claim-based access rules
test_array_claims_match_access_rule if {
	allow with input as mock_input(
		"POST", "/mcp",
		mock_bearer(mock_jwt_jarvis_array_claims),
		"{\"jsonrpc\":\"2.0\",\"id\":81,\"method\":\"tools/call\",\"params\":{\"name\":\"mock-calendar.list_events\",\"arguments\":{}}}",
	)
		with catalog as mock_catalog
		with access_rules as mock_access_rules
		with revoked_subjects as mock_revoked
		with governance_evaluator_url as mock_governance_evaluator_url
}

# Array claims with wildcard service rule
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
		"{\"jsonrpc\":\"2.0\",\"id\":82,\"method\":\"tools/call\",\"params\":{\"name\":\"mock-calendar.list_events\",\"arguments\":{}}}",
	)
		with catalog as mock_catalog
		with access_rules as wildcard_rules
		with revoked_subjects as mock_revoked
		with governance_evaluator_url as mock_governance_evaluator_url
}

# Array claims should appear in granted services
test_array_claims_granted_services if {
	result := granted_service_names with input as mock_input(
		"POST", "/mcp",
		mock_bearer(mock_jwt_jarvis_array_claims),
		"{\"jsonrpc\":\"2.0\",\"id\":83,\"method\":\"tools/list\",\"params\":{}}",
	)
		with catalog as mock_catalog
		with access_rules as mock_access_rules
		with revoked_subjects as mock_revoked
		with governance_evaluator_url as mock_governance_evaluator_url

	"mock-calendar" in result
}

# ============================================================================
# 11. MCP Session ID extraction
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
# 12. No pending headers when not pending
# ============================================================================

test_no_pending_headers_when_allowed if {
	rh := response_headers with input as mock_input(
		"POST", "/mcp",
		mock_bearer(mock_jwt_jarvis),
		"{\"jsonrpc\":\"2.0\",\"id\":90,\"method\":\"tools/call\",\"params\":{\"name\":\"mock-calendar.create_event\",\"arguments\":{\"title\":\"test\"}}}",
	)
		with catalog as mock_catalog
		with access_rules as mock_access_rules
		with revoked_subjects as mock_revoked
		with governance_evaluator_url as mock_governance_evaluator_url
		with npl_decision as {"decision": "allow", "requestId": "REQ-1", "message": "OK"}

	not rh["x-request-id"]
	not rh["retry-after"]
}
