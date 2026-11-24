'use strict';
// Servers: each IRC network, Matrix account (and its Spaces), and XMPP account you're connected to.
// Messages → Communities lists each server with its channels or rooms, sorted and collapsible; Settings → Messaging
// → "Show community chats in Chats" decides whether those also show in Chats like group chats.
// From a server you can join a channel or room by name, browse the server's own directory (IRC LIST, the Matrix
// public room directory, XMPP service discovery), and add another server. Leave is in a channel's details.
// Every step is a full page. Loaded after app.js, companion.js, chats.js, and server-directory.js, and uses their
// helpers (api, element, routes, chatAccounts, SERVER_DIRECTORY).

const SERVER_PROTOCOLS = ['irc', 'matrix', 'xmpp'];
const SERVER_HELP = {
  irc: 'An IRC network such as Libera.Chat hosts many public chat rooms called channels. Each network is its own account.',
  matrix: 'Your Matrix account lives on a homeserver. You can join rooms on any homeserver, and Spaces collect related rooms together.',
  xmpp: 'Your XMPP account lives on one server. Group chats live on group chat services such as conference.example.org.',
  email: 'Each mailbox is its own account, such as a work and a personal address.',
};
const SERVICE_TITLES = {irc: 'IRC network', matrix: 'Matrix homeserver', xmpp: 'XMPP server', email: 'Email account'};
const spaceCache = {};

function serverAccounts() {
  return (chatAccounts || []).filter((account) => SERVER_PROTOCOLS.includes(account.protocol) && account.id);
}

/** The short name of a server: the IRC network, the homeserver, or the XMPP domain. */
function serverName(account) {
  const name = account.name || '';
  const host = account.protocol === 'irc' ? name.split('@').pop() : account.protocol === 'matrix' ? name.split(':').slice(1).join(':')
    : name.split('@').pop();
  const known = account.protocol === 'irc' && (SERVER_DIRECTORY.irc || []).find((entry) => entry.host === host);
  return known ? known.name : host || labels[account.protocol];
}

/** " · via alice@example.org" when a service has more than one account, for chat headers and details. */
function accountVia(route) {
  if (!route?.account) return '';
  const same = (chatAccounts || []).filter((account) => account.id && account.protocol === route.account.split(':')[0]);
  return same.length > 1 ? ' · via ' + (route.account_name || route.account.split(':').slice(1).join(':')) : '';
}

// Messages → Chats | Communities. Communities lists each server with its channels and rooms, like chat rows, under a
// collapsible header with its actions. The same design as the phone (Communities.kt).

const COMMUNITY_SORTS = {recent: 'Recent activity', name: 'Name (A–Z)', joined: 'Date joined'};
let inboxView = localStorage.getItem('inboxView') === 'communities' ? 'communities' : 'chats';
const collapsedServers = new Set((() => {
  try { return JSON.parse(localStorage.getItem('communityCollapsed') || '[]'); } catch (error) { return []; }
})());

/** A pref the phone also has: the phone's value while following its settings (like tabCounts), otherwise this server's. */
function syncedPref(key, fallback) {
  if (typeof usingPhoneLook === 'function' && usingPhoneLook() && phoneLook[key] !== undefined) return phoneLook[key];
  return prefs[key] ?? fallback;
}

async function saveSyncedPref(key, value) {
  prefs = {...prefs, [key]: value};
  if (typeof usingPhoneLook === 'function' && usingPhoneLook() && phoneLook[key] !== undefined) phoneLook = {...phoneLook, [key]: value};
  showThreads();
  try { companionSettings = await api('settings', {prefs: {[key]: value}}); } catch (error) { showError(error); }
}

const showCommunityChats = () => syncedPref('showCommunityChats', true) !== false;
const communitySort = () => COMMUNITY_SORTS[syncedPref('communitySort', 'recent')] ? syncedPref('communitySort', 'recent') : 'recent';

/** A channel or room on a server (not a direct message there, and never in the sample inbox or a decoy session). */
function isCommunityChat(key) {
  if (sampleOn || (typeof inboxDecoy === 'function' && inboxDecoy())) return false;
  const route = typeof routeFor === 'function' ? routeFor(key) : null;
  return !!route?.group && !!route.account && serverAccounts().some((account) => account.id === route.account);
}

/** Whether Chats lists [key]: community chats only while "Show community chats in Chats" is on. */
const chatsShows = (key, show = showCommunityChats()) => show || !isCommunityChat(key);

/** The switch shows in the real inbox only: not the sample inbox, a decoy session, or the private folder. */
function communitiesOffered(folder) {
  return !sampleOn && !(typeof inboxDecoy === 'function' && inboxDecoy()) && !(typeof PRIVATE_FOLDER !== 'undefined' && folder === PRIVATE_FOLDER);
}

const inboxViewNow = (folder) => communitiesOffered(folder) ? inboxView : 'chats';

/** A channel's name for sorting: "#radio-club" sorts as "radio-club". */
const communityKey = (name) => String(name || '').replace(/^[#&!+]+/, '').toLocaleLowerCase();

/**
 * Servers and their channels in [sort] order: 'recent' (newest message first), 'name' (A–Z), or 'joined' (newest
 * first). [servers]: [{id, name, order}] (order: when it was added); [channels]: [{account, name, last, joined,
 * unread}]. Each server's own order follows its channels: its newest message, its name, or when it was added.
 */
function communityGroups(servers, channels, sort) {
  const byName = (a, b) => communityKey(a.name).localeCompare(communityKey(b.name));
  const order = {
    recent: (a, b) => (b.last || 0) - (a.last || 0) || byName(a, b),
    name: (a, b) => byName(a, b) || (b.last || 0) - (a.last || 0),
    joined: (a, b) => (b.joined || 0) - (a.joined || 0) || byName(a, b),
  }[sort] || ((a, b) => (b.last || 0) - (a.last || 0) || byName(a, b));
  const groups = servers.map((server) => {
    const mine = channels.filter((channel) => channel.account === server.id).sort(order);
    return {server, channels: mine, unread: mine.filter((channel) => channel.unread).length,
      last: Math.max(0, ...mine.map((channel) => channel.last || 0))};
  });
  const serverOrder = {
    recent: (a, b) => b.last - a.last || byName(a.server, b.server),
    name: (a, b) => byName(a.server, b.server),
    joined: (a, b) => (b.server.order || 0) - (a.server.order || 0) || byName(a.server, b.server),
  }[sort] || ((a, b) => b.last - a.last);
  return groups.sort(serverOrder);
}

/** Unread community chats, by the Chats tab's rule (people.js), for the Communities segment's count. */
function communityUnread() {
  if (typeof unreadChats !== 'function') return 0;
  return [...unreadChats()].filter((key) => isCommunityChat(key)).length;
}

/** Messages' Chats | Communities switch, under the title. */
function drawInboxSwitch(folder) {
  const holder = get('inbox-view');
  if (!holder) return;
  const offered = communitiesOffered(folder);
  holder.hidden = !offered;
  if (!offered) return;
  const unread = showCommunityChats() ? 0 : communityUnread();
  const make = (value, label) => {
    const button = element('button', '', 'segment');
    button.type = 'button';
    button.append(element('span', label));
    button.setAttribute('aria-pressed', String(inboxView === value));
    if (value === 'communities' && unread) {
      const badge = element('span', unread > 99 ? '99+' : String(unread), 'segment-badge');
      badge.setAttribute('aria-hidden', 'true');
      button.append(badge);
      button.setAttribute('aria-label', label + ', ' + unread + ' unread');
    }
    button.addEventListener('click', () => {
      if (inboxView === value) return;
      inboxView = value;
      localStorage.setItem('inboxView', value);
      showThreads();
    });
    return button;
  };
  holder.replaceChildren(make('chats', 'Chats'), make('communities', 'Communities'));
}

function toggleServer(id) {
  if (collapsedServers.has(id)) collapsedServers.delete(id);
  else collapsedServers.add(id);
  localStorage.setItem('communityCollapsed', JSON.stringify([...collapsedServers]));
  showThreads();
}

/** A server's initials for its header: "LC" for Libera.Chat, "EX" for example.org. */
function serverInitials(name) {
  const words = String(name).split(/[^\p{L}\p{N}]+/u).filter(Boolean);
  return (words.length > 1 ? words[0][0] + words[1][0] : (words[0] || '?').slice(0, 2)).toUpperCase();
}

/**
 * Communities: the sort menu, then each server with its channels and rooms as chat rows. [rows] are the inbox's
 * community chats after search and the filter; [row] draws one exactly as Chats does (app.js threadButton).
 */
function drawCommunities(list, rows, row) {
  const accounts = serverAccounts();
  const query = get('search').value.trim();
  if (!accounts.length) {
    const empty = element('div', '', 'empty-list community-empty');
    empty.append(element('strong', 'No communities yet'),
      element('p', 'Communities are public chat networks with many rooms: IRC networks, Matrix homeservers and their Spaces, '
        + 'and XMPP servers. Add one to join its channels and rooms. They show here, and in Chats if you like.', 'muted'));
    const add = element('button', 'Add server', 'primary');
    add.type = 'button';
    add.addEventListener('click', () => openAddServer());
    empty.append(add);
    list.append(empty);
    return;
  }
  const bar = element('div', '', 'community-bar');
  const sort = element('select', '');
  sort.setAttribute('aria-label', 'Sort communities');
  for (const [value, label] of Object.entries(COMMUNITY_SORTS)) sort.append(Object.assign(element('option', label), {value}));
  sort.value = communitySort();
  sort.addEventListener('change', () => saveSyncedPref('communitySort', sort.value));
  const sortLabel = element('label', '', 'community-sort');
  sortLabel.append(element('span', 'Sort'), sort);
  bar.append(sortLabel);
  list.append(bar);
  const unread = typeof unreadChats === 'function' ? unreadChats() : new Set();
  const index = new Map(routes.map((route, position) => [route.address + '\u001f' + route.protocol, position]));
  const channels = rows.map(([key, messages]) => {
    const route = routeFor(key);
    const last = messages.at(-1);
    return {key, messages, account: route.account, name: threadTitle(key), last: last ? timeOf(last) : 0,
      joined: route.joined || index.get(route.address + '\u001f' + route.protocol) || 0, unread: unread.has(key)};
  });
  const servers = accounts.map((account) => ({id: account.id, name: serverName(account), order: chatAccounts.indexOf(account), account}));
  let shown = 0;
  for (const group of communityGroups(servers, channels, communitySort())) {
    const {account} = group.server;
    // While searching, servers with nothing matching stay out.
    if (query && !group.channels.length) continue;
    shown++;
    const open = !collapsedServers.has(account.id) || !!query;
    const section = element('section', '', 'community');
    const head = element('div', '', 'community-head');
    const toggle = element('button', '', 'community-toggle');
    toggle.type = 'button';
    toggle.setAttribute('aria-expanded', String(open));
    const face = element('span', serverInitials(group.server.name), 'avatar community-avatar p-' + account.protocol);
    face.setAttribute('aria-hidden', 'true');
    const words = element('span', '', 'community-words');
    const noun = account.protocol === 'irc' ? 'channel' : account.protocol === 'matrix' ? 'room' : 'group chat';
    const title = element('span', '', 'community-title');
    title.append(element('strong', group.server.name), element('span', labels[account.protocol], 'tag p-' + account.protocol));
    // A muted server: every channel, room, and DM on it (chats.js). Its mark opens the mute page.
    const serverMuteKey = typeof SERVER_MUTE === 'string' ? SERVER_MUTE + account.id : '';
    const serverEnds = serverMuteKey && typeof muteEnds === 'function' ? muteEnds(serverMuteKey) : null;
    // Beside the header's toggle, not inside it, since the mark is its own button.
    const serverMark = serverEnds === null ? null : muteMark(muteLabel(serverEnds), () => openMutePage({keys: [serverMuteKey],
      title: group.server.name, what: 'Every channel, room, and direct message on ' + group.server.name + '.'}));
    words.append(title, element('small', group.channels.length + ' ' + noun + (group.channels.length === 1 ? '' : 's') + ' · ' + account.name));
    const chevron = element('span', '›', 'community-chevron');
    chevron.setAttribute('aria-hidden', 'true');
    toggle.append(chevron, face, words);
    if (group.unread) {
      const count = element('span', group.unread > 99 ? '99+' : String(group.unread), 'channel-unread');
      count.setAttribute('aria-label', group.unread + ' unread');
      toggle.append(count);
    }
    toggle.setAttribute('aria-label', group.server.name + ', ' + labels[account.protocol] + (group.unread ? ', ' + group.unread + ' unread' : '')
      + (open ? ', expanded' : ', collapsed'));
    toggle.addEventListener('click', () => toggleServer(account.id));
    const more = element('button', '⋯', 'icon-button small community-more');
    more.type = 'button';
    more.setAttribute('aria-label', 'Actions for ' + group.server.name);
    more.setAttribute('aria-expanded', 'false');
    head.append(toggle, ...(serverMark ? [serverMark] : []), more);
    section.append(head);
    const actions = element('div', '', 'community-actions');
    actions.hidden = true;
    actions.setAttribute('role', 'group');
    actions.setAttribute('aria-label', 'Actions for ' + group.server.name);
    const action = (label, run, primary = false) => {
      const button = element('button', label, primary ? 'primary' : '');
      button.type = 'button';
      button.addEventListener('click', run);
      actions.append(button);
    };
    action('Browse', () => openBrowse(account), true);
    action('Join a ' + noun, () => openJoin(account));
    if (serverMuteKey) action(serverEnds === null ? 'Mute notifications' : 'Muted · change', () => openMutePage({keys: [serverMuteKey],
      title: group.server.name, what: 'Every channel, room, and direct message on ' + group.server.name + '.'}));
    action('Server details', () => openServerDetails(account));
    more.addEventListener('click', () => {
      actions.hidden = !actions.hidden;
      more.setAttribute('aria-expanded', String(!actions.hidden));
    });
    section.append(actions);
    if (open) {
      const body = element('div', '', 'community-body');
      body.setAttribute('aria-label', group.server.name + ' ' + noun + 's');
      for (const channel of group.channels) body.append(row(channel.key, channel.messages));
      if (!group.channels.length) body.append(element('p', 'No ' + noun + 's joined yet. Browse ' + group.server.name + ' to find some.', 'placeholder'));
      section.append(body);
    }
    list.append(section);
  }
  if (query && !shown) list.append(element('p', 'No channels or rooms match “' + excerpt(query, 40) + '”', 'placeholder'));
  const add = element('button', '+ Add server', 'community-add');
  add.type = 'button';
  add.addEventListener('click', () => openAddServer());
  list.append(add);
}

/** A server's details on a full page: its account, Browse and Join, and Remove for one added here or on the phone. */
function openServerDetails(account) {
  const section = element('section', '', 'settings-section');
  const noun = account.protocol === 'irc' ? 'channels' : account.protocol === 'matrix' ? 'rooms' : 'group chats';
  const joined = routes.filter((route) => route.account === account.id && route.group).length;
  const status = element('p', '', 'muted');
  status.setAttribute('role', 'status');
  section.append(element('h3', serverName(account)), element('p', SERVICE_TITLES[account.protocol] + ' · signed in as ' + account.name, 'muted'),
    element('p', joined + ' ' + noun + ' joined. Leave one from its chat details.', 'muted'));
  const row = element('div', '', 'button-row');
  const browse = element('button', 'Browse', 'primary');
  const join = element('button', 'Join by name');
  browse.type = join.type = 'button';
  browse.addEventListener('click', () => openBrowse(account));
  join.addEventListener('click', () => openJoin(account));
  row.append(browse, join);
  section.append(row);
  // Mute every channel, room, and DM on this server (chats.js); its mark shows on the server's header while it's muted.
  if (typeof serverMuteControls === 'function') section.append(...serverMuteControls(account.id, serverName(account)));
  const remove = element('section', '', 'settings-section');
  remove.append(element('h3', 'Remove server'));
  if (account.source === 'phone') {
    remove.append(element('p', 'Signs out and stops getting its messages. Earlier messages stay here.', 'muted'),
      confirmButton('Remove ' + serverName(account), 'Tap again to remove', async () => {
        try {
          await api('protocols/accounts/delete', {protocol: account.protocol, id: account.id});
          closeDetailsPage(false);
          await refresh().catch(() => {});
        } catch (error) { status.textContent = error.message; }
      }, 'danger'), status);
  } else remove.append(element('p', 'This server is set on FluidMessages Web itself (Docker Compose or its config file). Remove it there.', 'muted'));
  detailsPage(serverName(account), [section, remove], () => closeDetailsPage());
}

/** Settings → Messaging → Show community chats in Chats. */
function communityChatsSwitch() {
  const wrap = checkbox('Show community chats in Chats', showCommunityChats(), (value) => saveSyncedPref('showCommunityChats', value),
    'Channels and rooms on your servers also show in Chats, like group chats. Off, they show only under Communities, which then '
    + 'counts their unread messages itself. Direct messages on those services always show in Chats. Applies to every browser on this server.');
  wrap.id = 'settings-community-chats';
  return wrap;
}

async function loadSpaces(ident, refresh = false) {
  const account = serverAccounts().find((item) => item.id === ident);
  if (account?.protocol !== 'matrix' || spaceCache[ident] && !refresh) return;
  spaceCache[ident] = [];
  try {
    spaceCache[ident] = (await api('protocols/servers/spaces', {account: ident, refresh})).spaces || [];
    if (spaceCache[ident].length) showThreads();
  } catch (error) { /* Spaces are optional; the server's rooms still show. */ }
}

function serverHint(account) {
  return {irc: '#channel', matrix: '#room:' + (serverName(account) || 'example.org'), xmpp: 'room@conference.' + (account.name.split('@').pop() || 'example.org')}[account.protocol];
}

async function joinTarget(account, target, title, status) {
  status.textContent = 'Joining…';
  try {
    const result = await api('protocols/join', {account: account.id, target, title});
    if (!routes.some((route) => route.address === result.route.address && route.protocol === result.route.protocol)) routes.push(result.route);
    await refresh().catch(() => {});
    closeDetailsPage(false);
    select(result.route.address + '\u001f' + result.route.protocol);
    return true;
  } catch (error) {
    status.textContent = error.message;
    return false;
  }
}

/** Join a channel or room by name: a full page, like the phone's. */
function openJoin(account) {
  const form = element('form', '', 'settings-section');
  const target = Object.assign(element('input', ''), {required: true, placeholder: serverHint(account), autocomplete: 'off', spellcheck: false});
  const status = element('p', '', 'muted');
  status.setAttribute('role', 'status');
  const noun = account.protocol === 'irc' ? 'channel' : account.protocol === 'matrix' ? 'room' : 'group chat';
  form.append(element('h3', 'Join a ' + noun + ' on ' + serverName(account)),
    field(account.protocol === 'irc' ? 'Channel' : 'Address', target,
      account.protocol === 'irc' ? 'Channel names start with #. You can leave it out.' : account.protocol === 'matrix'
        ? 'A room address like #room:example.org, or a room ID. Rooms on other homeservers work too.'
        : 'A group chat address like room@conference.example.org.'),
    Object.assign(element('button', 'Join', 'primary'), {type: 'submit'}), status);
  form.addEventListener('submit', (event) => {
    event.preventDefault();
    joinTarget(account, target.value.trim(), '', status);
  });
  const find = element('button', 'Browse ' + serverName(account) + ' instead', 'quiet');
  find.type = 'button';
  find.addEventListener('click', () => openBrowse(account));
  detailsPage('Join a ' + noun, [form, find], () => closeDetailsPage());
  target.focus();
}

/** The joined chat for [target] on [account], if any, so Browse can offer Open. */
function joinedRoute(account, target) {
  const wanted = String(target || '').toLowerCase();
  if (!wanted) return null;
  return routes.find((route) => route.account === account.id && route.group && String(route.target).toLowerCase() === wanted) || null;
}

function openRoute(route) {
  closeDetailsPage(false);
  select(threadKey({address: route.address, protocol: route.protocol}));
}

/**
 * The server's own directory on a full page: IRC LIST with a mask, the Matrix public room directory with its search
 * filter, or XMPP service discovery filtered here. The list filters as you type; the server is asked again shortly
 * after you stop typing, where it can search.
 */
function openBrowse(account, service = '') {
  const section = element('section', '', 'settings-section');
  const search = Object.assign(element('input', ''), {type: 'search', placeholder: account.protocol === 'irc' ? 'Search channels'
    : account.protocol === 'matrix' ? 'Search rooms' : service ? 'Search group chats' : 'Search services', autocomplete: 'off', spellcheck: false});
  search.setAttribute('aria-label', search.placeholder);
  search.setAttribute('aria-controls', 'browse-results');
  const status = element('p', '', 'muted');
  status.setAttribute('role', 'status');
  const results = element('div', '', 'browse-list');
  results.id = 'browse-results';
  const where = account.protocol === 'xmpp' ? (service || 'group chat services') : serverName(account);
  section.append(element('h3', 'Browse ' + where),
    element('p', {irc: 'The network’s public channels, busiest first. Big networks have thousands, so the network searches them for you and the first 100 matches show.',
      matrix: 'Public rooms in this homeserver’s directory.', xmpp: service ? 'Public group chats on this service.'
        : 'Group chat services on your XMPP server. Pick one to see its group chats.'}[account.protocol], 'muted'),
    search, status, results);
  let found = [];       // The last rooms the server sent, filtered here as you type.
  let services = null;  // XMPP: group chat services, before one is picked.
  let asked = null;     // The search the server last answered (null: not yet).
  let request = 0;
  let timer = 0;
  const row = (room) => {
    const item = element('div', '', 'browse-row');
    const words = element('span', '', 'account-words');
    const route = joinedRoute(account, room.target) || joinedRoute(account, room.alias);
    words.append(element('strong', room.name || room.target),
      element('span', [room.alias || (room.name !== room.target ? room.target : ''),
        room.members ? room.members + (room.members === 1 ? ' person' : ' people') : '', room.topic].filter(Boolean).join(' · '), 'muted browse-detail'));
    item.append(words);
    if (route || room.joined) {
      item.append(element('span', 'Joined', 'browse-joined'));
      const open = element('button', 'Open');
      open.type = 'button';
      open.setAttribute('aria-label', 'Open ' + (room.name || room.target));
      open.addEventListener('click', () => {
        const target = route || joinedRoute(account, room.target);
        if (target) openRoute(target);
        else joinTarget(account, room.alias || room.target, room.name, status);
      });
      item.append(open);
    } else {
      const join = element('button', 'Join', 'primary');
      join.type = 'button';
      join.setAttribute('aria-label', 'Join ' + (room.name || room.target));
      join.addEventListener('click', async () => {
        join.disabled = true;
        join.disabled = await joinTarget(account, room.alias || room.target, room.name, status);
      });
      item.append(join);
    }
    return item;
  };
  const draw = () => {
    results.replaceChildren();
    const query = search.value.trim();
    if (services) {
      const list = services.filter((jid) => !query || jid.toLowerCase().includes(query.toLowerCase()));
      for (const jid of list) {
        const open = element('button', '', 'settings-row');
        open.type = 'button';
        const words = element('span', '', 'account-words');
        words.append(element('strong', jid), element('span', 'Group chat service', 'muted'));
        open.append(words);
        open.addEventListener('click', () => openBrowse(account, jid));
        results.append(open);
      }
      status.textContent = !services.length ? 'Your server didn’t list a group chat service. Join one by address instead.'
        : list.length ? '' : 'Nothing matches “' + query + '”.';
      return;
    }
    const list = roomSearch(found, query);
    for (const room of list) results.append(row(room));
    if (asked === null) return;
    status.textContent = list.length ? list.length + ' found' : query ? 'Nothing matches “' + query + '”.' : 'Nothing listed.';
  };
  const run = async () => {
    const term = account.protocol === 'xmpp' ? '' : search.value.trim();
    if (asked === term) return draw();
    const mine = ++request;
    status.textContent = 'Searching ' + where + '…';
    try {
      const reply = await api('protocols/browse', {account: account.id, search: term, limit: 100, ...(service ? {service} : {})});
      if (mine !== request) return;
      asked = term;
      if (reply.services) services = reply.services;
      else found = reply.rooms || [];
      draw();
    } catch (error) { if (mine === request) status.textContent = error.message; }
  };
  search.addEventListener('input', () => {
    draw();
    clearTimeout(timer);
    // XMPP has no directory search: everything was fetched once and is filtered here.
    if (account.protocol !== 'xmpp') timer = setTimeout(run, 450);
  });
  search.addEventListener('keydown', (event) => {
    if (event.key !== 'Enter') return;
    event.preventDefault();
    clearTimeout(timer);
    run();
  });
  const nodes = [section];
  if (account.protocol === 'matrix') {
    // Rooms in your Spaces, with the ones you haven't joined yet.
    const spaces = element('section', '', 'settings-section');
    const drawSpaces = () => {
      spaces.replaceChildren(element('h3', 'Your Spaces'));
      const list = spaceCache[account.id] || [];
      if (!list.length) spaces.append(element('p', 'No Spaces on this account. A Space collects related rooms under one name.', 'muted'));
      for (const space of list) {
        spaces.append(element('h4', space.name));
        for (const room of space.rooms) spaces.append(row(room));
      }
    };
    loadSpaces(account.id, true).then(drawSpaces);
    drawSpaces();
    nodes.push(spaces);
  }
  const join = element('button', 'Join by name or address instead', 'quiet');
  join.type = 'button';
  join.addEventListener('click', () => openJoin(account));
  nodes.push(join);
  detailsPage('Browse', nodes, service ? () => openBrowse(account) : () => closeDetailsPage());
  run();
  search.focus();
}

/** Leave, in a channel's or room's details. */
function leaveSection(route) {
  if (!route?.group || !route.account || !SERVER_PROTOCOLS.includes(route.protocol)) return null;
  const section = element('section', '', 'settings-section');
  const noun = route.protocol === 'irc' ? 'channel' : route.protocol === 'matrix' ? 'room' : 'group chat';
  const status = element('p', '', 'muted');
  status.setAttribute('role', 'status');
  section.append(element('h3', 'Server'), element('p', labels[route.protocol] + ' · ' + (route.account_name || route.account.split(':').slice(1).join(':')), 'muted'),
    confirmButton('Leave this ' + noun, 'Tap again to leave', async () => {
      try {
        await api('protocols/leave', {address: route.address, protocol: route.protocol});
        const index = routes.findIndex((item) => item.address === route.address && item.protocol === route.protocol);
        if (index >= 0) routes.splice(index, 1);
        closeDetailsPage(false);
        await refresh().catch(() => {});
      } catch (error) { status.textContent = error.message; }
    }, 'danger'),
    element('p', 'You stop getting its messages. Earlier messages stay here.', 'muted'), status);
  return section;
}

/** Add server, step 1: pick a service, on a full page. */
function openAddServer() {
  const section = element('section', '', 'settings-section');
  section.append(element('h3', 'Choose a service'),
    element('p', 'Connect another IRC network, Matrix homeserver, or XMPP server. Each one shows up in Messages → Communities with its channels and rooms.', 'muted'));
  const list = element('div', '', 'service-list');
  for (const protocol of SERVER_PROTOCOLS) {
    const button = element('button', '', 'settings-row service-row');
    button.type = 'button';
    const words = element('span', '', 'account-words');
    words.append(element('strong', labels[protocol]), element('span', SERVER_HELP[protocol], 'muted'));
    const badge = element('span', labels[protocol][0], 'account-badge p-' + protocol);
    badge.setAttribute('aria-hidden', 'true');
    const chevron = element('span', '›', 'service-chevron');
    chevron.setAttribute('aria-hidden', 'true');
    button.append(badge, words, chevron);
    button.addEventListener('click', () => showServerList(protocol));
    list.append(button);
  }
  section.append(list);
  detailsPage('Add server', [section], () => closeDetailsPage());
}

/** Add server, step 2: well-known public servers for [protocol], searchable, and Other server… for any other. */
function showServerList(protocol) {
  const section = element('section', '', 'settings-section');
  const noun = {irc: 'networks', matrix: 'homeservers', xmpp: 'servers'}[protocol];
  const search = Object.assign(element('input', ''), {type: 'search', placeholder: 'Search ' + noun, autocomplete: 'off', spellcheck: false});
  search.setAttribute('aria-label', 'Search ' + noun);
  const list = element('div', '', 'directory-list');
  list.setAttribute('role', 'list');
  list.setAttribute('aria-label', 'Public ' + noun);
  const status = element('p', '', 'muted');
  status.setAttribute('role', 'status');
  const other = element('button', '', 'settings-row directory-other');
  other.type = 'button';
  const otherWords = element('span', '', 'account-words');
  otherWords.append(element('strong', 'Other server…'), element('span', {irc: 'Enter any network’s host and port',
    matrix: 'Enter any homeserver’s address', xmpp: 'Enter your address on any XMPP server'}[protocol], 'muted'));
  const plus = element('span', '+', 'account-badge directory-plus');
  plus.setAttribute('aria-hidden', 'true');
  other.append(plus, otherWords);
  other.addEventListener('click', () => showServerForm(protocol, null));
  section.append(element('h3', 'Public ' + noun),
    element('p', 'Well-known public ' + noun + '. Picking one fills in the sign-in form. Nothing connects until you save.', 'muted'),
    search, status, list, other);
  const draw = () => {
    list.replaceChildren();
    const found = directorySearch(SERVER_DIRECTORY[protocol] || [], search.value);
    for (const entry of found) {
      const row = element('button', '', 'settings-row directory-row');
      row.type = 'button';
      row.setAttribute('role', 'listitem');
      const words = element('span', '', 'account-words');
      const usable = entry.tls !== false;
      words.append(element('strong', entry.name),
        element('span', usable ? entry.about : entry.about + '. It doesn’t offer TLS, so FluidMessages can’t connect to it.', 'muted'),
        element('small', directoryAddress(entry) + (entry.port ? ':' + entry.port : ''), 'directory-address'));
      row.append(words);
      row.disabled = !usable;
      row.addEventListener('click', () => showServerForm(protocol, entry));
      list.append(row);
    }
    status.textContent = found.length ? '' : 'No public ' + noun + ' match “' + search.value.trim() + '”. Choose Other server… below.';
  };
  search.addEventListener('input', draw);
  draw();
  detailsPage(SERVICE_TITLES[protocol], [section], () => openAddServer());
  search.focus();
}

/** Add server, step 3: the sign-in form, pre-filled from the directory [entry], or blank for Other server…. */
function showServerForm(protocol, entry, preset = {}) {
  const done = () => { closeDetailsPage(false); refresh().catch(() => {}); };
  const intro = element('section', '', 'settings-section');
  if (entry) {
    intro.append(element('h3', entry.name), element('p', entry.about, 'muted'),
      element('p', {irc: 'Server ' + entry.host + ', TLS port ' + entry.port + '.', matrix: 'Homeserver ' + entry.homeserver + '.',
        xmpp: 'Your address ends in @' + entry.domain + '.'}[protocol], 'muted'));
    preset = {...preset, ...{irc: {server: entry.host, port: String(entry.port)}, matrix: {homeserver: entry.homeserver},
      xmpp: {jid: '@' + entry.domain}}[protocol]};
  } else {
    intro.append(element('h3', 'Other ' + SERVICE_TITLES[protocol].split(' ').pop()), element('p', {
      irc: 'Enter the network’s host and its TLS port, usually 6697.',
      matrix: 'Enter the homeserver’s address, starting with https://.',
      xmpp: 'Enter your full XMPP address. The server is found from its domain.'}[protocol], 'muted'));
  }
  if (protocol === 'irc') {
    // TLS is always on: FluidMessages never sends IRC passwords or messages in plain text.
    const tls = Object.assign(element('input', ''), {type: 'checkbox', checked: true, disabled: true});
    const wrap = element('label', '', 'check');
    const words = element('span', '');
    words.append(element('span', 'Use TLS'), element('small', 'Always on. FluidMessages doesn’t connect to IRC without it.', 'hint'));
    wrap.append(tls, words);
    intro.append(wrap);
  }
  const form = accountForm(protocol, null, done, {add: true, preset});
  const hint = {matrix: ['user_id', '@you:' + (entry?.name || 'example.org')], xmpp: ['jid', 'you@' + (entry?.domain || 'example.org')]}[protocol];
  const named = hint && form.querySelector('[name="' + hint[0] + '"]');
  if (named) named.placeholder = hint[1];
  detailsPage(entry ? entry.name : 'Other ' + SERVICE_TITLES[protocol],
    [intro, element('p', SERVER_HELP[protocol], 'muted server-help'), form], () => showServerList(protocol));
  // The first empty field gets the cursor; an XMPP address starts before "@domain".
  if (protocol === 'xmpp' && entry && named) {
    named.focus();
    named.setSelectionRange(0, 0);
  } else ([...form.querySelectorAll('input')].find((input) => !input.value) || form.querySelector('input'))?.focus();
}

/**
 * New chat → Channels: each server's joined channels and rooms under its name, with Join or browse… for that server,
 * and Add server. Picking a channel opens it. [sheet] is the New chat page, closed before anything else opens.
 */
function newChatChannels(sheet) {
  const panel = element('div', '', 'settings-section new-chat-channels');
  const leave = (run) => () => { sheet.close(); run(); };
  const addServer = element('button', 'Add server', 'primary');
  addServer.type = 'button';
  addServer.addEventListener('click', leave(() => openAddServer()));
  if (typeof sampleOn !== 'undefined' && sampleOn) {
    panel.append(element('p', 'Servers aren’t part of the sample inbox. Exit it to join channels and rooms on your own servers.', 'muted'));
    return panel;
  }
  const accounts = serverAccounts();
  if (!accounts.length) {
    panel.append(element('h3', 'No servers yet'),
      element('p', 'Servers are IRC networks, Matrix homeservers, and XMPP accounts. Add one to join its channels and rooms, then start a '
        + 'message in any of them from here.', 'muted'), addServer);
    return panel;
  }
  const search = Object.assign(element('input', ''), {type: 'search', placeholder: 'Search channels and rooms', autocomplete: 'off', spellcheck: false});
  search.setAttribute('aria-label', 'Search joined channels and rooms');
  const groups = element('div', '', 'channel-groups');
  const status = element('p', '', 'muted');
  status.setAttribute('role', 'status');
  const draw = () => {
    groups.replaceChildren();
    const query = search.value.trim();
    let shown = 0;
    for (const account of accounts) {
      const noun = account.protocol === 'irc' ? 'channels' : account.protocol === 'matrix' ? 'rooms' : 'group chats';
      const all = serverChannels(account);
      const joined = roomSearch(all.map((route) => ({...route, name: channelTitle(route)})), query);
      shown += joined.length;
      // While searching, servers with nothing matching are left out.
      if (query && !joined.length) continue;
      const group = element('section', '', 'channel-group');
      const head = element('div', '', 'channel-group-head');
      const badge = element('span', labels[account.protocol][0], 'account-badge p-' + account.protocol);
      badge.setAttribute('aria-hidden', 'true');
      const words = element('span', '', 'account-words');
      words.append(element('h3', serverName(account)), element('span', labels[account.protocol] + ' · ' + account.name, 'muted'));
      head.append(badge, words);
      group.append(head);
      const list = element('div', '', 'channel-list');
      list.setAttribute('aria-label', serverName(account) + ' ' + noun);
      for (const route of joined) list.append(channelButton(route, () => {
        sheet.close();
        select(threadKey({address: route.address, protocol: route.protocol}));
        get('body').focus();
      }));
      if (!all.length) list.append(element('p', 'No ' + noun + ' joined on ' + serverName(account) + ' yet.', 'muted'));
      const join = element('button', 'Join or browse…');
      join.type = 'button';
      join.setAttribute('aria-label', 'Join or browse ' + noun + ' on ' + serverName(account));
      join.addEventListener('click', leave(() => openBrowse(account)));
      group.append(list, join);
      groups.append(group);
    }
    status.textContent = query && !shown ? 'No joined channels or rooms match “' + query + '”. Browse a server to find more.' : '';
  };
  search.addEventListener('input', draw);
  draw();
  panel.append(search, status, groups);
  addServer.className = '';
  panel.append(addServer);
  return panel;
}

/** A server's joined channels and rooms, sorted by name ("#" and "&" aside). */
function serverChannels(account) {
  const bare = (route) => channelTitle(route).replace(/^[#&!]/, '').toLowerCase();
  return routes.filter((route) => route.account === account.id && route.group).sort((a, b) => bare(a).localeCompare(bare(b)));
}

/** A channel's or room's name: #name for IRC, the room's name for Matrix and XMPP. */
function channelTitle(route) {
  const title = String(route.title || '').trim();
  if (route.protocol === 'irc') return title && title !== route.address ? title : route.target;
  if (title && title !== route.address) return title;
  return route.protocol === 'xmpp' ? String(route.target).split('@')[0] : route.target;
}

/** Unread count and the newest message of a joined channel, from the inbox. */
function channelSummary(route) {
  const messages = (typeof threads !== 'undefined' && threads.get(threadKey({address: route.address, protocol: route.protocol}))) || [];
  return {unread: messages.filter((row) => row.type === 1 && row.read === 0).length, last: messages.at(-1)};
}

function channelButton(route, open) {
  const button = element('button', '', 'settings-row channel-row');
  button.type = 'button';
  const label = element('span', '', 'account-words');
  const {unread, last} = channelSummary(route);
  const title = channelTitle(route);
  label.append(element('strong', title), element('span', last?.body ? excerpt(last.body, 80)
    : title !== route.target ? route.target : 'No messages yet', 'muted'));
  button.append(label);
  if (unread) {
    const count = element('span', unread > 99 ? '99+' : String(unread), 'channel-unread');
    count.setAttribute('aria-label', unread + ' unread');
    button.append(count);
  }
  button.addEventListener('click', open);
  return button;
}

/**
 * Joined channels and rooms with no messages yet, as [key, []] inbox rows, so a channel shows in the inbox as soon as
 * it's joined. [has] says whether the inbox already has the key.
 */
function emptyServerRows(has) {
  if (typeof sampleOn !== 'undefined' && sampleOn) return [];
  const ids = new Set(serverAccounts().map((account) => account.id));
  const keys = routes.filter((route) => route.group && ids.has(route.account))
    .map((route) => threadKey({address: route.address, protocol: route.protocol}));
  return [...new Set(keys)].filter((key) => !has(key)).map((key) => [key, []]);
}
