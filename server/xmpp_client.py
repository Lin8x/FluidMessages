"""Native XMPP for the companion using slixmpp.

One XMPP session stays open on the companion (with stream management, so brief network drops resume without
losing messages). Supports one-to-one chats and group chats (MUC), message carbons, delivery receipts, replies
(XEP-0461), reactions (XEP-0444), and files through HTTP File Upload (XEP-0363) with an out-of-band link
(XEP-0066), the way Conversations and other clients send them.

With the optional OMEMO modules installed (xmpp_omemo.py), the companion is also one OMEMO device of your account:
one-to-one chats are locked with OMEMO when the other person's app uses it, and so are members-only group chats
where members can see each other's addresses. Without them, or when the other side has no OMEMO, chats are only
encrypted in transit, and the chat says so.

For daily use it also reports group chat subjects, joins, leaves, and nick changes (as event lines), group chat
invitations and contact requests (which wait on the Invitations page instead of being accepted on their own),
and catches up on missed messages from the server's archive (MAM, XEP-0313) after a reconnect.
"""
import asyncio
from datetime import datetime, timezone
import xml.etree.ElementTree as ET
import threading
import time

try:
    import slixmpp
    from slixmpp.exceptions import IqError
except ImportError:  # The companion still runs without XMPP; adding an XMPP account then reports this.
    slixmpp = None

import omemo_trust
try:
    import xmpp_omemo
except Exception:
    xmpp_omemo = None

OMEMO_NS = "eu.siacs.conversations.axolotl"
OMEMO_FALLBACK = "This message is locked with OMEMO, and your app can't open it."


def omemo_available():
    return xmpp_omemo is not None and xmpp_omemo.available()


def aesgcm_key(fragment):
    """The (key, iv) in an aesgcm:// link's fragment: hex of a 12- or 16-byte IV, then the 32-byte key."""
    try:
        raw = bytes.fromhex(fragment or "")
    except ValueError:
        return None
    if len(raw) not in (44, 48):
        return None
    return raw[-32:], raw[:-32]


def aesgcm_open(data, key, iv):
    """Decrypts a downloaded XEP-0454 file (ciphertext followed by the 16-byte tag); None if it doesn't open."""
    try:
        from cryptography.hazmat.primitives.ciphers.aead import AESGCM
        return AESGCM(key).decrypt(iv, data, None)
    except Exception:
        return None


def aesgcm_url(url, key, iv):
    """XEP-0454: an https link to an encrypted file becomes aesgcm://…#<iv><key> in hex."""
    return "aesgcm" + url[len("https"):] + "#" + (iv + key).hex() if url.startswith("https://") else ""


def available():
    return slixmpp is not None


class XmppClient:
    def __init__(self, account, on_message, rooms, on_event=None, auto=None, omemo_store=None, chats=None):
        if slixmpp is None:
            raise RuntimeError("Install slixmpp on the companion to use XMPP")
        self.account = account
        self.on_message = on_message  # (conversation_jid, author, text, ids, date_ms, outgoing, extra)
        self.on_event = on_event or (lambda *args: None)  # (kind, conversation_jid, data)
        self.rooms = set(rooms)
        self.nicks = {}        # room → your nick there
        self.joined = set()    # rooms whose own presence (status 110) has arrived, so later presences are live
        self.occupants = {}    # room → nicks
        self.last_seen = 0.0   # when the last message arrived or the connection dropped, for MAM catch-up
        self.loop = None
        self.client = None
        self.ready = threading.Event()
        self.stopped = False
        # Reconnect automatically (on by default): off, it connects only when you tap Reconnect.
        self.auto = auto or (lambda: True)
        self.wake = threading.Event()
        self.dropped = False  # Disconnect: closed by you, so it waits for Connect or Reconnect
        # What the apps show: connected, connecting, reconnecting, or disconnected ("manual" while auto is off).
        self.status = {"state": "connecting", "network": account.get("jid", "").partition("@")[2], "retry_at": 0, "error": "",
                       "manual": False}
        # OMEMO: this install is one device of the account. The store is encrypted on disk (xmpp_omemo.py).
        self.omemo_store = omemo_store
        self.chats = chats or (lambda: [])  # one-to-one chats, for their lock state after connecting
        self.omemo = None
        self.omemo_started = None
        self.locks = {}         # conversation → lock inputs (omemo_trust.lock)
        self.sent_text = {}     # recent message id → text you sent, for your own copies that come back locked
        self.locked_ids = set()  # recent message ids sent with OMEMO
        self.told_plain = set()  # chats already told that a message went without OMEMO
        self.real_jids = {}     # room → {nick: real bare JID}, when the room shows them
        self.room_ok = {}       # room → members-only and not anonymous, so OMEMO can lock it
        self.room_affiliates = {}  # room → members' bare JIDs from its affiliation lists

    def run(self):
        self.loop = asyncio.new_event_loop()
        asyncio.set_event_loop(self.loop)
        client = slixmpp.ClientXMPP(self.account["jid"], self.account["password"])
        for plugin in ("xep_0030", "xep_0199", "xep_0198", "xep_0280", "xep_0184", "xep_0045", "xep_0359",
                       "xep_0461", "xep_0444", "xep_0363", "xep_0066", "xep_0203", "xep_0249", "xep_0313",
                       "xep_0060", "xep_0163", "xep_0334"):
            try:
                client.register_plugin(plugin)
            except Exception:
                if plugin in ("xep_0249", "xep_0313", "xep_0060", "xep_0163", "xep_0334"):
                    continue  # Optional: direct invitations, the message archive, and PEP for OMEMO.
                raise
        # Contact requests wait for your answer on the Invitations page instead of being accepted on their own.
        client.roster.auto_authorize = False
        client.roster.auto_subscribe = False
        client.plugin["xep_0199"].enable_keepalive(interval=300, timeout=30)
        client.add_event_handler("session_start", self.started)
        client.add_event_handler("message", self.received)
        client.add_event_handler("groupchat_message", self.received)
        client.add_event_handler("carbon_sent", lambda msg: self.received(msg["carbon_sent"], outgoing=True))
        # A message sent to another of your devices; OMEMO locks it for this one too.
        client.add_event_handler("carbon_received", lambda msg: self.received(msg["carbon_received"]))
        client.add_event_handler("pubsub_publish", self.pep_event)
        client.add_event_handler("disconnected", self.disconnected)
        client.add_event_handler("groupchat_subject", self.subject)
        client.add_event_handler("groupchat_presence", self.presence)
        client.add_event_handler("groupchat_invite", self.invited)
        client.add_event_handler("groupchat_direct_invite", self.invited)
        client.add_event_handler("presence_subscribe", self.subscribe)
        self.client = client
        if not self.auto():
            self.status = {**self.status, "state": "disconnected", "manual": True}
            self.wake.wait()
            self.wake.clear()
            if self.stopped:
                return
        self.status = {**self.status, "state": "connecting", "manual": False}
        client.connect(*(self.address() or ()))
        self.loop.run_forever()

    def address(self):
        return (self.account["host"], int(self.account.get("port", 5222))) if self.account.get("host") else None

    def reconnect_now(self):
        """The Reconnect button: connects now, also while Reconnect automatically is off."""
        self.dropped = False
        self.wake.set()
        if self.loop and self.loop.is_running() and self.client and not self.ready.is_set() and self.status["state"] in ("disconnected", "reconnecting"):
            self.status = {**self.status, "state": "connecting", "manual": False}
            self.loop.call_soon_threadsafe(lambda: self.client.connect(*(self.address() or ())))

    async def started(self, _event):
        self.status = {**self.status, "state": "connected", "error": "", "manual": False}
        self.client.send_presence()
        await self.client.get_roster()
        await self.client.plugin["xep_0280"].enable()
        nick = self.account["jid"].split("@", 1)[0]
        self.joined.clear()
        for room in self.rooms:
            self.client.plugin["xep_0045"].join_muc(room, self.nicks.get(room, nick))
        self.ready.set()
        asyncio.ensure_future(self.after_start(self.last_seen))

    async def after_start(self, since):
        """OMEMO first (messages from the archive may be locked), then the archive catch-up."""
        await self.start_omemo()
        if self.omemo is not None:
            self.omemo.history_start()
        try:
            if since:
                await self.catch_up(since)
        finally:
            if self.omemo is not None:
                try:
                    await self.omemo.history_done()
                except Exception:
                    pass
        if self.omemo is not None:
            for chat in list(self.chats())[:200]:
                await self.update_lock(chat)

    async def catch_up(self, since):
        """Missed one-to-one messages from the server's archive (MAM) since the connection dropped. The gateway skips
        ones it already has."""
        try:
            mam = self.client.plugin["xep_0313"]
        except (KeyError, Exception):
            return
        start = datetime.fromtimestamp(since - 60, tz=timezone.utc)
        try:
            count = 0
            async for page in mam.iterate(start=start, rsm={"max": 50}):
                for result in page["mam"]["results"] if "mam" in page else []:
                    forwarded = result["mam_result"]["forwarded"]
                    msg = forwarded["stanza"]
                    stamp = forwarded["delay"]["stamp"]
                    own = slixmpp.JID(self.account["jid"]).bare
                    if msg.xml.find("{%s}encrypted" % OMEMO_NS) is not None:
                        await self.received_locked(msg, msg["from"].bare == own, stamp)
                    else:
                        self.received(msg, outgoing=msg["from"].bare == own, archived=stamp)
                    count += 1
                if count >= 500:
                    break
        except Exception:
            return  # The server has no archive, or the archive API differs: live messages still arrive.

    def subject(self, msg):
        room = msg["from"].bare
        if room not in self.rooms:
            return
        self.on_event("subject", room, {"nick": msg["from"].resource, "subject": msg["subject"],
                                        "live": room in self.joined and not msg["delay"]["stamp"]})

    def presence(self, presence):
        """Joins, leaves, kicks, bans, and nick changes in a group chat. The burst of presences sent while you join
        (before your own, status 110) is the member list, not news."""
        room, nick = presence["from"].bare, presence["from"].resource
        if room not in self.rooms or not nick:
            return
        codes = {status for status in presence["muc"]["status_codes"]} if presence["muc"] else set()
        members = self.occupants.setdefault(room, set())
        gone = presence["type"] == "unavailable"
        try:
            real = str(presence["muc"]["jid"].bare) if presence["muc"]["jid"] else ""
        except Exception:
            real = ""
        known = self.real_jids.setdefault(room, {})
        if gone:
            known.pop(nick, None)
        elif real:
            known[nick] = real.lower()
        if 110 in codes:
            self.joined.add(room)
            self.nicks[room] = nick
            if gone:
                self.joined.discard(room)
            elif self.omemo_store:
                asyncio.ensure_future(self.check_room(room))
            self.on_event("members", room, {"members": sorted(members)})
            return
        live = room in self.joined
        if gone:
            members.discard(nick)
            if 303 in codes and presence["muc"]["nick"]:
                members.add(presence["muc"]["nick"])
                kind, data = "nick", {"nick": nick, "new": presence["muc"]["nick"]}
            else:
                kind = "ban" if 301 in codes else "kick" if 307 in codes else "part"
                data = {"nick": nick}
        else:
            if nick in members:
                return  # A status change, not a join.
            members.add(nick)
            kind, data = "join", {"nick": nick}
        if live:
            self.on_event(kind, room, data)
            self.on_event("members", room, {"members": sorted(members)})

    def invited(self, msg):
        """A group chat invitation, mediated (XEP-0045) or direct (XEP-0249)."""
        room, sender = "", ""
        try:
            room = str(msg["groupchat_invite"]["jid"])  # Direct: the room is named in the invitation.
        except Exception:
            pass
        try:
            sender = str(msg["muc"]["invite"]["from"])  # Mediated: the room relays who invited you.
        except Exception:
            pass
        room = room or str(msg["from"].bare)
        sender = sender or str(msg["from"])
        if room:
            self.on_event("invite", str(slixmpp.JID(room).bare).lower(), {"from": str(slixmpp.JID(sender).bare),
                                                                           "name": str(slixmpp.JID(room).user)})

    def subscribe(self, presence):
        jid = presence["from"].bare
        if jid and jid != slixmpp.JID(self.account["jid"]).bare:
            self.on_event("subscribe", jid.lower(), {"name": presence["nick"]["nick"] if "nick" in presence.plugins else ""})

    def disconnect_now(self):
        """Disconnect: closes the connection and waits for Connect or Reconnect. Its chats stay in the list."""
        self.dropped = True
        self.status = {**self.status, "state": "disconnected", "retry_at": 0, "error": "", "manual": True}
        if self.loop and self.client and self.loop.is_running():
            self.loop.call_soon_threadsafe(self.client.disconnect)

    def disconnected(self, _event):
        self.ready.clear()
        self.last_seen = self.last_seen or time.time()
        if not self.stopped and (self.dropped or not self.auto()):
            # Reconnect automatically off: wait for Reconnect.
            self.status = {**self.status, "state": "disconnected", "manual": True}
        elif not self.stopped:
            # Reconnect with a pause, so an unreachable server doesn't spin.
            self.status = {**self.status, "state": "reconnecting", "retry_at": int((time.time() + 30) * 1000), "manual": False}
            self.loop.call_later(30, self.reconnect_later)

    def reconnect_later(self):
        if not self.stopped and not self.ready.is_set() and self.status["state"] == "reconnecting":
            self.status = {**self.status, "state": "connecting"}
            self.client.connect(*(self.address() or ()))

    def stop(self):
        self.stopped = True
        self.wake.set()
        if self.loop and self.client:
            self.loop.call_soon_threadsafe(self.client.disconnect)

    def message_id(self, msg):
        if msg["type"] == "groupchat" and msg["stanza_id"]["id"]:
            return msg["stanza_id"]["id"]
        return msg["id"]

    def received(self, msg, outgoing=False, archived=None, unlocked=None):
        if msg["type"] not in ("chat", "normal", "groupchat"):
            return
        if unlocked is None and msg.xml.find("{%s}encrypted" % OMEMO_NS) is not None:
            asyncio.ensure_future(self.received_locked(msg, outgoing, archived))
            return
        if archived is None:
            self.last_seen = time.time()
        own = slixmpp.JID(self.account["jid"]).bare
        if msg["type"] == "groupchat":
            conversation = msg["from"].bare
            author = msg["from"].resource
            if conversation not in self.rooms or not author:
                return
            outgoing = author == own.split("@", 1)[0] or outgoing
        else:
            conversation = (msg["to"] if outgoing or msg["from"].bare == own else msg["from"]).bare
            author = "" if outgoing else msg["from"].bare
        extra = {}
        reactions = msg["reactions"]
        retract = msg.xml.find("{urn:xmpp:message-retract:1}retract")
        if retract is not None and retract.get("id"):
            # XEP-0424: the sender took a message back.
            extra = {"retract": retract.get("id")}
            text = ""
        elif reactions["id"]:
            values = list(reactions.get_values())
            # An empty set takes the sender's reaction back.
            extra = {"reaction": values[-1] if values else "", "reaction_to": reactions["id"]}
            text = ""
        elif unlocked is not None:
            # An OMEMO message: the text is what was locked inside, never the plain fallback body.
            text, info = unlocked
            extra["omemo"] = info
            if msg["reply"]["id"]:
                extra["reply_to"] = msg["reply"]["id"]
                lines = text.split("\n")
                while len(lines) > 1 and lines[0].startswith(">"):
                    lines.pop(0)  # The quoted part other apps put first.
                text = "\n".join(lines).strip() or text
            if text.strip().startswith("aesgcm://") and " " not in text.strip():
                extra["file_url"] = text.strip()  # XEP-0454: an encrypted file; the companion opens it.
            if not text:
                return
        else:
            text = msg["body"]
            if msg["reply"]["id"]:
                extra["reply_to"] = msg["reply"]["id"]
                text = msg["reply"].strip_fallback_content() or text
            if msg["oob"]["url"]:
                extra["file_url"] = msg["oob"]["url"]
            if not text and not extra:
                return
        stamp = archived or (msg["delay"]["stamp"] if msg["delay"]["stamp"] else None)
        date = int(stamp.timestamp() * 1000) if stamp else int(time.time() * 1000)
        if archived is not None:
            extra["archived"] = True
        if msg["type"] != "groupchat" and not outgoing:
            # Someone in your roster isn't a stranger (message_requests.py); anyone else starts a message request.
            try:
                extra["roster"] = msg["from"].bare in self.client.client_roster
            except Exception:
                pass
        # XEP-0245: a body starting "/me " is an action; the apps show it as "* name waves", as for IRC and Matrix.
        self.on_message(conversation, author, text, self.message_id(msg), date, outgoing, extra)

    def call(self, coroutine, timeout=60):
        if not self.ready.wait(20):
            raise ConnectionError("XMPP isn’t connected yet")
        return asyncio.run_coroutine_threadsafe(coroutine, self.loop).result(timeout)

    def sync_rooms(self, rooms):
        """Joins group chats added since the session started and leaves ones that were left."""
        wanted = set(rooms)
        added, gone = wanted - self.rooms, self.rooms - wanted
        self.rooms = wanted
        if not self.ready.is_set():
            return  # Joined in started().
        nick = self.account["jid"].split("@", 1)[0]
        def apply():
            muc = self.client.plugin["xep_0045"]
            for room in added:
                muc.join_muc(room, nick)
            for room in gone:
                muc.leave_muc(room, nick)
        self.loop.call_soon_threadsafe(apply)

    def muc_services(self):
        """Group-chat (MUC) services on the account's server, found with service discovery (XEP-0030)."""
        domain = self.account["jid"].split("@", 1)[1].split("/", 1)[0]
        async def find():
            disco = self.client.plugin["xep_0030"]
            items = await disco.get_items(jid=domain, timeout=15)
            found = []
            for jid, _node, _name in items["disco_items"]["items"][:40]:
                try:
                    info = await disco.get_info(jid=jid, timeout=10)
                except Exception:
                    continue
                if any(category == "conference" for category, _type, _lang, _name in info["disco_info"]["identities"]):
                    found.append(str(jid))
            return found
        return self.call(find())

    def browse(self, service, search="", limit=50):
        """Public group chats on a MUC service (disco#items), filtered by [search]."""
        search = (search or "").strip().lower()
        async def rooms():
            items = await self.client.plugin["xep_0030"].get_items(jid=service, timeout=20)
            output = []
            for jid, _node, name in items["disco_items"]["items"]:
                jid = str(jid)
                if search and search not in (jid + " " + (name or "")).lower():
                    continue
                output.append({"target": jid.lower(), "name": (name or jid)[:120], "members": 0, "topic": ""})
                if len(output) >= limit:
                    break
            return output
        return self.call(rooms())

    def change_nick(self, room, nick):
        """/nick in a group chat: a presence to room/newnick; the server answers with the change."""
        async def apply():
            self.nicks[room] = nick
            self.client.send_presence(pto=room + "/" + nick)
        return self.call(apply())

    def set_subject(self, room, subject):
        async def apply():
            self.client.plugin["xep_0045"].set_subject(room, subject)
        return self.call(apply())

    def answer_subscription(self, jid, accept):
        """Accepting a contact request shares your presence and asks for theirs; declining refuses it."""
        async def apply():
            if accept:
                self.client.send_presence(pto=jid, ptype="subscribed")
                self.client.send_presence(pto=jid, ptype="subscribe")
            else:
                self.client.send_presence(pto=jid, ptype="unsubscribed")
        return self.call(apply())

    def decline_invite(self, room, sender):
        async def apply():
            try:
                self.client.plugin["xep_0045"].decline(room, sender)
            except Exception:
                pass
        return self.call(apply())

    def change_member(self, room, add, member):
        """Invites an address to a group chat, or kicks a nickname from it (kicking needs moderator rights)."""
        async def apply():
            muc = self.client.plugin["xep_0045"]
            if add:
                muc.invite(room, member)
            else:
                await muc.set_role(room, member, "none", reason="Removed from the group")
        return self.call(apply())

    def retract(self, to, message_id, group=False):
        """XEP-0424: takes back a sent message for everyone whose client supports it."""
        async def deliver():
            msg = self.client.make_message(mto=to, mbody="This message was removed.", mtype="groupchat" if group else "chat")
            msg.xml.append(ET.Element("{urn:xmpp:message-retract:1}retract", {"id": message_id}))
            msg.xml.append(ET.Element("{urn:xmpp:fallback:0}fallback", {"for": "urn:xmpp:message-retract:1"}))
            msg.enable("store")
            msg.send()
        return self.call(deliver())

    def send(self, to, text, group=False, reply=None, reply_author=None, react=None, file=None):
        """Sends a message and returns its id. file is (path, name, mime) for HTTP File Upload.

        With OMEMO ready, a one-to-one chat is locked when the other person has an OMEMO device, and a group chat
        when it's members-only and not anonymous and every member has one. Otherwise it goes without OMEMO, and the
        chat gets a notice once. When they have OMEMO devices but none you trust, nothing is sent."""
        async def deliver():
            mtype = "groupchat" if group else "chat"
            recipients = await self.omemo_recipients(to, group) if react is None else None
            locked = False
            if recipients:
                try:
                    locked = await self.omemo.can_lock(recipients)
                except Exception:
                    locked = False
            url = None
            if file is not None:
                path, name, mime = file
                if locked:
                    url = await self.upload_locked(path, name)
                else:
                    url = await self.client.plugin["xep_0363"].upload_file(path, content_type=mime, timeout=120)
            if locked:
                sent = None
                if text:
                    sent = await self.send_locked(to, mtype, text, recipients, reply, reply_author)
                if url:
                    sent = await self.send_locked(to, mtype, url, recipients, None if text else reply, reply_author)
                return sent
            if react is None and recipients is not None and self.omemo_store and omemo_available():
                await self.update_lock(to, plain=True)
                if to not in self.told_plain:
                    self.told_plain.add(to)
                    self.on_event("omemo_notice", to, {"text": self.plain_reason(to, group)})
            body = text
            if url:
                body = url if not text else text + "\n" + url
            if react is not None:
                msg = self.client.make_message(mto=to, mtype=mtype)
                self.client.plugin["xep_0444"].set_reactions(msg, reply, react if isinstance(react, list) else [react])
                msg.enable("store")
            elif reply:
                msg = self.client.plugin["xep_0461"].make_reply(reply_author or to, reply, mto=to, mbody=body, mtype=mtype)
            else:
                msg = self.client.make_message(mto=to, mbody=body, mtype=mtype)
            if url:
                msg["oob"]["url"] = url
            msg["request_receipt"] = not group
            msg.send()
            return msg["id"]
        return self.call(deliver())

    # OMEMO

    async def start_omemo(self):
        """Creates this install's OMEMO device once (publishing its device list entry and bundle), then reuses it
        across reconnects. If it can't start, XMPP keeps working without OMEMO."""
        if self.omemo is not None or not self.omemo_store or not omemo_available():
            return
        self.omemo_started = self.omemo_started or asyncio.Event()
        try:
            omemo = xmpp_omemo.Omemo(str(slixmpp.JID(self.account["jid"]).bare), PepNetwork(self),
                                     directory=self.omemo_store, on_notice=self.omemo_notice)
            await omemo.start()
            self.omemo = omemo
            try:
                self.client.plugin["xep_0163"].add_interest(OMEMO_NS + ".devicelist")
            except Exception:
                pass  # Device lists are still checked before sending.
        except Exception:
            self.omemo = None
        finally:
            self.omemo_started.set()

    def pep_event(self, msg):
        """A device list changed (yours or a contact's)."""
        if self.omemo is None or msg["pubsub_event"]["items"]["node"] != OMEMO_NS + ".devicelist":
            return
        for item in msg["pubsub_event"]["items"]:
            payload = item["payload"] if item.name == "item" else None
            if payload is not None:
                asyncio.ensure_future(self.omemo.device_list(str(msg["from"].bare), payload))

    def omemo_notice(self, kind, jid, data):
        own = str(slixmpp.JID(self.account["jid"]).bare).lower()
        if jid == own:
            return  # Your own devices are listed in each chat's Security section.
        if kind == "new_device":
            text = f"{jid} has a new device. Messages aren't locked for it until you OK it in Security."
        elif kind == "key_changed":
            text = (f"{jid}'s security key changed, for example after reinstalling their app. "
                    "Check it in Security if you didn't expect this.")
        else:
            return
        self.on_event("omemo_notice", jid, {"text": text})
        asyncio.ensure_future(self.update_lock(jid))

    def plain_reason(self, to, group):
        if self.omemo is None:
            return "Not locked with OMEMO: it isn't working on FluidMessages Web right now. Your XMPP server can read this message."
        if group and not self.room_ok.get(to):
            return "Not locked with OMEMO: this group isn't members-only with visible addresses, so OMEMO can't lock it."
        if group:
            return "Not locked with OMEMO: not every member's app uses it, so your XMPP server can read this message."
        return "Not locked with OMEMO: their app doesn't use it, so your XMPP server can read this message."

    async def omemo_recipients(self, to, group):
        """Who a message to [to] is locked for: [] when OMEMO can't lock this chat, None when OMEMO isn't installed."""
        if self.omemo is None:
            return [] if self.omemo_store and omemo_available() else None
        if not group:
            return [to]
        if not self.room_ok.get(to):
            return []
        return self.room_members(to)

    def room_members(self, room):
        own = str(slixmpp.JID(self.account["jid"]).bare).lower()
        members = set(self.real_jids.get(room, {}).values()) | set(self.room_affiliates.get(room, ()))
        return sorted(member for member in members if member and member != own)

    async def check_room(self, room):
        """OMEMO locks a group only when it's members-only and not anonymous (members see each other's addresses),
        as other OMEMO apps require. The member list comes from the room's affiliations where it shares them."""
        try:
            info = await self.client.plugin["xep_0030"].get_info(jid=room, timeout=20)
            features = set(info["disco_info"]["features"])
        except Exception:
            features = set()
        self.room_ok[room] = "muc_membersonly" in features and "muc_nonanonymous" in features
        members = set()
        if self.room_ok[room]:
            for affiliation in ("owner", "admin", "member"):
                try:
                    for jid in await self.client.plugin["xep_0045"].get_affiliation_list(room, affiliation, timeout=20):
                        members.add(str(slixmpp.JID(jid).bare).lower())
                except Exception:
                    continue
        self.room_affiliates[room] = members
        if self.omemo is not None:
            await self.update_lock(room)

    async def update_lock(self, conversation, plain=None, failed=None):
        """Recomputes a chat's lock inputs and reports them as an "omemo_state" event."""
        group = conversation in self.rooms
        previous = self.locks.get(conversation, {})
        state = {"available": self.omemo is not None, "group": group, "group_ok": bool(self.room_ok.get(conversation))}
        try:
            if self.omemo is not None:
                recipients = [conversation] if not group else self.room_members(conversation) if state["group_ok"] else []
                if recipients:
                    state.update(await self.omemo.state(recipients))
        except Exception:
            pass
        state["plain"] = previous.get("plain", False) if plain is None else plain
        state["failed"] = previous.get("failed", 0) if failed is None else failed
        self.locks[conversation] = state
        color, words = omemo_trust.lock(state)
        self.on_event("omemo_state", conversation, {"state": state, "color": color, "words": words})
        return state

    async def upload_locked(self, path, name):
        """XEP-0454: the file is encrypted with AES-256-GCM before upload; the key travels inside the locked message."""
        import io
        import secrets
        from cryptography.hazmat.primitives.ciphers.aead import AESGCM
        with open(path, "rb") as source:
            data = source.read()
        key, iv = secrets.token_bytes(32), secrets.token_bytes(12)
        sealed = AESGCM(key).encrypt(iv, data, None)
        url = await self.client.plugin["xep_0363"].upload_file(name or "file", size=len(sealed),
                                                              content_type="application/octet-stream",
                                                              input_file=io.BytesIO(sealed), timeout=120)
        locked = aesgcm_url(url, key, iv)
        if not locked:
            raise ValueError("The upload service gave a link FluidMessages can't lock")
        return locked

    async def send_locked(self, to, mtype, body, recipients, reply=None, reply_author=None):
        try:
            element, report = await self.omemo.encrypt(recipients, body)
        except xmpp_omemo.OmemoNotTrusted:
            await self.update_lock(to)
            raise ValueError("Not sent: none of their OMEMO devices is trusted yet. Check them in this chat's Security "
                             "section, then send again.")
        msg = self.client.make_message(mto=to, mbody=OMEMO_FALLBACK, mtype=mtype)
        if reply:
            msg["reply"]["id"] = reply
            msg["reply"]["to"] = reply_author or to
        msg.xml.append(element)
        msg.xml.append(ET.Element("{urn:xmpp:eme:0}encryption", {"namespace": OMEMO_NS, "name": "OMEMO"}))
        msg.xml.append(ET.Element("{urn:xmpp:hints}store"))
        msg["request_receipt"] = mtype == "chat"
        msg.send()
        self.remember(msg["id"], body)
        await self.update_lock(to, plain=False, failed=report["failed"])
        return msg["id"]

    def remember(self, message_id, body):
        self.sent_text[message_id] = body
        self.locked_ids.add(message_id)
        while len(self.sent_text) > 300:
            self.sent_text.pop(next(iter(self.sent_text)))
        if len(self.locked_ids) > 2000:
            self.locked_ids = set(list(self.locked_ids)[-1000:])

    def was_locked(self, message_id):
        return message_id in self.locked_ids

    async def received_locked(self, msg, outgoing=False, archived=None):
        """An OMEMO message: opened with this device's session, or replaced by a plain notice when it can't be."""
        try:
            await self._received_locked(msg, outgoing, archived)
        except Exception:
            pass  # A broken message never stops the connection.

    async def _received_locked(self, msg, outgoing, archived):
        own = str(slixmpp.JID(self.account["jid"]).bare).lower()
        element = msg.xml.find("{%s}encrypted" % OMEMO_NS)
        if msg["type"] == "groupchat":
            room, nick = msg["from"].bare, msg["from"].resource
            if room not in self.rooms or not nick:
                return
            mine = nick == self.nicks.get(room, own.split("@", 1)[0])
            sender = own if mine else self.real_jids.get(room, {}).get(nick, "")
            conversation = room
        else:
            sender = str(msg["from"].bare).lower()
            mine = sender == own
            conversation = str((msg["to"] if outgoing or mine else msg["from"]).bare).lower()
        outgoing = outgoing or mine
        if self.omemo is None and self.omemo_started is not None:
            try:
                await asyncio.wait_for(self.omemo_started.wait(), 60)
            except asyncio.TimeoutError:
                pass
        if self.omemo is None:
            result = {"text": None, "error": "unavailable"}
        elif not sender:
            result = {"text": None, "error": "unknown_device"}
        else:
            result = await self.omemo.decrypt(sender, element, archived=archived is not None)
        if result["error"]:
            cached = self.sent_text.get(msg["id"])
            if outgoing and cached is not None:
                text, info = cached, {"trust": "this"}
            elif outgoing and archived is not None:
                return  # Your own earlier message from the archive: FluidMessages already has it.
            else:
                text, info = locked_notice(result["error"], outgoing), {"error": result["error"]}
        else:
            if result["text"] is None:
                return  # A key transport or heartbeat, part of keeping the session going.
            text, info = result["text"], {"trust": result["trust"]}
            if result["trust"] in (omemo_trust.UNCONFIRMED, omemo_trust.DISTRUSTED) and not outgoing \
                    and (sender, result["device"]) not in self.told_plain:
                # OMEMO 0.3: messages from devices you haven't trusted may be read, but say so.
                self.told_plain.add((sender, result["device"]))
                self.on_event("omemo_notice", conversation, {"text": f"Messages from one of {sender}'s devices aren't "
                              "trusted: you haven't OK'd it yet. Check it in Security."})
        self.received(msg, outgoing=outgoing, archived=archived, unlocked=(text, info))
        if not result["error"] and conversation not in self.locks:
            await self.update_lock(conversation)

    def omemo_devices(self, conversation, group=False):
        """For a chat's Security section: the other side's devices and yours, with fingerprints."""
        if self.omemo is None:
            return {"available": False, "reason": "OMEMO isn't working on FluidMessages Web right now." if omemo_available()
                    else "OMEMO isn't installed on FluidMessages Web."}
        async def gather():
            own = str(slixmpp.JID(self.account["jid"]).bare).lower()
            jids = self.room_members(conversation) if group else [conversation]
            people = [{"jid": jid, "devices": await self.omemo.devices(jid)} for jid in jids[:50]]
            state = await self.update_lock(conversation)
            color, words = omemo_trust.lock(state)
            return {"available": True, "people": people, "own": {"jid": own, "devices": await self.omemo.devices(own)},
                    "uri": await self.omemo.own_uri(), "color": color, "words": words}
        return self.call(gather())

    def omemo_verify_link(self, conversation, group, uri):
        """Verifies the devices an xmpp: link names, when their fingerprints match what this chat knows."""
        parsed = omemo_trust.parse_xmpp_uri(uri)
        if self.omemo is None or not parsed:
            raise ValueError("That isn't an xmpp: link with OMEMO fingerprints")
        jid = parsed[0]
        allowed = set(self.room_members(conversation)) if group else {conversation}
        if jid not in allowed | {str(slixmpp.JID(self.account["jid"]).bare).lower()}:
            return {"verified": 0, "mismatched": 0}
        async def apply():
            known = {device["id"]: "".join(device["fingerprint"]) for device in await self.omemo.devices(jid)}
            matches, mismatches = omemo_trust.verify_from_uri(uri, jid, known)
            for device in matches:
                await self.omemo.set_trust(jid, device, omemo_trust.VERIFIED)
            await self.update_lock(conversation)
            return {"verified": len(matches), "mismatched": len(mismatches)}
        return self.call(apply())

    def omemo_trust(self, conversation, jid, device, trust):
        if self.omemo is None:
            raise ValueError("OMEMO isn't working on FluidMessages Web right now")
        async def apply():
            changed = await self.omemo.set_trust(jid, device, trust)
            await self.update_lock(conversation)
            return changed
        return self.call(apply())


def locked_notice(error, outgoing=False):
    """What a locked message that can't be opened shows instead of its text."""
    if error == "not_for_us":
        return ("🔒 Sent from another of your devices, locked for the others only, so FluidMessages Web can't open it."
                if outgoing else "🔒 This message was locked for your other devices only, so FluidMessages Web can't open it.")
    if error == "unavailable":
        return "🔒 This message is locked with OMEMO, which isn't working on FluidMessages Web."
    if error == "key_changed":
        return "🔒 This message couldn't be opened because the sender's security key changed."
    if error == "unknown_device":
        return "🔒 This locked message came from a device FluidMessages Web doesn't know, so it couldn't be opened."
    return "🔒 This locked message couldn't be opened. Ask them to send it again."


class PepNetwork:
    """What xmpp_omemo needs from XMPP: your PEP nodes (open to anyone, as OMEMO requires), others' nodes, and
    sending key transport messages."""

    def __init__(self, xmpp):
        self.xmpp = xmpp

    def options(self):
        form = self.xmpp.client.plugin["xep_0004"].make_form(ftype="submit")
        form.add_field(var="FORM_TYPE", ftype="hidden", value="http://jabber.org/protocol/pubsub#publish-options")
        form.add_field(var="pubsub#access_model", value="open")
        return form

    async def publish(self, node, element):
        pubsub = self.xmpp.client.plugin["xep_0060"]
        try:
            await pubsub.publish(None, node, id="current", payload=element, options=self.options(), timeout=30)
        except IqError:
            # Servers that don't take publish options (or a node set up differently) still take a plain publish.
            await pubsub.publish(None, node, id="current", payload=element, timeout=30)

    async def fetch(self, bare_jid, node):
        try:
            iq = await self.xmpp.client.plugin["xep_0060"].get_items(bare_jid, node, max_items=1, timeout=30)
        except IqError as error:
            if error.condition in ("item-not-found", "feature-not-implemented"):
                return None
            raise
        for item in iq["pubsub"]["items"]:
            payload = item["payload"]
            if payload is not None:
                return payload
        return None

    async def delete(self, node):
        try:
            await self.xmpp.client.plugin["xep_0060"].delete_node(None, node, timeout=30)
        except IqError as error:
            if error.condition != "item-not-found":
                raise

    async def send(self, bare_jid, element):
        msg = self.xmpp.client.make_message(mto=bare_jid, mtype="chat")
        msg.xml.append(element)
        msg.xml.append(ET.Element("{urn:xmpp:hints}store"))
        msg.send()
