'use strict';
// XMPP voice and video calls: WebRTC media with Jingle signaling, the way Conversations, Dino, and other XMPP apps
// call each other. Ringing uses Jingle Message Initiation (XEP-0353); the session uses Jingle RTP (XEP-0166,
// XEP-0167) with ICE-UDP (XEP-0176), DTLS-SRTP (XEP-0320), RTCP feedback (XEP-0293), header extensions
// (XEP-0294), and BUNDLE (XEP-0338). This file only converts and sends stanzas; the app sends them over XMPP.

const NS = {
  jingle: 'urn:xmpp:jingle:1',
  rtp: 'urn:xmpp:jingle:apps:rtp:1',
  ice: 'urn:xmpp:jingle:transports:ice-udp:1',
  dtls: 'urn:xmpp:jingle:apps:dtls:0',
  fb: 'urn:xmpp:jingle:apps:rtp:rtcp-fb:0',
  hdrext: 'urn:xmpp:jingle:apps:rtp:rtp-hdrext:0',
  group: 'urn:xmpp:jingle:apps:grouping:0',
  ssma: 'urn:xmpp:jingle:apps:rtp:ssma:0',
  jmi: 'urn:xmpp:jingle-message:0',
};

function escapeXml(value) {
  return String(value).replace(/[&<>"']/g, (c) => ({'&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&apos;'}[c]));
}

function attrs(object) {
  return Object.entries(object).filter(([, value]) => value !== undefined && value !== '')
    .map(([key, value]) => ' ' + key + '="' + escapeXml(value) + '"').join('');
}

// SDP → Jingle contents (the XML inside <jingle>), plus the BUNDLE group.
function sdpToJingle(sdp, creator) {
  const lines = sdp.split(/\r?\n/);
  const sessionLines = [];
  const sections = [];
  for (const line of lines) {
    if (line.startsWith('m=')) sections.push([line]);
    else if (sections.length) sections.at(-1).push(line);
    else sessionLines.push(line);
  }
  const sessionValue = (prefix) => sessionLines.find((line) => line.startsWith(prefix))?.slice(prefix.length);
  let xml = '';
  const bundle = sessionValue('a=group:BUNDLE ');
  for (const section of sections) {
    const value = (prefix) => (section.find((line) => line.startsWith(prefix)) ?? (prefix.startsWith('a=ice') || prefix.startsWith('a=fingerprint') || prefix.startsWith('a=setup') ? sessionLines.find((line) => line.startsWith(prefix)) : undefined))?.slice(prefix.length);
    const all = (prefix) => section.filter((line) => line.startsWith(prefix)).map((line) => line.slice(prefix.length));
    const [media, , , ...formats] = section[0].slice(2).split(' ');
    const mid = value('a=mid:') || media;
    const senders = section.includes('a=inactive') ? 'none' : section.includes('a=sendonly') ? (creator === 'initiator' ? 'initiator' : 'responder')
      : section.includes('a=recvonly') ? (creator === 'initiator' ? 'responder' : 'initiator') : 'both';
    let description = '';
    for (const id of formats) {
      const rtpmap = all('a=rtpmap:' + id + ' ')[0];
      if (!rtpmap) continue;
      const [name, clockrate, channels] = rtpmap.split('/');
      let inner = '';
      const fmtp = all('a=fmtp:' + id + ' ')[0];
      if (fmtp) for (const pair of fmtp.split(';')) {
        const [key, ...rest] = pair.trim().split('=');
        if (key) inner += '<parameter' + attrs({name: key, value: rest.join('=')}) + '/>';
      }
      for (const fb of all('a=rtcp-fb:' + id + ' ')) {
        const [type, subtype] = fb.split(' ');
        inner += '<rtcp-fb' + attrs({xmlns: NS.fb, type, subtype}) + '/>';
      }
      description += '<payload-type' + attrs({id, name, clockrate, channels: channels && channels !== '1' ? channels : undefined}) + '>' + inner + '</payload-type>';
    }
    for (const ext of all('a=extmap:')) {
      const [id, uri] = ext.split(' ');
      description += '<rtp-hdrext' + attrs({xmlns: NS.hdrext, id: id.split('/')[0], uri}) + '/>';
    }
    if (section.includes('a=rtcp-mux')) description += '<rtcp-mux/>';
    const sources = new Map();
    for (const line of all('a=ssrc:')) {
      const [ssrc, ...rest] = line.split(' ');
      const [name, ...valueParts] = rest.join(' ').split(':');
      if (!sources.has(ssrc)) sources.set(ssrc, []);
      sources.get(ssrc).push([name, valueParts.join(':')]);
    }
    for (const [ssrc, parameters] of sources) {
      description += '<source' + attrs({xmlns: NS.ssma, ssrc}) + '>' + parameters.map(([name, v]) => '<parameter' + attrs({name, value: v}) + '/>').join('') + '</source>';
    }
    let transport = '';
    const fingerprint = value('a=fingerprint:');
    if (fingerprint) {
      const [hash, print] = fingerprint.split(' ');
      transport += '<fingerprint' + attrs({xmlns: NS.dtls, hash, setup: value('a=setup:')}) + '>' + escapeXml(print) + '</fingerprint>';
    }
    for (const candidate of all('a=candidate:')) transport += candidateToXml(candidate);
    xml += '<content' + attrs({creator, name: mid, senders}) + '>'
      + '<description' + attrs({xmlns: NS.rtp, media}) + '>' + description + '</description>'
      + '<transport' + attrs({xmlns: NS.ice, ufrag: value('a=ice-ufrag:'), pwd: value('a=ice-pwd:')}) + '>' + transport + '</transport>'
      + '</content>';
  }
  if (bundle) xml += '<group' + attrs({xmlns: NS.group, semantics: 'BUNDLE'}) + '>'
    + bundle.split(' ').map((name) => '<content' + attrs({name}) + '/>').join('') + '</group>';
  return xml;
}

// "a=candidate:" value → <candidate/>.
function candidateToXml(value, mlineIndex) {
  const parts = value.replace(/^candidate:/, '').split(' ');
  const [foundation, component, protocol, priority, ip, port, , type] = parts;
  const extra = {};
  for (let i = 8; i + 1 < parts.length; i += 2) extra[parts[i]] = parts[i + 1];
  return '<candidate' + attrs({foundation, component, protocol: protocol.toLowerCase(), priority, ip, port, type,
    generation: extra.generation || '0', network: '0', id: Math.random().toString(36).slice(2, 12),
    'rel-addr': extra.raddr, 'rel-port': extra.rport}) + '/>';
}

function candidateFromXml(node) {
  const get = (name) => node.getAttribute(name);
  let line = 'candidate:' + get('foundation') + ' ' + get('component') + ' ' + get('protocol') + ' ' + get('priority') + ' '
    + get('ip') + ' ' + get('port') + ' typ ' + get('type');
  if (get('rel-addr')) line += ' raddr ' + get('rel-addr') + ' rport ' + get('rel-port');
  line += ' generation ' + (get('generation') || '0');
  return line;
}

const children = (node, name, ns) => Array.from(node?.children || []).filter((child) => child.localName === name && (!ns || child.namespaceURI === ns));

// Jingle contents → SDP. setupFlip turns the remote role into ours when needed (DTLS "actpass" offers).
function jingleToSdp(jingle, creatorRole) {
  const contents = children(jingle, 'content', NS.jingle);
  const group = children(jingle, 'group', NS.group)[0];
  let sdp = 'v=0\r\no=- ' + Date.now() + ' 2 IN IP4 0.0.0.0\r\ns=-\r\nt=0 0\r\n';
  if (group) sdp += 'a=group:BUNDLE ' + children(group, 'content').map((c) => c.getAttribute('name')).join(' ') + '\r\n';
  sdp += 'a=msid-semantic: WMS *\r\n';
  for (const content of contents) {
    const description = children(content, 'description', NS.rtp)[0];
    const transport = children(content, 'transport', NS.ice)[0];
    const media = description.getAttribute('media');
    const payloads = children(description, 'payload-type', NS.rtp);
    sdp += 'm=' + media + ' 9 UDP/TLS/RTP/SAVPF ' + payloads.map((p) => p.getAttribute('id')).join(' ') + '\r\n';
    sdp += 'c=IN IP4 0.0.0.0\r\na=rtcp:9 IN IP4 0.0.0.0\r\n';
    if (transport) {
      sdp += 'a=ice-ufrag:' + transport.getAttribute('ufrag') + '\r\na=ice-pwd:' + transport.getAttribute('pwd') + '\r\n';
      const fingerprint = children(transport, 'fingerprint', NS.dtls)[0];
      if (fingerprint) {
        sdp += 'a=fingerprint:' + fingerprint.getAttribute('hash') + ' ' + fingerprint.textContent.trim() + '\r\n';
        sdp += 'a=setup:' + (fingerprint.getAttribute('setup') || 'actpass') + '\r\n';
      }
    }
    sdp += 'a=mid:' + content.getAttribute('name') + '\r\n';
    const senders = content.getAttribute('senders') || 'both';
    const remoteSends = senders === 'both' || senders === creatorRole;
    const remoteReceives = senders === 'both' || (senders !== 'none' && senders !== creatorRole);
    sdp += 'a=' + (remoteSends && remoteReceives ? 'sendrecv' : remoteSends ? 'sendonly' : remoteReceives ? 'recvonly' : 'inactive') + '\r\n';
    for (const ext of children(description, 'rtp-hdrext', NS.hdrext)) sdp += 'a=extmap:' + ext.getAttribute('id') + ' ' + ext.getAttribute('uri') + '\r\n';
    if (children(description, 'rtcp-mux').length || group) sdp += 'a=rtcp-mux\r\n';
    for (const payload of payloads) {
      const id = payload.getAttribute('id');
      const channels = payload.getAttribute('channels');
      sdp += 'a=rtpmap:' + id + ' ' + payload.getAttribute('name') + '/' + payload.getAttribute('clockrate') + (channels ? '/' + channels : '') + '\r\n';
      const parameters = children(payload, 'parameter');
      if (parameters.length) sdp += 'a=fmtp:' + id + ' ' + parameters.map((p) => p.getAttribute('name') + '=' + p.getAttribute('value')).join(';') + '\r\n';
      for (const fb of children(payload, 'rtcp-fb', NS.fb)) sdp += 'a=rtcp-fb:' + id + ' ' + fb.getAttribute('type') + (fb.getAttribute('subtype') ? ' ' + fb.getAttribute('subtype') : '') + '\r\n';
    }
    for (const source of children(description, 'source', NS.ssma)) {
      for (const parameter of children(source, 'parameter')) {
        sdp += 'a=ssrc:' + source.getAttribute('ssrc') + ' ' + parameter.getAttribute('name') + (parameter.getAttribute('value') ? ':' + parameter.getAttribute('value') : '') + '\r\n';
      }
    }
    if (transport) for (const candidate of children(transport, 'candidate', NS.ice)) sdp += 'a=' + candidateFromXml(candidate) + '\r\n';
  }
  return sdp;
}

function parseXml(xml) { return new DOMParser().parseFromString(xml, 'application/xml').documentElement; }

function stanzaId() { return 'fm' + Math.random().toString(36).slice(2, 12); }

// One XMPP call. send(xml) sends a stanza; same callbacks and controls as FluidCall in call.js.
class FluidXmppCall {
  constructor({send, self, peer, video = false, iceServers = [], onState = () => {}, onStreams = () => {}}) {
    this.send = send;
    this.self = self;          // our full JID
    this.peer = peer;          // their bare JID, then the full JID of the device that answers
    this.video = video;
    this.onState = onState;
    this.onStreams = onStreams;
    this.sid = stanzaId() + stanzaId();
    this.callId = this.sid;
    this.ended = false;
    this.muted = false;
    this.cameraOff = false;
    this.initiator = true;
    this.pendingCandidates = [];
    this.pc = new RTCPeerConnection({iceServers});
    this.remote = new MediaStream();
    this.pc.ontrack = (event) => {
      for (const track of event.streams[0]?.getTracks() || [event.track]) if (!this.remote.getTracks().includes(track)) this.remote.addTrack(track);
      this.onStreams(this.local, this.remote);
    };
    this.pc.onicecandidate = (event) => {
      if (!event.candidate || !this.sessionStarted) {
        if (event.candidate) this.pendingCandidates.push(event.candidate);
        return;
      }
      this.sendCandidate(event.candidate);
    };
    this.pc.onconnectionstatechange = () => {
      if (this.pc.connectionState === 'connected') { this.connectedAt = Date.now(); this.onState('Connected', false); }
      if (this.pc.connectionState === 'failed') this.hangup('connectivity-error');
    };
  }

  role() { return this.initiator ? 'initiator' : 'responder'; }

  iq(action, inner) {
    const attributes = {xmlns: NS.jingle, action, sid: this.sid};
    if (action === 'session-initiate') attributes.initiator = this.self;
    if (action === 'session-accept') attributes.responder = this.self;
    this.send('<iq' + attrs({to: this.peer, type: 'set', id: stanzaId()}) + '><jingle' + attrs(attributes) + '>' + inner + '</jingle></iq>');
  }

  message(element, inner = '', to = this.peer) {
    this.send('<message' + attrs({to, type: 'chat', id: stanzaId()}) + '><' + element + attrs({xmlns: NS.jmi, id: this.sid}) + '>' + inner
      + '</' + element + '><store xmlns="urn:xmpp:hints"/></message>');
  }

  sendCandidate(candidate) {
    const mid = candidate.sdpMid || ['audio', 'video'][candidate.sdpMLineIndex || 0];
    // Every content was created by the caller, so trickled candidates name the initiator as creator.
    this.iq('transport-info', '<content' + attrs({creator: 'initiator', name: mid}) + '><transport'
      + attrs({xmlns: NS.ice, ufrag: (candidate.usernameFragment || '')}) + '>' + candidateToXml(candidate.candidate) + '</transport></content>');
  }

  async media() {
    this.local = await navigator.mediaDevices.getUserMedia({audio: true, video: this.video});
    for (const track of this.local.getTracks()) this.pc.addTrack(track, this.local);
    this.onStreams(this.local, this.remote);
  }

  // Rings their devices; the session starts when one of them says "proceed".
  async start() {
    this.onState('Calling…', false);
    await this.media();
    this.message('propose', '<description' + attrs({xmlns: NS.rtp, media: 'audio'}) + '/>'
      + (this.video ? '<description' + attrs({xmlns: NS.rtp, media: 'video'}) + '/>' : ''));
    this.timeout = setTimeout(() => { if (!this.sessionStarted) this.hangup('timeout'); }, 60000);
  }

  async initiate() {
    const offer = await this.pc.createOffer();
    await this.pc.setLocalDescription(offer);
    this.sessionStarted = true;
    this.iq('session-initiate', sdpToJingle(this.pc.localDescription.sdp, 'initiator'));
    for (const candidate of this.pendingCandidates.splice(0)) this.sendCandidate(candidate);
  }

  // Answers a "propose": tells their device to go ahead, then waits for its session-initiate.
  async answer(invite) {
    this.initiator = false;
    this.sid = invite.sid;
    this.callId = invite.sid;
    this.peer = invite.from;
    this.video = !!invite.video;
    this.onState('Connecting…', false);
    await this.media();
    this.message('proceed');
  }

  decline() {
    if (this.ended) return;
    this.message('reject');
    this.finish('Declined');
  }

  // Handles one incoming stanza (as XML) for this call.
  async handle(event) {
    if (this.ended || !event.xml) return;
    const stanza = parseXml(event.xml);
    const from = stanza.getAttribute('from') || '';
    const jmi = Array.from(stanza.children).find((child) => child.namespaceURI === NS.jmi);
    if (jmi && jmi.getAttribute('id') === this.sid) {
      if (jmi.localName === 'proceed' && this.initiator && !this.sessionStarted) {
        this.peer = from;
        clearTimeout(this.timeout);
        this.onState('Connecting…', false);
        await this.initiate();
      } else if (jmi.localName === 'reject') this.finish('Declined');
      else if (jmi.localName === 'retract') this.finish('Missed call');
      else if (jmi.localName === 'accept' && !this.initiator) this.finish('Answered on another device');
      return;
    }
    const jingle = children(stanza, 'jingle', NS.jingle)[0];
    if (!jingle || jingle.getAttribute('sid') !== this.sid) return;
    const action = jingle.getAttribute('action');
    if (action === 'session-initiate' && !this.initiator) {
      this.peer = from;
      await this.pc.setRemoteDescription({type: 'offer', sdp: jingleToSdp(jingle, 'initiator')});
      const answer = await this.pc.createAnswer();
      await this.pc.setLocalDescription(answer);
      this.sessionStarted = true;
      this.iq('session-accept', sdpToJingle(this.pc.localDescription.sdp, 'initiator'));
      for (const candidate of this.pendingCandidates.splice(0)) this.sendCandidate(candidate);
    } else if (action === 'session-accept' && this.initiator) {
      await this.pc.setRemoteDescription({type: 'answer', sdp: jingleToSdp(jingle, 'responder')});
    } else if (action === 'transport-info') {
      for (const content of children(jingle, 'content', NS.jingle)) {
        const transport = children(content, 'transport', NS.ice)[0];
        for (const candidate of children(transport, 'candidate', NS.ice)) {
          try { await this.pc.addIceCandidate({candidate: candidateFromXml(candidate), sdpMid: content.getAttribute('name')}); }
          catch (error) { /* stale candidates are harmless */ }
        }
      }
    } else if (action === 'session-terminate') {
      this.finish('Call ended');
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

  hangup(reason = 'success') {
    if (this.ended) return;
    if (this.sessionStarted) this.iq('session-terminate', '<reason><' + reason + '/></reason>');
    else if (this.initiator) this.message('retract');
    else this.message('reject');
    this.finish(reason === 'timeout' ? 'No answer' : 'Call ended');
  }

  finish(text) {
    if (this.ended) return;
    this.ended = true;
    clearTimeout(this.timeout);
    for (const track of this.local?.getTracks() || []) track.stop();
    this.pc.close();
    this.onState(text, true);
  }
}
