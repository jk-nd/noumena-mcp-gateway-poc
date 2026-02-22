#!/usr/bin/env bash
# seed.sh — Populate the MCP Gateway with realistic demo data.
#
# Idempotent: safe to run multiple times (ignores duplicate errors).
#
# Usage:
#   bash scripts/seed.sh
#
# Environment variables (all have sensible defaults):
#   NPL_URL            NPL Engine base URL      (default: http://localhost:12000)
#   KEYCLOAK_URL       Keycloak base URL         (default: http://localhost:11000)
#   ADMIN_PASSWORD     Admin user password        (default: Welcome123)
set -euo pipefail

# ---------------------------------------------------------------------------
# Configuration
# ---------------------------------------------------------------------------
NPL_URL="${NPL_URL:-http://localhost:12000}"
KEYCLOAK_URL="${KEYCLOAK_URL:-http://localhost:11000}"
PASSWORD="${ADMIN_PASSWORD:-Welcome123}"
REALM="mcpgateway"
CLIENT_ID="mcpgateway"

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------
bold()  { printf '\033[1m%s\033[0m\n' "$*"; }
green() { printf '\033[32m%s\033[0m\n' "$*"; }
yellow(){ printf '\033[33m%s\033[0m\n' "$*"; }
red()   { printf '\033[31m%s\033[0m\n' "$*"; }

# Acquire a Keycloak access token for the given user.
get_token() {
  local user="$1" pass="$2"
  curl -sf -X POST \
    "${KEYCLOAK_URL}/realms/${REALM}/protocol/openid-connect/token" \
    -H "Content-Type: application/x-www-form-urlencoded" \
    -d "grant_type=password&client_id=${CLIENT_ID}&username=${user}&password=${pass}" \
    | jq -r '.access_token'
}

# Call an NPL action. Ignores errors (duplicates return 500/409).
# Usage: npl_call <path> <json-body>
npl_call() {
  local path="$1" body="$2"
  local http_code
  http_code=$(curl -s -o /dev/null -w '%{http_code}' -X POST \
    "${NPL_URL}${path}" \
    -H "Authorization: Bearer ${ADMIN_TOKEN}" \
    -H "Content-Type: application/json" \
    -d "${body}")

  if [[ "$http_code" =~ ^2 ]]; then
    return 0
  else
    # Likely a duplicate / already-exists — not fatal
    return 0
  fi
}

# Call an NPL action and capture the JSON response.
npl_call_response() {
  local path="$1" body="$2"
  curl -sf -X POST \
    "${NPL_URL}${path}" \
    -H "Authorization: Bearer ${ADMIN_TOKEN}" \
    -H "Content-Type: application/json" \
    -d "${body}" 2>/dev/null || echo "{}"
}

# ---------------------------------------------------------------------------
# 1. Admin token
# ---------------------------------------------------------------------------
bold "=== MCP Gateway Seed Script ==="
echo ""
bold "1. Acquiring admin token..."
ADMIN_TOKEN=$(get_token "admin" "${PASSWORD}")
if [[ -z "$ADMIN_TOKEN" || "$ADMIN_TOKEN" == "null" ]]; then
  red "ERROR: Failed to acquire admin token. Is Keycloak running?"
  exit 1
fi
green "   Got admin token"

# ---------------------------------------------------------------------------
# 2. Find or create GatewayStore singleton
# ---------------------------------------------------------------------------
bold "2. Finding GatewayStore..."
STORE_RESPONSE=$(curl -sf \
  "${NPL_URL}/npl/store/GatewayStore/" \
  -H "Authorization: Bearer ${ADMIN_TOKEN}" 2>/dev/null || echo '{"items":[]}')

STORE_ID=$(echo "$STORE_RESPONSE" | jq -r '.items[0]["@id"] // empty')

if [[ -z "$STORE_ID" ]]; then
  echo "   Creating GatewayStore singleton..."
  CREATE_RESPONSE=$(npl_call_response "/npl/store/GatewayStore/" '{"@parties":{}}')
  STORE_ID=$(echo "$CREATE_RESPONSE" | jq -r '.["@id"] // empty')
  if [[ -z "$STORE_ID" ]]; then
    red "ERROR: Failed to create GatewayStore"
    exit 1
  fi
  green "   Created GatewayStore: ${STORE_ID}"
else
  green "   Found GatewayStore: ${STORE_ID}"
fi

STORE="/npl/store/GatewayStore/${STORE_ID}"

# ---------------------------------------------------------------------------
# 3. Register and enable services
# ---------------------------------------------------------------------------
bold "3. Registering services..."

for svc in duckduckgo mock-calendar; do
  npl_call "${STORE}/registerService" "{\"serviceName\":\"${svc}\"}"
  npl_call "${STORE}/enableService"   "{\"serviceName\":\"${svc}\"}"
  green "   ${svc} — registered + enabled"
done

# ---------------------------------------------------------------------------
# 4. Register tools with tags
# ---------------------------------------------------------------------------
bold "4. Registering tools..."

register_tool() {
  local svc="$1" tool="$2" tag="$3"
  npl_call "${STORE}/registerTool" \
    "{\"serviceName\":\"${svc}\",\"toolName\":\"${tool}\",\"tag\":\"${tag}\"}"
  echo "   ${svc}.${tool} [${tag}]"
}

register_tool "duckduckgo"    "search"       "acl"
register_tool "mock-calendar" "list_events"  "acl"
register_tool "mock-calendar" "read_inbox"   "acl"
register_tool "mock-calendar" "create_event" "logic"
register_tool "mock-calendar" "send_email"   "logic"

green "   Tools registered"

# ---------------------------------------------------------------------------
# 5. Access rules
# ---------------------------------------------------------------------------
bold "5. Adding access rules..."

add_rule() {
  local id="$1" matchType="$2" claims="$3" identity="$4" services="$5" tools="$6"
  npl_call "${STORE}/addAccessRule" \
    "{\"id\":\"${id}\",\"matchType\":\"${matchType}\",\"matchClaims\":${claims},\"matchIdentity\":\"${identity}\",\"allowServices\":${services},\"allowTools\":${tools}}"
  echo "   ${id}"
}

add_rule "acme-all-search" \
  "claims" '{"organization":"acme"}' "" \
  '["duckduckgo"]' '["*"]'

add_rule "engineering-calendar" \
  "claims" '{"department":"engineering"}' "" \
  '["mock-calendar"]' '["*"]'

add_rule "sales-search-only" \
  "claims" '{"department":"sales"}' "" \
  '["duckduckgo"]' '["*"]'

add_rule "jarvis-calendar" \
  "identity" '{}' "jarvis@acme.com" \
  '["mock-calendar"]' '["*"]'

add_rule "compliance-all" \
  "claims" '{"role":"approver"}' "" \
  '["*"]' '["*"]'

green "   Access rules added"

# ---------------------------------------------------------------------------
# 6. ServiceGovernance instances
# ---------------------------------------------------------------------------
bold "6. Creating ServiceGovernance instances..."

# Helper: find existing governance instance by service name, or create a new one.
find_or_create_governance() {
  local svc_name="$1"

  # List existing instances
  local list_response
  list_response=$(curl -sf \
    "${NPL_URL}/npl/governance/ServiceGovernance/" \
    -H "Authorization: Bearer ${ADMIN_TOKEN}" 2>/dev/null || echo '{"items":[]}')

  # Look for one that matches the service name
  local existing_id
  existing_id=$(echo "$list_response" | jq -r \
    --arg name "$svc_name" \
    '.items[] | select(.serviceName == $name) | .["@id"] // empty' 2>/dev/null | head -1)

  if [[ -n "$existing_id" ]]; then
    echo "$existing_id"
    return
  fi

  # Create new instance
  local create_response
  create_response=$(npl_call_response "/npl/governance/ServiceGovernance/" '{"@parties":{}}')
  local new_id
  new_id=$(echo "$create_response" | jq -r '.["@id"] // empty')

  if [[ -n "$new_id" ]]; then
    # Initialize with service name
    npl_call "/npl/governance/ServiceGovernance/${new_id}/setup" "{\"name\":\"${svc_name}\"}"
  fi

  echo "$new_id"
}

MOCK_CAL_GOV=$(find_or_create_governance "mock-calendar")
if [[ -n "$MOCK_CAL_GOV" ]]; then
  green "   mock-calendar governance: ${MOCK_CAL_GOV}"
else
  yellow "   WARNING: Could not create mock-calendar governance instance"
fi

DDG_GOV=$(find_or_create_governance "duckduckgo")
if [[ -n "$DDG_GOV" ]]; then
  green "   duckduckgo governance: ${DDG_GOV}"
else
  yellow "   WARNING: Could not create duckduckgo governance instance"
fi

# ---------------------------------------------------------------------------
# 7. Register tools in governance + add constraints
# ---------------------------------------------------------------------------
bold "7. Configuring governance (two-phase intent confirmation)..."

#
# Two-phase workflow:
#
#   Phase 1 (READ — open, no gate):
#     Agent freely reads calendar, inbox, searches — builds context.
#
#   Phase 2 (WRITE — gated, requires human confirmation):
#     Agent prepares the action (create_event, send_email) but CANNOT execute.
#     The request goes to "pending" with full details visible to the approver.
#     A human (the requesting user, their manager, or compliance) reviews
#     the exact action and approves or denies.
#     Only then does the agent retry and execute.
#
# Constraints act as guardrails that catch policy violations even if
# someone rubber-stamps the approval.
#

GOV_BASE="/npl/governance/ServiceGovernance"

if [[ -n "$MOCK_CAL_GOV" ]]; then
  # Register tools in governance
  for tool_tag in "list_events:acl" "read_inbox:acl" "create_event:logic" "send_email:logic"; do
    tool="${tool_tag%%:*}"
    tag="${tool_tag##*:}"
    npl_call "${GOV_BASE}/${MOCK_CAL_GOV}/registerTool" \
      "{\"toolName\":\"${tool}\",\"tag\":\"${tag}\"}"
  done

  # ── create_event: workflow-gated for external attendees ──
  # No post-hoc approval — internal invites auto-allow, external require workflow authorization
  npl_call "${GOV_BASE}/${MOCK_CAL_GOV}/setRequiresApproval" \
    "{\"toolName\":\"create_event\",\"required\":false}"

  # Constraint: attendees must be internal — external invites require workflow authorization
  npl_call "${GOV_BASE}/${MOCK_CAL_GOV}/addConstraint" \
    "{\"toolName\":\"create_event\",\"paramName\":\"attendees\",\"operator\":\"contains\",\"values\":[\"@acme.com\"],\"description\":\"Attendees must be Acme employees — external invites require workflow authorization\"}"

  # Guardrail: only standard meeting durations (avoid 7-hour blocks)
  npl_call "${GOV_BASE}/${MOCK_CAL_GOV}/addConstraint" \
    "{\"toolName\":\"create_event\",\"paramName\":\"duration\",\"operator\":\"in\",\"values\":[\"15\",\"30\",\"60\",\"90\",\"120\"],\"description\":\"Meeting duration must be 15, 30, 60, 90, or 120 minutes\"}"

  # Guardrail: block sensitive meeting types that need manual scheduling
  npl_call "${GOV_BASE}/${MOCK_CAL_GOV}/addConstraint" \
    "{\"toolName\":\"create_event\",\"paramName\":\"title\",\"operator\":\"not_contains\",\"values\":[\"Board Meeting\",\"M&A\",\"Termination\",\"Disciplinary\"],\"description\":\"Sensitive meetings (board, M&A, HR) must be scheduled manually\"}"

  # ── send_email: human must confirm before agent sends ──
  # Gate: approval required — agent drafts, human confirms before sending
  npl_call "${GOV_BASE}/${MOCK_CAL_GOV}/setRequiresApproval" \
    "{\"toolName\":\"send_email\",\"required\":true}"

  # Guardrail: internal recipients only
  npl_call "${GOV_BASE}/${MOCK_CAL_GOV}/addConstraint" \
    "{\"toolName\":\"send_email\",\"paramName\":\"to\",\"operator\":\"contains\",\"values\":[\"@acme.com\"],\"description\":\"Recipients must be Acme employees — no external emails\"}"

  # Guardrail: block sensitive content patterns
  npl_call "${GOV_BASE}/${MOCK_CAL_GOV}/addConstraint" \
    "{\"toolName\":\"send_email\",\"paramName\":\"subject\",\"operator\":\"not_contains\",\"values\":[\"CONFIDENTIAL\",\"NDA\",\"salary\",\"offer letter\"],\"description\":\"Sensitive topics (NDA, salary, offers) must not be sent by AI agents\"}"

  # Guardrail: no SSN patterns in email body
  npl_call "${GOV_BASE}/${MOCK_CAL_GOV}/addConstraint" \
    "{\"toolName\":\"send_email\",\"paramName\":\"body\",\"operator\":\"regex\",\"values\":[\"^((?!\\\\d{3}-\\\\d{2}-\\\\d{4}).)*$\"],\"description\":\"Email body must not contain SSN patterns (XXX-XX-XXXX)\"}"

  npl_call "${GOV_BASE}/${MOCK_CAL_GOV}/setGovernanceDescription" \
    "{\"desc\":\"Calendar governance with two models: create_event uses workflow-gated authorization (internal auto-allow, external requires ToolAuthorization), send_email uses post-hoc approval (human confirms every send). Constraints enforce internal-only recipients, standard durations, and block sensitive content.\"}"

  green "   mock-calendar governance configured (gated: create_event, send_email)"
fi

if [[ -n "$DDG_GOV" ]]; then
  npl_call "${GOV_BASE}/${DDG_GOV}/registerTool" \
    "{\"toolName\":\"search\",\"tag\":\"acl\"}"

  # Search is open (Phase 1 read action) — no gate, just guardrails
  npl_call "${GOV_BASE}/${DDG_GOV}/setRequiresApproval" \
    "{\"toolName\":\"search\",\"required\":false}"

  # Guardrail: block competitive intelligence queries
  npl_call "${GOV_BASE}/${DDG_GOV}/addConstraint" \
    "{\"toolName\":\"search\",\"paramName\":\"query\",\"operator\":\"not_contains\",\"values\":[\"competitor pricing\",\"salary benchmark\",\"SEC filing\",\"insider\"],\"description\":\"AI agents must not perform competitive intelligence or insider searches\"}"

  # Guardrail: reasonable query length
  npl_call "${GOV_BASE}/${DDG_GOV}/addConstraint" \
    "{\"toolName\":\"search\",\"paramName\":\"query\",\"operator\":\"max_length\",\"values\":[\"500\"],\"description\":\"Search query max 500 characters\"}"

  npl_call "${GOV_BASE}/${DDG_GOV}/setGovernanceDescription" \
    "{\"desc\":\"Search is a read-only action (Phase 1) — open access with guardrails against competitive intelligence queries.\"}"

  green "   duckduckgo governance configured (open: search)"
fi

# ---------------------------------------------------------------------------
# 8. Demo ToolAuthorization (workflow-gated override)
# ---------------------------------------------------------------------------
bold "8. Creating demo ToolAuthorization..."

# Alice authorizes jarvis to invite an external party (one-shot workflow override)
ALICE_TOKEN=$(get_token "alice" "${PASSWORD}")
if [[ -n "$ALICE_TOKEN" && "$ALICE_TOKEN" != "null" ]]; then
  AUTH_RESPONSE=$(curl -sf -X POST "${NPL_URL}/npl/governance/ToolAuthorization/" \
    -H "Authorization: Bearer $ALICE_TOKEN" \
    -H "Content-Type: application/json" \
    -d '{"@parties":{}}' 2>/dev/null || echo '{}')
  AUTH_ID=$(echo "$AUTH_RESPONSE" | jq -r '.["@id"] // empty')

  if [[ -n "$AUTH_ID" ]]; then
    curl -sf -X POST "${NPL_URL}/npl/governance/ToolAuthorization/${AUTH_ID}/authorize" \
      -H "Authorization: Bearer $ALICE_TOKEN" \
      -H "Content-Type: application/json" \
      -d '{
        "svc": "mock-calendar",
        "tool": "create_event",
        "agent": "jarvis@acme.com",
        "desc": "Schedule Q2 planning with client@partner.com"
      }' > /dev/null 2>&1
    green "   ToolAuthorization ${AUTH_ID}: alice -> jarvis@acme.com may create_event (external)"
  else
    yellow "   WARNING: Could not create ToolAuthorization instance"
  fi
else
  yellow "   WARNING: Could not get alice token — skipping ToolAuthorization demo"
fi

# ---------------------------------------------------------------------------
# 9. Summary
# ---------------------------------------------------------------------------
echo ""
bold "=== Seed Complete ==="
echo ""
echo "Services:"
echo "  - duckduckgo    (search [acl])"
echo "  - mock-calendar (list_events [acl], read_inbox [acl], create_event [logic], send_email [logic])"
echo ""
echo "Access rules:"
echo "  - acme-all-search       org=acme            -> duckduckgo.*"
echo "  - engineering-calendar   dept=engineering     -> mock-calendar.*"
echo "  - sales-search-only      dept=sales           -> duckduckgo.*"
echo "  - jarvis-calendar        jarvis@acme.com      -> mock-calendar.*"
echo "  - compliance-all         role=approver         -> *.*"
echo ""
echo "Governance models:"
echo "  READ (acl):    list_events, read_inbox, search — auto-allow"
echo "  WRITE (logic): two models coexist:"
echo ""
if [[ -n "$MOCK_CAL_GOV" ]]; then
  echo "  mock-calendar: ${MOCK_CAL_GOV}"
  echo "    create_event [WORKFLOW-GATED] internal=auto-allow, external=requires ToolAuthorization"
  echo "    send_email   [APPROVAL-GATED] all sends require human approval"
fi
if [[ -n "$DDG_GOV" ]]; then
  echo "  duckduckgo:    ${DDG_GOV}"
  echo "    search       [OPEN] auto-allow with guardrails"
fi
echo ""
echo "Workflow authorization (one-shot):"
echo "  1. User creates ToolAuthorization: ./scripts/approve.sh authorize <svc> <tool> <agent> \"desc\""
echo "  2. Agent calls tool -> constraint fails -> evaluator finds authorization -> allow"
echo "  3. Authorization consumed (one-shot, gate closes)"
echo ""
green "Done! Dashboard: http://localhost:13000"
