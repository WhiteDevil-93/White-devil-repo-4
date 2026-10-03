#!/usr/bin/env python3
"""Pull goon clips from MyDrive/wan_renders/gooning that the relay is missing."""
import json, os, sys, urllib.parse, urllib.request

T = json.load(open(os.path.expanduser("~/.config/colab-cli/token.json")))
R = os.path.expanduser("~/wan/renders")
QP = "themyscira-485918"
tok = json.load(urllib.request.urlopen(T["token_uri"], urllib.parse.urlencode({
    "grant_type": "refresh_token", "refresh_token": T["refresh_token"],
    "client_id": T["client_id"], "client_secret": T["client_secret"]}).encode()))["access_token"]
H = {"Authorization": "Bearer " + tok, "x-goog-user-project": QP}


def get(url):
    return urllib.request.urlopen(urllib.request.Request(url, headers=H), timeout=120)


def q(s):
    return json.load(get("https://www.googleapis.com/drive/v3/files?" + urllib.parse.urlencode(
        {"q": s, "fields": "files(id,name,size,modifiedTime)", "pageSize": 1000})))["files"]


root = q("name='wan_renders' and mimeType='application/vnd.google-apps.folder' and trashed=false")[0]["id"]
sub = q(f"name='gooning' and '{root}' in parents and trashed=false")[0]["id"]
pat = sys.argv[1] if len(sys.argv) > 1 else "smoke_goon_p04_c"
files = q(f"'{sub}' in parents and name contains '{pat}' and trashed=false")
have = set(os.listdir(R))
for f in sorted(files, key=lambda f: f["name"]):
    if f["name"] in have:
        continue
    part = os.path.join(R, "." + f["name"] + ".part")
    with get(f"https://www.googleapis.com/drive/v3/files/{f['id']}?alt=media") as r, open(part, "wb") as o:
        o.write(r.read())
    os.rename(part, os.path.join(R, f["name"]))
    print("pulled", f["name"], f["size"])
print("drive has", len(files), "matching;", sorted(f["name"][:22] for f in files)[-3:])
