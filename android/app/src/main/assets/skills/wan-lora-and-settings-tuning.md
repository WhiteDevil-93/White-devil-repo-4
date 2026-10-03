---
name: wan-lora-and-settings-tuning
description: Tuning Wan 2.2 14B settings (length, steps, seed, LoRA strength) after a prompt is correct.
---

1. Fix the prompt first (wan-14b-prompting); settings cannot repair a bad prompt.
2. Change one variable per render: seed (variety), steps (detail vs time), length (max 5 s per clip; longer needs a chain), LoRA strength (0.1-0.2 steps).
3. Keep a table of what you tried: setting, value, verdict. Stop after two non-improvements and ask.
4. Record the winning combination with remember so it becomes the default for next time.
