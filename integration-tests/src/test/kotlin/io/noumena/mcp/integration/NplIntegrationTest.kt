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
 * Integration tests for the NPL Engine — GatewayStore singleton (v4).
 *
 * GatewayStore holds:
 *   - Catalog: services + tools with open/gated tags
 *   - Access rules: claim-based and identity-based
 *   - Emergency revocation
 *
 * Run with: ./gradlew :integration-tests:test --tests "*NplIntegrationTest*"
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class NplIntegrationTest {

    private lateinit var adminToken: String
    private lateinit var gatewayToken: String
    private lateinit var client: HttpClient
    private lateinit var storeId: String

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    @BeforeAll
    fun setup() = runBlocking {
        println("╔════════════════════════════════════════════════════════════════╗")
        println("║ NPL INTEGRATION TESTS (GatewayStore v4) - Setup              ║")
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

        println("    Getting tokens for admin and gateway...")
        adminToken = KeycloakAuth.getToken("admin", "Welcome123")
        println("    ✓ Admin token obtained (pAdmin)")
        gatewayToken = KeycloakAuth.getToken("gateway", "Welcome123")
        println("    ✓ Gateway token obtained (pGateway)")
    }

    // ── Health + Setup ──────────────────────────────────────────────────────

    @Test
    @Order(1)
    fun `NPL engine health check`() = runBlocking {
        val response = client.get("${TestConfig.nplUrl}/actuator/health")
        assertTrue(response.status.isSuccess(), "NPL Engine should be healthy")
        println("    ✓ NPL Engine is healthy")
    }

    @Test
    @Order(2)
    fun `create GatewayStore singleton`() = runBlocking {
        storeId = NplBootstrap.ensureGatewayStore(adminToken)
        println("    ✓ GatewayStore singleton: $storeId")
        assertNotNull(storeId)
        assertTrue(storeId.isNotBlank(), "Store ID should not be blank")
    }

    // ── Service Registration with Tags ──────────────────────────────────────

    @Test
    @Order(3)
    fun `register and enable service with tools`() = runBlocking {
        Assumptions.assumeTrue(::storeId.isInitialized, "GatewayStore not created")

        NplBootstrap.registerServiceWithTools(
            storeId, "mock-calendar",
            mapOf("list_events" to "open", "create_event" to "gated"),
            adminToken
        )
        println("    ✓ mock-calendar registered with tools")

        val bundleData = getBundleData()
        val catalog = bundleData["catalog"]?.jsonObject
        assertNotNull(catalog, "Should have catalog")

        val mockCal = catalog!!["mock-calendar"]?.jsonObject
        assertNotNull(mockCal, "Should have mock-calendar entry")
        assertEquals(true, mockCal!!["enabled"]?.jsonPrimitive?.boolean, "Should be enabled")

        val tools = mockCal["tools"]?.jsonObject
        assertNotNull(tools, "Should have tools")
        assertEquals("open", tools!!["list_events"]?.jsonObject?.get("tag")?.jsonPrimitive?.content)
        assertEquals("gated", tools["create_event"]?.jsonObject?.get("tag")?.jsonPrimitive?.content)

        println("    ✓ getBundleData confirms: list_events=open, create_event=gated")
    }

    // ── Tool CRUD with Tags ─────────────────────────────────────────────────

    @Test
    @Order(4)
    fun `tag switching open to gated`() = runBlocking {
        Assumptions.assumeTrue(::storeId.isInitialized, "GatewayStore not created")

        // Switch list_events from open to gated
        val resp = client.post(
            "${TestConfig.nplUrl}/npl/store/GatewayStore/$storeId/setTag"
        ) {
            header("Authorization", "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody("""{"serviceName": "mock-calendar", "toolName": "list_events", "tag": "gated"}""")
        }
        assertTrue(resp.status.isSuccess(), "setTag should succeed")

        val bundleData = getBundleData()
        val tag = bundleData["catalog"]?.jsonObject
            ?.get("mock-calendar")?.jsonObject
            ?.get("tools")?.jsonObject
            ?.get("list_events")?.jsonObject
            ?.get("tag")?.jsonPrimitive?.content
        assertEquals("gated", tag, "list_events should now be gated")
        println("    ✓ list_events tag switched to gated")

        // Switch back to open
        client.post("${TestConfig.nplUrl}/npl/store/GatewayStore/$storeId/setTag") {
            header("Authorization", "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody("""{"serviceName": "mock-calendar", "toolName": "list_events", "tag": "open"}""")
        }
        println("    ✓ list_events tag restored to open")
    }

    @Test
    @Order(5)
    fun `remove and re-add tool`() = runBlocking {
        Assumptions.assumeTrue(::storeId.isInitialized, "GatewayStore not created")

        // Remove create_event
        val removeResp = client.post(
            "${TestConfig.nplUrl}/npl/store/GatewayStore/$storeId/removeTool"
        ) {
            header("Authorization", "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody("""{"serviceName": "mock-calendar", "toolName": "create_event"}""")
        }
        assertTrue(removeResp.status.isSuccess(), "removeTool should succeed")

        var bundleData = getBundleData()
        val tools = bundleData["catalog"]?.jsonObject
            ?.get("mock-calendar")?.jsonObject
            ?.get("tools")?.jsonObject
        assertFalse(tools!!.containsKey("create_event"), "create_event should be removed")
        println("    ✓ create_event removed")

        // Re-add create_event
        client.post("${TestConfig.nplUrl}/npl/store/GatewayStore/$storeId/registerTool") {
            header("Authorization", "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody("""{"serviceName": "mock-calendar", "toolName": "create_event", "tag": "gated"}""")
        }

        bundleData = getBundleData()
        val tag = bundleData["catalog"]?.jsonObject
            ?.get("mock-calendar")?.jsonObject
            ?.get("tools")?.jsonObject
            ?.get("create_event")?.jsonObject
            ?.get("tag")?.jsonPrimitive?.content
        assertEquals("gated", tag, "create_event should be re-added as gated")
        println("    ✓ create_event re-added as gated")
    }

    // ── Disable/Enable Service ──────────────────────────────────────────────

    @Test
    @Order(6)
    fun `disable and re-enable service`() = runBlocking {
        Assumptions.assumeTrue(::storeId.isInitialized, "GatewayStore not created")

        client.post("${TestConfig.nplUrl}/npl/store/GatewayStore/$storeId/disableService") {
            header("Authorization", "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody("""{"serviceName": "mock-calendar"}""")
        }

        var bundleData = getBundleData()
        assertEquals(false, bundleData["catalog"]?.jsonObject
            ?.get("mock-calendar")?.jsonObject
            ?.get("enabled")?.jsonPrimitive?.boolean)
        println("    ✓ mock-calendar disabled")

        client.post("${TestConfig.nplUrl}/npl/store/GatewayStore/$storeId/enableService") {
            header("Authorization", "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody("""{"serviceName": "mock-calendar"}""")
        }

        bundleData = getBundleData()
        assertEquals(true, bundleData["catalog"]?.jsonObject
            ?.get("mock-calendar")?.jsonObject
            ?.get("enabled")?.jsonPrimitive?.boolean)
        println("    ✓ mock-calendar re-enabled")
    }

    // ── Access Rule CRUD ────────────────────────────────────────────────────

    @Test
    @Order(7)
    fun `add claim-based access rule`() = runBlocking {
        Assumptions.assumeTrue(::storeId.isInitialized, "GatewayStore not created")

        NplBootstrap.addAccessRule(
            storeId, "sales-calendar", "claims",
            matchClaims = mapOf("organization" to "acme", "department" to "sales"),
            allowServices = listOf("mock-calendar"),
            allowTools = listOf("*"),
            adminToken = adminToken
        )

        val bundleData = getBundleData()
        val rules = bundleData["accessRules"]?.jsonArray
        assertNotNull(rules, "Should have access rules")
        val salesRule = rules!!.firstOrNull {
            it.jsonObject["id"]?.jsonPrimitive?.content == "sales-calendar"
        }
        assertNotNull(salesRule, "Should have sales-calendar rule")
        assertEquals("claims", salesRule!!.jsonObject["matcher"]?.jsonObject?.get("matchType")?.jsonPrimitive?.content)
        println("    ✓ Added claim-based rule: sales-calendar")
    }

    @Test
    @Order(8)
    fun `add identity-based access rule`() = runBlocking {
        Assumptions.assumeTrue(::storeId.isInitialized, "GatewayStore not created")

        NplBootstrap.addAccessRule(
            storeId, "alice-calendar", "identity",
            matchIdentity = "alice@acme.com",
            allowServices = listOf("mock-calendar"),
            allowTools = listOf("list_events"),
            adminToken = adminToken
        )

        val bundleData = getBundleData()
        val rules = bundleData["accessRules"]?.jsonArray
        val aliceRule = rules?.firstOrNull {
            it.jsonObject["id"]?.jsonPrimitive?.content == "alice-calendar"
        }
        assertNotNull(aliceRule, "Should have alice-calendar rule")
        assertEquals("identity", aliceRule!!.jsonObject["matcher"]?.jsonObject?.get("matchType")?.jsonPrimitive?.content)
        assertEquals("alice@acme.com", aliceRule.jsonObject["matcher"]?.jsonObject?.get("identity")?.jsonPrimitive?.content)
        println("    ✓ Added identity-based rule: alice-calendar")
    }

    @Test
    @Order(9)
    fun `remove access rule`() = runBlocking {
        Assumptions.assumeTrue(::storeId.isInitialized, "GatewayStore not created")

        val resp = client.post(
            "${TestConfig.nplUrl}/npl/store/GatewayStore/$storeId/removeAccessRule"
        ) {
            header("Authorization", "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody("""{"id": "alice-calendar"}""")
        }
        assertTrue(resp.status.isSuccess(), "removeAccessRule should succeed")

        val bundleData = getBundleData()
        val rules = bundleData["accessRules"]?.jsonArray
        val aliceRule = rules?.firstOrNull {
            it.jsonObject["id"]?.jsonPrimitive?.content == "alice-calendar"
        }
        assertNull(aliceRule, "alice-calendar rule should be removed")
        println("    ✓ Removed access rule: alice-calendar")
    }

    // ── Emergency Revocation ────────────────────────────────────────────────

    @Test
    @Order(10)
    fun `revoke and reinstate subject`() = runBlocking {
        Assumptions.assumeTrue(::storeId.isInitialized, "GatewayStore not created")

        // Revoke
        client.post("${TestConfig.nplUrl}/npl/store/GatewayStore/$storeId/revokeSubject") {
            header("Authorization", "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody("""{"subjectId": "mallory@evil.com"}""")
        }

        var bundleData = getBundleData()
        val revoked = bundleData["revokedSubjects"]?.jsonArray?.map { it.jsonPrimitive.content }
        assertTrue(revoked!!.contains("mallory@evil.com"), "mallory should be revoked")
        println("    ✓ mallory@evil.com revoked")

        // Reinstate
        client.post("${TestConfig.nplUrl}/npl/store/GatewayStore/$storeId/reinstateSubject") {
            header("Authorization", "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody("""{"subjectId": "mallory@evil.com"}""")
        }

        bundleData = getBundleData()
        val revokedAfter = bundleData["revokedSubjects"]?.jsonArray?.map { it.jsonPrimitive.content }
        assertFalse(revokedAfter?.contains("mallory@evil.com") ?: false, "mallory should be reinstated")
        println("    ✓ mallory@evil.com reinstated")
    }

    // ── getBundleData (full snapshot) ────────────────────────────────────────

    @Test
    @Order(11)
    fun `getBundleData returns complete v4 format`() = runBlocking {
        Assumptions.assumeTrue(::storeId.isInitialized, "GatewayStore not created")

        val bundleData = getBundleData()

        val catalog = bundleData["catalog"]?.jsonObject
        assertNotNull(catalog, "Should have catalog")
        assertTrue(catalog!!.containsKey("mock-calendar"), "Should contain mock-calendar")
        println("    Catalog services: ${catalog.keys}")

        val rules = bundleData["accessRules"]?.jsonArray
        assertNotNull(rules, "Should have accessRules")
        println("    Access rules: ${rules!!.size}")

        val revoked = bundleData["revokedSubjects"]?.jsonArray
        assertNotNull(revoked, "Should have revokedSubjects")
        println("    Revoked subjects: $revoked")

        println("    ✓ getBundleData returned complete v4 format")
    }

    // ── Remove Service ──────────────────────────────────────────────────────

    @Test
    @Order(12)
    fun `remove service cleans up`() = runBlocking {
        Assumptions.assumeTrue(::storeId.isInitialized, "GatewayStore not created")

        // Register a temporary service
        client.post("${TestConfig.nplUrl}/npl/store/GatewayStore/$storeId/registerService") {
            header("Authorization", "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody("""{"serviceName": "temp-service"}""")
        }

        var bundleData = getBundleData()
        assertTrue(bundleData["catalog"]?.jsonObject?.containsKey("temp-service") == true)
        println("    ✓ temp-service registered")

        // Remove it
        client.post("${TestConfig.nplUrl}/npl/store/GatewayStore/$storeId/removeService") {
            header("Authorization", "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody("""{"serviceName": "temp-service"}""")
        }

        bundleData = getBundleData()
        assertFalse(bundleData["catalog"]?.jsonObject?.containsKey("temp-service") == true)
        println("    ✓ temp-service removed")
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private suspend fun getBundleData(): JsonObject {
        val response = client.post(
            "${TestConfig.nplUrl}/npl/store/GatewayStore/$storeId/getBundleData"
        ) {
            header("Authorization", "Bearer $gatewayToken")
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertTrue(response.status.isSuccess(), "getBundleData should succeed")
        return json.parseToJsonElement(response.bodyAsText()).jsonObject
    }

    @AfterAll
    fun teardown() {
        println("\n╔════════════════════════════════════════════════════════════════╗")
        println("║ NPL INTEGRATION TESTS (GatewayStore v4) - Complete           ║")
        println("╚════════════════════════════════════════════════════════════════╝")
        if (::client.isInitialized) {
            client.close()
        }
    }
}
