# relay/wan: the render-pipeline scripts that run on wan-relay

These were living **only** in `~/wan` on the OCI relay (no repo, no backup) until 2026-10-03.
This folder is the repo copy. The relay runs its own copy; changing a file here changes nothing
until you copy it over.

| Group | Files |
|---|---|
| Health / recovery | `heartbeat.sh`, `wan-heartbeat.service`, `colab_ops.sh`, `colab_recover.sh`, `colab_comfy_tunnel.sh`, `requeue_v3.sh`, `mirror14.sh` |
| Chains / resume | `resume_chains.py`, `colab_turbo/*` (boot, restore_resume, rehydrate_g4, run_gooning_chain_colab, mirror_chains, drive_sync, ...) |
| Status page / sync | `status_page.py`, `push_laptop.py`, `drive_pull.py` |
| WanBot (job runner on Colab) | `wanbot_src/` |

**Not here on purpose**
- `tools/wan_ingest.py` is the canonical ingest script. The relay still had an older copy of it
  (it matches commit 791dc6c from 2026-09-28), so it was not imported. Deploy the repo version.
- Runtime data: `renders/`, `thumbs/`, `lora_stage/`, `prompts/`, `gooning_chains/`, logs, `resume_chains.json`.
- Secrets. These scripts read them from the relay's home directory (`~/.wanbot_token`, the Drive
  token JSON, `~/.civitai_token`) or the environment. Nothing secret is committed; scanned for
  hardcoded tokens and client secrets on 2026-10-03: none found.
- `Caddyfile*`: deployment config, not imported.

**Updating the relay from here:** `rsync -a relay/wan/ wan-relay:~/wan/` (no `--delete`: `~/wan` also
holds runtime state). Restart nothing unless the change needs it, and check for running renders first.
