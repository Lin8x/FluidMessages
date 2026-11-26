'use strict';
// An XMPP chat's Encryption section in its details: everyone's OMEMO devices with their fingerprints (8 groups of
// 8 characters, as other OMEMO apps show them), a choice per device, and your own devices and xmpp: link.
// Trust is "blind trust before verification": devices are trusted on sight until you verify one of that person's,
// then new ones wait for your OK (xmpp_omemo.py and omemo_trust.py on the server).

const OMEMO_CHOICES = [['blind', 'Trusted'], ['verified', 'Verified'], ['distrusted', 'Off']];

/** Whether a chat is XMPP through FluidMessages Web, where OMEMO can lock it. */
function omemoChat(key) {
  const route = typeof routeFor === 'function' ? routeFor(key) : null;
  return Boolean(route && route.protocol === 'xmpp' && !onSampleChat(key));
}

function onSampleChat(key) {
  return typeof sampleOn !== 'undefined' && sampleOn || String(key || '').startsWith('demo:');
}

/** The section, filled in once the server answers. */
function omemoSection(key) {
  const section = element('section', '', 'settings-section omemo-section');
  section.append(element('h3', 'Encryption (OMEMO)'), element('p', 'Checking devices…', 'muted'));
  const address = threadAddress(key), protocol = threadProtocol(key);
  const load = (request) => request.then((info) => drawOmemo(section, key, info, load))
    .catch((error) => { section.replaceChildren(element('h3', 'Encryption (OMEMO)'), element('p', error.message, 'muted')); });
  load(api('protocols/omemo', {address, protocol}));
  return section;
}

function omemoTrustLabel(trust) {
  return {verified: 'Verified by you', blind: 'Trusted, not checked yet', unconfirmed: 'New: waiting for your OK',
          distrusted: 'Off: messages aren’t locked for it', undecided: 'Not checked yet'}[trust] || 'Not checked yet';
}

/** One device: its number, fingerprint, and what you chose for it. */
function omemoDeviceRow(key, jid, device, load) {
  const row = element('div', '', 'detail-row omemo-device');
  const words = element('span', '', 'detail-words');
  words.append(element('strong', device.this ? 'This device (FluidMessages Web)' : 'Device ' + device.id),
    element('code', device.fingerprint.join(' '), 'omemo-fingerprint'),
    element('small', device.this ? 'Your messages here are locked with this key.' : omemoTrustLabel(device.trust)));
  row.append(words);
  if (!device.this) {
    const choice = element('select', '');
    choice.setAttribute('aria-label', 'Trust for device ' + device.id);
    if (!OMEMO_CHOICES.some(([value]) => value === device.trust)) {
      choice.append(Object.assign(element('option', 'Choose…'), {value: '', disabled: true, selected: true}));
    }
    for (const [value, label] of OMEMO_CHOICES) {
      choice.append(Object.assign(element('option', label), {value, selected: value === device.trust}));
    }
    choice.addEventListener('change', () => {
      load(api('protocols/omemo/trust', {address: threadAddress(key), protocol: threadProtocol(key), jid,
                                         device: device.id, trust: choice.value}));
    });
    row.append(choice);
  }
  return row;
}

function drawOmemo(section, key, info, load) {
  section.replaceChildren(element('h3', 'Encryption (OMEMO)'));
  if (!info.available) {
    section.append(element('p', (info.reason || 'OMEMO isn’t set up.') + ' This chat is encrypted in transit only.', 'muted'));
    return;
  }
  section.append(element('p', info.words, 'omemo-words omemo-' + info.color));
  section.append(element('p', 'Compare a device’s fingerprint with what their app shows (in person or on a call), '
    + 'then choose Verified. After you verify one of their devices, new ones wait for your OK.', 'muted'));
  for (const person of info.people) {
    section.append(element('h4', person.jid));
    if (!person.devices.length) section.append(element('p', 'No OMEMO devices: their app doesn’t use OMEMO.', 'muted'));
    for (const device of person.devices) section.append(omemoDeviceRow(key, person.jid, device, load));
  }
  section.append(element('h4', 'Your devices'));
  for (const device of info.own.devices) section.append(omemoDeviceRow(key, info.own.jid, device, load));
  // Your link, in the format Conversations shows as a QR code, and checking a link someone shared with you.
  const link = element('div', '', 'detail-row');
  const words = element('span', '', 'detail-words');
  words.append(element('strong', 'Your OMEMO link'), element('code', info.uri, 'omemo-fingerprint'));
  const copy = element('button', 'Copy', 'secondary');
  copy.type = 'button';
  copy.addEventListener('click', () => navigator.clipboard?.writeText(info.uri).then(() => { copy.textContent = 'Copied'; }));
  link.append(words, copy);
  const check = element('form', '', 'detail-row omemo-check');
  const input = Object.assign(element('input', ''), {type: 'text', placeholder: 'xmpp:name@example.org?omemo-sid-…'});
  input.setAttribute('aria-label', 'An OMEMO link they shared with you');
  const button = Object.assign(element('button', 'Check'), {type: 'submit'});
  const result = element('p', '', 'muted');
  check.append(input, button);
  check.addEventListener('submit', (event) => {
    event.preventDefault();
    api('protocols/omemo/trust', {address: threadAddress(key), protocol: threadProtocol(key), uri: input.value.trim()})
      .then((answer) => {
        drawOmemo(section, key, answer, load);
        const note = answer.checked || {};
        section.append(element('p', note.verified ? `Verified ${note.verified} device${note.verified === 1 ? '' : 's'} from the link.`
          : note.mismatched ? 'The link’s fingerprint doesn’t match. Don’t verify this device; ask them to check.'
          : 'No device in the link matches this chat.', 'omemo-check-result'));
      })
      .catch((error) => { result.textContent = error.message; });
  });
  section.append(link, element('p', 'Paste an xmpp: link they shared to verify their devices in one step.', 'muted'), check, result);
}
