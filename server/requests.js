'use strict';
// Message requests and automated senders, the same rules as the phone (MessageRequests.kt).
//
// A request is an incoming one-to-one chat from someone you don't know. It stays out of the inbox and the Chats
// count and shows under the inbox filter's Requests, with Accept, Delete, Block, and Block and report spam at the top
// of the chat. Replying accepts it too. What you decide is kept per address in web_chats.requests ("accepted", or
// "deleted:<ms>") and the settings in prefs; both reach the phone through the merge (message_requests.py).
// Automated senders (short codes and sender IDs, or a chat's own Sender type) show under Automated, and their own
// settings can keep them out of the inbox, silence them, or block them.
// Loaded after app.js, chats.js, people.js, settings.js, and inbox-filters.js, and uses their helpers.

const REQUEST_DEFAULTS = {requestTexts: false, requestChats: true, requestNotify: 'quiet', automatedOut: false, automatedQuiet: false,
  automatedBlock: false};
// NickServ and friends, bouncer control nicks (*status), and servers: never a request (message_requests.py).
const SERVICE_NICK = /^((nick|chan|memo|oper|host|bot|help|info|stat|sasl|alis|global|group|hist|op)serv|global|\*.*)$/i;

/** The request settings in force: this server's, shared with the phone. The sample inbox filters unknown texts. */
function requestSettings() {
  const own = {...REQUEST_DEFAULTS};
  for (const key of Object.keys(REQUEST_DEFAULTS)) {
    const value = (typeof prefs === 'object' && prefs) ? prefs[key] : undefined;
    if (typeof value === typeof REQUEST_DEFAULTS[key]) own[key] = value;
  }
  if (own.requestNotify !== 'off') own.requestNotify = 'quiet';
  // The sample shows what a request from a new number looks like, whatever the real setting.
  if (typeof sampleOn !== 'undefined' && sampleOn) own.requestTexts = true;
  return own;
}

/** Network services and servers, which talk to you without being people. */
function requestNetworkService(protocol, name) {
  const value = String(name || '');
  if (!value) return true;
  if (protocol === 'irc') return SERVICE_NICK.test(value) || value.includes('.');
  if (protocol === 'xmpp') return !value.includes('@');
  if (protocol === 'matrix') {
    const local = value.replace(/^@/, '').split(':')[0].toLowerCase();
    return !value.includes(':') || ['server', 'notices', 'server-notices', '_server'].includes(local);
  }
  if (protocol === 'email') return /^(mailer-daemon|postmaster|mail-daemon)@/i.test(value);
  return false;
}

/**
 * The rule, on plain facts so it can be tested (scripts/test-local, and MessageRequestsTest.kt with the same cases):
 * {service: sms|matrix|xmpp|irc|email, group, invite, contact, favorite, sent, decision, automated, networkService,
 * stranger (an internet chat that wrote first rather than one you linked or joined)}.
 */
function isRequest(facts, settings = REQUEST_DEFAULTS) {
  if (facts.decision === 'accepted') return false;
  if (facts.contact || facts.favorite || facts.sent) return false;
  if (facts.service === 'sms') return !!settings.requestTexts && !facts.group && !facts.automated;
  if (!settings.requestChats || facts.networkService) return false;
  if (facts.invite) return true;
  return !facts.group && !!facts.stranger;
}

/** When a request was deleted, the time; everything up to it stays gone. */
function requestDeletedAt(decision) {
  const match = /^deleted:(\d{1,15})$/.exec(decision || '');
  return match ? Number(match[1]) : 0;
}

const requestDecisions = () => (typeof chatState === 'function' ? chatState().requests : null) || {};

/** A web contact with this number or chat address. */
function requestKnownContact(address, target) {
  const list = typeof webContactList === 'function' ? webContactList() : [];
  return list.some((contact) => (contact.numbers || []).some((number) => typeof sameNumber === 'function' && sameNumber(number, address))
    || (contact.addresses || []).some((item) => [address, target].some((value) => value && String(item.address).toLowerCase() === String(value).toLowerCase())));
}

function requestFacts(key, messages = threads.get(key) || []) {
  const address = threadAddress(key);
  const protocol = family(threadProtocol(key));
  const route = typeof routeFor === 'function' && typeof routes !== 'undefined' ? routeFor(key) : null;
  const internetChat = protocol !== 'sms';
  const sampleStranger = typeof sampleOn !== 'undefined' && sampleOn && typeof SAMPLE_REQUEST !== 'undefined' && address === SAMPLE_REQUEST.address;
  return {
    service: protocol,
    group: threadIsGroup(key),
    invite: !!route?.invite,
    // A text from a number the phone has a name for, or a web contact.
    contact: !sampleStranger && (internetChat ? false : messages.some((row) => row.title)) || requestKnownContact(address, route?.target),
    favorite: typeof favoriteChat === 'function' && favoriteChat(key),
    sent: messages.some((row) => row.type !== 1 && !row.event),
    decision: requestDecisions()[address] || '',
    automated: typeof automatedThread === 'function' && automatedThread(key),
    networkService: internetChat && requestNetworkService(protocol, route?.inviter || route?.target || address),
    stranger: !!(route?.request || route?.invite),
  };
}

/** Whether a chat is a request right now. */
function chatIsRequest(key, messages) {
  return isRequest(requestFacts(key, messages), requestSettings());
}

/** Where a chat goes, for the inbox filters (inbox-filters.js inboxFilterPlaces). */
function requestPlace(key, messages) {
  const settings = requestSettings();
  const facts = requestFacts(key, messages);
  const newest = Math.max(0, ...(messages || []).map((row) => typeof timeOf === 'function' ? timeOf(row) : row.date || 0));
  return {
    request: isRequest(facts, settings),
    automated: facts.automated,
    automatedOut: facts.automated && settings.automatedOut,
    automatedBlocked: facts.automated && settings.automatedBlock,
    // Deleted, and nothing new since.
    dismissed: !!messages?.length && requestDeletedAt(facts.decision) >= newest,
  };
}

/** Addresses that never count on the Chats tab: requests, and automated chats kept out of the inbox or blocked. */
function requestQuietAddresses() {
  const quiet = new Set();
  if (typeof threads === 'undefined') return quiet;
  for (const [key, messages] of threads) {
    const place = requestPlace(key, messages);
    if (place.request || place.automatedOut || place.automatedBlocked || place.dismissed) quiet.add(threadAddress(key));
  }
  return quiet;
}

/** How a new message alerts: 'loud', 'quiet' (no sound), or 'none'. */
function requestAlert(message) {
  if (typeof threadKey !== 'function') return 'loud';
  const key = threadKey(message);
  const place = requestPlace(key, threads.get(key) || [message]);
  const settings = requestSettings();
  if (place.dismissed || place.automatedBlocked) return 'none';
  if (place.automated && settings.automatedQuiet) return 'none';
  if (place.request) return settings.requestNotify === 'off' ? 'none' : 'quiet';
  return 'loud';
}

// Actions

/** Accept, delete, block, or report, here and on the server (or only on this page in the sample inbox). */
async function requestAct(action, key, extra = {}) {
  const address = threadAddress(key);
  if (typeof sampleOn !== 'undefined' && sampleOn) {
    const state = chatState();
    const decisions = {...(state.requests || {})};
    const blocked = (state.blocked || []).filter((item) => item !== address);
    if (action === 'accept') decisions[address] = 'accepted';
    if (action === 'delete') {
      decisions[address] = 'deleted:' + Date.now();
      inbox = inbox.filter((row) => row.address !== address);
      rebuildThreads();
    }
    if (action === 'block' || action === 'report') { delete decisions[address]; blocked.push(address); }
    sampleChats = {...state, blocked, requests: decisions};
    return {reported: false};
  }
  const result = await api('requests/' + action, {address, ...extra});
  if (result.web_chats) webChats = {...webChats, ...result.web_chats};
  return result;
}

function requestLeaveChat() {
  active = '';
  delete get('workspace').dataset.thread;
  if (typeof showMessages === 'function') showMessages();
}

/** Replying to a request accepts it, so the next message isn't a request either (app.js send). */
function acceptOnReply(key) {
  if (!key || !chatIsRequest(key)) return;
  requestAct('accept', key).then(() => { if (typeof showThreads === 'function') showThreads(); }).catch(() => {});
}

/** Delete, as a full page: what goes, and that it can't be undone. */
function confirmRequestDelete(key, done) {
  const name = threadTitle(key);
  const go = element('button', 'Delete', 'danger-button');
  go.type = 'button';
  go.addEventListener('click', async () => {
    go.disabled = true;
    try {
      await requestAct('delete', key);
      closeDetailsPage(false);
      requestLeaveChat();
      say('Request deleted.');
      done();
    } catch (error) { go.disabled = false; showError(error); }
  });
  const cancel = element('button', 'Cancel', 'quiet');
  cancel.type = 'button';
  cancel.addEventListener('click', () => closeDetailsPage());
  const text = element('p', 'The messages from ' + name + ' are removed from FluidMessages Web and the chat leaves Requests. '
    + 'Texts stay on your phone until you delete them there. If they write again, it’s a new request. This can’t be undone.', 'muted');
  const actions = element('div', '', 'request-page-actions');
  actions.append(go, cancel);
  detailsPage('Delete request?', [text, actions]);
}

/**
 * The bar at the top of a request chat (app.js showMessages), like Signal's: who it's from, then Accept (with Add to
 * contacts), Delete, Block, and Block and report spam. Matrix and XMPP invitations accept or decline the invite.
 */
function requestBanner(conversation, key) {
  if (!key) return;
  const messages = threads.get(key) || [];
  const facts = requestFacts(key, messages);
  if (!(isRequest(facts, requestSettings()) || facts.invite && facts.decision !== 'accepted')) return;
  const name = threadTitle(key);
  const route = typeof routeFor === 'function' && typeof routes !== 'undefined' ? routeFor(key) : null;
  const box = element('section', '', 'request-banner');
  box.setAttribute('aria-label', 'Message request');
  const heading = facts.invite ? (route?.inviter || name) + (facts.group ? ' invited you to ' + name : ' wants to chat') : name + ' isn’t in your contacts';
  box.append(element('strong', heading), element('p', facts.invite
    ? 'Accept to join and reply. Decline and they won’t know whether you saw it.'
    : 'They won’t know you’ve seen this until you reply or accept. ' + (facts.service === 'sms'
      ? 'Be careful with links and codes from numbers you don’t know.' : 'Replying accepts the request.'), 'muted'));
  const save = Object.assign(element('input', ''), {type: 'checkbox', id: 'request-add-contact'});
  const label = element('label', '', 'check request-contact');
  label.append(save, element('span', 'Add to contacts'));
  const row = element('div', '', 'request-actions');
  const button = (text, className, run) => {
    const item = element('button', text, className);
    item.type = 'button';
    item.addEventListener('click', async () => {
      for (const other of row.querySelectorAll('button')) other.disabled = true;
      try { await run(); } catch (error) { showError(error); }
      for (const other of row.querySelectorAll('button')) other.disabled = false;
    });
    return item;
  };
  const redraw = () => { showThreads(); showMessages(); };
  row.append(
    button(facts.invite ? 'Accept invite' : 'Accept', 'primary', async () => {
      await requestAct('accept', key, {contact: save.checked, name});
      say(name + ' moved to your inbox.');
      redraw();
    }),
    button(facts.invite ? 'Decline' : 'Delete', 'quiet', async () => confirmRequestDelete(key, redraw)),
    button('Block', 'quiet', async () => {
      await requestAct('block', key);
      say(name + ' is blocked.');
      requestLeaveChat();
      redraw();
    }),
    button('Block and report spam', 'quiet danger-text', async () => {
      const result = await requestAct('report', key);
      say(result.reported ? name + ' is blocked and reported to the homeserver.'
        : name + ' is blocked.' + (facts.service === 'sms' ? ' Your phone blocks the number too once it syncs.' : ''));
      requestLeaveChat();
      redraw();
    }));
  box.append(label, row);
  conversation.prepend(box);
}

// Settings → Privacy and security → Requests and spam

function requestSettingsLink() {
  const card = settingsCard('Requests and spam');
  const settings = requestSettings();
  card.append(settingsLink('requests', 'Unknown texts ' + (settings.requestTexts ? 'filtered' : 'in the inbox') + ' · chat services '
    + (settings.requestChats ? 'filtered' : 'in the inbox') + ' · automated senders'));
  return card;
}

function requestSettingsPage() {
  const settings = requestSettings();
  const save = async (change, status) => {
    prefs = {...prefs, ...change};
    if (typeof showThreads === 'function') showThreads();
    try {
      companionSettings = await api('settings', {prefs: change});
      status.textContent = 'Saved. Your phone follows the next time it syncs.';
    } catch (error) { showError(error); }
  };
  const card = settingsCard('Message requests', 'Chats from people you don’t know wait under Requests in the inbox filter menu, out of '
    + 'your inbox and the Chats count. Open one to accept, delete, block, or report it. Replying accepts it. Shared with your phone.');
  card.id = 'settings-requests';
  const status = element('p', '', 'muted');
  status.setAttribute('role', 'status');
  card.append(
    checkbox('Filter unknown senders (texts)', settings.requestTexts, (value) => save({requestTexts: value}, status),
      'Texts from numbers that aren’t in your contacts go to Requests. Off by default, so delivery and sign-in codes are never missed.'),
    checkbox('Filter unknown senders (chat services)', settings.requestChats, (value) => save({requestChats: value}, status),
      'Matrix and XMPP invitations, and Matrix, XMPP, IRC, and email messages from people who aren’t in your contacts or favorites and '
      + 'whom you’ve never messaged. Network services like NickServ, and channels and rooms you joined, are never requests.'));
  const notify = element('select', '');
  notify.id = 'request-notify';
  for (const [value, text] of [['quiet', 'Quiet: no sound or vibration'], ['off', 'Off: no notification']]) {
    notify.append(Object.assign(element('option', text), {value}));
  }
  notify.value = settings.requestNotify;
  notify.addEventListener('change', () => save({requestNotify: notify.value}, status));
  card.append(field('Request notifications', notify), status);
  const automated = settingsCard('Automated messages', 'Short codes and sender IDs, like delivery updates and sign-in codes, or any chat whose '
    + 'Sender type is set to Automated sender. They’re always under the Automated filter.');
  automated.id = 'settings-automated';
  const warning = element('p', 'Sign-in codes, bank alerts, and delivery updates come from automated senders. Blocking them means you won’t '
    + 'see those messages in your inbox or get notified.', 'notice');
  warning.hidden = !settings.automatedBlock;
  const astatus = element('p', '', 'muted');
  astatus.setAttribute('role', 'status');
  automated.append(
    checkbox('Keep automated messages out of the inbox', settings.automatedOut, (value) => save({automatedOut: value}, astatus),
      'They show only under Automated and don’t count on the Chats tab.'),
    checkbox('Don’t notify for automated messages', settings.automatedQuiet, (value) => save({automatedQuiet: value}, astatus),
      'They still arrive and show unread.'),
    checkbox('Block automated senders', settings.automatedBlock, (value) => { warning.hidden = !value; save({automatedBlock: value}, astatus); },
      'They go to Blocked, without notifications.'),
    warning, astatus);
  return [card, automated];
}

SETTINGS_SUBPAGES.push({id: 'requests', icon: 'privacy', title: 'Requests and spam', parent: 'privacy', page: () => requestSettingsPage()});

// The sample inbox: one request from a new number. Its Automated filter has Trail Alerts.
const SAMPLE_REQUEST = {address: '+12025550177', body: 'Hi! Is this the bike repair co-op? I found this number on the library flyer.'};

function sampleRequestRows(add) {
  add(SAMPLE_REQUEST.address, 'sms', '', SAMPLE_REQUEST.body, 1, 20, '', 0);
}
