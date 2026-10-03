---
name: skill-authoring
description: Writing a good new skill with save_skill.
---

A skill is a playbook for a repeatable kind of task.
- name: short, dash-separated, a noun phrase. description: one line saying what it is for AND when to use it; this is all the model sees until it loads the skill.
- Body: numbered steps or tight bullets, in the order you would do them. Include the checks that prove each step worked and the things never to do.
- Put facts you verified; mark guesses. Do not include secrets.
- Keep it under about 40 lines. If it grows, split it into two skills.
- Only save a skill after the procedure worked, or when the user dictated it. Tell the user the name you saved.
