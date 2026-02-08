# TUI Credential Management

## Overview

The TUI now includes comprehensive credential management, allowing you to configure services with Vault-backed credential injection without manual YAML editing or Vault CLI commands.

## Features

### 🎯 Guided Credential Setup
- **Smart Service Detection**: Automatically detects common services (Gemini, GitHub, Slack, OpenAI, etc.)
- **Canonical Paths**: Suggests industry-standard Vault paths for known services
- **Intelligent Env Vars**: Auto-generates correct environment variable names (e.g., `GEMINI_API_KEY`)
- Interactive prompts for all credential configuration
- No manual YAML editing required
- No Vault CLI commands needed
- Automatic validation and testing

### 🔐 Vault Integration
- Store secrets directly from TUI (masked input)
- Automatic path interpolation (`{tenant}`, `{user}`)
- Verify secrets stored correctly
- Test credential injection before using

### ⚙️ Service Configuration
- Map services to credentials with dropdown selection
- See which services have credentials configured
- Test credentials for any service
- Remove credential mappings

## How to Use

### Access Credential Management

1. Start the TUI:
   ```bash
   cd tui && npm run dev
   ```

2. Navigate to: **System > Manage credentials**

### Supported Common Services

The TUI automatically recognizes and provides canonical configurations for:

| Service | Credential Name | Vault Path | Env Var |
|---------|----------------|------------|---------|
| **Google Gemini** | `gemini`, `google_gemini` | `secret/data/tenants/{tenant}/users/{user}/gemini/api` | `GEMINI_API_KEY` |
| **GitHub** | `github`, `personal_github`, `work_github` | `secret/data/tenants/{tenant}/users/{user}/github/personal` | `GITHUB_TOKEN` |
| **Slack** | `slack`, `prod_slack` | `secret/data/tenants/{tenant}/users/{user}/slack/workspace` | `SLACK_TOKEN` |
| **OpenAI** | `openai` | `secret/data/tenants/{tenant}/users/{user}/openai/api` | `OPENAI_API_KEY` |
| **Anthropic** | `anthropic` | `secret/data/tenants/{tenant}/users/{user}/anthropic/api` | `ANTHROPIC_API_KEY` |
| **Database** | `database` | `secret/data/tenants/{tenant}/users/{user}/database/credentials` | `DB_USER`, `DB_PASS` |

💡 **Tip**: When naming your credential, use or include these service names (e.g., `my_gemini`, `production_github`) and the TUI will automatically suggest the correct paths and environment variables!

---

## Workflows

### ✅ Complete Flow: Add Google Gemini

This shows the full end-to-end flow for adding a new service with credentials:

**Step 1: Add Credential Mapping**
```
System > Manage credentials > Add credential

Credential name: google_gemini
Vault path: secret/data/tenants/{tenant}/users/{user}/gemini/api
Injection type: Environment variables

Field mappings:
  api_key → GEMINI_API_KEY

✓ Credential mapping saved
```

**Step 2: Store Secret in Vault**
```
Store secrets in Vault now? Yes

Tenant ID: default
User ID: alice
Enter api_key: ********************* (your actual API key)

✓ Secrets stored in Vault
✓ Credential 'google_gemini' is ready to use
```

**Step 3: Configure Service**
```
System > Manage credentials > Configure service

Select service: Google Gemini
Select credential: google_gemini

✓ Google Gemini will use 'google_gemini' credentials
Test credential injection now? Yes

✓ Google Gemini: google_gemini
    GEMINI_API_KEY=AIzaSyCd...
```

**Done!** Your service is now configured with secure credential injection.

---

### 📝 Add Credential Mapping Only

If you want to define a credential without storing secrets yet:

```
System > Manage credentials > Add credential

[Follow prompts...]

Store secrets in Vault now? No

✓ Credential mapping saved
  You can store secrets manually later:
    docker exec gateway-vault-1 vault kv put secret/...
```

---

### 🔗 Configure Existing Service

Map an existing service to a credential:

```
System > Manage credentials > Configure service

Select service: GitHub
Select credential: work_github

✓ GitHub will use 'work_github' credentials
```

---

### 🧪 Test Credential Injection

Verify credentials are working:

```
System > Manage credentials > Test injection

Test credentials for: [Select service or "Test all services"]

✓ GitHub: work_github
    GITHUB_TOKEN=ghp_abc123...
✓ Gemini: google_gemini
    GEMINI_API_KEY=AIzaSyCd...
```

---

### 👀 View Configuration

See the current credentials.yaml:

```
System > Manage credentials > View credentials.yaml

[Shows complete configuration file]
```

---

## Example Credentials

### Simple API Key (Google Gemini)

```
Credential name: google_gemini
Vault path: secret/data/tenants/{tenant}/users/{user}/gemini/api
Injection type: env

Field mapping:
  api_key → GEMINI_API_KEY
```

**Stores in Vault:**
```json
{
  "api_key": "AIzaSyCdEfGh..."
}
```

**Injected as:**
```bash
GEMINI_API_KEY=AIzaSyCdEfGh...
```

---

### Multiple Fields (Database)

```
Credential name: readonly_db
Vault path: secret/data/tenants/{tenant}/services/database/readonly
Injection type: env

Field mappings:
  username → DB_USER
  password → DB_PASS
```

**Stores in Vault:**
```json
{
  "username": "reader",
  "password": "readonly123"
}
```

**Injected as:**
```bash
DB_USER=reader
DB_PASS=readonly123
```

---

### HTTP Headers (instead of env vars)

```
Credential name: api_service
Vault path: secret/data/tenants/{tenant}/services/api/prod
Injection type: header

Field mapping:
  token → X-API-Key
```

**Injected as HTTP header:**
```
X-API-Key: abc123...
```

---

## Credential Naming Conventions

### Recommended Patterns

**By environment:**
- `prod_slack`, `dev_slack`, `staging_slack`

**By account:**
- `work_github`, `personal_github`
- `admin_db`, `readonly_db`

**By service:**
- `google_gemini`, `anthropic_claude`, `openai_gpt`

### Rules

- Use lowercase letters, numbers, and underscores only
- No spaces or special characters
- Descriptive and unique

---

## Vault Path Templates

Paths support variable interpolation:

- `{tenant}` - Replaced with tenant ID (e.g., "default", "acme")
- `{user}` - Replaced with user ID (e.g., "alice", "bob")

### Common Patterns

**User-specific credentials:**
```
secret/data/tenants/{tenant}/users/{user}/service/name
```

**Service-level credentials (shared):**
```
secret/data/tenants/{tenant}/services/service/type
```

**Team credentials:**
```
secret/data/tenants/{tenant}/teams/team-name/service
```

---

## Behind the Scenes

### What the TUI Does

1. **Add Credential**: Updates `configs/credentials.yaml` with mapping
2. **Store Secrets**: Makes HTTP request to Vault API to store encrypted secrets
3. **Configure Service**: Links service name to credential name in config
4. **Test Injection**: Calls Credential Proxy `/inject-credentials` endpoint

### Files Modified

- `configs/credentials.yaml` - Credential mappings and service defaults
- Vault KV store - Encrypted secrets (not in files)

### No Gateway Restart Needed

Credential Proxy picks up configuration changes automatically. Gateway only needs restart when:
- Adding/removing services
- Changing service definitions
- Modifying tools

---

## Troubleshooting

### "Failed to store secrets in Vault"

**Check Vault is running:**
```bash
docker ps | grep vault
curl http://localhost:8200/v1/sys/health
```

**Check Vault token:**
```bash
echo $VAULT_TOKEN  # Should be "dev-token" for local dev
```

---

### "Credential injection test failed"

**Check Credential Proxy:**
```bash
docker ps | grep credential-proxy
curl http://localhost:9002/health
```

**Check logs:**
```bash
docker compose logs credential-proxy
```

---

### Secret Not Found in Vault

**Verify path interpolation:**
```bash
# Check actual path used
docker exec gateway-vault-1 vault kv list secret/tenants/default/users/alice/
```

**Check secret exists:**
```bash
docker exec gateway-vault-1 sh -c \
  'VAULT_TOKEN=dev-token vault kv get secret/tenants/default/users/alice/gemini/api'
```

---

## Security Best Practices

✅ **DO:**
- Use unique credentials per service
- Use user-specific paths for personal API keys
- Test credentials before deploying to production
- Rotate secrets regularly

❌ **DON'T:**
- Share credentials across unrelated services
- Store credentials in config files
- Use same credential for dev/staging/prod
- Commit secrets to git

---

## Migration from Manual Setup

If you previously configured credentials manually:

1. **Check existing credentials.yaml:**
   ```bash
   cat configs/credentials.yaml
   ```

2. **Verify secrets in Vault:**
   ```bash
   docker exec gateway-vault-1 vault kv list secret/...
   ```

3. **Use TUI to add missing mappings**

4. **Test all services:**
   ```
   System > Manage credentials > Test injection > Test all services
   ```

---

## Next Steps

After setting up credentials:

1. **Add the MCP service** (if not already added)
   - Via Docker Hub search
   - Or custom image

2. **Enable the service**
   - Select service from main menu
   - Choose "Enable service"

3. **Grant user access** (NPL)
   - User Management > [Select user]
   - Grant tools for the service

4. **Test end-to-end**
   - Make actual tool call through Gateway
   - Verify credentials are injected
   - Check logs for confirmation

---

## Advanced

### Multiple Users/Tenants

The TUI prompts for tenant and user when storing secrets:

```
Tenant ID: default
User ID: alice
```

Store same credential definition for multiple users:
```bash
# Alice's credentials
Tenant: default, User: alice

# Bob's credentials  
Tenant: default, User: bob

# Team credentials
Tenant: acme, User: team
```

### Custom Injection Types

Currently supported:
- `env` - Environment variables (most common)
- `header` - HTTP headers

Future: Custom injection patterns via NPL

---

## Related Documentation

- [CREDENTIAL_INJECTION.md](./CREDENTIAL_INJECTION.md) - Complete architecture
- [CREDENTIAL_INJECTION_QUICKSTART.md](./CREDENTIAL_INJECTION_QUICKSTART.md) - CLI setup
- [CREDENTIAL_INJECTION_VERIFIED.md](./CREDENTIAL_INJECTION_VERIFIED.md) - Test results

---

*Last updated: 2026-02-08*
