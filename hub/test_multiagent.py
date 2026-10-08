"""Parallel child jobs can be collected, but cannot approve risky parent actions."""
import json
import time

from fastapi.testclient import TestClient

import venice
from agentic import runner, store
from app import app


def _fake_done_chat(job, messages, model):
    """A local, no-network stand-in for the Venice call inside the job loop."""
    text = str(messages[-1]["content"])[:40]
    return {"choices": [{"message": {"role": "assistant", "content": "DONE: ok — " + text}}]}


def _wait_done(ids, timeout=25.0):
    deadline = time.time() + timeout
    while time.time() < deadline:
        statuses = [(store.get_job(i) or {}).get("status") for i in ids]
        if all(s in ("done", "max_steps", "error", "stopped", "needs_user") for s in statuses):
            return statuses
        time.sleep(0.1)
    return statuses


def test_delegate_fans_out_3_children_and_collects(tmp_path, monkeypatch):
    monkeypatch.setattr(store, "DATA", tmp_path / "agentic_data")
    store.ensure()
    store.save_config({"enabled": True})
    monkeypatch.setattr(runner, "_step_chat", _fake_done_chat)

    out = venice.execute_tool("delegate_to_subagent", {
        "tasks_json": json.dumps([
            {"role": "researcher", "task": "survey render pipeline"},
            {"role": "coder", "task": "write a helper"},
            {"role": "reviewer", "task": "check recent changes"},
        ])
    })
    payload = json.loads(out)
    children = payload["children"]
    assert len(children) == 3
    ids = [c["id"] for c in children]
    assert all(len(i) == 12 for i in ids)
    assert {c["role"] for c in children} == {"researcher", "coder", "reviewer"}

    statuses = _wait_done(ids)
    assert all(s == "done" for s in statuses), statuses

    # children carry role + parent-less records and are visible via the jobs API
    client = TestClient(app)
    jobs = client.get("/api/agentic/jobs").json()["jobs"]
    seen = [j for j in jobs if j.get("id") in ids]
    assert len(seen) == 3
    assert all(j.get("role") for j in seen)

    collected = json.loads(venice.execute_tool("collect_subagents", {"ids": ",".join(ids)}))
    assert len(collected["results"]) == 3
    assert all(r["status"] == "done" for r in collected["results"])
    assert all("DONE: ok" in (r.get("result") or "") for r in collected["results"])


def test_delegate_validates_input(tmp_path, monkeypatch):
    monkeypatch.setattr(store, "DATA", tmp_path / "agentic_data")
    assert venice.execute_tool("delegate_to_subagent", {"tasks_json": "not json"}).startswith("Error:")
    bad = json.dumps([{"role": "pirate", "task": "arr"}])
    out = venice.execute_tool("delegate_to_subagent", {"tasks_json": bad})
    assert "role must be one of" in out
    too_many = json.dumps([{"role": "researcher", "task": f"t{i}"} for i in range(5)])
    assert "at most 4" in venice.execute_tool("delegate_to_subagent", {"tasks_json": too_many})


def test_background_job_cannot_turn_model_review_into_approval(tmp_path, monkeypatch):
    monkeypatch.setattr(store, "DATA", tmp_path / "agentic_data")
    store.ensure()
    store.save_permissions({"allow_spend": True})
    calls = []
    monkeypatch.setattr(runner, "_run_tool", lambda *args, **kwargs: calls.append((args, kwargs)) or "executed")
    monkeypatch.setattr(runner, "_memory_preamble", lambda: "")
    monkeypatch.setattr(runner, "_step_chat", lambda *_: {"choices": [{"message": {
        "role": "assistant", "content": "ALLOW; I reviewed it",
        "tool_calls": [{"id": "t1", "function": {"name": "queue_gpu_render", "arguments": '{"cloud":"colab","packs":"1"}'}}],
    }}]})
    job = {"id": "background1", "goal": "queue a GPU render", "max_steps": 1, "status": "queued", "log": []}
    store.upsert_job(job)
    runner._job_loop(job["id"])

    completed = store.get_job(job["id"])
    assert completed["status"] == "max_steps"
    assert calls == []
    tool_events = [entry for entry in completed["log"] if entry.get("event") == "tool"]
    assert len(tool_events) == 1
    assert "needs in-chat user confirmation" in tool_events[0]["out"]


def test_runner_rechecks_current_permission_not_stale_job_snapshot(tmp_path, monkeypatch):
    monkeypatch.setattr(store, "DATA", tmp_path / "agentic_data")
    store.ensure()
    stale = {**store.permissions(), "allow_spend": True, "require_confirm_destructive": False}
    calls = []
    monkeypatch.setattr(venice, "_queue_gpu_render", lambda args: calls.append(args) or "queued")
    out = runner._run_tool("queue_gpu_render", {"cloud": "colab", "packs": "1"}, stale)
    assert "allow_spend=false" in out
    assert calls == []
