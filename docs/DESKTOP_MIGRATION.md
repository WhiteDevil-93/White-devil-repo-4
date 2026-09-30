# Moving from the Electron app (`laptop-app/`) to the native desktop app (`desktop/`)

Reconciled against `main` (`laptop-app/` = **Forge Hub 1.0.1**, `desktop/` = the tree that ships the MSI).
The Electron feature list here is read from `laptop-app/main.cjs`, `config.cjs`, `preload.cjs`,
`start.html`, `settings.html`, `package.json` and the scripts as they are on `main`. An earlier version of this
page described a different Electron app (WhiteDevil Agent 1.1.x, from a branch whose Electron changes were dropped
when it was merged); none of that is in `laptop-app/` now, and section 3 says which of those features are gone.

**Read this first.**

- **Nothing here has been run on Windows.** Not the desktop app, not the MSI (which has
  never been built), not `wd-hello.exe`, not a TPM. Everything below comes from reading
  code, plus Gradle and unit-test runs on Linux described in
  [`DESKTOP_PACKAGING.md`](DESKTOP_PACKAGING.md#7-what-was-verified-and-what-was-not).
- **Code that compiles and passes unit tests is not code that has been seen working.**
  Every native screen in section 2 has code on `main`; none has been run on Windows or against the live
  hub. Do not trust a status here over what you see on screen.
- **`laptop-app/` is not deleted and this page does not delete it.** It is marked deprecated in its
  README. The operator decides when to remove it, after the checks in section 5.
- Statements I could not check are marked *(uncertain)*.

---

## 1. What moved

| | Electron app (`laptop-app/`) | Native app (`desktop/`) |
|---|---|---|
| What it is | One Electron window that loads the relay's web shell, `<relay>/app/desktop/`, directly. Every screen is the hub's own web page. The native part is the menu, tray, settings window and the sign-in page. | A Compose Desktop window (Kotlin/JVM), no webview. A native nav rail with native screens. |
| Agent loop | Runs on the relay, in the web UI (`hub/venice.py`, key from the hub's own environment or `~/.venice_key`) | Runs on the laptop: `:shared` `Agent`, the same class the Android app uses. Calls Venice directly with the key in local settings. Tools that need the hub call it over HTTP. |
| Shell | Hub Shell screen: a ttyd page (`/laptop/term/`), signed in with the separate "laptop" user and password | pty4j straight into `wsl.exe` (ConPTY, winpty fallback), JediTerm for emulation. No ttyd, no relay in the path. |
| Auth to the relay | Relay basic-auth password only. Electron adds it to every request to the hub's host and answers Caddy's challenge. | Relay basic-auth password on every native request, plus an optional per-device Windows Hello / TPM key (`wd-hello.exe`, enrol and sign in from Settings). See [`DEVICE_AUTH_MIGRATION.md`](DEVICE_AUTH_MIGRATION.md). |
| Settings file | `settings.json` in Electron's user-data folder, expected `%APPDATA%\Forge Hub` *(uncertain: Electron's default is `%APPDATA%\<productName>` and the product name is "Forge Hub"; not checked on Windows)* | `%LOCALAPPDATA%\WhiteDevil\settings.json` (`Settings.kt`) |
| Packaging | `npm run dist:win` gives `Forge-Hub-Setup.exe` (NSIS, per-user) and `Forge-Hub-Portable.exe`; `npm run dist:linux` an AppImage | `gradlew packageMsi` gives an MSI. Needs Windows, WiX 3.x and a published `wd-hello.exe`. See [`DESKTOP_PACKAGING.md`](DESKTOP_PACKAGING.md). |

What did **not** move: the hub on the VM is still the system of record and the phone is
unaffected. The desktop app is a client of the hub, not a replacement.

## 2. Desktop screens: native, or not

**Native (8 screens plus Settings).** The desktop navigation is the `Screen` enum in
`desktop/src/main/kotlin/com/whitedevil/desktop/Main.kt`. "Code on main" means implemented, compiles and has
unit tests on Linux; it has never been run on Windows or against the live hub.

| Desktop screen | Electron equivalent | What the code does | What to do |
|---|---|---|---|
| Agent | Agent (`/app/venice/`) | Chat, tool events, Stop, Clear (`AgentScreen.kt`). **A new `Agent` object is built for every message, so the model sees only the current message, not the earlier ones; the transcript on screen is memory only and is lost on restart** (read from `AgentScreen.kt`). | Decide whether that matters to you before you rely on it for multi-step work |
| Shell (`Terminal`) | Hub Shell (`/laptop/term/`) | JediTerm emulation, pty4j into `wsl.exe`, ConPTY with a winpty fallback (the header says which is in use), session kept across tab switches, child killed on exit. Exercised on Linux only, with a fake `wsl.exe`. | Run the shell checks in section 5 on the real laptop |
| Renders | Hub Renders | Grouped list from `/api/media/library`; failure is shown as an error, never as an empty list. The hub returns no render *status* field, so none is shown. | Try against the real hub |
| Gallery | Gallery (`/app/gallery/`) | Lazy bounded thumbnail cache, preview, contact sheet on button press | Try against the real hub with a large library |
| Colab | Hub Colab | Status, usage and session, polled every 30s. Stop and Start need a typed confirmation. Note that the hub's `GET /api/colab/state` has a side effect on the hub (it can restart the Colab tunnel service), so merely opening this screen can do that. | Look at it read-only first; do not press Stop or Start until you have |
| Thunder | Hub Thunder | Queue, instances, snapshots. Every spend or destructive action is behind a confirmation; submit honours `ok:false`. Field names for instances and pricing came from the web page, not the hub source. | Try against the real hub |
| Vast | Hub Vast | Instances and offers; rent, start, stop and delete behind confirmations | Try against the real hub |
| Setup | Hub Setup | `enabled=0` shown as "not yet saved"; save behind a confirmation | Try against the real hub |
| Settings | Settings window (Ctrl+,) | Relay, Venice and model settings, plus a **Device key** section: enrol this PC, sign in, check Windows Hello, sign out, with a confirmation before a re-enrol replaces the key. Details in section 4. | Do the enrolment check in section 5 |

**Not native, and no plan in the repository to make them native.** The hub's `hub/screens.json`
also lists the screens below. The desktop `Screen` enum has nothing for them. Until someone ports them they are
reachable only in the Electron app or in a browser at `<relay>/app/desktop/`.

| Hub screen | Where it lives | Note |
|---|---|---|
| **LTX** | `/api/ltx/*` (`hub/ltx.py`) and the hub's LTX page | **The gap. It stays on the web on purpose:** LTX starts GPU renders, installs models on the Colab and runs a render-review cycle, and that needs a deliberate native design, not a copy of the web page. The desktop **Agent** can already reach a few LTX routes through its tools (`/cycle start|status|stop`, `render_assess_adjust_cycle`, `queue_gpu_render`, `hub_request`); that is a chat tool, not a screen, and it spends GPU time when it runs. |
| Hub Home | web | |
| HypnoForge | web | |
| Files | `/laptop/files/` (needs the laptop user and password) | Shell is native; Files is not |
| Shotwriter and the prompt-creator generator pages (Colab 5B, Thunder 14B) | web | |
| Bot screens | added dynamically by the hub's manifest (`group: bots`) | |

The Electron app is the only installed way to see these inside a window. In a browser they need the relay
password; the device-auth migration (below) affects that too.

## 3. What the Electron app does that the desktop app does not

Enumerated from `laptop-app/` on `main`: `main.cjs`, `preload.cjs`, `config.cjs`, `start.html`,
`settings.html`, `package.json`, `install-home.sh`, `run.sh`, `build-win.sh` and `test/`.

| # | Electron feature (where) | Desktop | Status |
|---|---|---|---|
| 1 | Hosts the whole web hub, so LTX, HypnoForge, Files, Shotwriter, Home and bot screens all work in the window (it loads `/app/desktop/`) | Only the native screens in section 2 | **Not in desktop.** LTX is the gap; see section 2 |
| 2 | System-tray icon: Open Forge Hub / Venice Agent / Settings / Quit (`createTray`) | None | Not in desktop |
| 3 | App menu and shortcuts: Ctrl+1 Home, Ctrl+Shift+V Venice Agent, Ctrl+Shift+T Shell, Ctrl+, Settings, Ctrl+R Reload; Renders has a menu entry but no shortcut (`buildMenu`) | Mouse-driven nav rail only | Not in desktop |
| 4 | Single-instance lock: a second launch brings the first window forward (`requestSingleInstanceLock`) | None found (grep of `desktop/src/main`) | Not in desktop. A second launch probably opens a second window and a second shell *(uncertain: read, not run)* |
| 5 | First run probes a local hub at `127.0.0.1:43173` and prefers it (`probeLocalHub`, `firstRunDefaults`) | Default hub is the relay (`Settings.DEFAULT_HUB_URL`) | Not in desktop. Type a local URL into Settings if you run one |
| 6 | "Test connection": `GET /api/manifest` with basic auth, explains a 401 (`settings:test`) | No button | Not in desktop. The Renders, Gallery and ops screens show the hub's or the network's reason when a request fails, which is the closest equivalent |
| 7 | Sign-in page (`start.html`) instead of a black window when the password is missing, the relay answers 401, or the page fails to load | A native screen that gets a 401/403 shows an error that says to check the relay user and password in Settings (`OpsHttp.kt`, `MediaErrors.kt`), never a blank panel. Agent additionally says "Add your Venice API key in Settings" when the key is missing. | Different, and covers the same failure |
| 8 | Separate "laptop" credentials for `/laptop/term/` and `/laptop/files/` (`credsFor`, `isLaptopPath`) | None. Shell talks to `wsl.exe` directly, so it needs no credential. Files has no native screen. | Shell: not needed. Files: not covered |
| 9 | Sends the relay password to every request for the hub's host and answers Caddy's challenge (`onBeforeSendHeaders`, `app.on("login")`) | Native screens send it on every request (`OpsHttp.kt`, `MediaClient.kt`) | Equivalent, for native screens only |
| 10 | Environment overrides: `FORGE_HUB_URL`, `FORGE_RELAY_USER`/`_PASS`, `FORGE_LAPTOP_USER`/`_PASS` (first-run defaults), `FORGE_DEBUG=1` (DevTools), `FORGE_SANDBOX=1` (Linux) | None for any of the settings. `Settings.kt` reads only `LOCALAPPDATA`. | Not in desktop |
| 11 | Grants every web permission request the hub page makes (`setPermissionRequestHandler`) | No web content | By design |
| 12 | Opens external `http(s)` links in the default browser | No web content | By design |
| 13 | Disables GPU compositing on Windows to avoid blank windows (`disableHardwareAcceleration`); disables the Chromium sandbox on Linux unless `FORGE_SANDBOX=1` | Compose draws through Skia instead. If a blank or garbled window ever appears, the Skiko render API is the thing to try. *(uncertain: not tested)* | Different renderer |
| 14 | Windows: NSIS `Forge-Hub-Setup.exe` (per-user, choose folder, desktop and Start-menu shortcuts) and `Forge-Hub-Portable.exe`; Linux AppImage; WSL helpers `install-home.sh` (copies to `~/laptop-app`, writes `~/.local/bin/forge-hub`), `run.sh`, `build-win.sh` | MSI only, per-machine. The Shell also needs `wsl.exe`, so the app is Windows-only in practice. | Not in desktop: no portable exe, no Linux or WSLg path |
| 15 | Node tests for URL normalisation, settings merge, the install script and the installer artifact names (`npm test`, `test/`) | JUnit tests under `desktop/src/test` | Different suites; neither covers the other app |

**Things an earlier version of this page listed that `laptop-app/` on `main` does not do:** import of
`relay_access.txt`, import of Venice or OpenRouter keys from local files, Venice and OpenRouter key fields,
pushing keys to the relay on save and launch, remembering the last domain (`WD_START_DOMAIN`), the attach-media
file dialog, a `shell.html`/`signin.html` rail, and the Ctrl+1..4 domain shortcuts. They belonged to the
dropped Electron lineage. Only rely on them if you are running a build made from that lineage, not from `main`.
On `main`, neither app pushes a key to the relay: the hub's Venice key comes from its own environment or
`~/.venice_key`, and `POST /api/gen/key` (OpenRouter) is never called by either app.

What the desktop app has that Electron does not: an agent loop that runs on the laptop instead
of the relay, a real pty shell, native ops screens with confirmations on anything that spends or destroys, and a
hardware-backed device key.

**Version note.** `laptop-app/package.json` is 1.0.1 ("Forge Hub"). The desktop MSI starts at 1.0.0 ("WhiteDevil").
The two have different names now, so a Start-menu entry or shortcut tells you which is which.

## 4. Settings: field by field

| Electron field (`config.cjs`) | Desktop field (`Settings.kt`) | Notes |
|---|---|---|
| `hubUrl` | `hubUrl` | **Do not copy the value across.** Electron stores a full URL ending in `/app/desktop/` (it adds that path itself: `normalizeHubUrl`). The desktop app appends `/api/...` to whatever you enter, so pasting the Electron value makes requests go to `/app/desktop/api/...` and fail. Enter the origin only: `https://84-12-112-249.sslip.io`. |
| `relayUser`, `relayPass` | `relayUser`, `relayPass` | Same Caddy basic-auth credential (Electron default user `anon3`, as is the desktop's). |
| `laptopUser`, `laptopPass` | none | Only used for `/laptop/term/` and `/laptop/files/`. Not needed for the desktop Shell; Files has no native screen. |
| none | `veniceApiKey` | The desktop agent calls Venice itself, so it needs a key. Electron has no Venice key field: its agent uses the hub's key. |
| none | `model`, `enableWebSearch` | Default model `zai-org-glm-5-2`. |
| none | `deviceId`, `deviceName` | Filled in by enrolment (Settings, Device key). |

There is no importer. Re-enter the values by hand. Both apps store secrets in plaintext in their own settings
file (Electron writes it with mode 600, which does nothing on Windows). The desktop Settings screen says so.

The two folders have similar names under different roots, so be careful when cleaning up: Electron's is
expected under `%APPDATA%\Forge Hub` *(uncertain)*, the desktop app's is `%LOCALAPPDATA%\WhiteDevil`.

## 5. Switching over

The order matters. Keep the Electron app installed and working until step 6.

**0. Read [`DEVICE_AUTH_MIGRATION.md`](DEVICE_AUTH_MIGRATION.md) once.** The two migrations touch each other,
see "The one trap" below.

**1. Get an MSI.** None exists yet: it has never been built. Someone with a Windows machine, WiX Toolset 3.x, a
full JDK and the .NET 8 SDK builds it as described in [`DESKTOP_PACKAGING.md`](DESKTOP_PACKAGING.md). In short:

```powershell
cd desktop\hello-helper
dotnet publish -c Release          # produces wd-hello.exe (Windows only, .NET 8 SDK)
cd ..
.\gradlew.bat packageMsi
```

**`packageMsi` fails on purpose if `wd-hello.exe` has not been published.** That is a deliberate gate
(`checkHelloHelper` and `stageHelloHelper` in `desktop/build.gradle.kts`), not a bug: an MSI without the
Windows Hello helper installs fine and then cannot enrol a device. The gate checks that the published exe exists,
starts with the `MZ` header, and has no `wd-hello.dll` beside it. It applies to every jpackage task, and does not
affect `compileKotlin`, `test` or `run`. `.\gradlew.bat buildHelloHelper packageMsi` does both steps in one
command.

On the laptop you need:

- the **.NET 8 Runtime (x64)**: `wd-hello.exe` is framework-dependent and the MSI does not install .NET;
- **WSL**, for the Shell tab;
- **Windows Hello** set up (a PIN or fingerprint in Windows Settings, Accounts, Sign-in options), for device
  enrolment.

**2. Install.** Run the MSI. It is per-machine, so expect a UAC prompt, and it lets you pick the folder. It adds a
Start-menu group and a desktop shortcut, both called "WhiteDevil". The Electron app is called "Forge Hub", so the
two are told apart by name.

**3. First run: Settings.** Enter the hub origin (section 4), relay user and password, Venice API key, and model.
Save. There is no Test button, so the proof is sending a message in Agent and having it call a hub tool.

**4. Enrol the laptop.** In Settings, Device key: enter a device name (and an enrolment code if the hub requires
one; get one from an already-trusted session with `POST /api/auth/enrol-code`), press Enrol this PC, then Sign in.
Windows Hello should prompt you each time. Then confirm from any machine:

```bash
curl -s -u relay:THE_RELAY_PASSWORD https://84-12-112-249.sslip.io/api/auth/devices
```

The laptop must show a non-null `last_seen`. A device that enrolled but never signed in is not enrolled for this
purpose. Enrolment is additive: if it fails, the app carries on with the relay password as before.

**5. Operator checks, on the real laptop, before Electron goes.** Tick every line that matters to you; anything
you cannot tick is a reason to keep Electron.

- [ ] Agent: a conversation works, a hub tool call works (for example the render status), Stop works, and you
      have decided whether the no-memory-across-messages behaviour in section 2 matters to you.
- [ ] Shell: header reads `wsl · connected · ConPTY`, and **`vim` and `top` are usable**, resize keeps
      `stty size` correct, and closing the app leaves no `wsl.exe`.
- [ ] Each screen you use daily (Renders, Gallery, Colab, Thunder, Vast, Setup) actually loads real data from
      the hub. They compile and pass unit tests; none has been run against the live hub.
- [ ] For every screen with no native version (Hub Home, HypnoForge, **LTX**, Files, Shotwriter, bots), you have
      a plan: browser, or keep Electron. See "The one trap".
- [ ] Persistence: quit and relaunch, and Settings are still there.
- [ ] Installed-location check: `<install dir>\app\resources\wd-hello.exe` exists, and enrolment worked from the
      installed app, not just from a dev build. (A dev build finds the helper through a fallback path; the
      installed app must find it in its resources directory, and does not search the working directory.)
- [ ] Upgrade path: one MSI installed over an older one replaced it rather than duplicating it.

**6. Only then remove Electron.**

1. Uninstall it: Windows Settings, Apps, "Forge Hub" (NSIS build; it installs per-user), or delete the portable
   `Forge-Hub-Portable.exe`.
2. Delete Electron's settings folder (expected `%APPDATA%\Forge Hub` *(uncertain)*). It holds the relay and laptop
   passwords in plaintext. **Do not delete `%LOCALAPPDATA%\WhiteDevil`; that is the desktop app's.**
3. If you ever ran `laptop-app/install-home.sh` in WSL, remove `~/laptop-app` and the `~/.local/bin/forge-hub`
   launcher it wrote.
4. Leave the `laptop-app/` folder in the repository until you decide to delete it.

**Rolling back** before step 6 is nothing: Electron is still there. After step 6, reinstall it from
`laptop-app/` with `npm install` and `npm run dist:win`.

### The one trap: Electron and the device-auth migration

The Electron app authenticates with the relay basic-auth password and nothing else. It has no code for the device
key or `/api/auth/*` (checked by grep). The device-auth migration removes or stops honouring that password in its
later steps:

- **Step 6 there (`strict`) will lock the Electron app out**, and any browser tab, for certain: `strict` accepts
  device tokens only.
- **Step 5 there (removing Caddy's `basic_auth`) may also break the Electron window** *(inference, not tested)*.
  Electron only sends the password after the server challenges for it in some paths (`app.on("login")`) and
  otherwise adds it to requests for the hub's host; once Caddy stops challenging, the hub's own API calls from
  the web pages may go out without the header if a page does not send its own credentials.

So: get the desktop app configured and enrolled (steps 3 and 4 of this page) **before** you take the device-auth
migration past its step 4, and do not treat Electron as your fallback after that point. The same applies to any
browser you use for the screens with no native version, which now includes LTX.

## 6. Known gaps and unknowns

- No part of this has been run on Windows. That covers the desktop app, the packaged app, `wd-hello.exe`, WSL
  from the app, and the MSI (never built).
- The section 2 table reflects `main`: every native screen has code, none has been run on Windows or against the
  live hub.
- I did not compare the desktop Agent's tool set with the relay Agent's tool set in detail. `:shared` `ToolBox`
  (used by desktop, shared with Android) and `hub/venice.py` (used by the Electron Agent) are separate
  implementations.
- The desktop Agent keeps no conversation memory across messages (section 2).
- Whether Hub Home, HypnoForge, LTX, Files, Shotwriter and the bot screens ever get native screens is undecided;
  I found no plan for them in the repository.
