---
name: render-sync-verify
description: Syncing renders from a rented GPU to the laptop and PROVING they arrived. Use before ending any session that used a cloud GPU.
---

Standing order: every render made on a rented GPU must be on the laptop at A:\ComfyUI\renders\<instance> and verified by byte size against the instance before the session counts as done. "It is on the box" is not saved.

Procedure:
1. List the files and sizes on the instance (ssh/hub), and on the laptop folder.
2. Copy anything missing. A tool saying "synced" is not proof.
3. Compare byte sizes file by file. Print the counts of both sides.
4. Report: N files on instance, N on laptop, M mismatches (name them). Zero files on both sides is a failed check, not a pass.
5. Only after a clean match may the instance be stopped (see instance-idle-shutdown). Never delete the instance copy.
A:\ComfyUI\guard.ps1 does sync+verify+idle-watch; read it and cloud.conf there before re-implementing anything.
