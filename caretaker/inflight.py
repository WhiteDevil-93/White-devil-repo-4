"""In-flight work detector. A disruptive action must not run while this returns reasons.

Covered: LTX jobs, gen jobs, agentic jobs, Colab/laptop runs, interactive SSH sessions.
NOT covered (add before trusting reboots): anything started outside the hub.
"""
import glob, json, subprocess, time
from pathlib import Path

HUB = Path.home() / "hub"
ACTIVE = {"rendering", "queued", "running", "starting", "processing", "pending"}


def _json_jobs(pattern):
    for f in glob.glob(str(pattern)):
        try:
            yield json.loads(Path(f).read_text())
        except Exception:
            continue


def detect():
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
    return why


if __name__ == "__main__":
    r = detect()
    print(json.dumps({"clear": not r, "reasons": r}, indent=1))
