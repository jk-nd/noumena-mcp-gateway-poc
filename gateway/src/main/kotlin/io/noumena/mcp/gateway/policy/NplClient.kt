package io.noumena.mcp.gateway.policy

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.noumena.mcp.shared.models.PolicyRequest
import io.noumena.mcp.shared.models.PolicyResponse
import kotlinx.serialization.json.Json
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Client for NPL Engine policy checks.
 * 
 * Sends policy requests to NPL Engine.
 * NPL Engine evaluates the request and, if allowed, triggers Executor.
 */
class NplClient {
    
    private val nplUrl = System.getenv("NPL_URL") ?: "http://npl-engine:8080"
    private val devMode = System.getenv("DEV_MODE")?.toBoolean() ?: true
    
    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
            })
        }
    }
    
    /**
     * Check policy with NPL Engine.
     * 
     * If allowed, NPL will automatically trigger the Executor via notification.
     * Gateway just needs to wait for the callback.
     */
    suspend fun checkPolicy(request: PolicyRequest): PolicyResponse {
        logger.info { "Checking policy: service=${request.service}, operation=${request.operation}" }
        
        // DEV MODE: Bypass NPL and auto-allow
        if (devMode) {
            logger.warn { "DEV MODE: Auto-allowing request (NPL bypassed)" }
            val requestId = "dev-${System.currentTimeMillis()}"
            
            // In dev mode, we need to manually trigger the executor
            // In production, NPL does this via HTTP Bridge
            triggerExecutorDirectly(request, requestId)
            
            return PolicyResponse(
                allowed = true,
                requestId = requestId,
                reason = "DEV MODE: Auto-allowed"
            )
        }
        
        return try {
            val response = client.post("$nplUrl/api/policy/check") {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
            
            if (response.status.isSuccess()) {
                Json.decodeFromString<PolicyResponse>(response.bodyAsText())
            } else {
                logger.error { "NPL policy check failed: ${response.status}" }
                PolicyResponse(
                    allowed = false,
                    reason = "Policy check failed: ${response.status}"
                )
            }
        } catch (e: Exception) {
            logger.error(e) { "Error calling NPL Engine" }
            
            // Fail closed (deny) when NPL is unavailable
            PolicyResponse(
                allowed = false,
                reason = "Policy engine unavailable"
            )
        }
    }
    
    /**
     * DEV MODE ONLY: Directly trigger executor (simulates what NPL HTTP Bridge does)
     */
    private suspend fun triggerExecutorDirectly(request: PolicyRequest, requestId: String) {
        val executorUrl = System.getenv("EXECUTOR_URL") ?: "http://executor:8081"
        
        try {
            val executeRequest = io.noumena.mcp.shared.models.ExecuteRequest(
                requestId = requestId,
                tenantId = request.tenantId,
                userId = request.userId,
                service = request.service,
                operation = request.operation,
                params = request.params,
                callbackUrl = request.callbackUrl
            )
            
            client.post("$executorUrl/execute") {
                contentType(ContentType.Application.Json)
                setBody(executeRequest)
            }
            logger.info { "DEV MODE: Triggered executor for $requestId" }
        } catch (e: Exception) {
            logger.error(e) { "DEV MODE: Failed to trigger executor" }
        }
    }
}
