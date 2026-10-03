import os
import platform
import re
import shutil
import stat
import subprocess
import threading
import time
import urllib.request

URL_RE = re.compile(r"https://[a-z0-9-]+\.trycloudflare\.com")


def ensure_cloudflared(dest_dir):
    found = shutil.which("cloudflared")
    if found:
        return found
    os.makedirs(dest_dir, exist_ok=True)
    path = os.path.join(dest_dir, "cloudflared")
    if not os.path.exists(path):
        arch = "arm64" if platform.machine().lower() in ("aarch64", "arm64") else "amd64"
        url = f"https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-{arch}"
        print(f"[tunnel] downloading cloudflared ({arch}) ...", flush=True)
        urllib.request.urlretrieve(url, path)
        os.chmod(path, os.stat(path).st_mode | stat.S_IEXEC)
    return path


def start(port, work_dir, timeout=90):
    binary = ensure_cloudflared(os.path.join(work_dir, "bin"))
    log_path = os.path.join(work_dir, "cloudflared.log")
    proc = subprocess.Popen([binary, "tunnel", "--no-autoupdate", "--url", f"http://127.0.0.1:{port}"],
                            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, bufsize=1)
    found = {}

    def pump():
        with open(log_path, "a", encoding="utf-8") as lf:
            for line in proc.stdout:
                lf.write(line)
                lf.flush()
                m = URL_RE.search(line)
                if m and "url" not in found:
                    found["url"] = m.group(0)

    threading.Thread(target=pump, daemon=True).start()
    t0 = time.time()
    while "url" not in found:
        if proc.poll() is not None:
            raise RuntimeError(f"cloudflared exited; see {log_path}")
        if time.time() - t0 > timeout:
            raise RuntimeError(f"No tunnel URL after {timeout}s; see {log_path}")
        time.sleep(0.5)
    return found["url"], proc
