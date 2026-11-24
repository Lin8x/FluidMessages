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
const phoneTab = {view: 'recents', keypad: false, dialed: '', query: '', missedOnly: false, note: null, jobs: [], mode: 'number', typed: ''};

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

// Starting something new: what a typed number or address is. The phone's NewAddress.detect follows the same rules
// (NewAddressTest.kt), and scripts/test-local runs this block with the same cases.
// address-detect:start
const IRC_NICK = /^[A-Za-z[\]\\`_^{|}][A-Za-z0-9[\]\\`_^{|}-]{0,31}$/;
/**
 * Every service [text] could be, best guess first: [{service, target, group?, network?, guess?}]. service is sms,
 * matrix, xmpp, email, irc, or sip. Several entries mean it's ambiguous (name@example.org is email or XMPP); guess
 * marks a bare word that might be an IRC nickname or just a name being searched.
 */
function detectAddress(text) {
  const value = String(text || '').trim();
  if (!value || value.length > 320 || /[\u0000-\u001f]/.test(value)) return [];
  if (/^\+?[\d ().-]+$/.test(value)) return value.replace(/\D/g, '').length >= 3 ? [{service: 'sms', target: value}] : [];
  let match = /^sip:([^@\s]{1,64}@[A-Za-z0-9.:-]{1,253})$/i.exec(value);
  if (match) return [{service: 'sip', target: 'sip:' + match[1]}];
  if (/^@[a-z0-9._=\-/+]{1,255}:[A-Za-z0-9.:-]{1,255}$/.test(value)) return [{service: 'matrix', target: value}];
  if (/^[!#][^\s:]{1,250}:[A-Za-z0-9.:-]{1,255}$/.test(value)) return [{service: 'matrix', target: value, group: true}];
  if (/^[#&][^\s,:\u0007]{1,49}$/.test(value)) return [{service: 'irc', target: value, group: true}];
  match = /^irc:(?:\/\/([A-Za-z0-9.-]+)\/)?(\S+)$/i.exec(value);
  if (match && (IRC_NICK.test(match[2]) || /^[#&][^\s,:]{1,49}$/.test(match[2]))) {
    return [{service: 'irc', target: match[2], ...(match[1] ? {network: match[1].toLowerCase()} : {}), ...(/^[#&]/.test(match[2]) ? {group: true} : {})}];
  }
  match = /^([^@\s<>"']{1,64})@([A-Za-z0-9](?:[A-Za-z0-9.-]{0,251}[A-Za-z0-9])?)$/.exec(value);
  if (match) {
    const [, user, host] = match;
    const lower = value.toLowerCase();
    // riley@irc.libera.chat: a nickname on that IRC network.
    if (/^irc\./i.test(host) && IRC_NICK.test(user)) return [{service: 'irc', target: user, network: host.toLowerCase()}];
    if (!host.includes('.')) return [{service: 'xmpp', target: lower}];
    // Group chats live on conference servers; everything else could be email or XMPP.
    if (/^(conference|muc|chat|rooms?)\./i.test(host)) return [{service: 'xmpp', target: lower, group: true}, {service: 'email', target: lower}];
    return [{service: 'email', target: lower}, {service: 'xmpp', target: lower}];
  }
  if (IRC_NICK.test(value)) return [{service: 'irc', target: value, guess: true}];
  return [];
}
// address-detect:end

const SERVICE_LABELS = {sms: 'SMS', matrix: 'Matrix', xmpp: 'XMPP', email: 'Email', irc: 'IRC', sip: 'SIP'};

// Contacts made here (main.py web_contacts): kept for every browser and fetched by the phone, which saves them into
// its contacts once you allow it. In the sample inbox, new contacts stay on this page only.
let webContacts = [];
let sampleContacts = [];
const webContactList = () => (sampleOn ? sampleContacts : webContacts);

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
  // Contacts made here first, so their names win and their numbers and addresses find their chats.
  for (const contact of webContactList()) {
    let person = null;
    for (const number of contact.numbers || []) person = add({name: contact.name, number}) || person;
    for (const item of contact.addresses || []) {
      person = add({name: contact.name, account: {protocol: item.service, target: item.address, address: ''}}) || person;
    }
    if (!person) person = add({name: contact.name});
    if (person) Object.assign(person, {name: contact.name, named: true, webId: contact.id});
  }
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
    const label = labels[account.protocol] || SERVICE_LABELS[account.protocol] || account.protocol;
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
  if (Array.isArray(snapshot.web_contacts)) webContacts = snapshot.web_contacts;
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
  const create = element('button', '+ New contact', 'primary new-contact');
  create.type = 'button';
  create.addEventListener('click', () => openContactEditor(null, {back: () => { closeDetailsPage(false); showPhoneTab(list); }}));
  body.append(largeTitle('Contacts', create));
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
        : 'No contacts yet. People you text, chat with, and meet in groups show up here, and so do contacts you make with New contact.', 'placeholder'));
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
  // Call a phone number through your phone, or call or message anyone by their address, like the phone's keypad.
  const modes = element('div', '', 'phone-filter keypad-modes');
  modes.setAttribute('role', 'group');
  modes.setAttribute('aria-label', 'Dial');
  for (const [mode, label] of [['number', 'Number'], ['address', 'Address']]) {
    const button = element('button', label);
    button.type = 'button';
    button.setAttribute('aria-pressed', String(phoneTab.mode === mode));
    button.addEventListener('click', () => { phoneTab.mode = mode; showPhoneTab(list); });
    modes.append(button);
  }
  body.append(modes);
  if (phoneTab.mode === 'address') { addressDialView(body, list); return; }
  const pad = element('div', '', 'dialpad');
  const display = element('output', phoneTab.dialed, 'dial-display');
  display.tabIndex = 0;
  display.setAttribute('aria-label', phoneTab.dialed ? 'Number: ' + phoneTab.dialed : 'Number. Type digits or use the keys.');
  const match = element('button', '', 'dial-match');
  match.type = 'button';
  const status = element('div', '', 'dial-status');
  // Someone new: text them, or save them as a contact.
  const extras = element('div', '', 'button-row dial-new');
  const message = element('button', 'Message', 'quiet');
  message.type = 'button';
  const save = element('button', 'Add to contacts', 'quiet');
  save.type = 'button';
  extras.append(message, save);
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
    const fresh = !person && /^\+?[\d]{3,}$/.test(phoneTab.dialed);
    extras.hidden = !fresh;
    message.setAttribute('aria-label', 'Message ' + phoneTab.dialed);
    save.setAttribute('aria-label', 'Add ' + phoneTab.dialed + ' to contacts');
  };
  match.addEventListener('click', () => { const person = matched(); if (person) openPersonPage(person).catch(showError); });
  message.addEventListener('click', () => openChat(keyOf(phoneTab.dialed, 'sms')));
  save.addEventListener('click', () => openContactEditor(null, {prefill: {numbers: [phoneTab.dialed]},
    back: () => { closeDetailsPage(false); showPhoneTab(list); }}));
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
    if (text === null) status.replaceChildren(noPhoneNote(number, matched()));
    else {
      const line = element('p', text, 'phone-status' + (error ? ' error' : ''));
      line.setAttribute('role', error ? 'alert' : 'status');
      status.replaceChildren(line);
    }
    // Below the keys on short screens: bring what happened into view.
    status.scrollIntoView?.({block: 'nearest', behavior: 'smooth'});
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
  const hint = sampleOn || phonePaired() ? 'Type a number on your keyboard too. Enter calls, Esc closes.'
    : 'Calls to numbers ring through your phone once it’s paired. Type a number on your keyboard too.';
  pad.append(display, match, extras, keys, actions, status, element('p', hint, 'dial-hint'), close);
  pad.typeKey = type;
  pad.callNow = () => call.click();
  pad.eraseKey = () => erase.click();
  body.append(pad);
  update();
}

/** Keypad → Address: call a Matrix address from this browser, or message anyone by their address on any service. */
function addressDialView(body, list) {
  const box = element('form', '', 'settings-section address-dial');
  box.noValidate = true;
  const input = Object.assign(element('input', ''), {value: phoneTab.typed, autocomplete: 'off', spellcheck: false,
    placeholder: '@name:example.org'});
  input.setAttribute('autocapitalize', 'off');
  const label = element('label', 'Address');
  label.append(input, element('small', 'Matrix @name:server, XMPP name@server, SIP sip:name@provider, or a phone number', 'hint'));
  const line = element('p', '', 'hint');
  line.setAttribute('aria-live', 'polite');
  const status = element('div', '', 'dial-status');
  const row = element('div', '', 'button-row wrap');
  const button = (text, run, primary) => {
    const node = element('button', text, primary ? 'primary' : '');
    node.type = 'button';
    node.addEventListener('click', run);
    row.append(node);
    return node;
  };
  const report = (text, error) => {
    const note = element('p', text, 'phone-status' + (error ? ' error' : ''));
    note.setAttribute('role', error ? 'alert' : 'status');
    status.replaceChildren(note);
  };
  const callMatrix = async (target, video) => {
    if (sampleOn) { report('Sample inbox · nothing was sent. With a real Matrix account, this calls ' + target + '.'); return; }
    if (!chatAccounts.some((item) => item.protocol === 'matrix')) { report('Add a Matrix account in Settings → Accounts to call Matrix addresses.', true); return; }
    try {
      const key = await startInternetChat({service: 'matrix', target});
      openChat(key);
      await startCall(video);
    } catch (error) { report(error.message, true); }
  };
  const draw = () => {
    row.replaceChildren();
    status.replaceChildren();
    const [option] = detectAddress(input.value);
    const service = option?.service;
    if (!input.value.trim()) line.textContent = 'Type an address to call or message someone new.';
    else if (!option || option.guess) line.textContent = 'That doesn’t look like an address yet.';
    else line.textContent = (SERVICE_LABELS[service] || service) + (option.group ? ' room or channel' : ' address');
    if (service === 'matrix' && !option.group) {
      button('Voice call', () => callMatrix(option.target, false), true);
      button('Video call', () => callMatrix(option.target, true));
    }
    if (service === 'sms') button('Call through your phone', () => { phoneTab.mode = 'number'; phoneTab.dialed = option.target.replace(/[^\d+*#]/g, ''); showPhoneTab(list); }, true);
    if (['xmpp', 'sip'].includes(service)) {
      status.append(element('p', (service === 'sip' ? 'SIP' : 'XMPP') + ' calls are made from FluidMessages for Android: open its Phone tab → Keypad → Username.'
        + (service === 'xmpp' ? ' You can message XMPP addresses from here.' : ''), 'muted'));
    }
    if (option && !option.guess && service !== 'sip') button('Message', () => { phoneTab.typed = input.value; openNewChat('', {to: input.value.trim()}); }, service !== 'matrix' && service !== 'sms');
    if (option && !option.guess) button('Add to contacts', () => openContactEditor(null, {prefill: service === 'sms' ? {numbers: [option.target]}
      : {addresses: [{service, address: service === 'irc' && option.network ? option.target + '@' + option.network : option.target.replace(/^sip:/, '')}]},
    back: () => { closeDetailsPage(false); showPhoneTab(list); }}));
  };
  input.addEventListener('input', () => { phoneTab.typed = input.value; draw(); });
  box.addEventListener('submit', (event) => { event.preventDefault(); row.querySelector('button')?.click(); });
  const close = element('button', 'Hide keypad', 'quiet dial-close');
  close.type = 'button';
  close.addEventListener('click', () => { phoneTab.keypad = false; showPhoneTab(list); });
  box.append(label, line, row, status, close);
  body.append(box);
  draw();
  requestAnimationFrame(() => input.focus({preventScroll: true}));
}

/** Opens the Calls tab's keypad, on Address with [address] typed in when there is one (New chat → a SIP address). */
function openKeypad({address = ''} = {}) {
  showSection('calls');
  phoneTab.keypad = true;
  if (address) { phoneTab.mode = 'address'; phoneTab.typed = address; }
  showPhoneTab(get('calls-list'));
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
  // Saved addresses without a chat yet: Message starts one there.
  for (const account of person.accounts) {
    if (account.address || !['matrix', 'xmpp', 'email', 'irc'].includes(account.protocol)) continue;
    if (chats.some((key) => threadProtocol(key) === account.protocol)) continue;
    options.push({kind: 'message', via: protocolName(account.protocol), target: account.target, run: () => {
      if (sampleOn) { report('Sample inbox · nothing was sent.'); return; }
      const [nick, network] = account.protocol === 'irc' ? account.target.split('@') : [account.target];
      startInternetChat({service: account.protocol, target: nick, name: person.name}).then(openChat)
        .catch((error) => report(network ? error.message + ' (' + network + ')' : error.message, '', true));
    }});
  }
  // Only the ways to call that are on in Settings → Calls.
  const ways = (way) => typeof callWayOn !== 'function' || callWayOn(way);
  for (const account of person.accounts) {
    if (account.protocol !== 'matrix' || !ways('matrix')) continue;
    const run = (video) => () => {
      if (sampleOn) { report('Sample inbox · nothing was sent.'); return; }
      if (account.address) { callOnMatrix(account, video); return; }
      // A Matrix address from a contact, with no chat yet: start the chat, then call in it.
      startInternetChat({service: 'matrix', target: account.target, name: person.name})
        .then((key) => { openChat(key); return startCall(video); }).catch((error) => report(error.message, '', true));
    };
    options.push({kind: 'voice', via: 'Matrix', target: account.target, run: run(false)}, {kind: 'video', via: 'Matrix', target: account.target, run: run(true)});
  }
  if (!options.some((option) => option.kind === 'video') && chats.length && ways('meeting')) {
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
  // Edit a contact made here, or save anyone else as one, with every number and address known for them.
  const reopen = () => openPersonPage(person, {back});
  const stored = person.webId ? webContactList().find((contact) => contact.id === person.webId) : null;
  const edit = element('button', stored ? 'Edit contact' : 'Save as contact', 'favorite-toggle contact-edit');
  edit.type = 'button';
  edit.addEventListener('click', () => (stored ? openContactEditor(stored, {back: reopen})
    : openContactEditor(null, {back: reopen, prefill: {name: person.named ? person.name : '', numbers: person.numbers,
      addresses: person.accounts.filter((account) => SERVICE_LABELS[account.protocol] && account.protocol !== 'sms'
        && !/^[!#]/.test(account.target)).map((account) => ({service: account.protocol, address: account.target}))}})));
  const tools = element('div', '', 'button-row contact-tools');
  tools.append(favorite, edit);
  hero.append(tools);
  if (stored) hero.append(element('p', sampleOn ? 'Made on this page · not saved' : 'Saved on FluidMessages Web · your phone can save it to its contacts', 'muted'));
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
    info.append(detailRow((SERVICE_LABELS[account.protocol] && !labels[account.protocol] ? SERVICE_LABELS[account.protocol] : protocolName(account.protocol))
      + ' · ' + label, account.target, lockBadge(tone, label, 'large')));
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
  // Mute all of their chats and numbers for a while or for good (chats.js).
  if (typeof personMuteSection === 'function') nodes.push(personMuteSection(person, chats));
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

// New contact and Edit: a full page, like the phone's contact editor (ContactEditor.kt). Contacts are kept on the
// server for every browser (main.py web_contacts); the phone fetches them and saves them into Android's contacts once
// you allow it. In the sample inbox they stay on this page.

const CONTACT_SERVICES = ['matrix', 'xmpp', 'email', 'irc', 'sip'];

/** [contact] is a stored contact to edit, or null for a new one; [prefill] fills a new one (from the keypad, say). */
function openContactEditor(contact = null, {back, prefill = {}} = {}) {
  const editing = !!contact?.id;
  const form = element('form', '', 'settings-section contact-form');
  form.noValidate = true;
  const name = Object.assign(element('input', ''), {value: contact?.name ?? prefill.name ?? '', autocomplete: 'off', maxLength: 100,
    placeholder: 'Maya Chen', required: true});
  const numbers = element('div', '', 'contact-rows');
  const addresses = element('div', '', 'contact-rows');
  const status = element('p', '', 'error');
  status.setAttribute('role', 'alert');
  const remove = (row, list, focus) => {
    row.remove();
    if (!list.children.length) focus();
  };
  const numberRow = (value = '') => {
    const row = element('div', '', 'contact-row');
    const input = Object.assign(element('input', ''), {type: 'tel', value, autocomplete: 'off', maxLength: 40, placeholder: '+1 202 555 0143'});
    input.setAttribute('aria-label', 'Phone number');
    const drop = element('button', 'Remove', 'quiet');
    drop.type = 'button';
    drop.setAttribute('aria-label', 'Remove this phone number');
    drop.addEventListener('click', () => remove(row, numbers, () => addNumber.focus()));
    row.append(input, drop);
    numbers.append(row);
    return input;
  };
  const addressRow = (item = {service: '', address: ''}) => {
    const row = element('div', '', 'contact-row contact-address');
    const input = Object.assign(element('input', ''), {value: item.address || '', autocomplete: 'off', spellcheck: false, maxLength: 320,
      placeholder: '@maya:example.org'});
    input.setAttribute('autocapitalize', 'off');
    input.setAttribute('aria-label', 'Chat address');
    const service = element('select', '');
    service.setAttribute('aria-label', 'Service');
    for (const key of CONTACT_SERVICES) service.append(Object.assign(element('option', SERVICE_LABELS[key]), {value: key}));
    service.value = item.service || 'matrix';
    // The service follows what's typed until you pick one yourself.
    let chosen = !!item.service;
    service.addEventListener('change', () => { chosen = true; });
    input.addEventListener('input', () => {
      if (chosen) return;
      const [guess] = detectAddress(input.value);
      if (guess && CONTACT_SERVICES.includes(guess.service) && !guess.guess) service.value = guess.service;
    });
    const drop = element('button', 'Remove', 'quiet');
    drop.type = 'button';
    drop.setAttribute('aria-label', 'Remove this chat address');
    drop.addEventListener('click', () => remove(row, addresses, () => addAddress.focus()));
    row.append(input, service, drop);
    row.read = () => {
      let value = input.value.trim();
      if (service.value === 'sip') value = value.replace(/^sip:/i, '');
      return {service: service.value, address: value};
    };
    addresses.append(row);
    return input;
  };
  const addNumber = element('button', '+ Add phone number', 'quiet');
  addNumber.type = 'button';
  addNumber.addEventListener('click', () => numberRow().focus());
  const addAddress = element('button', '+ Add chat address', 'quiet');
  addAddress.type = 'button';
  addAddress.addEventListener('click', () => addressRow().focus());
  for (const number of contact?.numbers || prefill.numbers || []) numberRow(number);
  for (const item of contact?.addresses || prefill.addresses || []) addressRow(item);
  if (!numbers.children.length) numberRow();
  const numberSet = element('fieldset', '', 'contact-set');
  numberSet.append(element('legend', 'Phone numbers'), numbers, addNumber);
  const addressSet = element('fieldset', '', 'contact-set');
  addressSet.append(element('legend', 'Chat addresses'),
    element('p', 'Matrix @name:server, XMPP or email name@server, IRC nickname (riley@irc.libera.chat for a network), or SIP name@provider. '
      + 'The service is picked from what you type; change it if it’s wrong.', 'hint'), addresses, addAddress);
  const save = element('button', editing ? 'Save changes' : 'Save contact', 'primary');
  const cancel = element('button', 'Cancel', 'quiet');
  cancel.type = 'button';
  const leave = back || (() => closeDetailsPage());
  cancel.addEventListener('click', leave);
  const actions = element('div', '', 'button-row');
  actions.append(save, cancel);
  form.append(field('Name', name), numberSet, addressSet, status, actions);
  const where = element('p', sampleOn ? 'Sample inbox · contacts you make here stay on this page and aren’t saved.'
    : 'Saved on FluidMessages Web for every browser. Your phone shows it after its next sync, and saves it to its contacts once you allow it there.',
  'muted contact-where');
  const opened = (saved) => {
    const person = knownPeople().find((item) => item.webId === saved.id);
    if (person) openPersonPage(person, {back}).catch(showError);
    else leave();
  };
  form.addEventListener('submit', async (event) => {
    event.preventDefault();
    status.textContent = '';
    const draft = {name: name.value.trim(), numbers: [...numbers.querySelectorAll('input')].map((input) => input.value.trim()).filter(Boolean),
      addresses: [...addresses.children].map((row) => row.read()).filter((item) => item.address)};
    // The same checks as the server, so mistakes show right away; the server checks again.
    if (!draft.name) { status.textContent = 'Enter a name.'; name.focus(); return; }
    const badNumber = draft.numbers.find((number) => !/^\+?[0-9][0-9 ().-]{2,39}$/.test(number) || digitsOf(number).length < 3);
    if (badNumber) { status.textContent = '“' + excerpt(badNumber, 40) + '” isn’t a phone number. Use digits, like +1 202 555 0143.'; return; }
    if (sampleOn) {
      const saved = {id: contact?.id || 'sample-' + Date.now().toString(16), ...draft};
      sampleContacts = [...sampleContacts.filter((item) => item.id !== saved.id), saved];
      opened(saved);
      return;
    }
    save.disabled = true;
    try {
      const result = await api('contacts', {...(editing ? {id: contact.id} : {}), ...draft});
      webContacts = result.web_contacts;
      opened(result.contact);
    } catch (error) { status.textContent = error.message; }
    finally { save.disabled = false; }
  });
  const nodes = [where, form];
  if (editing) {
    const danger = element('section', '', 'settings-section');
    danger.append(element('h3', 'Delete contact'), element('p', sampleOn ? 'Removes it from this page.'
      : 'Removes it from FluidMessages Web. Chats with them stay, and a copy your phone already saved stays in its contacts.', 'muted'),
    confirmButton('Delete ' + contact.name, 'Tap again to delete', async () => {
      if (sampleOn) sampleContacts = sampleContacts.filter((item) => item.id !== contact.id);
      else {
        try { webContacts = (await api('contacts/delete', {id: contact.id})).web_contacts; } catch (error) { status.textContent = error.message; return; }
      }
      closeDetailsPage(false);
      if (section === 'calls') { phoneTab.view = 'contacts'; phoneTab.keypad = false; showPhoneTab(get('calls-list')); }
    }, 'danger-text'));
    nodes.push(danger);
  }
  detailsPage(editing ? 'Edit contact' : 'New contact', nodes, leave);
  requestAnimationFrame(() => (name.value ? numbers.querySelector('input') || name : name).focus({preventScroll: true}));
}

// Counts on the tabs: missed calls on Calls and unread chats on Chats, on both the side rail and the bottom tabs,
// on the installed app's icon, and as "(N)" in the title. Muted, archived, blocked, and private chats never count,
// and the sample inbox's fictional messages never do either: while it's open, the last real counts stay.

const tabCount = {calls: 0, messages: 0, shown: -1};
// The sample inbox counts its own unread chats and missed calls on the tabs, on this page only (sampleTabCounts).
let sampleCallsSeen = false;

function callsSeen() {
  let seen = Number(localStorage.getItem('callsSeen'));
  // The first time, only calls from now on count, not every missed call ever.
  if (!seen) { seen = Date.now(); localStorage.setItem('callsSeen', String(seen)); }
  return seen;
}

/** Opening Calls clears its count. */
function markCallsSeen() {
  if (sampleOn) { sampleCallsSeen = true; updateTabBadges(); return; }
  localStorage.setItem('callsSeen', String(Date.now()));
  tabCount.calls = 0;
  updateTabBadges();
}

/** Chats that never count: muted, archived, blocked, and private, this web app's and the phone's. In the sample inbox, its own. */
function quietChats() {
  const own = (sampleOn && typeof sampleChats !== 'undefined' ? sampleChats : webChats) || {};
  const phone = sampleOn || (typeof mergeActive === 'function' && mergeActive()) ? {} : appearance.folders || {};
  const quiet = new Set(['archived', 'blocked', 'private', 'hidden'].flatMap((key) => [...(own[key] || []), ...(phone[key] || [])])
    .concat(phone.muted || []).filter((item) => typeof item === 'string'));
  // Muted right now, by the chat itself or its whole server; a timed mute that has run out counts again (chats.js).
  const muted = typeof chatMuteEnds === 'function' ? (address) => chatMuteEnds(address, own) !== null
    : (address) => (own.muted || []).includes(address);
  for (const item of own.muted || []) if (typeof item === 'string' && muted(item)) quiet.add(item);
  for (const message of inbox) if (!quiet.has(message.address) && muted(message.address)) quiet.add(message.address);
  if (typeof privateFolderHas === 'function') for (const message of inbox) if (privateFolderHas(message.address)) quiet.add(message.address);
  // Message requests, and automated chats kept out of the inbox or blocked (requests.js).
  if (typeof requestQuietAddresses === 'function') for (const address of requestQuietAddresses()) quiet.add(address);
  return quiet;
}

const callEvent = (message) => !!message.event && /^(Voice|Video) call/.test(message.body || '');

/** The rule for an unread chat, shared by the Chats tab count and the inbox filter's Unread: an incoming message,
 * not a call, not expired, not read yet, in a chat that isn't muted, archived, blocked, or private. */
function unreadIncoming(message, quiet = quietChats(), now = Date.now()) {
  return message.type === 1 && !(message.expires && message.expires <= now) && !quiet.has(message.address)
    && !callEvent(message) && message.read === 0;
}

/** The chats with unread messages, by thread key (app.js showThreads for Unread). */
function unreadChats() {
  const quiet = quietChats();
  const now = Date.now();
  return new Set(inbox.filter((message) => unreadIncoming(message, quiet, now)).map(threadKey));
}

function countTabs() {
  const quiet = quietChats();
  const now = Date.now();
  const seen = callsSeen();
  const unread = new Set();
  let missed = 0;
  for (const message of inbox) {
    if (message.type !== 1 || (message.expires && message.expires <= now) || quiet.has(message.address)) continue;
    if (callEvent(message)) {
      if (/missed/.test(message.body) && timeOf(message) > seen) missed++;
      continue;
    }
    if (unreadIncoming(message, quiet, now)) unread.add(threadKey(message));
  }
  // Channels and rooms shown only under Communities count there instead (servers.js).
  if (typeof chatsShows === 'function' && !showCommunityChats()) for (const key of unread) if (!chatsShows(key, false)) unread.delete(key);
  tabCount.calls = section === 'calls' ? 0 : missed;
  tabCount.messages = unread.size;
}

/** The sample's counts: its unread chats by the same rule, and its fictional missed calls until Calls is opened. */
function sampleTabCounts() {
  const unread = unreadChats();
  if (typeof chatsShows === 'function' && !showCommunityChats()) for (const key of unread) if (!chatsShows(key, false)) unread.delete(key);
  const missed = sampleCallsSeen || section === 'calls' ? 0 : SAMPLE_RECENTS.filter((call) => call[2] === 'Missed').length;
  return {calls: missed, messages: unread.size};
}

function updateTabBadges() {
  if (!sampleOn && signedIn) countTabs();
  // Real counts stay as they were while the sample is open; the sample's never reach the title or the app icon.
  const counts = sampleOn ? sampleTabCounts() : tabCount;
  // Following the phone's settings, its "Show counts on tabs" switch decides; otherwise this web app's own.
  const on = typeof usingPhoneLook === 'function' && usingPhoneLook() && typeof phoneLook.tabCounts === 'boolean'
    ? phoneLook.tabCounts : prefs.tabCounts !== false;
  for (const [key, name, noun] of [['calls', 'Calls', 'missed'], ['messages', 'Chats', 'unread']]) {
    const button = document.querySelector('.rail button[data-section="' + key + '"]');
    if (!button) continue;
    const count = on ? counts[key] : 0;
    let badge = button.querySelector('.tab-badge');
    if (!count) badge?.remove();
    else {
      if (!badge) { badge = element('span', '', 'tab-badge'); badge.setAttribute('aria-hidden', 'true'); button.append(badge); }
      badge.textContent = count > 99 ? '99+' : String(count);
    }
    button.setAttribute('aria-label', name + (count ? ', ' + count + ' ' + noun : ''));
  }
  if (sampleOn) return;
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

/** Settings → Sounds and notifications → App badges. Kept on the server for every browser. */
function tabCountsCard() {
  const card = element('section', '', 'settings-section');
  card.id = 'settings-tab-counts';
  card.append(element('h3', 'App badges'), checkbox('Show counts on tabs', prefs.tabCounts !== false, async (value) => {
    prefs = {...prefs, tabCounts: value};
    updateTabBadges();
    try { companionSettings = await api('settings', {prefs: {tabCounts: value}}); } catch (error) { showError(error); }
  }, 'Missed calls on Calls and unread chats on Chats, also on the installed app’s icon and in the page title. '
    + 'Muted, archived, and blocked chats don’t count. Applies to every browser on this server.'));
  return card;
}
