'use strict';
const get = (id) => document.getElementById(id);
let token = '';
let active = '';
let inbox = [];
let updated = null;

async function api(path, body) {
  const response = await fetch('/api/' + path, {
    method: body === undefined ? 'GET' : 'POST',
    headers: {Authorization: 'Bearer ' + token, 'Content-Type': 'application/json'},
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const result = await response.json();
  if (!response.ok) throw new Error(result.error || 'Request failed');
  return result;
}

function element(tag, value, className) {
  const node = document.createElement(tag);
  node.textContent = value;
  if (className) node.className = className;
  return node;
}

function showMessages() {
  const conversation = get('conversation');
  conversation.replaceChildren();
  get('recipient').textContent = active || 'New SMS';
  const history = inbox.filter((message) => message.address === active).reverse();
  for (const message of history) {
    const bubble = element('div', '', message.type === 1 ? 'message' : 'message out');
    const status = message.type === 5 ? ' · Failed' : message.type === 4 ? ' · Sending' : '';
    bubble.append(element('p', message.body), element('small', new Date(message.date).toLocaleString() + status));
    conversation.append(bubble);
  }
  conversation.scrollTop = conversation.scrollHeight;
}

function select(address) {
  active = address;
  get('address').value = address;
  showMessages();
  showThreads();
}

function showThreads() {
  const threads = get('threads');
  threads.replaceChildren();
  const filter = get('search').value.toLocaleLowerCase().trim();
  const matches = new Map();
  for (const message of inbox) {
    if ((message.address + ' ' + message.body).toLocaleLowerCase().includes(filter) && !matches.has(message.address)) {
      matches.set(message.address, message);
    }
  }
  for (const [address, message] of matches) {
    const button = element('button', '', 'thread');
    button.type = 'button';
    button.setAttribute('aria-current', String(address === active));
    button.append(element('strong', address), element('small', message.body));
    button.addEventListener('click', () => select(address));
    threads.append(button);
  }
  if (!matches.size) threads.append(element('p', filter ? 'No matching conversations' : 'No synced conversations'));
}

async function refresh() {
  const session = token;
  const [snapshot, jobs] = await Promise.all([api('inbox'), api('jobs')]);
  if (session !== token) return;
  if (updated !== snapshot.updated || !get('threads').childElementCount) {
    inbox = snapshot.messages;
    updated = snapshot.updated;
    showThreads();
    if (JSON.stringify(inbox.filter((message) => message.address === active)) !== get('conversation').dataset.snapshot) {
      get('conversation').dataset.snapshot = JSON.stringify(inbox.filter((message) => message.address === active));
      showMessages();
    }
  }
  get('status').textContent = updated ? 'Synced ' + new Date(updated).toLocaleTimeString() : 'Waiting for phone sync';
  const queue = get('jobs');
  const signature = JSON.stringify(jobs);
  if (signature === queue.dataset.snapshot) return;
  queue.dataset.snapshot = signature;
  queue.replaceChildren();
  if (!jobs.length) queue.append(element('p', 'No scheduled messages'));
  for (const job of jobs) {
    const row = element('div', '', 'job');
    row.append(element('strong', job.address), element('p', job.body), element('small',
      new Date(job.due).toLocaleString() + ' · ' + job.state));
    if (job.state === 'scheduled') {
      const cancel = element('button', 'Cancel');
      cancel.addEventListener('click', async () => {
        try { await api('cancel', {id: job.id}); await refresh(); }
        catch (error) { get('schedule-status').textContent = error.message; }
      });
      row.append(document.createElement('br'), cancel);
    }
    queue.append(row);
  }
}

get('pair').addEventListener('submit', async (event) => {
  event.preventDefault();
  token = get('token').value.trim();
  try {
    await refresh();
    get('pair').reset();
    get('pair-error').textContent = '';
    get('pair').hidden = true;
    get('workspace').hidden = false;
    get('disconnect').hidden = false;
  } catch (error) {
    token = '';
    get('pair-error').textContent = error.message;
  }
});
get('disconnect').addEventListener('click', () => {
  token = '';
  inbox = [];
  updated = null;
  get('jobs').replaceChildren();
  delete get('jobs').dataset.snapshot;
  delete get('conversation').dataset.snapshot;
  select('');
  get('schedule').reset();
  get('search').value = '';
  get('schedule-status').textContent = '';
  get('status').textContent = 'Phone disconnected';
  get('workspace').hidden = true;
  get('disconnect').hidden = true;
  get('pair').hidden = false;
});
get('search').addEventListener('input', showThreads);
get('new').addEventListener('click', () => { select(''); get('address').focus(); });
get('schedule').addEventListener('submit', async (event) => {
  event.preventDefault();
  try {
    await api('schedule', {address: get('address').value.trim(), body: get('body').value,
      due: new Date(get('due').value).getTime()});
    get('body').value = '';
    get('schedule-status').textContent = 'Scheduled. Your phone sends when it next syncs after this time.';
    await refresh();
  } catch (error) {
    get('schedule-status').textContent = error.message;
  }
});
setInterval(() => {
  if (token && !get('workspace').hidden) refresh().catch(() => { get('status').textContent = 'Sync unavailable'; });
}, 5000);
