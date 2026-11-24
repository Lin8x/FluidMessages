'use strict';
// Servers, like Discord's: each IRC network, Matrix account (and its Spaces), and XMPP account you're connected to.
// Picking one in the strip at the top of the inbox shows only its channels or rooms, then its direct messages.
// From a server you can join a channel or room by name, browse the server's own directory (IRC LIST, the Matrix
// public room directory, XMPP service discovery), and add another server. Leave is in a channel's details.
// Loaded after app.js, companion.js, and chats.js, and uses their helpers (api, element, routes, chatAccounts).

const SERVER_PROTOCOLS = ['irc', 'matrix', 'xmpp'];
// Well-known public IRC networks, offered when adding one. Nothing connects until you save.
const IRC_NETWORKS = [['Libera.Chat', 'irc.libera.chat', 'Free and open-source projects'], ['OFTC', 'irc.oftc.net', 'Debian, Tor, and other projects']];
const SERVER_HELP = {
  irc: 'Like a Discord server: a network such as Libera.Chat with many channels. Each network is its own account.',
  matrix: 'Your Matrix account lives on a homeserver. Its rooms (and Spaces, which group rooms like a Discord server) can be on any homeserver.',
  xmpp: 'Your XMPP account lives on one server. Group chats live on group chat services such as conference.example.org.',
  email: 'Each mailbox is its own account, such as a work and a personal address.',
};
let activeServer = '';  // '' is All; otherwise an account id, or 'space:<account id>:<space id>'.
const spaceCache = {};

function serverAccounts() {
  return (chatAccounts || []).filter((account) => SERVER_PROTOCOLS.includes(account.protocol) && account.id);
}

/** The short name of a server: the IRC network, the homeserver, or the XMPP domain. */
function serverName(account) {
  const name = account.name || '';
  const host = account.protocol === 'irc' ? name.split('@').pop() : account.protocol === 'matrix' ? name.split(':').slice(1).join(':')
    : name.split('@').pop();
  const known = IRC_NETWORKS.find(([, address]) => address === host);
  return known ? known[0] : host || labels[account.protocol];
}

/** " · via alice@example.org" when a service has more than one account, for chat headers and details. */
function accountVia(route) {
  if (!route?.account) return '';
  const same = (chatAccounts || []).filter((account) => account.id && account.protocol === route.account.split(':')[0]);
  return same.length > 1 ? ' · via ' + (route.account_name || route.account.split(':').slice(1).join(':')) : '';
}

function serverOf(key) {
  return (typeof routeFor === 'function' ? routeFor(key) : null)?.account || '';
}

/** Whether the inbox shows [key] with the chosen server. */
function serverShows(key) {
  if (!activeServer) return true;
  const route = typeof routeFor === 'function' ? routeFor(key) : null;
  if (activeServer.startsWith('space:')) {
    const [, account, space] = activeServer.match(/^space:(.+):(![^:]+:.+)$/) || [];
    const rooms = (spaceCache[account] || []).find((item) => item.id === space)?.rooms || [];
    return !!route && route.account === account && rooms.some((room) => room.target === route.target);
  }
  return !!route && route.account === activeServer;
}

/** Channels and rooms first, then direct messages, like a Discord server. */
function serverOrder([a], [b]) {
  if (!activeServer) return 0;
  return Number(!threadIsGroup(a)) - Number(!threadIsGroup(b));
}

/** The heading above the first channel and the first direct message of a server. */
function serverHeading(list, key, previous) {
  if (!activeServer) return;
  const group = threadIsGroup(key);
  if (previous !== null && threadIsGroup(previous) === group) return;
  const account = serverAccounts().find((item) => item.id === activeServer);
  list.append(element('h3', group ? (account?.protocol === 'irc' ? 'Channels' : 'Rooms') : 'Direct messages', 'server-heading'));
}

/** The strip of servers at the top of the inbox, and the chosen server's actions. */
function serverStrip(list) {
  const accounts = serverAccounts();
  if (!accounts.length || get('folder').value !== 'Inbox') {
    activeServer = '';
    return;
  }
  if (activeServer && !accounts.some((item) => activeServer === item.id || activeServer.startsWith('space:' + item.id + ':'))) activeServer = '';
  const strip = element('div', '', 'server-strip');
  strip.setAttribute('role', 'toolbar');
  strip.setAttribute('aria-label', 'Servers');
  const chip = (label, value, badge, title) => {
    const button = element('button', '', 'server-chip' + (badge ? ' p-' + badge : ''));
    button.type = 'button';
    if (badge) button.append(element('span', labels[badge][0], 'server-badge'));
    button.append(element('span', label));
    button.title = title || label;
    button.setAttribute('aria-pressed', String(activeServer === value));
    button.addEventListener('click', () => {
      activeServer = value;
      if (value && !value.startsWith('space:')) loadSpaces(value);
      showThreads();
    });
    strip.append(button);
  };
  chip('All', '');
  for (const account of accounts) {
    chip(serverName(account), account.id, account.protocol, labels[account.protocol] + ' · ' + account.name);
    for (const space of spaceCache[account.id] || []) chip(space.name, 'space:' + account.id + ':' + space.id, '', 'Matrix Space on ' + serverName(account));
  }
  const add = element('button', '+', 'server-chip server-add');
  add.type = 'button';
  add.title = 'Add server';
  add.setAttribute('aria-label', 'Add server');
  add.addEventListener('click', () => openAddServer());
  strip.append(add);
  list.append(strip);
  const account = accounts.find((item) => item.id === activeServer || activeServer.startsWith('space:' + item.id + ':'));
  if (!account) return;
  const bar = element('div', '', 'server-bar');
  const words = element('span', '', 'server-words');
  words.append(element('strong', serverName(account)), element('small', labels[account.protocol] + ' · ' + account.name));
  const join = element('button', account.protocol === 'irc' ? 'Join a channel' : account.protocol === 'matrix' ? 'Join a room' : 'Join a group chat', 'primary');
  const browse = element('button', 'Browse');
  join.type = browse.type = 'button';
  join.addEventListener('click', () => openJoin(account));
  browse.addEventListener('click', () => openBrowse(account));
  bar.append(words, join, browse);
  list.append(bar);
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

/** The server's own directory: IRC LIST, the Matrix public room directory, or XMPP service discovery. */
function openBrowse(account, service = '') {
  const form = element('form', '', 'settings-section');
  const search = Object.assign(element('input', ''), {type: 'search', placeholder: 'Search', autocomplete: 'off'});
  const status = element('p', '', 'muted');
  status.setAttribute('role', 'status');
  const results = element('div', '', 'browse-list');
  const where = account.protocol === 'xmpp' ? (service || 'group chat services') : serverName(account);
  form.append(element('h3', 'Browse ' + where),
    element('p', {irc: 'The network’s public channels, busiest first. Big networks have thousands, so only the first 100 matches are shown.',
      matrix: 'Public rooms in this homeserver’s directory.', xmpp: service ? 'Public group chats on this service.'
        : 'Group chat services on your XMPP server. Pick one to see its group chats.'}[account.protocol], 'muted'),
    field('Search', search), Object.assign(element('button', 'Search', 'primary'), {type: 'submit'}), status, results);
  const row = (room) => {
    const item = element('div', '', 'browse-row');
    const words = element('span', '', 'account-words');
    words.append(element('strong', room.name || room.target),
      element('span', [room.alias || (room.name !== room.target ? room.target : ''), room.members ? room.members + ' people' : '', room.topic]
        .filter(Boolean).join(' · '), 'muted'));
    const join = element('button', room.joined ? 'Joined' : 'Join', room.joined ? 'quiet' : '');
    join.type = 'button';
    join.disabled = !!room.joined;
    join.setAttribute('aria-label', (room.joined ? 'Already joined ' : 'Join ') + (room.name || room.target));
    join.addEventListener('click', () => joinTarget(account, room.alias || room.target, room.name, status));
    item.append(words, join);
    return item;
  };
  const run = async () => {
    status.textContent = 'Looking…';
    results.replaceChildren();
    try {
      const found = await api('protocols/browse', {account: account.id, search: search.value.trim(), limit: 100, ...(service ? {service} : {})});
      if (found.services) {
        for (const jid of found.services) {
          const open = element('button', jid, 'settings-row');
          open.type = 'button';
          open.addEventListener('click', () => openBrowse(account, jid));
          results.append(open);
        }
        status.textContent = found.services.length ? '' : 'Your server didn’t list a group chat service. Join one by address instead.';
        return;
      }
      for (const room of found.rooms) results.append(row(room));
      status.textContent = found.rooms.length ? found.rooms.length + ' found' : 'Nothing matches.';
    } catch (error) { status.textContent = error.message; }
  };
  form.addEventListener('submit', (event) => { event.preventDefault(); run(); });
  const nodes = [form];
  if (account.protocol === 'matrix') {
    // Rooms in your Spaces, with the ones you haven't joined yet.
    const spaces = element('section', '', 'settings-section');
    spaces.append(element('h3', 'Your Spaces'));
    const draw = () => {
      spaces.replaceChildren(element('h3', 'Your Spaces'));
      const list = spaceCache[account.id] || [];
      if (!list.length) spaces.append(element('p', 'No Spaces on this account. Spaces group rooms the way a Discord server does.', 'muted'));
      const joined = new Set(routes.filter((route) => route.account === account.id).map((route) => route.target));
      for (const space of list) {
        spaces.append(element('h4', space.name));
        for (const room of space.rooms) spaces.append(row({...room, joined: joined.has(room.target)}));
      }
    };
    loadSpaces(account.id, true).then(draw);
    draw();
    nodes.push(spaces);
  }
  detailsPage('Browse', nodes, service ? () => openBrowse(account) : () => closeDetailsPage());
  if (account.protocol !== 'irc' || service) run();
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

/** Add server: pick a service, then fill in the account, on a full page. */
function openAddServer() {
  const section = element('section', '', 'settings-section');
  section.append(element('h3', 'Add server'), element('p', 'Connect another IRC network, Matrix homeserver, or XMPP account. Each one shows up in the strip above your chats.', 'muted'));
  for (const protocol of SERVER_PROTOCOLS) {
    const button = element('button', '', 'settings-row');
    button.type = 'button';
    const words = element('span', '', 'account-words');
    words.append(element('strong', {irc: 'IRC network', matrix: 'Matrix homeserver', xmpp: 'XMPP account'}[protocol]), element('span', SERVER_HELP[protocol], 'muted'));
    button.append(element('span', labels[protocol][0], 'account-badge p-' + protocol), words);
    button.addEventListener('click', () => showServerForm(protocol));
    section.append(button);
  }
  detailsPage('Add server', [section], () => closeDetailsPage());
}

function showServerForm(protocol, preset = {}) {
  const done = () => { closeDetailsPage(false); refresh().catch(() => {}); };
  const nodes = [element('p', SERVER_HELP[protocol], 'muted server-help')];
  if (protocol === 'irc') {
    const picks = element('section', '', 'settings-section');
    picks.append(element('h3', 'Popular networks'), element('p', 'Picking one fills in its address. Nothing connects until you save.', 'muted'));
    for (const [name, host, about] of IRC_NETWORKS) {
      const pick = element('button', '', 'settings-row');
      pick.type = 'button';
      const words = element('span', '', 'account-words');
      words.append(element('strong', name), element('span', host + ' · ' + about, 'muted'));
      pick.append(words);
      pick.addEventListener('click', () => showServerForm('irc', {server: host, port: '6697'}));
      picks.append(pick);
    }
    nodes.push(picks);
  }
  nodes.push(accountForm(protocol, null, done, {add: true, preset}));
  detailsPage({irc: 'IRC network', matrix: 'Matrix homeserver', xmpp: 'XMPP account', email: 'Email account'}[protocol], nodes, () => openAddServer());
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
  for (const account of accounts) {
    const group = element('section', '', 'channel-group');
    const head = element('div', '', 'channel-group-head');
    const badge = element('span', labels[account.protocol][0], 'account-badge p-' + account.protocol);
    badge.setAttribute('aria-hidden', 'true');
    const words = element('span', '', 'account-words');
    const name = element('h3', serverName(account));
    words.append(name, element('span', labels[account.protocol] + ' · ' + account.name, 'muted'));
    head.append(badge, words);
    group.append(head);
    const noun = account.protocol === 'irc' ? 'channels' : account.protocol === 'matrix' ? 'rooms' : 'group chats';
    const joined = routes.filter((route) => route.account === account.id && route.group)
      .sort((a, b) => String(a.title || a.target).localeCompare(String(b.title || b.target)));
    const list = element('div', '', 'channel-list');
    list.setAttribute('aria-label', serverName(account) + ' ' + noun);
    for (const route of joined) {
      const open = element('button', '', 'settings-row channel-row');
      open.type = 'button';
      const label = element('span', '', 'account-words');
      label.append(element('strong', route.title || route.target), element('span', route.title && route.title !== route.target ? route.target : labels[route.protocol], 'muted'));
      open.append(label);
      open.addEventListener('click', () => {
        sheet.close();
        select(threadKey({address: route.address, protocol: route.protocol}));
        get('body').focus();
      });
      list.append(open);
    }
    if (!joined.length) list.append(element('p', 'No ' + noun + ' joined on ' + serverName(account) + ' yet.', 'muted'));
    const join = element('button', 'Join or browse…');
    join.type = 'button';
    join.setAttribute('aria-label', 'Join or browse ' + noun + ' on ' + serverName(account));
    join.addEventListener('click', leave(() => openJoin(account)));
    group.append(list, join);
    panel.append(group);
  }
  addServer.className = '';
  panel.append(addServer);
  return panel;
}
