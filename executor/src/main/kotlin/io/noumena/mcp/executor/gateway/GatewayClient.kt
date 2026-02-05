package io.noumena.mcp.executor.gateway

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.noumena.mcp.shared.models.ContextResponse
import io.noumena.mcp.shared.models.ExecuteResult
import io.noumena.mcp.shared.models.StoredContext
import kotlinx.serialization.json.Json
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Client for communicating with the Gateway.
 * 
 * Provides:
 * 1. Fetch context - Get the full request body stored by Gateway
 * 2. Send callback - Return execution result to Gateway
 */
class GatewayClient {
    
    private val json = Json { 
        ignoreUnknownKeys = true 
        prettyPrint = true
    }
    
    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(this@GatewayClient.json)
        }
    }
    
    /**
     * Fetch stored context from Gateway.
     * Called after NPL validation to get the full request body.
     */
    suspend fun fetchContext(gatewayUrl: String, requestId: String): StoredContext? {
        logger.info { "┌────────────────────────────────────────────────────────────────┐" }
        logger.info { "│ GATEWAY CLIENT - Fetching Context                              │" }
        logger.info { "├────────────────────────────────────────────────────────────────┤" }
        logger.info { "│ URL: $gatewayUrl/context/$requestId" }
        logger.info { "└────────────────────────────────────────────────────────────────┘" }
        
        return try {
            val response = client.get("$gatewayUrl/context/$requestId")
            
            logger.info { "Gateway response status: ${response.status}" }
            
            if (response.status.isSuccess()) {
                val responseBody = response.bodyAsText()
                logger.debug { "Gateway response body: $responseBody" }
                
                val contextResponse = json.decodeFromString<ContextResponse>(responseBody)
                
                val ctx = contextResponse.context
                if (contextResponse.found && ctx != null) {
                    logger.info { "✓ Context fetched successfully:" }
                    logger.info { "  - Service: ${ctx.service}" }
                    logger.info { "  - Operation: ${ctx.operation}" }
                    logger.info { "  - Tenant: ${ctx.tenantId}" }
                    logger.info { "  - User: ${ctx.userId}" }
                    logger.info { "  - Body keys: ${ctx.body.keys}" }
                    ctx
                } else {
                    logger.warn { "✗ Context not found: ${contextResponse.error}" }
                    null
                }
            } else {
                logger.error { "✗ Failed to fetch context: ${response.status}" }
                null
            }
        } catch (e: Exception) {
            logger.error(e) { "✗ Error fetching context from Gateway" }
            null
        }
    }
    
    /**
     * Send execution result back to Gateway.
     * Gateway is waiting on a Channel for this callback.
     */
    suspend fun sendCallback(callbackUrl: String, result: ExecuteResult) {
        logger.info { "┌────────────────────────────────────────────────────────────────┐" }
        logger.info { "│ GATEWAY CLIENT - Sending Callback                              │" }
        logger.info { "├────────────────────────────────────────────────────────────────┤" }
        logger.info { "│ URL: $callbackUrl" }
        logger.info { "│ Request ID: ${result.requestId}" }
        logger.info { "│ Success: ${result.success}" }
        if (result.error != null) {
            logger.info { "│ Error: ${result.error}" }
        }
        if (result.mcpContent != null) {
            logger.info { "│ MCP Content: ${result.mcpContent?.take(100)}..." }
        }
        logger.info { "└────────────────────────────────────────────────────────────────┘" }
        
        try {
            val response = client.post(callbackUrl) {
                contentType(ContentType.Application.Json)
                setBody(result)
            }
            
            if (response.status.isSuccess()) {
                logger.info { "✓ Callback sent successfully for ${result.requestId}" }
            } else {
                logger.error { "✗ Failed to send callback: ${response.status}" }
            }
        } catch (e: Exception) {
            logger.error(e) { "✗ Error sending callback to Gateway" }
        }
    }
}
