plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    application
}

application {
    mainClass.set("io.noumena.mcp.executor.ApplicationKt")
}

dependencies {
    implementation(project(":shared"))
    
    // Kotlin MCP SDK - Client (for calling upstream MCP containers)
    implementation("io.modelcontextprotocol:kotlin-sdk-client:0.8.3")
    
    // Ktor Server (for receiving NPL notifications)
    implementation("io.ktor:ktor-server-core:2.3.7")
    implementation("io.ktor:ktor-server-netty:2.3.7")
    implementation("io.ktor:ktor-server-content-negotiation:2.3.7")
    implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.7")
    implementation("io.ktor:ktor-server-call-logging:2.3.7")
    
    // Ktor Client (for upstream REST calls and callbacks)
    implementation("io.ktor:ktor-client-core:2.3.7")
    implementation("io.ktor:ktor-client-cio:2.3.7")
    implementation("io.ktor:ktor-client-content-negotiation:2.3.7")
    
    // Vault Client - ONLY Executor has this dependency
    implementation("com.bettercloud:vault-java-driver:5.1.0")
    
    // Config
    implementation("com.sksamuel.hoplite:hoplite-core:2.7.5")
    implementation("com.sksamuel.hoplite:hoplite-yaml:2.7.5")
}

tasks.jar {
    manifest {
        attributes["Main-Class"] = "io.noumena.mcp.executor.ApplicationKt"
    }
}
