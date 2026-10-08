"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const vm = require("node:vm");
const fs = require("node:fs");
const path = require("node:path");

test("Electron auth callbacks keep synthetic credentials on the configured HTTPS origin", () => {
  const appDir = path.resolve(__dirname, "..");
  const handlers = {};
  let headerHook;
  const electron = {
    app: {
      commandLine: { appendSwitch() {} },
      disableHardwareAcceleration() {},
      getPath() { return "/unused"; },
      on(name, handler) { handlers[name] = handler; },
      requestSingleInstanceLock() { return false; },
      quit() {},
    },
    ipcMain: { handle() {} },
    session: { defaultSession: { webRequest: { onBeforeSendHeaders(_filter, handler) { headerHook = handler; } } } },
  };
  const settings = {
    hubUrl: "https://hub.example/app/desktop/",
    relayUser: "synthetic-user",
    relayPass: "SYNTHETIC-SECRET",
    laptopUser: "synthetic-laptop",
    laptopPass: "SYNTHETIC-LAPTOP-SECRET",
  };
  const fakeFs = { readFileSync() { return JSON.stringify(settings); } };
  const context = {
    require(name) {
      if (name === "electron") return electron;
      if (name === "fs") return fakeFs;
      if (name === "./config.cjs") return require(path.join(appDir, "config.cjs"));
      return require(name);
    },
    process: { platform: "win32", env: {} },
    __dirname: appDir,
    URL,
    Buffer,
    console,
  };
  vm.createContext(context);
  vm.runInContext(fs.readFileSync(path.join(appDir, "main.cjs"), "utf8"), context);
  vm.runInContext("attachAuth()", context);

  function headersFor(url) {
    let result;
    headerHook({ url, requestHeaders: {} }, value => { result = value.requestHeaders; });
    return result;
  }
  assert.equal(headersFor("http://hub.example/collect").Authorization, undefined);
  assert.equal(headersFor("https://attacker.example/collect").Authorization, undefined);
  assert.equal(
    headersFor("https://hub.example/api/status").Authorization,
    "Basic " + Buffer.from("synthetic-user:SYNTHETIC-SECRET").toString("base64"),
  );

  function challenge(url) {
    let result;
    let prevented = false;
    handlers.login({ preventDefault() { prevented = true; } }, { id: 5 }, { url }, {}, (...args) => { result = args; });
    return { result, prevented };
  }
  assert.deepEqual(challenge("https://attacker.example/").result, []);
  assert.deepEqual(challenge("http://hub.example/").result, []);
  assert.equal(challenge("https://hub.example/").result[0], "synthetic-user");
  assert.equal(challenge("https://hub.example/laptop/term/").result[0], "synthetic-laptop");
});
