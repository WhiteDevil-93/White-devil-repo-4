---
name: prompt-pack-batching
description: Preparing and queueing a batch of prompt packs (Colab/Thunder) cheaply and in order.
---

- list_prompt_packs shows the available packs and numbers. Confirm which packs and how many clips before queueing; state the total expected GPU time.
- Queue a single pack first as a canary; review the result (render-review-iterate) before queueing the rest.
- Queue the remainder in one request when the backend allows it, so the box does not idle between jobs.
- Track the batch: count queued, running, done, failed from the queue state, not from memory.
- When the queue empties, run render-sync-verify and instance-idle-shutdown.
