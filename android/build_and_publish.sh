#!/bin/bash
# Build the WhiteDevil APK on Windows (Android SDK lives there), publish it on the relay and
# bump apk_version so installed apps offer the update. Only needed when the native shell changes;
# screens themselves update from the relay without a new APK (use tools/deploy_hub.sh).
set -euo pipefail
A=$(cd "$(dirname "$0")" && pwd)
REPO=$(dirname "$A")
B=/mnt/c/Users/anon3/whitedevil-build
VC=$(grep -oP 'versionCode = \K\d+' "$A/app/build.gradle.kts")
if [[ -z "$VC" ]]; then
  echo "could not read versionCode from $A/app/build.gradle.kts" >&2
  exit 1
fi
HOST="${HUB_HOST:-wan-relay}"

mkdir -p "$B"
rsync -a --delete --exclude build --exclude .gradle --exclude app/build --exclude local.properties --exclude keystore.properties "$A/" "$B/"
if ! cp ~/.config/forgehub-keystore.properties "$B/keystore.properties" 2>/dev/null; then
  # Without it the release APK is not signed with the release key and will not
  # install over the existing app. Say so instead of swallowing the failure.
  echo "WARNING: ~/.config/forgehub-keystore.properties missing - APK will not carry the release signature" >&2
fi
echo 'sdk.dir=C:/Users/anon3/AppData/Local/Android/Sdk' > "$B/local.properties"
# PowerShell must cd to the SAME directory as $B; a second hardcoded literal drifts
# the moment $B changes, and an unquoted cd breaks on a path containing spaces.
WIN_B=$(wslpath -w "$B")
powershell.exe -NoProfile -Command "\$env:JAVA_HOME='C:\Program Files\Microsoft\jdk-17.0.19.10-hotspot'; cd '$WIN_B'; .\gradlew.bat --no-daemon -q assembleRelease; exit \$LASTEXITCODE"

APK="$B/app/build/outputs/apk/release/app-release.apk"
if [[ ! -f "$APK" ]]; then
  echo "APK missing after build" >&2
  exit 1
fi

# Live download path is /app/forgehub.apk via forge-hub StaticFiles (hub/static).
# Also mirror to wan/www for backups / old bookmarks.
scp -q "$APK" "$HOST:hub/static/forgehub.apk"
scp -q "$APK" "$HOST:wan/www/app/forgehub.apk"
ssh -q "$HOST" 'chmod 644 ~/hub/static/forgehub.apk ~/wan/www/app/forgehub.apk'

python3 - "$REPO/hub/screens.json" "$VC" <<'PY'
import json, sys
p, vc = sys.argv[1], int(sys.argv[2])
m = json.load(open(p))
m["apk_version"] = vc
m["force_update"] = True
prev = int(m.get("web_rev") or 0)
m["web_rev"] = max(prev, vc)
json.dump(m, open(p, "w"), indent=2)
open(p, "a").write("\n")
print(f"screens.json apk={vc} web_rev={m['web_rev']}")
PY

scp -q "$REPO/hub/screens.json" "$HOST:hub/screens.json"
scp -q "$REPO/hub/screens.json" "$HOST:wan/www/app/screens.json"
ssh -q "$HOST" 'sudo systemctl restart forge-hub; sleep 1; curl -sS http://127.0.0.1:9000/api/manifest | python3 -c "import sys,json; m=json.load(sys.stdin); print(\"live apk\", m.get(\"apk_version\"), \"web_rev\", m.get(\"web_rev\"))"'

echo "published versionCode $VC"
