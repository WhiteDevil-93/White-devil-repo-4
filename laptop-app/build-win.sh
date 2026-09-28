#!/usr/bin/env bash
# Cross-build Forge Hub Windows installers (NSIS + portable).
# On this Linux VM / WSL:  ./build-win.sh
# On Windows PowerShell, from the laptop-app folder:
#   npm install
#   npm run dist:win
set -euo pipefail
cd "$(dirname "$0")"
npm install
CSC_IDENTITY_AUTO_DISCOVERY=false npm run dist:win
echo "Installers in $(pwd)/dist/"
ls -lh dist/*.exe 2>/dev/null || true
