"""Run a Venice snippet on the laptop over `ssh laptop` (same path as HypnoForge).

The Venice Agent POSTs here from run_laptop_command (and from the old Run sheet).
Scripts land in ~/venice_run on the WSL laptop. Live Shell paste is separate
(ttyd via forge:term-paste) so the user can watch commands in the Terminal tab.
"""
from __future__ import annotations

import subprocess
from typing import Optional

import httpx
from fastapi import APIRouter, HTTPException
from pydantic import BaseModel, Field

router = APIRouter(prefix="/api/laptop")

MAX_CODE = 100_000
LANGS = {
    "bash": ("last.sh", "bash last.sh"),
    "python": ("last.py", "python3 last.py"),
}


def decode(b: bytes) -> str:
    return (b or b"").decode("utf-8", errors="replace")


def ssh(cmd: str, timeout: int = 60, stdin: Optional[str] = None) -> subprocess.CompletedProcess:
    try:
        r = subprocess.run(
            ["ssh", "-o", "ConnectTimeout=8", "-o", "BatchMode=yes", "laptop", cmd],
            capture_output=True,
            timeout=timeout,
            input=stdin.encode() if stdin is not None else None,
        )
    except subprocess.TimeoutExpired:
        raise HTTPException(504, "The laptop took too long to answer.")
    if r.returncode == 255:
        raise HTTPException(503, "Laptop is offline (asleep, or WSL not running).")
    r.stdout, r.stderr = decode(r.stdout), decode(r.stderr)
    return r


@router.get("/ping")
def ping():
    try:
        r = httpx.get("http://127.0.0.1:18765/ping", timeout=2.0)
        if r.status_code == 200:
            data = r.json() if r.headers.get("content-type", "").startswith("application/json") else {}
            return {"online": True, "via": "agent", "host": data.get("host"), "free_gb": data.get("free_gb")}
    except Exception:
        pass
    try:
        r = ssh("echo ok", timeout=10)
        return {"online": "ok" in (r.stdout or ""), "via": "ssh"}
    except HTTPException:
        return {"online": False, "via": "ssh"}


class RunIn(BaseModel):
    lang: str = "bash"
    code: str
    timeout: int = Field(default=90, ge=10, le=180)


@router.post("/run")
def run(body: RunIn):
    lang = (body.lang or "bash").strip().lower()
    if lang in ("py", "python3"):
        lang = "python"
    if lang in ("sh", "shell", "zsh"):
        lang = "bash"
    if lang not in LANGS:
        raise HTTPException(400, "Use bash or python.")
    code = body.code.replace("\r\n", "\n")
    if not code.strip():
        raise HTTPException(400, "Nothing to run.")
    if len(code) > MAX_CODE:
        raise HTTPException(400, "That snippet is too large.")
    name, exe = LANGS[lang]
    remote = f"mkdir -p ~/venice_run && cd ~/venice_run && cat > {name} && {exe}"
    r = ssh(remote, timeout=body.timeout, stdin=code if code.endswith("\n") else code + "\n")
    out = (r.stdout or "").strip()
    err = (r.stderr or "").strip()
    text = out if not err else (out + ("\n" if out else "") + err)
    return {
        "ok": r.returncode == 0,
        "exit": r.returncode,
        "lang": lang,
        "cwd": "~/venice_run",
        "output": text[-24000:],
        "stdout": (r.stdout or "")[-20000:],
        "stderr": (r.stderr or "")[-8000:],
    }
