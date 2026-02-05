package io.noumena.mcp.executor.upstream

import io.noumena.mcp.executor.secrets.ServiceCredentials
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import mu.KotlinLogging
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger {}

/**
 * Configuration for a STDIO-based MCP server.
 */
data class StdioMcpConfig(
    /** Command to execute (e.g., "duckduckgo-mcp-server", "uvx mcp-server-fetch") */
    val command: String,
    /** Additional arguments */
    val args: List<String> = emptyList(),
    /** Environment variables */
    val env: Map<String, String> = emptyMap(),
    /** Whether to keep the process running between calls (process pooling) */
    val keepAlive: Boolean = true,
    /** Maximum idle time before stopping a keep-alive process */
    val maxIdleMs: Long = 300_000 // 5 minutes
)

/**
 * A managed MCP server process with STDIO communication.
 */
private class ManagedProcess(
    val process: Process,
    val stdin: BufferedWriter,
    val stdout: BufferedReader,
    val config: StdioMcpConfig
) {
    @Volatile
    var lastUsed: Long = System.currentTimeMillis()
    
    @Volatile
    var initialized: Boolean = false
    
    private val mutex = Mutex()
    private var requestIdCounter = 0
    
    suspend fun nextRequestId(): Int = mutex.withLock { ++requestIdCounter }
    
    fun isAlive(): Boolean = process.isAlive
    
    fun updateLastUsed() {
        lastUsed = System.currentTimeMillis()
    }
    
    fun destroy() {
        try {
            stdin.close()
            stdout.close()
            process.destroy()
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly()
            }
        } catch (e: Exception) {
            logger.warn(e) { "Error destroying MCP process" }
        }
    }
}

/**
 * Client for STDIO-based MCP servers.
 * 
 * Spawns MCP server processes and communicates via JSON-RPC over STDIO.
 * Supports process pooling for better performance.
 * 
 * Security benefits:
 * - No network exposure between Executor and MCP servers
 * - Credentials handled entirely within Executor process
 * - Process isolation via OS-level controls
 */
class StdioMcpClient {
    
    // Pool of running MCP server processes, keyed by service name
    private val processPool = ConcurrentHashMap<String, ManagedProcess>()
    private val processMutex = Mutex()
    
    // Cleanup job for idle processes
    private val cleanupScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    init {
        // Start background cleanup of idle processes
        cleanupScope.launch {
            while (isActive) {
                delay(60_000) // Check every minute
                cleanupIdleProcesses()
            }
        }
    }
    
    /**
     * Call an MCP tool via STDIO.
     */
    suspend fun call(
        serviceName: String,
        config: StdioMcpConfig,
        operation: String,
        params: Map<String, String>,
        credentials: ServiceCredentials
    ): McpCallResult {
        logger.info { "STDIO MCP call: $serviceName.$operation" }
        
        val managedProcess = getOrCreateProcess(serviceName, config, credentials)
        managedProcess.updateLastUsed()
        
        return try {
            callTool(managedProcess, operation, params, credentials)
        } catch (e: Exception) {
            logger.error(e) { "STDIO MCP call failed: $serviceName.$operation" }
            
            // Process may have died, remove from pool
            processPool.remove(serviceName)?.destroy()
            
            throw RuntimeException("STDIO MCP call failed: ${e.message}", e)
        }
    }
    
    /**
     * Get or create a managed process for the given service.
     */
    private suspend fun getOrCreateProcess(
        serviceName: String,
        config: StdioMcpConfig,
        credentials: ServiceCredentials
    ): ManagedProcess {
        // Check if we have a running process
        processPool[serviceName]?.let { existing ->
            if (existing.isAlive()) {
                return existing
            }
            // Process died, remove it
            processPool.remove(serviceName)
        }
        
        // Create new process (synchronized)
        return processMutex.withLock {
            // Double-check after acquiring lock
            processPool[serviceName]?.let { existing ->
                if (existing.isAlive()) return existing
                processPool.remove(serviceName)
            }
            
            val managed = startProcess(serviceName, config, credentials)
            
            if (config.keepAlive) {
                processPool[serviceName] = managed
            }
            
            managed
        }
    }
    
    /**
     * Start a new MCP server process.
     */
    private suspend fun startProcess(
        serviceName: String,
        config: StdioMcpConfig,
        credentials: ServiceCredentials
    ): ManagedProcess {
        logger.info { "Starting STDIO MCP server: ${config.command} ${config.args.joinToString(" ")}" }
        
        val processBuilder = ProcessBuilder(listOf(config.command) + config.args)
        
        // Set environment variables
        val env = processBuilder.environment()
        config.env.forEach { (k, v) -> env[k] = v }
        
        // Add credentials to environment if present
        credentials.accessToken?.let { env["MCP_OAUTH_TOKEN"] = it }
        credentials.apiKey?.let { env["MCP_API_KEY"] = it }
        credentials.username?.let { env["MCP_USERNAME"] = it }
        credentials.password?.let { env["MCP_PASSWORD"] = it }
        
        // Redirect stderr to inherit (logs to Executor's stderr)
        processBuilder.redirectErrorStream(false)
        
        val process = withContext(Dispatchers.IO) {
            processBuilder.start()
        }
        
        val stdin = BufferedWriter(OutputStreamWriter(process.outputStream))
        val stdout = BufferedReader(InputStreamReader(process.inputStream))
        
        // Start stderr reader (logs to our logger)
        CoroutineScope(Dispatchers.IO).launch {
            try {
                BufferedReader(InputStreamReader(process.errorStream)).use { stderr ->
                    var line: String?
                    while (stderr.readLine().also { line = it } != null) {
                        logger.debug { "[$serviceName stderr] $line" }
                    }
                }
            } catch (e: Exception) {
                // Process closed
            }
        }
        
        val managed = ManagedProcess(process, stdin, stdout, config)
        
        // Initialize the MCP server
        initializeServer(managed)
        
        return managed
    }
    
    /**
     * Initialize the MCP server with the standard handshake.
     */
    private suspend fun initializeServer(managed: ManagedProcess) {
        val requestId = managed.nextRequestId()
        
        val initRequest = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", requestId)
            put("method", "initialize")
            put("params", buildJsonObject {
                put("protocolVersion", "2024-11-05")
                put("capabilities", buildJsonObject {})
                put("clientInfo", buildJsonObject {
                    put("name", "noumena-mcp-executor")
                    put("version", "1.0.0")
                })
            })
        }
        
        val response = sendRequest(managed, initRequest, requestId)
        logger.info { "MCP server initialized: ${response["result"]}" }
        
        // Send initialized notification
        val notification = buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", "notifications/initialized")
        }
        sendNotification(managed, notification)
        
        managed.initialized = true
    }
    
    /**
     * Call a tool on the MCP server.
     */
    private suspend fun callTool(
        managed: ManagedProcess,
        operation: String,
        params: Map<String, String>,
        credentials: ServiceCredentials
    ): McpCallResult {
        val requestId = managed.nextRequestId()
        
        val request = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", requestId)
            put("method", "tools/call")
            put("params", buildJsonObject {
                put("name", operation)
                put("arguments", buildJsonObject {
                    params.forEach { (k, v) -> 
                        // Try to parse as number or boolean, fall back to string
                        when {
                            v.toIntOrNull() != null -> put(k, v.toInt())
                            v.toBooleanStrictOrNull() != null -> put(k, v.toBoolean())
                            else -> put(k, v)
                        }
                    }
                })
            })
        }
        
        val response = sendRequest(managed, request, requestId)
        return parseResponse(response)
    }
    
    /**
     * Send a request and wait for matching response.
     */
    private suspend fun sendRequest(
        managed: ManagedProcess,
        request: JsonObject,
        expectedId: Int
    ): JsonObject = withContext(Dispatchers.IO) {
        // Send request
        val requestStr = request.toString()
        logger.debug { "STDIO send: $requestStr" }
        
        managed.stdin.write(requestStr)
        managed.stdin.newLine()
        managed.stdin.flush()
        
        // Read responses until we get one with matching ID
        // (skip notifications which have no ID)
        var attempts = 0
        val maxAttempts = 100
        
        while (attempts < maxAttempts) {
            val line = managed.stdout.readLine()
                ?: throw RuntimeException("MCP server closed connection")
            
            logger.debug { "STDIO recv: ${line.take(200)}..." }
            
            val json = Json.parseToJsonElement(line).jsonObject
            val responseId = json["id"]?.jsonPrimitive?.intOrNull
            
            // Skip notifications (no ID)
            if (responseId == null) {
                val method = json["method"]?.jsonPrimitive?.content
                logger.debug { "Received notification: $method" }
                attempts++
                continue
            }
            
            // Check if this is our response
            if (responseId == expectedId) {
                return@withContext json
            }
            
            logger.warn { "Received response with unexpected ID: $responseId (expected $expectedId)" }
            attempts++
        }
        
        throw RuntimeException("No response received after $maxAttempts attempts")
    }
    
    /**
     * Send a notification (no response expected).
     */
    private suspend fun sendNotification(
        managed: ManagedProcess,
        notification: JsonObject
    ) = withContext(Dispatchers.IO) {
        val notifStr = notification.toString()
        logger.debug { "STDIO notification: $notifStr" }
        
        managed.stdin.write(notifStr)
        managed.stdin.newLine()
        managed.stdin.flush()
    }
    
    /**
     * Parse MCP response into our result format.
     */
    private fun parseResponse(response: JsonObject): McpCallResult {
        // Check for JSON-RPC error
        response["error"]?.let { error ->
            val errorObj = error.jsonObject
            val message = errorObj["message"]?.jsonPrimitive?.content ?: "Unknown error"
            throw RuntimeException("MCP error: $message")
        }
        
        val result = response["result"]?.jsonObject
            ?: return McpCallResult(mapOf("raw" to response.toString()), null, false)
        
        val output = mutableMapOf<String, String>()
        var rawContent: String? = null
        
        // Extract content array
        result["content"]?.let { content ->
            rawContent = content.toString()
            
            content.jsonArray.forEachIndexed { index, contentItem ->
                val contentObj = contentItem.jsonObject
                val type = contentObj["type"]?.jsonPrimitive?.content
                
                when (type) {
                    "text" -> {
                        contentObj["text"]?.jsonPrimitive?.content?.let {
                            output["result_$index"] = it
                        }
                    }
                    "image" -> {
                        contentObj["data"]?.jsonPrimitive?.content?.let {
                            output["image_$index"] = it
                        }
                    }
                    else -> {
                        output["content_$index"] = contentItem.toString()
                    }
                }
            }
        }
        
        val isError = result["isError"]?.jsonPrimitive?.booleanOrNull ?: false
        if (isError) output["error"] = "true"
        
        return McpCallResult(
            data = output.ifEmpty { mapOf("raw" to response.toString()) },
            rawContent = rawContent,
            isError = isError
        )
    }
    
    /**
     * Cleanup idle processes.
     */
    private fun cleanupIdleProcesses() {
        val now = System.currentTimeMillis()
        
        processPool.entries.removeIf { (serviceName, managed) ->
            val idle = now - managed.lastUsed
            val shouldRemove = !managed.isAlive() || idle > managed.config.maxIdleMs
            
            if (shouldRemove) {
                logger.info { "Removing idle MCP process: $serviceName (idle=${idle}ms)" }
                managed.destroy()
            }
            
            shouldRemove
        }
    }
    
    /**
     * Shutdown all processes.
     */
    fun shutdown() {
        cleanupScope.cancel()
        processPool.values.forEach { it.destroy() }
        processPool.clear()
        logger.info { "STDIO MCP client shutdown complete" }
    }
}
