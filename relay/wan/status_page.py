#!/usr/bin/env python3
"""Render ~/wan/www/index.html: latest heartbeat, alerts, newest clips."""
import html
import json
import time
from pathlib import Path

WAN = Path.home() / "wan"
RENDERS = WAN / "renders"
WWW = WAN / "www"


def tail(path, n, pred=lambda s: True):
    try:
        lines = [l for l in path.read_text(errors="replace").splitlines() if pred(l)]
    except FileNotFoundError:
        return []
    return lines[-n:]


def main():
    beats = tail(WAN / "heartbeat.log", 6, lambda l: l.startswith("20"))
    alerts = tail(RENDERS / "_ALERT.txt", 5)
    clips = sorted(RENDERS.glob("smoke_*.mp4"), key=lambda p: p.stat().st_mtime, reverse=True)
    newest_age = int((time.time() - clips[0].stat().st_mtime) / 60) if clips else -1
    ok = not alerts and 0 <= newest_age <= 45
    items = "\n".join(
        f'<li><a href="/clips/{html.escape(p.name)}">{html.escape(p.name.replace("smoke_", "").split("_1280")[0])}</a>'
        f' <small>{time.strftime("%d %b %H:%M", time.localtime(p.stat().st_mtime))}</small></li>'
        for p in clips[:40]
    )
    latest = clips[0].name if clips else ""
    try:
        lap = json.loads((WWW / "laptop.json").read_text())
    except (FileNotFoundError, ValueError):
        lap = {}
    if lap.get("online"):
        laptop = f'laptop <b>online</b> · {lap.get("clips")} clips · {lap.get("free_gb")} GB free'
    elif lap.get("last_seen"):
        laptop = f'laptop <b>offline</b> since {time.strftime("%d %b %H:%M", time.localtime(lap["last_seen"]))} (clips wait on the relay)'
    else:
        laptop = "laptop <b>not connected yet</b>"
    page = f"""<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><meta http-equiv="refresh" content="300">
<title>wan relay</title><style>
body{{font:15px system-ui;background:#111;color:#ddd;margin:0;padding:12px}}a{{color:#8cf}}
.s{{padding:10px;border-radius:8px;background:{'#153' if ok else '#512'}}}pre{{white-space:pre-wrap;font-size:12px}}
video{{width:100%;border-radius:8px}}li{{margin:4px 0}}nav a{{margin-right:14px}}</style></head><body>
<nav><a href="/">status</a><a href="/clips/">all clips</a><a href="/generator.html">generator</a><a href="/laptop/files/">laptop files</a><a href="/laptop/term/">laptop terminal</a><a href="/laptop/files/files/mnt/a/HypnoForge/">HypnoForge</a></nav>
<div class="s"><b>{'OK' if ok else 'CHECK'}</b> · {len(clips)} clips · newest {newest_age} min ago</div>
<p>{laptop}</p>
{'<h3>Alerts</h3><pre>' + html.escape(chr(10).join(alerts)) + '</pre>' if alerts else ''}
{f'<h3>Latest</h3><video src="/clips/{html.escape(latest)}" controls muted playsinline loop></video>' if latest else ''}
<h3>Heartbeat</h3><pre>{html.escape(chr(10).join(beats))}</pre>
<h3>Newest clips</h3><ul>{items}</ul>
<p><a href="/app/forgehub.apk">Install the Forge Hub Android app</a></p>
<small>updated {time.strftime('%Y-%m-%d %H:%M %Z')}</small></body></html>"""
    tmp = WWW / "index.html.tmp"
    tmp.write_text(page)
    tmp.replace(WWW / "index.html")


if __name__ == "__main__":
    main()
