"""Fixed runbook. The model may only pick a name from ACTIONS; it never supplies a command.

Every call goes through execute(). Default is dry_run=True: it logs what it WOULD do and does nothing.
Disruptive actions: pause-file check -> in-flight check -> backup -> notify + grace -> re-check
in-flight -> act -> verify hub -> audit. Anything failing a gate defers (never forces).
"""
import json, subprocess, time, urllib.request
from pathlib import Path

import inflight

HOME = Path.home()
DIR = HOME / "caretaker"
PAUSED = DIR / "PAUSED"          # exists -> do nothing at all (same idea as GUARD_PAUSED)
AUDIT = DIR / "audit.jsonl"
NTFY = HOME / ".wan_ntfy_topic"
GRACE_S = 120
LOW = ["nice", "-n", "19", "ionice", "-c", "3"]   # never compete with the hub or renders


def audit(**kw):
    kw["t"] = time.strftime("%Y-%m-%dT%H:%M:%S%z")
    with AUDIT.open("a") as f:
        f.write(json.dumps(kw) + "\n")


def notify(text):
    try:
        topic = NTFY.read_text().strip()
        urllib.request.urlopen(urllib.request.Request(
            f"https://ntfy.sh/{topic}", data=text.encode(), headers={"Title": "Relay caretaker"}), timeout=20)
        return True
    except Exception as e:
        audit(event="notify_failed", err=str(e)[:200])
        return False


def hub_ok():
    try:
        with urllib.request.urlopen("http://127.0.0.1:9000/api/status", timeout=8) as r:
            return r.status == 200
    except Exception:
        return False


def _sh(cmd, t=900):
    p = subprocess.run(cmd, capture_output=True, text=True, timeout=t)
    return p.returncode, (p.stdout + p.stderr)[-400:]


# name -> (disruptive, command or callable). sudo uses -n so it fails fast instead of hanging.
ACTIONS = {
    "noop":            (False, None),
    "run_backup":      (False, LOW + [str(HOME / "bin/forge-backup.sh")]),
    "rotate_logs":     (False, ["sudo", "-n", "journalctl", "--vacuum-time=14d"]),
    "apt_upgrade":     (True,  ["sudo", "-n", "apt-get", "-y", "-o", "Dpkg::Options::=--force-confold", "upgrade"]),
    "restart_hub":     (True,  ["sudo", "-n", "systemctl", "restart", "forge-hub"]),
    "reboot":          (True,  ["sudo", "-n", "systemctl", "reboot"]),
}


def execute(name, dry_run=True, grace_s=GRACE_S, reason=""):
    if name not in ACTIONS:
        audit(event="rejected_unknown_action", action=name)
        return {"ok": False, "status": "rejected", "why": "not in runbook"}
    if PAUSED.exists():
        audit(event="paused", action=name)
        return {"ok": False, "status": "paused"}
    disruptive, cmd = ACTIONS[name]
    if cmd is None:
        return {"ok": True, "status": "noop"}

    if disruptive:
        # Deep check: also asks the hub about Colab/Thunder/Vast/laptop. restart_hub may act when the hub is unreadable.
        deep = dict(deep=True, allow_unknown=(name == "restart_hub"))
        blockers = inflight.detect(**deep)
        if blockers:
            audit(event="deferred", action=name, why=blockers, reason=reason)
            return {"ok": False, "status": "deferred", "why": blockers}
        if dry_run:
            audit(event="dry_run", action=name, plan="backup -> notify -> grace -> recheck -> act -> verify", reason=reason)
            return {"ok": True, "status": "dry_run", "would_run": cmd}
        rc, out = _sh(ACTIONS["run_backup"][1])
        if rc != 0:
            audit(event="aborted_backup_failed", action=name, out=out)
            return {"ok": False, "status": "aborted", "why": "backup failed"}
        notify(f"Relay: '{name}' in {grace_s}s, short interruption. {reason}".strip())
        time.sleep(grace_s)
        inflight._cache["v"] = None          # the grace period is exactly when new work may have started
        blockers = inflight.detect(**deep)
        if blockers:
            audit(event="deferred_after_grace", action=name, why=blockers)
            notify(f"Relay: '{name}' postponed, work started during the notice.")
            return {"ok": False, "status": "deferred", "why": blockers}
        rc, out = _sh(cmd)
        if name != "reboot":
            for _ in range(24):                       # up to ~2 min for the hub to answer
                if hub_ok():
                    break
                time.sleep(5)
            ok = rc == 0 and hub_ok()
            audit(event="done" if ok else "FAILED", action=name, rc=rc, out=out)
            notify(f"Relay: '{name}' " + ("done." if ok else "FAILED, needs you."))
            return {"ok": ok, "status": "done" if ok else "failed", "rc": rc}
        audit(event="reboot_issued", rc=rc, out=out)   # post-boot check runs from the next loop
        return {"ok": rc == 0, "status": "reboot_issued"}

    if dry_run:
        audit(event="dry_run", action=name, reason=reason)
        return {"ok": True, "status": "dry_run", "would_run": cmd}
    rc, out = _sh(cmd)
    audit(event="done" if rc == 0 else "FAILED", action=name, rc=rc, out=out)
    return {"ok": rc == 0, "status": "done" if rc == 0 else "failed", "rc": rc}
