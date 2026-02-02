plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

dependencies {
    // Ktor client (for shared HTTP utilities)
    implementation("io.ktor:ktor-client-core:2.3.7")
    implementation("io.ktor:ktor-client-content-negotiation:2.3.7")
    implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.7")
}
