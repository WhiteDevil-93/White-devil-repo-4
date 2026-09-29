#!/usr/bin/env python3
"""wan-ingest: model-free pack ingest for the Colab Wan2.2 TI2V-5B gooning chain runner.

No AI calls. Validates prompt packs, merges them into gooning_chains.jsonl,
uploads to the Colab instance and queues them behind the running chain.
Run `wan-ingest schema` for accepted input formats.
"""
import argparse
import datetime as dt
import json
import os
import shutil
import subprocess
import sys
from pathlib import Path

GC = Path(os.environ.get("WAN_GC", "/mnt/c/Users/anon3/wan_outputs/gooning_chains"))
JSONL = GC / "data" / "gooning_chains.jsonl"
INCOMING = GC / "incoming"
DOWNLOADS = Path("/mnt/a/Users/anon3/Downloads")
RUNNER = Path(os.environ.get("WAN_RUNNER", "/mnt/c/Users/anon3/wan_outputs/colab_g4_ti2v5b_smoke/run_gooning_chain_colab.sh"))
COLAB_TOKEN = Path.home() / ".config/colab-cli/token.json"
COLAB_SESSIONS = Path.home() / ".config/colab-cli/sessions.json"
MAX_CLIPS = 64
FIRST_NEW_INDEX = 49

SCHEMA = """Accepted inputs for `wan-ingest add` (auto-detected):

1. Pack JSONL / JSON (preferred), one pack per line:
   {"index": 49, "title": "...", "clips": [{"clip": 1, "prompt": "...", "negative": "..."}, ...]}
   index optional (next free >= 49), clip optional (numbered in order), negative optional.
   Extra keys (bible, start_state, end_state, setting) are kept.

2. wan22-prompt-generator.html chain export: {"type": "chain", "clips": [{"index": 1, "prompt": ...}]}

3. Simple JSON: {"title": "...", "clips": ["prompt 1", "prompt 2", ...]}  or  ["prompt 1", ...]

4. Plain .txt: one prompt per non-empty line (use --title).

Rules: 1-64 clips, every prompt non-empty. Each clip is one 81-frame I2V shot at 1280x704;
clip N+1 starts from clip N's last frame, so end each prompt on a settled, stable pose.
Per-clip "negative" is appended to the runner's fixed anti-grain/exposure negatives.
Avoid "film grain"/harsh light words in prompts; use soft even diffuse light.
Packs 1-48 are originals and cannot be overwritten."""

WAITER = r"""#!/bin/bash
Q=/content/goon_ingest_queue.txt
LOG=/content/outputs/colab_g4_ti2v5b_smoke/nohup_gooning_chain.out
while [[ -s "$Q" ]]; do
  while pgrep -f "run_gooning_chain_colab" >/dev/null; do sleep 60; done
  P=$(head -1 "$Q"); sed -i '1d' "$Q"
  [[ -z "$P" ]] && continue
  echo "INGEST_QUEUE start pack $P $(date -u)" >> "$LOG"
  PACKS="$P" REFRESH_EVERY=6 bash /content/run_gooning_chain_colab_v2.sh >> "$LOG" 2>&1 || echo "INGEST_QUEUE pack $P failed rc=$? $(date -u)" >> "$LOG"
done
"""


def die(msg):
    print(f"ERROR: {msg}", file=sys.stderr)
    sys.exit(1)


def load_packs():
    # A missing/garbled catalog used to surface as a bare traceback, and a merge
    # against an empty dict would happily renumber packs from scratch.
    if not JSONL.is_file():
        die(f"pack file not found: {JSONL} (set WAN_GC if the chain lives elsewhere)")
    packs = {}
    for n, line in enumerate(JSONL.read_text(encoding="utf-8").splitlines(), 1):
        if not line.strip():
            continue
        try:
            d = json.loads(line)
            packs[int(d["index"])] = d
        except (json.JSONDecodeError, KeyError, TypeError, ValueError) as e:
            die(f"{JSONL}:{n}: unreadable pack line ({e})")
    if not packs:
        die(f"{JSONL} exists but contains no packs - refusing to treat that as a valid catalog")
    return packs


def save_packs(packs):
    backup = JSONL.with_name(f"{JSONL.name}.bak_{dt.datetime.now():%Y%m%d_%H%M%S}")
    shutil.copy2(JSONL, backup)
    tmp = JSONL.with_suffix(".tmp")
    tmp.write_text("".join(json.dumps(packs[k], ensure_ascii=False) + "\n" for k in sorted(packs)), encoding="utf-8")
    tmp.replace(JSONL)
    return backup


def wsl_path(p):
    if len(p) > 2 and p[1] == ":" and p[2] in "\\/":
        return f"/mnt/{p[0].lower()}/" + p[3:].replace("\\", "/")
    return p


def parse_file(path, title):
    path = wsl_path(path)
    if not Path(path).is_file():
        die(f"file not found: {path}")
    text = Path(path).read_text(encoding="utf-8-sig").strip()
    if not text:
        die(f"{path}: empty")
    if path.lower().endswith(".txt"):
        return [{"title": title or Path(path).stem, "clips": [l.strip() for l in text.splitlines() if l.strip()]}]
    try:
        docs = [json.loads(text)]
    except json.JSONDecodeError:
        try:
            docs = [json.loads(l) for l in text.splitlines() if l.strip()]
        except json.JSONDecodeError as e:
            die(f"{path}: not valid JSON/JSONL ({e})")
    return [{"title": title, "clips": d} if isinstance(d, list) else d for d in docs]


def normalize(raw, src):
    if not isinstance(raw, dict):
        die(f"{src}: pack must be an object or list of prompts")
    clips_in = raw.get("clips") or []
    if not isinstance(clips_in, list) or not clips_in:
        die(f"{src}: no clips")
    if len(clips_in) > MAX_CLIPS:
        die(f"{src}: {len(clips_in)} clips (max {MAX_CLIPS})")
    clips = []
    for i, c in enumerate(clips_in, 1):
        c = {"prompt": c} if isinstance(c, str) else dict(c)
        prompt = str(c.get("prompt", "")).strip()
        if not prompt:
            die(f"{src}: clip {i} has an empty prompt")
        num = c.pop("clip", None) or (c.pop("index", None) if raw.get("type", "").startswith("chain") else None) or i
        c.pop("input", None); c.pop("output_file", None); c.pop("word_count", None)
        c["prompt"] = prompt
        clips.append({"clip": int(num), **c})
        low = prompt.lower()
        for bad in ("film grain", "grainy", "hard spotlight", "harsh light"):
            if bad in low:
                print(f"WARN {src}: clip {num} prompt contains '{bad}'", file=sys.stderr)
    nums = [c["clip"] for c in clips]
    if len(set(nums)) != len(nums):
        die(f"{src}: duplicate clip numbers")
    pack = {k: v for k, v in raw.items() if k not in ("clips", "type", "part", "settings", "brief")}
    if raw.get("bible") and not pack.get("setting"):
        pack["setting"] = raw["bible"].get("setting", "")
    pack["title"] = str(pack.get("title") or Path(wsl_path(src)).stem)
    pack["clips"] = sorted(clips, key=lambda c: c["clip"])
    pack["clip_count"] = len(clips)
    return pack


def cmd_add(args, files=None):
    packs = load_packs()
    files = files if files is not None else args.files
    if not files:
        die("no input files")
    # --index applies to one file only; silently dropping it used to write the
    # pack to an index the user never asked for.
    if getattr(args, "index", None) and len(files) > 1:
        die(f"--index {args.index} needs exactly one input file ({len(files)} given)")
    added = []
    for f in files:
        for raw in parse_file(f, args.title):
            p = normalize(raw, f)
            idx = args.index if (args.index and len(files) == 1) else p.get("index")
            if idx is None:
                idx = max([FIRST_NEW_INDEX - 1, *packs.keys()]) + 1
            idx = int(idx)
            if idx in packs and "ingested_at" not in packs[idx]:
                die(f"{f}: pack {idx} is an original pack; pick another --index")
            replaced = idx in packs
            p["index"] = idx
            p.setdefault("source", "wan_ingest")
            p["ingested_at"] = dt.datetime.now().isoformat(timespec="seconds")
            packs[idx] = p
            added.append(idx)
            print(f"pack {idx}: {p['clip_count']} clips  '{p['title']}'" + (" (replaced)" if replaced else ""))
    if args.dry_run:
        print("dry run, nothing written")
        return added
    backup = save_packs(packs)
    print(f"merged into {JSONL} (backup {backup.name})")
    return added


def cmd_scan(args):
    # A glob over a directory that does not exist yields nothing, which used to
    # print the same "nothing to ingest" as a genuinely empty inbox. Say which
    # directory was searched, and fail loudly when none of them exist.
    files = []
    missing = []
    for d in (INCOMING, DOWNLOADS):
        if not d.is_dir():
            missing.append(str(d))
            continue
        found = sorted(str(p) for p in d.glob("pack_*.jsonl"))
        print(f"scan {d}: {len(found)} pack_*.jsonl file(s)")
        files.extend(found)
    for d in missing:
        print(f"scan {d}: directory does not exist", file=sys.stderr)
    if len(missing) == len((INCOMING, DOWNLOADS)):
        die("no scan directory exists - set WAN_GC / fix the Downloads path")
    files = sorted(files)
    if not files:
        print("nothing to ingest")
        return []
    args.index = None
    added = cmd_add(args, files)
    if not args.dry_run:
        done = INCOMING / "done"
        done.mkdir(parents=True, exist_ok=True)
        for f in files:
            target = done / Path(f).name
            if target.exists():  # never silently overwrite an earlier ingest
                target = done / f"{Path(f).stem}_{dt.datetime.now():%Y%m%d_%H%M%S}{Path(f).suffix}"
            shutil.move(f, target)
    return added


def cmd_list(args):
    for idx, p in sorted(load_packs().items()):
        tag = "ingested" if "ingested_at" in p else "original"
        print(f"{idx:3d}  {len(p.get('clips') or []):2d} clips  {tag:8s}  {p.get('title','')[:70]}")


def cmd_show(args):
    p = load_packs().get(args.index) or die(f"no pack {args.index}")
    print(json.dumps(p, indent=2, ensure_ascii=False))


WANBOT_CONN = Path.home() / ".config/wanbot/connection.json"
WANBOT_MODEL = "wan22-5b-turbo-lora"
SEED_BASE = 20260924


def pack_to_chain(p, frames=81):
    """gooning_chains.jsonl pack -> wanbot/generator chain spec (resumes via chain_id goon-pNN)."""
    idx = int(p["index"])
    clips = p.get("clips") or []
    return {
        "type": "chain",
        "chain_id": f"goon-p{idx:02d}",
        "settings": {"size": "1280*704", "frames_per_clip": frames, "steps": 28, "guide_scale": 4.0,
                     "sample_shift": 5.0, "linking": "continuous", "clip_count": len(clips)},
        "brief": {"idea": p.get("title", f"pack {idx}")},
        "clips": [{"index": int(c["clip"]), "title": c.get("title", ""), "prompt": c["prompt"],
                   "negative": c.get("negative", ""), "input": {"mode": "i2v"}} for c in clips],
    }


def wanbot_conn():
    import os
    url, tok = os.environ.get("WANBOT_URL"), os.environ.get("WANBOT_TOKEN")
    if not (url and tok) and WANBOT_CONN.exists():
        c = json.loads(WANBOT_CONN.read_text())
        url, tok = url or c.get("url"), tok or c.get("token")
    if not (url and tok):
        die(f"no runner URL/token: set WANBOT_URL + WANBOT_TOKEN or create {WANBOT_CONN}")
    return url.rstrip("/"), tok


def cmd_to_chain(args):
    p = load_packs().get(args.index) or die(f"no pack {args.index}")
    txt = json.dumps(pack_to_chain(p, args.frames), indent=2, ensure_ascii=False)
    if args.out:
        Path(args.out).write_text(txt, encoding="utf-8")
        print(f"wrote {args.out}")
    else:
        print(txt)


def cmd_send(args):
    import requests
    url, tok = wanbot_conn()
    packs = load_packs()
    for idx in args.indexes:
        p = packs.get(idx) or die(f"no pack {idx}")
        spec = pack_to_chain(p, args.frames)
        spec["runner"] = {"model": args.model, "name": f"goon_p{idx:02d}", "seed": SEED_BASE + idx * 100,
                          "start_image_path": "/content/gooning_start_soft.png"}
        r = requests.post(f"{url}/jobs", json=spec, headers={"Authorization": f"Bearer {tok}"}, timeout=60)
        if r.status_code >= 300:
            die(f"pack {idx}: runner said {r.status_code} {r.text[:300]}")
        j = r.json()
        print(f"pack {idx}: job {j.get('id')} queued on {url} ({len(spec['clips'])} clips, resumes chain goon-p{idx:02d})")


def refresh_colab():
    from google.auth.transport.requests import Request
    from google.oauth2.credentials import Credentials
    from colab_cli.client import Client, Prod
    import requests
    tok = json.loads(COLAB_TOKEN.read_text())
    creds = Credentials(token=tok.get("token"), refresh_token=tok.get("refresh_token"), token_uri=tok.get("token_uri"),
                        client_id=tok.get("client_id"), client_secret=tok.get("client_secret"), scopes=tok.get("scopes"))
    if not creds.valid:
        creds.refresh(Request())
        tok["token"] = creds.token
        COLAB_TOKEN.write_text(json.dumps(tok))
    sess = requests.Session()
    sess.headers["Authorization"] = f"Bearer {creds.token}"
    a = Client(Prod(), sess).list_assignments()[0]
    st = json.loads(COLAB_SESSIONS.read_text())
    st["colab"]["token"] = a.runtime_proxy_info.token
    st["colab"]["url"] = a.runtime_proxy_info.url
    COLAB_SESSIONS.write_text(json.dumps(st, indent=2))


def colab(*a, stdin=None):
    return subprocess.run(["colab", *a], input=stdin, capture_output=True, text=True, timeout=120)


def upload(local, remote):
    r = colab("upload", "-s", "colab", str(local), remote)
    if r.returncode:
        die(f"upload {local} failed: {r.stderr.strip() or r.stdout.strip()}")


def cmd_push(args):
    try:
        refresh_colab()
    except Exception as e:
        die(f"colab auth refresh failed: {e}")
    waiter = Path("/tmp/goon_queue_ingest.sh")
    waiter.write_text(WAITER)
    upload(JSONL, "/content/gooning_chains.jsonl.new")
    upload(RUNNER, "/content/run_gooning_chain_colab_v2.sh")
    upload(waiter, "/content/goon_queue_ingest.sh")
    remote = ("mv -f /content/gooning_chains.jsonl.new /content/gooning_chains.jsonl && "
              "chmod +x /content/goon_queue_ingest.sh /content/run_gooning_chain_colab_v2.sh")
    queue = [int(q) for q in (args.queue or [])]
    if queue:
        remote += (" && for p in " + " ".join(map(str, queue)) + "; do echo $p >> /content/goon_ingest_queue.txt; done"
                   " && (pgrep -f goon_queue_ingest.sh >/dev/null || nohup bash /content/goon_queue_ingest.sh >/dev/null 2>&1 &)")
    script = f"{remote}\necho INGEST_OK; echo QUEUE: $(cat /content/goon_ingest_queue.txt 2>/dev/null | tr '\\n' ' ')\nexit\n"
    try:
        r = colab("console", "-s", "colab", stdin=script)
        out = r.stdout + r.stderr
    except subprocess.TimeoutExpired as e:
        out = (e.stdout or b"").decode(errors="replace") if isinstance(e.stdout, bytes) else (e.stdout or "")
    if "INGEST_OK" in out:
        q = next((l for l in out.splitlines() if l.startswith("QUEUE:")), "")
        print("uploaded to instance" + (f"; queued {queue} (runs after current chain). {q.strip()}" if queue else ""))
    else:
        die("upload done but console confirmation not seen; rerun `wan-ingest push`")


def main():
    ap = argparse.ArgumentParser(prog="wan-ingest", description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    sub.add_parser("schema", help="print accepted input formats")
    a = sub.add_parser("add", help="validate + merge pack file(s)")
    a.add_argument("files", nargs="+")
    a.add_argument("--index", type=int, help="pack index (single file only; default next free >= 49)")
    a.add_argument("--title")
    a.add_argument("--dry-run", action="store_true")
    a.add_argument("--push", action="store_true", help="upload after merge")
    a.add_argument("--queue", action="store_true", help="upload and queue the added packs")
    s = sub.add_parser("scan", help=f"ingest pack_*.jsonl from {INCOMING} and {DOWNLOADS}")
    s.add_argument("--title")
    s.add_argument("--dry-run", action="store_true")
    s.add_argument("--push", action="store_true")
    s.add_argument("--queue", action="store_true")
    sub.add_parser("list", help="list packs")
    sh = sub.add_parser("show", help="print one pack")
    sh.add_argument("index", type=int)
    p = sub.add_parser("push", help="upload packs + runner to Colab")
    p.add_argument("--queue", nargs="*", metavar="INDEX", help="pack indexes to run after the current chain")
    tc = sub.add_parser("to-chain", help="print/write a pack as a generator/wanbot chain JSON")
    tc.add_argument("index", type=int)
    tc.add_argument("--out")
    tc.add_argument("--frames", type=int, default=81)
    se = sub.add_parser("send", help="queue pack(s) on the wanbot runner (URL/token from env or ~/.config/wanbot)")
    se.add_argument("indexes", type=int, nargs="+")
    se.add_argument("--model", default=WANBOT_MODEL)
    se.add_argument("--frames", type=int, default=81)
    args = ap.parse_args()

    if args.cmd == "schema":
        print(SCHEMA)
    elif args.cmd in ("add", "scan"):
        added = cmd_add(args) if args.cmd == "add" else cmd_scan(args)
        if added and not args.dry_run and (args.push or args.queue):
            args.queue = added if args.queue else None
            cmd_push(args)
    elif args.cmd == "list":
        cmd_list(args)
    elif args.cmd == "show":
        cmd_show(args)
    elif args.cmd == "push":
        cmd_push(args)
    elif args.cmd == "to-chain":
        cmd_to_chain(args)
    elif args.cmd == "send":
        cmd_send(args)


if __name__ == "__main__":
    main()
