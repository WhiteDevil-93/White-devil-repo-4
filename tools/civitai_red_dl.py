#!/usr/bin/env python3
"""Download Civitai (.red mirror) LoRA files onto the laptop.

Forge Hub → Terminal → Civitai LoRA → Send. This is the laptop (WSL), not the phone.

  python3 tools/civitai_red_dl.py
  python3 tools/civitai_red_dl.py --id 2851705 --id 1234567
  python3 tools/civitai_red_dl.py --id 2851705 --primary-only

Civitai requires an API token for every file download. The script reads, in order:
  CIVITAI_TOKEN / CIVITAI_API_KEY / CIVITAI_API_TOKEN env
  ~/.civitai_token  (one line, chmod 600)

Default behaviour: every LoRA-like file on every version of each --id.
The old one-file default left LTX 2.5 with a single LoRA when a model
had more versions (Wan 2.2, LTX-2, distilled, …).

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

# CoachBate Penis LTX-2.5 preview — extra --id values stack more LoRAs.
DEFAULT_IDS = ("2851705",)

# Skip these Civitai file types unless --weights (full checkpoints).
WEIGHT_TYPES = {
    "diffusion model",
    "text encoder",
    "vae",
    "upscaler",
    "checkpoint",
    "pruned model",
}


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


def _is_weight_file(f: dict) -> bool:
    return (f.get("type") or "").strip().lower() in WEIGHT_TYPES


def pick_files(
    model: dict,
    *,
    primary_only: bool = False,
    include_weights: bool = False,
) -> list[tuple[dict, dict]]:
    """Every LoRA-like file on every version (unless primary_only)."""
    versions = model.get("modelVersions") or []
    if not versions:
        sys.exit("no modelVersions in API response")
    chosen_vers = versions[:1] if primary_only else versions
    out: list[tuple[dict, dict]] = []
    for ver in chosen_vers:
        files = [f for f in (ver.get("files") or []) if f]
        if not include_weights:
            loras = [f for f in files if not _is_weight_file(f)]
            files = loras or files
        if not files:
            continue
        files.sort(key=lambda f: (not f.get("primary"), (f.get("type") or "") != "Model", f.get("id") or 0))
        if primary_only:
            files = files[:1]
        for f in files:
            out.append((ver, f))
    if not out:
        sys.exit(f"no downloadable files on model {model.get('id')}")
    return out


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
    return dest


def parse_ids(values: list[str] | None) -> list[str]:
    ids: list[str] = []
    seen: set[str] = set()
    for raw in values or []:
        for part in re.split(r"[\s,;]+", raw.strip()):
            part = part.strip()
            m = re.search(r"(?:models/)?(\d{3,})(?:/|$)", part)
            mid = m.group(1) if m else (part if part.isdigit() else "")
            if mid and mid not in seen:
                seen.add(mid)
                ids.append(mid)
    return ids


def dest_for(out: Path, model_id: str, ver: dict, f: dict) -> Path:
    name = Path(f.get("name") or "model.safetensors").name
    base = ver.get("baseModel") or "unknown"
    slug = re.sub(r"[^A-Za-z0-9._-]+", "_", str(base).strip())[:40] or "unknown"
    folder = out / f"{model_id}_{slug}"
    return folder / name


def one_model(
    model_id: str,
    tok: str,
    out: Path,
    *,
    dry_run: bool,
    primary_only: bool,
    include_weights: bool,
) -> list[Path]:
    model = load_model(model_id, tok)
    print(f"\nmodel {model_id}  {model.get('name')}")
    targets = pick_files(model, primary_only=primary_only, include_weights=include_weights)
    print(f"  {len(targets)} file(s) across {len({v.get('id') for v, _ in targets})} version(s)")
    saved: list[Path] = []
    for ver, f in targets:
        name = Path(f.get("name") or "model.safetensors").name
        dest = dest_for(out, model_id, ver, f)
        expect = (f.get("hashes") or {}).get("SHA256")
        url = download_url(ver, f)
        print(
            f"  version {ver.get('id')}  base={ver.get('baseModel')}  "
            f"{name}  {round(float(f.get('sizeKB') or 0) / 1e3, 1)} MB"
        )
        print("  url", re.sub(r"token=[^&]+", "token=***", url))
        if dry_run:
            saved.append(dest)
            continue
        if dest.is_file() and expect and sha256_file(dest).lower() == expect.lower():
            print("  already have", dest)
            saved.append(dest)
            continue
        saved.append(save(url, dest, tok, expect))
    return saved


def main() -> None:
    p = argparse.ArgumentParser(
        description="Download civitai.red LoRA files onto the laptop (all versions, not just LTX 2.5)"
    )
    p.add_argument(
        "--id",
        action="append",
        dest="ids",
        help="Civitai model id or URL (repeat or comma-separate). Default: Penis LTX-2.5.",
    )
    p.add_argument("--slug", default="penis-ltx-25-by-coachbate", help=argparse.SUPPRESS)
    p.add_argument("--out", default=str(OUT))
    p.add_argument("--dry-run", action="store_true")
    p.add_argument(
        "--primary-only",
        action="store_true",
        help="Old behaviour: newest version, one primary file.",
    )
    p.add_argument(
        "--weights",
        action="store_true",
        help="Also download checkpoints / diffusion / text-encoder files (huge).",
    )
    args = p.parse_args()
    ids = parse_ids(args.ids) or list(DEFAULT_IDS)
    tok = token()
    out = Path(args.out).expanduser()
    print("host", HOST)
    print("token", "yes" if tok else "NO — download will 401")
    print("ids", " ".join(ids))
    print("mode", "primary-only" if args.primary_only else "all LoRA files / all versions")
    saved: list[Path] = []
    for mid in ids:
        saved.extend(
            one_model(
                mid,
                tok,
                out,
                dry_run=args.dry_run,
                primary_only=args.primary_only,
                include_weights=args.weights,
            )
        )
    print()
    print(f"done  {len(saved)} LoRA file(s) in {out}")
    print(f"Files tab: /home/{Path.home().name}/civitai_dl/")
    print("Thunder LTX 2.5: copy every .safetensors into ComfyUI/models/loras/")
    print("and stack them — one LoRA loader per file. A single loader only applies one LoRA.")
    _ = args.slug


if __name__ == "__main__":
    main()
