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
 * V2 Architecture:
 *   - Gateway uses agent-level credentials to call per-service ToolPolicy instances
 *   - Each service has its own ToolPolicy instance (created via TUI bootstrap)
 *   - The Gateway never holds admin credentials
 *   - Fail-closed: if NPL is unavailable, requests are denied
 *
 * Policy check flow:
 *   1. Get agent token from Keycloak
 *   2. Find ToolPolicy instance for the service
 *   3. Call checkAccess permission
 *   4. Return allow/deny result
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

            // Try per-service ToolPolicy first
            val policyId = findToolPolicyForService(agentToken, service)
            if (policyId != null) {
                return invokeCheckAccess(policyId, agentToken, service, operation, userId)
            }

            // Fallback: check ServiceRegistry directly
            val registryAllowed = checkServiceRegistry(agentToken, service)
            if (!registryAllowed) {
                return PolicyResponse(
                    allowed = false,
                    reason = "Service '$service' is not enabled in ServiceRegistry"
                )
            }

            // Service is enabled in registry but no ToolPolicy exists - allow (backward compat)
            logger.info { "No ToolPolicy for '$service' but service is enabled in registry - allowing" }
            PolicyResponse(
                allowed = true,
                reason = "Service enabled (no per-service policy configured)"
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
     * Get an agent token from Keycloak.
     */
    private suspend fun getAgentToken(): String {
        val response = client.submitForm(
            url = "$keycloakUrl/realms/$keycloakRealm/protocol/openid-connect/token",
            formParameters = parameters {
                append("grant_type", "password")
                append("client_id", "mcpgateway")
                append("username", agentUsername)
                append("password", agentPassword)
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
     * Clear the cached policy IDs (e.g., after NPL bootstrap).
     */
    fun clearCache() {
        policyIds.clear()
    }
}
