'use strict';
// Lets FluidMessages Web install as an app and open instantly. Only the app's own files are cached: messages,
// accounts, and anything under /api always come from your server and are never stored by the browser.
const VERSION = 'fluid-web-12';
const SHELL = ['/', '/style.css', '/app.js', '/companion.js', '/call.js', '/chess.js', '/cards.js', '/welcome.js', '/icon.webp',
  '/look.js', '/chats.js', '/media.js', '/editor.js', '/settings.js', '/sync-merge.js', '/people.js', '/privacy.js', '/servers.js', '/features.css', '/samples/maya.webp', '/samples/jordan.webp',
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
