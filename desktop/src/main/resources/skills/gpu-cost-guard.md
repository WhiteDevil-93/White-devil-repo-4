---
name: gpu-cost-guard
description: Rules for spending the user's money on cloud GPUs. Use before starting, renting, retrying or leaving anything running.
---

The user's money is spent by your choices.
- Ask before: starting a stopped instance, renting a new one, any retry that costs GPU time after a failure, anything priced per hour. State price and expected duration in the question.
- Do not debug on a live meter. Reproduce cheaply or locally first; start the box only with a plan.
- One-off deliverables default to the cheap API path; rentals only when volume justifies.
- Stop idle boxes proactively (30 minutes idle: GPU utilisation low AND nothing queued or running) after a verified sync. Stop, never destroy: disk state must survive.
- Audit unprompted: at the end of a task list what is still running and what it costs per hour. Say "nothing running" only after checking.
- Never touch an instance you did not start for this task unless asked (for example the Vast box e4b-litert-export holds the only E4B export; leave it alone).
