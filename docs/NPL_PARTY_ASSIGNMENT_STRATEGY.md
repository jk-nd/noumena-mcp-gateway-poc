# NPL Party Assignment Strategy

## Overview

NPL protocols use **parties** to control who can call which permissions. This document explains the correct party assignment strategy across all MCP Gateway protocols.

## Core Principles

### 1. **Explicit Party Assignment**

Always explicitly assign parties when creating protocol instances:

```typescript
// ✅ CORRECT
"@parties": {
  "pAdmin": { "role": "admin" },
  "pAgent": { "role": "agent" }
}

// ❌ WRONG - NPL auto-assigns ALL claims from creator's JWT
"@parties": {}
```

### 2. **Three Party Types**

| Type | Example | JWT Binding | Use Case |
|------|---------|-------------|----------|
| **Role-based** | `pAdmin`, `pAgent`, `pAuditor` | `{"role": "admin"}` | Generic service roles |
| **User-specific** | `pUser` | `{"userId": "alice"}` | User's own protocol instance |
| **Empty** | (admin-only) | `{"role": "admin"}` | Management protocols |

## Protocol-Specific Assignments

### ToolPolicy - `protocol[pAdmin, pAgent]`

**Purpose**: Per-service tool access control (admin manages, gateway checks)

```typescript
"@parties": {
  "pAdmin": { "role": "admin" },    // Manages policy (enable/disable tools)
  "pAgent": { "role": "agent" }     // Gateway checks tool access at runtime
}
```

**Permissions**:
- `pAdmin`: `enableTool()`, `disableTool()`, `suspendPolicy()`
- `pAgent`: `checkAccess()`, `getEnabledTools()`

---

### UserToolAccess - `protocol[pAdmin, pUser, pGateway]`

**Purpose**: Per-user tool access control

```typescript
"@parties": {
  "pAdmin": { "role": "admin" },           // Manages user's access
  "pUser": { "userId": "<actual-user>" },  // The user themselves (e.g., "alice")
  "pGateway": { "role": "agent" }          // Gateway checks access at runtime
}
```

**⚠️ Critical**: `pUser` is **user-specific**, NOT a role:
- Alice's instance: `"pUser": { "userId": "alice" }`
- Bob's instance: `"pUser": { "userId": "bob" }`
- Agent's instance: `"pUser": { "userId": "agent" }`

**Permissions**:
- `pAdmin`: `grantTool()`, `revokeTool()`, `revokeAllAccess()`
- `pUser`: `getToolsForService()`, `getAllServices()` (user can view their own access)
- `pGateway`: `hasAccess()`, `getVaultPath()` (runtime checks)

---

### UserRegistry - `protocol[pAdmin]`

**Purpose**: Master list of registered users

```typescript
"@parties": {
  "pAdmin": { "role": "admin" }     // Only admin manages user list
}
```

**Permissions**:
- `pAdmin`: `registerUser()`, `removeUser()`, `isUserRegistered()`, `getAllUsers()`

---

### ServiceRegistry - `protocol[pAdmin]`

**Purpose**: Organization-wide service enablement

```typescript
"@parties": {
  "pAdmin": { "role": "admin" }     // Only admin enables/disables services
}
```

**Permissions**:
- `pAdmin`: `enableService()`, `disableService()`, `isServiceEnabled()`

---

### CredentialInjectionPolicy - `protocol[pAdmin, pGateway, pAuditor]`

**Purpose**: Determines which credential to use for requests

```typescript
"@parties": {
  "pAdmin": { "role": "admin" },      // Manages credential selection rules
  "pGateway": { "role": "agent" },    // Gateway selects credentials at runtime
  "pAuditor": { "role": "auditor" }   // Future: read-only audit access
}
```

**Permissions**:
- `pAdmin`: `addInjectionRule()`, `removeInjectionRule()`
- `pGateway`: `selectCredential()` (runtime)
- `pAuditor`: `getRulesForService()`, `getStatistics()` (read-only)

---

## Role Assignment in Keycloak

### JWT Claims Structure

Each user/service JWT should have a `role` claim:

```json
{
  "sub": "aab2bfec-ebcc-43a3-99c8-052fe8f2637f",
  "preferred_username": "admin",
  "email": "admin@acme.com",
  "role": "admin",                    // ← Party binding claim
  "organization": "acme"
}
```

### Standard Roles

| Role | Keycloak User | Purpose |
|------|---------------|---------|
| `admin` | `admin@acme.com` | TUI management, NPL protocol administration |
| `agent` | `gateway-service` | Gateway service account for runtime policy checks |
| `user` | Regular users | End users with tool access (bound via `userId`) |
| `auditor` | Future | Read-only security auditing |

### Gateway Service Account

The Gateway should authenticate as a **service principal** with `role: agent`:

```json
{
  "sub": "<gateway-service-account-id>",
  "client_id": "mcpgateway",
  "role": "agent"                     // ← Gateway role
}
```

This allows the Gateway to call:
- `ToolPolicy.checkAccess()` as `pAgent`
- `UserToolAccess.hasAccess()` as `pGateway`
- `CredentialInjectionPolicy.selectCredential()` as `pGateway`

---

## Security Boundaries

### Separation of Concerns

| Party | Can Manage | Can Execute | Can View |
|-------|-----------|-------------|----------|
| **pAdmin** | ✅ Policies, Users, Services | ❌ (admin operations only) | ✅ Everything |
| **pAgent/pGateway** | ❌ No management | ✅ Runtime checks | ✅ Operational data |
| **pUser** | ❌ No management | ❌ No gateway ops | ✅ Own access only |
| **pAuditor** | ❌ No management | ❌ No execution | ✅ Read-only audit |

### Why This Matters

1. **Principle of Least Privilege**: Gateway can't enable/disable tools, only check them
2. **User Privacy**: Users can view their own access, not others'
3. **Auditability**: Clear separation between management and runtime operations
4. **Security**: Compromised gateway ≠ compromised policy management

---

## Migration Notes

### Existing Protocol Instances

If you have existing protocol instances with incorrect party assignments (e.g., `@parties: {}`), they will have inherited ALL claims from the creator's JWT.

**Symptoms**:
- `pAgent` has both `admin` and `agent` claims
- All parties have the same claims

**Fix**:
1. Delete existing protocol instances (via NPL Inspector or API)
2. Re-run NPL bootstrap (TUI "Sync NPL") to recreate with correct parties
3. Verify in NPL Inspector that parties have distinct claims

### When to Delete NPL Database

Full reset needed if:
- Party assignments are fundamentally broken
- Testing new party structure
- Development environment only (⚠️ **NEVER in production**)

```bash
cd /Users/juerg/development/noumena-mcp-gateway/deployments
docker compose down -v npl-db
docker compose up -d
# Then re-run NPL bootstrap via TUI
```

---

## Testing Party Assignments

### Via NPL Inspector

1. Open [http://localhost:8080](http://localhost:8080)
2. Login with `admin` / `admin`
3. Navigate to protocol instance (e.g., ToolPolicy for "gemini")
4. Check **Parties** section:
   - `pAdmin` should show `role: admin`
   - `pAgent` should show `role: agent` (NOT `admin`)

### Via NPL API

```bash
# Get ToolPolicy instance
curl -H "Authorization: Bearer $TOKEN" \
  http://localhost:12000/npl/services/ToolPolicy/<instance-id>

# Check parties section:
{
  "@id": "...",
  "@parties": {
    "pAdmin": { "role": "admin" },
    "pAgent": { "role": "agent" }
  },
  ...
}
```

---

## Future: Additional Roles

As the system evolves, you may add:

- **`operator`**: Read-only operational monitoring (metrics, health checks)
- **`auditor`**: Security audit trail access (already defined in CredentialInjectionPolicy)
- **`developer`**: Dev environment testing (elevated access in dev only)

**Guidelines for new roles**:
1. Define in Keycloak as realm roles
2. Add to `role` claim mapper
3. Document in this file
4. Update protocol `@api` declarations if needed

---

## Quick Reference

```typescript
// Admin-only protocols
ServiceRegistry:    { "pAdmin": { "role": "admin" } }
UserRegistry:       { "pAdmin": { "role": "admin" } }

// Admin + Gateway protocols
ToolPolicy:         { "pAdmin": { "role": "admin" }, "pAgent": { "role": "agent" } }

// Admin + User + Gateway protocols  
UserToolAccess:     { "pAdmin": { "role": "admin" }, 
                      "pUser": { "userId": "<user>" },    // ← USER-SPECIFIC
                      "pGateway": { "role": "agent" } }

// Admin + Gateway + Auditor protocols
CredentialPolicy:   { "pAdmin": { "role": "admin" }, 
                      "pGateway": { "role": "agent" },
                      "pAuditor": { "role": "auditor" } }
```

---

## References

- [NPL Party Documentation](https://docs.noumenadigital.com/npl/parties/)
- [MCP Gateway Architecture](./ARCHITECTURE.md)
- [Keycloak Provisioning](../keycloak-provisioning/terraform.tf)
