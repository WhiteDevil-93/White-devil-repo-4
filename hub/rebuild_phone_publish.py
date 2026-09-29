#!/usr/bin/env python3
"""Rebuild hub/static/term/phone_publish.sh with a fresh hub.tgz payload."""
from __future__ import annotations

import base64
import io
import tarfile
from pathlib import Path

HUB = Path(__file__).resolve().parent
SCRIPT = HUB / "static" / "term" / "phone_publish.sh"
SKIP_DIR = {".venv", "__pycache__", ".pytest_cache", ".git"}
SKIP_FILE = {"phone_publish.sh"}


def keep(path: Path) -> bool:
    parts = set(path.parts)
    if parts & SKIP_DIR:
        return False
    if path.name in SKIP_FILE:
        return False
    if path.suffix == ".pyc":
        return False
    return True


def tarball() -> bytes:
    buf = io.BytesIO()
    with tarfile.open(fileobj=buf, mode="w:gz") as tar:
        for p in sorted(HUB.rglob("*")):
            if not p.is_file() or not keep(p.relative_to(HUB)):
                continue
            tar.add(p, arcname=str(Path("hub") / p.relative_to(HUB)))
    return buf.getvalue()


HEADER = """#!/bin/bash
# Phone-only Forge Hub publish. Forge Hub -> Terminal (this IS the laptop).
set -euo pipefail
echo "Publishing Forge Hub to wan-relay from this phone session..."
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

base64 -d >"$TMP/hub.tgz" <<'__FORGE_HUB_TGZ_B64__'
"""

FOOTER = """
__FORGE_HUB_TGZ_B64__

if ! ssh -o BatchMode=yes -o ConnectTimeout=12 wan-relay "echo ok" >/dev/null 2>&1; then
  echo "Cannot reach wan-relay. Wake WSL / the PC (Terminal should not say offline), then Send again." >&2
  exit 1
fi

ssh -o BatchMode=yes wan-relay 'mkdir -p ~/hub/static/term && tar -C ~ -xzf - && (
  mkdir -p ~/wan/www/app
  rsync -a --delete --exclude forgehub.apk ~/hub/static/ ~/wan/www/app/ 2>/dev/null || cp -a ~/hub/static/. ~/wan/www/app/
  [[ -f ~/hub/static/forgehub.apk ]] && cp -f ~/hub/static/forgehub.apk ~/wan/www/app/forgehub.apk
  cp -f ~/hub/screens.json ~/wan/www/app/screens.json 2>/dev/null || true
  systemctl --user restart forge-hub || sudo -n systemctl restart forge-hub || true
)' <"$TMP/hub.tgz"

# Keep the phone endpoint /app/term/phone_publish.sh up to date on the relay
cat "$0" | ssh -o BatchMode=yes wan-relay 'cat > ~/hub/static/term/phone_publish.sh && chmod +x ~/hub/static/term/phone_publish.sh' 2>/dev/null || true

echo "Done! Forge Hub updated on the relay (hub + live /app)."
echo "Reload the laptop app (Forge Hub desktop tab) or pull-to-refresh on the phone."
echo "Manifest web_rev should match hub/screens.json after reload."
"""


def main() -> None:
    raw = tarball()
    b64 = base64.b64encode(raw).decode("ascii")
    wrapped = "\n".join(b64[i : i + 76] for i in range(0, len(b64), 76))
    SCRIPT.write_text(HEADER + wrapped + "\n" + FOOTER)
    SCRIPT.chmod(0o755)
    print(f"wrote {SCRIPT}  tgz={len(raw)} bytes  script={SCRIPT.stat().st_size} bytes")


if __name__ == "__main__":
    main()
