const C = "shotwriter-v2";
const ASSETS = ["./", "./index.html", "./app.css", "./app.js", "./models.js", "./icon.svg", "./manifest.webmanifest"];
self.addEventListener("install", (e) => { e.waitUntil(caches.open(C).then((c) => c.addAll(ASSETS)).catch(() => {})); self.skipWaiting(); });
self.addEventListener("activate", (e) => { e.waitUntil(caches.keys().then((ks) => Promise.all(ks.filter((k) => k !== C).map((k) => caches.delete(k))))); self.clients.claim(); });
self.addEventListener("fetch", (e) => {
  const u = new URL(e.request.url);
  if (e.request.method !== "GET" || u.origin !== location.origin) return; // never cache API calls
  e.respondWith(fetch(e.request).then((r) => { const cp = r.clone(); caches.open(C).then((c) => c.put(e.request, cp)); return r; }).catch(() => caches.match(e.request)));
});
