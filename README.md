# WhiteDevil — an agentic app

WhiteDevil is an agentic app. The agent is the product; everything else is surfaces and tools it drives.

You give it a goal. It plans, uses real tools, checks results, recovers when it fails, and keeps going until the job is done — with little help from you, inside limits you set. It knows when it's finished and when it's stuck and needs you.

Components:

1. **The Agent** — `hub/static/venice/` (the dedicated Agent screen) driven by `hub/venice.py` (tool definitions, Venice proxy, chat persistence) and `hub/agentic/` (background goal runner, permissions, memory, schedules). A `venice-agent/` Kotlin CLI runs the same loop headless.
2. **Forge Hub (`hub/`)** — FastAPI relay + web screens (renders, Colab, Thunder, LTX, Gallery, Setup, Shell, laptop): the machine's studio surfaces and the tools the agent can drive. Forge Hub is one part of the app, not the app.
3. **WhiteDevil Android App (`android/`)** — native phone surface (`com.whitedevil`): Agent, Forge Hub, Terminal, Settings; chat state syncs with the hub.
4. **Desktop app (`desktop/`)** — native Windows app (Kotlin / Compose Desktop, ships as an MSI): Agent, Shell, Renders, Gallery, Colab, Thunder, Vast, Setup. It replaces the Electron **laptop app (`laptop-app/`)**, which is deprecated and left in place as a fallback. Screens with no native version (Hub Home, HypnoForge, LTX, Files, Shotwriter, bots) stay on the web. See `docs/DESKTOP_MIGRATION.md`.
5. **Tools (`tools/`)** — Civitai LoRA downloader and Wan2.2 prompt pack ingest utilities.

---

## Repository Structure

```
White-devil-repo-4/
├── venice-agent/                        # Autonomous Venice Agent (Kotlin CLI)
│   ├── build.gradle.kts                 # Ktor, kotlinx-serialization, coroutines
│   └── src/main/kotlin/com/whitedevil/veniceagent/
│       ├── Agent.kt                     # Autonomous tool-use reasoning loop
│       ├── VeniceClient.kt              # Venice AI chat completions client
│       ├── Models.kt                    # Chat, Tool, Function schemas
│       ├── Tools.kt                     # Sandboxed file/shell tools + Forge Hub tools
│       └── Main.kt                      # CLI entrypoint with updated toolset
├── hub/                                 # Forge Hub Backend & Web Frontend
│   ├── app.py                           # FastAPI hub server (relay :9000)
│   ├── screens.json                     # Dynamic screens manifest (APK v8)
│   ├── venice.py                        # Venice relay proxy & persistent chat threads
│   ├── laptop.py                        # SSH bridge to laptop WSL (~/venice_run)
│   └── static/                          # Mobile web UIs (venice, term, renders, etc.)
├── desktop/                             # Native Windows desktop app (Compose Desktop, MSI)
├── laptop-app/                          # DEPRECATED Electron laptop app (Forge Hub window), kept as a fallback
├── android/                             # WhiteDevil Native Android Client (`com.whitedevil`)
│   ├── app/src/main/
│   │   ├── AndroidManifest.xml
│   │   ├── java/com/whitedevil/
│   │   │   ├── MainActivity.kt          # 4-Tab Bottom Nav, Dark Glass styling, Hub & Term isolation
│   │   │   ├── SettingsManager.kt       # EncryptedSharedPreferences (Venice API key, relay credentials)
│   │   │   └── agent/                   # Native Venice Agent integration
│   │   │       ├── Agent.kt             # Coroutine agent loop with live UI event emission
│   │   │       ├── VeniceClient.kt      # Native Ktor Venice chat completion client
│   │   │       ├── Models.kt            # OpenAI/Venice Chat schemas
│   │   │       └── Tools.kt             # Local workspace sandboxed tools + remote relay/laptop tools
│   │   └── res/                         # Vector icons (Agent, Forge Hub, Terminal, Settings), dark glass theme
│   └── build_and_publish.sh             # Build script for Android SDK
└── tools/
    ├── civitai_red_dl.py                # Civitai LoRA background downloader
    ├── wan_ingest.py                    # Model-free pack ingest & queueing
    └── WAN_INGEST.md                    # Ingest documentation & schemas
```

---

## 1. Venice Agent (`venice-agent/`)

Autonomous CLI agent powered by Venice AI (OpenAI-compatible chat completions) with tool execution.

### Capabilities & Tools
- **Filesystem Tools**: `read_file`, `write_file`, `list_directory` (sandboxed to `workspace/`).
- **Shell Execution**: `run_shell_command` inside the workspace.
- **Pipeline Monitoring**: `get_render_status` queries active Wan2.2 5B/14B GPU rendering jobs, heartbeat status, and GPU credit balances from the relay.
- **Catalog Inspection**: `list_prompt_packs` inspects prompt chains and completion counts.
- **Remote Laptop SSH**: `run_laptop_command` executes scripts on the WSL laptop in `~/venice_run`.
- **LoRA Downloader**: `download_civitai_lora` triggers background LoRA downloads on the laptop to `~/civitai_dl`.

### Running Venice Agent

```bash
cd venice-agent
export VENICE_API_KEY="your-venice-api-key"
export RELAY_BASE_URL="https://84-12-112-249.sslip.io" # optional, defaults to relay
export RELAY_USER="anon3"
export RELAY_PASS="your-relay-password"

./gradlew run
```

---

## 2. Forge Hub Backend (`hub/`)

FastAPI server providing endpoints for the Android app and web interface:
- **Dynamic manifest**: `GET /api/manifest` (serves `hub/screens.json`)
- **Status & health**: `GET /api/status`
- **Colab GPU & Wan2.2 runner state**: `/api/colab/*`
- **Venice chat proxy, agent tools, and thread persistence**: `/api/venice/*` (`/tools`, `/tool`, `/chat`)
- **Remote laptop execution**: `/api/laptop/*`
- **Agentic runtime**: `/api/agentic/*` — background goal jobs, sub-agent pools (parallel researcher/coder/reviewer children), persistent memory store (preferences/notes/projects injected into every chat turn), schedules with a hub-side timer (no external cron), watchers (e.g. "when a new render appears, run X"), per-tool permissions with a shared risky-action blocklist, an enforced `daily_tool_budget`, and an audit log of every gated action.
- **Laptop Venice tab** (`/app/venice/`): same Agent loop as the Android app (You / Venice / Tool Call / Output), including `run_laptop_command` in `~/venice_run` and `run_in_terminal` which types into the live Shell (ttyd).

### Running Forge Hub

```bash
cd hub
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
uvicorn app:app --host 0.0.0.0 --port 9000
```

---

## 3. WhiteDevil Android App (`android/`)

Independent native Android application (`com.whitedevil`) with:
- **Architecture & Navigation**: 4-tab native bottom navigation bar with dark glass styling:
  1. `[Agent]`: Native Android UI for Venice Agent, custom message bubbles (User, Venice, Tool Call, Tool Output, Error), model selector, and system prompt dialog. Runs `VeniceClient` and `Agent` coroutines directly on Android.
  2. `[Forge Hub]`: Isolated WebView container for the relay screens (Home, Renders, Colab, Thunder, etc.). If the relay is offline or sends `force_update`, only this component displays error banners; it never locks out the rest of the app.
  3. `[Terminal]`: Isolated terminal container wrapping ttyd/terminal with one-finger touch scrolling and safe staged paste modal.
  4. `[Settings]`: Native settings screen configuring `VENICE_API_KEY`, Relay URL, Relay User/Pass, and Laptop User/Pass stored securely in Android `EncryptedSharedPreferences`.
- **Integrated Agent Core**: Direct port of Venice Agent client logic and unified local device workspace tools (`read_file`, `write_file`, `list_directory`, `delete_file`) plus remote Forge Hub and WSL laptop tools (`get_render_status`, `list_prompt_packs`, `run_laptop_command`, `download_civitai_lora`).

### Building the APK

```bash
cd android
./gradlew assembleDebug
./gradlew assembleRelease
```

---

## 4. Tools (`tools/`)

- **`civitai_red_dl.py`**: Downloads Civitai LoRA files to the WSL laptop (`~/civitai_dl/<id>_<base>/`). Pass multiple `--id` values to pull Wan 2.2 + LTX-2 + LTX-2.5, not a single LTX 2.5 file.
- **`wan_ingest.py`**: Validates, merges, and queues prompt packs for the Wan2.2 video pipeline.

---

## 5. Laptop app (`laptop-app/`) — deprecated

> Replaced by the native desktop app in `desktop/`; see `docs/DESKTOP_MIGRATION.md` for what moved and how to switch, and `docs/DESKTOP_PACKAGING.md` for building the MSI. This Electron app stays until the operator retires it.

Electron window for Forge Hub on the WSL/Windows laptop (Venice Agent, Shell, renders). Not a browser tab.

`cd laptop-app` from `~` fails — that folder is not in your home directory. On WhiteDevil:

```bash
git clone https://github.com/WhiteDevil-93/White-devil-repo-4.git ~/White-devil-repo-4
~/White-devil-repo-4/laptop-app/install-home.sh
cd ~/laptop-app
npm start
```

Later launches: `cd ~/laptop-app && npm start`, or `forge-hub` after install. Settings (`Ctrl+,`) store the hub URL and relay/laptop passwords in the OS user-data folder. See `laptop-app/README.md`. Windows installer: `npm run dist:win`.

---

## Security Note

Sensitive configuration and authentication files such as `credentials.json`, `.env`, `.venice_key`, and relay access tokens must never be committed to git and are excluded via `.gitignore`.

