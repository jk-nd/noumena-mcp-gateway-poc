#!/bin/sh
# Post-provisioning script to initialize services.yaml from default template
# Ensures services.yaml exists with base configuration after Keycloak provisioning

set -e

echo "🔄 Initializing services.yaml from default template..."

# Define paths
SERVICES_YAML="/configs/services.yaml"
SERVICES_DEFAULT="/configs/services.yaml.default"

# Check if default template exists
if [ ! -f "$SERVICES_DEFAULT" ]; then
  echo "❌ services.yaml.default not found at $SERVICES_DEFAULT"
  exit 1
fi

# Only create services.yaml if it doesn't exist (preserve manual changes)
if [ ! -f "$SERVICES_YAML" ]; then
  cp "$SERVICES_DEFAULT" "$SERVICES_YAML"
  echo "✓ Created services.yaml from default template"
  echo "ℹ  Users list is empty - use TUI to register Keycloak users"
else
  echo "✓ services.yaml already exists - preserving existing configuration"
fi

echo "✅ Initialization complete!"
