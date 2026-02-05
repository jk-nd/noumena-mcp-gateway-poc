package io.noumena.mcp.gateway.context

import io.noumena.mcp.shared.models.StoredContext
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull

/**
 * Comprehensive tests for ContextStore.
 * 
 * The ContextStore is a critical security component that:
 * 1. Stores request bodies before NPL validation
 * 2. Allows Executor to fetch context after NPL approval
 * 3. Ensures context can only be consumed once (claim-check pattern)
 * 4. Cleans up expired contexts automatically
 */
@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class ContextStoreTest {
    
    @BeforeEach
    fun setup() {
        println("═══════════════════════════════════════════════════════════════")
        println("Setting up test - clearing ContextStore")
        println("═══════════════════════════════════════════════════════════════")
        // Clear any existing entries by consuming them all
        // In a real implementation, we'd have a clear() method for testing
    }
    
    @Test
    fun `store and fetch context successfully`() = runTest {
        println("┌─────────────────────────────────────────────────────────────┐")
        println("│ TEST: store and fetch context successfully                 │")
        println("└─────────────────────────────────────────────────────────────┘")
        
        // Given: A context to store
        val requestId = "test-request-${System.currentTimeMillis()}"
        val context = StoredContext(
            requestId = requestId,
            tenantId = "tenant-1",
            userId = "user-1",
            service = "google_gmail",
            operation = "send_email",
            body = mapOf(
                "to" to "test@example.com",
                "subject" to "Test Email",
                "body" to "This is a test email"
            )
        )
        
        println("Step 1: Storing context")
        println("  - Request ID: ${context.requestId}")
        println("  - Service: ${context.service}")
        println("  - Operation: ${context.operation}")
        println("  - Body keys: ${context.body.keys}")
        
        // When: We store the context
        val storedId = ContextStore.store(context)
        
        println("Step 2: Context stored")
        println("  - Returned ID: $storedId")
        
        assertEquals(requestId, storedId, "Store should return the request ID")
        
        // And: We fetch and consume it
        println("Step 3: Fetching and consuming context")
        val fetched = ContextStore.fetchAndConsume(requestId)
        
        // Then: We get the same context back
        assertNotNull(fetched, "Fetched context should not be null")
        println("Step 4: Context fetched successfully")
        println("  - Request ID: ${fetched?.requestId}")
        println("  - Service: ${fetched?.service}")
        println("  - Body: ${fetched?.body}")
        
        assertEquals(context.requestId, fetched?.requestId)
        assertEquals(context.tenantId, fetched?.tenantId)
        assertEquals(context.userId, fetched?.userId)
        assertEquals(context.service, fetched?.service)
        assertEquals(context.operation, fetched?.operation)
        assertEquals(context.body, fetched?.body)
        
        println("✓ TEST PASSED: Context stored and fetched correctly")
    }
    
    @Test
    fun `context can only be consumed once`() = runTest {
        println("┌─────────────────────────────────────────────────────────────┐")
        println("│ TEST: context can only be consumed once                    │")
        println("└─────────────────────────────────────────────────────────────┘")
        
        // Given: A stored context
        val requestId = "test-consume-once-${System.currentTimeMillis()}"
        val context = StoredContext(
            requestId = requestId,
            tenantId = "tenant-1",
            userId = "user-1",
            service = "slack",
            operation = "send_message",
            body = mapOf("channel" to "#general", "message" to "Hello!")
        )
        
        println("Step 1: Storing context with ID: $requestId")
        ContextStore.store(context)
        
        // When: We consume it the first time
        println("Step 2: First fetch attempt")
        val firstFetch = ContextStore.fetchAndConsume(requestId)
        assertNotNull(firstFetch, "First fetch should succeed")
        println("  - First fetch: SUCCESS")
        
        // And: We try to consume it again
        println("Step 3: Second fetch attempt (should fail)")
        val secondFetch = ContextStore.fetchAndConsume(requestId)
        assertNull(secondFetch, "Second fetch should return null (already consumed)")
        println("  - Second fetch: NULL (as expected)")
        
        println("✓ TEST PASSED: Context can only be consumed once")
    }
    
    @Test
    fun `fetch non-existent context returns null`() = runTest {
        println("┌─────────────────────────────────────────────────────────────┐")
        println("│ TEST: fetch non-existent context returns null              │")
        println("└─────────────────────────────────────────────────────────────┘")
        
        // Given: A non-existent request ID
        val requestId = "non-existent-${System.currentTimeMillis()}"
        
        println("Step 1: Attempting to fetch non-existent context")
        println("  - Request ID: $requestId")
        
        // When: We try to fetch it
        val fetched = ContextStore.fetchAndConsume(requestId)
        
        // Then: We get null
        println("Step 2: Result: ${if (fetched == null) "NULL" else "FOUND"}")
        assertNull(fetched, "Non-existent context should return null")
        
        println("✓ TEST PASSED: Non-existent context returns null")
    }
    
    @Test
    fun `peek does not consume context`() = runTest {
        println("┌─────────────────────────────────────────────────────────────┐")
        println("│ TEST: peek does not consume context                        │")
        println("└─────────────────────────────────────────────────────────────┘")
        
        // Given: A stored context
        val requestId = "test-peek-${System.currentTimeMillis()}"
        val context = StoredContext(
            requestId = requestId,
            tenantId = "tenant-1",
            userId = "user-1",
            service = "google_calendar",
            operation = "create_event",
            body = mapOf("title" to "Meeting", "date" to "2024-01-15")
        )
        
        println("Step 1: Storing context")
        ContextStore.store(context)
        
        // When: We peek at it multiple times
        println("Step 2: Peeking at context (first time)")
        val peek1 = ContextStore.peek(requestId)
        assertNotNull(peek1)
        println("  - Peek 1: ${peek1?.requestId}")
        
        println("Step 3: Peeking at context (second time)")
        val peek2 = ContextStore.peek(requestId)
        assertNotNull(peek2)
        println("  - Peek 2: ${peek2?.requestId}")
        
        // Then: We can still consume it
        println("Step 4: Consuming context after peeks")
        val consumed = ContextStore.fetchAndConsume(requestId)
        assertNotNull(consumed, "Context should still be consumable after peeks")
        println("  - Consumed: ${consumed?.requestId}")
        
        println("✓ TEST PASSED: Peek does not consume context")
    }
    
    @Test
    fun `remove deletes context`() = runTest {
        println("┌─────────────────────────────────────────────────────────────┐")
        println("│ TEST: remove deletes context                               │")
        println("└─────────────────────────────────────────────────────────────┘")
        
        // Given: A stored context
        val requestId = "test-remove-${System.currentTimeMillis()}"
        val context = StoredContext(
            requestId = requestId,
            tenantId = "tenant-1",
            userId = "user-1",
            service = "test",
            operation = "test",
            body = emptyMap()
        )
        
        println("Step 1: Storing context")
        ContextStore.store(context)
        
        // Verify it exists
        println("Step 2: Verifying context exists")
        val exists = ContextStore.peek(requestId)
        assertNotNull(exists)
        println("  - Context exists: YES")
        
        // When: We remove it
        println("Step 3: Removing context")
        ContextStore.remove(requestId)
        
        // Then: It's gone
        println("Step 4: Verifying context is removed")
        val afterRemove = ContextStore.peek(requestId)
        assertNull(afterRemove, "Context should be null after removal")
        println("  - Context exists: NO")
        
        println("✓ TEST PASSED: Remove deletes context")
    }
    
    @Test
    fun `count returns correct number of stored contexts`() = runTest {
        println("┌─────────────────────────────────────────────────────────────┐")
        println("│ TEST: count returns correct number of stored contexts      │")
        println("└─────────────────────────────────────────────────────────────┘")
        
        val baseCount = ContextStore.count()
        println("Step 1: Initial count: $baseCount")
        
        // Store multiple contexts
        val ids = mutableListOf<String>()
        for (i in 1..3) {
            val requestId = "test-count-$i-${System.currentTimeMillis()}"
            ids.add(requestId)
            val context = StoredContext(
                requestId = requestId,
                tenantId = "tenant-1",
                userId = "user-1",
                service = "test",
                operation = "test",
                body = emptyMap()
            )
            ContextStore.store(context)
            println("Step ${i + 1}: Stored context #$i, count: ${ContextStore.count()}")
        }
        
        assertEquals(baseCount + 3, ContextStore.count(), "Should have 3 more contexts")
        
        // Clean up
        ids.forEach { ContextStore.remove(it) }
        
        println("✓ TEST PASSED: Count returns correct number")
    }
}
