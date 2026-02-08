# Credential Provisioning Models: NPL vs Direct Vault

## The Core Question

**Should NPL manage credential provisioning, or just policy?**

There are several approaches, each with different trade-offs around security, flexibility, and NPL's role.

---

## Option A: NPL as Credential Orchestrator (Full NPL Control) ⭐

**NPL manages the entire credential lifecycle through protocols.**

```npl
/**
 * NPL Protocol: CredentialRegistry
 * Stores credential metadata and vault paths.
 * Admin registers credentials via NPL, not directly in Vault.
 */
@api
protocol[admin, system] CredentialRegistry() {
    // Maps: (tenant, user, service, credentialName) -> CredentialMetadata
    private var credentials: Map<Text, CredentialMetadata> = mapOf();
    
    /**
     * Admin provisions a new credential.
     * This creates the mapping in NPL and optionally in Vault.
     */
    @api
    permission[admin] provisionCredential(
        tenantId: Text,
        userId: Text,
        serviceName: Text,
        credentialName: Text,
        vaultPath: Text,
        injectionMapping: Map<Text, Text>,
        scope: CredentialScope
    ) returns Text {
        var credId = generateCredentialId(tenantId, userId, serviceName, credentialName);
        
        var metadata = CredentialMetadata(
            id = credId,
            tenantId = tenantId,
            userId = userId,
            serviceName = serviceName,
            credentialName = credentialName,
            vaultPath = vaultPath,
            injectionMapping = injectionMapping,
            scope = scope,
            status = "active",
            createdAt = now()
        );
        
        credentials = credentials.with(credId, metadata);
        
        // Optionally: Trigger vault provisioning via connector
        // connector.http.post(vaultUrl, {...})
        
        return credId;
    };
    
    /**
     * System (Vault Sidecar) looks up credential metadata.
     */
    @api
    permission[system] getCredentialMetadata(
        tenantId: Text,
        userId: Text,
        serviceName: Text,
        credentialName: Text
    ) returns CredentialMetadata {
        var credId = generateCredentialId(tenantId, userId, serviceName, credentialName);
        var maybeCred = credentials.getOrNone(credId);
        require(maybeCred.isPresent(), "Credential not found");
        return maybeCred.getOrFail();
    };
    
    /**
     * Admin revokes a credential (e.g., user leaves company).
     */
    @api
    permission[admin] revokeCredential(credId: Text) {
        var maybeCred = credentials.getOrNone(credId);
        if (maybeCred.isPresent()) {
            var cred = maybeCred.getOrFail();
            var updated = CredentialMetadata(
                id = cred.id,
                tenantId = cred.tenantId,
                userId = cred.userId,
                serviceName = cred.serviceName,
                credentialName = cred.credentialName,
                vaultPath = cred.vaultPath,
                injectionMapping = cred.injectionMapping,
                scope = cred.scope,
                status = "revoked",
                createdAt = cred.createdAt
            );
            credentials = credentials.with(credId, updated);
        };
    };
};

struct CredentialMetadata {
    id: Text,
    tenantId: Text,
    userId: Text,
    serviceName: Text,
    credentialName: Text,
    vaultPath: Text,
    injectionMapping: Map<Text, Text>,
    scope: CredentialScope,
    status: Text,  // "active", "revoked", "expired"
    createdAt: DateTime
};

struct CredentialScope {
    operations: Set<Text>,      // e.g., ["read", "write"] or ["*"]
    repositories: Set<Text>,    // e.g., ["acme/*"] or ["*"]
    validUntil: DateTime?,
    ipWhitelist: Set<Text>?
};
```

### Flow

```
1. Admin → NPL CredentialRegistry:
   provisionCredential(
     tenant: "acme",
     user: "alice", 
     service: "github",
     name: "work_account",
     vaultPath: "tenants/acme/users/alice/github/work",
     injectionMapping: {"token": "GITHUB_TOKEN"},
     scope: {
       operations: ["*"],
       repositories: ["acme/*"],
       validUntil: null
     }
   )
   → Returns credId: "cred-123"

2. Agent → Gateway → NPL CredentialInjectionPolicy:
   selectCredential(service: "github", operation: "create_issue", metadata: {...})
   → Returns: {credentialName: "work_account", ...}

3. Vault Sidecar → NPL CredentialRegistry:
   getCredentialMetadata(tenant: "acme", user: "alice", service: "github", name: "work_account")
   → Returns: {vaultPath: "...", injectionMapping: {...}, scope: {...}}

4. Vault Sidecar validates scope:
   - Check operation in scope.operations
   - Check repo matches scope.repositories
   - Check not expired (validUntil)
   
5. If scope valid → Fetch from Vault, inject, forward
```

### ✅ Pros

**Security:**
- **NPL is source of truth** for what credentials exist
- **Scope enforcement** at NPL level (e.g., read-only credentials)
- **Centralized revocation** - revoke in NPL, immediately effective
- **Audit trail** - every credential provision/access logged in NPL

**Flexibility:**
- **Fine-grained scoping** - credentials limited to specific operations/repos
- **Time-bound credentials** - auto-expire after period
- **IP whitelisting** - only use credentials from certain IPs
- **Dynamic provisioning** - NPL can provision credentials on-demand

**Governance:**
- **Compliance-friendly** - clear audit trail of who has what credentials
- **Policy-driven** - credential access controlled by same NPL policies
- **Multi-tenant isolation** - tenant-specific credential namespaces

### ❌ Cons

**Complexity:**
- **More NPL protocols** to build and maintain
- **NPL becomes critical path** for credential lookups
- **State management** - NPL must persist credential metadata

**Performance:**
- **Extra NPL call** for credential metadata lookup (~10ms)
- **Total latency**: ~80-100ms (NPL policy + NPL credential + Vault)

**Operational:**
- **NPL dependency** - if NPL down, no credential access
- **Migration complexity** - must sync existing Vault creds into NPL

### Verdict: ⭐ **Best for governance-heavy use cases**

Use when:
- **Regulatory compliance** (SOC 2, HIPAA, PCI)
- **Multi-tenant SaaS** with per-user credentials
- **Fine-grained scoping** required (read-only creds, time-limited)
- **Audit requirements** (who accessed what credential when)

---

## Option B: NPL for Policy, Vault for Credentials (Hybrid) ✅

**NPL decides WHICH credential to use, Vault stores the actual secrets.**

```npl
/**
 * NPL Protocol: CredentialInjectionPolicy
 * Maps requests to credential names, but NOT vault paths.
 */
@api
protocol[admin, gateway, system] CredentialInjectionPolicy() {
    // Maps: service -> list of (rule, credentialName)
    private var injectionRules: Map<Text, List<InjectionRule>> = mapOf();
    
    /**
     * Admin defines rule: "For this service+operation, use this credential"
     */
    @api
    permission[admin] addInjectionRule(
        serviceName: Text,
        credentialName: Text,
        condition: Text,  // NPL expression: "operation == 'create_issue'"
        priority: Number
    ) {
        var rule = InjectionRule(
            credentialName = credentialName,
            condition = condition,
            priority = priority
        );
        
        var existingRules = injectionRules.getOrElse(serviceName, listOf<InjectionRule>());
        var updatedRules = existingRules.append(rule).sortBy(r -> r.priority);
        injectionRules = injectionRules.with(serviceName, updatedRules);
    };
    
    /**
     * Gateway asks: "Which credential should I use?"
     * Returns just the credential NAME, not vault path.
     */
    @api
    permission[gateway] selectCredential(
        serviceName: Text,
        operationName: Text,
        requestMetadata: Map<Text, Text>
    ) returns Text {
        var rules = injectionRules.getOrElse(serviceName, listOf<InjectionRule>());
        
        // Evaluate rules in priority order
        var selectedCred = evaluateRules(rules, operationName, requestMetadata);
        
        return selectedCred;  // Just the name: "work_account"
    };
};

struct InjectionRule {
    credentialName: Text,
    condition: Text,
    priority: Number
};
```

**Vault Sidecar has a config file mapping credential names to Vault paths:**

```yaml
# vault-sidecar-config.yaml
credential_mappings:
  github:
    personal_account:
      vault_path: "tenants/{tenant}/users/{user}/github/personal"
      injection_mapping:
        token: "GITHUB_TOKEN"
    
    work_account:
      vault_path: "tenants/{tenant}/users/{user}/github/work"
      injection_mapping:
        token: "GITHUB_TOKEN"
  
  slack:
    prod_workspace:
      vault_path: "tenants/{tenant}/services/slack/prod"
      injection_mapping:
        token: "SLACK_TOKEN"
    
    dev_workspace:
      vault_path: "tenants/{tenant}/services/slack/dev"
      injection_mapping:
        token: "SLACK_TOKEN"
```

### Flow

```
1. Admin → NPL:
   addInjectionRule(
     service: "github",
     credentialName: "work_account",
     condition: "metadata.repo startsWith 'acme/'",
     priority: 10
   )

2. Agent → Gateway → NPL:
   selectCredential(service: "github", operation: "create_issue", metadata: {"repo": "acme/foo"})
   → Returns: "work_account"

3. Gateway → Vault Sidecar:
   POST /forward
   Headers:
     X-Tenant: acme
     X-User: alice
     X-Service: github
     X-Credential: work_account  # Just the name!
   Body: <MCP request>

4. Vault Sidecar:
   - Looks up "github.work_account" in config
   - Gets vault_path template: "tenants/{tenant}/users/{user}/github/work"
   - Interpolates: "tenants/acme/users/alice/github/work"
   - Fetches from Vault
   - Injects via injection_mapping
   - Forwards to upstream
```

### ✅ Pros

**Performance:**
- **No extra NPL call** for credential metadata
- **Total latency**: ~70-90ms (NPL policy + Vault fetch)
- **Simpler NPL protocols** (less state)

**Flexibility:**
- **Easy to add new credentials** - just update Vault + config file
- **No NPL restart** needed for new credential mappings
- **Vault is source of truth** for actual secrets

**Operational:**
- **Standard Vault usage** - no special NPL integration
- **Config file hot-reload** - update mappings without restart
- **Less NPL dependency** - NPL only for policy, not credential metadata

### ❌ Cons

**Security:**
- **No centralized credential registry** - can't query "what credentials exist"
- **No scope enforcement** at NPL level (must implement in Vault Sidecar)
- **Config file security** - credential mappings visible in config

**Governance:**
- **Weaker audit** - no NPL record of credential definitions
- **Manual management** - must keep config in sync with Vault

### Verdict: ✅ **Best for most use cases**

Use when:
- **Performance critical** (minimize NPL calls)
- **Simpler operations** (fewer compliance requirements)
- **Standard Vault usage** preferred
- **Faster iteration** (no NPL protocol changes for new creds)

---

## Option C: Scoped Vault Tokens (Vault-Native)

**Use Vault's native token scoping instead of NPL.**

Each user/service gets a scoped Vault token that can only access specific paths.

```
Agent alice → Gateway → Vault Sidecar
                            ↓
                        Uses alice's scoped Vault token
                            ↓
                        Vault (only allows: secret/data/tenants/acme/users/alice/*)
```

### Flow

```
1. Admin provisions user in Vault:
   vault write auth/userpass/users/alice password=xxx policies=alice-policy

2. Vault policy for alice:
   path "secret/data/tenants/acme/users/alice/*" {
     capabilities = ["read"]
   }

3. Vault Sidecar:
   - Receives request from alice
   - Uses alice's Vault token (from JWT or session)
   - Fetches from Vault (scoped by policy)
   - If alice tries to access bob's creds → Vault denies
```

### ✅ Pros

**Security:**
- **Vault-native security** - battle-tested, mature
- **No credential leakage** - alice can't access bob's creds
- **Minimal trust** - Vault enforces access, not our code

**Simplicity:**
- **No NPL protocols needed** for credential management
- **Standard Vault patterns** - well-documented
- **Less code to maintain**

### ❌ Cons

**Flexibility:**
- **No dynamic selection** - can't say "use work creds for acme repos"
- **Vault policy language** - less flexible than NPL
- **Hard to implement conditional logic** (repo-based selection)

**Multi-tenancy:**
- **Token management complexity** - must manage tokens per user
- **No service-level credentials** - only user-level

### Verdict: ⚠️ **Good for user-scoped credentials only**

Use when:
- **User-owned credentials** (personal GitHub tokens)
- **No conditional selection** needed
- **Want minimal custom code**

Not suitable for:
- **Service-level credentials** (shared prod Slack token)
- **Conditional credential selection** (different creds per repo)

---

## Recommendation: Option B (NPL Policy + Vault Creds) ✅

### Why Option B Wins for Most Cases

**Balance of concerns:**
1. **NPL handles policy** (its strength) - WHICH credential to use
2. **Vault handles secrets** (its strength) - WHERE to store them
3. **Config file bridges them** - lightweight, flexible mapping

**Typical flow:**
```
Agent → Gateway → NPL Sidecar (policy) → Vault Sidecar (inject) → Upstream
                      ↓                        ↓
                "work_account"          Fetch from Vault + inject
```

### When to Use Option A (Full NPL Control)

Upgrade to Option A if you need:
- **Fine-grained scoping** (read-only creds, time-limited, IP-restricted)
- **Centralized credential registry** (compliance requirement)
- **Dynamic provisioning** (provision credentials via NPL API)
- **Advanced audit** (who provisioned what credential when)

### When to Use Option C (Scoped Vault Tokens)

Use Option C for:
- **User-owned credentials only** (no shared service creds)
- **Simplest possible setup** (no conditional selection)
- **Vault-native security** (trust Vault, not custom code)

---

## Sidecar Lifecycle: "What are they doing in the meantime?"

### The Three Processes

```
┌─────────────────────────────────────────────────────────────┐
│ Pod: mcp-gateway                                            │
│                                                             │
│  ┌──────────────────────────────────────────────────────┐   │
│  │ Container 1: Gateway (always active)                 │   │
│  │ - HTTP server on :8080                               │   │
│  │ - WebSocket server on :8080/mcp/ws                   │   │
│  │ - Handles ALL agent connections                      │   │
│  │ - Always processing requests                         │   │
│  └──────────────────────────────────────────────────────┘   │
│                                                             │
│  ┌──────────────────────────────────────────────────────┐   │
│  │ Container 2: NPL Sidecar (reactive)                  │   │
│  │ - HTTP server on localhost:9001                      │   │
│  │ - Idle until Gateway calls /check-policy             │   │
│  │ - Calls NPL Engine when needed                       │   │
│  │ - Caches policy results (60s TTL)                    │   │
│  └──────────────────────────────────────────────────────┘   │
│                                                             │
│  ┌──────────────────────────────────────────────────────┐   │
│  │ Container 3: Vault Sidecar (reactive)                │   │
│  │ - HTTP/WebSocket server on localhost:9002            │   │
│  │ - Idle until Gateway calls /forward                  │   │
│  │ - Fetches from Vault when needed                     │   │
│  │ - Caches credentials (5min TTL)                      │   │
│  │ - Spawns/manages upstream MCP connections            │   │
│  └──────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────┘
```

### Detailed Lifecycle

**Gateway (Active)**
```
[00:00] Start HTTP server on :8080
[00:01] Start WebSocket server on :8080/mcp/ws
[00:02] Initialize NPL Sidecar client (localhost:9001)
[00:03] Initialize Vault Sidecar client (localhost:9002)
[00:04] Ready for connections

[Request comes in]
[00:10] Receive MCP request from agent
[00:11] Extract service, operation, metadata
[00:12] Call NPL Sidecar: POST localhost:9001/check-policy
[00:22] Receive policy decision + credential name
[00:23] Forward to Vault Sidecar: POST localhost:9002/forward
[00:93] Receive response from Vault Sidecar
[00:94] Stream back to agent

[Between requests]
[00:95-01:00] Idle, waiting for next request
```

**NPL Sidecar (Reactive)**
```
[00:00] Start HTTP server on localhost:9001
[00:01] Connect to NPL Engine
[00:02] Initialize policy cache (empty)
[00:03] Ready for requests

[Gateway calls /check-policy]
[00:12] Receive policy check request
[00:13] Check cache for (service, operation, metadata) → miss
[00:14] Call NPL Engine: ToolExecutionPolicy.checkAndApprove()
[00:19] Call NPL Engine: CredentialInjectionPolicy.selectCredential()
[00:21] Cache result (TTL 60s)
[00:22] Return {allowed: true, credential: "work_account"}

[Between requests]
[00:23-01:00] Idle, waiting for next request
[01:00] Background: Clean up expired cache entries
```

**Vault Sidecar (Reactive)**
```
[00:00] Start HTTP/WebSocket proxy on localhost:9002
[00:01] Connect to Vault (authenticate)
[00:02] Load credential mapping config
[00:03] Initialize credential cache (empty)
[00:04] Ready for requests

[Gateway calls /forward]
[00:23] Receive MCP request + credential name "work_account"
[00:24] Extract tenant/user from headers
[00:25] Look up vault_path from config
[00:26] Check cache for credential → miss
[00:27] Fetch from Vault: GET /v1/secret/data/tenants/acme/users/alice/github/work
[00:77] Receive credential {token: "ghp_xxx"}
[00:78] Cache credential (TTL 5min)
[00:79] Inject into upstream call (env var: GITHUB_TOKEN=ghp_xxx)
[00:80] Forward MCP request to upstream (spawn process or HTTP)
[00:90] Receive response from upstream
[00:91] Stream back to Gateway (passthrough)
[00:92] Close connection

[Between requests]
[00:93-01:00] Idle, waiting for next request
[01:00] Background: Clean up idle upstream connections
[02:00] Background: Rotate cached credentials if needed
```

### Resource Usage

**Gateway:**
- CPU: Active (handling agent connections)
- Memory: ~200MB base + per-connection overhead
- Network: Active (agent traffic)

**NPL Sidecar:**
- CPU: Mostly idle (~1-2% baseline)
- Memory: ~50MB (small cache)
- Network: Burst when policy check needed

**Vault Sidecar:**
- CPU: Idle between requests, active during forwarding
- Memory: ~100MB (credential cache + upstream connections)
- Network: Burst to Vault + upstream

### Scaling

**When do sidecars become active?**

- **NPL Sidecar**: Only when Gateway needs policy decision (~70% of requests, others cached)
- **Vault Sidecar**: On EVERY request (but may use cached credentials)

**Bottlenecks:**

- **Gateway**: Agent connections (thousands)
- **NPL Sidecar**: NPL Engine calls (hundreds/sec)
- **Vault Sidecar**: Upstream MCP connections (hundreds)

**Scale independently:**

```yaml
# High agent traffic → scale Gateway
replicas: 10 gateway pods

# Many policy checks → scale NPL Engine
replicas: 5 NPL Engine instances

# Many upstream calls → scale Vault Sidecars
# (Each Gateway pod has own sidecar, auto-scales with Gateway)
```

---

## Summary & Decision Points

### Credential Provisioning: Choose Option B (Hybrid)

**NPL for policy, Vault for secrets, config file for mapping**

Unless you need:
- Advanced scoping → Option A
- User-only creds → Option C

### Implementation Next Steps

1. **NPL Protocol**: `CredentialInjectionPolicy` (just credential NAME selection)
2. **Vault Sidecar**: Config file mapping names → vault paths
3. **NPL Sidecar**: Policy check endpoint
4. **Gateway**: Client for both sidecars

Want me to start building?
