package io.noumena.mcp.gateway.credentials

/**
 * User context for credential injection.
 * Extracted from JWT or request headers.
 */
data class UserContext(
    val userId: String,
    val tenantId: String = "default"
)
