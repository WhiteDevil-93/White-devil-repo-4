"""Thunder Compute screen API: a thin proxy over api.thundercompute.com (what the `tnr` CLI uses).

The token lives only on the relay in ~/.thunder_token. Thunder has no start/stop: instances are
created, modified, snapshotted and deleted.
"""
import json as jsonlib
import logging
import re
import subprocess
import time
import urllib.error
import urllib.request
from pathlib import Path
from typing import Optional

from fastapi import APIRouter, HTTPException
from pydantic import BaseModel

log = logging.getLogger("forge-hub.thunder")
router = APIRouter(prefix="/api/thunder")
BASE = "https://api.thundercompute.com:8443"
TOKEN = Path.home() / ".thunder_token"
_cache = {}
ID = re.compile(r"^[\w-]{1,64}$")


def call(method, path, json=None, timeout=20):
    try:
        token = TOKEN.read_text().strip()
    except FileNotFoundError:
        raise HTTPException(500, "No Thunder token on the relay (~/.thunder_token).")
    req = urllib.request.Request(BASE + path, method=method,
                                 data=jsonlib.dumps(json).encode() if json is not None else None,
                                 headers={"Authorization": f"Bearer {token}", "Content-Type": "application/json",
                                          "User-Agent": "forge-hub"})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            code, text = r.status, r.read().decode(errors="replace")
    except urllib.error.HTTPError as e:
        code, text = e.code, e.read().decode(errors="replace")
    except (urllib.error.URLError, TimeoutError, OSError):
        raise HTTPException(503, "Thunder Compute isn't answering right now.")
    if code in (502, 503, 504, 520, 521, 522, 523, 524):
        raise HTTPException(503, f"Thunder Compute is down (HTTP {code}).")
    if code == 401:
        raise HTTPException(401, "Thunder rejected the token; generate a new one in the console.")
    if code >= 400:
        raise HTTPException(code, (text or "Thunder error")[:400])
    try:
        return jsonlib.loads(text)
    except ValueError:
        return {"text": text}


def cached(path, ttl=3600):
    hit = _cache.get(path)
    if hit and time.time() - hit[0] < ttl:
        return hit[1]
    try:
        data = call("GET", path)
    except HTTPException:
        return hit[1] if hit else None
    _cache[path] = (time.time(), data)
    return data


TARGET = Path.home() / ".thunder_target"


def restart_tunnel(timeout=20):
    """(ok, why) - never claim the tunnel moved when systemctl did not actually restart it."""
    try:
        r = subprocess.run(["sudo", "-n", "systemctl", "restart", "wan-thunder-tunnel"],
                           capture_output=True, text=True, timeout=timeout)
    except subprocess.TimeoutExpired:
        return False, f"systemctl restart did not finish within {timeout}s"
    except OSError as e:
        return False, f"could not run systemctl: {e}"
    if r.returncode != 0:
        return False, (r.stderr or r.stdout or "").strip()[:200] or f"systemctl exit {r.returncode}"
    return True, ""


def follow_instance(instances):
    """Point the ComfyUI tunnel at the running instance if its address changed (same key only)."""
    up = [i for i in instances if str(i.get("status", "")).upper() == "RUNNING" and i.get("ip") and i.get("port")]
    if len(up) != 1:
        return
    want = f"{up[0]['ip']} {up[0]['port']}"
    try:
        have = TARGET.read_text().strip()
    except FileNotFoundError:
        have = ""
    if want != have:
        TARGET.write_text(want + "\n")
        ok, why = restart_tunnel(20)
        if not ok:
            log.warning("thunder tunnel target moved to %s but the restart failed: %s", want, why)


@router.get("/state")
def state():
    inst = call("GET", "/v1/instances/list?update_ips=true")
    instances = [dict(v, id=k) for k, v in inst.items()] if isinstance(inst, dict) else inst
    follow_instance(instances)
    try:
        snaps = call("GET", "/v1/snapshots/list")
    except HTTPException:
        snaps = []
    return {"instances": instances, "snapshots": snaps, "pricing": cached("/v2/pricing"),
            "specs": cached("/v2/specs"), "templates": cached("/v1/thunder-templates"),
            "status": cached("/v2/status", 120)}


class Create(BaseModel):
    gpu_type: str
    num_gpus: int = 1
    cpu_cores: int = 4
    template: str = "base"
    disk_size_gb: int = 100


@router.post("/instances")
def create(c: Create):
    body = c.model_dump(exclude_none=True)
    return call("POST", "/v1/instances/create", body, timeout=60)


def check(i):
    if not ID.match(i):
        raise HTTPException(400, "Bad id")
    return i


@router.post("/instances/{iid}/delete")
def delete(iid: str):
    return call("POST", f"/v1/instances/{check(iid)}/delete", timeout=60)


class Modify(BaseModel):
    cpu_cores: Optional[int] = None
    gpu_type: Optional[str] = None
    num_gpus: Optional[int] = None
    disk_size_gb: Optional[int] = None


@router.post("/instances/{iid}/modify")
def modify(iid: str, m: Modify):
    body = m.model_dump(exclude_none=True)
    if not body:
        raise HTTPException(400, "Nothing to change")
    return call("POST", f"/v1/instances/{check(iid)}/modify", body, timeout=60)


class Ports(BaseModel):
    add_ports: list[int] = []
    remove_ports: list[int] = []


@router.post("/instances/{iid}/ports")
def ports(iid: str, p: Ports):
    return call("PATCH", f"/v1/instances/{check(iid)}/ports", p.model_dump())


class Snap(BaseModel):
    instance_id: str
    name: str


@router.post("/snapshots")
def snapshot(s: Snap):
    return call("POST", "/v1/snapshots/create", {"instanceId": check(s.instance_id), "name": s.name[:60]}, timeout=60)


@router.post("/snapshots/{sid}/delete")
def snapshot_delete(sid: str):
    return call("DELETE", f"/v1/snapshots/{check(sid)}")


WANBOT14 = "http://127.0.0.1:18901"


def wanbot14(method, path):
    try:
        token = (Path.home() / ".wanbot_token").read_text().strip()
    except FileNotFoundError:
        raise HTTPException(503, "The 14B runner on the relay isn't running.")
    req = urllib.request.Request(WANBOT14 + path, method=method, headers={
        "Authorization": "Bearer " + token})
    try:
        with urllib.request.urlopen(req, timeout=15) as r:
            return jsonlib.loads(r.read() or b"null")
    except urllib.error.HTTPError as e:
        raise HTTPException(e.code, e.read().decode(errors="replace")[:300])
    except (urllib.error.URLError, TimeoutError, OSError):
        raise HTTPException(503, "The 14B runner on the relay isn't running.")


def comfy_online():
    try:
        with urllib.request.urlopen("http://127.0.0.1:18188/queue", timeout=5) as r:
            q = jsonlib.loads(r.read())
    except TimeoutError:
        return {"online": False, "why": "ComfyUI did not answer within 5s (busy or wedged, not stopped)"}
    except urllib.error.HTTPError as e:
        return {"online": False, "why": f"ComfyUI answered HTTP {e.code}"}
    except (urllib.error.URLError, OSError) as e:
        return {"online": False, "why": f"ComfyUI is not reachable: {getattr(e, 'reason', e)}"}
    except ValueError as e:
        return {"online": False, "why": f"ComfyUI sent a non-JSON queue: {e}"}
    try:
        prefixes = [v["inputs"].get("filename_prefix", "") for it in q.get("queue_running", [])
                    for v in it[2].values() if v.get("class_type", "").startswith("Save")]
    except (AttributeError, IndexError, KeyError, TypeError):
        prefixes = []
    return {"online": True, "running": len(q.get("queue_running", [])), "pending": len(q.get("queue_pending", [])),
            "running_prefix": prefixes[0] if prefixes else ""}


@router.get("/queue")
def queue14():
    try:
        jobs = wanbot14("GET", "/jobs")
        jobs = jobs.get("jobs", jobs) if isinstance(jobs, dict) else jobs
        if not isinstance(jobs, list):
            raise HTTPException(502, "The 14B runner sent an unexpected job list.")
        jobs = [j for j in jobs if isinstance(j, dict)]
        up = True
    except HTTPException:
        jobs, up = [], False
    ended = ("done", "cancelled", "error")
    active = sorted((j for j in jobs if j.get("status") not in ended),
                    key=lambda j: (j.get("status") != "rendering", j.get("created") or ""))
    past = sorted((j for j in jobs if j.get("status") in ended), key=lambda j: j.get("created") or "", reverse=True)
    for j in active + past:
        j.pop("log", None)
    return {"runner": up, "comfy": comfy_online(), "jobs": active + past[:15]}


class Submit(BaseModel):
    spec: dict
    name: Optional[str] = None
    seed: Optional[int] = None
    first: bool = False


@router.post("/queue")
def submit14(s: Submit):
    spec = dict(s.spec)
    if spec.get("type") not in ("chain", "chain_part") or not spec.get("clips"):
        raise HTTPException(400, "That file isn't a generator chain (no clips).")
    runner = dict(spec.get("runner") or {})
    runner["model"] = "wan22-14b-i2v"
    if s.name:
        runner["name"] = s.name[:80]
    if s.seed is not None:
        runner["seed"] = s.seed
    spec["runner"] = runner
    try:
        token = (Path.home() / ".wanbot_token").read_text().strip()
    except FileNotFoundError:
        raise HTTPException(503, "The 14B runner on the relay isn't running.")
    ahead = []
    if s.first:
        jobs = wanbot14("GET", "/jobs")
        jobs = jobs.get("jobs", jobs) if isinstance(jobs, dict) else jobs
        if not isinstance(jobs, list):
            raise HTTPException(502, "The 14B runner sent an unexpected job list.")
        jobs = [j for j in jobs if isinstance(j, dict) and j.get("id")]
        ahead = [j["id"] for j in sorted(jobs, key=lambda j: j.get("created") or "") if j.get("status") == "queued"]
        for jid in ahead:
            wanbot14("POST", f"/jobs/{jid}/cancel")
    req = urllib.request.Request(WANBOT14 + "/jobs", method="POST", data=jsonlib.dumps(spec).encode(),
                                 headers={"Authorization": "Bearer " + token, "Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            job = jsonlib.loads(r.read())
    except urllib.error.HTTPError as e:
        raise HTTPException(e.code, e.read().decode(errors="replace")[:300])
    except (urllib.error.URLError, TimeoutError, OSError):
        raise HTTPException(503, "The 14B runner on the relay isn't running.")
    finally:
        stuck = []
        for jid in ahead:
            try:
                wanbot14("POST", f"/jobs/{jid}/retry")
            except HTTPException as e:
                stuck.append(f"{jid}: {e.detail}")
    res = {"ok": True, "id": job.get("id") if isinstance(job, dict) else None,
           "clips": len(spec["clips"]), "requeued": len(ahead) - len(stuck)}
    if stuck:
        # The submit itself succeeded; say plainly which jobs are still cancelled.
        res["ok"] = False
        res["warning"] = ("Your job was queued, but these jobs it jumped ahead of could not be put back "
                          "and are still cancelled: " + "; ".join(stuck)[:400])
    return res


@router.post("/queue/{jid}/{action}")
def queue14_action(jid: str, action: str):
    if action not in ("cancel", "retry"):
        raise HTTPException(400)
    return wanbot14("POST", f"/jobs/{check(jid)}/{action}")
