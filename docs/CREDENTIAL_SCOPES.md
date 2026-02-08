# Credential Scope Management

## Two Credential Scopes

**Tenant-Level**: One API key shared by all users in organization
- Path: `secret/data/tenants/{tenant}/services/SERVICE/...`
- Example: Company Slack workspace

**User-Level**: Each user has their own API key  
- Path: `secret/data/tenants/{tenant}/users/{user}/SERVICE/...`
- Example: Personal GitHub tokens

## TUI Workflow

1. Add service (e.g., Google Gemini)
2. Manage Credentials → Add credential
3. **Choose scope**: Tenant-level or User-level
4. **Store secrets**:
   - Tenant: Enter once, available to all users
   - User: Select users, enter per-user secrets

## Runtime

JWT provides `{tenant}` and `{user}` → VaultClient interpolates path → fetches secret

## Examples

```yaml
# Tenant-level
company_gemini:
  vault_path: secret/data/tenants/{tenant}/services/gemini/api

# User-level  
personal_gemini:
  vault_path: secret/data/tenants/{tenant}/users/{user}/gemini/api
```
