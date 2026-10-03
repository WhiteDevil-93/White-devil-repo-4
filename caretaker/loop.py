"""One caretaker pass. Run from a timer. Cheap by default: the model is loaded ONLY on an anomaly.

Dry-run unless CARETAKER_LIVE=1. Override facts for testing with CARETAKER_FAKE_FACTS='{"k":v}'.
"""
import json, os, re, sys, time
from pathlib import Path

import facts as F, inflight, runbook as R

MODEL = str(Path.home() / "models/gemma4_e4b_heretic_int4.litertlm")
IGNORE_UNITS = ("lvm-activate-ocivolume",)       # known failed unit, not investigated yet
STATE = R.DIR / "state.json"
LIVE = os.environ.get("CARETAKER_LIVE") == "1"


def anomalies(f):
    a = []
    if f["disk_used_pct"] > 80: a.append(f"disk {f['disk_used_pct']}% used")
    if f["mem_available_mb"] < 1500: a.append(f"only {f['mem_available_mb']} MB RAM available")
    if f["forge_hub_service"] != "active" or f["forge_hub_http"] != 200:
        a.append(f"forge-hub unhealthy (service={f['forge_hub_service']}, http={f['forge_hub_http']})")
    if str(f["apt_upgradable"]).isdigit() and int(f["apt_upgradable"]) > 0: a.append(f"{f['apt_upgradable']} apt upgrades pending")
    if f["reboot_required"]: a.append("reboot required")
    if f["last_backup_age_h"] is None or f["last_backup_age_h"] > 26: a.append(f"last backup {f['last_backup_age_h']} h ago")
    bad = [l for l in f["failed_units"].splitlines() if l and not any(i in l for i in IGNORE_UNITS) and l != "none"]
    if bad: a.append("failed units: " + "; ".join(bad)[:200])
    return a


CANDIDATES = [  # anomaly text prefix -> runbook action that fixes it
    ("disk", "rotate_logs"), ("only", None), ("forge-hub unhealthy", "restart_hub"),
    ("apt", "apt_upgrade"), ("reboot required", "reboot"), ("last backup", "run_backup"), ("failed units", None),
]


def candidates(an):
    out = []
    for a in an:
        for pre, act in CANDIDATES:
            if pre in a and act and act not in out: out.append(act)
    return out


ORDER = ["run_backup", "rotate_logs", "restart_hub", "apt_upgrade", "reboot"]   # one action per pass, safest first

SYSTEM = ("You review a proposed maintenance action on a small Linux relay server. The fix is already chosen by a "
          "fixed table; you only decide whether to hold it. Reply with 'APPROVE - <short reason>' or "
          "'HOLD - <short reason>'. HOLD only if the facts or work in flight show the action is unsafe or unneeded.")


def review(action, an, blockers):
    """Model veto. Only an explicit HOLD blocks; anything else (including garbage) approves, because the
    table already chose the right fix and the in-flight gate in runbook.execute still protects renders."""
    import litert_lm as l
    prompt = (f"Anomalies: {an}\nProposed action: {action}\nWork in flight: {blockers or 'none'}\n"
              "Answer: APPROVE - <reason> or HOLD - <reason>")
    e = l.Engine(MODEL, backend=l.Backend.CPU(), max_num_tokens=2048, cache_dir=str(Path.home() / "litert-cache"))
    c = e.create_conversation(max_output_tokens=50, system_message=SYSTEM)
    r = c.send_message({"role": "user", "content": prompt})
    txt = "".join(p.get("text", "") for p in r["content"] if p.get("type") == "text")
    txt = re.sub(r"<\|channel>thought.*?<channel\|>", "", txt, flags=re.S).strip()
    hold = bool(re.match(r"\W*HOLD\b", txt, re.I))
    return hold, txt.split("-", 1)[-1].strip()[:200], txt


def main():
    f = F.collect()
    f.update(json.loads(os.environ.get("CARETAKER_FAKE_FACTS", "{}")))
    an = anomalies(f)
    if not an:
        R.audit(event="ok"); print("ok, no anomalies, model not loaded"); return
    blockers = inflight.detect()
    cands = candidates(an)
    action = next((a for a in ORDER if a in cands), "noop")     # the TABLE decides
    print("anomalies:", an); print("table chose:", action)
    res = {"ok": True, "status": "noop"}
    if action != "noop":
        t = time.time()
        try:
            hold, reason, raw = review(action, an, blockers)     # the MODEL may only veto
        except Exception as ex:
            hold, reason, raw = False, f"model unavailable ({str(ex)[:80]})", ""
        R.audit(event="review", anomalies=an, action=action, hold=hold, raw=raw[:300], model_s=round(time.time() - t, 1))
        print("model:", "HOLD" if hold else "approve", "-", reason, f"({time.time()-t:.0f}s)")
        res = {"ok": False, "status": "held_by_model", "why": reason} if hold else \
              R.execute(action, dry_run=not LIVE, reason=reason)
        print("execute ->", res)
    if action == "noop" or res["status"] == "held_by_model" or not res["ok"] and res["status"] not in ("deferred", "paused", "dry_run"):
        key = "|".join(an)
        st = json.loads(STATE.read_text()) if STATE.exists() else {}
        if st.get("last_alert") != key:                 # tell the user once per distinct problem
            msg = "Relay caretaker needs you: " + "; ".join(an)[:300]
            if LIVE:
                R.notify(msg)
            else:
                R.audit(event="would_notify", msg=msg)       # dry-run never pushes to the user's phone
            st["last_alert"] = key
            STATE.write_text(json.dumps(st))


if __name__ == "__main__":
    main()
