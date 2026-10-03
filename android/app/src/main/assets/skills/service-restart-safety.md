---
name: service-restart-safety
description: Before restarting any hub, bot or app process: check for the user's in-flight work.
---

Restarting killed the user's renders twice. Before any restart (hub, wanbot, ComfyUI, Colab runtime, the laptop app):
1. Look for in-flight work: queued/running jobs on every backend, renders being written, an install, a download.
2. If anything is active, wait or ask. "Idle" must come from reading state, not assuming.
3. Prefer a hot fix that does not need a restart. When a restart is required, say why and what it will interrupt.
4. After the restart, verify the service is serving and the queue is intact (state you read, not the command you ran).
