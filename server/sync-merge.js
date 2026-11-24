'use strict';
// Combining your phone's settings with this web app's (server/sync_merge.py, and SyncMerge.kt on the phone).
// When both changed the same thing, a full page asks which to keep: the phone is picked for each by default, and
// "Use phone for all" or "Use web for all" sets every one. Settle it here or on the phone, whichever you open first.
// Also keeps the payment methods, GIF sites, and stickers made in this browser on the server, so the phone and
// your other browsers share them. Loaded last; uses app.js (api, element, say, showError, threadTitle),
// welcome.js (guideFlow), and media.js (stickerStore, listStickers).

let mergeState = {active: false, pending: null};
let mergeLater = '';
let mergeStickersRev = '';
let mergeStickersAt = 0;
let mergeStickersBusy = false;

/** The phone combines settings with this web app (so its folders aren't shown on top of these). chats.js */
function mergeActive() { return !!mergeState.active; }

function mergePendingCount() { return mergeState.pending?.conflicts?.length || 0; }

/** Each refresh (app.js): what waits for a choice, and the shared extras and stickers. */
function noticeMerge(snapshot) {
  if (!snapshot || (typeof sampleOn !== 'undefined' && sampleOn)) return;
  mergeState = snapshot.merge || {active: false, pending: null};
  if ('web_extras' in snapshot) mirrorExtras(snapshot.web_extras).catch(() => {});
  if (snapshot.stickers_rev !== mergeStickersRev || Date.now() - mergeStickersAt > 60_000) {
    mergeStickersRev = snapshot.stickers_rev;
    mirrorStickers().catch(() => {});
  }
  showMergeBar();
  // The first refresh can come just before the inbox shows.
  if (get('workspace')?.hidden) setTimeout(showMergeBar, 1500);
}

// ---- Payment methods and GIF sites, kept in this browser by companion.js and media.js ----

function mergeList(key) { try { const value = JSON.parse(localStorage.getItem(key) || '[]'); return Array.isArray(value) ? value : []; } catch (error) { return []; } }

function localExtras() {
  return {money: {custom: mergeList('money.custom'), default: localStorage.getItem('money.default') || ''},
    gifs: {own: mergeList('gifSites.own'), off: mergeList('gifSites.off')}};
}

function writeExtras(extras) {
  localStorage.setItem('money.custom', JSON.stringify(extras.money.custom));
  if (extras.money.default) localStorage.setItem('money.default', extras.money.default); else localStorage.removeItem('money.default');
  localStorage.setItem('gifSites.own', JSON.stringify(extras.gifs.own));
  localStorage.setItem('gifSites.off', JSON.stringify(extras.gifs.off));
}

/** Both, by name; where both have the same name, the server's. */
function unionExtras(local, server) {
  const byName = (list, other) => [...other, ...list.filter((item) => !other.some((kept) => kept.name === item.name))];
  return {money: {custom: byName(local.money.custom, server.money.custom), default: server.money.default || local.money.default},
    gifs: {own: byName(local.gifs.own, server.gifs.own), off: [...new Set([...server.gifs.off, ...local.gifs.off])]}};
}

async function mirrorExtras(server) {
  const local = localExtras();
  let last = {};
  try { last = JSON.parse(localStorage.getItem('merge.extras') || '{}'); } catch (error) { /* start over */ }
  const localText = JSON.stringify(local), serverText = JSON.stringify(server);
  const localChanged = localText !== last.local, serverChanged = serverText !== last.server;
  if (!localChanged && !serverChanged) return;
  let next = local;
  if (server && serverChanged && !localChanged) next = server;
  else if (server && serverChanged) next = unionExtras(local, server);
  let saved = server;
  if (!server || JSON.stringify(next) !== serverText) saved = (await api('settings', {web_extras: next})).web_extras;
  if (JSON.stringify(next) !== localText) writeExtras(next);
  localStorage.setItem('merge.extras', JSON.stringify({local: JSON.stringify(localExtras()), server: JSON.stringify(saved)}));
}

// ---- Stickers: IndexedDB here (media.js), files on the server, and the phone's own folder ----

async function mirrorStickers() {
  if (mergeStickersBusy || typeof listStickers !== 'function') return;
  mergeStickersBusy = true;
  mergeStickersAt = Date.now();
  try {
    const local = await listStickers();
    const server = (await api('stickers')).stickers || [];
    let seen = new Set();
    try { seen = new Set(JSON.parse(localStorage.getItem('merge.stickers') || '[]')); } catch (error) { /* start over */ }
    const here = new Set(local.map((item) => item.id)), there = new Set(server.map((item) => item.id));
    const kept = new Set();
    for (const item of local) {
      if (there.has(item.id)) { kept.add(item.id); continue; }
      if (seen.has(item.id)) { await deleteSticker(item.id); continue; } // Deleted on the phone or another browser.
      if (!/^[A-Za-z0-9_-]{1,80}$/.test(item.id) || !['image/png', 'image/webp', 'image/gif'].includes(item.blob?.type)) continue;
      const response = await fetch('/api/stickers/upload', {method: 'POST', credentials: 'same-origin', body: item.blob,
        headers: {...REQUEST_HEADERS, 'Content-Type': item.blob.type, 'X-Fluid-Sticker': item.id}});
      if (response.ok) kept.add(item.id);
    }
    for (const item of server) {
      if (here.has(item.id)) continue;
      if (seen.has(item.id)) { await api('merge/stickers/delete', {id: item.id}); continue; } // Deleted in this browser.
      const response = await fetch('/api/stickers/file?id=' + encodeURIComponent(item.id), {credentials: 'same-origin', headers: REQUEST_HEADERS});
      if (!response.ok) continue;
      const blob = new Blob([await response.arrayBuffer()], {type: item.mime});
      await stickerStore('readwrite', (store) => store.put({id: item.id, blob, date: item.date || Date.now()}));
      kept.add(item.id);
    }
    localStorage.setItem('merge.stickers', JSON.stringify([...kept]));
  } finally { mergeStickersBusy = false; }
}

// ---- Words ----

const MERGE_LOOK = {theme: 'Color theme', mode: 'Light or dark', radius: 'Bubble corners', size: 'Text size', padding: 'Bubble padding',
  wallpaper: 'Wallpaper', searchPosition: 'Search position', toolbarFinish: 'Toolbar finish', density: 'Inbox density',
  tails: 'Bubble tails', chromeOpacity: 'Toolbar opacity', speed: 'Motion', exactTimes: 'Exact times',
  combineProtocols: 'Combine linked conversations', tagMode: 'Inbox protocol tags', encryptionMode: 'Encryption labels'};
const MERGE_FUN = {effects: 'Message effects', reactions: 'Reaction effects', games: 'Games', random: 'Random picks',
  gifs: 'Pictures and GIFs from links', linkPreviews: 'Link previews'};
const capital = (text) => text ? text[0].toUpperCase() + text.slice(1) : text;

function mergePerson(address) {
  try { return typeof threadTitle === 'function' ? threadTitle(address) : address; } catch (error) { return address; }
}

/** [section, title] for a key, like SyncMerge.describe on the phone. */
function mergeDescribe(key) {
  const [group, ...rest] = key.split(':');
  const name = rest.join(':');
  return {look: ['Appearance', MERGE_LOOK[name] || name], fun: ['Fun and effects', MERGE_FUN[name] || name],
    folder: ['Chat folders', mergePerson(name)], muted: ['Muted chats', mergePerson(name)], sender: ['Automated senders', mergePerson(name)],
    timer: ['Disappearing messages', name === '*' ? 'Default timer' : mergePerson(name)], wallpaper: ['Chat wallpaper', mergePerson(name)],
    gif: ['GIF sites', name], gifoff: ['GIF sites', capital(name)], pay: ['Payments', name === '*' ? 'Default way to pay' : name],
    sticker: ['Stickers', 'Sticker'], account: ['Chat accounts', name.includes(':') ? capital(name.split(':')[0]) + ' · ' + name.split(':').slice(1).join(':') : capital(name)], protocol: ['Chat services', capital(name)]}[group]
    || ['Other', key];
}

function mergeTimer(seconds) {
  if (!seconds) return 'Off';
  for (const [size, one, many] of [[86400 * 7, 'week', 'weeks'], [86400, 'day', 'days'], [3600, 'hour', 'hours'], [60, 'minute', 'minutes']]) {
    if (seconds % size === 0) { const count = seconds / size; return count + ' ' + (count === 1 ? one : many); }
  }
  return seconds + ' seconds';
}

/** A value in words, like SyncMerge.valueLabel on the phone. */
function mergeValue(key, value) {
  const [group, ...rest] = key.split(':');
  const name = rest.join(':');
  switch (group) {
    case 'look':
      if (value == null) return name === 'theme' ? 'Fluid' : name === 'mode' ? 'Automatic' : 'Default';
      return value === 'true' ? 'On' : value === 'false' ? 'Off' : capital(value);
    case 'fun': case 'protocol': return value === 'off' ? 'Off' : 'On';
    case 'gifoff': return value === 'off' ? 'Hidden' : 'Shown';
    case 'folder': {
      const set = (value || '').split('+').filter(Boolean);
      return set.length ? ['pinned', 'archived', 'blocked'].filter((item) => set.includes(item)).map(capital).join(', ') : 'Inbox';
    }
    case 'muted': return value === 'on' ? 'Muted' : 'Alerts on';
    case 'sender': return value === 'automated' ? 'Automated' : value === 'person' ? 'A person' : 'Automatic';
    case 'timer': return value == null ? (name === '*' ? 'Off' : 'Follows the default') : mergeTimer(Number(value));
    case 'wallpaper': return value == null || value === 'none' ? 'Solid' : value === 'photo' ? 'A photo' : capital(value);
    case 'gif': case 'pay': return value ?? 'Not added';
    // Per-account items say where that account connects (sync_merge.py).
    case 'account': return value === 'web' ? 'Connects through FluidMessages Web' : value === 'phone' ? 'Connects from this phone' : value ?? 'None';
    default: return value ?? 'Default';
  }
}

function mergeWhen(time) {
  if (!time) return '';
  const minutes = Math.round((Date.now() - time) / 60000);
  if (minutes < 1) return 'Changed just now';
  if (minutes < 60) return 'Changed ' + minutes + (minutes === 1 ? ' minute ago' : ' minutes ago');
  const hours = Math.round(minutes / 60);
  if (hours < 24) return 'Changed ' + hours + (hours === 1 ? ' hour ago' : ' hours ago');
  return 'Changed ' + new Date(time).toLocaleDateString();
}

// ---- The bar that says there's something to choose, and the full page ----

function showMergeBar() {
  let bar = document.getElementById('merge-bar');
  const count = mergePendingCount();
  const open = !!document.getElementById('sync-merge') || !!document.getElementById('sync-guide');
  if (!count || open || mergeLater === mergeState.pending?.id || get('workspace')?.hidden) { bar?.remove(); return; }
  if (!bar) {
    bar = element('div', '', 'merge-bar');
    bar.id = 'merge-bar';
    bar.setAttribute('role', 'status');
    document.body.append(bar);
  }
  const words = element('span', count === 1 ? 'Your phone and this web app changed the same setting.'
    : 'Your phone and this web app changed ' + count + ' of the same settings.');
  const choose = element('button', 'Choose what to keep', 'primary');
  choose.type = 'button';
  choose.addEventListener('click', () => showSyncMerge().catch(showError));
  const later = element('button', 'Later', 'quiet');
  later.type = 'button';
  later.addEventListener('click', () => { mergeLater = mergeState.pending?.id || ''; bar.remove(); });
  bar.replaceChildren(words, choose, later);
}

/**
 * The differences, full page, like the guides: a card for each with this phone and this web app side by side.
 * [then] runs once it's closed, saved or not (the sync guide continues there).
 */
async function showSyncMerge({then} = {}) {
  const state = await api('merge');
  document.getElementById('merge-bar')?.remove();
  document.querySelector('dialog.settings-sheet')?.close();
  document.getElementById('sync-merge')?.remove();
  const pending = state.pending;
  const conflicts = pending?.conflicts || [];
  const choices = Object.fromEntries(conflicts.map((item) => [item.key, 'phone']));
  const sheet = element('dialog', '', 'guide-dialog');
  sheet.id = 'sync-merge';
  document.body.append(sheet);
  let saved = false;
  let problem = '';
  let busy = false;
  const close = () => {
    flow.stop();
    if (sheet.open) sheet.close();
    sheet.remove();
    if (!get('workspace').hidden) refresh().catch(() => {});
    if (then) then();
  };
  sheet.addEventListener('cancel', (event) => { event.preventDefault(); close(); });

  const card = (item) => {
    const [section, title] = mergeDescribe(item.key);
    const box = element('div', '', 'merge-card');
    const head = element('div', '', 'merge-card-head');
    head.append(element('small', section, 'muted'), element('strong', title));
    const sides = element('div', '', 'merge-sides');
    sides.setAttribute('role', 'radiogroup');
    sides.setAttribute('aria-label', title);
    for (const [side, label] of [['phone', 'Your phone'], ['web', 'This web app']]) {
      const option = element('button', '', 'merge-side' + (choices[item.key] === side ? ' on' : ''));
      option.type = 'button';
      option.setAttribute('role', 'radio');
      option.setAttribute('aria-checked', String(choices[item.key] === side));
      option.append(element('span', label, 'merge-side-label'), element('span', mergeValue(item.key, item[side]), 'merge-side-value'));
      const when = mergeWhen(item[side + '_t']);
      if (when) option.append(element('small', when, 'muted'));
      option.addEventListener('click', () => { choices[item.key] = side; flow.redraw(); });
      sides.append(option);
    }
    box.append(head, sides);
    return box;
  };

  const save = async () => {
    if (busy) return;
    busy = true;
    problem = '';
    try {
      await api('merge/resolve', {id: pending.id, choices});
      saved = true;
      mergeState = {...mergeState, pending: null};
    } catch (error) { problem = error.message; }
    busy = false;
    flow.redraw();
  };

  const steps = [{
    get title() { return saved || !conflicts.length ? 'Everything matches' : 'Choose what to keep'; },
    get subtitle() {
      return saved ? 'Saved here. Your phone picks it up on its next sync.'
        : conflicts.length ? 'Your phone and this web app each changed these. Everything else was combined.'
          : 'Your phone and this web app have the same settings.';
    },
    body: () => {
      if (saved || !conflicts.length) return [typeof readyIllustration === 'function' ? readyIllustration() : element('div', '')];
      const all = element('div', '', 'merge-all');
      for (const [side, label] of [['phone', 'Use phone for all'], ['web', 'Use web for all']]) {
        const button = element('button', label, 'chip' + (conflicts.every((item) => choices[item.key] === side) ? ' on' : ''));
        button.type = 'button';
        button.setAttribute('aria-pressed', String(conflicts.every((item) => choices[item.key] === side)));
        button.addEventListener('click', () => { for (const item of conflicts) choices[item.key] = side; flow.redraw(); });
        all.append(button);
      }
      const out = [all, ...conflicts.map(card)];
      if (problem) {
        const error = element('p', problem, 'error');
        error.setAttribute('role', 'alert');
        out.push(error);
      }
      return out;
    },
    actions: () => saved || !conflicts.length ? [['Done', close]] : [[busy ? 'Saving…' : 'Keep these and sync', save, {disabled: busy}]],
  }];
  sheet.showModal();
  const flow = guideFlow(sheet, {steps, brand: 'Phone and web', onSkip: close, skipLabel: 'Later', onBackFromFirst: close});
}
