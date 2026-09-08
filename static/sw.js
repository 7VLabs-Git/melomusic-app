const CACHE_NAME = 'melo-cache-v3';

// Only precache files guaranteed to exist
const PRECACHE_ASSETS = [
  '/',
  '/static/manifest.json',
  '/static/css/app.css',
  '/static/js/app.js',
  '/static/images/melo-text.png'
];

// 1. Install & Cache Shell Assets Safely
self.addEventListener('install', (event) => {
  event.waitUntil(
    caches.open(CACHE_NAME).then(async (cache) => {
      // Individual cache fetches prevent one 404 from crashing the entire install
      for (const asset of PRECACHE_ASSETS) {
        try {
          await cache.add(asset);
        } catch (err) {
          console.warn(`[MELO:SW] Precache skipped for ${asset}:`, err);
        }
      }
    })
  );
  self.skipWaiting();
});

// 2. Clean Up Outdated Caches on Version Bump
self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys().then((keys) =>
      Promise.all(
        keys
          .filter((k) => k !== CACHE_NAME)
          .map((k) => {
            console.log('[MELO:SW] Purging old cache:', k);
            return caches.delete(k);
          })
      )
    )
  );
  self.clients.claim();
});

// 3. Fetch Strategy: Network First for Dynamic APIs, Cache First for Static Assets
self.addEventListener('fetch', (event) => {
  const req = event.request;
  const url = new URL(req.url);

  // A. Bypass media streams, ranges, or non-GET requests entirely
  if (
    req.method !== 'GET' ||
    url.pathname.startsWith('/api/stream') ||
    req.headers.has('range')
  ) {
    return;
  }

  // B. Network-first for dynamic search, lyrics, recommendations, and images
  if (url.pathname.startsWith('/api/')) {
    event.respondWith(
      fetch(req).catch(() => caches.match(req))
    );
    return;
  }

  // C. Cache-first, network fallback for UI assets (CSS, JS, fonts, images)
  event.respondWith(
    caches.match(req).then((cachedResponse) => {
      if (cachedResponse) {
        return cachedResponse;
      }
      return fetch(req).then((networkResponse) => {
        // Cache valid static responses dynamically
        if (
          networkResponse &&
          networkResponse.status === 200 &&
          (url.pathname.startsWith('/static/') || url.pathname === '/')
        ) {
          const responseClone = networkResponse.clone();
          caches.open(CACHE_NAME).then((cache) => cache.put(req, responseClone));
        }
        return networkResponse;
      });
    })
  );
});