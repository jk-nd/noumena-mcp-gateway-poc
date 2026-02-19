plugins {
    kotlin("jvm") version "2.2.21" apply false
    kotlin("plugin.serialization") version "2.2.21" apply false
}

allprojects {
    group = "io.noumena.mcp"
    version = "0.1.0-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "org.jetbrains.kotlin.plugin.serialization")

    dependencies {
        // Kotlin
        "implementation"(kotlin("stdlib"))
        
        // Coroutines
        "implementation"("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
        
        // Serialization
        "implementation"("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")
        
        // Logging
        "implementation"("io.github.microutils:kotlin-logging-jvm:3.0.5")
        "implementation"("ch.qos.logback:logback-classic:1.4.14")
        
        // Testing
        "testImplementation"(kotlin("test"))
        "testImplementation"("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    }

    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
            freeCompilerArgs.add("-Xjsr305=strict")
        }
    }

    tasks.withType<Test> {
        useJUnitPlatform()
    }
}
