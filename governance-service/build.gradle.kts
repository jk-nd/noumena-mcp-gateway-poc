plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    application
    id("com.gradleup.shadow") version "8.3.6"
}

application {
    mainClass.set("io.noumena.mcp.governance.ApplicationKt")
}

dependencies {
    implementation(project(":shared"))

    // Ktor Server (slim — only what ext_authz needs)
    val ktorVersion = "3.2.3"
    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-netty:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    implementation("io.ktor:ktor-server-status-pages:$ktorVersion")
    implementation("io.ktor:ktor-server-call-logging:$ktorVersion")

    // Ktor Client (for NPL Engine and Keycloak calls)
    implementation("io.ktor:ktor-client-core:$ktorVersion")
    implementation("io.ktor:ktor-client-cio:$ktorVersion")
    implementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")

    // Testing
    testImplementation("io.ktor:ktor-server-test-host:$ktorVersion")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.1")
}

tasks.jar {
    manifest {
        attributes["Main-Class"] = "io.noumena.mcp.governance.ApplicationKt"
    }
}

tasks.test {
    useJUnitPlatform()
}
