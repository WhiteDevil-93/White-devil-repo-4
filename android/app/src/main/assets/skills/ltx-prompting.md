---
name: ltx-prompting
description: Writing prompts and settings for LTX 2.5 clips (text-to-video, image-to-video, director plans). Use before queueing an LTX job.
---

- LTX renders from a single descriptive paragraph. Lead with subject and action, then setting, camera, lighting. Present tense, concrete visible motion, no story words or slang.
- One continuous shot per clip. State camera movement explicitly or say it is static.
- Keep identity details (age, build, hair, clothing) identical across clips of one project; copy them verbatim.
- Image-to-video: do not redescribe the image in conflicting ways; spend the words on motion and camera.
- Text-to-video with a director plan: the hub's t2v-with-plan path expects a plan (lines/directed) rather than a bare source image; if a job is rejected for having no source, the plan is what is missing.
- LTX LoRAs listed in Setup are for LTX 2.5 only. Never attach them to Wan jobs.
- Start with the New render page on the hub or the app's LTX builder, and use hub_request GET /api/ltx/status to confirm the builder is up before queueing.
- Check the result with the render-review-iterate skill; clips without an audio track make Sharpen 2x fail (known hub bug), so do not offer it for those.
