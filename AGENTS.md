# WhiteDevil 4 Agent Guidance

## Context

This repo ships an agentic desktop app (WhiteDevil), with `laptop-app/` as the operating entry point, a shared backend (`hub/`), mobile clients, and tooling.

All agent work here must respect the shared hub (`/home/anon3/ai-home`) and the current session contract — no extra resource approvals, no persistent changes to existing workloads unless explicitly authorized.

## Red Team Harness Integration

### Goal

Add red team capability to the harness by testing:

- Tool authority and refusals
- Path isolation (parent traversal, symlinks, secret direcotries)
- Execution boundaries (Docker sandbox fallback)
- Model protocol errors (malformed tool arguments, multiple calls)
- Completion integrity (stale evidence, oversized context, interrupted intent)
- Workspace review (new/ignored files, stale hashes, patch exports)
- Docker runtime behavior (network, read-only root, resource limits, timeout)
- Content-based secret handling (scanning bounded file contents for credentials)

### Key Areas

- **Tests**: `tests/redteam/test_redteam_policies.py` and `tests/redteam/test_redteam_tooling.py`
- **Scripts**: `scripts/harness-run.sh` and shared harness helpers
- **Node integration**: `laptop-app/test/redteam/` for Node.js harness tests

### Maven Rules (from shared hub)

- Start every session by reading START.md, AGENT-CONTRACT.md, PERMISSIONS.md, policies/standing-orders.md, and this file
- Every claim of fact carries inline evidence (command, path, raw number)
- Do not assume live model endpoints or GPU availability — work in bounded harness/unit-test style, no live Colab or remote GPU races
- Docker sandbox scenarios run headless; desktop changes are limited to test artifacts
- Account and tool credentials live in their native stores; do not copy secrets into repos or hub exports

### Testbed Repositories

- **web-slim-lambda**: `https://github.com/ajv/web-slim-lambda`
- **chat-thread-imitation**: `https://github.com/yoyo-nb/chat-thread-imitation`
- **simple-cloud-kv**: `https://github.com/simple-store/simple-cloud-kv`

(Choose which to use for which scenario; not all need to be present.)

### Running Tests

```bash
cd "/mnt/a/New folder (4)/white-devil-repo-4"
uv run pytest -q tests/redteam/
uv run pytest -q laptop-app/test/redteam/
bash scripts/harness-run.sh --help
```

Any new or modified test file must:
- Use corresponding shared hub startup docs (START.md, AGENT-CONTRACT.md, PERMISSIONS.md, policies/standing-orders.md)
- Keep secrets out of repos and exports
- Reference the appropriate testbed if applicable
- Call `uv run pytest -q` and `uv run ruff check tests/redteam laptop-app/test/redteam` as final gates

## Behavior Limits

- Do not run live model endpoints or cloud GPU workloads without explicit testbed use
- Do not restart services without checking for owner in-flight work
- Docker sandbox scenarios are headless runs; the desktop app does not auto-update on container lifecycle events
- Do not add agent-specific guidance into this file; keep it project-level and versioned
