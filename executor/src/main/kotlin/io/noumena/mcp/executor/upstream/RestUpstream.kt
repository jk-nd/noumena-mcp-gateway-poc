package io.noumena.mcp.executor.upstream

import com.sksamuel.hoplite.ConfigAlias
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.noumena.mcp.executor.secrets.ServiceCredentials
import io.noumena.mcp.shared.config.ConfigLoader
import kotlinx.serialization.json.Json
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Upstream client for direct REST APIs (no MCP).
 * 
 * Used for legacy systems like SAP S/4HANA that don't have MCP support.
 */
class RestUpstream {
    
    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    }
    
    // REST endpoint configuration loaded from YAML
    private val restConfig: Map<String, RestConfig> = loadRestConfig()
    
    /**
     * Call a REST API endpoint.
     */
    suspend fun call(
        service: String,
        operation: String,
        params: Map<String, String>,
        credentials: ServiceCredentials
    ): Map<String, String> {
        val config = restConfig[service]
            ?: throw IllegalArgumentException("No REST config for service: $service")
        
        val endpoint = config.endpoints[operation]
            ?: throw IllegalArgumentException("No endpoint for operation: $operation")
        
        // Interpolate path parameters
        var path = endpoint.path
        params.forEach { (key, value) ->
            path = path.replace("{$key}", value)
        }
        
        val url = "${config.baseUrl}$path"
        logger.info { "Calling REST: ${endpoint.method} $url" }
        
        val response = client.request(url) {
            method = HttpMethod.parse(endpoint.method)
            
            // Add authentication based on available credentials
            credentials.accessToken?.let {
                header("Authorization", "Bearer $it")
            } ?: credentials.apiKey?.let {
                header("X-API-Key", it)
            } ?: credentials.sessionId?.let {
                header("X-SAP-Session", it)
            }
            
            // Add body for POST/PUT
            if (endpoint.method in listOf("POST", "PUT", "PATCH")) {
                contentType(ContentType.Application.Json)
                setBody(params)
            }
        }
        
        if (!response.status.isSuccess()) {
            throw RuntimeException("REST call failed: ${response.status} - ${response.bodyAsText()}")
        }
        
        val body = response.bodyAsText()
        return try {
            Json.decodeFromString<Map<String, String>>(body)
        } catch (e: Exception) {
            mapOf("result" to body)
        }
    }
}

private fun loadRestConfig(): Map<String, RestConfig> {
    val path = System.getenv("UPSTREAMS_CONFIG") ?: "configs/upstreams.yaml"
    val upstreams = ConfigLoader.load<UpstreamsConfig>(path)

    return upstreams.upstreams
        .filter { (_, cfg) -> cfg.type.equals("direct_rest", ignoreCase = true) }
        .mapValues { (_, cfg) ->
            val baseUrl = cfg.baseUrl
                ?: throw IllegalArgumentException("Missing base_url for REST upstream")
            RestConfig(
                baseUrl = baseUrl,
                endpoints = cfg.endpoints.orEmpty()
            )
        }
}

data class RestConfig(
    val baseUrl: String,
    val endpoints: Map<String, Endpoint>
)

data class Endpoint(
    val method: String,
    val path: String
)

data class UpstreamsConfig(
    val upstreams: Map<String, UpstreamEntry> = emptyMap()
)

data class UpstreamEntry(
    val type: String,
    @ConfigAlias("base_url") val baseUrl: String? = null,
    @ConfigAlias("endpoint") val endpoint: String? = null,
    @ConfigAlias("vault_path") val vaultPath: String? = null,
    @ConfigAlias("auth_type") val authType: String? = null,
    val endpoints: Map<String, Endpoint>? = emptyMap()
)
