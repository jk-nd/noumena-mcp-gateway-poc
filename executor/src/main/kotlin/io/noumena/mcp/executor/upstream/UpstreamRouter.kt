package io.noumena.mcp.executor.upstream

import io.noumena.mcp.executor.secrets.VaultClient
import io.noumena.mcp.shared.models.ExecuteRequest
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Routes requests to the appropriate upstream service.
 */
class UpstreamRouter(
    private val vaultClient: VaultClient
) {
    
    private val mcpUpstream = McpUpstream()
    private val restUpstream = RestUpstream()
    
    // Service to upstream type mapping
    // TODO: Load from configuration
    private val serviceTypes = mapOf(
        "google_gmail" to UpstreamType.DOCKER_MCP,
        "google_calendar" to UpstreamType.DOCKER_MCP,
        "slack" to UpstreamType.DOCKER_MCP,
        "stripe" to UpstreamType.DOCKER_MCP,
        "sap" to UpstreamType.DIRECT_REST
    )
    
    // MCP endpoints for Docker containers
    private val mcpEndpoints = mapOf(
        "google_gmail" to "http://google-mcp:8080",
        "google_calendar" to "http://google-mcp:8080",
        "slack" to "http://slack-mcp:8080",
        "stripe" to "http://stripe-mcp:8080"
    )
    
    /**
     * Route an execute request to the appropriate upstream.
     */
    suspend fun route(request: ExecuteRequest): Map<String, String> {
        val upstreamType = serviceTypes[request.service]
            ?: throw IllegalArgumentException("Unknown service: ${request.service}")
        
        logger.info { "Routing ${request.service}.${request.operation} via $upstreamType" }
        
        // Fetch credentials from Vault
        val credentials = vaultClient.getCredentials(
            request.tenantId,
            request.userId,
            request.service.substringBefore("_") // "google_gmail" -> "google"
        )
        
        return when (upstreamType) {
            UpstreamType.DOCKER_MCP -> {
                val endpoint = mcpEndpoints[request.service]
                    ?: throw IllegalArgumentException("No MCP endpoint for ${request.service}")
                
                mcpUpstream.call(
                    endpoint = endpoint,
                    operation = request.operation,
                    params = request.params,
                    credentials = credentials
                )
            }
            
            UpstreamType.DIRECT_REST -> {
                restUpstream.call(
                    service = request.service,
                    operation = request.operation,
                    params = request.params,
                    credentials = credentials
                )
            }
        }
    }
}

enum class UpstreamType {
    DOCKER_MCP,
    DIRECT_REST
}
