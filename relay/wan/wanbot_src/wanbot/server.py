import argparse
import hmac
import json
import os
import subprocess

import uvicorn
from fastapi import Body, Depends, FastAPI, HTTPException, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse

from . import __version__, tunnel
from .backends import BackendManager
from .config import load_config
from .jobs import TERMINAL, JobStore, Worker
from .watcher import InboxWatcher


def gpu_info():
    try:
        out = subprocess.run(["nvidia-smi", "--query-gpu=name,memory.used,memory.total", "--format=csv,noheader"],
                             capture_output=True, text=True, timeout=10).stdout.strip()
        return out.splitlines()
    except Exception:
        return []


def create_app(cfg, store, manager):
    app = FastAPI(title="wanbot", version=__version__)
    app.add_middleware(CORSMiddleware, allow_origins=cfg["server"]["cors_origins"], allow_methods=["*"],
                       allow_headers=["*"])
    token = cfg["server"]["token"]
    models = cfg["models"]

    def auth(request: Request):
        h = request.headers.get("authorization", "")
        supplied = h[7:].strip() if h.lower().startswith("bearer ") else request.query_params.get("token", "")
        if not supplied or not hmac.compare_digest(supplied, token):
            raise HTTPException(401, "Invalid or missing token")

    def job_or_404(jid):
        job = store.get(jid)
        if not job:
            raise HTTPException(404, "No such job")
        return job

    def check_model(runner):
        m = (runner or {}).get("model")
        if m and m not in models:
            raise HTTPException(400, f"Unknown model '{m}'. Available: {', '.join(models)}")

    @app.get("/health", dependencies=[Depends(auth)])
    def health():
        return {"ok": True, "version": __version__, "models": list(models), "default_model": cfg["default_model"],
                "loaded_model": manager.current.name if manager.current else None, "gpu": gpu_info(),
                "queued": store.queue.qsize(), "openrouter": bool(cfg["openrouter"].get("api_key")),
                "openrouter_model": cfg["openrouter"].get("model")}

    @app.get("/models", dependencies=[Depends(auth)])
    def list_models():
        keys = ("type", "fps", "sizes", "max_frames", "frame_multiple", "frame_offset", "supports")
        return {n: {k: m.get(k) for k in keys if k in m} for n, m in models.items()}

    @app.post("/jobs", dependencies=[Depends(auth)])
    def create_job(payload: dict = Body(...)):
        runner = payload.get("runner") or {}
        spec = payload.get("spec") or {k: v for k, v in payload.items() if k != "runner"}
        if spec.get("type") not in ("single", "chain", "chain_part"):
            raise HTTPException(400, "Body must be generator JSON with type single, chain or chain_part")
        check_model(runner)
        return store.view(store.create("spec", spec, runner, source="api"))

    @app.post("/generate", dependencies=[Depends(auth)])
    def generate(payload: dict = Body(...)):
        brief = payload.get("brief")
        mode = payload.get("mode", "chain")
        if not isinstance(brief, dict) or not brief.get("idea"):
            raise HTTPException(400, "Body needs {\"mode\": \"chain\"|\"single\", \"brief\": {\"idea\": ...}}")
        if mode not in ("chain", "single"):
            raise HTTPException(400, "mode must be chain or single")
        if not cfg["openrouter"].get("api_key"):
            raise HTTPException(400, "OPENROUTER_API_KEY is not configured on the runner")
        check_model(payload.get("runner"))
        return store.view(store.create("brief", brief, payload.get("runner"), source="api", mode=mode))

    @app.get("/jobs", dependencies=[Depends(auth)])
    def jobs(limit: int = 50):
        return [store.view(j, log_lines=0) for j in store.list()[:limit]]

    @app.get("/jobs/{jid}", dependencies=[Depends(auth)])
    def job(jid: str, log: int = 30):
        return store.view(job_or_404(jid), log_lines=log)

    @app.get("/jobs/{jid}/spec", dependencies=[Depends(auth)])
    def job_spec(jid: str):
        job_or_404(jid)
        p = os.path.join(store.job_dir(jid), "spec.json")
        if not os.path.exists(p):
            raise HTTPException(404, "Prompts not generated yet")
        with open(p, "r", encoding="utf-8") as f:
            return json.load(f)

    @app.post("/jobs/{jid}/cancel", dependencies=[Depends(auth)])
    def cancel(jid: str):
        job_or_404(jid)
        return store.view(store.cancel(jid))

    @app.post("/jobs/{jid}/retry", dependencies=[Depends(auth)])
    def retry(jid: str):
        job = job_or_404(jid)
        if job["status"] not in TERMINAL and job["status"] != "waiting":
            raise HTTPException(409, f"Job is {job['status']}")
        return store.view(store.retry(jid))

    @app.get("/jobs/{jid}/final", dependencies=[Depends(auth)])
    def final(jid: str):
        job = job_or_404(jid)
        if not job.get("final") or not os.path.exists(job["final"]):
            raise HTTPException(404, "Final video not ready")
        return FileResponse(job["final"], media_type="video/mp4", filename=f"{jid}.mp4")

    @app.get("/jobs/{jid}/clips/{n}", dependencies=[Depends(auth)])
    def clip(jid: str, n: int):
        job = job_or_404(jid)
        p = os.path.join(job.get("chain_dir") or "", f"clip_{n:02d}.mp4")
        if not job.get("chain_dir") or not os.path.exists(p):
            raise HTTPException(404, "Clip not ready")
        return FileResponse(p, media_type="video/mp4", filename=f"{jid}_clip_{n:02d}.mp4")

    return app


def main():
    ap = argparse.ArgumentParser(description="wanbot GPU runner")
    ap.add_argument("--config", default="config.yaml")
    ap.add_argument("--host")
    ap.add_argument("--port", type=int)
    ap.add_argument("--tunnel", action="store_true", help="expose via a Cloudflare quick tunnel")
    ap.add_argument("--no-watch", action="store_true", help="disable inbox folder watcher")
    args = ap.parse_args()

    cfg = load_config(args.config)
    if args.host:
        cfg["server"]["host"] = args.host
    if args.port:
        cfg["server"]["port"] = args.port
    host, port = cfg["server"]["host"], int(cfg["server"]["port"])
    work = cfg["paths"]["work_dir"]

    manager = BackendManager(cfg)
    store = JobStore(cfg)
    Worker(store, manager, cfg).start()
    inbox = cfg["paths"].get("inbox_dir")
    if inbox and not args.no_watch:
        InboxWatcher(store, inbox).start()

    public = None
    if args.tunnel:
        public, _ = tunnel.start(port, work)

    info = {"url": public or f"http://{host}:{port}", "local_url": f"http://127.0.0.1:{port}",
            "token": cfg["server"]["token"], "models": list(cfg["models"]), "default_model": cfg["default_model"],
            "inbox": inbox, "outputs": cfg["paths"].get("outputs_dir")}
    with open(os.path.join(work, "connection.json"), "w") as f:
        json.dump(info, f, indent=2)
    if cfg["paths"].get("outputs_dir"):
        with open(os.path.join(cfg["paths"]["outputs_dir"], "connection.txt"), "w") as f:
            f.write(f"URL:   {info['url']}\nToken: {info['token']}\n")

    print("=" * 60)
    print(f"wanbot {__version__} ready")
    print(f"  URL:    {info['url']}")
    print(f"  Token:  {info['token']}" + ("  (generated; set WANBOT_TOKEN to fix it)" if cfg["_token_generated"] else ""))
    print(f"  Models: {', '.join(info['models'])} (default {info['default_model']})")
    if inbox:
        print(f"  Inbox:  {inbox}")
    print("=" * 60, flush=True)
    uvicorn.run(create_app(cfg, store, manager), host=host, port=port, log_level="warning")


if __name__ == "__main__":
    main()
