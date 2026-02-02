package io.noumena.mcp.executor

import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.routing.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.plugins.calllogging.*
import io.ktor.server.response.*
import io.noumena.mcp.executor.handler.executeRoutes
import mu.KotlinLogging
import kotlinx.serialization.Serializable

private val logger = KotlinLogging.logger {}

@Serializable
data class HealthResponse(val status: String, val service: String)

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8081
    
    logger.info { "Starting Noumena MCP Executor on port $port" }
    logger.info { "Executor HAS Vault access - handles all secret operations" }
    
    embeddedServer(Netty, port = port) {
        configureExecutor()
    }.start(wait = true)
}

fun Application.configureExecutor() {
    install(ContentNegotiation) {
        json()
    }
    
    install(CallLogging)
    
    routing {
        // Health check
        get("/health") {
            call.respond(HealthResponse("ok", "executor"))
        }
        
        // Execute endpoint (receives requests from NPL)
        executeRoutes()
    }
}
