#!/bin/bash
# Fresh-VM boot for the wanbot runner, started by relay/colab_recover.sh after it unpacks the bundle.
# Order: relay tunnel + Drive sync first (so nothing is lost), then deps/models, clip mirror, wanbot.
set -u
S=/content/outputs/colab_g4_ti2v5b_smoke
B=/content/bundle
mkdir -p "$S/gooning"
exec >> "$S/boot.log" 2>&1
echo "BOOT_START $(date -u)"

mkdir -p /root/.ssh
cp "$B/relay_known_hosts" /root/.ssh/known_hosts
install -m600 "$B/relay_tunnel_key" /root/.ssh/relay_tunnel_key
nohup bash -c 'while true; do
  ssh -N -i /root/.ssh/relay_tunnel_key -o ExitOnForwardFailure=yes -o ServerAliveInterval=20 -o ServerAliveCountMax=3 \
      -R 127.0.0.1:18900:127.0.0.1:8000 ubuntu@84.12.112.249
  sleep 10
done' >> "$S/tunnel.log" 2>&1 < /dev/null &

install -m600 "$B/drive_token.json" /root/.wan_drive_token.json
[[ -f "$S/_drive_sync_state.json" ]] || python3 - "$B/clips" "$S/gooning" "$S/_drive_sync_state.json" <<'PY'
import json, os, sys
src, dst, state = sys.argv[1:]
json.dump({os.path.join(dst, f): "on-drive-before-boot" for f in os.listdir(src) if f.endswith(".mp4")}, open(state, "w"))
PY
pgrep -f drive_sync.py >/dev/null || nohup python3 /content/drive_sync.py /root/.wan_drive_token.json --loop 60 >> "$S/drive_sync.log" 2>&1 < /dev/null &

pip -q install -U diffusers ftfy imageio imageio-ffmpeg accelerate
bash /content/rehydrate_g4.sh
for f in /content/loras_5b/DR34ML4Y_TI2V_5B_V1.safetensors /content/loras_5b/PENISLORA_WAN22_5B_e349.safetensors /content/Wan2.2-TI2V-5B-Turbo/model_index.json; do
  [[ -s "$f" ]] || { echo "BOOT_FATAL missing $f"; exit 2; }
done

pgrep -f mirror_chains.sh >/dev/null || nohup bash /content/mirror_chains.sh >> "$S/mirror.log" 2>&1 < /dev/null &
WANBOT_TUNNEL=0 WANBOT_TOKEN=$(cat "$B/wanbot_token") bash /content/start_wanbot.sh
echo "BOOT_OK $(date -u)"
