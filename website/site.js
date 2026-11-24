// Small touches for the public site. Everything still reads and works without this file.
(() => {
  // Content stays visible without JavaScript; only hide it for the fade-in when this runs.
  document.documentElement.classList.add('js');

  // Fade things in as they scroll into view.
  const items = document.querySelectorAll('.reveal');
  if ('IntersectionObserver' in window) {
    const seen = new IntersectionObserver((entries) => {
      for (const entry of entries) {
        if (!entry.isIntersecting) continue;
        entry.target.classList.add('in');
        seen.unobserve(entry.target);
      }
    }, {rootMargin: '0px 0px -8% 0px'});
    items.forEach((item, index) => {
      item.style.setProperty('--delay', (index % 4) * 70 + 'ms');
      seen.observe(item);
    });
  } else {
    items.forEach((item) => item.classList.add('in'));
  }

  // "Which one do you need?": the phone, computer, and server choices.
  const tabs = [...document.querySelectorAll('.chooser [role="tab"]')];
  function choose(tab, focus) {
    for (const other of tabs) {
      const on = other === tab;
      other.setAttribute('aria-selected', String(on));
      other.tabIndex = on ? 0 : -1;
      document.getElementById(other.getAttribute('aria-controls')).hidden = !on;
    }
    if (focus) tab.focus();
  }
  tabs.forEach((tab, index) => {
    tab.addEventListener('click', () => choose(tab, false));
    tab.addEventListener('keydown', (event) => {
      const step = event.key === 'ArrowRight' ? 1 : event.key === 'ArrowLeft' ? -1 : 0;
      if (step) { event.preventDefault(); choose(tabs[(index + step + tabs.length) % tabs.length], true); }
    });
  });

  // Point the main button, and the chooser, at the visitor's own device.
  const agent = navigator.userAgent;
  const os = /Android/i.test(agent) ? 'android' : /iPhone|iPad|iPod/i.test(agent) ? 'ios' : /Windows/i.test(agent) ? 'windows'
    : /Mac OS X|Macintosh/i.test(agent) ? 'mac' : /Linux|X11/i.test(agent) ? 'linux' : '';
  const main = document.querySelector('[data-download]');
  const desktop = os && document.querySelector(`.platforms [data-os="${os}"]`);
  if (desktop) {
    desktop.classList.add('yours');
    choose(tabs.find((tab) => tab.dataset.choice === 'computer'), false);
    if (main) { main.href = desktop.href; main.textContent = 'Download for ' + desktop.querySelector('strong').textContent; }
  } else if (os === 'ios' && main) {
    // No texting app on iPhone: send them to the explanation instead of a file they can't open.
    main.href = '#who';
    main.textContent = 'Using an iPhone? Read this';
  }

  // Tap-to-explain words.
  const words = {
    sms: 'SMS and MMS are regular text and picture messages, the kind every phone can send.',
    community: 'A community is a big group with many chat rooms inside, usually about a shared interest.',
    encrypted: 'Encrypted means scrambled so only the right people can read it, like a sealed letter instead of a postcard.',
    apk: 'An APK is the install file for an Android app, like the box an app comes in.',
    linux: 'Linux is a free alternative to Windows and macOS.',
    selfhost: 'Self-hosting means running the app on your own computer at home instead of a company’s computers.',
    opensource: 'Open source means anyone can see, check, and improve the code that makes the app work.',
    networks: 'Matrix, XMPP, and IRC are free chat networks no single company owns, a bit like email but for chatting.',
  };
  const tip = document.querySelector('.term-tip');
  let open = null;
  function close() {
    if (!open) return;
    open.setAttribute('aria-expanded', 'false');
    open = null;
    tip.hidden = true;
  }
  document.querySelectorAll('.term').forEach((term) => {
    term.setAttribute('aria-expanded', 'false');
    term.addEventListener('click', (event) => {
      event.stopPropagation();
      if (open === term) { close(); return; }
      close();
      open = term;
      term.setAttribute('aria-expanded', 'true');
      tip.textContent = words[term.dataset.term] || '';
      tip.hidden = false;
      const box = term.getBoundingClientRect();
      const width = Math.min(320, window.innerWidth - 32);
      tip.style.width = width + 'px';
      tip.style.left = Math.max(16, Math.min(window.innerWidth - width - 16, box.left + box.width / 2 - width / 2)) + window.scrollX + 'px';
      tip.style.top = box.bottom + window.scrollY + 10 + 'px';
    });
  });
  document.addEventListener('click', close);
  document.addEventListener('keydown', (event) => { if (event.key === 'Escape') close(); });
  window.addEventListener('resize', close);
})();
