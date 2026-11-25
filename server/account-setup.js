'use strict';
// Adding an account, made easy. Three parts:
// 1. Automatic setup: the email provider table, and the server found from a Matrix ID, an XMPP address, or an IRC
//    network's name. Keep the table and the wording in step with the phone's (AccountSetup.kt).
// 2. A friendly full page for each service, shown before its form, for someone who has never heard of it.
// 3. The helpers that fill in an account form as you type. A field you changed yourself is never overwritten.
// The first part is plain logic with no page in it; scripts/test-local runs it in Node.
// Loaded after server-directory.js and before companion.js's forms are used (element, field, labels come from app.js
// and companion.js at call time).

// account-setup:start (scripts/test-local runs this block on its own, with server-directory.js)

// Email providers by domain. imap and smtp are [host, port]. FluidMessages always uses TLS: IMAP on 993 and SMTP on 465
// use it from the start; IMAP on 143 and SMTP on any other port (like 587) upgrade with STARTTLS (accounts.py).
const EMAIL_PROVIDERS = [
  {id: 'gmail', name: 'Gmail', domains: ['gmail.com', 'googlemail.com'], imap: ['imap.gmail.com', 993], smtp: ['smtp.gmail.com', 465],
    password: 'App password', note: 'Gmail needs an app password, not your normal password. Turn on 2-Step Verification first.',
    link: ['Get a Gmail app password', 'https://myaccount.google.com/apppasswords']},
  {id: 'outlook', name: 'Outlook', domains: ['outlook.com', 'hotmail.com', 'live.com', 'msn.com'], imap: ['outlook.office365.com', 993],
    smtp: ['smtp-mail.outlook.com', 587], note: 'Outlook may not allow sign-in from other apps.',
    link: ['See what Microsoft says', 'https://support.microsoft.com/account-billing/5896ed9b-4263-e681-128a-a6f2979a7944']},
  {id: 'yahoo', name: 'Yahoo Mail', domains: ['yahoo.com', 'ymail.com', 'rocketmail.com'], imap: ['imap.mail.yahoo.com', 993],
    smtp: ['smtp.mail.yahoo.com', 465], password: 'App password', note: 'Yahoo needs an app password, not your normal password.',
    link: ['Get a Yahoo app password', 'https://login.yahoo.com/account/security']},
  {id: 'aol', name: 'AOL Mail', domains: ['aol.com'], imap: ['imap.aol.com', 993], smtp: ['smtp.aol.com', 465],
    password: 'App password', note: 'AOL needs an app password, not your normal password.',
    link: ['Get an AOL app password', 'https://login.aol.com/account/security']},
  {id: 'icloud', name: 'iCloud Mail', domains: ['icloud.com', 'me.com', 'mac.com'], imap: ['imap.mail.me.com', 993], smtp: ['smtp.mail.me.com', 587],
    password: 'App-specific password', note: 'iCloud needs an app-specific password, not your normal password.',
    link: ['Get an app-specific password', 'https://account.apple.com/account/manage']},
  {id: 'fastmail', name: 'Fastmail', domains: ['fastmail.com', 'fastmail.fm'], imap: ['imap.fastmail.com', 993], smtp: ['smtp.fastmail.com', 465],
    password: 'App password', note: 'Fastmail needs an app password, not your normal password.',
    link: ['Get a Fastmail app password', 'https://app.fastmail.com/settings/security/apps']},
  {id: 'zoho', name: 'Zoho Mail', domains: ['zoho.com', 'zohomail.com'], imap: ['imap.zoho.com', 993], smtp: ['smtp.zoho.com', 465],
    password: 'App password', note: 'Zoho needs an app password, not your normal password.',
    link: ['Get a Zoho app password', 'https://accounts.zoho.com/home#security/app_password']},
  {id: 'gmx', name: 'GMX', domains: ['gmx.com', 'gmx.us', 'gmx.net', 'gmx.de'], imap: ['imap.gmx.com', 993], smtp: ['mail.gmx.com', 587]},
  {id: 'mailcom', name: 'mail.com', domains: ['mail.com'], imap: ['imap.mail.com', 993], smtp: ['smtp.mail.com', 587]},
  // Proton has no IMAP of its own: the Proton Mail Bridge app offers it on the computer it runs on, at these ports.
  {id: 'proton', name: 'Proton Mail', domains: ['proton.me', 'protonmail.com', 'pm.me'], imap: ['127.0.0.1', 1143], smtp: ['127.0.0.1', 1025],
    bridge: true, password: 'Bridge password',
    note: 'Proton needs the Proton Mail Bridge app running on this computer. The password is the one Bridge shows, not your Proton password.'},
];

/** How a port connects, by the same rule as the server (accounts.py imap_starttls and smtp_starttls). */
const emailSecurity = (kind, port) => (kind === 'imap' ? Number(port) === 143 : Number(port) !== 465) ? 'STARTTLS' : 'TLS';

/** "Sam@Example.ORG " → "Sam@example.org": spaces out, and the part after the last @ in lower case. */
function tidyAddress(text) {
  const value = String(text || '').replace(/\s+/g, '');
  const at = value.lastIndexOf('@');
  return at < 0 ? value : value.slice(0, at + 1) + value.slice(at + 1).toLowerCase().replace(/\.+$/, '');
}

const SETUP_DOMAIN = /^(?=.{1,253}$)([a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]{2,63}$/;

/** The domain of an address like sam@example.org, in lower case, or '' while it isn't a whole address yet. */
function addressDomain(text) {
  const value = tidyAddress(text);
  const at = value.lastIndexOf('@');
  const domain = at > 0 ? value.slice(at + 1) : '';
  return SETUP_DOMAIN.test(domain) ? domain : '';
}

/**
 * The mail servers for [address], or null while it isn't a whole address. A provider in the table gives
 * {known: true, name, fields, note, link, password}. Any other domain gives a guess (imap.<domain> 993 and
 * smtp.<domain> 465, guess: true) unless [guess] is false.
 */
function emailSetup(address, {guess = true} = {}) {
  const domain = addressDomain(address);
  if (!domain) return null;
  const provider = EMAIL_PROVIDERS.find((item) => item.domains.includes(domain));
  if (!provider && !guess) return null;
  const imap = provider ? provider.imap : ['imap.' + domain, 993];
  const smtp = provider ? provider.smtp : ['smtp.' + domain, 465];
  return {id: provider ? provider.id : '', name: provider ? provider.name : domain, domain, known: !!provider, guess: !provider,
    bridge: !!provider?.bridge, fields: {imap_host: imap[0], imap_port: imap[1], smtp_host: smtp[0], smtp_port: smtp[1]},
    security: {imap: emailSecurity('imap', imap[1]), smtp: emailSecurity('smtp', smtp[1])},
    password: provider?.password || 'Password',
    note: provider ? provider.note || '' : 'This is our best guess. If it doesn’t work, look up “IMAP settings” on your email provider’s help page.',
    link: provider?.link ? {label: provider.link[0], url: provider.link[1]} : null};
}

/** "@Sam:Example.org" or "sam:example.org" → "@sam:example.org". Matrix IDs are lower case. */
function tidyMatrixId(text) {
  const value = String(text || '').replace(/\s+/g, '').toLowerCase();
  return value && !value.startsWith('@') && /^[^@:]+:[^:]/.test(value) ? '@' + value : value;
}

/**
 * The homeserver for a Matrix ID like @sam:example.org, or null while it isn't a whole ID. A server in Add server's
 * list gives its own address (matrix.org is at matrix-client.matrix.org); any other is https://<the part after the
 * colon>, a guess that is right for most servers.
 */
function matrixSetup(userId) {
  const match = /^@[a-z0-9._=\-/+]+:([a-z0-9.-]+(?::\d{1,5})?)$/.exec(tidyMatrixId(userId));
  if (!match || !SETUP_DOMAIN.test(match[1].replace(/:\d+$/, ''))) return null;
  const known = typeof SERVER_DIRECTORY === 'object' ? (SERVER_DIRECTORY.matrix || []).find((entry) => entry.name === match[1]) : null;
  return {domain: match[1], homeserver: known ? known.homeserver : 'https://' + match[1], known: !!known, guess: !known,
    name: known ? known.name : match[1]};
}

/** "matrix.example.org/" → "https://matrix.example.org". */
function tidyHomeserver(text) {
  const value = String(text || '').trim().replace(/\s+/g, '').replace(/\/+$/, '');
  if (!value) return '';
  return (/^https?:\/\//i.test(value) ? value.replace(/^http:/i, 'https:') : 'https://' + value).replace(/^https:\/\/[^/]+/i, (host) => host.toLowerCase());
}

/** The server of an XMPP address like sam@example.org, or null while it isn't a whole address. */
function xmppSetup(jid) {
  const value = tidyAddress(jid).split('/')[0];
  const domain = addressDomain(value);
  if (!domain || value.indexOf('@') !== value.lastIndexOf('@')) return null;
  const known = typeof SERVER_DIRECTORY === 'object' ? (SERVER_DIRECTORY.xmpp || []).find((entry) => entry.domain === domain) : null;
  return {domain, known: !!known, name: known ? known.name : domain};
}

/** The IRC network in Add server's list with this name or host ("libera.chat", "Libera.Chat", "irc.libera.chat"). */
function ircNetwork(text) {
  const value = String(text || '').trim().toLowerCase().replace(/^ircs?:\/\//, '').replace(/[/:].*$/, '');
  if (!value || typeof SERVER_DIRECTORY !== 'object') return null;
  return (SERVER_DIRECTORY.irc || []).find((entry) => entry.host === value || entry.name.toLowerCase() === value
    || entry.host.replace(/^(irc|ssl)\./, '') === value) || null;
}

/** "irc.Libera.Chat:6697 " or "ircs://irc.libera.chat/" → "irc.libera.chat". */
function tidyHost(text) {
  return String(text || '').trim().toLowerCase().replace(/^[a-z]+:\/\//, '').replace(/[/:].*$/, '');
}

/**
 * A nickname made from a name: "Sam Rivera" → "Sam", "@sam.r:example.org" → "sam_r". Letters, digits, _ and - only,
 * starting with a letter, 16 at most. '' when nothing usable is left.
 */
function suggestNick(name) {
  let text = String(name || '').trim();
  if (text.startsWith('@')) text = text.slice(1);
  text = text.split(/[@:\s]/)[0].normalize('NFD').replace(/\p{M}/gu, '').replace(/[.]+/g, '_').replace(/[^A-Za-z0-9_-]/g, '');
  return text.replace(/^[^A-Za-z]+/, '').slice(0, 16);
}

/**
 * What someone typed in an IRC join box, as a channel name: "python", " #python ", or "#python, please" → "#python".
 * '' when it can't be a channel (empty, or nothing but the #).
 */
function channelName(text) {
  const word = String(text || '').trim().split(/[\s,]+/)[0] || '';
  const name = /^[#&]/.test(word) ? word : '#' + word;
  // eslint-disable-next-line no-control-regex
  return /^[#&][^\s,\x00-\x1f]{1,49}$/.test(name) ? name : '';
}

/** Well-known, long-lived public channels of an IRC network in the list ([] for any other), for "Popular here". */
function popularChannels(host) {
  const network = ircNetwork(host);
  return network && Array.isArray(network.popular) ? network.popular.map(([target, topic]) => ({target, name: target, topic, popular: true})) : [];
}

/** An account's fields tidied before saving: spaces out, domains and hosts in lower case, channels with their #. */
function tidyAccountFields(protocol, fields) {
  const out = {...fields};
  const text = (name, tidy) => { if (typeof out[name] === 'string') out[name] = tidy(out[name]); };
  if (protocol === 'email') {
    text('address', tidyAddress);
    text('imap_host', tidyHost);
    text('smtp_host', tidyHost);
  } else if (protocol === 'matrix') {
    text('user_id', tidyMatrixId);
    text('homeserver', tidyHomeserver);
  } else if (protocol === 'xmpp') {
    text('jid', (value) => tidyAddress(value).toLowerCase());
    text('host', tidyHost);
  } else if (protocol === 'irc') {
    text('server', tidyHost);
    text('nick', (value) => value.replace(/\s+/g, ''));
    text('channels', (value) => value.split(',').map(channelName).filter(Boolean).join(', '));
  }
  for (const [name, value] of Object.entries(out)) if (value === '') delete out[name];
  return out;
}

// The one-liners are the same on the phone. Written for someone who has never heard of the service.
const SERVICE_GUIDES = {
  matrix: {
    title: 'Matrix',
    line: 'Modern group chat that no single company owns. Used by communities, schools, and workplaces.',
    good: 'Good for group rooms, one-to-one chats, and calls.',
    start: 'Most people start at matrix.org. It’s free.',
    example: 'Use matrix.org',
    exampleNote: 'Fills in the server for you',
    link: ['Make a free account at matrix.org', 'https://matrix.org/try-matrix/'],
    steps: ['Type your Matrix ID. It looks like @sam:matrix.org.', 'We fill in the server from your ID.',
      'Paste your access token. It works like a password. Your rooms then show up in Messages.'],
  },
  xmpp: {
    title: 'XMPP',
    line: 'Simple, private one-to-one and group chat. It works like email: pick any provider and talk to people on all the others.',
    good: 'Good for chatting with friends and family, whatever provider they use.',
    start: 'A well-known free provider is jabber.org. Make an account on their website, then come back here.',
    example: 'Use jabber.org',
    exampleNote: 'Fills in the end of your address',
    link: ['Open jabber.org', 'https://www.jabber.org/'],
    steps: ['Type your address. It looks like an email address: sam@jabber.org.', 'We find the server from your address.',
      'Type your password and save. Your chats show up in Messages.'],
  },
  email: {
    title: 'Email',
    line: 'Your normal email, shown as chats. Only the conversations you choose show up.',
    good: 'Good for people who only use email. The rest of your mailbox stays where it is.',
    start: 'You don’t need a new account.',
    example: 'Use the email you already have',
    exampleNote: 'Gmail, Outlook, Yahoo, and others',
    steps: ['Type your email address. For well-known providers, we fill in the rest.',
      'Type your password. Some providers ask for a special “app password”. We tell you if yours does.',
      'Start a chat with someone’s email address. Only chats you start or pick show up.'],
  },
  irc: {
    title: 'IRC',
    line: 'Big public chat rooms for hobbies and open-source projects. No sign-up needed: just pick a nickname.',
    good: 'Good for asking questions and meeting people who share a hobby.',
    start: 'You don’t need an account. Libera.Chat is a big, friendly network to start with.',
    example: 'Use Libera.Chat',
    exampleNote: 'Just pick a nickname',
    steps: ['Pick a network. We fill in its address for you.', 'Pick a nickname. This is the name other people see.',
      'Join rooms, called channels. We show you popular ones to start with.'],
    // When a network wants a registered nickname: the same words as the phone's guide (ServiceGuides.IRC_SIGN_IN).
    signIn: ['If the network asks you to sign in',
      'Your nickname is the name people see. Anyone can use a free one. Registering it with the network’s NickServ keeps it yours, with a password.',
      'Some networks, like Libera.Chat, only let registered nicknames connect from a VPN, a work or school network, or a cloud server. Some channels only let in people signed in to their nickname. FluidMessages tells you when this happens.',
      'To register, connect once from home Wi-Fi or mobile data. In the network’s chat, type /msg NickServ REGISTER, a new password, and your email.',
      'Open the email the network sends you and follow its steps.',
      'Then edit the account: put the name you registered in Account name and that password in Password, and save. FluidMessages signs you in each time it connects.'],
  },
};

/** What the tappable example fills in: the form's preset, and the entry in Add server's list where there is one. */
function guideExample(protocol) {
  const list = typeof SERVER_DIRECTORY === 'object' ? SERVER_DIRECTORY[protocol] || [] : [];
  const entry = {matrix: list.find((item) => item.name === 'matrix.org'), xmpp: list.find((item) => item.domain === 'jabber.org'),
    irc: list.find((item) => item.name === 'Libera.Chat')}[protocol] || null;
  const preset = !entry ? {} : protocol === 'matrix' ? {homeserver: entry.homeserver} : protocol === 'xmpp' ? {jid: '@' + entry.domain}
    : {server: entry.host, port: String(entry.port)};
  return {entry, preset};
}
// account-setup:end

if (typeof module !== 'undefined') {
  module.exports = {EMAIL_PROVIDERS, emailSecurity, tidyAddress, addressDomain, emailSetup, tidyMatrixId, matrixSetup, tidyHomeserver, xmppSetup,
    ircNetwork, tidyHost, suggestNick, channelName, popularChannels, tidyAccountFields, SERVICE_GUIDES, guideExample};
}

// ---- The guide page ----

// One original picture per service, drawn with the theme's own colors (features.css .sg-*), so it follows light and
// dark and every color theme. Static markup, never user data.
const GUIDE_PICTURES = {
  // Three servers joined together, with a chat between them: nobody owns the whole thing.
  matrix: '<line class="sg-link" x1="70" y1="92" x2="160" y2="34"/><line class="sg-link" x1="250" y1="92" x2="160" y2="34"/>'
    + '<line class="sg-link" x1="70" y1="92" x2="250" y2="92"/>'
    + '<rect class="sg-box" x="132" y="14" width="56" height="40" rx="10"/><rect class="sg-box" x="42" y="72" width="56" height="40" rx="10"/>'
    + '<rect class="sg-box" x="222" y="72" width="56" height="40" rx="10"/>'
    + '<circle class="sg-dot" cx="150" cy="34" r="5"/><circle class="sg-dot" cx="170" cy="34" r="5"/>'
    + '<circle class="sg-dot" cx="60" cy="92" r="5"/><circle class="sg-dot" cx="80" cy="92" r="5"/>'
    + '<circle class="sg-dot" cx="240" cy="92" r="5"/><circle class="sg-dot" cx="260" cy="92" r="5"/>'
    + '<rect class="sg-bubble" x="128" y="66" width="64" height="26" rx="13"/><circle class="sg-on" cx="146" cy="79" r="3"/>'
    + '<circle class="sg-on" cx="160" cy="79" r="3"/><circle class="sg-on" cx="174" cy="79" r="3"/>',
  // Two people on two different providers, talking straight across.
  xmpp: '<rect class="sg-box" x="24" y="24" width="104" height="80" rx="14"/><rect class="sg-box" x="192" y="24" width="104" height="80" rx="14"/>'
    + '<circle class="sg-dot" cx="76" cy="52" r="11"/><path class="sg-dot" d="M54 90a22 20 0 0 1 44 0z"/>'
    + '<circle class="sg-dot" cx="244" cy="52" r="11"/><path class="sg-dot" d="M222 90a22 20 0 0 1 44 0z"/>'
    + '<path class="sg-arrow" d="M136 54h44m-9-8 9 8-9 8"/><path class="sg-arrow" d="M184 80h-44m9-8-9 8 9 8"/>',
  // A letter on the left becomes chat bubbles on the right.
  email: '<rect class="sg-box" x="30" y="32" width="96" height="66" rx="10"/><path class="sg-line" d="M32 38l46 34 46-34"/>'
    + '<path class="sg-arrow" d="M140 65h34m-9-8 9 8-9 8"/>'
    + '<rect class="sg-in" x="190" y="26" width="84" height="26" rx="13"/><rect class="sg-bubble" x="212" y="58" width="84" height="26" rx="13"/>'
    + '<rect class="sg-in" x="190" y="90" width="60" height="22" rx="11"/>',
  // One big room with a # on the door and many people in it.
  irc: '<rect class="sg-box" x="24" y="16" width="272" height="96" rx="16"/><rect class="sg-bubble" x="38" y="28" width="46" height="34" rx="10"/>'
    + '<path class="sg-hash" d="M54 36l-3 18M68 36l-3 18M48 42h22M47 49h22"/>'
    + '<circle class="sg-dot" cx="118" cy="80" r="9"/><circle class="sg-dot" cx="150" cy="80" r="9"/><circle class="sg-dot" cx="182" cy="80" r="9"/>'
    + '<circle class="sg-dot" cx="214" cy="80" r="9"/><circle class="sg-dot" cx="246" cy="80" r="9"/>'
    + '<rect class="sg-in" x="104" y="30" width="96" height="18" rx="9"/><rect class="sg-in" x="208" y="30" width="72" height="18" rx="9"/>'
    + '<rect class="sg-in" x="104" y="52" width="60" height="12" rx="6"/>',
};

function guidePicture(protocol) {
  const template = document.createElement('template');
  template.innerHTML = '<svg class="service-guide-art p-' + protocol + '" viewBox="0 0 320 128" role="img" aria-hidden="true">'
    + GUIDE_PICTURES[protocol] + '</svg>';
  return template.content.firstElementChild;
}

/** A link that opens the provider's own page in a new tab, drawn as a button. Only addresses from this file. */
function outsideLink(label, url) {
  const link = element('a', label, 'outside-link');
  Object.assign(link, {href: url, target: '_blank', rel: 'noopener noreferrer'});
  link.append(element('span', ' ↗', 'outside-mark'));
  link.setAttribute('aria-label', label + ' (opens in a new tab)');
  return link;
}

/**
 * The full page shown before a service's form: what it is, where to get an account, what happens next, one big
 * Get started button, and a quieter way straight to the full form. [actions]: start(), example(), expert(), and
 * for servers more() (the list of public servers). Returns the nodes for the page.
 */
function serviceGuide(protocol, {start, example, expert, more}) {
  const guide = SERVICE_GUIDES[protocol];
  const hero = element('section', '', 'settings-section service-guide');
  const what = element('p', guide.line, 'service-guide-line');
  hero.append(guidePicture(protocol), element('h3', 'What is ' + guide.title + '?'), what, element('p', guide.good, 'muted'));

  const account = element('section', '', 'settings-section service-guide-start');
  account.append(element('h3', 'Don’t have an account yet?'), element('p', guide.start, 'muted'));
  const pick = element('button', '', 'settings-row service-guide-example');
  pick.type = 'button';
  const badge = element('span', labels[protocol][0], 'account-badge p-' + protocol);
  badge.setAttribute('aria-hidden', 'true');
  const words = element('span', '', 'account-words');
  words.append(element('strong', guide.example), element('span', guide.exampleNote, 'muted'));
  const chevron = element('span', '›', 'service-chevron');
  chevron.setAttribute('aria-hidden', 'true');
  pick.append(badge, words, chevron);
  pick.addEventListener('click', () => example());
  account.append(pick);
  const extras = element('div', '', 'button-row wrap');
  if (guide.link) extras.append(outsideLink(guide.link[0], guide.link[1]));
  if (more) {
    const others = element('button', 'See other public servers', 'quiet');
    others.type = 'button';
    others.addEventListener('click', () => more());
    extras.append(others);
  }
  if (extras.childElementCount) account.append(extras);

  const next = element('section', '', 'settings-section');
  const steps = element('ol', '', 'service-guide-steps');
  for (const text of guide.steps) steps.append(element('li', text));
  next.append(element('h3', 'What happens next'), steps);
  if (guide.signIn) {
    // IRC: registering a nickname for networks that ask you to sign in, and where its password goes.
    next.append(element('h3', guide.signIn[0]));
    for (const line of guide.signIn.slice(1)) next.append(element('p', line, 'muted'));
  }

  const go = element('div', '', 'service-guide-go');
  const begin = element('button', 'Get started', 'primary service-guide-begin');
  begin.type = 'button';
  begin.addEventListener('click', () => start());
  const skip = element('button', 'I already know what I’m doing', 'quiet');
  skip.type = 'button';
  skip.addEventListener('click', () => expert());
  go.append(begin, skip);
  return [hero, account, next, go];
}

// ---- Forms that fill themselves in ----

/** Sets [input] to [value] unless you typed something else there yourself. Returns whether it was set. */
function autoFill(input, value) {
  const text = String(value);
  if (input.value && input.dataset.auto !== input.value) return false;
  input.value = text;
  input.dataset.auto = text;
  return true;
}

/** Puts the cursor in a form's first empty box; an XMPP address that starts as "@jabber.org" gets it before the @. */
function focusAccountForm(root) {
  const boxes = [...root.querySelectorAll('input:not([type="checkbox"])')].filter((input) => input.offsetParent);
  const address = boxes.find((input) => input.name === 'jid' && input.value.startsWith('@'));
  if (address) {
    address.focus();
    address.setSelectionRange(0, 0);
  } else (boxes.find((input) => !input.value) || boxes[0])?.focus();
}

/** The label wrapped around [input] by field(). */
const fieldOf = (input) => input.closest('label');

function setupNote(after) {
  const note = element('div', '', 'setup-note');
  note.setAttribute('role', 'status');
  note.hidden = true;
  after.after(note);
  return note;
}

function moreOptions(summary, labelsIn, open) {
  const more = element('details', '', 'more setup-details');
  more.open = !!open;
  more.append(element('summary', summary), ...labelsIn.filter(Boolean));
  return more;
}

/**
 * Makes a new account's form fill itself in. [simple] (from Get started) tucks the settings most people never
 * change under Show details or More options; they are still sent when you save. Called by accountForm (companion.js)
 * for a new account only, so editing an account never changes what you saved.
 */
function setUpAccountForm(form, inputs, protocol, {simple = false, preset = {}} = {}) {
  // Typing in a field makes it yours: automatic setup leaves it alone from then on.
  for (const input of Object.values(inputs)) input.addEventListener('input', () => { delete input.dataset.auto; });
  const save = form.querySelector('button.primary');
  if (protocol === 'email') setUpEmailForm(form, inputs, simple);
  else if (protocol === 'matrix') setUpMatrixForm(form, inputs);
  else if (protocol === 'xmpp') setUpXmppForm(form, inputs, simple);
  else if (protocol === 'irc') setUpIrcForm(form, inputs, simple, preset);
  if (save && simple) save.textContent = protocol === 'irc' ? 'Connect' : 'Sign in';
}

function setUpEmailForm(form, inputs, simple) {
  const servers = ['imap_host', 'imap_port', 'imap_user', 'smtp_host', 'smtp_port', 'smtp_user', 'smtp_password'];
  // The ports the form starts with are ours to change.
  for (const name of ['imap_port', 'smtp_port']) if (inputs[name].value) inputs[name].dataset.auto = inputs[name].value;
  const note = setupNote(fieldOf(inputs.address));
  const password = fieldOf(inputs.imap_password);
  password.firstChild.textContent = 'Password';
  const hint = element('small', '', 'hint');
  password.append(hint);
  const summary = element('p', '', 'muted setup-summary');
  const details = moreOptions('Show details', [summary, ...servers.map((name) => fieldOf(inputs[name]))], !simple);
  password.after(details);
  Object.assign(inputs.address, {type: 'email', autocomplete: 'email', inputMode: 'email'});

  const apply = (guess) => {
    const setup = emailSetup(inputs.address.value, {guess});
    if (!setup) {
      if (!addressDomain(inputs.address.value)) { note.hidden = true; hint.textContent = ''; summary.textContent = ''; }
      return;
    }
    let filled = 0;
    for (const [name, value] of Object.entries(setup.fields)) if (autoFill(inputs[name], value)) filled++;
    const title = element('strong', setup.known ? '✓ Set up for ' + setup.name : 'Our best guess for ' + setup.domain);
    note.replaceChildren(title);
    note.classList.toggle('guess', setup.guess);
    if (setup.note) note.append(element('p', setup.note));
    if (setup.bridge) note.append(element('small', 'Bridge has to run on the same computer as FluidMessages Web. In Bridge’s settings, set the IMAP '
      + 'connection mode to SSL, and make this computer trust Bridge’s certificate, or the connection is refused.', 'hint'));
    if (setup.link) note.append(outsideLink(setup.link.label, setup.link.url));
    if (!filled) note.append(element('small', 'You changed the server settings yourself, so they were left as they are.', 'hint'));
    note.hidden = false;
    password.firstChild.textContent = setup.password;
    hint.textContent = setup.password === 'Password' ? '' : 'Not your normal ' + setup.name + ' password.';
    summary.textContent = 'Incoming mail: ' + inputs.imap_host.value + ', port ' + inputs.imap_port.value + ' ('
      + emailSecurity('imap', inputs.imap_port.value) + '). Outgoing mail: ' + inputs.smtp_host.value + ', port ' + inputs.smtp_port.value
      + ' (' + emailSecurity('smtp', inputs.smtp_port.value) + ').' + (setup.guess ? ' A guess: change anything that’s different.' : '');
    // A guess is shown so you can check it.
    if (setup.guess) details.open = true;
  };
  // Well-known providers fill in as you type; a guess waits until you leave the field, so half an address isn't guessed at.
  inputs.address.addEventListener('input', () => apply(false));
  inputs.address.addEventListener('change', () => {
    inputs.address.value = tidyAddress(inputs.address.value);
    apply(true);
  });
  form.beforeSave = () => {
    inputs.address.value = tidyAddress(inputs.address.value);
    apply(true);
  };
  form.afterError = () => { details.open = true; };
}

function setUpMatrixForm(form, inputs) {
  // The ID comes first: the server is found from it.
  const id = fieldOf(inputs.user_id);
  fieldOf(inputs.homeserver).before(id);
  const note = setupNote(id);
  const token = fieldOf(inputs.access_token);
  token.append(element('small', 'A long code that works like a password. You can find it in your Matrix app’s settings, under Help or About. '
    + 'Keep it private.', 'hint'));
  if (inputs.homeserver.value) {
    inputs.homeserver.dataset.auto = inputs.homeserver.value;
    const picked = (SERVER_DIRECTORY.matrix || []).find((entry) => entry.homeserver === inputs.homeserver.value);
    if (picked) inputs.user_id.placeholder = '@you:' + picked.name;
  }
  const apply = () => {
    const setup = matrixSetup(inputs.user_id.value);
    if (!setup) { note.hidden = true; return; }
    const filled = autoFill(inputs.homeserver, setup.homeserver);
    note.classList.toggle('guess', setup.guess && filled);
    note.replaceChildren(element('strong', !filled ? 'Server left as you set it' : setup.known ? '✓ Set up for ' + setup.name : 'Server filled in from your ID'),
      element('p', !filled ? 'Your ID ends in ' + setup.domain + ', but the homeserver below is different. That’s fine if you meant it.'
        : setup.known ? 'Homeserver: ' + setup.homeserver
          : 'We used ' + setup.homeserver + '. That’s right for most servers. Change it below if yours is different.'));
    note.hidden = false;
  };
  inputs.user_id.addEventListener('input', apply);
  inputs.user_id.addEventListener('change', () => { inputs.user_id.value = tidyMatrixId(inputs.user_id.value); apply(); });
  inputs.homeserver.addEventListener('change', () => { inputs.homeserver.value = tidyHomeserver(inputs.homeserver.value); });
  form.beforeSave = () => {
    inputs.user_id.value = tidyMatrixId(inputs.user_id.value);
    apply();
    inputs.homeserver.value = tidyHomeserver(inputs.homeserver.value);
  };
}

function setUpXmppForm(form, inputs, simple) {
  const note = setupNote(fieldOf(inputs.jid));
  Object.assign(inputs.jid, {autocomplete: 'username', inputMode: 'email'});
  const more = moreOptions('More options', [element('p', 'Leave these empty unless your provider told you to fill them in.', 'muted'),
    fieldOf(inputs.host), fieldOf(inputs.port)], !simple);
  fieldOf(inputs.password).after(more);
  const apply = () => {
    const setup = xmppSetup(inputs.jid.value);
    if (!setup) { note.hidden = true; inputs.host.placeholder = ''; return; }
    // The server is looked up from the address when you save, so the box stays empty and only shows what will be used.
    inputs.host.placeholder = setup.domain;
    note.classList.remove('guess');
    note.replaceChildren(element('strong', '✓ Server found from your address'), element('p', 'Server: ' + setup.domain
      + (setup.known ? '' : '. FluidMessages looks up the rest by itself.')));
    note.hidden = false;
  };
  inputs.jid.addEventListener('input', apply);
  inputs.jid.addEventListener('change', () => { inputs.jid.value = tidyAddress(inputs.jid.value).toLowerCase(); apply(); });
  form.beforeSave = () => { inputs.jid.value = tidyAddress(inputs.jid.value).toLowerCase(); };
  form.afterError = () => { more.open = true; };
}

/** A nickname to start from: the one you already use on another network, else one made from another account's name. */
function nickFromAccounts() {
  const list = typeof chatAccounts !== 'undefined' && Array.isArray(chatAccounts) ? chatAccounts : [];
  const irc = list.find((account) => account.protocol === 'irc' && account.name);
  if (irc) return suggestNick(irc.name.split('@')[0]);
  for (const protocol of ['matrix', 'xmpp', 'email']) {
    const account = list.find((item) => item.protocol === protocol && item.name);
    const nick = account ? suggestNick(account.name) : '';
    if (nick) return nick;
  }
  return '';
}

function setUpIrcForm(form, inputs, simple, preset) {
  const list = (SERVER_DIRECTORY.irc || []).filter((entry) => entry.tls !== false);
  const network = element('select', '');
  for (const entry of list) network.append(Object.assign(element('option', entry.name), {value: entry.host}));
  network.append(Object.assign(element('option', 'Another network…'), {value: ''}));
  const server = fieldOf(inputs.server);
  const port = fieldOf(inputs.port);
  server.before(field('Network', network, 'Picking one fills in its address and port. The connection is always encrypted (TLS).'));
  const other = element('div', '', 'setup-other');
  server.before(other);
  other.append(server, port);
  const known = ircNetwork(inputs.server.value);
  // Get started begins on the first network in the list; the full form begins empty unless one was picked.
  network.value = known ? known.host : simple && !preset.server && !inputs.server.value ? list[0]?.host || '' : '';
  const choose = () => {
    const entry = list.find((item) => item.host === network.value);
    if (entry) {
      inputs.server.value = entry.host;
      inputs.port.value = String(entry.port);
      inputs.server.dataset.auto = entry.host;
      inputs.port.dataset.auto = String(entry.port);
    } else if (inputs.server.dataset.auto === inputs.server.value) {
      inputs.server.value = '';
      inputs.port.value = '';
    }
    other.hidden = simple && !!entry;
    inputs.server.required = !entry;
  };
  network.addEventListener('change', () => { choose(); if (!network.value) inputs.server.focus(); });
  choose();
  // Typing a known network's name or address by hand picks it in the list too.
  inputs.server.addEventListener('change', () => {
    inputs.server.value = tidyHost(inputs.server.value);
    const typed = ircNetwork(inputs.server.value);
    if (typed && typed.tls !== false) { network.value = typed.host; choose(); }
  });

  const nick = fieldOf(inputs.nick);
  const nickHint = element('small', 'Nickname: the name people see. Letters and numbers, no spaces.', 'hint');
  nick.append(nickHint);
  inputs.nick.required = true;
  inputs.nick.maxLength = 32;
  const suggested = nickFromAccounts();
  if (suggested && autoFill(inputs.nick, suggested)) nickHint.textContent = 'Suggested from your other account. Change it to anything you like. No spaces.';
  inputs.nick.addEventListener('change', () => { inputs.nick.value = inputs.nick.value.replace(/\s+/g, ''); });

  const extra = ['sasl_user', 'sasl_password', 'nickserv_password', 'channels', 'ctcp_replies'].map((name) => fieldOf(inputs[name]));
  // What a registered nickname is, right above the account name and password (IRC_WORDS in companion.js).
  const words = typeof IRC_WORDS === 'object' ? IRC_WORDS : null;
  const explain = words ? element('div', '', 'setup-note irc-names') : null;
  if (explain) explain.append(element('strong', words.whatsThisTitle), element('p', words.whatsThis, 'muted'));
  const more = moreOptions('More options', [element('p', 'You can skip all of these. They are for nicknames you registered with the network, '
    + 'and for channels to join right away.', 'muted'), explain, ...extra], !simple);
  nick.after(more);
  inputs.channels.addEventListener('change', () => {
    inputs.channels.value = inputs.channels.value.split(',').map(channelName).filter(Boolean).join(', ');
  });
  form.afterError = () => { if (!network.value) other.hidden = false; };
}
