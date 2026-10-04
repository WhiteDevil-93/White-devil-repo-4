"""LTX-2.5 image-to-video on the Colab ComfyUI (reached through wan-colab-comfy-tunnel on 127.0.0.1:18288).

One still + one prompt -> one clip in ~/wan/renders (so it shows in Renders). The graph follows the kit notes:
Stubelius + distilled LoRA 450 @0.8 + one sex LoRA @0.7, LTX Gemma 4, CFG 1, distilled 8-step sigmas, no enhancer.
Jobs are files in ~/hub/ltx_jobs; a thread per job polls ComfyUI and downloads the result.
"""
import json
import os
import random
import re
import secrets
import shutil
import subprocess
import threading
import time
import uuid
from pathlib import Path
from typing import Optional

import requests
from fastapi import APIRouter, File, Form, HTTPException, UploadFile
from fastapi.responses import FileResponse
from pydantic import BaseModel

from gen import KEY as OR_KEY, plain_words

router = APIRouter(prefix="/api/ltx")
COMFY = "http://127.0.0.1:18288"
HUB = Path(__file__).resolve().parent
JOBS = HUB / "ltx_jobs"
JOBS.mkdir(exist_ok=True)
RENDERS = Path.home() / "wan" / "renders"
JOB_ID = re.compile(r"^[0-9a-f]{12}$")
SIGMAS = "1.0, 0.99375, 0.9875, 0.98125, 0.975, 0.909375, 0.725, 0.421875, 0.0"
NEGATIVE = ("cartoon, anime, cgi, video game, illustration, concept art, digital painting, painting, drawing, "
            "fantasy art, comic, 3d render, airbrushed, plastic skin, waxy skin, ugly, deformed, blurry, low quality, watermark, text, subtitles, "
            "woman, women, female, girl, lady, breasts, boobs, tits, cleavage, vagina, vulva, labia, pussy, clitoris, "
            "feminine body, soft chest, bouncing breasts, curvy, futa, shemale, girlfriend, wife, her face, she moans, "
            "labial folds, vaginal slit, feminine hands, long nails, manicure, androgynous torso, soft feminine hips, "
            "gender swap, female anatomy, genital drift, morphing hands, morphing genitals, fused fingers, extra limbs, "
            "overexposed, blown highlights, clipped whites, washed out, hot speculars, glare, blown whites, "
            "vibration, jitter, buzzing motion, audio hum, synthetic hum, feminine nails, manicure")
SIZES = {"landscape": (768, 512), "portrait": (512, 768), "square": (640, 640)}
FRAMES = (49, 73, 97, 121, 193, 241)
TAIL = 6
_threads = {}


def comfy(method, path, **kw):
    try:
        r = requests.request(method, COMFY + path, timeout=kw.pop("timeout", 30), **kw)
    except requests.RequestException:
        raise HTTPException(503, "ComfyUI on Colab isn't reachable. Start the Colab runtime, then give the tunnel a minute.")
    if r.status_code >= 400:
        raise HTTPException(502, f"ComfyUI said: {r.text[:300]}")
    return r


def choices(node, field):
    spec = comfy("GET", f"/object_info/{node}").json()[node]["input"]["required"][field]
    return spec[1].get("options", []) if spec[0] == "COMBO" else spec[0]


def has_distill_built_in(unet):
    """The distilled checkpoint and Stubelius beta 2 (distilled LoRA merged in, per its model card) must not get
    LoRA 450 on top: that applies the distillation twice."""
    u = (unet or "").lower()
    return "distilled" in u or "beta2" in u


def pick(options, *prefer):
    for p in prefer:
        for o in options:
            if p in o:
                return o
    return options[0] if options else None


WRITER_CHOICES = {"x-ai/grok-4.5": "Grok 4.5 uncensored", "x-ai/grok-4.20": "Grok 4.20 (fast)", "x-ai/grok-4.7": "Grok 4.7",
                  "x-ai/grok-4.3": "Grok 4.3", "mistralai/mistral-medium-3.1": "Mistral Medium 3.1 (fast, cheap)",
                  "mistralai/mistral-small-3.2-24b-instruct": "Mistral Small 3.2 (cheapest)",
                  "qwen/qwen3-vl-235b-a22b-instruct": "Qwen3 VL 235B", "meta-llama/llama-4-maverick": "Llama 4 Maverick",
                  "moonshotai/kimi-k2.6": "Kimi K2.6 (slow)"}
MODEL_ID = re.compile(r"^~?[\w.-]+/[\w.:-]+$")


def is_ic_lora(name):
    return "ic-lora" in str(name or "").lower()


def norm_opts(o):
    """Model choices: {transformer, loras (max 4 content adapters), distill, vae, clip, writer, i2v}.
    Distilled 450 is separate (default 1.0). Content stack is anatomy then motion; camera/lighting only if chosen."""
    loras = []
    # Official IC-LoRAs (ltx-2.5-22b-ic-lora-*) need their own reference/control workflow at strength 1.0; loaded
    # as an ordinary content LoRA at 0.65 they only distort the render.
    for it in [x for x in (o.get("loras") or []) if not is_ic_lora(x[0] if isinstance(x, (list, tuple)) and x else "")][:4]:
        try:
            name, s = str(it[0] or ""), float(it[1])
        except (TypeError, ValueError, IndexError):
            continue
        if name:
            loras.append([name, max(-1.0, min(1.5, s))])
    try:
        distill = max(0.0, min(1.2, float(o.get("distill", 1.0))))
    except (TypeError, ValueError):
        distill = 1.0
    try:
        i2v = max(0.25, min(1.0, float(o.get("i2v", 0.55))))
    except (TypeError, ValueError):
        i2v = 0.55
    return {"transformer": o.get("transformer") or None, "loras": loras, "distill": distill, "i2v": i2v,
            "prompt_style": o.get("prompt_style") if o.get("prompt_style") in PROMPT_STYLES else "auto",
            "two_stage": o.get("two_stage") is not False,
            "vae": "fast" if o.get("vae") == "fast" else "quality", "clip": o.get("clip") or None,
            "writer": o.get("writer") if MODEL_ID.match(str(o.get("writer") or "")) else None}


def parse_opts(raw, fallback):
    if not raw:
        return norm_opts(fallback)
    try:
        return norm_opts(json.loads(raw))
    except (ValueError, AttributeError):
        raise HTTPException(400, "The model settings couldn't be read; reload the page.")


def job_opts(job):
    return job.get("opts") or norm_opts({"transformer": job.get("transformer"), "loras": [[job["sex_lora"],
                                         job.get("sex_strength", 0.7)]] if job.get("sex_lora") else []})


def _colab_billing_note():
    try:
        from colab import usage, session_info, billing_active, status_summary
        u, sess = usage(), session_info()
        if billing_active(u, sess):
            rate = u.get("rate_per_hr")
            rate_s = f"${rate:.2f}/h" if isinstance(rate, (int, float)) else "credits/h"
            return True, f"Colab G4 is BILLING ({rate_s}) but ComfyUI tunnel is down. Stop the runtime on the Colab screen to stop charges, or Start/restart / wait for the tunnel."
        return False, None
    except Exception:
        return False, None


@router.get("/status")
def status():
    try:
        unets = choices("UNETLoader", "unet_name")
        loras = choices("LoraLoaderModelOnly", "lora_name")
        vaes = choices("VAELoader", "vae_name")
        clips = choices("CLIPLoader", "clip_name")
        q = comfy("GET", "/queue").json()
    except HTTPException as e:
        billing, note = _colab_billing_note()
        detail = note or e.detail
        return {"online": False, "billing": billing, "detail": detail, "installs": INSTALLS}
    ltx = [u for u in unets if "ltx" in u.lower()]
    return {"online": bool(ltx), "billing": True, "detail": None if ltx else "ComfyUI is up but has no LTX-2.5 model; install the LTX kit from Setup.",
            "transformers": ltx, "loras": [l for l in loras if "distilled-lora" not in l.lower() and not is_ic_lora(l)
                                          and ( "ltx" in l.lower()
                                                or l in ("CGS23.safetensors", "Defined_Muscle.safetensors",
                                                         "plora_sulfter_i2v-step00008500.comfy.safetensors",
                                                         "cumsplash_LTX2_v1.safetensors",
                                                         "solo-male-ltx2-4000.safetensors",
                                                         "LTX2-i2v-SexThrust.safetensors"))],
            "distilled": any("distilled-lora" in l for l in loras),
            "decoders": {"quality": any("video-vae" in v and "conv" not in v for v in vaes), "fast": any("video-vae-conv" in v for v in vaes)},
            "clips": [c for c in clips if "gemma" in c.lower() or "ltx" in c.lower()] or clips,
            "writers": [[k, v] for k, v in WRITER_CHOICES.items()], "installs": INSTALLS,
            "triggers": {l: trigger(l) for l in loras if trigger(l)},
            "busy": len(q.get("queue_running", [])) + len(q.get("queue_pending", [])), "frames": FRAMES,
            "sizes": list(SIZES)}


TRIGGERS = {"ltx_BEANFLK_V1.safetensors": "BEANFLK", "ltx_gay-sex-sulphur-10eros.safetensors": "g@ys3x",
            "ltx-2-19b-bwc-lora-35000.safetensors": "bwc", "ltx-2.5_penis_coachbate_preview1.safetensors": "p3n15", "penis-lora-by-coachbate-ltx-2.3.safetensors": "p3n15",
            "ltx-2.5_uncut_penis_coachbate_v1.safetensors": "CBUNCT", "cumsplash_LTX2_v1.safetensors": "cumsplash",
            "ltx_plora_sulfter_i2v-step00008500.comfy.safetensors": "PENISLORA",
            "plora_sulfter_i2v-step00008500.comfy.safetensors": "PENISLORA",
            "ltx_sexgod_nudity_v2_LTXNUDES.safetensors": "LTXNUDES"}


def trigger(name):
    return (INSTALLS.get(name) or {}).get("trigger") or TRIGGERS.get(name, "")


def with_triggers(prompt, opts):
    words = [trigger(n) for n, s in opts["loras"] if s > 0]
    words = [w for w in dict.fromkeys(words) if w and w.lower() not in prompt.lower()]
    return (", ".join(words) + ". " + prompt) if words else prompt


def gay_reinforce(prompt, opts=None):
    """Stamp gay-male framing onto every male-only render so the model doesn't drift female/hetero."""
    opts = opts or {}
    prompt = re.sub(r"\b(?:throbbing|twitching|shuddering|vibrating|pulsing|trembling|shaking|"
                    r"pulses?|twitches|shudders|trembles|vibrates)\b", "tensing", prompt, flags=re.I)
    if not men_only(prompt) and not any("gay-sex" in n or "sulphur" in n for n, s in (opts.get("loras") or []) if s > 0):
        return prompt
    prompt = fix_men(prompt)
    lower = prompt.lower()
    sex = bool(re.search(r"\b(sex|fuck|thrust|penetrat|anal|blowjob|suck|rides?|riding|kiss|stroke|strokes|stroking|"
                         r"handjob|masturbat|jerk|cum|orgasm|edging|goon)\w*", lower))
    if sex:
        stamp = ("g@ys3x, gay male sex, adult men only, flat muscular male pecs, defined male fingers with short nails, "
                 "stable thick erect penis, heavy testicles, tight male anus and perineum, hips and legs keep moving, "
                 "no breasts no vagina no labia no gender swap, smooth deliberate motion without jitter")
    else:
        # A solo scene that is not sex (a pose, a transformation): keep it male, but do not inject sex acts,
        # an erection, hip motion or a lighting style the scene did not ask for (job 8c09dff6c180).
        stamp = ("adult man, male anatomy only, flat male chest, defined male fingers with short nails, "
                 "no breasts no vagina no labia no gender swap, smooth deliberate motion without jitter")
    if "soft diffused" not in lower and not re.search(r"\b(red|orange|lava|fire|neon|dark|night|glow)\b", lower):
        stamp += ", soft diffused light with controlled highlights, no blowout"
    if "male anus and perineum" in lower or lower.startswith(("g@ys3x", "adult man, male anatomy only")):
        return prompt
    return stamp + ". " + prompt


STAGE2_SIGMAS = "0.909375, 0.725, 0.421875, 0.0"


def graph(image, prompt, frames, width, height, seed, opts, prefix, compression=18, tail=False, two_stage=None):
    """Render graph. Two-stage (default, the official LTX-2.5 and Stubelius beta 2 recipe): 8 distilled steps at half
    size, x2 latent upscale, then the last 3 distilled steps at full size with the start image re-applied at 1.0.
    Falls back to one full-size stage when the spatial upscaler is not installed or opts["two_stage"] is off."""
    prompt = gay_reinforce(with_triggers(prompt, opts), opts)
    unets = choices("UNETLoader", "unet_name")
    loras = choices("LoraLoaderModelOnly", "lora_name")
    clips = choices("CLIPLoader", "clip_name")
    vaes = choices("VAELoader", "vae_name")
    unet = opts["transformer"] if opts["transformer"] in unets else pick(unets, "Stubelius_remix_beta2", "Stubelius", "distilled")
    clip = opts["clip"] if opts["clip"] in clips else pick(clips, "gemma4-12b-with-proj-ltx-2.5-bf16", "gemma4-12b")
    quality = [v for v in vaes if "video-vae" in v and "conv" not in v]
    vae = pick(vaes, "video-vae-conv") if opts["vae"] == "fast" else (quality[0] if quality else None)
    vae = vae or pick(vaes, "ltx-2.5-video-vae")
    g = {
        "1": {"class_type": "UNETLoader", "inputs": {"unet_name": unet, "weight_dtype": "default"}},
        "4": {"class_type": "CLIPLoader", "inputs": {"clip_name": clip, "type": "ltxv", "device": "default"}},
        "5": {"class_type": "CLIPTextEncode", "inputs": {"clip": ["4", 0], "text": prompt}},
        "6": {"class_type": "CLIPTextEncode", "inputs": {"clip": ["4", 0], "text": NEGATIVE}},
        "7": {"class_type": "LTXVConditioning", "inputs": {"positive": ["5", 0], "negative": ["6", 0], "frame_rate": 24.0}},
        "8": {"class_type": "VAELoader", "inputs": {"vae_name": vae}},
        "9": {"class_type": "VAELoader", "inputs": {"vae_name": pick(vaes, "ltx-2.5-audio-vae")}},
        "10": {"class_type": "LoadImage", "inputs": {"image": image}},
        "11": {"class_type": "ImageScale", "inputs": {"image": ["10", 0], "upscale_method": "bicubic",
                                                    "width": width, "height": height, "crop": "center"}},
        "12": {"class_type": "LTXVPreprocess", "inputs": {"image": ["11", 0], "img_compression": compression}},
        "13": {"class_type": "EmptyLTXVLatentVideo", "inputs": {"width": width, "height": height, "length": frames, "batch_size": 1}},
        "14": {"class_type": "LTXVImgToVideoInplace", "inputs": {"vae": ["8", 0], "image": ["12", 0], "latent": ["13", 0],
                                                               "strength": float(opts.get("i2v", 0.55)), "bypass": False}},
        "15": {"class_type": "LTXVEmptyLatentAudio", "inputs": {"audio_vae": ["9", 0], "frames_number": frames,
                                                              "frame_rate": 24, "batch_size": 1}},
        "16": {"class_type": "LTXVConcatAVLatent", "inputs": {"video_latent": ["14", 0], "audio_latent": ["15", 0]}},
        "18": {"class_type": "KSamplerSelect", "inputs": {"sampler_name": "euler_ancestral"}},
        "19": {"class_type": "RandomNoise", "inputs": {"noise_seed": seed}},
        "20": {"class_type": "ManualSigmas", "inputs": {"sigmas": SIGMAS}},
        "21": {"class_type": "SamplerCustomAdvanced", "inputs": {"noise": ["19", 0], "guider": ["17", 0], "sampler": ["18", 0],
                                                               "sigmas": ["20", 0], "latent_image": ["16", 0]}},
        "22": {"class_type": "LTXVSeparateAVLatent", "inputs": {"av_latent": ["21", 0]}},
        "23": {"class_type": "VAEDecodeTiled", "inputs": {"samples": ["22", 0], "vae": ["8", 0], "tile_size": 512, "overlap": 64,
                                                        "temporal_size": 64, "temporal_overlap": 16}},
        "24": {"class_type": "LTXVAudioVAEDecode", "inputs": {"samples": ["22", 1], "audio_vae": ["9", 0]}},
        "25": {"class_type": "CreateVideo", "inputs": {"images": ["23", 0], "audio": ["24", 0], "fps": 24.0}},
        "26": {"class_type": "SaveVideo", "inputs": {"video": ["25", 0], "filename_prefix": prefix, "format": "auto", "codec": "auto"}},
    }
    if image is None:
        for k in ("10", "11", "12", "14"):
            g.pop(k)
        g["16"]["inputs"]["video_latent"] = ["13", 0]
    if tail:
        g["27"] = {"class_type": "ImageFromBatch", "inputs": {"image": ["23", 0], "batch_index": frames - TAIL, "length": TAIL}}
        g["28"] = {"class_type": "SaveImage", "inputs": {"images": ["27", 0], "filename_prefix": prefix + "_tail"}}
    model = ["1", 0]
    if not has_distill_built_in(unet) and opts["distill"] > 0:
        lora450 = pick(loras, "distilled-lora-450")
        if lora450 and "distilled-lora" in lora450:
            g["2"] = {"class_type": "LoraLoaderModelOnly", "inputs": {"model": model, "lora_name": lora450,
                                                                    "strength_model": opts["distill"]}}
            model = ["2", 0]
    for n, (name, strength) in enumerate(opts["loras"]):
        if name in loras and strength:
            g[str(50 + n)] = {"class_type": "LoraLoaderModelOnly", "inputs": {"model": model, "lora_name": name,
                                                                            "strength_model": strength}}
            model = [str(50 + n), 0]
    g["17"] = {"class_type": "LTXVDualCFGGuider", "inputs": {"model": model, "positive": ["7", 0], "negative": ["7", 1],
                                                           "video_cfg": 1.0, "audio_cfg": 0.0}}
    if two_stage is None:
        two_stage = opts.get("two_stage", True)
    if two_stage and UPSCALER in choices("LatentUpscaleModelLoader", "model_name"):
        add_stage2(g, width, height, seed, image is not None)
    return g, unet


def add_stage2(g, width, height, seed, i2v):
    """Stage 1 renders at half size (every size stays a multiple of 32); stage 2 upsamples x2 and refines."""
    g["13"]["inputs"].update(width=width // 2, height=height // 2)
    g.update({
        "60": {"class_type": "LatentUpscaleModelLoader", "inputs": {"model_name": UPSCALER}},
        "61": {"class_type": "LTXVLatentUpsampler", "inputs": {"samples": ["22", 0], "upscale_model": ["60", 0], "vae": ["8", 0]}},
        "63": {"class_type": "LTXVConcatAVLatent", "inputs": {"video_latent": ["62", 0] if i2v else ["61", 0],
                                                            "audio_latent": ["22", 1]}},
        "64": {"class_type": "RandomNoise", "inputs": {"noise_seed": seed}},
        "65": {"class_type": "ManualSigmas", "inputs": {"sigmas": STAGE2_SIGMAS}},
        "66": {"class_type": "SamplerCustomAdvanced", "inputs": {"noise": ["64", 0], "guider": ["17", 0], "sampler": ["18", 0],
                                                               "sigmas": ["65", 0], "latent_image": ["63", 0]}},
        "67": {"class_type": "LTXVSeparateAVLatent", "inputs": {"av_latent": ["66", 0]}},
    })
    if i2v:
        # Official templates re-apply the start image at full strength on the upscaled latent.
        g["62"] = {"class_type": "LTXVImgToVideoInplace", "inputs": {"vae": ["8", 0], "image": ["12", 0], "latent": ["61", 0],
                                                                   "strength": 1.0, "bypass": False}}
    g["23"]["inputs"]["samples"] = ["67", 0]
    g["24"]["inputs"]["samples"] = ["67", 1]


WRITERS = ["x-ai/grok-4.5", "x-ai/grok-4.3"]
COMPILER_MODEL = "google/gemma-3-12b-it"
TEEN = re.compile(r"\b(?:late |early |mid-?)?teen(?:s|age|aged|ager|agers)?(?:\s*(?:to|-)\s*(?:early |mid-?)?(?:twenties|20s))?", re.I)
REFUSAL = re.compile(r"^\W*(i('m| am)? (sorry|unable|not able)|i can(no|'|’)t|sorry|as an ai)|\b(can(no|'|’)t|cannot|unable to|won(’|')t) "
                     r"(write|describe|help|create|comply|produce|generate)|\battach (the|a) (start )?frame", re.I)
DIRECTOR = """SYSTEM ROLE: MULTI-CLIP VIDEO DIRECTOR

You convert a user's simple story or action description into a coherent multi-clip production plan.
You DO NOT write final prompts for the video generation model.
You plan the sequence that a downstream prompt compiler will implement, one clip at a time; the compiler never sees
the other clips, so each clip must stand on its own.

Produce exactly {n} clips. Each clip is {seconds} seconds. Do not put more physical action into a clip than can
plausibly occur in that time, and never less: every clip shows a clear, visible movement or change. Never plan a clip
where nothing happens unless the user explicitly asks for a still hold.
One primary action per clip, with minimal secondary motion. Never stack in one clip several of: complex body
interaction, a large camera move, a major facial change, a large environment change, a dramatic lighting change,
several independent limb actions. Spread them across clips, one at a time.

Preserve the user's intent exactly. Do not introduce new characters, actions, story events, objects, dialogue,
camera movements, sounds, or environmental events unless required for physical continuity.
Everyone is an adult in their 20s or older. Never describe anyone as young-looking, boyish, a teen, a boy, a girl or a
child. Relationship words the user uses (bro, daddy, son, etc.) are roleplay labels between unrelated adults: never
family; show them only through visible traits such as build and an adult age.
Label actors Person A, Person B, Person C. Never write "he grabs him", "his hand moves", or "they turn".
Name the person and the limb: "Person B's right hand".
Plan only what a camera can see at this framing: no internal or microscopic changes (follicles, cells, organs, nerves)
and no imperceptible ones ("slightly", "a fraction"). A gradual change is split into clearly visible steps.
Do not solve a hard action with impossible anatomy. Do not repeat a major action in the next clip.
The END STATE of clip N must be a valid START STATE for clip N+1. Every START STATE repeats each fixed feature in
the continuity's exact words, because each clip is rendered on its own. A transformation changes only what the user
asked to change; everything else (wing type and colour, hair, face) stays as the continuity says.
No throbbing, twitching, pulsing, shuddering, vibrating or trembling: describe the movement itself (rises, swings, flexes).
Faces: each clip starts from the previous clip's last frame, so a face that has left the frame is lost and the model
invents a new one when it returns. Either keep the face in frame across clips, or once the camera leaves it, never
bring it back.
If the user did not ask for camera movement, the camera stays stable. A camera move that runs across several clips is
written per clip: where it starts and where it ends in that clip.
When the cast is male, it stays male: never a woman or female anatomy unless the user explicitly asks for it. When
two or more men have sex, the plan is gay male sex (men, penises, anus, mouths). A solo man, or a scene that is not
sex, is planned as exactly what the user asked, with nothing sexual added.
Use plain anatomical words. Slang dictionary: gooner = someone who edges/goons (prolonged edging session), edging = bringing close to orgasm then stopping repeatedly, bate bros = men who masturbate together, petite = small/slender adult build, fresh = an adult in his 20s, daddy/son = roleplay labels between unrelated adult men (an older adult and a younger adult, both 20s or older), gooning = extended edging/orgasm control session. Use these terms when the user uses them; translate to anatomical descriptions for the video model.

Reply in EXACTLY this layout, no markdown, no commentary:

GLOBAL CONTINUITY
Only facts that are true in EVERY clip: each actor's label with a short visible descriptor of 2-5 words
(Person A = the dark-haired winged man), then their fixed appearance with exact colour and type for anything a
transformation might be tempted to change (black feathered wings, short black hair), identity, fixed clothing, environment, and anything else that never changes. Anything that
changes or first appears later (a transformation, something revealed, a lighting change, a camera move) does NOT go
here; it goes only in the clips where it happens.
End it with one line: STYLE: <the look, in a few words>. With a reference image, describe its look (a photo gives
"photorealistic live-action footage, natural skin texture, real camera"); without one, photorealistic unless the user
asks for another look. The style never changes, however fantastical the story.

CLIP 1
DURATION: {seconds} seconds
START STATE: ACTORS: A: position; facing; pose; clothing; limb state (B, C the same) | CAMERA: position; angle |
  ENVIRONMENT: ... | OBJECTS: who holds what, where
ACTION: chronological, visible physical action for this clip only
END STATE: the same fields as START STATE, as they are when this clip ends (the next clip's START STATE)
ACTOR/LIMB OWNERSHIP: who moves which limb
CAMERA: this clip's framing and movement (stable if none was requested)
CONTINUITY: what must not change

CLIP 2
(same fields, through CLIP {n})"""

COMPILER = """SYSTEM ROLE: LTX-2.5 VIDEO PROMPT COMPILER

You receive ONE clip specification from an upstream director, plus a global continuity state and the reference-image state.
Translate that specification into one precise LTX-2.5 prompt. The clip specification is authoritative.

Write this clip only. The global continuity gives fixed facts; never narrate events that belong to other clips or to
the video as a whole (no "initially", no "transforms from ... into ..." beyond what this clip's ACTION says, no camera
move that is not in this clip's CAMERA, nothing revealed before this clip reveals it).

DO NOT rewrite the story, add actions, add characters, change who acts, change left/right limbs, add dramatic movement,
add camera movement that was not specified, add dialogue, sound, music, ambience, cuts, or secondary actions.

Each prompt is rendered on its own and the model forgets everything else, so every prompt names the fixed
appearance from the continuity that is in view, in the continuity's exact words (black feathered wings, not just
wings), even mid-transformation: words like devil, demon or monster must never change a fixed feature.
With two or more people, keep the labels Person A, Person B and define each once at first mention with the
continuity's descriptor ("Person A, the blond man, ..."); then always the label, with ownership repeated
("Person B's right hand"). With one person, write "the man" (or "the person"), never a label. Translate anatomy-textbook or internal terms
into what is visible (hair follicles retract -> the hair gets shorter; chest wall expands -> the chest grows broader).

Priority: reference-image geometry, actor identity, spatial position, limb ownership, starting pose, the one primary
action, chronological movement, end state, camera, lighting. No audio words: the video is saved silent.
Order (guide §54): open with the primary action; then who does it and their starting pose; the movement in order
(initiation, movement, contact, result); important anatomy and object relationships; environment; camera; lighting;
end on the end state. It reads as natural sentences, not a list. Do not describe the start or end as a separate static
picture a second time. Never put durations or seconds in the prompt: length is set by the frame count.
When two or more people are present, repeat whose limb it is wherever a hand or arm could be confused.
One primary action. If a limb is only partly in frame, it still belongs to its actor — do not borrow the other person's visible limb.
If the camera is not specified, say the viewpoint stays stable. Use the lighting the spec gives. Do not invent speech,
breathing, impacts, vibration, humming, ambience, or music. No throbbing, twitching, shuddering, vibrating, pulsing,
trembling, or shaking.
Everyone is an adult (20s or older); never write young-looking, boyish, teen, boy, girl or child, and never family words.
Graphic anatomy when the spec names it (penis, glans, shaft, foreskin, testicles, anus, mouth, vagina, vulva, clitoris, breasts) — use explicit terms when the scene calls for it. Female anatomy only when the user explicitly requests it. Slang from the director (gooner, edging, bate bros, etc.) should be translated to anatomical descriptions for LTX-2.5.
End the paragraph with the continuity's STYLE line, word for word. The look never drifts toward illustration,
concept art, painting or CGI, however fantastical the action.
70-130 words, present tense, one paragraph. Reply with that paragraph only."""

# Stubelius beta 2's model card points to the 10Eros / Sulphur 2 prompting guides (LTX 2.3 lineage): describe the
# start image first (people, look, pose and composition, background, context), then every moving body part in order,
# as a long directive script. The distilled checkpoint keeps the official / guide §54 order (primary action first).
COMPILER_SCENE_FIRST = COMPILER.replace(
    """Order (guide §54): open with the primary action; then who does it and their starting pose; the movement in order
(initiation, movement, contact, result); important anatomy and object relationships; environment; camera; lighting;
end on the end state.""",
    """Order (10Eros / Sulphur guide, for Stubelius beta 2): first describe the starting scene in concise natural language:
each person, their appearance, their composition and pose, the background and context. Then the scene evolves: describe
every moving body part, composition change and manipulation in order (initiation, movement, contact, result), the
camera, the lighting, and end on the end state. LTX has very little self-reasoning: anything not commanded will not
happen.""").replace("70-130 words, present tense", "120-200 words, present tense")
assert COMPILER_SCENE_FIRST != COMPILER and "10Eros" in COMPILER_SCENE_FIRST and "120-200 words" in COMPILER_SCENE_FIRST
PROMPT_STYLES = {"distilled": "Action first (official distilled / LTX guide)",
                 "stubelius": "Scene first (Stubelius beta 2 / 10Eros guide)"}


def prompt_style(transformer, override=None):
    """Which compiler a render gets: an explicit choice wins; else the official distilled checkpoint gets the
    action-first order and everything else (Stubelius beta 2, the default) the scene-first order."""
    if override in PROMPT_STYLES:
        return override
    return "distilled" if "distilled" in str(transformer or "").lower() else "stubelius"


MEN = """
EVERYONE IN THIS VIDEO IS A MAN. Write GAY male sex / male-only sex — never a woman, never female anatomy unless explicitly requested, never a
hetero couple. If two or more people are in frame they are men having gay sex with each other; if one man, it is male
masturbation or solo male sex. Prefer wording like "two men", "gay sex", "his penis", "another man's penis/anus/mouth",
"male bodies". The video model easily turns a chest into breasts, so: when a man's chest is in the scene call it a flat
male chest or pecs, sized as the clip describes (slim, muscular, heavily muscled); hands rub, stroke or brush the chest, never squeeze, knead, cup, grope, fondle or pinch it; nothing bounces or
jiggles; never write breasts, bust, cleavage, soft, curvy, nipples, vagina, vulva, labia, pussy, clitoris, girlfriend,
wife, she, her. Name penis / erection / testicles / male anus / male perineum when genitals are involved — never a
vaginal opening or labial folds. Hands must stay readable: male fingers, short nails, no manicure, no melted blobs.
Lighting: use the clip's own lighting; only if it gives none, use soft diffused indoor light with controlled
highlights so skin detail survives; never describe blown-out white sheets, hot specular glare, washed-out skin, or harsh
overexposure."""
FEMALE = re.compile(r"\b(woman|women|female|girls?|lady|ladies|she|her|breasts?|boobs?|tits|futa|pussy|vagina|vulva)\b", re.I)
MALE = re.compile(r"\b(man|men|male|guys?|boys?|he|his|him|penis|gay)\b", re.I)
BREASTS = re.compile(r"\b(?:breasts?|boobs?|tits|bust|cleavage|pussy|vagina|vulva|labia|clitoris)\b", re.I)
CHEST_GRAB = re.compile(r"\b(squeez|knead|cupp?|grop|fondl|jiggl|pinch)(\w*)((?:\s+\w+){0,3}?\s+(?:pectorals?|pecs|chest|nipples?))", re.I)
SHEHER = re.compile(r"\b(she|her|hers|herself|girlfriend|wife)\b", re.I)


def men_only(*texts):
    t = " ".join(x for x in texts if x)
    return bool(MALE.search(t)) and not FEMALE.search(t)


def fix_men(text):
    text = BREASTS.sub("chest", text)
    text = SHEHER.sub("his", text)
    def verb(m):
        end = m[2].lower()
        return "rub" + ("s" if end.endswith("s") else "bing" if end.endswith("ing") else "bed" if end.endswith("ed") else "") + m[3]
    return CHEST_GRAB.sub(verb, text)


T2V = """
THERE IS NO START FRAME: this is text-to-video and the video model sees only your words. Keep the action first and most of
the paragraph, but replace step 3 with 2-3 sentences that fully set the scene: each person (adults 20s-50s: build, hair,
skin, body hair, nude or exact clothes, genitals as visible (erect or soft, size, pubic hair), where they are and their
starting pose) and the place. In this case the paragraph may run to 160 words."""


CAPTION = """Describe this video start frame factually in 2-4 sentences: how many people, each one's apparent sex,
age as an adult decade (20s, 30s, 40s, 50s; never younger than 20s, never the word teen), build, hair, skin, clothing or nudity, pose and position, the place, the camera framing, the light.
Only what is visible. No guesses about names or relationships."""


class Assist(BaseModel):
    idea: str
    frames: int = 49
    image: Optional[str] = None
    parts: int = 1
    from_job: Optional[str] = None
    writer: Optional[str] = None
    transformer: Optional[str] = None
    prompt_style: Optional[str] = None


CLIP_HEAD = re.compile(r"(?im)^CLIP\s+\d+\s*$")
GLOBAL_HEAD = re.compile(r"(?im)^GLOBAL CONTINUITY\s*$")


def parse_director(text):
    """(continuity, [{raw}]) or None. The director document is the only story the compiler sees."""
    raw = (text or "").strip()
    if not GLOBAL_HEAD.search(raw) or not re.search(r"(?im)^CLIP\s+1\s*$", raw):
        return None
    chunks = CLIP_HEAD.split(raw)
    continuity = GLOBAL_HEAD.sub("", chunks[0]).strip()
    specs = [{"raw": block.strip()} for block in chunks[1:] if block.strip()]
    return (continuity, specs) if specs else None


@router.post("/assist")
def assist(a: Assist):
    try:
        key = OR_KEY.read_text().strip()
    except FileNotFoundError:
        raise HTTPException(400, "No OpenRouter key on the relay; add it in the prompt generator's settings.")
    idea = plain_words(a.idea.strip())[:1500]
    if len(idea) < 3 and not a.image:
        raise HTTPException(400, "Type a few words about what should happen.")
    if a.from_job:
        src = load(a.from_job)
        if src["status"] != "done" or not (RENDERS / src["out"]).exists():
            raise HTTPException(409, "That clip isn't finished.")
        import base64
        tmp = JOBS / f"assist_{a.from_job}.jpg"
        last_frame(RENDERS / src["out"], tmp)
        a.image = "data:image/jpeg;base64," + base64.b64encode(tmp.read_bytes()).decode()
        tmp.unlink(missing_ok=True)
    if a.image and (len(a.image) > 4_000_000 or not re.match(r"^data:image/(jpeg|png|webp);base64,", a.image)):
        raise HTTPException(400, "The picture couldn't be read; pick it again.")
    frame = ""
    if a.image:
        frame, _ = ask(key, CAPTION, [{"type": "text", "text": "Describe the frame."},
                                      {"type": "image_url", "image_url": {"url": a.image}}], 250, a.writer)
    men = men_only(frame, idea)
    if a.parts > 1 or a.from_job:
        return plan(key, a, idea, frame, men)
    directed = direct(key, idea, frame, 1, a.frames, men, a.writer, a.image)
    style = prompt_style(a.transformer, a.prompt_style)
    text, model = compile_clip(key, directed["continuity"], directed["specs"][0], frame, a.image if a.image else None, men,
                               style)
    if not text:
        raise HTTPException(502, f"Couldn't compile the prompt ({model}). Try different words.")
    return {"prompt": fix_men(text) if men else text, "model": model, "frame": frame, "style": style}


def direct(key, idea, frame, n, frames, men, writer, image=None):
    """Director only. Returns continuity + one spec per clip. Does not write an LTX prompt."""
    seconds = max(2, round(frames / 24))
    system = DIRECTOR.format(n=n, seconds=seconds) + (MEN if men else "")
    user = (f"REFERENCE IMAGE:\n{frame}\n\n" if frame else
            "No reference image. Use only the people and place the words name. Adults in their 20s-40s.\n\n") + \
        f"CLIPS: {n}\nDURATION EACH: {seconds} seconds\nSTORY:\n{idea or 'something natural that fits the picture'}"
    content = [{"type": "text", "text": user}] + ([{"type": "image_url", "image_url": {"url": image}}] if image else [])
    text, model = "", ""
    parsed = None
    for _ in range(2):
        text, model = ask(key, system, content, 600 + n * 320, writer, temperature=0.3)
        parsed = parse_director(text)
        if parsed and len(parsed[1]) == n:
            break
        parsed = None
    if not parsed:
        raise HTTPException(502, f"Couldn't direct {n} clips ({'wrong clip count' if text else model}). Try again.")
    return {"continuity": parsed[0], "specs": parsed[1], "model": model, "text": text.strip()}


def plan(key, a, idea, frame, men=False):
    n = max(1, min(20, a.parts))
    directed = direct(key, idea, frame, n, a.frames, men, a.writer, a.image)
    return {"prompt": directed["text"], "model": directed["model"], "frame": frame, "lines": n}


LABEL = re.compile(r"\bPerson ([A-E])\b")
DESCRIPTOR = re.compile(r"\bPerson ([A-E])\s*(?:=|:|is|\u2014|-)\s*((?:the|a|an)\s+[^,;.\n()]{3,60})", re.I)


def short_descriptor(d, words=6):
    """'a blond muscular man with a beard, nude' -> 'the blond muscular man': stop at a clause, at most `words` words."""
    d = re.split(r"\s+(?:with|wearing|who|in|on|lying|standing|sitting)\s+|[,;:(]", d.strip(), maxsplit=1)[0]
    d = " ".join(d.split()[:words])
    return re.sub(r"^(a|an)\s+", "the ", d, flags=re.I)


def relabel(text, continuity, spec, men):
    """One person: "the man" (or "the person"); the prompt already describes him and a label adds nothing.
    Several people: keep Person A / Person B (the guide's actor binding) but make sure each label is defined once,
    with its short descriptor from the director's continuity, before it is used."""
    labels = sorted(set(LABEL.findall(f"{continuity or ''} {spec or ''} {text or ''}")))
    out = text or ""
    if len(labels) <= 1:
        out = LABEL.sub(lambda m: "the man" if men else "the person", out)
    else:
        names = {m.group(1): short_descriptor(m.group(2)) for m in DESCRIPTOR.finditer(continuity or "")}
        missing = [f"Person {l} is {names[l]}" for l in labels if l in names and names[l].lower() not in out.lower()
                   and f"Person {l}" in out]
        if missing:
            out = "; ".join(missing) + ". " + out
    # "the man's" at the start of a sentence reads as "The man's".
    return re.sub(r"(^|[.!?]\s+)the\b", lambda m: m.group(1) + "The", out)


def plan_name(continuity, spec_raw, men=True):
    """A run's name from its plan: clip 1's ACTION in words ("The man's shoulders broaden ..."), not the raw
    "DURATION: 5 seconds" header that used to become the job name."""
    m = re.search(r"(?im)^ACTION:\s*(.+)$", (spec_raw or "").replace("\r", ""))
    text = relabel(m.group(1), continuity, spec_raw, men) if m else ""
    text = re.sub(r"\s+", " ", text).strip().rstrip(".;,")
    return (text[:57].rsplit(" ", 1)[0] + "…") if len(text) > 60 else (text or "chain")


STYLE_LINE = re.compile(r"(?im)^\s*STYLE:\s*(.+?)\s*$")
PHOTOREAL = "Photorealistic live-action footage, natural skin texture, real camera."
STYLED = re.compile(r"\b(cartoon|anime|illustrat|painting|painted|comic|pixel art|claymation|3d render|cgi)", re.I)


def style_of(continuity, spec_raw=""):
    """The run's look: the director's STYLE line, else photoreal unless the plan itself asks for another look."""
    m = STYLE_LINE.search(continuity or "")
    if m:
        return m.group(1).rstrip(".") + "."
    return "" if STYLED.search(f"{continuity or ''} {spec_raw or ''}") else PHOTOREAL


def with_style(text, style):
    """Every clip ends on the same style words: each clip is rendered alone, and without them the look drifted
    from photo to concept art over a transformation (job 8c09dff6c180)."""
    if not text or not style or style.rstrip(".").lower() in text.lower():
        return text
    return text.rstrip() + " " + style


def compile_clip(key, continuity, spec, frame, image, men, style="distilled"):
    """Gemma compiles exactly one clip. It does not see the rest of the story. style picks the prompt order
    (prompt_style): "distilled" action first, "stubelius" scene first."""
    text, model = _compile_clip(key, continuity, spec, frame, image, men, style)
    raw = spec.get("raw") or ""
    return (with_style(relabel(text, continuity, raw, men), style_of(continuity, raw)) if text else text), model


def _compile_clip(key, continuity, spec, frame, image, men, style="distilled"):
    system = (COMPILER_SCENE_FIRST if style == "stubelius" else COMPILER) + (MEN if men else "") + ("" if frame or image else
             "\nNo reference image. Use only the people and place named in the clip specification.")
    user = f"GLOBAL CONTINUITY STATE:\n{continuity or 'none'}\n\nCLIP SPECIFICATION:\n{spec.get('raw') or ''}\n\n" + \
        (f"REFERENCE IMAGE STATE:\n{frame}\n" if frame else "REFERENCE IMAGE STATE:\nnone\n")
    content = [{"type": "text", "text": user}] + ([{"type": "image_url", "image_url": {"url": image}}] if image else [])
    return ask(key, system, content, 500, models=[COMPILER_MODEL] + WRITERS, temperature=0.2)


def ask(key, system, content, max_tokens, writer=None, models=None, temperature=0.5):
    """(text, model) from the first writer that answers without refusing; ("", reason) if none."""
    last = ""
    order = models if models is not None else ([writer] if writer and MODEL_ID.match(writer) else []) + [w for w in WRITERS if w != writer]
    for model in order:
        if not model or not MODEL_ID.match(model):
            continue
        try:
            r = requests.post("https://openrouter.ai/api/v1/chat/completions", timeout=120, json={
                "model": model, "temperature": temperature, "max_tokens": max_tokens,
                "messages": [{"role": "system", "content": system}, {"role": "user", "content": content}]},
                headers={"Authorization": f"Bearer {key}", "HTTP-Referer": "https://84-12-112-249.sslip.io",
                         "X-Title": "Forge LTX"})
        except requests.RequestException:
            last = "OpenRouter didn't answer"
            continue
        if r.status_code == 401:
            raise HTTPException(401, "OpenRouter rejected the API key; paste it again in the prompt generator's settings.")
        if r.status_code >= 400:
            try:
                last = (r.json().get("error") or {}).get("message") or f"HTTP {r.status_code}"
            except ValueError:
                last = f"HTTP {r.status_code}"
            continue
        text = (((r.json().get("choices") or [{}])[0].get("message") or {}).get("content") or "").strip().strip('"')
        if text and not REFUSAL.match(text):
            return TEEN.sub("early 20s", text), model
        last = "the model refused"
    return "", last


def save(job):
    job["updated"] = time.time()
    p = JOBS / f"{job['id']}.json"
    tmp = p.with_suffix(".tmp")
    tmp.write_text(json.dumps(job))
    tmp.replace(p)


def load(jid):
    if not JOB_ID.match(jid):
        raise HTTPException(400, "Bad id")
    try:
        return json.loads((JOBS / f"{jid}.json").read_text())
    except FileNotFoundError:
        raise HTTPException(404, "No such job")


def wait_video(pid, on_state=lambda s: None, tail=None):
    """Poll ComfyUI until prompt `pid` finishes; returns the mp4 bytes, raises RuntimeError on failure."""
    started, misses = time.time(), 0
    while time.time() - started < 3 * 3600:
        try:
            h = comfy("GET", f"/history/{pid}").json().get(pid)
            misses = 0
        except HTTPException:
            misses += 1
            if misses > 20:
                raise RuntimeError("Lost ComfyUI on Colab for 10+ minutes (runtime stopped?)")
            time.sleep(30)
            continue
        if h:
            st = h.get("status", {})
            if st.get("status_str") == "error":
                msg = next((m[1].get("exception_message") for m in st.get("messages", []) if m[0] == "execution_error"), None)
                raise RuntimeError(msg or "ComfyUI reported an error")
            files = [f for out in h.get("outputs", {}).values() for k in ("images", "gifs", "videos", "video")
                     for f in out.get(k, []) if str(f.get("filename", "")).endswith((".mp4", ".webm"))
                     and f.get("type", "output") == "output"]
            if files:
                if tail is not None:
                    pngs = sorted((f for out in h.get("outputs", {}).values() for f in out.get("images", [])
                                   if str(f.get("filename", "")).endswith(".png")), key=lambda f: f["filename"])
                    tail[:] = [view(f) for f in pngs]
                return view(files[0])
            if st.get("completed"):
                raise RuntimeError("ComfyUI finished but saved no video")
        else:
            q = comfy("GET", "/queue").json()
            ids = [it[1] for it in q.get("queue_running", [])] + [it[1] for it in q.get("queue_pending", [])]
            on_state("rendering" if ids and ids[0] == pid else "queued")
        time.sleep(5)
    raise RuntimeError("Gave up after 3 hours")


def view(f):
    return comfy("GET", "/view", params={"filename": f["filename"], "subfolder": f.get("subfolder", ""),
                                         "type": f.get("type", "output")}, timeout=300).content


def mute_mp4(data: bytes) -> bytes:
    """Drop LTX's hallucinated audio (buzz/vibration hum) — keep picture only."""
    import tempfile
    with tempfile.TemporaryDirectory() as td:
        src, dst = Path(td) / "in.mp4", Path(td) / "out.mp4"
        src.write_bytes(data)
        subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", str(src), "-c:v", "copy", "-an", str(dst)],
                       check=True, timeout=120)
        return dst.read_bytes() if dst.exists() and dst.stat().st_size > 1000 else data


def set_status(jid, new):
    job = load(jid)
    if job["status"] == "failed":
        raise RuntimeError("Cancelled")
    if job["status"] in ("queued", "rendering") and new != job["status"]:
        job["status"] = new
        save(job)


def watch(jid):
    job = load(jid)
    try:
        if job.get("kind") == "chain":
            return run_chain(jid)
        if job.get("kind") == "sharpen":
            return run_sharpen(jid)
        data = wait_video(job["prompt_id"], lambda st: set_status(jid, st))
        (RENDERS / job["out"]).write_bytes(mute_mp4(data))
        job = load(jid)
        job.update(status="done", finished=time.time(), seconds=round(time.time() - job["created"]))
        return save(job)
    except Exception as e:
        job = load(jid)
        if job["status"] != "failed":
            job.update(status="failed", error=str(getattr(e, "detail", e))[:400])
            save(job)
    finally:
        _threads.pop(jid, None)


def start_watch(jid):
    if jid not in _threads:
        th = threading.Thread(target=watch, args=(jid,), daemon=True)
        _threads[jid] = th
        th.start()


def slug(s):
    return re.sub(r"[^a-z0-9]+", "-", s.lower()).strip("-")[:40] or "clip"


def submit(data, fname, prompt, frames, size, seed, opts, prefix, compression=18, tail=False):
    """data=None renders text-to-video."""
    image = comfy("POST", "/upload/image", files={"image": (fname, data)}, data={"overwrite": "true"}).json()["name"] if data else None
    width, height = SIZES[size]
    g, unet = graph(image, prompt, frames, width, height, seed, opts, prefix, compression, tail)
    res = comfy("POST", "/prompt", json={"prompt": g, "client_id": str(uuid.uuid4())}).json()
    if res.get("node_errors"):
        raise HTTPException(502, "ComfyUI rejected the graph: " + json.dumps(res["node_errors"])[:300])
    return res["prompt_id"], unet


UPSCALER = "ltx-2.5-latent-spatial-upscaler-x2-bf16-1.0.safetensors"


def has_audio(path):
    """True if the file has an audio stream. Renders are saved picture-only (see strip_audio), so most have none.
    If ffprobe cannot tell, assume there is sound: that is the old behaviour."""
    try:
        r = subprocess.run(["ffprobe", "-v", "error", "-select_streams", "a", "-show_entries", "stream=index", "-of", "csv=p=0",
                            str(path)], capture_output=True, text=True, timeout=30)
        return r.returncode != 0 or bool(r.stdout.strip())
    except Exception:
        return True


def sharpen_graph(video, prompt, frames, seed, opts, prefix, audio=True):
    """Finished clip -> latents -> x2 latent upscale -> last 3 distilled steps at full size -> decode, original sound kept.
    A silent clip (audio=False) gets the empty audio latent a normal render starts from, and comes back silent."""
    g, unet = graph("unused.png", prompt, frames, 768, 512, seed, opts, prefix, two_stage=False)
    empty_audio = json.loads(json.dumps(g["15"]))
    for k in ("10", "11", "12", "13", "14", "15", "16", "19", "20", "21", "22", "23", "24", "25", "26"):
        g.pop(k, None)
    g.update({
        "30": {"class_type": "LoadVideo", "inputs": {"file": video}},
        "31": {"class_type": "GetVideoComponents", "inputs": {"video": ["30", 0]}},
        "32": {"class_type": "VAEEncodeTiled", "inputs": {"pixels": ["31", 0], "vae": ["8", 0], "tile_size": 512, "overlap": 64,
                                                          "temporal_size": 64, "temporal_overlap": 16}},
        "33": {"class_type": "LatentUpscaleModelLoader", "inputs": {"model_name": UPSCALER}},
        "34": {"class_type": "LTXVLatentUpsampler", "inputs": {"samples": ["32", 0], "upscale_model": ["33", 0], "vae": ["8", 0]}},
        "35": {"class_type": "LTXVAudioVAEEncode", "inputs": {"audio": ["31", 1], "audio_vae": ["9", 0]}},
        "36": {"class_type": "LTXVConcatAVLatent", "inputs": {"video_latent": ["34", 0], "audio_latent": ["35", 0]}},
        "37": {"class_type": "RandomNoise", "inputs": {"noise_seed": seed}},
        "38": {"class_type": "ManualSigmas", "inputs": {"sigmas": "0.909375, 0.725, 0.421875, 0.0"}},
        "39": {"class_type": "SamplerCustomAdvanced", "inputs": {"noise": ["37", 0], "guider": ["17", 0], "sampler": ["18", 0],
                                                                "sigmas": ["38", 0], "latent_image": ["36", 0]}},
        "40": {"class_type": "LTXVSeparateAVLatent", "inputs": {"av_latent": ["39", 0]}},
        "41": {"class_type": "VAEDecodeTiled", "inputs": {"samples": ["40", 0], "vae": ["8", 0], "tile_size": 768, "overlap": 64,
                                                          "temporal_size": 64, "temporal_overlap": 16}},
        "42": {"class_type": "CreateVideo", "inputs": {"images": ["41", 0], "audio": ["31", 1], "fps": 24.0}},
        "43": {"class_type": "SaveVideo", "inputs": {"video": ["42", 0], "filename_prefix": prefix, "format": "auto", "codec": "auto"}},
    })
    if not audio:
        # GetVideoComponents yields no audio for a silent clip, and encoding None fails ("input audio is None").
        empty_audio["inputs"]["frames_number"] = frames
        g["35"] = empty_audio
        del g["42"]["inputs"]["audio"]
    return g


def sharpen_clip(src, prompt, frames, seed, job, prefix, on_state):
    up = comfy("POST", "/upload/image", files={"image": (f"{Path(prefix).name}.mp4", src.read_bytes(), "video/mp4")},
               data={"overwrite": "true"}).json()
    g = sharpen_graph(up["name"], prompt, frames, seed, job_opts(job), prefix, audio=has_audio(src))
    res = comfy("POST", "/prompt", json={"prompt": g, "client_id": str(uuid.uuid4())}).json()
    if res.get("node_errors"):
        raise RuntimeError("ComfyUI rejected the sharpen graph: " + json.dumps(res["node_errors"])[:300])
    return wait_video(res["prompt_id"], on_state)


def run_sharpen(jid):
    job = load(jid)
    src = load(job["source"])
    d = JOBS / jid
    d.mkdir(exist_ok=True)
    set_status(jid, "rendering")
    if src.get("kind") == "chain":
        sd = JOBS / src["id"]
        clips = ([(sd / "source.mp4", src["prompt"] or "the same scene continues")] if (sd / "source.mp4").exists() else []) + \
            [(sd / f"part_{i + 1:02d}.mp4", p.get("prompt") or src["prompt"]) for i, p in enumerate(src["parts"])]
    else:
        clips = [(RENDERS / src["out"], src["prompt"])]
    outs = []
    for i, (clip, prompt) in enumerate(clips):
        out = d / f"hd_{i + 1:02d}.mp4"
        if not out.exists():
            job = load(jid)
            job["step"] = f"clip {i + 1} of {len(clips)}"
            save(job)
            frames = src["frames"] if clip.name.startswith("part_") or src.get("kind") != "chain" else 0
            out.write_bytes(sharpen_clip(clip, prompt, frames or 121, src["seed"] + i, src, f"ltx/hd_{jid}_{i + 1:02d}",
                                         lambda st: set_status(jid, st)))
        outs.append(out)
    job = load(jid)
    if len(outs) == 1:
        shutil.copyfile(outs[0], RENDERS / job["out"])
    else:
        job["step"] = "joining"
        save(job)
        off = len(outs) - len(src["parts"])
        join(outs, RENDERS / job["out"], {off + i: p.get("trim", 0) for i, p in enumerate(src["parts"])})
    job = load(jid)
    job.update(status="done", step=None, finished=time.time(), seconds=round(time.time() - job["created"]))
    save(job)


@router.post("/jobs/{jid}/sharpen")
def sharpen(jid: str):
    src = load(jid)
    if src["status"] != "done" or src.get("kind") == "sharpen":
        raise HTTPException(409, "Only finished clips can be sharpened.")
    if not any(UPSCALER == m for m in choices("LatentUpscaleModelLoader", "model_name")):
        raise HTTPException(409, "The x2 upscaler isn't installed on Colab; rerun the LTX kit from Setup.")
    nid = secrets.token_hex(6)
    job = {"id": nid, "kind": "sharpen", "source": jid, "created": time.time(), "status": "queued", "name": "2×: " + src["name"],
           "prompt": src["prompt"], "frames": src["frames"], "size": src["size"], "seed": src["seed"],
           "parts": src.get("parts", []), "out": src["out"][:-4] + "_2x.mp4", "step": "starting", "opts": job_opts(src)}
    save(job)
    start_watch(nid)
    return job


def last_frame(video, dst):
    subprocess.run(["ffmpeg", "-v", "error", "-y", "-sseof", "-0.5", "-i", str(video), "-update", "1", "-q:v", "2", str(dst)],
                   check=True, timeout=120)
    if not dst.exists():
        raise RuntimeError(f"Couldn't grab the last frame of {video.name}")


def ref_stats(image):
    """Per-channel mean/std of the chain's first picture: every clip is pulled back to these colours."""
    import numpy as np
    from PIL import Image
    a = np.asarray(Image.open(image).convert("RGB"), dtype=np.float32).reshape(-1, 3)
    return a.mean(0), a.std(0) + 1e-3


def colour_fix(src, dst, ref):
    """Match every frame's colour to `ref` (smoothed over time) so drift can't compound from clip to clip."""
    import numpy as np
    probe = subprocess.run(["ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries", "stream=width,height,r_frame_rate",
                            "-of", "csv=p=0", str(src)], capture_output=True, text=True, timeout=60).stdout.strip().split(",")
    w, h, fps = int(probe[0]), int(probe[1]), probe[2]
    dec = subprocess.Popen(["ffmpeg", "-v", "error", "-i", str(src), "-f", "rawvideo", "-pix_fmt", "rgb24", "-"], stdout=subprocess.PIPE)
    tmp = dst.with_name(dst.stem + ".fix.mp4")
    enc = subprocess.Popen(["ffmpeg", "-v", "error", "-y", "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", f"{w}x{h}", "-r", fps, "-i", "-",
                            "-i", str(src), "-map", "0:v", "-map", "1:a?", "-c:v", "libx264", "-crf", "16", "-preset", "medium",
                            "-pix_fmt", "yuv420p", "-c:a", "copy", "-movflags", "+faststart", str(tmp)], stdin=subprocess.PIPE)
    rm, rs = ref
    m = sd = None
    size = w * h * 3
    while True:
        buf = dec.stdout.read(size)
        if len(buf) < size:
            break
        f = np.frombuffer(buf, np.uint8).reshape(-1, 3).astype(np.float32)
        fm, fs = f.mean(0), f.std(0) + 1e-3
        m, sd = (fm, fs) if m is None else (0.7 * m + 0.3 * fm, 0.7 * sd + 0.3 * fs)
        gain = np.clip(rs / sd, 0.7, 1.5)
        enc.stdin.write(np.clip((f - m) * gain + rm, 0, 255).astype(np.uint8).tobytes())
    enc.stdin.close()
    dec.wait(), enc.wait()
    if enc.returncode or not tmp.exists():
        raise RuntimeError(f"Colour correction failed on {src.name}")
    tmp.replace(dst)


def handoff(pngs, dst, ref):
    """Pick the sharpest of the clip's last lossless frames (latest wins ties), pull its colour to `ref`, save as PNG.
    Returns how many frames after it the previous clip must lose so the join doesn't jump."""
    import io
    import numpy as np
    from PIL import Image
    ims = [np.asarray(Image.open(io.BytesIO(b)).convert("RGB"), dtype=np.float32) for b in pngs]
    def sharp(a):
        g = a.mean(2)
        return float(np.var(g[1:-1, 1:-1] * 4 - g[:-2, 1:-1] - g[2:, 1:-1] - g[1:-1, :-2] - g[1:-1, 2:]))
    scores = [sharp(a) for a in ims]
    best = max(i for i, sc in enumerate(scores) if sc >= 0.9 * max(scores))
    a = ims[best].reshape(-1, 3)
    rm, rs = ref
    a = (a - a.mean(0)) * np.clip(rs / (a.std(0) + 1e-3), 0.7, 1.5) + rm
    Image.fromarray(np.clip(a, 0, 255).astype(np.uint8).reshape(ims[best].shape)).save(dst)
    return len(ims) - 1 - best


def join(parts, dst, trims=None):
    """Concatenate clips, dropping each later clip's first frame (it repeats the previous clip's last one)."""
    def has_audio(p):
        out = subprocess.run(["ffprobe", "-v", "error", "-select_streams", "a", "-show_entries", "stream=codec_type",
                              "-of", "csv=p=0", str(p)], capture_output=True, text=True, timeout=60).stdout
        return "audio" in out
    audio = has_audio(parts[0])
    args, chains = ["ffmpeg", "-v", "error", "-y"], []
    for i, p in enumerate(parts):
        args += ["-i", str(p)]
        cut = "trim=start_frame=1," if i else ""
        acut = "atrim=start=0.041667," if i else ""
        k = (trims or {}).get(i, 0)
        if k:
            n = int(subprocess.run(["ffprobe", "-v", "error", "-select_streams", "v:0", "-count_packets", "-show_entries",
                                    "stream=nb_read_packets", "-of", "csv=p=0", str(p)], capture_output=True, text=True,
                                   timeout=60).stdout.strip())
            cut += f"trim=end_frame={n - k},"
            acut += f"atrim=end={(n - k) / 24:.6f},"
        if audio:
            chains.append(f"[{i}:v]{cut}setpts=PTS-STARTPTS[v{i}];[{i}:a]{acut}asetpts=PTS-STARTPTS[a{i}]")
        else:
            chains.append(f"[{i}:v]{cut}setpts=PTS-STARTPTS[v{i}]")
    tmp = dst.with_name(dst.stem + ".part.mp4")
    if audio:
        fc = ";".join(chains) + ";" + "".join(f"[v{i}][a{i}]" for i in range(len(parts))) + f"concat=n={len(parts)}:v=1:a=1[v][a]"
        cmd = args + ["-filter_complex", fc, "-map", "[v]", "-map", "[a]", "-c:v", "libx264", "-crf", "17",
                      "-preset", "medium", "-pix_fmt", "yuv420p", "-c:a", "aac", "-b:a", "160k", "-movflags", "+faststart", str(tmp)]
    else:
        fc = ";".join(chains) + ";" + "".join(f"[v{i}]" for i in range(len(parts))) + f"concat=n={len(parts)}:v=1:a=0[v]"
        cmd = args + ["-filter_complex", fc, "-map", "[v]", "-c:v", "libx264", "-crf", "17",
                      "-preset", "medium", "-pix_fmt", "yuv420p", "-an", "-movflags", "+faststart", str(tmp)]
    subprocess.run(cmd, check=True, timeout=1800)
    tmp.replace(dst)


CHAIN = """
This clip is part {i} of {n} of ONE continuous video; each part starts on the last frame of the part before.
People already visible (keep them; do not gender-swap). Label them Person A, Person B in the prompt: {cast}
Overall idea for the whole video: {idea}
If the idea names an extra person not yet listed, INCLUDE them — their hand or arm may enter from off-frame.
Bind every contact to one person's limb. Never let Person A perform an action that belongs to Person B, and never
collapse a two-person idea into solo self-touch. Do not invent extra limbs, camera moves, cuts or sound.
Actions already done in earlier parts (do NOT repeat them): {story}
Write part {i}: one new, physically simple step forward from this frame, not a repeat and not a reset. {end}"""


SENTENCE = re.compile(r"(?<=[.!?])\s")


def write_part(key, job, i, frame_path):
    """Compile clip i only. The director's spec is authoritative; the full story is not sent again."""
    import base64
    job = load(job["id"])
    part = job["parts"][i]
    writer = job_opts(job)["writer"]
    url = frame = ""
    if frame_path:
        mime = "png" if frame_path.suffix == ".png" else "jpeg"
        url = f"data:image/{mime};base64," + base64.b64encode(frame_path.read_bytes()).decode()
        frame, _ = ask(key, CAPTION, [{"type": "text", "text": "Describe the frame."},
                                      {"type": "image_url", "image_url": {"url": url}}], 250, writer)
    if not job.get("cast") and frame:
        job["cast"] = frame
        save(job)
    if not part.get("spec") and not part.get("beat"):
        if not any(p.get("spec") for p in job["parts"]):
            try:
                directed = direct(key, job.get("idea") or job.get("prompt") or "", frame, len(job["parts"]),
                                  job["frames"], men_only(frame, job.get("idea") or ""), writer, url or None)
            except HTTPException as e:
                raise RuntimeError(e.detail)
            job = load(job["id"])
            job["continuity"] = directed["continuity"]
            job["brief"] = job.get("brief") or job.get("idea") or job.get("prompt") or ""
            job["idea"] = ""
            for p, spec in zip(job["parts"], directed["specs"]):
                p["spec"] = spec
            save(job)
            part = job["parts"][i]
    spec = part.get("spec")
    if not spec and part.get("beat"):
        seconds = max(2, round(job["frames"] / 24))
        spec = {"raw": f"DURATION: {seconds} seconds\nSTART STATE: the reference frame\nACTION: {part['beat']}\n"
                       "END STATE: the pose after this one action\nACTOR/LIMB OWNERSHIP: only the person named in ACTION moves\n"
                       "CAMERA: stable\nCONTINUITY: identity, clothes, and positions stay as in the reference frame"}
    if not spec:
        raise RuntimeError(f"No clip specification for part {i + 1}.")
    men = men_only(job.get("cast") or frame, spec.get("raw") or "")
    o = job_opts(job)
    text, model = compile_clip(key, job.get("continuity") or "", spec, frame, url or None, men,
                               prompt_style(o.get("transformer") or job.get("transformer"), o.get("prompt_style")))
    if not text:
        raise RuntimeError(f"Couldn't compile part {i + 1} ({model}).")
    job = load(job["id"])
    job["parts"][i]["compiler"] = model
    save(job)
    return fix_men(plain_words(text)) if men else plain_words(text)


def cast_of(key, job, writer):
    import base64
    start = JOBS / job["id"] / "start.jpg"
    if not start.exists():
        return ""
    url = "data:image/jpeg;base64," + base64.b64encode(start.read_bytes()).decode()
    return ask(key, CAPTION, [{"type": "text", "text": "Describe the frame."}, {"type": "image_url", "image_url": {"url": url}}],
               250, writer)[0]


LOWER_BODY = re.compile(r"\b(thrust|hips?|pelvis|ride|riding|rides|straddl|bounc|grind|squat|kneel|legs? (spread|open|lift|wrap)|"
                        r"penetrat|fuck|sex)\w*", re.I)


def needs_loose_i2v(text):
    """A continuation clip whose action moves the lower body gets the looser start-image grip."""
    return bool(LOWER_BODY.search(text or ""))


def part_name(job, i):
    return job["out"][:-4] + f"_c{i + 1:02d}.mp4"


def run_chain(jid):
    job = load(jid)
    d = JOBS / jid
    key = OR_KEY.read_text().strip() if OR_KEY.exists() else ""
    for i, part in enumerate(job["parts"]):
        clip = d / f"part_{i + 1:02d}.mp4"
        if part.get("done") and clip.exists():
            continue
        start = (None if job.get("t2v") else d / "start.jpg") if i == 0 else d / f"last_{i:02d}.png"
        if i and not start.exists():
            start = d / f"last_{i:02d}.jpg"
            last_frame(d / f"part_{i:02d}.mp4", start)
        if not part.get("prompt_id"):
            if not part.get("prompt"):
                if not key:
                    raise RuntimeError("No OpenRouter key on the relay to write the prompts; type one line per part instead.")
                part["prompt"] = write_part(key, job, i, start)
            job = load(jid)
            if job["status"] == "failed":
                return
            o = dict(job_opts(job))
            # Continuations: looser I2V only when the clip needs the lower body to move (Omni: ~0.40 moving lower
            # body). Otherwise it stays at the job's value: lowering it everywhere let faces and wings drift.
            if i > 0 and needs_loose_i2v((job["parts"][i].get("spec") or {}).get("raw") or part.get("prompt") or ""):
                o["i2v"] = min(float(o.get("i2v", 0.55)), 0.45)
            pid, unet = submit(start and start.read_bytes(), f"ltx_{jid}_{i + 1:02d}{start.suffix if start else ''}", part["prompt"], job["frames"],
                               job["size"], job["seed"] + i, o, f"ltx/chain_{jid}_{i + 1:02d}",
                               compression=18 if i == 0 and not job.get("from_job") else 8, tail=i < len(job["parts"]) - 1)
            job["parts"][i].update(prompt=part["prompt"], prompt_id=pid)
            job.update(transformer=unet, prompt_id=pid, step=f"part {i + 1} of {len(job['parts'])}")
            save(job)
        tail = []
        clip.write_bytes(mute_mp4(wait_video(job["parts"][i]["prompt_id"], lambda st: set_status(jid, st), tail)))
        if not (d / "start.jpg").exists():
            subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", str(clip), "-frames:v", "1", "-q:v", "2", str(d / "start.jpg")],
                           check=True, timeout=60)
        ref = ref_stats(d / "start.jpg")
        colour_fix(clip, clip, ref)
        if tail and i < len(job["parts"]) - 1:
            trim = handoff(tail, d / f"last_{i + 1:02d}.png", ref)
            job = load(jid)
            job["parts"][i]["trim"] = trim
            save(job)
        shutil.copyfile(clip, RENDERS / part_name(job, i))
        job = load(jid)
        job["parts"][i]["done"] = True
        save(job)
    job = load(jid)
    job.update(status="rendering", step="joining the parts")
    save(job)
    parts = ([d / "source.mp4"] if (d / "source.mp4").exists() else []) + [d / f"part_{i + 1:02d}.mp4" for i in range(len(job["parts"]))]
    off = len(parts) - len(job["parts"])
    join(parts, RENDERS / job["out"], {off + i: p.get("trim", 0) for i, p in enumerate(job["parts"])})
    job = load(jid)
    job.update(status="done", step=None, finished=time.time(), seconds=round(time.time() - job["created"]))
    save(job)


@router.post("/chain")
async def chain(image: Optional[UploadFile] = File(None), from_job: Optional[str] = Form(None), idea: str = Form(""),
                parts: int = Form(3), frames: int = Form(97), size: str = Form("landscape"), seed: Optional[int] = Form(None),
                sex_lora: Optional[str] = Form(None), sex_strength: float = Form(0.7), opts: Optional[str] = Form(None)):
    """A run of clips, each starting on the previous one's last frame, joined into one video in Renders.
    Either a start picture, or from_job = a finished clip to continue (it's kept at the front of the joined video).
    idea: one line for the whole run, one line per part, or a director plan (GLOBAL CONTINUITY + CLIP blocks)."""
    if not 1 <= parts <= 20:
        raise HTTPException(400, "Between 1 and 20 parts.")
    if frames not in FRAMES:
        raise HTTPException(400, f"Frames must be one of {FRAMES}.")
    directed = parse_director(idea)
    lines = [] if directed else [l.strip() for l in idea.strip().splitlines() if l.strip()]
    if directed and len(directed[1]) != parts:
        raise HTTPException(400, f"The plan has {len(directed[1])} clips and this run is set to {parts}.")
    if len(lines) > 1 and len(lines) != parts:
        raise HTTPException(400, f"You wrote {len(lines)} lines for {parts} parts: write one line for the whole thing, one per part, or a director plan.")
    jid = secrets.token_hex(6)
    d = JOBS / jid
    src = None
    if from_job:
        src = load(from_job)
        if src["status"] != "done" or not (RENDERS / src["out"]).exists():
            raise HTTPException(409, "That clip isn't finished.")
        size = src["size"]
        seed = seed if seed is not None else src["seed"] + 100
    if size not in SIZES:
        raise HTTPException(400, "Size must be landscape, portrait or square.")
    if not src and image is None and not lines and not directed:
        raise HTTPException(400, "With no picture, describe the video: who is in it and what happens.")
    o = parse_opts(opts, {"loras": [[sex_lora, sex_strength]]} if sex_lora else job_opts(src) if src else {})
    d.mkdir()
    if src:
        (d / "source.mp4").write_bytes((RENDERS / src["out"]).read_bytes())
        last_frame(d / "source.mp4", d / "start.jpg")
    elif image is not None:
        data = await image.read()
        if not data or len(data) > 30_000_000:
            raise HTTPException(400, "Pick an image under 30 MB.")
        raw = d / ("raw" + ((Path(image.filename or "x.png").suffix.lower() or ".png")[:5]))
        raw.write_bytes(data)
        subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", str(raw), "-frames:v", "1", "-q:v", "2", str(d / "start.jpg")],
                       check=True, timeout=60)
    name = ("more: " + src["name"]) if src else (
        (plan_name(directed[0], directed[1][0]["raw"], men_only(directed[0])) if directed else lines[0]) if (directed or lines) else "chain")
    job = {"id": jid, "kind": "chain", "created": time.time(), "status": "queued",
           "idea": "" if directed else (idea.strip() if len(lines) <= 1 else ""),
           # What was typed, kept for good: "idea" is cleared once a plan exists, and that lost the brief.
           "brief": idea.strip()[:6000],
           "continuity": directed[0] if directed else "",
           "lines": lines if len(lines) > 1 else [], "parts": [{} for _ in range(parts)], "frames": frames, "size": size,
           "seed": seed if seed is not None else random.randint(1, 2**48), "opts": o,
           "sex_lora": o["loras"][0][0] if o["loras"] else None, "from_job": from_job, "name": name[:60],
           "prompt": "" if directed else idea.strip(),
           "out": f"ltx_chain_{slug(name)}_{jid}.mp4", "step": "writing part 1", "t2v": not src and image is None}
    if directed:
        for p, spec in zip(job["parts"], directed[1]):
            p["spec"] = spec
    elif len(lines) > 1:
        for p, l in zip(job["parts"], lines):
            p["prompt" if len(l) >= 200 else "beat"] = plain_words(l)
    save(job)
    start_watch(jid)
    return job


@router.post("/render")
async def render(image: Optional[UploadFile] = File(None), prompt: str = Form(...), frames: int = Form(49),
                 size: str = Form("landscape"), seed: Optional[int] = Form(None), transformer: Optional[str] = Form(None),
                 sex_lora: Optional[str] = Form(None), sex_strength: float = Form(0.7), name: Optional[str] = Form(None),
                 opts: Optional[str] = Form(None)):
    prompt = prompt.strip()
    if len(prompt) < 10:
        raise HTTPException(400, "Write what happens in the clip (one sentence at least).")
    if frames not in FRAMES:
        raise HTTPException(400, f"Frames must be one of {FRAMES}.")
    if size not in SIZES:
        raise HTTPException(400, "Size must be landscape, portrait or square.")
    data = await image.read() if image else None
    if image and (not data or len(data) > 30_000_000):
        raise HTTPException(400, "Pick an image under 30 MB.")
    ext = (Path((image and image.filename) or "x.png").suffix.lower() or ".png")[:5]
    jid = secrets.token_hex(6)
    seed = seed if seed is not None else random.randint(1, 2**48)
    base = slug(name or prompt)
    out = f"ltx_{base}_{jid}_{frames}f.mp4"
    o = parse_opts(opts, {"transformer": transformer, "loras": [[sex_lora, sex_strength]] if sex_lora else []})
    if data:
        (JOBS / jid).mkdir()
        (JOBS / jid / f"input{ext}").write_bytes(data)
    pid, unet = submit(data, f"ltx_{jid}{ext}", prompt, frames, size, seed, o, f"ltx/{base}_{jid}")
    job = {"id": jid, "created": time.time(), "prompt_id": pid, "status": "queued", "prompt": prompt,
           "frames": frames, "size": size, "seed": seed, "transformer": unet, "opts": dict(o, transformer=unet),
           "sex_lora": o["loras"][0][0] if o["loras"] else None, "out": out, "name": name or prompt[:60], "t2v": not data}
    save(job)
    start_watch(jid)
    return job


@router.get("/jobs")
def jobs():
    out = []
    for f in JOBS.glob("*.json"):
        try:
            out.append(json.loads(f.read_text()))
        except ValueError:
            pass
    out.sort(key=lambda j: j["created"], reverse=True)
    return out[:30]


@router.get("/jobs/{jid}/input")
def job_input(jid: str):
    """The picture a clip was made from (404 for text-to-video)."""
    job = load(jid)
    d = JOBS / (job.get("source") or jid) if job.get("kind") == "sharpen" else JOBS / jid
    src = load(job["source"]) if job.get("kind") == "sharpen" else job
    if src.get("t2v") or src.get("from_job"):
        raise HTTPException(404, "No input picture")
    for f in sorted(d.glob("input.*")) + sorted(d.glob("raw.*")) + [d / "start.jpg"]:
        if f.exists():
            return FileResponse(f, headers={"Cache-Control": "private, max-age=86400"})
    raise HTTPException(404, "No input picture")


@router.post("/jobs/{jid}/cancel")
def cancel(jid: str):
    job = load(jid)
    if job["status"] not in ("queued", "rendering"):
        raise HTTPException(409, "That clip isn't rendering.")
    pid = job.get("prompt_id")
    if pid:
        q = comfy("GET", "/queue").json()
        if any(it[1] == pid for it in q.get("queue_running", [])):
            comfy("POST", "/interrupt")
        comfy("POST", "/queue", json={"delete": [pid]})
    job["status"] = "failed"
    job["error"] = "Cancelled"
    save(job)
    return {"ok": True}


COLAB_OPS = str(Path.home() / "wan" / "colab_ops.sh")
MODELS = "/content/workspace/ComfyUI/models"
DIRS = {"lora": "loras", "transformer": "diffusion_models"}
CIVIT = re.compile(r"civitai\.(?:com|red)/(?:api/download/models/(\d+)|models/\d+\S*?[?&]modelVersionId=(\d+))")
HF = re.compile(r"^https://huggingface\.co/([\w.-]+/[\w.-]+)/(?:resolve|blob)/([\w.-]+)/([\w./-]+\.safetensors)(?:\?\S*)?$")
INSTALLS_FILE = JOBS / "installs.state"
try:
    INSTALLS = json.loads(INSTALLS_FILE.read_text())
except (FileNotFoundError, ValueError):
    INSTALLS = {}


def save_installs():
    INSTALLS_FILE.write_text(json.dumps(INSTALLS))


class Install(BaseModel):
    url: str
    kind: str = "lora"


@router.post("/install")
def install(req: Install):
    """Download a LoRA or video model from CivitAI / Hugging Face straight onto Colab (tokens stay in the Colab env file)."""
    url, kind, warn, size_mb, words = req.url.strip(), req.kind, None, 0, []
    if kind not in DIRS:
        raise HTTPException(400, "Kind must be lora or transformer.")
    m = CIVIT.search(url)
    if m or url.isdigit():
        vid = url if url.isdigit() else (m[1] or m[2])
        tok = Path.home() / ".civitai_token"
        r = requests.get(f"https://civitai.com/api/v1/model-versions/{vid}", timeout=30,
                         headers={"Authorization": f"Bearer {tok.read_text().strip()}"} if tok.exists() else {})
        if r.status_code == 404:
            raise HTTPException(404, f"CivitAI has no model version {vid}. Open the model, pick the version, and copy that link.")
        if r.status_code >= 400:
            raise HTTPException(502, f"CivitAI said HTTP {r.status_code}.")
        v = r.json()
        files = [f for f in v.get("files") or [] if str(f.get("name", "")).endswith(".safetensors")]
        f = next((f for f in files if f.get("primary")), files[0] if files else None)
        if not f:
            raise HTTPException(400, "That CivitAI version has no .safetensors file.")
        name, size_mb, src, token = f["name"], round(f.get("sizeKB", 0) / 1024), f"https://civitai.com/api/download/models/{vid}", "CIVITAI_TOKEN"
        words = [w for w in v.get("trainedWords") or [] if 0 < len(w) <= 40]
        mtype = (v.get("model") or {}).get("type", "")
        kind = "lora" if mtype in ("LORA", "LoCon", "DoRA") else "transformer" if mtype == "Checkpoint" else kind
        chk = requests.get(src, params={"token": tok.read_text().strip()} if tok.exists() else {}, allow_redirects=False,
                           stream=True, timeout=30)
        if chk.status_code >= 400:
            try:
                why = chk.json()
                why = f"{why.get('error')}: {why.get('message')}" if why.get("error") else why.get("message") or chk.text[:150]
            except ValueError:
                why = f"HTTP {chk.status_code}"
            raise HTTPException(403 if chk.status_code in (401, 403) else 502, f"CivitAI won't give this file: {why}")
        chk.close()
        base = v.get("baseModel") or "unknown"
        if "2.5" not in base:
            warn = f"CivitAI lists this for {base}, not LTX 2.5, so it may do little or look wrong."
    elif (m := HF.match(url)):
        src, name, token = f"https://huggingface.co/{m[1]}/resolve/{m[2]}/{m[3]}", m[3].rsplit("/", 1)[-1], "HF_TOKEN"
    else:
        raise HTTPException(400, "Paste a CivitAI model link (with the version you want open) or a Hugging Face .safetensors file link.")
    name = re.sub(r"[^A-Za-z0-9._-]+", "_", name)
    if "ltx" not in name.lower():
        name = "ltx_" + name
    node, field = ("LoraLoaderModelOnly", "lora_name") if kind == "lora" else ("UNETLoader", "unet_name")
    if name in choices(node, field):
        raise HTTPException(409, f"{name} is already installed.")
    if INSTALLS.get(name, {}).get("state") == "downloading":
        return INSTALLS[name]
    it = INSTALLS[name] = {"name": name, "kind": kind, "state": "downloading", "size_mb": size_mb, "warn": warn,
                           "started": time.time(), "detail": "starting the download on Colab", "trigger": words[0] if words else ""}
    save_installs()
    threading.Thread(target=run_install, args=(it, src, token), daemon=True).start()
    return it


def run_install(it, src, token):
    d, name = f"{MODELS}/{DIRS[it['kind']]}", it["name"]
    log = f"/tmp/install_{name}.log"
    url = f"{src}?token=${token}" if token == "CIVITAI_TOKEN" else src
    auth = "" if token == "CIVITAI_TOKEN" else f'-H "Authorization: Bearer ${token}"'
    cmd = (f"cd /content/forge_setup && set -a && . ./env && set +a && mkdir -p {d} && "
           f"(nohup sh -c 'ok=; for i in 1 2 3 4 5; do curl -fsSL --retry 3 -C - {auth} -o {d}/{name}.part \"{url}\" && ok=1 && break; "
           f"sleep 10; done; [ -n \"$ok\" ] && mv {d}/{name}.part {d}/{name} && echo done > {log} || echo failed > {log}' "
           f">/dev/null 2>&1 &); echo started")
    try:
        r = subprocess.run(["bash", COLAB_OPS, cmd], capture_output=True, text=True, timeout=900)
        if "started" not in r.stdout:
            raise RuntimeError("Couldn't reach the Colab runtime to start the download.")
        it["detail"] = "downloading on Colab"
        save_installs()
    except Exception as e:
        it.update(state="failed", detail=str(e)[:300])
        return save_installs()
    watch_install(it)


def watch_install(it):
    d, name = f"{MODELS}/{DIRS[it['kind']]}", it["name"]
    node, field = ("LoraLoaderModelOnly", "lora_name") if it["kind"] == "lora" else ("UNETLoader", "unet_name")
    try:
        deadline = it["started"] + 1500 + it["size_mb"] / 10
        while time.time() < deadline:
            try:
                if name in choices(node, field):
                    it.update(state="done", detail="installed", seconds=round(time.time() - it["started"]))
                    return save_installs()
            except HTTPException:
                pass
            time.sleep(15)
        r = subprocess.run(["bash", COLAB_OPS, f"cat /tmp/install_{name}.log 2>/dev/null; ls -l {d}/{name}.part 2>/dev/null"],
                           capture_output=True, text=True, timeout=900)
        raise RuntimeError("The download failed on Colab (bad link or token?)." if "failed" in r.stdout
                           else "The download is taking too long; check Colab's disk and connection.")
    except Exception as e:
        it.update(state="failed", detail=str(e)[:300])
        save_installs()




STAGE = Path.home() / "wan" / "lora_stage"
STAGE.mkdir(parents=True, exist_ok=True)


@router.get("/stage/{name}")
def stage_get(name: str):
    """Serve a laptop-uploaded LoRA from ~/wan/lora_stage (relay basic-auth protects this)."""
    safe = Path(name).name
    if safe != name or ".." in name or not name.endswith(".safetensors"):
        raise HTTPException(400, "bad name")
    f = STAGE / safe
    if not f.is_file():
        raise HTTPException(404, f"not staged: {safe}")
    return FileResponse(f, filename=safe, media_type="application/octet-stream",
                        headers={"Cache-Control": "private, no-store"})


class InstallLocal(BaseModel):
    name: str
    kind: str = "lora"


@router.post("/install-local")
def install_local(req: InstallLocal):
    """Copy a staged LoRA from the relay onto Colab ComfyUI models/loras (or diffusion_models)."""
    kind = req.kind if req.kind in DIRS else "lora"
    name = Path(req.name).name
    if not name.endswith(".safetensors"):
        raise HTTPException(400, "name must end with .safetensors")
    f = STAGE / name
    if not f.is_file():
        raise HTTPException(404, f"Stage {name} first (scp into ~/wan/lora_stage/).")
    if "ltx" not in name.lower():
        # keep filename; Comfy lists exact names
        pass
    size_mb = round(f.stat().st_size / 1e6)
    node, field = ("LoraLoaderModelOnly", "lora_name") if kind == "lora" else ("UNETLoader", "unet_name")
    try:
        if name in choices(node, field):
            it = INSTALLS[name] = {"name": name, "kind": kind, "state": "done", "size_mb": size_mb,
                                   "warn": None, "started": time.time(), "detail": "already on Colab",
                                   "trigger": trigger(name) if "trigger" in dir() else INSTALLS.get(name, {}).get("trigger", "")}
            save_installs()
            return it
    except HTTPException as e:
        if e.status_code != 503:
            raise
        # offline — still queue
        pass
    it = INSTALLS[name] = {"name": name, "kind": kind, "state": "downloading", "size_mb": size_mb, "warn": None,
                           "started": time.time(), "detail": "copying staged file onto Colab",
                           "trigger": (INSTALLS.get(name) or {}).get("trigger") or ""}
    save_installs()
    threading.Thread(target=run_install_local, args=(it,), daemon=True).start()
    return it


def run_install_local(it):
    d, name = f"{MODELS}/{DIRS[it['kind']]}", it["name"]
    log = f"/tmp/install_{name}.log"
    # Pull from this hub over the public URL (Colab -> internet -> relay). Auth via env on Colab.
    # Prefer localhost via reverse if available; else public host from env RELAY_PUBLIC.
    public = os.environ.get("RELAY_PUBLIC", "https://84-12-112-249.sslip.io").rstrip("/")
    src = f"{public}/api/ltx/stage/{name}"
    cmd = (
        f"cd /content/forge_setup && set -a && . ./env && set +a && mkdir -p {d} && "
        f"(nohup sh -c 'ok=; AUTH=\"\"; "
        f"[ -n \"${{RELAY_USER:-}}\" ] && AUTH=\"-u ${{RELAY_USER}}:${{RELAY_PASS}}\"; "
        f"for i in 1 2 3 4 5; do curl -fsSL --retry 3 -C - $AUTH -o {d}/{name}.part \"{src}\" && ok=1 && break; "
        f"sleep 10; done; [ -n \"$ok\" ] && mv {d}/{name}.part {d}/{name} && echo done > {log} || echo failed > {log}' "
        f">/dev/null 2>&1 &); echo started"
    )
    try:
        r = subprocess.run(["bash", COLAB_OPS, cmd], capture_output=True, text=True, timeout=900)
        if "started" not in r.stdout:
            raise RuntimeError("Couldn't reach Colab to start the staged copy. " + (r.stderr or r.stdout)[:200])
        it["detail"] = "copying onto Colab from relay stage"
        save_installs()
    except Exception as e:
        it.update(state="failed", detail=str(e)[:300])
        return save_installs()
    watch_install(it)



CYCLE_SCRIPT = Path(__file__).resolve().parent / "ltx_qa_cycle.py"
CYCLE_PID = Path.home() / "hub" / "ltx_qa_cycle.pid"
CYCLE_LOG = Path.home() / "hub" / "ltx_qa_cycle.log"
CYCLE_STATE = Path.home() / "hub" / "ltx_qa_cycle_state.json"
CYCLE_OUT = Path.home() / "hub" / "ltx_qa_cycle.out"


class CycleStart(BaseModel):
    src: Optional[str] = None
    rounds: int = 5


def _cycle_pid() -> Optional[int]:
    if CYCLE_PID.exists():
        try:
            pid = int(CYCLE_PID.read_text().strip())
            os.kill(pid, 0)
            return pid
        except (ValueError, OSError, ProcessLookupError):
            pass
    # fallback: scan process table
    try:
        out = subprocess.check_output(["pgrep", "-f", "ltx_qa_cycle.py"], text=True)
        for line in out.split():
            try:
                pid = int(line.strip())
                return pid
            except ValueError:
                continue
    except (subprocess.CalledProcessError, FileNotFoundError):
        pass
    return None


def _cycle_stop() -> dict:
    pid = _cycle_pid()
    killed = []
    if pid:
        try:
            os.kill(pid, 15)
            killed.append(pid)
        except OSError:
            try:
                os.kill(pid, 9)
                killed.append(pid)
            except OSError:
                pass
    try:
        CYCLE_PID.unlink(missing_ok=True)
    except OSError:
        pass
    return {"ok": True, "stopped": killed}


@router.get("/cycle/status")
def cycle_status():
    pid = _cycle_pid()
    state = {}
    if CYCLE_STATE.exists():
        try:
            state = json.loads(CYCLE_STATE.read_text())
        except ValueError:
            state = {}
    log_tail = ""
    if CYCLE_LOG.exists():
        try:
            log_tail = "".join(CYCLE_LOG.read_text(errors="replace").splitlines(True)[-40:])
        except OSError:
            pass
    return {
        "running": pid is not None,
        "pid": pid,
        "src": state.get("src"),
        "kept": state.get("kept") or [],
        "history_len": len(state.get("history") or []),
        "last": (state.get("history") or [None])[-1],
        "log_tail": log_tail,
    }


@router.post("/cycle/start")
def cycle_start(body: CycleStart = CycleStart()):
    """Start render→assess→adjust→repeat in the background."""
    if not CYCLE_SCRIPT.is_file():
        raise HTTPException(500, "ltx_qa_cycle.py missing on the relay")
    rounds = max(1, min(10, int(body.rounds or 5)))
    src = (body.src or "").strip() or None
    if src:
        if not (JOBS / f"{src}.json").is_file():
            raise HTTPException(404, f"Unknown LTX job '{src}'")
    running = _cycle_pid()
    if running:
        _cycle_stop()
        time.sleep(1)
    venv_py = Path(__file__).resolve().parent / ".venv" / "bin" / "python"
    python = str(venv_py) if venv_py.is_file() else "python3"
    cmd = [python, "-u", str(CYCLE_SCRIPT), "--rounds", str(rounds)]
    if src:
        cmd += ["--src", src]
    CYCLE_LOG.parent.mkdir(parents=True, exist_ok=True)
    with CYCLE_LOG.open("a") as logf:
        logf.write(f"\n--- app start src={src or 'default'} rounds={rounds} ---\n")
    out = CYCLE_OUT.open("a")
    proc = subprocess.Popen(
        cmd,
        cwd=str(CYCLE_SCRIPT.parent),
        stdout=out,
        stderr=subprocess.STDOUT,
        start_new_session=True,
    )
    # pid file written by script; also stash quickly for status
    try:
        CYCLE_PID.write_text(str(proc.pid))
    except OSError:
        pass
    return {"ok": True, "pid": proc.pid, "src": src, "rounds": rounds, "message": "Render→assess→adjust→repeat started"}


@router.post("/cycle/stop")
def cycle_stop():
    return _cycle_stop()




def resume():
    for f in JOBS.glob("*.json"):
        try:
            j = json.loads(f.read_text())
        except ValueError:
            continue
        if j.get("status") in ("queued", "rendering"):
            start_watch(j["id"])
    for it in INSTALLS.values():
        if it["state"] == "downloading" and it.get("detail") == "downloading on Colab":
            threading.Thread(target=watch_install, args=(it,), daemon=True).start()
        elif it["state"] == "downloading":
            it.update(state="failed", detail="The hub restarted before the download started; paste the link again.")


resume()
