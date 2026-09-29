"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const {
  normalizeHubUrl,
  isLaptopPath,
  mergeSettings,
  DEFAULT_HUB,
  agentUrl,
  hubDesktopUrl,
  hubScreenUrl,
  relayOrigin,
} = require("../config.cjs");

test("normalizeHubUrl fills /app/desktop/", () => {
  assert.equal(normalizeHubUrl("https://84-12-112-249.sslip.io"), "https://84-12-112-249.sslip.io/app/desktop/");
  assert.equal(normalizeHubUrl("http://127.0.0.1:43173"), "http://127.0.0.1:43173/app/desktop/");
  assert.equal(normalizeHubUrl("http://127.0.0.1:43173/app/desktop"), "http://127.0.0.1:43173/app/desktop/");
});

test("normalizeHubUrl rejects junk with default", () => {
  assert.equal(normalizeHubUrl(""), DEFAULT_HUB);
  assert.equal(normalizeHubUrl("::::"), DEFAULT_HUB);
});

test("isLaptopPath", () => {
  assert.equal(isLaptopPath("https://host/laptop/term/"), true);
  assert.equal(isLaptopPath("https://host/app/venice/"), false);
});

test("mergeSettings keeps empty passwords and defaults to agent", () => {
  const s = mergeSettings({ hubUrl: "http://127.0.0.1:43173", relayPass: "", laptopPass: "" });
  assert.equal(s.relayPass, "");
  assert.equal(s.hubUrl, "http://127.0.0.1:43173/app/desktop/");
  assert.equal(s.startDomain, "agent");
});

test("agent hub gallery URLs share origin", () => {
  const { galleryUrl } = require("../config.cjs");
  const s = mergeSettings({ hubUrl: "https://84-12-112-249.sslip.io" });
  assert.equal(relayOrigin(s.hubUrl), "https://84-12-112-249.sslip.io");
  assert.equal(agentUrl(s), "https://84-12-112-249.sslip.io/app/venice/");
  assert.equal(hubDesktopUrl(s), "https://84-12-112-249.sslip.io/app/desktop/");
  assert.equal(hubScreenUrl(s, "term"), "https://84-12-112-249.sslip.io/app/desktop/#term");
  assert.equal(galleryUrl(s), "https://84-12-112-249.sslip.io/app/gallery/");
});

test("mergeSettings keeps api keys and gallery domain", () => {
  const s = mergeSettings({
    veniceKey: "VENICE_TEST_KEY_123",
    openRouterKey: "sk-or-v1-testkey1234567890",
    startDomain: "gallery",
  });
  assert.equal(s.veniceKey, "VENICE_TEST_KEY_123");
  assert.equal(s.openRouterKey, "sk-or-v1-testkey1234567890");
  assert.equal(s.startDomain, "gallery");
});
