"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const path = require("path");
const { pathToFileURL } = require("url");
const { isAllowedNavigation, isTrustedAppPage, sameOrigin } = require("../config.cjs");

const APP = path.resolve(__dirname, "..");
const HUB = "https://84-12-112-249.sslip.io/app/desktop/";

test("the window may move around inside the hub", () => {
  for (const u of [
    "https://84-12-112-249.sslip.io/app/desktop/#venice",
    "https://84-12-112-249.sslip.io/laptop/files/",
    "https://84-12-112-249.sslip.io/app/gallery/?x=1",
  ]) assert.equal(isAllowedNavigation(u, HUB, APP), true, u);
});

test("the window may not leave the hub: other hosts, other schemes, look-alikes", () => {
  for (const u of [
    "https://evil.example/",
    "http://84-12-112-249.sslip.io/app/desktop/",          // same host, downgraded scheme = another origin
    "https://84-12-112-249.sslip.io.evil.example/",
    "https://84-12-112-249.sslip.io:8443/",
    "javascript:alert(1)",
    "data:text/html,<script>1</script>",
    "file:///etc/passwd",
    "not a url",
    "",
  ]) {
    assert.equal(isAllowedNavigation(u, HUB, APP), false, u);
  }
});

test("the app's own pages are allowed, and only those", () => {
  assert.equal(isAllowedNavigation(pathToFileURL(path.join(APP, "start.html")).href + "?msg=x", HUB, APP), true);
  assert.equal(isAllowedNavigation(pathToFileURL(path.join(APP, "settings.html")).href, HUB, APP), true);
  assert.equal(isAllowedNavigation(pathToFileURL(path.join(APP, "main.cjs")).href, HUB, APP), false);
  assert.equal(isAllowedNavigation(pathToFileURL(path.join(APP, "package.json")).href, HUB, APP), false);
});

test("only start.html and settings.html may use the settings bridge", () => {
  assert.equal(isTrustedAppPage(pathToFileURL(path.join(APP, "settings.html")).href, APP), true);
  assert.equal(isTrustedAppPage(pathToFileURL(path.join(APP, "start.html")).href + "?msg=hi", APP), true);
  // the hub page, another site, a missing sender, and a file page that is not one of the two
  for (const u of [HUB, "https://evil.example/settings.html", undefined, null, "", "about:blank",
    pathToFileURL(path.join(APP, "icon.svg")).href,
    pathToFileURL(path.join(APP, "..", "settings.html")).href,              // a sibling directory's file
    pathToFileURL(path.join(APP, "node_modules", "x", "settings.html")).href, // a nested file of the same name
    pathToFileURL(path.join(path.dirname(APP), "evil", "settings.html")).href]) {
    assert.equal(isTrustedAppPage(u, APP), false, String(u));
  }
});

test("same-origin is strict and never true for opaque origins", () => {
  assert.equal(sameOrigin("https://a.test/x", "https://a.test/y"), true);
  assert.equal(sameOrigin("https://a.test/x", "https://b.test/x"), false);
  assert.equal(sameOrigin("data:text/plain,a", "data:text/plain,a"), false);
  assert.equal(sameOrigin("junk", "junk"), false);
});
