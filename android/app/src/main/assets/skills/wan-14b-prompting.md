---
name: wan-14b-prompting
description: Writing positive/negative prompts for Wan 2.2 14B video clips. Use before queueing or editing any Wan prompt.
---

Wan fills every gap with its own cinematic defaults (extra people, cuts, location drift) and only animates what the prompt describes as visible motion. The ACTION is the point; everything else supports it.

Order of a positive prompt (plain present-tense English, short sentences):
1. Cast: the exact number of people, and for each an adult age (20s or older), build, hair, clothing. State that nobody else appears and that everyone is in place at the first frame.
2. Setting: one short sentence, one light source. If the brief names no place, pick one plain place and keep it identical everywhere.
3. Camera: copy the brief's framing and camera lines. If none, write "The camera does not move."
4. Action: at least half of all words. Who does what, in order, paced to the clip length: body parts, hand positions, speed, rhythm, where they look, how it changes.
5. Boundaries: only if the brief has "must not happen" items, restated positively in one sentence.
6. Style: a few words, only from the brief.

Rules: one continuous shot, no cuts or transitions. No invented characters, text or logos. For image-to-video build on the start image and spend words on motion. When over the word budget cut setting and style first, never action. Motion intensity sets pace, not which actions happen.

Negative prompt: a short comma-separated list specific to the scene (extra people, clothing changes, camera shake, subtitles, deformed hands). Never list anything the action needs.

The desktop app's Wan builder holds the canonical rules; this phone app has no Wan builder, so follow the rules above yourself when you write or review a prompt, then queue it with queue_gpu_render.
