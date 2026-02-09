# NPL as Source of Truth

## Overview

**Gateway V3 Architecture**: NPL ServiceRegistry is the single source of truth for which services are enabled at runtime.

## Two-Mode Configuration Management

### Mode 1: Interactive Management (TUI)
```
User → TUI UI → NPL API → NPL ServiceRegistry
```

- Enable/disable services via TUI
- Grant/revoke tool access per user
- Manage user permissions
- **Direct NPL mutations** - changes take effect immediately

### Mode 2: Declarative Import (YAML Upload)
```
Admin edits services.yaml
         ↓
Admin runs "Sync NPL" in TUI
         ↓
Bootstrap sync (overwrites NPL)
         ↓
NPL ServiceRegistry updated
```

- Edit `services.yaml` in IDE/editor
- Bulk configuration changes
- **Full NPL reset from YAML** - careful, overwrites all NPL state!

---

## Architecture

### Separation of Concerns

| **Component** | **Purpose** | **Stored Where** | **Mutable?** |
|---------------|-------------|------------------|--------------|
| **Service URLs/Commands** | Static routing info | `services.yaml` | ❌ Read-only at runtime |
| **Tool Schemas** | API contracts | `services.yaml` | ❌ Read-only at runtime |
| **Enabled State** | Runtime config | **NPL** | ✅ Source of truth |
| **User Access** | Runtime permissions | **NPL** | ✅ Source of truth |

### Data Flow

```
┌─────────────────────────────────────────────┐
│ services.yaml (Static Config)              │
│  - Service URLs, commands, tool schemas    │
│  - Admin can import to sync NPL            │
│  - NOT read for enabled state              │
└─────────────────┬───────────────────────────┘
                  │ One-way: Admin imports
                  ↓
┌─────────────────────────────────────────────┐
│ NPL ServiceRegistry (Source of Truth)      │
│  - Enabled services list                   │
│  - ToolPolicy: service-level governance    │
│  - UserToolAccess: per-user permissions    │
└─────────────────┬───────────────────────────┘
                  │ Gateway queries at runtime
                  ↓
┌─────────────────────────────────────────────┐
│ Gateway (Reads from NPL)                    │
│  - getEnabledServices() → Query NPL        │
│  - getService(name) → Query NPL + YAML     │
│  - reload() → Re-query NPL                 │
└─────────────────────────────────────────────┘
```

---

## Implementation Details

### Gateway: NplClient

**New Methods:**
```kotlin
suspend fun getEnabledServices(): Set<String>
suspend fun isServiceEnabled(serviceName: String): Boolean
```

These query the NPL ServiceRegistry's `enabledServices` set.

### Gateway: UpstreamRouter

**Updated Logic:**
```kotlin
fun getEnabledServices(): List<ServiceDefinition> {
    // 1. Query NPL for enabled services (source of truth)
    val enabledInNpl = nplClient.getEnabledServices()
    
    // 2. Load static config from YAML
    val allServices = ServicesConfigLoader.load(configPath).services
    
    // 3. Return intersection: enabled in NPL AND present in YAML
    return allServices.filter { it.name in enabledInNpl }
}
```

### Gateway: Admin Endpoints

**`GET /admin/services`** - Now returns 3 enabled states:
- `enabledInYaml`: What's in the YAML file (informational only)
- `enabledInNpl`: What's in NPL ServiceRegistry (source of truth)
- `enabled`: Runtime enabled state (= `enabledInNpl`)

**`POST /admin/services/reload`** - Now:
1. Clears NPL cache
2. Reloads YAML config
3. Re-queries NPL for enabled services
4. Clears stale upstream sessions

---

## Migration Guide

### Before (V2 - Incorrect ❌)

```kotlin
// Gateway read from YAML enabled flag
ServicesConfigLoader.load(configPath).services.filter { it.enabled }

// TUI modified NPL, but Gateway never saw changes
// → NPL and YAML drifted apart immediately
```

### After (V3 - Correct ✅)

```kotlin
// Gateway queries NPL for enabled state
val enabledInNpl = nplClient.getEnabledServices()
ServicesConfigLoader.load(configPath).services.filter { it.name in enabledInNpl }

// TUI modifies NPL → Gateway sees changes immediately
// → NPL is always the source of truth
```

---

## Operations Guide

### How to Enable a Service

**Option A: Via TUI (Recommended)**
1. Run TUI: `npm start`
2. Navigate to "Manage Services"
3. Enable the service
4. Changes take effect immediately in Gateway

**Option B: Via YAML Import**
1. Edit `configs/services.yaml`
2. Set service's `enabled: true`
3. Run TUI: "Sync NPL"
4. ⚠️ This **overwrites** NPL state!

### How to Check Drift

```bash
# Query Gateway admin endpoint
curl -H "Authorization: Bearer $ADMIN_TOKEN" \
  http://localhost:8080/admin/services | jq '.services[] | {name, enabledInYaml, enabledInNpl}'
```

If `enabledInYaml != enabledInNpl`, there's drift. NPL wins at runtime.

### How to Reload Configuration

```bash
# Reload Gateway (re-query NPL)
curl -X POST -H "Authorization: Bearer $ADMIN_TOKEN" \
  http://localhost:8080/admin/services/reload
```

This does NOT re-read YAML for enabled state - it re-queries NPL!

---

## FAQ

### Q: When should I edit services.yaml?

**A:** Only when:
- Adding a new service (URL, schema, command)
- Changing a service URL or command
- Updating tool schemas
- Doing a bulk configuration import

Do NOT edit `services.yaml` just to enable/disable services - use the TUI!

### Q: What happens if I delete a service from services.yaml?

**A:** 
- NPL still has it in `enabledServices`
- Gateway can't find the service definition → service effectively disabled
- TUI can still see it in NPL
- Use TUI to disable it in NPL, then delete from YAML

### Q: What does "Reload Gateway" actually do?

**A:** V3 behavior:
1. Clears NPL client cache
2. Re-queries NPL ServiceRegistry for enabled services
3. Reloads YAML for static config (URLs, schemas)
4. Clears stale upstream sessions

It does NOT re-read enabled flags from YAML!

### Q: Can I deploy without NPL?

**A:** Yes, for testing. Set `nplClient = null` in UpstreamRouter. Gateway will fall back to YAML `enabled` flags. But this is NOT recommended for production!

---

## Testing

### Unit Tests

Tests pass `nplClient = null` to UpstreamRouter → falls back to YAML enabled flags.

### Integration Tests

Tests should:
1. Bootstrap NPL with test services
2. Query Gateway to verify it reads from NPL
3. Modify NPL enabled state
4. Reload Gateway
5. Verify Gateway reflects NPL changes

---

## TUI Sync Pattern (NPL-First)

The TUI follows an **NPL-first** pattern for all state changes:

```
User action in TUI
    ↓
1. Write to NPL                    ← Source of truth updated first
2. If NPL succeeds → update services.yaml (persistent cache)
3. Reload Gateway (best-effort)
4. If YAML write fails → NPL still correct, gateway can recover on restart
```

If the NPL write fails, `services.yaml` is unchanged and an error is shown.

The only exception is **YAML Import** (Configuration Manager > Import and apply from file), which intentionally overwrites NPL from a YAML file as a bulk admin operation.

---

## Related Files

- `gateway/src/main/kotlin/io/noumena/mcp/gateway/policy/NplClient.kt`
- `gateway/src/main/kotlin/io/noumena/mcp/gateway/upstream/UpstreamRouter.kt`
- `gateway/src/main/kotlin/io/noumena/mcp/gateway/server/McpServerHandler.kt`
- `gateway/src/main/kotlin/io/noumena/mcp/gateway/Application.kt`
- `npl/src/main/npl-1.0/registry/service_registry.npl`
- `tui/src/lib/api.ts` (bootstrapNpl function)
