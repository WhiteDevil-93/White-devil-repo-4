---
name: model-storage-safety
description: Rules for model weights and checkouts on laptop and remote boxes.
---

- Model files live in /workspace/models (remote) outside the ComfyUI checkout and are symlinked in. Rebuilding or reinstalling must never touch the weights.
- Never rm -rf a ComfyUI checkout: models/ inside it is up to 50 GB.
- Before deleting any model artifact, run last-copy-check.
- After any move, print file counts and sizes; an empty glob must not look like success.
