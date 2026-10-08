"""Negative tests for tool authority, using local fixtures instead of cloud services."""

import json
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from fastapi.testclient import TestClient

import app
import venice
from agentic import store


def _isolate_permissions(tmp_path, monkeypatch):
    monkeypatch.setattr(store, "DATA", tmp_path / "agentic_data")
    store.ensure()


def test_spend_disabled_stops_gpu_queue_before_cloud_boundary(tmp_path, monkeypatch):
    _isolate_permissions(tmp_path, monkeypatch)
    store.save_permissions({"allow_spend": False})
    calls = []
    monkeypatch.setattr(venice, "_queue_gpu_render", lambda args: calls.append(args) or "queued by fixture")

    response = TestClient(app.app).post(
        "/api/venice/tool",
        json={"name": "queue_gpu_render", "arguments": {"cloud": "colab", "packs": "1"}},
    )

    assert response.status_code == 200
    assert response.json()["ok"] is False
    assert "allow_spend=false" in response.json()["output"]
    assert calls == []


def test_hub_request_cannot_change_its_own_permissions(tmp_path, monkeypatch):
    _isolate_permissions(tmp_path, monkeypatch)
    store.save_permissions({"allow_file_write": False})
    calls = []

    def local_hub(method, path, body=None):
        calls.append((method, path, body))
        if method.upper() == "PUT" and path == "/api/agentic/permissions":
            return json.dumps(store.save_permissions(body))
        return "unexpected fixture request"

    monkeypatch.setattr(venice, "_hub_request", local_hub)
    response = TestClient(app.app).post(
        "/api/venice/tool",
        json={
            "name": "hub_request",
            "arguments": {
                "method": "PUT",
                "path": "/api/agentic/permissions",
                "body": '{"allow_file_write": true}',
            },
        },
    )

    assert response.status_code == 200
    assert response.json()["ok"] is False
    assert "permission" in response.json()["output"].lower()
    assert calls == []
    assert store.permissions()["allow_file_write"] is False

    approval = TestClient(app.app).post(
        "/api/venice/approval",
        json={"name": "hub_request", "arguments": {"method": "PUT", "path": "/api/agentic/%70ermissions"}},
    )
    assert approval.status_code == 403
    traversal = TestClient(app.app).post(
        "/api/venice/approval",
        json={"name": "hub_request", "arguments": {"method": "PUT", "path": "/api/other/%2e%2e/agentic/permissions"}},
    )
    assert traversal.status_code == 403


def test_generic_hub_request_cannot_queue_cloud_when_spend_is_off(tmp_path, monkeypatch):
    _isolate_permissions(tmp_path, monkeypatch)
    store.save_permissions({"allow_spend": False})
    calls = []
    monkeypatch.setattr(venice, "_hub_request", lambda *args: calls.append(args) or "queued by fixture")

    response = TestClient(app.app).post(
        "/api/venice/tool",
        json={"name": "hub_request", "arguments": {"method": "POST", "path": "/api/colab/queue", "body": "{}"}},
    )

    assert response.json()["ok"] is False
    assert "allow_spend=false" in response.json()["output"]
    assert calls == []


def test_risky_tool_requires_exact_single_use_approval(tmp_path, monkeypatch):
    _isolate_permissions(tmp_path, monkeypatch)
    store.save_permissions({"allow_spend": True})
    calls = []
    monkeypatch.setattr(venice, "_queue_gpu_render", lambda args: calls.append(args) or "queued by fixture")
    client = TestClient(app.app)
    tool = {"name": "queue_gpu_render", "arguments": {"cloud": "colab", "packs": "1"}}

    assert client.post("/api/venice/tool", json=tool).json()["ok"] is False
    assert calls == []
    approval = client.post("/api/venice/approval", json=tool)
    assert approval.status_code == 200
    token = approval.json()["token"]
    changed = {**tool, "arguments": {"cloud": "colab", "packs": "2"}, "approval_token": token}
    assert client.post("/api/venice/tool", json=changed).json()["ok"] is False
    assert calls == []

    token = client.post("/api/venice/approval", json=tool).json()["token"]
    approved = {**tool, "approval_token": token}
    assert client.post("/api/venice/tool", json=approved).json()["ok"] is True
    assert len(calls) == 1
    assert client.post("/api/venice/tool", json=approved).json()["ok"] is False
    assert len(calls) == 1


def test_corrupt_permission_store_blocks_mutation(tmp_path, monkeypatch):
    _isolate_permissions(tmp_path, monkeypatch)
    workspace = tmp_path / "workspace"
    workspace.mkdir()
    monkeypatch.setattr(venice, "WORKSPACE", workspace)
    (store.DATA / "permissions.json").write_text("{invalid", encoding="utf-8")

    response = TestClient(app.app).post(
        "/api/venice/tool",
        json={"name": "write_file", "arguments": {"path": "should-not-exist.txt", "content": "secret"}},
    )

    assert response.json()["ok"] is False
    assert "policy unavailable" in response.json()["output"]
    assert not (workspace / "should-not-exist.txt").exists()


def test_hub_http_failure_is_not_success(tmp_path, monkeypatch):
    _isolate_permissions(tmp_path, monkeypatch)
    class Missing(BaseHTTPRequestHandler):
        def do_GET(self):
            self.send_response(404)
            self.end_headers()
            self.wfile.write(b"missing")

        def log_message(self, *_args):
            pass

    server = ThreadingHTTPServer(("127.0.0.1", 0), Missing)
    serving = threading.Thread(target=server.serve_forever, daemon=True)
    serving.start()
    monkeypatch.setattr(venice, "_hub_base", lambda: f"http://127.0.0.1:{server.server_port}")
    try:
        response = TestClient(app.app).post(
            "/api/venice/tool",
            json={"name": "hub_request", "arguments": {"method": "GET", "path": "/api/missing"}},
        )
    finally:
        server.shutdown()
        server.server_close()
        serving.join(timeout=2)

    assert response.json()["ok"] is False
    assert response.json()["output"].startswith("Error: HTTP 404:")


def test_civitai_arguments_never_become_shell_syntax(tmp_path, monkeypatch):
    _isolate_permissions(tmp_path, monkeypatch)
    store.save_permissions({"allow_civitai_download": True})
    calls = []
    monkeypatch.setattr(venice, "_laptop_run", lambda code, lang, timeout: calls.append(code) or "fixture")

    bad, _ = venice.execute_tool_detailed(
        "download_civitai_lora", {"model_id": "123;id", "slug": "safe"}, preapproved=True,
    )
    assert bad.startswith("Error: model_id")
    assert calls == []

    slug = "name; echo SHOULD_NOT_RUN"
    ok, _ = venice.execute_tool_detailed(
        "download_civitai_lora", {"model_id": "123,456", "slug": slug}, preapproved=True,
    )
    assert ok == "fixture"
    assert calls == ["mkdir -p ~/civitai_dl && python3 ~/hub/static/term/civitai_red_dl.py "
                     "--id 123 --id 456 --slug 'name; echo SHOULD_NOT_RUN'"]
