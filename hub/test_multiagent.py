"""Pillar 3 proof: delegate_to_subagent fans out parallel child jobs, collect_subagents gathers them,
and the reviewer sub-agent gates risky actions in background jobs."""
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


def test_reviewer_pass_allows_safe_denies_dangerous(tmp_path, monkeypatch):
    monkeypatch.setattr(store, "DATA", tmp_path / "agentic_data")
    store.ensure()

    def fake_review_chat(job, messages, model):
        text = messages[-1]["content"]
        verdict = "DENY deletes user data" if "rm -rf" in text else "ALLOW safe and required"
        return {"choices": [{"message": {"role": "assistant", "content": verdict}}]}

    monkeypatch.setattr(runner, "_step_chat", fake_review_chat)
    job = {"goal": "tidy the renders folder", "log": []}

    allow, verdict = runner._reviewer_pass(job, "run_laptop_command", {"code": "rm -rf ~/renders"}, "m")
    assert allow is False
    assert verdict.startswith("DENY")

    allow, verdict = runner._reviewer_pass(job, "run_laptop_command", {"code": "ls ~/renders"}, "m")
    assert allow is True
    assert verdict.startswith("ALLOW")


def test_reviewer_fails_closed(tmp_path, monkeypatch):
    monkeypatch.setattr(store, "DATA", tmp_path / "agentic_data")

    def broken(job, messages, model):
        raise RuntimeError("Venice unreachable")

    monkeypatch.setattr(runner, "_step_chat", broken)
    allow, verdict = runner._reviewer_pass({"goal": "x"}, "hub_request", {"method": "POST"}, "m")
    assert allow is False
    assert "reviewer unavailable" in verdict


def test_preapproved_skips_confirmation_but_not_permission_flags(tmp_path, monkeypatch):
    monkeypatch.setattr(store, "DATA", tmp_path / "agentic_data")
    perms = store.permissions()  # require_confirm_destructive=True, allow_file_delete=False
    # preapproved confirm-action executes (hub unreachable -> tool-level error, not a block)
    out = runner._run_tool("hub_request", {"method": "POST", "path": "/api/status"}, perms, preapproved=True)
    assert "needs in-chat user confirmation" not in out
    # permission flags are reviewer-proof
    out = runner._run_tool("delete_file", {"path": "x"}, perms, preapproved=True)
    assert "allow_file_delete=false" in out
