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
            
            // TODO: Decide on fail-open vs fail-closed
            // For now, fail closed (deny)
            PolicyResponse(
                allowed = false,
                reason = "Policy engine unavailable"
            )
        }
    }
}
