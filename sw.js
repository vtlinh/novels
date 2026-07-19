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
    /* only reap old SHELL caches — "bgjob-" caches hold background-fetch
       results awaiting collection and must survive SW updates */
    for (const k of await caches.keys())
      if (k.startsWith("novel-dl-shell") && k !== CACHE) await caches.delete(k);
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

/* ---- Background Fetch ----
   Chapter downloads registered by the page keep going in the browser's
   download manager after the app closes. When a job settles (success OR
   partial failure), copy every finished response into a per-job cache;
   the page collects, parses, and writes them to the user's folder on its
   next visit. Failed records are simply absent — a later run re-fetches
   them via the normal skip/resume logic. */
const bgCache = id => "bgjob-" + id;
async function stashRecords(reg) {
  const cache = await caches.open(bgCache(reg.id));
  for (const rec of await reg.matchAll()) {
    try { await cache.put(rec.request, await rec.responseReady); } catch {}
  }
}
self.addEventListener("backgroundfetchsuccess", e => {
  e.waitUntil((async () => {
    await stashRecords(e.registration);
    try { await e.updateUI({ title: "Chapters downloaded — open the app to save them" }); } catch {}
  })());
});
self.addEventListener("backgroundfetchfail", e => {
  e.waitUntil((async () => {
    await stashRecords(e.registration);
    try { await e.updateUI({ title: "Download incomplete — open the app to save what finished" }); } catch {}
  })());
});
self.addEventListener("backgroundfetchclick", e => {
  e.waitUntil(self.clients.openWindow("./"));
});
