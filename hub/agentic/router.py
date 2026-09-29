"""Side-track HTTP API — /api/agentic/* (does not touch /api/venice/*)."""
from __future__ import annotations

import time
from typing import Any, Optional

from fastapi import APIRouter, HTTPException
from pydantic import BaseModel, Field

from . import runner, store

router = APIRouter(prefix="/api/agentic", tags=["agentic-side"])


class ConfigIn(BaseModel):
    enabled: Optional[bool] = None
    max_parallel_jobs: Optional[int] = None
    default_max_steps: Optional[int] = None
    vision_model: Optional[str] = None
    chat_model: Optional[str] = None


class PermissionsIn(BaseModel):
    allow_laptop_commands: Optional[bool] = None
    allow_file_write: Optional[bool] = None
    allow_file_delete: Optional[bool] = None
    allow_civitai_download: Optional[bool] = None
    allow_spend: Optional[bool] = None
    require_confirm_destructive: Optional[bool] = None
    daily_tool_budget: Optional[int] = None


class MemoryIn(BaseModel):
    preferences: Optional[dict[str, Any]] = None
    notes: Optional[list[Any]] = None
    projects: Optional[dict[str, Any]] = None
    append_note: Optional[str] = None


class JobIn(BaseModel):
    goal: str = Field(..., min_length=1, max_length=8000)
    max_steps: Optional[int] = Field(default=None, ge=1, le=40)
    model: Optional[str] = None


class ScheduleIn(BaseModel):
    goal: str = Field(..., min_length=1, max_length=4000)
    every_minutes: int = Field(..., ge=5, le=10080)
    label: Optional[str] = None
    enabled: bool = True


@router.get("/status")
def status():
    store.ensure()
    cfg = store.config()
    jobs = store.list_jobs()
    return {
        "side": "agentic",
        "disrupts_venice": False,
        "enabled": bool(cfg.get("enabled")),
        "config": cfg,
        "jobs_running": sum(1 for j in jobs if j.get("status") == "running"),
        "jobs_total": len(jobs),
        "data_dir": str(store.DATA),
        "hint": "PUT /api/agentic/config {\"enabled\": true} to allow background goal runners. Venice chat is unchanged.",
    }


@router.get("/config")
def get_config():
    return store.config()


@router.put("/config")
def put_config(body: ConfigIn):
    patch = {k: v for k, v in body.model_dump().items() if v is not None}
    out = store.save_config(patch)
    store.audit("config", patch)
    if out.get("enabled"):
        runner.kick_queued()
    return out


@router.get("/permissions")
def get_permissions():
    return store.permissions()


@router.get("/gates")
def get_gates():
    """The shared risky-tool blocklist: same list the runner enforces and the chat UI confirms."""
    perms = store.permissions()
    usage = store._read("tool_usage.json") or dict(store.DEFAULT_TOOL_USAGE)
    return {
        "confirm_tools": store.CONFIRM_TOOLS,
        "dangerous_cmd": store.DANGEROUS_CMD,
        "require_confirm_destructive": bool(perms.get("require_confirm_destructive")),
        "daily_tool_budget": perms.get("daily_tool_budget"),
        "tool_usage": usage,
    }


@router.put("/permissions")
def put_permissions(body: PermissionsIn):
    patch = {k: v for k, v in body.model_dump().items() if v is not None}
    out = store.save_permissions(patch)
    store.audit("permissions", patch)
    return out


@router.get("/memory")
def get_memory():
    return store.memory()


@router.put("/memory")
def put_memory(body: MemoryIn):
    patch = {k: v for k, v in body.model_dump().items() if v is not None}
    return store.save_memory(patch)


@router.get("/jobs")
def get_jobs():
    runner.kick_queued()
    return {"jobs": store.list_jobs()[:40]}


@router.get("/jobs/{jid}")
def get_job(jid: str):
    job = store.get_job(jid)
    if not job:
        raise HTTPException(404, "job not found")
    return job


@router.post("/jobs")
def post_job(body: JobIn):
    job = runner.start_job(body.goal.strip(), max_steps=body.max_steps, model=body.model)
    return job


@router.post("/jobs/{jid}/stop")
def post_stop(jid: str):
    return runner.stop_job(jid)


@router.get("/schedules")
def get_schedules():
    return {"schedules": store.list_schedules()}


@router.post("/schedules")
def post_schedule(body: ScheduleIn):
    rows = store.list_schedules()
    sid = store.new_job_id()
    row = {
        "id": sid,
        "goal": body.goal.strip(),
        "every_minutes": body.every_minutes,
        "label": body.label or body.goal[:40],
        "enabled": body.enabled,
        "created": time.time(),
        "next_run": time.time() + body.every_minutes * 60,
        "last_run": None,
        "last_job_id": None,
    }
    rows.insert(0, row)
    store.save_schedules(rows)
    store.audit("schedule_add", {"id": sid})
    return row


@router.delete("/schedules/{sid}")
def delete_schedule(sid: str):
    rows = [r for r in store.list_schedules() if r.get("id") != sid]
    store.save_schedules(rows)
    return {"ok": True}


@router.post("/schedules/tick")
def tick_schedules():
    """Fire due schedules. Kept for cron-compat; the hub's own scheduler thread calls the same logic."""
    fired = runner.tick_due_schedules()
    if not fired and not store.config().get("enabled"):
        return {"ok": True, "fired": [], "reason": "runners disabled"}
    return {"ok": True, "fired": fired}


class WatcherIn(BaseModel):
    kind: str = Field(default="new_render", max_length=40)
    goal: str = Field(default="Review the newest render {render}", max_length=4000)
    enabled: bool = True


@router.get("/watchers")
def get_watchers():
    return {"watchers": store.list_watchers()}


@router.post("/watchers")
def post_watcher(body: WatcherIn):
    rows = store.list_watchers()
    row = {
        "id": store.new_job_id(),
        "kind": body.kind.strip() or "new_render",
        "goal": body.goal.strip(),
        "enabled": bool(body.enabled),
        "created": time.time(),
        "last_seen": None,
        "last_fired": None,
    }
    rows.insert(0, row)
    store.save_watchers(rows)
    store.audit("watcher_add", {"id": row["id"], "kind": row["kind"]})
    return row


@router.delete("/watchers/{wid}")
def delete_watcher(wid: str):
    rows = [w for w in store.list_watchers() if w.get("id") != wid]
    store.save_watchers(rows)
    return {"ok": True}
