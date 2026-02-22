# How-To Guide: Configuring the MCP Gateway

A hands-on walkthrough for configuring and operating the MCP Gateway security pipeline. Follow these steps in order for a complete setup, or jump to the section you need.

**Contents**

1. [Prerequisites & Starting the Stack](#1-prerequisites--starting-the-stack)
2. [First-Time Setup](#2-first-time-setup)
3. [Adding an MCP Service](#3-adding-an-mcp-service)
4. [Managing Users & Access Rules](#4-managing-users--access-rules)
5. [Governance Rules & Approval Workflows](#5-governance-rules--approval-workflows)
6. [Dashboard](#6-dashboard)
7. [Credential Injection](#7-credential-injection-optional)
8. [Debugging & Troubleshooting](#8-debugging--troubleshooting)
9. [Configuration Reference](#9-configuration-reference)
10. [Running Tests](#10-running-tests)

---

## 1. Prerequisites & Starting the Stack

**Requirements:** Docker and Docker Compose.

### Start everything

```bash
cd deployments
docker compose up -d
```

### Wait for healthy services

Keycloak and the NPL Engine take the longest to initialize. Wait for them:

```bash
docker compose ps --format "table {{.Name}}\t{{.Status}}"
```

All services should show `(healthy)` in their status. If Keycloak is still starting, give it up to 60 seconds.

### Verify with health endpoints

```bash
# Envoy gateway
curl http://localhost:8000/health

# OPA bundle server
curl http://localhost:8282/health

# Keycloak
curl http://localhost:9000/health
```

### Service URLs

| Service | URL | Credentials |
|---------|-----|-------------|
| **Dashboard** | `http://localhost:8888` | admin / Welcome123 |
| Envoy Gateway (MCP endpoint) | `http://localhost:8000/mcp` | Bearer JWT |
| Envoy Admin | `http://localhost:9901` | (none) |
| OPA HTTP API | `http://localhost:8181` | (none) |
| OPA Bundle Server | `http://localhost:8282` | (none) |
| Keycloak Admin Console | `http://localhost:11000` | admin / welcome |
| NPL Engine API | `http://localhost:12000` | Bearer JWT |
| NPL Inspector | `http://localhost:8080` | admin / Welcome123 |
| Vault UI | `http://localhost:8200` | Token: `dev-token` |

---

## 2. First-Time Setup

There are two ways to configure the gateway: the **Dashboard** (web UI) or the **TUI** (CLI wizard).

### Option A: Dashboard (recommended)

Open `http://localhost:8888` and log in with `admin` / `Welcome123`. The dashboard provides a full admin interface for managing the gateway.

### Option B: TUI Wizard

```bash
cd tui
npm install   # first time only
npm start
```

Log in with `admin` / `Welcome123`.

### NPL Bootstrap

On first use, the system creates a **GatewayStore singleton** — the single source of truth for all policy data. This happens automatically.

The GatewayStore holds:
- **Catalog** — registered services and tools with open/gated tags
- **Access Rules** — claim-based and identity-based authorization rules
- **Revoked Subjects** — emergency kill switch entries

**ServiceGovernance** instances (one per governed service) hold:
- **Tool Configs** — argument-level constraints (regex, in/not_in, max_length)
- **Pending Requests** — gated tool calls awaiting approval
- **Approval Settings** — per-tool approval requirements and deadlines

---

## 3. Adding an MCP Service

There are three ways to add a service.

### Option A: TUI Wizard (recommended)

From the main menu:

1. Select **+ Search Docker Hub** to browse the `mcp/*` namespace
2. Or select **+ Add MCP service** to enter a custom Docker command, NPM package, or template

The TUI will:
- Register the service in the GatewayStore catalog
- Discover available tools from the MCP server
- Let you enable/disable individual tools with open/gated tags

### Option B: Dashboard

In the Dashboard (http://localhost:8888):
1. Go to **Catalog** > **+ Add Service**
2. Enter the service name or select from running backends
3. Use **+ Tool** to register tools with open/gated tags
4. Or use **Discover** to search Docker Hub and wire services automatically

### Option C: Manual via NPL API

Register and configure a service using curl:

```bash
# Get a token
TOKEN=$(curl -s -X POST "http://localhost:11000/realms/mcpgateway/protocol/openid-connect/token" \
  -d "grant_type=password&client_id=mcpgateway&username=admin&password=Welcome123" \
  | jq -r '.access_token')

# Find the GatewayStore instance
STORE_ID=$(curl -s -H "Authorization: Bearer $TOKEN" \
  "http://localhost:12000/npl/store/GatewayStore/" \
  | jq -r '.items[0]["@id"]')

# Register a service
curl -s -X POST "http://localhost:12000/npl/store/GatewayStore/$STORE_ID/registerService" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"serviceName": "my-service"}'

# Enable the service
curl -s -X POST "http://localhost:12000/npl/store/GatewayStore/$STORE_ID/enableService" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"serviceName": "my-service"}'

# Register a tool (with open or gated tag)
curl -s -X POST "http://localhost:12000/npl/store/GatewayStore/$STORE_ID/registerTool" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"serviceName": "my-service", "toolName": "my-tool", "tag": "open"}'
```

### Option C: Bulk import from services.yaml

Edit `configs/services.yaml` with your services:

```yaml
services:
  - name: "my-service"
    displayName: "My Service"
    type: "MCP_STDIO"
    enabled: true
    command: "docker run -i --rm mcp/my-service"
    requiresCredentials: false
    description: "Description of my service"
    tools:
      - name: "my-tool"
        description: "What this tool does"
        inputSchema:
          type: "object"
          properties: {}
          required: []
        enabled: true

user_access:
  users:
    - userId: "user@example.com"
      tools:
        my-service:
          - "*"
```

Then import via TUI: **Import / Export > Import from YAML**.

### Adding a backend container

To run a new MCP server as part of the Docker stack, add a supergateway sidecar to `deployments/docker-compose.yml`:

```yaml
  my-service-mcp:
    build:
      context: ..
      dockerfile: deployments/docker/Dockerfile.supergateway
    environment:
      MCP_COMMAND: "docker run -i --rm mcp/my-service"
    volumes:
      - /var/run/docker.sock:/var/run/docker.sock
    networks:
      - backend-net
```

Then register it in the MCP aggregator's `BACKENDS` environment variable:

```yaml
  mcp-aggregator:
    environment:
      BACKENDS: "duckduckgo:http://duckduckgo-mcp:8000,my-service:http://my-service-mcp:8000"
```

Restart the stack: `docker compose up -d`.

---

## 4. Managing Users & Access Rules

### Creating users

**Via Dashboard:** Go to **Users** > **+ Create User**. Set username, email, role, organization, and department.

**Via TUI:** Main menu > **Manage users & tool access** > Register a new user.

**Via Keycloak Admin UI:**
1. Go to `http://localhost:11000`
2. Log in with admin / welcome
3. Navigate to the `mcpgateway` realm
4. Users > Add user

### Access rules (v4)

In v4, access is controlled by **access rules** that match on JWT claims or identity. Rules can target groups of users or individuals.

**Via Dashboard:** Go to **Access Rules** > **+ Add Rule**. Choose claim-based (match by role, department, organization) or identity-based (match specific user email).

**Via API:**

```bash
# Add a claim-based rule (all sales team members get calendar access)
curl -s -X POST "http://localhost:12000/npl/store/GatewayStore/$STORE_ID/addAccessRule" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "id": "sales-calendar",
    "matchType": "claims",
    "matchClaims": {"department": "sales", "organization": "acme"},
    "matchIdentity": "",
    "allowServices": ["mock-calendar"],
    "allowTools": ["*"]
  }'

# Add an identity-based rule (specific user gets GitHub read access)
curl -s -X POST "http://localhost:12000/npl/store/GatewayStore/$STORE_ID/addAccessRule" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "id": "jarvis-github",
    "matchType": "identity",
    "matchClaims": {},
    "matchIdentity": "jarvis@acme.com",
    "allowServices": ["github"],
    "allowTools": ["list_repos", "search_code"]
  }'
```

### Emergency kill switch

`revokeSubject` blocks ALL access for a user instantly — across every service:

**Via Dashboard:** Go to **Revoked** > **+ Revoke Subject**.

**Via API:**

```bash
curl -s -X POST "http://localhost:12000/npl/store/GatewayStore/$STORE_ID/revokeSubject" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"subjectId": "compromised-user@example.com"}'
```

### How changes propagate

When you change an access rule or catalog entry, the update flows through the pipeline:

```
GatewayStore (NPL) → SSE event → Bundle Server → OPA bundle rebuild → Envoy ext_authz
```

Typical propagation time: **2-6 seconds**. The bundle server subscribes to SSE push notifications from the NPL Engine and rebuilds the OPA bundle on every state change.

---

## 5. Governance Rules & Approval Workflows

Governance rules add fine-grained argument-level constraints and approval workflows on top of the access rules layer. They apply to tools tagged as **gated** in the catalog.

### 5.1 Open vs Gated Tags

Each tool in the catalog has a tag:

| Tag | Meaning | OPA behavior |
|-----|---------|--------------|
| `open` | Stateless check — access rules sufficient | Allow if catalog + access rules pass |
| `gated` | Stateful check — requires governance evaluation | Allow only if catalog + access rules + governance evaluator returns allow |

Toggle tags in the Dashboard via the **Catalog** panel, or via API:

```bash
curl -s -X POST "http://localhost:12000/npl/store/GatewayStore/$STORE_ID/setTag" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"serviceName": "duckduckgo", "toolName": "search", "tag": "gated"}'
```

### 5.2 Argument-Level Constraints

For gated tools, you can define argument-level constraints that are evaluated before the request reaches NPL. These are managed through the governance evaluator.

**Via Dashboard:** Go to **Gov Rules** > **+ New Rule**. The wizard guides you through:
1. Select a service (must have governance enabled)
2. Select a tool (shows available parameters from MCP schema)
3. Define the constraint (parameter, operator, values)
4. Review and apply

**Constraint operators:**

| Operator | Description | Example |
|----------|-------------|---------|
| `in` | Value must be in allowed list | domains: `["wikipedia.org", "stackoverflow.com"]` |
| `not_in` | Value must not be in blocked list | domains: `["malware.com"]` |
| `contains` | Value must contain a substring | query must contain `"safe"` |
| `not_contains` | Value must not contain a substring | query must not contain `"DROP TABLE"` |
| `regex` | Value must match a pattern | email must match `".*@acme\\.com"` |
| `max_length` | Value length must not exceed limit | query max 500 characters |

**Via API:**

```bash
# Add a constraint: search queries must only target allowed domains
curl -s -X POST "http://localhost:12000/npl/governance/ServiceGovernance/$INSTANCE_ID/addConstraint" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "toolName": "search",
    "paramName": "query",
    "operator": "max_length",
    "values": ["500"],
    "description": "Limit search queries to 500 characters"
  }'
```

### 5.3 Approval Workflows

When a gated tool call passes constraint checks but still requires human approval, the governance evaluator routes to NPL's ServiceGovernance protocol. The approval flow:

1. Agent calls a gated tool
2. OPA calls governance evaluator
3. Evaluator checks argument constraints
4. If constraints pass and approval is required, a pending request is created
5. Admin approves or denies in Dashboard (**Approvals** panel)
6. On next retry, the tool call is allowed/denied based on the decision

**Via Dashboard:** Go to **Approvals** to see pending requests. Click **Approve** or **Deny**.

### 5.4 Auto-Allow Mode

For tools where constraints alone are sufficient (no human approval needed), set auto-allow mode:

**Via Dashboard:** In **Gov Rules**, click **Set Auto-Allow** on a tool's constraint card.

When auto-allow is set, calls that pass all constraints are allowed immediately without creating a pending approval request.

---

## 6. Dashboard

The admin dashboard at `http://localhost:8888` provides a full web interface for gateway management.

### Panels

| Panel | Description |
|-------|-------------|
| **Overview** | System health status, key stats, aggregator backends |
| **Activity Log** | Real-time NPL Engine state changes via SSE stream |
| **Metrics** | Real-time operational metrics from all components (auto-refreshes every 5s) |
| **Catalog** | Service and tool management with open/gated toggles |
| **Access Rules** | Claim-based and identity-based authorization rules |
| **Gov Rules** | Argument-level constraints for gated tools |
| **Approvals** | Pending approval requests for gated tool calls |
| **Revoked** | Emergency subject revocation |
| **Users** | Keycloak user directory and management |
| **Discover** | Search Docker Hub and MCP Registry, wire services into the gateway |

### Metrics Panel

The Metrics panel shows real-time data from all gateway components in collapsible sections:

- **Envoy Gateway** — request counts, response codes (2xx/4xx/5xx), active connections, ext_authz allow/deny
- **OPA Policy Engine** — health status, decision counts, bundle revision
- **Bundle Server** — revision, bundle age, SSE connection, rebuild count/errors
- **Governance Evaluator** — health status, cached services count
- **MCP Aggregator** — active sessions, registered backends

---

## 7. Credential Injection (Optional)

Some MCP servers need API keys or tokens to work (GitHub, Slack, etc.). The gateway supports credential injection via HashiCorp Vault.

### When to use

Use credential injection when:
- An MCP server requires environment variables like `GITHUB_TOKEN` or `SLACK_TOKEN`
- You want secrets managed centrally, not baked into Docker images
- You need per-user or per-tenant credential isolation

### Storing secrets in Vault

**Via Vault UI:** Go to `http://localhost:8200`, log in with token `dev-token`, and create secrets.

**Via CLI:**

```bash
# Store a tenant-level secret
curl -s -X POST "http://localhost:8200/v1/secret/data/tenants/acme/services/github/work" \
  -H "X-Vault-Token: dev-token" \
  -H "Content-Type: application/json" \
  -d '{"data": {"token": "ghp_your_github_token_here"}}'

# Store a user-level secret
curl -s -X POST "http://localhost:8200/v1/secret/data/tenants/acme/users/alice/github/work" \
  -H "X-Vault-Token: dev-token" \
  -H "Content-Type: application/json" \
  -d '{"data": {"token": "ghp_alice_personal_token"}}'
```

### Configuring credentials.yaml

Edit `configs/credentials.yaml` to define how Vault secrets map to environment variables:

```yaml
mode: simple
tenant: acme

credentials:
  work_github:
    vault_path: secret/data/tenants/{tenant}/users/{user}/github/work
    injection:
      type: env
      mapping:
        token: GITHUB_TOKEN

  prod_slack:
    vault_path: secret/data/tenants/{tenant}/services/slack/prod
    injection:
      type: env
      mapping:
        token: SLACK_TOKEN

  admin_db:
    vault_path: secret/data/tenants/{tenant}/services/database/admin
    injection:
      type: env
      mapping:
        username: DB_USER
        password: DB_PASS

service_defaults:
  github: work_github
  slack: prod_slack
  database: readonly_db

default_credential:
  vault_path: secret/data/tenants/{tenant}/users/{user}/default
  injection:
    type: env
    mapping:
      api_key: API_KEY
```

### Two scopes

| Scope | Vault path pattern | Use case |
|-------|-------------------|----------|
| Tenant-level | `secret/data/tenants/{tenant}/services/SERVICE/...` | Shared org-wide keys (Slack bot token, DB credentials) |
| User-level | `secret/data/tenants/{tenant}/users/{user}/SERVICE/...` | Per-user keys (personal GitHub tokens) |

Template variables `{tenant}` and `{user}` are replaced at runtime.

### How it works

1. Admin stores secrets in Vault (via TUI or Vault UI)
2. Supergateway sidecar calls the credential proxy at startup
3. Credential proxy looks up `configs/credentials.yaml`, resolves the vault path, fetches from Vault
4. Supergateway exports the values as environment variables
5. The STDIO MCP tool is spawned with those environment variables available

---

## 8. Debugging & Troubleshooting

### Inspecting OPA bundle data

View the full policy data that OPA is using:

```bash
# All policy data
curl -s http://localhost:8181/v1/data | jq .

# Just the catalog
curl -s http://localhost:8181/v1/data/catalog | jq .

# Just the access rules
curl -s http://localhost:8181/v1/data/access_rules | jq .

# Revoked subjects
curl -s http://localhost:8181/v1/data/revoked_subjects | jq .
```

### OPA decision logs

```bash
docker compose logs opa --tail 100 -f
```

Decision logs show every authorization decision with the input, result, and any errors.

### Bundle server health

```bash
curl -s http://localhost:8282/health | jq .
```

Response fields:
- `status`: `healthy`, `initializing`, or `degraded`
- `revision`: Current bundle SHA256 hash (first 16 chars)
- `bundle_age_seconds`: How old the current bundle is
- `sse_connected`: Whether SSE subscription to NPL is active
- `last_sse_event_at`: Last SSE event timestamp
- `rebuild_count` / `rebuild_error_count`: Bundle build metrics

### Envoy ext_authz stats

```bash
curl -s http://localhost:9901/stats | grep ext_authz
```

Key metrics:
- `ext_authz.denied`: Number of denied requests
- `ext_authz.ok`: Number of allowed requests
- `ext_authz.error`: Number of OPA errors (should be 0)

### Response headers for tracing

Every request through the gateway gets tracing headers in the response:

| Header | Value | When |
|--------|-------|------|
| `x-authz-reason` | Denial reason | On 403 |
| `x-governance-action` | `allow`, `deny`, `pending_approval` | When governance layer evaluates |
| `x-governance-rule` | Name of matched governance rule | When governance rule matched |
| `x-approval-id` | `APR-N` | When approval is pending |
| `x-user-id` | User identity from JWT | On every authorized request |
| `x-granted-services` | CSV of granted services | On tools/list requests |
| `x-bundle-revision` | Bundle hash | On every request |

**Example — inspecting a denied request:**

```bash
TOKEN=$(curl -s -X POST "http://localhost:11000/realms/mcpgateway/protocol/openid-connect/token" \
  -d "grant_type=password&client_id=mcpgateway&username=admin&password=Welcome123" \
  | jq -r '.access_token')

curl -v -X POST http://localhost:8000/mcp \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"gmail.send_email","arguments":{"to":"external@other.com","subject":"test"}}}'
```

Look for `x-governance-action`, `x-governance-rule`, and `x-authz-reason` in the response headers.

### Common issues

**Bundle not rebuilding:**
- Check SSE connection: `curl -s http://localhost:8282/health | jq .sse_connected`
- If `false`, restart the bundle server: `docker compose restart bundle-server`
- Check bundle server logs: `docker compose logs bundle-server --tail 50`

**403 without reason headers:**
- OPA might not be returning headers correctly. Check OPA logs for errors.
- Verify OPA gRPC ext_authz is running: `curl -s http://localhost:8181/health`
- Verify OPA has bundle data: `curl -s http://localhost:8181/v1/data/catalog | jq .`

**Governance rules not taking effect:**
- Verify catalog data in OPA: `curl -s http://localhost:8181/v1/data/catalog | jq .`
- Check if the bundle was rebuilt after changes: compare `x-bundle-revision` header with `curl -s http://localhost:8282/health | jq .revision`
- For gated tools, check the governance evaluator: `curl -s http://localhost:8090/health | jq .`

**Approval workflow not working:**
- Verify the ServiceGovernance instance exists for the service
- Check governance evaluator logs: `docker compose logs governance-evaluator --tail 50`
- Verify NPL Engine health: `curl -s http://localhost:12000/actuator/health | jq .`

---

## 9. Configuration Reference

| File | Purpose | Read by | When |
|------|---------|---------|------|
| `configs/services.yaml` | Service definitions (cache for bulk import/export) | Dashboard or API | Import/export only; GatewayStore is authoritative |
| `configs/credentials.yaml` | Vault-to-env-var credential mappings | Credential Proxy | At supergateway startup |
| `deployments/docker-compose.yml` | Service orchestration (add backend containers here) | Docker Compose | `docker compose up` |
| `deployments/envoy/envoy-config.yaml` | Envoy routing, JWT, CORS, ext_authz config | Envoy | At startup (rarely needs editing) |
| `deployments/opa/opa-config.yaml` | OPA bundle polling and plugin config | OPA | At startup |
| `npl/src/main/yaml/rules.yml` | NPL party-to-role mappings (JWT claims → NPL parties) | NPL Engine | At startup (rarely needs editing) |
| `policies/mcp_authz.rego` | OPA Rego authorization policy | OPA | At startup |

> [-> Architecture Reference](ARCHITECTURE.md) for the full system topology and network design.

---

## 10. Running Tests

### OPA Rego unit tests

```bash
opa test policies/ -v
```

This runs test cases covering authentication, catalog rules, access rules, and governance evaluation. Tests use mock data and don't require the Docker stack.

### Integration tests

Requires the full Docker stack to be running:

```bash
cd integration-tests
./gradlew test
```

Or run via Docker Compose profile:

```bash
cd deployments
docker compose --profile test run test-runner
```

### Manual verification with curl

**Get a token:**

```bash
TOKEN=$(curl -s -X POST "http://localhost:11000/realms/mcpgateway/protocol/openid-connect/token" \
  -d "grant_type=password&client_id=mcpgateway&username=admin&password=Welcome123" \
  | jq -r '.access_token')
```

**Initialize an MCP session:**

```bash
curl -s -X POST http://localhost:8000/mcp \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"test","version":"1.0"}}}'
```

**List available tools:**

```bash
curl -s -X POST http://localhost:8000/mcp \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/list"}'
```

**Call a tool:**

```bash
curl -s -X POST http://localhost:8000/mcp \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"duckduckgo.search","arguments":{"query":"MCP protocol"}}}'
```

---

**See also:**
- [Architecture Reference](ARCHITECTURE.md) — system topology, network tiers, data flow
- [v4 Governance Design](DESIGN_V4_SIMPLIFIED_GOVERNANCE.md) — three-layer architecture design
- [Approval Workflow Deep Dive](APPROVAL_WORKFLOW.md) — state machine, store-and-forward, protocol API
