#!/usr/bin/env bash
# Launch Forge Hub as a desktop window (WSL / Linux).
set -euo pipefail
cd "$(dirname "$0")"

if [[ ! -f package.json ]]; then
  echo "Forge Hub laptop app is missing package.json here: $PWD" >&2
  echo "From ~ that folder does not exist. Install it:" >&2
  echo "  git clone https://github.com/WhiteDevil-93/White-devil-repo-4.git" >&2
  echo "  ~/White-devil-repo-4/laptop-app/install-home.sh" >&2
  exit 1
fi

if [[ ! -d node_modules/electron ]]; then
  npm install
fi

if [[ -z "${DISPLAY:-}${WAYLAND_DISPLAY:-}" ]]; then
  echo "No DISPLAY/WAYLAND_DISPLAY. On WSL install WSLg or: export DISPLAY=:0" >&2
  exit 1
fi

exec npx electron . "$@"
