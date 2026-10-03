#!/bin/bash
# Forward ComfyUI on the Colab runtime (8188) to the relay at 127.0.0.1:18288.
# `colab ssh --proxy-mode -s colab` creates (and bills) a runtime when none exists, so only connect
# while one is active; otherwise idle and let systemd retry.
export PATH="$HOME/.local/bin:/usr/local/bin:/usr/bin:/bin"
active=$(timeout 90 colab usage 2>/dev/null | sed -n 's/.*Active assignments: *\([0-9]*\).*/\1/p')
if [ "${active:-0}" -lt 1 ] || [ -f "$HOME/wan/paused_comfy" ]; then
  sleep 120
  exit 0
fi
exec ssh -N -i "$HOME/.ssh/id_ed25519" -o BatchMode=yes -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null \
  -o LogLevel=ERROR -o ServerAliveInterval=20 -o ServerAliveCountMax=3 -o ExitOnForwardFailure=yes \
  -o "ProxyCommand=$HOME/.local/bin/colab ssh --proxy-mode -s colab" \
  -L 127.0.0.1:18288:127.0.0.1:8188 root@colab
