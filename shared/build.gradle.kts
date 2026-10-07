// Shared agent core: the tool-use loop, Venice client and tool definitions.
// Consumed by the Android app and (next) the Compose Desktop app, so there is
// one implementation of the loop rather than one per surface.
//
// Deliberately a plain Kotlin/JVM library with no Android dependencies — the
// whole package already had none, which is what made this extraction a move
// rather than a rewrite. Keep it that way: anything platform-specific
// (file pickers, prefs, pty) belongs in the consuming module.
plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
}

kotlin {
    jvmToolchain(17) // matches android/app's VERSION_17 and the JDK on this machine
}

val ktorVersion = "2.3.12"

dependencies {
    // api(), not implementation(): consumers compile against these types
    // (ChatMessage is serializable, the client returns Ktor types).
    api("io.ktor:ktor-client-core:$ktorVersion")
    api("io.ktor:ktor-client-cio:$ktorVersion")
    api("io.ktor:ktor-client-content-negotiation:$ktorVersion")
    api("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")

    testImplementation(kotlin("test"))
    testImplementation("io.ktor:ktor-client-mock:$ktorVersion")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}

tasks.test { useJUnitPlatform() }
