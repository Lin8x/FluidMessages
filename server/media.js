'use strict';
// Media like the phone: voice messages (up to 3 minutes), a player for audio files, photos that wait above the
// message box with Edit before you send them, photo quality, your own stickers, and the GIF sites to browse.
// Loaded after cards.js and uses its helpers (uploadFile, sendFile, internetChat, authorizedBlob, blobUrl).

// Voice messages

const VOICE_LIMIT = 180000;
let voice = null;
// The photo waiting above the message box, if any.
let staged = null;

function voiceSupported() { return !!(window.MediaRecorder && navigator.mediaDevices?.getUserMedia); }

function voiceType() {
  // AAC in MP4 like the phone where the browser can, otherwise Opus.
  for (const type of ['audio/mp4;codecs=mp4a.40.2', 'audio/mp4', 'audio/webm;codecs=opus', 'audio/ogg;codecs=opus', 'audio/webm']) {
    if (MediaRecorder.isTypeSupported?.(type)) return type;
  }
  return '';
}

const minutes = (millis) => Math.floor(millis / 60000) + ':' + String(Math.floor(millis / 1000) % 60).padStart(2, '0');

/** Records from the microphone, with a bar in the message box: the time, Cancel, and Send. */
async function startVoiceNote() {
  if (voice || !internetChat()) return;
  if (!voiceSupported()) { showError(new Error('This browser can’t record. Voice messages need HTTPS (or localhost).')); return; }
  let stream;
  try { stream = await navigator.mediaDevices.getUserMedia({audio: true}); } catch (error) {
    showError(new Error(error.name === 'NotAllowedError' ? 'The microphone is blocked for this site. Allow it in the browser’s site settings.' : 'No microphone is available.'));
    return;
  }
  const type = voiceType();
  const recorder = new MediaRecorder(stream, type ? {mimeType: type} : undefined);
  const parts = [];
  const began = Date.now();
  const chat = active;
  const bar = element('div', '', 'voice-bar');
  bar.setAttribute('role', 'group');
  bar.setAttribute('aria-label', 'Recording a voice message');
  const time = element('span', '0:00', 'voice-time');
  time.setAttribute('aria-live', 'off');
  const cancel = element('button', 'Cancel', 'quiet');
  const done = element('button', 'Send', 'primary');
  cancel.type = done.type = 'button';
  bar.append(element('span', '', 'voice-dot'), element('span', 'Recording', 'voice-label'), time, cancel, done);
  get('schedule').querySelector('.compose-row').before(bar);
  const ticker = setInterval(() => {
    const spent = Date.now() - began;
    time.textContent = minutes(spent) + ' / 3:00';
    if (spent >= VOICE_LIMIT) finish(true);
  }, 250);
  recorder.addEventListener('dataavailable', (event) => { if (event.data.size) parts.push(event.data); });
  const finish = (keep) => {
    if (!voice) return;
    voice = null;
    clearInterval(ticker);
    bar.remove();
    recorder.addEventListener('stop', () => {
      for (const track of stream.getTracks()) track.stop();
      if (!keep || active !== chat) return;
      const mime = (recorder.mimeType || type || 'audio/webm').split(';')[0];
      const blob = new Blob(parts, {type: mime});
      const duration = Math.min(VOICE_LIMIT, Date.now() - began);
      sendVoice(blob, duration).catch(showError);
    }, {once: true});
    recorder.stop();
  };
  voice = {finish};
  cancel.addEventListener('click', () => { finish(false); get('body').focus(); });
  done.addEventListener('click', () => finish(true));
  bar.addEventListener('keydown', (event) => { if (event.key === 'Escape') finish(false); });
  recorder.start(250);
  done.focus();
}

async function sendVoice(blob, duration) {
  if (blob.size < 200) { showError(new Error('That recording was empty. Try again.')); return; }
  const name = 'Voice message.' + ({'audio/mp4': 'm4a', 'audio/ogg': 'ogg'}[blob.type] || 'webm');
  say('Sending voice message…', true);
  const upload = await uploadFile(blob, name);
  await send(name, name, null, Date.now(), {upload, kind: 'voice', duration});
  if (typeof sendFeedback === 'function') sendFeedback();
}

/** Voice messages and audio files play in the chat, with Download beside them. */
function attachmentAudio(message, bubble) {
  const file = message.attachment;
  if (!file || !/^audio\//i.test(file.mime || '')) return false;
  const holder = element('span', '', 'media audio-note');
  const voiceNote = file.kind === 'voice' || /^Voice message/.test(file.name || '');
  holder.append(element('small', (voiceNote ? '🎙️ Voice message' : '🎵 ' + (file.name || 'Audio')) + (file.duration ? ' · ' + minutes(file.duration) : ''), 'audio-label'));
  const player = Object.assign(document.createElement('audio'), {controls: true, preload: 'metadata'});
  player.setAttribute('aria-label', voiceNote ? 'Voice message' : file.name || 'Audio');
  holder.append(player);
  bubble.prepend(holder);
  authorizedBlob('/api/protocols/file?id=' + encodeURIComponent(message.id)).then((src) => {
    const blob = src && blobFiles.get(src);
    if (!blob) { holder.replaceWith(element('span', 'Audio unavailable', 'muted')); return; }
    // The server sends every file as a download; the real type lets the browser play it.
    const playable = blobUrl(new Blob([blob], {type: file.mime}));
    player.src = playable;
    holder.append(downloadButton(playable, file.name, 'Download ' + (voiceNote ? 'voice message' : 'audio')));
  });
  return true;
}

(() => {
  const row = document.querySelector('.compose-row');
  if (!row) return;
  const mic = element('button', '🎙️', 'icon-button voice-button');
  mic.type = 'button';
  mic.title = 'Record a voice message';
  mic.setAttribute('aria-label', 'Record a voice message');
  mic.addEventListener('click', () => startVoiceNote());
  get('send').before(mic);
  // With nothing typed, the microphone sits beside Send, as on the phone.
  const update = () => { mic.hidden = !!get('body').value.trim() || !!staged || !active || !internet(threadProtocol(active)) || !voiceSupported(); };
  get('body').addEventListener('input', update);
  new MutationObserver(update).observe(get('recipient'), {childList: true, characterData: true, subtree: true});
  window.updateVoiceButton = update;
  update();
})();

// Photos waiting to send: Edit, or × to drop it. Send takes your words as its caption.

const QUALITY = {saver: [1024, .7], standard: [1600, .85], high: [4096, .92]};

function photoQuality() {
  const choice = localStorage.getItem('photoQuality') || 'auto';
  if (choice !== 'auto') return choice;
  const connection = navigator.connection;
  return connection && (connection.saveData || /(^|-)2g|3g/.test(connection.effectiveType || '')) ? 'saver' : 'standard';
}

/** Resized for the photo quality setting and saved again, which also drops location and camera details. */
async function preparePhoto(blob, name) {
  if (!/^image\/(jpeg|png|webp)$/.test(blob.type)) return [blob, name];
  const [longest, quality] = QUALITY[photoQuality()] || QUALITY.standard;
  let bitmap;
  try { bitmap = await createImageBitmap(blob); } catch (error) { return [blob, name]; }
  const scale = Math.min(1, longest / Math.max(bitmap.width, bitmap.height));
  const canvas = Object.assign(document.createElement('canvas'), {width: Math.max(1, Math.round(bitmap.width * scale)), height: Math.max(1, Math.round(bitmap.height * scale))});
  canvas.getContext('2d').drawImage(bitmap, 0, 0, canvas.width, canvas.height);
  bitmap.close?.();
  // A see-through picture stays a PNG.
  const keepAlpha = blob.type === 'image/png' && hasTransparency(canvas);
  const type = keepAlpha ? 'image/png' : 'image/jpeg';
  const out = await new Promise((resolve) => canvas.toBlob(resolve, type, quality));
  if (!out) return [blob, name];
  return [out, name.replace(/\.\w{2,5}$/, '') + (keepAlpha ? '.png' : '.jpg')];
}

function hasTransparency(canvas) {
  const data = canvas.getContext('2d').getImageData(0, 0, canvas.width, canvas.height).data;
  for (let index = 3; index < data.length; index += 4) if (data[index] < 255) return true;
  return false;
}

/** Puts a photo above the message box. Returns false for other files, which send right away. */
function stageAttachment(file, name = file.name || 'Photo.jpg') {
  if (!/^image\/(jpeg|png|webp|gif|avif|heic)$/i.test(file.type || '')) return false;
  clearStaged();
  const chip = element('div', '', 'staged');
  chip.setAttribute('role', 'group');
  chip.setAttribute('aria-label', 'Photo waiting to send');
  const url = URL.createObjectURL(file);
  const thumb = Object.assign(element('img', ''), {src: url, alt: ''});
  const words = element('span', '', 'staged-words');
  words.append(element('strong', name), element('small', /gif/i.test(file.type) ? 'GIF · sends as it is' : 'Send adds your words as a caption'));
  const edit = element('button', 'Edit');
  edit.type = 'button';
  edit.hidden = typeof openPhotoEditor !== 'function';
  edit.addEventListener('click', () => openPhotoEditor(file, {done: (blob, edited, now) => {
    if (!now) { stageAttachment(blob, edited); return; }
    clearStaged();
    sendEdited(blob, edited).catch(showError);
  }}));
  const drop = element('button', '×', 'icon-button small');
  drop.type = 'button';
  drop.setAttribute('aria-label', 'Remove the photo');
  drop.addEventListener('click', () => { clearStaged(); get('body').focus(); });
  chip.append(thumb, words, edit, drop);
  get('schedule').querySelector('.compose-row').before(chip);
  staged = {file, name, chip, url, chat: active};
  window.updateVoiceButton?.();
  get('body').focus();
  return true;
}

function clearStaged() {
  if (!staged) return;
  staged.chip.remove();
  URL.revokeObjectURL(staged.url);
  staged = null;
  window.updateVoiceButton?.();
}

/** Sends the waiting photo with the typed words (app.js, when you press Send). */
async function sendStaged() {
  if (!staged) return false;
  if (staged.chat !== active) { clearStaged(); return false; }
  const {file, name} = staged;
  get('send').disabled = true;
  try {
    const [blob, finalName] = /gif/i.test(file.type) ? [file, name] : await preparePhoto(file, name);
    await sendFile(blob, finalName);
    clearStaged();
    if (typeof sendFeedback === 'function') sendFeedback();
  } catch (error) { showError(error); }
  finally { get('send').disabled = false; }
  return true;
}

async function sendEdited(blob, name) {
  if (!internetChat()) return;
  const [ready, finalName] = await preparePhoto(blob, name);
  await sendFile(ready, finalName);
  if (typeof sendFeedback === 'function') sendFeedback();
}

/** + → Edit a photo: pick one, edit it, then send it or put it in the message box. */
function pickPhotoToEdit() {
  if (!internetChat() || typeof openPhotoEditor !== 'function') return;
  const input = Object.assign(element('input', ''), {type: 'file', accept: 'image/*'});
  input.addEventListener('change', () => {
    const file = input.files[0];
    if (file) openPhotoEditor(file, {done: (blob, name, now) => (now ? sendEdited(blob, name).catch(showError) : stageAttachment(blob, name))});
  });
  input.click();
}

// Pasting or dropping a picture into the message box puts it above the box too.
(() => {
  const body = get('body');
  const take = (files) => {
    const file = [...files].find((item) => /^image\//.test(item.type));
    if (!file || !active || !internet(threadProtocol(active))) return false;
    return stageAttachment(file, file.name || 'Pasted photo.png');
  };
  body.addEventListener('paste', (event) => { if (event.clipboardData?.files.length && take(event.clipboardData.files)) event.preventDefault(); });
  const form = get('schedule');
  form.addEventListener('dragover', (event) => { if ([...event.dataTransfer.types].includes('Files')) event.preventDefault(); });
  form.addEventListener('drop', (event) => { if (event.dataTransfer.files.length && take(event.dataTransfer.files)) event.preventDefault(); });
  // A waiting photo belongs to its chat.
  new MutationObserver(() => { if (staged && staged.chat !== active) clearStaged(); })
    .observe(get('recipient'), {childList: true, characterData: true, subtree: true});
})();

// Your stickers: made with Make a sticker, kept in this browser (IndexedDB), and sent as pictures.

function stickerStore(mode, run) {
  return new Promise((resolve, reject) => {
    const opening = indexedDB.open('fluid-stickers', 1);
    opening.onupgradeneeded = () => opening.result.createObjectStore('stickers', {keyPath: 'id'});
    opening.onerror = () => reject(opening.error);
    opening.onsuccess = () => {
      const db = opening.result;
      const transaction = db.transaction('stickers', mode);
      const request = run(transaction.objectStore('stickers'));
      transaction.oncomplete = () => { db.close(); resolve(request?.result); };
      transaction.onerror = () => { db.close(); reject(transaction.error); };
    };
  });
}

const listStickers = () => stickerStore('readonly', (store) => store.getAll()).then((items) => (items || []).sort((a, b) => b.date - a.date));
const saveSticker = (blob) => stickerStore('readwrite', (store) => store.put({id: newCardId() + newCardId(), blob, date: Date.now()}));
const deleteSticker = (id) => stickerStore('readwrite', (store) => store.delete(id));

async function sendSticker(item) {
  if (!internetChat()) return;
  const name = 'sticker-' + item.id + '.png';
  const upload = await uploadFile(item.blob, name);
  await send(name, name, null, Date.now(), {upload});
  if (typeof sendFeedback === 'function') sendFeedback();
}

/** "Your stickers" at the top of the GIF picker (cards.js openGifs). */
function stickerShelf(close) {
  const shelf = element('div', '', 'sticker-shelf');
  const grid = element('div', '', 'gif-grid sticker-grid');
  const make = element('button', '✂️ Make a sticker');
  make.type = 'button';
  make.addEventListener('click', () => { close(); makeSticker(); });
  shelf.append(element('small', 'Your stickers', 'muted'), grid, make);
  listStickers().then((items) => {
    if (!items.length) { grid.replaceWith(element('p', 'Make one from any photo: the background is taken out for you.', 'muted')); return; }
    for (const item of items.slice(0, 60)) {
      const button = element('button', '', 'gif-cell sticker-cell');
      button.type = 'button';
      button.setAttribute('aria-label', 'Send this sticker');
      const url = URL.createObjectURL(item.blob);
      button.append(Object.assign(element('img', ''), {src: url, alt: 'Sticker'}));
      button.addEventListener('click', () => { close(); sendSticker(item).catch(showError); });
      grid.append(button);
    }
  }).catch(() => grid.replaceWith(element('p', 'Stickers aren’t available in this browser.', 'muted')));
  return shelf;
}

/** + → Make a sticker: a photo opens in the editor with its background already being removed. */
function makeSticker() {
  if (typeof openPhotoEditor !== 'function') return;
  const input = Object.assign(element('input', ''), {type: 'file', accept: 'image/*'});
  input.addEventListener('change', () => {
    const file = input.files[0];
    if (!file) return;
    openPhotoEditor(file, {sticker: true, done: async (blob) => {
      try { await saveSticker(blob); say('Sticker saved. It’s in GIF or sticker → Your stickers.'); } catch (error) { showError(new Error('Couldn’t keep the sticker in this browser.')); }
    }});
  });
  input.click();
}

// GIF sites to browse: the built-in ones can each be turned off, and you can add your own with {query}.

const GIF_SITE_DEFAULTS = [{id: 'tenor', name: 'Tenor', search: 'https://tenor.com/search/{query}-gifs'},
  {id: 'giphy', name: 'GIPHY', search: 'https://giphy.com/search/{query}'}, {id: 'imgur', name: 'Imgur', search: 'https://imgur.com/search?q={query}'},
  {id: 'reddit', name: 'Reddit r/gifs', search: 'https://www.reddit.com/r/gifs/search/?q={query}&restrict_sr=1'}];

function storedList(key) { try { const value = JSON.parse(localStorage.getItem(key) || '[]'); return Array.isArray(value) ? value : []; } catch (error) { return []; } }

function gifSites() {
  const off = storedList('gifSites.off');
  const own = storedList('gifSites.own').filter((site) => typeof site?.name === 'string' && /^https:\/\/[^\s]+\{query\}/.test(site.search || ''));
  return GIF_SITE_DEFAULTS.filter((site) => !off.includes(site.id)).concat(own);
}

/** Settings → Media, GIFs, and fun: GIFs and stickers, GIF sites to browse, and Photos and videos, as on the phone. */
function mediaSettingsSection() {
  const gifs = element('section', '', 'settings-section');
  gifs.id = 'settings-media';
  gifs.append(element('h3', 'GIFs and stickers'));
  // Search in the GIF picker, with your own free key; it stays in this browser.
  const source = element('select', '');
  for (const [value, label] of [['tenor', 'Tenor'], ['giphy', 'GIPHY']]) source.append(Object.assign(element('option', label), {value}));
  source.value = localStorage.getItem('gifSource') || 'tenor';
  source.addEventListener('change', () => localStorage.setItem('gifSource', source.value));
  const key = Object.assign(element('input', ''), {type: 'password', autocomplete: 'off', value: localStorage.getItem('gifKey') || '',
    placeholder: 'Your free API key', maxLength: 200});
  key.addEventListener('change', () => localStorage.setItem('gifKey', key.value.trim()));
  gifs.append(field('Search results in the picker', source, 'Searches go through your server to the service you pick.'),
    field('API key', key, 'Create a free key at developers.google.com/tenor or developers.giphy.com. Saved in this browser.'));
  const stickers = element('div', '', 'subsection');
  stickers.append(element('h4', 'Your stickers'));
  const count = element('p', 'Checking…', 'muted');
  const make = element('button', 'Make a sticker');
  make.type = 'button';
  make.addEventListener('click', () => makeSticker());
  const clear = confirmButton('Delete all stickers', 'Tap again to delete them', async () => {
    for (const item of await listStickers()) await deleteSticker(item.id);
    count.textContent = 'No stickers yet.';
  }, 'quiet');
  const row = element('div', '', 'button-row');
  row.append(make, clear);
  stickers.append(count, row);
  listStickers().then((items) => { count.textContent = items.length ? items.length + (items.length === 1 ? ' sticker' : ' stickers') + ' in this browser.' : 'No stickers yet.'; })
    .catch(() => { count.textContent = 'Stickers aren’t available in this browser.'; });
  gifs.append(stickers);

  const section = element('section', '', 'settings-section');
  section.id = 'settings-gif-sites';
  section.append(element('h3', 'GIF sites to browse'));
  const sites = element('fieldset', '', 'stack');
  sites.append(element('legend', 'GIF sites to browse', 'visually-hidden'));
  const draw = () => {
    sites.replaceChildren(element('legend', 'GIF sites to browse', 'visually-hidden'));
    const off = storedList('gifSites.off');
    for (const site of GIF_SITE_DEFAULTS) {
      sites.append(checkbox(site.name, !off.includes(site.id), (value) => {
        const next = storedList('gifSites.off').filter((id) => id !== site.id);
        localStorage.setItem('gifSites.off', JSON.stringify(value ? next : [...next, site.id]));
      }));
    }
    for (const site of storedList('gifSites.own')) {
      const row = element('div', '', 'account-row');
      const words = element('span', '', 'account-words');
      words.append(element('strong', site.name), element('span', site.search, 'muted'));
      const remove = element('button', 'Remove', 'quiet');
      remove.type = 'button';
      remove.addEventListener('click', () => {
        localStorage.setItem('gifSites.own', JSON.stringify(storedList('gifSites.own').filter((item) => item.search !== site.search)));
        draw();
      });
      row.append(words, remove);
      sites.append(row);
    }
    const add = element('details', '', 'more');
    add.append(element('summary', 'Add your own site'));
    const name = Object.assign(element('input', ''), {maxLength: 40, placeholder: 'My GIF site'});
    const search = Object.assign(element('input', ''), {type: 'url', maxLength: 300, placeholder: 'https://example.org/search?q={query}'});
    const status = element('p', '', 'error');
    const save = element('button', 'Add site', 'primary');
    save.type = 'button';
    save.addEventListener('click', () => {
      if (!name.value.trim() || !/^https:\/\/[^\s]+\{query\}/.test(search.value.trim())) {
        status.textContent = 'Give it a name and an https:// address with {query} where the search words go.';
        return;
      }
      localStorage.setItem('gifSites.own', JSON.stringify([...storedList('gifSites.own'), {name: name.value.trim(), search: search.value.trim()}].slice(-12)));
      draw();
    });
    add.append(field('Name', name), field('Search address', search), status, save);
    sites.append(add, element('small', 'Open a site from GIF or sticker, copy a GIF’s link, and paste it there to send. Saved in this browser.', 'hint'));
  };
  draw();
  section.append(sites);


  const photo = element('section', '', 'settings-section');
  photo.id = 'settings-photos';
  photo.append(element('h3', 'Photos and videos'));
  photo.append(choiceGroup('Photo quality', 'photo-quality', [
    ['auto', 'Automatic', 'Smaller photos on a slow connection.'], ['saver', 'Data saver', 'Up to 1024 pixels.'],
    ['standard', 'Standard', 'Up to 1600 pixels.'], ['high', 'High quality', 'Up to 4096 pixels.']],
  localStorage.getItem('photoQuality') || 'auto', (value) => localStorage.setItem('photoQuality', value)),
  element('p', 'Photos are saved again before they go, which also removes location and camera details. Saved in this browser.', 'muted'));

  return [gifs, section, photo];
}
