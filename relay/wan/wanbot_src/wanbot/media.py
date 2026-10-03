import json
import os
import shutil
import subprocess

FFMPEG = shutil.which("ffmpeg") or "ffmpeg"
FFPROBE = shutil.which("ffprobe") or "ffprobe"


def run(cmd):
    r = subprocess.run([str(c) for c in cmd], capture_output=True, text=True)
    if r.returncode != 0:
        raise RuntimeError(f"{os.path.basename(str(cmd[0]))} failed: {r.stderr[-2000:]}")
    return r.stdout


def probe(path):
    out = run([FFPROBE, "-v", "error", "-select_streams", "v:0",
               "-show_entries", "stream=width,height,r_frame_rate,nb_frames", "-of", "json", path])
    s = (json.loads(out).get("streams") or [{}])[0]
    return {"width": s.get("width"), "height": s.get("height"), "fps": s.get("r_frame_rate"), "frames": s.get("nb_frames")}


def last_frame(video, png):
    run([FFMPEG, "-y", "-loglevel", "error", "-sseof", "-0.5", "-i", video, "-update", "1", png])
    if not os.path.exists(png):
        raise RuntimeError(f"Could not extract last frame from {video}")
    return png


def normalize(src, dst, fps, trim_first=False, size=None):
    vf = []
    if trim_first:
        vf += ["trim=start_frame=1", "setpts=PTS-STARTPTS"]
    if size:
        vf.append(f"scale={size[0]}:{size[1]}:flags=lanczos")
    vf.append(f"fps={fps}")
    run([FFMPEG, "-y", "-loglevel", "error", "-i", src, "-vf", ",".join(vf), "-an",
         "-c:v", "libx264", "-crf", "16", "-preset", "medium", "-pix_fmt", "yuv420p", dst])
    return dst


def concat(files, dst):
    lst = dst + ".txt"
    with open(lst, "w", encoding="utf-8") as f:
        for p in files:
            f.write("file '" + os.path.abspath(p).replace("'", "'\\''") + "'\n")
    run([FFMPEG, "-y", "-loglevel", "error", "-f", "concat", "-safe", "0", "-i", lst, "-c", "copy", dst])
    os.remove(lst)
    return dst


def to_mp4(src, dst):
    run([FFMPEG, "-y", "-loglevel", "error", "-i", src, "-an", "-c:v", "libx264", "-crf", "14",
         "-pix_fmt", "yuv420p", "-vf", "pad=ceil(iw/2)*2:ceil(ih/2)*2", dst])
    return dst


def frames_to_mp4(frame_files, dst, fps):
    tmp = dst + "_frames"
    os.makedirs(tmp, exist_ok=True)
    for i, f in enumerate(sorted(frame_files)):
        shutil.copy(f, os.path.join(tmp, f"{i:06d}{os.path.splitext(f)[1]}"))
    ext = os.path.splitext(frame_files[0])[1]
    run([FFMPEG, "-y", "-loglevel", "error", "-framerate", str(fps), "-i", os.path.join(tmp, f"%06d{ext}"),
         "-c:v", "libx264", "-crf", "14", "-pix_fmt", "yuv420p", "-vf", "pad=ceil(iw/2)*2:ceil(ih/2)*2", dst])
    shutil.rmtree(tmp, ignore_errors=True)
    return dst


def write_tensor_video(tensor, path, fps):
    """tensor: (C,F,H,W) or (1,C,F,H,W), values in [-1, 1]."""
    import torch

    t = tensor.detach().float().cpu()
    if t.dim() == 5:
        t = t[0]
    t = ((t.clamp(-1, 1) + 1) * 127.5).round().to(torch.uint8)
    arr = t.permute(1, 2, 3, 0).contiguous().numpy()
    _, h, w, c = arr.shape
    if c != 3:
        raise RuntimeError(f"Expected 3 channels, got {c}")
    cmd = [FFMPEG, "-y", "-loglevel", "error", "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", f"{w}x{h}",
           "-r", str(fps), "-i", "-", "-c:v", "libx264", "-crf", "14", "-pix_fmt", "yuv420p",
           "-vf", "pad=ceil(iw/2)*2:ceil(ih/2)*2", path]
    p = subprocess.Popen(cmd, stdin=subprocess.PIPE, stderr=subprocess.PIPE)
    _, err = p.communicate(arr.tobytes())
    if p.returncode != 0:
        raise RuntimeError(f"ffmpeg encode failed: {err.decode(errors='ignore')[-1500:]}")
    return path
