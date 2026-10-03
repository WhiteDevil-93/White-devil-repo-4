#!/usr/bin/env bash
# Snapshots the hub state that exists nowhere else, then pushes off-box to the
# laptop when its reverse tunnel is up.
#
# DELIBERATELY EXCLUDED, do not "fix" these:
#   ~/.venice_key, ~/.openrouter_key, ~/.wanbot_token
#     Secrets are regenerable from the provider dashboards; an automated archive
#     that carries them turns every backup copy into a copy of the credentials.
#     Losing auth_data/agentic_data is what is actually unrecoverable.
#   ~/wan/renders
#     2.1GB of mostly-regenerable output would dominate every transfer.
set -uo pipefail

DEST=~/backups
KEEP=14
STAMP=$(date +%Y%m%d-%H%M%S)
ARCHIVE="$DEST/forge-$STAMP.tar.gz"

mkdir -p "$DEST"; chmod 700 "$DEST"

INCLUDE=()
for p in \
  "$HOME/hub/agentic_data" \
  "$HOME/hub/auth_data" \
  "$HOME/hub/screens.json" \
  "$HOME/hub/venice.chats.json" \
  "$HOME/.venice_chats.json" ; do
  [ -e "$p" ] && INCLUDE+=("${p#$HOME/}")
done

# Render-pipeline code and small state that existed nowhere else (added 2026-10-03; the code is also in
# the repo under relay/wan). NOT included: ~/wan/renders, thumbs, lora_stage (GBs, regenerable) and the
# secret files in $HOME (tokens), same rule as above.
for p in "$HOME"/wan/*.py "$HOME"/wan/*.sh "$HOME"/wan/*.service "$HOME"/wan/colab_turbo "$HOME"/wan/wanbot_src          "$HOME"/wan/prompts "$HOME"/wan/gooning_chains "$HOME"/wan/resume_chains.json "$HOME"/caretaker ; do
  [ -e "$p" ] && INCLUDE+=("${p#$HOME/}")
done

if [ ${#INCLUDE[@]} -eq 0 ]; then
  echo "forge-backup: nothing to back up — refusing to write an empty archive" >&2
  exit 1
fi

umask 077
tar -czf "$ARCHIVE" --exclude=__pycache__ --exclude='*.pyc' -C "$HOME" "${INCLUDE[@]}" 2>/dev/null
chmod 600 "$ARCHIVE"
echo "forge-backup: wrote $ARCHIVE ($(du -h "$ARCHIVE" | cut -f1), ${#INCLUDE[@]} paths)"

# Verify before trusting or rotating on it.
if ! tar -tzf "$ARCHIVE" >/dev/null 2>&1; then
  echo "forge-backup: archive failed verification — keeping it, not rotating" >&2
  exit 1
fi

# Off-box copy. Opportunistic by design: the laptop is not always on.
if ssh -n -o ConnectTimeout=8 -o BatchMode=yes laptop 'mkdir -p ~/forge-backups && chmod 700 ~/forge-backups' 2>/dev/null; then
  if scp -q -o ConnectTimeout=8 -o BatchMode=yes "$ARCHIVE" laptop:~/forge-backups/ 2>/dev/null; then
    ssh -n -o BatchMode=yes laptop "ls -1t ~/forge-backups/forge-*.tar.gz 2>/dev/null | tail -n +$((KEEP+1)) | xargs -r rm -f" 2>/dev/null
    echo "forge-backup: pushed off-box to laptop:~/forge-backups/"
  else
    echo "forge-backup: laptop reachable but scp failed — local copy only" >&2
  fi
else
  echo "forge-backup: laptop offline — local copy only"
fi

ls -1t "$DEST"/forge-*.tar.gz 2>/dev/null | tail -n +$((KEEP+1)) | xargs -r rm -f
echo "forge-backup: $(ls -1 "$DEST"/forge-*.tar.gz 2>/dev/null | wc -l) local snapshot(s) retained"
