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
 * End-to-End Integration Tests for V2 Transparent Proxy Architecture.
 * 
 * Tests the complete flow:
 * MCP Client -> Gateway (WebSocket/HTTP) -> NPL Policy -> Upstream MCP -> Response
 * 
 * Prerequisites:
 * - Full Docker stack running: docker compose -f deployments/docker-compose.yml up -d
 * - All services healthy: Gateway, NPL Engine, Keycloak
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
    
    // Separate client for WebSocket (no ContentNegotiation to avoid serialization conflicts)
    private lateinit var wsClient: HttpClient
    
    @BeforeAll
    fun setup() = runBlocking {
        println("╔════════════════════════════════════════════════════════════════╗")
        println("║ END-TO-END INTEGRATION TESTS (V2 Transparent Proxy)           ║")
        println("╠════════════════════════════════════════════════════════════════╣")
        println("║ Gateway URL:  ${TestConfig.gatewayUrl}")
        println("║ NPL URL:      ${TestConfig.nplUrl}")
        println("║ Keycloak URL: ${TestConfig.keycloakUrl}")
        println("╚════════════════════════════════════════════════════════════════╝")
        
        // HTTP client for REST calls
        client = HttpClient(CIO) {
            install(ContentNegotiation) {
                json(json)
            }
        }
        
        // Separate WebSocket client (ContentNegotiation conflicts with WebSocket sessions)
        wsClient = HttpClient(CIO) {
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
        
        wsClient.webSocket(wsUrl, request = {
            header("Authorization", "Bearer $token")
        }) {
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
            
            // V2: Verify server version is 2.0.0
            val serverInfo = result?.get("serverInfo")?.jsonObject
            assertEquals("noumena-mcp-gateway", serverInfo?.get("name")?.jsonPrimitive?.content)
            assertEquals("2.0.0", serverInfo?.get("version")?.jsonPrimitive?.content)
            
            println("    ✓ MCP handshake successful (V2 proxy)")
            println("    Server: ${result?.get("serverInfo")}")
        }
    }
    
    @Test
    @Order(2)
    fun `MCP tools list returns namespaced tools`() = runBlocking {
        println("\n┌─────────────────────────────────────────────────────────────┐")
        println("│ TEST: MCP Tools List Returns Namespaced Tools              │")
        println("└─────────────────────────────────────────────────────────────┘")
        
        val wsUrl = TestConfig.gatewayUrl.replace("http://", "ws://") + "/mcp/ws"
        
        wsClient.webSocket(wsUrl, request = {
            header("Authorization", "Bearer $token")
        }) {
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
            
            println("    Available namespaced tools:")
            tools?.forEach { tool ->
                val name = tool.jsonObject["name"]?.jsonPrimitive?.content ?: ""
                val description = tool.jsonObject["description"]?.jsonPrimitive?.content ?: ""
                println("      - $name: $description")
                
                // V2: Verify tool names are namespaced (contain a dot)
                if (name.isNotEmpty()) {
                    assertTrue(name.contains("."), 
                        "Tool name '$name' should be namespaced (service.tool)")
                }
            }
            
            println("    ✓ Namespaced tools list retrieved successfully")
        }
    }
    
    @Test
    @Order(3)
    fun `MCP tool call via HTTP POST`() = runBlocking {
        println("\n┌─────────────────────────────────────────────────────────────┐")
        println("│ TEST: MCP Tool Call via HTTP POST                          │")
        println("└─────────────────────────────────────────────────────────────┘")
        
        // V2: Use namespaced tool name (service.tool format)
        val callRequest = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", 3)
            put("method", "tools/call")
            putJsonObject("params") {
                put("name", "duckduckgo.search")
                putJsonObject("arguments") {
                    put("query", "integration test")
                }
            }
        }
        
        println("    Sending tools/call via HTTP POST:")
        println("    Tool: duckduckgo.search (namespaced)")
        
        val response = client.post("${TestConfig.gatewayUrl}/mcp") {
            header("Authorization", "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(callRequest.toString())
        }
        
        val responseText = response.bodyAsText()
        println("    HTTP Status: ${response.status}")
        println("    Response: ${responseText.take(300)}")
        
        assertTrue(response.status.isSuccess(), "HTTP POST should succeed")
        
        val responseJson = json.parseToJsonElement(responseText).jsonObject
        assertEquals("2.0", responseJson["jsonrpc"]?.jsonPrimitive?.content)
        
        // Check for result or error (may fail if upstream not available)
        val result = responseJson["result"]?.jsonObject
        if (result != null) {
            val content = result["content"]?.jsonArray
            assertNotNull(content, "Should have content array")
            println("    ✓ Tool call returned result with ${content?.size} content blocks")
        } else {
            val error = responseJson["error"]?.jsonObject
            println("    ⚠ Tool call returned error (upstream may not be available): ${error}")
        }
    }
    
    @Test
    @Order(5)
    fun `E2E - DuckDuckGo search via STDIO transport`() = runBlocking {
        println("\n┌─────────────────────────────────────────────────────────────┐")
        println("│ TEST: E2E DuckDuckGo Search via STDIO Transport            │")
        println("│ Agent → Gateway → NPL Policy → STDIO → Real Results       │")
        println("└─────────────────────────────────────────────────────────────┘")
        
        // Prerequisites: mcp/duckduckgo Docker image must be available locally
        // and the Gateway container must have the Docker socket mounted.
        
        // Step 1: Call duckduckgo.search through the Gateway
        val callRequest = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", 100)
            put("method", "tools/call")
            putJsonObject("params") {
                put("name", "duckduckgo.search")
                putJsonObject("arguments") {
                    put("query", "Noumena Protocol Language")
                    put("max_results", 3)
                }
            }
        }
        
        println("    Sending duckduckgo.search (STDIO transport)")
        println("    Query: 'Noumena Protocol Language'")
        
        val response = withTimeout(90.seconds) {
            client.post("${TestConfig.gatewayUrl}/mcp") {
                header("Authorization", "Bearer $token")
                contentType(ContentType.Application.Json)
                setBody(callRequest.toString())
            }
        }
        
        val responseText = response.bodyAsText()
        println("    HTTP Status: ${response.status}")
        println("    Response (first 500 chars): ${responseText.take(500)}")
        
        assertTrue(response.status.isSuccess(), "HTTP POST should succeed")
        
        val responseJson = json.parseToJsonElement(responseText).jsonObject
        assertEquals("2.0", responseJson["jsonrpc"]?.jsonPrimitive?.content)
        assertEquals(100, responseJson["id"]?.jsonPrimitive?.int)
        
        val result = responseJson["result"]?.jsonObject
        assertNotNull(result, "Should have a result object")
        
        val content = result!!["content"]?.jsonArray
        assertNotNull(content, "Result should have content array")
        assertTrue(content!!.isNotEmpty(), "Content should not be empty — real search results expected")
        
        // Verify we got actual text content back (search results)
        val textBlocks = content.filter { 
            it.jsonObject["type"]?.jsonPrimitive?.content == "text" 
        }
        assertTrue(textBlocks.isNotEmpty(), "Should have at least one text content block")
        
        val firstText = textBlocks.first().jsonObject["text"]?.jsonPrimitive?.content ?: ""
        println("    First result text (first 200 chars): ${firstText.take(200)}")
        assertTrue(firstText.isNotEmpty(), "Text content should not be empty")
        
        // Verify isError is false (successful upstream call)
        val isError = result["isError"]?.jsonPrimitive?.booleanOrNull ?: false
        assertFalse(isError, "Tool call should succeed (isError=false)")
        
        // Check for Gateway context metadata (appended by McpServerHandler)
        val contextBlocks = textBlocks.filter { 
            it.jsonObject["text"]?.jsonPrimitive?.content?.contains("noumena-mcp-gateway") == true 
        }
        assertTrue(contextBlocks.isNotEmpty(), "Should have Gateway context metadata")
        
        println("    ✓ E2E STDIO test passed: real search results received via Gateway proxy")
        println("    ✓ Flow: Agent → Gateway → NPL Policy → STDIO(docker run mcp/duckduckgo) → Results")
    }
    
    @Test
    @Order(4)
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
        
        // Keycloak health
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
        
        println("    ✓ Core services are healthy (V2: no Executor or RabbitMQ)")
    }
    
    @AfterAll
    fun teardown() {
        println("\n╔════════════════════════════════════════════════════════════════╗")
        println("║ END-TO-END TESTS - Complete                                    ║")
        println("╚════════════════════════════════════════════════════════════════╝")
        if (::client.isInitialized) {
            client.close()
        }
        if (::wsClient.isInitialized) {
            wsClient.close()
        }
    }
}
