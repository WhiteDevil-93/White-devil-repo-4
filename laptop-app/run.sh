#!/usr/bin/env bash
# Launch Forge Hub as a desktop window (WSL / Linux).
set -euo pipefail
cd "$(dirname "$0")"
if [[ ! -d node_modules/electron ]]; then
  npm install
fi
exec npx electron . "$@"
