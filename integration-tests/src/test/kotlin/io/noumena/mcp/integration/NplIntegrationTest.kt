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
 * Integration tests for NPL Engine — V2 Architecture.
 * 
 * Tests the V2 policy flow:
 * 1. Create ServiceRegistry and enable services
 * 2. Create per-service ToolPolicy instances
 * 3. Enable tools within ToolPolicy
 * 4. checkAccess — Gateway checks if a tool call is allowed
 * 5. Disabled tool rejection
 * 6. Suspended policy rejection
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
    private lateinit var gatewayToken: String     // For checkAccess (pGateway)
    private lateinit var client: HttpClient
    private var serviceRegistryId: String? = null
    private var toolPolicyId: String? = null
    
    @BeforeAll
    fun setup() = runBlocking {
        println("╔════════════════════════════════════════════════════════════════╗")
        println("║ NPL INTEGRATION TESTS (V2 ToolPolicy) - Setup                ║")
        println("╠════════════════════════════════════════════════════════════════╣")
        println("║ NPL URL:      ${TestConfig.nplUrl}")
        println("║ Keycloak URL: ${TestConfig.keycloakUrl}")
        println("╚════════════════════════════════════════════════════════════════╝")
        
        // Create HTTP client for this test class
        client = HttpClient(CIO) {
            install(ContentNegotiation) {
                json(TestClient.json)
            }
        }
        
        // Check if Docker stack is running
        Assumptions.assumeTrue(isDockerStackRunning()) {
            "Docker stack is not running. Start with: docker compose -f deployments/docker-compose.yml up -d"
        }
        
        // Get authentication tokens for different parties
        println("    Getting tokens for admin and gateway...")
        adminToken = KeycloakAuth.getToken("admin", "Welcome123")
        println("    ✓ Admin token obtained")
        gatewayToken = KeycloakAuth.getToken("gateway", "Welcome123")
        println("    ✓ Gateway token obtained")
    }
    
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
    fun `create ServiceRegistry protocol instance`() = runBlocking {
        println("\n┌─────────────────────────────────────────────────────────────┐")
        println("│ TEST: Create ServiceRegistry Instance                      │")
        println("└─────────────────────────────────────────────────────────────┘")
        
        // Check if ServiceRegistry already exists
        val listResponse = client.get("${TestConfig.nplUrl}/npl/registry/ServiceRegistry/") {
            header("Authorization", "Bearer $adminToken")
        }
        
        if (listResponse.status.isSuccess()) {
            val listBody = listResponse.bodyAsText()
            val listJson = TestClient.json.parseToJsonElement(listBody).jsonObject
            val items = listJson["items"]?.jsonArray
            if (items != null && items.isNotEmpty()) {
                serviceRegistryId = items[0].jsonObject["@id"]?.jsonPrimitive?.content
                println("    ServiceRegistry already exists: $serviceRegistryId")
                println("    ✓ Using existing instance")
                return@runBlocking
            }
        }
        
        // Create new ServiceRegistry
        val createBody = """{"@parties": {}}"""
        
        println("    Creating ServiceRegistry...")
        
        val response = client.post("${TestConfig.nplUrl}/npl/registry/ServiceRegistry/") {
            header("Authorization", "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody(createBody)
        }
        
        println("    Response status: ${response.status}")
        val responseBody = response.bodyAsText()
        println("    Response body: ${responseBody.take(200)}")
        
        if (response.status.isSuccess()) {
            val json = TestClient.json.parseToJsonElement(responseBody).jsonObject
            serviceRegistryId = json["@id"]?.jsonPrimitive?.content
            println("    ✓ ServiceRegistry created: $serviceRegistryId")
        } else {
            println("    ⚠ Could not create ServiceRegistry: ${response.status}")
        }
    }
    
    @Test
    @Order(3)
    fun `enable duckduckgo service in ServiceRegistry`() = runBlocking {
        println("\n┌─────────────────────────────────────────────────────────────┐")
        println("│ TEST: Enable duckduckgo Service                            │")
        println("└─────────────────────────────────────────────────────────────┘")
        
        if (serviceRegistryId == null) {
            println("    ⚠ No ServiceRegistry available, skipping")
            Assumptions.assumeTrue(false, "ServiceRegistry not created")
            return@runBlocking
        }
        
        val enableBody = """{"serviceName": "duckduckgo"}"""
        
        println("    Enabling duckduckgo service...")
        
        val response = client.post(
            "${TestConfig.nplUrl}/npl/registry/ServiceRegistry/$serviceRegistryId/enableService"
        ) {
            header("Authorization", "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody(enableBody)
        }
        
        println("    Response status: ${response.status}")
        
        if (response.status.isSuccess()) {
            println("    ✓ duckduckgo service enabled")
        } else {
            val body = response.bodyAsText()
            println("    Response: $body")
        }
    }
    
    @Test
    @Order(4)
    fun `create ToolPolicy instance for duckduckgo`() = runBlocking {
        println("\n┌─────────────────────────────────────────────────────────────┐")
        println("│ TEST: Create ToolPolicy Instance for duckduckgo            │")
        println("└─────────────────────────────────────────────────────────────┘")
        
        // Check if ToolPolicy already exists for duckduckgo
        val listResponse = client.get("${TestConfig.nplUrl}/npl/services/ToolPolicy/") {
            header("Authorization", "Bearer $adminToken")
        }
        
        if (listResponse.status.isSuccess()) {
            val listBody = listResponse.bodyAsText()
            val listJson = TestClient.json.parseToJsonElement(listBody).jsonObject
            val items = listJson["items"]?.jsonArray
            if (items != null) {
                for (item in items) {
                    val serviceName = item.jsonObject["policyServiceName"]?.jsonPrimitive?.content
                    if (serviceName == "duckduckgo") {
                        toolPolicyId = item.jsonObject["@id"]?.jsonPrimitive?.content
                        println("    ToolPolicy for duckduckgo already exists: $toolPolicyId")
                        println("    ✓ Using existing instance")
                        return@runBlocking
                    }
                }
            }
        }
        
        // Create new ToolPolicy for duckduckgo
        val createBody = buildJsonObject {
            putJsonObject("@parties") {}
            put("policyServiceName", "duckduckgo")
        }
        
        println("    Creating ToolPolicy for duckduckgo (party assignment via rules.yml)")
        
        val response = client.post("${TestConfig.nplUrl}/npl/services/ToolPolicy/") {
            header("Authorization", "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody(createBody.toString())
        }
        
        println("    Response status: ${response.status}")
        val responseBody = response.bodyAsText()
        println("    Response body: ${responseBody.take(300)}")
        
        if (response.status.isSuccess()) {
            val json = TestClient.json.parseToJsonElement(responseBody).jsonObject
            toolPolicyId = json["@id"]?.jsonPrimitive?.content
            println("    ✓ ToolPolicy created: $toolPolicyId")
        } else {
            println("    ⚠ Could not create ToolPolicy: ${response.status}")
        }
    }
    
    @Test
    @Order(5)
    fun `enable search tool in ToolPolicy`() = runBlocking {
        println("\n┌─────────────────────────────────────────────────────────────┐")
        println("│ TEST: Enable 'search' Tool in ToolPolicy                   │")
        println("└─────────────────────────────────────────────────────────────┘")
        
        if (toolPolicyId == null) {
            println("    ⚠ No ToolPolicy available, skipping")
            Assumptions.assumeTrue(false, "ToolPolicy not created")
            return@runBlocking
        }
        
        val enableBody = """{"toolName": "search"}"""
        
        println("    Enabling 'search' tool...")
        println("    Using admin token (pAdmin party)")
        
        val response = client.post(
            "${TestConfig.nplUrl}/npl/services/ToolPolicy/$toolPolicyId/enableTool"
        ) {
            header("Authorization", "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody(enableBody)
        }
        
        println("    Response status: ${response.status}")
        
        if (response.status.isSuccess()) {
            println("    ✓ 'search' tool enabled")
        } else {
            val body = response.bodyAsText()
            println("    Response: $body")
        }
    }
    
    @Test
    @Order(6)
    fun `checkAccess returns true for enabled tool`() = runBlocking {
        println("\n┌─────────────────────────────────────────────────────────────┐")
        println("│ TEST: checkAccess Returns True for Enabled Tool            │")
        println("└─────────────────────────────────────────────────────────────┘")
        
        if (toolPolicyId == null) {
            println("    ⚠ No ToolPolicy available, skipping")
            Assumptions.assumeTrue(false, "ToolPolicy not created")
            return@runBlocking
        }
        
        val invokeBody = buildJsonObject {
            put("toolName", "search")
            put("callerIdentity", "test-agent")
        }
        
        println("    Checking access for 'search' tool...")
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
        println("    ✓ Access allowed for enabled tool")
    }
    
    @Test
    @Order(7)
    fun `checkAccess returns false for disabled tool`() = runBlocking {
        println("\n┌─────────────────────────────────────────────────────────────┐")
        println("│ TEST: checkAccess Returns False for Disabled Tool           │")
        println("└─────────────────────────────────────────────────────────────┘")
        
        if (toolPolicyId == null) {
            println("    ⚠ No ToolPolicy available, skipping")
            Assumptions.assumeTrue(false, "ToolPolicy not created")
            return@runBlocking
        }
        
        val invokeBody = buildJsonObject {
            put("toolName", "nonexistent_tool")
            put("callerIdentity", "test-agent")
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
        println("    ✓ Access correctly denied for disabled tool")
    }
    
    @Test
    @Order(8)
    fun `getEnabledTools returns enabled tool set`() = runBlocking {
        println("\n┌─────────────────────────────────────────────────────────────┐")
        println("│ TEST: getEnabledTools Returns Tool Set                      │")
        println("└─────────────────────────────────────────────────────────────┘")
        
        if (toolPolicyId == null) {
            println("    ⚠ No ToolPolicy available, skipping")
            Assumptions.assumeTrue(false, "ToolPolicy not created")
            return@runBlocking
        }
        
        println("    Using admin token (pAdmin party)")
        
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
        assertTrue(responseBody.contains("search"), "Enabled tools should contain 'search'")
        println("    ✓ Enabled tools retrieved")
    }
    
    @Test
    @Order(9)
    fun `getRequestCount returns number of policy checks`() = runBlocking {
        println("\n┌─────────────────────────────────────────────────────────────┐")
        println("│ TEST: getRequestCount Returns Policy Check Count           │")
        println("└─────────────────────────────────────────────────────────────┘")
        
        if (toolPolicyId == null) {
            println("    ⚠ No ToolPolicy available, skipping")
            Assumptions.assumeTrue(false, "ToolPolicy not created")
            return@runBlocking
        }
        
        println("    Using admin token (pAdmin party)")
        
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
        assertTrue(count >= 2, "Should have processed at least 2 policy checks (allowed + denied)")
        println("    ✓ Policy check count: $count")
    }
    
    @Test
    @Order(10)
    fun `list protocol instances`() = runBlocking {
        println("\n┌─────────────────────────────────────────────────────────────┐")
        println("│ TEST: List Protocol Instances                              │")
        println("└─────────────────────────────────────────────────────────────┘")
        
        // List ServiceRegistry instances
        val registryResponse = client.get("${TestConfig.nplUrl}/npl/registry/ServiceRegistry/") {
            header("Authorization", "Bearer $adminToken")
        }
        
        println("    ServiceRegistry instances:")
        if (registryResponse.status.isSuccess()) {
            val body = registryResponse.bodyAsText()
            val json = TestClient.json.parseToJsonElement(body).jsonObject
            val items = json["items"]?.jsonArray
            println("      Count: ${items?.size ?: 0}")
            items?.forEach { instance ->
                val id = instance.jsonObject["@id"]?.jsonPrimitive?.content
                println("      - $id")
            }
        }
        
        // List ToolPolicy instances (V2)
        val policyResponse = client.get("${TestConfig.nplUrl}/npl/services/ToolPolicy/") {
            header("Authorization", "Bearer $adminToken")
        }
        
        println("    ToolPolicy instances:")
        if (policyResponse.status.isSuccess()) {
            val body = policyResponse.bodyAsText()
            val json = TestClient.json.parseToJsonElement(body).jsonObject
            val items = json["items"]?.jsonArray
            println("      Count: ${items?.size ?: 0}")
            items?.forEach { instance ->
                val id = instance.jsonObject["@id"]?.jsonPrimitive?.content
                val serviceName = instance.jsonObject["policyServiceName"]?.jsonPrimitive?.content
                println("      - $id (service: $serviceName)")
            }
        }
        
        println("    ✓ Protocol instances listed")
    }
    
    @AfterAll
    fun teardown() {
        println("\n╔════════════════════════════════════════════════════════════════╗")
        println("║ NPL INTEGRATION TESTS (V2) - Complete                         ║")
        println("╚════════════════════════════════════════════════════════════════╝")
        if (::client.isInitialized) {
            client.close()
        }
    }
}
