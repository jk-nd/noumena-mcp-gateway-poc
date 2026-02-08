package io.noumena.mcp.credentialproxy.selector

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.noumena.mcp.credentialproxy.config.CredentialConfig
import io.noumena.mcp.credentialproxy.config.CredentialDefinition
import io.noumena.mcp.credentialproxy.config.CredentialMode
import kotlinx.serialization.json.*
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Result of credential selection.
 */
data class CredentialSelection(
    val credentialName: String,
    val definition: CredentialDefinition
)

/**
 * Selects which credential to use for a request.
 * Supports two modes: SIMPLE (YAML-based) and EXPERT (NPL-based).
 */
class CredentialSelector(
    private val config: CredentialConfig,
    private val nplClient: NplCredentialClient?
) {
    
    private val mode = config.getMode()
    
    init {
        logger.info { "Credential selector initialized in ${mode} mode" }
        if (mode == CredentialMode.EXPERT && nplClient == null) {
            throw IllegalArgumentException("EXPERT mode requires NplCredentialClient")
        }
    }
    
    /**
     * Select which credential to use for a request.
     * 
     * @param service Service name (e.g., "github", "slack")
     * @param operation Operation name (e.g., "create_issue")
     * @param metadata Request metadata for conditional selection
     * @param tenantId Tenant ID
     * @param userId User ID
     * @return Selected credential name and definition
     */
    suspend fun selectCredential(
        service: String,
        operation: String,
        metadata: Map<String, String>,
        tenantId: String,
        userId: String
    ): CredentialSelection {
        val credentialName = when (mode) {
            CredentialMode.SIMPLE -> selectSimple(service)
            CredentialMode.EXPERT -> selectExpert(service, operation, metadata, tenantId, userId)
        }
        
        val definition = config.getCredential(credentialName)
            ?: throw IllegalStateException("Credential '$credentialName' not found in configuration")
        
        return CredentialSelection(
            credentialName = credentialName,
            definition = definition
        )
    }
    
    /**
     * Simple mode: Direct YAML lookup by service.
     */
    private fun selectSimple(service: String): String {
        val credentialName = config.getServiceDefault(service) ?: "default"
        logger.info { "SIMPLE mode: Selected '$credentialName' for service '$service'" }
        return credentialName
    }
    
    /**
     * Expert mode: NPL-based selection with full audit trail.
     */
    private suspend fun selectExpert(
        service: String,
        operation: String,
        metadata: Map<String, String>,
        tenantId: String,
        userId: String
    ): String {
        val credentialName = nplClient!!.selectCredential(
            service = service,
            operation = operation,
            metadata = metadata,
            tenantId = tenantId,
            userId = userId
        )
        
        logger.info { "EXPERT mode: NPL selected '$credentialName' for $service.$operation" }
        return credentialName
    }
}

/**
 * Client for NPL CredentialInjectionPolicy protocol.
 * Used in EXPERT mode only.
 */
class NplCredentialClient(
    private val nplUrl: String = System.getenv("NPL_URL") ?: "http://npl-engine:12000"
) {
    
    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    }
    
    // Cached policy ID (looked up once)
    private var policyId: String? = null
    
    /**
     * Select credential via NPL CredentialInjectionPolicy.
     * 
     * @return Credential name (not vault path, not secrets!)
     */
    suspend fun selectCredential(
        service: String,
        operation: String,
        metadata: Map<String, String>,
        tenantId: String,
        userId: String
    ): String {
        // Get policy ID (cached)
        val id = policyId ?: findPolicyId().also { policyId = it }
        
        // Call NPL selectCredential permission
        val requestBody = buildJsonObject {
            put("serviceName", service)
            put("operationName", operation)
            putJsonObject("requestMetadata") {
                metadata.forEach { (k, v) -> put(k, v) }
            }
        }
        
        logger.debug { "Calling NPL selectCredential: $service.$operation" }
        
        val response = client.post("$nplUrl/npl/policies/CredentialInjectionPolicy/$id/selectCredential") {
            contentType(ContentType.Application.Json)
            setBody(requestBody.toString())
        }
        
        if (!response.status.isSuccess()) {
            throw RuntimeException("NPL credential selection failed: ${response.status}")
        }
        
        val responseBody = response.bodyAsText()
        val result = Json.parseToJsonElement(responseBody).jsonObject
        
        // Extract credential name from CredentialSelection struct
        return result["credentialName"]?.jsonPrimitive?.content
            ?: throw RuntimeException("NPL response missing credentialName")
    }
    
    /**
     * Find the CredentialInjectionPolicy protocol ID.
     */
    private suspend fun findPolicyId(): String {
        val response = client.get("$nplUrl/npl/policies/CredentialInjectionPolicy/")
        
        val responseBody = response.bodyAsText()
        val body = Json.parseToJsonElement(responseBody).jsonObject
        val items = body["items"]?.jsonArray
        
        if (items != null && items.isNotEmpty()) {
            val id = items[0].jsonObject["@id"]?.jsonPrimitive?.content
                ?: throw RuntimeException("NPL protocol missing @id")
            logger.info { "Found NPL CredentialInjectionPolicy: $id" }
            return id
        }
        
        throw RuntimeException(
            "CredentialInjectionPolicy not initialized in NPL. " +
            "Bootstrap the policy first or switch to SIMPLE mode."
        )
    }
}
