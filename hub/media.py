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
        try:
            text = JSONL.read_text(encoding="utf-8", errors="replace")
        except OSError:
            return titles
        for line in text.splitlines():
            if not line.strip():
                continue
            try:
                p = json.loads(line)
                titles[int(p["index"])] = p.get("title", "")
            except (ValueError, TypeError, KeyError):
                continue  # one malformed row must not hide the whole library
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
                if isinstance(jobs, dict):
                    jobs = jobs.get("jobs") or []
                if isinstance(jobs, list):
                    titles.update({j["chain_id"]: j.get("name") for j in jobs
                                   if isinstance(j, dict) and j.get("chain_id")})
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


def clip_source(name: str) -> str:
    """Pipeline that produced this clip — used by LTX / Thunder / Vast review tabs."""
    n = name.lower()
    if n.endswith("_14b.mp4") or "/thunder" in n or n.startswith("thunder_"):
        return "thunder"
    if n.startswith("ltx_") or n.startswith("ltx-") or "ltx_chain" in n:
        return "ltx"
    # Vast.ai Remix / Colab 5B farm (goon packs, smoke tests, non-LTX chains)
    return "vast"


def _safe_clip(name: str) -> bool:
    """Reject anything that is not a plain *.mp4 basename inside RENDERS."""
    if not name or not name.endswith(".mp4") or name.startswith("."):
        return False
    return "/" not in name and "\\" not in name


@router.get("/library")
def library():
    groups = {}
    for f in RENDERS.glob("*.mp4"):
        try:
            st = f.stat()
        except OSError:  # deleted between the glob and the stat
            continue
        gid, title, kind, idx = classify(f.name)
        src = clip_source(f.name)
        g = groups.setdefault(gid, {"id": gid, "title": title, "kind": kind, "source": src, "clips": []})
        # Prefer the more specific pipeline when a group mixes names (shouldn't normally)
        if g["source"] != src and src in ("ltx", "thunder"):
            g["source"] = src
        g["clips"].append({
            "name": f.name,
            "idx": idx,
            "mtime": st.st_mtime,
            "mb": round(st.st_size / 1e6, 1),
            "source": src,
        })
    for g in groups.values():
        g["clips"].sort(key=lambda c: (c["idx"] is None, c["idx"] or 0, c["mtime"]))
        g["updated"] = max(c["mtime"] for c in g["clips"])
        g["count"] = len(g["clips"])
    return sorted(groups.values(), key=lambda g: g["updated"], reverse=True)


@router.get("/thumb/{name}")
def thumb(name: str):
    if not _safe_clip(name):
        raise HTTPException(404)
    src = RENDERS / name
    if not src.is_file():
        raise HTTPException(404)
    out = THUMBS / (name + ".jpg")
    if not out.exists() or out.stat().st_mtime < src.stat().st_mtime:
        THUMBS.mkdir(exist_ok=True)
        tmp = out.with_suffix(f".{time.time_ns()}.tmp.jpg")
        with _ffmpeg:
            # Re-check in case another worker already generated it while waiting for semaphore
            if out.exists() and out.stat().st_mtime >= src.stat().st_mtime:
                return FileResponse(out, media_type="image/jpeg", headers={"Cache-Control": "public, max-age=604800, immutable"})
            why = ""
            try:
                for ss in ("1.5", "0"):
                    try:
                        r = subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-ss", ss, "-i", str(src), "-frames:v", "1",
                                            "-vf", "scale=480:-2", "-q:v", "5", str(tmp)], capture_output=True, timeout=20)
                    except FileNotFoundError:
                        raise HTTPException(500, "ffmpeg is not installed on this host")
                    except subprocess.TimeoutExpired:
                        why = "ffmpeg timed out after 20s"
                        tmp.unlink(missing_ok=True)
                        continue
                    except OSError as e:
                        raise HTTPException(500, f"could not run ffmpeg: {e}")
                    if r.returncode == 0 and tmp.exists() and tmp.stat().st_size:
                        tmp.replace(out)
                        break
                    why = (r.stderr or b"").decode(errors="replace").strip()[-200:] or f"ffmpeg exit {r.returncode}"
            finally:
                tmp.unlink(missing_ok=True)
        if not out.exists():
            raise HTTPException(500, f"thumbnail failed: {why or 'ffmpeg produced no frame'}")
    return FileResponse(out, media_type="image/jpeg", headers={"Cache-Control": "public, max-age=604800, immutable"})


CONTACT_COLS = 4
CONTACT_VERSION = 3  # bump when layout/frame count changes so old sheets regenerate


def _contact_frame_count(duration: float) -> int:
    """Dense sampling so agents see motion progression, not a handful of stills."""
    if duration < 6:
        return 12
    if duration < 20:
        return 16
    if duration < 45:
        return 20
    return 24


def _contact_fractions(n: int) -> list[float]:
    if n <= 1:
        return [0.5]
    return [0.04 + i * (0.92 / (n - 1)) for i in range(n)]


def _assemble_contact_grid(frames: list[Path], cols: int, dest: Path) -> None:
    from PIL import Image

    imgs = []
    try:
        for p in frames:
            with Image.open(p) as raw:
                imgs.append(raw.convert("RGB"))
        if not imgs:
            raise ValueError("no frames were extracted")
        w, h = imgs[0].size
        rows = (len(imgs) + cols - 1) // cols
        while len(imgs) < cols * rows:
            imgs.append(imgs[-1].copy())
        sheet = Image.new("RGB", (cols * w, rows * h), (0, 0, 0))
        for i, im in enumerate(imgs):
            if im.size != (w, h):
                im = im.resize((w, h))
            sheet.paste(im, ((i % cols) * w, (i // cols) * h))
        sheet.save(dest, format="JPEG", quality=85, optimize=True)
    finally:
        for im in imgs:
            im.close()


@router.get("/contact/{name}")
def contact_sheet(name: str):
    """Dense contact sheet (8–16 frames) for multimodal agent review of a completed video."""
    if not _safe_clip(name):
        raise HTTPException(404)
    src = RENDERS / name
    if not src.is_file():
        raise HTTPException(404)
    out = THUMBS / (name + f".contact.v{CONTACT_VERSION}.jpg")
    legacy = THUMBS / (name + ".contact.jpg")
    if not out.exists() or out.stat().st_mtime < src.stat().st_mtime:
        THUMBS.mkdir(exist_ok=True)
        try:
            probe = subprocess.run(
                ["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "default=nw=1:nk=1", str(src)],
                capture_output=True, text=True, timeout=30, check=True,
            )
            duration = max(float(probe.stdout.strip()), 0.1)
        except FileNotFoundError:
            raise HTTPException(500, "ffprobe is not installed on this host")
        except subprocess.TimeoutExpired:
            raise HTTPException(500, "ffprobe timed out reading the render duration")
        except subprocess.CalledProcessError as e:
            raise HTTPException(500, "ffprobe could not read the render: "
                                + (e.stderr or "").strip()[-200:])
        except (subprocess.SubprocessError, OSError, ValueError) as e:
            raise HTTPException(500, f"could not inspect render duration: {e}")
        n = _contact_frame_count(duration)
        cols = CONTACT_COLS
        work = THUMBS / f".{name}.contact.v{CONTACT_VERSION}.work"
        work.mkdir(exist_ok=True)
        frames = [work / f"f{i:02d}.jpg" for i in range(n)]
        tmp = out.with_suffix(f".{time.time_ns()}.tmp.jpg")
        with _ffmpeg:
            try:
                for frame, fraction in zip(frames, _contact_fractions(n)):
                    subprocess.run(
                        ["ffmpeg", "-y", "-loglevel", "error", "-ss", f"{duration * fraction:.3f}", "-i", str(src),
                         "-frames:v", "1", "-vf", "scale=320:-2", "-q:v", "5", str(frame)],
                        capture_output=True, timeout=60, check=True,
                    )
                _assemble_contact_grid(frames, cols, tmp)
                tmp.replace(out)
                try:
                    legacy.unlink(missing_ok=True)
                except OSError:
                    pass
            except subprocess.CalledProcessError as e:
                raise HTTPException(500, "contact sheet generation failed: "
                                    + ((e.stderr or "").strip()[-200:] or f"ffmpeg exit {e.returncode}"))
            except (subprocess.SubprocessError, OSError, ValueError, ImportError) as e:
                raise HTTPException(500, f"contact sheet generation failed: {type(e).__name__}: {e}")
            finally:
                tmp.unlink(missing_ok=True)
                for p in work.glob("*"):
                    p.unlink(missing_ok=True)
                try:
                    work.rmdir()
                except OSError:
                    pass
    if not legacy.exists() or legacy.stat().st_mtime < out.stat().st_mtime:
        try:
            legacy.write_bytes(out.read_bytes())
        except OSError:
            pass
    return FileResponse(out, media_type="image/jpeg", headers={"Cache-Control": "public, max-age=604800, immutable"})
