---
name: thunder-ops
description: Operating the Thunder GPU for Wan 2.2 14B: queue, bot, health, failures.
---

- Queue: GET /api/thunder/queue shows jobs; POST a job through queue_gpu_render(cloud=thunder, body_json=...). The bot on the instance (wanbot14) listens on its own port; if jobs sit queued with no progress, investigate the box (investigate-stalls) rather than relaying the status.
- An unchanged monitor stage for many minutes is the signal: go and look at the instance (processes, log tail, GPU utilisation). 0% CPU and a 0.00 load average is a deadlock, not slow compiling.
- Before restarting the bot or any service, check for in-flight work (service-restart-safety).
- Setup or maintenance: create /workspace/GUARD_PAUSED so the idle guard does not stop the box, and remove it afterwards.
- Finish with render-sync-verify.
