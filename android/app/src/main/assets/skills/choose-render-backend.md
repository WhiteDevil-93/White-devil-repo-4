---
name: choose-render-backend
description: Deciding where a render should run (Thunder, Colab, Vast, LTX box) and what it costs. Use whenever a render is requested without a named backend.
---

Facts to gather first (do not guess): hub_overview for what is running and idle, then the specific domain state.
- Wan 2.2 14B: Thunder queue (/api/thunder/queue, bot on the instance). This is the default for 14B.
- Colab: prompt-pack queue; free/limited tier, billing must be OFF. Needs the user to Start it from the Colab screen; confirm it is running before queueing and say so if it is stopped.
- LTX 2.5: the LTX builder on the hub (/api/ltx/*).
- Vast: rented boxes. Rent/manage only; costs money per hour while running.

Decision rules:
1. One-off or few clips: use the cheapest backend that is already running. Do not start a paid box for a single clip if an idle one exists.
2. Volume (many clips, a long chain): a rental is justified, but state the hourly price and estimate before starting it and wait for yes.
3. If nothing is running, tell the user what would need starting and what it costs; do not start it silently.
4. After queueing, follow the render-sync-verify and instance-idle-shutdown skills so nothing is left running or unsaved.
