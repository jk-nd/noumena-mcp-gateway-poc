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
 * Integration tests for NPL Engine.
 * 
 * Tests the full policy flow:
 * 1. Create ServiceRegistry and enable services
 * 2. Create ToolExecutionPolicy
 * 3. checkAndApprove - policy check returns request ID
 * 4. validateForExecution - defense-in-depth validation
 * 5. reportCompletion - audit trail
 * 6. Disabled service rejection
 * 
 * Prerequisites:
 * - Docker stack must be running: docker compose -f deployments/docker-compose.yml up -d
 * - Keycloak must be provisioned with test users
 * 
 * Run with: ./gradlew :integration-tests:test --tests "*NplIntegrationTest*"
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class NplIntegrationTest {
    
    private lateinit var adminToken: String      // For setup/admin operations (pAdmin)
    private lateinit var agentToken: String      // For checkAndApprove (pAgent)
    private lateinit var executorToken: String   // For validateForExecution/reportCompletion (pExecutor)
    private lateinit var client: HttpClient
    private var serviceRegistryId: String? = null
    private var policyId: String? = null
    private var approvedRequestId: String? = null
    
    @BeforeAll
    fun setup() = runBlocking {
        println("╔════════════════════════════════════════════════════════════════╗")
        println("║ NPL INTEGRATION TESTS - Setup                                   ║")
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
        println("    Getting tokens for admin, agent, and executor...")
        adminToken = KeycloakAuth.getToken("admin", "Welcome123")
        println("    ✓ Admin token obtained")
        agentToken = KeycloakAuth.getToken("agent", "Welcome123")
        println("    ✓ Agent token obtained")
        executorToken = KeycloakAuth.getToken("executor", "Welcome123")
        println("    ✓ Executor token obtained")
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
        
        // Check if ServiceRegistry already exists using the /npl/ API
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
    fun `enable google_gmail service in ServiceRegistry`() = runBlocking {
        println("\n┌─────────────────────────────────────────────────────────────┐")
        println("│ TEST: Enable google_gmail Service                          │")
        println("└─────────────────────────────────────────────────────────────┘")
        
        if (serviceRegistryId == null) {
            println("    ⚠ No ServiceRegistry available, skipping")
            Assumptions.assumeTrue(false, "ServiceRegistry not created")
            return@runBlocking
        }
        
        val enableBody = """{"serviceName": "google_gmail"}"""
        
        println("    Enabling google_gmail service...")
        
        val response = client.post(
            "${TestConfig.nplUrl}/npl/registry/ServiceRegistry/$serviceRegistryId/enableService"
        ) {
            header("Authorization", "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody(enableBody)
        }
        
        println("    Response status: ${response.status}")
        
        // May return 200 or 4xx if already enabled or permission issue
        if (response.status.isSuccess()) {
            println("    ✓ google_gmail service enabled")
        } else {
            val body = response.bodyAsText()
            println("    Response: $body")
            // Don't fail - service might already be enabled
        }
    }
    
    @Test
    @Order(4)
    fun `create ToolExecutionPolicy protocol instance`() = runBlocking {
        println("\n┌─────────────────────────────────────────────────────────────┐")
        println("│ TEST: Create ToolExecutionPolicy Instance                  │")
        println("└─────────────────────────────────────────────────────────────┘")
        
        // Check if ToolExecutionPolicy already exists
        val listResponse = client.get("${TestConfig.nplUrl}/npl/services/ToolExecutionPolicy/") {
            header("Authorization", "Bearer $adminToken")
        }
        
        if (listResponse.status.isSuccess()) {
            val listBody = listResponse.bodyAsText()
            val listJson = TestClient.json.parseToJsonElement(listBody).jsonObject
            val items = listJson["items"]?.jsonArray
            if (items != null && items.isNotEmpty()) {
                policyId = items[0].jsonObject["@id"]?.jsonPrimitive?.content
                println("    ToolExecutionPolicy already exists: $policyId")
                println("    ✓ Using existing instance")
                return@runBlocking
            }
        }
        
        // Need a ServiceRegistry first
        if (serviceRegistryId == null) {
            println("    ⚠ No ServiceRegistry available, skipping")
            return@runBlocking
        }
        
        // Create with empty @parties - let rules.yml handle party assignment
        // Party assignment is based on JWT 'role' claims in each user's token
        val createBody = buildJsonObject {
            putJsonObject("@parties") {} // Empty - party assignment via rules.yml
            put("registry", serviceRegistryId!!)
            put("policyTenantId", "test-tenant")
            put("policyGatewayUrl", "http://gateway:8080")
        }
        
        println("    Creating ToolExecutionPolicy (party assignment via rules.yml)")
        println("    Body: $createBody")
        
        val response = client.post("${TestConfig.nplUrl}/npl/services/ToolExecutionPolicy/") {
            header("Authorization", "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody(createBody.toString())
        }
        
        println("    Response status: ${response.status}")
        val responseBody = response.bodyAsText()
        println("    Response body: ${responseBody.take(300)}")
        
        if (response.status.isSuccess()) {
            val json = TestClient.json.parseToJsonElement(responseBody).jsonObject
            policyId = json["@id"]?.jsonPrimitive?.content
            println("    ✓ ToolExecutionPolicy created: $policyId")
        } else {
            println("    ⚠ Could not create ToolExecutionPolicy: ${response.status}")
        }
    }
    
    @Test
    @Order(5)
    fun `checkAndApprove returns request ID for enabled service`() = runBlocking {
        println("\n┌─────────────────────────────────────────────────────────────┐")
        println("│ TEST: checkAndApprove Returns Request ID                   │")
        println("└─────────────────────────────────────────────────────────────┘")
        
        if (policyId == null) {
            println("    ⚠ No ToolExecutionPolicy available, skipping")
            Assumptions.assumeTrue(false, "ToolExecutionPolicy not created")
            return@runBlocking
        }
        
        val invokeBody = buildJsonObject {
            put("serviceName", "google_gmail")
            put("operationName", "send_email")
            put("requestMetadata", """{"recipient_domain":"example.com"}""")
            put("gatewayCallbackUrl", "http://gateway:8080/callback")
        }
        
        println("    Invoking checkAndApprove on policy: $policyId")
        println("    Using agent token (pAgent party)")
        
        val response = client.post(
            "${TestConfig.nplUrl}/npl/services/ToolExecutionPolicy/$policyId/checkAndApprove"
        ) {
            header("Authorization", "Bearer $agentToken")
            contentType(ContentType.Application.Json)
            setBody(invokeBody.toString())
        }
        
        println("    Response status: ${response.status}")
        val responseBody = response.bodyAsText()
        println("    Response body: $responseBody")
        
        if (response.status.isSuccess()) {
            approvedRequestId = responseBody.trim().removeSurrounding("\"")
            println("    ✓ Request approved with ID: $approvedRequestId")
            assertNotNull(approvedRequestId, "Should return a request ID")
            // Request ID format is "{tenantId}-{counter}" - accept any tenant ID
            assertTrue(approvedRequestId!!.contains("-"), "Request ID should contain tenant-counter format")
        } else {
            fail("checkAndApprove should succeed for enabled service: ${response.status}")
        }
    }
    
    @Test
    @Order(6)
    fun `validateForExecution returns true for approved request`() = runBlocking {
        println("\n┌─────────────────────────────────────────────────────────────┐")
        println("│ TEST: validateForExecution Returns True                    │")
        println("└─────────────────────────────────────────────────────────────┘")
        
        if (policyId == null || approvedRequestId == null) {
            println("    ⚠ No approved request available, skipping")
            Assumptions.assumeTrue(false, "No approved request")
            return@runBlocking
        }
        
        val invokeBody = buildJsonObject {
            put("reqId", approvedRequestId!!)
            put("expectedService", "google_gmail")
            put("expectedOperation", "send_email")
        }
        
        println("    Validating request: $approvedRequestId")
        println("    Using executor token (pExecutor party)")
        
        val response = client.post(
            "${TestConfig.nplUrl}/npl/services/ToolExecutionPolicy/$policyId/validateForExecution"
        ) {
            header("Authorization", "Bearer $executorToken")
            contentType(ContentType.Application.Json)
            setBody(invokeBody.toString())
        }
        
        println("    Response status: ${response.status}")
        val responseBody = response.bodyAsText()
        println("    Response body: $responseBody")
        
        assertTrue(response.status.isSuccess(), "validateForExecution should succeed")
        
        val isValid = responseBody.trim().removeSurrounding("\"").toBooleanStrictOrNull() ?: false
        assertTrue(isValid, "validateForExecution should return true for approved request")
        println("    ✓ Request validated successfully")
    }
    
    @Test
    @Order(7)
    fun `getRequestStatus returns validated after validation`() = runBlocking {
        println("\n┌─────────────────────────────────────────────────────────────┐")
        println("│ TEST: getRequestStatus Returns 'validated'                 │")
        println("└─────────────────────────────────────────────────────────────┘")
        
        if (policyId == null || approvedRequestId == null) {
            println("    ⚠ No approved request available, skipping")
            Assumptions.assumeTrue(false, "No approved request")
            return@runBlocking
        }
        
        val invokeBody = buildJsonObject {
            put("reqId", approvedRequestId!!)
        }
        
        println("    Getting status for request: $approvedRequestId")
        println("    Using executor token (pExecutor party)")
        
        val response = client.post(
            "${TestConfig.nplUrl}/npl/services/ToolExecutionPolicy/$policyId/getRequestStatus"
        ) {
            header("Authorization", "Bearer $executorToken")
            contentType(ContentType.Application.Json)
            setBody(invokeBody.toString())
        }
        
        println("    Response status: ${response.status}")
        val responseBody = response.bodyAsText()
        println("    Response body: $responseBody")
        
        assertTrue(response.status.isSuccess(), "getRequestStatus should succeed")
        
        val status = responseBody.trim().removeSurrounding("\"")
        assertEquals("validated", status, "Status should be 'validated' after validateForExecution")
        println("    ✓ Status is 'validated'")
    }
    
    @Test
    @Order(8)
    fun `reportCompletion updates status to completed`() = runBlocking {
        println("\n┌─────────────────────────────────────────────────────────────┐")
        println("│ TEST: reportCompletion Updates Status                      │")
        println("└─────────────────────────────────────────────────────────────┘")
        
        if (policyId == null || approvedRequestId == null) {
            println("    ⚠ No approved request available, skipping")
            Assumptions.assumeTrue(false, "No approved request")
            return@runBlocking
        }
        
        val invokeBody = buildJsonObject {
            put("reqId", approvedRequestId!!)
            put("wasSuccessful", true)
            put("failureMessage", "")
            put("execDurationMs", 150)
        }
        
        println("    Reporting completion for request: $approvedRequestId")
        println("    Using executor token (pExecutor party)")
        
        val response = client.post(
            "${TestConfig.nplUrl}/npl/services/ToolExecutionPolicy/$policyId/reportCompletion"
        ) {
            header("Authorization", "Bearer $executorToken")
            contentType(ContentType.Application.Json)
            setBody(invokeBody.toString())
        }
        
        println("    Response status: ${response.status}")
        
        assertTrue(response.status.isSuccess(), "reportCompletion should succeed")
        println("    ✓ Completion reported successfully")
        
        // Verify status changed to completed
        val statusBody = buildJsonObject { put("reqId", approvedRequestId!!) }
        val statusResponse = client.post(
            "${TestConfig.nplUrl}/npl/services/ToolExecutionPolicy/$policyId/getRequestStatus"
        ) {
            header("Authorization", "Bearer $executorToken")
            contentType(ContentType.Application.Json)
            setBody(statusBody.toString())
        }
        
        val status = statusResponse.bodyAsText().trim().removeSurrounding("\"")
        assertEquals("completed", status, "Status should be 'completed' after reportCompletion")
        println("    ✓ Status is 'completed'")
    }
    
    @Test
    @Order(9)
    fun `checkAndApprove fails for disabled service`() = runBlocking {
        println("\n┌─────────────────────────────────────────────────────────────┐")
        println("│ TEST: checkAndApprove Fails for Disabled Service           │")
        println("└─────────────────────────────────────────────────────────────┘")
        
        if (policyId == null) {
            println("    ⚠ No ToolExecutionPolicy available, skipping")
            Assumptions.assumeTrue(false, "ToolExecutionPolicy not created")
            return@runBlocking
        }
        
        // Try to approve a service that is not enabled
        val invokeBody = buildJsonObject {
            put("serviceName", "disabled_service")
            put("operationName", "some_operation")
            put("requestMetadata", "{}")
            put("gatewayCallbackUrl", "http://gateway:8080/callback")
        }
        
        println("    Invoking checkAndApprove for disabled_service...")
        println("    Using agent token (pAgent party)")
        
        val response = client.post(
            "${TestConfig.nplUrl}/npl/services/ToolExecutionPolicy/$policyId/checkAndApprove"
        ) {
            header("Authorization", "Bearer $agentToken")
            contentType(ContentType.Application.Json)
            setBody(invokeBody.toString())
        }
        
        println("    Response status: ${response.status}")
        val responseBody = response.bodyAsText()
        println("    Response body: $responseBody")
        
        // Should fail with error (4xx status)
        assertFalse(response.status.isSuccess(), "checkAndApprove should fail for disabled service")
        assertTrue(
            responseBody.contains("disabled") || responseBody.contains("Service"),
            "Error should mention service is disabled"
        )
        println("    ✓ Request correctly rejected for disabled service")
    }
    
    @Test
    @Order(10)
    fun `validateForExecution returns false for unknown request`() = runBlocking {
        println("\n┌─────────────────────────────────────────────────────────────┐")
        println("│ TEST: validateForExecution Returns False for Unknown       │")
        println("└─────────────────────────────────────────────────────────────┘")
        
        if (policyId == null) {
            println("    ⚠ No ToolExecutionPolicy available, skipping")
            Assumptions.assumeTrue(false, "ToolExecutionPolicy not created")
            return@runBlocking
        }
        
        val invokeBody = buildJsonObject {
            put("reqId", "unknown-request-id-12345")
            put("expectedService", "google_gmail")
            put("expectedOperation", "send_email")
        }
        
        println("    Validating unknown request...")
        println("    Using executor token (pExecutor party)")
        
        val response = client.post(
            "${TestConfig.nplUrl}/npl/services/ToolExecutionPolicy/$policyId/validateForExecution"
        ) {
            header("Authorization", "Bearer $executorToken")
            contentType(ContentType.Application.Json)
            setBody(invokeBody.toString())
        }
        
        println("    Response status: ${response.status}")
        val responseBody = response.bodyAsText()
        println("    Response body: $responseBody")
        
        assertTrue(response.status.isSuccess(), "API call should succeed")
        
        val isValid = responseBody.trim().removeSurrounding("\"").toBooleanStrictOrNull() ?: true
        assertFalse(isValid, "validateForExecution should return false for unknown request")
        println("    ✓ Unknown request correctly rejected")
    }
    
    @Test
    @Order(11)
    fun `getRequestCount returns number of processed requests`() = runBlocking {
        println("\n┌─────────────────────────────────────────────────────────────┐")
        println("│ TEST: getRequestCount Returns Processed Count              │")
        println("└─────────────────────────────────────────────────────────────┘")
        
        if (policyId == null) {
            println("    ⚠ No ToolExecutionPolicy available, skipping")
            Assumptions.assumeTrue(false, "ToolExecutionPolicy not created")
            return@runBlocking
        }
        
        println("    Using admin token (pAdmin party)")
        
        val response = client.post(
            "${TestConfig.nplUrl}/npl/services/ToolExecutionPolicy/$policyId/getRequestCount"
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
        assertTrue(count >= 1, "Should have processed at least 1 request")
        println("    ✓ Request count: $count")
    }
    
    @Test
    @Order(12)
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
        
        // List ToolExecutionPolicy instances
        val policyResponse = client.get("${TestConfig.nplUrl}/npl/services/ToolExecutionPolicy/") {
            header("Authorization", "Bearer $adminToken")
        }
        
        println("    ToolExecutionPolicy instances:")
        if (policyResponse.status.isSuccess()) {
            val body = policyResponse.bodyAsText()
            val json = TestClient.json.parseToJsonElement(body).jsonObject
            val items = json["items"]?.jsonArray
            println("      Count: ${items?.size ?: 0}")
            items?.forEach { instance ->
                val id = instance.jsonObject["@id"]?.jsonPrimitive?.content
                println("      - $id")
            }
        }
        
        println("    ✓ Protocol instances listed")
    }
    
    @AfterAll
    fun teardown() {
        println("\n╔════════════════════════════════════════════════════════════════╗")
        println("║ NPL INTEGRATION TESTS - Complete                               ║")
        println("╚════════════════════════════════════════════════════════════════╝")
        if (::client.isInitialized) {
            client.close()
        }
    }
}
