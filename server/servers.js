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
  // The sample inbox has its own fictional servers, never your accounts (sampleServers below).
  if (typeof sampleOn !== 'undefined' && sampleOn) return SAMPLE_SERVERS;
  return (chatAccounts || []).filter((account) => SERVER_PROTOCOLS.includes(account.protocol) && account.id);
}

/** The short name of a server: the IRC network, the homeserver, or the XMPP domain. */
function serverName(account) {
  if (account.label) return account.label;
  const name = account.name || '';
  const host = account.protocol === 'irc' ? name.split('@').pop() : account.protocol === 'matrix' ? name.split(':').slice(1).join(':')
    : name.split('@').pop();
  const known = account.protocol === 'irc' && (SERVER_DIRECTORY.irc || []).find((entry) => entry.host === host);
  return known ? known.name : host || labels[account.protocol];
}

// from-account:start (scripts/test-local runs this block on its own)
// Which account sends, like a mail app's From field and the phone's FromAccount (AccountKeys.kt). Nothing shows while a
// service has one account. A chat always replies from its own account; a link from before multiple accounts names none
// and belongs to the service's first one. New chat preselects the account used most recently, else the newest.

/**
 * An address compared the way its service does (protocol_gateway.target_key, AccountKeys.targetKey), so one person,
 * group, channel, or room is one chat per account: case-blind for email, XMPP, and Matrix users and aliases; a Matrix
 * room ID keeps its case but not its server's; IRC folds case by RFC 1459, where []\\~ are the capitals of {}|^.
 */
function targetKey(service, target) {
  const text = String(target || '').trim();
  const via = text.startsWith('!') ? 'matrix' : service;
  if (via === 'irc') return text.toLowerCase().replace(/[[\]\\~]/g, (c) => ({'[': '{', ']': '}', '\\': '|', '~': '^'})[c]);
  if (via === 'matrix' && text.startsWith('!')) {
    const at = text.indexOf(':');
    return at < 0 ? text : text.slice(0, at) + ':' + text.slice(at + 1).toLowerCase();
  }
  return text.toLowerCase();
}

const accountShown = (account) => account.name || String(account.id || '').split(':').slice(1).join(':');

function sendAccounts(service, accounts) {
  return (accounts || []).filter((account) => account.id && account.protocol === service);
}

/** The account a chat sends from, or null with one account (or none). */
function chatFromAccount(route, accounts) {
  if (!route) return null;
  const same = sendAccounts(route.account ? route.account.split(':')[0] : route.protocol, accounts);
  if (same.length < 2) return null;
  return same.find((account) => account.id === route.account) || (route.account ? null : same[0]);
}

/** "From: alice@example.org" above the composer, or ''. */
function fromLineText(route, accounts) {
  const account = chatFromAccount(route, accounts);
  return account ? 'From: ' + accountShown(account) : '';
}

/** New chat's From: an IRC network typed with the nick, else the account used last, else the newest one. */
function preselectFrom(list, network = '') {
  if (!list.length) return '';
  const typed = network && list.find((account) => String(account.name || '').toLowerCase().endsWith('@' + network.toLowerCase()));
  return (typed || list.find((account) => account.recent) || list[list.length - 1]).id;
}

/** The inbox row's small hint, for a chat on another account than the service's first. */
function inboxAccountHint(route, accounts) {
  const account = chatFromAccount(route, accounts);
  if (!account) return '';
  return account.id === sendAccounts(account.protocol, accounts)[0].id ? '' : accountShown(account);
}
// from-account:end

/** " · from alice@example.org" when a service has more than one account, for chat headers and details. */
function accountVia(route) {
  const account = chatFromAccount(route, chatAccounts);
  return account ? ' · from ' + accountShown(account) : '';
}

/**
 * The composer's "From: alice@example.org ▾" while the chat's service has more than one account. Replies always go
 * out from the chat's own account (the server sends through its link); tapping explains that and offers a new chat.
 */
function updateFromLine() {
  const form = document.getElementById('schedule');
  if (!form) return;
  let line = document.getElementById('from-line');
  const route = !sampleOn && typeof active === 'string' && active && typeof routeFor === 'function' ? routeFor(active) : null;
  const account = route && internet(route.protocol) ? chatFromAccount(route, chatAccounts) : null;
  let info = document.getElementById('from-info');
  if (!account) { if (line) line.hidden = true; if (info) info.hidden = true; return; }
  if (!line) {
    line = element('button', '', 'from-line');
    line.id = 'from-line';
    line.type = 'button';
    line.setAttribute('aria-controls', 'from-info');
    // The note opens in place, under the line, rather than as a pop-up.
    info = element('div', '', 'from-info');
    info.id = 'from-info';
    info.hidden = true;
    line.addEventListener('click', () => {
      info.hidden = !info.hidden;
      line.setAttribute('aria-expanded', String(!info.hidden));
      line.querySelector('.from-caret').textContent = info.hidden ? '▾' : '▴';
    });
    form.querySelector('.compose-row')?.before(line, info);
  }
  const name = accountShown(account);
  line.replaceChildren(element('span', 'From:', 'from-label'), element('strong', name), element('span', info.hidden ? '▾' : '▴', 'from-caret'));
  line.setAttribute('aria-label', 'Sending from ' + name + '. About sending from this account');
  line.setAttribute('aria-expanded', String(!info.hidden));
  const another = element('button', 'New message from another account', 'quiet');
  another.type = 'button';
  another.addEventListener('click', () => { info.hidden = true; openNewChat(); });
  const done = element('button', 'Done', 'quiet');
  done.type = 'button';
  done.addEventListener('click', () => { line.click(); line.focus(); });
  const actions = element('div', '', 'from-actions');
  actions.append(another, done);
  info.replaceChildren(element('p', 'Replies in this chat come from ' + name + '. To write from another account, start a new message.'), actions);
  line.hidden = false;
}

// Messages → Chats | Communities. Communities lists each server with its channels and rooms, like chat rows, under a
// collapsible header with its actions. The same design as the phone (Communities.kt).

const COMMUNITY_SORTS = {recent: 'Recent activity', name: 'Name (A–Z)', joined: 'Date joined'};
let realInboxView = localStorage.getItem('inboxView') === 'communities' ? 'communities' : 'chats';
const realCollapsed = new Set((() => {
  try { return JSON.parse(localStorage.getItem('communityCollapsed') || '[]'); } catch (error) { return []; }
})());
// The sample inbox keeps its own view, sort, and collapsed servers on this page only, so trying them changes nothing of yours.
const sampleCommunity = {view: 'chats', sort: 'recent', collapsed: new Set()};
const inSample = () => typeof sampleOn !== 'undefined' && sampleOn;
const collapsedNow = () => inSample() ? sampleCommunity.collapsed : realCollapsed;
const inboxViewMode = () => inSample() ? sampleCommunity.view : realInboxView;
function setInboxView(value) {
  if (inSample()) { sampleCommunity.view = value; return; }
  realInboxView = value;
  localStorage.setItem('inboxView', value);
}

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
function communitySort() {
  const sort = inSample() ? sampleCommunity.sort : syncedPref('communitySort', 'recent');
  return COMMUNITY_SORTS[sort] ? sort : 'recent';
}

/** A channel or room on a server (not a direct message there, and never in a decoy session). In the sample inbox,
 * only the sample servers' channels and rooms, since serverAccounts and routes are the sample's then. */
function isCommunityChat(key) {
  if (typeof inboxDecoy === 'function' && inboxDecoy()) return false;
  const route = typeof routeFor === 'function' ? routeFor(key) : null;
  return !!route?.group && !!route.account && serverAccounts().some((account) => account.id === route.account);
}

/** Whether Chats lists [key]: community chats only while "Show community chats in Chats" is on. */
const chatsShows = (key, show = showCommunityChats()) => show || !isCommunityChat(key);

/** The switch shows in the inbox and the sample inbox: not in a decoy session or the private folder. */
function communitiesOffered(folder) {
  return !(typeof inboxDecoy === 'function' && inboxDecoy()) && !(typeof PRIVATE_FOLDER !== 'undefined' && folder === PRIVATE_FOLDER);
}

const inboxViewNow = (folder) => communitiesOffered(folder) ? inboxViewMode() : 'chats';

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
    button.setAttribute('aria-pressed', String(inboxViewMode() === value));
    if (value === 'communities' && unread) {
      const badge = element('span', unread > 99 ? '99+' : String(unread), 'segment-badge');
      badge.setAttribute('aria-hidden', 'true');
      button.append(badge);
      button.setAttribute('aria-label', label + ', ' + unread + ' unread');
    }
    button.addEventListener('click', () => {
      if (inboxViewMode() === value) return;
      setInboxView(value);
      showThreads();
    });
    return button;
  };
  holder.replaceChildren(make('chats', 'Chats'), make('communities', 'Communities'));
}

function toggleServer(id) {
  const collapsed = collapsedNow();
  if (collapsed.has(id)) collapsed.delete(id);
  else collapsed.add(id);
  if (!inSample()) localStorage.setItem('communityCollapsed', JSON.stringify([...collapsed]));
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
    empty.append(add, communitiesGuideButton());
    list.append(empty);
    return;
  }
  const bar = element('div', '', 'community-bar');
  const sort = element('select', '');
  sort.setAttribute('aria-label', 'Sort communities');
  for (const [value, label] of Object.entries(COMMUNITY_SORTS)) sort.append(Object.assign(element('option', label), {value}));
  sort.value = communitySort();
  sort.addEventListener('change', () => {
    if (inSample()) { sampleCommunity.sort = sort.value; showThreads(); } else saveSyncedPref('communitySort', sort.value);
  });
  const sortLabel = element('label', '', 'community-sort');
  sortLabel.append(element('span', 'Sort'), sort);
  if (inSample()) bar.prepend(element('span', 'Sample servers · fictional, and nothing is sent', 'community-sample-note'));
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
  const servers = accounts.map((account, index) => ({id: account.id, name: serverName(account),
    order: account.sample ? index : chatAccounts.indexOf(account), account}));
  let shown = 0;
  for (const group of communityGroups(servers, channels, communitySort())) {
    const {account} = group.server;
    // While searching, servers with nothing matching stay out.
    if (query && !group.channels.length) continue;
    shown++;
    const open = !collapsedNow().has(account.id) || !!query;
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
    // "Disconnected from <network> · Reconnect" while it isn't connected (connections.js).
    const connection = typeof connectionBar === 'function' ? connectionBar(account.id, group.server.name) : null;
    if (connection) section.append(connection);
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
    // Disconnect or Connect the whole server (connections.js), as in each of its chats' menus.
    if (typeof setConnected === 'function') {
      const off = connectionOff(account.id);
      action(off ? 'Connect' : 'Disconnect', () => setConnected(account.id, off).catch(showError));
    }
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
      if (!group.channels.length) {
        // Browse is one tap away, not only behind the ⋯ button.
        const find = element('button', 'Find ' + noun + 's to join', 'primary community-find');
        find.type = 'button';
        find.addEventListener('click', () => openBrowse(account));
        body.append(element('p', 'No ' + noun + 's joined yet.', 'placeholder'), find);
      }
      section.append(body);
    }
    list.append(section);
  }
  if (query && !shown) list.append(element('p', 'No channels or rooms match “' + excerpt(query, 40) + '”', 'placeholder'));
  const add = element('button', '+ Add server', 'community-add');
  add.type = 'button';
  add.addEventListener('click', () => openAddServer());
  list.append(add, communitiesGuideButton());
}

/** "How communities work" under Add server: the Communities guide (welcome.js showCommunitiesIntro). */
function communitiesGuideButton() {
  const guide = element('button', 'How communities work', 'link community-guide');
  guide.type = 'button';
  guide.addEventListener('click', () => { if (typeof showCommunitiesIntro === 'function') showCommunitiesIntro(); });
  return guide;
}

/** A server's details on a full page: its account, Browse and Join, and Remove for one added here or on the phone. */
function openServerDetails(account) {
  if (account.sample) { sampleServerPage(account, 'details'); return; }
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
  // Connection (connections.js): its status, Reconnect or Connect, Disconnect, and Reconnect automatically.
  const connection = typeof connectionSection === 'function' ? connectionSection(account.id) : null;
  detailsPage(serverName(account), [section, connection, remove], () => closeDetailsPage());
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
  if (account?.sample || account?.protocol !== 'matrix' || spaceCache[ident] && !refresh) return;
  spaceCache[ident] = [];
  try {
    spaceCache[ident] = (await api('protocols/servers/spaces', {account: ident, refresh})).spaces || [];
    if (spaceCache[ident].length) showThreads();
  } catch (error) { /* Spaces are optional; the server's rooms still show. */ }
}

function serverHint(account) {
  return {irc: '#channel', matrix: '#room:' + (serverName(account) || 'example.org'), xmpp: 'room@conference.' + (account.name.split('@').pop() || 'example.org')}[account.protocol];
}

/** [account]'s IRC connection as the inbox last reported it (connections.js), or null. */
function serverConnection(account) {
  return typeof chatConnections === 'object' && chatConnections ? chatConnections[account.id] || null : null;
}
/** Whether an IRC network is connected right now. Matrix and XMPP answer for themselves when asked. */
const serverConnected = (account) => account.protocol !== 'irc' || serverConnection(account)?.state === 'connected';

/**
 * A channel locked with a password (IRC 475): a password field right under the Join page's status, inline, that joins
 * again with it. The password goes only to the network with the JOIN; it isn't saved.
 */
function channelKeyForm(account, target, title, status) {
  status.parentElement?.querySelector('.channel-key')?.remove();
  const form = element('form', '', 'channel-key');
  const input = Object.assign(element('input', ''), {type: 'password', autocomplete: 'off', maxLength: 100, required: true});
  const go = Object.assign(element('button', 'Join with password', 'primary'), {type: 'submit'});
  form.append(field('Channel password', input, 'Ask someone in ' + target + ' for it. It isn’t saved, so you may need it again after reconnecting.'), go);
  form.addEventListener('submit', async (event) => {
    event.preventDefault();
    go.disabled = true;
    const done = await joinTarget(account, target, title, status, input.value.trim());
    if (!done) go.disabled = false;
  });
  status.after(form);
  input.focus();
}

async function joinTarget(account, target, title, status, key = '') {
  if (account.sample) return sampleJoin(account, target, title, status);
  if (account.protocol === 'irc' && typeof channelName === 'function') {
    // "python", "#python", and " #python " all join #python.
    const channel = channelName(target);
    if (!channel) { status.textContent = 'Type the channel’s name, like #python. It can’t have spaces or commas.'; return false; }
    target = channel;
  }
  status.textContent = 'Joining…';
  try {
    const result = await api('protocols/join', {account: account.id, target, title, ...(key ? {key} : {})});
    if (result.refused) {
      // The network said no, in plain words (irc_client.join_error); a password-locked channel asks for it here.
      status.textContent = result.refused.text;
      if (result.refused.code === '475') channelKeyForm(account, target, title, status);
      else status.parentElement?.querySelector('.channel-key')?.remove();
      return false;
    }
    if (!routes.some((route) => route.address === result.route.address && route.protocol === result.route.protocol)) routes.push(result.route);
    // Joined again after Remove: back out of Archived, where Remove put its earlier messages.
    if (typeof inFolder === 'function' && typeof chatState === 'function' && (chatState().archived || []).includes(result.route.address)) {
      toggleChat(result.route.address, 'archived');
    }
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
  if (account.sample) { sampleServerPage(account, 'join'); return; }
  const form = element('form', '', 'settings-section');
  const target = Object.assign(element('input', ''), {required: true, placeholder: serverHint(account), autocomplete: 'off', spellcheck: false});
  const status = element('p', '', 'muted');
  status.setAttribute('role', 'status');
  const noun = account.protocol === 'irc' ? 'channel' : account.protocol === 'matrix' ? 'room' : 'group chat';
  form.append(element('h3', 'Join a ' + noun + ' on ' + serverName(account)),
    field(account.protocol === 'irc' ? 'Channel' : 'Address', target,
      account.protocol === 'irc' ? 'Type the channel’s name, like #python. You can leave out the #.' : account.protocol === 'matrix'
        ? 'A room address like #room:example.org, or a room ID. Rooms on other homeservers work too.'
        : 'A group chat address like room@conference.example.org.'),
    Object.assign(element('button', 'Join', 'primary'), {type: 'submit'}), status);
  if (!serverConnected(account)) form.append(element('p', 'You’re not connected to ' + serverName(account) + ' right now. You can still join: '
    + 'the channel opens as soon as you’re connected.', 'notice'));
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
function openBrowse(account, service = '', {fresh = false} = {}) {
  if (account.sample) { sampleServerPage(account, 'browse'); return; }
  const section = element('section', '', 'settings-section');
  const irc = account.protocol === 'irc';
  // Well-known channels of a well-known network, shown while the search box is empty and its own list hasn't come.
  const popular = irc && typeof popularChannels === 'function' ? popularChannels(String(account.name).split('@').pop()) : [];
  const search = Object.assign(element('input', ''), {type: 'search', placeholder: account.protocol === 'irc' ? 'Search channels'
    : account.protocol === 'matrix' ? 'Search rooms' : service ? 'Search group chats' : 'Search services', autocomplete: 'off', spellcheck: false});
  search.setAttribute('aria-label', search.placeholder);
  search.setAttribute('aria-controls', 'browse-results');
  const status = element('p', '', 'muted');
  status.setAttribute('role', 'status');
  const results = element('div', '', 'browse-list');
  results.id = 'browse-results';
  const where = account.protocol === 'xmpp' ? (service || 'group chat services') : serverName(account);
  // IRC only: said plainly when the network isn't connected, with a button to connect.
  const offline = element('div', '', 'notice browse-offline');
  offline.setAttribute('role', 'status');
  offline.hidden = true;
  const heading = element('h4', 'Popular here', 'browse-heading');
  heading.hidden = true;
  section.append(element('h3', 'Browse ' + where));
  if (fresh) section.append(element('p', 'Your account is saved. Now pick a '
    + (irc ? 'channel' : account.protocol === 'matrix' ? 'room' : 'group chat') + ' to join.', 'notice'));
  section.append(
    element('p', {irc: 'Channels are the chat rooms on ' + where + '. Type a word to search, like “python” or “music”. '
      + 'Big networks have thousands, so only the 100 busiest matches show.',
      matrix: 'Public rooms in this homeserver’s directory.', xmpp: service ? 'Public group chats on this service.'
        : 'Group chat services on your XMPP server. Pick one to see its group chats.'}[account.protocol], 'muted'),
    search, offline, status, heading, results);
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
        room.members ? room.members + (room.members === 1 ? ' person' : ' people') : '', room.topic].filter(Boolean).join(' · ')
        || (irc ? 'No topic set' : ''), 'muted browse-detail'));
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
    // The search box empty and nothing from the network (yet, or at all): its well-known channels.
    const starter = !query && !list.length ? popular : [];
    heading.hidden = !starter.length;
    // A name typed in full joins even when the list doesn't have it: unlisted channels are common.
    const typed = irc && query && typeof channelName === 'function' && !/\s/.test(query) ? channelName(query) : '';
    if (typed && !list.some((room) => String(room.target).toLowerCase() === typed.toLowerCase())) {
      results.append(row({target: typed, name: typed, topic: 'Not in the list? Join it by name.'}));
    }
    for (const room of [...list, ...starter]) results.append(row(room));
    if (asked === null) return;
    status.textContent = list.length ? list.length + ' found' + (irc && list.length >= 100 ? '. Type a word to narrow it down.' : '')
      : query ? 'Nothing in the list matches “' + query + '”.'
        : irc ? where + ' didn’t send its list. Try again in a minute, or join a channel by name.' : 'Nothing listed.';
  };
  // "You're not connected", in plain words, with Connect. Joining still works: the channel opens once connected.
  const drawOffline = (failed = '') => {
    if (!irc || serverConnected(account)) { offline.hidden = true; return; }
    const state = serverConnection(account)?.state || '';
    const trying = state === 'connecting' && !failed;
    const words = element('span', trying ? 'Connecting to ' + where + '… This takes a few seconds.'
      : failed || 'You’re not connected to ' + where + ' right now.' + (state === 'paused' ? ' FluidMessages is waiting a while before it tries again.' : ''));
    offline.replaceChildren(words, element('small', 'You can still join a channel. It opens as soon as you’re connected.', 'hint'));
    if (!trying) {
      const connect = element('button', 'Connect', 'primary');
      connect.type = 'button';
      connect.addEventListener('click', async () => {
        connect.disabled = true;
        try {
          await api('protocols/reconnect', {account: account.id});
          if (typeof chatConnections === 'object') chatConnections[account.id] = {...(serverConnection(account) || {}), state: 'connecting'};
          drawOffline();
          refresh().catch(() => {});
        } catch (error) { drawOffline(error.message); }
      });
      offline.append(connect);
    }
    offline.hidden = false;
  };
  // The inbox refreshes every few seconds and brings the connection's state; the list loads the moment it's connected.
  let wasConnected = serverConnected(account);
  let wasState = serverConnection(account)?.state || '';
  const watch = irc ? setInterval(() => {
    if (!section.isConnected) { clearInterval(watch); return; }
    const now = serverConnected(account);
    const state = serverConnection(account)?.state || '';
    if (now === wasConnected && state === wasState) return;
    const came = now && !wasConnected;
    wasConnected = now;
    wasState = state;
    drawOffline();
    if (came) run();
  }, 1000) : 0;
  const run = async () => {
    const term = account.protocol === 'xmpp' ? '' : search.value.trim();
    if (asked === term) return draw();
    if (irc && !serverConnected(account)) {
      // Asking now would only wait and fail. The well-known channels show, and Connect is offered.
      status.textContent = '';
      drawOffline();
      return draw();
    }
    const mine = ++request;
    status.textContent = irc ? (term ? 'Searching ' + where + '…' : 'Getting the channel list from ' + where + '… Big networks take a few seconds.')
      : 'Searching ' + where + '…';
    try {
      const reply = await api('protocols/browse', {account: account.id, search: term, limit: 100, ...(service ? {service} : {})});
      if (mine !== request) return;
      asked = term;
      if (reply.services) services = reply.services;
      else found = reply.rooms || [];
      draw();
    } catch (error) {
      if (mine !== request) return;
      // "IRC isn't connected yet" and the like: said plainly, with Connect, instead of the raw error.
      if (irc && /connect/i.test(error.message)) {
        status.textContent = '';
        wasConnected = false;
        drawOffline('You’re not connected to ' + where + ' right now.');
        draw();
      } else status.textContent = error.message;
    }
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
    // "#python" and Enter just joins it.
    const typed = irc && /^[#&]\S+$/.test(search.value.trim()) && typeof channelName === 'function' ? channelName(search.value) : '';
    if (typed) joinTarget(account, typed, '', status);
    else run();
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
  draw();
  drawOffline();
  run();
  search.focus();
}

/**
 * Remove channel (or room, or group chat), in its details, confirmed inline with a second tap: it's left on the server
 * (IRC parts it) and taken out of the list. Earlier messages stay, as Leave always kept them, in Archived; joining
 * again brings the chat back (joinTarget).
 */
function leaveSection(route) {
  if (!route?.group || !route.account || !SERVER_PROTOCOLS.includes(route.protocol)) return null;
  const section = element('section', '', 'settings-section');
  const noun = route.protocol === 'irc' ? 'channel' : route.protocol === 'matrix' ? 'room' : 'group chat';
  const status = element('p', '', 'muted');
  status.setAttribute('role', 'status');
  section.append(element('h3', 'Server'), element('p', labels[route.protocol] + ' · ' + (route.account_name || route.account.split(':').slice(1).join(':')), 'muted'),
    confirmButton('Remove ' + noun, 'Tap again to remove it', async () => {
      try {
        await api('protocols/leave', {address: route.address, protocol: route.protocol});
        const index = routes.findIndex((item) => item.address === route.address && item.protocol === route.protocol);
        if (index >= 0) routes.splice(index, 1);
        if (typeof chatState === 'function' && !(chatState().archived || []).includes(route.address)) toggleChat(route.address, 'archived');
        closeDetailsPage(false);
        await refresh().catch(() => {});
      } catch (error) { status.textContent = error.message; }
    }, 'danger'),
    element('p', 'Leaves it and takes it out of your list. You stop getting its messages. Earlier messages stay in Archived.', 'muted'), status);
  return section;
}

// add-server-steps:start
/**
 * Which page comes next in Add server, as on the phone (AddServerFlow.kt): Add server → the Communities page (only
 * while no IRC, Matrix, or XMPP server is added yet) → Choose a service → its help page (only while that service has
 * no account yet) → the public servers → the form. [added] is the services with an account: 'irc', 'matrix', 'xmpp'.
 */
const ADD_SERVER_SERVICES = ['irc', 'matrix', 'xmpp'];
function addServerIntroDue(added) { return !added.some((service) => ADD_SERVER_SERVICES.includes(String(service).toLowerCase())); }
function addServerGuideDue(protocol, added) { return !added.some((service) => String(service).toLowerCase() === protocol); }
/** Back from the list of servers: the help page when it showed, otherwise Choose a service. */
function addServerListBack(guided) { return guided ? 'guide' : 'services'; }
// add-server-steps:end

/** The services that already have an account here or on the phone. */
function addedServices() {
  return (typeof chatAccounts !== 'undefined' && chatAccounts || []).filter((account) => account.id).map((account) => account.protocol);
}

/**
 * Add server, from Communities, its empty state, or New chat: the Communities page first while no IRC, Matrix, or
 * XMPP server is added yet (welcome.js showCommunitiesIntro), then Choose a service.
 */
function openAddServer() {
  if (inSample()) { sampleServerPage(null, 'add'); return; }
  if (addServerIntroDue(addedServices()) && typeof showCommunitiesIntro === 'function') showCommunitiesIntro({then: () => openChooseService()});
  else openChooseService();
}

/** Add server, Choose a service, on a full page. A service with no account yet opens its help page, any other its servers. */
function openChooseService() {
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
    button.addEventListener('click', () => addServerGuideDue(protocol, addedServices()) ? openServerGuide(protocol) : showServerList(protocol));
    list.append(button);
  }
  section.append(list);
  detailsPage('Add server', [section], () => closeDetailsPage());
}

/**
 * Add server, a service's help page, for a service with no account yet (account-setup.js): Pick a server opens the
 * list of public servers, the example opens the form with it filled in, and "I already know what I'm doing" an empty form.
 */
function openServerGuide(protocol) {
  if (typeof serviceGuide !== 'function') { showServerList(protocol, {guided: true}); return; }
  const back = () => openServerGuide(protocol);
  detailsPage('Add ' + labels[protocol], serviceGuide(protocol, {
    pickServer: () => showServerList(protocol, {guided: true}),
    example: () => showServerForm(protocol, guideExample(protocol).entry, {}, {simple: true, back}),
    expert: () => showServerForm(protocol, null, {}, {back})}), () => openChooseService());
}

/**
 * Add server: well-known public servers for [protocol], searchable, and Other server… for any other. Back goes to the
 * help page when it showed first ([guided]), otherwise to Choose a service; Back from the form returns here.
 */
function showServerList(protocol, {guided = false} = {}) {
  const again = () => showServerList(protocol, {guided});
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
  other.addEventListener('click', () => showServerForm(protocol, null, {}, {back: again}));
  // "What is IRC?" and the rest, for anyone who skipped it: the same help page.
  const about = element('section', '', 'settings-section');
  if (typeof SERVICE_GUIDES === 'object' && SERVICE_GUIDES[protocol] && typeof serviceGuide === 'function') {
    const read = element('button', 'Read the guide', 'link');
    read.type = 'button';
    read.addEventListener('click', () => openServerGuide(protocol));
    about.append(element('h3', 'What is ' + SERVICE_GUIDES[protocol].title + '?'), element('p', SERVICE_GUIDES[protocol].line, 'muted'), read);
  }
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
      row.addEventListener('click', () => showServerForm(protocol, entry, {}, {back: again}));
      list.append(row);
    }
    status.textContent = found.length ? '' : 'No public ' + noun + ' match “' + search.value.trim() + '”. Choose Other server… below.';
  };
  search.addEventListener('input', draw);
  draw();
  detailsPage(SERVICE_TITLES[protocol], about.childElementCount ? [about, section] : [section],
    () => addServerListBack(guided) === 'guide' ? openServerGuide(protocol) : openChooseService());
  search.focus();
}

/**
 * Add server, last step: the sign-in form, pre-filled from the directory [entry], or blank. It fills itself in as you
 * type (account-setup.js); [simple] (from Get started) tucks the rarely needed settings under More options.
 * Once saved, the new server's Browse page opens, so there is something to join right away.
 */
function showServerForm(protocol, entry, preset = {}, {simple = false, back = null} = {}) {
  const done = async (added) => {
    await refresh().catch(() => {});
    const account = added ? serverAccounts().find((item) => item.id === added.id) : null;
    if (account) openBrowse(account, '', {fresh: true});
    else closeDetailsPage(false);
  };
  const intro = element('section', '', 'settings-section');
  if (entry) {
    intro.append(element('h3', entry.name), element('p', entry.about, 'muted'),
      element('p', {irc: 'Server ' + entry.host + ', TLS port ' + entry.port + '.', matrix: 'Homeserver ' + entry.homeserver + '.',
        xmpp: 'Your address ends in @' + entry.domain + '.'}[protocol], 'muted'));
    preset = {...preset, ...{irc: {server: entry.host, port: String(entry.port)}, matrix: {homeserver: entry.homeserver},
      xmpp: {jid: '@' + entry.domain}}[protocol]};
  } else if (simple) {
    intro.append(element('h3', 'Add ' + labels[protocol]), element('p', {
      irc: 'Pick a network and a nickname. That’s all you need.',
      matrix: 'Type your Matrix ID, and the server is filled in for you.',
      xmpp: 'Type your address and password. The server is found from your address.'}[protocol], 'muted'));
  } else {
    intro.append(element('h3', 'Other ' + SERVICE_TITLES[protocol].split(' ').pop()), element('p', {
      irc: 'Pick a network, or choose Another network… and enter its address and TLS port, usually 6697.',
      matrix: 'Enter your Matrix ID. The homeserver is filled in from it; change it if yours is different.',
      xmpp: 'Enter your full XMPP address. The server is found from its domain.'}[protocol], 'muted'));
  }
  if (protocol === 'irc') {
    // TLS is always on: FluidMessages never sends IRC passwords or messages in plain text.
    // A themed status line rather than a greyed-out checkbox, which browsers draw in their own colors.
    const wrap = element('p', '', 'tls-note');
    const words = element('span', '');
    words.append(element('strong', '🔒 Encrypted connection (TLS)'), element('small', 'Always on. FluidMessages doesn’t connect to IRC without it.', 'hint'));
    wrap.append(words);
    intro.append(wrap);
  }
  const form = accountForm(protocol, null, done, {add: true, preset, simple});
  const hint = {matrix: ['user_id', '@you:' + (entry?.name || 'example.org')], xmpp: ['jid', 'you@' + (entry?.domain || 'example.org')]}[protocol];
  const named = hint && form.querySelector('[name="' + hint[0] + '"]');
  if (named) named.placeholder = hint[1];
  detailsPage(entry ? entry.name : simple ? 'Add ' + labels[protocol] : 'Other ' + SERVICE_TITLES[protocol],
    [intro, element('p', SERVER_HELP[protocol], 'muted server-help'), form], back || (() => showServerList(protocol)));
  // The first empty field gets the cursor; an XMPP address starts before "@domain".
  if (typeof focusAccountForm === 'function') focusAccountForm(form);
  else if (protocol === 'xmpp' && entry && named) {
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
    panel.append(element('p', 'The sample servers and their channels are in Messages → Communities. They’re fictional, so nothing here joins '
      + 'or sends. Exit the sample inbox to join channels and rooms on your own servers.', 'muted'));
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
 * it's joined, connected or not, and each IRC network's server chat, so a network you added shows before it says
 * anything. [has] says whether the inbox already has the key.
 */
function emptyServerRows(has) {
  // In the sample inbox these are the sample servers and the rooms joined on this page (sampleJoin).
  const ids = new Set(serverAccounts().map((account) => account.id));
  const keys = routes.filter((route) => (route.group || route.server_chat) && ids.has(route.account))
    .map((route) => threadKey({address: route.address, protocol: route.protocol}));
  return [...new Set(keys)].filter((key) => !has(key)).map((key) => [key, []]);
}

// The sample inbox's servers: two fictional communities with channels and rooms, drawn on this page only. They never
// reach the API: every action above that would (Browse, Join, Server details, Add server, Spaces) checks for
// account.sample first and comes here instead. Fictional names and example addresses only.

const SAMPLE_SERVERS = [
  {id: 'sample:irc', protocol: 'irc', name: 'you@irc.starlight.example', label: 'Starlight IRC', sample: true, source: 'sample'},
  {id: 'sample:matrix', protocol: 'matrix', name: '@you:commons.example.org', label: 'Garden Commons', sample: true, source: 'sample'},
];

// Each server's public directory. joined: in the sample from the start, joined that many minutes ago.
const SAMPLE_DIRECTORY = {
  'sample:irc': [
    {target: '#astronomy', address: 'sample-irc-astronomy', members: 214, topic: 'Clear skies and kind questions', joined: 40000},
    {target: '#telescopes', address: 'sample-irc-telescopes', members: 88, topic: 'Mounts, eyepieces, and collimation', joined: 30000},
    {target: '#meteor-watch', address: 'sample-irc-meteors', members: 41, topic: 'Shower dates and where to look', joined: 9000},
    {target: '#beginners', address: 'sample-irc-beginners', members: 132, topic: 'First scope? Start here'},
    {target: '#astrophotography', address: 'sample-irc-photos', members: 97, topic: 'Stacking, tracking, and dark frames'},
  ],
  'sample:matrix': [
    {target: '#seeds:commons.example.org', name: 'Seed Swap', address: 'sample-mx-seeds', members: 63, topic: 'Trade what you saved', joined: 20000},
    {target: '#allotments:commons.example.org', name: 'Allotment News', address: 'sample-mx-allotments', members: 120,
      topic: 'Plots, water days, and work parties', joined: 50000},
    {target: '#compost:commons.example.org', name: 'Compost Corner', address: 'sample-mx-compost', members: 37, topic: 'Greens, browns, and patience', joined: 15000},
    {target: '#tools:commons.example.org', name: 'Tool Library', address: 'sample-mx-tools', members: 52, topic: 'Borrow a ladder, lend a saw'},
    {target: '#bees:commons.example.org', name: 'Beekeeping', address: 'sample-mx-bees', members: 29, topic: 'Hives, swarms, and honey'},
  ],
};

function sampleRoute(account, room, joined) {
  return {address: room.address, protocol: account.protocol, account: account.id, account_name: account.name, group: true,
    target: room.target, title: room.name || room.target, joined, sample: true};
}

/** The sample servers' joined channels and rooms, as routes, for the sample inbox (chats.js startSample). */
function sampleServerRoutes(now = Date.now()) {
  return SAMPLE_SERVERS.flatMap((account) => SAMPLE_DIRECTORY[account.id].filter((room) => room.joined)
    .map((room) => sampleRoute(account, room, now - room.joined * 60000)));
}

/** Joins a sample channel or room on this page only: it shows under its server, and nothing is sent anywhere. */
function sampleJoin(account, target, title, status) {
  const wanted = String(target || '').trim();
  if (!wanted) { if (status) status.textContent = 'Type a name to join.'; return false; }
  const bare = (value) => String(value || '').toLowerCase().replace(/^[#!]/, '').split(':')[0];
  let room = SAMPLE_DIRECTORY[account.id].find((item) => bare(item.target) === bare(wanted) || bare(item.name) === bare(wanted));
  if (!room) {
    const word = bare(wanted).replace(/[^a-z0-9-]/g, '-').slice(0, 40) || 'room';
    room = account.protocol === 'irc' ? {target: '#' + word, address: 'sample-made-irc-' + word}
      : {target: '#' + word + ':commons.example.org', name: title || wanted.replace(/^#/, '').split(':')[0], address: 'sample-made-matrix-' + word};
  }
  let route = routes.find((item) => item.account === account.id && item.address === room.address);
  if (!route) {
    route = sampleRoute(account, room, Date.now());
    routes.push(route);
    inbox = [{id: sampleId(), address: route.address, protocol: route.protocol, title: route.title, type: 2, date: Date.now(), read: 1, event: true,
      body: 'You joined ' + route.title + ' · sample, nothing was sent'}, ...inbox];
    rebuildThreads();
    say('Sample server · joined on this page only. Nothing was sent.');
  }
  openRoute(route);
  return true;
}

/** Browse, Join, Server details, and Add server for the sample servers, each saying plainly that it's a sample. */
function sampleServerPage(account, mode) {
  const note = (text) => element('p', text, 'notice sample-server-note');
  if (mode === 'add') {
    const section = element('section', '', 'settings-section');
    const exit = element('button', 'Exit the sample inbox', 'primary');
    exit.type = 'button';
    exit.addEventListener('click', () => exitSample());
    section.append(element('h3', 'Add server'), note('This is the sample inbox, so its servers are fictional. Exit it to add an IRC network, '
      + 'Matrix homeserver, or XMPP server of your own. They show in Messages → Communities with their channels and rooms.'), exit);
    detailsPage('Add server', [section], () => closeDetailsPage());
    return;
  }
  const name = serverName(account);
  const noun = account.protocol === 'irc' ? 'channel' : 'room';
  const browse = () => sampleServerPage(account, 'browse');
  const join = () => sampleServerPage(account, 'join');
  if (mode === 'details') {
    const section = element('section', '', 'settings-section');
    const joined = routes.filter((route) => route.account === account.id && route.group).length;
    const row = element('div', '', 'button-row');
    const browseButton = element('button', 'Browse', 'primary');
    const joinButton = element('button', 'Join by name');
    browseButton.type = joinButton.type = 'button';
    browseButton.addEventListener('click', browse);
    joinButton.addEventListener('click', join);
    row.append(browseButton, joinButton);
    section.append(element('h3', name), element('p', 'Sample ' + SERVICE_TITLES[account.protocol] + ' · signed in as ' + account.name, 'muted'),
      element('p', joined + ' ' + noun + 's joined.', 'muted'), row);
    if (typeof serverMuteControls === 'function') section.append(...serverMuteControls(account.id, name));
    const remove = element('section', '', 'settings-section');
    remove.append(element('h3', 'Remove server'), element('p', 'A sample server: there’s nothing to sign out of. It goes away when you exit the sample inbox.', 'muted'));
    detailsPage(name, [section, remove], () => closeDetailsPage());
    return;
  }
  if (mode === 'join') {
    const form = element('form', '', 'settings-section');
    const target = Object.assign(element('input', ''), {required: true, placeholder: account.protocol === 'irc' ? '#beginners' : '#tools:commons.example.org',
      autocomplete: 'off', spellcheck: false});
    const status = element('p', '', 'muted');
    status.setAttribute('role', 'status');
    form.append(element('h3', 'Join a ' + noun + ' on ' + name),
      note('Sample server: joining adds the ' + noun + ' to this page only. Nothing is sent, and no server is contacted.'),
      field(account.protocol === 'irc' ? 'Channel' : 'Address', target), Object.assign(element('button', 'Join', 'primary'), {type: 'submit'}), status);
    form.addEventListener('submit', (event) => { event.preventDefault(); sampleJoin(account, target.value, '', status); });
    const find = element('button', 'Browse ' + name + ' instead', 'quiet');
    find.type = 'button';
    find.addEventListener('click', browse);
    detailsPage('Join a ' + noun, [form, find], () => closeDetailsPage());
    target.focus();
    return;
  }
  const section = element('section', '', 'settings-section');
  const search = Object.assign(element('input', ''), {type: 'search', placeholder: 'Search ' + noun + 's', autocomplete: 'off', spellcheck: false});
  search.setAttribute('aria-label', search.placeholder);
  const results = element('div', '', 'browse-list');
  const status = element('p', '', 'muted');
  status.setAttribute('role', 'status');
  const draw = () => {
    results.replaceChildren();
    const query = search.value.trim().toLowerCase();
    const list = SAMPLE_DIRECTORY[account.id].filter((room) => !query
      || [room.name, room.target, room.topic].some((text) => (text || '').toLowerCase().includes(query)));
    for (const room of list) {
      const item = element('div', '', 'browse-row');
      const words = element('span', '', 'account-words');
      words.append(element('strong', room.name || room.target), element('span', [room.name ? room.target : '', room.members + ' people', room.topic]
        .filter(Boolean).join(' · '), 'muted browse-detail'));
      item.append(words);
      const route = routes.find((other) => other.account === account.id && other.address === room.address);
      if (route) item.append(element('span', 'Joined', 'browse-joined'));
      const button = element('button', route ? 'Open' : 'Join', route ? '' : 'primary');
      button.type = 'button';
      button.setAttribute('aria-label', (route ? 'Open ' : 'Join ') + (room.name || room.target));
      button.addEventListener('click', () => (route ? openRoute(route) : sampleJoin(account, room.target, room.name, status)));
      item.append(button);
      results.append(item);
    }
    status.textContent = list.length ? list.length + ' found' : 'Nothing matches “' + search.value.trim() + '”.';
  };
  search.addEventListener('input', draw);
  section.append(element('h3', 'Browse ' + name), note('A sample directory of fictional ' + noun + 's. Joining one adds it to this page only; '
    + 'nothing is sent, and no server is contacted.'), search, status, results);
  draw();
  const joinInstead = element('button', 'Join by name instead', 'quiet');
  joinInstead.type = 'button';
  joinInstead.addEventListener('click', join);
  detailsPage('Browse', [section, joinInstead], () => closeDetailsPage());
  search.focus();
}

// join-first:start (plain rules with no page in them; scripts/test-local runs this part in Node)
/** An IRC name folded the way IRC compares them ([]\~ are the capitals of {}|^), like irc_client.fold. */
const ircFold = (name) => String(name || '').toLowerCase().replace(/\[/g, '{').replace(/]/g, '}').replace(/\\/g, '|').replace(/~/g, '^');

/** The other joined channels on [route]'s network that need it joined first (their join_first lists name it). */
function channelNeededBy(route, all) {
  if (!route || route.protocol !== 'irc' || !route.group) return [];
  return all.filter((other) => other !== route && other.protocol === 'irc' && other.group && other.account === route.account
    && ircFold(other.target) !== ircFold(route.target) && (other.join_first || []).some((name) => ircFold(name) === ircFold(route.target)));
}

/** "#lobby is needed to stay in #chat on this network." */
function channelNeededText(target, needs) {
  const names = needs.map((route) => route.target);
  const list = names.length < 3 ? names.join(' and ') : names.slice(0, -1).join(', ') + ', and ' + names.at(-1);
  return target + ' is needed to stay in ' + list + ' on this network.';
}
// join-first:end

/**
 * Communities: right-click or long-press an IRC channel for "Leave and remove channel", confirmed inline in the same
 * row of choices (no pop-up). It uses the same Remove channel as the channel's details (leaveSection). A channel
 * another joined channel needs first can't be removed alone: it says which, and offers to remove them together.
 */
async function removeChannels(list, status) {
  try {
    for (const route of list) {
      await api('protocols/leave', {address: route.address, protocol: route.protocol});
      const index = routes.findIndex((item) => item.address === route.address && item.protocol === route.protocol);
      if (index >= 0) routes.splice(index, 1);
      if (typeof chatState === 'function' && !(chatState().archived || []).includes(route.address)) toggleChat(route.address, 'archived');
    }
    await refresh().catch(() => {});
    return true;
  } catch (error) {
    if (status) status.textContent = error.message;
    return false;
  }
}

if (typeof threadMenu === 'function') {
  const plainThreadMenu = threadMenu;
  // eslint-disable-next-line no-func-assign
  threadMenu = function (key, button) {
    plainThreadMenu(key, button);
    const route = typeof routeFor === 'function' ? routeFor(key) : null;
    const row = document.querySelector('.thread-actions');
    if (!row || !route || route.protocol !== 'irc' || !route.group || !route.account || route.server_chat || inSample()) return;
    const leave = element('button', 'Leave and remove channel');
    leave.type = 'button';
    leave.addEventListener('click', () => {
      const needs = channelNeededBy(route, routes);
      const status = element('p', '', 'muted');
      status.setAttribute('role', 'status');
      const keep = element('button', needs.length ? 'Keep them' : 'Cancel');
      keep.type = 'button';
      keep.addEventListener('click', () => { row.remove(); button.focus(); });
      const words = needs.length
        ? channelNeededText(route.target, needs) + ' Removing it means leaving ' + (needs.length === 1 ? 'that channel' : 'those channels') + ' too.'
        : 'Leave ' + route.target + ' and take it out of your list? You stop getting its messages. Earlier messages stay in Archived.';
      const go = element('button', needs.length ? 'Remove all ' + (needs.length + 1) : 'Remove channel', 'danger');
      go.type = 'button';
      go.addEventListener('click', async () => {
        go.disabled = true;
        // The channels that need it go first, so none is left behind without what it needs.
        if (!await removeChannels([...needs, route], status)) go.disabled = false;
      });
      row.replaceChildren(element('p', words), go, keep, status);
      go.focus();
    });
    row.querySelector('button:last-of-type')?.before(leave);
  };
}
