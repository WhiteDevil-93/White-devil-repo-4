#!/usr/bin/env bash
# One-shot setup for any Linux GPU box (RunPod, Vast.ai, Lambda, Paperspace, your own server).
# Usage (from the wanbot folder):
#   OPENROUTER_API_KEY=sk-or-... WANBOT_TOKEN=choose-a-secret bash scripts/setup_linux.sh
# Env options:
#   INSTALL_WAN22_5B=1    install Wan2.2 + TI2V-5B weights (default 1)
#   INSTALL_WAN22_14B=0   install Wan2.2 T2V-A14B + I2V-A14B weights (default 0, needs ~40GB VRAM
#                         or ~24GB with offload; enables wan22-14b-t2v / wan22-14b-i2v models)
#   DATA_DIR=./data      where Wan2.2, weights, work/outputs/inbox live
#   PORT=8000
#   TUNNEL=1             expose via Cloudflare quick tunnel (use 0 if the provider exposes the port)
#   START=1              start the runner at the end
set -euo pipefail
cd "$(dirname "$0")/.."
WANBOT_DIR="$(pwd)"
DATA_DIR="$(mkdir -p "${DATA_DIR:-./data}" && cd "${DATA_DIR:-./data}" && pwd)"
PORT="${PORT:-8000}"
INSTALL_WAN22_5B="${INSTALL_WAN22_5B:-1}"
INSTALL_WAN22_14B="${INSTALL_WAN22_14B:-0}"
TUNNEL="${TUNNEL:-1}"
START="${START:-1}"
PY="${PYTHON:-python3}"

if ! command -v ffmpeg >/dev/null; then
  (command -v apt-get >/dev/null && { sudo -n true 2>/dev/null && SUDO=sudo || SUDO=""; $SUDO apt-get update -qq && $SUDO apt-get install -y -qq ffmpeg git; }) \
    || { echo "Install ffmpeg manually"; exit 1; }
fi

$PY -m pip install -q -r requirements.txt

if [ "$INSTALL_WAN22_5B" = "1" ]; then
  [ -d "$DATA_DIR/Wan2.2" ] || git clone -q https://github.com/Wan-Video/Wan2.2.git "$DATA_DIR/Wan2.2"
  grep -v -i flash_attn "$DATA_DIR/Wan2.2/requirements.txt" > /tmp/wan_req.txt
  $PY -m pip install -q -r /tmp/wan_req.txt
  $PY -m pip install -q flash_attn --no-build-isolation || echo "flash_attn unavailable - continuing"
  $PY - <<PYEOF
from huggingface_hub import snapshot_download
snapshot_download("Wan-AI/Wan2.2-TI2V-5B", local_dir="$DATA_DIR/models/Wan2.2-TI2V-5B")
PYEOF
fi

if [ "$INSTALL_WAN22_14B" = "1" ]; then
  [ -d "$DATA_DIR/Wan2.2" ] || git clone -q https://github.com/Wan-Video/Wan2.2.git "$DATA_DIR/Wan2.2"
  grep -v -i flash_attn "$DATA_DIR/Wan2.2/requirements.txt" > /tmp/wan_req.txt
  $PY -m pip install -q -r /tmp/wan_req.txt
  $PY -m pip install -q flash_attn --no-build-isolation || echo "flash_attn unavailable - continuing"
  $PY - <<PYEOF
from huggingface_hub import snapshot_download
snapshot_download("Wan-AI/Wan2.2-T2V-A14B", local_dir="$DATA_DIR/models/Wan2.2-T2V-A14B")
snapshot_download("Wan-AI/Wan2.2-I2V-A14B", local_dir="$DATA_DIR/models/Wan2.2-I2V-A14B")
PYEOF
fi

mkdir -p "$DATA_DIR/work" "$DATA_DIR/outputs" "$DATA_DIR/inbox"
if [ ! -f config.yaml ]; then
  $PY - <<PYEOF
import yaml
d = "$DATA_DIR"
common = {"fps": 24, "sizes": ["1280*704", "704*1280"], "max_frames": 121, "frame_multiple": 4, "frame_offset": 1, "supports": ["t2v", "i2v"]}
common14b = {"fps": 16, "sizes": ["1280*720", "720*1280"], "max_frames": 121, "frame_multiple": 4, "frame_offset": 1}
cfg = {
  "server": {"host": "0.0.0.0", "port": int("$PORT"), "token": "\${WANBOT_TOKEN}", "cors_origins": ["*"]},
  "paths": {"work_dir": f"{d}/work", "outputs_dir": f"{d}/outputs", "inbox_dir": f"{d}/inbox"},
  "openrouter": {"api_key": "\${OPENROUTER_API_KEY}", "model": "cognitivecomputations/dolphin-mistral-24b-venice-edition", "temperature": 0.7},
  "defaults": {"seed": 42, "steps": 50, "guide_scale": 5.0, "shift": 5.0},
  "default_model": "wan22-5b",
  "models": {
    "wan22-5b": {"type": "wan22", "repo_dir": f"{d}/Wan2.2", "ckpt_dir": f"{d}/models/Wan2.2-TI2V-5B", "task": "ti2v-5B",
                 "class": "WanTI2V", "t5_cpu": True, "convert_model_dtype": True, "offload_model": True, "fallback": "wan22-5b-cli", **common},
    "wan22-5b-cli": {"type": "cli", "cwd": f"{d}/Wan2.2",
                     "command": ["python3", "generate.py", "--task", "ti2v-5B", "--size", "{width}*{height}", "--frame_num", "{frames}",
                                 "--ckpt_dir", f"{d}/models/Wan2.2-TI2V-5B", "--offload_model", "True", "--convert_model_dtype", "--t5_cpu",
                                 "--base_seed", "{seed}", "--sample_steps", "{steps}", "--sample_guide_scale", "{guide_scale}",
                                 "--sample_shift", "{shift}", "--save_file", "{output}", "--prompt", "{prompt}"],
                     "image_args": ["--image", "{image}"], **common},
    "wan22-14b-t2v": {"type": "wan22", "repo_dir": f"{d}/Wan2.2", "ckpt_dir": f"{d}/models/Wan2.2-T2V-A14B", "task": "t2v-A14B",
                 "class": "WanT2V", "t5_cpu": True, "convert_model_dtype": True, "offload_model": True,
                 "fallback": "wan22-14b-t2v-cli", "supports": ["t2v"], **common14b},
    "wan22-14b-t2v-cli": {"type": "cli", "cwd": f"{d}/Wan2.2",
                     "command": ["python3", "generate.py", "--task", "t2v-A14B", "--size", "{width}*{height}", "--frame_num", "{frames}",
                                 "--ckpt_dir", f"{d}/models/Wan2.2-T2V-A14B", "--offload_model", "True", "--convert_model_dtype", "--t5_cpu",
                                 "--base_seed", "{seed}", "--sample_steps", "{steps}", "--sample_guide_scale", "{guide_scale}",
                                 "--sample_shift", "{shift}", "--save_file", "{output}", "--prompt", "{prompt}"],
                     "supports": ["t2v"], **common14b},
    "wan22-14b-i2v": {"type": "wan22", "repo_dir": f"{d}/Wan2.2", "ckpt_dir": f"{d}/models/Wan2.2-I2V-A14B", "task": "i2v-A14B",
                 "class": "WanI2V", "t5_cpu": True, "convert_model_dtype": True, "offload_model": True,
                 "fallback": "wan22-14b-i2v-cli", "supports": ["i2v"], **common14b},
    "wan22-14b-i2v-cli": {"type": "cli", "cwd": f"{d}/Wan2.2",
                     "command": ["python3", "generate.py", "--task", "i2v-A14B", "--size", "{width}*{height}", "--frame_num", "{frames}",
                                 "--ckpt_dir", f"{d}/models/Wan2.2-I2V-A14B", "--offload_model", "True", "--convert_model_dtype", "--t5_cpu",
                                 "--base_seed", "{seed}", "--sample_steps", "{steps}", "--sample_guide_scale", "{guide_scale}",
                                 "--sample_shift", "{shift}", "--save_file", "{output}", "--prompt", "{prompt}"],
                     "image_args": ["--image", "{image}"], "supports": ["i2v"], **common14b},
    "test": {"type": "python", "module": "$WANBOT_DIR/examples/test_backend.py", "class": "TestPatternBackend", "fps": 24,
             "max_frames": 121, "frame_multiple": 4, "frame_offset": 1},
  },
}
yaml.safe_dump(cfg, open("config.yaml", "w"), sort_keys=False)
print("wrote config.yaml")
PYEOF
fi

if [ "$START" = "1" ]; then
  ARGS=(--config config.yaml)
  [ "$TUNNEL" = "1" ] && ARGS+=(--tunnel)
  exec $PY -m wanbot.server "${ARGS[@]}"
fi
