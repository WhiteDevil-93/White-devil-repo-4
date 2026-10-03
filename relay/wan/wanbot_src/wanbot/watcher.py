import glob
import json
import os
import shutil
import threading
import time
from datetime import datetime

IMG_EXT = (".png", ".jpg", ".jpeg", ".webp")


class InboxWatcher(threading.Thread):
    """Drop generator JSON files (or {"mode":"chain","brief":{...}}) into the inbox folder.
    A same-named image next to the JSON (e.g. scene.json + scene.png) becomes the start image."""

    def __init__(self, store, inbox, interval=5):
        super().__init__(daemon=True, name="wanbot-inbox")
        self.store, self.inbox, self.interval = store, inbox, interval
        self.done_dir = os.path.join(inbox, "processed")
        self.fail_dir = os.path.join(inbox, "failed")
        os.makedirs(self.done_dir, exist_ok=True)
        os.makedirs(self.fail_dir, exist_ok=True)

    def run(self):
        print(f"[inbox] watching {self.inbox}", flush=True)
        while True:
            try:
                self.scan()
            except Exception as e:
                print(f"[inbox] scan error: {e}", flush=True)
            time.sleep(self.interval)

    def _move(self, paths, dest):
        stamp = datetime.now().strftime("%Y%m%d-%H%M%S_")
        for p in paths:
            if p and os.path.exists(p):
                shutil.move(p, os.path.join(dest, stamp + os.path.basename(p)))

    def scan(self):
        for path in sorted(glob.glob(os.path.join(self.inbox, "*.json"))):
            if time.time() - os.path.getmtime(path) < 3:
                continue
            stem = os.path.splitext(path)[0]
            image = next((stem + e for e in IMG_EXT if os.path.exists(stem + e)), None)
            try:
                with open(path, "r", encoding="utf-8") as f:
                    data = json.load(f)
                runner = data.pop("runner", None) or {}
                if image:
                    runner["start_image_path"] = image
                if data.get("type") in ("single", "chain", "chain_part"):
                    job = self.store.create("spec", data, runner, source="inbox")
                elif isinstance(data.get("brief"), dict):
                    job = self.store.create("brief", data["brief"], runner, source="inbox", mode=data.get("mode", "chain"))
                else:
                    raise ValueError("Not a generator JSON (type single/chain/chain_part) or a {brief:...} request")
                print(f"[inbox] {os.path.basename(path)} -> job {job['id']}", flush=True)
                self._move([path, image], self.done_dir)
            except Exception as e:
                print(f"[inbox] {os.path.basename(path)} failed: {e}", flush=True)
                with open(path + ".error.txt", "w", encoding="utf-8") as f:
                    f.write(str(e))
                self._move([path, image, path + ".error.txt"], self.fail_dir)
