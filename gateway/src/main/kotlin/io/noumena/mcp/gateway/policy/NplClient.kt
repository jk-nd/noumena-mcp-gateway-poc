package io.noumena.mcp.gateway.policy

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.noumena.mcp.shared.models.PolicyResponse
import kotlinx.serialization.json.*
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Client for NPL Engine policy checks.
 *
 * V3 Architecture:
 *   - Gateway authenticates as system service (role=gateway)
 *   - Each service has its own ToolPolicy instance (service-level governance)
 *   - Each tool user has their own UserToolAccess instance (user-level governance)
 *   - Tool users (humans and AI) are treated identically from governance perspective
 *   - The Gateway never holds admin credentials
 *   - Fail-closed: if NPL is unavailable, requests are denied
 *
 * Policy check flow:
 *   1. Get gateway service token from Keycloak (role=gateway)
 *   2. Find ToolPolicy instance for the service (service-level check)
 *   3. Call checkAccess permission on ToolPolicy as pGateway
 *   4. Find UserToolAccess instance for the tool user (user-level check)
 *   5. Call hasAccess permission on UserToolAccess as pGateway
 *   6. Return allow/deny result (both checks must pass)
 */
class NplClient {

    private val nplUrl = System.getenv("NPL_URL") ?: "http://npl-engine:12000"
    private val keycloakUrl = System.getenv("KEYCLOAK_URL") ?: "http://keycloak:11000"
    private val keycloakRealm = System.getenv("KEYCLOAK_REALM") ?: "mcpgateway"
    private val agentUsername = System.getenv("AGENT_USERNAME") ?: "agent"
    private val agentPassword = System.getenv("AGENT_PASSWORD") ?: "Welcome123"
    private val devMode = System.getenv("DEV_MODE")?.toBoolean() ?: false

    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    }

    // Cached ToolPolicy instance IDs per service
    private val policyIds = mutableMapOf<String, String>()
    
    // Cached UserToolAccess instance IDs per user
    private val userAccessIds = mutableMapOf<String, String>()

    /**
     * Check policy for a tool call.
     *
     * @param service The upstream service name (e.g., "duckduckgo")
     * @param operation The tool name (e.g., "search")
     * @param userId The user/agent making the request
     * @return PolicyResponse indicating allow/deny
     */
    suspend fun checkPolicy(
        service: String,
        operation: String,
        userId: String
    ): PolicyResponse {
        logger.info { "Policy check: $service.$operation (user: $userId)" }

        // DEV MODE: Bypass NPL and auto-allow
        if (devMode) {
            return handleDevMode(service, operation)
        }

        return try {
            val agentToken = getAgentToken()

            // Step 1: Check service-level ToolPolicy
            val policyId = findToolPolicyForService(agentToken, service)
            if (policyId != null) {
                val policyResult = invokeCheckAccess(policyId, agentToken, service, operation, userId)
                if (!policyResult.allowed) {
                    return policyResult // Service-level policy denied
                }
                logger.info { "ToolPolicy check passed for $service.$operation" }
            } else {
                // Fallback: check ServiceRegistry directly
                val registryAllowed = checkServiceRegistry(agentToken, service)
                if (!registryAllowed) {
                    return PolicyResponse(
                        allowed = false,
                        reason = "Service '$service' is not enabled in ServiceRegistry"
                    )
                }
                logger.info { "No ToolPolicy for '$service' but service is enabled in registry" }
            }

            // Step 2: Check user-level UserToolAccess (NEW)
            val userAccessId = findUserToolAccess(agentToken, userId)
            if (userAccessId != null) {
                val userAccessResult = invokeUserAccessCheck(userAccessId, agentToken, service, operation, userId)
                if (!userAccessResult.allowed) {
                    return userAccessResult // User-level access denied
                }
                logger.info { "UserToolAccess check passed for user $userId on $service.$operation" }
            } else {
                // No per-user access control configured - allow (backward compat)
                logger.info { "No UserToolAccess configured for user '$userId' - allowing" }
            }

            // Both checks passed (or were not configured)
            PolicyResponse(
                allowed = true,
                reason = "Policy checks passed"
            )
        } catch (e: Exception) {
            logger.error(e) { "Policy check failed for $service.$operation" }
            PolicyResponse(
                allowed = false,
                reason = "Policy check failed: ${e.message}"
            )
        }
    }

    /**
     * Get a gateway service token from Keycloak.
     * The Gateway authenticates as a system service (role=gateway).
     */
    private suspend fun getAgentToken(): String {
        val response = client.submitForm(
            url = "$keycloakUrl/realms/$keycloakRealm/protocol/openid-connect/token",
            formParameters = parameters {
                append("grant_type", "password")
                append("client_id", "mcpgateway")
                append("username", agentUsername)  // TODO: Rename to gatewayUsername
                append("password", agentPassword)  // TODO: Rename to gatewayPassword
            }
        )

        if (!response.status.isSuccess()) {
            throw RuntimeException("Failed to get agent token: ${response.status}")
        }

        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        return body["access_token"]?.jsonPrimitive?.content
            ?: throw RuntimeException("No access_token in response")
    }

    /**
     * Find a ToolPolicy instance for a specific service.
     * Returns the instance ID, or null if no ToolPolicy exists for this service.
     */
    private suspend fun findToolPolicyForService(agentToken: String, serviceName: String): String? {
        // Check cache first
        policyIds[serviceName]?.let { return it }

        try {
            val listResponse = client.get("$nplUrl/npl/services/ToolPolicy/") {
                header("Authorization", "Bearer $agentToken")
            }

            if (!listResponse.status.isSuccess()) return null

            val responseText = listResponse.bodyAsText()
            val listBody = Json.parseToJsonElement(responseText).jsonObject
            val items = listBody["items"]?.jsonArray ?: return null

            // Find the ToolPolicy instance matching this service name
            for (item in items) {
                val obj = item.jsonObject
                val policyServiceName = obj["policyServiceName"]?.jsonPrimitive?.content
                if (policyServiceName == serviceName) {
                    val id = obj["@id"]?.jsonPrimitive?.content
                    if (id != null) {
                        policyIds[serviceName] = id
                        logger.info { "Found ToolPolicy for '$serviceName': $id" }
                        return id
                    }
                }
            }
        } catch (e: Exception) {
            logger.debug { "No ToolPolicy found for '$serviceName': ${e.message}" }
        }

        return null
    }

    /**
     * Invoke checkAccess permission on a ToolPolicy instance.
     */
    private suspend fun invokeCheckAccess(
        policyId: String,
        token: String,
        service: String,
        operation: String,
        userId: String
    ): PolicyResponse {
        val requestBody = buildJsonObject {
            put("toolName", operation)
            put("callerIdentity", userId)
        }

        logger.info { "NPL: POST /services/ToolPolicy/$policyId/checkAccess ($service.$operation)" }

        val response = client.post("$nplUrl/npl/services/ToolPolicy/$policyId/checkAccess") {
            header("Authorization", "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(requestBody.toString())
        }

        val responseBody = response.bodyAsText()

        return if (response.status.isSuccess()) {
            val allowed = responseBody.trim().removeSurrounding("\"").toBoolean()
            PolicyResponse(
                allowed = allowed,
                reason = if (allowed) "Policy approved" else "Tool access denied by policy"
            )
        } else {
            val errorMessage = try {
                val errorJson = Json.parseToJsonElement(responseBody).jsonObject
                errorJson["message"]?.jsonPrimitive?.content ?: responseBody
            } catch (e: Exception) {
                responseBody
            }
            PolicyResponse(
                allowed = false,
                reason = errorMessage
            )
        }
    }

    /**
     * Find a UserToolAccess instance for a specific user.
     * Returns the instance ID, or null if no UserToolAccess exists for this user.
     */
    private suspend fun findUserToolAccess(agentToken: String, userId: String): String? {
        // Check cache first
        userAccessIds[userId]?.let { return it }

        try {
            // NPL doesn't support filtering by constructor params via URL query
            // So we query all UserToolAccess and filter client-side
            val listResponse = client.get("$nplUrl/npl/users/UserToolAccess/") {
                header("Authorization", "Bearer $agentToken")
            }

            if (!listResponse.status.isSuccess()) return null

            val responseText = listResponse.bodyAsText()
            val listBody = Json.parseToJsonElement(responseText).jsonObject
            val items = listBody["items"]?.jsonArray ?: return null

            // Find the UserToolAccess with matching userId
            for (item in items) {
                val obj = item.jsonObject
                val objectUserId = obj["userId"]?.jsonPrimitive?.content
                if (objectUserId == userId) {
                    val id = obj["@id"]?.jsonPrimitive?.content
                    if (id != null) {
                        userAccessIds[userId] = id
                        logger.info { "Found UserToolAccess for user '$userId': $id" }
                        return id
                    }
                }
            }
            logger.debug { "No UserToolAccess found with userId='$userId' (searched ${items.size} objects)" }
        } catch (e: Exception) {
            logger.debug { "Error finding UserToolAccess for user '$userId': ${e.message}" }
        }

        return null
    }

    /**
     * Invoke hasAccess permission on a UserToolAccess instance.
     */
    private suspend fun invokeUserAccessCheck(
        accessId: String,
        token: String,
        service: String,
        operation: String,
        userId: String
    ): PolicyResponse {
        val requestBody = buildJsonObject {
            put("serviceName", service)
            put("toolName", operation)
        }

        logger.info { "NPL: POST /users/UserToolAccess/$accessId/hasAccess ($service.$operation for $userId)" }

        val response = client.post("$nplUrl/npl/users/UserToolAccess/$accessId/hasAccess") {
            header("Authorization", "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(requestBody.toString())
        }

        val responseBody = response.bodyAsText()

        return if (response.status.isSuccess()) {
            val allowed = responseBody.trim().removeSurrounding("\"").toBoolean()
            PolicyResponse(
                allowed = allowed,
                reason = if (allowed) "User access approved" else "User does not have access to this tool"
            )
        } else {
            val errorMessage = try {
                val errorJson = Json.parseToJsonElement(responseBody).jsonObject
                errorJson["message"]?.jsonPrimitive?.content ?: responseBody
            } catch (e: Exception) {
                responseBody
            }
            PolicyResponse(
                allowed = false,
                reason = "User access check failed: $errorMessage"
            )
        }
    }

    /**
     * Check if a service is enabled in the ServiceRegistry.
     * Fallback when no per-service ToolPolicy exists.
     */
    private suspend fun checkServiceRegistry(agentToken: String, serviceName: String): Boolean {
        try {
            val listResponse = client.get("$nplUrl/npl/registry/ServiceRegistry/") {
                header("Authorization", "Bearer $agentToken")
            }

            if (!listResponse.status.isSuccess()) return false

            val responseText = listResponse.bodyAsText()
            val listBody = Json.parseToJsonElement(responseText).jsonObject
            val items = listBody["items"]?.jsonArray

            if (items != null && items.isNotEmpty()) {
                val registry = items[0].jsonObject
                val enabledServices = registry["enabledServices"]?.jsonArray
                if (enabledServices != null) {
                    return enabledServices.any {
                        it.jsonPrimitive.content == serviceName
                    }
                }
            }
        } catch (e: Exception) {
            logger.warn { "Failed to check ServiceRegistry: ${e.message}" }
        }

        return false
    }

    /**
     * DEV MODE: Bypass NPL and auto-allow all requests.
     */
    private fun handleDevMode(service: String, operation: String): PolicyResponse {
        logger.info { "DEV MODE: Auto-allowing $service.$operation" }
        return PolicyResponse(
            allowed = true,
            reason = "DEV MODE: Auto-allowed"
        )
    }

    /**
     * Get all enabled services from NPL ServiceRegistry.
     * This is the source of truth for which services are available at runtime.
     * 
     * @return Set of enabled service names, or empty set if registry unavailable
     */
    suspend fun getEnabledServices(): Set<String> {
        return try {
            val agentToken = getAgentToken()
            
            val listResponse = client.get("$nplUrl/npl/registry/ServiceRegistry/") {
                header("Authorization", "Bearer $agentToken")
            }

            if (!listResponse.status.isSuccess()) {
                logger.warn { "Failed to query ServiceRegistry: ${listResponse.status}" }
                return emptySet()
            }

            val responseText = listResponse.bodyAsText()
            val listBody = Json.parseToJsonElement(responseText).jsonObject
            val items = listBody["items"]?.jsonArray

            if (items != null && items.isNotEmpty()) {
                val registry = items[0].jsonObject
                val enabledServices = registry["enabledServices"]?.jsonArray
                if (enabledServices != null) {
                    val services = enabledServices.map { it.jsonPrimitive.content }.toSet()
                    logger.info { "NPL: Retrieved ${services.size} enabled services from ServiceRegistry" }
                    return services
                }
            }
            
            logger.warn { "ServiceRegistry exists but has no enabled services" }
            emptySet()
        } catch (e: Exception) {
            logger.error(e) { "Failed to get enabled services from NPL: ${e.message}" }
            emptySet()
        }
    }

    /**
     * Check if a specific service is enabled in NPL ServiceRegistry.
     * 
     * @param serviceName The service name to check
     * @return true if the service is enabled in NPL
     */
    suspend fun isServiceEnabled(serviceName: String): Boolean {
        return getEnabledServices().contains(serviceName)
    }

    /**
     * Clear the cached policy IDs and user access IDs (e.g., after NPL bootstrap).
     */
    fun clearCache() {
        policyIds.clear()
        userAccessIds.clear()
    }
}
