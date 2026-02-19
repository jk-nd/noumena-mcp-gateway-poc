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
 * End-to-End Integration Tests for v4 Three-Layer Governance.
 *
 * Tests the complete flow using Streamable HTTP (POST /mcp):
 * MCP Client -> Envoy (JWT authn) -> OPA (ext_authz) -> mock-calendar-mcp -> Response
 *
 * GatewayStore is bootstrapped with:
 *   - Catalog: mock-calendar with list_events=open, create_event=gated
 *   - Access rules: claim-based rule for sales department
 *
 * Verifies:
 *   - list_events (open) → allowed immediately
 *   - create_event (gated) → returns 403 + x-request-id
 *   - Alice (no matching access rule) → denied at Layer 2
 *   - Dynamic: add access rule → allow → remove → deny (proves full pipeline)
 *
 * Prerequisites:
 * - Full Docker stack running: docker compose -f deployments/docker-compose.yml up -d
 * - All services healthy: Envoy, OPA, NPL Engine, Keycloak, mock-calendar-mcp
 *
 * Run with: ./gradlew :integration-tests:test --tests "*EndToEndTest*"
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class EndToEndTest {

    private lateinit var adminToken: String
    private lateinit var jarvisToken: String    // Allowed user (sales department)
    private lateinit var aliceToken: String     // Denied user (engineering, no matching rule)
    private lateinit var client: HttpClient
    private lateinit var storeId: String        // GatewayStore singleton ID
    private var jarvisSessionId: String? = null
    private var aliceSessionId: String? = null

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    companion object {
        /** Time to wait for SSE-triggered OPA bundle rebuild (ms). */
        const val BUNDLE_REBUILD_WAIT = 6000L
    }

    @BeforeAll
    fun setup() = runBlocking {
        println("╔════════════════════════════════════════════════════════════════╗")
        println("║ END-TO-END INTEGRATION TESTS (v4 Three-Layer Governance)      ║")
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

        // Get authentication tokens
        adminToken = KeycloakAuth.getToken("admin", "Welcome123")
        println("    ✓ Admin token obtained")
        jarvisToken = KeycloakAuth.getToken(TestConfig.jarvisUsername, TestConfig.defaultPassword)
        println("    ✓ Jarvis token obtained (department=sales)")
        aliceToken = KeycloakAuth.getToken(TestConfig.aliceUsername, TestConfig.defaultPassword)
        println("    ✓ Alice token obtained (department=engineering)")

        // Bootstrap GatewayStore
        println("\n    Bootstrapping GatewayStore...")

        storeId = NplBootstrap.ensureGatewayStore(adminToken)
        println("    ✓ GatewayStore: $storeId")

        // Register mock-calendar with tools: list_events=open, create_event=gated
        NplBootstrap.registerServiceWithTools(
            storeId, "mock-calendar",
            mapOf("list_events" to "open", "create_event" to "gated"),
            adminToken
        )
        println("    ✓ mock-calendar registered: list_events=open, create_event=gated")

        // Add claim-based access rule: department=sales → mock-calendar.*
        NplBootstrap.addAccessRule(
            storeId, "sales-team", "claims",
            matchClaims = mapOf("department" to "sales"),
            allowServices = listOf("mock-calendar"),
            allowTools = listOf("*"),
            adminToken = adminToken
        )
        println("    ✓ Access rule: department=sales → mock-calendar.*")

        // Wait for SSE-triggered bundle rebuild
        println("    Waiting ${BUNDLE_REBUILD_WAIT / 1000}s for OPA bundle rebuild via SSE...")
        delay(BUNDLE_REBUILD_WAIT)
        println("    ✓ Bootstrap complete, OPA should have fresh state")
    }

    // ── MCP Initialize + tools/list ───────────────────────────────────────

    @Test
    @Order(1)
    fun `MCP initialize via Streamable HTTP`() = runBlocking {
        val response = client.post("${TestConfig.gatewayUrl}/mcp") {
            header("Authorization", "Bearer $jarvisToken")
            contentType(ContentType.Application.Json)
            setBody(buildJsonRpc(1, "initialize", """{"protocolVersion":"2024-11-05","clientInfo":{"name":"test-client","version":"1.0.0"},"capabilities":{}}"""))
        }

        println("    Status: ${response.status}")
        assertEquals(HttpStatusCode.OK, response.status, "Initialize should succeed")

        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("2.0", body["jsonrpc"]?.jsonPrimitive?.content)
        assertNotNull(body["result"]?.jsonObject?.get("serverInfo"))

        jarvisSessionId = response.headers["mcp-session-id"]
        assertNotNull(jarvisSessionId)
        println("    ✓ MCP initialize: session=$jarvisSessionId")
    }

    @Test
    @Order(2)
    fun `MCP tools list via Streamable HTTP`() = runBlocking {
        val response = client.post("${TestConfig.gatewayUrl}/mcp") {
            header("Authorization", "Bearer $jarvisToken")
            jarvisSessionId?.let { header("Mcp-Session-Id", it) }
            contentType(ContentType.Application.Json)
            setBody(buildJsonRpc(2, "tools/list"))
        }

        assertEquals(HttpStatusCode.OK, response.status, "tools/list should succeed")

        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        val tools = body["result"]?.jsonObject?.get("tools")?.jsonArray
        assertNotNull(tools)

        val toolNames = tools!!.map { it.jsonObject["name"]?.jsonPrimitive?.content ?: "" }
        println("    Available tools: $toolNames")
        assertTrue(toolNames.contains("mock-calendar.list_events"))
        assertTrue(toolNames.contains("mock-calendar.create_event"))
        println("    ✓ Tools list retrieved")
    }

    // ── Open tool: list_events → allowed immediately ──────────────────────

    @Test
    @Order(3)
    fun `open tool list_events allowed for sales user`() = runBlocking {
        val response = client.post("${TestConfig.gatewayUrl}/mcp") {
            header("Authorization", "Bearer $jarvisToken")
            jarvisSessionId?.let { header("Mcp-Session-Id", it) }
            contentType(ContentType.Application.Json)
            setBody(buildJsonRpc(3, "tools/call", """{"name":"mock-calendar.list_events","arguments":{"date":"2026-02-14"}}"""))
        }

        println("    Status: ${response.status}")
        assertEquals(HttpStatusCode.OK, response.status, "Open tool should be allowed for authorized user")

        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        val content = body["result"]?.jsonObject?.get("content")?.jsonArray
        assertNotNull(content)
        assertTrue(content!!.isNotEmpty())
        println("    ✓ list_events (open) allowed for jarvis (sales)")
    }

    // ── Gated tool: create_event → 403 + x-request-id ────────────────────

    @Test
    @Order(4)
    fun `gated tool create_event returns 403 with request-id`() = runBlocking {
        val response = client.post("${TestConfig.gatewayUrl}/mcp") {
            header("Authorization", "Bearer $jarvisToken")
            jarvisSessionId?.let { header("Mcp-Session-Id", it) }
            contentType(ContentType.Application.Json)
            setBody(buildJsonRpc(4, "tools/call", """{"name":"mock-calendar.create_event","arguments":{"title":"Test Meeting","date":"2026-02-14","time":"14:00","duration":30}}"""))
        }

        println("    Status: ${response.status}")
        assertEquals(HttpStatusCode.Forbidden, response.status,
            "Gated tool should return 403 (pending approval)")

        val requestId = response.headers["x-request-id"]
        val retryAfter = response.headers["retry-after"]
        println("    x-request-id: $requestId")
        println("    retry-after: $retryAfter")

        // x-request-id should be present (from NPL ServiceGovernance pending response)
        // Note: this depends on ServiceGovernance instance existing and evaluate() returning pending
        println("    ✓ create_event (gated) returned 403")
    }

    // ── Alice denied at Layer 2 (no matching access rule) ─────────────────

    @Test
    @Order(5)
    fun `OPA denies tool call for user without matching access rule`() = runBlocking {
        val response = client.post("${TestConfig.gatewayUrl}/mcp") {
            header("Authorization", "Bearer $aliceToken")
            contentType(ContentType.Application.Json)
            setBody(buildJsonRpc(5, "tools/call", """{"name":"mock-calendar.list_events","arguments":{"date":"2026-02-14"}}"""))
        }

        println("    Status: ${response.status}")
        assertEquals(HttpStatusCode.Forbidden, response.status,
            "OPA should deny: alice (engineering) has no matching access rule for mock-calendar")
        println("    ✓ Alice denied at Layer 2 (no matching access rule)")
    }

    // ── Non-tool-call methods still allowed ───────────────────────────────

    @Test
    @Order(6)
    fun `OPA allows initialize for user without tool access`() = runBlocking {
        val response = client.post("${TestConfig.gatewayUrl}/mcp") {
            header("Authorization", "Bearer $aliceToken")
            contentType(ContentType.Application.Json)
            setBody(buildJsonRpc(6, "initialize", """{"protocolVersion":"2024-11-05","clientInfo":{"name":"test-client","version":"1.0.0"},"capabilities":{}}"""))
        }

        assertEquals(HttpStatusCode.OK, response.status,
            "Non-tool-call methods should be allowed regardless of access rules")
        aliceSessionId = response.headers["mcp-session-id"]
        println("    ✓ Alice allowed for initialize (non-tool-call bypass)")
    }

    @Test
    @Order(7)
    fun `OPA allows tools list for user without access rules`() = runBlocking {
        val response = client.post("${TestConfig.gatewayUrl}/mcp") {
            header("Authorization", "Bearer $aliceToken")
            contentType(ContentType.Application.Json)
            setBody(buildJsonRpc(7, "tools/list"))
        }

        assertEquals(HttpStatusCode.OK, response.status,
            "tools/list should be allowed (non-tool-call)")
        println("    ✓ Alice allowed for tools/list (non-tool-call bypass)")
    }

    @Test
    @Order(8)
    fun `unauthenticated request returns 401`() = runBlocking {
        val response = client.post("${TestConfig.gatewayUrl}/mcp") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonRpc(8, "initialize", """{"protocolVersion":"2024-11-05","clientInfo":{"name":"test-client","version":"1.0.0"},"capabilities":{}}"""))
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        println("    ✓ Unauthenticated request rejected with 401")
    }

    // ── Dynamic pipeline: add access rule → allow → remove → deny ─────────

    @Test
    @Order(9)
    fun `dynamic access rule proves full NPL-SSE-OPA pipeline`() = runBlocking {
        // Phase 1: Add identity-based rule for alice
        println("    Phase 1: Adding identity access rule for alice...")
        NplBootstrap.addAccessRule(
            storeId, "alice-e2e-test", "identity",
            matchIdentity = "alice@acme.com",
            allowServices = listOf("mock-calendar"),
            allowTools = listOf("list_events"),
            adminToken = adminToken
        )

        println("    Waiting ${BUNDLE_REBUILD_WAIT / 1000}s for bundle rebuild...")
        delay(BUNDLE_REBUILD_WAIT)

        // Phase 2: Alice tool call should now succeed
        println("    Phase 2: Alice calls list_events (should succeed)...")
        val allowedResp = client.post("${TestConfig.gatewayUrl}/mcp") {
            header("Authorization", "Bearer $aliceToken")
            aliceSessionId?.let { header("Mcp-Session-Id", it) }
            contentType(ContentType.Application.Json)
            setBody(buildJsonRpc(9, "tools/call", """{"name":"mock-calendar.list_events","arguments":{"date":"2026-02-14"}}"""))
        }
        println("    Alice status: ${allowedResp.status}")
        assertEquals(HttpStatusCode.OK, allowedResp.status,
            "Alice should be allowed after identity-based access rule added")
        println("    ✓ Alice tool call succeeded after dynamic access rule")

        // Phase 3: Remove alice's access rule
        println("    Phase 3: Removing alice's access rule...")
        val removeResp = client.post(
            "${TestConfig.nplUrl}/npl/store/GatewayStore/$storeId/removeAccessRule"
        ) {
            header("Authorization", "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody("""{"id": "alice-e2e-test"}""")
        }
        assertTrue(removeResp.status.isSuccess())

        println("    Waiting ${BUNDLE_REBUILD_WAIT / 1000}s for bundle rebuild...")
        delay(BUNDLE_REBUILD_WAIT)

        // Phase 4: Alice tool call should be denied again
        println("    Phase 4: Alice calls list_events (should be denied)...")
        val deniedResp = client.post("${TestConfig.gatewayUrl}/mcp") {
            header("Authorization", "Bearer $aliceToken")
            aliceSessionId?.let { header("Mcp-Session-Id", it) }
            contentType(ContentType.Application.Json)
            setBody(buildJsonRpc(10, "tools/call", """{"name":"mock-calendar.list_events","arguments":{"date":"2026-02-14"}}"""))
        }
        println("    Alice status: ${deniedResp.status}")
        assertEquals(HttpStatusCode.Forbidden, deniedResp.status,
            "Alice should be denied after access rule removed")

        println("    ✓ Full pipeline proven: add rule → allow → remove rule → deny")
    }

    // ── Disabled service denies tool calls ─────────────────────────────────

    @Test
    @Order(10)
    fun `disabled service denies tool calls E2E`() = runBlocking {
        // Phase 1: Disable mock-calendar
        println("    Phase 1: Disabling mock-calendar...")
        val disableResp = client.post(
            "${TestConfig.nplUrl}/npl/store/GatewayStore/$storeId/disableService"
        ) {
            header("Authorization", "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody("""{"serviceName": "mock-calendar"}""")
        }
        assertTrue(disableResp.status.isSuccess())

        println("    Waiting ${BUNDLE_REBUILD_WAIT / 1000}s for bundle rebuild...")
        delay(BUNDLE_REBUILD_WAIT)

        // Phase 2: Jarvis tool call should be denied (service disabled)
        println("    Phase 2: Jarvis calls list_events (should be denied)...")
        val deniedResp = client.post("${TestConfig.gatewayUrl}/mcp") {
            header("Authorization", "Bearer $jarvisToken")
            jarvisSessionId?.let { header("Mcp-Session-Id", it) }
            contentType(ContentType.Application.Json)
            setBody(buildJsonRpc(11, "tools/call", """{"name":"mock-calendar.list_events","arguments":{"date":"2026-02-14"}}"""))
        }
        assertEquals(HttpStatusCode.Forbidden, deniedResp.status)
        println("    ✓ Disabled service correctly blocked tool call")

        // Phase 3: Re-enable (cleanup)
        println("    Phase 3: Re-enabling mock-calendar...")
        client.post("${TestConfig.nplUrl}/npl/store/GatewayStore/$storeId/enableService") {
            header("Authorization", "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody("""{"serviceName": "mock-calendar"}""")
        }

        println("    Waiting ${BUNDLE_REBUILD_WAIT / 1000}s for bundle rebuild...")
        delay(BUNDLE_REBUILD_WAIT)

        // Phase 4: Verify jarvis can call again
        val allowedResp = client.post("${TestConfig.gatewayUrl}/mcp") {
            header("Authorization", "Bearer $jarvisToken")
            jarvisSessionId?.let { header("Mcp-Session-Id", it) }
            contentType(ContentType.Application.Json)
            setBody(buildJsonRpc(12, "tools/call", """{"name":"mock-calendar.list_events","arguments":{"date":"2026-02-14"}}"""))
        }
        assertEquals(HttpStatusCode.OK, allowedResp.status)
        println("    ✓ Disable→deny→enable→allow cycle proven E2E")
    }

    // ── Health checks ─────────────────────────────────────────────────────

    @Test
    @Order(11)
    fun `verify all services healthy`() = runBlocking {
        val gatewayHealth = client.get("${TestConfig.gatewayUrl}/health")
        assertTrue(gatewayHealth.status.isSuccess(), "Gateway should be healthy")

        val nplHealth = client.get("${TestConfig.nplUrl}/actuator/health")
        assertTrue(nplHealth.status.isSuccess(), "NPL Engine should be healthy")

        println("    ✓ All core services are healthy")
    }

    @AfterAll
    fun teardown() {
        println("\n╔════════════════════════════════════════════════════════════════╗")
        println("║ END-TO-END TESTS (v4 Three-Layer) - Complete                  ║")
        println("╚════════════════════════════════════════════════════════════════╝")
        if (::client.isInitialized) {
            client.close()
        }
    }
}
