plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

dependencies {
    // Ktor client (for shared HTTP utilities) - version must match MCP SDK
    val ktorVersion = "3.2.3"
    implementation("io.ktor:ktor-client-core:$ktorVersion")
    implementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    
    // Config loading
    implementation("com.sksamuel.hoplite:hoplite-core:2.7.5")
    implementation("com.sksamuel.hoplite:hoplite-yaml:2.7.5")
}
