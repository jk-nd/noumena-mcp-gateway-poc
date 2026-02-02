# Noumena MCP Gateway - Next Steps

## What's Done

### Working Infrastructure
| Component | Status | Notes |
|-----------|--------|-------|
| **Gateway** | ✅ Running | Kotlin/Ktor, receives MCP calls from agents |
| **Executor** | ✅ Running | Kotlin/Ktor, routes to upstreams, has Vault access |
| **Mock MCP** | ✅ Running | Go server that echoes tool calls |
| **Vault** | ✅ Running | Dev mode with `dev-token` |
| **Keycloak** | ✅ Running | Dev mode, not yet integrated |
| **Docker Compose** | ✅ Working | Full stack deployment |

### Dev Mode Stubs
| Feature | Status | Behavior |
|---------|--------|----------|
| Auth (ActorTokenValidator) | Stub | Accepts any token, returns mock claims |
| Policy (NplClient) | Stub | Auto-allows, triggers executor directly |
| Vault (VaultClient) | Stub | Returns mock credentials per service |

### Tools
| Tool | Status | Description |
|------|--------|-------------|
| `tools/register-mcp.py` | Basic | CLI for registering new MCP services |

### Repository
- **GitHub**: https://github.com/jk-nd/noumena-mcp-gateway-poc
- **Commits**: 7 commits on `main`

---

## Next Steps Plan

### Phase 1: Dynamic Service Registration (Priority: High)

**Goal**: Services can be added/updated at runtime without restarts.

#### 1.1 Service Registry
```
┌─────────────────────────────────────────────────────────────────┐
│                     SERVICE REGISTRY                             │
│  (Source of truth for available MCP services)                   │
│                                                                  │
│  Options:                                                        │
│  - NPL Protocol (ServiceRegistry.npl)                           │
│  - PostgreSQL + API                                              │
│  - Redis + pub/sub for notifications                            │
└─────────────────────────────────────────────────────────────────┘
```

**Tasks**:
- [ ] Create `ServiceRegistry` NPL protocol or DB schema
- [ ] Add Admin API endpoints: `POST /admin/services`, `GET /admin/services`
- [ ] Gateway: Poll/subscribe to registry for available tools
- [ ] Executor: Poll/subscribe for upstream routing config

#### 1.2 Gateway Dynamic Tools
```kotlin
// Instead of hardcoded tools list:
class DynamicToolRegistry {
    suspend fun getAvailableTools(userId: String): List<Tool>
    fun subscribeToChanges(callback: (List<Tool>) -> Unit)
}
```

**Tasks**:
- [ ] Create `ToolRegistry` interface
- [ ] Implement NPL-backed or DB-backed registry
- [ ] Update `McpServer.kt` to use dynamic registry
- [ ] Add caching with TTL

#### 1.3 NPL Hot Deployment
```bash
# Deploy new protocol without restart
curl -X POST http://npl-engine:8080/api/protocols \
  -H "Content-Type: application/npl" \
  --data-binary @governance/new_service.npl
```

**Tasks**:
- [ ] Document NPL Engine deployment API
- [ ] Update registration tool to deploy NPL via API
- [ ] Add protocol versioning

---

### Phase 2: NPL Governance Templates (Priority: High)

**Goal**: Standard, reusable governance patterns for all services.

#### 2.1 Base Delegation Protocol
```npl
package governance.base

/**
 * Base protocol for all service delegations.
 * Tracks user → agent delegation for a specific service.
 */
@api
protocol[user] ServiceDelegation(
    var agentId: Text,
    var service: Text,
    var allowedOperations: List<Text>
) {
    initial state active;
    final state revoked;
    
    @api
    permission[user] isAllowed(operation: Text) returns Boolean | active {
        return allowedOperations.contains(operation) || allowedOperations.contains("*");
    };
    
    @api
    permission[user] revoke() | active {
        become revoked;
    };
}
```

#### 2.2 Constraint Templates
```npl
// Budget constraint
@api
protocol[user] BudgetConstraint(var dailyLimit: Number) { ... }

// Rate limit constraint  
@api
protocol[user] RateLimitConstraint(var maxPerHour: Number) { ... }

// Approval workflow constraint
@api
protocol[user, approver] ApprovalConstraint(var thresholdAmount: Number) { ... }

// Time restriction constraint
@api
protocol[user] TimeConstraint(var allowedHours: List<Number>) { ... }
```

#### 2.3 Composed Service Governance
```npl
package governance.services

/**
 * Governance for a specific service.
 * Composes base delegation with service-specific constraints.
 */
@api
protocol[user] GoogleFlightsGovernance(
    var delegation: ServiceDelegation,
    var budget: BudgetConstraint,
    var rateLimit: RateLimitConstraint
) {
    @api
    permission[user] canBook(metadata: Map<Text, Text>) returns Boolean | active {
        // Check delegation
        require(delegation.isAllowed[user]("book"), "Operation not allowed");
        
        // Check budget
        var amount = metadata.getOrNone("amount").getOrElse("0");
        require(budget.checkSpend[user](amount.toNumber()), "Budget exceeded");
        
        // Check rate limit
        require(rateLimit.checkRate[user](), "Rate limit exceeded");
        
        return true;
    };
}
```

**Tasks**:
- [ ] Create `governance/base/` package with delegation protocol
- [ ] Create `governance/constraints/` with reusable constraints
- [ ] Create `governance/templates/` with composed examples
- [ ] Update registration tool to use templates
- [ ] Test with NPL Engine

---

### Phase 3: Interactive TUI (Priority: Medium)

**Goal**: Rich terminal UI for service management.

#### 3.1 TUI Framework
Use Python `textual` or `rich` for modern TUI:

```
┌─────────────────────────────────────────────────────────────────┐
│  NOUMENA MCP GATEWAY - Service Manager                    [Q]uit│
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  Services                          │  Details                   │
│  ─────────────────────────         │  ───────────────────────   │
│  ▸ google-gmail      ● Active      │  Name: google-gmail        │
│    google-calendar   ● Active      │  Status: Active            │
│    slack             ● Active      │  Endpoint: google-mcp:8080 │
│    sap               ○ Pending     │  Operations:               │
│                                    │    • send_email            │
│                                    │    • read_inbox            │
│                                    │  Policy: GoogleGovern...   │
│  [A]dd  [E]dit  [D]elete          │  [V]iew Logs  [T]est       │
│                                                                  │
├─────────────────────────────────────────────────────────────────┤
│  Status: 4 services registered, 3 active                        │
└─────────────────────────────────────────────────────────────────┘
```

#### 3.2 TUI Features
| Feature | Description |
|---------|-------------|
| **List Services** | Show all registered MCP services with status |
| **Add Service** | Wizard for new service (Docker image, operations, governance) |
| **Edit Service** | Modify existing service configuration |
| **View Logs** | Stream logs from Gateway/Executor for a service |
| **Test Tool** | Send test tool call and see result |
| **Deploy NPL** | Edit and deploy NPL governance |
| **Manage Credentials** | Store/update Vault secrets |

#### 3.3 TUI Tech Stack
```
tools/
├── requirements.txt      # textual, rich, httpx
├── tui/
│   ├── __init__.py
│   ├── app.py           # Main TUI application
│   ├── screens/
│   │   ├── services.py  # Service list screen
│   │   ├── add.py       # Add service wizard
│   │   ├── logs.py      # Log viewer
│   │   └── test.py      # Tool tester
│   └── api/
│       ├── gateway.py   # Gateway API client
│       ├── npl.py       # NPL Engine API client
│       └── vault.py     # Vault API client
└── manage.py            # CLI entry point
```

**Tasks**:
- [ ] Set up `textual` project structure
- [ ] Implement service list screen
- [ ] Implement add service wizard
- [ ] Implement log viewer (WebSocket or polling)
- [ ] Implement tool tester
- [ ] Add NPL editor/deployer
- [ ] Add Vault credential manager

---

### Phase 4: Production Readiness (Priority: Low for POC)

| Task | Description |
|------|-------------|
| Real Keycloak Auth | JWT validation with JWKS, token exchange |
| Real Vault | Production Vault with AppRole auth |
| NPL Engine Integration | Replace dev bypass with real NPL calls |
| Network Policies | K8s NetworkPolicy for Executor isolation |
| Observability | Prometheus metrics, structured logging |
| CI/CD | GitHub Actions for build/test/deploy |

---

## Quick Reference

### Start the Stack
```bash
cd deployments
docker compose up -d
```

### Test a Tool Call
```bash
curl -X POST http://localhost:8080/mcp/call \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer any-token" \
  -d '{"tool": "google_gmail.send_email", "arguments": {"to": "test@example.com"}}'
```

### Register a New Service
```bash
cd tools
source .venv/bin/activate
python3 register-mcp.py
```

### View Logs
```bash
docker logs -f deployments-gateway-1
docker logs -f deployments-executor-1
```

---

## Architecture Diagram

```
                                    ┌─────────────┐
                                    │   Agent     │
                                    │  (ADK/etc)  │
                                    └──────┬──────┘
                                           │ MCP
                                           ▼
┌──────────────────────────────────────────────────────────────────┐
│                         GATEWAY                                   │
│  • Validates Actor Token (Keycloak)                              │
│  • Fetches available tools (Registry)                            │
│  • Sends policy check to NPL                                     │
│  • NO Vault access                                               │
└──────────────────────────────────────────────────────────────────┘
                                           │
                                           ▼
┌──────────────────────────────────────────────────────────────────┐
│                       NPL ENGINE                                  │
│  • Evaluates governance protocols                                │
│  • Checks delegation, constraints                                │
│  • If allowed: triggers Executor via HTTP Bridge                 │
│  • Maintains audit trail                                         │
└──────────────────────────────────────────────────────────────────┘
                                           │
                                           ▼
┌──────────────────────────────────────────────────────────────────┐
│                        EXECUTOR                                   │
│  • Receives requests from NPL only (network isolated)            │
│  • Fetches credentials from Vault                                │
│  • Calls upstream MCP/REST services                              │
│  • Sends callback to Gateway                                     │
└──────────────────────────────────────────────────────────────────┘
                                           │
                         ┌─────────────────┼─────────────────┐
                         ▼                 ▼                 ▼
                  ┌───────────┐     ┌───────────┐     ┌───────────┐
                  │ Google    │     │ Slack     │     │ SAP       │
                  │ MCP       │     │ MCP       │     │ REST      │
                  └───────────┘     └───────────┘     └───────────┘
```
