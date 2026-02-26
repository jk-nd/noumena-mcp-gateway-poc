# MCP Gateway Authorization Policy — v5 Three-Tier Governance
#
# Three tiers, each answers one question:
#   Tier 1 — Access: Is this caller allowed? (catalog + access rules, fail-closed)
#   Tier 2 — Guardrails: Do constraints and allowlists pass? (in-memory)
#   Tier 3 — Workflow: Does the approval workflow allow? (NPL call, rare path)
#
# No tags — governance is derived from configuration:
#   - No access rule → denied (Tier 1)
#   - Access rule + no guardrails/workflow → allowed
#   - Access rule + guardrails configured → must also pass guardrails
#   - Access rule + workflow configured → must also pass workflow
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

default guardrails := {}

default workflow_config := {}

default workflow_instances := {}

default tool_authorizations := []

catalog := data.catalog

access_rules := data.access_rules

revoked_subjects := data.revoked_subjects

guardrails := data.guardrails

workflow_config := data.workflow_config

workflow_instances := data.workflow_instances

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

# --- Tier 1: Catalog + Access ---

service_enabled if {
	catalog[service_name].enabled == true
}

tool_in_catalog if {
	catalog[service_name].tools[tool_name]
}

# --- Tier 1: Access rules ---

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

# --- Tier 2: Guardrails — Constraint evaluation (in-memory) ---

# Get guardrails for current service/tool
current_guardrails := guardrails[service_name][tool_name]

# Whether guardrails exist for this tool
has_guardrails if {
	current_guardrails
}

# All constraints pass if guardrails exist and none are violated
all_constraints_pass if {
	current_guardrails
	not any_constraint_violated
}

# Also passes if no guardrails exist (no constraints configured)
all_constraints_pass if {
	not current_guardrails
}

# Check if any single constraint is violated
any_constraint_violated if {
	some constraint in current_guardrails.constraints
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
	some constraint in current_guardrails.constraints
	constraint_violated(constraint)
	msg := sprintf("Constraint violated: %s", [constraint.description])
}

# --- Tier 2: Allowlist evaluation (in-memory) ---

# Whether any allowlist applies to this service/tool/caller
allowlist_applies if {
	current_guardrails
	some al in current_guardrails.allowlists
	allowlist_caller_matches(al)
}

# Allowlist check passed (value approved)
allowlist_approved if {
	current_guardrails
	some al in current_guardrails.allowlists
	allowlist_caller_matches(al)
	param_value := parsed_arguments[al.paramName]
	allowlist_value_approved(al, param_value)
}

# Allowlist check failed (applies but not approved)
allowlist_denied if {
	current_guardrails
	some al in current_guardrails.allowlists
	allowlist_caller_matches(al)
	param_value := parsed_arguments[al.paramName]
	not allowlist_value_approved(al, param_value)
}

# Caller scope matching
allowlist_caller_matches(al) if {
	al.callerScope == ""
}

allowlist_caller_matches(al) if {
	al.callerScope == user_id
}

# Value approval — exact match mode
allowlist_value_approved(al, value) if {
	al.matchMode == "exact"
	some v in al.allowedValues
	lower(v) == lower(value)
}

# Value approval — exact match mode, pattern check
allowlist_value_approved(al, value) if {
	al.matchMode == "exact"
	some p in al.allowedPatterns
	regex.match(p, value)
}

# Value approval — domain match mode (email domain extraction)
allowlist_value_approved(al, value) if {
	al.matchMode == "domain"
	at_idx := indexof(value, "@")
	at_idx >= 0
	domain := lower(substring(value, at_idx + 1, -1))
	some p in al.allowedPatterns
	lower(p) == domain
}

# Value approval — domain match mode, exact email
allowlist_value_approved(al, value) if {
	al.matchMode == "domain"
	some v in al.allowedValues
	lower(v) == lower(value)
}

# --- Tier 2: ToolAuthorization override (one-shot, calls NPL) ---

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

# --- Tier 3: Workflow (direct NPL call, rare path) ---

requires_workflow if {
	workflow_config[service_name][tool_name] == true
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

npl_workflow_decision := resp.body if {
	requires_workflow
	instance_id := workflow_instances[service_name]
	resp := http.send({
		"method": "POST",
		"url": sprintf("%s/npl/governance/Workflow/%s/evaluate", [data.npl_url, instance_id]),
		"headers": {
			"Authorization": sprintf("Bearer %s", [data.gateway_token]),
			"Content-Type": "application/json",
		},
		"body": {
			"toolName": tool_name,
			"callerIdentity": user_id,
			"callerClaims": caller_claims_flat,
			"arguments": json.marshal(parsed_arguments),
			"argumentsFingerprint": crypto.sha256(json.marshal(parsed_arguments)),
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

# --- Allow: Tool calls — unified three-tier evaluation ---

# Path 1: Simple tool — no guardrails, no workflow → access sufficient
allow if {
	not is_stream_setup
	is_tool_call; service_name; tool_name; user_id
	service_enabled; tool_in_catalog
	caller_authorized; caller_not_revoked
	not has_guardrails
	not requires_workflow
}

# Path 2: Guardrails pass, no allowlist binding, no workflow → allow
allow if {
	not is_stream_setup
	is_tool_call; service_name; tool_name; user_id
	service_enabled; tool_in_catalog
	caller_authorized; caller_not_revoked
	all_constraints_pass
	not allowlist_applies
	not requires_workflow
}

# Path 3: Guardrails pass, allowlist approved → allow
allow if {
	not is_stream_setup
	is_tool_call; service_name; tool_name; user_id
	service_enabled; tool_in_catalog
	caller_authorized; caller_not_revoked
	all_constraints_pass
	allowlist_approved
}

# Path 4: Guardrails pass, no allowlist denied, workflow allows → allow
allow if {
	not is_stream_setup
	is_tool_call; service_name; tool_name; user_id
	service_enabled; tool_in_catalog
	caller_authorized; caller_not_revoked
	all_constraints_pass
	not allowlist_denied
	npl_workflow_decision.decision == "allow"
}

# Path 5: Guardrails fail but ToolAuthorization override exists → allow
allow if {
	not is_stream_setup
	is_tool_call; service_name; tool_name; user_id
	service_enabled; tool_in_catalog
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
	caller_authorized
	caller_not_revoked
	not all_constraints_pass
	not has_tool_authorization_override
	constraint_violation_message
}

# Allowlist denied reason
reason := sprintf("Allowlist denied for %s.%s", [service_name, tool_name]) if {
	is_tool_call
	service_name
	user_id
	service_enabled
	tool_in_catalog
	caller_authorized
	caller_not_revoked
	all_constraints_pass
	allowlist_denied
}

# Workflow pending
reason := sprintf("Workflow pending: %s", [npl_workflow_decision.requestId]) if {
	is_tool_call
	service_name
	user_id
	service_enabled
	tool_in_catalog
	caller_authorized
	caller_not_revoked
	all_constraints_pass
	not allowlist_denied
	npl_workflow_decision
	npl_workflow_decision.decision == "pending"
}

# Workflow denied
reason := sprintf("Workflow denied: %s", [npl_workflow_decision.message]) if {
	is_tool_call
	service_name
	user_id
	service_enabled
	tool_in_catalog
	caller_authorized
	caller_not_revoked
	all_constraints_pass
	not allowlist_denied
	npl_workflow_decision
	npl_workflow_decision.decision == "deny"
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
response_headers["x-request-id"] := npl_workflow_decision.requestId if {
	npl_workflow_decision
	npl_workflow_decision.decision == "pending"
}

response_headers["retry-after"] := "30" if {
	npl_workflow_decision
	npl_workflow_decision.decision == "pending"
}

# Visible tools for tools/list response filtering (Lua reads this on response path)
response_headers["x-visible-tools"] := concat(",", sort(visible_tool_names)) if {
	jsonrpc_method == "tools/list"
	count(visible_tool_names) > 0
}
