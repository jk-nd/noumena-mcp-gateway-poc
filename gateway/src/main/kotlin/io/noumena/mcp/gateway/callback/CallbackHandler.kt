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
 * Pending requests waiting for Executor callback.
 */
object PendingRequests {
    private val channels = ConcurrentHashMap<String, Channel<ExecuteResult>>()
    
    fun register(requestId: String): Channel<ExecuteResult> {
        val channel = Channel<ExecuteResult>(1)
        channels[requestId] = channel
        return channel
    }
    
    fun complete(requestId: String, result: ExecuteResult) {
        channels.remove(requestId)?.trySend(result)
    }
    
    suspend fun await(requestId: String, timeoutMs: Long): ExecuteResult? {
        val channel = channels[requestId] ?: return null
        return withTimeoutOrNull(timeoutMs) {
            channel.receive()
        }
    }
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
