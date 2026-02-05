package io.noumena.mcp.gateway.messaging

import com.rabbitmq.client.Channel
import com.rabbitmq.client.Connection
import com.rabbitmq.client.ConnectionFactory
import com.rabbitmq.client.MessageProperties
import io.noumena.mcp.shared.models.ExecutionNotification
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Publishes execution notifications to RabbitMQ for the Executor.
 * 
 * FLOW:
 * 1. Gateway receives MCP tool call from LLM
 * 2. Gateway calls NPL for policy check
 * 3. NPL approves and returns requestId
 * 4. Gateway publishes to RabbitMQ (this class)
 * 5. Executor consumes from RabbitMQ
 * 6. Executor calls back to Gateway with result
 * 
 * SECURITY: The Executor only processes messages from this queue.
 * All messages are NPL-approved before being published here.
 */
object ExecutorPublisher {
    
    private val rabbitHost = System.getenv("RABBITMQ_HOST") ?: "rabbitmq"
    private val rabbitPort = System.getenv("RABBITMQ_PORT")?.toIntOrNull() ?: 5672
    private val rabbitUser = System.getenv("RABBITMQ_USER") ?: "guest"
    private val rabbitPass = System.getenv("RABBITMQ_PASS") ?: "guest"
    private val queueName = System.getenv("EXECUTION_QUEUE") ?: "npl.execution.requests"
    
    private var connection: Connection? = null
    private var channel: Channel? = null
    
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }
    
    /**
     * Initialize connection to RabbitMQ.
     * Called at Gateway startup.
     */
    fun init() {
        try {
            logger.info { "Connecting to RabbitMQ at $rabbitHost:$rabbitPort" }
            
            val factory = ConnectionFactory().apply {
                host = rabbitHost
                port = rabbitPort
                username = rabbitUser
                password = rabbitPass
                isAutomaticRecoveryEnabled = true
                networkRecoveryInterval = 5000
            }
            
            connection = factory.newConnection("gateway-publisher")
            channel = connection?.createChannel()
            
            // Declare queue (idempotent - matches executor declaration)
            channel?.queueDeclare(queueName, true, false, false, null)
            
            logger.info { "✓ Connected to RabbitMQ, publishing to queue: $queueName" }
            
        } catch (e: Exception) {
            logger.error(e) { "Failed to connect to RabbitMQ - Executor flow will not work" }
        }
    }
    
    /**
     * Publish an execution notification for the Executor.
     * Called after NPL approves a request.
     * 
     * @param notification The execution notification with all context
     * @return true if published successfully
     */
    fun publish(notification: ExecutionNotification): Boolean {
        val ch = channel
        if (ch == null || !ch.isOpen) {
            logger.error { "RabbitMQ channel not available - cannot publish" }
            return false
        }
        
        return try {
            val message = json.encodeToString(notification)
            
            logger.info { "┌──────────────────────────────────────────────────────────────┐" }
            logger.info { "│ → PUBLISHING TO EXECUTOR                                     │" }
            logger.info { "├──────────────────────────────────────────────────────────────┤" }
            logger.info { "│ Queue:     $queueName" }
            logger.info { "│ RequestId: ${notification.requestId}" }
            logger.info { "│ Service:   ${notification.service}.${notification.operation}" }
            logger.info { "│ Callback:  ${notification.callbackUrl}" }
            logger.info { "└──────────────────────────────────────────────────────────────┘" }
            
            ch.basicPublish(
                "",           // Default exchange
                queueName,    // Routing key = queue name
                MessageProperties.PERSISTENT_TEXT_PLAIN,
                message.toByteArray(Charsets.UTF_8)
            )
            
            logger.info { "✓ Published execution notification for ${notification.requestId}" }
            true
            
        } catch (e: Exception) {
            logger.error(e) { "Failed to publish execution notification" }
            false
        }
    }
    
    /**
     * Close connection on shutdown.
     */
    fun shutdown() {
        logger.info { "Shutting down RabbitMQ publisher" }
        try {
            channel?.close()
            connection?.close()
        } catch (e: Exception) {
            logger.warn(e) { "Error closing RabbitMQ connection" }
        }
    }
}
