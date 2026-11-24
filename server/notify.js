'use strict';
// Sounds and notifications, like the phone's page of the same name: the send and receive sounds with a preview and a
// volume, sounds for muted chats, and this browser's notifications for new messages, with a test.
// Notifications go through the service worker (sw.js) whenever there is one, since Android Chrome and the installed
// app can't show them any other way; elsewhere the page shows them itself. Either way they only come while
// FluidMessages is open in a tab or the desktop app: this server doesn't send push notifications.
// Loaded last; uses look.js (playTone, SEND_SOUNDS, RECEIVE_SOUNDS, lookChoice, lookSlider), chats.js, and app.js.

// Per browser, like the permission itself.
const NOTIFY_KEY = 'fluidNotify';
const NOTIFY_PREVIEW_KEY = 'fluidNotifyPreview';
const notifyOn = () => localStorage.getItem(NOTIFY_KEY) !== 'off';
const notifyPreview = () => localStorage.getItem(NOTIFY_PREVIEW_KEY) !== 'off';
const notifySupported = () => 'Notification' in window;

/** 'granted', 'denied', 'default', 'insecure' (plain HTTP on another computer), or 'unsupported'. */
function notifyPermission() {
  if (!window.isSecureContext) return 'insecure';
  if (!notifySupported()) return 'unsupported';
  return Notification.permission;
}

const NOTIFY_WORDS = {granted: 'Allowed', denied: 'Blocked', default: 'Not asked', insecure: 'Needs HTTPS', unsupported: 'Not available in this browser'};

function notifySummary() {
  const state = notifyPermission();
  return 'Notifications: ' + (state === 'granted' && !notifyOn() ? 'Off' : NOTIFY_WORDS[state]) + ' · send and receive sounds, volume';
}

/** Asks for permission. Call it only from a click: browsers ignore, or block for good, a prompt nobody asked for. */
async function askNotifyPermission() {
  if (notifyPermission() !== 'default') return notifyPermission();
  try { return await Notification.requestPermission(); } catch (error) {
    // Older Safari takes a callback instead of returning a promise.
    return new Promise((resolve) => Notification.requestPermission(resolve));
  }
}

/** The service worker's registration, once it's running, or null after a few seconds without one. */
async function notifyRegistration() {
  if (!('serviceWorker' in navigator)) return null;
  try {
    const now = await navigator.serviceWorker.getRegistration();
    if (now?.active) return now;
    return await Promise.race([navigator.serviceWorker.ready, new Promise((resolve) => setTimeout(() => resolve(null), 3000))]);
  } catch (error) { return null; }
}

/** What a notification says about [message]: its chat's name and, with previews on, the words, like the inbox. */
function notificationFor(message, {test = false} = {}) {
  const key = test ? '' : threadKey(message);
  const sealed = !test && typeof chatLocked === 'function' && chatLocked(key);
  let words = '';
  if (sealed || !notifyPreview()) words = 'New message';
  else if (/^FM1:/.test(message.body || '')) words = 'Encrypted message';
  else {
    const body = message.body || '';
    words = (typeof meetingPreview === 'function' ? meetingPreview(body) : null) || (typeof cardPreview === 'function' ? cardPreview(body) : null)
      || (typeof plainPreview === 'function' ? plainPreview(body) : body) || (message.attachment ? '📎 ' + (message.attachment.name || 'Attachment') : 'New message');
    if (message.author && (test || threadIsGroup(key))) words = message.author + ': ' + words;
    if (words.length > 180) words = words.slice(0, 179) + '…';
  }
  // A chat with a code doesn't even say whose it is.
  const title = sealed ? 'FluidMessages' : (test ? message.title + ' · test' : (message.title || threadTitle(key)));
  return {title, options: {body: words, tag: test ? 'fluid-test' : 'fluid-chat-' + notifyTag(key), renotify: true,
    icon: '/icons/icon-192.png', badge: '/icons/favicon-32.png', data: {chat: key}, timestamp: message.date || Date.now()}};
}

/** A short, stable tag for a chat, so one chat's notifications replace each other instead of piling up. */
function notifyTag(key) {
  let hash = 0;
  for (const char of key) hash = (hash * 31 + char.codePointAt(0)) >>> 0;
  return hash.toString(36);
}

/**
 * Shows a notification. Returns 'worker' or 'page' for how it was shown. Throws when the browser refuses.
 * [silent] is true when FluidMessages already played its own sound.
 */
async function showChatNotification({title, options}, silent = false) {
  const state = notifyPermission();
  if (state !== 'granted') throw new Error(state === 'insecure' ? 'Notifications need HTTPS.' : 'Notifications aren’t allowed in this browser.');
  const settings = {...options, silent};
  const registration = await notifyRegistration();
  if (registration?.showNotification) {
    await registration.showNotification(title, settings);
    return 'worker';
  }
  // No service worker (some private windows): the page shows it, and handles the click itself.
  const note = new Notification(title, settings);
  note.addEventListener('click', () => { note.close(); openNotifiedChat(settings.data?.chat || ''); });
  return 'page';
}

/** Whether you're looking at this chat right now, so a notification would only repeat what's on screen. */
function watchingChat(key) {
  return document.visibilityState === 'visible' && document.hasFocus() && section === 'messages' && active === key
    && !get('workspace').hidden && (document.getElementById('details-page')?.hidden ?? true);
}

/**
 * New incoming messages (look.js lookNoticeIncoming), after muted, blocked, and private chats are left out. Nothing
 * for the sample inbox or a decoy session, whose chats are pretend; nothing for the chat you're looking at.
 */
function notifyIncoming(messages, played) {
  if (!notifyOn() || notifyPermission() !== 'granted' || (typeof sampleOn !== 'undefined' && sampleOn)
    || (typeof decoyLike === 'function' && decoyLike())) return;
  // After the snapshot is drawn (app.js refresh), so chat names and the open chat are current.
  setTimeout(() => {
    const latest = new Map();
    const now = Date.now();
    for (const message of messages) {
      if (message.expires && message.expires <= now) continue;
      if (typeof privateFolderHas === 'function' && privateFolderHas(message.address)) continue;
      // Muted (for now, or by its server) or blocked: never a notification, whatever the caller passed.
      if (typeof chatMuted === 'function' && chatMuted(message.address)) continue;
      const key = threadKey(message);
      if (watchingChat(key)) continue;
      if (!latest.has(key) || latest.get(key).date < message.date) latest.set(key, message);
    }
    for (const message of latest.values()) showChatNotification(notificationFor(message), played).catch(() => {});
  }, 0);
}

let notifiedChat = '';
let notifiedTries = 0;

/** A notification was tapped: bring FluidMessages forward and open its chat once the inbox has it. */
function openNotifiedChat(chat) {
  try { window.focus(); } catch (error) { /* the browser decides */ }
  if (!chat) return;
  notifiedChat = chat;
  notifiedTries = 0;
  openNotifiedSoon();
}

function openNotifiedSoon() {
  const chat = notifiedChat;
  if (!chat) return;
  const ready = signedIn && !get('workspace').hidden && !(typeof sampleOn !== 'undefined' && sampleOn);
  if (ready && threads.has(chat)) {
    notifiedChat = '';
    if (section !== 'messages') showSection('messages');
    if (typeof closeDetailsPage === 'function') closeDetailsPage(false);
    select(chat);
    return;
  }
  // Just opened, or still signing in: try again for a little while.
  if (++notifiedTries < 40) setTimeout(openNotifiedSoon, 500); else notifiedChat = '';
}

if ('serviceWorker' in navigator) {
  navigator.serviceWorker.addEventListener('message', (event) => {
    if (event.data?.type === 'fluid-open-chat') openNotifiedChat(typeof event.data.chat === 'string' ? event.data.chat : '');
  });
}

// Settings → Sounds and notifications

function soundPicker(label, receive, options) {
  const key = receive ? 'receiveSound' : 'sendSound';
  const wrap = lookChoice(label, webLook[key] || 'fluid', options, (value) => { saveWebLook({...webLook, [key]: value}, true); playTone(receive, value); });
  const play = element('button', 'Play', 'quiet');
  play.type = 'button';
  play.setAttribute('aria-label', 'Play the ' + label.toLowerCase());
  play.addEventListener('click', () => {
    const choice = wrap.querySelector('select').value;
    if (choice === 'silent') { play.textContent = 'Silent'; setTimeout(() => { play.textContent = 'Play'; }, 1200); return; }
    playTone(receive, choice);
  });
  const row = element('div', '', 'sound-row');
  row.append(wrap, play);
  return row;
}

function soundsPage() {
  const sounds = settingsCard('Sounds', 'Made right here in your browser, so nothing is downloaded. Browsers stay quiet until you’ve clicked the page once.');
  const volume = lookSlider('Volume', soundVolume(), 0, 100, '%', (value) => saveWebLook({...webLook, soundVolume: value}));
  volume.querySelector('input').setAttribute('aria-label', 'Sound volume');
  // Hear the new level when you let go.
  volume.querySelector('input').addEventListener('change', () => playTone(true));
  sounds.append(
    soundPicker('Send sound', false, SEND_SOUNDS),
    soundPicker('Receive sound', true, RECEIVE_SOUNDS),
    volume,
    checkbox('Sounds for muted chats', !!webLook.mutedSounds, (value) => saveWebLook({...webLook, mutedSounds: value}, true),
      'Muted chats play the receive sound but still don’t show notifications. Blocked chats stay silent.'),
    element('p', 'Sounds play at this device’s media volume. The phone’s choice between media and notification volume doesn’t apply in a browser. '
      + 'Sounds stay the same when you switch themes.', 'muted'));

  const notes = settingsCard('Notifications');
  notes.id = 'settings-notifications';
  const state = element('p', '', 'notify-state');
  state.setAttribute('role', 'status');
  const allow = element('button', 'Allow', 'primary');
  allow.type = 'button';
  const explain = element('p', '', 'notice');
  const head = element('div', '', 'notify-head');
  head.append(state, allow);
  const toggles = element('div', '', 'stack');
  const enabled = checkbox('Notify me in this browser', notifyOn(), (value) => { localStorage.setItem(NOTIFY_KEY, value ? 'on' : 'off'); draw(); },
    'New messages, while you’re in another tab, another app, or another chat. Muted, blocked, and private chats never notify.');
  const preview = checkbox('Show message text in notifications', notifyPreview(), (value) => localStorage.setItem(NOTIFY_PREVIEW_KEY, value ? 'on' : 'off'),
    'Off shows only who it’s from. Chats with a code never show either.');
  toggles.append(enabled, preview);
  const test = element('button', 'Send a test notification');
  test.type = 'button';
  const tested = element('p', '', 'muted');
  tested.setAttribute('role', 'status');
  const draw = () => {
    const now = notifyPermission();
    state.replaceChildren(element('span', 'Notifications in this browser: '), element('strong', NOTIFY_WORDS[now]));
    state.dataset.state = now;
    allow.hidden = now !== 'default';
    test.disabled = now === 'insecure' || now === 'unsupported' || now === 'denied';
    for (const input of toggles.querySelectorAll('input')) input.disabled = now !== 'granted';
    preview.querySelector('input').disabled = now !== 'granted' || !notifyOn();
    explain.hidden = !['denied', 'insecure', 'unsupported'].includes(now);
    explain.textContent = {
      denied: 'This browser is set to block notifications from FluidMessages. To allow them, open the site settings (the icon beside the address), '
        + 'set Notifications to Allow, and reload. On a computer, also check that your system lets the browser show notifications.',
      insecure: 'This page is open over plain HTTP from another computer, and browsers only allow notifications on HTTPS or on the computer '
        + 'FluidMessages runs on. Open FluidMessages at its HTTPS address (see the self-hosting guide) to get notifications here.',
      unsupported: 'This browser can’t show notifications. On iPhone and iPad, add FluidMessages to your Home Screen first.',
    }[now] || '';
  };
  allow.addEventListener('click', async () => {
    await askNotifyPermission();
    draw();
  });
  test.addEventListener('click', async () => {
    tested.textContent = 'Sending…';
    if (notifyPermission() === 'default') await askNotifyPermission();
    draw();
    if (notifyPermission() !== 'granted') { tested.textContent = 'Notifications aren’t allowed, so nothing was sent.'; return; }
    // Fictional, and through the same code as a real one.
    const sample = {title: 'Maya Chen', author: '', body: 'Coffee at ten? ☕ This is a test from FluidMessages.', date: Date.now(), type: 1};
    try {
      const how = await showChatNotification(notificationFor(sample, {test: true}), playTone(true));
      tested.textContent = 'Sent. If nothing appeared, check that your system’s Do Not Disturb or Focus is off and that the browser may show notifications. '
        + (how === 'worker' ? '(Shown through the app’s service worker.)' : '(Shown by this page.)')
        + (notifyOn() ? '' : ' Notify me in this browser is off, so real messages won’t notify.');
    } catch (error) { tested.textContent = 'The browser didn’t show it: ' + error.message; }
  });
  // The permission can change in the browser's own settings while this page is open.
  navigator.permissions?.query({name: 'notifications'}).then((status) => { status.onchange = () => { if (notes.isConnected) draw(); }; }).catch(() => {});
  draw();
  const how = element('p', 'Notifications come while FluidMessages is open in a tab, even in the background, or in the desktop app. This server '
    + 'doesn’t send push notifications, so once every FluidMessages tab is closed nothing arrives here; your phone still notifies you '
    + 'through FluidMessages for Android. Browsers check background tabs less often, so a notification can come up to a minute late.', 'muted');
  notes.append(head, explain, toggles, test, tested, how);
  return [sounds, notes];
}
