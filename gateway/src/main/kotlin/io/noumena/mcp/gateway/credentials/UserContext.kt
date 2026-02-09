package io.noumena.mcp.gateway.credentials

/**
 * User context for credential injection and session isolation.
 * Extracted from JWT or request headers.
 *
 * @param userId       User identity (email or subject from JWT)
 * @param tenantId     Tenant for credential Vault path resolution
 * @param agentSessionId  Unique ID for this agent connection (e.g., "ws-xxx" or "sse-xxx"),
 *                        used to route upstream notifications back to the correct agent.
 *                        Null for HTTP POST requests (no persistent connection for notifications).
 */
data class UserContext(
    val userId: String,
    val tenantId: String = "default",
    val agentSessionId: String? = null
)
