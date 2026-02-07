# User Access Control - Quick Start Guide

This guide walks you through testing the new per-user tool access control system in under 5 minutes.

## Prerequisites

- Gateway stack running (`docker compose up -d`)
- TUI available (`cd tui && npm install`)
- Admin credentials (default: `admin` / `Welcome123`)

## Step 1: Bootstrap NPL with User Support

```bash
cd tui
npm start
```

1. Login with admin credentials
2. Select **"NPL Bootstrap"**
3. Verify output shows:
   - ✓ Created/Found ServiceRegistry
   - ✓ Created/Found UserRegistry **← NEW**
   - ✓ Created ToolPolicy for services
   - ℹ No users configured yet (expected)

## Step 2: Create a Test User

In the TUI:

1. Select **"User Management"** (new menu option)
2. Select **"+ Create new user"**
3. Fill in:
   - **Email**: `testuser@example.com`
   - **Username**: `testuser`
   - **First name**: `Test` (optional)
   - **Last name**: `User` (optional)
   - **Password**: `Welcome123`
4. Wait for:
   - ✓ User created in Keycloak
   - ✓ User registered in NPL
   - ✓ Configuration updated

## Step 3: Grant Tool Access

Still in User Management:

1. Select `testuser@example.com` (or Test User)
2. Select **"Edit tool access"**
3. Select **"duckduckgo"** service
4. Select **"Grant tools"**
5. Press SPACE to select `search`
6. Press ENTER to confirm
7. Verify: ✓ 1 tool(s) granted

## Step 4: Test Authentication

```bash
# Get JWT token for test user
export KEYCLOAK_URL="http://localhost:11000"
export TOKEN=$(curl -s -X POST \
  "$KEYCLOAK_URL/realms/mcpgateway/protocol/openid-connect/token" \
  -d "grant_type=password" \
  -d "client_id=mcpgateway" \
  -d "username=testuser" \
  -d "password=Welcome123" | jq -r '.access_token')

echo "Token obtained: ${TOKEN:0:50}..."
```

## Step 5: Test ALLOWED Tool Call

```bash
# Call duckduckgo__search (granted to testuser)
curl -s -X POST \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  "http://localhost:8000/mcp" \
  -d '{
    "jsonrpc": "2.0",
    "id": 1,
    "method": "tools/call",
    "params": {
      "name": "duckduckgo__search",
      "arguments": {"query": "test"}
    }
  }' | jq
```

**Expected Result**: 
```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "result": {
    "content": [
      {
        "type": "text",
        "text": "[Search results...]"
      },
      {
        "type": "text",
        "text": "---\n{\"status\":\"SUCCESS\",\"service\":\"duckduckgo\",...}"
      }
    ],
    "isError": false
  }
}
```

## Step 6: Test DENIED Tool Call

```bash
# Call github__create_issue (NOT granted to testuser)
curl -s -X POST \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  "http://localhost:8000/mcp" \
  -d '{
    "jsonrpc": "2.0",
    "id": 2,
    "method": "tools/call",
    "params": {
      "name": "github__create_issue",
      "arguments": {
        "owner": "test",
        "repo": "test",
        "title": "Test Issue"
      }
    }
  }' | jq
```

**Expected Result**:
```json
{
  "jsonrpc": "2.0",
  "id": 2,
  "result": {
    "content": [
      {
        "type": "text",
        "text": "❌ POLICY_DENIED"
      },
      {
        "type": "text",
        "text": "User does not have access to this tool"
      }
    ],
    "isError": true
  }
}
```

## Step 7: Grant Additional Access (Wildcard)

Back in TUI → User Management:

1. Select `testuser@example.com`
2. Select **"Edit tool access"**
3. Select **"github"** service
4. Select **"Grant all"**
5. Verify: All tools granted

Now retry the `github__create_issue` call from Step 6 — it should succeed!

## Step 8: View User Access

In TUI:

1. Select `testuser@example.com`
2. Select **"View all access"**
3. See detailed list:
   - ✓ duckduckgo: search
   - ✓ github: ALL TOOLS

Or query via NPL API:

```bash
# Get UserToolAccess instance ID (requires admin token)
ADMIN_TOKEN=$(curl -s -X POST \
  "$KEYCLOAK_URL/realms/mcpgateway/protocol/openid-connect/token" \
  -d "grant_type=password" \
  -d "client_id=mcpgateway" \
  -d "username=admin" \
  -d "password=Welcome123" | jq -r '.access_token')

# Query user access
curl -s -X GET \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  "http://localhost:12000/npl/users/UserToolAccess/?userId=testuser@example.com" | jq
```

## Step 9: Revoke Access

In TUI:

1. User Management → testuser@example.com → Edit tool access
2. Select **"duckduckgo"**
3. Select **"Revoke tools"**
4. Press SPACE to select `search`
5. Press ENTER to confirm

Retry Step 5 — now the call should be DENIED!

## Step 10: Clean Up (Optional)

In TUI:

1. User Management → testuser@example.com
2. Select **"Delete user"**
3. Confirm deletion
4. User removed from:
   - Keycloak (can no longer authenticate)
   - NPL UserRegistry (not registered)
   - services.yaml (not persisted)

## Verification Checklist

✅ NPL Bootstrap creates UserRegistry  
✅ Can create users in Keycloak via TUI  
✅ Users are registered in NPL UserRegistry  
✅ Can grant tools per service  
✅ Can grant wildcard (all tools) for a service  
✅ Granted tools work (ALLOWED)  
✅ Non-granted tools are denied (POLICY_DENIED)  
✅ Can revoke individual tools  
✅ Can revoke entire service access  
✅ Can delete users (Keycloak + NPL + config)  

## Troubleshooting

### "Policy engine unavailable"
- Check NPL is running: `docker ps | grep npl`
- Check Gateway can reach NPL: `docker logs mcpgateway | grep NPL`

### "Authentication failed"
- Verify Keycloak is healthy: `curl http://localhost:11000/health`
- Check user exists: TUI → User Management → view users
- Verify password is correct

### Tool call denied but user has access
- Check service is enabled: TUI main menu → verify service status
- Check tool is enabled in ToolPolicy: Select service → Manage tools
- Check Gateway logs: `docker logs -f mcpgateway | grep "Policy check"`
- Check NPL logs: `docker logs -f npl-engine | grep "UserToolAccess"`

### Changes not taking effect
- Run NPL Bootstrap again to sync
- Check services.yaml has correct user_access section
- Restart Gateway: `docker restart mcpgateway`

## Next Steps

- Import existing Keycloak users: **User Management → + Import from Keycloak**
- Configure default template: Edit `configs/services.yaml` → `user_access.default_template`
- Enable more services: TUI main menu → services → Enable
- Grant more tools: User Management → Edit tool access

## Architecture Deep Dive

For a complete understanding of the system, see:
- [USER_ACCESS_CONTROL.md](USER_ACCESS_CONTROL.md) - Full architecture docs
- [ARCHITECTURE_V2.md](ARCHITECTURE_V2.md) - Overall Gateway design

## Example: Production Setup

For a production multi-user scenario:

1. **Create user template** in `services.yaml`:
```yaml
user_access:
  default_template:
    enabled: true
    description: "Standard employee access"
    tools:
      duckduckgo: ["search"]
      slack: ["send_message"]
```

2. **Create users in Keycloak** (via TUI or Keycloak admin)

3. **Import users via TUI** → automatically inherit template

4. **Grant additional access** per user as needed

5. **Audit via NPL**: Query `UserToolAccess` protocols to see who has what

6. **Revoke access** instantly via TUI when employees leave

## Support

Questions? Check:
- Gateway logs: `docker logs -f mcpgateway`
- NPL logs: `docker logs -f npl-engine`
- Keycloak admin: http://localhost:11000/admin (admin / Welcome123)
- NPL admin: http://localhost:12000 (browse protocols)
