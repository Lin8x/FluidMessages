'use strict';
// Organizing chats like the phone: pin, archive, mute, and block (the inbox filter at the top trailing edge
// switches between Inbox, Archived, and Blocked), chat codes that hide a chat until you enter its code, more in
// each message's ⋯ menu, and the sample chats, which try everything with fictional people and send nothing.
// Loaded after app.js, companion.js, and cards.js, and uses their helpers.

const NO_CHATS = () => ({pinned: [], archived: [], blocked: [], muted: [], mute_until: {}, locks: {}});
let webChats = NO_CHATS();

/** The snapshot's web chat settings and new messages (app.js refresh). */
function noticeSnapshot(snapshot) {
  webChats = {...NO_CHATS(), ...(snapshot.web_chats || {})};
  ircChannels = snapshot.channels && typeof snapshot.channels === 'object' ? snapshot.channels : {};
  if (typeof lookNoticeIncoming === 'function') lookNoticeIncoming(snapshot.messages);
}

function chatState() { return sampleOn ? sampleChats : webChats; }

function saveChats(next) {
  if (sampleOn) sampleChats = next; else webChats = next;
  showThreads();
  if (typeof updateMuteMark === 'function') updateMuteMark();
  if (!sampleOn) api('settings', {web_chats: next}).catch(showError);
}

/** Folders: this web app's, plus the phone's when it has synced (app.js showThreads). */
function chatFolders() {
  const own = chatState();
  // Once the phone merges settings (sync-merge.js), its folders are already in this web app's own.
  const phone = sampleOn || (typeof mergeActive === 'function' && mergeActive()) ? {} : appearance.folders || {};
  const both = (key) => [...new Set([...(own[key] || []), ...(phone[key] || [])])];
  return {pinned: both('pinned'), archived: both('archived'), blocked: both('blocked')};
}

function inFolder(address, key) { return chatFolders()[key].includes(address); }
/** Kept quiet: muted now (by itself or its server, and not yet run out) or blocked. */
function chatMuted(address) { return chatMuteEnds(address) !== null || inFolder(address, 'blocked'); }
function chatLocked(key) { return !!chatState().locks[threadAddress(key)]; }

// Muting, for a while or for good: a chat, everyone's chats with one person, or every channel and room on a server.
// Muted chats don't notify, chime, or vibrate, and don't count on the tabs; their messages still arrive and show
// unread. Keys are kept in web_chats.muted as before: a chat's address, or "server:<account id>" for a whole
// server. web_chats.mute_until says when a timed mute ends; a key without one is muted forever, as every mute
// from before was. A mute whose time is up simply stops counting, with nothing to run in the background, and is
// tidied away on the next save. Same rules as the phone (Mutes.kt). [muteClock] is swapped in the tests.

const MUTE_CHOICES = [['1 hour', 3600000], ['6 hours', 6 * 3600000], ['1 day', 86400000], ['1 week', 7 * 86400000], ['Forever', 0]];
const SERVER_MUTE = 'server:';
let muteClock = () => Date.now();

/** When [key]'s own mute ends: 0 for forever, the time it ends, or null when it isn't muted (or its time is up). */
function muteEnds(key, state = chatState(), now = muteClock()) {
  if (!key || !(state.muted || []).includes(key)) return null;
  const until = Number((state.mute_until || {})[key]) || 0;
  return until && until <= now ? null : until;
}

/** The longer of two mutes: forever beats any time, and null is no mute. */
function longerMute(a, b) {
  if (a === null || a === undefined) return b ?? null;
  if (b === null || b === undefined) return a;
  return a === 0 || b === 0 ? 0 : Math.max(a, b);
}

/** The server (account id) a chat's address belongs to, for chats in a channel, room, or server DM. */
function muteServerOf(address) {
  const route = (typeof routes === 'undefined' ? [] : routes).find((item) => item.address === address && item.account);
  return route ? route.account : '';
}

/** When a chat's mute ends, counting its server's: 0 forever, a time, or null when it isn't muted. */
function chatMuteEnds(address, state = chatState(), now = muteClock()) {
  const server = muteServerOf(address);
  return longerMute(muteEnds(address, state, now), server ? muteEnds(SERVER_MUTE + server, state, now) : null);
}

/** Several keys, such as a person's chats, are muted when every one of them is; the soonest end is what shows. */
function keysMuteEnds(keys, state = chatState(), now = muteClock()) {
  if (!keys.length) return null;
  let soonest = 0;
  for (const key of keys) {
    const ends = key.startsWith(SERVER_MUTE) ? muteEnds(key, state, now) : chatMuteEnds(key, state, now);
    if (ends === null) return null;
    if (ends && (!soonest || ends < soonest)) soonest = ends;
  }
  return soonest;
}

/** The chat settings with mutes whose time is up taken out. */
function pruneMutes(state, now = muteClock()) {
  const until = {...(state.mute_until || {})};
  const muted = (state.muted || []).filter((key) => {
    const ends = Number(until[key]) || 0;
    if (ends && ends <= now) { delete until[key]; return false; }
    return true;
  });
  for (const key of Object.keys(until)) if (!muted.includes(key) || !(Number(until[key]) > 0)) delete until[key];
  return {...state, muted, mute_until: until};
}

/** Mutes [keys] for [duration] milliseconds, or forever with 0. */
function muteKeys(keys, duration, state = chatState(), now = muteClock()) {
  const next = pruneMutes(state, now);
  for (const key of keys) {
    if (!next.muted.includes(key)) next.muted = [...next.muted, key];
    if (duration > 0) next.mute_until[key] = now + duration; else delete next.mute_until[key];
  }
  return next;
}

function unmuteKeys(keys, state = chatState(), now = muteClock()) {
  const next = pruneMutes(state, now);
  next.muted = next.muted.filter((key) => !keys.includes(key));
  for (const key of keys) delete next.mute_until[key];
  return next;
}

/** "Muted", "Muted until 3:40 PM" (later today), or "Muted for 6 hours" / "1 day" / "1 week". Empty when not muted. */
function muteLabel(ends, now = muteClock()) {
  if (ends === null || ends === undefined) return '';
  if (!ends) return 'Muted';
  if (new Date(ends).toDateString() === new Date(now).toDateString()) return 'Muted until ' + clock(ends);
  const hours = Math.ceil((ends - now) / 3600000);
  if (hours < 24) return 'Muted for ' + hours + (hours === 1 ? ' hour' : ' hours');
  const days = Math.max(1, Math.round((ends - now) / 86400000));
  return 'Muted for ' + (days === 7 ? '1 week' : days === 1 ? '1 day' : days + ' days');
}

/** The small outlined bell with a slash through it. A button when [open] is given. */
function muteMark(label, open) {
  const mark = element(open ? 'button' : 'span', '', 'mute-mark');
  if (open) {
    mark.type = 'button';
    mark.setAttribute('aria-label', label + '. Change or unmute');
    mark.addEventListener('click', (event) => { event.stopPropagation(); open(); });
    mark.addEventListener('keydown', (event) => event.stopPropagation());
  } else {
    mark.setAttribute('role', 'img');
    mark.setAttribute('aria-label', label);
  }
  mark.title = label;
  const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
  svg.setAttribute('viewBox', '0 0 16 16');
  svg.setAttribute('aria-hidden', 'true');
  for (const d of ['M4 11.5V7.2a4 4 0 0 1 6.4-3.2M12 6.6v4.9l1 1H5.5', 'M6.6 13.6a1.5 1.5 0 0 0 2.8 0', 'M2.5 2.5l11 11']) {
    const path = document.createElementNS('http://www.w3.org/2000/svg', 'path');
    for (const [name, value] of [['d', d], ['fill', 'none'], ['stroke', 'currentColor'], ['stroke-width', '1.5'],
      ['stroke-linecap', 'round'], ['stroke-linejoin', 'round']]) path.setAttribute(name, value);
    svg.append(path);
  }
  mark.append(svg);
  return mark;
}

/**
 * The mute page: how long, or Unmute, as a list of choices on a full page. [keys] are chat addresses (a person's
 * every address) or a server key. [what] says what it covers. [back] runs afterwards and for Back.
 */
function openMutePage({keys, title, what, back}) {
  const done = back || (() => closeDetailsPage());
  const draw = () => {
    const now = muteClock();
    const ends = keysMuteEnds(keys, chatState(), now);
    const status = element('section', '', 'settings-section mute-status');
    const line = element('p', '', 'mute-line');
    if (ends !== null) line.append(muteMark(muteLabel(ends, now)), document.createTextNode(' ' + muteLabel(ends, now)));
    else line.textContent = 'Notifications are on.';
    status.append(line, element('p', what + ' No notifications, sounds, or vibration while muted. Messages still arrive and '
      + 'show as unread, and muted chats don’t count on the tabs.', 'muted'));
    if (ends !== null) {
      const unmute = element('button', 'Unmute', 'primary');
      unmute.type = 'button';
      unmute.addEventListener('click', () => { saveChats(unmuteKeys(keys)); say('Unmuted.'); done(); });
      status.append(unmute);
    }
    const list = element('section', '', 'settings-section mute-choices');
    list.setAttribute('role', 'group');
    list.append(element('h3', ends === null ? 'Mute for' : 'Change to'));
    for (const [label, duration] of MUTE_CHOICES) {
      const row = element('button', '', 'member-row mute-choice');
      row.type = 'button';
      row.dataset.duration = String(duration);
      const words = element('span', '', 'detail-words');
      words.append(element('strong', label), element('small', duration ? 'Until ' + (new Date(now + duration).toDateString() === new Date(now).toDateString()
        ? clock(now + duration) : new Date(now + duration).toLocaleString([], {weekday: 'short', hour: 'numeric', minute: '2-digit'}))
        : 'Until you unmute'));
      row.append(words, element('span', '›', 'chevron'));
      row.addEventListener('click', () => {
        saveChats(muteKeys(keys, duration));
        say(duration ? 'Muted for ' + label + '.' : 'Muted.');
        done();
      });
      list.append(row);
    }
    detailsPage(ends === null ? 'Mute ' + title : 'Muted · ' + title, [status, list], done);
  };
  draw();
}

/** Mute for one chat, from its details, the inbox menu, or the header's mark. */
function openChatMute(key, back) {
  const address = threadAddress(key);
  const server = muteServerOf(address);
  const serverMuted = server && muteEnds(SERVER_MUTE + server) !== null;
  openMutePage({keys: [address], title: threadTitle(key), back,
    what: serverMuted ? 'Its whole server is muted too; unmute the server from the server’s bar to hear its other chats.' : 'Just this chat.'});
}

/** Mute for a person's contact page: all of their chats and numbers at once (people.js). */
function personMuteSection(person, chats) {
  const addresses = [...new Set([...chats.map(threadAddress), ...(person.numbers || [])])];
  const section = element('section', '', 'settings-section');
  section.append(element('h3', 'Notifications'));
  const ends = addresses.length ? keysMuteEnds(addresses) : null;
  const row = element('button', '', 'member-row');
  row.type = 'button';
  row.disabled = !addresses.length;
  const words = element('span', '', 'detail-words');
  words.append(element('strong', ends === null ? 'Mute ' + person.name : muteLabel(ends)),
    element('small', ends === null ? 'All of their chats and numbers' : 'All of their chats and numbers · tap to change or unmute'));
  if (ends !== null) row.append(muteMark(muteLabel(ends)));
  row.append(words, element('span', '›', 'chevron'));
  row.addEventListener('click', () => openMutePage({keys: addresses, title: person.name, what: 'All of ' + person.name + '’s chats and numbers.',
    back: () => (typeof openPersonPage === 'function' ? openPersonPage(person).catch(showError) : closeDetailsPage())}));
  section.append(row);
  return section;
}

/** A server's mark and Mute button, for its bar or header (servers.js): every channel, room, and DM on it. */
function serverMuteControls(accountId, name) {
  const key = SERVER_MUTE + accountId;
  const ends = muteEnds(key);
  const open = () => openMutePage({keys: [key], title: name, what: 'Every channel, room, and direct message on ' + name + '.'});
  const nodes = [];
  if (ends !== null) nodes.push(muteMark(muteLabel(ends), open));
  const button = element('button', ends === null ? 'Mute' : 'Muted');
  button.type = 'button';
  button.setAttribute('aria-pressed', String(ends !== null));
  button.addEventListener('click', open);
  nodes.push(button);
  return nodes;
}

/** The mark beside the chat header's lock (app.js select, and after any change). */
function updateMuteMark() {
  const old = document.getElementById('mute-mark');
  const address = typeof active === 'string' && active ? threadAddress(active) : '';
  const ends = address ? chatMuteEnds(address) : null;
  if (ends === null) { old?.remove(); return; }
  const mark = muteMark(muteLabel(ends), () => openChatMute(active));
  mark.id = 'mute-mark';
  if (old) old.replaceWith(mark);
  else (document.getElementById('security-badge') || get('transport')).after(mark);
}

// End of muting.

// Mute opens its own page with how long (openChatMute), so it isn't a toggle like the folders.
const FOLDER_ACTIONS = {pinned: ['Pin', 'Unpin'], archived: ['Archive', 'Unarchive'], muted: ['Mute…', 'Muted…'], blocked: ['Block', 'Unblock']};
const FOLDER_DONE = {pinned: ['Pinned.', 'Unpinned.'], archived: ['Archived.', 'Moved back to the inbox.'],
  blocked: ['Blocked here. FluidMessages Web moves it to Blocked; delivery isn’t blocked.', 'Unblocked.']};

function toggleChat(address, key) {
  const state = chatState();
  const list = state[key] || [];
  if (!list.includes(address) && inFolder(address, key)) {
    say('Your phone set this. Change it there, or choose “This web app’s own” settings.', true);
    return;
  }
  const next = {...state, [key]: list.includes(address) ? list.filter((item) => item !== address) : [...list, address]};
  // Archived and blocked are separate folders, like the phone's.
  if (key === 'archived' && next.archived.includes(address)) next.blocked = (next.blocked || []).filter((item) => item !== address);
  if (key === 'blocked' && next.blocked.includes(address)) next.archived = (next.archived || []).filter((item) => item !== address);
  saveChats(next);
  say(FOLDER_DONE[key][next[key].includes(address) ? 0 : 1]);
}

/** Marks in the inbox row, and the menu from right-click, long-press, or the context-menu key (app.js showThreads). */
function decorateThread(key, button, copy) {
  const address = threadAddress(key);
  const time = button.querySelector('time');
  if (chatLocked(key)) copy.lastElementChild.textContent = '🔒 Chat code';
  // Muted: the bell with a slash, beside the protocol tags (or the name when tags are hidden).
  const muted = chatMuteEnds(address);
  if (muted !== null) {
    const mark = muteMark(muteLabel(muted));
    const tags = copy.querySelector('.tags');
    if (tags) tags.append(mark); else copy.querySelector('strong')?.append(mark);
  }
  if (inFolder(address, 'pinned') && time) time.prepend('Pinned · ');
  button.addEventListener('contextmenu', (event) => { event.preventDefault(); threadMenu(key, button); });
  let timer = 0;
  button.addEventListener('pointerdown', (event) => {
    if (event.pointerType !== 'touch') return;
    timer = setTimeout(() => { timer = -1; threadMenu(key, button); }, 550);
  });
  const cancel = () => { if (timer > 0) clearTimeout(timer); };
  button.addEventListener('pointerup', cancel);
  button.addEventListener('pointerleave', cancel);
  button.addEventListener('click', (event) => { if (timer === -1) { event.stopImmediatePropagation(); timer = 0; } }, true);
}

/** A row of choices that opens under the chat in the list (not a pop-up). */
function threadMenu(key, button) {
  document.querySelector('.thread-actions')?.remove();
  const address = threadAddress(key);
  const row = element('div', '', 'thread-actions');
  row.setAttribute('role', 'group');
  row.setAttribute('aria-label', 'Choices for ' + threadTitle(key));
  const state = chatState();
  for (const [folder, [on, off]] of Object.entries(FOLDER_ACTIONS)) {
    const set = folder === 'muted' ? chatMuteEnds(address, state) !== null : inFolder(address, folder);
    const item = element('button', set ? off : on);
    item.type = 'button';
    item.addEventListener('click', () => {
      row.remove();
      if (folder === 'muted') { select(key); openChatMute(key); } else toggleChat(address, folder);
    });
    row.append(item);
  }
  // Move to private folder, once it's set up (privacy.js).
  const hide = typeof privateMenuItem === 'function' ? privateMenuItem(key, row) : null;
  if (hide) row.append(hide);
  const details = element('button', 'Details');
  details.type = 'button';
  details.addEventListener('click', () => { row.remove(); select(key); openDetails().catch(showError); });
  const close = element('button', '×', 'icon-button small');
  close.type = 'button';
  close.setAttribute('aria-label', 'Close');
  close.addEventListener('click', () => { row.remove(); button.focus(); });
  row.append(details, close);
  row.addEventListener('keydown', (event) => { if (event.key === 'Escape') { row.remove(); button.focus(); } });
  button.after(row);
  row.querySelector('button').focus();
}

// Chat codes: the chat's preview is hidden and it asks for the code before opening, as on the phone. Only a
// salted PBKDF2 hash is kept. It's a privacy screen for this browser; anyone signed in could remove it from Details.

let unlockedAddress = '';
let gateLast = '';

const hexOf2 = (buffer) => [...new Uint8Array(buffer)].map((byte) => byte.toString(16).padStart(2, '0')).join('');

async function codeHash(code, salt) {
  if (!crypto.subtle) throw new Error('Chat codes need FluidMessages Web opened over HTTPS (or at localhost).');
  const bytes = new Uint8Array(salt.match(/../g).map((pair) => parseInt(pair, 16)));
  const key = await crypto.subtle.importKey('raw', new TextEncoder().encode(code), 'PBKDF2', false, ['deriveBits']);
  return hexOf2(await crypto.subtle.deriveBits({name: 'PBKDF2', hash: 'SHA-256', salt: bytes, iterations: 100000}, key, 256));
}

async function codeMatches(address, code) {
  const lock = chatState().locks[address];
  return !!lock && await codeHash(code, lock.salt) === lock.hash;
}

/** Called before a chat is drawn (app.js showMessages). Returns true when the chat waits for its code. */
function chatLockGate(conversation) {
  if (typeof applyChatWallpaper === 'function') applyChatWallpaper();
  const address = active ? threadAddress(active) : '';
  if (address !== gateLast) { gateLast = address; if (unlockedAddress !== address) unlockedAddress = ''; }
  if (sampleOn && active) get('schedule').hidden = false;
  if (!address || !chatState().locks[address] || unlockedAddress === address) return false;
  get('schedule').hidden = true;
  const box = element('form', '', 'empty-state lock-gate');
  box.append(element('span', '🔒', 'lock-mark'), element('h2', 'This chat has a code'), element('p', 'Enter it to open the chat.', 'muted'));
  const input = Object.assign(element('input', ''), {type: 'password', autocomplete: 'off', required: true, maxLength: 64, inputMode: 'numeric'});
  input.setAttribute('aria-label', 'Chat code');
  const status = element('p', '', 'error');
  status.setAttribute('role', 'alert');
  const open = element('button', 'Open chat', 'primary');
  box.append(input, status, open);
  box.addEventListener('submit', async (event) => {
    event.preventDefault();
    open.disabled = true;
    try {
      if (await codeMatches(address, input.value)) { unlockedAddress = address; select(active); }
      else { status.textContent = 'That isn’t this chat’s code.'; input.select(); }
    } catch (error) { status.textContent = error.message; }
    finally { open.disabled = false; }
  });
  conversation.append(box);
  requestAnimationFrame(() => input.focus());
  return true;
}

function lockSection(address) {
  const section = element('section', '', 'settings-section');
  section.append(element('h3', 'Chat code'));
  const draw = () => {
    section.replaceChildren(element('h3', 'Chat code'));
    const locked = !!chatState().locks[address];
    section.append(element('p', locked ? 'This chat asks for its code before it opens, and its preview is hidden in the inbox and search.'
      : 'Add a code to hide this chat’s preview and ask for the code before it opens.', 'muted'));
    const form = element('form', '', 'stack');
    const code = Object.assign(element('input', ''), {type: 'password', autocomplete: 'new-password', required: true, minLength: 4, maxLength: 64});
    const again = Object.assign(element('input', ''), {type: 'password', autocomplete: 'new-password', required: true, minLength: 4, maxLength: 64});
    const status = element('p', '', 'error');
    status.setAttribute('role', 'alert');
    const save = element('button', locked ? 'Remove code' : 'Add code', locked ? '' : 'primary');
    form.append(field(locked ? 'Current code' : 'Code (at least 4 characters)', code));
    if (!locked) form.append(field('Type it again', again));
    form.append(status, save);
    form.addEventListener('submit', async (event) => {
      event.preventDefault();
      save.disabled = true;
      try {
        const state = chatState();
        if (locked) {
          if (!await codeMatches(address, code.value)) { status.textContent = 'That isn’t this chat’s code.'; return; }
          const locks = {...state.locks};
          delete locks[address];
          saveChats({...state, locks});
        } else {
          if (code.value !== again.value) { status.textContent = 'The two codes don’t match.'; return; }
          const salt = hexOf2(crypto.getRandomValues(new Uint8Array(16)));
          saveChats({...state, locks: {...state.locks, [address]: {salt, hash: await codeHash(code.value, salt)}}});
          unlockedAddress = address;
        }
        draw();
        showMessages(true);
      } catch (error) { status.textContent = error.message; }
      finally { save.disabled = false; }
    });
    section.append(form, element('small', 'A forgotten code can’t be recovered here, but removing the chat’s code needs it.', 'hint'));
  };
  draw();
  return section;
}

/** A chat's own sections for its details page (companion.js openDetails): its folders, its code, and its wallpaper. */
function detailsExtras(key) {
  const address = threadAddress(key);
  const chat = element('section', '', 'settings-section');
  chat.append(element('h3', 'Conversation'));
  const row = element('div', '', 'button-row wrap');
  const draw = () => {
    row.replaceChildren();
    const state = chatState();
    for (const [folder, [on, off]] of Object.entries(FOLDER_ACTIONS)) {
      const ends = folder === 'muted' ? chatMuteEnds(address, state) : null;
      const set = folder === 'muted' ? ends !== null : inFolder(address, folder);
      const button = element('button', folder === 'muted' && set ? muteLabel(ends) + '…' : set ? off : on);
      button.type = 'button';
      button.setAttribute('aria-pressed', String(set));
      if (folder === 'muted' && set) button.prepend(muteMark(muteLabel(ends)), ' ');
      button.addEventListener('click', () => {
        if (folder !== 'muted') { toggleChat(address, folder); draw(); return; }
        // Back from the mute page returns to these details.
        openChatMute(key, () => (typeof openDetails === 'function' && active === key ? openDetails().catch(showError) : closeDetailsPage()));
      });
      row.append(button);
    }
    const hide = typeof privateDetailsButton === 'function' ? privateDetailsButton(address) : null;
    if (hide) row.append(hide);
  };
  draw();
  chat.append(row, element('p', 'Muted chats don’t notify or chime, for as long as you choose; messages still arrive. Blocking hides the chat in Blocked here; it doesn’t stop delivery.', 'muted'));
  let wall = null;
  if (typeof wallpaperPicker === 'function') {
    wall = element('section', '', 'settings-section');
    wall.append(element('h3', 'Wallpaper for this chat'));
    const drawWall = () => {
      wall.querySelector('.stack')?.remove();
      wall.append(wallpaperPicker('chat-wallpaper', webLook.chatWallpapers?.[address] || '', (value) => {
        const chats = {...(webLook.chatWallpapers || {})};
        if (value) chats[address] = value; else delete chats[address];
        saveWebLook({...webLook, chatWallpapers: chats}, true);
        applyChatWallpaper();
        drawWall();
      }, true));
    };
    drawWall();
  }
  return {conversation: chat, code: lockSection(address), wallpaper: wall};
}

// Conversation details: a full page in the chat pane, like the phone's (ConversationDetails in ProfileEditor.kt),
// opened from the chat's name or avatar, its ⓘ button, or Details in the inbox menu.

// The sample groups' people, as on the phone (MainActivity SAMPLE_GROUPS). Fictional names, 555-01xx numbers, example.org.
const SAMPLE_MEMBERS = {
  'mms:+12025550131,+12025550142': [['Sam Rivera', '+12025550131'], ['Jordan Ellis', '+12025550142']],
  'sample-photo-walk': [['Ana Ortiz', '+12025550155'], ['Leo Park', '+12025550166'], ['Maya Chen', '+12025550123']],
  'sample-radio-club': [['Kestrel', '@kestrel:example.org'], ['Orbital', '@orbital:example.org'], ['Mosaic', '@mosaic:example.org']],
};
// IRC channels' live member lists and topics, from the server (main.py /api/inbox "channels").
let ircChannels = {};
const looksLikeHandle = (value) => /^[@#!+]|^[^\s@]+@[^\s@]+\.[^\s@]+$|^@?[^\s:]+:[^\s]+$|^\+?[\d ().-]{5,}$/.test(value);

/** Everyone in a group chat besides you: name, address or handle, and protocol. */
function threadMembers(key) {
  if (!key) return [];
  const address = threadAddress(key);
  const protocol = threadProtocol(key);
  const found = [];
  const add = (name, handle = '', via = protocol) => {
    name = String(name || '').trim();
    handle = String(handle || '').trim();
    if (!name && !handle) return;
    const same = (a, b) => !!a && !!b && a.toLowerCase() === b.toLowerCase();
    const known = found.find((member) => same(member.name, name || handle) || same(member.address, handle) || same(member.address, name));
    if (known) { if (!known.address && handle) known.address = handle; return; }
    found.push({name: name || handle, address: handle, protocol: via});
  };
  if (sampleOn) for (const [name, handle] of SAMPLE_MEMBERS[address] || []) add(name, handle);
  if (address.startsWith('mms:')) {
    // Group texts: the numbers are in the address, and the phone sends their names, in order, as the title.
    const numbers = address.slice(4).split(',').filter(Boolean);
    const names = (threads.get(key) || []).find((row) => row.title)?.title?.split(', ') || [];
    numbers.forEach((number, index) => add(names.length === numbers.length ? names[index] : number, number, 'mms'));
  }
  const route = typeof routeFor === 'function' ? routeFor(key) : null;
  for (const name of route?.members || []) add(name, looksLikeHandle(name) ? name : '');
  for (const nick of ircChannels[address]?.members || []) add(nick, nick, 'irc');
  for (const row of threads.get(key) || []) {
    if (row.type === 1 && row.author && !row.event) add(row.author, looksLikeHandle(row.author) ? row.author : '', row.protocol || protocol);
  }
  return found;
}

/** Group chats: group texts, group rooms and channels, the sample groups, and any chat with more than one sender. */
function threadIsGroup(key) {
  if (!key) return false;
  const address = threadAddress(key);
  if (address.startsWith('mms:') || ircChannels[address] || (sampleOn && SAMPLE_MEMBERS[address])) return true;
  const route = typeof routeFor === 'function' ? routeFor(key) : null;
  if (route?.group) return true;
  return new Set((threads.get(key) || []).filter((row) => row.type === 1 && row.author).map((row) => row.author)).size > 1;
}

const protocolName = (protocol) => protocol === 'rcs' ? 'RCS preview' : labels[protocol] || 'SMS';

/** The line under a chat's name: its protocol, or for a group how many people are in it, as on the phone. */
function headerSubtitle(key) {
  const protocol = threadProtocol(key);
  if (automatedThread(key)) return 'SMS · Automated sender';
  // "via alice@example.org" when the service has more than one account (servers.js).
  const via = typeof accountVia === 'function' && typeof routeFor === 'function' ? accountVia(routeFor(key)) : '';
  if (!threadIsGroup(key)) return (sampleOn ? 'Sample ' : '') + protocolName(protocol) + via;
  const count = threadMembers(key).length + 1;
  return (sampleOn ? 'Sample group · ' : '') + count + ' members · ' + protocolName(protocol) + via;
}

function detailsPageNode() {
  let page = document.getElementById('details-page');
  if (!page) {
    page = element('section', '', 'details-page');
    page.id = 'details-page';
    page.hidden = true;
    page.setAttribute('aria-labelledby', 'details-title');
    page.addEventListener('keydown', (event) => {
      if (event.key !== 'Escape' || event.defaultPrevented) return;
      event.preventDefault();
      page.querySelector('.details-head .sheet-back')?.click();
    });
    document.querySelector('.thread-pane').append(page);
  }
  return page;
}

/** Shows [nodes] as a full page over the chat. [back] runs for Back and Esc; by default it returns to the chat. */
function detailsPage(title, nodes, back) {
  const page = detailsPageNode();
  const head = element('div', '', 'details-head');
  const button = element('button', '', 'sheet-back');
  button.type = 'button';
  const arrow = element('span', '‹');
  arrow.setAttribute('aria-hidden', 'true');
  button.append(arrow, document.createTextNode(' Back'));
  button.setAttribute('aria-label', back ? 'Back' : 'Back to the chat');
  button.addEventListener('click', () => (back ? back() : closeDetailsPage()));
  const heading = element('h2', title);
  heading.id = 'details-title';
  heading.tabIndex = -1;
  head.append(button, heading);
  const body = element('div', '', 'details-body');
  body.append(...nodes.filter(Boolean));
  page.replaceChildren(head, body);
  page.hidden = false;
  get('workspace').dataset.details = 'true';
  page.scrollTop = 0;
  heading.focus({preventScroll: true});
}

function closeDetailsPage(focus = true) {
  const page = document.getElementById('details-page');
  if (!page || page.hidden) return;
  page.hidden = true;
  page.replaceChildren();
  delete get('workspace').dataset.details;
  if (focus) (get('details').hidden ? get('conversation') : get('details')).focus();
}

/** The big avatar, name, and line at the top of a details page. */
function detailsHero(name, line, lock) {
  const hero = element('div', '', 'details-hero');
  hero.append(typeof avatarFor === 'function' ? avatarFor(name, 'details-avatar') : element('span', initials(name), 'avatar details-avatar'),
    element('h3', name));
  if (line) hero.append(element('p', line, 'muted'));
  if (lock) hero.append(lock);
  return hero;
}

/** One labelled row with a value and Copy, for addresses and numbers. */
function detailRow(label, value, extra) {
  const row = element('div', '', 'detail-row');
  const words = element('span', '', 'detail-words');
  words.append(element('strong', value), element('small', label));
  row.append(words);
  if (extra) row.append(extra);
  if (navigator.clipboard?.writeText) {
    const copy = element('button', 'Copy', 'quiet small-button');
    copy.type = 'button';
    copy.setAttribute('aria-label', 'Copy ' + value);
    copy.addEventListener('click', () => navigator.clipboard.writeText(value).then(() => { copy.textContent = 'Copied'; }).catch(() => {}));
    row.append(copy);
  }
  return row;
}

/** Every address known for a one-to-one chat: its number or handle, and any address linked to it. */
function contactAddresses(key) {
  const address = threadAddress(key);
  const out = [];
  const add = (label, value) => { if (value && !out.some(([, known]) => known.toLowerCase() === value.toLowerCase())) out.push([label, value]); };
  const route = typeof routeFor === 'function' ? routeFor(key) : null;
  if (!/^(web|sample|irc)-/.test(address)) add(automatedThread(key) ? 'Automated sender · SMS' : protocolName(threadProtocol(key)), address);
  if (route) add(protocolName(route.protocol) + (typeof accountVia === 'function' ? accountVia(route) : ''), route.target);
  for (const linked of (typeof routes === 'undefined' ? [] : routes)) if (linked.address === address) add(protocolName(linked.protocol), linked.target);
  // The same person in another chat, when chats aren't combined.
  const title = threadTitle(key);
  for (const other of threads.keys()) {
    if (other === key || threadTitle(other) !== title || threadIsGroup(other)) continue;
    const otherRoute = typeof routeFor === 'function' ? routeFor(other) : null;
    add(protocolName(threadProtocol(other)), otherRoute ? otherRoute.target : threadAddress(other));
  }
  return out;
}

/** The chat a group member also has with you, if any. */
function memberThread(member) {
  for (const key of threads.keys()) {
    if (threadIsGroup(key)) continue;
    const route = typeof routeFor === 'function' ? routeFor(key) : null;
    const address = threadAddress(key);
    if (member.address && (address === member.address || route?.target?.toLowerCase() === member.address.toLowerCase())) return key;
    if (threadTitle(key) === member.name) return key;
  }
  return '';
}

/** The Members card: everyone in the group. Tapping someone opens their details, with Back returning here. */
function memberList(key, members) {
  const section = element('section', '', 'settings-section');
  section.append(element('h3', 'Members'));
  const list = element('div', '', 'member-list');
  for (const member of members) {
    const row = element('button', '', 'member-row');
    row.type = 'button';
    const words = element('span', '', 'detail-words');
    words.append(element('strong', member.name), element('small', [member.address !== member.name ? member.address : '', protocolName(member.protocol)].filter(Boolean).join(' · ')));
    row.append(typeof avatarFor === 'function' ? avatarFor(member.name) : element('span', initials(member.name), 'avatar'), words,
      element('span', '›', 'chevron'));
    row.setAttribute('aria-label', member.name + ', ' + (member.address && member.address !== member.name ? member.address + ', ' : '') + protocolName(member.protocol) + '. Open details');
    row.addEventListener('click', () => openMemberDetails(key, member));
    list.append(row);
  }
  if (!members.length) list.append(element('p', 'No one else has written here yet.', 'muted'));
  section.append(list);
  return section;
}

/** A group member's contact page (people.js), with Back returning to the group's details. */
function openMemberDetails(groupKey, member) {
  const backToGroup = () => { select(groupKey); openDetails().catch(showError); };
  if (typeof openMemberContact === 'function') { openMemberContact(member, backToGroup).catch(showError); return; }
  const own = memberThread(member);
  if (own) { select(own); openDetails({back: backToGroup}).catch(showError); return; }
  const info = element('section', '', 'settings-section');
  info.append(element('h3', 'Contact info'));
  if (member.address) info.append(detailRow(protocolName(member.protocol), member.address));
  else info.append(element('p', 'Only their name is known here.', 'muted'));
  const shared = element('section', '', 'settings-section');
  shared.append(element('h3', 'In this group'), element('p', threadTitle(groupKey), 'muted'));
  const nodes = [detailsHero(member.name, (sampleOn ? 'Sample contact · ' : '') + protocolName(member.protocol)), info, shared];
  // A number can get a new text; it waits for your phone.
  if (!sampleOn && /^\+?[\d ().-]{5,}$/.test(member.address) && senderAddress(member.address)) {
    const text = element('button', 'Text ' + member.name, 'primary');
    text.type = 'button';
    text.addEventListener('click', () => { closeDetailsPage(false); select(''); get('address').value = member.address; get('body').focus(); });
    info.append(text);
  }
  detailsPage(member.name, nodes, backToGroup);
}

/** Photos and files sent in this chat, newest first. */
function sharedMedia(key) {
  const section = element('section', '', 'settings-section');
  section.append(element('h3', 'Photos and files'));
  const files = (threads.get(key) || []).filter((row) => row.attachment).reverse().slice(0, 24);
  if (!files.length) { section.append(element('p', 'Photos, videos, and files you share here show up here.', 'muted')); return section; }
  const grid = element('div', '', 'media-grid');
  for (const message of files) {
    const tile = element('div', '', 'media-tile');
    if (!(typeof attachmentPicture === 'function' && attachmentPicture(message, tile))) {
      const file = element('button', '📎 ' + message.attachment.name, 'file-card');
      file.type = 'button';
      file.addEventListener('click', () => download(message).catch(showError));
      tile.append(file);
    }
    grid.append(tile);
  }
  section.append(grid);
  return section;
}

// The chat's name and avatar open its details too.
(() => {
  const info = get('head-info');
  const open = () => { if (active && threadAddress(active)) openDetails().catch(showError); };
  info.addEventListener('click', open);
  info.addEventListener('keydown', (event) => {
    if (event.key !== 'Enter' && event.key !== ' ') return;
    event.preventDefault();
    open();
  });
  const sync = () => {
    const on = !!active && !!threadAddress(active);
    info.classList.toggle('opens', on);
    if (on) { info.setAttribute('role', 'button'); info.tabIndex = 0; info.setAttribute('aria-label', 'Conversation details for ' + threadTitle(active)); }
    else { info.removeAttribute('role'); info.removeAttribute('tabindex'); info.removeAttribute('aria-label'); }
  };
  new MutationObserver(sync).observe(get('recipient'), {childList: true, characterData: true, subtree: true});
  sync();
})();

// More in a message's ⋯ menu (app.js messageActions): any emoji, select text, share, details, and delete for me.

function firstEmoji(text) {
  const value = text.trim();
  const first = window.Intl?.Segmenter ? [...new Intl.Segmenter(undefined, {granularity: 'grapheme'}).segment(value)][0]?.segment : [...value][0];
  return first && isEmoji(first) ? first : null;
}

function moreMessageActions(message, row, {action, quick, more, sendable}) {
  const menu = () => row.querySelector('details.message-tools');
  if (sendable && message.body) {
    const plus = element('button', '+', 'more-emoji');
    plus.type = 'button';
    plus.setAttribute('aria-label', 'More emoji');
    plus.addEventListener('click', () => {
      const existing = quick.parentElement.querySelector('.emoji-form');
      if (existing) { existing.remove(); return; }
      const form = element('form', '', 'emoji-form');
      const input = Object.assign(element('input', ''), {placeholder: 'Type or paste an emoji', maxLength: 16, autocomplete: 'off'});
      input.setAttribute('aria-label', 'Emoji');
      const go = element('button', 'React', 'primary');
      form.append(input, go);
      form.addEventListener('submit', (event) => {
        event.preventDefault();
        const emoji = firstEmoji(input.value);
        if (!emoji) { input.setCustomValidity('Use an emoji'); input.reportValidity(); return; }
        menu().open = false;
        reactWith(message, row, emoji);
      });
      input.addEventListener('input', () => input.setCustomValidity(''));
      quick.after(form);
      input.focus();
    });
    quick.append(plus);
  }
  if (message.body && !message.attachment) {
    action('Select text', () => {
      const sheet = dialog('Select text');
      const text = element('p', (typeof parseEffect === 'function' && parseEffect(message.body)?.words) || message.body, 'selectable settings-section');
      sheet.append(text);
      sheet.showModal();
      const range = document.createRange();
      range.selectNodeContents(text);
      getSelection().removeAllRanges();
      getSelection().addRange(range);
    });
    if (navigator.share) action('Share', () => navigator.share({text: message.body}).catch(() => {}));
  }
  action('Message details', () => messageDetails(message));
  const removable = sampleOn || (internet(message.protocol) && /^[A-Za-z0-9_-]{1,120}$/.test(message.id || ''));
  if (removable && typeof confirmButton === 'function') {
    more.append(confirmButton('Delete for me', 'Tap again to delete', async () => {
      menu().open = false;
      await deleteForMe(message);
    }, 'delete-for-me'));
  }
}

async function deleteForMe(message) {
  if (sampleOn) inbox = inbox.filter((row) => row.id !== message.id);
  else {
    try { await api('protocols/delete', {id: message.id}); } catch (error) { showError(error); return; }
    inbox = inbox.filter((row) => row.id !== message.id);
  }
  rebuildThreads();
  showThreads();
  showMessages(true);
  say('Deleted from FluidMessages Web. Other people keep their copy.');
}

function messageDetails(message) {
  const sheet = dialog('Message details');
  const box = element('section', '', 'settings-section');
  const list = element('dl', '', 'details-list');
  const add = (term, value) => { if (value) list.append(element('dt', term), element('dd', value)); };
  const time = new Date(message.date);
  add('Direction', message.type === 1 ? 'Incoming' : 'Outgoing');
  add('From', message.type === 1 ? message.author || threadTitle(active) : 'You');
  add('When', time.toLocaleDateString([], {weekday: 'long', year: 'numeric', month: 'long', day: 'numeric'}) + ' · ' + time.toLocaleTimeString());
  if (message.sentAt > 0 && message.sentAt !== message.date) add('Sent by their phone', new Date(message.sentAt).toLocaleString());
  add('Sent with', labels[message.protocol || 'sms'] || 'SMS');
  add('Status', message.status || ([4, 6].includes(message.type) ? 'Sending' : message.type === 5 ? 'Not delivered' : ''));
  if (message.attachment) add('File', message.attachment.name + ' · ' + Math.max(1, Math.round((message.attachment.size || 0) / 1024)) + ' KB');
  if (message.reactions?.size) add('Reactions', [...message.reactions].map(([who, emoji]) => emoji + ' ' + (who === 'me' ? 'you' : who.replace(/^from:/, '') || 'them')).join(', '));
  box.append(list);
  if (message.earlier?.length) {
    box.append(element('h3', 'Earlier versions'));
    for (const version of message.earlier) box.append(element('p', typeof plainPreview === 'function' ? plainPreview(version) : version, 'earlier-version'));
  }
  sheet.append(box);
  sheet.showModal();
}

// The sample chats: fictional people and messages, drawn only in this page. Nothing is sent or saved.

let sampleOn = false;
let sampleSeq = 0;
let sampleChats = NO_CHATS();
let sampleSender = 'me';
let sampleSaved = null;
const sampleBlobs = new Map();

// Each visit's messages get new ids, so an effect sent in the sample inbox plays even after an earlier visit.
let sampleRun = '';
const sampleId = () => 'sample-' + sampleRun + '-' + (++sampleSeq);

function sampleMessages() {
  sampleRun = Date.now().toString(36);
  const now = Date.now();
  const minute = 60000;
  const rows = [];
  const add = (address, protocol, title, body, type, ago, author = '', read = 1) => rows.push({id: sampleId(), address, protocol, title, body,
    type, date: now - ago * minute, read, author});
  const maya = '+12025550123';
  add(maya, 'sms', 'Maya Chen', 'Coffee at ten?', 1, 95);
  // A chat setting notice, shown locally as the outlined note a real one gets (cards.js).
  if (typeof timerNoticeText === 'function') add(maya, 'sms', 'Maya Chen', timerNoticeText(86400), 2, 92);
  add(maya, 'sms', 'Maya Chen', 'See you there!', 2, 90);
  add(maya, 'sms', 'Maya Chen', 'Bring the *good* beans this time ☕ and ||the secret recipe||', 1, 88);
  // Maya likes your message. " (my message)" would mean she liked her own, so it wouldn't fold onto yours.
  add(maya, 'sms', 'Maya Chen', 'Liked “See you there!”', 1, 87);
  const plans = 'mms:+12025550131,+12025550142';
  add(plans, 'mms', 'Weekend Plans', 'Who’s in for Saturday?', 1, 300, 'Sam Rivera');
  add(plans, 'mms', 'Weekend Plans', '📊 Poll: Where should we hike?\n1. Ridge loop\n2. Lake trail\n3. Pine meadow\nReply with a number to vote · poll smpl01', 2, 290);
  add(plans, 'mms', 'Weekend Plans', '2', 1, 280, 'Jordan Ellis');
  add(plans, 'mms', 'Weekend Plans', '🗳️ Ridge loop · voted in “Where should we hike?” (poll smpl01)', 1, 275, 'Sam Rivera');
  const saturday = new Date(now + 3 * 86400000);
  saturday.setHours(9, 0, 0, 0);
  if (typeof eventText === 'function') {
    add(plans, 'mms', 'Weekend Plans', eventText({title: 'Saturday hike', start: saturday.getTime(), end: saturday.getTime() + 4 * 3600000, allDay: false,
      location: 'Ridge trailhead', notes: 'Bring water and a snack'}), 2, 260);
    add(plans, 'mms', 'Weekend Plans', 'yes', 1, 250, 'Jordan Ellis');
  }
  const walk = 'sample-photo-walk';
  add(walk, 'rcs', 'Photo Walk', 'Golden hour starts at six!\n(sent with Confetti 🎉)', 1, 40, 'Ana Ortiz');
  add(walk, 'rcs', 'Photo Walk', '🎲 Rolled 2d6: 3 + 5 = 8', 2, 35);
  // Two unread chats, for the inbox filter's Unread.
  add(walk, 'rcs', 'Photo Walk', 'I’ll bring the tripod', 1, 30, 'Leo Park', 0);
  // An automated sender (a short code), like the phone's Trail Alerts; your reply shows in the automated color.
  add('55123', 'sms', 'Trail Alerts', 'Trail update: the south entrance is open. Reply STOP to stop receiving these messages.', 1, 600);
  add('55123', 'sms', 'Trail Alerts', 'STOP', 2, 598);
  const radio = 'sample-radio-club';
  add(radio, 'matrix', 'Radio Club', 'Antenna night is Thursday', 2, 70);
  add(radio, 'matrix', 'Radio Club', '✏️ Edited “Antenna night is Thursday” to: Antenna night is Friday at 7', 2, 69);
  add(radio, 'matrix', 'Radio Club', 'Loved “Antenna night is Friday at 7”', 1, 60, 'Kestrel');
  add(radio, 'matrix', 'Radio Club', 'I can bring the analyzer', 1, 58, 'Kestrel', 0);
  add('+12025550188', 'sms', 'Old Neighbor', 'Thanks again for the ladder!', 1, 4000);
  add('+12025550199', 'sms', '', 'You won a prize! Reply YES to claim', 1, 2000);
  return rows.sort((a, b) => b.date - a.date);
}

function sampleBanner() {
  let banner = document.getElementById('sample-banner');
  if (!sampleOn) { banner?.remove(); return; }
  if (!banner) {
    banner = element('div', '', 'sample-banner');
    banner.id = 'sample-banner';
    banner.setAttribute('role', 'status');
    document.querySelector('.sidebar-head').after(banner);
  }
  banner.replaceChildren();
  const words = element('span', '');
  words.append(element('strong', 'Sample inbox — nothing here is sent'), element('small', 'Fictional people. Your own chats come back when you exit.'));
  const exit = element('button', 'Exit', 'quiet');
  exit.type = 'button';
  exit.setAttribute('aria-label', 'Exit the sample inbox');
  exit.addEventListener('click', exitSample);
  banner.append(words, exit);
  // Send as: you, or someone in the group, to try reactions, polls, and games from both sides.
  const people = [...new Set((threads.get(active) || []).filter((row) => row.author).map((row) => row.author))];
  if (people.length) {
    const who = element('select', '');
    for (const name of ['me', ...people]) who.append(Object.assign(element('option', name === 'me' ? 'Send as me' : 'Send as ' + name), {value: name}));
    who.value = people.includes(sampleSender) ? sampleSender : 'me';
    who.setAttribute('aria-label', 'Send as');
    who.addEventListener('change', () => { sampleSender = who.value; });
    banner.append(who);
  } else sampleSender = 'me';
}

let sampleExit = null;

/** Opens the sample inbox. [onExit] runs after Exit, like returning to the setup guide (welcome.js). */
function startSample(options) {
  closeDetailsPage(false);
  if (!sampleOn || typeof options?.onExit === 'function') sampleExit = typeof options?.onExit === 'function' ? options.onExit : null;
  if (typeof showSection === 'function') showSection('messages');  // From the Settings tab too.
  if (!sampleOn) sampleSaved = {routes: typeof routes === 'undefined' ? [] : routes};
  sampleOn = true;
  sampleSeq = 0;
  sampleChats = {...NO_CHATS(), pinned: ['mms:+12025550131,+12025550142'], archived: ['+12025550188'], blocked: ['+12025550199']};
  inbox = sampleMessages();
  active = '';
  delete get('workspace').dataset.thread;
  get('schedule').hidden = true;
  get('recipient').textContent = 'Messages';
  get('transport').textContent = '';
  get('avatar').textContent = '';
  rebuildThreads();
  get('folder').value = 'Inbox';
  get('search').value = '';
  showThreads();
  showMessages();
  sampleBanner();
  if (typeof showSection === 'function') showSection('messages');
  get('threads').querySelector('.thread')?.focus();
}

function exitSample() {
  closeDetailsPage(false);
  sampleOn = false;
  sampleBanner();
  inbox = [];
  threads.clear();
  active = '';
  delete get('workspace').dataset.thread;
  get('schedule').hidden = true;
  get('recipient').textContent = 'Messages';
  get('transport').textContent = '';
  get('avatar').textContent = '';
  if (sampleSaved && typeof routes !== 'undefined') routes = sampleSaved.routes;
  refreshed = false;
  lastLook = '';
  refresh().catch(showError);
  showMessages();
  const then = sampleExit;
  sampleExit = null;
  if (then) then();
}

/** The sample chats never reach the server (app.js refresh). */
function sampleRefresh() {
  if (sampleOn) sampleBanner();
  return sampleOn;
}

/** In the sample chats, a "sent" message is added to this page only (app.js send and queueSms). */
function sampleSend(body, attachment) {
  if (!sampleOn) return false;
  const key = active;
  const first = (threads.get(key) || [])[0] || {};
  const other = sampleSender !== 'me';
  const message = {id: sampleId(), address: threadAddress(key), protocol: threadProtocol(key), title: first.title || '', body,
    type: other ? 1 : 2, date: Date.now(), read: 1, author: other ? sampleSender : '', status: other ? '' : 'Sample · not sent'};
  const file = attachment?.upload && sampleBlobs.get(attachment.upload);
  if (file) {
    message.attachment = {name: file.name, mime: file.blob.type, size: file.blob.size, kind: attachment.kind || 'file', duration: attachment.duration || 0};
    // Shown from this page's copy, the way received files are shown from the server's.
    if (typeof cardBlobs !== 'undefined') cardBlobs.set('/api/protocols/file?id=' + message.id, Promise.resolve(blobUrl(file.blob)));
  }
  inbox = [message, ...inbox];
  rebuildThreads();
  showThreads();
  showMessages();
  say('Sample chat · nothing was sent.');
  return true;
}

// In the sample chats, photos and voice messages stay in this page.
if (typeof uploadFile === 'function') {
  const serverUpload = uploadFile;
  uploadFile = async function sampleAwareUpload(blob, name) {
    if (!sampleOn) return serverUpload(blob, name);
    const id = 'sample-upload-' + sampleRun + '-' + (++sampleSeq);
    sampleBlobs.set(id, {blob, name});
    return id;
  };
}

// Settings pieces (settings.js): chat codes in Privacy and security, and cloud destinations in Backups.

/** Privacy and security → Chat codes. */
function chatCodesSection() {
  const section = element('section', '', 'settings-section');
  section.id = 'settings-chat-codes';
  const codes = Object.keys(webChats.locks || {}).length;
  section.append(element('h3', 'Chat codes'),
    element('p', codes ? codes + (codes === 1 ? ' chat has a code.' : ' chats have codes.') + ' Their previews stay hidden in the inbox and search.'
      : 'Give a chat a code in its details: its preview is hidden and it asks for the code before it opens.', 'muted'));
  const relock = element('button', 'Lock chats with codes now');
  relock.type = 'button';
  relock.disabled = !codes;
  relock.addEventListener('click', () => { unlockedAddress = ''; showMessages(); say('Chats with codes are locked.'); relock.textContent = 'Locked'; });
  section.append(relock);
  return section;
}

/** Privacy and security → Keep things private: what stays on the phone. */
function privateNoteSection() {
  const section = element('section', '', 'settings-section');
  section.append(element('h3', 'Your phone and this browser'),
    element('p', 'Your phone’s private folder and decoy chats never sync here, and FluidMessages Web hides its copies. Messages sent '
      + 'from the browser aren’t FluidMessages-encrypted; that key stays on your phone. On another device, FluidMessages Web always asks for its password.', 'muted'));
  return section;
}

/** Backups → Cloud destinations set up on this server. */
function cloudBackupsSection() {
  const section = element('section', '', 'settings-section');
  section.id = 'settings-backups';
  const where = element('p', 'Checking…', 'muted');
  section.append(element('h3', 'Cloud backups'), element('p', 'FluidMessages for Android makes encrypted backups (.fmb) and can send them through '
    + 'this server to OneDrive, Google Drive, or S3. Chats and accounts here live in the server’s data volume; back that up with your server.', 'muted'), where);
  api('backups').then((result) => {
    const names = {onedrive: 'OneDrive', drive: 'Google Drive', s3: 'S3'};
    where.textContent = result.targets.length ? 'Cloud destinations set up on this server: ' + result.targets.map((key) => names[key] || key).join(', ') + '.'
      : 'No cloud destinations are set up on this server. See the self-hosting guide to add OneDrive, Google Drive, or S3 with rclone.';
  }).catch(() => { where.textContent = ''; });
  return section;
}
