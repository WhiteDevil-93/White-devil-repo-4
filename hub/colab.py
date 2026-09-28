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


@router.get("/state")
def state():
    health = runner("GET", "/health")
    jobs = runner("GET", "/jobs") if health else None
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
    return {
        "runner_mode": (WAN / "runner_mode").read_text().strip() if (WAN / "runner_mode").exists() else None,
        "runner_online": bool(health),
        "paused": (WAN / "paused").read_text().strip() if (WAN / "paused").exists() else None,
        "gpu": (health or {}).get("gpu"),
        "jobs": view,
        "heartbeat": hb_status(),
        "usage": usage(),
        "recover_running": subprocess.run(["pgrep", "-f", "colab_recover.sh"], capture_output=True).returncode == 0,
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


@router.post("/recover")
def recover():
    if subprocess.run(["pgrep", "-f", "colab_recover.sh"], capture_output=True).returncode == 0:
        return {"ok": True, "already_running": True}
    (WAN / "paused").unlink(missing_ok=True)
    subprocess.Popen(["bash", str(WAN / "colab_recover.sh")], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                     start_new_session=True)
    return {"ok": True}


@router.post("/stop-runtime")
def stop_runtime():
    r = subprocess.run(["bash", "-c", f"exec 9>/tmp/colab.lock; flock -w 300 9 && {COLAB} stop -s colab"],
                       capture_output=True, text=True, timeout=400)
    if r.returncode == 0:
        (WAN / "paused").write_text(time.strftime("%Y-%m-%d %H:%M") + "\n")
    _usage["at"] = 0
    return {"ok": r.returncode == 0, "output": (r.stdout + r.stderr)[-400:]}
