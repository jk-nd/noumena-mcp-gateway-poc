# Declarative NPL Sync

## Overview

The NPL sync process now implements a **declarative model** where `services.yaml` is the single source of truth, and NPL state is synchronized to match it exactly.

## Key Principle

> **`services.yaml` defines the desired state. NPL sync makes NPL match that state.**

This means:
- ✅ **Add**: Services/tools/users in YAML → Enabled/Registered in NPL
- 🧹 **Remove**: Services/tools/users NOT in YAML → Disabled/Removed from NPL
- 🔄 **Idempotent**: Safe to run multiple times - NPL will always match YAML

## Separation of Concerns

### Keycloak (Identity)

**Purpose**: "Who are you?"
- Authentication (passwords, 2FA, SSO)
- User accounts and credentials
- Session management

**Management**: 
- Via Terraform (`keycloak-provisioning/`)
- Via Keycloak Admin UI
- **NOT managed by NPL sync!**

### NPL (Authorization)

**Purpose**: "What can you do?"
- Service enable/disable
- Tool-level access control
- Per-user permissions

**Management**:
- Via TUI (interactive management)
- Via `services.yaml` import (declarative sync)
- **Managed by NPL sync!**

---

## What Gets Synced

### 1. Services (NEW: Cleanup Added!)

**Before**:
```
services.yaml: [duckduckgo, slack]
NPL: [duckduckgo, slack, github]  ← github orphaned!
Result: github still enabled in NPL ❌
```

**After**:
```
services.yaml: [duckduckgo, slack]
NPL: [duckduckgo, slack, github] 
Sync: Disables github in NPL ✅
Result: NPL matches YAML
```

**Implementation**: Lines 448-496 in `tui/src/lib/api.ts`

### 2. Tools (NEW: Cleanup Added!)

**Before**:
```
services.yaml duckduckgo: [search]
NPL ToolPolicy: [search, news]  ← news orphaned!
Result: news still enabled in NPL ❌
```

**After**:
```
services.yaml duckduckgo: [search]
NPL ToolPolicy: [search, news]
Sync: Disables news in NPL ✅
Result: NPL matches YAML
```

**Implementation**: Lines 532-572 in `tui/src/lib/api.ts`

### 3. Users (Already Had Cleanup!)

**Before (Already Working)**:
```
services.yaml users: [alice@acme.com, charlie@acme.com]
NPL UserRegistry: [alice@acme.com, charlie@acme.com, peter@acme.com]
Sync: Removes peter@acme.com from NPL ✅
Result: NPL matches YAML
```

**Implementation**: Lines 585-614 in `tui/src/lib/api.ts`

---

## Implementation Details

### New Helper Functions Added

```typescript
// Query NPL state
async function getAllEnabledServicesFromNpl(token, registryId): Promise<Set<string>>
async function getEnabledToolsFromPolicy(token, policyId): Promise<Set<string>>

// Disable operations
async function disableToolInPolicy(token, policyId, toolName): Promise<void>
// disableServiceInNpl already existed
```

### Sync Logic Pattern

For each entity type (services, tools, users):

1. **Enable from YAML**: Add/enable items present in `services.yaml`
2. **Query NPL**: Get current state from NPL
3. **Find orphans**: Items in NPL but not in YAML
4. **Cleanup**: Disable/remove orphaned items

### NPL Protocol Methods Used

**ServiceRegistry** (`npl/src/main/npl-1.0/registry/service_registry.npl`):
- `getEnabledServices()` → Query current state
- `enableService(serviceName)` → Enable
- `disableService(serviceName)` → Disable

**ToolPolicy** (`npl/src/main/npl-1.0/services/tool_policy.npl`):
- `getEnabledTools()` → Query current state
- `enableTool(toolName)` → Enable
- `disableTool(toolName)` → Disable

**UserRegistry** (`npl/src/main/npl-1.0/users/user_registry.npl`):
- `getAllUsers()` → Query current state
- `registerUser(userId)` → Register
- `removeUser(userId)` → Remove

---

## TUI User Experience

### Main Menu

**Sync NPL Button**:
- Shows: ✓ if NPL synced, ⚠ if needs sync
- Hint: "Declarative sync: services.yaml → NPL (DOES NOT touch Keycloak)"

### Sync NPL Screen

Shows clear explanation:
```
  Sync NPL (Declarative)

  📄 services.yaml is the source of truth
  ✅ Enables services/tools/users from YAML
  🧹 Disables services/tools not in YAML
  🧹 Removes users not in YAML

  ⚠  DOES NOT touch Keycloak - only syncs NPL permissions!
```

### User Management Screen

Shows clarification:
```
  User Management

  💡 Keycloak: Identity (who you are) | NPL: Authorization (what you can do)
  • KC = registered in Keycloak | NPL = registered in NPL
```

### User Actions

- **Create user**: "Create in Keycloak + register in NPL + add to services.yaml"
- **Delete user**: "Remove from Keycloak + NPL + services.yaml"

---

## Workflow Examples

### Example 1: Remove a Service

**Goal**: Remove the `github` service completely.

**Steps**:
1. Edit `configs/services.yaml` → Remove `github` entry (or set `enabled: false`)
2. Run TUI → "Sync NPL"
3. Result:
   - ✅ `github` disabled in NPL ServiceRegistry
   - ✅ Gateway stops routing to `github`
   - ❌ Keycloak unchanged (as expected)

### Example 2: Remove a Tool

**Goal**: Remove the `duckduckgo.news` tool.

**Steps**:
1. Edit `configs/services.yaml` → Remove `news` from `duckduckgo.tools` array (or set `enabled: false`)
2. Run TUI → "Sync NPL"
3. Result:
   - ✅ `news` disabled in `duckduckgo` ToolPolicy
   - ✅ Gateway blocks calls to `duckduckgo.news`
   - ❌ Keycloak unchanged (as expected)

### Example 3: Remove a User

**Goal**: Remove user `peter@acme.com` from NPL.

**Steps**:
1. Edit `configs/services.yaml` → Remove `peter@acme.com` from `user_access.users` array
2. Run TUI → "Sync NPL"
3. Result:
   - ✅ `peter@acme.com` removed from NPL UserRegistry
   - ✅ `peter@acme.com`'s UserToolAccess deleted
   - ❌ Keycloak unchanged - peter's account still exists! (You must delete separately if desired)

### Example 4: Bulk Configuration Change

**Goal**: Reconfigure the entire system from a saved YAML file.

**Steps**:
1. Replace `configs/services.yaml` with your saved configuration
2. Run TUI → "Sync NPL"
3. Result:
   - ✅ NPL state matches new YAML exactly
   - ✅ Old services/tools/users not in new YAML are cleaned up
   - ❌ Keycloak unchanged (manage separately)

---

## Testing Checklist

Before deploying to production, verify:

- [ ] **Service Cleanup**: Remove a service from YAML → Verify disabled in NPL
- [ ] **Tool Cleanup**: Remove a tool from YAML → Verify disabled in NPL
- [ ] **User Cleanup**: Remove a user from YAML → Verify removed from NPL
- [ ] **Service Addition**: Add new service to YAML → Verify enabled in NPL
- [ ] **Tool Addition**: Add new tool to YAML → Verify enabled in NPL
- [ ] **User Addition**: Add new user to YAML → Verify registered in NPL
- [ ] **Idempotency**: Run sync twice → Verify no errors, same result
- [ ] **Keycloak Separation**: Verify Keycloak users unaffected by NPL sync

---

## Common Pitfalls

### ❌ Expecting NPL Sync to Create Keycloak Users

**Wrong**:
```yaml
# services.yaml
user_access:
  users:
    - userId: newuser@acme.com  # User doesn't exist in Keycloak yet
```

**What Happens**: NPL sync tries to register the user, but fails or creates inconsistent state.

**Solution**: Create the user in Keycloak first (Terraform or Admin UI), then add to YAML.

### ❌ Forgetting to Remove from YAML

**Wrong**:
```
1. Delete user in Keycloak
2. Don't remove from services.yaml
3. Run "Sync NPL"
```

**What Happens**: User re-registered in NPL (but without Keycloak account = broken state).

**Solution**: Always remove from both Keycloak AND services.yaml.

### ❌ Manual NPL Changes Get Overwritten

**Wrong**:
```
1. Use TUI to enable a service
2. Don't update services.yaml
3. Run "Sync NPL"
```

**What Happens**: Your manual NPL change is reverted (service disabled again).

**Solution**: Either:
- Update services.yaml first, then sync
- Or use TUI for all changes (it updates both YAML and NPL)

---

## Related Documentation

- [NPL as Source of Truth](./NPL_AS_SOURCE_OF_TRUTH.md) - Gateway V3 architecture
- [User Access Control](./USER_ACCESS_CONTROL.md) - Per-user tool permissions
- [TUI User Management Guide](./TUI_ADD_SERVICE_GUIDE.md) - Interactive management
