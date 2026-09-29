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
    # web_rev bumps with every static-UI rev; only require a sane minimum.
    assert int(manifest_data.get("web_rev") or 0) >= 15

    # Desktop HTML should return no-store
    res_desktop = client.get("/app/desktop/index.html")
    assert res_desktop.status_code == 200
    assert "no-store" in res_desktop.headers.get("Cache-Control", "")
    assert "Update Hub" in res_desktop.text
    assert "/app/term/?update=1" in res_desktop.text
    assert "ForgeDesktopTermPaste" in res_desktop.text
    assert "html.app #pop" in res_desktop.text
    assert "forge:term-paste" in res_desktop.text
    assert "Operator" in res_desktop.text
    assert "local session" in res_desktop.text
    assert "venice-chrome" in res_desktop.text

    res_home = client.get("/app/home/")
    assert res_home.status_code == 200
    assert "Welcome back" in res_home.text
    assert "Here's what the render farm has been up to." in res_home.text
    # Stat-card labels are title-case since the UX Pilot home redesign.
    assert "Newest Clip" in res_home.text
    assert "Render Queue" in res_home.text
    assert "Storage Used" in res_home.text
    assert "Update available" in res_home.text
    assert "/app/term/?update=1" in res_home.text
    assert "Open Gallery" in res_home.text
    assert "now-banner" in res_home.text

    res_term = client.get("/app/term/")
    assert res_term.status_code == 200
    assert "loraIds" in res_term.text
    assert "every LoRA file" in res_term.text
    assert "ForgeTermReceive" in res_term.text
    assert "forge:term-paste" in res_term.text

    res_venice = client.get("/app/venice/")
    assert res_venice.status_code == 200
    assert "Venice" in res_venice.text
    assert "Venice Bench" not in res_venice.text
    assert "run_laptop_command" in res_venice.text
    assert "run_in_terminal" in res_venice.text
    assert "forge:term-paste" in res_venice.text
    assert "Give Venice a goal or feedback" in res_venice.text
    assert "btn-send" in res_venice.text
    assert "idea-input" in res_venice.text

    # Static CSS and JS assets under /app/ should return no-cache (allowing 304 validation)
    res_css = client.get("/app/ui/forge.css")
    assert res_css.status_code == 200
    assert "no-store" not in res_css.headers.get("Cache-Control", "")
    assert "no-cache" in res_css.headers.get("Cache-Control", "")

    res_js = client.get("/app/ui/forge.js")
    assert res_js.status_code == 200
    assert "no-store" not in res_js.headers.get("Cache-Control", "")
    assert "no-cache" in res_js.headers.get("Cache-Control", "")


def test_setup_saves_full_ltx_lora_pack(monkeypatch, tmp_path):
    import setup as setup_mod
    monkeypatch.setattr(setup_mod, "STATE", tmp_path / "forge_setup.json")
    client = TestClient(app)

    ids = [s["id"] for s in client.get("/api/manifest").json()["screens"]]
    assert "setup" in ids and "ltx" in ids

    empty = client.get("/api/setup")
    assert empty.status_code == 200
    data = empty.json()
    # The pack is the 11 Lightricks LoRAs plus the NSFW content set, so pin the
    # Lightricks half exactly and let the content set grow (setup.py _lightricks
    # already hard-fails if the Lightricks catalog is not exactly 11).
    assert data["lightricks"] == 11
    assert data["nsfw"] > 0
    assert data["count"] == data["lightricks"] + data["nsfw"]
    assert data["saved"] is False
    assert data["enabled"] == 0
    names = [r["name"] for r in data["loras"]]
    assert "Distilled 450" in names
    assert "Cinemagraph" in names
    assert len({r["id"] for r in data["loras"]}) == data["count"]

    saved = client.post("/api/setup", json={})
    assert saved.status_code == 200
    body = saved.json()
    assert body["saved"] is True
    assert body["enabled"] == data["count"]
    assert all(r["enabled"] for r in body["loras"])

    ltx = client.get("/app/ltx/")
    assert ltx.status_code == 200
    # The Setup pack surfaces on LTX as the "Setup pack" pill (it was an <h2>
    # before the redesign); what matters is that LTX still reads /api/setup.
    assert "Setup pack" in ltx.text
    assert "/api/setup" in ltx.text

    sh = client.get("/api/setup/download.sh")
    assert sh.status_code == 200
    assert sh.text.count("huggingface.co/Lightricks/") == 11


