/*
 * Minimal service worker — exists so the page is installable as a PWA
 * (home-screen app + Android share target). Deliberately NETWORK-FIRST for
 * page loads so a new VERSION of index.html is always picked up immediately;
 * the cache is only an offline fallback for the app shell.
 */
const CACHE = "novel-dl-shell-v1";

self.addEventListener("install", e => {
  e.waitUntil(caches.open(CACHE).then(c => c.add("./")).then(() => self.skipWaiting()));
});

self.addEventListener("activate", e => {
  e.waitUntil((async () => {
    /* reap everything but the current shell cache — this also cleans up
       "bgjob-" caches left behind by the removed background-download mode */
    for (const k of await caches.keys()) if (k !== CACHE) await caches.delete(k);
    await self.clients.claim();
  })());
});

self.addEventListener("fetch", e => {
  const req = e.request;
  if (req.method !== "GET" || req.mode !== "navigate") return;
  e.respondWith(
    fetch(req).then(r => {
      const copy = r.clone();
      caches.open(CACHE).then(c => c.put("./", copy)).catch(() => {});
      return r;
    }).catch(() => caches.match("./"))
  );
});
