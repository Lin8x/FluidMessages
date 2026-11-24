'use strict';

const get = (id) => document.getElementById(id);
const colors = {
  ink: 'ink', muted: 'muted', surface: 'surface', raised: 'raised', line: 'line', outline: 'outline', action: 'action',
  canvas: 'canvas', incoming: 'incoming', incomingInk: 'incoming-ink', sms: 'sms', smsInk: 'sms-ink',
  automated: 'automated', automatedInk: 'automated-ink', accent: 'accent', accentInk: 'accent-ink',
  rcs: 'rcs', rcsInk: 'rcs-ink', matrix: 'matrix', matrixInk: 'matrix-ink', xmpp: 'xmpp', xmppInk: 'xmpp-ink',
  email: 'email', emailInk: 'email-ink', irc: 'irc', ircInk: 'irc-ink',
};
// Same order and names as the phone. RCS, SMS, and MMS share one text conversation.
const labels = {rcs: 'RCS', sms: 'SMS', mms: 'MMS', matrix: 'Matrix', xmpp: 'XMPP', email: 'Email', irc: 'IRC'};
const family = (protocol) => ['rcs', 'mms', undefined, ''].includes(protocol) ? 'sms' : protocol;
let enabled = new Set(['sms', 'mms', 'matrix', 'xmpp', 'email', 'irc']);
const verbs = [['Liked', '👍'], ['Loved', '❤️'], ['Disliked', '👎'], ['Laughed at', '😆'],
  ['Emphasized', '‼️'], ['Questioned', '❓']];
const jobStates = {scheduled: 'Queued', claimed: 'Claimed by phone', submitted: 'Submitted by phone',
  failed: 'Phone submission failed', cancelled: 'Cancelled'};
const drafts = new Map();
// Signed in with an HttpOnly session cookie, so no token is kept where page scripts could read it.
let signedIn = false;
let session = 0;
let active = '';
let appearance = {};
let phoneLook = {};
let webLook = {theme: 'fluid', mode: 'system'};
let followPhone = true;
let inbox = [];
let threads = new Map();
let updated = null;
let reply = '';
let refreshed = false;
let refreshNumber = 0;
let lastLook = '';
// Fun and effects, set in Settings for this server. Everything is on until turned off.
let prefs = {effects: true, reactionEffects: true, games: true, random: true, gifs: true, linkPreviews: true};
// Chat accounts on this server (Matrix, XMPP, Email, IRC), for empty states and New chat.
let chatAccounts = [];
// Signed in as this computer, which opens without a password, so there's nothing to sign out of.
let localHere = false;
let lastLocalSignIn = 0;
let welcomed = true;
// Which internet protocols this server sends and receives (Settings → Chat services).
let gatewayEnabled = ['matrix', 'xmpp', 'email', 'irc'];
const phonePaired = () => updated !== null && updated !== undefined;

// Every request says it comes from this page; the server refuses cookie requests that don't (CSRF protection).
const REQUEST_HEADERS = {'X-Fluid-Request': '1'};

async function api(path, body) {
  const response = await fetch('/api/' + path, {
    method: body === undefined ? 'GET' : 'POST',
    credentials: 'same-origin',
    headers: body === undefined ? REQUEST_HEADERS : {...REQUEST_HEADERS, 'Content-Type': 'application/json'},
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  let result = {};
  try { result = await response.json(); } catch (error) { /* not JSON */ }
  if (response.status === 401 && result.signed_out && signedIn && !path.startsWith('auth/')) {
    signOutHere('You were signed out. Sign in again to keep going.');
  }
  if (!response.ok) throw new Error(result.error || 'Something went wrong. Try again.');
  return result;
}

// Themes, the same named palettes as the phone (MessageStyle.java), light and dark.
// [canvas, sms, rcs, controls, chrome, automated, [matrix, xmpp, email, irc]]
const RCS_LIGHT = '#9643eb', RCS_DARK = '#a458f9';
const THEMES = {
  fluid: {label: 'Fluid', light: ['#ffffff', '#0088ff', RCS_LIGHT, '#007aff', '#f2f2f7', '#34c759', ['#ff2d55', '#ff2d55', '#ff8d28', '#ff383c']],
    dark: ['#101012', '#0091ff', RCS_DARK, '#007aff', '#1c1c1e', '#30d158', ['#ff375f', '#ff375f', '#ff9230', '#ff4245']]},
  midnight: {label: 'Midnight', light: ['#f8f9fe', '#0088ff', RCS_LIGHT, '#007aff', '#eceefa', '#34c759', ['#ff2d55', '#e0408a', '#ff8d28', '#ff383c']],
    dark: ['#08080b', '#0091ff', RCS_DARK, '#007aff', '#23242c', '#30d158']},
  lavender: {label: 'Lavender', light: ['#fcf8ff', '#7564d8', '#c88330', '#5c43b2', '#efe7fa', '#2f9e83', ['#c4508f', '#a955b8', '#4f86c6', '#cf4a4a']],
    dark: ['#15111e', '#9481f4', '#e5ab65', '#c1a8ff', '#2e2441', '#4fc6a6']},
  evergreen: {label: 'Evergreen', light: ['#f5fcf8', '#2d9e78', '#ac8126', '#176249', '#e6f4ec', '#3f72c4', ['#c2507a', '#9a5bb5', '#d9603a', '#d13f3f']],
    dark: ['#0d1814', '#52c59a', '#d3a846', '#8bdabb', '#1b372d', '#74a3f0']},
  tideline: {label: 'Tideline', light: ['#f5fbff', '#308fd3', '#d37548', '#225d97', '#e4f2fa', '#3a9a4a', ['#c24f86', '#8a62c9', '#c9a227', '#c8434a']],
    dark: ['#0e1721', '#54b4f6', '#f5a370', '#9fdaff', '#21374a', '#5cc46d']},
  ember: {label: 'Ember', light: ['#fff9f1', '#e28b48', '#ce6c6e', '#923c24', '#faeee0', '#5e8f2e', ['#b0508f', '#7d5fc0', '#3f86b8', '#2f9a8c']],
    dark: ['#1c120e', '#f1aa68', '#f29194', '#ffbc8a', '#38241b', '#8fc25a']},
  love: {label: 'Love', light: ['#fff4f8', '#c14770', '#947029', '#9b274f', '#ffe6ef', '#2c8a6e', ['#8a5cc4', '#5b6fd0', '#e0603a', '#3a8fbf']],
    dark: ['#211018', '#cf507f', '#d6aa56', '#ffa6c2', '#3d2231', '#4fbf98']},
  rosewater: {label: 'Rosewater', light: ['#fff8fa', '#d5779f', '#b7784d', '#a13567', '#fbe9f0', '#4f8a66', ['#8c67c7', '#5f7fce', '#d4c13a', '#3d93b8']],
    dark: ['#1e1119', '#f59abf', '#d99d73', '#ffafd0', '#3a222d', '#7cbf93']},
};
const BUBBLES = ['incoming', 'sms', 'automated', 'rcs', 'matrix', 'xmpp', 'email', 'irc'];
const LAYOUT = {radius: 18, size: 16, padding: 12};

const rgb = (hex) => [1, 3, 5].map((index) => parseInt(hex.slice(index, index + 2), 16) / 255);
const hexOf = (values) => '#' + values.map((value) => Math.round(Math.max(0, Math.min(1, value)) * 255).toString(16).padStart(2, '0')).join('');
const mixColor = (a, b, amount) => { const from = rgb(a), to = rgb(b); return hexOf(from.map((value, index) => value + (to[index] - value) * amount)); };
function luminance(hex) {
  const [r, g, b] = rgb(hex).map((value) => (value <= .04045 ? value / 12.92 : ((value + .055) / 1.055) ** 2.4));
  return .2126 * r + .7152 * g + .0722 * b;
}
const contrast = (a, b) => { const [x, y] = [luminance(a), luminance(b)].sort((m, n) => n - m); return (x + .05) / (y + .05); };
const chromatic = (hex) => { const values = rgb(hex); return Math.max(...values) - Math.min(...values) >= .12; };
// Black or white, whichever reads better: for neutral bubbles and controls.
const textOn = (hex) => (contrast(hex, '#ffffff') >= contrast(hex, '#000000') ? '#ffffff' : '#000000');
// The phone's rule: colored bubbles get white text, and bright colors are shaded (same hue) until white reads at 4.5:1.
function bubbleFill(hex) {
  if (!chromatic(hex) || luminance(hex) <= .1833) return hex;
  let light = 0, dark = 1;
  for (let step = 0; step < 14; step++) {
    const middle = (light + dark) / 2;
    if (luminance(mixColor(hex, '#000000', middle)) <= .1833) dark = middle; else light = middle;
  }
  return mixColor(hex, '#000000', dark);
}
const bubbleInk = (hex) => (chromatic(hex) ? '#ffffff' : textOn(hex));
// Moves a color toward the text color until it reaches the contrast asked for, for readable muted text and outlines.
function readable(color, background, ink, ratio) {
  let value = color;
  for (let step = 0; step < 20 && contrast(value, background) < ratio; step++) value = mixColor(value, ink, .12);
  return value;
}
const lift = (hex) => hexOf(rgb(hex).map((value) => value + (1 - value) * .22));

function prefersDark() { return !!window.matchMedia?.('(prefers-color-scheme: dark)').matches; }

/** The web app's own look: a named theme in light or dark, your color changes, and bubble layout. */
function themeAppearance(look) {
  const theme = THEMES[look.theme] || THEMES.fluid;
  const dark = look.mode === 'dark' || (look.mode !== 'light' && prefersDark());
  const [canvas, sms, rcs, controls, chrome, automated, protocols] = theme[dark ? 'dark' : 'light'];
  const protocolColors = theme[dark ? 'dark' : 'light'][6] || theme.light[6].map(dark ? lift : (value) => value);
  const ink = textOn(canvas);
  const surface = mixColor(canvas, chrome, .72);
  const own = look.colors || {};
  const base = {incoming: own.incoming || (dark ? '#2c2c2e' : '#e5e5ea'), sms: own.sms || sms, automated: own.automated || automated,
    rcs: own.rcs || rcs, matrix: own.matrix || protocolColors[0], xmpp: own.xmpp || protocolColors[1], email: own.email || protocolColors[2],
    irc: own.irc || protocolColors[3]};
  const colors = {canvas, ink, surface, raised: chrome,
    muted: readable(mixColor(ink, surface, .4), surface, ink, 4.5),
    line: mixColor(surface, ink, .14), outline: readable(mixColor(surface, ink, .3), canvas, ink, 3),
    action: readable(controls, canvas, ink, 3), accent: bubbleFill(controls), accentInk: bubbleInk(controls)};
  for (const key of BUBBLES) colors[key] = base[key];
  return {dark, colors, exactTimes: !!look.exactTimes, combineProtocols: !!look.combineProtocols, announceSettings: look.announceSettings !== false,
    tagMode: look.tagMode || 'auto', encryptionMode: look.encryptionMode || 'auto',
    radius: look.radius ?? LAYOUT.radius, size: look.size ?? LAYOUT.size, padding: look.padding ?? LAYOUT.padding};
}

// Facts about the phone that hold whichever settings you use: which protocols it has on, MMS, and its folders.
const PHONE_FACTS = ['enabledProtocols', 'mmsReady', 'folders', 'protocolDefaults', 'protocolPrivacy', 'senderTypes'];

/** The phone's settings when it's connected and you chose them (the default), otherwise the web app's own. */
function usingPhoneLook() { return followPhone && !!phoneLook.colors && Object.keys(phoneLook.colors).length > 0; }
function resolvedAppearance() {
  if (usingPhoneLook()) return phoneLook;
  const facts = {};
  for (const key of PHONE_FACTS) if (key in phoneLook) facts[key] = phoneLook[key];
  return {...facts, ...themeAppearance(webLook)};
}
window.matchMedia?.('(prefers-color-scheme: dark)').addEventListener?.('change', () => {
  if (signedIn && !usingPhoneLook()) { applyAppearance(resolvedAppearance()); showThreads(); showMessages(true); }
});

function element(tag, value, className) {
  const node = document.createElement(tag);
  node.textContent = value;
  if (className) node.className = className;
  return node;
}

function applyAppearance(next) {
  appearance = next || {};
  const root = document.documentElement;
  root.dataset.mode = appearance.dark ? 'dark' : 'light';
  const valid = (value) => typeof value === 'string' && /^#[a-f\d]{6}$/i.test(value);
  const palette = {...(appearance.colors || {})};
  // White text on colored bubbles, bright fills shaded, and black or white on neutral ones, whichever side set the colors.
  for (const key of BUBBLES) {
    if (!valid(palette[key])) continue;
    palette[key + 'Ink'] = bubbleInk(palette[key]);
    palette[key] = bubbleFill(palette[key]);
  }
  if (valid(palette.accent)) { palette.accentInk = bubbleInk(palette.accent); palette.accent = bubbleFill(palette.accent); }
  for (const [key, name] of Object.entries(colors)) {
    const value = palette[key];
    if (valid(value)) root.style.setProperty('--' + name, value);
    else root.style.removeProperty('--' + name);
  }
  const themeColor = document.querySelector('meta[name="theme-color"]');
  if (themeColor) themeColor.content = valid(palette.surface) ? palette.surface : (appearance.dark ? '#202126' : '#f2f3f6');
  for (const key of ['radius', 'size', 'padding']) {
    if (Number.isInteger(appearance[key])) root.style.setProperty('--bubble-' + key, appearance[key] + 'px');
    else root.style.removeProperty('--bubble-' + key);
  }
  // Wallpaper, inbox layout, and motion (look.js).
  if (typeof applyLookExtras === 'function') applyLookExtras();
}

// The phone's rule (SenderStyle.infer): five- or six-digit short codes and short letter sender IDs.
function automated(address) {
  const value = String(address || '').trim();
  return /^[0-9]{5,6}$/.test(value) || (/^[A-Za-z0-9 ._-]{2,20}$/.test(value) && /[A-Za-z]/.test(value));
}

function senderAddress(value) {
  return /^(?=.*\d)[+\d*#(). -]{1,40}$/.test(value);
}

function timeOf(message) {
  return appearance.exactTimes && message.sentAt > 0 ? message.sentAt : message.date;
}

function clock(time) {
  return new Date(time).toLocaleTimeString([], {hour: 'numeric', minute: '2-digit'});
}

function day(time) {
  const date = new Date(time);
  if (date.toDateString() === new Date().toDateString()) return 'Today';
  return date.toLocaleDateString([], {weekday: 'short', month: 'short', day: 'numeric'});
}

function initials(address) {
  // First letters of the first two words, so "#radio-club" reads RC and "Maya Chen" reads MC.
  const words = address.split(/[\s._-]+/).map((word) => [...word].find((value) => /\p{L}/u.test(value))).filter(Boolean);
  return words.slice(0, 2).join('').toUpperCase() || '●';
}

// The phone's photo-less avatars: one of five gradients, picked from the name (MessagingScreens.kt Avatar).
function avatarTint(name) {
  // Java's String.hashCode and Math.floorMod, so a name gets the same colors here as on the phone.
  let hash = 0;
  const text = String(name || '');
  for (let index = 0; index < text.length; index++) hash = (Math.imul(hash, 31) + text.charCodeAt(index)) | 0;
  return String(((hash % 5) + 5) % 5);
}
function setAvatar(node, name) {
  node.textContent = name ? initials(name) : '';
  if (name) node.dataset.tint = avatarTint(name); else delete node.dataset.tint;
  return node;
}

function excerpt(value, limit) {
  const chars = Array.from(value);
  return chars.length > limit ? chars.slice(0, limit).join('') + '…' : value;
}

const nouns = {'a like': '👍', 'a heart': '❤️', 'a dislike': '👎', 'a laugh': '😆', 'an exclamation': '‼️',
  'a question mark': '❓'};
// One emoji, as the phone checks it (isReactionEmoji): a single character, with a pictograph or keycap in it.
const graphemes = window.Intl?.Segmenter ? new Intl.Segmenter(undefined, {granularity: 'grapheme'}) : null;
const isEmoji = (value) => !!value && value.length <= 64 && /[\p{Emoji_Presentation}\p{Extended_Pictographic}\u20E3]/u.test(value)
  && (!graphemes || [...graphemes.segment(value)].length === 1);

// Same wording as the phone: added, removed (as iPhones word it), or changed reactions.
function parseReaction(body) {
  if (typeof body !== 'string') return null;
  const own = body.endsWith(' (my message)');
  const wire = own ? body.slice(0, -13) : body;
  const unquote = (text) => (/^“([\s\S]+)”$/.exec(text) || /^"([\s\S]+)"$/.exec(text) || [])[1];
  const known = verbs.find(([verb]) => wire.startsWith(verb + ' “') || wire.startsWith(verb + ' "'));
  if (known) return {change: 'add', emoji: known[1], quote: unquote(wire.slice(known[0].length + 1)), own};
  let match = /^Reacted (\S{1,64}) to ([\s\S]+)$/.exec(wire);
  if (match && isEmoji(match[1])) return {change: 'add', emoji: match[1], quote: unquote(match[2]), own};
  match = /^Removed (.{1,64}?) from ([\s\S]+)$/.exec(wire);
  if (match && (nouns[match[1]] || isEmoji(match[1]))) {
    return {change: 'remove', emoji: nouns[match[1]] || match[1], quote: unquote(match[2]), own};
  }
  match = /^Changed reaction from (\S{1,64}) to (\S{1,64}) on ([\s\S]+)$/.exec(wire);
  if (match && isEmoji(match[2])) return {change: 'change', emoji: match[2], quote: unquote(match[3]), own};
  return null;
}

// One reaction per sender: yourself for sent messages, otherwise the group member who sent it.
const senderKey = (message) => message.type === 1 ? 'from:' + (message.author || '') : 'me';

// Still sending or failed: kept as its own bubble so its status shows, like the phone's unsettled lines.
const unsettled = (message) => [4, 5, 6].includes(message.type) || /^(Sending|Couldn’t)/.test(message.status || '');

/** Folds edits and reactions sent as text onto the messages they're about, the same way as the phone (foldSmsReactions). */
function fold(history) {
  const shown = [];
  for (const message of history) {
    // "✏️ Edited “old” to: new" replaces the sender's own earlier message.
    const edit = unsettled(message) || message.event || message.attachment ? null : /^✏️ Edited “([\s\S]+?)” to: ([\s\S]+)$/.exec(message.body || '');
    if (edit) {
      const target = [...shown].reverse().find((row) => senderKey(row) === senderKey(message) && !row.attachment
        && excerpt((row.body || '').replace(/”/g, '"'), 300) === edit[1]);
      if (target) { target.earlier = (target.earlier || []).concat(target.body); target.body = edit[2]; continue; }
    }
    const parsed = unsettled(message) || message.event ? null : parseReaction(message.body);
    if (parsed?.quote) {
      const quoted = parsed.quote;
      // Long messages are quoted as an excerpt; some phones shorten them further and end with "…".
      const prefix = quoted.endsWith('…') && quoted.length > 20 ? quoted.slice(0, -1) : null;
      const matches = [...shown].reverse().filter((row) => typeof row.body === 'string' && (row.body === quoted
        || excerpt(row.body, 700) === quoted || (prefix && row.body.startsWith(prefix))));
      const target = (message.reaction_target && shown.find((row) => row.id === message.reaction_target))
        || matches.find((row) => (senderKey(row) === senderKey(message)) === parsed.own)
        || (!parsed.own && matches[0]);
      if (target) {
        const key = senderKey(message);
        target.reactions.delete(key);
        if (parsed.change !== 'remove') target.reactions.set(key, parsed.emoji);
        continue;
      }
    }
    shown.push({...message, reactions: new Map()});
  }
  return shown;
}

function threadKey(message) {
  return appearance.combineProtocols ? message.address : message.address + '\u001f' + family(message.protocol);
}

function threadAddress(key) { return key.split('\u001f')[0]; }
function threadProtocol(key) { return threads.get(key)?.at(-1)?.protocol || key.split('\u001f')[1] || 'sms'; }
function threadTitle(key) {
  const message = (threads.get(key) || []).find((row) => row.title);
  const route = typeof routes === 'undefined' ? null : routes.find((item) => item.address === threadAddress(key));
  return message?.title || route?.title || threadAddress(key);
}
// Short codes and sender IDs are automated only for SMS and MMS from one sender, as on the phone: not RCS, not
// group texts, and not internet IDs, which often contain letters.
function automatedThread(key) {
  if (!key) return false;
  const address = threadAddress(key);
  if (!['sms', 'mms', '', undefined].includes(threadProtocol(key)) || address.startsWith('mms:')) return false;
  // Sender type set in the phone's conversation details: the merged web copy first, then what the phone sent.
  const chosen = (typeof webChats !== 'undefined' && webChats.senders?.[address]) || appearance.senderTypes?.[address];
  if (chosen === 'automated') return true;
  if (chosen === 'person') return false;
  return automated(address);
}
function internet(protocol) { return !['sms', 'mms', 'rcs', undefined, ''].includes(protocol); }

function rebuildThreads() {
  threads = new Map();
  const now = Date.now();
  for (const message of inbox) {
    // Disappearing messages leave the screen when their time is up, before the next sync deletes them.
    if (message.expires && message.expires <= now) continue;
    const key = threadKey(message);
    if (!threads.has(key)) threads.set(key, []);
    threads.get(key).push(message);
  }
  for (const [address, messages] of threads) threads.set(address, fold(messages.reverse()));
}

let replyId = '';
function setReply(text, id = '') {
  reply = text;
  replyId = id;
  get('reply').hidden = !text;
  get('reply-text').textContent = text ? 'Replying to ' + excerpt(text, 130) : '';
}

function quoteCard(title, text) {
  const card = element('div', '', 'quote');
  card.append(element('strong', title), element('span', text));
  return card;
}

function messageActions(message, row) {
  const menu = element('details', '', 'message-tools');
  const summary = element('summary', '');
  summary.append(Object.assign(element('span', '⋯'), {ariaHidden: 'true'}), element('span', 'Message actions', 'visually-hidden'));
  summary.title = 'React, reply, and more';
  menu.append(summary);
  const quick = element('div', '', 'quick-reactions');
  const more = element('div', '', 'message-menu');
  const popover = element('div', '', 'message-popover');
  popover.append(quick, more);
  menu.append(popover);
  menu.addEventListener('keydown', (event) => {
    if (event.key === 'Escape' && menu.open) { event.stopPropagation(); menu.open = false; summary.focus(); }
  });
  // Only one menu open at a time. It floats over the page, placed to stay on screen (placeMessageMenu).
  menu.classList.add('floats');
  menu.addEventListener('toggle', () => {
    if (!menu.open) { delete menu.dataset.via; if (openMenu?.menu === menu) unplaceMessageMenu(); return; }
    for (const other of document.querySelectorAll('details.message-tools[open]')) if (other !== menu) other.open = false;
    placeMessageMenu(menu);
  });
  const action = (label, callback, place = more, aria) => {
    const button = element('button', label);
    button.type = 'button';
    if (aria) button.setAttribute('aria-label', aria);
    button.addEventListener('click', () => { menu.open = false; callback(); });
    place.append(button);
  };
  // Every sample chat can reply and react, group texts included (chats.js).
  const sendable = senderAddress(threadAddress(active)) || internet(threadProtocol(active)) || (typeof sampleOn !== 'undefined' && sampleOn);
  if (sendable) action('Reply', () => { setReply(message.body, message.id); get('body').focus(); });
  action('Forward', () => {
    const author = message.type === 1 ? excerpt(message.address.replace(/[“”\r\n]/g, ' '), 90) : 'Me';
    const prefix = 'Forwarded “';
    const suffix = '” by “' + author + '”';
    const max = 1600 - prefix.length - suffix.length;
    const body = message.body.length <= max ? message.body : message.body.slice(0, max - 1).replace(/[\uD800-\uDBFF]$/, '') + '…';
    // Pick who gets it, on any account; the forwarded text waits in their message box.
    openNewChat(prefix + body + suffix);
  });
  if (sendable) {
    // The phone's reaction strip, in the same order (SmsReaction); + adds any emoji (chats.js).
    for (const [emoji, , name] of REACTIONS) {
      action(emoji, () => reactWith(message, row, emoji), quick, (message.reactions.get('me') === emoji ? 'Remove ' : '') + name);
    }
  }
  if (sendable && message.type !== 1 && !message.attachment && ![4, 5, 6].includes(message.type) && typeof openEditMessage === 'function') {
    action('Edit', () => openEditMessage(message));
  }
  if (message.attachment) action('Download', () => download(message).catch(showError));
  if (message.body && !message.attachment) action('Copy text', () => navigator.clipboard?.writeText(typeof plainPreview === 'function' ? plainPreview(message.body) : message.body));
  // Custom emoji, message details, share, select text, and delete for me (chats.js).
  if (typeof moreMessageActions === 'function') moreMessageActions(message, row, {action, quick, more, sendable});
  if (!quick.children.length) quick.remove();
  pressOpens(row, menu);
  return menu;
}

// Touch and hold a message, or right-click it, to open its menu, like the phone's long-press. On touch screens
// that's the only way in, so no ⋯ sits beside every bubble; with a mouse the ⋯ shows on hover and keyboard focus.
function pressOpens(row, menu) {
  const open = () => {
    menu.dataset.via = 'press';
    if (menu.open) placeMessageMenu(menu); else menu.open = true;
    try { localStorage.setItem('pressHint', 'seen'); } catch (error) { /* private mode */ }
    document.querySelector('.press-hint')?.remove();
  };
  const target = () => row.querySelector('.bubble-wrap');
  row.addEventListener('contextmenu', (event) => {
    if (!target()?.contains(event.target)) return;
    // A selection you made keeps the browser's own menu, so Copy still works.
    const picked = String(getSelection?.() || '');
    if (picked && event.pointerType !== 'touch' && row.contains(getSelection().anchorNode)) return;
    event.preventDefault();
    open();
  });
  let timer = 0, startX = 0, startY = 0;
  const cancel = () => { if (timer > 0) { clearTimeout(timer); timer = 0; } };
  row.addEventListener('pointerdown', (event) => {
    // A new press starts fresh, so a hold that ended without a click doesn't swallow this tap.
    cancel();
    timer = 0;
    if (event.pointerType === 'mouse' || !target()?.contains(event.target)) return;
    startX = event.clientX; startY = event.clientY;
    timer = setTimeout(() => { timer = -1; navigator.vibrate?.(8); open(); }, 480);
  });
  row.addEventListener('pointermove', (event) => {
    if (timer > 0 && Math.hypot(event.clientX - startX, event.clientY - startY) > 10) cancel();
  });
  for (const name of ['pointerup', 'pointercancel', 'pointerleave']) row.addEventListener(name, cancel);
  // The press that opened the menu isn't also a tap on a link, spoiler, or card in the bubble.
  row.addEventListener('click', (event) => { if (timer === -1) { event.preventDefault(); event.stopImmediatePropagation(); timer = 0; } }, true);
}

// The open ⋯ menu floats with fixed coordinates, so the chat's scrolling box can't clip it: above the message when
// there's room, otherwise below it, and moved sideways to stay inside the window, at any width.
let openMenu = null;
function placeMessageMenu(menu) {
  unplaceMessageMenu();
  const popover = menu.querySelector('.message-popover');
  const summary = menu.querySelector('summary');
  const line = menu.closest('.bubble-line') || menu;
  if (!popover || !summary) return;
  const place = () => {
    if (!menu.open || !menu.isConnected) { unplaceMessageMenu(); return; }
    const margin = 8;
    const width = document.documentElement.clientWidth, height = window.innerHeight;
    popover.style.maxWidth = Math.max(160, Math.min(300, width - margin * 2)) + 'px';
    const box = popover.getBoundingClientRect();
    // Opened by touch-and-hold or right-click, or with no ⋯ drawn (touch screens): lined up with the bubble itself.
    const shown = summary.getBoundingClientRect();
    const pressed = menu.dataset.via === 'press' || shown.width < 4;
    const dots = pressed ? (menu.closest('.bubble-line')?.querySelector('.bubble-wrap') || line).getBoundingClientRect() : shown;
    const anchor = line.getBoundingClientRect();
    // A message scrolled out of the chat closes its menu, as menus do.
    const pane = get('conversation').getBoundingClientRect();
    if (anchor.bottom < pane.top || anchor.top > pane.bottom) { menu.open = false; return; }
    let top = anchor.top - box.height - 6;
    if (top < margin) top = anchor.bottom + 6;
    if (top + box.height > height - margin) top = Math.max(margin, height - margin - box.height);
    // Over the bubble, from the ⋯ side: incoming messages open leftward from the ⋯, yours rightward (mirrored in RTL).
    const out = !!menu.closest('.message.out');
    const rtl = getComputedStyle(line).direction === 'rtl';
    let left = pressed ? (out !== rtl ? dots.right - box.width : dots.left) : out !== rtl ? dots.left : dots.right - box.width;
    // Inside the chat's pane when it's wide enough, otherwise inside the window.
    let low = Math.max(margin, pane.left + 4), high = Math.min(width - margin, pane.right - 4);
    if (high - low < box.width) { low = margin; high = width - margin; }
    left = Math.min(Math.max(low, left), Math.max(low, high - box.width));
    popover.style.left = Math.round(left) + 'px';
    popover.style.top = Math.round(top) + 'px';
    popover.classList.add('placed');
  };
  let frame = 0;
  const later = () => { if (!frame) frame = requestAnimationFrame(() => { frame = 0; place(); }); };
  const resize = new ResizeObserver(later);
  resize.observe(popover);
  const box = get('conversation');
  box.addEventListener('scroll', later, {passive: true});
  window.addEventListener('resize', later);
  openMenu = {menu, stop() {
    cancelAnimationFrame(frame);
    resize.disconnect();
    box.removeEventListener('scroll', later);
    window.removeEventListener('resize', later);
    popover.classList.remove('placed');
  }};
  place();
}
function unplaceMessageMenu() { openMenu?.stop(); openMenu = null; }

const REACTIONS = [['👍', 'Liked', 'Thumbs up'], ['❤️', 'Loved', 'Heart'], ['👎', 'Disliked', 'Thumbs down'], ['😆', 'Laughed at', 'Laugh'],
  ['‼️', 'Emphasized', 'Exclamation'], ['❓', 'Questioned', 'Question']];

/** Sends a reaction the phone's way: your current one again takes it back, a different one replaces it. */
function reactWith(message, row, emoji) {
  // In the sample chats you can react as someone in the group; " (my message)" then means their own message.
  const as = typeof sampleOn !== 'undefined' && sampleOn && sampleSender !== 'me' ? 'from:' + sampleSender : 'me';
  if (message.reactions.get(as) !== emoji) playReaction(row, emoji, reactionId(message, as, emoji));
  const own = as === 'me' ? message.type !== 1 : senderKey(message) === as;
  const quote = '“' + excerpt(message.body, 700) + '”' + (own ? ' (my message)' : '');
  const current = message.reactions.get(as);
  // Matrix and XMPP take reactions back and change them natively; the readable text goes everywhere else.
  const native = ['matrix', 'xmpp'].includes(message.protocol);
  const relation = (extra) => native ? {kind: 'reaction', target: message.id || '', emoji, own, ...extra} : undefined;
  const verb = (REACTIONS.find(([symbol]) => symbol === emoji) || [])[1];
  if (current === emoji) {
    const noun = Object.keys(nouns).find((key) => nouns[key] === emoji) || emoji;
    send('Removed ' + noun + ' from ' + quote, '', relation({removed: true})).catch(showError);
  } else if (current) {
    send('Changed reaction from ' + current + ' to ' + emoji + ' on ' + quote, '', relation({previous: current})).catch(showError);
  } else {
    send((verb || 'Reacted ' + emoji + ' to') + ' ' + quote, '', {kind: 'reaction', target: message.id || '', emoji, own}).catch(showError);
  }
}

// A click anywhere else closes an open message menu.
document.addEventListener('click', (event) => {
  for (const open of document.querySelectorAll('details.message-tools[open]')) if (!open.contains(event.target)) open.open = false;
});

// Reactions pop up from the bubble when they arrive, yours or anyone's. Each plays once, never when a chat first opens.
const reactionsSeen = new Set();
const threadsShown = new Set();
const reactionId = (message, sender, emoji) => [message.id || message.date + ':' + message.type, sender, emoji].join('|');

function playReaction(row, emoji, id) {
  if (id) reactionsSeen.add(id);
  if (!prefs.reactionEffects || matchMedia('(prefers-reduced-motion: reduce)').matches || document.documentElement.dataset.motion === 'off'
    || document.hidden || !row?.isConnected) return;
  document.querySelector('.reaction-burst')?.remove();
  const bubble = row.querySelector('.bubble-wrap');
  if (!bubble) return;
  // Built-in reactions play their own animation (cards.js); custom emoji get the pop below.
  if (typeof playReactionEffect === 'function' && playReactionEffect(emoji, bubble.querySelector('.bubble') || bubble, row.classList.contains('out'))) return;
  // Drawn over the page at the bubble's corner, so a refresh redrawing the chat doesn't cut it short.
  const box = bubble.getBoundingClientRect();
  const burst = element('span', '', 'reaction-burst');
  burst.setAttribute('aria-hidden', 'true');
  burst.style.left = (row.classList.contains('out') ? box.left + 6 : box.right - 6) + 'px';
  burst.style.top = (box.top + 6) + 'px';
  burst.append(element('span', emoji, 'burst-main'));
  for (let index = 0; index < 5; index++) {
    const spark = element('span', emoji, 'burst-spark');
    spark.style.setProperty('--dx', Math.round((index - 2) * 22 + (Math.random() * 10 - 5)) + 'px');
    spark.style.setProperty('--delay', (60 + index * 45) + 'ms');
    burst.append(spark);
  }
  document.body.append(burst);
  setTimeout(() => burst.remove(), 1500);
}

function showMessages(preserve = false) {
  const conversation = get('conversation');
  const scrollTop = conversation.scrollTop;
  const atEnd = conversation.scrollHeight - scrollTop - conversation.clientHeight < 48;
  conversation.replaceChildren();
  // A chat with a code asks for it before showing anything (chats.js).
  if (typeof chatLockGate === 'function' && chatLockGate(conversation)) return;
  const history = threads.get(active) || [];
  let lastDay = '';
  let position = -1;
  let newestReaction = null;
  // Runs of bubbles from one sender sit closer together and round their inner corners less, like the phone's.
  const notice = (message) => typeof parseChatNotice === 'function' && !message.attachment && !!parseChatNotice(message.body);
  const sender = (message) => message && !message.event && !notice(message) ? (message.type !== 1 ? 'me' : 'from:' + (message.author || '')) : null;
  for (const message of history) {
    position++;
    const messageDay = day(timeOf(message));
    if (messageDay !== lastDay) conversation.append(element('p', messageDay, 'day'));
    lastDay = messageDay;
    const outgoing = message.type !== 1;
    if (message.event) { conversation.append(element('p', '— ' + message.body, 'event')); continue; }
    // Chat setting notices (disappearing messages, encryption), sent or received, are outlined notes like the phone's.
    if (notice(message)) {
      const note = element('p', message.body + ' · ' + clock(timeOf(message)), 'event notice p-' + (message.protocol || 'sms'));
      note.setAttribute('role', 'note');
      conversation.append(note);
      continue;
    }
    const protocolClass = ' p-' + (message.protocol || 'sms');
    const row = element('div', '', (outgoing ? 'message out' + (automatedThread(active) ? ' automated' : '') : 'message') + protocolClass);
    const before = history[position - 1], after = history[position + 1];
    const sameDay = (other) => other && day(timeOf(other)) === messageDay;
    if (sameDay(before) && sender(before) === sender(message)) row.classList.add('joins-prev');
    if (sameDay(after) && sender(after) === sender(message)) row.classList.add('joins-next');
    // Who said it, in any group, as on the phone (RCS groups too).
    const group = typeof threadIsGroup === 'function' && threadIsGroup(active);
    if (!outgoing && message.author && (internet(message.protocol) || message.protocol === 'mms' || group)) row.append(element('small', message.author, 'author'));
    if (appearance.combineProtocols && message.protocol) row.append(element('small', labels[message.protocol] || '', 'via'));
    const bubble = element('div', '', 'bubble');
    const quoted = /^Reply to (?:“([\s\S]+?)”|"([\s\S]+?)")\s*\r?\n(?:\r?\n)?([\s\S]+)$/.exec(message.body);
    const forwarded = /^Forwarded (?:“([\s\S]+?)”|"([\s\S]+?)") by (?:“([^”\r\n]+)”|"([^"\r\n]+)")$/.exec(message.body);
    if (quoted) bubble.append(quoteCard('Replying to', quoted[1] || quoted[2]), element('p', quoted[3]));
    else if (forwarded) bubble.append(quoteCard('Forwarded from ' + (forwarded[3] || forwarded[4]), forwarded[1] || forwarded[2]));
    else if (typeof formattedText === 'function') bubble.replaceChildren(formattedText(message.body));
    else bubble.textContent = message.body;
    if (/^FM1:/.test(message.body || '')) {
      // FluidMessages encryption: only phones with the shared key can read it.
      bubble.textContent = '🔒 Encrypted with FluidMessages. Read it on your phone.';
      bubble.classList.add('sealed');
    }
    // One card per message: a money or call card already shows its link, so it gets no extra link preview.
    const carded = (typeof moneyCard === 'function' && moneyCard(message, bubble))
      || (typeof meetingCard === 'function' && meetingCard(message, bubble));
    if (!carded && typeof richCard === 'function') richCard(message, bubble, history);
    // Only messages sent with an effect; each plays once, and screen effects take turns (cards.js).
    if (typeof messageEffect === 'function' && /\(sent with /.test(message.body || '') && !/^FM1:/.test(message.body)) {
      const recent = position >= history.length - 3;
      setTimeout(() => messageEffect(message, bubble, recent));
    }
    if (message.attachment) {
      if (message.body === message.attachment.name || /^(Photo|sticker\.\w+)$/.test(message.body || '')) bubble.textContent = '';
      // Pictures show inline and open full screen; other files download.
      if (!(typeof attachmentPicture === 'function' && (attachmentPicture(message, bubble) || attachmentVideo(message, bubble)
        || attachmentContact(message, bubble) || (typeof attachmentAudio === 'function' && attachmentAudio(message, bubble))))) {
        const file = element('button', '📎 ' + message.attachment.name, 'file-card');
        file.type = 'button';
        file.addEventListener('click', () => download(message).catch(showError));
        bubble.prepend(file);
      }
    }
    // The bubble, with a small ⋯ beside it (on hover or focus) for reactions, reply, and more.
    const wrap = element('div', '', 'bubble-wrap');
    wrap.append(bubble);
    const line = element('div', '', 'bubble-line');
    line.append(wrap, messageActions(message, row));
    row.append(line);
    if (message.reactions.size) {
      const chips = element('span', [...message.reactions.values()].join(' '), 'reaction');
      chips.setAttribute('aria-label', 'Reactions: ' + [...message.reactions.values()].join(' '));
      row.append(chips);
    }
    const failed = message.type === 5 || (message.status || '').startsWith('Couldn’t send');
    const status = failed ? ' · Not delivered' : [4, 6].includes(message.type) ? ' · Sending…' : message.status ? ' · ' + message.status : '';
    if (failed) row.classList.add('failed');
    const meta = element('div', '', 'message-meta');
    if (message.earlier?.length) meta.append(element('small', 'Edited', 'message-time'));
    if (appearance.exactTimes || status) meta.append(element('small', clock(timeOf(message)) + status, 'message-time'));
    if (message.expires > Date.now()) meta.append(disappearTimer(message.expires));
    if (meta.children.length) row.append(meta);
    conversation.append(row);
    // New reactions since this chat was last drawn get a short animation: the newest one, not one per reaction.
    for (const [sender, emoji] of message.reactions) {
      const id = reactionId(message, sender, emoji);
      if (!reactionsSeen.has(id)) {
        reactionsSeen.add(id);
        if (threadsShown.has(active)) newestReaction = [row, emoji];
      }
    }
  }
  if (newestReaction) { const [row, emoji] = newestReaction; setTimeout(() => playReaction(row, emoji), 30); }
  threadsShown.add(active);
  if (!history.length && get('schedule').hidden) conversation.append(emptyThreadPane());
  // Touch screens have no ⋯ beside each bubble, so the first chat says once how to reach a message's menu.
  if (history.length && matchMedia('(hover: none)').matches && !localStorage.getItem('pressHint')) {
    conversation.append(element('p', 'Touch and hold a message to react, reply, or copy it.', 'press-hint'));
  }
  conversation.scrollTop = preserve && !atEnd ? scrollTop : conversation.scrollHeight;
  conversation.dataset.atEnd = String(!preserve || atEnd);
}

/** "Disappears in 3 h": what the timer by a disappearing message says. Rounds up below an hour, so it never reads 0. */
function timeLeft(millis) {
  if (millis <= 0) return 'Disappearing now';
  const seconds = Math.ceil(millis / 1000), minutes = Math.ceil(seconds / 60);
  if (seconds < 60) return 'Disappears in ' + seconds + ' s';
  if (minutes < 60) return 'Disappears in ' + minutes + ' min';
  const hours = Math.max(1, Math.round(millis / 3600000));
  return hours < 24 ? 'Disappears in ' + hours + ' h' : 'Disappears in ' + Math.round(millis / 86400000) + ' d';
}

/** A small timer by a disappearing message's time. Hover shows the time left; a tap or click keeps it open. */
function disappearTimer(expires) {
  const timer = element('button', '', 'disappear-timer');
  timer.type = 'button';
  timer.append(element('span', '⏱\uFE0E', 'timer-glyph'), element('span', '', 'timer-left'));
  const update = () => {
    const left = timeLeft(expires - Date.now());
    timer.title = left;
    timer.setAttribute('aria-label', left);
    timer.lastChild.textContent = timer.classList.contains('open') ? left : '';
  };
  timer.addEventListener('click', () => { timer.classList.toggle('open'); update(); });
  timer.addEventListener('mouseenter', update);
  timer.refresh = update;
  update();
  return timer;
}

// Open timers count down while the chat is open.
setInterval(() => { for (const timer of document.querySelectorAll('#conversation .disappear-timer.open')) timer.refresh?.(); }, 1000);

// Pictures and videos load after the chat is drawn; if you were at the newest message, stay there as they grow.
get('conversation').addEventListener('scroll', () => {
  const box = get('conversation');
  box.dataset.atEnd = String(box.scrollHeight - box.scrollTop - box.clientHeight < 48);
}, {passive: true});
function keepAtEnd() {
  const box = get('conversation');
  if (box.dataset.atEnd === 'true') box.scrollTop = box.scrollHeight;
}
get('conversation').addEventListener('load', keepAtEnd, true);
get('conversation').addEventListener('loadedmetadata', keepAtEnd, true);

/** What the message pane shows before you pick a chat: a friendly start, not an empty box. */
function emptyThreadPane() {
  const box = element('div', '', 'empty-state');
  box.append(Object.assign(element('img', ''), {src: '/icons/icon-192.png', alt: '', width: 72, height: 72, className: 'empty-icon'}));
  if (!threads.size && !chatAccounts.length) {
    box.append(element('h2', 'Your chats will show up here'),
      element('p', 'Add a Matrix, XMPP, Email, or IRC account to start chatting.' + (phonePaired() ? '' : ' Texts appear once you connect your phone.'), 'muted'));
    box.append(emptyActions([['Add an account', () => openSettings('settings-accounts').catch(showError), true],
      ...(phonePaired() ? [] : [['Connect your phone', () => (typeof showSyncGuide === 'function' ? showSyncGuide() : openSettings('settings-devices').catch(showError))]]),
      ...sampleAction()]));
  } else {
    box.append(element('h2', 'Pick a chat'), element('p', 'Or start a new one with anyone on your accounts.', 'muted'));
    box.append(emptyActions([['New chat', () => openNewChat(), true], ...(threads.size ? [] : sampleAction())]));
  }
  return box;
}

/** The sample inbox (chats.js), offered wherever the inbox is empty. */
function sampleAction() {
  return typeof startSample === 'function' ? [['Try the sample inbox', () => startSample()]] : [];
}

function emptyActions(list) {
  const row = element('div', '', 'button-row center');
  for (const [label, run, primary] of list) {
    const button = element('button', label, primary ? 'primary' : '');
    button.type = 'button';
    button.addEventListener('click', run);
    row.append(button);
  }
  return row;
}

function select(key) {
  if (typeof closeDetailsPage === 'function') closeDetailsPage(false);
  if (!get('schedule').hidden) drafts.set(active, get('body').value);
  active = key;
  const address = threadAddress(key);
  const protocol = key ? threadProtocol(key) : 'sms';
  get('workspace').dataset.thread = 'true';
  get('recipient').textContent = key ? threadTitle(key) : 'New text message';
  setAvatar(get('avatar'), key ? threadTitle(key) : '');
  if (typeof paintAvatar === 'function') paintAvatar(get('avatar'), key ? threadTitle(key) : '');
  // Groups say how many people are in them, like the phone's header (chats.js).
  get('transport').textContent = key && typeof headerSubtitle === 'function' ? headerSubtitle(key)
    : automatedThread(key) ? 'SMS · Automated sender' : labels[protocol] || 'SMS';
  get('address').value = address;
  get('address-field').hidden = !!address;
  // A protocol turned off on the phone stays readable here too.
  const off = !enabled.has(family(protocol)) && !enabled.has(protocol);
  get('readonly').hidden = !off;
  get('readonly').textContent = off ? (labels[protocol] || 'This protocol') + ' is turned off on your phone. This conversation is read-only.' : '';
  get('schedule').hidden = off || !!address && !senderAddress(address) && !internet(protocol);
  get('send').setAttribute('aria-label', internet(protocol) ? 'Send ' + labels[protocol] + ' message' : 'Send text message');
  get('body').placeholder = internet(protocol) ? labels[protocol] + ' message' : phonePaired() ? 'Text message' : 'Text message · sends when your phone connects';
  get('later').hidden = false;  // Internet chats schedule on FluidMessages Web; texts wait on the phone.
  // Click-to-call: your phone shows a "Tap to call" notification with the number ready to dial.
  get('call').hidden = !key || !phonePaired() || internet(protocol) || !senderAddress(address) || automatedThread(key);
  get('body').value = drafts.get(key) || '';
  get('schedule-status').textContent = '';
  setReply('');
  if (typeof showComposerEffect === 'function') showComposerEffect();
  markRead(key);
  showMessages();
  showThreads();
  if (typeof updateCallButtons === 'function') updateCallButtons();
}

// Opening an internet conversation reads it everywhere. Texts are marked read when you open them on the phone.
function markRead(key) {
  // The sample inbox's fictional unread chats are read on this page only.
  if (typeof sampleOn !== 'undefined' && sampleOn) {
    for (const row of [...(threads.get(key) || []), ...inbox.filter((item) => threadKey(item) === key)]) if (row.type === 1) row.read = 1;
    return;
  }
  const unread = (threads.get(key) || []).filter((row) => row.type === 1 && row.read === 0 && internet(row.protocol));
  if (!unread.length) return;
  for (const row of unread) row.read = 1;
  for (const row of inbox) if (unread.some((item) => item.id === row.id)) row.read = 1;
  api('protocols/read', {ids: unread.map((row) => row.id)}).catch(() => {});
}

function nearbyWord(a, b) {
  if (a.length > b.length) return nearbyWord(b, a);
  if (b.length - a.length > 1) return false;
  let offset = 0;
  while (offset < a.length && a[offset] === b[offset]) offset++;
  if (a.length !== b.length) return a.slice(offset) === b.slice(offset + 1);
  return offset === a.length || a.slice(offset + 1) === b.slice(offset + 1)
    || (a[offset] === b[offset + 1] && a[offset + 1] === b[offset] && a.slice(offset + 2) === b.slice(offset + 2));
}

function messageMatcher(query) {
  const normalize = (value) => value.normalize('NFD').replace(/\p{M}/gu, '').toLowerCase()
    .replace(/[^\p{L}\p{N}]+/gu, ' ').trim();
  const terms = normalize(query).split(' ').filter(Boolean);
  const digits = /^[+0-9(). -]+$/.test(query) ? query.replace(/\D/g, '') : '';
  return (address, body) => {
    if (!terms.length || (digits && address.replace(/\D/g, '').includes(digits))) return true;
    const text = normalize(address + ' ' + body);
    let words;
    return terms.every((term) => text.includes(term) || (term.length >= 4 && term.length <= 24 && !/\d/.test(term)
      && (words ??= text.split(' ')).some((word) => nearbyWord(term, word) ||
        (word.length > term.length && nearbyWord(term, word.slice(0, term.length))))));
  };
}

function showThreads() {
  const list = get('threads');
  list.replaceChildren();
  const filter = get('search').value.trim();
  const matches = messageMatcher(filter);
  const folder = get('folder').value;
  get('inbox-title').textContent = folder === 'Inbox' ? 'Messages' : folder;
  // The private folder's passcode prompt, or its open bar (privacy.js).
  if (typeof privateFolderGate === 'function' && privateFolderGate(list, folder)) return;
  const selected = typeof chatFolders === 'function' ? chatFolders() : appearance.folders || {};
  // Unread: the chats the Chats tab counts (people.js), plus the one open now, so it doesn't vanish as it's read.
  const unread = folder === 'Unread' && typeof unreadChats === 'function' ? unreadChats() : null;
  const rows = [...threads].filter(([address, messages]) => messages.length).filter(([address, messages]) => {
    const blocked = selected.blocked?.includes(threadAddress(address));
    const archived = selected.archived?.includes(threadAddress(address));
    // Private chats show only in the Private folder, and only once this session has opened it.
    const hidden = typeof privateFolderHas === 'function' && privateFolderHas(threadAddress(address));
    const inFolder = folder === 'Private folder' ? hidden
      : unread ? !hidden && (unread.has(address) || address === active && !blocked && !archived)
      : !hidden && (folder === 'Blocked' ? blocked : !blocked && (folder === 'Archived' ? archived : !archived));
    // A chat with a code is found by its name only, never by what it says.
    const sealed = typeof chatLocked === 'function' && chatLocked(address);
    // A server picked in the strip at the top shows only its channels and direct messages (servers.js).
    if (typeof serverShows === 'function' && !serverShows(address)) return false;
    return inFolder && (matches(threadTitle(address), '') || !sealed && messages.some((message) => matches(threadTitle(address), message.body)));
  });
  const pinned = new Set(selected.pinned || []);
  rows.sort(([a], [b]) => Number(pinned.has(threadAddress(b))) - Number(pinned.has(threadAddress(a))));
  if (typeof serverOrder === 'function') rows.sort(serverOrder);
  if (typeof serverStrip === 'function') serverStrip(list);
  let previousKey = null;
  // Same rule as the phone: Automatic shows tags once more than one kind of conversation is present.
  const mode = appearance.tagMode || (appearance.protocolTags ? 'always' : 'auto');
  const families = new Set([...threads.values()].flatMap((messages) => messages.map((row) => family(row.protocol || 'sms'))));
  const showTags = mode === 'always' || (mode === 'auto' && families.size > 1);
  for (const [key, messages] of rows) {
    const address = key;
    const message = messages.at(-1);
    const button = element('button', '', 'thread');
    button.type = 'button';
    button.setAttribute('aria-current', String(address === active));
    button.dataset.unread = String(message.type === 1 && message.read === 0);
    const copy = element('span', '', 'thread-copy');
    copy.append(element('strong', threadTitle(key)));
    if (showTags) {
      const tags = element('span', '', 'tags');
      const formats = appearance.combineProtocols ? [...new Set(messages.map((row) => row.protocol || 'sms'))] : [message.protocol || 'sms'];
      for (const format of formats) {
        // Automated senders' tag takes the automated color, as on the phone (ProtocolTags).
        const auto = automatedThread(key) && ['sms', 'mms'].includes(format);
        tags.append(element('span', auto ? 'Automated' : labels[format] || 'SMS', 'tag p-' + format + (auto ? ' automated' : '')));
      }
      copy.append(tags);
    }
    const meetingLine = (typeof meetingPreview === 'function' ? meetingPreview(message.body || '') : null)
      || (typeof cardPreview === 'function' ? cardPreview(message.body || '') : null);
    copy.append(element('small', /^FM1:/.test(message.body || '') ? '🔒 Encrypted with FluidMessages' : meetingLine || message.body));
    const time = timeOf(message);
    // Photos where one is known, like the sample contacts' (people.js), otherwise tinted initials.
    const face = setAvatar(element('span', '', 'avatar'), threadTitle(key));
    if (typeof paintAvatar === 'function') paintAvatar(face, threadTitle(key));
    button.append(element('span', '', 'indicator'), face, copy,
      element('time', day(time) === 'Today' ? clock(time) : day(time) + (appearance.exactTimes ? ' ' + clock(time) : '')));
    button.addEventListener('click', () => select(address));
    // Pinned, muted, and locked marks, and the pin/archive/mute menu (chats.js).
    if (typeof decorateThread === 'function') decorateThread(key, button, copy);
    if (typeof serverHeading === 'function') serverHeading(list, key, previousKey);
    previousKey = key;
    list.append(button);
  }
  if (!rows.length) {
    if (filter) list.append(element('p', 'No chats match “' + excerpt(filter, 40) + '”', 'placeholder'));
    else if (typeof activeServer === 'string' && activeServer) list.append(element('p', 'No chats on this server yet. Join a channel or room above.', 'placeholder'));
    else if (folder !== 'Inbox') list.append(element('p', folder === 'Private folder' ? 'Nothing in the private folder.' : 'Nothing ' + folder.toLowerCase() + '.', 'placeholder'));
    else {
      const empty = element('div', '', 'empty-list');
      empty.append(element('strong', 'No chats yet'), element('p', chatAccounts.length
        ? 'Start one with the new chat button beside Search.' : 'Add a chat account to get started.', 'muted'));
      empty.append(emptyActions([...(chatAccounts.length ? [] : [['Add an account', () => openSettings('settings-accounts').catch(showError), true]]), ...sampleAction()]));
      list.append(empty);
    }
  }
  // Unread and missed counts on the tabs, the app icon, and the title (people.js).
  if (typeof updateTabBadges === 'function') updateTabBadges();
}

function showJobs(jobs) {
  const queue = get('jobs');
  // The Scheduled button shows only while something is waiting, so it isn't one more thing on screen.
  const waiting = jobs.filter((job) => ['scheduled', 'claimed', 'queued'].includes(job.state) && job.protocol !== 'call').length;
  get('open-queue').hidden = !waiting && !get('queue').open;
  get('open-queue').textContent = 'Scheduled · ' + waiting;
  const signature = JSON.stringify(jobs);
  if (signature === queue.dataset.snapshot) return;
  queue.dataset.snapshot = signature;
  queue.replaceChildren();
  if (!jobs.length) queue.append(element('p', 'No scheduled messages', 'placeholder'));
  for (const job of jobs) {
    const row = element('div', '', 'job');
    row.append(element('strong', job.address), element('p', job.protocol === 'call' ? 'Call request' : job.body),
      element('small', new Date(job.due).toLocaleString() + ' · ' + (jobStates[job.state] || job.state)));
    if (job.state === 'scheduled') {
      const cancel = element('button', 'Cancel');
      cancel.type = 'button';
      cancel.addEventListener('click', async () => {
        cancel.disabled = true;
        try { await api('cancel', {id: job.id}); await refresh(); }
        catch (error) { get('queue-status').textContent = error.message; cancel.disabled = false; }
      });
      row.append(cancel);
    }
    queue.append(row);
  }
}

async function refresh() {
  // The sample chats are drawn from this page only (chats.js).
  if (typeof sampleRefresh === 'function' && sampleRefresh()) return;
  const currentSession = session;
  const request = ++refreshNumber;
  const [snapshot, jobs] = await Promise.all([api('inbox'), api('jobs')]);
  if (currentSession !== session || request !== refreshNumber) return;
  // Redraws when anything changed: a phone sync, new or updated internet messages, or the settings.
  const look = JSON.stringify([snapshot.appearance, snapshot.follow_phone, snapshot.web_appearance, snapshot.routes, snapshot.messages,
    snapshot.prefs, snapshot.accounts, snapshot.web_chats, snapshot.web_favorites]);
  welcomed = snapshot.welcomed !== false;
  if (!refreshed || updated !== snapshot.updated || look !== lastLook) {
    lastLook = look;
    prefs = {...prefs, ...(snapshot.prefs || {})};
    // These two used to be saved per browser; an earlier "off" moves to the server's Fun and effects settings.
    for (const [legacy, key] of [['linkPreviews', 'linkPreviews'], ['autoPictures', 'gifs']]) {
      if (localStorage.getItem(legacy) !== 'off') continue;
      localStorage.removeItem(legacy);
      if (prefs[key]) { prefs[key] = false; api('settings', {prefs: {[key]: false}}).catch(() => {}); }
    }
    chatAccounts = snapshot.accounts || [];
    if (Array.isArray(snapshot.enabled)) gatewayEnabled = snapshot.enabled;
    phoneLook = snapshot.appearance || {};
    followPhone = snapshot.follow_phone !== false;
    webLook = snapshot.web_appearance || webLook;
    applyAppearance(resolvedAppearance());
    if (typeof routes !== 'undefined') routes = snapshot.routes || [];
    if (Array.isArray(snapshot.appearance?.enabledProtocols)) enabled = new Set(snapshot.appearance.enabledProtocols);
    updated = snapshot.updated;
    inbox = snapshot.messages;
    if (typeof noticeSnapshot === 'function') noticeSnapshot(snapshot);
    rebuildThreads();
    showThreads();
    showMessages(true);
    // Favorites, and the Calls tab when it's open (people.js).
    if (typeof noticePeople === 'function') noticePeople(snapshot);
    refreshed = true;
  }
  // The phone is an add-on: its sync time shows once it's connected, and nothing is said about it before.
  if (typeof noticeMerge === 'function') noticeMerge(snapshot);
  setSyncStatus(phonePaired() ? 'Phone synced ' + new Date(updated).toLocaleTimeString([], {hour: 'numeric', minute: '2-digit'}) : '');
  showJobs(jobs);
}

/** The phone's sync time beside the app's name on wide screens; on phones, where there's no top bar, only a problem shows, under the title. */
function setSyncStatus(text, problem = false) {
  get('status').textContent = text;
  get('inbox-status').textContent = problem ? text : '';
  get('inbox-status').hidden = !problem;
}

let statusTimer = 0;
function showError(error) { say(error.message, true); }
/** A short line under the composer. Confirmations fade after a few seconds; problems stay until the next action. */
function say(text, sticky = false) {
  const line = get('schedule-status');
  line.textContent = text;
  clearTimeout(statusTimer);
  if (!sticky && text) statusTimer = setTimeout(() => { if (line.textContent === text) line.textContent = ''; }, 4000);
}

async function send(body, text, relation, due = Date.now(), attachment) {
  if (typeof sampleSend === 'function' && sampleSend(body, attachment)) return;
  const protocol = threadProtocol(active);
  if (!internet(protocol)) return queueSms(body, threadAddress(active), due);
  const currentSession = session;
  const request = crypto.randomUUID ? crypto.randomUUID() : String(Date.now()) + Math.random().toString(16).slice(2);
  await api('protocols/send', {address: threadAddress(active), protocol, body, text: text || body, request,
    relation: relation && relation.target ? relation : undefined, due: due > Date.now() + 30000 ? due : undefined,
    attachment: attachment || undefined});
  if (currentSession !== session) return;
  say(due > Date.now() + 30000 ? 'Scheduled for ' + new Date(due).toLocaleString() + '.' : 'Sending…');
  refresh().catch(() => setSyncStatus('Sync unavailable', true));
}

async function download(message) {
  const response = await fetch('/api/protocols/file?id=' + encodeURIComponent(message.id), {headers: REQUEST_HEADERS});
  if (!response.ok) throw new Error('The attachment is no longer on FluidMessages Web.');
  saveBlob(await response.blob(), message.attachment.name);
}

/** Saves a file the browser already has, under a safe name. */
function saveBlob(blob, name) {
  const url = URL.createObjectURL(blob);
  const link = element('a', '');
  link.href = url;
  link.download = (name || 'download').replace(/[\\/:*?"<>|\u0000-\u001f]+/g, ' ').trim().slice(0, 120) || 'download';
  document.body.append(link);
  link.click();
  link.remove();
  setTimeout(() => URL.revokeObjectURL(url), 30000);
}

async function queueSms(body, address, due) {
  if (typeof sampleSend === 'function' && sampleSend(body)) return;
  const currentSession = session;
  await api('schedule', {address, body, due});
  if (currentSession !== session) return;
  say(due > Date.now() + 30000 ? 'Scheduled. Your phone sends it after ' + new Date(due).toLocaleString() + '.'
    : phonePaired() ? 'Queued for your phone.' : 'Waiting for your phone. Connect it in Settings to send texts.');
  refresh().catch(() => setSyncStatus('Sync unavailable', true));
}

// Signing in: a password you create on first visit, then a session cookie. No access token to type.

let signInMode = 'login';

function showSignIn(mode, message = '') {
  signInMode = mode;
  get('pair').hidden = false;
  get('workspace').hidden = true;
  for (const [id, name] of [['setup-form', 'setup'], ['login-form', 'login'], ['token-form', 'token']]) get(id).hidden = mode !== name;
  get('sign-in-title').textContent = mode === 'setup' ? 'Set up FluidMessages' : 'Welcome back';
  get('sign-in-intro').textContent = mode === 'setup'
    ? 'You’re opening FluidMessages from another device. Enter the setup code from the server and choose a password for your other devices.'
    : mode === 'token' ? 'Sign in with an access token: the server’s FLUID_TOKEN or a phone pairing token.'
      : 'Enter your FluidMessages password to open your chats.';
  get('pair-error').textContent = message;
  get('reset-help').hidden = mode === 'setup';
  const first = {setup: 'setup-code', login: 'login-password', token: 'token'}[mode];
  requestAnimationFrame(() => get(first)?.focus());
}

async function authCall(path, body) {
  const response = await fetch('/api/auth/' + path, {method: 'POST', credentials: 'same-origin',
    headers: {...REQUEST_HEADERS, 'Content-Type': 'application/json'}, body: JSON.stringify(body || {})});
  let result = {};
  try { result = await response.json(); } catch (error) { /* not JSON */ }
  if (!response.ok) throw new Error(result.error || 'Couldn’t sign in. Try again.');
  return result;
}

async function enterWorkspace() {
  const currentSession = ++session;
  signedIn = true;
  await refresh();
  if (currentSession !== session) return;
  for (const id of ['setup-form', 'login-form', 'token-form']) get(id).reset();
  get('pair-error').textContent = '';
  get('pair').hidden = true;
  // First run: a short welcome (look, accounts, and the optional phone) before the inbox.
  if (!welcomed && typeof showWelcome === 'function') { showWelcome(); return; }
  get('workspace').hidden = false;
  // A Settings address (#settings, #settings/privacy) opens that page, so links and Back land where they point.
  if (typeof settingsRoute === 'function') settingsRoute();
}

function signInForm(id, run) {
  get(id).addEventListener('submit', async (event) => {
    event.preventDefault();
    const button = get(id).querySelector('button.primary');
    button.disabled = true;
    get('pair-error').textContent = '';
    try {
      await run();
      await enterWorkspace();
    } catch (error) {
      signedIn = false;
      get('pair-error').textContent = error.message;
    } finally { button.disabled = false; }
  });
}

signInForm('setup-form', async () => {
  const password = get('setup-password').value;
  if (password !== get('setup-confirm').value) throw new Error('The two passwords don’t match.');
  await authCall('setup', {code: get('setup-code').value.trim(), password, remember: get('setup-remember').checked});
});
signInForm('login-form', () => authCall('login', {password: get('login-password').value, remember: get('login-remember').checked}));
signInForm('token-form', () => authCall('token', {token: get('token').value.trim(), remember: get('remember').checked}));
get('use-token').addEventListener('click', () => showSignIn('token'));
get('setup-use-token').addEventListener('click', () => showSignIn('token'));
get('use-password').addEventListener('click', () => start().catch(() => showSignIn('login')));
for (const [button, input] of [['show-setup-password', 'setup-password'], ['show-login-password', 'login-password']]) {
  get(button).addEventListener('click', () => {
    const field = get(input);
    const shown = field.type === 'text';
    field.type = shown ? 'password' : 'text';
    get(button).textContent = shown ? 'Show' : 'Hide';
    get(button).setAttribute('aria-pressed', String(!shown));
  });
}

/** Clears this page after signing out or when the session ends. */
function signOutHere(message = '') {
  ++session;
  signedIn = false;
  inbox = [];
  threads.clear();
  drafts.clear();
  updated = null;
  refreshed = false;
  lastLook = '';
  active = '';
  phoneLook = {};
  setReply('');
  applyAppearance(themeAppearance({theme: 'fluid', mode: 'system'}));
  for (const open of document.querySelectorAll('dialog[open]')) open.close();
  if (typeof closeDetailsPage === 'function') closeDetailsPage(false);
  get('jobs').replaceChildren();
  delete get('jobs').dataset.snapshot;
  get('threads').replaceChildren();
  get('conversation').replaceChildren();
  get('schedule').reset();
  get('schedule').hidden = true;
  get('send').disabled = false;
  get('search').value = '';
  get('folder').value = 'Inbox';
  get('recipient').textContent = 'Messages';
  get('transport').textContent = '';
  setAvatar(get('avatar'), '');
  delete get('workspace').dataset.thread;
  setSyncStatus('');
  get('open-queue').hidden = true;
  get('welcome').hidden = true;
  // On this computer the app simply opens again; elsewhere it asks for the password.
  start(message).catch(() => showSignIn('login', message));
}

/** Signing out, from Settings. On this computer there's nothing to sign out of, so it isn't offered there. */
async function signOut() {
  await authCall('logout').catch(() => {});
  signOutHere();
}
get('folder').addEventListener('change', showThreads);
get('search').addEventListener('input', showThreads);
get('new').addEventListener('click', () => openNewChat());
get('back').addEventListener('click', () => { delete get('workspace').dataset.thread; get('threads').querySelector('[aria-current="true"]')?.focus(); });
get('open-queue').addEventListener('click', () => {
  get('queue-status').textContent = '';
  get('queue').showModal();
});
get('close-queue').addEventListener('click', () => get('queue').close());
get('cancel-reply').addEventListener('click', () => setReply(''));
get('call').addEventListener('click', async () => {
  get('call').disabled = true;
  try {
    await api('call', {address: threadAddress(active)});
    say('Sent to your phone. Tap the notification there to call.');
  } catch (error) { showError(error); }
  finally { setTimeout(() => { get('call').disabled = false; }, 3000); }
});
// Enter sends and Shift+Enter starts a new line, like the phone's keyboard Send.
get('body').addEventListener('keydown', (event) => {
  if (event.key === 'Enter' && !event.shiftKey && !event.isComposing && localStorage.getItem('enterSends') !== 'off') {
    event.preventDefault();
    get('schedule').requestSubmit();
  }
});
// The message box grows with what you type, up to a few lines, then scrolls.
get('body').addEventListener('input', () => {
  const box = get('body');
  box.style.height = 'auto';
  box.style.height = Math.min(box.scrollHeight + 2, 180) + 'px';
});
get('schedule').addEventListener('submit', async (event) => {
  event.preventDefault();
  const currentSession = session;
  const address = get('address').value.trim();
  const draft = get('body').value;
  const current = active;
  // A chosen effect rides along with the next message: "words\n(sent with Confetti 🎉)".
  const effect = typeof composerEffect === 'function' ? composerEffect() : null;
  const withEffect = (text) => effect ? effectText(text, effect) : text;
  const body = withEffect(reply ? 'Reply to “' + excerpt(reply, 240) + '”\n\n' + draft : draft);
  // A photo waiting above the message box goes with the words as its caption (media.js).
  if (typeof sendStaged === 'function' && await sendStaged()) return;
  if (!draft.trim()) { showError(new Error(effect ? 'Type a message to send with ' + effect + '.' : 'Type a message first.')); get('body').focus(); return; }
  get('send').disabled = true;
  say('');
  try {
    const due = get('due').value;
    const when = due && get('later').open ? new Date(due).getTime() : Date.now();
    if (active && internet(threadProtocol(active))) await send(body, withEffect(draft), reply ? {kind: 'reply', target: replyId} : null, when);
    else await queueSms(body, address, when);
    if (typeof sendFeedback === 'function') sendFeedback();
    if (currentSession === session && current === active) {
      get('body').value = '';
      drafts.delete(current);
      setReply('');
      get('later').open = false;
      if (effect) setComposerEffect(null);
    }
  } catch (error) {
    if (currentSession === session) showError(error);
  } finally {
    if (currentSession === session) get('send').disabled = false;
  }
});
setInterval(() => {
  if (signedIn && !get('workspace').hidden) refresh().catch(() => {
    if (signedIn) setSyncStatus('Sync unavailable', true);
  });
}, 5000);

/**
 * Opens the app. On the computer FluidMessages runs on it simply opens, like any app. Elsewhere: signed in
 * already, a token handed over by the desktop app, or the password page.
 */
async function start(message = '') {
  // The desktop app opens with its token after "#token=", which never leaves the browser. It's swapped for a
  // session cookie at once and forgotten. Browsers that kept the old token sign in the same way, once.
  let handed = '';
  if (location.hash.startsWith('#token=')) {
    handed = decodeURIComponent(location.hash.slice(7));
    history.replaceState(null, '', location.pathname);
  }
  // After Settings → Erase everything on this server, the page reloads at #erased.
  if (location.hash === '#erased') {
    history.replaceState(null, '', location.pathname);
    message ||= 'Everything on this server was erased. Set FluidMessages Web up again to keep using it.';
  }
  const kept = localStorage.getItem('fluidToken') || '';
  localStorage.removeItem('fluidToken');
  let state = await (await fetch('/api/auth', {headers: REQUEST_HEADERS})).json();
  localHere = !!state.local;
  // This computer first, so its access is remembered even when the desktop app also hands over a token.
  // At most once every few seconds, so a server that keeps ending sessions can't make the page loop.
  if (!state.signed_in && state.local && Date.now() - lastLocalSignIn > 5000) {
    lastLocalSignIn = Date.now();
    try { await authCall('local'); state = {...state, signed_in: true}; } catch (error) { localHere = false; }
  }
  if (!state.signed_in) {
    for (const value of [handed, kept]) {
      if (!/^[0-9A-Za-z_-]{32,256}$/.test(value)) continue;
      try { await authCall('token', {token: value, remember: true}); state = {...state, signed_in: true}; break; } catch (error) { /* try the next one */ }
    }
  }
  if (state.signed_in) {
    try { await enterWorkspace(); return; } catch (error) { signedIn = false; }
  }
  showSignIn(state.owner ? 'login' : 'setup', message);
}

// The on-screen keyboard: browsers that don't shrink the page for it (iOS Safari) still keep the message box just above
// it, because the app's height follows the part of the page you can see.
if (window.visualViewport) {
  const fit = () => {
    const view = window.visualViewport;
    document.documentElement.style.setProperty('--view-height', Math.round(view.height) + 'px');
    document.documentElement.style.setProperty('--view-top', Math.round(view.offsetTop) + 'px');
  };
  visualViewport.addEventListener('resize', fit);
  visualViewport.addEventListener('scroll', fit);
  fit();
}

applyAppearance(themeAppearance({theme: 'fluid', mode: 'system'}));
document.addEventListener("DOMContentLoaded", () => start().catch(() => showSignIn('login', 'FluidMessages Web can’t be reached right now. Check your connection and reload.')));
