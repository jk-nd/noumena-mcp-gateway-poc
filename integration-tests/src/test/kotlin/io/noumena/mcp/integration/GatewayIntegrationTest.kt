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
 * Integration tests for the MCP Gateway — V2 Transparent Proxy.
 * 
 * Tests Gateway endpoints and proxy behavior:
 * - Health check
 * - Admin services endpoint (namespaced tools)
 * - MCP HTTP POST endpoint
 * - MCP WebSocket endpoint availability
 * - OAuth discovery endpoints
 * 
 * Prerequisites:
 * - Docker stack must be running: docker compose -f deployments/docker-compose.yml up -d
 * - Gateway must be built and running
 * 
 * Run with: ./gradlew :integration-tests:test --tests "*GatewayIntegrationTest*"
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class GatewayIntegrationTest {
    
    private lateinit var token: String
    private lateinit var adminToken: String
    private lateinit var client: HttpClient
    
    @BeforeAll
    fun setup() = runBlocking {
        println("╔════════════════════════════════════════════════════════════════╗")
        println("║ GATEWAY INTEGRATION TESTS (V2 Proxy) - Setup                  ║")
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
        
        // Get authentication tokens
        token = KeycloakAuth.getToken()
        adminToken = KeycloakAuth.getToken("admin", "Welcome123")
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
    fun `admin services endpoint returns namespaced tools`() = runBlocking {
        println("\n┌─────────────────────────────────────────────────────────────┐")
        println("│ TEST: Admin Services Endpoint                              │")
        println("└─────────────────────────────────────────────────────────────┘")
        
        val response = client.get("${TestConfig.gatewayUrl}/admin/services") {
            header("Authorization", "Bearer $adminToken")
        }
        
        println("    Status: ${response.status}")
        val body = response.bodyAsText()
        println("    Body: ${body.take(300)}")
        
        assertTrue(response.status.isSuccess(), "Admin services endpoint should succeed")
        
        val json = TestClient.json.parseToJsonElement(body).jsonObject
        val services = json["services"]?.jsonArray
        assertNotNull(services, "Should have services array")
        
        // V2: tools should be namespaced
        services?.forEach { svc ->
            val svcName = svc.jsonObject["name"]?.jsonPrimitive?.content ?: ""
            val tools = svc.jsonObject["tools"]?.jsonArray
            tools?.forEach { tool ->
                val toolName = tool.jsonObject["name"]?.jsonPrimitive?.content ?: ""
                assertTrue(toolName.startsWith("$svcName."),
                    "Tool '$toolName' should be namespaced with service '$svcName'")
            }
        }
        
        println("    ✓ Admin services endpoint returns namespaced tools")
    }
    
    @Test
    @Order(3)
    fun `MCP HTTP POST endpoint requires authentication`() = runBlocking {
        println("\n┌─────────────────────────────────────────────────────────────┐")
        println("│ TEST: MCP HTTP POST Requires Auth                          │")
        println("└─────────────────────────────────────────────────────────────┘")
        
        // Request without token should be rejected
        val response = client.post("${TestConfig.gatewayUrl}/mcp") {
            contentType(ContentType.Application.Json)
            setBody("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}""")
        }
        
        println("    Status (no token): ${response.status}")
        assertEquals(HttpStatusCode.Unauthorized, response.status,
            "MCP endpoint should require authentication")
        
        println("    ✓ MCP endpoint correctly requires authentication")
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
        assertTrue(
            response.status == HttpStatusCode.BadRequest ||
            response.status == HttpStatusCode.UpgradeRequired ||
            response.status.value in 400..499,
            "WebSocket endpoint should reject plain HTTP"
        )
        
        println("    ✓ MCP WebSocket endpoint is configured")
    }
    
    @Test
    @Order(5)
    fun `OAuth discovery endpoints available`() = runBlocking {
        println("\n┌─────────────────────────────────────────────────────────────┐")
        println("│ TEST: OAuth Discovery Endpoints                            │")
        println("└─────────────────────────────────────────────────────────────┘")
        
        // Protected Resource Metadata (RFC 9728)
        val prmResponse = client.get("${TestConfig.gatewayUrl}/.well-known/oauth-protected-resource")
        println("    Protected Resource Metadata: ${prmResponse.status}")
        assertTrue(prmResponse.status.isSuccess(), "PRM endpoint should be available")
        
        // Authorization Server Metadata (RFC 8414)
        val asmResponse = client.get("${TestConfig.gatewayUrl}/.well-known/oauth-authorization-server")
        println("    Auth Server Metadata: ${asmResponse.status}")
        assertTrue(asmResponse.status.isSuccess(), "ASM endpoint should be available")
        
        println("    ✓ OAuth discovery endpoints are available")
    }
    
    @AfterAll
    fun teardown() {
        println("\n╔════════════════════════════════════════════════════════════════╗")
        println("║ GATEWAY INTEGRATION TESTS (V2) - Complete                     ║")
        println("╚════════════════════════════════════════════════════════════════╝")
        if (::client.isInitialized) {
            client.close()
        }
    }
}
