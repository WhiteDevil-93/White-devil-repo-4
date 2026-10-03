#!/usr/bin/env python3
"""Command-line client for a wanbot runner.

  export WANBOT_URL=https://xxxx.trycloudflare.com  WANBOT_TOKEN=...
  python wanbot_client.py send chain.json [--model wan22-5b] [--image start.png] [--seed 42] [--wait out.mp4]
  python wanbot_client.py send part01.json part02.json part03.json      # parts of one chain, in order
  python wanbot_client.py generate --idea "..." --clips 8 [--mode chain] [--wait out.mp4]
  python wanbot_client.py status [JOB_ID] | list | cancel JOB_ID | retry JOB_ID | download JOB_ID out.mp4 | health
"""
import argparse
import base64
import json
import os
import sys
import time

import requests


def client(args):
    url = (args.url or os.environ.get("WANBOT_URL") or "").rstrip("/")
    token = args.token or os.environ.get("WANBOT_TOKEN")
    if not url or not token:
        sys.exit("Set --url/--token or WANBOT_URL/WANBOT_TOKEN")
    s = requests.Session()
    s.headers["Authorization"] = f"Bearer {token}"
    return url, s


def call(s, method, url, **kw):
    r = s.request(method, url, timeout=kw.pop("timeout", 60), **kw)
    if r.status_code >= 400:
        try:
            msg = r.json().get("detail")
        except Exception:
            msg = r.text[:500]
        sys.exit(f"HTTP {r.status_code}: {msg}")
    return r


def runner_opts(args):
    o = {}
    for k in ("model", "seed", "variant", "name", "steps", "guide_scale", "shift"):
        v = getattr(args, k, None)
        if v not in (None, ""):
            o[k] = v
    if getattr(args, "fresh", False):
        o["fresh"] = True
    if getattr(args, "image", None):
        with open(args.image, "rb") as f:
            o["start_image_b64"] = base64.b64encode(f.read()).decode()
    return o


def show(j):
    p = j.get("progress") or {}
    line = f"{j['id']}  {j['status']:<18} {p.get('done', 0)}/{p.get('total', 0)}  {j.get('model')}  {(j.get('name') or '')[:50]}"
    if j.get("error"):
        line += f"\n    error: {j['error'][:300]}"
    print(line)


def wait(url, s, jid, out=None):
    last = None
    while True:
        j = call(s, "GET", f"{url}/jobs/{jid}", params={"log": 1}).json()
        p = j.get("progress") or {}
        state = (j["status"], p.get("done"))
        if state != last:
            print(f"{time.strftime('%H:%M:%S')} {j['status']} {p.get('done', 0)}/{p.get('total', 0)}"
                  + (f"  {j['log'][-1][26:]}" if j.get("log") else ""), flush=True)
            last = state
        if j["status"] in ("done", "error", "cancelled"):
            break
        time.sleep(10)
    if j["status"] != "done":
        sys.exit(f"Job {j['status']}: {j.get('error') or ''}")
    if out and j.get("final_url"):
        download(url, s, jid, out)
    elif out:
        print("Part finished; final video will be stitched once all parts are rendered.")


def download(url, s, jid, out):
    with s.get(f"{url}/jobs/{jid}/final", stream=True, timeout=600) as r:
        if r.status_code != 200:
            sys.exit(f"Not ready (HTTP {r.status_code})")
        with open(out, "wb") as f:
            for chunk in r.iter_content(1 << 20):
                f.write(chunk)
    print(f"saved {out}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--url")
    ap.add_argument("--token")
    sub = ap.add_subparsers(dest="cmd", required=True)

    def add_runner(p):
        p.add_argument("--model")
        p.add_argument("--seed", type=int)
        p.add_argument("--image", help="start image for i2v first clip")
        p.add_argument("--name")
        p.add_argument("--steps", type=int)
        p.add_argument("--guide-scale", dest="guide_scale", type=float)
        p.add_argument("--shift", type=float)
        p.add_argument("--fresh", action="store_true", help="re-render clips that already exist for this chain")
        p.add_argument("--wait", metavar="OUT_MP4", nargs="?", const="", help="wait for completion, optionally download")

    p = sub.add_parser("send")
    p.add_argument("files", nargs="+")
    p.add_argument("--variant", type=int, help="which variant of a single JSON (1-based)")
    add_runner(p)

    p = sub.add_parser("generate")
    p.add_argument("--brief", help="JSON file with brief fields")
    p.add_argument("--idea")
    p.add_argument("--mode", default="chain", choices=["chain", "single"])
    p.add_argument("--clips", type=int)
    p.add_argument("--beats", help="one beat per line, or ';'-separated")
    p.add_argument("--linking", choices=["continuous", "cut"])
    p.add_argument("--people", type=int)
    p.add_argument("--style")
    p.add_argument("--must-not", dest="must_not")
    p.add_argument("--portrait", action="store_true")
    p.add_argument("--variant", type=int)
    add_runner(p)

    for name in ("status", "cancel", "retry"):
        sp = sub.add_parser(name)
        sp.add_argument("job", nargs="?" if name == "status" else None)
    sub.add_parser("list")
    sub.add_parser("health")
    p = sub.add_parser("download")
    p.add_argument("job")
    p.add_argument("out")
    args = ap.parse_args()
    url, s = client(args)

    if args.cmd == "health":
        print(json.dumps(call(s, "GET", f"{url}/health").json(), indent=2))
    elif args.cmd == "list" or (args.cmd == "status" and not args.job):
        for j in call(s, "GET", f"{url}/jobs").json():
            show(j)
    elif args.cmd == "status":
        j = call(s, "GET", f"{url}/jobs/{args.job}", params={"log": 15}).json()
        show(j)
        for line in j.get("log", []):
            print("   ", line)
    elif args.cmd in ("cancel", "retry"):
        show(call(s, "POST", f"{url}/jobs/{args.job}/{args.cmd}").json())
    elif args.cmd == "download":
        download(url, s, args.job, args.out)
    elif args.cmd == "send":
        ids = []
        for i, path in enumerate(args.files):
            with open(path, "r", encoding="utf-8") as f:
                spec = json.load(f)
            opts = runner_opts(args)
            if i > 0:
                opts.pop("start_image_b64", None)
            j = call(s, "POST", f"{url}/jobs", json={**spec, "runner": opts}).json()
            print(f"{path} -> {j['id']}")
            ids.append(j["id"])
        if args.wait is not None:
            for jid in ids[:-1]:
                wait(url, s, jid)
            wait(url, s, ids[-1], args.wait or None)
    elif args.cmd == "generate":
        brief = {}
        if args.brief:
            with open(args.brief, "r", encoding="utf-8") as f:
                brief = json.load(f)
        for k, v in (("idea", args.idea), ("clips", args.clips), ("linking", args.linking),
                     ("people_count", args.people), ("style", args.style), ("must_not", args.must_not)):
            if v not in (None, ""):
                brief[k] = v
        if args.beats:
            brief["beats"] = [b.strip() for b in args.beats.replace(";", "\n").splitlines() if b.strip()]
        if args.portrait:
            brief["orientation"] = "portrait"
        if args.image:
            brief["first_clip_mode"] = "i2v"
        j = call(s, "POST", f"{url}/generate", json={"mode": args.mode, "brief": brief, "runner": runner_opts(args)}).json()
        print(f"job {j['id']}")
        if args.wait is not None:
            wait(url, s, j["id"], args.wait or None)


if __name__ == "__main__":
    main()
