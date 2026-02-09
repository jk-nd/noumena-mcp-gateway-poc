package io.noumena.mcp.gateway.auth

import io.noumena.mcp.shared.config.ConfigLoader
import mu.KotlinLogging
import java.io.File
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

/**
 * Identity associated with an API key.
 * Mirrors the claims an agent would have in a JWT.
 */
data class ApiKeyIdentity(
    val userId: String,
    val tenantId: String = "default",
    val roles: List<String> = listOf("agent"),
    val description: String = ""
)

/**
 * API key entry stored in the keys file.
 */
data class ApiKeyEntry(
    val key: String,
    val identity: ApiKeyIdentity,
    val createdAt: String = "",
    val enabled: Boolean = true
)

/**
 * API key configuration file structure.
 */
data class ApiKeysConfig(
    val keys: List<ApiKeyEntry> = emptyList()
)

/**
 * Validates API keys (Bearer nmc-xxx) as an alternative to JWT authentication.
 *
 * API keys are stored in a YAML file and cached in memory. Each key maps
 * to an identity (userId, tenantId, roles) that is used identically to JWT claims
 * by the downstream policy and credential injection layers.
 *
 * Key format: nmc-<32 hex chars> (e.g., nmc-a1b2c3d4e5f6...)
 */
class ApiKeyAuthProvider(
    private val keysFilePath: String = System.getenv("API_KEYS_PATH") ?: "/app/configs/api-keys.yaml"
) {
    private val keyCache = ConcurrentHashMap<String, ApiKeyIdentity>()
    private var lastLoadTime = 0L

    init {
        loadKeys()
    }

    /**
     * Validate an API key and return the associated identity.
     * Returns null if the key is invalid or disabled.
     */
    fun validate(apiKey: String): ApiKeyIdentity? {
        // Reload keys if file changed (check every 60s)
        val now = System.currentTimeMillis()
        if (now - lastLoadTime > 60_000) {
            loadKeys()
        }

        return keyCache[apiKey]
    }

    /**
     * Load API keys from the config file into the cache.
     */
    private fun loadKeys() {
        lastLoadTime = System.currentTimeMillis()
        val file = File(keysFilePath)
        if (!file.exists()) {
            logger.debug { "API keys file not found at $keysFilePath — API key auth disabled" }
            return
        }

        try {
            val config = ConfigLoader.load<ApiKeysConfig>(keysFilePath)

            keyCache.clear()
            config.keys.filter { it.enabled }.forEach { entry ->
                keyCache[entry.key] = entry.identity
            }

            logger.info { "Loaded ${keyCache.size} API keys from $keysFilePath" }
        } catch (e: Exception) {
            logger.error(e) { "Failed to load API keys from $keysFilePath" }
        }
    }

    companion object {
        private val secureRandom = SecureRandom()

        /**
         * Generate a new API key in the format: nmc-<32 hex chars>
         */
        fun generateKey(): String {
            val bytes = ByteArray(16)
            secureRandom.nextBytes(bytes)
            val hex = bytes.joinToString("") { "%02x".format(it) }
            return "nmc-$hex"
        }
    }
}
