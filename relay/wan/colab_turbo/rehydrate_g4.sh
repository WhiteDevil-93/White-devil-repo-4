#!/bin/bash
set -u
export PYTHONUNBUFFERED=1
LOG=/content/outputs/colab_g4_ti2v5b_smoke/rehydrate.log
mkdir -p /content/loras_5b /content/outputs/colab_g4_ti2v5b_smoke/gooning
exec > >(tee -a "$LOG") 2>&1
echo "REHYDRATE_START $(date -u)"

pip -q install "huggingface_hub[hf_transfer]>=1.5,<2" hf_transfer 2>&1 | tail -5
export HF_HUB_ENABLE_HF_TRANSFER=1

# Base Diffusers Turbo (~same layout as prior /content/Wan2.2-TI2V-5B-Turbo)
if [[ ! -f /content/Wan2.2-TI2V-5B-Turbo/model_index.json ]]; then
  echo "DL base yetter-ai/Wan2.2-TI2V-5B-Turbo-Diffusers"
  python3 -c "from huggingface_hub import snapshot_download; snapshot_download('yetter-ai/Wan2.2-TI2V-5B-Turbo-Diffusers', local_dir='/content/Wan2.2-TI2V-5B-Turbo')"
else
  echo "HAVE base"
fi
ls -lah /content/Wan2.2-TI2V-5B-Turbo/model_index.json

dl() {
  local url="$1" dest="$2"
  if [[ -f "$dest" && $(stat -c%s "$dest") -gt 1000000 ]]; then
    echo "HAVE $dest ($(stat -c%s "$dest"))"; return 0
  fi
  echo "GET $dest <- $url"
  wget -q --show-progress -O "$dest.tmp" "$url" && mv -f "$dest.tmp" "$dest"
  ls -lah "$dest"
}

# Known-good LoRAs
dl "https://huggingface.co/jerd16/penis-lora-taz-wan22/resolve/main/PENISLORA_WAN22_5B_e349.safetensors" \
  /content/loras_5b/PENISLORA_WAN22_5B_e349.safetensors

# LUSTY-5B (replaces the lost mast LoRA); CivitAI needs the key from the relay bundle
LUSTY=/content/loras_5b/lusty-5b.safetensors
if [[ ! -s "$LUSTY" && -s /content/bundle/civitai_token ]]; then
  wget -q -O "$LUSTY.tmp" "https://civitai.com/api/download/models/3029501?token=$(cat /content/bundle/civitai_token)" \
    && [[ -s "$LUSTY.tmp" ]] && mv -f "$LUSTY.tmp" "$LUSTY"
  rm -f "$LUSTY.tmp"
fi
ls -lah "$LUSTY" 2>&1

# DR34 TI2V-5B V1 — try HF mirrors then CivitAI
if [[ ! -f /content/loras_5b/DR34ML4Y_TI2V_5B_V1.safetensors ]]; then
  for url in \
    "https://huggingface.co/rahul7star/wan2.2Lora/resolve/main/DR34ML4Y_TI2V_5B_V1.safetensors" \
    "https://huggingface.co/UnifiedHorusRA/TheWan2.2TI2V5B/resolve/main/DR34ML4Y_TI2V_5B_V1.safetensors" \
    "https://civitai.com/api/download/models/2099309"
  do
    echo "TRY DR34 $url"
    if wget -q --show-progress -O /content/loras_5b/DR34ML4Y_TI2V_5B_V1.safetensors.tmp "$url"; then
      mv -f /content/loras_5b/DR34ML4Y_TI2V_5B_V1.safetensors.tmp /content/loras_5b/DR34ML4Y_TI2V_5B_V1.safetensors
      break
    fi
    rm -f /content/loras_5b/DR34ML4Y_TI2V_5B_V1.safetensors.tmp
  done
fi
ls -lah /content/loras_5b/DR34ML4Y_TI2V_5B_V1.safetensors 2>&1

# mast LoRA — search common mirrors
if [[ ! -f /content/loras_5b/Wan2.2-TI2V-5B-mast-768px-73f-r32.safetensors ]]; then
  for url in \
    "https://huggingface.co/rahul7star/wan2.2Lora/resolve/main/Wan2.2-TI2V-5B-mast-768px-73f-r32.safetensors" \
    "https://huggingface.co/UnifiedHorusRA/TheWan2.2TI2V5B/resolve/main/Wan2.2-TI2V-5B-mast-768px-73f-r32.safetensors" \
    "https://huggingface.co/jerd16/penis-lora-taz-wan22/resolve/main/Wan2.2-TI2V-5B-mast-768px-73f-r32.safetensors"
  do
    echo "TRY MAST $url"
    if wget -q --show-progress -O /content/loras_5b/Wan2.2-TI2V-5B-mast-768px-73f-r32.safetensors.tmp "$url"; then
      mv -f /content/loras_5b/Wan2.2-TI2V-5B-mast-768px-73f-r32.safetensors.tmp /content/loras_5b/Wan2.2-TI2V-5B-mast-768px-73f-r32.safetensors
      break
    fi
    rm -f /content/loras_5b/Wan2.2-TI2V-5B-mast-768px-73f-r32.safetensors.tmp
  done
fi
ls -lah /content/loras_5b/Wan2.2-TI2V-5B-mast-768px-73f-r32.safetensors 2>&1

echo "REHYDRATE_LORAS"
ls -lah /content/loras_5b/*.safetensors 2>&1 | head -20
echo "REHYDRATE_DONE $(date -u)"