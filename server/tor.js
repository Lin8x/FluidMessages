'use strict';
// Settings → Privacy and security → Connect through Tor (tor_route.py), like the phone's (TorSettingsPage). Off by
// default. Turning it on shows the warning in place, and Turn on waits for "I understand".

async function torSettingsLink() {
  const card = settingsCard('Network');
  let summary = 'Off · hide this server’s internet address from chat servers. Slower.';
  try {
    const status = await api('tor');
    if (status.on) summary = status.reachable ? 'On · Connected through Tor' : 'On · Tor isn’t running';
  } catch (error) { /* shown on the page */ }
  card.append(settingsLink('tor', summary));
  return card;
}

function torStateWords(status) {
  if (!status.on) return 'Off';
  return status.reachable ? 'Connected through Tor' : 'Tor isn’t running at ' + status.proxy;
}

async function torSettingsPage() {
  let status = await api('tor');
  const card = settingsCard('Connect through Tor', 'For the connections this server makes: IRC, Matrix, and email, and link '
    + 'previews. Your phone has its own setting.');
  card.id = 'settings-tor';
  const state = element('p', torStateWords(status), 'muted');
  state.setAttribute('role', 'status');
  const problem = errorLine();
  const save = async (change) => {
    problem.textContent = '';
    try {
      status = await api('tor', change);
      draw();
      return true;
    } catch (error) {
      problem.textContent = error.message;
      return false;
    }
  };
  const consent = element('div', '', 'notice');
  consent.id = 'tor-consent';
  const understood = checkbox('I understand it’s slower and less reliable', false, (value) => { turnOn.disabled = !value; });
  const turnOn = element('button', 'Turn on', 'primary');
  turnOn.type = 'button';
  turnOn.disabled = true;
  const cancel = element('button', 'Cancel');
  cancel.type = 'button';
  consent.append(element('strong', 'Connect through Tor?'), element('p', status.warning), understood, turnOn, cancel);
  const toggle = checkbox('Connect through Tor', status.on, async (value) => {
    if (value) { consent.hidden = false; return; }
    consent.hidden = true;
    await save({on: false});
  }, status.warning);
  const toggleInput = toggle.querySelector('input');
  cancel.addEventListener('click', () => { consent.hidden = true; toggleInput.checked = false; });
  turnOn.addEventListener('click', async () => {
    if (await save({on: true, understood: true})) consent.hidden = true;
  });
  consent.hidden = true;

  const proxy = Object.assign(element('input', ''), {type: 'text', value: status.proxy, autocomplete: 'off', spellcheck: false});
  proxy.id = 'tor-proxy';
  const saveProxy = element('button', 'Save address');
  saveProxy.type = 'button';
  saveProxy.addEventListener('click', () => save({proxy: proxy.value.trim()}));
  const install = element('p', '', 'notice');
  install.id = 'tor-install';

  function draw() {
    toggleInput.checked = status.on;
    state.textContent = torStateWords(status);
    install.hidden = status.reachable;
    install.textContent = 'No Tor proxy answers at ' + status.proxy + '. ' + status.install
      + (status.on ? ' Until it answers, chat services wait instead of connecting without Tor.' : '');
  }
  draw();
  card.append(toggle, consent, state, field('Tor proxy address', proxy, 'Tor’s SOCKS port. Usually 127.0.0.1:9050.'), saveProxy, install, problem);

  const what = settingsCard('What goes through Tor');
  what.append(element('p', 'Through Tor: IRC, Matrix, and email connected from this server, and link previews and pictures from links.', 'muted'),
    element('p', 'Not through Tor: XMPP waits and encrypted Matrix rooms can’t be used here while Tor is on, because their '
      + 'libraries open their own connections. Cloud backups, and servers on your own network, connect directly. Calls run in your browser or phone '
      + 'and never go through this server.', 'muted'));
  return [card, what];
}

SETTINGS_SUBPAGES.push({id: 'tor', icon: 'privacy', title: 'Connect through Tor', parent: 'privacy', page: () => torSettingsPage()});
