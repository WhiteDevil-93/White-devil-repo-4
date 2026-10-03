---
name: render-review-iterate
description: Reviewing a finished render and deciding whether to accept, tweak and re-queue, or stop. Use after every render and before spending on a retry.
---

1. Look before judging: use review_latest_render (or the Renders screen) and describe only what you actually saw. Never invent visuals you have not seen.
2. Check against the brief: people count, identities, clothing, setting, requested actions in order, camera, no cuts, no stray text.
3. Classify each problem: prompt problem (wrong or missing wording), settings problem (length, seed, steps, LoRA strength), asset problem (bad start image) or model limit. Only prompt and settings problems are worth a retry.
4. Change one thing at a time and say what you changed.
5. Retry budget: at most two automatic retries per clip, and ask before the third because every retry costs GPU time. render_assess_adjust_cycle automates this; keep its iteration count small.
6. Report as: verdict, what was wrong, what changed, cost so far.
