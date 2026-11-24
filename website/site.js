// Small touches for the public site: fade things in as they scroll into view, and point the main button at the
// download for the visitor's own system. Everything still works without this file.
(() => {
  // Content stays visible without JavaScript; only hide it for the fade-in when this runs.
  document.documentElement.classList.add('js');
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

  const agent = navigator.userAgent;
  const os = /Android/i.test(agent) ? 'android' : /iPhone|iPad|iPod/i.test(agent) ? '' : /Windows/i.test(agent) ? 'windows'
    : /Mac OS X|Macintosh/i.test(agent) ? 'mac' : /Linux|X11/i.test(agent) ? 'linux' : '';
  const names = {android: 'Download for Android', windows: 'Download for Windows', mac: 'Download for macOS', linux: 'Download for Linux'};
  const card = os && document.querySelector(`.platforms [data-os="${os}"]`);
  const main = document.querySelector('[data-download]');
  if (card && main) {
    card.classList.add('yours');
    main.href = card.href;
    main.textContent = names[os];
  }
})();
