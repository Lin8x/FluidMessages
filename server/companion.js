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

  const preview = element('div', '', 'look-preview');
  preview.setAttribute('aria-label', 'Preview');
  preview.setAttribute('role', 'img');
  for (const [text, out] of [['Are we still on for Saturday?', false], ['Yes! I’ll bring the map 🗺️', true]]) {
    const row = element('div', '', out ? 'message out' : 'message');
    row.append(element('div', text, 'bubble'));
    preview.append(row);
  }

  const colorsBox = element('details', '', 'more');
  colorsBox.append(element('summary', 'Customize colors'));
  const colorGrid = element('div', '', 'color-grid');
  const colorInputs = {};
  for (const [key, label] of [['sms', 'Your texts'], ['incoming', 'Incoming'], ['automated', 'Automated texts'], ['rcs', 'RCS (preview)'],
    ['matrix', 'Matrix'], ['xmpp', 'XMPP'], ['email', 'Email'], ['irc', 'IRC']]) {
    const input = Object.assign(element('input', ''), {type: 'color'});
    input.addEventListener('input', () => saveWebLook({...webLook, colors: {...(webLook.colors || {}), [key]: input.value}}));
    colorInputs[key] = input;
    colorGrid.append(field(label, input));
  }
  const resetTheme = element('button', 'Reset to theme');
  resetTheme.type = 'button';
  resetTheme.addEventListener('click', () => {
    saveWebLook(themeLook(webLook, webLook.theme || 'fluid', webLook.activePalette), true);
    refreshAppearanceControls();
  });
  colorsBox.append(element('p', 'Colored bubbles always use white text; bright colors are shaded so it stays readable.', 'muted'), colorGrid, resetTheme);

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
    for (const input of themes.querySelectorAll('input')) {
      input.checked = input.value === (webLook.theme || 'fluid');
      input.parentElement.querySelector('.theme-swatch')?.replaceWith(themeSwatch(input.value));
    }
  }
  refreshAppearanceControls();
  // Saved palettes, wallpaper, inbox and toolbar, bubble tails, and motion and sound, as on the phone (look.js).
  const extras = typeof lookExtras === 'function' ? lookExtras({themes, colorsBox, bubbles, refresh: refreshAppearanceControls}) : {groups: [], feedback: null};
  controls.append(themes, mode, preview, colorsBox, ...extras.groups, bubbles);
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
  const open = (protocol, account) => {
    const back = element('button', '', 'sheet-back');
    back.type = 'button';
    back.append(element('span', '‹'), document.createTextNode(' Accounts'));
    back.addEventListener('click', again);
    list.replaceChildren(back, element('h3', (account ? '' : 'Add ') + labels[protocol] + (account ? ' · ' + account.name : ' account')),
      element('p', typeof SERVER_HELP === 'object' ? SERVER_HELP[protocol] : '', 'muted'), accountForm(protocol, account, again, {add: !account}));
    list.querySelector('input')?.focus();
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
    add.addEventListener('click', () => open(protocol, null));
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
    list.append(group);
  }
  return result;
}

/** An account's fields. [add] adds another account; with [account] it edits that one. [preset] pre-fills fields. */
function accountForm(protocol, account, done, {add = false, preset = {}} = {}) {
  const form = element('form', '', 'settings-section');
  const inputs = {};
  for (const [name, label, hint, secret] of ACCOUNT_FIELDS[protocol]) {
    const input = Object.assign(element('input', ''), {name, placeholder: hint, type: secret ? 'password' : 'text', autocomplete: secret ? 'new-password' : 'off',
      spellcheck: false});
    const current = account?.fields?.[name] ?? preset[name];
    if (current && !secret) input.value = Array.isArray(current) ? current.join(', ') : String(current);
    if (secret && current) input.placeholder = 'Saved · leave blank to keep';
    inputs[name] = input;
    form.append(field(label, input));
  }
  const status = element('p', '', 'muted');
  status.setAttribute('role', 'status');
  const save = element('button', 'Save', 'primary');
  form.append(element('p', 'Passwords are kept in a private file on your server and are never shown again.'
    + (protocol === 'irc' ? ' FluidMessages connects to IRC with TLS only.' : ''), 'muted'), save, status);
  form.addEventListener('submit', async (event) => {
    event.preventDefault();
    const fields = {};
    for (const [name, input] of Object.entries(inputs)) if (input.value.trim()) fields[name] = input.value.trim();
    save.disabled = true;
    try {
      await api('protocols/accounts', {protocol, fields, ...(account?.id ? {id: account.id} : add ? {add: true} : {})});
      done();
    } catch (error) { status.textContent = error.message; }
    finally { save.disabled = false; }
  });
  return form;
}

/** The account form in a sheet, for the setup guide. Without [account] it adds one. */
function editAccount(protocol, account, done) {
  const sheet = dialog(labels[protocol] + ' account');
  sheet.append(accountForm(protocol, account, () => { sheet.close(); done(); }, {add: !account}));
  sheet.showModal();
  sheet.querySelector('input')?.focus();
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
    checkbox('Link previews', current.linkPreviews, save('linkPreviews'), 'A card with the page’s title and picture. Your server fetches it, so the site never sees your browser.'));
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
    }
    if (plain) others.append(element('p', 'This page isn’t using HTTPS, so passwords and messages cross the network unencrypted. Put an HTTPS reverse proxy in front of it.', 'notice'));
  };
  phone.append(element('p', 'Loading…', 'muted'));
  api('auth/security').then(draw).catch((error) => { phone.replaceChildren(element('p', error.message, 'muted')); });
  return section;
}

/** Set or change the password, inline. [done] gets the new security state. */
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
      done(result);
    } catch (error) { status.textContent = error.message; }
    finally { save.disabled = false; }
  });
  requestAnimationFrame(() => (changing ? current : next).focus());
  return form;
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
    const steps = element('ol', '', 'steps');
    steps.append(element('li', 'Make FluidMessages reachable from your phone over HTTPS (a reverse proxy such as Caddy). See the self-hosting guide.'),
      element('li', 'On Android, open Settings → FluidMessages Web, enter this server’s HTTPS address and the pairing token, and save.'));
    if (current.pairing.web || current.pairing.compose || value) box.append(steps);
    box.append(token, actions);
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
  const same = (a, b) => String(a || '').toLowerCase() === String(b || '').toLowerCase();
  const existing = routes.find((route) => route.protocol === service && (!account || !route.account || route.account === account)
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
function openNewChat(prefill = '', {to = '', back} = {}) {
  const leave = back || (() => closeDetailsPage());
  const form = element('form', '', 'settings-section new-chat-form');
  form.noValidate = true;
  const target = Object.assign(element('input', ''), {autocomplete: 'off', spellcheck: false, value: to,
    placeholder: 'Name, phone number, or address'});
  target.setAttribute('autocapitalize', 'off');
  target.setAttribute('aria-describedby', 'new-chat-formats');
  const formats = element('small', 'Phone number · Matrix @name:server · XMPP or email name@server · IRC nickname, riley@irc.network, or #channel', 'hint');
  formats.id = 'new-chat-formats';
  const toField = element('label', 'To');
  toField.append(target, formats);
  const people = element('div', '', 'new-chat-people');
  const choices = element('fieldset', '', 'choices new-chat-choices');
  const account = element('select', '');
  const accountField = field('Account', account, 'You have more than one account on this service.');
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
    options = (typeof detectAddress === 'function' ? detectAddress(text) : []).filter((option) => option.service !== 'irc' || !option.guess || hasService('irc'));
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
    account.replaceChildren(Object.assign(element('option', 'Most recently used'), {value: ''}),
      ...mine.map((item) => Object.assign(element('option', item.name), {value: item.id, selected: item === preferred})));
    accountField.hidden = mine.length < 2;
    const chat = option && !['sms', 'sip'].includes(service);
    nameField.hidden = !chat || !!option.group;
    groupLabel.hidden = service !== 'xmpp';
    group.checked = !!option?.group;
    note.replaceChildren();
    start.textContent = !option ? 'Start chat' : service === 'sip' ? 'Open the keypad' : option.group ? 'Join and open' : 'Start chat';
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
    if (option.service === 'sms') {
      openThread(threadKey({address: option.target, protocol: 'sms'}));
      fill();
      return;
    }
    if (sampleOn) { status.textContent = 'Sample inbox · nothing was sent. Exit the sample inbox to start a chat.'; return; }
    start.disabled = true;
    try {
      const key = await startInternetChat({service: option.service, target: option.target, name: name.value.trim(),
        account: account.value, group: group.checked});
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
  } else if (threadAddress(key) && (!get('schedule').hidden || sampleOn)) {
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
  const row = element('div', '', 'detail-row');
  const words = element('span', '', 'detail-words');
  words.append(element('strong', label), element('small', protocolName(protocol) + ' · ' + detail));
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

/** Shown wherever disappearing messages can be turned on for texts. */
const CARRIER_COPY_NOTE = 'Your carrier and the other person’s phone can still keep a copy of texts. Disappearing only removes them from FluidMessages.';

/** A chat's own settings, also shown on the person's contact page (people.js): photos and files, wallpaper,
 * folders, security and its timer, and its code. [settings] is null in the sample chats. */
function conversationSettings(key, settings) {
  const address = threadAddress(key);
  const extras = detailsExtras(key);
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
      security.append(element('p', 'Your phone’s timers replace this when it syncs. Change it on the phone, or choose '
        + '“This web app’s own” in Settings → Phone and other devices.', 'muted'));
    }
  } else if (sampleOn) {
    security.append(element('p', 'Disappearing messages are set per chat in your real chats’ details. Nothing in the sample inbox is saved.', 'muted'));
  }
  nodes.push(security, extras.code);
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
  // Matrix chats call natively; everywhere else the camera button sends a meeting link.
  if (callButtons.meeting) callButtons.meeting.hidden = !active || !threadAddress(active) || matrix;
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
    const box = element('form', '', 'stack');
    const name = Object.assign(element('input', ''), {required: true, maxLength: 40, placeholder: 'DOGE on Dogecoin', autocomplete: 'off'});
    const template = Object.assign(element('input', ''), {required: true, maxLength: 300, placeholder: 'dogecoin:{address}?amount={amount}',
      autocomplete: 'off', spellcheck: false});
    const status = element('p', '', 'error');
    status.setAttribute('role', 'alert');
    const buttons = element('div', '', 'button-row end');
    const cancel = element('button', 'Cancel');
    cancel.type = 'button';
    cancel.addEventListener('click', pick);
    buttons.append(cancel, element('button', 'Add', 'primary'));
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
      form(title);
    });
    body.replaceChildren(box);
    name.focus();
  }

  pick();
  sheet.showModal();
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

// Meeting-link calls: sent as plain text so every app can open them, shown here as a call card with Join.
const MEETING = /^(?:📹|📞)?\s*Join my (video )?call: (https:\/\/[A-Za-z0-9.-]+(?::\d+)?\/[A-Za-z0-9_-]+)\s*$/;

function meetingPreview(body) {
  const match = MEETING.exec(body.trim());
  return match ? (match[1] ? '📹 Video call · tap to join' : '📞 Call · tap to join') : null;
}

function meetingCard(message, bubble) {
  const match = MEETING.exec((message.body || '').trim());
  if (!match) return false;
  const [, video, link] = match;
  const card = element('div', '', 'meeting-card');
  const icon = element('span', video ? '📹' : '📞', 'meeting-icon');
  const text = element('span', '', 'meeting-text');
  text.append(element('strong', video ? 'Video call' : 'Voice call'),
    element('small', (message.type === 1 ? 'Started a call' : 'You started a call') + ' · ' + new URL(link).host));
  const join = element('a', 'Join', 'meeting-join');
  join.href = video ? link : link + '#config.startWithVideoMuted=true';
  join.target = '_blank';
  join.rel = 'noopener noreferrer';
  card.append(icon, text, join);
  bubble.replaceChildren(card);
  return true;
}

// Starts a meeting-link call from the browser: the link goes to this chat, then the meeting opens.
async function startMeeting(video) {
  if (!active) return;
  const room = 'FluidMessages-' + fluidCallId().slice(0, 16);
  const link = 'https://meet.jit.si/' + room;
  const body = (video ? '📹 Join my video call: ' : '📞 Join my call: ') + link;
  window.open(video ? link : link + '#config.startWithVideoMuted=true', '_blank', 'noopener');
  if (internet(threadProtocol(active))) await send(body, body, null);
  else await queueSms(body, threadAddress(active), Date.now());
}

(() => {
  const button = headButton('video', MEETING_VIDEO_LABEL);
  button.hidden = true;
  button.addEventListener('click', () => startMeeting(true).catch(showError));
  document.getElementById('details').before(button);
  callButtons.meeting = button;
  updateCallButtons();
})();
