#!/usr/bin/env bash
# Sync caretaker code to wan-relay. Code only: never touches audit.jsonl, state.json or PAUSED,
# and never restarts anything (restart caretaker-web yourself when no chat is in progress).
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
HOST="${HUB_HOST:-wan-relay}"
rsync -az --exclude '__pycache__/' --exclude 'deploy/' --exclude 'deploy.sh' --exclude 'README.md' \
  --exclude 'audit*.jsonl' --exclude 'state.json' --exclude 'PAUSED' "$HERE/" "$HOST:~/caretaker/"
echo "synced. Apply: ssh $HOST 'sudo systemctl restart caretaker-web'  (the 10-minute timer picks up loop.py by itself)"
