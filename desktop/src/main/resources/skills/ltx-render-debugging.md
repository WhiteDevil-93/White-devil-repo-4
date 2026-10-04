---
name: ltx-render-debugging
description: Diagnosing a bad LTX-2.5 render (wrong person moves, drift, jitter, weak action, bad anatomy) in the order the user's LTX guide sets. Use before changing LoRAs or re-rendering.
---

From the user's LTX-2.5 guide. Do NOT add LoRAs first. Check in this order and stop at the first real cause:
1. Is the action physically possible from the start image's pose and framing?
2. Are actors explicit (Person A/B with descriptors) and is limb ownership explicit (actor, side, limb, movement, target)?
3. Is the movement chronological (start, initiation, movement, contact, result, end)?
4. Too many things moving at once? One primary action per clip, minimal secondary motion.
5. Is a camera move interfering? Prefer "camera remains stationary" unless asked.
6. Motion LoRA too strong? 7. Several LoRAs interfering? 8. Same failure with no extra LoRAs?
9. Does full Dev differ from Distilled (only with the correct schedule for each)? 10. Same across several seeds?
11. Would structure (start/end keyframes, reference sheet, pose/depth control) fix it better than more text?
Known fixes: wrong person acts -> labels + limb ownership, fewer secondary actions, steady camera, lower motion LoRA.
Excess movement -> lower motion LoRA, remove "dramatic/wildly/energetic". Anatomy worse as motion rises -> lower motion
(0.60 -> 0.50 -> 0.45) before raising anatomy. Faces change between chained clips -> keep the face in frame.
Testing: change ONE variable, keep prompt/seed/image/size constant, compare several matched seeds. LoRA weights are
not percentages; official IC-LoRAs run at 1.0 in their own workflow, never as content LoRAs. Report what you changed
and what you saw, not a guess.
