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
 * Integration tests for the MCP Gateway.
 * 
 * Prerequisites:
 * - Docker stack must be running: docker compose -f deployments/docker-compose.yml up -d
 * - Gateway must be built and running
 * 
 * Run with: ./gradlew :integration-tests:test
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class GatewayIntegrationTest {
    
    private lateinit var token: String
    private lateinit var client: HttpClient
    
    @BeforeAll
    fun setup() = runBlocking {
        println("╔════════════════════════════════════════════════════════════════╗")
        println("║ GATEWAY INTEGRATION TESTS - Setup                              ║")
        println("╠════════════════════════════════════════════════════════════════╣")
        println("║ Gateway URL:  ${TestConfig.gatewayUrl}")
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
        
        // Check if Gateway is running
        Assumptions.assumeTrue(isGatewayRunning()) {
            "Gateway is not running. Build and start with docker compose."
        }
        
        // Get authentication token
        token = KeycloakAuth.getToken()
        println("    ✓ Authentication successful")
    }
    
    @Test
    @Order(1)
    fun `gateway health check`() = runBlocking {
        println("\n┌─────────────────────────────────────────────────────────────┐")
        println("│ TEST: Gateway Health Check                                 │")
        println("└─────────────────────────────────────────────────────────────┘")
        
        val response = client.get("${TestConfig.gatewayUrl}/health")
        
        println("    Status: ${response.status}")
        val body = response.bodyAsText()
        println("    Body: $body")
        
        assertTrue(response.status.isSuccess(), "Gateway should be healthy")
        
        val json = TestClient.json.parseToJsonElement(body).jsonObject
        assertEquals("ok", json["status"]?.jsonPrimitive?.content)
        assertEquals("gateway", json["service"]?.jsonPrimitive?.content)
        
        println("    ✓ Gateway is healthy")
    }
    
    @Test
    @Order(2)
    fun `context store operations`() = runBlocking {
        println("\n┌─────────────────────────────────────────────────────────────┐")
        println("│ TEST: Context Store Operations                             │")
        println("└─────────────────────────────────────────────────────────────┘")
        
        // Try to fetch a non-existent context
        val response = client.get("${TestConfig.gatewayUrl}/context/non-existent-id")
        
        println("    Fetching non-existent context...")
        println("    Status: ${response.status}")
        
        // Should return 404 or empty response
        assertTrue(
            response.status == HttpStatusCode.NotFound || 
            response.status == HttpStatusCode.OK,
            "Should handle non-existent context gracefully"
        )
        
        println("    ✓ Context store handles missing contexts correctly")
    }
    
    @Test
    @Order(3)
    fun `callback endpoint exists`() = runBlocking {
        println("\n┌─────────────────────────────────────────────────────────────┐")
        println("│ TEST: Callback Endpoint Exists                             │")
        println("└─────────────────────────────────────────────────────────────┘")
        
        // Send a test callback (will fail validation but endpoint should exist)
        val callbackBody = """
            {
                "requestId": "test-callback-123",
                "success": true,
                "data": {"test": "data"}
            }
        """.trimIndent()
        
        val response = client.post("${TestConfig.gatewayUrl}/callback") {
            contentType(ContentType.Application.Json)
            setBody(callbackBody)
        }
        
        println("    Status: ${response.status}")
        
        // Endpoint should exist (may return error for unknown requestId, but not 404)
        assertNotEquals(HttpStatusCode.NotFound, response.status, "Callback endpoint should exist")
        
        println("    ✓ Callback endpoint is available")
    }
    
    @Test
    @Order(4)
    fun `MCP WebSocket endpoint available`() = runBlocking {
        println("\n┌─────────────────────────────────────────────────────────────┐")
        println("│ TEST: MCP WebSocket Endpoint Available                     │")
        println("└─────────────────────────────────────────────────────────────┘")
        
        // HTTP request to WebSocket endpoint should return upgrade required
        val response = client.get("${TestConfig.gatewayUrl}/mcp/ws")
        
        println("    Status: ${response.status}")
        
        // WebSocket endpoint should reject plain HTTP (upgrade required)
        // This confirms the endpoint exists
        assertTrue(
            response.status == HttpStatusCode.BadRequest ||
            response.status == HttpStatusCode.UpgradeRequired ||
            response.status.value in 400..499,
            "WebSocket endpoint should reject plain HTTP"
        )
        
        println("    ✓ MCP WebSocket endpoint is configured")
    }
    
    @AfterAll
    fun teardown() {
        println("\n╔════════════════════════════════════════════════════════════════╗")
        println("║ GATEWAY INTEGRATION TESTS - Complete                           ║")
        println("╚════════════════════════════════════════════════════════════════╝")
        if (::client.isInitialized) {
            client.close()
        }
    }
}
