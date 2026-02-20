# MCP Gateway Authorization Policy — v4 Three-Layer Governance
#
# Three layers, each answers one question:
#   Layer 1 — Catalog: Is this service/tool available?
#   Layer 2 — Access Rules: Is this caller allowed?
#   Layer 3 — NPL Governance: Does this gated call comply? (runtime NPL call)
#
# Fail-closed: default allow = false

package envoy.authz

import input.attributes.request.http as http_request
import rego.v1

# --- Default deny ---

default allow := false

# --- Bundle data (loaded in background, zero network I/O) ---

default catalog := {}

default access_rules := []

default revoked_subjects := []

default governance_instances := {}

catalog := data.catalog

access_rules := data.access_rules

revoked_subjects := data.revoked_subjects

governance_instances := data.governance_instances

npl_url := data.npl_url

governance_evaluator_url := data.governance_evaluator_url

# --- JWT decoding ---

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

parsed_body := json.unmarshal(http_request.body) if {
	http_request.body
	http_request.body != ""
}

is_tool_call if {
	parsed_body.method == "tools/call"
}

jsonrpc_method := parsed_body.method if {
	parsed_body
	parsed_body.method
}

qualified_name := parsed_body.params.name if {
	is_tool_call
	parsed_body.params.name
}

name_parts := split(qualified_name, ".") if {
	qualified_name
	contains(qualified_name, ".")
}

service_name := name_parts[0] if {
	name_parts
}

tool_name := concat(".", array.slice(name_parts, 1, count(name_parts))) if {
	name_parts
}

# --- MCP Session ID ---

mcp_session_id := http_request.headers["mcp-session-id"] if {
	http_request.headers["mcp-session-id"]
}

default mcp_session_id := ""

# --- Layer 1: Catalog check ---

service_enabled if {
	catalog[service_name].enabled == true
}

tool_in_catalog if {
	catalog[service_name].tools[tool_name]
}

tool_tag := catalog[service_name].tools[tool_name].tag

# --- Layer 2: Access rules ---

# Match a JWT claim value against a rule's expected value.
# Handles both scalar ("acme") and array (["acme"]) JWT claims,
# since Keycloak encodes multi-valued attributes as arrays.
claim_value_matches(jwt_val, expected) if {
	jwt_val == expected
}

claim_value_matches(jwt_val, expected) if {
	is_array(jwt_val)
	expected in jwt_val
}

caller_authorized if {
	some rule in access_rules
	access_matches(rule)
}

access_matches(rule) if {
	# Claim-based match
	rule.matcher.matchType == "claims"
	every k, v in rule.matcher.claims {
		claim_value_matches(jwt_payload[k], v)
	}
	service_match(rule.allow.services)
	tool_match(rule.allow.tools)
}

access_matches(rule) if {
	# Identity-based match
	rule.matcher.matchType == "identity"
	rule.matcher.identity == user_id
	service_match(rule.allow.services)
	tool_match(rule.allow.tools)
}

service_match(services) if {
	some s in services
	s == "*"
}

service_match(services) if {
	some s in services
	s == service_name
}

tool_match(tools) if {
	some t in tools
	t == "*"
}

tool_match(tools) if {
	some t in tools
	t == tool_name
}

# --- Revocation check ---

caller_not_revoked if {
	not user_id in revoked_subjects
}

# --- Stream setup detection ---

is_stream_setup if {
	http_request.method == "GET"
	contains(http_request.path, "/mcp")
}

is_stream_setup if {
	not parsed_body
	not http_request.body
}

is_stream_setup if {
	http_request.body == ""
}

# --- Allow: Stream setup (GET /mcp) ---

allow if {
	is_stream_setup
	bearer_token
	user_id
}

# --- Allow: Non-tool-call methods (initialize, tools/list, ping, etc.) ---

allow if {
	not is_stream_setup
	jsonrpc_method
	jsonrpc_method != "tools/call"
	bearer_token
}

# --- Allow: Open tools (catalog + access rules sufficient) ---

allow if {
	not is_stream_setup
	is_tool_call
	service_name
	tool_name
	user_id
	service_enabled
	tool_in_catalog
	tool_tag == "open"
	caller_authorized
	caller_not_revoked
}

# --- Allow: Gated tools (requires NPL allow) ---

allow if {
	not is_stream_setup
	is_tool_call
	service_name
	tool_name
	user_id
	service_enabled
	tool_in_catalog
	tool_tag == "gated"
	caller_authorized
	caller_not_revoked
	npl_decision.decision == "allow"
}

# --- Governance evaluate call (gated path only) ---
# Calls the governance-evaluator sidecar, which handles constraint
# evaluation and routes to NPL for approval workflows when needed.

# Flatten JWT claims to simple key-value map.
# Handles both scalar strings and single-element arrays from Keycloak.
caller_claims_flat[k] := v if {
	some k, v in jwt_payload
	is_string(v)
}

caller_claims_flat[k] := v if {
	some k, arr in jwt_payload
	is_array(arr)
	count(arr) > 0
	v := arr[0]
	is_string(v)
}

governance_response := http.send({
	"method": "POST",
	"url": sprintf("%s/evaluate", [governance_evaluator_url]),
	"headers": {
		"Content-Type": "application/json",
	},
	"body": {
		"serviceName": service_name,
		"toolName": tool_name,
		"callerIdentity": user_id,
		"callerClaims": caller_claims_flat,
		"arguments": json.marshal(object.get(parsed_body, ["params", "arguments"], {})),
		"sessionId": mcp_session_id,
		"requestPayload": json.marshal(parsed_body),
	},
	"timeout": "5s",
}) if {
	governance_evaluator_url
	service_name
}

npl_decision := governance_response.body if {
	governance_response
	governance_response.status_code == 200
}

# --- Response headers ---

# Reason for denial
reason := "No authentication" if {
	not bearer_token
}

reason := "No user identity" if {
	bearer_token
	not user_id
}

reason := sprintf("Service '%s' not in catalog or disabled", [service_name]) if {
	is_tool_call
	service_name
	user_id
	not service_enabled
}

reason := sprintf("Tool '%s' not in catalog for service '%s'", [tool_name, service_name]) if {
	is_tool_call
	service_name
	user_id
	service_enabled
	not tool_in_catalog
}

reason := sprintf("User '%s' not authorized by any access rule", [user_id]) if {
	is_tool_call
	service_name
	user_id
	service_enabled
	tool_in_catalog
	not caller_authorized
}

reason := sprintf("User '%s' is revoked", [user_id]) if {
	is_tool_call
	service_name
	user_id
	service_enabled
	tool_in_catalog
	caller_authorized
	not caller_not_revoked
}

reason := sprintf("Gated tool pending: %s", [npl_decision.requestId]) if {
	is_tool_call
	service_name
	user_id
	service_enabled
	tool_in_catalog
	tool_tag == "gated"
	caller_authorized
	caller_not_revoked
	npl_decision
	npl_decision.decision == "pending"
}

reason := sprintf("Gated tool denied: %s", [npl_decision.message]) if {
	is_tool_call
	service_name
	user_id
	service_enabled
	tool_in_catalog
	tool_tag == "gated"
	caller_authorized
	caller_not_revoked
	npl_decision
	npl_decision.decision == "deny"
}

reason := "Authorized" if {
	allow
}

# --- Granted services computation (for tools/list filtering) ---

granted_service_names contains svc if {
	user_id
	some rule in access_rules
	access_rule_matches_caller(rule)
	some svc in rule.allow.services
	svc != "*"
	catalog[svc].enabled == true
}

granted_service_names contains svc if {
	user_id
	some rule in access_rules
	access_rule_matches_caller(rule)
	some s in rule.allow.services
	s == "*"
	some svc, entry in catalog
	entry.enabled == true
}

access_rule_matches_caller(rule) if {
	rule.matcher.matchType == "claims"
	every k, v in rule.matcher.claims {
		claim_value_matches(jwt_payload[k], v)
	}
}

access_rule_matches_caller(rule) if {
	rule.matcher.matchType == "identity"
	rule.matcher.identity == user_id
}

# --- Structured decision for OPA envoy plugin ---

result := {"allowed": true, "headers": headers, "response_headers_to_add": response_headers} if {
	allow
	response_headers
}

result := {"allowed": true, "headers": headers} if {
	allow
	not response_headers
}

result := {"allowed": false, "headers": object.union(headers, response_headers)} if {
	not allow
	response_headers
}

result := {"allowed": false, "headers": headers} if {
	not allow
	not response_headers
}

response_headers["x-authz-reason"] := reason if {
	reason
}

# --- Upstream request headers ---

headers["x-user-id"] := user_id if {
	user_id
}

headers["x-granted-services"] := concat(",", sort(granted_service_names)) if {
	jsonrpc_method == "tools/list"
	user_id
}

headers["x-mcp-service"] := service_name if {
	service_name
}

headers["x-bundle-revision"] := data._bundle_metadata.revision if {
	data._bundle_metadata.revision
}

# Pending: return request ID and retry hint
response_headers["x-request-id"] := npl_decision.requestId if {
	npl_decision
	npl_decision.decision == "pending"
}

response_headers["retry-after"] := "30" if {
	npl_decision
	npl_decision.decision == "pending"
}
