"""Guards added after the agentic-workflow review: the permission gate covers every
tool path, a hub restart cannot wedge the runner, schedules do not stack or drift,
and sub-agents cannot fan out further sub-agents."""
import time

from agentic import runner, store


def _fast_chat(job, messages, model):
    return {"choices": [{"message": {"role": "assistant", "content": "DONE: ok"}}]}


def _isolate(tmp_path, monkeypatch, **cfg):
    monkeypatch.setattr(store, "DATA", tmp_path / "agentic_data")
    store.ensure()
    store.save_config({"enabled": True, **cfg})
    monkeypatch.setattr(runner, "_step_chat", _fast_chat)


def test_run_in_terminal_obeys_allow_laptop_commands(tmp_path, monkeypatch):
    """run_in_terminal executes on the laptop just like run_laptop_command, so the
    same switch must gate it — otherwise the agent sidesteps the permission by
    picking the other tool name."""
    import venice

    _isolate(tmp_path, monkeypatch)
    store.save_permissions({"allow_laptop_commands": False})
    for tool, args in (
        ("run_in_terminal", {"command": "echo hi"}),
        ("run_laptop_command", {"code": "echo hi"}),
    ):
        out, _ = venice.execute_tool_detailed(tool, args)
        assert "allow_laptop_commands=false" in out, f"{tool} bypassed the gate: {out}"

    store.save_permissions({"allow_laptop_commands": True})
    out, _ = venice.execute_tool_detailed("run_in_terminal", {"command": "echo hi"})
    assert '"paste": true' in out


def test_permission_gate_is_shared_by_runner_and_chat(tmp_path, monkeypatch):
    """The background runner and the Venice chat executor must not drift apart."""
    import venice

    _isolate(tmp_path, monkeypatch)
    store.save_permissions({"allow_file_delete": False})
    perms = store.permissions()
    assert runner._permission_blocks("delete_file", perms) is not None
    out, _ = venice.execute_tool_detailed("delete_file", {"path": "x.txt"})
    assert "allow_file_delete=false" in out


def test_restart_orphaned_jobs_release_their_slot(tmp_path, monkeypatch):
    """Job threads are daemons, so a hub restart leaves rows saying "running" with
    no thread behind them. _running_jobs counts those, and max_parallel_jobs
    defaults to 1, so one restart used to queue every later goal forever."""
    _isolate(tmp_path, monkeypatch)
    store.upsert_job({"id": "ghost", "goal": "g", "status": "running", "created": time.time()})
    assert len(runner._running_jobs("parent")) == 1

    assert runner.mark_interrupted() == 1
    assert store.get_job("ghost")["status"] == "interrupted"
    assert len(runner._running_jobs("parent")) == 0

    # A job with a live thread registered must not be reaped.
    store.upsert_job({"id": "live", "goal": "g", "status": "running", "created": time.time()})
    runner._active["live"] = object()
    try:
        assert runner.mark_interrupted() == 0
        assert store.get_job("live")["status"] == "running"
    finally:
        runner._active.pop("live", None)


def test_schedule_does_not_stack_on_a_slow_run(tmp_path, monkeypatch):
    """A 5-minute schedule whose job takes 20 used to launch overlapping runs that
    piled into the queue and never drained."""
    _isolate(tmp_path, monkeypatch)
    now = time.time()
    store.upsert_job({"id": "slow", "goal": "g", "status": "running", "created": now})
    store.save_schedules([{
        "id": "s1", "enabled": True, "goal": "do x", "every_minutes": 5,
        "next_run": now - 1, "last_job_id": "slow",
    }])

    fired = runner.tick_due_schedules(now)
    assert len(fired) == 1
    assert fired[0]["skipped"] == "previous run still active"
    assert fired[0]["job"] == "slow"
    # Still only the one job — no second run was launched.
    assert len(store.list_jobs()) == 1


def test_schedule_next_run_does_not_drift(tmp_path, monkeypatch):
    """next_run anchors on the due time, not the tick time, so the up-to-60s
    scheduler granularity does not accumulate on every fire."""
    _isolate(tmp_path, monkeypatch)
    now = time.time()
    due = now - 90
    store.save_schedules([{
        "id": "s2", "enabled": True, "goal": "do y", "every_minutes": 5, "next_run": due,
    }])

    fired = runner.tick_due_schedules(now)
    nxt = store.list_schedules()[0]["next_run"]
    assert abs(nxt - (due + 300)) < 1, f"drifted: {nxt - due}s after the due time"
    assert len(fired) == 1
    worker = runner._active.get(fired[0]["job"])
    if worker is not None:
        worker.join(timeout=5)
    assert store.get_job(fired[0]["job"])["status"] == "done"


def test_subagents_cannot_delegate_further(tmp_path, monkeypatch):
    """delegate_to_subagent is in AGENT_TOOLS, so a sub-agent could fan out more.
    MAX_SUBAGENTS caps concurrency only — the overflow queues and kick_queued keeps
    draining it, so a self-similar task could enqueue work without bound."""
    _isolate(tmp_path, monkeypatch)
    perms = store.permissions()

    blocked = runner._run_tool("delegate_to_subagent", {"tasks_json": "[]"}, perms, role="researcher")
    assert "cannot delegate further" in blocked

    # A parent job (no role) still reaches the real handler.
    parent = runner._run_tool("delegate_to_subagent", {"tasks_json": "[]"}, perms, role=None)
    assert "cannot delegate further" not in parent
