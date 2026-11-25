'use strict';
// IRC, Matrix, and XMPP as everyday chat: slash commands typed in the message box (with a small hint list as you type
// "/"), "* name waves" for /me, mention markers, the server's event lines (joins, leaves, topic changes, /whois),
// with a choice to hide joins and leaves, @ and + beside channel operators and voiced members, topics in the header,
// and bots labeled as automated. The command table matches the companion's (chat_commands.py) and the phone's
// (ChatCommands.kt). Commands never go to SMS or email as text: there, // sends a line that starts with / as written.
// Loaded after app.js, companion.js, chats.js, and servers.js, and uses their helpers.

const CHAT_COMMANDS = {
  me: ['/me waves', 'Send an action, shown as “* you waves”', ['irc', 'matrix', 'xmpp']],
  msg: ['/msg nick message', 'Open a direct chat and send a message', ['irc', 'matrix', 'xmpp']],
  query: ['/query nick', 'Open a direct chat', ['irc', 'matrix', 'xmpp']],
  join: ['/join #channel', 'Join a channel or room', ['irc', 'matrix', 'xmpp']],
  part: ['/part [reason]', 'Leave this channel or room', ['irc', 'matrix', 'xmpp']],
  nick: ['/nick newname', 'Change your nickname (display name on Matrix)', ['irc', 'matrix', 'xmpp']],
  topic: ['/topic [new topic]', 'Show or change the topic', ['irc', 'matrix', 'xmpp']],
  invite: ['/invite nick [#channel]', 'Invite someone here', ['irc', 'matrix', 'xmpp']],
  whois: ['/whois nick', 'Look someone up', ['irc']],
  away: ['/away [message]', 'Set or clear your away message', ['irc']],
  kick: ['/kick nick [reason]', 'Remove someone (needs operator rights)', ['irc']],
  mode: ['/mode [target] +o nick', 'Change channel or user modes', ['irc']],
  notice: ['/notice target message', 'Send a notice', ['irc']],
  quote: ['/quote RAW LINE', 'Send a raw IRC line (advanced)', ['irc']],
  help: ['/help', 'List the commands', ['irc', 'matrix', 'xmpp']],
};
const CHAT_COMMAND_ALIASES = {leave: 'part', raw: 'quote', j: 'join', q: 'query', back: 'away', action: 'me'};
const CHAT_COMMAND_NEEDS = {me: 1, msg: 2, query: 1, join: 1, nick: 1, invite: 1, whois: 1, kick: 1, mode: 1, notice: 2, quote: 1};
const CHAT_PROTOCOL_NAMES = {irc: 'IRC', matrix: 'Matrix', xmpp: 'XMPP'};
const HIDE_JOINS_KEY = 'fluidHideJoins';

const looksLikeChatCommand = (text) => typeof text === 'string' && /^\/[^\s/]/.test(text) && !text.startsWith('//');
const knownChatCommand = (word) => {
  const name = CHAT_COMMAND_ALIASES[word.toLowerCase()] || word.toLowerCase();
  return CHAT_COMMANDS[name] ? name : null;
};
const chatCommandsFor = (protocol) => Object.keys(CHAT_COMMANDS).filter((name) => CHAT_COMMANDS[name][2].includes(protocol));

/** Which command set a chat uses: Matrix for Matrix rooms, including bridged IRC and XMPP rooms ("!room:server"). */
function commandFamily(key) {
  if (!key) return 'sms';
  const protocol = threadProtocol(key);
  const route = typeof routeFor === 'function' ? routeFor(key) : null;
  if (['irc', 'xmpp'].includes(protocol) && route?.target?.startsWith('!')) return 'matrix';
  return protocol;
}

/** Like chat_commands.parse: {name, args, argv}, null for ordinary text, or an Error with a friendly message. */
function parseChatCommand(family, text) {
  if (!looksLikeChatCommand(text)) return null;
  const space = text.indexOf(' ');
  const word = text.slice(1, space < 0 ? undefined : space);
  const rest = space < 0 ? '' : text.slice(space + 1).trim();
  const name = knownChatCommand(word);
  if (!CHAT_PROTOCOL_NAMES[family]) {
    if (!name) return null;  // "/s" or "/shrug" in a text is just text.
    throw new Error('Commands work in IRC, Matrix, and XMPP chats. Start with // to send “' + text.slice(0, 40) + '” as text.');
  }
  if (!name) throw new Error('Unknown command /' + word.slice(0, 30) + '. Type /help for the list, or start with // to send it as text.');
  if (!CHAT_COMMANDS[name][2].includes(family)) throw new Error('/' + name + ' isn’t available on ' + CHAT_PROTOCOL_NAMES[family] + '.');
  if (/[\r\n]/.test(rest)) throw new Error('Commands fit on one line');
  const argv = rest.split(/\s+/).filter(Boolean);
  if (argv.length < (CHAT_COMMAND_NEEDS[name] || 0)) throw new Error('Usage: ' + CHAT_COMMANDS[name][0]);
  return {name, args: word.toLowerCase() === 'back' ? '' : rest, argv};
}

/** The commands on a full page, from /help or the hint list. */
function openCommandHelp(family) {
  const section = element('section', '', 'settings-section');
  section.append(element('h3', CHAT_PROTOCOL_NAMES[family] + ' commands'));
  const list = element('dl', '', 'command-help');
  for (const name of chatCommandsFor(family)) list.append(element('dt', CHAT_COMMANDS[name][0]), element('dd', CHAT_COMMANDS[name][1]));
  section.append(list, element('p', 'Start a message with // to send a line that begins with / as written. Commands only work in IRC, Matrix, and XMPP chats; they’re never sent to texts or email.', 'muted'));
  detailsPage('Commands', [section]);
}

/**
 * Before a message is sent (app.js submit): runs a slash command, or returns what to send. {text, literal} sends text
 * (literal: it began with //); {handled: true, clear} means nothing more to send.
 */
async function chatCommandDraft(draft) {
  hideCommandHints();
  if (draft.startsWith('//')) return {text: draft.slice(1), literal: true};
  if (!looksLikeChatCommand(draft) || !active) return {text: draft};
  const family = commandFamily(active);
  let parsed;
  try { parsed = parseChatCommand(family, draft.trim()); } catch (error) { showError(error); return {handled: true}; }
  if (!parsed || parsed.name === 'me') return {text: draft};  // /me is a message: replies, effects, and Send later still apply.
  if (parsed.name === 'help') { openCommandHelp(family); return {handled: true, clear: true}; }
  if (typeof sampleOn !== 'undefined' && sampleOn) {
    say('Commands run in your real chats. Nothing in the sample inbox is sent.');
    return {handled: true};
  }
  const key = active;
  const request = crypto.randomUUID ? crypto.randomUUID() : String(Date.now()) + Math.random().toString(16).slice(2);
  try {
    const result = await api('protocols/command', {address: threadAddress(key), protocol: threadProtocol(key), text: draft.trim(), request});
    say(result.notice || (result.message ? 'Sending…' : 'Done.'));
    await refresh().catch(() => {});
    if (result.open) select(threadKey({address: result.open.address, protocol: result.open.protocol}));
    return {handled: true, clear: true};
  } catch (error) {
    showError(error);
    return {handled: true};
  }
}

// The hint list above the message box while you type "/" in an IRC, Matrix, or XMPP chat.

function commandHintsNode() {
  let node = document.getElementById('command-hints');
  if (!node) {
    node = element('div', '', 'command-hints');
    node.id = 'command-hints';
    node.hidden = true;
    node.setAttribute('role', 'listbox');
    node.setAttribute('aria-label', 'Commands');
    const box = get('body');
    const row = box.parentElement && box.parentElement !== get('schedule') ? box.parentElement : box;
    row.before(node);
  }
  return node;
}

function hideCommandHints() {
  const node = document.getElementById('command-hints');
  if (node) node.hidden = true;
}

function matchingCommands() {
  const value = get('body').value;
  if (!active || !looksLikeChatCommand(value) && value !== '/' || /\s/.test(value)) return [];
  const family = commandFamily(active);
  if (!CHAT_PROTOCOL_NAMES[family]) return [];
  const typed = value.slice(1).toLowerCase();
  return chatCommandsFor(family).filter((name) => name.startsWith(typed)).slice(0, 8);
}

function showCommandHints() {
  const node = commandHintsNode();
  const names = matchingCommands();
  node.replaceChildren();
  node.hidden = !names.length;
  for (const name of names) {
    const option = element('button', '', 'command-hint');
    option.type = 'button';
    option.setAttribute('role', 'option');
    option.append(element('strong', CHAT_COMMANDS[name][0]), element('span', CHAT_COMMANDS[name][1]));
    option.addEventListener('mousedown', (event) => event.preventDefault());  // Keeps the message box focused.
    option.addEventListener('click', () => completeCommand(name));
    node.append(option);
  }
}

function completeCommand(name) {
  const box = get('body');
  box.value = '/' + name + (name === 'help' ? '' : ' ');
  hideCommandHints();
  box.focus();
}

get('body').addEventListener('input', showCommandHints);
get('body').addEventListener('blur', () => setTimeout(hideCommandHints, 150));
get('body').addEventListener('keydown', (event) => {
  const node = document.getElementById('command-hints');
  if (!node || node.hidden) return;
  if (event.key === 'Escape') { event.preventDefault(); event.stopPropagation(); hideCommandHints(); }
  else if (event.key === 'Tab' && !event.shiftKey) { event.preventDefault(); completeCommand(matchingCommands()[0]); }
}, true);

// How chats show what happens in them.

const hideJoins = () => localStorage.getItem(HIDE_JOINS_KEY) === 'on';

/** An event line (app.js showMessages) in the outlined note style, or null when joins and leaves are hidden. */
function chatEventLine(message) {
  const kind = message.event_kind || '';
  if (['join', 'part', 'quit'].includes(kind) && hideJoins()) return null;
  const line = element('p', message.body + ' · ' + clock(timeOf(message)),
    'event notice chat-event p-' + (message.protocol || 'irc') + (kind ? ' event-' + kind : ''));
  line.setAttribute('role', 'note');
  return line;
}

/** "/me waves" reads "* maya waves"; a message that names you gets a mention marker (app.js showMessages). */
function chatActionText(message, bubble, row) {
  if (message.attachment) return;
  const body = message.body || '';
  if (body.startsWith('/me ') && internet(message.protocol)) {
    const who = message.type !== 1 ? 'you' : (message.author || 'someone');
    bubble.replaceChildren(element('em', '* ' + who + ' ' + body.slice(4), 'action-text'));
    row.classList.add('action');
  }
  if (message.type === 1 && message.mention) {
    row.classList.add('mention');
    const mark = element('span', '@', 'mention-mark');
    mark.title = 'Mentions you';
    mark.setAttribute('aria-label', 'Mentions you');
    bubble.prepend(mark);
  }
  if (message.type === 1 && message.notice && !row.classList.contains('notice-message')) row.classList.add('notice-message');
}

/** The setting beside a channel's or room's members: show or hide joins, leaves, and quits (this browser). */
function joinsToggle() {
  // A switch at the row's end, like the other on/off rows in details (switch-row in chat-features.css).
  const label = element('label', '', 'check-row switch-row');
  const box = Object.assign(element('input', ''), {type: 'checkbox', checked: !hideJoins()});
  box.setAttribute('role', 'switch');
  box.addEventListener('change', () => {
    localStorage.setItem(HIDE_JOINS_KEY, box.checked ? 'off' : 'on');
    showMessages(true);
  });
  label.append(element('span', 'Show joins, leaves, and quits in channels and rooms'), box);
  return label;
}

// IRC explainers, the same words as the phone's (IrcIntro in IrcJoins.kt): a channel is a public room (said once, the
// first time you open one), and the server chat is the network talking to you, named "Libera.Chat · network messages".

// irc-intro:start (plain rules with no page in them; scripts/test-local runs this part in Node)
const IRC_SERVER_SUFFIX = ' · network messages';
const IRC_SERVER_INTRO = 'This is the network talking to you: welcome notes, sign-in answers, and problems joining channels. '
  + 'You can type commands here, like /join #channel or /msg NickServ help.';
const ircChannelIntro = (channel) => 'IRC channels are public rooms. Anyone in ' + channel + ' can read what you write. Moderators can remove people.';

/** "Libera.Chat · network messages" for a server chat stored under its host (irc.libera.chat). */
function serverChatTitle(stored, directory) {
  const text = String(stored || '').trim();
  if (text.endsWith(IRC_SERVER_SUFFIX)) return text;
  const known = (directory || []).find((entry) => entry.host === text.toLowerCase());
  return (known ? known.name : text || 'IRC') + IRC_SERVER_SUFFIX;
}
// irc-intro:end

const IRC_INTRO_SEEN = 'fluid.ircChannelIntroSeen';

/** The line at the top of an IRC channel (until Got it) or server chat, or null. */
function ircIntroNode(key) {
  if (!key || threadProtocol(key) !== 'irc' || (typeof sampleOn !== 'undefined' && sampleOn)) return null;
  const route = typeof routeFor === 'function' ? routeFor(key) : null;
  if (!route) return null;
  const channel = route.group && /^[#&]/.test(route.target || '');
  if (!route.server_chat && (!channel || localStorage.getItem(IRC_INTRO_SEEN) === '1')) return null;
  const node = element('div', '', 'notice irc-intro');
  node.setAttribute('role', 'note');
  node.append(element('p', route.server_chat ? IRC_SERVER_INTRO : ircChannelIntro(route.target)));
  if (!route.server_chat) {
    const ok = element('button', 'Got it');
    ok.type = 'button';
    ok.addEventListener('click', () => { localStorage.setItem(IRC_INTRO_SEEN, '1'); node.remove(); });
    node.append(ok);
  }
  return node;
}

if (typeof threadTitle === 'function') {
  const plainTitle = threadTitle;
  // eslint-disable-next-line no-func-assign
  threadTitle = function (key) {
    const title = plainTitle(key);
    const route = key && typeof routeFor === 'function' ? routeFor(key) : null;
    return route?.server_chat && route.protocol === 'irc'
      ? serverChatTitle(route.title || title, typeof SERVER_DIRECTORY === 'object' ? SERVER_DIRECTORY.irc : []) : title;
  };
}

// Headers and members: bots are automated, channels show their topic, and @ and + mark operators and voice.

const PREFIX_RANK = '~&@%+';
if (typeof headerSubtitle === 'function') {
  const plainSubtitle = headerSubtitle;
  // eslint-disable-next-line no-func-assign
  headerSubtitle = function (key) {
    const base = plainSubtitle(key);
    if (!key || !internet(threadProtocol(key))) return base;
    const route = typeof routeFor === 'function' ? routeFor(key) : null;
    if (route?.server_chat) return protocolName(threadProtocol(key)) + ' · Server messages and command replies';
    if (route?.automated) return protocolName(threadProtocol(key)) + ' · Automated';
    const topic = ircChannels[threadAddress(key)]?.topic;
    return topic ? base + ' · ' + (topic.length > 90 ? topic.slice(0, 89) + '…' : topic) : base;
  };
}
if (typeof threadMembers === 'function') {
  const plainMembers = threadMembers;
  // eslint-disable-next-line no-func-assign
  threadMembers = function (key) {
    const members = plainMembers(key);
    const modes = key ? ircChannels[threadAddress(key)]?.modes : null;
    if (!modes) return members;
    for (const member of members) {
      const prefix = modes[member.address] || modes[member.name];
      if (prefix && !member.name.startsWith(prefix)) member.name = prefix + member.name;
    }
    const rank = (member) => { const at = PREFIX_RANK.indexOf(member.name[0]); return at < 0 ? PREFIX_RANK.length : at; };
    return members.map((member, index) => [member, index]).sort((a, b) => rank(a[0]) - rank(b[0]) || a[1] - b[1]).map(([member]) => member);
  };
}
// The joins setting and the command list sit in a channel's or room's details.
if (typeof openDetails === 'function') {
  const plainDetails = openDetails;
  // eslint-disable-next-line no-func-assign
  openDetails = async function (options) {
    await plainDetails(options);
    const key = active;
    if (!key || !internet(threadProtocol(key)) || !CHAT_PROTOCOL_NAMES[commandFamily(key)]) return;
    const body = document.querySelector('#details-page .details-body');
    if (!body) return;
    const section = element('section', '', 'settings-section');
    const help = element('button', 'Commands you can type here');
    help.type = 'button';
    help.addEventListener('click', () => openCommandHelp(commandFamily(key)));
    section.append(element('h3', 'Chat'));
    if (typeof threadIsGroup === 'function' && threadIsGroup(key)) section.append(joinsToggle());
    // Channels this network needs joined first (irc_client.prerequisite), each with Remove.
    const route = typeof routeFor === 'function' ? routeFor(key) : null;
    for (const first of route?.protocol === 'irc' ? route.join_first || [] : []) {
      const line = element('div', '', 'join-first');
      const remove = element('button', 'Remove');
      remove.type = 'button';
      remove.setAttribute('aria-label', 'Stop joining ' + first + ' first');
      remove.addEventListener('click', async () => {
        remove.disabled = true;
        try {
          const result = await api('protocols/joinfirst', {address: route.address, protocol: 'irc', remove: first});
          route.join_first = result.join_first;
          line.replaceChildren(element('p', 'Stopped. If the network still needs ' + first + ', FluidMessages adds it back.', 'muted'));
        } catch (error) { remove.disabled = false; line.append(element('p', error.message, 'muted')); }
      });
      line.append(element('span', 'Also joins ' + first + ' first (this network needs it)'), remove);
      section.append(line);
    }
    section.append(help);
    const members = [...body.children].find((node) => node.querySelector?.('.member-list'));
    (members || body.children[1] || body.lastElementChild)?.after(section);
  };
}

// NickServ's identify notices as one card in an IRC chat, like the phone's (NickServ.kt): "This nickname is
// registered" with a password field and Identify, "Identified as kit", or "That password didn’t work". NickServ's own
// words stay under "Show what NickServ said". The password goes over the connection, never into a chat.

// nickserv:start (plain rules with no page in them; scripts/test-local runs this part in Node)
const NICKSERV_FORMATTING = /\x03(?:\d{1,2}(?:,\d{1,2})?)?|[\x00-\x1f\x7f]/g;
const NICKSERV_PREFIX = /^nickserv:\s*/i;
const NICKSERV_FAILED = /\b(invalid password|password incorrect|incorrect password|wrong password|identify failed|authentication failed)\b/i;
const NICKSERV_IDENTIFIED = /\b(you are now (identified|recognized|logged in)|password accepted|(you are|you're) (successfully|already) (identified|logged in)|successfully identified)\b/i;
const NICKSERV_ASK = /\b(this )?nick(name)? is registered\b|\bnick(name)? is owned by someone else\b|\bplease (identify|authenticate)\b/i;
const NICKSERV_NOTE = /\bidentify\b|\bnick(name)? (will be|has been|is being|is now being) changed\b|\bchoose a different nick|\bchange your nick|\bif (this|it) is your nick|^last login from\b/i;
const NICKSERV_NAMED = /(?:identified|recognized|logged in) (?:for|as) ([^\s.,]+)|password for ([^\s.,]+)|failed (?:for|as) ([^\s.,]+)/i;

const isNickServ = (author) => String(author || '').trim().toLowerCase() === 'nickserv';
const nickServPlain = (text) => String(text || '').replace(NICKSERV_FORMATTING, '').replace(NICKSERV_PREFIX, '').trim();

/** 'ask', 'identified', 'failed', 'note' (another line of the same notice), or null for anything else. */
function nickServKind(text) {
  const words = nickServPlain(text);
  if (!words || words.length > 400) return null;
  if (NICKSERV_FAILED.test(words)) return 'failed';
  if (NICKSERV_IDENTIFIED.test(words)) return 'identified';
  if (NICKSERV_ASK.test(words)) return 'ask';
  return NICKSERV_NOTE.test(words) ? 'note' : null;
}

function nickServNick(text) {
  const match = NICKSERV_NAMED.exec(nickServPlain(text));
  return match ? match.slice(1).find(Boolean) || '' : '';
}

/**
 * Cards for runs of incoming NickServ lines about identifying, each with at least one that asks, accepts, or
 * refuses: [{first, last, state, nick, said, answered}]. Lines: [{author, text, incoming, event}].
 */
function nickServFold(lines, nickServChat = false) {
  const cards = [];
  let start = -1;
  const close = (end) => {
    if (start < 0) return;
    const run = lines.slice(start, end + 1);
    const state = run.map((line) => nickServKind(line.text)).filter((kind) => kind && kind !== 'note').pop();
    if (state) {
      cards.push({first: start, last: end, state, nick: run.map((line) => nickServNick(line.text)).filter(Boolean).pop() || '',
        said: run.map((line) => nickServPlain(line.text)), answered: false});
    }
    start = -1;
  };
  lines.forEach((line, index) => {
    const from = line.incoming && !line.event && (isNickServ(line.author) || !line.author && nickServChat);
    if (from && nickServKind(line.text)) { if (start < 0) start = index; } else close(index - 1);
  });
  close(lines.length - 1);
  return cards.map((card, index) => ({...card, answered: index < cards.length - 1}));
}

function nickServTitle(card, fallbackNick) {
  const who = card.nick || fallbackNick || '';
  if (card.state === 'identified') return who ? 'Identified as ' + who : 'Identified';
  return card.state === 'failed' ? 'That password didn’t work' : 'This nickname is registered';
}

function nickServDetail(card, saved) {
  if (card.state === 'identified') return 'NickServ accepted your password.';
  if (card.answered) return 'NickServ answered below.';
  if (card.state === 'failed') return 'NickServ didn’t accept it. Check the password and try again.';
  return saved ? 'Your saved password is sent each time you connect. If NickServ asks again, type it here.'
    : 'This nickname belongs to a registered account. If it’s yours, type its password. It goes only to NickServ, never to a channel. '
      + 'If it isn’t yours, pick another nickname in the IRC account settings.';
}
// nickserv:end

// What's typed in the card survives a redraw (showMessages redraws when anything changes). Never saved anywhere.
let nickServDraft = {key: '', password: '', save: true, open: false, result: '', focused: false};

/** The cards for this chat's history: {at: Map(index → card), hidden: Set(index)}, or null outside IRC. */
function nickServCards(history) {
  if (!active || threadProtocol(active) !== 'irc' || sampleOn) return null;
  const chat = isNickServ(routeFor(active)?.target);
  const cards = nickServFold(history.map((message) => ({author: message.author, text: message.body, incoming: message.type === 1,
    event: !!message.event || !!message.attachment})), chat);
  if (!cards.length) return null;
  const at = new Map(), hidden = new Set();
  for (const card of cards) {
    at.set(card.first, card);
    for (let index = card.first + 1; index <= card.last; index++) hidden.add(index);
  }
  return {at, hidden};
}

/** One card, drawn in place of the lines it folds. */
function nickServCardNode(card, history) {
  const route = routeFor(active);
  const account = chatAccounts.find((item) => item.protocol === 'irc' && item.id === route?.account)
    || chatAccounts.find((item) => item.protocol === 'irc');
  const saved = !!account?.fields?.nickserv_password?.set;
  const key = active + ':' + (history[card.first]?.id || card.first);
  if (nickServDraft.key !== key) nickServDraft = {key, password: '', save: !saved, open: false, result: '', focused: false};
  const draft = nickServDraft;
  const node = element('section', '', 'nickserv-card');
  node.setAttribute('aria-label', 'NickServ');
  node.append(element('h3', nickServTitle(card, account?.fields?.nick || '')), element('p', nickServDetail(card, saved), 'muted'));
  if (!card.answered && card.state !== 'identified') {
    const form = element('form', '', 'nickserv-form');
    const label = element('label', '', 'nickserv-field');
    const input = Object.assign(element('input', ''), {type: 'password', autocomplete: 'current-password', value: draft.password, maxLength: 300});
    label.append(element('span', 'NickServ password'), input);
    const keep = checkbox(saved ? 'Replace the saved password on this IRC account' : 'Save it to this IRC account', draft.save,
      (value) => { draft.save = value; });
    const go = element('button', 'Identify', 'primary');
    go.type = 'submit';
    const status = element('p', draft.result, 'muted');
    status.setAttribute('role', 'status');
    input.addEventListener('input', () => { draft.password = input.value; });
    input.addEventListener('focus', () => { draft.focused = true; });
    input.addEventListener('blur', () => { draft.focused = false; });
    form.addEventListener('submit', async (event) => {
      event.preventDefault();
      if (!input.value) return;
      go.disabled = true;
      go.textContent = 'Sending…';
      try {
        const result = await api('protocols/identify', {address: threadAddress(active), protocol: 'irc', password: input.value, save: draft.save});
        draft.password = '';
        input.value = '';
        draft.result = result.notice || 'Sent to NickServ. Its answer shows here in a moment.';
      } catch (error) { draft.result = error.message; }
      status.textContent = draft.result;
      go.disabled = false;
      go.textContent = 'Identify';
    });
    form.append(label, keep, go, status);
    node.append(form);
    if (draft.focused) setTimeout(() => input.focus());
  }
  const more = element('button', draft.open ? 'Hide what NickServ said' : 'Show what NickServ said', 'nickserv-more');
  more.type = 'button';
  more.setAttribute('aria-expanded', String(draft.open));
  const said = element('div', '', 'nickserv-said');
  said.hidden = !draft.open;
  for (const line of card.said) said.append(element('p', line));
  more.addEventListener('click', () => {
    draft.open = !draft.open;
    said.hidden = !draft.open;
    more.textContent = draft.open ? 'Hide what NickServ said' : 'Show what NickServ said';
    more.setAttribute('aria-expanded', String(draft.open));
  });
  node.append(more, said);
  return node;
}
