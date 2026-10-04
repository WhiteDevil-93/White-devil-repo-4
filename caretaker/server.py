"""Caretaker domain service. Own process, own port, independent of forge-hub.

Serves /caretaker/ (page) and /caretaker/api/* behind Caddy's renders_auth (same login as the app).
Binds 127.0.0.1 only. Chat is READ-ONLY: the model sees facts + audit log and can explain; it cannot act.
POSTs need either the header X-Requested-With: caretaker (the web page) or Content-Type: application/json
(the desktop and other API clients). A cross-site HTML form can send neither, and a cross-site fetch with
either one triggers a CORS preflight that this server never answers, so a malicious page cannot trigger them.
"""
import json, threading, time, re
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

import facts as F, inflight, runbook as R
import loop

PORT = 9100
PREFIX = "/caretaker"
IDLE_UNLOAD_S = 20 * 60
_lock = threading.Lock()          # one inference at a time (4 cores)
_engine = {"e": None, "used": 0.0}


def _get_engine():
    import litert_lm as l
    if _engine["e"] is None:
        _engine["e"] = l.Engine(loop.MODEL, backend=l.Backend.CPU(), max_num_tokens=3072,
                                cache_dir=str(Path.home() / "litert-cache"))
    _engine["used"] = time.time()
    return _engine["e"]


def _reaper():
    while True:
        time.sleep(60)
        with _lock:
            if _engine["e"] is not None and time.time() - _engine["used"] > IDLE_UNLOAD_S:
                _engine["e"] = None          # frees ~3.7 GB; next chat reloads (~16 s)


def audit_tail(n=40):
    try:
        lines = R.AUDIT.read_text().splitlines()[-n:]
        return [json.loads(x) for x in lines]
    except Exception:
        return []


def snapshot():
    f = F.collect()
    return {"facts": f, "anomalies": loop.anomalies(f), "inflight": inflight.detect(),
            "paused": R.PAUSED.exists(), "live": loop.LIVE, "model_loaded": _engine["e"] is not None}


CHAT_SYSTEM = ("You are the caretaker of a small Linux relay server (wan-relay). You are READ-ONLY in this chat: "
               "you can explain what you see in the facts and activity log, but you cannot run anything. "
               "If asked to do something, say what the runbook action would be and that it runs from the schedule "
               "or when the user does it. Be short and concrete. Never invent facts that are not in the data.")


def chat(message, history):
    snap = snapshot()
    ctx = (f"LIVE FACTS: {json.dumps(snap['facts'])}\nANOMALIES: {snap['anomalies'] or 'none'}\n"
           f"WORK IN FLIGHT: {snap['inflight'] or 'none'}\nPAUSED: {snap['paused']}  LIVE: {snap['live']}\n"
           f"RECENT ACTIVITY: {json.dumps(audit_tail(12))[:1800]}")
    msgs = [{"role": h["role"], "content": h["content"]} for h in history[-6:]
            if h.get("role") in ("user", "assistant") and isinstance(h.get("content"), str)]
    with _lock:
        c = _get_engine().create_conversation(messages=msgs or None, system_message=CHAT_SYSTEM + "\n\n" + ctx,
                                              max_output_tokens=220)
        r = c.send_message({"role": "user", "content": message[:1500]})
        _engine["used"] = time.time()
    txt = "".join(p.get("text", "") for p in r["content"] if p.get("type") == "text")
    return re.sub(r"<\|channel>thought.*?<channel\|>", "", txt, flags=re.S).strip()


PAGE = (Path(__file__).parent / "page.html")


class H(BaseHTTPRequestHandler):
    def _send(self, code, body, ctype="application/json"):
        b = body if isinstance(body, bytes) else (body if isinstance(body, str) else json.dumps(body)).encode()
        self.send_response(code); self.send_header("Content-Type", ctype + "; charset=utf-8")
        self.send_header("Content-Length", str(len(b))); self.send_header("Cache-Control", "no-store")
        self.end_headers(); self.wfile.write(b)

    def do_GET(self):
        if self.path.split("?")[0] == PREFIX:            # relative api/ URLs need the trailing slash
            self.send_response(301); self.send_header("Location", PREFIX + "/"); self.end_headers(); return
        p = self.path.split("?")[0].rstrip("/")
        if p == PREFIX:
            return self._send(200, PAGE.read_bytes(), "text/html")
        if p == PREFIX + "/api/state":
            return self._send(200, snapshot())
        if p == PREFIX + "/api/audit":
            return self._send(200, audit_tail(80))
        self._send(404, {"error": "not found"})

    def do_POST(self):
        json_type = self.headers.get("Content-Type", "").split(";")[0].strip().lower() == "application/json"
        if self.headers.get("X-Requested-With") != "caretaker" and not json_type:
            return self._send(403, {"error": "send Content-Type: application/json (or X-Requested-With: caretaker)"})
        try:
            body = json.loads(self.rfile.read(min(int(self.headers.get("Content-Length", 0)), 20000)) or b"{}")
        except Exception:
            return self._send(400, {"error": "bad json"})
        p = self.path.split("?")[0].rstrip("/")
        if p == PREFIX + "/api/pause":
            if body.get("paused"):
                R.PAUSED.write_text("paused from caretaker page\n")
            else:
                R.PAUSED.unlink(missing_ok=True)
            R.audit(event="pause_toggled", paused=bool(body.get("paused")))
            return self._send(200, {"paused": R.PAUSED.exists()})
        if p == PREFIX + "/api/chat":
            msg = str(body.get("message", "")).strip()
            if not msg:
                return self._send(400, {"error": "empty message"})
            try:
                return self._send(200, {"reply": chat(msg, body.get("history") or [])})
            except Exception as e:
                return self._send(500, {"error": f"{type(e).__name__}: {str(e)[:200]}"})
        self._send(404, {"error": "not found"})

    def log_message(self, *a):
        pass


if __name__ == "__main__":
    threading.Thread(target=_reaper, daemon=True).start()
    ThreadingHTTPServer(("127.0.0.1", PORT), H).serve_forever()
