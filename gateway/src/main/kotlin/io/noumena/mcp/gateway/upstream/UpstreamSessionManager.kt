package io.noumena.mcp.gateway.upstream

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.websocket.*
import io.ktor.serialization.kotlinx.json.*
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.ClientOptions
import io.modelcontextprotocol.kotlin.sdk.client.StdioClientTransport
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.client.WebSocketClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ImageContent
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.noumena.mcp.shared.config.ServiceDefinition
import io.noumena.mcp.shared.config.ServicesConfig
import kotlinx.coroutines.runBlocking
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.serialization.json.*
import mu.KotlinLogging
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

/**
 * Manages MCP client sessions to upstream MCP services using the MCP Kotlin SDK.
 *
 * Supports multiple transport types:
 *   - MCP_STDIO: Spawns a process (e.g., `docker run -i --rm mcp/...`) and communicates via stdin/stdout.
 *   - MCP_HTTP: Connects via Streamable HTTP transport (MCP spec).
 *   - MCP_WS: Connects via WebSocket transport.
 *
 * Key design decisions:
 *   - Lazy connection: sessions are created on first tool call, not at startup.
 *   - Session keyed by service name (one connection per upstream service).
 *   - STDIO processes are tracked and destroyed on shutdown or session eviction.
 *   - Uses MCP Kotlin SDK's Client for protocol handling (handshake, serialization, framing).
 *   - No `supergateway` wrapper needed — the Gateway natively speaks STDIO/WS/HTTP.
 */
class UpstreamSessionManager(
    private val router: UpstreamRouter
) {
    /** Active upstream sessions keyed by service name. */
    private val sessions = ConcurrentHashMap<String, ManagedSession>()

    /** HTTP client configured for WebSocket + Streamable HTTP upstream connections. */
    private val httpClient = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
        install(WebSockets)
        engine {
            requestTimeout = 60_000 // 60 seconds per upstream call
        }
    }

    /** Client info sent during MCP handshake with upstream services. */
    private val clientInfo = Implementation(
        name = "noumena-mcp-gateway",
        version = "2.0.0"
    )

    /**
     * Forward a tool call to the upstream MCP service.
     *
     * Lazy connection: if no session exists for the service, one is created
     * on the first call. The MCP SDK Client handles the initialize handshake.
     *
     * @param resolved The resolved tool (service + stripped tool name)
     * @param arguments The tool call arguments as JSON
     * @return The upstream MCP result (content array)
     */
    suspend fun forwardToolCall(
        resolved: ResolvedTool,
        arguments: JsonObject
    ): UpstreamResult {
        val session = getOrCreateSession(resolved.service)

        return try {
            // Use CallToolRequest directly to avoid lossy JsonObject → Map round-trip
            val request = CallToolRequest(
                CallToolRequestParams(
                    name = resolved.toolName,
                    arguments = arguments
                )
            )
            val callResult = session.client.callTool(request)
            convertCallToolResult(callResult)
        } catch (e: Exception) {
            logger.error(e) { "Failed to forward tool call ${resolved.serviceName}.${resolved.toolName}" }
            // Remove session on error so it can be re-created
            closeSession(resolved.serviceName)
            UpstreamResult(
                success = false,
                error = "Upstream call failed: ${e.message}",
                isError = true
            )
        }
    }

    /**
     * Discover tools from an upstream MCP service.
     * Creates a session if needed and sends tools/list via the SDK.
     */
    suspend fun discoverTools(service: ServiceDefinition): List<JsonObject> {
        val session = getOrCreateSession(service)
        return try {
            val listResult = session.client.listTools()
            listResult.tools.map { tool ->
                buildJsonObject {
                    put("name", tool.name)
                    put("description", tool.description ?: "")
                    tool.inputSchema.let { schema ->
                        putJsonObject("inputSchema") {
                            put("type", schema.type)
                            schema.properties?.let { put("properties", it) }
                            schema.required?.let { reqList ->
                                putJsonArray("required") { reqList.forEach { add(it) } }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to discover tools from ${service.name}" }
            closeSession(service.name)
            emptyList()
        }
    }

    /**
     * Get or create an upstream session for a service.
     * Sessions are lazily initialized and cached by service name.
     */
    private suspend fun getOrCreateSession(service: ServiceDefinition): ManagedSession {
        // Fast path: return existing session
        sessions[service.name]?.let { return it }

        // Slow path: create new session
        logger.info { "Creating upstream session for '${service.name}' (type=${service.type})" }
        val managed = createSession(service)

        // Atomically store, or return existing if another coroutine won the race
        val existing = sessions.putIfAbsent(service.name, managed)
        if (existing != null) {
            managed.close()
            return existing
        }
        return managed
    }

    /**
     * Create a new MCP SDK Client session with the appropriate transport.
     */
    private suspend fun createSession(service: ServiceDefinition): ManagedSession {
        return when (service.type) {
            "MCP_STDIO" -> createStdioSession(service)
            "MCP_HTTP" -> createHttpSession(service)
            "MCP_WS" -> createWebSocketSession(service)
            else -> throw IllegalArgumentException(
                "Unsupported transport type '${service.type}' for service '${service.name}'. " +
                    "Supported types: MCP_STDIO, MCP_HTTP, MCP_WS"
            )
        }
    }

    // ─── Transport-specific session factories ────────────────────────────────

    /**
     * Create a STDIO session by spawning a child process.
     * The process communicates via stdin/stdout JSON-RPC (MCP STDIO transport).
     */
    private suspend fun createStdioSession(service: ServiceDefinition): ManagedSession {
        val command = service.command
            ?: throw IllegalArgumentException("MCP_STDIO service '${service.name}' has no command configured")

        val parts = command.split(" ") + service.args
        logger.info { "Spawning STDIO process for '${service.name}': $parts" }

        val process = ProcessBuilder(parts)
            .redirectErrorStream(false)
            .start()

        val transport = StdioClientTransport(
            input = process.inputStream.asSource().buffered(),
            output = process.outputStream.asSink().buffered(),
            error = process.errorStream.asSource().buffered()
        )

        val client = Client(clientInfo, ClientOptions())
        client.connect(transport)

        logger.info { "STDIO session '${service.name}' connected (PID: ${process.pid()})" }
        return ManagedSession(
            client = client,
            process = process,
            configType = service.type,
            configEndpoint = service.endpoint,
            configCommand = service.command
        )
    }

    /**
     * Create a Streamable HTTP session (MCP spec HTTP transport).
     */
    private suspend fun createHttpSession(service: ServiceDefinition): ManagedSession {
        val endpoint = service.endpoint
            ?: throw IllegalArgumentException("MCP_HTTP service '${service.name}' has no endpoint configured")

        logger.info { "Connecting HTTP transport for '${service.name}' → $endpoint" }

        val transport = StreamableHttpClientTransport(httpClient, endpoint)
        val client = Client(clientInfo, ClientOptions())
        client.connect(transport)

        logger.info { "HTTP session '${service.name}' connected" }
        return ManagedSession(
            client = client,
            configType = service.type,
            configEndpoint = service.endpoint,
            configCommand = service.command
        )
    }

    /**
     * Create a WebSocket session (MCP WebSocket transport).
     */
    private suspend fun createWebSocketSession(service: ServiceDefinition): ManagedSession {
        val endpoint = service.endpoint
            ?: throw IllegalArgumentException("MCP_WS service '${service.name}' has no endpoint configured")

        logger.info { "Connecting WebSocket transport for '${service.name}' → $endpoint" }

        val transport = WebSocketClientTransport(httpClient, endpoint)
        val client = Client(clientInfo, ClientOptions())
        client.connect(transport)

        logger.info { "WebSocket session '${service.name}' connected" }
        return ManagedSession(
            client = client,
            configType = service.type,
            configEndpoint = service.endpoint,
            configCommand = service.command
        )
    }

    // ─── Result conversion ───────────────────────────────────────────────────

    /**
     * Convert an MCP SDK [CallToolResult] to our [UpstreamResult].
     */
    private fun convertCallToolResult(result: CallToolResult): UpstreamResult {
        val isError = result.isError ?: false
        val contentArray = buildJsonArray {
            result.content.forEach { block ->
                when (block) {
                    is TextContent -> addJsonObject {
                        put("type", "text")
                        put("text", block.text)
                    }
                    is ImageContent -> addJsonObject {
                        put("type", "image")
                        put("data", block.data)
                        put("mimeType", block.mimeType)
                    }
                    else -> addJsonObject {
                        put("type", "text")
                        put("text", block.toString())
                    }
                }
            }
        }

        return UpstreamResult(
            success = !isError,
            content = contentArray,
            isError = isError,
            error = if (isError) "Upstream tool returned error" else null
        )
    }

    // ─── Session lifecycle ───────────────────────────────────────────────────

    /**
     * Close and remove a session by service name.
     */
    private fun closeSession(name: String) {
        sessions.remove(name)?.close()
    }

    /**
     * Clear stale sessions whose configuration has changed.
     * Called after [ServicesConfigLoader.reload()] to evict sessions
     * for services whose config changed (endpoint, command, type) or
     * services that were removed or disabled.
     */
    fun clearStaleSessions(newConfig: ServicesConfig) {
        val newServicesByName = newConfig.services.associateBy { it.name }

        sessions.keys.toList().forEach { name ->
            val newDef = newServicesByName[name]
            val currentSession = sessions[name] ?: return@forEach

            // Evict if service was removed, disabled, or config changed
            if (newDef == null || !newDef.enabled || currentSession.hasConfigChanged(newDef)) {
                logger.info { "Evicting stale session for '$name'" }
                closeSession(name)
            }
        }
    }

    /**
     * Close all upstream sessions and destroy STDIO processes.
     */
    fun shutdown() {
        logger.info { "Shutting down ${sessions.size} upstream sessions" }
        sessions.forEach { (name, session) ->
            try {
                logger.info { "Closing upstream session: $name" }
                session.close()
            } catch (e: Exception) {
                logger.warn(e) { "Error closing session $name" }
            }
        }
        sessions.clear()
        httpClient.close()
    }
}

/**
 * Wraps an MCP SDK [Client] with optional process handle and config snapshot
 * for change detection during config reloads.
 */
class ManagedSession(
    val client: Client,
    val process: Process? = null,
    private val configType: String? = null,
    private val configEndpoint: String? = null,
    private val configCommand: String? = null
) {
    /**
     * Check if the service configuration has changed relative to this session.
     */
    fun hasConfigChanged(newDef: ServiceDefinition): Boolean {
        return configType != newDef.type ||
            configEndpoint != newDef.endpoint ||
            configCommand != newDef.command
    }

    /**
     * Close the MCP SDK Client and destroy any STDIO process.
     */
    fun close() {
        try {
            runBlocking { client.close() }
        } catch (e: Exception) {
            logger.warn(e) { "Error closing MCP client" }
        }
        process?.let { proc ->
            try {
                if (proc.isAlive) {
                    proc.destroyForcibly()
                    logger.info { "Destroyed STDIO process (PID: ${proc.pid()})" }
                }
            } catch (e: Exception) {
                logger.warn(e) { "Error destroying STDIO process" }
            }
        }
    }

    private companion object {
        private val logger = KotlinLogging.logger {}
    }
}

/**
 * Result from an upstream MCP tool call.
 */
data class UpstreamResult(
    val success: Boolean,
    val content: JsonArray? = null,
    val isError: Boolean = false,
    val error: String? = null
)
