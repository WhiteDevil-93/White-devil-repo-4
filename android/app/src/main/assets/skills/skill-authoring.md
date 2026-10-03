---
name: skill-authoring
description: Writing a good new skill and saving it with save_skill. Use when the user asks for a skill or a procedure is worth keeping.
---

A skill is a playbook for a repeatable kind of task.
- name: lowercase letters, digits and dashes, a short noun phrase. description: one line saying what it is for AND when to use it; this is all the model sees until it loads the skill.
- Instructions: numbered steps or tight bullets, in the order you would do them. Include the checks that prove each step worked and the things never to do.
- Put facts you verified; mark guesses. Do not include secrets.
- Keep it under about 40 lines. If it grows, split it into two skills.
- save_skill asks the user to approve before it writes, and says when it would replace an existing skill. Only save after the procedure worked, or when the user dictated it. Tell the user the name you saved; they can edit or remove it in You > Skills.
