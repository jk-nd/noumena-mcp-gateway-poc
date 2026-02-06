package io.noumena.mcp.gateway.policy

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.noumena.mcp.shared.models.PolicyRequest
import io.noumena.mcp.shared.models.PolicyResponse
import kotlinx.serialization.json.*
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Client for NPL Engine policy checks.
 * 
 * The Gateway only uses the `agent` role to call checkAndApprove on ToolExecutionPolicy.
 * It never holds admin credentials. All admin-level NPL operations (creating
 * ServiceRegistry, ToolExecutionPolicy, enabling/disabling services) are performed
 * via the TUI, which the admin runs locally.
 * 
 * NPL Engine checks:
 * 1. ServiceRegistry has the service enabled
 * 2. Policy-specific rules
 * 
 * If allowed, NPL returns a request ID and emits ExecutionNotificationMessage to RabbitMQ.
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
            json(Json {
                ignoreUnknownKeys = true
            })
        }
    }
    
    // Cached ToolExecutionPolicy instance ID
    private var policyId: String? = null
    
    /**
     * Check policy with NPL Engine via ToolExecutionPolicy.checkAndApprove.
     * 
     * If allowed, NPL will emit ExecutionNotificationMessage to RabbitMQ.
     * Executor consumes the notification and executes the request.
     * Gateway returns the request ID to the agent.
     */
    suspend fun checkPolicy(request: PolicyRequest): PolicyResponse {
        logger.info { "" }
        logger.info { "┌──────────────────────────────────────────────────────────────┐" }
        logger.info { "│ → OUTGOING: Gateway → NPL Engine [Policy Check]             │" }
        logger.info { "├──────────────────────────────────────────────────────────────┤" }
        logger.info { "│ Service:   ${request.service}" }
        logger.info { "│ Operation: ${request.operation}" }
        logger.info { "│ Metadata:  ${request.metadata}" }
        logger.info { "│ Callback:  ${request.callbackUrl}" }
        logger.info { "└──────────────────────────────────────────────────────────────┘" }
        
        // DEV MODE: Bypass NPL and auto-allow
        if (devMode) {
            return handleDevMode(request)
        }
        
        return try {
            // Get agent token from Keycloak (minimum privilege)
            val agentToken = getAgentToken()
            
            // Find ToolExecutionPolicy instance (must be bootstrapped via TUI)
            val toolPolicyId = findPolicyId(agentToken)
            
            // Encode metadata as JSON string
            val metadataJson = buildJsonObject {
                request.metadata.forEach { (k, v) -> put(k, v) }
            }.toString()
            
            // Call checkAndApprove on ToolExecutionPolicy
            val result = invokeCheckAndApprove(
                policyId = toolPolicyId,
                token = agentToken,
                service = request.service,
                operation = request.operation,
                metadata = metadataJson,
                callbackUrl = request.callbackUrl
            )
            
            result
        } catch (e: Exception) {
            logger.error(e) { "Policy check failed" }
            
            PolicyResponse(
                allowed = false,
                reason = e.message ?: "Policy check failed"
            )
        }
    }
    
    /**
     * Get a token for the agent from Keycloak.
     * The agent has minimum privilege - it can only call checkAndApprove.
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
     * Find the existing ToolExecutionPolicy instance.
     * The policy must be bootstrapped via the TUI before the Gateway can operate.
     * The agent role (pAgent) has read access to list protocol instances.
     */
    private suspend fun findPolicyId(agentToken: String): String {
        if (policyId != null) return policyId!!
        
        val listResponse = client.get("$nplUrl/npl/services/ToolExecutionPolicy/") {
            header("Authorization", "Bearer $agentToken")
        }
        
        val responseText = listResponse.bodyAsText()
        val listBody = Json.parseToJsonElement(responseText).jsonObject
        val items = listBody["items"]?.jsonArray
        
        if (items != null && items.isNotEmpty()) {
            policyId = items[0].jsonObject["@id"]?.jsonPrimitive?.content
            logger.info { "Found ToolExecutionPolicy: $policyId" }
            return policyId!!
        }
        
        throw RuntimeException(
            "ToolExecutionPolicy not initialized. " +
            "Run 'npm start' in the TUI directory and use 'NPL Bootstrap' to set up."
        )
    }
    
    /**
     * Invoke checkAndApprove permission on ToolExecutionPolicy.
     */
    private suspend fun invokeCheckAndApprove(
        policyId: String,
        token: String,
        service: String,
        operation: String,
        metadata: String,
        callbackUrl: String
    ): PolicyResponse {
        // Build JSON body - parameter names must match NPL permission parameters
        val requestBody = buildJsonObject {
            put("serviceName", service)
            put("operationName", operation)
            put("requestMetadata", metadata)
            put("gatewayCallbackUrl", callbackUrl)
        }
        
        val prettyJson = Json { prettyPrint = true }
        
        logger.info { "" }
        logger.info { "┌──────────────────────────────────────────────────────────────┐" }
        logger.info { "│ → NPL API: POST /checkAndApprove                            │" }
        logger.info { "├──────────────────────────────────────────────────────────────┤" }
        logger.info { "│ URL: $nplUrl/npl/services/ToolExecutionPolicy/$policyId/checkAndApprove" }
        logger.info { "│ Body:" }
        prettyJson.encodeToString(JsonObject.serializer(), requestBody).lines().forEach { line ->
            logger.info { "│   $line" }
        }
        logger.info { "└──────────────────────────────────────────────────────────────┘" }
        
        val response = client.post("$nplUrl/npl/services/ToolExecutionPolicy/$policyId/checkAndApprove") {
            header("Authorization", "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(requestBody.toString())
        }
        
        val responseBody = response.bodyAsText()
        
        logger.info { "" }
        logger.info { "┌──────────────────────────────────────────────────────────────┐" }
        logger.info { "│ ← NPL Response: ${response.status}" }
        logger.info { "├──────────────────────────────────────────────────────────────┤" }
        logger.info { "│ $responseBody" }
        logger.info { "└──────────────────────────────────────────────────────────────┘" }
        
        return if (response.status.isSuccess()) {
            // NPL returns the request ID as the result
            val requestId = responseBody.trim().removeSurrounding("\"")
            
            logger.info { "│ ✓ POLICY APPROVED - Request ID: $requestId" }
            
            PolicyResponse(
                allowed = true,
                requestId = requestId,
                reason = "Policy approved by NPL"
            )
        } else {
            // Parse error message
            val errorMessage = try {
                val errorJson = Json.parseToJsonElement(responseBody).jsonObject
                errorJson["message"]?.jsonPrimitive?.content ?: responseBody
            } catch (e: Exception) {
                responseBody
            }
            
            logger.info { "│ ✗ POLICY DENIED - Reason: $errorMessage" }
            
            PolicyResponse(
                allowed = false,
                reason = errorMessage
            )
        }
    }
    
    /**
     * DEV MODE: Bypass NPL and auto-allow.
     * 
     * In dev mode with RabbitMQ, we still need the Executor to be running.
     * This mode just skips NPL policy check - the Executor will still
     * validate with NPL (which will pass in dev mode).
     */
    private suspend fun handleDevMode(request: PolicyRequest): PolicyResponse {
        val requestId = "dev-${System.currentTimeMillis()}"
        
        logger.info { "" }
        logger.info { "┌──────────────────────────────────────────────────────────────┐" }
        logger.info { "│ ⚠️  DEV MODE: NPL Policy Check BYPASSED                       │" }
        logger.info { "├──────────────────────────────────────────────────────────────┤" }
        logger.info { "│ Service:    ${request.service}" }
        logger.info { "│ Operation:  ${request.operation}" }
        logger.info { "│ Request ID: $requestId" }
        logger.info { "│ Status:     AUTO-APPROVED (dev mode)" }
        logger.info { "└──────────────────────────────────────────────────────────────┘" }
        
        return PolicyResponse(
            allowed = true,
            requestId = requestId,
            reason = "DEV MODE: Auto-allowed (ensure Executor is running)"
        )
    }
}
