'use strict';
// Invitations: IRC channel invites, Matrix room invites (including new direct chats, which Matrix starts with an
// invite), XMPP group chat invites, and XMPP contact requests. They wait on a full page until you accept or decline;
// nothing joins on its own. A chip beside the inbox title says how many are waiting.
// Loaded after app.js, companion.js, chats.js, and chat-commands.js, and uses their helpers.

let pendingInvites = [];

const INVITE_WHAT = {channel: 'channel', room: 'room', group: 'group chat', contact: 'contact request'};

function inviteChip() {
  let chip = document.getElementById('open-invites');
  if (!chip) {
    chip = element('button', '', 'chip-button invites-chip');
    chip.id = 'open-invites';
    chip.type = 'button';
    chip.hidden = true;
    chip.addEventListener('click', () => openInvites());
    (get('open-queue') || get('inbox-title')).after(chip);
  }
  return chip;
}

function showInviteChip() {
  const chip = inviteChip();
  const count = pendingInvites.length;
  chip.hidden = !count || (typeof sampleOn !== 'undefined' && sampleOn);
  chip.textContent = count === 1 ? '1 invitation' : count + ' invitations';
  chip.setAttribute('aria-label', chip.textContent + '. Open invitations');
}

function inviteAccountName(invite) {
  const account = (typeof chatAccounts !== 'undefined' ? chatAccounts : []).find((item) => item.id === invite.account);
  return account?.name || invite.account.split(':').slice(1).join(':');
}

/** One invitation: who invited you to what, with Accept and Decline. */
function inviteCard(invite, status) {
  const card = element('section', '', 'settings-section invite-card');
  const service = CHAT_PROTOCOL_NAMES[invite.protocol] || invite.protocol;
  const title = invite.kind === 'contact' ? (invite.name || invite.target) + ' wants to add you'
    : invite.name && invite.name !== invite.target ? invite.name : invite.target;
  card.append(element('h3', title));
  const line = invite.kind === 'contact'
    ? invite.target + ' asked to share presence with you on ' + service + '. Accepting adds a chat with them.'
    : (invite.from ? invite.from + ' invited you to this ' : 'An invitation to this ') + INVITE_WHAT[invite.kind] + ' on ' + service + '.';
  card.append(element('p', line), element('p', [invite.kind === 'contact' ? '' : invite.target, 'Account: ' + inviteAccountName(invite),
    new Date(invite.date).toLocaleString()].filter(Boolean).join(' · '), 'muted'));
  const row = element('div', '', 'button-row');
  const accept = element('button', invite.kind === 'contact' ? 'Accept' : 'Join', 'primary');
  const decline = element('button', 'Decline');
  accept.type = decline.type = 'button';
  const answer = (yes) => async () => {
    accept.disabled = decline.disabled = true;
    status.textContent = yes ? 'Joining…' : 'Declining…';
    try {
      const result = await api('protocols/invites', {id: invite.id, accept: yes});
      pendingInvites = result.invites || [];
      showInviteChip();
      await refresh().catch(() => {});
      if (yes && result.route) {
        closeDetailsPage(false);
        select(threadKey({address: result.route.address, protocol: result.route.protocol}));
        return;
      }
      status.textContent = yes ? 'Accepted.' : 'Declined.';
      openInvites();
    } catch (error) {
      status.textContent = error.message;
      accept.disabled = decline.disabled = false;
    }
  };
  accept.addEventListener('click', answer(true));
  decline.addEventListener('click', answer(false));
  row.append(accept, decline);
  card.append(row);
  return card;
}

/** The Invitations page: every waiting invitation, newest first. */
function openInvites() {
  const status = element('p', '', 'muted');
  status.setAttribute('role', 'status');
  const nodes = [...pendingInvites].sort((a, b) => b.date - a.date).map((invite) => inviteCard(invite, status));
  if (!nodes.length) {
    const empty = element('section', '', 'settings-section');
    empty.append(element('p', 'No invitations are waiting. When someone invites you to an IRC channel, a Matrix room, or an XMPP group chat, or asks to add you on XMPP, it shows here.', 'muted'));
    nodes.push(empty);
  }
  get('workspace').dataset.thread = 'true';
  detailsPage('Invitations', [status, ...nodes], () => {
    closeDetailsPage(false);
    if (!active) delete get('workspace').dataset.thread;
  });
}

if (typeof noticeSnapshot === 'function') {
  const plainNotice = noticeSnapshot;
  // eslint-disable-next-line no-func-assign
  noticeSnapshot = function (snapshot) {
    plainNotice(snapshot);
    pendingInvites = Array.isArray(snapshot.invites) ? snapshot.invites : [];
    showInviteChip();
  };
}
