"""Setup bot: install a named recipe (e.g. Wan 2.2 Remix 14B ComfyUI) on a chosen GPU from the app.

A plan comes from the form or from a chat sentence (OpenRouter turns it into the same plan shape).
Nothing runs until the plan is previewed and confirmed. Runs are files in ~/hub/setup_runs, and the
remote setup keeps going on its own if the hub restarts; the hub picks polling back up on start.
"""
import json
import logging
import re
import secrets
import shlex
import subprocess
import tempfile
import threading
import time
import urllib.error
import urllib.request
from pathlib import Path
from typing import Optional

from fastapi import APIRouter, HTTPException
from pydantic import BaseModel

import thunder
import vast
from gen import KEY as OR_KEY, KeyRejected, call_model, extract_json, notify

log = logging.getLogger("forge-hub.setupbot")
router = APIRouter(prefix="/api/setup")
HUB = Path(__file__).resolve().parent
RECIPES = HUB / "recipes"
RUNS = HUB / "setup_runs"
RUNS.mkdir(exist_ok=True)
HOME = Path.home()
SSH_KEY = HOME / ".ssh" / "thunder_instance"
CIVITAI = HOME / ".civitai_token"
SECRETS = {"CIVITAI_TOKEN": CIVITAI, "HF_TOKEN": HOME / ".hf_token"}
HF_PROBE = "https://huggingface.co/Lightricks/LTX-2.5/resolve/main/vae/ltx-2.5-audio-vae-bf16.safetensors"
_hf_ok = {"at": 0, "tok": None, "ok": False, "why": ""}
COLAB = str(HOME / ".local/bin/colab")
COLAB_OPS = str(HOME / "wan" / "colab_ops.sh")
STAGE_DIR = HOME / "wan" / "lora_stage"
COACH_LORA = "penis-lora-by-coachbate-ltx-2.3.safetensors"
PLANNER = "x-ai/grok-4.5"
ACTIVE = {"planned", "creating", "waiting", "uploading", "installing"}
RUN_ID = re.compile(r"^[0-9a-f]{12}$")
BASE_DIR = {"thunder": "/workspace", "thunder_new": "/workspace", "vast": "/workspace", "vast_new": "/workspace",
            "colab": "/content/workspace"}
REMOTE_DIR = {"thunder": "/home/ubuntu/forge_setup", "thunder_new": "/home/ubuntu/forge_setup",
              "vast": "/root/forge_setup", "vast_new": "/root/forge_setup",
              "colab": "/content/forge_setup"}
_threads = {}


def as_lf_bytes(data: bytes) -> bytes:
    """Bash rejects `set -o pipefail\\r`. Always ship LF scripts to Colab/Thunder/Vast."""
    return data.replace(b"\r\n", b"\n").replace(b"\r", b"\n")


def write_lf(path: Path, text: str) -> None:
    path.write_bytes(as_lf_bytes(text.encode("utf-8")))


def recipes():
    out = {}
    for f in sorted(RECIPES.glob("*/recipe.json")):
        try:
            r = json.loads(f.read_text(encoding="utf-8"))
        except (OSError, ValueError) as e:
            log.warning("skipping unreadable recipe %s: %s", f, e)
            continue
        if not isinstance(r, dict) or not r.get("id"):
            log.warning("skipping recipe %s: no id", f)
            continue
        out[r["id"]] = r
    return out


def recipe(rid):
    r = recipes().get(rid)
    if not r:
        raise HTTPException(400, f"Unknown recipe '{rid}'.")
    return r


def spec_key(gpu, n):
    return f"{gpu.lower()}_x{n}"


def thunder_state():
    try:
        st = thunder.state()
    except HTTPException as e:
        return {"error": e.detail, "instances": [], "specs": {}, "pricing": {}, "stock": {}}
    unwrap = lambda d, k: (d or {}).get(k, d or {}) if isinstance(d, dict) else {}
    return {"instances": st["instances"], "specs": unwrap(st.get("specs"), "specs"),
            "pricing": unwrap(st.get("pricing"), "pricing"), "stock": unwrap(st.get("status"), "specs")}


def hourly(st, gpu, n, disk_gb=0):
    base = st["pricing"].get(spec_key(gpu, n))
    if not isinstance(base, (int, float)):
        return None
    return round(base + disk_gb * (st["pricing"].get("disk_gb") or 0), 2)


def render_target():
    try:
        return thunder.TARGET.read_text().strip()
    except FileNotFoundError:
        return ""


def catalog():
    """Build the Setup form catalog. Vast/Thunder must not hang the whole page."""
    st = thunder_state()
    specs, stock = st["specs"], st["stock"]
    rt = render_target()
    existing = []
    for i in st.get("instances", []):
        n = int(i.get("numGpus") or 1)
        gpu = str(i.get("gpuType") or "")
        sp = specs.get(spec_key(gpu, n)) or {}
        addr = f"{i.get('ip')} {i.get('port')}"
        existing.append({"kind": "thunder", "id": str(i["id"]), "name": i.get("name"), "gpu": gpu, "num_gpus": n,
                         "vram_gb": sp.get("vramGB"), "status": i.get("status"),
                         "price": hourly(st, gpu, n),
                         "renders_here": bool(rt) and addr == rt, "created": i.get("createdAt")})
    new = []
    for k, sp in sorted(specs.items(), key=lambda kv: (kv[1].get("vramGB") or 0, kv[0])):
        m = re.match(r"^(.+)_x(\d+)$", k)
        if not m:
            continue
        gpu, n = m.group(1), int(m.group(2))
        new.append({"gpu_type": gpu, "num_gpus": n, "name": sp.get("displayName") or gpu.upper(),
                    "vram_gb": sp.get("vramGB"), "price": hourly(st, gpu, n), "cpu_options": sp.get("vcpuOptions") or [],
                    "disk": sp.get("storageGB") or {}, "available": stock.get(k) != "unavailable"})
    vst = {"existing": [], "offers": [], "error": None, "credit": None}
    import concurrent.futures
    try:
        # Soft timeout: Vast API stalls leave Setup with zero options otherwise.
        def _vast_bits():
            vs = vast.state()
            offers = {}
            for need in sorted({r.get("min_vram_gb") or 24 for r in recipes().values()}):
                for o in vast.offers(min_vram=need, disk_gb=170)[:10]:
                    offers.setdefault(o["id"], o)
            return vs, offers
        with concurrent.futures.ThreadPoolExecutor(max_workers=1) as pool:
            fut = pool.submit(_vast_bits)
            vs, offers = fut.result(timeout=12)
        vst.update(credit=vs["credit"], existing=vs["instances"],
                   offers=sorted(offers.values(), key=lambda o: (o["vram_gb"], o["price"])))
    except concurrent.futures.TimeoutError:
        vst["error"] = "Vast catalog timed out — Thunder/Colab still work; retry Vast later."
    except HTTPException as e:
        vst["error"] = e.detail
    except Exception as e:
        vst["error"] = str(e)[:200]
    return {"recipes": list(recipes().values()), "existing": existing, "new": new, "vast": vst,
            "colab": {"kind": "colab", "label": "Colab G4 (RTX PRO 6000, 96 GB)", "vram_gb": 96},
            "thunder_error": st.get("error")}


@router.get("/catalog")
def get_catalog():
    return catalog()


class Plan(BaseModel):
    recipe: str
    target: dict
    options: dict = {}
    use_for_renders: bool = False


def ssh(host, port, cmd, timeout=60, user="ubuntu"):
    return subprocess.run(["ssh", "-i", str(SSH_KEY), "-p", str(port), "-o", "BatchMode=yes",
                           "-o", "StrictHostKeyChecking=no", "-o", "UserKnownHostsFile=/dev/null",
                           "-o", "ConnectTimeout=15", "-o", "LogLevel=ERROR", f"{user}@{host}", cmd],
                          capture_output=True, text=True, timeout=timeout)


def scp(host, port, src, dst, user="ubuntu"):
    try:
        r = subprocess.run(["scp", "-q", "-i", str(SSH_KEY), "-P", str(port), "-o", "BatchMode=yes",
                            "-o", "StrictHostKeyChecking=no", "-o", "UserKnownHostsFile=/dev/null",
                            "-o", "ConnectTimeout=15", "-o", "LogLevel=ERROR", src, f"{user}@{host}:{dst}"],
                           capture_output=True, text=True, timeout=300)
    except subprocess.TimeoutExpired:
        raise RuntimeError(f"Copying {Path(src).name} to the machine timed out after 300s")
    except OSError as e:
        raise RuntimeError(f"Could not run scp: {e}")
    if r.returncode != 0:
        why = (r.stderr or r.stdout or "").strip()[-250:] or f"scp exit {r.returncode}"
        raise RuntimeError(f"Copying {Path(src).name} to the machine failed: {why}")


def rssh(run, cmd, timeout=60):
    return ssh(run["host"], run["port"], cmd, timeout, run.get("user", "ubuntu"))


def sudo(run):
    return "" if run.get("user") == "root" or run["plan"]["target"]["kind"] == "colab" else "sudo -n "


def remote_disk(host, port, user, base="/workspace"):
    try:
        out = ssh(host, port, f"mkdir -p {base} 2>/dev/null; df -BG --output=avail {base} 2>/dev/null | tail -1; "
                  f"test -d {base}/ComfyUI && echo HAS_COMFY", 30, user)
        free = int(re.sub(r"\D", "", out.stdout.splitlines()[0])) if out.returncode == 0 else None
        return free, "HAS_COMFY" in out.stdout
    except Exception:
        return None, False


def colab(cmd, timeout=240):
    return subprocess.run(["bash", COLAB_OPS, cmd], capture_output=True, text=True, timeout=timeout)


def colab_active():
    out = subprocess.run([COLAB, "usage"], capture_output=True, text=True, timeout=90).stdout
    m = re.search(r"Active assignments:\s*(\d+)", out)
    return bool(m and int(m.group(1)))


def instance(iid):
    for i in thunder_state().get("instances", []):
        if str(i["id"]) == str(iid):
            return i
    return None


def clean_options(r, opts):
    out = {}
    for o in r["options"]:
        v = opts.get(o["key"], o["default"])
        if o["type"] == "bool":
            v = v in (True, 1, "1", "true", "yes")
            out[o["key"]] = v
        else:
            allowed = [c[0] for c in o["choices"]]
            if v not in allowed:
                raise HTTPException(400, f"{o['label']} must be one of {', '.join(allowed)}.")
            out[o["key"]] = v
    return out


def disk_need(r, opts):
    d = r["disk_gb"]
    if "base" in d:
        return d["base"] + sum(n for k, n in d.get("add", {}).items()
                               if str(opts.get(k.split("=")[0])).lower() == k.split("=")[1])
    return d.get(opts.get("MODE", "both"), max(v for k, v in d.items() if k != "sfw_extra")) + \
        (d.get("sfw_extra", 0) if opts.get("INSTALL_SFW") else 0)


def active_14b():
    try:
        jobs = thunder.wanbot14("GET", "/jobs") or []
    except HTTPException:
        return 0
    jobs = jobs.get("jobs", jobs) if isinstance(jobs, dict) else jobs
    return sum(1 for j in jobs if str(j.get("status", "")).lower() in ("queued", "running", "pending"))


def preview(p: Plan):
    r = recipe(p.recipe)
    opts = clean_options(r, p.options)
    need = disk_need(r, opts)
    kind = p.target.get("kind")
    steps, warn, block = [], [], []
    info = {"recipe": r["title"], "options": opts, "disk_need_gb": need, "minutes": r.get("minutes")}
    if kind == "thunder":
        i = instance(p.target.get("id"))
        if not i:
            raise HTTPException(400, "That Thunder instance doesn't exist any more.")
        n = int(i.get("numGpus") or 1)
        gpu = str(i.get("gpuType") or "")
        st = thunder_state()
        vram = (st["specs"].get(spec_key(gpu, n)) or {}).get("vramGB")
        info.update(target=f"Thunder {gpu.upper()} x{n} (existing, id {i['id']})", gpu=gpu, vram_gb=vram,
                    price=hourly(st, gpu, n))
        if str(i.get("status", "")).upper() != "RUNNING":
            block.append(f"The instance is {i.get('status')}, not running.")
        else:
            free, has_comfy = remote_disk(i["ip"], i["port"], "ubuntu")
            if free is None:
                block.append("The relay can't SSH into that instance with its key.")
            else:
                info["free_gb"] = free
                if free < need:
                    block.append(f"Only {free} GB free on /workspace; the setup script refuses to start below "
                                 f"{need} GB even when models are already there. Pick fewer models (image-to-video "
                                 f"only needs 80 GB), grow the disk on the Thunder screen, or use a new instance.")
            renders = f"{i['ip']} {i['port']}" == render_target()
            if renders and "SKIP_COMFY" in opts:
                jobs = active_14b()
                if not opts["SKIP_COMFY"]:
                    msg = ("This is the machine your 14B renders use. Reinstalling deletes its ComfyUI folder and "
                           "stops rendering for about 15 minutes")
                    block.append(msg + (f"; {jobs} 14B job(s) are active." if jobs else ".") +
                                 " Turn on 'Keep the existing ComfyUI', or use a new instance.")
                else:
                    warn.append("ComfyUI restarts at the end, so the clip rendering then is redone"
                                + (f" ({jobs} 14B job(s) active)." if jobs else "."))
            elif has_comfy and opts.get("SKIP_COMFY") is False:
                warn.append("This deletes the ComfyUI folder already on that instance.")
        steps = ["Upload the setup script", f"Install into {BASE_DIR['thunder']} (about {r.get('minutes')} min)",
                 "Start ComfyUI on port 8188"]
    elif kind == "thunder_new":
        gpu = str(p.target.get("gpu_type") or "").lower()
        n = int(p.target.get("num_gpus") or 1)
        st = thunder_state()
        sp = st["specs"].get(spec_key(gpu, n))
        if not sp:
            raise HTTPException(400, f"Thunder has no {gpu.upper()} x{n}.")
        lim = sp.get("storageGB") or {"min": 100, "max": 500}
        disk = min(max(int(p.target.get("disk_gb") or 0), need + 40, lim["min"]), lim["max"])
        cpus = sp.get("vcpuOptions") or [8]
        cores = int(p.target.get("cpu_cores") or 0)
        if cores not in cpus:
            cores = 8 if 8 in cpus else cpus[0]
        p.target.update(gpu_type=gpu, num_gpus=n, disk_gb=disk, cpu_cores=cores)
        price = hourly(st, gpu, n, disk)
        info.update(target=f"New Thunder {sp.get('displayName') or gpu.upper()} x{n}, {cores} CPU cores, {disk} GB disk",
                    gpu=gpu, vram_gb=sp.get("vramGB"), price=price)
        if st["stock"].get(spec_key(gpu, n)) == "unavailable":
            block.append(f"Thunder has no {sp.get('displayName') or gpu.upper()} x{n} available right now.")
        if disk < need:
            block.append(f"This GPU allows at most {lim['max']} GB of disk; the setup needs {need} GB.")
        warn.append("A new instance bills" + (f" ${price:.2f}/h" if isinstance(price, (int, float)) else "") +
                    " until you delete it on the Thunder screen.")
        if p.use_for_renders:
            warn.append("When it's done, the 14B renders move to it. The old instance keeps billing until you delete it.")
        steps = ["Create the instance with the relay's SSH key", "Wait until it's running (2-5 min)",
                 "Upload the setup script", f"Install into /workspace (about {r.get('minutes')} min)",
                 "Start ComfyUI on port 8188"] + (["Point the 14B runner at it"] if p.use_for_renders else [])
    elif kind == "vast":
        vid = str(p.target.get("id") or "")
        if not vast.ID.match(vid):
            raise HTTPException(400, "Bad Vast instance id.")
        i = vast.instance(vid)
        if not i:
            raise HTTPException(400, "That Vast instance doesn't exist any more.")
        info.update(target=f"Vast {i['gpu']} x{i['num_gpus']} (existing, id {i['id']}, {i['location']})",
                    gpu=i["gpu"], vram_gb=i["vram_gb"], price=i["price"])
        free, has_comfy = (None, False)
        if i["status"] != "running" or not i["host"]:
            block.append(f"The Vast instance is {i['status']}; start it on the Vast screen first.")
        else:
            free, has_comfy = remote_disk(i["host"], i["port"], "root")
            if free is None:
                block.append("The relay can't SSH into that Vast instance with its key.")
            elif free < need:
                block.append(f"Only {free} GB free; the setup needs {need} GB. Vast can't grow a disk, so pick "
                             f"fewer models or rent a new machine with a bigger disk.")
            else:
                info["free_gb"] = free
        if has_comfy and opts.get("SKIP_COMFY") is False:
            warn.append("This deletes the ComfyUI folder already on that instance.")
        if p.use_for_renders:
            warn.append("The 14B runner only drives Thunder for now, so renders stay where they are.")
        p.use_for_renders = False
        steps = ["Upload the setup script", f"Install into /workspace (about {r.get('minutes')} min)",
                 "Start ComfyUI on port 8188"]
    elif kind == "vast_new":
        oid = str(p.target.get("offer_id") or "")
        if not vast.ID.match(oid):
            raise HTTPException(400, "Pick a Vast offer.")
        disk = max(int(p.target.get("disk_gb") or 0), need + 40)
        found = vast.search(disk_gb=disk, offer_id=oid)
        if found and found[0]["rentable"]:
            o = found[0]
        else:
            gpu = p.target.get("gpu") or (found[0]["gpu"] if found else None)
            alt = [x for x in vast.search(min_vram=r.get("min_vram_gb", 24), gpu=gpu, disk_gb=disk, limit=10)
                   if gpu and x["price"] <= float(p.target.get("price") or x["price"]) * 1.1]
            if not alt:
                raise HTTPException(409, "That Vast offer is gone (someone else rented it). Pick another.")
            o = alt[0]
            warn.append(f"The machine you picked was just rented; this is the cheapest other {o['gpu']}.")
            oid = o["id"]
        p.target.update(offer_id=oid, disk_gb=disk, gpu=o["gpu"], price=o["price"])
        info.update(target=f"New Vast {o['gpu']} x{o['num_gpus']} in {o['location']}, {disk} GB disk",
                    gpu=o["gpu"], vram_gb=o["vram_gb"], price=o["price"])
        if not o["rentable"]:
            block.append("That Vast offer was just rented by someone else. Pick another.")
        if disk > o["disk_max_gb"]:
            block.append(f"That machine only has {o['disk_max_gb']} GB of disk; the setup needs {disk} GB.")
        if (o["cuda"] or 0) < vast.MIN_CUDA:
            block.append(f"That machine's driver only supports CUDA {o['cuda']}; ComfyUI needs {vast.MIN_CUDA}.")
        try:
            credit = vast.state()["credit"]
            if credit < o["price"] * 2:
                block.append(f"Vast credit is ${credit:.2f}, under 2 hours at ${o['price']:.2f}/h. Top up first.")
            elif credit < o["price"] * 8:
                warn.append(f"Vast credit is ${credit:.2f}: about {credit / o['price']:.0f} hours at this price.")
        except HTTPException:
            pass
        warn.append(f"Bills ${o['price']:.2f}/h while running and about ${o['stopped_price']:.2f}/h for the disk when "
                    f"stopped, until you destroy it on the Vast screen.")
        if p.use_for_renders:
            warn.append("The 14B runner only drives Thunder for now, so renders stay where they are.")
        p.use_for_renders = False
        steps = ["Rent the machine with the relay's SSH key", "Wait for the image to download and start (3-10 min)",
                 "Upload the setup script", f"Install into /workspace (about {r.get('minutes')} min)",
                 "Start ComfyUI on port 8188"]
    elif kind == "colab":
        info.update(target="Colab G4 (RTX PRO 6000, 96 GB)", gpu="rtx pro 6000", vram_gb=96, price=None)
        if need > 150:
            block.append(f"Colab has about 160 GB of disk; this setup needs {need} GB. Pick one model type or skip SFW.")
        warn.append("Starts a G4 runtime if none is running (uses Colab credits). Colab wipes it when the runtime "
                    "is reclaimed, and the 14B runner isn't wired to Colab's ComfyUI.")
        if (HOME / "wan" / "paused").exists() is False:
            warn.append("The 5B renders also use this runtime; the install shares its GPU and disk.")
        p.use_for_renders = False
        steps = ["Start a G4 runtime if needed", "Upload the setup script",
                 f"Install into /content/workspace (about {r.get('minutes')} min)", "Start ComfyUI on port 8188"]
    else:
        raise HTTPException(400, "Target must be a Thunder or Vast instance (existing or new), or Colab.")
    if info.get("vram_gb") and info["vram_gb"] < r.get("min_vram_gb", 0):
        block.append(f"{r['title']} needs at least {r['min_vram_gb']} GB of VRAM; this GPU has {info['vram_gb']} GB.")
    if r.get("recommended_vram_gb") and info.get("vram_gb") and \
            r.get("min_vram_gb", 0) <= info["vram_gb"] < r["recommended_vram_gb"]:
        warn.append(f"{info['vram_gb']} GB of VRAM works but is tight for this kit ({r['recommended_vram_gb']} GB "
                    f"recommended): stay at 49 frames, and pick the int8 encoder if it runs out of memory.")
    if opts.get("KIT") == "recipes" and not CIVITAI.exists():
        warn.append("No CivitAI token on the relay, so the muscle and possession LoRAs are skipped.")
    if opts.get("SEX_LORA") in ("sexgod", "all") and not CIVITAI.exists():
        warn.append("No CivitAI token on the relay, so SexGod / Civitai sex LoRAs are skipped.")
    if str(opts.get("CONTENT_LORAS", "1")).lower() in ("1", "true", "yes") and not CIVITAI.exists():
        warn.append("No CivitAI token on the relay — NSFW content LoRAs from Civitai are skipped (HF ones still download).")
    needs = None
    if r.get("secrets", {}).get("HF_TOKEN") == "required":
        ok, why = hf_access()
        if not ok:
            needs = "HF_TOKEN"
            block.append({"missing": "No Hugging Face token on the relay yet.",
                          "rejected": "Hugging Face rejected the stored token (expired or deleted).",
                          "gated": "The Hugging Face token works but can't read Lightricks/LTX-2.5: accept the "
                                   "licence on huggingface.co/Lightricks/LTX-2.5 with the same account."}.get(why, why)
                         + " Paste a read token below (huggingface.co/settings/tokens).")
    return {"plan": p.model_dump(), "info": info, "steps": steps, "warnings": warn, "blocking": block,
            "ok": not block, "needs_secret": needs}


class Secret(BaseModel):
    name: str
    value: str


@router.post("/secret")
def put_secret(s: Secret):
    if s.name not in SECRETS:
        raise HTTPException(400, "Unknown secret")
    v = s.value.strip()
    if s.name == "HF_TOKEN" and not re.fullmatch(r"hf_[A-Za-z0-9]{30,}", v):
        raise HTTPException(400, "That doesn't look like a Hugging Face token (it starts with hf_).")
    if not re.fullmatch(r"[\x21-\x7e]{16,200}", v):
        raise HTTPException(400, "That doesn't look like a token.")
    path = SECRETS[s.name]
    tmp = path.with_suffix(".tmp")
    tmp.touch(0o600)
    tmp.write_text(v + "\n")
    tmp.replace(path)
    path.chmod(0o600)
    _hf_ok["at"] = 0
    if s.name == "HF_TOKEN":
        ok, why = hf_access()
        return {"ok": ok, "why": why}
    return {"ok": True}


@router.post("/preview")
def post_preview(p: Plan):
    return preview(p)


# ---------- chat planner ----------

class Chat(BaseModel):
    text: str
    history: list = []


def catalog_brief(c):
    lines = ["RECIPES:"]
    for r in c["recipes"]:
        opts = "; ".join(f"{o['key']} ({o['label']}): " + ("true/false" if o["type"] == "bool" else
                         "/".join(x[0] for x in o["choices"])) + f", default {o['default']}" for o in r["options"])
        lines.append(f"- {r['id']}: {r['title']}. {r.get('summary', '')} min VRAM {r['min_vram_gb']} GB"
                     + (f", best {r['recommended_vram_gb']} GB" if r.get("recommended_vram_gb") else "") + f". Options: {opts}")
    lines.append("EXISTING THUNDER INSTANCES:")
    for i in c["existing"] or []:
        lines.append(f"- id {i['id']}: {i['gpu']} x{i['num_gpus']}, {i['vram_gb']} GB VRAM, {i['status']}"
                     + (", the 14B renders run here" if i["renders_here"] else ""))
    if not c["existing"]:
        lines.append("- none")
    lines.append("NEW THUNDER GPUS (gpu_type, num_gpus, VRAM, $/h):")
    for g in c["new"]:
        lines.append(f"- {g['gpu_type']} x{g['num_gpus']} ({g['name']}), {g['vram_gb']} GB, ${g['price']}/h, "
                     f"cpu_cores {g['cpu_options']}" + ("" if g["available"] else ", SOLD OUT"))
    v = c.get("vast") or {}
    lines.append(f"EXISTING VAST INSTANCES (credit ${v.get('credit')}):")
    for i in v.get("existing") or []:
        lines.append(f"- id {i['id']}: {i['gpu']} x{i['num_gpus']}, {i['vram_gb']} GB VRAM, {i['status']}, ${i['price']}/h, {i['location']}")
    if not v.get("existing"):
        lines.append("- none")
    lines.append("VAST OFFERS FOR A NEW MACHINE (offer_id, gpu, VRAM, $/h with disk, location, reliability %):")
    for o in v.get("offers") or []:
        lines.append(f"- {o['id']}: {o['gpu']} x{o['num_gpus']}, {o['vram_gb']} GB, ${o['price']}/h, {o['location']}, {o['reliability']}%")
    lines.append("COLAB: one G4 runtime (RTX PRO 6000, 96 GB VRAM, ~160 GB disk).")
    return "\n".join(lines)


SYSTEM = """You plan GPU setups for a render pipeline. The user says what to install and where, in casual words.
Turn it into one JSON object and nothing else:
{"reply": "<one or two short plain sentences saying what you'll do or asking what you need>",
 "plan": null or {"recipe": "<recipe id>",
   "target": {"kind": "thunder", "id": "<existing id>"} or
             {"kind": "thunder_new", "gpu_type": "<gpu_type>", "num_gpus": <n>, "cpu_cores": <4|8|16|32>, "disk_gb": <GB>} or
             {"kind": "vast", "id": "<existing Vast id>"} or
             {"kind": "vast_new", "offer_id": "<offer id from the list>", "gpu": "<its gpu>", "price": <its $/h>, "disk_gb": 0} or
             {"kind": "colab"},
   "options": {<option key>: <value>},
   "use_for_renders": <true only if they want the 14B renders to move there>}}
Rules:
- Only use recipe ids, instance ids, gpu types and option values from the catalog. Never invent.
- If there is exactly one recipe and they don't name one, use it. "ltx", "ltx 2.5", "stubelius" mean ltx25;
  "wan", "14b", "remix" mean remix14. If they don't say which and there are several, ask.
- Only pick GPUs with at least the recipe's min VRAM; for ltx25 prefer 96 GB (Colab G4 or a 96 GB Vast card).
- "a new one", "fresh", "another" means thunder_new. "the current one", "my instance" means the existing instance.
- If they name a GPU without saying new or existing, use the existing instance if it has that GPU, else thunder_new.
- "vast" means Vast: existing Vast instance if they say current/my, else vast_new with the cheapest matching offer
  (prefer reliability >= 99% when prices are within 10%). "thunder" means Thunder. Say the GPU, location and price you chose.
- If they give no provider, GPU or target, pick the cheapest new Thunder GPU that meets the recipe's min VRAM and say so.
- Set disk_gb to 0 to let the server size it.
- Leave options at their defaults unless the user asks otherwise. "just image to video" means MODE i2v, "no loras" means KIT none,
  "keep comfy" / "don't reinstall" means SKIP_COMFY true.
- If the request is too vague to choose, set plan to null and ask one short question in reply.
- Never mention prices you can't see in the catalog."""


@router.post("/chat")
def chat(c: Chat):
    try:
        key = OR_KEY.read_text().strip()
    except FileNotFoundError:
        raise HTTPException(400, "No OpenRouter key on the relay yet; add it under the prompt generator's settings.")
    cat = catalog()
    hist = "\n".join(f"{m.get('role', 'user')}: {m.get('text', '')}" for m in c.history[-6:])
    user = f"CATALOG\n{catalog_brief(cat)}\n\n" + (f"EARLIER\n{hist}\n\n" if hist else "") + f"USER: {c.text.strip()[:1500]}"
    try:
        out = extract_json(call_model(key, PLANNER, 0.1, SYSTEM, user, 600))
    except KeyRejected as e:
        raise HTTPException(401, str(e))
    except (ValueError, RuntimeError) as e:
        raise HTTPException(502, f"The planner didn't answer properly ({e}). Try again or use the form.")
    res = {"reply": str(out.get("reply") or ""), "preview": None}
    if isinstance(out.get("plan"), dict):
        try:
            res["preview"] = preview(Plan(**out["plan"]))
        except (HTTPException, ValueError) as e:
            res["reply"] += f" (I couldn't use that plan: {getattr(e, 'detail', e)})"
    return res


# ---------- runs ----------

def save(run):
    run["updated"] = time.time()
    path = RUNS / f"{run['id']}.json"
    tmp = path.with_suffix(".tmp")
    tmp.write_text(json.dumps(run), encoding="utf-8")
    tmp.replace(path)


def read_run(p):
    """Parsed run file, or None if it is missing/corrupt - never a partial dict."""
    try:
        r = json.loads(p.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return None
    return r if isinstance(r, dict) and r.get("id") else None


def load(rid):
    if not RUN_ID.match(rid):
        raise HTTPException(400, "Bad run id")
    p = RUNS / f"{rid}.json"
    if not p.is_file():
        raise HTTPException(404, "No such run")
    run = read_run(p)
    if run is None:
        raise HTTPException(500, f"Setup run {rid} is unreadable or corrupt on disk.")
    return run


def secret(name):
    try:
        return SECRETS[name].read_text().strip()
    except FileNotFoundError:
        return ""


def hf_access():
    """(ok, why) for reading the gated Lightricks repo with the stored token; cached 10 min."""
    tok = secret("HF_TOKEN")
    if not tok:
        return False, "missing"
    if _hf_ok["tok"] == tok and time.time() - _hf_ok["at"] < 600:
        return _hf_ok["ok"], _hf_ok["why"]
    req = urllib.request.Request(HF_PROBE, method="HEAD", headers={"Authorization": f"Bearer {tok}"})
    try:
        with urllib.request.build_opener(NoRedirect).open(req, timeout=20):
            pass
        ok, why = True, ""
    except urllib.error.HTTPError as e:
        ok = e.code in (301, 302, 307, 308)
        why = "" if ok else ("rejected" if e.code == 401 and "Invalid" in (e.headers.get("x-error-message") or "")
                             else "gated" if e.code in (401, 403) else f"HTTP {e.code}")
    except (urllib.error.URLError, TimeoutError, OSError):
        return True, "unchecked"
    _hf_ok.update(at=time.time(), tok=tok, ok=ok, why=why)
    return ok, why


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *a, **k):
        return None


def mask(text):
    for name in SECRETS:
        tok = secret(name)
        if tok:
            text = text.replace(tok, "***")
    return re.sub(r"token=[^&\s\"']+", "token=***", text)


def stage(tail):
    for line in reversed(tail.splitlines()):
        s = line.strip()
        if s and not s.startswith(("SKIP", "  ", "\t")) and len(s) < 200:
            return s
    return ""


def cancelled(rid):
    return load(rid).get("cancel")


def env_file(r, opts, base):
    env = {"BASE_DIR": base, "NEED_GB": disk_need(r, opts), **r.get("fixed_env", {})}
    for k, v in opts.items():
        env[k] = ("1" if v else "0") if isinstance(v, bool) else v
    lines = [f"export {k}={shlex.quote(str(v))}" for k, v in env.items()]
    for name in r.get("secrets", {"CIVITAI_TOKEN": "optional"}):
        v = secret(name)
        if v:
            lines.append(f"export {name}={shlex.quote(v)}")
    return "\n".join(lines) + "\n"


def launch_cmd(rdir, base, log):
    # Strip CR again on the box in case an upload path rewrote the file.
    inner = (f"sed -i 's/\\r$//' {rdir}/setup.sh {rdir}/env 2>/dev/null; "
             f"set -a; . {rdir}/env; set +a; mkdir -p {base}/logs; cd {rdir}; "
             f"setsid nohup bash {rdir}/setup.sh > {base}/{log} 2>&1 < /dev/null & echo launched")
    return inner


def ensure_coachbate_on_colab(step):
    """Push the staged CoachBate 2.3 LoRA into /content/lora_keep for setup.sh to restore."""
    src = STAGE_DIR / COACH_LORA
    if not src.is_file() or src.stat().st_size < 1_000_000:
        step("uploading", f"CoachBate 2.3 not staged at {src} — content pack will warn if missing")
        return False
    remote = f"/content/lora_keep/{COACH_LORA}"
    colab(f"mkdir -p /content/lora_keep")
    check = colab(f"stat -c%s {remote} 2>/dev/null || echo 0")
    remote_sz = (check.stdout or "").strip().splitlines()[-1:] or ["0"]
    remote_sz = remote_sz[0].strip()
    want = str(src.stat().st_size)
    if remote_sz == want:
        step("uploading", "CoachBate 2.3 already on Colab keep")
        return True
    step("uploading", "Uploading CoachBate 2.3 LoRA to Colab (about 1.3 GB)")
    subprocess.run(
        ["flock", "/tmp/colab.lock", COLAB, "upload", "-s", "colab", str(src), remote],
        check=True, capture_output=True, text=True, timeout=3600,
    )
    verify = colab(f"stat -c%s {remote} 2>/dev/null || echo 0")
    got = (verify.stdout or "").strip().splitlines()[-1:] or ["0"]
    if got[0].strip() != want:
        raise RuntimeError(f"CoachBate upload size mismatch: remote={got[0]!r} want={want}")
    return True


def poll_cmd(rdir, base, log):
    return (f"tail -c 6000 {base}/{log} 2>/dev/null; echo; "
            f"pgrep -f {rdir}/[s]etup.sh >/dev/null && echo __ALIVE__ || echo __DEAD__")


def work(rid):
    run = load(rid)
    r = recipe(run["plan"]["recipe"])
    t = run["plan"]["target"]
    kind = t["kind"]
    base, rdir, log = BASE_DIR[kind], REMOTE_DIR[kind], r["log"]

    def step(status, text, **kw):
        nonlocal run
        run = load(rid)
        run.update(status=status, step=text, **kw)
        save(run)

    try:
        if kind == "thunder_new" and not run.get("host"):
            if not run.get("instance_id"):
                step("creating", "Creating the Thunder instance")
                pub = subprocess.run(["ssh-keygen", "-y", "-f", str(SSH_KEY)], capture_output=True, text=True,
                                     check=True).stdout.strip()
                res = thunder.call("POST", "/v1/instances/create", {
                    "gpu_type": t["gpu_type"], "num_gpus": t["num_gpus"], "cpu_cores": t["cpu_cores"],
                    "disk_size_gb": t["disk_gb"], "template": "base", "public_key": pub}, timeout=90)
                step("waiting", "Waiting for the instance to start", instance_id=str(res.get("identifier")))
            deadline = time.time() + 1200
            while time.time() < deadline:
                if cancelled(rid):
                    return step("cancelled", "Cancelled before install (the instance still exists; delete it on "
                                             "the Thunder screen if you don't want it)")
                i = instance(run["instance_id"])
                if i and str(i.get("status", "")).upper() == "RUNNING" and i.get("ip") and i.get("port"):
                    try:
                        if ssh(i["ip"], i["port"], "true", 30).returncode == 0:
                            step("uploading", "Instance is up", host=i["ip"], port=i["port"])
                            break
                    except subprocess.TimeoutExpired:
                        pass
                time.sleep(15)
            else:
                return step("failed", "The instance didn't come up within 20 minutes")
        elif kind == "vast_new" and not run.get("host"):
            if not run.get("instance_id"):
                step("creating", "Renting the Vast machine")
                res = vast.rent(vast.Rent(offer_id=t["offer_id"], disk_gb=t["disk_gb"], label=f"forge-{rid}"))
                step("waiting", "Waiting for the machine to start", instance_id=res["id"], user="root")
            deadline = time.time() + 1800
            while time.time() < deadline:
                if cancelled(rid):
                    return step("cancelled", "Cancelled before install (the Vast machine still exists and bills; "
                                             "destroy it on the Vast screen if you don't want it)")
                try:
                    i = vast.instance(run["instance_id"])
                except HTTPException:
                    i = None
                if i and i["status"] == "running" and i["host"]:
                    try:
                        if ssh(i["host"], i["port"], "true", 30, "root").returncode == 0:
                            step("uploading", "Machine is up", host=i["host"], port=i["port"], user="root")
                            break
                    except subprocess.TimeoutExpired:
                        pass
                elif i:
                    step("waiting", f"Vast: {i['status_msg'] or i['status']}")
                time.sleep(15)
            else:
                return step("failed", "The Vast machine didn't come up within 30 minutes (it still exists; "
                                      "destroy it on the Vast screen)")
        elif kind == "vast" and not run.get("host"):
            i = vast.instance(t["id"])
            if not i or not i["host"]:
                return step("failed", "That Vast instance isn't running")
            step("uploading", "Connecting", host=i["host"], port=i["port"], user="root", instance_id=i["id"])
        elif kind == "thunder" and not run.get("host"):
            i = instance(t["id"])
            if not i or not i.get("ip"):
                return step("failed", "That Thunder instance isn't running")
            step("uploading", "Connecting", host=i["ip"], port=i["port"], instance_id=str(i["id"]))

        if not run.get("launched"):
            if cancelled(rid):
                return step("cancelled", "Cancelled")
            step("uploading", "Uploading the setup script")
            with tempfile.TemporaryDirectory() as td:
                envp = Path(td) / "env"
                write_lf(envp, env_file(r, run["options"], base))
                envp.chmod(0o600)
                script_src = RECIPES / r["id"] / r["script"]
                script_lf = Path(td) / "setup.sh"
                script_lf.write_bytes(as_lf_bytes(script_src.read_bytes()))
                script_lf.chmod(0o755)
                if kind == "colab":
                    if not colab_active():
                        step("uploading", "Starting a Colab G4 runtime (a few minutes)")
                        subprocess.run(["flock", "-w", "600", "/tmp/colab.lock", COLAB, "new", "-s", "colab",
                                        "--gpu", "G4"], capture_output=True, text=True, timeout=900)
                        if not colab_active():
                            return step("failed", "Colab didn't give a G4 runtime (quota or none free). Try again later.")
                    colab(f"mkdir -p {rdir} {base}/logs /content/lora_keep")
                    if r["id"] == "ltx25":
                        ensure_coachbate_on_colab(step)
                    for src, name in ((str(script_lf), "setup.sh"), (str(envp), "env")):
                        subprocess.run(["flock", "/tmp/colab.lock", COLAB, "upload", "-s", "colab", src,
                                        f"{rdir}/{name}"], check=True, capture_output=True, text=True, timeout=600)
                    colab(f"chmod 600 {rdir}/env; chmod 755 {rdir}/setup.sh; " + launch_cmd(rdir, base, log))
                else:
                    user = run.get("user", "ubuntu")
                    rssh(run, f"mkdir -p {rdir} && chmod 700 {rdir}")
                    scp(run["host"], run["port"], str(script_lf), f"{rdir}/setup.sh", user)
                    scp(run["host"], run["port"], str(envp), f"{rdir}/env", user)
                    res = rssh(run, f"chmod 600 {rdir}/env; chmod 755 {rdir}/setup.sh; {sudo(run)}bash -c "
                               + shlex.quote(launch_cmd(rdir, base, log)))
                    if "launched" not in res.stdout:
                        return step("failed", "Couldn't start the setup: " + mask(res.stderr.strip())[:300])
            step("installing", "Installing", launched=time.time())

        misses, last_miss = 0, ""
        while True:
            if cancelled(rid):
                kill = f"for p in $(pgrep -f {rdir}/[s]etup.sh); do {sudo(run)}kill -TERM -- -$p; done"
                colab(kill) if kind == "colab" else rssh(run, kill)
                return step("cancelled", "Cancelled; the install was stopped part-way")
            try:
                res = colab(poll_cmd(rdir, base, log)) if kind == "colab" else \
                    rssh(run, poll_cmd(rdir, base, log))
                out = res.stdout if res.returncode == 0 else ""
                if not out:
                    last_miss = (res.stderr or "").strip().splitlines()[-1:] or [f"exit {res.returncode}, no output"]
                    last_miss = last_miss[0][:200]
            except subprocess.TimeoutExpired:
                out, last_miss = "", "the poll command timed out"
            except OSError as e:
                out, last_miss = "", f"could not run ssh: {e}"
            if not out:
                misses += 1
                if misses >= 20:
                    return step("failed", "Lost contact with the machine for 10+ minutes; last reason: "
                                + mask(last_miss or "no output and no error"))
                time.sleep(30)
                continue
            misses = 0
            tail = mask(out.replace("__ALIVE__", "").replace("__DEAD__", "")).strip()
            run = load(rid)
            run["log"] = tail[-4000:]
            save(run)
            if r["done_marker"] in tail:
                break
            if any(m in tail for m in r["fail_markers"]):
                notify(f"Setup failed: {r['title']} on {run['info']['target']}")
                return step("failed", stage(tail) or "The setup script reported a problem")
            if "__DEAD__" in out:
                notify(f"Setup stopped: {r['title']} on {run['info']['target']}")
                return step("failed", "The setup script stopped without finishing: " + stage(tail))
            step("installing", stage(tail) or "Installing")
            time.sleep(20 if kind != "colab" else 60)

        done = "Done. ComfyUI is running"
        if kind == "colab":
            import colab as colab_api
            step("installing", "Bringing the Comfy tunnel up")
            tun = colab_api.comfy_tunnel(True, wait_s=90)
            if tun.get("online"):
                done += ": open LTX from the phone (tunnel on :18288)"
            else:
                done += (": Comfy finished on Colab, but the relay tunnel is not answering yet — "
                         "open the Colab screen and hit Recover, or wait a minute")
                notify(f"Setup done but tunnel cold: {tun.get('detail') or tun}")
        elif kind in ("thunder", "thunder_new") and run["plan"].get("use_for_renders"):
            done += ": open it from the Thunder screen"
        if run["plan"].get("use_for_renders") and kind in ("thunder", "thunder_new"):
            thunder.TARGET.write_text(f"{run['host']} {run['port']}\n")
            moved, why = thunder.restart_tunnel(30)
            done += ("; the 14B renders now use this machine" if moved else
                     "; BUT the render tunnel did NOT restart, so 14B renders still go to the old "
                     "machine - restart wan-thunder-tunnel on the relay by hand (" + mask(why)[:200] + ")")
        step("done", done, finished=time.time())
        notify(f"Setup done: {r['title']} on {run['info']['target']}")
    except Exception as e:
        step("failed", f"Stopped: {mask(str(getattr(e, 'detail', e)))[:300]}")
    finally:
        _threads.pop(rid, None)


def start_thread(rid):
    if rid in _threads:
        return
    th = threading.Thread(target=work, args=(rid,), daemon=True)
    _threads[rid] = th
    th.start()


@router.post("/runs")
def create_run(p: Plan):
    pv = preview(p)
    if not pv["ok"]:
        raise HTTPException(409, " ".join(pv["blocking"]))
    for f in RUNS.glob("*.json"):
        other = read_run(f)
        if other is None:
            continue
        if other.get("status") in ACTIVE and (other.get("plan") or {}).get("target") == pv["plan"]["target"] \
                and pv["plan"]["target"]["kind"] not in ("thunder_new", "vast_new"):
            raise HTTPException(409, "A setup is already running on that machine.")
    rid = secrets.token_hex(6)
    run = {"id": rid, "created": time.time(), "plan": pv["plan"], "options": pv["info"]["options"],
           "info": pv["info"], "warnings": pv["warnings"], "status": "planned", "step": "Starting", "log": ""}
    save(run)
    start_thread(rid)
    return run


def view(run):
    return {k: v for k, v in run.items() if k != "log"} | {"log_tail": (run.get("log") or "")[-1500:]}


@router.get("/runs")
def list_runs():
    runs = []
    for f in RUNS.glob("*.json"):
        r = read_run(f)
        if r is not None:
            runs.append(r)
    runs.sort(key=lambda r: r.get("created") or 0, reverse=True)
    return [view(r) for r in runs[:20]]


@router.get("/runs/{rid}")
def get_run(rid: str):
    run = load(rid)
    return run | {"running_here": rid in _threads}


@router.post("/runs/{rid}/cancel")
def cancel_run(rid: str):
    run = load(rid)
    if run.get("status") not in ACTIVE:
        raise HTTPException(409, "That setup isn't running.")
    run["cancel"] = True
    save(run)
    if rid not in _threads:
        run.update(status="cancelled", step="Cancelled")
        save(run)
    return {"ok": True}


def resume_runs():
    try:
        files = list(RUNS.glob("*.json"))
    except OSError as e:
        log.warning("cannot list setup runs: %s", e)
        return
    for f in files:
        run = read_run(f)
        if run and run.get("status") in ACTIVE:
            start_thread(run["id"])


resume_runs()
