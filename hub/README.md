# Forge Hub

Phone + desktop shell for the Wan relay. The Android app builds its tabs from `GET /api/manifest` (`hub/screens.json`), so new screens like Venice show up without waiting on an APK — unless the native shell itself changed (icons, terminal WebView).

App **v8** is a forced update: `force_update` + `apk_version` 8 in the manifest blocks older installs until they tap Download. From the **phone**, Home → **Update Hub** (or Terminal → **Update Hub**) publishes `relay/phone_publish.sh` to the relay over `ssh wan-relay`. That is the laptop via ttyd — no desk keyboard. Optional APK: `bash android/build_and_publish.sh` in the same Terminal after the hub files are on the relay.

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

**Civitai LoRA (phone):** Terminal → **Civitai LoRA** → Send. Weights land in `~/civitai_dl/` on the laptop (~672 MB for Penis LTX-2.5). Open them from **Files** → `/home/<wsl-user>/civitai_dl`. Do not scp to the phone. Civitai requires an API token in `~/.civitai_token` (or `CIVITAI_TOKEN`); if the run 401s, paste this in the sheet first (token stays in the terminal, not in chat):

```bash
printf '%s\n' 'YOUR_CIVITAI_API_TOKEN' > ~/.civitai_token && chmod 600 ~/.civitai_token
```

Same downloader: `tools/civitai_red_dl.py` (defaults `--id 2851705`).
