'use strict';
// Themes and appearance, the rest of the phone's page (ThemeEditor.kt): saved palettes, wallpaper, inbox and
// toolbar, bubble tails, and motion and sound. Loaded after companion.js and uses its helpers (field, checkbox,
// saveWebLook, THEMES, webLook, mixColor, usingPhoneLook).

// What a theme brings: picking or resetting one restores these. Light or dark, motion, sounds, and haptics stay.
const THEME_KEYS = ['colors', 'radius', 'size', 'padding', 'wallpaper', 'searchPosition', 'toolbarFinish', 'chromeOpacity', 'density',
  'tails', 'activePalette'];
const WALLPAPERS = [['none', 'Solid'], ['horizon', 'Horizon'], ['aurora', 'Aurora'], ['love', 'Love'], ['orchard', 'Orchard']];
const MOTION = [[0, 'Off'], [130, 'Quick'], [220, 'Standard'], [360, 'Relaxed']];
const WALLPAPER_PHOTO = 'look.wallpaperPhoto';

/** [look] with theme [key]'s colors, wallpaper (Love has its own), and default layout; or a saved palette's. */
function themeLook(look, key, palette) {
  const next = {...look};
  for (const name of THEME_KEYS) delete next[name];
  const saved = palette && look.palettes?.[palette];
  next.theme = saved ? saved.theme : key;
  if (next.theme === 'love') next.wallpaper = 'love';
  if (saved) { next.colors = {...saved.colors}; next.activePalette = palette; }
  return next;
}

/** The look settings that belong to this web app, whichever settings the colors follow. */
function ownLook() { return usingPhoneLook() ? {} : webLook; }

function wallpaperBackground(name, base) {
  const tint = (color, amount) => mixColor(base, color, amount);
  switch (name) {
    case 'horizon': return 'linear-gradient(180deg, ' + tint('#317bc4', .25) + ', ' + base + ', ' + tint('#e29b70', .27) + ')';
    case 'aurora': return 'linear-gradient(135deg, ' + base + ', ' + tint('#35b6a4', .28) + ', ' + tint('#735bce', .19) + ', ' + base + ')';
    case 'love': return 'radial-gradient(circle at 50% 35%, ' + tint('#e96b9b', .32) + ', ' + tint('#9345a0', .15) + ', ' + base + ')';
    case 'orchard': return 'linear-gradient(180deg, ' + base + ', ' + tint('#4c9e64', .24) + ', ' + tint('#cda05a', .2) + ')';
    case 'photo': {
      const photo = localStorage.getItem(WALLPAPER_PHOTO);
      if (!photo || !/^data:image\/(jpeg|png|webp);base64,[A-Za-z0-9+/=]+$/.test(photo)) return '';
      // The photo is dimmed toward the background color, so bubbles and text stay readable.
      const veil = 'color-mix(in srgb, ' + base + ' 72%, transparent)';
      return 'linear-gradient(' + veil + ', ' + veil + '), center / cover no-repeat url("' + photo + '")';
    }
    default: return '';
  }
}

function canvasColor() {
  const value = appearance.colors?.canvas || getComputedStyle(document.documentElement).getPropertyValue('--canvas').trim();
  return /^#[0-9a-f]{6}$/i.test(value) ? value : (appearance.dark ? '#101012' : '#ffffff');
}

/** This chat's wallpaper, or the global one. Called whenever a chat is drawn. */
function applyChatWallpaper() {
  const box = document.getElementById('conversation');
  if (!box) return;
  const own = typeof active === 'string' && active ? webLook.chatWallpapers?.[threadAddress(active)] : '';
  const name = own || ownLook().wallpaper || 'none';
  const background = wallpaperBackground(name, canvasColor());
  box.style.background = background;
  box.dataset.wallpaper = background ? name : '';
}

/** Applied after every appearance change (app.js applyAppearance). */
function applyLookExtras() {
  const root = document.documentElement;
  const look = ownLook();
  root.dataset.search = look.searchPosition || 'bottom';
  root.dataset.density = look.density || 'standard';
  root.dataset.tails = String(!!look.tails);
  root.dataset.toolbar = look.toolbarFinish || 'plain';
  root.style.setProperty('--chrome-opacity', (look.chromeOpacity ?? 84) + '%');
  // Motion follows this browser's own choice, like sounds, even while colors follow the phone.
  const speed = webLook.speed ?? 220;
  root.dataset.motion = speed === 0 ? 'off' : 'on';
  root.style.setProperty('--motion-ms', speed + 'ms');
  applyChatWallpaper();
}

// Sounds and haptics: a soft swish when you send and a short three-note chime when a message arrives, made
// on the spot with Web Audio, so nothing is downloaded. Browsers stay quiet until you've clicked the page once.

let audioContext = null;

function playTone(receive) {
  const choice = webLook[receive ? 'receiveSound' : 'sendSound'] || 'fluid';
  if (choice === 'silent' || !(window.AudioContext || window.webkitAudioContext)) return;
  try {
    audioContext ||= new (window.AudioContext || window.webkitAudioContext)();
    if (audioContext.state === 'suspended') audioContext.resume().catch(() => {});
    const now = audioContext.currentTime + .02;
    const out = audioContext.createGain();
    out.gain.value = .16;
    out.connect(audioContext.destination);
    if (receive) {
      // E6, G6, C7: short bell-like notes.
      [1318.5, 1568, 2093].forEach((frequency, index) => {
        const start = now + index * .09;
        const tone = audioContext.createOscillator();
        const level = audioContext.createGain();
        tone.type = 'sine';
        tone.frequency.value = frequency;
        level.gain.setValueAtTime(0, start);
        level.gain.linearRampToValueAtTime(.9, start + .008);
        level.gain.exponentialRampToValueAtTime(.001, start + .32);
        tone.connect(level).connect(out);
        tone.start(start);
        tone.stop(start + .34);
      });
    } else {
      // A swish: a burst of noise swept through a band-pass filter.
      const length = Math.round(audioContext.sampleRate * .22);
      const buffer = audioContext.createBuffer(1, length, audioContext.sampleRate);
      const data = buffer.getChannelData(0);
      for (let index = 0; index < length; index++) data[index] = (Math.random() * 2 - 1) * Math.sin(Math.PI * index / length);
      const noise = audioContext.createBufferSource();
      noise.buffer = buffer;
      const band = audioContext.createBiquadFilter();
      band.type = 'bandpass';
      band.Q.value = 1.4;
      band.frequency.setValueAtTime(700, now);
      band.frequency.exponentialRampToValueAtTime(4200, now + .2);
      noise.connect(band).connect(out);
      noise.start(now);
    }
  } catch (error) { /* no sound on this browser */ }
}

const HAPTICS = {soft: [6], tap: [14], confirm: [12, 60, 18], long: [45]};

function vibrateOnce() {
  const pattern = HAPTICS[webLook.haptics || 'tap'];
  if (pattern && navigator.vibrate) navigator.vibrate(pattern);
}

/** After you send (app.js). */
function sendFeedback() {
  playTone(false);
  vibrateOnce();
}

const heard = new Set();
let heardFirst = false;

/** New messages from other people chime, except in muted or blocked chats and while the page is in the sample chats. */
function lookNoticeIncoming(messages) {
  const fresh = [];
  for (const message of messages || []) {
    const id = message.id || message.date + ':' + message.address;
    if (heard.has(id)) continue;
    heard.add(id);
    if (heardFirst && message.type === 1 && !message.event && Date.now() - message.date < 10 * 60000) fresh.push(message);
  }
  heardFirst = true;
  const quiet = (message) => typeof chatMuted === 'function' && chatMuted(message.address);
  if (fresh.some((message) => !quiet(message))) playTone(true);
}

// Settings → Themes and appearance

function lookChoice(label, value, options, change) {
  const select = element('select', '');
  for (const [key, text] of options) select.append(Object.assign(element('option', text), {value: String(key)}));
  select.value = String(value);
  select.addEventListener('change', () => change(select.value));
  return field(label, select);
}

function lookSlider(label, value, min, max, unit, change) {
  const input = Object.assign(element('input', ''), {type: 'range', min, max, step: 1, value});
  const output = element('output', value + unit);
  input.addEventListener('input', () => { output.textContent = input.value + unit; change(Number(input.value)); });
  const wrap = field(label, input);
  wrap.append(output);
  wrap.classList.add('slider');
  return wrap;
}

/** A wallpaper tile: the design drawn in the current background color. */
function wallpaperTile(name, label, group, checked, change) {
  const input = Object.assign(element('input', ''), {type: 'radio', name: group, value: name, checked});
  const tile = element('label', '', 'theme-tile wallpaper-tile');
  const swatch = element('span', '', 'wallpaper-swatch');
  swatch.setAttribute('aria-hidden', 'true');
  swatch.style.background = wallpaperBackground(name, canvasColor()) || canvasColor();
  tile.append(input, swatch, element('span', label));
  input.addEventListener('change', () => { if (input.checked) change(name); });
  return tile;
}

/** Scales a photo to at most 1600 pixels and keeps it in this browser, for the wallpaper. */
async function wallpaperPhoto(file) {
  const bitmap = await createImageBitmap(file);
  const scale = Math.min(1, 1600 / Math.max(bitmap.width, bitmap.height));
  const canvas = Object.assign(document.createElement('canvas'), {width: Math.round(bitmap.width * scale), height: Math.round(bitmap.height * scale)});
  canvas.getContext('2d').drawImage(bitmap, 0, 0, canvas.width, canvas.height);
  bitmap.close?.();
  for (const quality of [.8, .65, .5]) {
    const data = canvas.toDataURL('image/jpeg', quality);
    try { localStorage.setItem(WALLPAPER_PHOTO, data); return; } catch (error) { /* too big: try smaller */ }
  }
  throw new Error('That photo is too big to keep in this browser. Try a smaller one.');
}

/** A wallpaper picker; [group] names the radio set, [current] the choice, and [change] saves one. */
function wallpaperPicker(group, current, change, inherit = false) {
  const grid = element('fieldset', '', 'theme-grid wallpaper-grid');
  grid.append(element('legend', 'Conversation wallpaper'));
  const choices = (inherit ? [['', 'Global']] : []).concat(WALLPAPERS, [['photo', 'Your photo']]);
  for (const [name, label] of choices) {
    grid.append(wallpaperTile(name, label, group, (current || (inherit ? '' : 'none')) === name, (value) => {
      if (value !== 'photo' || localStorage.getItem(WALLPAPER_PHOTO)) { change(value); return; }
      choose.click();
    }));
  }
  const choose = Object.assign(element('input', ''), {type: 'file', accept: 'image/*', hidden: true});
  choose.addEventListener('change', async () => {
    const file = choose.files[0];
    if (!file) return;
    try { await wallpaperPhoto(file); change('photo'); } catch (error) { showError(error); }
  });
  const pick = element('button', 'Choose a photo…');
  pick.type = 'button';
  pick.addEventListener('click', () => choose.click());
  const note = element('small', 'Your photo stays in this browser. Other browsers show the solid background.', 'hint');
  const box = element('div', '', 'stack');
  box.append(grid, pick, choose, note);
  return box;
}

/**
 * The appearance page's extra groups (companion.js appearanceSection): saved palettes in Color theme and
 * Customize colors, Wallpaper, Inbox and toolbar, bubble tails, and Motion and sound (outside the part that
 * follows the phone, since sounds and haptics belong to this device).
 */
function lookExtras({themes, colorsBox, bubbles, refresh}) {
  const redraw = () => { refresh(); drawPalettes(); };
  // Saved palettes are listed after the named themes.
  const drawPalettes = () => {
    for (const tile of themes.querySelectorAll('.palette-tile')) tile.remove();
    for (const name of Object.keys(webLook.palettes || {}).sort()) {
      const palette = webLook.palettes[name];
      const input = Object.assign(element('input', ''), {type: 'radio', name: 'web-theme', value: 'custom:' + name, checked: webLook.activePalette === name});
      const tile = element('label', '', 'theme-tile palette-tile');
      const swatch = themeSwatch(palette.theme);
      // Its own class, so redrawing the named themes' tiles leaves this one's colors alone.
      swatch.className = 'palette-swatch';
      const own = swatch.querySelector('.swatch-bubble.out');
      if (own && palette.colors?.sms) { own.style.background = bubbleFill(palette.colors.sms); own.style.color = bubbleInk(palette.colors.sms); }
      tile.append(input, swatch, element('span', name));
      input.addEventListener('change', () => { if (input.checked) { saveWebLook(themeLook(webLook, palette.theme, name), true); redraw(); } });
      themes.append(tile);
    }
    for (const input of themes.querySelectorAll('input:not([value^="custom:"])')) if (webLook.activePalette) input.checked = false;
  };
  drawPalettes();
  const save = element('details', '', 'more palette-save');
  save.append(element('summary', 'Save custom palette'));
  const name = Object.assign(element('input', ''), {maxLength: 40, placeholder: 'My colors', value: webLook.activePalette || ''});
  const keep = element('button', 'Save palette', 'primary');
  keep.type = 'button';
  const status = element('p', '', 'muted');
  keep.addEventListener('click', () => {
    const label = name.value.trim();
    if (!label) { name.focus(); return; }
    const palettes = {...(webLook.palettes || {})};
    if (!palettes[label] && Object.keys(palettes).length >= 20) { status.textContent = 'You can keep up to 20 palettes.'; return; }
    palettes[label] = {theme: webLook.theme || 'fluid', colors: {...(webLook.colors || {})}};
    saveWebLook({...webLook, palettes, activePalette: label}, true);
    status.textContent = 'Saved as “' + label + '”. It’s in Color theme.';
    redraw();
  });
  const remove = confirmButton('Delete this saved palette', 'Tap again to delete', async () => {
    const palettes = {...(webLook.palettes || {})};
    delete palettes[webLook.activePalette];
    const {activePalette, ...rest} = webLook;
    saveWebLook({...rest, palettes}, true);
    redraw();
  }, 'quiet');
  const row = element('div', '', 'button-row');
  row.append(keep, remove);
  save.append(field('Palette name', name), row, status);
  save.addEventListener('toggle', () => { remove.hidden = !webLook.activePalette; name.value = webLook.activePalette || name.value; });
  colorsBox.append(save);

  const wallpaper = element('details', '', 'more');
  wallpaper.append(element('summary', 'Wallpaper'));
  const drawWallpaper = () => {
    wallpaper.querySelector('.stack')?.remove();
    wallpaper.append(wallpaperPicker('web-wallpaper', webLook.wallpaper, (value) => { saveWebLook({...webLook, wallpaper: value}, true); drawWallpaper(); }));
  };
  drawWallpaper();

  const inbox = element('details', '', 'more');
  inbox.append(element('summary', 'Inbox and toolbar'));
  const opacity = lookSlider('Toolbar opacity', webLook.chromeOpacity ?? 84, 25, 100, '%', (value) => saveWebLook({...webLook, chromeOpacity: value}));
  opacity.hidden = (webLook.toolbarFinish || 'plain') !== 'floating';
  inbox.append(
    lookChoice('Search position', webLook.searchPosition || 'bottom', [['top', 'Top search bar'], ['bottom', 'Bottom dock']],
      (value) => saveWebLook({...webLook, searchPosition: value}, true)),
    lookChoice('Toolbar finish', webLook.toolbarFinish || 'plain', [['plain', 'Plain'], ['tinted', 'Tinted'], ['floating', 'Floating translucent']],
      (value) => { opacity.hidden = value !== 'floating'; saveWebLook({...webLook, toolbarFinish: value}, true); }),
    opacity,
    lookChoice('Inbox density', webLook.density || 'standard', [['compact', 'Compact'], ['standard', 'Standard'], ['roomy', 'Roomy']],
      (value) => saveWebLook({...webLook, density: value}, true)));

  bubbles.querySelector('summary').after(checkbox('Bubble tails', !!webLook.tails, (value) => saveWebLook({...webLook, tails: value}, true),
    'A small tail on the last bubble in each run.'));

  // Sounds, haptics, and motion belong to this browser, so they apply even while colors follow the phone.
  const saveFeedback = (next, now) => { saveWebLook(next, now); applyLookExtras(); };
  const feedback = element('details', '', 'more feedback-group');
  feedback.append(element('summary', 'Motion and sound'));
  const speed = webLook.speed ?? 220;
  const preset = MOTION.some(([value]) => value === speed) ? speed : 'custom';
  const motionOptions = MOTION.concat(preset === 'custom' ? [['custom', 'Custom · ' + speed + ' ms']] : []);
  const duration = lookSlider('Animation duration', speed, 0, 800, ' ms', (value) => saveFeedback({...webLook, speed: value}));
  const tones = [['fluid', 'Fluid'], ['silent', 'Silent']];
  const test = element('div', '', 'button-row');
  for (const [label, run] of [['Hear send', () => playTone(false)], ['Hear receive', () => playTone(true)], ['Test haptics', vibrateOnce]]) {
    const button = element('button', label, 'quiet');
    button.type = 'button';
    button.addEventListener('click', run);
    test.append(button);
  }
  feedback.append(
    lookChoice('Motion preset', preset, motionOptions, (value) => { if (value !== 'custom') { saveFeedback({...webLook, speed: Number(value)}, true); duration.querySelector('input').value = value; duration.querySelector('output').textContent = value + ' ms'; } }),
    duration,
    lookChoice('Outgoing sound', webLook.sendSound || 'fluid', tones, (value) => saveFeedback({...webLook, sendSound: value}, true)),
    lookChoice('Receive chime', webLook.receiveSound || 'fluid', tones, (value) => saveFeedback({...webLook, receiveSound: value}, true)),
    lookChoice('Send haptics', webLook.haptics || 'tap', [['off', 'Off'], ['soft', 'Soft tick'], ['tap', 'Tap'], ['confirm', 'Confirmation'], ['long', 'Long press']],
      (value) => saveFeedback({...webLook, haptics: value}, true)),
    test,
    element('p', 'Motion Off stops message effects and animations here. Sounds, haptics, and motion stay when you switch themes. '
      + (navigator.vibrate ? '' : 'This device can’t vibrate, so haptics do nothing here.'), 'muted'));
  return {groups: [wallpaper, inbox], feedback};
}
