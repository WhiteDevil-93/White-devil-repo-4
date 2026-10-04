"""POST guard of the caretaker web service. Run: python test_server.py"""
import json, tempfile, threading, urllib.error, urllib.request
from http.server import ThreadingHTTPServer
from pathlib import Path

import server
import runbook as r

tmp = Path(tempfile.mkdtemp())
r.AUDIT = tmp / "audit.jsonl"
r.PAUSED = tmp / "PAUSED"
srv = ThreadingHTTPServer(("127.0.0.1", 0), server.H)
threading.Thread(target=srv.serve_forever, daemon=True).start()
BASE = f"http://127.0.0.1:{srv.server_address[1]}/caretaker/api"


def post(path, body, headers):
    req = urllib.request.Request(BASE + path, data=json.dumps(body).encode(), headers=headers, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=10) as resp:
            return resp.status, json.loads(resp.read().decode())
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read().decode() or "{}")


# What a cross-site HTML form could send: no custom header, a form/text content type. Must be refused.
for ctype in ("text/plain", "application/x-www-form-urlencoded", "multipart/form-data; boundary=x", ""):
    code, _ = post("/pause", {"paused": True}, {"Content-Type": ctype} if ctype else {})
    assert code == 403, (ctype, code)
    assert not r.PAUSED.exists(), "a refused request must not pause anything"
print("form-style POSTs refused")

# The web page: custom header, any content type.
code, body = post("/pause", {"paused": True}, {"X-Requested-With": "caretaker", "Content-Type": "text/plain"})
assert code == 200 and body == {"paused": True} and r.PAUSED.exists(), (code, body)
# The desktop app / API clients: JSON content type, no custom header (charset suffix and odd case allowed).
code, body = post("/pause", {"paused": False}, {"Content-Type": "Application/JSON; charset=utf-8"})
assert code == 200 and body == {"paused": False} and not r.PAUSED.exists(), (code, body)
print("page (header) and clients (JSON) accepted")

# Bad bodies are still rejected after the guard.
req = urllib.request.Request(BASE + "/chat", data=b"not json", headers={"Content-Type": "application/json"}, method="POST")
try:
    urllib.request.urlopen(req, timeout=10); raise SystemExit("bad json accepted")
except urllib.error.HTTPError as e:
    assert e.code == 400, e.code
code, _ = post("/chat", {"message": "  "}, {"Content-Type": "application/json"})
assert code == 400, code
print("ALL PASS")
