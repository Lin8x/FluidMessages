'use strict';
// Cards in FluidMessages Web: pictures and GIFs from links, stickers, polls, calendar events, link previews,
// games, and a full-screen viewer for pictures. The formats are the same plain text as the Android app's
// (RichCards.kt), so every card reads sensibly in other apps too. Pictures and previews come through your own
// server (/api/picture, /api/preview), so the browser never contacts other sites.

const cardBlobs = new Map();
// The file behind each blob: address this page made. The page's security policy doesn't let it fetch blob:
// addresses back, so saving or reading a file uses the kept Blob instead.
const blobFiles = new Map();

function blobUrl(blob) {
  const url = URL.createObjectURL(blob);
  blobFiles.set(url, blob);
  return url;
}

async function authorizedBlob(path) {
  if (cardBlobs.has(path)) return cardBlobs.get(path);
  const pending = fetch(path, {headers: REQUEST_HEADERS})
    .then((response) => (response.ok ? response.blob() : null)).then((blob) => (blob ? blobUrl(blob) : null))
    .catch(() => null);
  cardBlobs.set(path, pending);
  return pending;
}

async function postToChat(body) {
  if (active && internet(threadProtocol(active))) await send(body, body, null);
  else await queueSms(body, threadAddress(active) || get('address').value.trim(), Date.now());
}

function newCardId() {
  const bytes = crypto.getRandomValues(new Uint8Array(4));
  return [...bytes].map((value) => value.toString(16).padStart(2, '0')).join('');
}

// The full-screen viewer, with Download and Close at the top.

const DOWNLOAD_ICON = 'M12 3a1 1 0 0 1 1 1v9.59l3.3-3.3a1 1 0 1 1 1.4 1.42l-5 5a1 1 0 0 1-1.4 0l-5-5a1 1 0 1 1 1.4-1.42l3.3 3.3V4a1 1 0 0 1 1-1zM5 19h14a1 1 0 1 1 0 2H5a1 1 0 1 1 0-2z';

function iconSvg(d) {
  const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
  svg.setAttribute('viewBox', '0 0 24 24');
  svg.setAttribute('aria-hidden', 'true');
  const path = document.createElementNS('http://www.w3.org/2000/svg', 'path');
  path.setAttribute('d', d);
  path.setAttribute('fill', 'currentColor');
  svg.append(path);
  return svg;
}

/** A sensible file name for a picture: its own name, or one from its link, with the right extension. */
function mediaName(name, blob, fallback = 'Picture') {
  const types = {'image/gif': 'gif', 'image/png': 'png', 'image/jpeg': 'jpg', 'image/webp': 'webp', 'image/avif': 'avif', 'image/heic': 'heic',
    'video/mp4': 'mp4', 'video/webm': 'webm', 'video/quicktime': 'mov'};
  let base = (name || '').split(/[?#]/)[0];
  if (/^https?:/.test(base)) {
    let parts = [];
    try { parts = new URL(base).pathname.split('/').filter(Boolean).map(decodeURIComponent); } catch (error) { parts = []; }
    let last = parts.pop() || '';
    // GIF sites name every file giphy.gif or similar; the folder above it says which one it is.
    if (/^(giphy|tenor|source|original|image|media|\d+)(\.\w+)?$/i.test(last) && parts.length) last = parts.pop() + (/\.\w+$/.exec(last)?.[0] || '');
    base = last;
  }
  base = base.replace(/[\\/:*?"<>|]+/g, ' ').trim().slice(0, 100);
  const extension = types[(blob?.type || '').split(';')[0]];
  if (!base) base = fallback + ' ' + new Date().toISOString().slice(0, 10);
  return /\.\w{2,5}$/.test(base) || !extension ? base : base + '.' + extension;
}

async function saveMedia(src, name, fallback) {
  const blob = blobFiles.get(src);
  if (!blob) throw new Error('That file isn’t available anymore. Reload and try again.');
  saveBlob(blob, mediaName(name, blob, fallback));
}

function downloadButton(src, name, label, className = 'media-download') {
  const button = element('button', '', className);
  button.type = 'button';
  button.title = label;
  button.setAttribute('aria-label', label);
  button.append(iconSvg(DOWNLOAD_ICON));
  button.addEventListener('click', (event) => { event.stopPropagation(); saveMedia(src, name).catch(showError); });
  return button;
}

function openViewer(src, alt, name) {
  const overlay = element('div', '', 'viewer');
  overlay.setAttribute('role', 'dialog');
  overlay.setAttribute('aria-modal', 'true');
  overlay.setAttribute('aria-label', alt || 'Picture');
  const picture = Object.assign(element('img', ''), {src, alt: alt || 'Picture'});
  const bar = element('div', '', 'viewer-bar');
  const save = element('button', '', 'viewer-button');
  save.type = 'button';
  save.append(iconSvg(DOWNLOAD_ICON), element('span', 'Download'));
  save.addEventListener('click', (event) => { event.stopPropagation(); saveMedia(src, name || alt, alt).catch(showError); });
  const close = element('button', '✕', 'viewer-button viewer-close');
  close.type = 'button';
  close.setAttribute('aria-label', 'Close');
  bar.append(save, close);
  let zoomed = false;
  picture.addEventListener('click', (event) => { event.stopPropagation(); zoomed = !zoomed; picture.classList.toggle('zoomed', zoomed); });
  const opener = document.activeElement;
  const done = () => { overlay.remove(); document.removeEventListener('keydown', key); opener?.focus?.(); };
  const key = (event) => {
    if (event.key === 'Escape') done();
    // Keep Tab inside the viewer.
    if (event.key === 'Tab') { event.preventDefault(); (document.activeElement === save ? close : save).focus(); }
  };
  overlay.addEventListener('click', done);
  close.addEventListener('click', (event) => { event.stopPropagation(); done(); });
  document.addEventListener('keydown', key);
  overlay.append(picture, bar);
  document.body.append(overlay);
  requestAnimationFrame(() => { overlay.classList.add('open'); save.focus(); });
}

function pictureElement(path, alt, sticker, name) {
  const holder = element('span', '', 'media' + (sticker ? ' sticker' : ''));
  const frame = element('button', '', sticker ? 'picture sticker' : 'picture');
  frame.type = 'button';
  frame.setAttribute('aria-label', 'View ' + (alt || 'picture') + ' full screen');
  const picture = Object.assign(element('img', ''), {alt: alt || '', loading: 'lazy'});
  frame.append(picture);
  holder.append(frame);
  // Files keep their own name; GIFs and stickers from links are named after their link.
  let saveAs = name || '';
  if (!saveAs && path.includes('?url=')) { try { saveAs = decodeURIComponent(path.split('?url=')[1]); } catch (error) { saveAs = ''; } }
  authorizedBlob(path).then((src) => {
    if (!src) { holder.replaceWith(element('span', (alt || 'Picture') + ' unavailable', 'muted')); return; }
    picture.src = src;
    frame.addEventListener('click', () => openViewer(src, alt, saveAs));
    holder.append(downloadButton(src, saveAs, 'Download ' + (alt || 'picture').toLowerCase()));
  });
  return holder;
}

/** Videos sent as files play right in the chat, with Download beside them. */
function attachmentVideo(message, bubble) {
  const file = message.attachment;
  if (!file || !/^video\/(mp4|webm|ogg|quicktime)$/i.test(file.mime || '')) return false;
  const holder = element('span', '', 'media video');
  const video = Object.assign(document.createElement('video'), {controls: true, preload: 'metadata', playsInline: true});
  video.setAttribute('aria-label', file.name || 'Video');
  holder.append(video);
  bubble.prepend(holder);
  fetch('/api/protocols/file?id=' + encodeURIComponent(message.id), {headers: REQUEST_HEADERS})
    .then((response) => (response.ok ? response.blob() : null)).then((blob) => {
      if (!blob) { holder.replaceWith(element('span', 'Video unavailable', 'muted')); return; }
      // The server sends every file as a download; give the browser the real type so it can play it.
      const src = blobUrl(new Blob([blob], {type: file.mime}));
      video.src = src;
      holder.append(downloadButton(src, file.name, 'Download video'));
    }).catch(() => holder.replaceWith(element('span', 'Video unavailable', 'muted')));
  return true;
}

// Pictures, GIFs, and stickers sent as links

const PICTURE_URL = /^https:\/\/\S+?\.(gif|webp|png|jpe?g)(\?[^\s#]*)?(#sticker)?$/i;
const GIF_HOST = /^https:\/\/(media\d*\.giphy\.com|i\.giphy\.com|media\.tenor\.com|c\.tenor\.com)\/\S+(?<!\.mp4)(?<!\.webm)$/i;

function pictureLink(text) {
  const trimmed = (text || '').trim();
  if (!PICTURE_URL.test(trimmed) && !GIF_HOST.test(trimmed)) return null;
  return {url: trimmed.replace(/#sticker$/, ''), sticker: trimmed.endsWith('#sticker')};
}

// Polls

const POLL = /^📊 Poll: (.+)\n((?:\d{1,2}\. .+\n?){2,12})Reply with a number to vote · poll ([a-z0-9]{6,12})\s*$/;
const VOTE = /^🗳️ (.+) · voted in “(.+)” \(poll ([a-z0-9]{6,12})\)\s*$/;

function parsePoll(text) {
  const match = POLL.exec((text || '').trim());
  if (!match) return null;
  return {id: match[3], question: match[1].trim(), options: match[2].trim().split('\n').map((line) => line.replace(/^\d{1,2}\. /, '').trim())};
}

function pollText(question, options) {
  return '📊 Poll: ' + question.replace(/\n/g, ' ').trim() + '\n' + options.map((option, index) => (index + 1) + '. ' + option.replace(/\n/g, ' ').trim()).join('\n')
    + '\nReply with a number to vote · poll ' + newCardId();
}

function tallyPolls(history) {
  const polls = new Map(), votes = new Map();
  const moves = trackGames(history).consumed;
  let latest = null;
  for (const message of history) {
    const voter = message.type === 1 ? (message.author || 'them') : 'you';
    const poll = parsePoll(message.body);
    if (poll) { polls.set(poll.id, poll); latest = poll; continue; }
    const vote = VOTE.exec((message.body || '').trim());
    if (vote) {
      const target = polls.get(vote[3]);
      const index = target ? target.options.indexOf(vote[1]) : -1;
      if (index >= 0) { if (!votes.has(target.id)) votes.set(target.id, new Map()); votes.get(target.id).set(voter, index); }
      continue;
    }
    if (moves.has(message)) continue;
    const number = /^\s*(\d{1,2})\s*$/.exec(message.body || '');
    if (number && latest && +number[1] >= 1 && +number[1] <= latest.options.length) {
      if (!votes.has(latest.id)) votes.set(latest.id, new Map());
      votes.get(latest.id).set(voter, +number[1] - 1);
    }
  }
  return votes;
}

function pollCard(poll, history) {
  const votes = tallyPolls(history).get(poll.id) || new Map();
  const card = element('div', '', 'poll-card');
  card.append(element('small', 'POLL', 'card-kind'), element('strong', poll.question));
  const total = votes.size;
  poll.options.forEach((option, index) => {
    const count = [...votes.values()].filter((value) => value === index).length;
    const button = element('button', '', 'poll-option' + (votes.get('you') === index ? ' chosen' : ''));
    button.type = 'button';
    button.style.setProperty('--share', total ? (count / total * 100) + '%' : '0%');
    button.append(element('span', (votes.get('you') === index ? '✓ ' : '') + option), element('b', total ? String(count) : ''));
    button.addEventListener('click', () => postToChat('🗳️ ' + option + ' · voted in “' + poll.question + '” (poll ' + poll.id + ')').catch(showError));
    card.append(button);
  });
  card.append(element('small', total === 0 ? 'No votes yet · click an option' : total === 1 ? '1 vote' : total + ' votes', 'muted'));
  return card;
}

// Calendar events

function parseEvent(text) {
  if (!(text || '').startsWith('📅 ')) return null;
  const match = /Add to calendar: (https:\/\/calendar\.google\.com\/calendar\/render\?action=TEMPLATE\S+)/.exec(text);
  if (!match) return null;
  const url = new URL(match[1]);
  const dates = (url.searchParams.get('dates') || '').split('/');
  if (dates.length !== 2) return null;
  const allDay = dates[0].length === 8;
  const read = (value) => allDay ? Date.UTC(+value.slice(0, 4), +value.slice(4, 6) - 1, +value.slice(6, 8))
    : Date.UTC(+value.slice(0, 4), +value.slice(4, 6) - 1, +value.slice(6, 8), +value.slice(9, 11), +value.slice(11, 13), +value.slice(13, 15));
  return {title: url.searchParams.get('text') || 'Event', start: read(dates[0]), end: read(dates[1]), allDay,
    location: url.searchParams.get('location') || '', notes: url.searchParams.get('details') || '', link: match[1]};
}

function eventWhen(event) {
  const day = {weekday: 'long', month: 'long', day: 'numeric', year: 'numeric'};
  if (event.allDay) return new Date(event.start).toLocaleDateString(undefined, {...day, timeZone: 'UTC'});
  const start = new Date(event.start), end = new Date(event.end);
  const time = {hour: 'numeric', minute: '2-digit'};
  return start.toLocaleDateString(undefined, day) + ' · ' + start.toLocaleTimeString(undefined, time) + ' – '
    + (start.toDateString() === end.toDateString() ? '' : end.toLocaleDateString(undefined, day) + ' ') + end.toLocaleTimeString(undefined, time);
}

function eventLink(event) {
  const pad = (value) => String(value).padStart(2, '0');
  const stamp = (millis) => {
    const date = new Date(millis);
    const d = date.getUTCFullYear() + pad(date.getUTCMonth() + 1) + pad(date.getUTCDate());
    return event.allDay ? d : d + 'T' + pad(date.getUTCHours()) + pad(date.getUTCMinutes()) + pad(date.getUTCSeconds()) + 'Z';
  };
  return 'https://calendar.google.com/calendar/render?action=TEMPLATE&text=' + encodeURIComponent(event.title) + '&dates=' + stamp(event.start)
    + '/' + stamp(event.end) + (event.location ? '&location=' + encodeURIComponent(event.location) : '') + (event.notes ? '&details=' + encodeURIComponent(event.notes) : '');
}

function eventText(event) {
  return ['📅 ' + event.title, eventWhen(event), event.location && '📍 ' + event.location, event.notes, 'Add to calendar: ' + eventLink(event), RSVP_ASK]
    .filter(Boolean).join('\n');
}

function eventIcs(event) {
  const pad = (value) => String(value).padStart(2, '0');
  const stamp = (millis, dayOnly) => {
    const date = new Date(millis);
    const d = date.getUTCFullYear() + pad(date.getUTCMonth() + 1) + pad(date.getUTCDate());
    return dayOnly ? d : d + 'T' + pad(date.getUTCHours()) + pad(date.getUTCMinutes()) + pad(date.getUTCSeconds()) + 'Z';
  };
  const esc = (text) => text.replace(/\\/g, '\\\\').replace(/;/g, '\;').replace(/,/g, '\\,').replace(/\n/g, '\\n');
  return ['BEGIN:VCALENDAR', 'VERSION:2.0', 'PRODID:-//FluidMessages//EN', 'BEGIN:VEVENT', 'UID:' + newCardId() + '@fluidmessages',
    'DTSTAMP:' + stamp(Date.now(), false), event.allDay ? 'DTSTART;VALUE=DATE:' + stamp(event.start, true) : 'DTSTART:' + stamp(event.start, false),
    event.allDay ? 'DTEND;VALUE=DATE:' + stamp(event.end, true) : 'DTEND:' + stamp(event.end, false), 'SUMMARY:' + esc(event.title)]
    .concat(event.location ? ['LOCATION:' + esc(event.location)] : [], event.notes ? ['DESCRIPTION:' + esc(event.notes)] : [], ['END:VEVENT', 'END:VCALENDAR'])
    .join('\r\n') + '\r\n';
}

function eventCard(event) {
  const card = element('div', '', 'event-card');
  const date = new Date(event.start);
  const zone = event.allDay ? {timeZone: 'UTC'} : {};
  const badge = element('div', '', 'event-date');
  badge.append(element('small', date.toLocaleDateString(undefined, {month: 'short', ...zone}).toUpperCase()),
    element('strong', date.toLocaleDateString(undefined, {day: 'numeric', ...zone})));
  const head = element('div', '', 'event-head');
  const words = element('div', '');
  words.append(element('strong', event.title), element('small', eventWhen(event), 'muted'));
  head.append(badge, words);
  card.append(head);
  if (event.location) card.append(element('p', '📍 ' + event.location));
  if (event.notes) card.append(element('p', event.notes, 'muted'));
  const add = Object.assign(element('a', 'Google Calendar', 'primary'), {href: event.link, target: '_blank', rel: 'noopener noreferrer'});
  const ics = element('button', 'Download .ics');
  ics.type = 'button';
  ics.addEventListener('click', () => {
    const url = URL.createObjectURL(new Blob([eventIcs(event)], {type: 'text/calendar'}));
    const link = Object.assign(element('a', ''), {href: url, download: (event.title.replace(/[^A-Za-z0-9 _-]/g, '').trim() || 'event').slice(0, 40) + '.ics'});
    link.click();
    setTimeout(() => URL.revokeObjectURL(url), 30000);
  });
  const actions = element('div', '', 'card-actions');
  actions.append(add, ics);
  card.append(actions);
  return card;
}

// Games: chess, tic-tac-toe, and four in a row, the same plain text as the Android app (Games.kt). People on
// other apps see the board drawn in text and reply with just their move, which counts as their move here too.

const GAME_KINDS = {
  chess: {key: 'chess', title: 'Chess', icon: '♟️', max: 600},
  ttt: {key: 'ttt', title: 'Tic-tac-toe', icon: '❌', max: 9},
  four: {key: 'four', title: 'Four in a row', icon: '🔴', max: 42},
};
const GAME = /fm-game:([a-z]{2,8}):([a-z0-9]{6,12}):([fs]):([a-h0-9qrbn,]{0,3600})(:resign)?\s*$/;
const LEGACY_CHESS = /fm-chess:([a-z0-9]{6,12}):([wb]):([a-h1-8qrbn,]{0,3600})(:resign)?/;
const PIECES = {1: '♟', 2: '♞', 3: '♝', 4: '♜', 5: '♛', 6: '♚'};
const LOOKS_LIKE_MOVE = /^(?:[kqrbn]?[a-h]?[1-8]?x?[a-h][1-8]=?[qrbn]?|[a-h][1-8][a-h][1-8][qrbn]?|[o0][o0][o0]?)$/i;
const keycap = (number) => number + '️⃣';

class ChessPosition {
  constructor(game) { this.game = game; this.kind = 'chess'; }
  get firstToMove() { return this.game.white; }
  get result() {
    if (this._result) return this._result;
    const state = this.game.outcome();
    this._result = state === 'playing' ? 'playing' : state === 'checkmate' ? (this.game.white ? 'second' : 'first') : 'draw';
    return this._result;
  }
  get note() {
    if (this.result === 'playing' && this.game.check()) return 'Check!';
    if (this.result === 'draw' && !this.game.legal().length) return 'Stalemate';
    return null;
  }
  play(text) {
    const move = this.game.parse(text);
    if (!move || !this.game.legal().some((m) => m.from === move.from && m.to === move.to && (m.promotion || 0) === (move.promotion || 0))) return null;
    const next = this.game.copy();
    next.play(move);
    return new ChessPosition(next);
  }
  describe(text) { const move = this.game.parse(text); return move ? this.game.san(move) : text; }
  readReply(text) {
    const raw = text.trim().replace(/[!?.+#]+$/, '').replace(/[ -]/g, '');
    // Most short messages aren't moves; only ones shaped like a move are checked against the board.
    if (raw.length > 8 || !LOOKS_LIKE_MOVE.test(raw)) return null;
    const legal = this.game.legal();
    const castle = raw.toUpperCase().replace(/0/g, 'O');
    if (castle === 'OO' || castle === 'OOO') {
      const move = legal.find((m) => Math.abs(this.game.board[m.from]) === FluidChess.KING && Math.abs(m.to - m.from) === 2 && (m.to > m.from) === (castle === 'OO'));
      return move ? this.game.uci(move) : null;
    }
    const lower = raw.toLowerCase();
    if (this.play(lower)) return lower;
    if (lower.length === 4 && this.play(lower + 'q')) return lower + 'q';
    const key = (san) => san.replace(/[x=+#]/g, '');
    const upFirst = raw[0].toUpperCase() + raw.slice(1);
    const upLast = (s) => s.slice(0, -1) + s.slice(-1).toUpperCase();
    const notation = legal.map((move) => [move, key(this.game.san(move))]);
    for (const candidate of new Set([raw, upFirst, upLast(raw), upLast(upFirst), lower])) {
      const matches = notation.filter(([, san]) => san === key(candidate));
      if (matches.length === 1) return this.game.uci(matches[0][0]);
    }
    return null;
  }
  textBoard(forFirst) {
    const ranks = forFirst ? [7, 6, 5, 4, 3, 2, 1, 0] : [0, 1, 2, 3, 4, 5, 6, 7];
    const files = forFirst ? [0, 1, 2, 3, 4, 5, 6, 7] : [7, 6, 5, 4, 3, 2, 1, 0];
    const rows = ranks.map((rank) => (rank + 1) + ' ' + files.map((file) => {
      const piece = this.game.board[rank * 8 + file];
      if (!piece) return '.';
      const letter = ' pnbrqk'[Math.abs(piece)];
      return piece > 0 ? letter.toUpperCase() : letter;
    }).join(' '));
    return rows.concat('  ' + files.map((file) => 'abcdefgh'[file]).join(' ')).join('\n');
  }
  howToReply(forFirst) {
    return 'Reply with your move, like ' + (forFirst ? 'e4 or Nf3' : 'e5 or Nf6') + '. You’re ' + (forFirst ? 'white (capital letters)' : 'black (small letters)') + '.';
  }
}

class GridPosition {
  constructor(columns, rows, goal, drop, cells) { Object.assign(this, {columns, rows, goal, drop, cells, kind: drop ? 'four' : 'ttt'}); }
  get count() { return this.cells.filter(Boolean).length; }
  get firstToMove() { return this.count % 2 === 0; }
  get line() {
    if (this._line) return this._line;
    this._line = [];
    for (let row = 0; row < this.rows && !this._line.length; row++) for (let column = 0; column < this.columns && !this._line.length; column++) {
      const mark = this.cells[row * this.columns + column];
      if (!mark) continue;
      for (const [dc, dr] of [[1, 0], [0, 1], [1, 1], [1, -1]]) {
        const line = [];
        for (let step = 0; step < this.goal; step++) {
          const c = column + dc * step, r = row + dr * step;
          if (c < 0 || c >= this.columns || r < 0 || r >= this.rows || this.cells[r * this.columns + c] !== mark) break;
          line.push(r * this.columns + c);
        }
        if (line.length === this.goal) { this._line = line; break; }
      }
    }
    return this._line;
  }
  get result() {
    if (this.line.length) return this.cells[this.line[0]] === 1 ? 'first' : 'second';
    return this.count === this.cells.length ? 'draw' : 'playing';
  }
  get note() { return null; }
  target(move) {
    if (!/^\d$/.test(move)) return null;
    const number = +move;
    if (this.drop) {
      if (number < 1 || number > this.columns) return null;
      for (let row = 0; row < this.rows; row++) if (!this.cells[row * this.columns + number - 1]) return row * this.columns + number - 1;
      return null;
    }
    return number >= 1 && number <= this.cells.length && !this.cells[number - 1] ? number - 1 : null;
  }
  play(move) {
    if (this.result !== 'playing') return null;
    const at = this.target(move);
    if (at === null) return null;
    const cells = this.cells.slice();
    cells[at] = this.firstToMove ? 1 : 2;
    return new GridPosition(this.columns, this.rows, this.goal, this.drop, cells);
  }
  describe(move) {
    if (this.drop) return 'column ' + move;
    return {5: 'the middle', 1: 'the top left', 3: 'the top right', 7: 'the bottom left', 9: 'the bottom right'}[move] || 'square ' + move;
  }
  readReply(text) {
    const match = (this.drop ? /^\s*(?:col(?:umn)?\s*)?(\d)\s*[.!]?\s*$/i : /^\s*(\d)\s*[.!]?\s*$/).exec(text);
    return match && this.target(match[1]) !== null ? match[1] : null;
  }
  mark(player) { return this.drop ? (player === 1 ? '🔴' : '🟡') : (player === 1 ? '❌' : '⭕'); }
  textBoard() {
    const rows = [];
    if (this.drop) {
      rows.push(Array.from({length: this.columns}, (_, i) => keycap(i + 1)).join(''));
      for (let row = this.rows - 1; row >= 0; row--) rows.push(Array.from({length: this.columns}, (_, c) => this.cells[row * this.columns + c] ? this.mark(this.cells[row * this.columns + c]) : '⚪').join(''));
    } else for (let row = 0; row < this.rows; row++) {
      rows.push(Array.from({length: this.columns}, (_, c) => { const i = row * this.columns + c; return this.cells[i] ? this.mark(this.cells[i]) : keycap(i + 1); }).join(''));
    }
    return rows.join('\n');
  }
  howToReply(forFirst) {
    return this.drop ? 'Reply with a column number (1–' + this.columns + ') to drop your piece. You’re ' + this.mark(forFirst ? 1 : 2) + '.'
      : 'Reply with a number to play there. You’re ' + this.mark(forFirst ? 1 : 2) + '.';
  }
}

function gameStart(kind) {
  if (kind === 'chess') return new ChessPosition(FluidChess.Game.start());
  return kind === 'ttt' ? new GridPosition(3, 3, 3, false, new Array(9).fill(0)) : new GridPosition(7, 6, 4, true, new Array(42).fill(0));
}

function gameReplay(kind, moves) {
  if (moves.length > GAME_KINDS[kind].max) return null;
  let position = gameStart(kind);
  for (const move of moves) { position = position.play(move); if (!position) return null; }
  return position;
}

function parseGame(text) {
  text = text || '';
  if (text.length > 6000) return null;
  if (text.startsWith('🎮')) {
    const match = GAME.exec(text);
    if (!match || !Object.hasOwn(GAME_KINDS, match[1])) return null;
    const moves = match[4].split(',').filter(Boolean);
    if (moves.length > GAME_KINDS[match[1]].max) return null;
    return {kind: match[1], id: match[2], senderFirst: match[3] === 'f', moves, resigned: !!match[5]};
  }
  if (text.startsWith('♟')) {
    const match = LEGACY_CHESS.exec(text);
    if (!match) return null;
    const moves = match[3].split(',').filter(Boolean);
    if (moves.length > 600) return null;
    return {kind: 'chess', id: match[1], senderFirst: match[2] === 'w', moves, resigned: !!match[4]};
  }
  return null;
}

function gameText(kind, id, senderFirst, moves, summary, resign) {
  const position = gameReplay(kind, moves) || gameStart(kind);
  const them = !senderFirst;
  const reply = !resign && position.result === 'playing' && position.firstToMove === them ? '\n' + position.howToReply(them) : '';
  return '🎮 ' + GAME_KINDS[kind].title + ' · ' + summary + '\n' + position.textBoard(them) + reply
    + '\nfm-game:' + kind + ':' + id + ':' + (senderFirst ? 'f' : 's') + ':' + moves.join(',') + (resign ? ':resign' : '');
}

function newGameText(kind, senderFirst) {
  return gameText(kind, newCardId(), senderFirst, [], kind === 'chess'
    ? (senderFirst ? 'Let’s play chess! I’m white, so I go first.' : 'Let’s play chess! I’m black, so you go first.')
    : (senderFirst ? 'Let’s play! I’ll go first.' : 'Let’s play! You go first.'));
}

function moveSummary(before, move, after) {
  const played = (before.drop ? 'I dropped in ' : 'I played ') + before.describe(move);
  if (after.result === 'first' || after.result === 'second') return played + (after.kind === 'chess' ? '. Checkmate!' : '. ' + (after.drop ? 'Four' : 'Three') + ' in a row, I win!');
  if (after.result === 'draw') return played + '. It’s a draw!';
  return played + '. ' + (after.note ? after.note + ' ' : '') + 'Your turn!';
}

const gameCache = new WeakMap();

/** Follows every game through a chat, plain replies included. Messages map to how they fit their game. */
function trackGames(history) {
  const cached = gameCache.get(history);
  if (cached && cached.length === history.length && cached.last === history[history.length - 1]) return cached;
  const games = new Map(), views = new Map(), consumed = new Set();
  let lastPoll = -1;
  // Rebuilding a game from a card is the costly part; a flood of long made-up games can't stall the chat.
  let budget = 4000;
  const view = (track, message, extra) => Object.assign({kind: track.kind, id: track.id, moves: track.moves, position: track.position,
    mineFirst: track.mineFirst, resignedByMe: track.resignedByMe, latest: false, byMe: message.type !== 1, moveText: null, reply: false, rejected: false}, extra);
  history.forEach((message, index) => {
    if (message.attachment || message.event) return;
    const body = message.body || '';
    if (parsePoll(body)) { lastPoll = index; return; }
    const outgoing = message.type !== 1;
    const card = parseGame(body);
    if (card) {
      const known = games.get(card.id);
      if (!known) {
        if (card.moves.length > budget) return;
        budget -= card.moves.length;
        const position = gameReplay(card.kind, card.moves);
        if (!position) return;
        const track = {kind: card.kind, id: card.id, moves: card.moves, position, mineFirst: outgoing ? card.senderFirst : !card.senderFirst,
          resignedByMe: card.resigned ? outgoing : null, last: message, index};
        games.set(card.id, track);
        views.set(message, view(track, message, {}));
        return;
      }
      if (known.kind !== card.kind) return;
      // A card must play for its sender's side, keep every earlier move, and add at most the moves since.
      const sideOk = (outgoing ? known.mineFirst : !known.mineFirst) === card.senderFirst;
      const extra = card.moves.length - known.moves.length;
      const keeps = extra >= 0 && known.moves.every((move, i) => card.moves[i] === move);
      let next = known.position, lastBy = null;
      if (sideOk && keeps && extra <= 2 && known.resignedByMe === null && (extra === 0 || known.position.result === 'playing')) {
        for (const move of card.moves.slice(known.moves.length)) { if (!next) break; lastBy = next.firstToMove === card.senderFirst; next = next.play(move); }
      } else next = null;
      const fits = next && ((extra === 0 && card.resigned) || (extra > 0 && lastBy === true));
      if (!fits) { views.set(message, view(known, message, {rejected: true})); return; }
      const moveText = extra === 1 ? known.position.describe(card.moves[card.moves.length - 1]) : null;
      known.moves = card.moves; known.position = next;
      if (card.resigned) known.resignedByMe = outgoing;
      known.last = message; known.index = index;
      views.set(message, view(known, message, {moveText}));
      return;
    }
    if (body.length > 24) return;
    let waiting = null;
    for (const track of games.values()) {
      if (track.resignedByMe !== null || track.position.result !== 'playing') continue;
      if (track.position.firstToMove !== (outgoing ? track.mineFirst : !track.mineFirst)) continue;
      if (!waiting || track.index > waiting.index) waiting = track;
    }
    if (!waiting || (waiting.kind !== 'chess' && lastPoll > waiting.index)) return;
    const move = waiting.position.readReply(body);
    const after = move && waiting.position.play(move);
    if (!after) return;
    const moveText = waiting.position.describe(move);
    waiting.moves = waiting.moves.concat(move); waiting.position = after;
    waiting.last = message; waiting.index = index;
    consumed.add(message);
    views.set(message, view(waiting, message, {moveText, reply: true}));
  });
  for (const track of games.values()) { const item = views.get(track.last); if (item && !item.rejected) item.latest = true; }
  const result = {views, consumed, length: history.length, last: history[history.length - 1]};
  gameCache.set(history, result);
  return result;
}

function gameOver(view) { return view.resignedByMe !== null || view.position.result !== 'playing'; }
function myTurn(view) { return view.latest && !gameOver(view) && view.position.firstToMove === view.mineFirst; }

function gameStatus(view) {
  if (view.resignedByMe !== null) return view.resignedByMe ? 'You resigned' : 'They resigned · you win! 🎉';
  const result = view.position.result;
  if (result === 'playing') return !view.latest ? 'Move ' + view.moves.length : myTurn(view) ? (view.position.note ? view.position.note + ' ' : '') + 'Your move' : 'Their move';
  if (result === 'draw') return view.position.note ? view.position.note + ' · it’s a draw' : 'It’s a draw';
  return result === (view.mineFirst ? 'first' : 'second') ? 'You win! 🎉' : 'They win';
}

function chessBoard(game, flipped, last, onTap, selected, targets) {
  const board = element('div', '', 'chess-board');
  for (let row = 0; row < 8; row++) for (let column = 0; column < 8; column++) {
    const rank = flipped ? row : 7 - row, file = flipped ? 7 - column : column, index = rank * 8 + file;
    const cell = element(onTap ? 'button' : 'span', '', 'chess-square ' + ((rank + file) % 2 ? 'light' : 'dark')
      + (index === selected ? ' selected' : '') + (last && (index === last.from || index === last.to) ? ' last' : '') + (targets && targets.has(index) ? ' target' : ''));
    const piece = game.board[index];
    if (piece) cell.append(element('span', PIECES[Math.abs(piece)] + '︎', piece > 0 ? 'piece white' : 'piece black'));
    if (onTap) { cell.type = 'button'; cell.setAttribute('aria-label', FluidChess.name(index)); cell.addEventListener('click', () => onTap(index)); }
    board.append(cell);
  }
  return board;
}

function gridBoard(position, pending, onTap) {
  const board = element('div', '', 'grid-board ' + (position.drop ? 'four' : 'ttt'));
  board.style.gridTemplateColumns = 'repeat(' + position.columns + ', 1fr)';
  const line = new Set(position.line);
  for (let row = 0; row < position.rows; row++) for (let column = 0; column < position.columns; column++) {
    const index = position.drop ? (position.rows - 1 - row) * position.columns + column : row * position.columns + column;
    const move = String(position.drop ? column + 1 : index + 1);
    const mark = index === pending ? (position.firstToMove ? 1 : 2) : position.cells[index];
    const tappable = onTap && position.target(move) !== null;
    const cell = element(tappable ? 'button' : 'span', '', 'grid-cell' + (mark ? ' p' + mark : '') + (line.has(index) ? ' win' : '') + (index === pending ? ' pending' : ''));
    if (!position.drop && mark) cell.textContent = mark === 1 ? '✕' : '◯';
    if (tappable) { cell.type = 'button'; cell.setAttribute('aria-label', position.describe(move)); cell.addEventListener('click', () => onTap(move)); }
    board.append(cell);
  }
  return board;
}

function lastChessMove(view) {
  if (view.kind !== 'chess' || !view.moves.length) return null;
  return view.position.game.parse(view.moves[view.moves.length - 1]);
}

function gameCard(view) {
  const card = element('div', '', 'chess-card game-card');
  card.append(element('small', GAME_KINDS[view.kind].title.toUpperCase(), 'card-kind'));
  if (view.rejected) { card.append(element('span', 'This message doesn’t fit the game so far, so it was skipped.')); return card; }
  if (view.moveText) card.append(element('span', (view.byMe ? 'You played ' : 'They played ') + view.moveText, 'muted'));
  const holder = element('div', '');
  const status = element('strong', gameStatus(view));
  const controls = element('div', '', 'card-actions');
  card.append(holder, status, controls);
  const playing = myTurn(view);
  let selected = null, pending = null;
  const legal = playing && view.kind === 'chess' ? view.position.game.legal() : [];
  const redraw = () => {
    if (view.kind === 'chess') {
      const game = view.position.game;
      const move = pending ? game.parse(pending) : null;
      const shown = move ? (() => { const next = game.copy(); next.play(move); return next; })() : game;
      const targets = selected === null ? null : new Set(legal.filter((m) => m.from === selected).map((m) => m.to));
      holder.replaceChildren(chessBoard(shown, !view.mineFirst, move || lastChessMove(view), playing && !pending ? (index) => {
        const piece = game.board[index];
        if (selected !== null && targets.has(index)) {
          const options = legal.filter((m) => m.from === selected && m.to === index);
          pending = game.uci(options.find((m) => m.promotion === FluidChess.QUEEN) || options[0]);
          selected = null;
        } else selected = piece && (piece > 0) === view.mineFirst ? index : null;
        redraw();
      } : null, selected, targets));
    } else {
      holder.replaceChildren(gridBoard(view.position, pending === null ? null : view.position.target(pending), playing && !pending ? (move) => { pending = move; redraw(); } : null));
    }
    controls.replaceChildren();
    if (pending) {
      const after = view.position.play(pending);
      const undo = element('button', 'Undo');
      undo.type = 'button';
      undo.addEventListener('click', () => { pending = null; redraw(); });
      const go = element('button', 'Send ' + view.position.describe(pending), 'primary');
      go.type = 'button';
      go.disabled = !after;
      go.addEventListener('click', () => {
        go.disabled = true;
        postToChat(gameText(view.kind, view.id, view.mineFirst, view.moves.concat(pending), moveSummary(view.position, pending, after))).catch(showError);
      });
      controls.append(undo, go);
    } else if (playing) {
      const resign = element('button', 'Resign');
      resign.type = 'button';
      resign.addEventListener('click', () => postToChat(gameText(view.kind, view.id, view.mineFirst, view.moves, 'I resign. Good game!', true)).catch(showError));
      controls.append(element('small', view.kind === 'chess' ? 'Click a piece, then where it goes.' : view.kind === 'four' ? 'Click a column.' : 'Click a square.', 'muted'), resign);
    } else if (view.latest && gameOver(view)) {
      const again = element('button', 'Play again');
      again.type = 'button';
      again.addEventListener('click', () => postToChat(newGameText(view.kind, !view.mineFirst)).catch(showError));
      controls.append(again);
    }
  };
  redraw();
  return card;
}

// Link previews

function previewableLink(text) {
  const match = /https:\/\/[^\s<>"]+/.exec(text || '');
  if (!match) return null;
  const link = match[0].replace(/[.,)!?;:”’]+$/, '');
  if (pictureLink(link) || link.includes('calendar.google.com/calendar/render') || link.includes('lichess.org/analysis')) return null;
  return link;
}

const previews = new Map();

function linkPreview(link, bubble) {
  if (!prefs.linkPreviews) return;
  if (!previews.has(link)) previews.set(link, fetch('/api/preview?url=' + encodeURIComponent(link), {headers: REQUEST_HEADERS})
    .then((response) => (response.ok ? response.json() : {})).then((data) => data.preview || null).catch(() => null));
  previews.get(link).then((preview) => {
    if (!preview || !bubble.isConnected) return;
    const card = Object.assign(element('a', '', 'link-preview'), {href: link, target: '_blank', rel: 'noopener noreferrer'});
    if (preview.image) {
      const picture = Object.assign(element('img', ''), {alt: '', loading: 'lazy'});
      authorizedBlob('/api/picture?url=' + encodeURIComponent(preview.image)).then((src) => { if (src) picture.src = src; else picture.remove(); });
      card.append(picture);
      if (/^https:\/\/(www\.|m\.)?(youtube\.com|youtu\.be|vimeo\.com|twitch\.tv)\//.test(link)) card.classList.add('video');
    }
    const words = element('span', '', 'link-words');
    words.append(element('small', preview.site), element('strong', preview.title));
    if (preview.description) words.append(element('span', preview.description, 'muted'));
    card.append(words);
    bubble.append(card);
  });
}

// The one hook app.js calls for every message bubble.

function richCard(message, bubble, history) {
  const body = message.body || '';
  if (message.attachment) return false;
  // Fun and effects in Settings can turn GIFs, games, and dice into plain text.
  const picture = prefs.gifs ? pictureLink(body) : null;
  if (picture) {
    bubble.replaceChildren(pictureElement('/api/picture?url=' + encodeURIComponent(picture.url), picture.sticker ? 'Sticker' : 'GIF', picture.sticker));
    if (picture.sticker) bubble.classList.add('sticker-bubble');
    return true;
  }
  const poll = parsePoll(body);
  if (poll) { bubble.replaceChildren(pollCard(poll, history)); return true; }
  const vote = VOTE.exec(body.trim());
  if (vote) { bubble.replaceChildren(element('span', '🗳️ Voted “' + vote[1] + '”', 'vote-line')); return true; }
  const event = parseEvent(body);
  if (event) { const card = eventCard(event); card.append(rsvpRow(event, message, history)); bubble.replaceChildren(card); return true; }
  const game = prefs.games && trackGames(history).views.get(message);
  if (game) { bubble.replaceChildren(gameCard(game)); return true; }
  const rps = prefs.games && trackRps(history).get(message);
  if (rps) { bubble.replaceChildren(rpsCard(rps)); return true; }
  const random = prefs.random && parseRandom(body);
  if (random) { bubble.replaceChildren(randomCard(random, message.type !== 1)); return true; }
  const gameLink = prefs.games && parseGameLink(body);
  if (gameLink) { bubble.replaceChildren(gameLinkCard(gameLink, message.type !== 1)); return true; }
  const place = LOCATION.exec(body.trim());
  if (place) { bubble.replaceChildren(locationCard(place)); return true; }
  const link = previewableLink(body);
  if (link && !/^FM1:/.test(body)) linkPreview(link, bubble);
  return false;
}

/** Photos, GIFs, and stickers sent as files show as pictures you can open full screen. */
function attachmentPicture(message, bubble) {
  const file = message.attachment;
  if (!file || !/^image\/(png|jpe?g|gif|webp|heic|avif)$/i.test(file.mime || '')) return false;
  const sticker = (file.name || '').startsWith('sticker');
  // With GIFs and stickers off, those show as a file to download instead.
  if (!prefs.gifs && (sticker || /gif/i.test(file.mime))) return false;
  bubble.prepend(pictureElement('/api/protocols/file?id=' + encodeURIComponent(message.id), sticker ? 'Sticker' : /gif/i.test(file.mime) ? 'GIF' : 'Photo', sticker, file.name));
  if (sticker) bubble.classList.add('sticker-bubble');
  return true;
}

/** A short sidebar line for a card, so the inbox never shows the text behind it. */
function cardPreview(body) {
  const picture = pictureLink(body);
  if (picture) return picture.sticker ? 'Sticker' : 'GIF';
  const poll = parsePoll(body);
  if (poll) return '📊 Poll: ' + poll.question;
  const vote = VOTE.exec((body || '').trim());
  if (vote) return '🗳️ Voted “' + vote[1] + '”';
  const event = parseEvent(body);
  if (event) return '📅 ' + event.title;
  if (parseRps(body)) return '✊✋✌️ Rock paper scissors';
  const link = parseGameLink(body);
  if (link) return '🎮 ' + link.game + ' on ' + link.site;
  if (parseRandom(body)) return body.split('\n')[0];
  if (LOCATION.test((body || '').trim())) return '📍 Location';
  const edit = EDIT.exec(body || '');
  if (edit) return '✏️ ' + plainPreview(edit[2]);
  if (parseEffect(body) || (body || '').includes('||')) { const plain = plainPreview(body); if (plain !== body) return plain; }
  const game = parseGame(body);
  if (game) return GAME_KINDS[game.kind].icon + ' ' + GAME_KINDS[game.kind].title + ' · ' + ((/ · (.*)/.exec(body) || [])[1] || '');
  return null;
}

// Composer: polls, events, games, and GIFs

function openPoll() {
  const sheet = dialog('New poll');
  const form = element('form', '', 'settings-section');
  const question = Object.assign(element('input', ''), {required: true, maxLength: 140});
  const list = element('div', '', 'poll-inputs');
  const addOption = () => {
    if (list.children.length >= 10) return;
    list.append(field('Option ' + (list.children.length + 1), Object.assign(element('input', ''), {maxLength: 60})));
  };
  addOption(); addOption();
  const more = element('button', 'Add an option');
  more.type = 'button';
  more.addEventListener('click', addOption);
  const status = element('p', 'Everyone in the chat can vote. People on other apps see a numbered list and can reply with a number.', 'muted');
  form.append(field('Question', question), list, more, element('button', 'Send poll', 'primary'), status);
  form.addEventListener('submit', (submitEvent) => {
    submitEvent.preventDefault();
    const options = [...list.querySelectorAll('input')].map((input) => input.value.trim()).filter(Boolean);
    if (options.length < 2) { status.textContent = 'Add at least two options'; return; }
    if (new Set(options).size !== options.length) { status.textContent = 'Each option needs to be different'; return; }
    sheet.close();
    postToChat(pollText(question.value, options)).catch(showError);
  });
  sheet.append(form);
  sheet.showModal();
  question.focus();
}

function openEvent() {
  const sheet = dialog('Share an event');
  const form = element('form', '', 'settings-section');
  const title = Object.assign(element('input', ''), {required: true, maxLength: 100});
  const allDay = Object.assign(element('input', ''), {type: 'checkbox'});
  const now = new Date(Date.now() + 3600000);
  now.setMinutes(0, 0, 0);
  const local = (date) => new Date(date.getTime() - date.getTimezoneOffset() * 60000).toISOString().slice(0, 16);
  const start = Object.assign(element('input', ''), {type: 'datetime-local', required: true, value: local(now)});
  const end = Object.assign(element('input', ''), {type: 'datetime-local', required: true, value: local(new Date(now.getTime() + 3600000))});
  const where = Object.assign(element('input', ''), {maxLength: 140});
  const notes = Object.assign(element('textarea', ''), {maxLength: 500, rows: 3});
  const status = element('p', 'They can add it to any calendar app, Google Calendar, or import the .ics file.', 'muted');
  const allDayLabel = element('label', '', 'check');
  allDayLabel.append(allDay, document.createTextNode(' All day'));
  form.append(field('What', title), allDayLabel, field('Starts', start), field('Ends', end), field('Where (optional)', where),
    field('Notes (optional)', notes), element('button', 'Send event', 'primary'), status);
  form.addEventListener('submit', (submitEvent) => {
    submitEvent.preventDefault();
    let first = new Date(start.value).getTime(), last = new Date(end.value).getTime();
    if (allDay.checked) {
      const day = (millis) => { const date = new Date(millis); return Date.UTC(date.getFullYear(), date.getMonth(), date.getDate()); };
      first = day(first); last = Math.max(day(last), first) + 86400000;
    } else if (last <= first) { status.textContent = 'The event needs to end after it starts'; return; }
    sheet.close();
    postToChat(eventText({title: title.value.trim(), start: first, end: last, allDay: allDay.checked, location: where.value.trim(), notes: notes.value.trim()}))
      .catch(showError);
  });
  sheet.append(form);
  sheet.showModal();
  title.focus();
}

function openGames() {
  const sheet = dialog('Games');
  const box = element('div', '', 'settings-section');
  const choice = (label, run, cls = 'game-choice') => { const button = element('button', label, cls); button.type = 'button'; button.addEventListener('click', run); box.append(button); return button; };
  const pick = () => {
    sheet.back = null;
    box.replaceChildren(element('p', 'Take turns right in this chat. People on other apps see the board as text and reply with their move.', 'muted'));
    for (const kind of Object.values(GAME_KINDS)) choice(kind.icon + ' ' + kind.title, () => where(kind));
    choice('✊ Rock paper scissors · sealed picks, so it’s fair', rps);
  };
  const rps = () => {
    sheet.back = pick;
    box.replaceChildren(element('p', 'Pick your hand. It’s sealed until they choose. People on other apps reply with rock, paper, or scissors.', 'muted'));
    for (const [hand, emoji] of Object.entries(HANDS)) choice(emoji + ' ' + capitalized(hand), () => { sheet.close(); startRps(hand).then(postToChat).catch(showError); });
  };
  const where = (kind) => {
    sheet.back = pick;
    box.replaceChildren(element('p', 'Where do you want to play?', 'muted'));
    choice('💬 Here in this chat · works with anyone', () => sides(kind));
    for (const [name, url, detail, automatic] of GAME_SITES[kind.key] || []) choice('🌐 ' + name + ' · ' + detail, () => automatic ? lichess(kind) : paste(kind, name, url, detail));
  };
  const sides = (kind) => {
    sheet.back = () => where(kind);
    box.replaceChildren(element('p', kind.key === 'chess' ? 'White moves first.' : 'Who makes the first move?', 'muted'));
    for (const [label, first] of kind.key === 'chess' ? [['Play white', true], ['Play black', false]] : [['I go first', true], ['They go first', false]]) {
      choice(label, () => { sheet.close(); postToChat(newGameText(kind.key, first)).catch(showError); }, first ? 'primary game-choice' : 'game-choice');
    }
  };
  const lichess = (kind) => {
    sheet.back = () => where(kind);
    const status = element('p', 'Free, no account needed. Which side do you want?', 'muted');
    box.replaceChildren(status);
    for (const [label, white] of [['Play white', true], ['Play black', false]]) choice(label, () => {
      status.textContent = 'Making the game…';
      lichessGame(white).then(([mine, theirs]) => {
        window.open(mine, '_blank', 'noopener');
        sheet.close();
        return postToChat('🎮 Chess on Lichess · Join my game: ' + theirs + '\nYou play ' + (white ? 'black' : 'white') + '. Open the link to play in your browser, no account needed.');
      }).catch((error) => { status.textContent = error.message || 'Lichess didn’t answer. Try again.'; });
    }, white ? 'primary game-choice' : 'game-choice');
  };
  const paste = (kind, name, url, detail) => {
    sheet.back = () => where(kind);
    const link = Object.assign(element('input', ''), {type: 'url', placeholder: 'https://…', maxLength: 500});
    const status = element('p', '', 'muted');
    const open = Object.assign(element('a', 'Open ' + name), {href: url, target: '_blank', rel: 'noopener noreferrer'});
    box.replaceChildren(element('p', '1. Open ' + name + ' and start a game with a friend. 2. Copy its invite link. 3. Paste it here and send it.', 'muted'));
    if (/account/.test(detail)) box.append(element('small', name + ' needs a free account for both of you.', 'muted'));
    box.append(open, field('Invite link', link), status);
    choice('Send', () => {
      const value = link.value.trim();
      if (!/^https:\/\/[A-Za-z0-9.-]+\.[A-Za-z]{2,}(\/\S*)?$/.test(value)) { status.textContent = 'Paste the game’s https:// link.'; return; }
      sheet.close();
      postToChat('🎮 ' + kind.title + ' on ' + name + ' · Join my game: ' + value + '\nOpen the link to play in your browser.').catch(showError);
    }, 'primary');
  };
  pick();
  sheet.append(box);
  sheet.showModal();
}

function openGifs() {
  const sheet = dialog('GIFs and stickers');
  const box = element('div', '', 'settings-section');
  const source = element('select', '');
  for (const [value, label] of [['tenor', 'Tenor'], ['giphy', 'GIPHY']]) source.append(Object.assign(element('option', label), {value}));
  source.value = localStorage.getItem('gifSource') || 'tenor';
  const key = Object.assign(element('input', ''), {type: 'password', autocomplete: 'off', value: localStorage.getItem('gifKey') || '',
    placeholder: 'Your free API key'});
  const kind = element('select', '');
  for (const [value, label] of [['gifs', 'GIFs'], ['stickers', 'Stickers']]) kind.append(Object.assign(element('option', label), {value}));
  const query = Object.assign(element('input', ''), {type: 'search', placeholder: 'Search'});
  const grid = element('div', '', 'gif-grid');
  const status = element('p', 'Searches go through FluidMessages Web to the service you pick. The key stays in this browser.', 'muted');
  let timer = 0;
  const search = async () => {
    localStorage.setItem('gifSource', source.value);
    localStorage.setItem('gifKey', key.value.trim());
    if (!key.value.trim()) { grid.replaceChildren(); status.textContent = 'Add an API key from ' + (source.value === 'tenor' ? 'developers.google.com/tenor' : 'developers.giphy.com') + ' to search.'; return; }
    status.textContent = 'Searching…';
    const response = await fetch('/api/gifs?provider=' + source.value + '&stickers=' + (kind.value === 'stickers' ? 1 : 0) + '&q='
      + encodeURIComponent(query.value.trim()), {headers: {...REQUEST_HEADERS, 'X-Fluid-Gif-Key': key.value.trim()}});
    const data = response.ok ? await response.json() : {results: []};
    status.textContent = data.results.length ? '' : 'Nothing found.';
    grid.replaceChildren(...data.results.map((item) => {
      const button = element('button', '', 'gif-cell');
      button.type = 'button';
      const picture = Object.assign(element('img', ''), {alt: kind.value === 'stickers' ? 'Sticker' : 'GIF', loading: 'lazy'});
      authorizedBlob('/api/picture?url=' + encodeURIComponent(item.preview)).then((src) => { if (src) picture.src = src; });
      button.append(picture);
      button.addEventListener('click', () => { sheet.close(); postToChat(item.send + (kind.value === 'stickers' ? '#sticker' : '')).catch(showError); });
      return button;
    }));
  };
  const later = () => { clearTimeout(timer); timer = setTimeout(() => search().catch(() => { status.textContent = 'Search isn’t available right now.'; }), 350); };
  for (const input of [source, key, kind]) input.addEventListener('change', later);
  query.addEventListener('input', later);
  // GIF sites to browse, no key needed: open one, copy a GIF's link, and paste it here.
  const sites = element('div', '', 'card-actions');
  for (const [name, url] of [['Tenor', 'https://tenor.com/search/{query}-gifs'], ['GIPHY', 'https://giphy.com/search/{query}'],
    ['Imgur', 'https://imgur.com/search?q={query}'], ['Reddit r/gifs', 'https://www.reddit.com/r/gifs/search/?q={query}&restrict_sr=1']]) {
    const open = element('a', name);
    open.target = '_blank';
    open.rel = 'noopener noreferrer';
    const update = () => { open.href = query.value.trim() ? url.replace('{query}', encodeURIComponent(query.value.trim())) : url.split('/search')[0]; };
    query.addEventListener('input', update);
    update();
    sites.append(open);
  }
  const paste = Object.assign(element('input', ''), {type: 'url', placeholder: 'Paste a GIF link (https://…gif)'});
  const sendPaste = element('button', 'Send link', 'primary');
  sendPaste.type = 'button';
  sendPaste.addEventListener('click', () => {
    const link = paste.value.trim();
    if (!pictureLink(link)) { status.textContent = 'That isn’t a GIF or picture link. Copy the GIF’s own address (it usually ends in .gif).'; return; }
    sheet.close();
    postToChat(link + (kind.value === 'stickers' ? '#sticker' : '')).catch(showError);
  });
  const pasteRow = element('div', '', 'card-actions');
  pasteRow.append(paste, sendPaste);
  box.append(element('small', 'Browse a GIF site', 'muted'), sites, pasteRow, field('Search here with', source), field('API key', key), field('Show', kind), query, grid, status);
  sheet.append(box);
  sheet.showModal();
  later();
}

// Files, locations, and contact cards from the browser. Internet chats carry files through FluidMessages Web;
// texts with files (MMS) still go from the phone, which has the carrier connection.

const MAX_UPLOAD = 25 * 1024 * 1024;

async function uploadFile(blob, name) {
  if (blob.size > MAX_UPLOAD) throw new Error('Files can be up to 25 MB.');
  const response = await fetch('/api/protocols/upload', {method: 'POST', credentials: 'same-origin', body: blob,
    headers: {...REQUEST_HEADERS, 'Content-Type': blob.type || 'application/octet-stream', 'X-Fluid-Name': encodeURIComponent(name)}});
  const result = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(result.error || 'The upload didn’t finish. Try again.');
  return result.upload;
}

function internetChat() {
  if (active && internet(threadProtocol(active))) return true;
  showError(new Error('Photos, files, and contact cards go through internet chats here. To text a photo, send it from your phone.'));
  return false;
}

async function sendFile(blob, name) {
  say('Uploading ' + name + '…', true);
  const upload = await uploadFile(blob, name);
  const caption = get('body').value.trim();
  await send(caption || name, caption || name, null, Date.now(), {upload});
  if (caption) { get('body').value = ''; drafts.delete(active); }
}

function attachFile() {
  if (!internetChat()) return;
  const input = Object.assign(element('input', ''), {type: 'file'});
  input.addEventListener('change', () => {
    const file = input.files[0];
    if (file) sendFile(file, file.name).catch(showError);
  });
  input.click();
}

// Locations: the same OpenStreetMap link as the phone, shown as a card with Open map.
const LOCATION = /^📍 Location: https:\/\/www\.openstreetmap\.org\/\?mlat=(-?\d{1,2}(?:\.\d+)?)&mlon=(-?\d{1,3}(?:\.\d+)?)(?:#map=\S*)?\s*$/;

function locationText(lat, lon) {
  const a = lat.toFixed(5), b = lon.toFixed(5);
  return '📍 Location: https://www.openstreetmap.org/?mlat=' + a + '&mlon=' + b + '#map=17/' + a + '/' + b;
}

function locationCard(match) {
  const [, lat, lon] = match;
  const card = element('div', '', 'location-card');
  card.append(element('span', 'LOCATION', 'card-kind'), element('strong', '📍 ' + Number(lat).toFixed(4) + ', ' + Number(lon).toFixed(4)));
  const actions = element('div', '', 'card-actions');
  actions.append(Object.assign(element('a', 'Open map', 'primary'), {href: 'https://www.openstreetmap.org/?mlat=' + lat + '&mlon=' + lon + '#map=17/' + lat + '/' + lon,
    target: '_blank', rel: 'noopener noreferrer'}));
  card.append(actions);
  return card;
}

function openLocation() {
  const sheet = dialog('Share your location');
  const box = element('div', '', 'settings-section');
  const status = element('p', 'Your browser asks before sharing where you are. Nothing is sent until you choose Send.', 'muted');
  status.setAttribute('role', 'status');
  const preview = element('div', '');
  const sendButton = element('button', 'Send location', 'primary');
  sendButton.type = 'button';
  sendButton.disabled = true;
  let text = '';
  sendButton.addEventListener('click', () => { sheet.close(); postToChat(text).catch(showError); });
  const find = element('button', 'Find my location');
  find.type = 'button';
  find.addEventListener('click', () => {
    if (!navigator.geolocation) { status.textContent = 'This browser can’t share a location.'; return; }
    status.textContent = 'Finding you…';
    navigator.geolocation.getCurrentPosition((position) => {
      text = locationText(position.coords.latitude, position.coords.longitude);
      preview.replaceChildren(locationCard(LOCATION.exec(text)));
      status.textContent = 'Accurate to about ' + Math.round(position.coords.accuracy) + ' m. Check it, then send.';
      sendButton.disabled = false;
      sendButton.focus();
    }, (error) => {
      status.textContent = error.code === 1 ? 'Location is blocked for this site. Allow it in the browser’s site settings, then try again.'
        : 'Your location isn’t available right now.';
    }, {enableHighAccuracy: true, timeout: 15000, maximumAge: 60000});
  });
  const row = element('div', '', 'button-row');
  row.append(find, sendButton);
  box.append(status, preview, row);
  sheet.append(box);
  sheet.showModal();
}

// Contact cards: a vCard file, the standard every phone understands.
function vcardEscape(value) { return value.replace(/\\/g, '\\\\').replace(/\n/g, '\\n').replace(/([,;])/g, '\\$1'); }

function openContact() {
  if (!internetChat()) return;
  const sheet = dialog('Share a contact');
  const form = element('form', '', 'settings-section');
  const name = Object.assign(element('input', ''), {required: true, maxLength: 100, autocomplete: 'off'});
  const phone = Object.assign(element('input', ''), {type: 'tel', maxLength: 40, autocomplete: 'off'});
  const email = Object.assign(element('input', ''), {type: 'email', maxLength: 120, autocomplete: 'off'});
  const status = element('p', 'They can save it to their contacts. People on other apps get a standard .vcf file.', 'muted');
  status.setAttribute('role', 'status');
  form.append(field('Name', name), field('Phone (optional)', phone), field('Email (optional)', email), status, element('button', 'Send contact', 'primary'));
  form.addEventListener('submit', (event) => {
    event.preventDefault();
    if (!phone.value.trim() && !email.value.trim()) { status.textContent = 'Add a phone number or an email.'; return; }
    const lines = ['BEGIN:VCARD', 'VERSION:3.0', 'FN:' + vcardEscape(name.value.trim())];
    if (phone.value.trim()) lines.push('TEL;TYPE=CELL:' + vcardEscape(phone.value.trim()));
    if (email.value.trim()) lines.push('EMAIL:' + vcardEscape(email.value.trim()));
    lines.push('END:VCARD');
    const file = (name.value.trim().replace(/[^\w .-]/g, '').slice(0, 40) || 'contact') + '.vcf';
    sheet.close();
    sendFile(new Blob([lines.join('\r\n') + '\r\n'], {type: 'text/vcard'}), file).catch(showError);
  });
  sheet.append(form);
  sheet.showModal();
  name.focus();
}

(() => {
  const row = document.querySelector('.compose-row');
  if (!row) return;
  const more = element('button', '＋', 'icon-button compose-more');
  more.type = 'button';
  more.title = 'Photos, location, polls, effects, games, and more';
  more.setAttribute('aria-label', 'Add: photos, location, money, polls, events, effects, GIFs, and games');
  more.setAttribute('aria-haspopup', 'true');
  more.setAttribute('aria-expanded', 'false');
  const menu = element('div', '', 'compose-menu');
  menu.id = 'compose-menu';
  more.setAttribute('aria-controls', menu.id);
  menu.hidden = true;
  const toggle = (open) => {
    if (open) filter();
    menu.hidden = !open;
    more.setAttribute('aria-expanded', String(open));
    if (open) [...menu.querySelectorAll('button')].find((button) => !button.hidden && !button.closest('[hidden]'))?.focus();
  };
  const internetOnly = () => !!active && internet(threadProtocol(active));
  const groups = [['Share', [['📎', 'Photo or file', attachFile, internetOnly], ['📍', 'Location', openLocation], ['👤', 'Contact', openContact, internetOnly],
    ['💸', 'Money', openMoney]]],
  ['Create', [['📊', 'Poll', openPoll], ['📅', 'Event', openEvent]]],
  ['Fun', [['✨', 'Effect', openEffects, () => prefs.effects], ['🖼️', 'GIF or sticker', openGifs, () => prefs.gifs],
    ['🎮', 'Games', openGames, () => prefs.games], ['🎲', 'Dice and random', openRandom, () => prefs.random]]]];
  const items = [];
  for (const [title, entries] of groups) {
    const heading = element('small', title, 'menu-group');
    heading.setAttribute('aria-hidden', 'true');
    const grid = element('div', '', 'menu-grid');
    grid.setAttribute('role', 'group');
    grid.setAttribute('aria-label', title);
    for (const [emoji, label, run, allowed] of entries) {
      const item = element('button', '');
      item.type = 'button';
      item.append(Object.assign(element('span', emoji, 'menu-emoji'), {ariaHidden: 'true'}), element('span', label));
      item.addEventListener('click', () => {
        toggle(false);
        if (!active && !get('address').value.trim()) { showError(new Error('Choose a chat first')); return; }
        run();
      });
      items.push([item, allowed || (() => true)]);
      grid.append(item);
    }
    menu.append(heading, grid);
  }
  // Each time it opens: hide what doesn't apply here, and any group left empty.
  const filter = () => {
    for (const [item, allowed] of items) item.hidden = !allowed();
    for (const grid of menu.querySelectorAll('.menu-grid')) {
      const empty = ![...grid.children].some((child) => !child.hidden);
      grid.hidden = empty;
      grid.previousElementSibling.hidden = empty;
    }
  };
  menu.addEventListener('keydown', (event) => {
    const items = [...menu.querySelectorAll('button')].filter((button) => !button.closest('[hidden]'));
    const index = items.indexOf(document.activeElement);
    if (event.key === 'Escape') { event.preventDefault(); toggle(false); more.focus(); }
    else if (event.key === 'ArrowDown' || event.key === 'ArrowRight') { event.preventDefault(); items[(index + 1) % items.length].focus(); }
    else if (event.key === 'ArrowUp' || event.key === 'ArrowLeft') { event.preventDefault(); items[(index - 1 + items.length) % items.length].focus(); }
  });
  more.addEventListener('click', (event) => { event.stopPropagation(); toggle(menu.hidden); });
  document.addEventListener('click', (event) => { if (!menu.contains(event.target)) toggle(false); });
  row.prepend(more);
  row.style.position = 'relative';
  row.append(menu);
})();

// Formatting and spoilers: *bold*, _italic_, ~strike~, `code`, ```blocks```, and ||spoilers||, as in the Android app (Extras.kt).

const FORMAT_SOURCE = '```\\n?([\\s\\S]+?)```|\\|\\|([\\s\\S]+?)\\|\\||`([^`\\n]+)`|(?<![\\w*])\\*(?=\\S)([^*\\n]+?)(?<=\\S)\\*(?![\\w*])'
  + '|(?<![\\w_])_(?=\\S)([^_\\n]+?)(?<=\\S)_(?![\\w_])|(?<![\\w~])~(?=\\S)([^~\\n]+?)(?<=\\S)~(?![\\w~])';
const EFFECT = /\n?\(sent with (Slam|Loud|Gentle|Invisible ink|Confetti|Balloons|Hearts|Fireworks)\b[^)\n]{0,40}\)\s*$/;
const EFFECTS = {Slam: ['💥', false, 'Lands with a thud'], Loud: ['📢', false, 'Big and shaking'], Gentle: ['🕊️', false, 'Small and soft'],
  'Invisible ink': ['🫥', false, 'Hidden until clicked'], Confetti: ['🎉', true, 'Fills the screen'], Balloons: ['🎈', true, 'Float up the screen'],
  Hearts: ['💕', true, 'Hearts everywhere'], Fireworks: ['🎆', true, 'Bursts of color']};

function parseEffect(text) {
  const match = EFFECT.exec(text || '');
  if (!match) return null;
  const words = text.slice(0, match.index);
  return words.trim() ? {words, effect: match[1]} : null;
}

function effectText(text, effect) {
  return text.trimEnd() + '\n(sent with ' + effect + ' ' + EFFECTS[effect][0] + (effect === 'Invisible ink' ? ' · hidden until tapped in FluidMessages' : '') + ')';
}

function formatInto(parent, text, depth) {
  const pattern = new RegExp(FORMAT_SOURCE, 'g');
  const links = [...text.matchAll(/https?:\/\/\S+/g)].map((match) => [match.index, match.index + match[0].length]);
  let at = 0;
  for (const match of text.matchAll(pattern)) {
    const start = match.index, end = start + match[0].length;
    if (start < at || links.some(([a, b]) => a < end && start < b)) continue;
    parent.append(text.slice(at, start));
    let node;
    if (match[1] !== undefined || match[3] !== undefined) node = element('code', (match[1] ?? match[3]).replace(/\n$/, ''), match[1] !== undefined ? 'code-block' : '');
    else if (match[2] !== undefined) {
      node = element('span', '', 'spoiler');
      node.tabIndex = 0;
      node.setAttribute('role', 'button');
      node.setAttribute('aria-label', 'Hidden text. Click to show.');
      const show = (event) => { event.stopPropagation(); node.classList.add('shown'); node.removeAttribute('role'); node.removeAttribute('aria-label'); };
      node.addEventListener('click', show, {once: true});
      node.addEventListener('keydown', (event) => { if (event.key === 'Enter' || event.key === ' ') show(event); });
      formatInto(node, match[2], depth + 1);
    } else {
      node = element(match[4] !== undefined ? 'strong' : match[5] !== undefined ? 'em' : 's', '');
      const inner = match[4] ?? match[5] ?? match[6];
      if (depth < 4) formatInto(node, inner, depth + 1); else node.textContent = inner;
    }
    parent.append(node);
    at = end;
  }
  parent.append(text.slice(at));
}

/** The words of a message, formatted, without its effect line. Used for every plain text bubble. */
function formattedText(body) {
  const words = parseEffect(body)?.words ?? (body || '');
  const fragment = document.createDocumentFragment();
  if (words.length > 4000 || !/[*_~`|]/.test(words)) fragment.append(words); else formatInto(fragment, words, 0);
  return fragment;
}

function plainPreview(body) { return (parseEffect(body)?.words ?? body).replace(/\|\|[\s\S]+?\|\|/g, '▒▒▒'); }

// Effects: bubble effects move one bubble; screen effects play over the page. Each plays once on its own.

function effectPlayed(id) {
  let seen = [];
  try { seen = JSON.parse(localStorage.getItem('effectsPlayed') || '[]'); } catch (_) { seen = []; }
  if (seen.includes(id)) return true;
  seen.push(id);
  localStorage.setItem('effectsPlayed', JSON.stringify(seen.slice(-300)));
  return false;
}

function playScreenEffect(effect) {
  if (matchMedia('(prefers-reduced-motion: reduce)').matches) return;
  document.querySelector('canvas.screen-effect')?.remove();
  const canvas = Object.assign(document.createElement('canvas'), {className: 'screen-effect'});
  canvas.setAttribute('aria-hidden', 'true');
  document.body.append(canvas);
  const context = canvas.getContext('2d');
  const scale = devicePixelRatio || 1;
  canvas.width = innerWidth * scale; canvas.height = innerHeight * scale;
  context.scale(scale, scale);
  const colors = ['#ff3b30', '#ff9500', '#ffcc00', '#34c759', '#007aff', '#af52de', '#ff2d55'];
  const pieces = Array.from({length: effect === 'Fireworks' ? 6 : 70}, () => Array.from({length: 6}, Math.random));
  const length = effect === 'Balloons' ? 4200 : 3000;
  const began = performance.now();
  const W = innerWidth, H = innerHeight;
  const frame = (now) => {
    const t = Math.min(1, (now - began) / length);
    const fade = t > .85 ? (1 - t) / .15 : 1;
    context.clearRect(0, 0, W, H);
    context.globalAlpha = fade;
    pieces.forEach((p, index) => {
      context.fillStyle = colors[index % colors.length];
      if (effect === 'Confetti') {
        const y = (-.1 + t * (1.1 + p[1]) - p[2] * .3) * H, x = (p[0] + Math.sin(t * 6 + p[3] * 6) * .04) * W;
        context.save(); context.translate(x, y); context.rotate(t * 12 * (p[4] - .5)); context.fillRect(-6, -3, 12 + p[5] * 8, 7); context.restore();
      } else if (effect === 'Balloons' && index < 18) {
        const x = (.05 + p[0] * .9 + Math.sin(t * 5 + p[3] * 6) * .02) * W, y = H * (1.15 + p[1] * .6) - t * H * (1.9 + p[2] * .4), r = 26 + p[5] * 14;
        context.beginPath(); context.ellipse(x, y, r * .85, r * 1.07, 0, 0, Math.PI * 2); context.fill();
        context.strokeStyle = 'rgba(0,0,0,.25)'; context.beginPath(); context.moveTo(x, y + r * 1.1); context.lineTo(x + Math.sin(t * 9) * 6, y + r * 3.2); context.stroke();
      } else if (effect === 'Hearts' && index < 30) {
        const x = p[0] * W + Math.sin(t * 7 + p[4] * 6) * 18, y = H * (1.05 + p[1] * .4) - t * H * (1.2 + p[2] * .5), s = 16 + p[5] * 22;
        context.fillStyle = '#ff2d55';
        context.beginPath(); context.moveTo(x, y + s * .3);
        context.bezierCurveTo(x - s, y - s * .4, x - s * .35, y - s, x, y - s * .35);
        context.bezierCurveTo(x + s * .35, y - s, x + s, y - s * .4, x, y + s * .3); context.fill();
      } else if (effect === 'Fireworks') {
        const start = p[0] * .5, local = (t - start) / .45;
        if (local < 0 || local >= 1) return;
        const cx = (.15 + p[1] * .7) * W, cy = (.15 + p[2] * .45) * H;
        context.globalAlpha = (1 - local) * fade;
        for (let ray = 0; ray < 28; ray++) {
          const angle = ray / 28 * Math.PI * 2, reach = local * (120 + p[3] * 140);
          context.beginPath(); context.arc(cx + Math.cos(angle) * reach, cy + Math.sin(angle) * reach + local * local * 60, 4 * (1 - local) + 1.5, 0, Math.PI * 2); context.fill();
        }
        context.globalAlpha = fade;
      }
    });
    if (t < 1) requestAnimationFrame(frame); else canvas.remove();
  };
  requestAnimationFrame(frame);
}

// Reactions: each built-in reaction plays its own short animation around the bubble, like the phone's
// ReactionEffects.kt. Custom emoji keep the small pop, so this returns false for them.
const REACTION_ANIMATIONS = {'👍': ['thumb', 1100], '❤': ['hearts', 1400], '👎': ['drop', 1400], '😆': ['haha', 1300],
  '‼': ['emphasis', 1100], '❓': ['tumble', 1300]};
const reactionAnimation = (emoji) => REACTION_ANIMATIONS[String(emoji || '').trim().replace(/[︎️]/g, '')] || null;

function playReactionEffect(emoji, bubble, outgoing) {
  const found = reactionAnimation(emoji);
  if (!found) return false;
  if (matchMedia('(prefers-reduced-motion: reduce)').matches) return true;
  const [kind, length] = found;
  // The bubble moves too: a heartbeat, a bounce, a sink, a giggle, a shake, or a puzzled tilt.
  bubble.classList.remove(...Object.values(REACTION_ANIMATIONS).map(([name]) => 'rx-' + name));
  void bubble.offsetWidth;
  bubble.style.setProperty('--rx-length', length + 'ms');
  bubble.classList.add('rx-' + kind);
  setTimeout(() => bubble.classList.remove('rx-' + kind), length + 50);
  // Drawn over the page and ignoring the pointer, so a refresh redrawing the chat doesn't cut it short.
  const canvas = Object.assign(document.createElement('canvas'), {className: 'screen-effect reaction-effect'});
  canvas.setAttribute('aria-hidden', 'true');
  document.body.append(canvas);
  const context = canvas.getContext('2d');
  const scale = devicePixelRatio || 1;
  canvas.width = innerWidth * scale; canvas.height = innerHeight * scale;
  Object.assign(canvas.style, {width: innerWidth + 'px', height: innerHeight + 'px'});
  context.scale(scale, scale);
  const box = bubble.getBoundingClientRect();
  const ax = outgoing ? box.left + 6 : box.right - 6, ay = box.top + 6;
  const pieces = Array.from({length: 14}, () => Array.from({length: 6}, Math.random));
  const clamp = (t) => Math.max(0, Math.min(1, t));
  const easeOut = (t) => 1 - (1 - t) ** 3;
  const easeInOut = (t) => t < .5 ? 4 * t * t * t : 1 - (-2 * t + 2) ** 3 / 2;
  const springPop = (t) => 1 - Math.exp(-6 * t) * Math.cos(12 * t);
  const fadeOut = (t, from) => t < from ? 1 : clamp((1 - t) / (1 - from));
  const rad = (degrees) => degrees * Math.PI / 180;
  const glyph = (text, x, y, size, turn, alpha) => {
    if (size <= 0 || alpha <= 0) return;
    context.save(); context.globalAlpha = alpha; context.translate(x, y); context.rotate(rad(turn));
    context.font = size + 'px "Apple Color Emoji", "Segoe UI Emoji", "Noto Color Emoji", system-ui, sans-serif';
    context.textAlign = 'center'; context.textBaseline = 'middle'; context.fillText(text, 0, 0); context.restore();
  };
  const heart = (color, x, y, s, alpha) => {
    context.save(); context.globalAlpha = alpha; context.fillStyle = color;
    context.beginPath(); context.moveTo(x, y + s * .3);
    context.bezierCurveTo(x - s, y - s * .4, x - s * .35, y - s, x, y - s * .35);
    context.bezierCurveTo(x + s * .35, y - s, x + s, y - s * .4, x, y + s * .3); context.fill(); context.restore();
  };
  const hearts = ['#ff2d55', '#ff375f', '#e0245e'];
  const began = performance.now();
  const frame = (now) => {
    const t = Math.min(1, (now - began) / length);
    context.clearRect(0, 0, innerWidth, innerHeight);
    if (kind === 'hearts') pieces.forEach((p, index) => {
      const delay = p[0] * .25, local = clamp((t - delay) / (1 - delay));
      if (local <= 0) return;
      const angle = rad(-90 + (p[1] - .5) * 130), burst = easeOut(Math.min(1, local / .35)) * (50 + p[2] * 60);
      const x = ax + Math.cos(angle) * burst + Math.sin(local * 6 + p[4] * 6) * 10;
      const y = ay + Math.sin(angle) * burst - local * (160 + p[3] * 220);
      heart(hearts[index % hearts.length], x, y, (12 + p[5] * 14) * Math.min(1, local / .15), fadeOut(local, .7));
    });
    else if (kind === 'thumb') {
      const ring = easeOut(t) * 70, fade = 1 - t;
      context.save(); context.strokeStyle = context.fillStyle = '#0a84ff';
      context.globalAlpha = fade * .8; context.lineWidth = Math.max(1, 5 * fade);
      context.beginPath(); context.arc(ax, ay, ring, 0, Math.PI * 2); context.stroke();
      context.globalAlpha = fade;
      for (let spark = 0; spark < 8; spark++) {
        const angle = spark / 8 * Math.PI * 2;
        context.beginPath();
        context.arc(ax + Math.cos(angle) * ring * 1.18, ay + Math.sin(angle) * ring * 1.18, 3 * fade + 1, 0, Math.PI * 2);
        context.fill();
      }
      context.restore();
      glyph('👍', ax, ay - 44 - t * 10, 54 * springPop(Math.min(1, t / .7)), 0, fadeOut(t, .75));
    } else if (kind === 'drop') {
      const sway = Math.sin(t * 2 * Math.PI);
      glyph('👎', ax + sway * 8, ay - 60 + easeInOut(t) * 140, 46, sway * 8, Math.min(1, t / .15) * fadeOut(t, .65));
    } else if (kind === 'haha') for (let index = 0; index < 7; index++) {
      const p = pieces[index], local = clamp((t - index * .05) / .8);
      if (local <= 0) continue;
      const angle = rad(-160 + index / 6 * 140 + (p[0] - .5) * 12), reach = easeOut(local) * (70 + p[1] * 70);
      const x = ax + Math.cos(angle) * reach, y = ay + Math.sin(angle) * reach - local * 30;
      const wiggle = Math.sin(t * 30 + index) * 14, grow = Math.min(1, local / .2), alpha = fadeOut(local, .7);
      if (index % 2) { glyph('😆', x, y, (24 + p[2] * 10) * grow, wiggle, alpha); continue; }
      // White text on a shaded orange, so "HA" stays readable on any wallpaper.
      context.save(); context.globalAlpha = alpha; context.translate(x, y); context.rotate(rad(wiggle)); context.scale(grow, grow);
      context.font = '900 15px system-ui, sans-serif'; context.textAlign = 'center'; context.textBaseline = 'middle';
      const w = context.measureText('HA').width + 14, h = 24;
      context.fillStyle = '#c2410c'; context.beginPath();
      if (context.roundRect) context.roundRect(-w / 2, -h / 2, w, h, h / 2); else context.rect(-w / 2, -h / 2, w, h);
      context.fill();
      context.fillStyle = '#fff'; context.fillText('HA', 0, 1); context.restore();
    } else if (kind === 'emphasis') for (let index = 0; index < 8; index++) {
      const p = pieces[index], local = clamp((t - p[0] * .15) / .85);
      if (local <= 0) continue;
      const degrees = -180 + index / 7 * 180 + (p[1] - .5) * 14, reach = springPop(Math.min(1, local / .6)) * (55 + p[2] * 45);
      glyph(index % 3 ? '❗' : '‼️', ax + Math.cos(rad(degrees)) * reach, ay + Math.sin(rad(degrees)) * reach,
        18 + p[3] * 12, degrees + 90, fadeOut(local, .7));
    } else if (kind === 'tumble') for (let index = 0; index < 6; index++) {
      const p = pieces[index], local = clamp((t - index * .04) / .8);
      if (local <= 0) continue;
      const x = ax + (p[0] - .5) * 220 * local, y = ay - (220 + p[1] * 120) * local + .5 * 420 * local * local;
      glyph('❓', x, y, 20 + p[3] * 12, local * (360 + p[2] * 360) * (p[4] < .5 ? -1 : 1), fadeOut(local, .7));
    }
    if (t < 1) requestAnimationFrame(frame); else canvas.remove();
  };
  requestAnimationFrame(frame);
  return true;
}

/** Applies a message's effect to its bubble. [recent] messages play it once by themselves. */
function messageEffect(message, bubble, recent) {
  if (message.attachment || !prefs.effects) return;
  const found = parseEffect(message.body);
  if (!found) return;
  const play = () => {
    if (EFFECTS[found.effect][1]) playScreenEffect(found.effect);
    else if (!matchMedia('(prefers-reduced-motion: reduce)').matches) {
      bubble.classList.remove('fx-' + found.effect.toLowerCase());
      void bubble.offsetWidth;
      bubble.classList.add('fx-' + found.effect.toLowerCase());
    }
  };
  if (found.effect === 'Invisible ink') {
    bubble.classList.add('fx-ink');
    bubble.title = 'Click to show';
    bubble.addEventListener('click', () => { bubble.classList.remove('fx-ink'); bubble.title = ''; }, {once: true});
  }
  // Replay sits in the message's ⋯ menu, so effect messages don't carry an extra line.
  const replay = element('button', 'Replay ' + found.effect.toLowerCase());
  replay.type = 'button';
  replay.addEventListener('click', () => { replay.closest('details').open = false; play(); });
  const menu = bubble.closest('.message')?.querySelector('.message-menu');
  if (menu) menu.prepend(replay);
  bubble.title = bubble.title || 'Sent with ' + found.effect;
  if (recent && message.id && !effectPlayed(String(message.id)) && found.effect !== 'Invisible ink') play();
}

// The effect waiting for each chat's next message.
const composerEffects = new Map();
function composerEffect() { return composerEffects.get(active) || null; }

function setComposerEffect(name) {
  if (name) composerEffects.set(active, name); else composerEffects.delete(active);
  showComposerEffect();
}

/** The chip above the message box: "🎉 Confetti", tap to switch, × to send without it. */
function showComposerEffect() {
  const name = composerEffect();
  get('effect-chip').hidden = !name;
  if (!name) return;
  const button = get('switch-effect');
  button.replaceChildren(element('span', EFFECTS[name][0], 'effect-emoji'), element('span', 'Sending with ' + name), element('span', '▾', 'effect-caret'));
  button.setAttribute('aria-label', 'Sending with ' + name + '. Change the effect');
}

function openEffects() {
  const sheet = dialog('Send with effect');
  sheet.classList.add('effects-sheet');
  const box = element('div', '', 'settings-section');
  const current = composerEffect();
  box.append(element('p', 'Pick one, then type and send whenever you’re ready. Other apps get your words with a note naming the effect.', 'muted'));
  const grid = element('div', '', 'effect-grid');
  grid.setAttribute('role', 'group');
  grid.setAttribute('aria-label', 'Effects');
  for (const [name, [emoji, screen, detail]] of Object.entries(EFFECTS)) {
    const button = element('button', '', 'effect-tile');
    button.type = 'button';
    button.setAttribute('aria-pressed', String(current === name));
    button.append(element('span', emoji, 'effect-emoji'), element('strong', name), element('small', detail));
    button.addEventListener('click', () => {
      sheet.close();
      setComposerEffect(name);
      get('body').focus();
    });
    grid.append(button);
    if (screen) button.classList.add('screen');
  }
  box.append(grid);
  if (current) {
    const none = element('button', 'Send without an effect', 'quiet');
    none.type = 'button';
    none.addEventListener('click', () => { sheet.close(); setComposerEffect(null); get('body').focus(); });
    box.append(none);
  }
  sheet.append(box);
  sheet.showModal();
  grid.querySelector('[aria-pressed="true"]')?.focus();
}

get('switch-effect').addEventListener('click', () => openEffects());
get('cancel-effect').addEventListener('click', () => { setComposerEffect(null); get('body').focus(); });

// Edits: "✏️ Edited “old” to: new" replaces the sender's own message.

const EDIT = /^✏️ Edited “([\s\S]+?)” to: ([\s\S]+)$/;
function editQuote(text) { return excerpt(text.replace(/”/g, '"'), 300); }
function editText(old, updated) { return '✏️ Edited “' + editQuote(old) + '” to: ' + updated.trim(); }

function openEditMessage(message) {
  const sheet = dialog('Edit message');
  const box = element('div', '', 'settings-section');
  const words = Object.assign(element('textarea', ''), {rows: 4, maxLength: 1500, value: parseEffect(message.body)?.words ?? message.body});
  const save = element('button', 'Save', 'primary');
  save.type = 'button';
  save.addEventListener('click', () => {
    const updated = words.value.trim();
    if (!updated || updated === message.body.trim()) return;
    sheet.close();
    postToChat(editText(message.body, updated)).catch(showError);
  });
  box.append(words, element('p', 'FluidMessages updates the message. People on other apps get the new text as a correction.', 'muted'), save);
  sheet.append(box);
  sheet.showModal();
  words.focus();
}

// RSVP on events: "✅ Going to “Dinner”", or a plain yes, maybe, or no to the newest invite that asks.

const RSVP_ASK = 'Coming? Reply yes, maybe, or no.';
const RSVPS = {going: ['✅', 'Going', '✅ Going to'], maybe: ['🤔', 'Maybe', '🤔 Maybe going to'], no: ['❌', 'Can’t go', '❌ Can\'t make']};
const RSVP = /^(✅ Going to|🤔 Maybe going to|❌ Can't make) “(.{1,200})”$/;
const RSVP_REPLY = /^\s*(yes|yeah|yep|yup|sure|going|i'?m in|count me in|maybe|perhaps|not sure|no|nope|can'?t|can ?not|i can'?t)\s*[.!]*\s*$/i;
const rsvpCache = new WeakMap();

function tallyRsvps(history) {
  const cached = rsvpCache.get(history);
  if (cached && cached.length === history.length) return cached.answers;
  const byTitle = new Map(), answers = new Map();
  let newest = null, asks = false;
  for (const message of history) {
    if (message.attachment) continue;
    const person = message.type === 1 ? (message.author || 'them') : 'you';
    const event = parseEvent(message.body);
    if (event) { byTitle.set(event.title.replace(/”/g, '"').slice(0, 200), message); newest = [message, event]; asks = message.body.includes(RSVP_ASK); continue; }
    const explicit = RSVP.exec(message.body || '');
    let target = null, answer = null;
    if (explicit) { target = byTitle.get(explicit[2]); answer = explicit[1].startsWith('✅') ? 'going' : explicit[1].startsWith('🤔') ? 'maybe' : 'no'; }
    else if (newest && asks && newest[1].end >= Date.now()) {
      const word = RSVP_REPLY.exec(message.body || '')?.[1]?.toLowerCase();
      if (word) { target = newest[0]; answer = /^(maybe|perhaps|not sure)/.test(word) ? 'maybe' : /^no|can/.test(word) ? 'no' : 'going'; }
    }
    if (!target) continue;
    if (!answers.has(target)) answers.set(target, new Map());
    answers.get(target).set(person, answer);
  }
  rsvpCache.set(history, {length: history.length, answers});
  return answers;
}

function rsvpRow(event, message, history) {
  const replies = tallyRsvps(history).get(message) || new Map();
  const wrap = element('div', '', 'rsvp');
  if (replies.size) {
    const counts = Object.entries(RSVPS).map(([key, [mark, label]]) => {
      const n = [...replies.values()].filter((value) => value === key).length;
      return n ? mark + ' ' + n + ' ' + label.toLowerCase() : '';
    }).filter(Boolean);
    wrap.append(element('strong', counts.join(' · ')));
    const names = [...replies].filter(([person]) => person !== 'you' && person !== 'them');
    if (names.length) wrap.append(element('small', names.map(([person, key]) => person + ' ' + RSVPS[key][0]).join(', '), 'muted'));
  }
  if (event.end >= Date.now()) {
    const row = element('div', '', 'card-actions');
    for (const [key, [, label, prefix]] of Object.entries(RSVPS)) {
      const chosen = replies.get('you') === key;
      const button = element('button', (chosen ? '✓ ' : '') + label, chosen ? 'primary' : '');
      button.type = 'button';
      button.addEventListener('click', () => { if (!chosen) postToChat(prefix + ' “' + event.title.replace(/”/g, '"').slice(0, 200) + '”').catch(showError); });
      row.append(button);
    }
    wrap.append(row);
  }
  return wrap;
}

// Dice, coin flips, and random picks.

const DICE = /^🎲 Rolled (\d)d(\d{1,3}): (\d{1,3}(?: \+ \d{1,3}){0,8})(?: = \d+)?$/;
const COIN = /^🪙 Flipped a coin: (Heads|Tails)$/;
const PICK = /^🎯 Random pick: (.{1,80})\nFrom: (.{1,1600})$/;
const FACES = ['⚀', '⚁', '⚂', '⚃', '⚄', '⚅'];

function parseRandom(text) {
  let match = DICE.exec(text || '');
  if (match) {
    const rolls = match[3].split(' + ').map(Number), sides = +match[2];
    return rolls.length === +match[1] && sides >= 2 && rolls.every((roll) => roll >= 1 && roll <= sides) ? {kind: 'dice', sides, rolls} : null;
  }
  match = COIN.exec(text || '');
  if (match) return {kind: 'coin', heads: match[1] === 'Heads'};
  match = PICK.exec(text || '');
  if (match) {
    const options = match[2].split(' · ').map((option) => option.trim()).filter(Boolean);
    return options.includes(match[1]) ? {kind: 'pick', choice: match[1], options} : null;
  }
  return null;
}

function randomBelow(n) {
  const limit = Math.floor(2 ** 32 / n) * n;
  const value = new Uint32Array(1);
  do crypto.getRandomValues(value); while (value[0] >= limit);
  return value[0] % n;
}

function randomCard(result, outgoing) {
  const card = element('div', '', 'event-card random-card');
  card.append(element('small', result.kind === 'dice' ? 'DICE' : result.kind === 'coin' ? 'COIN FLIP' : 'RANDOM PICK', 'card-kind'));
  if (result.kind === 'dice') {
    const row = element('div', '', 'dice-row');
    for (const roll of result.rolls) row.append(element('span', result.sides === 6 ? FACES[roll - 1] : String(roll), 'die' + (result.sides === 6 ? ' face' : '')));
    card.append(row, element('strong', result.rolls.length > 1 ? 'Total ' + result.rolls.reduce((a, b) => a + b, 0) + ' · ' + result.rolls.length + ' d' + result.sides : 'd' + result.sides));
  } else if (result.kind === 'coin') card.append(element('span', result.heads ? 'H' : 'T', 'coin'), element('strong', result.heads ? 'Heads' : 'Tails'));
  else card.append(element('strong', result.choice), element('small', 'From ' + result.options.join(', '), 'muted'));
  card.append(element('small', 'Picked at random by ' + (outgoing ? 'your' : 'their') + ' device', 'muted'));
  return card;
}

function openRandom() {
  const sheet = dialog('Dice and random');
  const box = element('div', '', 'settings-section');
  const mode = element('select', '');
  for (const label of ['Dice', 'Coin', 'Pick']) mode.append(Object.assign(element('option', label), {value: label}));
  const count = Object.assign(element('input', ''), {type: 'number', min: 1, max: 6, value: 1});
  const sides = element('select', '');
  for (const n of [4, 6, 8, 10, 12, 20, 100]) sides.append(Object.assign(element('option', 'd' + n), {value: n, selected: n === 6}));
  const options = Object.assign(element('textarea', ''), {rows: 4, placeholder: 'Pizza\nTacos\nSushi', maxLength: 800});
  const diceFields = element('div', ''), pickFields = element('div', '');
  diceFields.append(field('How many dice', count), field('Sides', sides));
  pickFields.append(field('Choices, one per line', options));
  const go = element('button', 'Roll', 'primary');
  go.type = 'button';
  const update = () => { diceFields.hidden = mode.value !== 'Dice'; pickFields.hidden = mode.value !== 'Pick'; go.textContent = {Dice: 'Roll', Coin: 'Flip', Pick: 'Pick'}[mode.value]; };
  mode.addEventListener('change', update);
  update();
  go.addEventListener('click', () => {
    let text;
    if (mode.value === 'Dice') {
      const n = Math.min(6, Math.max(1, +count.value || 1)), s = +sides.value;
      const rolls = Array.from({length: n}, () => 1 + randomBelow(s));
      text = '🎲 Rolled ' + n + 'd' + s + ': ' + rolls.join(' + ') + (n > 1 ? ' = ' + rolls.reduce((a, b) => a + b, 0) : '');
    } else if (mode.value === 'Coin') text = '🪙 Flipped a coin: ' + (randomBelow(2) ? 'Heads' : 'Tails');
    else {
      const list = [...new Set(options.value.split('\n').map((line) => line.replace(/ · /g, ' - ').trim().slice(0, 80)).filter(Boolean))].slice(0, 20);
      if (list.length < 2) { options.focus(); return; }
      text = '🎯 Random pick: ' + list[randomBelow(list.length)] + '\nFrom: ' + list.join(' · ');
    }
    sheet.close();
    postToChat(text).catch(showError);
  });
  box.append(field('Choose', mode), diceFields, pickFields, element('p', 'Everyone sees the same result, including people on other apps.', 'muted'), go);
  sheet.append(box);
  sheet.showModal();
}

// Rock paper scissors with a sealed first pick (see Rps.kt for how the seal works).

const RPS_TITLE = '✊✋✌️ Rock paper scissors';
const RPS = /fm-rps:([a-z0-9]{6,12}):(seal|pick|open):([a-z0-9]{1,64})(?::([a-f0-9]{16,64}))?\s*$/;
const HANDS = {rock: '✊', paper: '✋', scissors: '✌️'};
const HAND_REPLY = /^\s*(?:✊|✋|✌️|✌)?\s*(rock|paper|scissors)\s*(?:✊|✋|✌️|✌)?\s*[.!]*\s*$/i;
const beats = (a, b) => (a === 'rock' && b === 'scissors') || (a === 'paper' && b === 'rock') || (a === 'scissors' && b === 'paper');
const capitalized = (word) => word[0].toUpperCase() + word.slice(1);

async function rpsSeal(id, hand, secret) {
  const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(id + ':' + hand + ':' + secret));
  return [...new Uint8Array(digest)].map((byte) => byte.toString(16).padStart(2, '0')).join('').slice(0, 32);
}

function parseRps(text) {
  if (!(text || '').startsWith(RPS_TITLE) || text.length > 1000) return null;
  const match = RPS.exec(text);
  return match ? {id: match[1], stage: match[2], value: match[3], secret: match[4] || null} : null;
}

async function startRps(hand) {
  const id = newCardId();
  const secret = [...crypto.getRandomValues(new Uint8Array(16))].map((byte) => byte.toString(16).padStart(2, '0')).join('');
  localStorage.setItem('rps.' + id, hand + ':' + secret);
  return RPS_TITLE + ' · I’ve locked in my pick. Your turn: reply rock, paper, or scissors.\n'
    + 'FluidMessages seals my pick so I can’t change it. On other apps, you’ll see my pick after you reply.\n'
    + 'fm-rps:' + id + ':seal:' + await rpsSeal(id, hand, secret);
}

const rpsCache = new WeakMap();
function trackRps(history) {
  const cached = rpsCache.get(history);
  if (cached && cached.length === history.length) return cached.views;
  const games = new Map(), views = new Map();
  const view = (track, extra = {}) => ({id: track.id, sealedByMe: track.sealedByMe, hash: track.hash, answer: track.answer, revealed: track.revealed,
    secret: track.secret, latest: false, rejected: false, ...extra});
  for (const message of history) {
    if (message.attachment) continue;
    const outgoing = message.type !== 1;
    const card = parseRps(message.body);
    if (card) {
      const known = games.get(card.id);
      if (card.stage === 'seal' && !known) { const track = {id: card.id, sealedByMe: outgoing, hash: card.value, answer: null, revealed: null, last: message}; games.set(card.id, track); views.set(message, view(track)); }
      else if (card.stage === 'pick' && known) {
        if (!HANDS[card.value] || known.answer || outgoing === known.sealedByMe) views.set(message, view(known, {rejected: true}));
        else { known.answer = card.value; known.last = message; views.set(message, view(known)); }
      } else if (card.stage === 'open' && known) {
        if (!HANDS[card.value] || !known.answer || known.revealed || outgoing !== known.sealedByMe || !card.secret) views.set(message, view(known, {rejected: true}));
        else { known.revealed = card.value; known.secret = card.secret; known.last = message; views.set(message, view(known)); }
      }
      continue;
    }
    const word = HAND_REPLY.exec(message.body || '')?.[1]?.toLowerCase();
    if (!word) continue;
    const waiting = [...games.values()].reverse().find((track) => !track.answer && track.sealedByMe !== outgoing);
    if (!waiting) continue;
    waiting.answer = word; waiting.last = message;
    views.set(message, view(waiting, {reply: true}));
  }
  for (const track of games.values()) { const item = views.get(track.last); if (item && !item.rejected) item.latest = true; }
  rpsCache.set(history, {length: history.length, views});
  return views;
}

function rpsCard(view) {
  const card = element('div', '', 'event-card rps-card');
  card.append(element('small', 'ROCK PAPER SCISSORS', 'card-kind'));
  if (view.rejected) { card.append(element('span', 'This message doesn’t fit the game, so it was skipped.')); return card; }
  const saved = (localStorage.getItem('rps.' + view.id) || '').split(':');
  const myHand = HANDS[saved[0]] ? saved[0] : null;
  const mine = view.sealedByMe ? view.revealed || myHand : view.answer;
  const theirs = view.sealedByMe ? view.answer : view.revealed;
  const row = element('div', '', 'rps-row');
  row.append(element('span', HANDS[mine] || '❔', 'die'), element('small', 'vs', 'muted'), element('span', HANDS[theirs] || (!view.sealedByMe && !view.revealed ? '🔒' : '❔'), 'die'));
  const status = element('strong', '');
  card.append(row, status);
  if (view.revealed) {
    status.textContent = mine === theirs ? 'It’s a tie!' : beats(mine, theirs) ? 'You win! 🎉' : 'They win';
    rpsSeal(view.id, view.revealed, view.secret).then((check) => {
      if (check !== view.hash) status.textContent = '⚠️ Their reveal doesn’t match the pick they sealed.';
    });
  } else if (!view.answer) status.textContent = view.sealedByMe ? 'Your pick is sealed · waiting for them' : 'They sealed a pick. Choose yours.';
  else status.textContent = view.sealedByMe ? 'They picked. Reveal yours to finish.' : 'Waiting for them to reveal';
  const actions = element('div', '', 'card-actions');
  const button = (label, run) => { const b = element('button', label); b.type = 'button'; b.addEventListener('click', run); actions.append(b); };
  if (view.latest) {
    if (!view.answer && !view.sealedByMe) for (const [hand, emoji] of Object.entries(HANDS)) button(emoji + ' ' + capitalized(hand), () => postToChat(RPS_TITLE + ' · I picked ' + hand + ' ' + emoji + '.\nfm-rps:' + view.id + ':pick:' + hand).catch(showError));
    else if (view.answer && !view.revealed && view.sealedByMe) {
      if (myHand && saved[1]) button('Reveal my pick', () => {
        const result = myHand === view.answer ? 'We both picked ' + myHand + ': it’s a tie!' : beats(myHand, view.answer) ? capitalized(myHand) + ' beats ' + view.answer + ': I win!' : capitalized(view.answer) + ' beats ' + myHand + ': you win!';
        postToChat(RPS_TITLE + ' · I picked ' + myHand + ' ' + HANDS[myHand] + '. ' + result + '\nfm-rps:' + view.id + ':open:' + myHand + ':' + saved[1]).catch(showError);
      });
      else actions.append(element('small', 'Your pick was made on another device. Reveal it from there.', 'muted'));
    } else if (view.revealed) for (const [hand, emoji] of Object.entries(HANDS)) button('Again: ' + emoji, () => startRps(hand).then(postToChat).catch(showError));
  }
  card.append(actions);
  return card;
}

// Games on other sites, for people without FluidMessages.

const GAME_LINK = /^🎮 (.{1,40}?) on (.{1,40}?) · Join my game: (https:\/\/\S{1,500})/;
function parseGameLink(text) {
  const match = GAME_LINK.exec(text || '');
  return match ? {game: match[1], site: match[2], url: match[3].replace(/[.,)]+$/, '')} : null;
}
const GAME_SITES = {
  chess: [['Lichess', 'https://lichess.org', 'Free, no account needed. Makes a game link for you.', true],
    ['Chess.com', 'https://www.chess.com/play/online', 'Needs a free account. Start a game with a friend, then paste its link.']],
  ttt: [['Foony', 'https://foony.com/games/tic-tac-toe', 'Start a private room, then paste its link.'], ['Papergames', 'https://papergames.io/en/tic-tac-toe', 'Play with a friend, then paste its link.']],
  four: [['Foony', 'https://foony.com/games/fourinarow', 'Start a private room, then paste its link.'], ['Papergames', 'https://papergames.io/en/connect4', 'Play with a friend, then paste its link.']],
};

function gameLinkCard(link, outgoing) {
  const card = element('div', '', 'event-card');
  card.append(element('small', link.game.toUpperCase(), 'card-kind'), element('strong', (outgoing ? 'You started a game on ' : 'A game on ') + link.site),
    element('small', new URL(link.url).host, 'muted'));
  const open = Object.assign(element('a', outgoing ? 'Open my game' : 'Join the game', 'primary'), {href: link.url, target: '_blank', rel: 'noopener noreferrer'});
  const actions = element('div', '', 'card-actions');
  actions.append(open);
  card.append(actions);
  return card;
}

async function lichessGame(white) {
  const response = await fetch('https://lichess.org/api/challenge/open', {method: 'POST', headers: {'Content-Type': 'application/x-www-form-urlencoded', Accept: 'application/json'},
    body: 'days=3&name=FluidMessages'});
  if (!response.ok) throw new Error('Lichess didn’t answer. Try again in a minute.');
  const data = await response.json();
  const mine = data[white ? 'urlWhite' : 'urlBlack'], theirs = data[white ? 'urlBlack' : 'urlWhite'];
  if (!/^https:\/\/lichess\.org\//.test(mine || '') || !/^https:\/\/lichess\.org\//.test(theirs || '')) throw new Error('Lichess sent back something unexpected.');
  return [mine, theirs];
}

// Contact cards from vCard attachments.

function parseVCard(text) {
  const lines = text.replace(/\r?\n[ \t]/g, '').split(/\r?\n/);
  const value = (line) => line.slice(line.indexOf(':') + 1).replace(/\\,/g, ',').replace(/\;/g, ';').replace(/\\n/gi, ' ').trim();
  let name = '';
  const phones = [], emails = [];
  for (const line of lines) {
    const key = line.split(':')[0].split(';')[0].toUpperCase();
    if (key === 'FN') name = value(line);
    else if (key === 'N' && !name) name = value(line).split(';').filter(Boolean).reverse().join(' ');
    else if (key === 'TEL') { const phone = value(line).replace(/^tel:/, ''); if (phone && !phones.includes(phone)) phones.push(phone); }
    else if (key === 'EMAIL') { const email = value(line); if (email.includes('@') && !emails.includes(email)) emails.push(email); }
  }
  if (!name && !phones.length && !emails.length) return null;
  return {name: (name || phones[0] || emails[0]).slice(0, 120), phones: phones.slice(0, 6), emails: emails.slice(0, 6)};
}

/** A shared contact shows as a card with its numbers and emails, and a button to save the .vcf. */
function attachmentContact(message, bubble) {
  const file = message.attachment;
  if (!file || !/^text\/(x-)?vcard$/i.test(file.mime || '') && !/\.vcf$/i.test(file.name || '')) return false;
  const card = element('div', '', 'event-card contact-card');
  card.append(element('small', 'CONTACT', 'card-kind'), element('strong', file.name || 'Contact'));
  bubble.prepend(card);
  authorizedBlob('/api/protocols/file?id=' + encodeURIComponent(message.id)).then(async (src) => {
    const actions = element('div', '', 'card-actions');
    if (src) {
      const contact = parseVCard(((await blobFiles.get(src)?.text()) || '').slice(0, 65536));
      if (contact) {
        card.replaceChildren(element('small', 'CONTACT', 'card-kind'), element('strong', contact.name));
        for (const phone of contact.phones) card.append(element('span', '📞 ' + phone));
        for (const email of contact.emails) card.append(element('span', '✉️ ' + email));
      }
      actions.append(Object.assign(element('a', 'Save contact (.vcf)', 'primary'), {href: src, download: (contact?.name || 'contact').replace(/[^\w .-]/g, '').slice(0, 40) + '.vcf'}));
    } else {
      const save = element('button', 'Download contact');
      save.type = 'button';
      save.addEventListener('click', () => download(message).catch(showError));
      actions.append(save);
    }
    card.append(actions);
  });
  return true;
}
