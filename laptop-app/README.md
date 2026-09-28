# Forge Hub laptop app

Native desktop window for Forge Hub (Venice Agent, Shell, renders, Setup). This is the laptop app — not a browser tab, and not the Android APK.

It loads the same Hub UI the phone uses, with relay basic-auth handled by the window so iframes (Shell / Files) keep working.

## Run on the laptop (WSL or Windows)

```bash
cd laptop-app
npm install
npm start
```

On first launch it looks for a hub at `http://127.0.0.1:43173/app/desktop/`. If that is not up, it uses the relay:

`https://84-12-112-249.sslip.io/app/desktop/`

**Forge Hub → Settings…** (`Ctrl+,`) stores hub URL and passwords in the OS user-data folder (`settings.json`, mode 600). Nothing is committed.

| Setting | What |
|---|---|
| Hub URL | Relay site or a local `uvicorn` hub |
| Relay user / password | Caddy basic auth (`relay_access.txt`) |
| Laptop user / password | `/laptop/term/` and `/laptop/files/` only |

## Keyboard

- `Ctrl+1` Home
- `Ctrl+Shift+V` Venice Agent
- `Ctrl+Shift+T` Shell
- `Ctrl+,` Settings
- `Ctrl+R` Reload

## Windows installer

From Windows or WSL with a Windows electron-builder target:

```bash
cd laptop-app
npm run dist:win
```

The `.exe` lands in `laptop-app/dist/`. Linux AppImage: `npm run dist:linux`.

## Env (optional)

- `FORGE_HUB_URL` — override default hub (first run only if no settings file yet)
- `FORGE_RELAY_USER` / `FORGE_RELAY_PASS`
- `FORGE_LAPTOP_USER` / `FORGE_LAPTOP_PASS`
- `FORGE_DEBUG=1` — open DevTools
