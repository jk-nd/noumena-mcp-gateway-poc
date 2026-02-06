package io.noumena.mcp.gateway.auth

import io.ktor.server.auth.jwt.*
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Helper to extract identity information from a validated JWT principal.
 * 
 * The actual JWT validation is handled by Ktor's Authentication plugin
 * configured in Application.kt. This helper extracts the claims into
 * a typed data class for use in request handlers.
 */
data class UserIdentity(
    val subject: String,           // Keycloak user ID (UUID)
    val preferredUsername: String,  // Human-readable username
    val email: String?,
    val roles: List<String>,       // Custom "role" attribute from Keycloak
    val organization: List<String> // Custom "organization" attribute
) {
    fun hasRole(role: String): Boolean = roles.contains(role)
    fun isAdmin(): Boolean = hasRole("admin")
    fun isAgent(): Boolean = hasRole("agent")
    fun isExecutor(): Boolean = hasRole("executor")
}

/**
 * Extract UserIdentity from a validated JWTPrincipal.
 */
fun JWTPrincipal.toUserIdentity(): UserIdentity {
    val payload = this.payload
    return UserIdentity(
        subject = payload.subject ?: "unknown",
        preferredUsername = payload.getClaim("preferred_username")?.asString() ?: "unknown",
        email = payload.getClaim("email")?.asString(),
        roles = payload.getClaim("role")?.asList(String::class.java) ?: emptyList(),
        organization = payload.getClaim("organization")?.asList(String::class.java) ?: emptyList()
    )
}
