plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    application
}

application {
    mainClass.set("io.noumena.mcp.gateway.ApplicationKt")
}

dependencies {
    implementation(project(":shared"))
    
    // Kotlin MCP SDK - Server
    implementation("io.modelcontextprotocol:kotlin-sdk-server:0.8.3")
    
    // Ktor Server
    implementation("io.ktor:ktor-server-core:2.3.7")
    implementation("io.ktor:ktor-server-netty:2.3.7")
    implementation("io.ktor:ktor-server-content-negotiation:2.3.7")
    implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.7")
    implementation("io.ktor:ktor-server-auth:2.3.7")
    implementation("io.ktor:ktor-server-auth-jwt:2.3.7")
    implementation("io.ktor:ktor-server-status-pages:2.3.7")
    implementation("io.ktor:ktor-server-call-logging:2.3.7")
    
    // Ktor Client (for NPL Engine calls)
    implementation("io.ktor:ktor-client-core:2.3.7")
    implementation("io.ktor:ktor-client-cio:2.3.7")
    implementation("io.ktor:ktor-client-content-negotiation:2.3.7")
    
    // Config
    implementation("com.sksamuel.hoplite:hoplite-core:2.7.5")
    implementation("com.sksamuel.hoplite:hoplite-yaml:2.7.5")
    
    // NOTE: Gateway has NO Vault dependency - this is intentional for security
}

tasks.jar {
    manifest {
        attributes["Main-Class"] = "io.noumena.mcp.gateway.ApplicationKt"
    }
}
