"""Server-side prompt generation via OpenRouter (same prompts as the HTML generator)."""
import json
import re
import uuid
from datetime import datetime, timezone

import requests

CHUNK = 12
MAX_CLIPS = 60

WAN_DEFAULT_NEG = ("色调艳丽，过曝，静态，细节模糊不清，字幕，风格，作品，画作，画面，静止，整体发灰，最差质量，低质量，"
                   "JPEG压缩残留，丑陋的，残缺的，多余的手指，画得不好的手部，画得不好的脸部，畸形的，毁容的，形态畸形的肢体，"
                   "手指融合，静止不动的画面，杂乱的背景，三条腿，背景人很多，倒着走")

CORE_RULES = """WAN fills any gap in a prompt with its own cinematic defaults: surprise extra characters, kisses, people standing up, cuts, location drift. Your job is to leave it no gaps.

Every positive prompt must follow this order, written as short, plain, declarative present-tense sentences in English:
1. Cast and count: state the exact number of people ("Exactly two people: ...") with age range, appearance, clothing. If count is 0, say there are no people. Always add that nobody else appears in the scene.
2. Setting and time: location, time of day, light sources, key props. Keep the location fixed.
3. Camera and framing: shot type, angle, camera movement (or explicitly static: "The camera does not move, no zoom, no pan").
4. Action timeline: what happens, in order, matched to the clip duration. Small, physically plausible beats. Use concrete verbs.
5. Motion boundaries: restate the forbidden things as positive constraints ("They remain seated the entire time. Nobody enters or leaves the frame. They only talk and smile."). Never rely on negative prompts alone.
6. Visual style and mood last: lighting, lens, color, texture.

Rules:
- No scene cuts, no transitions, one continuous shot per prompt.
- For image-to-video: do not redescribe the subject in conflicting ways; build on the start image, keep identities, clothing, and positions consistent with it, and focus on motion and camera.
- Do not invent extra characters, text, logos, or dialogue captions.
- Respect the target word count per positive prompt.
- negative: a short comma-separated English list of scene-specific things to avoid (e.g. "extra people, kissing, standing up, camera shake, subtitles, deformed hands")."""

MODEL_SPECS = {
    # model_target -> render facts used in prompts, sizes and settings.
    "Wan2.2-TI2V-5B": {"label": "WAN 2.2 TI2V-5B", "fps": 24, "landscape": "1280*704", "portrait": "704*1280"},
    "Wan2.2-T2V-A14B": {"label": "WAN 2.2 T2V-A14B", "fps": 16, "landscape": "1280*720", "portrait": "720*1280"},
    "Wan2.2-I2V-A14B": {"label": "WAN 2.2 I2V-A14B", "fps": 16, "landscape": "1280*720", "portrait": "720*1280"},
}


def _spec(b):
    return MODEL_SPECS.get((b or {}).get("model_target"), MODEL_SPECS["Wan2.2-TI2V-5B"])


def _system_single(b):
    spec = _spec(b)
    return f"""You write prompts for the {spec["label"]} video diffusion model (720p, {spec["fps"]} fps, up to 5 seconds). You output ONLY valid JSON, no markdown fences, no commentary.

{CORE_RULES}
- Each variant should differ in wording, beat choices, or lighting detail while keeping the same brief.

Return exactly this JSON shape:
{{"variants":[{{"title":"3-6 word label","prompt":"...","negative":"..."}}]}}"""


def _system_chain(b):
    spec = _spec(b)
    return f"""You write CHAINED prompts for the {spec["label"]} video diffusion model (720p, {spec["fps"]} fps, max 5 seconds per clip). Several clips are rendered separately and stitched into one longer video. You output ONLY valid JSON, no markdown fences, no commentary.

WAN has no memory between clips. Continuity exists only through the prompt text and, in continuous mode, the start frame. So:
- First write a bible: fixed descriptions of every character (age, build, face, hair, exact clothing and colors), the setting, the lighting, and a style suffix.
- Every clip prompt must repeat the bible character descriptions and the setting VERBATIM, and end with the style suffix VERBATIM. Never paraphrase them between clips.
- The people count is the same in every clip unless a beat explicitly adds someone; if someone enters for one beat, state it only in that clip and state they leave or are absent in the others.

{CORE_RULES}

Chain rules:
- Split the story into exactly the requested number of clips, one beat per clip, each paced to the clip duration.
- start_state: the exact pose, positions, expressions and camera framing at the first frame of the clip.
- end_state: the exact pose, positions and framing at the last frame.
- CONTINUOUS mode (clips 2+ are image-to-video from the previous clip's last frame): each clip's start_state must equal the previous clip's end_state. Open each prompt from that state ("Continuing from a still moment where ..."). End every clip on a settled, stable pose with slow motion into it, faces visible, no motion blur, no mid-gesture, so the handoff frame is clean. Keep camera framing consistent across clips unless a beat requires a change; any camera move must be slow.
- CUT mode: each clip is independent; camera angle and framing may change per clip for coverage, but characters, setting, lighting and style stay identical.
- Clip 1 follows the first-clip mode given in the brief.

Return exactly this JSON shape:
{{"bible":{{"characters":"...","setting":"...","style":"..."}},"clips":[{{"title":"3-6 word label","start_state":"...","prompt":"...","end_state":"...","negative":"..."}}]}}"""

BRIEF_DEFAULTS = {
    "idea": "", "first_clip_mode": "t2v", "start_image": "", "people_count": 2, "frames_per_clip": 121,
    "framing": "medium shot", "camera": "static camera, eye-level, no zoom, no pan", "orientation": "landscape",
    "motion": "gentle, natural movement", "must_not": "", "style": "", "words": "80-120", "actions": "",
    "variants": 3, "clips": 4, "linking": "continuous", "beats": [], "append_wan_negative": True,
    "model_target": "Wan2.2-TI2V-5B",
}


def _brief(b):
    out = dict(BRIEF_DEFAULTS)
    out.update({k: v for k, v in (b or {}).items() if v is not None})
    if isinstance(out["beats"], str):
        out["beats"] = [x.strip() for x in out["beats"].splitlines() if x.strip()]
    if not out["idea"]:
        raise ValueError("brief.idea is required")
    return out


def call(orcfg, system, user, max_tokens):
    key = orcfg.get("api_key")
    if not key:
        raise RuntimeError("OpenRouter api_key not configured (set OPENROUTER_API_KEY)")
    r = requests.post("https://openrouter.ai/api/v1/chat/completions", timeout=300,
                      headers={"Authorization": f"Bearer {key}", "Content-Type": "application/json",
                               "HTTP-Referer": "http://localhost", "X-Title": "wanbot runner"},
                      json={"model": orcfg["model"], "temperature": float(orcfg.get("temperature", 0.7)),
                            "max_tokens": max_tokens,
                            "messages": [{"role": "system", "content": system}, {"role": "user", "content": user}]})
    try:
        data = r.json()
    except Exception:
        raise RuntimeError(f"OpenRouter HTTP {r.status_code}: {r.text[:500]}")
    if r.status_code != 200:
        raise RuntimeError(f"OpenRouter: {(data.get('error') or {}).get('message') or r.status_code}")
    return ((data.get("choices") or [{}])[0].get("message") or {}).get("content") or ""


def extract_json(text):
    t = re.sub(r"```json|```", "", text).strip()
    a, b = t.find("{"), t.rfind("}")
    if a == -1 or b == -1:
        raise ValueError("No JSON in model reply")
    return json.loads(t[a:b + 1])


def _wc(s):
    return len(str(s or "").split())


def _size(b):
    spec = _spec(b)
    return spec["portrait"] if b["orientation"] == "portrait" else spec["landscape"]


def _shared(b):
    frames = int(b["frames_per_clip"])
    fps = _spec(b)["fps"]
    lines = [f"First clip mode: {'image-to-video' if b['first_clip_mode'] == 'i2v' else 'text-to-video'}",
             f"Scene idea: {b['idea']}",
             f"Model: {_spec(b)['label']} ({fps} fps) — pace the actions to fit"]
    if b["first_clip_mode"] == "i2v" and b["start_image"]:
        lines.append(f"Start image contains: {b['start_image']}")
    lines += [f"Exact people count: {b['people_count']}",
              f"Clip length: {(frames - 1) / fps:g} seconds ({frames} frames at {fps} fps) — pace the actions to fit",
              f"Framing: {b['framing']}",
              f"Camera: {b['camera'] or 'model may choose, but keep it a single continuous shot'}",
              f"Orientation: {b['orientation']}", f"Motion intensity: {b['motion']}"]
    if b["must_not"]:
        lines.append(f"Must not happen: {b['must_not']}")
    if b["style"]:
        lines.append(f"Style/mood: {b['style']}")
    lines.append(f"Target length per positive prompt: {b['words']} words")
    return lines


def _neg(b, v):
    return ", ".join(x for x in [v.get("negative", ""), WAN_DEFAULT_NEG if b["append_wan_negative"] else ""] if x)


def _settings(b, orcfg):
    spec = _spec(b)
    return {"model_target": b.get("model_target") or "Wan2.2-TI2V-5B", "size": _size(b),
            "frames_per_clip": int(b["frames_per_clip"]),
            "fps": spec["fps"], "guide_scale": 5.0, "sample_shift": 5.0, "steps": 50}


def generate_single(orcfg, brief):
    b = _brief(brief)
    lines = _shared(b)
    if b["actions"]:
        lines.append(f"Action timeline: {b['actions']}")
    lines.append(f"Number of variants: {b['variants']}")
    parsed = extract_json(call(orcfg, _system_single(b), "\n".join(lines), 2500))
    vs = parsed.get("variants") if isinstance(parsed.get("variants"), list) else [parsed]
    return {"type": "single", "generated_at": datetime.now(timezone.utc).isoformat(), "llm": orcfg["model"],
            "settings": _settings(b, orcfg), "brief": b,
            "variants": [{"index": i + 1, "title": v.get("title", ""), "prompt": v.get("prompt", ""),
                          "negative": _neg(b, v), "word_count": _wc(v.get("prompt"))} for i, v in enumerate(vs)]}


def generate_chain(orcfg, brief, log=print):
    b = _brief(brief)
    total = max(2, min(MAX_CLIPS, int(b["clips"])))
    count = -(-total // CHUNK)
    cont = b["linking"] == "continuous"
    bible, clips = None, []
    for i in range(1, count + 1):
        start, end = (i - 1) * CHUNK + 1, min(i * CHUNK, total)
        want = end - start + 1
        log(f"OpenRouter: batch {i}/{count} (clips {start}-{end})")
        lines = _shared(b)
        lines.append(f"Total clips in the full video: {total}")
        lines.append("Linking: " + ("CONTINUOUS (each clip after the first starts from the previous clip's last frame via image-to-video)"
                                    if cont else "CUT (independent clips, hard cuts between them)"))
        if b["beats"]:
            lines.append(f"Story beats for the whole video (spread across all {total} clips in order; if fewer beats than clips, spread them; if more, merge):\n"
                         + "\n".join(f"{n + 1}. {x}" for n, x in enumerate(b["beats"])))
        if count > 1:
            lines.append(f"\nTHIS REQUEST: batch {i} of {count}. Write ONLY clips {start} to {end} ({want} clips), covering the matching portion of the story. The \"clips\" array must contain exactly {want} items, in order.")
        if bible:
            lines.append("\nContinuity bible (fixed — copy it into the \"bible\" field UNCHANGED and use it verbatim in every prompt):\n"
                         f"Characters: {bible.get('characters', '')}\nSetting: {bible.get('setting', '')}\nStyle: {bible.get('style', '')}")
        if clips:
            lines.append(f"\nStory so far (clips 1–{start - 1}):\n" + "\n".join(f"{n + 1}. {c.get('title', '')}" for n, c in enumerate(clips)))
            lines.append(f"\nClip {start - 1} ended on: {clips[-1].get('end_state', '')}"
                         + (f"\nClip {start}'s start_state must equal this exactly; it is image-to-video from that frame." if cont else ""))
        parsed = None
        for attempt in range(2):
            try:
                parsed = extract_json(call(orcfg, _system_chain(b), "\n".join(lines), 1200 + want * 700))
                if isinstance(parsed.get("clips"), list) and parsed["clips"]:
                    break
            except Exception as e:
                log(f"OpenRouter batch {i} attempt {attempt + 1} failed: {e}")
            parsed = None
        if not parsed:
            raise RuntimeError(f"OpenRouter batch {i} did not return usable JSON")
        if bible is None:
            bible = parsed.get("bible") or {}
        clips += parsed["clips"][:want]

    n = len(clips)
    frames = int(b["frames_per_clip"])
    out_clips = []
    for i, c in enumerate(clips):
        if i == 0:
            inp = {"mode": "i2v", "image": "start.jpg"} if b["first_clip_mode"] == "i2v" else {"mode": "t2v"}
        else:
            inp = {"mode": "i2v", "image": f"last_{i:02d}.png", "from_clip": i} if cont else {"mode": "t2v"}
        out_clips.append({"index": i + 1, "title": c.get("title", ""), "input": inp,
                          "start_state": c.get("start_state", ""), "prompt": c.get("prompt", ""),
                          "negative": _neg(b, c), "end_state": c.get("end_state", ""),
                          "output_file": f"clip_{i + 1:02d}.mp4", "word_count": _wc(c.get("prompt"))})
    settings = _settings(b, orcfg)
    fps = settings["fps"]
    settings.update({"linking": b["linking"], "clip_count": n,
                     "stitched_seconds": round(((n * frames - (n - 1)) if cont else n * (frames - 1)) / fps, 2)})
    return {"type": "chain", "chain_id": uuid.uuid4().hex[:12], "generated_at": datetime.now(timezone.utc).isoformat(),
            "llm": orcfg["model"], "settings": settings, "brief": b, "bible": bible or {}, "clips": out_clips}
