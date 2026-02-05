# NOUMENA MCP Gateway Wizard

Interactive CLI for managing MCP Gateway services. Powered by [@clack/prompts](https://github.com/natemoo-re/clack).

## Features

- **Service-centric navigation** - Services displayed as main menu items with status indicators
- **Enable/disable services** - Updates config and syncs with NPL ServiceRegistry
- **Multi-select tool management** - Use SPACE to select tools, ENTER to apply
- **Container control** - Pull, start, stop Docker containers for MCP servers
- **Search Docker Hub** - Find and add MCP servers with search functionality
- **Add custom images** - Support for local or private registry Docker images
- **Safe deletion** - Stops container and removes from NPL before deleting
- **Real-time status** - Gateway connection and container status indicators

## Status Indicators

| Symbol | Meaning |
|--------|---------|
| `✓` | Service/tool enabled |
| `–` | Service/tool disabled |
| `▶` | Container running |
| `■` | Container stopped (image exists) |
| `·` | Image not pulled |

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
| **Reload config** | Reload services.yaml from disk |
| **Reload Gateway** | Tell Gateway to reload its configuration |
| **Quit** | Exit the wizard |

## Service Actions

When you select a service:

| Action | Description |
|--------|-------------|
| **Enable/Disable** | Toggle service (syncs with NPL policy) |
| **Pull image** | Download Docker image (if not pulled) |
| **Start/Stop container** | Control the Docker container |
| **View logs** | Show last 30 lines of container logs |
| **Manage tools** | Enable/disable individual tools |
| **View details** | Show service configuration |
| **Delete** | Remove service (stops container, removes from NPL) |

## Tool Management

When enabling a service, you're prompted to select which tools to enable:

1. Choose **Enable tools** or **Disable tools**
2. Use **SPACE** to select/deselect tools
3. Press **ENTER** to apply changes

Or use **Enable all** / **Disable all** for bulk operations.

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

When you enable a service:
- Service is added to NPL ServiceRegistry's allowed list
- Requests to that service will be approved by policy

When you disable a service:
- Service is removed from NPL ServiceRegistry's allowed list
- Requests to that service will be denied by policy

Tool-level enabling only affects Gateway routing (which tools appear in `tools/list`), not NPL policy.
