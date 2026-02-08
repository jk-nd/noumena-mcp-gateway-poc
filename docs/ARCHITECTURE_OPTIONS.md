# Architecture Options: NPL-Driven Credential Injection

## Overview

Evaluating different architectural patterns for intercepting MCP traffic, authorizing with NPL, and injecting credentials from Vault - while maintaining true bidirectional streaming.

## Option 1: Gateway-Integrated (Monolithic)

```
┌─────────────────────────────────────────────────────────────┐
│                     Gateway MCP                             │
│  ┌──────────────────────────────────────────────────────┐   │
│  │ 1. Receive MCP request                               │   │
│  │ 2. Check NPL → selectCredential()                    │   │
│  │ 3. Fetch Vault → getCredentials(path)                │   │
│  │ 4. Inject credentials into upstream call             │   │
│  │ 5. Forward to upstream MCP                           │   │
│  │ 6. Stream response back ─────────────────────────────┼───┤
│  └──────────────────────────────────────────────────────┘   │
│         ↓ NPL Client        ↓ Vault Client                  │
│         ↓                   ↓                                │
└─────────┼───────────────────┼────────────────────────────────┘
          ↓                   ↓
    [NPL Engine]        [Vault Service]
```

### Security

**✅ Pros:**
- Single trust boundary (only Gateway has secrets)
- Credentials stay in memory, never persisted
- Direct control over injection logic

**❌ Cons:**
- **Gateway becomes high-value target** - compromise = all credentials exposed
- Credential code in same process as public-facing HTTP endpoints
- Harder to isolate blast radius
- All tenants share same Gateway process

### Performance

**✅ Pros:**
- **Lowest latency** (~60-80ms total):
  - NPL check: ~10ms
  - Vault fetch: ~50ms (cached)
  - No network hops
- True streaming maintained
- Single process = less overhead

**❌ Cons:**
- Gateway becomes bottleneck for all traffic
- Vault connection pooling shared across all tenants
- Memory pressure from credential caching

### Complexity

**✅ Pros:**
- Simplest to understand (single component)
- Easier debugging (all logs in one place)
- Fewer deployment units

**❌ Cons:**
- Gateway code becomes complex (HTTP + WebSocket + NPL + Vault + MCP)
- Hard to update credential logic without Gateway restart
- Tighter coupling

### Verdict: ⚠️ Simple but risky

Good for: **Single-tenant, low-scale, rapid prototyping**  
Bad for: **Multi-tenant SaaS, high-security environments, OpenClaw contribution**

---

## Option 2: Sidecar Interceptor Pattern (Recommended ⭐)

```
Agent ←─┐
        │
        ▼
┌────────────────────────────────────────────────────────────┐
│  Gateway MCP (Public)                                      │
│  - Receives MCP requests                                   │
│  - NO Vault access                                         │
│  - NO credential logic                                     │
└────────┬───────────────────────────────────────────────────┘
         │
         │ Unix socket / localhost
         ▼
┌────────────────────────────────────────────────────────────┐
│  NPL Sidecar (Policy + Credential Selection)              │
│  ┌──────────────────────────────────────────────────────┐  │
│  │ 1. Intercepts all outbound MCP traffic               │  │
│  │ 2. Checks ToolExecutionPolicy                        │  │
│  │ 3. Calls CredentialInjectionPolicy                   │  │
│  │ 4. Returns: (allow/deny, credentialSelection)        │  │
│  └──────────────────────────────────────────────────────┘  │
└────────┬───────────────────────────────────────────────────┘
         │
         │ Unix socket / localhost
         ▼
┌────────────────────────────────────────────────────────────┐
│  Vault Sidecar (Credential Injection)                     │
│  ┌──────────────────────────────────────────────────────┐  │
│  │ 1. Receives MCP request + credentialSelection        │  │
│  │ 2. Fetches from Vault (credentialSelection.path)     │  │
│  │ 3. Injects credentials based on mapping              │  │
│  │ 4. Forwards to upstream MCP                          │  │
│  │ 5. Streams response back (passthrough) ──────────────┼──┤
│  └──────────────────────────────────────────────────────┘  │
│         ↓ Vault Client                                     │
└─────────┼──────────────────────────────────────────────────┘
          ↓
    [Vault Service]
          │
          ▼
    [Upstream MCP: github, slack, etc.]
```

### Security

**✅ Pros:**
- **Defense in depth** - 3 separate processes with distinct privileges:
  - Gateway: Public-facing, NO secrets
  - NPL Sidecar: Policy only, NO secrets
  - Vault Sidecar: Secrets only, NO public exposure
- **Minimal blast radius** - compromise of Gateway = no credential access
- **Unix socket isolation** - sidecars not exposed to network
- **Process-level isolation** - can run with different users/namespaces
- **Audit trail** - NPL logs all policy decisions separately
- **Multi-tenancy friendly** - can run separate sidecar pods per tenant

**❌ Cons:**
- More complex secret management (3 services vs 1)
- Need to secure inter-process communication

### Performance

**✅ Pros:**
- **Near-native streaming** - Unix sockets ~1-2ms overhead
- **Parallel processing**:
  - Gateway handles public traffic
  - NPL Sidecar handles policy checks
  - Vault Sidecar handles credential injection
- **Independent scaling**:
  - Scale Gateway for public traffic
  - Scale Vault Sidecar for upstream calls
- **Caching at right layer** - credentials cached in Vault Sidecar only

**❌ Cons:**
- **Slightly higher latency** (~70-90ms total):
  - NPL check: ~10ms
  - Vault fetch: ~50ms
  - Unix socket hops: ~2-3ms each
- More complex load balancing

### Complexity

**✅ Pros:**
- **Separation of concerns**:
  - Gateway: MCP protocol only
  - NPL Sidecar: Policy logic only
  - Vault Sidecar: Credential injection only
- **Independent deployment** - update credential logic without Gateway changes
- **Cloud-native** - fits Kubernetes sidecar pattern perfectly
- **Testable** - each component tested independently
- **Reusable** - NPL/Vault sidecars work with any MCP gateway

**❌ Cons:**
- **More components to monitor** (3 processes vs 1)
- **More complex deployment** (sidecar configuration)
- **Distributed tracing required** for debugging across sidecars

### Implementation Details

**Gateway → NPL Sidecar (Policy Check)**
```kotlin
// Gateway sends metadata only (no request body)
POST http://localhost:9001/check-policy
{
  "service": "github",
  "operation": "create_issue",
  "metadata": {"repo": "acme/foo"}
}

// NPL Sidecar responds with policy decision + credential selection
200 OK
{
  "allowed": true,
  "credentialSelection": {
    "name": "work_github",
    "vaultPath": "tenants/acme/users/alice/github/work",
    "injectionMapping": {
      "token": "GITHUB_TOKEN"
    }
  }
}
```

**Gateway → Vault Sidecar (Inject & Forward)**
```kotlin
// Gateway forwards MCP request with credential selection
POST http://localhost:9002/forward-with-credentials
Headers:
  X-Credential-Selection: {"name":"work_github","vaultPath":"..."}
  
Body: <MCP JSON-RPC request>

// Vault Sidecar:
// 1. Fetches credentials from Vault
// 2. Injects into upstream call (env vars or headers)
// 3. Streams response back directly (HTTP chunked / WebSocket)
```

### Verdict: ⭐ **Best for production**

Good for: **Multi-tenant SaaS, high-security, Kubernetes deployments, OpenClaw**  
Bad for: **Simple single-tenant, development environments** (too much overhead)

---

## Option 3: Current Executor Pattern (via RabbitMQ)

```
Gateway ──► NPL ──► RabbitMQ ──► Executor ──► Vault
   │                                │
   │                                ▼
   │                          Upstream MCP
   │                                │
   └────────────── callback ────────┘
```

### Security

**✅ Pros:**
- Gateway has NO Vault access (same as sidecar)
- Network isolation via RabbitMQ
- Async = Gateway can't be blocked by slow Vault

**❌ Cons:**
- **NO true streaming** - request/response only
- RabbitMQ becomes credential-aware (metadata in messages)
- Executor must call back to Gateway (bidirectional networking)

### Performance

**❌ Cons:**
- **High latency** (~150-300ms):
  - Gateway → RabbitMQ: ~20ms
  - RabbitMQ → Executor: ~20ms
  - Vault fetch: ~50ms
  - Callback to Gateway: ~20ms
  - Total overhead: ~110ms + upstream latency
- **Breaks streaming** - can't stream responses
- Message serialization overhead

### Complexity

**❌ Cons:**
- RabbitMQ dependency (yet another service)
- Complex callback mechanism
- Hard to debug (distributed async flow)
- Request/response correlation complexity

### Verdict: ❌ **Legacy - do not use**

This was designed for async execution where Gateway doesn't wait. For streaming MCP, it's the wrong pattern.

---

## Option 4: Envoy/Istio Sidecar (Service Mesh)

```
Agent ──► Gateway ──► Envoy Sidecar ──► Upstream MCP
                           │
                           ├─► External Auth (NPL)
                           └─► Credential Service (Vault)
```

### Security

**✅ Pros:**
- Industry-standard service mesh security
- Mutual TLS out of the box
- External authorization via GRPC

**❌ Cons:**
- Envoy doesn't understand MCP protocol natively
- Hard to inject credentials into MCP-specific formats (env vars for STDIO)
- Complex external auth filter configuration

### Performance

**✅ Pros:**
- Battle-tested at scale
- Native support for streaming

**❌ Cons:**
- Envoy overhead (~5-10ms per hop)
- External auth calls add latency

### Complexity

**❌ Cons:**
- Requires full service mesh deployment
- Learning curve for Envoy/Istio configuration
- Overkill for single-service gateway

### Verdict: ⚠️ **Overkill unless you're already on Istio**

---

## Option 5: Lightweight Credential Proxy (Minimal Sidecar)

```
Agent ──► Gateway MCP ──► Credential Proxy ──► Upstream MCP
              │                  │
              │                  ▼
              ▼              [Vault]
          [NPL Engine]
```

Simplified sidecar: Gateway checks NPL, forwards to credential proxy which injects and forwards.

### Security

**✅ Pros:**
- Gateway still has NO Vault access
- Simpler than full sidecar pattern
- Small attack surface (proxy is ~200 LOC)

**❌ Cons:**
- Less separation than full sidecar (policy check still in Gateway)

### Performance

**✅ Pros:**
- Very low overhead (~65-85ms total)
- True streaming maintained
- Minimal process count (2 vs 3)

**❌ Cons:**
- Credential proxy becomes bottleneck

### Complexity

**✅ Pros:**
- Simpler than full sidecar
- Easy to deploy (just 2 processes)
- Easier debugging

**❌ Cons:**
- Still need to manage proxy lifecycle
- Less flexible than full sidecar pattern

### Verdict: ✅ **Good compromise for medium-scale deployments**

---

## Comparison Matrix

| Criteria | Gateway-Integrated | Sidecar Pattern | Executor/RabbitMQ | Envoy Mesh | Credential Proxy |
|----------|-------------------|-----------------|-------------------|------------|------------------|
| **Security** | ⚠️ Medium | ⭐ Excellent | ✅ Good | ⭐ Excellent | ✅ Good |
| **Performance** | ⭐ Best (60-80ms) | ✅ Good (70-90ms) | ❌ Poor (150-300ms) | ✅ Good (80-100ms) | ✅ Good (65-85ms) |
| **Streaming** | ⭐ Native | ⭐ Native | ❌ Broken | ⭐ Native | ⭐ Native |
| **Multi-tenancy** | ⚠️ Limited | ⭐ Excellent | ✅ Good | ⭐ Excellent | ✅ Good |
| **Blast Radius** | ❌ High | ⭐ Minimal | ✅ Good | ⭐ Minimal | ✅ Good |
| **Complexity** | ✅ Simple | ⚠️ Complex | ❌ Very Complex | ❌ Very Complex | ✅ Moderate |
| **Cloud Native** | ⚠️ Limited | ⭐ Perfect | ⚠️ Old pattern | ⭐ Perfect | ✅ Good |
| **OpenClaw Ready** | ⚠️ Questionable | ⭐ Yes | ❌ No | ⚠️ Too specific | ✅ Yes |

---

## Recommendation: Sidecar Pattern (Option 2) ⭐

### Why Sidecar Wins

1. **Security First**
   - Gateway is public-facing but has ZERO secrets
   - Vault Sidecar is internal-only and isolated
   - NPL Sidecar handles policy, never sees credentials
   - Perfect for SOC 2, HIPAA, PCI compliance

2. **True Streaming**
   - Unix sockets maintain bidirectional streaming
   - ~2-3ms overhead per hop (negligible)
   - No message queue serialization

3. **Multi-Tenancy**
   - Each tenant can have separate sidecar pods
   - Per-tenant policy isolation
   - Per-tenant credential caching

4. **Cloud Native**
   - Fits Kubernetes perfectly (sidecar containers)
   - Independent scaling of each component
   - Rolling updates without downtime

5. **OpenClaw Contribution**
   - Clean separation makes it reusable
   - Standard sidecar pattern others can adopt
   - Easy to document and explain

### Implementation Plan

**Phase 1: NPL Sidecar**
- Lightweight HTTP server (Ktor)
- Exposes `/check-policy` endpoint
- Calls NPL Engine for ToolExecutionPolicy + CredentialInjectionPolicy
- Returns decision + credential selection

**Phase 2: Vault Sidecar**
- Lightweight HTTP/WebSocket proxy (Ktor)
- Exposes `/forward-with-credentials` endpoint
- Reads credential selection from headers
- Fetches from Vault, injects, forwards to upstream
- Streams response back (passthrough mode)

**Phase 3: Gateway Updates**
- Add NPL Sidecar client
- Add Vault Sidecar client
- Remove direct Vault access
- Remove RabbitMQ/Executor dependencies

### Deployment Architecture

**Development (single machine):**
```bash
# Terminal 1: Gateway (public)
./gradlew :gateway:run

# Terminal 2: NPL Sidecar (localhost:9001)
./gradlew :npl-sidecar:run

# Terminal 3: Vault Sidecar (localhost:9002)
./gradlew :vault-sidecar:run
```

**Production (Kubernetes):**
```yaml
apiVersion: v1
kind: Pod
metadata:
  name: mcp-gateway
spec:
  containers:
  - name: gateway
    image: noumena/mcp-gateway:latest
    ports:
    - containerPort: 8080
    
  - name: npl-sidecar
    image: noumena/npl-sidecar:latest
    ports:
    - containerPort: 9001
    
  - name: vault-sidecar
    image: noumena/vault-sidecar:latest
    ports:
    - containerPort: 9002
    env:
    - name: VAULT_ADDR
      value: "http://vault:8200"
    - name: VAULT_TOKEN
      valueFrom:
        secretKeyRef:
          name: vault-token
          key: token
```

---

## Alternative for Simple Use Cases: Credential Proxy (Option 5)

If full sidecar is too complex, **Option 5 (Credential Proxy)** is a good middle ground:

- Gateway checks NPL directly (keep existing code)
- Forward to single credential proxy sidecar
- Proxy fetches from Vault and injects
- Still maintains security boundary (Gateway has no Vault access)

**Use when:**
- Single tenant
- < 100 req/s
- Want simpler operations

---

## Next Steps

**If you choose Sidecar Pattern:**
1. Create `npl-sidecar/` module
2. Create `vault-sidecar/` module
3. Update Gateway to use sidecar clients
4. Remove Executor + RabbitMQ
5. Update deployment configs

**If you choose Credential Proxy:**
1. Create `credential-proxy/` module
2. Update Gateway to forward through proxy
3. Remove Executor + RabbitMQ

Let me know which path you want to take and I'll start building!
