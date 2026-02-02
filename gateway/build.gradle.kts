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
    
    // Ktor Server (version must match what MCP SDK uses)
    val ktorVersion = "3.2.3"
    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-netty:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    implementation("io.ktor:ktor-server-auth:$ktorVersion")
    implementation("io.ktor:ktor-server-auth-jwt:$ktorVersion")
    implementation("io.ktor:ktor-server-status-pages:$ktorVersion")
    implementation("io.ktor:ktor-server-call-logging:$ktorVersion")
    
    // Ktor Client (for NPL Engine calls)
    implementation("io.ktor:ktor-client-core:$ktorVersion")
    implementation("io.ktor:ktor-client-cio:$ktorVersion")
    implementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")
    
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
