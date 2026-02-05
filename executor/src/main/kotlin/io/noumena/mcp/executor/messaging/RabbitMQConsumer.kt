package io.noumena.mcp.executor.messaging

import com.rabbitmq.client.*
import io.noumena.mcp.shared.models.ExecutionNotification
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * RabbitMQ consumer for NPL execution notifications.
 * 
 * SECURITY: This is the only way to trigger the Executor.
 * Executor has no inbound HTTP - only RabbitMQ.
 * Only NPL can publish to this queue (via HTTP Connector).
 */
class RabbitMQConsumer(
    private val handler: suspend (ExecutionNotification) -> Unit
) {
    
    private val rabbitHost = System.getenv("RABBITMQ_HOST") ?: "rabbitmq"
    private val rabbitPort = System.getenv("RABBITMQ_PORT")?.toIntOrNull() ?: 5672
    private val rabbitUser = System.getenv("RABBITMQ_USER") ?: "guest"
    private val rabbitPass = System.getenv("RABBITMQ_PASS") ?: "guest"
    private val queueName = System.getenv("EXECUTION_QUEUE") ?: "npl.execution.requests"
    
    private var connection: Connection? = null
    private var channel: Channel? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private val json = Json { 
        ignoreUnknownKeys = true 
        isLenient = true
    }
    
    /**
     * Start consuming messages from RabbitMQ.
     */
    fun start() {
        logger.info { "Connecting to RabbitMQ at $rabbitHost:$rabbitPort" }
        
        try {
            val factory = ConnectionFactory().apply {
                host = rabbitHost
                port = rabbitPort
                username = rabbitUser
                password = rabbitPass
                
                // Auto-recovery for resilience
                isAutomaticRecoveryEnabled = true
                networkRecoveryInterval = 5000
            }
            
            connection = factory.newConnection("executor")
            channel = connection?.createChannel()
            
            // Declare queue (idempotent)
            channel?.queueDeclare(queueName, true, false, false, null)
            
            // Only process one message at a time
            channel?.basicQos(1)
            
            logger.info { "Connected to RabbitMQ, consuming from queue: $queueName" }
            
            // Set up consumer
            val consumer = object : DefaultConsumer(channel) {
                override fun handleDelivery(
                    consumerTag: String,
                    envelope: Envelope,
                    properties: AMQP.BasicProperties,
                    body: ByteArray
                ) {
                    val messageBody = String(body, Charsets.UTF_8)
                    logger.info { "Received message: ${messageBody.take(200)}..." }
                    
                    scope.launch {
                        try {
                            val notification = parseNotification(messageBody)
                            if (notification != null) {
                                handler(notification)
                                // Acknowledge after successful processing
                                channel?.basicAck(envelope.deliveryTag, false)
                            } else {
                                // Reject malformed messages (don't requeue)
                                channel?.basicReject(envelope.deliveryTag, false)
                            }
                        } catch (e: Exception) {
                            logger.error(e) { "Error processing message" }
                            // Requeue on error (for retry)
                            channel?.basicNack(envelope.deliveryTag, false, true)
                        }
                    }
                }
            }
            
            channel?.basicConsume(queueName, false, consumer)
            
        } catch (e: Exception) {
            logger.error(e) { "Failed to connect to RabbitMQ" }
            throw e
        }
    }
    
    /**
     * Parse notification from JSON.
     * Handles both direct JSON and NPL HTTP Connector format.
     */
    private fun parseNotification(body: String): ExecutionNotification? {
        return try {
            // Try direct parse first
            json.decodeFromString<ExecutionNotification>(body)
        } catch (e: Exception) {
            logger.warn { "Failed to parse as ExecutionNotification, trying NPL format" }
            
            // Try NPL HTTP Connector format (wrapped in notification envelope)
            try {
                val envelope = json.decodeFromString<NplNotificationEnvelope>(body)
                ExecutionNotification(
                    requestId = envelope.requestId ?: "",
                    tenantId = envelope.tenantId ?: "default",
                    userId = envelope.userId ?: "unknown",
                    service = envelope.service ?: "",
                    operation = envelope.operation ?: "",
                    gatewayUrl = envelope.gatewayUrl ?: "",
                    callbackUrl = envelope.callbackUrl ?: ""
                )
            } catch (e2: Exception) {
                logger.error(e2) { "Failed to parse message: $body" }
                null
            }
        }
    }
    
    /**
     * Stop consuming and close connection.
     */
    fun stop() {
        logger.info { "Stopping RabbitMQ consumer" }
        scope.cancel()
        try {
            channel?.close()
            connection?.close()
        } catch (e: Exception) {
            logger.warn(e) { "Error closing RabbitMQ connection" }
        }
    }
}

/**
 * NPL notification envelope format (from HTTP Connector).
 */
@kotlinx.serialization.Serializable
private data class NplNotificationEnvelope(
    val requestId: String? = null,
    val tenantId: String? = null,
    val userId: String? = null,
    val service: String? = null,
    val operation: String? = null,
    val gatewayUrl: String? = null,
    val callbackUrl: String? = null,
    // Additional fields from NPL notification
    val params: String? = null,
    val metadata: Map<String, String>? = null
)
