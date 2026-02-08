package io.noumena.mcp.gateway.upstream

import io.noumena.mcp.gateway.policy.NplClient
import io.noumena.mcp.shared.config.ServicesConfigLoader
import io.noumena.mcp.shared.config.ServiceDefinition
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Resolves namespaced tool names (e.g., "duckduckgo.search") to the correct
 * upstream service definition. Handles tool name splitting and service lookup.
 *
 * Tool Namespacing Convention:
 *   {serviceName}.{toolName}  →  e.g., "slack.send_message"
 *
 * When forwarding to upstream, the namespace prefix is stripped:
 *   "duckduckgo.search" → upstream receives "search"
 *
 * Architecture V3: NPL as Source of Truth
 *   - services.yaml: Static config (URLs, schemas, commands)
 *   - NPL ServiceRegistry: Runtime enabled state (source of truth)
 *   - This router queries NPL to determine which services are enabled
 */
class UpstreamRouter(
    private val nplClient: NplClient? = null  // Optional for tests
) {

    private val configPath = System.getenv("SERVICES_CONFIG_PATH") ?: "/app/configs/services.yaml"

    /**
     * Parse a namespaced tool name into (serviceName, toolName).
     * Returns null if the name has no namespace prefix.
     *
     * Examples:
     *   "duckduckgo.search"       → Pair("duckduckgo", "search")
     *   "github.create_issue"     → Pair("github", "create_issue")
     *   "search"                  → null (no namespace)
     */
    fun parseNamespacedTool(namespacedTool: String): Pair<String, String>? {
        val dotIndex = namespacedTool.indexOf('.')
        if (dotIndex <= 0 || dotIndex >= namespacedTool.length - 1) {
            return null
        }
        val serviceName = namespacedTool.substring(0, dotIndex)
        val toolName = namespacedTool.substring(dotIndex + 1)
        return serviceName to toolName
    }

    /**
     * Look up the service definition for a given service name.
     * 
     * V3 Architecture:
     * - Queries NPL ServiceRegistry for enabled state (source of truth)
     * - Reads services.yaml for static config (URLs, schemas, commands)
     * - Only returns services that are enabled in NPL
     * 
     * Fallback: If NPL is unavailable, falls back to services.yaml enabled flag
     */
    fun getService(serviceName: String): ServiceDefinition? {
        return try {
            // Load static config from YAML
            val allServices = ServicesConfigLoader.load(configPath).services
            val service = allServices.find { it.name == serviceName } ?: return null
            
            // Check if enabled in NPL (source of truth)
            if (nplClient != null) {
                val enabledInNpl = runBlocking { nplClient.isServiceEnabled(serviceName) }
                if (!enabledInNpl) {
                    logger.debug { "Service '$serviceName' exists in YAML but is not enabled in NPL" }
                    return null
                }
            } else {
                // Fallback: use YAML enabled flag (for tests or when NPL unavailable)
                if (!service.enabled) {
                    logger.debug { "Service '$serviceName' is disabled in YAML (NPL check skipped)" }
                    return null
                }
            }
            
            service
        } catch (e: Exception) {
            logger.warn { "Failed to look up service '$serviceName': ${e.message}" }
            null
        }
    }

    /**
     * Get all enabled services with their tools.
     * 
     * V3 Architecture:
     * - Queries NPL ServiceRegistry for enabled services (source of truth)
     * - Reads services.yaml for static config (URLs, schemas, commands)
     * - Returns intersection: services enabled in NPL AND present in YAML
     * 
     * Fallback: If NPL is unavailable, falls back to services.yaml enabled flag
     */
    fun getEnabledServices(): List<ServiceDefinition> {
        return try {
            // Load static config from YAML
            val allServices = ServicesConfigLoader.load(configPath).services
            
            // Get enabled services from NPL (source of truth)
            if (nplClient != null) {
                val enabledInNpl = runBlocking { nplClient.getEnabledServices() }
                logger.info { "NPL reports ${enabledInNpl.size} enabled services: $enabledInNpl" }
                
                // Return services that are enabled in NPL and present in YAML
                val enabledServices = allServices.filter { it.name in enabledInNpl }
                logger.info { "Matched ${enabledServices.size} services from YAML config" }
                return enabledServices
            } else {
                // Fallback: use YAML enabled flag (for tests or when NPL unavailable)
                logger.debug { "NPL client unavailable, using YAML enabled flags" }
                return allServices.filter { it.enabled }
            }
        } catch (e: Exception) {
            logger.warn { "Failed to load services config: ${e.message}" }
            emptyList()
        }
    }

    /**
     * Resolve a namespaced tool to its upstream service and stripped tool name.
     * Also checks that the specific tool is enabled.
     *
     * Returns null if:
     *  - No namespace in tool name
     *  - Service not found or disabled
     *  - Specific tool is disabled
     */
    fun resolve(namespacedTool: String): ResolvedTool? {
        val parsed = parseNamespacedTool(namespacedTool)
        if (parsed == null) {
            // Fallback: try to find by raw tool name across all services
            return resolveByRawToolName(namespacedTool)
        }

        val (serviceName, toolName) = parsed
        val service = getService(serviceName) ?: return null

        // Check if the specific tool is enabled
        val toolDef = service.tools.find { it.name == toolName }
        if (toolDef != null && !toolDef.enabled) {
            logger.info { "Tool '$toolName' in service '$serviceName' is disabled" }
            return null
        }

        return ResolvedTool(
            serviceName = serviceName,
            toolName = toolName,
            service = service
        )
    }

    /**
     * Fallback: resolve by raw tool name (without namespace prefix).
     * Searches across all enabled services.
     */
    private fun resolveByRawToolName(toolName: String): ResolvedTool? {
        val services = getEnabledServices()
        for (service in services) {
            val tool = service.tools.find { it.name == toolName && it.enabled }
            if (tool != null) {
                return ResolvedTool(
                    serviceName = service.name,
                    toolName = toolName,
                    service = service
                )
            }
        }
        return null
    }
}

/**
 * Result of resolving a namespaced tool.
 */
data class ResolvedTool(
    /** The upstream service name */
    val serviceName: String,
    /** The tool name to forward (namespace stripped) */
    val toolName: String,
    /** Full service definition */
    val service: ServiceDefinition
)
