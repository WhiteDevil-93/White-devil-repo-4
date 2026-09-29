// The Compose Desktop app: the laptop-end interface, native rather than a
// webview. It consumes the same :shared agent core as the Android app, which is
// the whole reason this is Kotlin and not C# or Python.
pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        // pty4j's purejavacomm transitive is published here, not on mavenCentral.
        maven("https://packages.jetbrains.team/maven/p/ij/intellij-dependencies")
    }
}

rootProject.name = "whitedevil-desktop"

// Same wiring as android/settings.gradle.kts — shared/ lives at the repo root
// precisely so both surfaces can build it.
include(":shared")
project(":shared").projectDir = file("../shared")
