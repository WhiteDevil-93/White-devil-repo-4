"""Run a Venice snippet on the laptop over `ssh laptop` (same path as HypnoForge).

The native Android agent and Hub terminal POST here (run_laptop_command / Run sheet).
Scripts land in ~/venice_run on the WSL laptop and may execute from a caller-selected
directory under the laptop home. Live Shell paste is separate (ttyd via forge:term-paste)
so the user can watch commands in the Terminal tab.
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
    r.stdout, r.stderr = decode(r.stdout), decode(r.stderr)
    # 255 is ssh's own transport failure code, but it is also a perfectly legal
    # exit code for the remote command. Only call the laptop offline when the
    # error actually looks like ssh failing to connect, so a script exiting 255
    # is not reported as a dead machine.
    if r.returncode == 255 and (not r.stderr.strip() or "ssh:" in r.stderr.lower()):
        raise HTTPException(503, "Laptop is offline (asleep, or WSL not running).")
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
    cwd: str = Field(default="venice_run", max_length=240)


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
    cwd_input = (body.cwd or "venice_run").strip().replace("\\", "/")
    if cwd_input.startswith("/"):
        raise HTTPException(400, "cwd must be relative to the laptop home directory.")
    if cwd_input.startswith("~/"):
        cwd_input = cwd_input[2:]
    cwd = cwd_input.strip("/")
    if not cwd:
        cwd = "venice_run"
    parts = cwd.split("/")
    if any(part in ("", ".", "..") for part in parts) or not all(
        part.replace("-", "").replace("_", "").replace(".", "").isalnum() for part in parts
    ):
        raise HTTPException(400, "cwd must be a safe path relative to the laptop home directory.")
    name, exe = LANGS[lang]
    staged = f"$HOME/venice_run/{name}"
    workdir = "$HOME/" + cwd
    remote = (
        f"mkdir -p \"$HOME/venice_run\" && cat > \"{staged}\" && "
        # A bare `test -d` failed the chain with no stdout and no stderr, so a
        # missing cwd reached the agent as a blank failure it could not diagnose.
        f"{{ test -d \"{workdir}\" || {{ echo \"cwd not found: ~/{cwd}\" >&2; exit 2; }}; }} && "
        f"cd \"{workdir}\" && {exe.replace(name, staged)}"
    )
    r = ssh(remote, timeout=body.timeout, stdin=code if code.endswith("\n") else code + "\n")
    out = (r.stdout or "").strip()
    err = (r.stderr or "").strip()
    text = out if not err else (out + ("\n" if out else "") + err)
    return {
        "ok": r.returncode == 0,
        "exit": r.returncode,
        "lang": lang,
        "cwd": f"~/{cwd}",
        "output": text[-24000:],
        "stdout": (r.stdout or "")[-20000:],
        "stderr": (r.stderr or "")[-8000:],
    }
