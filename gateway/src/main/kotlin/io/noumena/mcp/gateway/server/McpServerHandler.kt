package io.noumena.mcp.gateway.server

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.modelcontextprotocol.kotlin.sdk.Implementation
import io.modelcontextprotocol.kotlin.sdk.server.*
import io.modelcontextprotocol.kotlin.sdk.types.*
import io.noumena.mcp.gateway.auth.ActorTokenValidator
import io.noumena.mcp.gateway.callback.PendingRequests
import io.noumena.mcp.gateway.context.ContextStore
import io.noumena.mcp.gateway.messaging.ExecutorPublisher
import io.noumena.mcp.gateway.policy.NplClient
import io.noumena.mcp.shared.models.ExecutionNotification
import io.noumena.mcp.shared.models.PolicyRequest
import io.noumena.mcp.shared.models.StoredContext
import kotlinx.serialization.json.*
import mu.KotlinLogging
import java.util.UUID

private val logger = KotlinLogging.logger {}

/**
 * MCP Server that exposes tools to AI agents.
 * 
 * Flow:
 * 1. Receives MCP tool call from LLM
 * 2. Calls NPL Engine for policy check (authorization + business rules)
 * 3. If denied → returns MCP error
 * 4. If allowed → calls Executor directly (sync)
 * 5. Executor adds secrets and calls upstream MCP service
 * 6. Returns proper MCP response to LLM
 */
class McpServerHandler(
    private val tokenValidator: ActorTokenValidator = ActorTokenValidator(),
    private val nplClient: NplClient = NplClient()
) {
    
    // Timeout for executor calls (default 30 seconds)
    private val executionTimeoutMs = System.getenv("EXECUTION_TIMEOUT_MS")?.toLongOrNull() ?: 30_000L
    
    // Gateway URL for executor to fetch context
    private val gatewayUrl = System.getenv("GATEWAY_URL") ?: "http://gateway:8080"
    
    // Callback URL for executor to send results
    private val callbackUrl = System.getenv("CALLBACK_URL") ?: "$gatewayUrl/callback"
    
    /**
     * Create and configure the MCP Server.
     */
    fun createServer(): Server {
        val server = Server(
            serverInfo = Implementation(
                name = "noumena-mcp-gateway",
                version = "1.0.0"
            ),
            options = ServerOptions(
                capabilities = ServerCapabilities(
                    tools = ServerCapabilities.Tools(listChanged = true)
                )
            )
        )
        
        // Register tools
        registerEmailTool(server)
        registerSlackTool(server)
        registerCalendarTool(server)
        
        logger.info { "MCP Server configured with tools" }
        
        return server
    }
    
    private fun registerEmailTool(server: Server) {
        server.addTool(
            name = "google_gmail_send_email",
            description = "Send an email via Google Gmail. Requires NPL policy approval."
        ) { request ->
            handleToolCall(
                service = "google_gmail",
                operation = "send_email",
                arguments = request.arguments?.toMap() ?: emptyMap(),
                context = request
            )
        }
        
        server.addTool(
            name = "google_gmail_read_email",
            description = "Read emails from Google Gmail inbox."
        ) { request ->
            handleToolCall(
                service = "google_gmail",
                operation = "read_email",
                arguments = request.arguments?.toMap() ?: emptyMap(),
                context = request
            )
        }
    }
    
    private fun registerSlackTool(server: Server) {
        server.addTool(
            name = "slack_send_message",
            description = "Send a message to a Slack channel or user."
        ) { request ->
            handleToolCall(
                service = "slack",
                operation = "send_message",
                arguments = request.arguments?.toMap() ?: emptyMap(),
                context = request
            )
        }
    }
    
    private fun registerCalendarTool(server: Server) {
        server.addTool(
            name = "google_calendar_create_event",
            description = "Create a calendar event in Google Calendar."
        ) { request ->
            handleToolCall(
                service = "google_calendar",
                operation = "create_event",
                arguments = request.arguments?.toMap() ?: emptyMap(),
                context = request
            )
        }
    }
    
    /**
     * Handle a tool call directly (public method for WebSocket handler).
     * 
     * Flow:
     * 1. Generate requestId
     * 2. Store context in ContextStore (body never sent to NPL)
     * 3. Call NPL for policy check (metadata only)
     * 4. Wait for Executor callback (sync-over-async)
     * 5. Return result to LLM
     */
    suspend fun handleToolCallDirect(
        service: String,
        operation: String,
        arguments: JsonObject
    ): CallToolResult {
        val argsMap = arguments.mapValues { (_, v) -> v }
        // Create a minimal CallToolRequest params object
        val params = CallToolRequestParams(name = "$service.$operation", arguments = arguments)
        return handleToolCall(service, operation, argsMap, CallToolRequest(params = params))
    }
    
    /**
     * Handle a tool call by checking NPL policy.
     * 
     * Flow:
     * 1. Generate requestId
     * 2. Store context in ContextStore (body never sent to NPL)
     * 3. Call NPL for policy check (metadata only)
     * 4. Wait for Executor callback (sync-over-async)
     * 5. Return result to LLM
     */
    private suspend fun handleToolCall(
        service: String,
        operation: String,
        arguments: Map<String, JsonElement>,
        context: CallToolRequest
    ): CallToolResult {
        logger.info { "Tool call: $service.$operation" }
        
        // Extract string arguments
        val params = arguments.mapValues { (_, v) ->
            when (v) {
                is JsonPrimitive -> v.content
                else -> v.toString()
            }
        }
        
        // Extract metadata for policy decision (not the full body)
        val metadata = extractMetadata(params)
        
        // Extract tenant/user info for later use
        val tenantId = params["tenant_id"] ?: "default"
        val userId = params["user_id"] ?: "unknown"
        
        // Build policy request (metadata only - no body!)
        // Note: In production, we'd extract tenantId, userId, agentId from the transport context
        val policyRequest = PolicyRequest(
            tenantId = tenantId,
            userId = userId,
            agentId = params["agent_id"] ?: "unknown",
            service = service,
            operation = operation,
            metadata = metadata,  // Only metadata for policy decision
            params = params,      // TODO: This should be removed - body should only be in ContextStore
            callbackUrl = callbackUrl
        )
        
        val startTime = System.currentTimeMillis()
        
        return try {
            // Step 1: Call NPL for policy check
            val policyResponse = nplClient.checkPolicy(policyRequest)
            
            // Step 2: Handle immediate responses (rejections, approvals)
            when {
                !policyResponse.allowed -> {
                    return buildErrorResult(
                        service = service,
                        operation = operation,
                        errorType = "POLICY_DENIED",
                        message = policyResponse.reason ?: "Not allowed",
                        requestId = null,
                        durationMs = System.currentTimeMillis() - startTime
                    )
                }
                policyResponse.requiresApproval -> {
                    return buildPendingApprovalResult(
                        service = service,
                        operation = operation,
                        approvalId = policyResponse.approvalId ?: "unknown",
                        durationMs = System.currentTimeMillis() - startTime
                    )
                }
            }
            
            // Step 3: NPL accepted - get request ID and wait for executor callback
            val requestId = policyResponse.requestId
            if (requestId == null) {
                return buildErrorResult(
                    service = service,
                    operation = operation,
                    errorType = "INTERNAL_ERROR",
                    message = "NPL returned no request ID",
                    requestId = null,
                    durationMs = System.currentTimeMillis() - startTime
                )
            }
            
            logger.info { "NPL accepted request $requestId, triggering Executor via RabbitMQ..." }
            
            // Store context NOW with NPL's requestId (Executor will fetch this)
            val storedContext = StoredContext(
                requestId = requestId,
                tenantId = tenantId,
                userId = userId,
                service = service,
                operation = operation,
                body = params  // Full body stored here, never sent to NPL
            )
            ContextStore.store(storedContext)
            logger.info { "Stored context for NPL request: $requestId" }
            
            // Register for callback before we miss it
            PendingRequests.register(requestId)
            
            // Step 4: Publish to RabbitMQ for Executor
            val notification = ExecutionNotification(
                requestId = requestId,
                tenantId = tenantId,
                userId = userId,
                service = service,
                operation = operation,
                gatewayUrl = gatewayUrl,
                callbackUrl = callbackUrl
            )
            
            val published = ExecutorPublisher.publish(notification)
            if (!published) {
                return buildErrorResult(
                    service = service,
                    operation = operation,
                    errorType = "INTERNAL_ERROR",
                    message = "Failed to publish execution request to RabbitMQ",
                    requestId = requestId,
                    durationMs = System.currentTimeMillis() - startTime
                )
            }
            
            // Step 5: Wait for executor result (sync-over-async)
            val executionResult = PendingRequests.await(requestId, executionTimeoutMs)
            
            // Step 6: Return result to MCP client
            val durationMs = System.currentTimeMillis() - startTime
            
            if (executionResult == null) {
                buildErrorResult(
                    service = service,
                    operation = operation,
                    errorType = "TIMEOUT",
                    message = "Request timed out after ${executionTimeoutMs}ms",
                    requestId = requestId,
                    durationMs = durationMs
                )
            } else if (executionResult.success) {
                buildSuccessResult(
                    service = service,
                    operation = operation,
                    data = executionResult.data,
                    mcpContent = executionResult.mcpContent,
                    requestId = requestId,
                    durationMs = durationMs
                )
            } else {
                buildErrorResult(
                    service = service,
                    operation = operation,
                    errorType = "EXECUTION_FAILED",
                    message = executionResult.error ?: "Unknown error",
                    mcpContent = executionResult.mcpContent,
                    mcpIsError = executionResult.mcpIsError,
                    requestId = requestId,
                    durationMs = durationMs
                )
            }
        } catch (e: Exception) {
            logger.error(e) { "Tool call failed for $service.$operation" }
            buildErrorResult(
                service = service,
                operation = operation,
                errorType = "EXCEPTION",
                message = e.message ?: "Unknown exception",
                requestId = null,
                durationMs = System.currentTimeMillis() - startTime
            )
        }
    }
    
    /**
     * Build a successful result with full context.
     * If MCP content is available, parse and pass it through.
     */
    private fun buildSuccessResult(
        service: String,
        operation: String,
        data: Map<String, String>?,
        mcpContent: String?,
        requestId: String,
        durationMs: Long
    ): CallToolResult {
        val contextJson = buildContextJson(
            status = "SUCCESS",
            service = service,
            operation = operation,
            requestId = requestId,
            durationMs = durationMs
        )
        
        // Try to use raw MCP content if available
        val contentBlocks = if (mcpContent != null) {
            parseMcpContent(mcpContent) + listOf(TextContent(text = "---\n$contextJson"))
        } else {
            listOf(
                TextContent(text = formatSuccessResult(data)),
                TextContent(text = "---\n$contextJson")
            )
        }
        
        return CallToolResult(content = contentBlocks)
    }
    
    /**
     * Build an error result with full context.
     */
    private fun buildErrorResult(
        service: String,
        operation: String,
        errorType: String,
        message: String,
        mcpContent: String? = null,
        mcpIsError: Boolean = false,
        requestId: String?,
        durationMs: Long
    ): CallToolResult {
        val contextJson = buildContextJson(
            status = "ERROR",
            errorType = errorType,
            service = service,
            operation = operation,
            requestId = requestId,
            durationMs = durationMs
        )
        
        // Use MCP content if available (for upstream errors)
        val contentBlocks = if (mcpContent != null && mcpIsError) {
            parseMcpContent(mcpContent) + listOf(TextContent(text = "---\n$contextJson"))
        } else {
            listOf(
                TextContent(text = message),
                TextContent(text = "---\n$contextJson")
            )
        }
        
        return CallToolResult(
            content = contentBlocks,
            isError = true
        )
    }
    
    /**
     * Parse raw MCP content JSON into TextContent blocks.
     */
    private fun parseMcpContent(mcpContentJson: String): List<TextContent> {
        return try {
            val contentArray = Json.parseToJsonElement(mcpContentJson).jsonArray
            contentArray.mapNotNull { item ->
                val obj = item.jsonObject
                when (obj["type"]?.jsonPrimitive?.content) {
                    "text" -> {
                        val text = obj["text"]?.jsonPrimitive?.content ?: return@mapNotNull null
                        TextContent(text = text)
                    }
                    // For image, audio, etc. - currently just convert to text representation
                    // In a full implementation, we'd use ImageContent, etc.
                    else -> {
                        TextContent(text = item.toString())
                    }
                }
            }
        } catch (e: Exception) {
            logger.warn(e) { "Failed to parse MCP content, using raw" }
            listOf(TextContent(text = mcpContentJson))
        }
    }
    
    /**
     * Build a pending approval result.
     */
    private fun buildPendingApprovalResult(
        service: String,
        operation: String,
        approvalId: String,
        durationMs: Long
    ): CallToolResult {
        val message = "Request pending approval. A human must approve this action before it can proceed."
        val contextJson = buildContextJson(
            status = "PENDING_APPROVAL",
            service = service,
            operation = operation,
            requestId = approvalId,
            durationMs = durationMs
        )
        
        return CallToolResult(
            content = listOf(
                TextContent(text = message),
                TextContent(text = "---\n$contextJson")
            )
        )
    }
    
    /**
     * Build context JSON for response metadata.
     */
    private fun buildContextJson(
        status: String,
        service: String,
        operation: String,
        requestId: String?,
        durationMs: Long,
        errorType: String? = null
    ): String {
        val context = buildJsonObject {
            put("status", status)
            put("service", service)
            put("operation", operation)
            requestId?.let { put("requestId", it) }
            errorType?.let { put("errorType", it) }
            put("durationMs", durationMs)
            put("timestamp", java.time.Instant.now().toString())
            put("gateway", "noumena-mcp-gateway")
        }
        return context.toString()
    }
    
    /**
     * Format successful execution result for display.
     */
    private fun formatSuccessResult(data: Map<String, String>?): String {
        if (data.isNullOrEmpty()) {
            return "Operation completed successfully."
        }
        
        // Check for common result patterns
        return when {
            data.containsKey("result_0") -> data["result_0"] ?: "Success"
            data.containsKey("message") -> data["message"] ?: "Success"
            data.containsKey("status") -> "Status: ${data["status"]}"
            else -> data.entries.joinToString("\n") { "${it.key}: ${it.value}" }
        }
    }
    
    /**
     * Extract metadata for policy decisions (domain, amount, etc.)
     */
    private fun extractMetadata(params: Map<String, String>): Map<String, String> {
        val metadata = mutableMapOf<String, String>()
        
        // Extract recipient domain from email addresses
        params["to"]?.let { email ->
            email.substringAfter("@", "").takeIf { it.isNotEmpty() }?.let {
                metadata["recipient_domain"] = it
            }
        }
        
        // Extract amount for financial operations
        params["amount"]?.let { metadata["amount"] = it }
        
        return metadata
    }
}
