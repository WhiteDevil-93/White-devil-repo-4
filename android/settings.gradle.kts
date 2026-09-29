pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "WhiteDevil"
include(":app")

// The shared agent core lives at the repo root, not under android/, because the
// Compose Desktop app consumes the same module. Building it from here keeps the
// Android build the thing that proves the extraction is sound.
include(":shared")
project(":shared").projectDir = file("../shared")
