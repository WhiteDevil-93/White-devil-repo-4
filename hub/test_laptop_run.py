from types import SimpleNamespace

from fastapi.testclient import TestClient

import laptop
from app import app


def test_run_rejects_empty_and_bad_lang():
    client = TestClient(app)
    assert client.post("/api/laptop/run", json={"lang": "bash", "code": "  "}).status_code == 400
    assert client.post("/api/laptop/run", json={"lang": "ruby", "code": "puts 1"}).status_code == 400


def test_run_ssh_payload(monkeypatch):
    seen = {}

    def fake_ssh(cmd, timeout=60, stdin=None):
        seen["cmd"] = cmd
        seen["timeout"] = timeout
        seen["stdin"] = stdin
        return SimpleNamespace(returncode=0, stdout="hi\n", stderr="")

    monkeypatch.setattr(laptop, "ssh", fake_ssh)
    client = TestClient(app)
    j = client.post("/api/laptop/run", json={"lang": "python", "code": "print(1)"}).json()
    assert j["ok"] is True
    assert j["exit"] == 0
    assert j["cwd"] == "~/venice_run"
    assert "last.py" in seen["cmd"] and "python3" in seen["cmd"]
    assert seen["stdin"].startswith("print(1)")


def test_ping_offline(monkeypatch):
    def boom(*a, **k):
        raise laptop.HTTPException(503, "Laptop is offline (asleep, or WSL not running).")

    monkeypatch.setattr(laptop.httpx, "get", lambda *a, **k: (_ for _ in ()).throw(RuntimeError("no")))
    monkeypatch.setattr(laptop, "ssh", boom)
    client = TestClient(app)
    j = client.get("/api/laptop/ping").json()
    assert j["online"] is False
