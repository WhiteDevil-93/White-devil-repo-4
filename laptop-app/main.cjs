"use strict";

const { app, BrowserWindow, Menu, Tray, nativeImage, ipcMain, shell, session, net, dialog } = require("electron");

// Windows: GPU compositing often paints a blank/black window over a 401.
if (process.platform === "win32" || process.env.FORGE_DISABLE_GPU === "1") {
  app.disableHardwareAcceleration();
}
if (process.platform === "linux" && process.env.FORGE_SANDBOX !== "1") {
  app.commandLine.appendSwitch("no-sandbox");
  app.commandLine.appendSwitch("disable-gpu-sandbox");
  app.commandLine.appendSwitch("disable-dev-shm-usage");
}
const fs = require("fs");
const path = require("path");
const os = require("os");
const {
  mergeSettings,
  isLaptopPath,
  normalizeHubUrl,
  hubDesktopUrl,
  agentUrl,
  galleryUrl,
  hubScreenUrl,
  relayOrigin,
  DEFAULT_HUB,
} = require("./config.cjs");

const SETTINGS_FILE = () => path.join(app.getPath("userData"), "settings.json");
const ICON_PNG = path.join(__dirname, "icon.png");
const SIGNIN_HTML = path.join(__dirname, "signin.html");
const SHELL_HTML = path.join(__dirname, "shell.html");

let mainWindow = null;
let settingsWindow = null;
let tray = null;
let cached = null;
let showingSignIn = false;
let currentDomain = "agent";

function loadSettings() {
  try {
    cached = mergeSettings(JSON.parse(fs.readFileSync(SETTINGS_FILE(), "utf8")));
  } catch {
    cached = mergeSettings({});
  }
  return cached;
}

function saveSettings(raw) {
  const prev = cached || loadSettings();
  const incoming = raw && typeof raw === "object" ? { ...raw } : {};
  if (incoming.hubUrl === undefined || incoming.hubUrl === null || incoming.hubUrl === "") {
    incoming.hubUrl = prev.hubUrl;
  }
  cached = mergeSettings(incoming);
  fs.mkdirSync(app.getPath("userData"), { recursive: true });
  fs.writeFileSync(SETTINGS_FILE(), JSON.stringify(cached, null, 2), { encoding: "utf8", mode: 0o600 });
  try { fs.chmodSync(SETTINGS_FILE(), 0o600); } catch { /* windows */ }
  return cached;
}

function iconImage() {
  try {
    return nativeImage.createFromPath(ICON_PNG);
  } catch {
    return nativeImage.createEmpty();
  }
}

function hubTarget(settings) {
  return hubDesktopUrl(settings || cached || loadSettings());
}

async function probeLocalHub() {
  try {
    const r = await net.fetch("http://127.0.0.1:43173/api/manifest", { method: "GET" });
    return r.ok;
  } catch {
    return false;
  }
}

function relayAccessCandidates() {
  const home = os.homedir();
  return [
    path.join(home, "wan_outputs", "relay_access.txt"),
    path.join("C:", "Users", "anon3", "wan_outputs", "relay_access.txt"),
    path.join(home, "relay_access.txt"),
    path.join(process.env.USERPROFILE || "", "wan_outputs", "relay_access.txt"),
    path.join(home, ".config", "wan-relay-access.txt"),
  ].filter(Boolean);
}

function keyFileCandidates(kind) {
  const home = os.homedir();
  const winHome = process.env.USERPROFILE || path.join("C:", "Users", "anon3");
  if (kind === "venice") {
    return [
      path.join(home, ".config", "venice", "api_key"),
      path.join(home, ".venice_key"),
      path.join(winHome, ".config", "venice", "api_key"),
      path.join(winHome, ".venice_key"),
    ];
  }
  return [
    path.join(home, ".config", "openrouter", "api_key"),
    path.join(home, ".openrouter_key"),
    path.join(winHome, ".config", "openrouter", "api_key"),
    path.join(winHome, ".openrouter_key"),
  ];
}

function readFirstKeyFile(kind) {
  for (const p of keyFileCandidates(kind)) {
    try {
      if (!fs.existsSync(p)) continue;
      const t = fs.readFileSync(p, "utf8").trim();
      if (t.length >= 12) return t;
    } catch { /* next */ }
  }
  return "";
}

function parseRelayAccessFile(filePath) {
  const text = fs.readFileSync(filePath, "utf8");
  const out = {};
  for (const line of text.split(/\r?\n/)) {
    const m = line.match(/^\s*([A-Za-z0-9_]+)\s*=\s*(.*)\s*$/);
    if (m) out[m[1]] = m[2];
  }
  return out;
}

function tryImportRelayAccess() {
  for (const p of relayAccessCandidates()) {
    try {
      if (!fs.existsSync(p)) continue;
      const d = parseRelayAccessFile(p);
      if (!d.password && !d.user) continue;
      return {
        ok: true,
        path: p,
        user: d.user || "",
        password: d.password || "",
        laptop_user: d.laptop_user || "",
        laptop_password: d.laptop_password || "",
        url: d.url || "",
      };
    } catch { /* try next */ }
  }
  return { ok: false, message: "Could not find wan_outputs\\relay_access.txt" };
}

function seedApiKeys(s) {
  if (!s.veniceKey) s.veniceKey = readFirstKeyFile("venice");
  if (!s.openRouterKey) s.openRouterKey = readFirstKeyFile("openrouter");
  return s;
}

async function firstRunDefaults() {
  if (fs.existsSync(SETTINGS_FILE())) {
    const s = seedApiKeys(loadSettings());
    return saveSettings(s);
  }
  const s = mergeSettings({});
  if (!process.env.FORGE_HUB_URL && (await probeLocalHub())) {
    s.hubUrl = "http://127.0.0.1:43173/app/desktop/";
  }
  const imported = tryImportRelayAccess();
  if (imported.ok) {
    if (imported.user) s.relayUser = imported.user;
    if (imported.password) s.relayPass = imported.password;
    if (imported.laptop_user) s.laptopUser = imported.laptop_user;
    if (imported.laptop_password) s.laptopPass = imported.laptop_password;
    if (imported.url && !process.env.FORGE_HUB_URL) {
      s.hubUrl = normalizeHubUrl(imported.url);
    }
  }
  seedApiKeys(s);
  return saveSettings(s);
}

function authHeaders(s) {
  const headers = { "Content-Type": "application/json" };
  if (s.relayUser && s.relayPass) {
    headers.Authorization = "Basic " + Buffer.from(s.relayUser + ":" + s.relayPass).toString("base64");
  }
  return headers;
}

/** Push Venice / OpenRouter keys to the relay so Agent + LTX writers stay configured. */
async function syncKeysToRelay(settings) {
  const s = settings || cached || loadSettings();
  const origin = relayOrigin(s.hubUrl);
  const out = { venice: null, openRouter: null };
  if (s.veniceKey) {
    try {
      const r = await net.fetch(origin + "/api/venice/key", {
        method: "POST",
        headers: authHeaders(s),
        body: JSON.stringify({ key: s.veniceKey }),
      });
      out.venice = { ok: r.ok, status: r.status };
    } catch (e) {
      out.venice = { ok: false, message: e.message || String(e) };
    }
  }
  if (s.openRouterKey) {
    try {
      const r = await net.fetch(origin + "/api/gen/key", {
        method: "POST",
        headers: authHeaders(s),
        body: JSON.stringify({ key: s.openRouterKey }),
      });
      out.openRouter = { ok: r.ok, status: r.status };
    } catch (e) {
      out.openRouter = { ok: false, message: e.message || String(e) };
    }
  }
  return out;
}

function credsFor(url) {
  const s = cached || loadSettings();
  if (isLaptopPath(url)) return { user: s.laptopUser, pass: s.laptopPass };
  return { user: s.relayUser, pass: s.relayPass };
}

async function hubNeedsSignIn(settings) {
  const s = settings || cached || loadSettings();
  if (/127\.0\.0\.1|localhost/i.test(s.hubUrl || "") && !s.relayPass) return false;
  if (!s.relayPass) return true;
  try {
    const headers = {};
    if (s.relayUser && s.relayPass) {
      headers.Authorization = "Basic " + Buffer.from(s.relayUser + ":" + s.relayPass).toString("base64");
    }
    const r = await net.fetch(new URL("/api/manifest", hubTarget(s)).toString(), { headers });
    return r.status === 401 || r.status === 403;
  } catch {
    return !s.relayPass;
  }
}

function showSignIn() {
  if (!mainWindow || mainWindow.isDestroyed()) return;
  showingSignIn = true;
  mainWindow.loadFile(SIGNIN_HTML);
}

function sendNavigate(payload) {
  if (!mainWindow || mainWindow.isDestroyed() || showingSignIn) return;
  mainWindow.webContents.send("app:navigate", payload || { domain: "agent" });
}

function createMainWindow() {
  if (mainWindow && !mainWindow.isDestroyed()) {
    mainWindow.show();
    mainWindow.focus();
    return mainWindow;
  }
  const win = new BrowserWindow({
    width: 1440,
    height: 900,
    minWidth: 1024,
    minHeight: 680,
    backgroundColor: "#000000",
    title: "WhiteDevil",
    icon: ICON_PNG,
    autoHideMenuBar: false,
    show: false,
    webPreferences: {
      preload: path.join(__dirname, "preload.cjs"),
      contextIsolation: true,
      sandbox: true,
      nodeIntegration: false,
      spellcheck: false,
      webviewTag: false,
    },
  });
  win.webContents.setUserAgent(win.webContents.getUserAgent() + " WhiteDevilApp/1.1.0");
  win.once("ready-to-show", () => win.show());
  win.on("closed", () => { if (mainWindow === win) mainWindow = null; });
  win.webContents.setWindowOpenHandler(({ url }) => {
    if (/^https?:\/\//i.test(url)) shell.openExternal(url);
    return { action: "deny" };
  });
  // The preload hands the top frame getSettings(), which carries the relay and
  // laptop passwords. Remote hub screens run in sandboxed iframes and never see
  // it — but only as long as the top frame stays on our own files. Pin it there
  // so a redirect or a stray link cannot carry that bridge to a remote origin.
  win.webContents.on("will-navigate", (e, url) => {
    if (!url.startsWith("file://")) {
      e.preventDefault();
      if (/^https?:\/\//i.test(url)) shell.openExternal(url);
    }
  });
  win.webContents.on("did-fail-load", (_e, code, desc, url) => {
    if (showingSignIn) return;
    if (code === -3) return;
    if (/401|403|AUTH/i.test(String(desc)) || code === -2) {
      showSignIn();
    }
  });
  mainWindow = win;
  openAppShell();
  return win;
}

async function openAppShell() {
  if (!mainWindow || mainWindow.isDestroyed()) return;
  const s = cached || loadSettings();
  if (await hubNeedsSignIn(s)) {
    showSignIn();
    return;
  }
  showingSignIn = false;
  currentDomain = s.startDomain || "agent";
  mainWindow.loadFile(SHELL_HTML);
  mainWindow.setTitle("WhiteDevil");
}

function openSettings() {
  if (settingsWindow && !settingsWindow.isDestroyed()) {
    settingsWindow.show();
    settingsWindow.focus();
    return;
  }
  settingsWindow = new BrowserWindow({
    width: 520,
    height: 640,
    parent: mainWindow || undefined,
    modal: !!mainWindow,
    backgroundColor: "#000000",
    title: "WhiteDevil settings",
    icon: ICON_PNG,
    autoHideMenuBar: false,
    webPreferences: {
      preload: path.join(__dirname, "preload.cjs"),
      contextIsolation: true,
      sandbox: true,
      nodeIntegration: false,
    },
  });
  settingsWindow.loadFile(path.join(__dirname, "settings.html"));
  settingsWindow.on("closed", () => { settingsWindow = null; });
}

function buildMenu() {
  const isMac = process.platform === "darwin";
  const goDomain = (domain, screen) => {
    if (!mainWindow) createMainWindow();
    if (showingSignIn) {
      openSettings();
      return;
    }
    sendNavigate({ domain, screen });
    mainWindow.show();
  };
  const template = [
    ...(isMac ? [{ role: "appMenu" }] : []),
    {
      label: "WhiteDevil",
      submenu: [
        { label: "Agent", accelerator: "CmdOrCtrl+1", click: () => goDomain("agent") },
        { label: "Forge Hub", accelerator: "CmdOrCtrl+2", click: () => goDomain("hub", "home") },
        { label: "Gallery", accelerator: "CmdOrCtrl+4", click: () => goDomain("gallery") },
        { label: "You", accelerator: "CmdOrCtrl+3", click: () => goDomain("you") },
        { type: "separator" },
        {
          label: "Hub screens",
          submenu: [
            { label: "Hub Home", click: () => goDomain("hub", "home") },
            { label: "Shell", accelerator: "CmdOrCtrl+Shift+T", click: () => goDomain("hub", "term") },
            { label: "Renders", click: () => goDomain("hub", "renders") },
            { label: "Gallery", click: () => goDomain("gallery") },
            { label: "LTX", click: () => goDomain("hub", "ltx") },
            { label: "Colab", click: () => goDomain("hub", "colab") },
            { label: "Setup", click: () => goDomain("hub", "setup") },
          ],
        },
        { type: "separator" },
        { label: "Sign in…", accelerator: "CmdOrCtrl+Shift+L", click: () => showSignIn() },
        { label: "Settings…", accelerator: "CmdOrCtrl+,", click: () => openSettings() },
        { label: "Reload panes", accelerator: "CmdOrCtrl+R", click: () => sendNavigate({ reload: true }) },
        { label: "Hard reload shell", accelerator: "CmdOrCtrl+Shift+R", click: () => mainWindow && mainWindow.reload() },
        { label: "Setup", accelerator: "CmdOrCtrl+Shift+S", click: () => goDomain("hub", "setup") },
        { type: "separator" },
        isMac ? { role: "close" } : { role: "quit" },
      ],
    },
    { role: "editMenu" },
    { role: "viewMenu" },
    { role: "windowMenu" },
  ];
  Menu.setApplicationMenu(Menu.buildFromTemplate(template));
}

function createTray() {
  if (tray) return;
  const img = iconImage();
  if (img.isEmpty()) return;
  tray = new Tray(img.resize({ width: 18, height: 18 }));
  tray.setToolTip("WhiteDevil");
  tray.setContextMenu(Menu.buildFromTemplate([
    { label: "Open WhiteDevil", click: () => createMainWindow() },
    { label: "Agent", click: () => { createMainWindow(); sendNavigate({ domain: "agent" }); } },
    { label: "Forge Hub", click: () => { createMainWindow(); sendNavigate({ domain: "hub", screen: "home" }); } },
    { label: "Gallery", click: () => { createMainWindow(); sendNavigate({ domain: "gallery" }); } },
    { label: "Sign in…", click: () => { createMainWindow(); showSignIn(); } },
    { label: "Settings", click: () => openSettings() },
    { type: "separator" },
    { label: "Quit", click: () => app.quit() },
  ]));
  tray.on("click", () => createMainWindow());
}

ipcMain.handle("settings:get", () => loadSettings());
ipcMain.handle("settings:save", async (_e, raw) => {
  const s = saveSettings(raw);
  try { await syncKeysToRelay(s); } catch { /* offline ok */ }
  return s;
});
ipcMain.handle("settings:syncKeys", async () => syncKeysToRelay());
ipcMain.handle("hub:open", async () => {
  createMainWindow();
  await openAppShell();
  if (!showingSignIn) sendNavigate({ domain: "hub", screen: "home" });
  if (settingsWindow && !settingsWindow.isDestroyed() && mainWindow && !showingSignIn) {
    settingsWindow.close();
  }
  return true;
});
ipcMain.handle("agent:open", async () => {
  createMainWindow();
  await openAppShell();
  if (!showingSignIn) sendNavigate({ domain: "agent" });
  return true;
});

ipcMain.handle("dialog:pickMedia", async () => {
  // Always start in wan_outputs — never OneDrive Pictures/Screenshots.
  const home = process.env.USERPROFILE || process.env.HOME || os.homedir() || "";
  const wan = path.join(home, "wan_outputs");
  const start = fs.existsSync(wan) ? wan : home;
  const win = BrowserWindow.getFocusedWindow() || mainWindow;
  const opts = {
    title: "Attach media (wan_outputs)",
    defaultPath: start,
    properties: ["openFile", "multiSelections"],
    filters: [
      { name: "Media", extensions: ["png", "jpg", "jpeg", "webp", "gif", "mp4", "webm", "wav", "mp3", "pdf", "txt", "json", "md"] },
      { name: "All", extensions: ["*"] },
    ],
  };
  const r = win ? await dialog.showOpenDialog(win, opts) : await dialog.showOpenDialog(opts);
  if (r.canceled || !r.filePaths?.length) return [];
  const mimeOf = (fp) => {
    const e = path.extname(fp).toLowerCase();
    return ({
      ".png": "image/png", ".jpg": "image/jpeg", ".jpeg": "image/jpeg", ".webp": "image/webp",
      ".gif": "image/gif", ".mp4": "video/mp4", ".webm": "video/webm", ".wav": "audio/wav",
      ".mp3": "audio/mpeg", ".pdf": "application/pdf", ".txt": "text/plain", ".json": "application/json",
      ".md": "text/markdown",
    })[e] || "application/octet-stream";
  };
  const out = [];
  for (const fp of r.filePaths.slice(0, 8)) {
    try {
      const buf = fs.readFileSync(fp);
      if (buf.length > 12 * 1024 * 1024) continue; // skip huge binary for attach
      const mime = mimeOf(fp);
      out.push({
        name: path.basename(fp),
        mime,
        size: buf.length,
        dataUrl: "data:" + mime + ";base64," + buf.toString("base64"),
      });
    } catch (_) { /* skip unreadable */ }
  }
  return out;
});

ipcMain.handle("gallery:open", async () => {
  createMainWindow();
  await openAppShell();
  if (!showingSignIn) sendNavigate({ domain: "gallery" });
  return true;
});
ipcMain.handle("app:openShell", async () => {
  createMainWindow();
  await openAppShell();
  return true;
});
ipcMain.handle("app:getUrls", () => {
  const s = cached || loadSettings();
  return {
    agent: agentUrl(s),
    hub: hubDesktopUrl(s),
    hubHome: hubScreenUrl(s, "home"),
    gallery: galleryUrl(s),
  };
});
ipcMain.handle("app:agentUrl", () => agentUrl(cached || loadSettings()));
ipcMain.handle("app:galleryUrl", () => galleryUrl(cached || loadSettings()));
ipcMain.handle("app:hubUrlFor", (_e, screen) => hubScreenUrl(cached || loadSettings(), screen || "home"));
ipcMain.handle("app:setDomain", (_e, domain) => {
  const d = String(domain || "agent").toLowerCase();
  currentDomain = d === "hub" || d === "you" || d === "gallery" ? d : "agent";
  const s = cached || loadSettings();
  if (s.startDomain !== currentDomain) saveSettings({ ...s, startDomain: currentDomain });
  return currentDomain;
});
ipcMain.handle("app:navigate", (_e, payload) => {
  sendNavigate(payload || { domain: "agent" });
  return true;
});
ipcMain.handle("settings:test", async (_e, raw) => {
  const s = mergeSettings({ ...(cached || loadSettings()), ...(raw || {}) });
  const url = hubTarget(s);
  try {
    const headers = {};
    if (s.relayUser && s.relayPass) {
      headers.Authorization = "Basic " + Buffer.from(s.relayUser + ":" + s.relayPass).toString("base64");
    }
    const r = await net.fetch(new URL("/api/manifest", url).toString(), { headers });
    if (r.ok) return { ok: true, status: r.status, message: "Hub answered. Manifest loaded." };
    if (r.status === 401) return { ok: false, status: 401, message: "Wrong relay user/password (401)." };
    return { ok: false, status: r.status, message: "HTTP " + r.status };
  } catch (err) {
    return { ok: false, status: 0, message: err.message || String(err) };
  }
});
ipcMain.handle("relay:importAccess", () => tryImportRelayAccess());
ipcMain.handle("keys:importLocal", () => {
  const venice = readFirstKeyFile("venice");
  const openRouter = readFirstKeyFile("openrouter");
  return {
    ok: !!(venice || openRouter),
    veniceKey: venice,
    openRouterKey: openRouter,
    message: venice || openRouter ? "Loaded from local key files." : "No local Venice/OpenRouter key files found.",
  };
});

app.on("login", (event, _webContents, details, _authInfo, callback) => {
  event.preventDefault();
  const c = credsFor(details.url);
  if (!c.pass && !isLaptopPath(details.url)) {
    showSignIn();
    callback("", "");
    return;
  }
  callback(c.user || "", c.pass || "");
});

const gotLock = app.requestSingleInstanceLock();
if (!gotLock) {
  app.quit();
} else {
  app.on("second-instance", () => createMainWindow());
  app.whenReady().then(async () => {
    if (process.platform === "linux") app.commandLine.appendSwitch("gtk-version", "3");
    await firstRunDefaults();
    session.defaultSession.setPermissionRequestHandler((_wc, _perm, cb) => cb(true));
    // Agent/Hub panes are iframes inside the native shell — strip frame blockers from relay.
    session.defaultSession.webRequest.onHeadersReceived((details, callback) => {
      const headers = { ...(details.responseHeaders || {}) };
      for (const key of Object.keys(headers)) {
        const lk = key.toLowerCase();
        if (lk === "x-frame-options") delete headers[key];
        if (lk === "content-security-policy" || lk === "content-security-policy-report-only") {
          headers[key] = (headers[key] || []).map((v) =>
            String(v).replace(/frame-ancestors[^;]*;?/gi, "").replace(/frame-src[^;]*;?/gi, "")
          );
        }
      }
      callback({ responseHeaders: headers });
    });
    buildMenu();
    createTray();
    createMainWindow();
    // Best-effort: keep relay keys in sync with local settings after launch.
    syncKeysToRelay().catch(() => {});
    app.on("activate", () => createMainWindow());
  });
}

app.on("window-all-closed", () => {
  if (process.platform !== "darwin") app.quit();
});

if (process.env.FORGE_DEBUG === "1") {
  app.whenReady().then(() => {
    if (mainWindow) mainWindow.webContents.openDevTools({ mode: "detach" });
  });
}
