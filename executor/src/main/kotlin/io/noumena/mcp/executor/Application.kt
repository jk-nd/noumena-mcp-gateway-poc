package io.noumena.mcp.executor

import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.routing.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.plugins.calllogging.*
import io.ktor.server.response.*
import io.noumena.mcp.executor.handler.ExecutionHandler
import io.noumena.mcp.executor.messaging.RabbitMQConsumer
import mu.KotlinLogging
import kotlinx.serialization.Serializable
import kotlinx.coroutines.runBlocking

private val logger = KotlinLogging.logger {}

@Serializable
data class HealthResponse(
    val status: String, 
    val service: String,
    val mode: String = "rabbitmq"
)

// Track RabbitMQ consumer for health checks
private var rabbitConsumer: RabbitMQConsumer? = null

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8081
    val useRabbitMQ = System.getenv("USE_RABBITMQ")?.toBoolean() ?: true
    
    logger.info { "Starting Noumena MCP Executor on port $port" }
    logger.info { "Executor HAS Vault access - handles all secret operations" }
    logger.info { "Mode: ${if (useRabbitMQ) "RabbitMQ (network isolated)" else "HTTP (legacy)"}" }
    
    // Start RabbitMQ consumer if enabled
    if (useRabbitMQ) {
        startRabbitMQConsumer()
    }
    
    // Start HTTP server (for health checks and legacy mode)
    embeddedServer(Netty, port = port) {
        configureExecutor(useRabbitMQ)
    }.start(wait = true)
}

/**
 * Start RabbitMQ consumer for receiving NPL notifications.
 * This is the primary mode - Executor has no inbound HTTP for execution.
 */
private fun startRabbitMQConsumer() {
    val handler = ExecutionHandler()
    
    rabbitConsumer = RabbitMQConsumer { notification ->
        handler.handle(notification)
    }
    
    try {
        rabbitConsumer?.start()
        logger.info { "RabbitMQ consumer started - Executor is network isolated" }
    } catch (e: Exception) {
        logger.error(e) { "Failed to start RabbitMQ consumer" }
        // Continue anyway - HTTP fallback may be available
    }
}

fun Application.configureExecutor(useRabbitMQ: Boolean) {
    install(ContentNegotiation) {
        json()
    }
    
    install(CallLogging)
    
    routing {
        // Health check (always available)
        get("/health") {
            call.respond(HealthResponse(
                status = "ok", 
                service = "executor",
                mode = if (useRabbitMQ) "rabbitmq" else "http"
            ))
        }
        
        // Note: No HTTP execute endpoint - Executor only accepts via RabbitMQ
        // This ensures network isolation and that all requests go through NPL
    }
    
    // Cleanup on shutdown
    environment.monitor.subscribe(ApplicationStopped) {
        rabbitConsumer?.stop()
    }
}
