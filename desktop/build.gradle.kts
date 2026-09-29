plugins {
    id("org.jetbrains.kotlin.jvm") version "2.0.21"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21"
    id("org.jetbrains.compose") version "1.7.0"
}

kotlin {
    jvmToolchain(17) // matches :shared and android/app; the JDK present on this machine
}

dependencies {
    implementation(project(":shared"))            // agent loop, Venice client, tools
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")

    // A real pty, not a pipe: wsl.exe needs a terminal on the other end or
    // interactive programs (and the shell prompt itself) misbehave. This is what
    // lets the Shell tab drop ttyd and the paste-queue bridge entirely.
    implementation("org.jetbrains.pty4j:pty4j:0.12.13")

    // Ktor client + kotlinx-serialization arrive transitively via :shared (api).
    testImplementation(kotlin("test"))
    testImplementation("io.ktor:ktor-client-mock:2.3.12")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}

tasks.test { useJUnitPlatform() }

compose.desktop {
    application {
        mainClass = "com.whitedevil.desktop.MainKt"
        nativeDistributions {
            targetFormats(org.jetbrains.compose.desktop.application.dsl.TargetFormat.Msi)
            packageName = "WhiteDevil"
            packageVersion = "1.0.0"
            windows {
                menuGroup = "WhiteDevil"
                // Stable UUID so upgrades replace rather than stack.
                upgradeUuid = "9C7A1B24-4D3E-4F51-9A2C-6E8D5B0F1A73"
            }
        }
    }
}
