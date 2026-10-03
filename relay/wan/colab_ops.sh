#!/bin/bash
# Run one shell command on the Colab instance and print its output.
# Shares /tmp/colab.lock with heartbeat.sh so only one thing ever drives `colab console`.
#   colab_ops.sh '<remote command>'
set -uo pipefail
export PATH="$HOME/.local/bin:$PATH"
PY="$HOME/.local/share/uv/tools/google-colab-cli/bin/python"
CMD="$1"
ID="ops_$$_$(date +%s)"

exec 9>/tmp/colab.lock
flock -w 600 9 || { echo "ERROR: colab busy (lock timeout)"; exit 3; }

"$PY" - <<'PY' 2>/dev/null
import os, runpy
runpy.run_path(os.path.expanduser("~/wan/wan_ingest.py"), run_name="lib")["refresh_colab"]()
PY

B64=$(printf '%s' "$CMD" | base64 -w0)
printf '%s\n' "{ echo $B64 | base64 -d | bash; } > /tmp/$ID.out 2>&1; echo \"__RC=\$?\" >> /tmp/$ID.out" 'exit' \
  | timeout 120 colab console -s colab >/dev/null 2>&1
for _ in $(seq 24); do
  colab download -s colab "/tmp/$ID.out" "/tmp/$ID.out" >/dev/null 2>&1 && grep -q "^__RC=" "/tmp/$ID.out" && break
  sleep 5
done
if [ ! -s "/tmp/$ID.out" ]; then echo "ERROR: no output from instance"; exit 2; fi
rc=$(sed -n 's/^__RC=//p' "/tmp/$ID.out" | tail -1)
grep -v '^__RC=' "/tmp/$ID.out"
rm -f "/tmp/$ID.out"
exit "${rc:-1}"
