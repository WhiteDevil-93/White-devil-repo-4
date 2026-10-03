---
name: disk-space-triage
description: Finding what is using disk on the laptop (A:, C:) and freeing space safely.
---

1. Get free space per drive first. Report numbers.
2. Find large folders/files with a read-only scan (top N by size). Common heavy places: model weights, renders (A:\ComfyUI\renders), Gradle caches, build outputs, old installers.
3. Classify each candidate: regenerable (build outputs, caches), precious (renders, models, exports), unknown. Only regenerable items may be suggested for cleanup; precious needs last-copy-check.
4. Propose the list with sizes and wait for yes. Delete in small steps and re-measure.
