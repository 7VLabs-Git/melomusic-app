const CACHE_NAME = 'melo-cache-v2';
const STATIC_ASSETS = [
  '/',
  '/static/manifest.json',
  '/static/css/app.css',
  '/static/js/store.js',
  '/static/js/player.js',
  '/static/js/app.js'
];

self.addEventListener('install', (e) => {
  e.waitUntil(
    caches.open(CACHE_NAME).then((cache) => cache.addAll(STATIC_ASSETS))
  );
  self.skipWaiting();
});

self.addEventListener('activate', (e) => {
  e.waitUntil(
    caches.keys().then((keys) =>
      Promise.all(keys.filter((k) => k !== CACHE_NAME).map((k) => caches.delete(k)))
    )
  );
  self.clients.claim();
});

self.addEventListener('fetch', (e) => {
  // Always let media stream requests pass directly to the network
  if (e.request.url.includes('/api/stream/')) {
    return;
  }
  e.respondWith(
    caches.match(e.request).then((cached) => cached || fetch(e.request))
  );
});