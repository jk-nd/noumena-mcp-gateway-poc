package io.noumena.mcp.shared.config

/**
 * Root configuration for services.yaml
 */
data class ServicesConfig(
    val services: List<ServiceDefinition> = emptyList()
)

/**
 * Definition of an upstream service including its tools.
 */
data class ServiceDefinition(
    /** Internal service name (e.g., "duckduckgo", "google_gmail") */
    val name: String,
    /** Display name for UI */
    val displayName: String = name,
    /** Transport type: MCP_STDIO, MCP_HTTP, or DIRECT_REST */
    val type: String,
    /** Whether the service is enabled */
    val enabled: Boolean = true,
    /** For MCP_STDIO: command to run */
    val command: String? = null,
    /** For MCP_STDIO: command arguments */
    val args: List<String> = emptyList(),
    /** For MCP_HTTP: HTTP endpoint */
    val endpoint: String? = null,
    /** For DIRECT_REST: base URL */
    val baseUrl: String? = null,
    /** Whether credentials are required */
    val requiresCredentials: Boolean = true,
    /** Vault path template for credentials */
    val vaultPath: String? = null,
    /** Service description */
    val description: String = "",
    /** Tools provided by this service */
    val tools: List<ToolDefinition> = emptyList()
)

/**
 * Definition of a tool exposed by a service.
 */
data class ToolDefinition(
    /** Tool name (e.g., "duckduckgo_search") */
    val name: String,
    /** Tool description */
    val description: String = "",
    /** JSON Schema for input parameters */
    val inputSchema: InputSchema = InputSchema(),
    /** Whether this tool is enabled (default true) */
    val enabled: Boolean = true
)

/**
 * JSON Schema for tool input.
 */
data class InputSchema(
    val type: String = "object",
    val properties: Map<String, PropertySchema> = emptyMap(),
    val required: List<String> = emptyList()
)

/**
 * Schema for a single property in the input.
 */
data class PropertySchema(
    val type: String = "string",
    val description: String = ""
)

/**
 * Loader for services configuration.
 */
object ServicesConfigLoader {
    private var cachedConfig: ServicesConfig? = null
    private var configPath: String = "/app/configs/services.yaml"
    
    /**
     * Load services configuration from the default or specified path.
     */
    fun load(path: String = configPath): ServicesConfig {
        // Return cached if available and path matches
        cachedConfig?.let { 
            if (path == configPath) return it 
        }
        
        configPath = path
        cachedConfig = try {
            ConfigLoader.load<ServicesConfig>(path)
        } catch (e: Exception) {
            // If config file doesn't exist, return empty config
            println("Warning: Could not load services config from $path: ${e.message}")
            ServicesConfig()
        }
        
        return cachedConfig!!
    }
    
    /**
     * Reload configuration from disk.
     */
    fun reload(): ServicesConfig {
        cachedConfig = null
        return load(configPath)
    }
    
    /**
     * Get a service by name.
     */
    fun getService(name: String): ServiceDefinition? {
        return load().services.find { it.name == name }
    }
    
    /**
     * Get all enabled services.
     */
    fun getEnabledServices(): List<ServiceDefinition> {
        return load().services.filter { it.enabled }
    }
    
    /**
     * Get all tools from all enabled services.
     */
    fun getAllTools(): List<ToolDefinition> {
        return getEnabledServices().flatMap { it.tools }
    }
}
