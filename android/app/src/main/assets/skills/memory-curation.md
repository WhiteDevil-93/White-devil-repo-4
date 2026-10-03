---
name: memory-curation
description: What to save with remember and what not to, and how to keep the memory list short and correct.
---

Save (sparingly): stable user preferences (defaults for length, resolution, style), decisions about a project, where things live, procedures verified to work.
Do not save: secrets or credentials, one-off task details, anything already in the repo or visible from the hub, guesses, or raw logs.
- One fact per memory, one short sentence. remember takes only the text.
- Check the MEMORY block in your prompt first so you do not duplicate or contradict it. If a saved fact turned out wrong, forget it by its id (shown in brackets), save the corrected fact, and say so.
- The user can review and delete memories in You > Memory. Everything saved is sent to the model with every message, so a long list costs money and attention.
- Repeatable procedures belong in a skill (save_skill), not in a memory.
