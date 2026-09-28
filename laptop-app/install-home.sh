#!/usr/bin/env bash
# Put Forge Hub on the laptop at ~/laptop-app so `cd ~/laptop-app && npm start` works
# from a home prompt. `cd laptop-app` from ~ fails — that folder is not in $HOME
# until this script copies it there (or you cd into the git clone).
set -euo pipefail

SRC="$(cd "$(dirname "$0")" && pwd)"
DEST="${FORGE_HOME:-$HOME/laptop-app}"
BIN_DIR="${HOME}/.local/bin"

for f in package.json main.cjs preload.cjs config.cjs settings.html icon.png run.sh; do
  if [[ ! -f "$SRC/$f" ]]; then
    echo "Run this from the laptop-app folder inside the git clone, not from ~." >&2
    echo "  git clone https://github.com/WhiteDevil-93/White-devil-repo-4.git" >&2
    echo "  ~/White-devil-repo-4/laptop-app/install-home.sh" >&2
    exit 1
  fi
done

mkdir -p "$DEST"
src_real="$(realpath "$SRC")"
dest_real="$(realpath "$DEST")"
if [[ "$src_real" != "$dest_real" ]]; then
  if command -v rsync >/dev/null 2>&1; then
    rsync -a --delete --exclude node_modules --exclude dist --exclude '.npm' "$SRC/" "$DEST/"
  else
    find "$DEST" -mindepth 1 -maxdepth 1 ! -name node_modules ! -name dist -exec rm -rf {} +
    tar -C "$SRC" --exclude=node_modules --exclude=dist --exclude=.npm -cf - . | tar -C "$DEST" -xf -
  fi
fi

chmod +x "$DEST/run.sh" "$DEST/install-home.sh"

mkdir -p "$BIN_DIR"
cat > "$BIN_DIR/forge-hub" <<EOF
#!/usr/bin/env bash
exec "$DEST/run.sh" "\$@"
EOF
chmod +x "$BIN_DIR/forge-hub"

echo "Installed to $DEST"
if [[ ! -d "$DEST/node_modules/electron" ]]; then
  echo "Installing npm packages (Electron)…"
  (cd "$DEST" && npm install)
fi

if ! command -v forge-hub >/dev/null 2>&1; then
  case ":$PATH:" in
    *":$BIN_DIR:"*) ;;
    *)
      echo
      echo "Add this to ~/.bashrc then open a new terminal:"
      echo "  export PATH=\"\$HOME/.local/bin:\$PATH\""
      ;;
  esac
fi

echo
echo "Start the window:"
echo "  forge-hub"
echo "or:"
echo "  cd ~/laptop-app && npm start"
echo
echo "First run opens the relay Hub. Ctrl+, stores passwords. WSL needs a GUI (WSLg)."
