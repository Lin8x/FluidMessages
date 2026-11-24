'use strict';
// Lets FluidMessages Web install as an app and open instantly. Only the app's own files are cached: messages,
// accounts, and anything under /api always come from your server and are never stored by the browser.
const VERSION = 'fluid-web-18';
const SHELL = ['/', '/style.css', '/app.js', '/companion.js', '/call.js', '/chess.js', '/cards.js', '/welcome.js', '/icon.webp',
  '/look.js', '/chats.js', '/media.js', '/editor.js', '/settings.js', '/sync-merge.js', '/people.js', '/privacy.js', '/server-directory.js', '/servers.js', '/inbox-filters.js', '/notify.js', '/requests.js', '/chat-commands.js', '/invites.js', '/connections.js', '/features.css', '/chat-features.css', '/samples/maya.webp', '/samples/jordan.webp', '/samples/lakeside.webp',
  '/manifest.webmanifest', '/favicon.ico', '/icons/favicon-32.png', '/icons/icon-192.png', '/icons/icon-512.png',
  '/icons/maskable-192.png', '/icons/maskable-512.png', '/icons/apple-touch-icon.png'];

self.addEventListener('install', (event) => {
  event.waitUntil(caches.open(VERSION).then((cache) => cache.addAll(SHELL)).then(() => self.skipWaiting()));
});

self.addEventListener('activate', (event) => {
  event.waitUntil(caches.keys().then((keys) => Promise.all(keys.filter((key) => key !== VERSION).map((key) => caches.delete(key))))
    .then(() => self.clients.claim()));
});

self.addEventListener('fetch', (event) => {
  const url = new URL(event.request.url);
  if (event.request.method !== 'GET' || url.origin !== self.location.origin || url.pathname.startsWith('/api/')) return;
  // Network first, so updates show right away; the cached copy is the fallback when the server can't be reached.
  event.respondWith(fetch(event.request).then((response) => {
    if (response.ok && SHELL.includes(url.pathname)) {
      const copy = response.clone();
      caches.open(VERSION).then((cache) => cache.put(event.request, copy));
    }
    return response;
  }).catch(() => caches.match(event.request).then((cached) => cached || caches.match('/'))));
});

// Notifications for new messages are shown through this worker (notify.js), the only way on Android Chrome and in the
// installed app. Tapping one brings FluidMessages forward, or opens it, and opens that chat. The chat to open travels
// in a message to the page, never in the address.
self.addEventListener('notificationclick', (event) => {
  event.notification.close();
  const chat = typeof event.notification.data?.chat === 'string' ? event.notification.data.chat : '';
  event.waitUntil((async () => {
    const windows = await self.clients.matchAll({type: 'window', includeUncontrolled: true});
    let client = windows.find((item) => new URL(item.url).origin === self.location.origin && item.focused)
      || windows.find((item) => new URL(item.url).origin === self.location.origin);
    if (client) {
      try { client = await client.focus(); } catch (error) { /* already in front, or the browser said no */ }
    } else client = await self.clients.openWindow('/');
    client?.postMessage({type: 'fluid-open-chat', chat});
  })());
});
