"""JSON persistence under ~/hub/agentic_data/ — isolated from venice chats & ltx jobs."""
from __future__ import annotations

import json
import os
import threading
import time
import uuid
from pathlib import Path
from typing import Any

DATA = Path.home() / "hub" / "agentic_data"
LOCK = threading.RLock()

DEFAULT_CONFIG = {
    "enabled": False,  # background runners off by default — APIs still work
    "max_parallel_jobs": 1,
    "default_max_steps": 16,
    "vision_model": "qwen3-vl-235b-a22b",
    "chat_model": "zai-org-glm-5-2",
}

DEFAULT_PERMISSIONS = {
    "allow_laptop_commands": True,
    "allow_file_write": True,
    "allow_file_delete": False,
    "allow_civitai_download": True,
    "allow_spend": False,
    "require_confirm_destructive": True,
    "daily_tool_budget": 200,
    "sandbox_note": "Workspace file tools stay sandboxed; laptop cmds use existing SSH bridge.",
}

DEFAULT_MEMORY = {
    "preferences": {},
    "notes": [],
    "projects": {},
    "updated": 0,
}

DEFAULT_TOOL_USAGE = {
    "day": "",
    "count": 0,
}

# Single source of truth for "risky" tools. The background runner (agentic/runner.py),
# the Venice tool choke point (hub/venice.py), and the front-end permission card
# (via GET /api/agentic/gates) all draw from this list.
CONFIRM_TOOLS = {
    "delete_file": "Delete a workspace file",
    "download_civitai_lora": "Download LoRA files from Civitai to the laptop",
    "render_assess_adjust_cycle": "Start an LTX render QA cycle (spends GPU time)",
    "hub_request": "Mutating Forge Hub API call",
    "run_laptop_command": "Potentially destructive laptop command",
    "run_in_terminal": "Potentially destructive laptop command",
}

# Laptop/terminal commands matching this pattern are considered destructive.
DANGEROUS_CMD = r"rm\s+-[rf]|mkfs|shutdown|reboot|sudo\s|>\s*/dev/|curl[^|]*\|\s*(bash|sh)"


def confirm_reason(tool: str, args: dict[str, Any] | None = None) -> str | None:
    """Why this tool call needs user confirmation, or None. Args disambiguate the conditional ones."""
    if tool not in CONFIRM_TOOLS:
        return None
    args = args or {}
    if tool == "render_assess_adjust_cycle" and str(args.get("action") or "status") != "start":
        return None
    if tool == "hub_request" and str(args.get("method") or "GET").upper() == "GET":
        return None
    if tool in ("run_laptop_command", "run_in_terminal"):
        import re

        cmd = str(args.get("code") or args.get("command") or "")
        if not re.search(DANGEROUS_CMD, cmd):
            return None
    base = CONFIRM_TOOLS[tool]
    if tool == "hub_request":
        base += " (" + str(args.get("method") or "").upper() + " " + str(args.get("path") or "") + ")"
    return base


def permission_block(
    tool: str,
    perms: dict[str, Any] | None = None,
    args: dict[str, Any] | None = None,
    preapproved: bool = False,
) -> str | None:
    """Why this tool call is forbidden outright, or None if it may run.

    Single choke point for the allow_* switches. Both the background runner
    (agentic/runner.py) and the Venice chat tool executor (hub/venice.py) call
    this, so a permission turned off in the UI is off on every path.
    """
    perms = permissions() if perms is None else perms
    if tool == "delete_file" and not perms.get("allow_file_delete"):
        return "Blocked by agentic permissions: allow_file_delete=false"
    if tool == "write_file" and not perms.get("allow_file_write"):
        return "Blocked by agentic permissions: allow_file_write=false"
    # run_in_terminal is the same capability as run_laptop_command — arbitrary
    # commands on the laptop, just typed into the live ttyd shell instead of run
    # over SSH. Gating only the latter left the switch trivially sidesteppable.
    if tool in ("run_laptop_command", "run_in_terminal") and not perms.get("allow_laptop_commands"):
        return "Blocked by agentic permissions: allow_laptop_commands=false"
    if tool == "download_civitai_lora" and not perms.get("allow_civitai_download"):
        return "Blocked by agentic permissions: allow_civitai_download=false"
    if perms.get("require_confirm_destructive") and not preapproved:
        reason = confirm_reason(tool, args or {})
        if reason:
            return (
                f"Blocked by agentic permissions: {reason} — needs in-chat user "
                "confirmation or reviewer sub-agent approval "
                "(require_confirm_destructive=true)"
            )
    return None


def default_files() -> tuple:
    return (
        ("config.json", DEFAULT_CONFIG),
        ("permissions.json", DEFAULT_PERMISSIONS),
        ("memory.json", DEFAULT_MEMORY),
        ("jobs.json", {"jobs": []}),
        ("schedules.json", {"schedules": []}),
        ("watchers.json", {"watchers": []}),
        ("tool_usage.json", DEFAULT_TOOL_USAGE),
        ("audit.jsonl", None),
    )


def ensure() -> Path:
    DATA.mkdir(parents=True, exist_ok=True)
    for name, default in default_files():
        path = DATA / name
        if not path.exists():
            if default is None:
                path.write_text("", encoding="utf-8")
            else:
                path.write_text(json.dumps(default, indent=2) + "\n", encoding="utf-8")
    return DATA


def _read(name: str) -> Any:
    ensure()
    path = DATA / name
    with LOCK:
        return json.loads(path.read_text(encoding="utf-8"))


def _write(name: str, payload: Any) -> None:
    ensure()
    path = DATA / name
    tmp = path.with_suffix(path.suffix + ".tmp")
    with LOCK:
        tmp.write_text(json.dumps(payload, indent=2, default=str) + "\n", encoding="utf-8")
        os.replace(tmp, path)


def config() -> dict[str, Any]:
    c = dict(DEFAULT_CONFIG)
    c.update(_read("config.json") or {})
    return c


def save_config(patch: dict[str, Any]) -> dict[str, Any]:
    c = config()
    for k, v in (patch or {}).items():
        if k in DEFAULT_CONFIG:
            c[k] = v
    _write("config.json", c)
    return c


def permissions() -> dict[str, Any]:
    p = dict(DEFAULT_PERMISSIONS)
    p.update(_read("permissions.json") or {})
    return p


def save_permissions(patch: dict[str, Any]) -> dict[str, Any]:
    p = permissions()
    for k, v in (patch or {}).items():
        if k in DEFAULT_PERMISSIONS:
            p[k] = v
    _write("permissions.json", p)
    return p


def memory() -> dict[str, Any]:
    m = dict(DEFAULT_MEMORY)
    raw = _read("memory.json") or {}
    m.update(raw)
    return m


def save_memory(patch: dict[str, Any]) -> dict[str, Any]:
    m = memory()
    if "preferences" in patch and isinstance(patch["preferences"], dict):
        prefs = dict(m.get("preferences") or {})
        prefs.update(patch["preferences"])
        m["preferences"] = prefs
    if "notes" in patch and isinstance(patch["notes"], list):
        m["notes"] = patch["notes"][-200:]
    if "projects" in patch and isinstance(patch["projects"], dict):
        projs = dict(m.get("projects") or {})
        projs.update(patch["projects"])
        m["projects"] = projs
    if "append_note" in patch and str(patch["append_note"]).strip():
        notes = list(m.get("notes") or [])
        notes.append({"t": time.time(), "text": str(patch["append_note"]).strip()[:4000]})
        m["notes"] = notes[-200:]
    m["updated"] = time.time()
    _write("memory.json", m)
    return m


def bump_tool_usage() -> dict[str, Any]:
    """Count one tool call against the current day; returns the updated counter."""
    u = dict(DEFAULT_TOOL_USAGE)
    u.update(_read("tool_usage.json") or {})
    today = time.strftime("%Y-%m-%d")
    if u.get("day") != today:
        u = {"day": today, "count": 0}
    u["count"] = int(u.get("count") or 0) + 1
    _write("tool_usage.json", u)
    return u


def list_jobs() -> list[dict[str, Any]]:
    return list((_read("jobs.json") or {}).get("jobs") or [])


def get_job(jid: str) -> dict[str, Any] | None:
    for j in list_jobs():
        if j.get("id") == jid:
            return j
    return None


def upsert_job(job: dict[str, Any]) -> dict[str, Any]:
    # Read-modify-write must be atomic: parallel job threads clobber each other otherwise.
    with LOCK:
        data = _read("jobs.json") or {"jobs": []}
        jobs = list(data.get("jobs") or [])
        found = False
        for i, j in enumerate(jobs):
            if j.get("id") == job.get("id"):
                jobs[i] = job
                found = True
                break
        if not found:
            jobs.insert(0, job)
        data["jobs"] = jobs[:100]
        _write("jobs.json", data)
    return job


def new_job_id() -> str:
    return uuid.uuid4().hex[:12]


def list_schedules() -> list[dict[str, Any]]:
    return list((_read("schedules.json") or {}).get("schedules") or [])


def save_schedules(rows: list[dict[str, Any]]) -> list[dict[str, Any]]:
    _write("schedules.json", {"schedules": rows[:50]})
    return rows


def list_watchers() -> list[dict[str, Any]]:
    return list((_read("watchers.json") or {}).get("watchers") or [])


def save_watchers(rows: list[dict[str, Any]]) -> list[dict[str, Any]]:
    # Read-modify-write atomic vs the scheduler thread.
    with LOCK:
        _write("watchers.json", {"watchers": rows[:50]})
    return rows


def audit(event: str, detail: dict[str, Any] | None = None) -> None:
    ensure()
    line = json.dumps({"t": time.time(), "event": event, "detail": detail or {}}, default=str)
    with LOCK:
        with (DATA / "audit.jsonl").open("a", encoding="utf-8") as f:
            f.write(line + "\n")
