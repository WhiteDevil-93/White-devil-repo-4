"use strict";

const DEFAULT_RELAY = "https://84-12-112-249.sslip.io";
const DEFAULT_HUB = DEFAULT_RELAY + "/app/desktop/";
const DEFAULT_AGENT = DEFAULT_RELAY + "/app/venice/";
const DEFAULT_GALLERY = DEFAULT_RELAY + "/app/gallery/";

function defaults() {
  return {
    hubUrl: process.env.FORGE_HUB_URL || DEFAULT_HUB,
    relayUser: process.env.FORGE_RELAY_USER || "anon3",
    relayPass: process.env.FORGE_RELAY_PASS || "",
    laptopUser: process.env.FORGE_LAPTOP_USER || "laptop",
    laptopPass: process.env.FORGE_LAPTOP_PASS || "",
    veniceKey: process.env.FORGE_VENICE_KEY || process.env.VENICE_API_KEY || "",
    openRouterKey: process.env.FORGE_OPENROUTER_KEY || process.env.OPENROUTER_API_KEY || "",
    startDomain: process.env.WD_START_DOMAIN || "agent",
  };
}

/** Origin + scheme only (no path). */
function relayOrigin(raw) {
  let u = String(raw || "").trim();
  if (!u) u = DEFAULT_RELAY;
  if (!/^https?:\/\//i.test(u)) u = "https://" + u;
  try {
    const url = new URL(u);
    return url.origin;
  } catch {
    return DEFAULT_RELAY;
  }
}

function normalizeHubUrl(raw) {
  let u = String(raw || "").trim();
  if (!u) return DEFAULT_HUB;
  if (!/^https?:\/\//i.test(u)) u = "https://" + u;
  try {
    const url = new URL(u);
    if (url.pathname === "/" || url.pathname === "") url.pathname = "/app/desktop/";
    else if (!/\/app\/desktop\/?$/i.test(url.pathname)) {
      // Allow explicit /app/venice/ or other paths; only coerce bare host/root → desktop.
      if (/^\/app\/venice\/?$/i.test(url.pathname)) {
        /* keep agent path if someone stored it — still normalize trailing slash */
        if (!url.pathname.endsWith("/")) url.pathname += "/";
      } else if (!/^\/app\//i.test(url.pathname)) {
        url.pathname = url.pathname.replace(/\/+$/, "") + "/app/desktop/";
      } else if (!url.pathname.endsWith("/")) {
        url.pathname += "/";
      }
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

/** Forge Hub desktop shell (web — gallery/shell/ltx need the browser surface). */
function hubDesktopUrl(settings) {
  const origin = relayOrigin((settings && settings.hubUrl) || DEFAULT_HUB);
  return origin + "/app/desktop/";
}

/** Agent surface (Venice UI hosts on relay; chrome is native shell). */
function agentUrl(settings) {
  const origin = relayOrigin((settings && settings.hubUrl) || DEFAULT_HUB);
  return origin + "/app/venice/";
}

/** Clip gallery (relay media library). */
function galleryUrl(settings) {
  const origin = relayOrigin((settings && settings.hubUrl) || DEFAULT_HUB);
  return origin + "/app/gallery/";
}

/** Hub domain screen via desktop hash (home, term, ltx, …). */
function hubScreenUrl(settings, screenId) {
  const id = String(screenId || "home").replace(/^#/, "") || "home";
  return hubDesktopUrl(settings) + "#" + id;
}

function mergeSettings(raw) {
  const base = defaults();
  const src = raw && typeof raw === "object" ? raw : {};
  const start = String(src.startDomain || base.startDomain || "agent").toLowerCase();
  const allowed = new Set(["agent", "hub", "gallery", "you"]);
  return {
    hubUrl: normalizeHubUrl(src.hubUrl || base.hubUrl),
    relayUser: String(src.relayUser || base.relayUser).trim() || "anon3",
    relayPass: String(src.relayPass ?? base.relayPass),
    laptopUser: String(src.laptopUser || base.laptopUser).trim() || "laptop",
    laptopPass: String(src.laptopPass ?? base.laptopPass),
    veniceKey: String(src.veniceKey ?? base.veniceKey).trim(),
    openRouterKey: String(src.openRouterKey ?? base.openRouterKey).trim(),
    startDomain: allowed.has(start) ? start : "agent",
  };
}

module.exports = {
  DEFAULT_RELAY,
  DEFAULT_HUB,
  DEFAULT_AGENT,
  DEFAULT_GALLERY,
  defaults,
  normalizeHubUrl,
  relayOrigin,
  hubDesktopUrl,
  agentUrl,
  galleryUrl,
  hubScreenUrl,
  isLaptopPath,
  mergeSettings,
};
