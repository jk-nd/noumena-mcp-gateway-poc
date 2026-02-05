plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    application
    id("com.gradleup.shadow") version "8.3.6"
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
    implementation("io.ktor:ktor-client-websockets:$ktorVersion")
    
    // Vault Client - ONLY Executor has this dependency
    implementation("com.bettercloud:vault-java-driver:5.1.0")
    
    // RabbitMQ - for receiving NPL notifications (network isolated)
    implementation("com.rabbitmq:amqp-client:5.20.0")
    
    // Config
    implementation("com.sksamuel.hoplite:hoplite-core:2.7.5")
    implementation("com.sksamuel.hoplite:hoplite-yaml:2.7.5")
    
    // Testing
    testImplementation("io.ktor:ktor-server-test-host:$ktorVersion")
    testImplementation("io.ktor:ktor-client-mock:$ktorVersion")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.1")
    testImplementation("io.mockk:mockk:1.13.8")
}

tasks.jar {
    manifest {
        attributes["Main-Class"] = "io.noumena.mcp.executor.ApplicationKt"
    }
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed", "standardOut", "standardError")
        showExceptions = true
        showCauses = true
        showStackTraces = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
