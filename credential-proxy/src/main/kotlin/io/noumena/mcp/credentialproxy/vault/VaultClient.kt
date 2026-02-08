package io.noumena.mcp.credentialproxy.vault

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.*
import mu.KotlinLogging
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

/**
 * Fetched credentials from Vault.
 */
data class VaultCredentials(
    val fields: Map<String, String>
)

/**
 * Client for HashiCorp Vault.
 * Fetches credentials and caches them with TTL.
 */
class VaultClient(
    private val vaultAddr: String = System.getenv("VAULT_ADDR") ?: "http://vault:8200",
    private val vaultToken: String = System.getenv("VAULT_TOKEN") ?: "dev-token",
    private val cacheTtlMs: Long = 300_000 // 5 minutes
) {
    
    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    }
    
    // Cache: vault_path -> (credentials, expiry_time)
    private val cache = ConcurrentHashMap<String, CacheEntry>()
    
    private data class CacheEntry(
        val credentials: VaultCredentials,
        val expiryTime: Long
    )
    
    /**
     * Get credentials from Vault (with caching).
     * 
     * @param vaultPath Path in Vault (e.g., "secret/data/tenants/acme/users/alice/github/work")
     * @param tenantId Tenant ID for path interpolation
     * @param userId User ID for path interpolation
     * @return Credentials as map of field name -> value
     */
    suspend fun getCredentials(
        vaultPath: String,
        tenantId: String,
        userId: String
    ): VaultCredentials {
        // Interpolate path variables
        val interpolatedPath = vaultPath
            .replace("{tenant}", tenantId)
            .replace("{user}", userId)
        
        // Check cache
        val cached = cache[interpolatedPath]
        if (cached != null && System.currentTimeMillis() < cached.expiryTime) {
            logger.debug { "Cache hit for $interpolatedPath" }
            return cached.credentials
        }
        
        // Fetch from Vault
        logger.info { "Fetching credentials from Vault: $interpolatedPath" }
        
        return try {
            val response = client.get("$vaultAddr/v1/$interpolatedPath") {
                header("X-Vault-Token", vaultToken)
            }
            
            if (response.status.isSuccess()) {
                val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                val data = body["data"]?.jsonObject?.get("data")?.jsonObject
                    ?: throw Exception("Invalid Vault response format: missing data.data")
                
                // Extract all fields
                val fields = data.mapValues { (_, value) ->
                    value.jsonPrimitive.content
                }
                
                val credentials = VaultCredentials(fields)
                
                // Cache with TTL
                cache[interpolatedPath] = CacheEntry(
                    credentials = credentials,
                    expiryTime = System.currentTimeMillis() + cacheTtlMs
                )
                
                logger.info { "Fetched ${fields.size} credential fields from Vault" }
                credentials
            } else {
                logger.error { "Vault request failed: ${response.status}" }
                throw Exception("Failed to fetch credentials: ${response.status}")
            }
        } catch (e: Exception) {
            logger.error(e) { "Error fetching credentials from Vault: $interpolatedPath" }
            throw e
        }
    }
    
    /**
     * Clear cache for a specific path or all paths.
     */
    fun clearCache(vaultPath: String? = null) {
        if (vaultPath != null) {
            cache.remove(vaultPath)
            logger.info { "Cleared cache for $vaultPath" }
        } else {
            cache.clear()
            logger.info { "Cleared entire credential cache" }
        }
    }
}
