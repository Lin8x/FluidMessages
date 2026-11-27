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

// Email ports, like the phone's (EmailPorts.kt) and accounts.py: filled in for a new account, and what an account saved
// without ports uses. FluidMessages always uses TLS and never falls back to plain text.
const EMAIL_PORTS = {imap_port: 993, smtp_port: 465};
const EMAIL_PORT_HINTS = {
  imap: '993 uses TLS from the start. 143 upgrades with STARTTLS.',
  smtp: '465 uses TLS from the start. Other ports, like 587, upgrade with STARTTLS.',
};
const validPort = (text) => /^[0-9]{1,5}$/.test(String(text)) && Number(text) >= 1 && Number(text) <= 65535;

// The IRC form's words for a nickname and a registered account, the same as the phone's (IrcWords in IrcJoins.kt).
const IRC_WORDS = {
  nick: 'Nickname: the name people see.',
  account: 'Account name: the name you registered with the network’s NickServ. Usually the same as your nickname. '
    + 'Leave blank to use your nickname.',
  password: 'Password: your NickServ password. FluidMessages signs you in with it each time it connects.',
  nickserv: 'Leave this blank unless signing in with Password doesn’t work on this network. It’s sent to NickServ after connecting.',
  whatsThisTitle: 'What’s this?',
  whatsThis: 'Anyone can pick a free nickname. Some people also register theirs with the network’s NickServ, so no one else can '
    + 'use it. If you registered, fill in the account name and password. If you didn’t, leave them blank.',
};

const ACCOUNT_FIELDS = {
  matrix: [['homeserver', 'Homeserver', 'https://matrix.example.org'], ['user_id', 'Matrix ID', '@you:example.org'],
    ['access_token', 'Access token', '', true]],
  xmpp: [['jid', 'Address', 'you@example.org'], ['password', 'Password', '', true], ['host', 'Server (optional)', ''],
    ['port', 'Port (optional)', '5222']],
  email: [['address', 'Email address', 'you@example.org'], ['imap_host', 'IMAP server', 'imap.example.org'],
    ['imap_port', 'IMAP port', '993', false, EMAIL_PORT_HINTS.imap], ['imap_user', 'IMAP user (optional)', ''],
    ['imap_password', 'IMAP password', '', true],
    ['smtp_host', 'SMTP server', 'smtp.example.org'], ['smtp_port', 'SMTP port', '465', false, EMAIL_PORT_HINTS.smtp],
    ['smtp_user', 'SMTP user (optional)', ''], ['smtp_password', 'SMTP password (optional)', '', true]],
  irc: [['server', 'Server', 'irc.libera.chat'], ['port', 'Port (optional)', '6697'], ['nick', 'Nickname', ''],
    // The stored keys stay sasl_user, sasl_password, and nickserv_password; only the words are plainer (IrcWords on the phone).
    ['sasl_user', 'Account name (optional)', 'Leave blank to use your nickname', false, IRC_WORDS.account],
    ['sasl_password', 'Password (optional)', '', true, IRC_WORDS.password],
    ['nickserv_password', 'Older networks: NickServ password (optional)', 'Only if Password doesn’t sign you in', true, IRC_WORDS.nickserv],
    ['channels', 'Channels (comma-separated)', '#example'],
    ['ctcp_replies', 'Answer CTCP VERSION and PING (off keeps your client private)', '', 'check']],
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
  return companionSettings;
}

function field(label, input, hint) {
  const wrap = element('label', label);
  wrap.append(input);
  if (hint) wrap.append(element('small', hint, 'hint'));
  return wrap;
}

function checkbox(label, checked, change, hint) {
  const input = Object.assign(element('input', ''), {type: 'checkbox', checked});
  const wrap = element('label', '', 'check');
  const words = element('span', '');
  words.append(element('span', label));
  if (hint) words.append(element('small', hint, 'hint'));
  wrap.append(input, words);
  input.addEventListener('change', () => change(input.checked));
  return wrap;
}

/** A group of choices shown as large radio rows, each with an explanation. */
function choiceGroup(legend, name, options, value, change) {
  const set = element('fieldset', '', 'choices');
  set.append(element('legend', legend));
  for (const [key, title, detail] of options) {
    const input = Object.assign(element('input', ''), {type: 'radio', name, value: key, checked: key === value});
    const row = element('label', '', 'choice');
    const words = element('span', '');
    words.append(element('strong', title));
    if (detail) words.append(element('small', detail, 'hint'));
    row.append(input, words);
    input.addEventListener('change', () => { if (input.checked) change(key); });
    set.append(row);
  }
  return set;
}

/** A button that asks again in place ("Tap again to remove") instead of opening a pop-up. */
function confirmButton(label, again, run, className = '') {
  const button = element('button', label, className);
  button.type = 'button';
  let timer = 0;
  const disarm = () => { clearTimeout(timer); button.textContent = label; button.classList.remove('danger'); button.dataset.armed = ''; };
  button.addEventListener('click', async () => {
    if (!button.dataset.armed) {
      button.dataset.armed = '1';
      button.textContent = again;
      button.classList.add('danger');
      timer = setTimeout(disarm, 6000);
      return;
    }
    disarm();
    button.disabled = true;
    try { await run(); } finally { button.disabled = false; }
  });
  button.addEventListener('blur', () => setTimeout(() => { if (document.activeElement !== button) disarm(); }, 150));
  return button;
}

let sheetCount = 0;

/**
 * A full page for settings and forms: the whole screen on phones, a roomy panel on wider screens, always with a
 * clear Back at the top. Set sheet.back for a step-by-step form; Back then goes one step back instead of closing.
 */
function dialog(title) {
  const node = element('dialog', '', 'sheet');
  const id = 'sheet-title-' + (++sheetCount);
  node.setAttribute('aria-labelledby', id);
  const head = element('div', '', 'sheet-head');
  const back = element('button', '', 'sheet-back');
  back.type = 'button';
  const arrow = element('span', '‹');
  arrow.setAttribute('aria-hidden', 'true');
  back.append(arrow, document.createTextNode(' Back'));
  back.addEventListener('click', () => (node.back ? node.back() : node.close()));
  const heading = element('h2', title);
  heading.id = id;
  head.append(back, heading);
  node.append(head);
  node.setTitle = (text) => { heading.textContent = text; };
  node.addEventListener('close', () => node.remove());
  document.body.append(node);
  return node;
}

const THEME_ORDER = ['fluid', 'midnight', 'lavender', 'evergreen', 'tideline', 'ember', 'love', 'rosewater'];
let saveLookTimer = 0;

function saveWebLook(next, now = false) {
  webLook = next;
  if (!usingPhoneLook()) { applyAppearance(resolvedAppearance()); showThreads(); showMessages(true); }
  clearTimeout(saveLookTimer);
  const run = () => api('settings', {web_appearance: webLook}).then((result) => { companionSettings = result; }).catch(showError);
  if (now) run(); else saveLookTimer = setTimeout(run, 400);
}

/** A small conversation drawn in a theme's own colors, for the theme picker and the live preview. */
function themeSwatch(key) {
  // Drawn in the light or dark you chose, so the tiles match what picking one gives you.
  const look = themeAppearance({mode: webLook.mode, theme: key});
  const swatch = element('span', '', 'theme-swatch');
  swatch.setAttribute('aria-hidden', 'true');
  swatch.style.background = look.colors.canvas;
  const incoming = element('span', 'Hi', 'swatch-bubble');
  incoming.style.background = bubbleFill(look.colors.incoming);
  incoming.style.color = bubbleInk(look.colors.incoming);
  const outgoing = element('span', 'Hey!', 'swatch-bubble out');
  outgoing.style.background = bubbleFill(look.colors.sms);
  outgoing.style.color = bubbleInk(look.colors.sms);
  swatch.append(incoming, outgoing);
  return swatch;
}

/** True while the look follows the phone, so the web app's own look settings are shown but can't change. */
function lookLocked(settings) { return !!(settings.follow_phone && settings.phone.paired && usingPhoneLook()); }

/** Settings → Themes and appearance (settings.js). Color theme first, with every named theme. */
function appearanceSection(settings) {
  const section = element('section', '', 'settings-section');
  section.id = 'settings-appearance';
  const locked = lookLocked(settings);
  if (locked) {
    section.append(element('p', 'Following your phone’s theme, colors, and bubbles. To set them here instead, choose '
      + '“This web app’s own” under Phone and other devices.', 'notice'));
  }
  const controls = element('fieldset', '', 'plain');
  controls.disabled = locked;
  controls.append(element('legend', 'Web app appearance', 'visually-hidden'));

  // Color theme comes first and lists every named theme. Picking one restores its colors and bubble layout.
  const themes = element('fieldset', '', 'theme-grid');
  themes.append(element('legend', 'Color theme'));
  for (const key of THEME_ORDER) {
    const input = Object.assign(element('input', ''), {type: 'radio', name: 'web-theme', value: key, checked: (webLook.theme || 'fluid') === key});
    const tile = element('label', '', 'theme-tile');
    tile.append(input, themeSwatch(key), element('span', THEMES[key].label));
    input.addEventListener('change', () => {
      if (!input.checked) return;
      // The theme's colors, wallpaper, and layout; light or dark, motion, sounds, and haptics stay (look.js).
      saveWebLook(themeLook(webLook, key), true);
      refreshAppearanceControls();
    });
    themes.append(tile);
  }
  const mode = choiceGroup('Light or dark', 'web-mode', [['system', 'Match this device'], ['light', 'Light'], ['dark', 'Dark']],
    webLook.mode || 'system', (value) => { saveWebLook({...webLook, mode: value}, true); refreshAppearanceControls(); });
  mode.classList.add('segmented');

  // The live preview, with the phone's Conversation | Inbox switch. Opening Inbox and toolbar shows the inbox there,
  // and opening Customize colors or Message bubbles shows the conversation, where those settings show.
  let previewing = 'conversation';
  const previewBox = element('div', '', 'look-preview-box');
  const previewSwitch = element('div', '', 'segmented-buttons preview-switch');
  previewSwitch.setAttribute('role', 'radiogroup');
  previewSwitch.setAttribute('aria-label', 'Preview');
  const preview = element('div', '', 'look-preview');
  preview.setAttribute('role', 'img');
  const drawPreview = () => {
    for (const button of previewSwitch.children) button.setAttribute('aria-checked', String(button.dataset.view === previewing));
    preview.classList.toggle('inbox-preview', previewing === 'inbox');
    preview.setAttribute('aria-label', previewing === 'inbox' ? 'Preview of the inbox' : 'Preview of a conversation');
    preview.replaceChildren();
    if (previewing === 'inbox') { preview.append(...inboxPreview()); return; }
    // Nearby's bubble uses its own color, shaded for white text like the others (style.css .out.p-nearby).
    for (const [text, out, service] of [['Are we still on for Saturday?', false], ['Yes! I’ll bring the map 🗺️', true],
      ['Nearby preview', true, 'nearby']]) {
      const row = element('div', '', (out ? 'message out' : 'message') + (service ? ' p-' + service : ''));
      row.append(element('div', text, 'bubble'));
      preview.append(row);
    }
  };
  const showPreview = (view) => { previewing = view; drawPreview(); };
  for (const [view, label] of [['conversation', 'Conversation'], ['inbox', 'Inbox']]) {
    const button = element('button', label);
    button.type = 'button';
    button.dataset.view = view;
    button.setAttribute('role', 'radio');
    button.addEventListener('click', () => showPreview(view));
    previewSwitch.append(button);
  }
  previewBox.append(previewSwitch, preview);
  drawPreview();

  const colorsBox = element('details', '', 'more');
  colorsBox.append(element('summary', 'Customize colors'));
  const colorGrid = element('div', '', 'color-grid');
  const colorInputs = {};
  // The phone's order: message colors, then the background, buttons, and toolbar tints.
  for (const [key, label] of [['sms', 'Your texts'], ['incoming', 'Incoming'], ['automated', 'Automated texts'], ['rcs', 'RCS (preview)'],
    ['matrix', 'Matrix'], ['xmpp', 'XMPP'], ['email', 'Email'], ['irc', 'IRC'], ['nearby', 'Nearby'], ['canvas', 'Background color'],
    ['controls', 'Buttons and unread indicators'], ['chrome', 'Toolbar and composer tint']]) {
    const input = Object.assign(element('input', ''), {type: 'color'});
    input.addEventListener('input', () => { saveWebLook({...webLook, colors: {...(webLook.colors || {}), [key]: input.value}}); drawPreview(); });
    colorInputs[key] = input;
    colorGrid.append(field(label, input));
  }
  const contrastChoice = element('select', '');
  for (const [value, label] of [['standard', 'Standard'], ['increased', 'Increased']]) contrastChoice.append(Object.assign(element('option', label), {value}));
  contrastChoice.addEventListener('change', () => {
    const {contrast, ...rest} = webLook;
    saveWebLook(contrastChoice.value === 'increased' ? {...rest, contrast: true} : rest, true);
    refreshAppearanceControls();
  });
  const contrastField = field('Palette contrast', contrastChoice, 'Increased uses deeper or brighter versions of the colors, and stronger text and outlines.');
  const resetTheme = element('button', 'Reset to theme');
  resetTheme.type = 'button';
  resetTheme.addEventListener('click', () => {
    saveWebLook(themeLook(webLook, webLook.theme || 'fluid', webLook.activePalette), true);
    refreshAppearanceControls();
  });
  colorsBox.append(contrastField, element('p', 'Colored bubbles always use white text; bright colors are shaded so it stays readable.', 'muted'), colorGrid, resetTheme);

  const bubbles = element('details', '', 'more');
  bubbles.append(element('summary', 'Message bubbles'));
  const sliders = {};
  for (const [key, label, min, max] of [['size', 'Text size', 12, 28], ['radius', 'Roundness', 2, 30], ['padding', 'Padding', 6, 24]]) {
    const input = Object.assign(element('input', ''), {type: 'range', min, max, step: 1});
    const output = element('output', '');
    input.addEventListener('input', () => { output.textContent = input.value + ' px'; saveWebLook({...webLook, [key]: Number(input.value)}); });
    sliders[key] = [input, output];
    const wrap = field(label, input);
    wrap.append(output);
    wrap.classList.add('slider');
    bubbles.append(wrap);
  }

  function refreshAppearanceControls() {
    const look = themeAppearance(webLook);
    for (const [key, input] of Object.entries(colorInputs)) input.value = (webLook.colors || {})[key] || look.colors[key];
    for (const [key, [input, output]] of Object.entries(sliders)) { input.value = look[key]; output.textContent = look[key] + ' px'; }
    contrastChoice.value = webLook.contrast ? 'increased' : 'standard';
    // The tints start from the theme's own, before any contrast adjustment.
    const raw = (look.contrast && (webLook.theme || 'fluid') === 'fluid' ? FLUID_CONTRAST : (THEMES[webLook.theme] || THEMES.fluid))[look.dark ? 'dark' : 'light'];
    colorInputs.controls.value = (webLook.colors || {}).controls || raw[3];
    colorInputs.chrome.value = (webLook.colors || {}).chrome || raw[4];
    if (typeof drawPreview === 'function') drawPreview();
    for (const input of themes.querySelectorAll('input')) {
      input.checked = input.value === (webLook.theme || 'fluid');
      input.parentElement.querySelector('.theme-swatch')?.replaceWith(themeSwatch(input.value));
    }
  }
  refreshAppearanceControls();
  // Saved palettes, wallpaper, inbox and toolbar, bubble tails, and motion and sound, as on the phone (look.js).
  const extras = typeof lookExtras === 'function' ? lookExtras({themes, colorsBox, bubbles, refresh: refreshAppearanceControls}) : {groups: [], feedback: null};
  // Opening a group shows the preview where its settings show, as on the phone.
  for (const [group, view] of [[colorsBox, 'conversation'], [bubbles, 'conversation'], [extras.groups[1], 'inbox']]) {
    group?.addEventListener('toggle', () => { if (group.open) showPreview(view); });
  }
  for (const input of extras.groups[1]?.querySelectorAll('select, input') || []) input.addEventListener('change', drawPreview);
  for (const input of extras.groups[1]?.querySelectorAll('input') || []) input.addEventListener('input', drawPreview);
  controls.append(themes, mode, previewBox, colorsBox, ...extras.groups, bubbles);
  section.append(controls);
  if (extras.feedback) section.append(extras.feedback);
  return section;
}

/** One row per protocol: who you're signed in as, with Add, Edit, and Remove. Shared by Settings and the welcome. */
/** Settings → Accounts: every account, grouped by service, with Add another on each. Forms open in place. */
async function accountRows(list, again) {
  const result = await api('protocols/accounts');
  list.replaceChildren();
  const blurbs = {matrix: 'Chat, groups, and calls', xmpp: 'Jabber and XMPP chat', email: 'Any IMAP and SMTP mailbox', irc: 'Channels and private messages'};
  const backButton = (label, run) => {
    const back = element('button', '', 'sheet-back');
    back.type = 'button';
    back.append(element('span', '‹'), document.createTextNode(' ' + label));
    back.addEventListener('click', run);
    return back;
  };
  const top = () => (list.closest('dialog') || list).scrollTo?.({top: 0});
  const open = (protocol, account, {simple = false, preset = {}, guided = false} = {}) => {
    list.replaceChildren(guided ? backButton('About ' + labels[protocol], () => guide(protocol)) : backButton('Accounts', again),
      element('h3', (account ? '' : 'Add ') + labels[protocol] + (account ? ' · ' + account.name : ' account')),
      element('p', typeof SERVER_HELP === 'object' ? SERVER_HELP[protocol] : '', 'muted'),
      accountForm(protocol, account, () => again(), {add: !account, simple, preset}));
    top();
    if (typeof focusAccountForm === 'function') focusAccountForm(list); else list.querySelector('input')?.focus();
  };
  // Adding starts with a page about the service (account-setup.js), then its form, which fills itself in.
  const guide = (protocol) => {
    if (typeof serviceGuide !== 'function') { open(protocol, null); return; }
    list.replaceChildren(backButton('Accounts', again), ...serviceGuide(protocol, {
      start: () => open(protocol, null, {simple: true, guided: true}),
      example: () => open(protocol, null, {simple: true, guided: true, preset: guideExample(protocol).preset}),
      expert: () => open(protocol, null, {guided: true})}));
    top();
    list.querySelector('.service-guide-begin')?.focus({preventScroll: true});
  };
  for (const protocol of Object.keys(ACCOUNT_FIELDS)) {
    const group = element('div', '', 'account-group');
    const mine = result.accounts.filter((item) => item.protocol === protocol);
    const head = element('div', '', 'account-row account-service');
    const badge = element('span', labels[protocol][0], 'account-badge p-' + protocol);
    badge.setAttribute('aria-hidden', 'true');
    const words = element('span', '', 'account-words');
    words.append(element('strong', labels[protocol]), element('span', mine.length ? mine.length + (mine.length === 1 ? ' account' : ' accounts') : blurbs[protocol], 'muted'));
    const add = element('button', mine.length ? 'Add another' : 'Add', mine.length ? '' : 'primary');
    add.type = 'button';
    add.setAttribute('aria-label', (mine.length ? 'Add another ' : 'Add ') + labels[protocol] + ' account');
    add.addEventListener('click', () => guide(protocol));
    head.append(badge, words, add);
    group.append(head);
    for (const account of mine) {
      const row = element('div', '', 'account-row account-item');
      const who = element('span', '', 'account-words');
      who.append(element('strong', account.error ? 'Docker Compose account' : account.name || 'Connected'),
        element('span', account.error || (account.source === 'compose' ? 'Set by the server · read-only' : account.source === 'file' ? 'Set in the server’s config file'
          : mine.length > 1 && account.primary ? 'First account · chats from before stay here' : 'On FluidMessages Web'), 'muted'));
      row.append(who);
      if (account.source === 'phone') {
        const edit = element('button', 'Edit');
        edit.type = 'button';
        edit.setAttribute('aria-label', 'Edit ' + (account.name || labels[protocol]));
        edit.addEventListener('click', () => open(protocol, account));
        row.append(edit, confirmButton('Remove', 'Tap again to remove', async () => {
          await api('protocols/accounts/delete', {protocol, id: account.id}).catch(showError);
          again();
        }, 'quiet'));
      }
      group.append(row);
    }
    // "What is Matrix?" and the rest: the same page about the service that adding starts with (account-setup.js).
    if (typeof serviceGuide === 'function') {
      const learn = element('button', protocol === 'email' ? 'Email as chats: how it works' : 'What is ' + labels[protocol] + '?', 'link account-learn');
      learn.type = 'button';
      learn.addEventListener('click', () => guide(protocol));
      group.append(learn);
    }
    list.append(group);
  }
  return result;
}

/** An account's fields. [add] adds another account; with [account] it edits that one. [preset] pre-fills fields. */
function accountForm(protocol, account, done, {add = false, preset = {}, simple = false} = {}) {
  const form = element('form', '', 'settings-section');
  const inputs = {};
  for (const [name, label, hint, secret, help] of ACCOUNT_FIELDS[protocol]) {
    if (secret === 'check') {
      // A yes-or-no choice, such as answering CTCP requests.
      const box = Object.assign(element('input', ''), {type: 'checkbox', name, checked: !!(account?.fields?.[name] ?? preset[name])});
      const label_ = element('label', '', 'check-row');
      label_.append(box, element('span', label));
      inputs[name] = box;
      form.append(label_);
      continue;
    }
    const input = Object.assign(element('input', ''), {name, placeholder: hint, type: secret ? 'password' : 'text', autocomplete: secret ? 'new-password' : 'off',
      spellcheck: false});
    // Ports start filled in, for new accounts and for ones saved before they could be changed.
    const current = account?.fields?.[name] ?? preset[name] ?? (protocol === 'email' ? EMAIL_PORTS[name] : undefined);
    if (current && !secret) input.value = Array.isArray(current) ? current.join(', ') : String(current);
    if (secret && current) input.placeholder = 'Saved · leave blank to keep';
    if (name in EMAIL_PORTS) Object.assign(input, {inputMode: 'numeric', maxLength: 5});
    inputs[name] = input;
    form.append(field(label, input, help));
  }
  const status = element('p', '', 'muted');
  status.setAttribute('role', 'status');
  const save = element('button', 'Save', 'primary');
  form.append(element('p', 'Passwords are kept in a private file on your server and are never shown again.'
    + (protocol === 'irc' ? ' FluidMessages connects to IRC with TLS only.' : protocol === 'email' ? ' Email always uses TLS, never plain text.' : ''), 'muted'), save, status);
  // A new account's form fills itself in as you type (account-setup.js). Editing one never changes what you saved.
  const fresh = add && !account && typeof setUpAccountForm === 'function';
  if (fresh) setUpAccountForm(form, inputs, protocol, {simple, preset});
  form.addEventListener('submit', async (event) => {
    event.preventDefault();
    if (form.beforeSave) form.beforeSave();
    let fields = {};
    for (const [name, input] of Object.entries(inputs)) {
      if (input.type === 'checkbox') fields[name] = input.checked;
      else if (input.value.trim()) fields[name] = input.value.trim();
    }
    // Spaces out, domains in lower case, channels with their #.
    if (fresh && typeof tidyAccountFields === 'function') fields = tidyAccountFields(protocol, fields);
    const badPort = protocol === 'email' && Object.keys(EMAIL_PORTS).find((name) => fields[name] && !validPort(fields[name]));
    if (badPort) {
      status.textContent = 'Enter a ' + (badPort === 'imap_port' ? 'IMAP' : 'SMTP') + ' port from 1 to 65535.';
      inputs[badPort].focus();
      return;
    }
    if (protocol === 'email') for (const name of Object.keys(EMAIL_PORTS)) if (fields[name]) fields[name] = Number(fields[name]);
    save.disabled = true;
    try {
      const before = new Set((typeof chatAccounts !== 'undefined' ? chatAccounts : []).map((item) => item.id));
      const result = await api('protocols/accounts', {protocol, fields, ...(account?.id ? {id: account.id} : add ? {add: true} : {})});
      // The account just added, for callers that carry on with it (Add server goes on to Browse).
      const added = add && !account ? (result.accounts || []).filter((item) => item.protocol === protocol && item.id && !before.has(item.id)).pop() : null;
      // No Communities page after saving: Add server shows it first, while no server is added yet (servers.js).
      done(added || null);
    } catch (error) {
      status.textContent = error.message;
      if (form.afterError) form.afterError();
    }
    finally { save.disabled = false; }
  });
  return form;
}

/** The account form in a sheet, for the setup guide. Without [account] it adds one. */
function editAccount(protocol, account, done) {
  const sheet = dialog(labels[protocol] + ' account');
  const body = element('div', '', 'account-flow');
  sheet.append(body);
  const form = ({simple = false, preset = {}, guided = false} = {}) => {
    sheet.back = guided ? () => guide() : null;
    body.replaceChildren(accountForm(protocol, account, () => { sheet.close(); done(); }, {add: !account, simple, preset}));
    sheet.scrollTo?.({top: 0});
    if (typeof focusAccountForm === 'function') focusAccountForm(body); else body.querySelector('input')?.focus();
  };
  // A new account starts with a page about the service (account-setup.js); Back from its form returns there.
  const guide = () => {
    sheet.back = null;
    body.replaceChildren(...serviceGuide(protocol, {
      start: () => form({simple: true, guided: true}),
      example: () => form({simple: true, guided: true, preset: guideExample(protocol).preset}),
      expert: () => form({guided: true})}));
    sheet.scrollTo?.({top: 0});
  };
  sheet.showModal();
  if (!account && typeof serviceGuide === 'function') guide(); else form();
}

/**
 * Settings → Media, GIFs, and fun: the Links and Fun and effects cards, like the phone's. Switch off anything you'd
 * rather not see; these apply to every browser on this server.
 */
function funSections(settings) {
  const current = {...prefs, ...(settings.prefs || {})};
  const save = (key) => async (value) => {
    prefs = {...prefs, [key]: value};
    showMessages(true);
    try { companionSettings = await api('settings', {prefs: {[key]: value}}); } catch (error) { showError(error); }
  };
  const links = element('section', '', 'settings-section');
  links.id = 'settings-links';
  links.append(element('h3', 'Links'),
    checkbox('Show pictures and GIFs from links', current.gifs, save('gifs'), 'GIFs, stickers, and pictures from links. Off shows the link instead.'),
    checkbox('Link previews', current.linkPreviews, save('linkPreviews'), 'A card with the page’s title and picture. Your server fetches it, so the site never sees your browser. Links from people you don’t know wait for Tap to load preview, since loading tells the site the message was opened.'));
  const fun = element('section', '', 'settings-section');
  fun.id = 'settings-fun';
  const motion = matchMedia('(prefers-reduced-motion: reduce)').matches ? ' Your device asks for reduced motion, so animations stay off.' : '';
  fun.append(element('h3', 'Fun and effects'), element('p', 'Turn off anything you’d rather not see. Applies to every browser on this server.', 'muted'),
    checkbox('Message effects', current.effects, save('effects'), 'Confetti, balloons, slams, and invisible ink. Off shows the words only.' + motion),
    checkbox('Reaction animations', current.reactionEffects, save('reactionEffects'), 'Each built-in reaction plays its own short animation; other emoji pop.'),
    checkbox('Games', current.games, save('games'), 'Chess, tic-tac-toe, four in a row, and rock paper scissors. Off shows moves as plain text.'),
    checkbox('Dice and random picks', current.random, save('random'), 'Dice, coin flips, and random choices.'));
  return [links, fun];
}

/** Your phone, this computer, and other devices. The password appears here, when you first let another device in. */
function devicesSection(settings, rebuild) {
  const section = element('section', '', 'settings-section');
  section.id = 'settings-devices';
  const phone = element('div', '', 'subsection');
  const computer = element('div', '', 'subsection');
  const others = element('div', '', 'subsection');
  section.append(phone, computer, others);
  const draw = (state, keepPhone = false) => {
    // Your phone: optional. It adds texts, and can share its look and timers.
    if (!keepPhone) drawPhone(state);
    drawDevices(state);
  };
  const drawPhone = (state) => {
    phone.replaceChildren(element('h4', 'Your phone'));
    phone.append(element('p', settings.phone.paired
      ? 'Last synced ' + new Date(settings.phone.last_sync).toLocaleString() + '. Texts go through it.'
      : 'Optional. FluidMessages for Android adds your texts here and sends texts for you.', 'muted'));
    // Setting the password from the phone steps updates the other devices below, leaving these steps in place.
    phone.append(phoneConnect(state, () => api('auth/security').then((next) => draw(next, true))));
    if (settings.phone.paired) {
      phone.append(choiceGroup('Settings to use', 'follow-phone', [
        ['phone', 'My phone’s (recommended)', 'Theme, colors, bubbles, times, tags, and timers follow the phone each time it syncs.'],
        ['web', 'This web app’s own', 'Keep a separate look and timers here.'],
      ], settings.follow_phone ? 'phone' : 'web', async (value) => {
        try {
          await api('settings', {follow_phone: value === 'phone'});
          followPhone = value === 'phone';
          applyAppearance(resolvedAppearance());
          showThreads();
          showMessages(true);
          await rebuild('settings-devices');
        } catch (error) { showError(error); }
      }));
    }
  };
  const drawDevices = (state) => {
    // This computer: opens without a password.
    computer.replaceChildren(element('h4', 'This computer'));
    computer.hidden = !state.here && !state.local;
    computer.append(checkbox('Open without a password on this computer', state.local, async (value) => {
      try { draw(await api('auth/local-access', {enabled: value})); } catch (error) { showError(error); }
    }, state.here ? 'You’re on it now. Other devices always need the password.' : 'Applies when FluidMessages is opened on the computer it runs on.'));
    // Other devices: a password, set the moment you want one.
    others.replaceChildren(element('h4', 'Other devices'));
    const plain = !state.https && !/^(localhost|127\.0\.0\.1|\[::1\])$/.test(location.hostname);
    if (!state.owner) {
      others.append(element('p', 'Only this computer can open FluidMessages right now. To use it from a laptop, tablet, or your phone’s browser, set a password.', 'muted'));
      const begin = element('button', 'Set up a password', 'primary');
      begin.type = 'button';
      begin.addEventListener('click', () => begin.replaceWith(passwordForm(false, (result) => draw(result))));
      others.append(begin);
    } else {
      others.append(element('p', state.sessions + (state.sessions === 1 ? ' browser is' : ' browsers are') + ' signed in. Other devices sign in with your password.', 'muted'));
      const change = element('details', '', 'more');
      change.append(element('summary', 'Change password'), passwordForm(true, (result) => { change.open = false; draw(result); }));
      const row = element('div', '', 'button-row');
      row.append(confirmButton('Sign out other browsers', 'Tap again to sign them out', async () => draw(await api('auth/sessions/end-others', {}))));
      if (!(state.here && state.local)) {
        const out = element('button', 'Sign out', 'quiet');
        out.type = 'button';
        out.addEventListener('click', () => signOut());
        row.append(out);
      }
      others.append(change, row);
      // Two-step sign-in: on, or a gentle notice with Turn on (two-step.js).
      if (typeof twoStepSection === 'function') others.append(twoStepSection(state, (next) => draw(next)));
    }
    if (plain) others.append(element('p', 'This page starts with http://, so anyone on the same Wi-Fi could read your password and messages. That’s fine for testing at home. For everyday use, use an https:// address.', 'notice'));
  };
  phone.append(element('p', 'Loading…', 'muted'));
  api('auth/security').then(draw).catch((error) => { phone.replaceChildren(element('p', error.message, 'muted')); });
  return section;
}

/** Set or change the password, inline. [done] gets the new security state. A first password goes straight on to
 * setting up two-step sign-in (two-step.js), with a visible Skip for now; [done] runs once that's finished or skipped. */
function passwordForm(changing, done) {
  const form = element('form', '', 'stack password-form');
  const current = Object.assign(element('input', ''), {type: 'password', autocomplete: 'current-password', maxLength: 1024, required: changing});
  const next = Object.assign(element('input', ''), {type: 'password', autocomplete: 'new-password', required: true, minLength: 8, maxLength: 1024});
  const again = Object.assign(element('input', ''), {type: 'password', autocomplete: 'new-password', required: true, minLength: 8, maxLength: 1024});
  const status = element('p', '', 'error');
  status.setAttribute('role', 'alert');
  const save = element('button', changing ? 'Change password' : 'Save password', 'primary');
  form.append(Object.assign(element('input', ''), {type: 'text', name: 'username', autocomplete: 'username', value: 'FluidMessages Web', hidden: true}));
  if (changing) form.append(field('Current password', current));
  form.append(field(changing ? 'New password' : 'Password', next, 'At least 8 characters. A few words together is easy to remember and hard to guess.'),
    field('Type it again', again), status, save);
  form.addEventListener('submit', async (event) => {
    event.preventDefault();
    if (next.value !== again.value) { status.textContent = 'The two passwords don’t match.'; return; }
    save.disabled = true;
    status.textContent = '';
    try {
      const result = await api('auth/password', {current: current.value, password: next.value});
      form.reset();
      if (!changing && typeof twoStepSetup === 'function' && result.two_step && !result.two_step.on) {
        form.replaceWith(twoStepSetup((state) => done(state)));
        return;
      }
      done(result);
    } catch (error) { status.textContent = error.message; }
    finally { save.disabled = false; }
  });
  requestAnimationFrame(() => (changing ? current : next).focus());
  return form;
}

const REACH_GUIDE = 'https://github.com/Lin8x/FluidMessages/blob/main/docs/self-hosting.md#reach-it-from-outside-your-home';
const PLAIN_WARNING = 'This address starts with http://, so anyone on the same Wi-Fi could read your messages and the link code. '
  + 'That’s fine for testing at home. For everyday use, use an https:// address.';

/**
 * The address to type on the phone, from the server's phone_reach (auth/security "reach"): each one with a Copy
 * button, what an IP address and a port are in plain words, the http:// warning, and what to do when the phone
 * can't reach this computer yet. Everything is set as text, never as HTML.
 */
function phoneAddressCard(reach, changed) {
  const card = element('div', '', 'stack phone-address');
  // The desktop app answers only this computer until "Let my phone connect" opens its HTTPS port to the local network.
  if (reach?.desktop) card.append(phoneAccessSwitch(reach, changed, card));
  const list = reach?.addresses || [];
  const labels = {page: 'The address of this page', wifi: 'On the same Wi-Fi', private: 'On your private network (like Tailscale)'};
  for (const item of list) {
    const row = element('div', '', 'token-box');
    const input = Object.assign(element('input', ''), {value: item.url, readOnly: true, spellcheck: false, autocomplete: 'off'});
    input.setAttribute('aria-label', labels[item.kind] || 'Address');
    input.addEventListener('focus', () => input.select());
    const copy = element('button', 'Copy', 'primary');
    copy.type = 'button';
    copy.setAttribute('aria-label', 'Copy ' + item.url);
    copy.addEventListener('click', () => {
      const done = () => { copy.textContent = 'Copied'; setTimeout(() => { copy.textContent = 'Copy'; }, 2500); };
      if (navigator.clipboard?.writeText) navigator.clipboard.writeText(item.url).then(done).catch(() => input.select());
      else { input.focus(); input.select(); }
    });
    row.append(input, copy);
    card.append(element('span', labels[item.kind] || 'Address', 'guide-copy-label'), row);
  }
  if (reach?.tls && list.some((item) => item.pinned)) card.append(copyBlock('Port', String(reach.tls.port), false));
  if (!list.length) {
    card.append(element('p', reach?.container
      ? 'Your phone can’t reach FluidMessages yet. It runs in Docker, which only lets this computer in. Remove FLUID_LOCAL_ONLY and publish the port to your network (like "8080:8080") in compose.yaml, then use this computer’s address, like http://192.168.1.20:8080.'
      : reach?.desktop
        ? 'Your phone can’t reach FluidMessages yet: it only answers this computer. Turn on Let my phone connect, above.'
        : 'Your phone can’t reach FluidMessages yet: it only answers this computer. Start it with FLUID_BIND=0.0.0.0 so other devices on your Wi-Fi can reach it, then open this page again to see the address.', 'notice'));
  } else if (reach && !reach.listening && list.every((item) => item.kind === 'page')) {
    card.append(element('p', 'This is the address you opened this page at. If your phone can’t reach it, start FluidMessages with FLUID_BIND=0.0.0.0.', 'hint'));
  }
  if (list.some((item) => item.url.startsWith('http://'))) card.append(element('p', PLAIN_WARNING, 'notice'));
  const explain = element('details', '', 'more');
  explain.append(element('summary', 'What do these numbers mean?'),
    element('p', 'The IP address (like 192.168.1.20) is this computer’s number on your home network. The port (the number after the colon, like 8080) is which “door” FluidMessages answers on. Your phone must be on the same Wi-Fi to use these.', 'hint'));
  const outside = Object.assign(element('a', 'Reach it from outside your home'), {href: REACH_GUIDE, target: '_blank', rel: 'noopener noreferrer'});
  const more = element('p', '', 'hint');
  more.append(outside);
  card.append(explain, more);
  return card;
}

/**
 * The desktop app's "Let my phone connect" switch (main.py set_phone_access). On, only the HTTPS port listens on the
 * local network, and a phone still needs its pairing token. The new addresses replace this card, or go to [changed]
 * when the caller draws more from them (the QR code).
 */
function phoneAccessSwitch(reach, changed, card) {
  const problem = element('p', '', 'error');
  problem.setAttribute('role', 'alert');
  problem.hidden = true;
  const row = switchRow('Let my phone connect', !!reach.phone_access, (on, box) => {
    box.disabled = true;
    problem.hidden = true;
    api('phone-access', {on}).then((result) => {
      if (changed) changed(result.reach);
      else card.replaceWith(phoneAddressCard(result.reach));
    }).catch((error) => {
      box.checked = !on;
      box.disabled = false;
      problem.textContent = error.message;
      problem.hidden = false;
    });
  }, reach.phone_access
    ? 'Phones on your Wi-Fi can find and reach this computer over https. Each one still needs your OK here, or the pairing token.'
    : 'Lets phones on your Wi-Fi find and reach this computer over https. Each one still needs your OK here, or the pairing token. Your computer may ask to allow it through its firewall.');
  const wrap = element('div', '', 'stack');
  wrap.append(row, problem);
  return wrap;
}

/**
 * Phones on the same Wi-Fi asking to connect (Find my computer on Android: main.py pair_open, nearby_pair.py),
 * listed as they appear with the six-digit code each one's screen shows. Connect makes the pairing token, which only
 * that phone can collect; [paired] gets the new security view and the phone's name. It checks every few seconds
 * while it's on the page. Empty when phones can't find this computer (the address card says how to let them).
 */
function nearbyPhones(reach, paired, replacing) {
  const card = element('div', '', 'stack nearby-phones');
  if (!reach?.nearby) return card;
  const list = element('div', '', 'stack');
  list.setAttribute('aria-live', 'polite');
  const problem = element('p', '', 'error');
  problem.setAttribute('role', 'alert');
  problem.hidden = true;
  card.append(element('span', 'Phones on this Wi-Fi', 'guide-copy-label'), list, problem);
  let shown = null;
  let busy = false;
  const empty = () => element('p', 'Looking for phones. On your phone, open FluidMessages → Settings → FluidMessages Web → Find my computer, and it shows up here.', 'hint');
  const answer = (phone, allow, buttons) => {
    busy = true;
    buttons.forEach((button) => { button.disabled = true; });
    problem.hidden = true;
    api('auth/pairing/nearby', {id: phone.id, allow}).then((result) => {
      busy = false;
      shown = null;
      if (allow) { stop(); paired(result, result.connected || phone.name); } else check();
    }).catch((error) => {
      busy = false;
      buttons.forEach((button) => { button.disabled = false; });
      problem.textContent = error.message;
      problem.hidden = false;
      shown = null;
      check();
    });
  };
  const draw = (phones) => {
    const key = phones.map((phone) => phone.id).join(',');
    if (key === shown) return;
    shown = key;
    if (!phones.length) { list.replaceChildren(empty()); return; }
    list.replaceChildren(...phones.map((phone) => {
      const row = element('div', '', 'nearby-phone');
      const words = element('div', '', 'nearby-phone-words');
      const code = element('span', phone.code, 'nearby-phone-code');
      const line = element('p', 'Connect it if its screen shows ', 'hint');
      line.append(code);
      words.append(element('strong', phone.name), line);
      const connect = element('button', 'Connect', 'primary');
      const no = element('button', 'Not this one', 'quiet');
      connect.type = no.type = 'button';
      connect.setAttribute('aria-label', 'Connect ' + phone.name + ', showing ' + phone.code);
      connect.addEventListener('click', () => answer(phone, true, [connect, no]));
      no.addEventListener('click', () => answer(phone, false, [connect, no]));
      const buttons = element('div', '', 'button-row');
      buttons.append(connect, no);
      row.append(words, buttons);
      return row;
    }));
    if (replacing) list.append(element('p', 'Connecting a phone here disconnects the one connected now.', 'hint'));
  };
  const check = () => {
    if (busy) return;
    if (shown !== null && !card.isConnected) { stop(); return; }
    api('auth/pairing/nearby').then((result) => { if (!busy) draw(result.phones || []); }).catch(() => {});
  };
  // Checks even with reduced motion: this is the list, not an animation.
  const id = setInterval(check, 2500);
  const stop = () => clearInterval(id);
  list.append(empty());
  check();
  return card;
}

/** A label, a read-only value, and a Copy button, set as text. [mono] for codes. */
function copyBlock(label, value, mono, hint) {
  const wrap = element('div', '', 'guide-copy');
  const box = element('div', '', 'token-box');
  const input = Object.assign(element('input', ''), {value, readOnly: true, spellcheck: false, autocomplete: 'off'});
  input.setAttribute('aria-label', label);
  if (mono) input.classList.add('mono');
  input.addEventListener('focus', () => input.select());
  const copy = element('button', 'Copy', 'primary');
  copy.type = 'button';
  copy.setAttribute('aria-label', 'Copy ' + label.toLowerCase());
  copy.addEventListener('click', () => {
    const done = () => { copy.textContent = 'Copied'; setTimeout(() => { copy.textContent = 'Copy'; }, 2500); };
    if (navigator.clipboard?.writeText) navigator.clipboard.writeText(value).then(done).catch(() => input.select());
    else { input.focus(); input.select(); }
  });
  box.append(input, copy);
  wrap.append(element('span', label, 'guide-copy-label'), box);
  if (hint) wrap.append(element('p', hint, 'hint'));
  return wrap;
}

/** The certificate fingerprint to type on the phone, when FluidMessages Web serves its own HTTPS (main.py start_tls). */
function fingerprintBlock(reach) {
  const print = reach?.tls?.fingerprint;
  return print ? copyBlock('Certificate fingerprint', print, true,
    'Optional on the phone. With it, your phone talks only to this computer, even on shared Wi-Fi.') : null;
}

// pair-payload:start
/**
 * What the pairing QR code holds, read by CompanionPairing.parse on the phone:
 * fluidmessages://pair?a=…&o=…&t=…&f=… with this computer's own HTTPS addresses (a, https:// left off, trusted by the
 * certificate fingerprint f), other https:// addresses such as your own domain (o, trusted the normal way), and the
 * pairing token t. The fingerprint is base64url to keep the code small. Addresses are dropped from the end until it
 * fits in a QR code. Null when there's no https:// address to put in it.
 */
function pairPayload(reach, token, origin, fits) {
  const list = reach?.addresses || [];
  const print = (reach?.tls?.fingerprint || '').replace(/[^0-9a-f]/gi, '');
  const pinned = print.length === 64 ? list.filter((item) => item.pinned).map((item) => item.url.replace(/^https:\/\//, '')) : [];
  const others = list.filter((item) => !item.pinned && item.url.startsWith('https://')).map((item) => item.url);
  if (origin && origin.startsWith('https://') && !others.includes(origin)
    && !pinned.includes(origin.replace(/^https:\/\//, ''))) others.push(origin);
  if (!token || (!pinned.length && !others.length)) return null;
  const encode = (value) => encodeURIComponent(value).replace(/%3A/gi, ':').replace(/%2F/gi, '/');
  let fingerprint = '';
  if (pinned.length) {
    const bytes = String.fromCharCode(...print.match(/../g).map((pair) => parseInt(pair, 16)));
    fingerprint = btoa(bytes).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
  }
  const build = (a, o) => 'fluidmessages://pair?' + [...a.map((value) => 'a=' + encode(value)), ...o.map((value) => 'o=' + encode(value)),
    't=' + encode(token), ...(a.length ? ['f=' + fingerprint] : [])].join('&');
  let a = pinned.slice(), o = others.slice();
  let text = build(a, o);
  while (fits && !fits(text) && a.length + o.length > 1) {
    if (o.length && (o.length > 1 || a.length)) o.pop(); else a.pop();
    text = build(a, o);
  }
  return text;
}
// pair-payload:end

/**
 * The pairing QR code, shown only right after a pairing token is made (it holds the token), with the plain-words
 * line above the typed-in details. Null when there's nothing to scan (no token, or no https:// address).
 */
function pairingQr(reach, token) {
  if (typeof qrSvg !== 'function') return null;
  const fits = (text) => { try { qrMatrix(text); return true; } catch (error) { return false; } };
  const text = pairPayload(reach, token, location.origin, fits);
  if (!text || !fits(text)) return null;
  const card = element('div', '', 'pair-qr');
  const code = element('div', '', 'pair-qr-code');
  code.append(qrSvg(text, 'QR code to connect FluidMessages on your phone'));
  card.append(code, element('p', 'Scan this with FluidMessages on your phone. Or type these in.', 'hint'));
  return card;
}

/**
 * Connecting the phone, step by step: a password first (the phone reaches this server over the network, and
 * your other devices will sign in with it), then a pairing token to type into the Android app.
 */
function phoneConnect(state, changed) {
  const box = element('div', '', 'stack');
  const token = element('div', '', 'token-box');
  token.hidden = true;
  const actions = element('div', '', 'button-row');
  const draw = (current, value) => {
    actions.replaceChildren();
    box.replaceChildren();
    if (value) {
      const input = Object.assign(element('input', ''), {value, readOnly: true, spellcheck: false});
      input.setAttribute('aria-label', 'Pairing token');
      input.addEventListener('focus', () => input.select());
      const copy = element('button', 'Copy', 'primary');
      copy.type = 'button';
      copy.addEventListener('click', () => navigator.clipboard?.writeText(value).then(() => { copy.textContent = 'Copied'; }).catch(() => input.select()));
      token.replaceChildren(input, copy, element('p', 'Shown only now. Keep it private: it lets the phone read and send your messages.', 'hint'));
      token.hidden = false;
    }
    // Phones on the Wi-Fi asking to connect, approved right here, with no token to copy.
    box.append(nearbyPhones(current.reach, (result, name) => {
      draw(result);
      box.prepend(element('p', 'Connected to ' + name + '. It syncs in a moment.', 'notice'));
    }, current.pairing.web));
    const steps = element('ol', '', 'steps');
    steps.append(element('li', 'On Android, open Settings → FluidMessages Web and tap Find my computer or Scan QR code.'),
      element('li', 'Or type in the address your phone can reach and the pairing token, below, and save. https:// is safest; http:// works for testing at home.'));
    const shown = current.pairing.web || current.pairing.compose || value;
    if (shown) {
      box.append(steps);
      // The QR code holds the token, so it shows only while the token does: right after it's made.
      const qr = value ? pairingQr(current.reach, value) : null;
      if (qr) box.append(qr);
      box.append(phoneAddressCard(current.reach, (reach) => draw({...current, reach}, value)));
    }
    box.append(token);
    const print = shown ? fingerprintBlock(current.reach) : null;
    if (print) box.append(print);
    box.append(actions);
    if (current.pairing.web && !value) box.append(element('p', 'A pairing token is active (made ' + new Date(current.pairing.created).toLocaleDateString() + ').', 'hint'));
    const make = () => api('auth/pairing', {}).then((result) => draw(result, result.token)).catch(showError);
    // The step-by-step guide: a full page with the password, the app, the token, and a live wait for the phone.
    const guided = (label, className) => {
      const button = element('button', label, className);
      button.type = 'button';
      button.addEventListener('click', () => showSyncGuide({fromSettings: true}));
      return button;
    };
    if (current.pairing.web) {
      if (typeof showSyncGuide === 'function') actions.append(guided('Set up step by step', 'quiet'));
      actions.append(confirmButton('Make a new pairing token', 'Tap again: the old one stops working', make),
        confirmButton('Disconnect phone', 'Tap again to disconnect', () => api('auth/pairing/delete', {}).then((result) => { token.hidden = true; draw(result); }).catch(showError)));
    } else {
      const connect = element('button', 'Connect your phone', 'primary');
      connect.type = 'button';
      connect.addEventListener('click', () => {
        if (typeof showSyncGuide === 'function') { showSyncGuide({fromSettings: true}); return; }
        if (current.owner) { make(); return; }
        // Step 1 of 2, right here: a password, since the phone (and any other device) reaches this server over the network.
        const intro = element('p', 'Step 1 of 2: set a password. Your phone connects over your network, so FluidMessages needs one before other devices can reach it.', 'notice');
        box.replaceChildren(intro, passwordForm(false, () => { changed(); make(); }));
      });
      actions.append(connect);
    }
  };
  draw(state);
  return box;
}

// Starting internet chats from the browser

/**
 * Opens the chat with [target] on [service], making it first if it's new: a person through a new link, a channel or
 * room by joining it on that account's server. Returns the chat's key. [account] picks the account (the most
 * recently used one otherwise).
 */
async function startInternetChat({service, target, name = '', account = '', group = false}) {
  // One chat per address, service, and account (servers.js targetKey compares addresses the way each service does).
  const same = (a, b) => typeof targetKey === 'function' ? targetKey(service, a) === targetKey(service, b)
    : String(a || '').toLowerCase() === String(b || '').toLowerCase();
  // With an account picked, only a chat through that account is the same chat; older links are the first account's.
  const first = chatAccounts.find((item) => item.protocol === service && item.id)?.id;
  const existing = routes.find((route) => route.protocol === service && (!account || (route.account || first) === account)
    && (same(route.target, target) || service === 'matrix' && !route.group && same(route.title, target)));
  if (existing) return threadKey({address: existing.address, protocol: existing.protocol});
  let result;
  if (group && service !== 'email') {
    const id = account || chatAccounts.find((item) => item.protocol === service && item.id)?.id;
    if (!id) throw new Error('Add a ' + labels[service] + ' account to join ' + target + '.');
    result = await api('protocols/join', {account: id, target, title: name});
  } else {
    result = await api('protocols/routes', {address: 'web-' + fluidCallId().slice(0, 16), protocol: service, target,
      title: name || target, group, ...(account ? {account} : {})});
  }
  if (!routes.some((route) => route.address === result.route.address && route.protocol === result.route.protocol)) routes.push(result.route);
  return threadKey({address: result.route.address, protocol: result.route.protocol});
}

/**
 * New chat, as a full page: one "To" box for a name, a phone number, or an address on any service. The service is
 * read from the format (people.js detectAddress); when it could be more than one, or you have more than one account
 * there, you pick. [prefill] waits in the new chat's message box; [to] fills the "To" box.
 */
/**
 * Several phone numbers typed into New chat, split by commas or semicolons, like the phone's smsto: list. Null unless
 * there are at least two and every one is a phone number. Repeats of the same number count once.
 */
function splitRecipients(text) {
  const parts = String(text || '').split(/[,;]/).map((part) => part.trim()).filter(Boolean);
  if (parts.length < 2 || parts.length > 50 || !parts.every((part) => /^\+?[\d ().-]+$/.test(part) && part.replace(/\D/g, '').length >= 3)) return null;
  const seen = new Set();
  return parts.filter((part) => { const digits = part.replace(/\D/g, ''); if (seen.has(digits)) return false; seen.add(digits); return true; });
}

/**
 * Multiple recipients, like the phone's: one message box, and each person gets their own text through your phone and
 * replies in their own chat.
 */
function openSeveralTexts(numbers, prefill = '', back) {
  const card = element('form', '', 'settings-section several-texts');
  card.noValidate = true;
  card.append(element('p', 'Each recipient gets their own SMS. Replies appear in individual chats.', 'muted'));
  const list = element('ul', '', 'recipient-list');
  list.setAttribute('aria-label', 'Recipients');
  for (const number of numbers) {
    const person = typeof personFor === 'function' ? personFor({address: number}) : null;
    const shown = typeof displayNumber === 'function' ? displayNumber(number) : number;
    const item = element('li', '');
    item.append(element('strong', person?.named ? person.name : shown), ...(person?.named ? [element('small', shown, 'muted')] : []));
    list.append(item);
  }
  const body = Object.assign(element('textarea', ''), {rows: 4, maxLength: 1600, value: prefill, required: true});
  const status = element('p', '', 'muted');
  status.setAttribute('role', 'status');
  const send = element('button', 'Send separate SMS', 'primary');
  const ready = () => { send.disabled = !body.value.trim(); };
  body.addEventListener('input', ready);
  card.append(element('h3', 'Recipients'), list, field('Message', body,
    phonePaired() ? 'Your phone sends them.' : 'They wait here until you connect your phone in Settings → Phone and other devices.'), send, status);
  card.addEventListener('submit', async (event) => {
    event.preventDefault();
    const text = body.value.trim();
    if (!text) return;
    if (sampleOn) { status.textContent = 'Sample inbox · nothing was sent.'; return; }
    send.disabled = body.disabled = true;
    status.textContent = 'Submitting…';
    let queued = 0;
    for (const address of numbers) {
      try { await api('schedule', {address, body: text, due: Date.now()}); queued++; } catch (error) { /* counted below */ }
    }
    const failed = numbers.length - queued;
    status.textContent = queued + ' queued for your phone' + (failed ? ' · ' + failed + ' could not be queued' : '');
    refresh().catch(() => {});
  });
  detailsPage('Multiple recipients', [card], back || (() => closeDetailsPage()));
  ready();
  body.focus();
}

function openNewChat(prefill = '', {to = '', back} = {}) {
  const leave = back || (() => closeDetailsPage());
  const form = element('form', '', 'settings-section new-chat-form');
  form.noValidate = true;
  const target = Object.assign(element('input', ''), {autocomplete: 'off', spellcheck: false, value: to,
    placeholder: 'Name, phone number, or address'});
  target.setAttribute('autocapitalize', 'off');
  target.setAttribute('aria-describedby', 'new-chat-formats');
  const formats = element('small', 'Phone number (several, with commas between) · Matrix @name:server · XMPP or email name@server · IRC nickname, riley@irc.network, or #channel', 'hint');
  formats.id = 'new-chat-formats';
  const toField = element('label', 'To');
  toField.append(target, formats);
  const people = element('div', '', 'new-chat-people');
  const choices = element('fieldset', '', 'choices new-chat-choices');
  // From, like a mail app's: which of your accounts on this service starts the chat (servers.js preselectFrom).
  const account = element('select', '');
  account.setAttribute('aria-label', 'From');
  const accountField = field('From', account, 'The chat starts from this account, and replies in it stay with it.');
  accountField.classList.add('from-field');
  const name = Object.assign(element('input', ''), {placeholder: 'Shown in your chat list', autocomplete: 'off', maxLength: 120});
  const nameField = field('Name (optional)', name);
  const group = Object.assign(element('input', ''), {type: 'checkbox'});
  const groupLabel = element('label', '', 'check');
  groupLabel.append(group, element('span', 'This is a group chat'));
  const note = element('p', '', 'hint');
  const status = element('p', '', 'error');
  status.setAttribute('role', 'alert');
  const start = element('button', 'Start chat', 'primary');
  let options = [];
  let picked = 0;
  const accountsFor = (service) => chatAccounts.filter((item) => item.protocol === service && item.id);
  const hasService = (service) => chatAccounts.some((item) => item.protocol === service);
  const describe = (option) => {
    if (option.several) return ['Text ' + option.several.length + ' people separately', 'Each person gets their own text and replies in their own chat'];
    if (option.service === 'sms') return ['Text ' + (typeof displayNumber === 'function' ? displayNumber(option.target) : option.target),
      phonePaired() ? 'SMS through your phone' : 'SMS · waits here until you connect your phone'];
    const label = labels[option.service] || option.service;
    if (option.service === 'sip') return ['Call ' + option.target, 'SIP calls are made from FluidMessages for Android'];
    const what = option.group ? (option.service === 'irc' ? 'Join and message ' : 'Join and message the room ') : 'Message ';
    const network = option.network ? ' on ' + option.network : '';
    return [what + option.target + network, label + (option.guess ? ' nickname' : '') + (hasService(option.service) ? '' : ' · add an account first')];
  };
  const draw = () => {
    const text = target.value.trim();
    const several = splitRecipients(text);
    options = several ? [{service: 'sms', target: several.join(', '), several}]
      : (typeof detectAddress === 'function' ? detectAddress(text) : []).filter((option) => option.service !== 'irc' || !option.guess || hasService('irc'));
    picked = Math.min(picked, Math.max(0, options.length - 1));
    // People you already know, by name, number, or address.
    people.replaceChildren();
    const words = text.toLowerCase();
    if (words && typeof knownPeople === 'function') {
      const digits = words.replace(/\D/g, '');
      const found = knownPeople().filter((person) => person.name.toLowerCase().includes(words)
        || digits.length >= 3 && person.numbers.some((number) => number.replace(/\D/g, '').includes(digits))
        || person.accounts.some((item) => String(item.target).toLowerCase().includes(words))).slice(0, 6);
      if (found.length) people.append(element('h3', 'Contacts'));
      for (const person of found) {
        const row = element('button', '', 'member-row');
        row.type = 'button';
        const detail = element('span', '', 'detail-words');
        detail.append(element('strong', person.name), element('small', [...person.numbers.map((number) => displayNumber(number)),
          ...person.accounts.map((item) => item.target)].slice(0, 3).join(' · ') || 'Contact'));
        row.append(typeof avatarFor === 'function' ? avatarFor(person.name) : element('span', initials(person.name), 'avatar'), detail,
          element('span', '›', 'chevron'));
        row.addEventListener('click', () => messagePerson(person, prefill).catch((error) => { status.textContent = error.message; }));
        people.append(row);
      }
    }
    choices.replaceChildren();
    choices.hidden = !options.length;
    if (options.length) {
      choices.append(element('legend', options.length > 1 ? 'Which service?' : 'Start'));
      options.forEach((option, index) => {
        const [title, detail] = describe(option);
        const input = Object.assign(element('input', ''), {type: 'radio', name: 'new-chat-service', value: String(index), checked: index === picked});
        const row = element('label', '', 'choice');
        const words = element('span', '');
        words.append(element('strong', title), element('small', detail, 'hint'));
        row.append(input, words);
        input.addEventListener('change', () => { if (input.checked) { picked = index; drawOptions(); } });
        choices.append(row);
      });
    }
    drawOptions();
  };
  const drawOptions = () => {
    const option = options[picked];
    const service = option?.service;
    const mine = service ? accountsFor(service) : [];
    // An IRC nickname on a network goes through your account there.
    const preferred = option?.network ? mine.find((item) => String(item.name).toLowerCase().endsWith('@' + option.network)) : null;
    // Every account on the service with its service tag; the one used most recently is picked to begin with.
    const chosen = mine.length > 1 && typeof preselectFrom === 'function' ? preselectFrom(mine, option?.network || '') : '';
    account.replaceChildren(...mine.map((item) => Object.assign(element('option', (item.name || item.id) + ' · ' + (labels[service] || service)),
      {value: item.id, selected: item.id === chosen})));
    if (mine.length < 2) account.value = '';
    accountField.hidden = mine.length < 2;
    const chat = option && !['sms', 'sip'].includes(service);
    nameField.hidden = !chat || !!option.group;
    groupLabel.hidden = service !== 'xmpp';
    group.checked = !!option?.group;
    note.replaceChildren();
    start.textContent = !option ? 'Start chat' : option.several ? 'Next' : service === 'sip' ? 'Open the keypad' : option.group ? 'Join and open' : 'Start chat';
    start.disabled = !option;
    if (chat && !hasService(service) && !sampleOn) {
      start.disabled = true;
      const add = element('button', 'Add a ' + labels[service] + ' account', 'quiet');
      add.type = 'button';
      add.addEventListener('click', () => openSettings('settings-accounts').catch(showError));
      note.append(document.createTextNode('There’s no ' + labels[service] + ' account on FluidMessages Web yet. '), add);
    } else if (service === 'sms' && !phonePaired() && !sampleOn) {
      note.textContent = 'Texts wait here until you connect your phone in Settings → Phone and other devices.';
    } else if (option?.network && mine.length && !preferred) {
      note.textContent = 'None of your IRC accounts is on ' + option.network + '. Pick one, or add that network in Settings → Accounts.';
    } else if (!option && target.value.trim() && !people.children.length) {
      note.textContent = 'That doesn’t look like a phone number or an address yet.';
    }
  };
  target.addEventListener('input', () => { picked = 0; status.textContent = ''; draw(); });
  form.append(toField, people, choices, accountField, nameField, groupLabel, note, status, start);
  form.addEventListener('submit', async (event) => {
    event.preventDefault();
    const option = options[picked];
    if (!option) {
      status.textContent = target.value.trim() ? 'Enter a phone number or an address, like +1 202 555 0143 or @maya:example.org.' : 'Enter who to message.';
      return;
    }
    status.textContent = '';
    const fill = () => { if (prefill) { get('body').value = prefill; drafts.set(active, prefill); } get('body').focus(); };
    if (option.service === 'sip') {
      closeDetailsPage(false);
      if (typeof openKeypad === 'function') openKeypad({address: option.target});
      return;
    }
    if (option.several) {
      openSeveralTexts(option.several, prefill, () => openNewChat(prefill, {to: target.value, back}));
      return;
    }
    if (option.service === 'sms') {
      openThread(threadKey({address: option.target, protocol: 'sms'}));
      fill();
      return;
    }
    if (sampleOn) { status.textContent = 'Sample inbox · nothing was sent. Exit the sample inbox to start a chat.'; return; }
    start.disabled = true;
    try {
      const key = await startInternetChat({service: option.service, target: option.target, name: name.value.trim(),
        account: accountField.hidden ? '' : account.value, group: group.checked});
      openThread(key);
      fill();
    } catch (error) { status.textContent = error.message; }
    finally { start.disabled = false; }
  });
  const nodes = [];
  // People (this form) or Channels: every server's joined channels and rooms, to open one or join another (servers.js).
  if (typeof newChatChannels === 'function') {
    const channels = newChatChannels({close: () => closeDetailsPage(false)});
    channels.hidden = true;
    const tabs = element('div', '', 'segmented-buttons new-chat-switch');
    tabs.setAttribute('role', 'radiogroup');
    tabs.setAttribute('aria-label', 'Start a chat with');
    for (const [panel, label] of [[form, 'People'], [channels, 'Channels']]) {
      const button = element('button', label);
      button.type = 'button';
      button.setAttribute('role', 'radio');
      button.setAttribute('aria-checked', String(panel === form));
      button.addEventListener('click', () => {
        for (const other of tabs.children) other.setAttribute('aria-checked', String(other === button));
        form.hidden = panel !== form;
        channels.hidden = panel !== channels;
        (panel === form ? target : channels.querySelector('button'))?.focus();
      });
      tabs.append(button);
    }
    nodes.push(tabs, form, channels);
  } else nodes.push(form);
  detailsPage(prefill ? 'Forward to' : 'New chat', nodes, leave);
  draw();
  target.focus();
}

/** Opens a chat in Messages, from wherever New chat or a contact page was opened. */
function openThread(key) {
  if (section !== 'messages') showSection('messages');
  select(key);
}

/** Messages someone you know: their newest chat, their number, or a new chat on their first address. */
async function messagePerson(person, prefill = '') {
  const fill = () => { if (prefill) { get('body').value = prefill; drafts.set(active, prefill); } get('body').focus(); };
  const chat = person.chats.find((key) => threads.has(key)) || person.chats[0];
  if (chat) { openThread(chat); fill(); return; }
  if (person.numbers.length) { openThread(threadKey({address: person.numbers[0], protocol: 'sms'})); fill(); return; }
  const account = person.accounts.find((item) => ['matrix', 'xmpp', 'email', 'irc'].includes(item.protocol));
  if (!account) throw new Error('There’s no number or chat address for ' + person.name + ' yet.');
  if (sampleOn) throw new Error('Sample inbox · nothing was sent.');
  openThread(account.address ? threadKey({address: account.address, protocol: account.protocol})
    : await startInternetChat({service: account.protocol, target: account.target.replace(/@irc\..*$/i, ''), name: person.name}));
  fill();
}

function routeFor(key) {
  const address = threadAddress(key);
  const protocol = threadProtocol(key);
  return routes.find((route) => route.address === address && route.protocol === protocol)
    || routes.find((route) => route.address === address && internet(route.protocol));
}

// Conversation details: a full page like the phone's (chats.js draws the page, its members, and its folders).

/** The Message, Call, and Video tiles under a chat's name, with the same icons as the chat's header. */
function reachTiles(key) {
  const row = element('div', '', 'reach-tiles');
  const tile = (icon, label, detail, run) => {
    const button = headButton(icon, label + (detail ? ' · ' + detail : ''));
    button.className = 'reach-tile';
    const words = element('span', '', 'reach-words');
    words.append(element('strong', label));
    if (detail) words.append(element('small', detail));
    button.append(words);
    button.addEventListener('click', run);
    row.append(button);
  };
  const back = () => { closeDetailsPage(false); };
  if (!get('schedule').hidden || sampleOn) tile('message', 'Message', '', () => { back(); get('body').focus(); });
  if (!get('call').hidden) tile('voice', 'Call', 'From your phone', () => { back(); get('call').click(); });
  if (callable(key)) {
    tile('voice', 'Call', 'Matrix', () => { back(); startCall(false).catch(showError); });
    tile('video', 'Video', 'Matrix', () => { back(); startCall(true).catch(showError); });
  } else if (threadAddress(key) && (!get('schedule').hidden || sampleOn) && callWayOn('meeting') && !ircCallsHidden(key)) {
    tile('voice', 'Call', 'Meeting link', () => { back(); startMeeting(false).catch(showError); });
    tile('video', 'Video', 'Meeting link', () => { back(); startMeeting(true).catch(showError); });
  }
  return row.children.length ? row : null;
}

/** The chat's padlock and what it means, like the Security section on the phone. */
function securityRow(key) {
  const [tone, label] = protection(key);
  const protocol = threadProtocol(key);
  const detail = {
    green: fluidEncrypted(key) ? 'Only people with your shared encryption key can read these messages.' : 'Only you and the people in this chat can read it.',
    yellow: 'Protected on the way to the server, but the server can read it.',
    red: protocol === 'rcs' ? 'RCS isn’t available in FluidMessages yet. Texts in this chat are SMS, which your carrier can read.'
      : 'Your carrier and anyone along the way can read these messages.',
  }[tone];
  const omemo = routeFor(key)?.omemo;
  const row = element('div', '', 'detail-row');
  const words = element('span', '', 'detail-words');
  words.append(element('strong', label), element('small', protocolName(protocol) + ' · '
    + (omemo && !fluidEncrypted(key) ? omemo.words : detail)));
  row.append(words, lockBadge(tone, label, 'large'));
  return row;
}

/** The small padlock in a protection color: open and red, or closed and yellow or green. */
function lockBadge(tone, label, extra = '') {
  const badge = element('span', '', 'security-lock tone-' + tone + (extra ? ' ' + extra : ''));
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
  badge.append(svg);
  return badge;
}

async function openDetails({back} = {}) {
  if (!active || !threadAddress(active)) return;
  const key = active;
  // The sample chats never save to the server, so they skip its timers.
  const settings = sampleOn ? null : companionSettings || await loadSettings().catch(() => null);
  if (key !== active) return;
  const address = threadAddress(key);
  const route = routeFor(key);
  const group = threadIsGroup(key);
  const members = group ? threadMembers(key) : [];
  const title = threadTitle(key);
  const channel = ircChannels[address];
  const subtitle = channel ? (channel.topic ? 'Topic: ' + channel.topic : 'IRC channel') + ' · ' + (members.length + 1) + ' people'
    : group ? (sampleOn ? 'Sample group · ' : '') + (members.length + 1) + ' members including you'
      : sampleOn ? 'Sample contact · ' + protocolName(threadProtocol(key))
        : automatedThread(key) ? 'Automated sender · ' + address : route?.target || address;
  const nodes = [detailsHero(title, subtitle), reachTiles(key)];
  // Right under Message and Call: + Add another way to reach them, then their linked addresses. One person only.
  if (!group) nodes.push(linkedSection(key, title, back));
  if (!group) {
    const info = element('section', '', 'settings-section');
    info.append(element('h3', 'Contact info'));
    const addresses = contactAddresses(key);
    for (const [label, value] of addresses) info.append(detailRow(label, value));
    if (!addresses.length) info.append(element('p', 'No address is known for this chat.', 'muted'));
    // Their contact page: every address, favorites, and shared groups (people.js).
    if (typeof contactPageLink === 'function') info.append(contactPageLink(key, back));
    nodes.push(info);
  }
  if (group) {
    const list = memberList(key, members);
    if (route?.group) {
      // Inviting and removing people, where the protocol allows it.
      const who = Object.assign(element('input', ''), {placeholder: route.protocol === 'matrix' ? '@maya:example.org'
        : route.protocol === 'xmpp' ? 'Address to invite, or nickname to remove' : 'Nickname', autocomplete: 'off'});
      const status = element('p', '', 'muted');
      status.setAttribute('role', 'status');
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
      list.append(field('Invite or remove someone', who), row, element('p', 'Removing someone needs moderator or operator rights in the group.', 'muted'), status);
    } else if (address.startsWith('mms:')) {
      list.append(element('p', 'Group texts can’t change members. Start a new group from your phone to add or remove someone.', 'muted'));
    }
    nodes.push(list);
  }
  // Leave a channel or room on its server (servers.js).
  if (typeof leaveSection === 'function' && !sampleOn) nodes.push(leaveSection(route));
  nodes.push(...conversationSettings(key, settings));
  detailsPage('Conversation details', nodes, back);
}

// Link an address: reach the same person on Matrix, XMPP, Email, or IRC from this chat, like the phone's conversation
// details (MainActivity linkAddress and confirmUnlink). A link is a route with this chat's address, so their messages
// on that service belong to this person; with Combine linked conversations on, they're in this one chat.

const LINK_SERVICES = ['matrix', 'xmpp', 'email', 'irc'];
const LINK_HINTS = {matrix: '@maya:example.org', xmpp: 'maya@example.org or room@conference.example.org', email: 'maya@example.org',
  irc: 'nickname or #channel'};

/** The addresses linked to a chat: routes with its address on another service. Its own route isn't one. */
function linkedRoutes(key) {
  const address = threadAddress(key);
  const own = internet(threadProtocol(key)) ? threadProtocol(key) : '';
  return routes.filter((route) => route.address === address && route.protocol !== own && LINK_SERVICES.includes(route.protocol)
    && !(route.group && route.account));
}

function detailsRowButton(title, detail, run) {
  const row = element('button', '', 'settings-row');
  row.type = 'button';
  const words = element('span', '', 'settings-words');
  words.append(element('strong', title), element('small', detail));
  const chevron = element('span', '', 'settings-chevron');
  if (typeof settingsIcon === 'function') chevron.append(settingsIcon('chevron'));
  row.append(words, chevron);
  row.addEventListener('click', run);
  return row;
}

/** The words for Ways to reach, the same as the phone's (ProfileEditor.kt ReachWays). */
const REACH_WAYS = {
  title: 'Ways to reach them',
  button: (name) => '+ Add another way to reach ' + name,
  detail: (ready) => ready ? 'Link their Matrix, XMPP, email, or IRC to this chat. Your past chats are searched to help you find them.'
    : 'First sign in to Matrix, XMPP, Email, or IRC in Accounts. Then link their address here.',
};

function linkedSection(key, name, back) {
  const section = element('section', '', 'settings-section');
  section.id = 'details-links';
  section.append(element('h3', REACH_WAYS.title));
  const reopen = () => openDetails({back}).catch(showError);
  // A clear button first. With no chat account yet it opens Accounts; the sample inbox links nothing.
  const ready = !sampleOn && chatAccounts.length > 0;
  const add = element('button', REACH_WAYS.button(name), 'primary add-way');
  add.type = 'button';
  add.addEventListener('click', () => (sampleOn ? say('Sample: nothing is linked. Try it in a real chat.')
    : ready ? openLinkAddress(key, name, reopen) : openSettings('accounts').catch(showError)));
  section.append(add, element('p', REACH_WAYS.detail(ready || sampleOn), 'muted'));
  if (sampleOn) return section;
  if (linkedRoutes(key).length) section.append(element('h4', 'Linked addresses'));
  for (const route of linkedRoutes(key)) {
    const via = typeof accountVia === 'function' ? accountVia(route).replace(/^ · /, '') : '';
    section.append(detailsRowButton(protocolName(route.protocol) + ' · ' + route.target, (via ? via + ' · ' : '') + 'Linked · tap to unlink',
      () => confirmUnlink(key, route, reopen)));
  }
  if (!webLook.combineProtocols && !usingPhoneLook()) {
    section.append(element('p', 'To see every way you talk to them in this one chat, turn on Combine linked conversations in Settings → Chat services.', 'muted'));
  }
  return section;
}

/** Link an address, step one: which service. */
function openLinkAddress(key, name, done) {
  const box = element('section', '', 'settings-section');
  const usable = LINK_SERVICES.filter((service) => chatAccounts.some((account) => account.protocol === service) && gatewayEnabled.includes(service));
  for (const service of LINK_SERVICES) {
    const ready = usable.includes(service);
    box.append(detailsRowButton(labels[service], ready ? LINK_HINTS[service]
      : chatAccounts.some((account) => account.protocol === service) ? 'Turned off in Settings → Chat services' : 'Add ' + (service === 'matrix' ? 'a ' : 'an ') + labels[service] + ' account in Settings → Accounts first',
    () => (ready ? linkAddressForm(key, name, service, () => openLinkAddress(key, name, done), done) : openSettings(chatAccounts.some((account) => account.protocol === service) ? 'ways' : 'accounts').catch(showError))));
  }
  detailsPage('Link an address', [box], done);
}

/** Link an address, step two: their address there, and for XMPP whether it's a group chat. */
function linkAddressForm(key, name, service, back, done) {
  const form = element('form', '', 'settings-section stack');
  const target = Object.assign(element('input', ''), {required: true, maxLength: 300, placeholder: LINK_HINTS[service], autocomplete: 'off',
    spellcheck: false, type: service === 'email' ? 'email' : 'text'});
  form.append(field(labels[service] + ' address', target, 'Type a new address, or pick someone from your ' + labels[service] + ' chats.'));
  // Suggestions from your chats on this service (people.js), found in this browser only; nothing is sent to search.
  if (typeof linkSuggestionBox === 'function') {
    form.append(linkSuggestionBox(target, () => service, (item) => { target.value = item.address; target.focus(); }));
  }
  const group = checkbox('This is a group chat', false, () => {});
  if (service === 'xmpp') form.append(group);
  const accounts = chatAccounts.filter((account) => account.protocol === service);
  const from = element('select', '');
  for (const account of accounts) from.append(Object.assign(element('option', account.name || account.id), {value: account.id}));
  if (accounts.length > 1) form.append(field('From', from));
  const status = element('p', '', 'error');
  status.setAttribute('role', 'alert');
  const link = element('button', 'Link', 'primary');
  form.append(status, link);
  form.addEventListener('submit', async (event) => {
    event.preventDefault();
    const value = target.value.trim();
    if (!value) { status.textContent = 'Enter an address.'; return; }
    link.disabled = true;
    try {
      const result = await api('protocols/routes', {address: threadAddress(key), protocol: service, target: value, title: name,
        group: service === 'xmpp' && group.querySelector('input').checked, ...(accounts.length > 1 ? {account: from.value} : {})});
      if (result.route && !routes.some((route) => route.address === result.route.address && route.protocol === result.route.protocol)) routes.push(result.route);
      refresh().catch(() => {});
      say(labels[service] + ' linked');
      done();
    } catch (error) { status.textContent = error.message; }
    finally { link.disabled = false; }
  });
  detailsPage(labels[service] + ' for ' + name, [form], back);
  target.focus();
}

/** Unlink, on its own page; Cancel goes back to the chat's details. */
function confirmUnlink(key, route, done) {
  const box = element('section', '', 'settings-section');
  const label = protocolName(route.protocol);
  box.append(element('p', 'New ' + label + ' messages stop appearing here. Earlier messages stay.', 'muted'));
  const status = element('p', '', 'error');
  status.setAttribute('role', 'alert');
  const row = element('div', '', 'button-row wrap');
  const unlink = element('button', 'Unlink', 'danger-button');
  unlink.type = 'button';
  unlink.addEventListener('click', async () => {
    unlink.disabled = true;
    try {
      await api('protocols/routes/delete', {address: route.address, protocol: route.protocol});
      const index = routes.findIndex((item) => item.address === route.address && item.protocol === route.protocol);
      if (index >= 0) routes.splice(index, 1);
      refresh().catch(() => {});
      done();
    } catch (error) { status.textContent = error.message; unlink.disabled = false; }
  });
  const cancel = element('button', 'Cancel');
  cancel.type = 'button';
  cancel.addEventListener('click', done);
  row.append(unlink, cancel);
  box.append(status, row);
  detailsPage('Unlink ' + label + '?', [box], done);
}

/** Shown wherever disappearing messages can be turned on for texts. */
const CARRIER_COPY_NOTE = 'Your carrier and the other person’s phone can still keep a copy of texts. Disappearing only removes them from FluidMessages.';

/** A chat's own settings, also shown on the person's contact page (people.js): photos and files, wallpaper,
 * folders, security and its timer, and its code. [settings] is null in the sample chats. */
function conversationSettings(key, settings, {block = true} = {}) {
  const address = threadAddress(key);
  const extras = detailsExtras(key, {block});
  const nodes = [sharedMedia(key)];
  if (extras.wallpaper) nodes.push(extras.wallpaper);
  nodes.push(extras.conversation);
  const security = element('section', '', 'settings-section');
  security.append(element('h3', 'Security'), securityRow(key));
  if (settings) {
    const own = settings.disappearing.chats[address];
    const timer = timerSelect(own === undefined ? -1 : own, true);
    timer.addEventListener('change', () => {
      const chats = {...settings.disappearing.chats};
      const effective = (own) => own ?? settings.disappearing.default ?? 0;
      const before = effective(chats[address]);
      if (Number(timer.value) < 0) delete chats[address]; else chats[address] = Number(timer.value);
      const after = effective(chats[address]);
      api('settings', {disappearing: {...settings.disappearing, chats}}).then(loadSettings)
        .then(() => { if (after !== before) return announceSetting(key, timerNoticeText(after)); }).catch(showError);
    });
    security.append(field('Disappearing messages: remove messages in this chat after', timer));
    if (!internet(threadProtocol(key))) security.append(element('p', CARRIER_COPY_NOTE, 'muted carrier-note'));
    if (settings.follow_phone && settings.phone.paired) {
      // A phone that merges settings (sync-merge.js) takes this timer; an older one sends its own over it.
      security.append(element('p', typeof mergeActive === 'function' && mergeActive()
        ? 'Your phone gets this timer on its next sync. If it changed this one too, you choose which to keep.'
        : 'Your phone’s timers replace this when it syncs. Change it on the phone, or choose '
          + '“This web app’s own” in Settings → Phone and other devices.', 'muted'));
    }
  } else if (sampleOn) {
    security.append(element('p', 'Disappearing messages are set per chat in your real chats’ details. Nothing in the sample inbox is saved.', 'muted'));
  }
  nodes.push(security);
  if (typeof omemoChat === 'function' && omemoChat(key)) nodes.push(omemoSection(key));
  nodes.push(extras.code);
  return nodes;
}

/**
 * Tells the other person about a chat setting change, as the plain-text notice the phone sends (cards.js). Not when
 * “Announce chat setting changes” is off, and never from the sample inbox or a decoy session, where nothing is sent.
 */
async function announceSetting(key, text) {
  if (!key || sampleOn || (typeof decoyLike === 'function' && decoyLike()) || resolvedAppearance().announceSettings === false) return;
  const address = threadAddress(key), protocol = threadProtocol(key);
  if (!internet(protocol)) {
    await api('schedule', {address, body: text, due: Date.now()});
  } else {
    const request = crypto.randomUUID ? crypto.randomUUID() : String(Date.now()) + Math.random().toString(16).slice(2);
    await api('protocols/send', {address, protocol, body: text, text, request});
  }
  refresh().catch(() => setSyncStatus('Sync unavailable', true));
}

// Calls

function callable(key) {
  const route = key && routeFor(key);
  return !!route && route.protocol === 'matrix' && callWayOn('matrix');
}

/** Settings → Calls → Ways to call: Matrix voice and video, and meeting links, each on unless turned off. */
function callWayOn(way) { return prefs[way === 'matrix' ? 'callMatrix' : 'callMeetings'] !== false; }

/**
 * IRC chats hide call and meeting-link buttons unless Settings → Accounts → IRC turns them on: many channels ban
 * call and meeting links, and moderators may kick or ban you for posting them. Other protocols keep theirs.
 */
function ircCallsHidden(key) { return !!key && threadProtocol(key) === 'irc' && prefs.ircCalls !== true; }

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
  // XMPP: the lock FluidMessages Web reports for the chat (OMEMO, in omemo.js and xmpp_omemo.py).
  if (route?.omemo) return route.omemo.color === 'green' ? ['green', 'Locked with OMEMO'] : ['yellow', 'Encrypted in transit'];
  if (route?.encrypted) return ['green', 'End-to-end encrypted'];
  if (internet(protocol)) return ['yellow', 'Encrypted in transit'];
  return ['red', 'Not encrypted'];
}

function updateCallButtons() {
  const matrix = callable(active);
  callButtons.voice.hidden = callButtons.video.hidden = !matrix;
  // Matrix chats call natively; everywhere else the camera button sends a meeting link.
  if (callButtons.meeting) callButtons.meeting.hidden = !active || !threadAddress(active) || matrix || !callWayOn('meeting') || ircCallsHidden(active);
  get('details').hidden = !active || !threadAddress(active);
  let badge = document.getElementById('security-badge');
  if (!badge) {
    badge = element('span', '', 'security-badge');
    badge.id = 'security-badge';
    get('transport').after(badge);
  }
  // Settings → Chat services → Encryption status: Off hides the lock (Security in Details still says it).
  badge.hidden = !active || appearance.encryptionMode === 'off';
  if (!active) return;
  const [tone, label] = protection(active);
  // A small padlock in the protection color; Details explains it.
  const fresh = lockBadge(tone, label);
  badge.className = fresh.className;
  badge.title = label;
  badge.setAttribute('role', 'img');
  badge.setAttribute('aria-label', label);
  badge.replaceChildren(...fresh.childNodes);
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
  if (!signedIn || get('workspace').hidden) return;
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

const memo = (note, key = 'message') => (note ? '&' + key + '=' + encodeURIComponent(note) : '');
const HANDLE = /^[A-Za-z0-9._-]{1,64}$/;
// The same ways to pay as the Android app (Money.kt). Monero is first and the default.
const BUILT_IN_ASSETS = {
  'XMR on Monero': {pattern: /^([48][0-9AB][1-9A-HJ-NP-Za-km-z]{93}|4[0-9AB][1-9A-HJ-NP-Za-km-z]{104})$/, uri: (to, amount, note) =>
    'monero:' + to + '?tx_amount=' + amount + memo(note, 'tx_description'), explorer: 'https://xmrchain.net/tx/'},
  'BTC on Bitcoin': {pattern: /^(bc1[02-9ac-hj-np-z]{11,87}|[13][1-9A-HJ-NP-Za-km-z]{25,34})$/, uri: (to, amount, note) =>
    'bitcoin:' + to + '?amount=' + amount + memo(note), explorer: 'https://mempool.space/tx/'},
  'BTC on Lightning': {pattern: /^([A-Za-z0-9._-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}|lnurl1[02-9ac-hj-np-z]{20,})$/i, uri: (to) => 'lightning:' + to,
    label: 'Lightning address'},
  'USDC on Solana': {pattern: /^[1-9A-HJ-NP-Za-km-z]{32,44}$/, uri: (to, amount, note) => 'solana:' + to + '?amount=' + amount
    + '&spl-token=EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v' + memo(note), explorer: 'https://solscan.io/tx/'},
  'SOL on Solana': {pattern: /^[1-9A-HJ-NP-Za-km-z]{32,44}$/, uri: (to, amount, note) => 'solana:' + to + '?amount=' + amount + memo(note),
    explorer: 'https://solscan.io/tx/'},
  'USDC on Ethereum': {pattern: /^0x[0-9a-fA-F]{40}$/, uri: (to, amount) =>
    'ethereum:0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48@1/transfer?address=' + to + '&uint256=' + units(amount, 6), explorer: 'https://etherscan.io/tx/'},
  'USDC on Base': {pattern: /^0x[0-9a-fA-F]{40}$/, uri: (to, amount) =>
    'ethereum:0x833589fCD6eDb6E08f4c7C32D4f71b54bdA02913@8453/transfer?address=' + to + '&uint256=' + units(amount, 6), explorer: 'https://basescan.org/tx/'},
  'ETH on Ethereum': {pattern: /^0x[0-9a-fA-F]{40}$/, uri: (to, amount) => 'ethereum:' + to + '@1?value=' + units(amount, 18), explorer: 'https://etherscan.io/tx/'},
  'via PayPal': {pattern: HANDLE, uri: (to, amount, note, currency) => 'https://paypal.me/' + to + '/' + amount + currency,
    currencies: ['USD', 'EUR', 'GBP', 'CAD', 'AUD', 'JPY', 'CHF', 'MXN', 'BRL'], label: 'PayPal.me username'},
  'via Venmo': {pattern: HANDLE, uri: (to, amount, note) => 'https://venmo.com/?txn=pay&recipients=' + to + '&amount=' + amount + memo(note, 'note'),
    currencies: ['USD'], label: 'Venmo username'},
  'via Cash App': {pattern: /^\$?[A-Za-z][A-Za-z0-9_-]{0,19}$/, uri: (to, amount) => 'https://cash.app/$' + to.replace(/^\$/, '') + '/' + amount,
    currencies: ['USD', 'GBP'], label: '$cashtag'},
  'via Revolut': {pattern: HANDLE, uri: (to, amount, note, currency) => 'https://revolut.me/' + to + '?amount=' + amount + '&currency=' + currency,
    currencies: ['EUR', 'GBP', 'USD', 'CHF', 'PLN', 'SEK', 'NOK', 'DKK'], label: 'Revolut username'},
  'via UPI': {pattern: /^[A-Za-z0-9._-]{2,256}@[A-Za-z]{2,64}$/, uri: (to, amount, note) => 'upi://pay?pa=' + to + '&am=' + amount + '&cu=INR' + memo(note, 'tn'),
    currencies: ['INR'], label: 'UPI ID'},
  'via Bank transfer': {pattern: /^[A-Z]{2}[0-9]{2}[A-Z0-9]{11,30}$/, uri: (to, amount, note, currency) =>
    'payto://iban/' + to.toUpperCase() + '?amount=' + currency + ':' + amount + memo(note),
    currencies: ['EUR', 'GBP', 'CHF', 'PLN', 'SEK', 'DKK', 'NOK', 'CZK', 'HUF', 'RON'], label: 'IBAN'},
};

// Ways to pay you added in this browser: a name and a payment link with {address}, {amount}, and {note}.
function customAssets() {
  try { return JSON.parse(localStorage.getItem('money.custom') || '[]').filter((item) => item && item.name && item.template); }
  catch (error) { return []; }
}

function allAssets() {
  const assets = {...BUILT_IN_ASSETS};
  for (const item of customAssets()) {
    assets[item.name] = {custom: true, pattern: /^.{3,256}$/, label: 'address', uri: (to, amount, note) =>
      item.template.replace('{address}', to).replace('{amount}', amount).replace('{note}', encodeURIComponent(note || ''))};
  }
  return assets;
}

const ASSETS = BUILT_IN_ASSETS;

function knownAsset(asset) {
  const lower = asset.toLowerCase();
  return Object.keys(BUILT_IN_ASSETS).some((name) => name.toLowerCase() === lower || (BUILT_IN_ASSETS[name].currencies && lower.endsWith(' ' + name.toLowerCase())));
}

function moneyWarning(outgoing) {
  return element('div', outgoing ? '⚠︎ Custom payment method. FluidMessages can’t check its link, so they’ll see a warning too.'
    : '⚠︎ Custom payment method. FluidMessages can’t check where this link sends money. Pay only if you trust the sender and the app it opens.',
  'money-warning');
}

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
  const incoming = message.type === 1;
  const via = asset.includes(' via ');
  const unit = via ? asset.split(' via ')[0] : asset.split(' on ')[0];
  const service = via ? asset.split(' via ')[1].replace(/^./, (c) => c.toUpperCase()) : '';
  const network = via ? 'via ' + service : asset.includes(' on ') ? 'on ' + asset.split(' on ')[1] : '';
  const unverified = !knownAsset(asset);
  const card = element('div', '', 'money-card');
  card.append(element('small', kind === 'Sent' ? (incoming ? 'Sent to you' : 'You sent') : (incoming ? 'Requested from you' : 'You requested')),
    element('strong', amount + ' ' + unit), element('span', network, 'muted'));
  if (note) card.append(element('p', note));
  const known = /^((solana|ethereum|bitcoin|monero|lightning|upi):\S+|payto:\/\/iban\/\S+|https:\/\/(paypal\.me|venmo\.com|cash\.app|revolut\.me)\/\S+)$/;
  const payable = unverified ? /^[a-z][a-z0-9+.-]{1,30}:\S+$/.test(link || '') && !/^(javascript|data|file|vbscript):/i.test(link) : known.test(link || '');
  if (link && kind !== 'Sent' && incoming && payable) {
    const pay = element('a', via ? 'Pay with ' + service : 'Pay with your wallet', 'primary');
    pay.href = link;
    pay.rel = 'noopener noreferrer';
    if (/^https:/.test(link)) pay.target = '_blank';
    const copy = element('button', 'Copy payment link');
    copy.type = 'button';
    copy.addEventListener('click', () => navigator.clipboard?.writeText(link));
    card.append(pay, copy);
  } else if (link && kind === 'Sent' && /^https:\/\/(solscan\.io|etherscan\.io|basescan\.org|mempool\.space|xmrchain\.net)\/tx\//.test(link)) {
    const view = element('a', 'View transaction');
    view.href = link;
    view.target = '_blank';
    view.rel = 'noopener noreferrer';
    card.append(view);
  }
  if (unverified) card.append(moneyWarning(!incoming));
  bubble.replaceChildren(card);
  return true;
}

// How each way to pay looks in the picker, the same as the phone (Money.kt): a name, a short detail, and a badge.
const MONEY_LOOKS = {
  'XMR on Monero': ['Monero', 'XMR · private by default', 'ɱ', '#ff6600'],
  'BTC on Bitcoin': ['Bitcoin', 'BTC', '₿', '#f7931a'],
  'BTC on Lightning': ['Lightning', 'Fast, tiny-fee bitcoin', '⚡', '#7b3fe4'],
  'USDC on Solana': ['USDC', 'on Solana', '$', '#2775ca'],
  'SOL on Solana': ['Solana', 'SOL', '◎', '#9945ff'],
  'USDC on Ethereum': ['USDC', 'on Ethereum', '$', '#2775ca'],
  'USDC on Base': ['USDC', 'on Base', '$', '#0052ff'],
  'ETH on Ethereum': ['Ethereum', 'ETH', 'Ξ', '#627eea'],
  'via PayPal': ['PayPal', 'PayPal.me link', 'P', '#003087'],
  'via Venmo': ['Venmo', 'US dollars', 'V', '#008cff'],
  'via Cash App': ['Cash App', '$cashtag', '$', '#00c244'],
  'via Revolut': ['Revolut', 'Revolut.me link', 'R', '#191c1f'],
  'via UPI': ['UPI', 'Indian rupees', '₹', '#097939'],
  'via Bank transfer': ['Bank transfer', 'IBAN · payto: link', '⌂', '#5856d6'],
};

function moneyLook(name) {
  const [title, detail, badge, color] = MONEY_LOOKS[name] || [name.replace(/^via /, ''), 'Added by you', (name.replace(/^via /, '')[0] || '?').toUpperCase(), '#8e8e93'];
  return {title, detail, badge, color};
}

function defaultAsset() {
  const saved = localStorage.getItem('money.default');
  return saved && allAssets()[saved] ? saved : 'XMR on Monero';
}

function moneyBadge(name, size) {
  const look = moneyLook(name);
  const badge = element('span', look.badge, 'money-badge');
  badge.setAttribute('aria-hidden', 'true');
  badge.style.background = bubbleFill(look.color);
  badge.style.setProperty('--badge', size + 'px');
  return badge;
}

/**
 * Request or send money in two steps, like the phone: first pick how (your usual method is one big button on
 * top, then crypto, payment apps and banks, and your own), then a focused form with only what that method needs.
 */
function openMoney() {
  if (!active && !get('address').value.trim()) { showError(new Error('Choose a conversation first')); return; }
  const sheet = dialog('Money');
  sheet.classList.add('money-sheet');
  const body = element('div', '', 'settings-section money-body');
  sheet.append(body);
  let request = true;

  const kindSwitch = () => {
    const set = element('div', '', 'segmented-buttons');
    set.setAttribute('role', 'radiogroup');
    set.setAttribute('aria-label', 'Request or send');
    for (const [value, label] of [[true, 'Request'], [false, 'Send']]) {
      const button = element('button', label);
      button.type = 'button';
      button.setAttribute('role', 'radio');
      button.setAttribute('aria-checked', String(request === value));
      button.addEventListener('click', () => { request = value; pick(); });
      set.append(button);
    }
    return set;
  };

  const tile = (name, big) => {
    const look = moneyLook(name);
    const button = element('button', '', big ? 'money-usual' : 'money-tile');
    button.type = 'button';
    const words = element('span', '', 'money-words');
    if (big) words.append(element('small', 'Your usual'));
    words.append(element('strong', look.title), element('small', look.detail));
    button.append(moneyBadge(name, big ? 46 : 40), words);
    if (big) button.append(Object.assign(element('span', '›', 'chevron'), {ariaHidden: 'true'}));
    button.setAttribute('aria-label', (big ? 'Your usual: ' : '') + look.title + ', ' + look.detail);
    button.addEventListener('click', () => form(name));
    return button;
  };

  function pick() {
    sheet.back = null;
    sheet.setTitle(request ? 'Request money' : 'Send money');
    const assets = allAssets();
    body.replaceChildren(kindSwitch(), element('h3', request ? 'How do you want to be paid?' : 'How are you paying?', 'money-question'),
      tile(defaultAsset(), true));
    for (const [title, test] of [['Crypto', (name) => !assets[name].currencies && !assets[name].custom],
      ['Payment apps and banks', (name) => !!assets[name].currencies], ['Added by you', (name) => assets[name].custom]]) {
      const names = Object.keys(assets).filter(test);
      if (!names.length) continue;
      const group = element('div', '', 'money-grid');
      group.setAttribute('role', 'group');
      group.setAttribute('aria-label', title);
      for (const name of names) group.append(tile(name, false));
      body.append(element('h4', title, 'group-title'), group);
    }
    const addOwn = element('button', '+ Add your own way to pay', 'wide');
    addOwn.type = 'button';
    addOwn.addEventListener('click', addCustom);
    body.append(addOwn, element('p', 'FluidMessages never holds money, keys, or card numbers. Payment links open the payer’s own app.', 'muted'));
    body.querySelector('.segmented-buttons [aria-checked="true"]')?.focus();
  }

  function form(name) {
    const assets = allAssets();
    const spec = assets[name];
    const look = moneyLook(name);
    sheet.back = pick;
    sheet.setTitle(request ? 'Request money' : 'Send money');
    const head = element('div', '', 'money-head');
    const words = element('div', '');
    words.append(element('h3', (request ? 'Request with ' : 'Pay with ') + look.title), element('p', look.detail, 'muted'));
    head.append(moneyBadge(name, 48), words);
    const box = element('form', '', 'stack');
    let currency = localStorage.getItem('money.currency.' + name) || (spec.currencies || [''])[0];
    if (!(spec.currencies || ['']).includes(currency)) currency = (spec.currencies || [''])[0];
    const unit = spec.currencies ? currency : name.split(' on ')[0].split(' via ')[0];
    const suffix = element('span', unit, 'amount-unit');
    if (spec.currencies && spec.currencies.length > 1) {
      const chips = element('fieldset', '', 'chips');
      chips.append(element('legend', 'Currency'));
      for (const option of spec.currencies) {
        const input = Object.assign(element('input', ''), {type: 'radio', name: 'currency', value: option, checked: option === currency});
        const chip = element('label', '', 'chip');
        chip.append(input, element('span', option));
        input.addEventListener('change', () => { currency = option; suffix.textContent = option; });
        chips.append(chip);
      }
      box.append(chips);
    }
    const amount = Object.assign(element('input', ''), {required: true, inputMode: 'decimal', autocomplete: 'off', placeholder: '0',
      pattern: '[0-9]+(\\.[0-9]{1,18})?', maxLength: 24});
    amount.addEventListener('input', () => { amount.value = amount.value.replace(/[^0-9.]/g, ''); });
    const amountRow = element('span', '', 'amount-row');
    amountRow.append(amount, suffix);
    const amountLabel = element('label', 'Amount', 'amount-field');
    amountLabel.append(amountRow);
    const label = spec.label || 'wallet address';
    const wallet = Object.assign(element('input', ''), {required: true, autocomplete: 'off', spellcheck: false,
      value: request ? localStorage.getItem('wallet:' + name) || '' : ''});
    const note = Object.assign(element('input', ''), {maxLength: 120, autocomplete: 'off'});
    box.append(amountLabel, field((request ? 'Your ' : 'Their ') + label, wallet, request ? 'Remembered in this browser for next time.' : ''),
      field('What it’s for (optional)', note));
    const status = element('p', '', 'error');
    status.setAttribute('role', 'alert');
    const build = () => {
      const address = wallet.value.trim();
      if (!/^[0-9]+(\.[0-9]{1,18})?$/.test(amount.value) || Number(amount.value) <= 0) throw new Error('Enter an amount');
      if (spec.currencies && /\.[0-9]{3,}$/.test(amount.value)) throw new Error('Use at most two decimal places');
      if (!spec.pattern.test(address)) throw new Error('That doesn’t look like a ' + label);
      return spec.uri(address, amount.value, note.value.trim(), currency || (spec.currencies || [''])[0]);
    };
    const tx = Object.assign(element('input', ''), {autocomplete: 'off', spellcheck: false});
    if (!request) {
      const open = element('button', 'Open ' + (spec.currencies ? look.title : 'your wallet') + ' to pay', 'wide');
      open.type = 'button';
      open.addEventListener('click', () => {
        try {
          const link = build();
          status.textContent = '';
          const anchor = Object.assign(element('a', ''), {href: link, rel: 'noopener noreferrer'});
          if (/^https:/.test(link)) anchor.target = '_blank';
          anchor.click();
        } catch (error) { status.textContent = error.message; }
      });
      box.append(open);
      if (!spec.currencies && spec.explorer) box.append(field('Transaction ID after paying (optional)', tx));
    }
    if (spec.custom) box.append(moneyWarning(true));
    box.append(element('p', spec.currencies ? 'The payment happens in ' + look.title + '. FluidMessages never holds money.'
      : 'Crypto payments can’t be reversed, so check the address.', 'muted'), status);
    const buttons = element('div', '', 'button-row end');
    const cancel = element('button', 'Cancel');
    cancel.type = 'button';
    cancel.addEventListener('click', () => sheet.close());
    buttons.append(cancel, element('button', request ? 'Send request' : 'Post that you paid', 'primary'));
    box.append(buttons);
    box.addEventListener('submit', async (event) => {
      event.preventDefault();
      try {
        const uri = build();
        const id = tx.value.trim();
        if (!request && id && !/^[A-Za-z0-9]{32,128}$/.test(id)) throw new Error('Enter the transaction ID only');
        const assetLabel = spec.currencies ? currency + ' ' + name : name;
        const memoLine = note.value.trim() ? 'For: ' + note.value.trim().replace(/\n/g, ' ') + '\n' : '';
        localStorage.setItem('money.default', name);
        if (spec.currencies) localStorage.setItem('money.currency.' + name, currency);
        let text;
        if (request) {
          localStorage.setItem('wallet:' + name, wallet.value.trim());
          text = '💸 Payment request: ' + amount.value + ' ' + assetLabel + '\n' + memoLine + 'Pay: ' + uri
            + '\nSent with FluidMessages · the link opens your own payment app';
        } else {
          text = '💸 Sent: ' + amount.value + ' ' + assetLabel + '\n' + memoLine + (id && spec.explorer ? 'Transaction: ' + spec.explorer + id : '');
        }
        sheet.close();
        await postToChat(text.trim());
      } catch (error) { status.textContent = error.message; }
    });
    body.replaceChildren(head, box);
    amount.focus();
  }

  function addCustom() {
    sheet.back = pick;
    sheet.setTitle('Add a way to pay');
    body.replaceChildren(customPayForm({done: (title) => form(title), cancel: pick}));
    body.querySelector('input')?.focus();
  }

  pick();
  sheet.showModal();
}

/**
 * The form that adds your own way to pay: a name and a payment link with {address}, {amount}, and {note}. Used in
 * Money (in a chat) and in Settings → Payments → Add a way to pay. [done] gets the new name.
 */
function customPayForm({done, cancel}) {
  const box = element('form', '', 'stack');
  const name = Object.assign(element('input', ''), {required: true, maxLength: 40, placeholder: 'DOGE on Dogecoin', autocomplete: 'off'});
  const template = Object.assign(element('input', ''), {required: true, maxLength: 300, placeholder: 'dogecoin:{address}?amount={amount}',
    autocomplete: 'off', spellcheck: false});
  const status = element('p', '', 'error');
  status.setAttribute('role', 'alert');
  const buttons = element('div', '', 'button-row end');
  const back = element('button', 'Cancel');
  back.type = 'button';
  back.addEventListener('click', cancel);
  buttons.append(back, element('button', 'Add', 'primary'));
  box.append(element('p', 'Any coin or service with a payment link works. Put {address}, {amount}, and {note} where they go.', 'muted'),
    field('Name', name), field('Payment link', template), moneyWarning(true), status, buttons);
  box.addEventListener('submit', (event) => {
    event.preventDefault();
    const link = template.value.trim();
    if (!/^[a-z][a-z0-9+.-]{1,30}:\S*\{address\}\S*$/.test(link) || /^(javascript|data|file|intent|content|vbscript|blob):/i.test(link)) {
      status.textContent = 'Enter a link like coin:{address}?amount={amount}';
      return;
    }
    const title = name.value.trim().slice(0, 40);
    if (BUILT_IN_ASSETS[title]) { status.textContent = 'That name is already in the list.'; return; }
    localStorage.setItem('money.custom', JSON.stringify(customAssets().filter((item) => item.name !== title).concat({name: title, template: link})));
    done(title);
  });
  return box;
}

// Wiring into the existing page

const HEAD_ICONS = {
  voice: 'M6.62 10.79a15.05 15.05 0 0 0 6.59 6.59l2.2-2.2c.27-.27.67-.36 1.02-.24 1.12.37 2.33.57 3.57.57.55 0 1 .45 1 1V20c0 .55-.45 1-1 1C10.61 21 3 13.39 3 4c0-.55.45-1 1-1h3.5c.55 0 1 .45 1 1 0 1.25.2 2.45.57 3.57.11.35.03.74-.25 1.02l-2.2 2.2z',
  video: 'M17 10.5V7a1 1 0 0 0-1-1H4a1 1 0 0 0-1 1v10a1 1 0 0 0 1 1h12a1 1 0 0 0 1-1v-3.5l4 4v-11l-4 4z',
  message: 'M20 2H4a2 2 0 0 0-2 2v18l4-4h14a2 2 0 0 0 2-2V4a2 2 0 0 0-2-2z',
  details: 'M12 2a10 10 0 1 0 10 10A10 10 0 0 0 12 2zm1 15h-2v-6h2zm0-8h-2V7h2z',
};

/** A round icon button for the chat header; its name shows on hover and is read by screen readers. */
function headButton(icon, label) {
  const button = element('button', '', 'head-button');
  button.type = 'button';
  button.title = label;
  button.setAttribute('aria-label', label);
  const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
  svg.setAttribute('viewBox', '0 0 24 24');
  svg.setAttribute('aria-hidden', 'true');
  const path = document.createElementNS('http://www.w3.org/2000/svg', 'path');
  path.setAttribute('d', HEAD_ICONS[icon]);
  path.setAttribute('fill', 'currentColor');
  svg.append(path);
  button.append(svg);
  return button;
}

(() => {
  const head = document.querySelector('.thread-head');
  // A handset for voice and a camera for video, here, in Calls, and in a chat's details.
  for (const [key, label, video] of [['voice', 'Voice call (Matrix)', false], ['video', 'Video call (Matrix)', true]]) {
    const button = headButton(key, label);
    button.hidden = true;
    button.addEventListener('click', () => startCall(video).catch(showError));
    callButtons[key] = button;
    head.append(button);
  }
  const details = headButton('details', 'Conversation details');
  details.id = 'details';
  details.hidden = true;
  details.addEventListener('click', () => openDetails().catch(showError));
  head.append(details);
  // Keep calls ringing while the app is open.
  new MutationObserver(() => {
    const shown = !get('workspace').hidden;
    if (shown && !companionSettings) loadSettings().catch(() => {});
    if (!shown && !signedIn) { companionSettings = null; callCursor = null; }
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
  // Leaving Settings leaves its address too, so Back returns to the Settings page you were on.
  if (next !== 'settings' && section === 'settings' && /^#settings/.test(location.hash)) history.pushState(null, '', location.pathname + location.search);
  // A contact or chat details page belongs to the section it was opened in.
  if (next !== section && typeof closeDetailsPage === 'function') closeDetailsPage(false);
  // Leaving Messages for another section locks a locked chat again (chats.js).
  if (next !== section && section === 'messages' && typeof leaveChatLock === 'function') leaveChatLock();
  section = next;
  document.body.dataset.section = next;
  for (const button of document.querySelectorAll('.rail button[data-section]')) {
    button.setAttribute('aria-current', String(button.dataset.section === next));
  }
  if (next === 'calls') showCalls();
}

// The Calls view, laid out like the phone's Phone tab: Favorites, Recents, Contacts, and the keypad (people.js).
function showCalls() {
  if (typeof showPhoneTab === 'function') showPhoneTab(get('calls-list'));
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
    if (key === 'settings') button.setAttribute('aria-label', 'Settings');
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

const MEETING_VIDEO_LABEL = 'Video call (sends a meeting link)';
const MEETING_VOICE_LABEL = 'Voice call (sends a meeting link)';

// Meeting-link calls (the call card, picking a service, and sending the link) are in meetings.js.

(() => {
  const button = headButton('video', MEETING_VIDEO_LABEL);
  button.hidden = true;
  button.addEventListener('click', () => startMeeting(true).catch(showError));
  document.getElementById('details').before(button);
  callButtons.meeting = button;
  updateCallButtons();
})();
