#!/usr/bin/env python3
"""Push clips the laptop is missing through the reverse tunnel; record laptop status for the page."""
import json
import time
import urllib.request
from pathlib import Path

WAN = Path.home() / "wan"
RENDERS = WAN / "renders"
STATE = WAN / "www" / "laptop.json"
AGENT = "http://127.0.0.1:18765"
TOKEN = (Path.home() / ".wan_laptop_token").read_text().strip()


def get(path):
    with urllib.request.urlopen(AGENT + path, timeout=15) as r:
        return json.load(r)


def put(path, file):
    data = file.read_bytes()
    req = urllib.request.Request(AGENT + path, data=data, method="PUT",
                                 headers={"X-Token": TOKEN, "Content-Length": str(len(data))})
    with urllib.request.urlopen(req, timeout=300) as r:
        return json.load(r)


def main():
    prev = json.loads(STATE.read_text()) if STATE.exists() else {}
    try:
        ping = get("/ping")
    except Exception as e:
        prev.update(online=False, error=str(e)[:120], checked=time.time())
        STATE.write_text(json.dumps(prev))
        return
    have = set(get("/list"))
    pushed = 0
    for p in sorted(RENDERS.glob("*.mp4")):
        if p.name not in have and put(f"/renders/{p.name}", p).get("stored"):
            pushed += 1
    alert = RENDERS / "_ALERT.txt"
    if alert.exists():
        put("/renders/_ALERT.txt", alert)
    STATE.write_text(json.dumps({"online": True, "last_seen": time.time(), "checked": time.time(),
                                 "pushed": pushed, **ping, "clips": ping["clips"] + pushed}))
    print(f"laptop online pushed={pushed}")


if __name__ == "__main__":
    main()
