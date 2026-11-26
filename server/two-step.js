'use strict';
// Two-step sign-in for FluidMessages Web: after the password, a 6-digit code from an authenticator app.
// Offered right away whenever a password is first set (the sign-in page's setup, Settings → Phone and other devices,
// and the phone guide), once after signing in on an install that had a password before, and in Settings.
// Loaded after app.js and companion.js and uses their helpers (api, element, field, checkbox, confirmButton, qrSvg).
// The secret and recovery codes live only in this page while it shows them; nothing here is logged or stored.

const TWO_STEP_WORDS = {
  title: 'Two-step sign-in',
  explain: 'After your password, type a code from an authenticator app on your phone (like Aegis or 2FAS). '
    + 'Then a stolen password alone can’t get in.',
  scan: 'Open your authenticator app, tap + (add), and scan this code.',
  cantScan: 'Can’t scan it? Type this key into the app instead:',
  typeCode: 'Type the 6-digit code from your authenticator app',
  recovery: 'Save these recovery codes somewhere safe, like a password manager or a printout. If you lose your phone, '
    + 'each code signs you in once instead of a code from the app. They’re shown only now.',
  off: 'Two-step sign-in is off',
  offLine: 'Turn it on so a stolen password alone can’t get in.',
  skip: 'Skip for now',
};

/** A text box for a 6-digit code (or a recovery code, when [recovery] is true). */
function twoStepCodeInput(recovery = false) {
  const input = Object.assign(element('input', ''), {
    type: 'text', required: true, autocomplete: 'one-time-code', spellcheck: false,
    maxLength: recovery ? 20 : 7, inputMode: recovery ? 'text' : 'numeric',
    placeholder: recovery ? '123456 or XXXX-XXXX-XXXX' : '123456',
  });
  input.autocapitalize = recovery ? 'characters' : 'off';
  input.classList.add('two-step-code');
  return input;
}

/** The recovery codes, one per line, with Copy and Download buttons and an "I saved them" check before [done]. */
function recoveryCodesCard(codes, done, doneLabel = 'Done') {
  const card = element('div', '', 'stack two-step-recovery');
  card.append(element('p', TWO_STEP_WORDS.recovery, 'notice'));
  const list = element('ol', '', 'recovery-codes');
  for (const code of codes) list.append(element('li', code));
  const text = 'FluidMessages Web recovery codes\nEach code works once.\n\n' + codes.join('\n') + '\n';
  const row = element('div', '', 'button-row');
  const copy = element('button', 'Copy', 'quiet');
  copy.type = 'button';
  copy.addEventListener('click', () => {
    if (!navigator.clipboard?.writeText) return;
    navigator.clipboard.writeText(text).then(() => { copy.textContent = 'Copied'; setTimeout(() => { copy.textContent = 'Copy'; }, 2500); })
      .catch(() => {});
  });
  const download = element('button', 'Download (.txt)', 'quiet');
  download.type = 'button';
  download.addEventListener('click', () => {
    const link = Object.assign(element('a', ''), {href: URL.createObjectURL(new Blob([text], {type: 'text/plain'})),
      download: 'fluidmessages-recovery-codes.txt'});
    document.body.append(link);
    link.click();
    link.remove();
    setTimeout(() => URL.revokeObjectURL(link.href), 1000);
  });
  row.append(copy, download);
  const finish = element('button', doneLabel, 'primary');
  finish.type = 'button';
  finish.disabled = true;
  finish.addEventListener('click', () => done());
  card.append(list, row, checkbox('I saved them', false, (value) => { finish.disabled = !value; }), finish);
  return card;
}

/**
 * Setting up two-step sign-in, inline: a QR code and the key in groups, a code from the app to confirm, then the
 * recovery codes. [done] gets the new security state and whether it was skipped. [skipLabel] is the visible way out.
 */
function twoStepSetup(done, {skipLabel = TWO_STEP_WORDS.skip, intro = true} = {}) {
  const box = element('div', '', 'stack two-step-setup');
  const status = element('p', '', 'error');
  status.setAttribute('role', 'alert');
  const skip = element('button', skipLabel, 'quiet two-step-skip');
  skip.type = 'button';
  skip.addEventListener('click', async () => {
    skip.disabled = true;
    try { done(await api('auth/two-step/later', {}), true); } catch (error) { status.textContent = error.message; skip.disabled = false; }
  });
  const head = [];
  if (intro) head.push(element('h4', TWO_STEP_WORDS.title));
  head.push(element('p', TWO_STEP_WORDS.explain, 'muted'));
  box.append(...head, element('p', 'Getting your code ready…', 'hint'), status, skip);
  api('auth/two-step/begin', {}).then(({setup}) => {
    const form = element('form', '', 'stack');
    const steps = element('ol', '', 'steps');
    steps.append(element('li', TWO_STEP_WORDS.scan));
    const picture = element('div', '', 'qr-box');
    try { picture.append(qrSvg(setup.uri, 'QR code for your authenticator app')); } catch (error) { picture.hidden = true; }
    const key = element('div', '', 'token-box');
    const keyText = Object.assign(element('input', ''), {value: setup.secret, readOnly: true, spellcheck: false, autocomplete: 'off'});
    keyText.setAttribute('aria-label', 'Key for your authenticator app');
    keyText.addEventListener('focus', () => keyText.select());
    const copy = element('button', 'Copy', 'quiet');
    copy.type = 'button';
    copy.addEventListener('click', () => navigator.clipboard?.writeText(setup.secret.replace(/ /g, ''))
      .then(() => { copy.textContent = 'Copied'; }).catch(() => keyText.select()));
    key.append(keyText, copy);
    const code = twoStepCodeInput();
    const save = element('button', 'Turn on two-step sign-in', 'primary');
    form.append(steps, picture, element('p', TWO_STEP_WORDS.cantScan, 'hint'), key,
      field(TWO_STEP_WORDS.typeCode, code), status, save);
    form.addEventListener('submit', async (event) => {
      event.preventDefault();
      save.disabled = true;
      status.textContent = '';
      try {
        const result = await api('auth/two-step/confirm', {code: code.value});
        const {recovery_codes: codes, ...state} = result;
        box.replaceChildren(element('h4', 'Two-step sign-in is on'), recoveryCodesCard(codes, () => done(state, false)));
      } catch (error) { status.textContent = error.message; code.select(); }
      finally { save.disabled = false; }
    });
    box.replaceChildren(...head, form, skip);
    requestAnimationFrame(() => code.focus({preventScroll: true}));
  }).catch((error) => { status.textContent = error.message; });
  return box;
}

/** Settings → Phone and other devices → Two-step sign-in. [draw] redraws the section with a new security state. */
function twoStepSection(state, draw) {
  const area = element('div', '', 'stack two-step-area');
  area.id = 'two-step-settings';
  const info = state.two_step || {};
  area.append(element('h4', TWO_STEP_WORDS.title));
  if (!info.on) {
    const notice = element('div', '', 'notice two-step-off');
    notice.append(element('strong', TWO_STEP_WORDS.off), element('p', TWO_STEP_WORDS.offLine));
    const on = element('button', 'Turn on', 'primary');
    on.type = 'button';
    on.addEventListener('click', () => area.replaceChildren(twoStepSetup((next) => draw(next), {skipLabel: 'Cancel'})));
    notice.append(on);
    area.append(element('p', TWO_STEP_WORDS.explain, 'muted'), notice);
    return area;
  }
  const left = info.recovery_left;
  area.append(element('p', 'On. Other devices type a code from your authenticator app after the password. '
    + left + (left === 1 ? ' recovery code is' : ' recovery codes are') + ' left.', 'muted'));
  if (left <= 2) area.append(element('p', 'You’re running out of recovery codes. Make new ones below.', 'notice'));
  if (info.readable === false) {
    area.append(element('p', 'Codes from your app can’t be checked right now: the server’s key file (auth-secret.key) is missing from its '
      + 'data folder. Recovery codes still work. Turn two-step sign-in off and on again to fix it.', 'notice'));
  }

  const fresh = element('details', '', 'more');
  const freshForm = element('form', '', 'stack');
  const freshCode = twoStepCodeInput(true);
  const freshStatus = element('p', '', 'error');
  freshStatus.setAttribute('role', 'alert');
  const make = element('button', 'Make new recovery codes', 'primary');
  freshForm.append(element('p', 'Your old recovery codes stop working.', 'hint'), field(TWO_STEP_WORDS.typeCode, freshCode), freshStatus, make);
  freshForm.addEventListener('submit', async (event) => {
    event.preventDefault();
    make.disabled = true;
    freshStatus.textContent = '';
    try {
      const {recovery_codes: codes, ...next} = await api('auth/two-step/recovery', {code: freshCode.value});
      fresh.replaceChildren(recoveryCodesCard(codes, () => draw(next)));
      fresh.open = true;
    } catch (error) { freshStatus.textContent = error.message; }
    finally { make.disabled = false; }
  });
  fresh.append(element('summary', 'Make new recovery codes'), freshForm);

  const off = element('details', '', 'more');
  off.append(element('summary', 'Turn off'));
  if (state.here) {
    // The this-computer rule: whoever is at the computer FluidMessages runs on can already open everything.
    off.append(element('p', 'You’re on the computer FluidMessages runs on, so no code is needed. Use this if you lost your authenticator app.', 'hint'),
      confirmButton('Turn off two-step sign-in', 'Click again to turn it off', async () => {
        try { draw(await api('auth/two-step/off', {})); } catch (error) { showError(error); }
      }, 'quiet'));
  } else {
    const form = element('form', '', 'stack');
    form.append(Object.assign(element('input', ''), {type: 'text', name: 'username', autocomplete: 'username', value: 'FluidMessages Web', hidden: true}));
    const password = Object.assign(element('input', ''), {type: 'password', autocomplete: 'current-password', required: true, maxLength: 1024});
    const code = twoStepCodeInput(true);
    const status = element('p', '', 'error');
    status.setAttribute('role', 'alert');
    const save = element('button', 'Turn off two-step sign-in', 'quiet');
    form.append(field('Password', password), field('Code from your app, or a recovery code', code), status, save);
    form.addEventListener('submit', async (event) => {
      event.preventDefault();
      save.disabled = true;
      status.textContent = '';
      try { draw(await api('auth/two-step/off', {password: password.value, code: code.value})); } catch (error) { status.textContent = error.message; }
      finally { save.disabled = false; }
    });
    off.append(form);
  }
  area.append(fresh, off);
  return area;
}

/**
 * The full-page offer on the sign-in page: right after a password is set there, and once after signing in on an
 * install that has a password without two-step sign-in. [then] opens the app, whatever was chosen.
 */
function showTwoStepOffer(then, {skipLabel = TWO_STEP_WORDS.skip} = {}) {
  get('pair').hidden = false;
  get('workspace').hidden = true;
  for (const id of ['setup-form', 'login-form', 'token-form', 'two-step-form']) { const form = get(id); if (form) form.hidden = true; }
  get('reset-help').hidden = true;
  get('pair-error').textContent = '';
  get('sign-in-title').textContent = 'Turn on two-step sign-in';
  get('sign-in-intro').textContent = 'One more step to keep your chats safe. It takes about a minute.';
  const holder = get('two-step-offer');
  holder.hidden = false;
  holder.replaceChildren(twoStepSetup(() => {
    holder.replaceChildren();
    holder.hidden = true;
    then();
  }, {skipLabel, intro: false}));
}
