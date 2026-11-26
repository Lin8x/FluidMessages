'use strict';
// Settings → Privacy and security → Connect through Tor (tor_route.py), like the phone's (TorSettingsPage). Off by
// default. Turning it on shows the warning in place, and Turn on waits for "I understand".

async function torSettingsLink() {
  const card = settingsCard('Network');
  let summary = 'Off · hide this server’s internet address from chat servers. Slower.';
  try {
    const status = await api('tor');
    if (status.on) summary = status.reachable ? 'On · Connected through Tor' : 'On · Tor isn’t running';
  } catch (error) { /* shown on the page */ }
  card.append(settingsLink('tor', summary));
  return card;
}

function torStateWords(status) {
  if (!status.on) return 'Off';
  return status.reachable ? 'Connected through Tor' : 'Tor isn’t running at ' + status.proxy;
}

async function torSettingsPage() {
  let status = await api('tor');
  const card = settingsCard('Connect through Tor', 'For the connections this server makes: IRC, Matrix, and email, and link '
    + 'previews. Your phone has its own setting.');
  card.id = 'settings-tor';
  const state = element('p', torStateWords(status), 'muted');
  state.setAttribute('role', 'status');
  const problem = errorLine();
  const save = async (change) => {
    problem.textContent = '';
    try {
      status = await api('tor', change);
      draw();
      return true;
    } catch (error) {
      problem.textContent = error.message;
      return false;
    }
  };
  const consent = element('div', '', 'notice');
  consent.id = 'tor-consent';
  const understood = checkbox('I understand it’s slower and less reliable', false, (value) => { turnOn.disabled = !value; });
  const turnOn = element('button', 'Turn on', 'primary');
  turnOn.type = 'button';
  turnOn.disabled = true;
  const cancel = element('button', 'Cancel');
  cancel.type = 'button';
  consent.append(element('strong', 'Connect through Tor?'), element('p', status.warning), understood, turnOn, cancel);
  const toggle = checkbox('Connect through Tor', status.on, async (value) => {
    if (value) { consent.hidden = false; return; }
    consent.hidden = true;
    await save({on: false});
  }, status.warning);
  const toggleInput = toggle.querySelector('input');
  cancel.addEventListener('click', () => { consent.hidden = true; toggleInput.checked = false; });
  turnOn.addEventListener('click', async () => {
    if (await save({on: true, understood: true})) consent.hidden = true;
  });
  consent.hidden = true;

  const proxy = Object.assign(element('input', ''), {type: 'text', value: status.proxy, autocomplete: 'off', spellcheck: false});
  proxy.id = 'tor-proxy';
  const saveProxy = element('button', 'Save address');
  saveProxy.type = 'button';
  saveProxy.addEventListener('click', () => save({proxy: proxy.value.trim()}));
  const install = element('p', '', 'notice');
  install.id = 'tor-install';

  function draw() {
    toggleInput.checked = status.on;
    state.textContent = torStateWords(status);
    install.hidden = status.reachable;
    install.textContent = 'No Tor proxy answers at ' + status.proxy + '. ' + status.install
      + (status.on ? ' Until it answers, chat services wait instead of connecting without Tor.' : '');
  }
  draw();
  card.append(toggle, consent, state, field('Tor proxy address', proxy, 'Tor’s SOCKS port. Usually 127.0.0.1:9050.'), saveProxy, install, problem);

  const what = settingsCard('What goes through Tor');
  what.append(element('p', 'Through Tor: IRC, Matrix, and email connected from this server, and link previews and pictures from links.', 'muted'),
    element('p', 'Not through Tor: XMPP waits and encrypted Matrix rooms can’t be used here while Tor is on, because their '
      + 'libraries open their own connections. Cloud backups, and servers on your own network, connect directly. Calls run in your browser or phone '
      + 'and never go through this server.', 'muted'));
  return [card, what];
}

SETTINGS_SUBPAGES.push({id: 'tor', icon: 'privacy', title: 'Connect through Tor', parent: 'privacy', page: () => torSettingsPage()});

// Tor per chat, as on the phone (TorChat.kt): an onion beside a chat's other marks (the plug, the muted bell) while
// its connection goes through Tor right now, the same in the chat's header, and in its details' Connection section
// "Through Tor", "Waiting for Tor", or "Not through Tor" with a "Connect through Tor" switch. Tor routes per
// connection, so the switch changes the chat's whole account on this server (POST tor/connection), and says so. It's
// disabled with the reason where Tor can't apply (tor_route.chat_choice).

let torStates = {};

// tor-chat:start (scripts/test-local runs this block on its own)
const TOR_TEXTS = 'Texts go over your phone plan, not the internet';
const TOR_NEARBY = 'Nearby doesn\'t use the internet';
const TOR_E2EE = 'Encrypted Matrix rooms on FluidMessages Web can\'t go through Tor yet, because their library opens its own connections';
const TOR_ON_PHONE = 'This chat connects from your phone. Choose Tor in its details on your phone';
const TOR_WORDS = {tor: 'Through Tor', wait: 'Waiting for Tor', direct: 'Not through Tor'};

/** Can this chat use Tor, and why not: {allowed, why}. [entry] is its account's Tor from the server (or null). */
function torChatChoice(protocol, entry, encrypted) {
  if (['sms', 'mms', 'rcs', '', undefined].includes(protocol)) return {allowed: false, why: TOR_TEXTS};
  if (protocol === 'nearby') return {allowed: false, why: TOR_NEARBY};
  if (!entry) return {allowed: false, why: TOR_ON_PHONE};
  if (protocol === 'matrix' && encrypted) return {allowed: false, why: TOR_E2EE};
  if (!entry.allowed) return {allowed: false, why: entry.why || 'This chat can\'t use Tor'};
  return {allowed: true, why: ''};
}

function torScope(where) { return 'Also applies to other chats on ' + (where || 'this server'); }

/** The onion shows only while the chat's traffic really goes through Tor: not while it waits, never for texts. */
function torMarked(choice, route) { return Boolean(choice.allowed) && route === 'tor'; }

/** The sample inbox: Starlight IRC's connection goes through Tor, so its channels show the onion. Pretend. */
const SAMPLE_TOR = {'sample:irc': true};
// tor-chat:end

/** A chat's Tor: {account, entry, choice, route}. The sample's is pretend. */
function chatTor(key) {
  const protocol = threadProtocol(key);
  const route = key && typeof routeFor === 'function' ? routeFor(key) : null;
  const account = route?.account || '';
  const sample = typeof sampleOn !== 'undefined' && sampleOn;
  let entry = account ? torStates[account] || null : null;
  if (sample && account) {
    const name = typeof connectionName === 'function' ? connectionName(account) : account;
    entry = {route: SAMPLE_TOR[account] ? 'tor' : 'direct', on: Boolean(SAMPLE_TOR[account]), allowed: true, why: '', where: name};
  }
  const choice = torChatChoice(route?.protocol || protocol, entry, Boolean(route?.encrypted));
  return {account, entry, choice, route: entry?.route || 'direct', sample};
}

/** The onion, in the same size and line style as the plug: an original drawing, not Tor's logo. */
function torMark() {
  const mark = element('span', '', 'connection-mark tor-mark');
  mark.setAttribute('role', 'img');
  mark.setAttribute('aria-label', TOR_WORDS.tor);
  mark.title = TOR_WORDS.tor;
  const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
  svg.setAttribute('viewBox', '0 0 16 16');
  svg.setAttribute('aria-hidden', 'true');
  for (const d of ['M8 3.4C8.6 5.2 13 6.8 13 10.2C13 12.7 10.8 14.2 8 14.2C5.2 14.2 3 12.7 3 10.2C3 6.8 7.4 5.2 8 3.4Z',
    'M8 7V12.2', 'M8 3.4V1.6M8 2.6L9.8 1.5']) {
    const path = document.createElementNS('http://www.w3.org/2000/svg', 'path');
    for (const [name, value] of [['d', d], ['fill', 'none'], ['stroke', 'currentColor'], ['stroke-width', '1.5'],
      ['stroke-linecap', 'round'], ['stroke-linejoin', 'round']]) path.setAttribute(name, value);
    svg.append(path);
  }
  mark.append(svg);
  return mark;
}

/** The Tor part of a chat's Connection section: its route in words, then the switch, enabled or with its reason. */
function torSection(key) {
  const block = element('div', '', 'tor-chat');
  block.dataset.torChat = key;
  const draw = () => {
    const now = chatTor(key);
    const line = element('p', '', 'connection-status');
    line.setAttribute('role', 'status');
    if (torMarked(now.choice, now.route)) line.append(torMark());
    const status = now.route === 'wait' && now.entry?.status ? ' · ' + now.entry.status : '';
    line.append(element('span', TOR_WORDS[now.route] + status));
    const note = element('p', '', 'muted');
    note.setAttribute('role', 'status');
    const toggle = switchRow('Connect through Tor', Boolean(now.choice.allowed && now.entry?.on), async (on, box) => {
      if (now.sample) {
        SAMPLE_TOR[now.account] = on;
        torRedraw();
        draw();
        block.querySelector('input[role="switch"]')?.focus();
        return;
      }
      box.disabled = true;
      try {
        torStates[now.account] = await api('tor/connection', {account: now.account, on});
        torRedraw();
        draw();
        block.querySelector('input[role="switch"]')?.focus();
      } catch (error) { box.checked = !on; box.disabled = false; note.textContent = error.message; }
    }, now.choice.allowed ? torScope(now.entry?.where) : now.choice.why);
    toggle.querySelector('input').disabled = !now.choice.allowed;
    if (!now.choice.allowed) toggle.classList.add('disabled');
    block.replaceChildren(line, toggle, note);
    if (now.sample && now.choice.allowed) note.textContent = 'Sample: nothing really connects.';
  };
  block.addEventListener('tor-redraw', draw);
  draw();
  return block;
}

/** Redraws the inbox's onions, the header's, and any open Tor section, after a switch or a sync. */
function torRedraw() {
  if (typeof showThreads === 'function') showThreads();
  if (typeof showChatConnection === 'function') showChatConnection();
  for (const node of document.querySelectorAll('[data-tor-chat]')) {
    if (!node.contains(document.activeElement)) node.dispatchEvent(new Event('tor-redraw'));
  }
}

if (typeof decorateThread === 'function') {
  const torDecorate = decorateThread;
  // eslint-disable-next-line no-func-assign
  decorateThread = function (key, button, copy) {
    torDecorate(key, button, copy);
    const now = chatTor(key);
    if (!torMarked(now.choice, now.route)) return;
    // Right after the plug, before the muted bell.
    const plug = copy.querySelector('.connection-mark:not(.tor-mark)');
    const mark = torMark();
    if (plug) plug.after(mark);
    else (copy.querySelector('.tags') || copy.querySelector('strong'))?.append(mark);
  };
}
if (typeof showChatConnection === 'function') {
  const torHead = showChatConnection;
  // eslint-disable-next-line no-func-assign
  showChatConnection = function () {
    torHead();
    const head = document.getElementById('head-connection');
    if (!head || !active) return;
    const now = chatTor(active);
    if (torMarked(now.choice, now.route)) head.append(torMark());
  };
}
if (typeof noticeSnapshot === 'function') {
  const torNotice = noticeSnapshot;
  // eslint-disable-next-line no-func-assign
  noticeSnapshot = function (snapshot) {
    const routesOf = (states) => JSON.stringify(Object.fromEntries(Object.entries(states).map(([id, item]) => [id, item?.route])));
    const before = routesOf(torStates);
    torStates = snapshot.tor && typeof snapshot.tor === 'object' ? snapshot.tor : {};
    torNotice(snapshot);
    if (before !== routesOf(torStates)) torRedraw();
  };
}
if (typeof openDetails === 'function') {
  const torDetails = openDetails;
  // eslint-disable-next-line no-func-assign
  openDetails = async function (options) {
    await torDetails(options);
    if (!active) return;
    const body = document.querySelector('#details-page .details-body');
    if (!body) return;
    // In the chat's Connection section (IRC, Matrix, and XMPP), or a Connection section of its own (texts, email).
    let section = body.querySelector('.connection-section');
    const own = !section;
    if (own) {
      section = element('section', '', 'settings-section connection-section tor-only');
      section.append(element('h3', 'Connection'));
      const first = body.querySelector('.settings-section');
      if (first) first.before(section); else body.append(section);
    }
    section.append(torSection(active));
    // The chat's own Connection section redraws itself on syncs; keep the Tor part in it.
    if (!own) section.addEventListener('connection-redraw', () => {
      if (!section.querySelector('[data-tor-chat]')) section.append(torSection(active));
    });
  };
}
