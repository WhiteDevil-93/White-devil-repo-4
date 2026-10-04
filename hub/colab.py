"""Colab screen API: runtime state, wanbot queue, packs, and runtime control."""
import json
import os
import re
import subprocess
import time
from pathlib import Path

import requests
from fastapi import APIRouter, HTTPException
from pydantic import BaseModel

WAN = Path.home() / "wan"
RUNNER = "http://127.0.0.1:18900"
JSONL = WAN / "gooning_chains" / "data" / "gooning_chains.jsonl"
COLAB = str(Path.home() / ".local/bin/colab")

router = APIRouter(prefix="/api/colab")
_usage = {"at": 0, "text": ""}


def _token():
    try:
        return (Path.home() / ".wanbot_token").read_text().strip()
    except FileNotFoundError:
        return None


def runner(method, path, **kw):
    tok = _token()
    if not tok:
        return None
    try:
        r = requests.request(method, RUNNER + path, headers={"Authorization": f"Bearer {tok}"}, timeout=15, **kw)
    except requests.RequestException:
        return None
    if r.status_code >= 300:
        raise HTTPException(r.status_code, r.text[:300])
    return r.json()


def usage():
    if time.time() - _usage["at"] > 300:
        try:
            out = subprocess.run([COLAB, "usage"], capture_output=True, text=True, timeout=60).stdout
        except (subprocess.TimeoutExpired, FileNotFoundError, OSError):
            out = ""
        _usage.update(at=time.time(), text=out)
    t = _usage["text"]
    bal = re.search(r"balance:\s*([\d.]+)", t)
    rate = re.search(r"rate:\s*([\d.]+)", t)
    act = re.search(r"assignments:\s*(\d+)", t)
    return {"balance": float(bal[1]) if bal else None, "rate_per_hr": float(rate[1]) if rate else None,
            "active": int(act[1]) if act else None, "checked": _usage["at"]}


_session = {"at": 0, "data": None}


def session_info():
    """Live Colab VM from CLI / sessions.json — independent of wanbot tunnel."""
    if time.time() - _session["at"] < 30 and _session["data"] is not None:
        return _session["data"]
    info = {"name": None, "endpoint": None, "accelerator": None, "status": None, "running": False, "raw": ""}
    try:
        out = subprocess.run([COLAB, "status"], capture_output=True, text=True, timeout=45).stdout or ""
        info["raw"] = out[-500:]
        # [colab] gpu-… | Hardware: G4 | … | Status: IDLE
        m = re.search(r"\[colab\]\s+(\S+)\s*\|\s*Hardware:\s*([^|]+)\|.*?Status:\s*(\S+)", out)
        if m:
            info.update(endpoint=m.group(1).strip(), accelerator=m.group(2).strip(), status=m.group(3).strip())
            info["running"] = True
        elif re.search(r"No (?:active )?session|no session", out, re.I):
            info["status"] = "none"
    except (subprocess.TimeoutExpired, FileNotFoundError, OSError) as e:
        info["raw"] = str(e)[:200]
    try:
        sj = Path.home() / ".config/colab-cli/sessions.json"
        if sj.is_file():
            d = json.loads(sj.read_text())
            c = d.get("colab") or next(iter(d.values()), {}) or {}
            info["name"] = c.get("name") or "colab"
            info["endpoint"] = info["endpoint"] or c.get("endpoint")
            info["accelerator"] = info["accelerator"] or c.get("accelerator")
            if c.get("endpoint") and not info["status"]:
                info["status"] = "unknown"
                info["running"] = True
    except Exception:
        pass
    _session.update(at=time.time(), data=info)
    return info


def billing_active(u=None, sess=None):
    u = u or usage()
    sess = sess if sess is not None else session_info()
    return bool((u.get("active") or 0) > 0 or sess.get("running"))


def status_summary(runner_online: bool, paused, u, sess):
    """Human status that never hides a billing VM behind 'Stopped'/'Offline'."""
    billing = billing_active(u, sess)
    rate = u.get("rate_per_hr")
    # `colab usage` reports compute units per hour, not dollars.
    rate_s = f"{rate:.2f} compute units/h" if isinstance(rate, (int, float)) else "compute units/h"
    if billing and runner_online:
        return {"label": "Running", "kind": "ok", "billing": True,
                "detail": f"G4 billing {rate_s} · wanbot up"}
    if billing and not runner_online:
        return {"label": f"BILLING · runner down", "kind": "warn", "billing": True,
                "detail": f"Colab VM is up and charging {rate_s}, but the render tunnel/wanbot is down. Stop the runtime to stop the bill, or Start/restart to bring the runner back."}
    if paused:
        return {"label": "Stopped", "kind": "warn", "billing": False,
                "detail": f"Marked stopped {paused}. Confirm Active assignments is 0 in usage if you still see charges."}
    if sess.get("status") == "none" or not billing:
        return {"label": "No runtime", "kind": "bad", "billing": False,
                "detail": "No Colab assignment. Press Start / restart to spin a G4."}
    return {"label": "Unknown", "kind": "warn", "billing": billing, "detail": sess.get("raw") or ""}


def hb_status():
    out = {}
    try:
        for line in Path("/tmp/hb_status.txt").read_text().splitlines():
            k, _, v = line.partition(" ")
            out[k] = v
        out["at"] = Path("/tmp/hb_status.txt").stat().st_mtime
    except FileNotFoundError:
        pass
    return out


def tail(path, n):
    try:
        return path.read_text(errors="replace").splitlines()[-n:]
    except FileNotFoundError:
        return []


def _recover_running():
    # pgrep does not exist on Windows dev machines — treat as "not running" there.
    try:
        return subprocess.run(["pgrep", "-f", "colab_recover.sh"], capture_output=True).returncode == 0
    except (FileNotFoundError, OSError):
        return False


@router.get("/state")
def state():
    health = runner("GET", "/health")
    jobs = runner("GET", "/jobs") if health else None
    comfy_up = False
    try:
        r = requests.get("http://127.0.0.1:18288/", timeout=3)
        comfy_up = r.status_code < 500
    except requests.RequestException:
        comfy_up = False
    view = []
    for j in jobs or []:
        cl = j.get("clips") or []
        cur = next((c for c in cl if c.get("status") == "rendering"), None)
        done = [c for c in cl if c.get("seconds")]
        view.append({"id": j["id"], "name": j.get("name"), "chain_id": j.get("chain_id"), "status": j["status"],
                     "progress": j.get("progress"),
                     "current": cur["index"] if cur else None,
                     "avg_seconds": round(sum(c["seconds"] for c in done) / len(done)) if done else None,
                     "error": j.get("error")})
    u = usage()
    sess = session_info()
    paused = (WAN / "paused").read_text().strip() if (WAN / "paused").exists() else None
    billing = billing_active(u, sess)
    # If Google is charging us, never leave the Comfy tunnel parked on paused_comfy.
    if billing and (WAN / "paused_comfy").exists():
        (WAN / "paused_comfy").unlink(missing_ok=True)
        subprocess.run(["sudo", "systemctl", "restart", "wan-colab-comfy-tunnel.service"], capture_output=True)
    # Stale ~/wan/paused must not win over a live bill in the UI.
    if billing and paused:
        paused_for_ui = paused  # still expose that auto-restart was flipped off
    else:
        paused_for_ui = paused
    summ = status_summary(bool(health), None if billing else paused, u, sess)
    if billing and not health:
        if comfy_up:
            summ = {"label": "BILLING · LTX up", "kind": "warn", "billing": True,
                    "detail": f"Colab VM charging (~{(u.get('rate_per_hr') or 0):.2f} compute units/h). Comfy/LTX tunnel is up; wanbot pack runner is down."}
        else:
            summ = {"label": "BILLING · runner down", "kind": "warn", "billing": True,
                    "detail": f"Colab VM is charging (~{(u.get('rate_per_hr') or 0):.2f} compute units/h)" + (f" (app marked stop at {paused_for_ui})" if paused_for_ui else "") + ". Hit Stop to kill the bill, or Start/restart to bring services back."}
    gpu = (health or {}).get("gpu") or (f"NVIDIA {sess['accelerator']}" if sess.get("accelerator") else None)
    return {
        "runner_mode": (WAN / "runner_mode").read_text().strip() if (WAN / "runner_mode").exists() else None,
        "runner_online": bool(health),
        "comfy_online": comfy_up,
        "paused": paused_for_ui if billing else paused,
        "billing": summ["billing"],
        "instance": sess,
        "status": summ,
        "gpu": gpu,
        "jobs": view,
        "heartbeat": hb_status(),
        "usage": u,
        "recover_running": _recover_running(),
        "recover_log": tail(WAN / "recover.log", 12),
        "resume_packs": (WAN / "resume_packs").read_text().split() if (WAN / "resume_packs").exists() else [],
    }


@router.get("/runner-token")
def runner_token():
    tok = _token()
    if not tok:
        raise HTTPException(503, "runner offline")
    return {"url": "/runner", "token": tok}


@router.get("/packs")
def packs():
    rendered = {}
    if (WAN / "renders").exists():
        for f in (WAN / "renders").glob("smoke_goon_p*_c*.mp4"):
            m = re.match(r"smoke_goon_p(\d+)_c(\d+)_", f.name)
            if m:
                rendered.setdefault(int(m[1]), set()).add(int(m[2]))
    try:
        lines = JSONL.read_text(encoding="utf-8").splitlines()
    except FileNotFoundError:
        return []
    out = []
    for line in lines:
        if not line.strip():
            continue
        p = json.loads(line)
        i = int(p["index"])
        out.append({"index": i, "title": p.get("title", ""), "clips": len(p.get("clips") or []),
                    "rendered": len(rendered.get(i, ()))})
    return sorted(out, key=lambda p: p["index"])


@router.get("/jobs/{jid}")
def job(jid: str, log: int = 40):
    j = runner("GET", f"/jobs/{jid}?log={log}")
    if j is None:
        raise HTTPException(503, "runner offline")
    return j


class Queue(BaseModel):
    packs: list[int]


@router.post("/queue")
def queue(q: Queue):
    if not runner("GET", "/health"):
        raise HTTPException(503, "runner offline")
    active = {j.get("name") for j in runner("GET", "/jobs") or [] if j["status"] in ("queued", "rendering", "waiting")}
    packs = [p for p in dict.fromkeys(q.packs) if f"goon_p{p:02d}" not in active]
    if not packs:
        return {"ok": True, "output": ["already queued"], "skipped": q.packs}
    env = dict(os.environ, WANBOT_URL=RUNNER, WANBOT_TOKEN=_token() or "", WAN_GC=str(WAN / "gooning_chains"))
    r = subprocess.run(["python3", str(WAN / "wan_ingest.py"), "send", *map(str, packs)],
                       capture_output=True, text=True, timeout=120, env=env)
    if r.returncode:
        raise HTTPException(400, (r.stderr or r.stdout)[-400:])
    cur = (WAN / "resume_packs").read_text().split() if (WAN / "resume_packs").exists() else []
    (WAN / "resume_packs").write_text(" ".join(dict.fromkeys(cur + [str(p) for p in packs])) + "\n")
    return {"ok": True, "output": r.stdout.strip().splitlines(), "skipped": [p for p in q.packs if p not in packs]}


@router.post("/jobs/{jid}/{action}")
def job_action(jid: str, action: str):
    if action not in ("cancel", "retry"):
        raise HTTPException(404)
    return runner("POST", f"/jobs/{jid}/{action}") or {"ok": False}


def comfy_tunnel(enable: bool = True, wait_s: int = 60) -> dict:
    """Start or park the wan-colab-comfy-tunnel unit and optionally wait for Comfy HTTP.

    Phone setup calls this after SETUP_COMPLETE. Missing this function used to mark a
    finished install as failed with a nonsense step string.
    """
    unit = "wan-colab-comfy-tunnel.service"
    paused = WAN / "paused_comfy"
    if enable:
        paused.unlink(missing_ok=True)
        subprocess.run(["sudo", "systemctl", "reset-failed", unit], capture_output=True)
        subprocess.run(["sudo", "systemctl", "restart", unit], capture_output=True)
        deadline = time.time() + max(5, wait_s)
        while time.time() < deadline:
            try:
                r = requests.get("http://127.0.0.1:18288/system_stats", timeout=5)
                if r.status_code == 200:
                    return {"ok": True, "online": True, "http": 200}
            except requests.RequestException:
                pass
            time.sleep(2)
        active = subprocess.run(["systemctl", "is-active", unit], capture_output=True, text=True)
        return {
            "ok": False,
            "online": False,
            "tunnel": (active.stdout or "").strip(),
            "detail": "tunnel restarted but Comfy /system_stats not answering yet",
        }
    stamp = time.strftime("%Y-%m-%d %H:%M") + "\n"
    paused.write_text(stamp)
    subprocess.run(["sudo", "systemctl", "stop", unit], capture_output=True)
    return {"ok": True, "online": False}


@router.post("/comfy-tunnel")
def comfy_tunnel_api(enable: bool = True):
    return comfy_tunnel(enable)


@router.post("/recover")
def recover():
    if subprocess.run(["pgrep", "-f", "colab_recover.sh"], capture_output=True).returncode == 0:
        return {"ok": True, "already_running": True}
    (WAN / "paused").unlink(missing_ok=True)
    (WAN / "paused_comfy").unlink(missing_ok=True)
    subprocess.Popen(["bash", str(WAN / "colab_recover.sh")], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                     start_new_session=True)
    # Bring Comfy tunnel back if systemd unit exists.
    comfy_tunnel(True, wait_s=30)
    return {"ok": True}


@router.post("/stop-runtime")
def stop_runtime():
    r = subprocess.run(["bash", "-c", f"exec 9>/tmp/colab.lock; flock -w 300 9 && {COLAB} stop -s colab"],
                       capture_output=True, text=True, timeout=400)
    _usage["at"] = 0
    _session["at"] = 0
    u = usage()
    sess = session_info()
    still = billing_active(u, sess)
    if r.returncode == 0 and not still:
        stamp = time.strftime("%Y-%m-%d %H:%M") + "\n"
        (WAN / "paused").write_text(stamp)
        (WAN / "paused_comfy").write_text(stamp)
        return {"ok": True, "billing": False, "output": (r.stdout + r.stderr)[-400:]}
    # Stop claimed success but assignment still active — do NOT pretend we stopped.
    msg = (r.stdout + r.stderr)[-400:]
    if still:
        msg = (msg + "\nWARNING: Colab still shows an active assignment — you are still being billed. Try Stop again or stop from colab.research.google.com.").strip()
    return {"ok": False, "billing": still, "usage": u, "instance": sess, "output": msg}
