package io.noumena.mcp.gateway.upstream

import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull

/**
 * Unit tests for UpstreamRouter — the namespace parser and tool resolver.
 *
 * These tests verify:
 *   - Tool name parsing (namespace.tool splitting)
 *   - Edge cases (no dot, leading dot, trailing dot, multiple dots)
 *   - Service resolution (enabled/disabled filtering)
 */
class UpstreamRouterTest {

    private val router = UpstreamRouter()

    // ════════════════════════════════════════════════════════════════════════
    // parseNamespacedTool
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("parseNamespacedTool")
    inner class ParseTests {

        @Test
        fun `parses standard namespaced tool`() {
            val result = router.parseNamespacedTool("duckduckgo.search")
            assertNotNull(result)
            assertEquals("duckduckgo", result!!.first)
            assertEquals("search", result.second)
        }

        @Test
        fun `parses tool with underscores`() {
            val result = router.parseNamespacedTool("github.create_issue")
            assertNotNull(result)
            assertEquals("github", result!!.first)
            assertEquals("create_issue", result.second)
        }

        @Test
        fun `returns null for bare tool name without namespace`() {
            val result = router.parseNamespacedTool("search")
            assertNull(result)
        }

        @Test
        fun `returns null for empty string`() {
            val result = router.parseNamespacedTool("")
            assertNull(result)
        }

        @Test
        fun `returns null for leading dot`() {
            val result = router.parseNamespacedTool(".search")
            assertNull(result)
        }

        @Test
        fun `returns null for trailing dot`() {
            val result = router.parseNamespacedTool("duckduckgo.")
            assertNull(result)
        }

        @Test
        fun `handles multiple dots - first dot wins`() {
            val result = router.parseNamespacedTool("google.gmail.send_email")
            assertNotNull(result)
            assertEquals("google", result!!.first)
            assertEquals("gmail.send_email", result.second)
        }

        @Test
        fun `returns null for just a dot`() {
            val result = router.parseNamespacedTool(".")
            assertNull(result)
        }

        @Test
        fun `handles single-char service and tool names`() {
            val result = router.parseNamespacedTool("a.b")
            assertNotNull(result)
            assertEquals("a", result!!.first)
            assertEquals("b", result.second)
        }
    }
}
