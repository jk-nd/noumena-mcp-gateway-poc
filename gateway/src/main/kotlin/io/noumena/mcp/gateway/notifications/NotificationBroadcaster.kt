package io.noumena.mcp.gateway.notifications

import mu.KotlinLogging
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

/**
 * Broadcasts server-initiated notifications from upstream MCP services
 * to all connected clients (SSE, WebSocket).
 *
 * Upstream MCP services can send JSON-RPC notifications (e.g.,
 * `notifications/tools/list_changed`, `notifications/resources/updated`)
 * at any time. The Gateway intercepts these via the MCP SDK Client's
 * fallback notification handler and forwards them to all connected
 * agent sessions through this broadcaster.
 *
 * Clients register a handler when they connect and unregister on disconnect.
 */
class NotificationBroadcaster {

    /**
     * Active listeners keyed by connection ID.
     * The handler receives the JSON-RPC notification as a JSON string.
     */
    private val listeners = ConcurrentHashMap<String, suspend (String) -> Unit>()

    /**
     * Register a client connection to receive upstream notifications.
     *
     * @param id Unique identifier for this connection (e.g., SSE session ID, WebSocket ID)
     * @param handler Callback invoked with the JSON-RPC notification string
     */
    fun register(id: String, handler: suspend (String) -> Unit) {
        listeners[id] = handler
        logger.info { "Notification listener registered: $id (total: ${listeners.size})" }
    }

    /**
     * Unregister a client connection.
     *
     * @param id The connection identifier used during registration
     */
    fun unregister(id: String) {
        listeners.remove(id)
        logger.info { "Notification listener unregistered: $id (total: ${listeners.size})" }
    }

    /**
     * Broadcast a notification to all connected clients.
     *
     * Failed deliveries are logged and the listener is removed to prevent
     * future failures on dead connections.
     *
     * @param notification JSON-RPC notification string to forward
     */
    suspend fun broadcast(notification: String) {
        if (listeners.isEmpty()) {
            logger.debug { "No listeners for notification broadcast" }
            return
        }

        logger.info { "Broadcasting notification to ${listeners.size} listener(s)" }
        val failedIds = mutableListOf<String>()

        listeners.forEach { (id, handler) ->
            try {
                handler(notification)
            } catch (e: Exception) {
                logger.warn { "Failed to send notification to listener $id: ${e.message}" }
                failedIds.add(id)
            }
        }

        // Clean up failed listeners
        failedIds.forEach { listeners.remove(it) }
    }

    /** Number of currently registered listeners. */
    val listenerCount: Int get() = listeners.size
}
