package io.noumena.mcp.executor.handler

import io.mockk.*
import io.noumena.mcp.executor.gateway.GatewayClient
import io.noumena.mcp.executor.npl.NplExecutorClient
import io.noumena.mcp.executor.secrets.VaultClient
import io.noumena.mcp.executor.upstream.UpstreamRouter
import io.noumena.mcp.executor.upstream.UpstreamResult
import io.noumena.mcp.shared.models.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertFalse

/**
 * Comprehensive tests for ExecutionHandler.
 * 
 * The ExecutionHandler is the core of the Executor service.
 * It orchestrates:
 * 1. NPL validation (defense in depth)
 * 2. Context fetching from Gateway
 * 3. Upstream MCP/REST calls
 * 4. Completion reporting to NPL
 * 5. Result callback to Gateway
 */
@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class ExecutionHandlerTest {
    
    private lateinit var nplClient: NplExecutorClient
    private lateinit var gatewayClient: GatewayClient
    private lateinit var vaultClient: VaultClient
    private lateinit var upstreamRouter: UpstreamRouter
    private lateinit var handler: ExecutionHandler
    
    @BeforeEach
    fun setup() {
        println("═══════════════════════════════════════════════════════════════")
        println("Setting up test mocks")
        println("═══════════════════════════════════════════════════════════════")
        
        nplClient = mockk(relaxed = true)
        gatewayClient = mockk(relaxed = true)
        vaultClient = mockk(relaxed = true)
        upstreamRouter = mockk(relaxed = true)
        
        // Default: NPL validation passes
        coEvery { nplClient.validate(any()) } returns NplValidationResponse(valid = true)
        
        handler = ExecutionHandler(
            nplClient = nplClient,
            gatewayClient = gatewayClient,
            vaultClient = vaultClient,
            upstreamRouter = upstreamRouter
        )
        
        println("Mocks created: nplClient, gatewayClient, vaultClient, upstreamRouter")
        println("Default: NPL validation -> valid=true")
    }
    
    @AfterEach
    fun teardown() {
        clearAllMocks()
    }
    
    @Test
    fun `successful execution flow`() = runTest {
        println("┌─────────────────────────────────────────────────────────────┐")
        println("│ TEST: Successful execution flow                            │")
        println("│ Verifies the happy path through all execution steps        │")
        println("└─────────────────────────────────────────────────────────────┘")
        
        // Given: A valid execution notification
        val notification = ExecutionNotification(
            requestId = "test-success-123",
            tenantId = "tenant-1",
            userId = "user-1",
            service = "google_gmail",
            operation = "send_email",
            gatewayUrl = "http://gateway:8080",
            callbackUrl = "http://gateway:8080/callback"
        )
        
        println("Step 1: Created notification")
        println("  - requestId: ${notification.requestId}")
        println("  - service: ${notification.service}.${notification.operation}")
        
        // NPL validation already mocked in setup to return valid=true
        println("Step 2: Using default NPL validation -> valid=true")
        
        // And: Gateway returns context
        val storedContext = StoredContext(
            requestId = notification.requestId,
            tenantId = notification.tenantId,
            userId = notification.userId,
            service = notification.service,
            operation = notification.operation,
            body = mapOf(
                "to" to "recipient@example.com",
                "subject" to "Test Email",
                "body" to "Hello from test"
            )
        )
        coEvery { gatewayClient.fetchContext(any(), any()) } returns storedContext
        println("Step 3: Mocked Gateway context fetch")
        println("  - body keys: ${storedContext.body.keys}")
        
        // And: Upstream succeeds
        val upstreamResult = UpstreamResult(
            data = mapOf("message_id" to "msg-456"),
            mcpContent = """[{"type":"text","text":"Email sent successfully"}]""",
            isError = false
        )
        coEvery { upstreamRouter.routeWithFullResult(any()) } returns upstreamResult
        println("Step 4: Mocked upstream router -> success")
        
        // When: Handler processes the notification
        println("\n>>> Executing handler.handle(notification)")
        handler.handle(notification)
        println(">>> Handler completed\n")
        
        // Then: Key steps were called
        println("Step 5: Verifying call sequence")
        
        // Verify context was fetched
        coVerify { gatewayClient.fetchContext(notification.gatewayUrl, notification.requestId) }
        println("  ✓ Gateway context fetched")
        
        // Verify upstream was called
        coVerify { upstreamRouter.routeWithFullResult(any()) }
        println("  ✓ Upstream router called")
        
        // Verify callback was sent
        coVerify { gatewayClient.sendCallback(notification.callbackUrl, any()) }
        println("  ✓ Gateway callback sent")
        
        // Verify the callback contains success
        coVerify {
            gatewayClient.sendCallback(notification.callbackUrl, match { result ->
                result.success && result.requestId == notification.requestId
            })
        }
        println("  ✓ Callback indicates success")
        
        println("\n✓ TEST PASSED: Successful execution flow verified")
    }
    
    @Test
    fun `execution fails when context not found`() = runTest {
        println("┌─────────────────────────────────────────────────────────────┐")
        println("│ TEST: Execution fails when context not found               │")
        println("│ Verifies proper error handling when Gateway has no context │")
        println("└─────────────────────────────────────────────────────────────┘")
        
        // Given
        val notification = ExecutionNotification(
            requestId = "test-no-context-123",
            tenantId = "tenant-1",
            userId = "user-1",
            service = "slack",
            operation = "send_message",
            gatewayUrl = "http://gateway:8080",
            callbackUrl = "http://gateway:8080/callback"
        )
        
        println("Step 1: Created notification for non-existent context")
        
        // And: Gateway returns null (context not found or consumed)
        coEvery { gatewayClient.fetchContext(any(), any()) } returns null
        println("Step 2: Mocked Gateway to return null")
        
        // When
        println("\n>>> Executing handler.handle(notification)")
        handler.handle(notification)
        println(">>> Handler completed\n")
        
        // Then: Upstream was NOT called
        coVerify(exactly = 0) { upstreamRouter.routeWithFullResult(any()) }
        println("Step 3: Verified upstream was NOT called")
        
        // And: Failure callback was sent (capture and check)
        val capturedResult = slot<ExecuteResult>()
        coVerify { gatewayClient.sendCallback(any(), capture(capturedResult)) }
        
        println("Step 4: Captured callback result")
        println("  - success: ${capturedResult.captured.success}")
        println("  - error: ${capturedResult.captured.error}")
        
        assertFalse(capturedResult.captured.success, "Result should indicate failure")
        // Error message should indicate the issue
        val errorMsg = capturedResult.captured.error ?: ""
        println("  - Error message: '$errorMsg'")
        assertTrue(errorMsg.isNotEmpty(), "Error message should not be empty")
        
        println("\n✓ TEST PASSED: Context-not-found error handled correctly")
    }
    
    @Test
    fun `execution fails when upstream returns error`() = runTest {
        println("┌─────────────────────────────────────────────────────────────┐")
        println("│ TEST: Execution fails when upstream returns error          │")
        println("│ Verifies error propagation from upstream MCP services      │")
        println("└─────────────────────────────────────────────────────────────┘")
        
        // Given
        val notification = ExecutionNotification(
            requestId = "test-upstream-error-123",
            tenantId = "tenant-1",
            userId = "user-1",
            service = "google_calendar",
            operation = "create_event",
            gatewayUrl = "http://gateway:8080",
            callbackUrl = "http://gateway:8080/callback"
        )
        
        println("Step 1: Created notification")
        
        // And: Gateway returns context
        val storedContext = StoredContext(
            requestId = notification.requestId,
            tenantId = notification.tenantId,
            userId = notification.userId,
            service = notification.service,
            operation = notification.operation,
            body = mapOf("title" to "Meeting", "date" to "invalid-date")
        )
        coEvery { gatewayClient.fetchContext(any(), any()) } returns storedContext
        println("Step 2: Mocked Gateway context fetch")
        
        // And: Upstream returns error
        val upstreamResult = UpstreamResult(
            data = mapOf("result_0" to "Invalid date format"),
            mcpContent = """[{"type":"text","text":"Error: Invalid date format"}]""",
            isError = true
        )
        coEvery { upstreamRouter.routeWithFullResult(any()) } returns upstreamResult
        println("Step 3: Mocked upstream to return error")
        
        // When
        println("\n>>> Executing handler.handle(notification)")
        handler.handle(notification)
        println(">>> Handler completed\n")
        
        // Then: Capture and verify callback
        val capturedResult = slot<ExecuteResult>()
        coVerify { gatewayClient.sendCallback(any(), capture(capturedResult)) }
        
        println("Step 4: Captured callback result")
        println("  - success: ${capturedResult.captured.success}")
        println("  - mcpIsError: ${capturedResult.captured.mcpIsError}")
        println("  - mcpContent: ${capturedResult.captured.mcpContent}")
        
        assertFalse(capturedResult.captured.success)
        assertTrue(capturedResult.captured.mcpIsError)
        assertTrue(capturedResult.captured.mcpContent?.contains("Invalid date") == true)
        
        println("\n✓ TEST PASSED: Upstream error handled correctly")
    }
    
    @Test
    fun `execution handles exception gracefully`() = runTest {
        println("┌─────────────────────────────────────────────────────────────┐")
        println("│ TEST: Execution handles exception gracefully               │")
        println("│ Verifies that unexpected exceptions are caught and handled │")
        println("└─────────────────────────────────────────────────────────────┘")
        
        // Given
        val notification = ExecutionNotification(
            requestId = "test-exception-123",
            tenantId = "tenant-1",
            userId = "user-1",
            service = "test",
            operation = "test",
            gatewayUrl = "http://gateway:8080",
            callbackUrl = "http://gateway:8080/callback"
        )
        
        println("Step 1: Created notification")
        
        // And: Gateway throws an exception
        coEvery { gatewayClient.fetchContext(any(), any()) } throws RuntimeException("Network timeout")
        println("Step 2: Mocked Gateway to throw exception")
        
        // When
        println("\n>>> Executing handler.handle(notification)")
        handler.handle(notification)  // Should not throw
        println(">>> Handler completed without throwing\n")
        
        // Then: Capture and verify failure callback
        val capturedResult = slot<ExecuteResult>()
        coVerify { gatewayClient.sendCallback(any(), capture(capturedResult)) }
        
        println("Step 3: Captured callback result")
        println("  - success: ${capturedResult.captured.success}")
        println("  - error: ${capturedResult.captured.error}")
        
        assertFalse(capturedResult.captured.success, "Result should indicate failure")
        val errorMsg = capturedResult.captured.error ?: ""
        println("  - Error message: '$errorMsg'")
        assertTrue(errorMsg.isNotEmpty(), "Error message should not be empty")
        
        println("\n✓ TEST PASSED: Exception handled gracefully")
    }
    
    @Test
    fun `MCP content is preserved in result`() = runTest {
        println("┌─────────────────────────────────────────────────────────────┐")
        println("│ TEST: MCP content is preserved in result                   │")
        println("│ Verifies that upstream MCP content passes through intact   │")
        println("└─────────────────────────────────────────────────────────────┘")
        
        // Given
        val notification = ExecutionNotification(
            requestId = "test-mcp-content-123",
            tenantId = "tenant-1",
            userId = "user-1",
            service = "google_gmail",
            operation = "send_email",
            gatewayUrl = "http://gateway:8080",
            callbackUrl = "http://gateway:8080/callback"
        )
        
        val storedContext = StoredContext(
            requestId = notification.requestId,
            tenantId = notification.tenantId,
            userId = notification.userId,
            service = notification.service,
            operation = notification.operation,
            body = mapOf("to" to "test@test.com")
        )
        coEvery { gatewayClient.fetchContext(any(), any()) } returns storedContext
        
        // And: Upstream returns rich MCP content
        val richMcpContent = """[{"type":"text","text":"Email sent!"}]"""
        
        val upstreamResult = UpstreamResult(
            data = mapOf("status" to "sent"),
            mcpContent = richMcpContent,
            isError = false
        )
        coEvery { upstreamRouter.routeWithFullResult(any()) } returns upstreamResult
        
        println("Step 1: Set up upstream with MCP content")
        println("  - Content: $richMcpContent")
        
        // When
        handler.handle(notification)
        
        // Then: MCP content is preserved in callback
        val capturedResult = slot<ExecuteResult>()
        coVerify { gatewayClient.sendCallback(any(), capture(capturedResult)) }
        
        println("Step 2: Captured callback result")
        println("  - success: ${capturedResult.captured.success}")
        println("  - mcpContent: ${capturedResult.captured.mcpContent}")
        println("  - mcpIsError: ${capturedResult.captured.mcpIsError}")
        
        assertTrue(capturedResult.captured.success)
        assertEquals(richMcpContent, capturedResult.captured.mcpContent)
        assertFalse(capturedResult.captured.mcpIsError)
        
        println("\n✓ TEST PASSED: MCP content preserved correctly")
    }
}
