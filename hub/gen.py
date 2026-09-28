"""Prompt-chain generation on the relay, so the generator page can sleep (screen off) mid-chain.

The page sends the same system prompt and form lines it would have used, and this runs the batch loop
(the page's runChain) server-side against OpenRouter. The page polls for progress and picks up the finished chain.
"""
import json
import re
import threading
import time
import urllib.error
import urllib.request
import uuid
from pathlib import Path
from typing import Optional

from fastapi import APIRouter, HTTPException
from fastapi.responses import FileResponse
from pydantic import BaseModel

router = APIRouter(prefix="/api/gen")
DIR = Path.home() / "hub" / "gen_jobs"
OUT = Path.home() / "wan" / "prompts"
KEY = Path.home() / ".openrouter_key"
NTFY = Path.home() / ".wan_ntfy_topic"
DIR.mkdir(parents=True, exist_ok=True)
OUT.mkdir(parents=True, exist_ok=True)
_lock = threading.Lock()


class Chain(BaseModel):
    idea: str
    system: str
    shared: list[str]
    beats: list[str] = []
    link: str = "continuous"
    total: int
    chunk: int
    model: str
    temperature: float = 0.8
    key: Optional[str] = None
    base: dict = {}
    meta: dict = {}


def save(job):
    with _lock:
        (DIR / f"{job['id']}.json").write_text(json.dumps(job))


def load(jid):
    if not re.match(r"^[\w-]+$", jid):
        raise HTTPException(400)
    try:
        return json.loads((DIR / f"{jid}.json").read_text())
    except FileNotFoundError:
        raise HTTPException(404, "No such generation job")


def chain_msg(req, b):
    lines = list(req["shared"])
    lines.append(f"Total clips in the full video: {req['total']}")
    lines.append("Linking: " + ("CONTINUOUS (each clip after the first starts from the previous clip's last frame via image-to-video)"
                                if req["link"] == "continuous" else "CUT (independent clips, hard cuts between them)"))
    if req["beats"]:
        lines.append(f"Story beats for the whole video (spread across all {req['total']} clips in order; if fewer beats than clips, "
                     f"spread them; if more, merge):\n" + "\n".join(f"{i + 1}. {x}" for i, x in enumerate(req["beats"])))
    if b["count"] > 1:
        n = b["end"] - b["start"] + 1
        lines.append(f"\nTHIS REQUEST: batch {b['index']} of {b['count']}. Write ONLY clips {b['start']} to {b['end']} ({n} clips), "
                     f"covering the matching portion of the story. The \"clips\" array must contain exactly {n} items, in order.")
    if b["bible"]:
        bb = b["bible"]
        lines.append("\nContinuity bible (fixed — copy it into the \"bible\" field UNCHANGED and use it verbatim in every prompt):\n"
                     f"Characters: {bb.get('characters', '')}\nSetting: {bb.get('setting', '')}\nStyle: {bb.get('style', '')}")
    if b["prev_titles"]:
        lines.append(f"\nStory so far (clips 1–{b['start'] - 1}):\n" + "\n".join(f"{i + 1}. {t}" for i, t in enumerate(b["prev_titles"])))
    if b["prev_end"]:
        lines.append(f"\nClip {b['start'] - 1} ended on: {b['prev_end']}" + (
            f"\nClip {b['start']}'s start_state must equal this exactly; it is image-to-video from that frame."
            if req["link"] == "continuous" else ""))
    return "\n".join(lines)


SHORTHAND = [
    ('\\bbig\\s*bro\\b', 'the older, broader man'),
    ('\\b(?:lil|little)\\s*bro\\b', 'the younger, slimmer man'),
    ('\\bbros\\b', 'the two men'),
    ('\\bbro\\b', 'man'),
    ('\\beach\\s*others\\b(?!\\s+(?:penis|penises|cock|dick|body|bodies|mouth))', "each other's penis"),
    ('\\bgooning\\b', 'slow, entranced masturbation with a glazed expression'),
    ('\\bgoon(?:s|ed)?\\b', 'masturbate slowly in a trance'),
    ('\\bedging\\b', 'stroking to the brink of orgasm, stopping with breath held, then slowly starting again'),
    ('\\bedged\\b', 'stopped just before orgasm'),
    ('\\bcum\\s*shots?\\b', 'visible ejaculation'),
    ('\\bcumming\\b', 'ejaculating'),
    ('\\bcums\\b', 'ejaculates'),
    ('\\bcum(?:med)?\\b', 'ejaculate'),
    ('\\bpre-?cum\\b', 'a clear drop of fluid at the tip of the penis'),
    ('\\b(?:shoot|shoots|shooting|blow|blows|blowing)\\s+(?:his|their|a)\\s+loads?\\b', 'ejaculating'),
    ('\\bbust(?:s|ing)?\\s+a\\s+nut\\b', 'ejaculating'),
    ('\\bnutting\\b', 'ejaculating'),
    ('\\b(?:jerk|jack)(?:ing|s|ed)?\\s+off\\b', 'masturbating'),
    ('\\b(?:jerk|jack)(?:ing|s|ed)?\\s+each\\s+other(?:\\s+off)?\\b', "stroking each other's penis"),
    ('\\bwank(?:ing|s|ed)?\\b', 'masturbating'),
    ('\\bfap(?:ping|s|ped)?\\b', 'masturbating'),
    ('\\bj/?o\\b', 'masturbation'),
    ('\\bhand\\s*jobs?\\b|\\bhj\\b', "one man stroking the other man's penis with his hand"),
    ('\\bblow\\s*jobs?\\b|\\bbj\\b|\\bsucking\\s+off\\b|\\bsucks?\\s+(?:him|each\\s+other)\\s+off\\b', "one man takes the other's penis into his mouth, lips around it, head moving slowly up and down"),
    ('\\bdeep\\s*throat(?:ing|s)?\\b', 'taking the penis fully into his mouth, down to the base'),
    ('\\bfrot(?:ting|tage)?\\b', 'the two men pressing their erect penises together and rubbing them against each other'),
    ('\\bboners?\\b|\\bhard-?ons?\\b', 'erection'),
    ('\\bcocks\\b|\\bdicks\\b', 'penises'),
    ('\\bcock\\b|\\bdick\\b', 'penis'),
    ('\\bballs\\b', 'testicles'),
    ('\\beach\\s*others\\b', "each other's"),
]


def plain_words(text):
    """Same rewrite as generator_relay.js: WAN only renders visible actions, so slang in a reply becomes plain words."""
    for pat, to in SHORTHAND:
        text = re.sub(pat, to, text, flags=re.I)
    return text


def extract_json(text):
    t = re.sub(r"```json|```", "", text).strip()
    a, b = t.find("{"), t.rfind("}")
    if a == -1 or b == -1:
        raise ValueError("No JSON in model reply")
    return json.loads(t[a:b + 1])


class KeyRejected(RuntimeError):
    pass


def call_model(key, model, temperature, system, user, max_tokens):
    body = json.dumps({"model": model, "temperature": temperature, "max_tokens": max_tokens,
                       "messages": [{"role": "system", "content": system}, {"role": "user", "content": user}]}).encode()
    req = urllib.request.Request("https://openrouter.ai/api/v1/chat/completions", data=body, method="POST", headers={
        "Authorization": f"Bearer {key}", "Content-Type": "application/json",
        "HTTP-Referer": "https://84-12-112-249.sslip.io", "X-Title": "WAN 2.2 Prompt Generator"})
    try:
        with urllib.request.urlopen(req, timeout=300) as r:
            data = json.loads(r.read())
    except urllib.error.HTTPError as e:
        try:
            msg = json.loads(e.read()).get("error", {}).get("message")
        except Exception:
            msg = None
        if e.code == 401:
            raise KeyRejected("OpenRouter rejected the API key (it's wrong, incomplete or deleted). Paste the full key again under OpenRouter settings.")
        raise RuntimeError(msg or f"HTTP {e.code}")
    return ((data.get("choices") or [{}])[0].get("message") or {}).get("content") or ""


def notify(text):
    try:
        topic = NTFY.read_text().strip()
        urllib.request.urlopen(urllib.request.Request(f"https://ntfy.sh/{topic}", data=text.encode(),
                                                      headers={"Title": "Prompt generator"}), timeout=20)
    except Exception:
        pass


def slug(s):
    return (re.sub(r"[^a-z0-9]+", "-", (s or "wan").lower()).strip("-")[:40] or "wan")


def run(jid, req, key):
    job = load(jid)
    try:
        while job["next"] <= job["count"]:
            i = job["next"]
            start, end = (i - 1) * req["chunk"] + 1, min(i * req["chunk"], req["total"])
            want = end - start + 1
            prev = job["chain"]["clips"]
            msg = chain_msg(req, {"index": i, "count": job["count"], "start": start, "end": end, "bible": job["chain"].get("bible"),
                                  "prev_titles": [c.get("title", "") for c in prev],
                                  "prev_end": prev[-1].get("end_state") if prev else None})
            job.update(status="running", batch=i, updated=time.time())
            save(job)
            last = None
            for attempt in range(3):
                try:
                    parsed = extract_json(plain_words(call_model(key, req["model"], req["temperature"], req["system"], msg, 1200 + want * 700)))
                    if not isinstance(parsed.get("clips"), list) or not parsed["clips"]:
                        raise ValueError("reply had no clips")
                    break
                except KeyRejected:
                    raise
                except Exception as e:
                    last = e
                    job["log"].append(f"batch {i} attempt {attempt + 1}: {e}")
                    save(job)
                    time.sleep(5 * (attempt + 1))
            else:
                raise RuntimeError(f"Batch {i}: {last}")
            if not job["chain"].get("bible"):
                job["chain"]["bible"] = parsed.get("bible") or {}
            job["chain"]["clips"] = prev + parsed["clips"][:want]
            job["next"] = i + 1
            save(job)
        name = f"wan_chain_{slug(req['idea'])}_{time.strftime('%Y%m%d-%H%M')}.json"
        (OUT / name).write_text(json.dumps(job["chain"], indent=1))
        job.update(status="done", file=name, updated=time.time())
        save(job)
        notify(f"Chain ready: {req['idea'][:60]} ({len(job['chain']['clips'])} clips). Open the generator to see it.")
    except Exception as e:
        job.update(status="error", error=str(e)[:400], updated=time.time())
        save(job)
        notify(f"Chain failed: {req['idea'][:60]} — {str(e)[:120]}")


@router.post("/chain")
def start(c: Chain):
    key = (c.key or "").strip()
    if key and not re.fullmatch(r"sk-or-v1-[0-9a-f]{64}", key):
        raise HTTPException(400, f"That OpenRouter key looks incomplete ({len(key)} characters; a full key is 73, starting sk-or-v1-). Paste it again.")
    if key:
        if not KEY.exists() or KEY.read_text().strip() != key:
            KEY.write_text(key)
            KEY.chmod(0o600)
    elif KEY.exists():
        key = KEY.read_text().strip()
    if not key:
        raise HTTPException(400, "Add your OpenRouter API key under OpenRouter settings.")
    if c.total < 1 or c.chunk < 1 or c.total > 500:
        raise HTTPException(400, "Bad clip count")
    req = c.model_dump(exclude={"key"})
    jid = time.strftime("%Y%m%d-%H%M%S-") + uuid.uuid4().hex[:4]
    job = {"id": jid, "created": time.time(), "updated": time.time(), "status": "queued", "idea": c.idea, "total": c.total,
           "count": -(-c.total // c.chunk), "next": 1, "batch": 0, "meta": c.meta, "req": req, "log": [],
           "chain": dict(c.base, bible=None, clips=[])}
    save(job)
    threading.Thread(target=run, args=(jid, req, key), daemon=True).start()
    return {"id": jid, "count": job["count"]}


def view(job):
    return {k: job.get(k) for k in ("id", "created", "updated", "status", "idea", "total", "count", "next", "batch", "meta",
                                    "chain", "file", "error", "log")}


@router.get("/jobs/{jid}")
def get(jid: str):
    return view(load(jid))


@router.get("/jobs")
def recent():
    jobs = sorted(DIR.glob("*.json"), key=lambda p: p.stat().st_mtime, reverse=True)[:20]
    out = []
    for p in jobs:
        j = json.loads(p.read_text())
        out.append({k: j.get(k) for k in ("id", "created", "updated", "status", "idea", "total", "count", "next", "file", "error")}
                   | {"clips": len(j["chain"]["clips"])})
    return out


@router.post("/jobs/{jid}/resume")
def resume(jid: str):
    job = load(jid)
    if job["status"] not in ("error", "interrupted"):
        raise HTTPException(400, "Job isn't stopped")
    if not KEY.exists():
        raise HTTPException(400, "No OpenRouter key saved on the relay")
    job.update(status="queued", error=None)
    save(job)
    threading.Thread(target=run, args=(jid, job["req"], KEY.read_text().strip()), daemon=True).start()
    return {"ok": True}


class File(BaseModel):
    name: str
    text: str


def safe_name(name):
    n = re.sub(r"[^\w.-]+", "_", name).strip("._")[:120]
    if not n:
        raise HTTPException(400, "Bad file name")
    return n


@router.post("/file")
def put_file(f: File):
    if len(f.text) > 5_000_000:
        raise HTTPException(413, "Too big")
    n = safe_name(f.name)
    (OUT / n).write_text(f.text)
    return {"url": f"/api/gen/file/{n}"}


@router.get("/file/{name}")
def get_file(name: str):
    p = OUT / safe_name(name)
    if not p.is_file():
        raise HTTPException(404)
    return FileResponse(p, filename=p.name, media_type="application/json")


def mark_interrupted():
    for p in DIR.glob("*.json"):
        j = json.loads(p.read_text())
        if j.get("status") in ("queued", "running"):
            j["status"] = "interrupted"
            p.write_text(json.dumps(j))


def resume_interrupted():
    """Hub restarted mid-chain: carry on where it stopped if the key is saved."""
    mark_interrupted()
    if not KEY.exists():
        return
    for p in DIR.glob("*.json"):
        j = json.loads(p.read_text())
        if j.get("status") == "interrupted" and time.time() - j.get("updated", 0) < 6 * 3600:
            resume(j["id"])


resume_interrupted()
