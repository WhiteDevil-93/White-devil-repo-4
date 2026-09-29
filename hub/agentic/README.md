# Agentic side-track

**WhiteDevil is the agentic app; Forge Hub is one domain.** Parallel module at **`/api/agentic/*`** and lab UI **`/app/agentic/`**.

- Does **not** change Venice Bench, phone Agent chat, LTX, or default tool lists.
- Background goal runners default **`enabled: false`**.
- Data: `~/hub/agentic_data/` (config, permissions, memory, jobs, schedules, audit).

## Enable runners (explicit)

```bash
curl -s -X PUT localhost:9000/api/agentic/config \
  -H 'Content-Type: application/json' \
  -d '{"enabled":true}'
```

## Start a background goal

```bash
curl -s -X POST localhost:9000/api/agentic/jobs \
  -H 'Content-Type: application/json' \
  -d '{"goal":"Check Hub/Colab status and summarize","max_steps":12}'
```

## Schedules

```bash
curl -s -X POST localhost:9000/api/agentic/schedules \
  -H 'Content-Type: application/json' \
  -d '{"goal":"Summarize render queue","every_minutes":60}'
# cron on relay (optional):
# */5 * * * * curl -s -X POST localhost:9000/api/agentic/schedules/tick
```

## Peak map (what's here vs later)

| Peak capability | Side-track now | Later |
|-----------------|----------------|-------|
| Goal → plan → act → observe | Background jobs runner | Tighter planner UI |
| Recover / max steps | Yes | Smarter backtrack |
| Permissions / budgets | permissions.json | Per-tool confirm UX |
| Long-term memory | memory.json | Vector/docs RAG |
| Scheduled work | schedules + tick | Event triggers (render done) |
| Multi-agent | — | Sub-agent pool |
| Screen control / MCP runtime | — | Separate adapters |
| Voice | — | Client-side |

Venice chat remains the interactive path; this is the long-running / scheduled path.
