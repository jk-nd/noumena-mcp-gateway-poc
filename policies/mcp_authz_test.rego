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
