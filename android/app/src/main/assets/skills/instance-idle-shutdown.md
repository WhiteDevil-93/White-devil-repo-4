---
name: instance-idle-shutdown
description: Safely stopping an idle cloud GPU after verified sync. Use when a job queue is empty or work is finished.
---

1. Confirm idle: GPU utilisation under threshold AND no queued or running renders, for 30 consecutive minutes (or the user said stop now).
2. Check for in-flight work from the user: running renders, open sessions, an install in progress. If any, do not stop; report.
3. Run render-sync-verify. Do not stop on an unverified sync.
4. Check /workspace/GUARD_PAUSED on the instance: while it exists, shutdown is suppressed on purpose (setup or maintenance). Respect it.
5. Stop (not destroy) through the hub or the vendor's write endpoint. If read endpoints return 401 (2FA quirk) but writes work, lifecycle-manage via write endpoints and use SSH with the direct port for inspection.
6. Verify the stop: re-check state server-side. Report the state you read, not the command you ran.
