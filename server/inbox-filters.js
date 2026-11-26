'use strict';
// Inbox filters: the menu at the upper trailing edge of the inbox, as on the phone (InboxFilters.kt). Settings →
// Messaging → Inbox filters picks which ones the menu shows and in what order, kept on the server as prefs.inboxFilters
// (an ordered list of ids; a hidden one has a "-" in front). Inbox is always there.
// Filters work inside the open private folder (on its chats only), in a decoy session (on its pretend chats only: no
// favorites), and in the sample inbox (on the sample chats). Loaded after app.js, chats.js, people.js, privacy.js, and
// servers.js, and uses their helpers.

const INBOX_FILTERS = [
  {id: 'inbox', label: 'Inbox', about: 'Every chat that isn’t archived or blocked'},
  {id: 'unread', label: 'Unread', about: 'Chats with messages you haven’t read'},
  {id: 'favorites', label: 'Favorites', about: 'Chats with people you starred as favorites'},
  {id: 'groups', label: 'Groups', about: 'Group texts, channels, and rooms'},
  {id: 'direct', label: 'Direct messages', about: 'One-to-one chats'},
  {id: 'texts', label: 'Texts', about: 'SMS, MMS, and RCS', service: 'sms'},
  {id: 'matrix', label: 'Matrix', about: 'Matrix chats and rooms', service: 'matrix'},
  {id: 'xmpp', label: 'XMPP', about: 'XMPP chats and group chats', service: 'xmpp'},
  {id: 'email', label: 'Email', about: 'Email conversations', service: 'email'},
  {id: 'irc', label: 'IRC', about: 'IRC channels and direct messages', service: 'irc'},
  // Nearby chats stay on the phone (no internet), so this shows only if one ever syncs; it keeps the phone's order intact.
  {id: 'nearby', label: 'Nearby', about: 'Chats with people close by, with no internet', service: 'nearby'},
  // Message requests and automated senders (requests.js).
  {id: 'requests', label: 'Requests', about: 'Chats from people you don’t know, waiting for you to accept them'},
  {id: 'automated', label: 'Automated', about: 'Short codes and sender IDs, like delivery updates and sign-in codes'},
  {id: 'archived', label: 'Archived', about: 'Chats you archived'},
  {id: 'blocked', label: 'Blocked', about: 'Chats you blocked'},
];
const PRIVATE_SUB = 'private:';

/** The stored order as [{id, shown}]: every known filter once, unknown ones dropped, missing ones added at the end. */
function inboxFilterOrder(stored) {
  const known = new Set(INBOX_FILTERS.map((filter) => filter.id));
  const seen = new Set();
  const order = [];
  for (const item of Array.isArray(stored) ? stored : []) {
    if (typeof item !== 'string') continue;
    const id = item.replace(/^-/, '');
    if (!known.has(id) || seen.has(id)) continue;
    seen.add(id);
    order.push({id, shown: id === 'inbox' || !item.startsWith('-')});
  }
  for (const filter of INBOX_FILTERS) if (!seen.has(filter.id)) order.push({id: filter.id, shown: true});
  return order;
}

const encodeInboxFilters = (order) => order.map((entry) => (entry.shown || entry.id === 'inbox' ? '' : '-') + entry.id);
const inboxFilter = (id) => INBOX_FILTERS.find((filter) => filter.id === id);
const inboxFilterByLabel = (label) => INBOX_FILTERS.find((filter) => filter.label === label);

/** The menu's filter ids: the shown ones in order; a service's only when you have chats or an account there. */
function inboxFilterMenu(order, services) {
  return order.filter((entry) => entry.shown && (!inboxFilter(entry.id).service || services.has(inboxFilter(entry.id).service)))
    .map((entry) => entry.id);
}

/** Which folder a filter draws from: archived and blocked chats only show under their own filter. */
const inboxFilterPlace = (id) => id === 'archived' || id === 'blocked' ? id : 'inbox';

/**
 * Whether a chat matches [id] within its folder. [chat]: {group, favorite, services (a Set of protocol families),
 * unread}. In a decoy session favorite is always false, so real favorites never show.
 */
function inboxFilterMatches(id, chat) {
  const filter = inboxFilter(id);
  if (!filter) return true;
  if (filter.service) return chat.services.has(filter.service);
  switch (id) {
    case 'unread': return !!chat.unread;
    case 'favorites': return !!chat.favorite;
    case 'groups': return !!chat.group;
    case 'direct': return !chat.group;
    case 'automated': return !!chat.automated;
    default: return true;
  }
}

/**
 * Where a chat is: {hidden (private folder), archived, blocked, request, automatedOut, dismissed}. Scope 'private'
 * lists only the private folder's chats. Requests show only under Requests, and automated chats kept out of the inbox
 * only under Automated (requests.js); a deleted request with nothing new shows nowhere.
 */
function inboxFilterPlaces(scope, id, place) {
  if (scope === 'private' ? !place.hidden : place.hidden) return false;
  if (place.dismissed) return false;
  const where = inboxFilterPlace(id);
  if (where === 'blocked') return !!place.blocked;
  if (place.blocked || (where === 'archived') !== !!place.archived) return false;
  if (where === 'archived') return true;
  if (id === 'requests') return !!place.request;
  if (place.request) return false;
  return !place.automatedOut || id === 'automated';
}

const inboxDecoy = () => typeof decoyLike === 'function' && decoyLike();

function inboxFilterPrefs() {
  const phone = typeof usingPhoneLook === 'function' && usingPhoneLook() && Array.isArray(phoneLook.inboxFilters) ? phoneLook.inboxFilters : null;
  return inboxFilterOrder(phone || prefs.inboxFilters);
}

/** Services with chats here or an account on this server, for the per-service filters. */
function inboxServices() {
  const found = new Set([...threads.values()].flatMap((messages) => messages.map((row) => family(row.protocol || 'sms'))));
  if (!sampleOn && !inboxDecoy()) for (const account of chatAccounts || []) found.add(account.protocol);
  return found;
}

/** A one-to-one chat with someone starred as a favorite: by number or chat address. Never in a decoy session. */
function favoriteChat(key) {
  // One-to-one chats only: a group with a favorite in it isn't that person's chat.
  if (inboxDecoy() || typeof starredList !== 'function' || threadAddress(key).startsWith('mms:')
    || typeof threadIsGroup === 'function' && threadIsGroup(key)) return false;
  const route = typeof routeFor === 'function' && !sampleOn ? routeFor(key) : null;
  const own = [threadAddress(key), route?.target].filter(Boolean);
  return starredList().some((item) => own.some((address) => sameText(item, address) || sameNumber(item, address)));
}

/** The facts inboxFilterMatches reads for one chat. */
function inboxChatFacts(key, messages, unread) {
  return {group: threadIsGroup(key), favorite: favoriteChat(key), unread, automated: typeof automatedThread === 'function' && automatedThread(key),
    services: new Set(messages.length ? messages.map((row) => family(row.protocol || 'sms')) : [family(threadProtocol(key))])};
}

/** {folder, filter}: the folder the select names (Private folder for its sub-filters), and the filter id in it. */
function inboxSelection() {
  const value = get('folder').value;
  if (value.startsWith(PRIVATE_SUB)) return {folder: PRIVATE_FOLDER, filter: value.slice(PRIVATE_SUB.length)};
  if (typeof PRIVATE_FOLDER !== 'undefined' && value === PRIVATE_FOLDER) return {folder: value, filter: 'inbox'};
  return {folder: value, filter: inboxFilterByLabel(value)?.id || 'inbox'};
}

/** The inbox's title: Messages, a filter's name, or Private folder. */
function inboxTitle(selection) {
  if (selection.folder === 'Inbox') return 'Messages';
  if (selection.folder === PRIVATE_FOLDER && selection.filter !== 'inbox') return inboxFilter(selection.filter).label;
  return selection.folder;
}

let inboxMenuSignature = '';

/**
 * Rebuilds the filter menu from Settings → Inbox filters, keeping the choice. Inside the open private folder its
 * filters show under "In the private folder"; picking any other filter leaves the folder, as before. Private folder
 * and Quick hide are added after these by privacy.js.
 */
function syncInboxFilterMenu() {
  const select = get('folder');
  const current = select.value || 'Inbox';
  const ids = inboxFilterMenu(inboxFilterPrefs(), inboxServices());
  const inPrivate = typeof PRIVATE_FOLDER !== 'undefined' && (current === PRIVATE_FOLDER || current.startsWith(PRIVATE_SUB))
    && typeof privateOpen === 'function' && privateOpen() && !inboxDecoy() && !sampleOn;
  const signature = JSON.stringify([ids, inPrivate]);
  if (signature === inboxMenuSignature && [...select.options].some((option) => option.value === current)) return;
  inboxMenuSignature = signature;
  select.replaceChildren(...ids.map((id) => Object.assign(element('option', inboxFilter(id).label), {value: inboxFilter(id).label})));
  if (inPrivate) {
    const group = element('optgroup', '');
    group.label = 'In the private folder';
    group.append(Object.assign(element('option', 'All private chats'), {value: PRIVATE_FOLDER}));
    for (const id of ids.filter((item) => item !== 'inbox')) {
      group.append(Object.assign(element('option', inboxFilter(id).label), {value: PRIVATE_SUB + id}));
    }
    select.append(group);
  }
  if (typeof syncFolderOptions === 'function') syncFolderOptions();
  const values = [...select.options].map((option) => option.value);
  // A filter turned off in Settings, or a service that's gone, goes back to Inbox.
  select.value = values.includes(current) ? current : current.startsWith(PRIVATE_SUB) && values.includes(PRIVATE_FOLDER) ? PRIVATE_FOLDER : 'Inbox';
}

/** Settings → Messaging → Inbox filters: a switch for each filter, and Move up and Move down to reorder them. */
function inboxFiltersPage() {
  const card = settingsCard('Inbox filters', 'Choose which filters the inbox’s filter menu shows, and their order. Inbox is always there. '
    + 'A service shows once you have chats or an account there. Applies to every browser on this server.');
  card.id = 'settings-inbox-filters';
  const list = element('ol', '', 'filter-order');
  list.setAttribute('aria-label', 'Inbox filters, in menu order');
  const status = element('p', '', 'muted');
  status.setAttribute('role', 'status');
  let order = inboxFilterPrefs();
  const save = async (next, focus, said) => {
    order = next;
    const value = encodeInboxFilters(order);
    prefs = {...prefs, inboxFilters: value};
    if (typeof usingPhoneLook === 'function' && usingPhoneLook()) phoneLook = {...phoneLook, inboxFilters: value};
    draw(focus);
    status.textContent = said;
    inboxMenuSignature = '';
    showThreads();
    try { companionSettings = await api('settings', {prefs: {inboxFilters: value}}); } catch (error) { showError(error); }
  };
  const draw = (focus) => {
    list.replaceChildren();
    order.forEach((entry, index) => {
      const filter = inboxFilter(entry.id);
      const item = element('li', '', 'filter-order-row');
      const toggle = Object.assign(element('input', ''), {type: 'checkbox', checked: entry.shown, disabled: entry.id === 'inbox'});
      toggle.id = 'filter-' + entry.id;
      const words = element('label', '', 'account-words');
      words.htmlFor = toggle.id;
      words.append(element('strong', filter.label), element('small', entry.id === 'inbox' ? 'Always shown' : filter.about, 'muted'));
      toggle.addEventListener('change', () => save(order.map((other) => other.id === entry.id ? {...other, shown: toggle.checked} : other),
        toggle.id, filter.label + (toggle.checked ? ' shows in the menu' : ' is hidden from the menu')));
      const move = (delta, label) => {
        const button = element('button', delta < 0 ? '↑' : '↓', 'icon-button small');
        button.type = 'button';
        button.id = 'filter-' + entry.id + (delta < 0 ? '-up' : '-down');
        button.setAttribute('aria-label', label + ' ' + filter.label);
        button.title = label;
        button.disabled = index + delta < 0 || index + delta >= order.length;
        button.addEventListener('click', () => {
          const next = [...order];
          [next[index], next[index + delta]] = [next[index + delta], next[index]];
          const target = index + delta <= 0 || index + delta >= order.length - 1 ? 'filter-' + entry.id + (delta < 0 ? '-down' : '-up') : button.id;
          save(next, target, filter.label + ' moved to position ' + (index + delta + 1));
        });
        return button;
      };
      item.append(toggle, words, move(-1, 'Move up'), move(1, 'Move down'));
      list.append(item);
    });
    if (focus) requestAnimationFrame(() => document.getElementById(focus)?.focus());
  };
  draw();
  const reset = element('button', 'Restore the default order', 'quiet');
  reset.type = 'button';
  reset.addEventListener('click', () => save(inboxFilterOrder([]), '', 'Inbox filters restored'));
  card.append(list, status, reset);
  return card;
}

SETTINGS_SUBPAGES.push({id: 'inbox-filters', icon: 'messaging', title: 'Inbox filters', parent: 'messaging', page: () => [inboxFiltersPage()]});
