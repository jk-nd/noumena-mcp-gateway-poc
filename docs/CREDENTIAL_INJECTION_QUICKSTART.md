# Credential Injection Quick Start

Get up and running with credential injection in 5 minutes.

## What You'll Build

A secure MCP Gateway that automatically injects credentials into upstream services (GitHub, Slack, etc.) based on policy rules.

## Prerequisites

- Docker & Docker Compose
- Vault (dev server) or use our docker-compose-vault.yml
- 10 minutes

## Step 1: Start the Gateway

```bash
cd deployments
docker compose up -d
```

This starts:
- Gateway (port 8000)
- NPL Engine (policy checks)
- Credential Proxy (credential injection)
- Keycloak (authentication)

## Step 2: Start Vault (Development)

```bash
docker compose -f docker-compose-vault.yml up -d
```

**Dev credentials:**
- URL: http://localhost:8200
- Token: `dev-token`

## Step 3: Configure Credentials

Edit `configs/credentials.yaml`:

```yaml
mode: simple  # Start with simple mode

credentials:
  my_github:
    vault_path: "secret/data/tenants/default/users/me/github/personal"
    injection:
      type: env
      mapping:
        token: "GITHUB_TOKEN"

service_defaults:
  github: my_github
  duckduckgo: default  # No credentials needed
```

## Step 4: Store Secrets in Vault

```bash
# Set Vault address
export VAULT_ADDR=http://localhost:8200
export VAULT_TOKEN=dev-token

# Store GitHub token
vault kv put secret/tenants/default/users/me/github/personal \
  token=ghp_your_github_token_here
```

## Step 5: Test!

```bash
# Get JWT token (login via Keycloak or use test token)
export JWT_TOKEN="your_jwt_token"

# Call GitHub via Gateway
curl -X POST http://localhost:8000/mcp \
  -H "Authorization: Bearer $JWT_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": 1,
    "method": "tools/call",
    "params": {
      "name": "github.list_repos",
      "arguments": {}
    }
  }'
```

**What just happened?**

1. Gateway received your request
2. Gateway checked NPL policy (allowed)
3. Gateway called Credential Proxy: "Get credentials for github"
4. Credential Proxy looked up `service_defaults[github]` → `"my_github"`
5. Credential Proxy fetched from Vault: `secret/.../me/github/personal`
6. Credential Proxy returned: `{"GITHUB_TOKEN": "ghp_xxx"}`
7. Gateway spawned GitHub MCP with `GITHUB_TOKEN=ghp_xxx`
8. Request succeeded!

## Step 6: Add More Services

```yaml
credentials:
  my_github:
    vault_path: "secret/data/tenants/default/users/me/github/personal"
    injection:
      type: env
      mapping:
        token: "GITHUB_TOKEN"
  
  my_slack:
    vault_path: "secret/data/tenants/default/services/slack/dev"
    injection:
      type: env
      mapping:
        token: "SLACK_BOT_TOKEN"

service_defaults:
  github: my_github
  slack: my_slack
  duckduckgo: default
```

Store Slack token:
```bash
vault kv put secret/tenants/default/services/slack/dev \
  token=xoxb-your-slack-token
```

Done! Now Slack requests automatically use the right credentials.

## Next Steps

### Upgrade to Expert Mode

Want conditional credential selection? (e.g., work account for company repos, personal for others)

```bash
# Via TUI
cd tui && npm start
# Select: System > Credential Mode > Expert

# Or via environment
export CREDENTIAL_MODE=expert
docker compose restart credential-proxy
```

Then add NPL rules:

```npl
// Use work account for company repos
addInjectionRule(
  service: "github",
  credentialName: "work_github",
  condition: "metadata.repo startsWith 'mycompany/'",
  priority: 10
)

// Use personal account for everything else
addInjectionRule(
  service: "github",
  credentialName: "personal_github",
  condition: "true",
  priority: 1
)
```

### Production Setup

1. **Use real Vault** (not dev mode)
2. **Configure Vault auth** (Kubernetes SA or AppRole)
3. **Set up proper tenant/user paths**
4. **Enable audit logging**
5. **Set credential TTLs**

See [CREDENTIAL_INJECTION.md](CREDENTIAL_INJECTION.md) for details.

## Troubleshooting

### "Credential 'xxx' not found"

Check your `credentials.yaml`:
```bash
cat configs/credentials.yaml | grep xxx
```

### "Failed to fetch from Vault: 403"

Vault token expired or wrong:
```bash
vault token lookup
```

### "Credential proxy not responding"

Check if it's running:
```bash
docker compose ps credential-proxy
docker compose logs credential-proxy
```

### "NPL policy denied"

You need to grant tool access first:
```bash
cd tui && npm start
# Select: User Management > [user] > Grant tool access
```

## What's Next?

- [Full documentation](CREDENTIAL_INJECTION.md)
- [Architecture overview](ARCHITECTURE_V2.md)
- [User access control](USER_ACCESS_CONTROL.md)

## Support

Questions? Open an issue or check the docs.

Happy credentialing! 🔐
