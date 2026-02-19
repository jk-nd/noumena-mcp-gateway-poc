package io.noumena.mcp.integration

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*

/**
 * E2E Store-and-Forward Approval Tests (v4 ServiceGovernance).
 *
 * Tests the full gated tool approval flow:
 * Envoy -> OPA -> NPL ServiceGovernance -> approve/deny -> re-call
 *
 * GatewayStore bootstrap:
 *   - mock-calendar: list_events=open, create_event=gated
 *   - Access rule: department=sales → mock-calendar.*
 *   - ServiceGovernance instance for mock-calendar
 *
 * Verifies:
 *   - Open tool (list_events) bypasses governance
 *   - Gated tool (create_event) returns 403 + pending
 *   - Approve via ServiceGovernance → re-call → allowed
 *   - Deny flow end-to-end
 *
 * Prerequisites:
 * - Full Docker stack running: docker compose -f deployments/docker-compose.yml up -d
 *
 * Run with: ./gradlew :integration-tests:test --tests "*StoreAndForwardTest*"
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class StoreAndForwardTest {

    private lateinit var adminToken: String
    private lateinit var jarvisToken: String
    private lateinit var client: HttpClient
    private lateinit var storeId: String
    private lateinit var governanceId: String
    private var mcpSessionId: String? = null
    private var capturedRequestId: String? = null

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    companion object {
        const val BUNDLE_REBUILD_WAIT = 6000L
    }

    @BeforeAll
    fun setup() = runBlocking {
        println("╔════════════════════════════════════════════════════════════════╗")
        println("║ STORE-AND-FORWARD APPROVAL TESTS (v4 ServiceGovernance)       ║")
        println("╠════════════════════════════════════════════════════════════════╣")
        println("║ Gateway URL:  ${TestConfig.gatewayUrl}")
        println("║ NPL URL:      ${TestConfig.nplUrl}")
        println("║ Keycloak URL: ${TestConfig.keycloakUrl}")
        println("╚════════════════════════════════════════════════════════════════╝")

        client = HttpClient(CIO) {
            install(ContentNegotiation) {
                json(json)
            }
        }

        Assumptions.assumeTrue(isDockerStackRunning()) {
            "Docker stack is not running. Start with: docker compose -f deployments/docker-compose.yml up -d"
        }
        Assumptions.assumeTrue(isGatewayRunning()) {
            "Gateway is not running."
        }

        // Get tokens
        adminToken = KeycloakAuth.getToken("admin", "Welcome123")
        println("    ✓ Admin token obtained")
        jarvisToken = KeycloakAuth.getToken(TestConfig.jarvisUsername, TestConfig.defaultPassword)
        println("    ✓ Jarvis token obtained (department=sales)")

        // Bootstrap GatewayStore
        println("\n    Bootstrapping GatewayStore + ServiceGovernance...")

        storeId = NplBootstrap.ensureGatewayStore(adminToken)
        println("    ✓ GatewayStore: $storeId")

        // Register mock-calendar: list_events=open, create_event=gated
        NplBootstrap.registerServiceWithTools(
            storeId, "mock-calendar",
            mapOf("list_events" to "open", "create_event" to "gated"),
            adminToken
        )
        println("    ✓ mock-calendar registered: list_events=open, create_event=gated")

        // Add claim-based access rule: department=sales → mock-calendar.*
        NplBootstrap.addAccessRule(
            storeId, "sales-saf-test", "claims",
            matchClaims = mapOf("department" to "sales"),
            allowServices = listOf("mock-calendar"),
            allowTools = listOf("*"),
            adminToken = adminToken
        )
        println("    ✓ Access rule: department=sales → mock-calendar.*")

        // Create ServiceGovernance instance for mock-calendar
        governanceId = NplBootstrap.ensureServiceGovernance("mock-calendar", adminToken)
        println("    ✓ ServiceGovernance: $governanceId (serviceName=mock-calendar)")

        // Wait for SSE bundle rebuild (includes governance_instances mapping)
        println("    Waiting ${BUNDLE_REBUILD_WAIT / 1000}s for OPA bundle rebuild via SSE...")
        delay(BUNDLE_REBUILD_WAIT)

        // Initialize MCP session
        val initResp = client.post("${TestConfig.gatewayUrl}/mcp") {
            header("Authorization", "Bearer $jarvisToken")
            contentType(ContentType.Application.Json)
            setBody(buildJsonRpc(0, "initialize", """{"protocolVersion":"2024-11-05","clientInfo":{"name":"saf-test","version":"1.0.0"},"capabilities":{}}"""))
        }
        Assumptions.assumeTrue(initResp.status.isSuccess()) { "MCP initialize failed: ${initResp.status}" }
        mcpSessionId = initResp.headers["mcp-session-id"]
        println("    ✓ MCP session initialized (session=$mcpSessionId)")
        println("    ✓ Bootstrap complete")
    }

    // ── Test 1: Open tool bypass ──────────────────────────────────────────

    @Test
    @Order(1)
    fun `open tool list_events allowed without governance`() = runBlocking {
        val response = mcpPost(1, "tools/call",
            """{"name":"mock-calendar.list_events","arguments":{"date":"2026-02-14"}}""")

        println("    Status: ${response.status}")
        assertEquals(HttpStatusCode.OK, response.status,
            "Open tools should bypass ServiceGovernance")

        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertNotNull(body["result"]?.jsonObject?.get("content"))
        println("    ✓ list_events (open) allowed without governance")
    }

    // ── Test 2: Gated tool → 403 + pending ────────────────────────────────

    @Test
    @Order(2)
    fun `gated tool create_event returns 403 pending`() = runBlocking {
        val params = """{"name":"mock-calendar.create_event","arguments":{"title":"Approval Test","date":"2026-02-15","time":"10:00","duration":30}}"""
        val response = mcpPost(2, "tools/call", params)

        println("    Status: ${response.status}")
        assertEquals(HttpStatusCode.Forbidden, response.status,
            "Gated tool should return 403 (pending approval via ServiceGovernance)")

        capturedRequestId = response.headers["x-request-id"]
        val retryAfter = response.headers["retry-after"]
        println("    x-request-id: $capturedRequestId")
        println("    retry-after: $retryAfter")

        println("    ✓ create_event (gated) returned 403 pending")
    }

    // ── Test 3: Verify pending in ServiceGovernance ───────────────────────

    @Test
    @Order(3)
    fun `verify pending request in ServiceGovernance`() = runBlocking {
        val response = client.post(
            "${TestConfig.nplUrl}/npl/governance/ServiceGovernance/$governanceId/getPendingRequests"
        ) {
            header("Authorization", "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertTrue(response.status.isSuccess())

        val pending = json.parseToJsonElement(response.bodyAsText()).jsonArray
        println("    Pending requests: ${pending.size}")
        assertTrue(pending.isNotEmpty(), "Should have at least one pending request")

        val request = pending.first().jsonObject
        assertEquals("pending", request["status"]?.jsonPrimitive?.content)
        assertEquals("create_event", request["toolName"]?.jsonPrimitive?.content)
        println("    ✓ Pending request verified: tool=create_event, status=pending")
    }

    // ── Test 4: Approve → re-call → allowed ──────────────────────────────

    @Test
    @Order(4)
    fun `approve then re-call returns allowed`() = runBlocking {
        // Find the request ID from ServiceGovernance pending list
        val pendingResp = client.post(
            "${TestConfig.nplUrl}/npl/governance/ServiceGovernance/$governanceId/getPendingRequests"
        ) {
            header("Authorization", "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        val pending = json.parseToJsonElement(pendingResp.bodyAsText()).jsonArray
        val requestId = pending.first().jsonObject["requestId"]!!.jsonPrimitive.content
        println("    Approving request: $requestId")

        // Approve
        val approveResp = client.post(
            "${TestConfig.nplUrl}/npl/governance/ServiceGovernance/$governanceId/approve"
        ) {
            header("Authorization", "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody("""{"requestId": "$requestId"}""")
        }
        assertTrue(approveResp.status.isSuccess(), "approve() should succeed")
        println("    ✓ Approved $requestId")

        // Re-call the same gated tool → OPA calls evaluate() → returns "allow" (consumed)
        val params = """{"name":"mock-calendar.create_event","arguments":{"title":"Approval Test","date":"2026-02-15","time":"10:00","duration":30}}"""
        val response = mcpPost(4, "tools/call", params)

        println("    Re-call status: ${response.status}")
        assertEquals(HttpStatusCode.OK, response.status,
            "After approval, gated tool call should succeed")

        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        val content = body["result"]?.jsonObject?.get("content")?.jsonArray
        assertNotNull(content, "Should have result content")
        println("    ✓ Approve → re-call → allowed (create_event executed)")
    }

    // ── Test 5: Deny flow end-to-end ──────────────────────────────────────

    @Test
    @Order(5)
    fun `deny flow - gated call denied end-to-end`() = runBlocking {
        // Trigger a new pending request with different arguments
        val params = """{"name":"mock-calendar.create_event","arguments":{"title":"Denied Meeting","date":"2026-03-01","time":"09:00","duration":60}}"""
        val triggerResp = mcpPost(5, "tools/call", params)
        assertEquals(HttpStatusCode.Forbidden, triggerResp.status)
        println("    ✓ New gated call → 403 (pending)")

        // Find the new request ID
        val pendingResp = client.post(
            "${TestConfig.nplUrl}/npl/governance/ServiceGovernance/$governanceId/getPendingRequests"
        ) {
            header("Authorization", "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        val pending = json.parseToJsonElement(pendingResp.bodyAsText()).jsonArray
        assertTrue(pending.isNotEmpty())
        val requestId = pending.last().jsonObject["requestId"]!!.jsonPrimitive.content
        println("    New request: $requestId")

        // Deny it
        val denyResp = client.post(
            "${TestConfig.nplUrl}/npl/governance/ServiceGovernance/$governanceId/deny"
        ) {
            header("Authorization", "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody("""{"requestId": "$requestId", "reason": "Meeting not needed"}""")
        }
        assertTrue(denyResp.status.isSuccess(), "deny() should succeed")
        println("    ✓ Denied $requestId: Meeting not needed")

        // Re-call → OPA calls evaluate() → returns "deny" → 403
        val deniedResp = mcpPost(6, "tools/call", params)
        assertEquals(HttpStatusCode.Forbidden, deniedResp.status,
            "After denial, gated tool call should still be 403")

        val reason = deniedResp.headers["x-authz-reason"]
        println("    Re-call status: ${deniedResp.status}, reason: $reason")
        println("    ✓ Deny flow verified end-to-end")
    }

    // ── Cleanup ───────────────────────────────────────────────────────────

    @AfterAll
    fun teardown() = runBlocking {
        println("\n╔════════════════════════════════════════════════════════════════╗")
        println("║ STORE-AND-FORWARD TESTS (v4) - Cleanup                        ║")
        println("╠════════════════════════════════════════════════════════════════╣")

        if (::client.isInitialized && ::storeId.isInitialized && ::adminToken.isInitialized) {
            try {
                // Remove the test access rule (ignore errors if already removed)
                client.post("${TestConfig.nplUrl}/npl/store/GatewayStore/$storeId/removeAccessRule") {
                    header("Authorization", "Bearer $adminToken")
                    contentType(ContentType.Application.Json)
                    setBody("""{"id": "sales-saf-test"}""")
                }
                println("║ ✓ Access rule cleaned up                                      ║")
            } catch (e: Exception) {
                println("║ ⚠ Cleanup error: ${e.message?.take(45)}")
            }
        }

        println("╚════════════════════════════════════════════════════════════════╝")
        if (::client.isInitialized) {
            client.close()
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private suspend fun mcpPost(id: Int, method: String, params: String = "{}"): HttpResponse {
        return client.post("${TestConfig.gatewayUrl}/mcp") {
            header("Authorization", "Bearer $jarvisToken")
            mcpSessionId?.let { header("Mcp-Session-Id", it) }
            contentType(ContentType.Application.Json)
            setBody(buildJsonRpc(id, method, params))
        }
    }
}
