# Moving from the Electron app (`laptop-app/`) to the native desktop app (`desktop/`)

**Read this first.**

- **Nothing here has been run on Windows.** Not the desktop app, not the MSI (which has
  never been built), not `wd-hello.exe`, not a TPM. Everything below comes from reading
  code, plus Gradle checks on Linux described in
  [`DESKTOP_PACKAGING.md`](DESKTOP_PACKAGING.md#7-what-was-verified-and-what-was-not).
- **The desktop app is mid-flight.** It is being finished by several people in parallel.
  Statements marked "at base" describe branch `cursor/full-device-integration-1b6f`
  at commit `db49f77` (plus the packaging change). **Verify anything marked "landing in
  the same series" on the merged branch.** Do not trust a status here over what you see
  on screen.
- **`laptop-app/` is not deleted and this page does not delete it.** The operator decides
  when, after the checks in section 5.
- Statements I could not check are marked *(uncertain)*.

---

## 1. What moved

| | Electron app (`laptop-app/`) | Native app (`desktop/`) |
|---|---|---|
| What it is | An Electron window with a native rail and iframes of the relay's web UIs (`/app/venice/`, `/app/desktop/#...`, `/app/gallery/`) | A Compose Desktop window (Kotlin/JVM), no webview |
| Agent loop | Runs on the relay, in the web UI (`hub/venice.py`) | Runs on the laptop: `:shared` `Agent`, the same class the Android app uses. Calls Venice directly with the key in local settings. Tools that need the hub call it over HTTP. |
| Shell | Hub, Shell screen: a ttyd iframe (`/app/term/`), laptop credentials for `/laptop/term/` | pty4j straight into `wsl.exe`. No ttyd, no relay in the path. |
| Auth to the relay | Relay basic-auth password only | Relay basic-auth password today; a per-device Windows Hello / TPM key via `wd-hello.exe` is being added (see [`DEVICE_AUTH_MIGRATION.md`](DEVICE_AUTH_MIGRATION.md)) |
| Settings file | `settings.json` in Electron's user-data folder, expected `%APPDATA%\WhiteDevil` *(uncertain: Electron default for productName "WhiteDevil", not checked on Windows)* | `%LOCALAPPDATA%\WhiteDevil\settings.json` (`Settings.kt`) |
| Packaging | `npm run dist:win` gives an NSIS installer and a portable `WhiteDevil.exe`; AppImage on Linux | `gradlew packageMsi` gives an MSI. Needs Windows plus WiX 3.x to build. See [`DESKTOP_PACKAGING.md`](DESKTOP_PACKAGING.md). |

What did **not** move: the hub on the VM is still the system of record and the phone is
unaffected. The desktop app is a client of the hub, not a replacement.

## 2. Desktop screens: native yet, or not

The desktop navigation (`Screen` enum in `desktop/src/main/kotlin/com/whitedevil/desktop/Main.kt`)
has nine entries. "Placeholder" means the file at base only draws the text
"<name>: not built yet".

| Desktop screen | Electron equivalent | State at base commit | What to do |
|---|---|---|---|
| Agent | Agent domain (`/app/venice/`) | Code exists (`AgentScreen.kt`: chat, tool events, Stop, Clear). Being extended in parallel. At base a new `Agent` object appears to be built for every message, so I could not see memory across messages, and the transcript is in memory only. *(uncertain: read, not run)* | Landing in the same series; verify on the merged branch |
| Shell (`Terminal`) | Hub, Shell (`/app/term/`) | Basic pty4j-to-`wsl.exe` plumbing exists, but the screen appends raw output to a text box: no escape-sequence handling as far as I can read, so full-screen programs will not render properly. Replacement with JediTerm is **in progress**. | Do not rely on it until the JediTerm change is merged and you have tried `vim` and `top` in it |
| Renders | Hub, Renders | Placeholder | Landing in the same series; verify on the merged branch |
| Gallery | Gallery domain (`/app/gallery/`) | Placeholder | Landing in the same series; verify on the merged branch |
| Colab | Hub, Colab | Placeholder | Landing in the same series; verify on the merged branch |
| Thunder | Hub, Thunder (Electron reached it only through the Hub iframe) | Placeholder | Landing in the same series; verify on the merged branch |
| Vast | Hub, Vast (same) | Placeholder | Landing in the same series; verify on the merged branch |
| Setup | Hub, Setup | Placeholder | Landing in the same series; verify on the merged branch |
| Settings | "You" pane and the Settings window | Code exists, a subset of Electron's (section 4) | Device enrolment UI is expected from the DeviceAuth work, not present at base |

**Hub screens with no native counterpart, and none announced.** The hub's
`screens.json` (at base) also lists Hub Home, HypnoForge, LTX, Files
(`/laptop/files/`) and Shotwriter, and the hub adds "bot" screens dynamically. The
Electron app showed all of these through its Hub iframe. The desktop `Screen` enum has
nothing for them. Until someone decides to port them, they are reachable only in the
Electron app or in a browser at `<relay>/app/desktop/`.

## 3. What the Electron app still does that the desktop app does not

Enumerated from `laptop-app/main.cjs`, `preload.cjs`, `config.cjs`, `shell.html`,
`settings.html`, `signin.html`, the scripts and `package.json`. "Desktop at base" is
what `desktop/src` contains at `db49f77`.

| # | Electron feature (where) | Desktop at base | Status |
|---|---|---|---|
| 1 | Hosts the whole web Hub domain in an iframe, including LTX, HypnoForge, Files, Shotwriter and bot screens (`shell.html`) | Nothing. Only the nine native entries exist. | Not in desktop (see section 2) |
| 2 | System-tray icon with Open / Agent / Hub / Gallery / Sign in / Settings / Quit (`createTray`) | None | Not in desktop |
| 3 | App menu and shortcuts: Ctrl+1..4, Ctrl+Shift+T (Shell), Ctrl+, (Settings), Ctrl+R, Ctrl+Shift+L / S / R (`buildMenu`) | Mouse-driven nav rail only | Not in desktop |
| 4 | Single-instance lock: a second launch focuses the first window (`requestSingleInstanceLock`) | None found | Not in desktop. A second launch probably opens a second window and a second shell *(uncertain)* |
| 5 | First run imports relay credentials from `wan_outputs\relay_access.txt` and a few other paths (`tryImportRelayAccess`, "Import relay_access.txt" button) | None. Type them in. | Not in desktop |
| 6 | First run imports Venice and OpenRouter keys from `~/.config/venice/api_key`, `~/.venice_key`, and similar (`seedApiKeys`, "Import local keys") | None | Not in desktop |
| 7 | First run probes a local hub at `127.0.0.1:43173` and prefers it (`probeLocalHub`) | Default hub is the relay (`Settings.DEFAULT_HUB_URL`) | Not in desktop |
| 8 | "Test connection" (`GET /api/manifest` with basic auth, explains a 401) | No button | Not in desktop |
| 9 | Sign-in page when the relay answers 401/403, added so a 401 is not a black window (`signin.html`, `hubNeedsSignIn`) | Agent shows "Add your Venice API key in Settings" when the key is missing. There is no check that the relay password works. | Different, partial |
| 10 | Separate "laptop" credentials for `/laptop/term/` and `/laptop/files/` | None. The desktop Shell talks to `wsl.exe` directly, so the term credential is not needed. Files has no native screen. | Shell: not needed. Files: not covered. |
| 11 | OpenRouter API key field, used by the relay's LTX prompt writers and review | No field | Not in desktop |
| 12 | Pushes the Venice key to `POST /api/venice/key` and the OpenRouter key to `POST /api/gen/key` on every Save and every launch (`syncKeysToRelay`) | No push | **Not in desktop.** The relay keeps the last key Electron pushed. If you rotate a key on the laptop, the phone's Agent and the LTX writers will not see it until you push it yourself. |
| 13 | Remembers the last domain and reopens it (`startDomain`, `WD_START_DOMAIN`) | Always opens Agent | Not in desktop |
| 14 | Environment overrides: `FORGE_HUB_URL`, `FORGE_RELAY_USER` / `_PASS`, `FORGE_LAPTOP_USER` / `_PASS`, `FORGE_VENICE_KEY` / `VENICE_API_KEY`, `FORGE_OPENROUTER_KEY` / `OPENROUTER_API_KEY`, `WD_START_DOMAIN`, `FORGE_DEBUG`, `FORGE_DISABLE_GPU`, `FORGE_SANDBOX` | None in `Settings.kt` | Not in desktop |
| 15 | Attach-media file dialog (`dialog:pickMedia`, opens in `wan_outputs`, up to 8 files of at most 12 MB, returned as data URLs) | None | Nothing in `shell.html` calls it, so it looks unused in Electron as well (grep of this tree) |
| 16 | Opens external links in the default browser, pins the top frame to local files | No web content, so not applicable | By design |
| 17 | Strips `X-Frame-Options` and CSP `frame-ancestors` so relay pages can be iframed | Not applicable | By design |
| 18 | Cache-busts the iframes when `/api/manifest` reports a new `web_rev` | Not applicable | By design |
| 19 | Disables GPU compositing on Windows to avoid blank windows (`disableHardwareAcceleration`) | Compose draws through Skiko instead. If a blank or garbled window ever appears, the Skiko render-API setting is the thing to try. *(uncertain: from memory, not tested)* | Different renderer |
| 20 | Portable `WhiteDevil.exe`, NSIS installer, Linux AppImage, and the WSLg helper scripts (`install-home.sh`, `run.sh`) | MSI only. The Shell also needs `wsl.exe`, so the app is Windows-only in practice. | Not in desktop: no portable exe, no Linux or WSLg path |
| 21 | Node tests for URL normalisation, settings merge and the install-script text (`test/`) | No tests at base; test dependencies were added and tests are being written in parallel | Verify on the merged branch |

What the desktop app has that Electron did not: an agent loop that runs on the laptop
instead of the relay, a real pty shell, and (once merged) a hardware-backed device key.

**Version note.** `laptop-app/package.json` is 1.1.4, and its README still says 1.1.0.
The MSI starts at 1.0.0.

## 4. Settings: field by field

| Electron field | Desktop field | Notes |
|---|---|---|
| `hubUrl` | `hubUrl` | **Do not copy the value across.** Electron stores a full URL ending in `/app/desktop/`. The desktop app appends `/api/...` to whatever you enter (`shared/.../Tools.kt`), so pasting the Electron value makes requests go to `/app/desktop/api/...` and fail. Enter the origin only: `https://84-12-112-249.sslip.io`. |
| `relayUser`, `relayPass` | `relayUser`, `relayPass` | Same Caddy basic-auth credential. |
| `veniceKey` | `veniceApiKey` | Same Venice key. |
| `openRouterKey` | none | Not used by the desktop app. |
| `laptopUser`, `laptopPass` | none | Not needed for the desktop Shell. |
| `startDomain` | none | |
| none | `model`, `enableWebSearch` | New. Default model `zai-org-glm-5-2`. |
| none | `deviceId`, `deviceName` | New. Filled in by enrolment. |

There is no importer. Re-enter the values by hand. Both apps store secrets in plaintext
in their own settings file. The desktop Settings screen says so.

The two folders have the same name under different roots, so be careful when cleaning
up: Electron's is expected under `%APPDATA%\WhiteDevil` *(uncertain)*, the desktop app's
is `%LOCALAPPDATA%\WhiteDevil`.

## 5. Switching over

The order matters. Keep the Electron app installed and working until step 6.

**0. Read [`DEVICE_AUTH_MIGRATION.md`](DEVICE_AUTH_MIGRATION.md) once.** The two
migrations touch each other, see "The one trap" below.

**1. Get an MSI.** None exists yet. Someone with a Windows machine, WiX Toolset 3.x, a
JDK and the .NET 8 SDK builds it as described in
[`DESKTOP_PACKAGING.md`](DESKTOP_PACKAGING.md). Packaging refuses to run without
`wd-hello.exe`, so an MSI that lacks the Windows Hello helper should not come out of it.

On the laptop you need:

- the **.NET 8 Runtime (x64)**: `wd-hello.exe` is framework-dependent and the MSI does
  not install .NET;
- **WSL**, for the Shell tab;
- **Windows Hello** set up (a PIN or fingerprint in Windows Settings, Accounts,
  Sign-in options), for device enrolment.

**2. Install.** Run the MSI. It is per-machine, so expect a UAC prompt, and it lets you
pick the folder. It adds a Start-menu group and a desktop shortcut, both called
"WhiteDevil". The Electron app is also called WhiteDevil, so check which one a shortcut
launches (right-click, Properties) until Electron is gone.

**3. First run: Settings.** Enter the hub origin (section 4), relay user and password,
Venice API key, and model. Save. There is no Test button, so the proof is sending a
message in Agent and having it call a hub tool.

**4. Enrol the laptop.** Follow step 2 of [`DEVICE_AUTH_MIGRATION.md`](DEVICE_AUTH_MIGRATION.md).
The enrolment screen comes from the DeviceAuth work and is **not in the base commit**;
verify it on the merged branch. If the hub requires enrolment codes, get one with
`POST /api/auth/enrol-code`. Windows Hello should prompt you. Then confirm from
any machine:

```bash
curl -s -u relay:THE_RELAY_PASSWORD https://84-12-112-249.sslip.io/api/auth/devices
```

The laptop must show a non-null `last_seen`. A device that enrolled but never signed
is not enrolled for this purpose.

**5. Operator checks, on the real laptop, before Electron goes.** Tick every line that
matters to you; anything you cannot tick is a reason to keep Electron.

- [ ] Agent: a conversation works, a hub tool call works (for example the render
      status), Stop works, and you have decided whether the memory-across-messages
      question in section 2 matters to you.
- [ ] Shell: opens WSL, and **`vim` and `top` are usable** (only true once the JediTerm
      change is merged).
- [ ] Each screen you use daily (Renders, Gallery, Colab, Thunder, Vast, Setup) is real
      on the merged branch, not the "not built yet" placeholder.
- [ ] For every screen with no native version (Hub Home, HypnoForge, LTX, Files,
      Shotwriter), you have a plan: browser, or keep Electron. See "The one trap".
- [ ] Keys: you know that rotating a key on the laptop no longer updates the relay
      (section 3, row 12), and you have pushed the current Venice and OpenRouter keys
      once yourself if the phone or LTX writers need them.
- [ ] Persistence: quit and relaunch, and Settings are still there.
- [ ] Installed-location check: `<install dir>\app\resources\wd-hello.exe` exists, and
      enrolment worked from the installed app, not just from a dev build. (A dev build
      finds the helper through a fallback path; the installed app must find it in its
      resources directory.)
- [ ] Upgrade path: one MSI installed over an older one replaced it rather than
      duplicating it.

**6. Only then remove Electron.**

1. Uninstall it: Windows Settings, Apps, "WhiteDevil" (NSIS build), or delete the
   portable `WhiteDevil.exe`. Make sure you are removing the Electron one; see the
   note in step 2.
2. Delete Electron's settings folder (expected `%APPDATA%\WhiteDevil` *(uncertain)*).
   It holds the relay password and API keys in plaintext. **Do not delete
   `%LOCALAPPDATA%\WhiteDevil`; that is the desktop app's.**
3. If you ever ran `laptop-app/install-home.sh` in WSL, remove `~/laptop-app` and the
   `~/.local/bin/forge-hub` and `~/.local/bin/whitedevil` links it created.
4. Leave the `laptop-app/` folder in the repository until you decide to delete it.

**Rolling back** before step 6 is nothing: Electron is still there. After step 6,
reinstall it from `laptop-app/` with `npm install` and `npm run dist:win`.

### The one trap: Electron and the device-auth migration

The Electron app authenticates with the relay basic-auth password and nothing else. It
has no code for the device key or `/api/auth/*` (checked by grep). The device-auth
migration removes or stops honouring that password in its later steps:

- **Step 6 there (`strict`) will lock the Electron app out**, and any browser tab, for
  certain: `strict` accepts device tokens only.
- **Step 5 there (removing Caddy's `basic_auth`) may also break Electron's web panes**
  *(inference, not tested)*. Electron only sends the password after the server
  challenges for it (`app.on("login")`); once Caddy stops challenging, the iframes'
  own API calls may go out without credentials.

So: get the desktop app configured and enrolled (steps 3 and 4 of this page)
**before** you take the device-auth migration past its step 4, and do not treat
Electron as your fallback after that point.
The same applies to any browser you use for the screens with no native version.

## 6. Known gaps and unknowns

- No part of this has been run on Windows. That covers the desktop app, the packaged
  app, `wd-hello.exe`, WSL from the app, and the MSI (never built).
- The screens in section 2 marked "landing in the same series" may or may not be done
  when you read this. The table is a snapshot of the base commit.
- I did not compare the Agent's tool set with the relay Agent's tool set in detail.
  `:shared` `ToolBox` (used by desktop, shared with Android) and `hub/venice.py`
  (used by the Electron Agent) are separate implementations.
- Whether Hub Home, HypnoForge, LTX, Files and Shotwriter ever get native screens is
  undecided; I found no plan for them in the repository.
