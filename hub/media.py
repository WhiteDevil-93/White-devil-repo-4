"""Media API: clip library grouped by pack/chain, and thumbnails made on the relay."""
import json
import re
import subprocess
import threading
import time
from pathlib import Path

import requests
from fastapi import APIRouter, HTTPException
from fastapi.responses import FileResponse

WAN = Path.home() / "wan"
RENDERS = WAN / "renders"
THUMBS = WAN / "thumbs"
JSONL = WAN / "gooning_chains" / "data" / "gooning_chains.jsonl"
RUNNERS = ("http://127.0.0.1:18900", "http://127.0.0.1:18901")

router = APIRouter(prefix="/api/media")
_ffmpeg = threading.Semaphore(2)
_cache = {"packs": (0, {}), "chains": (0, {})}

GOON = re.compile(r"^smoke_goon_p(\d+)_c(\d+)_")
CHAIN = re.compile(r"^smoke_(.+?)_c(\d+)_(?:wanbot|14b)\.mp4$")
KEEP = re.compile(r"^(KEEPER|NICE)_")


def pack_titles():
    at, titles = _cache["packs"]
    try:
        m = JSONL.stat().st_mtime
    except FileNotFoundError:
        return {}
    if m != at:
        titles = {}
        for line in JSONL.read_text(encoding="utf-8").splitlines():
            if line.strip():
                p = json.loads(line)
                titles[int(p["index"])] = p.get("title", "")
        _cache["packs"] = (m, titles)
    return titles


def chain_titles():
    at, titles = _cache["chains"]
    if time.time() - at > 60:
        try:
            tok = (Path.home() / ".wanbot_token").read_text().strip()
        except FileNotFoundError:
            tok = ""
        for url in RUNNERS:
            try:
                jobs = requests.get(url + "/jobs", headers={"Authorization": f"Bearer {tok}"}, timeout=5).json()
                titles.update({j["chain_id"]: j.get("name") for j in jobs if j.get("chain_id")})
            except (requests.RequestException, OSError, ValueError):
                pass
        _cache["chains"] = (time.time(), titles)
    return titles


def pretty(s):
    return re.sub(r"[_-]+", " ", s).strip().title()


def classify(name):
    m = GOON.match(name)
    if m:
        n = int(m[1])
        title = pack_titles().get(n) or ""
        return f"goon-p{n:02d}", f"Pack {n}" + (f" · {title}" if title else ""), "pack", int(m[2])
    m = CHAIN.match(name)
    if m:
        return m[1], pretty(chain_titles().get(m[1]) or m[1]), "chain", int(m[2])
    if KEEP.match(name):
        return "keepers", "Keepers", "keeper", None
    return "tests", "Tests & experiments", "test", None


@router.get("/library")
def library():
    groups = {}
    for f in RENDERS.glob("*.mp4"):
        st = f.stat()
        gid, title, kind, idx = classify(f.name)
        g = groups.setdefault(gid, {"id": gid, "title": title, "kind": kind, "source": "colab", "clips": []})
        if f.name.endswith("_14b.mp4"):
            g["source"] = "thunder"
        g["clips"].append({"name": f.name, "idx": idx, "mtime": st.st_mtime, "mb": round(st.st_size / 1e6, 1)})
    for g in groups.values():
        g["clips"].sort(key=lambda c: (c["idx"] is None, c["idx"] or 0, c["mtime"]))
        g["updated"] = max(c["mtime"] for c in g["clips"])
        g["count"] = len(g["clips"])
    return sorted(groups.values(), key=lambda g: g["updated"], reverse=True)


@router.get("/thumb/{name}")
def thumb(name: str):
    src = RENDERS / name
    if "/" in name or not name.endswith(".mp4") or not src.is_file():
        raise HTTPException(404)
    out = THUMBS / (name + ".jpg")
    if not out.exists() or out.stat().st_mtime < src.stat().st_mtime:
        THUMBS.mkdir(exist_ok=True)
        tmp = out.with_suffix(".tmp.jpg")
        with _ffmpeg:
            for ss in ("1.5", "0"):
                r = subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-ss", ss, "-i", str(src), "-frames:v", "1",
                                    "-vf", "scale=480:-2", "-q:v", "5", str(tmp)], capture_output=True, timeout=60)
                if r.returncode == 0 and tmp.exists() and tmp.stat().st_size:
                    tmp.replace(out)
                    break
        if not out.exists():
            raise HTTPException(500, "thumbnail failed")
    return FileResponse(out, media_type="image/jpeg", headers={"Cache-Control": "public, max-age=604800"})
