#!/usr/bin/env python3
"""Manual LoRA weight-merge smoke for Wan2.2 TI2V-5B Diffusers (no PEFT)."""
from __future__ import annotations

import glob
import inspect
import logging
import os
import sys
import time
import traceback

def fix_cuda_env() -> None:
    # Never inherit cv2's lib64 — it breaks cuDNN on Blackwell via SSH.
    only = [
        "/usr/lib64-nvidia",
        "/usr/local/cuda/lib64",
        "/usr/local/cuda/targets/x86_64-linux/lib",
        "/usr/lib/x86_64-linux-gnu",
    ]
    os.environ["LD_LIBRARY_PATH"] = ":".join([p for p in only if os.path.isdir(p)])
    os.environ.setdefault("CUDA_DEVICE_ORDER", "PCI_BUS_ID")
    os.environ["TORCH_CUDNN_V8_API_DISABLED"] = "1"


fix_cuda_env()
os.environ["TORCH_CUDNN_V8_API_DISABLED"] = "1"
os.environ["TOKENIZERS_PARALLELISM"] = "false"

OUT = "/content/outputs/colab_g4_ti2v5b_smoke"
os.makedirs(OUT, exist_ok=True)
SEED = int(os.environ.get("SMOKE_SEED", os.environ.get("SEED", "9009")))
TAG = os.environ.get("SMOKE_TAG", os.environ.get("TAG", f"i2v_iso_seed{SEED}"))
LOG = f"{OUT}/smoke_{TAG}.log"
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(message)s",
    handlers=[logging.StreamHandler(sys.stdout), logging.FileHandler(LOG)],
)
log = logging.info

import torch
torch.backends.cudnn.enabled = False
torch.backends.cuda.matmul.allow_tf32 = True
from safetensors.torch import load_file
from diffusers import WanImageToVideoPipeline, UniPCMultistepScheduler
from diffusers.utils import export_to_video

# Defaults; override via env for mix packs (1080p-ish = 1920x1088, length bump = 97f)
WIDTH = int(os.environ.get("WIDTH", "1280"))
HEIGHT = int(os.environ.get("HEIGHT", "704"))
FRAMES = int(os.environ.get("FRAMES", "81"))
STEPS = int(os.environ.get("STEPS", "28"))
CFG = float(os.environ.get("CFG", "4.5"))
SHIFT = float(os.environ.get("SHIFT", "5.0"))

PROMPT_PATH = os.environ.get("PROMPT_PATH", "/content/smoke_prompt_i2v.txt")
with open(PROMPT_PATH, "r", encoding="utf-8") as f:
    PROMPT = f.read().strip()
log(f"prompt_chars={len(PROMPT)} from {PROMPT_PATH}")
from PIL import Image
START = os.environ.get("START_IMG", "/content/start_i2v.png")
if not os.path.isfile(START):
    raise SystemExit(f"FATAL: missing start image {START}")
start_img = Image.open(START).convert("RGB")
# resize to target later per W,H
log(f"I2V start={START} size={start_img.size}")


_neg_mode = os.environ.get("NEG_MODE", "stroke").strip().lower()
_expose_neg = "overexposed, blown highlights, clipped whites, harsh contrast, crushed blacks, HDR look, specular blowout, plastic shiny overlit skin, hard spotlight, high contrast grading"
if _neg_mode == "action":
    NEG = (
        "detached penis, floating genitals, disembodied penis, hand through penis, intersecting anatomy, "
        "extra limbs, fused bodies, missing limbs, child, underage, watermark, text, cartoon, anime, "
        "3D render, blurry, deformed, grainy, film grain, heavy grain, noisy, speckles, "
        "compression artifacts, low quality, muddy, camera shake, whip pan, " + _expose_neg
    )
else:
    NEG = (
        "no body bobbing, no vertical bouncing, no swaying, no rocking, no torso movement, no shoulder movement, "
        "no hip movement, no weight shifting, no head nodding, no idle body motion, detached penis, floating genitals, "
        "disembodied penis, hand through penis, intersecting anatomy, bounce, hip thrust, blurry, deformed, extra limbs, "
        "child, underage, watermark, text, cartoon, anime, grainy, film grain, heavy grain, noisy, speckles, "
        "compression artifacts, low quality, muddy, " + _expose_neg
    )
if os.environ.get("NEG_EXTRA"):
    NEG = NEG + ", " + os.environ["NEG_EXTRA"]
def find_base() -> str:
    for p in (
        "/content/Wan2.2-TI2V-5B-Turbo",
        "/content/Wan2.2-TI2V-5B",
        "/content/Wan2.2-TI2V-5B-Diffusers",
        "/content/Wan2.2-TI2V-5B-Turbo",
    ):
        if os.path.isfile(os.path.join(p, "model_index.json")):
            return p
    raise SystemExit("FATAL: no Diffusers TI2V-5B base with model_index.json")


def find_lora(patterns: list[str]) -> str | None:
    hits: list[str] = []
    for root in ("/content/loras_5b", "/content"):
        for pat in patterns:
            hits += glob.glob(os.path.join(root, "**", pat), recursive=True)
            hits += glob.glob(os.path.join(root, pat))
    hits = [h for h in hits if h.endswith(".safetensors") and os.path.isfile(h)]
    hits.sort(key=lambda p: (0 if "/loras_5b/" in p else 1, len(p)))
    return hits[0] if hits else None


def strip_prefix(k: str) -> str:
    for p in ("diffusion_model.", "transformer.", "model.", "lora_unet_"):
        if k.startswith(p):
            k = k[len(p):]
    return k


def classify_lora_key(sk: str):
    for a_sfx, b_sfx in (
        (".lora_A.weight", ".lora_B.weight"),
        (".lora_down.weight", ".lora_up.weight"),
    ):
        if sk.endswith(a_sfx):
            return sk[: -len(a_sfx)], "A"
        if sk.endswith(b_sfx):
            return sk[: -len(b_sfx)], "B"
    if sk.endswith(".alpha") or sk.endswith(".lora_alpha"):
        base = sk.rsplit(".", 1)[0]
        for sfx in (".lora_A", ".lora_B", ".lora_down", ".lora_up"):
            if base.endswith(sfx):
                base = base[: -len(sfx)]
                break
        return base, "alpha"
    return None, None


def remap_candidates(base: str) -> list[str]:
    raw = base if base.endswith(".weight") else base + ".weight"
    cands = [raw]
    repls = [
        ("self_attn.q", "attn1.to_q"),
        ("self_attn.k", "attn1.to_k"),
        ("self_attn.v", "attn1.to_v"),
        ("self_attn.o", "attn1.to_out.0"),
        ("cross_attn.q", "attn2.to_q"),
        ("cross_attn.k", "attn2.to_k"),
        ("cross_attn.v", "attn2.to_v"),
        ("cross_attn.o", "attn2.to_out.0"),
        ("ffn.0", "ffn.net.0.proj"),
        ("ffn.2", "ffn.net.2"),
    ]
    for a, b in repls:
        if a in raw:
            cands.append(raw.replace(a, b))
    return list(dict.fromkeys(cands))


def merge_lora(transformer, lora_path: str, scale: float) -> int:
    lora = load_file(lora_path)
    pairs: dict = {}
    for k, v in lora.items():
        sk = strip_prefix(k)
        base, kind = classify_lora_key(sk)
        if base is None:
            continue
        slot = pairs.setdefault(base, {})
        slot[kind] = v

    name_to_param = dict(transformer.named_parameters())
    merged = 0
    skipped: list[str] = []
    with torch.no_grad():
        for base, ab in pairs.items():
            if "A" not in ab or "B" not in ab:
                continue
            wkey = None
            for c in remap_candidates(base):
                if c in name_to_param:
                    wkey = c
                    break
            if wkey is None:
                skipped.append(base)
                continue
            A = ab["A"].float()
            B = ab["B"].float()
            if A.ndim != 2 or B.ndim != 2:
                skipped.append(base + ":ndim")
                continue
            if B.shape[1] == A.shape[0]:
                delta = B @ A
            elif A.shape[1] == B.shape[0]:
                delta = A @ B
            else:
                skipped.append(f"{base}:matmul {tuple(A.shape)}x{tuple(B.shape)}")
                continue
            rank = min(A.shape)
            if "alpha" in ab:
                alpha_t = ab["alpha"]
                alpha = float(alpha_t.item() if hasattr(alpha_t, "item") else alpha_t)
                scale_eff = scale * (alpha / max(rank, 1))
            else:
                scale_eff = scale
            param = name_to_param[wkey]
            d = delta.to(device=param.device, dtype=torch.float32)
            if d.shape != param.shape:
                if d.T.shape == param.shape:
                    d = d.T
                else:
                    skipped.append(f"{base}:shape {tuple(d.shape)}!={tuple(param.shape)}")
                    continue
            param.add_(d.to(dtype=param.dtype) * scale_eff)
            merged += 1
    log(f"MERGE {os.path.basename(lora_path)} scale={scale} layers={merged} skipped={len(skipped)}")
    if merged == 0:
        log(f"  sample skipped: {skipped[:12]}")
        log(f"  sample t params: {list(name_to_param.keys())[:12]}")
        log(f"  sample lora bases: {list(pairs.keys())[:8]}")
    return merged


def ensure_cuda() -> None:
    log(f"cuda_avail={torch.cuda.is_available()} LD={os.environ.get('LD_LIBRARY_PATH','')[:240]}")
    if torch.cuda.is_available():
        return
    import ctypes
    for lib in (
        "/usr/lib/x86_64-linux-gnu/libcuda.so.1",
        "/usr/local/nvidia/lib64/libcuda.so.1",
        "/usr/lib64/libcuda.so.1",
    ):
        if os.path.exists(lib):
            try:
                ctypes.CDLL(lib)
                log(f"preloaded {lib}")
            except Exception as e:
                log(f"preload fail {lib}: {e}")
    try:
        torch.zeros(1, device="cuda")
        log("cuda tensor ok after preload")
    except Exception as e:
        log(f"FATAL CUDA: {e}")
        sys.exit(3)


ensure_cuda()
log(f"gpu={torch.cuda.get_device_name(0)}")

BASE = find_base()
# Mix-match via env:
#   LORA1 / LORA1_SCALE (required unless empty skip)
#   LORA2 / LORA2_SCALE
#   LORA3 / LORA3_SCALE (optional)
# Back-compat: DR34_SCALE+MAST_SCALE+MAST_LORA still work if LORA1 unset.
def _env_lora(key_name: str, key_scale: str, default_name: str, default_scale: float):
    name = os.environ.get(key_name, default_name).strip()
    if not name or name.upper() in ("NONE", "-", "OFF"):
        return None, 0.0
    scale = float(os.environ.get(key_scale, str(default_scale)))
    path = find_lora([name, f"*{name}*"])
    return path, scale

if os.environ.get("LORA1"):
    pairs = []
    for i in (1, 2, 3):
        p, s = _env_lora(f"LORA{i}", f"LORA{i}_SCALE", "NONE", 0.0)
        if p and s != 0:
            pairs.append((p, s))
else:
    # legacy mast path
    dr34, s1 = _env_lora("LORA1", "DR34_SCALE", "DR34ML4Y_TI2V_5B_V1.safetensors", float(os.environ.get("DR34_SCALE", "0.55")))
    mast_name = os.environ.get("MAST_LORA", "Wan2.2-TI2V-5B-mast-768px-73f-r32.safetensors")
    mast, s2 = _env_lora("LORA2", "MAST_SCALE", mast_name, float(os.environ.get("MAST_SCALE", "0.60")))
    pairs = [(p, s) for p, s in ((dr34, s1), (mast, s2)) if p]

log(f"BASE={BASE}")
for i, (p, s) in enumerate(pairs, 1):
    log(f"LORA{i}={p} scale={s}")
if not pairs:
    log("FATAL: no LoRAs configured")
    sys.exit(2)

t0 = time.time()
# TI2V-5B I2V path: WanImageToVideoPipeline (expand_timesteps + VAE first-frame),
# even when model_index.json says WanPipeline.
pipe = WanImageToVideoPipeline.from_pretrained(BASE, torch_dtype=torch.bfloat16)
pipe.scheduler = UniPCMultistepScheduler.from_config(
    pipe.scheduler.config, flow_shift=SHIFT
)
pipe.to("cuda")
log(f"pipeline loaded in {time.time() - t0:.1f}s class={type(pipe).__name__} expand_timesteps={getattr(pipe.config, 'expand_timesteps', None)}")

layer_counts = []
total = 0
for p, s in pairs:
    n = merge_lora(pipe.transformer, p, s)
    layer_counts.append((os.path.basename(p), s, n))
    total += n
log(f"total_merged_layers={total} detail={layer_counts}")
if total == 0:
    log("FATAL: zero LoRA layers merged")
    sys.exit(4)
m1 = layer_counts[0][2] if layer_counts else 0
m2 = layer_counts[1][2] if len(layer_counts) > 1 else 0

frames = None
used_wh = None
last_err = None
size_try = [(WIDTH, HEIGHT)]
# fallbacks if primary fails (OOM / invalid)
for fb in ((1920, 1088), (1280, 704), (704, 1280)):
    if fb not in size_try:
        size_try.append(fb)
for W, H in size_try:
    try:
        log(f"try generate {W}x{H} f={FRAMES} steps={STEPS} cfg={CFG} shift={SHIFT} seed={SEED}")
        gen = torch.Generator(device="cuda").manual_seed(SEED)
        sig = inspect.signature(pipe.__call__)
        img = start_img.resize((W, H))
        if "image" not in sig.parameters:
            log(f"FATAL: no image= on {type(pipe).__name__}")
            raise SystemExit(6)
        kwargs = dict(
            image=img,
            prompt=PROMPT,
            negative_prompt=NEG,
            height=H,
            width=W,
            num_frames=FRAMES,
            num_inference_steps=STEPS,
            guidance_scale=CFG,
            generator=gen,
        )
        log("I2V image= passed")
        if "flow_shift" in sig.parameters:
            kwargs["flow_shift"] = SHIFT
        elif "shift" in sig.parameters:
            kwargs["shift"] = SHIFT
        t1 = time.time()
        out = pipe(**kwargs)
        log(f"generate ok elapsed={time.time() - t1:.1f}s size={W}x{H}")
        frames = out.frames[0]
        used_wh = (W, H)
        break
    except SystemExit:
        raise
    except Exception as e:
        last_err = e
        log(f"generate fail {W}x{H}: {e}")
        traceback.print_exc()

if frames is None:
    log(f"FATAL generate: {last_err}")
    sys.exit(5)

W, H = used_wh
out_mp4 = f"{OUT}/smoke_{TAG}_{W}x{H}_f{FRAMES}_s{STEPS}.mp4"
export_to_video(frames, out_mp4, fps=16)
sz = os.path.getsize(out_mp4)
open(f"{OUT}/DONE_{TAG}.txt", "w").write(
    f"{out_mp4}\n{sz}\n{SEED}\ndr34_layers={m1}\nlora_layers={layer_counts}\nmethod=i2v_mix_match\n"
)
log(f"DONE {out_mp4} bytes={sz} seed={SEED} layers={total}")
print(f"SMOKE_OK {out_mp4} {sz} layers={total} seed={SEED}")
