const CACHE_NAME = 'melo-cache-v6';

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

// 3. Fetch Strategy: Network-First for HTML/APIs, Cache-First for Static Assets
self.addEventListener('fetch', (event) => {
  const req = event.request;
  const url = new URL(req.url);

  // A. Bypass media streams, downloads, byte-range requests, or non-GET requests entirely
  if (
    req.method !== 'GET' ||
    url.pathname.startsWith('/api/stream') ||
    url.pathname.startsWith('/api/download') ||
    req.headers.has('range')
  ) {
    return;
  }

  // B. Network-first for dynamic API routes (search, auth, lyrics, recommendations, sync)
  if (url.pathname.startsWith('/api/')) {
    event.respondWith(
      fetch(req).catch(() => caches.match(req))
    );
    return;
  }

  // C. Network-first for root HTML navigation to ensure updates reflect immediately
  if (req.mode === 'navigate' || url.pathname === '/') {
    event.respondWith(
      fetch(req)
        .then((networkResponse) => {
          if (networkResponse && networkResponse.status === 200) {
            const responseClone = networkResponse.clone();
            caches.open(CACHE_NAME).then((cache) => cache.put(req, responseClone));
          }
          return networkResponse;
        })
        .catch(() => caches.match(req))
    );
    return;
  }

  // D. Stale-While-Revalidate for static assets (CSS, JS, images, fonts)
  event.respondWith(
    caches.match(req).then((cachedResponse) => {
      const fetchPromise = fetch(req)
        .then((networkResponse) => {
          if (
            networkResponse &&
            networkResponse.status === 200 &&
            (url.pathname.startsWith('/static/') || url.origin === location.origin)
          ) {
            const responseClone = networkResponse.clone();
            caches.open(CACHE_NAME).then((cache) => cache.put(req, responseClone));
          }
          return networkResponse;
        })
        .catch((err) => {
          console.warn('[MELO:SW] Network fetch failed, falling back to cache:', err);
        });

      return cachedResponse || fetchPromise;
    })
  );
});