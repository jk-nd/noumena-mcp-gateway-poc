package io.noumena.mcp.gateway.auth

import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * Unit tests for UserIdentity data class and role checks.
 */
class UserIdentityTest {

    @Nested
    @DisplayName("Role checks")
    inner class RoleTests {

        @Test
        fun `admin role detected`() {
            val identity = UserIdentity(
                subject = "user-1",
                preferredUsername = "admin",
                email = "admin@example.com",
                roles = listOf("admin"),
                organization = listOf("noumena")
            )
            assertTrue(identity.isAdmin())
            assertFalse(identity.isAgent())
        }

        @Test
        fun `agent role detected`() {
            val identity = UserIdentity(
                subject = "user-2",
                preferredUsername = "agent-bot",
                email = null,
                roles = listOf("agent"),
                organization = emptyList()
            )
            assertFalse(identity.isAdmin())
            assertTrue(identity.isAgent())
        }

        @Test
        fun `user with multiple roles`() {
            val identity = UserIdentity(
                subject = "user-3",
                preferredUsername = "superuser",
                email = "super@example.com",
                roles = listOf("admin", "agent", "user"),
                organization = listOf("noumena")
            )
            assertTrue(identity.isAdmin())
            assertTrue(identity.isAgent())
            assertTrue(identity.hasRole("user"))
        }

        @Test
        fun `user with no roles`() {
            val identity = UserIdentity(
                subject = "user-4",
                preferredUsername = "viewer",
                email = "view@example.com",
                roles = emptyList(),
                organization = emptyList()
            )
            assertFalse(identity.isAdmin())
            assertFalse(identity.isAgent())
            assertFalse(identity.hasRole("anything"))
        }

        @Test
        fun `hasRole is case-sensitive`() {
            val identity = UserIdentity(
                subject = "user-5",
                preferredUsername = "test",
                email = null,
                roles = listOf("Admin"),
                organization = emptyList()
            )
            assertFalse(identity.isAdmin())  // "Admin" != "admin"
            assertTrue(identity.hasRole("Admin"))
        }
    }

    @Nested
    @DisplayName("Data fields")
    inner class DataTests {

        @Test
        fun `all fields populated`() {
            val identity = UserIdentity(
                subject = "uuid-123",
                preferredUsername = "john",
                email = "john@example.com",
                roles = listOf("admin"),
                organization = listOf("acme")
            )
            assertEquals("uuid-123", identity.subject)
            assertEquals("john", identity.preferredUsername)
            assertEquals("john@example.com", identity.email)
            assertEquals(listOf("admin"), identity.roles)
            assertEquals(listOf("acme"), identity.organization)
        }

        @Test
        fun `nullable email`() {
            val identity = UserIdentity(
                subject = "uuid-456",
                preferredUsername = "bot",
                email = null,
                roles = listOf("agent"),
                organization = emptyList()
            )
            assertNull(identity.email)
        }
    }
}
