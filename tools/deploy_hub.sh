#!/usr/bin/env bash
# Deploy Forge Hub from this laptop to wan-relay so updates actually go live.
# Caddy serves /app from forge-hub (hub/static). This script syncs code + static,
# bumps web_rev, mirrors APK into hub/static, restarts the service.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
HUB="$ROOT/hub"
HOST="${HUB_HOST:-wan-relay}"
REMOTE_HUB='~/hub'

if [[ ! -d "$HUB" ]]; then
  echo "missing $HUB" >&2
  exit 1
fi

# Bump web_rev so phone/desktop reload the UI. Keep apk_version from screens.json
# unless --apk N is passed (build_and_publish sets that).
APK_OVERRIDE=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    # set -u turns a bare `--apk` into "$2: unbound variable"; say what is wrong.
    --apk)
      if [[ $# -lt 2 || -z "${2:-}" ]]; then echo "--apk needs a versionCode" >&2; exit 2; fi
      if [[ ! "$2" =~ ^[0-9]+$ ]]; then echo "--apk must be an integer, got: $2" >&2; exit 2; fi
      APK_OVERRIDE="$2"; shift 2 ;;
    *) echo "unknown arg: $1" >&2; exit 2 ;;
  esac
done

python3 - "$HUB/screens.json" "$APK_OVERRIDE" <<'PY'
import json, sys
from pathlib import Path
p = Path(sys.argv[1])
apk = sys.argv[2]
m = json.loads(p.read_text())
prev = int(m.get("web_rev") or 0)
m["web_rev"] = prev + 1
if apk.strip():
    m["apk_version"] = int(apk)
    m["force_update"] = True
p.write_text(json.dumps(m, indent=2) + "\n")
print(f"screens.json apk={m.get('apk_version')} web_rev={m['web_rev']}")
PY

echo "→ syncing hub → $HOST:$REMOTE_HUB"
# Never --delete the remote hub tree: optional modules (ltx/vast/setupbot) and
# runtime state must survive a partial local checkout.
rsync -az \
  --exclude '__pycache__/' --exclude '.venv/' --exclude '*.pyc' \
  --exclude 'static/term/phone_publish.sh' \
  --exclude 'setup_runs/' --exclude 'bots/*/memory/' \
  --exclude '*.pid' --exclude '*.out' --exclude '*_state.json' --exclude '*.log' \
  "$HUB/" "$HOST:$REMOTE_HUB/"

# Static UI: mirror exactly so removed pages disappear, but keep the APK.
ssh -q "$HOST" "mkdir -p ~/hub/static ~/wan/www/app"

# APK lives under /app/forgehub.apk via StaticFiles. Prefer the just-built copy
# on wan/www if present; otherwise leave whatever is in hub/static.
ssh -q "$HOST" 'bash -s' <<'REMOTE'
set -euo pipefail
mkdir -p ~/hub/static
if [[ -f ~/wan/www/app/forgehub.apk ]]; then
  cp -f ~/wan/www/app/forgehub.apk ~/hub/static/forgehub.apk
  chmod 644 ~/hub/static/forgehub.apk
fi
# Mirror static into wan/www too (status page / old bookmarks / belt-and-suspenders).
mkdir -p ~/wan/www/app
rsync -a --delete \
  --exclude 'forgehub.apk' \
  ~/hub/static/ ~/wan/www/app/
# Keep APK on both trees
if [[ -f ~/hub/static/forgehub.apk ]]; then
  cp -f ~/hub/static/forgehub.apk ~/wan/www/app/forgehub.apk
fi
cp -f ~/hub/screens.json ~/wan/www/app/screens.json
sudo systemctl restart forge-hub
sleep 1
curl -sS http://127.0.0.1:9000/api/manifest | python3 -c "import sys,json; m=json.load(sys.stdin); print('live manifest apk=%s web_rev=%s screens=%d' % (m.get('apk_version'), m.get('web_rev'), len(m.get('screens') or [])))"
REMOTE

# Refresh the phone Terminal publish script so it includes this hub snapshot
if [[ -f "$HUB/rebuild_phone_publish.py" ]]; then
  python3 "$HUB/rebuild_phone_publish.py"
  scp -q "$HUB/static/term/phone_publish.sh" "$HOST:hub/static/term/phone_publish.sh" || true
fi

echo "Hub deployed. Pull-to-refresh on phone / reload laptop Hub."
