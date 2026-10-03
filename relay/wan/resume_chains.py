#!/usr/bin/env python3
"""Carry non-pack wanbot chains (e.g. best-friends V3) across Colab runtime restarts.

~/wan/resume_chains.json: [{"chain_id", "spec", "runner": {...}, "story": bool}]
  bundle DIR   copy each unfinished chain's clips (from ~/wan/renders mirror) into DIR/<chain_id>/
  submit       queue each unfinished chain on wanbot ahead of the queued packs
Chains whose clips are all on the relay are dropped from the list.
"""
import json
import shutil
import sys
import time
from pathlib import Path

import requests

HOME = Path.home()
LIST = HOME / "wan" / "resume_chains.json"
RENDERS = HOME / "wan" / "renders"
URL = "http://127.0.0.1:18900"
HDR = {"Authorization": "Bearer " + (HOME / ".wanbot_token").read_text().strip()}


def entries():
    try:
        items = json.loads(LIST.read_text())
    except (FileNotFoundError, ValueError):
        return []
    left = []
    for e in items:
        total = len(json.load(open(e["spec"]))["clips"])
        have = sorted(RENDERS.glob(f"smoke_{e['chain_id']}_c[0-9][0-9]_wanbot.mp4"))
        if len(have) < total:
            left.append(dict(e, have=have, total=total))
    LIST.write_text(json.dumps([{k: v for k, v in e.items() if k not in ("have", "total")} for e in left], indent=1))
    return left


def bundle(out):
    for e in entries():
        d = Path(out) / e["chain_id"]
        d.mkdir(parents=True, exist_ok=True)
        for f in e["have"]:
            n = f.name.split("_c")[-1][:2]
            shutil.copy(f, d / f"clip_{n}.mp4")
        if e.get("story"):
            (d / ".story").touch()
        print(f"{e['chain_id']}: {len(e['have'])}/{e['total']} clips bundled")


def submit():
    todo = entries()
    if not todo:
        return
    jobs = requests.get(URL + "/jobs", headers=HDR, timeout=30).json()
    jobs = jobs.get("jobs", jobs) if isinstance(jobs, dict) else jobs
    if any(j.get("chain_id") == e["chain_id"] and j.get("status") in ("queued", "rendering") for e in todo for j in jobs):
        return
    queued = [j["id"] for j in sorted(jobs, key=lambda j: j.get("created") or "") if j.get("status") == "queued"]
    for jid in queued:
        requests.post(f"{URL}/jobs/{jid}/cancel", headers=HDR, timeout=30)
    try:
        for e in todo:
            spec = json.load(open(e["spec"]))
            spec["chain_id"] = e["chain_id"]
            spec["runner"] = e["runner"]
            r = requests.post(URL + "/jobs", json=spec, headers=HDR, timeout=60)
            print(f"{e['chain_id']}: resubmitted {r.status_code} {r.json().get('id')} ({len(e['have'])}/{e['total']} already done)")
    finally:
        for jid in queued:
            requests.post(f"{URL}/jobs/{jid}/retry", headers=HDR, timeout=30)
        # a job that had just started when cancelled can reject the first retry while it is still stopping
        for _ in range(6):
            stuck = [jid for jid in queued
                     if requests.get(f"{URL}/jobs/{jid}", headers=HDR, timeout=30).json().get("status") == "cancelled"]
            if not stuck:
                break
            time.sleep(10)
            for jid in stuck:
                requests.post(f"{URL}/jobs/{jid}/retry", headers=HDR, timeout=30)


if __name__ == "__main__":
    {"bundle": lambda: bundle(sys.argv[2]), "submit": submit}[sys.argv[1]]()
