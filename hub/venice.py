"""Venice AI proxy for Forge Hub. OpenAI-compatible chat against api.venice.ai.

The key never goes into git. Resolution order:
  1. VENICE_API_KEY in the process environment
  2. ~/.venice_key or hub/venice.key (one line, the key only)
  3. .env next to the hub or the repo root (VENICE_API_KEY=...)
  4. Key saved from the Venice tab (writes ~/.venice_key)
  5. X-Venice-Key request header (device-local fallback)
"""
from __future__ import annotations

import json
import os
from pathlib import Path
from typing import Any, Optional

import httpx
from fastapi import APIRouter, Header, HTTPException, Request
from fastapi.responses import StreamingResponse
from pydantic import BaseModel

HUB = Path(__file__).resolve().parent
ROOT = HUB.parent
HOME = Path.home()
DEFAULT_BASE = "https://api.venice.ai/api/v1"
DEFAULT_MODEL = "zai-org-glm-5-2"
DEFAULT_SYSTEM_PROMPT = (
    "You are WhiteDevil Venice Agent, an autonomous AI assistant with tools to inspect "
    "and modify local workspace files, and monitor and trigger remote Forge Hub and Wan2.2 "
    "video generation pipelines on the relay and laptop. Be concise and proactive. "
    "Use run_laptop_command for scripts that need stdout. Use run_in_terminal to type a "
    "command into the live laptop Shell (ttyd) so the user can watch it."
)
DEFAULT_MODELS = [
    {"id": "zai-org-glm-5-2", "name": "GLM 5.2"},
    {"id": "zai-org-glm-5", "name": "GLM 5"},
    {"id": "venice-uncensored", "name": "Venice Uncensored"},
    {"id": "venice-uncensored-1-2", "name": "Venice Uncensored 1.2"},
    {"id": "kimi-k2-6", "name": "Kimi K2.6"},
    {"id": "claude-opus-4-8", "name": "Claude Opus 4.8"},
]
MAX_TOOL_ITERATIONS = 8
WORKSPACE = HOME / ".venice_workspace"
MAX_FILE_CHARS = 200_000

router = APIRouter(prefix="/api/venice")


def load_dotenv() -> None:
    for path in (ROOT / ".env", HUB / ".env", HOME / ".venice.env"):
        if not path.is_file():
            continue
        for raw in path.read_text(encoding="utf-8", errors="replace").splitlines():
            line = raw.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            key, _, val = line.partition("=")
            key, val = key.strip(), val.strip().strip("'").strip('"')
            if key and key not in os.environ:
                os.environ[key] = val


load_dotenv()


KEY_FILES = (HOME / ".venice_key", HUB / "venice.key")


def _clean_key(raw: str) -> str:
    key = (raw or "").strip()
    if key.lower().startswith("bearer "):
        key = key[7:].strip()
    return key


def _file_key() -> str:
    for p in KEY_FILES:
        if p.is_file():
            key = _clean_key(p.read_text(encoding="utf-8", errors="replace"))
            if key:
                return key
    return ""


def server_key() -> str:
    return (os.environ.get("VENICE_API_KEY") or _file_key()).strip()


def base_url() -> str:
    return (os.environ.get("VENICE_BASE_URL") or DEFAULT_BASE).rstrip("/")


def resolve_key(header_key: Optional[str]) -> str:
    key = _clean_key(header_key or "") or server_key()
    if not key:
        raise HTTPException(412, "Venice API key not configured. Paste it in the Venice tab and tap Save on relay.")
    return key


class KeyIn(BaseModel):
    key: str


class ChatIn(BaseModel):
    messages: list[dict[str, Any]]
    model: str = DEFAULT_MODEL
    temperature: float = 0.8
    stream: bool = True
    web_search: bool = False
    venice_prompt: bool = False
    thinking: bool = False
    max_tokens: Optional[int] = 2048
    tools: Optional[list[dict[str, Any]]] = None


def payload_of(req: ChatIn) -> dict[str, Any]:
    body: dict[str, Any] = {
        "model": req.model,
        "messages": req.messages,
        "temperature": req.temperature,
        "stream": req.stream,
        "venice_parameters": {
            "enable_web_search": "auto" if req.web_search else "off",
            "include_venice_system_prompt": bool(req.venice_prompt),
            "disable_thinking": not req.thinking,
            "strip_thinking_response": not req.thinking,
        },
    }
    if req.max_tokens:
        body["max_tokens"] = req.max_tokens
    if req.tools:
        body["tools"] = req.tools
    return body


CHAT_FILES = (HOME / ".venice_chats.json", HUB / "venice.chats.json")
MAX_CHATS = 40
MAX_MSGS = 80
MAX_MSG_CHARS = 20000


def _chat_path() -> Path:
    for p in CHAT_FILES:
        if p.is_file():
            return p
    return CHAT_FILES[0]


def empty_store() -> dict[str, Any]:
    return {"activeId": None, "chats": []}


KEEP_ROLES = ("user", "assistant", "system", "laptop", "tool", "tool_call", "error")


def _clean_msg(raw: Any) -> Optional[dict[str, Any]]:
    if not isinstance(raw, dict):
        return None
    role = str(raw.get("role") or "").strip()
    if role not in KEEP_ROLES:
        return None
    content = str(raw.get("content") or "")[:MAX_MSG_CHARS]
    msg: dict[str, Any] = {"role": role, "content": content}
    name = str(raw.get("name") or "").strip()
    if name:
        msg["name"] = name[:80]
    tid = str(raw.get("tool_call_id") or "").strip()
    if tid:
        msg["tool_call_id"] = tid[:80]
    args = raw.get("arguments")
    if args is not None:
        msg["arguments"] = str(args)[:MAX_MSG_CHARS]
    calls = raw.get("tool_calls")
    if isinstance(calls, list) and calls:
        msg["tool_calls"] = calls[:16]
    return msg


def normalize_store(raw: Any) -> dict[str, Any]:
    data = raw if isinstance(raw, dict) else {}
    chats: list[dict[str, Any]] = []
    seen: set[str] = set()
    for item in data.get("chats") or []:
        if not isinstance(item, dict):
            continue
        cid = str(item.get("id") or "").strip()
        if not cid or cid in seen:
            continue
        msgs = [m for m in (_clean_msg(x) for x in (item.get("messages") or [])) if m]
        if not msgs:
            continue
        seen.add(cid)
        chats.append({
            "id": cid[:64],
            "title": str(item.get("title") or "Chat")[:80],
            "updated": float(item.get("updated") or 0),
            "model": str(item.get("model") or "")[:80],
            "system": str(item.get("system") or "")[:4000],
            "messages": msgs[-MAX_MSGS:],
        })
    chats.sort(key=lambda c: c.get("updated") or 0, reverse=True)
    chats = chats[:MAX_CHATS]
    active = data.get("activeId")
    if active not in {c["id"] for c in chats}:
        active = None
    return {"activeId": active, "chats": chats}


def load_chats() -> dict[str, Any]:
    path = _chat_path()
    if not path.is_file():
        return empty_store()
    try:
        return normalize_store(json.loads(path.read_text(encoding="utf-8")))
    except (OSError, ValueError):
        return empty_store()


def save_chats(raw: Any) -> dict[str, Any]:
    data = normalize_store(raw)
    path = CHAT_FILES[0]
    tmp = path.with_suffix(".json.tmp")
    tmp.write_text(json.dumps(data, ensure_ascii=False), encoding="utf-8")
    tmp.chmod(0o600)
    tmp.replace(path)
    return data


@router.get("/status")
def status():
    return {
        "configured": bool(server_key()),
        "base": base_url(),
        "default_model": os.environ.get("VENICE_MODEL") or DEFAULT_MODEL,
        "default_system_prompt": DEFAULT_SYSTEM_PROMPT,
        "key_file": str(HOME / ".venice_key"),
        "chats": len(load_chats().get("chats") or []),
        "agent": True,
        "max_tool_iterations": MAX_TOOL_ITERATIONS,
        "workspace": str(WORKSPACE),
        "tools": [t["function"]["name"] for t in AGENT_TOOLS],
    }


class ChatStoreIn(BaseModel):
    activeId: Optional[str] = None
    chats: list[dict[str, Any]] = []


@router.get("/chats")
def get_chats():
    return load_chats()


@router.put("/chats")
def put_chats(body: ChatStoreIn):
    return save_chats(body.model_dump())


@router.post("/key")
async def save_key(body: KeyIn):
    key = _clean_key(body.key)
    if not key or len(key) < 12:
        raise HTTPException(400, "That does not look like a Venice API key.")
    try:
        async with httpx.AsyncClient(timeout=20.0) as client:
            r = await client.get(f"{base_url()}/models", headers={"Authorization": f"Bearer {key}"})
        if r.status_code == 401:
            raise HTTPException(401, "Venice rejected this key.")
        if r.status_code >= 400:
            raise HTTPException(r.status_code, _err_text(r))
    except HTTPException:
        raise
    except httpx.HTTPError as e:
        raise HTTPException(502, f"Could not reach Venice to check the key: {e}")
    dest = HOME / ".venice_key"
    dest.write_text(key + "\n", encoding="utf-8")
    dest.chmod(0o600)
    os.environ["VENICE_API_KEY"] = key
    return {"ok": True, "configured": True, "where": str(dest)}


@router.get("/models")
async def models(x_venice_key: Optional[str] = Header(default=None)):
    key = (x_venice_key or "").strip() or server_key()
    listed = list(DEFAULT_MODELS)
    if not key:
        return {"models": listed, "source": "fallback"}
    try:
        async with httpx.AsyncClient(timeout=20.0) as client:
            r = await client.get(f"{base_url()}/models", headers={"Authorization": f"Bearer {key}"})
        r.raise_for_status()
        data = r.json()
        remote = data.get("data") or data.get("models") or []
        ids = []
        for item in remote:
            mid = item.get("id") if isinstance(item, dict) else str(item)
            if mid:
                ids.append({"id": mid, "name": (item.get("name") if isinstance(item, dict) else None) or mid})
        # Keep preferred models first, then anything Venice returned that we didn't list.
        have = {m["id"] for m in listed}
        for m in ids:
            if m["id"] not in have:
                listed.append(m)
                have.add(m["id"])
        return {"models": listed, "source": "venice"}
    except Exception:
        return {"models": listed, "source": "fallback"}


@router.post("/chat")
async def chat(req: ChatIn, request: Request, x_venice_key: Optional[str] = Header(default=None)):
    key = resolve_key(x_venice_key)
    url = f"{base_url()}/chat/completions"
    headers = {"Authorization": f"Bearer {key}", "Content-Type": "application/json"}
    body = payload_of(req)

    if not req.stream:
        async with httpx.AsyncClient(timeout=120.0) as client:
            r = await client.post(url, headers=headers, json=body)
        if r.status_code >= 400:
            raise HTTPException(r.status_code, _err_text(r))
        return r.json()

    async def stream():
        try:
            async with httpx.AsyncClient(timeout=None) as client:
                async with client.stream("POST", url, headers=headers, json=body) as r:
                    if r.status_code >= 400:
                        text = (await r.aread()).decode("utf-8", "replace")
                        yield f"data: {json.dumps({'error': text or r.reason_phrase})}\n\n"
                        return
                    async for chunk in r.aiter_bytes():
                        if await request.is_disconnected():
                            break
                        yield chunk
        except httpx.HTTPError as e:
            yield f"data: {json.dumps({'error': str(e)})}\n\n"

    return StreamingResponse(stream(), media_type="text/event-stream")


def _err_text(r: httpx.Response) -> str:
    try:
        j = r.json()
        err = j.get("error")
        if isinstance(err, dict):
            return err.get("message") or r.text
        if isinstance(err, str):
            return err
        return r.text or r.reason_phrase
    except Exception:
        return r.text or r.reason_phrase


def _object_schema(*params: tuple[str, str], required: Optional[list[str]] = None) -> dict[str, Any]:
    props = {name: {"type": "string", "description": desc} for name, desc in params}
    req = [name for name, _ in params] if required is None else list(required)
    return {"type": "object", "properties": props, "required": req}


def _tool(name: str, description: str, parameters: dict[str, Any]) -> dict[str, Any]:
    return {"type": "function", "function": {"name": name, "description": description, "parameters": parameters}}


AGENT_TOOLS: list[dict[str, Any]] = [
    _tool(
        "read_file",
        "Read the contents of a text file inside the local agent workspace.",
        _object_schema(("path", "Path to the file, relative to the workspace root.")),
    ),
    _tool(
        "write_file",
        "Create or overwrite a text file inside the local agent workspace.",
        _object_schema(
            ("path", "Path to the file, relative to the workspace root."),
            ("content", "Full text content to write to the file."),
        ),
    ),
    _tool(
        "list_directory",
        "List files and subdirectories inside a directory in the local agent workspace.",
        _object_schema(("path", "Directory path, relative to the workspace root. Use \".\" for the root.")),
    ),
    _tool(
        "delete_file",
        "Delete a file inside the local agent workspace.",
        _object_schema(("path", "Path to the file, relative to the workspace root.")),
    ),
    _tool(
        "get_render_status",
        "Query active Wan2.2 rendering jobs, Colab GPU status, credit usage, and laptop connection state from Forge Hub.",
        {"type": "object", "properties": {}},
    ),
    _tool(
        "list_prompt_packs",
        "List prompt packs in the Wan2.2 generation catalog and their render completion status from Forge Hub.",
        {"type": "object", "properties": {}},
    ),
    _tool(
        "run_laptop_command",
        "Execute a bash or python command on the user's WSL laptop via the relay SSH bridge in ~/venice_run. Returns stdout/stderr.",
        _object_schema(
            ("code", "The shell command or python script code to execute."),
            ("lang", "Execution language: 'bash' or 'python'. Defaults to 'bash'."),
            required=["code"],
        ),
    ),
    _tool(
        "run_in_terminal",
        "Type a command into the live laptop Shell (ttyd) so it runs in the visible terminal. Use this when the user should watch the command. Prefer run_laptop_command when you need the output back.",
        _object_schema(("command", "The exact shell command to paste into the live terminal, without a trailing prompt.")),
    ),
    _tool(
        "download_civitai_lora",
        "Download LoRA files from Civitai onto the laptop ~/civitai_dl folder. Pass one id or several (comma-separated). Pulls every LoRA file on every version (Wan 2.2, LTX-2, LTX-2.5), not a single LTX 2.5 file.",
        _object_schema(
            ("model_id", "Civitai model ID or version ID."),
            ("slug", "Optional model slug name for file naming."),
            required=["model_id"],
        ),
    ),
]


def _as_args(raw: Any) -> dict[str, Any]:
    if isinstance(raw, dict):
        return raw
    if isinstance(raw, str) and raw.strip():
        try:
            parsed = json.loads(raw)
        except ValueError:
            return {}
        return parsed if isinstance(parsed, dict) else {}
    return {}


def _workspace_root() -> Path:
    WORKSPACE.mkdir(parents=True, exist_ok=True)
    return WORKSPACE.resolve()


def _within_workspace(relative: str) -> Path:
    root = _workspace_root()
    rel = (relative or ".").strip() or "."
    if rel.startswith("/") or rel.startswith("~") or ".." in Path(rel).parts:
        raise ValueError(f"Path '{relative}' escapes the workspace directory")
    target = (root / rel).resolve()
    if target != root and not str(target).startswith(str(root) + os.sep):
        raise ValueError(f"Path '{relative}' escapes the workspace directory")
    return target


def _clip(text: str, n: int = 24000) -> str:
    s = text or ""
    return s if len(s) <= n else s[-n:]


def _render_status_text() -> str:
    wan = Path.home() / "wan"
    renders = wan / "renders"
    clips = sorted(renders.glob("smoke_*.mp4"), key=lambda p: p.stat().st_mtime, reverse=True) if renders.exists() else []
    payload: dict[str, Any] = {
        "clips": len(clips),
        "newest": clips[0].name if clips else None,
    }
    try:
        payload["laptop_file"] = json.loads((wan / "www" / "laptop.json").read_text())
    except (OSError, ValueError):
        payload["laptop_file"] = {}
    try:
        import laptop as laptop_mod
        payload["laptop"] = laptop_mod.ping()
    except Exception as e:
        payload["laptop"] = {"error": str(e)}
    try:
        from colab import state as colab_state
        payload["colab"] = colab_state()
    except Exception as e:
        payload["colab"] = {"error": str(e)}
    return _clip(json.dumps(payload, default=str, indent=2))


def _packs_text() -> str:
    try:
        from colab import packs
        return _clip(json.dumps(packs(), default=str))
    except Exception as e:
        return f"Error fetching prompt packs: {e}"


def _laptop_run(code: str, lang: str = "bash", timeout: int = 90) -> str:
    import laptop as laptop_mod
    body = laptop_mod.RunIn(lang=lang or "bash", code=code, timeout=timeout)
    return _clip(json.dumps(laptop_mod.run(body), default=str))


def execute_tool(name: str, arguments: Any = None) -> str:
    """Run one Venice Agent tool. Same names as the Android app, plus run_in_terminal."""
    args = _as_args(arguments)
    try:
        if name == "read_file":
            path = str(args.get("path") or "").strip()
            if not path:
                return "Error: 'path' argument is required."
            file = _within_workspace(path)
            if not file.is_file():
                return f"Error: file not found: {path}"
            text = file.read_text(encoding="utf-8", errors="replace")
            return text if len(text) <= MAX_FILE_CHARS else text[:MAX_FILE_CHARS] + "\n…truncated"

        if name == "write_file":
            path = str(args.get("path") or "").strip()
            if not path:
                return "Error: 'path' argument is required."
            content = str(args.get("content") or "")
            file = _within_workspace(path)
            file.parent.mkdir(parents=True, exist_ok=True)
            file.write_text(content, encoding="utf-8")
            return f"Wrote {len(content)} characters to {path}"

        if name == "list_directory":
            path = str(args.get("path") or ".").strip() or "."
            folder = _within_workspace(path)
            if not folder.is_dir():
                return f"Error: directory not found: {path}"
            names = []
            for child in sorted(folder.iterdir(), key=lambda p: p.name):
                names.append(child.name + ("/" if child.is_dir() else ""))
            return "\n".join(names) if names else "(empty directory)"

        if name == "delete_file":
            path = str(args.get("path") or "").strip()
            if not path:
                return "Error: 'path' argument is required."
            file = _within_workspace(path)
            if not file.exists():
                return f"Error: file not found: {path}"
            if file.is_dir():
                return f"Error: '{path}' is a directory"
            file.unlink()
            return f"Deleted {path}"

        if name == "get_render_status":
            return _render_status_text()

        if name == "list_prompt_packs":
            return _packs_text()

        if name == "run_laptop_command":
            code = str(args.get("code") or args.get("command") or "")
            if not code.strip():
                return "Error: 'code' argument is required."
            lang = str(args.get("lang") or "bash")
            timeout = int(args.get("timeout") or 90)
            timeout = max(10, min(180, timeout))
            return _laptop_run(code, lang, timeout)

        if name == "run_in_terminal":
            cmd = str(args.get("command") or args.get("code") or "").strip()
            if not cmd:
                return "Error: 'command' argument is required."
            return json.dumps({
                "ok": True,
                "paste": True,
                "command": cmd,
                "hint": "Paste this into the live Shell. Laptop Venice does that automatically.",
            })

        if name == "download_civitai_lora":
            mid = str(args.get("model_id") or args.get("id") or "").strip()
            if not mid:
                return "Error: 'model_id' argument is required."
            slug = str(args.get("slug") or "").strip()
            ids = [p for p in mid.replace(",", " ").split() if p]
            flags = " ".join(f"--id {p}" for p in ids)
            if slug:
                flags += f" --slug {slug}"
            script = "~/hub/static/term/civitai_red_dl.py"
            cmd = f"mkdir -p ~/civitai_dl && python3 {script} {flags}"
            return _laptop_run(cmd, "bash", 180)

        return f"Error: unknown tool '{name}'."
    except HTTPException as e:
        detail = e.detail if isinstance(e.detail, str) else str(e.detail)
        return f"Error: {detail}"
    except Exception as e:
        return f"Error: {e}"


@router.get("/tools")
def list_tools():
    return {
        "tools": AGENT_TOOLS,
        "max_iterations": MAX_TOOL_ITERATIONS,
        "system_prompt": DEFAULT_SYSTEM_PROMPT,
        "workspace": str(WORKSPACE),
    }


class ToolIn(BaseModel):
    name: str
    arguments: Any = {}


@router.post("/tool")
def run_tool(body: ToolIn):
    name = (body.name or "").strip()
    if not name:
        raise HTTPException(400, "Tool name is required.")
    output = execute_tool(name, body.arguments)
    return {"ok": not str(output).startswith("Error:"), "name": name, "output": output}
