"""Pillar 2 proof: daily_tool_budget enforced, one shared gate list, gated actions audited."""
import json

from fastapi.testclient import TestClient

import venice
from agentic import runner, store
from app import app


def test_budget_blocks_the_over_limit_call_and_logs(tmp_path, monkeypatch):
    monkeypatch.setattr(store, "DATA", tmp_path / "agentic_data")
    store.ensure()
    store.save_permissions({"daily_tool_budget": 2})

    r1 = venice.execute_tool_detailed("list_directory", {"path": "."})
    assert not str(r1[0]).startswith("Blocked:"), r1
    r2 = venice.execute_tool_detailed("list_directory", {"path": "."})
    assert not str(r2[0]).startswith("Blocked:"), r2

    r3 = venice.execute_tool_detailed("list_directory", {"path": "."})
    assert str(r3[0]).startswith("Blocked: daily tool budget reached"), r3

    audit_lines = (tmp_path / "agentic_data" / "audit.jsonl").read_text().splitlines()
    rows = [json.loads(l) for l in audit_lines if l.strip()]
    blocks = [r for r in rows if r.get("event") == "gate" and r.get("detail", {}).get("decision") == "blocked:budget"]
    assert blocks, "budget block was not audit-logged"
    assert blocks[-1]["detail"]["tool"] == "list_directory"
    assert blocks[-1]["detail"]["args"]

    usage = store._read("tool_usage.json")
    assert usage["count"] == 3


def test_budget_resets_on_a_new_day(tmp_path, monkeypatch):
    monkeypatch.setattr(store, "DATA", tmp_path / "agentic_data")
    store.ensure()
    store.save_permissions({"daily_tool_budget": 1})

    assert not venice.execute_tool("list_directory", {"path": "."}).startswith("Blocked:")
    assert venice.execute_tool("list_directory", {"path": "."}).startswith("Blocked:")

    usage = store._read("tool_usage.json")
    usage["day"] = "2000-01-01"
    store._write("tool_usage.json", usage)
    assert not venice.execute_tool("list_directory", {"path": "."}).startswith("Blocked:")


def test_gates_endpoint_is_the_shared_blocklist(tmp_path, monkeypatch):
    monkeypatch.setattr(store, "DATA", tmp_path / "agentic_data")
    client = TestClient(app)
    g = client.get("/api/agentic/gates").json()
    assert g["confirm_tools"] == store.CONFIRM_TOOLS
    assert g["dangerous_cmd"] == store.DANGEROUS_CMD
    assert g["daily_tool_budget"] == store.DEFAULT_PERMISSIONS["daily_tool_budget"]
    assert g["require_confirm_destructive"] is True


def test_confirm_reason_matches_shared_list():
    assert store.confirm_reason("delete_file", {})
    assert store.confirm_reason("hub_request", {"method": "POST", "path": "/api/x"})
    assert store.confirm_reason("hub_request", {"method": "GET", "path": "/api/x"}) is None
    assert store.confirm_reason("render_assess_adjust_cycle", {"action": "start"})
    assert store.confirm_reason("render_assess_adjust_cycle", {"action": "status"}) is None
    assert store.confirm_reason("run_laptop_command", {"code": "rm -rf ~/"})
    assert store.confirm_reason("run_laptop_command", {"code": "ls -la"})
    assert store.confirm_reason("list_directory", {"path": "."}) is None


def test_runner_blocks_confirmation_tools_in_background(tmp_path, monkeypatch):
    monkeypatch.setattr(store, "DATA", tmp_path / "agentic_data")
    perms = store.permissions()  # require_confirm_destructive defaults to True
    assert "allow_spend=false" in (runner._permission_blocks("hub_request", perms, {"method": "POST", "path": "/api/ltx/jobs"}) or "")
    assert "allow_spend=false" in (runner._permission_blocks("queue_gpu_render", perms, {"cloud": "colab"}) or "")
    perms = {**perms, "allow_spend": True}
    assert "needs in-chat user confirmation" in (runner._permission_blocks("hub_request", perms, {"method": "POST", "path": "/api/ltx/jobs"}) or "")
    assert "needs in-chat user confirmation" in (runner._permission_blocks("queue_gpu_render", perms, {"cloud": "colab"}) or "")
    assert runner._permission_blocks("hub_request", perms, {"method": "GET", "path": "/api/status"}) is None
    assert "needs in-chat user confirmation" in (runner._permission_blocks("run_laptop_command", perms, {"code": "rm -rf ~/"}) or "")
    assert "needs in-chat user confirmation" in (runner._permission_blocks("run_laptop_command", perms, {"code": "ls -la"}) or "")
    assert "needs in-chat user confirmation" in (runner._permission_blocks("render_assess_adjust_cycle", perms, {"action": "start"}) or "")
    assert runner._permission_blocks("render_assess_adjust_cycle", perms, {"action": "status"}) is None
