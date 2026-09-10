const CACHE_NAME = 'melo-cache-v2.6.5';

// Assets must match the exact versioned URLs requested in index.html
const PRECACHE_ASSETS = [
  '/',
  '/static/manifest.json',
  '/static/css/app.css?v=2.6.0',
  '/static/js/app.js?v=2.6.0',
  '/static/images/melo-text.png',
  '/static/images/logo.png?v=2.6.0',
  '/static/images/favicon.png?v=2.6.0'
];

// 1. Install & Precache Assets
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

// 2. Purge Old Caches and Claim Clients
self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys().then((keys) => {
      return Promise.all(
        keys
          .filter((k) => k !== CACHE_NAME)
          .map((k) => {
            console.log('[MELO:SW] Purging old cache:', k);
            return caches.delete(k);
          })
      );
    }).then(() => self.clients.claim())
  );
});

// 3. Fetch Strategy
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

  // B. Network-first for dynamic API routes
  if (url.pathname.startsWith('/api/')) {
    event.respondWith(
      fetch(req).catch(() => caches.match(req))
    );
    return;
  }

  // C. Network-first for HTML navigation so page structure updates immediately
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

  // D. Static Assets: Network-first for versioned bundles (?v=), fallback to cache
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

  // E. Stale-While-Revalidate for other static assets (fonts, unversioned icons)
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