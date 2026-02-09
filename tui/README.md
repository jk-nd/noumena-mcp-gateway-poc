# NOUMENA MCP Gateway Wizard

Interactive CLI for managing MCP Gateway services, users, and credentials. Powered by [@clack/prompts](https://github.com/natemoo-re/clack).

## Features

- **Service management** - Add, enable/disable, remove services with NPL sync
- **Tool management** - Enable/disable individual tools per service
- **User management** - Register/deregister Keycloak users, grant/revoke tool access
- **Credential management** - Store secrets in Vault, configure tenant-level or user-level scopes
- **Container control** - Pull Docker images, manage HTTP service containers
- **Docker Hub search** - Find and add MCP servers from the `mcp/*` namespace
- **NPL-first operations** - All state changes write to NPL first, then update services.yaml as persistent cache
- **Real-time status** - Gateway connection, container, and NPL sync indicators

## Status Indicators

| Symbol | Meaning |
|--------|---------|
| `●` | Service/tool enabled |
| `–` | Service/tool disabled |
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
| **Configuration Manager** | View, edit, backup, import services.yaml |
| **Quit** | Exit the wizard |

## Service Actions

When you select a service:

| Action | Description |
|--------|-------------|
| **Enable/Disable** | Toggle service (syncs with NPL policy) |
| **Pull image** | Download Docker image (if not pulled) |
| **Start/Stop container** | Control container (HTTP services only) |
| **Discover tools** | Query service for available tools (Docker and NPM/command-based) |
| **Manage tools** | Enable/disable individual tools |
| **View details** | Show service configuration |
| **Delete** | Remove service (removes from NPL and config) |

## Tool Management

When enabling a service, you're prompted to select which tools to enable:

1. Choose **Enable tools** or **Disable tools**
2. Use **SPACE** to select/deselect tools
3. Press **ENTER** to apply changes

Or use **Enable all** / **Disable all** for bulk operations.

### NPL-First Sync

When you enable/disable a service or toggle individual tools, the TUI automatically:
1. Writes the change to NPL (source of truth) first
2. Updates `services.yaml` (persistent cache) on success
3. Calls `POST /admin/services/reload` on the Gateway (best-effort)

If the NPL write fails, `services.yaml` is unchanged and an error is shown. The Gateway reload is best-effort — if it fails (e.g. Gateway is down), changes take effect on next Gateway restart.

## Default Behavior

- **New services** are disabled by default
- **New tools** are disabled by default
- You must explicitly enable what you want to use

## Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `SERVICES_CONFIG_PATH` | `../configs/services.yaml` | Path to services config |
| `GATEWAY_URL` | `http://localhost:8000` | Gateway URL |
| `NPL_URL` | `http://localhost:12000` | NPL Engine URL |
| `KEYCLOAK_URL` | `http://localhost:11000` | Keycloak URL |
| `KEYCLOAK_REALM` | `mcpgateway` | Keycloak realm |
| `KEYCLOAK_CLIENT_ID` | `mcpgateway` | Keycloak client ID |
| `VAULT_ADDR` | `http://localhost:8200` | Vault server URL |
| `VAULT_TOKEN` | (none) | Vault access token |
| `CREDENTIALS_CONFIG_PATH` | `../configs/credentials.yaml` | Path to credentials config |

## Architecture

```
tui/
├── src/
│   ├── cli.ts              # Main wizard (Clack-based)
│   └── lib/
│       ├── config.ts       # YAML config read/write
│       └── api.ts          # Gateway, NPL, Docker Hub APIs
├── package.json
└── tsconfig.json
```

## How It Works

1. **Config File**: Services are defined in `configs/services.yaml`
2. **Gateway**: Reads config to know which tools to expose
3. **NPL Policy**: Controls which services are allowed (enable = allowed, disable = denied)
4. **Wizard**: Manages all three layers through a unified interface

## NPL Integration

All TUI operations follow the **NPL-first** pattern: NPL (source of truth) is written first, then `services.yaml` is updated as a persistent cache. If the NPL write fails, `services.yaml` remains unchanged.

When you enable a service:
- Service is registered in NPL ServiceRegistry
- A per-service ToolPolicy instance is created (if it doesn't already exist)
- Enabled tools are added to the ToolPolicy's allowed set

When you disable a service:
- Service is removed from NPL ServiceRegistry
- Requests to that service will be denied by policy

When you manage users:
- Users are registered/deregistered in NPL UserRegistry
- Tool access is granted/revoked via NPL UserToolAccess
- Changes are synced to Keycloak for authentication

Tool-level enabling syncs with ToolPolicy -- only explicitly enabled tools pass the policy check.

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
- For services requiring credentials, the Gateway injects them as `-e KEY=VALUE` Docker flags

**What this means:**
- For Docker services: pull the image and enable the service
- For NPM services: just enable the service (no image pull needed)
- The Gateway handles all container lifecycle management
- No need to worry about starting/stopping containers

### Tool Discovery

The TUI can discover tools from any STDIO service:

- **Docker services**: Runs the Docker image and queries via MCP JSON-RPC handshake
- **NPM/command services**: Spawns the command with credentials (fetched transiently from Vault) and queries via the same handshake

For services requiring credentials (e.g., Google Gemini needs `GEMINI_API_KEY`), the TUI fetches the API key from Vault at discovery time, holds it in memory only for the duration of the discovery, and passes it as an environment variable to the spawned process.

### HTTP Services (Less Common)

For HTTP-based MCP services:
- **▶ Running**: Container is currently running
- **■ Stopped**: Image is pulled but container is stopped
- Containers run as long-lived services
- Start/stop controls are available in the TUI
- Containers expose HTTP endpoints for the Gateway to call
