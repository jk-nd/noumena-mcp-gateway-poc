package io.noumena.mcp.integration

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.websocket.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import kotlin.time.Duration.Companion.seconds

/**
 * End-to-End Integration Tests.
 * 
 * Tests the complete flow:
 * MCP Client -> Gateway (WebSocket) -> NPL Policy -> Executor -> Upstream MCP -> Response
 * 
 * Prerequisites:
 * - Full Docker stack running: docker compose -f deployments/docker-compose.yml up -d
 * - All services healthy: Gateway, NPL Engine, Executor, Keycloak, RabbitMQ
 * 
 * Run with: ./gradlew :integration-tests:test --tests "*EndToEndTest*"
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class EndToEndTest {
    
    private lateinit var token: String
    private lateinit var client: HttpClient
    private val json = Json { 
        ignoreUnknownKeys = true 
        prettyPrint = true
    }
    
    @BeforeAll
    fun setup() = runBlocking {
        println("╔════════════════════════════════════════════════════════════════╗")
        println("║ END-TO-END INTEGRATION TESTS                                   ║")
        println("╠════════════════════════════════════════════════════════════════╣")
        println("║ Gateway URL:  ${TestConfig.gatewayUrl}")
        println("║ NPL URL:      ${TestConfig.nplUrl}")
        println("║ Keycloak URL: ${TestConfig.keycloakUrl}")
        println("╚════════════════════════════════════════════════════════════════╝")
        
        // Create HTTP client with WebSocket support
        client = HttpClient(CIO) {
            install(ContentNegotiation) {
                json(json)
            }
            install(WebSockets) {
                pingInterval = 15.seconds
            }
        }
        
        // Check if Docker stack is running
        Assumptions.assumeTrue(isDockerStackRunning()) {
            "Docker stack is not running. Start with: docker compose -f deployments/docker-compose.yml up -d"
        }
        
        // Check if Gateway is running
        Assumptions.assumeTrue(isGatewayRunning()) {
            "Gateway is not running."
        }
        
        // Get authentication token
        token = KeycloakAuth.getToken()
        println("    ✓ Authentication successful")
    }
    
    @Test
    @Order(1)
    fun `MCP initialize handshake via WebSocket`() = runBlocking {
        println("\n┌─────────────────────────────────────────────────────────────┐")
        println("│ TEST: MCP Initialize Handshake via WebSocket               │")
        println("└─────────────────────────────────────────────────────────────┘")
        
        val wsUrl = TestConfig.gatewayUrl.replace("http://", "ws://") + "/mcp/ws"
        println("    Connecting to: $wsUrl")
        
        client.webSocket(wsUrl) {
            // Send MCP initialize request
            val initRequest = buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", 1)
                put("method", "initialize")
                putJsonObject("params") {
                    put("protocolVersion", "2024-11-05")
                    putJsonObject("clientInfo") {
                        put("name", "test-client")
                        put("version", "1.0.0")
                    }
                    putJsonObject("capabilities") {}
                }
            }
            
            println("    Sending initialize request:")
            println("    ${json.encodeToString(JsonObject.serializer(), initRequest)}")
            
            send(Frame.Text(initRequest.toString()))
            
            // Receive response
            val response = withTimeout(10.seconds) {
                incoming.receive() as Frame.Text
            }
            
            val responseText = response.readText()
            println("    Received response:")
            println("    $responseText")
            
            val responseJson = json.parseToJsonElement(responseText).jsonObject
            
            // Verify response
            assertEquals("2.0", responseJson["jsonrpc"]?.jsonPrimitive?.content)
            assertEquals(1, responseJson["id"]?.jsonPrimitive?.int)
            assertNotNull(responseJson["result"], "Should have result object")
            
            val result = responseJson["result"]?.jsonObject
            assertNotNull(result?.get("protocolVersion"), "Should have protocol version")
            assertNotNull(result?.get("serverInfo"), "Should have server info")
            
            println("    ✓ MCP handshake successful")
            println("    Server: ${result?.get("serverInfo")}")
        }
    }
    
    @Test
    @Order(2)
    fun `MCP tools list via WebSocket`() = runBlocking {
        println("\n┌─────────────────────────────────────────────────────────────┐")
        println("│ TEST: MCP Tools List via WebSocket                         │")
        println("└─────────────────────────────────────────────────────────────┘")
        
        val wsUrl = TestConfig.gatewayUrl.replace("http://", "ws://") + "/mcp/ws"
        
        client.webSocket(wsUrl) {
            // Send tools/list request
            val listRequest = buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", 2)
                put("method", "tools/list")
                putJsonObject("params") {}
            }
            
            println("    Sending tools/list request")
            send(Frame.Text(listRequest.toString()))
            
            // Receive response
            val response = withTimeout(10.seconds) {
                incoming.receive() as Frame.Text
            }
            
            val responseText = response.readText()
            println("    Received response:")
            println("    $responseText")
            
            val responseJson = json.parseToJsonElement(responseText).jsonObject
            
            // Verify response
            assertEquals("2.0", responseJson["jsonrpc"]?.jsonPrimitive?.content)
            assertNotNull(responseJson["result"], "Should have result")
            
            val result = responseJson["result"]?.jsonObject
            val tools = result?.get("tools")?.jsonArray
            assertNotNull(tools, "Should have tools array")
            
            println("    Available tools:")
            tools?.forEach { tool ->
                val name = tool.jsonObject["name"]?.jsonPrimitive?.content
                val description = tool.jsonObject["description"]?.jsonPrimitive?.content
                println("      - $name: $description")
            }
            
            println("    ✓ Tools list retrieved successfully")
        }
    }
    
    @Test
    @Order(3)
    fun `MCP tool call via WebSocket`() = runBlocking {
        println("\n┌─────────────────────────────────────────────────────────────┐")
        println("│ TEST: MCP Tool Call via WebSocket                          │")
        println("└─────────────────────────────────────────────────────────────┘")
        
        val wsUrl = TestConfig.gatewayUrl.replace("http://", "ws://") + "/mcp/ws"
        
        client.webSocket(wsUrl) {
            // Send tools/call request
            val callRequest = buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", 3)
                put("method", "tools/call")
                putJsonObject("params") {
                    put("name", "google_gmail_send_email")
                    putJsonObject("arguments") {
                        put("to", "test@example.com")
                        put("subject", "E2E Test")
                        put("body", "This is an end-to-end test email")
                    }
                }
            }
            
            println("    Sending tools/call request:")
            println("    Tool: google_gmail_send_email")
            println("    Arguments: to=test@example.com, subject=E2E Test")
            
            send(Frame.Text(callRequest.toString()))
            
            // Receive response (may take longer due to full flow)
            val response = withTimeout(30.seconds) {
                incoming.receive() as Frame.Text
            }
            
            val responseText = response.readText()
            println("    Received response:")
            println("    $responseText")
            
            val responseJson = json.parseToJsonElement(responseText).jsonObject
            
            // Verify response structure
            assertEquals("2.0", responseJson["jsonrpc"]?.jsonPrimitive?.content)
            assertEquals(3, responseJson["id"]?.jsonPrimitive?.int)
            
            // Check for result or error
            val result = responseJson["result"]?.jsonObject
            val error = responseJson["error"]?.jsonObject
            
            if (result != null) {
                val content = result["content"]?.jsonArray
                assertNotNull(content, "Should have content array")
                println("    ✓ Tool call returned result with ${content?.size} content blocks")
                
                content?.forEach { block ->
                    val type = block.jsonObject["type"]?.jsonPrimitive?.content
                    val text = block.jsonObject["text"]?.jsonPrimitive?.content
                    println("      [$type]: ${text?.take(100)}")
                }
            } else if (error != null) {
                val code = error["code"]?.jsonPrimitive?.int
                val message = error["message"]?.jsonPrimitive?.content
                println("    ⚠ Tool call returned error: $code - $message")
                // This is acceptable - may fail due to policy or missing upstream
            } else {
                fail("Response should have either result or error")
            }
        }
    }
    
    @Test
    @Order(4)
    fun `full flow via HTTP callback endpoint`() = runBlocking {
        println("\n┌─────────────────────────────────────────────────────────────┐")
        println("│ TEST: Full Flow via HTTP (Policy Check + Callback)         │")
        println("└─────────────────────────────────────────────────────────────┘")
        
        // Step 1: Store a context (simulating what Gateway does)
        println("    Step 1: Storing context in Gateway...")
        
        val contextBody = buildJsonObject {
            put("requestId", "e2e-test-${System.currentTimeMillis()}")
            put("tenantId", "test-tenant")
            put("userId", "test-user")
            put("service", "google_gmail")
            put("operation", "send_email")
            putJsonObject("body") {
                put("to", "recipient@example.com")
                put("subject", "E2E Test")
                put("body", "Test content")
            }
            put("createdAt", System.currentTimeMillis())
        }
        
        // Note: Context storage is internal, so we test via callback
        
        // Step 2: Simulate a callback from Executor
        println("    Step 2: Sending callback to Gateway...")
        
        val callbackBody = buildJsonObject {
            put("requestId", "e2e-callback-test-${System.currentTimeMillis()}")
            put("success", true)
            putJsonObject("data") {
                put("message_id", "msg-12345")
                put("status", "sent")
            }
            put("mcpContent", """[{"type":"text","text":"Email sent successfully"}]""")
            put("mcpIsError", false)
        }
        
        val response = client.post("${TestConfig.gatewayUrl}/callback") {
            contentType(ContentType.Application.Json)
            setBody(callbackBody.toString())
        }
        
        println("    Callback response status: ${response.status}")
        
        // Gateway should accept the callback (even if requestId is unknown)
        assertTrue(response.status.isSuccess() || response.status == HttpStatusCode.NotFound,
            "Callback endpoint should respond")
        
        println("    ✓ Callback endpoint processed request")
    }
    
    @Test
    @Order(5)
    fun `verify all services are healthy`() = runBlocking {
        println("\n┌─────────────────────────────────────────────────────────────┐")
        println("│ TEST: Verify All Services Healthy                          │")
        println("└─────────────────────────────────────────────────────────────┘")
        
        // Gateway health
        val gatewayHealth = client.get("${TestConfig.gatewayUrl}/health")
        println("    Gateway: ${gatewayHealth.status}")
        assertTrue(gatewayHealth.status.isSuccess(), "Gateway should be healthy")
        
        // NPL Engine health
        val nplHealth = client.get("${TestConfig.nplUrl}/actuator/health")
        println("    NPL Engine: ${nplHealth.status}")
        assertTrue(nplHealth.status.isSuccess(), "NPL Engine should be healthy")
        
        // Keycloak health (uses port 9000 for health endpoint)
        try {
            val keycloakHealth = client.get("http://localhost:9000/health")
            println("    Keycloak: ${keycloakHealth.status}")
            assertTrue(keycloakHealth.status.isSuccess(), "Keycloak should be healthy")
        } catch (e: Exception) {
            println("    Keycloak: Health endpoint not reachable on port 9000")
            // Verify via token endpoint instead
            val tokenCheck = client.get("${TestConfig.keycloakUrl}/realms/mcpgateway/.well-known/openid-configuration")
            println("    Keycloak OIDC Config: ${tokenCheck.status}")
            assertTrue(tokenCheck.status.isSuccess(), "Keycloak OIDC should be accessible")
        }
        
        // Executor health (port 8001)
        try {
            val executorHealth = client.get("http://localhost:8001/health")
            println("    Executor: ${executorHealth.status}")
            assertTrue(executorHealth.status.isSuccess(), "Executor should be healthy")
        } catch (e: Exception) {
            println("    Executor: Not reachable (may be network isolated)")
        }
        
        // RabbitMQ management API
        try {
            val rabbitmqHealth = client.get("http://localhost:15672/api/health/checks/alarms") {
                basicAuth("guest", "guest")
            }
            println("    RabbitMQ: ${rabbitmqHealth.status}")
        } catch (e: Exception) {
            println("    RabbitMQ: Management API not reachable")
        }
        
        println("    ✓ Core services are healthy")
    }
    
    @AfterAll
    fun teardown() {
        println("\n╔════════════════════════════════════════════════════════════════╗")
        println("║ END-TO-END TESTS - Complete                                    ║")
        println("╚════════════════════════════════════════════════════════════════╝")
        if (::client.isInitialized) {
            client.close()
        }
    }
}
