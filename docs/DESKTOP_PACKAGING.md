# Building the WhiteDevil desktop MSI

**Status: nothing on this page has produced an installer yet.** The MSI has never
been built, installed or launched. What has been checked, and how, is in
[section 7](#7-what-was-verified-and-what-was-not). Read that before trusting any
step here.

For switching a laptop over from the Electron app, see
[`DESKTOP_MIGRATION.md`](DESKTOP_MIGRATION.md). This page is only about producing
the MSI.

---

## 1. Which step needs what

| Step | Where it can run | Needs |
|---|---|---|
| `compileKotlin`, `test`, `run` | any OS with a JDK | nothing else. Not affected by anything below. |
| `suggestRuntimeModules`, `checkRuntime` | any OS with JDK 17+ | the JDK that runs Gradle must be a full JDK (has `jlink` and `jpackage`) |
| Build `wd-hello.exe` (`dotnet publish`) | **Windows only** | **.NET 8 SDK**. The project targets `net8.0-windows10.0.17763.0`. |
| **`packageMsi` (the step that writes the `.msi`)** | **Windows only** | **WiX Toolset 3.x** (`candle.exe` and `light.exe` reachable) **and** a full JDK 17+ running Gradle **and** `wd-hello.exe` already published |

`packageMsi` cannot be run on Linux or macOS: jpackage's MSI bundler is Windows-only
and drives WiX. There is no way around that in this repo, and nothing here pretends
otherwise.

WiX 4, 5 and 6 are not a substitute. As far as I know, jpackage in JDK 17 and 21
only drives WiX 3.x. That is from memory, not tested here. If `packageMsi` complains
it cannot find the WiX tools, that is the first thing to check. WiX 3 also needs the
".NET Framework 3.5" Windows feature enabled (again from memory).

## 2. Build steps (on the Windows machine)

Run these from the repo root on the machine that has WiX 3.x.

**a. Use a full JDK 17 or newer to run Gradle.** Set `JAVA_HOME` to it. Use a
mainstream vendor build (Temurin, Corretto, Microsoft); the Compose plugin warns
about Homebrew JDKs.

This matters more than it looks. `jvmToolchain(17)` in `build.gradle.kts` only
controls which JDK compiles the code. The Compose plugin runs `jlink` and `jpackage`
from the JDK that runs Gradle (its `javaHome` defaults to the `java.home` system
property; confirmed by reading the plugin's bytecode) and its `checkRuntime` task
refuses anything older than 17. If Gradle runs on JDK 21, the installed app bundles a
JDK 21 runtime running classes compiled for 17. That should be fine (reasoned, not
tested), but if you want the runtime to match the toolchain, run Gradle on JDK 17.

**b. Build the Windows Hello helper.**

```powershell
cd desktop\hello-helper
dotnet publish -c Release
```

or, without leaving `desktop\`, `.\gradlew.bat buildHelloHelper` (a thin wrapper
around the same command; it refuses to run off Windows).

The build settings come from `HelloHelper.csproj`:
`TargetFramework net8.0-windows10.0.17763.0`, `RuntimeIdentifier win-x64`,
`AssemblyName wd-hello`, `PublishSingleFile true`, `SelfContained false`. That
produces:

```
desktop\hello-helper\bin\Release\net8.0-windows10.0.17763.0\win-x64\publish\wd-hello.exe
```

Use `publish`, not `build`. `dotnet build` also writes a `wd-hello.exe`, one folder up
in `...\win-x64\`, but that one is only an apphost: it needs `wd-hello.dll` and
`wd-hello.runtimeconfig.json` beside it and is useless copied on its own.

**c. Build the MSI.**

```powershell
cd desktop
.\gradlew.bat packageMsi
```

Expected output (from the plugin's usual layout, not observed):
`desktop\build\compose\binaries\main\msi\WhiteDevil-1.0.0.msi`.

Or in one command: `.\gradlew.bat buildHelloHelper packageMsi`.

## 3. How `wd-hello.exe` gets into the installer

Three tasks in `desktop/build.gradle.kts`:

| Task | What it does |
|---|---|
| `checkHelloHelper` | Fails, with the instructions above in the error, unless the published exe exists, starts with the `MZ` header, and has no `wd-hello.dll` next to it (which would mean it is not a single-file publish). |
| `stageHelloHelper` | Copies the exe to `desktop\build\hello-resources\windows\wd-hello.exe`. |
| `buildHelloHelper` | Optional. Runs `dotnet publish -c Release`. Nothing depends on it. |

`nativeDistributions { appResourcesRootDir }` points at `build\hello-resources`.
The Compose plugin's `prepareAppResources` task copies `<root>\common`,
`<root>\windows` and `<root>\windows-x64` into the app's resources directory. So the
exe lands at the root of that directory, which at runtime is the system property
`compose.application.resources.dir`. In the MSI that should be
`<install dir>\app\resources\wd-hello.exe`.

Every jpackage task (`createDistributable`, `packageMsi`, `packageReleaseMsi`, ...)
depends on both `checkHelloHelper` and `stageHelloHelper`. If the exe is missing, the
build stops within seconds, before the slow jlink step, instead of producing an MSI
that installs fine and then cannot enrol a device.

Deliberately not affected: `compileKotlin`, `test`, `run`. Their task graphs do not
contain the helper tasks (checked with `--dry-run`, see section 7).

**What the desktop app does at runtime (per the DeviceAuth task, which is not in the
base commit; check it on the merged branch):** it looks first for
`<compose.application.resources.dir>\wd-hello.exe`, then falls back to
`desktop\hello-helper\bin\Release\net8.0-windows10.0.17763.0\win-x64\wd-hello.exe`.
So during development, with no MSI, either `dotnet build -c Release` or
`dotnet publish -c Release` in `hello-helper` is enough for `gradlew run`: both leave
a working exe at that fallback path (publish builds first, so it populates that folder
too, and `wd-hello.dll` sits beside the exe there). Only `publish` gives the
single-file exe the MSI needs.

**`wd-hello.exe` is framework-dependent** (`SelfContained false`). The laptop needs the
.NET 8 Runtime (x64). The MSI does not install it. If it is missing the helper will
not start, and Windows Hello enrolment will fail with no JSON on stdout. Making the
helper self-contained would remove that requirement at a cost of tens of MB; that is
a decision for whoever owns `hello-helper`, and this page does not change it.

## 4. Installer choices (and why)

Set in the `windows { }` block of `desktop/build.gradle.kts`:

| Setting | Value | Reason |
|---|---|---|
| `menu`, `menuGroup` | on, "WhiteDevil" | Start-menu entry; without it there is no visible way to launch the app. |
| `shortcut` | on | Desktop shortcut. |
| `dirChooser` | on | The operator can choose the install folder. |
| `perUserInstall` | off (per-machine) | Installs to Program Files and asks for UAC. Chosen because, as far as I recall, jpackage's per-user default folder is `%LOCALAPPDATA%\WhiteDevil`, which is exactly where `Settings.dir` keeps `settings.json` and the agent workspace. Not verified here. Pick one scope and keep it; switching later may leave two copies installed. |
| `upgradeUuid` | unchanged | Stable, so a newer MSI replaces the old one. |
| `packageVersion` | `1.0.0` | MSI wants numeric `major.minor.build` (major at most 255). **Bump it for every MSI you hand out.** |
| icon | none | jpackage needs a `.ico`. The repo only has PNG and SVG icons (`laptop-app/icon.png`, `hub/static/...`), and no binary asset was invented. The installer and the app use the default Java icon until someone adds one and sets `iconFile.set(project.file("packaging/whitedevil.ico"))`. |
| JVM args | none | None was shown to be necessary. The plugin already passes `-Dcompose.application.resources.dir=$APPDIR/resources`. |

The MSI is not code-signed, so expect a SmartScreen warning on first run (from
memory; not tested).

## 5. Runtime modules

The installed app runs on a jlink-trimmed runtime. Compose includes only
`java.base`, `java.desktop`, `java.logging` and `jdk.crypto.ec` by default, and
`build.gradle.kts` adds `java.instrument`, `java.management` and `jdk.unsupported`.

That list is what `./gradlew suggestRuntimeModules` (jdeps over the resolved
classpath) printed at the base commit. Tracing it with jdeps:

- `java.management`: referenced by ktor-utils (`IntellijIdeaDebugDetector`), which
  sits on the Ktor client's path. This one is a genuine requirement.
- `java.instrument` and `jdk.unsupported`: referenced only by kotlinx-coroutines'
  debug agent (`AgentPremain`), which the app never loads. Kept because they are
  nearly free and the plugin suggests them.
- pty4j and JNA need `java.desktop` and `java.logging`, both already default.
  pty4j's jar carries its own Windows natives (`winpty.dll`, `winpty-agent.exe`), so
  nothing extra has to be bundled for the shell.

Limits of that analysis: jdeps only sees static references, not reflection or
`ServiceLoader`. Other dependencies (for example a terminal widget) are being added
in parallel. **Re-run `.\gradlew.bat suggestRuntimeModules` on the merged branch and
compare it with the `modules(...)` line.** If an installed build fails with
`NoClassDefFoundError`, the blunt fix is `includeAllModules = true` in the same
block (bigger installer, no missing-module class of bug).

## 6. Before you hand the MSI to anyone

1. On the build machine, confirm the exe is inside the MSI. An administrative install
   unpacks the files without installing anything:
   ```powershell
   msiexec /a desktop\build\compose\binaries\main\msi\WhiteDevil-1.0.0.msi /qb TARGETDIR=C:\wd-msi-check
   dir C:\wd-msi-check -Recurse -Filter wd-hello.exe
   ```
   You should see it under `...\app\resources\`. (Layout from memory.)
2. Install it on a clean Windows user profile or VM. Launch from the Start menu.
   Check Settings opens and saves. Check the Shell opens (needs WSL).
3. Run `wd-hello.exe status` from the install folder. Expect one line of JSON with
   `"hello_available"`. On a machine without Windows Hello set up it should say so
   rather than crash.
4. Complete a device enrolment from the installed app, then confirm the device shows
   a non-null `last_seen` (see `DEVICE_AUTH_MIGRATION.md`, step 2). This is the test
   that the whole chain works: resources dir, helper, Windows Hello, hub.
5. Install a second MSI with a higher `packageVersion` over the first and confirm it
   replaces rather than duplicates.

## 7. What was verified, and what was not

Verified on a Linux box (no Windows, no WiX, no .NET SDK, no TPM):

- The Gradle task graph resolves. `packageMsi --dry-run` lists, in order:
  `checkHelloHelper`, `checkRuntime`, compile tasks, `createRuntimeImage`, `jar`,
  `stageHelloHelper`, `prepareAppResources`, `unpackDefaultComposeDesktopJvmApplicationResources`,
  `packageMsi`.
- `run`, `compileKotlin` and `test` dry-runs contain no helper tasks.
- `compileKotlin` succeeds.
- `checkHelloHelper` fails with the actionable message when the exe is absent, when it
  is not a PE file, and when a `wd-hello.dll` sits beside it. It passes and
  `stageHelloHelper` stages the file when a stand-in file with an `MZ` header is
  present. (The stand-in was a throwaway text file, deleted afterwards. It says
  nothing about a real exe.)
- The resources mechanism: a stand-in file under `<root>/linux/` was copied by
  `createDistributable` to `lib/app/resources/` in the app image, and the generated
  launcher config contains `-Dcompose.application.resources.dir=$APPDIR/resources`.
  That exercises the same plugin code as `<root>/windows/` on Windows, but the
  Windows folder name and the `app\resources` path come from the plugin's bytecode
  and memory, not from a Windows run.
- `createRuntimeImage` (jlink) accepts the three module names.
- `checkRuntime` and `suggestRuntimeModules` ran.

Not verified, and depends on a Windows host or a person:

- That `packageMsi` completes: WiX detection, jpackage on Windows, the MSI itself.
- That the MSI contains `wd-hello.exe` at `app\resources\`, that the app finds it, and
  that Windows Hello enrolment works from an installed copy.
- That `dotnet publish -c Release` produces the single-file exe at the path above
  (read from the csproj, not run), and that `buildHelloHelper` works.
- That the app starts from the jlink runtime with these modules. Nobody has launched
  the packaged app.
- The per-machine versus per-user behaviour, the upgrade behaviour, the SmartScreen
  behaviour, and the output file name and folder.
- Anything about the screens still being built in parallel.
