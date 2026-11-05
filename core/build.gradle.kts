// Pure Kotlin/JVM: OBDLink protocol, signal definitions, interpretation, acquisition session.
// No Android dependency, so everything here is testable with `./gradlew :core:test`.
plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
}

kotlin { jvmToolchain(21) }

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.12.0")
    testImplementation(kotlin("test-junit"))
}
