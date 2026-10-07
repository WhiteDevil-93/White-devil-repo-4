# UI workflow repair

Scope: the named checkout, not the separate wd-ui branch. Preserve existing download,
build, and red-team work. No live paid jobs, deployments, or service restarts.

## Cause analysis (fishbone)

Conversation loss <- screen-owned scope/state; missing restore; silent disk failures.
Misleading state <- configuration presented as connectivity; static identity; ignored replies.
Uncontrolled work <- no separate Stop; duplicate submits; implicit QA source/workload.
Navigation friction <- infrastructure-first labels; hidden limits; inaccessible icon actions.
Visual drift <- independent palettes; undersized labels and targets.

## Plan and acceptance gates

1. Own desktop conversation/runs above navigation; restore model history and persist
   atomically, exposing failures. Gate: local persistence/restore tests and compile.
2. Separate Android Stop/Clear; protect history reset; attachment-only send. Gate:
   Android compile and focused tests where feasible, no network calls.
3. Guard Hub responses against obsolete selection; surface stale errors and pending
   actions; interpret returned action status. Gate: race/status regression tests.
4. Make QA source/rounds/workload explicit before submission. Gate: deterministic
   browser-side tests with no render submission.
5. Repair labels/docs/settings/navigation/library/files/chat and shared palette.
   Gate: affected suites and compile; distinguish source verification from device UX.

Fallback: never replace a failed saved conversation with an empty one silently.
Keep cached data visibly stale on refresh failure. Reject obsolete responses and
duplicate actions. No automatic paid retries. GUI/APK acceptance remains unverified
unless actually exercised; test counts alone do not establish that acceptance.
