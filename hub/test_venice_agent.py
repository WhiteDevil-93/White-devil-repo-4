import json
import os

from fastapi.testclient import TestClient

import venice
from app import app


def test_tools_catalog_matches_android_plus_terminal():
    client = TestClient(app)
    data = client.get("/api/venice/tools").json()
    names = [t["function"]["name"] for t in data["tools"]]
    for need in (
        "read_file",
        "write_file",
        "list_directory",
        "delete_file",
        "get_render_status",
        "review_latest_render",
        "list_prompt_packs",
        "run_laptop_command",
        "run_in_terminal",
        "download_civitai_lora",
        "remember",
        "delegate_to_subagent",
        "collect_subagents",
        "transcribe_audio",
    ):
        assert need in names
    assert "/review" in data["system_prompt"]
    assert data["max_iterations"] == venice.MAX_TOOL_ITERATIONS
    assert "WhiteDevil" in data["system_prompt"]
    st = client.get("/api/venice/status").json()
    assert st["agent"] is True
    assert "run_in_terminal" in st["tools"]
    assert st["default_model"] == (os.environ.get("VENICE_MODEL") or venice.DEFAULT_MODEL)


def test_models_report_tool_capability_and_ui_gates_on_it():
    # A model that cannot emit tool_calls narrates the call instead, executes
    # nothing, and then invents the result — that is how a fabricated
    # "Colab: Idle - Thunder: Idle" status reached a live transcript. The picker
    # must be able to tell the two apart.
    client = TestClient(app)
    models = client.get("/api/venice/models").json()["models"]
    by_id = {m["id"]: m for m in models}
    assert all("supports_tools" in m for m in models)

    for bad in venice.TOOL_INCAPABLE_MODELS:
        if bad in by_id:
            assert by_id[bad]["supports_tools"] is False, bad
    # Measured tool-capable on 2026-10-02; the uncensored one matters because it
    # is the drop-in for the uncensored models that cannot drive the agent.
    for good in ("zai-org-glm-5-2", "gemma-4-uncensored"):
        if good in by_id:
            assert by_id[good]["supports_tools"] is True, good
    assert "venice-uncensored-1-2" in venice.TOOL_INCAPABLE_MODELS

    page = client.get("/app/venice/").text
    assert "supportsTools" in page
    assert "no tools — cannot run the agent" in page
    assert "cannot call tools" in page
    # Unknown ids must default to usable, not blocked.
    assert "(m in modelTools) ? !!modelTools[m] : true" in page


def test_every_known_uncensored_model_is_in_the_picker():
    # Measured on Venice 2026-10-03 (/models + a live call each). The Android picker lists the same ids.
    client = TestClient(app)
    by_id = {m["id"]: m for m in client.get("/api/venice/models").json()["models"]}
    for mid in ("venice-uncensored-1-2", "venice-uncensored-role-play", "gemma-4-uncensored", "qwen-3-6-plus",
                "olafangensan-glm-4.7-flash-heretic", "abliteration-abliterated-model-large-v2",
                "e2ee-gemma-4-26b-a4b-uncensored-p"):
        assert by_id[mid]["uncensored"] is True, mid
    assert by_id["e2ee-gemma-4-26b-a4b-uncensored-p"]["supports_tools"] is False
    assert by_id["gemma-4-uncensored"]["supports_tools"] is True


def test_workspace_file_tools(tmp_path, monkeypatch):
    monkeypatch.setattr(venice, "WORKSPACE", tmp_path / "ws")
    client = TestClient(app)
    listed = client.post("/api/venice/tool", json={"name": "list_directory", "arguments": {"path": "."}}).json()
    assert listed["ok"] is True
    assert listed["output"] == "(empty directory)"

    wrote = client.post("/api/venice/tool", json={
        "name": "write_file",
        "arguments": {"path": "notes/hi.txt", "content": "hello agent"},
    }).json()
    assert "Wrote 11 characters" in wrote["output"]

    read = client.post("/api/venice/tool", json={"name": "read_file", "arguments": {"path": "notes/hi.txt"}}).json()
    assert read["output"] == "hello agent"

    escaped = client.post("/api/venice/tool", json={"name": "read_file", "arguments": {"path": "../secret"}}).json()
    assert escaped["ok"] is False
    assert "escapes" in escaped["output"]

    # delete_file is gated by allow_file_delete, which ships off. The chat path
    # used to ignore that switch while the background runner honoured it.
    blocked = client.post("/api/venice/tool", json={"name": "delete_file", "arguments": {"path": "notes/hi.txt"}}).json()
    assert "allow_file_delete=false" in blocked["output"]

    from agentic import store as agentic_store

    before = bool(agentic_store.permissions().get("allow_file_delete"))
    agentic_store.save_permissions({"allow_file_delete": True})
    try:
        delete_call = {"name": "delete_file", "arguments": {"path": "notes/hi.txt"}}
        assert client.post("/api/venice/tool", json=delete_call).json()["ok"] is False
        approval = client.post("/api/venice/approval", json=delete_call)
        assert approval.status_code == 200
        deleted = client.post("/api/venice/tool", json={
            **delete_call, "approval_token": approval.json()["token"],
        }).json()
        assert deleted["output"] == "Deleted notes/hi.txt"
    finally:
        agentic_store.save_permissions({"allow_file_delete": before})


def test_run_in_terminal_returns_paste_payload():
    client = TestClient(app)
    j = client.post("/api/venice/tool", json={
        "name": "run_in_terminal",
        "arguments": {"command": "ls -la ~/venice_run"},
    }).json()
    assert j["ok"] is True
    payload = json.loads(j["output"])
    assert payload["paste"] is True
    assert payload["command"] == "ls -la ~/venice_run"


def test_run_laptop_command_tool(monkeypatch):
    seen = {}

    def fake_run(body):
        seen["lang"] = body.lang
        seen["code"] = body.code
        return {"ok": True, "exit": 0, "lang": body.lang, "cwd": "~/venice_run", "output": "hi"}

    monkeypatch.setattr("laptop.run", fake_run)
    client = TestClient(app)
    j = client.post("/api/venice/tool", json={
        "name": "run_laptop_command",
        "arguments": {"code": "echo hi", "lang": "bash"},
    }).json()
    assert seen["code"] == "echo hi"
    out = json.loads(j["output"])
    assert out["ok"] is True
    assert out["output"] == "hi"


def test_payload_includes_tools():
    req = venice.ChatIn(
        messages=[{"role": "user", "content": "hi"}],
        stream=False,
        tools=venice.AGENT_TOOLS,
    )
    body = venice.payload_of(req)
    assert body["stream"] is False
    assert body["tools"][0]["function"]["name"] == "read_file"


def test_unknown_tool():
    client = TestClient(app)
    j = client.post("/api/venice/tool", json={"name": "explode_server", "arguments": {}}).json()
    assert j["ok"] is False
    assert "unknown tool" in j["output"]


def test_win_to_wsl_path_conversion():
    # Regression: upload_to_colab called re.match without importing re, so every
    # Windows path (and every path — re.match ran unconditionally) returned
    # "Error: name 're' is not defined" from the tool dispatcher.
    assert venice._win_to_wsl(r"C:\Users\x\a.safetensors") == "/mnt/c/Users/x/a.safetensors"
    assert venice._win_to_wsl(r"D:/models/lora.safetensors") == "/mnt/d/models/lora.safetensors"
    assert venice._win_to_wsl("/mnt/c/Users/x/a.safetensors") == "/mnt/c/Users/x/a.safetensors"
    out = venice.execute_tool("upload_to_colab", {"local_path": r"C:\Users\x\missing.safetensors"})
    assert "name 're' is not defined" not in out
    assert out.startswith("Error:")  # real path/ssh failure is fine; the NameError is not


def test_chats_keep_tool_events(tmp_path, monkeypatch):
    dest = tmp_path / "chats.json"
    monkeypatch.setattr(venice, "CHAT_FILES", (dest, tmp_path / "alt.json"))
    client = TestClient(app)
    body = {
        "activeId": "c1",
        "chats": [{
            "id": "c1",
            "title": "agent",
            "updated": 1,
            "model": "zai-org-glm-5-2",
            "system": venice.DEFAULT_SYSTEM_PROMPT,
            "messages": [
                {"role": "user", "content": "status?"},
                {"role": "tool_call", "name": "get_render_status", "content": "{}"},
                {"role": "tool", "name": "get_render_status", "content": '{"clips": 0}', "tool_call_id": "c1"},
                {"role": "assistant", "content": "idle"},
            ],
        }],
    }
    saved = client.put("/api/venice/chats", json=body).json()
    roles = [m["role"] for m in saved["chats"][0]["messages"]]
    assert roles == ["user", "tool_call", "tool", "assistant"]
