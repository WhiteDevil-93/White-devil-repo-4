from pathlib import Path
from fastapi.testclient import TestClient

import colab
import thunder
from app import app


def test_b1_colab_state_missing_token(monkeypatch, tmp_path):
    # Ensure ~/.wanbot_token does not exist
    monkeypatch.setattr(Path, "home", lambda: tmp_path)
    client = TestClient(app)
    res = client.get("/api/colab/state")
    assert res.status_code == 200
    data = res.json()
    assert data["runner_online"] is False
    assert data["jobs"] == []


def test_b1_thunder_queue_missing_token(monkeypatch, tmp_path):
    # Ensure ~/.wanbot_token does not exist
    monkeypatch.setattr(Path, "home", lambda: tmp_path)
    client = TestClient(app)
    res = client.get("/api/thunder/queue")
    assert res.status_code == 200
    data = res.json()
    assert data["runner"] is False
    assert data["jobs"] == []


def test_b1_colab_packs_missing_jsonl(monkeypatch, tmp_path):
    # Point JSONL to non-existent file
    missing_jsonl = tmp_path / "missing_chains.jsonl"
    monkeypatch.setattr(colab, "JSONL", missing_jsonl)
    client = TestClient(app)
    res = client.get("/api/colab/packs")
    assert res.status_code == 200
    assert res.json() == []


def test_b2_thunder_queue14_action_routing(monkeypatch):
    called = {}

    def fake_wanbot14(method, path):
        called["method"] = method
        called["path"] = path
        return {"ok": True, "action": path}

    monkeypatch.setattr(thunder, "wanbot14", fake_wanbot14)
    client = TestClient(app)

    # Valid retry action
    res_retry = client.post("/api/thunder/queue/test-job-1/retry")
    assert res_retry.status_code == 200
    assert called["method"] == "POST"
    assert called["path"] == "/jobs/test-job-1/retry"

    # Valid cancel action
    res_cancel = client.post("/api/thunder/queue/test-job-2/cancel")
    assert res_cancel.status_code == 200
    assert called["method"] == "POST"
    assert called["path"] == "/jobs/test-job-2/cancel"

    # Invalid action -> 400
    res_bad = client.post("/api/thunder/queue/test-job-3/restart")
    assert res_bad.status_code == 400


def test_b3_generator_route():
    client = TestClient(app)
    res = client.get("/generator.html")
    assert res.status_code == 200
    assert "WAN 2.2 Prompt Generator" in res.text
    assert "/app/generator_relay.js" in res.text


def test_b3_shotwriter_routes():
    client = TestClient(app)
    res_index = client.get("/shotwriter/index.html")
    assert res_index.status_code == 200
    assert "Shotwriter" in res_index.text
    assert "/app/shotwriter_relay.js" in res_index.text

    res_js = client.get("/shotwriter/app.js")
    assert res_js.status_code == 200


def test_m1_venice_model_default():
    venice_html = (Path(__file__).parent / "static" / "venice" / "index.html").read_text()
    assert "default_model: 'zai-org-glm-5-2'" in venice_html
    assert "default_model: 'qwen-3-8-27b'" not in venice_html
    assert "'zai-org-glm-5-2'" in venice_html


def test_m2_gitignore_credentials():
    gitignore = (Path(__file__).parent.parent / ".gitignore").read_text()
    assert "credentials.json" in gitignore


def test_desktop_app_caching_and_manifest():
    client = TestClient(app)
    # Manifest endpoint should return no-store
    res_manifest = client.get("/api/manifest")
    assert res_manifest.status_code == 200
    assert "no-store" in res_manifest.headers.get("Cache-Control", "")
    manifest_data = res_manifest.json()
    assert manifest_data.get("web_rev") == 8 or manifest_data.get("web_rev") == 9

    # Desktop HTML should return no-store
    res_desktop = client.get("/app/desktop/index.html")
    assert res_desktop.status_code == 200
    assert "no-store" in res_desktop.headers.get("Cache-Control", "")

    # Static CSS and JS assets under /app/ should return no-cache (allowing 304 validation)
    res_css = client.get("/app/ui/forge.css")
    assert res_css.status_code == 200
    assert "no-store" not in res_css.headers.get("Cache-Control", "")
    assert "no-cache" in res_css.headers.get("Cache-Control", "")

    res_js = client.get("/app/ui/forge.js")
    assert res_js.status_code == 200
    assert "no-store" not in res_js.headers.get("Cache-Control", "")
    assert "no-cache" in res_js.headers.get("Cache-Control", "")

