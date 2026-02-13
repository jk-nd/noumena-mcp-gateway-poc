# User Access Control - Phase 2

## Overview

Phase 2 introduces **per-user/agent tool access control** to the MCP Gateway. Users can now be granted granular access to specific tools across services, with enforcement via a two-tier architecture: a fast local check against `services.yaml` (sub-millisecond, no network) and a slow NPL protocol check for dynamic policy evaluation. The Governance Service runs as an Envoy `ext_authz` filter, sitting between Envoy and the upstream MCP services.

## Architecture Components

### 1. NPL Protocols (Policy Layer)

Located in `npl/src/main/npl-1.0/users/`

#### **UserRegistry**
- **Purpose**: Master registry of all users/agents with tool access
- **Key Functions**:
  - `registerUser(userId)` - Add a new user to the registry
  - `removeUser(userId)` - Remove a user from the registry
  - `isUserRegistered(userId)` - Check if a user exists
  - `getAllUsers()` - Get all registered users

#### **UserToolAccess[userId]**
- **Purpose**: Per-user tool access control (one instance per user)
- **Key Functions**:
  - `grantTool(serviceName, toolName)` - Grant a specific tool
  - `revokeTool(serviceName, toolName)` - Revoke a specific tool
  - `grantAllToolsForService(serviceName)` - Grant all tools (wildcard `*`)
  - `revokeServiceAccess(serviceName)` - Revoke all access to a service
  - `hasAccess(serviceName, toolName)` - Check if user has access (called by Governance Service)
  - `getAccessMap()` - Get complete access map for audit

**Wildcard Support**: Setting `["*"]` for a service grants access to ALL tools for that service.

### 2. Configuration (Persistence Layer)

Located in `configs/services.yaml`

```yaml
user_access:
  # Default template for new users (optional)
  default_template:
    enabled: true
    description: "Default tool access for new users"
    tools:
      duckduckgo: ["search"]
  
  # Registered users and their tool access
  users:
    - userId: "alice@acme.com"
      keycloakId: "aa0e467f-68b6-4a85-9057-4e809a66452c"
      displayName: "Alice"
      createdAt: "2026-02-07T18:00:00Z"
      tools:
        duckduckgo: ["search", "fetch_content"]
        github: ["get_issue", "create_issue"]
        slack: ["*"]  # Wildcard: all tools
      vaultPaths:  # Future: Vault integration
        github: "vault/users/alice@acme.com/github"
```

### 3. TUI (Management Layer)

New menu: **User Management**

#### Features:
- **Create User**: Create in Keycloak + NPL + config
- **Import User**: Import existing Keycloak users
- **Edit Tool Access**: Grant/revoke tools per service
- **View Access**: Display detailed permissions
- **Delete User**: Remove from Keycloak + NPL + config

#### Tool Access Management:
- Select service → Select tools (multi-select with SPACE)
- Grant All / Revoke All quick actions
- Real-time sync to NPL UserToolAccess protocol
- Persisted to `services.yaml`

### 4. OPA Policy Engine (Enforcement Layer)

Located in `policies/mcp_authz.rego` (with tests in `policies/mcp_authz_test.rego`)

Policy enforcement is handled by OPA (Open Policy Agent) running as an Envoy `ext_authz` filter. OPA evaluates Rego policies that check JWT claims, user access from `services.yaml`, and NPL protocol state via action endpoints.

#### Policy Check Flow:

```
1. Envoy validates JWT (Keycloak JWKS)
2. Envoy forwards to OPA ext_authz filter
3. OPA evaluates mcp_authz.rego policy
4. OPA extracts userId from JWT claims (email/preferred_username/sub)
5. OPA checks user_access in services.yaml config
6. OPA queries NPL action endpoints for dynamic policy state
7. ALLOW or DENY based on policy evaluation
```

#### Fail-Closed Behavior:
- If OPA is unavailable → DENY (Envoy ext_authz fail-closed)
- If user not found in config → DENY
- If tool not granted → DENY

## Bootstrap Process

Run `NPL Bootstrap` in TUI to:

1. Create `ServiceRegistry` (if needed)
2. Sync enabled services from `services.yaml`
3. Create `ToolPolicy` instances per service
4. Enable tools in `ToolPolicy`
5. **Create `UserRegistry`** ← NEW
6. **Sync users from `services.yaml`** ← NEW
7. **Create `UserToolAccess` instances per user** ← NEW
8. **Grant tools to each user** ← NEW

## User Lifecycle

### Creating a User

**Via TUI → Create New User:**
1. Provide email, username, first name, last name, password
2. User created in Keycloak (with initial password)
3. User registered in NPL `UserRegistry`
4. `UserToolAccess[userId]` protocol created
5. User added to `services.yaml`
6. Default tools granted (from `default_template`)

**Via TUI → Import from Keycloak:**
1. Select existing Keycloak user
2. User registered in NPL `UserRegistry`
3. `UserToolAccess[userId]` protocol created
4. User added to `services.yaml`
5. No tools granted initially (empty access)

### Granting Tool Access

**Granular (per-tool):**
```typescript
// 1. NPL first (source of truth)
await grantToolInNpl(userId, "duckduckgo", "search");
// 2. YAML second (persistent cache)
grantToolToUser(userId, "duckduckgo", "search");
```

**Wildcard (all tools):**
```typescript
// 1. NPL first (source of truth)
await grantAllToolsForServiceInNpl(userId, "slack");
// 2. YAML second (persistent cache)
grantAllToolsToUser(userId, "slack");
```

### Revoking Access

**Per-tool:**
```typescript
// 1. NPL first (source of truth)
await revokeToolInNpl(userId, "duckduckgo", "search");
// 2. YAML second (persistent cache)
revokeToolFromUser(userId, "duckduckgo", "search");
```

**Service-wide:**
```typescript
// 1. NPL first (source of truth)
await revokeServiceInNpl(userId, "slack");
// 2. YAML second (persistent cache)
revokeServiceFromUser(userId, "slack");
```

### Deleting a User

**Via TUI → Delete User:**
1. Remove from NPL `UserRegistry`
2. Delete from Keycloak (if Keycloak ID available)
3. Remove from `services.yaml`
4. `UserToolAccess` protocol remains (for audit) but user can no longer authenticate

## Request Flow Example (Envoy + Governance Service)

**Scenario**: Alice calls `duckduckgo__search`

```
┌─────────────────────────────────────────────────────────┐
│ 1. Alice's Agent → Envoy (JWT with email claim)       │
│    POST http://localhost:8000/mcp                       │
└─────────────────────────────────────────────────────────┘
                    ↓
┌─────────────────────────────────────────────────────────┐
│ 2. Envoy validates JWT via Keycloak JWKS               │
└─────────────────────────────────────────────────────────┘
                    ↓
┌─────────────────────────────────────────────────────────┐
│ 3. Envoy ext_authz → Governance Service                │
│    (forwards JSON-RPC body + JWT)                       │
└─────────────────────────────────────────────────────────┘
                    ↓
┌─────────────────────────────────────────────────────────┐
│ 4. Governance Service: Parse JSON-RPC body             │
│    - method: "tools/call"                               │
│    - tool: "duckduckgo__search"                         │
│    - userId: alice@acme.com (from JWT)                  │
└─────────────────────────────────────────────────────────┘
                    ↓
┌─────────────────────────────────────────────────────────┐
│ 5. FAST PATH: Check services.yaml user_access          │
│    - alice@acme.com has duckduckgo: ["search"]          │
│    - ✓ PASS (user found, tool granted locally)         │
└─────────────────────────────────────────────────────────┘
                    ↓
┌─────────────────────────────────────────────────────────┐
│ 6. SLOW PATH: NPL ToolPolicy[duckduckgo]              │
│    - checkAccess("search", "alice@acme.com")            │
│    - ✓ ALLOWED (tool is enabled globally)              │
└─────────────────────────────────────────────────────────┘
                    ↓
┌─────────────────────────────────────────────────────────┐
│ 7. SLOW PATH: NPL UserToolAccess[alice@acme.com]      │
│    - hasAccess("duckduckgo", "search")                  │
│    - ✓ ALLOWED (Alice has search granted)              │
└─────────────────────────────────────────────────────────┘
                    ↓
┌─────────────────────────────────────────────────────────┐
│ 8. Governance Service returns ALLOW → Envoy forwards   │
│    to upstream duckduckgo MCP service                   │
└─────────────────────────────────────────────────────────┘
```

**Denial Scenarios**:

- **Fast Path Reject**: User not found in services.yaml or tool not granted → NPL is never called
- **Service Disabled**: ToolPolicy doesn't exist or service not in ServiceRegistry
- **Tool Disabled**: ToolPolicy.checkAccess returns false
- **User Lacks Access**: UserToolAccess.hasAccess returns false
- **User Not Registered**: UserToolAccess protocol doesn't exist (allows for backward compat)

## NPL API Examples

### Query User Access
```bash
# Get user's complete access map
curl -X POST \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  "$NPL_URL/npl/users/UserToolAccess/$ACCESS_ID/getAccessMap" \
  -d '{}'
```

### Grant Tool Access
```bash
# Grant specific tool
curl -X POST \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  "$NPL_URL/npl/users/UserToolAccess/$ACCESS_ID/grantTool" \
  -d '{"serviceName": "github", "toolName": "create_issue"}'
```

### Check Access (Governance Service calls this)
```bash
# Check if user has access
curl -X POST \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  "$NPL_URL/npl/users/UserToolAccess/$ACCESS_ID/hasAccess" \
  -d '{"serviceName": "duckduckgo", "toolName": "search"}'
```

## Security Model

### Defense in Depth

1. **Service Level (ToolPolicy)**:
   - Org-wide policy: "Is this tool enabled at all?"
   - Controls which tools are available to ANY user
   - Default-deny: tools must be explicitly enabled

2. **User Level (UserToolAccess)**:
   - Per-user policy: "Does THIS user have access to this tool?"
   - Granular control per user/agent
   - Supports wildcard for convenience

3. **Credential Isolation** (Future - Phase 3):
   - Vault integration for per-user credentials
   - Credentials stored at `vaultPaths[serviceName]`
   - Governance Service fetches user-specific credentials before forwarding

### Fail-Closed Principle

- All NPL checks fail-closed
- If NPL is unreachable → DENY
- If protocol instance not found → DENY (except backward compat for no UserToolAccess)
- If permission call fails → DENY
- Governance Service never bypasses policy checks (except DEV_MODE)

### Audit Trail

- All access checks logged by NPL protocols
- `UserToolAccess.requestCounter` tracks total checks per user
- Governance Service logs show full policy check results
- NPL logs show grant/revoke operations with timestamps

## Backward Compatibility

**Phase 1 (Service/Tool Policy Only)**:
- If no `UserToolAccess` exists for a user, the check is skipped
- User is allowed if service-level policy passes
- Enables gradual migration: users can be added incrementally

**Phase 2 (User Access Control)**:
- All existing users continue to work (no UserToolAccess = allow)
- New users require explicit tool grants
- Admin can create UserToolAccess for existing users to restrict them

## Future Enhancements (Phase 3+)

### HashiCorp Vault Integration
- Store per-user credentials in Vault
- Governance Service fetches credentials before forwarding tool calls
- `vaultPaths` map already included in config schema

### Credential Modes (from architecture plan)
- **auto**: Governance Service fetches from Vault automatically
- **confirm**: User approves via Telegram/UI
- **provide**: User provides credentials in real-time

### Business Rules (NPL Extensions)
- Time-based access (office hours only)
- Rate limiting per user
- Context-aware policies (IP, device, session duration)
- Approval workflows for sensitive operations

## Testing the Implementation

### 1. Bootstrap NPL
```bash
cd tui
npm start
# Login as admin
# Select "NPL Bootstrap"
# Verify UserRegistry and users are created
```

### 2. Create a Test User
```bash
# In TUI: User Management → Create new user
# Email: test@example.com
# Username: testuser
# Password: Welcome123
```

### 3. Grant Tool Access
```bash
# In TUI: User Management → test@example.com → Edit tool access
# Select duckduckgo → Grant "search"
```

### 4. Test Tool Call
```bash
# Get token for test user
TOKEN=$(curl -s -X POST \
  "http://localhost:11000/realms/mcpgateway/protocol/openid-connect/token" \
  -d "grant_type=password" \
  -d "client_id=mcpgateway" \
  -d "username=testuser" \
  -d "password=Welcome123" | jq -r '.access_token')

# Call tool via Envoy → Governance Service → upstream
curl -X POST \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" \
  "http://localhost:8000/mcp" \
  -d '{
    "jsonrpc": "2.0",
    "id": 1,
    "method": "tools/call",
    "params": {
      "name": "duckduckgo__search",
      "arguments": {"query": "test"}
    }
  }'
```

### 5. Verify Denial
```bash
# Try calling a tool NOT granted to testuser
curl -X POST \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" \
  "http://localhost:8000/mcp" \
  -d '{
    "jsonrpc": "2.0",
    "id": 1,
    "method": "tools/call",
    "params": {
      "name": "github__create_issue",
      "arguments": {"owner": "test", "repo": "test", "title": "test"}
    }
  }'
# Expected: Policy denied error
```

## Troubleshooting

### User can't call any tools

**Check:**
1. Is user registered in NPL? (TUI → User Management → view users)
2. Does UserToolAccess protocol exist for user? (Check NPL logs)
3. Are tools granted? (TUI → Edit tool access)
4. Is service enabled? (Main menu → check service status)
5. Is tool enabled in ToolPolicy? (Service → Manage tools)

### "Policy engine unavailable" errors

**Check:**
1. Is NPL Engine running? (`docker ps | grep npl`)
2. Can Governance Service reach NPL? (Check `NPL_URL` env var)
3. Are NPL credentials valid? (Check `AGENT_USERNAME/PASSWORD`)
4. Is Envoy routing to Governance Service? (Check Envoy ext_authz config)

### Tool granted but still denied

**Check:**
1. Is service enabled in ServiceRegistry?
2. Is tool enabled in ToolPolicy?
3. Check Governance Service logs for exact denial reason
4. Verify JWT token contains correct email/preferred_username/sub claim
5. Is user listed in services.yaml user_access? (fast path check)

## Configuration Reference

### Environment Variables (Governance Service)

```bash
NPL_URL=http://npl-engine:12000
KEYCLOAK_URL=http://keycloak:11000
KEYCLOAK_REALM=mcpgateway
AGENT_USERNAME=agent
AGENT_PASSWORD=Welcome123
SERVICES_YAML_PATH=/configs/services.yaml  # Path to user_access config
DEV_MODE=false  # Set to true to bypass all policy checks
```

### Environment Variables (TUI)

```bash
GATEWAY_URL=http://localhost:8000
NPL_URL=http://localhost:12000
KEYCLOAK_URL=http://localhost:11000
KEYCLOAK_REALM=mcpgateway
KEYCLOAK_CLIENT_ID=mcpgateway
```

## Summary

Phase 2 delivers:

✅ **NPL Protocols**: UserRegistry + UserToolAccess  
✅ **TUI Management**: User creation, tool grants, deletion  
✅ **Governance Service Enforcement**: Two-tier policy checks (fast YAML + slow NPL)
✅ **Configuration**: Persistent user access in services.yaml  
✅ **Bootstrap**: Automated setup and sync  
✅ **Security**: Fail-closed, defense-in-depth, audit trail  
✅ **Backward Compatible**: Optional adoption, gradual migration  

**Next Phase**: HashiCorp Vault integration for per-user credential management.
