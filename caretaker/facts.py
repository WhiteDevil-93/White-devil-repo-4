"""Read-only fact gathering. No side effects. The model only ever sees this dict."""
import json, os, shutil, subprocess, time, urllib.request
from pathlib import Path


def _run(cmd, t=20):
    try:
        return subprocess.run(cmd, capture_output=True, text=True, timeout=t).stdout.strip()
    except Exception as e:
        return f"ERR {e}"


def collect():
    du = shutil.disk_usage("/")
    mem = {l.split(":")[0]: int(l.split()[1]) for l in open("/proc/meminfo") if ":" in l}
    try:
        with urllib.request.urlopen("http://127.0.0.1:9000/api/status", timeout=8) as r:
            hub_http = r.status
    except Exception as e:
        hub_http = f"ERR {e}"
    backups = sorted(Path.home().glob("backups/forge-*.tar.gz"))
    inst = _run(["bash", "-c", "apt-get -s upgrade 2>/dev/null | grep -c ^Inst"])
    return {
        "time": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
        "disk_used_pct": round(100 * du.used / du.total, 1),
        "mem_available_mb": mem["MemAvailable"] // 1024,
        "load1": os.getloadavg()[0],
        "forge_hub_service": _run(["systemctl", "is-active", "forge-hub"]),
        "forge_hub_http": hub_http,
        "apt_upgradable": inst,
        "reboot_required": Path("/var/run/reboot-required").exists(),
        "running_kernel": os.uname().release,
        "last_backup_age_h": round((time.time() - backups[-1].stat().st_mtime) / 3600, 1) if backups else None,
        "failed_units": _run(["systemctl", "--failed", "--no-legend", "--plain"]) or "none",
    }


if __name__ == "__main__":
    print(json.dumps(collect(), indent=1))
