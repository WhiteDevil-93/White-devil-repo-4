import base64
import io
import json
import os
import queue
import random
import re
import shutil
import threading
import time
import traceback
import uuid
from datetime import datetime, timezone

import requests

from . import media, openrouter
from .backends import BackendError, ClipRequest

TERMINAL = {"done", "error", "cancelled"}
CLIP_ATTEMPTS = 3
JOB_AUTO_RETRIES = 2


def transient(e):
    s = f"{type(e).__name__} {e}".lower()
    return any(k in s for k in ("out of memory", "outofmemory", "cuda", "cudnn", "nccl", "timed out"))


def free_gpu():
    import gc
    gc.collect()
    try:
        import torch
        torch.cuda.empty_cache()
    except Exception:
        pass


class Wait(Exception):
    pass


class Cancelled(Exception):
    pass


def now():
    return datetime.now(timezone.utc).isoformat()


def slug(s, n=40):
    s = re.sub(r"[^a-z0-9]+", "-", str(s or "").lower()).strip("-")
    return (s[:n].strip("-")) or "wan"


def parse_size(s):
    m = re.match(r"\s*(\d+)\s*[x*×]\s*(\d+)", str(s or ""))
    return (int(m.group(1)), int(m.group(2))) if m else (1280, 704)


def _save_image(data, dst):
    from PIL import Image
    Image.open(io.BytesIO(data)).convert("RGB").save(dst, "PNG")
    return dst


class JobStore:
    def __init__(self, cfg):
        self.cfg = cfg
        self.root = os.path.join(cfg["paths"]["work_dir"], "jobs")
        self.chains_root = os.path.join(cfg["paths"]["work_dir"], "chains")
        os.makedirs(self.root, exist_ok=True)
        os.makedirs(self.chains_root, exist_ok=True)
        self.jobs = {}
        self.events = {}
        self.lock = threading.RLock()
        self.queue = queue.Queue()
        self._load()

    # ---------- persistence ----------
    def job_dir(self, jid):
        return os.path.join(self.root, jid)

    def _persist(self, job):
        d = self.job_dir(job["id"])
        os.makedirs(d, exist_ok=True)
        tmp = os.path.join(d, "job.json.tmp")
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(job, f, indent=2, ensure_ascii=False)
        os.replace(tmp, os.path.join(d, "job.json"))

    def _load(self):
        found = []
        for name in os.listdir(self.root):
            p = os.path.join(self.root, name, "job.json")
            if os.path.exists(p):
                try:
                    with open(p, "r", encoding="utf-8") as f:
                        found.append(json.load(f))
                except Exception:
                    pass
        # the job that was mid-render when the server stopped resumes first
        for job in sorted(found, key=lambda j: (j.get("status") != "rendering", j.get("created", ""))):
            self.jobs[job["id"]] = job
            self.events[job["id"]] = threading.Event()
            if job.get("status") not in TERMINAL:
                job["status"] = "queued"
                job.setdefault("log", []).append(f"{now()} requeued after restart (finished clips are kept)")
                self._persist(job)
                self.queue.put(job["id"])

    # ---------- api ----------
    def create(self, kind, payload, runner=None, source="api", mode=None):
        runner = dict(runner or {})
        jid = datetime.now().strftime("%Y%m%d-%H%M%S-") + uuid.uuid4().hex[:6]
        d = self.job_dir(jid)
        os.makedirs(d, exist_ok=True)

        start_image = None
        b64 = runner.pop("start_image_b64", None)
        url = runner.pop("start_image_url", None)
        path = runner.pop("start_image_path", None)
        dst = os.path.join(d, "start.png")
        if b64:
            if "," in b64 and b64.strip().startswith("data:"):
                b64 = b64.split(",", 1)[1]
            start_image = _save_image(base64.b64decode(b64), dst)
        elif url:
            r = requests.get(url, timeout=60)
            r.raise_for_status()
            start_image = _save_image(r.content, dst)
        elif path and os.path.exists(path):
            with open(path, "rb") as f:
                start_image = _save_image(f.read(), dst)

        if kind == "brief":
            with open(os.path.join(d, "brief.json"), "w", encoding="utf-8") as f:
                json.dump(payload, f, indent=2, ensure_ascii=False)
            spec_type = mode or "chain"
            name = runner.get("name") or payload.get("idea", "")
            chain_id = None
        else:
            with open(os.path.join(d, "spec.json"), "w", encoding="utf-8") as f:
                json.dump(payload, f, indent=2, ensure_ascii=False)
            spec_type = payload.get("type")
            name = runner.get("name") or (payload.get("brief") or {}).get("idea", "")
            chain_id = payload.get("chain_id")

        job = {
            "id": jid, "created": now(), "updated": now(), "status": "queued", "source": source,
            "kind": kind, "mode": mode, "spec_type": spec_type, "name": name, "chain_id": chain_id,
            "model": runner.get("model") or self.cfg["default_model"], "runner": runner,
            "start_image": start_image, "part": payload.get("part") if kind == "spec" else None,
            "clips": [], "progress": {"done": 0, "total": 0}, "final": None, "output_copy": None,
            "error": None, "log": [],
        }
        with self.lock:
            self.jobs[jid] = job
            self.events[jid] = threading.Event()
            self._persist(job)
        self.log(jid, f"created ({source}, {spec_type}, model {job['model']})")
        self.queue.put(jid)
        return job

    def get(self, jid):
        return self.jobs.get(jid)

    def update(self, jid, **kw):
        with self.lock:
            job = self.jobs[jid]
            job.update(kw)
            job["updated"] = now()
            self._persist(job)
            return job

    def log(self, jid, msg):
        line = f"{now()} {msg}"
        print(f"[{jid}] {msg}", flush=True)
        with self.lock:
            job = self.jobs.get(jid)
            if job is None:
                return
            job["log"] = (job.get("log") or [])[-299:] + [line]
            job["updated"] = now()
            self._persist(job)

    def cancel(self, jid):
        job = self.jobs.get(jid)
        if not job:
            return None
        self.events[jid].set()
        if job["status"] in ("queued", "waiting"):
            self.update(jid, status="cancelled")
        return job

    def retry(self, jid):
        job = self.jobs.get(jid)
        if not job:
            return None
        self.events[jid] = threading.Event()
        self.update(jid, status="queued", error=None)
        self.log(jid, "retry requested")
        self.queue.put(jid)
        return job

    def set_final_for_chain(self, chain_dir, final, copy_path):
        with self.lock:
            for job in self.jobs.values():
                if job.get("chain_dir") == chain_dir:
                    job["final"] = final
                    job["output_copy"] = copy_path
                    self._persist(job)

    def view(self, job, log_lines=30):
        v = {k: job.get(k) for k in ("id", "created", "updated", "status", "source", "spec_type", "name", "model",
                                     "chain_id", "part", "progress", "error", "output_copy")}
        v["clips"] = job.get("clips", [])
        v["final_url"] = f"/jobs/{job['id']}/final" if job.get("final") and os.path.exists(job["final"]) else None
        v["log"] = (job.get("log") or [])[-log_lines:] if log_lines else []
        return v

    def list(self):
        return sorted(self.jobs.values(), key=lambda j: j["created"], reverse=True)


class Worker(threading.Thread):
    def __init__(self, store, manager, cfg):
        super().__init__(daemon=True, name="wanbot-worker")
        self.store, self.manager, self.cfg = store, manager, cfg

    def run(self):
        while True:
            jid = self.store.queue.get()
            job = self.store.get(jid)
            if not job or job["status"] in TERMINAL:
                continue
            try:
                self.process(job)
            except Wait as w:
                if job["status"] != "waiting":
                    self.store.log(jid, f"waiting: {w}")
                self.store.update(jid, status="waiting")
                threading.Timer(15, self.store.queue.put, [jid]).start()
            except Cancelled:
                self.store.update(jid, status="cancelled")
                self.store.log(jid, "cancelled")
            except Exception as e:
                self.store.update(jid, status="error", error=str(e)[:2000])
                self.store.log(jid, "ERROR " + traceback.format_exc()[-3000:])
                n = int(job.get("auto_retries") or 0)
                if n < JOB_AUTO_RETRIES and transient(e):
                    free_gpu()
                    self.store.update(jid, auto_retries=n + 1)
                    self.store.log(jid, f"auto-retry {n + 1}/{JOB_AUTO_RETRIES} in 60 s")
                    threading.Timer(60, self._auto_retry, [jid]).start()

    def _auto_retry(self, jid):
        job = self.store.get(jid)
        if job and job["status"] == "error":
            self.store.retry(jid)

    # ---------- plan ----------
    def _plan(self, spec, runner):
        t = spec.get("type")
        st = spec.get("settings") or {}
        plan = {
            "type": t, "size": parse_size(st.get("size")), "frames": int(st.get("frames_per_clip") or 121),
            "steps": int(st.get("steps") or self.cfg["defaults"]["steps"]),
            "guide": float(st.get("guide_scale") or self.cfg["defaults"]["guide_scale"]),
            "shift": float(st.get("sample_shift") or self.cfg["defaults"]["shift"]),
        }
        if t == "single":
            vs = spec.get("variants") or []
            if not vs:
                raise ValueError("single JSON has no variants")
            vi = max(1, min(len(vs), int(runner.get("variant") or 1)))
            v = vs[vi - 1]
            mode = (spec.get("brief") or {}).get("first_clip_mode", "t2v")
            plan.update(link="cut", total=1, clips=[{"index": 1, "title": v.get("title", ""), "prompt": v.get("prompt", ""),
                                                      "negative": v.get("negative", ""), "mode": mode}])
        elif t in ("chain", "chain_part"):
            clips = spec.get("clips") or []
            if not clips:
                raise ValueError("chain JSON has no clips")
            plan.update(link=st.get("linking", "continuous"),
                        total=int(st.get("clip_count") or len(clips)),
                        clips=[{"index": int(c.get("index", i + 1)), "title": c.get("title", ""),
                                "prompt": c.get("prompt", ""), "negative": c.get("negative", ""),
                                "mode": (c.get("input") or {}).get("mode", "t2v")} for i, c in enumerate(clips)])
            if t == "chain":
                plan["total"] = max(plan["total"], max(c["index"] for c in plan["clips"]))
        else:
            raise ValueError(f"Unsupported JSON type '{t}'")
        for k, key in (("steps", "steps"), ("guide", "guide_scale"), ("shift", "shift")):
            if runner.get(key) not in (None, ""):
                plan[k] = type(plan[k])(runner[key])
        return plan

    # ---------- main ----------
    def process(self, job):
        jid = job["id"]
        store = self.store
        log = lambda m: store.log(jid, m)  # noqa: E731
        cancel = store.events[jid]
        jdir = store.job_dir(jid)
        spec_path = os.path.join(jdir, "spec.json")

        if job["kind"] == "brief" and not os.path.exists(spec_path):
            store.update(jid, status="generating_prompts")
            with open(os.path.join(jdir, "brief.json"), "r", encoding="utf-8") as f:
                brief = json.load(f)
            if job.get("mode") == "single":
                spec = openrouter.generate_single(self.cfg["openrouter"], brief)
            else:
                spec = openrouter.generate_chain(self.cfg["openrouter"], brief, log)
            with open(spec_path, "w", encoding="utf-8") as f:
                json.dump(spec, f, indent=2, ensure_ascii=False)
            store.update(jid, chain_id=spec.get("chain_id"), spec_type=spec["type"])
            log(f"prompts generated ({spec['type']}, {len(spec.get('clips') or spec.get('variants') or [])} items)")

        with open(spec_path, "r", encoding="utf-8") as f:
            spec = json.load(f)
        runner = job.get("runner") or {}
        plan = self._plan(spec, runner)

        backend = self.manager.get(job["model"], log)
        store.update(jid, model=backend.name)
        chain_id = slug(spec.get("chain_id") or jid, 64)
        chain_dir = os.path.join(store.chains_root, f"{chain_id}__{slug(backend.name, 40)}")
        os.makedirs(chain_dir, exist_ok=True)
        indices = [c["index"] for c in plan["clips"]]

        if runner.get("fresh") and not job.get("_fresh_done"):
            for i in indices:
                for p in (f"clip_{i:02d}.mp4", f"last_{i:02d}.png"):
                    if os.path.exists(os.path.join(chain_dir, p)):
                        os.remove(os.path.join(chain_dir, p))
            for p in ("final.mp4", "seed.txt"):
                if os.path.exists(os.path.join(chain_dir, p)):
                    os.remove(os.path.join(chain_dir, p))
            store.update(jid, _fresh_done=True)

        if job.get("start_image") and 1 in indices:
            shutil.copy(job["start_image"], os.path.join(chain_dir, "start.png"))

        seed_file = os.path.join(chain_dir, "seed.txt")
        if runner.get("seed") not in (None, ""):
            seed = int(runner["seed"])
        elif os.path.exists(seed_file):
            seed = int(open(seed_file).read().strip())
        else:
            seed = int(self.cfg["defaults"].get("seed", 42))
        if seed < 0:
            seed = random.randint(0, 2**31 - 1)
        with open(seed_file, "w") as f:
            f.write(str(seed))

        frames = backend.normalize_frames(plan["frames"])
        w, h = backend.pick_size(*plan["size"])
        steps = int(backend.param("steps", plan["steps"]))
        guide = float(backend.param("guide_scale", plan["guide"]))
        shift = float(backend.param("shift", plan["shift"]))
        if frames != plan["frames"] or (w, h) != plan["size"]:
            log(f"adjusted for {backend.name}: {plan['size'][0]}x{plan['size'][1]}@{plan['frames']}f -> {w}x{h}@{frames}f")

        clip_state = []
        for c in plan["clips"]:
            out = os.path.join(chain_dir, f"clip_{c['index']:02d}.mp4")
            done = os.path.exists(out) and os.path.getsize(out) > 0
            clip_state.append({"index": c["index"], "title": c["title"], "status": "done" if done else "pending",
                               "seconds": None, "error": None})
        store.update(jid, status="rendering", chain_dir=chain_dir, clips=clip_state,
                     progress={"done": sum(s["status"] == "done" for s in clip_state), "total": len(clip_state)})
        log(f"rendering clips {indices[0]}-{indices[-1]} of {plan['total']} with {backend.name} "
            f"({w}x{h}, {frames}f, steps {steps}, cfg {guide}, shift {shift}, seed {seed}, {plan['link']})")

        for n, c in enumerate(plan["clips"]):
            i = c["index"]
            out = os.path.join(chain_dir, f"clip_{i:02d}.mp4")
            if clip_state[n]["status"] == "done":
                continue
            if cancel.is_set():
                raise Cancelled()

            image = None
            if i == 1 and c["mode"] == "i2v":
                sp = os.path.join(chain_dir, "start.png")
                if os.path.exists(sp):
                    image = sp
                else:
                    log("clip 1 is image-to-video but no start image was supplied; rendering it as text-to-video")
            elif i > 1 and plan["link"] == "continuous":
                prev = os.path.join(chain_dir, f"clip_{i - 1:02d}.mp4")
                if not os.path.exists(prev):
                    if spec.get("type") == "chain_part":
                        raise Wait(f"clip {i - 1} (previous part) not rendered yet")
                    raise RuntimeError(f"previous clip {i - 1} missing")
                image = os.path.join(chain_dir, f"last_{i - 1:02d}.png")
                if not os.path.exists(image):
                    media.last_frame(prev, image)

            mode = "i2v" if image else "t2v"
            if not backend.supports(mode):
                raise BackendError(f"model '{backend.name}' does not support {mode} (needed for clip {i})")

            clip_state[n]["status"] = "rendering"
            store.update(jid, clips=clip_state)
            tmp = os.path.join(chain_dir, f"clip_{i:02d}.tmp.mp4")
            if os.path.exists(tmp):
                os.remove(tmp)
            req = ClipRequest(job_id=jid, index=i, prompt=c["prompt"], negative=c["negative"], output=tmp,
                              frames=frames, width=w, height=h, fps=backend.fps, seed=seed, steps=steps,
                              guide_scale=guide, shift=shift, image=image)
            backend.cancel_event = cancel
            t0 = time.time()
            for attempt in range(1, CLIP_ATTEMPTS + 1):
                try:
                    backend.generate(req)
                    break
                except Exception as e:
                    if cancel.is_set():
                        raise Cancelled()
                    if attempt < CLIP_ATTEMPTS and transient(e):
                        log(f"clip {i}: attempt {attempt} failed ({str(e)[:120]}); retrying")
                        free_gpu()
                        time.sleep(5)
                        continue
                    clip_state[n].update(status="error", error=str(e)[:500])
                    store.update(jid, clips=clip_state)
                    raise
            os.replace(tmp, out)
            clip_state[n].update(status="done", seconds=round(time.time() - t0, 1))
            store.update(jid, clips=clip_state,
                         progress={"done": sum(s["status"] == "done" for s in clip_state), "total": len(clip_state)})
            log(f"clip {i}/{plan['total']} done in {time.time() - t0:.0f}s")

        all_files = [os.path.join(chain_dir, f"clip_{i:02d}.mp4") for i in range(1, plan["total"] + 1)]
        missing = [i + 1 for i, p in enumerate(all_files) if not os.path.exists(p)]
        if missing:
            store.update(jid, status="done")
            log(f"part rendered; waiting for clips {missing[0]}-{missing[-1]} from other parts before stitching")
            return

        store.update(jid, status="stitching")
        final = os.path.join(chain_dir, "final.mp4")
        if plan["total"] == 1:
            shutil.copy(all_files[0], final)
        else:
            norm_dir = os.path.join(chain_dir, "_norm")
            os.makedirs(norm_dir, exist_ok=True)
            ref = media.probe(all_files[0])
            size = (ref["width"], ref["height"]) if ref.get("width") else None
            normed = []
            for idx, p in enumerate(all_files):
                dst = os.path.join(norm_dir, f"n_{idx + 1:02d}.mp4")
                media.normalize(p, dst, backend.fps, trim_first=(plan["link"] == "continuous" and idx > 0), size=size)
                normed.append(dst)
            media.concat(normed, final)
            shutil.rmtree(norm_dir, ignore_errors=True)

        copy_path = None
        outdir = self.cfg["paths"].get("outputs_dir")
        if outdir:
            copy_path = os.path.join(outdir, f"{slug(job.get('name'))}_{chain_id[:12]}_{slug(backend.name, 20)}.mp4")
            shutil.copy(final, copy_path)
        store.set_final_for_chain(chain_dir, final, copy_path)
        store.update(jid, status="done", final=final, output_copy=copy_path)
        log(f"done -> {copy_path or final}")
