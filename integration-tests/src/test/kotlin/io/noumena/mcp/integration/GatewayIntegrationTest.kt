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
 * Integration tests for the Envoy AI Gateway.
 *
 * Tests Gateway endpoints and proxy behavior:
 * - Health check (Envoy Lua /health endpoint)
 * - MCP HTTP POST endpoint authentication
 * - OAuth discovery endpoints
 * - WWW-Authenticate header on 401
 *
 * Prerequisites:
 * - Docker stack must be running: docker compose -f deployments/docker-compose.yml up -d
 *
 * Run with: ./gradlew :integration-tests:test --tests "*GatewayIntegrationTest*"
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class GatewayIntegrationTest {

    private lateinit var token: String
    private lateinit var client: HttpClient

    @BeforeAll
    fun setup() = runBlocking {
        println("╔════════════════════════════════════════════════════════════════╗")
        println("║ GATEWAY INTEGRATION TESTS (Envoy) - Setup                     ║")
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
        assertEquals("healthy", json["status"]?.jsonPrimitive?.content)
        assertEquals("envoy-mcp-gateway", json["service"]?.jsonPrimitive?.content)

        println("    ✓ Gateway is healthy")
    }

    @Test
    @Order(2)
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
    @Order(3)
    fun `MCP 401 includes WWW-Authenticate header`() = runBlocking {
        println("\n┌─────────────────────────────────────────────────────────────┐")
        println("│ TEST: MCP 401 Includes WWW-Authenticate Header            │")
        println("└─────────────────────────────────────────────────────────────┘")

        val response = client.post("${TestConfig.gatewayUrl}/mcp") {
            contentType(ContentType.Application.Json)
            setBody("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}""")
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)

        val wwwAuth = response.headers["WWW-Authenticate"]
        println("    WWW-Authenticate: $wwwAuth")

        assertNotNull(wwwAuth, "401 response should include WWW-Authenticate header")
        assertTrue(wwwAuth!!.contains("Bearer"), "WWW-Authenticate should specify Bearer scheme")
        assertTrue(wwwAuth.contains("resource_metadata"), "WWW-Authenticate should include resource_metadata URL")

        println("    ✓ WWW-Authenticate header present with Bearer resource_metadata")
    }

    @Test
    @Order(4)
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
        println("║ GATEWAY INTEGRATION TESTS (Envoy) - Complete                  ║")
        println("╚════════════════════════════════════════════════════════════════╝")
        if (::client.isInitialized) {
            client.close()
        }
    }
}
