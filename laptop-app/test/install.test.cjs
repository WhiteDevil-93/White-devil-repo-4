"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("fs");
const path = require("path");

test("install-home.sh copies into ~/laptop-app", () => {
  const sh = fs.readFileSync(path.join(__dirname, "..", "install-home.sh"), "utf8");
  assert.match(sh, /HOME\/laptop-app/);
  assert.match(sh, /forge-hub/);
  assert.match(sh, /WhiteDevil-93\/White-devil-repo-4/);
});

test("run.sh refuses a missing package.json", () => {
  const sh = fs.readFileSync(path.join(__dirname, "..", "run.sh"), "utf8");
  assert.match(sh, /package\.json/);
  assert.match(sh, /install-home\.sh/);
});

test("packaged app includes start.html sign-in page", () => {
  const pkg = JSON.parse(fs.readFileSync(path.join(__dirname, "..", "package.json"), "utf8"));
  assert.ok(pkg.build.files.includes("start.html"));
  const html = fs.readFileSync(path.join(__dirname, "..", "start.html"), "utf8");
  assert.match(html, /relay_access\.txt/);
  assert.match(html, /Save and open/);
});

test("Windows dist produces named Setup and Portable exes", () => {
  const pkg = JSON.parse(fs.readFileSync(path.join(__dirname, "..", "package.json"), "utf8"));
  assert.match(pkg.scripts["dist:win"], /--win nsis portable/);
  assert.equal(pkg.build.nsis.artifactName, "Forge-Hub-Setup.${ext}");
  assert.equal(pkg.build.portable.artifactName, "Forge-Hub-Portable.${ext}");
  const readme = fs.readFileSync(path.join(__dirname, "..", "README.md"), "utf8");
  assert.match(readme, /Forge-Hub-Setup\.exe/);
  assert.match(readme, /Forge-Hub-Portable\.exe/);
  assert.match(readme, /Another program is currently using this file/);
});
