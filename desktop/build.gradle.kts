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
            // MSI needs numeric major.minor.build (major <= 255). Bump this for every
            // MSI you hand out: an upgrade is keyed on upgradeUuid + a higher version.
            packageVersion = "1.0.0"
            description = "WhiteDevil desktop: agent, shell and render tools"
            vendor = "WhiteDevil"

            // jlink builds a trimmed runtime, and Compose only includes java.base,
            // java.desktop, java.logging and jdk.crypto.ec by default. These three are
            // what `./gradlew suggestRuntimeModules` (jdeps over the resolved classpath)
            // printed at the base commit. Traced with jdeps: java.management is
            // ktor-utils' IntellijIdeaDebugDetector (java.lang.management), which is on
            // the Ktor client's path, so it is a real requirement; java.instrument and
            // jdk.unsupported are only kotlinx-coroutines' debug agent (AgentPremain),
            // which the app never loads, kept because they cost almost nothing.
            // jdeps cannot see reflection or ServiceLoader use, and other dependencies
            // (e.g. a terminal widget) land in parallel: RE-RUN suggestRuntimeModules on
            // the merged branch. If a packaged build dies with NoClassDefFoundError,
            // replace this line with `includeAllModules = true`.
            modules("java.instrument", "java.management", "jdk.unsupported")

            // wd-hello.exe (Windows Hello helper) is staged under <root>/windows by the
            // stageHelloHelper task at the bottom of this file; Compose copies that
            // folder into the packaged app's resources dir. Read the note down there
            // before changing this.
            appResourcesRootDir.set(layout.buildDirectory.dir("hello-resources"))

            windows {
                // Start-menu entry plus a desktop shortcut; without `menu` the MSI
                // installs no visible way to launch the app.
                menu = true
                menuGroup = "WhiteDevil"
                shortcut = true
                // Let the operator pick the install folder.
                dirChooser = true
                // Per-machine (Program Files, UAC prompt), deliberately NOT per-user:
                // as far as I recall jpackage's per-user default folder is
                // %LOCALAPPDATA%\WhiteDevil, which is exactly where Settings.dir keeps
                // settings.json and the agent workspace (unverified: no Windows here).
                // Pick one scope and keep it: switching later may leave two installs
                // side by side instead of upgrading.
                perUserInstall = false
                // Stable UUID so upgrades replace rather than stack.
                upgradeUuid = "9C7A1B24-4D3E-4F51-9A2C-6E8D5B0F1A73"
                // No iconFile: jpackage needs a .ico and the repo has none (only
                // PNG/SVG under laptop-app/ and hub/static/). Add one, then set
                // `iconFile.set(project.file("packaging/whitedevil.ico"))` here.
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Windows Hello helper (wd-hello.exe) ships INSIDE the installer.
//
// The app shells out to wd-hello.exe for Windows Hello. It is a .NET 8 project that
// only builds on Windows, so Gradle does not build it as part of the normal graph:
// it CHECKS that the published exe exists (checkHelloHelper) and STAGES it
// (stageHelloHelper) where Compose picks it up. buildHelloHelper is an optional
// convenience that runs `dotnet publish`; nothing depends on it.
//
// Layout: Compose's prepareAppResources copies <appResourcesRootDir>/common,
// /windows and /windows-x64 into the app's resources dir ROOT (the host OS decides
// which folders apply). At runtime that dir is the system property
// compose.application.resources.dir, i.e. <install dir>\app\resources in the MSI. So
// build/hello-resources/windows/wd-hello.exe ends up at <resources dir>\wd-hello.exe.
//
// Scope: only the jpackage tasks (createDistributable, packageMsi, packageReleaseMsi,
// ...) depend on the check. The staging dir is deliberately NOT tied to
// prepareAppResources by a task-output link, because `run` depends on that task and
// compileKotlin / test / run must keep working without the helper built.
// ---------------------------------------------------------------------------
val helloHelperDir = layout.projectDirectory.dir("hello-helper").asFile

// HelloHelper.csproj: TargetFramework net8.0-windows10.0.17763.0, RuntimeIdentifier
// win-x64, AssemblyName wd-hello, PublishSingleFile=true, SelfContained=false.
// `dotnet publish -c Release` therefore yields a single-file exe in .../win-x64/publish/.
// The `dotnet build` output one level up is NOT self-sufficient (it needs wd-hello.dll
// and wd-hello.runtimeconfig.json beside it), so it only serves as the dev-time fallback.
val helloPublishedExe = File(helloHelperDir, "bin/Release/net8.0-windows10.0.17763.0/win-x64/publish/wd-hello.exe")

// Same directory as nativeDistributions.appResourcesRootDir above (single source of truth).
val helloStagingRoot = compose.desktop.application.nativeDistributions.appResourcesRootDir

val checkHelloHelper by tasks.registering {
    group = "distribution"
    description = "Fails unless wd-hello.exe has been published (dotnet publish in hello-helper). " +
        "Gates every jpackage task; not part of compileKotlin/test/run."
    val exe = helloPublishedExe
    doLast {
        val header = if (exe.isFile) exe.inputStream().use { s -> ByteArray(2).also { s.read(it) } } else ByteArray(0)
        val problem: String? = when {
            !exe.isFile -> "wd-hello.exe was not found; expected ${exe.path}"
            header.size < 2 || header[0] != 'M'.code.toByte() || header[1] != 'Z'.code.toByte() ->
                "${exe.path} is not a Windows executable (no MZ header)."
            File(exe.parentFile, "wd-hello.dll").exists() ->
                "${exe.path} is not a single-file publish (wd-hello.dll sits beside it), " +
                    "so copying the exe alone would ship a helper that cannot start."
            else -> null
        }
        if (problem != null) {
            throw GradleException(
                """
                Refusing to package WhiteDevil without its Windows Hello helper.
                $problem

                wd-hello.exe is what the installed app calls to create and use the Windows
                Hello / TPM device key. An MSI without it installs fine and then fails at
                device enrolment, so packaging stops here instead of shipping that.

                Build it on Windows with the .NET 8 SDK installed (the helper cannot be
                built on Linux/macOS), then re-run the packaging task:
                    cd desktop\hello-helper
                    dotnet publish -c Release
                or, from desktop\ in one go:
                    .\gradlew.bat buildHelloHelper packageMsi
                Details: docs/DESKTOP_PACKAGING.md
                """.trimIndent()
            )
        }
    }
}

val stageHelloHelper by tasks.registering(Sync::class) {
    group = "distribution"
    description = "Copies wd-hello.exe into build/hello-resources/windows (Compose appResourcesRootDir)."
    dependsOn(checkHelloHelper)
    from(helloPublishedExe)
    into(helloStagingRoot.map { it.dir("windows") })
}

val buildHelloHelper by tasks.registering(Exec::class) {
    group = "distribution"
    description = "Windows only: runs `dotnet publish -c Release` in hello-helper (needs the .NET 8 SDK). " +
        "Optional; nothing depends on it. Run it before packageMsi."
    workingDir = helloHelperDir
    commandLine("dotnet", "publish", "-c", "Release")
    doFirst {
        check(System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
            "buildHelloHelper only works on Windows: wd-hello.exe targets net8.0-windows10.0.17763.0 " +
                "and the installer itself needs Windows + WiX 3.x. See docs/DESKTOP_PACKAGING.md."
        }
    }
}

// `gradlew buildHelloHelper packageMsi` must build before it checks.
checkHelloHelper.configure { mustRunAfter(buildHelloHelper) }

// Every jpackage task (createDistributable, packageMsi, packageReleaseMsi, ...) needs
// the helper. Matched by type so a task added by a later Compose upgrade is covered too.
tasks.withType<org.jetbrains.compose.desktop.application.tasks.AbstractJPackageTask>().configureEach {
    dependsOn(checkHelloHelper, stageHelloHelper)
}

// Ordering only (no dependency edge, so dev tasks stay unaffected): fail before the slow
// jlink step, and stage before Compose snapshots the resources folder.
tasks.matching { it.name == "createRuntimeImage" }.configureEach { mustRunAfter(checkHelloHelper) }
tasks.matching { it.name == "prepareAppResources" }.configureEach { mustRunAfter(stageHelloHelper) }
