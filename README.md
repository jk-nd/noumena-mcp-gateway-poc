# Noumena MCP Gateway

A policy-enforced MCP (Model Context Protocol) gateway that enables AI agents to access external services through NPL governance.

## Architecture

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                           NOUMENA MCP GATEWAY SYSTEM                        │
│                                                                             │
│  Agent ──MCP──► Gateway ──HTTP──► NPL Engine ──HTTP Bridge──► Executor     │
│                    ▲                                              │         │
│                    └──────────── callback ────────────────────────┘         │
│                                                                             │
│  KEY SECURITY PROPERTIES:                                                   │
│  • Gateway has NO Vault access                                              │
│  • Only Executor has Vault access                                           │
│  • Executor protected by network isolation (only NPL can reach it)          │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

## Components

| Component | Role | Vault Access |
|-----------|------|--------------|
| **Gateway** | MCP server for agents, token validation, policy checks | ❌ No |
| **Executor** | Fetches secrets, calls upstream services | ✅ Yes |
| **NPL Engine** | Policy evaluation, triggers Executor via HTTP Bridge | ❌ No |

## Quick Start

### Prerequisites

- JDK 17+
- Docker & Docker Compose
- Gradle 8.x

### Build

```bash
./gradlew build
```

### Run with Docker Compose

```bash
cd deployments
docker-compose up -d
```

### Test

```bash
# Health check
curl http://localhost:8080/health

# List tools (requires auth token)
curl -H "Authorization: Bearer <token>" http://localhost:8080/mcp/tools
```

## Configuration

### Gateway (`configs/gateway.yaml`)

```yaml
server:
  port: 8080

oidc:
  issuer: http://keycloak:8080/realms/noumena

npl:
  url: http://npl-engine:8080

policy:
  mode: required  # required | optional | audit_only
```

### Executor (`configs/executor.yaml`)

```yaml
server:
  port: 8081

vault:
  address: http://vault:8200
  auth_method: token  # token | kubernetes | approle
```

### Upstreams (`configs/upstreams.yaml`)

```yaml
upstreams:
  google_gmail:
    type: docker_mcp
    endpoint: http://google-mcp:8080
    vault_path: "secret/data/tenants/{tenant}/users/{user}/google"

  sap:
    type: direct_rest
    base_url: https://sap.example.com/odata/v4
```

## Security Model

### Network Isolation

The Executor is protected by network isolation, not cryptographic signatures:

- **Docker**: Executor is not exposed to the host network
- **Kubernetes**: NetworkPolicy restricts ingress to NPL Engine only

### Token Hierarchy

```
User's Master JWT (Keycloak)     → Stays in browser, NEVER given to agents
         │
         │ Token Exchange (RFC 8693)
         ▼
Actor Token (Delegated JWT)      → Given to agent, scoped to specific services
         │
         │ Policy Check
         ▼
NPL Evaluation                   → Dynamic, stateful business rules
```

## Project Structure

```
noumena-mcp-gateway/
├── gateway/           # MCP server (NO Vault access)
├── executor/          # Secret handler (HAS Vault access)
├── shared/            # Shared models and utilities
├── configs/           # YAML configuration files
├── deployments/       # Docker and Kubernetes files
└── docs/              # Specification and documentation
```

## Documentation

- [Full Specification](docs/SPEC.md)

## License

Copyright 2025 Noumena Digital AG. All rights reserved.
