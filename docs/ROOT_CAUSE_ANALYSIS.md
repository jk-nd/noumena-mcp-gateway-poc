# Root Cause Analysis: Missing ToolPolicy Creation

## The Bug You Discovered

When enabling tools via TUI after adding a service, tools appeared enabled in `services.yaml` but showed **0 instances** in NPL Inspector's ToolPolicy view.

## Investigation Chain

### 1. Initial Symptom
- **User Action**: Enable DuckDuckGo tools via TUI
- **TUI Response**: "✓ All tools enabled"
- **NPL Reality**: ToolPolicy shows 0 instances
- **Result**: Inconsistent state (YAML ≠ NPL)

### 2. First Bug Found (Fixed)
**TUI was showing success even when NPL sync failed**

```typescript
❌ BROKEN:
grantAllToolsToUser(userId, service);  // ← Write to YAML
try {
  await syncToNpl();
  showSuccess("✓ Granted");
} catch {
  showSuccess("✓ Granted");  // ← Still shows success!
}
```

**Fix Applied**: Atomic operations with rollback
- Backup state before changes
- If NPL fails → rollback YAML and show error
- Never show success unless NPL confirms

### 3. Root Cause Found (Fixed)
**syncServiceWithNpl() was incomplete**

The critical missing piece: **ToolPolicy was never created for runtime-added services**.

## The Architecture

### NPL Protocol Hierarchy
```
ServiceRegistry (which services exist)
    ↓
ToolPolicy (per service - manages tools)
    ↓
UserToolAccess (per user - tool permissions)
```

### Bootstrap Flow (Initial Setup)
```typescript
bootstrapNpl() {
  1. Create ServiceRegistry
  2. For each service in services.yaml:
     - Enable in ServiceRegistry ✓
     - Create ToolPolicy ✓
     - Enable tools in policy ✓
}
```

### Runtime Flow (Adding Services via TUI)
```typescript
// BEFORE (BROKEN):
syncServiceWithNpl(serviceName, true) {
  enableServiceInNpl(serviceName);  // ✓ Updates ServiceRegistry
  // ❌ MISSING: createToolPolicy()
}

// AFTER (FIXED):
syncServiceWithNpl(serviceName, true) {
  enableServiceInNpl(serviceName);  // ✓ Updates ServiceRegistry
  
  // ✓ NEW: Create ToolPolicy if missing
  let policyId = await findToolPolicyForService(token, serviceName);
  if (!policyId) {
    policyId = await createToolPolicy(token, serviceName);
  }
}
```

## Why It Failed

### Scenario: Add DuckDuckGo via TUI

**Step 1: Add Service**
```
TUI → addService("duckduckgo") → services.yaml
TUI → syncServiceWithNpl("duckduckgo", true)
  → ServiceRegistry.enableService("duckduckgo") ✓
  → [MISSING] ToolPolicy creation ✗
```

**Step 2: Enable Tools**
```
TUI → setToolEnabled("duckduckgo", "search", true) → services.yaml
TUI → reloadGatewayConfig()
  → Gateway reloads YAML ✓
  → Gateway queries NPL ✓
TUI → grantToolInNpl(userId, "duckduckgo", "search")
  → Needs ToolPolicy for "duckduckgo"
  → ToolPolicy doesn't exist! ✗
  → NPL call fails silently
```

**Result**: YAML says tools enabled, NPL has no ToolPolicy = inconsistent state

## The Fix

### Code Change
```typescript
export async function syncServiceWithNpl(serviceName: string, enabled: boolean) {
  if (enabled) {
    await enableServiceInNpl(serviceName);
    
    // ✅ FIX: Create ToolPolicy if it doesn't exist
    const token = await getKeycloakToken();
    let policyId = await findToolPolicyForService(token, serviceName);
    
    if (!policyId) {
      policyId = await createToolPolicy(token, serviceName);
      console.log(`Created ToolPolicy for ${serviceName}`);
    }
  } else {
    await disableServiceInNpl(serviceName);
  }
}
```

### Now When You Add DuckDuckGo

**Step 1: Add Service**
```
TUI → syncServiceWithNpl("duckduckgo", true)
  → ServiceRegistry.enableService("duckduckgo") ✓
  → createToolPolicy("duckduckgo") ✓ [NEW!]
  → ToolPolicy instance created with ID
```

**Step 2: Enable Tools**
```
TUI → grantToolInNpl(userId, "duckduckgo", "search")
  → Finds ToolPolicy for "duckduckgo" ✓
  → ToolPolicy.enableTool("search") ✓
  → NPL Inspector shows enabled tool ✓
```

**Result**: YAML and NPL stay in sync

## Testing the Fix

### Before Fix
```bash
# Add service
TUI → Add DuckDuckGo
TUI → Enable service

# Check NPL
NPL Inspector → ToolPolicy → 0 instances ✗

# Try to enable tools
TUI → Enable tools → "✓ Success" (fake)
NPL Inspector → ToolPolicy → Still 0 instances ✗
```

### After Fix
```bash
# Add service
TUI → Add DuckDuckGo
TUI → Enable service

# Check NPL
NPL Inspector → ToolPolicy → 1 instance (duckduckgo) ✓

# Enable tools
TUI → Enable tools → "✓ Success" (real)
NPL Inspector → ToolPolicy → Shows enabled tools ✓
```

## Why This Matters

### V3 Architecture Principle
> NPL is the source of truth for runtime policy decisions

When a user calls `duckduckgo.search`:
1. Gateway checks **NPL ToolPolicy** (not YAML)
2. If ToolPolicy doesn't exist → Access denied
3. If ToolPolicy exists but tool not enabled → Access denied
4. If ToolPolicy exists and tool enabled → Allow

**Without ToolPolicy, the service is effectively dead** even if YAML says it's enabled.

## Lessons Learned

### 1. Architecture Layering
```
Bootstrap (YAML → NPL)  ← One-time initial sync
Runtime   (NPL only)    ← Source of truth
Expert    (YAML → NPL)  ← Manual import only
```

### 2. Atomic Operations
Every operation that modifies state must be atomic with rollback:
- Backup state
- Apply changes
- Sync to NPL
- If sync fails: rollback and error
- If sync succeeds: commit and success

### 3. Complete Sync Functions
`syncServiceWithNpl()` must replicate what `bootstrapNpl()` does:
- ServiceRegistry update ✓
- ToolPolicy creation ✓
- Tool enablement ✓

## Related Fixes

1. ✅ Tool enable/disable atomic with rollback
2. ✅ Service enable/disable atomic with rollback
3. ✅ ToolPolicy creation in syncServiceWithNpl
4. ⚠️ TODO: User grant/revoke tools (individual) atomic fix
5. ⚠️ TODO: User registration/deregistration atomic fix

## Next Steps

With this fix, the system should work correctly when:
1. Adding services via TUI
2. Enabling services
3. Enabling tools
4. Granting tools to users

NPL Inspector should always reflect the actual state.
