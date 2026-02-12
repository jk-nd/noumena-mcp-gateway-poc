rootProject.name = "noumena-mcp-gateway"

include("shared")
include("governance-service")
// gateway module removed — replaced by Envoy AI Gateway + governance-service
include("credential-proxy")
include("integration-tests")
