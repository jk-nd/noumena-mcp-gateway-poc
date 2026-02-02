package io.noumena.mcp.executor.callback

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.noumena.mcp.shared.models.ExecuteResult
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Sends execution results back to Gateway via callback.
 */
class CallbackSender {
    
    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json()
        }
    }
    
    /**
     * Send result to Gateway callback URL.
     */
    suspend fun send(callbackUrl: String, result: ExecuteResult) {
        logger.info { "Sending callback for ${result.requestId} to $callbackUrl" }
        
        try {
            val response = client.post(callbackUrl) {
                contentType(ContentType.Application.Json)
                setBody(result)
            }
            
            if (response.status.isSuccess()) {
                logger.info { "Callback sent successfully for ${result.requestId}" }
            } else {
                logger.error { "Callback failed for ${result.requestId}: ${response.status}" }
            }
        } catch (e: Exception) {
            logger.error(e) { "Error sending callback for ${result.requestId}" }
        }
    }
}
