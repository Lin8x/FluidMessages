'use strict';
// People, like the phone's Phone tab (PhoneTab.kt): the Calls tab's Favorites, Recents, Contacts, and keypad, and a
// contact page for anyone, opened from those lists or from a group's members. Everyone known here comes from the
// phone's synced chats (names and numbers), internet chats and their routes, group members, and, in the sample
// inbox, its fictional address book. Calling a number asks the paired phone to dial it (main.py /api/call).
// Loaded last; uses app.js, companion.js, and chats.js.

// The sample people with portraits, as on the phone (MainActivity.samplePhoto). Real contacts never get these.
const SAMPLE_PHOTOS = {'Maya Chen': '/samples/maya.webp', 'Jordan Ellis': '/samples/jordan.webp'};

// The sample inbox's address book: the phone's samplePeople, with the numbers the web's sample chats use.
// Fictional names, 555-01xx numbers, and example.org addresses only.
const SAMPLE_BOOK = [
  ['Maya Chen', '+12025550123', [['matrix', '@mayachen:example.org'], ['xmpp', 'maya@example.org'], ['email', 'maya.chen@example.org']]],
  ['Jordan Ellis', '+12025550142'], ['Sam Rivera', '+12025550131'], ['Ana Ortiz', '+12025550155'], ['Leo Park', '+12025550166'],
  ['Avery Brooks', '+12025550104'], ['Nora Patel', '+12025550107'], ['Cameron Reed', '+12025550109'],
  ['Leah Morgan', '', [['email', 'leah.morgan@example.org']]], ['Amir Nasser', '', [['xmpp', 'amir@example.org']]],
  ['Mom', '+12025550111'], ['Elena Vasquez', '+12025550113', [['matrix', '@elenavasquez:example.org']]], ['Marcus Lee', '+12025550115'],
  ['Priya Raman', '+12025550117'], ['Tom Becker', '+12025550119'], ['Hannah Kim', '+12025550121'], ['Diego Alvarez', '+12025550125'],
  ['Grace Okafor', '+12025550127'], ['Ben Carter', '+12025550129'], ['Olivia Grant', '+12025550133'], ['Dr. Ellis Dental', '+12025550135'],
];
const SAMPLE_STARRED = ['+12025550123', '+12025550142', '+12025550111'];

let webFavorites = [];
let sampleStarred = null;
// Like the phone, the tab opens on Recents.
const phoneTab = {view: 'recents', keypad: false, dialed: '', query: '', missedOnly: false, note: null, jobs: []};

// Avatars: a photo where one is known, otherwise initials.

function photoFor(name) { return sampleOn && name ? SAMPLE_PHOTOS[name] || '' : ''; }

/** Puts [name]'s photo in an existing avatar, or leaves its initials. */
function paintAvatar(node, name) {
  const photo = photoFor(name);
  node.classList.toggle('has-photo', !!photo);
  if (!photo) {
    // Initials on the phone's gradient tints (app.js setAvatar).
    if (typeof setAvatar === 'function') return setAvatar(node, name);
    if (node.querySelector('img')) node.textContent = name ? initials(name) : '';
    return node;
  }
  delete node.dataset.tint;
  const image = element('img', '');
  image.src = photo;
  image.alt = '';
  image.decoding = 'async';
  node.replaceChildren(image);
  return node;
}

/** A round avatar for [name]; [extra] adds classes such as details-avatar. */
function avatarFor(name, extra = '') {
  const node = element('span', initials(name || ''), 'avatar' + (extra ? ' ' + extra : ''));
  node.setAttribute('aria-hidden', 'true');
  return paintAvatar(node, name);
}

// Everyone known here

const digitsOf = (value) => String(value || '').replace(/\D/g, '');
const isNumber = (value) => /^\+?[\d ().-]{5,40}$/.test(String(value || '').trim()) && digitsOf(value).length >= 5;
const sameText = (a, b) => !!a && !!b && a.toLowerCase() === b.toLowerCase();
function sameNumber(a, b) {
  const x = digitsOf(a), y = digitsOf(b);
  if (x.length < 5 || y.length < 5) return false;
  if (x === y) return true;
  // +1 202 555 0123 and (202) 555-0123 are the same phone.
  return x.length >= 10 && y.length >= 10 && x.slice(-10) === y.slice(-10);
}
const keyOf = (address, protocol) => threadKey({address, protocol});
/** US numbers read like the phone shows them, (202) 555-0123; anything else stays as it was saved. */
function displayNumber(value) {
  const text = String(value || '').trim();
  const digits = digitsOf(text);
  if (!/^\+?[\d ().-]+$/.test(text) || !(digits.length === 10 && !text.startsWith('+') || digits.length === 11 && digits[0] === '1')) return text;
  const local = digits.slice(-10);
  return '(' + local.slice(0, 3) + ') ' + local.slice(3, 6) + '-' + local.slice(6);
}

/**
 * Everyone you can reach: {name, numbers, accounts: [{protocol, target, address}], chats: [one-to-one chat keys],
 * groups: [group chat keys]}. One person per phone number, internet address, or name.
 */
function knownPeople() {
  const people = [];
  const find = (name, number, handle) => people.find((person) =>
    (number && person.numbers.some((known) => sameNumber(known, number)))
    || (handle && person.accounts.some((account) => sameText(account.target, handle) || sameText(account.address, handle)))
    || (name && !looksLikeHandle(name) && person.named && sameText(person.name, name)));
  const add = ({name = '', number = '', account = null, chat = '', group = ''}) => {
    name = String(name || '').trim();
    if (!name && !number && !account) return null;
    let person = find(name, number, account?.target);
    if (!person) {
      person = {name: name || number || account.target, named: !!name && !looksLikeHandle(name), numbers: [], accounts: [], chats: [], groups: []};
      people.push(person);
    } else if (!person.named && name && !looksLikeHandle(name)) {
      person.name = name;
      person.named = true;
    }
    if (number && !person.numbers.some((known) => sameNumber(known, number))) person.numbers.push(number);
    if (account && !person.accounts.some((known) => known.protocol === account.protocol && sameText(known.target, account.target))) {
      person.accounts.push(account);
    } else if (account?.address) {
      const known = person.accounts.find((item) => item.protocol === account.protocol && sameText(item.target, account.target));
      if (known && !known.address) known.address = account.address;
    }
    if (chat && !person.chats.includes(chat)) person.chats.push(chat);
    if (group && !person.groups.includes(group)) person.groups.push(group);
    return person;
  };
  if (sampleOn) {
    for (const [name, number, accounts = []] of SAMPLE_BOOK) {
      add({name, number});
      for (const [protocol, target] of accounts) add({name, account: {protocol, target, address: ''}});
    }
  }
  const linked = typeof routes === 'undefined' ? [] : routes;
  // One-to-one chats: the phone's texts with the names it synced, and internet chats.
  for (const key of threads.keys()) {
    if (threadIsGroup(key) || automatedThread(key)) continue;
    const address = threadAddress(key);
    const protocol = threadProtocol(key);
    const named = (threads.get(key) || []).some((row) => row.title);
    const route = routeFor(key);
    if (!internet(protocol) && isNumber(address)) {
      // An unnamed number is a stranger, not a contact; it still gets a page from its chat or Recents.
      if (named) add({name: threadTitle(key), number: address, chat: key});
    } else if (route || internet(protocol)) {
      add({name: threadTitle(key), account: {protocol: route?.protocol || protocol, target: route?.target || address, address}, chat: key});
    }
  }
  for (const route of linked) {
    if (route.group || !internet(route.protocol)) continue;
    add({name: route.title, account: {protocol: route.protocol, target: route.target, address: route.address}, chat: keyOf(route.address, route.protocol)});
  }
  // Group members.
  for (const key of threads.keys()) {
    if (!threadIsGroup(key)) continue;
    for (const member of threadMembers(key)) {
      const number = isNumber(member.address) ? member.address : '';
      const handle = !number && member.address ? member.address : '';
      add({name: member.name, number, account: handle ? {protocol: internet(member.protocol) ? member.protocol : 'matrix', target: handle, address: ''} : null,
        group: key});
    }
  }
  // Someone found by a number and also by name joins their groups and chats.
  for (const person of people) {
    for (const key of threads.keys()) {
      if (person.chats.includes(key) || threadIsGroup(key)) continue;
      const address = threadAddress(key);
      if (person.numbers.some((number) => sameNumber(number, address))) person.chats.push(key);
    }
  }
  return people.sort((a, b) => a.name.localeCompare(b.name, undefined, {sensitivity: 'base'}));
}

/** The person behind a one-to-one chat. */
function personForChat(key) {
  const found = knownPeople().find((person) => person.chats.includes(key));
  if (found) return found;
  const address = threadAddress(key);
  const route = routeFor(key);
  const protocol = threadProtocol(key);
  return {name: threadTitle(key), named: true, numbers: !internet(protocol) && isNumber(address) ? [address] : [],
    accounts: internet(protocol) ? [{protocol: route?.protocol || protocol, target: route?.target || address, address}] : [], chats: [key], groups: []};
}

/** The person behind a group member, or a call in Recents. */
function personFor({name = '', address = '', protocol = ''}) {
  const people = knownPeople();
  const number = isNumber(address) ? address : '';
  const found = people.find((person) => (number && person.numbers.some((known) => sameNumber(known, number)))
    || (address && !number && person.accounts.some((account) => sameText(account.target, address) || sameText(account.address, address))))
    || people.find((person) => name && !looksLikeHandle(name) && sameText(person.name, name));
  if (found) return found;
  return {name: name || (number ? displayNumber(number) : address), named: !!name, numbers: number ? [number] : [], chats: [], groups: [],
    accounts: address && !number ? [{protocol: internet(protocol) ? protocol : 'matrix', target: address, address: ''}] : []};
}

function personTags(person) {
  const tags = person.numbers.length ? ['SMS'] : [];
  for (const account of person.accounts) {
    const label = labels[account.protocol] || account.protocol;
    if (!tags.includes(label)) tags.push(label);
  }
  return tags;
}

function tagRow(tags) {
  const row = element('span', '', 'person-tags');
  for (const tag of tags) row.append(element('span', tag, 'person-tag'));
  return row;
}

// Favorites: kept on the server for every browser (main.py web_favorites), by number or chat address.

function starredList() {
  if (!sampleOn) { sampleStarred = null; return webFavorites; }
  if (!sampleStarred) sampleStarred = [...SAMPLE_STARRED];
  return sampleStarred;
}

function personAddresses(person) {
  return [...person.numbers, ...person.accounts.flatMap((account) => [account.target, account.address]).filter(Boolean)];
}

function isFavorite(person) {
  const own = personAddresses(person);
  return starredList().some((item) => own.some((address) => sameText(item, address) || sameNumber(item, address)));
}

function toggleFavorite(person) {
  const own = personAddresses(person);
  const current = starredList();
  const next = isFavorite(person) ? current.filter((item) => !own.some((address) => sameText(item, address) || sameNumber(item, address)))
    : [...current, own[0] || person.name];
  if (sampleOn) sampleStarred = next;
  else {
    webFavorites = next;
    api('settings', {web_favorites: next}).catch(showError);
  }
  return isFavorite(person);
}

function starButton(person, after) {
  const button = element('button', '', 'star-button');
  button.type = 'button';
  const draw = () => {
    const on = isFavorite(person);
    button.replaceChildren(phoneIcon(on ? 'star' : 'starOutline'));
    button.setAttribute('aria-pressed', String(on));
    button.setAttribute('aria-label', (on ? 'Remove ' : 'Add ') + person.name + (on ? ' from favorites' : ' to favorites'));
    button.title = on ? 'Remove from favorites' : 'Add to favorites';
  };
  button.addEventListener('click', (event) => {
    event.stopPropagation();
    toggleFavorite(person);
    draw();
    if (after) after();
  });
  draw();
  return button;
}

/** The snapshot's favorites, and a fresh Calls tab when it's open (app.js refresh). */
function noticePeople(snapshot) {
  webFavorites = Array.isArray(snapshot.web_favorites) ? snapshot.web_favorites : [];
  const list = get('calls-list');
  if (typeof section !== 'undefined' && section === 'calls' && list && !list.contains(document.activeElement)) showPhoneTab(list);
}

// Icons, in the same 24-unit style as the chat header's.

const PHONE_ICONS = {
  star: 'M12 17.27 18.18 21l-1.64-7.03L22 9.24l-7.19-.61L12 2 9.19 8.63 2 9.24l5.46 4.73L5.82 21z',
  backspace: 'M22 3H7c-.69 0-1.23.35-1.59.88L0 12l5.41 8.11c.36.53.9.89 1.59.89h15c1.1 0 2-.9 2-2V5c0-1.1-.9-2-2-2zm-3 12.59L17.59 17 14 13.41 10.41 17 9 15.59 12.59 12 9 8.41 10.41 7 14 10.59 17.59 7 19 8.41 15.41 12 19 15.59z',
};

function phoneIcon(name) {
  const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
  svg.setAttribute('viewBox', '0 0 24 24');
  svg.setAttribute('aria-hidden', 'true');
  if (name === 'keypad') {
    for (let row = 0; row < 4; row++) for (let column = 0; column < 3; column++) {
      if (row === 3 && column !== 1) continue;
      const dot = document.createElementNS('http://www.w3.org/2000/svg', 'circle');
      dot.setAttribute('cx', String(5.5 + column * 6.5));
      dot.setAttribute('cy', String(3.5 + row * 5.7));
      dot.setAttribute('r', '1.9');
      dot.setAttribute('fill', 'currentColor');
      svg.append(dot);
    }
    return svg;
  }
  const path = document.createElementNS('http://www.w3.org/2000/svg', 'path');
  path.setAttribute('d', PHONE_ICONS[name === 'starOutline' ? 'star' : name] || HEAD_ICONS[name]);
  if (name === 'starOutline') {
    path.setAttribute('fill', 'none');
    path.setAttribute('stroke', 'currentColor');
    path.setAttribute('stroke-width', '1.6');
    path.setAttribute('stroke-linejoin', 'round');
  } else path.setAttribute('fill', 'currentColor');
  svg.append(path);
  return svg;
}

function roundButton(icon, label, className, run) {
  const button = element('button', '', className);
  button.type = 'button';
  button.title = label;
  button.setAttribute('aria-label', label);
  button.append(phoneIcon(icon));
  button.addEventListener('click', (event) => { event.stopPropagation(); run(); });
  return button;
}

// Calling and messaging someone

function openChat(key) {
  closeDetailsPage(false);
  showSection('messages');
  select(key);
  get('body')?.focus();
}

/** Asks the paired phone to dial [number] (it shows "Tap to call"). [report] shows what happened. */
async function callFromPhone(number, report) {
  number = String(number || '').trim();
  if (sampleOn) { report('Sample inbox · nothing was sent. With a real chat, your phone shows “Tap to call” for ' + displayNumber(number) + '.'); return; }
  if (!phonePaired()) { report(null, number); return; }
  if (/[*#]/.test(number)) { report('Codes with * or # dial from the phone itself.', '', true); return; }
  try {
    await api('call', {address: number});
    report('Sent to your phone. Tap the notification there to call ' + displayNumber(number) + '.');
  } catch (error) { report(error.message, '', true); }
}

/** Calls someone's Matrix account from this browser, in their chat. */
function callOnMatrix(account, video) {
  if (sampleOn) { say('Sample chat · nothing was sent.'); return; }
  const key = keyOf(account.address, 'matrix');
  openChat(key);
  startCall(video).catch(showError);
}

/** What happens when a phone number can't be called because no phone is paired: why, and other ways to reach them. */
function noPhoneNote(number, person) {
  const box = element('div', '', 'phone-notice');
  box.setAttribute('role', 'status');
  box.append(element('strong', 'Pair your phone to call'),
    element('p', 'Calls to phone numbers are placed by FluidMessages for Android, and no phone is paired with this server yet. '
      + 'Pair it in Settings → Phone and other devices, or reach them on the internet instead.', 'muted'));
  const row = element('div', '', 'button-row wrap');
  const button = (label, run, primary) => {
    const node = element('button', label, primary ? 'primary' : '');
    node.type = 'button';
    node.addEventListener('click', run);
    row.append(node);
  };
  const chats = (person?.chats || []).filter((key) => internet(threadProtocol(key)));
  for (const key of chats) button('Text instead on ' + (labels[threadProtocol(key)] || 'the internet'), () => openChat(key), true);
  if (!chats.length && chatAccounts.length) button('Text instead', () => openNewChat(), true);
  if (!chatAccounts.length) button('Add a chat account', () => openSettings('settings-accounts').catch(showError));
  button('Pair a phone', () => openSettings('settings-devices').catch(showError));
  if (number && senderAddress(number)) button('Text ' + displayNumber(number) + ' later', () => openChat(keyOf(number, 'sms')));
  box.append(row);
  return box;
}

// The Calls tab

function showPhoneTab(list) {
  if (!list) return;
  markCallsSeen();
  const scroll = list.scrollTop;
  const tab = element('div', '', 'phone-tab');
  // Favorites, Recents, and Contacts, as a segmented control; the keypad opens from its round button.
  const segments = element('div', '', 'phone-segments');
  segments.setAttribute('role', 'tablist');
  segments.setAttribute('aria-label', 'Calls');
  for (const [view, label] of [['favorites', 'Favorites'], ['recents', 'Recents'], ['contacts', 'Contacts']]) {
    const button = element('button', label, 'phone-segment');
    button.type = 'button';
    button.setAttribute('role', 'tab');
    const on = !phoneTab.keypad && phoneTab.view === view;
    button.setAttribute('aria-selected', String(on));
    button.addEventListener('click', () => { phoneTab.view = view; phoneTab.keypad = false; phoneTab.note = null; showPhoneTab(list); });
    segments.append(button);
  }
  tab.append(segments);
  if (sampleOn) tab.append(element('p', 'Sample calls and contacts · nothing here is real', 'phone-sample-note'));
  const body = element('div', '', 'phone-body');
  if (phoneTab.keypad) keypadView(body, list);
  else if (phoneTab.view === 'favorites') favoritesView(body, list);
  else if (phoneTab.view === 'contacts') contactsView(body, list);
  else recentsView(body, list);
  tab.append(body);
  if (!phoneTab.keypad) {
    tab.append(roundButton('keypad', 'Keypad', 'keypad-fab', () => { phoneTab.keypad = true; phoneTab.note = null; showPhoneTab(list); }));
  }
  list.replaceChildren(tab);
  list.scrollTop = phoneTab.keypad ? 0 : scroll;
  if (phoneTab.keypad) list.querySelector('.dial-display')?.focus({preventScroll: true});
}

function largeTitle(text, extra) {
  const head = element('div', '', 'phone-title');
  head.append(element('h2', text));
  if (extra) head.append(extra);
  return head;
}

function personRow(person, detail, action) {
  const row = element('div', '', 'person-row');
  const open = element('button', '', 'person-open');
  open.type = 'button';
  const words = element('span', '', 'person-words');
  words.append(element('strong', person.name));
  if (detail) words.append(element('small', detail));
  const tags = personTags(person);
  if (tags.length) words.append(tagRow(tags));
  open.append(avatarFor(person.name), words);
  open.setAttribute('aria-label', person.name + (detail ? ', ' + detail : '') + (tags.length ? ', ' + tags.join(', ') : '') + '. Open contact');
  open.addEventListener('click', () => openPersonPage(person).catch(showError));
  row.append(open);
  if (action) row.append(action);
  return row;
}

function favoritesView(body, list) {
  body.append(largeTitle('Favorites'));
  const starred = knownPeople().filter(isFavorite);
  if (!starred.length) body.append(element('p', 'Tap the star next to someone in Contacts, or on their contact page, to add them here.', 'placeholder'));
  for (const person of starred) {
    const number = person.numbers[0];
    const matrix = person.accounts.find((account) => account.protocol === 'matrix' && account.address);
    let action = null;
    if (number) action = roundButton('voice', 'Call ' + person.name, 'quick-call', () => callFromPhone(number, tabReport(list, person)));
    else if (matrix) action = roundButton('voice', 'Call ' + person.name + ' on Matrix', 'quick-call', () => callOnMatrix(matrix, false));
    body.append(personRow(person, (number && displayNumber(number)) || person.accounts[0]?.target || '', action));
  }
  noteInto(body);
}

/** Shows what a call did, under the list, or why it couldn't call. */
function tabReport(list, person) {
  return (text, number, error) => {
    phoneTab.note = text === null ? {number, person} : {text, error};
    showPhoneTab(list);
  };
}

function noteInto(body) {
  const note = phoneTab.note;
  if (!note) return;
  if (note.number !== undefined && note.text === undefined) body.append(noPhoneNote(note.number, note.person));
  else {
    const line = element('p', note.text, 'phone-status' + (note.error ? ' error' : ''));
    line.setAttribute('role', note.error ? 'alert' : 'status');
    body.append(line);
  }
}

const SAMPLE_RECENTS = [
  ['Maya Chen', 'Phone', 'Incoming', 25, 252], ['Jordan Ellis', 'Phone', 'Missed', 120, 0], ['Mom', 'Phone', 'Incoming', 180, 1310],
  ['Elena Vasquez', 'Matrix', 'Incoming', 300, 846, true], ['Dr. Ellis Dental', 'Phone', 'Missed', 1320, 0],
  ['Maya Chen', 'Matrix', 'Outgoing', 1560, 1118, true], ['Sam Rivera', 'Phone', 'Outgoing', 1800, 95], ['Maya Chen', 'Matrix', 'Missed', 3000, 0],
  ['Nora Patel', 'Phone', 'Declined', 4320, 0], ['Avery Brooks', 'Phone', 'Incoming', 5760, 431], ['Cameron Reed', 'Phone', 'Outgoing', 12960, 64],
  ['Marcus Lee', 'Phone', 'Outgoing', 15840, 318],
];

/** Calls: internet calls noted in chats, calls this web app asked the phone to place, and in the sample inbox, fictional calls. */
function recentCalls() {
  if (sampleOn) {
    const now = Date.now();
    return SAMPLE_RECENTS.map(([name, protocol, kind, minutes, seconds, video = false]) => {
      const entry = SAMPLE_BOOK.find(([known]) => known === name) || [];
      const matrix = (entry[2] || []).find(([via]) => via === 'matrix');
      return {name, protocol, kind, video, seconds, date: now - minutes * 60000,
        address: protocol === 'Phone' ? entry[1] : matrix?.[1] || ''};
    });
  }
  const calls = inbox.filter((message) => message.event && /^(Voice|Video) call/.test(message.body || '')).slice(0, 60).map((message) => ({
    name: message.title || threadAddress(threadKey(message)), protocol: labels[message.protocol] || 'Matrix', key: threadKey(message),
    kind: /missed/.test(message.body) ? 'Missed' : message.type === 1 ? 'Incoming' : 'Outgoing', video: /^Video/.test(message.body),
    seconds: 0, date: timeOf(message), address: routeFor(threadKey(message))?.target || threadAddress(threadKey(message))}));
  for (const job of phoneTab.jobs) {
    if (job.protocol !== 'call') continue;
    const person = personFor({address: job.address});
    calls.push({name: person.name, protocol: 'Phone', kind: 'Outgoing', detail: job.state === 'scheduled' ? 'Waiting for your phone' : 'Sent to your phone',
      video: false, seconds: 0, date: job.due, address: job.address});
  }
  return calls.sort((a, b) => b.date - a.date);
}

function callWhen(date) {
  const time = new Date(date);
  if (time.toDateString() === new Date().toDateString()) return clock(date);
  if (Date.now() - date < 6 * 86400000) return time.toLocaleDateString([], {weekday: 'long'});
  return time.toLocaleDateString([], {year: '2-digit', month: 'numeric', day: 'numeric'});
}

function callKind(call) {
  const kind = call.video && ['Incoming', 'Outgoing'].includes(call.kind) ? call.kind + ' video' : call.kind;
  const length = call.seconds > 0 ? Math.floor(call.seconds / 60) + ':' + String(call.seconds % 60).padStart(2, '0') : '';
  return [call.detail || kind, length].filter(Boolean).join(' · ');
}

function recentsView(body, list) {
  const filter = element('div', '', 'phone-filter');
  filter.setAttribute('role', 'group');
  filter.setAttribute('aria-label', 'Show');
  for (const [label, missed] of [['All', false], ['Missed', true]]) {
    const button = element('button', label);
    button.type = 'button';
    button.setAttribute('aria-pressed', String(phoneTab.missedOnly === missed));
    button.addEventListener('click', () => { phoneTab.missedOnly = missed; showPhoneTab(list); });
    filter.append(button);
  }
  body.append(largeTitle('Recents', filter));
  const calls = recentCalls().filter((call) => !phoneTab.missedOnly || ['Missed', 'Declined'].includes(call.kind));
  if (!calls.length) {
    body.append(element('p', phoneTab.missedOnly ? 'No missed calls' : 'No recent calls. Matrix calls, and calls you place through your phone from here, show up here.', 'placeholder'));
  }
  for (const call of calls) {
    const missed = ['Missed', 'Declined'].includes(call.kind);
    const person = personFor({name: call.name, address: call.address, protocol: (call.protocol || '').toLowerCase()});
    const row = element('div', '', 'person-row recent-row' + (missed ? ' missed' : ''));
    const back = element('button', '', 'person-open');
    back.type = 'button';
    const words = element('span', '', 'person-words');
    const line = element('span', '', 'recent-line');
    line.append(element('span', call.protocol, 'person-tag'), element('small', callKind(call)));
    words.append(element('strong', call.name), line);
    back.append(avatarFor(call.name), words, element('time', callWhen(call.date), 'recent-when'));
    back.setAttribute('aria-label', 'Call ' + call.name + ' back, ' + call.protocol + ', ' + callKind(call) + ', ' + callWhen(call.date));
    back.addEventListener('click', () => {
      if (call.protocol === 'Phone') callFromPhone(call.address, tabReport(list, person));
      else {
        const matrix = person.accounts.find((account) => account.protocol === 'matrix' && account.address);
        if (matrix) callOnMatrix(matrix, call.video);
        else if (sampleOn) { phoneTab.note = {text: 'Sample inbox · nothing was sent.'}; showPhoneTab(list); }
        else if (call.key) openChat(call.key);
      }
    });
    row.append(back, roundButton('details', 'About ' + call.name, 'info-button', () => openPersonPage(person).catch(showError)));
    body.append(row);
  }
  noteInto(body);
  // Calls this web app asked the phone to place (the Scheduled list keeps them too).
  if (!sampleOn) {
    api('jobs').then((jobs) => {
      const signature = JSON.stringify(jobs.filter((job) => job.protocol === 'call'));
      if (signature === JSON.stringify(phoneTab.jobs.filter((job) => job.protocol === 'call'))) return;
      phoneTab.jobs = jobs;
      if (section === 'calls' && !phoneTab.keypad && phoneTab.view === 'recents' && list.isConnected) showPhoneTab(list);
    }).catch(() => {});
  }
}

function contactsView(body, list) {
  body.append(largeTitle('Contacts'));
  const search = Object.assign(element('input', ''), {type: 'search', placeholder: 'Search', value: phoneTab.query, autocomplete: 'off'});
  search.className = 'phone-search';
  search.setAttribute('aria-label', 'Search contacts');
  const results = element('div', '', 'contact-results');
  const draw = () => {
    results.replaceChildren();
    const words = phoneTab.query.trim().toLowerCase();
    const digits = digitsOf(words);
    const people = knownPeople().filter((person) => !words || person.name.toLowerCase().includes(words)
      || (digits.length >= 3 && person.numbers.some((number) => digitsOf(number).includes(digits)))
      || person.accounts.some((account) => account.target.toLowerCase().includes(words)));
    if (!people.length) {
      results.append(element('p', words ? 'No matches for “' + excerpt(phoneTab.query.trim(), 40) + '”'
        : 'No contacts yet. People you text, chat with, and meet in groups show up here.', 'placeholder'));
      return;
    }
    // A–Z sections, with # for names that don't start with a letter.
    let letter = '';
    let group = null;
    for (const person of people) {
      const first = [...person.name.normalize('NFD')].find((char) => /\p{L}|\p{N}/u.test(char)) || '#';
      const head = /\p{L}/u.test(first) ? first.toUpperCase() : '#';
      if (head !== letter) {
        letter = head;
        group = element('section', '', 'contact-letter');
        group.setAttribute('aria-label', head);
        group.append(element('h3', head));
        results.append(group);
      }
      group.append(personRow(person, displayNumber(person.numbers[0]) || person.accounts[0]?.target || '', starButton(person)));
    }
    results.append(element('p', people.length + (people.length === 1 ? ' contact' : ' contacts'), 'contact-count'));
  };
  search.addEventListener('input', () => { phoneTab.query = search.value; draw(); });
  body.append(search, results);
  draw();
}

const KEY_LETTERS = {2: 'ABC', 3: 'DEF', 4: 'GHI', 5: 'JKL', 6: 'MNO', 7: 'PQRS', 8: 'TUV', 9: 'WXYZ', 0: '+'};

function keypadView(body, list) {
  const pad = element('div', '', 'dialpad');
  const display = element('output', phoneTab.dialed, 'dial-display');
  display.tabIndex = 0;
  display.setAttribute('aria-label', phoneTab.dialed ? 'Number: ' + phoneTab.dialed : 'Number. Type digits or use the keys.');
  const match = element('button', '', 'dial-match');
  match.type = 'button';
  const status = element('div', '', 'dial-status');
  const people = knownPeople();
  const matched = () => (digitsOf(phoneTab.dialed).length >= 4 ? people.find((person) => person.numbers.some((number) => sameNumber(number, phoneTab.dialed))) : null);
  const update = () => {
    display.textContent = phoneTab.dialed;
    display.classList.toggle('long', phoneTab.dialed.length > 13);
    display.setAttribute('aria-label', phoneTab.dialed ? 'Number: ' + phoneTab.dialed : 'Number. Type digits or use the keys.');
    const person = matched();
    match.textContent = person ? person.name : '';
    match.hidden = !person;
    erase.hidden = !phoneTab.dialed;
    call.disabled = !phoneTab.dialed;
  };
  match.addEventListener('click', () => { const person = matched(); if (person) openPersonPage(person).catch(showError); });
  const type = (value) => {
    if (phoneTab.dialed.length >= 32) return;
    phoneTab.dialed += value;
    status.replaceChildren();
    update();
  };
  const keys = element('div', '', 'dial-keys');
  for (const digit of ['1', '2', '3', '4', '5', '6', '7', '8', '9', '*', '0', '#']) {
    const key = element('button', '', 'dial-key');
    key.type = 'button';
    key.append(element('span', digit, 'dial-digit'));
    if (KEY_LETTERS[digit]) key.append(element('span', KEY_LETTERS[digit], 'dial-letters'));
    key.setAttribute('aria-label', digit + (KEY_LETTERS[digit] ? ' ' + KEY_LETTERS[digit] : ''));
    key.addEventListener('click', () => type(digit));
    // Holding 0 types +, as on a phone.
    if (digit === '0') {
      let timer = 0;
      key.addEventListener('pointerdown', () => { timer = setTimeout(() => { timer = -1; type('+'); }, 550); });
      key.addEventListener('pointerup', () => clearTimeout(timer));
      key.addEventListener('pointerleave', () => clearTimeout(timer));
      key.addEventListener('click', (event) => { if (timer === -1) { event.stopImmediatePropagation(); timer = 0; } }, true);
    }
    keys.append(key);
  }
  const actions = element('div', '', 'dial-actions');
  const report = (text, number, error) => {
    if (text === null) { status.replaceChildren(noPhoneNote(number, matched())); return; }
    const line = element('p', text, 'phone-status' + (error ? ' error' : ''));
    line.setAttribute('role', error ? 'alert' : 'status');
    status.replaceChildren(line);
  };
  const call = roundButton('voice', 'Call', 'dial-call', () => { if (phoneTab.dialed) callFromPhone(phoneTab.dialed, report); });
  const erase = roundButton('backspace', 'Delete', 'dial-erase', () => { phoneTab.dialed = phoneTab.dialed.slice(0, -1); status.replaceChildren(); update(); });
  // Holding Delete clears the number.
  let held = 0;
  erase.addEventListener('pointerdown', () => { held = setTimeout(() => { phoneTab.dialed = ''; update(); }, 600); });
  erase.addEventListener('pointerup', () => clearTimeout(held));
  erase.addEventListener('pointerleave', () => clearTimeout(held));
  actions.append(element('span', '', 'dial-spacer'), call, erase);
  const close = element('button', 'Hide keypad', 'quiet dial-close');
  close.type = 'button';
  close.addEventListener('click', () => { phoneTab.keypad = false; showPhoneTab(list); });
  pad.append(display, match, keys, actions, status, element('p', 'Type a number on your keyboard too. Enter calls, Esc closes.', 'dial-hint'), close);
  pad.typeKey = type;
  pad.callNow = () => call.click();
  pad.eraseKey = () => erase.click();
  body.append(pad);
  update();
}

// Typing on the keyboard while the keypad is open.
document.addEventListener('keydown', (event) => {
  if (typeof section === 'undefined' || section !== 'calls' || !phoneTab.keypad || event.defaultPrevented) return;
  if (event.ctrlKey || event.metaKey || event.altKey || document.querySelector('dialog[open]')) return;
  if (event.target instanceof HTMLElement && event.target.closest('input, textarea, select, [contenteditable="true"], .details-page')) return;
  const pad = document.querySelector('#calls-list .dialpad');
  if (!pad) return;
  if (/^[0-9*#+]$/.test(event.key)) { event.preventDefault(); pad.typeKey(event.key); }
  else if (event.key === 'Backspace') { event.preventDefault(); pad.eraseKey(); }
  else if (event.key === 'Enter' && !(event.target instanceof HTMLButtonElement)) { event.preventDefault(); pad.callNow(); }
  else if (event.key === 'Escape') { event.preventDefault(); phoneTab.keypad = false; showPhoneTab(get('calls-list')); }
});
document.addEventListener('paste', (event) => {
  if (typeof section === 'undefined' || section !== 'calls' || !phoneTab.keypad) return;
  if (event.target instanceof HTMLElement && event.target.closest('input, textarea, [contenteditable="true"]')) return;
  const text = (event.clipboardData?.getData('text') || '').replace(/[^0-9*#+]/g, '').slice(0, 32);
  if (!text) return;
  event.preventDefault();
  for (const char of text) document.querySelector('#calls-list .dialpad')?.typeKey(char);
});

// A contact page: everything about one person, like the phone's (PhoneTab.kt PersonPage).

/** A group member's contact page (chats.js memberList), with [back] returning to the group. */
async function openMemberContact(member, back) {
  return openPersonPage(personFor(member), {back});
}

/** "Contact page" in a one-to-one chat's details (companion.js openDetails). */
function contactPageLink(key, back) {
  const row = element('button', '', 'member-row contact-link');
  row.type = 'button';
  const words = element('span', '', 'detail-words');
  words.append(element('strong', 'Contact page'), element('small', 'Every address, favorites, groups in common, and calls'));
  row.append(words, element('span', '›', 'chevron'));
  row.addEventListener('click', () => openPersonPage(personForChat(key), {back: () => { select(key); openDetails({back}).catch(showError); }}).catch(showError));
  return row;
}

/** One tile per way to reach someone; with more than one choice, it opens a list to pick from (like ReachTiles). */
function personTiles(options) {
  const wrap = element('div', '', 'person-reach');
  const row = element('div', '', 'reach-tiles');
  const choices = element('div', '', 'reach-choices');
  choices.hidden = true;
  for (const [icon, label] of [['message', 'Message'], ['voice', 'Call'], ['video', 'Video']]) {
    const mine = options.filter((option) => option.kind === icon);
    if (!mine.length) continue;
    const detail = mine.length === 1 ? mine[0].via : mine.length + ' ways';
    const tile = headButton(icon, label + ' · ' + detail);
    tile.className = 'reach-tile';
    const words = element('span', '', 'reach-words');
    words.append(element('strong', label), element('small', detail));
    tile.append(words);
    tile.setAttribute('aria-expanded', 'false');
    tile.addEventListener('click', () => {
      if (mine.length === 1) { mine[0].run(); return; }
      const open = choices.dataset.kind === icon && !choices.hidden;
      for (const other of row.children) other.setAttribute('aria-expanded', 'false');
      choices.hidden = open;
      choices.dataset.kind = icon;
      choices.replaceChildren();
      if (open) return;
      tile.setAttribute('aria-expanded', 'true');
      for (const option of mine) {
        const choice = element('button', '', 'member-row');
        choice.type = 'button';
        const text = element('span', '', 'detail-words');
        text.append(element('strong', option.via), element('small', option.target));
        choice.append(text, element('span', '›', 'chevron'));
        choice.addEventListener('click', option.run);
        choices.append(choice);
      }
    });
    row.append(tile);
  }
  if (!row.children.length) return null;
  wrap.append(row, choices);
  return wrap;
}

async function openPersonPage(person, {back} = {}) {
  const settings = sampleOn ? null : companionSettings || await loadSettings().catch(() => null);
  const status = element('div', '', 'phone-status');
  status.setAttribute('role', 'status');
  const report = (text, number, error) => {
    if (text === null) { status.replaceChildren(noPhoneNote(number, person)); return; }
    status.textContent = text;
    status.classList.toggle('error', !!error);
  };
  const chats = person.chats.filter((key) => threads.has(key) || routes.some((route) => keyOf(route.address, route.protocol) === key));
  const accountKey = (account) => account.address ? keyOf(account.address, account.protocol) : '';
  // The tiles: Message on each chat or number, Call through the phone or on Matrix, Video on Matrix or by meeting link.
  const options = [];
  const via = (key) => (protocolName(threadProtocol(key)));
  for (const key of chats) options.push({kind: 'message', via: via(key), target: routeFor(key)?.target || threadAddress(key), run: () => openChat(key)});
  for (const number of person.numbers) {
    if (!chats.some((key) => !internet(threadProtocol(key)) && sameNumber(threadAddress(key), number))) {
      options.push({kind: 'message', via: 'SMS', target: displayNumber(number), run: () => openChat(keyOf(number, 'sms'))});
    }
  }
  if (phonePaired() || sampleOn) {
    for (const number of person.numbers) options.push({kind: 'voice', via: 'From your phone', target: displayNumber(number), run: () => callFromPhone(number, report)});
  }
  for (const account of person.accounts) {
    if (account.protocol !== 'matrix' || !(account.address || sampleOn)) continue;
    const run = (video) => () => (sampleOn ? report('Sample inbox · nothing was sent.') : callOnMatrix(account, video));
    options.push({kind: 'voice', via: 'Matrix', target: account.target, run: run(false)}, {kind: 'video', via: 'Matrix', target: account.target, run: run(true)});
  }
  if (!options.some((option) => option.kind === 'video') && chats.length) {
    // Anyone else gets a meeting link in their chat, which works everywhere.
    const key = chats[0];
    options.push({kind: 'video', via: 'Meeting link', target: via(key), run: () => { openChat(key); startMeeting(true).catch(showError); }});
  }
  // The top: photo, name, protocols, and the favorite star.
  const hero = element('div', '', 'details-hero');
  hero.append(avatarFor(person.name, 'details-avatar'), element('h3', person.name));
  const tags = personTags(person);
  if (sampleOn) hero.append(element('p', 'Sample contact', 'muted'));
  if (tags.length) hero.append(tagRow(tags));
  const favorite = element('button', '', 'favorite-toggle');
  favorite.type = 'button';
  const drawFavorite = () => {
    const on = isFavorite(person);
    favorite.replaceChildren(phoneIcon(on ? 'star' : 'starOutline'), document.createTextNode(on ? ' Favorite' : ' Add to favorites'));
    favorite.setAttribute('aria-pressed', String(on));
  };
  favorite.addEventListener('click', () => { toggleFavorite(person); drawFavorite(); });
  drawFavorite();
  hero.append(favorite);
  const nodes = [hero, personTiles(options), status];
  // Every address, with its protocol and how private it is.
  const info = element('section', '', 'settings-section');
  info.append(element('h3', 'Contact info'));
  for (const number of person.numbers) {
    const key = chats.find((chat) => !internet(threadProtocol(chat)) && sameNumber(threadAddress(chat), number)) || keyOf(number, 'sms');
    const [tone, label] = protection(key);
    info.append(detailRow((threadProtocol(key) === 'rcs' ? 'Phone · RCS preview, sent as SMS' : 'Phone · SMS') + ' · ' + label, displayNumber(number), lockBadge(tone, label, 'large')));
  }
  for (const account of person.accounts) {
    const key = accountKey(account) || keyOf(account.target, account.protocol);
    const [tone, label] = protection(key);
    info.append(detailRow(protocolName(account.protocol) + ' · ' + label, account.target, lockBadge(tone, label, 'large')));
  }
  if (!person.numbers.length && !person.accounts.length) info.append(element('p', 'Only their name is known here.', 'muted'));
  if (person.numbers.length && !phonePaired() && !sampleOn) {
    info.append(element('p', 'Calls and texts to phone numbers go through your phone. Pair it in Settings → Phone and other devices.', 'muted'));
  }
  nodes.push(info);
  // Their chats, newest message first.
  if (chats.length) {
    const list = element('section', '', 'settings-section');
    list.append(element('h3', 'Chats'));
    for (const key of chats) {
      const last = (threads.get(key) || []).at(-1);
      const row = element('button', '', 'member-row');
      row.type = 'button';
      const words = element('span', '', 'detail-words');
      const sealed = typeof chatLocked === 'function' && chatLocked(key);
      words.append(element('strong', via(key) + ' · ' + (routeFor(key)?.target || threadAddress(key))),
        element('small', sealed ? '🔒 Chat code' : last ? excerpt(last.body || '', 80) : 'No messages yet'));
      row.append(words, element('span', '›', 'chevron'));
      row.addEventListener('click', () => openChat(key));
      list.append(row);
    }
    nodes.push(list);
  }
  // Groups you're both in.
  const shared = element('section', '', 'settings-section');
  shared.append(element('h3', 'Groups in common'));
  const groups = person.groups.filter((key) => threads.has(key));
  for (const key of groups) {
    const row = element('button', '', 'member-row');
    row.type = 'button';
    const words = element('span', '', 'detail-words');
    words.append(element('strong', threadTitle(key)), element('small', (threadMembers(key).length + 1) + ' members · ' + protocolName(threadProtocol(key))));
    row.append(avatarFor(threadTitle(key)), words, element('span', '›', 'chevron'));
    row.addEventListener('click', () => openChat(key));
    shared.append(row);
  }
  if (!groups.length) shared.append(element('p', 'No groups in common.', 'muted'));
  nodes.push(shared);
  // Their recent calls.
  const calls = recentCalls().filter((call) => sameText(call.name, person.name)
    || personAddresses(person).some((address) => sameText(address, call.address) || sameNumber(address, call.address))).slice(0, 20);
  if (calls.length) {
    const section = element('section', '', 'settings-section');
    section.append(element('h3', 'Recent calls'));
    for (const call of calls) {
      const row = element('div', '', 'detail-row' + (['Missed', 'Declined'].includes(call.kind) ? ' missed' : ''));
      const words = element('span', '', 'detail-words');
      words.append(element('strong', callKind(call)), element('small', call.protocol));
      row.append(words, element('small', callWhen(call.date), 'muted'));
      section.append(row);
    }
    nodes.push(section);
  }
  // Their one-to-one chats' own settings, the same as each chat's details.
  for (const key of chats.filter((chat) => threads.has(chat))) {
    const box = element('details', '', 'dm-settings');
    box.open = chats.length === 1;
    box.append(element('summary', 'Chat settings · ' + via(key) + (chats.length > 1 ? ' · ' + (routeFor(key)?.target || threadAddress(key)) : '')));
    box.append(...conversationSettings(key, settings));
    nodes.push(box);
  }
  nodes.push(blockSection(person, chats));
  detailsPage(person.name, nodes, back);
}

/** Block: every chat and number of theirs moves to Blocked here. It doesn't stop delivery. */
function blockSection(person, chats) {
  const section = element('section', '', 'settings-section');
  section.append(element('h3', 'Block'));
  const addresses = [...new Set([...chats.map(threadAddress), ...person.numbers])];
  const button = element('button', '', 'danger-text');
  button.type = 'button';
  const note = element('p', '', 'muted');
  const draw = () => {
    const blocked = addresses.length > 0 && addresses.every((address) => inFolder(address, 'blocked'));
    button.textContent = (blocked ? 'Unblock ' : 'Block ') + person.name;
    button.setAttribute('aria-pressed', String(blocked));
    note.textContent = blocked ? 'Their chats are in Blocked here and don’t chime.'
      : 'Moves their chats to Blocked here and stops their chimes. It doesn’t stop delivery; block on your phone for that.';
    button.disabled = !addresses.length;
  };
  button.addEventListener('click', () => {
    const state = chatState();
    const blocked = addresses.every((address) => inFolder(address, 'blocked'));
    const phoneSet = addresses.filter((address) => inFolder(address, 'blocked') && !(state.blocked || []).includes(address));
    const next = {...state, blocked: blocked ? (state.blocked || []).filter((item) => !addresses.includes(item))
      : [...new Set([...(state.blocked || []), ...addresses])],
    archived: blocked ? state.archived : (state.archived || []).filter((item) => !addresses.includes(item))};
    saveChats(next);
    draw();
    if (blocked && phoneSet.length) note.textContent = 'Your phone blocked some of these. Unblock them there, or choose “This web app’s own” settings.';
  });
  draw();
  section.append(note, button);
  if (!addresses.length) section.append(element('p', 'There’s no chat or number to block yet.', 'muted'));
  return section;
}

// Counts on the tabs: missed calls on Calls and unread chats on Chats, on both the side rail and the bottom tabs,
// on the installed app's icon, and as "(N)" in the title. Muted, archived, blocked, and private chats never count,
// and the sample inbox's fictional messages never do either: while it's open, the last real counts stay.

const tabCount = {calls: 0, messages: 0, shown: -1};

function callsSeen() {
  let seen = Number(localStorage.getItem('callsSeen'));
  // The first time, only calls from now on count, not every missed call ever.
  if (!seen) { seen = Date.now(); localStorage.setItem('callsSeen', String(seen)); }
  return seen;
}

/** Opening Calls clears its count. */
function markCallsSeen() {
  if (sampleOn) return;
  localStorage.setItem('callsSeen', String(Date.now()));
  tabCount.calls = 0;
  updateTabBadges();
}

function countTabs() {
  const own = webChats || {};
  const phone = typeof mergeActive === 'function' && mergeActive() ? {} : appearance.folders || {};
  const quiet = new Set(['muted', 'archived', 'blocked', 'private', 'hidden'].flatMap((key) => [...(own[key] || []), ...(phone[key] || [])])
    .filter((item) => typeof item === 'string'));
  const now = Date.now();
  const seen = callsSeen();
  const unread = new Set();
  let missed = 0;
  for (const message of inbox) {
    if (message.type !== 1 || (message.expires && message.expires <= now) || quiet.has(message.address)) continue;
    if (message.event && /^(Voice|Video) call/.test(message.body || '')) {
      if (/missed/.test(message.body) && timeOf(message) > seen) missed++;
      continue;
    }
    if (message.read === 0) unread.add(threadKey(message));
  }
  tabCount.calls = section === 'calls' ? 0 : missed;
  tabCount.messages = unread.size;
}

function updateTabBadges() {
  if (!sampleOn && signedIn) countTabs();
  // Following the phone's settings, its "Show counts on tabs" switch decides; otherwise this web app's own.
  const on = typeof usingPhoneLook === 'function' && usingPhoneLook() && typeof phoneLook.tabCounts === 'boolean'
    ? phoneLook.tabCounts : prefs.tabCounts !== false;
  for (const [key, name, noun] of [['calls', 'Calls', 'missed'], ['messages', 'Chats', 'unread']]) {
    const button = document.querySelector('.rail button[data-section="' + key + '"]');
    if (!button) continue;
    const count = on ? tabCount[key] : 0;
    let badge = button.querySelector('.tab-badge');
    if (!count) badge?.remove();
    else {
      if (!badge) { badge = element('span', '', 'tab-badge'); badge.setAttribute('aria-hidden', 'true'); button.append(badge); }
      badge.textContent = count > 99 ? '99+' : String(count);
    }
    button.setAttribute('aria-label', name + (count ? ', ' + count + ' ' + noun : ''));
  }
  const total = on ? tabCount.calls + tabCount.messages : 0;
  const title = document.title.replace(/^\(\d+\+?\) /, '');
  document.title = total ? '(' + (total > 99 ? '99+' : total) + ') ' + title : title;
  if (total === tabCount.shown) return;
  tabCount.shown = total;
  try {
    if (total && navigator.setAppBadge) navigator.setAppBadge(total).catch(() => {});
    else if (navigator.clearAppBadge) navigator.clearAppBadge().catch(() => {});
  } catch (error) { /* not an installed app here */ }
}

/** Settings → Messaging → Counts on tabs. Kept on the server for every browser. */
function tabCountsCard() {
  const card = element('section', '', 'settings-section');
  card.id = 'settings-tab-counts';
  card.append(element('h3', 'Counts on tabs'), checkbox('Show counts on tabs', prefs.tabCounts !== false, async (value) => {
    prefs = {...prefs, tabCounts: value};
    updateTabBadges();
    try { companionSettings = await api('settings', {prefs: {tabCounts: value}}); } catch (error) { showError(error); }
  }, 'Missed calls on Calls and unread chats on Chats, also on the installed app’s icon and in the page title. '
    + 'Muted, archived, and blocked chats don’t count. Applies to every browser on this server.'));
  return card;
}
