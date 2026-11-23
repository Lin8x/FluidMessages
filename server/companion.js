'use strict';
// Companion features beyond the shared inbox: settings that work without a phone, starting internet chats,
// chat details (disappearing timer and group members), Matrix voice and video calls, and money cards.
// Loaded after app.js and uses its helpers (api, get, element, threads, active, inbox, select, send).

let routes = [];
let companionSettings = null;
let callCursor = null;
let currentCall = null;
const callButtons = {};

// Settings and standalone use

const ACCOUNT_FIELDS = {
  matrix: [['homeserver', 'Homeserver', 'https://matrix.example.org'], ['user_id', 'Matrix ID', '@you:example.org'],
    ['access_token', 'Access token', '', true]],
  xmpp: [['jid', 'Address', 'you@example.org'], ['password', 'Password', '', true], ['host', 'Server (optional)', ''],
    ['port', 'Port (optional)', '5222']],
  email: [['address', 'Email address', 'you@example.org'], ['imap_host', 'IMAP server', 'imap.example.org'],
    ['imap_user', 'IMAP user (optional)', ''], ['imap_password', 'IMAP password', '', true],
    ['smtp_host', 'SMTP server', 'smtp.example.org'], ['smtp_user', 'SMTP user (optional)', ''],
    ['smtp_password', 'SMTP password (optional)', '', true]],
  irc: [['server', 'Server', 'irc.libera.chat'], ['port', 'Port (optional)', '6697'], ['nick', 'Nickname', ''],
    ['sasl_user', 'SASL user (optional)', ''], ['sasl_password', 'SASL password (optional)', '', true],
    ['channels', 'Channels (comma-separated)', '#example']],
};
const TIMERS = [[0, 'Off'], [30, '30 seconds'], [300, '5 minutes'], [3600, '1 hour'], [28800, '8 hours'], [86400, '1 day'],
  [604800, '1 week'], [2419200, '4 weeks']];

function timerLabel(seconds) {
  const known = TIMERS.find(([value]) => value === seconds);
  if (known) return known[1];
  for (const [unit, size] of [['week', 604800], ['day', 86400], ['hour', 3600], ['minute', 60]]) {
    if (seconds % size === 0) return seconds / size + ' ' + unit + (seconds / size === 1 ? '' : 's');
  }
  return seconds + ' seconds';
}

function timerSelect(value, includeDefault) {
  const select = element('select', '');
  const options = includeDefault ? [[-1, 'Use the default']] : [];
  for (const option of [...options, ...TIMERS]) {
    const node = element('option', option[1]);
    node.value = String(option[0]);
    select.append(node);
  }
  if (![...select.options].some((option) => Number(option.value) === value)) {
    const node = element('option', timerLabel(value));
    node.value = String(value);
    select.append(node);
  }
  select.value = String(value);
  return select;
}

async function loadSettings() {
  companionSettings = await api('settings');
  const phone = companionSettings.phone;
  get('status').textContent = phone.paired ? 'Phone synced ' + new Date(phone.last_sync).toLocaleTimeString()
    : 'No phone connected · internet chats work here';
  return companionSettings;
}

function field(label, input) {
  const wrap = element('label', label);
  wrap.append(input);
  return wrap;
}

function dialog(title) {
  const node = element('dialog', '', 'sheet');
  const head = element('div', '', 'sidebar-head');
  const close = element('button', 'Done');
  close.type = 'button';
  close.addEventListener('click', () => node.close());
  head.append(element('h2', title), close);
  node.append(head);
  node.addEventListener('close', () => node.remove());
  document.body.append(node);
  return node;
}

async function openSettings() {
  const settings = await loadSettings();
  const sheet = dialog('FluidMessages Web settings');
  const phone = element('section', '', 'settings-section');
  phone.append(element('h3', 'Phone'), element('p', settings.phone.paired
    ? 'Your phone last synced ' + new Date(settings.phone.last_sync).toLocaleString() + '. Texts, MMS, and RCS go through it.'
    : 'No phone has synced yet. Matrix, XMPP, Email, and IRC work here on their own; texting needs the phone.', 'muted'));
  const follow = Object.assign(element('input', ''), {type: 'checkbox', checked: settings.follow_phone});
  const followLabel = element('label', '', 'check');
  followLabel.append(follow, element('span', 'Use my phone’s settings (disappearing timers) when it syncs'));
  follow.addEventListener('change', () => api('settings', {follow_phone: follow.checked}).then(loadSettings).catch(showError));
  phone.append(followLabel);

  const timers = element('section', '', 'settings-section');
  const timer = timerSelect(settings.disappearing.default, false);
  timer.addEventListener('change', () => api('settings', {disappearing: {...settings.disappearing, default: Number(timer.value)}})
    .then(loadSettings).catch(showError));
  timers.append(element('h3', 'Disappearing messages'), field('Default timer for every chat', timer),
    element('p', 'Removes messages here and on the phone. Your own Matrix and XMPP messages are also taken back for everyone. '
      + 'With “Use my phone’s settings” on, the phone’s timers replace these when it syncs.', 'muted'));

  const accounts = element('section', '', 'settings-section');
  accounts.append(element('h3', 'Accounts'));
  const list = element('div', '');
  accounts.append(list);
  const showAccounts = async () => {
    const result = await api('protocols/accounts');
    list.replaceChildren();
    for (const protocol of Object.keys(ACCOUNT_FIELDS)) {
      const account = result.accounts.find((item) => item.protocol === protocol);
      const row = element('div', '', 'account-row');
      const summary = account ? (account.fields.user_id || account.fields.jid || account.fields.address || account.fields.nick || 'Set up')
        + (account.source === 'compose' ? ' · set in Docker Compose' : '') : 'Not set up';
      row.append(element('strong', labels[protocol]), element('span', summary, 'muted'));
      if (!account || account.source !== 'compose') {
        const edit = element('button', account ? 'Edit' : 'Add');
        edit.type = 'button';
        edit.addEventListener('click', () => editAccount(protocol, account, showAccounts));
        row.append(edit);
      }
      if (account && account.source !== 'compose') {
        const remove = element('button', 'Remove');
        remove.type = 'button';
        remove.addEventListener('click', async () => {
          if (!confirm('Remove the ' + labels[protocol] + ' account from FluidMessages Web?')) return;
          await api('protocols/accounts/delete', {protocol}).catch(showError);
          showAccounts();
        });
        row.append(remove);
      }
      list.append(row);
    }
  };
  const reset = element('section', '', 'settings-section');
  const resetButton = element('button', 'Reset FluidMessages Web settings');
  resetButton.type = 'button';
  resetButton.addEventListener('click', async () => {
    if (!confirm('Put FluidMessages Web’s settings back to their defaults? Accounts, chats, and messages stay.')) return;
    await api('settings', {reset: true}).catch(showError);
    sheet.close();
    loadSettings().catch(() => {});
  });
  reset.append(element('h3', 'Reset'), resetButton, element('p', 'To erase everything on FluidMessages Web, including accounts and '
    + 'messages, stop it and remove its data volume: docker compose down -v', 'muted'));
  sheet.append(phone, timers, accounts, reset);
  sheet.showModal();
  showAccounts().catch((error) => list.append(element('p', error.message, 'muted')));
}

function editAccount(protocol, account, done) {
  const sheet = dialog(labels[protocol] + ' account');
  const form = element('form', '', 'settings-section');
  const inputs = {};
  for (const [name, label, hint, secret] of ACCOUNT_FIELDS[protocol]) {
    const input = Object.assign(element('input', ''), {name, placeholder: hint, type: secret ? 'password' : 'text', autocomplete: 'off'});
    const current = account?.fields?.[name];
    if (current && !secret) input.value = Array.isArray(current) ? current.join(', ') : String(current);
    if (secret && current) input.placeholder = 'Saved · leave blank to keep';
    inputs[name] = input;
    form.append(field(label, input));
  }
  const status = element('p', '', 'muted');
  const save = element('button', 'Save', 'primary');
  form.append(save, status);
  form.addEventListener('submit', async (event) => {
    event.preventDefault();
    const fields = {};
    for (const [name, input] of Object.entries(inputs)) if (input.value.trim()) fields[name] = input.value.trim();
    try {
      await api('protocols/accounts', {protocol, fields});
      sheet.close();
      done();
    } catch (error) { status.textContent = error.message; }
  });
  sheet.append(form);
  sheet.showModal();
}

// Starting internet chats from the browser

function openNewChat() {
  const sheet = dialog('New chat');
  const form = element('form', '', 'settings-section');
  const protocol = element('select', '');
  for (const value of ['sms', 'matrix', 'xmpp', 'email', 'irc']) {
    const option = element('option', value === 'sms' ? 'Text message (through your phone)' : labels[value]);
    option.value = value;
    protocol.append(option);
  }
  const target = Object.assign(element('input', ''), {required: true, autocomplete: 'off'});
  const name = Object.assign(element('input', ''), {placeholder: 'Shown in your inbox', autocomplete: 'off'});
  const group = Object.assign(element('input', ''), {type: 'checkbox'});
  const groupLabel = element('label', '', 'check');
  groupLabel.append(group, element('span', 'This is a group chat'));
  const hints = {sms: 'Phone number', matrix: '@maya:example.org or !room:example.org', xmpp: 'maya@example.org or room@conference.example.org',
    email: 'maya@example.org', irc: '#channel or nickname'};
  const update = () => {
    target.placeholder = hints[protocol.value];
    target.type = protocol.value === 'sms' ? 'tel' : 'text';
    groupLabel.hidden = protocol.value !== 'xmpp';
    name.parentElement && (name.parentElement.hidden = protocol.value === 'sms');
  };
  protocol.addEventListener('change', update);
  const status = element('p', '', 'muted');
  form.append(field('Protocol', protocol), field('Address', target), field('Name', name), groupLabel,
    element('button', 'Start chat', 'primary'), status);
  update();
  form.addEventListener('submit', async (event) => {
    event.preventDefault();
    if (protocol.value === 'sms') {
      sheet.close();
      select('');
      get('address').value = target.value.trim();
      get('body').focus();
      return;
    }
    const address = 'web-' + fluidCallId().slice(0, 16);
    try {
      const result = await api('protocols/routes', {address, protocol: protocol.value, target: target.value.trim(),
        title: name.value.trim() || target.value.trim(), group: group.checked});
      routes.push(result.route);
      sheet.close();
      select(address + '\u001f' + protocol.value);
    } catch (error) { status.textContent = error.message; }
  });
  sheet.append(form);
  sheet.showModal();
}

function routeFor(key) {
  const address = threadAddress(key);
  const protocol = threadProtocol(key);
  return routes.find((route) => route.address === address && route.protocol === protocol)
    || routes.find((route) => route.address === address && internet(route.protocol));
}

// Conversation details

async function openDetails() {
  if (!active) return;
  const settings = companionSettings || await loadSettings();
  const address = threadAddress(active);
  const sheet = dialog(threadTitle(active));
  const timers = element('section', '', 'settings-section');
  const own = settings.disappearing.chats[address];
  const timer = timerSelect(own === undefined ? -1 : own, true);
  timer.addEventListener('change', () => {
    const chats = {...settings.disappearing.chats};
    if (Number(timer.value) < 0) delete chats[address]; else chats[address] = Number(timer.value);
    api('settings', {disappearing: {...settings.disappearing, chats}}).then(loadSettings).catch(showError);
  });
  timers.append(element('h3', 'Disappearing messages'), field('Remove messages in this chat after', timer));
  if (settings.follow_phone && settings.phone.paired) {
    timers.append(element('p', 'Your phone’s timers replace this when it syncs. Change it on the phone, or turn off '
      + '“Use my phone’s settings” in FluidMessages Web settings.', 'muted'));
  }
  sheet.append(timers);
  const route = routeFor(active);
  if (route && route.group) {
    const members = element('section', '', 'settings-section');
    members.append(element('h3', 'People'));
    if (route.members?.length) members.append(element('p', route.members.join(', '), 'muted'));
    const who = Object.assign(element('input', ''), {placeholder: route.protocol === 'matrix' ? '@maya:example.org'
      : route.protocol === 'xmpp' ? 'Address to invite, or nickname to remove' : 'Nickname', autocomplete: 'off'});
    const status = element('p', '', 'muted');
    const change = (action) => async () => {
      try {
        await api('protocols/members', {address: route.address, protocol: route.protocol, action, member: who.value.trim()});
        status.textContent = action === 'add' ? 'Invited.' : 'Removed.';
        who.value = '';
      } catch (error) { status.textContent = error.message; }
    };
    const add = element('button', 'Invite');
    const remove = element('button', 'Remove');
    add.type = remove.type = 'button';
    add.addEventListener('click', change('add'));
    remove.addEventListener('click', change('remove'));
    const row = element('div', '', 'button-row');
    row.append(add, remove);
    members.append(field('Person', who), row, element('p', 'Removing someone needs moderator or operator rights in the group.', 'muted'), status);
    sheet.append(members);
  } else if (family(threadProtocol(active)) === 'sms' && address.startsWith('mms:')) {
    sheet.append(element('p', 'Group texts can’t change members. Start a new group from your phone to add or remove someone.', 'muted settings-section'));
  }
  sheet.showModal();
}

// Calls

function callable(key) {
  const route = key && routeFor(key);
  return !!route && route.protocol === 'matrix';
}

// The same traffic-light badge as the phone: red not encrypted, yellow in transit, green end to end.
function fluidEncrypted(key) {
  const fluid = companionSettings?.fluid_encryption;
  if (!fluid || !key) return false;
  const choice = fluid.chats[threadAddress(key)];
  if (choice) return choice === 'on';
  const protocol = threadProtocol(key);
  return fluid.protocols.includes(protocol === 'rcs' ? 'sms' : protocol);
}

function protection(key) {
  const protocol = threadProtocol(key);
  const route = routeFor(key);
  if (fluidEncrypted(key)) return ['green', 'Encrypted with FluidMessages'];
  if (route?.encrypted) return ['green', 'End-to-end encrypted'];
  if (internet(protocol)) return ['yellow', 'Encrypted in transit'];
  return ['red', 'Not encrypted'];
}

function updateCallButtons() {
  const matrix = callable(active);
  callButtons.voice.hidden = callButtons.video.hidden = !matrix;
  get('details').hidden = !active || !threadAddress(active);
  let badge = document.getElementById('security-badge');
  if (!badge) {
    badge = element('span', '', 'security-badge');
    badge.id = 'security-badge';
    get('transport').after(badge);
  }
  badge.hidden = !active;
  if (!active) return;
  const [tone, label] = protection(active);
  // A small padlock in the protection color; Details explains it.
  badge.className = 'security-lock tone-' + tone;
  badge.title = label;
  badge.setAttribute('role', 'img');
  badge.setAttribute('aria-label', label);
  const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
  svg.setAttribute('viewBox', '0 0 16 16');
  svg.setAttribute('width', '11');
  svg.setAttribute('height', '11');
  const shackle = document.createElementNS('http://www.w3.org/2000/svg', 'path');
  shackle.setAttribute('d', tone === 'red' ? 'M8 7V4.5a2.5 2.5 0 0 1 5 0' : 'M4.5 7V4.5a3.5 3.5 0 0 1 7 0V7');
  shackle.setAttribute('fill', 'none');
  shackle.setAttribute('stroke', 'currentColor');
  shackle.setAttribute('stroke-width', '1.6');
  const body = document.createElementNS('http://www.w3.org/2000/svg', 'rect');
  for (const [name, value] of [['x', '3'], ['y', '7.5'], ['width', '10'], ['height', '7'], ['rx', '1.6'], ['fill', 'none'],
    ['stroke', 'currentColor'], ['stroke-width', '1.6']]) body.setAttribute(name, value);
  svg.append(shackle, body);
  badge.replaceChildren(svg);
  // Sends from the browser aren't FluidMessages-encrypted; keys stay on the phone.
  get('schedule-status').textContent = fluidEncrypted(active)
    ? 'Your phone encrypts this chat with FluidMessages. Messages you send here are not encrypted that way.' : '';
}

async function startCall(video) {
  if (currentCall) return;
  const route = routeFor(active);
  if (!route) return;
  if (!navigator.mediaDevices?.getUserMedia) {
    showError(new Error('Calls need FluidMessages Web opened over HTTPS (or on this computer at localhost).'));
    return;
  }
  const ice = await api('calls/ice').catch(() => ({iceServers: []}));
  const signal = (type, content) => api('calls/send', {address: route.address, protocol: route.protocol, type, content});
  runCall({route, video, ice, signal, title: threadTitle(active)});
}

function runCall({route, video, ice, signal, title, invite}) {
  let view;
  let recorder = null;
  let parts = [];
  // Stops recording and downloads the file, named after the call.
  const saveRecording = async () => {
    if (!recorder) return false;
    await recorder.stop();
    const blob = new Blob(parts, {type: recorder.mime || 'audio/webm'});
    const link = element('a', '');
    link.href = URL.createObjectURL(blob);
    link.download = (video ? 'Video call with ' : 'Call with ') + title + ' ' + new Date().toISOString().slice(0, 16).replace(':', '') + '.webm';
    link.click();
    setTimeout(() => URL.revokeObjectURL(link.href), 30000);
    recorder = null;
    parts = [];
    return false;
  };
  const call = new FluidCall({signal, video, iceServers: ice.iceServers || [],
    onState: (text, ended) => { if (ended) saveRecording(); view.setState(text, ended); if (ended) currentCall = null; },
    onStreams: (local, remote) => view.streams(local, remote)});
  currentCall = {call, route};
  // Matrix media goes straight between the two devices, encrypted end to end.
  view = new FluidCallView({title, video, incoming: !!invite, security: {tone: 'green', label: 'Matrix · End-to-end encrypted'},
    onAnswer: () => { view.showActive(); call.answer(invite).catch((error) => { view.setState(error.message, true); call.finish('Failed'); }); },
    onDecline: () => call.decline(), onHangup: () => call.hangup(), onMute: () => call.toggleMute(),
    onCamera: () => call.toggleCamera(),
    onFlip: async () => { call.facing = call.facing === 'environment' ? 'user' : 'environment'; await fluidFlipCamera(call.peer, call.local, call.facing); },
    onRecord: async () => {
      if (recorder) return saveRecording();
      recorder = new FluidRecorder(call.local, call.remote, video, (blob) => parts.push(blob));
      recorder.start();
      return true;
    }});
  if (!invite) call.start().catch((error) => { call.finish(error.name === 'NotAllowedError' ? 'Microphone or camera blocked' : error.message); });
}

async function pollCalls() {
  if (!token || get('workspace').hidden) return;
  try {
    const result = await api('calls?since=' + (callCursor ?? 0));
    if (callCursor === null) { callCursor = result.next; return; }  // Don't ring for calls from before this page opened.
    callCursor = result.next;
    for (const event of result.events) {
      if (currentCall && event.content.call_id === currentCall.call.callId) { currentCall.call.handle(event); continue; }
      if (event.type === 'm.call.invite' && event.live && !currentCall && event.content.offer) {
        const route = routes.find((item) => item.address === event.address && item.protocol === event.protocol);
        if (!route) continue;
        const ice = await api('calls/ice').catch(() => ({iceServers: []}));
        const signal = (type, content) => api('calls/send', {address: route.address, protocol: route.protocol, type, content});
        const video = /m=video/.test(event.content.offer.sdp || '');
        runCall({route, video, ice, signal, title: event.title, invite: event.content});
      }
    }
  } catch (error) { /* the next poll tries again */ }
}

// Money cards: requests and payments that open the person's own wallet. The companion never holds funds or keys.

const ASSETS = {
  'USDC on Solana': {pattern: /^[1-9A-HJ-NP-Za-km-z]{32,44}$/, uri: (to, amount, note) => 'solana:' + to + '?amount=' + amount
    + '&spl-token=EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v' + (note ? '&message=' + encodeURIComponent(note) : ''),
    explorer: 'https://solscan.io/tx/'},
  'SOL on Solana': {pattern: /^[1-9A-HJ-NP-Za-km-z]{32,44}$/, uri: (to, amount, note) => 'solana:' + to + '?amount=' + amount
    + (note ? '&message=' + encodeURIComponent(note) : ''), explorer: 'https://solscan.io/tx/'},
  'USDC on Ethereum': {pattern: /^0x[0-9a-fA-F]{40}$/, uri: (to, amount) =>
    'ethereum:0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48@1/transfer?address=' + to + '&uint256=' + units(amount, 6),
    explorer: 'https://etherscan.io/tx/'},
  'USDC on Base': {pattern: /^0x[0-9a-fA-F]{40}$/, uri: (to, amount) =>
    'ethereum:0x833589fCD6eDb6E08f4c7C32D4f71b54bdA02913@8453/transfer?address=' + to + '&uint256=' + units(amount, 6),
    explorer: 'https://basescan.org/tx/'},
  'ETH on Ethereum': {pattern: /^0x[0-9a-fA-F]{40}$/, uri: (to, amount) => 'ethereum:' + to + '@1?value=' + units(amount, 18),
    explorer: 'https://etherscan.io/tx/'},
  'BTC on Bitcoin': {pattern: /^(bc1[02-9ac-hj-np-z]{11,87}|[13][1-9A-HJ-NP-Za-km-z]{25,34})$/, uri: (to, amount, note) =>
    'bitcoin:' + to + '?amount=' + amount + (note ? '&message=' + encodeURIComponent(note) : ''),
    explorer: 'https://mempool.space/tx/'},
};

// "20.5" with 6 decimals → "20500000", exactly, without floating point.
function units(amount, decimals) {
  const [whole, fraction = ''] = amount.split('.');
  return (BigInt(whole || '0') * 10n ** BigInt(decimals) + BigInt((fraction + '0'.repeat(decimals)).slice(0, decimals) || '0')).toString();
}

const MONEY = /^💸 (Payment request|Sent): ([0-9]+(?:\.[0-9]+)?) (.+?)\n(?:For: (.+)\n)?(?:(?:Pay|Transaction): (\S+))?/;

function moneyCard(message, bubble) {
  const match = MONEY.exec(message.body || '');
  if (!match) return false;
  const [, kind, amount, asset, note, link] = match;
  const card = element('div', '', 'money-card');
  card.append(element('small', kind === 'Sent' ? (message.type === 1 ? 'Sent to you' : 'You sent') : (message.type === 1 ? 'Requested from you' : 'You requested')),
    element('strong', amount + ' ' + asset.split(' on ')[0]), element('span', asset.includes(' on ') ? 'on ' + asset.split(' on ')[1] : '', 'muted'));
  if (note) card.append(element('p', note));
  if (link && kind !== 'Sent' && message.type === 1 && /^(solana|ethereum|bitcoin):/.test(link)) {
    const pay = element('a', 'Pay with your wallet', 'primary');
    pay.href = link;
    const copy = element('button', 'Copy payment link');
    copy.type = 'button';
    copy.addEventListener('click', () => navigator.clipboard?.writeText(link));
    card.append(pay, copy);
  } else if (link && kind === 'Sent' && /^https:\/\/(solscan\.io|etherscan\.io|basescan\.org|mempool\.space)\/tx\//.test(link)) {
    const view = element('a', 'View transaction');
    view.href = link;
    view.target = '_blank';
    view.rel = 'noopener noreferrer';
    card.append(view);
  }
  bubble.replaceChildren(card);
  return true;
}

function openMoney() {
  if (!active && !get('address').value.trim()) { showError(new Error('Choose a conversation first')); return; }
  const sheet = dialog('Send or request money');
  const form = element('form', '', 'settings-section');
  const kind = element('select', '');
  for (const label of ['Request', 'Send']) kind.append(Object.assign(element('option', label), {value: label}));
  const asset = element('select', '');
  for (const name of Object.keys(ASSETS)) asset.append(Object.assign(element('option', name), {value: name}));
  const amount = Object.assign(element('input', ''), {required: true, inputMode: 'decimal', placeholder: '20', pattern: '[0-9]+(\\.[0-9]{1,18})?'});
  const wallet = Object.assign(element('input', ''), {required: true, autocomplete: 'off'});
  const note = Object.assign(element('input', ''), {maxLength: 120, placeholder: 'What it’s for (optional)'});
  const tx = Object.assign(element('input', ''), {autocomplete: 'off', placeholder: 'Transaction ID (optional)'});
  const walletLabel = field('Your wallet address', wallet);
  const txLabel = field('After paying in your wallet, paste the transaction ID', tx);
  const update = () => {
    walletLabel.firstChild.textContent = kind.value === 'Request' ? 'Your wallet address' : 'Their wallet address';
    txLabel.hidden = kind.value === 'Request';
    wallet.value = kind.value === 'Request' ? localStorage.getItem('wallet:' + asset.value) || '' : wallet.value;
  };
  kind.addEventListener('change', update);
  asset.addEventListener('change', update);
  const status = element('p', 'FluidMessages never holds money or keys. Payments happen in your own wallet app, '
    + 'and crypto payments can’t be reversed, so check the address.', 'muted');
  const payLink = element('a', '', 'primary');
  payLink.hidden = true;
  form.append(field('Request or send', kind), field('Currency', asset), field('Amount', amount), walletLabel, field('Note', note),
    txLabel, payLink, element('button', 'Post to the chat', 'primary'), status);
  update();
  const build = () => {
    const spec = ASSETS[asset.value];
    const address = wallet.value.trim();
    if (!spec.pattern.test(address)) throw new Error('That doesn’t look like a ' + asset.value.split(' on ')[1] + ' address');
    if (!/^[0-9]+(\.[0-9]{1,18})?$/.test(amount.value) || Number(amount.value) <= 0) throw new Error('Enter an amount');
    return spec.uri(address, amount.value, note.value.trim());
  };
  wallet.addEventListener('change', () => {
    if (kind.value !== 'Send') return;
    try { payLink.href = build(); payLink.textContent = 'Open your wallet to pay'; payLink.hidden = false; }
    catch (error) { payLink.hidden = true; }
  });
  form.addEventListener('submit', async (event) => {
    event.preventDefault();
    try {
      const uri = build();
      const spec = ASSETS[asset.value];
      const memo = note.value.trim() ? 'For: ' + note.value.trim().replace(/\n/g, ' ') + '\n' : '';
      let body;
      if (kind.value === 'Request') {
        localStorage.setItem('wallet:' + asset.value, wallet.value.trim());
        body = '💸 Payment request: ' + amount.value + ' ' + asset.value + '\n' + memo + 'Pay: ' + uri
          + '\nSent with FluidMessages · the link opens your own wallet app';
      } else {
        const id = tx.value.trim();
        if (id && !/^[A-Za-z0-9]{32,128}$/.test(id)) throw new Error('Enter the transaction ID only');
        body = '💸 Sent: ' + amount.value + ' ' + asset.value + '\n' + memo + (id ? 'Transaction: ' + spec.explorer + id : '');
      }
      sheet.close();
      if (active && internet(threadProtocol(active))) await send(body.trim(), body.trim(), null);
      else await queueSms(body.trim(), threadAddress(active) || get('address').value.trim(), Date.now());
    } catch (error) { status.textContent = error.message; }
  });
  sheet.append(form);
  sheet.showModal();
}

// Wiring into the existing page

(() => {
  const head = document.querySelector('.thread-head');
  for (const [key, label, video] of [['voice', 'Voice', false], ['video', 'Video', true]]) {
    const button = element('button', label, 'call-button');
    button.type = 'button';
    button.hidden = true;
    button.title = (video ? 'Video' : 'Voice') + ' call over Matrix';
    button.addEventListener('click', () => startCall(video).catch(showError));
    callButtons[key] = button;
    head.append(button);
  }
  const details = element('button', 'Details', 'call-button');
  details.id = 'details';
  details.type = 'button';
  details.hidden = true;
  details.addEventListener('click', () => openDetails().catch(showError));
  head.append(details);
  const settings = element('button', 'Settings');
  settings.id = 'open-settings';
  settings.type = 'button';
  settings.hidden = true;
  settings.addEventListener('click', () => openSettings().catch(showError));
  get('disconnect').before(settings);
  const chat = element('button', 'New chat', 'icon-button new-chat');
  chat.type = 'button';
  chat.title = 'Start a chat on any protocol';
  chat.addEventListener('click', openNewChat);
  get('new').after(chat);
  const money = element('button', '💸', 'icon-button');
  money.type = 'button';
  money.title = 'Send or request money';
  money.setAttribute('aria-label', 'Send or request money');
  money.addEventListener('click', openMoney);
  document.querySelector('.compose-row').prepend(money);
  // Show the companion's own controls once paired, and keep calls ringing.
  new MutationObserver(() => {
    const paired = !get('workspace').hidden;
    settings.hidden = !paired;
    if (paired && !companionSettings) loadSettings().catch(() => {});
    if (!paired) { companionSettings = null; callCursor = null; }
  }).observe(get('workspace'), {attributes: true, attributeFilter: ['hidden']});
  setInterval(pollCalls, 2000);
})();

// Navigation like the phone app: Calls and Messages, plus Settings. A rail on wide screens, tabs at the bottom on phones.

const RAIL_ICONS = {
  calls: 'M6.62 10.79a15.05 15.05 0 0 0 6.59 6.59l2.2-2.2c.27-.27.67-.36 1.02-.24 1.12.37 2.33.57 3.57.57.55 0 1 .45 1 1V20c0 .55-.45 1-1 1C10.61 21 3 13.39 3 4c0-.55.45-1 1-1h3.5c.55 0 1 .45 1 1 0 1.25.2 2.45.57 3.57.11.35.03.74-.25 1.02l-2.2 2.2z',
  messages: 'M20 2H4a2 2 0 0 0-2 2v18l4-4h14a2 2 0 0 0 2-2V4a2 2 0 0 0-2-2z',
  settings: 'M19.14 12.94a7.07 7.07 0 0 0 0-1.88l2.03-1.58a.5.5 0 0 0 .12-.64l-1.92-3.32a.5.5 0 0 0-.61-.22l-2.39.96a7 7 0 0 0-1.62-.94l-.36-2.54A.5.5 0 0 0 13.9 2h-3.84a.5.5 0 0 0-.49.42l-.36 2.54c-.59.24-1.13.56-1.62.94l-2.39-.96a.5.5 0 0 0-.61.22L2.67 8.48a.5.5 0 0 0 .12.64l2.03 1.58a7.07 7.07 0 0 0 0 1.88l-2.03 1.58a.5.5 0 0 0-.12.64l1.92 3.32c.13.22.39.3.61.22l2.39-.96c.49.38 1.03.7 1.62.94l.36 2.54c.05.24.25.42.49.42h3.84c.24 0 .45-.18.49-.42l.36-2.54c.59-.24 1.13-.56 1.62-.94l2.39.96c.22.08.48 0 .61-.22l1.92-3.32a.5.5 0 0 0-.12-.64l-2.03-1.58zM12 15.6a3.6 3.6 0 1 1 0-7.2 3.6 3.6 0 0 1 0 7.2z',
};

function railIcon(name) {
  const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
  svg.setAttribute('viewBox', '0 0 24 24');
  svg.setAttribute('aria-hidden', 'true');
  const path = document.createElementNS('http://www.w3.org/2000/svg', 'path');
  path.setAttribute('d', RAIL_ICONS[name]);
  path.setAttribute('fill', 'currentColor');
  svg.append(path);
  return svg;
}

let section = 'messages';

function showSection(next) {
  section = next;
  document.body.dataset.section = next;
  for (const button of document.querySelectorAll('.rail button[data-section]')) {
    button.setAttribute('aria-current', String(button.dataset.section === next));
  }
  if (next === 'calls') showCalls();
}

// The Calls view: recent internet calls, and everyone you can call from here.
function showCalls() {
  const list = get('calls-list');
  list.replaceChildren();
  const recent = inbox.filter((message) => message.event && /^(Voice|Video) call/.test(message.body || '')).slice(0, 30);
  list.append(element('h2', 'Recent calls', 'calls-heading'));
  if (!recent.length) list.append(element('p', 'No calls yet. Matrix calls you make or get show up here.', 'placeholder'));
  for (const call of recent) {
    const missed = /missed/.test(call.body);
    const row = element('button', '', 'call-row' + (missed ? ' missed' : ''));
    row.type = 'button';
    const name = call.title || threadAddress(threadKey(call));
    row.append(element('span', initials(name), 'avatar'), element('strong', name),
      element('small', call.body.replace(/ from .*$/, '') + ' · ' + day(timeOf(call)) + ' ' + clock(timeOf(call))));
    row.addEventListener('click', () => { showSection('messages'); select(threadKey(call)); });
    list.append(row);
  }
  list.append(element('h2', 'Call someone', 'calls-heading'));
  const callable = routes.filter((route) => route.protocol === 'matrix');
  if (!callable.length) list.append(element('p', 'Link a Matrix address to a chat to call it from here.', 'placeholder'));
  for (const route of callable) {
    const row = element('div', '', 'call-row');
    row.append(element('span', initials(route.title), 'avatar'), element('strong', route.title), element('small', 'Matrix · end-to-end encrypted'));
    for (const [label, video] of [['Voice', false], ['Video', true]]) {
      const button = element('button', label);
      button.type = 'button';
      button.addEventListener('click', () => {
        showSection('messages');
        select(route.address + '\u001f' + route.protocol);
        startCall(video).catch(showError);
      });
      row.append(button);
    }
    list.append(row);
  }
}

(() => {
  const rail = element('nav', '', 'rail');
  rail.setAttribute('aria-label', 'Sections');
  for (const [key, label] of [['calls', 'Calls'], ['messages', 'Messages'], ['settings', 'Settings']]) {
    const button = element('button', '');
    button.type = 'button';
    button.dataset.section = key;
    button.append(railIcon(key), element('span', label));
    button.addEventListener('click', () => key === 'settings' ? openSettings().catch(showError) : showSection(key));
    rail.append(button);
  }
  get('workspace').prepend(rail);
  const calls = element('section', '', 'calls-pane');
  calls.id = 'calls-list';
  calls.setAttribute('aria-label', 'Calls');
  document.querySelector('.sidebar').after(calls);
  showSection('messages');
  // Installable as an app (a PWA). The worker only caches FluidMessages' own files, never messages.
  if ('serviceWorker' in navigator) navigator.serviceWorker.register('/sw.js').catch(() => {});
})();
