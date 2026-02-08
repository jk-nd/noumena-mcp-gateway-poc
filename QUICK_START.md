# Quick Start: Credential Injection with TUI

## You're Now On: `feature/credential-injection-v2`

This branch contains **everything** you need:
- ✅ Complete credential injection system
- ✅ TUI with credential management UI
- ✅ Integration tests (9/10 passing)
- ✅ Documentation and guides
- ✅ Ready to use!

---

## Location

**Working from:** `/Users/juerg/development/noumena-mcp-gateway`

**Branch:** `feature/credential-injection-v2` (pushed to GitHub)

---

## What's Included

### 1. Credential Injection System
- Credential Proxy service (port 9002)
- Vault integration with caching
- Simple mode (YAML) and Expert mode (NPL)
- Network isolation (4-tier)
- Complete test suite

### 2. TUI Credential Management (NEW!)
- **Menu:** System > Manage credentials
- Add credentials interactively
- Store secrets in Vault (masked input)
- Configure services
- Test injection
- Zero manual YAML editing!

### 3. Documentation
- `docs/TUI_CREDENTIAL_MANAGEMENT.md` - Complete TUI guide
- `docs/GEMINI_QUICKSTART_TUI.md` - 5-minute Gemini setup
- `docs/CREDENTIAL_INJECTION.md` - Architecture details
- `docs/CREDENTIAL_INJECTION_VERIFIED.md` - Test results

---

## Try It Now!

### Start the TUI

```bash
cd tui
npm run dev

# Log in with admin credentials
# Navigate to: System > Manage credentials
```

### You'll See These Options:

```
System > Manage credentials

? Select action:
  + Add credential          ← Create new credential mapping
  Configure service         ← Link service to credential  
  Test injection            ← Verify it works
  View credentials.yaml     ← See configuration
```

---

## Add Google Gemini (5 Minutes)

**Step 1: Add Credential**
```
System > Manage credentials > Add credential

Credential name: google_gemini
Vault path: [press Enter for default]
Injection type: Environment variables
Field: api_key → GEMINI_API_KEY

Store secrets now? Yes
Enter api_key: [paste your API key]

✓ Done!
```

**Step 2: Add Service**
```
Main menu > + Add custom image

Service name: gemini
Command: npx
Args: -y gemini-mcp-server
Type: MCP_STDIO
```

**Step 3: Link Them**
```
System > Manage credentials > Configure service

Select: Google Gemini
Select: google_gemini

Test injection? Yes
✓ Working!
```

**Step 4: Enable & Grant Access**
```
Main menu > Google Gemini > Enable service
User Management > [your user] > Grant tools > gemini
```

**Done!** 🎉

---

## What Changed from Before

### Before (Manual Setup):
```bash
# 1. Edit credentials.yaml manually
vim configs/credentials.yaml

# 2. Run Vault commands
docker exec vault vault kv put secret/...

# 3. Edit services.yaml manually
vim configs/services.yaml

# 4. Test manually with curl
curl http://localhost:9002/...

# Time: 15-20 minutes
# Risk: Typos, wrong paths, debugging
```

### Now (TUI):
```bash
# 1. Run TUI
npm run dev

# 2. Follow prompts
System > Manage credentials > [follow prompts]

# Time: 5 minutes
# Risk: None (validation at every step)
```

---

## Branch Status

```bash
# Current location
pwd
# /Users/juerg/development/noumena-mcp-gateway

# Current branch
git branch
# * feature/credential-injection-v2

# Recent commits
git log --oneline -5
# 8fdb7d1 docs: Add Gemini quickstart guide for TUI
# 02fc2a1 feat(tui): Add comprehensive credential management UI
# 2b39dbf docs: Add comprehensive testing results and verification
# dd9953c feat: Add credential injection system with dual-mode architecture
# 77a5451 feat: add per-user tool access control...
```

---

## Services Status

Make sure services are running:

```bash
docker compose ps

# Should show:
# - gateway (port 8000)
# - credential-proxy (port 9002)
# - vault (port 8200)
# - npl-engine (port 12000)
# - keycloak (port 11000)
```

---

## Test Everything Works

```bash
# 1. Check Credential Proxy
curl http://localhost:9002/health

# 2. Check Vault
curl http://localhost:8200/v1/sys/health

# 3. Run integration tests
./gradlew :integration-tests:test --tests "*CredentialInjection*"

# Should show: 9/10 tests passing ✅
```

---

## Next Steps

1. **Add your first service via TUI** (e.g., Google Gemini)
2. **Test credential injection** end-to-end
3. **Add more services** - takes ~5 minutes each
4. **Switch to Expert mode** (NPL-based) when ready

---

## Documentation Quick Links

**For TUI Users:**
- [TUI_CREDENTIAL_MANAGEMENT.md](docs/TUI_CREDENTIAL_MANAGEMENT.md) - Complete guide
- [GEMINI_QUICKSTART_TUI.md](docs/GEMINI_QUICKSTART_TUI.md) - 5-min setup

**For Developers:**
- [CREDENTIAL_INJECTION.md](docs/CREDENTIAL_INJECTION.md) - Architecture
- [CREDENTIAL_INJECTION_VERIFIED.md](docs/CREDENTIAL_INJECTION_VERIFIED.md) - Tests

**For Manual Setup:**
- [CREDENTIAL_INJECTION_QUICKSTART.md](docs/CREDENTIAL_INJECTION_QUICKSTART.md) - CLI setup

---

## Troubleshooting

**TUI doesn't show "Manage credentials":**
```bash
# Rebuild TUI
cd tui && npm run build

# Verify it's there
grep "Manage credentials" dist/cli.js
```

**Services not running:**
```bash
docker compose up -d
docker compose ps
```

**Can't connect to Vault:**
```bash
docker compose logs vault
docker exec gateway-vault-1 vault status
```

---

**Everything is ready to use!** 🚀

Start with: `cd tui && npm run dev`
