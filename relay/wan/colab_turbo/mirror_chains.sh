#!/bin/bash
# Copy finished wanbot chain clips to the legacy gooning/ names so drive_sync, the relay heartbeat
# and the app see them exactly like the old runner's output. Symlinks are the legacy clips themselves.
G=/content/outputs/colab_g4_ti2v5b_smoke/gooning
C=/content/wanbot_data/work/chains
while true; do
  for f in "$C"/goon-p[0-9][0-9]__*/clip_[0-9][0-9].mp4; do
    [[ -f "$f" && ! -L "$f" && -s "$f" ]] || continue
    d=$(basename "$(dirname "$f")"); p=${d:6:2}; c=$(basename "$f" .mp4); c=${c#clip_}
    dst="$G/smoke_goon_p${p}_c${c}_1280x704_f81_s28.mp4"
    [[ -e "$dst" ]] && continue
    cp "$f" "$dst.tmp" && mv "$dst.tmp" "$dst" && echo "$(date -u +%FT%TZ) mirrored $dst"
  done
  for f in "$C"/*__*/clip_[0-9][0-9].mp4; do
    [[ -f "$f" && ! -L "$f" && -s "$f" ]] || continue
    d=$(basename "$(dirname "$f")"); chain=${d%%__*}
    [[ "$chain" == goon-p[0-9][0-9] ]] && continue
    c=$(basename "$f" .mp4); c=${c#clip_}
    dst="$G/smoke_${chain}_c${c}_wanbot.mp4"
    [[ -e "$dst" ]] && continue
    cp "$f" "$dst.tmp" && mv "$dst.tmp" "$dst" && echo "$(date -u +%FT%TZ) mirrored $dst"
  done
  sleep 20
done
