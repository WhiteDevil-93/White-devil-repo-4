#!/bin/bash
# Fresh-VM restore: models + LoRAs + deps, then resume goon chain (existing clips are skipped).
LOG=/content/outputs/colab_g4_ti2v5b_smoke/restore_resume.log
mkdir -p /content/outputs/colab_g4_ti2v5b_smoke/gooning
exec >> "$LOG" 2>&1
echo "RESTORE_START $(date -u)"
pip -q install -U diffusers ftfy imageio imageio-ffmpeg accelerate
python3 -c "import diffusers, ftfy, cv2; print('deps', diffusers.__version__, cv2.__version__)"
bash /content/rehydrate_g4.sh
for f in /content/loras_5b/DR34ML4Y_TI2V_5B_V1.safetensors /content/loras_5b/PENISLORA_WAN22_5B_e349.safetensors /content/Wan2.2-TI2V-5B-Turbo/model_index.json; do
  [[ -s "$f" ]] || { echo "RESTORE_FATAL missing $f"; exit 2; }
done
echo "RESTORE_OK $(date -u) -> goon chain packs ${PACKS:-2 3 4 5}"
PACKS="${PACKS:-2 3 4 5}" REFRESH_EVERY=6 bash /content/run_gooning_chain_colab.sh
