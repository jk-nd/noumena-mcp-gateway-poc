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
 * E2E Integration Tests for Store-and-Forward Approval.
 *
 * Tests the full flow: Envoy -> OPA -> NPL ApprovalPolicy -> approve -> verify execution state.
 *
 * Security policy marks create_event as "npl_evaluate" while list_events (readOnly)
 * gets "allow". The test verifies:
 * - Read-only tools bypass approval
 * - Mutating tools get 403 + x-approval-id header
 * - Pending approval has stored request payload
 * - Approve -> queued for execution
 * - recordExecution() simulates replay worker
 * - getExecutionResult() returns complete record
 * - Deny flow works end-to-end
 *
 * Prerequisites:
 * - Full Docker stack running: docker compose -f deployments/docker-compose.yml up -d
 * - All services healthy: Envoy, OPA, NPL Engine, Keycloak, mock-calendar-mcp
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
    private lateinit var approvalPolicyId: String
    private lateinit var capturedApprovalId: String
    private var mcpSessionId: String? = null

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
        println("║ STORE-AND-FORWARD APPROVAL TESTS (E2E)                       ║")
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

        // Check Docker stack + gateway health
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
        println("    ✓ Jarvis token obtained")

        // Bootstrap NPL state
        println("\n    Bootstrapping NPL state...")

        // 1. PolicyStore singleton
        storeId = NplBootstrap.ensurePolicyStore(adminToken)
        println("    ✓ PolicyStore: $storeId")

        // 2. Register + enable mock-calendar service + tools
        NplBootstrap.ensureCatalogService(storeId, "mock-calendar", adminToken)
        println("    ✓ mock-calendar service registered + enabled")
        NplBootstrap.ensureCatalogToolEnabled(storeId, "mock-calendar", "list_events", adminToken)
        NplBootstrap.ensureCatalogToolEnabled(storeId, "mock-calendar", "create_event", adminToken)
        println("    ✓ Tools enabled: list_events, create_event")

        // 3. Grant jarvis wildcard access
        NplBootstrap.ensureGrantAll(storeId, "jarvis@acme.com", "mock-calendar", adminToken)
        println("    ✓ jarvis granted wildcard (*) on mock-calendar")

        // 4. Find-or-create ApprovalPolicy singleton
        approvalPolicyId = ensureApprovalPolicy()
        println("    ✓ ApprovalPolicy: $approvalPolicyId")

        // 5. Set security policy on PolicyStore
        setSecurityPolicy()
        println("    ✓ Security policy set (create_event=npl_evaluate, list_events=allow)")

        // 6. Register contextual route: mock-calendar.* -> ApprovalPolicy/evaluate
        registerApprovalRoute()
        println("    ✓ Contextual route: mock-calendar.* → ApprovalPolicy/$approvalPolicyId/evaluate")

        // 7. Wait for SSE bundle rebuild
        println("    Waiting ${BUNDLE_REBUILD_WAIT / 1000}s for OPA bundle rebuild via SSE...")
        delay(BUNDLE_REBUILD_WAIT)
        println("    ✓ Bootstrap complete, OPA should have fresh state")

        // 8. Initialize MCP session (required by Streamable HTTP transport)
        val initResp = client.post("${TestConfig.gatewayUrl}/mcp") {
            header("Authorization", "Bearer $jarvisToken")
            contentType(ContentType.Application.Json)
            setBody(buildJsonRpc(0, "initialize", """{"protocolVersion":"2024-11-05","clientInfo":{"name":"store-forward-test","version":"1.0.0"},"capabilities":{}}"""))
        }
        println("    MCP initialize: ${initResp.status}")
        Assumptions.assumeTrue(initResp.status.isSuccess()) { "MCP initialize failed: ${initResp.status}" }
        mcpSessionId = initResp.headers["mcp-session-id"]
        println("    ✓ MCP session initialized (session=$mcpSessionId)")
    }

    // ── Test 1: Read-only bypass ────────────────────────────────────────────

    @Test
    @Order(1)
    fun `list_events still allowed (read-only bypass)`() = runBlocking {
        println("\n┌─────────────────────────────────────────────────────────────┐")
        println("│ TEST 1: list_events still allowed (read-only bypass)       │")
        println("└─────────────────────────────────────────────────────────────┘")

        val response = mcpPost(1, "tools/call", """{"name":"mock-calendar.list_events","arguments":{"date":"2026-02-14"}}""")

        println("    Status: ${response.status}")
        val body = response.bodyAsText()
        println("    Body: ${body.take(500)}")

        assertEquals(HttpStatusCode.OK, response.status,
            "Security policy should allow read-only tools through without approval")

        val responseJson = json.parseToJsonElement(body).jsonObject
        val result = responseJson["result"]?.jsonObject
        assertNotNull(result, "Should have result object")
        val content = result!!["content"]?.jsonArray
        assertNotNull(content, "Should have content array")

        println("    ✓ list_events allowed through (read-only bypass)")
    }

    // ── Test 2: create_event triggers 403 + approval ────────────────────────

    @Test
    @Order(2)
    fun `create_event returns 403 with x-approval-id`() = runBlocking {
        println("\n┌─────────────────────────────────────────────────────────────┐")
        println("│ TEST 2: create_event returns 403 with x-approval-id       │")
        println("└─────────────────────────────────────────────────────────────┘")

        val params = """{"name":"mock-calendar.create_event","arguments":{"title":"Approval Test Meeting","date":"2026-02-15","time":"10:00","duration":30}}"""

        val response = mcpPost(2, "tools/call", params)

        println("    Status: ${response.status}")
        val body = response.bodyAsText()
        println("    Body: ${body.take(500)}")
        println("    Response headers:")
        response.headers.forEach { name, values -> println("      $name: ${values.joinToString(", ")}") }

        assertEquals(HttpStatusCode.Forbidden, response.status,
            "create_event should return 403 (npl_evaluate)")

        val approvalId = response.headers["x-approval-id"]
        assertNotNull(approvalId, "Response should include x-approval-id header")
        assertTrue(approvalId!!.startsWith("APR-"), "Approval ID should start with APR-")
        capturedApprovalId = approvalId

        val spAction = response.headers["x-sp-action"]
        assertEquals("npl_evaluate", spAction, "x-sp-action should be npl_evaluate")

        println("    ✓ create_event returned 403 with x-approval-id=$capturedApprovalId")
    }

    // ── Test 3: verify stored request payload ───────────────────────────────

    @Test
    @Order(3)
    fun `verify pending approval has stored request payload`() = runBlocking {
        println("\n┌─────────────────────────────────────────────────────────────┐")
        println("│ TEST 3: verify pending approval has stored request payload │")
        println("└─────────────────────────────────────────────────────────────┘")

        Assumptions.assumeTrue(::capturedApprovalId.isInitialized, "No approval ID captured from test 2")

        val pendingList = getPendingApprovals()
        println("    Pending approvals: ${pendingList.size}")

        assertTrue(pendingList.isNotEmpty(), "Should have at least one pending approval")

        val approval = pendingList.first { it.jsonObject["approvalId"]?.jsonPrimitive?.content == capturedApprovalId }.jsonObject
        println("    Approval: ${json.encodeToString(JsonElement.serializer(), approval).take(500)}")

        assertEquals("pending", approval["status"]?.jsonPrimitive?.content,
            "Approval should be pending")
        assertEquals("jarvis@acme.com", approval["callerIdentity"]?.jsonPrimitive?.content,
            "Caller should be jarvis@acme.com")
        assertEquals("create_event", approval["toolName"]?.jsonPrimitive?.content,
            "Tool should be create_event")
        assertEquals("mock-calendar", approval["serviceName"]?.jsonPrimitive?.content,
            "Service should be mock-calendar")
        assertEquals("none", approval["executionStatus"]?.jsonPrimitive?.content,
            "Execution status should be none")

        val requestPayload = approval["requestPayload"]?.jsonPrimitive?.content ?: ""
        assertTrue(requestPayload.contains("create_event"),
            "Request payload should contain create_event")
        assertTrue(requestPayload.contains("tools/call"),
            "Request payload should contain tools/call")
        assertTrue(requestPayload.isNotEmpty(), "Request payload should not be empty")

        println("    ✓ Pending approval verified: status=pending, caller=jarvis@acme.com, tool=create_event")
        println("    ✓ Request payload stored (${requestPayload.length} chars)")
    }

    // ── Test 4: approve the pending request ─────────────────────────────────

    @Test
    @Order(4)
    fun `approve the pending request`() = runBlocking {
        println("\n┌─────────────────────────────────────────────────────────────┐")
        println("│ TEST 4: approve the pending request                        │")
        println("└─────────────────────────────────────────────────────────────┘")

        Assumptions.assumeTrue(::capturedApprovalId.isInitialized, "No approval ID captured from test 2")

        val response = client.post(
            "${TestConfig.nplUrl}/npl/policies/ApprovalPolicy/$approvalPolicyId/approve"
        ) {
            header("Authorization", "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody("""{"approvalId": "$capturedApprovalId", "approverIdentity": "admin@acme.com"}""")
        }

        println("    Status: ${response.status}")
        val body = response.bodyAsText()
        println("    Body: ${body.take(300)}")

        assertTrue(response.status.isSuccess(), "approve() should succeed")
        assertTrue(body.contains("approved"), "Response should contain 'approved'")

        println("    ✓ Approval $capturedApprovalId approved by admin@acme.com")
    }

    // ── Test 5: verify queued for execution ─────────────────────────────────

    @Test
    @Order(5)
    fun `verify approval is queued for execution`() = runBlocking {
        println("\n┌─────────────────────────────────────────────────────────────┐")
        println("│ TEST 5: verify approval is queued for execution            │")
        println("└─────────────────────────────────────────────────────────────┘")

        Assumptions.assumeTrue(::capturedApprovalId.isInitialized, "No approval ID captured from test 2")

        val queuedList = getQueuedForExecution()
        println("    Queued for execution: ${queuedList.size}")

        assertTrue(queuedList.isNotEmpty(), "Should have at least one queued approval")

        val approval = queuedList.first { it.jsonObject["approvalId"]?.jsonPrimitive?.content == capturedApprovalId }.jsonObject

        assertEquals("approved", approval["status"]?.jsonPrimitive?.content,
            "Status should be approved")
        assertEquals("queued", approval["executionStatus"]?.jsonPrimitive?.content,
            "Execution status should be queued")

        val requestPayload = approval["requestPayload"]?.jsonPrimitive?.content ?: ""
        assertTrue(requestPayload.isNotEmpty(), "Request payload should still be present")

        println("    ✓ Approval $capturedApprovalId is queued for execution with stored payload")
    }

    // ── Test 6: simulate replay with recordExecution ────────────────────────

    @Test
    @Order(6)
    fun `simulate replay with recordExecution`() = runBlocking {
        println("\n┌─────────────────────────────────────────────────────────────┐")
        println("│ TEST 6: simulate replay with recordExecution               │")
        println("└─────────────────────────────────────────────────────────────┘")

        Assumptions.assumeTrue(::capturedApprovalId.isInitialized, "No approval ID captured from test 2")

        val mockResult = """{"content":[{"type":"text","text":"Event created: Approval Test Meeting on 2026-02-15 at 10:00"}]}"""

        val response = client.post(
            "${TestConfig.nplUrl}/npl/policies/ApprovalPolicy/$approvalPolicyId/recordExecution"
        ) {
            header("Authorization", "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody("""{"approvalId": "$capturedApprovalId", "execStatus": "completed", "execResult": ${json.encodeToString(JsonElement.serializer(), JsonPrimitive(mockResult))}}""")
        }

        println("    Status: ${response.status}")
        val body = response.bodyAsText()
        println("    Body: ${body.take(300)}")

        assertTrue(response.status.isSuccess(), "recordExecution() should succeed")

        println("    ✓ Execution recorded for $capturedApprovalId: completed")
    }

    // ── Test 7: verify execution result ─────────────────────────────────────

    @Test
    @Order(7)
    fun `verify execution result via getExecutionResult`() = runBlocking {
        println("\n┌─────────────────────────────────────────────────────────────┐")
        println("│ TEST 7: verify execution result via getExecutionResult     │")
        println("└─────────────────────────────────────────────────────────────┘")

        Assumptions.assumeTrue(::capturedApprovalId.isInitialized, "No approval ID captured from test 2")

        val response = client.post(
            "${TestConfig.nplUrl}/npl/policies/ApprovalPolicy/$approvalPolicyId/getExecutionResult"
        ) {
            header("Authorization", "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody("""{"approvalId": "$capturedApprovalId"}""")
        }

        println("    Status: ${response.status}")
        val body = response.bodyAsText()
        println("    Body: ${body.take(500)}")

        assertTrue(response.status.isSuccess(), "getExecutionResult() should succeed")

        val result = json.parseToJsonElement(body).jsonObject
        assertEquals("completed", result["executionStatus"]?.jsonPrimitive?.content,
            "Execution status should be completed")

        val execResult = result["executionResult"]?.jsonPrimitive?.content ?: ""
        assertTrue(execResult.contains("Approval Test Meeting"),
            "Execution result should contain the mock result")
        assertEquals("approved", result["status"]?.jsonPrimitive?.content,
            "Approval status should still be approved")
        assertEquals("jarvis@acme.com", result["callerIdentity"]?.jsonPrimitive?.content,
            "Caller identity should be preserved")
        assertEquals("create_event", result["toolName"]?.jsonPrimitive?.content,
            "Tool name should be preserved")
        assertEquals("mock-calendar", result["serviceName"]?.jsonPrimitive?.content,
            "Service name should be preserved")

        println("    ✓ Execution result verified: completed with mock result")
    }

    // ── Test 8: deny flow ───────────────────────────────────────────────────

    @Test
    @Order(8)
    fun `deny flow - second create triggers new approval and deny it`() = runBlocking {
        println("\n┌─────────────────────────────────────────────────────────────┐")
        println("│ TEST 8: deny flow — new create → deny → verify             │")
        println("└─────────────────────────────────────────────────────────────┘")

        // Trigger a new approval with different arguments
        val params = """{"name":"mock-calendar.create_event","arguments":{"title":"Denied Meeting","date":"2026-03-01","time":"09:00","duration":60}}"""

        val triggerResp = mcpPost(8, "tools/call", params)

        println("    Trigger status: ${triggerResp.status}")
        assertEquals(HttpStatusCode.Forbidden, triggerResp.status,
            "create_event should return 403")

        val denyApprovalId = triggerResp.headers["x-approval-id"]
        assertNotNull(denyApprovalId, "Should have x-approval-id header")
        println("    New approval: $denyApprovalId")

        // Deny it
        val denyResp = client.post(
            "${TestConfig.nplUrl}/npl/policies/ApprovalPolicy/$approvalPolicyId/deny"
        ) {
            header("Authorization", "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody("""{"approvalId": "$denyApprovalId", "approverIdentity": "admin@acme.com", "reason": "Meeting not needed"}""")
        }

        println("    Deny status: ${denyResp.status}")
        assertTrue(denyResp.status.isSuccess(), "deny() should succeed")

        // Verify via getExecutionResult
        val resultResp = client.post(
            "${TestConfig.nplUrl}/npl/policies/ApprovalPolicy/$approvalPolicyId/getExecutionResult"
        ) {
            header("Authorization", "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody("""{"approvalId": "$denyApprovalId"}""")
        }

        assertTrue(resultResp.status.isSuccess(), "getExecutionResult() should succeed")
        val result = json.parseToJsonElement(resultResp.bodyAsText()).jsonObject

        assertEquals("denied", result["status"]?.jsonPrimitive?.content,
            "Status should be denied")
        assertEquals("none", result["executionStatus"]?.jsonPrimitive?.content,
            "Denied approvals should have executionStatus=none")
        assertEquals("Meeting not needed", result["reason"]?.jsonPrimitive?.content,
            "Reason should be preserved")

        println("    ✓ Deny flow verified: status=denied, executionStatus=none, reason preserved")
    }

    // ── Cleanup ─────────────────────────────────────────────────────────────

    @AfterAll
    fun teardown() = runBlocking {
        println("\n╔════════════════════════════════════════════════════════════════╗")
        println("║ STORE-AND-FORWARD TESTS - Cleanup                            ║")
        println("╠════════════════════════════════════════════════════════════════╣")

        if (::client.isInitialized && ::storeId.isInitialized && ::adminToken.isInitialized) {
            try {
                // Clear security policy
                client.post("${TestConfig.nplUrl}/npl/policy/PolicyStore/$storeId/clearSecurityPolicy") {
                    header("Authorization", "Bearer $adminToken")
                    contentType(ContentType.Application.Json)
                    setBody("{}")
                }
                println("║ ✓ Security policy cleared                                    ║")

                // Remove contextual route
                client.post("${TestConfig.nplUrl}/npl/policy/PolicyStore/$storeId/removeRoute") {
                    header("Authorization", "Bearer $adminToken")
                    contentType(ContentType.Application.Json)
                    setBody("""{"serviceName": "mock-calendar", "toolName": "*"}""")
                }
                println("║ ✓ Contextual route removed                                   ║")
            } catch (e: Exception) {
                println("║ ⚠ Cleanup error (PolicyStore): ${e.message?.take(40)}")
            }

            if (::approvalPolicyId.isInitialized) {
                try {
                    // Clear resolved approvals
                    client.post("${TestConfig.nplUrl}/npl/policies/ApprovalPolicy/$approvalPolicyId/clearResolved") {
                        header("Authorization", "Bearer $adminToken")
                        contentType(ContentType.Application.Json)
                        setBody("{}")
                    }
                    println("║ ✓ Resolved approvals cleared                                 ║")
                } catch (e: Exception) {
                    println("║ ⚠ Cleanup error (ApprovalPolicy): ${e.message?.take(40)}")
                }
            }

            // Wait for bundle rebuild so other tests aren't affected
            println("║ Waiting ${BUNDLE_REBUILD_WAIT / 1000}s for bundle rebuild...                          ║")
            delay(BUNDLE_REBUILD_WAIT)
        }

        println("╚════════════════════════════════════════════════════════════════╝")
        if (::client.isInitialized) {
            client.close()
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    /** Send a JSON-RPC request to the gateway with MCP session header. */
    private suspend fun mcpPost(id: Int, method: String, params: String = "{}"): HttpResponse {
        return client.post("${TestConfig.gatewayUrl}/mcp") {
            header("Authorization", "Bearer $jarvisToken")
            mcpSessionId?.let { header("Mcp-Session-Id", it) }
            contentType(ContentType.Application.Json)
            setBody(buildJsonRpc(id, method, params))
        }
    }

    /** Find or create the ApprovalPolicy singleton. */
    private suspend fun ensureApprovalPolicy(): String {
        val listResp = client.get("${TestConfig.nplUrl}/npl/policies/ApprovalPolicy/") {
            header("Authorization", "Bearer $adminToken")
        }
        if (listResp.status.isSuccess()) {
            val items = json.parseToJsonElement(listResp.bodyAsText()).jsonObject["items"]?.jsonArray
            if (items != null && items.isNotEmpty()) {
                return items[0].jsonObject["@id"]!!.jsonPrimitive.content
            }
        }
        val createResp = client.post("${TestConfig.nplUrl}/npl/policies/ApprovalPolicy/") {
            header("Authorization", "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody("""{"@parties": {}}""")
        }
        return json.parseToJsonElement(createResp.bodyAsText()).jsonObject["@id"]!!.jsonPrimitive.content
    }

    /** Set the security policy on PolicyStore. */
    private suspend fun setSecurityPolicy() {
        val securityPolicyJson = """
        {
            "version": "1.0",
            "tool_annotations": {
                "mock-calendar": {
                    "create_event": {
                        "annotations": {"readOnlyHint": false, "destructiveHint": false, "openWorldHint": false},
                        "verb": "create",
                        "labels": ["category:scheduling"]
                    },
                    "list_events": {
                        "annotations": {"readOnlyHint": true},
                        "verb": "list",
                        "labels": ["category:scheduling"]
                    }
                }
            },
            "classifiers": {},
            "policies": [
                {"name": "Approve creates", "when": {"verb": "create"}, "action": "npl_evaluate", "priority": 20, "approvers": []},
                {"name": "Default allow", "when": {}, "action": "allow", "priority": 999}
            ]
        }
        """.trimIndent()

        // Serialize the JSON as a string value for NPL Text field
        val escapedPolicy = json.encodeToString(JsonElement.serializer(), JsonPrimitive(securityPolicyJson))

        val response = client.post(
            "${TestConfig.nplUrl}/npl/policy/PolicyStore/$storeId/setSecurityPolicy"
        ) {
            header("Authorization", "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody("""{"policyJson": $escapedPolicy}""")
        }
        assertTrue(response.status.isSuccess(), "setSecurityPolicy should succeed: ${response.status}")
    }

    /** Register contextual route: mock-calendar.* -> ApprovalPolicy/evaluate. */
    private suspend fun registerApprovalRoute() {
        val response = client.post(
            "${TestConfig.nplUrl}/npl/policy/PolicyStore/$storeId/registerRoute"
        ) {
            header("Authorization", "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody("""{"serviceName": "mock-calendar", "toolName": "*", "routeProtocol": "ApprovalPolicy", "instanceId": "$approvalPolicyId", "endpoint": "/npl/policies/ApprovalPolicy/$approvalPolicyId/evaluate"}""")
        }
        assertTrue(response.status.isSuccess(), "registerRoute should succeed: ${response.status}")
    }

    /** Get pending approvals from ApprovalPolicy. */
    private suspend fun getPendingApprovals(): JsonArray {
        val response = client.post(
            "${TestConfig.nplUrl}/npl/policies/ApprovalPolicy/$approvalPolicyId/getPendingApprovals"
        ) {
            header("Authorization", "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertTrue(response.status.isSuccess(), "getPendingApprovals should succeed")
        val body = response.bodyAsText()
        return json.parseToJsonElement(body).jsonArray
    }

    /** Get approvals queued for execution from ApprovalPolicy. */
    private suspend fun getQueuedForExecution(): JsonArray {
        val response = client.post(
            "${TestConfig.nplUrl}/npl/policies/ApprovalPolicy/$approvalPolicyId/getQueuedForExecution"
        ) {
            header("Authorization", "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertTrue(response.status.isSuccess(), "getQueuedForExecution should succeed")
        val body = response.bodyAsText()
        return json.parseToJsonElement(body).jsonArray
    }
}
