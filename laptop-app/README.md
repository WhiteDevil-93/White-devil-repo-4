# Forge Hub laptop app

Native desktop window for Forge Hub (Venice Agent, Shell, renders, Setup). This is the laptop app — not a browser tab, and not the Android APK.

It loads the same Hub UI the phone uses, with relay basic-auth handled by the window so iframes (Shell / Files) keep working.

## On WhiteDevil (WSL)

`cd laptop-app` from `~` fails. There is no `~/laptop-app` until you clone the repo and install it. npm then looks for `/home/anon3/package.json` and errors with ENOENT.

**Once, from a home prompt:**

```bash
git clone https://github.com/WhiteDevil-93/White-devil-repo-4.git ~/White-devil-repo-4
~/White-devil-repo-4/laptop-app/install-home.sh
```

If the repo is already cloned somewhere else:

```bash
# find it, then:
/path/to/White-devil-repo-4/laptop-app/install-home.sh
```

**Every launch after that:**

```bash
cd ~/laptop-app
npm start
```

or, if `~/.local/bin` is on your PATH:

```bash
forge-hub
```

WSL needs a GUI (WSLg). If start fails with no DISPLAY: `export DISPLAY=:0`.

On first launch it looks for a hub at `http://127.0.0.1:43173/app/desktop/`. If that is not up, it uses the relay:

`https://84-12-112-249.sslip.io/app/desktop/`

**Forge Hub → Settings…** (`Ctrl+,`) stores hub URL and passwords in the OS user-data folder (`settings.json`, mode 600). Nothing is committed.

| Setting | What |
|---|---|
| Hub URL | Relay site or a local `uvicorn` hub |
| Relay user / password | Caddy basic auth (`relay_access.txt`) |
| Laptop user / password | `/laptop/term/` and `/laptop/files/` only |

## From the git clone (no home copy)

```bash
cd ~/White-devil-repo-4/laptop-app
npm install
npm start
```

## Keyboard

- `Ctrl+1` Home
- `Ctrl+Shift+V` Venice Agent
- `Ctrl+Shift+T` Shell
- `Ctrl+,` Settings
- `Ctrl+R` Reload

## Windows: double-click the installer

1. Download **Forge-Hub-Setup.exe** (NSIS installer) or **Forge-Hub-Portable.exe** (no install).
2. Double-click it. First run opens `https://84-12-112-249.sslip.io/app/desktop/`.
3. Press **Ctrl+,** to store relay / laptop passwords (same fields as Settings in the app).

WSL users can still use `install-home.sh` (see above) instead of the `.exe`.

### Build the `.exe` yourself

From **Windows PowerShell** (repo clone, not `cd laptop-app` from `~`):

```powershell
cd path\to\White-devil-repo-4\laptop-app
npm install
npm run dist:win
```

Or from Linux / WSL in this folder: `./build-win.sh` (same as `npm run dist:win`). Output is `dist/Forge-Hub-Setup.exe` and `dist/Forge-Hub-Portable.exe`. Linux AppImage: `npm run dist:linux`.

## Env (optional)

- `FORGE_HUB_URL` — override default hub (first run only if no settings file yet)
- `FORGE_RELAY_USER` / `FORGE_RELAY_PASS`
- `FORGE_LAPTOP_USER` / `FORGE_LAPTOP_PASS`
- `FORGE_DEBUG=1` — open DevTools
