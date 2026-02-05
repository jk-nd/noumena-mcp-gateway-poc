package io.noumena.mcp.executor.npl

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.noumena.mcp.shared.models.NplResumeRequest
import io.noumena.mcp.shared.models.NplValidationRequest
import io.noumena.mcp.shared.models.NplValidationResponse
import kotlinx.serialization.json.*
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * NPL client for Executor.
 * 
 * Calls ToolExecutionPolicy protocol for:
 * 1. Validation - Confirm NPL really approved this request (defense in depth)
 * 2. Completion - Report execution result for audit trail
 */
class NplExecutorClient {
    
    private val nplUrl = System.getenv("NPL_URL") ?: "http://npl-engine:12000"
    private val keycloakUrl = System.getenv("KEYCLOAK_URL") ?: "http://keycloak:11000"
    private val keycloakRealm = System.getenv("KEYCLOAK_REALM") ?: "mcpgateway"
    private val devMode = System.getenv("DEV_MODE")?.toBoolean() ?: false
    
    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    }
    
    // Cache token to avoid repeated auth calls
    private var cachedToken: String? = null
    private var tokenExpiry: Long = 0
    
    // Cache policy ID
    private var policyId: String? = null
    
    /**
     * Validate that NPL approved this request.
     * Calls ToolExecutionPolicy.validateForExecution()
     * Defense-in-depth: confirms NPL record exists before executing.
     */
    suspend fun validate(request: NplValidationRequest): NplValidationResponse {
        logger.info { "========================================" }
        logger.info { "NPL VALIDATION REQUEST" }
        logger.info { "  Request ID: ${request.requestId}" }
        logger.info { "  Service: ${request.service}" }
        logger.info { "  Operation: ${request.operation}" }
        logger.info { "========================================" }
        
        // In dev mode, skip NPL validation
        if (devMode) {
            logger.warn { "DEV_MODE: Skipping NPL validation - auto-approving" }
            return NplValidationResponse(valid = true)
        }
        
        return try {
            val token = getExecutorToken()
            val policy = findPolicyInstance(token)
            
            if (policy == null) {
                logger.error { "No ToolExecutionPolicy instance found" }
                return NplValidationResponse(valid = false, reason = "No policy instance found")
            }
            
            // Call validateForExecution permission
            val requestBody = buildJsonObject {
                put("reqId", request.requestId)
                put("expectedService", request.service)
                put("expectedOperation", request.operation)
            }
            
            logger.info { "Calling NPL: $nplUrl/npl/services/ToolExecutionPolicy/$policy/validateForExecution" }
            logger.debug { "Request body: $requestBody" }
            
            val response = client.post("$nplUrl/npl/services/ToolExecutionPolicy/$policy/validateForExecution") {
                header("Authorization", "Bearer $token")
                contentType(ContentType.Application.Json)
                setBody(requestBody.toString())
            }
            
            val responseBody = response.bodyAsText()
            logger.info { "NPL validation response: ${response.status} - $responseBody" }
            
            if (response.status.isSuccess()) {
                // NPL returns Boolean
                val isValid = responseBody.trim().toBooleanStrictOrNull() ?: 
                    responseBody.trim().removeSurrounding("\"").toBooleanStrictOrNull() ?: false
                
                logger.info { "Validation result: $isValid" }
                NplValidationResponse(valid = isValid)
            } else {
                logger.warn { "Validation failed: ${response.status}" }
                NplValidationResponse(valid = false, reason = "NPL returned ${response.status}")
            }
        } catch (e: Exception) {
            logger.error(e) { "Error validating with NPL" }
            NplValidationResponse(valid = false, reason = "NPL error: ${e.message}")
        }
    }
    
    /**
     * Report execution completion to NPL (audit trail).
     * Calls ToolExecutionPolicy.reportCompletion()
     */
    suspend fun reportCompletion(request: NplResumeRequest) {
        logger.info { "========================================" }
        logger.info { "NPL COMPLETION REPORT" }
        logger.info { "  Request ID: ${request.requestId}" }
        logger.info { "  Success: ${request.success}" }
        logger.info { "  Duration: ${request.durationMs}ms" }
        if (request.error != null) {
            logger.info { "  Error: ${request.error}" }
        }
        logger.info { "========================================" }
        
        // In dev mode, skip NPL reporting
        if (devMode) {
            logger.warn { "DEV_MODE: Skipping NPL completion report" }
            return
        }
        
        try {
            val token = getExecutorToken()
            val policy = findPolicyInstance(token)
            
            if (policy == null) {
                logger.error { "No ToolExecutionPolicy instance found - cannot report completion" }
                return
            }
            
            // Call reportCompletion permission
            val requestBody = buildJsonObject {
                put("reqId", request.requestId)
                put("wasSuccessful", request.success)
                put("failureMessage", request.error ?: "")
                put("execDurationMs", request.durationMs ?: 0)
            }
            
            logger.info { "Calling NPL: $nplUrl/npl/services/ToolExecutionPolicy/$policy/reportCompletion" }
            logger.debug { "Request body: $requestBody" }
            
            val response = client.post("$nplUrl/npl/services/ToolExecutionPolicy/$policy/reportCompletion") {
                header("Authorization", "Bearer $token")
                contentType(ContentType.Application.Json)
                setBody(requestBody.toString())
            }
            
            val responseStatus = response.status
            logger.info { "NPL completion response: $responseStatus" }
            
            if (!responseStatus.isSuccess()) {
                val errorBody = response.bodyAsText()
                logger.warn { "Failed to report completion: $responseStatus - $errorBody" }
            }
        } catch (e: Exception) {
            logger.error(e) { "Error reporting completion to NPL" }
            // Don't throw - we still want to return result to Gateway even if report fails
        }
    }
    
    /**
     * Find the ToolExecutionPolicy instance.
     */
    private suspend fun findPolicyInstance(token: String): String? {
        if (policyId != null) return policyId
        
        try {
            val response = client.get("$nplUrl/npl/services/ToolExecutionPolicy/") {
                header("Authorization", "Bearer $token")
            }
            
            if (response.status.isSuccess()) {
                val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                val items = body["items"]?.jsonArray
                
                if (items != null && items.isNotEmpty()) {
                    policyId = items[0].jsonObject["@id"]?.jsonPrimitive?.content
                    logger.info { "Found ToolExecutionPolicy instance: $policyId" }
                    return policyId
                }
            }
            
            logger.warn { "No ToolExecutionPolicy instance found" }
            return null
        } catch (e: Exception) {
            logger.error(e) { "Error finding ToolExecutionPolicy instance" }
            return null
        }
    }
    
    /**
     * Get executor token from Keycloak.
     * Uses password grant for simplicity (should be client_credentials in production).
     */
    private suspend fun getExecutorToken(): String {
        // Check cache
        if (cachedToken != null && System.currentTimeMillis() < tokenExpiry - 30_000) {
            return cachedToken!!
        }
        
        logger.debug { "Getting executor token from Keycloak" }
        
        // Use password grant with executor user
        val response = client.submitForm(
            url = "$keycloakUrl/realms/$keycloakRealm/protocol/openid-connect/token",
            formParameters = io.ktor.http.parameters {
                append("grant_type", "password")
                append("client_id", "mcpgateway")
                append("username", "executor")
                append("password", System.getenv("EXECUTOR_PASSWORD") ?: "Welcome123")
            }
        )
        
        if (!response.status.isSuccess()) {
            val errorBody = response.bodyAsText()
            logger.error { "Failed to get executor token: ${response.status} - $errorBody" }
            throw RuntimeException("Failed to get executor token: ${response.status}")
        }
        
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        cachedToken = body["access_token"]?.jsonPrimitive?.content
            ?: throw RuntimeException("No access_token in response")
        
        // Parse expiry (default 5 minutes)
        val expiresIn = body["expires_in"]?.jsonPrimitive?.content?.toLongOrNull() ?: 300
        tokenExpiry = System.currentTimeMillis() + (expiresIn * 1000)
        
        logger.info { "Got executor token (expires in ${expiresIn}s)" }
        
        return cachedToken!!
    }
}
