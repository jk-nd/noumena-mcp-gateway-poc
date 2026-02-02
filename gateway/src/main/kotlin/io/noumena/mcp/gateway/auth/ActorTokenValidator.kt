package io.noumena.mcp.gateway.auth

import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Claims extracted from a validated Actor Token.
 */
data class ActorTokenClaims(
    val tenantId: String,
    val delegatedBy: String,  // The user who delegated to the agent
    val agentId: String,
    val allowedServices: List<String>,
    val delegationId: String
) {
    fun hasScope(service: String): Boolean {
        return allowedServices.contains(service) || allowedServices.contains("*")
    }
}

/**
 * Validates Actor Tokens (delegated JWTs) via Keycloak.
 * 
 * Actor Tokens are issued when a user delegates to an agent.
 * They contain:
 * - sub: agent ID
 * - act.sub: delegating user
 * - noumena.allowed_services: services the agent can access
 */
class ActorTokenValidator {
    
    private val issuer = System.getenv("OIDC_ISSUER") ?: "http://keycloak:8080/realms/noumena"
    
    /**
     * Validate an Actor Token and extract claims.
     * 
     * TODO: Implement actual JWT validation with Keycloak JWKS
     */
    fun validate(token: String): ActorTokenClaims {
        // TODO: Implement real validation:
        // 1. Fetch JWKS from $issuer/.well-known/openid-configuration
        // 2. Verify JWT signature
        // 3. Check expiration
        // 4. Extract claims
        
        logger.debug { "Validating token (stub implementation)" }
        
        // STUB: For development, return mock claims
        // REMOVE THIS IN PRODUCTION
        return ActorTokenClaims(
            tenantId = "demo-tenant",
            delegatedBy = "user@example.com",
            agentId = "dev-agent",
            allowedServices = listOf("google_gmail", "slack", "sap"),
            delegationId = "del-dev-001"
        )
    }
}
