"""Native XMPP for the companion using slixmpp.

One XMPP session stays open on the companion (with stream management, so brief network drops resume without
losing messages). Supports one-to-one chats and group chats (MUC), message carbons, delivery receipts, replies
(XEP-0461), reactions (XEP-0444), and files through HTTP File Upload (XEP-0363) with an out-of-band link
(XEP-0066), the way Conversations and other clients send them. OMEMO is not supported, so the phone labels
XMPP as encrypted in transit only.
"""
import asyncio
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
        if reactions["id"]:
            values = list(reactions.get_values())
            if not values:
                return  # An empty set removes a reaction; FluidMessages keeps the last one shown.
            extra = {"reaction": values[-1], "reaction_to": reactions["id"]}
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
                self.client.plugin["xep_0444"].set_reactions(msg, reply, [react])
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
