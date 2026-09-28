"""Setup: persist the LTX 2.5 LoRA pack (all 11 Lightricks files) on the relay.

The LTX screen reads this list. Saving here is what wires every LoRA onto LTX 2.5,
not just the one Civitai file that used to get loaded.
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
STATE = Path.home() / ".forge_setup.json"
HF = "https://huggingface.co"


def catalog() -> list[dict]:
    try:
        data = json.loads(CATALOG.read_text())
    except (FileNotFoundError, ValueError) as e:
        raise HTTPException(500, f"Setup catalog missing: {e}")
    if not isinstance(data, list) or len(data) != 11:
        raise HTTPException(500, "Setup catalog must list all 11 LTX 2.5 LoRAs.")
    out = []
    for row in data:
        rec = dict(row)
        rec["url"] = f"{HF}/{rec['repo']}/resolve/main/{rec['file']}"
        rec["filename"] = Path(rec["file"]).name
        out.append(rec)
    return out


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
    # After a save, every catalog id is on. Before the first save, none are "on LTX".
    saved = bool(st.get("saved"))
    if saved and not enabled:
        enabled = {r["id"] for r in cat}
    loras = []
    for r in cat:
        on = r["id"] in enabled if saved else False
        loras.append(dict(r, enabled=on))
    return {
        "saved": saved,
        "count": len(cat),
        "enabled": sum(1 for r in loras if r["enabled"]),
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
        f'echo "Downloading {len(rows)} LTX 2.5 LoRAs into $DEST"',
        "",
    ]
    for r in rows:
        dest = f"$DEST/{r['filename']}"
        lines += [
            f'if [ -s "{dest}" ]; then echo "have {r["filename"]}"; else',
            f'  echo "get {r["name"]}"',
            f'  wget -c -q --show-progress "{r["url"]}" -O "{dest}.part"',
            f'  mv "{dest}.part" "{dest}"',
            "fi",
            "",
        ]
    lines += [
        f'echo "done  {len(rows)} files in $DEST"',
        'echo "Setup has all 11. LTX 2.5 loads this whole list, not one file."',
        "",
    ]
    return PlainTextResponse("\n".join(lines), media_type="text/x-shellscript")
