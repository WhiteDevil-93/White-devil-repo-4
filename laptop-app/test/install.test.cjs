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
