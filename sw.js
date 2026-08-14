const CACHE = 'mengxia-v2';
self.addEventListener('install', e => { self.skipWaiting(); });
self.addEventListener('activate', e => {
  e.waitUntil(caches.keys().then(ks => Promise.all(ks.filter(k => k !== CACHE).map(k => caches.delete(k)))).then(() => self.clients.claim()));
});
self.addEventListener('fetch', e => {
  if (e.request.method !== 'GET') return;
  const isPage = e.request.mode === 'navigate' || (e.request.destination === 'document');
  if (isPage) {
    // 页面本体：先联网拿最新的，拿不到再用缓存（这样更新一次就能看到）
    e.respondWith(
      fetch(e.request).then(res => {
        const copy = res.clone();
        caches.open(CACHE).then(c => { try { c.put(e.request, copy); } catch (x) {} });
        return res;
      }).catch(() => caches.open(CACHE).then(c => c.match(e.request)))
    );
    return;
  }
  e.respondWith(
    caches.open(CACHE).then(cache => cache.match(e.request).then(hit => {
      const net = fetch(e.request).then(res => {
        try { if (res && res.ok) cache.put(e.request, res.clone()); } catch (x) {}
        return res;
      }).catch(() => hit);
      return hit || net;
    }))
  );
});
