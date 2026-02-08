#!/bin/sh
# Post-provisioning script to sync Keycloak user IDs to services.yaml
# This ensures services.yaml always has the correct Keycloak IDs after provisioning

set -e

echo "🔄 Syncing Keycloak user IDs to services.yaml..."

# Wait a moment for Keycloak to be fully ready
sleep 2

# Get access token
TOKEN_RESPONSE=$(curl -s -X POST "${KEYCLOAK_URL}/realms/master/protocol/openid-connect/token" \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "username=${KEYCLOAK_USER}" \
  -d "password=${KEYCLOAK_PASSWORD}" \
  -d "grant_type=password" \
  -d "client_id=admin-cli")

ACCESS_TOKEN=$(echo $TOKEN_RESPONSE | grep -o '"access_token":"[^"]*' | cut -d'"' -f4)

if [ -z "$ACCESS_TOKEN" ]; then
  echo "❌ Failed to get access token"
  exit 1
fi

# Get users from mcpgateway realm
USERS=$(curl -s -X GET "${KEYCLOAK_URL}/admin/realms/mcpgateway/users" \
  -H "Authorization: Bearer ${ACCESS_TOKEN}")

# Extract user IDs using jq
JARVIS_ID=$(echo "$USERS" | jq -r '.[] | select(.username == "jarvis") | .id')
ALICE_ID=$(echo "$USERS" | jq -r '.[] | select(.username == "alice") | .id')
BOB_ID=$(echo "$USERS" | jq -r '.[] | select(.username == "bob") | .id')

if [ -z "$JARVIS_ID" ] || [ -z "$ALICE_ID" ] || [ -z "$BOB_ID" ]; then
  echo "❌ Failed to fetch all user IDs"
  echo "   jarvis: $JARVIS_ID"
  echo "   alice: $ALICE_ID"
  echo "   bob: $BOB_ID"
  exit 1
fi

echo "✓ Found user IDs:"
echo "  jarvis: $JARVIS_ID"
echo "  alice: $ALICE_ID"
echo "  bob: $BOB_ID"

# Update services.yaml from default template
SERVICES_YAML="/configs/services.yaml"
SERVICES_DEFAULT="/configs/services.yaml.default"

if [ ! -f "$SERVICES_DEFAULT" ]; then
  echo "❌ services.yaml.default not found at $SERVICES_DEFAULT"
  exit 1
fi

# Create services.yaml with actual IDs
sed -e "s/PLACEHOLDER_JARVIS_ID/$JARVIS_ID/" \
    -e "s/PLACEHOLDER_ALICE_ID/$ALICE_ID/" \
    -e "s/PLACEHOLDER_BOB_ID/$BOB_ID/" \
    "$SERVICES_DEFAULT" > "$SERVICES_YAML"

echo "✓ Updated services.yaml with fresh Keycloak IDs"
echo "✅ Sync complete!"
