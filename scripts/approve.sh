#!/usr/bin/env bash
# approve.sh — CLI helper for approvers to manage pending tool requests.
#
# Usage:
#   ./scripts/approve.sh list                       # List pending approvals
#   ./scripts/approve.sh approve <request-id>       # Approve a request
#   ./scripts/approve.sh deny <request-id> "reason" # Deny a request
#
# Environment variables:
#   APPROVER_USER      Username (default: carol)
#   APPROVER_PASSWORD  Password (default: Welcome123)
#   NPL_URL            NPL Engine URL (default: http://localhost:12000)
#   KEYCLOAK_URL       Keycloak URL (default: http://localhost:11000)
set -euo pipefail

NPL_URL="${NPL_URL:-http://localhost:12000}"
KEYCLOAK_URL="${KEYCLOAK_URL:-http://localhost:11000}"
APPROVER_USER="${APPROVER_USER:-carol}"
APPROVER_PASSWORD="${APPROVER_PASSWORD:-Welcome123}"
REALM="mcpgateway"
CLIENT_ID="mcpgateway"

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------
bold()  { printf '\033[1m%s\033[0m\n' "$*"; }
green() { printf '\033[32m%s\033[0m\n' "$*"; }
red()   { printf '\033[31m%s\033[0m\n' "$*"; }

usage() {
  echo "Usage:"
  echo "  $0 list                                              List pending approvals"
  echo "  $0 approve <request-id>                              Approve a request"
  echo "  $0 deny <request-id> \"reason\"                        Deny a request"
  echo "  $0 authorize <service> <tool> <agent> \"description\"  Create workflow authorization"
  echo ""
  echo "Environment: APPROVER_USER=${APPROVER_USER}, NPL_URL=${NPL_URL}"
  exit 1
}

get_token() {
  curl -sf -X POST \
    "${KEYCLOAK_URL}/realms/${REALM}/protocol/openid-connect/token" \
    -H "Content-Type: application/x-www-form-urlencoded" \
    -d "grant_type=password&client_id=${CLIENT_ID}&username=${APPROVER_USER}&password=${APPROVER_PASSWORD}" \
    | jq -r '.access_token'
}

# Get all ServiceGovernance instances as JSON
get_governance_list() {
  curl -sf \
    "${NPL_URL}/npl/governance/ServiceGovernance/" \
    -H "Authorization: Bearer ${TOKEN}" \
    | jq -r '.items'
}

# Extract just the IDs
get_governance_ids() {
  echo "$GOV_LIST" | jq -r '.[]."@id"'
}

# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------
[[ $# -lt 1 ]] && usage
ACTION="$1"

bold "Authenticating as ${APPROVER_USER}..."
TOKEN=$(get_token)
if [[ -z "$TOKEN" || "$TOKEN" == "null" ]]; then
  red "ERROR: Failed to authenticate. Check credentials."
  exit 1
fi

GOV_BASE="/npl/governance/ServiceGovernance"
GOV_LIST=$(get_governance_list)

case "$ACTION" in
  list)
    bold "Pending approvals:"
    echo ""
    for gid in $(get_governance_ids); do
      RESPONSE=$(curl -sf -X POST \
        "${NPL_URL}${GOV_BASE}/${gid}/getPendingRequests" \
        -H "Authorization: Bearer ${TOKEN}" \
        -H "Content-Type: application/json" \
        -d '{}' 2>/dev/null || echo "[]")

      COUNT=$(echo "$RESPONSE" | jq 'if type == "array" then length else 0 end')
      if [[ "$COUNT" -gt 0 ]]; then
        # Get service name from the list response
        SVC_NAME=$(echo "$GOV_LIST" | jq -r --arg id "$gid" '.[] | select(.["@id"] == $id) | .serviceName // "unknown"')
        bold "  Service: ${SVC_NAME} (${gid})"
        echo "$RESPONSE" | jq -r '.[] | "    \(.requestId)  \(.toolName)  caller=\(.callerIdentity)  status=\(.status)"'
        echo ""
      fi
    done
    ;;

  approve)
    [[ $# -lt 2 ]] && { red "ERROR: Missing request ID"; usage; }
    REQUEST_ID="$2"
    bold "Approving ${REQUEST_ID}..."

    for gid in $(get_governance_ids); do
      HTTP_CODE=$(curl -s -o /dev/null -w '%{http_code}' -X POST \
        "${NPL_URL}${GOV_BASE}/${gid}/approve" \
        -H "Authorization: Bearer ${TOKEN}" \
        -H "Content-Type: application/json" \
        -d "{\"requestId\":\"${REQUEST_ID}\"}")

      if [[ "$HTTP_CODE" =~ ^2 ]]; then
        green "Approved ${REQUEST_ID}"
        exit 0
      fi
    done
    red "ERROR: Request ${REQUEST_ID} not found in any governance instance"
    exit 1
    ;;

  deny)
    [[ $# -lt 3 ]] && { red "ERROR: Missing request ID or reason"; usage; }
    REQUEST_ID="$2"
    REASON="$3"
    bold "Denying ${REQUEST_ID}..."

    for gid in $(get_governance_ids); do
      HTTP_CODE=$(curl -s -o /dev/null -w '%{http_code}' -X POST \
        "${NPL_URL}${GOV_BASE}/${gid}/deny" \
        -H "Authorization: Bearer ${TOKEN}" \
        -H "Content-Type: application/json" \
        -d "{\"requestId\":\"${REQUEST_ID}\",\"reason\":\"${REASON}\"}")

      if [[ "$HTTP_CODE" =~ ^2 ]]; then
        green "Denied ${REQUEST_ID}: ${REASON}"
        exit 0
      fi
    done
    red "ERROR: Request ${REQUEST_ID} not found in any governance instance"
    exit 1
    ;;

  authorize)
    [[ $# -lt 5 ]] && { red "ERROR: Usage: $0 authorize <service> <tool> <agent> \"description\""; usage; }
    AUTH_SVC="$2"
    AUTH_TOOL="$3"
    AUTH_AGENT="$4"
    AUTH_DESC="$5"
    bold "Creating workflow authorization: ${AUTH_SVC}.${AUTH_TOOL} for ${AUTH_AGENT}..."

    # Create ToolAuthorization instance
    AUTH_RESPONSE=$(curl -sf -X POST \
      "${NPL_URL}/npl/governance/ToolAuthorization/" \
      -H "Authorization: Bearer ${TOKEN}" \
      -H "Content-Type: application/json" \
      -d '{"@parties":{}}' 2>/dev/null || echo '{}')
    AUTH_ID=$(echo "$AUTH_RESPONSE" | jq -r '.["@id"] // empty')

    if [[ -z "$AUTH_ID" ]]; then
      red "ERROR: Failed to create ToolAuthorization instance"
      exit 1
    fi

    # Authorize with service, tool, agent, and description
    HTTP_CODE=$(curl -s -o /dev/null -w '%{http_code}' -X POST \
      "${NPL_URL}/npl/governance/ToolAuthorization/${AUTH_ID}/authorize" \
      -H "Authorization: Bearer ${TOKEN}" \
      -H "Content-Type: application/json" \
      -d "{\"svc\":\"${AUTH_SVC}\",\"tool\":\"${AUTH_TOOL}\",\"agent\":\"${AUTH_AGENT}\",\"desc\":\"${AUTH_DESC}\"}")

    if [[ "$HTTP_CODE" =~ ^2 ]]; then
      green "Authorized: ${AUTH_ID}"
      echo "  Service: ${AUTH_SVC}"
      echo "  Tool:    ${AUTH_TOOL}"
      echo "  Agent:   ${AUTH_AGENT}"
      echo "  Scope:   ${AUTH_DESC}"
      echo ""
      echo "This is a one-shot authorization — it will be consumed after the agent's next matching tool call."
    else
      red "ERROR: Failed to authorize (HTTP ${HTTP_CODE})"
      exit 1
    fi
    ;;

  *)
    red "Unknown action: ${ACTION}"
    usage
    ;;
esac
