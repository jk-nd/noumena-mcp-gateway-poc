#!/usr/bin/env bash
# seed.sh — Populate the MCP Gateway with realistic demo data.
#
# Three-tier governance model:
#   Tier 1 — Access: catalog + access rules (fail-closed)
#   Tier 2 — Guardrails: constraints + allowlists (in-memory evaluation)
#   Tier 3 — Workflow: approval state machine (NPL call)
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
bold "=== MCP Gateway Seed Script (Three-Tier Governance) ==="
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
# 4. Register tools (no tags — governance is derived from configuration)
# ---------------------------------------------------------------------------
bold "4. Registering tools..."

register_tool() {
  local svc="$1" tool="$2"
  npl_call "${STORE}/registerTool" \
    "{\"serviceName\":\"${svc}\",\"toolName\":\"${tool}\"}"
  echo "   ${svc}__${tool}"
}

register_tool "duckduckgo"    "search"
register_tool "mock-calendar" "list_events"
register_tool "mock-calendar" "read_inbox"
register_tool "mock-calendar" "create_event"
register_tool "mock-calendar" "send_email"

green "   Tools registered"

# ---------------------------------------------------------------------------
# 5. Access rules (Tier 1)
# ---------------------------------------------------------------------------
bold "5. Adding access rules (Tier 1)..."

add_rule() {
  local id="$1" matchType="$2" claims="$3" identity="$4" services="$5" tools="$6"
  npl_call "${STORE}/addAccessRule" \
    "{\"id\":\"${id}\",\"matchType\":\"${matchType}\",\"matchClaims\":${claims},\"matchIdentity\":\"${identity}\",\"allowServices\":${services},\"allowTools\":${tools}}"
  echo "   ${id}"
}

# All Acme employees can use all services
add_rule "acme-all" \
  "claims" '{"organization":"acme"}' "" \
  '["*"]' '["*"]'

# Jarvis (AI agent) can use mock-calendar and duckduckgo
add_rule "jarvis-agent" \
  "identity" '{}' "jarvis@acme.com" \
  '["mock-calendar","duckduckgo"]' '["*"]'

green "   Access rules added"

# ---------------------------------------------------------------------------
# 6. Guardrails instances (Tier 2)
# ---------------------------------------------------------------------------
bold "6. Creating Guardrails instances (Tier 2)..."

find_or_create_guardrails() {
  local svc_name="$1"

  local list_response
  list_response=$(curl -sf \
    "${NPL_URL}/npl/governance/Guardrails/" \
    -H "Authorization: Bearer ${ADMIN_TOKEN}" 2>/dev/null || echo '{"items":[]}')

  local existing_id
  existing_id=$(echo "$list_response" | jq -r \
    --arg name "$svc_name" \
    '.items[] | select(.serviceName == $name) | .["@id"] // empty' 2>/dev/null | head -1)

  if [[ -n "$existing_id" ]]; then
    echo "$existing_id"
    return
  fi

  local create_response
  create_response=$(npl_call_response "/npl/governance/Guardrails/" '{"@parties":{}}')
  local new_id
  new_id=$(echo "$create_response" | jq -r '.["@id"] // empty')

  if [[ -n "$new_id" ]]; then
    npl_call "/npl/governance/Guardrails/${new_id}/setup" "{\"name\":\"${svc_name}\"}"
  fi

  echo "$new_id"
}

MOCK_CAL_GR=$(find_or_create_guardrails "mock-calendar")
if [[ -n "$MOCK_CAL_GR" ]]; then
  green "   mock-calendar guardrails: ${MOCK_CAL_GR}"
else
  yellow "   WARNING: Could not create mock-calendar guardrails instance"
fi

# ---------------------------------------------------------------------------
# 7. Workflow instances (Tier 3)
# ---------------------------------------------------------------------------
bold "7. Creating Workflow instances (Tier 3)..."

find_or_create_workflow() {
  local svc_name="$1"

  local list_response
  list_response=$(curl -sf \
    "${NPL_URL}/npl/governance/Workflow/" \
    -H "Authorization: Bearer ${ADMIN_TOKEN}" 2>/dev/null || echo '{"items":[]}')

  local existing_id
  existing_id=$(echo "$list_response" | jq -r \
    --arg name "$svc_name" \
    '.items[] | select(.serviceName == $name) | .["@id"] // empty' 2>/dev/null | head -1)

  if [[ -n "$existing_id" ]]; then
    echo "$existing_id"
    return
  fi

  local create_response
  create_response=$(npl_call_response "/npl/governance/Workflow/" '{"@parties":{}}')
  local new_id
  new_id=$(echo "$create_response" | jq -r '.["@id"] // empty')

  if [[ -n "$new_id" ]]; then
    npl_call "/npl/governance/Workflow/${new_id}/setup" "{\"name\":\"${svc_name}\"}"
  fi

  echo "$new_id"
}

# Only mock-calendar needs a workflow (for approval-gated tools)
MOCK_CAL_WF=$(find_or_create_workflow "mock-calendar")
if [[ -n "$MOCK_CAL_WF" ]]; then
  green "   mock-calendar workflow: ${MOCK_CAL_WF}"
else
  yellow "   WARNING: Could not create mock-calendar workflow instance"
fi

# ---------------------------------------------------------------------------
# 8. Configure guardrails + allowlists + workflow
# ---------------------------------------------------------------------------
bold "8. Configuring governance..."

GR_BASE="/npl/governance/Guardrails"
WF_BASE="/npl/governance/Workflow"

if [[ -n "$MOCK_CAL_GR" ]]; then
  # ── create_event: constraints ──
  npl_call "${GR_BASE}/${MOCK_CAL_GR}/addConstraint" \
    "{\"toolName\":\"create_event\",\"paramName\":\"attendees\",\"operator\":\"contains\",\"values\":[\"@acme.com\"],\"description\":\"Attendees must be Acme employees\"}"

  npl_call "${GR_BASE}/${MOCK_CAL_GR}/addConstraint" \
    "{\"toolName\":\"create_event\",\"paramName\":\"duration\",\"operator\":\"in\",\"values\":[\"15\",\"30\",\"60\",\"90\",\"120\"],\"description\":\"Meeting duration must be 15, 30, 60, 90, or 120 minutes\"}"

  # ── send_email: allowlist (replaces ApprovedRecipients) ──
  npl_call "${GR_BASE}/${MOCK_CAL_GR}/addAllowlist" \
    "{\"toolName\":\"send_email\",\"paramName\":\"to\",\"matchMode\":\"domain\",\"callerScope\":\"jarvis@acme.com\",\"description\":\"Approved email recipients for jarvis\"}"

  # Add approved domain pattern (acme.com)
  npl_call "${GR_BASE}/${MOCK_CAL_GR}/addAllowedPattern" \
    "{\"toolName\":\"send_email\",\"paramName\":\"to\",\"pattern\":\"acme.com\"}"

  # Add approved external recipients
  npl_call "${GR_BASE}/${MOCK_CAL_GR}/addAllowedValue" \
    "{\"toolName\":\"send_email\",\"paramName\":\"to\",\"value\":\"dave@external-vendor.com\"}"

  npl_call "${GR_BASE}/${MOCK_CAL_GR}/addAllowedValue" \
    "{\"toolName\":\"send_email\",\"paramName\":\"to\",\"value\":\"partner@consulting-firm.com\"}"

  green "   mock-calendar guardrails configured (constraints + allowlist)"
fi

if [[ -n "$MOCK_CAL_WF" ]]; then
  npl_call "${WF_BASE}/${MOCK_CAL_WF}/setWorkflowDescription" \
    "{\"desc\":\"Calendar governance: create_event auto-allows with constraints. send_email governed by allowlist for jarvis.\"}"

  green "   mock-calendar workflow configured"
fi

# ---------------------------------------------------------------------------
# 9. Demo ToolAuthorization (workflow-gated override)
# ---------------------------------------------------------------------------
bold "9. Creating demo ToolAuthorization..."

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
# 10. Summary
# ---------------------------------------------------------------------------
echo ""
bold "=== Seed Complete (Three-Tier Governance) ==="
echo ""
echo "Services:"
echo "  - duckduckgo    (search)"
echo "  - mock-calendar (list_events, read_inbox, create_event, send_email)"
echo ""
echo "Tier 1 — Access rules:"
echo "  - acme-all        org=acme             -> *__*  (all Acme employees, all services)"
echo "  - jarvis-agent    jarvis@acme.com      -> mock-calendar__*, duckduckgo__*"
echo ""
echo "Tier 2 — Guardrails:"
if [[ -n "$MOCK_CAL_GR" ]]; then
  echo "  mock-calendar Guardrails: ${MOCK_CAL_GR}"
  echo "    create_event [CONSTRAINED] internal attendees, standard durations"
  echo "    send_email   [ALLOWLIST]   jarvis: @acme.com + 2 pre-approved externals"
  echo "    list_events, read_inbox    (no guardrails — access sufficient)"
fi
echo "  duckduckgo: no guardrails (access sufficient)"
echo ""
echo "Tier 3 — Workflow:"
if [[ -n "$MOCK_CAL_WF" ]]; then
  echo "  mock-calendar Workflow: ${MOCK_CAL_WF} (no tools require workflow currently)"
fi
echo ""
green "Done! Dashboard: http://localhost:8888"
