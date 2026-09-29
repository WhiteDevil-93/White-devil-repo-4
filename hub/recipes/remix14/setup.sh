#!/usr/bin/env bash
# Wan2.2 Remix 14B — ComfyUI GPU-box setup (Vast / RunPod / G4).
# Replaces the old I2V-only kitchen-sink LoRA stack.
#
#   MODE=both|i2v|t2v KIT=recipes|none INSTALL_SFW=0|1 \
#     setsid nohup bash setup.sh > logs/setup.log 2>&1 < /dev/null &
#
# Env:
#   BASE_DIR=/workspace     install root (ComfyUI lives here)
#   MODE=both               i2v | t2v | both  (default both)
#   KIT=recipes             none = Lightning only
#                           recipes = gay/straight/softcore/T2V pose kits (docs/lora-recipes.md)
#   INSTALL_SFW=0           also fetch official I2V 14B + Remix SFW T2V + stock encoder
#   SKIP_COMFY=0            1 = keep existing ComfyUI, only refresh weights
#   LISTEN=127.0.0.1        0.0.0.0 if the provider exposes the port
#   CIVITAI_TOKEN=          needed for possession + (sometimes) muscle
#   TORCH_INDEX=            override; auto cu128 on G4/Blackwell, else cu126
#   START=1                 launch ComfyUI at the end
set -uo pipefail

BASE="${BASE_DIR:-/workspace}"
MODE="${MODE:-both}"
KIT="${KIT:-recipes}"
INSTALL_SFW="${INSTALL_SFW:-0}"
SKIP_COMFY="${SKIP_COMFY:-0}"
LISTEN="${LISTEN:-127.0.0.1}"
START="${START:-1}"
mkdir -p "$BASE/logs" "$BASE/downloads"

need_gb=80
[[ "$MODE" == "both" ]] && need_gb=130
[[ "$INSTALL_SFW" == "1" ]] && need_gb=$((need_gb + 40))

# --- python: prefer conda (vast/RunPod images), else venv ---
if [ -x /opt/conda/bin/python ]; then
  export PATH="/opt/conda/bin:$PATH"
  PYBIN="/opt/conda/bin/python"
else
  PYBIN="$BASE/venv/bin/python"
  [ -x "$PYBIN" ] || python3 -m venv "$BASE/venv" || python3 -m venv --without-pip "$BASE/venv"
  "$PYBIN" -m pip --version >/dev/null 2>&1 || curl -sS https://bootstrap.pypa.io/get-pip.py | "$PYBIN" - -q
  "$PYBIN" -m pip install -U pip
fi
echo "using python: $($PYBIN --version) at $PYBIN"
echo "MODE=$MODE KIT=$KIT INSTALL_SFW=$INSTALL_SFW"

# --- prereqs ---
if command -v apt-get >/dev/null 2>&1; then
  apt-get update -qq
  DEBIAN_FRONTEND=noninteractive apt-get install -y -qq git wget ffmpeg >/dev/null 2>&1 || \
    echo "WARN: apt install failed - ensure git/wget/ffmpeg exist"
fi
command -v git >/dev/null || { echo "FATAL: git missing"; exit 1; }

FREE_GB=$(df -BG --output=avail "$BASE" | tail -1 | tr -dc '0-9')
if [ "${FREE_GB:-0}" -lt "$need_gb" ]; then
  echo "FATAL: only ${FREE_GB}GB free under $BASE; need ~${need_gb}GB for MODE=$MODE INSTALL_SFW=$INSTALL_SFW"
  echo "       grow the disk, or MODE=i2v (fp8 only, ~80GB) / KIT=none"
  exit 1
fi

if nvidia-smi 2>/dev/null | grep -qiE 'RTX PRO 6000|Blackwell|GB202'; then
  TORCH_INDEX="${TORCH_INDEX:-https://download.pytorch.org/whl/cu128}"
else
  TORCH_INDEX="${TORCH_INDEX:-https://download.pytorch.org/whl/cu126}"
fi
echo "torch index: $TORCH_INDEX"

# --- ComfyUI ---
if [ "$SKIP_COMFY" != "1" ]; then
  rm -rf "$BASE/ComfyUI"
  git clone --depth 1 --branch v0.37.0 https://github.com/comfyanonymous/ComfyUI.git "$BASE/ComfyUI"
  sed -i 's/except ImportError as e:/except Exception as e:/' "$BASE/ComfyUI/comfy/quant_ops.py"
  cd "$BASE/ComfyUI"
  "$PYBIN" -m pip install -r requirements.txt
  "$PYBIN" -m pip install torch==2.8.0 torchvision==0.23.0 torchaudio==2.8.0 --index-url "$TORCH_INDEX"
  "$PYBIN" -c "import torch, torchaudio, torchvision; print('imports OK, torch', torch.__version__, 'cuda', torch.cuda.is_available())" || { echo "FATAL: import check"; exit 1; }
  mkdir -p custom_nodes
  [ -d custom_nodes/ComfyUI-Manager ] || git clone --depth 1 https://github.com/ltdrdata/ComfyUI-Manager custom_nodes/ComfyUI-Manager
else
  cd "$BASE/ComfyUI" || { echo "FATAL: $BASE/ComfyUI missing (unset SKIP_COMFY)"; exit 1; }
fi

mkdir -p models/diffusion_models models/text_encoders models/vae models/loras

# --- download helper (idempotent: skips files that already exist with size>0) ---
quote_path() { "$PYBIN" -c 'import sys,urllib.parse; print(urllib.parse.quote(sys.argv[1], safe="/"))' "$1"; }
dl() { # dl <url> <out>
  if [ -s "$2" ]; then echo "SKIP (exists): $2"; return 0; fi
  mkdir -p "$(dirname "$2")"
  for i in 1 2 3; do wget -c -q "$1" -O "$2" && return 0; sleep 5; done
  echo "FAILED: $2" >> "$BASE/downloads/failed.txt"; return 1
}
dl_hf() { # dl_hf <repo> <path> <out>
  local enc; enc="$(quote_path "$2")"
  dl "https://huggingface.co/$1/resolve/main/${enc}" "$3"
}
rm -f "$BASE/downloads/failed.txt"
HF=FX-FeiHou/wan2.2-Remix

want_i2v=0; want_t2v=0
[[ "$MODE" == "i2v" || "$MODE" == "both" ]] && want_i2v=1
[[ "$MODE" == "t2v" || "$MODE" == "both" ]] && want_t2v=1
[[ "$MODE" == "i2v" || "$MODE" == "t2v" || "$MODE" == "both" ]] || { echo "FATAL: MODE must be i2v|t2v|both"; exit 1; }

# --- base models ---
if [ "$want_i2v" = 1 ]; then
  dl_hf "$HF" "NSFW/Wan2.2_Remix_NSFW_i2v_14b_high_lighting_fp8_e4m3fn_v3.0.safetensors" \
    models/diffusion_models/Wan2.2_Remix_NSFW_i2v_14b_high_lighting_fp8_e4m3fn_v3.0.safetensors &
  dl_hf "$HF" "NSFW/Wan2.2_Remix_NSFW_i2v_14b_low_lighting_fp8_e4m3fn_v3.0.safetensors" \
    models/diffusion_models/Wan2.2_Remix_NSFW_i2v_14b_low_lighting_fp8_e4m3fn_v3.0.safetensors &
fi
if [ "$want_t2v" = 1 ]; then
  dl_hf "$HF" "NSFW/Wan2.2_Remix_NSFW_t2v_14b_high_lighting_v2.0.safetensors" \
    models/diffusion_models/Wan2.2_Remix_NSFW_t2v_14b_high_lighting_v2.0.safetensors &
  dl_hf "$HF" "NSFW/Wan2.2_Remix_NSFW_t2v_14b_low_lighting_v2.0.safetensors" \
    models/diffusion_models/Wan2.2_Remix_NSFW_t2v_14b_low_lighting_v2.0.safetensors &
fi
dl_hf "NSFW-API/NSFW-Wan-UMT5-XXL" "nsfw_wan_umt5-xxl_fp8_scaled.safetensors" \
  models/text_encoders/nsfw_wan_umt5-xxl_fp8_scaled.safetensors &
dl_hf "Comfy-Org/Wan_2.2_ComfyUI_Repackaged" "split_files/vae/wan_2.1_vae.safetensors" \
  models/vae/wan_2.1_vae.safetensors &

if [ "$INSTALL_SFW" = 1 ]; then
  dl_hf "Comfy-Org/Wan_2.2_ComfyUI_Repackaged" "split_files/diffusion_models/wan2.2_i2v_high_noise_14B_fp8_scaled.safetensors" \
    models/diffusion_models/wan2.2_i2v_high_noise_14B_fp8_scaled.safetensors &
  dl_hf "Comfy-Org/Wan_2.2_ComfyUI_Repackaged" "split_files/diffusion_models/wan2.2_i2v_low_noise_14B_fp8_scaled.safetensors" \
    models/diffusion_models/wan2.2_i2v_low_noise_14B_fp8_scaled.safetensors &
  dl_hf "Comfy-Org/Wan_2.2_ComfyUI_Repackaged" "split_files/text_encoders/umt5_xxl_fp8_e4m3fn_scaled.safetensors" \
    models/text_encoders/umt5_xxl_fp8_e4m3fn_scaled.safetensors &
  dl_hf "$HF" "SFW/Wan2.2_Remix_SFW_t2v_14b_high_lighting_v1.0_dyno.safetensors" \
    models/diffusion_models/Wan2.2_Remix_SFW_t2v_14b_high_lighting_v1.0_dyno.safetensors &
  dl_hf "$HF" "SFW/Wan2.2_Remix_SFW_t2v_14b_low_lighting_v1.0.safetensors" \
    models/diffusion_models/Wan2.2_Remix_SFW_t2v_14b_low_lighting_v1.0.safetensors &
fi

# --- speed LoRAs (always, matched to MODE) ---
if [ "$want_i2v" = 1 ]; then
  dl_hf "Kijai/WanVideo_comfy" "LoRAs/Wan22-Lightning/old/Wan2.2-Lightning_I2V-A14B-4steps-lora_HIGH_fp16.safetensors" \
    models/loras/lightx2v_I2V_HIGH.safetensors &
  dl_hf "Kijai/WanVideo_comfy" "LoRAs/Wan22-Lightning/old/Wan2.2-Lightning_I2V-A14B-4steps-lora_LOW_fp16.safetensors" \
    models/loras/lightx2v_I2V_LOW.safetensors &
fi
if [ "$want_t2v" = 1 ]; then
  dl_hf "Kijai/WanVideo_comfy" "LoRAs/Wan22-Lightning/old/Wan2.2-Lightning_T2V-A14B-4steps-lora_HIGH_fp16.safetensors" \
    models/loras/lightx2v_T2V_HIGH.safetensors &
  dl_hf "Kijai/WanVideo_comfy" "LoRAs/Wan22-Lightning/old/Wan2.2-Lightning_T2V-A14B-4steps-lora_LOW_fp16.safetensors" \
    models/loras/lightx2v_T2V_LOW.safetensors &
fi

# --- recipe kits (parts bin — load ONE content pair per graph; see docs/lora-recipes.md) ---
if [ "$KIT" = "recipes" ]; then
  # official-Wan general enhancer (do NOT stack on Remix NSFW)
  dl_hf "wiikoo/WAN-LORA" "wan2.2/NSFW-22-H-e8.safetensors" models/loras/nsfw22_H.safetensors &
  dl_hf "wiikoo/WAN-LORA" "wan2.2/NSFW-22-L-e8.safetensors" models/loras/nsfw22_L.safetensors &

  if [ "$want_i2v" = 1 ]; then
    # gay / MWM
    dl_hf "jasbloom/Wan2.2-I2V-A14B-Diffusers-bf16-mmxxii-rank128-lora" \
      "Wan2.2-I2V-A14B-Diffusers-bf16-mmxxii-rank128_000002750_high_noise.safetensors" models/loras/mmxxii_H.safetensors &
    dl_hf "jasbloom/Wan2.2-I2V-A14B-Diffusers-bf16-mmxxii-rank128-lora" \
      "Wan2.2-I2V-A14B-Diffusers-bf16-mmxxii-rank128_000002750_low_noise.safetensors" models/loras/mmxxii_L.safetensors &
    # anatomy backup (I2V only)
    dl_hf "lkzd7/WAN2.2_LoraSet_NSFW" "PENISLORA_22_i2v_HIGH_e320.safetensors" models/loras/penis_H.safetensors &
    dl_hf "lkzd7/WAN2.2_LoraSet_NSFW" "PENISLORA_22_i2v_LOW_e496.safetensors" models/loras/penis_L.safetensors &
    # softcore
    dl_hf "lkzd7/WAN2.2_LoraSet_NSFW" "W22_Multiscene_Photoshoot_Softcore_i2v_HN.safetensors" models/loras/softcore_H.safetensors &
    dl_hf "lkzd7/WAN2.2_LoraSet_NSFW" "W22_Multiscene_Photoshoot_Softcore_i2v_LN.safetensors" models/loras/softcore_L.safetensors &
    # straight I2V poses (load one pair)
    dl_hf "lkzd7/WAN2.2_LoraSet_NSFW" "iGoon_Blink_Missionary_I2V_HIGH v2.safetensors" models/loras/missionary_I2V_H.safetensors &
    dl_hf "lkzd7/WAN2.2_LoraSet_NSFW" "iGoon - Blink_Missionary_I2V_LOW v2.safetensors" models/loras/missionary_I2V_L.safetensors &
    dl_hf "lkzd7/WAN2.2_LoraSet_NSFW" "iGoon - Blink_Front_Doggystyle_I2V_HIGH.safetensors" models/loras/doggy_I2V_H.safetensors &
    dl_hf "lkzd7/WAN2.2_LoraSet_NSFW" "iGoon - Blink_Front_Doggystyle_I2V_LOW.safetensors" models/loras/doggy_I2V_L.safetensors &
    dl_hf "lkzd7/WAN2.2_LoraSet_NSFW" "WAN-2.2-I2V-SensualTeasingBlowjob-HIGH-v1.safetensors" models/loras/tease_I2V_H.safetensors &
    dl_hf "lkzd7/WAN2.2_LoraSet_NSFW" "WAN-2.2-I2V-SensualTeasingBlowjob-LOW-v1.safetensors" models/loras/tease_I2V_L.safetensors &
    # lesbian-adjacent
    dl_hf "wiikoo/WAN-LORA" "wan2.2/wan22-cunilingus-I2V-106epoc-high.safetensors" models/loras/cunilingus_I2V_H.safetensors &
    dl_hf "wiikoo/WAN-LORA" "wan2.2/wan22-cunilingus-I2V-72epoc-low.safetensors" models/loras/cunilingus_I2V_L.safetensors &
    # physique slider
    if [ -n "${CIVITAI_TOKEN:-}" ]; then
      dl "https://civitai.com/api/download/models/2242869?token=$CIVITAI_TOKEN" models/loras/muscle_H.safetensors &
      dl "https://civitai.com/api/download/models/2242857?token=$CIVITAI_TOKEN" models/loras/muscle_L.safetensors &
      dl "https://civitai.com/api/download/models/3168869?token=$CIVITAI_TOKEN" models/loras/possession_H.safetensors &
      dl "https://civitai.com/api/download/models/3168768?token=$CIVITAI_TOKEN" models/loras/possession_L.safetensors &
    else
      dl "https://civitai.com/api/download/models/2242869" models/loras/muscle_H.safetensors &
      dl "https://civitai.com/api/download/models/2242857" models/loras/muscle_L.safetensors &
      echo "NOTE: CIVITAI_TOKEN not set — possession skipped; muscle may 401"
    fi
  fi

  if [ "$want_t2v" = 1 ]; then
    # T2V pose kit (do not reuse any I2V *lora on these UNets)
    dl_hf "wiikoo/WAN-LORA" "wan2.2/NEW/Wan2.2 - T2V - Missionary Sex - HIGH 14B.safetensors" models/loras/missionary_T2V_H.safetensors &
    dl_hf "wiikoo/WAN-LORA" "wan2.2/NEW/Wan2.2 - T2V - Missionary Sex - LOW 14B.safetensors" models/loras/missionary_T2V_L.safetensors &
    dl_hf "wiikoo/WAN-LORA" "wan2.2/NEW/Wan2.2 - T2V - Orgasm - HIGH 14B.safetensors" models/loras/orgasm_T2V_H.safetensors &
    dl_hf "wiikoo/WAN-LORA" "wan2.2/NEW/Wan2.2 - T2V - Orgasm - LOW 14B.safetensors" models/loras/orgasm_T2V_L.safetensors &
    dl_hf "wiikoo/WAN-LORA" "wan2.2/NEW/Wan2.2 - T2V - Hand in Panties - HIGH 14B.safetensors" models/loras/hand_T2V_H.safetensors &
    dl_hf "wiikoo/WAN-LORA" "wan2.2/NEW/Wan2.2 - T2V - Hand in Panties - LOW 14B.safetensors" models/loras/hand_T2V_L.safetensors &
    dl_hf "wiikoo/WAN-LORA" "wan2.2/NEW/jfj-deepthroat-W22-T2V-HN-v1.safetensors" models/loras/deepthroat_T2V_H.safetensors &
    dl_hf "wiikoo/WAN-LORA" "wan2.2/NEW/jfj-deepthroat-W22-T2V-LN-v1.safetensors" models/loras/deepthroat_T2V_L.safetensors &
  fi
elif [ "$KIT" != "none" ]; then
  echo "FATAL: KIT must be recipes|none"; exit 1
fi

wait

# Do NOT symlink the NSFW encoder over the stock name — that breaks SFW official Wan.
# Workflows that still look for umt5_xxl_* on Remix NSFW should point at nsfw_wan_umt5-xxl_*.

# --- validate ---
"$PYBIN" - <<'PY'
from safetensors import safe_open
import glob, sys, os
files = sorted(glob.glob("models/**/*.safetensors", recursive=True))
bad = 0
for f in files:
    if os.path.getsize(f) < 10_000_000:
        print(f"SUSPICIOUS SIZE {os.path.getsize(f)}: {f}"); bad += 1; continue
    try:
        with safe_open(f, framework="pt") as st:
            print(f"VALID {len(list(st.keys()))}: {f}")
    except Exception as e:
        bad += 1; print(f"INVALID: {f}: {e}")
failed = os.path.exists("/workspace/downloads/failed.txt") or os.path.exists("downloads/failed.txt")
if failed:
    print("FAILED DOWNLOADS:")
    for p in ("/workspace/downloads/failed.txt", "downloads/failed.txt"):
        if os.path.exists(p):
            print(open(p).read())
if bad or failed or not files:
    print(f"RESULT: PROBLEM (files={len(files)}, bad={bad})"); sys.exit(1)
print(f"RESULT: ALL_VALID_{len(files)}")
PY

cat > "$BASE/start_comfy.sh" <<EOS
#!/usr/bin/env bash
BASE="\${BASE_DIR:-$BASE}"
if [ -x /opt/conda/bin/python ]; then export PATH="/opt/conda/bin:\$PATH"; PYBIN=/opt/conda/bin/python; else PYBIN="\$BASE/venv/bin/python"; fi
cd "\$BASE/ComfyUI"
pkill -f "python main.py" 2>/dev/null; sleep 2
setsid nohup "\$PYBIN" main.py --listen ${LISTEN} --port 8188 > "\$BASE/comfyui.log" 2>&1 < /dev/null & disown
echo "started on ${LISTEN}:8188"
EOS
chmod +x "$BASE/start_comfy.sh"

if [ "$START" = 1 ]; then
  bash "$BASE/start_comfy.sh"
fi

echo "SETUP_COMPLETE MODE=$MODE KIT=$KIT"
echo "Recipes: load ONE content pair per graph. Remix NSFW already has general NSFW — skip nsfw22_* on Remix."
echo "I2V UNets + I2V LoRAs only; T2V UNets + T2V LoRAs only. mmxxii is I2V (gay). See docs/lora-recipes.md"
