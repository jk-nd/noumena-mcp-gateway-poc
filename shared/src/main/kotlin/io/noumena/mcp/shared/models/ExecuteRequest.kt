package io.noumena.mcp.shared.models

import kotlinx.serialization.Serializable

/**
 * Request from NPL Engine to Executor to execute an upstream call.
 * This is sent via NPL's HTTP Bridge when a policy check passes.
 * 
 * SECURITY: No signature field needed - network isolation ensures
 * only NPL can reach the Executor endpoint.
 */
@Serializable
data class ExecuteRequest(
    /** Unique request ID for tracking */
    val requestId: String,
    
    /** Tenant ID from the actor token */
    val tenantId: String,
    
    /** User ID (delegated_by from actor token) */
    val userId: String,
    
    /** Target service (e.g., "google_gmail", "slack", "sap") */
    val service: String,
    
    /** Operation/tool to call (e.g., "send_email", "create_po") */
    val operation: String,
    
    /** Parameters for the operation (JSON object) */
    val params: Map<String, String> = emptyMap(),
    
    /** URL where Executor should send the result */
    val callbackUrl: String
)

/**
 * Result from Executor back to Gateway.
 */
@Serializable
data class ExecuteResult(
    /** Request ID this result corresponds to */
    val requestId: String,
    
    /** Whether the execution succeeded */
    val success: Boolean,
    
    /** Result data (if success) */
    val data: Map<String, String>? = null,
    
    /** Error message (if failure) */
    val error: String? = null
)
