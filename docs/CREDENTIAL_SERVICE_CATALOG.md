# Credential Service Catalog

## Overview

The TUI includes smart service detection that automatically provides canonical configurations for common third-party services. When you name your credential using or including these service names, the TUI will auto-suggest the correct Vault paths and environment variable names.

---

## Supported Services

### Google Gemini AI

**Credential Names:** `gemini`, `google_gemini`, `my_gemini`, etc.

**Canonical Configuration:**
```yaml
Vault Path: secret/data/tenants/{tenant}/users/{user}/gemini/api
Environment Variables:
  - GEMINI_API_KEY
```

**Example Usage:**
```
Credential name: gemini
✓ Detected: Google Gemini AI API
✓ Auto-filled: canonical path and env var
```

---

### GitHub

**Credential Names:** `github`, `personal_github`, `work_github`, etc.

**Canonical Configuration:**
```yaml
Vault Path: secret/data/tenants/{tenant}/users/{user}/github/personal
Environment Variables:
  - GITHUB_TOKEN
```

**Multiple Accounts:**
```
personal_github → secret/.../github/personal
work_github     → secret/.../github/work
```

---

### Slack

**Credential Names:** `slack`, `prod_slack`, `dev_slack`, etc.

**Canonical Configuration:**
```yaml
Vault Path: secret/data/tenants/{tenant}/users/{user}/slack/workspace
Environment Variables:
  - SLACK_TOKEN
```

**Multiple Workspaces:**
```
prod_slack → secret/.../slack/prod
dev_slack  → secret/.../slack/dev
```

---

### OpenAI

**Credential Names:** `openai`, `my_openai`, etc.

**Canonical Configuration:**
```yaml
Vault Path: secret/data/tenants/{tenant}/users/{user}/openai/api
Environment Variables:
  - OPENAI_API_KEY
```

---

### Anthropic Claude

**Credential Names:** `anthropic`, `claude`, etc.

**Canonical Configuration:**
```yaml
Vault Path: secret/data/tenants/{tenant}/users/{user}/anthropic/api
Environment Variables:
  - ANTHROPIC_API_KEY
```

---

### Database Credentials

**Credential Names:** `database`, `db`, `postgres`, etc.

**Canonical Configuration:**
```yaml
Vault Path: secret/data/tenants/{tenant}/users/{user}/database/credentials
Environment Variables:
  - DB_USER
  - DB_PASS
```

---

## How Service Detection Works

### 1. Exact Match
If you enter a credential name that exactly matches a known service, the TUI immediately applies the canonical configuration.

```
Input: "gemini"
Result: ✓ Detected Google Gemini AI API with full config
```

### 2. Partial Match
If your credential name *contains* a known service name, the TUI still detects it.

```
Input: "my_production_github"
Result: ✓ Detected GitHub API (contains "github")
```

### 3. Custom Services
For services not in the catalog, the TUI provides sensible defaults based on the name.

```
Input: "my_custom_service"
Result: Suggested path: secret/data/tenants/{tenant}/users/{user}/my/custom/service
```

---

## Vault Path Templates

### User-Specific (Recommended)
```
secret/data/tenants/{tenant}/users/{user}/SERVICE/api
```
- Each user has their own credentials
- Best for personal API keys
- Enables user-level multi-tenancy

### Service-Wide (Shared)
```
secret/data/tenants/{tenant}/services/SERVICE/env
```
- Shared across all users in a tenant
- Best for service accounts
- Simpler for non-user-specific credentials

---

## Environment Variable Naming

### Pattern
```
{SERVICE_PREFIX}_{FIELD_NAME}
```

### Examples
```
GEMINI_API_KEY      (service: gemini, field: api_key)
GITHUB_TOKEN        (service: github, field: token)
SLACK_BOT_TOKEN     (service: slack, field: bot_token)
DB_USER             (service: database, field: user)
```

### Benefits
- Industry-standard naming
- Easy to identify which service uses which env var
- Compatible with most MCP servers out-of-the-box

---

## Adding Your Own Services

Want to add a new service to the catalog? Edit `tui/src/cli.ts`:

```typescript
const SERVICE_CONFIGS: Record<string, {
  pathSuffix: string;
  envVarPrefix: string;
  fields: Array<{ vaultField: string; envVar: string }>;
  description: string;
}> = {
  // Add your service here
  my_service: {
    pathSuffix: "myservice/api",
    envVarPrefix: "MYSERVICE",
    fields: [{ vaultField: "api_key", envVar: "MYSERVICE_API_KEY" }],
    description: "My Custom Service API",
  },
  // ... existing services
};
```

Then rebuild the TUI:
```bash
cd tui && npm run build
```

---

## Best Practices

### 1. Use Descriptive Names
```
✅ Good: personal_github, work_github, prod_slack
❌ Bad: github1, github2, slack_token
```

### 2. Include Service Name
```
✅ Good: my_openai (detected as OpenAI)
❌ Bad: my_llm (not detected)
```

### 3. Stick to Canonical Paths
The TUI suggests canonical paths for a reason - they follow best practices for multi-tenancy and security.

### 4. Test After Setup
Always use "Test injection" after configuring credentials to verify everything works.

---

## Next Steps

- [TUI_CREDENTIAL_MANAGEMENT.md](./TUI_CREDENTIAL_MANAGEMENT.md) - Complete TUI guide
- [GEMINI_QUICKSTART_TUI.md](./GEMINI_QUICKSTART_TUI.md) - Quick start with Gemini
- [CREDENTIAL_INJECTION.md](./CREDENTIAL_INJECTION.md) - Architecture details

---

*Last updated: Feb 8, 2026*
