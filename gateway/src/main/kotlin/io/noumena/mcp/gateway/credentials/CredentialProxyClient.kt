package io.noumena.mcp.gateway.credentials

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

@Serializable
data class InjectCredentialsRequest(
    val service: String,
    val operation: String,
    val metadata: Map<String, String> = emptyMap(),
    val tenantId: String,
    val userId: String
)

@Serializable
data class InjectCredentialsResponse(
    val credentialName: String,
    val injectedFields: Map<String, String>
)

/**
 * Client for the Credential Proxy service.
 * Gateway uses this to get credentials before forwarding to upstream MCP.
 */
class CredentialProxyClient(
    private val credentialProxyUrl: String = System.getenv("CREDENTIAL_PROXY_URL") ?: "http://credential-proxy:9002"
) {
    
    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    }
    
    /**
     * Request credential injection for a service/operation.
     * Returns environment variables to inject into upstream connection.
     * 
     * @param service Service name (e.g., "github")
     * @param operation Operation name (e.g., "create_issue")
     * @param metadata Request metadata for conditional selection
     * @param tenantId Tenant ID (extracted from JWT)
     * @param userId User ID (extracted from JWT or act_on_behalf_of)
     * @return Map of environment variables to inject (e.g., {"GITHUB_TOKEN": "ghp_xxx"})
     */
    suspend fun getCredentials(
        service: String,
        operation: String,
        metadata: Map<String, String> = emptyMap(),
        tenantId: String,
        userId: String
    ): Map<String, String> {
        logger.debug { "Requesting credentials from proxy for $service.$operation" }
        
        return try {
            val request = InjectCredentialsRequest(
                service = service,
                operation = operation,
                metadata = metadata,
                tenantId = tenantId,
                userId = userId
            )
            
            val response: InjectCredentialsResponse = client.post("$credentialProxyUrl/inject-credentials") {
                contentType(ContentType.Application.Json)
                setBody(request)
            }.body()
            
            logger.info { "Received credentials from proxy: credential=${response.credentialName}, fields=${response.injectedFields.size}" }
            
            response.injectedFields
        } catch (e: Exception) {
            logger.error(e) { "Failed to get credentials from proxy for $service.$operation" }
            throw RuntimeException("Credential injection failed: ${e.message}", e)
        }
    }
}
