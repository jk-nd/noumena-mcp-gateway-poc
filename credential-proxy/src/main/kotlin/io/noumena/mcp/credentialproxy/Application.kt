package io.noumena.mcp.credentialproxy

import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.routing.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.http.*
import io.noumena.mcp.credentialproxy.config.CredentialConfigLoader
import io.noumena.mcp.credentialproxy.config.CredentialMode
import io.noumena.mcp.credentialproxy.selector.CredentialSelector
import io.noumena.mcp.credentialproxy.selector.NplCredentialClient
import io.noumena.mcp.credentialproxy.vault.VaultClient
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

@Serializable
data class HealthResponse(val status: String, val mode: String)

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

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 9002
    
    logger.info { "Starting Credential Proxy on port $port" }
    
    embeddedServer(Netty, port = port) {
        configureCredentialProxy()
    }.start(wait = true)
}

fun Application.configureCredentialProxy() {
    install(ContentNegotiation) {
        json(Json {
            prettyPrint = true
            ignoreUnknownKeys = true
        })
    }
    
    // Load configuration
    val configPath = System.getenv("CREDENTIAL_CONFIG_PATH") ?: "/app/configs/credentials.yaml"
    val config = CredentialConfigLoader.load(configPath)
    
    // Initialize components based on mode
    val vaultClient = VaultClient()
    val nplClient = if (config.getMode() == CredentialMode.EXPERT) {
        NplCredentialClient()
    } else {
        null
    }
    val credentialSelector = CredentialSelector(config, nplClient)
    
    logger.info { "Credential Proxy ready in ${config.getMode()} mode" }
    logger.info { "Loaded ${config.credentials.size} credentials, ${config.service_defaults.size} service defaults" }
    
    routing {
        // Health check
        get("/health") {
            call.respond(HealthResponse("ok", config.getMode().toString()))
        }
        
        // Inject credentials endpoint
        // Gateway calls this to get credentials injected
        post("/inject-credentials") {
            val request = call.receive<InjectCredentialsRequest>()
            
            logger.info { "Credential injection request: ${request.service}.${request.operation} for ${request.userId}@${request.tenantId}" }
            
            try {
                // Step 1: Select which credential to use
                val selection = credentialSelector.selectCredential(
                    service = request.service,
                    operation = request.operation,
                    metadata = request.metadata,
                    tenantId = request.tenantId,
                    userId = request.userId
                )
                
                logger.info { "Selected credential: ${selection.credentialName}" }
                
                // Step 2: Fetch actual secrets from Vault
                val vaultCreds = vaultClient.getCredentials(
                    vaultPath = selection.definition.vault_path,
                    tenantId = request.tenantId,
                    userId = request.userId
                )
                
                // Step 3: Map vault fields to injection targets
                val injectedFields = mutableMapOf<String, String>()
                selection.definition.injection.mapping.forEach { (vaultField, targetName) ->
                    val value = vaultCreds.fields[vaultField]
                    if (value != null) {
                        injectedFields[targetName] = value
                    } else {
                        logger.warn { "Vault field '$vaultField' not found in credentials" }
                    }
                }
                
                logger.info { "Injected ${injectedFields.size} credential fields" }
                
                call.respond(InjectCredentialsResponse(
                    credentialName = selection.credentialName,
                    injectedFields = injectedFields
                ))
                
            } catch (e: Exception) {
                logger.error(e) { "Credential injection failed" }
                call.respond(
                    HttpStatusCode.InternalServerError,
                    mapOf("error" to (e.message ?: "Unknown error"))
                )
            }
        }
        
        // Reload configuration (admin endpoint)
        post("/admin/reload-config") {
            try {
                val newConfig = CredentialConfigLoader.reload()
                logger.info { "Configuration reloaded successfully" }
                call.respond(mapOf(
                    "status" to "reloaded",
                    "mode" to newConfig.getMode().toString(),
                    "credentials" to newConfig.credentials.size
                ))
            } catch (e: Exception) {
                logger.error(e) { "Failed to reload configuration" }
                call.respond(
                    HttpStatusCode.InternalServerError,
                    mapOf("error" to (e.message ?: "Unknown error"))
                )
            }
        }
        
        // Clear cache (admin endpoint)
        post("/admin/clear-cache") {
            val path = call.request.queryParameters["path"]
            vaultClient.clearCache(path)
            call.respond(mapOf("status" to "cache_cleared"))
        }
    }
}
