"""Pillar 1 proof: the main chat agent reads and writes the agentic memory store."""
import json

import venice
from agentic import store as agentic_store
from app import app


def test_remember_note_then_injected_into_next_turn_payload(tmp_path, monkeypatch):
    monkeypatch.setattr(agentic_store, "DATA", tmp_path / "agentic_data")

    text, _images = venice.execute_tool_detailed("remember", {"text": "user prefers 24fps renders"})
    assert text == "Remembered note."

    mem = agentic_store.memory()
    assert any("24fps" in (n.get("text") or "") for n in mem.get("notes") or [])

    body = venice.payload_of(venice.ChatIn(
        messages=[
            {"role": "system", "content": "You are WhiteDevil."},
            {"role": "user", "content": "hi"},
        ],
        stream=False,
    ))
    sys_payload = body["messages"][0]
    assert sys_payload["role"] == "system"
    assert "24fps" in sys_payload["content"]
    assert "Persistent memory" in sys_payload["content"]
    # user turn untouched
    assert body["messages"][-1]["content"] == "hi"


def test_remember_preference_key_and_preamble_format(tmp_path, monkeypatch):
    monkeypatch.setattr(agentic_store, "DATA", tmp_path / "agentic_data")

    text, _ = venice.execute_tool_detailed("remember", {"text": "cinematic", "key": "style"})
    assert text.startswith("Remembered preference: style")

    mem = agentic_store.memory()
    assert (mem.get("preferences") or {}).get("style") == "cinematic"

    body = venice.payload_of(venice.ChatIn(messages=[{"role": "user", "content": "next"}], stream=False))
    # no system message from the client -> one is prepended carrying the preference
    assert body["messages"][0]["role"] == "system"
    assert "cinematic" in json.dumps(body) or "cinematic" in body["messages"][0]["content"]
    assert "User preferences" in body["messages"][0]["content"]


def test_memory_injection_inert_when_store_empty(tmp_path, monkeypatch):
    monkeypatch.setattr(agentic_store, "DATA", tmp_path / "agentic_data")
    msgs = [{"role": "system", "content": "base"}, {"role": "user", "content": "hi"}]
    assert venice._apply_memory(msgs) == msgs


def test_remember_requires_text(tmp_path, monkeypatch):
    monkeypatch.setattr(agentic_store, "DATA", tmp_path / "agentic_data")
    text, _ = venice.execute_tool_detailed("remember", {})
    assert text.startswith("Error:")
