# NPL as Source of Truth

## Overview

**Envoy + Governance Service Architecture**: NPL ServiceRegistry is the single source of truth for which services are enabled at runtime. Envoy handles MCP protocol routing and TLS termination, while the governance service enforces NPL-based policy checks via Envoy's ext_authz filter.

## Two-Mode Configuration Management

### Mode 1: Interactive Management (TUI)
```
User -> TUI UI -> NPL API -> NPL ServiceRegistry
```

- Enable/disable services via TUI
- Grant/revoke tool access per user
- Manage user permissions
- **Direct NPL mutations** - changes take effect immediately

### Mode 2: Declarative Import (YAML Upload)
```
Admin edits services.yaml
         |
Admin runs "Sync NPL" in TUI
         |
Bootstrap sync (overwrites NPL)
         |
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
| **Service URLs/Commands** | Static routing info | `services.yaml` | No, read-only at runtime |
| **Tool Schemas** | API contracts | `services.yaml` | No, read-only at runtime |
| **Enabled State** | Runtime config | **NPL** | Yes, source of truth |
| **User Access** | Runtime permissions | **NPL** | Yes, source of truth |

### Data Flow

```
+---------------------------------------------+
| services.yaml (Static Config)               |
|  - Service URLs, commands, tool schemas     |
|  - Admin can import to sync NPL             |
|  - NOT read for enabled state               |
+-----------------------+---------------------+
                        | One-way: Admin imports
                        v
+---------------------------------------------+
| NPL ServiceRegistry (Source of Truth)       |
|  - Enabled services list                    |
|  - ToolPolicy: service-level governance     |
|  - UserToolAccess: per-user permissions     |
+---------+---------------------------------+-+
          |                                 |
          | Governance service queries      | TUI reads/writes
          | at runtime (ext_authz)          |
          v                                 v
+----------------------------+   +-----------------+
| Envoy + Governance Service |   | TUI             |
|  - Envoy: MCP routing,    |   |  - Manage NPL   |
|    TLS, load balancing     |   |  - Sync YAML    |
|  - Governance: ext_authz   |   +-----------------+
|    policy checks via NPL   |
|  - RBAC from services.yaml |
+----------------------------+
```

---

## Implementation Details

### Governance Service: NplGovernanceClient

The governance service queries NPL for policy decisions via the `NplGovernanceClient` class. This runs as a standalone Ktor service behind Envoy's ext_authz filter.

**Key methods:**
```kotlin
suspend fun checkPolicy(service: String, operation: String, userId: String): PolicyResponse
```

The policy check flow:
1. Get a gateway service token from Keycloak (role=gateway)
2. Find the `ToolPolicy` instance for the service (service-level check)
3. Call `checkAccess` permission on ToolPolicy
4. Find the `UserToolAccess` instance for the user (user-level check)
5. Call `hasAccess` permission on UserToolAccess
6. Return allow/deny result (both checks must pass)

The client caches ToolPolicy and UserToolAccess instance IDs to avoid repeated lookups. The cache is cleared via the `/admin/clear-cache` endpoint.

### Envoy: Routing and MCP Protocol

Envoy replaces the old gateway application for routing and protocol handling:

- **MCP protocol**: Envoy handles SSE/streamable-HTTP transport natively
- **Routing**: Envoy routes requests to backend supergateway sidecars based on path prefixes
- **ext_authz**: On every `tools/call` request, Envoy forwards the request to the governance service for policy checks
- **TLS termination**: Envoy handles TLS at the edge

The governance service reads `services.yaml` for local RBAC configuration (the `user_access` section), providing a fast sub-millisecond first-pass check before making NPL calls.

### Governance Service: Admin Endpoints

**`POST /admin/clear-cache`** - Clears governance state:
1. Clears the NplGovernanceClient's cached ToolPolicy and UserToolAccess instance IDs
2. Reloads `services.yaml` for updated RBAC configuration

There is no longer a `/admin/services` endpoint on the gateway; Envoy handles routing directly.

---

## Migration Guide

### Before (V2 - Incorrect)

```kotlin
// Gateway read from YAML enabled flag
ServicesConfigLoader.load(configPath).services.filter { it.enabled }

// TUI modified NPL, but Gateway never saw changes
// -> NPL and YAML drifted apart immediately
```

### After (Current - Correct)

```kotlin
// Governance service queries NPL for policy decisions
val policyResult = governanceClient.checkPolicy(serviceName, toolName, userId)

// TUI modifies NPL -> Governance service sees changes immediately
// -> NPL is always the source of truth
```

---

## Operations Guide

### How to Enable a Service

**Option A: Via TUI (Recommended)**
1. Run TUI: `npm start`
2. Navigate to "Manage Services"
3. Enable the service
4. Changes take effect immediately (governance service queries NPL on each request)

**Option B: Via YAML Import**
1. Edit `configs/services.yaml`
2. Set service's `enabled: true`
3. Run TUI: "Sync NPL"
4. Warning: This **overwrites** NPL state!

### How to Clear Cache and Reload

```bash
# Clear governance service cache and reload services.yaml
curl -X POST http://localhost:8090/admin/clear-cache
```

This clears the cached NPL instance IDs and reloads RBAC configuration from `services.yaml`.

### How to Reload Configuration

After editing `services.yaml` (e.g., changing RBAC rules in `user_access`), clear the governance service cache so it picks up the new configuration:

```bash
curl -X POST http://localhost:8090/admin/clear-cache
```

This does NOT change enabled state in NPL -- it only reloads the local RBAC config and clears cached NPL lookups.

---

## FAQ

### Q: When should I edit services.yaml?

**A:** Only when:
- Adding a new service (URL, schema, command)
- Changing a service URL or command
- Updating tool schemas
- Changing RBAC rules (the `user_access` section)
- Doing a bulk configuration import

Do NOT edit `services.yaml` just to enable/disable services -- use the TUI!

### Q: What happens if I delete a service from services.yaml?

**A:**
- NPL still has it in `enabledServices`
- Envoy has no route for the service, so requests cannot reach the backend
- TUI can still see it in NPL
- Use TUI to disable it in NPL, then delete from YAML

### Q: What does clearing the governance cache actually do?

**A:** Current behavior:
1. Clears the NplGovernanceClient's cached ToolPolicy and UserToolAccess instance IDs
2. Reloads `services.yaml` for RBAC configuration
3. Next policy check will re-query NPL for fresh instance IDs

It does NOT change enabled state in NPL.

### Q: Can I deploy without NPL?

**A:** Yes, for development. Set `DEV_MODE=true` on the governance service. It will auto-allow all requests without contacting NPL. But this is NOT recommended for production!

---

## Testing

### Unit Tests

Tests can set `DEV_MODE=true` on the governance service to bypass NPL and auto-allow all requests.

### Integration Tests

Tests should:
1. Bootstrap NPL with test services
2. Send requests through Envoy to verify governance checks
3. Modify NPL enabled state
4. Clear governance cache
5. Verify governance service reflects NPL changes

---

## TUI Sync Pattern (NPL-First)

The TUI follows an **NPL-first** pattern for all state changes:

```
User action in TUI
    |
1. Write to NPL                    <- Source of truth updated first
2. If NPL succeeds -> update services.yaml (persistent cache)
3. Clear governance cache (best-effort)
4. If YAML write fails -> NPL still correct, governance recovers on next query
```

If the NPL write fails, `services.yaml` is unchanged and an error is shown.

The only exception is **YAML Import** (Configuration Manager > Import and apply from file), which intentionally overwrites NPL from a YAML file as a bulk admin operation.

---

## Related Files

- `governance-service/src/main/kotlin/io/noumena/mcp/governance/NplGovernanceClient.kt` - NPL policy client (ext_authz backend)
- `governance-service/src/main/kotlin/io/noumena/mcp/governance/Application.kt` - Governance service entry point and routing
- `shared/src/main/kotlin/io/noumena/mcp/shared/config/ServicesConfig.kt` - Services YAML config model
- `shared/src/main/kotlin/io/noumena/mcp/shared/config/ConfigLoader.kt` - YAML config loader
- `shared/src/main/kotlin/io/noumena/mcp/shared/models/PolicyModels.kt` - PolicyResponse model
- `deployments/envoy/envoy-config.yaml` - Envoy proxy configuration
- `npl/src/main/npl-1.0/registry/service_registry.npl` - NPL ServiceRegistry protocol
- `npl/src/main/npl-1.0/services/tool_policy.npl` - NPL ToolPolicy protocol
- `npl/src/main/npl-1.0/users/user_tool_access.npl` - NPL UserToolAccess protocol
- `configs/services.yaml` - Static service definitions and RBAC config
- `tui/src/lib/api.ts` - TUI API client (bootstrapNpl function)
