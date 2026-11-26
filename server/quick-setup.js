'use strict';
// Settings → Privacy and security → Quick setup, like the phone's (QuickSetup.kt, QuickSetupPage.kt): a few named
// choices that set this web app's existing settings at once, each saying who it's for and what it doesn't protect.
// The choice shown is worked out by comparing the settings with each choice, so changing one by hand shows Custom.
// Also "Only send encrypted messages" in the composer (quick_setup.py refuses on the server too), and a one-line
// "Quick setup: Standard · Change" at the top of Privacy and security, Accounts, and Chat services.
// Loaded after settings.js, media.js, notify.js, and tor.js; uses their helpers.

// quick-setup:start (scripts/test-local runs this block on its own)
// The settings a choice can set here, with the defaults this web app reads them with. App lock, the private keyboard,
// and Nearby are on the phone only.
const QUICK_DEFAULTS = {allServices: true, callMatrix: true, callMeetings: true, sendGifs: true, encryptedOnly: false, tor: false,
  linkPreviews: true, gifs: true, notifyPreview: true, stripMetadata: true, gifSearch: 'tenor'};
const QUICK_ANYTHING = {encryptedOnly: false};
const QUICK_ENCRYPTED = {encryptedOnly: true};
const QUICK_NO_TOR = {tor: false};
const QUICK_TOR = {tor: true};
// Fewer leaks? No: the defaults for these; GIF search stays as it is. Yes: the extras that tell others about you, off.
const QUICK_CONVENIENCE = {linkPreviews: true, gifs: true, notifyPreview: true, stripMetadata: true};
const QUICK_FEWER = {linkPreviews: false, gifs: false, notifyPreview: false, stripMetadata: true, gifSearch: 'none'};
const QUICK_EVERYTHING = {allServices: true, callMatrix: true, callMeetings: true, sendGifs: true};

// The same names, order, and words as the phone (QuickSetup.CHOICES). phoneOnly: chosen on the phone, not here.
const QUICK_CHOICES = [
  {id: 'everything', title: 'Everything on', forWho: 'You want to reach anyone, anywhere, with every feature.',
    does: 'Turns on every chat service, calls, and extras. Nearby is on your phone, so here this looks the same as Standard.',
    doesnt: 'Plain texts, IRC, and Email can be read by the companies that carry them. People nearby can see your name and tag.',
    values: {...QUICK_ANYTHING, ...QUICK_NO_TOR, ...QUICK_CONVENIENCE, ...QUICK_EVERYTHING}},
  {id: 'standard', title: 'Standard', forWho: 'Most people.',
    does: 'The settings FluidMessages starts with. Your chat services and calls stay as you set them.',
    doesnt: 'Plain texts, IRC, and Email can be read by the companies that carry them.',
    values: {...QUICK_ANYTHING, ...QUICK_NO_TOR, ...QUICK_CONVENIENCE}},
  {id: 'lock', title: 'App lock + fewer leaks', forWho: 'You want more privacy but still want everything to work.',
    does: 'Turns off extras that tell others about you. App lock is on your phone; here, two-step sign-in does that job.',
    doesnt: 'Doesn’t encrypt anything; texts, IRC, and Email are still readable by those who carry them.',
    values: {...QUICK_ANYTHING, ...QUICK_NO_TOR, ...QUICK_FEWER}},
  {id: 'encrypted', title: 'Encrypted only', forWho: 'You don’t want to use Tor, but you only want to send messages nobody else can read.',
    does: 'Everything in App lock + fewer leaks, and sending is off in chats that can’t be encrypted right now.',
    doesnt: 'Servers can still see who you talk to and when.',
    values: {...QUICK_ENCRYPTED, ...QUICK_NO_TOR, ...QUICK_FEWER}},
  {id: 'tor', title: 'Encrypted + Tor', forWho: 'Journalists, activists, or anyone who worries about being watched or tracked.',
    does: 'Everything in Encrypted only, and this server’s connections go through Tor.',
    doesnt: 'Your internet company still sees this server use Tor; Tor is slower.',
    values: {...QUICK_ENCRYPTED, ...QUICK_TOR, ...QUICK_FEWER}},
  {id: 'nearby', title: 'Nearby only', forWho: 'Blackouts or places with no internet.', phoneOnly: true,
    does: 'Your phone stops using the internet and only talks to phones close by. Choose it on your phone.',
    doesnt: 'People nearby can see your name and tag.', values: {}},
];
// Standard is checked before Everything on here: without Nearby, the two set the same things on this web app.
const QUICK_MATCH_ORDER = ['standard', 'everything', 'lock', 'encrypted', 'tor'];
const QUICK_CUSTOM = 'custom';

const quickValue = (values, key) => (values[key] === undefined ? QUICK_DEFAULTS[key] : values[key]);
const quickChoice = (id) => QUICK_CHOICES.find((choice) => choice.id === id);
const quickMatches = (choice, values) => Object.entries(choice.values).every(([key, want]) => quickValue(values, key) === want);

/** The choice the settings match, or 'custom'. */
function quickCurrent(values) {
  return QUICK_MATCH_ORDER.find((id) => quickMatches(quickChoice(id), values)) || QUICK_CUSTOM;
}

const quickTitle = (id) => quickChoice(id)?.title || 'Custom';

/** The three questions' answers. fewerLeaks is null when some extras are on and some off. */
function quickAnswers(values) {
  const all = (set) => Object.entries(set).every(([key, want]) => quickValue(values, key) === want);
  return {send: quickValue(values, 'encryptedOnly') ? 'encrypted' : 'anything', tor: quickValue(values, 'tor') === true,
    fewerLeaks: all(QUICK_FEWER) ? true : all(QUICK_CONVENIENCE) ? false : null};
}

const QUICK_NEEDS_TOR = 'No Tor proxy answers for this server yet, so Connect through Tor stays off. Start Tor, then choose this again.';
const QUICK_TOR_NOTE = 'Tor makes connections slower, and some servers block it. While Tor is on, encrypted Matrix rooms can’t be used from '
  + 'FluidMessages Web and XMPP waits. Your phone has its own Tor setting.';
const QUICK_TWO_STEP = 'Two-step sign-in is off. Turn it on in Settings → Phone and other devices, so a stolen password alone can’t get in.';

/** What a choice changes here: only the settings that differ, what's left to do, and what's worth knowing. */
function quickPlan(values, ready, wanted) {
  const want = {...wanted};
  const steps = [];
  const notes = [];
  if (want.tor === true && !quickValue(values, 'tor') && !ready.tor) { delete want.tor; steps.push(QUICK_NEEDS_TOR); }
  if (want.tor === true) notes.push(QUICK_TOR_NOTE);
  if (want.linkPreviews === false && ready.twoStep === false) notes.push(QUICK_TWO_STEP);
  const set = Object.fromEntries(Object.entries(want).filter(([key, value]) => quickValue(values, key) !== value));
  return {set, steps, notes};
}

/** What some settings are, in plain words, one line each. */
function quickLines(values) {
  const lines = [];
  const say = (key, on, off) => { if (values[key] === true && on) lines.push(on); else if (values[key] === false && off) lines.push(off); };
  say('allServices', 'Every chat service on: Matrix, XMPP, Email, and IRC. Each one still needs an account to connect.', '');
  if (values.callMatrix === true && values.callMeetings === true) lines.push('Calls on: Matrix and meeting links.');
  say('sendGifs', 'Sending GIFs on.', '');
  say('encryptedOnly', 'Only send encrypted messages on: sending is off in chats that can’t be encrypted right now. You can still read everything.',
    'Messages send whether or not they’re encrypted.');
  say('tor', 'Connect through Tor on: chat servers don’t see this server’s internet address.', 'Connect through Tor off.');
  say('linkPreviews', 'Link previews on. Links from people you don’t know still wait for a tap.', 'Link previews off.');
  say('gifs', 'Pictures and GIFs from links show by themselves.', 'Pictures and GIFs from links off: you see the link instead.');
  say('notifyPreview', 'Notifications in this browser show names and message text.', 'Notifications in this browser say only “New message”.');
  if (values.gifSearch === 'none') lines.push('GIF search off, so nothing you type goes to Tenor or GIPHY.');
  say('stripMetadata', 'Location and camera details are removed from photos you send.', '');
  return lines;
}

/** Why sending is off in this chat, or ''. The same words as quick_setup.py; receiving and reading are never blocked. */
function quickRefusal(on, protocol, encrypted) {
  if (!on || encrypted) return '';
  const start = 'Only send encrypted messages is on. ';
  switch (protocol) {
    case 'irc': return start + 'IRC can’t encrypt messages, so sending here is off.';
    case 'email': return start + 'Email isn’t encrypted end to end, so sending here is off.';
    case 'matrix': return start + 'This Matrix room isn’t encrypted, so sending here is off. A room admin can turn on encryption.';
    case 'xmpp': return start + 'This XMPP chat isn’t encrypted with OMEMO right now, so sending here is off. Check OMEMO in this chat’s details.';
    default: return start + 'Texts sent from FluidMessages Web aren’t encrypted, so sending here is off. Send it from your phone with '
      + 'FluidMessages encryption on.';
  }
}

/** The mark beside a service in Accounts and Chat services while the switch is on, or ''. */
function quickServiceMark(on, protocol) {
  if (!on) return '';
  return {matrix: 'Encrypted rooms only', xmpp: 'OMEMO chats only'}[protocol] || 'Can’t encrypt';
}
// quick-setup:end

const QUICK_SERVICES = ['matrix', 'xmpp', 'email', 'irc'];

/** This web app's settings that Quick setup reads, and whether Tor and two-step sign-in are ready. */
async function quickSetupState() {
  let tor = {on: false, reachable: false};
  let twoStep;
  try { tor = await api('tor'); } catch (error) { /* shown on the Tor page */ }
  try { twoStep = (await api('auth/security')).two_step?.on; } catch (error) { twoStep = undefined; }
  const values = {
    allServices: QUICK_SERVICES.every((protocol) => gatewayEnabled.includes(protocol)),
    callMatrix: prefs.callMatrix !== false, callMeetings: prefs.callMeetings !== false, sendGifs: prefs.sendGifs !== false,
    encryptedOnly: prefs.encryptedOnly === true, tor: !!tor.on, linkPreviews: prefs.linkPreviews !== false, gifs: prefs.gifs !== false,
    notifyPreview: typeof notifyPreview === 'function' ? notifyPreview() : true,
    stripMetadata: typeof stripDetails === 'function' ? stripDetails() : true,
    gifSearch: typeof gifSearchSource === 'function' ? gifSearchSource() : (localStorage.getItem('gifSource') || 'tenor')};
  return {values, ready: {tor: !!tor.reachable, twoStep}};
}

async function quickSetupApply(set) {
  const server = {};
  for (const key of ['callMatrix', 'callMeetings', 'sendGifs', 'encryptedOnly', 'linkPreviews', 'gifs']) if (key in set) server[key] = set[key];
  if (Object.keys(server).length) {
    companionSettings = await api('settings', {prefs: server});
    prefs = {...prefs, ...server};
  }
  if ('notifyPreview' in set) localStorage.setItem('fluidNotifyPreview', set.notifyPreview ? 'on' : 'off');
  if ('stripMetadata' in set) localStorage.setItem('stripMetadata', set.stripMetadata ? 'on' : 'off');
  if ('gifSearch' in set) localStorage.setItem('gifSource', set.gifSearch);
  if (set.allServices === true) {
    const result = await api('protocols', {enabled: QUICK_SERVICES});
    if (Array.isArray(result?.enabled)) gatewayEnabled = result.enabled; else gatewayEnabled = [...QUICK_SERVICES];
  }
  // Choosing Encrypted + Tor is the "I understand" for Tor: its summary says Tor is slower first.
  if ('tor' in set) await api('tor', set.tor ? {on: true, understood: true} : {on: false});
  showMessages(true);
  if (typeof updateCallButtons === 'function') updateCallButtons();
}

/** The short summary before saving, in place: what changes, what's left, and Use this setup. */
function quickSummary(state, wanted, close, rerender) {
  const plan = quickPlan(state.values, state.ready, wanted);
  const box = element('div', '', 'notice quick-summary');
  const changes = quickLines(plan.set);
  if (!changes.length && !plan.steps.length) box.append(element('p', 'You’re already using these settings.'));
  else {
    box.append(element('strong', changes.length ? 'This changes:' : 'Nothing changes yet.'));
    const list = element('ul', '');
    for (const line of changes) list.append(element('li', line));
    box.append(list);
  }
  for (const step of plan.steps) box.append(element('p', step, 'error'));
  for (const note of plan.notes) box.append(element('p', note, 'muted'));
  const problem = errorLine();
  const cancel = element('button', 'Cancel');
  cancel.type = 'button';
  cancel.addEventListener('click', close);
  box.append(cancel);
  if (Object.keys(plan.set).length) {
    const use = element('button', 'Use this setup', 'primary');
    use.type = 'button';
    use.addEventListener('click', async () => {
      use.disabled = true;
      try { await quickSetupApply(plan.set); rerender(); } catch (error) { problem.textContent = error.message; use.disabled = false; }
    });
    box.append(use);
  }
  box.append(problem);
  return box;
}

async function quickSetupPage(context) {
  const state = await quickSetupState();
  const current = quickCurrent(state.values);
  const rerender = () => context?.rerender ? context.rerender() : openSettings('quick-setup').catch(showError);
  const intro = settingsCard('', 'Pick what fits you. Each choice only changes settings you could change yourself, and you can change any of them '
    + 'later. If you do, this shows Custom. These are FluidMessages Web’s settings; your phone has its own Quick setup.');
  intro.append(element('strong', 'Now: ' + quickTitle(current) + (current === QUICK_CUSTOM ? ' · You changed some of these settings yourself.' : '')));
  const cards = [intro];
  let openBox = null;
  const toggle = (holder, make) => {
    if (openBox) { openBox.remove(); const was = openBox.parentElement === holder; openBox = null; if (was) return; }
    openBox = make();
    holder.append(openBox);
  };
  for (const choice of QUICK_CHOICES) {
    const card = settingsCard(choice.title + (choice.id === current ? ' · In use' : ''));
    card.classList.add('quick-choice');
    card.id = 'quick-' + choice.id;
    card.append(element('p', 'For: ' + choice.forWho), element('p', choice.does, 'muted'),
      element('p', 'What this doesn’t protect: ' + choice.doesnt, 'muted'));
    const lines = quickLines(choice.values);
    if (lines.length) {
      const list = element('ul', '');
      for (const line of lines) list.append(element('li', line));
      card.append(list);
    }
    if (!choice.phoneOnly) {
      const holder = element('div', '');
      const see = element('button', choice.id === current ? 'See what it sets' : 'Choose ' + choice.title);
      see.type = 'button';
      see.addEventListener('click', () => toggle(holder, () => quickSummary(state, choice.values, () => { openBox?.remove(); openBox = null; }, rerender)));
      card.append(see, holder);
    }
    cards.push(card);
  }
  const answers = quickAnswers(state.values);
  const questions = settingsCard('Or answer these');
  const question = (title, name, options, selected, values) => {
    const set = element('fieldset', '', 'stack');
    set.append(element('legend', title));
    const holder = element('div', '');
    options.forEach((label, index) => {
      const input = Object.assign(element('input', ''), {type: 'radio', name: 'quick-' + name, checked: index === selected});
      const wrap = element('label', '', 'check');
      wrap.append(input, element('span', label));
      input.addEventListener('change', () => toggle(holder, () => quickSummary(state, values(index), () => {
        openBox?.remove(); openBox = null;
        set.querySelectorAll('input').forEach((item, at) => { item.checked = at === selected; });
      }, rerender)));
      set.append(wrap);
    });
    set.append(holder);
    return set;
  };
  questions.append(
    question('What can you send?', 'send', ['Anything', 'Encrypted messages only', 'Nearby only (on your phone)'],
      answers.send === 'encrypted' ? 1 : 0, (index) => (index === 1 ? QUICK_ENCRYPTED : QUICK_ANYTHING)),
    question('Connect through Tor?', 'tor', ['No', 'Yes'], answers.tor ? 1 : 0, (index) => (index === 1 ? QUICK_TOR : QUICK_NO_TOR)),
    question('Fewer leaks?', 'leaks', ['No', 'Yes'], answers.fewerLeaks === null ? -1 : answers.fewerLeaks ? 1 : 0,
      (index) => (index === 1 ? QUICK_FEWER : QUICK_CONVENIENCE)));
  // Nearby only is set on the phone; choosing it here only says so.
  questions.querySelectorAll('input[name="quick-send"]')[2].disabled = true;
  if (answers.fewerLeaks === null) questions.append(element('p', 'Some of these extras are on and some are off.', 'muted'));
  cards.push(questions);
  return cards;
}

SETTINGS_SUBPAGES.push({id: 'quick-setup', icon: 'privacy', title: 'Quick setup', parent: 'privacy', page: (settings, context) => quickSetupPage(context)});

/** "Quick setup: Standard · Change", at the top of Privacy and security, Accounts, and Chat services. */
async function quickSetupRow() {
  const {values} = await quickSetupState();
  const card = settingsCard('');
  card.id = 'quick-setup-row';
  card.append(settingsRow({...settingsItem('quick-setup'), title: 'Quick setup: ' + quickTitle(quickCurrent(values)), summary: 'Change'}, {}, ''));
  return card;
}

for (const id of ['privacy', 'accounts', 'ways']) {
  const item = settingsItem(id);
  if (!item?.page) continue;
  const base = item.page;
  item.page = async (settings, context) => {
    const cards = await base(settings, context);
    const extra = [await quickSetupRow()];
    if (id === 'privacy') {
      // Only send encrypted messages, beside the other Messages switches.
      const card = settingsCard('Sending');
      card.append(checkbox('Only send encrypted messages', prefs.encryptedOnly === true, async (value) => {
        try { companionSettings = await api('settings', {prefs: {encryptedOnly: value}}); prefs = {...prefs, encryptedOnly: value}; updateCallButtons(); }
        catch (error) { showError(error); }
      }, 'Sending is off in chats that can’t be encrypted right now, like IRC, Email, or texts from this browser. You can still read everything.'));
      extra.push(card);
    } else if (prefs.encryptedOnly === true) {
      const marks = QUICK_SERVICES.map((protocol) => (labels[protocol] || protocol) + ': ' + quickServiceMark(true, protocol)).join(' · ');
      extra.push(Object.assign(element('p', 'Only send encrypted messages is on. ' + marks + '.', 'notice'), {id: 'quick-service-marks'}));
    }
    return [...extra, ...(Array.isArray(cards) ? cards : [cards])];
  };
}

// The composer: while Only send encrypted messages is on, a chat that can't be encrypted right now shows why instead
// of the message box, with a link to Quick setup.
function quickComposerRefusal() {
  if (!active || (typeof sampleOn !== 'undefined' && sampleOn)) return '';
  const protocol = threadProtocol(active);
  return quickRefusal(prefs.encryptedOnly === true, protocol, internet(protocol) && routeFor(active)?.encrypted === true);
}

function quickUpdateComposer() {
  const form = get('schedule');
  let note = document.getElementById('encrypted-only');
  if (!note) {
    note = element('p', '', 'notice');
    note.id = 'encrypted-only';
    note.setAttribute('role', 'status');
    form.prepend(note);
  }
  const reason = quickComposerRefusal();
  note.hidden = !reason;
  form.querySelector('.compose-row').hidden = !!reason;
  get('later').hidden = !!reason;
  if (!reason) return;
  const link = element('a', 'Change quick setup');
  link.href = '#settings/quick-setup';
  link.addEventListener('click', (event) => { event.preventDefault(); openSettings('quick-setup').catch(showError); });
  note.replaceChildren(element('span', reason + ' '), link);
}

{
  const callButtonsBase = updateCallButtons;
  updateCallButtons = function updateCallButtonsAndEncryptedOnly() { callButtonsBase(); quickUpdateComposer(); };
  // The server refuses too (quick_setup.py); this says why before anything is sent.
  const sendBase = send;
  send = async function sendEncryptedOnly(...args) {
    const reason = quickComposerRefusal();
    if (reason) throw new Error(reason);
    return sendBase(...args);
  };
  const queueBase = queueSms;
  queueSms = async function queueSmsEncryptedOnly(...args) {
    if (prefs.encryptedOnly === true && !(typeof sampleOn !== 'undefined' && sampleOn)) throw new Error(quickRefusal(true, 'sms', false));
    return queueBase(...args);
  };
}
