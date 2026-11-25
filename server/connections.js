'use strict';
// IRC, Matrix, and XMPP connections from FluidMessages Web: a plug beside the tag of every IRC, Matrix, and XMPP chat in
// the inbox (crossed out while not connected), a "Not connected to <network> · Reconnect" bar above that network's chats
// and on its Communities header, a Connection section in each such chat's details and on each server's details page
// (what's happening in words, Reconnect or Connect, Disconnect, Reconnect automatically, and Rejoin automatically for
// channels), Disconnect or Connect in each chat's menu, and a Connections page from the inbox's filter menu with every
// server. Disconnect turns Reconnect automatically off, so nothing connects again until you tap Connect, also after a
// restart; the chats stay in your list. Otherwise the server reconnects on its own (15 s, 30 s, 1, 2, 5, then 10
// minutes, at most five times an hour, so the network never sees a reconnect flood); Reconnect tries again at once.
// Channels that aren't kept joined rejoin when you open them.
// Loaded after app.js, companion.js, chats.js, servers.js, privacy.js, and chat-commands.js, and uses their helpers.

let chatConnections = {};

// connection-state:start (scripts/test-local runs this block on its own)
/** The bar's words: why it isn't connected (the network's reason, in plain words) and what happens next. */
function connectionWords(status, network) {
  const retry = status.retry_at ? new Date(status.retry_at).toLocaleTimeString([], {hour: 'numeric', minute: '2-digit'}) : '';
  const why = status.error ? ' · ' + status.error : '';
  if (status.state === 'off') return 'Not connected to ' + network + ' · This service is turned off on FluidMessages Web';
  if (status.manual && status.state !== 'connected' && status.state !== 'connecting') {
    return 'Not connected to ' + network + ' · Turned off until you connect' + why;
  }
  if (status.state === 'paused') return 'Disconnected from ' + network + ' · Waiting until ' + retry + ' to avoid reconnecting too often';
  if (status.state === 'reconnecting') return 'Disconnected from ' + network + why + (retry ? ' · Trying again at ' + retry : '');
  if (status.state === 'disconnected') return 'Disconnected from ' + network + (why || ' · Stopped after 5 tries this hour');
  if (status.state === 'connecting') return 'Connecting to ' + network + '…';
  if (status.state === 'connected') return 'Connected to ' + network;
  return 'Not connected to ' + network;
}

/**
 * The inbox mark's state, as on the phone (IrcRules.connectionState): connected, connecting, off (turned off by you
 * or on the server), or disconnected. Nothing known yet counts as not connected, so a chat never shows no mark.
 */
function connectionState(state, manual) {
  if (state === 'connected') return 'connected';
  if (state === 'connecting' || state === 'reconnecting') return 'connecting';
  if (state === 'off' || manual) return 'off';
  return 'disconnected';
}

const CONNECTION_LABELS = {connected: 'Connected', connecting: 'Connecting', disconnected: 'Not connected', off: 'Turned off'};

/** The Connection section's first line, as on the phone (IrcRules.connectionSummary). */
function connectionSummary(status, network, nick) {
  const state = connectionState(status.state || '', status.manual);
  const why = status.error ? ' · ' + status.error : '';
  if (state === 'connected') return 'Connected to ' + network + (nick ? ' as ' + nick : '');
  if (state === 'connecting') return 'Connecting to ' + network + '…';
  if (status.state === 'off') return 'Turned off · Turn this service on in Settings → Chat services';
  if (state === 'off') return 'Turned off · Tap Connect to connect to ' + network + why;
  return 'Not connected to ' + network + why;
}

/** The sample inbox's marks: the IRC network connected and the Matrix server not, so both looks show. Fictional. */
const SAMPLE_CONNECTIONS = {'sample:irc': 'connected', 'sample:matrix': 'disconnected'};
// connection-state:end

const onSample = () => typeof sampleOn !== 'undefined' && sampleOn;

/** A chat's server account id ("irc:kit@irc.example.org"), or '' for chats with no live connection. */
function chatServerAccount(key) {
  const route = key && typeof routeFor === 'function' ? routeFor(key) : null;
  if (!route || !['irc', 'matrix', 'xmpp'].includes(route.protocol) || !route.account) return '';
  return route.account;
}

/** A chat's connection mark state, from its server's connection; '' only for chats with none (texts, email). */
function chatConnection(key) {
  const account = chatServerAccount(key);
  if (!account) return '';
  if (onSample()) return SAMPLE_CONNECTIONS[routeFor(key).address] || SAMPLE_CONNECTIONS[account] || 'connected';
  const status = chatConnections[account];
  return connectionState(status?.state || '', status?.manual);
}

/** A server's connection status ({state, error, manual, …}); the sample's are pretend. */
function accountConnection(id) {
  if (onSample()) {
    const state = SAMPLE_CONNECTIONS[id] || 'connected';
    return state === 'off' ? {state: 'disconnected', manual: true} : {state};
  }
  return chatConnections[id] || {state: ''};
}

/**
 * The mark beside a chat's tag, in the same place, size, and line style as the muted bell: a plug while connected
 * (green) or connecting (gray), and the plug crossed out while not connected (red) or turned off (gray). The shape
 * tells them apart without color, and it reads out as text. Never a dot.
 */
function connectionMark(state) {
  const mark = element('span', '', 'connection-mark ' + state);
  mark.setAttribute('role', 'img');
  mark.setAttribute('aria-label', CONNECTION_LABELS[state]);
  mark.title = CONNECTION_LABELS[state];
  const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
  svg.setAttribute('viewBox', '0 0 16 16');
  svg.setAttribute('aria-hidden', 'true');
  const paths = ['M6 2v3.2M10 2v3.2', 'M4.4 5.2h7.2v2.6a3.6 3.6 0 0 1-7.2 0z', 'M8 11.4V14'];
  if (state === 'disconnected' || state === 'off') paths.push('M2.5 2.5l11 11');
  for (const d of paths) {
    const path = document.createElementNS('http://www.w3.org/2000/svg', 'path');
    for (const [name, value] of [['d', d], ['fill', 'none'], ['stroke', 'currentColor'], ['stroke-width', '1.5'],
      ['stroke-linecap', 'round'], ['stroke-linejoin', 'round']]) path.setAttribute(name, value);
    svg.append(path);
  }
  mark.append(svg);
  return mark;
}

/** Every IRC network and Matrix and XMPP account (the sample's own in the sample inbox). */
function connectionServers() {
  if (typeof serverAccounts === 'function') return serverAccounts();
  return (typeof chatAccounts !== 'undefined' ? chatAccounts : []).filter((item) => ['irc', 'matrix', 'xmpp'].includes(item.protocol) && item.id);
}

function connectionAccount(id) {
  return connectionServers().find((item) => item.id === id)
    || (typeof chatAccounts !== 'undefined' ? chatAccounts : []).find((item) => item.id === id) || null;
}

function connectionName(id) {
  const account = connectionAccount(id);
  return account && typeof serverName === 'function' ? serverName(account) : (id.split('@').pop() || id);
}

/** Your nickname on an IRC network ("kit"), for "Connected to Libera.Chat as kit"; '' for other services. */
function connectionNick(id) {
  return id.startsWith('irc:') ? id.slice(4).split('@')[0] : '';
}

/** Whether you (or the server) turned a connection off, so it offers Connect instead of Disconnect. */
function connectionOff(id) {
  const status = accountConnection(id);
  if (onSample()) return !!status.manual;
  return status.state === 'off' || connectionAccount(id)?.reconnect === false;
}

/** After Connect, Disconnect, or Reconnect: the inbox marks, the bar, and any open Connection section update. */
function connectionChanged() {
  if (typeof showThreads === 'function') showThreads();
  showChatConnection();
  for (const node of document.querySelectorAll('[data-connection]')) node.dispatchEvent(new Event('connection-redraw'));
}

/** Connect (on) or Disconnect (off) a server, and remember it: Reconnect automatically follows, also after a restart. */
async function setConnected(id, on) {
  if (onSample()) {
    sampleServerSet(id, on ? 'connected' : 'off');
    return;
  }
  await api(on ? 'protocols/autoreconnect' : 'protocols/disconnect', on ? {account: id, on: true} : {account: id});
  const account = connectionAccount(id);
  if (account) account.reconnect = on;
  chatConnections[id] = {...(chatConnections[id] || {}), state: on ? 'connecting' : 'disconnected', manual: !on, error: '', retry_at: 0};
  connectionChanged();
}

/** The sample's pretend Connect, Disconnect, and Reconnect: the whole server, its channels included. */
function sampleServerSet(id, state) {
  SAMPLE_CONNECTIONS[id] = state;
  for (const route of (typeof routes !== 'undefined' ? routes : []).filter((item) => item.account === id)) delete SAMPLE_CONNECTIONS[route.address];
  connectionChanged();
}

async function reconnectServer(id) {
  if (onSample()) { sampleServerSet(id, 'connected'); return; }
  await api('protocols/reconnect', {account: id});
  chatConnections[id] = {...(chatConnections[id] || {}), state: 'connecting', manual: false, error: ''};
  connectionChanged();
}

/** A switch row: a checkbox drawn as a switch, read out as one. */
function switchRow(label, checked, change, hint) {
  const row = element('label', '', 'check-row switch-row');
  const box = Object.assign(element('input', ''), {type: 'checkbox', checked});
  box.setAttribute('role', 'switch');
  const words = element('span', '');
  words.append(element('span', label));
  if (hint) words.append(element('small', hint, 'hint'));
  row.append(words, box);
  box.addEventListener('change', () => change(box.checked, box));
  return row;
}

/** The bar for one network's account, or null while it's connected or connecting (or not on this server at all). */
function connectionBar(account, network) {
  if (onSample() || !account) return null;
  const status = chatConnections[account] || {state: ''};
  const state = connectionState(status.state, status.manual);
  if (state === 'connected' || state === 'connecting') return null;
  const bar = element('div', '', 'connection-bar');
  bar.setAttribute('role', 'status');
  const words = element('span', connectionWords(status, network || status.network || 'the network'));
  bar.append(connectionMark(state), words);
  if (status.state !== 'off') {
    const manual = connectionOff(account);
    const button = element('button', manual ? 'Connect' : 'Reconnect');
    button.type = 'button';
    button.addEventListener('click', async () => {
      button.disabled = true;
      try {
        if (manual) await setConnected(account, true); else await reconnectServer(account);
      } catch (error) { showError(error); button.disabled = false; }
    });
    bar.append(button);
  }
  return bar;
}

/** The bar above the open chat, when its network isn't connected. */
function showChatConnection() {
  let slot = document.getElementById('connection-slot');
  if (!slot) {
    slot = element('div', '', 'connection-slot');
    slot.id = 'connection-slot';
    get('conversation').before(slot);
  }
  const account = active && internet(threadProtocol(active)) ? chatServerAccount(active) : '';
  const bar = account ? connectionBar(account, connectionName(account)) : null;
  slot.replaceChildren(...(bar ? [bar] : []));
  // The chat's header: the crossed-out plug beside its protocol line while not connected (as in the inbox).
  let head = document.getElementById('head-connection');
  if (!head) {
    head = element('span', '', 'head-connection');
    head.id = 'head-connection';
    get('transport')?.after(head);
  }
  const state = account ? chatConnection(active) : '';
  head.replaceChildren(...(state === 'disconnected' || state === 'off' ? [connectionMark(state)] : []));
}

/** Rejoin automatically (Keep joined), in a channel's details: on joins it as soon as you connect; off, when you open it. */
function keepJoinedToggle(route) {
  return switchRow('Rejoin automatically', route.keep_joined !== false, async (on, box) => {
    try {
      await api('protocols/keep', {address: route.address, protocol: route.protocol, keep: on});
      route.keep_joined = on;
    } catch (error) { box.checked = !on; showError(error); }
  }, 'Joins this channel as soon as you connect. Off: it joins only when you open it, and stays in your list.');
}

/** Reconnect automatically, on a server's details page: off, it connects only when you tap Reconnect. */
function autoReconnectToggle(account) {
  return switchRow('Reconnect automatically', account.reconnect !== false, async (on, box) => {
    try {
      await api('protocols/autoreconnect', {account: account.id, on});
      account.reconnect = on;
      connectionChanged();
    } catch (error) { box.checked = !on; showError(error); }
  }, 'Connects when FluidMessages Web starts and after the connection drops. Off: it connects only when you tap Connect '
    + 'or Reconnect. Disconnect turns this off. Its chats stay in your list.');
}

function connectionButton(label, run, status, primary) {
  const button = element('button', label, primary ? 'primary' : '');
  button.type = 'button';
  button.addEventListener('click', async () => {
    button.disabled = true;
    status.textContent = '';
    try { await run(); } catch (error) { status.textContent = error.message; button.disabled = false; }
  });
  return button;
}

/**
 * The Connection section, in a chat's details (with [route]) and on a server's details page: the status in words with
 * its mark, Reconnect or Connect, Disconnect, Reconnect automatically, and Rejoin automatically for an IRC channel.
 */
function connectionSection(id, route) {
  const section = element('section', '', 'settings-section connection-section');
  section.dataset.connection = id;
  const draw = () => {
    // A sample chat's pretend state is its server's.
    const own = onSample() && route ? SAMPLE_CONNECTIONS[route.address] : '';
    const status = own && own !== 'off' ? {state: own} : accountConnection(id);
    const state = connectionState(status.state || '', status.manual);
    const shown = status;
    const line = element('p', '', 'connection-status');
    line.setAttribute('role', 'status');
    line.append(connectionMark(state), element('span', connectionSummary(shown, connectionName(id), connectionNick(id))));
    const note = element('p', '', 'muted');
    note.setAttribute('role', 'status');
    const row = element('div', '', 'button-row wrap');
    if (status.state !== 'off') {
      if (connectionOff(id)) row.append(connectionButton('Connect', () => setConnected(id, true), note, true));
      else {
        row.append(connectionButton('Reconnect', () => reconnectServer(id), note, state === 'disconnected'),
          connectionButton('Disconnect', () => setConnected(id, false), note, false));
      }
    }
    const parts = [element('h3', 'Connection'), line, row];
    const account = connectionAccount(id);
    if (account && !onSample()) parts.push(autoReconnectToggle(account));
    if (route?.protocol === 'irc' && route.group && !route.server_chat && !onSample()) parts.push(keepJoinedToggle(route));
    parts.push(note);
    section.replaceChildren(...parts);
  };
  section.addEventListener('connection-redraw', draw);
  draw();
  return section;
}

/** The Connections page, from the inbox's filter menu: every server, with its mark, a Connected switch, and Reconnect. */
function openConnections() {
  const servers = connectionServers();
  const intro = element('section', '', 'settings-section');
  intro.append(element('p', 'Turn a server off to stop connecting to it, also when FluidMessages Web restarts. Its chats stay in your list. '
    + 'Turn it back on to connect.' + (onSample() ? ' These are sample servers: nothing connects.' : ''), 'muted'));
  const list = element('section', '', 'settings-section connections-list');
  list.dataset.connection = 'all';
  const status = element('p', '', 'muted');
  status.setAttribute('role', 'status');
  const draw = () => {
    const rows = servers.map((account) => {
      const info = accountConnection(account.id);
      const state = connectionState(info.state || '', info.manual);
      const row = element('div', '', 'connection-row');
      const words = element('span', '', 'detail-words');
      const name = serverName(account);
      words.append(element('strong', name), element('small', (CHAT_PROTOCOL_NAMES[account.protocol] || account.protocol) + ' · '
        + connectionSummary(info, name, connectionNick(account.id))));
      row.append(connectionMark(state), words);
      if (info.state !== 'off') {
        if (!connectionOff(account.id)) row.append(connectionButton('Reconnect', () => reconnectServer(account.id), status, false));
        const toggle = switchRow('Connected', !connectionOff(account.id), async (on, box) => {
          try { await setConnected(account.id, on); } catch (error) { box.checked = !on; status.textContent = error.message; }
        });
        toggle.querySelector('span').classList.add('visually-hidden');
        toggle.querySelector('input').setAttribute('aria-label', (connectionOff(account.id) ? 'Connect ' : 'Disconnect ') + name);
        row.append(toggle);
      }
      return row;
    });
    if (!rows.length) rows.push(element('p', 'No servers yet. Add an IRC network, or a Matrix or XMPP account, under Communities.', 'muted'));
    list.replaceChildren(...rows);
  };
  list.addEventListener('connection-redraw', draw);
  draw();
  get('workspace').dataset.thread = 'true';
  detailsPage('Connections', [intro, list, status], () => {
    closeDetailsPage(false);
    if (!active) delete get('workspace').dataset.thread;
  });
}

// "Connections" in the inbox's filter menu opens the page; the filter you were on stays picked.
const CONNECTIONS_OPTION = 'Connections…';
let connectionsBefore = 'Inbox';

if (typeof syncFolderOptions === 'function') {
  const plainOptions = syncFolderOptions;
  // eslint-disable-next-line no-func-assign
  syncFolderOptions = function () {
    plainOptions();
    const select = get('folder');
    let option = [...select.options].find((item) => item.value === CONNECTIONS_OPTION);
    const shown = connectionServers().length > 0;
    if (!option && shown) { option = element('option', CONNECTIONS_OPTION); option.value = CONNECTIONS_OPTION; select.append(option); }
    if (option && !shown) option.remove();
  };
}
get('folder')?.addEventListener('focus', () => { connectionsBefore = get('folder').value; });
get('folder')?.addEventListener('change', (event) => {
  const select = get('folder');
  if (select.value !== CONNECTIONS_OPTION) { connectionsBefore = select.value; return; }
  event.stopImmediatePropagation();
  select.value = connectionsBefore;
  if (typeof folderBefore !== 'undefined') folderBefore = connectionsBefore;
  openConnections();
}, true);

if (typeof threadMenu === 'function') {
  const plainMenu = threadMenu;
  // eslint-disable-next-line no-func-assign
  threadMenu = function (key, button) {
    plainMenu(key, button);
    // Disconnect or Connect this chat's server, right in the chat's menu.
    const id = chatServerAccount(key);
    const row = button.nextElementSibling;
    if (!id || !row?.classList.contains('thread-actions')) return;
    const off = connectionOff(id);
    const item = element('button', (off ? 'Connect ' : 'Disconnect ') + connectionName(id));
    item.type = 'button';
    item.addEventListener('click', () => {
      row.remove();
      setConnected(id, off).catch(showError);
    });
    const details = [...row.querySelectorAll('button')].find((node) => node.textContent === 'Details');
    if (details) details.before(item); else row.prepend(item);
  };
}

if (typeof select === 'function') {
  const plainSelect = select;
  // eslint-disable-next-line no-func-assign
  select = function (key) {
    plainSelect(key);
    showChatConnection();
    // A channel that isn't kept joined rejoins as you open it.
    const route = key && typeof routeFor === 'function' ? routeFor(key) : null;
    if (route?.protocol === 'irc' && route.group && route.keep_joined === false && !onSample()) {
      api('protocols/open', {address: route.address, protocol: route.protocol}).catch(() => {});
    }
  };
}
if (typeof decorateThread === 'function') {
  const plainDecorate = decorateThread;
  // eslint-disable-next-line no-func-assign
  decorateThread = function (key, button, copy) {
    plainDecorate(key, button, copy);
    // The plug goes right beside the tag (or the name while tags are hidden), before the muted bell.
    const state = chatConnection(key);
    if (!state) return;
    const tags = copy.querySelector('.tags');
    const mark = connectionMark(state);
    if (tags) tags.firstElementChild ? tags.firstElementChild.after(mark) : tags.append(mark);
    else copy.querySelector('strong')?.append(mark);
  };
}
if (typeof noticeSnapshot === 'function') {
  const plainNotice = noticeSnapshot;
  // eslint-disable-next-line no-func-assign
  noticeSnapshot = function (snapshot) {
    plainNotice(snapshot);
    chatConnections = snapshot.connections && typeof snapshot.connections === 'object' ? snapshot.connections : {};
    showChatConnection();
    // An open Connection section or Connections page follows along, but not while you're using one of its controls.
    for (const node of document.querySelectorAll('[data-connection]')) {
      if (!node.contains(document.activeElement)) node.dispatchEvent(new Event('connection-redraw'));
    }
    if (typeof syncFolderOptions === 'function') syncFolderOptions();
  };
}
if (typeof openDetails === 'function') {
  const detailsBefore = openDetails;
  // eslint-disable-next-line no-func-assign
  openDetails = async function (options) {
    await detailsBefore(options);
    // IRC, Matrix, and XMPP chats: the Connection section comes first, so whether you're connected shows at a glance.
    const id = active ? chatServerAccount(active) : '';
    if (!id) return;
    const route = routeFor(active);
    const first = document.querySelector('#details-page .details-body .settings-section');
    const section = connectionSection(id, route);
    if (first) first.before(section); else document.querySelector('#details-page .details-body')?.append(section);
  };
}
