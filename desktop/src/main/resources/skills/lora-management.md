---
name: lora-management
description: Finding, downloading, assigning and naming LoRAs for Wan vs LTX.
---

- LoRAs are model-family specific: LTX 2.5 LoRAs listed in Setup work only with LTX 2.5; Wan 2.2 14B LoRAs only with Wan. Check the file's family before attaching.
- download_civitai_lora fetches through the laptop mirror downloader. Confirm the target folder and disk space first (disk-space-triage). Large downloads: tell the user the size before starting.
- Keep LoRAs under /workspace/models (remote) or the models root (laptop), not inside a ComfyUI checkout.
- Strength: start at the author's recommended value; change in steps of 0.1-0.2 and re-render, one LoRA at a time.
- Record which LoRA and strength produced a good result with remember (a note) so it can be reused.
