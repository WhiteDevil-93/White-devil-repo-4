# Caretaker domain

A peer domain of the app (like Forge Hub, Laptop, Prompt creator), **not** part of Forge Hub. It runs on
wan-relay as its own processes, so it keeps answering when forge-hub is down or restarting.

| Piece | What | Where it runs |
|---|---|---|
| `loop.py` | One maintenance pass every 10 min. Model is loaded only when something is anomalous. | `caretaker.timer` |
| `runbook.py` | The ONLY actions it can take (fixed list). Disruptive ones wait for `inflight.py` to be clear, back up, notify, grace, re-check, act, verify. | called by `loop.py` |
| `facts.py`, `inflight.py` | Read-only facts; detector for work in flight (ltx/gen/agentic jobs, ssh sessions). Does NOT cover Colab/laptop runs yet. | |
| `server.py`, `page.html` | The domain's page, API and read-only chat on `127.0.0.1:9100`. | `caretaker-web.service` |

**Safety:** dry-run unless `CARETAKER_LIVE=1`. `touch ~/caretaker/PAUSED` (or the page's Pause button) stops all actions.
The model never supplies a command: a table picks the runbook action, the model may only veto it.

**Install on the relay** (needs sudo, once): copy `deploy/*.service|timer` to `/etc/systemd/system/`, add
`deploy/Caddyfile.snippet` to the site block in `/etc/caddy/Caddyfile` before `handle /api/laptop/*`
(`caddy validate` first, then `systemctl reload caddy`), then `systemctl enable --now caretaker.timer caretaker-web`.
Auth is Caddy's `renders_auth`, the same login as `/app*`.

**Update code:** `caretaker/deploy.sh` (rsync only, no restarts).

**Needs on the box:** `~/litert-venv` with `litert-lm-api`, and the `.litertlm` bundle at the path in `loop.py` (`MODEL`).
Tests: `python caretaker/test_runbook.py`.
