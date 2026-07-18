/*
 * 서비스워커 청소기 — 이 origin(:8090)이 과거에 다른 앱(Flutter 웹)을 서빙한 적이 있어
 * 브라우저에 그 서비스워커·캐시가 남아 있을 수 있다. 업데이트 체크로 이 파일을 받으면
 * 스스로 등록을 해제하고 캐시를 비운 뒤 페이지를 다시 불러온다.
 */
self.addEventListener('install', () => self.skipWaiting());
self.addEventListener('activate', event => {
  event.waitUntil((async () => {
    await self.registration.unregister();
    const keys = await caches.keys();
    await Promise.all(keys.map(k => caches.delete(k)));
    const clients = await self.clients.matchAll({ type: 'window' });
    clients.forEach(c => c.navigate(c.url));
  })());
});
