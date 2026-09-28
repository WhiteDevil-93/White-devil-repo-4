# Forge Hub

Phone + desktop shell for the Wan relay. The Android app builds its tabs from `GET /api/manifest` (`hub/screens.json`), so new screens like Venice show up without waiting on an APK — unless the native shell itself changed (icons, terminal WebView).

App **v8** is a forced update: `force_update` + `apk_version` 8 in the manifest blocks older installs until they tap Download.

**Update the laptop app (Forge Hub Desktop `/app/desktop/`):**

1. Wake WSL if Terminal says offline.
2. In Forge Hub, click **Update Hub** (top bar on desktop, or Home / Terminal).
3. Tap **Send**. That pastes `phone_publish.sh` into the laptop ttyd shell and `ssh`es the Hub tarball to `wan-relay`.
4. Wait for `Done! Forge Hub updated on the relay.`
5. Reload the desktop tab (or pull-to-refresh on the phone). Manifest `web_rev` should be **10**.

Optional APK: `bash android/build_and_publish.sh` in the same Terminal after the hub files are on the relay.

## Run locally

```bash
cd hub
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
# copy ../.env.example to ../.env and set VENICE_API_KEY, or:
#   echo 'your-key' > ~/.venice_key
uvicorn app:app --host 0.0.0.0 --port 43173
```

Open `/app/home/`, `/app/venice/`, `/app/term/`.

If the relay has no key yet, open the Venice tab: the key box is at the top. Paste the key and tap **Save on relay**. That writes `~/.venice_key` (mode 0600) after Venice accepts it. Same box replaces a key later.

You can also drop the key as one line in `hub/venice.key` or `~/.venice_key` — both are gitignored.

## Venice

Chat tab talks to `https://api.venice.ai/api/v1` (OpenAI-compatible). Default model is GLM 5.2 (`zai-org-glm-5-2`). If the relay has no key, the tab accepts a device-local key (not uploaded to git). Each bubble has a Copy button; the composer Copy copies the last Venice reply.

Chats auto-save after each turn (phone `localStorage` plus `~/.venice_chats.json` on the relay). **Chats** lists them; tap one to resume. **New** starts a fresh thread without deleting the old one.

**Run on laptop:** tap **Run** on a Venice reply (or the composer Run). A sheet shows the last fenced code block — edit it, pick bash or python3, then **Run on laptop**. That uses `ssh laptop` (same path as HypnoForge) and writes `~/venice_run/last.sh` or `last.py`. Output comes back as a Laptop bubble so the next chat turn can see it. Nothing runs until you tap Run. Wake WSL if the sheet says the laptop is offline.

## Terminal

`/app/term/` wraps ttyd (`/laptop/term/`). Long-press / clipboard-read paste is blocked; swipe scrolls the xterm buffer; an explicit Paste sheet is the only way text enters the shell.

**Civitai LoRAs (phone or laptop Terminal):** Terminal → **Civitai LoRAs** → optional extra model IDs → Send. Default is CoachBate Penis LTX-2.5 (`2851705`). The downloader now takes **every LoRA file on every version** of each id (Wan 2.2, LTX-2, LTX-2.5, …), not a single LTX 2.5 file. Weights land in `~/civitai_dl/<id>_<base>/`. Copy every `.safetensors` into Thunder ComfyUI `models/loras/` and stack them — one LoRA loader per file. Do not scp to the phone.

Civitai requires an API token in `~/.civitai_token` (or `CIVITAI_TOKEN`); if the run 401s, paste this in the sheet first (token stays in the terminal, not in chat):

```bash
printf '%s\n' 'YOUR_CIVITAI_API_TOKEN' > ~/.civitai_token && chmod 600 ~/.civitai_token
```

Same downloader: `tools/civitai_red_dl.py`. Repeat `--id` for more models. `--primary-only` restores the old one-file behaviour.
