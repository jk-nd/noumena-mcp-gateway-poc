# NPL-Based Credential Injection - Evolution V2

## Overview

This document describes the evolution of the Noumena MCP Gateway to support **NPL-controlled credential management and injection**. The goal is to make credential selection and injection fully policy-driven, enabling fine-grained control over which credentials are used for which requests.

## Current State (V1)

### Static Credential Injection

Currently, credentials are injected using a simple static mapping:

```kotlin
// StdioMcpClient.kt lines 182-186
credentials.accessToken?.let { env["MCP_OAUTH_TOKEN"] = it }
credentials.apiKey?.let { env["MCP_API_KEY"] = it }
credentials.username?.let { env["MCP_USERNAME"] = it }
credentials.password?.let { env["MCP_PASSWORD"] = it }
```

### Limitations

1. **No request-specific credential selection** - Always uses the same credentials for a service
2. **Static environment variable names** - Can't adapt to different MCP server requirements
3. **Single credential per service** - Can't select between multiple credential sets
4. **No policy-driven decisions** - Credential injection logic is hardcoded

## Evolution Goals (V2)

### Core Principles

1. **Gateway intercepts and authorizes ALL traffic with NPL**
2. **NPL rules determine credential injection** - "For this request, use this credential"
3. **True streaming MCP** - Bidirectional streaming maintained
4. **Zero trust** - Only Executor has Vault access

### Target: OpenClaw Contribution

This evolution is designed to be contributed to OpenClaw or similar open-source MCP gateway projects, providing a reference implementation for policy-driven credential management.

## Architecture V2

### NPL Protocol: CredentialInjectionPolicy

```npl
/**
 * Controls which credentials to inject for each request.
 * Supports multiple credential sets per service and dynamic selection.
 */
@api
protocol[admin, gateway, executor] CredentialInjectionPolicy() {
    // Maps service -> (credential_name -> injection rules)
    private var injectionRules: Map<Text, Map<Text, InjectionRule>> = mapOf();
    
    // Maps service -> list of available credential names
    private var serviceCredentials: Map<Text, Set<Text>> = mapOf();
    
    /**
     * Admin registers a credential set for a service.
     * @param serviceName Service identifier (e.g., "github", "slack")
     * @param credentialName Name for this credential set (e.g., "personal", "work")
     * @param vaultPath Path in Vault (e.g., "tenants/{tenant}/users/{user}/github/personal")
     * @param injectionMapping How to inject (e.g., {"token": "GITHUB_TOKEN", "key": "API_KEY"})
     */
    @api
    permission[admin] registerCredential(
        serviceName: Text,
        credentialName: Text,
        vaultPath: Text,
        injectionMapping: Map<Text, Text>
    );
    
    /**
     * Admin defines a rule for when to use a credential.
     * @param serviceName Service identifier
     * @param credentialName Which credential set to use
     * @param condition NPL expression (e.g., "operation == 'create_issue'")
     * @param priority Higher priority rules are evaluated first
     */
    @api
    permission[admin] addInjectionRule(
        serviceName: Text,
        credentialName: Text,
        condition: Text,
        priority: Number
    );
    
    /**
     * Gateway asks which credential to use for a request.
     * Returns credential name and injection mapping.
     */
    @api
    permission[gateway] selectCredential(
        serviceName: Text,
        operationName: Text,
        requestMetadata: Text
    ) returns CredentialSelection;
    
    /**
     * Executor reports successful credential usage for audit.
     */
    @api
    permission[executor] recordCredentialUsage(
        requestId: Text,
        serviceName: Text,
        credentialName: Text,
        wasSuccessful: Boolean
    );
}

struct InjectionRule {
    credentialName: Text,
    condition: Text,
    priority: Number
};

struct CredentialSelection {
    credentialName: Text,
    vaultPath: Text,
    injectionMapping: Map<Text, Text>  // Vault field -> Env var name
};
```

### Enhanced Flow

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│  Request Flow with NPL Credential Selection                                     │
├─────────────────────────────────────────────────────────────────────────────────┤
│                                                                                 │
│  1. Agent → Gateway                                                             │
│     POST /mcp { service: "github", operation: "create_issue", ... }             │
│                                                                                 │
│  2. Gateway → NPL [ToolExecutionPolicy]                                         │
│     checkAndApprove(service, operation, metadata) → requestId                   │
│                                                                                 │
│  3. Gateway → NPL [CredentialInjectionPolicy] ⭐ NEW                            │
│     selectCredential(service, operation, metadata) → CredentialSelection        │
│     Returns: {                                                                  │
│       credentialName: "work_account",                                           │
│       vaultPath: "tenants/acme/users/alice/github/work",                        │
│       injectionMapping: {                                                       │
│         "token": "GITHUB_TOKEN",                                                │
│         "api_key": "GITHUB_API_KEY"                                             │
│       }                                                                         │
│     }                                                                           │
│                                                                                 │
│  4. Gateway → RabbitMQ                                                          │
│     Publish ExecutionRequest with credentialSelection                           │
│                                                                                 │
│  5. Executor ← RabbitMQ                                                         │
│     Receive ExecutionRequest                                                    │
│                                                                                 │
│  6. Executor → Vault ⭐ DYNAMIC PATH                                            │
│     GET /v1/secret/data/tenants/acme/users/alice/github/work                    │
│     Returns: { token: "ghp_xxx", api_key: "xxx" }                              │
│                                                                                 │
│  7. Executor → MCP Process ⭐ DYNAMIC INJECTION                                 │
│     env[GITHUB_TOKEN] = "ghp_xxx"                                               │
│     env[GITHUB_API_KEY] = "xxx"                                                 │
│     Spawn MCP server with injected credentials                                  │
│                                                                                 │
│  8. Executor → NPL [CredentialInjectionPolicy]                                  │
│     recordCredentialUsage(requestId, service, credentialName, success)          │
│                                                                                 │
└─────────────────────────────────────────────────────────────────────────────────┘
```

## Implementation Plan

### Phase 1: NPL Protocol Definition

**Files to Create:**
- `npl/src/main/npl-1.0/policies/credential_injection_policy.npl` - New NPL protocol
- `npl/src/main/npl-1.0/common/credential_types.npl` - Shared credential types

**Key Features:**
- CredentialInjectionPolicy protocol
- Rule-based credential selection
- Audit trail for credential usage

### Phase 2: Shared Models

**Files to Modify:**
- `shared/src/main/kotlin/io/noumena/mcp/shared/models/ExecuteRequest.kt`
  - Add `credentialSelection: CredentialSelection?` field

**Files to Create:**
- `shared/src/main/kotlin/io/noumena/mcp/shared/models/CredentialModels.kt`
  - `CredentialSelection` data class
  - `InjectionMapping` data class

### Phase 3: Gateway Enhancement

**Files to Modify:**
- `gateway/src/main/kotlin/io/noumena/mcp/gateway/policy/NplClient.kt`
  - Add `selectCredential()` method
  - Call `CredentialInjectionPolicy.selectCredential()` after approval

- `gateway/src/main/kotlin/io/noumena/mcp/gateway/messaging/ExecutorPublisher.kt`
  - Include credential selection in published message

### Phase 4: Executor Enhancement

**Files to Modify:**
- `executor/src/main/kotlin/io/noumena/mcp/executor/secrets/VaultClient.kt`
  - Add `getCredentialsByPath(vaultPath: String)` method
  - Support dynamic Vault paths
  - Return Map<String, String> for flexible field mapping

- `executor/src/main/kotlin/io/noumena/mcp/executor/upstream/StdioMcpClient.kt`
  - Replace static credential injection with dynamic mapping
  - Use `credentialSelection.injectionMapping` to determine env vars

- `executor/src/main/kotlin/io/noumena/mcp/executor/upstream/UpstreamRouter.kt`
  - Pass `credentialSelection` to upstream clients
  - Use dynamic Vault paths

- `executor/src/main/kotlin/io/noumena/mcp/executor/handler/ExecutionHandler.kt`
  - Report credential usage to NPL after execution

### Phase 5: Configuration & Migration

**Files to Modify:**
- `configs/services.yaml`
  - Add credential configuration section
  - Example credential rules

**Files to Create:**
- `configs/credentials.yaml` - Credential injection rules
- `docs/CREDENTIAL_MIGRATION.md` - Migration guide from V1 to V2

### Phase 6: Admin Tooling (TUI Enhancement)

**Files to Modify:**
- `tui/src/cli.ts`
  - Add "Credential Management" menu
  - Register credentials
  - Define injection rules

**Files to Create:**
- `tui/src/lib/credential-client.ts` - NPL credential management client

## Example Use Cases

### Use Case 1: Multiple GitHub Accounts

```yaml
# Admin configures two GitHub credential sets
credentials:
  github:
    - name: "personal"
      vaultPath: "tenants/{tenant}/users/{user}/github/personal"
      injectionMapping:
        token: "GITHUB_TOKEN"
    
    - name: "work"
      vaultPath: "tenants/{tenant}/users/{user}/github/work"
      injectionMapping:
        token: "GITHUB_TOKEN"

# Injection rules
rules:
  - service: "github"
    credential: "work"
    condition: "metadata.repo_owner == 'acme-corp'"
    priority: 10
  
  - service: "github"
    credential: "personal"
    condition: "true"  # Default
    priority: 1
```

**Behavior:**
- Requests to `acme-corp` repos → use work account
- All other GitHub requests → use personal account

### Use Case 2: Environment-Specific Credentials

```yaml
credentials:
  slack:
    - name: "prod"
      vaultPath: "tenants/{tenant}/services/slack/prod"
      injectionMapping:
        token: "SLACK_TOKEN"
    
    - name: "dev"
      vaultPath: "tenants/{tenant}/services/slack/dev"
      injectionMapping:
        token: "SLACK_TOKEN"

rules:
  - service: "slack"
    credential: "prod"
    condition: "metadata.channel == '#alerts'"
    priority: 10
  
  - service: "slack"
    credential: "dev"
    condition: "true"
    priority: 1
```

### Use Case 3: Role-Based Credentials

```yaml
credentials:
  database:
    - name: "admin"
      vaultPath: "tenants/{tenant}/db/admin"
      injectionMapping:
        username: "DB_USER"
        password: "DB_PASS"
    
    - name: "readonly"
      vaultPath: "tenants/{tenant}/db/readonly"
      injectionMapping:
        username: "DB_USER"
        password: "DB_PASS"

rules:
  - service: "database"
    credential: "admin"
    condition: "operation in ['create_table', 'drop_table', 'update']"
    priority: 10
  
  - service: "database"
    credential: "readonly"
    condition: "true"
    priority: 1
```

## Security Enhancements

### Defense in Depth

1. **NPL Policy Gate** - Credentials only selected after ToolExecutionPolicy approval
2. **Principle of Least Privilege** - Select minimal credentials needed
3. **Audit Trail** - Every credential usage logged via `recordCredentialUsage()`
4. **Dynamic Selection** - Credentials can't be bypassed (selected per-request)

### Compliance Benefits

- **SOC 2** - Complete audit trail of credential usage
- **GDPR** - Separate personal/work credentials
- **PCI DSS** - Separate prod/dev credentials
- **HIPAA** - Role-based credential access

## Migration Path

### Backward Compatibility

V2 maintains backward compatibility with V1:

1. **If no `CredentialInjectionPolicy` configured** → Fall back to V1 static injection
2. **If no matching rule** → Use default credential set
3. **Existing Vault paths** → Continue working

### Migration Steps

1. **Deploy V2 code** (backward compatible)
2. **Bootstrap `CredentialInjectionPolicy`** via TUI
3. **Register credential sets** for services
4. **Define injection rules** per use case
5. **Monitor audit logs** for credential usage
6. **Gradually migrate services** to V2 rules

## Testing Strategy

### Unit Tests

- `CredentialInjectionPolicyTest.kt` - NPL protocol logic
- `VaultClientTest.kt` - Dynamic path resolution
- `StdioMcpClientTest.kt` - Dynamic injection mapping

### Integration Tests

- `CredentialInjectionIntegrationTest.kt` - End-to-end credential flow
- Test multiple credential sets per service
- Test rule evaluation and priority
- Test fallback to defaults

### Demo Scripts

- `deployments/credential_demo.py` - Show multi-credential selection
- `deployments/rule_evaluation_demo.py` - Show rule priority

## Performance Considerations

### Caching

- Cache credential rules in Gateway (refresh every 60s)
- Cache Vault responses in Executor (TTL-based)
- Reuse MCP processes with different credentials (pool by service + credential)

### Latency

- NPL `selectCredential()` call adds ~10ms
- Vault fetch adds ~50ms (cached after first use)
- **Total overhead: ~60ms first request, ~10ms subsequent**

## Future Enhancements (V3+)

1. **Credential Rotation** - Automatic rotation via NPL triggers
2. **Temporary Credentials** - Short-lived tokens for specific operations
3. **Credential Chaining** - Use multiple credentials in sequence
4. **Dynamic Vault Backends** - Support AWS Secrets Manager, Azure Key Vault
5. **Credential Attestation** - Verify credential provenance via NPL

## OpenClaw Contribution

### Why This Matters for OpenClaw

1. **Policy-Driven Security** - Makes OpenClaw enterprise-ready
2. **Multi-Tenancy** - Essential for SaaS MCP gateways
3. **Compliance** - Audit trails for regulated industries
4. **Flexibility** - Support any credential injection pattern

### Contribution Checklist

- [ ] Complete implementation with tests
- [ ] Documentation (this file + migration guide)
- [ ] Demo scripts showing use cases
- [ ] Performance benchmarks
- [ ] Security review
- [ ] OpenClaw RFC submission

## Summary

This evolution transforms the Noumena MCP Gateway from a static credential injector to a **policy-driven, multi-credential, auditable system**. By leveraging NPL for credential selection, we enable:

- **Fine-grained control** over which credentials are used
- **Complete audit trail** of credential usage
- **Zero trust architecture** maintained
- **Backward compatibility** with V1
- **OpenClaw-ready** for contribution

The key innovation is making credential injection a **first-class policy concern** rather than a hardcoded detail.
