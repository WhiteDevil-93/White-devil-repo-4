# Group 2 audit — AI agents, tools & automation

Date: 2026-10-08. Scope: `venice-agent/`, `tools/`, `scripts/` (+ `shared/` as the
agent core those directories delegate to, `hub/`/`tests/`/clients read-only for
interface verification). Evidence pinned to HEAD `58f4d90` and a frozen snapshot
(`%TEMP%/kilo/wd4-audit-snap`) — see "Tree instability" below. Read-only audit:
no repo files were modified by this group.

## 0. Tree instability during audit (meta-finding)

The working tree was rewritten by a parallel process mid-audit (~00:35 local):
untracked `scripts/harness-run.sh` and `tests/redteam/*.py` were deleted, `shared/`
gained 10 files, `hub/venice.py` gained `import re`, and new top-level `relay/` +
`caretaker/` trees appeared (commits `6f0d49d`, `58f4d90`). Findings below are
against HEAD after that reset; the deleted untracked files are assessed from the
copies captured before deletion (§6.1). `llms.txt`/`llms-full.txt` index the
pre-reset tree and are stale (they list `tests/redteam`, miss `shared/Mcp.kt`
etc., and index `build/` artifacts).

## 1. Executive assessment

| Subsystem | Status |
|---|---|
| venice-agent CLI (entrypoint, loop, tools, config) | WORKING (36/36 tests green; live model UNVERIFIED) |
| venice-agent MCP/registry subsystem | DEAD — fully tested, zero production caller |
| shared agent core (android+desktop) | WORKING (58/58 probe tests green; lone first-run failure was a probe-env artifact) |
| tools/civitai_red_dl.py | WORKING standalone; hub agent invokes a STALE fork instead |
| tools/wan_ingest.py | WORKING; integrated via hub/colab.py → deployed copy `~/wan/wan_ingest.py` |
| tools/deploy_hub.sh, wan-ingest.cmd | OPERATIONAL/manual, no in-repo caller |
| scripts/ | MISSING at HEAD (deleted untracked during audit; successors in relay/ + caretaker/) |
| hub ↔ client tool parity | PARTIAL — 6 hub tools native-absent, 3 schema/security drifts |
| Docs (READMEs, AGENTS.md) | BROKEN — inverted security default, stale file lists, missing test paths |

Headline defects: (1) CLI README claims shell disabled by default; code enables it
(Main.kt:29). (2) hub `download_civitai_lora` interpolates model-supplied ids
unquoted into a laptop bash command and runs a stale script copy. (3) shared
`queue_gpu_render` vast branch lacks the `/api/` path validation the hub has.
(4) desktop silently inherits git tools (incl. ungated `git_commit`) despite the
"no extra power" docstring. (5) the repo's own AGENTS.md test gates reference paths
that no longer exist.

## 2. Agent architecture map

```
INPUT ──► SURFACE ──► LOOP ──► MODEL ──► TOOL EXEC ──► RESULT ──► OUTPUT

CLI      ./gradlew run        venice-agent Agent.kt ──► VeniceClient (Ktor,
         (Main.kt:19)            │  cap 24, trim 60,      non-stream)
                                 │  repair pairs          ▼
                                 └─► CLI ToolBox ── 9 shared tools + run_shell_command
                                      (Tools.kt:44-54)     │ shared ToolBox (relay)
                                                            ▼
                                                     hub /api/laptop/run, /api/venice/*

Android  MainActivity.kt:941   shared Agent.kt ──► VeniceClient (stream when
         sendMessage                │               onPartial set; e2ee guard :233)
                                     └─► shared ToolBox: 13 relay + 5 git (+4 phone)
                                        + extensions (remember/recall/forget,
                                          use_skill/save_skill, mcp__* over HTTP)

Desktop  AgentScreen.kt:65     shared Agent.kt ──► shared ToolBox (13 relay + 5 git;
                                               NO extensions, NO confirm UI, NO stream)

Web      hub/static/venice/    JS loop (index.html:1424 `for i<MAX_ITERS`) ──►
         index.html                 POST /api/venice/chat (stream:false per iter)
                             ──► POST /api/venice/tool ──► hub execute_tool_detailed
                                                             (venice.py:1434, 19 tools)

Bg-job   hub/agentic/           runner.py run_goal_loop ──► venice.AGENT_TOOLS +
         runner.py                  venice.execute_tool (same registry, server-side)
                                   + permission/budget gates (store.permission_block)

Ops      caretaker/loop.py      timer → facts/inflight → local LiteRT model (read-only chat)
```

Four independent loop implementations (CLI Kotlin, shared Kotlin, web JS, hub
Python) with mirrored-but-not-shared protections; the CLI file itself admits the
mirror (venice-agent/Agent.kt:16-27 "must stay in step").

## 3. Agent inventory

| Agent | Entrypoint | Model | Tools | Context | Reachable | Tested | Status |
|---|---|---|---|---|---|---|---|
| CLI agent | venice-agent Main.kt:19 | `zai-org-glm-5-2` (Main.kt:7, env `VENICE_MODEL`) | 10 (9 relay + shell) | 24-cap/60-trim/repair (Agent.kt:95-129) | YES (`./gradlew run`) | 36 green | WORKING |
| Android agent | MainActivity.kt:941 | picker, default glm (SettingsManager.kt:38) | 13+5(+4)+3+2+MCP | ConversationStore+projects+memory+skills | YES (app) | probe 58/58 | WORKING (live UNVERIFIED) |
| Desktop agent | AgentScreen.kt:65 | `zai-org-glm-5-2` (Settings.kt:23) | 13+5 git | in-memory only | YES | same probe | PARTIAL (defect D4) |
| Web agent | hub/static/venice/index.html | picker w/ `supports_tools` gate | 19 hub tools | chats.json store | YES (hub UI) | dispatch tested | WORKING (loop JS untested) |
| Background runner | hub/agentic/runner.py:215 | glm / OR planner | 19 minus nested delegation | goal ledger | YES (`/api/agentic`, default disabled) | 25 green | WORKING |
| MCP stdio stack | venice-agent mcp/* | n/a | dynamic | n/a | **NO caller** (grep: tests only) | 25 green | DEAD |
| Caretaker | caretaker/loop.py | local `gemma4_e4b_heretic_int4.litertlm` | none (read-only facts) | state.json | relay timer | self-exec scripts | PARTIAL/OPS |

Abandoned/duplicated: MCP stdio stack (superseded by shared/Mcp.kt HTTP); CLI loop
duplicates shared loop; `laptop-app/` legacy (no `src/`, no consumers of
`/api/venice/tool`). Stale model refs: none — `zai-org-glm-5-2` consistent across
CLI/android/desktop/hub; web fallback list (index.html:1224) matches.

## 4. Tool inventory (executable tools in venice-agent/ + tools/)

| Tool | Location | Schema | Impl | Caller | Reachable | Tested | Status |
|---|---|---|---|---|---|---|---|
| read/write/list_directory | shared Tools.kt:94-135 | ✓ | workspace-sandboxed | CLI+android+desktop | YES | YES | WORKING |
| delete_file | shared Tools.kt:137-146 | ✓ | gated | android (confirm), desktop (deny) | YES | YES | WORKING |
| get_render_status / review_latest_render / render_assess_adjust_cycle / list_prompt_packs | shared Tools.kt:148-170 | typed (differs from hub strings) | relay GET/POST | android+desktop (CLI: 2 of 4) | YES | YES | WORKING |
| run_laptop_command | shared Tools.kt:172-180 | +cwd/timeout | relay | all natives | YES | YES | WORKING |
| download_civitai_lora | shared Tools.kt:182-190 | shellQuoted | → tools/civitai_red_dl.py on laptop | natives | YES | YES | WORKING (native) |
| hub_overview / hub_request | shared Tools.kt:192-206 | /api/ + no-`..` validated | relay | natives | YES | YES | WORKING |
| queue_gpu_render | shared Tools.kt:208-218 | vast path UNVALIDATED | relay | natives | YES | YES | PARTIAL (D3) |
| run_shell_command | venice-agent Tools.kt:96-108 | code | `sh -c`, default ON | CLI only | YES | YES | WORKING/unsafe-default |
| git_status/diff/log/commit/push | shared AccessTools.kt:126-184 | fixed cmds, argv arrays | relay runBash | android+desktop (CLI filtered) | YES | YES | PARTIAL (D4: commit ungated) |
| phone_list/read/write/delete | shared AccessTools.kt:186-235 | absolute-path validated | relay | android only | YES | YES | WORKING |
| remember/recall/forget | shared MemoryStore.kt:99-103 | ✓ | on-device store | android only | YES | YES | WORKING |
| use_skill/save_skill | shared SkillStore.kt:132-166 | ✓ | assets+user skills | android only | YES | YES | WORKING |
| mcp__server__tool | shared Mcp.kt:129-133 | server-discovered | HTTP/SSE, confirm-gated | android only | YES | YES | WORKING |
| civitai_red_dl.py | tools/civitai_red_dl.py | CLI args | resume+sha256 | manual + native agent (via `tools/` path) | YES | 5 green | WORKING |
| wan_ingest.py | tools/wan_ingest.py | subcommands | packs.json→wanbot/colab | hub/colab.py:259 (deployed copy) | YES | none | WORKING (untested) |
| deploy_hub.sh | tools/deploy_hub.sh | env args | rsync+systemd | manual only | manual | n/a | OPERATIONAL |
| wan-ingest.cmd | tools/wan-ingest.cmd | shim | wsl→deployed copy | manual | manual | n/a | OPERATIONAL |

Schema mismatches: none *within* the native stack (single registry). Drift is
native-vs-hub (§5). Missing registrations: none — every routed tool has a
definition and vice versa (ToolBox.execute routes exactly definitions; CLI routes
via SHARED_TOOLS filter, Tools.kt:62-90). Duplicate impls: civitai downloader ×2,
MCP stacks ×2, `remember` ×2 (different stores).

## 5. hub ↔ tool parity matrix

hub registry = `AGENT_TOOLS` (venice.py:682, 19 entries, real construction traced
through `execute_tool_detailed` :1434 and `/tools` :1679, `/tool` :1694).

| Tool | hub reg | shared | CLI | Schema match | Status |
|---|---|---|---|---|---|
| read_file, write_file, list_directory | ✓ | ✓ | ✓ | ✓ | parity |
| delete_file | ✓ | ✓ | — | ✓ | native-only gap (intentional) |
| get_render_status, list_prompt_packs | ✓ | ✓ | ✓ | rounds/typed props differ on cycle only | parity |
| review_latest_render, render_assess_adjust_cycle | ✓ | ✓ | — | hub all-string vs shared typed (D5) | DRIFT |
| run_laptop_command | ✓ | ✓ | ✓ | hub lacks cwd/timeout in schema (D6) | DRIFT |
| download_civitai_lora | ✓ | ✓ | ✓ | hub unquoted + stale script (D2) | DRIFT/SECURITY |
| hub_overview, hub_request | ✓ | ✓ | ✓ | ✓ | parity |
| queue_gpu_render | ✓ | ✓ | — | hub validates vast path, shared doesn't (D3) | DRIFT/SECURITY |
| run_in_terminal | ✓ | — | — | n/a | hub/web-only by design (live shell paste) |
| upload_to_colab | ✓ | — | — | n/a | backend-absent on natives |
| remember | ✓ | ✓ (name) | — | SAME NAME, DIFFERENT STORE (D7) | NAMING DRIFT |
| delegate_to_subagent, collect_subagents | ✓ | — | — | n/a | absent on natives |
| transcribe_audio | ✓ | — | — | n/a | absent on natives (web mic path) |

Backend registrations with no native implementation: run_in_terminal,
upload_to_colab, delegate_to_subagent, collect_subagents, transcribe_audio
(5). Implementations never registered in hub: git_* , phone_*, recall/forget,
use_skill/save_skill, mcp__*, run_shell_command (12 — by design, client-local).
Obsolete: none found; `slug` param of download_civitai_lora is accepted by both
registries but IGNORED by the implementation (civitai_red_dl.py:380 `_ = args.slug`)
— dead parameter, D8.

## 6. scripts inventory

`scripts/` does not exist at HEAD (`git ls-files scripts/` empty). Its former
content was untracked and removed in the mid-audit reset.

### 6.1 Deleted during audit (captured pre-reset)

| Script | Purpose | Caller | Status |
|---|---|---|---|
| scripts/harness-run.sh | Docker red-team harness | AGENTS.md (manual) | DEFECTIVE: `SCENARIO` never exported into container (:138-153) so all 5 scenarios ran parent-traversal logic; `network_on`/`timeout_sec` vars unused; `local rc=$?` unreachable under `set -e` (:180) |
| tests/redteam/test_redteam_policies.py | policy suite | AGENTS.md gate | VACUOUS: most methods empty; coverage test asserted area names that never match scenario names → guaranteed fail |
| tests/redteam/test_redteam_tooling.py | tooling checks | AGENTS.md gate | VACUOUS: every assertion guarded by `if path.exists()` on paths absent from HEAD (`qwen_api/`, `scripts/probe_model_capabilities.py`, `laptop-app/test/redteam`) |

### 6.2 Successors at HEAD

| Script | Purpose | Caller | Safe | Current | Status |
|---|---|---|---|---|---|
| tools/deploy_hub.sh | SETUP/DEPLOY hub→wan-relay | manual (comment refs only) | destructive (restarts forge-hub) | yes | OPERATIONAL |
| tools/wan-ingest.cmd | LAUNCHER shim | manual | yes | points at deployed `~/wan` copy | OPERATIONAL |
| relay/ops/forge-backup.sh | BACKUP | relay timer/manual | yes (excludes secrets by design) | yes | OPS |
| relay/wan/heartbeat.sh | MONITOR | wan-heartbeat.service | yes | hardcodes uv tool path | OPS |
| relay/wan/colab_recover.sh, colab_ops.sh, requeue_v3.sh, resume_chains.py, mirror14.sh, drive_pull.py, push_laptop.py, status_page.py | RENDER OPS | manual/timer | mutating (colab/wanbot) | yes | OPS |
| relay/wan/colab_turbo/* (boot, run_gooning_chain_colab, start_wanbot, rehydrate_g4, restore_resume, mirror_chains, drive_sync, smoke_i2v_mix_pack, goon_grain_check) | COLAB RENDER PIPELINE | colab runtime | heavy (never run locally per standing orders) | yes | OPS |
| relay/wan/wanbot_src/* | WANBOT SERVICE | systemd on relay | n/a | yes | OPS |
| caretaker/deploy.sh + loop.py + server.py + runbook.py + facts.py + inflight.py | MAINTENANCE AUTOMATION | systemd timer on relay | read-only chat; LIVE gated by env | yes | OPS/PARTIAL (tests are self-exec scripts, 0 pytest items) |

Machine-specific assumptions: `relay/wan/colab_turbo/boot.sh:16` relay IP
`84.12.112.249`; `colab_recover.sh` `127.0.0.1:18900` + `~/.wanbot_token`;
`heartbeat.sh` uv-tool python path; `caretaker/loop.py` `~/models/gemma4_e4b_heretic_int4.litertlm`;
`tools/wan_ingest.py:19-21` `/mnt/c|a/Users/anon3` (GC/runner env-overridable,
DOWNLOADS not); `shared/AccessTools.kt:30-31` `/mnt/a/New folder (4)` +
`white-devil-repo-4` as shipped defaults. Obsolete model URLs: none. Missing deps:
civitai_red_dl self-installs requests at import (:56-60, `--break-system-packages`
fallback) — works but stealthy.

## 7. Data / ingestion pipelines

**wan_ingest** (SOURCE incoming/*.json[l|txt] → parse → normalize → validate →
packs.json → send/upload): formats .json/.jsonl/.txt/.md handled (parse_file:96);
malformed → die() with line number, never persisted; duplicate clip indices →
rejected (:148); re-add of same clip REPLACES with `ingested_at` marker (:167-170);
atomic tmp+replace save with .bak (:116-122); originals 1-48 protected from
to_chain (:264); persistence verified. INTEGRATED via hub/colab.py:259
(`~/wan/wan_ingest.py send`) and relay heartbeat (runpy `refresh_colab`). Risk:
repo copy vs deployed copy are unlinked (no version/hash check) — same drift class
as the civitai fork. STATUS: WORKING, zero tests.

**civitai_red_dl** (API → pick files → stream → .part → sha256 → promote):
resume via 206/Content-Range (:244-255); truncated never promoted (test-verified);
token masked in logs (:71-74) — but only in the tools/ copy; TLS verification
disabled globally (:69 `verify=False`) in BOTH copies. STATUS: WORKING.

## 8. File & command execution (unsafe/accidental paths)

1. **CLI run_shell_command** — `ProcessBuilder("sh","-c",command)` (Tools.kt:98),
   model-controlled, no allowlist, 60s cap; **enabled by default** (Main.kt:29)
   while README claims default-off (:56). Reachable by anyone running the CLI with
   an API key. Owner: venice-agent.
2. **hub download_civitai_lora injection** — `--id {p}` / `--slug {slug}`
   interpolated unquoted (venice.py:1662-1665) into laptop bash; a model-emitted
   id/slug containing `;` executes on the laptop. Shared side quotes correctly
   (Tools.kt:513-517). Owner: hub (group 1) + script dedupe (this group).
3. **shared queue_gpu_render** — vast branch POSTs model-supplied `path` with no
   `/api/` validation (Tools.kt:569); hub equivalent validates (venice.py:1290-1292).
   Same-host only (base fixed) but reaches non-API endpoints. Owner: shared.
4. **desktop git tools** — accessTools added unconditionally (Tools.kt:58-60)
   despite "no extra power" docstring; `git_commit` has no approve()
   (AccessTools.kt:152-161) → desktop model can commit to laptop repos silently;
   push denied-by-default (approve returns !requireUi=false, :116-122). Owner: shared.
5. **civitai_red_dl** — pip install at import + TLS off (§7). Owner: tools.
6. **McpStdioClient** — arbitrary `ProcessBuilder(config.command)` — unreachable
   (dead), so not an active risk. Owner: venice-agent.
7. Verified-safe: relayHttp basic-auth over HTTPS to fixed base; hubRequest
   `/api/`+`..` validation; laptop.py RunIn field validation + `sh -c "$CODE"`
   with timeout (laptop.py:66-110); workspace resolveWithinWorkspace symlink
   re-resolve (Tools.kt:337-347); phone tools path-containment + trash-not-rm.

## 9. Configuration

| Item | Copies | Authoritative |
|---|---|---|
| Relay URL `https://84-12-112-249.sslip.io` | CLI Tools.kt:38, android SettingsManager.kt:35, desktop Settings.kt:29, hub gen.py:155/ltx.py:469,1259/ltx_qa_cycle.py:86 | should be hub-derived; today 6 copies |
| Relay user `anon3` | CLI Tools.kt:39, android :36, desktop Settings.kt:20 | same |
| Model `zai-org-glm-5-2` | CLI Main.kt:7, android :38+MainActivity:155, desktop Settings.kt:23, hub venice.py:28, web fallback index.html:1224 | hub `/api/venice/models` |
| System prompt | hub venice.py:286, CLI Main.kt:10, android SettingsManager (AGENT_INTEGRATION_PROMPT), desktop AgentScreen.kt:234 | hub (already served via /tools response) — 4 diverged copies |
| Workspace roots | CLI ./workspace, android filesDir/workspace, desktop Settings.workspaceDir, hub ~/.venice_workspace, laptop ~/venice_run | per-surface by design; undocumented divergence |
| Env | VENICE_API_KEY/BASE_URL/MODEL/TEMPERATURE/MAX_TOKENS/WEB_SEARCH shared CLI↔hub ✓; RELAY_* CLI-only; WAN_GC/WAN_RUNNER/WANBOT_URL/WANBOT_TOKEN ingest ✓ | fine |
| Feature flags | allow_file_delete (hub, default false), CARETAKER_LIVE, AGENT_ALLOW_SHELL (CLI, default ON) | CLI default inverted vs docs (D1) |

## 10. Cross-group integration defects

| # | Producer → Contract → Consumer | Mismatch | Owner |
|---|---|---|---|
| D1 | venice-agent README → "shell default off" → operators | code default ON (Main.kt:29) | venice-agent docs |
| D2 | hub AGENT_TOOLS → unquoted ids + `~/hub/static/term/civitai_red_dl.py` (stale fork) → laptop | tools/ copy is current & quoted | hub + repo dedupe |
| D3 | shared Tools.kt → queue_gpu_render vast path → relay hub | hub validates `/api/`, shared doesn't | shared |
| D4 | shared ToolBox → accessTools unconditional → desktop | docstring says no extra power; commit ungated | shared |
| D5 | hub `_object_schema` all-string params → render_assess_adjust_cycle → shared typed | two contracts for one tool | hub |
| D6 | hub run_laptop_command schema lacks cwd/timeout → web model | shared exposes both | hub |
| D7 | hub `remember` (server memory) vs shared `remember` (device memory) | same name, different stores | shared (rename) |
| D8 | both registries advertise `slug` → civitai_red_dl ignores it (:380) | dead param | tools |
| D9 | AGENTS.md → `uv run pytest tests/redteam/`, `laptop-app/test/redteam/`, `scripts/harness-run.sh` | paths absent at HEAD → gates unrunnable | docs |
| D10 | README structure blocks → `venice-agent/{VeniceClient,Models}.kt`, `android/.../agent/` | moved to shared/; dirs absent | docs |
| D11 | llms.txt/llms-full.txt → file index | pre-reset tree; indexes build artifacts | docs generator |
| D12 | hub /api/venice/tool → laptop-app (legacy) | no consumer outside web UI | laptop-app (retire) |

## 11. Test results (all run against frozen snapshot)

| Suite | Result | Production-path? |
|---|---|---|
| tools/test_civitai_red_dl.py | 5/5 PASS | real save()/mask()/pick_files with fake stream only |
| hub test_venice_agent + test_laptop_run + test_agentic_guards | 19/19 PASS | TestClient → real dispatch/sandbox/gates; one fake laptop.run |
| hub test_agentic_* + autonomy + multiagent + memory + senses | 25/25 PASS | real store/runner |
| venice-agent gradle :test | 36 PASS, 1 skip | real subprocesses (fake_mcp_server.py), real ToolBox IO |
| shared-core probe (android JVM tests) | 58/58 PASS (first run 57/58; the miss was missing `src/main/assets/skills` in the probe env, resolved by copying the 36 bundled skills) | real Agent/ToolBox/stores; fake Venice via local ServerSocket |
| tests/redteam | NOT RUNNABLE at HEAD (D9) | — |
| caretaker pytest | 0 items (self-exec scripts; import prints ALL PASS) | script-style |
| android/desktop full builds | NOT RUN (AGP/SDK weight) | — |
| live Venice/GPU/relay | NOT EXERCISED (no key; standing orders) | — |

## 12. Refactor assessment

| Subsystem | Class | Notes |
|---|---|---|
| venice-agent CLI core | NO REFACTOR | healthy; fix D1 doc + mirror e2ee guard |
| venice-agent MCP/registry | TARGETED | delete or wire behind flag; do not leave 560 lines tested-but-unreachable |
| shared core | TARGETED | validate vast path (D3), gate commit (D4), de-hardcode AccessConfig defaults, rename device remember (D7) |
| tools/ | TARGETED | single civitai script; quote hub ids (D2); add wan_ingest tests |
| hub tool layer | TARGETED (owner group 1) | D2/D5/D6 |
| scripts/relay/caretaker | NO REFACTOR | ops code, correctly separated |
| prompts/loops duplication | TARGETED | serve prompt from hub /tools to all natives; keep 4 loops (host-bound) but centralize the 3 mirrored safeguards |
| laptop-app | DELETE candidate | legacy, no consumers |

No major architectural refactor required.

## 13. Repairs made

None in-repo (audit authorized read-only). Probe scaffolding lives in
`%TEMP%/kilo/wd4-parity-probe` only.

## 14. Remaining unresolved issues

D1–D12 above (all unfixed, owners assigned); wan_ingest untested; web JS loop
untested; live model/GPU paths unverified (no credentials by design); tree still
being mutated by parallel sessions — re-verify HEAD before acting on line numbers.

## 15. Evidence index

venice-agent: Main.kt:7,10-14,29,33-47; Tools.kt:38-40,44-54,62-90,98;
Agent.kt:16-27,95-129; ToolRegistry.kt:19-136; mcp/McpStdioClient.kt:56-59,299-393;
mcp/McpServerLoader.kt:19-64; README.md:9,22,25,56; build.gradle.kts:21-22.
shared: Agent.kt:159-284,176,233-236,246-248; Tools.kt:58-60,94-218,283-328,405-426,
507-521,569,606-629; AccessTools.kt:29-31,105-122,152-184,186-235; VeniceClient.kt:29-118;
Mcp.kt:36-170; MemoryStore.kt:95-118; SkillStore.kt:127-166; StreamAssembler.kt;
ConversationStore.kt; ProjectStore.kt; Artifacts.kt; SpeechText.kt.
hub: venice.py:28,53-86,96-97,286,609-653,682-828,1370-1432,1434-1668,1657-1668,1679-1700;
laptop.py:66-110; colab.py:13,259; agentic/runner.py:39-67,215-266.
tools: civitai_red_dl.py:56-60,69,71-74,244-255,278-280,342,380; wan_ingest.py:17-21,
96-122,143-171,280,309; test_civitai_red_dl.py; deploy_hub.sh:14-15,29-33; wan-ingest.cmd:4.
android: MainActivity.kt:909-949; SettingsManager.kt:35-38; AgentWorkspace.kt:17-64;
app/src/test/.../AgentParityTest.kt,ToolBoxTest.kt,StreamingTest.kt,DeviceIntegrationTest.kt.
desktop: AgentScreen.kt:65-75,234; Settings.kt:20-29.
docs: README.md:21-28,63-68,115; AGENTS.md:26-27,49-52; llms.txt:9085-9160.
deleted-captured: scripts/harness-run.sh:35-37,138-153,180; tests/redteam/*.py.
