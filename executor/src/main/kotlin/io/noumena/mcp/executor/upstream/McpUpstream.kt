package io.noumena.mcp.executor.upstream

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.noumena.mcp.executor.secrets.ServiceCredentials
import kotlinx.serialization.json.Json
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Upstream client for Docker MCP containers.
 * 
 * Calls MCP servers running as Docker containers from the Docker MCP Catalog.
 */
class McpUpstream {
    
    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    }
    
    /**
     * Call an MCP tool on a Docker container.
     */
    suspend fun call(
        endpoint: String,
        operation: String,
        params: Map<String, String>,
        credentials: ServiceCredentials
    ): Map<String, String> {
        logger.info { "Calling MCP: $endpoint/$operation" }
        
        // TODO: Use proper MCP SDK client when available
        // For now, using HTTP POST to a JSON-RPC style endpoint
        
        val response = client.post("$endpoint/tools/$operation") {
            contentType(ContentType.Application.Json)
            
            // Add authentication based on available credentials
            credentials.accessToken?.let {
                header("Authorization", "Bearer $it")
            } ?: credentials.apiKey?.let {
                header("X-API-Key", it)
            }
            
            setBody(params)
        }
        
        if (!response.status.isSuccess()) {
            throw RuntimeException("MCP call failed: ${response.status} - ${response.bodyAsText()}")
        }
        
        // Parse response
        val body = response.bodyAsText()
        return try {
            Json.decodeFromString<Map<String, String>>(body)
        } catch (e: Exception) {
            mapOf("result" to body)
        }
    }
}
