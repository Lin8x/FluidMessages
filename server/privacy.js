'use strict';
// Privacy and security, like the phone's (MainActivity.privacySettings, SecurityDialogs): the private folder,
// decoy mode, and the emergency erase passcode, adapted to a browser. The server does the hiding (privacy.py):
// until this session opens the private folder, its chats never reach this page, and a decoy session only ever
// gets pretend chats. This file draws the inbox filter's Private folder and Quick hide, the passcode prompts in the
// chat list, the Move to private folder choices, and the three pages in Settings → Privacy and security.
// Loaded after settings.js; uses the helpers in app.js, companion.js, chats.js, and settings.js.

const PRIVATE_FOLDER = 'Private folder';
const QUICK_HIDE = 'Quick hide (decoy mode)';

Object.assign(SETTINGS_ICONS, {
  folder: 'M10 4H4c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V8c0-1.1-.9-2-2-2h-8l-2-2zm2 6a2 2 0 0 1 2 2v1h.5c.28 0 .5.22.5.5v3c0 .28-.22.5-.5.5h-5a.5.5 0 0 1-.5-.5v-3c0-.28.22-.5.5-.5h.5v-1a2 2 0 0 1 2-2zm0 1a1 1 0 0 0-1 1v1h2v-1a1 1 0 0 0-1-1z',
  mask: 'M12 4C6 4 2 6.5 2 9.5c0 4.5 3 9.5 6.5 9.5 1.7 0 2.5-1.5 3.5-1.5s1.8 1.5 3.5 1.5C19 19 22 14 22 9.5 22 6.5 18 4 12 4zM7.5 12.5c-1.1 0-2-.7-2-1.5s.9-1.5 2-1.5 2 .7 2 1.5-.9 1.5-2 1.5zm9 0c-1.1 0-2-.7-2-1.5s.9-1.5 2-1.5 2 .7 2 1.5-.9 1.5-2 1.5z',
  alarm: 'M12 2a10 10 0 1 0 0 20 10 10 0 0 0 0-20zm1 15h-2v-2h2v2zm0-4h-2V7h2v6z',
});
SETTINGS_SUBPAGES.push(
  {id: 'private-folder', icon: 'folder', title: 'Private folder', parent: 'privacy', page: (settings, context) => privateFolderPage(context)},
  {id: 'private-services', icon: 'folder', title: 'Hide whole services', parent: 'private-folder', page: (settings, context) => privateServicesPage(context)},
  {id: 'decoy-mode', icon: 'mask', title: 'Decoy mode', parent: 'privacy', page: (settings, context) => decoyModePage(context)},
  {id: 'decoy-allowed', icon: 'mask', title: 'Real chats in decoy mode', parent: 'decoy-mode', page: (settings, context) => decoyAllowedPage(context)},
  {id: 'emergency-erase', icon: 'alarm', title: 'Emergency erase passcode', parent: 'privacy', page: (settings, context) => emergencyPage(context)});

let privacyStatus = null;
let privacySession = -1;
let privateChats = [];
// The chat list's prompt: '' (none), 'wrong' (an empty folder after a passcode that opened nothing), or 'reveal'.
let privateGateMode = '';
const privateGate = {form: null, reveal: null, close: null, closeTo: ''};

async function loadPrivacy() {
  privacyStatus = await api('privacy');
  privacySession = session;
  syncFolderOptions();
  return privacyStatus;
}

/** A decoy session: the server leaves the private folder and decoy mode out of its status, like the phone in decoy mode. */
const decoyLike = () => !!privacyStatus && privacyStatus.private === null;
const privateOpen = () => !!privacyStatus?.private?.open;

/** Whether a chat is in the private folder. Only known while this session has it open (app.js showThreads). */
function privateFolderHas(address) {
  return !sampleOn && privateChats.includes(address);
}

// Each snapshot says which chats are private, but only once this session opened the folder with its passcode.
{
  const noticeBase = noticeSnapshot;
  noticeSnapshot = function noticeWithPrivate(snapshot) {
    privateChats = Array.isArray(snapshot.private_chats) ? snapshot.private_chats : [];
    if (privacySession !== session) { privacySession = session; loadPrivacy().then(() => showThreads()).catch(() => {}); }
    noticeBase(snapshot);
  };
}
// New messages in private chats never chime, even while the folder is open.
if (typeof lookNoticeIncoming === 'function') {
  const chimeBase = lookNoticeIncoming;
  lookNoticeIncoming = (messages) => chimeBase((messages || []).map((message) => privateFolderHas(message.address) ? {...message, type: 2} : message));
}
// Signing out forgets what this session had open.
{
  const signOutBase = signOutHere;
  signOutHere = function signOutAndForget(message) {
    privacyStatus = null;
    privateChats = [];
    privateGateMode = '';
    privateGate.form = privateGate.reveal = null;
    return signOutBase(message);
  };
}

/** The inbox filter: Private folder in every inbox, set up or not, so its presence gives nothing away (as on the
 * phone), and Quick hide once the private folder is set up. Neither in the sample inbox. */
function syncFolderOptions() {
  const select = get('folder');
  for (const [label, shown] of [[PRIVATE_FOLDER, !sampleOn], [QUICK_HIDE, !sampleOn && !!privacyStatus?.quick_hide]]) {
    let option = [...select.options].find((item) => item.value === label);
    if (!option && shown) { option = element('option', label); select.append(option); }
    if (option && !shown) { if (select.value === label) select.value = 'Inbox'; option.remove(); }
  }
}

/** Clears the chat on screen, for switching between the real and the decoy inbox. */
function resetChatView() {
  if (typeof closeDetailsPage === 'function') closeDetailsPage(false);
  active = '';
  delete get('workspace').dataset.thread;
  get('schedule').hidden = true;
  get('recipient').textContent = 'Chats';
  get('transport').textContent = '';
  get('avatar').textContent = '';
  get('folder').value = 'Inbox';
  get('search').value = '';
  privateGateMode = '';
  privateGate.form = privateGate.reveal = null;
  refreshed = false;
  lastLook = '';
}

async function reloadInbox() {
  await loadPrivacy().catch(() => {});
  await refresh();
  showThreads();
  showMessages();
}

const inPrivateFolder = (value) => value === PRIVATE_FOLDER || String(value || '').startsWith('private:');
let folderBefore = get('folder').value;

get('folder').addEventListener('change', () => {
  const folder = get('folder').value;
  const before = folderBefore;
  folderBefore = folder;
  if (folder === QUICK_HIDE) { quickHide().catch(showError); return; }
  // Leaving the open private folder asks for the passcode again, then locks it, as on the phone (Ask for the
  // passcode to close it). Until then the folder stays on screen.
  if (inPrivateFolder(before) && !inPrivateFolder(folder) && privateOpen() && !decoyLike() && privacyStatus?.private?.close_code !== false) {
    get('folder').value = folderBefore = before;
    privateGateMode = 'close';
    privateGate.close = null;
    privateGate.closeTo = folder;
    showThreads();
    return;
  }
  // Filters inside the open private folder (inbox-filters.js) stay in it.
  if (!inPrivateFolder(folder)) privateGateMode = privateGateMode === 'reveal' ? 'reveal' : '';
});

/** Close the private folder: its passcode, then it locks and the folder you picked opens. Cancel stays in the folder. */
function privateCloseForm() {
  const form = passcodeForm('Close the private folder', 'Enter your passcode to close it.', async (code, status) => {
    const result = await api('privacy/private/unlock', {code});
    if (!result.open) { status.textContent = 'That passcode doesn’t match'; return; }
    const to = privateGate.closeTo || 'Inbox';
    await api('privacy/private/lock', {});
    resetChatView();
    await reloadInbox();
    get('folder').value = folderBefore = [...get('folder').options].some((option) => option.value === to) ? to : 'Inbox';
    showThreads();
    say('The private folder is locked');
  }, {label: 'Close', back: () => { privateGateMode = ''; privateGate.close = null; showThreads(); }});
  return form;
}

/** Quick hide: this browser switches to the decoy inbox at once. The private folder closes. */
async function quickHide() {
  await api('privacy/quick-hide', {});
  resetChatView();
  if (typeof showSection === 'function') showSection('messages');
  await reloadInbox();
}

function gateIcon() {
  const icon = element('span', '', 'private-gate-icon');
  icon.append(settingsIcon('folder'));
  return icon;
}

function secretInput(label, autocomplete = 'off') {
  const input = Object.assign(element('input', ''), {type: 'password', autocomplete, maxLength: 1024, spellcheck: false});
  input.setAttribute('aria-label', label);
  return input;
}

/** The passcode prompt in the chat list: Private folder, or Show your real inbox in decoy mode. */
function passcodeForm(title, intro, submit, {label = 'Open', back = null} = {}) {
  const form = element('form', '', 'private-gate');
  form.append(gateIcon(), element('h2', title), element('p', intro, 'muted'));
  const input = secretInput('Passcode', 'current-password');
  input.required = true;
  const status = element('p', '', 'error');
  status.setAttribute('role', 'alert');
  const open = element('button', label, 'primary');
  const cancel = element('button', 'Cancel', 'quiet');
  cancel.type = 'button';
  cancel.addEventListener('click', back || (() => {
    privateGateMode = '';
    privateGate.form = privateGate.reveal = null;
    get('folder').value = 'Inbox';
    showThreads();
  }));
  const row = element('div', '', 'button-row');
  row.append(open, cancel);
  form.append(field('Passcode', input), status, row);
  form.addEventListener('submit', async (event) => {
    event.preventDefault();
    open.disabled = true;
    status.textContent = '';
    open.textContent = 'Checking…';
    try { await submit(input.value, status); } catch (error) { status.textContent = error.message; }
    finally { open.disabled = false; open.textContent = label; input.value = ''; }
  });
  form.focusFirst = () => requestAnimationFrame(() => input.focus());
  return form;
}

function privateUnlockForm() {
  return passcodeForm(PRIVATE_FOLDER, 'Enter your passcode to open it.', async (code) => {
    if (decoyLike()) {
      // In decoy mode the private folder passcode shows your real inbox again, with the folder still closed.
      const response = await fetch('/api/privacy/reveal', {method: 'POST', credentials: 'same-origin',
        headers: {...REQUEST_HEADERS, 'Content-Type': 'application/json'}, body: JSON.stringify({code})});
      if (response.status === 429) throw new Error((await response.json()).error);
      if (response.ok) { resetChatView(); await reloadInbox(); say('Decoy mode is off'); return; }
      privateGateMode = 'wrong';
      showThreads();
      return;
    }
    const result = await api('privacy/private/unlock', {code});
    // A wrong passcode opens an empty folder rather than an error, so nobody can tell whether anything is hidden.
    privateGateMode = result.open ? '' : 'wrong';
    privateGate.form = null;
    if (result.open) { await reloadInbox(); say('The private folder is open until you lock it or sign out'); }
    else showThreads();
  });
}

function revealForm() {
  return passcodeForm('Show your real inbox', 'Enter your private folder passcode.', async (code, status) => {
    const response = await fetch('/api/privacy/reveal', {method: 'POST', credentials: 'same-origin',
      headers: {...REQUEST_HEADERS, 'Content-Type': 'application/json'}, body: JSON.stringify({code})});
    let result = {};
    try { result = await response.json(); } catch (error) { /* not JSON */ }
    if (!response.ok) { status.textContent = result.error || 'That passcode doesn’t match'; return; }
    resetChatView();
    await reloadInbox();
    say('Decoy mode is off');
  });
}

function privateSetupPrompt() {
  const box = element('div', '', 'private-gate');
  box.append(gateIcon(), element('h2', PRIVATE_FOLDER),
    element('p', 'Move private chats here so they never show in your inbox. Only you can open this folder, with a passcode.', 'muted'));
  const row = element('div', '', 'button-row');
  const setUp = element('button', 'Set up', 'primary');
  setUp.type = 'button';
  setUp.addEventListener('click', () => openSettings('private-folder').catch(showError));
  const later = element('button', 'Not now', 'quiet');
  later.type = 'button';
  later.addEventListener('click', () => { get('folder').value = 'Inbox'; showThreads(); });
  row.append(setUp, later);
  box.append(row);
  return box;
}

function privateOpenBar() {
  const bar = element('div', '', 'private-bar');
  bar.setAttribute('role', 'status');
  const words = element('span', '');
  words.append(element('strong', 'Private folder · open'), element('small', 'Its chats stay out of your inbox, search, and chimes.'));
  const lock = element('button', 'Lock', 'quiet');
  lock.type = 'button';
  lock.addEventListener('click', async () => {
    lock.disabled = true;
    try {
      await api('privacy/private/lock', {});
      resetChatView();
      await reloadInbox();
      say('The private folder is locked');
    } catch (error) { showError(error); lock.disabled = false; }
  });
  bar.append(words, lock);
  return bar;
}

/**
 * Called by showThreads (app.js) before it lists chats. Draws the passcode prompt, the empty folder after a wrong
 * passcode, or the open folder's bar. Returns true when the list is drawn here instead.
 */
function privateFolderGate(list, folder) {
  syncFolderOptions();
  if (privateGateMode === 'reveal') {
    privateGate.reveal ||= revealForm();
    list.append(privateGate.reveal);
    privateGate.reveal.focusFirst();
    return true;
  }
  if (folder !== PRIVATE_FOLDER || sampleOn) return false;
  if (privateGateMode === 'close' && privateOpen() && !decoyLike()) {
    if (!privateGate.close) { privateGate.close = privateCloseForm(); privateGate.close.focusFirst(); }
    list.append(privateGate.close);
    return true;
  }
  if (privateOpen() && !decoyLike()) { list.append(privateOpenBar()); return false; }
  if (privateGateMode === 'wrong') {
    const empty = element('div', '', 'empty-list');
    empty.append(element('strong', 'Nothing in the private folder'));
    const again = element('button', 'Enter passcode', 'quiet');
    again.type = 'button';
    again.addEventListener('click', () => { privateGateMode = ''; showThreads(); });
    empty.append(again);
    list.append(empty);
    return true;
  }
  if (privacyStatus && !decoyLike() && !privacyStatus.private.set) { list.append(privateSetupPrompt()); return true; }
  if (!privateGate.form) { privateGate.form = privateUnlockForm(); privateGate.form.focusFirst(); }
  list.append(privateGate.form);
  return true;
}

// Decoy mode only: touching and holding the Chats title asks for the passcode to show the real inbox. Nothing on
// screen hints at it, as on the phone.
{
  const title = document.querySelector('.sidebar-head h1');
  let timer = 0;
  const cancel = () => clearTimeout(timer);
  title?.addEventListener('pointerdown', () => {
    cancel();
    if (!decoyLike()) return;
    timer = setTimeout(() => { privateGateMode = 'reveal'; privateGate.reveal = null; get('search').value = ''; showThreads(); }, 700);
  });
  for (const name of ['pointerup', 'pointerleave', 'pointercancel']) title?.addEventListener(name, cancel);
}

// Moving chats in and out (the inbox's choices row and a chat's details)

async function movePrivate(address, hide) {
  await api('privacy/private/move', {address, private: hide});
  const name = threadTitleFor(address);
  if (hide && !privateOpen()) resetChatView();
  await reloadInbox();
  say(hide ? name + ' moved to the private folder' : name + ' is back in the inbox');
}

/** "Move to private folder" for a chat, when the folder is set up (chats.js threadMenu). */
function privateMenuItem(key, row) {
  if (sampleOn || decoyLike() || !privacyStatus?.private?.set) return null;
  const address = threadAddress(key);
  const inside = privateFolderHas(address);
  const button = element('button', inside ? 'Move out of private folder' : 'Move to private folder');
  button.type = 'button';
  button.addEventListener('click', () => {
    if (inside) { row.remove(); movePrivate(address, false).catch(showError); return; }
    // Asks first, in the same row, the way the phone asks before moving a chat.
    row.replaceChildren();
    const words = element('p', 'Move ' + threadTitle(key) + ' to the private folder? It leaves your inbox and is only in the private folder, '
      + 'which opens with your passcode. New messages from them don’t chime.', 'private-confirm');
    const move = element('button', 'Move', 'primary');
    move.type = 'button';
    move.addEventListener('click', () => { row.remove(); movePrivate(address, true).catch(showError); });
    const cancel = element('button', 'Cancel');
    cancel.type = 'button';
    cancel.addEventListener('click', () => row.remove());
    row.append(words, move, cancel);
    move.focus();
  });
  return button;
}

/** The same choice in a chat's details (chats.js detailsExtras). */
function privateDetailsButton(address) {
  if (sampleOn || decoyLike() || !privacyStatus?.private?.set) return null;
  const inside = privateFolderHas(address);
  const button = element('button', inside ? 'Move out of private folder' : 'Move to private folder');
  button.type = 'button';
  button.addEventListener('click', () => movePrivate(address, !inside).catch(showError));
  return button;
}

// Settings → Privacy and security

function statusLine() {
  const line = element('p', '', 'muted');
  line.setAttribute('role', 'status');
  return line;
}

/** The phone's groups on Privacy and security: Protect the app, and Keep things private (left out in decoy mode). */
async function privacySections() {
  const status = await loadPrivacy();
  const protect = settingsCard('Protect the app');
  protect.append(settingsLink('emergency-erase', status.distress.set ? 'On · change or turn off'
    : 'A second passcode that erases everything if you’re ever forced to unlock'));
  if (!status.private) return [protect];
  // In decoy mode these stay out of sight, so someone looking can't tell anything is hidden.
  const keep = settingsCard('Keep things private');
  keep.append(settingsLink('private-folder', status.private.set ? 'Set up · open from the inbox filter'
    : 'Keep chats out of your inbox, behind a passcode'),
  settingsLink('decoy-mode', (status.decoy.set ? 'On · ' : '') + 'If someone else is using your browser, show only pretend chats'));
  return [protect, keep];
}

/** A new passcode typed twice, as on the phone (SecurityDialogs.code). */
function newCodeFields(label, min) {
  const code = secretInput(label, 'new-password');
  const again = secretInput('Type it again', 'new-password');
  code.required = again.required = true;
  code.minLength = min;
  return {code, again, nodes: [field(label, code, 'Use at least ' + min + ' characters.'), field('Type it again', again)],
    check() {
      if (code.value.length < min) throw new Error('Use at least ' + min + ' characters');
      if (code.value !== again.value) throw new Error('The passcodes don’t match');
    }};
}

function passwordField(status, label = 'Your FluidMessages Web password') {
  const input = secretInput(label, 'current-password');
  if (!status.password) return {input, nodes: []};
  input.required = true;
  return {input, nodes: [Object.assign(element('input', ''), {type: 'text', name: 'username', autocomplete: 'username', value: 'FluidMessages Web', hidden: true}),
    field(label, input)]};
}

function settingsForm(button, run, problem) {
  const form = element('form', '', 'stack');
  form.addEventListener('submit', async (event) => {
    event.preventDefault();
    button.disabled = true;
    problem.textContent = '';
    try { await run(); } catch (error) { problem.textContent = error.message; }
    finally { button.disabled = false; }
  });
  return form;
}

function errorLine() {
  const line = element('p', '', 'error');
  line.setAttribute('role', 'alert');
  return line;
}

function howItWorks(title, text) {
  const card = settingsCard('How it works');
  card.append(element('strong', title, 'privacy-lead'), element('p', text, 'muted'));
  return card;
}

async function privateFolderPage(context) {
  const status = await loadPrivacy();
  const cards = [howItWorks('Chats in the private folder stay out of your inbox', 'They’re only in the private folder. Open it from the filter '
    + 'at the top of the inbox with your passcode. It stays open in this browser until you lock it, leave it for another folder (when it asks for the '
    + 'passcode to close it), or sign out. While it’s closed, its chats '
    + 'never reach this browser: not the inbox, search, chimes, or the Scheduled list. Your phone’s own private folder stays on your phone.')];
  if (!status.private) return cards;
  if (!status.private.set) {
    const card = settingsCard('Set up the private folder', 'To show the private folder, choose Private folder in the inbox filter and type this '
      + 'passcode. Pick something you wouldn’t search for. It’s different from your FluidMessages Web password.');
    const fields = newCodeFields('Passcode', 4);
    const problem = errorLine();
    const save = element('button', 'Save', 'primary');
    const form = settingsForm(save, async () => {
      fields.check();
      await api('privacy/private/setup', {code: fields.code.value, again: fields.again.value});
      await reloadInbox();
      say('The private folder is ready');
      context.rerender();
    }, problem);
    form.append(...fields.nodes, problem, save);
    card.append(form);
    return [...cards, card];
  }
  if (!status.private.open) {
    const card = settingsCard('The private folder is closed', 'Open it to change these settings.');
    const input = secretInput('Passcode', 'current-password');
    input.required = true;
    const problem = errorLine();
    const open = element('button', 'Open the private folder', 'primary');
    const form = settingsForm(open, async () => {
      const result = await api('privacy/private/unlock', {code: input.value});
      input.value = '';
      if (!result.open) throw new Error('That passcode doesn’t match');
      await reloadInbox();
      context.rerender();
    }, problem);
    form.append(field('Passcode', input), problem, open);
    card.append(form);
    return [...cards, card];
  }
  const opening = settingsCard('Opening');
  const lock = element('button', 'Lock the private folder now');
  lock.type = 'button';
  lock.addEventListener('click', async () => {
    await api('privacy/private/lock', {}).catch(showError);
    resetChatView();
    await reloadInbox();
    context.rerender();
  });
  const fields = newCodeFields('New passcode', 4);
  const problem = errorLine();
  const done = statusLine();
  const change = element('button', 'Change passcode');
  const form = settingsForm(change, async () => {
    fields.check();
    await api('privacy/private/setup', {code: fields.code.value, again: fields.again.value});
    form.reset();
    done.textContent = 'Passcode saved';
  }, problem);
  form.append(element('h4', 'Change passcode'), ...fields.nodes, problem, change, done);
  const closing = checkbox('Ask for the passcode to close it', status.private.close_code !== false, async (on) => {
    try { await api('privacy/private/close-code', {on}); await loadPrivacy(); } catch (error) { showError(error); }
  }, 'Leaving the private folder for another folder asks again, then locks it.');
  opening.append(element('p', 'Open in this browser.', 'muted'), lock, closing, form);
  const hidden = settingsCard('What’s hidden');
  const count = status.private.count || 0;
  hidden.append(element('p', count ? count + (count === 1 ? ' chat is' : ' chats are') + ' in the private folder.' : 'No chats in the private folder yet.', 'muted'));
  hidden.append(settingsLink('private-services', hiddenServicesLabel(status.private.services)));
  const facts = element('dl', '', 'settings-facts');
  facts.append(element('dt', 'Hide one chat'), element('dd', 'Right-click or touch and hold it in the inbox, or open its details, and choose Move to private folder.'),
    element('dt', 'Show it again'), element('dd', 'Open the private folder, then choose Move out of private folder the same way.'));
  hidden.append(facts);
  const end = settingsCard('Done with the private folder?');
  end.append(element('p', 'Moves everything back to your inbox and deletes the passcode.', 'muted'),
    confirmButton('Turn off the private folder', 'Tap again to turn it off', async () => {
      try { await api('privacy/private/off', {}); } catch (error) { showError(error); return; }
      resetChatView();
      await reloadInbox();
      context.rerender();
    }));
  return [...cards, opening, hidden, end];
}

const SERVICE_NAMES = {sms: 'Texts', matrix: 'Matrix', xmpp: 'XMPP', email: 'Email', irc: 'IRC'};

function hiddenServicesLabel(services) {
  return services?.length ? services.map((name) => SERVICE_NAMES[name]).join(', ') : 'None';
}

/** Private folder → Hide whole services: every chat on a service goes in the private folder, as on the phone. */
async function privateServicesPage(context) {
  const status = await loadPrivacy();
  const card = settingsCard('Hide whole services', 'Every chat on a service you choose is in the private folder, new ones too: '
    + 'it stays out of your inbox, search, chimes, and the Scheduled list until you open the folder.');
  if (!status.private?.set || !status.private.open) {
    card.append(element('p', 'Open the private folder first.', 'notice'), settingsLink('private-folder', 'Open it with your passcode'));
    return [card];
  }
  const chosen = new Set(status.private.services || []);
  const problem = errorLine();
  const save = element('button', 'Save', 'primary');
  const form = settingsForm(save, async () => {
    await api('privacy/private/services', {services: [...chosen]});
    await reloadInbox();
    say(chosen.size ? hiddenServicesLabel([...chosen]) + ' hidden in the private folder' : 'No services hidden');
    settingsBack(settingsItem('private-services'));
  }, problem);
  const set = element('fieldset', '', 'stack');
  set.append(element('legend', 'Services', 'visually-hidden'));
  for (const [name, label] of Object.entries(SERVICE_NAMES)) {
    set.append(checkbox(label, chosen.has(name), (on) => { if (on) chosen.add(name); else chosen.delete(name); },
      name === 'sms' ? 'Texts from your phone: SMS, MMS, and RCS (preview)' : 'Every ' + label + ' chat and room'));
  }
  form.append(set, problem, save);
  card.append(form);
  return [card];
}

/** Decoy mode → Real chats that stay visible: real chats that look normal to someone else. */
async function decoyAllowedPage(context) {
  const status = await loadPrivacy();
  const card = settingsCard('Real chats in decoy mode', 'These stay visible in decoy mode. Choose chats that would look normal to someone else. '
    + 'Anything sent in decoy mode stays on that page, in these chats too.');
  if (!status.decoy) return [card];
  const chosen = new Set(status.decoy.allowed || []);
  // Real chats only: never the sample inbox or a private chat, and up to 60, newest first.
  const keys = [...threads.keys()].filter((key) => threads.get(key)?.length && !privateFolderHas(threadAddress(key)))
    .sort((a, b) => timeOf(threads.get(b).at(-1)) - timeOf(threads.get(a).at(-1))).slice(0, 60);
  const seen = new Set();
  const set = element('fieldset', '', 'stack');
  set.append(element('legend', 'Chats', 'visually-hidden'));
  for (const key of keys) {
    const address = threadAddress(key);
    if (seen.has(address)) continue;
    seen.add(address);
    set.append(checkbox(threadTitle(key), chosen.has(address), (on) => { if (on) chosen.add(address); else chosen.delete(address); },
      protocolName(threadProtocol(key))));
  }
  if (sampleOn) set.replaceChildren(element('p', 'Leave the sample inbox to choose your real chats.', 'notice'));
  else if (!seen.size) set.append(element('p', 'No chats yet.', 'muted'));
  const problem = errorLine();
  const save = element('button', 'Save', 'primary');
  save.disabled = sampleOn;
  const form = settingsForm(save, async () => {
    await api('privacy/decoy/allowed', {addresses: [...chosen].slice(0, 60)});
    say(chosen.size ? chosen.size + (chosen.size === 1 ? ' real chat stays' : ' real chats stay') + ' visible in decoy mode' : 'Only pretend chats show in decoy mode');
    settingsBack(settingsItem('decoy-allowed'));
  }, problem);
  form.append(set, problem, save);
  card.append(form);
  return [card];
}

async function decoyModePage(context) {
  const status = await loadPrivacy();
  const cards = [howItWorks('For when someone else is using your browser', 'Decoy mode swaps your inbox for pretend chats, plus only the real '
    + 'chats you allow. Nothing else real shows: no other messages, accounts, settings, pairing, or erase, and nothing sent there leaves the page. Nothing is deleted. Sign in '
    + 'with your decoy password to open it, or choose Quick hide (decoy mode) in the inbox filter. To end it, touch and hold the Chats title '
    + 'and enter your private folder passcode, or sign out and sign in with your real password.')];
  if (!status.decoy) return cards;
  const password = settingsCard('Decoy password', status.decoy.set ? 'On. Signing in with it opens the decoy inbox.'
    : 'A second password for the sign-in page. It opens the decoy inbox, and the sign-in looks and takes just as long as your real one.');
  if (!status.password) {
    password.append(element('p', 'Decoy mode works with a sign-in password. On this computer FluidMessages Web opens without one, so it always '
      + 'shows your real inbox here. Set a password in Phone and other devices first.', 'notice'), settingsLink('devices', 'Password and sign-ins'));
  } else {
    const fields = newCodeFields(status.decoy.set ? 'New decoy password' : 'Decoy password', 8);
    const current = passwordField(status);
    const problem = errorLine();
    const save = element('button', status.decoy.set ? 'Change decoy password' : 'Save decoy password', 'primary');
    const form = settingsForm(save, async () => {
      fields.check();
      await api('privacy/decoy', {password: fields.code.value, again: fields.again.value, current: current.input.value});
      say('Decoy password saved');
      context.rerender();
    }, problem);
    form.append(...fields.nodes, ...current.nodes, problem, save);
    password.append(form);
    if (status.decoy.set) {
      const off = passwordField(status);
      const offProblem = errorLine();
      const turnOff = element('button', 'Turn off decoy password');
      const offForm = settingsForm(turnOff, async () => {
        await api('privacy/decoy/off', {current: off.input.value});
        context.rerender();
      }, offProblem);
      offForm.append(element('h4', 'Turn off'), ...off.nodes, offProblem, turnOff);
      password.append(offForm);
    }
  }
  const turnOn = settingsCard('Turn on');
  if (status.private.set) {
    const now = element('button', 'Turn on decoy mode now');
    now.type = 'button';
    now.addEventListener('click', () => quickHide().catch(showError));
    turnOn.append(element('p', 'Shows the decoy inbox in this browser right away. Quick hide in the inbox filter does the same.', 'muted'), now);
  } else {
    turnOn.append(settingsLink('private-folder', 'Decoy mode ends with your private folder passcode, so set one up'));
    turnOn.querySelector('strong').textContent = 'Set up the private folder first';
  }
  return [...cards, password, turnOn, decoyChatsCard(status.decoy.chats, context, status.decoy.allowed || [])];
}

function decoyChatsCard(chats, context, allowed = []) {
  const card = settingsCard('What decoy mode shows', 'Pretend chats, plus only the real chats you allow. Pretend chats look like normal texts, and nothing is ever sent.');
  card.append(settingsLink('decoy-allowed', allowed.length ? allowed.length + ' chosen' : 'None · only pretend chats show'));
  card.querySelector('.settings-row:last-child strong').textContent = 'Real chats that stay visible';
  const list = element('div', '', 'settings-rows');
  const save = async (next) => { await api('privacy/decoy/chats', {chats: next}); context.rerender(); };
  chats.forEach((chat, index) => {
    const row = element('button', '', 'settings-row');
    row.type = 'button';
    const words = element('span', '', 'settings-words');
    words.append(element('strong', chat.name), element('small', 'Pretend chat · edit or delete'));
    const icon = element('span', '', 'settings-icon');
    icon.append(settingsIcon('messaging'));
    const chevron = element('span', '', 'settings-chevron');
    chevron.append(settingsIcon('chevron'));
    row.append(icon, words, chevron);
    row.addEventListener('click', () => card.replaceWith(decoyEditor(chat, (edited) => save(chats.map((item, at) => at === index ? edited : item)),
      () => save(chats.filter((item, at) => at !== index)), () => context.rerender())));
    list.append(row);
  });
  const add = element('button', 'New pretend chat');
  add.type = 'button';
  add.disabled = chats.length >= 12;
  add.addEventListener('click', () => card.replaceWith(decoyEditor({name: '', lines: [{me: false, text: ''}, {me: true, text: ''}]},
    (edited) => save([...chats, edited]), null, () => context.rerender())));
  card.append(list, add);
  return card;
}

/** The phone's decoy chat editor (DecoyEditor): a name, then bubbles from them or from you. */
function decoyEditor(chat, saved, removed, back) {
  const card = settingsCard(chat.name ? chat.name : 'New decoy chat', 'Type in each bubble. It looks like a normal chat in the decoy inbox, '
    + 'and nothing is ever sent.');
  const name = Object.assign(element('input', ''), {value: chat.name, maxLength: 60, required: true, autocomplete: 'off'});
  const lines = element('div', '', 'decoy-lines');
  const addLine = (line) => {
    const bubble = element('div', '', 'decoy-line' + (line.me ? ' me' : ''));
    const text = Object.assign(element('textarea', ''), {value: line.text, rows: 1, maxLength: 1000, placeholder: line.me ? 'Your message' : 'Their message'});
    text.setAttribute('aria-label', line.me ? 'Your message' : 'Their message');
    const side = element('button', line.me ? 'Move to them' : 'Move to me', 'quiet small-button');
    side.type = 'button';
    side.addEventListener('click', () => {
      const mine = !bubble.classList.contains('me');
      bubble.classList.toggle('me', mine);
      side.textContent = mine ? 'Move to them' : 'Move to me';
      text.placeholder = mine ? 'Your message' : 'Their message';
      text.setAttribute('aria-label', text.placeholder);
    });
    const remove = element('button', '×', 'icon-button small');
    remove.type = 'button';
    remove.setAttribute('aria-label', 'Remove this message');
    remove.addEventListener('click', () => bubble.remove());
    bubble.append(text, side, remove);
    lines.append(bubble);
  };
  chat.lines.forEach(addLine);
  const more = element('button', 'Add a message');
  more.type = 'button';
  more.addEventListener('click', () => { addLine({me: !lines.lastElementChild?.classList.contains('me'), text: ''}); lines.lastElementChild.querySelector('textarea').focus(); });
  const problem = errorLine();
  const ok = element('button', 'Save', 'primary');
  const form = settingsForm(ok, async () => {
    const who = name.value.trim();
    const kept = [...lines.children].map((bubble) => ({me: bubble.classList.contains('me'), text: bubble.querySelector('textarea').value.trim()}))
      .filter((line) => line.text);
    if (!who) throw new Error('Add a name');
    if (!kept.length) throw new Error('Add at least one message');
    await saved({name: who, lines: kept});
  }, problem);
  const row = element('div', '', 'button-row wrap');
  const cancel = element('button', 'Cancel', 'quiet');
  cancel.type = 'button';
  cancel.addEventListener('click', back);
  row.append(ok, cancel);
  if (removed) row.append(confirmButton('Delete this decoy chat', 'Tap again to delete', async () => { await removed(); }));
  form.append(field('Name shown in the inbox', name), lines, more, problem, row);
  card.append(form);
  requestAnimationFrame(() => name.focus());
  return card;
}

async function emergencyPage(context) {
  const status = await loadPrivacy();
  const intro = settingsCard('', 'If you’re ever forced to sign in or open your private folder, type this passcode instead of your real one. '
    + 'FluidMessages Web immediately erases every account, message, key, and setting on this server, as if it were just installed, and shows a '
    + 'harmless decoy inbox. It can’t be undone. Use at least 6 characters, different from your other passwords and passcodes.');
  const where = settingsCard('Where it works');
  const list = element('ul', '', 'erase-list');
  for (const item of ['The sign-in page, in place of your password', 'The private folder’s passcode, in the inbox filter',
    'Show your real inbox, in decoy mode', 'Not on your phone: FluidMessages for Android has its own emergency erase passcode']) list.append(element('li', item));
  where.append(list, settingsLink('erase', 'Everything it erases is the same as Erase everything on this server'));
  where.querySelector('.settings-row strong').textContent = 'What’s erased';
  // A decoy session sees the same form; the server refuses to save it there, like any other real setting.
  const card = settingsCard(status.distress.set ? 'Change emergency erase passcode' : 'Emergency erase passcode',
    status.distress.set ? 'On.' : 'Off.');
  const fields = newCodeFields(status.distress.set ? 'New emergency erase passcode' : 'Emergency erase passcode', 6);
  const current = passwordField(status);
  const understood = checkbox('I understand that typing this passcode erases everything on this server', false, () => {});
  const problem = errorLine();
  const next = element('button', 'Continue', 'primary');
  const form = settingsForm(next, async () => {
    fields.check();
    if (!understood.querySelector('input').checked) throw new Error('Confirm that this passcode erases everything');
    if (status.password && !current.input.value) throw new Error('Enter your FluidMessages Web password');
    // A second, separate confirmation before it's armed: it can never be set by accident.
    const sure = settingsCard('Turn on emergency erase?', 'From now on, typing this passcode erases everything on this server at once, without '
      + 'asking again. Make a backup first if you might want anything back.');
    const confirmProblem = errorLine();
    const go = element('button', status.distress.set ? 'Save the new passcode' : 'Turn on emergency erase', 'danger-button');
    go.type = 'button';
    const back = element('button', 'Back', 'quiet');
    back.type = 'button';
    back.addEventListener('click', () => sure.replaceWith(card));
    go.addEventListener('click', async () => {
      go.disabled = true;
      try {
        await api('privacy/distress', {code: fields.code.value, again: fields.again.value, understood: true, current: current.input.value});
        say('Emergency erase passcode saved');
        context.rerender();
      } catch (error) { confirmProblem.textContent = error.message; go.disabled = false; }
    });
    const row = element('div', '', 'button-row wrap');
    row.append(go, back);
    sure.append(confirmProblem, row);
    card.replaceWith(sure);
    go.focus();
  }, problem);
  form.append(...fields.nodes, ...current.nodes, understood, problem, next);
  card.append(form);
  const cards = [intro, where, card];
  if (status.distress.set) {
    const off = settingsCard('Turn off');
    const password = passwordField(status);
    const offProblem = errorLine();
    const button = element('button', 'Turn off emergency erase');
    const offForm = settingsForm(button, async () => {
      await api('privacy/distress/off', {current: password.input.value});
      say('Emergency erase turned off');
      context.rerender();
    }, offProblem);
    offForm.append(...password.nodes, offProblem, button);
    off.append(offForm);
    cards.push(off);
  }
  return cards;
}
