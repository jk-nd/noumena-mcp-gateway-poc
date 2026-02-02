package io.noumena.mcp.gateway.server

import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.http.*
import io.noumena.mcp.gateway.auth.ActorTokenValidator
import io.noumena.mcp.gateway.policy.NplClient
import io.noumena.mcp.shared.models.PolicyRequest
import kotlinx.serialization.Serializable
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * MCP tool call request from an agent.
 */
@Serializable
data class ToolCallRequest(
    val tool: String,
    val arguments: Map<String, String> = emptyMap()
)

@Serializable
data class ToolCallResponse(
    val status: String,
    val requestId: String? = null,
    val result: Map<String, String>? = null,
    val error: String? = null
)

/**
 * MCP routes - agent-facing endpoints.
 */
fun Route.mcpRoutes() {
    val tokenValidator = ActorTokenValidator()
    val nplClient = NplClient()
    
    route("/mcp") {
        // List available tools
        get("/tools") {
            // TODO: Fetch from NPL or config based on user's allowed services
            call.respond(mapOf(
                "tools" to listOf(
                    mapOf("name" to "google_gmail.send_email", "description" to "Send an email"),
                    mapOf("name" to "slack.send_message", "description" to "Send a Slack message"),
                    mapOf("name" to "sap.create_purchase_order", "description" to "Create SAP PO")
                )
            ))
        }
        
        // Call a tool
        post("/call") {
            val authHeader = call.request.header("Authorization")
            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Missing or invalid Authorization header"))
                return@post
            }
            
            val token = authHeader.removePrefix("Bearer ")
            
            // 1. Validate Actor Token
            val claims = try {
                tokenValidator.validate(token)
            } catch (e: Exception) {
                logger.warn { "Token validation failed: ${e.message}" }
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid token"))
                return@post
            }
            
            val request = call.receive<ToolCallRequest>()
            val (service, operation) = request.tool.split(".", limit = 2).let {
                if (it.size == 2) it[0] to it[1] else it[0] to "default"
            }
            
            // 2. Check token scope
            if (!claims.hasScope(service)) {
                call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Service not in token scope"))
                return@post
            }
            
            logger.info { "Tool call: ${request.tool} by agent=${claims.agentId} for user=${claims.delegatedBy}" }
            
            // 3. Call NPL for policy check (NPL will trigger Executor if allowed)
            val callbackUrl = System.getenv("CALLBACK_URL") ?: "http://gateway:8080/callback"
            val policyRequest = PolicyRequest(
                tenantId = claims.tenantId,
                userId = claims.delegatedBy,
                agentId = claims.agentId,
                service = service,
                operation = operation,
                metadata = extractMetadata(request.arguments),
                params = request.arguments,
                callbackUrl = callbackUrl
            )
            
            val policyResponse = nplClient.checkPolicy(policyRequest)
            
            when {
                !policyResponse.allowed -> {
                    call.respond(HttpStatusCode.Forbidden, ToolCallResponse(
                        status = "denied",
                        error = policyResponse.reason ?: "Policy denied"
                    ))
                }
                policyResponse.requiresApproval -> {
                    call.respond(HttpStatusCode.Accepted, ToolCallResponse(
                        status = "pending_approval",
                        requestId = policyResponse.approvalId
                    ))
                }
                else -> {
                    // NPL has triggered Executor - wait for callback
                    // For now, return immediately with request ID
                    // TODO: Implement async waiting with coroutines
                    call.respond(HttpStatusCode.Accepted, ToolCallResponse(
                        status = "executing",
                        requestId = policyResponse.requestId
                    ))
                }
            }
        }
    }
}

/**
 * Extract metadata fields for policy decisions (not content).
 */
private fun extractMetadata(args: Map<String, String>): Map<String, String> {
    val metadata = mutableMapOf<String, String>()
    
    // Extract recipient domain from email addresses
    args["to"]?.let { email ->
        email.substringAfter("@", "").takeIf { it.isNotEmpty() }?.let {
            metadata["recipient_domain"] = it
        }
    }
    
    // Extract amount for financial operations
    args["amount"]?.let { metadata["amount"] = it }
    
    // Extract channel type for messaging
    args["channel"]?.let { channel ->
        metadata["channel_type"] = when {
            channel.startsWith("C") -> "public"
            channel.startsWith("G") -> "private"
            channel.startsWith("D") -> "dm"
            else -> "unknown"
        }
    }
    
    return metadata
}
