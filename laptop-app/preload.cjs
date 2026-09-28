"use strict";

const { contextBridge, ipcRenderer } = require("electron");

contextBridge.exposeInMainWorld("forgeDesktop", {
  getSettings: () => ipcRenderer.invoke("settings:get"),
  saveSettings: (s) => ipcRenderer.invoke("settings:save", s),
  testHub: (s) => ipcRenderer.invoke("settings:test", s),
  openHub: () => ipcRenderer.invoke("hub:open"),
  platform: process.platform,
});
