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
DEFAULT_MODELS = [
    {"id": "zai-org-glm-5-2", "name": "GLM 5.2"},
    {"id": "zai-org-glm-5", "name": "GLM 5"},
    {"id": "venice-uncensored", "name": "Venice Uncensored"},
    {"id": "venice-uncensored-1-2", "name": "Venice Uncensored 1.2"},
    {"id": "kimi-k2-6", "name": "Kimi K2.6"},
    {"id": "claude-opus-4-8", "name": "Claude Opus 4.8"},
]

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
    model: str = "zai-org-glm-5-2"
    temperature: float = 0.8
    stream: bool = True
    web_search: bool = False
    venice_prompt: bool = False
    thinking: bool = False
    max_tokens: Optional[int] = 2048


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


def _clean_msg(raw: Any) -> Optional[dict[str, str]]:
    if not isinstance(raw, dict):
        return None
    role = str(raw.get("role") or "").strip()
    if role not in ("user", "assistant", "system", "laptop"):
        return None
    content = str(raw.get("content") or "")[:MAX_MSG_CHARS]
    return {"role": role, "content": content}


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
        "default_model": os.environ.get("VENICE_MODEL") or "zai-org-glm-5-2",
        "key_file": str(HOME / ".venice_key"),
        "chats": len(load_chats().get("chats") or []),
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
