"""Live Shell paste bridge — reliable path Venice → ttyd without depending on iframe postMessage.

POST /api/term/paste   enqueue text for the Terminal tab to type into ttyd
GET  /api/term/poll    Terminal tab long-polls; returns next item and marks it consumed
GET  /api/term/status  queue depth / last result
"""
from __future__ import annotations

import threading
import time
import uuid
from collections import deque
from typing import Any, Deque, Dict, Optional

from fastapi import APIRouter, HTTPException
from pydantic import BaseModel, Field

router = APIRouter(prefix="/api/term", tags=["term-bridge"])

_LOCK = threading.RLock()
_QUEUE: Deque[Dict[str, Any]] = deque(maxlen=40)
_BY_ID: Dict[str, Dict[str, Any]] = {}
_LAST: Optional[Dict[str, Any]] = None


class PasteIn(BaseModel):
    text: str = Field(..., min_length=1, max_length=100_000)
    show: bool = True
    source: str = Field(default="venice", max_length=40)


def _purge_old(now: float) -> None:
    dead = [i for i, it in _BY_ID.items() if now - float(it.get("created") or 0) > 300]
    for i in dead:
        _BY_ID.pop(i, None)


@router.post("/paste")
def paste(body: PasteIn):
    text = body.text.replace("\r\n", "\n")
    if not text.endswith("\n"):
        text += "\n"
    item = {
        "id": uuid.uuid4().hex[:12],
        "text": text,
        "show": bool(body.show),
        "source": (body.source or "venice")[:40],
        "created": time.time(),
        "consumed": False,
        "consumed_at": None,
    }
    with _LOCK:
        _purge_old(time.time())
        if _QUEUE.maxlen is not None and len(_QUEUE) >= _QUEUE.maxlen:
            evicted = _QUEUE[0]
            evicted["dropped"] = True
            evicted["dropped_at"] = time.time()
        _QUEUE.append(item)
        _BY_ID[item["id"]] = item
        global _LAST
        _LAST = {
            "id": item["id"],
            "queued": True,
            "at": item["created"],
            "source": item.get("source"),
            "preview": (item.get("text") or "")[:180],
            "consumed": False,
        }
    return {"ok": True, "id": item["id"], "queued": True, "depth": len(_QUEUE)}


@router.get("/paste/{pid}")
def paste_status(pid: str):
    with _LOCK:
        item = _BY_ID.get(pid)
    if not item:
        raise HTTPException(404, "paste id not found: it is older than 5 minutes, or was never queued "
                                 "by this Hub process (a restart clears the queue).")
    return {
        "id": item["id"],
        "consumed": bool(item.get("consumed")),
        "dropped": bool(item.get("dropped")),
        "created": item.get("created"),
        "consumed_at": item.get("consumed_at"),
        "source": item.get("source"),
        "preview": (item.get("text") or "")[:120],
    }


@router.get("/poll")
def poll(wait: float = 0.0):
    """Terminal tab calls this. Returns one paste payload or empty."""
    wait = max(0.0, min(2.0, float(wait or 0)))
    deadline = time.time() + wait
    while True:
        with _LOCK:
            while _QUEUE:
                item = _QUEUE.popleft()
                if item.get("consumed") or item.get("dropped"):
                    continue
                item["consumed"] = True
                item["consumed_at"] = time.time()
                global _LAST
                _LAST = {
                    "id": item["id"],
                    "consumed": True,
                    "at": item["consumed_at"],
                    "source": item.get("source"),
                    "preview": (item.get("text") or "")[:180],
                }
                return {
                    "ok": True,
                    "empty": False,
                    "id": item["id"],
                    "text": item["text"],
                    "show": bool(item.get("show")),
                    "source": item.get("source"),
                }
        if time.time() >= deadline:
            return {"ok": True, "empty": True}
        time.sleep(0.15)


@router.get("/status")
def status():
    with _LOCK:
        pending = sum(1 for it in _QUEUE if not it.get("consumed") and not it.get("dropped"))
        dropped = sum(1 for it in _BY_ID.values() if it.get("dropped"))
        return {
            "ok": True,
            "pending": pending,
            "dropped": dropped,
            "tracked": len(_BY_ID),
            "last": _LAST,
        }
