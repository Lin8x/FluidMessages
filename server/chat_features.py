"""What daily IRC, Matrix, and XMPP use needs beyond sending and receiving, for the companion's Gateway.

- Slash commands typed in a chat (chat_commands.py): /me, /msg, /join, /part, /nick, /topic, /whois, and the rest.
- Direct chats that open themselves: a bot or person who messages you on IRC (NickServ, ChanServ, a channel's bot)
  gets a chat of its own, labeled automated for services and notices; server notices and errors go to the network's
  server chat.
- Invitations to IRC channels, Matrix rooms, and XMPP group chats, and XMPP contact requests, which wait on a full
  page in the browser and phone until you accept or decline them.
- Event lines for topic and name changes, joins, leaves, kicks, and bans, with a kind so joins and parts can be
  hidden.

Gateway (protocol_gateway.py) mixes this in, so every method here runs as one of its own.
"""
from contextlib import closing
import collections
import hashlib
import json
import re
import threading
import time
import uuid
from urllib.parse import quote

from accounts import CHANNEL, JID, MXID, NICK
import chat_commands
import irc_client
import message_requests

MAX_INVITES = 100
# New direct chats a network can open for you in an hour before the rest wait in its server chat, so a spam wave
# can't fill the inbox.
AUTO_CHATS_PER_HOUR = 20
MXID_IN_TEXT = re.compile(r"(?<![\w@])@[a-z0-9._=\-/+]{1,255}:[A-Za-z0-9.-]{1,255}(?::\d{1,5})?")
EVENT_KINDS = ("join", "part", "quit", "nick", "kick", "ban", "topic", "name", "mode", "invite", "whois", "error", "info")
NAMES = {"irc": "IRC", "matrix": "Matrix", "xmpp": "XMPP"}


def short(text, limit=300):
    text = text if isinstance(text, str) else ""
    return text if len(text) <= limit else text[:limit] + "…"


def edit_text(old, new):
    """The phone's edit line (Extras.kt editText), which replaces the sender's earlier message in the thread."""
    quoted = old.replace("”", '"')
    quoted = quoted if len(quoted) <= 300 else quoted[:300] + "…"
    return "✏️ Edited “" + quoted + "” to: " + new.strip()


class ChatFeatures:
    route_lock = threading.Lock()

    # Event lines

    def mark_event(self, record, kind):
        """An event line: no bubble, never unread, never a notification. [kind] lets joins and parts be hidden."""
        record["event"] = True
        record["read"] = 1
        if kind in EVENT_KINDS:
            record["event_kind"] = kind
        return record

    def add_event(self, route, text, date, kind, key=""):
        record = self.message(route, route["protocol"] + "-" + self.stable(route, "event:" + (key or f"{date}:{text}")),
                              short(text, 600), False, date)
        self.add_incoming(self.mark_event(record, kind))
        return record

    def merge_channel(self, route, **changes):
        """Updates a room's or group chat's topic and members for its details, the way IRC channels report theirs."""
        key = "channel:" + route["address"]
        current = json.loads(self.state(key) or "{}")
        current.setdefault("name", route.get("title") or route["target"])
        current.update(changes)
        self.save_state(key, json.dumps(current))

    # IRC: chats that open themselves

    def irc_server_route(self, account):
        """The network's server chat: notices from the server, errors, and /whois replies. Created on first use."""
        route = next((r for r in self.routes if r["protocol"] == "irc" and r["account"] == account
                      and r["target"] == irc_client.SERVER), None)
        if route is not None:
            return route
        host = (self.accounts.get(account) or {}).get("server", "IRC")
        return self.auto_route(account, irc_client.SERVER, host, automated=True, extra={"server_chat": True})

    def auto_route(self, account, target, title, automated=False, extra=None):
        """Adds a chat the network opened (a direct message from someone new, or the server chat)."""
        address = "irc-" + hashlib.sha256((account + "\n" + target.lower()).encode()).hexdigest()[:16]
        with self.route_lock:
            existing = next((r for r in self.routes if r["address"] == address and r["protocol"] == "irc"), None)
            if existing is not None:
                return existing
            route = {"address": address, "protocol": "irc", "target": target, "via": "irc", "group": False,
                     "title": short(title, 120) or target, "members": [], "account": account, "joined": self.time()}
            if automated:
                route["automated"] = True
            route.update(extra or {})
            with closing(self.connect()) as db, db:
                db.execute("INSERT INTO protocol_routes (address, protocol, payload) VALUES (?, ?, ?)",
                           (address, "irc", json.dumps(route)))
        return route

    def irc_new_chat(self, account, nick, automated):
        """A direct chat with a nick that messaged you, at most AUTO_CHATS_PER_HOUR new ones an hour per network."""
        if not hasattr(self, "auto_chats"):
            self.auto_chats = collections.defaultdict(collections.deque)
        recent = self.auto_chats[account]
        now = time.time()
        while recent and now - recent[0] > 3600:
            recent.popleft()
        if len(recent) >= AUTO_CHATS_PER_HOUR:
            return None
        recent.append(now)
        return self.auto_route(account, nick, nick, automated=automated)

    def irc_fallback(self, conversation, nick, text, tags, account):
        """Where an IRC line with no chat of its own goes: a new direct chat for someone who messaged you, otherwise
        the server chat. Returns (route, text)."""
        account = account or self.default_account("irc")
        if not account:
            return None, text
        direct = conversation != irc_client.SERVER and conversation[:1] not in "#&$" and not tags.get("event")
        if direct and NICK.fullmatch(conversation):
            automated = bool(tags.get("service") or tags.get("notice"))
            route = self.irc_new_chat(account, conversation, automated)
            if route is not None:
                return route, text
        server = self.irc_server_route(account)
        if conversation != irc_client.SERVER:
            text = conversation + ": " + text  # Which channel or nick it was about.
        return server, text

    def irc_dm(self, db, account, nick):
        """The direct chat with [nick] for /msg and /query: the one you have, or a new one."""
        found = next((r for r in self.routes if r["protocol"] == "irc" and r.get("via") == "irc" and r["account"] == account
                      and r["target"].lower() == nick.lower()), None)
        return found or self.auto_route(account, nick, nick, automated=irc_client.is_service(nick))

    # Invitations

    def invites(self):
        return json.loads(self.state("invites") or "[]")

    def add_invite(self, account, protocol, kind, target, sender, name="", direct=False):
        """Records an invitation (or XMPP contact request) once; it waits until accepted or declined. Matrix and XMPP
        ones also show as a message request (message_requests.note_invite), answered through answer_invite."""
        if not isinstance(target, str) or not target or len(target) > 300:
            return None
        with self.route_lock:
            invites = self.invites()
            if any(i["account"] == account and i["target"].lower() == target.lower() and i["kind"] == kind for i in invites):
                return None
            if any(r["account"] == account and r["target"].lower() == target.lower() and not r.get("invite")
                   for r in self.routes) and kind != "contact":
                return None  # Already joined.
            invite = {"id": uuid.uuid4().hex[:16], "account": account, "protocol": protocol, "kind": kind,
                      "target": target, "from": short(sender, 300), "name": short(name, 120), "date": self.time()}
            invites = (invites + [invite])[-MAX_INVITES:]
            self.save_state("invites", json.dumps(invites))
        if protocol in ("matrix", "xmpp") and hasattr(message_requests, "note_invite"):
            try:
                message_requests.note_invite(self, protocol, account, target, sender, name,
                                             direct=direct or kind == "contact" or (protocol == "matrix" and not name))
            except (ValueError, KeyError):
                pass  # The Invitations page still has it.
        return invite

    def invite_route(self, account, target):
        """The chat message_requests.note_invite added for an invitation still waiting, or None."""
        return next((r for r in self.routes if r.get("invite") and r.get("account", "") == account
                     and r["target"].lower() == target.lower()), None)

    def drop_invite(self, account, target, db=None):
        """Takes an invitation off the list. Inside a request, [db] is its open transaction (a second connection
        would wait on its lock)."""
        with self.route_lock:
            invites = [i for i in self.invites() if not (i["account"] == account and i["target"].lower() == target.lower())]
            if db is None:
                self.save_state("invites", json.dumps(invites))
            else:
                db.execute("INSERT OR REPLACE INTO protocol_state VALUES ('invites', ?)", (json.dumps(invites),))
        return invites

    def answer_invite(self, db, data):
        """Accepts or declines an invitation from the Invitations page. Accepting joins and adds the chat."""
        ident, accept = data.get("id"), data.get("accept")
        invite = next((i for i in self.invites() if i["id"] == ident), None) if isinstance(ident, str) else None
        if invite is None or type(accept) is not bool:
            raise ValueError("That invitation is no longer waiting")
        if self.accounts.get(invite["account"]) is None:
            self.drop_invite(invite["account"], invite["target"])
            raise ValueError("That account isn’t on FluidMessages Web anymore")
        result = {}
        pending = self.invite_route(invite["account"], invite["target"])
        with self.using(invite["account"]):
            if invite["kind"] == "contact":
                self.client("xmpp").answer_subscription(invite["target"], accept)
                if accept and pending is not None:
                    result = {"route": {key: value for key, value in pending.items() if key not in ("via", "invite", "inviter")}}
                elif accept and not any(r["account"] == invite["account"] and r["target"] == invite["target"].lower()
                                        for r in self.routes):
                    address = "xmpp-" + hashlib.sha256((invite["account"] + "\n" + invite["target"].lower()).encode()).hexdigest()[:16]
                    result = self.add_route(db, {"address": address, "protocol": "xmpp", "target": invite["target"],
                                                 "title": invite["name"] or invite["target"], "account": invite["account"]})
            elif accept:
                result = self.join(db, {"account": invite["account"], "target": invite["target"], "title": invite["name"]})
            elif invite["protocol"] == "matrix":
                self.matrix_request("rooms/" + quote(invite["target"], safe="") + "/leave", {}, method="POST")
            elif invite["protocol"] == "xmpp":
                try:
                    self.client("xmpp").decline_invite(invite["target"], invite["from"])
                except (ConnectionError, AttributeError):
                    pass
        if pending is not None and accept:
            # The invitation's message request chat becomes the chat (join and add_route find it), keeping what's in it.
            message_requests._update_route(db, pending, invite=None, inviter=None)
            # Its "invited you" line was only there to answer; the joined chat starts with the room's own events.
            db.execute("DELETE FROM protocol_messages WHERE id=?", ("invite-" + pending["address"],))
            if result.get("route", {}).get("address") == pending["address"]:
                result["route"] = {key: value for key, value in result["route"].items() if key not in ("invite", "inviter")}
        elif pending is not None:
            db.execute("DELETE FROM protocol_routes WHERE address=? AND protocol=?", (pending["address"], pending["protocol"]))
        remaining = self.drop_invite(invite["account"], invite["target"], db)
        return {"invites": remaining, **({"route": result["route"]} if result.get("route") else {})}

    # Slash commands

    def run_command(self, db, data):
        """Runs a command typed in a chat. Returns {"notice": what happened, "open": a chat to switch to, "help"}."""
        address, protocol, text = data.get("address"), data.get("protocol"), data.get("text")
        if not isinstance(text, str) or not 1 < len(text) <= 1600:
            raise ValueError("Enter a command")
        route = self.route(address, protocol) if isinstance(address, str) and isinstance(protocol, str) else None
        if route is None:
            raise ValueError("This chat has no configured route for that protocol")
        if protocol not in self.enabled(db):
            raise ValueError("This protocol is turned off on the phone")
        via = route.get("via", protocol)
        parsed = chat_commands.parse(via if via in chat_commands.CHAT_PROTOCOLS else protocol, text)
        if parsed is None:
            raise ValueError("That isn’t a command. Type /help for the list.")
        if parsed["name"] == "help":
            return {"notice": chat_commands.help_text(via), "help": [[chat_commands.COMMANDS[name][0], chat_commands.COMMANDS[name][1]]
                                                                     for name in chat_commands.available(via)]}
        if parsed["name"] == "me":
            return self.command_send(db, route, "/me " + parsed["args"], data)
        with self.using(route["account"]):
            return {"irc": self.command_irc, "matrix": self.command_matrix, "xmpp": self.command_xmpp}[via](db, route, parsed, data)

    def command_send(self, db, route, body, data, opened=None):
        request = data.get("request") if isinstance(data.get("request"), str) else ""
        request = request or uuid.uuid4().hex
        sent = self.enqueue(db, {"address": route["address"], "protocol": route["protocol"], "body": body,
                                 "request": request, "command": True})
        result = {"message": sent["message"], "notice": ""}
        if opened:
            result["open"] = opened
        return result

    @staticmethod
    def opening(route, notice=""):
        return {"open": {"address": route["address"], "protocol": route["protocol"]}, "notice": notice}

    def group_here(self, route, what):
        if not route.get("group"):
            raise ValueError("Use " + what + " in a channel or room")
        return route["target"]

    def command_irc(self, db, route, cmd, data):
        name, args, argv = cmd["name"], cmd["args"], cmd["argv"]
        account, here = route["account"], route["target"]
        channel = here if here[:1] in "#&" else None
        client = self.client("irc")

        def nick_arg(value):
            if not NICK.fullmatch(value):
                raise ValueError("“" + value[:40] + "” isn’t an IRC nickname")
            return value

        def channel_arg(value):
            value = value if value[:1] in "#&" else "#" + value
            if not CHANNEL.fullmatch(value):
                raise ValueError("Enter a channel like #fluidmessages")
            return value

        if name in ("msg", "query"):
            who, text = argv[0], args[len(argv[0]):].strip()
            if who[:1] in "#&":
                target = self.irc_route(who, account)
                if target is None:
                    raise ValueError("Join " + who + " first with /join " + who)
            else:
                target = self.irc_dm(db, account, nick_arg(who))
            opened = {"address": target["address"], "protocol": target["protocol"]}
            if text:
                return self.command_send(db, target, text, data, opened)
            return {"open": opened, "notice": ""}
        if name == "join":
            target = channel_arg(argv[0])
            joined = self.join(db, {"account": account, "target": target})
            if len(argv) > 1:
                client.say("JOIN " + target + " " + argv[1])  # A channel key.
            return self.opening(joined["route"], "" if joined.get("joined") else "Already in " + target)
        if name == "part":
            if argv and argv[0][:1] in "#&":
                target, reason = self.irc_route(argv[0], account), args[len(argv[0]):].strip()
                if target is None:
                    raise ValueError("You aren’t in " + argv[0])
            else:
                target, reason = route, args
            self.group_here(target, "/part")
            client.part(target["target"], reason or "Left in FluidMessages")
            self.leave(db, {"address": target["address"], "protocol": target["protocol"]})
            return {"notice": "Left " + target["target"], "left": target["address"]}
        if name == "nick":
            client.change_nick(nick_arg(argv[0]))
            return {"notice": "Changing your nickname to " + argv[0] + " on this connection. Change it in the account to keep it."}
        if name == "topic":
            target = channel or ""
            if argv and argv[0][:1] in "#&":
                target, args = argv[0], args[len(argv[0]):].strip()
            if not target:
                raise ValueError("Use /topic in a channel")
            if not args:
                current = client.topic(target)
                return {"notice": ("Topic of " + target + ": " + current) if current else target + " has no topic"}
            client.say("TOPIC " + target + " :" + args)
            return {"notice": ""}
        if name == "whois":
            client.whois(nick_arg(argv[0]), here)
            return {"notice": "Looking up " + argv[0] + "…"}
        if name == "away":
            client.say("AWAY :" + args if args else "AWAY")
            return {"notice": "Away: " + args if args else "You’re back"}
        if name == "invite":
            target = channel_arg(argv[1]) if len(argv) > 1 else channel
            if not target:
                raise ValueError("Usage: /invite nick #channel")
            client.say("INVITE " + nick_arg(argv[0]) + " " + target)
            return {"notice": ""}
        if name == "kick":
            target = self.group_here(route, "/kick")
            reason = args[len(argv[0]):].strip() or "Removed from the channel"
            client.say("KICK " + target + " " + nick_arg(argv[0]) + " :" + reason)
            return {"notice": ""}
        if name == "mode":
            line = "MODE " + ((channel or client.nick) + " " + args if argv[0][:1] in "+-" else args)
            client.say(line)
            return {"notice": ""}
        if name == "notice":
            who, text = argv[0], args[len(argv[0]):].strip()
            if not (CHANNEL.fullmatch(who) or NICK.fullmatch(who)):
                raise ValueError("Enter a nickname or #channel")
            client.send(who, text, command="NOTICE")
            return {"notice": "Notice sent to " + who}
        if name == "quote":
            client.raw(args)
            return {"notice": "Sent"}
        raise ValueError("/" + name + " isn’t available on IRC.")

    def command_matrix(self, db, route, cmd, data):
        name, args, argv = cmd["name"], cmd["args"], cmd["argv"]
        account, room = route["account"], route["target"]
        if name in ("msg", "query"):
            who, text = argv[0], args[len(argv[0]):].strip()
            if not MXID.fullmatch(who):
                raise ValueError("Enter a Matrix ID like @maya:example.org")
            address = "mx-" + hashlib.sha256((account + "\n" + who.lower()).encode()).hexdigest()[:16]
            target = next((r for r in self.routes if r["address"] == address and r["protocol"] == "matrix"), None)
            if target is None:
                target = self.add_route(db, {"address": address, "protocol": "matrix", "target": who, "title": who,
                                             "account": account})["route"]
            opened = {"address": target["address"], "protocol": target["protocol"]}
            return self.command_send(db, target, text, data, opened) if text else {"open": opened, "notice": ""}
        if name == "join":
            joined = self.join(db, {"account": account, "target": argv[0]})
            return self.opening(joined["route"], "" if joined.get("joined") else "Already in " + argv[0])
        if name == "part":
            self.group_here(route, "/leave")
            self.leave(db, {"address": route["address"], "protocol": route["protocol"]})
            return {"notice": "Left " + route["title"], "left": route["address"]}
        if name == "invite":
            self.change_member_as(route, True, argv[0])
            return {"notice": "Invited " + argv[0]}
        if name == "topic":
            if not args:
                current = json.loads(self.state("channel:" + route["address"]) or "{}").get("topic", "")
                return {"notice": ("Topic: " + current) if current else "This room has no topic"}
            self.matrix_request("rooms/" + quote(room, safe="") + "/state/m.room.topic", {"topic": args[:1000]}, method="PUT")
            self.merge_channel(route, topic=args[:1000])
            return {"notice": ""}
        if name == "nick":
            me = quote(self.matrix["user_id"], safe="")
            self.matrix_request("profile/" + me + "/displayname", {"displayname": args[:255]}, method="PUT")
            return {"notice": "Your display name is now " + args[:255]}
        raise ValueError("/" + name + " isn’t available on Matrix.")

    def command_xmpp(self, db, route, cmd, data):
        name, args, argv = cmd["name"], cmd["args"], cmd["argv"]
        account, room = route["account"], route["target"]
        if name in ("msg", "query"):
            who, text = argv[0].lower(), args[len(argv[0]):].strip()
            if not JID.fullmatch(who):
                raise ValueError("Enter an XMPP address like maya@example.org")
            target = next((r for r in self.routes if r["account"] == account and r.get("via") == "xmpp" and r["target"] == who), None)
            if target is None:
                address = "xmpp-" + hashlib.sha256((account + "\n" + who).encode()).hexdigest()[:16]
                target = self.add_route(db, {"address": address, "protocol": "xmpp", "target": who, "title": who,
                                             "account": account})["route"]
            opened = {"address": target["address"], "protocol": target["protocol"]}
            return self.command_send(db, target, text, data, opened) if text else {"open": opened, "notice": ""}
        if name == "join":
            joined = self.join(db, {"account": account, "target": argv[0]})
            return self.opening(joined["route"], "" if joined.get("joined") else "Already in " + argv[0])
        if name == "part":
            self.group_here(route, "/part")
            self.leave(db, {"address": route["address"], "protocol": route["protocol"]})
            return {"notice": "Left " + route["title"], "left": route["address"]}
        if name == "nick":
            self.group_here(route, "/nick")
            if not re.fullmatch(r"[^\s/@]{1,64}", argv[0]):
                raise ValueError("Enter a nickname without spaces")
            self.client("xmpp").change_nick(room, argv[0])
            return {"notice": "Your nickname here is now " + argv[0]}
        if name == "topic":
            self.group_here(route, "/topic")
            if not args:
                current = json.loads(self.state("channel:" + route["address"]) or "{}").get("topic", "")
                return {"notice": ("Topic: " + current) if current else "This group chat has no topic"}
            self.client("xmpp").set_subject(room, args[:1000])
            return {"notice": ""}
        if name == "invite":
            self.change_member_as(route, True, argv[0])
            return {"notice": "Invited " + argv[0]}
        raise ValueError("/" + name + " isn’t available on XMPP.")

    def check_not_command(self, route, body, data):
        """Messages never carry a command as text: older apps that send "/join #x" get told why, and SMS and email
        refuse /me and the rest. "//" (sent with literal) and /me in chats are fine."""
        if data.get("literal") is True or not chat_commands.looks_like_command(body):
            return
        via = route.get("via", route["protocol"])
        family = via if via in chat_commands.CHAT_PROTOCOLS else route["protocol"]
        parsed = chat_commands.parse(family, body)  # Raises CommandError (a ValueError) for unknown commands.
        if parsed is not None and not (parsed["name"] == "me" and parsed["args"]):
            raise ValueError("/" + parsed["name"] + " is a command. Type it in the message box of FluidMessages Web or the "
                             "phone, or start with // to send it as text.")

    # Matrix: invitations, state changes, edits, and mentions

    def matrix_invited(self, account, room, data):
        """An invitation in a sync's rooms.invite: who invited you and the room's name from its stripped state."""
        sender, name, direct = "", "", False
        me = (self.accounts.get(account) or {}).get("user_id", "")
        for event in (data.get("invite_state") or {}).get("events", []):
            if not isinstance(event, dict):
                continue
            content = event.get("content") if isinstance(event.get("content"), dict) else {}
            if event.get("type") == "m.room.name" and isinstance(content.get("name"), str):
                name = content["name"]
            elif event.get("type") == "m.room.canonical_alias" and isinstance(content.get("alias"), str) and not name:
                name = content["alias"]
            elif event.get("type") == "m.room.member" and event.get("state_key") == me and content.get("membership") == "invite":
                sender = event.get("sender", "")
                direct = direct or content.get("is_direct") is True
                if content.get("is_direct") and not name:
                    name = sender
        self.add_invite(account, "matrix", "room", room, sender, name, direct=direct)

    def matrix_state(self, route, event, content, date):
        """Topic and name changes, and joins, leaves, kicks, bans, and invites, as event lines."""
        kind, sender = event.get("type"), event.get("sender", "")
        who = sender
        if kind == "m.room.topic":
            topic = short(content.get("topic"), 1000)
            self.merge_channel(route, topic=topic)
            return self.add_event(route, f"{who} changed the topic to “{topic}”" if topic else f"{who} removed the topic",
                                  date, "topic", event["event_id"])
        if kind == "m.room.name":
            name = short(content.get("name"), 120)
            return self.add_event(route, f"{who} renamed the room to “{name}”" if name else f"{who} removed the room name",
                                  date, "name", event["event_id"])
        if kind != "m.room.member" or not isinstance(event.get("state_key"), str):
            return None
        target = event["state_key"]
        unsigned = event.get("unsigned") if isinstance(event.get("unsigned"), dict) else {}
        previous = unsigned.get("prev_content") if isinstance(unsigned.get("prev_content"), dict) else {}
        now, before = content.get("membership"), previous.get("membership")
        name = short(content.get("displayname") if isinstance(content.get("displayname"), str) else "", 80) or target
        old = short(previous.get("displayname") if isinstance(previous.get("displayname"), str) else "", 80) or target
        reason = f" ({short(content['reason'], 200)})" if isinstance(content.get("reason"), str) and content["reason"] else ""
        if now == "join" and before == "join":
            if name == old:
                return None  # An avatar change.
            text, kind = f"{old} is now {name}", "nick"
        elif now == "join":
            text, kind = f"{name} joined", "join"
        elif now == "leave" and target == sender:
            text, kind = (f"{old} declined the invitation" if before == "invite" else f"{old} left{reason}"), "part"
        elif now == "leave":
            text, kind = (f"{sender} withdrew {old}’s invitation" if before == "invite" else f"{sender} removed {old}{reason}"), "kick"
        elif now == "ban":
            text, kind = f"{sender} banned {old}{reason}", "ban"
        elif now == "invite":
            text, kind = f"{sender} invited {name}", "invite"
        else:
            return None
        return self.add_event(route, text, date, kind, event["event_id"])

    def matrix_mentioned(self, content, body):
        me = self.matrix.get("user_id", "")
        mentions = content.get("m.mentions") if isinstance(content.get("m.mentions"), dict) else {}
        if me and isinstance(mentions.get("user_ids"), list) and me in mentions["user_ids"]:
            return True
        local = me[1:].split(":", 1)[0] if me.startswith("@") else ""
        return bool(me and (me in body or local and re.search(r"(?<![\w.-])" + re.escape(local) + r"(?![\w-])", body, re.IGNORECASE)))

    @staticmethod
    def matrix_outgoing(content, text):
        """/me becomes an emote, and Matrix IDs in the text become mentions, so the people named are notified."""
        if content.get("msgtype") == "m.text" and text.startswith("/me "):
            content["msgtype"] = "m.emote"
            content["body"] = text[4:]
        named = sorted(set(MXID_IN_TEXT.findall(content.get("body", ""))))[:20]
        if named:
            content["m.mentions"] = {"user_ids": named}
        return content

    # XMPP: group chat subjects and presence, invitations, and contact requests

    def accept_xmpp_event(self, kind, conversation, data, account=None):
        account = account or self.default_account("xmpp")
        if kind == "invite":
            self.add_invite(account, "xmpp", "group", conversation, data.get("from", ""), data.get("name", ""))
            return
        if kind == "subscribe":
            self.add_invite(account, "xmpp", "contact", conversation, conversation, data.get("name", ""))
            return
        route = self.xmpp_route(conversation, account)
        if route is None:
            return
        date = data.get("date") if type(data.get("date")) is int else self.time()
        nick = short(data.get("nick"), 80)
        if kind == "subject":
            subject = short(data.get("subject"), 1000)
            self.merge_channel(route, topic=subject)
            if data.get("live"):
                self.add_event(route, f"{nick} changed the topic to “{subject}”" if subject else f"{nick} removed the topic",
                               date, "topic")
        elif kind == "members":
            self.merge_channel(route, members=sorted(data.get("members", []))[:500])
        elif kind in ("join", "part", "kick", "ban", "nick"):
            text = {"join": f"{nick} joined", "part": f"{nick} left", "kick": f"{nick} was removed",
                    "ban": f"{nick} was banned", "nick": f"{nick} is now {short(data.get('new'), 80)}"}[kind]
            self.add_event(route, text, date, kind)

    # IRC connections: Keep joined, rejoining on open, the connection's state, and Reconnect

    def keep_map(self):
        return json.loads(self.state("keep_joined") or "{}")

    def keeps(self, account, channel):
        """Keep joined (the default): rejoined as soon as a connection opens. Off: rejoined when you open the chat."""
        route = self.irc_route(channel, account)
        return route is None or self.keep_map().get(route["address"], True) is not False

    def set_keep(self, db, data):
        route = self.route(data.get("address"), data.get("protocol")) if isinstance(data.get("address"), str) else None
        if route is None or route.get("via") != "irc" or not route.get("group") or type(data.get("keep")) is not bool:
            raise ValueError("Keep joined is for IRC channels")
        keep = self.keep_map()
        if data["keep"]:
            keep.pop(route["address"], None)
        else:
            keep[route["address"]] = False
        db.execute("INSERT OR REPLACE INTO protocol_state VALUES ('keep_joined', ?)", (json.dumps(keep),))
        return {"keep_joined": data["keep"]}

    def open_chat(self, data):
        """Opening a channel that isn't kept joined joins it again."""
        route = self.route(data.get("address"), data.get("protocol")) if isinstance(data.get("address"), str) else None
        if route is None or route.get("via") != "irc" or not route.get("group"):
            return {"joined": False}
        with self.using(route["account"]):
            try:
                self.client("irc").ensure_joined(route["target"])
            except (ConnectionError, OSError, ValueError):
                return {"joined": False}
        return {"joined": True}

    def reconnect_map(self):
        return json.loads(self.state("auto_reconnect") or "{}")

    def reconnects(self, account):
        """Reconnect automatically (the default): connects at start and after a drop. Off: only after Reconnect."""
        try:
            return self.reconnect_map().get(account, True) is not False
        except Exception:
            return True  # The setting can't be read right now: keep the default.

    def set_auto_reconnect(self, db, data):
        ident = data.get("account") if isinstance(data.get("account"), str) else None
        if ident is None or type(data.get("on")) is not bool or not any(
                entry["id"] == ident and entry["protocol"] in ("irc", "matrix", "xmpp") for entry in self.accounts.all()):
            raise ValueError("Reconnect automatically is for IRC networks and Matrix and XMPP accounts")
        auto = self.reconnect_map()
        if data["on"]:
            auto.pop(ident, None)
        else:
            auto[ident] = False
        db.execute("INSERT OR REPLACE INTO protocol_state VALUES ('auto_reconnect', ?)", (json.dumps(auto),))
        entry = self.clients.get(ident)
        # Turned back on: connect now rather than waiting for a Reconnect tap. Off leaves a working connection alone.
        if data["on"] and entry is not None and hasattr(entry[1], "reconnect_now"):
            entry[1].reconnect_now()
        return {"reconnect": data["on"]}

    def connections(self):
        """Each IRC network's, XMPP account's, and Matrix account's connection: connected, connecting, reconnecting
        (retry_at), paused by the hourly limit, or disconnected (with "manual" while Reconnect automatically is off)."""
        return {ident: dict(entry[1].status) for ident, entry in list(self.clients.items())
                if isinstance(getattr(entry[1], "status", None), dict)}

    def reconnect(self, data):
        ident = data.get("account") if isinstance(data.get("account"), str) else None
        entry = self.clients.get(ident)
        if entry is None and ident:
            # An account added a moment ago: start its connection now rather than at the next check.
            self.refresh_clients()
            entry = self.clients.get(ident)
            if entry is not None and hasattr(entry[1], "reconnect_now"):
                return {"ok": True}
        if entry is None or not hasattr(entry[1], "reconnect_now"):
            raise ValueError("IRC is turned off, so this network can’t connect. Turn it on in Settings → Chat services.")
        entry[1].reconnect_now()
        return {"ok": True}
