package io.noumena.mcp.shared.config

import com.sksamuel.hoplite.ConfigLoaderBuilder
import com.sksamuel.hoplite.addResourceSource
import com.sksamuel.hoplite.addFileSource
import java.io.File

/**
 * Utility for loading YAML configuration files.
 */
object ConfigLoader {
    
    /**
     * Load configuration from a YAML file.
     */
    inline fun <reified T : Any> load(path: String): T {
        val file = File(path)
        return if (file.exists()) {
            ConfigLoaderBuilder.default()
                .addFileSource(file)
                .build()
                .loadConfigOrThrow()
        } else {
            // Try as resource
            ConfigLoaderBuilder.default()
                .addResourceSource(path)
                .build()
                .loadConfigOrThrow()
        }
    }
}
