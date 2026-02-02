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
    
    // Ktor Server (for receiving NPL notifications) - version must match MCP SDK
    val ktorVersion = "3.2.3"
    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-netty:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    implementation("io.ktor:ktor-server-call-logging:$ktorVersion")
    
    // Ktor Client (for upstream REST calls and callbacks)
    implementation("io.ktor:ktor-client-core:$ktorVersion")
    implementation("io.ktor:ktor-client-cio:$ktorVersion")
    implementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")
    
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
