"""Setup: persist the LTX 2.5 LoRA pack (11 Lightricks + all NSFW content LoRAs).

Saving wires every LoRA onto LTX 2.5 — Lightricks IC/control plus the full NSFW
content set (CoachBate, Praxis, BEANFLK, …), not a single Civitai file.
"""
from __future__ import annotations

import json
from pathlib import Path

from fastapi import APIRouter, HTTPException
from fastapi.responses import PlainTextResponse
from pydantic import BaseModel

router = APIRouter(prefix="/api/setup")
HUB = Path(__file__).resolve().parent
CATALOG = HUB / "static" / "setup" / "loras.json"
CONTENT = HUB / "static" / "setup" / "content_loras.json"
STATE = Path.home() / ".forge_setup.json"
HF = "https://huggingface.co"
CIVIT = "https://civitai.com/api/download/models"


def _lightricks() -> list[dict]:
    try:
        data = json.loads(CATALOG.read_text())
    except (FileNotFoundError, ValueError) as e:
        raise HTTPException(500, f"Setup catalog missing: {e}")
    if not isinstance(data, list) or len(data) != 11:
        raise HTTPException(500, "Setup catalog must list all 11 LTX 2.5 LoRAs.")
    out = []
    for row in data:
        rec = dict(row)
        rec["pack"] = "lightricks"
        rec["url"] = f"{HF}/{rec['repo']}/resolve/main/{rec['file']}"
        rec["filename"] = Path(rec["file"]).name
        out.append(rec)
    return out


def _content() -> list[dict]:
    try:
        data = json.loads(CONTENT.read_text())
    except (FileNotFoundError, ValueError):
        return []
    if not isinstance(data, list):
        return []
    out = []
    for row in data:
        rec = dict(row)
        rec["pack"] = "nsfw"
        rec["target"] = rec.get("target") or "ltx-2.5"
        name = rec.get("file") or ""
        rec["filename"] = Path(name).name
        if rec.get("source") == "civitai" and rec.get("vid"):
            rec["url"] = f"{CIVIT}/{rec['vid']}"
        elif rec.get("url"):
            pass
        else:
            continue
        out.append(rec)
    return out


def catalog() -> list[dict]:
    return _lightricks() + _content()


def load_state() -> dict:
    try:
        st = json.loads(STATE.read_text())
    except (FileNotFoundError, ValueError):
        st = {}
    if not isinstance(st, dict):
        st = {}
    st.setdefault("enabled", [])
    st.setdefault("saved", False)
    return st


def merge(cat: list[dict], st: dict) -> dict:
    enabled = set(st.get("enabled") or [])
    # After a save, every catalog id is on. Before the first save, none are "on
    # LTX" — reporting them as enabled would claim a setup that never happened.
    # The Setup screen pre-ticks its boxes off `saved`, not off this flag.
    saved = bool(st.get("saved"))
    if saved and not enabled:
        enabled = {r["id"] for r in cat}
    loras = []
    for r in cat:
        on = r["id"] in enabled if saved else False
        loras.append(dict(r, enabled=on))
    n_lt = sum(1 for r in loras if r.get("pack") == "lightricks")
    n_ns = sum(1 for r in loras if r.get("pack") == "nsfw")
    return {
        "saved": saved,
        "count": len(cat),
        "enabled": sum(1 for r in loras if r["enabled"]),
        "lightricks": n_lt,
        "nsfw": n_ns,
        "target": "ltx-2.5",
        "loras": loras,
        "dest": "~/civitai_dl/ltx-2.5/",
    }


@router.get("")
@router.get("/")
def get_setup():
    return merge(catalog(), load_state())


class SaveIn(BaseModel):
    enabled: list[str] | None = None


@router.post("")
@router.post("/")
def save_setup(body: SaveIn | None = None):
    cat = catalog()
    ids = [r["id"] for r in cat]
    want = list(ids) if not body or body.enabled is None else [i for i in body.enabled if i in ids]
    if not want:
        want = list(ids)
    st = {"saved": True, "enabled": want, "target": "ltx-2.5"}
    STATE.write_text(json.dumps(st, indent=2) + "\n")
    STATE.chmod(0o600)
    return merge(cat, st)


@router.get("/download.sh")
def download_script():
    """Bash the laptop can run to pull every enabled LoRA into ~/civitai_dl/ltx-2.5/."""
    data = merge(catalog(), load_state())
    rows = [r for r in data["loras"] if r["enabled"]] or data["loras"]
    lines = [
        "#!/bin/bash",
        "set -euo pipefail",
        'DEST="${HOME}/civitai_dl/ltx-2.5"',
        "mkdir -p \"$DEST\"",
        'CT="${CIVITAI_TOKEN:-}"',
        '[ -z "$CT" ] && [ -f "$HOME/.civitai_token" ] && CT=$(tr -d "\\n\\r" < "$HOME/.civitai_token")',
        f'echo "Downloading {len(rows)} LTX 2.5 LoRAs (Lightricks + NSFW) into $DEST"',
        "",
    ]
    for r in rows:
        dest = f"$DEST/{r['filename']}"
        url = r["url"]
        if r.get("source") == "civitai":
            lines += [
                f'if [ -s "{dest}" ]; then echo "have {r["filename"]}"; else',
                f'  if [ -z "$CT" ]; then echo "SKIP {r["filename"]} (no CIVITAI_TOKEN)"; else',
                f'    echo "get {r["name"]}"',
                f'    wget -c -q --show-progress "{url}?token=$CT" -O "{dest}.part"',
                f'    mv "{dest}.part" "{dest}"',
                "  fi",
                "fi",
                "",
            ]
        else:
            lines += [
                f'if [ -s "{dest}" ]; then echo "have {r["filename"]}"; else',
                f'  echo "get {r["name"]}"',
                f'  wget -c -q --show-progress "{url}" -O "{dest}.part"',
                f'  mv "{dest}.part" "{dest}"',
                "fi",
                "",
            ]
    lines += [
        f'echo "done  {len(rows)} files in $DEST"',
        'echo "Setup has Lightricks + NSFW content. LTX 2.5 loads this whole list."',
        "",
    ]
    return PlainTextResponse("\n".join(lines), media_type="text/x-shellscript")
