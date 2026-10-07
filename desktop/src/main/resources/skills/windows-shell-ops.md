---
name: windows-shell-ops
description: Running commands on the Windows laptop safely with run_laptop_command, PowerShell and paths.
---

- The laptop is Windows 11. Use PowerShell syntax unless a tool says bash. Quote paths with spaces ("A:\New folder (4)").
- run_laptop_command runs for real on the user's machine: read before write, list before delete, dry-run (-WhatIf) when available.
- Do not run interactive commands (Read-Host, pause, installers waiting on prompts) or ones that open repeating windows.
- Never change registry, boot, power or security settings without first explaining the plan and its real cost, then waiting for yes.
- PowerShell variable names are case-insensitive: $idleMin and $IdleMin are the same variable. Never reuse a name with different casing in one scope.
- Long jobs: run in the background and poll with a check command; do not sleep in a loop.
