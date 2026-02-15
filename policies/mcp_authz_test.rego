# Unit tests for MCP Gateway authorization policy
#
# Run with: opa test policies/ -v
#
# These tests use mock input (simulating Envoy ext_authz requests)
# and mock policy data (catalog + grants from OPA bundle).

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

# A valid JWT payload for jarvis@acme.com (base64url-encoded, not signed — OPA only decodes)
# {"sub":"04c28d5a-7ac3-4ce8-b51e-04b4a36ba4d2","email":"jarvis@acme.com","preferred_username":"jarvis"}
# We use io.jwt.encode_sign with a dummy key for test tokens.

mock_jwt_jarvis := token if {
	header := {"alg": "HS256", "typ": "JWT"}
	payload := {
		"sub": "04c28d5a-7ac3-4ce8-b51e-04b4a36ba4d2",
		"email": "jarvis@acme.com",
		"preferred_username": "jarvis",
	}
	token := io.jwt.encode_sign(header, payload, {"kty": "oct", "k": "dGVzdC1zZWNyZXQta2V5LWZvci1vcGEtdW5pdC10ZXN0cw"})
}

mock_jwt_alice := token if {
	header := {"alg": "HS256", "typ": "JWT"}
	payload := {
		"sub": "64d75a3e-25b1-4455-ab7c-21aee1259ed3",
		"email": "alice@acme.com",
		"preferred_username": "alice",
	}
	token := io.jwt.encode_sign(header, payload, {"kty": "oct", "k": "dGVzdC1zZWNyZXQta2V5LWZvci1vcGEtdW5pdC10ZXN0cw"})
}

mock_jwt_unknown := token if {
	header := {"alg": "HS256", "typ": "JWT"}
	payload := {
		"sub": "unknown-user-id",
		"email": "unknown@acme.com",
		"preferred_username": "unknown",
	}
	token := io.jwt.encode_sign(header, payload, {"kty": "oct", "k": "dGVzdC1zZWNyZXQta2V5LWZvci1vcGEtdW5pdC10ZXN0cw"})
}

mock_bearer(jwt) := sprintf("Bearer %s", [jwt])

# --- Mock policy data (catalog + grants) ---

mock_catalog := {
	"duckduckgo": {
		"enabled": true,
		"enabledTools": ["search", "fetch_content"],
		"suspended": false,
		"metadata": {},
	},
	"mock-calendar": {
		"enabled": true,
		"enabledTools": ["list_events", "create_event"],
		"suspended": false,
		"metadata": {},
	},
}

mock_suspended_catalog := {
	"duckduckgo": {
		"enabled": true,
		"enabledTools": ["search"],
		"suspended": true,
		"metadata": {},
	},
}

mock_grants := {
	"jarvis@acme.com": [
		{"serviceName": "duckduckgo", "allowedTools": ["*"]},
		{"serviceName": "mock-calendar", "allowedTools": ["*"]},
	],
	"alice@acme.com": [],
}

mock_contextual_routing := {}

# ============================================================================
# Test: Non-tool-call methods are always allowed
# ============================================================================

test_allow_initialize if {
	allow with input as mock_input(
		"POST", "/mcp",
		mock_bearer(mock_jwt_jarvis),
		"{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}",
	)
		with catalog as mock_catalog
		with grants as mock_grants
		with contextual_routing as mock_contextual_routing
}

test_allow_tools_list if {
	allow with input as mock_input(
		"POST", "/mcp",
		mock_bearer(mock_jwt_jarvis),
		"{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}",
	)
		with catalog as mock_catalog
		with grants as mock_grants
		with contextual_routing as mock_contextual_routing
}

test_allow_ping if {
	allow with input as mock_input(
		"POST", "/mcp",
		mock_bearer(mock_jwt_jarvis),
		"{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"ping\"}",
	)
		with catalog as mock_catalog
		with grants as mock_grants
		with contextual_routing as mock_contextual_routing
}

test_allow_notifications if {
	allow with input as mock_input(
		"POST", "/mcp",
		mock_bearer(mock_jwt_jarvis),
		"{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}",
	)
		with catalog as mock_catalog
		with grants as mock_grants
		with contextual_routing as mock_contextual_routing
}

# ============================================================================
# Test: Stream setup (GET /mcp)
# ============================================================================

test_allow_stream_setup_for_granted_user if {
	allow with input as mock_input("GET", "/mcp", mock_bearer(mock_jwt_jarvis), "")
		with catalog as mock_catalog
		with grants as mock_grants
		with contextual_routing as mock_contextual_routing
}

test_deny_stream_setup_for_user_without_access if {
	not allow with input as mock_input("GET", "/mcp", mock_bearer(mock_jwt_alice), "")
		with catalog as mock_catalog
		with grants as mock_grants
		with contextual_routing as mock_contextual_routing
}

test_deny_stream_setup_for_unknown_user if {
	not allow with input as mock_input("GET", "/mcp", mock_bearer(mock_jwt_unknown), "")
		with catalog as mock_catalog
		with grants as mock_grants
		with contextual_routing as mock_contextual_routing
}

# ============================================================================
# Test: tools/call — namespaced tool (service.tool format)
# ============================================================================

test_allow_namespaced_tool_call if {
	allow with input as mock_input(
		"POST", "/mcp",
		mock_bearer(mock_jwt_jarvis),
		"{\"jsonrpc\":\"2.0\",\"id\":10,\"method\":\"tools/call\",\"params\":{\"name\":\"duckduckgo.search\",\"arguments\":{\"query\":\"hello\"}}}",
	)
		with catalog as mock_catalog
		with grants as mock_grants
		with contextual_routing as mock_contextual_routing
}

test_allow_calendar_tool_call if {
	allow with input as mock_input(
		"POST", "/mcp",
		mock_bearer(mock_jwt_jarvis),
		"{\"jsonrpc\":\"2.0\",\"id\":11,\"method\":\"tools/call\",\"params\":{\"name\":\"mock-calendar.list_events\",\"arguments\":{\"date\":\"2026-02-13\"}}}",
	)
		with catalog as mock_catalog
		with grants as mock_grants
		with contextual_routing as mock_contextual_routing
}

# ============================================================================
# Test: tools/call — denied cases
# ============================================================================

test_deny_tool_call_service_not_available if {
	not allow with input as mock_input(
		"POST", "/mcp",
		mock_bearer(mock_jwt_jarvis),
		"{\"jsonrpc\":\"2.0\",\"id\":20,\"method\":\"tools/call\",\"params\":{\"name\":\"github.create_issue\",\"arguments\":{}}}",
	)
		with catalog as mock_catalog
		with grants as mock_grants
		with contextual_routing as mock_contextual_routing
}

test_deny_tool_call_service_suspended if {
	not allow with input as mock_input(
		"POST", "/mcp",
		mock_bearer(mock_jwt_jarvis),
		"{\"jsonrpc\":\"2.0\",\"id\":21,\"method\":\"tools/call\",\"params\":{\"name\":\"duckduckgo.search\",\"arguments\":{\"query\":\"hello\"}}}",
	)
		with catalog as mock_suspended_catalog
		with grants as mock_grants
		with contextual_routing as mock_contextual_routing
}

test_deny_tool_call_tool_not_enabled if {
	# Catalog for duckduckgo only has "search" enabled, not "fetch_content"
	limited_catalog := {"duckduckgo": {
		"enabled": true,
		"enabledTools": ["search"],
		"suspended": false,
		"metadata": {},
	}}

	not allow with input as mock_input(
		"POST", "/mcp",
		mock_bearer(mock_jwt_jarvis),
		"{\"jsonrpc\":\"2.0\",\"id\":22,\"method\":\"tools/call\",\"params\":{\"name\":\"duckduckgo.fetch_content\",\"arguments\":{\"url\":\"http://example.com\"}}}",
	)
		with catalog as limited_catalog
		with grants as mock_grants
		with contextual_routing as mock_contextual_routing
}

test_deny_tool_call_user_no_access if {
	not allow with input as mock_input(
		"POST", "/mcp",
		mock_bearer(mock_jwt_alice),
		"{\"jsonrpc\":\"2.0\",\"id\":23,\"method\":\"tools/call\",\"params\":{\"name\":\"duckduckgo.search\",\"arguments\":{\"query\":\"hello\"}}}",
	)
		with catalog as mock_catalog
		with grants as mock_grants
		with contextual_routing as mock_contextual_routing
}

test_deny_tool_call_unknown_user if {
	not allow with input as mock_input(
		"POST", "/mcp",
		mock_bearer(mock_jwt_unknown),
		"{\"jsonrpc\":\"2.0\",\"id\":24,\"method\":\"tools/call\",\"params\":{\"name\":\"duckduckgo.search\",\"arguments\":{\"query\":\"hello\"}}}",
	)
		with catalog as mock_catalog
		with grants as mock_grants
		with contextual_routing as mock_contextual_routing
}

test_deny_tool_call_missing_tool_name if {
	not allow with input as mock_input(
		"POST", "/mcp",
		mock_bearer(mock_jwt_jarvis),
		"{\"jsonrpc\":\"2.0\",\"id\":25,\"method\":\"tools/call\",\"params\":{}}",
	)
		with catalog as mock_catalog
		with grants as mock_grants
		with contextual_routing as mock_contextual_routing
}

test_deny_tool_call_no_auth if {
	not allow with input as mock_input_no_auth(
		"POST", "/mcp",
		"{\"jsonrpc\":\"2.0\",\"id\":26,\"method\":\"tools/call\",\"params\":{\"name\":\"duckduckgo.search\",\"arguments\":{\"query\":\"hello\"}}}",
	)
		with catalog as mock_catalog
		with grants as mock_grants
		with contextual_routing as mock_contextual_routing
}

# ============================================================================
# Test: Fail-closed — empty policy data denies everything
# ============================================================================

test_deny_tools_call_when_policy_data_empty if {
	not allow with input as mock_input(
		"POST", "/mcp",
		mock_bearer(mock_jwt_jarvis),
		"{\"jsonrpc\":\"2.0\",\"id\":30,\"method\":\"tools/call\",\"params\":{\"name\":\"duckduckgo.search\",\"arguments\":{\"query\":\"hello\"}}}",
	)
		with catalog as {}
		with grants as {}
		with contextual_routing as {}
}

# ============================================================================
# Test: Wildcard access ("*") grants all tools in a service
# ============================================================================

test_wildcard_grants_any_tool if {
	# jarvis has "*" for duckduckgo — should allow any tool name
	allow with input as mock_input(
		"POST", "/mcp",
		mock_bearer(mock_jwt_jarvis),
		"{\"jsonrpc\":\"2.0\",\"id\":40,\"method\":\"tools/call\",\"params\":{\"name\":\"duckduckgo.fetch_content\",\"arguments\":{\"url\":\"http://example.com\"}}}",
	)
		with catalog as mock_catalog
		with grants as mock_grants
		with contextual_routing as mock_contextual_routing
}

# ============================================================================
# Test: Specific tool access (non-wildcard)
# ============================================================================

test_specific_tool_access_allowed if {
	specific_grants := {
		"jarvis@acme.com": [
			{"serviceName": "duckduckgo", "allowedTools": ["search"]},
		],
	}

	allow with input as mock_input(
		"POST", "/mcp",
		mock_bearer(mock_jwt_jarvis),
		"{\"jsonrpc\":\"2.0\",\"id\":50,\"method\":\"tools/call\",\"params\":{\"name\":\"duckduckgo.search\",\"arguments\":{\"query\":\"hello\"}}}",
	)
		with catalog as mock_catalog
		with grants as specific_grants
		with contextual_routing as mock_contextual_routing
}

test_specific_tool_access_denied_for_other_tool if {
	specific_grants := {
		"jarvis@acme.com": [
			{"serviceName": "duckduckgo", "allowedTools": ["search"]},
		],
	}

	not allow with input as mock_input(
		"POST", "/mcp",
		mock_bearer(mock_jwt_jarvis),
		"{\"jsonrpc\":\"2.0\",\"id\":51,\"method\":\"tools/call\",\"params\":{\"name\":\"duckduckgo.fetch_content\",\"arguments\":{\"url\":\"http://example.com\"}}}",
	)
		with catalog as mock_catalog
		with grants as specific_grants
		with contextual_routing as mock_contextual_routing
}

# ============================================================================
# Test: No catalog entry for service — allow if user has grant
# ============================================================================

test_allow_when_no_catalog_entry_exists if {
	allow with input as mock_input(
		"POST", "/mcp",
		mock_bearer(mock_jwt_jarvis),
		"{\"jsonrpc\":\"2.0\",\"id\":60,\"method\":\"tools/call\",\"params\":{\"name\":\"mock-calendar.list_events\",\"arguments\":{\"date\":\"2026-02-13\"}}}",
	)
		with catalog as {}
		with grants as mock_grants
		with contextual_routing as mock_contextual_routing
}

# ============================================================================
# Test: Default deny — completely empty input
# ============================================================================

test_default_deny_empty_body_post if {
	not allow with input as mock_input("POST", "/mcp", "", "")
		with catalog as mock_catalog
		with grants as mock_grants
		with contextual_routing as mock_contextual_routing
}

# ============================================================================
# Test: Contextual routing — Layer 2 (route lookup only, no http.send in unit tests)
# ============================================================================

test_fast_path_no_contextual_route if {
	# No contextual routes configured — should take fast path (Rule 3a)
	allow with input as mock_input(
		"POST", "/mcp",
		mock_bearer(mock_jwt_jarvis),
		"{\"jsonrpc\":\"2.0\",\"id\":70,\"method\":\"tools/call\",\"params\":{\"name\":\"duckduckgo.search\",\"arguments\":{\"query\":\"hello\"}}}",
	)
		with catalog as mock_catalog
		with grants as mock_grants
		with contextual_routing as {}
}

test_contextual_route_lookup_specific_tool if {
	# Verify contextual_route resolves for specific tool
	routes := {"duckduckgo": {"search": {
		"policyProtocol": "PiiGuardPolicy",
		"instanceId": "pii-001",
		"endpoint": "/npl/policies/PiiGuardPolicy/pii-001/evaluate",
	}}}

	# With a contextual route, Rule 3a (fast path) should NOT match
	# Rule 3b would match but requires http.send — so allow should be false
	# (http.send will fail in unit tests since there's no server)
	not allow with input as mock_input(
		"POST", "/mcp",
		mock_bearer(mock_jwt_jarvis),
		"{\"jsonrpc\":\"2.0\",\"id\":71,\"method\":\"tools/call\",\"params\":{\"name\":\"duckduckgo.search\",\"arguments\":{\"query\":\"hello\"}}}",
	)
		with catalog as mock_catalog
		with grants as mock_grants
		with contextual_routing as routes
}

test_contextual_route_lookup_wildcard if {
	# Verify contextual_route resolves for wildcard
	routes := {"duckduckgo": {"*": {
		"policyProtocol": "PiiGuardPolicy",
		"instanceId": "pii-001",
		"endpoint": "/npl/policies/PiiGuardPolicy/pii-001/evaluate",
	}}}

	# Wildcard route should block fast path too
	not allow with input as mock_input(
		"POST", "/mcp",
		mock_bearer(mock_jwt_jarvis),
		"{\"jsonrpc\":\"2.0\",\"id\":72,\"method\":\"tools/call\",\"params\":{\"name\":\"duckduckgo.search\",\"arguments\":{\"query\":\"hello\"}}}",
	)
		with catalog as mock_catalog
		with grants as mock_grants
		with contextual_routing as routes
}

test_contextual_route_no_match_other_service if {
	# Route for slack, not duckduckgo — should take fast path
	routes := {"slack": {"*": {
		"policyProtocol": "PiiGuardPolicy",
		"instanceId": "pii-001",
		"endpoint": "/npl/policies/PiiGuardPolicy/pii-001/evaluate",
	}}}

	allow with input as mock_input(
		"POST", "/mcp",
		mock_bearer(mock_jwt_jarvis),
		"{\"jsonrpc\":\"2.0\",\"id\":73,\"method\":\"tools/call\",\"params\":{\"name\":\"duckduckgo.search\",\"arguments\":{\"query\":\"hello\"}}}",
	)
		with catalog as mock_catalog
		with grants as mock_grants
		with contextual_routing as routes
}

# ============================================================================
# Test: x-granted-services header for tools/list filtering
# ============================================================================

test_granted_services_header_on_tools_list if {
	# jarvis has grants for duckduckgo and mock-calendar — both available
	result := headers with input as mock_input(
		"POST", "/mcp",
		mock_bearer(mock_jwt_jarvis),
		"{\"jsonrpc\":\"2.0\",\"id\":80,\"method\":\"tools/list\",\"params\":{}}",
	)
		with catalog as mock_catalog
		with grants as mock_grants
		with contextual_routing as mock_contextual_routing

	result["x-granted-services"] == "duckduckgo,mock-calendar"
}

test_granted_services_header_excludes_suspended if {
	# duckduckgo is suspended — should not appear in granted services
	result := headers with input as mock_input(
		"POST", "/mcp",
		mock_bearer(mock_jwt_jarvis),
		"{\"jsonrpc\":\"2.0\",\"id\":81,\"method\":\"tools/list\",\"params\":{}}",
	)
		with catalog as mock_suspended_catalog
		with grants as mock_grants
		with contextual_routing as mock_contextual_routing

	# mock-calendar has no catalog entry (allowed) but duckduckgo is suspended (excluded)
	result["x-granted-services"] == "mock-calendar"
}

test_granted_services_header_empty_for_no_grants if {
	# alice has empty grants — header should be empty string
	result := headers with input as mock_input(
		"POST", "/mcp",
		mock_bearer(mock_jwt_alice),
		"{\"jsonrpc\":\"2.0\",\"id\":82,\"method\":\"tools/list\",\"params\":{}}",
	)
		with catalog as mock_catalog
		with grants as mock_grants
		with contextual_routing as mock_contextual_routing

	result["x-granted-services"] == ""
}

test_granted_services_not_emitted_for_tools_call if {
	# x-granted-services should only appear for tools/list, not tools/call
	result := headers with input as mock_input(
		"POST", "/mcp",
		mock_bearer(mock_jwt_jarvis),
		"{\"jsonrpc\":\"2.0\",\"id\":83,\"method\":\"tools/call\",\"params\":{\"name\":\"duckduckgo.search\",\"arguments\":{\"query\":\"hello\"}}}",
	)
		with catalog as mock_catalog
		with grants as mock_grants
		with contextual_routing as mock_contextual_routing

	not result["x-granted-services"]
}

# ============================================================================
# Test: x-user-id header
# ============================================================================

test_user_id_header_emitted if {
	result := headers with input as mock_input(
		"POST", "/mcp",
		mock_bearer(mock_jwt_jarvis),
		"{\"jsonrpc\":\"2.0\",\"id\":84,\"method\":\"tools/list\",\"params\":{}}",
	)
		with catalog as mock_catalog
		with grants as mock_grants
		with contextual_routing as mock_contextual_routing

	result["x-user-id"] == "jarvis@acme.com"
}

test_user_id_header_not_emitted_without_auth if {
	result := headers with input as mock_input_no_auth(
		"POST", "/mcp",
		"{\"jsonrpc\":\"2.0\",\"id\":85,\"method\":\"tools/list\",\"params\":{}}",
	)
		with catalog as mock_catalog
		with grants as mock_grants
		with contextual_routing as mock_contextual_routing

	not result["x-user-id"]
}

# ============================================================================
# Test: security_policy data accessible from bundle
# ============================================================================

mock_security_policy := {
	"version": "1.0",
	"tool_annotations": {
		"gmail": {
			"send_email": {
				"annotations": {
					"readOnlyHint": false,
					"destructiveHint": false,
					"openWorldHint": true,
				},
				"verb": "create",
				"labels": ["category:communication"],
			},
			"read_email": {
				"annotations": {"readOnlyHint": true},
				"verb": "get",
				"labels": ["category:communication"],
			},
		},
	},
	"classifiers": {
		"gmail": {
			"send_email": [
				{"field": "to", "contains": "@acme.com", "set_labels": ["scope:internal"]},
				{"field": "to", "not_contains": "@acme.com", "set_labels": ["scope:external"]},
			],
		},
	},
	"policies": [
		{"name": "Block BCC", "when": {"labels": ["data:bcc-used"]}, "action": "deny", "priority": 0},
		{"name": "Allow read-only", "when": {"readOnlyHint": true}, "action": "allow", "priority": 50},
		{"name": "Default allow", "when": {}, "action": "allow", "priority": 999},
	],
}

test_security_policy_accessible if {
	# Verify security_policy data is loaded from bundle and accessible
	sp := security_policy with security_policy as mock_security_policy
	sp.version == "1.0"
	sp.tool_annotations.gmail.send_email.verb == "create"
	sp.tool_annotations.gmail.read_email.annotations.readOnlyHint == true
}

test_security_policy_has_classifiers if {
	sp := security_policy with security_policy as mock_security_policy
	some rule in sp.classifiers.gmail.send_email
	rule.field == "to"
	rule.contains == "@acme.com"
}

test_security_policy_has_policies if {
	sp := security_policy with security_policy as mock_security_policy
	count(sp.policies) == 3
	sp.policies[0].name == "Block BCC"
	sp.policies[0].action == "deny"
	sp.policies[0].priority == 0
}

test_security_policy_default_empty if {
	# When no security policy is loaded, should be empty object
	sp := security_policy with security_policy as {}
	count(sp) == 0
}

test_allow_still_works_with_security_policy if {
	# Ensure existing allow rules work when security_policy is present
	allow with input as mock_input(
		"POST", "/mcp",
		mock_bearer(mock_jwt_jarvis),
		"{\"jsonrpc\":\"2.0\",\"id\":90,\"method\":\"tools/call\",\"params\":{\"name\":\"duckduckgo.search\",\"arguments\":{\"query\":\"hello\"}}}",
	)
		with catalog as mock_catalog
		with grants as mock_grants
		with contextual_routing as mock_contextual_routing
		with security_policy as mock_security_policy
}

# ============================================================================
# Test: Security policy evaluation — tag classification and policy matching
# ============================================================================

mock_grants_with_gmail := {
	"jarvis@acme.com": [
		{"serviceName": "duckduckgo", "allowedTools": ["*"]},
		{"serviceName": "mock-calendar", "allowedTools": ["*"]},
		{"serviceName": "gmail", "allowedTools": ["*"]},
	],
	"alice@acme.com": [],
}

mock_sp_evaluation := {
	"version": "1.0",
	"tool_annotations": {
		"gmail": {
			"send_email": {
				"annotations": {
					"readOnlyHint": false,
					"destructiveHint": false,
					"openWorldHint": true,
				},
				"verb": "create",
				"labels": ["category:communication"],
			},
			"read_email": {
				"annotations": {"readOnlyHint": true},
				"verb": "get",
				"labels": ["category:communication"],
			},
			"delete_email": {
				"annotations": {"destructiveHint": true},
				"verb": "delete",
				"labels": ["category:communication"],
			},
		},
	},
	"classifiers": {
		"gmail": {
			"send_email": [
				{"field": "to", "contains": "@acme.com", "set_labels": ["scope:internal"]},
				{"field": "to", "not_contains": "@acme.com", "set_labels": ["scope:external"]},
			],
		},
	},
	"policies": [
		{"name": "Allow read-only", "when": {"readOnlyHint": true}, "action": "allow", "priority": 50},
		{"name": "Deny destructive", "when": {"destructiveHint": true}, "action": "deny", "priority": 10},
		{"name": "Deny external", "when": {"labels": ["scope:external"]}, "action": "deny", "priority": 20},
		{"name": "Default allow", "when": {}, "action": "allow", "priority": 999},
	],
}

# Test: read_email with readOnlyHint=true → "Allow read-only" → allowed
test_sp_read_only_tool_allowed if {
	allow with input as mock_input(
		"POST", "/mcp",
		mock_bearer(mock_jwt_jarvis),
		"{\"jsonrpc\":\"2.0\",\"id\":200,\"method\":\"tools/call\",\"params\":{\"name\":\"gmail.read_email\",\"arguments\":{\"id\":\"123\"}}}",
	)
		with catalog as {}
		with grants as mock_grants_with_gmail
		with contextual_routing as {}
		with security_policy as mock_sp_evaluation
}

# Test: delete_email with destructiveHint=true → "Deny destructive" → denied
test_sp_destructive_tool_denied if {
	not allow with input as mock_input(
		"POST", "/mcp",
		mock_bearer(mock_jwt_jarvis),
		"{\"jsonrpc\":\"2.0\",\"id\":201,\"method\":\"tools/call\",\"params\":{\"name\":\"gmail.delete_email\",\"arguments\":{\"id\":\"123\"}}}",
	)
		with catalog as {}
		with grants as mock_grants_with_gmail
		with contextual_routing as {}
		with security_policy as mock_sp_evaluation
}

# Test: send_email to external address → classifier adds scope:external → "Deny external" → denied
test_sp_external_send_denied if {
	not allow with input as mock_input(
		"POST", "/mcp",
		mock_bearer(mock_jwt_jarvis),
		"{\"jsonrpc\":\"2.0\",\"id\":202,\"method\":\"tools/call\",\"params\":{\"name\":\"gmail.send_email\",\"arguments\":{\"to\":\"external@other.com\",\"subject\":\"test\",\"body\":\"hello\"}}}",
	)
		with catalog as {}
		with grants as mock_grants_with_gmail
		with contextual_routing as {}
		with security_policy as mock_sp_evaluation
}

# Test: send_email to internal address → classifier adds scope:internal (no scope:external) → "Default allow" → allowed
test_sp_internal_send_allowed if {
	allow with input as mock_input(
		"POST", "/mcp",
		mock_bearer(mock_jwt_jarvis),
		"{\"jsonrpc\":\"2.0\",\"id\":203,\"method\":\"tools/call\",\"params\":{\"name\":\"gmail.send_email\",\"arguments\":{\"to\":\"colleague@acme.com\",\"subject\":\"test\",\"body\":\"hello\"}}}",
	)
		with catalog as {}
		with grants as mock_grants_with_gmail
		with contextual_routing as {}
		with security_policy as mock_sp_evaluation
}

# Test: unclassified tool (not in security policy) → "Default allow" → allowed
test_sp_unclassified_tool_default_allow if {
	allow with input as mock_input(
		"POST", "/mcp",
		mock_bearer(mock_jwt_jarvis),
		"{\"jsonrpc\":\"2.0\",\"id\":204,\"method\":\"tools/call\",\"params\":{\"name\":\"duckduckgo.search\",\"arguments\":{\"query\":\"hello\"}}}",
	)
		with catalog as mock_catalog
		with grants as mock_grants
		with contextual_routing as {}
		with security_policy as mock_sp_evaluation
}

# Test: no security policies configured → existing allow rules work (backward compatible)
test_sp_empty_policy_allows if {
	allow with input as mock_input(
		"POST", "/mcp",
		mock_bearer(mock_jwt_jarvis),
		"{\"jsonrpc\":\"2.0\",\"id\":205,\"method\":\"tools/call\",\"params\":{\"name\":\"duckduckgo.search\",\"arguments\":{\"query\":\"hello\"}}}",
	)
		with catalog as mock_catalog
		with grants as mock_grants
		with contextual_routing as {}
		with security_policy as {}
}

# Test: tool-name fallback — "list_events" inferred as readOnlyHint=true → "Allow read-only"
test_sp_tool_name_fallback_read_only if {
	sp_with_policies_only := {
		"version": "1.0",
		"tool_annotations": {},
		"classifiers": {},
		"policies": [
			{"name": "Allow read-only", "when": {"readOnlyHint": true}, "action": "allow", "priority": 50},
			{"name": "Default deny", "when": {}, "action": "deny", "priority": 999},
		],
	}

	allow with input as mock_input(
		"POST", "/mcp",
		mock_bearer(mock_jwt_jarvis),
		"{\"jsonrpc\":\"2.0\",\"id\":206,\"method\":\"tools/call\",\"params\":{\"name\":\"mock-calendar.list_events\",\"arguments\":{\"date\":\"2026-02-13\"}}}",
	)
		with catalog as {}
		with grants as mock_grants
		with contextual_routing as {}
		with security_policy as sp_with_policies_only
}

# Test: tool-name fallback — "delete_event" inferred as destructiveHint=true → denied
test_sp_tool_name_fallback_destructive if {
	sp_with_policies_only := {
		"version": "1.0",
		"tool_annotations": {},
		"classifiers": {},
		"policies": [
			{"name": "Deny destructive", "when": {"destructiveHint": true}, "action": "deny", "priority": 10},
			{"name": "Default allow", "when": {}, "action": "allow", "priority": 999},
		],
	}

	not allow with input as mock_input(
		"POST", "/mcp",
		mock_bearer(mock_jwt_jarvis),
		"{\"jsonrpc\":\"2.0\",\"id\":207,\"method\":\"tools/call\",\"params\":{\"name\":\"mock-calendar.delete_event\",\"arguments\":{\"id\":\"evt-123\"}}}",
	)
		with catalog as {}
		with grants as mock_grants
		with contextual_routing as {}
		with security_policy as sp_with_policies_only
}

# Test: verb-based policy — only allow "get" verb, deny "create"
test_sp_verb_based_policy if {
	sp_verb := {
		"version": "1.0",
		"tool_annotations": {
			"gmail": {
				"send_email": {
					"annotations": {},
					"verb": "create",
					"labels": [],
				},
			},
		},
		"classifiers": {},
		"policies": [
			{"name": "Allow get only", "when": {"verb": "get"}, "action": "allow", "priority": 50},
			{"name": "Default deny", "when": {}, "action": "deny", "priority": 999},
		],
	}

	# send_email has verb "create" → doesn't match "Allow get only" → "Default deny" → denied
	not allow with input as mock_input(
		"POST", "/mcp",
		mock_bearer(mock_jwt_jarvis),
		"{\"jsonrpc\":\"2.0\",\"id\":208,\"method\":\"tools/call\",\"params\":{\"name\":\"gmail.send_email\",\"arguments\":{\"to\":\"test@test.com\"}}}",
	)
		with catalog as {}
		with grants as mock_grants_with_gmail
		with contextual_routing as {}
		with security_policy as sp_verb
}

# Test: security policy denial surfaces in reason header
test_sp_deny_reason_header if {
	r := reason with input as mock_input(
		"POST", "/mcp",
		mock_bearer(mock_jwt_jarvis),
		"{\"jsonrpc\":\"2.0\",\"id\":209,\"method\":\"tools/call\",\"params\":{\"name\":\"gmail.delete_email\",\"arguments\":{\"id\":\"123\"}}}",
	)
		with catalog as {}
		with grants as mock_grants_with_gmail
		with contextual_routing as {}
		with security_policy as mock_sp_evaluation

	contains(r, "Deny destructive")
}

# Test: security policy tracing headers emitted
test_sp_tracing_headers if {
	rh := response_headers with input as mock_input(
		"POST", "/mcp",
		mock_bearer(mock_jwt_jarvis),
		"{\"jsonrpc\":\"2.0\",\"id\":210,\"method\":\"tools/call\",\"params\":{\"name\":\"gmail.read_email\",\"arguments\":{\"id\":\"123\"}}}",
	)
		with catalog as {}
		with grants as mock_grants_with_gmail
		with contextual_routing as {}
		with security_policy as mock_sp_evaluation

	rh["x-sp-action"] == "allow"
	rh["x-sp-rule"] == "Allow read-only"
	rh["x-sp-verb"] == "get"
	contains(rh["x-sp-labels"], "category:communication")
}

# ============================================================================
# Test: require_approval flow (Step 3 — ApprovalPolicy integration)
# ============================================================================

mock_sp_with_approval := {
	"version": "1.0",
	"tool_annotations": {
		"gmail": {
			"send_email": {
				"annotations": {
					"readOnlyHint": false,
					"destructiveHint": false,
					"openWorldHint": true,
				},
				"verb": "create",
				"labels": ["category:communication"],
			},
			"read_email": {
				"annotations": {"readOnlyHint": true},
				"verb": "get",
				"labels": ["category:communication"],
			},
			"delete_email": {
				"annotations": {"destructiveHint": true},
				"verb": "delete",
				"labels": ["category:communication"],
			},
		},
	},
	"classifiers": {
		"gmail": {
			"send_email": [
				{"field": "to", "contains": "@acme.com", "set_labels": ["scope:internal"]},
				{"field": "to", "not_contains": "@acme.com", "set_labels": ["scope:external"]},
			],
		},
	},
	"policies": [
		{"name": "Allow read-only", "when": {"readOnlyHint": true}, "action": "allow", "priority": 50},
		{"name": "Deny destructive", "when": {"destructiveHint": true}, "action": "deny", "priority": 10},
		{"name": "Approve external", "when": {"labels": ["scope:external"]}, "action": "require_approval", "priority": 20, "approvers": ["compliance", "legal"]},
		{"name": "Default allow", "when": {}, "action": "allow", "priority": 999},
	],
}

mock_approval_route := {"gmail": {"*": {
	"policyProtocol": "ApprovalPolicy",
	"instanceId": "apr-001",
	"endpoint": "/npl/policies/ApprovalPolicy/apr-001/evaluate",
}}}

# Test: require_approval with contextual route → denied (NPL unreachable in unit tests)
# In production: ApprovalPolicy would return "pending:<id>" or "allow"
test_sp_require_approval_with_route if {
	# send_email to external → scope:external → "Approve external" → require_approval
	# Contextual route to ApprovalPolicy exists → security_policy_allows passes
	# But http.send fails in unit tests → Rule 3b fails → denied
	not allow with input as mock_input(
		"POST", "/mcp",
		mock_bearer(mock_jwt_jarvis),
		"{\"jsonrpc\":\"2.0\",\"id\":300,\"method\":\"tools/call\",\"params\":{\"name\":\"gmail.send_email\",\"arguments\":{\"to\":\"external@other.com\",\"subject\":\"test\",\"body\":\"hello\"}}}",
	)
		with catalog as {}
		with grants as mock_grants_with_gmail
		with contextual_routing as mock_approval_route
		with security_policy as mock_sp_with_approval
}

# Test: require_approval without contextual route → denied (no approval mechanism)
test_sp_require_approval_without_route if {
	not allow with input as mock_input(
		"POST", "/mcp",
		mock_bearer(mock_jwt_jarvis),
		"{\"jsonrpc\":\"2.0\",\"id\":301,\"method\":\"tools/call\",\"params\":{\"name\":\"gmail.send_email\",\"arguments\":{\"to\":\"external@other.com\",\"subject\":\"test\",\"body\":\"hello\"}}}",
	)
		with catalog as {}
		with grants as mock_grants_with_gmail
		with contextual_routing as {}
		with security_policy as mock_sp_with_approval
}

# Test: require_approval without route → reason includes "no approval route"
test_sp_require_approval_no_route_reason if {
	r := reason with input as mock_input(
		"POST", "/mcp",
		mock_bearer(mock_jwt_jarvis),
		"{\"jsonrpc\":\"2.0\",\"id\":302,\"method\":\"tools/call\",\"params\":{\"name\":\"gmail.send_email\",\"arguments\":{\"to\":\"external@other.com\",\"subject\":\"test\",\"body\":\"hello\"}}}",
	)
		with catalog as {}
		with grants as mock_grants_with_gmail
		with contextual_routing as {}
		with security_policy as mock_sp_with_approval

	contains(r, "require_approval")
	contains(r, "Approve external")
	contains(r, "no approval route")
}

# Test: deny action still works even with approval route configured
test_sp_deny_not_bypassed_by_approval_route if {
	# delete_email → destructiveHint=true → "Deny destructive" → deny
	# Even though approval route exists, deny takes precedence (lower priority)
	not allow with input as mock_input(
		"POST", "/mcp",
		mock_bearer(mock_jwt_jarvis),
		"{\"jsonrpc\":\"2.0\",\"id\":303,\"method\":\"tools/call\",\"params\":{\"name\":\"gmail.delete_email\",\"arguments\":{\"id\":\"123\"}}}",
	)
		with catalog as {}
		with grants as mock_grants_with_gmail
		with contextual_routing as mock_approval_route
		with security_policy as mock_sp_with_approval
}

# Test: allow action still works with approval route configured
test_sp_allow_bypasses_approval_route if {
	# read_email → readOnlyHint=true → "Allow read-only" → allow
	# Approval route exists but sp_action is "allow", not "require_approval"
	allow with input as mock_input(
		"POST", "/mcp",
		mock_bearer(mock_jwt_jarvis),
		"{\"jsonrpc\":\"2.0\",\"id\":304,\"method\":\"tools/call\",\"params\":{\"name\":\"gmail.read_email\",\"arguments\":{\"id\":\"123\"}}}",
	)
		with catalog as {}
		with grants as mock_grants_with_gmail
		with contextual_routing as mock_approval_route
		with security_policy as mock_sp_with_approval
}

# Test: internal send → scope:internal (no scope:external) → "Default allow" even with approval route
test_sp_internal_send_allowed_with_approval_route if {
	allow with input as mock_input(
		"POST", "/mcp",
		mock_bearer(mock_jwt_jarvis),
		"{\"jsonrpc\":\"2.0\",\"id\":305,\"method\":\"tools/call\",\"params\":{\"name\":\"gmail.send_email\",\"arguments\":{\"to\":\"colleague@acme.com\",\"subject\":\"test\",\"body\":\"hello\"}}}",
	)
		with catalog as {}
		with grants as mock_grants_with_gmail
		with contextual_routing as mock_approval_route
		with security_policy as mock_sp_with_approval
}

# Test: approval pending reason header when route exists but NPL unreachable
test_sp_approval_pending_reason if {
	r := reason with input as mock_input(
		"POST", "/mcp",
		mock_bearer(mock_jwt_jarvis),
		"{\"jsonrpc\":\"2.0\",\"id\":306,\"method\":\"tools/call\",\"params\":{\"name\":\"gmail.send_email\",\"arguments\":{\"to\":\"external@other.com\",\"subject\":\"test\",\"body\":\"hello\"}}}",
	)
		with catalog as {}
		with grants as mock_grants_with_gmail
		with contextual_routing as mock_approval_route
		with security_policy as mock_sp_with_approval
		with npl_evaluate_response as "pending:APR-42"

	contains(r, "approval pending")
	contains(r, "APR-42")
	contains(r, "Approve external")
}

# Test: pending_approval_id extraction from npl_evaluate_response
test_pending_approval_id_extracted if {
	id := pending_approval_id with npl_evaluate_response as "pending:APR-7"
	id == "APR-7"
}

# Test: x-approval-id response header emitted when pending
test_approval_id_header_emitted if {
	rh := response_headers with input as mock_input(
		"POST", "/mcp",
		mock_bearer(mock_jwt_jarvis),
		"{\"jsonrpc\":\"2.0\",\"id\":400,\"method\":\"tools/call\",\"params\":{\"name\":\"gmail.send_email\",\"arguments\":{\"to\":\"external@other.com\",\"subject\":\"test\",\"body\":\"hello\"}}}",
	)
		with catalog as {}
		with grants as mock_grants_with_gmail
		with contextual_routing as mock_approval_route
		with security_policy as mock_sp_with_approval
		with npl_evaluate_response as "pending:APR-99"

	rh["x-approval-id"] == "APR-99"
	rh["retry-after"] == "30"
}

# Test: no approval headers when npl_evaluate_response is "allow"
test_no_approval_headers_when_allowed if {
	rh := response_headers with input as mock_input(
		"POST", "/mcp",
		mock_bearer(mock_jwt_jarvis),
		"{\"jsonrpc\":\"2.0\",\"id\":401,\"method\":\"tools/call\",\"params\":{\"name\":\"gmail.send_email\",\"arguments\":{\"to\":\"external@other.com\",\"subject\":\"test\",\"body\":\"hello\"}}}",
	)
		with catalog as {}
		with grants as mock_grants_with_gmail
		with contextual_routing as mock_approval_route
		with security_policy as mock_sp_with_approval
		with npl_evaluate_response as "allow"

	not rh["x-approval-id"]
	not rh["retry-after"]
}

# Test: denial by approval policy (npl returns "deny")
test_approval_policy_deny_reason if {
	r := reason with input as mock_input(
		"POST", "/mcp",
		mock_bearer(mock_jwt_jarvis),
		"{\"jsonrpc\":\"2.0\",\"id\":402,\"method\":\"tools/call\",\"params\":{\"name\":\"gmail.send_email\",\"arguments\":{\"to\":\"external@other.com\",\"subject\":\"test\",\"body\":\"hello\"}}}",
	)
		with catalog as {}
		with grants as mock_grants_with_gmail
		with contextual_routing as mock_approval_route
		with security_policy as mock_sp_with_approval
		with npl_evaluate_response as "deny"

	contains(r, "denied by approval policy")
}

# Test: sp_approvers extracted from winning require_approval policy
test_sp_approvers_extracted if {
	result := sp_approvers with input as mock_input(
		"POST", "/mcp",
		mock_bearer(mock_jwt_jarvis),
		"{\"jsonrpc\":\"2.0\",\"id\":403,\"method\":\"tools/call\",\"params\":{\"name\":\"gmail.send_email\",\"arguments\":{\"to\":\"external@other.com\",\"subject\":\"test\",\"body\":\"hello\"}}}",
	)
		with catalog as {}
		with grants as mock_grants_with_gmail
		with contextual_routing as mock_approval_route
		with security_policy as mock_sp_with_approval

	result == ["compliance", "legal"]
}

# Test: sp_approvers defaults to empty when no approvers specified
test_sp_approvers_empty_when_not_specified if {
	sp_no_approvers := {
		"version": "1.0",
		"tool_annotations": {
			"gmail": {
				"send_email": {
					"annotations": {"openWorldHint": true},
					"verb": "create",
					"labels": ["category:communication"],
				},
			},
		},
		"classifiers": {
			"gmail": {
				"send_email": [
					{"field": "to", "not_contains": "@acme.com", "set_labels": ["scope:external"]},
				],
			},
		},
		"policies": [
			{"name": "Approve external", "when": {"labels": ["scope:external"]}, "action": "require_approval", "priority": 20},
		],
	}

	result := sp_approvers with input as mock_input(
		"POST", "/mcp",
		mock_bearer(mock_jwt_jarvis),
		"{\"jsonrpc\":\"2.0\",\"id\":404,\"method\":\"tools/call\",\"params\":{\"name\":\"gmail.send_email\",\"arguments\":{\"to\":\"external@other.com\",\"subject\":\"test\",\"body\":\"hello\"}}}",
	)
		with catalog as {}
		with grants as mock_grants_with_gmail
		with contextual_routing as mock_approval_route
		with security_policy as sp_no_approvers

	result == []
}
