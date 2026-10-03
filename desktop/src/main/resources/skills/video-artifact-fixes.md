---
name: video-artifact-fixes
description: Mapping common video defects (extra people, drift, cuts, flicker, deformed hands, frozen motion) to prompt or setting fixes.
---

- Extra or vanishing people: restate the exact count and "nobody else appears, nobody enters or leaves"; add "extra people" to the negative.
- Clothing/identity drift between chained clips: copy the bible text verbatim; do not paraphrase.
- Location drift: shorten the setting to one sentence and repeat it identically.
- Unwanted cuts: add "one continuous shot"; remove any wording that implies a change of scene or time.
- Frozen or minimal motion: the prompt describes states instead of movement. Rewrite the action half as visible, ordered body motion with speed and rhythm.
- Camera shake or zoom: state "The camera does not move." and negate shake.
- Deformed hands: describe hand position and grip explicitly; shorten the action list; negate "deformed hands".
- Flicker/blur at clip joins: end each clip on a settled pose and start the next from that exact state.
Apply one fix, re-render, compare. Do not stack five changes at once.
