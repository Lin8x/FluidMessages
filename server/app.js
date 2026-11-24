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
  return {dark, colors, exactTimes: !!look.exactTimes, combineProtocols: !!look.combineProtocols,
    tagMode: look.tagMode || 'auto', encryptionMode: look.encryptionMode || 'auto',
    radius: look.radius ?? LAYOUT.radius, size: look.size ?? LAYOUT.size, padding: look.padding ?? LAYOUT.padding};
}

// Facts about the phone that hold whichever settings you use: which protocols it has on, MMS, and its folders.
const PHONE_FACTS = ['enabledProtocols', 'mmsReady', 'folders', 'protocolDefaults', 'protocolPrivacy'];

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
}

function automated(address) {
  return /^\d{5,6}$/.test(address) || (/^[\w .-]{2,20}$/.test(address) && /[a-z]/i.test(address));
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

function excerpt(value, limit) {
  const chars = Array.from(value);
  return chars.length > limit ? chars.slice(0, limit).join('') + '…' : value;
}

const nouns = {'a like': '👍', 'a heart': '❤️', 'a dislike': '👎', 'a laugh': '😆', 'an exclamation': '‼️',
  'a question mark': '❓'};
const isEmoji = (value) => value && value.length <= 64 && /[\p{Emoji_Presentation}\p{Extended_Pictographic}\u20E3]/u.test(value);

// Same wording as the phone: added, removed (as iPhones word it), or changed reactions.
function parseReaction(body) {
  const own = body.endsWith(' (my message)');
  const wire = own ? body.slice(0, -13) : body;
  const unquote = (text) => (/^“([\s\S]+)”$/.exec(text) || /^"([\s\S]+)"$/.exec(text) || [])[1];
  const known = verbs.find(([verb]) => wire.startsWith(verb + ' '));
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

function fold(history) {
  const shown = [];
  for (const message of history) {
    // "✏️ Edited “old” to: new" replaces the sender's own earlier message.
    const edit = [4, 5, 6].includes(message.type) || message.attachment ? null : /^✏️ Edited “([\s\S]+?)” to: ([\s\S]+)$/.exec(message.body || '');
    if (edit) {
      const target = [...shown].reverse().find((row) => senderKey(row) === senderKey(message) && !row.attachment
        && excerpt((row.body || '').replace(/”/g, '"'), 300) === edit[1]);
      if (target) { target.earlier = (target.earlier || []).concat(target.body); target.body = edit[2]; continue; }
    }
    const parsed = [4, 5, 6].includes(message.type) ? null : parseReaction(message.body);
    if (parsed?.quote) {
      const quoted = parsed.quote;
      const prefix = quoted.endsWith('…') && quoted.length > 20 ? quoted.slice(0, -1) : null;
      const matches = [...shown].reverse().filter((row) => row.body === quoted || excerpt(row.body, 700) === quoted
        || (prefix && row.body.startsWith(prefix)));
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
// Short codes and sender IDs are automated only for carrier texts; internet IDs often contain letters.
function automatedThread(key) { return !!key && family(threadProtocol(key)) === 'sms' && automated(threadAddress(key)); }
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

function messageActions(message) {
  const menu = element('details', '', 'message-tools');
  menu.append(element('summary', 'Actions'));
  const action = (label, callback) => {
    const button = element('button', label);
    button.type = 'button';
    button.addEventListener('click', () => { menu.open = false; callback(); });
    menu.append(button);
  };
  const sendable = senderAddress(threadAddress(active)) || internet(threadProtocol(active));
  if (sendable) action('Reply', () => { setReply(message.body, message.id); get('body').focus(); });
  action('Forward', () => {
    const author = message.type === 1 ? excerpt(message.address.replace(/[“”\r\n]/g, ' '), 90) : 'Me';
    const prefix = 'Forwarded “';
    const suffix = '” by “' + author + '”';
    const max = 1600 - prefix.length - suffix.length;
    const body = message.body.length <= max ? message.body : message.body.slice(0, max - 1).replace(/[\uD800-\uDBFF]$/, '') + '…';
    select('');
    get('body').value = prefix + body + suffix;
    drafts.set('', get('body').value);
    get('address').focus();
  });
  if (sendable) {
    for (const [emoji, verb] of [['👍', 'Liked'], ['❤️', 'Loved'], ['🎉', 'Reacted 🎉 to']]) {
      action(emoji, () => {
        const own = message.type !== 1;
        const quote = '“' + excerpt(message.body, 700) + '”' + (own ? ' (my message)' : '');
        const current = message.reactions.get('me');
        // Your current reaction again takes it back; a different one replaces it. Matrix and XMPP do that natively;
        // the readable text goes everywhere else.
        const native = ['matrix', 'xmpp'].includes(message.protocol);
        const relation = (extra) => native ? {kind: 'reaction', target: message.id || '', emoji, own, ...extra} : undefined;
        if (current === emoji) {
          const noun = Object.keys(nouns).find((key) => nouns[key] === emoji) || emoji;
          send('Removed ' + noun + ' from ' + quote, '', relation({removed: true})).catch(showError);
        } else if (current) {
          send('Changed reaction from ' + current + ' to ' + emoji + ' on ' + quote, '', relation({previous: current}))
            .catch(showError);
        } else {
          send(verb + ' ' + quote, '', {kind: 'reaction', target: message.id || '', emoji, own}).catch(showError);
        }
      });
    }
  }
  if (sendable && message.type !== 1 && !message.attachment && ![4, 5, 6].includes(message.type) && typeof openEditMessage === 'function') {
    action('Edit', () => openEditMessage(message));
  }
  if (message.attachment) action('Download ' + message.attachment.name, () => download(message).catch(showError));
  return menu;
}

function showMessages(preserve = false) {
  const conversation = get('conversation');
  const scrollTop = conversation.scrollTop;
  const atEnd = conversation.scrollHeight - scrollTop - conversation.clientHeight < 48;
  conversation.replaceChildren();
  const history = threads.get(active) || [];
  let lastDay = '';
  let position = -1;
  for (const message of history) {
    position++;
    const messageDay = day(timeOf(message));
    if (messageDay !== lastDay) conversation.append(element('p', messageDay, 'day'));
    lastDay = messageDay;
    const outgoing = message.type !== 1;
    if (message.event) { conversation.append(element('p', '— ' + message.body, 'event')); continue; }
    const protocolClass = ' p-' + (message.protocol || 'sms');
    const row = element('div', '', (outgoing ? 'message out' + (automatedThread(active) ? ' automated' : '') : 'message') + protocolClass);
    if (!outgoing && message.author && (internet(message.protocol) || message.protocol === 'mms')) row.append(element('small', message.author, 'author'));
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
    if (typeof messageEffect === 'function' && !/^FM1:/.test(message.body || '')) setTimeout(() => messageEffect(message, bubble, position >= history.length - 3));
    if (message.attachment) {
      if (message.body === message.attachment.name || /^(Photo|sticker\.\w+)$/.test(message.body || '')) bubble.textContent = '';
      // Pictures show inline and open full screen; other files download.
      if (!(typeof attachmentPicture === 'function' && (attachmentPicture(message, bubble) || attachmentContact(message, bubble)))) {
        const file = element('button', '📎 ' + message.attachment.name, 'file-card');
        file.type = 'button';
        file.addEventListener('click', () => download(message).catch(showError));
        bubble.prepend(file);
      }
    }
    row.append(bubble);
    if (message.reactions.size) row.append(element('span', [...message.reactions.values()].join(' '), 'reaction'));
    const failed = message.type === 5 || (message.status || '').startsWith('Couldn’t send');
    const status = failed ? ' · Not delivered' : [4, 6].includes(message.type) ? ' · Sending…' : message.status ? ' · ' + message.status : '';
    if (failed) row.classList.add('failed');
    if (message.earlier?.length) row.append(element('small', 'Edited', 'message-time'));
    if (appearance.exactTimes || status) row.append(element('small', clock(timeOf(message)) + status, 'message-time'));
    row.append(messageActions(message));
    conversation.append(row);
  }
  if (!history.length && get('schedule').hidden) conversation.append(element('p', 'Choose a conversation or start a new SMS.', 'placeholder'));
  conversation.scrollTop = preserve && !atEnd ? scrollTop : conversation.scrollHeight;
}

function select(key) {
  if (!get('schedule').hidden) drafts.set(active, get('body').value);
  active = key;
  const address = threadAddress(key);
  const protocol = key ? threadProtocol(key) : 'sms';
  get('workspace').dataset.thread = 'true';
  get('recipient').textContent = key ? threadTitle(key) : 'New SMS';
  get('avatar').textContent = initials(key ? threadTitle(key) : '');
  get('transport').textContent = automatedThread(key) ? 'SMS · Automated sender' : labels[protocol] || 'SMS';
  get('address').value = address;
  get('address-field').hidden = !!address;
  // A protocol turned off on the phone stays readable here too.
  const off = !enabled.has(family(protocol)) && !enabled.has(protocol);
  get('readonly').hidden = !off;
  get('readonly').textContent = off ? (labels[protocol] || 'This protocol') + ' is turned off on your phone. This conversation is read-only.' : '';
  get('schedule').hidden = off || !!address && !senderAddress(address) && !internet(protocol);
  get('send').textContent = internet(protocol) ? 'Send ' + labels[protocol] : 'Send SMS';
  get('body').placeholder = internet(protocol) ? labels[protocol] + ' message' : 'Text message';
  get('later').hidden = false;  // Internet chats schedule on FluidMessages Web; texts wait on the phone.
  // Click-to-call: your phone shows a "Tap to call" notification with the number ready to dial.
  get('call').hidden = !key || internet(protocol) || !senderAddress(address) || automatedThread(key);
  get('body').value = drafts.get(key) || '';
  get('schedule-status').textContent = '';
  setReply('');
  markRead(key);
  showMessages();
  showThreads();
  if (typeof updateCallButtons === 'function') updateCallButtons();
}

// Opening an internet conversation reads it everywhere. Texts are marked read when you open them on the phone.
function markRead(key) {
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
  const selected = appearance.folders || {};
  const rows = [...threads].filter(([address, messages]) => messages.length).filter(([address, messages]) => {
    const blocked = selected.blocked?.includes(threadAddress(address));
    const archived = selected.archived?.includes(threadAddress(address));
    const inFolder = folder === 'Blocked' ? blocked : !blocked && (folder === 'Archived' ? archived : !archived);
    return inFolder && (matches(threadTitle(address), '') || messages.some((message) => matches(threadTitle(address), message.body)));
  });
  const pinned = new Set(selected.pinned || []);
  rows.sort(([a], [b]) => Number(pinned.has(threadAddress(b))) - Number(pinned.has(threadAddress(a))));
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
      for (const format of formats) tags.append(element('span', automatedThread(key) && family(format) === 'sms' ? 'Automated' : labels[format] || 'SMS', 'tag p-' + format));
      copy.append(tags);
    }
    const meetingLine = (typeof meetingPreview === 'function' ? meetingPreview(message.body || '') : null)
      || (typeof cardPreview === 'function' ? cardPreview(message.body || '') : null);
    copy.append(element('small', /^FM1:/.test(message.body || '') ? '🔒 Encrypted with FluidMessages' : meetingLine || message.body));
    const time = timeOf(message);
    button.append(element('span', '', 'indicator'), element('span', initials(threadTitle(key)), 'avatar'), copy,
      element('time', day(time) === 'Today' ? clock(time) : day(time) + (appearance.exactTimes ? ' ' + clock(time) : '')));
    button.addEventListener('click', () => select(address));
    list.append(button);
  }
  if (!rows.length) list.append(element('p', filter ? 'No matching conversations' : 'No conversations here yet', 'placeholder'));
}

function showJobs(jobs) {
  const queue = get('jobs');
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
  const currentSession = session;
  const request = ++refreshNumber;
  const [snapshot, jobs] = await Promise.all([api('inbox'), api('jobs')]);
  if (currentSession !== session || request !== refreshNumber) return;
  // Redraws when anything changed: a phone sync, new or updated internet messages, or the settings.
  const look = JSON.stringify([snapshot.appearance, snapshot.follow_phone, snapshot.web_appearance, snapshot.routes, snapshot.messages]);
  if (!refreshed || updated !== snapshot.updated || look !== lastLook) {
    lastLook = look;
    phoneLook = snapshot.appearance || {};
    followPhone = snapshot.follow_phone !== false;
    webLook = snapshot.web_appearance || webLook;
    applyAppearance(resolvedAppearance());
    if (typeof routes !== 'undefined') routes = snapshot.routes || [];
    if (Array.isArray(snapshot.appearance?.enabledProtocols)) enabled = new Set(snapshot.appearance.enabledProtocols);
    updated = snapshot.updated;
    inbox = snapshot.messages;
    rebuildThreads();
    showThreads();
    showMessages(true);
    refreshed = true;
  }
  get('status').textContent = updated ? 'Phone synced ' + new Date(updated).toLocaleTimeString()
    : 'No phone connected · internet chats work here';
  showJobs(jobs);
}

function showError(error) { get('schedule-status').textContent = error.message; }

async function send(body, text, relation, due = Date.now(), attachment) {
  const protocol = threadProtocol(active);
  if (!internet(protocol)) return queueSms(body, threadAddress(active), due);
  const currentSession = session;
  const request = crypto.randomUUID ? crypto.randomUUID() : String(Date.now()) + Math.random().toString(16).slice(2);
  await api('protocols/send', {address: threadAddress(active), protocol, body, text: text || body, request,
    relation: relation && relation.target ? relation : undefined, due: due > Date.now() + 30000 ? due : undefined,
    attachment: attachment || undefined});
  if (currentSession !== session) return;
  get('schedule-status').textContent = due > Date.now() + 30000 ? 'Scheduled on FluidMessages Web for ' + new Date(due).toLocaleString() + '.'
    : 'Queued on FluidMessages Web.';
  refresh().catch(() => { get('status').textContent = 'Sync unavailable'; });
}

async function download(message) {
  const response = await fetch('/api/protocols/file?id=' + encodeURIComponent(message.id), {headers: REQUEST_HEADERS});
  if (!response.ok) throw new Error('The attachment is no longer on FluidMessages Web.');
  const url = URL.createObjectURL(await response.blob());
  const link = element('a', '');
  link.href = url;
  link.download = message.attachment.name;
  link.click();
  setTimeout(() => URL.revokeObjectURL(url), 30000);
}

async function queueSms(body, address, due) {
  const currentSession = session;
  await api('schedule', {address, body, due});
  if (currentSession !== session) return;
  get('schedule-status').textContent = due > Date.now() + 30000 ? 'Scheduled. Your phone sends it after ' + new Date(due).toLocaleString() + '.'
    : 'Queued for your phone.';
  refresh().catch(() => { get('status').textContent = 'Sync unavailable'; });
}

// Signing in: a password you create on first visit, then a session cookie. No access token to type.

let signInMode = 'login';

function showSignIn(mode, message = '') {
  signInMode = mode;
  get('pair').hidden = false;
  get('workspace').hidden = true;
  for (const [id, name] of [['setup-form', 'setup'], ['login-form', 'login'], ['token-form', 'token']]) get(id).hidden = mode !== name;
  get('sign-in-title').textContent = mode === 'setup' ? 'Welcome to FluidMessages Web' : 'Messages on the web';
  get('sign-in-intro').textContent = mode === 'setup'
    ? 'Create a password for this server. Matrix, XMPP, Email, and IRC work here on their own; texts go through your Android phone once it’s connected.'
    : mode === 'token' ? 'Sign in with an access token: the server’s FLUID_TOKEN or a phone pairing token.'
      : 'Sign in to read and send your messages.';
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
  get('workspace').hidden = false;
  get('disconnect').hidden = false;
  get('open-queue').hidden = false;
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
  get('avatar').textContent = '';
  delete get('workspace').dataset.thread;
  get('status').textContent = 'Signed out';
  get('disconnect').hidden = true;
  get('open-queue').hidden = true;
  fetch('/api/auth', {headers: REQUEST_HEADERS}).then((response) => response.json())
    .then((state) => showSignIn(state.owner ? 'login' : 'setup', message)).catch(() => showSignIn('login', message));
}

get('disconnect').addEventListener('click', async () => {
  await authCall('logout').catch(() => {});
  signOutHere();
});
get('folder').addEventListener('change', showThreads);
get('search').addEventListener('input', showThreads);
get('new').addEventListener('click', () => { select(''); get('address').focus(); });
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
    get('schedule-status').textContent = 'Sent to your phone. Tap the notification there to call.';
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
get('schedule').addEventListener('submit', async (event) => {
  event.preventDefault();
  const currentSession = session;
  const address = get('address').value.trim();
  const draft = get('body').value;
  const current = active;
  const body = reply ? 'Reply to “' + excerpt(reply, 240) + '”\n\n' + draft : draft;
  if (!draft.trim()) { showError(new Error('Enter a message')); return; }
  get('send').disabled = true;
  try {
    const due = get('due').value;
    const when = due && get('later').open ? new Date(due).getTime() : Date.now();
    if (active && internet(threadProtocol(active))) await send(body, draft, reply ? {kind: 'reply', target: replyId} : null, when);
    else await queueSms(body, address, when);
    if (currentSession === session && current === active) {
      get('body').value = '';
      drafts.delete(current);
      setReply('');
      get('later').open = false;
    }
  } catch (error) {
    if (currentSession === session) showError(error);
  } finally {
    if (currentSession === session) get('send').disabled = false;
  }
});
setInterval(() => {
  if (signedIn && !get('workspace').hidden) refresh().catch(() => {
    if (signedIn) get('status').textContent = 'Sync unavailable';
  });
}, 5000);

/** Opens the app: signed in already, or a token handed over by the desktop app, or the password page. */
async function start() {
  // The desktop app opens with its token after "#token=", which never leaves the browser. It's swapped for a
  // session cookie at once and forgotten. Browsers that kept the old token sign in the same way, once.
  let handed = '';
  if (location.hash.startsWith('#token=')) {
    handed = decodeURIComponent(location.hash.slice(7));
    history.replaceState(null, '', location.pathname);
  }
  const kept = localStorage.getItem('fluidToken') || '';
  localStorage.removeItem('fluidToken');
  for (const value of [handed, kept]) {
    if (!/^[0-9A-Za-z_-]{32,256}$/.test(value)) continue;
    try { await authCall('token', {token: value, remember: true}); break; } catch (error) { /* try the next one */ }
  }
  const state = await (await fetch('/api/auth', {headers: REQUEST_HEADERS})).json();
  if (state.signed_in) {
    try { await enterWorkspace(); return; } catch (error) { signedIn = false; }
  }
  showSignIn(state.owner ? 'login' : 'setup');
}

applyAppearance(themeAppearance({theme: 'fluid', mode: 'system'}));
document.addEventListener("DOMContentLoaded", () => start().catch(() => showSignIn('login', 'FluidMessages Web can’t be reached right now. Check your connection and reload.')));
