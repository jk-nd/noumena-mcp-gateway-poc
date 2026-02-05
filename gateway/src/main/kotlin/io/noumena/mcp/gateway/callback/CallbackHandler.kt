package io.noumena.mcp.gateway.callback

import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.http.*
import io.noumena.mcp.shared.models.ExecuteResult
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import mu.KotlinLogging
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

/**
 * Result wrapper that includes timeout information.
 */
sealed class AwaitResult {
    data class Success(val result: ExecuteResult) : AwaitResult()
    data class Timeout(val requestId: String, val timeoutMs: Long) : AwaitResult()
    data class Error(val message: String) : AwaitResult()
}

/**
 * Pending requests waiting for Executor callback.
 * 
 * Provides sync-over-async pattern:
 * 1. Register a request ID before triggering async flow
 * 2. Await the result with timeout
 * 3. Callback completes the waiting coroutine
 */
object PendingRequests {
    private val channels = ConcurrentHashMap<String, Channel<ExecuteResult>>()
    
    /**
     * Register a request ID and return a channel to receive the result.
     */
    fun register(requestId: String): Channel<ExecuteResult> {
        logger.debug { "Registering pending request: $requestId" }
        val channel = Channel<ExecuteResult>(1)
        channels[requestId] = channel
        return channel
    }
    
    /**
     * Complete a pending request with a result.
     * Called by the callback handler when Executor responds.
     */
    fun complete(requestId: String, result: ExecuteResult) {
        logger.debug { "Completing request $requestId: success=${result.success}" }
        val channel = channels.remove(requestId)
        if (channel != null) {
            channel.trySend(result)
        } else {
            logger.warn { "No pending request found for $requestId - may have timed out" }
        }
    }
    
    /**
     * Wait for a result with timeout.
     * Returns the result, or null if timeout/not found.
     */
    suspend fun await(requestId: String, timeoutMs: Long): ExecuteResult? {
        val channel = channels[requestId]
        if (channel == null) {
            logger.warn { "await called for unregistered request: $requestId" }
            return null
        }
        
        return try {
            withTimeoutOrNull(timeoutMs) {
                channel.receive()
            }.also { result ->
                if (result == null) {
                    logger.warn { "Request $requestId timed out after ${timeoutMs}ms" }
                    // Clean up the channel on timeout
                    channels.remove(requestId)?.close()
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Error awaiting request $requestId" }
            channels.remove(requestId)?.close()
            null
        }
    }
    
    /**
     * Register, trigger action, and await result in one call.
     * This is the main sync-over-async API.
     */
    suspend fun <T> awaitExecution(
        requestId: String,
        timeoutMs: Long,
        action: suspend () -> T
    ): AwaitResult {
        // Register before triggering async flow
        register(requestId)
        
        return try {
            // Trigger the async action (e.g., call NPL)
            action()
            
            // Wait for callback
            val result = await(requestId, timeoutMs)
            
            if (result != null) {
                AwaitResult.Success(result)
            } else {
                AwaitResult.Timeout(requestId, timeoutMs)
            }
        } catch (e: Exception) {
            // Clean up on error
            channels.remove(requestId)?.close()
            AwaitResult.Error(e.message ?: "Unknown error")
        }
    }
    
    /**
     * Get count of pending requests (for monitoring).
     */
    fun pendingCount(): Int = channels.size
}

/**
 * Callback routes - receives results from Executor.
 */
fun Route.callbackRoutes() {
    route("/callback") {
        post {
            val result = call.receive<ExecuteResult>()
            
            logger.info { "Received callback for request ${result.requestId}: success=${result.success}" }
            
            PendingRequests.complete(result.requestId, result)
            
            call.respond(HttpStatusCode.OK, mapOf("status" to "received"))
        }
    }
}
