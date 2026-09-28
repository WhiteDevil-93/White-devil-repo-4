#!/usr/bin/env python3
"""Download a Civitai (.red mirror) model file onto the laptop.

Forge Hub → Terminal → Paste → Send. This is the laptop (WSL), not the phone.

  python3 tools/civitai_red_dl.py
  python3 tools/civitai_red_dl.py --id 2851705 --slug penis-ltx-25-by-coachbate

Civitai requires an API token for every file download. The script reads, in order:
  CIVITAI_TOKEN / CIVITAI_API_KEY / CIVITAI_API_TOKEN env
  ~/.civitai_token  (one line, chmod 600)

Files land in ~/civitai_dl/. Open them from Files → /home/<you>/civitai_dl.
Do not scp the weights to the phone.
"""
from __future__ import annotations

import argparse
import hashlib
import os
import re
import subprocess
import sys
from pathlib import Path

HOST = "https://civitai.red"
OUT = Path.home() / "civitai_dl"
TOKEN_PATH = Path.home() / ".civitai_token"


def ensure_requests():
    try:
        import requests  # noqa: F401
        import urllib3  # noqa: F401
        return
    except ImportError:
        pass
    cmd = [sys.executable, "-m", "pip", "install", "-q", "requests", "urllib3"]
    try:
        subprocess.check_call(cmd)
    except subprocess.CalledProcessError:
        subprocess.check_call(cmd + ["--break-system-packages"])
    import requests  # noqa: F401
    import urllib3  # noqa: F401


ensure_requests()
import requests  # noqa: E402
import urllib3  # noqa: E402

urllib3.disable_warnings()


def token() -> str:
    for k in ("CIVITAI_TOKEN", "CIVITAI_API_KEY", "CIVITAI_API_TOKEN"):
        v = (os.environ.get(k) or "").strip()
        if v:
            return v
    if TOKEN_PATH.is_file():
        return TOKEN_PATH.read_text(encoding="utf-8").strip().splitlines()[0].strip()
    return ""


def headers(tok: str) -> dict[str, str]:
    h = {
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/126.0",
        "Accept": "application/json, application/octet-stream, */*;q=0.8",
        "Referer": HOST + "/",
    }
    if tok:
        h["Authorization"] = f"Bearer {tok}"
    return h


def rewrite(url: str) -> str:
    return re.sub(r"https?://(?:www\.)?civitai\.com", HOST, url, flags=re.I)


def get(url: str, tok: str, stream: bool = False):
    return requests.get(
        url,
        headers=headers(tok),
        verify=False,
        stream=stream,
        timeout=180,
        allow_redirects=True,
    )


def load_model(model_id: str, tok: str) -> dict:
    r = get(f"{HOST}/api/v1/models/{model_id}", tok)
    r.raise_for_status()
    return r.json()


def pick_file(model: dict, slug: str) -> tuple[dict, dict]:
    versions = model.get("modelVersions") or []
    if not versions:
        sys.exit("no modelVersions in API response")
    ver = versions[0]
    files = [f for f in (ver.get("files") or []) if f]
    if not files:
        sys.exit(f"no files on version {ver.get('id')}")
    files.sort(key=lambda f: (not f.get("primary"), f.get("type") != "Model"))
    chosen = files[0]
    _ = slug  # kept so the public page URL stays in the CLI
    return ver, chosen


def download_url(ver: dict, f: dict) -> str:
    for raw in (
        f.get("downloadUrl"),
        ver.get("downloadUrl"),
        f"{HOST}/api/download/models/{ver.get('id')}",
    ):
        if raw:
            u = rewrite(str(raw))
            fid = f.get("id")
            if fid and "fileId=" not in u:
                u += ("&" if "?" in u else "?") + f"fileId={fid}"
            return u
    sys.exit("no download URL")


def looks_like_file(r: requests.Response) -> bool:
    ct = (r.headers.get("content-type") or "").lower()
    cd = (r.headers.get("content-disposition") or "").lower()
    if r.status_code != 200:
        return False
    if "text/html" in ct:
        return False
    if "octet-stream" in ct or "application/" in ct:
        return True
    return "filename=" in cd


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def save(url: str, dest: Path, tok: str, expect_sha: str | None) -> Path:
    dest.parent.mkdir(parents=True, exist_ok=True)
    r = get(url, tok, stream=True)
    if r.status_code in (401, 403) and tok:
        # some proxies strip Authorization; query param is the documented fallback
        joiner = "&" if "?" in url else "?"
        r.close()
        r = get(f"{url}{joiner}token={tok}", "", stream=True)
    if r.status_code in (401, 403) or not looks_like_file(r):
        hint = (
            "Civitai blocks file downloads without an API token. "
            "On the laptop: echo YOUR_TOKEN > ~/.civitai_token && chmod 600 ~/.civitai_token "
            "(Account settings → API Keys on civitai.com). "
            "If this LoRA is early-access/paid, that same account must own it."
        )
        sys.exit(f"download refused {r.status_code} {(r.headers.get('content-type') or '')} {r.url}\n{hint}")
    total = int(r.headers.get("content-length") or 0)
    print(f"downloading {dest.name}  {round(total / 1e6, 1)} MB")
    tmp = dest.with_suffix(dest.suffix + ".part")
    got = 0
    with tmp.open("wb") as fh:
        for chunk in r.iter_content(1 << 20):
            if not chunk:
                continue
            fh.write(chunk)
            got += len(chunk)
            if total:
                pct = min(100, int(got * 100 / total))
                print(f"\r  {pct:3d}%  {round(got / 1e6, 1)} MB", end="", flush=True)
    print()
    tmp.replace(dest)
    if expect_sha:
        got_sha = sha256_file(dest)
        if got_sha.lower() != expect_sha.lower():
            dest.unlink(missing_ok=True)
            sys.exit(f"sha256 mismatch: got {got_sha} expected {expect_sha}")
        print("sha256 ok")
    print(f"saved {dest}  {dest.stat().st_size} bytes")
    print(f"Files tab: /home/{Path.home().name}/civitai_dl/{dest.name}")
    return dest


def main() -> None:
    p = argparse.ArgumentParser(description="Download a civitai.red model onto the laptop")
    p.add_argument("--id", default="2851705")
    p.add_argument("--slug", default="penis-ltx-25-by-coachbate")
    p.add_argument("--out", default=str(OUT))
    p.add_argument("--dry-run", action="store_true")
    args = p.parse_args()
    tok = token()
    print("host", HOST)
    print("token", "yes" if tok else "NO — download will 401")
    model = load_model(args.id, tok)
    print("model", model.get("name"))
    ver, f = pick_file(model, args.slug)
    name = Path(f.get("name") or "model.safetensors").name
    dest = Path(args.out).expanduser() / name
    expect = (f.get("hashes") or {}).get("SHA256")
    url = download_url(ver, f)
    print("version", ver.get("id"), ver.get("baseModel"))
    print("file", name, round(float(f.get("sizeKB") or 0) / 1e3, 1), "MB")
    print("url", re.sub(r"token=[^&]+", "token=***", url))
    if args.dry_run:
        return
    if dest.is_file() and expect and sha256_file(dest).lower() == expect.lower():
        print("already have", dest)
        print(f"Files tab: /home/{Path.home().name}/civitai_dl/{dest.name}")
        return
    save(url, dest, tok, expect)


if __name__ == "__main__":
    main()
