# Add Google Gemini via TUI (5 Minutes)

## Quick Start: Zero-Config Gemini Setup

This guide shows you how to add Google Gemini to your Gateway using **only the TUI** - no manual file editing or Vault commands needed!

---

## Prerequisites

✅ Gateway services running: `docker compose ps`  
✅ Your Gemini API key ready  
✅ TUI installed: `cd tui && npm install`

---

## Step-by-Step

### 1. Start the TUI

```bash
cd tui
npm run dev
```

Log in with admin credentials when prompted.

---

### 2. Add Gemini Credential

**Navigate to:** `System > Manage credentials > Add credential`

**Follow the prompts:**

```
Credential name: google_gemini

Vault path: secret/data/tenants/{tenant}/users/{user}/gemini/api
(or press Enter to use default)

Injection type: Environment variables

Field mappings:
  Vault field: api_key
  → Environment variable: GEMINI_API_KEY
  (Press Enter with empty field to finish)

✓ Credential mapping saved
```

---

### 3. Store Your API Key

**When prompted:**

```
Store secrets in Vault now? Yes

Tenant ID: default
User ID: alice

Enter api_key: ********************* (paste your Gemini API key)

✓ Secrets stored in Vault
✓ Credential 'google_gemini' is ready to use
```

**That's it for credentials!** ✅

---

### 4. Add Gemini Service

**Back at main menu, choose:** `+ Search Docker Hub`

```
Search term: gemini
(or press Enter to see all)

Select: gemini-mcp-server (if available)
OR use: + Add custom image
```

**If using custom image:**

```
Image name: npx
Command args: -y gemini-mcp-server
Service name: gemini
Display name: Google Gemini
Transport type: MCP_STDIO

✓ Service added
```

---

### 5. Configure Service Credentials

**Navigate to:** `System > Manage credentials > Configure service`

```
Select service: Google Gemini
Select credential: google_gemini

✓ Google Gemini will use 'google_gemini' credentials

Test credential injection now? Yes

✓ Google Gemini: google_gemini
    GEMINI_API_KEY=AIzaSyCd...

✓ Credential injection working!
```

---

### 6. Enable Service

**Main menu, select:** `Google Gemini`

```
Action: Enable service

✓ Service enabled
```

---

### 7. Grant User Access (NPL)

**Navigate to:** `User Management > [Select user: alice]`

```
Action: Grant access to tools

Select service: Google Gemini
Which tools? Grant all tools

✓ Access granted
```

---

### 8. Test It!

**Get a token and test:**

```bash
# Get JWT token
TOKEN=$(curl -s -X POST "http://localhost:11000/realms/mcpgateway/protocol/openid-connect/token" \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "grant_type=password&client_id=mcpgateway&username=alice&password=Welcome123" | \
  jq -r '.access_token')

# Call Gemini via Gateway
curl -X POST http://localhost:8000/mcp \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": 1,
    "method": "tools/call",
    "params": {
      "name": "gemini.use_gemini",
      "arguments": {
        "prompt": "Say hello in exactly 5 words"
      }
    }
  }' | jq
```

---

## 🎉 Success!

You now have Google Gemini fully integrated with:
- ✅ Secure credential injection (Vault)
- ✅ Policy enforcement (NPL)
- ✅ Gateway routing
- ✅ Zero manual config files edited

**Total time:** ~5 minutes  
**Manual commands:** 0 (except final test)  
**Files edited manually:** 0

---

## What Just Happened?

### The TUI Did This For You:

1. **Created credential mapping** in `configs/credentials.yaml`:
   ```yaml
   credentials:
     google_gemini:
       vault_path: "secret/data/tenants/{tenant}/users/{user}/gemini/api"
       injection:
         type: env
         mapping:
           api_key: "GEMINI_API_KEY"
   
   service_defaults:
     gemini: google_gemini
   ```

2. **Stored secret in Vault** via API:
   ```bash
   # Equivalent to:
   vault kv put secret/tenants/default/users/alice/gemini/api api_key='your-key'
   ```

3. **Added service** to `configs/services.yaml`:
   ```yaml
   - name: "gemini"
     type: "MCP_STDIO"
     command: "npx"
     args: ["-y", "gemini-mcp-server"]
     requiresCredentials: true
     # ...
   ```

4. **Tested injection** via Credential Proxy API

5. **Granted user access** via NPL API

**All through guided prompts!** 🎯

---

## Add More Services

Now that you understand the flow, add other services the same way:

**Popular MCP servers:**
- `mcp/github` - GitHub API
- `mcp/slack` - Slack integration
- `mcp/postgres` - Database access
- `anthropic-mcp-server` - Anthropic Claude
- `openai-mcp-server` - OpenAI GPT

**Each takes ~5 minutes via TUI!**

---

## Troubleshooting

### "Failed to store secrets in Vault"

```bash
# Check Vault is running
docker ps | grep vault

# Check Vault health
curl http://localhost:8200/v1/sys/health
```

### "Credential injection test failed"

```bash
# Check Credential Proxy
docker ps | grep credential-proxy

# Check logs
docker compose logs credential-proxy --tail 20
```

### Service doesn't appear in tools list

```bash
# Restart Gateway to pick up service
docker compose restart gateway

# Wait 5 seconds, then test
sleep 5
curl -H "Authorization: Bearer $TOKEN" http://localhost:8000/mcp \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}' | jq '.result.tools[] | .name'
```

---

## Next Steps

- **Add more services** via TUI
- **Create team credentials** (different tenant/user)
- **Switch to Expert mode** for NPL-based credential selection
- **Monitor credential usage** via logs

---

## Learn More

- [TUI_CREDENTIAL_MANAGEMENT.md](./TUI_CREDENTIAL_MANAGEMENT.md) - Complete TUI guide
- [CREDENTIAL_INJECTION.md](./CREDENTIAL_INJECTION.md) - Architecture details
- [CREDENTIAL_INJECTION_VERIFIED.md](./CREDENTIAL_INJECTION_VERIFIED.md) - Test results

---

*Estimated time: 5 minutes*  
*Difficulty: Easy*  
*Prerequisites: Running Gateway stack*
