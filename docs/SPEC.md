# Noumena MCP Gateway Specification

**Version**: 1.3 Draft  
**Date**: 2026-02-01  
**Status**: Proposal  
**Implementation Language**: Kotlin

---

## Executive Summary

The Noumena MCP Gateway is a policy-enforced proxy that enables AI agents to access external services (Google, SAP, Slack, etc.) through a governed channel. The gateway ensures:

- **Policy Enforcement**: Every request goes through NPL for authorization
- **No Content Persistence**: Gateway is stateless; only metadata is logged
- **Docker MCP Ecosystem**: Leverages containerized MCP servers from Docker Hub
- **Secret Management**: Integrates with customer's Vault (secrets never in Noumena)
- **Multi-tenancy**: Tenant isolation for SaaS deployments

### Technology Stack

- **Gateway + Executor**: Kotlin with Ktor
- **MCP Protocol**: Official Kotlin MCP SDK (`io.modelcontextprotocol:kotlin-sdk`)
- **Agent Framework**: Google ADK (already in use)
- **NPL Engine**: Existing Noumena NPL Engine (HTTP + notifications)
- **Upstream Services**: Docker MCP containers + direct REST APIs

---

## Table of Contents

1. [Architecture Overview](#1-architecture-overview)
2. [Component Design](#2-component-design)
3. [Authentication & Authorization](#3-authentication--authorization)
4. [Upstream Integration Patterns](#4-upstream-integration-patterns)
5. [Configuration](#5-configuration)
6. [Keycloak Integration](#6-keycloak-integration)
7. [NPL Engine Integration](#7-npl-engine-integration)
8. [Vault Access Architecture](#8-vault-access-architecture)
9. [Security Model](#9-security-model)
10. [Implementation Plan](#10-implementation-plan)
11. [API Reference](#11-api-reference)

---

## 1. Architecture Overview

### 1.1 High-Level Architecture

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                           NOUMENA MCP GATEWAY SYSTEM                        │
│                                                                             │
│  ┌─────────────┐                                                            │
│  │   Agents    │                                                            │
│  │ (Clawbot,   │──── MCP ────┐                                              │
│  │  Jarvis)    │             │                                              │
│  └─────────────┘             ▼                                              │
│                     ┌─────────────────┐                                     │
│                     │    Gateway      │                                     │
│                     │  (Routing Only) │                                     │
│                     │  NO Vault access│                                     │
│                     └────────┬────────┘                                     │
│                              │                                              │
│                              │ Policy check + execute request               │
│                              ▼                                              │
│                     ┌─────────────────┐     ┌─────────────────┐            │
│                     │   NPL Engine    │────►│    Executor     │            │
│                     │  (Policy Only)  │notif│ (Secret Handler)│            │
│                     │  NO Vault access│     │  HAS Vault access            │
│                     └─────────────────┘     └────────┬────────┘            │
│                              │                       │                      │
│                              │                       ▼                      │
│                              │              ┌─────────────────┐            │
│                              │              │     Vault       │            │
│                              │              │   (Secrets)     │            │
│                              │              └─────────────────┘            │
│                              │                       │                      │
│           ┌──────────────────┴───────────────────────┤                     │
│           │                                          ▼                      │
│           │              ┌─────────────────────────────────────────┐       │
│           │              │            UPSTREAM SERVICES            │       │
│           │              │  ┌─────────┐ ┌─────────┐ ┌─────────┐   │       │
│           │              │  │Docker   │ │Direct   │ │Internal │   │       │
│           │              │  │MCP      │ │REST     │ │Services │   │       │
│           │              │  │Containers│ │(SAP)   │ │         │   │       │
│           │              │  └─────────┘ └─────────┘ └─────────┘   │       │
│           │              └─────────────────────────────────────────┘       │
│           │                              │                                  │
│           │◄─────────────────────────────┘                                  │
│           │         Result (via callback)                                   │
│           ▼                                                                 │
│  ┌─────────────┐                                                            │
│  │   Agents    │◄── Result                                                  │
│  └─────────────┘                                                            │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘

KEY SECURITY PROPERTY:
• Gateway has NO Vault access - cannot bypass NPL
• Only Executor has Vault access - only triggered by NPL notifications
• NPL is the mandatory policy gate
```

### 1.2 Request Flow

```
1. Agent sends MCP request with Actor Token
2. Gateway validates Actor Token (via Keycloak)
3. Gateway checks token scope (service/operation allowed?)
4. Gateway extracts metadata (user, tenant, service, operation)
5. Gateway calls NPL Engine with metadata for policy decision
6. If DENIED:
   a. Gateway returns error to agent
7. If NEEDS_APPROVAL:
   a. Gateway returns pending status
   b. Approval flow handled by NPL
8. If ALLOWED:
   a. NPL sends notification to Executor (with request details)
   b. Executor fetches credentials from Vault
   c. Executor calls upstream (Docker MCP containers or REST APIs)
   d. Executor sends result to Gateway (via callback)
   e. Gateway returns response to agent

CRITICAL: Gateway NEVER touches Vault. Only Executor does.
          Executor ONLY acts on NPL notifications.
          This architecturally enforces NPL cannot be bypassed.
```

### 1.3 Core Principles

| Principle | Implementation |
|-----------|----------------|
| **Stateless Gateway** | No content persistence, only metadata to NPL |
| **Policy-First** | Every request must pass through NPL |
| **Gateway Has No Secrets** | Gateway cannot access Vault at all |
| **Executor Is Secret Handler** | Only Executor has Vault access, triggered by NPL |
| **Architectural Enforcement** | NPL bypass is impossible, not just discouraged |
| **Configuration-Driven** | Upstream routing via YAML/JSON config |
| **OIDC-Standard Auth** | Keycloak or any OIDC provider |

---

## 2. Component Design

### 2.1 Kotlin Project Structure

The system consists of two services: **Gateway** and **Executor**.

```
noumena-mcp-gateway/
├── gateway/                          # Gateway module (NO Vault access)
│   └── src/main/kotlin/
│       └── io/noumena/mcp/gateway/
│           ├── Application.kt        # Ktor application entry
│           ├── server/
│           │   └── McpServer.kt       # Agent-facing MCP server
│           ├── auth/
│           │   ├── OidcValidator.kt   # OIDC token validation
│           │   ├── ActorToken.kt      # Actor token handling
│           │   └── TenantExtractor.kt # Tenant extraction
│           ├── policy/
│           │   └── NplClient.kt       # NPL Engine HTTP client
│           └── callback/
│               └── CallbackHandler.kt # Receives results from Executor
│
├── executor/                          # Executor module (HAS Vault access)
│   └── src/main/kotlin/
│       └── io/noumena/mcp/executor/
│           ├── Application.kt         # Ktor application entry
│           ├── handler/
│           │   └── ExecuteHandler.kt  # Handles NPL notifications
│           ├── secrets/
│           │   └── VaultClient.kt     # HashiCorp Vault client
│           ├── upstream/
│           │   ├── UpstreamRouter.kt  # Upstream routing logic
│           │   ├── McpUpstream.kt     # MCP client for Docker containers
│           │   └── RestUpstream.kt    # REST client for legacy APIs
│           └── callback/
│               └── CallbackSender.kt  # Sends results to Gateway
│
├── shared/                            # Shared module
│   └── src/main/kotlin/
│       └── io/noumena/mcp/shared/
│           ├── models/
│           │   ├── ExecuteRequest.kt  # Shared request types
│           │   └── ExecuteResult.kt   # Shared result types
│           └── config/
│               └── ConfigLoader.kt    # YAML configuration loading
│
├── build.gradle.kts                   # Root build file
├── settings.gradle.kts                # Module settings
├── gradle.properties
│
├── configs/
│   ├── gateway.yaml                   # Gateway configuration
│   ├── executor.yaml                  # Executor configuration
│   └── upstreams.yaml                 # Docker MCP + REST definitions
│
├── deployments/
│   ├── docker/
│   │   ├── Dockerfile.gateway
│   │   └── Dockerfile.executor
│   ├── docker-compose.yml             # Local development stack
│   └── kubernetes/
│       ├── gateway-deployment.yaml
│       ├── executor-deployment.yaml
│       └── mcp-containers.yaml        # Docker MCP container deployments
│
└── README.md
```

### 2.2 Gradle Dependencies

```kotlin
// build.gradle.kts (root)
plugins {
    kotlin("jvm") version "1.9.22"
    kotlin("plugin.serialization") version "1.9.22"
}

subprojects {
    dependencies {
        // Kotlin MCP SDK
        implementation("io.modelcontextprotocol:kotlin-sdk-server:0.8.3")
        implementation("io.modelcontextprotocol:kotlin-sdk-client:0.8.3")
        
        // Ktor (HTTP server + client)
        implementation("io.ktor:ktor-server-core:2.3.7")
        implementation("io.ktor:ktor-server-netty:2.3.7")
        implementation("io.ktor:ktor-client-core:2.3.7")
        implementation("io.ktor:ktor-client-cio:2.3.7")
        
        // Serialization
        implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")
        
        // Coroutines
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    }
}

// gateway/build.gradle.kts
dependencies {
    implementation(project(":shared"))
    // NO Vault dependency - Gateway cannot access secrets
}

// executor/build.gradle.kts
dependencies {
    implementation(project(":shared"))
    // Vault client (only Executor has this)
    implementation("com.bettercloud:vault-java-driver:5.1.0")
}
```

### 2.2 Core Interfaces

```go
// internal/server/mcp_server.go

package server

import "context"

// MCPServer handles incoming MCP requests from agents
type MCPServer interface {
    // Start the MCP server
    Start(ctx context.Context) error
    
    // Stop gracefully
    Stop(ctx context.Context) error
    
    // RegisterTool registers a tool handler
    RegisterTool(name string, handler ToolHandler) error
}

// ToolHandler processes a tool invocation
type ToolHandler func(ctx context.Context, req *ToolRequest) (*ToolResponse, error)

// ToolRequest represents an incoming tool call
type ToolRequest struct {
    ToolName   string
    Arguments  map[string]interface{}
    ActorToken string  // From auth header
    RequestID  string  // For correlation
}

// ToolResponse is the result of a tool call
type ToolResponse struct {
    Result interface{}
    Error  *ToolError
}
```

```go
// internal/policy/npl_client.go

package policy

import "context"

// PolicyClient communicates with NPL Engine
type PolicyClient interface {
    // CheckPolicy evaluates a request against NPL protocols
    CheckPolicy(ctx context.Context, req *PolicyRequest) (*PolicyDecision, error)
}

// PolicyRequest contains metadata for policy evaluation
type PolicyRequest struct {
    TenantID     string
    UserID       string
    AgentID      string
    Service      string
    Operation    string
    Metadata     map[string]string  // Additional context (e.g., recipient_domain)
    Timestamp    time.Time
}

// PolicyDecision is the result of policy evaluation
type PolicyDecision struct {
    Allowed      bool
    Reason       string
    RequestID    string   // NPL-generated for correlation
    RequiresApproval bool
    ApprovalID   string   // If pending approval
}
```

```go
// internal/executor/handler/execute.go

package handler

import "context"

// ExecuteRequest is received from NPL via notification
type ExecuteRequest struct {
    RequestID   string                 `json:"request_id"`
    TenantID    string                 `json:"tenant_id"`
    UserID      string                 `json:"user_id"`
    Service     string                 `json:"service"`
    Operation   string                 `json:"operation"`
    Params      map[string]interface{} `json:"params"`
    CallbackURL string                 `json:"callback_url"`
    NPLSignature string                `json:"npl_signature"`  // To verify from NPL
}

// ExecuteHandler processes execution requests from NPL
type ExecuteHandler interface {
    // Handle processes an execution request
    Handle(ctx context.Context, req *ExecuteRequest) error
}
```

```go
// internal/executor/upstream/router.go

package upstream

import "context"

// Upstream represents a configured upstream service
type Upstream interface {
    // Call invokes the upstream service
    Call(ctx context.Context, req *UpstreamRequest) (*UpstreamResponse, error)
    
    // HealthCheck verifies upstream availability
    HealthCheck(ctx context.Context) error
}

// UpstreamRequest is a request to an upstream service
type UpstreamRequest struct {
    Service     string
    Operation   string
    Payload     interface{}           // Content (transient, never persisted)
    Credentials *CredentialSet        // From Vault
    UserContext *UserContext          // For user-specific operations
}

// Router selects and invokes the appropriate upstream
type Router interface {
    // Route determines the upstream and calls it
    Route(ctx context.Context, req *UpstreamRequest) (*UpstreamResponse, error)
    
    // GetUpstream returns the configured upstream for a service
    GetUpstream(service string) (Upstream, error)
}
```

```go
// internal/executor/secrets/vault_client.go

package secrets

import "context"

// SecretClient retrieves secrets from Vault
// NOTE: This is in the EXECUTOR package, NOT Gateway
type SecretClient interface {
    // GetCredentials fetches credentials for a user+service
    GetCredentials(ctx context.Context, tenantID, userID, service string) (*CredentialSet, error)
    
    // GetAPIKey fetches an API key for a service (tenant-level)
    GetAPIKey(ctx context.Context, tenantID, service string) (string, error)
}

// CredentialSet contains credentials for an upstream service
type CredentialSet struct {
    Type         CredentialType  // oauth, api_key, basic, etc.
    AccessToken  string          // For OAuth
    RefreshToken string          // For OAuth refresh
    APIKey       string          // For API key auth
    ExpiresAt    time.Time
}
```

---

## 3. Authentication & Authorization

### 3.0 Scopes vs. Policy: Understanding the Layers

There are two distinct layers of access control:

```
┌─────────────────────────────────────────────────────────────────────────────┐
│  LAYER 1: TOKEN SCOPES (Static, Keycloak-managed)                          │
│  ─────────────────────────────────────────────────                          │
│                                                                             │
│  Scopes define CAPABILITY - what the agent could potentially do.           │
│  Set at delegation time, fixed until token expires.                        │
│                                                                             │
│  Can be service-level:     ["gmail", "calendar", "sap"]                    │
│  Or operation-level:       ["gmail.send", "gmail.read", "sap.create_po"]   │
│                                                                             │
│  Scopes are BOOLEAN: you have the scope or you don't.                     │
│  Scopes are STATIC: same answer for every request until expiry.            │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────────────┐
│  LAYER 2: NPL POLICY (Dynamic, Stateful)                                   │
│  ────────────────────────────────────────                                   │
│                                                                             │
│  Policy defines PERMISSION - what the agent can do RIGHT NOW.              │
│  Evaluated at request time, depends on current state.                      │
│                                                                             │
│  NPL can enforce:                                                           │
│  • Quotas:        "Max 10 external emails per day"                         │
│  • Parameter rules: "Only approved vendors", "Max $5000"                   │
│  • Approval flows: "Over $1000 needs manager approval"                     │
│  • Cross-service:  "Can't book travel if budget exhausted"                 │
│  • Runtime changes: "Block this agent now" (no new token needed)           │
│                                                                             │
│  NPL is CONDITIONAL: depends on state, parameters, context.                │
│  NPL is DYNAMIC: answer can change between requests.                       │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

**When NPL is Optional:**
- Simple personal use (no quotas, no approval workflows)
- Token scopes provide sufficient control
- Only audit logging needed (NPL can still log without policy logic)

**When NPL is Required:**
- Enterprise with business rules (budgets, approvals, vendor lists)
- Stateful constraints (quotas, rate limits beyond token expiry)
- Multi-level approval workflows
- Runtime policy changes without re-issuing tokens
- Rich audit trail with business context

**Configuration:**

```yaml
# In gateway.yaml
policy:
  mode: "required"  # or "optional" or "audit_only"
  
  # If "required": every request must pass NPL
  # If "optional": only call NPL if integration config specifies
  # If "audit_only": call NPL but don't block on denial (just log)
```

### 3.1 Token Hierarchy

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                           TOKEN HIERARCHY                                   │
│                                                                             │
│  User's Master Token (Keycloak JWT)                                        │
│  ─────────────────────────────────────                                      │
│  • Issued by Keycloak when user logs in                                    │
│  • Stays in user's browser/app                                             │
│  • NEVER given to agents                                                   │
│                                                                             │
│         │                                                                   │
│         │ Delegation (Token Exchange - RFC 8693)                           │
│         ▼                                                                   │
│                                                                             │
│  Actor Token (Delegated JWT)                                               │
│  ────────────────────────────                                               │
│  • Issued by Keycloak Token Exchange                                       │
│  • Given to agent (Jarvis, Clawbot)                                        │
│  • Scoped: specific services/operations                                    │
│  • Short-lived (e.g., 1 hour)                                              │
│  • Contains: agent_id, delegated_by, allowed_services                      │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 3.2 Actor Token Claims

```json
{
  "iss": "https://keycloak.example.com/realms/noumena",
  "sub": "jarvis-agent-123",
  "aud": "noumena-mcp-gateway",
  "exp": 1706875200,
  "iat": 1706871600,
  
  "act": {
    "sub": "user-a@company.com"
  },
  
  "noumena": {
    "tenant_id": "acme-corp",
    "delegated_by": "user-a@company.com",
    "agent_id": "jarvis-agent-123",
    "allowed_services": ["gmail", "calendar", "slack"],
    "delegation_id": "del-xyz-789"
  }
}
```

### 3.3 Token Validation Flow

```go
// internal/auth/oidc.go

package auth

import (
    "context"
    "github.com/coreos/go-oidc/v3/oidc"
)

type OIDCValidator struct {
    provider *oidc.Provider
    verifier *oidc.IDTokenVerifier
    config   *OIDCConfig
}

type OIDCConfig struct {
    IssuerURL    string   // Keycloak realm URL
    ClientID     string   // Gateway's client ID
    RequiredAud  []string // Required audiences
}

func (v *OIDCValidator) ValidateActorToken(ctx context.Context, tokenString string) (*ActorClaims, error) {
    // 1. Verify signature using Keycloak's JWKS
    idToken, err := v.verifier.Verify(ctx, tokenString)
    if err != nil {
        return nil, ErrInvalidToken
    }
    
    // 2. Extract claims
    var claims ActorClaims
    if err := idToken.Claims(&claims); err != nil {
        return nil, ErrInvalidClaims
    }
    
    // 3. Validate actor token specific claims
    if claims.Act.Sub == "" {
        return nil, ErrNotActorToken
    }
    
    // 4. Check delegation is still valid (optional: call Keycloak)
    if v.config.ValidateDelegation {
        if err := v.checkDelegationValid(ctx, claims.Noumena.DelegationID); err != nil {
            return nil, ErrDelegationRevoked
        }
    }
    
    return &claims, nil
}
```

---

## 4. Upstream Integration Patterns

### 4.1 Docker MCP Ecosystem

The Noumena MCP Gateway leverages the **Docker MCP Catalog** for upstream service integration.
This eliminates the need for middleware like Zapier—each service runs as a containerized MCP server.

```
┌─────────────────────────────────────────────────────────────────────────────┐
│  WHY DOCKER MCP CONTAINERS?                                                │
│                                                                             │
│  • 1M+ pulls of curated MCP servers (Google, Stripe, Slack, etc.)         │
│  • Container isolation (security sandbox)                                  │
│  • Cryptographic signatures + SBOMs                                        │
│  • No middleman (Zapier) seeing your data                                 │
│  • OAuth handled by each container                                         │
│  • You control the containers, you control the data                       │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 4.2 Supported Upstream Types

| Type | Description | Example | Credentials |
|------|-------------|---------|-------------|
| **docker_mcp** | Docker Hub MCP containers | Google, Slack, Stripe | OAuth tokens from Vault |
| **direct_rest** | Direct REST API (no MCP) | SAP S/4HANA, internal APIs | API keys from Vault |
| **internal_mcp** | Self-hosted MCP servers | Custom internal tools | Bearer tokens from Vault |

### 4.3 Docker MCP Upstream (Primary Pattern)

```kotlin
// executor/src/main/kotlin/io/noumena/mcp/executor/upstream/McpUpstream.kt

package io.noumena.mcp.executor.upstream

import io.modelcontextprotocol.kotlin.sdk.client.McpClient
import io.modelcontextprotocol.kotlin.sdk.client.transport.HttpTransport

class McpUpstream(
    private val config: McpUpstreamConfig
) : Upstream {
    
    private val client = McpClient(
        transport = HttpTransport(config.endpoint)
    )
    
    override suspend fun call(request: UpstreamRequest): UpstreamResult {
        // Credentials already fetched from Vault and injected
        val result = client.callTool(
            name = request.operation,
            arguments = request.params,
            headers = mapOf(
                "Authorization" to "Bearer ${request.credentials.accessToken}"
            )
        )
        
        return UpstreamResult(
            success = true,
            data = result.content
        )
    }
}

data class McpUpstreamConfig(
    val name: String,           // e.g., "google_gmail"
    val endpoint: String,       // e.g., "http://google-mcp:8080"
    val vaultPath: String,      // e.g., "secret/data/tenants/{tenant}/users/{user}/google"
    val authType: AuthType      // OAUTH, API_KEY, NONE
)
```

### 4.4 Docker Compose: MCP Containers

```yaml
# deployments/docker-compose.yml

version: '3.8'
services:
  # Noumena Gateway (NO Vault access)
  gateway:
    build: ./gateway
    ports:
      - "8080:8080"
    environment:
      - NPL_URL=http://npl-engine:8080
      - OIDC_ISSUER=http://keycloak:8080/realms/noumena
  
  # Noumena Executor (HAS Vault access)
  executor:
    build: ./executor
    environment:
      - VAULT_ADDR=http://vault:8200
    depends_on:
      - vault
  
  # NPL Engine
  npl-engine:
    image: noumena/npl-engine:latest
    environment:
      - EXECUTOR_URL=http://executor:8080/execute
  
  # ─────────────────────────────────────────────────────────────────────────
  # Docker MCP Containers (from Docker Hub MCP Catalog)
  # ─────────────────────────────────────────────────────────────────────────
  
  google-mcp:
    image: docker.io/mcp/google-workspace:latest
    environment:
      - GOOGLE_OAUTH_CONFIG_PATH=/secrets/google-oauth.json
    volumes:
      - ./secrets/google:/secrets:ro
  
  slack-mcp:
    image: docker.io/mcp/slack:latest
    environment:
      - SLACK_TOKEN_PATH=/secrets/slack-token
    volumes:
      - ./secrets/slack:/secrets:ro
  
  stripe-mcp:
    image: docker.io/mcp/stripe:latest
    environment:
      - STRIPE_API_KEY_PATH=/secrets/stripe-key
    volumes:
      - ./secrets/stripe:/secrets:ro
  
  # ─────────────────────────────────────────────────────────────────────────
  # Infrastructure
  # ─────────────────────────────────────────────────────────────────────────
  
  vault:
    image: hashicorp/vault:latest
    ports:
      - "8200:8200"
    environment:
      - VAULT_DEV_ROOT_TOKEN_ID=dev-token
  
  keycloak:
    image: quay.io/keycloak/keycloak:latest
    ports:
      - "8081:8080"
    command: start-dev
```

### 4.5 Direct REST Upstream (Legacy APIs)

For services without MCP support (e.g., SAP S/4HANA, internal APIs):

```kotlin
// executor/src/main/kotlin/io/noumena/mcp/executor/upstream/RestUpstream.kt

package io.noumena.mcp.executor.upstream

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.http.*

class RestUpstream(
    private val config: RestUpstreamConfig,
    private val httpClient: HttpClient
) : Upstream {
    
    override suspend fun call(request: UpstreamRequest): UpstreamResult {
        val endpoint = config.endpoints[request.operation]
            ?: throw IllegalArgumentException("Unknown operation: ${request.operation}")
        
        val response = httpClient.request(config.baseUrl + endpoint.path) {
            method = HttpMethod.parse(endpoint.method)
            
            // Add auth
            when (config.authType) {
                AuthType.OAUTH -> header("Authorization", "Bearer ${request.credentials.accessToken}")
                AuthType.API_KEY -> header("X-API-Key", request.credentials.apiKey)
                AuthType.BASIC -> basicAuth(request.credentials.username, request.credentials.password)
            }
            
            // Add body
            contentType(ContentType.Application.Json)
            setBody(request.params)
        }
        
        return UpstreamResult(
            success = response.status.isSuccess(),
            data = response.body()
        )
    }
}

data class RestUpstreamConfig(
    val name: String,
    val baseUrl: String,                      // e.g., "https://sap.example.com/odata/v4"
    val authType: AuthType,
    val vaultPath: String,
    val endpoints: Map<String, EndpointConfig>
)

data class EndpointConfig(
    val method: String,   // GET, POST, PUT, DELETE
    val path: String      // URL path with placeholders
)
```

### 4.6 Upstream Configuration

```yaml
# configs/upstreams.yaml

upstreams:
  # Docker MCP containers
  google_gmail:
    type: docker_mcp
    endpoint: "http://google-mcp:8080"
    vault_path: "secret/data/tenants/{tenant}/users/{user}/google"
    auth_type: oauth
    
  google_calendar:
    type: docker_mcp
    endpoint: "http://google-mcp:8080"
    vault_path: "secret/data/tenants/{tenant}/users/{user}/google"
    auth_type: oauth

  slack:
    type: docker_mcp
    endpoint: "http://slack-mcp:8080"
    vault_path: "secret/data/tenants/{tenant}/users/{user}/slack"
    auth_type: oauth

  stripe:
    type: docker_mcp
    endpoint: "http://stripe-mcp:8080"
    vault_path: "secret/data/tenants/{tenant}/api_keys/stripe"
    auth_type: api_key

  # Direct REST (no MCP)
  sap_s4hana:
    type: direct_rest
    base_url: "https://sap.example.com/sap/opu/odata"
    vault_path: "secret/data/tenants/{tenant}/api_keys/sap"
    auth_type: api_key
    endpoints:
      create_purchase_order:
        method: POST
        path: "/API_PURCHASEORDER_SRV/PurchaseOrder"
      get_vendor:
        method: GET
        path: "/API_BUSINESS_PARTNER/A_BusinessPartner('{vendor_id}')"
```

---

## 5. Configuration

### 5.1 Main Gateway Configuration

```yaml
# configs/gateway.yaml

server:
  host: "0.0.0.0"
  port: 8080
  mcp_transport: "http"  # or "stdio", "websocket"

auth:
  oidc:
    issuer_url: "https://keycloak.example.com/realms/noumena"
    client_id: "noumena-mcp-gateway"
    # client_secret from env: OIDC_CLIENT_SECRET
    
  token_validation:
    validate_delegation: true  # Check if delegation still valid
    cache_ttl: 60s             # Cache validated tokens

npl:
  url: "http://npl-engine:8080"
  timeout: 5s
  retry:
    max_attempts: 3
    backoff: 100ms

vault:
  url: "http://vault:8200"
  auth_method: "kubernetes"  # or "approle", "token"
  # For kubernetes auth:
  role: "noumena-gateway"
  # For approle:
  # role_id from env: VAULT_ROLE_ID
  # secret_id from env: VAULT_SECRET_ID
  
  paths:
    user_credentials: "secret/data/tenants/{{.TenantID}}/users/{{.UserID}}/{{.Service}}"
    tenant_api_keys: "secret/data/tenants/{{.TenantID}}/api_keys/{{.Service}}"

metrics:
  enabled: true
  port: 9090
  path: "/metrics"

logging:
  level: "info"
  format: "json"
  # IMPORTANT: Never log content, only metadata
  redact_fields: ["payload", "body", "content", "message"]
```

### 5.2 Integrations Configuration

```yaml
# configs/integrations.yaml

integrations:
  # Docker MCP containers (from Docker Hub MCP Catalog)
  gmail:
    type: docker_mcp
    display_name: "Gmail"
    description: "Send and read emails via Gmail"
    mcp:
      endpoint: "http://google-mcp:8080"
      vault_path: "secret/data/tenants/{{.TenantID}}/users/{{.UserID}}/google"
    policy:
      protocol: "EmailGovernance"
      metadata_fields:
        - recipient_domain  # Extracted for policy decisions
    
  slack:
    type: docker_mcp
    display_name: "Slack"
    description: "Send messages to Slack"
    mcp:
      endpoint: "http://slack-mcp:8080"
      vault_path: "secret/data/tenants/{{.TenantID}}/users/{{.UserID}}/slack"
    policy:
      protocol: "CommunicationGovernance"
      metadata_fields:
        - channel_type  # public, private, dm
        
  # Docker MCP container
  google_calendar:
    type: docker_mcp
    display_name: "Google Calendar"
    mcp:
      endpoint: "http://google-mcp:8080"  # Same container as Gmail
      vault_path: "secret/data/tenants/{{.TenantID}}/users/{{.UserID}}/google"
    policy:
      protocol: "CalendarGovernance"
      
  # Direct REST API (for services without MCP support)
  sap:
    type: direct_rest
    display_name: "SAP S/4HANA"
    rest:
      base_url: "https://sap.company.com/odata/v4"
      auth_type: oauth
      vault_path: "secret/data/tenants/{{.TenantID}}/users/{{.UserID}}/sap"
      endpoints:
        create_purchase_order:
          method: POST
          path: "/PurchaseOrders"
        get_purchase_order:
          method: GET
          path: "/PurchaseOrders('{{.order_id}}')"
        list_materials:
          method: GET
          path: "/Materials"
    policy:
      protocol: "SAPPurchaseOrderGovernance"
      metadata_fields:
        - vendor_id
        - total_amount
        - material_category
        
  # Direct MCP
  internal_docs:
    type: direct_mcp
    display_name: "Internal Documents"
    mcp:
      url: "http://docs-mcp:8080"
      transport: http
      auth_type: api_key
      api_key_vault_path: "secret/data/integrations/internal_docs"
    policy:
      protocol: "DocumentGovernance"
      
  # Custom internal API
  internal_crm:
    type: direct_rest
    display_name: "Internal CRM"
    rest:
      base_url: "https://crm.internal.company.com/api/v1"
      auth_type: api_key
      api_key_vault_path: "secret/data/tenants/{{.TenantID}}/api_keys/crm"
      endpoints:
        get_customer:
          method: GET
          path: "/customers/{{.customer_id}}"
        create_contact:
          method: POST
          path: "/contacts"
    policy:
      protocol: "CRMGovernance"
```

---

## 6. Keycloak Integration

### 6.1 Features to Leverage

| Feature | Use Case | Priority |
|---------|----------|----------|
| **Token Exchange (RFC 8693)** | Issue Actor Tokens for agents | High |
| **Client Scopes** | Define allowed services per agent | High |
| **Realm Roles** | Tenant isolation | High |
| **Authorization Services** | Fine-grained permissions (optional) | Medium |
| **User-Managed Access (UMA)** | User controls agent permissions | Medium |
| **Token Introspection** | Validate delegation status | High |
| **Admin API** | Programmatic delegation management | Medium |
| **Events/Audit** | Track delegation lifecycle | Low |

### 6.2 Token Exchange Setup

```
┌─────────────────────────────────────────────────────────────────────────────┐
│  KEYCLOAK TOKEN EXCHANGE FOR ACTOR TOKENS                                  │
│                                                                             │
│  1. User authenticates to Keycloak (gets user_token)                       │
│                                                                             │
│  2. User's app requests Actor Token for agent:                             │
│                                                                             │
│     POST /realms/noumena/protocol/openid-connect/token                     │
│     Content-Type: application/x-www-form-urlencoded                        │
│                                                                             │
│     grant_type=urn:ietf:params:oauth:grant-type:token-exchange             │
│     subject_token={user_token}                                              │
│     subject_token_type=urn:ietf:params:oauth:token-type:access_token       │
│     requested_token_type=urn:ietf:params:oauth:token-type:access_token     │
│     audience=noumena-mcp-gateway                                           │
│     scope=agent:jarvis-123 services:gmail,calendar                         │
│                                                                             │
│  3. Keycloak returns Actor Token with delegation claims                    │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 6.3 Keycloak Configuration

```json
// Realm Configuration (noumena realm)
{
  "realm": "noumena",
  "enabled": true,
  "tokenExchange": true,
  
  "clients": [
    {
      "clientId": "noumena-mcp-gateway",
      "enabled": true,
      "protocol": "openid-connect",
      "publicClient": false,
      "serviceAccountsEnabled": true,
      "authorizationServicesEnabled": true,
      
      "protocolMappers": [
        {
          "name": "noumena-claims",
          "protocol": "openid-connect",
          "protocolMapper": "oidc-usermodel-attribute-mapper",
          "config": {
            "claim.name": "noumena",
            "jsonType.label": "JSON",
            "multivalued": "false"
          }
        },
        {
          "name": "actor-claims",
          "protocol": "openid-connect",
          "protocolMapper": "oidc-token-exchange-actor-mapper",
          "config": {
            "claim.name": "act",
            "jsonType.label": "JSON"
          }
        }
      ]
    },
    
    {
      "clientId": "agent-jarvis",
      "description": "Jarvis AI Agent",
      "enabled": true,
      "protocol": "openid-connect",
      "publicClient": false,
      
      "defaultClientScopes": ["email", "profile"],
      "optionalClientScopes": ["gmail", "calendar", "slack", "sap"]
    }
  ],
  
  "clientScopes": [
    {
      "name": "gmail",
      "description": "Access to Gmail operations",
      "protocol": "openid-connect"
    },
    {
      "name": "calendar",
      "description": "Access to Calendar operations",
      "protocol": "openid-connect"
    }
  ]
}
```

### 6.4 Delegation Revocation

```go
// internal/auth/delegation.go

package auth

import (
    "context"
    "github.com/Nerzal/gocloak/v13"
)

type DelegationManager struct {
    keycloak *gocloak.GoCloak
    realm    string
}

// CheckDelegationValid verifies delegation hasn't been revoked
func (d *DelegationManager) CheckDelegationValid(ctx context.Context, delegationID string) (bool, error) {
    // Option 1: Token introspection
    result, err := d.keycloak.RetrospectToken(ctx, d.adminToken, delegationID, d.realm)
    if err != nil {
        return false, err
    }
    return *result.Active, nil
}

// RevokeDelegation allows user to revoke agent's access
func (d *DelegationManager) RevokeDelegation(ctx context.Context, userID, delegationID string) error {
    // Revoke all tokens for this delegation
    return d.keycloak.LogoutAllSessions(ctx, d.adminToken, d.realm, userID)
}
```

### 6.5 Keycloak Authorization Services (Optional)

For fine-grained permissions, leverage Keycloak's Authorization Services:

```json
// Resource definition
{
  "name": "gmail-service",
  "type": "service",
  "uris": ["/gmail/*"],
  "scopes": [
    {"name": "send"},
    {"name": "read"},
    {"name": "delete"}
  ]
}

// Policy: Only allow send to internal domains without approval
{
  "name": "internal-email-policy",
  "type": "js",
  "logic": "POSITIVE",
  "code": "
    var context = $evaluation.getContext();
    var attributes = context.getAttributes();
    var recipientDomain = attributes.getValue('recipient_domain').asString(0);
    var internalDomains = ['company.com', 'corp.company.com'];
    
    if (internalDomains.indexOf(recipientDomain) >= 0) {
      $evaluation.grant();
    }
  "
}
```

---

## 7. NPL Engine Integration

### 7.1 NPL API Contract

```go
// internal/policy/npl_client.go

package policy

// NPL API request
type NPLPolicyRequest struct {
    ProtocolName string            `json:"protocol_name"`
    Operation    string            `json:"operation"`
    Party        string            `json:"party"`       // Agent's identity
    OnBehalfOf   string            `json:"on_behalf_of"` // User's identity
    TenantID     string            `json:"tenant_id"`
    Arguments    map[string]interface{} `json:"arguments"`  // Metadata only!
}

// NPL API response
type NPLPolicyResponse struct {
    Allowed         bool   `json:"allowed"`
    RequestID       string `json:"request_id"`
    Reason          string `json:"reason,omitempty"`
    RequiresApproval bool  `json:"requires_approval"`
    ApprovalID      string `json:"approval_id,omitempty"`
    State           string `json:"state,omitempty"`
}
```

### 7.2 Gateway → NPL Flow

```go
// internal/policy/npl_client.go

func (c *NPLClient) CheckPolicy(ctx context.Context, req *PolicyRequest) (*PolicyDecision, error) {
    // Build NPL request with METADATA ONLY
    nplReq := &NPLPolicyRequest{
        ProtocolName: c.getProtocolName(req.Service),
        Operation:    mapOperationToPermission(req.Operation),
        Party:        req.AgentID,
        OnBehalfOf:   req.UserID,
        TenantID:     req.TenantID,
        Arguments:    req.Metadata,  // Metadata only, never content!
    }
    
    resp, err := c.httpClient.Post(ctx, "/api/v1/protocols/invoke", nplReq)
    if err != nil {
        return nil, err
    }
    
    var nplResp NPLPolicyResponse
    if err := json.Unmarshal(resp.Body, &nplResp); err != nil {
        return nil, err
    }
    
    return &PolicyDecision{
        Allowed:          nplResp.Allowed,
        RequestID:        nplResp.RequestID,
        Reason:           nplResp.Reason,
        RequiresApproval: nplResp.RequiresApproval,
        ApprovalID:       nplResp.ApprovalID,
    }, nil
}
```

### 7.3 NPL Protocol Pattern

```npl
// Example: EmailGovernance protocol invoked by Gateway

@api
protocol[enterprise, agent] EmailGovernance(
    var allowedExternalDomains: List<Text>,
    var dailyExternalLimit: Number,
    var requireApprovalForExternal: Boolean
) {
    initial state active;
    state pending_approval;
    
    private var dailyExternalCount: Number = 0;
    
    @api
    permission[agent] sendEmail(
        recipientDomain: Text  // Metadata only! Not email content.
    ) returns Text | active {
        var isExternal = !allowedExternalDomains.contains(recipientDomain);
        
        if (isExternal) {
            require(dailyExternalCount < dailyExternalLimit, "Daily limit exceeded");
            
            if (requireApprovalForExternal) {
                // Return pending, Gateway will handle
                become pending_approval;
                return "PENDING_APPROVAL";
            };
            
            dailyExternalCount = dailyExternalCount + 1;
        };
        
        return "ALLOWED";
    };
}
```

---

## 8. Vault Access Architecture

### 8.1 Design Decision: Executor Pattern (Enforced Separation)

After evaluating options, we choose the **Executor Pattern** for architectural enforcement:

```
┌─────────────────────────────────────────────────────────────────────────────┐
│  CHOSEN PATTERN: Gateway has NO Vault access. Executor is triggered by NPL.│
│                                                                             │
│  1. Agent → Gateway: Request with Actor Token                              │
│  2. Gateway validates token (Keycloak)                                     │
│  3. Gateway extracts metadata                                               │
│  4. Gateway → NPL: Policy check (metadata only)                            │
│                                                                             │
│  If DENIED: Gateway returns error                                          │
│                                                                             │
│  If ALLOWED:                                                                │
│  5. NPL → Executor: Notification with request details                      │
│  6. Executor → Vault: Fetch credentials                                    │
│  7. Executor → Upstream: Execute with credentials                          │
│  8. Executor → Gateway: Send result (via callback)                         │
│  9. Gateway → Agent: Return result                                         │
│                                                                             │
│  Gateway CANNOT access Vault. Only Executor can.                           │
│  Executor ONLY acts on NPL notifications.                                  │
│  NPL bypass is architecturally IMPOSSIBLE.                                 │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

**Why this pattern:**
- **Enforced**: Gateway physically cannot bypass NPL (no Vault credentials)
- **Clear separation**: Gateway routes, NPL decides, Executor acts
- **Auditable**: NPL logs decisions, Executor logs executions
- **No trust required**: Don't need to trust Gateway code for security

**Trade-off accepted:**
- Additional component (Executor)
- Async flow with callbacks
- Mitigated by: simpler security model, cleaner architecture

### 8.2 Vault Path Structure

```
secret/
└── data/
    └── tenants/
        └── {tenant_id}/
            ├── api_keys/           # Tenant-level API keys
            │   ├── stripe          # Stripe API key
            │   └── sap             # SAP API key
            │   └── internal_crm    # Internal service keys
            │
            └── users/
                └── {user_id}/
                    ├── gmail/      # User's Gmail OAuth tokens
                    │   ├── access_token
                    │   └── refresh_token
                    ├── calendar/   # User's Calendar tokens
                    ├── sap/        # User's SAP credentials
                    └── slack/      # User's Slack tokens
```

### 8.3 Gateway Flow (No Vault Access)

```go
// internal/gateway/handler.go
// NOTE: Gateway has NO Vault client. It cannot access secrets.

func (g *Gateway) HandleToolCall(ctx context.Context, req *ToolRequest) (*ToolResponse, error) {
    // 1. Validate Actor Token (Keycloak)
    claims, err := g.auth.ValidateActorToken(ctx, req.ActorToken)
    if err != nil {
        return nil, ErrUnauthorized
    }
    
    // 2. Check token scope
    if !claims.HasScope(req.Service, req.Operation) {
        return nil, ErrForbidden  // Scope not in token
    }
    
    // 3. Build policy request (METADATA ONLY, never content)
    policyReq := &PolicyRequest{
        TenantID:    claims.TenantID,
        UserID:      claims.DelegatedBy,
        AgentID:     claims.AgentID,
        Service:     req.Service,
        Operation:   req.Operation,
        Params:      req.Payload,              // Passed to Executor if allowed
        Metadata:    extractMetadata(req),     // For policy decision
        CallbackURL: g.callbackURL,            // Where Executor sends result
    }
    
    // 4. Call NPL - NPL will trigger Executor if allowed
    decision, err := g.npl.CheckAndExecute(ctx, policyReq)
    if err != nil {
        return nil, ErrPolicyCheckFailed
    }
    
    if !decision.Allowed {
        return nil, &PolicyDeniedError{Reason: decision.Reason}
    }
    
    if decision.RequiresApproval {
        return &ToolResponse{
            Status:     "pending_approval",
            ApprovalID: decision.ApprovalID,
        }, nil
    }
    
    // 5. Wait for result from Executor (via callback)
    // NPL has already notified Executor to execute
    result, err := g.waitForResult(ctx, decision.RequestID, 30*time.Second)
    if err != nil {
        return nil, err
    }
    
    return result, nil
}

// Gateway has NO vault client - this is intentional
// Gateway has NO upstream clients - Executor handles those
```

### 8.4 Executor Flow (Has Vault Access)

```go
// internal/executor/handler.go
// NOTE: Executor HAS Vault client. It's the only component that does.

type Executor struct {
    vault      *secrets.VaultClient    // Executor HAS Vault access
    router     *upstream.Router        // Executor HAS upstream access
    nplPubKey  *rsa.PublicKey          // To verify NPL signatures
}

func (e *Executor) HandleExecuteRequest(w http.ResponseWriter, r *http.Request) {
    var req ExecuteRequest
    json.NewDecoder(r.Body).Decode(&req)
    
    // 1. Verify request came from NPL (cryptographic signature)
    if !e.verifyNPLSignature(req.NPLSignature, req) {
        http.Error(w, "Unauthorized - invalid NPL signature", 401)
        return
    }
    
    // 2. Fetch credentials from Vault
    creds, err := e.vault.GetCredentials(
        r.Context(),
        req.TenantID,
        req.UserID,
        req.Service,
    )
    if err != nil {
        e.sendCallback(req.CallbackURL, req.RequestID, nil, err)
        return
    }
    
    // 3. Call upstream with credentials
    result, err := e.router.Route(r.Context(), &upstream.Request{
        Service:     req.Service,
        Operation:   req.Operation,
        Params:      req.Params,
        Credentials: creds,
    })
    
    // 4. Send result to Gateway (via callback)
    e.sendCallback(req.CallbackURL, req.RequestID, result, err)
    
    // Credentials are garbage collected after this function returns
}

func (e *Executor) verifyNPLSignature(sig string, req ExecuteRequest) bool {
    // Verify that the request was signed by NPL's private key
    data := fmt.Sprintf("%s:%s:%s:%s", req.RequestID, req.TenantID, req.UserID, req.Service)
    return crypto.VerifySignature(e.nplPubKey, []byte(data), sig)
}
```

### 8.5 NPL Notification to Executor

```npl
// NPL protocol sends notification to Executor when request is allowed

notification ExecuteRequest(
    requestId: Text,
    tenantId: Text,
    userId: Text,
    service: Text,
    operation: Text,
    params: Text,           // JSON-encoded parameters
    callbackUrl: Text,      // Where Executor sends result
    signature: Text         // Signed by NPL to prove authenticity
) returns Unit;

@api
permission[agent] sendEmail(recipientDomain: Text, params: Text) returns Text | active {
    // Policy checks
    require(dailyCount < dailyLimit, "Quota exceeded");
    require(!blockedDomains.contains(recipientDomain), "Domain blocked");
    
    // Generate request ID and signature
    var reqId = generateRequestId();
    var sig = signRequest(reqId, tenantId, userId, "gmail");
    
    // Notify Executor to execute
    notify ExecuteRequest(
        reqId,
        tenantId,
        userId,
        "gmail",
        "send",
        params,
        callbackUrl,
        sig
    );
    
    // Update state
    dailyCount = dailyCount + 1;
    
    return reqId;
};
```

### 8.6 Vault Authentication (Executor Only)

**Only Executor has Vault credentials. Gateway has none.**

Executor authenticates to Vault using one of:

**Option A: Kubernetes Auth (Recommended for K8s deployments)**
```yaml
# executor.yaml
vault:
  auth_method: kubernetes
  kubernetes:
    role: noumena-executor   # NOT gateway!
    # Uses service account token automatically
```

**Option B: AppRole (For non-K8s deployments)**
```yaml
# executor.yaml
vault:
  auth_method: approle
  approle:
    role_id: ${VAULT_ROLE_ID}
    secret_id: ${VAULT_SECRET_ID}
```

**Gateway has no Vault configuration at all:**
```yaml
# gateway.yaml
# NO vault section - Gateway cannot access Vault
```

### 8.7 Vault Policy (Executor Only)

```hcl
# Vault policy: noumena-executor

# Executor can read user credentials
path "secret/data/tenants/*/users/*" {
  capabilities = ["read"]
}

# Executor can read tenant-level API keys
path "secret/data/tenants/*/api_keys/*" {
  capabilities = ["read"]
}

# Executor can read service-specific credentials
path "secret/data/tenants/*/connections/*" {
  capabilities = ["read"]
}

# Executor can update tokens (for OAuth refresh)
path "secret/data/tenants/*/users/*" {
  capabilities = ["read", "update"]
}

# No policy for Gateway - it has no Vault access at all
```

### 8.8 Credential Lifecycle

```
┌─────────────────────────────────────────────────────────────────────────────┐
│  CREDENTIALS ARE TRANSIENT (in Executor only)                              │
│                                                                             │
│  1. Fetched from Vault into EXECUTOR memory (not Gateway)                 │
│  2. Used immediately for upstream call                                     │
│  3. Garbage collected after request completes                              │
│  4. NEVER written to disk, database, or logs                              │
│                                                                             │
│  Gateway never sees credentials at all.                                    │
│  If Executor restarts, credentials are refetched from Vault per-request.  │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 8.9 Token Refresh (OAuth Credentials)

When OAuth tokens expire, Executor refreshes them:

```go
// internal/executor/secrets/vault_client.go

func (v *VaultClient) GetCredentials(ctx context.Context, tenant, user, service string) (*Credentials, error) {
    creds, err := v.readFromVault(ctx, tenant, user, service)
    if err != nil {
        return nil, err
    }
    
    // Check if access token is expired or near expiry
    if creds.ExpiresAt.Before(time.Now().Add(5 * time.Minute)) {
        // Refresh using refresh_token
        newCreds, err := v.refreshOAuthToken(ctx, creds)
        if err != nil {
            return nil, err
        }
        
        // Store refreshed tokens back to Vault
        if err := v.writeToVault(ctx, tenant, user, service, newCreds); err != nil {
            // Log but continue - we have working tokens
            log.Warn("Failed to persist refreshed tokens", "error", err)
        }
        
        return newCreds, nil
    }
    
    return creds, nil
}
```

### 8.10 Docker MCP Credential Model

With Docker MCP containers, we store OAuth tokens in Vault and inject them into containers:

```
┌─────────────────────────────────────────────────────────────────────────────┐
│  DOCKER MCP CREDENTIAL FLOW                                                │
│                                                                             │
│  Vault stores:                                                              │
│  • User's OAuth tokens (per user, per service)                             │
│  • Service API keys (tenant-level, for services like Stripe)               │
│                                                                             │
│  Executor fetches:                                                          │
│  • User's access token from Vault                                          │
│  • Passes token to Docker MCP container via headers                        │
│  • Docker container calls the actual service (Google, Slack, etc.)         │
│                                                                             │
│  Security:                                                                  │
│  • Tokens never leave your infrastructure                                  │
│  • Docker containers are isolated (no network access to Vault)             │
│  • Executor is the only secret handler                                     │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

```yaml
# Vault structure for Docker MCP integration
secret/data/tenants/{tenant_id}/
├── users/
│   ├── user-a/
│   │   ├── google        # { "access_token": "ya29...", "refresh_token": "1//..." }
│   │   └── slack         # { "access_token": "xoxb-..." }
│   └── user-b/
│       └── google        # { "access_token": "ya29...", "refresh_token": "1//..." }
└── api_keys/
    ├── stripe            # { "api_key": "sk_live_..." }
    └── sap               # { "api_key": "sap_xxx" }
```

---

## 9. Security Model

### 9.1 Security Principles

| Principle | Implementation |
|-----------|----------------|
| **Zero Content Persistence** | Gateway/Executor never write content to disk/DB |
| **Gateway Has No Secrets** | Gateway has zero Vault access |
| **Executor Triggered by NPL** | Executor only acts on signed NPL notifications |
| **Architectural Enforcement** | NPL bypass is impossible, not just discouraged |
| **Tenant Isolation** | Tenant ID from signed token, not user input |
| **Audit Trail** | All decisions logged in NPL (metadata only) |
| **Token Revocation** | Keycloak introspection, short-lived tokens |

### 9.2 Threat Model

```
┌─────────────────────────────────────────────────────────────────────────────┐
│  THREAT: Malicious Agent                                                    │
│                                                                             │
│  Attack: Agent tries to access resources beyond its scope                  │
│                                                                             │
│  Mitigations:                                                               │
│  1. Actor Token scopes limit allowed services (Keycloak)                   │
│  2. NPL protocol validates business rules (dynamic, stateful)              │
│  3. Vault paths scoped to tenant/user/service                              │
│  4. Agent never communicates with Executor directly                        │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────────────┐
│  THREAT: Gateway Compromise                                                 │
│                                                                             │
│  Attack: Attacker gains access to Gateway process                          │
│                                                                             │
│  What's at risk:                                                            │
│  • Content in memory (transient)                                           │
│  • Actor tokens in flight                                                   │
│                                                                             │
│  What's NOT at risk:                                                        │
│  • Vault secrets (Gateway has NO Vault access)                             │
│  • Upstream credentials (only Executor has these)                          │
│  • Past content (never persisted)                                          │
│  • User master tokens (never in Gateway)                                   │
│                                                                             │
│  Key protection: Even if Gateway is compromised, attacker cannot           │
│  access Vault or call upstream with credentials.                           │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────────────┐
│  THREAT: NPL Bypass                                                         │
│                                                                             │
│  Attack: Gateway calls upstream without policy check                       │
│                                                                             │
│  Mitigation: ARCHITECTURALLY IMPOSSIBLE                                    │
│                                                                             │
│  1. Gateway has NO Vault credentials - cannot fetch secrets                │
│  2. Gateway has NO upstream clients - cannot call services                 │
│  3. Only Executor has Vault access and upstream clients                    │
│  4. Executor ONLY acts on NPL notifications (cryptographically signed)     │
│  5. There is no code path for Gateway to bypass NPL                        │
│                                                                             │
│  This is enforcement by architecture, not by code correctness.             │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────────────┐
│  THREAT: Executor Compromise                                                │
│                                                                             │
│  Attack: Attacker gains access to Executor process                         │
│                                                                             │
│  What's at risk:                                                            │
│  • Vault credentials (Executor has AppRole/K8s auth)                       │
│  • Upstream credentials in memory (transient)                              │
│  • Content in memory (transient)                                           │
│                                                                             │
│  Mitigations:                                                               │
│  • Executor is a minimal service (small attack surface)                    │
│  • Executor has no external API (only receives NPL notifications)          │
│  • Vault credentials are short-lived                                       │
│  • Network isolation (Executor only reachable by NPL)                      │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 9.3 Component Trust Levels

| Component | Has Vault Access | Has Upstream Access | Receives External Input | Trust Level |
|-----------|------------------|---------------------|------------------------|-------------|
| Gateway | ❌ No | ❌ No | ✅ Yes (agents) | Lower |
| NPL Engine | ❌ No | ❌ No | ✅ Yes (Gateway) | Medium |
| Executor | ✅ Yes | ✅ Yes | ⚠️ Only NPL | Higher |
| Vault | N/A | N/A | ⚠️ Only Executor | Highest |

The components with highest risk exposure (Gateway) have the least access.
The components with most access (Executor) have the least exposure.

### 9.4 Access Control Summary

| Check | Where | Enforces |
|-------|-------|----------|
| Token Signature | Gateway | Token not forged |
| Token Expiry | Gateway | Token not expired |
| Token Scope | Gateway | Agent has capability (static) |
| NPL Policy | NPL Engine | Business rules pass (dynamic) |
| NPL Signature | Executor | Request came from NPL |
| Vault Path | Vault | Tenant/user isolation |

---

## 10. Implementation Plan

### 10.1 Phase 1: Core Gateway + Executor (Weeks 1-4)

**Gateway:**
- [ ] Project scaffolding (Go modules, structure)
- [ ] MCP server implementation (agent-facing)
- [ ] OIDC token validation (Keycloak)
- [ ] NPL client (policy checks + execution trigger)
- [ ] Callback handler (receive results from Executor)
- [ ] Configuration loading

**Executor:**
- [ ] Execution handler (receives NPL notifications)
- [ ] NPL signature verification
- [ ] Vault client integration
- [ ] Single upstream (direct REST)
- [ ] Callback sender (send results to Gateway)

**NPL Integration:**
- [ ] ExecuteRequest notification definition
- [ ] Request signing in NPL

**Deliverable**: Gateway + Executor that validates tokens, checks NPL policy, executes via Executor.

### 10.2 Phase 2: Upstream Integrations (Weeks 5-7)

- [ ] Direct REST upstream (SAP, internal APIs)
- [ ] Direct MCP upstream
- [ ] Additional Docker MCP containers (Stripe, Neo4j, etc.)
- [ ] Docker MCP container orchestration
- [ ] Configuration-driven routing
- [ ] OAuth token refresh in Executor

**Deliverable**: Full routing to multiple upstream types via Executor.

### 10.3 Phase 3: Production Hardening (Weeks 8-9)

- [ ] Metrics (Prometheus) - both Gateway and Executor
- [ ] Health checks
- [ ] Graceful shutdown
- [ ] Rate limiting (Gateway level)
- [ ] Error handling and retries
- [ ] Logging (with content redaction)
- [ ] Docker packaging (Gateway + Executor images)
- [ ] Kubernetes manifests with NetworkPolicy
- [ ] NPL key rotation mechanism

**Deliverable**: Production-ready Gateway + Executor.

### 10.4 Phase 4: Advanced Features (Future)

- [ ] Actor Token delegation UI
- [ ] Multi-tenant SaaS mode
- [ ] Keycloak Authorization Services integration
- [ ] Approval workflow handling (async approvals)
- [ ] OAuth connection management UI
- [ ] Executor horizontal scaling
- [ ] Request queuing (for high load)

---

## 11. API Reference

### 11.1 MCP Tools (Agent-Facing)

Tools are dynamically registered based on configuration. Example:

```json
// List tools response
{
  "tools": [
    {
      "name": "gmail_send",
      "description": "Send an email via Gmail",
      "inputSchema": {
        "type": "object",
        "properties": {
          "to": {"type": "string"},
          "subject": {"type": "string"},
          "body": {"type": "string"}
        },
        "required": ["to", "subject", "body"]
      }
    },
    {
      "name": "sap_create_purchase_order",
      "description": "Create a purchase order in SAP",
      "inputSchema": {
        "type": "object",
        "properties": {
          "vendor_id": {"type": "string"},
          "items": {"type": "array"}
        }
      }
    }
  ]
}
```

### 11.2 Admin API

```
POST /admin/integrations
  → Add/update integration configuration

GET /admin/integrations
  → List configured integrations

POST /admin/connections/{user_id}
  → Initiate OAuth connection for a user (redirects to provider)

DELETE /admin/connections/{user_id}/{service}
  → Disconnect a user from a service

GET /admin/health
  → Health check

GET /metrics
  → Prometheus metrics
```

---

## Appendix A: Deployment

### Docker

**Gateway (No Vault access):**
```dockerfile
# Dockerfile.gateway
FROM golang:1.22-alpine AS builder
WORKDIR /app
COPY go.mod go.sum ./
RUN go mod download
COPY . .
RUN CGO_ENABLED=0 go build -o gateway ./cmd/gateway

FROM alpine:3.19
RUN apk add --no-cache ca-certificates
COPY --from=builder /app/gateway /gateway
COPY configs/gateway.yaml /configs/
ENTRYPOINT ["/gateway"]
CMD ["--config", "/configs/gateway.yaml"]
```

**Executor (Has Vault access):**
```dockerfile
# Dockerfile.executor
FROM golang:1.22-alpine AS builder
WORKDIR /app
COPY go.mod go.sum ./
RUN go mod download
COPY . .
RUN CGO_ENABLED=0 go build -o executor ./cmd/executor

FROM alpine:3.19
RUN apk add --no-cache ca-certificates
COPY --from=builder /app/executor /executor
COPY configs/executor.yaml /configs/
ENTRYPOINT ["/executor"]
CMD ["--config", "/configs/executor.yaml"]
```

### Docker Compose (Local Development)

```yaml
version: '3.8'
services:
  gateway:
    build:
      context: .
      dockerfile: deployments/docker/Dockerfile.gateway
    ports:
      - "8080:8080"
    environment:
      - NPL_URL=http://npl-engine:8080
      - OIDC_ISSUER=http://keycloak:8080/realms/noumena
      - CALLBACK_URL=http://gateway:8080/callback
    # NOTE: No VAULT_* environment variables - Gateway has no Vault access
    depends_on:
      - npl-engine
      - keycloak
  
  executor:
    build:
      context: .
      dockerfile: deployments/docker/Dockerfile.executor
    environment:
      - VAULT_ADDR=http://vault:8200
      - VAULT_ROLE_ID=${VAULT_ROLE_ID}
      - VAULT_SECRET_ID=${VAULT_SECRET_ID}
      - NPL_PUBLIC_KEY_PATH=/keys/npl-public.pem
    volumes:
      - ./keys:/keys:ro
    # NOTE: Executor is NOT exposed externally - only NPL can reach it
    depends_on:
      - vault
  
  npl-engine:
    image: noumena/npl-engine:latest
    ports:
      - "8081:8080"
    environment:
      - EXECUTOR_URL=http://executor:8080/execute
      - NPL_PRIVATE_KEY_PATH=/keys/npl-private.pem
    volumes:
      - ./keys:/keys:ro
  
  vault:
    image: hashicorp/vault:latest
    ports:
      - "8200:8200"
    environment:
      - VAULT_DEV_ROOT_TOKEN_ID=dev-token
  
  keycloak:
    image: quay.io/keycloak/keycloak:latest
    ports:
      - "8082:8080"
    command: start-dev
```

### Kubernetes

**Gateway Deployment (No Vault access):**
```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: noumena-gateway
spec:
  replicas: 3
  template:
    spec:
      # NOTE: No serviceAccountName for Vault - Gateway has no Vault access
      containers:
      - name: gateway
        image: noumena/mcp-gateway:latest
        ports:
        - containerPort: 8080
        env:
        - name: NPL_URL
          value: "http://npl-engine:8080"
        - name: OIDC_ISSUER
          value: "https://keycloak.example.com/realms/noumena"
        - name: OIDC_CLIENT_SECRET
          valueFrom:
            secretKeyRef:
              name: gateway-secrets
              key: oidc-client-secret
        - name: CALLBACK_URL
          value: "http://noumena-gateway:8080/callback"
        # NO VAULT_* environment variables
        resources:
          requests:
            memory: "64Mi"
            cpu: "100m"
```

**Executor Deployment (Has Vault access):**
```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: noumena-executor
spec:
  replicas: 2
  template:
    spec:
      serviceAccountName: noumena-executor  # For Vault K8s auth
      containers:
      - name: executor
        image: noumena/mcp-executor:latest
        ports:
        - containerPort: 8080
        env:
        - name: VAULT_ADDR
          value: "http://vault:8200"
        - name: VAULT_AUTH_METHOD
          value: "kubernetes"
        - name: VAULT_ROLE
          value: "noumena-executor"
        volumeMounts:
        - name: npl-keys
          mountPath: /keys
          readOnly: true
        resources:
          requests:
            memory: "64Mi"
            cpu: "100m"
      volumes:
      - name: npl-keys
        secret:
          secretName: npl-signing-keys
---
# Network policy: Executor only reachable by NPL
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: executor-ingress
spec:
  podSelector:
    matchLabels:
      app: noumena-executor
  ingress:
  - from:
    - podSelector:
        matchLabels:
          app: npl-engine
    ports:
    - port: 8080
```

---

## Appendix B: Docker MCP Container Security

Docker MCP containers provide a secure, isolated way to connect to external services:

```
┌─────────────────────────────────────────────────────────────────────────────┐
│  ✓  SECURITY BENEFITS OF DOCKER MCP                                        │
│                                                                             │
│  • Tokens stay in YOUR Vault (never sent to third parties)                │
│  • Containers are sandboxed (no host access)                               │
│  • Cryptographic signatures verify container authenticity                  │
│  • SBOMs (Software Bill of Materials) for auditing                        │
│  • You control updates and rollbacks                                       │
│  • No external dependencies or middlemen                                   │
│                                                                             │
│  Docker MCP Catalog certifications:                                        │
│  • Verified publisher signatures                                           │
│  • Automated security scanning                                             │
│  • Regularly updated with CVE fixes                                        │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### Container Isolation

```yaml
# Kubernetes NetworkPolicy for MCP containers
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: mcp-container-isolation
spec:
  podSelector:
    matchLabels:
      type: mcp-container
  egress:
    # Only allow outbound to their respective services
    - to:
        - ipBlock:
            cidr: 0.0.0.0/0
      ports:
        - port: 443
          protocol: TCP
  ingress:
    # Only allow inbound from Executor
    - from:
        - podSelector:
            matchLabels:
              app: noumena-executor
      ports:
        - port: 8080
```

---

## Change Log

| Version | Date | Changes |
|---------|------|---------|
| 1.0 Draft | 2026-02-01 | Initial specification |
| 1.1 Draft | 2026-02-01 | Added: Scopes vs. Policy clarification (Section 3.0), Vault Access Architecture (Section 8) |
| 1.2 Draft | 2026-02-01 | Major: Executor pattern for architectural enforcement of NPL policy. Gateway has NO Vault access. Executor triggered by NPL notifications. |
| 1.3 Draft | 2026-02-01 | **Language change**: Go → Kotlin with Ktor. **Upstream change**: Removed Zapier, adopted Docker MCP Catalog ecosystem. Added Kotlin project structure and Gradle dependencies. Updated all code examples to Kotlin. |
