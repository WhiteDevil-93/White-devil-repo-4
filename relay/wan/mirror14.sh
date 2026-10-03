#!/bin/bash
# Hard-link finished 14B chain clips into ~/wan/renders as smoke_<chain>_cNN_14b.mp4 so the app gallery,
# laptop sync and the heartbeat see them like the Colab clips.
# Also pulls chains rendered directly on the Thunder instance (/workspace/chains/<chain>/out/clip_NN.mp4).
R=/home/ubuntu/wan/renders
REMOTE=/home/ubuntu/wan14/remote
KEY=/home/ubuntu/.ssh/thunder_instance
mkdir -p "$REMOTE"

link() {  # link <clip file> <chain>
  local f=$1 chain=${2//_/-} n dst
  n=$(basename "$f" .mp4); n=${n#clip_}
  dst="$R/smoke_${chain}_c${n}_14b.mp4"
  [ -e "$dst" ] && return
  # the clip may still be written on the instance; wait until it has settled
  [ $(( $(date +%s) - $(stat -c %Y "$f") )) -ge 90 ] || return
  ln "$f" "$dst" 2>/dev/null || cp "$f" "$dst"
}

i=0
while true; do
  for d in /home/ubuntu/wan14/work/chains/*/; do
    [ -d "$d" ] || continue
    chain=$(basename "$d"); chain=${chain%%__*}
    for f in "$d"clip_[0-9][0-9].mp4; do [ -f "$f" ] && link "$f" "$chain"; done
  done

  if (( i % 4 == 0 )) && read -r H P < /home/ubuntu/.thunder_target 2>/dev/null; then
    rsync -rt --timeout=60 -e "ssh -i $KEY -p $P -o StrictHostKeyChecking=no -o ConnectTimeout=10 -o BatchMode=yes" \
      --include='*/' --include='/*/out/clip_[0-9][0-9].mp4' --exclude='*' --prune-empty-dirs \
      "ubuntu@$H:/workspace/chains/" "$REMOTE/" 2>/dev/null
  fi
  for d in "$REMOTE"/*/out/; do
    [ -d "$d" ] || continue
    chain=$(basename "$(dirname "$d")")
    for f in "$d"clip_[0-9][0-9].mp4; do [ -f "$f" ] && link "$f" "$chain"; done
  done

  i=$((i + 1))
  sleep 30
done
