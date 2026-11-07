/* StreamBox service worker: offline app-shell + faster repeat loads.
   Pages (navigations) are NETWORK-FIRST so site updates always reach you;
   icons/manifest are cache-first. Bump CACHE on shell changes. */
const CACHE = "streambox-v2";
const SHELL = ["./", "./index.html", "./manifest.webmanifest", "./icon-192.png", "./icon-512.png"];
// Only the shell statics are cache-first. Everything else same-origin (the
// version.txt poll carries a cache-buster every visit) must ride the network:
// caching those keyed-by-URL grows the cache forever and can pin stale copies.
const STATIC_OK = new Set(["manifest.webmanifest", "icon-192.png", "icon-512.png"]);

function offline() {
  return new Response("Offline", {
    status: 503, statusText: "Offline", headers: { "Content-Type": "text/plain" }
  });
}

self.addEventListener("install", (e) => {
  e.waitUntil(caches.open(CACHE).then((c) => c.addAll(SHELL)).then(() => self.skipWaiting()));
});
self.addEventListener("activate", (e) => {
  e.waitUntil(
    caches.keys().then((keys) => Promise.all(keys.filter((k) => k !== CACHE).map((k) => caches.delete(k))))
      .then(() => self.clients.claim())
  );
});
self.addEventListener("fetch", (e) => {
  const url = new URL(e.request.url);
  if (e.request.method !== "GET" || url.origin !== location.origin) return;
  const isPage = e.request.mode === "navigate" || url.pathname.endsWith("index.html") || url.pathname === "/" || /\/streambox\/?$/.test(url.pathname);
  if (isPage) {
    // network-first: fresh HTML every visit, cached copy only when offline
    e.respondWith(
      fetch(e.request).then((res) => {
        if (res.ok) {
          const copy = res.clone();
          e.waitUntil(caches.open(CACHE).then((c) => c.put("./index.html", copy)));
        }
        return res;
      }).catch(() => caches.match("./index.html").then((hit) => hit || caches.match("./")).then((hit) => hit || offline()))
    );
    return;
  }
  const name = url.pathname.split("/").pop();
  if (!STATIC_OK.has(name)) return; // network passthrough (version.txt etc.)
  // stale-while-revalidate: serve the cached icon/manifest instantly but
  // ALWAYS refresh it behind the scenes - without this a changed icon stayed
  // stale forever unless the CACHE name was bumped by hand
  e.respondWith(
    caches.match(e.request).then((hit) => {
      const net = fetch(e.request).then((res) => {
        if (res.ok) {
          const copy = res.clone();
          e.waitUntil(caches.open(CACHE).then((c) => c.put(e.request, copy)));
        }
        return res;
      }).catch(() => hit || offline());
      return hit || net;
    })
  );
});
