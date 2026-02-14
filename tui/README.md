# NOUMENA MCP Gateway Wizard

Interactive CLI for managing MCP Gateway services, users, and policy. Powered by [@clack/prompts](https://github.com/natemoo-re/clack).

## Features

- **Service management** - Add, enable/disable, suspend/resume, remove services via PolicyStore
- **Tool management** - Enable/disable individual tools per service
- **User management** - Grant/revoke tool access per Keycloak user (grants-only model)
- **Emergency controls** - Suspend services, revoke all user access instantly
- **Container control** - Pull Docker images, manage HTTP service containers
- **Docker Hub search** - Find and add MCP servers from the `mcp/*` namespace
- **PolicyStore-first** - All state changes write to the NPL PolicyStore singleton (single source of truth)
- **Import/Export** - Bulk import from `services.yaml`, export PolicyStore snapshots to YAML
- **Real-time status** - Gateway connection, container, and PolicyStore indicators

## Status Indicators

| Symbol | Meaning |
|--------|---------|
| `●` | Service/tool enabled |
| `–` | Service/tool disabled |
| `⏸` | Service suspended (emergency) |
| `■` | Docker image pulled and ready (green) |
| `·` | Docker image not pulled |
| `▶` | Container running (HTTP services only) |

## Prerequisites

- Node.js 18+
- Docker (for container management)
- MCP Gateway running (for remote operations)

## Installation

```bash
cd tui
npm install
```

## Usage

```bash
# Start the wizard
npm start

# Development mode (auto-reload)
npm run dev
```

## Navigation

- **Arrow keys** - Navigate menus
- **Enter** - Select option
- **Space** - Toggle selection (in multi-select)
- **Ctrl+C** - Cancel/quit

## Main Menu

Select a service directly to manage it, or use these actions:

| Action | Description |
|--------|-------------|
| **+ Search Docker Hub** | Find and add MCP servers from the `mcp/*` namespace |
| **+ Add custom image** | Add local or private registry Docker images |
| **Import / Export** | Import YAML to PolicyStore, export snapshots, create backups |
| **Quit** | Exit the wizard |

## Service Actions

When you select a service:

| Action | Description |
|--------|-------------|
| **Enable/Disable** | Toggle service availability in PolicyStore |
| **Suspend/Resume** | Emergency kill switch (blocks all tool calls instantly) |
| **Pull image** | Download Docker image (if not pulled) |
| **Start/Stop container** | Control container (HTTP services only) |
| **Discover tools** | Query service for available tools (Docker and NPM/command-based) |
| **Manage tools** | Enable/disable individual tools |
| **View details** | Show service configuration |
| **Delete** | Remove service from PolicyStore |

## Tool Management

When managing tools for a service:

1. Choose **Enable tool** (enter tool name) or **Disable tools** (multi-select)
2. For disable: use **SPACE** to select/deselect tools
3. Press **ENTER** to apply changes

Changes are written directly to the PolicyStore catalog.

## User Management

Users are managed through a **grants-only** model:

- Users are sourced from **Keycloak** (identity provider)
- Tool access is controlled via **PolicyStore grants** (authorization)
- No separate registration step — grant tools directly to any Keycloak user

| Action | Description |
|--------|-------------|
| **Grant tools** | Give a user access to specific tools on a service |
| **Grant all tools** | Wildcard access to all tools on a service |
| **Revoke tools** | Remove specific tool access |
| **Revoke service** | Remove all access to a service |
| **Emergency revoke** | Instantly revoke ALL access for a user (fail-closed) |
| **Reinstate** | Restore access for a previously revoked user |

## Default Behavior

- **New services** are disabled by default
- **New tools** must be explicitly enabled
- You must explicitly enable what you want to use

## Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `SERVICES_CONFIG_PATH` | `../configs/services.yaml` | Path to services config (import/export) |
| `GATEWAY_URL` | `http://localhost:8000` | Gateway URL |
| `NPL_URL` | `http://localhost:12000` | NPL Engine URL |
| `KEYCLOAK_URL` | `http://localhost:11000` | Keycloak URL |
| `KEYCLOAK_REALM` | `mcpgateway` | Keycloak realm |
| `KEYCLOAK_CLIENT_ID` | `mcpgateway` | Keycloak client ID |

## Architecture

```
tui/
├── src/
│   ├── cli.ts              # Main wizard (Clack-based)
│   └── lib/
│       ├── config.ts       # YAML import/export utility
│       └── api.ts          # PolicyStore, Keycloak, Docker APIs
├── package.json
└── tsconfig.json
```

## How It Works

1. **PolicyStore**: NPL singleton that holds all policy data (services, tools, grants, revocations)
2. **Bundle Server**: Reads PolicyStore and generates OPA policy bundles
3. **OPA/Envoy**: Enforces policy at the Gateway proxy layer
4. **Keycloak**: Identity provider (who exists)
5. **Wizard**: Manages PolicyStore and Keycloak through a unified interface

```
┌─────────┐     ┌──────────────┐     ┌───────────────┐     ┌─────┐
│   TUI   │────▶│  PolicyStore │────▶│ Bundle Server │────▶│ OPA │
│ (admin) │     │  (NPL)       │     │               │     │     │
└─────────┘     └──────────────┘     └───────────────┘     └─────┘
     │                                                        │
     ▼                                                        ▼
┌──────────┐                                           ┌───────────┐
│ Keycloak │                                           │  Gateway  │
│ (users)  │                                           │  (Envoy)  │
└──────────┘                                           └───────────┘
```

## PolicyStore Integration

All TUI operations follow the **PolicyStore-first** pattern: the NPL PolicyStore singleton is the single source of truth for all policy data. `services.yaml` serves only as an import/export format for bulk configuration.

When you add a service:
1. Service is registered in the PolicyStore catalog (`registerService`)
2. Metadata is set (displayName, type, command, etc.) via `setServiceMetadata`
3. Discovered tools are enabled in the catalog (`enableTool`)
4. Service is enabled when ready (`enableService`)

When you manage users:
- Tool access is granted/revoked via PolicyStore grants (`grantTool`, `revokeTool`)
- Keycloak manages user identity (authentication)
- PolicyStore manages tool access (authorization)

The bundle server reads PolicyStore in a single `getPolicyData()` call and generates OPA bundles. Policy enforcement happens at the Envoy proxy layer via OPA — the Gateway itself never makes policy decisions.

## Container Management

The wizard shows Docker image status for MCP services:

- **■ Ready**: Docker image is pulled and ready to use
- **· Not pulled**: Image needs to be pulled

### STDIO Services (Most Common)

Most MCP services use STDIO transport, including both Docker-based and NPM-based servers:

**Docker-based examples:**
- `mcp/duckduckgo`
- `mcp/github`
- `mcp/slack`
- `mcp/fetch`

**NPM-based examples (run via Docker-wrapped npx):**
- `@houtini/gemini-mcp` (Google Gemini)
- Other npm MCP packages

NPM-based services use the pattern `docker run -i --rm node:22-slim npx -y <package>` so they run inside a Docker container with Node.js available, even though the Gateway container itself has no Node.js runtime.

**How STDIO services work:**
- Containers are **ephemeral** and expect stdin/stdout communication
- The Gateway **spawns containers on-demand** when tools are called
- Containers automatically exit when the request completes
- **You cannot manually start/stop STDIO containers** - they require an active stdin/stdout pipe
- The TUI does not show start/stop options for STDIO services

**What this means:**
- For Docker services: pull the image and enable the service
- For NPM services: just enable the service (no image pull needed)
- The Gateway handles all container lifecycle management
- No need to worry about starting/stopping containers

### Tool Discovery

The TUI can discover tools from any STDIO service:

- **Docker services**: Runs the Docker image and queries via MCP JSON-RPC handshake
- **NPM/command services**: Spawns the command and queries via the same handshake

### HTTP Services (Less Common)

For HTTP-based MCP services:
- **▶ Running**: Container is currently running
- **■ Stopped**: Image is pulled but container is stopped
- Containers run as long-lived services
- Start/stop controls are available in the TUI
- Containers expose HTTP endpoints for the Gateway to call
