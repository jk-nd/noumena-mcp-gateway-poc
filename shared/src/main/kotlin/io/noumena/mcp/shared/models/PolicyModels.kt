package io.noumena.mcp.shared.models

import kotlinx.serialization.Serializable

/**
 * Response from NPL Engine after policy evaluation.
 *
 * V2: Simplified - no more callback URLs or executor-specific fields.
 * The Gateway checks policy synchronously and forwards if allowed.
 */
@Serializable
data class PolicyResponse(
    /** Whether the request is allowed */
    val allowed: Boolean,

    /** If allowed, optional request ID for tracking/audit */
    val requestId: String? = null,

    /** Reason for allow/deny */
    val reason: String? = null,

    /** If pending approval, the approval ID */
    val approvalId: String? = null,

    /** Whether the request requires human approval */
    val requiresApproval: Boolean = false
)
