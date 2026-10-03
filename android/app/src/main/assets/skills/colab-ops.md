---
name: colab-ops
description: Operating Google Colab as a render backend: start/stop, packs queue, billing.
---

- Colab runs prompt packs (queue port 18900). It must be Started from the Colab screen; confirm state before queueing.
- Billing must be off ($0/h). After use, stop it and verify the state server-side; before stopping make sure there are no jobs queued or running.
- LTX installs on Colab have failed on model download steps; read the install run log (Setup > runs) for the FAILED lines before retrying, and do not retry blindly.
- Colab disks are ephemeral: sync outputs (render-sync-verify) before the runtime stops.
