const C = "shotwriter-v3";
const ASSETS = ["./", "./index.html", "./app.css", "./app.js", "./models.js", "./icon.svg", "./manifest.webmanifest"];
self.addEventListener("install", (e) => { e.waitUntil(caches.open(C).then((c) => c.addAll(ASSETS)).catch(() => {})); self.skipWaiting(); });
self.addEventListener("activate", (e) => { e.waitUntil(caches.keys().then((ks) => Promise.all(ks.filter((k) => k !== C).map((k) => caches.delete(k))))); self.clients.claim(); });

async function handle(req) {
  try {
    const r = await fetch(req);
    // Only store a real, complete success. Caching a 404/500 — or a 206 range, which Cache.put rejects
    // with an unhandled rejection — would serve that broken response back on every later offline load.
    if (r.ok && r.status === 200 && r.type === "basic") {
      const cp = r.clone();
      caches.open(C).then((c) => c.put(req, cp)).catch(() => {});
    }
    return r;
  } catch (err) {
    const hit = await caches.match(req);
    if (hit) return hit;
    if (req.mode === "navigate") {
      const shell = await caches.match("./index.html");
      if (shell) return shell;
    }
    // Never resolve respondWith with undefined; say plainly that this is an offline miss.
    return new Response("Offline and not in the cache.", { status: 504, headers: { "Content-Type": "text/plain" } });
  }
}

self.addEventListener("fetch", (e) => {
  const u = new URL(e.request.url);
  if (e.request.method !== "GET" || u.origin !== location.origin) return; // never cache API calls
  e.respondWith(handle(e.request));
});
