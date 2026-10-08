"""Pillar 5 proof: due schedules fire from the in-process tick (no HTTP caller), and
the new_render watcher fires a goal when a new clip appears — once, not repeatedly."""
import time

from fastapi.testclient import TestClient

from agentic import runner, store
from app import app


def _fast_chat(job, messages, model):
    return {"choices": [{"message": {"role": "assistant", "content": "DONE: scheduled tick ok"}}]}


def _wait_job(job_id, timeout=15):
    deadline = time.time() + timeout
    while time.time() < deadline:
        job = store.get_job(job_id) or {}
        if job.get("status") in ("done", "error", "max_steps", "stopped", "needs_user"):
            worker = runner._active.get(job_id)
            if worker is not None:
                worker.join(timeout=2)
            return job
        time.sleep(0.05)
    raise AssertionError(f"job did not finish: {job_id}: {store.get_job(job_id)}")


def test_due_schedule_fires_without_http_tick(tmp_path, monkeypatch):
    monkeypatch.setattr(store, "DATA", tmp_path / "agentic_data")
    store.ensure()
    store.save_config({"enabled": True})
    monkeypatch.setattr(runner, "_step_chat", _fast_chat)

    store.save_schedules([{
        "id": "sch1",
        "goal": "Every morning review the nightly renders",
        "every_minutes": 60,
        "label": "morning review",
        "enabled": True,
        "created": time.time() - 7200,
        "next_run": time.time() - 61,  # overdue
        "last_run": None,
        "last_job_id": None,
    }])

    fired = runner.tick_due_schedules()
    assert len(fired) == 1
    assert fired[0]["schedule"] == "sch1"
    job_id = fired[0]["job"]
    assert job_id

    row = store.list_schedules()[0]
    assert row["last_job_id"] == job_id
    assert row["last_run"]
    assert float(row["next_run"]) > time.time()

    job = _wait_job(job_id)
    assert job["status"] == "done", job.get("error")
    assert "scheduled tick ok" in (job.get("result") or "")

    # not due again -> second tick fires nothing
    assert runner.tick_due_schedules() == []


def test_tick_noop_when_runners_disabled(tmp_path, monkeypatch):
    monkeypatch.setattr(store, "DATA", tmp_path / "agentic_data")
    store.ensure()  # config enabled defaults to False
    store.save_schedules([{
        "id": "sch2", "goal": "x", "every_minutes": 60, "label": "x", "enabled": True,
        "created": time.time() - 7200, "next_run": time.time() - 61, "last_run": None, "last_job_id": None,
    }])
    assert runner.tick_due_schedules() == []
    client = TestClient(app)
    res = client.post("/api/agentic/schedules/tick").json()
    assert res["fired"] == []
    assert res.get("reason") == "runners disabled"


def test_new_render_watcher_fires_once(tmp_path, monkeypatch):
    monkeypatch.setattr(store, "DATA", tmp_path / "agentic_data")
    store.ensure()
    store.save_config({"enabled": True})
    monkeypatch.setattr(runner, "_step_chat", _fast_chat)

    client = TestClient(app)
    w = client.post("/api/agentic/watchers", json={"goal": "Assess render {render}"}).json()
    assert w["id"] and w["kind"] == "new_render"

    monkeypatch.setattr(runner, "_render_newest", lambda: "smoke_0001.mp4")
    fired = runner.tick_watchers()
    assert len(fired) == 1
    assert fired[0]["render"] == "smoke_0001.mp4"
    job = _wait_job(fired[0]["job"])
    assert job["status"] == "done"

    jobs = {j["id"]: j for j in client.get("/api/agentic/jobs").json()["jobs"]}
    job = jobs.get(fired[0]["job"])
    assert job is not None
    assert "Assess render smoke_0001.mp4" in (job.get("goal") or "")

    # same render again -> no refire
    assert runner.tick_watchers() == []
    # newer render -> fires again
    monkeypatch.setattr(runner, "_render_newest", lambda: "smoke_0002.mp4")
    fired2 = runner.tick_watchers()
    assert len(fired2) == 1 and fired2[0]["render"] == "smoke_0002.mp4"
    assert _wait_job(fired2[0]["job"])["status"] == "done"

    audit = (tmp_path / "agentic_data" / "audit.jsonl").read_text()
    assert "watcher_fire" in audit


def test_watchers_crud(tmp_path, monkeypatch):
    monkeypatch.setattr(store, "DATA", tmp_path / "agentic_data")
    client = TestClient(app)
    w = client.post("/api/agentic/watchers", json={}).json()
    listing = client.get("/api/agentic/watchers").json()["watchers"]
    assert any(x["id"] == w["id"] for x in listing)
    assert client.delete(f"/api/agentic/watchers/{w['id']}").json()["ok"] is True
    assert all(x["id"] != w["id"] for x in client.get("/api/agentic/watchers").json()["watchers"])
