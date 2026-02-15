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

default security_policy := {}

catalog := data.catalog

grants := data.grants

contextual_routing := data.contextual_routing

security_policy := data.security_policy

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

# --- Security Policy Evaluation ---
# Classifies tools/call requests into MCP annotations + verb + labels
# and evaluates security policy rules to determine allow/deny/require_approval.

# Check if security policies are configured
has_security_policies if {
	security_policy.policies
	count(security_policy.policies) > 0
}

# Lookup tool annotation from security policy bundle
sp_tool_entry := security_policy.tool_annotations[resolved_service_name][parsed_tool_name] if {
	security_policy.tool_annotations
	security_policy.tool_annotations[resolved_service_name]
	security_policy.tool_annotations[resolved_service_name][parsed_tool_name]
}

# --- Tool-name pattern fallback (when no explicit annotation exists) ---

verb_prefix_map := {
	"read_": "get",
	"get_": "get",
	"list_": "list",
	"search_": "get",
	"fetch_": "get",
	"create_": "create",
	"send_": "create",
	"add_": "create",
	"update_": "update",
	"edit_": "update",
	"modify_": "update",
	"delete_": "delete",
	"remove_": "delete",
	"revoke_": "delete",
}

fallback_verb := v if {
	some prefix, v in verb_prefix_map
	startswith(parsed_tool_name, prefix)
}

# --- Resolved annotations ---

# readOnlyHint: from security policy or fallback (get/list tools are read-only)
resolved_read_only_hint if {
	sp_tool_entry
	sp_tool_entry.annotations.readOnlyHint == true
}

resolved_read_only_hint if {
	not sp_tool_entry
	fallback_verb in {"get", "list"}
}

default resolved_read_only_hint := false

# destructiveHint: from security policy or fallback (delete tools are destructive)
resolved_destructive_hint if {
	sp_tool_entry
	sp_tool_entry.annotations.destructiveHint == true
}

resolved_destructive_hint if {
	not sp_tool_entry
	fallback_verb == "delete"
}

default resolved_destructive_hint := false

# idempotentHint: from security policy or fallback (get/list are idempotent)
resolved_idempotent_hint if {
	sp_tool_entry
	sp_tool_entry.annotations.idempotentHint == true
}

resolved_idempotent_hint if {
	not sp_tool_entry
	fallback_verb in {"get", "list"}
}

default resolved_idempotent_hint := false

# openWorldHint: from security policy only (no fallback)
resolved_open_world_hint if {
	sp_tool_entry
	sp_tool_entry.annotations.openWorldHint == true
}

default resolved_open_world_hint := false

# --- Resolved verb ---

resolved_verb := sp_tool_entry.verb if {
	sp_tool_entry
	sp_tool_entry.verb
}

resolved_verb := fallback_verb if {
	not sp_tool_entry
	fallback_verb
}

# --- Argument classifiers ---

# Classifier with "contains" condition
classifier_rule_matches(rule) if {
	rule.contains
	arguments := parsed_body.params.arguments
	field_value := arguments[rule.field]
	contains(field_value, rule.contains)
}

# Classifier with "not_contains" condition
classifier_rule_matches(rule) if {
	rule.not_contains
	arguments := parsed_body.params.arguments
	field_value := arguments[rule.field]
	not contains(field_value, rule.not_contains)
}

# Classifier with "present: true" condition
classifier_rule_matches(rule) if {
	rule.present == true
	arguments := parsed_body.params.arguments
	arguments[rule.field]
}

# Classifier with "present: false" condition
classifier_rule_matches(rule) if {
	rule.present == false
	arguments := parsed_body.params.arguments
	not arguments[rule.field]
}

# --- Resolved labels ---

# Static labels from security policy tool entry
resolved_labels contains label if {
	sp_tool_entry
	some label in sp_tool_entry.labels
}

# Dynamic labels from argument classifiers
resolved_labels contains label if {
	security_policy.classifiers
	security_policy.classifiers[resolved_service_name]
	some rule in security_policy.classifiers[resolved_service_name][parsed_tool_name]
	classifier_rule_matches(rule)
	some label in rule.set_labels
}

# --- Policy condition matching ---

condition_met("readOnlyHint", val) if {
	val == resolved_read_only_hint
}

condition_met("destructiveHint", val) if {
	val == resolved_destructive_hint
}

condition_met("idempotentHint", val) if {
	val == resolved_idempotent_hint
}

condition_met("openWorldHint", val) if {
	val == resolved_open_world_hint
}

condition_met("verb", val) if {
	val == resolved_verb
}

condition_met("labels", required_labels) if {
	every label in required_labels {
		label in resolved_labels
	}
}

# --- Policy rule matching ---

# Default match semantics: "all" (every condition must be met)
policy_matches(policy) if {
	not policy.match
	every key, val in policy.when {
		condition_met(key, val)
	}
}

policy_matches(policy) if {
	policy.match == "all"
	every key, val in policy.when {
		condition_met(key, val)
	}
}

# Alternative match semantics: "any" (at least one condition must be met)
policy_matches(policy) if {
	policy.match == "any"
	count(policy.when) > 0
	some key, val in policy.when
	condition_met(key, val)
}

# Collect all matching policies
matching_policies contains policy if {
	some policy in security_policy.policies
	policy_matches(policy)
}

# Winning priority: lowest number among matching policies
sp_winning_priority := min({p.priority | some p in matching_policies})

# Action from the highest-priority matching policy
sp_action := p.action if {
	some p in matching_policies
	p.priority == sp_winning_priority
}

# Fallback: no matching policy → default allow
sp_action := "allow" if {
	has_security_policies
	count(matching_policies) == 0
}

# Name of the matched rule (for debugging/tracing)
sp_matched_rule := p.name if {
	some p in matching_policies
	p.priority == sp_winning_priority
}

sp_matched_rule := "no matching policy (default allow)" if {
	has_security_policies
	count(matching_policies) == 0
}

# Approvers from the winning security policy rule (for require_approval)
sp_approvers := p.approvers if {
	some p in matching_policies
	p.priority == sp_winning_priority
	p.approvers
}

default sp_approvers := []

# --- NPL body enrichment ---
# Serialize resolved classification for contextual policy calls

sp_verb_for_npl := resolved_verb if {
	resolved_verb
}

default sp_verb_for_npl := ""

active_annotation_hints contains "readOnlyHint" if {
	resolved_read_only_hint
}

active_annotation_hints contains "destructiveHint" if {
	resolved_destructive_hint
}

active_annotation_hints contains "idempotentHint" if {
	resolved_idempotent_hint
}

active_annotation_hints contains "openWorldHint" if {
	resolved_open_world_hint
}

sp_annotations_text := concat(",", sort(active_annotation_hints))

sp_labels_text := concat(",", sort(resolved_labels))

argument_digest := crypto.sha256(json.marshal(parsed_body.params.arguments)) if {
	parsed_body.params
	parsed_body.params.arguments
}

default argument_digest := ""

# --- Security policy decision ---

# Allow if no security policies are configured (backward compatible)
security_policy_allows if {
	not has_security_policies
}

# Allow if the winning policy action is "allow"
security_policy_allows if {
	has_security_policies
	sp_action == "allow"
}

# Allow require_approval to proceed to Layer 2 if a contextual route exists
# (ApprovalPolicy will manage the approval state)
security_policy_allows if {
	has_security_policies
	sp_action == "require_approval"
	contextual_route
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

	# Security policy check
	security_policy_allows
}

# Rule 3a-sp: tools/call — security policy explicitly allows, skip contextual route
# When the security policy says "allow", the decision is final — no need for Layer 2.
# Contextual routes are only used for "require_approval" (ApprovalPolicy workflow).
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

	# Security policy explicitly allows — overrides contextual route
	has_security_policies
	sp_action == "allow"
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

	# Security policy check (before NPL call to avoid unnecessary network I/O)
	security_policy_allows

	# Contextual route exists — check NPL evaluate response
	contextual_route
	npl_evaluate_response == "allow"
}

# Capture NPL evaluate() response (OPA caches http.send with identical params — single HTTP call)
npl_evaluate_response := resp if {
	not is_stream_setup
	jsonrpc_method == "tools/call"
	jsonrpc_tool_name
	user_id
	resolved_service_name
	service_available_or_no_catalog(resolved_service_name)
	tool_enabled_or_no_catalog(resolved_service_name, parsed_tool_name)
	user_has_tool_access(user_id, resolved_service_name, parsed_tool_name)
	security_policy_allows
	contextual_route
	resp := http.send({
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
			"verb": sp_verb_for_npl,
			"labels": sp_labels_text,
			"annotations": sp_annotations_text,
			"argumentDigest": argument_digest,
			"approvers": sp_approvers,
		},
		"timeout": "5s",
	}).body
}

# Extract approval ID from "pending:<id>" response
pending_approval_id := substring(npl_evaluate_response, 8, -1) if {
	npl_evaluate_response
	startswith(npl_evaluate_response, "pending:")
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

reason := sprintf("tools/call: denied by security policy '%s'", [sp_matched_rule]) if {
	not is_stream_setup
	jsonrpc_method == "tools/call"
	jsonrpc_tool_name
	user_id
	resolved_service_name
	service_available_or_no_catalog(resolved_service_name)
	tool_enabled_or_no_catalog(resolved_service_name, parsed_tool_name)
	user_has_tool_access(user_id, resolved_service_name, parsed_tool_name)
	has_security_policies
	not security_policy_allows
	sp_action != "require_approval"
}

reason := sprintf("tools/call: require_approval by '%s' but no approval route configured", [sp_matched_rule]) if {
	not is_stream_setup
	jsonrpc_method == "tools/call"
	jsonrpc_tool_name
	user_id
	resolved_service_name
	service_available_or_no_catalog(resolved_service_name)
	tool_enabled_or_no_catalog(resolved_service_name, parsed_tool_name)
	user_has_tool_access(user_id, resolved_service_name, parsed_tool_name)
	has_security_policies
	sp_action == "require_approval"
	not contextual_route
}

reason := sprintf("tools/call: approval pending %s (policy '%s')", [pending_approval_id, sp_matched_rule]) if {
	not is_stream_setup
	jsonrpc_method == "tools/call"
	jsonrpc_tool_name
	user_id
	resolved_service_name
	service_available_or_no_catalog(resolved_service_name)
	tool_enabled_or_no_catalog(resolved_service_name, parsed_tool_name)
	user_has_tool_access(user_id, resolved_service_name, parsed_tool_name)
	has_security_policies
	sp_action == "require_approval"
	contextual_route
	pending_approval_id
}

reason := sprintf("tools/call: denied by approval policy (policy '%s')", [sp_matched_rule]) if {
	not is_stream_setup
	jsonrpc_method == "tools/call"
	jsonrpc_tool_name
	user_id
	resolved_service_name
	service_available_or_no_catalog(resolved_service_name)
	tool_enabled_or_no_catalog(resolved_service_name, parsed_tool_name)
	user_has_tool_access(user_id, resolved_service_name, parsed_tool_name)
	has_security_policies
	sp_action == "require_approval"
	contextual_route
	npl_evaluate_response
	npl_evaluate_response != "allow"
	not startswith(npl_evaluate_response, "pending:")
}

reason := "Authorized" if {
	allow
}

# --- Granted service names (for tools/list filtering) ---

# Compute the set of service names the user has grants for,
# filtered by catalog availability (suspended/disabled services excluded)
granted_service_names contains svc if {
	user_id
	some grant in grants[user_id]
	svc := grant.serviceName
	service_available_or_no_catalog(svc)
}

# --- Structured decision for OPA envoy plugin ---
# The envoy plugin only processes headers when the result is an object.
# Boolean results (allow = true/false) bypass header injection.

result := {"allowed": allow, "headers": headers, "response_headers_to_add": response_headers}

response_headers["x-authz-reason"] := reason if {
	reason
}

# --- Upstream request headers ---

# Pass user identity to upstream (for aggregator filtering and traceability)
headers["x-user-id"] := user_id if {
	user_id
}

# Pass granted services for tools/list filtering — aggregator only fans out to these
headers["x-granted-services"] := concat(",", sort(granted_service_names)) if {
	jsonrpc_method == "tools/list"
	user_id
}

# Pass resolved service name as upstream header (useful for aggregator debugging)
headers["x-mcp-service"] := resolved_service_name if {
	resolved_service_name
}

# Pass bundle revision for decision traceability
headers["x-bundle-revision"] := data._bundle_metadata.revision if {
	data._bundle_metadata.revision
}

# Security policy evaluation headers (for debugging/tracing)
response_headers["x-sp-action"] := sp_action if {
	has_security_policies
	sp_action
}

response_headers["x-sp-rule"] := sp_matched_rule if {
	has_security_policies
	sp_matched_rule
}

response_headers["x-sp-verb"] := resolved_verb if {
	has_security_policies
	resolved_verb
}

response_headers["x-sp-labels"] := concat(",", sort(resolved_labels)) if {
	has_security_policies
	count(resolved_labels) > 0
}

# Approval pending: return approval ID and retry hint
response_headers["x-approval-id"] := pending_approval_id if {
	pending_approval_id
}

response_headers["retry-after"] := "30" if {
	pending_approval_id
}

# Security policy version for traceability
response_headers["x-sp-version"] := data._bundle_metadata.security_policy_version if {
	data._bundle_metadata.security_policy_version
}
