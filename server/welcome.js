'use strict';
// First run, like any app's: a welcome, pick a look, add chat accounts, and, if you like, connect your phone.
// Loaded after app.js and companion.js and uses their helpers (api, get, element, themeSwatch, accountRows, ...).

function showWelcome() {
  const root = get('welcome');
  get('workspace').hidden = true;
  get('pair').hidden = true;
  root.hidden = false;
  let step = 0;

  const finish = async (then) => {
    try { await api('settings', {welcomed: true}); } catch (error) { /* shown again next time, which is fine */ }
    welcomed = true;
    root.hidden = true;
    root.replaceChildren();
    get('workspace').hidden = false;
    await refresh().catch(() => {});
    if (then) then(); else get('new').focus();
  };

  const button = (label, run, className = '') => {
    const node = element('button', label, className);
    node.type = 'button';
    node.addEventListener('click', run);
    return node;
  };

  const footer = (...buttons) => {
    const row = element('div', '', 'welcome-actions');
    row.append(...buttons);
    return row;
  };

  const back = () => button('Back', () => { step--; render(); }, 'quiet');
  const next = (label = 'Continue') => button(label, () => { step++; render(); }, 'primary');

  const intro = () => {
    const icon = Object.assign(element('img', ''), {src: '/icons/icon-192.png', alt: '', width: 88, height: 88, className: 'app-icon'});
    const title = element('h1', 'Welcome to FluidMessages');
    title.id = 'welcome-title';
    const points = element('ul', '', 'welcome-points');
    for (const [emoji, text] of [['💬', 'Matrix, XMPP, Email, and IRC chats in one place'], ['🎨', 'Themes, effects, polls, games, and calls'],
      ['📱', 'Add your Android phone any time for texts']]) {
      const item = element('li', '');
      item.append(Object.assign(element('span', emoji), {ariaHidden: 'true'}), element('span', text));
      points.append(item);
    }
    return [icon, title, element('p', 'It runs right here on your computer, so there’s nothing to sign up for.', 'lead'), points,
      footer(next('Get started'))];
  };

  const look = () => {
    const title = element('h2', 'Pick a look');
    title.id = 'welcome-title';
    const themes = element('fieldset', '', 'theme-grid');
    themes.append(element('legend', 'Color theme', 'visually-hidden'));
    const draw = () => {
      for (const tile of themes.querySelectorAll('.theme-tile')) {
        const input = tile.querySelector('input');
        input.checked = input.value === (webLook.theme || 'fluid');
        tile.querySelector('.theme-swatch').replaceWith(themeSwatch(input.value));
      }
    };
    for (const key of THEME_ORDER) {
      const input = Object.assign(element('input', ''), {type: 'radio', name: 'welcome-theme', value: key, checked: (webLook.theme || 'fluid') === key});
      const tile = element('label', '', 'theme-tile');
      tile.append(input, themeSwatch(key), element('span', THEMES[key].label));
      input.addEventListener('change', () => { if (input.checked) { saveWebLook({theme: key, mode: webLook.mode || 'system'}, true); draw(); } });
      themes.append(tile);
    }
    const mode = choiceGroup('Light or dark', 'welcome-mode', [['system', 'Automatic'], ['light', 'Light'], ['dark', 'Dark']],
      webLook.mode || 'system', (value) => { saveWebLook({...webLook, mode: value}, true); draw(); });
    mode.classList.add('segmented');
    return [title, element('p', 'You can change it, and every color, any time in Settings.', 'lead'), themes, mode, footer(back(), next())];
  };

  const accounts = () => {
    const title = element('h2', 'Add your chat accounts');
    title.id = 'welcome-title';
    const list = element('div', '', 'account-list');
    const go = next('Skip for now');
    const again = () => accountRows(list, again).then((result) => {
      const any = result.accounts.length > 0;
      go.textContent = any ? 'Continue' : 'Skip for now';
      go.className = any ? 'primary' : 'quiet';
    }).catch((error) => list.replaceChildren(element('p', error.message, 'error')));
    again();
    return [title, element('p', 'Sign in to the services you chat on. Passwords stay on this computer. You can add more later in Settings.', 'lead'),
      list, footer(back(), go)];
  };

  const phone = () => {
    const title = element('h2', 'Texts from your phone');
    title.id = 'welcome-title';
    const badge = element('span', 'Optional', 'pill');
    return [badge, title, element('p', 'With FluidMessages for Android, your texts show up here and you can send them from this computer. '
      + 'It takes a password and an HTTPS address, and Settings walks you through both.', 'lead'),
    footer(back(), button('Connect my phone', () => finish(() => openSettings('settings-devices').catch(showError))),
      button('Start chatting', () => finish(), 'primary'))];
  };

  const steps = [intro, look, accounts, phone];

  function render() {
    const card = element('div', '', 'welcome-card');
    const progress = element('ol', '', 'welcome-progress');
    progress.setAttribute('aria-label', 'Step ' + (step + 1) + ' of ' + steps.length);
    steps.forEach((_, index) => progress.append(element('li', '', index === step ? 'current' : index < step ? 'done' : '')));
    card.append(progress, ...steps[step]());
    root.replaceChildren(card);
    const heading = card.querySelector('#welcome-title');
    heading.tabIndex = -1;
    heading.focus({preventScroll: true});
  }
  render();
}
