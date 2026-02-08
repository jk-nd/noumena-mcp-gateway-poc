# Credential Proxy

Secure credential injection for the Noumena MCP Gateway.

## Overview

The Credential Proxy is a standalone service that:

1. **Selects** which credential to use (YAML or NPL-based)
2. **Fetches** secrets from HashiCorp Vault
3. **Injects** credentials into upstream MCP connections

The Gateway has **NO Vault access** - only the Credential Proxy does.

## Architecture

```
Gateway → HTTP → Credential Proxy → Vault
                        ↓
                   (Optional) NPL Engine
```

## Modes

### Simple Mode (Default)

Direct YAML lookup:

```yaml
mode: simple
service_defaults:
  github: work_github
```

**Flow:**
1. Gateway: "Get credentials for github"
2. Proxy: Look up `service_defaults[github]` → `"work_github"`
3. Proxy: Look up `credentials[work_github]` → vault path
4. Proxy: Fetch from Vault
5. Proxy: Return injected fields

**Latency:** ~7ms (YAML lookup + Vault fetch)

### Expert Mode

NPL-based selection with conditional rules:

```yaml
mode: expert
```

**Flow:**
1. Gateway: "Get credentials for github.create_issue with metadata {repo: 'acme/foo'}"
2. Proxy: Call NPL `selectCredential()` with request context
3. NPL: Evaluate rules, return `"work_github"`
4. Proxy: Look up `credentials[work_github]` → vault path
5. Proxy: Fetch from Vault
6. Proxy: Return injected fields

**Latency:** ~17ms (NPL call + YAML lookup + Vault fetch)

## API

### POST `/inject-credentials`

Request credentials for a service/operation.

**Request:**
```json
{
  "service": "github",
  "operation": "create_issue",
  "metadata": {
    "repo": "acme/foo"
  },
  "tenantId": "acme",
  "userId": "alice"
}
```

**Response:**
```json
{
  "credentialName": "work_github",
  "injectedFields": {
    "GITHUB_TOKEN": "ghp_xxxxxxxxxxxx"
  }
}
```

### GET `/health`

Health check.

**Response:**
```json
{
  "status": "ok",
  "mode": "simple"
}
```

### POST `/admin/reload-config`

Reload `credentials.yaml` without restart.

### POST `/admin/clear-cache?path=...`

Clear Vault credential cache.

## Configuration

### Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `PORT` | `9002` | HTTP port |
| `CREDENTIAL_MODE` | `simple` | Credential selection mode |
| `CREDENTIAL_CONFIG_PATH` | `/app/configs/credentials.yaml` | Path to config |
| `NPL_URL` | `http://npl-engine:12000` | NPL Engine URL (expert mode) |
| `VAULT_ADDR` | `http://vault:8200` | Vault address |
| `VAULT_TOKEN` | `dev-token` | Vault authentication token |

### credentials.yaml

See [configs/credentials.yaml](../configs/credentials.yaml) for full example.

**Minimal:**
```yaml
mode: simple

credentials:
  my_github:
    vault_path: "secret/data/tenants/{tenant}/users/{user}/github/personal"
    injection:
      type: env
      mapping:
        token: "GITHUB_TOKEN"

service_defaults:
  github: my_github
```

## Vault Integration

### Credential Cache

- **TTL:** 5 minutes
- **Strategy:** Per-path cache
- **Eviction:** Manual (`/admin/clear-cache`) or TTL

### Path Interpolation

Variables in `vault_path` are replaced at runtime:

- `{tenant}` → `tenantId` from request
- `{user}` → `userId` from request

**Example:**
```yaml
vault_path: "secret/data/tenants/{tenant}/users/{user}/github/work"
```

For `tenantId=acme`, `userId=alice`:
```
secret/data/tenants/acme/users/alice/github/work
```

## NPL Integration (Expert Mode)

### Protocol: CredentialInjectionPolicy

Located at: `npl/src/main/npl-1.0/policies/credential_injection_policy.npl`

**Permissions:**
- `selectCredential` - Select credential based on request context
- `addInjectionRule` - Add new rule
- `removeInjectionRule` - Remove rule

**Example Rule:**
```npl
addInjectionRule(
  service: "github",
  credentialName: "work_github",
  condition: "metadata.repo startsWith 'acme/'",
  priority: 10
)
```

## Development

### Build

```bash
./gradlew :credential-proxy:build
```

### Run Locally

```bash
# Set environment
export CREDENTIAL_CONFIG_PATH=../configs/credentials.yaml
export VAULT_ADDR=http://localhost:8200
export VAULT_TOKEN=dev-token
export CREDENTIAL_MODE=simple

# Run
./gradlew :credential-proxy:run
```

### Docker

```bash
docker build -f deployments/docker/Dockerfile.credential-proxy -t credential-proxy .
docker run -p 9002:9002 \
  -e CREDENTIAL_MODE=simple \
  -v $(pwd)/configs:/app/configs:ro \
  credential-proxy
```

## Testing

```bash
# Unit tests
./gradlew :credential-proxy:test

# Manual test
curl -X POST http://localhost:9002/inject-credentials \
  -H "Content-Type: application/json" \
  -d '{
    "service": "github",
    "operation": "create_issue",
    "tenantId": "acme",
    "userId": "alice"
  }'
```

## Security

### Design Principles

1. **Isolated component** - Only service with Vault access
2. **Memory-only secrets** - Never logged or persisted
3. **Short-lived cache** - 5 minute TTL
4. **Network isolation** - Only accessible via `secrets-net`

### Vault Authentication

**Development:** Static token (`VAULT_TOKEN=dev-token`)

**Production:** Use Kubernetes service account or AppRole:

```bash
export VAULT_TOKEN=$(vault login -token-only -method=kubernetes role=credential-proxy)
```

## Monitoring

### Metrics

Expose Prometheus metrics (future):

- `credential_selections_total` - Counter
- `credential_cache_hits` - Counter
- `credential_cache_misses` - Counter
- `vault_fetch_duration_seconds` - Histogram

### Logs

Structured logs (JSON):

```json
{
  "level": "INFO",
  "message": "Credential injection request: github.create_issue for alice@acme",
  "service": "github",
  "operation": "create_issue",
  "tenantId": "acme",
  "userId": "alice"
}
```

## Troubleshooting

### "Credential 'xxx' not found in configuration"

Check `credentials.yaml`:
```bash
cat configs/credentials.yaml | grep xxx
```

### "Failed to fetch credentials: 403"

Vault token invalid or expired:
```bash
vault token lookup
```

### "CredentialInjectionPolicy not initialized"

NPL protocol not bootstrapped (expert mode):
```bash
curl http://localhost:12000/npl/policies/CredentialInjectionPolicy/
```

Bootstrap via TUI: `System > NPL Bootstrap`

## See Also

- [Full documentation](../docs/CREDENTIAL_INJECTION.md)
- [Architecture V2](../docs/ARCHITECTURE_V2.md)
- [credentials.yaml example](../configs/credentials.yaml)
