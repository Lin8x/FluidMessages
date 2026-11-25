'use strict';

// Meeting links: a call anyone can join by opening a link. You pick the service each time you send one. Zoom is first
// and the default; Zoom and Jitsi Meet are on at first, and Google Meet and Microsoft Teams can be turned on in
// Settings → Calls → Meeting links. FluidMessages has no account with Zoom, Google, or Microsoft, so for those it sends
// a link you saved yourself. Only Jitsi Meet makes a new room on the spot. The phone has the same list, checks, and
// wording (MeetingServices.kt), and the server checks what is saved (main.py clean_meetings).

// meetings:start (plain rules with no page in them; scripts/test-local runs this part in Node)
const MEETING_SERVICES = [
  {id: 'zoom', label: 'Zoom', short: 'Zoom', company: 'Zoom', on: true, home: 'https://zoom.us/profile', example: 'https://zoom.us/j/1234567890',
    steps: ['Open Zoom with the button below and sign in.', 'On your Profile page, find Personal Meeting ID and copy the link under it.',
      'Come back here and paste the link.']},
  {id: 'meet', label: 'Google Meet', short: 'Google Meet', company: 'Google', on: false, home: 'https://meet.google.com/', example: 'https://meet.google.com/abc-defg-hij',
    steps: ['Open Google Meet with the button below and sign in.', 'Choose New meeting, then Create a meeting for later, and copy the link.',
      'Come back here and paste the link.']},
  {id: 'teams', label: 'Microsoft Teams', short: 'Teams', company: 'Microsoft', on: false, home: 'https://teams.microsoft.com/', example: 'https://teams.microsoft.com/meet/1234567890',
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
// Sent as plain text so every app can open it: "📹 Join my Zoom video call: https://…". Jitsi has no service name, as before.
const MEETING = /^(?:📹|📞)?\s*Join my (?:(?:Zoom|Google Meet|Teams) )?(video )?call: (https:\/\/\S+)\s*$/;

function meetingService(id) { return MEETING_SERVICES.find((service) => service.id === id) || null; }

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

function meetingLinkProblem(id, text) {
  const service = meetingService(id);
  return String(text || '').trim() ? 'That doesn’t look like a ' + service.label + ' link. It should look like ' + service.example
    : 'Paste your ' + service.label + ' link first.';
}

/**
 * Your choices, from what the server keeps (prefs.meetings): which services are on, your saved links, the one used
 * last, and the Jitsi server. Anything missing or of the wrong kind uses the default, so a server that only ever had
 * the Jitsi-only meeting links starts with Zoom and Jitsi on and Jitsi working just as before.
 */
function meetingSettings(stored) {
  const from = stored && typeof stored === 'object' ? stored : {};
  const on = {}, links = {};
  for (const service of MEETING_SERVICES) {
    on[service.id] = typeof from.on?.[service.id] === 'boolean' ? from.on[service.id] : service.on;
    const link = from.links?.[service.id];
    if (service.id !== 'jitsi' && typeof link === 'string' && link.trim()) links[service.id] = link;
  }
  return {on, links, last: meetingService(from.last) ? from.last : '', jitsiServer: cleanJitsiServer(from.jitsiServer) || MEETING_DEFAULT_JITSI};
}

/** The services you can pick when sending, Zoom first. Never empty: with all turned off, Zoom and Jitsi show. */
function meetingEnabled(settings) {
  const on = MEETING_SERVICES.filter((service) => settings.on[service.id]);
  return on.length ? on : MEETING_SERVICES.filter((service) => service.on);
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
function meetingSavedLink(settings, id) { return id === 'jitsi' || !settings.links[id] ? null : validMeetingLink(id, settings.links[id]); }

/** Ready to send right away: Jitsi always, the others once a link is saved. */
function meetingReady(settings, id) { return id === 'jitsi' || !!meetingSavedLink(settings, id); }

/** Whether a service may be turned off: one always stays on, so there is always something to pick. */
function meetingCanTurnOff(settings, id) { return MEETING_SERVICES.some((service) => service.id !== id && settings.on[service.id]); }

/** The line under a service in the picker and in settings. */
function meetingDetail(settings, id) {
  if (id === 'jitsi') return 'Makes a new room each time on ' + settings.jitsiServer + '. No account needed.';
  return meetingSavedLink(settings, id) ? 'Sends your saved link' : 'Save your own ' + meetingService(id).label + ' link first';
}

/** The short line for the Meeting links row: "Zoom or Jitsi Meet". */
function meetingSummary(settings) {
  const choices = meetingEnabled(settings);
  if (choices.length === 1) return choices[0].label;
  if (choices.length === 2) return choices[0].label + ' or ' + choices[1].label;
  return meetingService(meetingPreselected(settings)).label + ' and ' + (choices.length - 1) + ' more';
}

/** Who can see the call, said plainly. */
function meetingPrivacy(id) {
  const service = meetingService(id);
  return (id === 'jitsi' ? 'The meeting server can access the call.' : service.company + ' can see who joins and may record.')
    + ' For a private call, use an encrypted call instead.';
}

function meetingSetupLine(id) {
  const {label} = meetingService(id);
  return 'FluidMessages can’t make ' + label + ' meetings for you. Save your own ' + label + ' link once, and FluidMessages sends it each time you pick ' + label + '.';
}

/** The message that carries a meeting link. */
function meetingText(id, video, link) {
  const name = id === 'jitsi' ? '' : meetingService(id).short + ' ';
  return video ? '📹 Join my ' + name + 'video call: ' + link : '📞 Join my ' + name + 'call: ' + link;
}

/** A call card's parts ({video, link, service, place}), or null for any other message. */
function parseMeeting(body, jitsiServer = MEETING_DEFAULT_JITSI) {
  const match = MEETING.exec(String(body || '').trim());
  if (!match) return null;
  const link = match[2];
  const service = meetingServiceOf(link, jitsiServer);
  if (!service && !MEETING_ROOM.test(link)) return null;
  return {video: !!match[1], link, service: service ? service.id : '', place: service && service.id !== 'jitsi' ? service.label : meetingHost(link)};
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
  prefs = {...prefs, meetings: next};
  companionSettings = await api('settings', {prefs: {meetings: next}});
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
  const body = meetingText(id, video, link);
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
  const leave = back || (() => closeDetailsPage());
  detailsPage(saved ? 'A different ' + meetingService(id).label + ' link' : 'Your ' + meetingService(id).label + ' link',
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
    privacy.textContent = meetingPrivacy(current);
    go.textContent = meetingReady(settings, current) ? 'Send link and join' : 'Set up ' + meetingService(current).label;
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
  const service = meetingService(id);
  const saved = meetingSavedLink(myMeetings(), id);
  const card = element('section', '', 'settings-section meeting-setup');
  if (mode === 'once') card.append(element('p', 'Paste the ' + service.label + ' link to send this time. Your saved link stays the same.'));
  else {
    const steps = element('ol', '', 'meeting-steps');
    for (const step of service.steps) steps.append(element('li', step));
    card.append(element('p', meetingSetupLine(id)), steps, meetingAnchor('Open ' + service.label, service.home, 'primary meeting-open'));
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
    const link = validMeetingLink(id, input.value);
    if (!link) {
      problem.textContent = meetingLinkProblem(id, input.value);
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
    // The meeting opens from this click, so the link is sent first and saved alongside.
    if (mode !== 'once') {
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
  card.append(field(service.label + ' link', input), problem, element('p', meetingPrivacy(id), 'muted'), actions);
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

/** Settings → Calls → Meeting links: each service on or off, your saved links, and the Jitsi server. */
function meetingLinksPage(context) {
  const settings = myMeetings();
  const redraw = () => context.rerender('meeting-links');
  const services = settingsCard('Services you can pick when you send a link', 'Zoom is first. The one you used last is picked for you next time.');
  for (const service of MEETING_SERVICES) {
    services.append(checkbox(service.label, settings.on[service.id], async (value) => {
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
  know.append(element('p', 'FluidMessages sends your link. It can’t make Zoom, Google Meet, or Teams meetings for you. Jitsi Meet makes a new room each time.', 'muted'),
    element('p', 'Zoom, Google, or Microsoft can see who joins and may record. On Jitsi, the meeting server can access the call. '
      + 'For a private call, use an encrypted call instead.', 'muted'),
    element('p', 'These choices are for FluidMessages Web. Your phone keeps its own in Settings → Calls → Meeting links.', 'muted'));
  pages.push(know);
  return pages;
}
