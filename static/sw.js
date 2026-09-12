const CACHE_NAME = 'melo-cache-v2.7.0';

// Precache list matching active production assets and v2.7.0 cache-busting queries
const PRECACHE_ASSETS = [
  '/',
  '/static/manifest.json',
  '/static/css/app.css?v=2.7.0',
  '/static/js/app.js?v=2.7.0',
  '/static/images/melo-text.png',
  '/static/images/logo.png?v=2.7.0',
  '/static/images/favicon.png?v=2.7.0'
];

// 1. Install & Precache Assets (Immediate Takeover)
self.addEventListener('install', (event) => {
  self.skipWaiting();
  event.waitUntil(
    caches.open(CACHE_NAME).then(async (cache) => {
      for (const asset of PRECACHE_ASSETS) {
        try {
          const res = await fetch(asset, { cache: 'reload' });
          if (res.ok) {
            await cache.put(asset, res);
          }
        } catch (err) {
          console.warn(`[MELO:SW] Precache skipped for ${asset}:`, err);
        }
      }
    })
  );
});

// 2. Purge Outdated Version Caches and Claim Open Clients
self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches
      .keys()
      .then((keys) => {
        return Promise.all(
          keys
            .filter((k) => k !== CACHE_NAME)
            .map((k) => {
              console.log('[MELO:SW] Purging outdated cache:', k);
              return caches.delete(k);
            })
        );
      })
      .then(() => self.clients.claim())
  );
});

// 3. Fetch Strategy
self.addEventListener('fetch', (event) => {
  const req = event.request;
  const url = new URL(req.url);

  // A. Bypass media streams, downloads, audio ranges, or mutation requests
  if (
    req.method !== 'GET' ||
    url.pathname.startsWith('/api/stream') ||
    url.pathname.startsWith('/api/download') ||
    req.headers.has('range')
  ) {
    return;
  }

  // B. Never cache OTA version check endpoints to prevent update loops
  if (url.pathname === '/api/app-version') {
    event.respondWith(fetch(req));
    return;
  }

  // C. Network-first with short timeout for dynamic backend APIs
  if (url.pathname.startsWith('/api/')) {
    event.respondWith(
      fetch(req).catch(() => caches.match(req))
    );
    return;
  }

  // D. Network-first for HTML navigation & root
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

  // E. Network-first for versioned bundles (?v=)
  if (url.searchParams.has('v')) {
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

  // F. Stale-While-Revalidate for unversioned static assets
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
          console.warn('[MELO:SW] Network fetch fallback:', err);
        });

      return cachedResponse || fetchPromise;
    })
  );
});