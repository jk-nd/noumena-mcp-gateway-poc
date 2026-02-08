package io.noumena.mcp.gateway.server

import io.noumena.mcp.gateway.credentials.UserContext
import io.noumena.mcp.gateway.policy.NplClient
import io.noumena.mcp.gateway.upstream.UpstreamRouter
import io.noumena.mcp.gateway.upstream.UpstreamSessionManager
import io.noumena.mcp.shared.config.ServicesConfigLoader
import kotlinx.serialization.json.*
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Transparent MCP proxy handler.
 *
 * The Gateway acts as an MCP proxy:
 *   1. Aggregates tools from all enabled upstream services (namespaced)
 *   2. Checks NPL policy before forwarding tool calls
 *   3. Forwards approved calls to upstream MCP services
 *   4. Returns upstream results to the agent
 *
 * Tool namespacing:
 *   Each tool is prefixed with its service name: "{service}.{tool}"
 *   e.g., "duckduckgo.search", "github.create_issue"
 */
class McpServerHandler(
    private val nplClient: NplClient = NplClient(),
    private val upstreamRouter: UpstreamRouter,
    private val upstreamSessionManager: UpstreamSessionManager
) {
    
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    /**
     * Handle tools/list: aggregate namespaced tools from all enabled services.
     * 
     * Tools are loaded from services.yaml configuration. Each tool name is
     * prefixed with the service name (namespace) to avoid collisions.
     * Only enabled services and enabled tools are returned.
     */
    suspend fun handleToolsList(requestId: JsonElement?, userId: String): String {
        val configPath = System.getenv("SERVICES_CONFIG_PATH") ?: "/app/configs/services.yaml"
        
        val namespacedTools = try {
            ServicesConfigLoader.load(configPath).services
                .filter { it.enabled }
                .flatMap { svc ->
                    svc.tools
                        .filter { it.enabled }
                        .map { tool -> svc to tool }
                }
        } catch (e: Exception) {
            logger.warn { "Failed to load services config: ${e.message}. Using empty tool list." }
            emptyList()
        }

        logger.info { "Returning ${namespacedTools.size} namespaced tools from ${
            namespacedTools.map { it.first.name }.distinct().size
        } services" }

        val response = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", requestId ?: JsonNull)
            putJsonObject("result") {
                putJsonArray("tools") {
                    namespacedTools.forEach { (svc, tool) ->
                        addJsonObject {
                            // Namespaced tool name: service.tool
                            put("name", "${svc.name}.${tool.name}")
                            put("description", "[${svc.displayName}] ${tool.description}")
                            putJsonObject("inputSchema") {
                                put("type", tool.inputSchema.type)
                                putJsonObject("properties") {
                                    tool.inputSchema.properties.forEach { (propName, propSchema) ->
                                        putJsonObject(propName) {
                                            put("type", propSchema.type)
                                            put("description", propSchema.description)
                                        }
                                    }
                                }
                                if (tool.inputSchema.required.isNotEmpty()) {
                                    putJsonArray("required") {
                                        tool.inputSchema.required.forEach { add(it) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        return json.encodeToString(JsonObject.serializer(), response)
    }

    /**
     * Handle tools/call: parse namespace, check NPL policy, forward to upstream.
     *
     * Flow:
     *   1. Parse namespace from tool name (e.g., "duckduckgo.search")
     *   2. Resolve to upstream service via UpstreamRouter
     *   3. Check NPL policy (fail-closed: deny if NPL unavailable)
     *   4. Forward to upstream MCP service via UpstreamSessionManager
     *   5. Return upstream result to agent
     */
    suspend fun handleToolCall(
        requestId: JsonElement,
        namespacedToolName: String,
        arguments: JsonObject,
        userId: String
    ): String {
        val startTime = System.currentTimeMillis()

        // Step 1: Resolve the namespaced tool
        val resolved = upstreamRouter.resolve(namespacedToolName)
        if (resolved == null) {
            logger.warn { "Tool '$namespacedToolName' not found or disabled" }
            return buildErrorResponse(
                requestId = requestId,
                service = namespacedToolName,
                operation = "unknown",
                errorType = "TOOL_NOT_FOUND",
                message = "Tool '$namespacedToolName' not found or disabled. Use tools/list to see available tools.",
                durationMs = System.currentTimeMillis() - startTime
            )
        }

        logger.info { "Resolved: $namespacedToolName → ${resolved.serviceName}.${resolved.toolName}" }

        // Step 2: Check NPL policy (fail-closed)
        try {
            val policyResponse = nplClient.checkPolicy(
                service = resolved.serviceName,
                operation = resolved.toolName,
                userId = userId
            )

            if (!policyResponse.allowed) {
                logger.info { "NPL DENIED: ${resolved.serviceName}.${resolved.toolName} - ${policyResponse.reason}" }
                return buildErrorResponse(
                    requestId = requestId,
                    service = resolved.serviceName,
                    operation = resolved.toolName,
                    errorType = "POLICY_DENIED",
                    message = policyResponse.reason ?: "Policy denied",
                    durationMs = System.currentTimeMillis() - startTime
                )
            }

            logger.info { "NPL APPROVED: ${resolved.serviceName}.${resolved.toolName}" }
        } catch (e: Exception) {
            // Fail-closed: if NPL is unavailable, deny the request
            logger.error(e) { "NPL policy check failed (fail-closed)" }
            return buildErrorResponse(
                requestId = requestId,
                service = resolved.serviceName,
                operation = resolved.toolName,
                errorType = "POLICY_UNAVAILABLE",
                message = "Policy engine unavailable. Request denied (fail-closed).",
                durationMs = System.currentTimeMillis() - startTime
            )
        }

        // Step 3: Forward to upstream MCP service with user context for credentials
        val userContext = UserContext(userId = userId, tenantId = "default")
        val upstreamResult = upstreamSessionManager.forwardToolCall(resolved, arguments, userContext)
        val durationMs = System.currentTimeMillis() - startTime

        // Step 4: Build response
        return if (upstreamResult.success && upstreamResult.content != null) {
            buildSuccessResponse(
                requestId = requestId,
                content = upstreamResult.content,
                service = resolved.serviceName,
                operation = resolved.toolName,
                durationMs = durationMs
            )
        } else if (upstreamResult.content != null) {
            // Upstream returned content with isError=true - pass through
            buildUpstreamErrorResponse(
                requestId = requestId,
                content = upstreamResult.content,
                service = resolved.serviceName,
                operation = resolved.toolName,
                durationMs = durationMs
            )
        } else {
            buildErrorResponse(
                requestId = requestId,
                service = resolved.serviceName,
                operation = resolved.toolName,
                errorType = "UPSTREAM_ERROR",
                message = upstreamResult.error ?: "Unknown upstream error",
                durationMs = durationMs
            )
        }
    }

    /**
     * Build a successful JSON-RPC response with upstream content passed through.
     */
    private fun buildSuccessResponse(
        requestId: JsonElement,
        content: JsonArray,
        service: String,
        operation: String,
        durationMs: Long
    ): String {
        val contextJson = buildContextJson("SUCCESS", service, operation, durationMs)

        val response = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", requestId)
            putJsonObject("result") {
                // Pass through upstream content + add Gateway context
                putJsonArray("content") {
                    content.forEach { add(it) }
                    addJsonObject {
                        put("type", "text")
                        put("text", "---\n$contextJson")
                    }
                }
                put("isError", false)
            }
        }
        return json.encodeToString(JsonObject.serializer(), response)
    }

    /**
     * Build a response passing through upstream error content.
     */
    private fun buildUpstreamErrorResponse(
        requestId: JsonElement,
        content: JsonArray,
        service: String,
        operation: String,
        durationMs: Long
    ): String {
        val contextJson = buildContextJson("UPSTREAM_ERROR", service, operation, durationMs)

        val response = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", requestId)
            putJsonObject("result") {
                putJsonArray("content") {
                    content.forEach { add(it) }
                    addJsonObject {
                        put("type", "text")
                        put("text", "---\n$contextJson")
                    }
                }
                put("isError", true)
            }
        }
        return json.encodeToString(JsonObject.serializer(), response)
    }

    /**
     * Build a JSON-RPC error response from the Gateway itself.
     */
    private fun buildErrorResponse(
        requestId: JsonElement,
        service: String,
        operation: String,
        errorType: String,
        message: String,
        durationMs: Long
    ): String {
        val contextJson = buildContextJson(errorType, service, operation, durationMs)

        val response = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", requestId)
            putJsonObject("result") {
                putJsonArray("content") {
                    addJsonObject {
                        put("type", "text")
                        put("text", message)
                    }
                    addJsonObject {
                        put("type", "text")
                        put("text", "---\n$contextJson")
                    }
                }
                put("isError", true)
            }
        }
        return json.encodeToString(JsonObject.serializer(), response)
    }

    /**
     * Build context metadata JSON for response.
     */
    private fun buildContextJson(
        status: String,
        service: String,
        operation: String,
        durationMs: Long
    ): String {
        val context = buildJsonObject {
            put("status", status)
            put("service", service)
            put("operation", operation)
            put("durationMs", durationMs)
            put("timestamp", java.time.Instant.now().toString())
            put("gateway", "noumena-mcp-gateway")
            put("version", "2.0.0")
        }
        return context.toString()
    }
}
