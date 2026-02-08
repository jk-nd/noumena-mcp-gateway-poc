package io.noumena.mcp.integration

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*

/**
 * Integration tests for Credential Injection via Credential Proxy.
 * 
 * Tests the full credential injection pipeline:
 * - Credential Proxy health and configuration
 * - Simple mode credential selection (YAML-based)
 * - Vault integration (fetch and cache)
 * - Field mapping and injection
 * - Gateway integration
 * 
 * Prerequisites:
 * - Docker stack running: docker compose up -d
 * - Vault seeded with test credentials
 * - Credential Proxy in SIMPLE mode
 * 
 * Run with: ./gradlew :integration-tests:test --tests "*CredentialInjectionTest*"
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class CredentialInjectionTest {
    
    private lateinit var client: HttpClient
    
    private val credentialProxyUrl = System.getenv("CREDENTIAL_PROXY_URL") ?: "http://localhost:9002"
    private val vaultUrl = System.getenv("VAULT_ADDR") ?: "http://localhost:8200"
    
    @Serializable
    data class HealthResponse(val status: String, val mode: String)
    
    @Serializable
    data class InjectCredentialsRequest(
        val service: String,
        val operation: String,
        val metadata: Map<String, String> = emptyMap(),
        val tenantId: String,
        val userId: String
    )
    
    @Serializable
    data class InjectCredentialsResponse(
        val credentialName: String,
        val injectedFields: Map<String, String>
    )
    
    @BeforeAll
    fun setup() {
        println("╔════════════════════════════════════════════════════════════════╗")
        println("║ CREDENTIAL INJECTION INTEGRATION TESTS - Setup                ║")
        println("╠════════════════════════════════════════════════════════════════╣")
        println("║ Credential Proxy: $credentialProxyUrl")
        println("║ Vault:            $vaultUrl")
        println("╚════════════════════════════════════════════════════════════════╝")
        
        client = HttpClient(CIO) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        }
    }
    
    @AfterAll
    fun teardown() {
        client.close()
        println("✓ Credential Injection tests completed")
    }
    
    @Test
    @Order(1)
    fun `test credential proxy health check`() = runBlocking {
        println("\n[TEST] Credential Proxy health check")
        
        val response = client.get("$credentialProxyUrl/health")
        
        assertEquals(HttpStatusCode.OK, response.status, "Health check should return 200 OK")
        
        val health = Json.decodeFromString<HealthResponse>(response.bodyAsText())
        assertEquals("ok", health.status, "Health status should be 'ok'")
        assertEquals("SIMPLE", health.mode, "Credential mode should be 'SIMPLE'")
        
        println("  ✓ Credential Proxy is healthy in SIMPLE mode")
    }
    
    @Test
    @Order(2)
    fun `test github credential injection`() = runBlocking {
        println("\n[TEST] GitHub credential injection")
        
        val request = InjectCredentialsRequest(
            service = "github",
            operation = "create_issue",
            tenantId = "default",
            userId = "alice"
        )
        
        val response = client.post("$credentialProxyUrl/inject-credentials") {
            contentType(ContentType.Application.Json)
            setBody(Json.encodeToString(InjectCredentialsRequest.serializer(), request))
        }
        
        assertEquals(HttpStatusCode.OK, response.status, "Injection should return 200 OK")
        
        val result = Json.decodeFromString<InjectCredentialsResponse>(response.bodyAsText())
        
        assertEquals("work_github", result.credentialName, "Should select work_github credential")
        assertTrue(result.injectedFields.containsKey("GITHUB_TOKEN"), "Should inject GITHUB_TOKEN")
        assertEquals("ghp_test_work_token_12345", result.injectedFields["GITHUB_TOKEN"], 
            "Should inject correct token value")
        
        println("  ✓ GitHub credential injected: ${result.credentialName}")
        println("    - GITHUB_TOKEN: ${result.injectedFields["GITHUB_TOKEN"]}")
    }
    
    @Test
    @Order(3)
    fun `test slack credential injection`() = runBlocking {
        println("\n[TEST] Slack credential injection")
        
        val request = InjectCredentialsRequest(
            service = "slack",
            operation = "send_message",
            tenantId = "default",
            userId = "alice"
        )
        
        val response = client.post("$credentialProxyUrl/inject-credentials") {
            contentType(ContentType.Application.Json)
            setBody(Json.encodeToString(InjectCredentialsRequest.serializer(), request))
        }
        
        assertEquals(HttpStatusCode.OK, response.status)
        
        val result = Json.decodeFromString<InjectCredentialsResponse>(response.bodyAsText())
        
        assertEquals("prod_slack", result.credentialName)
        assertTrue(result.injectedFields.containsKey("SLACK_TOKEN"))
        assertEquals("xoxb-test-slack-prod-token", result.injectedFields["SLACK_TOKEN"])
        
        println("  ✓ Slack credential injected: ${result.credentialName}")
    }
    
    @Test
    @Order(4)
    fun `test database credential injection with multiple fields`() = runBlocking {
        println("\n[TEST] Database credential injection (multiple fields)")
        
        val request = InjectCredentialsRequest(
            service = "database",
            operation = "query",
            tenantId = "default",
            userId = "alice"
        )
        
        val response = client.post("$credentialProxyUrl/inject-credentials") {
            contentType(ContentType.Application.Json)
            setBody(Json.encodeToString(InjectCredentialsRequest.serializer(), request))
        }
        
        assertEquals(HttpStatusCode.OK, response.status)
        
        val result = Json.decodeFromString<InjectCredentialsResponse>(response.bodyAsText())
        
        assertEquals("readonly_db", result.credentialName)
        assertTrue(result.injectedFields.containsKey("DB_USER"))
        assertTrue(result.injectedFields.containsKey("DB_PASS"))
        assertEquals("reader", result.injectedFields["DB_USER"])
        assertEquals("readonly123", result.injectedFields["DB_PASS"])
        
        println("  ✓ Database credential injected: ${result.credentialName}")
        println("    - DB_USER: ${result.injectedFields["DB_USER"]}")
        println("    - DB_PASS: ********")
    }
    
    @Test
    @Order(5)
    fun `test credential caching`() = runBlocking {
        println("\n[TEST] Credential caching (performance)")
        
        val request = InjectCredentialsRequest(
            service = "github",
            operation = "list_repos",
            tenantId = "default",
            userId = "alice"
        )
        
        // First call - fetch from Vault
        val start1 = System.currentTimeMillis()
        val response1 = client.post("$credentialProxyUrl/inject-credentials") {
            contentType(ContentType.Application.Json)
            setBody(Json.encodeToString(InjectCredentialsRequest.serializer(), request))
        }
        val duration1 = System.currentTimeMillis() - start1
        assertEquals(HttpStatusCode.OK, response1.status)
        
        // Second call - should be cached
        val start2 = System.currentTimeMillis()
        val response2 = client.post("$credentialProxyUrl/inject-credentials") {
            contentType(ContentType.Application.Json)
            setBody(Json.encodeToString(InjectCredentialsRequest.serializer(), request))
        }
        val duration2 = System.currentTimeMillis() - start2
        assertEquals(HttpStatusCode.OK, response2.status)
        
        // Cached call should be faster or equal
        assertTrue(duration2 <= duration1, "Cached credential fetch should be faster or equal")
        
        println("  ✓ Credential caching working:")
        println("    - First fetch:  ${duration1}ms")
        println("    - Cached fetch: ${duration2}ms")
    }
    
    @Test
    @Order(6)
    fun `test admin cache clear endpoint`() = runBlocking {
        println("\n[TEST] Admin cache clear endpoint")
        
        val response = client.post("$credentialProxyUrl/admin/clear-cache")
        
        assertEquals(HttpStatusCode.OK, response.status)
        
        val result = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("cache_cleared", result["status"]?.jsonPrimitive?.content)
        
        println("  ✓ Cache cleared successfully")
    }
    
    @Test
    @Order(7)
    fun `test admin config reload endpoint`() = runBlocking {
        println("\n[TEST] Admin config reload endpoint")
        
        val response = client.post("$credentialProxyUrl/admin/reload-config")
        
        assertEquals(HttpStatusCode.OK, response.status)
        
        val result = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("reloaded", result["status"]?.jsonPrimitive?.content)
        assertEquals("SIMPLE", result["mode"]?.jsonPrimitive?.content)
        
        println("  ✓ Configuration reloaded successfully")
    }
}
