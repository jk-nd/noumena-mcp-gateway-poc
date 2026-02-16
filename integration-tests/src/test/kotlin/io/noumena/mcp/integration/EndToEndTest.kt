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
 * End-to-End Integration Tests for the Envoy AI Gateway + OPA architecture.
 *
 * Tests the complete flow using Streamable HTTP (POST /mcp):
 * MCP Client -> Envoy (JWT authn) -> OPA (ext_authz) -> mock-calendar-mcp -> Response
 *
 * NPL state (PolicyStore singleton) is bootstrapped in @BeforeAll so that OPA can
 * evaluate access policies. Bundle server subscribes to SSE and rebuilds on state
 * changes, so a short sleep is required after bootstrap.
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

    private lateinit var adminToken: String    // For NPL bootstrap (pAdmin)
    private lateinit var jarvisToken: String   // Allowed user (has tool grants)
    private lateinit var aliceToken: String    // Denied user (no tool grants)
    private lateinit var client: HttpClient
    private lateinit var storeId: String       // PolicyStore singleton ID (for dynamic tests)
    private var jarvisSessionId: String? = null  // MCP session for jarvis
    private var aliceSessionId: String? = null   // MCP session for alice
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
        println("║ END-TO-END INTEGRATION TESTS (Envoy + OPA)                    ║")
        println("╠════════════════════════════════════════════════════════════════╣")
        println("║ Gateway URL:  ${TestConfig.gatewayUrl}")
        println("║ NPL URL:      ${TestConfig.nplUrl}")
        println("║ Keycloak URL: ${TestConfig.keycloakUrl}")
        println("╚════════════════════════════════════════════════════════════════╝")

        // HTTP client for REST calls
        client = HttpClient(CIO) {
            install(ContentNegotiation) {
                json(json)
            }
        }

        // Check if Docker stack is running
        Assumptions.assumeTrue(isDockerStackRunning()) {
            "Docker stack is not running. Start with: docker compose -f deployments/docker-compose.yml up -d"
        }

        // Check if Gateway is running
        Assumptions.assumeTrue(isGatewayRunning()) {
            "Gateway is not running."
        }

        // Get authentication tokens
        adminToken = KeycloakAuth.getToken("admin", "Welcome123")
        println("    ✓ Admin token obtained")
        jarvisToken = KeycloakAuth.getToken(TestConfig.jarvisUsername, TestConfig.defaultPassword)
        println("    ✓ Jarvis token obtained")
        aliceToken = KeycloakAuth.getToken(TestConfig.aliceUsername, TestConfig.defaultPassword)
        println("    ✓ Alice token obtained")

        // Bootstrap NPL state via PolicyStore singleton
        println("\n    Bootstrapping NPL state via PolicyStore...")

        // 1. PolicyStore singleton — find or create
        storeId = NplBootstrap.ensurePolicyStore(adminToken)
        println("    ✓ PolicyStore: $storeId")

        // 2. Register + enable mock-calendar service, enable tools
        NplBootstrap.ensureCatalogService(storeId, "mock-calendar", adminToken)
        println("    ✓ mock-calendar service registered + enabled")
        NplBootstrap.ensureCatalogToolEnabled(storeId, "mock-calendar", "list_events", adminToken)
        NplBootstrap.ensureCatalogToolEnabled(storeId, "mock-calendar", "create_event", adminToken)
        println("    ✓ Tools enabled: list_events, create_event")

        // 3. Grant jarvis wildcard access on mock-calendar
        NplBootstrap.ensureGrantAll(storeId, "jarvis@acme.com", "mock-calendar", adminToken)
        println("    ✓ jarvis granted wildcard (*) on mock-calendar")

        // 4. Alice gets no grants (denied user) — nothing to do

        // 5. Wait for SSE-triggered bundle rebuild
        println("    Waiting ${BUNDLE_REBUILD_WAIT / 1000}s for OPA bundle rebuild via SSE...")
        delay(BUNDLE_REBUILD_WAIT)
        println("    ✓ NPL bootstrap complete, OPA should have fresh state")
    }

    @Test
    @Order(1)
    fun `MCP initialize via Streamable HTTP`() = runBlocking {
        println("\n┌─────────────────────────────────────────────────────────────┐")
        println("│ TEST: MCP Initialize via Streamable HTTP                  │")
        println("└─────────────────────────────────────────────────────────────┘")

        val response = client.post("${TestConfig.gatewayUrl}/mcp") {
            header("Authorization", "Bearer $jarvisToken")
            contentType(ContentType.Application.Json)
            setBody(buildJsonRpc(1, "initialize", """{"protocolVersion":"2024-11-05","clientInfo":{"name":"test-client","version":"1.0.0"},"capabilities":{}}"""))
        }

        println("    Status: ${response.status}")
        val body = response.bodyAsText()
        println("    Body: ${body.take(500)}")

        assertEquals(HttpStatusCode.OK, response.status, "Initialize should succeed")

        val responseJson = json.parseToJsonElement(body).jsonObject
        assertEquals("2.0", responseJson["jsonrpc"]?.jsonPrimitive?.content)
        assertEquals(1, responseJson["id"]?.jsonPrimitive?.int)
        assertNotNull(responseJson["result"], "Should have result object")

        val result = responseJson["result"]!!.jsonObject
        assertNotNull(result["protocolVersion"], "Should have protocol version")
        assertNotNull(result["serverInfo"], "Should have server info")

        val serverInfo = result["serverInfo"]!!.jsonObject
        println("    Server: ${serverInfo["name"]?.jsonPrimitive?.content} v${serverInfo["version"]?.jsonPrimitive?.content}")

        // Capture MCP session ID for subsequent requests
        jarvisSessionId = response.headers["mcp-session-id"]
        assertNotNull(jarvisSessionId, "Should receive Mcp-Session-Id header")
        println("    Session: $jarvisSessionId")

        println("    ✓ MCP initialize handshake successful via Streamable HTTP")
    }

    @Test
    @Order(2)
    fun `MCP tools list via Streamable HTTP`() = runBlocking {
        println("\n┌─────────────────────────────────────────────────────────────┐")
        println("│ TEST: MCP Tools List via Streamable HTTP                  │")
        println("└─────────────────────────────────────────────────────────────┘")

        val response = client.post("${TestConfig.gatewayUrl}/mcp") {
            header("Authorization", "Bearer $jarvisToken")
            jarvisSessionId?.let { header("Mcp-Session-Id", it) }
            contentType(ContentType.Application.Json)
            setBody(buildJsonRpc(2, "tools/list"))
        }

        println("    Status: ${response.status}")
        val body = response.bodyAsText()
        println("    Body: ${body.take(500)}")

        assertEquals(HttpStatusCode.OK, response.status, "tools/list should succeed")

        val responseJson = json.parseToJsonElement(body).jsonObject
        assertEquals("2.0", responseJson["jsonrpc"]?.jsonPrimitive?.content)
        assertNotNull(responseJson["result"], "Should have result")

        val result = responseJson["result"]!!.jsonObject
        val tools = result["tools"]?.jsonArray
        assertNotNull(tools, "Should have tools array")

        val toolNames = tools!!.map { it.jsonObject["name"]?.jsonPrimitive?.content ?: "" }
        println("    Available tools: $toolNames")

        // Tool names are namespaced as <service>.<tool> by the aggregator
        assertTrue(toolNames.contains("mock-calendar.list_events"), "Should contain mock-calendar.list_events tool")
        assertTrue(toolNames.contains("mock-calendar.create_event"), "Should contain mock-calendar.create_event tool")

        println("    ✓ Tools list retrieved via Streamable HTTP")
    }

    @Test
    @Order(3)
    fun `tool call list_events succeeds for granted user`() = runBlocking {
        println("\n┌─────────────────────────────────────────────────────────────┐")
        println("│ TEST: tool call list_events succeeds (jarvis)             │")
        println("└─────────────────────────────────────────────────────────────┘")

        val response = client.post("${TestConfig.gatewayUrl}/mcp") {
            header("Authorization", "Bearer $jarvisToken")
            jarvisSessionId?.let { header("Mcp-Session-Id", it) }
            contentType(ContentType.Application.Json)
            setBody(buildJsonRpc(3, "tools/call", """{"name":"mock-calendar.list_events","arguments":{"date":"2026-02-14"}}"""))
        }

        println("    Status: ${response.status}")
        val body = response.bodyAsText()
        println("    Body: ${body.take(500)}")

        assertEquals(HttpStatusCode.OK, response.status, "Tool call should succeed for granted user")

        val responseJson = json.parseToJsonElement(body).jsonObject
        assertEquals("2.0", responseJson["jsonrpc"]?.jsonPrimitive?.content)

        val result = responseJson["result"]?.jsonObject
        assertNotNull(result, "Should have result object")

        val content = result!!["content"]?.jsonArray
        assertNotNull(content, "Should have content array")
        assertTrue(content!!.isNotEmpty(), "Content should not be empty")

        println("    ✓ list_events tool call succeeded for jarvis")
    }

    @Test
    @Order(4)
    fun `tool call create_event succeeds for granted user`() = runBlocking {
        println("\n┌─────────────────────────────────────────────────────────────┐")
        println("│ TEST: tool call create_event succeeds (jarvis)            │")
        println("└─────────────────────────────────────────────────────────────┘")

        val params = """{"name":"mock-calendar.create_event","arguments":{"title":"Integration Test Meeting","date":"2026-02-14","time":"14:00","duration":30}}"""

        val response = client.post("${TestConfig.gatewayUrl}/mcp") {
            header("Authorization", "Bearer $jarvisToken")
            jarvisSessionId?.let { header("Mcp-Session-Id", it) }
            contentType(ContentType.Application.Json)
            setBody(buildJsonRpc(4, "tools/call", params))
        }

        println("    Status: ${response.status}")
        val body = response.bodyAsText()
        println("    Body: ${body.take(500)}")

        assertEquals(HttpStatusCode.OK, response.status, "Tool call should succeed for granted user")

        val responseJson = json.parseToJsonElement(body).jsonObject
        assertEquals("2.0", responseJson["jsonrpc"]?.jsonPrimitive?.content)

        val result = responseJson["result"]?.jsonObject
        assertNotNull(result, "Should have result object")

        val content = result!!["content"]?.jsonArray
        assertNotNull(content, "Should have content array")
        assertTrue(content!!.isNotEmpty(), "Content should not be empty")

        println("    ✓ create_event tool call succeeded for jarvis")
    }

    @Test
    @Order(5)
    fun `OPA denies tool call for user without access`() = runBlocking {
        println("\n┌─────────────────────────────────────────────────────────────┐")
        println("│ TEST: OPA denies tool call (alice - no grants)            │")
        println("└─────────────────────────────────────────────────────────────┘")

        val response = client.post("${TestConfig.gatewayUrl}/mcp") {
            header("Authorization", "Bearer $aliceToken")
            contentType(ContentType.Application.Json)
            setBody(buildJsonRpc(5, "tools/call", """{"name":"mock-calendar.list_events","arguments":{"date":"2026-02-14"}}"""))
        }

        println("    Status: ${response.status}")
        val body = response.bodyAsText()
        println("    Body: ${body.take(300)}")

        assertEquals(HttpStatusCode.Forbidden, response.status,
            "OPA should deny tool call for user without access grants")

        println("    ✓ OPA correctly denied tool call for alice (no grants)")
    }

    @Test
    @Order(6)
    fun `OPA allows initialize for user without tool access`() = runBlocking {
        println("\n┌─────────────────────────────────────────────────────────────┐")
        println("│ TEST: OPA allows initialize (alice - non-tool-call)       │")
        println("└─────────────────────────────────────────────────────────────┘")

        val response = client.post("${TestConfig.gatewayUrl}/mcp") {
            header("Authorization", "Bearer $aliceToken")
            contentType(ContentType.Application.Json)
            setBody(buildJsonRpc(6, "initialize", """{"protocolVersion":"2024-11-05","clientInfo":{"name":"test-client","version":"1.0.0"},"capabilities":{}}"""))
        }

        println("    Status: ${response.status}")
        val body = response.bodyAsText()
        println("    Body: ${body.take(300)}")

        assertEquals(HttpStatusCode.OK, response.status,
            "OPA should allow non-tool-call methods (initialize) regardless of tool grants")

        // Capture alice's session ID for later tests
        aliceSessionId = response.headers["mcp-session-id"]
        println("    Session: $aliceSessionId")

        println("    ✓ OPA correctly allowed initialize for alice")
    }

    @Test
    @Order(7)
    fun `unauthenticated request returns 401`() = runBlocking {
        println("\n┌─────────────────────────────────────────────────────────────┐")
        println("│ TEST: Unauthenticated request returns 401                 │")
        println("└─────────────────────────────────────────────────────────────┘")

        val response = client.post("${TestConfig.gatewayUrl}/mcp") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonRpc(7, "initialize", """{"protocolVersion":"2024-11-05","clientInfo":{"name":"test-client","version":"1.0.0"},"capabilities":{}}"""))
        }

        println("    Status: ${response.status}")

        assertEquals(HttpStatusCode.Unauthorized, response.status,
            "Envoy jwt_authn should reject unauthenticated requests")

        println("    ✓ Unauthenticated request correctly rejected with 401")
    }

    @Test
    @Order(8)
    fun `verify all services healthy`() = runBlocking {
        println("\n┌─────────────────────────────────────────────────────────────┐")
        println("│ TEST: Verify All Services Healthy                          │")
        println("└─────────────────────────────────────────────────────────────┘")

        // Envoy gateway health
        val gatewayHealth = client.get("${TestConfig.gatewayUrl}/health")
        println("    Envoy Gateway: ${gatewayHealth.status}")
        assertTrue(gatewayHealth.status.isSuccess(), "Envoy gateway should be healthy")
        val gatewayBody = json.parseToJsonElement(gatewayHealth.bodyAsText()).jsonObject
        assertEquals("healthy", gatewayBody["status"]?.jsonPrimitive?.content)
        assertEquals("envoy-mcp-gateway", gatewayBody["service"]?.jsonPrimitive?.content)

        // NPL Engine health
        val nplHealth = client.get("${TestConfig.nplUrl}/actuator/health")
        println("    NPL Engine: ${nplHealth.status}")
        assertTrue(nplHealth.status.isSuccess(), "NPL Engine should be healthy")

        // Keycloak health
        try {
            val keycloakHealth = client.get("http://localhost:9000/health")
            println("    Keycloak: ${keycloakHealth.status}")
            assertTrue(keycloakHealth.status.isSuccess(), "Keycloak should be healthy")
        } catch (e: Exception) {
            // Verify via OIDC config endpoint instead
            val tokenCheck = client.get("${TestConfig.keycloakUrl}/realms/mcpgateway/.well-known/openid-configuration")
            println("    Keycloak OIDC Config: ${tokenCheck.status}")
            assertTrue(tokenCheck.status.isSuccess(), "Keycloak OIDC should be accessible")
        }

        println("    ✓ All core services are healthy")
    }

    // ── Non-tool-call bypass ────────────────────────────────────────────────

    @Test
    @Order(9)
    fun `OPA allows tools list for user without grants`() = runBlocking {
        println("\n┌─────────────────────────────────────────────────────────────┐")
        println("│ TEST: OPA allows tools/list (alice - non-tool-call)        │")
        println("└─────────────────────────────────────────────────────────────┘")

        val response = client.post("${TestConfig.gatewayUrl}/mcp") {
            header("Authorization", "Bearer $aliceToken")
            contentType(ContentType.Application.Json)
            setBody(buildJsonRpc(9, "tools/list"))
        }

        println("    Status: ${response.status}")
        val body = response.bodyAsText()
        println("    Body: ${body.take(300)}")

        assertEquals(HttpStatusCode.OK, response.status,
            "OPA should allow non-tool-call methods (tools/list) regardless of tool grants")

        println("    ✓ OPA correctly allowed tools/list for alice (no grants)")
    }

    // ── Dynamic pipeline: NPL → SSE → bundle → OPA ─────────────────────────

    @Test
    @Order(10)
    fun `dynamic grant-revoke proves full pipeline`() = runBlocking {
        println("\n┌─────────────────────────────────────────────────────────────┐")
        println("│ TEST: Dynamic grant → allow → revoke → deny (full pipeline)│")
        println("└─────────────────────────────────────────────────────────────┘")

        // Phase 1: Grant alice list_events
        println("    Phase 1: Granting alice list_events on mock-calendar...")
        val grantResp = client.post(
            "${TestConfig.nplUrl}/npl/store/PolicyStore/$storeId/grantTool"
        ) {
            header("Authorization", "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody("""{"subjectId": "alice@acme.com", "serviceName": "mock-calendar", "toolName": "list_events"}""")
        }
        assertTrue(grantResp.status.isSuccess(), "grantTool should succeed")

        println("    Waiting ${BUNDLE_REBUILD_WAIT / 1000}s for bundle rebuild...")
        delay(BUNDLE_REBUILD_WAIT)

        // Phase 2: Alice tool call should now succeed
        println("    Phase 2: Alice calls list_events (should succeed)...")
        val allowedResp = client.post("${TestConfig.gatewayUrl}/mcp") {
            header("Authorization", "Bearer $aliceToken")
            aliceSessionId?.let { header("Mcp-Session-Id", it) }
            contentType(ContentType.Application.Json)
            setBody(buildJsonRpc(10, "tools/call", """{"name":"mock-calendar.list_events","arguments":{"date":"2026-02-14"}}"""))
        }
        println("    Alice tool call status: ${allowedResp.status}")
        assertEquals(HttpStatusCode.OK, allowedResp.status,
            "Alice should be allowed after grant (NPL→SSE→bundle→OPA pipeline)")
        println("    ✓ Alice tool call succeeded after dynamic grant")

        // Phase 3: Revoke alice
        println("    Phase 3: Revoking alice's access...")
        val revokeResp = client.post(
            "${TestConfig.nplUrl}/npl/store/PolicyStore/$storeId/revokeTool"
        ) {
            header("Authorization", "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody("""{"subjectId": "alice@acme.com", "serviceName": "mock-calendar", "toolName": "list_events"}""")
        }
        assertTrue(revokeResp.status.isSuccess(), "revokeTool should succeed")

        println("    Waiting ${BUNDLE_REBUILD_WAIT / 1000}s for bundle rebuild...")
        delay(BUNDLE_REBUILD_WAIT)

        // Phase 4: Alice tool call should be denied again
        println("    Phase 4: Alice calls list_events (should be denied)...")
        val deniedResp = client.post("${TestConfig.gatewayUrl}/mcp") {
            header("Authorization", "Bearer $aliceToken")
            aliceSessionId?.let { header("Mcp-Session-Id", it) }
            contentType(ContentType.Application.Json)
            setBody(buildJsonRpc(11, "tools/call", """{"name":"mock-calendar.list_events","arguments":{"date":"2026-02-14"}}"""))
        }
        println("    Alice tool call status: ${deniedResp.status}")
        assertEquals(HttpStatusCode.Forbidden, deniedResp.status,
            "Alice should be denied after revoke (NPL→SSE→bundle→OPA pipeline)")

        println("    ✓ Full pipeline proven: grant→allow→revoke→deny")
    }

    @Test
    @Order(11)
    fun `suspended service denies tool calls E2E`() = runBlocking {
        println("\n┌─────────────────────────────────────────────────────────────┐")
        println("│ TEST: Suspended service denies tool calls (E2E)            │")
        println("└─────────────────────────────────────────────────────────────┘")

        // Phase 1: Suspend mock-calendar
        println("    Phase 1: Suspending mock-calendar...")
        val suspendResp = client.post(
            "${TestConfig.nplUrl}/npl/store/PolicyStore/$storeId/suspendService"
        ) {
            header("Authorization", "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody("""{"serviceName": "mock-calendar"}""")
        }
        assertTrue(suspendResp.status.isSuccess(), "suspendService should succeed")

        println("    Waiting ${BUNDLE_REBUILD_WAIT / 1000}s for bundle rebuild...")
        delay(BUNDLE_REBUILD_WAIT)

        // Phase 2: Jarvis tool call should be denied (service suspended)
        println("    Phase 2: Jarvis calls list_events (should be denied)...")
        val deniedResp = client.post("${TestConfig.gatewayUrl}/mcp") {
            header("Authorization", "Bearer $jarvisToken")
            jarvisSessionId?.let { header("Mcp-Session-Id", it) }
            contentType(ContentType.Application.Json)
            setBody(buildJsonRpc(12, "tools/call", """{"name":"mock-calendar.list_events","arguments":{"date":"2026-02-14"}}"""))
        }
        println("    Jarvis tool call status: ${deniedResp.status}")
        assertEquals(HttpStatusCode.Forbidden, deniedResp.status,
            "OPA should deny tool call when service is suspended")
        println("    ✓ Suspended service correctly blocked tool call")

        // Phase 3: Resume mock-calendar (cleanup)
        println("    Phase 3: Resuming mock-calendar...")
        val resumeResp = client.post(
            "${TestConfig.nplUrl}/npl/store/PolicyStore/$storeId/resumeService"
        ) {
            header("Authorization", "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody("""{"serviceName": "mock-calendar"}""")
        }
        assertTrue(resumeResp.status.isSuccess(), "resumeService should succeed")

        println("    Waiting ${BUNDLE_REBUILD_WAIT / 1000}s for bundle rebuild...")
        delay(BUNDLE_REBUILD_WAIT)

        // Phase 4: Verify jarvis can call again
        println("    Phase 4: Jarvis calls list_events (should succeed again)...")
        val allowedResp = client.post("${TestConfig.gatewayUrl}/mcp") {
            header("Authorization", "Bearer $jarvisToken")
            jarvisSessionId?.let { header("Mcp-Session-Id", it) }
            contentType(ContentType.Application.Json)
            setBody(buildJsonRpc(13, "tools/call", """{"name":"mock-calendar.list_events","arguments":{"date":"2026-02-14"}}"""))
        }
        println("    Jarvis tool call status: ${allowedResp.status}")
        assertEquals(HttpStatusCode.OK, allowedResp.status,
            "Jarvis should be allowed after service resume")

        println("    ✓ Suspend→deny→resume→allow cycle proven E2E")
    }

    @AfterAll
    fun teardown() {
        println("\n╔════════════════════════════════════════════════════════════════╗")
        println("║ END-TO-END TESTS (Envoy + OPA) - Complete                     ║")
        println("╚════════════════════════════════════════════════════════════════╝")
        if (::client.isInitialized) {
            client.close()
        }
    }
}
