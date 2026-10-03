"""Deep in-flight checks against a fake hub. Run: python test_inflight_deep.py"""
import copy, json, tempfile, threading, types
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

import inflight, runbook as r

IDLE = {
    "/api/colab/state": {"runner_online": False, "instance": {"running": False}, "recover_running": False, "jobs": []},
    "/api/thunder/state": {"instances": [], "snapshots": []},
    "/api/thunder/queue": {"runner": True, "jobs": [{"id": "t1", "status": "done"}, {"id": "t2", "status": "error"}, {"id": "t3", "status": "cancelled"}]},
    "/api/vast/state": {"credit": 1.2, "instances": [{"id": "9", "label": "e4b-litert-export", "status": "exited"}]},
    "/api/laptop/hypno/jobs": [],
}
state = copy.deepcopy(IDLE)
hits = []


class Hub(BaseHTTPRequestHandler):
    def do_GET(self):
        hits.append(self.path)
        if self.path not in state:
            self.send_response(404); self.end_headers(); return
        body = json.dumps(state[self.path]).encode()
        self.send_response(200); self.send_header("Content-Length", str(len(body))); self.end_headers(); self.wfile.write(body)

    def log_message(self, *a): pass


srv = ThreadingHTTPServer(("127.0.0.1", 0), Hub)
threading.Thread(target=srv.serve_forever, daemon=True).start()
inflight.HUB_API = f"http://127.0.0.1:{srv.server_address[1]}"
tmp = Path(tempfile.mkdtemp()); [(tmp / d).mkdir() for d in ("ltx_jobs", "gen_jobs", "agentic_data")]
inflight.HUB = tmp
inflight.subprocess = types.SimpleNamespace(run=lambda *a, **k: types.SimpleNamespace(stdout=""))   # no ssh sessions
r.AUDIT = tmp / "audit.jsonl"; r.PAUSED = tmp / "PAUSED"


def deep(**kw):
    inflight._cache["v"] = None
    return inflight.detect(deep=True, **kw)


def mutate(path, fn):
    global state
    state = copy.deepcopy(IDLE); fn(state[path])


assert deep() == [], deep()                                                       # everything idle: clear
print("idle -> clear")

cases = {
    "Colab runtime is running": ("/api/colab/state", lambda d: d["instance"].update(running=True)),
    "Colab recovery is running": ("/api/colab/state", lambda d: d.update(recover_running=True)),
    "colab job c1 running": ("/api/colab/state", lambda d: d.update(jobs=[{"id": "c1", "status": "running"}])),
    "Thunder instance up": ("/api/thunder/state", lambda d: d.update(instances=[{"id": "i-1"}])),
    "thunder job t9 queued": ("/api/thunder/queue", lambda d: d["jobs"].append({"id": "t9", "status": "queued"})),
    "Vast instance running (e4b-litert-export)": ("/api/vast/state", lambda d: d["instances"][0].update(status="running")),
    "laptop hypno job h1 rendering": ("/api/laptop/hypno/jobs", None),
}
for expect, (path, fn) in cases.items():
    state = copy.deepcopy(IDLE)
    if path == "/api/laptop/hypno/jobs":
        state[path] = [{"id": "h1", "status": "rendering"}]
    else:
        fn(state[path])
    got = deep()
    assert any(expect in g for g in got), (expect, got)
    print("blocks:", expect)

# cache: a second call within 30 s does not hit the hub again; clearing it does.
state = copy.deepcopy(IDLE); hits.clear(); inflight._cache["v"] = None
inflight.detect(deep=True); n = len(hits); inflight.detect(deep=True)
assert len(hits) == n and n == 5, (n, len(hits))
print("cache ok:", n, "requests, none on the repeat")

# hub unreadable: blocks, except for restart_hub.
inflight.HUB_API = "http://127.0.0.1:1"
assert any("hub API unreadable" in g for g in deep()), deep()
assert deep(allow_unknown=True) == []
print("unreadable hub blocks, restart_hub allowed")
assert r.execute("reboot", dry_run=True)["status"] == "deferred"
assert r.execute("restart_hub", dry_run=True)["status"] == "dry_run"
assert r.execute("apt_upgrade", dry_run=True)["status"] == "deferred"
print("runbook: reboot/apt deferred, restart_hub proceeds when the hub is down")

# runbook with a readable but busy hub defers even restart_hub (a restart would not kill remote work, but is conservative)
inflight.HUB_API = f"http://127.0.0.1:{srv.server_address[1]}"
state = copy.deepcopy(IDLE); state["/api/colab/state"]["instance"]["running"] = True
inflight._cache["v"] = None
x = r.execute("restart_hub", dry_run=True)
assert x["status"] == "deferred" and any("Colab" in w for w in x["why"]), x
state = copy.deepcopy(IDLE); inflight._cache["v"] = None
assert r.execute("restart_hub", dry_run=True)["status"] == "dry_run"
print("ALL PASS")
