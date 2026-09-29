"use strict";

const { contextBridge, ipcRenderer } = require("electron");

const api = {
  getSettings: () => ipcRenderer.invoke("settings:get"),
  saveSettings: (s) => ipcRenderer.invoke("settings:save", s),
  syncKeys: () => ipcRenderer.invoke("settings:syncKeys"),
  testHub: (s) => ipcRenderer.invoke("settings:test", s),
  openHub: () => ipcRenderer.invoke("hub:open"),
  openAgent: () => ipcRenderer.invoke("agent:open"),
  openGallery: () => ipcRenderer.invoke("gallery:open"),
  openShell: () => ipcRenderer.invoke("app:openShell"),
  importRelayAccess: () => ipcRenderer.invoke("relay:importAccess"),
  importLocalKeys: () => ipcRenderer.invoke("keys:importLocal"),
  getUrls: () => ipcRenderer.invoke("app:getUrls"),
  agentUrl: () => ipcRenderer.invoke("app:agentUrl"),
  galleryUrl: () => ipcRenderer.invoke("app:galleryUrl"),
  hubUrlFor: (screen) => ipcRenderer.invoke("app:hubUrlFor", screen),
  setDomain: (domain) => ipcRenderer.invoke("app:setDomain", domain),
  navigate: (payload) => ipcRenderer.invoke("app:navigate", payload),
  onNavigate: (cb) => {
    const handler = (_e, payload) => cb(payload);
    ipcRenderer.on("app:navigate", handler);
    return () => ipcRenderer.removeListener("app:navigate", handler);
  },
  platform: process.platform,
  pickMedia: () => ipcRenderer.invoke("dialog:pickMedia"),
};

contextBridge.exposeInMainWorld("whiteDevil", api);
// Back-compat for signin/settings pages written against forgeDesktop
contextBridge.exposeInMainWorld("forgeDesktop", api);
