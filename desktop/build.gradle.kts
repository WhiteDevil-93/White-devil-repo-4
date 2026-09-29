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

    // --- Terminal emulation (Shell tab: TerminalScreen.kt / Terminal.kt) -------------
    // JediTerm is the VT/xterm emulator + Swing widget that IntelliJ's terminal is built
    // on. It is not on Maven Central; it resolves from the JetBrains intellij-dependencies
    // repository already declared in settings.gradle.kts. jediterm-ui lists jediterm-core
    // as runtime-only, so both are declared. There is no pty4j coupling: the TtyConnector
    // is ours (WslTtyConnector), so pty4j stays at 0.12.13 above.
    // 3.53 is pinned on purpose: it is the last release built with Kotlin 1.9.x. 3.54-3.74
    // are built with Kotlin 2.1 (stdlib 2.1.21 would be forced onto this project's 2.0.21
    // compiler) and 3.76 needs kotlin-stdlib 2.4.0, whose metadata a 2.0.21 compiler cannot
    // read. Bump only together with the Kotlin plugin version.
    implementation("org.jetbrains.jediterm:jediterm-core:3.53")
    implementation("org.jetbrains.jediterm:jediterm-ui:3.53")
    // --- end terminal emulation --------------------------------------------------------

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
