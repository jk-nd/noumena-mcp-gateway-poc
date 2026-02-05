package io.noumena.mcp.executor.upstream

import io.noumena.mcp.executor.secrets.VaultClient
import io.noumena.mcp.shared.models.ExecuteRequest
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Result from routing an upstream call.
 */
data class UpstreamResult(
    /** Flattened data (for backwards compatibility) */
    val data: Map<String, String>,
    /** Raw MCP content JSON (if from MCP upstream) */
    val mcpContent: String? = null,
    /** Whether upstream reported an error */
    val isError: Boolean = false
)

/**
 * Upstream transport type.
 */
enum class UpstreamType {
    /** HTTP JSON-RPC to MCP containers */
    MCP_HTTP,
    /** STDIO JSON-RPC to spawned MCP processes */
    MCP_STDIO,
    /** Direct REST API calls */
    DIRECT_REST
}

/**
 * Configuration for an upstream service.
 */
data class ServiceConfig(
    val type: UpstreamType,
    /** For MCP_HTTP: the HTTP endpoint. For MCP_STDIO: not used. */
    val endpoint: String? = null,
    /** For MCP_STDIO: the command to run */
    val command: String? = null,
    /** For MCP_STDIO: command arguments */
    val args: List<String> = emptyList(),
    /** For MCP_STDIO: environment variables */
    val env: Map<String, String> = emptyMap(),
    /** Whether this service needs Vault credentials */
    val requiresCredentials: Boolean = true
)

/**
 * Routes requests to the appropriate upstream service.
 * 
 * Supports multiple transport types:
 * - MCP_HTTP: HTTP JSON-RPC to MCP containers (legacy bridge containers)
 * - MCP_STDIO: Direct STDIO communication with spawned MCP processes (preferred)
 * - DIRECT_REST: REST API calls to non-MCP services
 */
class UpstreamRouter(
    private val vaultClient: VaultClient
) {
    
    private val httpMcpClient = McpUpstream()
    private val stdioMcpClient = StdioMcpClient()
    private val restUpstream = RestUpstream()
    
    /**
     * Service configurations.
     * 
     * STDIO-based services spawn processes directly in the Executor,
     * eliminating the need for separate bridge containers.
     */
    private val serviceConfigs = mapOf(
        // Real MCP servers via STDIO (no bridge containers needed!)
        // Note: These services go through Vault even if they don't strictly need credentials
        // This ensures consistent security flow and audit trail
        "duckduckgo" to ServiceConfig(
            type = UpstreamType.MCP_STDIO,
            command = "duckduckgo-mcp-server",
            requiresCredentials = true  // Goes through Vault (credentials optional but flow is tested)
        ),
        "web" to ServiceConfig(
            type = UpstreamType.MCP_STDIO,
            command = "mcp-server-fetch",
            requiresCredentials = true  // Goes through Vault
        ),
        
        // Mock MCP servers via HTTP (for demo/testing)
        "google_gmail" to ServiceConfig(
            type = UpstreamType.MCP_HTTP,
            endpoint = "http://google-mcp:8080",
            requiresCredentials = true
        ),
        "google_calendar" to ServiceConfig(
            type = UpstreamType.MCP_HTTP,
            endpoint = "http://google-mcp:8080",
            requiresCredentials = true
        ),
        "slack" to ServiceConfig(
            type = UpstreamType.MCP_HTTP,
            endpoint = "http://slack-mcp:8080",
            requiresCredentials = true
        ),
        "stripe" to ServiceConfig(
            type = UpstreamType.MCP_HTTP,
            endpoint = "http://stripe-mcp:8080",
            requiresCredentials = true
        ),
        
        // Non-MCP REST APIs
        "sap" to ServiceConfig(
            type = UpstreamType.DIRECT_REST,
            requiresCredentials = true
        )
    )
    
    /**
     * Route an execute request to the appropriate upstream.
     * Returns legacy Map for backwards compatibility.
     */
    suspend fun route(request: ExecuteRequest): Map<String, String> {
        return routeWithFullResult(request).data
    }
    
    /**
     * Route an execute request and return full result with MCP content.
     */
    suspend fun routeWithFullResult(request: ExecuteRequest): UpstreamResult {
        val config = serviceConfigs[request.service]
            ?: throw IllegalArgumentException("Unknown service: ${request.service}")
        
        logger.info { "Routing ${request.service}.${request.operation} via ${config.type}" }
        
        // Fetch credentials from Vault (skip for services that don't need them)
        val credentials = if (!config.requiresCredentials) {
            logger.info { "Skipping Vault lookup for ${request.service} (no credentials needed)" }
            io.noumena.mcp.executor.secrets.ServiceCredentials()
        } else {
            vaultClient.getCredentials(
                request.tenantId,
                request.userId,
                request.service.substringBefore("_") // "google_gmail" -> "google"
            )
        }
        
        return when (config.type) {
            UpstreamType.MCP_STDIO -> {
                val stdioConfig = StdioMcpConfig(
                    command = config.command 
                        ?: throw IllegalArgumentException("No command for STDIO service ${request.service}"),
                    args = config.args,
                    env = config.env
                )
                
                val mcpResult = stdioMcpClient.call(
                    serviceName = request.service,
                    config = stdioConfig,
                    operation = request.operation,
                    params = request.params,
                    credentials = credentials
                )
                
                UpstreamResult(
                    data = mcpResult.data,
                    mcpContent = mcpResult.rawContent,
                    isError = mcpResult.isError
                )
            }
            
            UpstreamType.MCP_HTTP -> {
                val endpoint = config.endpoint
                    ?: throw IllegalArgumentException("No HTTP endpoint for ${request.service}")
                
                val mcpResult = httpMcpClient.callWithFullResult(
                    endpoint = endpoint,
                    operation = request.operation,
                    params = request.params,
                    credentials = credentials
                )
                
                UpstreamResult(
                    data = mcpResult.data,
                    mcpContent = mcpResult.rawContent,
                    isError = mcpResult.isError
                )
            }
            
            UpstreamType.DIRECT_REST -> {
                val restResult = restUpstream.call(
                    service = request.service,
                    operation = request.operation,
                    params = request.params,
                    credentials = credentials
                )
                
                UpstreamResult(
                    data = restResult,
                    mcpContent = null,
                    isError = false
                )
            }
        }
    }
    
    /**
     * Shutdown all upstream clients.
     */
    fun shutdown() {
        stdioMcpClient.shutdown()
        httpMcpClient.close()
    }
}
