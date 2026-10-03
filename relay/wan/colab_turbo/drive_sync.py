#!/usr/bin/env python3
"""Upload finished render mp4s to Google Drive (MyDrive/wan_renders/<sub>/) via the Drive API.
Usage: drive_sync.py TOKEN_JSON [--loop SECONDS] [--once FILE ...]
Uploads every *.mp4 in the watched dirs not yet recorded in the state file."""
import glob
import json
import os
import sys
import time

import requests
from google.auth.transport.requests import Request
from google.oauth2.credentials import Credentials

WATCH = {
    "gooning": "/content/outputs/colab_g4_ti2v5b_smoke/gooning/smoke_*.mp4",
    "alts": "/content/outputs/colab_g4_ti2v5b_smoke/smoke_*_alt_*.mp4",
}
STATE = os.environ.get("DRIVE_SYNC_STATE", "/content/outputs/colab_g4_ti2v5b_smoke/_drive_sync_state.json")
ROOT = "wan_renders"
QUOTA_PROJECT = os.environ.get("DRIVE_QUOTA_PROJECT", "themyscira-485918")
API = "https://www.googleapis.com/drive/v3/files"


class Drive:
    def __init__(self, token_path):
        t = json.load(open(token_path))
        # token=None: saved access tokens carry no expiry, so google-auth would treat them as valid forever
        self.creds = Credentials(token=None, refresh_token=t["refresh_token"], token_uri=t["token_uri"],
                                 client_id=t["client_id"], client_secret=t["client_secret"], scopes=t.get("scopes"))
        self.folders = {}

    def hdr(self):
        if not self.creds.valid:
            self.creds.refresh(Request())
        return {"Authorization": f"Bearer {self.creds.token}", "x-goog-user-project": QUOTA_PROJECT}

    def folder(self, name, parent=None):
        key = (name, parent)
        if key in self.folders:
            return self.folders[key]
        q = f"name = '{name}' and mimeType = 'application/vnd.google-apps.folder' and trashed = false"
        if parent:
            q += f" and '{parent}' in parents"
        r = requests.get(API, headers=self.hdr(), params={"q": q, "fields": "files(id)"}, timeout=60)
        r.raise_for_status()
        files = r.json().get("files", [])
        if files:
            fid = files[0]["id"]
        else:
            meta = {"name": name, "mimeType": "application/vnd.google-apps.folder"}
            if parent:
                meta["parents"] = [parent]
            r = requests.post(API, headers=self.hdr(), json=meta, params={"fields": "id"}, timeout=60)
            r.raise_for_status()
            fid = r.json()["id"]
        self.folders[key] = fid
        return fid

    def upload(self, path, sub):
        parent = self.folder(sub, self.folder(ROOT))
        meta = {"name": os.path.basename(path), "parents": [parent]}
        r = requests.post("https://www.googleapis.com/upload/drive/v3/files",
                          params={"uploadType": "resumable", "fields": "id"},
                          headers={**self.hdr(), "Content-Type": "application/json; charset=UTF-8"},
                          json=meta, timeout=60)
        r.raise_for_status()
        with open(path, "rb") as f:
            r2 = requests.put(r.headers["Location"], data=f, headers={"Content-Type": "video/mp4"}, timeout=600)
        r2.raise_for_status()
        return r2.json()["id"]


def load_state():
    try:
        return json.load(open(STATE))
    except Exception:
        return {}


def save_state(st):
    tmp = STATE + ".tmp"
    json.dump(st, open(tmp, "w"), indent=1)
    os.replace(tmp, STATE)


def sync(drive, files=None):
    st = load_state()
    todo = [(p, "manual") for p in files] if files else [
        (p, sub) for sub, pat in WATCH.items() for p in sorted(glob.glob(pat))]
    n = 0
    for p, sub in todo:
        if p in st or not os.path.isfile(p):
            continue
        if time.time() - os.path.getmtime(p) < 20:
            continue
        try:
            st[p] = drive.upload(p, sub)
            save_state(st)
            n += 1
            print(f"{time.strftime('%H:%M:%S')} uploaded {sub}/{os.path.basename(p)}", flush=True)
        except Exception as e:
            print(f"{time.strftime('%H:%M:%S')} FAIL {p}: {e}", flush=True)
    return n


def main():
    token = sys.argv[1]
    drive = Drive(token)
    if "--once" in sys.argv:
        sync(drive, sys.argv[sys.argv.index("--once") + 1:])
        return
    interval = int(sys.argv[sys.argv.index("--loop") + 1]) if "--loop" in sys.argv else 60
    while True:
        try:
            sync(drive)
        except Exception as e:
            print(f"{time.strftime('%H:%M:%S')} sync error: {e}", flush=True)
        time.sleep(interval)


if __name__ == "__main__":
    main()
