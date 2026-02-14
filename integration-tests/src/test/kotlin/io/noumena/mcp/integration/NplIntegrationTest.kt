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
 * Integration tests for the NPL Engine — the policy data store backing OPA.
 *
 * NPL holds the three-layer authorization model that OPA queries at request time:
 * 1. ServiceRegistry — which services are enabled organization-wide
 * 2. ToolPolicy — which tools are enabled per service
 * 3. UserToolAccess — which users can access which tools on which services
 *
 * These tests exercise all three layers directly against the NPL Engine API,
 * using mock-calendar as the test service (matching the Envoy-routed backend).
 *
 * Party model (from rules.yml):
 * - pAdmin (role=admin) — manages all NPL state (create, enable, grant, revoke)
 * - pGateway (role=gateway) — queries state at runtime (checkAccess, hasAccess, getAccessList)
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

    private lateinit var adminToken: String      // For setup/admin operations (pAdmin)
    private lateinit var gatewayToken: String     // For checkAccess/hasAccess (pGateway)
    private lateinit var client: HttpClient

    // Instance IDs populated by bootstrap helpers and reused across tests
    private var serviceRegistryId: String? = null
    private var toolPolicyId: String? = null
    private var userToolAccessId: String? = null  // jarvis — granted user
    private var userToolAccessDeniedId: String? = null  // alice — denied user

    @BeforeAll
    fun setup() = runBlocking {
        println("╔════════════════════════════════════════════════════════════════╗")
        println("║ NPL INTEGRATION TESTS (3-Layer Policy Model) - Setup          ║")
        println("╠════════════════════════════════════════════════════════════════╣")
        println("║ NPL URL:      ${TestConfig.nplUrl}")
        println("║ Keycloak URL: ${TestConfig.keycloakUrl}")
        println("╚════════════════════════════════════════════════════════════════╝")

        client = HttpClient(CIO) {
            install(ContentNegotiation) {
                json(TestClient.json)
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

    // ── Layer 1: ServiceRegistry ───────────────────────────────────────────

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
    fun `create ServiceRegistry and enable mock-calendar`() = runBlocking {
        println("\n┌─────────────────────────────────────────────────────────────┐")
        println("│ TEST: ServiceRegistry — enable mock-calendar               │")
        println("└─────────────────────────────────────────────────────────────┘")

        serviceRegistryId = NplBootstrap.ensureServiceRegistry(adminToken)
        println("    ✓ ServiceRegistry: $serviceRegistryId")

        NplBootstrap.ensureServiceEnabled(serviceRegistryId!!, "mock-calendar", adminToken)
        println("    ✓ mock-calendar service enabled")

        // Verify via isServiceEnabled
        val checkResp = client.post(
            "${TestConfig.nplUrl}/npl/registry/ServiceRegistry/$serviceRegistryId/isServiceEnabled"
        ) {
            header("Authorization", "Bearer $gatewayToken")
            contentType(ContentType.Application.Json)
            setBody("""{"serviceName": "mock-calendar"}""")
        }

        assertTrue(checkResp.status.isSuccess(), "isServiceEnabled should succeed")
        val enabled = checkResp.bodyAsText().trim().removeSurrounding("\"").toBooleanStrictOrNull() ?: false
        assertTrue(enabled, "mock-calendar should be enabled in ServiceRegistry")
        println("    ✓ isServiceEnabled confirmed: mock-calendar = true")
    }

    @Test
    @Order(3)
    fun `getEnabledServices returns mock-calendar`() = runBlocking {
        println("\n┌─────────────────────────────────────────────────────────────┐")
        println("│ TEST: ServiceRegistry — getEnabledServices                 │")
        println("└─────────────────────────────────────────────────────────────┘")

        Assumptions.assumeTrue(serviceRegistryId != null, "ServiceRegistry not created")

        val response = client.post(
            "${TestConfig.nplUrl}/npl/registry/ServiceRegistry/$serviceRegistryId/getEnabledServices"
        ) {
            header("Authorization", "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody("{}")
        }

        println("    Response status: ${response.status}")
        val body = response.bodyAsText()
        println("    Enabled services: $body")

        assertTrue(response.status.isSuccess(), "getEnabledServices should succeed")
        assertTrue(body.contains("mock-calendar"), "Enabled services should contain mock-calendar")
        println("    ✓ getEnabledServices confirmed")
    }

    // ── Layer 2: ToolPolicy ────────────────────────────────────────────────

    @Test
    @Order(4)
    fun `create ToolPolicy for mock-calendar and enable tools`() = runBlocking {
        println("\n┌─────────────────────────────────────────────────────────────┐")
        println("│ TEST: ToolPolicy — enable list_events + create_event       │")
        println("└─────────────────────────────────────────────────────────────┘")

        toolPolicyId = NplBootstrap.ensureToolPolicy("mock-calendar", adminToken)
        println("    ✓ ToolPolicy (mock-calendar): $toolPolicyId")

        NplBootstrap.ensureToolEnabled(toolPolicyId!!, "list_events", adminToken)
        NplBootstrap.ensureToolEnabled(toolPolicyId!!, "create_event", adminToken)
        println("    ✓ Tools enabled: list_events, create_event")
    }

    @Test
    @Order(5)
    fun `checkAccess returns true for enabled tool`() = runBlocking {
        println("\n┌─────────────────────────────────────────────────────────────┐")
        println("│ TEST: ToolPolicy — checkAccess (enabled tool)              │")
        println("└─────────────────────────────────────────────────────────────┘")

        Assumptions.assumeTrue(toolPolicyId != null, "ToolPolicy not created")

        val invokeBody = buildJsonObject {
            put("toolName", "list_events")
            put("callerIdentity", "jarvis@acme.com")
        }

        println("    Checking access for 'list_events'...")
        println("    Using gateway token (pGateway party)")

        val response = client.post(
            "${TestConfig.nplUrl}/npl/services/ToolPolicy/$toolPolicyId/checkAccess"
        ) {
            header("Authorization", "Bearer $gatewayToken")
            contentType(ContentType.Application.Json)
            setBody(invokeBody.toString())
        }

        println("    Response status: ${response.status}")
        val responseBody = response.bodyAsText()
        println("    Response body: $responseBody")

        assertTrue(response.status.isSuccess(), "checkAccess should succeed")

        val allowed = responseBody.trim().removeSurrounding("\"").toBooleanStrictOrNull() ?: false
        assertTrue(allowed, "checkAccess should return true for enabled tool")
        println("    ✓ Access allowed for list_events")
    }

    @Test
    @Order(6)
    fun `checkAccess returns false for disabled tool`() = runBlocking {
        println("\n┌─────────────────────────────────────────────────────────────┐")
        println("│ TEST: ToolPolicy — checkAccess (disabled tool)             │")
        println("└─────────────────────────────────────────────────────────────┘")

        Assumptions.assumeTrue(toolPolicyId != null, "ToolPolicy not created")

        val invokeBody = buildJsonObject {
            put("toolName", "nonexistent_tool")
            put("callerIdentity", "jarvis@acme.com")
        }

        println("    Checking access for 'nonexistent_tool'...")
        println("    Using gateway token (pGateway party)")

        val response = client.post(
            "${TestConfig.nplUrl}/npl/services/ToolPolicy/$toolPolicyId/checkAccess"
        ) {
            header("Authorization", "Bearer $gatewayToken")
            contentType(ContentType.Application.Json)
            setBody(invokeBody.toString())
        }

        println("    Response status: ${response.status}")
        val responseBody = response.bodyAsText()
        println("    Response body: $responseBody")

        assertTrue(response.status.isSuccess(), "API call should succeed")

        val allowed = responseBody.trim().removeSurrounding("\"").toBooleanStrictOrNull() ?: true
        assertFalse(allowed, "checkAccess should return false for disabled tool")
        println("    ✓ Access correctly denied for nonexistent_tool")
    }

    @Test
    @Order(7)
    fun `getEnabledTools returns enabled tool set`() = runBlocking {
        println("\n┌─────────────────────────────────────────────────────────────┐")
        println("│ TEST: ToolPolicy — getEnabledTools                         │")
        println("└─────────────────────────────────────────────────────────────┘")

        Assumptions.assumeTrue(toolPolicyId != null, "ToolPolicy not created")

        val response = client.post(
            "${TestConfig.nplUrl}/npl/services/ToolPolicy/$toolPolicyId/getEnabledTools"
        ) {
            header("Authorization", "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody("{}")
        }

        println("    Response status: ${response.status}")
        val responseBody = response.bodyAsText()
        println("    Response body: $responseBody")

        assertTrue(response.status.isSuccess(), "getEnabledTools should succeed")
        assertTrue(responseBody.contains("list_events"), "Should contain list_events")
        assertTrue(responseBody.contains("create_event"), "Should contain create_event")
        println("    ✓ Enabled tools: list_events, create_event")
    }

    @Test
    @Order(8)
    fun `getRequestCount returns number of policy checks`() = runBlocking {
        println("\n┌─────────────────────────────────────────────────────────────┐")
        println("│ TEST: ToolPolicy — getRequestCount                         │")
        println("└─────────────────────────────────────────────────────────────┘")

        Assumptions.assumeTrue(toolPolicyId != null, "ToolPolicy not created")

        val response = client.post(
            "${TestConfig.nplUrl}/npl/services/ToolPolicy/$toolPolicyId/getRequestCount"
        ) {
            header("Authorization", "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody("{}")
        }

        println("    Response status: ${response.status}")
        val responseBody = response.bodyAsText()
        println("    Response body: $responseBody")

        assertTrue(response.status.isSuccess(), "getRequestCount should succeed")

        val count = responseBody.trim().toIntOrNull() ?: -1
        assertTrue(count >= 2, "Should have at least 2 policy checks (allowed + denied)")
        println("    ✓ Policy check count: $count")
    }

    // ── Layer 3: UserToolAccess ────────────────────────────────────────────

    @Test
    @Order(9)
    fun `create UserToolAccess and grant wildcard for jarvis`() = runBlocking {
        println("\n┌─────────────────────────────────────────────────────────────┐")
        println("│ TEST: UserToolAccess — grant jarvis wildcard on mock-cal   │")
        println("└─────────────────────────────────────────────────────────────┘")

        userToolAccessId = NplBootstrap.ensureUserToolAccess("jarvis@acme.com", adminToken)
        println("    ✓ UserToolAccess (jarvis@acme.com): $userToolAccessId")

        NplBootstrap.ensureUserToolGrantAll(userToolAccessId!!, "mock-calendar", adminToken)
        println("    ✓ Granted wildcard (*) on mock-calendar")
    }

    @Test
    @Order(10)
    fun `hasAccess returns true for granted user`() = runBlocking {
        println("\n┌─────────────────────────────────────────────────────────────┐")
        println("│ TEST: UserToolAccess — hasAccess (jarvis, granted)          │")
        println("└─────────────────────────────────────────────────────────────┘")

        Assumptions.assumeTrue(userToolAccessId != null, "UserToolAccess not created")

        val response = client.post(
            "${TestConfig.nplUrl}/npl/users/UserToolAccess/$userToolAccessId/hasAccess"
        ) {
            header("Authorization", "Bearer $gatewayToken")
            contentType(ContentType.Application.Json)
            setBody("""{"serviceName": "mock-calendar", "toolName": "list_events"}""")
        }

        println("    Response status: ${response.status}")
        val responseBody = response.bodyAsText()
        println("    Response body: $responseBody")

        assertTrue(response.status.isSuccess(), "hasAccess should succeed")

        val hasAccess = responseBody.trim().removeSurrounding("\"").toBooleanStrictOrNull() ?: false
        assertTrue(hasAccess, "jarvis should have access to list_events on mock-calendar")
        println("    ✓ hasAccess = true for jarvis → mock-calendar.list_events")
    }

    @Test
    @Order(11)
    fun `getAccessList returns service access entries`() = runBlocking {
        println("\n┌─────────────────────────────────────────────────────────────┐")
        println("│ TEST: UserToolAccess — getAccessList (jarvis)               │")
        println("└─────────────────────────────────────────────────────────────┘")

        Assumptions.assumeTrue(userToolAccessId != null, "UserToolAccess not created")

        val response = client.post(
            "${TestConfig.nplUrl}/npl/users/UserToolAccess/$userToolAccessId/getAccessList"
        ) {
            header("Authorization", "Bearer $gatewayToken")
            contentType(ContentType.Application.Json)
            setBody("{}")
        }

        println("    Response status: ${response.status}")
        val responseBody = response.bodyAsText()
        println("    Response body: $responseBody")

        assertTrue(response.status.isSuccess(), "getAccessList should succeed")
        assertTrue(responseBody.contains("mock-calendar"), "Access list should contain mock-calendar")
        assertTrue(responseBody.contains("*"), "Access list should contain wildcard (*)")
        println("    ✓ getAccessList returned mock-calendar with wildcard access")
    }

    @Test
    @Order(12)
    fun `create UserToolAccess for alice with no grants`() = runBlocking {
        println("\n┌─────────────────────────────────────────────────────────────┐")
        println("│ TEST: UserToolAccess — create alice (no grants)             │")
        println("└─────────────────────────────────────────────────────────────┘")

        userToolAccessDeniedId = NplBootstrap.ensureUserToolAccess("alice@acme.com", adminToken)
        println("    ✓ UserToolAccess (alice@acme.com): $userToolAccessDeniedId (no grants)")
    }

    @Test
    @Order(13)
    fun `hasAccess returns false for user without grants`() = runBlocking {
        println("\n┌─────────────────────────────────────────────────────────────┐")
        println("│ TEST: UserToolAccess — hasAccess (alice, denied)            │")
        println("└─────────────────────────────────────────────────────────────┘")

        Assumptions.assumeTrue(userToolAccessDeniedId != null, "UserToolAccess for alice not created")

        val response = client.post(
            "${TestConfig.nplUrl}/npl/users/UserToolAccess/$userToolAccessDeniedId/hasAccess"
        ) {
            header("Authorization", "Bearer $gatewayToken")
            contentType(ContentType.Application.Json)
            setBody("""{"serviceName": "mock-calendar", "toolName": "list_events"}""")
        }

        println("    Response status: ${response.status}")
        val responseBody = response.bodyAsText()
        println("    Response body: $responseBody")

        assertTrue(response.status.isSuccess(), "hasAccess call should succeed")

        val hasAccess = responseBody.trim().removeSurrounding("\"").toBooleanStrictOrNull() ?: true
        assertFalse(hasAccess, "alice should NOT have access to list_events on mock-calendar")
        println("    ✓ hasAccess = false for alice → mock-calendar.list_events")
    }

    // ── Summary ────────────────────────────────────────────────────────────

    @Test
    @Order(14)
    fun `list all protocol instances`() = runBlocking {
        println("\n┌─────────────────────────────────────────────────────────────┐")
        println("│ TEST: List All Protocol Instances                           │")
        println("└─────────────────────────────────────────────────────────────┘")

        // ServiceRegistry
        val registryResponse = client.get("${TestConfig.nplUrl}/npl/registry/ServiceRegistry/") {
            header("Authorization", "Bearer $adminToken")
        }
        println("    ServiceRegistry instances:")
        if (registryResponse.status.isSuccess()) {
            val items = TestClient.json.parseToJsonElement(registryResponse.bodyAsText())
                .jsonObject["items"]?.jsonArray
            println("      Count: ${items?.size ?: 0}")
        }

        // ToolPolicy
        val policyResponse = client.get("${TestConfig.nplUrl}/npl/services/ToolPolicy/") {
            header("Authorization", "Bearer $adminToken")
        }
        println("    ToolPolicy instances:")
        if (policyResponse.status.isSuccess()) {
            val items = TestClient.json.parseToJsonElement(policyResponse.bodyAsText())
                .jsonObject["items"]?.jsonArray
            println("      Count: ${items?.size ?: 0}")
            items?.forEach { instance ->
                val id = instance.jsonObject["@id"]?.jsonPrimitive?.content
                val svc = instance.jsonObject["policyServiceName"]?.jsonPrimitive?.content
                println("      - $id (service: $svc)")
            }
        }

        // UserToolAccess
        val accessResponse = client.get("${TestConfig.nplUrl}/npl/users/UserToolAccess/") {
            header("Authorization", "Bearer $adminToken")
        }
        println("    UserToolAccess instances:")
        if (accessResponse.status.isSuccess()) {
            val items = TestClient.json.parseToJsonElement(accessResponse.bodyAsText())
                .jsonObject["items"]?.jsonArray
            println("      Count: ${items?.size ?: 0}")
            items?.forEach { instance ->
                val id = instance.jsonObject["@id"]?.jsonPrimitive?.content
                val userId = instance.jsonObject["userId"]?.jsonPrimitive?.content
                println("      - $id (user: $userId)")
            }
        }

        println("    ✓ All protocol instances listed")
    }

    @AfterAll
    fun teardown() {
        println("\n╔════════════════════════════════════════════════════════════════╗")
        println("║ NPL INTEGRATION TESTS (3-Layer Policy Model) - Complete       ║")
        println("╚════════════════════════════════════════════════════════════════╝")
        if (::client.isInitialized) {
            client.close()
        }
    }
}
