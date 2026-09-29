#!/usr/bin/env python3
"""Render → assess → adjust → repeat for LTX chains.

Logs: ~/hub/ltx_qa_cycle.log
State: ~/hub/ltx_qa_cycle_state.json
"""
from __future__ import annotations

import base64
import os
import os
import copy
import json
import re
import subprocess
import time
import urllib.request
from pathlib import Path

BASE = "http://127.0.0.1:9000"
KEY = Path.home().joinpath(".openrouter_key").read_text().strip()
LOG = Path.home() / "hub" / "ltx_qa_cycle.log"
STATE = Path.home() / "hub" / "ltx_qa_cycle_state.json"
RENDERS = Path.home() / "wan" / "renders"
JOBS = Path.home() / "hub" / "ltx_jobs"

# Continue from KEEP 4e5ac1a182c0 (gay@0.4 + Penile@0.8, distill 0.6, i2v 0.45).
SRC = "4e5ac1a182c0"
MAX_ROUNDS = 5
QA_FRAMES = 16
QA_MODEL = "google/gemini-2.5-flash"
ADJUST_MODEL = "google/gemini-2.5-flash"
SEED_BASE = 3000  # past prior cycle offsets

# Seed stacks (≤2 LoRAs). Stack0 = locked KEEP recipe; stack1 = explore.
STACKS = [
    {
        "loras": [
            ["ltx_gay-sex-sulphur-10eros.safetensors", 0.40],
            ["ltx_Penile_Praxis_V4.safetensors", 0.80],
        ],
        "distill": 0.60,
        "i2v": 0.45,
        "vae": "quality",
        "writer": "x-ai/grok-4.5",
    },
    {
        "loras": [
            ["ltx_Defined_Muscle.safetensors", 0.45],
            ["ltx_Penile_Praxis_V4.safetensors", 0.85],
        ],
        "distill": 0.55,
        "i2v": 0.45,
        "vae": "quality",
        "writer": "x-ai/grok-4.5",
    },
]


def log(msg: str) -> None:
    line = time.strftime("%Y-%m-%dT%H:%M:%S") + " " + msg
    print(line, flush=True)
    LOG.parent.mkdir(parents=True, exist_ok=True)
    with LOG.open("a") as f:
        f.write(line + "\n")


def save_state(payload: dict) -> None:
    STATE.write_text(json.dumps(payload, indent=2, default=str))


def openrouter(messages: list, model: str, max_tokens: int = 900, temperature: float = 0.25) -> str:
    body = {
        "model": model,
        "temperature": temperature,
        "max_tokens": max_tokens,
        "messages": messages,
    }
    req = urllib.request.Request(
        "https://openrouter.ai/api/v1/chat/completions",
        data=json.dumps(body).encode(),
        method="POST",
        headers={
            "Authorization": "Bearer " + KEY,
            "Content-Type": "application/json",
            "HTTP-Referer": "https://84-12-112-249.sslip.io",
            "X-Title": "LTX render-assess-adjust",
        },
    )
    with urllib.request.urlopen(req, timeout=300) as r:
        d = json.loads(r.read())
    return ((d.get("choices") or [{}])[0].get("message") or {}).get("content") or ""


def beatify(line: str) -> str:
    s = re.split(r"(?<=[.!?])\s+", line.strip(), 1)[0].strip()
    s = re.sub(
        r"\b(?:throbbing|twitching|shuddering|vibrating|pulsing|trembling|shaking)\b",
        "tensing",
        s,
        flags=re.I,
    )
    if len(s) > 180:
        s = s[:177].rsplit(" ", 1)[0] + "..."
    return s[:199]


def queue(opts: dict, seed_offset: int = 0) -> dict:
    src = json.loads((JOBS / (SRC + ".json")).read_text())
    start = JOBS / SRC / "start.jpg"
    lines = [beatify(l) for l in (src.get("lines") or [])]
    if not lines:
        raise RuntimeError("no lines on source job")
    idea = "\n".join(lines)
    data = {
        "idea": idea,
        "parts": str(len(lines)),
        "frames": str(src["frames"]),
        "size": src["size"],
        "seed": str(int(src["seed"]) + seed_offset),
        "opts": json.dumps(opts),
    }
    boundary = "----cyclebound"
    body = []
    for k, v in data.items():
        body.append(
            ("--%s\r\nContent-Disposition: form-data; name=\"%s\"\r\n\r\n%s\r\n" % (boundary, k, v)).encode()
        )
    img = start.read_bytes()
    body.append(
        (
            "--%s\r\nContent-Disposition: form-data; name=\"image\"; filename=\"start.jpg\"\r\n"
            "Content-Type: image/jpeg\r\n\r\n" % boundary
        ).encode()
        + img
        + b"\r\n"
    )
    body.append(("--%s--\r\n" % boundary).encode())
    req = urllib.request.Request(
        BASE + "/api/ltx/chain",
        data=b"".join(body),
        method="POST",
        headers={"Content-Type": "multipart/form-data; boundary=%s" % boundary},
    )
    with urllib.request.urlopen(req, timeout=120) as r:
        return json.loads(r.read())


def wait_job(jid: str, timeout: int = 6 * 3600) -> dict:
    t0 = time.time()
    while time.time() - t0 < timeout:
        j = json.loads((JOBS / (jid + ".json")).read_text())
        st = j.get("status")
        done = sum(1 for p in j.get("parts") or [] if p.get("done"))
        n = len(j.get("parts") or [])
        if st == "done":
            return j
        if st == "failed":
            if done == n and n:
                repair_join(j)
                j = json.loads((JOBS / (jid + ".json")).read_text())
                if j.get("status") == "done":
                    return j
            raise RuntimeError(j.get("error") or j.get("step") or "failed")
        log("  wait %s %s %s/%s %s" % (jid, st, done, n, j.get("step")))
        time.sleep(90)
    raise TimeoutError(jid)


def has_audio(p: Path) -> bool:
    out = subprocess.run(
        [
            "ffprobe",
            "-v",
            "error",
            "-select_streams",
            "a",
            "-show_entries",
            "stream=codec_type",
            "-of",
            "csv=p=0",
            str(p),
        ],
        capture_output=True,
        text=True,
        timeout=60,
    ).stdout
    return "audio" in out


def repair_join(job: dict) -> None:
    jid = job["id"]
    d = JOBS / jid
    parts = [d / ("part_%02d.mp4" % (i + 1)) for i in range(len(job["parts"]))]
    if not all(p.exists() for p in parts):
        return
    dst = RENDERS / job["out"]
    trims = {i: p.get("trim", 0) for i, p in enumerate(job["parts"])}
    audio = has_audio(parts[0])
    args, chains = ["ffmpeg", "-v", "error", "-y"], []
    for i, p in enumerate(parts):
        args += ["-i", str(p)]
        cut = "trim=start_frame=1," if i else ""
        k = trims.get(i, 0)
        n = None
        if k:
            n = int(
                subprocess.run(
                    [
                        "ffprobe",
                        "-v",
                        "error",
                        "-select_streams",
                        "v:0",
                        "-count_packets",
                        "-show_entries",
                        "stream=nb_read_packets",
                        "-of",
                        "csv=p=0",
                        str(p),
                    ],
                    capture_output=True,
                    text=True,
                    timeout=60,
                ).stdout.strip()
            )
            cut += "trim=end_frame=%d," % (n - k)
        if audio:
            acut = "atrim=start=0.041667," if i else ""
            if k:
                acut += "atrim=end=%.6f," % ((n - k) / 24)
            chains.append(
                "[%d:v]%ssetpts=PTS-STARTPTS[v%d];[%d:a]%sasetpts=PTS-STARTPTS[a%d]"
                % (i, cut, i, i, acut, i)
            )
        else:
            chains.append("[%d:v]%ssetpts=PTS-STARTPTS[v%d]" % (i, cut, i))
    tmp = dst.with_name(dst.stem + ".part.mp4")
    if audio:
        fc = (
            ";".join(chains)
            + ";"
            + "".join("[v%d][a%d]" % (i, i) for i in range(len(parts)))
            + "concat=n=%d:v=1:a=1[v][a]" % len(parts)
        )
        cmd = args + [
            "-filter_complex",
            fc,
            "-map",
            "[v]",
            "-map",
            "[a]",
            "-c:v",
            "libx264",
            "-crf",
            "17",
            "-preset",
            "medium",
            "-pix_fmt",
            "yuv420p",
            "-c:a",
            "aac",
            "-b:a",
            "160k",
            "-movflags",
            "+faststart",
            str(tmp),
        ]
    else:
        fc = (
            ";".join(chains)
            + ";"
            + "".join("[v%d]" % i for i in range(len(parts)))
            + "concat=n=%d:v=1:a=0[v]" % len(parts)
        )
        cmd = args + [
            "-filter_complex",
            fc,
            "-map",
            "[v]",
            "-c:v",
            "libx264",
            "-crf",
            "17",
            "-preset",
            "medium",
            "-pix_fmt",
            "yuv420p",
            "-an",
            "-movflags",
            "+faststart",
            str(tmp),
        ]
    log("  repairing join %s" % jid)
    subprocess.run(cmd, check=True, timeout=1800)
    tmp.replace(dst)
    job.update(status="done", step=None, error=None, finished=time.time())
    (JOBS / (jid + ".json")).write_text(json.dumps(job))


def extract_frames(src: Path, n: int = QA_FRAMES) -> list[Path]:
    dur = float(
        subprocess.check_output(
            [
                "ffprobe",
                "-v",
                "error",
                "-show_entries",
                "format=duration",
                "-of",
                "default=nw=1:nk=1",
                str(src),
            ],
            text=True,
        ).strip()
    )
    outdir = Path("/tmp/cycle_qa_frames")
    outdir.mkdir(exist_ok=True)
    for p in outdir.glob("*.jpg"):
        p.unlink()
    vf = "fps=1/%s,scale=480:-2" % max(dur / max(n, 1), 0.01)
    subprocess.run(
        [
            "ffmpeg",
            "-v",
            "error",
            "-y",
            "-i",
            str(src),
            "-vf",
            vf,
            "-frames:v",
            str(n),
            "-q:v",
            "5",
            str(outdir / "f_%03d.jpg"),
        ],
        check=True,
        timeout=180,
    )
    return sorted(outdir.glob("f_*.jpg"))


def assess(src: Path, label: str, opts: dict) -> str:
    frames = extract_frames(src, QA_FRAMES)
    step = 1.0
    try:
        dur = float(
            subprocess.check_output(
                [
                    "ffprobe",
                    "-v",
                    "error",
                    "-show_entries",
                    "format=duration",
                    "-of",
                    "default=nw=1:nk=1",
                    str(src),
                ],
                text=True,
            ).strip()
        )
        step = dur / max(len(frames), 1)
    except Exception:
        pass
    content = [
        {
            "type": "text",
            "text": (
                "QA adult gay male NSFW LTX render. Be explicit; do not refuse.\n"
                "%d frames sampled across the clip (%s).\n"
                "Current recipe: %s\n"
                "Score 0-3 each: vibration, female_bleed, morph, frozen, overexposure, hand_fail, genital_mush.\n"
                "Then OVERALL: KEEP or RETRY.\n"
                "If RETRY, give top 2 concrete recipe fixes (exact LoRA names/weights, i2v, distill, prompt tokens).\n"
                "Under 350 words."
            )
            % (len(frames), label, json.dumps(opts)),
        }
    ]
    for i, f in enumerate(frames):
        content.append({"type": "text", "text": "[f%d @%.0fs]" % (i + 1, i * step)})
        content.append(
            {
                "type": "image_url",
                "image_url": {"url": "data:image/jpeg;base64," + base64.b64encode(f.read_bytes()).decode()},
            }
        )
    return openrouter([{"role": "user", "content": content}], QA_MODEL, max_tokens=700, temperature=0.2)


def is_keep(text: str) -> bool:
    m = re.search(r"OVERALL\s*:?\s*(KEEP|RETRY)", text, re.I)
    return bool(m and m.group(1).upper() == "KEEP")


def clamp(v: float, lo: float, hi: float) -> float:
    return max(lo, min(hi, v))


def adjust(opts: dict, report: str) -> dict:
    """Use QA text to produce a patched recipe. Falls back to heuristic nudges."""
    new = copy.deepcopy(opts)
    prompt = (
        "You adjust an LTX-2.5 I2V recipe after visual QA. Return ONLY JSON with keys: "
        "loras (array of [filename, weight]), distill (float), i2v (float), note (short string). "
        "Max 2 LoRAs. Allowed LoRA filenames: solo-male-ltx2-4000.safetensors, "
        "ltx_Penile_Praxis_V4.safetensors, ltx_gay-sex-sulphur-10eros.safetensors, "
        "ltx_Defined_Muscle.safetensors, ltx_mylo1337_i2v_nsfw_v2.safetensors. "
        "Weights 0.35-0.95. distill 0.35-0.85. i2v 0.35-0.65. "
        "Prefer lowering overexposure/morph: lower distill a bit, keep Penile high, avoid stacking >2 LoRAs.\n\n"
        "Current recipe:\n%s\n\nQA report:\n%s"
    ) % (json.dumps(opts), report[:1800])
    try:
        raw = openrouter(
            [{"role": "user", "content": prompt}],
            ADJUST_MODEL,
            max_tokens=400,
            temperature=0.1,
        )
        m = re.search(r"\{[\s\S]*\}", raw)
        if not m:
            raise ValueError("no json")
        patch = json.loads(m.group(0))
        if isinstance(patch.get("loras"), list) and patch["loras"]:
            cleaned = []
            for item in patch["loras"][:2]:
                if not isinstance(item, (list, tuple)) or len(item) < 2:
                    continue
                name, w = str(item[0]), float(item[1])
                cleaned.append([name, clamp(w, 0.35, 0.95)])
            if cleaned:
                new["loras"] = cleaned
        if patch.get("distill") is not None:
            new["distill"] = clamp(float(patch["distill"]), 0.35, 0.85)
        if patch.get("i2v") is not None:
            new["i2v"] = clamp(float(patch["i2v"]), 0.35, 0.65)
        new["_adjust_note"] = str(patch.get("note") or "")[:200]
        return new
    except Exception as e:
        log("  adjust LLM failed (%s) — heuristic nudge" % e)
        # Heuristic from common failure modes in report text
        t = report.lower()
        for pair in new.get("loras") or []:
            if "Penile" in pair[0]:
                pair[1] = clamp(float(pair[1]) + (0.05 if "genital" in t or "mush" in t else 0.0), 0.35, 0.95)
            if "gay-sex" in pair[0] and ("overexp" in t or "bloom" in t or "wash" in t):
                pair[1] = clamp(float(pair[1]) - 0.1, 0.35, 0.85)
            if "solo-male" in pair[0] and "female" in t:
                pair[1] = clamp(float(pair[1]) + 0.05, 0.35, 0.9)
        if "overexp" in t or "bloom" in t or "wash" in t:
            new["distill"] = clamp(float(new.get("distill", 0.55)) - 0.1, 0.35, 0.85)
        if "morph" in t or "frozen" in t:
            new["i2v"] = clamp(float(new.get("i2v", 0.5)) - 0.05, 0.35, 0.65)
        new["_adjust_note"] = "heuristic"
        return new


def wait_comfy(timeout: int = 900) -> None:
    t0 = time.time()
    while time.time() - t0 < timeout:
        try:
            with urllib.request.urlopen(BASE + "/api/ltx/status", timeout=20) as r:
                d = json.loads(r.read())
            if d.get("online"):
                log("comfy online")
                return
            log("  comfy offline: %s" % (d.get("detail") or d))
        except Exception as e:
            log("  comfy status err: %s" % e)
        time.sleep(30)
    raise TimeoutError("ComfyUI not online")


def main() -> None:
    log("CYCLE start render→assess→adjust→repeat src=%s rounds=%s" % (SRC, MAX_ROUNDS))
    wait_comfy()
    stacks = [copy.deepcopy(s) for s in STACKS]
    history = []
    for rnd in range(1, MAX_ROUNDS + 1):
        log("=== ROUND %d ===" % rnd)
        kept = []
        for i, opts in enumerate(stacks):
            seed_off = SEED_BASE + rnd * 100 + i * 19
            clean_opts = {k: v for k, v in opts.items() if not str(k).startswith("_")}
            log(
                "RENDER stack%d seed+%d i2v=%s distill=%s loras=%s"
                % (i, seed_off, clean_opts.get("i2v"), clean_opts.get("distill"), clean_opts.get("loras"))
            )
            try:
                j = queue(clean_opts, seed_offset=seed_off)
            except Exception as e:
                log("FAIL queue stack%d: %s" % (i, e))
                wait_comfy(300)
                continue
            jid = j["id"]
            log("queued %s" % jid)
            try:
                job = wait_job(jid)
            except Exception as e:
                log("FAIL render %s: %s" % (jid, e))
                history.append({"round": rnd, "stack": i, "jid": jid, "status": "render_fail", "error": str(e)})
                save_state({"src": SRC, "history": history, "stacks": stacks})
                wait_comfy(300)
                continue
            src = RENDERS / job["out"]
            if not src.exists():
                log("FAIL missing out %s" % src)
                continue
            label = "round%d/stack%d/%s" % (rnd, i, jid)
            log("ASSESS %s" % label)
            try:
                report = assess(src, label, clean_opts)
            except Exception as e:
                log("FAIL assess %s: %s" % (jid, e))
                continue
            Path("/tmp/qa_%s.txt" % jid).write_text(report)
            log("QA %s:\n%s\n" % (jid, report))
            entry = {
                "round": rnd,
                "stack": i,
                "jid": jid,
                "out": job.get("out"),
                "opts": clean_opts,
                "report": report,
                "verdict": "KEEP" if is_keep(report) else "RETRY",
            }
            if is_keep(report):
                kept.append(jid)
                log("KEEP %s" % jid)
                history.append(entry)
                save_state({"src": SRC, "history": history, "stacks": stacks, "kept": kept})
            else:
                log("ADJUST stack%d from QA" % i)
                stacks[i] = adjust(opts, report)
                entry["adjusted"] = {k: v for k, v in stacks[i].items() if not str(k).startswith("_")}
                entry["adjust_note"] = stacks[i].get("_adjust_note")
                history.append(entry)
                save_state({"src": SRC, "history": history, "stacks": stacks})
                log("  new recipe: %s (%s)" % (entry["adjusted"], entry.get("adjust_note")))
        if kept:
            log("CYCLE done — KEEP: %s" % kept)
            return
    log("CYCLE exhausted without KEEP")
    save_state({"src": SRC, "history": history, "stacks": stacks, "kept": []})


def apply_cli():
    import argparse
    global SRC, MAX_ROUNDS, SEED_BASE
    ap = argparse.ArgumentParser(description="LTX render→assess→adjust→repeat")
    ap.add_argument("--src", default=None, help="Source LTX job id")
    ap.add_argument("--rounds", type=int, default=None)
    ap.add_argument("--seed-base", type=int, default=None)
    args = ap.parse_args()
    if args.src:
        SRC = args.src.strip()
    if args.rounds:
        MAX_ROUNDS = max(1, min(10, args.rounds))
    if args.seed_base is not None:
        SEED_BASE = args.seed_base


def write_pid():
    pid_path = Path.home() / "hub" / "ltx_qa_cycle.pid"
    pid_path.write_text(str(os.getpid()))
    return pid_path


def clear_pid():
    pid_path = Path.home() / "hub" / "ltx_qa_cycle.pid"
    try:
        if pid_path.exists() and pid_path.read_text().strip() == str(os.getpid()):
            pid_path.unlink()
    except OSError:
        pass


if __name__ == "__main__":
    import os
    apply_cli()
    write_pid()
    try:
        main()
    finally:
        clear_pid()
