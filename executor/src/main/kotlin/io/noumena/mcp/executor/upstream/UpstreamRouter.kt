package io.noumena.mcp.executor.upstream

import io.noumena.mcp.executor.secrets.VaultClient
import io.noumena.mcp.shared.config.ServiceDefinition
import io.noumena.mcp.shared.config.ServicesConfigLoader
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
    DIRECT_REST;
    
    companion object {
        fun fromString(value: String): UpstreamType = when (value.uppercase()) {
            "MCP_HTTP" -> MCP_HTTP
            "MCP_STDIO" -> MCP_STDIO
            "DIRECT_REST" -> DIRECT_REST
            else -> throw IllegalArgumentException("Unknown upstream type: $value")
        }
    }
}

/**
 * Configuration for an upstream service.
 * This is the runtime config used by the router.
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
) {
    companion object {
        /**
         * Create ServiceConfig from a ServiceDefinition (loaded from YAML).
         */
        fun fromDefinition(def: ServiceDefinition): ServiceConfig {
            return ServiceConfig(
                type = UpstreamType.fromString(def.type),
                endpoint = def.endpoint,
                command = def.command,
                args = def.args,
                env = emptyMap(),
                requiresCredentials = def.requiresCredentials
            )
        }
    }
}

/**
 * Routes requests to the appropriate upstream service.
 * 
 * Supports multiple transport types:
 * - MCP_HTTP: HTTP JSON-RPC to MCP containers (legacy bridge containers)
 * - MCP_STDIO: Direct STDIO communication with spawned MCP processes (preferred)
 * - DIRECT_REST: REST API calls to non-MCP services
 * 
 * Service configurations are loaded dynamically from configs/services.yaml.
 */
class UpstreamRouter(
    private val vaultClient: VaultClient,
    private val configPath: String = System.getenv("SERVICES_CONFIG_PATH") ?: "/app/configs/services.yaml"
) {
    
    private val httpMcpClient = McpUpstream()
    private val stdioMcpClient = StdioMcpClient()
    private val restUpstream = RestUpstream()
    
    init {
        // Load config on startup
        logger.info { "Loading services configuration from: $configPath" }
        val config = ServicesConfigLoader.load(configPath)
        logger.info { "Loaded ${config.services.size} service definitions" }
        config.services.forEach { svc ->
            logger.info { "  - ${svc.name} (${svc.type}, enabled=${svc.enabled}, tools=${svc.tools.size})" }
        }
    }
    
    /**
     * Get service configuration by name.
     * Loads from YAML config file.
     */
    private fun getServiceConfig(serviceName: String): ServiceConfig {
        val definition = ServicesConfigLoader.getService(serviceName)
            ?: throw IllegalArgumentException("Unknown service: $serviceName. Check configs/services.yaml")
        
        if (!definition.enabled) {
            throw IllegalArgumentException("Service '$serviceName' is disabled in configuration")
        }
        
        return ServiceConfig.fromDefinition(definition)
    }
    
    /**
     * Route an execute request to the appropriate upstream.
     * Returns legacy Map for backwards compatibility.
     */
    suspend fun route(request: ExecuteRequest): Map<String, String> {
        return routeWithFullResult(request).data
    }
    
    /**
     * Reload service configurations from disk.
     */
    fun reloadConfig() {
        logger.info { "Reloading services configuration..." }
        val config = ServicesConfigLoader.reload()
        logger.info { "Reloaded ${config.services.size} service definitions" }
    }
    
    /**
     * Route an execute request and return full result with MCP content.
     */
    suspend fun routeWithFullResult(request: ExecuteRequest): UpstreamResult {
        val config = getServiceConfig(request.service)
        
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
