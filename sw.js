// NEURALE — service worker: app utilizzabile anche offline
const SHELL = "neurale-shell-v1";
const EDIZ = "neurale-edizioni";
const DATA = "neurale-dati";
const FILES = [
  "./", "index.html", "manifest.webmanifest",
  "icons/icon-180.png", "icons/icon-192.png", "icons/icon-512.png",
  "fonts/anton-latin-400-normal.woff2",
  "fonts/inter-latin-400-normal.woff2", "fonts/inter-latin-500-normal.woff2",
  "fonts/inter-latin-600-normal.woff2", "fonts/inter-latin-700-normal.woff2",
  "fonts/jetbrains-mono-latin-400-normal.woff2", "fonts/jetbrains-mono-latin-600-normal.woff2"
];
self.addEventListener("install", e => {
  e.waitUntil(caches.open(SHELL).then(c => c.addAll(FILES)).then(() => self.skipWaiting()));
});
self.addEventListener("activate", e => {
  e.waitUntil(caches.keys().then(ks => Promise.all(
    ks.filter(k => k.startsWith("neurale-shell-") && k !== SHELL).map(k => caches.delete(k))
  )).then(() => self.clients.claim()));
});
self.addEventListener("fetch", e => {
  const req = e.request;
  if (req.method !== "GET") return;
  const url = new URL(req.url);
  if (url.origin !== location.origin) return;

  // elenco edizioni: prima la rete, se offline l'ultima copia salvata
  if (url.pathname.endsWith("/editions.json")) {
    e.respondWith(fetch(req, { cache: "no-store" }).then(r => {
      if (r.ok) { const cp = r.clone(); caches.open(DATA).then(c => c.put("editions.json", cp)); }
      return r;
    }).catch(() => caches.open(DATA).then(c => c.match("editions.json"))
      .then(r => r || new Response('{"editions":[]}', { headers: { "Content-Type": "application/json" } }))));
    return;
  }
  // pagine delle edizioni: dalla memoria se ci sono, altrimenti rete (e le conserva)
  if (url.pathname.includes("/edizioni/")) {
    e.respondWith(caches.open(EDIZ).then(c => c.match(req).then(hit => hit || fetch(req).then(r => {
      if (r.ok) c.put(req, r.clone());
      return r;
    }))));
    return;
  }
  // app: risposta immediata dalla memoria, aggiornamento in background
  e.respondWith(caches.open(SHELL).then(c => c.match(req, { ignoreSearch: true }).then(hit => {
    const net = fetch(req).then(r => { if (r.ok) c.put(req, r.clone()); return r; }).catch(() => hit || c.match("index.html"));
    return hit || net;
  })));
});
