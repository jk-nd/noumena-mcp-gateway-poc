package io.noumena.mcp.gateway

import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.routing.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.plugins.calllogging.*
import io.ktor.server.response.*
import io.noumena.mcp.gateway.server.mcpRoutes
import io.noumena.mcp.gateway.callback.callbackRoutes
import mu.KotlinLogging
import kotlinx.serialization.Serializable

private val logger = KotlinLogging.logger {}

@Serializable
data class HealthResponse(val status: String, val service: String)

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    
    logger.info { "Starting Noumena MCP Gateway on port $port" }
    logger.info { "NOTE: Gateway has NO Vault access - this is by design" }
    
    embeddedServer(Netty, port = port) {
        configureGateway()
    }.start(wait = true)
}

fun Application.configureGateway() {
    install(ContentNegotiation) {
        json()
    }
    
    install(CallLogging)
    
    routing {
        // Health check
        get("/health") {
            call.respond(HealthResponse("ok", "gateway"))
        }
        
        // MCP endpoints (agent-facing)
        mcpRoutes()
        
        // Callback endpoint (receives results from Executor)
        callbackRoutes()
    }
}
