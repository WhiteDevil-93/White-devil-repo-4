#!/bin/bash
set +e
export PATH="$HOME/.local/bin:$PATH"
RENDERS=$HOME/wan/renders
SMOKE=$HOME/wan
LOG=$SMOKE/heartbeat.log
ALERT=$RENDERS/_ALERT.txt
STALL_MIN=${STALL_MIN:-45}
mkdir -p "$RENDERS" "$SMOKE"

refresh() {
"$HOME/.local/share/uv/tools/google-colab-cli/bin/python" - <<'PY' 2>/dev/null
import os, runpy
runpy.run_path(os.path.expanduser("~/wan/wan_ingest.py"), run_name="lib")["refresh_colab"]()
PY
}

alert() {
  echo "$(date -Is) ALERT $*" | tee -a "$ALERT" >> "$LOG"
  [ -s "$HOME/.wan_ntfy_topic" ] && curl -s -m 20 -H "Title: Wan render alert" -H "Priority: high" -H "Click: forgehub://open?screen=renders" \
    -d "$*" "https://ntfy.sh/$(cat "$HOME/.wan_ntfy_topic")" >/dev/null
}

page() {
  python3 "$SMOKE/push_laptop.py" >> "$LOG" 2>&1
  python3 "$SMOKE/status_page.py" 2>> "$LOG"
}

beat() {
  if [ -f "$SMOKE/paused" ]; then
    echo "$(date -Is) paused since $(cat "$SMOKE/paused") (runtime stopped from the app)" >> "$LOG"
    return
  fi
  refresh
  rm -f /tmp/hb_status.txt /tmp/dl_list.txt
  printf '%s\n' \
    'python3 -c "import glob; open(\"/tmp/dl_list.txt\",\"w\").write(\"\\n\".join(sorted(glob.glob(\"/content/outputs/colab_g4_ti2v5b_smoke/smoke_*_alt_*.mp4\"))+sorted(glob.glob(\"/content/outputs/colab_g4_ti2v5b_smoke/gooning/smoke_*.mp4\")))+\"\\n\")"' \
    '{ echo "PROCS $(pgrep -f "run_gooning_chain|smoke_i2v_mix|restore_resume|goon_queue_ingest|wanbot.server|boot.sh" | wc -l)"; echo "GPU $(nvidia-smi --query-gpu=utilization.gpu,memory.used --format=csv,noheader)"; echo "RUNNER $(ls /content/run_gooning_chain_colab.sh 2>/dev/null | wc -l)"; echo "DRIVE $(pgrep -f drive_sync.py | wc -l) $(grep -c uploaded /content/outputs/colab_g4_ti2v5b_smoke/drive_sync.log 2>/dev/null) $(grep -c FAIL /content/outputs/colab_g4_ti2v5b_smoke/drive_sync.log 2>/dev/null)"; pgrep -f drive_sync.py >/dev/null || [ ! -f /root/.wan_drive_token.json ] || nohup python3 /content/drive_sync.py /root/.wan_drive_token.json --loop 60 >> /content/outputs/colab_g4_ti2v5b_smoke/drive_sync.log 2>&1 < /dev/null & echo "LAST $(grep -E "=== RUN|PACK_|GOON_CHAIN_DONE|FATAL|BOOT_" /content/outputs/colab_g4_ti2v5b_smoke/nohup_gooning_chain.out /content/outputs/colab_g4_ti2v5b_smoke/boot.log 2>/dev/null | tail -1 | cut -c1-160)"; } > /tmp/hb_status.txt 2>&1' \
    'exit' | timeout 40 colab console -s colab > /tmp/hb_console.txt 2>&1
  if ! colab download -s colab /tmp/hb_status.txt /tmp/hb_status.txt >/dev/null 2>&1; then
    if timeout 60 colab usage 2>/dev/null | grep -q "Active assignments: 0"; then
      alert "Colab runtime is gone (no active assignment)"
      touch /tmp/need_recover
    else
      alert "instance unreachable or console not executing (no status file)"
    fi
    return
  fi
  colab download -s colab /tmp/dl_list.txt /tmp/dl_list.txt >/dev/null 2>&1
  n=0
  while IFS= read -r remote; do
    [ -z "$remote" ] && continue
    base=$(basename "$remote")
    [ -f "$RENDERS/$base" ] && continue
    rm -f "$RENDERS/.$base.part"
    colab download -s colab "$remote" "$RENDERS/.$base.part" >> "$LOG" 2>&1 \
      && [ -s "$RENDERS/.$base.part" ] && mv "$RENDERS/.$base.part" "$RENDERS/$base" && n=$((n+1))
  done < /tmp/dl_list.txt
  procs=$(awk '/^PROCS/{print $2}' /tmp/hb_status.txt)
  gpu=$(sed -n 's/^GPU //p' /tmp/hb_status.txt)
  runner=$(awk '/^RUNNER/{print $2}' /tmp/hb_status.txt)
  last=$(sed -n 's/^LAST //p' /tmp/hb_status.txt)
  read -r drive_up drive_n drive_fail < <(sed -n 's/^DRIVE //p' /tmp/hb_status.txt)
  newest=$(ls -1t "$RENDERS"/smoke_*.mp4 2>/dev/null | head -1)
  age_min=$(( ( $(date +%s) - $(stat -c %Y "$newest" 2>/dev/null || echo 0) ) / 60 ))
  echo "$(date -Is) pulled=$n goon_local=$(ls "$RENDERS"/smoke_goon_*.mp4 2>/dev/null | wc -l) newest_age=${age_min}m procs=${procs:-?} drive=[up=${drive_up:-?} uploaded=${drive_n:-?} fail=${drive_fail:-?}] gpu=[${gpu}] last=[${last}]" >> "$LOG"
  [[ "${drive_up:-0}" == "0" ]] && alert "drive sync was not running (restart attempted)"
  [[ "$runner" == "0" ]] && alert "runtime wiped: /content/run_gooning_chain_colab.sh missing"
  [[ "$runner" == "0" && "${procs:-0}" == "0" ]] && touch /tmp/need_recover
  [[ "${procs:-0}" == "0" && "$last" != *GOON_CHAIN_DONE* ]] && alert "no render process running (last: $last)"
  (( n == 0 && age_min > STALL_MIN )) && [[ "$last" != *GOON_CHAIN_DONE* ]] && alert "no new clip for ${age_min} min"
}

auto_recover() {
  [ -f /tmp/need_recover ] || return
  rm -f /tmp/need_recover
  local last=$(cat "$SMOKE/last_recover_try" 2>/dev/null || echo 0)
  if (( $(date +%s) - last < 1800 )); then
    alert "runtime gone but last auto-restart was under 30 min ago; not retrying yet"
    return
  fi
  date +%s > "$SMOKE/last_recover_try"
  alert "auto-restarting a new G4 runtime (packs: $(cat "$SMOKE/resume_packs" 2>/dev/null || echo '2 3 4 5'))"
  bash "$SMOKE/colab_recover.sh"
}

locked_beat() { ( flock -w 600 9 || { alert "colab lock busy for 10 min"; exit; }; beat ) 9>/tmp/colab.lock; }
[[ "$1" == once ]] && { locked_beat; page; exit; }
echo "HEARTBEAT_20M_START $(date -Is)" >> "$LOG"
while true; do
  locked_beat
  auto_recover
  page
  sleep 1200
done
