"use strict";

const DEFAULT_RELAY = "https://84-12-112-249.sslip.io";
const DEFAULT_HUB = DEFAULT_RELAY + "/app/desktop/";

function defaults() {
  return {
    hubUrl: process.env.FORGE_HUB_URL || DEFAULT_HUB,
    relayUser: process.env.FORGE_RELAY_USER || "anon3",
    relayPass: process.env.FORGE_RELAY_PASS || "",
    laptopUser: process.env.FORGE_LAPTOP_USER || "laptop",
    laptopPass: process.env.FORGE_LAPTOP_PASS || "",
  };
}

function normalizeHubUrl(raw) {
  let u = String(raw || "").trim();
  if (!u) return DEFAULT_HUB;
  if (!/^https?:\/\//i.test(u)) u = "https://" + u;
  try {
    const url = new URL(u);
    if (url.pathname === "/" || url.pathname === "") url.pathname = "/app/desktop/";
    else if (!/\/app\/desktop\/?$/i.test(url.pathname)) {
      url.pathname = url.pathname.replace(/\/+$/, "") + "/app/desktop/";
    } else if (!url.pathname.endsWith("/")) {
      url.pathname += "/";
    }
    url.hash = "";
    url.search = "";
    return url.toString();
  } catch {
    return DEFAULT_HUB;
  }
}

function isLaptopPath(targetUrl) {
  try {
    return new URL(targetUrl).pathname.startsWith("/laptop/");
  } catch {
    return /\/laptop\//.test(String(targetUrl || ""));
  }
}

function mergeSettings(raw) {
  const base = defaults();
  const src = raw && typeof raw === "object" ? raw : {};
  return {
    hubUrl: normalizeHubUrl(src.hubUrl || base.hubUrl),
    relayUser: String(src.relayUser || base.relayUser).trim() || "anon3",
    relayPass: String(src.relayPass ?? base.relayPass),
    laptopUser: String(src.laptopUser || base.laptopUser).trim() || "laptop",
    laptopPass: String(src.laptopPass ?? base.laptopPass),
  };
}

function needsRelayPassword(raw) {
  const s = mergeSettings(raw);
  try {
    const u = new URL(s.hubUrl);
    if (u.hostname === "127.0.0.1" || u.hostname === "localhost") return false;
  } catch {
    return true;
  }
  return !(s.relayPass || "").trim();
}

function hubHost(raw) {
  try {
    return new URL(normalizeHubUrl(raw)).host;
  } catch {
    return "";
  }
}

module.exports = {
  DEFAULT_RELAY,
  DEFAULT_HUB,
  defaults,
  normalizeHubUrl,
  isLaptopPath,
  mergeSettings,
  needsRelayPassword,
  hubHost,
};
