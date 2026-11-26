'use strict';
// Settings → Status → Running on this server: what FluidMessages Web keeps going right now (running_status.py),
// like the phone's Running now page. Read-only and refreshed every few seconds while it's open. Each row opens the
// setting that controls it. Loaded after settings.js.

let runningSummary = '';
let runningAsked = false;

/** The Settings row's line: the last count, fetched once in the background the first time. */
function runningSummaryLine() {
  if (!runningAsked) {
    runningAsked = true;
    api('running').then((status) => {
      runningSummary = status.summary;
      const small = document.querySelector('a.settings-row[href="#settings/running"] small');
      if (small) small.textContent = runningSummary;
    }).catch(() => { runningAsked = false; });
  }
  return runningSummary || 'What this server keeps going right now';
}

/** A row's state with its time in this browser's clock: "Reconnecting · next try at 9:41". */
function runningState(row) {
  if (!row.at) return row.state;
  // "Last synced at 9:41", "3 scheduled · next at 9:41".
  return row.state + (row.at_label === 'at' ? ' at ' : ' · ' + (row.at_label ? row.at_label + ' ' : '')) + clock(row.at);
}

function runningRow(row) {
  const button = element('button', '', 'settings-row');
  button.type = 'button';
  button.dataset.running = row.id;
  button.addEventListener('click', () => openSettings(row.opens).catch(showError));
  const icon = element('span', '', 'settings-icon');
  icon.append(settingsIcon(SETTINGS_ICONS[row.icon] ? row.icon : 'running'));
  const words = element('span', '', 'settings-words');
  words.append(element('strong', row.name), element('small', runningState(row)), element('small', row.why));
  const chevron = element('span', '', 'settings-chevron');
  chevron.append(settingsIcon('chevron'));
  button.append(icon, words, chevron);
  return button;
}

async function runningPage() {
  const intro = settingsCard('', 'What this server keeps going right now. Your phone has its own page, in Settings → Running now.');
  const headline = element('p', 'Checking…');
  headline.setAttribute('role', 'status');
  headline.id = 'running-summary';
  intro.append(headline);
  const holder = element('div', '');
  holder.id = 'running-groups';
  const problem = element('p', '', 'notice');
  problem.hidden = true;
  const draw = (status) => {
    runningSummary = status.summary;
    headline.textContent = status.count ? status.summary + ' in the background' : status.summary;
    const cards = status.groups.map((group) => {
      const card = settingsCard(group.title);
      card.dataset.group = group.id;
      const rows = element('div', '', 'settings-rows');
      rows.append(...group.rows.map(runningRow));
      card.append(rows);
      return card;
    });
    holder.replaceChildren(...cards);
  };
  const load = async () => {
    try {
      draw(await api('running'));
      problem.hidden = true;
    } catch (error) {
      problem.textContent = error.message;
      problem.hidden = false;
    }
  };
  await load();
  // Live while the page is shown; stops by itself once you leave it.
  const timer = setInterval(() => {
    if (!holder.isConnected) { clearInterval(timer); return; }
    if (!document.hidden) load();
  }, 5000);
  return [intro, problem, holder];
}

