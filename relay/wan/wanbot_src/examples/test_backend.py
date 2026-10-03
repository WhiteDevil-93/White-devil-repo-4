"""GPU-free backend that renders ffmpeg test patterns. Use it to check the pipeline end to end."""
import subprocess

from wanbot.backends import Backend
from wanbot.media import FFMPEG


class TestPatternBackend(Backend):
    def generate(self, req):
        dur = req.frames / req.fps
        src = (f"testsrc2=size={req.width}x{req.height}:rate={req.fps}:duration={dur}" if not req.image else None)
        if req.image:
            cmd = [FFMPEG, "-y", "-loglevel", "error", "-loop", "1", "-i", req.image, "-t", str(dur),
                   "-vf", f"scale={req.width}:{req.height},fps={req.fps},hue=h=t*40"]
        else:
            cmd = [FFMPEG, "-y", "-loglevel", "error", "-f", "lavfi", "-i", src]
        cmd += ["-frames:v", str(req.frames), "-c:v", "libx264", "-pix_fmt", "yuv420p", req.output]
        subprocess.run(cmd, check=True)
        self.log(f"[test] clip {req.index}: {req.frames}f {'i2v' if req.image else 't2v'} prompt={req.prompt[:40]!r}")
        return req.output
