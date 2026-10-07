# Red Team Harness Integration for WhiteDevil 4

## Overview

This adds red team capability to the WhiteDevil harness by:

- Testing tool authority, path isolation, and model protocol
- Enforcing Docker sandbox boundaries and resource limits
- Verifying secret scanning and completion integrity
- Providing headless Docker harness runners and Node.js bridge scripts

## Required Reading (shared hub)

Before running any tests, read the shared hub documents:

- `/home/anon3/ai-home/START.md`
- `/home/anon3/ai-home/AGENT-CONTRACT.md`
- `/home/anon3/ai-home/PERMISSIONS.md`
- `/home/anon3/ai-home/policies/standing-orders.md`
- `/home/anon3/ai-home/policies/windows-python.md`
- `/home/anon3/ai-home/policies/python-runtime.md`

## Layout

```
white-devil-repo-4/
├── AGENTS.md                    # Project agent guidance (new)
├── REDTEAM_INTEGRATION.md       # This file
├── tmp-AGENTS.md                # Previous project AGENTS.md
├── tests/
│   └── redteam/                 # Python tests (new)
│       ├── __init__.py
│       ├── test_redteam_policies.py
│       └── test_redteam_tooling.py
├── scripts/                     # Harness helpers (new)
│   └── harness-run.sh           # Docker benchmark runner
└── laptop-app/
    ├── test/                     # Node.js test area
    │   └── redteam/
    │       └── harness-entry.cjs # Node.js harness bridge
    ├── main.cjs                  # WhiteDevil CLI
    └── node_modules/
```

## Running Tests

Python (policy and tooling):

```bash
cd "/mnt/a/New folder (4)/white-devil-repo-4"
uv run pytest -q tests/redteam/
uv run ruff check tests/redteam/
```

Docker harness runner:

```bash
cd "/mnt/a/New folder (4)/white-devil-repo-4"
bash scripts/harness-run.sh --scenario parent_traversal
# Output logs to artifacts/<scenario>/<scenario>.log
```

Node.js harness bridge:

```bash
cd "/mnt/a/New folder (4)/white-devil-repo-4"
node laptop-app/test/redteam harness-entry.cjs --list
node laptop-app/test/redteam harness-entry.cjs bogus_done_completion
```

Note: All tests run headless in Docker or via CLI; desktop changes are limited to test artifacts in `artifacts/` and temporary files in `/tmp/harness-redteam`.

## Red Team Scenarios

Defined in `tests/redteam/test_redteam_policies.py` and `REDTEAM_SCENARIOS`:

| Scenario | Description | Key Tests |
|----------|-------------|-----------|
| parent_traversal | Access files outside bound directory tree | Test parent directory escape |
| absolute_path | Read absolute path when relative only | Test boundary enforcement |
| symlink_escape | Follow symlink into parent | Test path isolation |
| model_malformed_args | Send malformed tool arguments | Test protocol validation |
| bogus_done_completion | Complete with 'done' without evidence | Test completion integrity |

## Guardrails (per shared hub)

Each harness run observes:

- **Memory**: 256MB (configurable via `MEMORY_MB`)
- **CPU**: 1 core (configurable)
- **PIDs**: 50 (configurable)
- **Timeout**: 30s (configurable)
- **Network**: disabled by default (toggle via `NETWORK_ON=1`)
- **Root**: read-only enabled (`READONLY_ROOT=0` to disable)
- **Logs**: capped at 10MB per container, rotated to 1 file
- **Artifacts**: written to `artifacts/<scenario>/`, destroyed after run

## Testbed Repositories

Use any of these as test scenarios (examples, not full integration):

- web-slim-lambda: https://github.com/ajv/web-slim-lambda
- chat-thread-imitation: https://github.com/yoyo-nb/chat-thread-imitation
- simple-cloud-kv: https://github.com/simple-store/simple-cloud-kv

Choose which to use for which scenario; not all need to be present.

## Security and Privacy

- Run tests headless; no desktop UI updates on container lifecycle
- No host directory modifications except test artifacts (managed via temp dirs)
- Each run uses fresh temporary volumes and gets wiped after pass/fail
- Logs contain operational details only, no secrets (see CREDENTIALS.md for locations)
- Secret scanning is heuristic and does not substitute for dedicated secret management

## Future Extensions

Benefits of this integration:

- Formal red team policy validation
- Automated failure detection and reporting
- Easy addition of new red team scenarios
- Bridge between Node.js and Python harness code
- External evaluation integration via Docker

Additions can be made to:

- `tests/redteam/test_redteam_*.py` — new policy/tooling suites
- `scripts/*.sh` — new harness runners
- `laptop-app/test/redteam/*.cjs` — new Node.js bridges

## Limits and Known Gap

- This is a local harness exercise, not a security certification or exhaustive container-escape assessment
- Secret scanning is heuristic and cannot identify every credential format
- Router refusal tests verify tool availability, not live model verbal refusals
- No live cloud, network target, GPU was exercised in this initial integration
