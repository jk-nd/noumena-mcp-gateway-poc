# Packaging & Delivery Roadmap

## Target Audiences

### OpenClaw Community
- Open-source the gateway, credential proxy, TUI, configs, and NPL protocol definitions
- NPL Engine ships as a freely available Docker container (closed-source binary, like Keycloak or Vault)
- Community can write and deploy custom `.npl` protocols for governance logic

### Enterprise
- Full stack with support, SLAs, managed hosting
- Advanced NPL features, audit trails, multi-tenant governance

---

## All-in-One Docker Image

A single Docker image containing all components for zero-config developer experience:

```bash
docker run -p 8000:8000 noumena/mcp-gateway
```

### What's Inside

| Component | Runtime | Mode |
|-----------|---------|------|
| Gateway | JVM (Kotlin/Ktor) | Standard |
| Credential Proxy | JVM (Kotlin/Ktor) | Standard |
| NPL Engine | JVM | Embedded H2 database |
| Keycloak | JVM | Dev mode with embedded H2 |
| Vault | Go binary | File backend |

Process management via `s6-overlay` or `supervisord`.

### First-Run Experience

```
$ docker run -p 8000:8000 -v mcp-data:/data noumena/mcp-gateway
MCP Gateway ready at http://localhost:8000/mcp
API Key: nmc-xxxxxxxxxxxxxxxx
TUI:     docker exec -it <id> mcp-wizard
```

- Bootstraps Keycloak realm, admin user, and NPL protocols on first run
- Generates an initial API key and prints it
- All state persisted to the volume

### Persistence Tiers

| Mode | Storage | Use Case |
|------|---------|----------|
| No volume (`-v` omitted) | tmpfs / in-memory | Quick demo, CI, ephemeral testing |
| Named volume (`-v mcp-data:/data`) | File-backed H2 + Vault file storage | Development, daily use, OpenClaw |
| `docker-compose.yml` | PostgreSQL, production Vault, separate containers | Production, enterprise |

### Internal Storage Layout

```
/data/
├── vault/          # Vault file backend (secrets, API keys)
├── keycloak/       # Keycloak H2 database (users, realm config)
├── npl/            # NPL Engine H2 database (protocol instances, policies)
└── configs/        # services.yaml, credentials.yaml
```

---

## Dev-to-Production Migration

When moving from the all-in-one dev image to a production docker-compose deployment:

| What | Action | Effort |
|------|--------|--------|
| Service definitions | Copy `services.yaml` | Copy a file |
| Tool policies | NPL export → import | One API call |
| User access rules | NPL export → import | Same call |
| Credentials config | Copy `credentials.yaml` | Copy a file |
| Actual secrets | Re-enter in production Vault via TUI | Manual (intentional — don't migrate dev secrets) |
| Keycloak users | Reconfigure for production IdP (LDAP, SSO) | Separate concern |
| API keys | Generate new ones for production | Fresh start |

The "hard" parts (service config, policy rules, tool enablement) are portable.
The parts you re-enter (secrets, users, API keys) are the parts you *should* re-enter for security.

---

## Agent Framework Integration

The gateway is a standard MCP server. Any MCP-compatible client connects with just a URL and an API key — no framework-specific SDK needed.

### Per-Framework Getting Started (Planned Docs)

**Google ADK:**
```python
from google.adk import Agent
from google.adk.tools import MCPToolset

tools = await MCPToolset.from_server(
    url="http://localhost:8000/mcp",
    headers={"Authorization": "Bearer nmc-your-api-key"}
)
agent = Agent(tools=tools)
```

**LangChain:**
```python
from langchain_mcp_adapters import MCPToolkit

toolkit = MCPToolkit(
    url="http://localhost:8000/mcp",
    headers={"Authorization": "Bearer nmc-your-api-key"}
)
tools = toolkit.get_tools()
```

**Any MCP Client:**
```bash
# Streamable HTTP
POST http://localhost:8000/mcp
Authorization: Bearer nmc-your-api-key
Content-Type: application/json

{"jsonrpc":"2.0","id":1,"method":"tools/list"}
```

### Auth Priority

API key authentication is the single biggest friction reducer for framework adoption.
The OAuth/JWT flow (via Keycloak) remains for browser-based clients (MCP Inspector, human users).

---

## Implementation Priority

1. **API key auth** — Gateway accepts `Bearer nmc-xxx`, key management in TUI
2. **All-in-one Dockerfile** — Single image with s6-overlay, volume persistence
3. **Getting-started docs** — One page per framework (ADK, LangChain, generic MCP)
4. **Helm chart** — Kubernetes-native deployment for production

---

*Planning document — Feb 9, 2026*
