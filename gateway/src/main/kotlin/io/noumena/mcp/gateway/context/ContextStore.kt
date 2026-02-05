package io.noumena.mcp.gateway.context

import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.http.*
import io.noumena.mcp.shared.models.ContextResponse
import io.noumena.mcp.shared.models.StoredContext
import mu.KotlinLogging
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

/**
 * In-memory context store for pending requests.
 * 
 * Stores the full request body so NPL never sees it.
 * Executor fetches context after NPL approval.
 * 
 * Contexts are automatically cleaned up:
 * - After successful callback
 * - After timeout (via periodic cleanup)
 * - When consumed
 */
object ContextStore {
    
    private data class StoredEntry(
        val context: StoredContext,
        var consumed: Boolean = false
    )
    
    private val store = ConcurrentHashMap<String, StoredEntry>()
    
    // Context TTL: 5 minutes
    private val contextTtlMs = System.getenv("CONTEXT_TTL_MS")?.toLongOrNull() ?: 300_000L
    
    /**
     * Store context for a request.
     * Called by Gateway when receiving MCP request, before calling NPL.
     */
    fun store(context: StoredContext): String {
        logger.debug { "Storing context for request: ${context.requestId}" }
        store[context.requestId] = StoredEntry(context)
        return context.requestId
    }
    
    /**
     * Fetch and consume context.
     * Called by Executor after NPL approval.
     * Context can only be fetched once (consumed).
     */
    fun fetchAndConsume(requestId: String): StoredContext? {
        val entry = store[requestId] ?: run {
            logger.warn { "Context not found: $requestId" }
            return null
        }
        
        if (entry.consumed) {
            logger.warn { "Context already consumed: $requestId" }
            return null
        }
        
        entry.consumed = true
        logger.debug { "Context consumed: $requestId" }
        return entry.context
    }
    
    /**
     * Peek at context without consuming.
     * For debugging/monitoring only.
     */
    fun peek(requestId: String): StoredContext? {
        return store[requestId]?.context
    }
    
    /**
     * Remove context after completion.
     * Called by callback handler.
     */
    fun remove(requestId: String) {
        store.remove(requestId)
        logger.debug { "Context removed: $requestId" }
    }
    
    /**
     * Clean up expired contexts.
     * Should be called periodically.
     */
    fun cleanupExpired() {
        val now = System.currentTimeMillis()
        val expired = store.entries.filter { 
            now - it.value.context.createdAt > contextTtlMs 
        }
        
        expired.forEach { entry ->
            store.remove(entry.key)
            logger.info { "Cleaned up expired context: ${entry.key}" }
        }
        
        if (expired.isNotEmpty()) {
            logger.info { "Cleaned up ${expired.size} expired contexts" }
        }
    }
    
    /**
     * Get count of stored contexts (for monitoring).
     */
    fun count(): Int = store.size
    
    /**
     * Get count of consumed but not yet removed contexts.
     */
    fun consumedCount(): Int = store.values.count { it.consumed }
}

/**
 * Context routes - Executor fetches request body here.
 * 
 * SECURITY: In production, validate that caller is the Executor
 * (via mTLS, token, or network policy).
 */
fun Route.contextRoutes() {
    route("/context") {
        /**
         * GET /context/{requestId}
         * Executor fetches the stored context after NPL approval.
         */
        get("/{requestId}") {
            val requestId = call.parameters["requestId"]
            
            if (requestId.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest, ContextResponse(
                    found = false,
                    error = "Missing requestId"
                ))
                return@get
            }
            
            logger.info { "Context fetch request for: $requestId" }
            
            val context = ContextStore.fetchAndConsume(requestId)
            
            if (context != null) {
                call.respond(HttpStatusCode.OK, ContextResponse(
                    found = true,
                    context = context
                ))
            } else {
                call.respond(HttpStatusCode.NotFound, ContextResponse(
                    found = false,
                    error = "Context not found or already consumed"
                ))
            }
        }
        
        /**
         * GET /context
         * Health/monitoring endpoint - returns count of stored contexts.
         */
        get {
            call.respond(HttpStatusCode.OK, mapOf(
                "stored" to ContextStore.count(),
                "consumed" to ContextStore.consumedCount()
            ))
        }
    }
}
