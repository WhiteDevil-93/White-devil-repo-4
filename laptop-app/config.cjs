"use strict";

const path = require("path");
const { fileURLToPath } = require("url");

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

function maySendHubCredentials(targetUrl, hubUrl) {
  try {
    const target = new URL(String(targetUrl));
    const hub = new URL(normalizeHubUrl(hubUrl));
    if (target.origin !== hub.origin) return false;
    if (target.protocol === "https:") return true;
    return target.protocol === "http:" && ["127.0.0.1", "localhost", "[::1]"].includes(target.hostname);
  } catch {
    return false;
  }
}

// ---------------------------------------------------------------------------
// Which pages may talk to the main process.
//
// The preload bridge (preload.cjs) hands whatever page is in the window the stored relay and
// laptop passwords. The window shows the hub, and the hub shows content it did not write (agent
// output, render names, links). Without these checks a link or redirect that moved the window to
// any other site gave that site the bridge and the passwords; this was reproduced against a stand-in
// hub before the fix. Only the app's own two file pages may use the bridge, and the window may not
// leave the hub's origin.
// ---------------------------------------------------------------------------

const TRUSTED_APP_PAGES = new Set(["start.html", "settings.html"]);

function sameOrigin(a, b) {
  try {
    const ua = new URL(String(a));
    const ub = new URL(String(b));
    return ua.origin !== "null" && ua.origin === ub.origin;
  } catch {
    return false;
  }
}

// The name of a file: URL that sits directly in appDir, else null.
function appPageName(rawUrl, appDir) {
  let url;
  try {
    url = new URL(String(rawUrl || ""));
  } catch {
    return null;
  }
  if (url.protocol !== "file:") return null;
  let file;
  try {
    file = fileURLToPath(url);
  } catch {
    return null;
  }
  const rel = path.relative(appDir, file);
  if (!rel || rel.startsWith("..") || path.isAbsolute(rel) || rel.includes(path.sep)) return null;
  return rel;
}

function isTrustedAppPage(rawUrl, appDir) {
  const name = appPageName(rawUrl, appDir);
  return name !== null && TRUSTED_APP_PAGES.has(name);
}

// A navigation the window may make by itself: within the hub's origin, or to the app's own pages.
function isAllowedNavigation(targetUrl, hubUrl, appDir) {
  return sameOrigin(targetUrl, hubUrl) || isTrustedAppPage(targetUrl, appDir);
}

module.exports = {
  isTrustedAppPage,
  isAllowedNavigation,
  sameOrigin,
  DEFAULT_RELAY,
  DEFAULT_HUB,
  defaults,
  normalizeHubUrl,
  isLaptopPath,
  mergeSettings,
  needsRelayPassword,
  hubHost,
  maySendHubCredentials,
};
