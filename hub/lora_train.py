"""LoRA training for LTX-2.5: build a dataset on the relay, caption it, train it on the Colab G4, pull the LoRA back.

Flow (phone, laptop app or web):
  POST /api/loratrain/datasets                 {name, kind: character|motion, trigger}
  POST /api/loratrain/datasets/{id}/files      images and clips (multipart, several at once)
  POST /api/loratrain/datasets/{id}/caption    auto-captions every uncaptioned item (OpenRouter vision, background)
  PUT  /api/loratrain/datasets/{id}/captions   {file: caption} edits
  GET  /api/loratrain/datasets/{id}            items, captions, readiness checks and a time/cost estimate
  POST /api/loratrain/datasets/{id}/train      uploads to Colab, runs recipes/ltxtrain/train.sh, polls it
  GET  /api/loratrain/runs/{rid}               stage, log tail, pulled checkpoints (size-verified)
  POST /api/loratrain/runs/{rid}/cancel

Numbers (dataset sizes, steps, rank) follow Lightricks' LTX-2.5 character LoRA guide and ltx-trainer configs; the
time estimate is an ESTIMATE until a real run on the G4 measures it (see ESTIMATE_S_PER_STEP).
Datasets live in ~/hub/lora_datasets/<id>/, runs in ~/hub/lora_runs/<rid>.json, trained LoRAs in ~/wan/lora_trained/.
"""
import base64
import json
import re
import secrets
import shlex
import shutil
import subprocess
import tempfile
import threading
import time
from pathlib import Path
from typing import Dict, List, Optional

from fastapi import APIRouter, File, HTTPException, UploadFile
from fastapi.responses import FileResponse
from pydantic import BaseModel

router = APIRouter(prefix="/api/loratrain")
HUB = Path(__file__).resolve().parent
DATASETS = HUB / "lora_datasets"
RUNS = HUB / "lora_runs"
TRAINED = Path.home() / "wan" / "lora_trained"
SCRIPT = HUB / "recipes" / "ltxtrain" / "train.sh"
COLAB = str(Path.home() / ".local/bin/colab")
REMOTE_BASE = "/content/workspace"
HF_REPO = "WhiteDevil6969/forge-loras"

IMAGE = {".jpg", ".jpeg", ".png", ".webp"}
VIDEO = {".mp4", ".mov", ".webm", ".mkv"}
MAX_FILE = 300 * 1024 * 1024
MAX_FILES = 200
ID = re.compile(r"^[0-9a-f]{12}$")
SAFE = re.compile(r"[^A-Za-z0-9_.-]+")
ACTIVE = {"queued", "uploading", "training"}

KINDS = {
    # Lightricks' 2.5 character guide: ~2000 steps, rank 32, lr 1e-4, i2v first_frame at 0.5, stills allowed (F=1),
    # add the feed-forward layers for identity.
    "character": {"label": "Character / identity", "steps": 2000, "rank": 32, "lr": "1e-4", "ff": True,
                  "buckets": "768x448x1;448x768x1;768x448x49;448x768x49",
                  "min_items": 20, "recommended": "25-40 items: stills and short clips of the same person, "
                  "varied angles, lighting, outfits and backgrounds"},
    # Motion must be video; longer clips for motion (Lightricks: "longer clips (e.g. 121 frames)").
    "motion": {"label": "Motion / concept", "steps": 3000, "rank": 32, "lr": "1e-4", "ff": False,
               "buckets": "768x448x49;448x768x49;768x448x89;448x768x89",
               "min_items": 15, "recommended": "30-50 clips of 4-5 seconds showing the motion, with different "
               "people so no single face is learned"},
}
# ESTIMATE, not measured on the G4: community figures are 0.67 s/step (L40S, 512px) and ~1.1 s/step (RTX 5090,
# images). 768x448 clips cost more per step. Replace with the first real run's numbers.
ESTIMATE_S_PER_STEP = {"image": 0.8, "video": 2.0}
SETUP_MIN = {"install": 10, "dev_download": 15, "per_item_encode_s": 20}

_lock = threading.Lock()
_threads: Dict[str, threading.Thread] = {}


def now():
    return time.time()


def write_json(path: Path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(".tmp")
    tmp.write_text(json.dumps(data, indent=1), encoding="utf-8")
    tmp.replace(path)


def ds_dir(did):
    if not ID.match(did or ""):
        raise HTTPException(404, "No such dataset.")
    d = DATASETS / did
    if not (d / "dataset.json").is_file():
        raise HTTPException(404, "No such dataset.")
    return d


def load_ds(did):
    return json.loads((ds_dir(did) / "dataset.json").read_text(encoding="utf-8"))


def save_ds(ds):
    ds["updated"] = now()
    write_json(DATASETS / ds["id"] / "dataset.json", ds)


def lora_name(name):
    n = SAFE.sub("_", (name or "").strip()).strip("._")[:60]
    return n or "my_lora"


def media_kind(fname):
    ext = Path(fname).suffix.lower()
    return "image" if ext in IMAGE else "video" if ext in VIDEO else None


def checks(ds):
    """Readiness, as (ready, problems, warnings). Problems block training; warnings don't."""
    k = KINDS[ds["kind"]]
    items = ds["items"]
    vids = [i for i in items if i["type"] == "video"]
    problems, warnings = [], []
    if len(items) < k["min_items"]:
        problems.append(f"{len(items)} items; a {k['label'].lower()} LoRA needs at least {k['min_items']} "
                        f"(recommended {k['recommended']}).")
    if ds["kind"] == "motion" and len(vids) < k["min_items"]:
        problems.append(f"Motion is learned from video: {len(vids)} clips, need at least {k['min_items']}.")
    missing = [i["file"] for i in items if not (i.get("caption") or "").strip()]
    if missing:
        problems.append(f"{len(missing)} items have no caption (run Auto-caption or write them).")
    if ds["kind"] == "character" and not (ds.get("trigger") or "").strip():
        problems.append("A character LoRA needs a trigger word (a made-up word such as ohwx_man).")
    if ds["kind"] == "character":
        warnings.append("A LoRA of a real person needs that person's consent (LTX-2.x licence: no impersonation "
                        "without consent).")
        if items and not vids:
            warnings.append("Stills only: identity trains, but adding 5+ short clips helps it hold in motion.")
    return not problems, problems, warnings


def estimate(ds, rate_per_hr=None, dev_on_colab=False):
    k = KINDS[ds["kind"]]
    steps = int(ds.get("settings", {}).get("steps") or k["steps"])
    n = len(ds["items"]) or 1
    share_video = sum(1 for i in ds["items"] if i["type"] == "video") / n
    s_step = ESTIMATE_S_PER_STEP["video"] * share_video + ESTIMATE_S_PER_STEP["image"] * (1 - share_video)
    minutes = (SETUP_MIN["install"] + (0 if dev_on_colab else SETUP_MIN["dev_download"])
               + n * SETUP_MIN["per_item_encode_s"] / 60 + steps * s_step / 60)
    out = {"minutes": round(minutes), "steps": steps, "seconds_per_step": round(s_step, 2), "label": "ESTIMATE"}
    if isinstance(rate_per_hr, (int, float)):
        out["cost_usd"] = round(minutes / 60 * rate_per_hr, 2)
        out["rate_per_hr"] = rate_per_hr
    return out


def colab_rate():
    try:
        import colab as colab_api
        return colab_api.usage().get("rate_per_hr")
    except Exception:
        return None


def view(ds, rate=None):
    ready, problems, warnings = checks(ds)
    out = dict(ds)
    out.update(ready=ready, problems=problems, warnings=warnings, kind_info=KINDS[ds["kind"]],
               estimate=estimate(ds, rate),
               counts={"images": sum(1 for i in ds["items"] if i["type"] == "image"),
                       "videos": sum(1 for i in ds["items"] if i["type"] == "video")})
    return out


class NewDataset(BaseModel):
    name: str
    kind: str = "character"
    trigger: Optional[str] = None


@router.get("/kinds")
def kinds():
    return KINDS


@router.get("/datasets")
def list_datasets():
    out = []
    for f in sorted(DATASETS.glob("*/dataset.json"), key=lambda p: p.stat().st_mtime, reverse=True):
        try:
            ds = json.loads(f.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            continue
        ready, problems, _ = checks(ds)
        out.append({"id": ds["id"], "name": ds["name"], "kind": ds["kind"], "items": len(ds["items"]),
                    "ready": ready, "problems": problems, "updated": ds.get("updated")})
    return out


@router.post("/datasets")
def create_dataset(n: NewDataset):
    if n.kind not in KINDS:
        raise HTTPException(400, "Kind must be character or motion.")
    if not n.name.strip():
        raise HTTPException(400, "Give the LoRA a name.")
    did = secrets.token_hex(6)
    trigger = SAFE.sub("_", (n.trigger or "").strip())[:40] or None
    ds = {"id": did, "name": n.name.strip()[:80], "lora": lora_name(n.name), "kind": n.kind, "trigger": trigger,
          "items": [], "created": now(), "settings": {}, "captioning": None}
    (DATASETS / did / "media").mkdir(parents=True)
    save_ds(ds)
    return view(ds)


@router.get("/datasets/{did}")
def get_dataset(did: str):
    return view(load_ds(did), colab_rate())


class DatasetPatch(BaseModel):
    name: Optional[str] = None
    trigger: Optional[str] = None
    steps: Optional[int] = None
    rank: Optional[int] = None


@router.patch("/datasets/{did}")
def patch_dataset(did: str, p: DatasetPatch):
    with _lock:
        ds = load_ds(did)
        if p.name and p.name.strip():
            ds["name"], ds["lora"] = p.name.strip()[:80], lora_name(p.name)
        if p.trigger is not None:
            ds["trigger"] = SAFE.sub("_", p.trigger.strip())[:40] or None
        if p.steps is not None:
            ds["settings"]["steps"] = max(250, min(8000, int(p.steps)))
        if p.rank is not None:
            if p.rank not in (8, 16, 32, 64, 128):
                raise HTTPException(400, "Rank must be 8, 16, 32, 64 or 128.")
            ds["settings"]["rank"] = p.rank
        save_ds(ds)
    return view(ds)


@router.delete("/datasets/{did}")
def delete_dataset(did: str):
    d = ds_dir(did)
    if any(r.get("dataset") == did and r.get("status") in ACTIVE for r in all_runs()):
        raise HTTPException(409, "That dataset is training; cancel the run first.")
    shutil.rmtree(d)
    return {"deleted": did}


@router.post("/datasets/{did}/files")
async def add_files(did: str, files: List[UploadFile] = File(...)):
    d = ds_dir(did)
    added, skipped = [], []
    for up in files:
        kind = media_kind(up.filename or "")
        if not kind:
            skipped.append(f"{up.filename}: not an image (jpg/png/webp) or clip (mp4/mov/webm/mkv)")
            continue
        base = SAFE.sub("_", Path(up.filename).stem)[:50] or "item"
        fname = f"{base}_{secrets.token_hex(3)}{Path(up.filename).suffix.lower()}"
        dst = d / "media" / fname
        size = 0
        with dst.open("wb") as fh:
            while chunk := await up.read(1024 * 1024):
                size += len(chunk)
                if size > MAX_FILE:
                    break
                fh.write(chunk)
        if size > MAX_FILE:
            dst.unlink(missing_ok=True)
            skipped.append(f"{up.filename}: over {MAX_FILE // (1024 * 1024)} MB")
            continue
        added.append({"file": fname, "type": kind, "size": size, "caption": "", "source": up.filename})
    with _lock:
        ds = load_ds(did)
        room = max(0, MAX_FILES - len(ds["items"]))
        for a in added[room:]:
            (d / "media" / a["file"]).unlink(missing_ok=True)
            skipped.append(f"{a['source']}: the dataset is full ({MAX_FILES} items)")
        ds["items"].extend(added[:max(0, room)])
        save_ds(ds)
    return {"added": len(added[:max(0, room)]), "skipped": skipped, "dataset": view(ds)}


@router.get("/datasets/{did}/files/{fname}")
def get_file(did: str, fname: str):
    d = ds_dir(did)
    f = d / "media" / Path(fname).name
    if not f.is_file():
        raise HTTPException(404, "No such file.")
    return FileResponse(f)


@router.delete("/datasets/{did}/files/{fname}")
def delete_file(did: str, fname: str):
    with _lock:
        ds = load_ds(did)
        ds["items"] = [i for i in ds["items"] if i["file"] != fname]
        (ds_dir(did) / "media" / Path(fname).name).unlink(missing_ok=True)
        save_ds(ds)
    return view(ds)


@router.put("/datasets/{did}/captions")
def put_captions(did: str, captions: Dict[str, str]):
    with _lock:
        ds = load_ds(did)
        for i in ds["items"]:
            if i["file"] in captions:
                i["caption"] = " ".join(str(captions[i["file"]]).split())[:2000]
                i["caption_by"] = "you"
        save_ds(ds)
    return view(ds)


CAPTION_SYSTEM = """You write training captions for a video LoRA (LTX-2.5). Write ONE paragraph, {words} words, present
tense, plain visual description. No audio, sound or speech words (the clips are silent). No opinions, no "the image
shows", no lists, no quotes. Everyone shown is an adult; never describe anyone as young-looking, a teen or a child.
Use plain anatomical words for nudity or sex.
{rules}
Reply with the caption only."""
CAPTION_RULES = {
    "character": """This is a CHARACTER LoRA: the same man appears in every example and the LoRA must learn his identity
from the pixels. Call him "the man". Describe what CHANGES between examples: framing and camera angle, pose and
action, clothing, setting and background, lighting. Do NOT describe his fixed identity (face shape, eyes, hair colour
or style, skin tone, body build, tattoos, distinctive marks): words for those would take the identity away from the
LoRA.""",
    "motion": """This is a MOTION LoRA: it must learn the movement. Describe the motion completely, in order: who moves,
which body part, the direction, speed and contact, and the result; then the camera (static, pan, push-in). Describe
the people only briefly and generically (an adult man, a muscular man) since the subjects vary.""",
}


def frames_of(path: Path, kind: str):
    """Data URLs for the model: the image itself, or three frames (10%, 50%, 90%) of a clip."""
    if kind == "image":
        mime = {".png": "png", ".webp": "webp"}.get(path.suffix.lower(), "jpeg")
        return [f"data:image/{mime};base64," + base64.b64encode(path.read_bytes()).decode()]
    try:
        dur = float(subprocess.run(["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0",
                                    str(path)], capture_output=True, text=True, timeout=30).stdout.strip() or 0)
    except (ValueError, subprocess.SubprocessError, OSError):
        dur = 0
    out = []
    with tempfile.TemporaryDirectory() as td:
        for n, at in enumerate((0.1, 0.5, 0.9)):
            jpg = Path(td) / f"{n}.jpg"
            subprocess.run(["ffmpeg", "-v", "error", "-y", "-ss", f"{dur * at:.2f}", "-i", str(path), "-frames:v", "1",
                            "-vf", "scale=768:-2", str(jpg)], capture_output=True, timeout=60)
            if jpg.is_file():
                out.append("data:image/jpeg;base64," + base64.b64encode(jpg.read_bytes()).decode())
    return out


def caption_one(key, ds, item):
    import ltx
    path = DATASETS / ds["id"] / "media" / item["file"]
    images = frames_of(path, item["type"])
    if not images:
        return "", "couldn't read frames"
    words = "40-80" if ds["kind"] == "character" else "60-120"
    system = CAPTION_SYSTEM.format(words=words, rules=CAPTION_RULES[ds["kind"]])
    lead = "Caption this image." if item["type"] == "image" else \
        "These are three frames (start, middle, end) of one clip. Caption the clip, including the motion between them."
    content = [{"type": "text", "text": lead}] + [{"type": "image_url", "image_url": {"url": u}} for u in images]
    text, model = ltx.ask(key, system, content, 300, models=ltx.WRITERS, temperature=0.3)
    return " ".join((text or "").split()), model


def run_captions(did, redo):
    import ltx
    try:
        key = ltx.OR_KEY.read_text().strip()
    except OSError:
        key = ""
    done = failed = 0
    with _lock:
        ds = load_ds(did)
        todo = [i["file"] for i in ds["items"] if redo or not (i.get("caption") or "").strip()]
        ds["captioning"] = {"status": "running", "done": 0, "total": len(todo), "failed": 0, "started": now()}
        save_ds(ds)
    for fname in todo:
        ds = load_ds(did)
        item = next((i for i in ds["items"] if i["file"] == fname), None)
        if not item:
            continue
        text, model = caption_one(key, ds, item) if key else ("", "no OpenRouter key on the relay")
        with _lock:
            ds = load_ds(did)
            for i in ds["items"]:
                if i["file"] == fname:
                    if text:
                        i["caption"], i["caption_by"] = text, model
                        done += 1
                    else:
                        i["caption_error"] = model
                        failed += 1
            ds["captioning"].update(done=done, failed=failed)
            save_ds(ds)
    with _lock:
        ds = load_ds(did)
        ds["captioning"].update(status="done", finished=now())
        save_ds(ds)


@router.post("/datasets/{did}/caption")
def caption(did: str, redo: bool = False):
    ds = load_ds(did)
    if (ds.get("captioning") or {}).get("status") == "running":
        raise HTTPException(409, "Captioning is already running.")
    threading.Thread(target=run_captions, args=(did, redo), daemon=True).start()
    return {"started": True}


# ---------------------------------------------------------------- runs

def run_path(rid):
    if not ID.match(rid or ""):
        raise HTTPException(404, "No such run.")
    return RUNS / f"{rid}.json"


def load_run(rid):
    p = run_path(rid)
    if not p.is_file():
        raise HTTPException(404, "No such run.")
    return json.loads(p.read_text(encoding="utf-8"))


def save_run(run):
    run["updated"] = now()
    write_json(RUNS / f"{run['id']}.json", run)


def all_runs():
    out = []
    for f in RUNS.glob("*.json"):
        try:
            out.append(json.loads(f.read_text(encoding="utf-8")))
        except (OSError, ValueError):
            pass
    return sorted(out, key=lambda r: r.get("created", 0), reverse=True)


def training_now():
    """True while a run holds the Colab GPU; LTX renders refuse to start meanwhile."""
    return any(r.get("status") in ACTIVE for r in all_runs())


def remote(cmd, timeout=240):
    import setupbot
    return setupbot.colab(cmd, timeout)


def colab_put(src, dst, timeout=900):
    return subprocess.run(["flock", "-w", "600", "/tmp/colab.lock", COLAB, "upload", "-s", "colab", str(src), dst],
                          capture_output=True, text=True, timeout=timeout)


def colab_get(src, dst, timeout=1800):
    return subprocess.run(["flock", "-w", "600", "/tmp/colab.lock", COLAB, "download", "-s", "colab", src, str(dst)],
                          capture_output=True, text=True, timeout=timeout)


def env_text(run, ds):
    import setupbot
    k = KINDS[ds["kind"]]
    s = ds.get("settings", {})
    env = {"BASE_DIR": REMOTE_BASE, "RUN_ID": run["id"], "NAME": ds["lora"], "TRIGGER": ds.get("trigger") or "",
           "KIND": ds["kind"], "STEPS": s.get("steps") or k["steps"], "RANK": s.get("rank") or k["rank"], "LR": k["lr"],
           "BUCKETS": k["buckets"], "I2V_P": "0.5", "FF": "1" if k["ff"] else "0", "HF_REPO": HF_REPO}
    lines = [f"export {a}={shlex.quote(str(b))}" for a, b in env.items()]
    token = setupbot.secret("HF_TOKEN")
    if token:
        lines.append(f"export HF_TOKEN={shlex.quote(token)}")
    return "\n".join(lines) + "\n"


CKPT = re.compile(r"^CKPT (\S+) (\d+)$", re.M)
FINAL = re.compile(r"^FINAL (\S+) (\d+)$", re.M)
STAGE = re.compile(r"^TRAIN_STAGE: (.+)$", re.M)
STEP = re.compile(r"(?:step|Step)\D{0,3}(\d+)\s*/\s*(\d+)")


def progress(log):
    """(stage, step, total) from the log tail."""
    stage = (STAGE.findall(log) or [""])[-1]
    steps = STEP.findall(log)
    return stage, (int(steps[-1][0]) if steps else None), (int(steps[-1][1]) if steps else None)


def pull(run, remote_file, size, dest):
    """Copy a file off Colab and check its size. Returns True only when the byte count matches."""
    dest.parent.mkdir(parents=True, exist_ok=True)
    for _ in range(3):
        res = colab_get(remote_file, dest)
        if res.returncode == 0 and dest.is_file() and dest.stat().st_size == size:
            return True
        time.sleep(20)
    return False


def work(rid):
    def step(status, text, **kw):
        run = load_run(rid)
        run.update(status=status, step=text, **kw)
        save_run(run)
        return run

    run = load_run(rid)
    rdir = f"{REMOTE_BASE}/train/runs/{rid}"
    log = f"{rdir}/train.log"
    try:
        ds = load_ds(run["dataset"])
        if not run.get("launched"):
            import ltx
            if not ltx.status().get("online"):
                return step("failed", "Colab isn't running the LTX kit. Start Colab and run the LTX setup, then train.")
            step("uploading", f"Uploading {len(ds['items'])} items to Colab")
            remote(f"mkdir -p {rdir}/data")
            entries = []
            for n, item in enumerate(ds["items"]):
                if load_run(rid).get("cancel"):
                    return step("cancelled", "Cancelled during upload")
                src = DATASETS / ds["id"] / "media" / item["file"]
                res = colab_put(src, f"{rdir}/data/{item['file']}")
                if res.returncode != 0:
                    return step("failed", f"Upload of {item['file']} failed: {(res.stderr or '').strip()[-200:]}")
                entries.append({"video": f"{rdir}/data/{item['file']}", "caption": item["caption"]})
                if n % 5 == 4:
                    step("uploading", f"Uploaded {n + 1} of {len(ds['items'])}")
            with tempfile.TemporaryDirectory() as td:
                (Path(td) / "dataset.json").write_text(json.dumps(entries, indent=1), encoding="utf-8")
                envp = Path(td) / "env"
                envp.write_text(env_text(run, ds), encoding="utf-8")
                (Path(td) / "train.sh").write_bytes(SCRIPT.read_bytes().replace(b"\r\n", b"\n"))
                for name in ("dataset.json", "env", "train.sh"):
                    res = colab_put(Path(td) / name, f"{rdir}/{'data/' if name == 'dataset.json' else ''}{name}")
                    if res.returncode != 0:
                        return step("failed", f"Upload of {name} failed: {(res.stderr or '').strip()[-200:]}")
            res = remote(f"chmod 600 {rdir}/env; cd {rdir}; set -a; . ./env; set +a; "
                         f"setsid nohup bash {rdir}/train.sh > {log} 2>&1 < /dev/null & echo launched")
            if "launched" not in (res.stdout or ""):
                return step("failed", "Couldn't start training: " + (res.stderr or res.stdout or "")[-300:])
            step("training", "Started", launched=now())

        misses = 0
        while True:
            run = load_run(rid)
            if run.get("cancel"):
                remote(f"for p in $(pgrep -f {rdir}/[t]rain.sh); do kill -TERM -- -$p; done; pkill -f {rdir}/config.yaml")
                return step("cancelled", "Cancelled; training was stopped. Checkpoints already pulled are kept.")
            try:
                res = remote(f"tail -c 8000 {log} 2>/dev/null; grep -E '^(CKPT|FINAL) ' {log} 2>/dev/null; "
                             f"pgrep -f {rdir}/[t]rain.sh >/dev/null && echo __ALIVE__ || echo __DEAD__")
                out = res.stdout if res.returncode == 0 else ""
            except subprocess.TimeoutExpired:
                out = ""
            if not out:
                misses += 1
                if misses >= 20:
                    return step("failed", "Lost contact with Colab for 20+ minutes (the runtime may have been "
                                          "reclaimed). Pulled checkpoints are kept; Train again resumes from the last.")
                time.sleep(60)
                continue
            misses = 0
            stage, cur, total = progress(out)
            pulled = run.get("pulled", [])
            have = {p["remote"] for p in pulled}
            for path, size in CKPT.findall(out):
                if path in have:
                    continue
                dest = TRAINED / "checkpoints" / ds["lora"] / rid / f"{len(pulled) + 1:02d}_{Path(path).name}"
                ok = pull(run, path, int(size), dest)
                pulled.append({"remote": path, "size": int(size), "local": str(dest), "verified": ok, "at": now()})
                run = load_run(rid)
                run["pulled"] = pulled
                save_run(run)
            tail = out.replace("__ALIVE__", "").replace("__DEAD__", "")
            run = load_run(rid)
            run.update(log=tail[-5000:], stage=stage, step_now=cur, step_total=total)
            save_run(run)
            fin = FINAL.findall(out)
            if "TRAIN_COMPLETE" in out and fin:
                path, size = fin[-1]
                dest = TRAINED / f"{ds['lora']}.safetensors"
                ok = pull(run, path, int(size), dest)
                note = (f"Done. {ds['lora']}.safetensors is in the LTX LoRA list on Colab and saved on the relay "
                        f"({int(size) // (1024 * 1024)} MB, size checked)" if ok else
                        f"Done on Colab ({ds['lora']}.safetensors is in the LTX LoRA list), but the copy to the relay "
                        f"did NOT verify; it is also backed up to Hugging Face {HF_REPO} if the log says so")
                return step("done", note, final={"remote": path, "size": int(size), "local": str(dest), "verified": ok},
                            finished=now())
            if "FATAL:" in out:
                line = [l for l in tail.splitlines() if l.startswith("FATAL:")][-1]
                return step("failed", line[6:].strip()[:300])
            if "__DEAD__" in out:
                return step("failed", "Training stopped without finishing: " + (tail.strip().splitlines() or [""])[-1][:300])
            step("training", stage or "Training")
            time.sleep(60)
    except HTTPException as e:
        step("failed", str(e.detail)[:300])
    except Exception as e:  # noqa: BLE001
        step("failed", f"Stopped: {e}"[:300])
    finally:
        _threads.pop(rid, None)


def start(rid):
    if rid in _threads:
        return
    th = threading.Thread(target=work, args=(rid,), daemon=True)
    _threads[rid] = th
    th.start()


@router.post("/datasets/{did}/train")
def train(did: str):
    ds = load_ds(did)
    ready, problems, _ = checks(ds)
    if not ready:
        raise HTTPException(400, " ".join(problems))
    with _lock:
        if training_now():
            raise HTTPException(409, "A LoRA is already training; wait for it or cancel it.")
        rid = secrets.token_hex(6)
        run = {"id": rid, "dataset": did, "name": ds["name"], "lora": ds["lora"], "kind": ds["kind"],
               "status": "queued", "step": "Queued", "created": now(), "estimate": estimate(ds, colab_rate()),
               "pulled": []}
        save_run(run)
    start(rid)
    return run


@router.get("/runs")
def runs():
    return [{k: r.get(k) for k in ("id", "dataset", "name", "lora", "kind", "status", "step", "stage", "step_now",
                                    "step_total", "created", "finished", "final")} for r in all_runs()]


@router.get("/runs/{rid}")
def get_run(rid: str):
    return load_run(rid)


@router.post("/runs/{rid}/cancel")
def cancel(rid: str):
    run = load_run(rid)
    if run.get("status") not in ACTIVE:
        raise HTTPException(409, "That run isn't active.")
    run["cancel"] = True
    save_run(run)
    return {"cancelling": rid}


def resume():
    for r in all_runs():
        if r.get("status") in ACTIVE and r.get("launched"):
            start(r["id"])
        elif r.get("status") in ("queued", "uploading"):
            r.update(status="failed", step="The hub restarted before training began; start it again.")
            save_run(r)


RUNS.mkdir(parents=True, exist_ok=True)
DATASETS.mkdir(parents=True, exist_ok=True)
resume()
