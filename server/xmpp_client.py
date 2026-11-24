"""Native XMPP for the companion using slixmpp.

One XMPP session stays open on the companion (with stream management, so brief network drops resume without
losing messages). Supports one-to-one chats and group chats (MUC), message carbons, delivery receipts, replies
(XEP-0461), reactions (XEP-0444), and files through HTTP File Upload (XEP-0363) with an out-of-band link
(XEP-0066), the way Conversations and other clients send them. OMEMO is not supported, so the phone labels
XMPP as encrypted in transit only.
"""
import asyncio
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
    def __init__(self, account, on_message, rooms):
        if slixmpp is None:
            raise RuntimeError("Install slixmpp on the companion to use XMPP")
        self.account = account
        self.on_message = on_message  # (conversation_jid, author, text, ids, date_ms, outgoing, extra)
        self.rooms = set(rooms)
        self.loop = None
        self.client = None
        self.ready = threading.Event()
        self.stopped = False

    def run(self):
        self.loop = asyncio.new_event_loop()
        asyncio.set_event_loop(self.loop)
        client = slixmpp.ClientXMPP(self.account["jid"], self.account["password"])
        for plugin in ("xep_0030", "xep_0199", "xep_0198", "xep_0280", "xep_0184", "xep_0045", "xep_0359",
                       "xep_0461", "xep_0444", "xep_0363", "xep_0066", "xep_0203"):
            client.register_plugin(plugin)
        client.plugin["xep_0199"].enable_keepalive(interval=300, timeout=30)
        client.add_event_handler("session_start", self.started)
        client.add_event_handler("message", self.received)
        client.add_event_handler("groupchat_message", self.received)
        client.add_event_handler("carbon_sent", lambda msg: self.received(msg["carbon_sent"], outgoing=True))
        client.add_event_handler("disconnected", self.disconnected)
        self.client = client
        address = (self.account["host"], int(self.account.get("port", 5222))) if self.account.get("host") else None
        client.connect(*(address or ()))
        self.loop.run_forever()

    async def started(self, _event):
        self.client.send_presence()
        await self.client.get_roster()
        await self.client.plugin["xep_0280"].enable()
        nick = self.account["jid"].split("@", 1)[0]
        for room in self.rooms:
            self.client.plugin["xep_0045"].join_muc(room, nick)
        self.ready.set()

    def disconnected(self, _event):
        self.ready.clear()
        if not self.stopped:
            # Reconnect with a pause, so an unreachable server doesn't spin.
            self.loop.call_later(30, self.client.connect)

    def stop(self):
        self.stopped = True
        if self.loop and self.client:
            self.loop.call_soon_threadsafe(self.client.disconnect)

    def message_id(self, msg):
        if msg["type"] == "groupchat" and msg["stanza_id"]["id"]:
            return msg["stanza_id"]["id"]
        return msg["id"]

    def received(self, msg, outgoing=False):
        if msg["type"] not in ("chat", "normal", "groupchat"):
            return
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
        stamp = msg["delay"]["stamp"] if msg["delay"]["stamp"] else None
        date = int(stamp.timestamp() * 1000) if stamp else int(time.time() * 1000)
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
