'use strict';
// First run and the phone sync guide, drawn like the Android app's setup guide (OnboardingScreen.kt and
// OnboardingVisuals.kt): a title, a moving picture, a few tips that slide in, progress bars, and one big button.
// Steps that only make sense on a phone (default SMS app, permissions, backups to a phone folder) are left out or
// swapped for the web's own versions. Loaded after app.js, companion.js, and cards.js and uses their helpers (api, get,
// element, saveWebLook, editAccount, checkbox, passwordForm, confirmButton, openSettings, railIcon, pollCard, ...).
// The sample inbox (startSample) is in chats.js, which loads later and is only used once the page is running.

const GUIDE_RELEASES = 'https://github.com/Lin8x/FluidMessages/releases/latest';
const guideStill = () => !!window.matchMedia?.('(prefers-reduced-motion: reduce)').matches;

/** Static drawing markup (never user data) as a node. */
function drawing(markup, className = '') {
  const template = document.createElement('template');
  template.innerHTML = markup.trim();
  const node = template.content.firstElementChild;
  if (className) node.classList.add(...className.split(' '));
  return node;
}

// The phone's tip icons (TipIcon), redrawn as 24-unit strokes.
const TIP_ICONS = {
  phone: '<path fill="currentColor" stroke="none" d="M6.62 10.79a15.05 15.05 0 0 0 6.59 6.59l2.2-2.2c.27-.27.67-.36 1.02-.24 1.12.37 2.33.57 3.57.57.55 0 1 .45 1 1V20c0 .55-.45 1-1 1C10.61 21 3 13.39 3 4c0-.55.45-1 1-1h3.5c.55 0 1 .45 1 1 0 1.25.2 2.45.57 3.57.11.35.03.74-.25 1.02l-2.2 2.2z"/>',
  chat: '<rect x="2" y="3.4" width="20" height="14.4" rx="7"/><path d="M6.7 17.3 4.3 22"/>',
  practice: '<circle cx="12" cy="12" r="9.6"/><path d="m7.2 12.5 3.6 3.3 6.5-7.2"/>',
  tag: '<rect x="2.4" y="7.2" width="19.2" height="9.6" rx="2.9"/>',
  people: '<circle cx="8.4" cy="7.7" r="3.4"/><circle cx="16.3" cy="7.7" r="3.4"/><path d="M2.9 19.4a5.5 5.6 0 0 1 11 0M10.8 19.4a5.5 5.6 0 0 1 11 0"/>',
  lock: '<rect x="4.8" y="10.6" width="14.4" height="10.4" rx="2.4"/><path d="M8 10.6V7.8a4 4 0 0 1 8 0v2.8"/>',
  finger: '<path d="M8.6 20.4c-1.3-1.9-2-4.1-2-6.4a5.4 5.4 0 0 1 10.8 0M12 14v1.4c0 1.9.6 3.7 1.6 5.1M17.4 14v.6M4.2 11a8.2 8.2 0 0 1 15.6 0"/>',
  folder: '<rect x="2" y="7.2" width="20" height="13.4" rx="2"/><path d="M2 7.2 9.6 3.8"/>',
  mask: '<ellipse cx="12" cy="12" rx="10" ry="5.3"/><circle cx="8.2" cy="12" r="1.9" fill="currentColor"/><circle cx="15.8" cy="12" r="1.9" fill="currentColor"/>',
  timer: '<circle cx="12" cy="13.4" r="9.1"/><path d="M12 13.4V8.2M9.6 1.9h4.8"/>',
  laptop: '<rect x="3.8" y="4.8" width="16.4" height="11" rx="1.2"/><path d="M1 18.7h22"/>',
  optional: '<circle cx="12" cy="12" r="9.1"/><path d="M7.7 12h8.6"/>',
  download: '<path d="M12 3v12M7 10.5l5 5 5-5M4 20.5h16"/>',
  key: '<circle cx="7.5" cy="12" r="4.3"/><path d="M11.8 12H22M18.5 12v3.4M21.5 12v2.6"/>',
  gear: '<circle cx="12" cy="12" r="3.3"/><path d="M12 2.6v2.6M12 18.8v2.6M2.6 12h2.6M18.8 12h2.6M5.4 5.4l1.8 1.8M16.8 16.8l1.8 1.8M5.4 18.6l1.8-1.8M16.8 7.2l1.8-1.8"/>',
  wifi: '<path d="M2.5 9a14 14 0 0 1 19 0M5.8 12.6a9 9 0 0 1 12.4 0M9.1 16.1a4.2 4.2 0 0 1 5.8 0"/><circle cx="12" cy="19.6" r="1.2" fill="currentColor"/>',
};

function tipIcon(name) {
  return drawing('<svg viewBox="0 0 24 24" width="24" height="24" fill="none" stroke="currentColor" stroke-width="1.9" '
    + 'stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">' + (TIP_ICONS[name] || TIP_ICONS.optional) + '</svg>');
}

const LOCK_SHAPES = {
  green: '<rect x="4.8" y="10.6" width="14.4" height="10.4" rx="2.4" fill="currentColor"/><path d="M8 10.6V7.8a4 4 0 0 1 8 0v2.8"/>',
  yellow: '<rect x="4.8" y="10.6" width="14.4" height="10.4" rx="2.4" fill="currentColor"/><path d="M8 10.6V7.8a4 4 0 0 1 8 0v2.8"/>',
  red: '<rect x="4.8" y="10.6" width="14.4" height="10.4" rx="2.4" fill="currentColor"/><path d="M8 10.6V7.8a4 4 0 0 1 7.6-1.8"/>',
};
function lockIcon(tone, size = 28) {
  return drawing('<svg viewBox="0 0 24 24" width="' + size + '" height="' + size + '" fill="none" stroke="currentColor" stroke-width="2.2" '
    + 'stroke-linecap="round" aria-hidden="true">' + LOCK_SHAPES[tone] + '</svg>', 'g-lock tone-' + tone);
}

/**
 * Tips that slide in one after another, like the phone's Tips(). A tip with [run] or [href] is a button or link
 * with a chevron.
 */
function guideTips(list) {
  const box = element('div', '', 'guide-tips');
  list.forEach(({icon, title, line, run, href}, index) => {
    const tip = href ? Object.assign(element('a', ''), {href, target: '_blank', rel: 'noopener noreferrer'})
      : run ? Object.assign(element('button', ''), {type: 'button'}) : element('div', '');
    tip.className = 'guide-tip' + (run || href ? ' action' : '');
    tip.style.setProperty('--i', index);
    if (run) tip.addEventListener('click', run);
    const badge = element('span', '', 'guide-tip-icon');
    badge.append(tipIcon(icon));
    const words = element('span', '', 'guide-tip-words');
    words.append(element('strong', title), element('span', line));
    tip.append(badge, words);
    if (run || href) tip.append(Object.assign(element('span', '›', 'guide-chevron'), {ariaHidden: 'true'}));
    box.append(tip);
  });
  return box;
}

/**
 * The shared frame: Back (or the app name) and Skip along the top, progress bars, a title and subtitle, the
 * step's pictures and tips, and one wide button at the bottom. Steps slide in from the side you're heading.
 */
function guideFlow(root, {steps, skipLabel = 'Skip setup', onSkip, onBackFromFirst, brand = 'FluidMessages', start = 0}) {
  // Opening a guide again (Settings → Help → Setup guide) stops the one already there, with all its timers.
  root.guideFlow?.stop();
  let step = Math.max(0, Math.min(steps.length - 1, start));
  let stops = [];
  const flow = {
    get step() { return step; },
    /** Runs [run] every [ms] while this step is showing and the tab is visible, unless the device asks for reduced motion. */
    every(ms, run) {
      if (guideStill()) return;
      const id = setInterval(() => { if (root.isConnected && !root.hidden && !document.hidden) run(); }, ms);
      stops.push(() => clearInterval(id));
    },
    after(ms, run) {
      const id = setTimeout(() => { if (root.isConnected) run(); }, ms);
      stops.push(() => clearTimeout(id));
    },
    onLeave(run) { stops.push(run); },
    // Leaving a step or closing the guide also ends any effect its pictures started (cards.js).
    stop() { for (const run of stops.splice(0)) run(); if (typeof stopEffects === 'function') stopEffects(); },
    go(next, direction = next >= step ? 1 : -1) {
      flow.stop();
      step = Math.max(0, Math.min(steps.length - 1, next));
      draw(direction);
    },
    next() { flow.go(step + 1, 1); },
    back() { if (step > 0) flow.go(step - 1, -1); else if (onBackFromFirst) onBackFromFirst(); },
    redraw() { flow.stop(); draw(0); },
  };

  function draw(direction) {
    const current = steps[step];
    const frame = element('div', '', 'guide');
    const top = element('div', '', 'guide-top');
    if (step > 0 || onBackFromFirst) {
      const back = element('button', 'Back', 'quiet guide-back');
      back.type = 'button';
      back.addEventListener('click', () => flow.back());
      top.append(back);
    } else top.append(element('span', brand, 'guide-brand'));
    if (step < steps.length - 1 && onSkip) {
      const skip = element('button', skipLabel, 'quiet');
      skip.type = 'button';
      skip.addEventListener('click', () => onSkip());
      top.append(skip);
    }
    const bars = element('div', '', 'guide-progress');
    bars.setAttribute('role', 'img');
    bars.setAttribute('aria-label', 'Step ' + (step + 1) + ' of ' + steps.length);
    steps.forEach((_, index) => bars.append(element('span', '', index <= step ? 'on' : '')));

    const scroll = element('div', '', 'guide-scroll');
    const page = element('div', '', 'guide-page' + (direction > 0 ? ' from-end' : direction < 0 ? ' from-start' : ''));
    const heading = element('h1', current.title);
    heading.id = root.id ? root.id + '-title' : 'guide-title';
    heading.tabIndex = -1;
    const head = element('div', '', 'guide-head');
    head.append(heading, element('p', current.subtitle, 'guide-sub'));
    page.append(head, ...current.body(flow));
    scroll.append(page);

    const bottom = element('div', '', 'guide-bottom');
    const actions = current.actions ? current.actions(flow) : [['Continue', () => flow.next()]];
    actions.forEach(([label, run, options = {}], index) => {
      const button = element('button', label, index === 0 ? 'primary' : 'quiet');
      button.type = 'button';
      if (options.disabled) button.disabled = true;
      if (options.id) button.id = options.id;
      button.addEventListener('click', run);
      bottom.append(button);
    });
    frame.append(top, bars, scroll, bottom);
    root.replaceChildren(frame);
    root.setAttribute('aria-labelledby', heading.id);
    heading.focus({preventScroll: true});
  }
  draw(0);
  root.guideFlow = flow;
  return flow;
}

// ---- Pictures, after OnboardingVisuals.kt. Each is an original drawing in the theme's colors. ----

/** The phone's SampleConversation: one bubble of each kind, in the current theme. */
function sampleConversation() {
  const card = element('div', '', 'g-sample');
  card.append(element('span', 'Sample conversation', 'g-sample-label'));
  for (const [text, kind] of [['Coffee at ten?', 'incoming'], ['See you there!', 'sms'], ['RCS preview', 'rcs'], ['Automated preview', 'automated'],
    ['Matrix preview', 'matrix'], ['XMPP preview', 'xmpp'], ['Email preview', 'email'], ['IRC preview', 'irc']]) {
    const bubble = element('span', text, 'g-bubble b-' + kind);
    card.append(bubble);
  }
  [...card.querySelectorAll('.g-bubble')].forEach((bubble, index) => bubble.style.setProperty('--i', index));
  return card;
}

/** Calls and Messages, taking turns being selected (the phone's TabsIllustration). */
function tabsIllustration(flow) {
  const card = element('div', '', 'g-card g-tabs');
  const caption = element('span', 'All your chats', 'g-caption');
  const bar = element('div', '', 'g-tabbar');
  const tabs = {};
  for (const [key, label] of [['calls', 'Calls'], ['messages', 'Messages']]) {
    const tab = element('span', '', 'g-tab');
    tab.append(railIcon(key), element('span', label));
    tabs[key] = tab;
    bar.append(tab);
  }
  let calls = false;
  const show = () => {
    tabs.calls.classList.toggle('on', calls);
    tabs.messages.classList.toggle('on', !calls);
    caption.textContent = calls ? 'Recent calls, voice and video' : 'All your chats';
  };
  show();
  flow.every(1600, () => { calls = !calls; show(); });
  card.append(caption, bar);
  return card;
}

/** Three chats on three services, each with its tag (WaysIllustration). */
function waysIllustration() {
  const card = element('div', '', 'g-card g-ways');
  [['Mom', 'See you at dinner!', 'SMS'], ['Elena', 'Sent the photos', 'Matrix'], ['Book club', 'Next week’s pick?', 'Email']].forEach(([name, text, tag], index) => {
    const row = element('div', '', 'g-chat');
    row.style.setProperty('--i', index);
    const words = element('span', '', 'g-chat-words');
    words.append(element('strong', name), element('span', text));
    row.append(element('span', initials(name), 'avatar'), words, element('span', tag, 'g-tag'));
    card.append(row);
  });
  return card;
}

/** The three lock colors side by side (LocksIllustration). */
function locksIllustration() {
  const card = element('div', '', 'g-card g-row');
  for (const [tone, label] of [['green', 'Private'], ['yellow', 'Partly'], ['red', 'Open']]) {
    const item = element('span', '', 'g-item');
    const circle = element('span', '', 'g-circle' + (tone === 'green' ? ' g-pulse' : ''));
    circle.append(lockIcon(tone));
    item.append(circle, element('strong', label));
    card.append(item);
  }
  return card;
}

/** Three privacy tools, the middle one taking turns being on (PrivacyToolsIllustration). */
function privacyIllustration(flow) {
  const card = element('div', '', 'g-card g-row');
  const circles = ['key', 'laptop', 'people'].map((icon) => {
    const circle = element('span', '', 'g-circle big');
    circle.append(tipIcon(icon));
    card.append(circle);
    return circle;
  });
  let locked = guideStill();
  const show = () => circles[1].classList.toggle('on', locked);
  show();
  flow.every(1400, () => { locked = !locked; show(); });
  return card;
}

/** A bubble that fades while a timer ring runs down, then comes back (DisappearingIllustration). */
function disappearingIllustration() {
  const card = element('div', '', 'g-card g-disappear');
  card.append(drawing('<svg class="g-ring" viewBox="0 0 64 64" width="64" height="64" aria-hidden="true">'
    + '<circle cx="32" cy="32" r="25" class="g-ring-track"/><circle cx="32" cy="32" r="25" class="g-ring-run" pathLength="100"/></svg>'));
  const words = element('div', '', 'g-stack');
  words.append(element('span', 'Meet you at 6', 'g-bubble b-sms g-fade'), element('span', 'Gone when the timer ends', 'g-caption'));
  card.append(words);
  return card;
}

/** A bubble that changes through theme colors, readable in each (ColorsIllustration). */
function colorsIllustration(flow) {
  const card = element('div', '', 'g-card g-colors');
  const line = element('div', '', 'g-end');
  const bubble = element('span', 'Love this color!', 'g-bubble g-color-bubble');
  line.append(bubble);
  const dots = element('div', '', 'g-dots');
  const accent = getComputedStyle(document.documentElement).getPropertyValue('--accent').trim();
  const colors = [/^#[0-9a-f]{6}$/i.test(accent) ? accent : '#007aff', '#0a84ff', '#34c759', '#af52de', '#ff9500', '#ff2d55'];
  const swatches = colors.map((color) => {
    const dot = element('span', '', 'g-dot');
    dot.style.background = bubbleFill(color);
    dots.append(dot);
    return dot;
  });
  let index = 0;
  const show = () => {
    // White text on colored bubbles, with bright colors shaded until it reads, the same rule as real chats.
    bubble.style.background = bubbleFill(colors[index]);
    bubble.style.color = bubbleInk(colors[index]);
    swatches.forEach((dot, position) => dot.classList.toggle('on', position === index));
  };
  show();
  flow.every(1100, () => { index = (index + 1) % colors.length; show(); });
  card.append(line, dots);
  return card;
}

/** A message that springs in, with the chime's sound waves (MotionIllustration, without the phone's buzz). */
function motionIllustration(flow) {
  const card = element('div', '', 'g-card g-motion');
  const bubble = element('span', 'New message!', 'g-bubble b-sms g-spring');
  const holder = element('div', '', 'g-grow');
  holder.append(bubble);
  card.append(holder, drawing('<svg class="g-waves" viewBox="0 0 48 48" width="48" height="48" aria-hidden="true">'
    + '<path d="M20 18a8 8 0 0 1 0 12"/><path d="M26 12a16 16 0 0 1 0 24"/><path d="M32 6a24 24 0 0 1 0 36"/></svg>'));
  flow.every(1800, () => {
    card.classList.remove('arrive');
    void card.offsetWidth;
    card.classList.add('arrive');
  });
  card.classList.add('arrive');
  return card;
}

/** A phone and this computer, with messages travelling between them. */
function linkIllustration(state = 'moving') {
  const card = element('div', '', 'g-card g-link ' + state);
  card.append(drawing('<svg viewBox="0 0 240 110" width="240" height="110" aria-hidden="true">'
    + '<rect x="14" y="14" width="46" height="82" rx="10" class="g-ink"/><path d="M30 24h14" class="g-ink"/>'
    + '<rect x="22" y="40" width="22" height="9" rx="4.5" class="g-fill-muted"/><rect x="30" y="54" width="22" height="9" rx="4.5" class="g-fill-accent"/>'
    + '<rect x="126" y="18" width="88" height="58" rx="7" class="g-ink"/><path d="M110 90h120" class="g-ink thick"/>'
    + '<rect x="138" y="32" width="34" height="11" rx="5.5" class="g-fill-muted"/><rect x="162" y="50" width="38" height="11" rx="5.5" class="g-fill-accent"/>'
    + '<g class="g-travel"><circle cx="76" cy="55" r="4"/><circle cx="92" cy="55" r="4"/><circle cx="108" cy="55" r="4"/></g>'
    + '<g class="g-ok"><circle cx="92" cy="55" r="15"/><path d="m85 55 5 5 9-10"/></g></svg>'));
  return card;
}

/** A phone with its Settings list, one row lit up. */
function phoneSettingsIllustration() {
  const card = element('div', '', 'g-card g-phone-settings');
  const phone = element('div', '', 'g-phone');
  phone.append(element('strong', 'Settings'));
  for (const [label, lit] of [['Backups', false], ['FluidMessages Web', true], ['Battery and sync', false]]) {
    const row = element('span', label, 'g-setting' + (lit ? ' lit' : ''));
    phone.append(row);
  }
  card.append(phone);
  return card;
}

/** A phone with a download arrow dropping into it. */
function installIllustration() {
  const card = element('div', '', 'g-card g-install');
  card.append(drawing('<svg viewBox="0 0 120 110" width="120" height="110" aria-hidden="true">'
    + '<rect x="34" y="12" width="52" height="90" rx="11" class="g-ink"/><path d="M52 22h16" class="g-ink"/>'
    + '<g class="g-drop"><path d="M60 34v30M49 53l11 11 11-11" class="g-ink accent"/></g><path d="M46 80h28" class="g-ink accent"/></svg>'));
  return card;
}

/** A pairing token card, with its Copy button breathing. */
function tokenIllustration() {
  const card = element('div', '', 'g-card g-token');
  const box = element('span', '', 'g-token-box');
  box.append(tipIcon('key'), element('span', '••••••••••••', 'g-token-dots'), element('span', 'Copy', 'g-copy g-pulse'));
  card.append(box);
  return card;
}

/** A check mark drawing itself, with a little burst (ReadyIllustration). */
function readyIllustration() {
  const card = element('div', '', 'g-card g-ready');
  let rays = '';
  for (let ray = 0; ray < 8; ray++) {
    const angle = ray * Math.PI / 4;
    const point = (radius) => (55 + radius * Math.cos(angle)).toFixed(1) + ' ' + (55 + radius * Math.sin(angle)).toFixed(1);
    rays += '<path d="M' + point(48) + 'L' + point(54) + '"/>';
  }
  card.append(drawing('<svg viewBox="0 0 110 110" width="110" height="110" aria-hidden="true"><circle class="g-ready-dot" cx="55" cy="55" r="44"/>'
    + '<g class="g-rays">' + rays + '</g><path class="g-check" pathLength="100" d="M35 57l14 13 27-29"/></svg>'));
  return card;
}

// ---- Showcases: a small chat drawn with the app's own classes and card renderers (cards.js), playing one example
// after another. Fictional people and messages only, and nothing inside can be clicked or sent. ----

/**
 * Plays [scenes] in a frame: dots to pick one, a caption, and a click (or Enter) anywhere on the picture for the
 * next. Each scene's build(scene, tools) draws it; tools.at(ms, run) times its steps. With reduced motion nothing
 * advances by itself and every step runs at once, so each scene shows how it ends.
 */
function showcase(flow, scenes, label) {
  const still = guideStill();
  const box = element('div', '', 'g-show');
  const stage = element('div', '', 'g-stage');
  stage.tabIndex = 0;
  stage.setAttribute('role', 'button');
  stage.setAttribute('aria-label', label + ': next example');
  const caption = element('p', '', 'g-show-caption');
  const pips = element('div', '', 'g-pips');
  let index = 0;
  const timers = [];
  let waiting = false;
  // A hidden tab pauses the scenes; they pick up where they were when it shows again.
  const resume = () => { if (!document.hidden && waiting && stage.isConnected) { waiting = false; show(index); } };
  document.addEventListener('visibilitychange', resume);
  const clear = () => { for (const id of timers.splice(0)) clearTimeout(id); };
  flow.onLeave(() => { clear(); waiting = false; document.removeEventListener('visibilitychange', resume); });
  const at = (ms, run) => {
    if (still) { run(); return; }
    timers.push(setTimeout(() => {
      if (!stage.isConnected) return;
      if (document.hidden) { clear(); waiting = true; return; }
      run();
    }, ms));
  };
  for (const scene of scenes) scene.shown = 0;
  const buttons = scenes.map((scene, position) => {
    const pip = element('button', '', 'g-pip');
    pip.type = 'button';
    pip.setAttribute('aria-label', scene.caption);
    pip.addEventListener('click', (event) => { event.stopPropagation(); show(position); });
    pips.append(pip);
    return pip;
  });
  function show(next) {
    clear();
    index = (next + scenes.length) % scenes.length;
    const scene = scenes[index];
    caption.textContent = scene.caption;
    buttons.forEach((pip, position) => {
      pip.classList.toggle('on', position === index);
      pip.setAttribute('aria-current', String(position === index));
    });
    const content = element('div', '', 'g-scene');
    content.inert = true;
    content.setAttribute('aria-hidden', 'true');
    stage.replaceChildren(content);
    scene.build(content, {at, still, first: !(scene.shown++)});
    if (!still) at(scene.length || 2500, () => show(index + 1));
  }
  stage.addEventListener('click', () => show(index + 1));
  stage.addEventListener('keydown', (event) => {
    if (event.key !== 'Enter' && event.key !== ' ') return;
    event.preventDefault();
    show(index + 1);
  });
  show(0);
  box.append(stage, caption, pips);
  return box;
}

/** A pointer that glides to things in a scene and clicks them. Hidden with reduced motion. */
function guidePointer(scene, still) {
  const node = element('span', '', 'g-cursor');
  node.append(drawing('<svg viewBox="0 0 24 24" width="24" height="24" aria-hidden="true">'
    + '<path d="M5 2.8 19.2 11l-6.3 1.4 3.7 7-2.7 1.4-3.7-7L5 18.3z"/></svg>'), element('span', '', 'g-ripple'));
  if (still) node.hidden = true;
  scene.append(node);
  // It glides with translate (composited), from where left and top first put it.
  const glide = (x, y) => { node.style.translate = Math.round(x - node.offsetLeft) + 'px ' + Math.round(y - node.offsetTop) + 'px'; };
  return {
    node,
    /** Moves to [target]'s point (fractions of its box), or jumps to a point given in fractions of the scene. */
    to(target, fx = .5, fy = .5) {
      if (!(target instanceof Element)) { node.style.left = (target * 100) + '%'; node.style.top = (fx * 100) + '%'; node.style.translate = '0px 0px'; return; }
      const frame = scene.getBoundingClientRect();
      const box = target.getBoundingClientRect();
      glide(box.left - frame.left + box.width * fx, box.top - frame.top + box.height * fy);
    },
    /** Moves to a point in the scene, in pixels. */
    point(x, y) { glide(x, y); },
    click(right = false) {
      node.classList.remove('press', 'right');
      void node.offsetWidth;
      node.classList.add('press');
      if (right) node.classList.add('right');
    },
  };
}

/** One message row, built like app.js showMessages: .message > .bubble-line > .bubble-wrap > .bubble. */
function mockMessage(content, {out = false, protocol = 'matrix', author = ''} = {}) {
  const row = element('div', '', (out ? 'message out' : 'message') + ' p-' + protocol);
  if (!out && author) row.append(element('small', author, 'author'));
  const bubble = element('div', '', 'bubble');
  if (typeof content === 'string') bubble.append(typeof formattedText === 'function' ? formattedText(content) : content);
  else bubble.append(content);
  const wrap = element('div', '', 'bubble-wrap');
  wrap.append(bubble);
  const line = element('div', '', 'bubble-line');
  line.append(wrap);
  row.append(line);
  return {row, bubble, line};
}

/** A chat window: the head with its lock and tag, and the messages. */
function mockWindow(title, protocol, tone) {
  const win = element('div', '', 'g-window');
  const head = element('div', '', 'g-window-head');
  const name = element('span', '', 'g-window-name');
  name.append(element('strong', title), lockIcon(tone, 13));
  head.append(element('span', initials(title), 'avatar'), name, element('span', labels[protocol] || protocol, 'g-tag'));
  const thread = element('div', '', 'g-thread');
  win.append(head, thread);
  return {win, head, thread};
}

/** The message box, as app.js draws it, with a box that only looks like the real one. */
function mockComposer() {
  const form = element('div', '', 'composer g-composer');
  const row = element('div', '', 'compose-row');
  const box = element('div', '', 'g-textarea');
  const words = element('span', '', 'g-typed');
  box.append(words, element('span', 'Message', 'g-placeholder'));
  const send = element('button', 'Send', 'primary send-button');
  send.type = 'button';
  row.append(box, send);
  form.append(row);
  return {form, row, box, words, send};
}

const GUIDE_PHOTO = 'data:image/svg+xml,' + encodeURIComponent('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 120 90">'
  + '<rect width="120" height="90" fill="#7cc4f2"/><circle cx="92" cy="24" r="11" fill="#ffd25e"/>'
  + '<path d="M0 66 32 30l22 24 16-14 50 40v10H0z" fill="#3e8f5a"/><path d="M0 74 40 52l28 18 22-10 30 16v14H0z" fill="#2f6f46"/>'
  + '<path d="M0 82h120v8H0z" fill="#5aa0d8"/></svg>');

/** A sample poll, fictional voters included, for pollCard. */
function samplePoll() {
  const body = '📊 Poll: Next month’s book?\n1. The Night Garden\n2. Salt and Stars\n3. Paper Moons\nReply with a number to vote · poll gsample1';
  const history = [{type: 2, body}, {type: 1, author: 'Priya', body: '2'}, {type: 1, author: 'Sam', body: '1'}, {type: 1, author: 'Leo', body: '2'},
    {type: 2, body: '🗳️ Salt and Stars · voted in “Next month’s book?” (poll gsample1)'}];
  return {poll: parsePoll(body), history};
}

/** A tic-tac-toe card the way a chat shows it, from the moves so far. */
function sampleGame(moves, byMe, latest) {
  const before = gameReplay('ttt', moves.slice(0, -1));
  return gameCard({kind: 'ttt', id: 'gsample2', moves, position: gameReplay('ttt', moves), mineFirst: true, resignedByMe: null, latest,
    byMe, moveText: before ? before.describe(moves[moves.length - 1]) : null, reply: false, rejected: false});
}

/** More than text: polls, a game, effects, a spoiler, and dice, with the real cards. */
function extrasShowcase(flow) {
  const chat = () => mockWindow('Book club', 'matrix', 'green');
  return showcase(flow, [
    {caption: 'Polls: everyone votes, and the bars fill in', length: 2700, build(scene, {at}) {
      const {win, thread} = chat();
      const {poll, history} = samplePoll();
      const card = mockMessage(pollCard(poll, history), {out: true});
      const options = [...card.bubble.querySelectorAll('.poll-option')];
      const shares = options.map((option) => option.style.getPropertyValue('--share'));
      options.forEach((option) => option.style.setProperty('--share', '0%'));
      thread.append(mockMessage('Time to pick the next one!', {author: 'Priya'}).row, card.row);
      scene.append(win);
      at(350, () => options.forEach((option, position) => option.style.setProperty('--share', shares[position])));
    }},
    {caption: 'Games: chess, tic-tac-toe, and more, one move at a time', length: 2900, build(scene, {at}) {
      const {win, thread} = chat();
      const theirs = mockMessage(sampleGame(['1', '4', '5', '7'], false, true), {author: 'Sam'});
      thread.append(theirs.row);
      scene.append(win);
      at(1100, () => {
        theirs.bubble.replaceChildren(sampleGame(['1', '4', '5', '7'], false, false));
        const mine = mockMessage(sampleGame(['1', '4', '5', '7', '9'], true, true), {out: true});
        mine.bubble.querySelectorAll('.grid-cell')[8]?.classList.add('g-pop-in');
        thread.append(mine.row);
      });
    }},
    {caption: 'Effects: send with a slam, confetti, balloons, and more', length: 3000, build(scene, {at, first}) {
      const {win, thread} = chat();
      thread.append(mockMessage('How did the interview go?', {out: true}).row);
      scene.append(win);
      at(200, () => {
        const news = mockMessage('I got the job!! 🎉\n(sent with Slam 💥)', {author: 'Priya'});
        news.bubble.classList.add('fx-slam');
        thread.append(news.row);
      });
      at(1200, () => {
        thread.append(mockMessage('Congratulations! 🥳\n(sent with Confetti 🎉)', {out: true}).row);
        // Over the whole window, so only the first time this scene shows, not on every loop.
        if (first && prefs.effects !== false && typeof playScreenEffect === 'function') playScreenEffect('Confetti');
      });
    }},
    {caption: 'Spoilers: ||hidden words|| stay covered until clicked', length: 2600, build(scene, {at}) {
      const {win, thread} = chat();
      const spoiler = mockMessage('Finished it last night. The twist: ||the lighthouse keeper was her sister||', {author: 'Leo'});
      thread.append(mockMessage('No spoilers, I’m on chapter 9!', {out: true}).row, spoiler.row);
      scene.append(win);
      at(1200, () => spoiler.bubble.querySelector('.spoiler')?.classList.add('shown'));
    }},
    {caption: 'Dice, coin flips, and random picks', length: 2600, build(scene, {at}) {
      const {win, thread} = chat();
      const rolls = [4, 2];
      const roll = mockMessage(randomCard({kind: 'dice', sides: 6, rolls}, true), {out: true});
      thread.append(mockMessage('Highest roll picks the snacks 🎲', {author: 'Sam'}).row, roll.row);
      scene.append(win);
      const dice = [...roll.bubble.querySelectorAll('.die')];
      for (let tick = 0; tick <= 8; tick++) {
        at(tick * 85, () => dice.forEach((die, position) => {
          die.textContent = FACES[tick === 8 ? rolls[position] - 1 : (tick * 2 + position * 3) % 6];
        }));
      }
    }},
  ], 'More than text');
}

/** The message ⋯ menu, with the same parts as app.js messageActions. */
function mockMessageMenu() {
  const menu = element('details', '', 'message-tools');
  const summary = element('summary', '');
  summary.append(element('span', '⋯'));
  const popover = element('div', '', 'message-popover');
  const quick = element('div', '', 'quick-reactions');
  for (const [emoji] of REACTIONS) quick.append(Object.assign(element('button', emoji), {type: 'button'}));
  const more = element('div', '', 'message-menu');
  for (const label of ['Reply', 'Forward', 'Copy text']) more.append(Object.assign(element('button', label), {type: 'button'}));
  popover.append(quick, more);
  menu.append(summary, popover);
  return {menu, summary, quick};
}

/** Quick moves: the web's real shortcuts, played with a pointer. Touch screens hold instead, like the phone. */
function movesShowcase(flow) {
  const touch = matchMedia('(hover: none)').matches;
  return showcase(flow, [
    {caption: touch ? 'Touch and hold a message to react, reply, or forward' : 'Point at a message and click ⋯, or right-click it, to react, reply, or forward', length: 4300, build(scene, {at, still, first}) {
      const {win, thread} = mockWindow('Book club', 'matrix', 'green');
      const target = mockMessage('Perfect! Who’s bringing snacks?', {author: 'Priya'});
      const tools = mockMessageMenu();
      target.line.append(tools.menu);
      thread.append(mockMessage('Book club is at my place on Friday', {out: true}).row, target.row);
      scene.append(win);
      const pointer = guidePointer(scene, still);
      pointer.to(.82, .95);
      at(250, () => pointer.to(target.bubble, .6, .55));
      at(900, () => target.row.classList.add('g-hover'));
      at(1300, () => pointer.to(tools.summary));
      at(1900, () => { pointer.click(); tools.menu.open = true; });
      const heart = tools.quick.children[1];
      at(2400, () => pointer.to(heart));
      at(3000, () => {
        pointer.click();
        tools.menu.open = false;
        const chips = element('span', '❤️', 'reaction');
        target.row.append(chips);
        if (!still && first && prefs.reactionEffects !== false && typeof playReactionEffect === 'function') playReactionEffect('❤️', target.bubble, false);
        else target.bubble.classList.add('rx-hearts');
      });
    }},
    {caption: touch ? 'Touch and hold a chat to pin, archive, mute, or block it' : 'Right-click a chat to pin, archive, mute, or block it', length: 4200, build(scene, {at, still}) {
      const inbox = element('div', '', 'g-inbox');
      const head = element('div', '', 'g-inbox-head');
      head.append(element('strong', 'Messages'), element('span', 'Inbox ▾', 'g-filter'));
      const rows = [['Book club', 'Priya: Who’s bringing snacks?', '9:41'], ['Maya Chen', 'See you at ten!', '9:12'],
        ['Radio Club', 'Antenna night is Friday', 'Mon']].map(([name, line, time]) => {
        const row = element('div', '', 'thread');
        const copy = element('span', '', 'thread-copy');
        copy.append(element('strong', name), element('small', line));
        const clock = element('time', time);
        row.append(element('span', '', 'indicator'), element('span', initials(name), 'avatar'), copy, clock);
        return {row, clock};
      });
      const list = element('div', '', 'g-inbox-list');
      list.append(...rows.map(({row}) => row));
      inbox.append(head, list);
      scene.append(inbox);
      const pointer = guidePointer(scene, still);
      pointer.to(.82, .95);
      const [, maya] = rows;
      const actions = element('div', '', 'thread-actions');
      for (const label of ['Pin', 'Archive', 'Mute', 'Block', 'Details']) actions.append(Object.assign(element('button', label), {type: 'button'}));
      actions.append(Object.assign(element('button', '×', 'icon-button small'), {type: 'button'}));
      at(250, () => pointer.to(maya.row, .45, .5));
      at(1000, () => { pointer.click(true); maya.row.classList.add('g-hover'); maya.row.after(actions); });
      at(1600, () => pointer.to(actions.firstElementChild));
      at(2300, () => {
        pointer.click();
        actions.remove();
        maya.row.classList.remove('g-hover');
        maya.clock.prepend('Pinned · ');
        list.prepend(maya.row);
        maya.row.classList.add('g-pop-in');
      });
    }},
    {caption: 'Drag a photo onto the message box, or paste one', length: 4600, build(scene, {at, still}) {
      const {win, thread} = mockWindow('Maya Chen', 'matrix', 'green');
      thread.append(mockMessage('Send me the photo from the hike!', {author: 'Maya Chen'}).row);
      const composer = mockComposer();
      win.append(composer.form);
      const photo = element('span', '', 'g-photo');
      photo.append(Object.assign(element('img', ''), {src: GUIDE_PHOTO, alt: ''}));
      scene.append(win, photo);
      const pointer = guidePointer(scene, still);
      pointer.to(.62, .2);
      const staged = element('div', '', 'staged');
      const words = element('span', '', 'staged-words');
      words.append(element('strong', 'Lake trail.jpg'), element('small', 'Send adds your words as a caption'));
      staged.append(Object.assign(element('img', ''), {src: GUIDE_PHOTO, alt: ''}), words, Object.assign(element('button', 'Edit'), {type: 'button'}),
        Object.assign(element('button', '×', 'icon-button small'), {type: 'button'}));
      at(250, () => pointer.to(photo, .55, .6));
      at(850, () => photo.classList.add('lifted'));
      at(1000, () => {
        // The photo and the pointer travel together, the pointer still holding the same spot.
        const frame = scene.getBoundingClientRect(), box = composer.box.getBoundingClientRect(), held = photo.getBoundingClientRect();
        const x = box.left - frame.left + box.width * .35, y = box.top - frame.top - 18;
        photo.style.left = x + 'px';
        photo.style.top = y + 'px';
        pointer.point(x + held.width * .55, y + held.height * .6);
      });
      at(1700, () => composer.form.classList.add('g-drop-on'));
      at(2200, () => {
        photo.hidden = true;
        composer.form.classList.remove('g-drop-on');
        composer.row.before(staged);
      });
      at(2900, () => pointer.to(composer.send));
      at(3500, () => {
        pointer.click();
        staged.remove();
        const picture = Object.assign(element('img', '', 'g-sent-photo'), {src: GUIDE_PHOTO, alt: ''});
        const sent = mockMessage(picture, {out: true});
        sent.bubble.classList.add('g-photo-bubble');
        thread.append(sent.row);
      });
    }},
    {caption: 'Enter sends. Shift+Enter starts a new line.', length: 4800, build(scene, {at, still}) {
      const {win, thread} = mockWindow('Maya Chen', 'matrix', 'green');
      thread.append(mockMessage('Are you still coming at 7?', {author: 'Maya Chen'}).row);
      const composer = mockComposer();
      const keys = element('div', '', 'g-keys');
      const shift = element('kbd', 'Shift'), enter = element('kbd', 'Enter');
      const plus = element('span', '+');
      keys.append(shift, plus, enter);
      win.append(composer.form);
      scene.append(win, keys);
      const first = 'Running 5 min late', second = 'Save me a seat?';
      const type = (start, text, before) => [...text].forEach((_, count) => at(start + count * 45, () => {
        composer.words.textContent = before + text.slice(0, count + 1);
      }));
      const press = (ms, both) => {
        at(ms, () => { keys.className = 'g-keys on' + (both ? ' both' : ''); });
        at(ms + 600, () => { keys.className = 'g-keys'; });
      };
      type(200, first, '');
      press(1150, true);
      at(1350, () => { composer.words.textContent = first + '\n'; });
      type(1500, second, first + '\n');
      press(2450, false);
      at(2650, () => {
        composer.words.textContent = '';
        thread.append(mockMessage(first + '\n' + second, {out: true}).row);
      });
      if (still) keys.className = 'g-keys';
    }},
  ], 'Quick moves');
}

// ---- Services and calls ----

const SERVICE_INFO = {  // Same words and locks as the phone's Chat services page (OnboardingShowcase.kt).
  matrix: {line: 'An open chat network, like email for instant messages. Talk to anyone on any Matrix server, including people using Element.',
    tone: 'yellow', lock: 'Encrypted in transit'},
  xmpp: {line: 'A long-running open chat standard, also called Jabber. Good for friends on Conversations, Monal, or their own server.',
    tone: 'yellow', lock: 'Encrypted in transit'},
  email: {line: 'Chat with anyone who has an email address. Only the conversations you choose show up, never your whole mailbox.',
    tone: 'yellow', lock: 'Encrypted in transit'},
  irc: {line: 'Classic chat rooms, called channels, used by many open-source projects and communities. Connects over TLS.',
    tone: 'yellow', lock: 'Encrypted in transit'},
};

/** One service: a colored badge, what it is, its lock, and what you can do. */
function serviceCard({badge, badgeClass, name, line, tone, lock, status, action, note}) {
  const card = element('div', '', 'g-svc');
  const mark = element('span', badge, 'g-svc-badge ' + badgeClass);
  mark.setAttribute('aria-hidden', 'true');
  const words = element('div', '', 'g-svc-words');
  const title = element('div', '', 'g-svc-title');
  title.append(element('strong', name));
  if (tone) {
    const security = element('span', '', 'g-svc-lock');
    security.append(lockIcon(tone, 13), element('span', lock));
    title.append(security);
  }
  words.append(title, element('span', line, 'g-svc-line'));
  if (status) words.append(element('small', status, 'g-svc-status'));
  if (note) words.append(element('small', note, 'hint'));
  card.append(mark, words);
  if (action) {
    const [label, run, primary, aria] = action;
    const button = element('button', label, primary ? 'primary' : '');
    button.type = 'button';
    if (aria) button.setAttribute('aria-label', aria);
    button.addEventListener('click', run);
    card.append(button);
  }
  return card;
}

/** Waits for the phone sync guide to close, then runs [then]. */
async function syncGuideThen(then) {
  await showSyncGuide();
  document.getElementById('sync-guide')?.addEventListener('close', () => then(), {once: true});
}

/** Chat services: Matrix, XMPP, Email, IRC, and texts through the phone, each with its add-account action. */
function chatServices() {
  const list = element('div', '', 'g-svc-list');
  list.setAttribute('aria-busy', 'true');
  const draw = async () => {
    let result;
    try { result = await api('protocols/accounts'); } catch (error) { list.replaceChildren(element('p', error.message, 'error')); return; }
    list.removeAttribute('aria-busy');
    const cards = Object.keys(SERVICE_INFO).map((protocol) => {
      const info = SERVICE_INFO[protocol];
      const account = result.accounts.find((item) => item.protocol === protocol);
      const who = account ? (account.fields.user_id || account.fields.jid || account.fields.address || account.fields.nick || 'Connected') : '';
      const fixed = account?.source === 'compose';
      return serviceCard({badge: labels[protocol][0], badgeClass: 'account-badge p-' + protocol, name: labels[protocol], line: info.line,
        tone: info.tone, lock: info.lock, status: account ? '✓ ' + who + (fixed ? ' · set by the server' : '') : '',
        action: fixed ? null : [account ? 'Edit' : 'Add', () => editAccount(protocol, account, draw), !account,
          (account ? 'Edit ' : 'Add ') + labels[protocol] + ' account']});
    });
    const paired = phonePaired();
    cards.push(serviceCard({badge: 'S', badgeClass: 'b-sms', name: 'Texts from your phone (SMS)',
      line: 'Your Android phone’s texts, synced here. For anyone you text by phone number. Needs FluidMessages for Android.',
      tone: 'red', lock: 'Not encrypted, like regular texts', status: paired ? '✓ Phone connected' : '',
      action: [paired ? 'Manage' : 'Set up', () => (paired ? openSettings('settings-devices').catch(showError) : syncGuideThen(draw).catch(showError)), !paired,
        paired ? 'Manage the connected phone' : 'Set up texts from your phone']}));
    list.replaceChildren(...cards);
  };
  draw();
  return list;
}

/** A meeting-link call in a chat, drawn by companion.js meetingCard. */
function meetingPicture() {
  const {win, thread} = mockWindow('Radio Club', 'matrix', 'green');
  thread.classList.add('short');
  thread.append(mockMessage('Can we talk it through tonight?', {author: 'Kestrel'}).row);
  const call = mockMessage('📹 Join my video call: https://meet.jit.si/FluidMessages-sample', {out: true});
  if (typeof meetingCard === 'function') meetingCard({type: 2, body: '📹 Join my video call: https://meet.jit.si/FluidMessages-sample'}, call.bubble);
  call.row.querySelector('a')?.setAttribute('tabindex', '-1');
  thread.append(call.row);
  win.inert = true;
  win.setAttribute('aria-hidden', 'true');
  return win;
}

/** Calls: Matrix calls in this browser, meeting links in any chat, XMPP calls on Android, and phone calls on the phone. */
function callServices() {
  const list = element('div', '', 'g-svc-list');
  const draw = async () => {
    let accounts = [];
    try { accounts = (await api('protocols/accounts')).accounts; } catch (error) { accounts = chatAccounts || []; }
    const matrix = accounts.find((item) => item.protocol === 'matrix');
    const secure = window.isSecureContext;
    list.replaceChildren(
      serviceCard({badge: 'M', badgeClass: 'account-badge p-matrix', name: 'Matrix calls',
        line: 'Voice and video calls right in this browser with anyone on Matrix. They can answer in FluidMessages, Element, or another Matrix app.',
        tone: 'green', lock: 'End-to-end encrypted',
        status: matrix ? '✓ Ready. Use the Calls tab, or the call buttons in a Matrix chat.' : '',
        note: secure ? '' : 'Calls need FluidMessages Web opened over HTTPS, or at localhost on the computer it runs on.',
        action: matrix ? null : ['Add Matrix', () => editAccount('matrix', null, draw), true, 'Add Matrix account']}),
      serviceCard({badge: '📹', badgeClass: 'g-badge-meet', name: 'Meeting links',
        line: 'A Jitsi Meet link anyone can open to join, from any app or browser, texts included. The meeting server can access the call.',
        tone: 'yellow', lock: 'Encrypted in transit', status: '✓ Ready. Use the camera button at the top of a chat.'}),
      serviceCard({badge: 'X', badgeClass: 'account-badge p-xmpp', name: 'XMPP calls',
        line: 'Voice and video with apps like Conversations and Dino ring in FluidMessages for Android. Here, send a meeting link instead.',
        status: 'Android app only for now'}));
  };
  draw();
  return list;
}

// ---- First run ----

/**
 * First run, like the phone's setup guide, worded for a browser: no phone calls, SIMs, gestures, or haptics here.
 * [at] reopens it on a step, for coming back from the sample inbox. Settings → Help → Setup guide replays it.
 */
function showWelcome({at = 0} = {}) {
  const root = get('welcome');
  if (!root.dataset.watched) {
    root.dataset.watched = 'true';
    new MutationObserver(() => { if (root.hidden) root.guideFlow?.stop(); }).observe(root, {attributes: true, attributeFilter: ['hidden']});
  }
  get('workspace').hidden = true;
  get('pair').hidden = true;
  document.querySelector('dialog.settings-sheet')?.close();
  root.hidden = false;

  const finish = async (then) => {
    try { await api('settings', {welcomed: true}); } catch (error) { /* shown again next time, which is fine */ }
    welcomed = true;
    flow.stop();
    root.hidden = true;
    root.replaceChildren();
    get('workspace').hidden = false;
    await refresh().catch(() => {});
    if (then) then(); else get('new').focus();
  };
  const settingsAt = (target) => () => finish(() => openSettings(target).catch(showError));
  const phoneGuide = () => showSyncGuide();
  // The sample inbox, like the phone's Practice first: fictional chats, nothing sent. Exit comes back to this step.
  const trySample = () => {
    if (typeof startSample !== 'function') return;
    const step = flow.step;
    flow.stop();
    root.hidden = true;
    root.replaceChildren();
    get('workspace').hidden = false;
    startSample({onExit: () => showWelcome({at: step})});
  };
  const savePref = (key) => async (value) => {
    prefs = {...prefs, [key]: value};
    try { companionSettings = await api('settings', {prefs: {[key]: value}}); } catch (error) { showError(error); }
  };

  const steps = [
    {title: 'Welcome', subtitle: 'Your chats and calls, all in one place, right in your browser.',
      body: () => [sampleConversation(), element('p', 'New here? The sample inbox has fictional chats to click around in. Nothing in it is sent.', 'hint')],
      actions: (flow) => [['Get started', () => flow.next()], ['Try the sample inbox', trySample]]},
    {title: 'Two tabs', subtitle: 'Calls and Messages, along the side or at the bottom.',
      body: (flow) => [tabsIllustration(flow), guideTips([
        {icon: 'phone', title: 'Calls', line: 'Voice and video with Matrix contacts, and meeting links'},
        {icon: 'chat', title: 'Messages', line: 'Every chat in one place'},
        {icon: 'gear', title: 'Settings', line: 'Accounts, themes, and an optional phone, next to the tabs'}])]},
    {title: 'One inbox', subtitle: 'Chat services side by side, and texts too if you connect your phone.',
      body: () => [waysIllustration(), guideTips([
        {icon: 'chat', title: 'Chat services work right away', line: 'Matrix, XMPP, Email, and IRC, straight from this browser'},
        {icon: 'tag', title: 'Tags show how', line: 'Matrix, Email, SMS, and more'},
        {icon: 'laptop', title: 'Texts, if you like', line: 'Connect FluidMessages for Android and your texts join in'}])]},
    {title: 'Who can read it?', subtitle: 'Every chat shows a small lock.',
      body: () => [locksIllustration(), guideTips([
        {icon: 'lock', title: 'Green', line: 'Only you and them can read it'},
        {icon: 'lock', title: 'Yellow', line: 'Protected on the way, but the service can read it'},
        {icon: 'lock', title: 'Red, open', line: 'Not encrypted, like regular texts'},
        {icon: 'chat', title: 'Where to look', line: 'Next to the name at the top of a chat. Details explains it.'}])]},
    {title: 'Keep chats private', subtitle: 'Decide who can open FluidMessages, and from where.',
      body: (flow) => [privacyIllustration(flow), guideTips([
        {icon: 'key', title: 'A password', line: 'Other devices sign in with it. Settings → Phone and other devices'},
        {icon: 'laptop', title: 'The computer it runs on', line: 'Opens there without a password, or turn that off'},
        {icon: 'lock', title: 'Chat codes', line: 'Hide a chat until its code is entered. A chat’s Details'},
        {icon: 'people', title: 'Sign out other browsers', line: 'All at once, from Settings'}])]},
    {title: 'Disappearing messages', subtitle: 'Messages can remove themselves after a while.',
      body: () => [disappearingIllustration(), guideTips([
        {icon: 'timer', title: 'For one chat', line: 'A chat’s Details → Disappearing messages'},
        {icon: 'optional', title: 'For every chat', line: 'Settings → Privacy and security'},
        {icon: 'chat', title: 'Older messages stay', line: 'Only new messages disappear'}])]},
    {title: 'More than text', subtitle: 'Polls, events, games, money, and more, right in a chat.',
      body: (flow) => [extrasShowcase(flow), guideTips([
        {icon: 'chat', title: 'The + button', line: 'Photos, polls, events, money, games, dice, GIFs, and effects'},
        {icon: 'people', title: 'Works with everyone', line: 'People on other apps get readable text and can reply to vote, RSVP, or play'},
        {icon: 'gear', title: 'Rather not?', line: 'Turn any of it off in Settings → Media, GIFs, and fun'}])]},
    {title: 'Quick moves', subtitle: 'A few shortcuts that make chatting faster.',
      body: (flow) => [movesShowcase(flow), guideTips([
        {icon: 'tag', title: 'Formatting', line: '*bold*, _italic_, ~strike~, `code`, and ||spoilers||'},
        {icon: 'optional', title: 'Esc closes', line: 'Message menus, the + menu, and photos you opened'},
        {icon: 'gear', title: 'Rather have Enter add a line?', line: 'Turn off Enter sends in Settings → Messaging'}])]},
    {title: 'Pick your colors', subtitle: 'Choose a look and preview your bubbles. You can change it any time.',
      body: (flow) => {
        const theme = element('select', '');
        for (const key of THEME_ORDER) theme.append(Object.assign(element('option', THEMES[key].label), {value: key}));
        theme.value = webLook.theme || 'fluid';
        // A theme brings back its own colors and layout, the same as picking it in Settings.
        theme.addEventListener('change', () => { saveWebLook({theme: theme.value, mode: webLook.mode || 'system'}, true); flow.redraw(); });
        const mode = element('select', '');
        for (const [value, label] of [['system', 'Automatic'], ['light', 'Light'], ['dark', 'Dark']]) mode.append(Object.assign(element('option', label), {value}));
        mode.value = webLook.mode || 'system';
        mode.addEventListener('change', () => { saveWebLook({...webLook, mode: mode.value}, true); flow.redraw(); });
        const fields = element('div', '', 'guide-fields');
        fields.append(field('Color theme', theme), field('Appearance mode', mode));
        if (usingPhoneLook()) fields.append(element('p', 'Your phone’s look is in use. Pick “This web app’s own” in Settings → Phone and other devices to use these here.', 'hint'));
        return [colorsIllustration(flow), sampleConversation(), fields];
      }},
    {title: 'Motion and effects', subtitle: 'Decide how messages move when they arrive or send.',
      body: (flow) => {
        const options = element('div', '', 'guide-card-list');
        options.append(checkbox('Message effects', prefs.effects !== false, savePref('effects'), 'Confetti, balloons, slams, and invisible ink. Off shows the words only.'),
          checkbox('Reaction animations', prefs.reactionEffects !== false, savePref('reactionEffects'), 'A quick animation when someone reacts to a message.'));
        const out = [motionIllustration(flow), options];
        if (guideStill()) out.push(element('p', 'Your device asks for reduced motion, so animations stay off.', 'hint'));
        return out;
      }},
    {title: 'Chat services', subtitle: 'Sign in to the services you chat on. Passwords stay on your FluidMessages server. You can add more later in Settings.',
      body: () => [chatServices()]},
    {title: 'Calls', subtitle: 'Voice and video from this browser. Phone calls stay on your phone.',
      body: () => [meetingPicture(), callServices(), guideTips([
        {icon: 'phone', title: 'Phone calls stay on your phone', line: 'This browser can’t place phone calls. With your phone connected, Call in a text chat rings it so you can talk there.'}])]},
    {title: 'You’re ready', subtitle: 'Start chatting, or look around the sample inbox first.',
      body: () => [readyIllustration(), guideTips([
        {icon: 'practice', title: 'Add a chat account', line: 'Matrix, XMPP, Email, or IRC', run: settingsAt('settings-accounts')},
        {icon: 'laptop', title: 'Connect your phone', line: 'For texts, step by step', run: phoneGuide},
        {icon: 'optional', title: 'Come back anytime', line: 'Settings → Help → Setup guide'}])],
      actions: () => [['Start chatting', () => finish()], ['Try the sample inbox', trySample]]},
  ];
  const flow = guideFlow(root, {steps, onSkip: () => finish(), start: at});
}

// ---- Connecting the phone ----

/**
 * Connecting FluidMessages for Android, step by step and full page: why, a password if there isn't one, the app,
 * where to go on the phone, the address and pairing token, then a live wait for its first sync. It uses the same
 * endpoints as Settings (auth/password, auth/pairing, settings), so CSRF, rate limits, and token hashing are
 * unchanged. The token lives only in this page while it's open.
 */
async function showSyncGuide({fromSettings = false, connectedStep = false} = {}) {
  let security;
  try { security = await api('auth/security'); } catch (error) { showError(error); return; }
  document.querySelector('dialog.settings-sheet')?.close();
  const sheet = element('dialog', '', 'guide-dialog');
  sheet.id = 'sync-guide';
  document.body.append(sheet);
  let token = '';
  let since = 0;
  let connected = connectedStep;

  const close = () => {
    token = '';
    flow.stop();
    if (sheet.open) sheet.close();
    sheet.remove();
    if (!get('workspace').hidden) refresh().catch(() => {});
    if (fromSettings) openSettings('settings-devices').catch(showError);
  };
  sheet.addEventListener('cancel', (event) => { event.preventDefault(); close(); });

  const origin = location.origin;
  const loopback = /^(localhost|127\.0\.0\.1|\[::1\])$/.test(location.hostname);
  const copyRow = (label, value, secret) => {
    const wrap = element('div', '', 'guide-copy');
    const name = element('span', label, 'guide-copy-label');
    const box = element('div', '', 'token-box');
    const input = Object.assign(element('input', ''), {value, readOnly: true, spellcheck: false, autocomplete: 'off'});
    input.setAttribute('aria-label', label);
    if (secret) input.classList.add('mono');
    input.addEventListener('focus', () => input.select());
    const copy = element('button', 'Copy', 'primary');
    copy.type = 'button';
    copy.setAttribute('aria-label', 'Copy ' + label.toLowerCase());
    copy.addEventListener('click', () => {
      const done = () => { copy.textContent = 'Copied'; setTimeout(() => { copy.textContent = 'Copy'; }, 2500); };
      if (navigator.clipboard?.writeText) navigator.clipboard.writeText(value).then(done).catch(() => input.select());
      else { input.focus(); input.select(); }
    });
    box.append(input, copy);
    wrap.append(name, box);
    return wrap;
  };

  const make = async (flow, status) => {
    try {
      const result = await api('auth/pairing', {});
      security = result;
      token = result.token;
      since = result.pairing.created || Date.now();
      flow.redraw();
    } catch (error) {
      if (status) { status.textContent = error.message; status.className = 'error'; } else showError(error);
    }
  };

  const steps = [
    {title: 'Texts on your computer', subtitle: 'Connect FluidMessages for Android, and your texts show up here too.',
      body: () => [linkIllustration(), guideTips([
        {icon: 'chat', title: 'Read and reply here', line: 'Texts sync from your phone, and texts you write here go out through it'},
        {icon: 'phone', title: 'Your phone does the sending', line: 'It uses your phone plan, so nothing changes for the people you text'},
        {icon: 'optional', title: 'Optional', line: 'Chat accounts work here without a phone'}])]},
    ...(security.owner ? [] : [{title: 'Set a password', subtitle: 'Your phone connects over your network, so FluidMessages needs a password before other devices can reach it.',
      body: (flow) => {
        if (security.owner) return [guideTips([{icon: 'practice', title: 'Password set', line: 'Other devices sign in with it'}])];
        return [passwordForm(false, (result) => { security = result; flow.redraw(); })];
      },
      actions: (flow) => [['Continue', () => flow.next(), {disabled: !security.owner}]]}]),
    {title: 'Get the Android app', subtitle: 'Install FluidMessages on your phone, then open it once.',
      body: (flow) => [installIllustration(), guideTips([
        {icon: 'download', title: 'Download it', line: 'The latest release, on GitHub', href: GUIDE_RELEASES},
        {icon: 'optional', title: 'Allow the install', line: 'Your phone asks once, since it’s not from an app store'},
        {icon: 'practice', title: 'Already have it?', line: 'Skip ahead', run: () => flow.next()}])]},
    {title: 'On your phone', subtitle: 'Open FluidMessages and find the FluidMessages Web settings.',
      body: () => [phoneSettingsIllustration(), guideTips([
        {icon: 'gear', title: 'Open Settings', line: 'The gear at the top of your inbox'},
        {icon: 'laptop', title: 'Storage and devices', line: 'Tap FluidMessages Web'},
        {icon: 'practice', title: 'Set up step by step', line: 'It asks for the address and token on the next step here'}])]},
    {title: 'Enter these on your phone', subtitle: 'Copy the address and the pairing token into FluidMessages on your phone.',
      body: (flow) => {
        const out = [tokenIllustration()];
        const https = location.protocol === 'https:';
        out.push(copyRow('Address', origin, false));
        if (!https || loopback) out.push(element('p', loopback
          ? 'This address only works on this computer. Your phone needs the https:// address it can reach, for example one from a reverse proxy such as Caddy.'
          : 'Your phone needs an https:// address. Put FluidMessages behind an HTTPS reverse proxy such as Caddy, then use that address.', 'notice'));
        if (token) {
          out.push(copyRow('Pairing token', token, true),
            element('p', 'Shown only now. Keep it private: it lets the phone read and send your messages.', 'hint'));
        } else if (security.pairing.web) {
          const row = element('div', '', 'button-row');
          const problem = element('p', '', 'error');
          problem.setAttribute('role', 'alert');
          row.append(confirmButton('Make a new pairing token', 'Click again: the old one stops working', () => make(flow, problem), 'primary'));
          out.push(problem, element('p', 'A pairing token is already active (made ' + new Date(security.pairing.created).toLocaleDateString()
            + '). A new one disconnects the phone that uses it.', 'hint'), row);
        } else {
          const status = element('p', 'Making a pairing token…', 'hint');
          out.push(status);
          flow.after(0, () => make(flow, status));
        }
        return out;
      },
      actions: (flow) => [['I’ve entered them', () => { since = since || Date.now(); flow.next(); }, {disabled: !token && !security.pairing.web}]]},
    {title: 'Waiting for your phone', subtitle: 'Save the connection on your phone. This page updates by itself once the phone syncs.',
      body: (flow) => {
        const status = element('p', 'Waiting for the first sync…', 'guide-status');
        status.setAttribute('role', 'status');
        const check = async () => {
          if (!sheet.open) { clearInterval(id); return; }
          try {
            const settings = await api('settings');
            const last = settings.phone?.last_sync || 0;
            if (last && last >= since - 1000) {
              connected = true;
              // A first pairing with differences goes straight to choosing what to keep (sync-merge.js).
              if (settings.merge?.pending?.conflicts?.length && typeof showSyncMerge === 'function') {
                clearInterval(id);
                sheet.close();
                sheet.remove();
                showSyncMerge({then: () => showSyncGuide({fromSettings, connectedStep: true}).catch(showError)}).catch(showError);
                return;
              }
              flow.next();
              return;
            }
            status.textContent = 'Waiting for the first sync…';
          } catch (error) { status.textContent = 'Can’t check right now. Still trying.'; }
        };
        // Polls even with reduced motion: this is the status, not an animation.
        const id = setInterval(() => check(), 3000);
        flow.onLeave(() => clearInterval(id));
        check();
        return [linkIllustration('moving'), status, guideTips([
          {icon: 'chat', title: 'Turn on browser messaging', line: 'On the phone. It needs FluidMessages as the default SMS app.'},
          {icon: 'wifi', title: 'Not connecting?', line: 'Check the https:// address and that the phone can reach it'},
          {icon: 'key', title: 'Token not accepted?', line: 'Go back and make a new one'}])];
      },
      actions: () => [['Finish later', close]]},
    {title: 'Your phone is connected', subtitle: 'Texts sync here every few minutes, and texts you send here go out through your phone.',
      body: () => [connected ? readyIllustration() : linkIllustration('done'), guideTips([
        {icon: 'gear', title: 'Whose settings?', line: 'Your phone’s look and timers, or this app’s own. Settings → Phone and other devices'},
        {icon: 'phone', title: 'Calls', line: 'Call in a text chat rings your phone'},
        {icon: 'optional', title: 'Disconnect anytime', line: 'From the same Settings section'}])],
      actions: () => [['Done', close]]},
  ];
  sheet.showModal();
  const flow = guideFlow(sheet, {steps, brand: 'Connect your phone', onSkip: close, skipLabel: 'Close', onBackFromFirst: close,
    start: connectedStep ? steps.length - 1 : 0});
}
