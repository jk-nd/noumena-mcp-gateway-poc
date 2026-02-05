package io.noumena.mcp.gateway

import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.routing.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.plugins.calllogging.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.response.*
import io.ktor.server.request.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import io.ktor.http.*
import io.ktor.utils.io.*
import io.noumena.mcp.gateway.server.McpServerHandler
import io.noumena.mcp.gateway.callback.callbackRoutes
import io.noumena.mcp.gateway.context.contextRoutes
import io.noumena.mcp.gateway.context.ContextStore
import io.noumena.mcp.gateway.messaging.ExecutorPublisher
import io.noumena.mcp.shared.config.ServicesConfigLoader
import io.noumena.mcp.shared.config.ToolDefinition
import mu.KotlinLogging
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.seconds

private val logger = KotlinLogging.logger {}

@Serializable
data class HealthResponse(val status: String, val service: String, val mcpEnabled: Boolean = true)

/**
 * SSE session for MCP Inspector connections.
 * Each session has a channel for sending responses back to the client.
 */
data class SseSession(
    val id: String,
    val responseChannel: Channel<String> = Channel(Channel.UNLIMITED),
    val createdAt: Long = System.currentTimeMillis()
)

// Active SSE sessions (sessionId -> SseSession)
private val sseSessions = ConcurrentHashMap<String, SseSession>()

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    
    logger.info { "Starting Noumena MCP Gateway on port $port" }
    logger.info { "NOTE: Gateway has NO Vault access - this is by design" }
    logger.info { "MCP endpoints:" }
    logger.info { "  POST /mcp     - HTTP (LangChain, ADK, Sligo.ai, etc.)" }
    logger.info { "  WS   /mcp/ws  - WebSocket (streaming agents)" }
    logger.info { "  GET  /sse     - SSE (MCP Inspector)" }
    
    embeddedServer(Netty, port = port) {
        configureGateway()
    }.start(wait = true)
}

fun Application.configureGateway() {
    install(ContentNegotiation) {
        json(Json {
            prettyPrint = true
            ignoreUnknownKeys = true
        })
    }
    
    install(CallLogging)
    
    // CORS for MCP Inspector and other cross-origin clients
    install(CORS) {
        anyHost()
        allowHeader(HttpHeaders.ContentType)
        allowHeader(HttpHeaders.Authorization)
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Options)
    }
    
    install(WebSockets) {
        pingPeriod = 15.seconds
        timeout = 30.seconds
        maxFrameSize = Long.MAX_VALUE
        masking = false
    }
    
    // Initialize RabbitMQ publisher for triggering Executor
    ExecutorPublisher.init()
    
    // Create MCP Server handler
    val mcpHandler = McpServerHandler()
    val mcpServer = mcpHandler.createServer()
    
    routing {
        // Health check
        get("/health") {
            call.respond(HealthResponse("ok", "gateway"))
        }
        
        // ─────────────────────────────────────────────────────────────────────
        // Admin endpoints for service management
        // ─────────────────────────────────────────────────────────────────────
        
        // List all services with their status
        get("/admin/services") {
            val configPath = System.getenv("SERVICES_CONFIG_PATH") ?: "/app/configs/services.yaml"
            val config = try {
                ServicesConfigLoader.load(configPath)
            } catch (e: Exception) {
                logger.error { "Failed to load services config: ${e.message}" }
                call.respondText(
                    """{"error": "Failed to load config: ${e.message}"}""",
                    ContentType.Application.Json,
                    HttpStatusCode.InternalServerError
                )
                return@get
            }
            
            val response = buildJsonObject {
                putJsonArray("services") {
                    config.services.forEach { svc ->
                        addJsonObject {
                            put("name", svc.name)
                            put("displayName", svc.displayName)
                            put("type", svc.type)
                            put("enabled", svc.enabled)
                            put("description", svc.description)
                            put("toolCount", svc.tools.size)
                            putJsonArray("tools") {
                                svc.tools.forEach { tool ->
                                    addJsonObject {
                                        put("name", tool.name)
                                        put("description", tool.description)
                                    }
                                }
                            }
                        }
                    }
                }
                put("totalServices", config.services.size)
                put("enabledServices", config.services.count { it.enabled })
                put("totalTools", config.services.flatMap { it.tools }.size)
            }
            
            call.respondText(
                Json.encodeToString(JsonObject.serializer(), response),
                ContentType.Application.Json
            )
        }
        
        // Reload services configuration
        post("/admin/services/reload") {
            logger.info { "Reloading services configuration..." }
            val config = ServicesConfigLoader.reload()
            
            val response = buildJsonObject {
                put("status", "reloaded")
                put("servicesLoaded", config.services.size)
                put("enabledServices", config.services.count { it.enabled })
            }
            
            call.respondText(
                Json.encodeToString(JsonObject.serializer(), response),
                ContentType.Application.Json
            )
        }
        
        // MCP HTTP POST endpoint for agents (LangChain, ADK, Sligo.ai, etc.)
        post("/mcp") {
            val requestBody = call.receiveText()
            
            logger.info { "╔════════════════════════════════════════════════════════════════╗" }
            logger.info { "║ 📨 MCP REQUEST via HTTP POST                                   ║" }
            logger.info { "╚════════════════════════════════════════════════════════════════╝" }
            
            try {
                val response = processMcpMessage(mcpServer, requestBody, mcpHandler)
                call.respondText(response, ContentType.Application.Json)
            } catch (e: Exception) {
                logger.error(e) { "HTTP MCP error" }
                val errorResponse = buildJsonObject {
                    put("jsonrpc", "2.0")
                    put("id", null as String?)
                    putJsonObject("error") {
                        put("code", -32603)
                        put("message", e.message ?: "Internal error")
                    }
                }
                call.respondText(errorResponse.toString(), ContentType.Application.Json, HttpStatusCode.InternalServerError)
            }
        }
        
        // MCP WebSocket endpoint for agents (streaming/realtime)
        webSocket("/mcp/ws") {
            logger.info { "╔════════════════════════════════════════════════════════════════╗" }
            logger.info { "║ 🔌 AGENT CONNECTED via WebSocket                               ║" }
            logger.info { "╚════════════════════════════════════════════════════════════════╝" }
            
            try {
                for (frame in incoming) {
                    when (frame) {
                        is Frame.Text -> {
                            val text = frame.readText()
                            
                            // Process through MCP server with verbose logging
                            val response = processMcpMessage(mcpServer, text, mcpHandler)
                            send(Frame.Text(response))
                        }
                        else -> {}
                    }
                }
            } catch (e: Exception) {
                logger.error(e) { "WebSocket error" }
            } finally {
                logger.info { "╔════════════════════════════════════════════════════════════════╗" }
                logger.info { "║ 🔌 AGENT DISCONNECTED                                          ║" }
                logger.info { "╚════════════════════════════════════════════════════════════════╝" }
            }
        }
        
        // ─────────────────────────────────────────────────────────────────────
        // SSE endpoint for MCP Inspector
        // ─────────────────────────────────────────────────────────────────────
        
        get("/sse") {
            val sessionId = UUID.randomUUID().toString()
            val session = SseSession(id = sessionId)
            sseSessions[sessionId] = session
            
            logger.info { "╔════════════════════════════════════════════════════════════════╗" }
            logger.info { "║ 🔗 SSE CLIENT CONNECTED (MCP Inspector)                        ║" }
            logger.info { "║ Session: $sessionId" }
            logger.info { "╚════════════════════════════════════════════════════════════════╝" }
            
            // Determine the base URL for the message endpoint
            val host = call.request.host()
            val port = call.request.port()
            val scheme = if (call.request.local.scheme == "https") "https" else "http"
            val messageEndpoint = "$scheme://$host:$port/message?sessionId=$sessionId"
            
            call.response.cacheControl(CacheControl.NoCache(null))
            call.respondTextWriter(contentType = ContentType.Text.EventStream) {
                try {
                    // Send the endpoint event first (tells Inspector where to POST messages)
                    write("event: endpoint\n")
                    write("data: $messageEndpoint\n\n")
                    flush()
                    
                    logger.info { "SSE: Sent endpoint event: $messageEndpoint" }
                    
                    // Keep connection alive and forward responses from the channel
                    while (true) {
                        // Wait for messages with timeout for keepalive
                        val message = withTimeoutOrNull(30_000) {
                            session.responseChannel.receive()
                        }
                        
                        if (message != null) {
                            write("event: message\n")
                            write("data: $message\n\n")
                            flush()
                            logger.info { "SSE: Sent message event" }
                        } else {
                            // Send keepalive comment
                            write(": keepalive\n\n")
                            flush()
                        }
                    }
                } catch (e: Exception) {
                    logger.info { "SSE connection closed: ${e.message}" }
                } finally {
                    sseSessions.remove(sessionId)
                    session.responseChannel.close()
                    logger.info { "SSE session $sessionId cleaned up" }
                }
            }
        }
        
        // Message endpoint for SSE clients to send requests
        post("/message") {
            val sessionId = call.request.queryParameters["sessionId"]
            if (sessionId == null) {
                call.respondText(
                    """{"error": "Missing sessionId parameter"}""",
                    ContentType.Application.Json,
                    HttpStatusCode.BadRequest
                )
                return@post
            }
            
            val session = sseSessions[sessionId]
            if (session == null) {
                call.respondText(
                    """{"error": "Session not found"}""",
                    ContentType.Application.Json,
                    HttpStatusCode.NotFound
                )
                return@post
            }
            
            val requestBody = call.receiveText()
            
            logger.info { "╔════════════════════════════════════════════════════════════════╗" }
            logger.info { "║ 📨 MCP REQUEST via SSE (MCP Inspector)                         ║" }
            logger.info { "╚════════════════════════════════════════════════════════════════╝" }
            
            try {
                // Process the MCP message
                val response = processMcpMessage(mcpServer, requestBody, mcpHandler)
                
                // Send response back through SSE channel
                session.responseChannel.send(response)
                
                // Acknowledge the POST request
                call.respondText("Accepted", ContentType.Text.Plain, HttpStatusCode.Accepted)
            } catch (e: Exception) {
                logger.error(e) { "SSE message processing error" }
                call.respondText(
                    """{"error": "${e.message}"}""",
                    ContentType.Application.Json,
                    HttpStatusCode.InternalServerError
                )
            }
        }
        
        // Callback endpoint (receives results from Executor)
        callbackRoutes()
        
        // Context endpoint (Executor fetches request body here)
        contextRoutes()
    }
    
    // Periodic cleanup of expired contexts and stale SSE sessions
    val cleanupJob = CoroutineScope(Dispatchers.Default).launch {
        while (isActive) {
            delay(60_000) // Every minute
            ContextStore.cleanupExpired()
            
            // Clean up stale SSE sessions (older than 1 hour)
            val staleThreshold = System.currentTimeMillis() - 3600_000
            sseSessions.entries.removeIf { (id, session) ->
                if (session.createdAt < staleThreshold) {
                    session.responseChannel.close()
                    logger.info { "Cleaned up stale SSE session: $id" }
                    true
                } else {
                    false
                }
            }
        }
    }
    
    // Cancel cleanup and close RabbitMQ on shutdown
    environment.monitor.subscribe(ApplicationStopped) {
        cleanupJob.cancel()
        ExecutorPublisher.shutdown()
    }
}

/**
 * Process an MCP JSON-RPC message with verbose logging.
 * Shows the complete message flow for demonstration purposes.
 */
private suspend fun processMcpMessage(
    server: io.modelcontextprotocol.kotlin.sdk.server.Server, 
    message: String,
    mcpHandler: McpServerHandler
): String {
    val json = Json { 
        ignoreUnknownKeys = true 
        prettyPrint = true
    }
    
    return try {
        val request = json.parseToJsonElement(message).jsonObject
        val method = request["method"]?.jsonPrimitive?.content
        val id = request["id"]
        val params = request["params"]?.jsonObject
        
        // Log incoming request
        logMcpMessage("INCOMING", "LLM → Gateway", method ?: "unknown", message)
        
        val result = when (method) {
            "initialize" -> {
                val response = buildJsonObject {
                    put("jsonrpc", "2.0")
                    put("id", id ?: JsonNull)
                    putJsonObject("result") {
                        put("protocolVersion", "2024-11-05")
                        putJsonObject("serverInfo") {
                            put("name", "noumena-mcp-gateway")
                            put("version", "1.0.0")
                        }
                        putJsonObject("capabilities") {
                            putJsonObject("tools") {
                                put("listChanged", true)
                            }
                        }
                    }
                }
                json.encodeToString(JsonObject.serializer(), response)
            }
            
            "tools/list" -> {
                // Load tools dynamically from services.yaml
                val configPath = System.getenv("SERVICES_CONFIG_PATH") ?: "/app/configs/services.yaml"
                val allTools = try {
                    ServicesConfigLoader.load(configPath).services
                        .filter { it.enabled }
                        .flatMap { svc -> svc.tools.filter { it.enabled } }
                } catch (e: Exception) {
                    logger.warn { "Failed to load services config: ${e.message}. Using empty tool list." }
                    emptyList()
                }
                
                logger.info { "Returning ${allTools.size} tools from dynamic config" }
                
                val response = buildJsonObject {
                    put("jsonrpc", "2.0")
                    put("id", id ?: JsonNull)
                    putJsonObject("result") {
                        putJsonArray("tools") {
                            allTools.forEach { tool ->
                                addJsonObject {
                                    put("name", tool.name)
                                    put("description", tool.description)
                                    putJsonObject("inputSchema") {
                                        put("type", tool.inputSchema.type)
                                        putJsonObject("properties") {
                                            tool.inputSchema.properties.forEach { (propName, propSchema) ->
                                                putJsonObject(propName) {
                                                    put("type", propSchema.type)
                                                    put("description", propSchema.description)
                                                }
                                            }
                                        }
                                        if (tool.inputSchema.required.isNotEmpty()) {
                                            putJsonArray("required") {
                                                tool.inputSchema.required.forEach { add(it) }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                json.encodeToString(JsonObject.serializer(), response)
            }
            
            "tools/call" -> {
                val toolName = params?.get("name")?.jsonPrimitive?.content ?: "unknown"
                val arguments = params?.get("arguments")?.jsonObject ?: buildJsonObject {}
                
                logger.info { "╔══════════════════════════════════════════════════════════════╗" }
                logger.info { "║ TOOL CALL: $toolName" }
                logger.info { "╠══════════════════════════════════════════════════════════════╣" }
                logger.info { "║ Arguments: ${json.encodeToString(JsonObject.serializer(), arguments).take(60)}..." }
                logger.info { "╚══════════════════════════════════════════════════════════════╝" }
                
                // Parse service and operation from tool name
                val (service, operation) = parseToolName(toolName)
                
                // Check if the service is enabled before executing
                val configPath = System.getenv("SERVICES_CONFIG_PATH") ?: "/app/configs/services.yaml"
                val serviceConfig = try {
                    ServicesConfigLoader.load(configPath).services.find { it.name == service }
                } catch (e: Exception) {
                    null
                }
                
                if (serviceConfig == null || !serviceConfig.enabled) {
                    logger.warn { "Service '$service' is not enabled or not found. Rejecting tool call." }
                    val errorResponse = buildJsonObject {
                        put("jsonrpc", "2.0")
                        put("id", id ?: JsonNull)
                        putJsonObject("result") {
                            putJsonArray("content") {
                                addJsonObject {
                                    put("type", "text")
                                    put("text", "Error: Service '$service' is disabled or not found. Tool call rejected.")
                                }
                            }
                            put("isError", true)
                        }
                    }
                    return@processMcpMessage json.encodeToString(JsonObject.serializer(), errorResponse)
                }
                
                // Check if the specific tool is enabled
                val toolConfig = serviceConfig.tools.find { it.name == toolName }
                if (toolConfig != null && !toolConfig.enabled) {
                    logger.warn { "Tool '$toolName' is disabled. Rejecting tool call." }
                    val errorResponse = buildJsonObject {
                        put("jsonrpc", "2.0")
                        put("id", id ?: JsonNull)
                        putJsonObject("result") {
                            putJsonArray("content") {
                                addJsonObject {
                                    put("type", "text")
                                    put("text", "Error: Tool '$toolName' is disabled. Tool call rejected.")
                                }
                            }
                            put("isError", true)
                        }
                    }
                    return@processMcpMessage json.encodeToString(JsonObject.serializer(), errorResponse)
                }
                
                // Call the actual handler
                val toolResult = mcpHandler.handleToolCallDirect(
                    service = service,
                    operation = operation,
                    arguments = arguments
                )
                
                // Build JSON-RPC response
                val response = buildJsonObject {
                    put("jsonrpc", "2.0")
                    put("id", id ?: JsonNull)
                    putJsonObject("result") {
                        putJsonArray("content") {
                            toolResult.content.forEach { content ->
                                addJsonObject {
                                    put("type", "text")
                                    put("text", (content as? io.modelcontextprotocol.kotlin.sdk.types.TextContent)?.text ?: content.toString())
                                }
                            }
                        }
                        put("isError", toolResult.isError ?: false)
                    }
                }
                json.encodeToString(JsonObject.serializer(), response)
            }
            
            else -> {
                val response = buildJsonObject {
                    put("jsonrpc", "2.0")
                    put("id", id ?: JsonNull)
                    putJsonObject("error") {
                        put("code", -32601)
                        put("message", "Method not found: $method")
                    }
                }
                json.encodeToString(JsonObject.serializer(), response)
            }
        }
        
        // Log outgoing response
        logMcpMessage("OUTGOING", "Gateway → LLM", method ?: "unknown", result)
        
        result
    } catch (e: Exception) {
        logger.error(e) { "Error processing MCP message" }
        val errorResponse = """{"jsonrpc":"2.0","error":{"code":-32700,"message":"Parse error: ${e.message}"}}"""
        logMcpMessage("ERROR", "Gateway → LLM", "error", errorResponse)
        errorResponse
    }
}

/**
 * Parse tool name into service and operation.
 * e.g., "google_gmail_send_email" -> ("google_gmail", "send_email")
 */
private fun parseToolName(toolName: String): Pair<String, String> {
    // Special cases for real MCP servers
    if (toolName == "web_fetch") {
        return "web" to "fetch"
    }
    if (toolName == "duckduckgo_search") {
        return "duckduckgo" to "search"
    }
    if (toolName == "duckduckgo_fetch_content") {
        return "duckduckgo" to "fetch_content"
    }
    
    // Known services
    val services = listOf("google_gmail", "google_calendar", "slack", "sap", "stripe")
    
    for (service in services) {
        if (toolName.startsWith(service + "_")) {
            val operation = toolName.removePrefix(service + "_")
            return service to operation
        }
    }
    
    // Fallback: first part is service, rest is operation
    val parts = toolName.split("_", limit = 2)
    return if (parts.size == 2) {
        parts[0] to parts[1]
    } else {
        toolName to "default"
    }
}

/**
 * Log MCP messages with nice formatting for visibility.
 */
private fun logMcpMessage(direction: String, flow: String, method: String, message: String) {
    val separator = "─".repeat(60)
    val json = try {
        val element = Json.parseToJsonElement(message)
        Json { prettyPrint = true }.encodeToString(JsonElement.serializer(), element)
    } catch (e: Exception) {
        message
    }
    
    val color = when (direction) {
        "INCOMING" -> "→"
        "OUTGOING" -> "←"
        "ERROR" -> "✗"
        else -> "•"
    }
    
    logger.info { "" }
    logger.info { "┌$separator┐" }
    logger.info { "│ $color $direction: $flow [$method]" }
    logger.info { "├$separator┤" }
    json.lines().forEach { line ->
        logger.info { "│ $line" }
    }
    logger.info { "└$separator┘" }
}
