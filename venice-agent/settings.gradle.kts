// mavenCentral() is where the Kotlin Gradle plugin artifacts already sit in the
// shared Gradle cache (the android module resolves them there), so listing it lets
// this build resolve its plugins offline instead of only from the plugin portal.
pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

// :shared declares no repositories of its own (it is normally built from
// android/settings.gradle.kts, which supplies them here). Default mode is
// PREFER_PROJECT, so this module's own repositories block still wins for
// :venice-agent and :shared falls back to these.
dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "venice-agent"

// Same wiring as android/settings.gradle.kts: the shared agent core lives at the
// repo root and is included by path so the CLI and the app compile the one copy.
include(":shared")
project(":shared").projectDir = file("../shared")
