package io.noumena.mcp.gateway.upstream

import io.noumena.mcp.shared.config.ServicesConfigLoader
import io.noumena.mcp.shared.config.ServiceDefinition
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
 */
class UpstreamRouter {

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
     * Only returns enabled services.
     */
    fun getService(serviceName: String): ServiceDefinition? {
        return try {
            ServicesConfigLoader.load(configPath).services
                .find { it.name == serviceName && it.enabled }
        } catch (e: Exception) {
            logger.warn { "Failed to look up service '$serviceName': ${e.message}" }
            null
        }
    }

    /**
     * Get all enabled services with their tools.
     */
    fun getEnabledServices(): List<ServiceDefinition> {
        return try {
            ServicesConfigLoader.load(configPath).services.filter { it.enabled }
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
