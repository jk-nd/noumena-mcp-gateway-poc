# MCP Gateway Authorization Policy — OPA ext_authz for Envoy AI Gateway
#
# Policy data is loaded from OPA bundle (zero network I/O for Layer 1).
# Bundle server subscribes to NPL SSE and rebuilds on every state change.
#
# Two-layer architecture:
#   Layer 1 (99% of requests, ~1ms):
#     OPA checks catalog + grants from bundle → allow/deny
#   Layer 2 (1% of requests, ~60ms):
#     Layer 1 passes + contextual route matches →
#     OPA calls NPL evaluate() via http.send → allow/deny/pending
#
# Fail-closed: default allow = false

package envoy.authz

import input.attributes.request.http as http_request
import rego.v1

# --- Default deny ---

default allow := false

# --- Policy data from OPA bundle (loaded in background, zero network I/O) ---

default catalog := {}

default grants := {}

default contextual_routing := {}

catalog := data.catalog

grants := data.grants

contextual_routing := data.contextual_routing

# --- NPL URL for Layer 2 contextual calls ---

npl_url := opa.runtime().env.NPL_URL

# --- Catalog checks (combined service + tool) ---

service_available(service_name) if {
	entry := catalog[service_name]
	entry.enabled == true
	entry.suspended == false
}

tool_enabled(service_name, tool_name) if {
	entry := catalog[service_name]
	some tool in entry.enabledTools
	tool == tool_name
}

service_available_or_no_catalog(service_name) if {
	catalog[service_name]
	service_available(service_name)
}

service_available_or_no_catalog(service_name) if {
	not catalog[service_name]
}

tool_enabled_or_no_catalog(service_name, tool_name) if {
	catalog[service_name]
	tool_enabled(service_name, tool_name)
}

tool_enabled_or_no_catalog(service_name, tool_name) if {
	not catalog[service_name]
}

# --- Grant checks ---

user_has_tool_access(subject_id, service_name, tool_name) if {
	some grant in grants[subject_id]
	grant.serviceName == service_name
	some tool in grant.allowedTools
	tool == "*"
}

user_has_tool_access(subject_id, service_name, tool_name) if {
	some grant in grants[subject_id]
	grant.serviceName == service_name
	some tool in grant.allowedTools
	tool == tool_name
}

# Check if user has ANY service access (for stream setup)
user_has_any_access(subject_id) if {
	grant_list := grants[subject_id]
	count(grant_list) > 0
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

# For non-namespaced tools, find the service by searching user grant entries
resolved_service_name := parsed_service_name if {
	parsed_service_name
}

resolved_service_name := svc if {
	not parsed_service_name
	jsonrpc_tool_name
	some grant in grants[user_id]
	svc := grant.serviceName
	some tool in grant.allowedTools
	tool_matches(tool, parsed_tool_name)
}

tool_matches(allowed_tool, requested_tool) if {
	allowed_tool == "*"
}

tool_matches(allowed_tool, requested_tool) if {
	allowed_tool == requested_tool
}

# --- Contextual routing (Layer 2) ---
# Find route: specific tool > wildcard

contextual_route := contextual_routing[resolved_service_name][parsed_tool_name]

contextual_route := contextual_routing[resolved_service_name]["*"] if {
	not contextual_routing[resolved_service_name][parsed_tool_name]
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

# Rule 3a: tools/call — fast path (no contextual route)
allow if {
	not is_stream_setup
	jsonrpc_method == "tools/call"
	jsonrpc_tool_name
	user_id
	resolved_service_name

	# Catalog checks
	service_available_or_no_catalog(resolved_service_name)
	tool_enabled_or_no_catalog(resolved_service_name, parsed_tool_name)

	# Grant check
	user_has_tool_access(user_id, resolved_service_name, parsed_tool_name)

	# No contextual route — fast path
	not contextual_route
}

# Rule 3b: tools/call — contextual route (Layer 2: call NPL evaluate())
allow if {
	not is_stream_setup
	jsonrpc_method == "tools/call"
	jsonrpc_tool_name
	user_id
	resolved_service_name

	# Catalog checks
	service_available_or_no_catalog(resolved_service_name)
	tool_enabled_or_no_catalog(resolved_service_name, parsed_tool_name)

	# Grant check
	user_has_tool_access(user_id, resolved_service_name, parsed_tool_name)

	# Contextual route exists — call NPL evaluate()
	contextual_route
	npl_eval := http.send({
		"method": "POST",
		"url": sprintf("%s%s", [npl_url, contextual_route.endpoint]),
		"headers": {
			"Authorization": sprintf("Bearer %s", [data.gateway_token]),
			"Content-Type": "application/json",
		},
		"body": {
			"toolName": parsed_tool_name,
			"callerIdentity": user_id,
			"sessionId": "",
			"arguments": "",
		},
		"timeout": "5s",
	}).body

	npl_eval == "allow"
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

reason := sprintf("tools/call: service '%s' not available", [resolved_service_name]) if {
	not is_stream_setup
	jsonrpc_method == "tools/call"
	jsonrpc_tool_name
	user_id
	resolved_service_name
	not service_available_or_no_catalog(resolved_service_name)
}

reason := sprintf("tools/call: tool '%s' not enabled for service '%s'", [parsed_tool_name, resolved_service_name]) if {
	not is_stream_setup
	jsonrpc_method == "tools/call"
	jsonrpc_tool_name
	user_id
	resolved_service_name
	service_available_or_no_catalog(resolved_service_name)
	catalog[resolved_service_name]
	not tool_enabled(resolved_service_name, parsed_tool_name)
}

reason := sprintf("tools/call: user '%s' has no access to %s.%s", [user_id, resolved_service_name, parsed_tool_name]) if {
	not is_stream_setup
	jsonrpc_method == "tools/call"
	jsonrpc_tool_name
	user_id
	resolved_service_name
	service_available_or_no_catalog(resolved_service_name)
	tool_enabled_or_no_catalog(resolved_service_name, parsed_tool_name)
	not user_has_tool_access(user_id, resolved_service_name, parsed_tool_name)
}

reason := "Authorized" if {
	allow
}
