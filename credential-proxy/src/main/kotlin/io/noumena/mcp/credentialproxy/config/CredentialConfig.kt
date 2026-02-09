package io.noumena.mcp.credentialproxy.config

import com.charleskorn.kaml.Yaml
import kotlinx.serialization.Serializable
import mu.KotlinLogging
import java.io.File

private val logger = KotlinLogging.logger {}

/**
 * Credential configuration mode.
 */
enum class CredentialMode {
    /** Simple mode: Direct YAML lookup, no NPL tracking */
    SIMPLE,
    
    /** Expert mode: NPL-based selection with full audit trail */
    EXPERT;
    
    companion object {
        fun fromString(value: String): CredentialMode {
            return when (value.lowercase()) {
                "expert" -> EXPERT
                "simple", "" -> SIMPLE
                else -> {
                    logger.warn { "Unknown credential mode '$value', defaulting to SIMPLE" }
                    SIMPLE
                }
            }
        }
    }
}

/**
 * Full credential configuration loaded from YAML.
 */
@Serializable
data class CredentialConfig(
    val mode: String = "simple",
    val tenant: String = "default",
    val credentials: Map<String, CredentialDefinition> = emptyMap(),
    val service_defaults: Map<String, String> = emptyMap(),
    val default_credential: CredentialDefinition? = null
) {
    fun getMode(): CredentialMode = CredentialMode.fromString(mode)
    
    /**
     * Get credential definition by name.
     */
    fun getCredential(credentialName: String): CredentialDefinition? {
        return credentials[credentialName] ?: default_credential
    }
    
    /**
     * Get default credential for a service (simple mode).
     */
    fun getServiceDefault(service: String): String? {
        return service_defaults[service]
    }
}

/**
 * Individual credential definition.
 */
@Serializable
data class CredentialDefinition(
    val vault_path: String,
    val injection: InjectionConfig
)

/**
 * How to inject the credential.
 */
@Serializable
data class InjectionConfig(
    val type: String,  // "env" or "header"
    val mapping: Map<String, String>  // vault_field -> env_var/header_name
)

/**
 * Loads and caches credential configuration from YAML.
 */
object CredentialConfigLoader {
    private val yaml = Yaml.default
    private var cachedConfig: CredentialConfig? = null
    private var configPath: String? = null
    
    /**
     * Load configuration from file.
     */
    fun load(path: String): CredentialConfig {
        configPath = path
        val file = File(path)
        
        if (!file.exists()) {
            logger.warn { "Credential config not found at $path, using defaults" }
            return CredentialConfig()
        }
        
        val config = try {
            yaml.decodeFromString(CredentialConfig.serializer(), file.readText())
        } catch (e: Exception) {
            logger.error(e) { "Failed to parse credential config from $path" }
            throw RuntimeException("Invalid credential configuration: ${e.message}", e)
        }
        
        cachedConfig = config
        logger.info { "Loaded credential config: mode=${config.mode}, ${config.credentials.size} credentials, ${config.service_defaults.size} service defaults" }
        
        return config
    }
    
    /**
     * Reload configuration from disk.
     */
    fun reload(): CredentialConfig {
        val path = configPath ?: throw IllegalStateException("Config not loaded yet")
        logger.info { "Reloading credential configuration from $path" }
        return load(path)
    }
    
    /**
     * Get cached configuration.
     */
    fun get(): CredentialConfig {
        return cachedConfig ?: throw IllegalStateException("Config not loaded yet. Call load() first.")
    }
}
