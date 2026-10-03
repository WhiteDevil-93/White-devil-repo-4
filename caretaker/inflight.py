"""In-flight work detector. A disruptive action must not run while this returns reasons.

detect()           fast, local only: LTX/gen/agentic job files and interactive SSH sessions.
detect(deep=True)  also asks the hub about Colab, Thunder, Vast and laptop (Hypno) work. Those calls hit
                   vendor APIs and take 2-5 s, so only runbook.execute uses it, before a disruptive action.

If the hub cannot be read, a deep check reports "cannot confirm idle" and blocks, except for
restart_hub (allow_unknown=True), where the hub being down is the reason for acting.

NOT covered: work started outside the hub (a manual ssh job, a render run by hand).
"""
import glob, json, subprocess, time, urllib.request
from pathlib import Path

HUB = Path.home() / "hub"
HUB_API = "http://127.0.0.1:9000"
ACTIVE = {"rendering", "queued", "running", "starting", "processing", "pending"}
FINISHED = {"done", "failed", "error", "cancelled", "canceled", "complete", "completed", "finished", "stopped"}
_cache = {"t": 0.0, "v": None}
CACHE_S = 30


def _json_jobs(pattern):
    for f in glob.glob(str(pattern)):
        try:
            yield json.loads(Path(f).read_text())
        except Exception:
            continue


def _get(path, timeout=20):
    """JSON from the local hub, or None if it cannot be read."""
    try:
        with urllib.request.urlopen(HUB_API + path, timeout=timeout) as r:
            return json.loads(r.read().decode())
    except Exception:
        return None


def _unfinished(jobs):
    return [j for j in (jobs or []) if isinstance(j, dict) and str(j.get("status", "")).lower() not in FINISHED]


def hub_reasons():
    """Reasons from the hub's own view of Colab, Thunder, Vast and laptop work. None = hub unreadable."""
    colab = _get("/api/colab/state")
    thunder = _get("/api/thunder/state")
    thunder_q = _get("/api/thunder/queue")
    vast = _get("/api/vast/state")
    hypno = _get("/api/laptop/hypno/jobs")
    if any(x is None for x in (colab, thunder, thunder_q, vast, hypno)):
        return None
    why = []
    if (colab.get("instance") or {}).get("running"):
        why.append("Colab runtime is running")
    if colab.get("recover_running"):
        why.append("Colab recovery is running")
    cj = colab.get("jobs")
    if isinstance(cj, dict):
        cj = list(cj.values())
    for j in _unfinished(cj):
        why.append(f"colab job {j.get('id', '?')} {j.get('status', '?')}")
    for inst in thunder.get("instances") or []:
        why.append(f"Thunder instance up ({inst.get('id') or inst.get('name') or '?'})")
    for j in _unfinished(thunder_q.get("jobs")):
        why.append(f"thunder job {j.get('id', '?')} {j.get('status', '?')}")
    for inst in vast.get("instances") or []:
        if str(inst.get("status", "")).lower() == "running":
            why.append(f"Vast instance running ({inst.get('label') or inst.get('id')})")
    for j in _unfinished(hypno if isinstance(hypno, list) else hypno.get("jobs")):
        why.append(f"laptop hypno job {j.get('id', '?')} {j.get('status', '?')}")
    return why


def detect(deep=False, allow_unknown=False):
    """Return a list of human-readable reasons not to disrupt the box. Empty list == clear."""
    why = []
    for kind, pat in (("ltx", HUB / "ltx_jobs" / "*.json"), ("gen", HUB / "gen_jobs" / "*.json")):
        for j in _json_jobs(pat):
            if isinstance(j, dict) and str(j.get("status")).lower() in ACTIVE:
                age = int(time.time() - j.get("updated", j.get("created", time.time())))
                why.append(f"{kind} job {j.get('id')} {j.get('status')} (updated {age}s ago)")
    try:
        aj = json.loads((HUB / "agentic_data" / "jobs.json").read_text())
        rows = aj if isinstance(aj, list) else list(aj.values())
        for j in rows:
            if isinstance(j, dict) and str(j.get("status")).lower() in ACTIVE:
                why.append(f"agentic job {j.get('id')} {j.get('status')}")
    except Exception:
        pass
    try:
        who = subprocess.run(["who"], capture_output=True, text=True, timeout=5).stdout.strip().splitlines()
        if who:
            why.append(f"{len(who)} interactive session(s) logged in")
    except Exception:
        pass
    if deep:
        now = time.time()
        if _cache["v"] is None or now - _cache["t"] > CACHE_S:
            _cache["v"] = hub_reasons()
            _cache["t"] = now
        v = _cache["v"]
        if v is None:
            if not allow_unknown:
                why.append("hub API unreadable: cannot confirm Colab/Thunder/Vast/laptop are idle")
        else:
            why.extend(v)
    return why


if __name__ == "__main__":
    import sys
    r = detect(deep="--deep" in sys.argv)
    print(json.dumps({"clear": not r, "reasons": r}, indent=1))
