'use strict';
// IRC connections from FluidMessages Web: a "Disconnected from <network> · Reconnect" bar in that network's chats and
// on its Communities header while it isn't connected, and each channel's Keep joined setting. The server reconnects
// on its own (15 s, 30 s, 1, 2, 5, then 10 minutes, at most five times an hour, so the network never sees a reconnect
// flood); Reconnect tries again at once. Channels that aren't kept joined rejoin when you open them.
// Loaded after app.js, companion.js, chats.js, servers.js, and chat-commands.js, and uses their helpers.

let chatConnections = {};

function connectionWords(status, network) {
  const retry = status.retry_at ? new Date(status.retry_at).toLocaleTimeString([], {hour: 'numeric', minute: '2-digit'}) : '';
  if (status.state === 'paused') return 'Disconnected from ' + network + ' · Waiting until ' + retry + ' to avoid reconnecting too often';
  if (status.state === 'reconnecting') return 'Disconnected from ' + network + (retry ? ' · Trying again at ' + retry : '');
  return 'Connecting to ' + network + '…';
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

/** Keep joined, in a channel's details: on rejoins it right after a reconnect; off rejoins it when you open it. */
function keepJoinedToggle(route) {
  const label = element('label', '', 'check-row');
  const box = Object.assign(element('input', ''), {type: 'checkbox', checked: route.keep_joined !== false});
  box.addEventListener('change', async () => {
    try {
      await api('protocols/keep', {address: route.address, protocol: route.protocol, keep: box.checked});
      route.keep_joined = box.checked;
    } catch (error) { box.checked = !box.checked; showError(error); }
  });
  label.append(box, element('span', 'Keep joined · rejoin right after reconnecting (off: rejoin when you open it)'));
  return label;
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
