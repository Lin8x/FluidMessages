'use strict';
// One voice or video call, shared by the browser companion and the phone's call screen.
//
// Media flows directly between the two devices with WebRTC. Signaling uses Matrix VoIP (version 1) events:
// m.call.invite, m.call.answer, m.call.candidates, m.call.select_answer, m.call.reject, and m.call.hangup, so the
// other person can answer in FluidMessages, Element, or another Matrix client that supports one-to-one calls.
// How signals travel is up to the caller: the companion relays them, or the phone sends them itself.

const FLUID_CALL_LIFETIME = 60000;

function fluidCallId() {
  const bytes = new Uint8Array(12);
  crypto.getRandomValues(bytes);
  return Array.from(bytes, (byte) => byte.toString(16).padStart(2, '0')).join('');
}

class FluidCall {
  // signal(type, content) sends one event and returns a promise. onState(text, ended) reports progress.
  constructor({signal, iceServers = [], video = false, onState = () => {}, onStreams = () => {}}) {
    this.signal = signal;
    this.video = video;
    this.onState = onState;
    this.onStreams = onStreams;
    this.callId = fluidCallId();
    this.partyId = fluidCallId();
    this.remoteParty = null;
    this.pending = [];
    this.outgoingCandidates = [];
    this.flushTimer = null;
    this.ended = false;
    this.muted = false;
    this.cameraOff = false;
    this.peer = new RTCPeerConnection({iceServers});
    this.remote = new MediaStream();
    this.peer.ontrack = (event) => {
      for (const track of event.streams[0]?.getTracks() || [event.track]) {
        if (!this.remote.getTracks().includes(track)) this.remote.addTrack(track);
      }
      this.onStreams(this.local, this.remote);
    };
    this.peer.onicecandidate = (event) => {
      if (!event.candidate) return;
      this.outgoingCandidates.push({candidate: event.candidate.candidate, sdpMid: event.candidate.sdpMid,
        sdpMLineIndex: event.candidate.sdpMLineIndex});
      // Candidates go in small batches, the way Matrix clients expect them.
      if (!this.flushTimer) this.flushTimer = setTimeout(() => this.flushCandidates(), 150);
    };
    this.peer.onconnectionstatechange = () => {
      const state = this.peer.connectionState;
      if (state === 'connected') { this.connectedAt = Date.now(); this.onState('Connected', false); }
      if (state === 'failed') this.hangup('ice_failed');
    };
  }

  base() { return {call_id: this.callId, party_id: this.partyId, version: '1'}; }

  async media() {
    this.local = await navigator.mediaDevices.getUserMedia({audio: true, video: this.video});
    for (const track of this.local.getTracks()) this.peer.addTrack(track, this.local);
    this.onStreams(this.local, this.remote);
  }

  async start() {
    this.onState('Calling…', false);
    await this.media();
    const offer = await this.peer.createOffer();
    await this.peer.setLocalDescription(offer);
    await this.signal('m.call.invite', {...this.base(), lifetime: FLUID_CALL_LIFETIME,
      offer: {type: 'offer', sdp: this.peer.localDescription.sdp}});
    this.timeout = setTimeout(() => { if (!this.remoteParty) this.hangup('invite_timeout'); }, FLUID_CALL_LIFETIME);
  }

  // Answers an m.call.invite event's content.
  async answer(invite) {
    this.callId = invite.call_id;
    this.remoteParty = invite.party_id || null;
    this.onState('Connecting…', false);
    await this.peer.setRemoteDescription({type: 'offer', sdp: invite.offer.sdp});
    await this.media();
    const answer = await this.peer.createAnswer();
    await this.peer.setLocalDescription(answer);
    await this.signal('m.call.answer', {...this.base(), answer: {type: 'answer', sdp: this.peer.localDescription.sdp}});
    await this.addPending();
  }

  async flushCandidates() {
    this.flushTimer = null;
    if (!this.outgoingCandidates.length || this.ended) return;
    const candidates = this.outgoingCandidates.splice(0);
    try { await this.signal('m.call.candidates', {...this.base(), candidates}); } catch (error) { /* ICE can still succeed */ }
  }

  async addPending() {
    for (const candidate of this.pending.splice(0)) {
      try { await this.peer.addIceCandidate(candidate); } catch (error) { /* stale candidates are harmless */ }
    }
  }

  // Handles a signaling event for this call: {type, content}.
  async handle(event) {
    const content = event.content || {};
    if (content.call_id !== this.callId || this.ended || content.party_id === this.partyId) return;
    if (event.type === 'm.call.answer' && !this.remoteParty) {
      this.remoteParty = content.party_id || null;
      clearTimeout(this.timeout);
      await this.peer.setRemoteDescription({type: 'answer', sdp: content.answer.sdp});
      this.signal('m.call.select_answer', {...this.base(), selected_party_id: this.remoteParty}).catch(() => {});
      await this.addPending();
    } else if (event.type === 'm.call.candidates') {
      for (const item of content.candidates || []) {
        if (!item || !item.candidate) continue;
        const candidate = {candidate: item.candidate, sdpMid: item.sdpMid, sdpMLineIndex: item.sdpMLineIndex};
        if (this.peer.remoteDescription) {
          try { await this.peer.addIceCandidate(candidate); } catch (error) { /* ignore */ }
        } else this.pending.push(candidate);
      }
    } else if (event.type === 'm.call.select_answer' && content.selected_party_id !== this.partyId) {
      this.finish('Answered on another device');
    } else if (event.type === 'm.call.hangup' || event.type === 'm.call.reject') {
      this.finish(event.type === 'm.call.reject' ? 'Declined' : 'Call ended');
    }
  }

  toggleMute() {
    this.muted = !this.muted;
    for (const track of this.local?.getAudioTracks() || []) track.enabled = !this.muted;
    return this.muted;
  }

  toggleCamera() {
    this.cameraOff = !this.cameraOff;
    for (const track of this.local?.getVideoTracks() || []) track.enabled = !this.cameraOff;
    return this.cameraOff;
  }

  hangup(reason = 'user_hangup') {
    if (this.ended) return;
    this.signal('m.call.hangup', {...this.base(), reason}).catch(() => {});
    this.finish(reason === 'invite_timeout' ? 'No answer' : 'Call ended');
  }

  decline() {
    if (this.ended) return;
    this.signal('m.call.reject', this.base()).catch(() => {});
    this.finish('Declined');
  }

  finish(text) {
    if (this.ended) return;
    this.ended = true;
    clearTimeout(this.timeout);
    clearTimeout(this.flushTimer);
    for (const track of this.local?.getTracks() || []) track.stop();
    this.peer.close();
    this.onState(text, true);
  }
}

// Icons for the call controls (Material Symbols paths, Apache 2.0).
const FLUID_CALL_ICONS = {
  mute: 'M12 14a3 3 0 0 0 3-3V5a3 3 0 0 0-6 0v6a3 3 0 0 0 3 3zm5-3a5 5 0 0 1-10 0H5a7 7 0 0 0 6 6.92V21h2v-3.08A7 7 0 0 0 19 11z',
  speaker: 'M3 9v6h4l5 5V4L7 9H3zm13.5 3A4.5 4.5 0 0 0 14 8v8a4.47 4.47 0 0 0 2.5-4zM14 3.23v2.06a7 7 0 0 1 0 13.42v2.06a9 9 0 0 0 0-17.54z',
  keypad: 'M6 3a2 2 0 1 0 0 4 2 2 0 0 0 0-4zm6 0a2 2 0 1 0 0 4 2 2 0 0 0 0-4zm6 0a2 2 0 1 0 0 4 2 2 0 0 0 0-4zM6 9a2 2 0 1 0 0 4 2 2 0 0 0 0-4zm6 0a2 2 0 1 0 0 4 2 2 0 0 0 0-4zm6 0a2 2 0 1 0 0 4 2 2 0 0 0 0-4zM6 15a2 2 0 1 0 0 4 2 2 0 0 0 0-4zm6 0a2 2 0 1 0 0 4 2 2 0 0 0 0-4zm6 0a2 2 0 1 0 0 4 2 2 0 0 0 0-4z',
  camera: 'M17 10.5V7a1 1 0 0 0-1-1H4a1 1 0 0 0-1 1v10a1 1 0 0 0 1 1h12a1 1 0 0 0 1-1v-3.5l4 4v-11l-4 4z',
  flip: 'M16 7h-1l-1-1h-4L9 7H8a2 2 0 0 0-2 2v6a2 2 0 0 0 2 2h8a2 2 0 0 0 2-2V9a2 2 0 0 0-2-2zm-4 8a3 3 0 1 1 0-6 3 3 0 0 1 0 6zM8.57.51l4.48 4.48V2.04A10 10 0 0 1 21.95 11h1.5A11.5 11.5 0 0 0 8.57.51zm2.38 20.45A10 10 0 0 1 2.05 13H.55a11.5 11.5 0 0 0 14.88 10.49l-4.48-4.48z',
  hold: 'M6 5h4v14H6zm8 0h4v14h-4z',
  record: 'M12 2a10 10 0 1 0 0 20 10 10 0 0 0 0-20zm0 18a8 8 0 1 1 0-16 8 8 0 0 1 0 16zm0-13a5 5 0 1 0 0 10 5 5 0 0 0 0-10z',
  end: 'M12 9c-1.6 0-3.15.25-4.6.72v3.1c0 .39-.23.74-.56.9-.98.49-1.87 1.12-2.66 1.85-.18.18-.43.28-.7.28-.28 0-.53-.11-.71-.29L.29 13.08a1 1 0 0 1 0-1.41C3.34 8.78 7.46 7 12 7s8.66 1.78 11.71 4.67a1 1 0 0 1 0 1.41l-2.48 2.48c-.18.18-.43.29-.71.29-.27 0-.52-.11-.7-.28a11.27 11.27 0 0 0-2.67-1.85.996.996 0 0 1-.56-.9v-3.1C15.15 9.25 13.6 9 12 9z',
  answer: 'M6.62 10.79a15.05 15.05 0 0 0 6.59 6.59l2.2-2.2c.27-.27.67-.36 1.02-.24 1.12.37 2.33.57 3.57.57.55 0 1 .45 1 1V20c0 .55-.45 1-1 1C10.61 21 3 13.39 3 4c0-.55.45-1 1-1h3.5c.55 0 1 .45 1 1 0 1.25.2 2.45.57 3.57.11.35.03.74-.25 1.02l-2.2 2.2z',
  lock: 'M17 9V7A5 5 0 0 0 7 7v2H6a2 2 0 0 0-2 2v9a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-9a2 2 0 0 0-2-2h-1zM9 7a3 3 0 0 1 6 0v2H9V7zm3 10a2 2 0 1 1 0-4 2 2 0 0 1 0 4z',
  unlock: 'M17 9V7A5 5 0 0 0 7.1 6h2.1A3 3 0 0 1 15 7v2H6a2 2 0 0 0-2 2v9a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-9a2 2 0 0 0-2-2h-1zm-5 8a2 2 0 1 1 0-4 2 2 0 0 1 0 4z',
};

function fluidIcon(name) {
  return '<svg viewBox="0 0 24 24" width="26" height="26" aria-hidden="true"><path fill="currentColor" d="' + FLUID_CALL_ICONS[name] + '"/></svg>';
}

// Swaps between the front and back camera on a call, keeping the same connection.
async function fluidFlipCamera(pc, local, facing) {
  const stream = await navigator.mediaDevices.getUserMedia({video: {facingMode: facing}});
  const track = stream.getVideoTracks()[0];
  const sender = pc.getSenders().find((item) => item.track && item.track.kind === 'video');
  if (sender) await sender.replaceTrack(track);
  for (const old of local.getVideoTracks()) { local.removeTrack(old); old.stop(); }
  local.addTrack(track);
}

// Records a call: both voices mixed together, plus their video on video calls. Chunks arrive every two seconds.
class FluidRecorder {
  constructor(local, remote, video, onChunk) {
    this.context = new AudioContext();
    const mix = this.context.createMediaStreamDestination();
    for (const stream of [local, remote]) {
      if (stream && stream.getAudioTracks().length) this.context.createMediaStreamSource(new MediaStream(stream.getAudioTracks())).connect(mix);
    }
    const tracks = [...mix.stream.getAudioTracks()];
    const remoteVideo = video && remote ? remote.getVideoTracks()[0] : null;
    if (remoteVideo) tracks.push(remoteVideo);
    this.video = !!remoteVideo;
    const types = this.video ? ['video/webm;codecs=vp8,opus', 'video/webm'] : ['audio/webm;codecs=opus', 'audio/webm', 'audio/ogg;codecs=opus'];
    this.mime = types.find((type) => MediaRecorder.isTypeSupported(type)) || '';
    this.recorder = new MediaRecorder(new MediaStream(tracks), this.mime ? {mimeType: this.mime} : undefined);
    this.recorder.ondataavailable = (event) => { if (event.data.size) onChunk(event.data); };
  }
  start() { this.recorder.start(2000); }
  stop() {
    return new Promise((resolve) => {
      this.recorder.onstop = () => { this.context.close(); resolve(); };
      if (this.recorder.state !== 'inactive') this.recorder.stop(); else resolve();
    });
  }
}

/**
 * A full-window call screen, used by the browser and the phone. Controls appear only when the call supports them:
 * onSpeaker, onFlip, onKeypad, onHold, and onRecord are optional. security is {tone: 'green'|'yellow'|'red', label}.
 */
class FluidCallView {
  constructor({title, video, incoming = false, security, onAnswer, onDecline, onHangup, onMute, onCamera, onSpeaker, onFlip,
                 onKeypad, onHold, onRecord, speakerOn}) {
    this.handlers = {video, onHangup, onMute, onCamera, onSpeaker, onFlip, onKeypad, onHold, onRecord, speakerOn};
    this.root = document.createElement('div');
    this.root.className = 'fluid-call' + (video ? ' is-video' : '');
    this.root.setAttribute('role', 'dialog');
    this.root.setAttribute('aria-label', (video ? 'Video call with ' : 'Voice call with ') + title);
    this.remoteVideo = Object.assign(document.createElement('video'), {autoplay: true, playsInline: true});
    this.remoteVideo.className = 'fluid-call-remote';
    this.remoteVideo.hidden = !video;
    this.localVideo = Object.assign(document.createElement('video'), {autoplay: true, playsInline: true, muted: true});
    this.localVideo.className = 'fluid-call-local';
    this.localVideo.hidden = !video;
    const top = document.createElement('div');
    top.className = 'fluid-call-top';
    if (security) {
      // The call's protection, the same lock colors as in chats.
      const pill = document.createElement('div');
      pill.className = 'fluid-call-security tone-' + security.tone;
      pill.innerHTML = fluidIcon(security.tone === 'red' ? 'unlock' : 'lock');
      pill.append(document.createTextNode(security.label));
      top.append(pill);
    }
    const avatar = document.createElement('div');
    avatar.className = 'fluid-call-avatar';
    avatar.textContent = title.split(/[\s@._-]+/).filter(Boolean).slice(0, 2).map((word) => word[0].toUpperCase()).join('') || '?';
    const heading = document.createElement('h2');
    heading.textContent = title;
    this.state = document.createElement('p');
    this.state.className = 'fluid-call-state';
    this.state.setAttribute('role', 'status');
    this.state.textContent = incoming ? (video ? 'Incoming video call' : 'Incoming voice call') : 'Calling…';
    this.recording = document.createElement('p');
    this.recording.className = 'fluid-call-recording';
    this.recording.textContent = '● Recording';
    this.recording.hidden = true;
    top.append(avatar, heading, this.state, this.recording);
    this.controls = document.createElement('div');
    this.controls.className = 'fluid-call-controls';
    this.bottom = document.createElement('div');
    this.bottom.className = 'fluid-call-bottom';
    if (incoming) {
      this.bottom.append(this.round('Decline', 'end', 'fluid-call-end', onDecline), this.round('Answer', 'answer', 'fluid-call-answer', onAnswer));
    } else this.showActive();
    this.root.append(this.remoteVideo, this.localVideo, top, this.controls, this.bottom);
    document.body.append(this.root);
  }

  round(label, icon, className, action) {
    const node = document.createElement('button');
    node.type = 'button';
    node.className = 'fluid-call-button ' + className;
    node.innerHTML = fluidIcon(icon);
    const text = document.createElement('span');
    text.textContent = label;
    node.append(text);
    node.setAttribute('aria-label', label);
    node.addEventListener('click', action);
    return node;
  }

  // A control that switches on and off; action returns the new state.
  toggle(label, icon, action, on = false) {
    const node = this.round(label, icon, 'fluid-call-toggle' + (on ? ' on' : ''), async () => {
      const value = await action();
      node.classList.toggle('on', !!value);
      node.setAttribute('aria-pressed', String(!!value));
    });
    node.setAttribute('aria-pressed', String(on));
    return node;
  }

  showActive() {
    const h = this.handlers;
    const nodes = [this.toggle('Mute', 'mute', h.onMute)];
    if (h.onSpeaker) nodes.push(this.toggle('Speaker', 'speaker', h.onSpeaker, !!h.speakerOn));
    if (h.onKeypad) nodes.push(this.round('Keypad', 'keypad', 'fluid-call-toggle', () => this.showKeypad()));
    if (h.video) nodes.push(this.toggle('Camera off', 'camera', h.onCamera));
    if (h.video && h.onFlip) nodes.push(this.round('Flip', 'flip', 'fluid-call-toggle', h.onFlip));
    if (h.onHold) nodes.push(this.toggle('Hold', 'hold', h.onHold));
    if (h.onRecord) nodes.push(this.toggle('Record', 'record', async () => {
      const on = await h.onRecord();
      this.recording.hidden = !on;
      return on;
    }));
    this.controls.replaceChildren(...nodes);
    this.bottom.replaceChildren(this.round('End', 'end', 'fluid-call-end', h.onHangup));
  }

  // Number keys for menus ("press 1 for…"), sent as touch tones. Like iOS: the keypad replaces the other controls,
  // the digits you press show at the top, and Hide (next to End) brings the controls back.
  showKeypad() {
    this.typed = '';
    this.topBefore = this.state.textContent;
    this.root.classList.add('keypad-open');
    const pad = document.createElement('div');
    pad.className = 'fluid-call-keypad';
    const letters = {2: 'ABC', 3: 'DEF', 4: 'GHI', 5: 'JKL', 6: 'MNO', 7: 'PQRS', 8: 'TUV', 9: 'WXYZ', 0: '+'};
    for (const key of ['1', '2', '3', '4', '5', '6', '7', '8', '9', '*', '0', '#']) {
      const node = document.createElement('button');
      node.type = 'button';
      node.setAttribute('aria-label', key);
      const digit = document.createElement('span');
      digit.textContent = key;
      node.append(digit);
      if (letters[key]) {
        const small = document.createElement('small');
        small.textContent = letters[key];
        node.append(small);
      }
      node.addEventListener('click', () => {
        this.typed = (this.typed + key).slice(-24);
        this.digits.textContent = this.typed;
        this.handlers.onKeypad(key);
      });
      pad.append(node);
    }
    this.digits = document.createElement('p');
    this.digits.className = 'fluid-call-digits';
    this.digits.setAttribute('aria-live', 'polite');
    this.root.querySelector('.fluid-call-top').append(this.digits);
    const hide = document.createElement('button');
    hide.type = 'button';
    hide.className = 'fluid-call-hide';
    hide.textContent = 'Hide';
    const spacer = document.createElement('span');
    spacer.className = 'fluid-call-spacer';
    hide.addEventListener('click', () => this.hideKeypad());
    this.controls.hidden = true;
    this.root.insertBefore(pad, this.bottom);
    this.keypad = {pad, hide, spacer};
    this.bottom.prepend(spacer);
    this.bottom.append(hide);
  }

  hideKeypad() {
    if (!this.keypad) return;
    this.keypad.pad.remove();
    this.keypad.hide.remove();
    this.keypad.spacer.remove();
    this.digits.remove();
    this.keypad = null;
    this.root.classList.remove('keypad-open');
    this.controls.hidden = false;
  }

  streams(local, remote) {
    if (local && this.localVideo.srcObject !== local) this.localVideo.srcObject = local;
    if (remote && this.remoteVideo.srcObject !== remote) this.remoteVideo.srcObject = remote;
  }

  setState(text, ended) {
    clearInterval(this.timer);
    if (text === 'Connected' && !ended) {
      // Like a phone call, a connected call shows how long it's been going.
      const start = Date.now();
      const tick = () => {
        const seconds = Math.floor((Date.now() - start) / 1000);
        this.state.textContent = Math.floor(seconds / 60) + ':' + String(seconds % 60).padStart(2, '0');
      };
      tick();
      this.timer = setInterval(tick, 1000);
    } else this.state.textContent = text;
    if (ended) {
      this.controls.replaceChildren();
      this.bottom.replaceChildren();
      this.recording.hidden = true;
      setTimeout(() => this.close(), 1600);
    }
  }

  close() { clearInterval(this.timer); this.root.remove(); if (this.onClose) this.onClose(); }
}

const FLUID_CALL_STYLE = `
.fluid-call { position: fixed; inset: 0; z-index: 50; display: flex; flex-direction: column; align-items: center; justify-content: space-between;
  gap: 2vh; padding: max(28px, 5vh) 16px max(24px, 4vh); box-sizing: border-box; overflow-y: auto; background: #0d0f14; color: #fff;
  text-align: center; font: 16px -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
  --call-button: clamp(50px, min(16vw, 9vh), 68px); }
.fluid-call [hidden] { display: none !important; }
.fluid-call-top { position: relative; display: grid; justify-items: center; gap: .4rem; max-width: 100%; }
.fluid-call h2 { margin: .1rem 0 0; font-size: clamp(1.3rem, 6vw, 1.7rem); font-weight: 600; overflow-wrap: anywhere; }
.fluid-call-state { margin: 0; color: #d4d7de; font-variant-numeric: tabular-nums; }
.fluid-call-digits { margin: 0; min-height: 1.6em; font-size: clamp(1.4rem, 7vw, 2rem); letter-spacing: .08em; font-variant-numeric: tabular-nums; }
.fluid-call-recording { margin: 0; color: #ff6b60; font-size: .9rem; font-weight: 600; }
.fluid-call-security { display: inline-flex; align-items: center; gap: .35rem; padding: .3rem .7rem; border-radius: 999px;
  background: rgb(0 0 0 / .38); font-size: .8rem; margin-bottom: .6rem; max-width: calc(100vw - 32px); }
.fluid-call-security svg { width: 14px; height: 14px; flex: none; }
.fluid-call-security.tone-green svg { color: #4cd964; } .fluid-call-security.tone-yellow svg { color: #ffc83d; }
.fluid-call-security.tone-red svg { color: #ff6b60; }
.fluid-call-avatar { width: clamp(64px, 12vh, 96px); height: clamp(64px, 12vh, 96px); border-radius: 50%; display: grid; place-items: center;
  font-size: clamp(1.5rem, 5vh, 2.2rem); font-weight: 600; background: rgb(255 255 255 / .16); }
.fluid-call.is-video .fluid-call-avatar, .fluid-call.keypad-open .fluid-call-avatar, .fluid-call.keypad-open .fluid-call-security { display: none; }
.fluid-call-remote { position: absolute; inset: 0; width: 100%; height: 100%; object-fit: cover; }
.fluid-call-local { position: absolute; right: 1rem; top: 1rem; width: 28vw; max-width: 200px; border-radius: 14px; z-index: 2; }
.fluid-call-controls { position: relative; display: grid; grid-template-columns: repeat(3, var(--call-button)); gap: 2vh clamp(14px, 6vw, 30px);
  justify-content: center; }
.fluid-call-bottom { position: relative; display: grid; grid-template-columns: repeat(3, var(--call-button)); gap: clamp(14px, 6vw, 30px);
  justify-content: center; align-items: center; }
.fluid-call-bottom > :only-child { grid-column: 2; }
.fluid-call-bottom > .fluid-call-button:first-child:nth-last-child(2) { grid-column: 1; }
.fluid-call-bottom > .fluid-call-button:first-child:nth-last-child(2) + .fluid-call-button { grid-column: 3; }
.fluid-call-button { display: grid; justify-items: center; gap: 5px; border: 0; background: none; color: #fff; cursor: pointer;
  font-size: .74rem; padding: 0; min-width: 0; }
.fluid-call-button span { white-space: nowrap; }
.fluid-call-button svg { width: var(--call-button); height: var(--call-button); padding: calc(var(--call-button) * .28); box-sizing: border-box;
  border-radius: 50%; background: rgb(255 255 255 / .18); }
.fluid-call-toggle.on svg { background: #fff; color: #111; }
.fluid-call-end svg { background: #ff3b30; }
.fluid-call-answer svg { background: #34c759; }
.fluid-call-keypad { position: relative; display: grid; grid-template-columns: repeat(3, var(--call-button)); gap: 1.6vh clamp(16px, 7vw, 30px);
  justify-content: center; }
.fluid-call-keypad button { width: var(--call-button); height: var(--call-button); border-radius: 50%; border: 0; display: grid; place-content: center;
  background: rgb(255 255 255 / .18); color: #fff; line-height: 1; padding: 0; }
.fluid-call-keypad span { font-size: calc(var(--call-button) * .42); }
.fluid-call-keypad small { font-size: calc(var(--call-button) * .14); letter-spacing: .12em; font-weight: 600; margin-top: 2px; }
.fluid-call-hide { position: relative; border: 0; background: none; color: #fff; font-size: 1rem; padding: .6rem; }
.fluid-call-spacer { display: block; }
@media (max-height: 520px) and (orientation: landscape) {
  .fluid-call { --call-button: clamp(42px, 13vh, 54px); padding: 12px 16px; gap: 8px; }
  .fluid-call-avatar { display: none; }
  .fluid-call-security { margin-bottom: 0; }
  .fluid-call-controls { grid-template-columns: repeat(4, var(--call-button)); gap: 8px 26px; }
  .fluid-call-keypad { grid-template-columns: repeat(6, var(--call-button)); gap: 8px 16px; }
}
`;
(() => {
  // The companion's stylesheet has these rules (its security policy blocks inline styles); the phone's page uses this.
  if (document.querySelector('link[href="/style.css"]')) return;
  const style = document.createElement('style');
  style.textContent = FLUID_CALL_STYLE;
  document.head.append(style);
})();
