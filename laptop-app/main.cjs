"use strict";

const { app, BrowserWindow, Menu, Tray, nativeImage, ipcMain, shell, session, net } = require("electron");

if (process.platform === "linux" && process.env.FORGE_SANDBOX !== "1") {
  app.commandLine.appendSwitch("no-sandbox");
  app.commandLine.appendSwitch("disable-gpu-sandbox");
  app.commandLine.appendSwitch("disable-dev-shm-usage");
}
const fs = require("fs");
const path = require("path");
const { mergeSettings, isLaptopPath, normalizeHubUrl, DEFAULT_HUB } = require("./config.cjs");

const SETTINGS_FILE = () => path.join(app.getPath("userData"), "settings.json");
const ICON_PNG = path.join(__dirname, "icon.png");

let mainWindow = null;
let settingsWindow = null;
let tray = null;
let cached = null;

function loadSettings() {
  try {
    cached = mergeSettings(JSON.parse(fs.readFileSync(SETTINGS_FILE(), "utf8")));
  } catch {
    cached = mergeSettings({});
  }
  return cached;
}

function saveSettings(raw) {
  cached = mergeSettings(raw);
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
  return normalizeHubUrl(settings.hubUrl || DEFAULT_HUB);
}

async function probeLocalHub() {
  try {
    const r = await net.fetch("http://127.0.0.1:43173/api/manifest", { method: "GET" });
    return r.ok;
  } catch {
    return false;
  }
}

async function firstRunDefaults() {
  if (fs.existsSync(SETTINGS_FILE())) return loadSettings();
  const s = mergeSettings({});
  if (!process.env.FORGE_HUB_URL && (await probeLocalHub())) {
    s.hubUrl = "http://127.0.0.1:43173/app/desktop/";
  }
  return saveSettings(s);
}

function credsFor(url) {
  const s = cached || loadSettings();
  if (isLaptopPath(url)) return { user: s.laptopUser, pass: s.laptopPass };
  return { user: s.relayUser, pass: s.relayPass };
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
    backgroundColor: "#0a0b0f",
    title: "Forge Hub",
    icon: ICON_PNG,
    autoHideMenuBar: true,
    show: false,
    webPreferences: {
      preload: path.join(__dirname, "preload.cjs"),
      contextIsolation: true,
      sandbox: true,
      nodeIntegration: false,
      spellcheck: false,
    },
  });
  win.webContents.setUserAgent(win.webContents.getUserAgent() + " ForgeHubApp/1.0");
  win.once("ready-to-show", () => win.show());
  win.on("closed", () => { if (mainWindow === win) mainWindow = null; });
  win.webContents.setWindowOpenHandler(({ url }) => {
    if (/^https?:\/\//i.test(url)) shell.openExternal(url);
    return { action: "deny" };
  });
  mainWindow = win;
  loadHub();
  return win;
}

function loadHub() {
  if (!mainWindow || mainWindow.isDestroyed()) return;
  const s = cached || loadSettings();
  mainWindow.loadURL(hubTarget(s));
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
    backgroundColor: "#0a0b0f",
    title: "Forge Hub settings",
    icon: ICON_PNG,
    autoHideMenuBar: true,
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
  const go = (hash) => {
    if (!mainWindow) createMainWindow();
    const base = hubTarget(cached || loadSettings()).replace(/#.*$/, "");
    mainWindow.loadURL(base + (hash ? "#" + hash : ""));
    mainWindow.show();
  };
  const template = [
    ...(isMac ? [{ role: "appMenu" }] : []),
    {
      label: "Forge Hub",
      submenu: [
        { label: "Home", accelerator: "CmdOrCtrl+1", click: () => go("home") },
        { label: "Venice Agent", accelerator: "CmdOrCtrl+Shift+V", click: () => go("venice") },
        { label: "Shell", accelerator: "CmdOrCtrl+Shift+T", click: () => go("term") },
        { label: "Renders", click: () => go("renders") },
        { type: "separator" },
        { label: "Settings…", accelerator: "CmdOrCtrl+,", click: () => openSettings() },
        { label: "Reload", accelerator: "CmdOrCtrl+R", click: () => mainWindow && mainWindow.reload() },
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
  tray.setToolTip("Forge Hub");
  tray.setContextMenu(Menu.buildFromTemplate([
    { label: "Open Forge Hub", click: () => createMainWindow() },
    { label: "Venice Agent", click: () => { createMainWindow(); mainWindow.loadURL(hubTarget(cached || loadSettings()) + "#venice"); } },
    { label: "Settings", click: () => openSettings() },
    { type: "separator" },
    { label: "Quit", click: () => app.quit() },
  ]));
  tray.on("click", () => createMainWindow());
}

ipcMain.handle("settings:get", () => loadSettings());
ipcMain.handle("settings:save", (_e, raw) => {
  const s = saveSettings(raw);
  loadHub();
  return s;
});
ipcMain.handle("hub:open", () => {
  createMainWindow();
  if (settingsWindow && !settingsWindow.isDestroyed() && mainWindow) settingsWindow.close();
  return true;
});
ipcMain.handle("settings:test", async (_e, raw) => {
  const s = mergeSettings(raw || loadSettings());
  const url = hubTarget(s);
  try {
    const headers = {};
    if (s.relayUser && s.relayPass) {
      headers.Authorization = "Basic " + Buffer.from(s.relayUser + ":" + s.relayPass).toString("base64");
    }
    const r = await net.fetch(new URL("/api/manifest", url).toString(), { headers });
    if (r.ok) return { ok: true, status: r.status, message: "Hub answered. Manifest loaded." };
    if (r.status === 401) return { ok: false, status: 401, message: "Hub asked for a password. Check relay user/pass." };
    return { ok: false, status: r.status, message: "HTTP " + r.status };
  } catch (err) {
    return { ok: false, status: 0, message: err.message || String(err) };
  }
});

app.on("login", (event, _webContents, details, _authInfo, callback) => {
  event.preventDefault();
  const c = credsFor(details.url);
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
    buildMenu();
    createTray();
    createMainWindow();
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
