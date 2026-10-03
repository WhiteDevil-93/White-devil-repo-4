#!/bin/bash
# Anti-grain I2V chain: STEPS=28, denoise handoff, periodic soft-start blend.
set -euo pipefail
export PYTHONUNBUFFERED=1
export LD_LIBRARY_PATH=/usr/lib64-nvidia:/usr/local/cuda/lib64
export TORCH_CUDNN_V8_API_DISABLED=1

OUT=/content/outputs/colab_g4_ti2v5b_smoke
GOUT="$OUT/gooning"
LOG=$OUT/nohup_gooning_chain.out
JSONL=/content/gooning_chains.jsonl
START0=${GOON_START:-/content/gooning_start_soft.png}
PACKS=${PACKS:-"1 2 3 4 5"}
MAX_CLIPS=${MAX_CLIPS:-64}
SEED_BASE=${SEED_BASE:-20260924}
REFRESH_EVERY=${REFRESH_EVERY:-8}

mkdir -p "$GOUT" "$OUT"
echo "GOON_CHAIN_START $(date -u) packs=$PACKS anti_grain steps=28 refresh_every=$REFRESH_EVERY" | tee -a "$LOG"

[[ -f "$JSONL" ]] || { echo "FATAL missing $JSONL" | tee -a "$LOG"; exit 2; }
[[ -f "$START0" ]] || { echo "FATAL missing start $START0" | tee -a "$LOG"; exit 2; }

export LORA1="${LORA1:-Wan2.2-TI2V-5B-mast-768px-73f-r32.safetensors}"
export LORA1_SCALE="${LORA1_SCALE:-0.60}"
export LORA2="${LORA2:-DR34ML4Y_TI2V_5B_V1.safetensors}"
export LORA2_SCALE="${LORA2_SCALE:-0.55}"
export LORA3="${LORA3:-PENISLORA_WAN22_5B_e349.safetensors}"
export LORA3_SCALE="${LORA3_SCALE:-0.25}"

export CFG="${CFG:-4.0}" STEPS="${STEPS:-28}" FRAMES="${FRAMES:-81}" WIDTH=1280 HEIGHT=704
export NEG_MODE=stroke
NEG_BASE="grainy, film grain, heavy grain, noisy, speckles, compression artifacts, mosquito noise, blocky, muddy, overexposed, blown highlights, harsh contrast, crushed blacks, hard spotlight, shiny oily overlit skin"

# Extract last frame, denoise, optional blend toward clean soft start (kills chain grain accumulation)
extract_last() {
  local mp4="$1" png="$2" blend="${3:-0.35}"
  python3 - "$mp4" "$png" "$START0" "$blend" <<'ENDPY'
import sys, cv2
import numpy as np
from PIL import Image, ImageFilter, ImageEnhance
mp4, png, soft, blend = sys.argv[1], sys.argv[2], sys.argv[3], float(sys.argv[4])
cap = cv2.VideoCapture(mp4)
if not cap.isOpened():
    raise SystemExit("cannot open " + mp4)
n = int(cap.get(cv2.CAP_PROP_FRAME_COUNT) or 0)
if n > 0:
    cap.set(cv2.CAP_PROP_POS_FRAMES, max(0, n - 2))
ok, frame = cap.read()
if not ok:
    last = None
    cap.set(cv2.CAP_PROP_POS_FRAMES, 0)
    while True:
        ok, fr = cap.read()
        if not ok: break
        last = fr
    frame = last
cap.release()
if frame is None:
    raise SystemExit("no frames")
# bilateral denoise (edge-preserving) then light median
frame = cv2.bilateralFilter(frame, d=5, sigmaColor=40, sigmaSpace=40)
frame = cv2.medianBlur(frame, 3)
rgb = cv2.cvtColor(frame, cv2.COLOR_BGR2RGB)
im = Image.fromarray(rgb)
# mild extra denoise + suppress sparkle
im = im.filter(ImageFilter.MedianFilter(size=3))
im = ImageEnhance.Sharpness(im).enhance(0.92)
# blend toward soft start to fight accumulated grain/drift
soft_im = Image.open(soft).convert("RGB").resize(im.size, Image.Resampling.LANCZOS)
arr = np.asarray(im).astype(np.float32)
sarr = np.asarray(soft_im).astype(np.float32)
# keep identity mostly; pull only high-frequency noise toward soft (approx: blend then re-add lowpass of arr)
out = arr * (1.0 - blend) + sarr * blend
# restore structure from denoised frame a bit more
out = out * 0.55 + arr * 0.45
out = np.clip(out, 0, 255).astype(np.uint8)
Image.fromarray(out).save(png)
print("ok", png, "blend", blend)
ENDPY
}

for PACK in $PACKS; do
  echo "=== PACK $PACK $(date -u) ===" | tee -a "$LOG"
  mapfile -t CLIPS < <(python3 - <<PY
import json
from pathlib import Path
pack=int("$PACK")
maxc=int("$MAX_CLIPS")
for line in Path("$JSONL").read_text().splitlines():
    if not line.strip(): continue
    d=json.loads(line)
    if int(d["index"])!=pack: continue
    clips=d.get("clips") or d.get("clips_parsed") or []
    for c in clips[:maxc]:
        print(c["clip"])
        Path(f"/tmp/goon_p{pack:02d}_c{int(c['clip']):02d}.txt").write_text(c["prompt"].strip()+"\n")
        Path(f"/tmp/goon_p{pack:02d}_c{int(c['clip']):02d}.neg").write_text((c.get("negative") or "").strip().replace("\n", " "))
    break
else:
    raise SystemExit(f"pack {pack} not found")
PY
)
  start="$START0"
  PP=$(printf '%02d' "$PACK")
  idx=0
  for C in "${CLIPS[@]}"; do
    idx=$((idx+1))
    CC=$(printf '%02d' "$C")
    TAG=$(printf 'goon_p%02d_c%02d' "$PACK" "$C")
    PROMPT=/tmp/goon_p${PP}_c${CC}.txt
    # Force re-render if FORCE=1 or file missing
    if [[ "${FORCE:-0}" != "1" ]] && ls "$GOUT"/smoke_${TAG}_*.mp4 >/dev/null 2>&1; then
      echo "SKIP have $TAG" | tee -a "$LOG"
      lastmp4=$(ls -1 "$GOUT"/smoke_${TAG}_*.mp4 | tail -1)
      nextstart="$GOUT/start_after_${TAG}.png"
      extract_last "$lastmp4" "$nextstart" 0.30
      start="$nextstart"
      continue
    fi
    # periodic hard refresh to soft start (kills grain spiral)
    if (( idx > 1 && (idx - 1) % REFRESH_EVERY == 0 )); then
      echo "REFRESH start -> soft @ clip $C" | tee -a "$LOG"
      start="$START0"
    fi
    SEED=$((SEED_BASE + PACK * 100 + C))
    CLIPNEG=$(cat "/tmp/goon_p${PP}_c${CC}.neg" 2>/dev/null || true)
    export NEG_EXTRA="$NEG_BASE${CLIPNEG:+, $CLIPNEG}"
    export SMOKE_TAG="$TAG" SMOKE_SEED="$SEED" PROMPT_PATH="$PROMPT" START_IMG="$start"
    echo "=== RUN $TAG seed=$SEED cfg=$CFG s=$STEPS f=$FRAMES start=$(basename "$start") ===" | tee -a "$LOG"
    set +e
    python3 -u /content/smoke_i2v_mix_pack.py >> "$LOG" 2>&1
    rc=$?
    set -e
    echo "=== EXIT $TAG rc=$rc ===" | tee -a "$LOG"
    src=$(ls -1t "$OUT"/smoke_${TAG}_*.mp4 2>/dev/null | head -1 || true)
    if [[ -z "${src:-}" || "$rc" -ne 0 ]]; then
      echo "FATAL no mp4 for $TAG rc=$rc" | tee -a "$LOG"
      exit 3
    fi
    mv -f "$src" "$GOUT/"
    dest="$GOUT/$(basename "$src")"
    nextstart="$GOUT/start_after_${TAG}.png"
    # stronger blend early in chain recovery
    extract_last "$dest" "$nextstart" 0.32
    [[ -f "$nextstart" ]] || { echo "FATAL extract $TAG" | tee -a "$LOG"; exit 4; }
    set +e
    python3 /content/goon_grain_check.py "$dest" | tee -a "$LOG"
    grc=${PIPESTATUS[0]}
    set -e
    if [[ "$grc" -eq 2 ]]; then
      echo "GRAIN_ESCALATE -> soft start refresh for next clip" | tee -a "$LOG"
      start="$START0"
      # reset baseline after refresh so we can detect new rise
      rm -f /content/outputs/colab_g4_ti2v5b_smoke/gooning/_grain_state.json /content/outputs/colab_g4_ti2v5b_smoke/gooning/_GRAIN_WARN
    else
      start="$nextstart"
    fi
  done
  echo "PACK_${PP}_DONE $(date -u)" | tee -a "$LOG"
done
echo "GOON_CHAIN_DONE $(date -u)" | tee -a "$LOG"
