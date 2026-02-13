rootProject.name = "noumena-mcp-gateway"

include("shared")
// governance-service removed — replaced by OPA Rego policy (policies/mcp_authz.rego)
include("credential-proxy")
include("integration-tests")
