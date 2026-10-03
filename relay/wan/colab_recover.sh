#!/bin/bash
# Bring up a fresh Colab G4 runtime and resume the gooning chains on wanbot.
# Falls back to the old runner (run_gooning_chain_colab.sh) if wanbot fails its first clip.
#   colab_recover.sh [PACKS...]        default: packs from ~/wan/resume_packs (else "2 3 4 5")
set -uo pipefail
export PATH="$HOME/.local/bin:$PATH"
WAN=$HOME/wan
LOG=$WAN/recover.log
PACKS=${*:-$(cat "$WAN/resume_packs" 2>/dev/null || echo "2 3 4 5")}
TOKEN=$(cat "$HOME/.wanbot_token")
RUNNER_URL=http://127.0.0.1:18900
exec >> "$LOG" 2>&1
exec 8>/tmp/recover.lock
flock -n 8 || { echo "$(date -Is) recovery already running"; exit 0; }
say() { echo "$(date -Is) $*"; }
notify() {
  say "NOTIFY $*"
  [ -s "$HOME/.wan_ntfy_topic" ] && curl -s -m 20 -H "Title: Wan runtime" -H "Click: forgehub://open?screen=colab" \
    -d "$*" "https://ntfy.sh/$(cat "$HOME/.wan_ntfy_topic")" >/dev/null
}
health() { curl -s -m 10 -H "Authorization: Bearer $TOKEN" "$RUNNER_URL/health" >/dev/null; }

say "RECOVER_START packs=[$PACKS]"
echo "$PACKS" > "$WAN/resume_packs"
rm -f "$WAN/paused"

if ! timeout 60 colab usage 2>/dev/null | grep -q "Active assignments: [1-9]"; then
  say "creating G4 runtime"
  ( exec 9>/tmp/colab.lock; flock -w 600 9; timeout 900 colab new -s colab --gpu G4 ) || { notify "Could not create a G4 runtime (quota or availability). Will retry next heartbeat."; exit 1; }
fi

BUNDLE=$(mktemp -d)
mkdir -p "$BUNDLE/clips"
cp -r "$WAN/colab_turbo" "$BUNDLE/colab_turbo"
cp -r "$WAN/wanbot_src" "$BUNDLE/wanbot"
cp "$WAN/gooning_chains/data/gooning_chains.jsonl" "$BUNDLE/"
cp "$HOME/.config/colab-cli/token.json" "$BUNDLE/drive_token.json"
cp "$HOME/.ssh/colab_tunnel" "$BUNDLE/relay_tunnel_key"
echo "84.12.112.249 $(cut -d' ' -f1,2 /etc/ssh/ssh_host_ed25519_key.pub)" > "$BUNDLE/relay_known_hosts"
cp "$HOME/.wanbot_token" "$BUNDLE/wanbot_token"
[[ -f "$HOME/.civitai_token" ]] && cp "$HOME/.civitai_token" "$BUNDLE/civitai_token"
for p in $PACKS; do cp "$WAN/renders"/smoke_goon_p$(printf %02d "$p")_c*.mp4 "$BUNDLE/clips/" 2>/dev/null; done
python3 "$WAN/resume_chains.py" bundle "$BUNDLE/chains"
tar czf /tmp/wan_bundle.tgz -C "$BUNDLE" . && rm -rf "$BUNDLE"
say "bundle $(du -h /tmp/wan_bundle.tgz | cut -f1), $(tar tzf /tmp/wan_bundle.tgz | grep -c 'clips/.*mp4') resume clips"

# Colab's contents API drops single uploads somewhere above ~60 MB, so send 30 MB parts.
rm -f /tmp/wan_bundle.part_*
split -b 30M -d /tmp/wan_bundle.tgz /tmp/wan_bundle.part_
for part in /tmp/wan_bundle.part_*; do
  ok=0
  for _ in 1 2 3 4; do
    "$WAN/colab_ops.sh" "true" >/dev/null 2>&1   # refreshes the session token under the lock
    colab upload -s colab "$part" "/content/$(basename "$part")" && ok=1 && break
    sleep 20
  done
  [ $ok = 1 ] || { notify "Runtime created but bundle upload failed ($(basename "$part")). Will retry next heartbeat."; exit 1; }
done
say "uploaded $(ls /tmp/wan_bundle.part_* | wc -l) parts"

"$WAN/colab_ops.sh" 'set -e; cat /content/wan_bundle.part_* > /content/wan_bundle.tgz; rm -f /content/wan_bundle.part_*
rm -rf /content/bundle; mkdir -p /content/bundle && tar xzf /content/wan_bundle.tgz -C /content/bundle
cp /content/bundle/colab_turbo/* /content/; chmod +x /content/*.sh
rm -rf /content/wanbot; cp -r /content/bundle/wanbot /content/wanbot
cp /content/bundle/gooning_chains.jsonl /content/gooning_chains.jsonl
mkdir -p /content/outputs/colab_g4_ti2v5b_smoke/gooning; cp -n /content/bundle/clips/*.mp4 /content/outputs/colab_g4_ti2v5b_smoke/gooning/ 2>/dev/null || true
for d in /content/bundle/chains/*/; do [ -d "$d" ] || continue; w=/content/wanbot_data/work/chains/$(basename "$d")__wan22-5b-turbo-lora; mkdir -p "$w"; cp -an "$d". "$w"/; done
nohup bash /content/boot.sh >/dev/null 2>&1 < /dev/null &
echo BUNDLE_OK' | grep -q BUNDLE_OK || { notify "Bundle unpack failed on the new runtime."; exit 1; }
say "boot started; waiting for wanbot via relay tunnel"

for _ in $(seq 120); do health && break; sleep 20; done
if ! health; then
  notify "wanbot did not come up within 40 min; switching to the old runner."
  "$WAN/colab_ops.sh" 'tail -5 /content/outputs/colab_g4_ti2v5b_smoke/boot.log'
  FALLBACK=1
else
  say "wanbot healthy; queueing packs $PACKS"
  WANBOT_URL=$RUNNER_URL WANBOT_TOKEN=$TOKEN WAN_GC="$WAN/gooning_chains" python3 "$WAN/wan_ingest.py" send $PACKS
  python3 "$WAN/resume_chains.py" submit
  say "test: watching all wanbot jobs for the first newly rendered clip"
  FALLBACK=1
  for _ in $(seq 90); do
    st=$(python3 - "$RUNNER_URL" "$TOKEN" <<'EOF'
import json, sys, urllib.request
url, tok = sys.argv[1], sys.argv[2]
get = lambda p: json.load(urllib.request.urlopen(urllib.request.Request(url + p, headers={"Authorization": "Bearer " + tok}), timeout=20))
try:
    jobs = get("/jobs"); jobs = jobs.get("jobs", jobs) if isinstance(jobs, dict) else jobs
    for j in jobs:
        if j.get("status") not in ("rendering", "queued", "done", "completed"):
            if j.get("status") in ("error", "failed"): print("error job %s %s" % (j["id"], j.get("error", ""))[:200]); break
            continue
        cl = get("/jobs/" + j["id"]).get("clips") or []
        new = [c for c in cl if c.get("seconds")]
        err = [c for c in cl if c.get("status") == "error"]
        if err: print("error %s clip %s: %s" % (j.get("chain_id"), err[0].get("index"), str(err[0].get("error"))[:160])); break
        if new: print("ok %s clip %d in %ss" % (j.get("chain_id"), new[0]["index"], new[0]["seconds"])); break
    else:
        print("wait")
except Exception as e:
    print("wait " + str(e)[:80])
EOF
)
    case "$st" in
      ok*) say "test passed: $st"; FALLBACK=0; break ;;
      error*) say "test failed: $st"; break ;;
    esac
    sleep 20
  done
fi

if [ "$FALLBACK" = 1 ]; then
  "$WAN/colab_ops.sh" "pkill -f wanbot.server; cd /content; PACKS='$PACKS' REFRESH_EVERY=6 nohup bash /content/run_gooning_chain_colab.sh >> /content/outputs/colab_g4_ti2v5b_smoke/nohup_gooning_chain.out 2>&1 < /dev/null & echo FALLBACK_STARTED"
  notify "New runtime is rendering packs $PACKS on the OLD runner (wanbot test failed; see recover.log)."
  echo old > "$WAN/runner_mode"
else
  notify "New runtime is rendering packs $PACKS on wanbot. First clip passed."
  echo wanbot > "$WAN/runner_mode"
fi
date +%s > "$WAN/last_recover"
say "RECOVER_DONE fallback=$FALLBACK"
