# MCP Gateway Authorization Policy — OPA ext_authz for Envoy AI Gateway
#
# Policy data is loaded from OPA bundle (zero network I/O during evaluation).
# Bundle server subscribes to NPL SSE and rebuilds on every state change.
#
# Decision flow:
#   1. Read policy data from bundle (in-memory, ~1ms)
#   2. Decode user JWT from Authorization header
#   3. Parse JSON-RPC body from Envoy ext_authz input
#   4. Evaluate allow/deny based on method, service, tool, user
#
# Fail-closed: default allow = false

package envoy.authz

import input.attributes.request.http as http_request
import rego.v1

# --- Default deny ---

default allow := false

# --- Policy data from OPA bundle (loaded in background, zero network I/O) ---
# Bundle server subscribes to NPL SSE and rebuilds on every state change.

default enabled_services := {}

default tool_policies := {}

default user_access_entries := {}

enabled_services := data.enabled_services

tool_policies := data.tool_policies

user_access_entries := data.user_access_entries

# Check if a service's policy is in "active" state (not suspended)
service_policy_active(service_name) if {
	policy := tool_policies[service_name]
	policy["@state"] == "active"
}

# Check if a service's policy is in "active" state — no policy means not suspended
service_policy_active(service_name) if {
	not tool_policies[service_name]
}

# Get enabled tools for a service from ToolPolicy
tool_enabled(service_name, tool_name) if {
	policy := tool_policies[service_name]
	some tool in policy.enabledTools
	tool == tool_name
}

# Check if user has access to a specific service+tool
user_has_tool_access(user_id, service_name, tool_name) if {
	entry := user_access_entries[user_id]
	some sa in entry.serviceAccess
	sa.serviceName == service_name
	some tool in sa.allowedTools
	tool == "*"
}

user_has_tool_access(user_id, service_name, tool_name) if {
	entry := user_access_entries[user_id]
	some sa in entry.serviceAccess
	sa.serviceName == service_name
	some tool in sa.allowedTools
	tool == tool_name
}

# Check if user has ANY service access (for stream setup)
user_has_any_access(user_id) if {
	entry := user_access_entries[user_id]
	count(entry.serviceAccess) > 0
}

# --- JWT decoding ---

# Extract the Bearer token from Authorization header
bearer_token := t if {
	auth_header := http_request.headers.authorization
	startswith(auth_header, "Bearer ")
	t := substring(auth_header, 7, -1)
}

bearer_token := t if {
	auth_header := http_request.headers.authorization
	startswith(auth_header, "bearer ")
	t := substring(auth_header, 7, -1)
}

# Decode JWT payload (no signature verification — Envoy jwt_authn already validated)
jwt_payload := payload if {
	[_, payload, _] := io.jwt.decode(bearer_token)
}

# Extract userId: email > preferred_username > sub
user_id := jwt_payload.email if {
	jwt_payload.email
}

user_id := jwt_payload.preferred_username if {
	not jwt_payload.email
	jwt_payload.preferred_username
}

user_id := jwt_payload.sub if {
	not jwt_payload.email
	not jwt_payload.preferred_username
	jwt_payload.sub
}

# --- JSON-RPC body parsing ---

# Parse the request body as JSON-RPC
parsed_body := json.unmarshal(http_request.body) if {
	http_request.body
	http_request.body != ""
}

jsonrpc_method := parsed_body.method if {
	parsed_body
	parsed_body.method
}

jsonrpc_tool_name := parsed_body.params.name if {
	parsed_body
	parsed_body.params
	parsed_body.params.name
}

# --- Tool namespace parsing ---
# "duckduckgo.search" → service=duckduckgo, tool=search
# "search" → service=null, tool=search

parsed_service_name := service if {
	jsonrpc_tool_name
	contains(jsonrpc_tool_name, ".")
	parts := split(jsonrpc_tool_name, ".")
	service := parts[0]
}

parsed_tool_name := tool if {
	jsonrpc_tool_name
	contains(jsonrpc_tool_name, ".")
	parts := split(jsonrpc_tool_name, ".")
	tool := concat(".", array.slice(parts, 1, count(parts)))
}

parsed_tool_name := jsonrpc_tool_name if {
	jsonrpc_tool_name
	not contains(jsonrpc_tool_name, ".")
}

# For non-namespaced tools, find the service by searching user access entries
resolved_service_name := parsed_service_name if {
	parsed_service_name
}

resolved_service_name := svc if {
	not parsed_service_name
	jsonrpc_tool_name
	entry := user_access_entries[user_id]
	some sa in entry.serviceAccess
	svc := sa.serviceName
	some tool in sa.allowedTools
	tool_matches(tool, parsed_tool_name)
}

tool_matches(allowed_tool, requested_tool) if {
	allowed_tool == "*"
}

tool_matches(allowed_tool, requested_tool) if {
	allowed_tool == requested_tool
}

# --- Allow rules ---

# Rule 1: Stream setup (GET /mcp — empty body, SSE notification stream)
# User must be authenticated and have at least some tool access
allow if {
	is_stream_setup
	user_id
	user_has_any_access(user_id)
}

is_stream_setup if {
	http_request.method == "GET"
	contains(http_request.path, "/mcp")
}

# Also allow stream setup if body is empty on POST (shouldn't happen but be safe)
is_stream_setup if {
	not parsed_body
	not http_request.body
}

is_stream_setup if {
	http_request.body == ""
}

# Rule 2: Non-tool-call methods — allow through
# initialize, tools/list, ping, notifications/*, completion/complete
allow if {
	not is_stream_setup
	jsonrpc_method
	jsonrpc_method != "tools/call"
}

# Rule 3: tools/call — full RBAC check
allow if {
	not is_stream_setup
	jsonrpc_method == "tools/call"
	jsonrpc_tool_name
	user_id
	resolved_service_name

	# Service must be enabled in ServiceRegistry
	enabled_services[resolved_service_name]

	# Service policy must be active (not suspended)
	service_policy_active(resolved_service_name)

	# Tool must be enabled in ToolPolicy (if policy exists for this service)
	tool_enabled_or_no_policy(resolved_service_name, parsed_tool_name)

	# User must have access to this service+tool
	user_has_tool_access(user_id, resolved_service_name, parsed_tool_name)
}

# If ToolPolicy exists for this service, the tool must be in enabledTools
# If no ToolPolicy exists, allow (service is enabled in registry, that's enough)
tool_enabled_or_no_policy(service_name, tool_name) if {
	tool_policies[service_name]
	tool_enabled(service_name, tool_name)
}

tool_enabled_or_no_policy(service_name, tool_name) if {
	not tool_policies[service_name]
}

# --- Response headers for debugging ---

# Provide reason in response headers when denying
reason := "Stream setup: user has no service access" if {
	is_stream_setup
	user_id
	not user_has_any_access(user_id)
}

reason := "Stream setup: no user identity" if {
	is_stream_setup
	not user_id
}

reason := "tools/call: missing tool name" if {
	not is_stream_setup
	jsonrpc_method == "tools/call"
	not jsonrpc_tool_name
}

reason := "tools/call: no user identity" if {
	not is_stream_setup
	jsonrpc_method == "tools/call"
	jsonrpc_tool_name
	not user_id
}

reason := sprintf("tools/call: service '%s' not enabled", [resolved_service_name]) if {
	not is_stream_setup
	jsonrpc_method == "tools/call"
	jsonrpc_tool_name
	user_id
	resolved_service_name
	not enabled_services[resolved_service_name]
}

reason := sprintf("tools/call: service '%s' is suspended", [resolved_service_name]) if {
	not is_stream_setup
	jsonrpc_method == "tools/call"
	jsonrpc_tool_name
	user_id
	resolved_service_name
	enabled_services[resolved_service_name]
	not service_policy_active(resolved_service_name)
}

reason := sprintf("tools/call: tool '%s' not enabled for service '%s'", [parsed_tool_name, resolved_service_name]) if {
	not is_stream_setup
	jsonrpc_method == "tools/call"
	jsonrpc_tool_name
	user_id
	resolved_service_name
	enabled_services[resolved_service_name]
	service_policy_active(resolved_service_name)
	tool_policies[resolved_service_name]
	not tool_enabled(resolved_service_name, parsed_tool_name)
}

reason := sprintf("tools/call: user '%s' has no access to %s.%s", [user_id, resolved_service_name, parsed_tool_name]) if {
	not is_stream_setup
	jsonrpc_method == "tools/call"
	jsonrpc_tool_name
	user_id
	resolved_service_name
	enabled_services[resolved_service_name]
	service_policy_active(resolved_service_name)
	tool_enabled_or_no_policy(resolved_service_name, parsed_tool_name)
	not user_has_tool_access(user_id, resolved_service_name, parsed_tool_name)
}

reason := "Authorized" if {
	allow
}
