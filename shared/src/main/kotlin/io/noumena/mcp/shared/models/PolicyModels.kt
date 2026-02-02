package io.noumena.mcp.shared.models

import kotlinx.serialization.Serializable

/**
 * Request to NPL Engine for policy evaluation.
 * Contains only METADATA - never content.
 */
@Serializable
data class PolicyRequest(
    /** Tenant ID from actor token */
    val tenantId: String,
    
    /** User on whose behalf agent is acting */
    val userId: String,
    
    /** Agent making the request */
    val agentId: String,
    
    /** Target service */
    val service: String,
    
    /** Operation being requested */
    val operation: String,
    
    /** Metadata extracted from request (e.g., recipient_domain, amount) */
    val metadata: Map<String, String> = emptyMap(),
    
    /** Full params to pass to Executor if allowed */
    val params: Map<String, String> = emptyMap(),
    
    /** URL for Executor to send result back */
    val callbackUrl: String
)

/**
 * Response from NPL Engine after policy evaluation.
 */
@Serializable
data class PolicyResponse(
    /** Whether the request is allowed */
    val allowed: Boolean,
    
    /** If allowed, the request ID for tracking */
    val requestId: String? = null,
    
    /** If denied, the reason */
    val reason: String? = null,
    
    /** If pending approval, the approval ID */
    val approvalId: String? = null,
    
    /** Whether the request requires approval */
    val requiresApproval: Boolean = false
)
