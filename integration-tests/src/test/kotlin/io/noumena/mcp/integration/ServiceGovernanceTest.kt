package io.noumena.mcp.integration

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*

/**
 * Integration tests for ServiceGovernance — per-service governance protocol (v4).
 *
 * Tests the gated tool workflow directly via NPL REST API:
 * - Create instance with serviceName parameter
 * - Register tools, evaluate() for gated → pending
 * - approve() then re-evaluate() → allow (consumed)
 * - deny() then re-evaluate() → deny (consumed)
 * - Retry detection (same caller+tool+args → same pending request)
 *
 * No gateway, OPA, or MCP transport needed — we call evaluate() with the
 * gateway token the same way OPA calls it at runtime.
 *
 * Run with: ./gradlew :integration-tests:test --tests "*ServiceGovernanceTest*"
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class ServiceGovernanceTest {

    private lateinit var adminToken: String
    private lateinit var gatewayToken: String
    private lateinit var client: HttpClient
    private lateinit var governanceId: String

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    @BeforeAll
    fun setup() = runBlocking {
        println("╔════════════════════════════════════════════════════════════════╗")
        println("║ SERVICE GOVERNANCE INTEGRATION TESTS (v4)                     ║")
        println("╠════════════════════════════════════════════════════════════════╣")
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

        adminToken = KeycloakAuth.getToken("admin", "Welcome123")
        println("    ✓ Admin token obtained (pAdmin)")
        gatewayToken = KeycloakAuth.getToken("gateway", "Welcome123")
        println("    ✓ Gateway token obtained (pGateway)")

        governanceId = NplBootstrap.ensureServiceGovernance("test-service", adminToken)
        println("    ✓ ServiceGovernance instance: $governanceId (serviceName=test-service)")
    }

    // ── Tool registration ─────────────────────────────────────────────────

    @Test
    @Order(1)
    fun `register tools with tags`() = runBlocking {
        callAdmin("registerTool", """{"toolName": "read_data", "tag": "open"}""")
        callAdmin("registerTool", """{"toolName": "write_data", "tag": "gated"}""")
        println("    ✓ Registered tools: read_data=open, write_data=gated")
    }

    @Test
    @Order(2)
    fun `set tag changes tool governance`() = runBlocking {
        callAdmin("setTag", """{"toolName": "read_data", "tag": "gated"}""")
        println("    ✓ read_data tag changed to gated")

        // Change back
        callAdmin("setTag", """{"toolName": "read_data", "tag": "open"}""")
        println("    ✓ read_data tag restored to open")
    }

    // ── Evaluate: gated → pending ─────────────────────────────────────────

    @Test
    @Order(3)
    fun `evaluate gated tool returns pending`() = runBlocking {
        val result = callEvaluate(
            toolName = "write_data",
            callerIdentity = "alice@acme.com",
            arguments = """{"key":"val1"}"""
        )

        assertEquals("pending", result["decision"]?.jsonPrimitive?.content)
        val requestId = result["requestId"]?.jsonPrimitive?.content
        assertNotNull(requestId)
        assertTrue(requestId!!.startsWith("REQ-"))
        assertEquals("Awaiting approval", result["message"]?.jsonPrimitive?.content)
        println("    ✓ evaluate → pending (requestId=$requestId)")
    }

    // ── Retry detection ───────────────────────────────────────────────────

    @Test
    @Order(4)
    fun `retry returns same pending request`() = runBlocking {
        // Same caller + tool + arguments → same request
        val result1 = callEvaluate(
            toolName = "write_data",
            callerIdentity = "alice@acme.com",
            arguments = """{"key":"val1"}"""
        )
        val result2 = callEvaluate(
            toolName = "write_data",
            callerIdentity = "alice@acme.com",
            arguments = """{"key":"val1"}"""
        )

        assertEquals("pending", result1["decision"]?.jsonPrimitive?.content)
        assertEquals("pending", result2["decision"]?.jsonPrimitive?.content)
        assertEquals(
            result1["requestId"]?.jsonPrimitive?.content,
            result2["requestId"]?.jsonPrimitive?.content,
            "Same caller+tool+args should return same pending request"
        )
        println("    ✓ Retry detection: same requestId returned")
    }

    @Test
    @Order(5)
    fun `different arguments creates new request`() = runBlocking {
        val result = callEvaluate(
            toolName = "write_data",
            callerIdentity = "alice@acme.com",
            arguments = """{"key":"val2"}"""
        )

        assertEquals("pending", result["decision"]?.jsonPrimitive?.content)
        println("    ✓ Different arguments → new pending request (requestId=${result["requestId"]?.jsonPrimitive?.content})")
    }

    // ── getPendingRequests ────────────────────────────────────────────────

    @Test
    @Order(6)
    fun `getPendingRequests returns pending items`() = runBlocking {
        val response = client.post(
            "${TestConfig.nplUrl}/npl/governance/ServiceGovernance/$governanceId/getPendingRequests"
        ) {
            header("Authorization", "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertTrue(response.status.isSuccess())

        val pending = json.parseToJsonElement(response.bodyAsText()).jsonArray
        assertTrue(pending.size >= 2, "Should have at least 2 pending requests")

        val requestIds = pending.map { it.jsonObject["requestId"]?.jsonPrimitive?.content }
        println("    ✓ getPendingRequests: ${pending.size} pending (IDs: $requestIds)")
    }

    // ── Approve → re-evaluate → allow (consumed) ─────────────────────────

    @Test
    @Order(7)
    fun `approve then re-evaluate returns allow and consumes`() = runBlocking {
        // Get the request ID for alice's val1 request
        val evalResult = callEvaluate(
            toolName = "write_data",
            callerIdentity = "alice@acme.com",
            arguments = """{"key":"val1"}"""
        )
        val requestId = evalResult["requestId"]!!.jsonPrimitive.content
        assertEquals("pending", evalResult["decision"]?.jsonPrimitive?.content)

        // Approve it
        callAdmin("approve", """{"requestId": "$requestId"}""")
        println("    ✓ Approved $requestId")

        // Re-evaluate → should now return "allow" and consume the approval
        val afterApproval = callEvaluate(
            toolName = "write_data",
            callerIdentity = "alice@acme.com",
            arguments = """{"key":"val1"}"""
        )
        assertEquals("allow", afterApproval["decision"]?.jsonPrimitive?.content,
            "After approval, evaluate should return 'allow'")
        assertEquals(requestId, afterApproval["requestId"]?.jsonPrimitive?.content)
        println("    ✓ Re-evaluate → allow (consumed)")

        // Third evaluate → new pending (approval was consumed)
        val afterConsume = callEvaluate(
            toolName = "write_data",
            callerIdentity = "alice@acme.com",
            arguments = """{"key":"val1"}"""
        )
        assertEquals("pending", afterConsume["decision"]?.jsonPrimitive?.content,
            "After consumption, evaluate should create new pending")
        assertNotEquals(requestId, afterConsume["requestId"]?.jsonPrimitive?.content,
            "New request should have a new ID")
        println("    ✓ After consumption → new pending (requestId=${afterConsume["requestId"]?.jsonPrimitive?.content})")
    }

    // ── Deny → re-evaluate → deny (consumed) ─────────────────────────────

    @Test
    @Order(8)
    fun `deny then re-evaluate returns deny and consumes`() = runBlocking {
        // Get the request ID for alice's val2 request
        val evalResult = callEvaluate(
            toolName = "write_data",
            callerIdentity = "alice@acme.com",
            arguments = """{"key":"val2"}"""
        )
        val requestId = evalResult["requestId"]!!.jsonPrimitive.content
        assertEquals("pending", evalResult["decision"]?.jsonPrimitive?.content)

        // Deny it
        callAdmin("deny", """{"requestId": "$requestId", "reason": "Not authorized for this operation"}""")
        println("    ✓ Denied $requestId")

        // Re-evaluate → should now return "deny" and consume the denial
        val afterDenial = callEvaluate(
            toolName = "write_data",
            callerIdentity = "alice@acme.com",
            arguments = """{"key":"val2"}"""
        )
        assertEquals("deny", afterDenial["decision"]?.jsonPrimitive?.content,
            "After denial, evaluate should return 'deny'")
        assertEquals(requestId, afterDenial["requestId"]?.jsonPrimitive?.content)
        assertTrue(afterDenial["message"]?.jsonPrimitive?.content?.contains("Not authorized") == true)
        println("    ✓ Re-evaluate → deny (consumed)")

        // Third evaluate → new pending (denial was consumed)
        val afterConsume = callEvaluate(
            toolName = "write_data",
            callerIdentity = "alice@acme.com",
            arguments = """{"key":"val2"}"""
        )
        assertEquals("pending", afterConsume["decision"]?.jsonPrimitive?.content,
            "After consumption, evaluate should create new pending")
        println("    ✓ After consumption → new pending")
    }

    // ── Different caller isolation ────────────────────────────────────────

    @Test
    @Order(9)
    fun `different callers have separate requests`() = runBlocking {
        val aliceResult = callEvaluate(
            toolName = "write_data",
            callerIdentity = "alice@acme.com",
            arguments = """{"key":"shared"}"""
        )
        val bobResult = callEvaluate(
            toolName = "write_data",
            callerIdentity = "bob@acme.com",
            arguments = """{"key":"shared"}"""
        )

        assertEquals("pending", aliceResult["decision"]?.jsonPrimitive?.content)
        assertEquals("pending", bobResult["decision"]?.jsonPrimitive?.content)
        assertNotEquals(
            aliceResult["requestId"]?.jsonPrimitive?.content,
            bobResult["requestId"]?.jsonPrimitive?.content,
            "Different callers should have separate requests even with same args"
        )
        println("    ✓ Different callers → separate pending requests")
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private suspend fun callEvaluate(
        toolName: String,
        callerIdentity: String,
        arguments: String = "{}",
        sessionId: String = "",
        requestPayload: String = ""
    ): JsonObject {
        val body = """
        {
            "toolName": "$toolName",
            "callerIdentity": "$callerIdentity",
            "callerClaims": {},
            "arguments": ${json.encodeToString(JsonElement.serializer(), JsonPrimitive(arguments))},
            "sessionId": "$sessionId",
            "requestPayload": ${json.encodeToString(JsonElement.serializer(), JsonPrimitive(requestPayload))}
        }
        """.trimIndent()

        val response = client.post(
            "${TestConfig.nplUrl}/npl/governance/ServiceGovernance/$governanceId/evaluate"
        ) {
            header("Authorization", "Bearer $gatewayToken")
            contentType(ContentType.Application.Json)
            setBody(body)
        }

        assertTrue(response.status.isSuccess(),
            "evaluate() should succeed: ${response.status} - ${response.bodyAsText()}")
        return json.parseToJsonElement(response.bodyAsText()).jsonObject
    }

    private suspend fun callAdmin(action: String, body: String) {
        val response = client.post(
            "${TestConfig.nplUrl}/npl/governance/ServiceGovernance/$governanceId/$action"
        ) {
            header("Authorization", "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        assertTrue(response.status.isSuccess(),
            "ServiceGovernance.$action should succeed: ${response.status} - ${response.bodyAsText()}")
    }

    @AfterAll
    fun teardown() {
        println("\n╔════════════════════════════════════════════════════════════════╗")
        println("║ SERVICE GOVERNANCE TESTS (v4) - Complete                      ║")
        println("╚════════════════════════════════════════════════════════════════╝")
        if (::client.isInitialized) {
            client.close()
        }
    }
}
