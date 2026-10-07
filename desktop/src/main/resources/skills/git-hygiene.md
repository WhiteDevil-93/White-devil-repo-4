---
name: git-hygiene
description: Committing and pushing safely in the user's repos.
---

- Commit or push only when the user asked; on the default branch, branch first.
- Stage explicit paths. Never `git add -A` or `git add .` in HypnoForge or the shared main checkout: other sessions have uncommitted work there.
- Do not edit another session's uncommitted files or project-owned instruction files (a repo's AGENTS.md, SOUL.md, MEMORY.md) to record personal lessons.
- Look at git status and the diff before committing; keep unrelated changes out; write the message about why.
- Never force-push, reset --hard or skip hooks without an explicit yes.
- After pushing, show the commit and branch you pushed.
