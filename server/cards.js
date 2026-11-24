'use strict';
// Cards in FluidMessages Web: pictures and GIFs from links, stickers, polls, calendar events, link previews,
// chess, and a full-screen viewer for pictures. The formats are the same plain text as the Android app's
// (RichCards.kt), so every card reads sensibly in other apps too. Pictures and previews come through your own
// server (/api/picture, /api/preview), so the browser never contacts other sites.

const cardBlobs = new Map();

async function authorizedBlob(path) {
  if (cardBlobs.has(path)) return cardBlobs.get(path);
  const pending = fetch(path, {headers: {Authorization: 'Bearer ' + token}})
    .then((response) => (response.ok ? response.blob() : null)).then((blob) => (blob ? URL.createObjectURL(blob) : null))
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

// The full-screen viewer

function openViewer(src, alt) {
  const overlay = element('div', '', 'viewer');
  const picture = Object.assign(element('img', ''), {src, alt: alt || 'Picture'});
  const close = element('button', '✕', 'viewer-close');
  close.type = 'button';
  close.setAttribute('aria-label', 'Close');
  let zoomed = false;
  picture.addEventListener('click', (event) => { event.stopPropagation(); zoomed = !zoomed; picture.classList.toggle('zoomed', zoomed); });
  const done = () => { overlay.remove(); document.removeEventListener('keydown', key); };
  const key = (event) => { if (event.key === 'Escape') done(); };
  overlay.addEventListener('click', done);
  close.addEventListener('click', done);
  document.addEventListener('keydown', key);
  overlay.append(picture, close);
  document.body.append(overlay);
  requestAnimationFrame(() => overlay.classList.add('open'));
}

function pictureElement(path, alt, sticker) {
  const frame = element('button', '', sticker ? 'picture sticker' : 'picture');
  frame.type = 'button';
  frame.setAttribute('aria-label', 'View ' + (alt || 'picture') + ' full screen');
  const picture = Object.assign(element('img', ''), {alt: alt || '', loading: 'lazy'});
  frame.append(picture);
  authorizedBlob(path).then((src) => {
    if (!src) { frame.replaceWith(element('span', 'Picture unavailable', 'muted')); return; }
    picture.src = src;
    frame.addEventListener('click', () => openViewer(src, alt));
  });
  return frame;
}

// Pictures, GIFs, and stickers sent as links

const PICTURE_URL = /^https:\/\/\S+?\.(gif|webp|png|jpe?g)(\?[^\s#]*)?(#sticker)?$/i;
const GIF_HOST = /^https:\/\/(media\d*\.giphy\.com|i\.giphy\.com|media\.tenor\.com|c\.tenor\.com)\/\S+$/i;

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
  return ['📅 ' + event.title, eventWhen(event), event.location && '📍 ' + event.location, event.notes, 'Add to calendar: ' + eventLink(event)]
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

// Chess

const CHESS = /fm-chess:([a-z0-9]{6,12}):([wb]):([a-h1-8qrbn,]*)(:resign)?/;
const PIECES = {1: '♟', 2: '♞', 3: '♝', 4: '♜', 5: '♛', 6: '♚'};

function parseChess(text) {
  if (!(text || '').startsWith('♟')) return null;
  const match = CHESS.exec(text);
  if (!match) return null;
  return {id: match[1], senderWhite: match[2] === 'w', moves: match[3].split(',').filter(Boolean), resigned: !!match[4]};
}

function chessText(id, white, moves, summary, resign) {
  const game = FluidChess.Game.replay(moves) || FluidChess.Game.start();
  return '♟️ Chess · ' + summary + '\nSee the board: https://lichess.org/analysis/standard/' + game.fen().replace(/ /g, '_')
    + '\nfm-chess:' + id + ':' + (white ? 'w' : 'b') + ':' + moves.join(',') + (resign ? ':resign' : '');
}

function chessBoard(game, flipped, last, onTap, selected, targets) {
  const board = element('div', '', 'chess-board');
  for (let row = 0; row < 8; row++) for (let column = 0; column < 8; column++) {
    const rank = flipped ? row : 7 - row, file = flipped ? 7 - column : column, index = rank * 8 + file;
    const cell = element(onTap ? 'button' : 'span', '', 'chess-square ' + ((rank + file) % 2 ? 'light' : 'dark')
      + (index === selected ? ' selected' : '') + (last && (index === last.from || index === last.to) ? ' last' : '') + (targets && targets.has(index) ? ' target' : ''));
    const piece = game.board[index];
    if (piece) cell.append(element('span', PIECES[Math.abs(piece)] + '\uFE0E', piece > 0 ? 'piece white' : 'piece black'));
    if (onTap) { cell.type = 'button'; cell.setAttribute('aria-label', FluidChess.name(index)); cell.addEventListener('click', () => onTap(index)); }
    board.append(cell);
  }
  return board;
}

function chessCard(chess, message, history) {
  const game = FluidChess.Game.replay(chess.moves);
  if (!game) return element('p', '♟ This chess move isn’t valid.');
  const outgoing = message.type !== 1;
  const mineWhite = outgoing ? chess.senderWhite : !chess.senderWhite;
  const latest = [...history].reverse().find((item) => parseChess(item.body)?.id === chess.id) === message;
  const last = chess.moves.length ? game.parse(chess.moves[chess.moves.length - 1]) : null;
  const card = element('div', '', 'chess-card');
  card.append(element('small', 'CHESS', 'card-kind'));
  const state = game.outcome();
  const yourTurn = latest && !chess.resigned && state === 'playing' && game.white === mineWhite;
  let status;
  if (chess.resigned) status = outgoing ? 'You resigned' : 'They resigned · you win';
  else if (state === 'checkmate') status = game.white !== mineWhite ? 'Checkmate · you win! 🎉' : 'Checkmate · they win';
  else if (state === 'stalemate') status = 'Stalemate · it’s a draw';
  else if (state === 'draw') status = 'Draw';
  else status = !latest ? 'Move ' + chess.moves.length : yourTurn ? (game.check() ? 'Check! Your move' : 'Your move') : 'Their move';
  const holder = element('div', '');
  holder.append(chessBoard(game, !mineWhite, last, null));
  card.append(holder, element('strong', status));
  if (yourTurn) {
    let selected = null, pending = null;
    const legal = game.legal();
    const controls = element('div', '', 'card-actions');
    const redraw = () => {
      const shown = pending ? (() => { const next = game.copy(); next.play(pending); return next; })() : game;
      const targets = selected === null ? null : new Set(legal.filter((move) => move.from === selected).map((move) => move.to));
      holder.replaceChildren(chessBoard(shown, !mineWhite, pending || last, pending ? null : (index) => {
        const piece = game.board[index];
        if (selected !== null && targets.has(index)) {
          const options = legal.filter((move) => move.from === selected && move.to === index);
          pending = options.find((move) => move.promotion === FluidChess.QUEEN) || options[0];
          selected = null;
        } else selected = piece && (piece > 0) === mineWhite ? index : null;
        redraw();
      }, selected, targets));
      controls.replaceChildren();
      if (pending) {
        const san = game.san(pending);
        const undo = element('button', 'Undo');
        undo.type = 'button';
        undo.addEventListener('click', () => { pending = null; redraw(); });
        const go = element('button', 'Send ' + san, 'primary');
        go.type = 'button';
        go.addEventListener('click', () => {
          const moves = chess.moves.concat(game.uci(pending));
          const after = FluidChess.Game.replay(moves);
          const result = after.outcome();
          const summary = result === 'checkmate' ? 'I played ' + san + '. Checkmate!' : result === 'stalemate' ? 'I played ' + san + '. Stalemate, it’s a draw.'
            : result === 'draw' ? 'I played ' + san + '. It’s a draw.' : 'I played ' + san + ' (move ' + Math.floor((moves.length + 1) / 2) + '). Your turn!';
          postToChat(chessText(chess.id, mineWhite, moves, summary)).catch(showError);
        });
        controls.append(undo, go);
      } else {
        const resign = element('button', 'Resign');
        resign.type = 'button';
        resign.addEventListener('click', () => postToChat(chessText(chess.id, mineWhite, chess.moves, 'I resign. Good game!', true)).catch(showError));
        controls.append(element('small', 'Click a piece, then where it goes.', 'muted'), resign);
      }
    };
    redraw();
    card.append(controls);
  } else if (latest && (chess.resigned || state !== 'playing')) {
    const again = element('button', 'Rematch');
    again.type = 'button';
    again.addEventListener('click', () => postToChat(chessText(newCardId(), !mineWhite, [], !mineWhite ? 'Let’s play! I’m white, so I go first.'
      : 'Let’s play! I’m black, so you go first.')).catch(showError));
    card.append(again);
  }
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
  if (localStorage.getItem('linkPreviews') === 'off') return;
  if (!previews.has(link)) previews.set(link, fetch('/api/preview?url=' + encodeURIComponent(link), {headers: {Authorization: 'Bearer ' + token}})
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
  const picture = localStorage.getItem('autoPictures') === 'off' ? null : pictureLink(body);
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
  if (event) { bubble.replaceChildren(eventCard(event)); return true; }
  const chess = parseChess(body);
  if (chess) { bubble.replaceChildren(chessCard(chess, message, history)); return true; }
  const link = previewableLink(body);
  if (link && !/^FM1:/.test(body)) linkPreview(link, bubble);
  return false;
}

/** Photos, GIFs, and stickers sent as files show as pictures you can open full screen. */
function attachmentPicture(message, bubble) {
  const file = message.attachment;
  if (!file || !/^image\/(png|jpe?g|gif|webp|heic|avif)$/i.test(file.mime || '')) return false;
  const sticker = (file.name || '').startsWith('sticker');
  bubble.prepend(pictureElement('/api/protocols/file?id=' + encodeURIComponent(message.id), file.name, sticker));
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
  if (parseChess(body)) return '♟️ ' + ((/Chess · (.*)/.exec(body) || [])[1] || 'Chess');
  return null;
}

// Composer: polls, events, chess, and GIFs

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
}

function openChess() {
  const sheet = dialog('Play chess');
  const box = element('div', '', 'settings-section');
  box.append(element('p', 'Take turns right in this chat. People on other apps get each move as text with a link to see the board.', 'muted'));
  for (const [label, white] of [['Play white', true], ['Play black', false]]) {
    const button = element('button', label, white ? 'primary' : '');
    button.type = 'button';
    button.addEventListener('click', () => {
      sheet.close();
      postToChat(chessText(newCardId(), white, [], white ? 'Let’s play! I’m white, so I go first.' : 'Let’s play! I’m black, so you go first.')).catch(showError);
    });
    box.append(button);
  }
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
      + encodeURIComponent(query.value.trim()) + '&key=' + encodeURIComponent(key.value.trim()), {headers: {Authorization: 'Bearer ' + token}});
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
  box.append(field('Search with', source), field('API key', key), field('Show', kind), query, grid, status);
  sheet.append(box);
  sheet.showModal();
  later();
}

(() => {
  const row = document.querySelector('.compose-row');
  if (!row) return;
  const more = element('button', '＋', 'icon-button');
  more.type = 'button';
  more.title = 'Polls, events, chess, and GIFs';
  more.setAttribute('aria-label', 'More: polls, events, chess, and GIFs');
  const menu = element('div', '', 'compose-menu');
  menu.hidden = true;
  for (const [label, run] of [['📊 Poll', openPoll], ['📅 Event', openEvent], ['♟️ Chess', openChess], ['GIF or sticker', openGifs]]) {
    const item = element('button', label);
    item.type = 'button';
    item.addEventListener('click', () => {
      menu.hidden = true;
      if (!active && !get('address').value.trim()) { showError(new Error('Choose a conversation first')); return; }
      run();
    });
    menu.append(item);
  }
  more.addEventListener('click', (event) => { event.stopPropagation(); menu.hidden = !menu.hidden; });
  document.addEventListener('click', () => { menu.hidden = true; });
  row.prepend(more);
  row.style.position = 'relative';
  row.append(menu);
})();
