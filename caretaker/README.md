# Caretaker domain

A peer domain of the app (like Forge Hub, Laptop, Prompt creator), **not** part of Forge Hub. It runs on
wan-relay as its own processes, so it keeps answering when forge-hub is down or restarting.

| Piece | What | Where it runs |
|---|---|---|
| `loop.py` | One maintenance pass every 10 min. Model is loaded only when something is anomalous. | `caretaker.timer` |
| `runbook.py` | The ONLY actions it can take (fixed list). Disruptive ones wait for `inflight.py` to be clear, back up, notify, grace, re-check, act, verify. | called by `loop.py` |
| `facts.py` | Read-only facts about the box. | |
| `inflight.py` | Detector for work in flight. `detect()` is fast and local (LTX/gen/agentic job files, ssh sessions). `detect(deep=True)` also asks the hub about Colab, Thunder, Vast and laptop (Hypno) work; only `runbook.execute` uses it, before a disruptive action. If the hub cannot be read it blocks, except for `restart_hub`. Does NOT see work started outside the hub. | |
| `server.py`, `page.html` | The domain's page, API and read-only chat on `127.0.0.1:9100`. | `caretaker-web.service` |

**Safety:** dry-run unless `CARETAKER_LIVE=1`. `touch ~/caretaker/PAUSED` (or the Pause button) stops all actions.
The model never supplies a command: a table picks the runbook action, the model may only veto it.

**Where to open it**
- Phone app: Hub tab > Caretaker (any hub screen with a path opens as a web page).
- Windows app: Caretaker under LAPTOP (native screen, branch `feat/desktop-forge-hub-ui`).
- Any browser: `https://<relay host>/caretaker/`, same login as `/app`.

**POST guard:** state-changing requests (pause, chat) need `X-Requested-With: caretaker` (the web page) or
`Content-Type: application/json` (the Windows app and other clients). A cross-site HTML form can send neither.

**Install on the relay** (needs sudo, once): copy `deploy/*.service|timer` to `/etc/systemd/system/`, add
`deploy/Caddyfile.snippet` to the site block in `/etc/caddy/Caddyfile` before `handle /api/laptop/*`
(`caddy validate` first, then `systemctl reload caddy`), then `systemctl enable --now caretaker.timer caretaker-web`.
Auth is Caddy's `renders_auth`, the same login as `/app*`.

**Update code:** `caretaker/deploy.sh` (rsync only, no restarts), then `sudo systemctl restart caretaker-web`
for the page to pick up changes (the timer runs `loop.py` fresh each time).

**Needs on the box:** `~/litert-venv` with `litert-lm-api`, and the `.litertlm` bundle at the path in `loop.py` (`MODEL`).
Tests (run from this folder): `python test_runbook.py`, `python test_inflight_deep.py`, `python test_server.py`.
