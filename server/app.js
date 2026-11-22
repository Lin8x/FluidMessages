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
let token = '';
let session = 0;
let active = '';
let appearance = {};
let inbox = [];
let threads = new Map();
let updated = null;
let reply = '';
let refreshed = false;
let refreshNumber = 0;

async function api(path, body) {
  const response = await fetch('/api/' + path, {
    method: body === undefined ? 'GET' : 'POST',
    headers: {Authorization: 'Bearer ' + token, 'Content-Type': 'application/json'},
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const result = await response.json();
  if (!response.ok) throw new Error(result.error || 'Request failed');
  return result;
}

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
  for (const [key, name] of Object.entries(colors)) {
    const value = appearance.colors?.[key];
    if (typeof value === 'string' && /^#[a-f\d]{6}$/i.test(value)) root.style.setProperty('--' + name, value);
    else root.style.removeProperty('--' + name);
  }
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

function fold(history) {
  const shown = [];
  for (const message of history) {
    const own = message.body.endsWith(' (my message)');
    const wire = own ? message.body.slice(0, -13) : message.body;
    const known = verbs.find(([verb]) => wire.startsWith(verb + ' '));
    const custom = /^Reacted (\S{1,64}) to ([\s\S]+)$/.exec(wire);
    const emoji = known?.[1] || (custom && /[\p{Emoji_Presentation}\p{Extended_Pictographic}\u20E3]/u.test(custom[1]) ? custom[1] : '');
    const quote = known ? wire.slice(known[0].length + 1) : custom?.[2];
    const quoted = quote && (/^“([\s\S]+)”$/.exec(quote) || /^"([\s\S]+)"$/.exec(quote));
    if (emoji && quoted && ![4, 5, 6].includes(message.type)) {
      const matches = [...shown].reverse().filter((row) =>
        row.body === quoted[1] || excerpt(row.body, 700) === quoted[1]);
      const target = matches.find((row) => ((row.type === 1) === (message.type === 1)) === own)
        || (!own && matches[0]);
      if (target) {
        target.reactions.set(message.type === 1, emoji);
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
  return message?.title || threadAddress(key);
}
// Short codes and sender IDs are automated only for carrier texts; internet IDs often contain letters.
function automatedThread(key) { return !!key && family(threadProtocol(key)) === 'sms' && automated(threadAddress(key)); }
function internet(protocol) { return !['sms', 'mms', 'rcs', undefined, ''].includes(protocol); }

function rebuildThreads() {
  threads = new Map();
  for (const message of inbox) {
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
        const wire = verb + ' “' + excerpt(message.body, 700) + '”' + (message.type !== 1 ? ' (my message)' : '');
        send(wire, '', {kind: 'reaction', target: message.id || '', emoji, own: message.type !== 1}).catch(showError);
      });
    }
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
  for (const message of history) {
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
    else bubble.textContent = message.body;
    if (message.attachment) {
      if (message.body === message.attachment.name) bubble.textContent = '';
      const file = element('button', '📎 ' + message.attachment.name, 'file-card');
      file.type = 'button';
      file.addEventListener('click', () => download(message).catch(showError));
      bubble.prepend(file);
    }
    row.append(bubble);
    if (message.reactions.size) row.append(element('span', [...message.reactions.values()].join(' '), 'reaction'));
    const failed = message.type === 5 || (message.status || '').startsWith('Couldn’t send');
    const status = failed ? ' · Not delivered' : [4, 6].includes(message.type) ? ' · Sending…' : message.status ? ' · ' + message.status : '';
    if (failed) row.classList.add('failed');
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
  get('later').hidden = internet(protocol);
  get('body').value = drafts.get(address) || '';
  get('schedule-status').textContent = '';
  setReply('');
  showMessages();
  showThreads();
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
  const rows = [...threads].filter(([address, messages]) => {
    const blocked = selected.blocked?.includes(threadAddress(address));
    const archived = selected.archived?.includes(threadAddress(address));
    const inFolder = folder === 'Blocked' ? blocked : !blocked && (folder === 'Archived' ? archived : !archived);
    return inFolder && (matches(threadTitle(address), '') || messages.some((message) => matches(threadTitle(address), message.body)));
  });
  const pinned = new Set(selected.pinned || []);
  rows.sort(([a], [b]) => Number(pinned.has(threadAddress(b))) - Number(pinned.has(threadAddress(a))));
  for (const [key, messages] of rows) {
    const address = key;
    const message = messages.at(-1);
    const button = element('button', '', 'thread');
    button.type = 'button';
    button.setAttribute('aria-current', String(address === active));
    button.dataset.unread = String(message.type === 1 && message.read === 0);
    const copy = element('span', '', 'thread-copy');
    copy.append(element('strong', threadTitle(key)));
    if (appearance.protocolTags) {
      const tags = element('span', '', 'tags');
      const formats = appearance.combineProtocols ? [...new Set(messages.map((row) => row.protocol || 'sms'))] : [message.protocol || 'sms'];
      for (const format of formats) tags.append(element('span', automatedThread(key) && family(format) === 'sms' ? 'Automated' : labels[format] || 'SMS', 'tag p-' + format));
      copy.append(tags);
    }
    copy.append(element('small', message.body));
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
    row.append(element('strong', job.address), element('p', job.body),
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
  if (!refreshed || updated !== snapshot.updated) {
    applyAppearance(snapshot.appearance);
    if (Array.isArray(snapshot.appearance?.enabledProtocols)) enabled = new Set(snapshot.appearance.enabledProtocols);
    updated = snapshot.updated;
    inbox = snapshot.messages;
    rebuildThreads();
    showThreads();
    showMessages(true);
    refreshed = true;
  }
  get('status').textContent = updated ? 'Phone synced ' + new Date(updated).toLocaleTimeString() : 'Waiting for Android sync';
  showJobs(jobs);
}

function showError(error) { get('schedule-status').textContent = error.message; }

async function send(body, text, relation, due = Date.now()) {
  const protocol = threadProtocol(active);
  if (!internet(protocol)) return queueSms(body, threadAddress(active), due);
  const currentSession = session;
  const request = crypto.randomUUID ? crypto.randomUUID() : String(Date.now()) + Math.random().toString(16).slice(2);
  await api('protocols/send', {address: threadAddress(active), protocol, body, text: text || body, request,
    relation: relation && relation.target ? relation : undefined});
  if (currentSession !== session) return;
  get('schedule-status').textContent = 'Queued on your companion.';
  refresh().catch(() => { get('status').textContent = 'Sync unavailable'; });
}

async function download(message) {
  const response = await fetch('/api/protocols/file?id=' + encodeURIComponent(message.id), {headers: {Authorization: 'Bearer ' + token}});
  if (!response.ok) throw new Error('The attachment is no longer on the companion.');
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
  get('schedule-status').textContent = 'Queued for your phone.';
  refresh().catch(() => { get('status').textContent = 'Sync unavailable'; });
}

get('pair').addEventListener('submit', async (event) => {
  event.preventDefault();
  const currentSession = ++session;
  token = get('token').value.trim();
  try {
    await refresh();
    if (currentSession !== session) return;
    get('pair').reset();
    get('pair-error').textContent = '';
    get('pair').hidden = true;
    get('workspace').hidden = false;
    get('disconnect').hidden = false;
    get('open-queue').hidden = false;
  } catch (error) {
    if (currentSession !== session) return;
    token = '';
    get('pair-error').textContent = error.message;
  }
});
get('disconnect').addEventListener('click', () => {
  ++session;
  token = '';
  inbox = [];
  threads.clear();
  drafts.clear();
  updated = null;
  refreshed = false;
  active = '';
  setReply('');
  applyAppearance({});
  get('queue').close();
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
  get('status').textContent = 'Disconnected';
  get('workspace').hidden = true;
  get('disconnect').hidden = true;
  get('open-queue').hidden = true;
  get('pair').hidden = false;
});
get('folder').addEventListener('change', showThreads);
get('search').addEventListener('input', showThreads);
get('new').addEventListener('click', () => { select(''); get('address').focus(); });
get('back').addEventListener('click', () => { delete get('workspace').dataset.thread; });
get('open-queue').addEventListener('click', () => {
  get('queue-status').textContent = '';
  get('queue').showModal();
});
get('close-queue').addEventListener('click', () => get('queue').close());
get('cancel-reply').addEventListener('click', () => setReply(''));
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
    }
  } catch (error) {
    if (currentSession === session) showError(error);
  } finally {
    if (currentSession === session) get('send').disabled = false;
  }
});
setInterval(() => {
  if (token && !get('workspace').hidden) refresh().catch(() => {
    if (token) get('status').textContent = 'Sync unavailable';
  });
}, 5000);
