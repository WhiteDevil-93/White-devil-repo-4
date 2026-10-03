import json, tempfile, time
from pathlib import Path
import inflight, runbook as r

tmp = Path(tempfile.mkdtemp()); r.AUDIT = tmp / "audit.jsonl"; r.PAUSED = tmp / "PAUSED"
fake = tmp / "hub"; [(fake / d).mkdir(parents=True) for d in ("ltx_jobs", "gen_jobs", "agentic_data")]
inflight.HUB = fake
def res(*a, **k): x = r.execute(*a, **k); print(a[0], "->", x["status"], x.get("why", "")); return x

assert res("rm_rf")["status"] == "rejected"
assert res("noop")["status"] == "noop"
(fake / "ltx_jobs" / "j.json").write_text(json.dumps({"id": "j", "status": "rendering", "updated": time.time()}))
assert res("reboot", dry_run=True)["status"] == "deferred"                 # in-flight blocks
assert res("reboot", dry_run=False)["status"] == "deferred"                # blocks even when live
(fake / "ltx_jobs" / "j.json").write_text(json.dumps({"id": "j", "status": "done"}))
inflight.detect = lambda: []                                                # ignore ssh session for the test
assert res("reboot", dry_run=True)["status"] == "dry_run"
r.PAUSED.write_text("x")
assert res("restart_hub", dry_run=False)["status"] == "paused"             # kill switch wins
print("audit lines:", len(r.AUDIT.read_text().splitlines())); print("ALL PASS")
