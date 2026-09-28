"""HypnoForge screen API: runs the owner's `hf` CLI on the laptop over `ssh laptop` (never edits the app).

Quick commands (lists, search, chat) run synchronously. Renders, queues, caption packs, AI assist and
ingest run detached on the laptop (nohup) with a log in ~/.hf_jobs/<id>.log, so they survive the
relay connection dropping.
"""
import json
import re
import shlex
import subprocess
import time
import uuid
from pathlib import Path
from typing import Optional

from fastapi import APIRouter, HTTPException
from pydantic import BaseModel

router = APIRouter(prefix="/api/laptop/hypno")
JOBS = Path.home() / "hub" / "hf_jobs.json"
PROJECT_DIRS = ["/mnt/a/HypnoForge/projects", "/mnt/a/HypnoForge/renders/projects"]
RENDER_DIRS = {"completed": "/mnt/a/HypnoForge/renders/completed", "previews": "/mnt/a/HypnoForge/renders/previews"}
STYLES = ["commanding_hypno", "soft_trance", "filthy_short", "degrading_filth", "good_boy_trainer", "denial_edge",
          "obsessive_loop", "identity_overwrite", "devotional_worship", "seductive_corruption", "primal_need",
          "ritual_conditioning", "playful_tease", "cold_reprogramming"]
ASSIST_MODES = ["rewrite", "expand", "project_preset", "pmv_notes", "polish_tts", "diagnose", "pack_metadata"]
_cache = {"at": 0, "data": None}
NOISE = re.compile(r"^\d{4}-\d\d-\d\d \d\d:\d\d:\d\d \[(INFO|DEBUG)\]")


def ssh(cmd, timeout=60, stdin=None):
    try:
        r = subprocess.run(["ssh", "-o", "ConnectTimeout=8", "-o", "BatchMode=yes", "laptop", cmd],
                           capture_output=True, timeout=timeout, input=stdin.encode() if stdin else None)
    except subprocess.TimeoutExpired:
        raise HTTPException(504, "The laptop took too long to answer.")
    if r.returncode == 255:
        raise HTTPException(503, "Laptop is offline (asleep, or WSL not running).")
    r.stdout, r.stderr = decode(r.stdout), decode(r.stderr)
    return r


def decode(b):
    # hf prints some Windows-1252 dashes from its data files
    return b.decode("utf-8", errors="replace").replace("\ufffd", "—")


def hf(args, timeout=90):
    r = ssh("hf " + shlex.join(args) + " < /dev/null", timeout)
    lines = [l for l in (r.stdout + r.stderr).splitlines() if not NOISE.match(l)]
    return r.returncode, "\n".join(lines).strip()


def load_jobs():
    try:
        return json.loads(JOBS.read_text())
    except (FileNotFoundError, ValueError):
        return []


def save_jobs(jobs):
    JOBS.write_text(json.dumps(jobs[-60:], indent=1))


OVERVIEW = r"""
import json, os, glob
out = {"projects": [], "renders": []}
for d in %s:
    for p in glob.glob(os.path.join(d, "*.json")):
        out["projects"].append({"path": p, "name": os.path.basename(p)[:-5], "mtime": os.path.getmtime(p)})
for kind, d in %s.items():
    for p in glob.glob(os.path.join(d, "*.mp4")):
        st = os.stat(p)
        out["renders"].append({"name": os.path.basename(p), "kind": kind, "mtime": st.st_mtime, "mb": round(st.st_size / 1e6, 1)})
out["projects"].sort(key=lambda x: -x["mtime"]); out["renders"].sort(key=lambda x: -x["mtime"])
out["renders"] = out["renders"][:80]
print(json.dumps(out))
"""


@router.get("/overview")
def overview(fresh: bool = False):
    if not fresh and _cache["data"] and time.time() - _cache["at"] < 60:
        return _cache["data"]
    r = ssh("python3 -", 40, stdin=OVERVIEW % (json.dumps(PROJECT_DIRS), json.dumps(RENDER_DIRS)))
    try:
        data = json.loads(r.stdout.strip().splitlines()[-1])
    except (ValueError, IndexError):
        raise HTTPException(502, "Could not read HypnoForge folders: " + (r.stderr or r.stdout)[-300:])
    _, presets = hf(["presets", "--list"])
    _, kits = hf(["kits", "--list"])
    _, themes = hf(["themes"])
    data["presets"] = list(dict.fromkeys(l.strip() for l in presets.splitlines()
                                         if l.strip().startswith(("Visual - ", "Utility - "))))
    data["kits"] = [l.strip() for l in kits.splitlines() if l.strip().startswith("Kit")]
    data["themes"] = [dict(zip(("id", "name", "hint"), [x.strip() for x in l.split("|", 2)]))
                      for l in themes.splitlines() if l.count("|") >= 2]
    data["styles"], data["assist_modes"] = STYLES, ASSIST_MODES
    data["video_base"] = "/laptop/hfvid"
    _cache.update(at=time.time(), data=data)
    return data


class Render(BaseModel):
    project: str
    preview: bool = False
    draft: bool = False
    audience: Optional[str] = None
    mode: Optional[str] = None
    no_tts: bool = False
    preset: Optional[str] = None
    kit: Optional[str] = None


class Queue(BaseModel):
    projects: list[str]
    preview: bool = False
    draft: bool = False


class Captions(BaseModel):
    theme: str
    style: str = "filthy_short"
    name: Optional[str] = None
    lines: Optional[int] = None


class Assist(BaseModel):
    mode: str
    theme: Optional[str] = None
    save: Optional[str] = None


class Ingest(BaseModel):
    urls: list[str]
    first_only: bool = False
    queue_ai: bool = False


def check_project(p):
    if not p.endswith(".json") or ".." in p or not any(p.startswith(d + "/") for d in PROJECT_DIRS):
        raise HTTPException(400, "Unknown project")
    return p


def start_job(kind, title, args, pre=None):
    jid = time.strftime("%Y%m%d-%H%M%S-") + uuid.uuid4().hex[:4]
    steps = [shlex.join(["hf"] + a) for a in (pre or [])] + [shlex.join(["hf"] + args)]
    script = " && ".join(steps)
    cmd = (f"mkdir -p ~/.hf_jobs && nohup bash -c {shlex.quote(script + '; echo __RC=$?')} "
           f"> ~/.hf_jobs/{jid}.log 2>&1 < /dev/null & echo started")
    r = ssh(cmd, 30)
    if "started" not in r.stdout:
        raise HTTPException(502, "Could not start the job on the laptop: " + r.stderr[-300:])
    jobs = load_jobs()
    jobs.append({"id": jid, "kind": kind, "title": title, "created": time.time(), "status": "running"})
    save_jobs(jobs)
    return {"ok": True, "id": jid}


@router.post("/render")
def render(r: Render):
    p = check_project(r.project)
    pre = []
    if r.preset:
        pre.append(["presets", "--apply", r.preset, "--project", p])
    if r.kit:
        pre.append(["kits", "--apply", r.kit, "--project", p])
    args = ["render", p]
    if r.preview:
        args.append("--preview")
    if r.draft:
        args.append("--draft")
    if r.audience in ("gay", "bi", "straight", "trans"):
        args += ["--audience", r.audience]
    if r.mode in ("normal", "pmv"):
        args += ["--mode", r.mode]
    if r.no_tts:
        args.append("--no-tts")
    return start_job("render", Path(p).stem + (" (preview)" if r.preview else ""), args, pre)


@router.post("/queue")
def queue(q: Queue):
    ps = [check_project(p) for p in q.projects]
    if not ps:
        raise HTTPException(400, "Pick at least one project")
    args = ["queue", *ps] + (["--preview"] if q.preview else []) + (["--draft"] if q.draft else [])
    return start_job("queue", f"Queue of {len(ps)}", args)


@router.post("/captions")
def captions(c: Captions):
    if c.style not in STYLES:
        raise HTTPException(400, "Unknown style")
    args = ["generate-captions", "--theme", c.theme[:300], "--style", c.style]
    if c.name:
        args += ["--name", c.name[:80]]
    if c.lines:
        args += ["--lines", str(max(5, min(c.lines, 300)))]
    return start_job("captions", "Captions: " + c.theme[:40], args)


@router.post("/assist")
def assist(a: Assist):
    if a.mode not in ASSIST_MODES:
        raise HTTPException(400, "Unknown mode")
    args = ["ai-assist", "--mode", a.mode]
    if a.theme:
        args += ["--theme", a.theme[:300]]
    if a.save:
        args += ["--save", a.save[:80]]
    return start_job("assist", f"AI assist: {a.mode}", args)


@router.post("/ingest")
def ingest(i: Ingest):
    urls = [u.strip() for u in i.urls if re.match(r"^https?://\S+$", u.strip())]
    if not urls:
        raise HTTPException(400, "Add at least one http(s) URL")
    args = ["ingest", "url", *urls] + (["--first-only"] if i.first_only else []) + (["--queue-ai"] if i.queue_ai else [])
    return start_job("ingest", f"Ingest {len(urls)} URL(s)", args)


@router.get("/jobs")
def jobs():
    js = load_jobs()
    running = [j for j in js if j["status"] == "running"]
    if running:
        ids = " ".join(shlex.quote(j["id"]) for j in running)
        r = ssh(f"cd ~/.hf_jobs 2>/dev/null && for i in {ids}; do echo \"$i $(grep -ao '__RC=[0-9]*' $i.log | tail -1)\"; done", 30)
        for line in r.stdout.splitlines():
            jid, _, rc = line.partition(" ")
            for j in js:
                if j["id"] == jid and rc.startswith("__RC="):
                    j["status"] = "done" if rc == "__RC=0" else "failed"
                    j["finished"] = time.time()
        save_jobs(js)
        _cache["at"] = 0
    return list(reversed(js))


@router.get("/jobs/{jid}")
def job_log(jid: str, lines: int = 60):
    if not re.match(r"^[\w-]+$", jid):
        raise HTTPException(400)
    r = ssh(f"tail -n {max(5, min(lines, 400))} ~/.hf_jobs/{jid}.log", 30)
    return {"log": "\n".join(l for l in r.stdout.splitlines() if not NOISE.match(l))}


class Chat(BaseModel):
    message: str


@router.post("/chat")
def chat(c: Chat):
    rc, out = hf(["chat", c.message[:2000]], timeout=240)
    return {"ok": rc == 0, "reply": out}


@router.get("/library")
def library(q: str = "", kind: Optional[str] = None, limit: int = 40):
    args = ["library", "search"] + ([q] if q else []) + ["--limit", str(max(1, min(limit, 200)))]
    if kind in ("image", "video", "audio"):
        args += ["--kind", kind]
    rc, out = hf(args, timeout=60)
    return {"ok": rc == 0, "output": out}


@router.get("/info")
def info():
    rc, out = hf(["info"], timeout=60)
    return {"ok": rc == 0, "output": out}
