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
 * NPL state (ServiceRegistry, ToolPolicy, UserToolAccess) is bootstrapped in @BeforeAll
 * so that OPA can evaluate access policies. OPA caches NPL data for 5s, so a 6s sleep
 * is required after bootstrap before running policy-dependent tests.
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
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
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

        // Bootstrap NPL state for OPA policy evaluation
        println("\n    Bootstrapping NPL state for OPA...")

        // 1. ServiceRegistry → enable mock-calendar
        val registryId = NplBootstrap.ensureServiceRegistry(adminToken)
        println("    ✓ ServiceRegistry: $registryId")
        NplBootstrap.ensureServiceEnabled(registryId, "mock-calendar", adminToken)
        println("    ✓ mock-calendar service enabled")

        // 2. ToolPolicy for mock-calendar → enable list_events + create_event
        val policyId = NplBootstrap.ensureToolPolicy("mock-calendar", adminToken)
        println("    ✓ ToolPolicy (mock-calendar): $policyId")
        NplBootstrap.ensureToolEnabled(policyId, "list_events", adminToken)
        NplBootstrap.ensureToolEnabled(policyId, "create_event", adminToken)
        println("    ✓ Tools enabled: list_events, create_event")

        // 3. UserToolAccess for jarvis → grant wildcard on mock-calendar
        val jarvisAccessId = NplBootstrap.ensureUserToolAccess("jarvis@acme.com", adminToken)
        println("    ✓ UserToolAccess (jarvis): $jarvisAccessId")
        NplBootstrap.ensureUserToolGrantAll(jarvisAccessId, "mock-calendar", adminToken)
        println("    ✓ jarvis granted wildcard (*) on mock-calendar")

        // 4. UserToolAccess for alice → no grants (denied user)
        val aliceAccessId = NplBootstrap.ensureUserToolAccess("alice@acme.com", adminToken)
        println("    ✓ UserToolAccess (alice): $aliceAccessId (no grants)")

        // 5. Wait for OPA cache to expire and pick up new NPL state
        println("    Waiting 6s for OPA cache expiry...")
        delay(6000)
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

        assertTrue(toolNames.contains("list_events"), "Should contain list_events tool")
        assertTrue(toolNames.contains("create_event"), "Should contain create_event tool")

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
            contentType(ContentType.Application.Json)
            setBody(buildJsonRpc(3, "tools/call", """{"name":"list_events","arguments":{"date":"2026-02-14"}}"""))
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

        val params = """{"name":"create_event","arguments":{"title":"Integration Test Meeting","date":"2026-02-14","time":"14:00","duration":30}}"""

        val response = client.post("${TestConfig.gatewayUrl}/mcp") {
            header("Authorization", "Bearer $jarvisToken")
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
            setBody(buildJsonRpc(5, "tools/call", """{"name":"list_events","arguments":{"date":"2026-02-14"}}"""))
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
