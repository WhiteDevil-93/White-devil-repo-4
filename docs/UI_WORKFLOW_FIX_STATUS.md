# WhiteDevil UI repair status — 2026-10-07

Target: `A:\New folder (4)\white-devil-repo-4`, not the separate `wd-ui` checkout.
Implemented means present in this checkout, not deployed or accepted on a device.

| Review issue | Change / evidence | Remaining acceptance |
| --- | --- | --- |
| Desktop model context | `AgentScreen.kt` restores `AgentSession.history`; `UiWorkflowTest` checks authoritative prompt and previous turns | Real model continuation not run |
| Desktop navigation loses chat/run/draft | Window owns `AgentSession`; `AgentNavigationUiTest` leaves/returns during a suspended run, checks draft, then Stop | Packaged Windows app not run |
| Android Stop conflated with Clear | Separate Stop; Clear invalidates old run callbacks and final save; cancellation restores unsubmitted attachments/draft | APK interaction not run |
| Fictional status/identity/build | Configured is not Connected; local profile, actual BuildConfig; connection-test label | Live connectivity not tested |
| Hub stale/racing replies | Latest-request gate, clear data on destination change, stale error banner and update time; JVM regression tests | Live relay race not run |
| Implicit paid QA workload | Explicit eligible source, rounds, maximum clips and cost/replacement confirmation; offline JS tests | Browser/GPU execution not run |
| Misleading Done / duplicate actions | Pending actions deduplicated; Shotwriter disabled; shared reply classifier rejects application failure and exposes job ID | Real service responses not exercised |
| Attachment-only send/removal | Shared submission predicate (Android unit test); explicit Remove | Device attachments not exercised |
| Setup language | Add API key → Test connections → first goal copy | Actual first-launch walk-through not run |
| Fragmented/hidden navigation | Grouped scrollable desktop rail; Android grouped destination menu and swipe hint | Small-window/device navigation not run |
| Docs routing / security label | Docs opens an actual in-app workflow guide; test action labeled Test connections | SDK/reference documentation is not provided by the guide |
| Long settings form | Connection / Agent / Security / Advanced sections retain common form and Save | Device settings/save not exercised |
| Chat controls/reading position/tool state | Explicit Copy/Expand, Jump to latest, conditional autoscroll; Running/returned/interrupted/reported-failure labels; Compose controls test | No arbitrary automatic tool retry: paid/destructive operations must be reviewed; screen-reader pass not run |
| Hidden library limits / inaccessible folders | Show more/counts; responsive Gallery columns; subfolder navigation and Up breadcrumb | SAF providers and media browsing not exercised on a phone |
| Silent persistence failures | Atomic history publication, unreadable-history protection, in-memory context on Android write failure, save retry; serialized web sync with visible failure/retry | Process-kill during an active goal and server sync not tested |
| Visual/accessibility drift | Native colors aligned to web tokens; larger labels/targets, icon names, selected tab semantics; palette/contrast regression test | Full WCAG, keyboard, 200% font scaling and APK visual acceptance not established |

## Verification commands and evidence

- `desktop\gradlew.bat :test --offline --console=plain` — inspect
  `desktop/build/test-results/test/TEST-*.xml`: 364 tests, 358 passed,
  0 failures/errors, 6 skipped. Includes persistence,
  reply/race rules and two real Compose interaction regressions.
- `android\gradlew.bat :app:testDebugUnitTest --offline --console=plain` —
  41 passed, 0 failures/errors/skips; includes Android compilation.
  XML in `android/app/build/test-results/testDebugUnitTest`.
- `node --test hub/static/ui/workflow.test.cjs` — 8 passed, 0 failures/skips.
- Both changed HTML files' inline scripts parsed with Node `vm.Script`:
  LTX 1 script, Venice 4 scripts, no syntax failures.
- `git diff --check` — no whitespace errors.

The desktop suite has six existing shell-runner skips: `/bin/sh` is not executable
on this Windows host (`HelloHelperTest.kt: requireSh`). The skipped cases are stdout
capture, timeout termination, descendant termination, caller cancellation, output
cap, and fake-helper end-to-end parsing. These are not UI acceptance evidence.

Project red-team gates were attempted, not green:
`uv run pytest -q` cannot spawn pytest; `uv run ruff check tests/redteam laptop-app/test/redteam`
cannot spawn ruff (programs not found). Those unrelated scaffold tests also contain
placeholder bodies and must not be represented as proof of the UI repairs.

No live API, render, cloud rental, deployment, service restart or packaged-app
acceptance run was performed. Other sessions committed portions of this shared
checkout during implementation; this session issued no commit or push command.
