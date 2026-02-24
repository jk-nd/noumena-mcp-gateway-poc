# MCP Gateway Authorization Policy — v5 In-Memory Governance
#
# Three layers, each answers one question:
#   Layer 1 — Catalog: Is this service/tool available?
#   Layer 2 — Access Rules: Is this caller allowed?
#   Layer 3 — Governance: Do constraints, recipients, and approval workflows pass?
#
# Layer 3 evaluates constraints and recipients in-memory from bundle data.
# Only approval workflows (rare path) call NPL via http.send.
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

default tool_configs := {}

default recipient_bindings := {}

default tool_authorizations := []

catalog := data.catalog

access_rules := data.access_rules

revoked_subjects := data.revoked_subjects

governance_instances := data.governance_instances

tool_configs := data.tool_configs

recipient_bindings := data.recipient_bindings

tool_authorizations := data.tool_authorizations

npl_url := data.npl_url

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

name_parts := split(qualified_name, "__") if {
	qualified_name
	contains(qualified_name, "__")
}

service_name := name_parts[0] if {
	name_parts
}

tool_name := concat("__", array.slice(name_parts, 1, count(name_parts))) if {
	name_parts
}

# --- MCP Session ID ---

mcp_session_id := http_request.headers["mcp-session-id"] if {
	http_request.headers["mcp-session-id"]
}

default mcp_session_id := ""

# --- Parsed arguments ---

parsed_arguments := object.get(parsed_body, ["params", "arguments"], {})

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
# Also handles comma-separated expected values (e.g. "sales,engineering")
# so that a single Map<Text,Text> key can match multiple allowed values.
claim_value_matches(jwt_val, expected) if {
	jwt_val == expected
}

claim_value_matches(jwt_val, expected) if {
	is_array(jwt_val)
	expected in jwt_val
}

claim_value_matches(jwt_val, expected) if {
	contains(expected, ",")
	some part in split(expected, ",")
	trim_space(part) == jwt_val
}

claim_value_matches(jwt_val, expected) if {
	is_array(jwt_val)
	contains(expected, ",")
	some part in split(expected, ",")
	trim_space(part) in jwt_val
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

# --- Layer 3a: Constraint evaluation (in-memory) ---

# Get tool config for current service/tool
current_tool_config := tool_configs[service_name][tool_name]

# All constraints pass if tool config exists and none are violated
all_constraints_pass if {
	current_tool_config
	not any_constraint_violated
}

# Also passes if no tool config exists (no constraints configured)
all_constraints_pass if {
	not current_tool_config
}

# Check if any single constraint is violated
any_constraint_violated if {
	some constraint in current_tool_config.constraints
	constraint_violated(constraint)
}

# Per-operator violation checks

# "in" — value must be in allowed list
constraint_violated(c) if {
	c.operator == "in"
	arg_value := parsed_arguments[c.paramName]
	not arg_value in c.values
}

# "not_in" — value must NOT be in blocked list
constraint_violated(c) if {
	c.operator == "not_in"
	arg_value := parsed_arguments[c.paramName]
	arg_value in c.values
}

# "contains" — string must contain at least one of the values
constraint_violated(c) if {
	c.operator == "contains"
	arg_value := parsed_arguments[c.paramName]
	not any_value_contained(arg_value, c.values)
}

# "not_contains" — string must NOT contain any of the values
constraint_violated(c) if {
	c.operator == "not_contains"
	arg_value := parsed_arguments[c.paramName]
	some v in c.values
	contains(arg_value, v)
}

# "regex" — string must match at least one pattern
constraint_violated(c) if {
	c.operator == "regex"
	arg_value := parsed_arguments[c.paramName]
	not any_regex_matches(arg_value, c.values)
}

# "max_length" — string length must not exceed limit
constraint_violated(c) if {
	c.operator == "max_length"
	arg_value := parsed_arguments[c.paramName]
	max_len := to_number(c.values[0])
	count(arg_value) > max_len
}

# Helpers
any_value_contained(str, values) if {
	some v in values
	contains(str, v)
}

any_regex_matches(str, patterns) if {
	some p in patterns
	regex.match(p, str)
}

# First violated constraint message (for deny reason)
constraint_violation_message := msg if {
	some constraint in current_tool_config.constraints
	constraint_violated(constraint)
	msg := sprintf("Constraint violated: %s", [constraint.description])
}

# --- Layer 3b: Approved Recipients (in-memory) ---

# Whether a recipient binding applies to this service/tool/agent
recipient_binding_applies if {
	binding := recipient_bindings[service_name]
	binding.toolName == tool_name
	agent_matches_binding(binding)
}

# Recipient is approved (domain or email match)
recipient_approved if {
	binding := recipient_bindings[service_name]
	binding.toolName == tool_name
	agent_matches_binding(binding)
	to_value := parsed_arguments[binding.paramName]
	recipient_or_domain_approved(binding, to_value)
}

# Recipient is denied (binding applies but not approved)
recipient_denied if {
	binding := recipient_bindings[service_name]
	binding.toolName == tool_name
	agent_matches_binding(binding)
	to_value := parsed_arguments[binding.paramName]
	not recipient_or_domain_approved(binding, to_value)
}

agent_matches_binding(binding) if {
	binding.agentIdentity == ""
}

agent_matches_binding(binding) if {
	binding.agentIdentity == user_id
}

recipient_or_domain_approved(binding, to_value) if {
	at_idx := indexof(to_value, "@")
	at_idx >= 0
	domain := lower(substring(to_value, at_idx + 1, -1))
	some d in binding.approvedDomains
	lower(d) == domain
}

recipient_or_domain_approved(binding, to_value) if {
	some r in binding.approvedRecipients
	lower(r) == lower(to_value)
}

# --- Layer 3c: ToolAuthorization override (one-shot, calls NPL) ---

has_tool_authorization_override if {
	some auth in tool_authorizations
	auth.serviceName == service_name
	auth.toolName == tool_name
	auth.agentIdentity == user_id
	consume_resp := http.send({
		"method": "POST",
		"url": sprintf("%s/npl/governance/ToolAuthorization/%s/consume", [data.npl_url, auth.instanceId]),
		"headers": {
			"Authorization": sprintf("Bearer %s", [data.gateway_token]),
			"Content-Type": "application/json",
		},
		"body": {},
		"timeout": "5s",
	})
	consume_resp.status_code < 400
}

# --- Layer 3d: Approval workflow (direct NPL call, rare path) ---

requires_approval if {
	current_tool_config.requiresApproval == true
}

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

npl_approval_decision := resp.body if {
	requires_approval
	instance_id := governance_instances[service_name]
	resp := http.send({
		"method": "POST",
		"url": sprintf("%s/npl/governance/ServiceGovernance/%s/evaluate", [data.npl_url, instance_id]),
		"headers": {
			"Authorization": sprintf("Bearer %s", [data.gateway_token]),
			"Content-Type": "application/json",
		},
		"body": {
			"toolName": tool_name,
			"callerIdentity": user_id,
			"callerClaims": caller_claims_flat,
			"arguments": json.marshal(parsed_arguments),
			"sessionId": mcp_session_id,
			"requestPayload": json.marshal(parsed_body),
		},
		"timeout": "5s",
	})
	resp.status_code == 200
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

# --- Allow: ACL tools (catalog + access rules sufficient) ---

allow if {
	not is_stream_setup
	is_tool_call
	service_name
	tool_name
	user_id
	service_enabled
	tool_in_catalog
	tool_tag == "acl"
	caller_authorized
	caller_not_revoked
}

# --- Allow: Logic tools — multiple paths ---

# Path A: Constraints pass, no recipient binding, no approval required
allow if {
	not is_stream_setup
	is_tool_call; service_name; tool_name; user_id
	service_enabled; tool_in_catalog; tool_tag == "logic"
	caller_authorized; caller_not_revoked
	all_constraints_pass
	not recipient_binding_applies
	not requires_approval
}

# Path B: Constraints pass, recipient approved
allow if {
	not is_stream_setup
	is_tool_call; service_name; tool_name; user_id
	service_enabled; tool_in_catalog; tool_tag == "logic"
	caller_authorized; caller_not_revoked
	all_constraints_pass
	recipient_approved
}

# Path C: Constraints pass, approval workflow allows
allow if {
	not is_stream_setup
	is_tool_call; service_name; tool_name; user_id
	service_enabled; tool_in_catalog; tool_tag == "logic"
	caller_authorized; caller_not_revoked
	all_constraints_pass
	not recipient_denied
	npl_approval_decision.decision == "allow"
}

# Path D: Constraints fail but authorization override exists
allow if {
	not is_stream_setup
	is_tool_call; service_name; tool_name; user_id
	service_enabled; tool_in_catalog; tool_tag == "logic"
	caller_authorized; caller_not_revoked
	not all_constraints_pass
	has_tool_authorization_override
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

# Constraint violation reason
reason := constraint_violation_message if {
	is_tool_call
	service_name
	user_id
	service_enabled
	tool_in_catalog
	tool_tag == "logic"
	caller_authorized
	caller_not_revoked
	not all_constraints_pass
	not has_tool_authorization_override
	constraint_violation_message
}

# Recipient denied reason
reason := sprintf("Recipient not approved for %s.%s", [service_name, tool_name]) if {
	is_tool_call
	service_name
	user_id
	service_enabled
	tool_in_catalog
	tool_tag == "logic"
	caller_authorized
	caller_not_revoked
	all_constraints_pass
	recipient_denied
}

# Approval workflow pending
reason := sprintf("Logic tool pending: %s", [npl_approval_decision.requestId]) if {
	is_tool_call
	service_name
	user_id
	service_enabled
	tool_in_catalog
	tool_tag == "logic"
	caller_authorized
	caller_not_revoked
	all_constraints_pass
	not recipient_denied
	npl_approval_decision
	npl_approval_decision.decision == "pending"
}

# Approval workflow denied
reason := sprintf("Logic tool denied: %s", [npl_approval_decision.message]) if {
	is_tool_call
	service_name
	user_id
	service_enabled
	tool_in_catalog
	tool_tag == "logic"
	caller_authorized
	caller_not_revoked
	all_constraints_pass
	not recipient_denied
	npl_approval_decision
	npl_approval_decision.decision == "deny"
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

# --- Visible tools computation (for tools/list response filtering) ---

visible_tool_names contains qualified if {
	some svc in granted_service_names
	some tool_name_entry, _ in catalog[svc].tools
	qualified := concat("__", [svc, tool_name_entry])
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
response_headers["x-request-id"] := npl_approval_decision.requestId if {
	npl_approval_decision
	npl_approval_decision.decision == "pending"
}

response_headers["retry-after"] := "30" if {
	npl_approval_decision
	npl_approval_decision.decision == "pending"
}

# Visible tools for tools/list response filtering (Lua reads this on response path)
response_headers["x-visible-tools"] := concat(",", sort(visible_tool_names)) if {
	jsonrpc_method == "tools/list"
	count(visible_tool_names) > 0
}
