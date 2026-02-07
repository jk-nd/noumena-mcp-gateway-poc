plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

dependencies {
    // Ktor Client for HTTP calls
    val ktorVersion = "3.2.3"
    implementation("io.ktor:ktor-client-core:$ktorVersion")
    implementation("io.ktor:ktor-client-cio:$ktorVersion")
    implementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    implementation("io.ktor:ktor-client-websockets:$ktorVersion")
    
    // Shared models
    implementation(project(":shared"))
    
    // Testing
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
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
    
    // Integration tests need the Docker stack running
    environment("NPL_URL", System.getenv("NPL_URL") ?: "http://localhost:12000")
    environment("KEYCLOAK_URL", System.getenv("KEYCLOAK_URL") ?: "http://localhost:11000")
    environment("GATEWAY_URL", System.getenv("GATEWAY_URL") ?: "http://localhost:8000")
    // RabbitMQ removed in V2 architecture
}
