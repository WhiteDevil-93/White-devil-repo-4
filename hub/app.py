"""Forge Hub API on the relay (127.0.0.1:9000, behind Caddy).

The Android app builds its tabs from GET /api/manifest, so adding a screen or a bot here
shows up on the phone without reinstalling the app.
"""
import json
import time
from pathlib import Path

from fastapi import FastAPI
from fastapi.responses import FileResponse, RedirectResponse
from fastapi.staticfiles import StaticFiles
from starlette.requests import Request

from colab import router as colab_router
from media import router as media_router
from hypno import router as hypno_router
from thunder import router as thunder_router
from gen import router as gen_router
from venice import router as venice_router
from laptop import router as laptop_router

HUB = Path(__file__).resolve().parent
WAN = Path.home() / "wan"
RENDERS = WAN / "renders"
BOTS = HUB / "bots"

app = FastAPI(title="Forge Hub")
app.include_router(colab_router)
app.include_router(media_router)
app.include_router(hypno_router)
app.include_router(thunder_router)
app.include_router(gen_router)
app.include_router(venice_router)
app.include_router(laptop_router)


@app.middleware("http")
async def no_store_hub(request: Request, call_next):
    response = await call_next(request)
    path = request.url.path
    if path.startswith("/app/") or path.startswith("/api/manifest") or path.startswith("/api/venice") or path.startswith("/api/laptop/"):
        response.headers["Cache-Control"] = "no-store"
    return response


@app.get("/")
def root():
    return RedirectResponse("/app/home/")


@app.get("/laptop/term/")
@app.get("/laptop/term/{rest:path}")
def laptop_term_shim(rest: str = ""):
    """Local preview only. On the relay, Caddy sends /laptop/term/* to ttyd instead."""
    return FileResponse(HUB / "static" / "term" / "shim.html")


def bot_screens():
    screens = []
    for f in sorted(BOTS.glob("*/bot.json")):
        try:
            bot = json.loads(f.read_text())
        except ValueError:
            continue
        bid = f.parent.name
        screens.append({"id": f"bot-{bid}", "title": bot.get("title", bid), "icon": bot.get("icon", "bot"),
                        "url": f"/app/bots/?bot={bid}", "group": "bots"})
    return screens


@app.get("/api/manifest")
def manifest():
    m = json.loads((HUB / "screens.json").read_text())
    m["screens"] = m["screens"] + bot_screens()
    m["generated"] = time.time()
    return m


@app.get("/api/status")
def status():
    clips = sorted(RENDERS.glob("smoke_*.mp4"), key=lambda p: p.stat().st_mtime, reverse=True) if RENDERS.exists() else []
    beat_log = WAN / "heartbeat.log"
    beats = []
    if beat_log.exists():
        beats = [l for l in beat_log.read_text(errors="replace").splitlines() if l.startswith("20")][-1:]
    try:
        laptop = json.loads((WAN / "www" / "laptop.json").read_text())
    except (FileNotFoundError, ValueError):
        laptop = {}
    alerts = (RENDERS / "_ALERT.txt").read_text().splitlines()[-5:] if (RENDERS / "_ALERT.txt").exists() else []
    return {
        "clips": len(clips),
        "newest": clips[0].name if clips else None,
        "newest_age_min": int((time.time() - clips[0].stat().st_mtime) / 60) if clips else None,
        "last_beat": beats[0] if beats else None,
        "alerts": alerts,
        "laptop": laptop,
    }


app.mount("/app", StaticFiles(directory=str(HUB / "static"), html=True), name="app")
