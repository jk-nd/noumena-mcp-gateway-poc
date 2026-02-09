package io.noumena.mcp.gateway.server

import io.noumena.mcp.gateway.upstream.UpstreamRouter
import io.noumena.mcp.gateway.upstream.UpstreamSessionManager
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import java.io.File

/**
 * Unit tests for McpServerHandler — the transparent MCP proxy handler.
 *
 * Tests verify:
 *   - tools/list returns namespaced tools from config
 *   - tools/call returns TOOL_NOT_FOUND for unknown tools
 *   - JSON-RPC response structure is correct
 */
class McpServerHandlerTest {

    private lateinit var handler: McpServerHandler
    private lateinit var tempConfigFile: File

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    @BeforeEach
    fun setup() {
        // Enable DEV_MODE BEFORE constructing NplClient/handler,
        // so NplClient reads devMode=true from env at field init time.
        setEnv("DEV_MODE", "true")

        // Create a temporary services.yaml for testing
        tempConfigFile = File.createTempFile("services-test", ".yaml")
        tempConfigFile.writeText("""
            services:
              - name: "testservice"
                displayName: "Test Service"
                type: "MCP_HTTP"
                enabled: true
                endpoint: "http://test:8080"
                requiresCredentials: false
                description: "A test service"
                tools:
                  - name: "do_thing"
                    description: "Does a thing"
                    inputSchema:
                      type: "object"
                      properties:
                        query:
                          type: "string"
                          description: "The query"
                      required:
                        - "query"
                    enabled: true
                  - name: "disabled_tool"
                    description: "This tool is disabled"
                    inputSchema:
                      type: "object"
                    enabled: false
              - name: "disabled_svc"
                displayName: "Disabled Service"
                type: "MCP_HTTP"
                enabled: false
                endpoint: "http://disabled:8080"
                requiresCredentials: false
                description: "This service is disabled"
                tools:
                  - name: "hidden_tool"
                    description: "Should not appear"
                    inputSchema:
                      type: "object"
                    enabled: true
        """.trimIndent())

        setEnv("SERVICES_CONFIG_PATH", tempConfigFile.absolutePath)

        val router = UpstreamRouter()
        val sessionManager = UpstreamSessionManager(router)

        // Handler constructed AFTER DEV_MODE is set, so NplClient picks it up
        handler = McpServerHandler(
            upstreamRouter = router,
            upstreamSessionManager = sessionManager
        )
    }

    @AfterEach
    fun cleanup() {
        tempConfigFile.delete()
        removeEnv("DEV_MODE")
        removeEnv("SERVICES_CONFIG_PATH")
    }

    @Nested
    @DisplayName("tools/list")
    inner class ToolsListTests {

        @Test
        fun `returns namespaced tools from config`() = runTest {
            val response = handler.handleToolsList(JsonPrimitive(1), "test-user")
            val parsed = json.parseToJsonElement(response).jsonObject

            // Verify JSON-RPC structure
            assertEquals("2.0", parsed["jsonrpc"]?.jsonPrimitive?.content)
            assertEquals(1, parsed["id"]?.jsonPrimitive?.int)

            val tools = parsed["result"]?.jsonObject?.get("tools")?.jsonArray
            assertNotNull(tools)

            // Should have 1 enabled tool from the enabled service
            // (disabled tool and disabled service's tools are excluded)
            assertEquals(1, tools!!.size)

            val tool = tools[0].jsonObject
            assertEquals("testservice.do_thing", tool["name"]?.jsonPrimitive?.content)
            assertTrue(tool["description"]?.jsonPrimitive?.content?.contains("Test Service") == true)

            // Verify input schema is passed through
            val schema = tool["inputSchema"]?.jsonObject
            assertNotNull(schema)
            assertEquals("object", schema!!["type"]?.jsonPrimitive?.content)
            assertTrue(schema["properties"]?.jsonObject?.containsKey("query") == true)
        }
    }

    @Nested
    @DisplayName("tools/call error paths")
    inner class ToolsCallErrorTests {

        @Test
        fun `returns TOOL_NOT_FOUND for completely unknown tool`() = runTest {
            val response = handler.handleToolCall(
                requestId = JsonPrimitive(42),
                namespacedToolName = "nonexistent.fake_tool",
                arguments = buildJsonObject {},
                userId = "test-user"
            )

            val parsed = json.parseToJsonElement(response).jsonObject
            assertEquals("2.0", parsed["jsonrpc"]?.jsonPrimitive?.content)
            assertEquals(42, parsed["id"]?.jsonPrimitive?.int)

            val result = parsed["result"]?.jsonObject
            assertNotNull(result)
            assertTrue(result!!["isError"]?.jsonPrimitive?.boolean == true)

            // The error content should mention TOOL_NOT_FOUND
            val content = result["content"]?.jsonArray
            assertNotNull(content)
            val texts = content!!.map { it.jsonObject["text"]?.jsonPrimitive?.content ?: "" }
            assertTrue(texts.any { it.contains("not found") }, "Expected 'not found' in response: $texts")
        }

        @Test
        fun `returns TOOL_NOT_FOUND for disabled tool`() = runTest {
            val response = handler.handleToolCall(
                requestId = JsonPrimitive(43),
                namespacedToolName = "testservice.disabled_tool",
                arguments = buildJsonObject {},
                userId = "test-user"
            )

            val parsed = json.parseToJsonElement(response).jsonObject
            val result = parsed["result"]?.jsonObject
            assertTrue(result!!["isError"]?.jsonPrimitive?.boolean == true)
        }
    }

    // ─── Environment helpers ─────────────────────────────────────────────

    @Suppress("UNCHECKED_CAST")
    private fun setEnv(key: String, value: String) {
        try {
            val env = System.getenv()
            val field = env.javaClass.getDeclaredField("m")
            field.isAccessible = true
            val map = field.get(env) as MutableMap<String, String>
            map[key] = value
        } catch (e: Exception) {
            try {
                val clazz = Class.forName("java.lang.ProcessEnvironment")
                val field = clazz.getDeclaredField("theEnvironment")
                field.isAccessible = true
                val map = field.get(null) as MutableMap<String, String>
                map[key] = value
            } catch (e2: Exception) {
                throw RuntimeException("Cannot set env var $key", e2)
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun removeEnv(key: String) {
        try {
            val env = System.getenv()
            val field = env.javaClass.getDeclaredField("m")
            field.isAccessible = true
            val map = field.get(env) as MutableMap<String, String>
            map.remove(key)
        } catch (e: Exception) {
            // Best effort
        }
    }
}
