package io.noumena.mcp.shared.models

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import kotlin.test.Test

/**
 * Comprehensive tests for shared data models.
 * 
 * These models are critical for communication between:
 * - Gateway and NPL Engine
 * - Gateway and Executor
 * - Executor and NPL Engine
 */
class ModelsTest {
    
    private val json = Json { 
        prettyPrint = true
        ignoreUnknownKeys = true
    }
    
    @Nested
    @DisplayName("StoredContext Tests")
    inner class StoredContextTests {
        
        @Test
        fun `StoredContext serializes and deserializes correctly`() {
            println("┌─────────────────────────────────────────────────────────────┐")
            println("│ TEST: StoredContext serialization                          │")
            println("└─────────────────────────────────────────────────────────────┘")
            
            // Given
            val context = StoredContext(
                requestId = "req-123",
                tenantId = "tenant-abc",
                userId = "user-456",
                service = "google_gmail",
                operation = "send_email",
                body = mapOf(
                    "to" to "recipient@example.com",
                    "subject" to "Test Subject",
                    "body" to "Test Body"
                ),
                mcpMeta = mapOf("traceId" to "trace-789"),
                createdAt = 1704067200000L
            )
            
            println("Original context:")
            println("  - requestId: ${context.requestId}")
            println("  - tenantId: ${context.tenantId}")
            println("  - service: ${context.service}")
            println("  - body keys: ${context.body.keys}")
            
            // When: Serialize
            val serialized = json.encodeToString(context)
            println("\nSerialized JSON:")
            println(serialized)
            
            // And: Deserialize
            val deserialized = json.decodeFromString<StoredContext>(serialized)
            println("\nDeserialized context:")
            println("  - requestId: ${deserialized.requestId}")
            println("  - tenantId: ${deserialized.tenantId}")
            
            // Then
            assertEquals(context.requestId, deserialized.requestId)
            assertEquals(context.tenantId, deserialized.tenantId)
            assertEquals(context.userId, deserialized.userId)
            assertEquals(context.service, deserialized.service)
            assertEquals(context.operation, deserialized.operation)
            assertEquals(context.body, deserialized.body)
            assertEquals(context.mcpMeta, deserialized.mcpMeta)
            
            println("\n✓ TEST PASSED: StoredContext serializes correctly")
        }
        
        @Test
        fun `StoredContext with null mcpMeta serializes correctly`() {
            println("┌─────────────────────────────────────────────────────────────┐")
            println("│ TEST: StoredContext with null mcpMeta                      │")
            println("└─────────────────────────────────────────────────────────────┘")
            
            val context = StoredContext(
                requestId = "req-null-meta",
                tenantId = "tenant-1",
                userId = "user-1",
                service = "test",
                operation = "test",
                body = emptyMap(),
                mcpMeta = null
            )
            
            val serialized = json.encodeToString(context)
            println("Serialized JSON with null mcpMeta:")
            println(serialized)
            
            val deserialized = json.decodeFromString<StoredContext>(serialized)
            assertNull(deserialized.mcpMeta)
            
            println("\n✓ TEST PASSED: Null mcpMeta handled correctly")
        }
    }
    
    @Nested
    @DisplayName("ExecuteRequest Tests")
    inner class ExecuteRequestTests {
        
        @Test
        fun `ExecuteRequest serializes with all fields`() {
            println("┌─────────────────────────────────────────────────────────────┐")
            println("│ TEST: ExecuteRequest serialization                         │")
            println("└─────────────────────────────────────────────────────────────┘")
            
            val request = ExecuteRequest(
                requestId = "exec-123",
                tenantId = "tenant-1",
                userId = "user-1",
                service = "slack",
                operation = "send_message",
                params = mapOf(
                    "channel" to "#general",
                    "message" to "Hello, World!",
                    "as_user" to "true"
                ),
                callbackUrl = "http://gateway:8080/callback"
            )
            
            println("Original request:")
            println("  - requestId: ${request.requestId}")
            println("  - service: ${request.service}")
            println("  - operation: ${request.operation}")
            println("  - params: ${request.params}")
            println("  - callbackUrl: ${request.callbackUrl}")
            
            val serialized = json.encodeToString(request)
            println("\nSerialized JSON:")
            println(serialized)
            
            val deserialized = json.decodeFromString<ExecuteRequest>(serialized)
            
            assertEquals(request.requestId, deserialized.requestId)
            assertEquals(request.service, deserialized.service)
            assertEquals(request.params, deserialized.params)
            assertEquals(request.callbackUrl, deserialized.callbackUrl)
            
            println("\n✓ TEST PASSED: ExecuteRequest serializes correctly")
        }
    }
    
    @Nested
    @DisplayName("ExecuteResult Tests")
    inner class ExecuteResultTests {
        
        @Test
        fun `ExecuteResult success case serializes correctly`() {
            println("┌─────────────────────────────────────────────────────────────┐")
            println("│ TEST: ExecuteResult success case                           │")
            println("└─────────────────────────────────────────────────────────────┘")
            
            val result = ExecuteResult(
                requestId = "result-123",
                success = true,
                data = mapOf("message_id" to "msg-456", "timestamp" to "2024-01-01T12:00:00Z"),
                mcpContent = """[{"type":"text","text":"Message sent successfully"}]""",
                mcpIsError = false
            )
            
            println("Original result:")
            println("  - requestId: ${result.requestId}")
            println("  - success: ${result.success}")
            println("  - data: ${result.data}")
            println("  - mcpContent: ${result.mcpContent}")
            
            val serialized = json.encodeToString(result)
            println("\nSerialized JSON:")
            println(serialized)
            
            val deserialized = json.decodeFromString<ExecuteResult>(serialized)
            
            assertTrue(deserialized.success)
            assertNull(deserialized.error)
            assertEquals(result.mcpContent, deserialized.mcpContent)
            assertFalse(deserialized.mcpIsError)
            
            println("\n✓ TEST PASSED: Success result serializes correctly")
        }
        
        @Test
        fun `ExecuteResult failure case serializes correctly`() {
            println("┌─────────────────────────────────────────────────────────────┐")
            println("│ TEST: ExecuteResult failure case                           │")
            println("└─────────────────────────────────────────────────────────────┘")
            
            val result = ExecuteResult(
                requestId = "result-error-123",
                success = false,
                error = "Connection timeout to upstream service",
                mcpContent = """[{"type":"text","text":"Error: Connection timeout"}]""",
                mcpIsError = true
            )
            
            println("Original result:")
            println("  - requestId: ${result.requestId}")
            println("  - success: ${result.success}")
            println("  - error: ${result.error}")
            println("  - mcpIsError: ${result.mcpIsError}")
            
            val serialized = json.encodeToString(result)
            println("\nSerialized JSON:")
            println(serialized)
            
            val deserialized = json.decodeFromString<ExecuteResult>(serialized)
            
            assertFalse(deserialized.success)
            assertEquals("Connection timeout to upstream service", deserialized.error)
            assertTrue(deserialized.mcpIsError)
            
            println("\n✓ TEST PASSED: Failure result serializes correctly")
        }
    }
    
    @Nested
    @DisplayName("PolicyRequest/Response Tests")
    inner class PolicyTests {
        
        @Test
        fun `PolicyRequest serializes with metadata`() {
            println("┌─────────────────────────────────────────────────────────────┐")
            println("│ TEST: PolicyRequest serialization                          │")
            println("└─────────────────────────────────────────────────────────────┘")
            
            val request = PolicyRequest(
                tenantId = "tenant-1",
                userId = "user-1",
                agentId = "agent-claude",
                service = "google_gmail",
                operation = "send_email",
                metadata = mapOf(
                    "recipient_domain" to "example.com",
                    "has_attachments" to "false"
                ),
                params = mapOf(
                    "to" to "user@example.com",
                    "subject" to "Test"
                ),
                callbackUrl = "http://gateway:8080/callback"
            )
            
            println("Policy request:")
            println("  - tenantId: ${request.tenantId}")
            println("  - userId: ${request.userId}")
            println("  - agentId: ${request.agentId}")
            println("  - service: ${request.service}")
            println("  - operation: ${request.operation}")
            println("  - metadata: ${request.metadata}")
            
            val serialized = json.encodeToString(request)
            println("\nSerialized JSON:")
            println(serialized)
            
            val deserialized = json.decodeFromString<PolicyRequest>(serialized)
            
            assertEquals(request.tenantId, deserialized.tenantId)
            assertEquals(request.metadata, deserialized.metadata)
            
            println("\n✓ TEST PASSED: PolicyRequest serializes correctly")
        }
        
        @Test
        fun `PolicyResponse allowed case`() {
            println("┌─────────────────────────────────────────────────────────────┐")
            println("│ TEST: PolicyResponse allowed case                          │")
            println("└─────────────────────────────────────────────────────────────┘")
            
            val response = PolicyResponse(
                allowed = true,
                requestId = "policy-req-123",
                reason = "Policy approved by NPL"
            )
            
            println("Response: allowed=${response.allowed}, requestId=${response.requestId}")
            
            val serialized = json.encodeToString(response)
            val deserialized = json.decodeFromString<PolicyResponse>(serialized)
            
            assertTrue(deserialized.allowed)
            assertEquals("policy-req-123", deserialized.requestId)
            assertFalse(deserialized.requiresApproval)
            
            println("\n✓ TEST PASSED: Allowed response works correctly")
        }
        
        @Test
        fun `PolicyResponse denied case`() {
            println("┌─────────────────────────────────────────────────────────────┐")
            println("│ TEST: PolicyResponse denied case                           │")
            println("└─────────────────────────────────────────────────────────────┘")
            
            val response = PolicyResponse(
                allowed = false,
                reason = "Service google_gmail is disabled by administrator"
            )
            
            println("Response: allowed=${response.allowed}, reason=${response.reason}")
            
            val serialized = json.encodeToString(response)
            val deserialized = json.decodeFromString<PolicyResponse>(serialized)
            
            assertFalse(deserialized.allowed)
            assertNull(deserialized.requestId)
            assertEquals("Service google_gmail is disabled by administrator", deserialized.reason)
            
            println("\n✓ TEST PASSED: Denied response works correctly")
        }
        
        @Test
        fun `PolicyResponse requires approval case`() {
            println("┌─────────────────────────────────────────────────────────────┐")
            println("│ TEST: PolicyResponse requires approval case                │")
            println("└─────────────────────────────────────────────────────────────┘")
            
            val response = PolicyResponse(
                allowed = true,
                requiresApproval = true,
                approvalId = "approval-456",
                reason = "High-risk operation requires human approval"
            )
            
            println("Response: requiresApproval=${response.requiresApproval}, approvalId=${response.approvalId}")
            
            val serialized = json.encodeToString(response)
            val deserialized = json.decodeFromString<PolicyResponse>(serialized)
            
            assertTrue(deserialized.allowed)
            assertTrue(deserialized.requiresApproval)
            assertEquals("approval-456", deserialized.approvalId)
            
            println("\n✓ TEST PASSED: Approval required response works correctly")
        }
    }
    
    @Nested
    @DisplayName("NPL Validation Models Tests")
    inner class NplValidationTests {
        
        @Test
        fun `NplValidationRequest serializes correctly`() {
            println("┌─────────────────────────────────────────────────────────────┐")
            println("│ TEST: NplValidationRequest serialization                   │")
            println("└─────────────────────────────────────────────────────────────┘")
            
            val request = NplValidationRequest(
                requestId = "validate-123",
                service = "google_gmail",
                operation = "send_email"
            )
            
            println("Validation request:")
            println("  - requestId: ${request.requestId}")
            println("  - service: ${request.service}")
            println("  - operation: ${request.operation}")
            
            val serialized = json.encodeToString(request)
            println("\nSerialized: $serialized")
            
            val deserialized = json.decodeFromString<NplValidationRequest>(serialized)
            assertEquals(request.requestId, deserialized.requestId)
            assertEquals(request.service, deserialized.service)
            assertEquals(request.operation, deserialized.operation)
            
            println("\n✓ TEST PASSED")
        }
        
        @Test
        fun `NplResumeRequest serializes correctly`() {
            println("┌─────────────────────────────────────────────────────────────┐")
            println("│ TEST: NplResumeRequest serialization                       │")
            println("└─────────────────────────────────────────────────────────────┘")
            
            val request = NplResumeRequest(
                requestId = "resume-123",
                success = true,
                error = null,
                durationMs = 1500
            )
            
            println("Resume request:")
            println("  - requestId: ${request.requestId}")
            println("  - success: ${request.success}")
            println("  - durationMs: ${request.durationMs}")
            
            val serialized = json.encodeToString(request)
            println("\nSerialized: $serialized")
            
            val deserialized = json.decodeFromString<NplResumeRequest>(serialized)
            assertEquals(request.requestId, deserialized.requestId)
            assertTrue(deserialized.success)
            assertEquals(1500L, deserialized.durationMs)
            
            println("\n✓ TEST PASSED")
        }
    }
    
    @Nested
    @DisplayName("ExecutionNotification Tests")
    inner class ExecutionNotificationTests {
        
        @Test
        fun `ExecutionNotification serializes correctly`() {
            println("┌─────────────────────────────────────────────────────────────┐")
            println("│ TEST: ExecutionNotification serialization                  │")
            println("└─────────────────────────────────────────────────────────────┘")
            
            val notification = ExecutionNotification(
                requestId = "notif-123",
                tenantId = "tenant-1",
                userId = "user-1",
                service = "slack",
                operation = "send_message",
                gatewayUrl = "http://gateway:8080",
                callbackUrl = "http://gateway:8080/callback"
            )
            
            println("Notification:")
            println("  - requestId: ${notification.requestId}")
            println("  - service: ${notification.service}")
            println("  - gatewayUrl: ${notification.gatewayUrl}")
            println("  - callbackUrl: ${notification.callbackUrl}")
            
            val serialized = json.encodeToString(notification)
            println("\nSerialized JSON:")
            println(serialized)
            
            val deserialized = json.decodeFromString<ExecutionNotification>(serialized)
            assertEquals(notification.requestId, deserialized.requestId)
            assertEquals(notification.service, deserialized.service)
            assertEquals(notification.gatewayUrl, deserialized.gatewayUrl)
            
            println("\n✓ TEST PASSED")
        }
    }
}
