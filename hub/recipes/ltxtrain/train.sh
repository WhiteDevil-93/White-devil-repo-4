#!/bin/bash
# LTX-2.5 LoRA training on the Colab G4 (RTX PRO 6000 96 GB), next to the LTX kit's ComfyUI.
# Started by hub/lora_train.py with an env file; prints TRAIN_STAGE lines for the hub, CKPT/FINAL lines for the files
# it must pull back, TRAIN_COMPLETE at the end and FATAL: on any stop.
#
# Uses Lightricks' ltx-trainer (github.com/Lightricks/LTX-2 packages/ltx-trainer) exactly as its quick start says:
#   uv sync; scripts/process_dataset.py (split pack: --model-path/--text-encoder-path/--video-vae-path/--audio-vae-path)
#   then scripts/train.py <config>. Video only (--skip-audio): our clips are silent.
# Reuses the kit's Gemma 4 bf16 encoder and video VAE from ComfyUI/models; downloads the official 2.5 Dev transformer
# (42 GB, gated) to $BASE/train/models once. LoRAs train on Dev and load on Dev-derived models (Stubelius) in ComfyUI.
#
# env: BASE_DIR RUN_ID NAME TRIGGER KIND(character|motion) STEPS RANK LR BUCKETS I2V_P FF HF_TOKEN [HF_REPO]
set -uo pipefail

BASE="${BASE_DIR:-/content/workspace}"
RUN="$BASE/train/runs/$RUN_ID"
DATA="$RUN/data"
OUT="$RUN/out"
MODELS="$BASE/train/models"
COMFY="$BASE/ComfyUI"
LTX=https://huggingface.co/Lightricks/LTX-2.5/resolve/main
AUTH="Authorization: Bearer ${HF_TOKEN:-}"
STEPS="${STEPS:-2000}"; RANK="${RANK:-32}"; LR="${LR:-1e-4}"; I2V_P="${I2V_P:-0.5}"; FF="${FF:-0}"
mkdir -p "$RUN" "$OUT" "$MODELS"
stage() { echo "TRAIN_STAGE: $*"; }
die() { echo "FATAL: $*"; exit 1; }

stage "checking the GPU"
command -v nvidia-smi >/dev/null || die "no nvidia-smi: this runtime has no GPU"
DRIVER=$(nvidia-smi --query-gpu=driver_version --format=csv,noheader | head -1)
VRAM_MB=$(nvidia-smi --query-gpu=memory.total --format=csv,noheader,nounits | head -1)
GPU=$(nvidia-smi --query-gpu=name --format=csv,noheader | head -1)
echo "GPU: $GPU, ${VRAM_MB} MB, driver $DRIVER"
# ltx-trainer installs torch for CUDA 13.2 (cu132 index), which needs an R580+ driver.
[ "${DRIVER%%.*}" -ge 580 ] 2>/dev/null || die "driver $DRIVER is older than 580; the trainer's CUDA 13.2 torch won't run here"
[ "${VRAM_MB:-0}" -ge 30000 ] || die "only ${VRAM_MB} MB VRAM; ltx-trainer needs 32 GB at least"

[ -n "${HF_TOKEN:-}" ] || die "HF_TOKEN missing (the LTX-2.5 Dev transformer is gated)"
[ -s "$DATA/dataset.json" ] || die "no dataset.json in $DATA"

stage "freeing ComfyUI's GPU memory (renders pause while this trains)"
curl -s -m 20 -X POST http://127.0.0.1:8188/free -H 'Content-Type: application/json' \
  -d '{"unload_models": true, "free_memory": true}' >/dev/null || echo "NOTE: ComfyUI not answering; nothing to free"

stage "installing ltx-trainer"
export PATH="$HOME/.local/bin:$PATH"
command -v uv >/dev/null || curl -LsSf https://astral.sh/uv/install.sh | sh >/dev/null 2>&1
command -v uv >/dev/null || die "couldn't install uv"
if [ -d "$BASE/train/LTX-2/.git" ]; then git -C "$BASE/train/LTX-2" pull -q --ff-only || echo "NOTE: git pull failed; using the copy on disk"
else git clone -q --depth 1 https://github.com/Lightricks/LTX-2 "$BASE/train/LTX-2" || die "couldn't clone Lightricks/LTX-2"; fi
cd "$BASE/train/LTX-2" && uv sync -q || die "uv sync failed (see the lines above)"
cd packages/ltx-trainer || die "packages/ltx-trainer missing in the checkout"
uv run python -c "import torch; assert torch.cuda.is_available(), 'CUDA not available'; print('torch', torch.__version__, 'cuda', torch.version.cuda)" \
  || die "torch can't use the GPU in the trainer's environment"

fetch() { # fetch <hf path> <dest>
  [ -s "$2" ] && { echo "SKIP (exists): $2"; return 0; }
  for i in 1 2 3; do wget -c -q --header="$AUTH" "$LTX/$1" -O "$2" && { echo "GOT: $2"; return 0; }; sleep 5; done
  rm -f "$2"; return 1
}
pick() { for f in "$@"; do [ -s "$f" ] && { echo "$f"; return 0; }; done; return 1; }

stage "getting the models"
FREE_GB=$(df -BG --output=avail "$BASE" | tail -1 | tr -dc '0-9')
DEV="$MODELS/ltx-2.5-22b-dev-transformer-bf16.safetensors"
if [ ! -s "$DEV" ] && [ "${FREE_GB:-0}" -lt 60 ]; then
  # Stubelius beta 1 is superseded by beta 2 and stays on Hugging Face, so it is safe to drop here.
  OLD="$COMFY/models/diffusion_models/ltx2.5-Stubelius_remix_beta1.safetensors"
  [ -s "$OLD" ] && { echo "NOTE: removing $OLD (on Hugging Face; beta 2 replaces it) to fit the Dev model"; rm -f "$OLD"; }
  FREE_GB=$(df -BG --output=avail "$BASE" | tail -1 | tr -dc '0-9')
  [ "${FREE_GB:-0}" -ge 55 ] || die "only ${FREE_GB} GB free; the Dev transformer needs 42 GB plus room for latents and checkpoints"
fi
fetch diffusion_models/ltx-2.5-22b-dev-transformer-bf16.safetensors "$DEV" || die "couldn't download the Dev transformer"
GEMMA=$(pick "$COMFY/models/text_encoders/gemma4-12b-with-proj-ltx-2.5-bf16.safetensors" "$MODELS/gemma4-12b-with-proj-ltx-2.5-bf16.safetensors") || {
  fetch text_encoders/gemma4-12b-with-proj-ltx-2.5-bf16.safetensors "$MODELS/gemma4-12b-with-proj-ltx-2.5-bf16.safetensors" \
    || die "couldn't get the Gemma 4 bf16 encoder"; GEMMA="$MODELS/gemma4-12b-with-proj-ltx-2.5-bf16.safetensors"; }
VVAE=$(pick "$COMFY/models/vae/ltx-2.5-video-vae-bf16.safetensors" "$MODELS/ltx-2.5-video-vae-bf16.safetensors") || {
  fetch vae/ltx-2.5-video-vae-bf16.safetensors "$MODELS/ltx-2.5-video-vae-bf16.safetensors" || die "couldn't get the video VAE"
  VVAE="$MODELS/ltx-2.5-video-vae-bf16.safetensors"; }
AVAE=$(pick "$COMFY/models/vae/ltx-2.5-audio-vae-bf16.safetensors" "$MODELS/ltx-2.5-audio-vae-bf16.safetensors") || {
  fetch vae/ltx-2.5-audio-vae-bf16.safetensors "$MODELS/ltx-2.5-audio-vae-bf16.safetensors" || die "couldn't get the audio VAE"
  AVAE="$MODELS/ltx-2.5-audio-vae-bf16.safetensors"; }
echo "models: $DEV | $GEMMA | $VVAE"

LOWVRAM=0; [ "$VRAM_MB" -lt 79000 ] && LOWVRAM=1
stage "preparing the dataset (encoding $(grep -c '"caption"' "$DATA/dataset.json") items)"
PRE="$RUN/precomputed"
if [ ! -f "$PRE/.done" ]; then
  uv run python scripts/process_dataset.py "$DATA/dataset.json" --resolution-buckets "$BUCKETS" \
    --model-path "$DEV" --text-encoder-path "$GEMMA" --video-vae-path "$VVAE" --audio-vae-path "$AVAE" \
    --output-dir "$PRE" --skip-audio ${TRIGGER:+--lora-trigger "$TRIGGER"} \
    $([ "$LOWVRAM" = 1 ] && echo --load-text-encoder-in-8bit) || die "dataset preprocessing failed (see above)"
  touch "$PRE/.done"
fi

stage "writing the training config"
RESUME=null
[ -n "$(find "$OUT/checkpoints" -name '*.safetensors' 2>/dev/null | head -1)" ] && RESUME="\"$OUT/checkpoints\"" && echo "resuming from $OUT/checkpoints"
MODULES='"attn1.to_k", "attn1.to_q", "attn1.to_v", "attn1.to_out.0", "attn2.to_k", "attn2.to_q", "attn2.to_v", "attn2.to_out.0"'
[ "$FF" = 1 ] && MODULES="$MODULES, \"ff.net.0.proj\", \"ff.net.2\""
if [ "$LOWVRAM" = 1 ]; then QUANT='"int8-quanto"'; OPT=adamw8bit; TE8=true; [ "$RANK" -gt 16 ] && RANK=16
else QUANT=null; OPT=adamw; TE8=false; fi
cat > "$RUN/config.yaml" <<YAML
# Generated from ltx-trainer configs/i2v_lora.yaml: video only (silent clips), no validation sampling.
model:
  model_path: "$DEV"
  text_encoder_path: "$GEMMA"
  video_vae_path: "$VVAE"
  training_mode: "lora"
  load_checkpoint: $RESUME
lora:
  rank: $RANK
  alpha: $RANK
  dropout: 0.0
  target_modules: [ $MODULES ]
training_strategy:
  name: "flexible"
  video:
    is_generated: true
    latents_dir: "latents"
    conditions:
      - type: first_frame
        probability: $I2V_P
optimization:
  learning_rate: $LR
  steps: $STEPS
  batch_size: 1
  gradient_accumulation_steps: 1
  max_grad_norm: 1.0
  optimizer_type: "$OPT"
  scheduler_type: "linear"
  scheduler_params: { }
  enable_gradient_checkpointing: true
acceleration:
  mixed_precision_mode: "bf16"
  quantization: $QUANT
  load_text_encoder_in_8bit: $TE8
data:
  preprocessed_data_root: "$PRE"
  num_dataloader_workers: 2
validation:
  interval: null
checkpoints:
  interval: 250
  keep_last_n: -1
  precision: "bfloat16"
flow_matching:
  timestep_sampling_mode: "shifted_logit_normal"
  timestep_sampling_params: { }
seed: 42
output_dir: "$OUT"
YAML

# Every finished checkpoint is announced (name + size) so the hub pulls it off the runtime and checks the size.
announce() {
  for f in $(find "$OUT" -name '*.safetensors' 2>/dev/null | sort); do
    [ -f "$f.announced" ] && continue
    sleep 5; s1=$(stat -c%s "$f"); sleep 5; s2=$(stat -c%s "$f")
    [ "$s1" = "$s2" ] || continue
    echo "CKPT $f $s2"; touch "$f.announced"
  done
}
( while sleep 60; do announce; done ) &
WATCH=$!
trap 'kill $WATCH 2>/dev/null' EXIT

stage "training $STEPS steps (rank $RANK, lr $LR, $([ "$LOWVRAM" = 1 ] && echo low-VRAM || echo standard) config)"
uv run python scripts/train.py "$RUN/config.yaml" 2>&1 | tr '\r' '\n' | grep --line-buffered -v '^\s*$' \
  || die "training stopped (see above)"
announce

FINAL=$(find "$OUT" -name '*.safetensors' -printf '%T@ %p
' 2>/dev/null | sort -n | tail -1 | cut -d' ' -f2-)
[ -n "$FINAL" ] || die "training finished but no .safetensors was written in $OUT"
DEST="$COMFY/models/loras/${NAME}.safetensors"
cp -f "$FINAL" "$DEST" && echo "installed in ComfyUI: $DEST"
echo "FINAL $FINAL $(stat -c%s "$FINAL")"
if [ -n "${HF_REPO:-}" ]; then
  uv run python - "$FINAL" "$NAME" <<'PY' || echo "NOTE: Hugging Face backup failed; the relay copy is the backup"
import os, sys
from huggingface_hub import upload_file
upload_file(path_or_fileobj=sys.argv[1], path_in_repo=f"trained/{sys.argv[2]}.safetensors",
            repo_id=os.environ["HF_REPO"], token=os.environ["HF_TOKEN"])
print("backed up to Hugging Face:", os.environ["HF_REPO"])
PY
fi
echo "TRAIN_COMPLETE $NAME"
