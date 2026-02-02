package io.noumena.mcp.executor.secrets

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Credentials fetched from Vault.
 */
@Serializable
data class ServiceCredentials(
    val accessToken: String? = null,
    val refreshToken: String? = null,
    val apiKey: String? = null,
    val username: String? = null,
    val password: String? = null,
    val sessionId: String? = null
)

/**
 * Client for HashiCorp Vault.
 * 
 * Fetches service credentials on behalf of users.
 * Only the Executor has access to Vault.
 */
class VaultClient {
    
    private val vaultAddr = System.getenv("VAULT_ADDR") ?: "http://vault:8200"
    private val vaultToken = System.getenv("VAULT_TOKEN") ?: "dev-token"
    private val devMode = System.getenv("DEV_MODE")?.toBoolean() ?: true
    
    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
            })
        }
    }
    
    /**
     * Get credentials for a service from Vault.
     * 
     * Path convention: secret/data/tenants/{tenant}/users/{user}/{service}
     */
    suspend fun getCredentials(tenantId: String, userId: String, service: String): ServiceCredentials {
        if (devMode) {
            logger.warn { "DEV MODE: Returning mock credentials for $service" }
            return mockCredentials(service)
        }
        
        val path = "secret/data/tenants/$tenantId/users/$userId/$service"
        logger.info { "Fetching credentials from Vault: $path" }
        
        return try {
            val response = client.get("$vaultAddr/v1/$path") {
                header("X-Vault-Token", vaultToken)
            }
            
            if (response.status.isSuccess()) {
                val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                val data = body["data"]?.jsonObject?.get("data")?.jsonObject
                    ?: throw Exception("Invalid Vault response format")
                
                ServiceCredentials(
                    accessToken = data["access_token"]?.jsonPrimitive?.content,
                    refreshToken = data["refresh_token"]?.jsonPrimitive?.content,
                    apiKey = data["api_key"]?.jsonPrimitive?.content,
                    username = data["username"]?.jsonPrimitive?.content,
                    password = data["password"]?.jsonPrimitive?.content,
                    sessionId = data["session_id"]?.jsonPrimitive?.content
                )
            } else {
                logger.error { "Vault request failed: ${response.status}" }
                throw Exception("Failed to fetch credentials: ${response.status}")
            }
        } catch (e: Exception) {
            logger.error(e) { "Error fetching credentials from Vault" }
            throw e
        }
    }
    
    /**
     * Mock credentials for development.
     */
    private fun mockCredentials(service: String): ServiceCredentials {
        return when (service) {
            "google" -> ServiceCredentials(
                accessToken = "mock-google-access-token",
                refreshToken = "mock-google-refresh-token"
            )
            "slack" -> ServiceCredentials(
                accessToken = "xoxb-mock-slack-token"
            )
            "sap" -> ServiceCredentials(
                username = "SAP_USER",
                password = "mock-sap-password",
                sessionId = "mock-sap-session"
            )
            else -> ServiceCredentials(
                apiKey = "mock-api-key-for-$service"
            )
        }
    }
}
