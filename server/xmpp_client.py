"""Native XMPP for the companion using slixmpp.

One XMPP session stays open on the companion (with stream management, so brief network drops resume without
losing messages). Supports one-to-one chats and group chats (MUC), message carbons, delivery receipts, replies
(XEP-0461), reactions (XEP-0444), and files through HTTP File Upload (XEP-0363) with an out-of-band link
(XEP-0066), the way Conversations and other clients send them. OMEMO is not supported, so the phone labels
XMPP as encrypted in transit only.

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
except ImportError:  # The companion still runs without XMPP; adding an XMPP account then reports this.
    slixmpp = None


def available():
    return slixmpp is not None


class XmppClient:
    def __init__(self, account, on_message, rooms, on_event=None, auto=None):
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
        # What the apps show: connected, connecting, reconnecting, or disconnected ("manual" while auto is off).
        self.status = {"state": "connecting", "network": account.get("jid", "").partition("@")[2], "retry_at": 0, "error": "",
                       "manual": False}

    def run(self):
        self.loop = asyncio.new_event_loop()
        asyncio.set_event_loop(self.loop)
        client = slixmpp.ClientXMPP(self.account["jid"], self.account["password"])
        for plugin in ("xep_0030", "xep_0199", "xep_0198", "xep_0280", "xep_0184", "xep_0045", "xep_0359",
                       "xep_0461", "xep_0444", "xep_0363", "xep_0066", "xep_0203", "xep_0249", "xep_0313"):
            try:
                client.register_plugin(plugin)
            except Exception:
                if plugin in ("xep_0249", "xep_0313"):
                    continue  # Optional: direct invitations and the message archive.
                raise
        # Contact requests wait for your answer on the Invitations page instead of being accepted on their own.
        client.roster.auto_authorize = False
        client.roster.auto_subscribe = False
        client.plugin["xep_0199"].enable_keepalive(interval=300, timeout=30)
        client.add_event_handler("session_start", self.started)
        client.add_event_handler("message", self.received)
        client.add_event_handler("groupchat_message", self.received)
        client.add_event_handler("carbon_sent", lambda msg: self.received(msg["carbon_sent"], outgoing=True))
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
        if self.last_seen:
            asyncio.ensure_future(self.catch_up(self.last_seen))

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
        if 110 in codes:
            self.joined.add(room)
            self.nicks[room] = nick
            if gone:
                self.joined.discard(room)
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

    def disconnected(self, _event):
        self.ready.clear()
        self.last_seen = self.last_seen or time.time()
        if not self.stopped and not self.auto():
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

    def received(self, msg, outgoing=False, archived=None):
        if msg["type"] not in ("chat", "normal", "groupchat"):
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
        """Sends a message and returns its id. file is (path, name, mime) for HTTP File Upload."""
        async def deliver():
            mtype = "groupchat" if group else "chat"
            body = text
            url = None
            if file is not None:
                path, name, mime = file
                url = await self.client.plugin["xep_0363"].upload_file(path, content_type=mime, timeout=120)
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
