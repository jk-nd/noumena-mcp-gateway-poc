# Unit tests for MCP Gateway authorization policy
#
# Run with: opa test policies/ -v
#
# These tests use mock input (simulating Envoy ext_authz requests)
# and mock NPL data (overriding http.send responses).

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

# --- Mock NPL data ---
# These override the http.send-based rules in the main policy via `with` keyword.

mock_enabled_services := {"duckduckgo", "mock-calendar"}

mock_tool_policies := {"duckduckgo": {
	"@id": "tp-1",
	"@state": "active",
	"policyServiceName": "duckduckgo",
	"enabledTools": ["search", "fetch_content"],
}, "mock-calendar": {
	"@id": "tp-2",
	"@state": "active",
	"policyServiceName": "mock-calendar",
	"enabledTools": ["list_events", "create_event"],
}}

mock_suspended_tool_policies := {"duckduckgo": {
	"@id": "tp-1",
	"@state": "suspended",
	"policyServiceName": "duckduckgo",
	"enabledTools": ["search"],
}}

mock_user_access_entries := {
	"jarvis@acme.com": {
		"@id": "ua-1",
		"userId": "jarvis@acme.com",
		"serviceAccess": [
			{"serviceName": "duckduckgo", "allowedTools": ["*"]},
			{"serviceName": "mock-calendar", "allowedTools": ["*"]},
		],
	},
	"alice@acme.com": {
		"@id": "ua-2",
		"userId": "alice@acme.com",
		"serviceAccess": [],
	},
}

# ============================================================================
# Test: Non-tool-call methods are always allowed
# ============================================================================

test_allow_initialize if {
	allow with input as mock_input(
		"POST", "/mcp",
		mock_bearer(mock_jwt_jarvis),
		"{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}",
	)
		with enabled_services as mock_enabled_services
		with tool_policies as mock_tool_policies
		with user_access_entries as mock_user_access_entries
}

test_allow_tools_list if {
	allow with input as mock_input(
		"POST", "/mcp",
		mock_bearer(mock_jwt_jarvis),
		"{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}",
	)
		with enabled_services as mock_enabled_services
		with tool_policies as mock_tool_policies
		with user_access_entries as mock_user_access_entries
}

test_allow_ping if {
	allow with input as mock_input(
		"POST", "/mcp",
		mock_bearer(mock_jwt_jarvis),
		"{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"ping\"}",
	)
		with enabled_services as mock_enabled_services
		with tool_policies as mock_tool_policies
		with user_access_entries as mock_user_access_entries
}

test_allow_notifications if {
	allow with input as mock_input(
		"POST", "/mcp",
		mock_bearer(mock_jwt_jarvis),
		"{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}",
	)
		with enabled_services as mock_enabled_services
		with tool_policies as mock_tool_policies
		with user_access_entries as mock_user_access_entries
}

# ============================================================================
# Test: Stream setup (GET /mcp)
# ============================================================================

test_allow_stream_setup_for_granted_user if {
	allow with input as mock_input("GET", "/mcp", mock_bearer(mock_jwt_jarvis), "")
		with enabled_services as mock_enabled_services
		with tool_policies as mock_tool_policies
		with user_access_entries as mock_user_access_entries
}

test_deny_stream_setup_for_user_without_access if {
	not allow with input as mock_input("GET", "/mcp", mock_bearer(mock_jwt_alice), "")
		with enabled_services as mock_enabled_services
		with tool_policies as mock_tool_policies
		with user_access_entries as mock_user_access_entries
}

test_deny_stream_setup_for_unknown_user if {
	not allow with input as mock_input("GET", "/mcp", mock_bearer(mock_jwt_unknown), "")
		with enabled_services as mock_enabled_services
		with tool_policies as mock_tool_policies
		with user_access_entries as mock_user_access_entries
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
		with enabled_services as mock_enabled_services
		with tool_policies as mock_tool_policies
		with user_access_entries as mock_user_access_entries
}

test_allow_calendar_tool_call if {
	allow with input as mock_input(
		"POST", "/mcp",
		mock_bearer(mock_jwt_jarvis),
		"{\"jsonrpc\":\"2.0\",\"id\":11,\"method\":\"tools/call\",\"params\":{\"name\":\"mock-calendar.list_events\",\"arguments\":{\"date\":\"2026-02-13\"}}}",
	)
		with enabled_services as mock_enabled_services
		with tool_policies as mock_tool_policies
		with user_access_entries as mock_user_access_entries
}

# ============================================================================
# Test: tools/call — denied cases
# ============================================================================

test_deny_tool_call_service_not_enabled if {
	not allow with input as mock_input(
		"POST", "/mcp",
		mock_bearer(mock_jwt_jarvis),
		"{\"jsonrpc\":\"2.0\",\"id\":20,\"method\":\"tools/call\",\"params\":{\"name\":\"github.create_issue\",\"arguments\":{}}}",
	)
		with enabled_services as mock_enabled_services
		with tool_policies as mock_tool_policies
		with user_access_entries as mock_user_access_entries
}

test_deny_tool_call_service_suspended if {
	not allow with input as mock_input(
		"POST", "/mcp",
		mock_bearer(mock_jwt_jarvis),
		"{\"jsonrpc\":\"2.0\",\"id\":21,\"method\":\"tools/call\",\"params\":{\"name\":\"duckduckgo.search\",\"arguments\":{\"query\":\"hello\"}}}",
	)
		with enabled_services as mock_enabled_services
		with tool_policies as mock_suspended_tool_policies
		with user_access_entries as mock_user_access_entries
}

test_deny_tool_call_tool_not_enabled if {
	# ToolPolicy for duckduckgo only has "search" enabled, not "nonexistent_tool"
	limited_policies := {"duckduckgo": {
		"@id": "tp-1",
		"@state": "active",
		"policyServiceName": "duckduckgo",
		"enabledTools": ["search"],
	}}

	not allow with input as mock_input(
		"POST", "/mcp",
		mock_bearer(mock_jwt_jarvis),
		"{\"jsonrpc\":\"2.0\",\"id\":22,\"method\":\"tools/call\",\"params\":{\"name\":\"duckduckgo.fetch_content\",\"arguments\":{\"url\":\"http://example.com\"}}}",
	)
		with enabled_services as mock_enabled_services
		with tool_policies as limited_policies
		with user_access_entries as mock_user_access_entries
}

test_deny_tool_call_user_no_access if {
	not allow with input as mock_input(
		"POST", "/mcp",
		mock_bearer(mock_jwt_alice),
		"{\"jsonrpc\":\"2.0\",\"id\":23,\"method\":\"tools/call\",\"params\":{\"name\":\"duckduckgo.search\",\"arguments\":{\"query\":\"hello\"}}}",
	)
		with enabled_services as mock_enabled_services
		with tool_policies as mock_tool_policies
		with user_access_entries as mock_user_access_entries
}

test_deny_tool_call_unknown_user if {
	not allow with input as mock_input(
		"POST", "/mcp",
		mock_bearer(mock_jwt_unknown),
		"{\"jsonrpc\":\"2.0\",\"id\":24,\"method\":\"tools/call\",\"params\":{\"name\":\"duckduckgo.search\",\"arguments\":{\"query\":\"hello\"}}}",
	)
		with enabled_services as mock_enabled_services
		with tool_policies as mock_tool_policies
		with user_access_entries as mock_user_access_entries
}

test_deny_tool_call_missing_tool_name if {
	not allow with input as mock_input(
		"POST", "/mcp",
		mock_bearer(mock_jwt_jarvis),
		"{\"jsonrpc\":\"2.0\",\"id\":25,\"method\":\"tools/call\",\"params\":{}}",
	)
		with enabled_services as mock_enabled_services
		with tool_policies as mock_tool_policies
		with user_access_entries as mock_user_access_entries
}

test_deny_tool_call_no_auth if {
	not allow with input as mock_input_no_auth(
		"POST", "/mcp",
		"{\"jsonrpc\":\"2.0\",\"id\":26,\"method\":\"tools/call\",\"params\":{\"name\":\"duckduckgo.search\",\"arguments\":{\"query\":\"hello\"}}}",
	)
		with enabled_services as mock_enabled_services
		with tool_policies as mock_tool_policies
		with user_access_entries as mock_user_access_entries
}

# ============================================================================
# Test: Fail-closed — empty NPL data denies everything
# ============================================================================

test_deny_tools_call_when_npl_data_empty if {
	not allow with input as mock_input(
		"POST", "/mcp",
		mock_bearer(mock_jwt_jarvis),
		"{\"jsonrpc\":\"2.0\",\"id\":30,\"method\":\"tools/call\",\"params\":{\"name\":\"duckduckgo.search\",\"arguments\":{\"query\":\"hello\"}}}",
	)
		with enabled_services as set()
		with tool_policies as {}
		with user_access_entries as {}
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
		with enabled_services as mock_enabled_services
		with tool_policies as mock_tool_policies
		with user_access_entries as mock_user_access_entries
}

# ============================================================================
# Test: Specific tool access (non-wildcard)
# ============================================================================

test_specific_tool_access_allowed if {
	specific_access := {
		"jarvis@acme.com": {
			"@id": "ua-1",
			"userId": "jarvis@acme.com",
			"serviceAccess": [{"serviceName": "duckduckgo", "allowedTools": ["search"]}],
		},
	}

	allow with input as mock_input(
		"POST", "/mcp",
		mock_bearer(mock_jwt_jarvis),
		"{\"jsonrpc\":\"2.0\",\"id\":50,\"method\":\"tools/call\",\"params\":{\"name\":\"duckduckgo.search\",\"arguments\":{\"query\":\"hello\"}}}",
	)
		with enabled_services as mock_enabled_services
		with tool_policies as mock_tool_policies
		with user_access_entries as specific_access
}

test_specific_tool_access_denied_for_other_tool if {
	specific_access := {
		"jarvis@acme.com": {
			"@id": "ua-1",
			"userId": "jarvis@acme.com",
			"serviceAccess": [{"serviceName": "duckduckgo", "allowedTools": ["search"]}],
		},
	}

	not allow with input as mock_input(
		"POST", "/mcp",
		mock_bearer(mock_jwt_jarvis),
		"{\"jsonrpc\":\"2.0\",\"id\":51,\"method\":\"tools/call\",\"params\":{\"name\":\"duckduckgo.fetch_content\",\"arguments\":{\"url\":\"http://example.com\"}}}",
	)
		with enabled_services as mock_enabled_services
		with tool_policies as mock_tool_policies
		with user_access_entries as specific_access
}

# ============================================================================
# Test: No ToolPolicy for service — allow if service is enabled and user has access
# ============================================================================

test_allow_when_no_tool_policy_exists if {
	allow with input as mock_input(
		"POST", "/mcp",
		mock_bearer(mock_jwt_jarvis),
		"{\"jsonrpc\":\"2.0\",\"id\":60,\"method\":\"tools/call\",\"params\":{\"name\":\"mock-calendar.list_events\",\"arguments\":{\"date\":\"2026-02-13\"}}}",
	)
		with enabled_services as mock_enabled_services
		with tool_policies as {}
		with user_access_entries as mock_user_access_entries
}

# ============================================================================
# Test: Default deny — completely empty input
# ============================================================================

test_default_deny_empty_body_post if {
	not allow with input as mock_input("POST", "/mcp", "", "")
		with enabled_services as mock_enabled_services
		with tool_policies as mock_tool_policies
		with user_access_entries as mock_user_access_entries
}
