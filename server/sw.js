'use strict';
// Lets FluidMessages Web install as an app and open instantly. Only the app's own files are cached: messages,
// accounts, and anything under /api always come from your server and are never stored by the browser.
const VERSION = 'fluid-web-1';
const SHELL = ['/', '/style.css', '/app.js', '/companion.js', '/call.js', '/icon.webp', '/manifest.webmanifest',
  '/icons/icon-192.png', '/icons/icon-512.png'];

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
