---
name: wan-chain-planning
description: Planning a multi-clip Wan chain (bible, beats, start/end states, continuous vs cut). Use for any video longer than one 5 s clip.
---

Wan has no memory between clips. Continuity exists only in prompt text and, in continuous mode, the start frame.

1. Write a bible first: fixed descriptions of every character (age, build, face, hair, exact clothing and colors), the setting, the lighting, and a style suffix.
2. Every clip prompt repeats the bible character and setting text VERBATIM and ends with the style suffix VERBATIM. Never paraphrase between clips.
3. Exactly the requested number of clips, one beat each, paced to the clip duration.
4. Each clip gets start_state and end_state (pose, positions, expression, framing).
5. CONTINUOUS mode (clips 2+ are image-to-video from the previous last frame): start_state must equal the previous end_state; open from that state; end each clip on a settled pose, faces visible, no motion blur or mid-gesture; keep framing unless a beat needs a change, and then move slowly.
6. CUT mode: clips are independent; angle and framing may change, but characters, setting, lighting and style stay identical.
7. People count is constant unless a beat explicitly adds someone, and then only in that clip.

Output shape used by the app: {"bible":{"characters","setting","style"},"clips":[{"title","start_state","prompt","end_state","negative"}]}. Use the Create tab's chain builder; only hand-plan when debugging a bad chain.
