#!/bin/bash
# Build the Forge Hub APK on Windows (Android SDK lives there), publish it on the relay and
# bump apk_version so installed apps offer the update. Only needed when the native shell changes;
# screens themselves update from the relay without a new APK.
set -euo pipefail
A=$(cd "$(dirname "$0")" && pwd)
REPO=$(dirname "$A")
B=/mnt/c/Users/anon3/forgehub-build
VC=$(grep -oP 'versionCode = \K\d+' "$A/app/build.gradle.kts")

mkdir -p "$B"
rsync -a --delete --exclude build --exclude .gradle --exclude app/build --exclude local.properties --exclude keystore.properties "$A/" "$B/"
cp ~/.config/forgehub-keystore.properties "$B/keystore.properties"
echo 'sdk.dir=C:/Users/anon3/AppData/Local/Android/Sdk' > "$B/local.properties"
powershell.exe -NoProfile -Command "\$env:JAVA_HOME='C:\Program Files\Microsoft\jdk-17.0.19.10-hotspot'; cd C:\Users\anon3\forgehub-build; .\gradlew.bat --no-daemon -q assembleRelease; exit \$LASTEXITCODE"

scp -q "$B/app/build/outputs/apk/release/app-release.apk" wan-relay:wan/www/app/forgehub.apk
ssh wan-relay 'chmod 644 ~/wan/www/app/forgehub.apk'
python3 - "$REPO/hub/screens.json" "$VC" <<'PY'
import json, sys
p, vc = sys.argv[1], int(sys.argv[2])
m = json.load(open(p)); m["apk_version"] = vc; m["force_update"] = True; m["web_rev"] = vc
json.dump(m, open(p, "w"), indent=2); open(p, "a").write("\n")
PY
scp -q "$REPO/hub/screens.json" wan-relay:hub/screens.json
echo "published versionCode $VC"
