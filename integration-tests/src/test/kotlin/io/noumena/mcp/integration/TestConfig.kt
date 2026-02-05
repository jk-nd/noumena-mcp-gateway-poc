package io.noumena.mcp.integration

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Configuration for integration tests.
 * Reads from environment variables, with defaults for local Docker setup.
 */
object TestConfig {
    val nplUrl: String = System.getenv("NPL_URL") ?: "http://localhost:12000"
    val keycloakUrl: String = System.getenv("KEYCLOAK_URL") ?: "http://localhost:11000"
    val gatewayUrl: String = System.getenv("GATEWAY_URL") ?: "http://localhost:8000"
    val realm: String = "mcpgateway"
    val clientId: String = "mcpgateway"
    
    // Test user credentials (from Keycloak provisioning)
    val testUsername: String = "admin"
    val testPassword: String = "Welcome123"
}

/**
 * HTTP client configured for integration tests.
 */
object TestClient {
    val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }
    
    val httpClient = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(json)
        }
    }
}

@Serializable
data class TokenResponse(
    val access_token: String,
    val token_type: String,
    val expires_in: Int
)

/**
 * Keycloak token helper for integration tests.
 */
object KeycloakAuth {
    
    /**
     * Get an access token from Keycloak using password grant.
     */
    suspend fun getToken(
        username: String = TestConfig.testUsername,
        password: String = TestConfig.testPassword
    ): String {
        val tokenUrl = "${TestConfig.keycloakUrl}/realms/${TestConfig.realm}/protocol/openid-connect/token"
        
        println("    Getting Keycloak token from: $tokenUrl")
        
        // Create a fresh client to avoid lifecycle issues between test classes
        val client = HttpClient(CIO) {
            install(ContentNegotiation) {
                json(TestClient.json)
            }
        }
        
        val response = client.submitForm(
            url = tokenUrl,
            formParameters = parameters {
                append("grant_type", "password")
                append("client_id", TestConfig.clientId)
                append("username", username)
                append("password", password)
            }
        )
        
        if (!response.status.isSuccess()) {
            val body = response.bodyAsText()
            client.close()
            throw RuntimeException("Failed to get token: ${response.status} - $body")
        }
        
        val tokenResponse = TestClient.json.decodeFromString<TokenResponse>(response.bodyAsText())
        client.close()
        println("    Token obtained successfully (expires in ${tokenResponse.expires_in}s)")
        
        return tokenResponse.access_token
    }
}

/**
 * Check if the Docker stack is running (NPL Engine).
 */
suspend fun isDockerStackRunning(): Boolean {
    return try {
        val client = HttpClient(CIO) {
            install(ContentNegotiation) {
                json()
            }
        }
        // NPL Engine uses Spring Boot's actuator health endpoint
        val response = client.get("${TestConfig.nplUrl}/actuator/health")
        client.close()
        response.status.isSuccess()
    } catch (e: Exception) {
        println("    Docker stack not running: ${e.message}")
        false
    }
}

/**
 * Check if Gateway is running.
 */
suspend fun isGatewayRunning(): Boolean {
    return try {
        val client = HttpClient(CIO) {
            install(ContentNegotiation) {
                json()
            }
        }
        val response = client.get("${TestConfig.gatewayUrl}/health")
        client.close()
        response.status.isSuccess()
    } catch (e: Exception) {
        println("    Gateway not running: ${e.message}")
        false
    }
}
