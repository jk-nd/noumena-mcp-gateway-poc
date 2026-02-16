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
 * Integration tests for the NPL Engine — PolicyStore singleton.
 *
 * PolicyStore is the unified policy data store backing OPA. It holds:
 * - Catalog: services + their enabled tools (organization-wide)
 * - Grants: per-user tool access
 * - Emergency revocation: fail-closed subject blocking
 * - Contextual routes: Layer 2 policy routing table
 *
 * Party model (from rules.yml):
 * - pAdmin (role=admin) — manages catalog, grants, routes
 * - pGateway (role=gateway) — reads policy data (getPolicyData)
 *
 * Prerequisites:
 * - Docker stack must be running: docker compose -f deployments/docker-compose.yml up -d
 * - Keycloak must be provisioned with test users (admin, gateway)
 *
 * Run with: ./gradlew :integration-tests:test --tests "*NplIntegrationTest*"
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class NplIntegrationTest {

    private lateinit var adminToken: String      // For admin operations (pAdmin)
    private lateinit var gatewayToken: String     // For getPolicyData (pGateway)
    private lateinit var client: HttpClient
    private lateinit var storeId: String          // PolicyStore singleton ID

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    @BeforeAll
    fun setup() = runBlocking {
        println("╔════════════════════════════════════════════════════════════════╗")
        println("║ NPL INTEGRATION TESTS (PolicyStore) - Setup                  ║")
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
        println("\n┌─────────────────────────────────────────────────────────────┐")
        println("│ TEST: NPL Engine Health Check                              │")
        println("└─────────────────────────────────────────────────────────────┘")

        val response = client.get("${TestConfig.nplUrl}/actuator/health")

        println("    Status: ${response.status}")
        assertTrue(response.status.isSuccess(), "NPL Engine should be healthy")
        println("    ✓ NPL Engine is healthy")
    }

    @Test
    @Order(2)
    fun `create PolicyStore singleton`() = runBlocking {
        println("\n┌─────────────────────────────────────────────────────────────┐")
        println("│ TEST: PolicyStore — find or create singleton               │")
        println("└─────────────────────────────────────────────────────────────┘")

        storeId = NplBootstrap.ensurePolicyStore(adminToken)
        println("    ✓ PolicyStore singleton: $storeId")
        assertNotNull(storeId)
        assertTrue(storeId.isNotBlank(), "Store ID should not be blank")
    }

    // ── Catalog Management ──────────────────────────────────────────────────

    @Test
    @Order(3)
    fun `register and enable mock-calendar service`() = runBlocking {
        println("\n┌─────────────────────────────────────────────────────────────┐")
        println("│ TEST: PolicyStore — register + enable mock-calendar        │")
        println("└─────────────────────────────────────────────────────────────┘")

        Assumptions.assumeTrue(::storeId.isInitialized, "PolicyStore not created")

        NplBootstrap.ensureCatalogService(storeId, "mock-calendar", adminToken)
        println("    ✓ mock-calendar registered + enabled")

        // Verify via getPolicyData
        val policyData = getPolicyData()
        val services = policyData["services"]?.jsonObject
        assertNotNull(services, "Should have services")

        val mockCal = services!!["mock-calendar"]?.jsonObject
        assertNotNull(mockCal, "Should have mock-calendar entry")
        assertEquals(true, mockCal!!["enabled"]?.jsonPrimitive?.boolean, "mock-calendar should be enabled")
        assertEquals(false, mockCal["suspended"]?.jsonPrimitive?.boolean, "mock-calendar should not be suspended")

        println("    ✓ getPolicyData confirms mock-calendar is enabled")
    }

    @Test
    @Order(4)
    fun `enable tools for mock-calendar`() = runBlocking {
        println("\n┌─────────────────────────────────────────────────────────────┐")
        println("│ TEST: PolicyStore — enable list_events + create_event      │")
        println("└─────────────────────────────────────────────────────────────┘")

        Assumptions.assumeTrue(::storeId.isInitialized, "PolicyStore not created")

        NplBootstrap.ensureCatalogToolEnabled(storeId, "mock-calendar", "list_events", adminToken)
        NplBootstrap.ensureCatalogToolEnabled(storeId, "mock-calendar", "create_event", adminToken)
        println("    ✓ Tools enabled: list_events, create_event")

        // Verify via getPolicyData
        val policyData = getPolicyData()
        val enabledTools = policyData["services"]?.jsonObject
            ?.get("mock-calendar")?.jsonObject
            ?.get("enabledTools")?.jsonArray
            ?.map { it.jsonPrimitive.content }

        assertNotNull(enabledTools, "Should have enabledTools")
        assertTrue(enabledTools!!.contains("list_events"), "Should contain list_events")
        assertTrue(enabledTools.contains("create_event"), "Should contain create_event")

        println("    ✓ getPolicyData confirms tools: $enabledTools")
    }

    // ── Grant Management ────────────────────────────────────────────────────

    @Test
    @Order(5)
    fun `grant jarvis wildcard access on mock-calendar`() = runBlocking {
        println("\n┌─────────────────────────────────────────────────────────────┐")
        println("│ TEST: PolicyStore — grant jarvis wildcard on mock-calendar  │")
        println("└─────────────────────────────────────────────────────────────┘")

        Assumptions.assumeTrue(::storeId.isInitialized, "PolicyStore not created")

        NplBootstrap.ensureGrantAll(storeId, "jarvis@acme.com", "mock-calendar", adminToken)
        println("    ✓ Granted jarvis@acme.com wildcard (*) on mock-calendar")

        // Verify via getPolicyData
        val policyData = getPolicyData()
        val jarvisGrants = policyData["grants"]?.jsonObject?.get("jarvis@acme.com")?.jsonArray

        assertNotNull(jarvisGrants, "jarvis should have grants")
        assertTrue(jarvisGrants!!.isNotEmpty(), "jarvis should have at least one grant")

        val mockCalGrant = jarvisGrants.firstOrNull {
            it.jsonObject["serviceName"]?.jsonPrimitive?.content == "mock-calendar"
        }
        assertNotNull(mockCalGrant, "jarvis should have mock-calendar grant")

        val allowedTools = mockCalGrant!!.jsonObject["allowedTools"]?.jsonArray
            ?.map { it.jsonPrimitive.content }
        assertNotNull(allowedTools)
        assertTrue(allowedTools!!.contains("*"), "jarvis should have wildcard (*) access")

        println("    ✓ getPolicyData confirms jarvis grant: mock-calendar.* ")
    }

    @Test
    @Order(6)
    fun `grant specific tool to alice`() = runBlocking {
        println("\n┌─────────────────────────────────────────────────────────────┐")
        println("│ TEST: PolicyStore — grant alice specific tool               │")
        println("└─────────────────────────────────────────────────────────────┘")

        Assumptions.assumeTrue(::storeId.isInitialized, "PolicyStore not created")

        // Grant alice only list_events (not wildcard)
        val response = client.post(
            "${TestConfig.nplUrl}/npl/store/PolicyStore/$storeId/grantTool"
        ) {
            header("Authorization", "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody("""{"subjectId": "alice@acme.com", "serviceName": "mock-calendar", "toolName": "list_events"}""")
        }
        println("    grantTool response: ${response.status}")

        // Verify via getPolicyData
        val policyData = getPolicyData()
        val aliceGrants = policyData["grants"]?.jsonObject?.get("alice@acme.com")?.jsonArray

        assertNotNull(aliceGrants, "alice should have grants")
        val mockCalGrant = aliceGrants!!.firstOrNull {
            it.jsonObject["serviceName"]?.jsonPrimitive?.content == "mock-calendar"
        }
        assertNotNull(mockCalGrant, "alice should have mock-calendar grant")

        val allowedTools = mockCalGrant!!.jsonObject["allowedTools"]?.jsonArray
            ?.map { it.jsonPrimitive.content }
        assertTrue(allowedTools!!.contains("list_events"), "alice should have list_events")
        assertFalse(allowedTools.contains("*"), "alice should NOT have wildcard")
        assertFalse(allowedTools.contains("create_event"), "alice should NOT have create_event")

        println("    ✓ alice granted only list_events on mock-calendar")
    }

    @Test
    @Order(7)
    fun `revoke specific tool from alice`() = runBlocking {
        println("\n┌─────────────────────────────────────────────────────────────┐")
        println("│ TEST: PolicyStore — revoke alice's tool access              │")
        println("└─────────────────────────────────────────────────────────────┘")

        Assumptions.assumeTrue(::storeId.isInitialized, "PolicyStore not created")

        val response = client.post(
            "${TestConfig.nplUrl}/npl/store/PolicyStore/$storeId/revokeTool"
        ) {
            header("Authorization", "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody("""{"subjectId": "alice@acme.com", "serviceName": "mock-calendar", "toolName": "list_events"}""")
        }
        println("    revokeTool response: ${response.status}")
        assertTrue(response.status.isSuccess(), "revokeTool should succeed")

        // Verify alice has no more mock-calendar grants
        val policyData = getPolicyData()
        val aliceGrants = policyData["grants"]?.jsonObject?.get("alice@acme.com")?.jsonArray

        // After revoking the only tool, the grant entry should be removed
        val mockCalGrant = aliceGrants?.firstOrNull {
            it.jsonObject["serviceName"]?.jsonPrimitive?.content == "mock-calendar"
        }
        assertNull(mockCalGrant, "alice should have no mock-calendar grant after revocation")

        println("    ✓ alice's mock-calendar access revoked")
    }

    // ── Emergency Revocation ────────────────────────────────────────────────

    @Test
    @Order(8)
    fun `revoke subject blocks all access`() = runBlocking {
        println("\n┌─────────────────────────────────────────────────────────────┐")
        println("│ TEST: PolicyStore — revokeSubject (emergency kill)          │")
        println("└─────────────────────────────────────────────────────────────┘")

        Assumptions.assumeTrue(::storeId.isInitialized, "PolicyStore not created")

        // Revoke mallory
        val revokeResp = client.post(
            "${TestConfig.nplUrl}/npl/store/PolicyStore/$storeId/revokeSubject"
        ) {
            header("Authorization", "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody("""{"subjectId": "mallory@evil.com"}""")
        }
        assertTrue(revokeResp.status.isSuccess(), "revokeSubject should succeed")

        // Verify in revokedSubjects
        val policyData = getPolicyData()
        val revoked = policyData["revokedSubjects"]?.jsonArray
            ?.map { it.jsonPrimitive.content }

        assertNotNull(revoked, "Should have revokedSubjects")
        assertTrue(revoked!!.contains("mallory@evil.com"), "mallory should be revoked")

        println("    ✓ mallory@evil.com is in revokedSubjects")
    }

    @Test
    @Order(9)
    fun `reinstate subject restores access`() = runBlocking {
        println("\n┌─────────────────────────────────────────────────────────────┐")
        println("│ TEST: PolicyStore — reinstateSubject                        │")
        println("└─────────────────────────────────────────────────────────────┘")

        Assumptions.assumeTrue(::storeId.isInitialized, "PolicyStore not created")

        val reinstateResp = client.post(
            "${TestConfig.nplUrl}/npl/store/PolicyStore/$storeId/reinstateSubject"
        ) {
            header("Authorization", "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody("""{"subjectId": "mallory@evil.com"}""")
        }
        assertTrue(reinstateResp.status.isSuccess(), "reinstateSubject should succeed")

        // Verify removed from revokedSubjects
        val policyData = getPolicyData()
        val revoked = policyData["revokedSubjects"]?.jsonArray
            ?.map { it.jsonPrimitive.content }

        assertFalse(revoked?.contains("mallory@evil.com") ?: false,
            "mallory should no longer be revoked")

        println("    ✓ mallory@evil.com reinstated")
    }

    // ── Service Suspend/Resume ──────────────────────────────────────────────

    @Test
    @Order(10)
    fun `suspend and resume service`() = runBlocking {
        println("\n┌─────────────────────────────────────────────────────────────┐")
        println("│ TEST: PolicyStore — suspend + resume mock-calendar          │")
        println("└─────────────────────────────────────────────────────────────┘")

        Assumptions.assumeTrue(::storeId.isInitialized, "PolicyStore not created")

        // Suspend
        val suspendResp = client.post(
            "${TestConfig.nplUrl}/npl/store/PolicyStore/$storeId/suspendService"
        ) {
            header("Authorization", "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody("""{"serviceName": "mock-calendar"}""")
        }
        assertTrue(suspendResp.status.isSuccess(), "suspendService should succeed")

        // Verify suspended
        var policyData = getPolicyData()
        var suspended = policyData["services"]?.jsonObject
            ?.get("mock-calendar")?.jsonObject
            ?.get("suspended")?.jsonPrimitive?.boolean
        assertEquals(true, suspended, "mock-calendar should be suspended")
        println("    ✓ mock-calendar suspended")

        // Resume
        val resumeResp = client.post(
            "${TestConfig.nplUrl}/npl/store/PolicyStore/$storeId/resumeService"
        ) {
            header("Authorization", "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody("""{"serviceName": "mock-calendar"}""")
        }
        assertTrue(resumeResp.status.isSuccess(), "resumeService should succeed")

        // Verify resumed
        policyData = getPolicyData()
        suspended = policyData["services"]?.jsonObject
            ?.get("mock-calendar")?.jsonObject
            ?.get("suspended")?.jsonPrimitive?.boolean
        assertEquals(false, suspended, "mock-calendar should no longer be suspended")
        println("    ✓ mock-calendar resumed")
    }

    // ── Disable Service/Tool ────────────────────────────────────────────────

    @Test
    @Order(11)
    fun `disable and re-enable service`() = runBlocking {
        println("\n┌─────────────────────────────────────────────────────────────┐")
        println("│ TEST: PolicyStore — disable + re-enable mock-calendar      │")
        println("└─────────────────────────────────────────────────────────────┘")

        Assumptions.assumeTrue(::storeId.isInitialized, "PolicyStore not created")

        // Disable
        val disableResp = client.post(
            "${TestConfig.nplUrl}/npl/store/PolicyStore/$storeId/disableService"
        ) {
            header("Authorization", "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody("""{"serviceName": "mock-calendar"}""")
        }
        assertTrue(disableResp.status.isSuccess(), "disableService should succeed")

        var policyData = getPolicyData()
        var enabled = policyData["services"]?.jsonObject
            ?.get("mock-calendar")?.jsonObject
            ?.get("enabled")?.jsonPrimitive?.boolean
        assertEquals(false, enabled, "mock-calendar should be disabled")
        println("    ✓ mock-calendar disabled")

        // Re-enable
        val enableResp = client.post(
            "${TestConfig.nplUrl}/npl/store/PolicyStore/$storeId/enableService"
        ) {
            header("Authorization", "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody("""{"serviceName": "mock-calendar"}""")
        }
        assertTrue(enableResp.status.isSuccess(), "enableService should succeed")

        policyData = getPolicyData()
        enabled = policyData["services"]?.jsonObject
            ?.get("mock-calendar")?.jsonObject
            ?.get("enabled")?.jsonPrimitive?.boolean
        assertEquals(true, enabled, "mock-calendar should be re-enabled")
        println("    ✓ mock-calendar re-enabled")
    }

    @Test
    @Order(12)
    fun `disable and re-enable tool`() = runBlocking {
        println("\n┌─────────────────────────────────────────────────────────────┐")
        println("│ TEST: PolicyStore — disable + re-enable list_events        │")
        println("└─────────────────────────────────────────────────────────────┘")

        Assumptions.assumeTrue(::storeId.isInitialized, "PolicyStore not created")

        // Disable list_events
        val disableResp = client.post(
            "${TestConfig.nplUrl}/npl/store/PolicyStore/$storeId/disableTool"
        ) {
            header("Authorization", "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody("""{"serviceName": "mock-calendar", "toolName": "list_events"}""")
        }
        assertTrue(disableResp.status.isSuccess(), "disableTool should succeed")

        var policyData = getPolicyData()
        var tools = policyData["services"]?.jsonObject
            ?.get("mock-calendar")?.jsonObject
            ?.get("enabledTools")?.jsonArray
            ?.map { it.jsonPrimitive.content }
        assertFalse(tools!!.contains("list_events"), "list_events should be disabled")
        assertTrue(tools.contains("create_event"), "create_event should still be enabled")
        println("    ✓ list_events disabled (create_event still enabled)")

        // Re-enable list_events
        NplBootstrap.ensureCatalogToolEnabled(storeId, "mock-calendar", "list_events", adminToken)

        policyData = getPolicyData()
        tools = policyData["services"]?.jsonObject
            ?.get("mock-calendar")?.jsonObject
            ?.get("enabledTools")?.jsonArray
            ?.map { it.jsonPrimitive.content }
        assertTrue(tools!!.contains("list_events"), "list_events should be re-enabled")
        println("    ✓ list_events re-enabled")
    }

    // ── Revoke Service Access ───────────────────────────────────────────────

    @Test
    @Order(13)
    fun `revoke all service access from user`() = runBlocking {
        println("\n┌─────────────────────────────────────────────────────────────┐")
        println("│ TEST: PolicyStore — revokeServiceAccess (bulk revoke)      │")
        println("└─────────────────────────────────────────────────────────────┘")

        Assumptions.assumeTrue(::storeId.isInitialized, "PolicyStore not created")

        // First grant bob two tools
        client.post("${TestConfig.nplUrl}/npl/store/PolicyStore/$storeId/grantTool") {
            header("Authorization", "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody("""{"subjectId": "bob@acme.com", "serviceName": "mock-calendar", "toolName": "list_events"}""")
        }
        client.post("${TestConfig.nplUrl}/npl/store/PolicyStore/$storeId/grantTool") {
            header("Authorization", "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody("""{"subjectId": "bob@acme.com", "serviceName": "mock-calendar", "toolName": "create_event"}""")
        }

        // Verify bob has 2 tools
        var policyData = getPolicyData()
        var bobGrants = policyData["grants"]?.jsonObject?.get("bob@acme.com")?.jsonArray
        val bobTools = bobGrants?.firstOrNull {
            it.jsonObject["serviceName"]?.jsonPrimitive?.content == "mock-calendar"
        }?.jsonObject?.get("allowedTools")?.jsonArray?.map { it.jsonPrimitive.content }
        assertTrue(bobTools!!.contains("list_events") && bobTools.contains("create_event"),
            "bob should have both tools before revocation")
        println("    ✓ bob has list_events + create_event")

        // Revoke all service access at once
        val revokeResp = client.post(
            "${TestConfig.nplUrl}/npl/store/PolicyStore/$storeId/revokeServiceAccess"
        ) {
            header("Authorization", "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody("""{"subjectId": "bob@acme.com", "serviceName": "mock-calendar"}""")
        }
        assertTrue(revokeResp.status.isSuccess(), "revokeServiceAccess should succeed")

        // Verify bob has no mock-calendar grants
        policyData = getPolicyData()
        bobGrants = policyData["grants"]?.jsonObject?.get("bob@acme.com")?.jsonArray
        val remaining = bobGrants?.firstOrNull {
            it.jsonObject["serviceName"]?.jsonPrimitive?.content == "mock-calendar"
        }
        assertNull(remaining, "bob should have no mock-calendar grants after revokeServiceAccess")

        println("    ✓ revokeServiceAccess removed all mock-calendar grants for bob")
    }

    // ── Contextual Routing (Layer 2) ────────────────────────────────────────

    @Test
    @Order(14)
    fun `register and remove contextual route`() = runBlocking {
        println("\n┌─────────────────────────────────────────────────────────────┐")
        println("│ TEST: PolicyStore — registerRoute + removeRoute            │")
        println("└─────────────────────────────────────────────────────────────┘")

        Assumptions.assumeTrue(::storeId.isInitialized, "PolicyStore not created")

        // Register a wildcard route for mock-calendar
        val registerResp = client.post(
            "${TestConfig.nplUrl}/npl/store/PolicyStore/$storeId/registerRoute"
        ) {
            header("Authorization", "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody("""{"serviceName": "mock-calendar", "toolName": "*", "routeProtocol": "PiiGuardPolicy", "instanceId": "pii-001", "endpoint": "/npl/policies/PiiGuardPolicy/pii-001/evaluate"}""")
        }
        assertTrue(registerResp.status.isSuccess(), "registerRoute should succeed")

        // Verify route in getPolicyData (RouteGroup model: {mode, routes: [{policyProtocol, instanceId, endpoint}]})
        var policyData = getPolicyData()
        val routeGroup = policyData["contextualRoutes"]?.jsonObject
            ?.get("mock-calendar")?.jsonObject
            ?.get("*")?.jsonObject
        assertNotNull(routeGroup, "Should have wildcard route group for mock-calendar")
        assertEquals("single", routeGroup!!["mode"]?.jsonPrimitive?.content, "New route should have mode=single")
        val routes = routeGroup["routes"]?.jsonArray
        assertNotNull(routes, "RouteGroup should have routes array")
        assertEquals(1, routes!!.size, "Single route group should have 1 route")
        assertEquals("PiiGuardPolicy", routes[0].jsonObject["policyProtocol"]?.jsonPrimitive?.content)
        assertEquals("pii-001", routes[0].jsonObject["instanceId"]?.jsonPrimitive?.content)
        println("    ✓ Route registered: mock-calendar.* → RouteGroup(single, [PiiGuardPolicy/pii-001])")

        // Remove the route
        val removeResp = client.post(
            "${TestConfig.nplUrl}/npl/store/PolicyStore/$storeId/removeRoute"
        ) {
            header("Authorization", "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody("""{"serviceName": "mock-calendar", "toolName": "*"}""")
        }
        assertTrue(removeResp.status.isSuccess(), "removeRoute should succeed")

        // Verify route removed
        policyData = getPolicyData()
        val allRoutes = policyData["contextualRoutes"]?.jsonObject
        val mockCalRoutes = allRoutes?.get("mock-calendar")?.jsonObject
        // After removing the only route, the service entry should be gone
        assertTrue(mockCalRoutes == null || !mockCalRoutes.containsKey("*"),
            "Wildcard route should be removed")

        println("    ✓ Route removed: mock-calendar.* no longer exists")
    }

    // ── getPolicyData (gateway party) ───────────────────────────────────────

    @Test
    @Order(15)
    fun `getPolicyData returns complete policy snapshot`() = runBlocking {
        println("\n┌─────────────────────────────────────────────────────────────┐")
        println("│ TEST: PolicyStore — getPolicyData (full snapshot)           │")
        println("└─────────────────────────────────────────────────────────────┘")

        Assumptions.assumeTrue(::storeId.isInitialized, "PolicyStore not created")

        val policyData = getPolicyData()

        // Services
        val services = policyData["services"]?.jsonObject
        assertNotNull(services, "Should have services map")
        assertTrue(services!!.containsKey("mock-calendar"), "Should contain mock-calendar")
        println("    Services: ${services.keys}")

        // Grants
        val grants = policyData["grants"]?.jsonObject
        assertNotNull(grants, "Should have grants map")
        assertTrue(grants!!.containsKey("jarvis@acme.com"), "Should have jarvis grants")
        println("    Grant subjects: ${grants.keys}")

        // Revoked subjects
        val revoked = policyData["revokedSubjects"]?.jsonArray
        assertNotNull(revoked, "Should have revokedSubjects set")
        println("    Revoked subjects: $revoked")

        // Contextual routes
        val routes = policyData["contextualRoutes"]?.jsonObject
        assertNotNull(routes, "Should have contextualRoutes map")
        println("    Contextual routes: ${routes!!.keys}")

        println("    ✓ getPolicyData returned complete policy snapshot")
    }

    // ── Summary ─────────────────────────────────────────────────────────────

    @Test
    @Order(16)
    fun `list PolicyStore singleton`() = runBlocking {
        println("\n┌─────────────────────────────────────────────────────────────┐")
        println("│ TEST: List PolicyStore Singleton                            │")
        println("└─────────────────────────────────────────────────────────────┘")

        val listResp = client.get("${TestConfig.nplUrl}/npl/store/PolicyStore/") {
            header("Authorization", "Bearer $adminToken")
        }

        assertTrue(listResp.status.isSuccess(), "List should succeed")

        val items = json.parseToJsonElement(listResp.bodyAsText())
            .jsonObject["items"]?.jsonArray
        assertNotNull(items, "Should have items")
        assertEquals(1, items!!.size, "Should have exactly 1 PolicyStore singleton")

        val id = items[0].jsonObject["@id"]?.jsonPrimitive?.content
        println("    PolicyStore singleton: $id")
        println("    ✓ Exactly 1 PolicyStore instance exists")
    }

    // ── Route Groups (AND/OR composition) ──────────────────────────────────

    @Test
    @Order(17)
    fun `route group — addRouteToGroup upgrades single to AND group`() = runBlocking {
        println("\n┌─────────────────────────────────────────────────────────────┐")
        println("│ TEST: PolicyStore — addRouteToGroup (single → AND group)   │")
        println("└─────────────────────────────────────────────────────────────┘")

        Assumptions.assumeTrue(::storeId.isInitialized, "PolicyStore not created")

        // 1. Register a single route for mock-calendar.*
        val registerResp = client.post(
            "${TestConfig.nplUrl}/npl/store/PolicyStore/$storeId/registerRoute"
        ) {
            header("Authorization", "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody("""{"serviceName": "mock-calendar", "toolName": "*", "routeProtocol": "ApprovalPolicy", "instanceId": "ap-001", "endpoint": "/npl/policies/ApprovalPolicy/ap-001/evaluate"}""")
        }
        assertTrue(registerResp.status.isSuccess(), "registerRoute should succeed")

        // Verify single route
        var policyData = getPolicyData()
        var group = policyData["contextualRoutes"]?.jsonObject
            ?.get("mock-calendar")?.jsonObject
            ?.get("*")?.jsonObject
        assertNotNull(group, "Should have route group")
        assertEquals("single", group!!["mode"]?.jsonPrimitive?.content)
        assertEquals(1, group["routes"]?.jsonArray?.size)
        println("    ✓ Single route registered: ApprovalPolicy")

        // 2. Add a second protocol to the group
        val addResp = client.post(
            "${TestConfig.nplUrl}/npl/store/PolicyStore/$storeId/addRouteToGroup"
        ) {
            header("Authorization", "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody("""{"serviceName": "mock-calendar", "toolName": "*", "routeProtocol": "RateLimitPolicy", "instanceId": "rl-001", "endpoint": "/npl/policies/RateLimitPolicy/rl-001/evaluate"}""")
        }
        assertTrue(addResp.status.isSuccess(), "addRouteToGroup should succeed")

        // Verify group upgraded to AND mode with 2 routes
        policyData = getPolicyData()
        group = policyData["contextualRoutes"]?.jsonObject
            ?.get("mock-calendar")?.jsonObject
            ?.get("*")?.jsonObject
        assertNotNull(group, "Should still have route group")
        assertEquals("and", group!!["mode"]?.jsonPrimitive?.content, "Mode should upgrade from single to and")
        val routes = group["routes"]?.jsonArray
        assertNotNull(routes, "Should have routes array")
        assertEquals(2, routes!!.size, "Should have 2 routes in group")

        val protocols = routes.map { it.jsonObject["policyProtocol"]?.jsonPrimitive?.content }
        assertTrue(protocols.contains("ApprovalPolicy"), "Should contain ApprovalPolicy")
        assertTrue(protocols.contains("RateLimitPolicy"), "Should contain RateLimitPolicy")
        println("    ✓ Group upgraded to AND with 2 routes: $protocols")
    }

    @Test
    @Order(18)
    fun `route group — setRouteGroupMode switches AND to OR`() = runBlocking {
        println("\n┌─────────────────────────────────────────────────────────────┐")
        println("│ TEST: PolicyStore — setRouteGroupMode (AND → OR)           │")
        println("└─────────────────────────────────────────────────────────────┘")

        Assumptions.assumeTrue(::storeId.isInitialized, "PolicyStore not created")

        // Precondition: group exists from @Order(17) with mode=and, 2 routes
        var policyData = getPolicyData()
        var group = policyData["contextualRoutes"]?.jsonObject
            ?.get("mock-calendar")?.jsonObject
            ?.get("*")?.jsonObject
        assertNotNull(group, "Route group should exist from previous test")
        assertEquals("and", group!!["mode"]?.jsonPrimitive?.content, "Precondition: mode should be 'and'")

        // Set mode to OR
        val modeResp = client.post(
            "${TestConfig.nplUrl}/npl/store/PolicyStore/$storeId/setRouteGroupMode"
        ) {
            header("Authorization", "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody("""{"serviceName": "mock-calendar", "toolName": "*", "mode": "or"}""")
        }
        assertTrue(modeResp.status.isSuccess(), "setRouteGroupMode should succeed")

        // Verify mode changed
        policyData = getPolicyData()
        group = policyData["contextualRoutes"]?.jsonObject
            ?.get("mock-calendar")?.jsonObject
            ?.get("*")?.jsonObject
        assertNotNull(group, "Route group should still exist")
        assertEquals("or", group!!["mode"]?.jsonPrimitive?.content, "Mode should now be 'or'")
        assertEquals(2, group["routes"]?.jsonArray?.size, "Routes should be unchanged")
        println("    ✓ Group mode changed to OR (2 routes preserved)")

        // Switch back to AND
        val modeResp2 = client.post(
            "${TestConfig.nplUrl}/npl/store/PolicyStore/$storeId/setRouteGroupMode"
        ) {
            header("Authorization", "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody("""{"serviceName": "mock-calendar", "toolName": "*", "mode": "and"}""")
        }
        assertTrue(modeResp2.status.isSuccess(), "setRouteGroupMode back to AND should succeed")

        policyData = getPolicyData()
        group = policyData["contextualRoutes"]?.jsonObject
            ?.get("mock-calendar")?.jsonObject
            ?.get("*")?.jsonObject
        assertEquals("and", group!!["mode"]?.jsonPrimitive?.content, "Mode should be back to 'and'")
        println("    ✓ Group mode toggled back to AND")
    }

    @Test
    @Order(19)
    fun `route group — removeRouteFromGroup reduces group and cleans up`() = runBlocking {
        println("\n┌─────────────────────────────────────────────────────────────┐")
        println("│ TEST: PolicyStore — removeRouteFromGroup + cleanup          │")
        println("└─────────────────────────────────────────────────────────────┘")

        Assumptions.assumeTrue(::storeId.isInitialized, "PolicyStore not created")

        // Precondition: group exists from @Order(17-18) with mode=and, 2 routes
        var policyData = getPolicyData()
        var group = policyData["contextualRoutes"]?.jsonObject
            ?.get("mock-calendar")?.jsonObject
            ?.get("*")?.jsonObject
        assertNotNull(group, "Route group should exist from previous tests")
        assertEquals(2, group!!["routes"]?.jsonArray?.size, "Precondition: should have 2 routes")

        // Remove RateLimitPolicy from the group
        val removeResp = client.post(
            "${TestConfig.nplUrl}/npl/store/PolicyStore/$storeId/removeRouteFromGroup"
        ) {
            header("Authorization", "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody("""{"serviceName": "mock-calendar", "toolName": "*", "routeProtocol": "RateLimitPolicy"}""")
        }
        assertTrue(removeResp.status.isSuccess(), "removeRouteFromGroup should succeed")

        // Verify only ApprovalPolicy remains
        policyData = getPolicyData()
        group = policyData["contextualRoutes"]?.jsonObject
            ?.get("mock-calendar")?.jsonObject
            ?.get("*")?.jsonObject
        assertNotNull(group, "Route group should still exist with 1 route")
        val routes = group!!["routes"]?.jsonArray
        assertNotNull(routes, "Should have routes array")
        assertEquals(1, routes!!.size, "Should have 1 route remaining")
        assertEquals("ApprovalPolicy", routes[0].jsonObject["policyProtocol"]?.jsonPrimitive?.content)
        println("    ✓ RateLimitPolicy removed, ApprovalPolicy remains")

        // Remove the last route — group should be fully cleaned up
        val removeLastResp = client.post(
            "${TestConfig.nplUrl}/npl/store/PolicyStore/$storeId/removeRouteFromGroup"
        ) {
            header("Authorization", "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody("""{"serviceName": "mock-calendar", "toolName": "*", "routeProtocol": "ApprovalPolicy"}""")
        }
        assertTrue(removeLastResp.status.isSuccess(), "Removing last route from group should succeed")

        // Verify route group fully removed
        policyData = getPolicyData()
        val mockCalRoutes = policyData["contextualRoutes"]?.jsonObject
            ?.get("mock-calendar")?.jsonObject
        assertTrue(mockCalRoutes == null || !mockCalRoutes.containsKey("*"),
            "Route group should be fully removed when last route is removed")
        println("    ✓ Last route removed — route group cleaned up completely")
    }

    @Test
    @Order(20)
    fun `route group — addRouteToGroup creates new AND group when no route exists`() = runBlocking {
        println("\n┌─────────────────────────────────────────────────────────────┐")
        println("│ TEST: PolicyStore — addRouteToGroup (creates new group)     │")
        println("└─────────────────────────────────────────────────────────────┘")

        Assumptions.assumeTrue(::storeId.isInitialized, "PolicyStore not created")

        // Ensure no route exists for mock-calendar.*
        var policyData = getPolicyData()
        val existing = policyData["contextualRoutes"]?.jsonObject
            ?.get("mock-calendar")?.jsonObject
            ?.get("*")
        assertTrue(existing == null, "Precondition: no route should exist for mock-calendar.*")

        // addRouteToGroup when no group exists — should create a new "and" group
        val addResp = client.post(
            "${TestConfig.nplUrl}/npl/store/PolicyStore/$storeId/addRouteToGroup"
        ) {
            header("Authorization", "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody("""{"serviceName": "mock-calendar", "toolName": "*", "routeProtocol": "ConstraintPolicy", "instanceId": "cp-001", "endpoint": "/npl/policies/ConstraintPolicy/cp-001/evaluate"}""")
        }
        assertTrue(addResp.status.isSuccess(), "addRouteToGroup should succeed even without existing group")

        // Verify new AND group created
        policyData = getPolicyData()
        val group = policyData["contextualRoutes"]?.jsonObject
            ?.get("mock-calendar")?.jsonObject
            ?.get("*")?.jsonObject
        assertNotNull(group, "Should have created new route group")
        assertEquals("and", group!!["mode"]?.jsonPrimitive?.content, "New group from addRouteToGroup should be 'and'")
        val routes = group["routes"]?.jsonArray
        assertEquals(1, routes!!.size, "Should have 1 route")
        assertEquals("ConstraintPolicy", routes[0].jsonObject["policyProtocol"]?.jsonPrimitive?.content)
        println("    ✓ addRouteToGroup created new AND group with ConstraintPolicy")

        // Cleanup
        val cleanupResp = client.post(
            "${TestConfig.nplUrl}/npl/store/PolicyStore/$storeId/removeRoute"
        ) {
            header("Authorization", "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody("""{"serviceName": "mock-calendar", "toolName": "*"}""")
        }
        assertTrue(cleanupResp.status.isSuccess(), "Cleanup removeRoute should succeed")
        println("    ✓ Cleanup complete")
    }

    @Test
    @Order(21)
    fun `route group — setRouteGroupMode rejects invalid mode`() = runBlocking {
        println("\n┌─────────────────────────────────────────────────────────────┐")
        println("│ TEST: PolicyStore — setRouteGroupMode validation            │")
        println("└─────────────────────────────────────────────────────────────┘")

        Assumptions.assumeTrue(::storeId.isInitialized, "PolicyStore not created")

        // Register a route so we have a group
        client.post("${TestConfig.nplUrl}/npl/store/PolicyStore/$storeId/registerRoute") {
            header("Authorization", "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody("""{"serviceName": "mock-calendar", "toolName": "*", "routeProtocol": "ApprovalPolicy", "instanceId": "ap-001", "endpoint": "/npl/policies/ApprovalPolicy/ap-001/evaluate"}""")
        }

        // Try invalid mode — should fail (require mode == "and" || mode == "or")
        val invalidResp = client.post(
            "${TestConfig.nplUrl}/npl/store/PolicyStore/$storeId/setRouteGroupMode"
        ) {
            header("Authorization", "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody("""{"serviceName": "mock-calendar", "toolName": "*", "mode": "invalid"}""")
        }
        assertFalse(invalidResp.status.isSuccess(), "setRouteGroupMode with invalid mode should fail")
        println("    ✓ Invalid mode rejected")

        // Try non-existent service — should fail
        val noServiceResp = client.post(
            "${TestConfig.nplUrl}/npl/store/PolicyStore/$storeId/setRouteGroupMode"
        ) {
            header("Authorization", "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody("""{"serviceName": "nonexistent", "toolName": "*", "mode": "and"}""")
        }
        assertFalse(noServiceResp.status.isSuccess(), "setRouteGroupMode for non-existent service should fail")
        println("    ✓ Non-existent service rejected")

        // Cleanup
        client.post("${TestConfig.nplUrl}/npl/store/PolicyStore/$storeId/removeRoute") {
            header("Authorization", "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody("""{"serviceName": "mock-calendar", "toolName": "*"}""")
        }
        println("    ✓ Cleanup complete")
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    /** Call getPolicyData on the PolicyStore singleton using the gateway token. */
    private suspend fun getPolicyData(): JsonObject {
        val response = client.post(
            "${TestConfig.nplUrl}/npl/store/PolicyStore/$storeId/getPolicyData"
        ) {
            header("Authorization", "Bearer $gatewayToken")
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertTrue(response.status.isSuccess(), "getPolicyData should succeed")
        return json.parseToJsonElement(response.bodyAsText()).jsonObject
    }

    @AfterAll
    fun teardown() {
        println("\n╔════════════════════════════════════════════════════════════════╗")
        println("║ NPL INTEGRATION TESTS (PolicyStore) - Complete               ║")
        println("╚════════════════════════════════════════════════════════════════╝")
        if (::client.isInitialized) {
            client.close()
        }
    }
}
