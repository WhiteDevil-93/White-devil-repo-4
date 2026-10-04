#!/usr/bin/env bash
# LTX-2.5 NSFW kit — ComfyUI GPU-box setup (G4 RTX PRO 6000 96 GB / rented 80-96 GB cards).
# From the operator notes 2026-09-25: one transformer, LTX Gemma 4, 2.5 VAEs, distilled LoRA 450
# on Stubelius, one sex LoRA, plus the full Lightricks Setup pack (8 IC + 2 control LoRAs).
#
#   TRANSFORMER=stubelius_b2 GEMMA=bf16 SEX_LORA=mylo \
#     setsid nohup bash setup.sh > logs/setup.log 2>&1 < /dev/null &
#
# Env:
#   BASE_DIR=/workspace     install root (ComfyUI lives here)
#   TRANSFORMER=stubelius_b1 | stubelius_b2 | stubelius_b2_int8 | distilled
#   GEMMA=bf16 | int8
#   SEX_LORA=mylo | sexgod | none | all   (all = mylo+sexgod; CONTENT_LORAS still pulls every NSFW content LoRA)
#   PACK_LORAS=1            0 = skip the 11-file Lightricks Setup pack (IC + control)
#   CONTENT_LORAS=1         0 = skip NSFW content LoRAs (CoachBate, Praxis, BEANFLK, …)
#   EXTRAS=0                1 = also the temporal x2 latent upscaler (spatial x2 + duration head always)
#   SKIP_COMFY=0            1 = keep existing ComfyUI (e.g. the Wan 14B one), only add nodes + weights
#   NEED_GB=100             refuse to start with less free disk
#   HF_TOKEN=               required: Lightricks/LTX-2.5 is gated (accept it on the model page first)
#   CIVITAI_TOKEN=          required for NSFW content LoRAs from Civitai
#   LISTEN=127.0.0.1  START=1
#   CoachBate 2.3 (penis-lora-by-coachbate-ltx-2.3.safetensors) is NOT on Civitai as the
#   full file. Phone setup uploads it into /content/lora_keep/ from the relay stage;
#   this script restores it into models/loras/ after ComfyUI is (re)cloned.
set -uo pipefail

BASE="${BASE_DIR:-/workspace}"
TRANSFORMER="${TRANSFORMER:-stubelius_b2}"
TRAINER="${TRAINER:-0}"
GEMMA="${GEMMA:-bf16}"
SEX_LORA="${SEX_LORA:-all}"
PACK_LORAS="${PACK_LORAS:-1}"
CONTENT_LORAS="${CONTENT_LORAS:-1}"
EXTRAS="${EXTRAS:-0}"
SKIP_COMFY="${SKIP_COMFY:-0}"
NEED_GB="${NEED_GB:-100}"
LISTEN="${LISTEN:-127.0.0.1}"
START="${START:-1}"
mkdir -p "$BASE/logs" "$BASE/downloads"
echo "TRANSFORMER=$TRANSFORMER GEMMA=$GEMMA SEX_LORA=$SEX_LORA PACK_LORAS=$PACK_LORAS CONTENT_LORAS=$CONTENT_LORAS EXTRAS=$EXTRAS SKIP_COMFY=$SKIP_COMFY"

[ -n "${HF_TOKEN:-}" ] || { echo "FATAL: HF_TOKEN missing (Lightricks/LTX-2.5 is gated)"; exit 1; }
code=$(curl -s -o /dev/null -w '%{http_code}' -I -H "Authorization: Bearer $HF_TOKEN" \
  "https://huggingface.co/Lightricks/LTX-2.5/resolve/main/vae/ltx-2.5-audio-vae-bf16.safetensors")
case "$code" in 200|302|307) ;; *) echo "FATAL: HF token can't read Lightricks/LTX-2.5 (HTTP $code); accept the licence on the model page"; exit 1;; esac

if [ -x /opt/conda/bin/python ]; then
  export PATH="/opt/conda/bin:$PATH"; PYBIN="/opt/conda/bin/python"
else
  PYBIN="$BASE/venv/bin/python"
  [ -x "$PYBIN" ] || python3 -m venv "$BASE/venv" || python3 -m venv --without-pip "$BASE/venv"
  "$PYBIN" -m pip --version >/dev/null 2>&1 || curl -sS https://bootstrap.pypa.io/get-pip.py | "$PYBIN" - -q
  "$PYBIN" -m pip install -q -U pip
fi
echo "using python: $($PYBIN --version) at $PYBIN"

if command -v apt-get >/dev/null 2>&1; then
  apt-get update -qq
  DEBIAN_FRONTEND=noninteractive apt-get install -y -qq git wget curl ffmpeg >/dev/null 2>&1 || \
    echo "WARN: apt install failed - ensure git/wget/ffmpeg exist"
fi
command -v git >/dev/null || { echo "FATAL: git missing"; exit 1; }

FREE_GB=$(df -BG --output=avail "$BASE" | tail -1 | tr -dc '0-9')
if [ "${FREE_GB:-0}" -lt "$NEED_GB" ]; then
  echo "FATAL: only ${FREE_GB}GB free under $BASE; need ~${NEED_GB}GB (try GEMMA=int8 or TRANSFORMER=stubelius_b2_int8)"
  exit 1
fi

TORCH_INDEX="${TORCH_INDEX:-https://download.pytorch.org/whl/cu128}"
echo "torch index: $TORCH_INDEX"

# --- ComfyUI (latest: LTX-2.5 / Gemma 4 support is newer than the Wan pin) ---
# Park the CoachBate penis LoRA outside ComfyUI. Setup deletes that tree, and this file is not on Civitai.
COACH_NAME=penis-lora-by-coachbate-ltx-2.3.safetensors
COACH_KEEP="/content/lora_keep/$COACH_NAME"
mkdir -p /content/lora_keep
if [ -s "$BASE/ComfyUI/models/loras/$COACH_NAME" ]; then
  cp -f "$BASE/ComfyUI/models/loras/$COACH_NAME" "$COACH_KEEP"
  echo "PARKED: $COACH_KEEP"
fi

if [ "$SKIP_COMFY" != "1" ]; then
  echo "== installing ComfyUI"
  rm -rf "$BASE/ComfyUI"
  git clone --depth 1 https://github.com/comfyanonymous/ComfyUI.git "$BASE/ComfyUI"
  cd "$BASE/ComfyUI"
  "$PYBIN" -m pip uninstall -q -y torch torchvision torchaudio >/dev/null 2>&1
  "$PYBIN" -m pip install -q torch torchvision torchaudio --index-url "$TORCH_INDEX"
  "$PYBIN" -m pip install -q -r requirements.txt
  "$PYBIN" -c "import torch, torchaudio, torchvision; print('imports OK, torch', torch.__version__, 'cuda', torch.cuda.is_available())" || { echo "FATAL: import check"; exit 1; }
  mkdir -p custom_nodes
  [ -d custom_nodes/ComfyUI-Manager ] || git clone --depth 1 https://github.com/ltdrdata/ComfyUI-Manager custom_nodes/ComfyUI-Manager
else
  cd "$BASE/ComfyUI" || { echo "FATAL: $BASE/ComfyUI missing (unset SKIP_COMFY)"; exit 1; }
fi

echo "== ComfyUI-LTXVideo nodes"
if [ -d custom_nodes/ComfyUI-LTXVideo ]; then git -C custom_nodes/ComfyUI-LTXVideo pull -q --ff-only || true
else git clone --depth 1 https://github.com/Lightricks/ComfyUI-LTXVideo custom_nodes/ComfyUI-LTXVideo; fi
[ -f custom_nodes/ComfyUI-LTXVideo/requirements.txt ] && "$PYBIN" -m pip install -q -r custom_nodes/ComfyUI-LTXVideo/requirements.txt

mkdir -p models/diffusion_models models/text_encoders models/vae models/loras models/latent_upscale_models models/model_patches
if [ -s "${COACH_KEEP:-}" ]; then
  cp -f "$COACH_KEEP" "models/loras/$COACH_NAME" && echo "GOT: models/loras/$COACH_NAME (local CoachBate)"
elif [ -n "${HF_TOKEN:-}" ] && wget -q --header="Authorization: Bearer $HF_TOKEN" -O "models/loras/$COACH_NAME" \
    "https://huggingface.co/${COACH_REPO:-WhiteDevil6969/forge-loras}/resolve/main/$COACH_NAME" && [ -s "models/loras/$COACH_NAME" ]; then
  # A private copy on the owner's Hugging Face account: Colab pulls 1.3 GB from there in seconds.
  echo "GOT: models/loras/$COACH_NAME (private Hugging Face copy)"
elif rm -f "models/loras/$COACH_NAME"; [ -n "${RELAY_USER:-}" ] && [ -n "${RELAY_PASS:-}" ]; then
  wget -c -q --user="$RELAY_USER" --password="$RELAY_PASS" \
    -O "models/loras/$COACH_NAME" "https://84-12-112-249.sslip.io/api/ltx/stage/$COACH_NAME" \
    && echo "GOT: models/loras/$COACH_NAME" || { rm -f "models/loras/$COACH_NAME"; echo "FAILED: models/loras/$COACH_NAME"; }
fi
# LoRAs trained on the Train LoRA screen live in the private repo under trained/; this tree was just re-cloned, so
# bring them back into the picker.
if [ -n "${HF_TOKEN:-}" ]; then
  curl -s -m 60 -H "Authorization: Bearer $HF_TOKEN"     "https://huggingface.co/api/models/${COACH_REPO:-WhiteDevil6969/forge-loras}/tree/main/trained" 2>/dev/null     | "$PYBIN" -c "import json,sys
try: print('\n'.join(x['path'] for x in json.load(sys.stdin) if x.get('path','').endswith('.safetensors')))
except Exception: pass" | while read -r f; do
      [ -n "$f" ] || continue
      out="models/loras/$(basename "$f")"
      [ -s "$out" ] || wget -q --header="Authorization: Bearer $HF_TOKEN" -O "$out"         "https://huggingface.co/${COACH_REPO:-WhiteDevil6969/forge-loras}/resolve/main/$f"         && echo "GOT: $out (trained LoRA)" || { rm -f "$out"; echo "FAILED: $out (trained LoRA)"; }
    done
fi


dl() { # dl <url> <out> [auth header]
  if [ -s "$2" ]; then echo "SKIP (exists): $2"; return 0; fi
  mkdir -p "$(dirname "$2")"
  for i in 1 2 3; do
    if [ -n "${3:-}" ]; then wget -c -q --header="$3" "$1" -O "$2" && { echo "GOT: $2"; return 0; }
    else wget -c -q "$1" -O "$2" && { echo "GOT: $2"; return 0; }; fi
    sleep 5
  done
  rm -f "$2"
  code="?"
  if [ -n "${3:-}" ]; then
    code=$(curl -s -o /dev/null -w '%{http_code}' -I -H "$3" "$1" || echo err)
  else
    code=$(curl -s -o /dev/null -w '%{http_code}' -I "$1" || echo err)
  fi
  # 401/403 on Lightricks IC repos = accept that repo's licence on Hugging Face (separate from LTX-2.5).
  # Say which page, so the fix is one click. Never print a Civitai URL: it carries the token.
  hint=""
  case "$code" in 401|403)
    case "$1" in
      https://huggingface.co/*) r="${1#https://huggingface.co/}"; hint=" -> accept the licence while logged in as the token's owner: https://huggingface.co/${r%%/resolve/*}" ;;
      https://civitai.com/*) hint=" -> Civitai refuses this file for your token (its creator restricted downloads, or it needs a login on civitai.com)" ;;
    esac ;;
  esac
  echo "FAILED: $2 (HTTP $code)$hint" >> "$BASE/downloads/failed.txt"
  echo "FAILED: $2 (HTTP $code)$hint"
  return 1
}
LTX=https://huggingface.co/Lightricks/LTX-2.5/resolve/main
AUTH="Authorization: Bearer $HF_TOKEN"
dl_ltx() { dl "$LTX/$1" "models/$2/$(basename "$1")" "$AUTH"; }
rm -f "$BASE/downloads/failed.txt"

echo "== downloading (transformer ~42 GB, Gemma ~15-26 GB; this is the long part)"
case "$TRANSFORMER" in
  stubelius_b1)      dl https://huggingface.co/Stuubs/Stubelius_Remix/resolve/main/ltx2.5-Stubelius_remix_beta1.safetensors models/diffusion_models/ltx2.5-Stubelius_remix_beta1.safetensors & ;;
  stubelius_b2)      dl https://huggingface.co/Stuubs/Stubelius_Remix/resolve/main/ltx2.5-Stubelius_remix_beta2_bf16.safetensors models/diffusion_models/ltx2.5-Stubelius_remix_beta2_bf16.safetensors & ;;
  stubelius_b2_int8) dl https://huggingface.co/Stuubs/Stubelius_Remix/resolve/main/ltx2.5-Stubelius_remix_beta2_int8_convrot.safetensors models/diffusion_models/ltx2.5-Stubelius_remix_beta2_int8_convrot.safetensors & ;;
  distilled)         dl_ltx diffusion_models/ltx-2.5-22b-distilled-transformer-bf16.safetensors diffusion_models & ;;
  *) echo "FATAL: TRANSFORMER must be stubelius_b1|stubelius_b2|stubelius_b2_int8|distilled"; exit 1 ;;
esac
case "$GEMMA" in
  bf16) dl_ltx text_encoders/gemma4-12b-with-proj-ltx-2.5-bf16.safetensors text_encoders & ;;
  int8) dl_ltx text_encoders/gemma4-12b-with-proj-ltx-2.5-comfy-int8-convrot.safetensors text_encoders & ;;
  *) echo "FATAL: GEMMA must be bf16|int8"; exit 1 ;;
esac
dl_ltx vae/ltx-2.5-video-vae-conv-bf16.safetensors vae &
dl_ltx vae/ltx-2.5-video-vae-bf16.safetensors vae &
dl_ltx vae/ltx-2.5-audio-vae-bf16.safetensors vae &
# Beta 2 has the distilled LoRA merged in; only beta 1 needs it separately.
[[ "$TRANSFORMER" == stubelius_b1 ]] && dl_ltx loras/ltx-2.5-22b-distilled-lora-450-bf16.safetensors loras &
case "$SEX_LORA" in
  mylo)   dl "https://civitai.com/api/download/models/2774472${CIVITAI_TOKEN:+?token=$CIVITAI_TOKEN}" models/loras/ltx_mylo1337_i2v_nsfw_v2.safetensors & ;;
  sexgod) if [ -n "${CIVITAI_TOKEN:-}" ]; then
            dl "https://civitai.com/api/download/models/2778606?token=$CIVITAI_TOKEN" models/loras/ltx_sexgod_nudity_v2_LTXNUDES.safetensors &
          else echo "NOTE: CIVITAI_TOKEN not set — SexGod LoRA skipped"; fi ;;
  all)
    dl "https://civitai.com/api/download/models/2774472${CIVITAI_TOKEN:+?token=$CIVITAI_TOKEN}" models/loras/ltx_mylo1337_i2v_nsfw_v2.safetensors &
    if [ -n "${CIVITAI_TOKEN:-}" ]; then
      dl "https://civitai.com/api/download/models/2778606?token=$CIVITAI_TOKEN" models/loras/ltx_sexgod_nudity_v2_LTXNUDES.safetensors &
    else echo "NOTE: CIVITAI_TOKEN not set — SexGod LoRA skipped"; fi
    ;;
  none) ;;
  *) echo "FATAL: SEX_LORA must be mylo|sexgod|all|none"; exit 1 ;;
esac

# NSFW content LoRAs — every known LTX sex/anatomy LoRA onto models/loras/ during Setup
if [ "$CONTENT_LORAS" = "1" ]; then
  echo "== NSFW content LoRA pack"
  CT="${CIVITAI_TOKEN:-}"
  if [ -z "$CT" ]; then
    echo "NOTE: CIVITAI_TOKEN not set — Civitai content LoRAs skipped (HF ones still pull)"
  fi
  # Prefer SEX_LORA=all above for mylo/sexgod; still re-get if CONTENT alone
  [ "$SEX_LORA" = "none" ] && [ -n "$CT" ] && dl "https://civitai.com/api/download/models/2774472?token=$CT" models/loras/ltx_mylo1337_i2v_nsfw_v2.safetensors &
  [ "$SEX_LORA" = "none" ] && [ -n "$CT" ] && dl "https://civitai.com/api/download/models/2778606?token=$CT" models/loras/ltx_sexgod_nudity_v2_LTXNUDES.safetensors &
  [ -n "$CT" ] && dl "https://civitai.com/api/download/models/3192441?token=$CT" models/loras/ltx_BEANFLK_V1.safetensors &
  [ -n "$CT" ] && dl "https://civitai.com/api/download/models/2674954?token=$CT" models/loras/LTX2-i2v-SexThrust.safetensors &
  [ -n "$CT" ] && dl "https://civitai.com/api/download/models/2987819?token=$CT" models/loras/CGS23.safetensors &
  [ -n "$CT" ] && dl "https://civitai.com/api/download/models/3132867?token=$CT" models/loras/Defined_Muscle.safetensors &
  [ -n "$CT" ] && dl "https://civitai.com/api/download/models/2657002?token=$CT" models/loras/cumsplash_LTX2_v1.safetensors &
  [ -n "$CT" ] && dl "https://civitai.com/api/download/models/3086880?token=$CT" models/loras/plora_sulfter_i2v-step00008500.comfy.safetensors &
  [ -n "$CT" ] && dl "https://civitai.com/api/download/models/2609597?token=$CT" models/loras/solo-male-ltx2-4000.safetensors &
  [ -n "$CT" ] && dl "https://civitai.com/api/download/models/3220391?token=$CT" models/loras/ltx-2.5_penis_coachbate_preview1.safetensors &
  # HF-hosted NSFW (no Civitai token required)
  dl "https://huggingface.co/Muapi/gay-sex-sulphur-10eros/resolve/main/gay-sex-sulphur-10eros.safetensors" models/loras/ltx_gay-sex-sulphur-10eros.safetensors "$AUTH" &
  dl "https://huggingface.co/lynaNSFW/LTX2.3_penile_praxis/resolve/main/Penile_Praxis_V4.safetensors" models/loras/ltx_Penile_Praxis_V4.safetensors "$AUTH" &
  dl "https://huggingface.co/UnifiedHorusRA/028220/resolve/main/PENIS_LoRA_by_CoachBate/LTXV/ltx-2-19b-bwc-lora-35000.safetensors" models/loras/ltx-2-19b-bwc-lora-35000.safetensors "$AUTH" &
fi
# Full LTX 2.5 Setup pack (11 Lightricks LoRAs): distilled is above for Stubelius;
# always pull the 8 IC + 2 control LoRAs so Setup is not stuck on one sex LoRA.
if [ "$PACK_LORAS" = "1" ]; then
  echo "== LTX 2.5 Setup pack: IC + control LoRAs"
  dl "https://huggingface.co/Lightricks/LTX-2.5-22b-IC-LoRA-Pixel-Spatial-Upscaler/resolve/main/ltx-2.5-22b-ic-lora-pixel-spatial-upscaler-x2-1.0.safetensors" models/loras/ltx-2.5-22b-ic-lora-pixel-spatial-upscaler-x2-1.0.safetensors "$AUTH" &
  dl "https://huggingface.co/Lightricks/LTX-2.5-22b-IC-LoRA-Ingredients/resolve/main/ltx-2.5-22b-ic-lora-ingredients-0.9.safetensors" models/loras/ltx-2.5-22b-ic-lora-ingredients-0.9.safetensors "$AUTH" &
  dl "https://huggingface.co/Lightricks/LTX-2.5-22b-IC-LoRA-Day-To-Night/resolve/main/ltx-2.5-22b-ic-lora-day-to-night-0.9.safetensors" models/loras/ltx-2.5-22b-ic-lora-day-to-night-0.9.safetensors "$AUTH" &
  dl "https://huggingface.co/Lightricks/LTX-2.5-22b-IC-LoRA-Colorization/resolve/main/ltx-2.5-22b-ic-lora-colorization-0.9.safetensors" models/loras/ltx-2.5-22b-ic-lora-colorization-0.9.safetensors "$AUTH" &
  dl "https://huggingface.co/Lightricks/LTX-2.5-22b-IC-LoRA-Water-Simulation/resolve/main/ltx-2.5-22b-ic-lora-water-simulation-0.9.safetensors" models/loras/ltx-2.5-22b-ic-lora-water-simulation-0.9.safetensors "$AUTH" &
  dl "https://huggingface.co/Lightricks/LTX-2.5-22b-IC-LoRA-Clean-Plate/resolve/main/ltx-2.5-22b-ic-lora-clean-plate-1.0.safetensors" models/loras/ltx-2.5-22b-ic-lora-clean-plate-1.0.safetensors "$AUTH" &
  dl "https://huggingface.co/Lightricks/LTX-2.5-22b-IC-LoRA-Deblur/resolve/main/ltx-2.5-22b-ic-lora-deblur-0.9.safetensors" models/loras/ltx-2.5-22b-ic-lora-deblur-0.9.safetensors "$AUTH" &
  dl "https://huggingface.co/Lightricks/LTX-2.5-22b-IC-LoRA-Decompression/resolve/main/ltx-2.5-22b-ic-lora-decompression-0.9.safetensors" models/loras/ltx-2.5-22b-ic-lora-decompression-0.9.safetensors "$AUTH" &
  dl "https://huggingface.co/Lightricks/LTX-2.5-22b-LoRA-Slow-Motion-Control/resolve/main/ltx-2.5-22b-lora-slow-motion-control-1.0.safetensors" models/loras/ltx-2.5-22b-lora-slow-motion-control-1.0.safetensors "$AUTH" &
  dl "https://huggingface.co/Lightricks/LTX-2.5-22b-LoRA-Cinemagraph/resolve/main/ltx-2.5-22b-lora-cinemagraph-0.9.safetensors" models/loras/ltx-2.5-22b-lora-cinemagraph-0.9.safetensors "$AUTH" &
  # Distilled 450 also when using the official distilled transformer (Stubelius path already got it).
  [[ "$TRANSFORMER" != stubelius* ]] && dl_ltx loras/ltx-2.5-22b-distilled-lora-450-bf16.safetensors loras &
fi
dl_ltx latent_upscale_models/ltx-2.5-latent-spatial-upscaler-x2-bf16-1.0.safetensors latent_upscale_models &
dl_ltx model_patches/ltx-2.5-duration-head-bf16.safetensors model_patches &
if [ "$EXTRAS" = "1" ]; then
  dl_ltx latent_upscale_models/ltx-2.5-latent-temporal-upscaler-x2-bf16-1.0.safetensors latent_upscale_models &
fi
if [ "$TRAINER" = 1 ]; then
  # Pre-install the LoRA trainer so the Train LoRA screen starts at once (recipes/ltxtrain/train.sh reuses these).
  (
    export PATH="$HOME/.local/bin:$PATH"
    command -v uv >/dev/null || curl -LsSf https://astral.sh/uv/install.sh | sh >/dev/null 2>&1
    mkdir -p "$BASE/train/models"
    if [ -d "$BASE/train/LTX-2/.git" ]; then git -C "$BASE/train/LTX-2" pull -q --ff-only
    else git clone -q --depth 1 https://github.com/Lightricks/LTX-2 "$BASE/train/LTX-2"; fi
    (cd "$BASE/train/LTX-2" && uv sync -q) && echo "GOT: ltx-trainer environment" || echo "FAILED: ltx-trainer environment (train.sh retries)"
  ) &
  dl "$LTX/diffusion_models/ltx-2.5-22b-dev-transformer-bf16.safetensors"      "$BASE/train/models/ltx-2.5-22b-dev-transformer-bf16.safetensors" "$AUTH" &
fi
wait
echo "== downloads finished"

# --- validate (the duration head is legitimately tiny) ---
BASE="$BASE" "$PYBIN" - <<'PY'
from safetensors import safe_open
import glob, os, sys
base = os.environ["BASE"]
files = sorted(f for f in glob.glob("models/**/*.safetensors", recursive=True)
               if os.path.basename(f).startswith(("ltx", "gemma4")))
bad = 0
for f in files:
    small_ok = "model_patches" in f
    if os.path.getsize(f) < 10_000_000 and not small_ok:
        print(f"SUSPICIOUS SIZE {os.path.getsize(f)}: {f}"); bad += 1; continue
    try:
        with safe_open(f, framework="pt") as st:
            print(f"VALID {len(list(st.keys()))}: {f}")
    except Exception as e:
        bad += 1; print(f"INVALID: {f}: {e}")
failed = os.path.join(base, "downloads", "failed.txt")
if os.path.exists(failed):
    print("FAILED DOWNLOADS:"); print(open(failed).read())
# Core kit must exist; optional IC/content LoRA failures must NOT block start_comfy.
core_ok = any("Stubelius" in f or "distilled-lora" in f or "gemma4" in f for f in files)
if not files or bad or not core_ok:
    print(f"RESULT: PROBLEM (files={len(files)}, bad={bad}, core_ok={core_ok})"); sys.exit(1)
if os.path.exists(failed):
    print(f"RESULT: CORE_OK_WITH_GAPS (files={len(files)}, bad={bad})")
else:
    print(f"RESULT: ALL_VALID_{len(files)}")
PY
VALIDATE=$?
# Always write start script even when optional downloads failed (CORE_OK_WITH_GAPS = exit 0 above).
# Only hard-fail the whole setup when validation exit 1 (missing/corrupt core).

cat > "$BASE/start_comfy.sh" <<EOS
#!/usr/bin/env bash
BASE="\${BASE_DIR:-$BASE}"
# Colab puts NVIDIA userspace under /usr/lib64-nvidia; without it torch.cuda and nvidia-smi fail in a fresh shell.
export LD_LIBRARY_PATH="/usr/lib64-nvidia\${LD_LIBRARY_PATH:+:\$LD_LIBRARY_PATH}"
if [ -x /opt/conda/bin/python ]; then export PATH="/opt/conda/bin:\$PATH"; PYBIN=/opt/conda/bin/python; else PYBIN="\$BASE/venv/bin/python"; fi
cd "\$BASE/ComfyUI"
pkill -f "python main.py" 2>/dev/null; sleep 2
setsid nohup "\$PYBIN" main.py --listen ${LISTEN} --port 8188 > "\$BASE/comfyui.log" 2>&1 < /dev/null & disown
echo "started on ${LISTEN}:8188"
EOS
chmod +x "$BASE/start_comfy.sh"
if [ "$VALIDATE" -ne 0 ]; then
  echo "SETUP_VALIDATE_FAILED — not starting Comfy (core models missing/corrupt)"
  exit 1
fi
if [ "$START" = 1 ]; then
  bash "$BASE/start_comfy.sh"
  echo "== waiting for ComfyUI /system_stats on ${LISTEN}:8188"
  ready=
  for _ in $(seq 1 60); do
    if curl -sf -m 3 "http://${LISTEN}:8188/system_stats" >/dev/null; then ready=1; break; fi
    sleep 3
  done
  if [ -n "$ready" ]; then
    echo "COMFY_READY on ${LISTEN}:8188"
  else
    echo "WARN: Comfy process started but /system_stats not ready after ~3 min — check $BASE/comfyui.log"
  fi
fi

# CoachBate 2.3 must be present for phone I2V; fail the marker only if START ran and it is still missing.
if [ "$CONTENT_LORAS" = "1" ] && [ ! -s "models/loras/$COACH_NAME" ]; then
  echo "WARN: missing $COACH_NAME — stage it on the relay (wan/lora_stage) so phone setup can upload it"
fi

echo "SETUP_COMPLETE TRANSFORMER=$TRANSFORMER GEMMA=$GEMMA SEX_LORA=$SEX_LORA CONTENT_LORAS=$CONTENT_LORAS"
echo "Load the official LTX-2.5 I2V template, swap the transformer, LoRA 450 at 0.8 (Stubelius only), content LoRAs at 0.35-0.85,"
echo "enhancer off, Conv VAE, 768x512, 49 frames, 10-12 steps, CFG 1. Keepers: 97 frames + DiffVAE."
