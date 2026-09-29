import json
import os
from pathlib import Path

from fastapi.testclient import TestClient

import venice
from app import app


def test_normalize_drops_junk_and_keeps_order(tmp_path, monkeypatch):
    monkeypatch.setattr(venice, "CHAT_FILES", (tmp_path / "chats.json", tmp_path / "alt.json"))
    raw = {
        "activeId": "keep",
        "chats": [
            {"id": "old", "title": "older", "updated": 1, "messages": [{"role": "user", "content": "a"}]},
            {"id": "keep", "title": "newer", "updated": 9, "messages": [{"role": "user", "content": "b"}, {"role": "assistant", "content": "c"}]},
            {"id": "empty", "title": "nope", "updated": 8, "messages": []},
            {"id": "bad", "messages": [{"role": "nope", "content": "x"}]},
        ],
    }
    out = venice.normalize_store(raw)
    assert [c["id"] for c in out["chats"]] == ["keep", "old"]
    assert out["activeId"] == "keep"
    assert venice.normalize_store({"chats": raw["chats"], "activeId": "missing"})["activeId"] is None


def test_chats_roundtrip(tmp_path, monkeypatch):
    dest = tmp_path / "chats.json"
    monkeypatch.setattr(venice, "CHAT_FILES", (dest, tmp_path / "alt.json"))
    client = TestClient(app)
    empty = client.get("/api/venice/chats").json()
    assert empty == {"activeId": None, "chats": []}
    body = {
        "activeId": "c1",
        "chats": [{
            "id": "c1",
            "title": "ping",
            "updated": 100,
            "model": "qwen-3-8-27b",
            "system": "",
            "messages": [{"role": "user", "content": "ping"}, {"role": "assistant", "content": "pong"}],
        }],
    }
    saved = client.put("/api/venice/chats", json=body).json()
    assert saved["activeId"] == "c1"
    assert saved["chats"][0]["messages"][1]["content"] == "pong"
    assert dest.is_file()
    if os.name != "nt":
        # chmod is a no-op on Windows dev machines; the mode holds on the Linux relay.
        assert oct(dest.stat().st_mode)[-3:] == "600"
    again = client.get("/api/venice/chats").json()
    assert again["chats"][0]["title"] == "ping"
    st = client.get("/api/venice/status").json()
    assert st["chats"] == 1
