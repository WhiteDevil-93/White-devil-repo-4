"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const { normalizeHubUrl, isLaptopPath, mergeSettings, DEFAULT_HUB } = require("../config.cjs");

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

test("mergeSettings keeps empty passwords", () => {
  const s = mergeSettings({ hubUrl: "http://127.0.0.1:43173", relayPass: "", laptopPass: "" });
  assert.equal(s.relayPass, "");
  assert.equal(s.hubUrl, "http://127.0.0.1:43173/app/desktop/");
});
