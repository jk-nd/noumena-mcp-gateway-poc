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
 * Simplified credential injection E2E tests for the Envoy architecture.
 *
 * In the Envoy architecture, credentials are injected at supergateway sidecar startup
 * (not per-request). This test verifies that an authenticated user can list tools
 * through the gateway and that the credential proxy is healthy.
 *
 * Prerequisites:
 * - All services running (Envoy, Credential Proxy, NPL, Keycloak)
 *
 * Run with: ./gradlew :integration-tests:test --tests "*CredentialInjectionE2ETest*"
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class CredentialInjectionE2ETest {

    private lateinit var client: HttpClient
    private lateinit var token: String

    private val gatewayUrl = System.getenv("GATEWAY_URL") ?: "http://localhost:8000"
    private val credentialProxyUrl = System.getenv("CREDENTIAL_PROXY_URL") ?: "http://localhost:9002"

    @BeforeAll
    fun setup() = runBlocking {
        println("╔════════════════════════════════════════════════════════════════╗")
        println("║ CREDENTIAL INJECTION E2E TESTS - Setup                       ║")
        println("╠════════════════════════════════════════════════════════════════╣")
        println("║ Gateway:          $gatewayUrl")
        println("║ Credential Proxy: $credentialProxyUrl")
        println("╚════════════════════════════════════════════════════════════════╝")

        client = HttpClient(CIO) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        }

        // Use jarvis user who has tool access grants
        token = KeycloakAuth.getToken(TestConfig.jarvisUsername, TestConfig.defaultPassword)
        println("    ✓ Obtained JWT token for jarvis")

        // Verify credential proxy is ready
        try {
            val healthResponse = client.get("$credentialProxyUrl/health")
            assertEquals(HttpStatusCode.OK, healthResponse.status)
            println("    ✓ Credential Proxy is healthy")
        } catch (e: Exception) {
            println("    ⚠ Credential Proxy not reachable: ${e.message}")
            Assumptions.assumeTrue(false, "Credential Proxy is not running")
        }
    }

    @AfterAll
    fun teardown() {
        if (::client.isInitialized) {
            client.close()
        }
        println("    ✓ Credential injection E2E tests completed")
    }

    @Test
    @Order(1)
    fun `test tools list through gateway`() = runBlocking {
        println("\n[E2E TEST] Tools list through gateway")

        val response = client.post("$gatewayUrl/mcp") {
            header("Authorization", "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(buildJsonRpc(1, "tools/list"))
        }

        assertEquals(HttpStatusCode.OK, response.status)

        val result = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val tools = result["result"]?.jsonObject?.get("tools")?.jsonArray

        assertNotNull(tools, "Tools list should be present")
        assertTrue(tools!!.size > 0, "Should have at least one tool")

        val toolNames = tools.map { it.jsonObject["name"]?.jsonPrimitive?.content }
        println("    ✓ Found ${tools.size} tools: ${toolNames.joinToString(", ")}")
    }
}
