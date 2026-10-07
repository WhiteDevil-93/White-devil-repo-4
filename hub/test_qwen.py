"""Unit tests for Forge Hub Qwen router (/api/qwen)."""
from unittest.mock import patch
import pytest
from fastapi.testclient import TestClient

from app import app
import qwen

client = TestClient(app)


def test_qwen_state_ready(monkeypatch):
    monkeypatch.setenv("QWEN_CLIENT_KEY", "secret_qwen_token")

    def mock_probe(url, headers=None, timeout=4.0):
        if url.endswith("/healthz"):
            return 200, '{"status":"alive"}', 1.5
        if url.endswith("/readyz"):
            return 200, '{"object":"list","data":[{"id":"qwen-agent","object":"model"}]}', 2.0
        if url.endswith("/status"):
            return 200, '{"model":"qwen-agent","counters_since_restart":{"active":2,"completed":10,"failed":0,"rejected":0,"prompt_tokens":0,"completion_tokens":0},"max_output_tokens":4096,"max_concurrency":1}', 1.0
        return 404, "not found", 1.0

    with patch.object(qwen, "_probe_http", side_effect=mock_probe):
        resp = client.get("/api/qwen/state")
        assert resp.status_code == 200
        data = resp.json()
        assert data["status"] == "ready"
        assert data["gateway_alive"] is True
        assert data["model_ready"] is True
        assert data["model_alias"] == "qwen-agent"
        assert data["active_requests"] == 2
        assert data["max_concurrency"] == 1
        assert data["error"] is None
        # Ensure credentials are never leaked
        assert "key" not in data
        assert "Authorization" not in str(data)
        assert "secret_qwen_token" not in resp.text


def test_qwen_state_loading():
    def mock_probe(url, headers=None, timeout=4.0):
        if url.endswith("/healthz"):
            return 200, '{"status":"ok"}', 1.5
        if url.endswith("/readyz"):
            return 503, '{"error":"model upstream loading"}', 2.0
        return 404, "not found", 1.0

    with patch.object(qwen, "_probe_http", side_effect=mock_probe):
        resp = client.get("/api/qwen/state")
        assert resp.status_code == 200
        data = resp.json()
        assert data["status"] == "loading"
        assert data["gateway_alive"] is True
        assert data["model_ready"] is False
        assert "Model upstream not ready" in (data["error"] or "")


def test_qwen_state_offline():
    def mock_probe(url, headers=None, timeout=4.0):
        return 0, "Connection refused", 10.0

    with patch.object(qwen, "_probe_http", side_effect=mock_probe):
        resp = client.get("/api/qwen/state")
        assert resp.status_code == 200
        data = resp.json()
        assert data["status"] == "offline"
        assert data["gateway_alive"] is False
        assert data["model_ready"] is False
        assert "Gateway unreachable" in (data["error"] or "")


def test_qwen_vm_power_validation():
    # Bad instance ID format
    resp = client.post("/api/qwen/vm/power", json={"instance_id": "bad;id", "action": "start"})
    assert resp.status_code == 400

    # Unsupported action
    resp = client.post("/api/qwen/vm/power", json={"instance_id": "12345", "action": "reboot"})
    assert resp.status_code == 400

    # Valid action start
    with patch("vast.start", return_value={"success": True}):
        resp = client.post("/api/qwen/vm/power", json={"instance_id": "12345", "action": "start"})
        assert resp.status_code == 200
        assert resp.json()["success"] is True

    # Valid action stop
    with patch("vast.stop", return_value={"success": True}):
        resp = client.post("/api/qwen/vm/power", json={"instance_id": "12345", "action": "stop"})
        assert resp.status_code == 200
        assert resp.json()["success"] is True
