---
name: vast-ops
description: Operating Vast.ai rentals through the hub: listing, starting, stopping, SSH access and known quirks.
---

- The hub manages Vast lifecycle only (rent/manage); render work goes through the backends in choose-render-backend.
- Read endpoints (show instance/instances) have required 2FA (401) while write endpoints (create/start/stop) still worked with the stored key. If reads 401 but the box exists, use SSH with the direct port from the Vast console and the write endpoints.
- A CLI crash is not an auth rejection; classify the failure first (failure-triage). whoami is not a token-validity test; use a gated-file HEAD request.
- Never destroy an instance without the user saying so and without checking whether it holds the last copy of anything (last-copy-check).
- Leave e4b-litert-export (RTX 5090, exited, 63 GB used) alone: it holds the E4B export.
- Starting costs money. Follow gpu-cost-guard. After any work, run render-sync-verify then instance-idle-shutdown.
