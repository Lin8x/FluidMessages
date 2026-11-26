'use strict';
// Settings as a full page and a tab, organized like the phone's (MainActivity.settings): the same groups, names,
// and order, each category with an icon and a one-line summary, and its own page with a back arrow. Every page has
// an address (#settings, #settings/privacy), so the browser's Back works. Phone-only settings (battery, app lock,
// SIP, carrier protocols) stay on the phone and are mentioned only where it helps. The web's own private folder,
// decoy mode, and emergency erase passcode add their pages from privacy.js.
// Loaded near the end; uses the helpers in app.js, companion.js, look.js, chats.js, and media.js.

const SETTINGS_ICONS = {
  palette: 'M12 3a9 9 0 0 0 0 18c.83 0 1.5-.67 1.5-1.5 0-.39-.15-.74-.39-1.01-.23-.26-.38-.61-.38-.99 0-.83.67-1.5 1.5-1.5H16c2.76 0 5-2.24 5-5 0-4.42-4.03-8-9-8zm-5.5 9a1.5 1.5 0 1 1 0-3 1.5 1.5 0 0 1 0 3zm3-4a1.5 1.5 0 1 1 0-3 1.5 1.5 0 0 1 0 3zm5 0a1.5 1.5 0 1 1 0-3 1.5 1.5 0 0 1 0 3zm3 4a1.5 1.5 0 1 1 0-3 1.5 1.5 0 0 1 0 3z',
  sounds: 'M12 22c1.1 0 2-.9 2-2h-4c0 1.1.89 2 2 2zm6-6v-5c0-3.07-1.64-5.64-4.5-6.32V4c0-.83-.67-1.5-1.5-1.5s-1.5.67-1.5 1.5v.68C7.63 5.36 6 7.92 6 11v5l-2 2v1h16v-1l-2-2z',
  messaging: 'M20 2H4a2 2 0 0 0-2 2v18l4-4h14a2 2 0 0 0 2-2V4a2 2 0 0 0-2-2zM6 9h12v2H6V9zm8 5H6v-2h8v2zm4-6H6V6h12v2z',
  account: 'M12 12a4 4 0 1 0 0-8 4 4 0 0 0 0 8zm0 2c-2.67 0-8 1.34-8 4v2h16v-2c0-2.66-5.33-4-8-4z',
  ways: 'M6.99 11 3 15l3.99 4v-3H14v-2H6.99v-3zM21 9l-3.99-4v3H10v2h7.01v3L21 9z',
  calls: 'M6.62 10.79a15.05 15.05 0 0 0 6.59 6.59l2.2-2.2c.27-.27.67-.36 1.02-.24 1.12.37 2.33.57 3.57.57.55 0 1 .45 1 1V20c0 .55-.45 1-1 1C10.61 21 3 13.39 3 4c0-.55.45-1 1-1h3.5c.55 0 1 .45 1 1 0 1.25.2 2.45.57 3.57.11.35.03.74-.25 1.02l-2.2 2.2z',
  media: 'M21 19V5a2 2 0 0 0-2-2H5a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2zM8.5 13.5l2.5 3.01L14.5 12l4.5 6H5l3.5-4.5z',
  payments: 'M20 4H4a2 2 0 0 0-1.99 2L2 18a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2V6a2 2 0 0 0-2-2zm0 14H4v-6h16v6zm0-10H4V6h16v2z',
  privacy: 'M12 1 3 5v6c0 5.55 3.84 10.74 9 12 5.16-1.26 9-6.45 9-12V5l-9-4zm0 10.99h7c-.53 4.12-3.28 7.79-7 8.94V12H5V6.3l7-3.11v8.8z',
  backups: 'M19.35 10.04A7.49 7.49 0 0 0 12 4C9.11 4 6.6 5.64 5.35 8.04A5.994 5.994 0 0 0 0 14c0 3.31 2.69 6 6 6h13c2.76 0 5-2.24 5-5 0-2.64-2.05-4.78-4.65-4.96zM14 13v4h-4v-4H7l5-5 5 5h-3z',
  devices: 'M4 6h18V4H4c-1.1 0-2 .9-2 2v11H0v3h14v-3H4V6zm19 2h-6c-.55 0-1 .45-1 1v10c0 .55.45 1 1 1h6c.55 0 1-.45 1-1V9c0-.55-.45-1-1-1zm-1 9h-4v-7h4v7z',
  sample: 'M19.8 18.4 14 10.67V6.5l1.35-1.69c.26-.33.03-.81-.39-.81H9.04c-.42 0-.65.48-.39.81L10 6.5v4.17L4.2 18.4c-.49.66-.02 1.6.8 1.6h14c.82 0 1.29-.94.8-1.6z',
  guide: 'M12 2a10 10 0 1 0 0 20 10 10 0 0 0 0-20zm1 17h-2v-2h2v2zm2.07-7.75-.9.92C13.45 12.9 13 13.5 13 15h-2v-.5c0-1.1.45-2.1 1.17-2.83l1.24-1.26c.37-.36.59-.86.59-1.41 0-1.1-.9-2-2-2s-2 .9-2 2H8c0-2.21 1.79-4 4-4s4 1.79 4 4c0 .88-.36 1.68-.93 2.25z',
  about: 'M12 2a10 10 0 1 0 0 20 10 10 0 0 0 0-20zm1 15h-2v-6h2v6zm0-8h-2V7h2v2z',
  reset: 'M12 5V1L7 6l5 5V7c3.31 0 6 2.69 6 6s-2.69 6-6 6-6-2.69-6-6H4c0 4.42 3.58 8 8 8s8-3.58 8-8-3.58-8-8-8z',
  erase: 'M6 19c0 1.1.9 2 2 2h8c1.1 0 2-.9 2-2V7H6v12zm2.46-7.12 1.41-1.41L12 12.59l2.12-2.12 1.41 1.41L13.41 14l2.12 2.12-1.41 1.41L12 15.41l-2.12 2.12-1.41-1.41L10.59 14l-2.13-2.12zM15.5 4l-1-1h-5l-1 1H5v2h14V4z',
  back: 'M20 11H7.83l5.59-5.59L12 4l-8 8 8 8 1.41-1.41L7.83 13H20v-2z',
  chevron: 'M10 6 8.59 7.41 13.17 12l-4.58 4.59L10 18l6-6z',
};

function settingsIcon(name) {
  const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
  svg.setAttribute('viewBox', '0 0 24 24');
  svg.setAttribute('aria-hidden', 'true');
  const path = document.createElementNS('http://www.w3.org/2000/svg', 'path');
  path.setAttribute('d', SETTINGS_ICONS[name]);
  path.setAttribute('fill', 'currentColor');
  svg.append(path);
  return svg;
}

// Older section names (welcome.js, the sync guide, empty states) still open the right page.
const SETTINGS_ALIASES = {'settings-accounts': 'accounts', 'settings-devices': 'devices', 'settings-appearance': 'appearance',
  'settings-chats': 'messaging', 'settings-fun': 'media', 'settings-media': 'media', 'settings-privacy': 'privacy',
  'settings-sample': 'sample', 'settings-backups': 'backups', 'settings-payments': 'payments', 'settings-help': 'about',
  'settings-reset': 'reset', 'settings-sounds': 'sounds', 'notifications': 'sounds'};

const leaveSettingsThen = (run) => () => { showSection('messages'); run(); };

// The phone's groups and categories, in its order and with its names. "FluidMessages Web" on the phone is this
// app's own "Phone and other devices"; Battery and sync is phone-only.
const SETTINGS_GROUPS = [
  ['Look and feel', [
    {id: 'appearance', icon: 'palette', title: 'Themes and appearance', summary: 'Colors, wallpapers, bubbles, and motion', page: (settings) => [appearanceSection(settings)]},
    {id: 'messaging', icon: 'messaging', title: 'Messaging', summary: 'Timestamps, inbox filters, communities, sending, and gestures', page: messagingPage},
    // The phone's page of the same name. Sounds, their volume, and this browser's notifications (notify.js).
    {id: 'sounds', icon: 'sounds', title: 'Sounds and notifications', page: (settings) => soundsPage(settings),
      summary: () => typeof notifySummary === 'function' ? notifySummary() : 'Send and receive sounds, volume, notifications, and tab counts'}]],
  ['Accounts and calls', [
    {id: 'accounts', icon: 'account', title: 'Accounts', summary: 'Sign in to Matrix, XMPP, Email, or IRC to chat there too', page: accountsPage},
    {id: 'ways', icon: 'ways', title: 'Chat services', page: waysPage,
      summary: () => gatewayEnabled.length ? 'Turn services on or off, and combine one person’s chats' : 'All off · read-only, nothing is sent'},
    {id: 'calls', icon: 'calls', title: 'Calls', summary: 'Matrix voice and video, and meeting links', page: callsPage}]],
  ['In chats', [
    {id: 'media', icon: 'media', title: 'Media, GIFs, and fun', summary: 'GIFs, stickers, photo quality, links, effects, and games',
      page: (settings) => [...mediaSettingsSection(), ...funSections(settings)]},
    {id: 'payments', icon: 'payments', title: 'Payments', summary: () => 'Default: ' + moneyLook(defaultAsset()).title, page: paymentsPage}]],
  ['Privacy', [
    {id: 'privacy', icon: 'privacy', title: 'Privacy and security', page: privacyPage, summary: () => {
      const {chats: codes, kinds} = lockCounts(webChats);
      return (codes ? codes + (codes === 1 ? ' chat code' : ' chat codes') : 'No chat codes') + (kinds ? ' · ' + kinds + (kinds === 1 ? ' kind' : ' kinds') + ' locked' : '')
        + ' · blocked chats, disappearing messages, erase';
    }}]],
  ['Storage and devices', [
    {id: 'backups', icon: 'backups', title: 'Backups', summary: 'Save and restore chat settings and messages (.fmb)', page: backupsPage},
    {id: 'devices', icon: 'devices', title: 'Phone and other devices', page: (settings, context) => [devicesSection(settings, context.rerender)],
      // The phone's last sync shows here, as on the phone's FluidMessages Web row, since phones have no top bar for it.
      summary: (settings) => (settings.phone.paired ? (phonePaired() ? 'Phone synced ' + clock(updated) : 'Phone connected') : 'Connect your phone')
        + ' · password and sign-ins'}]],
  ['Help', [
    {id: 'sample', icon: 'sample', title: 'Sample inbox', summary: 'Practice with made-up chats. Nothing is sent.', run: () => startSample()},
    {id: 'guide', icon: 'guide', title: 'Setup guide', summary: 'A quick tour of what FluidMessages can do', run: leaveSettingsThen(() => showWelcome())},
    // The page that shows once after the first IRC, Matrix, or XMPP account (welcome.js), any time.
    {id: 'communities', icon: 'messaging', title: 'Communities', summary: 'Servers with their channels and rooms, beside your chats',
      run: () => showCommunitiesIntro()},
    {id: 'about', icon: 'about', title: 'About and updates', summary: 'FluidMessages Web on your server', page: aboutPage},
    {id: 'reset', icon: 'reset', title: 'Reset or erase', summary: 'Start over with default settings, or remove everything', page: resetPage}]],
];
// Pages reached from inside another page.
const SETTINGS_SUBPAGES = [
  {id: 'erase', icon: 'erase', title: 'Erase everything on this server', parent: 'privacy', page: erasePage},
  {id: 'payment-add', icon: 'payments', title: 'Add a way to pay', parent: 'payments', page: (settings, context) => addPaymentPage(context)},
  {id: 'chat-locks', icon: 'privacy', title: 'Chat locks', parent: 'privacy', page: () => chatLocksPage()},
  // Calls → Meeting links, and a page to save your own link for each service that needs one (meetings.js).
  {id: 'meeting-links', icon: 'calls', title: 'Meeting links', parent: 'calls', page: (settings, context) => meetingLinksPage(context)},
  ...['zoom', 'meet', 'teams'].map((id) => ({id: 'meeting-link-' + id, icon: 'calls', title: 'Your meeting link', parent: 'meeting-links',
    page: () => meetingLinkPage(id)})),
  ...LOCK_KINDS.map(([kind, label]) => ({id: 'lock-' + kind, icon: 'privacy', title: label, parent: 'chat-locks', page: () => kindLockPage(kind, label)})),
  {id: 'encryption', icon: 'privacy', title: 'Encryption', parent: 'privacy', page: () => encryptionPage()}];

function settingsItem(id) {
  return SETTINGS_GROUPS.flatMap(([, items]) => items).concat(SETTINGS_SUBPAGES).find((item) => item.id === id);
}

const settingsPane = (() => {
  const root = element('section', '', 'settings-pane');
  root.id = 'settings-pane';
  root.setAttribute('aria-label', 'Settings');
  const list = element('nav', '', 'settings-list');
  list.setAttribute('aria-label', 'Settings categories');
  const page = element('div', '', 'settings-page');
  root.append(list, page);
  document.querySelector('.thread-pane').after(root);
  return {root, list, page};
})();

let settingsShown = null;

/** Opens Settings, or one of its pages: openSettings('privacy'). Older section names work too. */
async function openSettings(target) {
  const id = SETTINGS_ALIASES[target] ?? (target || '');
  const hash = '#settings' + (id ? '/' + id : '');
  if (location.hash !== hash) {
    history.pushState({fluidSettings: true, from: section === 'settings' ? settingsShown : null}, '', hash);
  }
  await showSettings(id);
}

/** Follows the address: Back and Forward, links, and reloading the page. */
function settingsRoute() {
  const match = /^#settings(?:\/([a-z-]+))?$/.exec(location.hash);
  if (!match) {
    if (section === 'settings') showSection('messages');
    return;
  }
  if (!signedIn || get('workspace').hidden) return;
  const id = match[1] || '';
  if (section === 'settings' && settingsShown === id) return;
  showSettings(id).catch(showError);
}
addEventListener('hashchange', settingsRoute);
addEventListener('popstate', settingsRoute);

async function showSettings(id) {
  const item = settingsItem(id);
  if (!item || item.run) id = '';
  settingsShown = id;
  showSection('settings');
  const settings = await loadSettings();
  if (settingsShown !== id) return;
  settingsPane.root.dataset.page = String(!!id);
  drawSettingsList(settings, item?.parent || id);
  await drawSettingsPage(id, settings);
}

function settingsRow(item, settings, current) {
  const row = element(item.run ? 'button' : 'a', '', 'settings-row' + (item.danger ? ' danger' : ''));
  if (item.run) {
    row.type = 'button';
    row.addEventListener('click', item.run);
  } else {
    row.href = '#settings/' + item.id;
    row.addEventListener('click', (event) => { event.preventDefault(); openSettings(item.id).catch(showError); });
    if (item.id === current) row.setAttribute('aria-current', 'page');
  }
  const icon = element('span', '', 'settings-icon');
  icon.append(settingsIcon(item.icon));
  const words = element('span', '', 'settings-words');
  words.append(element('strong', item.title), element('small', typeof item.summary === 'function' ? item.summary(settings) : item.summary));
  const chevron = element('span', '', 'settings-chevron');
  chevron.append(settingsIcon('chevron'));
  row.append(icon, words, chevron);
  return row;
}

function drawSettingsList(settings, current) {
  const list = settingsPane.list;
  const heading = element('h1', 'Settings');
  heading.tabIndex = -1;
  list.replaceChildren(heading);
  for (const [group, items] of SETTINGS_GROUPS) {
    const box = element('div', '', 'settings-group');
    box.append(element('h2', group, 'settings-group-title'));
    const rows = element('div', '', 'settings-rows');
    for (const item of items) rows.append(settingsRow(item, settings, current));
    box.append(rows);
    list.append(box);
  }
  if (!current) requestAnimationFrame(() => heading.focus({preventScroll: true}));
}

function settingsBack(item) {
  const parent = item.parent || '';
  // Back in the browser when that's where you came from; otherwise straight to the page above.
  if (history.state?.fluidSettings && history.state.from === parent) { history.back(); return; }
  history.replaceState(null, '', '#settings' + (parent ? '/' + parent : ''));
  showSettings(parent).catch(showError);
}

async function drawSettingsPage(id, settings) {
  const page = settingsPane.page;
  const item = settingsItem(id);
  if (!item) {
    const empty = element('div', '', 'settings-empty');
    empty.append(settingsIcon('palette'), element('p', 'Choose a category to see its settings.', 'muted'));
    page.replaceChildren(empty);
    return;
  }
  const head = element('div', '', 'settings-page-head');
  const back = element('button', '', 'settings-back icon-button');
  back.type = 'button';
  back.setAttribute('aria-label', 'Back to ' + (item.parent ? settingsItem(item.parent).title : 'Settings'));
  back.append(settingsIcon('back'));
  back.addEventListener('click', () => settingsBack(item));
  const title = element('h2', item.title);
  title.tabIndex = -1;
  head.append(back, title);
  const body = element('div', '', 'settings-page-body');
  page.replaceChildren(head, body);
  const context = {settings, rerender: (where) => showSettings(SETTINGS_ALIASES[where] ?? (where || id))};
  try { body.append(...await item.page(settings, context)); } catch (error) { body.append(element('p', error.message, 'notice')); }
  page.scrollTop = 0;
  requestAnimationFrame(() => title.focus({preventScroll: true}));
}

function settingsCard(title, intro) {
  const card = element('section', '', 'settings-section');
  if (title) card.append(element('h3', title));
  if (intro) card.append(element('p', intro, 'muted'));
  return card;
}

/** A row in a page that opens another page, drawn like the category rows. */
function settingsLink(id, summary, danger = false) {
  const item = {...settingsItem(id), summary, danger};
  return settingsRow(item, {}, '');
}

const followingPhoneNote = () => element('p', 'Following your phone’s settings. To change this here, choose “This web app’s own” under '
  + 'Phone and other devices.', 'notice');

function lockInput(wrap, locked) {
  for (const input of wrap.querySelectorAll('input, select')) input.disabled = locked;
  return wrap;
}

// Look and feel → Messaging

function messagingPage(settings) {
  const locked = lookLocked(settings);
  const display = settingsCard('Message display');
  display.append(lockInput(checkbox('Show exact message times', !!webLook.exactTimes, (value) => saveWebLook({...webLook, exactTimes: value}, true),
    'Show the clock time in the inbox and under each message.'), locked));
  if (locked) display.append(followingPhoneNote());

  const sending = settingsCard('Sending');
  const queue = element('button', get('open-queue').hidden ? 'Scheduled messages' : get('open-queue').textContent);
  queue.type = 'button';
  queue.addEventListener('click', () => { get('queue-status').textContent = ''; get('queue').showModal(); });
  sending.append(checkbox('Enter sends', localStorage.getItem('enterSends') !== 'off', (value) => localStorage.setItem('enterSends', value ? 'on' : 'off'),
    'Shift+Enter starts a new line. Saved in this browser.'),
  element('p', 'To schedule a message, open Send later under the message box. Internet messages send from this server at their time; '
    + 'texts wait for your phone. Cancel any that haven’t gone yet here.', 'muted'), queue);

  const privacy = settingsCard('Privacy', stripDetails() ? 'Photos you send are saved again first, which removes location and camera details. '
    + 'Other files are sent as they are.' : 'Location and camera details stay in the photos you send.');
  privacy.append(settingsLink('media', 'Remove location and camera details from files you send · Photos and videos'));
  const gestures = settingsCard('Gestures');
  const list = element('dl', '', 'settings-facts');
  for (const [term, detail] of [['Swipe right to reply', 'On a touch screen, quote a message and add your reply underneath. With a mouse or keyboard, Reply is in the message’s ⋯ menu.'],
    ['Right-click or touch and hold a chat', 'Pin, archive, mute, block, or open its details.'],
    ['Right-click or touch and hold a message', 'React, reply, copy, select text, see message details, or delete it for you. With a mouse, its ⋯ shows when you point at it.'],
    ['Chat details', 'The details button at the top of a chat: disappearing timer, people, chat code, and wallpaper.']]) {
    list.append(element('dt', term), element('dd', detail));
  }
  gestures.append(list);
  const sample = element('button', 'Try in sample inbox');
  sample.type = 'button';
  sample.addEventListener('click', () => startSample());
  gestures.append(sample);
  // The inbox: its filter menu (inbox-filters.js) and where community chats show (servers.js).
  const inbox = settingsCard('Inbox');
  inbox.id = 'settings-inbox';
  if (typeof inboxFiltersPage === 'function') {
    const shown = inboxFilterMenu(inboxFilterPrefs(), new Set(INBOX_FILTERS.map((filter) => filter.service).filter(Boolean)));
    inbox.append(settingsLink('inbox-filters', shown.length + ' of ' + INBOX_FILTERS.length + ' shown in the filter menu · choose and reorder them'));
  }
  if (typeof communityChatsSwitch === 'function') inbox.append(communityChatsSwitch());
  // Counts on the Calls and Chats tabs (people.js).
  return [display, inbox, sending, privacy, gestures];
}

// Accounts and calls

function accountsPage() {
  const card = settingsCard('', 'FluidMessages stays connected to these, even when this page is closed.');
  const list = element('div', '', 'account-list');
  card.append(list);
  const draw = () => accountRows(list, draw).then(() => refresh().catch(() => {}))
    .catch((error) => list.replaceChildren(element('p', error.message, 'muted')));
  draw();
  return [card, ircCallsCard()];
}

/** Settings → Accounts → IRC: call and meeting-link buttons in IRC chats, off unless you turn them on. */
function ircCallsCard() {
  const card = settingsCard('IRC');
  const toggle = checkbox('Show call and meeting link buttons in IRC chats', prefs.ircCalls === true, async (value) => {
    prefs = {...prefs, ircCalls: value};
    updateCallButtons();
    try { companionSettings = await api('settings', {prefs: {ircCalls: value}}); } catch (error) { showError(error); }
  });
  toggle.querySelector('input').setAttribute('role', 'switch');
  card.append(toggle, element('p', 'Not recommended. Many IRC channels ban call and meeting links, and moderators may kick or ban you for '
    + 'posting them. Off by default.', 'notice irc-calls-warning'));
  return card;
}

// How chat services and battery go together, the same words as the phone's (ServiceBattery.kt): turning a service on
// costs nothing; only a signed-in account connects, and this server can hold those connections for the phone.
const SERVICE_BATTERY = {
  title: 'What about battery?',
  lines: (paired) => ['Turning a service on uses no battery by itself. Only an account you sign in to connects.',
    'To get new messages, a chat service needs a connection that stays open. On your phone, that keeps it awake.',
    paired ? 'FluidMessages Web is set up, so the computer it runs on holds those connections for you. Your phone only checks in now and then.'
      : 'FluidMessages Web can run on a computer that stays on and hold those connections for you. Then your phone only checks in now and then.'],
};

function serviceBatteryCard(paired) {
  const card = settingsCard(SERVICE_BATTERY.title);
  card.classList.add('service-battery');
  for (const line of SERVICE_BATTERY.lines(paired)) card.append(element('p', line, 'muted'));
  return card;
}

const PROTOCOL_BLURBS ={matrix: 'Chat, groups, and calls', xmpp: 'Jabber and XMPP chat', email: 'Any IMAP and SMTP mailbox', irc: 'Channels and private messages'};

function waysPage(settings) {
  const paired = settings.phone.paired;
  const texts = settingsCard('Text messages', 'RCS (preview), SMS, and MMS go through your phone. Turn them on or off in FluidMessages for Android.');
  const internetCard = settingsCard('Internet protocols');
  for (const key of ['matrix', 'xmpp', 'email', 'irc']) {
    internetCard.append(lockInput(checkbox(labels[key], gatewayEnabled.includes(key), async (on) => {
      const next = on ? [...new Set([...gatewayEnabled, key])] : gatewayEnabled.filter((item) => item !== key);
      try {
        const result = await api('protocols', {enabled: next});
        gatewayEnabled = result.enabled;
        refresh().catch(() => {});
      } catch (error) { showError(error); }
    }, PROTOCOL_BLURBS[key]), paired));
  }
  internetCard.append(element('p', paired ? 'Your phone turns these on or off each time it syncs. Change them in Settings → Chat services on the phone.'
    : 'Off stops sending and receiving on this server. Its chats stay readable.', 'muted'));
  const locked = lookLocked(settings);
  const conversations = settingsCard('Conversations');
  const tags = element('select', '');
  for (const [value, label] of [['auto', 'Automatic'], ['always', 'Always'], ['off', 'Off']]) tags.append(Object.assign(element('option', label), {value}));
  tags.value = webLook.tagMode || 'auto';
  tags.addEventListener('change', () => saveWebLook({...webLook, tagMode: tags.value}, true));
  // Encryption status, as on the phone (ProtocolUi DisplayRules.ENCRYPTION): Off hides the lock beside the chat name.
  const locks = element('select', '');
  for (const [value, label] of [['auto', 'Automatic'], ['always', 'Always'], ['off', 'Off']]) locks.append(Object.assign(element('option', label), {value}));
  locks.value = webLook.encryptionMode || 'auto';
  locks.addEventListener('change', () => saveWebLook({...webLook, encryptionMode: locks.value}, true));
  conversations.append(
    lockInput(checkbox('Combine linked conversations', !!webLook.combineProtocols, (value) => saveWebLook({...webLook, combineProtocols: value}, true),
      'Every way you talk to someone in one chat, with each message labeled.'), locked),
    lockInput(field('Inbox protocol tags', tags, 'Automatic shows them once you have more than one kind of conversation.'), locked),
    lockInput(field('Encryption status', locks, 'A colored lock beside the chat name: green for end-to-end, yellow for in transit only, '
      + 'red for not encrypted. Every chat’s Security section says the same.'), locked));
  if (locked) conversations.append(followingPhoneNote());
  const order = element('p', 'New chats use the account you pick in New chat. A protocol that’s turned off stays readable.', 'muted');
  return [texts, internetCard, serviceBatteryCard(paired), conversations, order];
}

// The phone's Ways to call (Calling.Method): Matrix and meeting links work from this browser, so they're switches here,
// kept for every browser on this server. Phone, XMPP, and SIP calls are made on the phone, so they're shown as facts.
function callsPage() {
  const ways = settingsCard('Ways to call · separate from messaging');
  const save = (name) => async (value) => {
    prefs = {...prefs, [name]: value};
    updateCallButtons();
    try { companionSettings = await api('settings', {prefs: {[name]: value}}); } catch (error) { showError(error); }
  };
  const matrix = chatAccounts.some((account) => account.protocol === 'matrix');
  ways.append(
    checkbox('Matrix', callWayOn('matrix'), save('callMatrix'), 'Voice and video with Matrix contacts. End-to-end encrypted.'
      + (matrix ? '' : ' Add a Matrix account in Accounts to call from here.')),
    checkbox('Meeting links', callWayOn('meeting'), save('callMeetings'), 'A link anyone can open to join, on any app. Pick Zoom, Jitsi Meet, Google Meet, or Teams when you send it.'),
    settingsLink('meeting-links', meetingSummary(meetingSettings(prefs.meetings)) + ' · services, your saved links, and the Jitsi server'),
    element('p', 'Off hides that way to call in chats, their details, and contact pages. Calls you get still show.', 'muted'));
  const phone = settingsCard('On your phone');
  const list = element('dl', '', 'settings-facts');
  for (const [term, detail] of [
    ['Phone', 'Calls with your phone number, through Android’s call screen. ' + (phonePaired()
      ? 'The Call button in a text chat asks your phone to dial.' : 'Connect your phone to call numbers through it.')],
    ['XMPP', 'Voice and video with XMPP contacts, such as people using Conversations. Placed from FluidMessages for Android.'],
    ['SIP', 'Internet phone service from a SIP provider, including regular phone numbers. Set up and made on your phone.']]) {
    list.append(element('dt', term), element('dd', detail));
  }
  phone.append(list, element('p', 'Turn these on or off, and set call recording, the phone’s own meeting links, and regular phone calls in Recents, in '
    + 'FluidMessages for Android.', 'muted'));
  const open = element('button', 'Open Calls', 'primary');
  open.type = 'button';
  open.addEventListener('click', () => showSection('calls'));
  ways.append(open);
  return [ways, phone];
}

// In chats → Payments

function paymentsPage() {
  const main = settingsCard('Requests');
  const usual = element('select', '');
  for (const name of Object.keys(allAssets())) usual.append(Object.assign(element('option', moneyLook(name).title + ' · ' + moneyLook(name).detail), {value: name}));
  usual.value = defaultAsset();
  usual.addEventListener('change', () => localStorage.setItem('money.default', usual.value));
  main.append(field('Your usual way to pay', usual, 'It sits on top in Money. Saved in this browser.'));
  const custom = settingsCard('Added by you');
  const draw = () => {
    custom.replaceChildren(element('h3', 'Added by you'));
    const own = customAssets();
    if (!own.length) custom.append(element('p', 'None yet. Add one here, or from Money in any chat.', 'muted'));
    for (const item of own) {
      const row = element('div', '', 'account-row');
      const words = element('span', '', 'account-words');
      words.append(element('strong', item.name), element('span', item.template, 'muted'));
      row.append(words, confirmButton('Remove', 'Tap again to remove', async () => {
        localStorage.setItem('money.custom', JSON.stringify(customAssets().filter((other) => other.name !== item.name)));
        draw();
      }, 'quiet'));
      custom.append(row);
    }
  };
  draw();
  const add = settingsCard('');
  add.append(settingsLink('payment-add', 'Any coin or service with a payment link'));
  const about = settingsCard('How paying works', 'FluidMessages never holds money, keys, or card numbers. A request is a standard payment link that '
    + 'opens the payer’s own app.');
  return [main, custom, add, about];
}

/** Settings → Payments → Add a way to pay: the same form as in Money. */
function addPaymentPage(context) {
  const card = settingsCard('');
  const leave = () => settingsBack(settingsItem('payment-add'));
  card.append(customPayForm({done: (name) => { say(name + ' added. It’s in Money in every chat.'); leave(); }, cancel: leave}));
  return [card];
}

// Privacy → Chat locks: lock every chat of one kind with one passcode (chats.js), as on the phone.

function chatLocksPage() {
  const how = settingsCard('How it works');
  how.append(element('strong', 'Locked chats ask you to unlock them', 'privacy-lead'), element('p', 'With this computer’s screen lock '
    + 'or a code. Their previews are hidden in the inbox and search, and they lock again when you leave them. To lock one chat, '
    + 'open its details and turn on Chat lock.', 'muted'));
  const kinds = settingsCard('Lock every chat of one kind');
  for (const [kind, label] of LOCK_KINDS) {
    kinds.append(settingsLink('lock-' + kind, !webChats.locks?.[serviceLock(kind)] ? 'Not locked'
      : deviceLock(webChats.locks[serviceLock(kind)]) ? 'Locked · with this computer’s screen lock' : 'Locked · with a passcode'));
  }
  const relock = element('button', 'Lock them again now');
  relock.type = 'button';
  relock.addEventListener('click', () => { unlockedAddress = ''; showMessages(); say('Locked chats are locked again.'); });
  kinds.append(relock);
  return [how, kinds];
}

function kindLockPage(kind, label) {
  const card = settingsCard('');
  const lower = label.charAt(0).toLowerCase() + label.slice(1);
  lockCodeForm(card, serviceLock(kind), {
    title: label,
    label: 'Lock ' + lower,
    on: 'On. ' + label + ' ask you to unlock them before they open, and their previews are hidden. A chat with its own lock asks for that one.',
    off: 'Off. Turn it on to hide their previews and ask before they open.',
    wrong: 'That isn’t the passcode.',
    saved: (added) => { unlockedAddress = ''; showMessages(true); say(added ? label + ' are locked.' : label + ' are unlocked.'); },
  });
  return [card];
}

// Privacy → Encryption: what the lock colors mean, like the phone's page.

function encryptionPage() {
  const legend = settingsCard('What the lock colors mean');
  for (const [tone, title, detail] of [
    ['green', 'Green lock', 'End-to-end encrypted, or encrypted with FluidMessages. Only you and the people in the chat can read it.'],
    ['yellow', 'Yellow lock', 'Protected on its way to the server, but the service can read it.'],
    ['red', 'Red open lock', 'Not encrypted. Regular texts work this way; your carrier can read them.']]) {
    const row = element('div', '', 'detail-row');
    const words = element('span', '', 'detail-words');
    words.append(element('strong', title), element('small', detail));
    row.append(lockBadge(tone, title, 'large'), words);
    legend.append(row);
  }
  legend.append(element('p', 'Each chat shows its lock beside its name, and its details say what it means there. Settings → Chat services → '
    + 'Encryption status can hide the lock.', 'muted'));
  const fluid = settingsCard('Encrypt with FluidMessages', 'You and the other person enter the same encryption key, and messages are scrambled '
    + 'before they leave your phone. Both of you need FluidMessages. A chat’s own setting, in its details on the phone, always wins. Turn it on '
    + 'in FluidMessages for Android: the key stays on your phone, so messages sent from this browser aren’t encrypted that way.');
  return [legend, fluid];
}

// Privacy → Privacy and security

function threadTitleFor(address) {
  const key = [...threads.keys()].find((item) => threadAddress(item) === address);
  return key ? threadTitle(key) : address;
}

function blockedSection(context) {
  const card = settingsCard('Blocked chats');
  const blocked = chatFolders().blocked;
  const own = chatState().blocked || [];
  if (!blocked.length) card.append(element('p', 'No blocked chats. Block one from its details, or right-click it in the inbox.', 'muted'));
  for (const address of blocked) {
    const row = element('div', '', 'account-row');
    const words = element('span', '', 'account-words');
    words.append(element('strong', threadTitleFor(address)), element('span', own.includes(address) ? 'Blocked here' : 'Blocked on your phone', 'muted'));
    row.append(words);
    if (own.includes(address)) {
      const unblock = element('button', 'Unblock');
      unblock.type = 'button';
      unblock.setAttribute('aria-label', 'Unblock ' + threadTitleFor(address));
      unblock.addEventListener('click', () => { toggleChat(address, 'blocked'); context.rerender(); });
      row.append(unblock);
    }
    card.append(row);
  }
  card.append(element('p', 'Blocking moves a chat to Blocked and stops its chime. It doesn’t stop delivery.', 'muted'));
  return card;
}

function disappearingSection(settings) {
  const card = settingsCard('Messages');
  const timer = timerSelect(settings.disappearing.default, false);
  timer.addEventListener('change', () => api('settings', {disappearing: {...settings.disappearing, default: Number(timer.value)}})
    .then(loadSettings).catch(showError));
  card.append(field('Disappearing messages', timer, 'The default for every chat; each chat’s details can change it. Your own Matrix and XMPP '
    + 'messages are taken back for everyone; texts, email, and IRC stay on the other person’s device.'
    + (settings.follow_phone && settings.phone.paired ? (typeof mergeActive === 'function' && mergeActive()
      ? ' Your phone gets this on its next sync; if it changed this too, you choose which to keep.'
      : ' Your phone’s timers replace this when it syncs.') : '')));
  if (typeof CARRIER_COPY_NOTE === 'string') card.append(element('p', CARRIER_COPY_NOTE, 'muted carrier-note'));
  // Sends “⏱️ Disappearing messages are on…” in the chat when you change its timer, like the phone.
  const locked = lookLocked(settings);
  card.append(lockInput(checkbox('Announce chat setting changes', resolvedAppearance().announceSettings !== false,
    (value) => saveWebLook({...webLook, announceSettings: value}, true),
    'When you change a chat’s disappearing timer, send a short note so the other person knows. Off: the setting still changes, nothing is sent.'), locked));
  if (locked) card.append(followingPhoneNote());
  return card;
}

async function privacyPage(settings, context) {
  const erase = settingsCard('Erase');
  erase.append(settingsLink('erase', 'Removes all accounts, messages, keys, and settings from this server', true));
  const encryption = settingsCard('Encryption');
  encryption.append(settingsLink('encryption', 'What the green, yellow, and red locks mean'));
  // Protect the app and Keep things private: the emergency erase passcode, the private folder, and decoy mode (privacy.js).
  const own = typeof privacySections === 'function' ? await privacySections() : [];
  // Requests and spam: unknown senders and automated messages (requests.js).
  const requests = typeof requestSettingsLink === 'function' ? [requestSettingsLink()] : [];
  return [...own, ...requests, chatCodesSection(), encryption, privateNoteSection(), blockedSection(context), disappearingSection(settings), erase];
}

// Storage and devices → Backups: the phone's .fmb format, unchanged. A web backup holds this server's conversation
// settings (pinned, archived, blocked, muted, exact times) and internet messages, always with a password.

const FMB_MAX = 64 * 1024 * 1024;

async function fmbKey(password, salt, uses) {
  if (!crypto.subtle) throw new Error('Backups need FluidMessages Web opened over HTTPS (or at localhost).');
  const base = await crypto.subtle.importKey('raw', new TextEncoder().encode(password), 'PBKDF2', false, ['deriveKey']);
  return crypto.subtle.deriveKey({name: 'PBKDF2', hash: 'SHA-256', salt, iterations: 600000}, base, {name: 'AES-GCM', length: 256}, false, uses);
}

async function fmbWrite(archive, password) {
  const json = new TextEncoder().encode(JSON.stringify(archive));
  if (json.length > FMB_MAX) throw new Error('This backup would be too large.');
  const packed = new Uint8Array(await new Response(new Blob([json]).stream().pipeThrough(new CompressionStream('gzip'))).arrayBuffer());
  const salt = crypto.getRandomValues(new Uint8Array(16));
  const nonce = crypto.getRandomValues(new Uint8Array(12));
  const sealed = await crypto.subtle.encrypt({name: 'AES-GCM', iv: nonce}, await fmbKey(password, salt, ['encrypt']), packed);
  // "FMB1", then 1 for encrypted, the salt, the nonce, and AES-GCM over the gzipped JSON (Backup.java).
  return new Blob([new Uint8Array([0x46, 0x4d, 0x42, 0x31, 1]), salt, nonce, sealed], {type: 'application/octet-stream'});
}

async function fmbRead(file, password) {
  if (file.size > FMB_MAX + 64) throw new Error('This backup is too large.');
  const bytes = new Uint8Array(await file.arrayBuffer());
  if (bytes.length < 5 || String.fromCharCode(...bytes.slice(0, 4)) !== 'FMB1') throw new Error('This is not a FluidMessages backup.');
  let body = bytes.slice(5);
  if (bytes[4]) {
    if (!password) throw new Error('This backup has a password. Enter it to open the backup.');
    try {
      const key = await fmbKey(password, body.slice(0, 16), ['decrypt']);
      body = new Uint8Array(await crypto.subtle.decrypt({name: 'AES-GCM', iv: body.slice(16, 28)}, key, body.slice(28)));
    } catch (error) {
      if (/HTTPS/.test(error.message)) throw error;
      throw new Error('That password isn’t right, or the file is damaged.');
    }
  }
  const json = await new Response(new Blob([body]).stream().pipeThrough(new DecompressionStream('gzip'))).text();
  if (json.length > FMB_MAX) throw new Error('This backup is too large.');
  const archive = JSON.parse(json);
  if (!archive || archive.version !== 1) throw new Error('This backup is from a newer version of FluidMessages.');
  return archive;
}

const FOLDER_KEYS = ['pinned', 'archived', 'blocked', 'muted'];

function addressList(value) {
  try {
    const list = JSON.parse(value);
    return Array.isArray(list) ? list.filter((item) => typeof item === 'string' && item.length <= 300) : [];
  } catch (error) { return []; }
}

function backupExportSection() {
  const card = settingsCard('Save a backup', 'A .fmb file, the same kind the phone makes. FluidMessages for Android can restore it too.');
  const chats = Object.assign(element('input', ''), {type: 'checkbox', checked: true});
  const messages = Object.assign(element('input', ''), {type: 'checkbox', checked: true});
  const what = element('fieldset', '', 'stack');
  what.append(element('legend', 'What to include'));
  for (const [input, title, hint] of [[chats, 'Conversation settings', 'Pinned, archived, blocked, and muted chats, and exact times.'],
    [messages, 'Internet messages', 'Matrix, XMPP, Email, and IRC messages on this server, without attachments.']]) {
    const wrap = element('label', '', 'check');
    const words = element('span', '');
    words.append(element('span', title), element('small', hint, 'hint'));
    wrap.append(input, words);
    what.append(wrap);
  }
  const form = element('form', '', 'stack');
  const password = Object.assign(element('input', ''), {type: 'password', autocomplete: 'new-password', required: true, minLength: 8, maxLength: 1024});
  const again = Object.assign(element('input', ''), {type: 'password', autocomplete: 'new-password', required: true, minLength: 8, maxLength: 1024});
  const status = element('p', '', 'muted');
  status.setAttribute('role', 'status');
  const save = element('button', 'Save backup', 'primary');
  form.append(Object.assign(element('input', ''), {type: 'text', name: 'username', autocomplete: 'username', value: 'FluidMessages backup', hidden: true}),
    what, field('Backup password', password, 'At least 8 characters. You need it to restore; it can’t be recovered.'), field('Type it again', again), save, status);
  form.addEventListener('submit', async (event) => {
    event.preventDefault();
    if (password.value !== again.value) { status.textContent = 'The two passwords don’t match.'; return; }
    if (!chats.checked && !messages.checked) { status.textContent = 'Choose something to include.'; return; }
    save.disabled = true;
    status.textContent = 'Saving…';
    try {
      const archive = {version: 1, categories: 0};
      if (chats.checked) {
        archive.settings = Object.fromEntries(FOLDER_KEYS.map((key) => [key, JSON.stringify(webChats[key] || [])]));
        archive.settings.exactTimes = !!webLook.exactTimes;
      }
      if (messages.checked) {
        const snapshot = await api('inbox');
        const rows = snapshot.messages.filter((row) => internet(row.protocol) && /^[A-Za-z0-9_-]{1,100}$/.test(row.id || ''));
        archive.categories = 64;
        archive.protocols = {messages: rows, files: {}, settings: {}};
      }
      const stamp = new Date().toISOString().slice(0, 10);
      saveBlob(await fmbWrite(archive, password.value), 'FluidMessages_Web_' + stamp + '.fmb');
      form.reset();
      chats.checked = messages.checked = true;
      status.textContent = 'Saved. Keep the file and its password somewhere safe.';
    } catch (error) { status.textContent = error.message; }
    finally { save.disabled = false; }
  });
  card.append(form);
  return card;
}

function backupImportSection() {
  const card = settingsCard('Restore a backup', 'Open a .fmb from this web app or from your phone. Conversation settings restore here; '
    + 'texts, themes, and phone settings restore on your phone.');
  const form = element('form', '', 'stack');
  const file = Object.assign(element('input', ''), {type: 'file', accept: '.fmb,application/octet-stream', required: true});
  const password = Object.assign(element('input', ''), {type: 'password', autocomplete: 'current-password', maxLength: 1024});
  const status = element('p', '', 'error');
  status.setAttribute('role', 'alert');
  const open = element('button', 'Open backup', 'primary');
  const result = element('div', '', 'stack');
  form.append(field('Backup file', file), field('Backup password', password, 'Leave it empty if the backup has none.'), open, status);
  form.addEventListener('submit', async (event) => {
    event.preventDefault();
    open.disabled = true;
    status.textContent = '';
    result.replaceChildren();
    try {
      const archive = await fmbRead(file.files[0], password.value);
      const settings = archive.settings && typeof archive.settings === 'object' ? archive.settings : {};
      const here = FOLDER_KEYS.some((key) => typeof settings[key] === 'string') || typeof settings.exactTimes === 'boolean';
      const inside = element('ul', '', 'erase-list');
      const add = (text) => inside.append(element('li', text));
      if (here) add('Conversation settings · restores here');
      if (Array.isArray(archive.sms)) add(archive.sms.length + ' text messages · restore on your phone');
      if (archive.theme) add('Theme, sounds, and layout from the phone · restore on your phone');
      if (Array.isArray(archive.protocols?.messages)) add(archive.protocols.messages.length + ' MMS and internet messages · restore on your phone');
      if (archive.wallpaperPhotos) add('Wallpaper photos · restore on your phone');
      result.append(element('h4', 'In this backup'), inside);
      if (here) {
        const restore = element('button', 'Restore conversation settings here', 'primary');
        restore.type = 'button';
        restore.addEventListener('click', () => {
          // Added to what's here, so restoring twice changes nothing more.
          const next = {...webChats};
          for (const key of FOLDER_KEYS) next[key] = [...new Set([...(webChats[key] || []), ...addressList(settings[key])])].slice(0, 5000);
          saveChats(next);
          if (typeof settings.exactTimes === 'boolean') saveWebLook({...webLook, exactTimes: settings.exactTimes}, true);
          restore.disabled = true;
          restore.textContent = 'Restored';
        });
        result.append(restore);
      } else result.append(element('p', 'Nothing in it restores here.', 'muted'));
    } catch (error) { status.textContent = error instanceof SyntaxError ? 'This backup is damaged.' : error.message; }
    finally { open.disabled = false; }
  });
  card.append(form, result);
  return card;
}

function backupsPage() {
  return [backupExportSection(), backupImportSection(), cloudBackupsSection()];
}

// Help

const FLUID_REPOSITORY = 'https://github.com/Lin8x/FluidMessages';

function aboutPage() {
  const about = settingsCard('FluidMessages Web', 'Your chats in a browser, running on your own server. Matrix, XMPP, Email, and IRC work on their '
    + 'own; FluidMessages for Android adds your texts.');
  const updates = settingsCard('Updates', 'The server updates itself only when you update it: pull the newest image and restart it '
    + '(docker compose pull, then docker compose up -d). Your chats and settings stay.');
  const links = settingsCard('Links');
  for (const [label, url] of [['GitHub', FLUID_REPOSITORY], ['Releases', FLUID_REPOSITORY + '/releases/latest'], ['Self-hosting guide', FLUID_REPOSITORY + '#lan-web-companion']]) {
    const link = element('a', label, 'settings-link');
    link.href = url;
    link.target = '_blank';
    link.rel = 'noopener noreferrer';
    links.append(link);
  }
  return [about, updates, links];
}

function resetPage(settings, context) {
  const reset = settingsCard('Settings');
  reset.append(element('p', 'Themes, wallpapers, fun and effects, and disappearing defaults go back to how they start. Your messages, accounts, '
    + 'phone pairing, chat folders, chat codes, and password stay.', 'muted'),
  confirmButton('Reset settings to defaults', 'Tap again to reset', async () => {
    try { await api('settings', {reset: true}); } catch (error) { showError(error); return; }
    await refresh().catch(() => {});
    context.rerender();
  }));
  const everything = settingsCard('Everything');
  everything.append(settingsLink('erase', 'Removes all accounts, messages, keys, and settings from this server', true));
  return [reset, everything];
}

// Privacy and security → Erase everything on this server

/** Everything this origin keeps in the browser: saved choices, stickers, the app's cached files, and its worker. */
async function clearBrowserData() {
  try { localStorage.clear(); } catch (error) { /* not available */ }
  try { sessionStorage.clear(); } catch (error) { /* not available */ }
  try {
    const listed = indexedDB.databases ? await indexedDB.databases() : [];
    const names = new Set(['fluid-stickers', ...listed.map((item) => item.name).filter(Boolean)]);
    await Promise.all([...names].map((name) => new Promise((done) => {
      const request = indexedDB.deleteDatabase(name);
      request.onsuccess = request.onerror = request.onblocked = () => done();
    })));
  } catch (error) { /* not available */ }
  try { for (const key of await caches.keys()) await caches.delete(key); } catch (error) { /* not available */ }
  try { for (const registration of (await navigator.serviceWorker?.getRegistrations?.()) || []) await registration.unregister(); }
  catch (error) { /* not available */ }
}

async function erasePage() {
  const security = await api('auth/security');
  const intro = settingsCard('', 'This deletes every account, message, chat, key, and setting from FluidMessages Web on this server, and signs out '
    + 'every browser. It can’t be undone. Make a backup first if you might want anything back.');
  const backup = element('button', 'Make a backup first');
  backup.type = 'button';
  backup.addEventListener('click', () => openSettings('backups').catch(showError));
  intro.append(backup);
  const list = (items) => {
    const node = element('ul', '', 'erase-list');
    for (const item of items) node.append(element('li', item));
    return node;
  };
  const deleted = settingsCard('What’s deleted');
  deleted.append(list([
    'Messages and chats kept on this server: internet messages, its copy of your texts, attachments, and scheduled messages, including texts still waiting for your phone',
    'Chat accounts (Matrix, XMPP, Email, IRC) with their saved passwords and tokens, and Matrix encryption keys',
    'Settings: theme, colors, fun and effects, chat folders, chat codes, and disappearing timers',
    'The private folder and its passcode, decoy mode and its pretend chats, and the emergency erase passcode',
    'Stickers, your wallpaper photo, and everything else this browser saved for FluidMessages Web',
    'Your phone’s pairing token. The phone stops syncing until you connect it again',
    'Every browser sign-in' + (security.owner ? ', and the web password' : '')]));
  const kept = settingsCard('What stays');
  kept.append(list([
    'Your phone and everything on it: its texts, its FluidMessages chats and settings, and its backups',
    'Messages on the Matrix, XMPP, email, and IRC servers. Other people keep their copies',
    'What the server’s admin set up outside the app: FLUID_TOKEN, accounts in Docker Compose, and cloud backup destinations (rclone)']));
  const confirm = settingsCard('Erase now', 'Afterward FluidMessages Web starts over like a new install. On the server’s own computer it opens to the '
    + 'welcome; other devices need the new setup code from the server’s log.');
  const form = element('form', '', 'stack');
  const word = Object.assign(element('input', ''), {required: true, autocomplete: 'off', spellcheck: false, autocapitalize: 'characters', maxLength: 10});
  const password = Object.assign(element('input', ''), {type: 'password', autocomplete: 'current-password', required: true, maxLength: 1024});
  const status = element('p', '', 'error');
  status.setAttribute('role', 'alert');
  const go = element('button', 'Erase everything', 'danger-button');
  go.disabled = true;
  form.append(field('Type ERASE to confirm', word));
  if (security.owner) {
    form.append(Object.assign(element('input', ''), {type: 'text', name: 'username', autocomplete: 'username', value: 'FluidMessages Web', hidden: true}),
      field('Your FluidMessages Web password', password));
  }
  form.append(go, status);
  const ready = () => { go.disabled = word.value.trim() !== 'ERASE' || (security.owner && !password.value); };
  word.addEventListener('input', ready);
  password.addEventListener('input', ready);
  form.addEventListener('submit', async (event) => {
    event.preventDefault();
    if (word.value.trim() !== 'ERASE') { status.textContent = 'Type ERASE to confirm.'; return; }
    go.disabled = true;
    status.textContent = '';
    go.textContent = 'Erasing…';
    try {
      const response = await fetch('/api/erase', {method: 'POST', credentials: 'same-origin',
        headers: {...REQUEST_HEADERS, 'Content-Type': 'application/json'},
        body: JSON.stringify({confirm: 'ERASE', password: security.owner ? password.value : ''})});
      let result = {};
      try { result = await response.json(); } catch (error) { /* not JSON */ }
      if (!response.ok) throw new Error(result.error || 'Couldn’t erase. Try again.');
      // Signed out everywhere: stop syncing, forget this browser's copy, and start over.
      signedIn = false;
      await clearBrowserData();
      history.replaceState(null, '', location.pathname + '#erased');
      location.reload();
    } catch (error) {
      status.textContent = error.message;
      go.textContent = 'Erase everything';
      ready();
    }
  });
  confirm.append(form);
  return [intro, deleted, kept, confirm];
}
