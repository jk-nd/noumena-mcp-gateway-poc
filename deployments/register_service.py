#!/usr/bin/env python3
"""Register a service in NPL ServiceRegistry"""

import requests
import json
import sys

# Keycloak auth from within Docker network
KC_URL = "http://keycloak:11000"
KC_REALM = "mcpgateway"
KC_CLIENT_ID = "mcpgateway"

# Service to register
SERVICE_NAME = sys.argv[1] if len(sys.argv) > 1 else "duckduckgo"

# Get token
token_resp = requests.post(
    f"{KC_URL}/realms/{KC_REALM}/protocol/openid-connect/token",
    data={
        "grant_type": "password",
        "client_id": KC_CLIENT_ID,
        "username": "admin",
        "password": "Welcome123"
    }
)
print(f"Token status: {token_resp.status_code}")
if token_resp.status_code != 200:
    print(f"Auth error: {token_resp.text[:500]}")
    sys.exit(1)

token = token_resp.json()["access_token"]
print("Token obtained successfully")
headers = {"Authorization": f"Bearer {token}", "Content-Type": "application/json"}

# NPL API
NPL_URL = "http://npl-engine:12000"

# Get the ServiceRegistry
resp = requests.get(f"{NPL_URL}/npl/registry/ServiceRegistry/", headers=headers)
print(f"ServiceRegistry status: {resp.status_code}")
if resp.status_code != 200:
    print(f"Error: {resp.text[:500]}")
    sys.exit(1)

data = resp.json()
registries = data.get("items", [])
print(f"Found {len(registries)} registries")

if not registries:
    print("No registries found")
    sys.exit(1)

registry = registries[0]
registry_id = registry["@id"]
enabled = registry.get("enabledServices", [])
print(f"Registry ID: {registry_id}")
print(f"Enabled services: {enabled}")

# Enable service
print()
print(f"Enabling '{SERVICE_NAME}' service...")
resp = requests.post(
    f"{NPL_URL}/npl/registry/ServiceRegistry/{registry_id}/enableService",
    headers=headers,
    json={"serviceName": SERVICE_NAME}
)
print(f"Enable response: {resp.status_code}")
if resp.status_code != 200:
    print(f"Error: {resp.text[:200]}")

# Verify
resp = requests.get(f"{NPL_URL}/npl/registry/ServiceRegistry/{registry_id}", headers=headers)
updated = resp.json()
key = "enabledServices"
print(f"Updated enabled services: {updated.get(key, [])}")
