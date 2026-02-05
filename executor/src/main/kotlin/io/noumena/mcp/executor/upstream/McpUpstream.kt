package io.noumena.mcp.executor.upstream

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.noumena.mcp.executor.secrets.ServiceCredentials
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Result from an MCP upstream call, preserving the full MCP structure.
 */
data class McpCallResult(
    /** Flattened key-value data (for backwards compatibility) */
    val data: Map<String, String>,
    /** Raw MCP content array as JSON string */
    val rawContent: String?,
    /** Whether isError was set */
    val isError: Boolean
)

/**
 * Upstream client for Docker MCP containers.
 * 
 * Uses HTTP/JSON-RPC to call MCP servers running as Docker containers.
 * In a full implementation, this would use the MCP SDK's transport layer.
 * For now, we use a simplified JSON-RPC over HTTP approach that is compatible
 * with most MCP server implementations.
 */
class McpUpstream {
    
    private val httpClient = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json { 
                ignoreUnknownKeys = true 
                prettyPrint = true
            })
        }
        install(WebSockets)
    }
    
    private var requestIdCounter = 0
    private val counterMutex = Mutex()
    
    /**
     * Call an MCP tool on a Docker container.
     * Returns legacy Map<String, String> for backwards compatibility.
     */
    suspend fun call(
        endpoint: String,
        operation: String,
        params: Map<String, String>,
        credentials: ServiceCredentials
    ): Map<String, String> {
        return callWithFullResult(endpoint, operation, params, credentials).data
    }
    
    /**
     * Call an MCP tool and return the full result with raw MCP content.
     */
    suspend fun callWithFullResult(
        endpoint: String,
        operation: String,
        params: Map<String, String>,
        credentials: ServiceCredentials
    ): McpCallResult {
        logger.info { "Calling MCP: $endpoint tool=$operation" }
        
        // Normalize endpoint to HTTP
        val httpEndpoint = endpoint
            .replace("ws://", "http://")
            .replace("wss://", "https://")
            .removeSuffix("/mcp")
        
        val requestId = counterMutex.withLock { ++requestIdCounter }
        
        // Build MCP JSON-RPC request
        val mcpRequest = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", requestId)
            put("method", "tools/call")
            put("params", buildJsonObject {
                put("name", operation)
                put("arguments", buildJsonObject {
                    params.forEach { (k, v) -> put(k, v) }
                    // Add credentials as special params if present
                    credentials.accessToken?.let { put("_oauth_token", it) }
                    credentials.apiKey?.let { put("_api_key", it) }
                })
            })
        }
        
        return try {
            val response = httpClient.post("$httpEndpoint/mcp") {
                contentType(ContentType.Application.Json)
                
                // Add auth header if we have credentials
                credentials.accessToken?.let {
                    header("Authorization", "Bearer $it")
                } ?: credentials.apiKey?.let {
                    header("X-API-Key", it)
                }
                
                setBody(mcpRequest.toString())
            }
            
            if (!response.status.isSuccess()) {
                throw RuntimeException("MCP call failed: ${response.status} - ${response.bodyAsText()}")
            }
            
            // Parse JSON-RPC response
            val body = response.bodyAsText()
            parseJsonRpcResponse(body)
        } catch (e: Exception) {
            logger.error(e) { "MCP call failed: $httpEndpoint/$operation" }
            
            // Return mock response in dev mode
            if (System.getenv("DEV_MODE")?.toBoolean() == true) {
                logger.warn { "DEV_MODE: Returning mock MCP response" }
                return McpCallResult(
                    data = mapOf(
                        "status" to "mock_success",
                        "message" to "Mock response for $operation",
                        "tool" to operation
                    ),
                    rawContent = null,
                    isError = false
                )
            }
            
            throw RuntimeException("MCP call failed: ${e.message}", e)
        }
    }
    
    /**
     * Parse a JSON-RPC response from MCP, preserving full content.
     */
    private fun parseJsonRpcResponse(body: String): McpCallResult {
        val json = Json.parseToJsonElement(body).jsonObject
        
        // Check for JSON-RPC error
        json["error"]?.let { error ->
            val errorObj = error.jsonObject
            val message = errorObj["message"]?.jsonPrimitive?.content ?: "Unknown error"
            throw RuntimeException("MCP error: $message")
        }
        
        // Extract result
        val result = json["result"]?.jsonObject 
            ?: return McpCallResult(mapOf("raw" to body), null, false)
        
        val output = mutableMapOf<String, String>()
        var rawContent: String? = null
        
        // Preserve raw content array for pass-through
        result["content"]?.let { content ->
            rawContent = content.toString()
            
            // Also extract flattened data for backwards compatibility
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
        
        // Check for error flag
        val isError = result["isError"]?.jsonPrimitive?.booleanOrNull ?: false
        if (isError) output["error"] = "true"
        
        return McpCallResult(
            data = output.ifEmpty { mapOf("raw" to body) },
            rawContent = rawContent,
            isError = isError
        )
    }
    
    /**
     * Close the HTTP client.
     */
    fun close() {
        httpClient.close()
    }
}
