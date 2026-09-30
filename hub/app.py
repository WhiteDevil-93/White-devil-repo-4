"""Forge Hub API on the relay (127.0.0.1:9000, behind Caddy).

Includes every studio router. Optional modules must not take the Hub down.
"""
from __future__ import annotations

import json
import logging
import time
from pathlib import Path

from fastapi import FastAPI, HTTPException
from fastapi.responses import FileResponse, RedirectResponse
from fastapi.staticfiles import StaticFiles
from starlette.requests import Request

from contextlib import asynccontextmanager

log = logging.getLogger("forge-hub")

from colab import router as colab_router
from media import router as media_router
from hypno import router as hypno_router
from thunder import router as thunder_router
from gen import router as gen_router
from venice import router as venice_router

HUB = Path(__file__).resolve().parent
WAN = Path.home() / "wan"
RENDERS = WAN / "renders"
BOTS = HUB / "bots"

@asynccontextmanager
async def _lifespan(_app):
    try:
        from agentic import runner
        runner.start_scheduler()
        log.info("agentic scheduler thread started")
    except Exception as e:  # noqa: BLE001
        log.warning("agentic scheduler not started: %s", e)
    yield


app = FastAPI(title="Forge Hub", lifespan=_lifespan)
app.include_router(colab_router)
app.include_router(media_router)
app.include_router(hypno_router)
app.include_router(thunder_router)
app.include_router(gen_router)
app.include_router(venice_router)


# Modules that failed to mount, name -> reason. They stay optional (a partial
# checkout must still serve the rest), but a missing dependency used to make a
# whole route family vanish with one WARNING line nobody reads: python-multipart
# absent silently dropped every /api/ltx/* route. /api/manifest now reports them,
# and tools/deploy_hub.sh refuses to call a deploy good while any are listed.
MOUNT_FAILURES: dict[str, str] = {}


def _mount(mod_name: str, attr: str = "router") -> None:
    try:
        mod = __import__(mod_name)
        app.include_router(getattr(mod, attr))
        log.info("mounted %s", mod_name)
    except Exception as e:  # noqa: BLE001
        MOUNT_FAILURES[mod_name] = f"{type(e).__name__}: {e}"[:300]
        log.error("module %s NOT mounted, its routes are missing: %s", mod_name, e)


# Laptop / setup — white-devil names
_mount("laptop")
_mount("setup")
# Relay studio stack
_mount("setupbot")
_mount("vast")
_mount("ltx")
# Bridges
_mount("term_bridge")
_mount("auth")
_mount("agentic")


@app.middleware("http")
async def no_store_hub(request: Request, call_next):
    response = await call_next(request)
    path = request.url.path
    is_static_asset = any(
        path.endswith(ext) for ext in (".css", ".js", ".svg", ".png", ".jpg", ".jpeg", ".ico", ".woff", ".woff2")
    )
    if not is_static_asset and (
        path.startswith("/app/")
        or path.startswith("/api/")
        or path == "/generator.html"
        or path.startswith("/shotwriter")
    ):
        response.headers["Cache-Control"] = "no-store, no-cache, must-revalidate"
    elif is_static_asset:
        response.headers["Cache-Control"] = "no-cache, must-revalidate"
    return response


@app.get("/")
def root():
    return RedirectResponse("/app/home/")


@app.get("/laptop/term/")
@app.get("/laptop/term/{rest:path}")
def laptop_term_shim(rest: str = ""):
    """Local preview only. On the relay, Caddy sends /laptop/term/* to ttyd instead."""
    shim = HUB / "static" / "term" / "shim.html"
    if not shim.is_file():
        raise HTTPException(404, "terminal shim is not installed on this host")
    return FileResponse(shim)


def bot_screens():
    screens = []
    for f in sorted(BOTS.glob("*/bot.json")):
        try:
            bot = json.loads(f.read_text(encoding="utf-8"))
        except (OSError, ValueError) as e:
            log.warning("skipping unreadable bot manifest %s: %s", f, e)
            continue
        if not isinstance(bot, dict):
            log.warning("skipping bot manifest %s: not a JSON object", f)
            continue
        bid = f.parent.name
        screens.append(
            {
                "id": f"bot-{bid}",
                "title": bot.get("title", bid),
                "icon": bot.get("icon", "bot"),
                "url": f"/app/bots/?bot={bid}",
                "group": "bots",
            }
        )
    return screens


@app.get("/api/manifest")
def manifest():
    m = json.loads((HUB / "screens.json").read_text())
    m["screens"] = m["screens"] + bot_screens()
    m["generated"] = time.time()
    m["failed_modules"] = dict(MOUNT_FAILURES)
    return m


def _mtime(p: Path) -> float:
    try:
        return p.stat().st_mtime
    except OSError:  # deleted between the glob and the stat
        return 0.0


@app.get("/api/status")
def status():
    clips = sorted(RENDERS.glob("smoke_*.mp4"), key=_mtime, reverse=True) if RENDERS.exists() else []
    beat_log = WAN / "heartbeat.log"
    beats = []
    try:
        with beat_log.open("rb") as fh:
            fh.seek(0, 2)
            fh.seek(max(0, fh.tell() - 65536))
            tail = fh.read().decode("utf-8", errors="replace")
        beats = [l for l in tail.splitlines() if l.startswith("20")][-1:]
    except OSError:
        pass
    try:
        laptop = json.loads((WAN / "www" / "laptop.json").read_text(encoding="utf-8", errors="replace"))
    except (OSError, ValueError):
        laptop = {}
    newest, newest_age = None, None
    for c in clips:
        try:
            newest, newest_age = c, int((time.time() - c.stat().st_mtime) / 60)
            break
        except OSError:
            continue
    try:
        alerts = (RENDERS / "_ALERT.txt").read_text(errors="replace").splitlines()[-5:]
    except OSError:
        alerts = []
    return {
        "clips": len(clips),
        "newest": newest.name if newest else None,
        "newest_age_min": newest_age,
        "last_beat": beats[0] if beats else None,
        "alerts": alerts,
        "laptop": laptop,
    }


@app.get("/generator.html")
def generator_html():
    for gen in (HUB / "static" / "generator.html", HUB.parent / "relay" / "generator.html"):
        if gen.is_file():
            return FileResponse(gen)
    raise HTTPException(404, "generator.html is not installed on this host")


shotwriter_dir = HUB / "static" / "shotwriter"
if not shotwriter_dir.exists():
    shotwriter_dir = HUB.parent / "relay" / "shotwriter"
if shotwriter_dir.exists():
    app.mount("/shotwriter", StaticFiles(directory=str(shotwriter_dir), html=True), name="shotwriter")

static_dir = HUB / "static"
if static_dir.is_dir():
    app.mount("/app", StaticFiles(directory=str(static_dir), html=True), name="app")
