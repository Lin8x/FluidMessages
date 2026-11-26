'use strict';
// Settings → Privacy and security → Connect through Tor (tor_route.py), like the phone's (TorSettingsPage). Off by
// default. The first time it's turned on, "What is Tor?" (below) comes first; then the warning shows in place, and
// Turn on waits for "I understand".

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
  torIntroNext = null;
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
    // The first time, "What is Tor?" comes before the warning; Continue there brings you back to it.
    if (value && torIntroDue(true)) {
      toggleInput.checked = false;
      torIntroNext = 'consent';
      openSettings('tor-intro').catch(showError);
      return;
    }
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
  // Back from "What is Tor?" with Continue: the warning, open, as if the switch had just been turned on.
  if (torAskOnOpen) {
    torAskOnOpen = false;
    if (!status.on) {
      consent.hidden = false;
      toggleInput.checked = true;
    }
  }
  card.append(toggle, consent, state, field('Tor proxy address', proxy, 'Tor’s SOCKS port. Usually 127.0.0.1:9050.'), saveProxy, install, problem,
    settingsLink('tor-intro', 'How Tor works, who it’s for, and its downsides'));

  const what = settingsCard('What goes through Tor');
  what.append(element('p', 'Through Tor: IRC, Matrix, and email connected from this server, and link previews and pictures from links.', 'muted'),
    element('p', 'Not through Tor: XMPP waits and encrypted Matrix rooms can’t be used here while Tor is on, because their '
      + 'libraries open their own connections. Cloud backups, and servers on your own network, connect directly. Calls run in your browser or phone '
      + 'and never go through this server.', 'muted'));
  return [card, what];
}

SETTINGS_SUBPAGES.push({id: 'tor', icon: 'privacy', title: 'Connect through Tor', parent: 'privacy', page: () => torSettingsPage()});

// "What is Tor?", like the phone's (TorIntro.kt), in the style of the service guides (account-setup.js): a picture,
// what it is, who it's for, what it doesn't do, the downsides, and numbered steps. It shows the first time Connect
// through Tor is turned on, before the warning, and any time from the Tor page and Settings → Help.

// tor-intro:start (scripts/test-local runs this block on its own)
const TOR_INTRO = {
  title: 'What is Tor?',
  about: 'Tor hides your internet address from the servers you talk to and from anyone watching your network. It does '
    + 'this by bouncing your connection through three computers run by volunteers. Each one only knows the step before it '
    + 'and the step after it.',
  whoTitle: 'Who it’s for',
  who: 'People who don’t want a chat server, a school, a workplace, or an internet provider to see who they talk to or '
    + 'where they connect from.',
  notTitle: 'What it doesn’t do',
  not: ['It doesn’t lock your messages. That’s the job of the lock next to a chat’s name.',
    'Texts and phone calls never use Tor. They go over your phone plan.'],
  downsidesTitle: 'The downsides',
  downsides: ['It’s slower. Messages and pictures take longer.', 'The connection sometimes drops and has to start again.',
    'Some servers block Tor, so a chat there may not connect.'],
  stepsTitle: 'How it works here',
  steps: ['Turn on Connect through Tor for every chat service in Settings → Privacy and security.',
    'Or turn it on for only some chats: each chat’s details have their own Connect through Tor switch.',
    'Chats that go through Tor show a small onion next to their name.'],
  again: 'See this again any time in Settings → Help → What is Tor?',
};

/** Whether turning Tor on shows "What is Tor?" first: only on the way to turning it on, and only the first time. */
function torIntroDue(turningOn, seen = typeof prefs === 'object' && prefs?.torIntroSeen === true) {
  return !!turningOn && !seen;
}
// tor-intro:end

/** 'consent' while "What is Tor?" is on the way to turning Tor on; the Tor page then opens its warning. */
let torIntroNext = null;
let torAskOnOpen = false;

function torIntroMarkSeen() {
  if (prefs?.torIntroSeen === true) return;
  prefs = {...prefs, torIntroSeen: true};
  api('settings', {prefs: {torIntroSeen: true}}).then((result) => { companionSettings = result; }).catch(() => {});
}

/** An original picture (not the Tor Project's logo): your device, a dashed path hopping through three computers, a server. */
function torIntroPicture() {
  const template = document.createElement('template');
  const hops = [[48, 64], [112, 32], [168, 96], [224, 34], [272, 64]];
  let path = '';
  for (let i = 0; i < hops.length - 1; i++) path += 'M' + hops[i].join(' ') + 'L' + hops[i + 1].join(' ');
  template.innerHTML = '<svg class="service-guide-art tor-intro-art" viewBox="0 0 320 128" role="img" aria-hidden="true">'
    + '<rect class="sg-bubble" x="12" y="30" width="36" height="68" rx="9"/><rect class="sg-on" x="20" y="54" width="20" height="14" rx="6"/>'
    + '<path class="sg-link tor-hop" d="' + path + '"/>'
    + hops.slice(1, 4).map(([x, y]) => '<circle class="tor-node" cx="' + x + '" cy="' + y + '" r="15"/>'
      + '<rect class="sg-on" x="' + (x - 7) + '" y="' + (y - 5) + '" width="14" height="9" rx="2"/>').join('')
    + [34, 56, 78].map((y) => '<rect class="sg-box" x="272" y="' + y + '" width="38" height="18" rx="5"/>'
      + '<circle class="sg-dot" cx="282" cy="' + (y + 9) + '" r="3"/>').join('')
    + '</svg>';
  return template.content.firstElementChild;
}

/** The page. [back] is the page to return to with Done, Continue, or Not now. */
function torIntroPage(back) {
  torIntroMarkSeen();
  const turningOn = torIntroNext === 'consent';
  const leave = (ask) => {
    torIntroNext = null;
    torAskOnOpen = ask;
    openSettings(ask ? 'tor' : back).catch(showError);
  };
  const hero = element('section', '', 'settings-section service-guide');
  hero.append(torIntroPicture(), element('h3', TOR_INTRO.title), element('p', TOR_INTRO.about, 'service-guide-line'),
    element('p', 'Here, it hides the address of the computer FluidMessages Web runs on. Your phone has its own setting.', 'muted'));
  const section = (title, lines) => {
    const box = element('section', '', 'settings-section');
    box.append(element('h3', title));
    if (lines.length === 1) box.append(element('p', lines[0]));
    else {
      const list = element('ul', '', 'tor-intro-list');
      for (const line of lines) list.append(element('li', line));
      box.append(list);
    }
    return box;
  };
  const steps = element('section', '', 'settings-section');
  const list = element('ol', '', 'service-guide-steps');
  for (const text of TOR_INTRO.steps) list.append(element('li', text));
  list.lastElementChild.append(torMark());
  steps.append(element('h3', TOR_INTRO.stepsTitle), list);
  if (!turningOn) steps.append(element('p', TOR_INTRO.again, 'muted'));
  const go = element('div', '', 'service-guide-go');
  const done = element('button', turningOn ? 'Continue' : 'Done', 'primary service-guide-begin');
  done.type = 'button';
  done.addEventListener('click', () => leave(turningOn));
  go.append(done);
  if (turningOn) {
    const later = element('button', 'Not now', 'quiet');
    later.type = 'button';
    later.addEventListener('click', () => leave(false));
    go.append(later);
  }
  return [hero, section(TOR_INTRO.whoTitle, [TOR_INTRO.who]), section(TOR_INTRO.notTitle, TOR_INTRO.not),
    section(TOR_INTRO.downsidesTitle, TOR_INTRO.downsides), steps, go];
}

SETTINGS_SUBPAGES.push({id: 'tor-intro', icon: 'privacy', title: TOR_INTRO.title, parent: 'tor', page: () => torIntroPage('tor')});
// Settings → Help → What is Tor?, just before About and updates.
{
  const help = SETTINGS_GROUPS.find(([name]) => name === 'Help')?.[1];
  const item = {id: 'what-is-tor', icon: 'privacy', title: TOR_INTRO.title, summary: 'How Tor hides where you connect from, and its downsides',
    page: () => torIntroPage('')};
  const at = help ? help.findIndex((entry) => entry.id === 'about') : -1;
  if (at >= 0) help.splice(at, 0, item); else help?.push(item);
}

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
