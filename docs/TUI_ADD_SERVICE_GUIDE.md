# TUI Service Addition Guide

## Overview

The TUI now provides a fully guided experience for adding MCP services with three different paths optimized for different use cases.

---

## Access the Feature

```
TUI Main Menu → + Add MCP service
```

---

## Three Service Addition Paths

### 1. 🚀 Quick Start (Common Services)

**Best for:** Getting started fast with popular MCP servers

**Features:**
- Pre-configured templates for common services
- One-click setup with canonical configurations
- Automatic credential guidance
- Enabled by default

**Available Templates:**
- **Google Gemini** - AI text generation and chat
- **GitHub** - Repository management and code operations
- **Filesystem** - Local file operations
- **Memory** - Persistent knowledge graph
- **Brave Search** - Web search capabilities

**Example Flow:**
```
Select service type: Quick Start (Common Services)
Select service: Google Gemini
✓ Selected: Google Gemini
  Google Gemini AI API for text generation and chat
  💡 You'll need a Gemini API key from https://aistudio.google.com/apikey

Add Google Gemini? Yes

✓ Added: Google Gemini
⚠️  This service requires credentials

Set up credentials now? Yes
[Guided through credential setup]
✓ Google Gemini will use 'gemini' credentials

✨ Service added! Don't forget to grant user access via 'User Management'
```

**What Happens Behind the Scenes:**
- Service added to `configs/services.yaml` with correct command/args
- Enabled by default (ready to use)
- Placeholder tool added for discovery
- Gateway config reloaded
- Credentials optionally configured
- Service linked to credential in `credentials.yaml`

---

### 2. 📦 NPM Package

**Best for:** Adding any MCP server available as an npm package

**Features:**
- Flexible for any npm-based MCP server
- Smart service name suggestion from package name
- Optional credential configuration
- Auto-enabled after adding

**Example Flow:**
```
Select service type: NPM Package

NPM package name: @modelcontextprotocol/server-google-gemini
Service name: gemini [auto-suggested]
Display name: Gemini [auto-filled]
Description: MCP service: Gemini [default]
Does this service require credentials? Yes

✓ Added: Gemini
⚠️  Don't forget to set up credentials!
   Go to: System > Manage credentials

✨ Service added! Don't forget to grant user access via 'User Management'
```

**What It Creates:**
```yaml
- name: "gemini"
  displayName: "Gemini"
  type: "MCP_STDIO"
  enabled: true
  command: "npx"
  args:
    - "-y"
    - "@modelcontextprotocol/server-google-gemini"
  requiresCredentials: true
  description: "MCP service: Gemini"
  tools: []
```

---

### 3. 🐳 Docker Image

**Best for:** Docker-based MCP servers (local or registry images)

**Features:**
- Supports local images
- Supports private registries
- Auto-pull from public registries
- Image validation before adding

**Example Flow:**
```
Select service type: Docker Image

Enter Docker image name: ghcr.io/myorg/my-mcp-server:latest
Service name: my-mcp-server [auto-suggested]
Display name: My Mcp Server [auto-filled]
Description: Custom MCP service: My Mcp Server [default]

Image 'ghcr.io/myorg/my-mcp-server:latest' not found locally.
Try to pull the image now? Yes

Pulling ghcr.io/myorg/my-mcp-server:latest...
✓ Image pulled successfully

✓ Added: My Mcp Server
Service is disabled by default. Select it to enable and configure tools.
```

---

## After Adding a Service

### Next Steps

1. **Set Up Credentials** (if required)
   ```
   System → Manage credentials → Add credential
   ```

2. **Link Service to Credential**
   ```
   System → Manage credentials → Configure service
   ```

3. **Grant User Access**
   ```
   User Management → [Select user] → Grant access to tools
   ```

4. **Enable the Service** (if using Docker path)
   ```
   Main Menu → [Select service] → Enable service
   ```

5. **Discover Tools** (optional, for better tool metadata)
   ```
   Main Menu → [Select service] → Discover tools
   ```

6. **Restart Gateway** (if needed)
   ```
   System → Restart Gateway
   ```

---

## Comparison: Old vs New Flow

### Old Flow (Manual)
```
❌ User tries Docker image
❌ Doesn't know Gemini is npm-based
❌ Pull fails with cryptic error
❌ Manual YAML editing required
❌ No guidance on credentials
❌ No service-credential linking help
```

### New Flow (Guided)
```
✅ Clear choice: Template / NPM / Docker
✅ Pre-configured template for Gemini
✅ One-click setup
✅ Automatic credential prompts
✅ Smart defaults throughout
✅ Zero manual file editing
```

---

## Service Templates Deep Dive

### Template Structure

Each template includes:
- **Display Name**: Human-readable name
- **Description**: What the service does
- **Command/Args**: How to run the service
- **Requires Credentials**: Boolean flag
- **Credential Name**: Suggested credential identifier
- **Setup Guide**: Context-sensitive help text

### Adding New Templates

To add more templates, edit `tui/src/cli.ts`:

```typescript
const SERVICE_TEMPLATES = {
  my_service: {
    displayName: "My Service",
    description: "What my service does",
    command: "npx",
    args: ["-y", "@myorg/mcp-server-myservice"],
    requiresCredentials: true,
    credentialName: "my_service",
    setupGuide: "You'll need an API key from https://myservice.com/keys",
  },
  // ... existing templates
};
```

Then rebuild the TUI:
```bash
cd tui && npm run build
```

---

## Best Practices

### For Users

1. **Start with Templates** - If your service is listed, use the template for instant setup
2. **Use NPM Path** - Most modern MCP servers are npm packages
3. **Use Docker Path** - Only for custom/private Docker images
4. **Set Up Credentials Immediately** - When prompted, configure credentials right away
5. **Test Before Granting Access** - Use "Test injection" to verify credentials work

### For Template Creators

1. **Use Official Package Names** - Point to the official npm package
2. **Match Credential Detection** - Use same service name as in `SERVICE_CONFIGS` (credentials.ts)
3. **Provide Clear Setup Guides** - Include exact URL for getting API keys
4. **Test the Template** - Verify it works end-to-end before committing

---

## Troubleshooting

### "Service name already exists"

**Problem:** You're trying to add a service that's already configured.

**Solution:** 
- Check existing services in main menu
- Choose a different service name
- Or remove the existing service first

### "Failed to add service"

**Problem:** YAML parsing or file write error.

**Solution:**
- Check `configs/services.yaml` for syntax errors
- Ensure you have write permissions
- Try restarting the TUI

### "Don't forget to set up credentials"

**Problem:** Service requires credentials but they're not configured yet.

**Solution:**
- Go to: System → Manage credentials → Add credential
- Follow the guided credential setup
- Link service to credential via "Configure service"

### Service added but not working

**Problem:** Service is added but calls fail.

**Solution:**
1. Check service is enabled: `Main Menu → [Service] → Enable service`
2. Verify credentials: `System → Manage credentials → Test injection`
3. Grant user access: `User Management → [User] → Grant access`
4. Restart gateway: `System → Restart Gateway`
5. Check logs: `docker compose logs gateway --tail 50`

---

## Related Documentation

- [TUI_CREDENTIAL_MANAGEMENT.md](./TUI_CREDENTIAL_MANAGEMENT.md) - Credential setup guide
- [CREDENTIAL_SERVICE_CATALOG.md](./CREDENTIAL_SERVICE_CATALOG.md) - Supported services
- [GEMINI_QUICKSTART_TUI.md](./GEMINI_QUICKSTART_TUI.md) - End-to-end Gemini example

---

*Last updated: Feb 8, 2026*
