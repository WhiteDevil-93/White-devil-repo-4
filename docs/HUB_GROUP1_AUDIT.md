# Group 1 audit — hub/ core backend & model runtime

Date: 2026-10-08. Evidence-based; live Venice/GPU paths not exercised.

## Executive status

| Area | Status |
|------|--------|
| App mount / routes | WORKING (130 routes, empty `MOUNT_FAILURES` on import) |
| Safe read APIs | WORKING (TestClient) |
| Tool registry + dispatch | PARTIAL — workspace tools OK; see defects |
| Venice/OpenRouter chat + SSE | Failure path WORKING (412 without key); live UNVERIFIED |
| Agentic side-runner | APIs WORKING; runners default `enabled: false` |
| Device auth | Unit-tested; `require_device` not applied to product routes |
| LTX/Colab/Thunder/Vast/Hypno | Code present; end-to-end UNVERIFIED without relay services |
| Tests | `python -m pytest -q` in `hub/`: 156+ passed (see suite) |

## Architecture (production)

- Entry: `forge-hub.service` → `uvicorn app:app --host 127.0.0.1 --port 9000` (Caddy in front).
- Dev README: port `43173`.
- `app.py` always mounts colab, media, hypno, thunder, gen, venice; optional `_mount` for laptop, setup, setupbot, vast, ltx, term_bridge, auth, agentic.
- Tool loop is **client-side** (`MAX_TOOL_ITERATIONS` is advertised only). Hub exposes `GET /api/venice/tools` and `POST /api/venice/tool`.

## Critical defect (fixed on this branch)

**`upload_to_colab` always failed** with `Error: name 're' is not defined` because `hub/venice.py` used `re.match` in `_win_to_wsl` without `import re`.

- Fix: add `import re`.
- Regression: `test_win_to_wsl_path_conversion` in `hub/test_venice_agent.py`.

## Other findings (not all fixed here)

1. **`hub_request` / `hub_overview`** call `http://127.0.0.1:9000` over HTTP. Works on the live relay; fails under in-process TestClient with no listener.
2. **`run_in_terminal`** returns `{paste: true}`; web UI pastes via `/api/term/paste` — contract OK for static Venice.
3. **Android `shared/Tools.kt`** omits several hub tools (`run_in_terminal`, `upload_to_colab`, `remember`, `delegate_to_subagent`, `collect_subagents`, `transcribe_audio`).
4. **Auth** is additive: `require_device` exists but is not wired onto product routes yet.
5. **README** apk/web_rev text can lag `hub/screens.json` (`apk_version` / `web_rev`).
6. **Windows pytest**: race on enrol-code unlink (`WinError 32`); agentic daemon thread after tmp teardown — warnings only on Windows.

## Tool matrix (hub `AGENT_TOOLS`)

Defined in `hub/venice.py`: read/write/list/delete file, get_render_status, review_latest_render, render_assess_adjust_cycle, list_prompt_packs, run_laptop_command, run_in_terminal, download_civitai_lora, upload_to_colab, hub_overview, hub_request, queue_gpu_render, remember, delegate_to_subagent, collect_subagents, transcribe_audio.

Permissions/budget gates share `agentic.store.permission_block` / `CONFIRM_TOOLS`.

## Model providers (code)

| Provider | Use |
|----------|-----|
| Venice (`api.venice.ai`) | `/api/venice/chat`, agentic job steps; default `zai-org-glm-5-2` |
| OpenRouter | OR model ids; gen chains; LTX writers; setupbot planner |
| Local Comfy / wanbot / Thunder / Vast | Via ltx/colab/thunder/vast modules and tokens under `$HOME` |

## Config keys / paths (selected)

- Keys: `~/.venice_key`, `~/.openrouter_key`, `~/.thunder_token`, `~/.vast_api_key`, `~/.civitai_token`, `~/.wanbot_token`
- Auth env: `HUB_FORWARD_AUTH_MODE`, `HUB_REQUIRE_ENROL_CODE`, `HUB_BASIC_AUTH_*`
- Ports: hub 9000, Comfy tunnels 18288/18188, wanbot 18900/18901, laptop agent 18765

## Refactor guidance

- Targeted: shared OpenRouter client; optional in-process hub calls instead of loopback HTTP for tools.
- No major rewrite required for auth/mount/agentic gates.
- Cross-group: align Android tool catalog with hub if native parity is required.

## Verification commands

```bash
cd hub
python -m pytest -q test_venice_agent.py test_app_mounts.py
python -c "import venice; print(venice._win_to_wsl(r'C:\\Users\\x\\a.safetensors'))"
```
