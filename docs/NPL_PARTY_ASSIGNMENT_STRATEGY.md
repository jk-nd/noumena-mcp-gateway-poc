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
  "pGateway": { "role": "gateway" }
}

// ❌ WRONG - NPL auto-assigns ALL claims from creator's JWT
"@parties": {}
```

### 2. **Two Party Types**

| Type | Example | JWT Binding | Use Case |
|------|---------|-------------|----------|
| **Role-based** | `pAdmin`, `pGateway`, `pAuditor` | `{"role": "admin"}` | System service roles |
| **Admin-only** | Single `pAdmin` | `{"role": "admin"}` | Management protocols |

**Note**: We removed user-specific parties (`pUser`). Users don't call NPL directly - they interact via TUI/API, which uses admin credentials. The Gateway checks policies on behalf of users using the `userId` parameter, not as a party.

## Protocol-Specific Assignments

### ToolPolicy - `protocol[pAdmin, pGateway]`

**Purpose**: Per-service tool access control (admin manages, gateway enforces)

```typescript
"@parties": {
  "pAdmin": { "role": "admin" },      // Manages policy (enable/disable tools)
  "pGateway": { "role": "gateway" }   // Gateway system service checks at runtime
}
```

**Permissions**:
- `pAdmin`: `enableTool()`, `disableTool()`, `suspendPolicy()`
- `pGateway`: `checkAccess()`, `getEnabledTools()`

---

### UserToolAccess - `protocol[pAdmin, pGateway]`

**Purpose**: Per-tool-user access control (humans and AI treated identically)

```typescript
"@parties": {
  "pAdmin": { "role": "admin" },        // Manages user's tool access
  "pGateway": { "role": "gateway" }     // Gateway enforces at runtime
}
```

**Architectural Note**: We removed the `pUser` party. Tool users (humans like "alice", AI agents like "agent-x") don't call NPL directly. Instead:
- **Admin/TUI** manages access using `pAdmin` permissions
- **Gateway** enforces access using `pGateway` permissions
- The `userId` parameter identifies whose access this protocol instance governs

This design treats humans and AI identically from a governance perspective. User delegation (human authorizing AI) is a separate authorization layer concern.

**Permissions**:
- `pAdmin`: `grantTool()`, `revokeTool()`, `revokeAllAccess()`, `getToolsForService()`, `getAccessList()`
- `pGateway`: `hasAccess()`, `getVaultPath()` (runtime enforcement)

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
  "pGateway": { "role": "gateway" },  // Gateway selects credentials at runtime
  "pAuditor": { "role": "auditor" }   // Future: read-only audit access
}
```

**Permissions**:
- `pAdmin`: `addInjectionRule()`, `removeInjectionRule()`
- `pGateway`: `selectCredential()` (runtime enforcement)
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
| `gateway` | `agent` (username) | Gateway system service for runtime policy enforcement |
| `auditor` | Future | Read-only security auditing |

**Note**: Tool users (humans and AI) don't have a "role" claim in this sense. They're identified by `userId` (e.g., "alice", "agent-x") and have UserToolAccess protocol instances governing their tool access.

### Gateway Service Account

The Gateway authenticates as a **system service** with `role: gateway`:

```json
{
  "sub": "<gateway-service-account-id>",
  "preferred_username": "agent",      // Username (backward compat)
  "email": "agent@acme.com",
  "role": "gateway",                  // ← System service role
  "organization": "acme"
}
```

This allows the Gateway to call:
- `ToolPolicy.checkAccess()` as `pGateway`
- `UserToolAccess.hasAccess()` as `pGateway`
- `CredentialInjectionPolicy.selectCredential()` as `pGateway`

**Note**: The Keycloak username is still "agent" for backward compatibility, but the `role` attribute is "gateway" to match the NPL party model.

---

## Security Boundaries

### Separation of Concerns

| Party | Can Manage | Can Execute | Can View |
|-------|-----------|-------------|----------|
| **pAdmin** | ✅ Policies, Users, Services | ❌ (admin operations only) | ✅ Everything |
| **pGateway** | ❌ No management | ✅ Runtime enforcement | ✅ Operational data |
| **pAuditor** | ❌ No management | ❌ No execution | ✅ Read-only audit |

**Note**: Tool users (humans and AI) don't have direct NPL party representation. They interact via TUI/API (as admin) or have policies enforced by Gateway (as pGateway).

### Why This Matters

1. **Principle of Least Privilege**: Gateway can't enable/disable tools, only enforce them
2. **System vs. User Separation**: Gateway is a system service, not a user
3. **Uniform Governance**: Humans and AI treated identically as "tool users"
4. **Auditability**: Clear separation between management and runtime operations
5. **Security**: Compromised gateway ≠ compromised policy management

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
ToolPolicy:         { "pAdmin": { "role": "admin" }, 
                      "pGateway": { "role": "gateway" } }

UserToolAccess:     { "pAdmin": { "role": "admin" }, 
                      "pGateway": { "role": "gateway" } }
                    // userId param identifies whose access (not a party)

// Admin + Gateway + Auditor protocols
CredentialPolicy:   { "pAdmin": { "role": "admin" }, 
                      "pGateway": { "role": "gateway" },
                      "pAuditor": { "role": "auditor" } }
```

**Key Insight**: Tool users (humans like "alice", AI like "agent-x") are NOT parties. They're identified by the `userId` parameter in UserToolAccess. The Gateway enforces policies on their behalf.

---

## References

- [NPL Party Documentation](https://docs.noumenadigital.com/npl/parties/)
- [MCP Gateway Architecture](./ARCHITECTURE.md)
- [Keycloak Provisioning](../keycloak-provisioning/terraform.tf)
