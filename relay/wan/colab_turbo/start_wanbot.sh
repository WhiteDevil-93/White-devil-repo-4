#!/bin/bash
# Start wanbot (Turbo+LoRA backend) on the Colab runtime; Cloudflare tunnel unless WANBOT_TUNNEL=0 (relay SSH tunnel).
# Expects /content/wanbot (repo's wanbot/ folder), /content/wanbot_turbo.yaml, models from rehydrate_g4.sh.
# Existing smoke_goon_pXX_cYY clips are linked into wanbot chain dirs so goon-pXX chains resume.
set -u
LOG=/content/wanbot.log
W=/content/wanbot_data/work
mkdir -p "$W/chains" /content/wanbot_data/outputs /content/wanbot_data/inbox
pip -q install -r /content/wanbot/requirements.txt "huggingface_hub>=1.5,<2" >> "$LOG" 2>&1

for f in /content/outputs/colab_g4_ti2v5b_smoke/gooning/smoke_goon_p*_c*.mp4; do
  [[ -e "$f" ]] || continue
  b=$(basename "$f"); p=${b:12:2}; c=${b:16:2}
  d="$W/chains/goon-p${p}__wan22-5b-turbo-lora"
  mkdir -p "$d"
  [[ -e "$d/clip_${c}.mp4" ]] || ln -s "$f" "$d/clip_${c}.mp4"
done

pkill -f "wanbot.server" 2>/dev/null; pkill -f "cloudflared tunnel" 2>/dev/null; sleep 2
rm -f "$W/connection.json"
cd /content/wanbot
[[ -n "${WANBOT_TOKEN:-}" ]] || export WANBOT_TOKEN=$(cat /root/.wanbot_token 2>/dev/null || python3 -c "import secrets;print(secrets.token_urlsafe(24))")
echo "$WANBOT_TOKEN" > /root/.wanbot_token; chmod 600 /root/.wanbot_token
TUNNEL=--tunnel; [[ "${WANBOT_TUNNEL:-1}" == 0 ]] && TUNNEL=
export PYTORCH_CUDA_ALLOC_CONF=expandable_segments:True
# Supervisor loop: its command line contains "wanbot.server", so the pkill above stops it too.
nohup bash -c "while true; do python3 -m wanbot.server --config /content/wanbot_turbo.yaml $TUNNEL; \
  echo \"\$(date -u +%FT%TZ) wanbot.server exited (\$?), restarting in 10 s\"; sleep 10; done" >> "$LOG" 2>&1 < /dev/null &
for _ in $(seq 60); do [[ -f "$W/connection.json" ]] && break; sleep 2; done
cat "$W/connection.json" 2>/dev/null || { echo "wanbot failed to start"; tail -30 "$LOG"; }
