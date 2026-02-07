package io.noumena.mcp.gateway.policy

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * Unit tests for NplClient.
 *
 * These tests verify:
 *   - DEV_MODE auto-allows all requests
 *   - Fail-closed behavior when NPL is unreachable
 *   - Cache clearing
 *
 * Note: Tests that require Keycloak/NPL connectivity are in integration-tests.
 */
class NplClientTest {

    @Nested
    @DisplayName("DEV_MODE behavior")
    inner class DevModeTests {

        @Test
        fun `DEV_MODE allows any service and tool`() = runTest {
            // NplClient reads DEV_MODE from env. We must set it before construction.
            withEnv("DEV_MODE", "true") {
                val client = NplClient()
                val result = client.checkPolicy("slack", "send_message", "user-123")
                assertTrue(result.allowed)
                assertTrue(result.reason?.contains("DEV MODE") == true)
            }
        }

        @Test
        fun `DEV_MODE allows even non-existent services`() = runTest {
            withEnv("DEV_MODE", "true") {
                val client = NplClient()
                val result = client.checkPolicy("nonexistent_service", "nonexistent_tool", "user-456")
                assertTrue(result.allowed)
            }
        }
    }

    @Nested
    @DisplayName("Fail-closed behavior")
    inner class FailClosedTests {

        @Test
        fun `returns denied when Keycloak is unreachable`() = runTest {
            withEnv("DEV_MODE", "false") {
                withEnv("KEYCLOAK_URL", "http://localhost:99999") {
                    val client = NplClient()
                    val result = client.checkPolicy("duckduckgo", "search", "user-123")
                    assertFalse(result.allowed)
                    assertNotNull(result.reason)
                }
            }
        }
    }

    @Nested
    @DisplayName("Cache management")
    inner class CacheTests {

        @Test
        fun `clearCache does not throw`() {
            val client = NplClient()
            assertDoesNotThrow { client.clearCache() }
        }
    }

    /**
     * Temporarily set an environment variable for a test block.
     * Uses reflection since System.setenv doesn't exist in Java.
     */
    private inline fun <T> withEnv(key: String, value: String, block: () -> T): T {
        val original = System.getenv(key)
        setEnv(key, value)
        try {
            return block()
        } finally {
            if (original != null) {
                setEnv(key, original)
            } else {
                removeEnv(key)
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun setEnv(key: String, value: String) {
        try {
            val env = System.getenv()
            val field = env.javaClass.getDeclaredField("m")
            field.isAccessible = true
            val map = field.get(env) as MutableMap<String, String>
            map[key] = value
        } catch (e: Exception) {
            // Fallback: try ProcessEnvironment
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
