# White-Devil-Repo-4: Autonomous Venice Agent & Forge Hub

Unified multi-component platform containing:
1. **Autonomous Venice Agent (`venice-agent/`)**: Kotlin/JVM autonomous tool-calling CLI agent with native tools for file management, shell execution, Wan2.2 rendering pipeline control, and remote WSL laptop SSH execution.
2. **Forge Hub Backend (`hub/`)**: FastAPI-based management hub serving dynamic screens, Wan2.2 Colab/ThunderCompute runners, HypnoForge bridge, and OpenAI-compatible Venice proxy.
3. **Forge Hub Android App (`android/`)**: Native Android client with bottom navigation, dynamic manifest tabs (`/api/manifest`), WebView shell, terminal patch with preset commands, and credential management.
4. **Tools (`tools/`)**: Civitai LoRA downloader (`civitai_red_dl.py`) and Wan2.2 prompt pack ingest utilities.

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
├── android/                             # Forge Hub Native Android Client
│   ├── app/src/main/
│   │   ├── AndroidManifest.xml
│   │   ├── java/com/anon3/forgehub/MainActivity.kt  # Bottom Nav + Dynamic Manifest
│   │   └── res/                         # Vector icons, themes, and layouts
│   └── build_and_publish.sh             # Build script for Windows Android SDK
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
- **Venice chat proxy & thread persistence**: `/api/venice/*`
- **Remote laptop execution**: `/api/laptop/*`

### Running Forge Hub

```bash
cd hub
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
uvicorn app:app --host 0.0.0.0 --port 9000
```

---

## 3. Forge Hub Android App (`android/`)

Native Android client with:
- Bottom navigation with dynamic tabs loaded from the relay manifest.
- Custom vector icons for Home, Venice, Renders, HypnoForge, Colab, Thunder, Files, and Terminal.
- Terminal patch (`patch.js`) with one-finger touch scroll and explicit paste protection.
- Built-in update check with `force_update` support.

### Building the APK

```bash
cd android
./gradlew assembleRelease
```

---

## 4. Tools (`tools/`)

- **`civitai_red_dl.py`**: Downloads Civitai mirror models directly to the WSL laptop (`~/civitai_dl`).
- **`wan_ingest.py`**: Validates, merges, and queues prompt packs for the Wan2.2 video pipeline.

---

## Security Note

Sensitive configuration and authentication files such as `credentials.json`, `.env`, `.venice_key`, and relay access tokens must never be committed to git and are excluded via `.gitignore`.

