package io.noumena.mcp.shared.models

import kotlinx.serialization.Serializable

/**
 * Stored context for a pending request.
 * Gateway stores this when receiving an MCP request, Executor fetches it after NPL approval.
 */
@Serializable
data class StoredContext(
    /** Unique request ID */
    val requestId: String,
    
    /** Tenant ID from actor token */
    val tenantId: String,
    
    /** User on whose behalf agent is acting */
    val userId: String,
    
    /** Target service (e.g., "google_gmail") */
    val service: String,
    
    /** Operation being requested (e.g., "send_email") */
    val operation: String,
    
    /** Full request body/arguments (never sent to NPL) */
    val body: Map<String, String>,
    
    /** MCP _meta if present */
    val mcpMeta: Map<String, String>? = null,
    
    /** Timestamp when stored */
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * Response when Executor fetches context from Gateway.
 */
@Serializable
data class ContextResponse(
    /** Whether context was found */
    val found: Boolean,
    
    /** The stored context (if found) */
    val context: StoredContext? = null,
    
    /** Error message (if not found) */
    val error: String? = null
)

/**
 * Request from Executor to NPL to validate approval.
 * Defense-in-depth: confirms NPL really approved this request.
 */
@Serializable
data class NplValidationRequest(
    /** Request ID to validate */
    val requestId: String,
    
    /** Service being executed */
    val service: String,
    
    /** Operation being executed */
    val operation: String
)

/**
 * Response from NPL validation.
 */
@Serializable
data class NplValidationResponse(
    /** Whether the request is valid/approved */
    val valid: Boolean,
    
    /** Reason if not valid */
    val reason: String? = null,
    
    /** When the approval expires */
    val expiresAt: Long? = null
)

/**
 * Request from Executor to NPL to report completion (resume).
 */
@Serializable
data class NplResumeRequest(
    /** Request ID that completed */
    val requestId: String,
    
    /** Whether execution succeeded */
    val success: Boolean,
    
    /** Error message if failed */
    val error: String? = null,
    
    /** Execution duration in ms */
    val durationMs: Long? = null
)

/**
 * Message received by Executor from RabbitMQ (NPL notification).
 */
@Serializable
data class ExecutionNotification(
    /** Unique request ID */
    val requestId: String,
    
    /** Tenant ID */
    val tenantId: String,
    
    /** User ID */
    val userId: String,
    
    /** Target service */
    val service: String,
    
    /** Operation to execute */
    val operation: String,
    
    /** Gateway URL to fetch context */
    val gatewayUrl: String,
    
    /** Gateway callback URL for result */
    val callbackUrl: String
)
