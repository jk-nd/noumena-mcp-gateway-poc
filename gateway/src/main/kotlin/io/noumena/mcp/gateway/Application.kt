package io.noumena.mcp.gateway

import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.routing.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.plugins.calllogging.*
import io.ktor.server.response.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import io.noumena.mcp.gateway.server.McpServerHandler
import io.noumena.mcp.gateway.callback.callbackRoutes
import io.noumena.mcp.gateway.context.contextRoutes
import io.noumena.mcp.gateway.context.ContextStore
import io.noumena.mcp.gateway.messaging.ExecutorPublisher
import mu.KotlinLogging
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import kotlin.time.Duration.Companion.seconds

private val logger = KotlinLogging.logger {}

@Serializable
data class HealthResponse(val status: String, val service: String, val mcpEnabled: Boolean = true)

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    
    logger.info { "Starting Noumena MCP Gateway on port $port" }
    logger.info { "NOTE: Gateway has NO Vault access - this is by design" }
    logger.info { "MCP endpoints: /mcp/ws (WebSocket), /mcp/* (REST)" }
    
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
        
        // MCP WebSocket endpoint for agents
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
        
        // Callback endpoint (receives results from Executor)
        callbackRoutes()
        
        // Context endpoint (Executor fetches request body here)
        contextRoutes()
    }
    
    // Periodic cleanup of expired contexts
    val cleanupJob = CoroutineScope(Dispatchers.Default).launch {
        while (isActive) {
            delay(60_000) // Every minute
            ContextStore.cleanupExpired()
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
                val response = buildJsonObject {
                    put("jsonrpc", "2.0")
                    put("id", id ?: JsonNull)
                    putJsonObject("result") {
                        putJsonArray("tools") {
                            addJsonObject {
                                put("name", "google_gmail_send_email")
                                put("description", "Send an email via Google Gmail. Requires NPL policy approval.")
                                putJsonObject("inputSchema") {
                                    put("type", "object")
                                    putJsonObject("properties") {
                                        putJsonObject("to") { put("type", "string"); put("description", "Recipient email address") }
                                        putJsonObject("subject") { put("type", "string"); put("description", "Email subject") }
                                        putJsonObject("body") { put("type", "string"); put("description", "Email body") }
                                    }
                                    putJsonArray("required") { add("to"); add("subject"); add("body") }
                                }
                            }
                            addJsonObject {
                                put("name", "slack_send_message")
                                put("description", "Send a message to a Slack channel.")
                                putJsonObject("inputSchema") {
                                    put("type", "object")
                                    putJsonObject("properties") {
                                        putJsonObject("channel") { put("type", "string"); put("description", "Channel ID or name") }
                                        putJsonObject("message") { put("type", "string"); put("description", "Message text") }
                                    }
                                }
                            }
                            addJsonObject {
                                put("name", "google_calendar_create_event")
                                put("description", "Create a calendar event in Google Calendar.")
                                putJsonObject("inputSchema") {
                                    put("type", "object")
                                    putJsonObject("properties") {
                                        putJsonObject("title") { put("type", "string"); put("description", "Event title") }
                                        putJsonObject("start") { put("type", "string"); put("description", "Start time (ISO 8601)") }
                                        putJsonObject("end") { put("type", "string"); put("description", "End time (ISO 8601)") }
                                    }
                                }
                            }
                            // Real MCP Server: Fetch (official mcp/fetch image)
                            addJsonObject {
                                put("name", "web_fetch")
                                put("description", "Fetch a URL and extract its content. Uses real MCP server.")
                                putJsonObject("inputSchema") {
                                    put("type", "object")
                                    putJsonObject("properties") {
                                        putJsonObject("url") { put("type", "string"); put("description", "URL to fetch") }
                                        putJsonObject("max_length") { put("type", "integer"); put("description", "Maximum content length (optional)") }
                                        putJsonObject("start_index") { put("type", "integer"); put("description", "Start index for content (optional)") }
                                        putJsonObject("raw") { put("type", "boolean"); put("description", "Return raw HTML instead of markdown (optional)") }
                                    }
                                    putJsonArray("required") { add("url") }
                                }
                            }
                            // Real MCP Server: DuckDuckGo Search (official mcp/duckduckgo image)
                            addJsonObject {
                                put("name", "duckduckgo_search")
                                put("description", "Search DuckDuckGo and return formatted results. Uses real MCP server - no API key required.")
                                putJsonObject("inputSchema") {
                                    put("type", "object")
                                    putJsonObject("properties") {
                                        putJsonObject("query") { put("type", "string"); put("description", "Search query string") }
                                        putJsonObject("max_results") { put("type", "integer"); put("description", "Maximum number of results (default: 10)") }
                                    }
                                    putJsonArray("required") { add("query") }
                                }
                            }
                            // Real MCP Server: DuckDuckGo Fetch Content (official mcp/duckduckgo image)
                            addJsonObject {
                                put("name", "duckduckgo_fetch_content")
                                put("description", "Fetch and parse content from a webpage URL using DuckDuckGo. Uses real MCP server.")
                                putJsonObject("inputSchema") {
                                    put("type", "object")
                                    putJsonObject("properties") {
                                        putJsonObject("url") { put("type", "string"); put("description", "URL to fetch content from") }
                                    }
                                    putJsonArray("required") { add("url") }
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
