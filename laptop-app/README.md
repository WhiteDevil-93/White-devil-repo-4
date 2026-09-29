# WhiteDevil desktop app

Native **WhiteDevil Agent** `.exe` (Electron). **Forge Hub is a domain** inside the app — not the product name.

Chrome is native (`shell.html`: Agent / Hub / You). Only surfaces that must stay web (Venice agent UI host, Hub desktop screens, Shell ttyd, gallery, LTX studio) load in iframes from the relay.

IPC bridge: `window.whiteDevil` (alias `forgeDesktop`) — settings, navigate, agent/hub URLs.

## On WhiteDevil (WSL)

```bash
git clone https://github.com/WhiteDevil-93/White-devil-repo-4.git ~/White-devil-repo-4
~/White-devil-repo-4/laptop-app/install-home.sh
cd ~/laptop-app && npm start
```

WSL needs a GUI (WSLg). If start fails with no DISPLAY: `export DISPLAY=:0`.

On first launch it probes `http://127.0.0.1:43173`, else the relay. Defaults open the **Agent** domain (`/app/venice/`). Hub is `/app/desktop/#…`.

**WhiteDevil → You** or **Ctrl+,** stores hub URL and passwords in OS user-data (`settings.json`, mode 600).

| Setting | What |
|---|---|
| Hub URL | Relay origin (stored as `/app/desktop/`) |
| Relay user / password | Caddy basic auth (`relay_access.txt`) |
| Laptop user / password | `/laptop/term/` and `/laptop/files/` only |

## Keyboard

- `Ctrl+1` Agent
- `Ctrl+2` Forge Hub
- `Ctrl+3` You
- `Ctrl+Shift+T` Hub → Shell
- `Ctrl+,` Settings window
- `Ctrl+R` Reload

## Windows installer / portable .exe

```powershell
Set-Location -LiteralPath 'A:\New folder (4)\white-devil-repo-4\laptop-app'
npm install
npm run dist:win
```

Artifacts in `laptop-app\dist\`:

| File | What |
|---|---|
| `WhiteDevil.exe` | Portable |
| `WhiteDevil-Setup-1.1.0.exe` | NSIS installer |
| `win-unpacked\WhiteDevil.exe` | Unpacked debug |

## Env (optional)

- `FORGE_HUB_URL` — override default hub (first run only if no settings file yet)
- `FORGE_RELAY_USER` / `FORGE_RELAY_PASS`
- `FORGE_LAPTOP_USER` / `FORGE_LAPTOP_PASS`
- `WD_START_DOMAIN` — `agent` (default), `hub`, or `you`
- `FORGE_DEBUG=1` — open DevTools
