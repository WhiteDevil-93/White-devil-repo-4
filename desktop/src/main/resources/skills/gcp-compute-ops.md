---
name: gcp-compute-ops
description: Inspecting and controlling Google Compute Engine VMs with gcloud, with cost safety. Use for any GCP VM, disk or GPU question.
---

The user watches GCP spend closely. Audit unprompted: after any GCP task, say what is still running and what it costs.
- Context: run `gcloud config list` first and state the project, region and account you are acting as (authentication here impersonates a service account; do not change it).
- Read first, always: `gcloud compute instances list --format="table(name,zone,status,machineType.basename())"`, `gcloud compute disks list`, `gcloud compute addresses list`, `gcloud compute snapshots list`.
- Money leaks to look for: RUNNING instances, GPUs attached, unattached disks, reserved but unused static IPs, old snapshots.
- Stop, never delete: `gcloud compute instances stop NAME --zone ZONE`. Deleting an instance or disk needs an explicit yes plus last-copy-check.
- Ask before: starting or creating anything (state machine type, GPU, hourly price estimate), resizing, or changing firewall/IAM.
- After a stop, verify by listing again and report the STATUS you read (TERMINATED), not the command you ran.
- Never print tokens or key files; use gcloud, not hand-built API calls with credentials.
- The official Compute Engine MCP (https://compute.googleapis.com/mcp) is not wired in yet; gcloud through run_laptop_command is the supported path.
