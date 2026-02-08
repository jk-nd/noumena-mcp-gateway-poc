package io.noumena.mcp.shared.models

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import kotlin.test.Test

/**
 * Tests for shared data models.
 *
 * V2 Architecture: Simplified models.
 * Only PolicyResponse remains — ExecuteRequest, ContextModels, and
 * ExecutionNotification have been removed with the Executor pattern.
 */
class ModelsTest {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    @Nested
    @DisplayName("PolicyResponse Tests")
    inner class PolicyTests {

        @Test
        fun `PolicyResponse allowed case`() {
            val response = PolicyResponse(
                allowed = true,
                requestId = "policy-req-123",
                reason = "Policy approved by NPL"
            )

            val serialized = json.encodeToString(response)
            val deserialized = json.decodeFromString<PolicyResponse>(serialized)

            assertTrue(deserialized.allowed)
            assertEquals("policy-req-123", deserialized.requestId)
            assertFalse(deserialized.requiresApproval)
        }

        @Test
        fun `PolicyResponse denied case`() {
            val response = PolicyResponse(
                allowed = false,
                reason = "Service google_gmail is disabled by administrator"
            )

            val serialized = json.encodeToString(response)
            val deserialized = json.decodeFromString<PolicyResponse>(serialized)

            assertFalse(deserialized.allowed)
            assertNull(deserialized.requestId)
            assertEquals("Service google_gmail is disabled by administrator", deserialized.reason)
        }

        @Test
        fun `PolicyResponse requires approval case`() {
            val response = PolicyResponse(
                allowed = true,
                requiresApproval = true,
                approvalId = "approval-456",
                reason = "High-risk operation requires human approval"
            )

            val serialized = json.encodeToString(response)
            val deserialized = json.decodeFromString<PolicyResponse>(serialized)

            assertTrue(deserialized.allowed)
            assertTrue(deserialized.requiresApproval)
            assertEquals("approval-456", deserialized.approvalId)
        }
    }
}
