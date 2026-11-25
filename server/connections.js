'use strict';
// IRC connections from FluidMessages Web: a "Disconnected from <network> · Reconnect" bar in that network's chats and
// on its Communities header while it isn't connected (with the network's own reason in plain words), each channel's
// Rejoin automatically switch (Keep joined), each server's Reconnect automatically switch, and a small connection mark
// beside the tag of every IRC, Matrix, and XMPP chat in the inbox. The server reconnects on its own (15 s, 30 s, 1, 2,
// 5, then 10 minutes, at most five times an hour, so the network never sees a reconnect flood); Reconnect tries again
// at once. Channels that aren't kept joined rejoin when you open them.
// Loaded after app.js, companion.js, chats.js, servers.js, and chat-commands.js, and uses their helpers.

let chatConnections = {};

// connection-state:start (scripts/test-local runs this block on its own)
/** The bar's words: why it isn't connected (the network's reason, in plain words) and what happens next. */
function connectionWords(status, network) {
  const retry = status.retry_at ? new Date(status.retry_at).toLocaleTimeString([], {hour: 'numeric', minute: '2-digit'}) : '';
  const why = status.error ? ' · ' + status.error : '';
  if (status.manual && status.state !== 'connected' && status.state !== 'connecting') {
    return 'Not connected to ' + network + ' · Reconnect automatically is off' + why;
  }
  if (status.state === 'paused') return 'Disconnected from ' + network + ' · Waiting until ' + retry + ' to avoid reconnecting too often';
  if (status.state === 'reconnecting') return 'Disconnected from ' + network + why + (retry ? ' · Trying again at ' + retry : '');
  if (status.state === 'disconnected') return 'Disconnected from ' + network + (why || ' · Stopped after 5 tries this hour');
  return 'Connecting to ' + network + '…';
}

/** The inbox mark's state, as on the phone (IrcRules.connectionState): connected, connecting, disconnected, or ''. */
function connectionState(state) {
  if (state === 'connected') return 'connected';
  if (state === 'connecting' || state === 'reconnecting') return 'connecting';
  if (['disconnected', 'paused', 'offline', 'failed'].includes(state)) return 'disconnected';
  return '';
}

const CONNECTION_LABELS = {connected: 'Connected', connecting: 'Connecting', disconnected: 'Not connected'};

/** The sample inbox's marks: one channel on a network that isn't connected, so every look shows. Fictional. */
const SAMPLE_CONNECTIONS = {'sample:irc': 'connected', 'sample:matrix': 'connected', 'sample-irc-meteors': 'disconnected'};
// connection-state:end

/** A chat's connection mark state, from its server's connection; '' for chats with none (texts, email, unknown). */
function chatConnection(key) {
  const route = typeof routeFor === 'function' ? routeFor(key) : null;
  if (!route || !['irc', 'matrix', 'xmpp'].includes(route.protocol) || !route.account) return '';
  if (typeof sampleOn !== 'undefined' && sampleOn) return SAMPLE_CONNECTIONS[route.address] || SAMPLE_CONNECTIONS[route.account] || '';
  return connectionState(chatConnections[route.account]?.state || '');
}

/**
 * The mark beside a chat's tag: three signal bars, all filled while connected, one while connecting, and empty with a
 * slash while not connected. The shape tells them apart without color, and it reads out as text. Never a dot.
 */
function connectionMark(state) {
  const mark = element('span', '', 'connection-mark ' + state);
  mark.setAttribute('role', 'img');
  mark.setAttribute('aria-label', CONNECTION_LABELS[state]);
  mark.title = CONNECTION_LABELS[state];
  mark.innerHTML = '<svg viewBox="0 0 13 13" aria-hidden="true"><rect class="b1" x="0" y="7.8" width="2.6" height="5.2" rx=".8"/>'
    + '<rect class="b2" x="5.2" y="3.9" width="2.6" height="9.1" rx=".8"/><rect class="b3" x="10.4" y="0" width="2.6" height="13" rx=".8"/>'
    + (state === 'disconnected' ? '<line x1="0" y1="1.3" x2="13" y2="12.4"/>' : '') + '</svg>';
  return mark;
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

/** The bar for one network's account, or null while it's connected (or not connected from this server at all). */
function connectionBar(account, network) {
  const status = chatConnections[account];
  if (!status || status.state === 'connected' || typeof sampleOn !== 'undefined' && sampleOn) return null;
  const bar = element('div', '', 'connection-bar');
  bar.setAttribute('role', 'status');
  bar.append(element('span', connectionWords(status, network || status.network || 'the network')));
  if (status.state !== 'connecting') {
    const button = element('button', 'Reconnect');
    button.type = 'button';
    button.addEventListener('click', async () => {
      button.disabled = true;
      try {
        await api('protocols/reconnect', {account});
        chatConnections[account] = {...status, state: 'connecting'};
        bar.firstChild.textContent = connectionWords(chatConnections[account], network || status.network);
        button.remove();
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
  const route = active && internet(threadProtocol(active)) && typeof routeFor === 'function' ? routeFor(active) : null;
  const account = route?.account || '';
  const name = (typeof chatAccounts !== 'undefined' ? chatAccounts : []).find((item) => item.id === account);
  const bar = account ? connectionBar(account, name && typeof serverName === 'function' ? serverName(name) : '') : null;
  slot.replaceChildren(...(bar ? [bar] : []));
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
    } catch (error) { box.checked = !on; showError(error); }
  }, 'Connects when FluidMessages Web starts and after the connection drops. Off: it connects only when you tap Reconnect. '
    + 'Its chats stay in your list.');
}

if (typeof select === 'function') {
  const plainSelect = select;
  // eslint-disable-next-line no-func-assign
  select = function (key) {
    plainSelect(key);
    showChatConnection();
    // A channel that isn't kept joined rejoins as you open it.
    const route = key && typeof routeFor === 'function' ? routeFor(key) : null;
    if (route?.protocol === 'irc' && route.group && route.keep_joined === false && !(typeof sampleOn !== 'undefined' && sampleOn)) {
      api('protocols/open', {address: route.address, protocol: route.protocol}).catch(() => {});
    }
  };
}
if (typeof decorateThread === 'function') {
  const plainDecorate = decorateThread;
  // eslint-disable-next-line no-func-assign
  decorateThread = function (key, button, copy) {
    plainDecorate(key, button, copy);
    // The connection mark goes right beside the tag (or the name while tags are hidden), never as a dot.
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
  };
}
if (typeof openDetails === 'function') {
  const detailsBefore = openDetails;
  // eslint-disable-next-line no-func-assign
  openDetails = async function (options) {
    await detailsBefore(options);
    const route = active && typeof routeFor === 'function' ? routeFor(active) : null;
    const chat = document.querySelector('#details-page .details-body .settings-section:has(> h3)');
    if (!route || route.protocol !== 'irc' || !route.group) return;
    const sections = [...document.querySelectorAll('#details-page .details-body .settings-section')];
    const place = sections.find((node) => node.querySelector('h3')?.textContent === 'Chat') || chat;
    place?.append(keepJoinedToggle(route));
  };
}
