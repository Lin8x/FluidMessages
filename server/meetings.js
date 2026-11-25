'use strict';

// Meeting links: a call anyone can join by opening a link. You pick the service each time you send one. Zoom is first
// and the default; Zoom, Google Meet, Microsoft Teams, and Jitsi Meet are all on at first, and each can be turned off in
// Settings → Calls → Meeting links. FluidMessages has no account with Zoom, Google, or Microsoft, so for those it sends
// a link you saved yourself. Only Jitsi Meet makes a new room on the spot. You can also add your own service: a name
// and a saved https link. The phone has the same list, checks, and wording (MeetingServices.kt), and the server checks
// what is saved (main.py clean_meetings).

// meetings:start (plain rules with no page in them; scripts/test-local runs this part in Node)
const MEETING_SERVICES = [
  {id: 'zoom', label: 'Zoom', short: 'Zoom', company: 'Zoom', on: true, home: 'https://zoom.us/profile', example: 'https://zoom.us/j/1234567890',
    steps: ['Open Zoom with the button below and sign in.', 'On your Profile page, find Personal Meeting ID and copy the link under it.',
      'Come back here and paste the link.']},
  {id: 'meet', label: 'Google Meet', short: 'Google Meet', company: 'Google', on: true, home: 'https://meet.google.com/', example: 'https://meet.google.com/abc-defg-hij',
    steps: ['Open Google Meet with the button below and sign in.', 'Choose New meeting, then Create a meeting for later, and copy the link.',
      'Come back here and paste the link.']},
  {id: 'teams', label: 'Microsoft Teams', short: 'Teams', company: 'Microsoft', on: true, home: 'https://teams.microsoft.com/', example: 'https://teams.microsoft.com/meet/1234567890',
    steps: ['Open Microsoft Teams with the button below and sign in.', 'Choose Meet, then Create a meeting link, and copy the link.',
      'Come back here and paste the link.']},
  {id: 'jitsi', label: 'Jitsi Meet', short: 'Jitsi Meet', company: '', on: true, home: 'https://meet.jit.si/', example: 'https://meet.jit.si/YourRoom', steps: []}];
const MEETING_DEFAULT_JITSI = 'meet.jit.si';
const MEETING_LINK = {
  zoom: /^https:\/\/(?:[a-z0-9-]+\.)*zoom\.us\/(?:j\/\d{9,11}|my\/[A-Za-z0-9._-]{1,64})(?:[?#]\S*)?$/i,
  meet: /^https:\/\/meet\.google\.com\/[a-z]{3}-[a-z]{4}-[a-z]{3}(?:[?#]\S*)?$/i,
  teams: /^https:\/\/teams\.(?:microsoft|live)\.com\/(?:l\/meetup-join\/\S+|meet\/[A-Za-z0-9]+(?:[?#]\S*)?)$/i};
// A room on any server, which is how a Jitsi room on someone's own server looks (and what older versions sent).
const MEETING_ROOM = /^https:\/\/[A-Za-z0-9.-]+(?::\d+)?\/[A-Za-z0-9_-]+$/;
// Sent as plain text so every app can open it: "📹 Join my Zoom video call: https://…", or "📹 Join my Work video
// call: https://…" for a service you added. Jitsi has no service name, as before. Groups: the emoji, the name, "video ", the link.
const MEETING = /^(📹|📞)?\s*Join my (?:(\S.{0,31}?) )??(video )?call: (https:\/\/\S+)\s*$/;
// A link to a service you added: https, a real host name, then anything with no spaces.
const MEETING_CUSTOM_LINK = /^https:\/\/([A-Za-z0-9-]{1,63}(?:\.[A-Za-z0-9-]{1,63})+)(?::\d{1,5})?(?:[/?#]\S*)?$/;
const MEETING_HOST = /^[a-z0-9-]{1,63}(?:\.[a-z0-9-]{1,63})+$/;
const MEETING_CUSTOM_ID = /^c[0-9a-f]{8}$/;
const MEETING_NAME_MAX = 32, MEETING_CUSTOM_MAX = 10;
// The names the built-in services use, which a service you add can't take.
const MEETING_BUILT_IN_NAMES = new Set(MEETING_SERVICES.flatMap((service) => [service.label, service.short]).map((name) => name.toLowerCase()));
// Letters that can't be seen or that change how the text around them shows.
const MEETING_HIDDEN = /[\p{Cc}\p{Cf}]/u;

/** A built-in service, or one you added when settings are given. */
function meetingService(id, settings) {
  return MEETING_SERVICES.find((service) => service.id === id) || settings?.custom?.find((service) => service.id === id) || null;
}

/** A service you added: a name such as "Work video", the link it sends, and the host it must be on ('' for any). */
function customMeetingService(id, name, link, host = '') {
  return {id, label: name, short: name, company: '', on: true, home: '', example: 'https://' + (host || 'video.example.org') + '/your-room',
    steps: [], custom: true, link, host};
}

const meetingSpaced = (text) => String(text || '').trim().replace(/\s+/g, ' ');

/** The name for a service you add, tidied; null when it's empty, longer than 32 letters, or a built-in one's. */
function cleanMeetingName(text) {
  const name = meetingSpaced(text);
  return name && name.length <= MEETING_NAME_MAX && !MEETING_HIDDEN.test(name) && !MEETING_BUILT_IN_NAMES.has(name.toLowerCase()) ? name : null;
}

/** What's wrong with a name for a service you add, or null when it's fine. others are your other services' names. */
function meetingNameProblem(text, others = []) {
  const name = meetingSpaced(text);
  if (!name) return 'Give your service a name.';
  if (name.length > MEETING_NAME_MAX) return 'Use a shorter name, ' + MEETING_NAME_MAX + ' letters or fewer.';
  if (MEETING_HIDDEN.test(name)) return 'That name has a letter that can’t be shown. Type it again.';
  if (MEETING_BUILT_IN_NAMES.has(name.toLowerCase())) return name + ' is already on the list. Use another name.';
  if (others.some((other) => other.toLowerCase() === name.toLowerCase())) return 'You already have a service named ' + name + '.';
  return null;
}

/** The host a link must be on, tidied (video.example.org); '' when left empty; null when it isn't a host name. */
function cleanMeetingHost(text) {
  const value = String(text || '').trim().replace(/^https?:\/\//i, '').replace(/\/+$/, '').toLowerCase();
  return !value ? '' : value.length <= 253 && MEETING_HOST.test(value) ? value : null;
}

/** A link for a service you add, tidied, when it's an https link (on host when one is given); null when it isn't. */
function validCustomMeetingLink(text, host = '') {
  const link = tidyMeetingLink(text);
  if (link.length > 2000 || /\s/.test(link)) return null;
  const match = MEETING_CUSTOM_LINK.exec(link);
  if (!match) return null;
  const on = match[1].toLowerCase();
  return !host || on === host || on.endsWith('.' + host) ? link : null;
}

/** What's wrong with a link for a service you add, or null when it's fine. */
function customMeetingLinkProblem(text, host) {
  if (!String(text || '').trim()) return 'Paste your link first.';
  if (validCustomMeetingLink(text, host)) return null;
  if (host && validCustomMeetingLink(text)) return 'That link isn’t on ' + host + '. Check it for a typo.';
  return 'That doesn’t look like a web link. It should start with https:// and have no spaces.';
}

/** A typed or pasted link, tidied: spaces trimmed and https:// added when it's missing. */
function tidyMeetingLink(text) {
  const value = String(text || '').trim();
  if (/^https?:\/\//i.test(value)) return 'https://' + value.replace(/^https?:\/\//i, '');
  return !value || value.includes('://') ? value : 'https://' + value;
}

/** A Jitsi server name such as meet.jit.si, or null when it isn't one. */
function cleanJitsiServer(text) {
  const value = String(text || '').trim().replace(/^https?:\/\//i, '').replace(/\/+$/, '');
  return /^[A-Za-z0-9.-]{3,253}(:[0-9]{1,5})?$/.test(value) ? value : null;
}

const meetingHost = (link) => link.replace(/^[a-z]+:\/\//i, '').split(/[/?#]/)[0];

/** The link, tidied, when it really is that service's meeting link; null when it isn't. */
function validMeetingLink(id, text, jitsiServer = MEETING_DEFAULT_JITSI) {
  const link = tidyMeetingLink(text);
  if (link.length > 2000 || /\s/.test(link)) return null;
  if (id === 'jitsi') {
    const host = meetingHost(link).toLowerCase();
    return MEETING_ROOM.test(link) && (host === jitsiServer.toLowerCase() || host === MEETING_DEFAULT_JITSI) ? link : null;
  }
  return MEETING_LINK[id] && MEETING_LINK[id].test(link) ? link : null;
}

/** Which service a link belongs to, or null for any other link. */
function meetingServiceOf(link, jitsiServer = MEETING_DEFAULT_JITSI) {
  return MEETING_SERVICES.find((service) => validMeetingLink(service.id, link, jitsiServer) === link) || null;
}

/** The link, tidied, when it really is a link for that service, built-in or one you added; null when it isn't. */
function meetingCheckLink(settings, id, text) {
  const service = meetingService(id, settings);
  return service?.custom ? validCustomMeetingLink(text, service.host) : validMeetingLink(id, text, settings?.jitsiServer);
}

function meetingLinkProblem(id, text, settings) {
  const service = meetingService(id, settings);
  if (!String(text || '').trim()) return 'Paste your ' + service.label + ' link first.';
  if (service.custom) return customMeetingLinkProblem(text, service.host) || '';
  return 'That doesn’t look like a ' + service.label + ' link. It should look like ' + service.example;
}

/**
 * Your choices, from what the server keeps (prefs.meetings): which services are on, your saved links, the services you
 * added, the one used last, and the Jitsi server. Anything missing or of the wrong kind uses the default, so a server
 * that only ever had the Jitsi-only meeting links starts with all four on and Jitsi working just as before. The server
 * turns on Google Meet and Teams once for settings saved before they were on at first (main.py clean_meetings).
 */
function meetingSettings(stored) {
  const from = stored && typeof stored === 'object' ? stored : {};
  const on = {}, links = {}, custom = [];
  for (const item of Array.isArray(from.custom) ? from.custom.slice(0, MEETING_CUSTOM_MAX) : []) {
    const name = cleanMeetingName(item?.name);
    if (!name || typeof item.id !== 'string' || !MEETING_CUSTOM_ID.test(item.id) || custom.some((other) => other.id === item.id)) continue;
    if (typeof item.link !== 'string' || !item.link.trim()) continue;
    custom.push(customMeetingService(item.id, name, item.link, cleanMeetingHost(item.host) || ''));
  }
  for (const service of [...MEETING_SERVICES, ...custom]) {
    on[service.id] = typeof from.on?.[service.id] === 'boolean' ? from.on[service.id] : service.on;
    const link = from.links?.[service.id];
    if (!service.custom && service.id !== 'jitsi' && typeof link === 'string' && link.trim()) links[service.id] = link;
  }
  const settings = {version: 2, on, links, custom, last: '', jitsiServer: cleanJitsiServer(from.jitsiServer) || MEETING_DEFAULT_JITSI};
  settings.last = meetingService(from.last, settings) ? from.last : '';
  return settings;
}

/** Your choices as the server keeps them: your own services without the parts that are the same for all of them. */
function meetingStored(settings) {
  return {...settings, custom: settings.custom.map(({id, label, link, host}) => ({id, name: label, link, host}))};
}

/** Every service: the built-in ones, Zoom first, then the ones you added. */
function meetingAll(settings) { return [...MEETING_SERVICES, ...(settings.custom || [])]; }

/** The services you can pick when sending, Zoom first. Never empty: with all turned off, the built-in ones show. */
function meetingEnabled(settings) {
  const on = meetingAll(settings).filter((service) => settings.on[service.id]);
  return on.length ? on : MEETING_SERVICES.filter((service) => service.on);
}

/**
 * What's wrong with your own service before it's saved, or null when it can be saved. id is the service being
 * changed, or '' for a new one.
 */
function customMeetingProblem(settings, id, name, link, host) {
  if (!id && settings.custom.length >= MEETING_CUSTOM_MAX) return 'You can add up to ' + MEETING_CUSTOM_MAX + ' services of your own.';
  const named = meetingNameProblem(name, settings.custom.filter((service) => service.id !== id).map((service) => service.label));
  if (named) return named;
  const clean = cleanMeetingHost(host);
  if (clean === null) return 'Enter just the web address, like video.example.org.';
  return customMeetingLinkProblem(link, clean);
}

/** Your choices with your own service saved (checked first with customMeetingProblem). A new one gets newId and starts on. */
function withCustomMeeting(settings, id, newId, name, link, host) {
  const clean = cleanMeetingHost(host) || '';
  const service = customMeetingService(id || newId, cleanMeetingName(name), validCustomMeetingLink(link, clean), clean);
  const custom = id ? settings.custom.map((item) => item.id === id ? service : item) : [...settings.custom, service];
  return {...settings, custom, on: {...settings.on, [service.id]: id ? settings.on[id] !== false : true}};
}

/** Your choices with your own service id removed. */
function withoutCustomMeeting(settings, id) {
  const on = {...settings.on};
  delete on[id];
  return {...settings, custom: settings.custom.filter((item) => item.id !== id), on, last: settings.last === id ? '' : settings.last};
}

/** The last one you used when it's still on; a Jitsi server of your own means Jitsi; otherwise the first, Zoom. */
function meetingPreselected(settings) {
  const choices = meetingEnabled(settings);
  const last = choices.find((service) => service.id === settings.last);
  if (last) return last.id;
  if (!settings.last && settings.jitsiServer !== MEETING_DEFAULT_JITSI && choices.some((service) => service.id === 'jitsi')) return 'jitsi';
  return choices[0].id;
}

/** A saved link that still passes the check; a broken one counts as not saved. */
function meetingSavedLink(settings, id) {
  const service = meetingService(id, settings);
  if (service?.custom) return validCustomMeetingLink(service.link, service.host);
  return id === 'jitsi' || !settings.links[id] ? null : validMeetingLink(id, settings.links[id]);
}

/** Ready to send right away: Jitsi always, the others once a link is saved. */
function meetingReady(settings, id) { return id === 'jitsi' || !!meetingSavedLink(settings, id); }

/** Whether a service may be turned off: one always stays on, so there is always something to pick. */
function meetingCanTurnOff(settings, id) { return meetingAll(settings).some((service) => service.id !== id && settings.on[service.id]); }

/** The line under a service in the picker and in settings. */
function meetingDetail(settings, id) {
  if (id === 'jitsi') return 'Makes a new room each time on ' + settings.jitsiServer + '. No account needed.';
  const service = meetingService(id, settings);
  if (service?.custom) return meetingSavedLink(settings, id) ? 'Your own service · sends your saved link' : 'Your link needs a fix first';
  return meetingSavedLink(settings, id) ? 'Sends your saved link' : 'Save your own ' + service.label + ' link first';
}

/** The short line for the Meeting links row: "Zoom or Jitsi Meet". */
function meetingSummary(settings) {
  const choices = meetingEnabled(settings);
  if (choices.length === 1) return choices[0].label;
  if (choices.length === 2) return choices[0].label + ' or ' + choices[1].label;
  return meetingService(meetingPreselected(settings), settings).label + ' and ' + (choices.length - 1) + ' more';
}

/** Who can see the call, said plainly. */
function meetingPrivacy(id, settings) {
  const service = meetingService(id, settings);
  return (id === 'jitsi' ? 'The meeting server can access the call.' : service?.custom ? 'Whoever runs ' + service.label + ' can see who joins and may record.'
    : service.company + ' can see who joins and may record.') + ' For a private call, use an encrypted call instead.';
}

function meetingSetupLine(id, settings) {
  const {label, custom} = meetingService(id, settings);
  if (custom) return 'FluidMessages sends your saved ' + label + ' link each time you pick ' + label + '.';
  return 'FluidMessages can’t make ' + label + ' meetings for you. Save your own ' + label + ' link once, and FluidMessages sends it each time you pick ' + label + '.';
}

/** The message that carries a meeting link. */
function meetingText(id, video, link, settings) {
  const name = id === 'jitsi' ? '' : meetingService(id, settings).short + ' ';
  // A name that already ends in "video" ("Work video") isn't followed by a second "video".
  const videoWord = /(^|\s)video $/i.test(name) ? '' : 'video ';
  return video ? '📹 Join my ' + name + videoWord + 'call: ' + link : '📞 Join my ' + name + 'call: ' + link;
}

/**
 * A call card's parts ({video, link, service, place}), or null for any other message. No name or a built-in name
 * needs that service's link (or a Jitsi room); any other name is a service the sender added, and needs an https link.
 * Those cards have service 'custom' and the name with the link's server as the place.
 */
function parseMeeting(body, jitsiServer = MEETING_DEFAULT_JITSI) {
  const match = MEETING.exec(String(body || '').trim());
  if (!match) return null;
  const link = match[4];
  let name = match[2] || '', video = !!match[3];
  // "📞 Join my Work video call:" is a voice call on a service named Work video.
  if (match[1] === '📞' && video) { name = name ? name + ' video' : 'video'; video = false; }
  // A name such as "Video" leaves no second "video" word, so the 📹 says it's a video call.
  if (match[1] === '📹') video = true;
  if (!name || MEETING_BUILT_IN_NAMES.has(name.toLowerCase())) {
    const service = meetingServiceOf(link, jitsiServer);
    if (!service && !MEETING_ROOM.test(link)) return null;
    return {video, link, service: service ? service.id : '', place: service && service.id !== 'jitsi' ? service.label : meetingHost(link)};
  }
  if (MEETING_HIDDEN.test(name) || validCustomMeetingLink(link) !== link) return null;
  return {video, link, service: 'custom', place: name + ' · ' + meetingHost(link)};
}

/** The first meeting link in an everyday message, for the "Join meeting" button under it. */
function findMeetingLink(body, jitsiServer = MEETING_DEFAULT_JITSI) {
  for (const found of String(body || '').match(/https:\/\/[^\s<>"']+/g) || []) {
    const link = found.replace(/[.,)!;:’”]+$/, '');
    const service = meetingServiceOf(link, jitsiServer);
    if (service) return {service: service.id, link};
  }
  return null;
}

/** Where Join goes. A Jitsi voice call starts with the camera off; other services decide for themselves. */
function meetingJoinLink(id, video, link) { return !video && (!id || id === 'jitsi') ? link + '#config.startWithVideoMuted=true' : link; }

function newJitsiRoom(jitsiServer, randomHex) { return 'https://' + jitsiServer + '/FluidMessages-' + randomHex; }

function meetingPreview(body) {
  const card = parseMeeting(body);
  return card ? (card.video ? '📹 Video call · tap to join' : '📞 Call · tap to join') : null;
}
// meetings:end

const myMeetings = () => meetingSettings(prefs.meetings);

/** Keeps your meeting-link choices on this server, for every browser signed in to it. */
async function saveMeetings(next) {
  const stored = meetingStored(next);
  prefs = {...prefs, meetings: stored};
  companionSettings = await api('settings', {prefs: {meetings: stored}});
  if (companionSettings.prefs?.meetings) prefs = {...prefs, meetings: companionSettings.prefs.meetings};
}

/** A link that opens in a new tab and tells the other site nothing about this one. */
function meetingAnchor(label, link, className) {
  const anchor = element('a', label, className);
  anchor.href = link;
  anchor.target = '_blank';
  anchor.rel = 'noopener noreferrer';
  return anchor;
}

/** The call card for a meeting-link message, with Join. */
function meetingCard(message, bubble) {
  const card = parseMeeting(message.body, myMeetings().jitsiServer);
  if (!card) return false;
  const box = element('div', '', 'meeting-card');
  const text = element('span', '', 'meeting-text');
  text.append(element('strong', card.video ? 'Video call' : 'Voice call'),
    element('small', (message.type === 1 ? 'Started a call' : 'You started a call') + ' · ' + card.place));
  box.append(element('span', card.video ? '📹' : '📞', 'meeting-icon'), text,
    meetingAnchor('Join', meetingJoinLink(card.service, card.video, card.link), 'meeting-join'));
  bubble.replaceChildren(box);
  return true;
}

/** "Join meeting · Zoom" under an everyday message that has a Zoom, Google Meet, Teams, or Jitsi link in it. */
function meetingChip(message, bubble) {
  const body = message.body || '';
  if (!body.includes('https://') || /^FM1:/.test(body)) return false;
  const found = findMeetingLink(body, myMeetings().jitsiServer);
  if (!found) return false;
  const chip = meetingAnchor('Join meeting · ' + meetingService(found.service).label, found.link, 'meeting-chip');
  bubble.append(chip);
  return true;
}

function meetingRandomHex() {
  return [...crypto.getRandomValues(new Uint8Array(8))].map((byte) => byte.toString(16).padStart(2, '0')).join('');
}

/** Sends the link to the open chat and opens the meeting, remembering the service for next time. */
async function sendMeeting(id, video, link) {
  closeDetailsPage(false);
  window.open(meetingJoinLink(id, video, link), '_blank', 'noopener');
  const settings = myMeetings();
  if (settings.last !== id) saveMeetings({...settings, last: id}).catch(showError);
  const body = meetingText(id, video, link, settings);
  if (internet(threadProtocol(active))) await send(body, body, null);
  else await queueSms(body, threadAddress(active), Date.now());
}

/**
 * Starts a meeting-link call from the browser. A full page lists the services that are on, with the last one used
 * already picked (Zoom the first time). With only one service on, it's used without asking.
 */
async function startMeeting(video) {
  if (!active) return;
  if (!callWayOn('meeting')) throw new Error('Meeting links are off. Turn them on in Settings → Calls.');
  const choices = meetingEnabled(myMeetings());
  if (choices.length === 1) return chooseMeeting(choices[0].id, video, false);
  openMeetingPicker(video);
}

/** Jitsi gets a new room; a saved link is sent right away; otherwise the page to save or paste your link opens. */
async function chooseMeeting(id, video, once, back) {
  const settings = myMeetings();
  if (id === 'jitsi') return sendMeeting(id, video, newJitsiRoom(settings.jitsiServer, meetingRandomHex()));
  const saved = meetingSavedLink(settings, id);
  if (saved && !once) return sendMeeting(id, video, saved);
  const {label, custom} = meetingService(id, settings);
  if (custom && !saved) {
    // Your own service with a link that no longer passes the check: fix it where you added it.
    closeDetailsPage(false);
    meetingEditing = id;
    say('Fix the link for ' + label + ', then send it again.');
    return openSettings('meeting-links');
  }
  const leave = back || (() => closeDetailsPage());
  detailsPage(saved ? 'A different ' + label + ' link' : 'Your ' + label + ' link',
    [meetingLinkForm({id, mode: saved ? 'once' : 'first', cancel: leave, done: (link) => sendMeeting(id, video, link).catch(showError)})], leave);
}

function openMeetingPicker(video, picked) {
  const settings = myMeetings();
  const choices = meetingEnabled(settings);
  let current = choices.some((service) => service.id === picked) ? picked : meetingPreselected(settings);
  const card = element('section', '', 'settings-section meeting-picker');
  const privacy = element('p', '', 'notice');
  const go = element('button', '', 'primary');
  go.type = 'button';
  const other = element('button', 'Use a different link just this once');
  other.type = 'button';
  const again = () => openMeetingPicker(video, current);
  const draw = () => {
    privacy.textContent = meetingPrivacy(current, settings);
    go.textContent = meetingReady(settings, current) ? 'Send link and join' : 'Set up ' + meetingService(current, settings).label;
    other.hidden = !meetingSavedLink(settings, current);
  };
  go.addEventListener('click', () => chooseMeeting(current, video, false, again).catch(showError));
  other.addEventListener('click', () => chooseMeeting(current, video, true, again).catch(showError));
  const actions = element('div', '', 'meeting-actions');
  actions.append(go, other);
  card.append(element('p', 'Pick a service. They join by opening the link, in a browser or that service’s app.', 'muted'),
    choiceGroup('Service', 'meeting-service', choices.map((service) => [service.id, service.label, meetingDetail(settings, service.id)]),
      current, (id) => { current = id; draw(); }), privacy, actions);
  draw();
  detailsPage((video ? 'Video meeting with ' : 'Meeting with ') + threadTitle(active), [card]);
}

/**
 * Saving your own link: one sentence, three steps, a button to the service's own page, and the field. The link has
 * to be a real link for that service. mode is 'first' (picked while sending, nothing saved yet), 'once' (send another
 * link this time only), or 'settings'. done gets the link, or null when it was removed.
 */
function meetingLinkForm({id, mode, done, cancel}) {
  const now = myMeetings();
  const service = meetingService(id, now);
  const saved = meetingSavedLink(now, id);
  const card = element('section', '', 'settings-section meeting-setup');
  if (mode === 'once') card.append(element('p', 'Paste the ' + service.label + ' link to send this time. Your saved link stays the same.'));
  else {
    const steps = element('ol', '', 'meeting-steps');
    for (const step of service.steps) steps.append(element('li', step));
    card.append(element('p', meetingSetupLine(id, now)), steps);
    if (service.home) card.append(meetingAnchor('Open ' + service.label, service.home, 'primary meeting-open'));
  }
  const input = Object.assign(element('input', ''), {type: 'url', placeholder: service.example, value: mode === 'settings' ? saved || '' : '',
    maxLength: 2000, autocomplete: 'off', spellcheck: false});
  input.id = 'meeting-link-' + id;
  const problem = element('p', '', 'notice meeting-problem');
  problem.setAttribute('role', 'alert');
  problem.hidden = true;
  input.addEventListener('input', () => { problem.hidden = true; input.removeAttribute('aria-invalid'); });
  /** The checked link, or null after saying what's wrong. */
  const checked = () => {
    const link = meetingCheckLink(now, id, input.value);
    if (!link) {
      problem.textContent = meetingLinkProblem(id, input.value, now);
      problem.hidden = false;
      input.setAttribute('aria-invalid', 'true');
      input.focus();
    }
    return link;
  };
  const button = (label, run, className = '') => {
    const node = element('button', label, className);
    node.type = 'button';
    node.addEventListener('click', run);
    return node;
  };
  const actions = element('div', '', 'meeting-actions');
  actions.append(button(mode === 'first' ? 'Save and send' : mode === 'once' ? 'Send this link' : 'Save', () => {
    const link = checked();
    if (!link) return;
    // The meeting opens from this click, so the link is sent first and saved alongside. Your own service keeps its
    // link with it, in Your own services.
    if (mode !== 'once' && !service.custom) {
      const settings = myMeetings();
      saveMeetings({...settings, links: {...settings.links, [id]: link}}).then(() => { if (mode === 'settings') done(link); }).catch(showError);
    }
    if (mode !== 'settings') done(link);
  }, 'primary'));
  if (mode === 'first') actions.append(button('Use this link just this once', () => { const link = checked(); if (link) done(link); }));
  if (mode === 'settings' && saved) actions.append(confirmButton('Remove', 'Tap again to remove', async () => {
    const settings = myMeetings();
    const links = {...settings.links};
    delete links[id];
    await saveMeetings({...settings, links});
    done(null);
  }));
  actions.append(button('Cancel', cancel));
  card.append(field(service.label + ' link', input), problem, element('p', meetingPrivacy(id, now), 'muted'), actions);
  return card;
}

/** Settings → Calls → Meeting links → your link for one service. */
function meetingLinkPage(id) {
  const leave = () => settingsBack(settingsItem('meeting-link-' + id));
  return [meetingLinkForm({id, mode: 'settings', cancel: leave, done: (link) => {
    say(link ? 'Your ' + meetingService(id).label + ' link is saved.' : 'Your ' + meetingService(id).label + ' link was removed.');
    leave();
  }})];
}

/** Which of your own services is open for changes on the Meeting links page: '' for none, 'new' to add one, or its id. */
let meetingEditing = '';

/** A checkbox drawn and announced as an on/off switch. */
function meetingSwitch(label, checked, change, hint) {
  const row = checkbox(label, checked, change, hint);
  row.classList.add('switch');
  row.querySelector('input').setAttribute('role', 'switch');
  return row;
}

/**
 * Adding or changing your own service, in place on the Meeting links page: its name, its link, and (if you like) the
 * host the link must be on, so a typo is caught. service is null to add a new one.
 */
function customMeetingForm(settings, service, redraw) {
  const box = element('div', '', 'meeting-custom');
  const input = (value, placeholder, type = 'text', maxLength = 64) =>
    Object.assign(element('input', ''), {type, value, placeholder, maxLength, autocomplete: 'off', spellcheck: false});
  const name = input(service?.label || '', 'Work video');
  const link = input(service?.link || '', 'https://video.example.org/your-room', 'url', 2000);
  const host = input(service?.host || '', 'video.example.org', 'text', 253);
  const problem = element('p', '', 'notice meeting-problem');
  problem.setAttribute('role', 'alert');
  problem.hidden = true;
  const preview = element('p', '', 'muted');
  const drawPreview = () => { preview.textContent = 'They get: ' + meetingText('custom-preview', true, 'https://…', {custom: [customMeetingService('custom-preview', cleanMeetingName(name.value) || 'Work video', '')]}); };
  for (const node of [name, link, host]) node.addEventListener('input', () => { problem.hidden = true; drawPreview(); });
  drawPreview();
  const done = (said) => { meetingEditing = ''; say(said); redraw(); };
  const save = element('button', 'Save', 'primary');
  save.type = 'button';
  save.addEventListener('click', async () => {
    const wrong = customMeetingProblem(settings, service?.id || '', name.value, link.value, host.value);
    if (wrong) {
      problem.textContent = wrong;
      problem.hidden = false;
      return;
    }
    let id = '';
    while (!service && (!id || meetingService(id, settings))) id = 'c' + meetingRandomHex().slice(0, 8);
    try {
      await saveMeetings(withCustomMeeting(settings, service?.id || '', id, name.value, link.value, host.value));
      done(cleanMeetingName(name.value) + ' is saved.');
    } catch (error) { showError(error); }
  });
  const cancel = element('button', 'Cancel');
  cancel.type = 'button';
  cancel.addEventListener('click', () => { meetingEditing = ''; redraw(); });
  const actions = element('div', '', 'meeting-actions');
  actions.append(save);
  if (service) actions.append(confirmButton('Remove', 'Tap again to remove', async () => {
    try {
      await saveMeetings(withoutCustomMeeting(settings, service.id));
      done(service.label + ' was removed.');
    } catch (error) { showError(error); }
  }));
  actions.append(cancel);
  box.append(element('h4', service ? 'Change ' + service.label : 'Add your own service'),
    field('Name', name, 'Up to ' + MEETING_NAME_MAX + ' letters. People see it in your message.'),
    field('Meeting link', link),
    field('Web address it must be on (optional)', host, 'Catches a typo: the link has to be on this web address.'),
    problem, preview, element('p', 'Whoever runs your service can see who joins and may record. For a private call, use an encrypted call instead.', 'muted'),
    actions);
  return box;
}

/** Settings → Calls → Meeting links: each service on or off, your saved links, your own services, and the Jitsi server. */
function meetingLinksPage(context) {
  const settings = myMeetings();
  const redraw = () => context.rerender('meeting-links');
  const services = settingsCard('Services you can pick when you send a link', 'Zoom is first. The one you used last is picked for you next time.');
  for (const service of meetingAll(settings)) {
    services.append(meetingSwitch(service.label, settings.on[service.id], async (value) => {
      if (!value && !meetingCanTurnOff(settings, service.id)) {
        say('One service always stays on. To stop meeting links, turn off Meeting links in Calls.');
        redraw();
        return;
      }
      try { await saveMeetings({...settings, on: {...settings.on, [service.id]: value}}); } catch (error) { showError(error); }
      redraw();
    }, meetingDetail(settings, service.id)));
  }
  const pages = [services];
  const needLinks = MEETING_SERVICES.filter((service) => service.id !== 'jitsi' && settings.on[service.id]);
  if (needLinks.length) {
    const links = settingsCard('Your saved links');
    const rows = element('div', '', 'settings-rows');
    for (const service of needLinks) {
      rows.append(settingsRow({...settingsItem('meeting-link-' + service.id), title: service.label + ' link',
        summary: meetingSavedLink(settings, service.id) || 'Not saved yet. Add yours.'}, {}, ''));
    }
    links.append(rows);
    pages.push(links);
  }
  const own = settingsCard('Your own services', 'Use a different video app? Add it with a name and your meeting link. FluidMessages sends that link each time you pick it.');
  if (meetingEditing && meetingEditing !== 'new' && !meetingService(meetingEditing, settings)?.custom) meetingEditing = '';
  for (const service of settings.custom) {
    if (meetingEditing === service.id) { own.append(customMeetingForm(settings, service, redraw)); continue; }
    const row = element('div', '', 'meeting-own');
    const words = element('span', '');
    words.append(element('strong', service.label),
      element('small', (meetingSavedLink(settings, service.id) || 'Your link needs a fix') + (settings.on[service.id] ? '' : ' · off'), 'hint'));
    const edit = element('button', 'Change');
    edit.type = 'button';
    edit.setAttribute('aria-label', 'Change ' + service.label);
    edit.addEventListener('click', () => { meetingEditing = service.id; redraw(); });
    row.append(words, edit);
    own.append(row);
  }
  if (meetingEditing === 'new') own.append(customMeetingForm(settings, null, redraw));
  else if (settings.custom.length < MEETING_CUSTOM_MAX) {
    const add = element('button', 'Add your own service', 'primary');
    add.type = 'button';
    add.addEventListener('click', () => { meetingEditing = 'new'; redraw(); });
    const actions = element('div', '', 'meeting-actions');
    actions.append(add);
    own.append(actions);
  }
  pages.push(own);
  if (settings.on.jitsi) {
    const jitsi = settingsCard('Jitsi Meet', 'Free and open source. Works in any browser and the free Jitsi app, with no account.');
    const input = Object.assign(element('input', ''), {type: 'text', value: settings.jitsiServer, placeholder: MEETING_DEFAULT_JITSI,
      autocomplete: 'off', spellcheck: false});
    const save = element('button', 'Save', 'primary');
    save.type = 'button';
    save.addEventListener('click', async () => {
      const server = cleanJitsiServer(input.value);
      if (!server) { say('Enter a server name like meet.jit.si.'); input.focus(); return; }
      try { await saveMeetings({...settings, jitsiServer: server}); say('Jitsi server saved.'); } catch (error) { showError(error); }
      redraw();
    });
    const actions = element('div', '', 'meeting-actions');
    actions.append(save);
    jitsi.append(field('Jitsi server', input, 'Use meet.jit.si, or your own Jitsi server. The meeting server can see who joins.'), actions);
    pages.push(jitsi);
  }
  const know = settingsCard('Good to know');
  know.append(element('p', 'FluidMessages sends your link. It can’t make Zoom, Google Meet, Teams, or your own service’s meetings for you. Jitsi Meet makes a new room each time.', 'muted'),
    element('p', 'Zoom, Google, or Microsoft can see who joins and may record. On Jitsi, the meeting server can access the call. '
      + 'For a private call, use an encrypted call instead.', 'muted'),
    element('p', 'These choices are for FluidMessages Web. Your phone keeps its own in Settings → Calls → Meeting links.', 'muted'));
  pages.push(know);
  return pages;
}
