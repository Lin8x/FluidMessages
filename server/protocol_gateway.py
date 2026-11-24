"""Opt-in Internet messaging for configured correspondents and Matrix rooms.

Each service can have several accounts (accounts.py). A link (route) names the account it goes through; links and
messages from before multiple accounts name none and belong to the service's first account. The same contact linked
through a second account is a chat of its own: its address gets the account's short tag ("maya~1a2b3c4d"), so the
first account's chats keep their addresses and every app that keys chats by address keeps them apart.
"""
from contextlib import closing, contextmanager
from email import policy
from email.message import EmailMessage
from email.parser import BytesParser
from email.utils import make_msgid, parsedate_to_datetime, parseaddr
import collections
import hashlib
import imaplib
import json
import os
from pathlib import Path
import re
import smtplib
import ssl
import threading
import time
import uuid
from urllib.error import HTTPError
from urllib.parse import quote, urlencode, urlsplit
from urllib.request import HTTPRedirectHandler, HTTPSHandler, Request, build_opener

from accounts import AccountStore, CHANNEL, IMAP_PORT, JID, MXID, NICK, SMTP_PORT, identity as account_name, imap_starttls, smtp_starttls, tag as account_tag
from chat_features import ChatFeatures, edit_text
import irc_client
import matrix_e2ee
import message_requests
import xmpp_client

INTERNET = ("matrix", "xmpp", "email", "irc")
MATRIX_ROUTES = ("matrix", "xmpp", "irc")
MAX_MAIL = 128 * 1024
MAX_FILE = 25 * 1024 * 1024
# Whole messages up to this size are fetched, so a 25 MB attachment still arrives after base64 encoding.
MAX_MAIL_WITH_FILES = 35 * 1024 * 1024
KINDS = ("photo", "video", "audio", "voice", "file", "contact")
MIME = re.compile(r"[a-z0-9][a-z0-9!#$&^_.+-]{0,62}/[a-z0-9][a-z0-9!#$&^_.+-]{0,62}")
MAX_MATRIX = 5 * 1024 * 1024
# Matrix VoIP (v1) call signaling. The browser or phone runs the WebRTC call; the companion only relays these.
CALL_TYPES = ("m.call.invite", "m.call.candidates", "m.call.answer", "m.call.hangup", "m.call.select_answer",
              "m.call.reject", "m.call.negotiate")
# Lets other FluidMessages apps remove a message on the same timer; other Matrix clients ignore it.
DISAPPEAR = "org.fluidmessages.disappear_after"
EMAIL = re.compile(r"[^@\s<>\x00-\x1f]{1,64}@[A-Za-z0-9](?:[A-Za-z0-9.-]{0,251}[A-Za-z0-9])?")
NUMBER = re.compile(r"\+?[0-9][0-9(). -]{2,38}")
ROOM = re.compile(r"![^\s/\x00-\x1f]{1,250}")
ID = re.compile(r"[a-zA-Z0-9_-]{1,90}")
TAG = re.compile(r"[0-9a-f]{8}")


def valid_address(address):
    """A phone conversation's address, optionally with an account tag for a chat through another account."""
    if not isinstance(address, str):
        return False
    base, mark, suffix = address.partition("~")
    return bool(ID.fullmatch(base) or NUMBER.fullmatch(base)) and (not mark or bool(TAG.fullmatch(suffix)))


IRC_LOWER = str.maketrans("[]\\~", "{}|^")


def target_key(via, target):
    """An address compared the way its service does, so one person, group, channel, or room is one chat per account.

    Email and XMPP addresses and Matrix user IDs and aliases ignore case; a Matrix room ID keeps its own case but not its
    server's; IRC channels and nicks fold case by RFC 1459 ([]\\~ are the capitals of {}|^)."""
    if not isinstance(target, str):
        return ""
    target = target.strip()
    if via == "irc":
        return target.lower().translate(IRC_LOWER)
    if via == "matrix" and target.startswith("!"):
        room, _, server = target.partition(":")
        return room + ":" + server.lower()
    return target.lower()


def via_for(format, target):
    """The service that carries a link: a Matrix room for XMPP or IRC is a bridge, so it goes through Matrix."""
    return "matrix" if format == "matrix" or format in ("xmpp", "irc") and isinstance(target, str) and target.startswith("!") \
        else format


class Poller:
    """A loop for services without a long-lived connection (Email, and Matrix without encryption), one per account."""

    def __init__(self, operation, interval):
        self.operation = operation
        self.interval = interval
        self.stopped = threading.Event()

    def run(self):
        while not self.stopped.is_set():
            try:
                self.operation()
            except Exception:
                pass
            self.stopped.wait(self.interval() if callable(self.interval) else self.interval)

    def stop(self):
        self.stopped.set()
# Mirrors SmsReaction on Android so folded reactions read the same on every protocol.
REACTION_VERBS = {"👍": "Liked", "❤️": "Loved", "👎": "Disliked", "😆": "Laughed at", "‼️": "Emphasized", "❓": "Questioned"}


def excerpt(text, limit):
    return text if len(text) <= limit else text[:limit] + "…"


def reaction_text(emoji, quote):
    verb = REACTION_VERBS.get(emoji)
    quoted = "“" + excerpt(quote, 700) + "”"
    return f"{verb} {quoted}" if verb else f"Reacted {emoji} to {quoted}"


REACTION_NOUNS = {"👍": "a like", "❤️": "a heart", "👎": "a dislike", "😆": "a laugh", "‼️": "an exclamation",
                  "❓": "a question mark"}


def removed_text(emoji, quote):
    """Worded the way iPhones send it, matching reactionRemovedSms on Android."""
    return f"Removed {REACTION_NOUNS.get(emoji, emoji)} from “{excerpt(quote, 700)}”"


def changed_text(previous, emoji, quote):
    return f"Changed reaction from {previous} to {emoji} on “{excerpt(quote, 700)}”"


def revises(relation):
    """A removal or a change of an earlier reaction."""
    return relation.get("kind") == "reaction" and (relation.get("removed") or relation.get("previous"))


ATTRIBUTION_START = re.compile(r"\s*(On|Am|Le|El|Il|Em|Op)\b.*")
ATTRIBUTION_END = re.compile(r"\s*(On|Am|Le|El|Il|Em|Op)\b.{0,400}?\b(wrote|schrieb|a écrit|escribió|ha scritto|escreveu|schreef)"
                             r"\b.{0,200}:\s*", re.S)
ORIGINAL_MESSAGE = re.compile(r"\s*-{2,}\s*Original Message\s*-{2,}\s*", re.I)
HEADER_FROM = re.compile(r"\s*\*?From:\*?\s.+")
HEADER_NEXT = re.compile(r"\s*\*?(Sent|Date|To|Subject):\*?\s.*")


def strip_quoted_email(text):
    """Mirrors stripQuotedEmail on Android: drops the quoted earlier conversation under an email reply."""
    lines = text.replace("\r\n", "\n").split("\n")
    rule = lambda line: not line.strip() or set(line.strip()) <= {"_", "-"}
    cut, skip = len(lines), 0
    for i, line in enumerate(lines):
        size = next((extra + 1 for extra in range(4) if ATTRIBUTION_START.fullmatch(line) and i + extra < len(lines)
                     and ATTRIBUTION_END.fullmatch(" ".join(lines[i:i + extra + 1]))), 0)
        attribution = size > 0
        outlook = ORIGINAL_MESSAGE.fullmatch(line) or (
            HEADER_FROM.fullmatch(line) and any(i + n < len(lines) and HEADER_NEXT.fullmatch(lines[i + n]) for n in range(1, 5))
            and (i == 0 or rule(lines[i - 1])))
        trailing = line.lstrip().startswith(">") and all(not rest.strip() or rest.lstrip().startswith(">") for rest in lines[i:])
        if attribution or outlook or trailing:
            cut, skip = i, size
            break
    kept = lines[:cut]
    while kept and rule(kept[-1]):
        kept.pop()
    kept = "\n".join(kept).strip()
    if kept:
        return kept
    # Everything sat under the quote (an inline reply): drop the "wrote:" line and the quoted lines only.
    rest = lines[:cut] + lines[cut + skip:]
    inline = "\n".join(line for line in rest if not line.lstrip().startswith(">")).strip()
    return inline or text.strip()


def reply_text(quote, body):
    return "Reply to “" + excerpt(quote, 240) + "”\n\n" + body


def is_emoji(value):
    return isinstance(value, str) and 0 < len(value) <= 64 and not re.search(r"[\sA-Za-z0-9<>]", value)

SCHEMA = """
CREATE TABLE IF NOT EXISTS protocol_messages (id TEXT PRIMARY KEY, date INTEGER NOT NULL, payload TEXT NOT NULL);
CREATE INDEX IF NOT EXISTS protocol_message_date ON protocol_messages (date DESC);
CREATE TABLE IF NOT EXISTS protocol_jobs (id TEXT PRIMARY KEY, format TEXT NOT NULL, target TEXT NOT NULL,
    date INTEGER NOT NULL, state TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS protocol_state (key TEXT PRIMARY KEY, value TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS protocol_routes (address TEXT NOT NULL, protocol TEXT NOT NULL, payload TEXT NOT NULL,
    PRIMARY KEY (address, protocol));
CREATE TABLE IF NOT EXISTS protocol_uploads (id TEXT PRIMARY KEY, name TEXT NOT NULL, mime TEXT NOT NULL,
    size INTEGER NOT NULL, date INTEGER NOT NULL);
"""


def safe_name(name):
    name = re.sub(r"[\\/:*?\"<>|\x00-\x1f]", "_", name if isinstance(name, str) else "").strip()
    return (name or "attachment")[-120:]


def kind_for(mime):
    if mime.startswith("image/"):
        return "photo"
    if mime.startswith("video/"):
        return "video"
    if mime.startswith("audio/"):
        return "audio"
    return "contact" if mime in ("text/vcard", "text/x-vcard") else "file"



class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def bounded(value, limit):
    return value[:limit] if isinstance(value, str) else ""


def network_error(error):
    """A short reason for a chat server's refusal, such as Matrix's own error text."""
    if isinstance(error, HTTPError):
        try:
            reason = json.loads(error.read(4096) or b"{}").get("error")
        except (ValueError, OSError, AttributeError):
            reason = None
        return "The server said: " + bounded(reason, 200) if isinstance(reason, str) and reason else \
            "The server refused (" + str(error.code) + ")"
    if isinstance(error, ConnectionError):
        return str(error)[:200]
    return "Couldn’t reach the server. Check its address and try again."


def configured():
    path = os.environ.get("FLUID_PROTOCOL_CONFIG")
    if not path:
        return {}
    file = Path(path)
    if file.stat().st_size > 128 * 1024:
        raise ValueError("Protocol configuration is too large")
    result = json.loads(file.read_text())
    if not isinstance(result, dict):
        raise ValueError("Expected a protocol configuration object")
    return result


class Gateway(ChatFeatures):
    def __init__(self, connect, config, files=None, accounts=None):
        self.connect = connect
        # Attachments live beside the database, one file per message id; uploads wait in a staging folder.
        self.files = Path(files or os.environ.get("FLUID_FILES", "data/protocol-files"))
        (self.files / "uploads").mkdir(parents=True, exist_ok=True)
        self.accounts = accounts or AccountStore(self.files / "accounts.json", legacy=config, environ={})
        self.tls = ssl.create_default_context()
        self.http = build_opener(HTTPSHandler(context=self.tls), NoRedirect())
        self.wake = threading.Event()
        self.clients = {}
        self.calls = collections.deque(maxlen=400)
        self.call_number = 0
        self.call_lock = threading.Lock()
        # The account a sender, poller, or callback is working for; None means each service's first account.
        self.local = threading.local()
        self.legacy_routes = self.validate_routes(config.get("routes") or [])

    # Accounts

    def first(self, protocol):
        """The service's first account, noted once. It owns the chats, state, and message ids from before multiple
        accounts, so those keep working even when another account is added ahead of it (from Docker Compose)."""
        known = json.loads(self.state("accounts:first") or "{}")
        if protocol not in known:
            primary = self.accounts.primary(protocol)
            if not primary:
                return ""
            known[protocol] = primary
            self.save_state("accounts:first", json.dumps(known))
        return known[protocol]

    def owner(self, route, everyone=None):
        """The id of the account a link goes through: its own, or for older links the service's first account."""
        if route.get("account"):
            return route["account"]
        via = route.get("via") or via_for(route.get("protocol"), route.get("target"))
        everyone = self.accounts.all() if everyone is None else everyone
        ids = [entry["id"] for entry in everyone if entry["protocol"] == via]
        if not ids:
            return ""
        first = self.first(via)
        return first if first in ids else ids[0]

    def default_account(self, protocol):
        return self.owner({"protocol": protocol, "via": protocol})

    def scope(self, account):
        """"" for a service's first account, so its thread keys, state, and message ids stay as they were."""
        if not account:
            return ""
        return "" if account == self.first(account.split(":", 1)[0]) else account_tag(account)

    def stable(self, route, text):
        scope = self.scope(route.get("account"))
        return hashlib.sha256(((scope + ":") if scope else "").encode() + text.encode()).hexdigest()

    @contextmanager
    def using(self, account):
        """Runs Matrix, Email, IRC, and XMPP calls as [account]; None keeps each service's first account."""
        previous = getattr(self.local, "account", None)
        self.local.account = account or None
        try:
            yield
        finally:
            self.local.account = previous

    def current_account(self, protocol):
        chosen = getattr(self.local, "account", None)
        return chosen if chosen and chosen.startswith(protocol + ":") else self.default_account(protocol)

    def values(self, protocol):
        return self.accounts.get(self.current_account(protocol)) or {}

    def recent_account(self, db, via):
        """For a new chat: the account of the service that sent or received most recently, else the newest one."""
        choices = [entry["id"] for entry in self.accounts.of(via)]
        if not choices:
            return ""
        for (payload,) in db.execute("SELECT payload FROM protocol_messages ORDER BY date DESC, rowid DESC LIMIT 500"):
            account = json.loads(payload).get("account")
            if account in choices:
                return account
        return choices[-1]

    @property
    def email(self):
        return self.values("email")

    @property
    def matrix(self):
        return self.values("matrix")

    @property
    def routes(self):
        """Links from the old config file plus the ones added from the phone, stored in the database. Each names
        the account it goes through ("" when its service has none)."""
        with closing(self.connect()) as db:
            rows = db.execute("SELECT payload FROM protocol_routes ORDER BY rowid").fetchall()
        routes = [dict(route) for route in self.legacy_routes] + [json.loads(row[0]) for row in rows]
        everyone = self.accounts.all()
        for route in routes:
            route["account"] = self.owner(route, everyone)
        # Channels listed on each IRC account appear in the inbox without a separate link.
        for entry in everyone:
            if entry["protocol"] != "irc":
                continue
            linked = {target_key("irc", r["target"]) for r in routes if r["protocol"] == "irc" and r["account"] == entry["id"]}
            scope = self.scope(entry["id"])
            for channel in entry["values"].get("channels", []):
                if target_key("irc", channel) not in linked:
                    linked.add(target_key("irc", channel))
                    key = ((scope + ":") if scope else "") + channel.lower()
                    routes.append({"address": "irc-" + hashlib.sha256(key.encode()).hexdigest()[:16],
                                   "protocol": "irc", "target": channel, "via": "irc", "group": True, "title": channel,
                                   "members": [], "account": entry["id"]})
        return routes

    def validate_routes(self, routes):
        if not isinstance(routes, list) or len(routes) > 300:
            raise ValueError("Configure up to 300 protocol routes")
        output = []
        for route in routes:
            output.append(self.validate_route(route, output))
        return output

    def validate_route(self, route, existing):
        """Checks one link between a phone conversation and an internet address.

        Targets: an email address; a Matrix room (!room) or person (@user:server); an XMPP address or group
        (room@conference.server); an IRC #channel or nick. A Matrix room for XMPP or IRC means a bridge.
        """
        format, address, target = route.get("protocol"), route.get("address"), route.get("target")
        if (not valid_address(address) or not isinstance(target, str)
                or any(r["address"] == address and r["protocol"] == format for r in existing)):
            raise ValueError("Invalid or duplicate protocol route")
        account = route.get("account") or ""
        if not isinstance(account, str) or account and not account.startswith(via_for(format, target) + ":"):
            raise ValueError("Choose an account for this service")
        via = format
        if format == "email":
            if not EMAIL.fullmatch(target):
                raise ValueError("Enter one email address for this contact")
            target = target.lower()
        elif format == "matrix" or format in ("xmpp", "irc") and target.startswith("!"):
            if not ROOM.fullmatch(target) and not MXID.fullmatch(target):
                raise ValueError("Enter a Matrix ID like @maya:example.org or a room ID")
            via = "matrix"
        elif format == "xmpp":
            if not JID.fullmatch(target):
                raise ValueError("Enter an XMPP address like maya@example.org")
            target = target.lower()
        elif format == "irc":
            if not (CHANNEL.fullmatch(target) or NICK.fullmatch(target)):
                raise ValueError("Enter an IRC #channel or nickname")
        else:
            raise ValueError("Unknown internet format")
        if any(r.get("via", r["protocol"]) == via and target_key(via, r["target"]) == target_key(via, target)
               and (r.get("account") or "") == account for r in existing):
            raise ValueError("Each address or room can belong to one conversation per account")
        members = route.get("members", [])
        if not isinstance(members, list) or len(members) > 100:
            raise ValueError("Invalid route member list")
        group = bool(route.get("group")) or format == "irc" and target[:1] in "#&"
        result = {"address": address, "protocol": format, "target": target, "via": via, "group": group,
                  "title": bounded(route.get("title"), 120) or address,
                  "members": [bounded(name, 120) for name in members if isinstance(name, str)]}
        if account:
            result["account"] = account
        # When it was linked or joined (epoch ms), for Communities' "Date joined" order.
        if type(route.get("joined")) is int and route["joined"] > 0:
            result["joined"] = route["joined"]
        return result

    def add_route(self, db, data):
        """Links a chat. [account] picks which account it goes through; without one (older phones, or a choice not
        made) it's the service's most recently used account."""
        data = dict(data)
        via = via_for(data.get("protocol"), data.get("target"))
        choices = [entry["id"] for entry in self.accounts.of(via)]
        account = data.get("account") or self.recent_account(db, via)
        if account and account not in choices:
            raise ValueError("That account isn’t on FluidMessages Web anymore")
        existing = self.routes
        # One chat per person, group, channel, or room on a service and account: the same one again opens that chat.
        # (A different account on the service gets a chat of its own, below.)
        wanted = target_key(via, data.get("target"))
        same = next((r for r in existing if r.get("via", r["protocol"]) == via and wanted
                     and target_key(via, r["target"]) == wanted and (r.get("account") or "") == (account or "")), None)
        # Linking it to a contact's phone-number conversation (details → Link an address) still says it's taken.
        if same and data.get("protocol") == same["protocol"] and not NUMBER.fullmatch(str(data.get("address") or "").partition("~")[0]):
            return {"route": {key: value for key, value in same.items() if key != "via"}, "existing": True}
        address = data.get("address")
        if account and valid_address(address) and any(r["address"] == address and r["protocol"] == data.get("protocol")
                                                       and r["account"] != account for r in existing):
            # The same contact through another account is a chat of its own.
            data["address"] = address.partition("~")[0] + "~" + account_tag(account)
        data["account"] = account
        data.setdefault("joined", int(time.time() * 1000))
        route = self.validate_route(data, existing)
        if route["via"] == "matrix" and route["target"].startswith("@"):
            with self.using(account):
                route["target"] = self.matrix_direct_room(route["target"])
            # Starting a new chat again with someone you already have a direct room with opens that chat. Linking the
            # room to a phone number's conversation (conversation details → Link an address) still links it there.
            same = None if NUMBER.fullmatch(route["address"].partition("~")[0]) else next(
                (r for r in existing if r.get("via", r["protocol"]) == "matrix" and r["target"] == route["target"]
                 and (r.get("account") or "") == (route.get("account") or "")), None)
            if same:
                return {"route": {key: value for key, value in same.items() if key != "via"}, "existing": True}
        db.execute("INSERT INTO protocol_routes (address, protocol, payload) VALUES (?, ?, ?)",
                   (route["address"], route["protocol"], json.dumps(route)))
        self.refresh_clients()
        return {"route": {key: value for key, value in route.items() if key != "via"}}

    def delete_route(self, db, data):
        if any(r["address"] == data.get("address") and r["protocol"] == data.get("protocol") for r in self.legacy_routes):
            raise ValueError("This link is set in FluidMessages Web’s configuration file")
        db.execute("DELETE FROM protocol_routes WHERE address=? AND protocol=?", (data.get("address"), data.get("protocol")))
        self.refresh_clients()
        return {"deleted": True}

    @staticmethod
    def time():
        return int(time.time() * 1000)

    def enabled(self, db):
        row = db.execute("SELECT value FROM protocol_state WHERE key='enabled'").fetchone()
        return set(json.loads(row[0])) if row else set(INTERNET)

    def snapshot(self, db, enabled=None):
        if enabled is not None:
            if not isinstance(enabled, list) or any(not isinstance(item, str) for item in enabled):
                raise ValueError("Invalid protocol settings")
            db.execute("INSERT OR REPLACE INTO protocol_state VALUES ('enabled', ?) ", (json.dumps(enabled),))
        rows = db.execute("SELECT payload FROM protocol_messages ORDER BY date DESC, rowid DESC LIMIT 1500")
        messages, size = [], 0
        for row in rows:
            size += len(row[0].encode())
            if size > 5 * 1024 * 1024:
                break
            messages.append(json.loads(row[0]))
        settings = self.settings(db)
        messages = self.visible(settings, messages)
        channels = {}
        for key, value in db.execute("SELECT key, value FROM protocol_state WHERE key LIKE 'channel:%'"):
            channels[key.removeprefix("channel:")] = json.loads(value)
        listed = [account for account in self.accounts.redacted() if "error" not in account]
        names = {account["id"]: account["name"] for account in listed}
        keep = self.keep_map()
        def shown(route):
            item = {key: value for key, value in route.items() if key != "via"}
            if not item.get("account"):
                item.pop("account", None)
            elif item["account"] in names:
                # Shown as "via alice@example.org" when a service has more than one account.
                item["account_name"] = names[item["account"]]
            if route.get("via") == "irc" and route.get("group"):
                item["keep_joined"] = keep.get(route["address"], True) is not False
            secure = self.secure(route.get("account")) if route.get("via") == "matrix" else None
            if secure is not None:
                item["encrypted"] = secure.encrypted(route["target"])
            return item
        routes = [shown(route) for route in self.routes if not self.hidden(settings, route)]
        # Each service's account used most recently (else its newest), which New message preselects as From.
        recent = {}
        for message in messages:
            account = message.get("account")
            protocol = account.partition(":")[0] if isinstance(account, str) else ""
            if protocol and protocol not in recent and account in names:
                recent[protocol] = account
        for account in listed:
            recent.setdefault(account["protocol"], next(item["id"] for item in reversed(listed) if item["protocol"] == account["protocol"]))
        # Older phones read only protocol and source; the id and public address name each account. The primary one
        # (the service's first) owns chats from before multiple accounts.
        return {"messages": messages, "routes": routes, "enabled": sorted(self.enabled(db)), "channels": channels,
                # IRC, Matrix, and XMPP invitations and XMPP contact requests waiting for an answer (chat_features.py).
                "invites": [invite for invite in self.invites() if invite["account"] in names],
                # Each IRC network's connection state, for the Disconnected · Reconnect bar.
                "connections": self.connections(),
                "accounts": [{"protocol": account["protocol"], "source": account["source"], "id": account["id"],
                              "name": account["name"], "primary": bool(account.get("primary")),
                              "recent": recent.get(account["protocol"]) == account["id"]} for account in listed]}

    @staticmethod
    def mark_read(db, data):
        """Marks received messages read, by id or for a whole conversation, so every device clears the unread dot."""
        ids, address = data.get("ids", []), data.get("address", "")
        if not isinstance(ids, list) or len(ids) > 2000 or any(not isinstance(item, str) or len(item) > 120 for item in ids) \
                or not isinstance(address, str) or len(address) > 300 or not ids and not address:
            raise ValueError("Invalid read request")
        wanted, changed = set(ids), 0
        for key, payload in db.execute("SELECT id, payload FROM protocol_messages").fetchall():
            item = json.loads(payload)
            if item.get("type") != 1 or item.get("read", 1) == 1 or not (key in wanted or address and item["address"] == address):
                continue
            item["read"] = 1
            db.execute("UPDATE protocol_messages SET payload=? WHERE id=?", (json.dumps(item), key))
            changed += 1
        return {"read": changed}

    # Companion settings, privacy, and disappearing messages

    DEFAULT_SETTINGS = {"follow_phone": True, "fluid_encryption": {"protocols": [], "chats": {}}, "disappearing": {"default": 0, "default_since": 0, "chats": {}, "since": {}},
                        "hidden_protocols": [], "hidden_addresses": []}

    def settings(self, db):
        """Settings FluidMessages Web uses on its own. With follow_phone on, the phone's copy replaces them on each sync."""
        row = db.execute("SELECT value FROM protocol_state WHERE key='settings'").fetchone()
        stored = json.loads(row[0]) if row else {}
        return {**self.DEFAULT_SETTINGS, **stored}

    @staticmethod
    def clean_timers(value):
        if not isinstance(value, dict):
            raise ValueError("Invalid disappearing settings")
        default = value.get("default", 0)
        chats = value.get("chats", {})
        default_since, since = value.get("default_since", 0), value.get("since", {})
        if type(default) is not int or not 0 <= default <= 366 * 86400 or not isinstance(chats, dict) or len(chats) > 5000 \
                or type(default_since) is not int or default_since < 0 or not isinstance(since, dict):
            raise ValueError("Invalid disappearing settings")
        clean, starts = {}, {}
        for key, seconds in chats.items():
            if not isinstance(key, str) or len(key) > 300 or type(seconds) is not int or not 0 <= seconds <= 366 * 86400:
                raise ValueError("Invalid disappearing settings")
            clean[key] = seconds
            start = since.get(key, 0)
            starts[key] = start if type(start) is int and start >= 0 else 0
        return {"default": default, "default_since": default_since, "chats": clean, "since": starts}

    @staticmethod
    def clean_list(value, limit=5000):
        if not isinstance(value, list) or len(value) > limit or any(not isinstance(item, str) or len(item) > 300 for item in value):
            raise ValueError("Invalid privacy settings")
        return value

    def save_settings(self, db, data, from_phone=False):
        current = self.settings(db)
        if data.get("reset") is True and not from_phone:
            # Back to defaults, keeping what the phone hides and encrypts, which only the phone can change.
            current = {**json.loads(json.dumps(self.DEFAULT_SETTINGS)),
                       **{key: current[key] for key in ("hidden_protocols", "hidden_addresses", "fluid_encryption")}}
            db.execute("INSERT OR REPLACE INTO protocol_state VALUES ('settings', ?)", (json.dumps(current),))
            return current
        if from_phone and not current["follow_phone"]:
            # The companion was set to use its own settings; keep the phone's hidden lists, which only ever hide more.
            data = {key: data[key] for key in ("hidden_protocols", "hidden_addresses", "fluid_encryption") if key in data}
        if "follow_phone" in data:
            if type(data["follow_phone"]) is not bool:
                raise ValueError("Invalid setting")
            current["follow_phone"] = data["follow_phone"]
        if "disappearing" in data:
            timers = self.clean_timers(data["disappearing"])
            if not from_phone:
                # A timer changed in the browser starts now, so earlier messages stay.
                before, now = current["disappearing"], self.time()
                if timers["default"] != before.get("default"):
                    timers["default_since"] = now
                for key, seconds in timers["chats"].items():
                    timers["since"][key] = now if before.get("chats", {}).get(key) != seconds else before.get("since", {}).get(key, now)
            current["disappearing"] = timers
        for key in ("hidden_protocols", "hidden_addresses"):
            if key in data:
                current[key] = self.clean_list(data[key])
        if from_phone and isinstance(data.get("fluid_encryption"), dict):
            fluid = data["fluid_encryption"]
            chats = fluid.get("chats", {})
            if not isinstance(chats, dict) or len(chats) > 5000 or any(
                    not isinstance(key, str) or len(key) > 300 or value not in ("on", "off") for key, value in chats.items()):
                raise ValueError("Invalid encryption settings")
            current["fluid_encryption"] = {"protocols": self.clean_list(fluid.get("protocols", []), 20), "chats": chats}
        db.execute("INSERT OR REPLACE INTO protocol_state VALUES ('settings', ?)", (json.dumps(current),))
        return current

    def hidden(self, settings, message):
        """Chats hidden on the phone stay out of FluidMessages Web too."""
        protocol = family = message.get("protocol") or "sms"
        if protocol in ("mms", "rcs"):
            family = "sms"
        return family.upper() in settings["hidden_protocols"] or message.get("address") in settings["hidden_addresses"]

    @staticmethod
    def expiry(settings, message):
        if isinstance(message.get("expires"), int) and message["expires"] > 0:
            return message["expires"]
        timers = settings["disappearing"]
        address = message.get("address")
        if address in timers["chats"]:
            seconds, since = timers["chats"][address], timers.get("since", {}).get(address, 0)
        else:
            seconds, since = timers["default"], timers.get("default_since", 0)
        # Only messages from after the timer was turned on disappear.
        return message["date"] + seconds * 1000 if seconds > 0 and message["date"] >= since else 0

    def visible(self, settings, messages, now=None):
        now = now or self.time()
        return [m for m in messages if not self.hidden(settings, m) and not 0 < self.expiry(settings, m) <= now]

    def sweep_expired(self):
        """Deletes FluidMessages Web copies whose time is up, and takes back your own Matrix and XMPP messages."""
        now = self.time()
        with closing(self.connect()) as db, db:
            settings = self.settings(db)
            gone = []
            for key, payload in db.execute("SELECT id, payload FROM protocol_messages").fetchall():
                message = json.loads(payload)
                if 0 < self.expiry(settings, message) <= now:
                    gone.append(message)
                    db.execute("DELETE FROM protocol_messages WHERE id=?", (key,))
        for message in gone:
            try:
                path = self.file_path(message["id"])
                if path.is_file():
                    path.unlink()
            except (ValueError, OSError):
                pass
            if message.get("type") != 2:
                continue
            route = self.route(message["address"], message["protocol"])
            try:
                with self.using(route and route.get("account")):
                    self.take_back(route, message)
            except Exception:
                pass
        return len(gone)

    def take_back(self, route, message):
        """Takes back your own disappearing Matrix or XMPP message, through the chat's account."""
        if route and route.get("via") == "matrix" and message.get("event_id"):
            room = quote(route["target"], safe="")
            self.matrix_request("rooms/" + room + "/redact/" + quote(message["event_id"], safe="") + "/"
                                + message["id"] + "-expire", {"reason": "Disappearing message"}, method="PUT")
        elif route and route.get("via") == "xmpp" and message.get("xmpp_id"):
            self.client("xmpp").retract(route["target"], message["xmpp_id"], route.get("group"))

    def expirer(self):
        while True:
            try:
                self.sweep_expired()
            except Exception:
                pass
            time.sleep(20)

    # Calls

    def call_event(self, route, event, content):
        """Queues call signaling for browsers and phones, and notes the call in the conversation."""
        date = event.get("origin_server_ts") if type(event.get("origin_server_ts")) is int else self.time()
        # Invites older than their lifetime are missed calls, not ringing ones (sync can deliver old events).
        lifetime = content.get("lifetime") if type(content.get("lifetime")) is int else 60_000
        live = self.time() - date < lifetime
        with self.call_lock:
            self.call_number += 1
            self.calls.append({"seq": self.call_number, "address": route["address"], "protocol": route["protocol"],
                               "title": route["title"], "type": event["type"], "content": content,
                               "sender": bounded(event.get("sender"), 300), "date": date, "live": live})
        if event["type"] == "m.call.invite":
            video = "m=video" in str((content.get("offer") or {}).get("sdp", ""))
            record = self.message(route, "call-" + self.stable(route, event["event_id"]),
                                  ("Video call" if video else "Voice call") + (" from " + route["title"] if live else " · missed"),
                                  False, date, author=bounded(event.get("sender"), 120))
            record["event"] = True
            self.add_incoming(record)

    def call_events(self, since):
        with self.call_lock:
            return {"events": [item for item in self.calls if item["seq"] > since], "next": self.call_number}

    def send_call(self, data):
        address, protocol, kind, content = (data.get(key) for key in ("address", "protocol", "type", "content"))
        route = self.route(address, protocol) if isinstance(address, str) and isinstance(protocol, str) else None
        if route is None or route.get("via") != "matrix":
            raise ValueError("Calls from FluidMessages Web work in Matrix conversations")
        if kind not in CALL_TYPES or not isinstance(content, dict) or len(json.dumps(content)) > 64_000:
            raise ValueError("Invalid call signal")
        transaction = "call-" + uuid.uuid4().hex
        with self.using(route["account"]):
            secure = self.secure()
            if secure is not None:
                return {"event_id": secure.send(route["target"], kind, content, transaction)}
            response = self.matrix_request("rooms/" + quote(route["target"], safe="") + "/send/" + kind + "/" + transaction,
                                           content, method="PUT")
        return {"event_id": response["event_id"]}

    def ice_servers(self):
        """TURN from the Matrix homeserver, plus any servers set with FLUID_ICE_SERVERS (a JSON list)."""
        servers = []
        try:
            extra = json.loads(os.environ.get("FLUID_ICE_SERVERS", "[]"))
            if isinstance(extra, list):
                servers += [item for item in extra if isinstance(item, dict) and item.get("urls")]
        except ValueError:
            pass
        if self.matrix.get("access_token"):
            try:
                turn = self.matrix_request("voip/turnServer") or {}
                if isinstance(turn.get("uris"), list) and turn["uris"]:
                    servers.append({"urls": [uri for uri in turn["uris"] if isinstance(uri, str)],
                                    "username": str(turn.get("username", "")), "credential": str(turn.get("password", ""))})
            except Exception:
                pass
        return {"iceServers": servers}

    def change_member(self, db, data):
        """Invites or removes someone in a Matrix room, XMPP group chat, or IRC channel."""
        address, protocol, action, member = (data.get(key) for key in ("address", "protocol", "action", "member"))
        route = self.route(address, protocol) if isinstance(address, str) and isinstance(protocol, str) else None
        if route is None:
            raise ValueError("Link this group first")
        if action not in ("add", "remove") or not isinstance(member, str) or not 0 < len(member.strip()) <= 300:
            raise ValueError("Enter who to add or remove")
        with self.using(route["account"]):
            return self.change_member_as(route, add=action == "add", member=member.strip())

    def change_member_as(self, route, add, member):
        via = route.get("via")
        if via == "matrix":
            if not re.fullmatch(r"@[^:\s]+:[^\s]+", member):
                raise ValueError("Enter a Matrix ID like @maya:example.org")
            body = {"user_id": member} if add else {"user_id": member, "reason": "Removed from the group"}
            self.matrix_request("rooms/" + quote(route["target"], safe="") + ("/invite" if add else "/kick"), body, method="POST")
        elif via == "xmpp":
            if not route.get("group"):
                raise ValueError("Only XMPP group chats have members to change")
            self.client("xmpp").change_member(route["target"], add, member)
        elif via == "irc":
            if not route["target"].startswith("#") or not re.fullmatch(r"[^\s,:#]{1,64}", member):
                raise ValueError("Enter a nickname in an IRC channel")
            self.client("irc").write(("INVITE " + member + " " + route["target"]) if add
                                     else ("KICK " + route["target"] + " " + member + " :Removed from the channel"))
        else:
            raise ValueError("This group's protocol can't change members")
        return {"ok": True}

    def route(self, address, protocol):
        return next((route for route in self.routes if route["address"] == address and route["protocol"] == protocol), None)

    def validate_send(self, db, data):
        address, format, body, request = (data[name] for name in ("address", "protocol", "body", "request"))
        route = self.route(address, format)
        if route is None:
            raise ValueError("This contact has no configured route for that protocol")
        if format not in self.enabled(db):
            raise ValueError("This protocol is turned off on the phone")
        if not isinstance(body, str) or not body.strip() or len(body) > 1600:
            raise ValueError("Enter 1–1,600 characters")
        if not isinstance(request, str) or not ID.fullmatch(request):
            raise ValueError("Invalid message request")
        self.check_not_command(route, body, data)
        return route, body, "gateway-" + request

    def relation(self, db, data, route):
        """Validates an optional reply, reaction, or forward and resolves its target in this conversation."""
        value = data.get("relation")
        text = data.get("text", "")
        if not isinstance(text, str) or len(text) > 1600:
            raise ValueError("Enter 1–1,600 characters")
        if value is None:
            return None, text
        kind = value.get("kind") if isinstance(value, dict) else None
        target = value.get("target", "") if isinstance(value, dict) else None
        if kind not in ("reply", "reaction", "forward") or not isinstance(target, str) or len(target) > 120:
            raise ValueError("Invalid message relation")
        emoji = value.get("emoji", "")
        if kind == "reaction" and not is_emoji(emoji):
            raise ValueError("Choose one emoji")
        found = None
        if target:
            row = db.execute("SELECT payload FROM protocol_messages WHERE id=?", (target,)).fetchone()
            item = json.loads(row[0]) if row else None
            if item and item["address"] == route["address"] and item["protocol"] == route["protocol"]:
                found = item
        result = {"kind": kind, "target": target if found else "", "emoji": emoji if kind == "reaction" else ""}
        for key in ("event_id", "mail_id", "mail_subject", "xmpp_id", "irc_msgid", "author"):
            if found and isinstance(found.get(key), str):
                result[key] = found[key]
        if kind == "reaction":
            removed, previous = value.get("removed", False), value.get("previous", "")
            if type(removed) is not bool or not isinstance(previous, str) or previous and not is_emoji(previous):
                raise ValueError("Invalid message relation")
            if removed or previous:
                result["removed"], result["previous"] = removed, previous
                if found:
                    earlier = self.previous_reaction(db, route, found["id"])
                    if earlier:
                        result["previous_event"] = earlier
        return result, text

    @staticmethod
    def previous_reaction(db, route, target):
        """The event of your latest reaction on a message, which a removal or change takes back."""
        for row in db.execute("SELECT payload FROM protocol_messages ORDER BY date DESC, rowid DESC LIMIT 1500"):
            item = json.loads(row[0])
            if item["address"] != route["address"] or item["protocol"] != route["protocol"] or item["type"] != 2 \
                    or item.get("reaction_target") != target:
                continue
            if (item.get("relation") or {}).get("removed") or item.get("status") != "Sent":
                return ""
            return item.get("event_id", "")
        return ""

    def file_path(self, message_id):
        if not isinstance(message_id, str) or not re.fullmatch(r"[A-Za-z0-9_-]{1,120}", message_id):
            raise ValueError("Invalid message")
        return self.files / message_id

    def store_upload(self, db, stream, size, mime, name):
        """Saves a file the phone sends before the message that uses it. Unused uploads expire after a day."""
        if not 0 < size <= MAX_FILE:
            raise ValueError("Files can be up to 25 MB")
        mime = (mime or "").split(";")[0].strip().lower()
        if not MIME.fullmatch(mime):
            mime = "application/octet-stream"
        upload = hashlib.sha256(os.urandom(32)).hexdigest()[:40]
        path = self.files / "uploads" / upload
        remaining = size
        with open(path, "wb") as output:
            while remaining:
                chunk = stream.read(min(remaining, 1 << 16))
                if not chunk:
                    path.unlink(missing_ok=True)
                    raise ValueError("The upload was incomplete")
                output.write(chunk)
                remaining -= len(chunk)
        cutoff = self.time() - 86_400_000
        for (old,) in db.execute("SELECT id FROM protocol_uploads WHERE date<?", (cutoff,)).fetchall():
            (self.files / "uploads" / old).unlink(missing_ok=True)
        db.execute("DELETE FROM protocol_uploads WHERE date<?", (cutoff,))
        db.execute("INSERT INTO protocol_uploads VALUES (?, ?, ?, ?, ?)", (upload, safe_name(name), mime, size, self.time()))
        return {"upload": upload}

    def claim_upload(self, db, value):
        if value is None:
            return None
        if not isinstance(value, dict) or not isinstance(value.get("upload"), str):
            raise ValueError("Invalid attachment")
        row = db.execute("SELECT name, mime, size FROM protocol_uploads WHERE id=?", (value["upload"],)).fetchone()
        if row is None or not (self.files / "uploads" / value["upload"]).is_file():
            raise ValueError("Upload the attachment again")
        kind = value.get("kind") if value.get("kind") in KINDS else kind_for(row[1])
        duration = value.get("duration") if type(value.get("duration")) is int and 0 <= value["duration"] < 86_400_000 else 0
        return {"upload": value["upload"], "kind": kind, "name": row[0], "mime": row[1], "size": row[2], "duration": duration}

    def find(self, address, protocol, key, value):
        """Most recent message in a conversation whose payload field matches, for threading and relations."""
        if not isinstance(value, str) or not value:
            return None
        with closing(self.connect()) as db:
            for row in db.execute("SELECT payload FROM protocol_messages ORDER BY date DESC, rowid DESC LIMIT 1500"):
                item = json.loads(row[0])
                if item["address"] == address and item["protocol"] == protocol and item.get(key) == value:
                    return item
        return None

    def enqueue(self, db, data):
        route, body, message_id = self.validate_send(db, data)
        relation, text = self.relation(db, data, route)
        attachment = self.claim_upload(db, data.get("attachment"))
        row = db.execute("SELECT payload FROM protocol_messages WHERE id=?", (message_id,)).fetchone()
        if row:
            existing = json.loads(row[0])
            if any(existing[key] != value for key, value in
                   (("address", route["address"]), ("protocol", route["protocol"]), ("body", body))):
                raise ValueError("Message request already belongs to a different message")
            return {"message": existing}
        now = self.time()
        due = data.get("due", now)
        if type(due) is not int or not now - 60_000 <= due <= now + 366 * 86_400_000:
            raise ValueError("Choose a date within the next year")
        cutoff = now - 60_000
        if db.execute("SELECT count(*) FROM protocol_jobs WHERE date>=?", (cutoff,)).fetchone()[0] >= 30:
            raise ValueError("Too many messages queued. Try again in a minute.")
        message = self.message(route, message_id, body, True, now, "Queued" if due <= now else "Scheduled")
        if data.get("literal") is True:
            message["literal"] = True  # Typed with //: "/me" goes out as written, not as an action.
        settings = self.settings(db)
        seconds = settings["disappearing"]["chats"].get(route["address"], settings["disappearing"]["default"])
        if seconds > 0:
            message["expire_seconds"] = seconds
        if attachment:
            upload = attachment.pop("upload")
            message["attachment"] = attachment
            os.replace(self.files / "uploads" / upload, self.file_path(message_id))
            db.execute("DELETE FROM protocol_uploads WHERE id=?", (upload,))
        if relation:
            message["relation"] = relation
            message["text"] = text
            if relation["target"]:
                message[relation["kind"] + "_target"] = relation["target"]
        db.execute("INSERT INTO protocol_messages VALUES (?, ?, ?)", (message_id, now, json.dumps(message)))
        db.execute("INSERT INTO protocol_jobs VALUES (?, ?, ?, ?, 'queued')", (message_id, route["protocol"], route["target"], due))
        self.wake.set()
        return {"message": message}

    def claim(self):
        with closing(self.connect()) as db, db:
            db.execute("BEGIN IMMEDIATE")
            row = db.execute("""SELECT jobs.id, jobs.format, jobs.target, messages.payload FROM protocol_jobs jobs
                JOIN protocol_messages messages ON messages.id=jobs.id
                WHERE jobs.state='queued' AND jobs.date<=? ORDER BY jobs.date, jobs.id LIMIT 1""", (self.time(),)).fetchone()
            if row is None:
                return None
            db.execute("UPDATE protocol_jobs SET state='claimed' WHERE id=?", (row[0],))
            return row

    def sender(self):
        while True:
            row = self.claim()
            if row is None:
                self.wake.wait(5)
                self.wake.clear()
                continue
            self.deliver_job(row)

    def deliver_job(self, row):
        """Sends one claimed job through its chat's account and records the result."""
        key, format, endpoint, payload = row
        message = json.loads(payload)
        try:
            with closing(self.connect()) as db:
                if format not in self.enabled(db):
                    raise ValueError("Protocol disabled")
            route = self.route(message["address"], format)
            if route is None or route["target"] != endpoint:
                raise ValueError("Route changed")
            if route["account"] and self.accounts.get(route["account"]) is None:
                raise ValueError("This chat’s account was removed from FluidMessages Web")
            send = {"email": self.send_mail, "matrix": self.send_matrix, "xmpp": self.send_xmpp,
                    "irc": self.send_irc}[route.get("via", format)]
            with self.using(route["account"]):
                message.update(send(route, message) or {})
            state, caption = "sent", "Sent"
        except (ValueError, ConnectionError) as error:
            # Short reasons reach the phone's red "Not delivered" notice.
            state, caption = "failed", "Couldn’t send · " + str(error)[:120]
        except Exception:
            state, caption = "failed", "Couldn’t send"
        with closing(self.connect()) as db, db:
            message["status"] = caption
            db.execute("UPDATE protocol_messages SET payload=? WHERE id=?", (json.dumps(message), key))
            db.execute("UPDATE protocol_jobs SET state=? WHERE id=?", (state, key))

    @staticmethod
    def message(route, id, body, outgoing, date, status="", author=""):
        record = {"id": id, "address": route["address"], "protocol": route["protocol"], "title": route["title"],
                  "body": bounded(body, 8000), "date": date, "type": 2 if outgoing else 1,
                  "read": 1 if outgoing else 0, "status": status, "author": bounded(author, 120)}
        if route.get("account"):
            record["account"] = route["account"]
        return record

    def add_incoming(self, message):
        with closing(self.connect()) as db, db:
            db.execute("INSERT OR IGNORE INTO protocol_messages VALUES (?, ?, ?)",
                       (message["id"], message["date"], json.dumps(message)))

    def latest_id(self, address):
        """The newest message in a conversation, on any protocol."""
        with closing(self.connect()) as db:
            for row in db.execute("SELECT payload FROM protocol_messages ORDER BY date DESC, rowid DESC LIMIT 1500"):
                item = json.loads(row[0])
                if item["address"] == address:
                    return item["id"]
        return ""

    def strip_stored_quotes(self):
        """Once: removes quoted history from emails received before FluidMessages stripped it."""
        if self.state("mail:quotes-stripped"):
            return
        with closing(self.connect()) as db, db:
            for key, payload in db.execute("SELECT id, payload FROM protocol_messages").fetchall():
                item = json.loads(payload)
                if item.get("protocol") != "email" or item.get("type") != 1 or item.get("reaction_target"):
                    continue
                cleaned = strip_quoted_email(item["body"])
                if cleaned and cleaned != item["body"]:
                    item["body"] = cleaned
                    db.execute("UPDATE protocol_messages SET payload=? WHERE id=?", (json.dumps(item), key))
        self.save_state("mail:quotes-stripped", "1")

    def state(self, key):
        with closing(self.connect()) as db:
            row = db.execute("SELECT value FROM protocol_state WHERE key=?", (key,)).fetchone()
            return row[0] if row else ""

    def save_state(self, key, value):
        with closing(self.connect()) as db, db:
            db.execute("INSERT OR REPLACE INTO protocol_state VALUES (?, ?)", (key, value))

    def send_mail(self, route, message):
        cfg = self.email
        envelope = EmailMessage()
        envelope["From"] = cfg["address"]
        envelope["To"] = route["target"]
        envelope["Message-ID"] = make_msgid(idstring=message["id"])
        envelope["Subject"] = bounded(cfg.get("subject"), 120) or "FluidMessages conversation"
        with closing(self.connect()) as db:
            rows = db.execute("SELECT payload FROM protocol_messages WHERE id<>? ORDER BY date DESC LIMIT 1500",
                              (message["id"],))
            previous = next((item for row in rows if (item := json.loads(row[0]))["address"] == route["address"]
                             and item["protocol"] == "email" and item.get("mail_id")), None)
        relation = message.get("relation") or {}
        if relation.get("mail_id"):
            # Replies and reactions thread onto the exact email they refer to.
            previous = {"mail_id": relation["mail_id"], "mail_subject": relation.get("mail_subject", "")}
        if previous and re.fullmatch(r"<[^\s<>]{1,240}>", previous["mail_id"]):
            envelope["In-Reply-To"] = previous["mail_id"]
            envelope["References"] = previous["mail_id"]
            if previous.get("mail_subject") and not re.search(r"[\r\n]", previous["mail_subject"]):
                subject = previous["mail_subject"]
                envelope.replace_header("Subject", subject if subject.lower().startswith("re:") else "Re: " + subject)
        attachment = message.get("attachment")
        if relation.get("kind") == "reaction" and relation.get("mail_id") and not revises(relation):
            # RFC 9078: a reaction is a reply whose only text part is the emoji, marked as a reaction.
            envelope.set_content(relation["emoji"], disposition="reaction")
        elif relation.get("kind") == "reply" and relation.get("mail_id"):
            envelope.set_content(message.get("text", message["body"]) + "\n\nSent with FluidMessages")
        else:
            caption = message.get("text") if attachment else message["body"]
            envelope.set_content((caption or "") + "\n\nSent with FluidMessages")
        if attachment:
            maintype, _, subtype = attachment["mime"].partition("/")
            envelope.add_attachment(self.file_path(message["id"]).read_bytes(), maintype=maintype, subtype=subtype,
                                    filename=attachment["name"])
        with self.smtp_connection(cfg) as server:
            server.login(cfg.get("smtp_user", cfg["address"]), cfg["smtp_password"])
            server.send_message(envelope, from_addr=cfg["address"], to_addrs=[route["target"]])
        return {"mail_id": str(envelope["Message-ID"]), "mail_subject": str(envelope["Subject"])}

    def smtp_connection(self, cfg):
        """SMTP with TLS from the start on 465 (the default), or STARTTLS on any other port such as 587. Never plain text."""
        port = int(cfg.get("smtp_port") or SMTP_PORT)
        if not smtp_starttls(port):
            return smtplib.SMTP_SSL(cfg["smtp_host"], port, context=self.tls, timeout=12)
        server = smtplib.SMTP(cfg["smtp_host"], port, timeout=12)
        try:
            server.ehlo()
            if not server.has_extn("starttls"):
                raise RuntimeError(f"The mail server on port {port} doesn’t offer STARTTLS, so nothing was sent. Try port 465.")
            server.starttls(context=self.tls)
            server.ehlo()
        except BaseException:
            server.close()
            raise
        return server

    def imap_connection(self, cfg):
        """IMAP with TLS from the start (993, the default), or STARTTLS on 143. Never plain text."""
        port = int(cfg.get("imap_port") or IMAP_PORT)
        if not imap_starttls(port):
            return imaplib.IMAP4_SSL(cfg["imap_host"], port, ssl_context=self.tls, timeout=12)
        mailbox = imaplib.IMAP4(cfg["imap_host"], port, timeout=12)
        try:
            # Raises if the server doesn't offer STARTTLS, so the password never goes out unencrypted.
            mailbox.starttls(ssl_context=self.tls)
        except BaseException:
            mailbox.shutdown()
            raise
        return mailbox

    def receive_mail(self, account=None):
        with self.using(account):
            self.receive_mail_as(self.current_account("email"))

    def receive_mail_as(self, account):
        cfg = self.email
        scope = self.scope(account)
        with self.imap_connection(cfg) as mailbox:
            mailbox.login(cfg.get("imap_user", cfg["address"]), cfg["imap_password"])
            if mailbox.select(cfg.get("folder", "INBOX"), readonly=True)[0] != "OK":
                return
            validity = mailbox.response("UIDVALIDITY")[1][0]
            if not validity:
                return
            epoch = validity.decode()
            for route in self.routes:
                if route["protocol"] != "email" or route["account"] != account:
                    continue
                key = "mail:" + (scope + ":" if scope else "") + epoch + ":" + route["target"]
                highest = int(self.state(key) or 0)
                criteria = ('FROM', '"' + route["target"] + '"') if highest == 0 else (
                    'UID', str(highest + 1) + ':*', 'FROM', '"' + route["target"] + '"')
                result, matches = mailbox.uid("search", None, *criteria)
                if result != "OK" or not matches or not matches[0]:
                    continue
                candidates = sorted(int(uid) for uid in matches[0].split() if int(uid) > highest)
                for uid in (candidates[-25:] if highest == 0 else candidates[:25]):
                    result, parts = mailbox.uid("fetch", str(uid), f"(RFC822.SIZE BODY.PEEK[]<0.{MAX_MAIL_WITH_FILES}>)")
                    if result != "OK":
                        break
                    part = next((part for part in parts if isinstance(part, tuple)), None)
                    if part is not None:
                        reported = re.search(rb"RFC822.SIZE (\d+)", part[0])
                        if reported and int(reported[1]) <= MAX_MAIL_WITH_FILES:
                            self.accept_mail(route, epoch, uid, part[1])
                    self.save_state(key, str(uid))

    def accept_mail(self, route, epoch, uid, payload):
        message = BytesParser(policy=policy.default).parsebytes(payload)
        sender = parseaddr(str(message.get("From", "")))[1].lower()
        if (sender != route["target"] or message.get("List-Id") or str(message.get("X-Spam-Flag", "")).lower() == "yes"
                or str(message.get("Auto-Submitted", "no")).lower() != "no"):
            return
        reaction = next((part for part in message.walk() if part.get_content_disposition() == "reaction"), None)
        target = reply_target = None
        if reaction is not None:
            emoji = reaction.get_content().strip() if isinstance(reaction.get_content(), str) else ""
            replied = str(message.get("In-Reply-To", "")).strip()
            target = self.find(route["address"], "email", "mail_id", replied) if is_emoji(emoji) and replied else None
        attached = None
        if target is not None:
            text = reaction_text(emoji, target["body"])
        else:
            body = message.get_body(preferencelist=("plain",))
            text = body.get_content() if body is not None and isinstance(body.get_content(), str) else ""
            text = strip_quoted_email(text.removesuffix("\n\nSent with FluidMessages\n"))
            # The quoted history is gone; a reply card stands in when they answer an older email than the latest.
            replied = str(message.get("In-Reply-To", "")).strip()
            answered = self.find(route["address"], "email", "mail_id", replied) if replied else None
            if answered is not None and answered["id"] != self.latest_id(route["address"]) and text:
                text = reply_text(answered["body"], text)
                reply_target = answered["id"]
            # The first attachment comes along; the companion keeps it for the phone to download.
            for part in message.iter_attachments():
                data = part.get_payload(decode=True)
                if isinstance(data, bytes) and 0 < len(data) <= MAX_FILE:
                    mime = part.get_content_type() if MIME.fullmatch(part.get_content_type()) else "application/octet-stream"
                    attached = ({"kind": kind_for(mime), "name": safe_name(part.get_filename()), "mime": mime,
                                 "size": len(data), "duration": 0}, data)
                    break
            if not text and attached is None:
                return
            text = text or attached[0]["name"]
        try:
            date = min(self.time(), int(parsedate_to_datetime(message.get("Date")).timestamp() * 1000))
        except (AttributeError, ValueError, TypeError, OverflowError):
            date = self.time()
        stable = self.stable(route, f"{route['target']}:{epoch}:{uid}")
        record = self.message(route, "mail-" + stable, text, False, max(0, date), author=route["title"])
        record["mail_id"] = bounded(str(message.get("Message-ID", "")), 240)
        record["mail_subject"] = bounded(str(message.get("Subject", "")), 120)
        if target is not None:
            record["reaction_target"] = target["id"]
        if reply_target is not None:
            record["reply_target"] = reply_target
        if attached is not None:
            record["attachment"] = attached[0]
            self.file_path(record["id"]).write_bytes(attached[1])
        self.add_incoming(record)

    def matrix_request(self, path, body=None, allow_missing=False, method=None):
        request = Request(self.matrix["homeserver"] + "/_matrix/client/v3/" + path,
                          data=json.dumps(body).encode() if body is not None else None, method=method,
                          headers={"Authorization": "Bearer " + self.matrix["access_token"], "Content-Type": "application/json"})
        try:
            with self.http.open(request, timeout=17) as response:
                data = response.read(MAX_MATRIX + 1)
                if len(data) > MAX_MATRIX:
                    raise ValueError("Matrix response too large")
                return json.loads(data)
        except HTTPError as error:
            if not allow_missing or error.code != 404:
                raise
            if json.load(error).get("errcode") != "M_NOT_FOUND":
                raise
            return None

    def send_matrix(self, route, message):
        room = quote(route["target"], safe="")
        secure = self.secure()
        if secure is None and self.matrix_request("rooms/" + room + "/state/m.room.encryption", allow_missing=True) is not None:
            raise ValueError("This room is end-to-end encrypted. Install matrix-nio[e2e] on FluidMessages Web to use it.")
        relation = message.get("relation") or {}
        event = relation.get("event_id")
        if revises(relation) and event:
            # Taking back a reaction redacts its event; a change redacts the old one, then sends the new one.
            earlier = relation.get("previous_event")
            if earlier:
                self.matrix_request("rooms/" + room + "/redact/" + quote(earlier, safe="") + "/" + message["id"] + "-redact",
                                    {}, method="PUT")
                if relation.get("removed"):
                    return {"redacted": earlier}
            elif relation.get("removed"):
                relation = {}  # Nothing native to take back: say it in text instead.
        if relation.get("kind") == "reaction" and event:
            content = {"m.relates_to": {"rel_type": "m.annotation", "event_id": event, "key": relation["emoji"]}}
            if secure is not None:
                return {"event_id": secure.send(route["target"], "m.reaction", content, message["id"])}
            response = self.matrix_request("rooms/" + room + "/send/m.reaction/" + message["id"], content, method="PUT")
            return {"event_id": response["event_id"]}
        content = {"msgtype": "m.text", "body": message["body"]}
        if message.get("attachment"):
            content = self.matrix_media(message, route["target"], secure)
        if relation.get("kind") == "reply" and event and not message.get("attachment"):
            # Matrix v1.13 replies carry no quoted fallback; the relation links the original event.
            content = {"msgtype": "m.text", "body": message.get("text") or message["body"],
                       "m.relates_to": {"m.in_reply_to": {"event_id": event}}}
        if not message.get("attachment"):
            self.matrix_outgoing(content, "" if message.get("literal") else content["body"])
        if message.get("expire_seconds"):
            content[DISAPPEAR] = message["expire_seconds"]
        if secure is not None:
            return {"event_id": secure.send(route["target"], "m.room.message", content, message["id"])}
        response = self.matrix_request("rooms/" + room + "/send/m.room.message/" + message["id"], content, method="PUT")
        return {"event_id": response["event_id"]}

    def matrix_raw(self, url, data=None, headers=None, method=None, limit=MAX_FILE):
        request = Request(url, data=data, method=method, headers={"Authorization": "Bearer " + self.matrix["access_token"],
                                                                  **(headers or {})})
        with self.http.open(request, timeout=120) as response:
            body = response.read(limit + 1)
            if len(body) > limit:
                raise ValueError("Matrix file too large")
            return body

    def matrix_media(self, message, room=None, secure=None):
        """Uploads to the homeserver's media repository and builds an m.image, m.video, m.audio, or m.file event.

        In encrypted rooms the file is encrypted before upload and the event carries its key ("file" instead of "url").
        """
        attachment = message["attachment"]
        data = self.file_path(message["id"]).read_bytes()
        if secure is not None:
            location = secure.upload(room, data, attachment["mime"], attachment["name"])
        else:
            uploaded = json.loads(self.matrix_raw(self.matrix["homeserver"] + "/_matrix/media/v3/upload?" +
                                                  urlencode({"filename": attachment["name"]}), data,
                                                  {"Content-Type": attachment["mime"]}, "POST", MAX_MATRIX))
            location = {"url": uploaded["content_uri"]}
        msgtype = {"photo": "m.image", "video": "m.video", "audio": "m.audio", "voice": "m.audio"}.get(attachment["kind"], "m.file")
        info = {"mimetype": attachment["mime"], "size": len(data)}
        if attachment.get("duration"):
            info["duration"] = attachment["duration"]
        caption = message.get("text") or ""
        content = {"msgtype": msgtype, "body": caption or attachment["name"], "filename": attachment["name"],
                   **location, "info": info}
        if attachment["kind"] == "voice":
            # MSC3245 marks voice notes so Element and other clients show a voice player.
            content["org.matrix.msc3245.voice"] = {}
            content["org.matrix.msc1767.audio"] = {"duration": attachment.get("duration", 0)}
        return content

    def matrix_download(self, mxc):
        match = re.fullmatch(r"mxc://([A-Za-z0-9.:-]{1,255})/([A-Za-z0-9_-]{1,255})", mxc or "")
        if not match:
            raise ValueError("Invalid media address")
        path = quote(match[1], safe="") + "/" + quote(match[2], safe="")
        try:
            # Authenticated media (Matrix 1.11), with the older endpoint as a fallback for older homeservers.
            return self.matrix_raw(self.matrix["homeserver"] + "/_matrix/client/v1/media/download/" + path)
        except HTTPError as error:
            if error.code not in (400, 404):
                raise
            return self.matrix_raw(self.matrix["homeserver"] + "/_matrix/media/v3/download/" + path)

    def receive_matrix(self, account=None):
        with self.using(account):
            self.receive_matrix_as(self.current_account("matrix"))

    def receive_matrix_as(self, account):
        rooms = {route["target"]: route for route in self.routes if route.get("via") == "matrix" and route["account"] == account}
        # Linked rooms' timelines (with topic, name, and membership changes), and every invitation: the room filter
        # sits on the timeline, state, and ephemeral parts so rooms.invite still lists rooms you aren't in yet.
        linked = list(rooms)
        filter = {"presence": {"types": []}, "room": {"timeline": {"limit": 60, "rooms": linked,
                  "types": ["m.room.message", "m.room.encrypted", "m.reaction", "m.room.redaction", "m.room.topic",
                            "m.room.name", "m.room.member", *CALL_TYPES]},
                  "state": {"rooms": linked, "lazy_load_members": True}, "ephemeral": {"rooms": linked, "types": []},
                  "account_data": {"rooms": linked, "types": []}}}
        args = {"filter": json.dumps(filter, separators=(",", ":")), "timeout": 10_000}
        scope = self.scope(account)
        since = "matrix:since" + (":" + scope if scope else "")
        if token := self.state(since):
            args["since"] = token
        joined = self.matrix_request("sync?" + urlencode(args))
        for room, data in joined.get("rooms", {}).get("join", {}).items():
            if room not in rooms:
                continue
            for event in data.get("timeline", {}).get("events", []):
                self.accept_matrix(rooms[room], event)
        for room, data in (joined.get("rooms", {}).get("invite") or {}).items():
            if isinstance(room, str) and ROOM.fullmatch(room) and isinstance(data, dict):
                self.matrix_invited(account, room, data)
        for room in (joined.get("rooms", {}).get("leave") or {}):
            # Declined or withdrawn somewhere else: the invitation goes.
            if isinstance(room, str) and room not in rooms:
                self.drop_invite(account, room)
        if isinstance(joined.get("next_batch"), str):
            self.save_state(since, joined["next_batch"])

    def accept_matrix(self, route, event):
        with self.using(route.get("account")):
            self.accept_matrix_as(route, event)

    def accept_matrix_as(self, route, event):
        if event.get("sender") == self.matrix.get("user_id") or not isinstance(event.get("event_id"), str):
            return
        content = event.get("content", {})
        if not isinstance(content, dict):
            return
        if event.get("type") in CALL_TYPES:
            self.call_event(route, event, content)
            return
        relates = content.get("m.relates_to") if isinstance(content.get("m.relates_to"), dict) else {}
        stable = self.stable(route, event["event_id"])
        date = event.get("origin_server_ts")
        date = date if type(date) is int else self.time()
        author = event.get("sender", "")
        if event.get("type") in ("m.room.topic", "m.room.name", "m.room.member"):
            self.matrix_state(route, event, content, date)
            return
        edited = relates.get("rel_type") == "m.replace" and isinstance(content.get("m.new_content"), dict)
        if event.get("type") == "m.reaction":
            target = self.find(route["address"], route["protocol"], "event_id", relates.get("event_id"))
            if relates.get("rel_type") != "m.annotation" or target is None or not is_emoji(relates.get("key")):
                return
            record = self.message(route, "matrix-" + stable, reaction_text(relates["key"], target["body"]), False, date,
                                  author=author)
            record["reaction_target"] = target["id"]
            record["reaction"] = relates["key"]
        elif event.get("type") == "m.room.redaction":
            # Someone took back a reaction: record it as a removal so the bubble updates.
            redacts = event.get("redacts") or content.get("redacts")
            reaction = self.find(route["address"], route["protocol"], "event_id", redacts)
            if reaction is not None and not reaction.get("reaction"):
                # A deleted or disappearing message goes from the companion too.
                with closing(self.connect()) as db, db:
                    db.execute("DELETE FROM protocol_messages WHERE id=?", (reaction["id"],))
                return
            if reaction is None:
                return
            target = self.find(route["address"], route["protocol"], "id", reaction.get("reaction_target"))
            if target is None:
                return
            record = self.message(route, "matrix-" + stable, removed_text(reaction["reaction"], target["body"]), False, date,
                                  author=author)
            record["reaction_target"] = target["id"]
        elif event.get("type") == "m.room.message" and content.get("msgtype") in ("m.image", "m.video", "m.audio", "m.file") \
                and (isinstance(content.get("url"), str) or isinstance(content.get("file"), dict)
                     and isinstance(content["file"].get("url"), str)):
            info = content.get("info") if isinstance(content.get("info"), dict) else {}
            if type(info.get("size")) is int and info["size"] > MAX_FILE:
                return
            name = safe_name(content.get("filename") or content.get("body"))
            mime = info.get("mimetype") if isinstance(info.get("mimetype"), str) and MIME.fullmatch(info["mimetype"]) \
                else "application/octet-stream"
            data = self.matrix_download(content.get("url") or content["file"]["url"])
            if isinstance(content.get("file"), dict):
                data = matrix_e2ee.decrypt_file(content, data)
            kind = "voice" if "org.matrix.msc3245.voice" in content else kind_for(mime)
            caption = content.get("body") if isinstance(content.get("body"), str) and content.get("body") != name else ""
            record = self.message(route, "matrix-" + stable, caption or name, False, date, author=author)
            record["attachment"] = {"kind": kind, "name": name, "mime": mime, "size": len(data),
                                    "duration": info.get("duration") if type(info.get("duration")) is int else 0}
            self.file_path(record["id"]).write_bytes(data)
        elif event.get("type") == "m.room.message" and edited:
            # An edit replaces the sender's earlier message, the way the phone shows its own edits.
            new = content["m.new_content"]
            original = self.find(route["address"], route["protocol"], "event_id", relates.get("event_id"))
            if original is None or not isinstance(new.get("body"), str):
                return
            text = ("/me " if new.get("msgtype") == "m.emote" else "") + new["body"]
            record = self.message(route, "matrix-" + stable, edit_text(original["body"], text), False, date, author=author)
        elif event.get("type") == "m.room.message":
            if content.get("msgtype") not in ("m.text", "m.notice", "m.emote") or not isinstance(content.get("body"), str):
                return
            body = ("/me " if content["msgtype"] == "m.emote" else "") + content["body"]
            reply = relates.get("m.in_reply_to") if isinstance(relates.get("m.in_reply_to"), dict) else {}
            target = self.find(route["address"], route["protocol"], "event_id", reply.get("event_id")) if reply else None
            if target is not None:
                # Older clients prepend "> <@user> quote" lines; the quote card replaces them.
                lines = body.split("\n")
                while lines and lines[0].startswith(">"):
                    lines.pop(0)
                stripped = "\n".join(lines).strip()
                body = reply_text(target["body"], stripped or body)
            record = self.message(route, "matrix-" + stable, body, False, date, author=author)
            if target is not None:
                record["reply_target"] = target["id"]
            if content["msgtype"] == "m.notice":
                record["notice"] = True  # Bots send notices.
            if self.matrix_mentioned(content, content["body"]):
                record["mention"] = True
            if type(content.get(DISAPPEAR)) is int and 0 < content[DISAPPEAR] <= 366 * 86400:
                record["expires"] = date + content[DISAPPEAR] * 1000
        else:
            return
        record["event_id"] = event["event_id"]
        self.add_incoming(record)

    def active(self, protocol):
        """Turned on in the phone's settings and not connected from the phone instead."""
        with closing(self.connect()) as db:
            return protocol in self.enabled(db)

    def start(self):
        try:
            self.strip_stored_quotes()
        except Exception:
            pass
        threading.Thread(target=self.sender, daemon=True, name="protocol-sender").start()
        threading.Thread(target=self.supervise, daemon=True, name="protocol-clients").start()
        threading.Thread(target=self.expirer, daemon=True, name="protocol-disappearing").start()

    def supervise(self):
        while True:
            try:
                self.refresh_clients()
            except Exception:
                pass
            time.sleep(15)

    def wanted_clients(self):
        """What should run for each account: {account id: (signature, start)}. A poller or connection runs only while
        its account is turned on and at least one link uses it."""
        routes = self.routes
        wanted = {}
        for entry in self.accounts.all():
            ident, protocol, account = entry["id"], entry["protocol"], entry["values"]
            if not self.active(protocol):
                continue
            mine = [r for r in routes if r.get("via", r["protocol"]) == protocol and r["account"] == ident]
            if protocol == "matrix":
                # Running with no rooms linked yet too, so invitations (and new direct chats) arrive.
                secure = matrix_e2ee.available()
                wanted[ident] = (json.dumps([account, secure], sort_keys=True),
                                 (lambda i=ident, a=account: self.secure_client(i, a)) if secure else
                                 (lambda i=ident: Poller(lambda: self.receive_matrix(i), 5)), None)
            elif protocol == "email" and mine:
                interval = lambda i=ident: 60 * int((self.accounts.get(i) or {}).get("poll_minutes", 1))
                wanted[ident] = (json.dumps(account, sort_keys=True),
                                 lambda i=ident, every=interval: Poller(lambda: self.receive_mail(i), every), None)
            elif protocol in ("irc", "xmpp"):
                native = [r for r in mine if r["protocol"] == protocol]
                # Connected even with no chats yet: bots, people, and invitations can reach you.
                if protocol == "xmpp" and not xmpp_client.available():
                    continue
                # An invitation still waiting (message_requests.note_invite) isn't joined until it's accepted.
                rooms = sorted({r["target"] for r in native if r.get("group") and not r.get("invite")})
                if protocol == "irc":
                    rooms = sorted(set(account.get("channels", [])) | set(rooms), key=str.lower)
                # Joining or leaving a channel or group chat doesn't reconnect: sync() updates the live session.
                wanted[ident] = (json.dumps(account, sort_keys=True),
                                 lambda i=ident, a=account, r=rooms, p=protocol: self.chat_client(p, i, a, r), rooms)
        return wanted

    def refresh_clients(self):
        """Starts, restarts, or stops each account's IRC, XMPP, encrypted Matrix, or polling session to match
        accounts and links."""
        wanted = self.wanted_clients()
        for ident, entry in list(self.clients.items()):
            if ident not in wanted or wanted[ident][0] != entry[0]:
                entry[1].stop()
                del self.clients[ident]
        for ident, (signature, create, rooms) in wanted.items():
            if ident in self.clients:
                client = self.clients[ident][1]
                if rooms is not None and hasattr(client, "sync_channels"):
                    client.sync_channels(rooms)
                elif rooms is not None and hasattr(client, "sync_rooms"):
                    client.sync_rooms(rooms)
                continue
            client = create()
            self.clients[ident] = (signature, client)
            run = (lambda c=client, i=ident: self.guard(i, c)) if isinstance(client, matrix_e2ee.MatrixE2ee) else client.run
            threading.Thread(target=run, daemon=True, name="protocol-" + ident.split(":", 1)[0]).start()

    def secure_client(self, ident, account):
        scope = self.scope(ident)
        store = self.files / ("matrix-store" + ("-" + scope if scope else ""))
        rooms = lambda: {r["target"] for r in self.routes if r.get("via") == "matrix" and r["account"] == ident}
        return matrix_e2ee.MatrixE2ee(account, store, rooms, lambda room, event: self.accept_secure(room, event, ident),
                                      on_invite=lambda room, sender, name: self.add_invite(ident, "matrix", "room", room,
                                                                                           sender, name))

    def chat_client(self, protocol, ident, account, rooms):
        if protocol == "irc":
            return irc_client.IrcClient({**account, "channels": list(rooms)},
                                        lambda *args: self.accept_irc(*args, account=ident),
                                        lambda channel, state: self.channel_state(channel, state, ident),
                                        on_invite=lambda channel, sender: self.add_invite(ident, "irc", "channel", channel,
                                                                                          sender, channel),
                                        keep=lambda channel: self.keeps(ident, channel))
        return xmpp_client.XmppClient(account, lambda *args: self.accept_xmpp(*args, account=ident), rooms,
                                      on_event=lambda *args: self.accept_xmpp_event(*args, account=ident))

    def erase(self):
        """Erase everything: stops every chat connection and forgets recent calls, before the data behind them goes."""
        for ident, entry in list(self.clients.items()):
            try:
                entry[1].stop()
            except Exception:
                pass
            self.clients.pop(ident, None)
        with self.call_lock:
            self.calls.clear()

    def guard(self, ident, secure):
        try:
            secure.run()
        except Exception:
            # A failed encrypted session falls back to the plain Matrix poller until the next account change.
            entry = self.clients.get(ident)
            if entry and entry[1] is secure:
                poller = Poller(lambda: self.receive_matrix(ident), 5)
                self.clients[ident] = (entry[0], poller)
                threading.Thread(target=poller.run, daemon=True, name="protocol-matrix").start()

    def accept_secure(self, room_id, event, account=None):
        account = account or self.default_account("matrix")
        route = next((r for r in self.routes if r.get("via") == "matrix" and r["target"] == room_id
                      and r["account"] == account), None)
        if route is not None:
            try:
                self.accept_matrix(route, event)
            except Exception:
                pass

    def secure(self, account=None):
        """The encrypted Matrix session of [account] (default: the one in use), or None."""
        entry = self.clients.get(account or self.current_account("matrix"))
        return entry[1] if entry and isinstance(entry[1], matrix_e2ee.MatrixE2ee) else None

    def client(self, protocol):
        entry = self.clients.get(self.current_account(protocol))
        if entry is None or isinstance(entry[1], Poller):
            raise ConnectionError(protocol.upper() + " isn’t connected. Check the account on FluidMessages Web.")
        return entry[1]

    # Servers: each IRC network, Matrix account, and XMPP account is a "server" with its
    # channels or rooms and its direct messages. Browsing uses each protocol's own directory.

    def servers(self):
        """Every server connection, with its chats split into channels/rooms and direct messages."""
        routes = self.routes
        output = []
        for account in self.accounts.redacted():
            if "error" in account or account["protocol"] not in ("irc", "matrix", "xmpp"):
                continue
            fields = account["fields"]
            host = {"irc": fields.get("server", ""), "matrix": urlsplit(fields.get("homeserver", "")).hostname or "",
                    "xmpp": account["name"].partition("@")[2]}[account["protocol"]]
            mine = [r for r in routes if r["account"] == account["id"]]
            output.append({"id": account["id"], "protocol": account["protocol"], "name": account["name"], "host": host,
                           "source": account["source"],
                           "channels": [r["address"] for r in mine if r.get("group")],
                           "direct": [r["address"] for r in mine if not r.get("group")],
                           "spaces": self.cached_spaces(account["id"]) if account["protocol"] == "matrix" else []})
        return output

    def server_account(self, data, protocols=("irc", "matrix", "xmpp")):
        ident = data.get("account")
        entry = next((e for e in self.accounts.all() if e["id"] == ident), None) if isinstance(ident, str) else None
        if entry is None or entry["protocol"] not in protocols:
            raise ValueError("Choose a server first")
        return entry

    def cached_spaces(self, ident):
        cached = json.loads(self.state("spaces:" + ident) or "null")
        return cached["spaces"] if cached else []

    def spaces(self, data):
        """Matrix Spaces the account has joined, with their rooms (the space hierarchy), cached for ten minutes."""
        entry = self.server_account(data, ("matrix",))
        cached = json.loads(self.state("spaces:" + entry["id"]) or "null")
        if cached and self.time() - cached["at"] < 600_000 and not data.get("refresh"):
            return {"spaces": cached["spaces"]}
        spaces = []
        with self.using(entry["id"]):
            joined = (self.matrix_request("joined_rooms") or {}).get("joined_rooms", [])
            for room in [room for room in joined if isinstance(room, str) and ROOM.fullmatch(room)][:150]:
                create = self.matrix_request("rooms/" + quote(room, safe="") + "/state/m.room.create", allow_missing=True) or {}
                if create.get("type") != "m.space":
                    continue
                tree = self.matrix_request("rooms/" + quote(room, safe="") + "/hierarchy?max_depth=1&limit=50",
                                           allow_missing=True) or {}
                rooms = tree.get("rooms") if isinstance(tree.get("rooms"), list) else []
                top = next((item for item in rooms if item.get("room_id") == room), {})
                children = [{"target": item["room_id"], "name": bounded(item.get("name") or item.get("canonical_alias")
                                                                        or item["room_id"], 120),
                             "topic": bounded(item.get("topic"), 300), "members": item.get("num_joined_members", 0)
                             if type(item.get("num_joined_members")) is int else 0}
                            for item in rooms if isinstance(item, dict) and isinstance(item.get("room_id"), str)
                            and item["room_id"] != room and ROOM.fullmatch(item["room_id"]) and item.get("room_type") != "m.space"]
                spaces.append({"id": room, "name": bounded(top.get("name") or top.get("canonical_alias") or room, 120),
                               "rooms": children})
                if len(spaces) >= 30:
                    break
        self.save_state("spaces:" + entry["id"], json.dumps({"at": self.time(), "spaces": spaces}))
        return {"spaces": spaces}

    def browse(self, data):
        """Public channels or rooms on a server: IRC LIST, the Matrix room directory, or XMPP disco#items."""
        entry = self.server_account(data)
        search = data.get("search", "")
        limit = data.get("limit", 50)
        if not isinstance(search, str) or len(search) > 100 or type(limit) is not int or not 1 <= limit <= 200:
            raise ValueError("Invalid search")
        with self.using(entry["id"]):
            if entry["protocol"] == "irc":
                rooms = self.client("irc").list_channels(search, limit)
            elif entry["protocol"] == "matrix":
                body = {"limit": limit, **({"filter": {"generic_search_term": search.strip()}} if search.strip() else {})}
                found = self.matrix_request("publicRooms", body, method="POST") or {}
                rooms = [{"target": item["room_id"], "alias": bounded(item.get("canonical_alias"), 255),
                          "name": bounded(item.get("name") or item.get("canonical_alias") or item["room_id"], 120),
                          "topic": bounded(item.get("topic"), 300),
                          "members": item["num_joined_members"] if type(item.get("num_joined_members")) is int else 0}
                         for item in found.get("chunk", []) if isinstance(item, dict) and isinstance(item.get("room_id"), str)
                         and ROOM.fullmatch(item["room_id"])][:limit]
            else:
                service = data.get("service")
                client = self.client("xmpp")
                if not service:
                    return {"rooms": [], "services": client.muc_services()}
                if not isinstance(service, str) or not re.fullmatch(r"[A-Za-z0-9](?:[A-Za-z0-9.-]{0,251}[A-Za-z0-9])?", service):
                    raise ValueError("Enter a group chat service like conference.example.org")
                rooms = client.browse(service, search, limit)
        joined = {(r["target"].lower()) for r in self.routes if r["account"] == entry["id"]}
        for room in rooms:
            room["joined"] = room["target"].lower() in joined
        return {"rooms": rooms}

    def join(self, db, data):
        """Joins a channel or room on a server and adds it to the inbox under that server."""
        entry = self.server_account(data)
        target, title = data.get("target"), data.get("title")
        if not isinstance(target, str) or not 0 < len(target.strip()) <= 300:
            raise ValueError("Enter a channel or room")
        target = target.strip()
        protocol = entry["protocol"]
        if protocol == "irc":
            if target[:1] not in "#&":
                target = "#" + target
            if not CHANNEL.fullmatch(target):
                raise ValueError("Enter a channel like #fluidmessages")
        elif protocol == "matrix":
            if not re.fullmatch(r"[!#][^\s/\x00-\x1f]{1,250}:[A-Za-z0-9.:-]{1,255}", target):
                raise ValueError("Enter a room address like #room:example.org")
            with self.using(entry["id"]):
                joined = self.matrix_request("join/" + quote(target, safe=""), {}, method="POST") or {}
            if not isinstance(joined.get("room_id"), str) or not ROOM.fullmatch(joined["room_id"]):
                raise ValueError("The homeserver didn’t let you join that room")
            if not title and not target.startswith("#"):
                # Joined by room ID: show the room's own name rather than "!abc:example.org".
                with self.using(entry["id"]):
                    named = self.matrix_request("rooms/" + quote(joined["room_id"], safe="") + "/state/m.room.name",
                                                allow_missing=True) or {}
                title = named.get("name") if isinstance(named.get("name"), str) else ""
            title = title or (target if target.startswith("#") else "")
            target = joined["room_id"]
        else:
            if not JID.fullmatch(target):
                raise ValueError("Enter a group chat address like room@conference.example.org")
            target = target.lower()
            # A group chat's name is its address before the @ unless the directory gave a better one.
            title = title or target.split("@", 1)[0]
        # Joining a channel or room you're already in (on this account) opens it rather than adding it again.
        # An invitation still waiting (message_requests.note_invite) isn't joined yet.
        existing = next((r for r in self.routes if r["account"] == entry["id"] and r.get("via", r["protocol"]) == protocol
                         and not r.get("invite") and target_key(protocol, r["target"]) == target_key(protocol, target)), None)
        if existing:
            return {"route": {key: value for key, value in existing.items() if key != "via"}, "joined": False}
        address = "room-" + hashlib.sha256((entry["id"] + "\n" + target.lower()).encode()).hexdigest()[:16]
        result = self.add_route(db, {"address": address, "protocol": protocol, "target": target, "group": True,
                                     "title": bounded(title, 120) or target, "account": entry["id"]})
        result["joined"] = True
        return result

    def leave(self, db, data):
        """Leaves a channel or room on its server and takes it out of the inbox. Its earlier messages stay."""
        address, protocol = data.get("address"), data.get("protocol")
        route = self.route(address, protocol) if isinstance(address, str) and isinstance(protocol, str) else None
        if route is None or not route.get("group"):
            raise ValueError("Only channels and rooms can be left")
        if any(r["address"] == address and r["protocol"] == protocol for r in self.legacy_routes):
            raise ValueError("This link is set in FluidMessages Web’s configuration file")
        stored = db.execute("SELECT 1 FROM protocol_routes WHERE address=? AND protocol=?", (address, protocol)).fetchone()
        if not stored:
            # A channel listed on the IRC account itself: take it off the account's list.
            entry = next((e for e in self.accounts.all() if e["id"] == route["account"]), None)
            if entry is None or entry["source"] != "phone":
                raise ValueError("This channel is set in Docker Compose. Remove it there.")
            values = dict(entry["values"])
            values["channels"] = [c for c in values.get("channels", []) if c.lower() != route["target"].lower()]
            self.accounts.save("irc", values, entry["id"])
        if route.get("via") == "matrix":
            with self.using(route["account"]):
                self.matrix_request("rooms/" + quote(route["target"], safe="") + "/leave", {}, method="POST")
        db.execute("DELETE FROM protocol_routes WHERE address=? AND protocol=?", (address, protocol))
        # Once this is saved, IRC parts the channel and XMPP leaves the group chat, without reconnecting.
        threading.Timer(0.5, self.refresh_clients).start()
        return {"left": True}

    # IRC

    def irc_route(self, conversation, account=None):
        account = account or self.default_account("irc")
        for route in self.routes:
            if route["protocol"] == "irc" and route.get("via") == "irc" and route["account"] == account \
                    and route["target"].lower() == conversation.lower():
                return route
        return None

    def channel_state(self, channel, state, account=None):
        route = self.irc_route(channel, account)
        if route is not None:
            self.save_state("channel:" + route["address"], json.dumps({"name": channel, **state}))

    def accept_irc(self, conversation, nick, text, tags, date, outgoing, account=None):
        if outgoing:
            return
        route = self.irc_route(conversation, account)
        # A direct message from a person you haven't linked starts a chat of its own, marked as a message request.
        # Network services (NickServ and the like), notices, and servers are left out (message_requests.py) and go
        # through irc_fallback below, which labels them automated or puts them in the server chat.
        if route is None and conversation != irc_client.SERVER and conversation[:1] not in "#&$" and not tags.get("event") \
                and not tags.get("service") and not tags.get("notice"):
            message_requests.inbound_route(self, "irc", conversation, account)
            route = self.irc_route(conversation, account)
        if route is None:
            # Someone new (a person or a bot such as NickServ) gets a direct chat; the rest goes to the server chat.
            route, text = self.irc_fallback(conversation, nick, text, tags, account)
            if route is None:
                return
        msgid = tags.get("msgid", "")
        stable = self.stable(route, msgid or f"{conversation}:{nick}:{date}:{text}")
        react, reply = tags.get("+draft/react"), tags.get("+draft/reply")
        target = self.find(route["address"], "irc", "irc_msgid", reply) if reply else None
        if react:
            if target is None or not is_emoji(react):
                return
            record = self.message(route, "irc-" + stable, reaction_text(react, target["body"]), False, date, author=nick)
            record["reaction_target"] = target["id"]
        else:
            body = reply_text(target["body"], text) if target else text
            record = self.message(route, "irc-" + stable, body, False, date, author=nick)
            if target:
                record["reply_target"] = target["id"]
            if tags.get("event"):
                self.mark_event(record, tags["event"])
            if tags.get("mention"):
                record["mention"] = True
            if tags.get("notice"):
                record["notice"] = True
        if route.get("server_chat"):
            record["read"] = 1  # Connection notices come with every reconnect; they never leave an unread dot.
        record["irc_msgid"] = msgid
        self.add_incoming(record)

    def send_irc(self, route, message):
        relation = message.get("relation") or {}
        if message.get("attachment"):
            raise ValueError("IRC has no file transfer. Send the file with another protocol.")
        if route["target"] == irc_client.SERVER:
            raise ValueError("This is the network’s server chat. Type a command here, such as /join, /msg, or /quote.")
        client = self.client("irc")
        client.ensure_joined(route["target"], wait=5)  # A channel that isn't kept joined rejoins before you write.
        reply = relation.get("irc_msgid")
        tagged = reply and "message-tags" in client.caps
        if revises(relation):
            client.send(route["target"], message["body"])  # IRC has no way to take a reaction back.
        elif relation.get("kind") == "reaction":
            if tagged:
                client.send(route["target"], "", reply=reply, react=relation["emoji"])
            else:
                client.send(route["target"], message["body"])
        else:
            client.send(route["target"], message.get("text") if tagged and relation.get("kind") == "reply" else message["body"],
                        reply=reply if tagged else None, plain=bool(message.get("literal")))
        return {}

    # XMPP

    def xmpp_route(self, conversation, account=None):
        account = account or self.default_account("xmpp")
        for route in self.routes:
            if route["protocol"] == "xmpp" and route.get("via") == "xmpp" and route["account"] == account \
                    and route["target"] == conversation.lower():
                return route
        return None

    def accept_xmpp(self, conversation, author, text, message_id, date, outgoing, extra, account=None):
        route = self.xmpp_route(conversation, account)
        if route is None and not outgoing and author == conversation and text:
            # A one-to-one chat from an address you haven't linked: a message request unless it's in your roster.
            message_requests.inbound_route(self, "xmpp", conversation, account, known=extra.get("roster") is True)
            route = self.xmpp_route(conversation, account)
        if route is None:
            return
        if (outgoing or extra.get("archived")) and self.find(route["address"], "xmpp", "xmpp_id", message_id):
            return  # Our own message reflected back by a group or carbon, or one the archive catch-up already gave.
        stable = self.stable(route, f"{conversation}:{message_id}:{date}")
        if "retract" in extra:
            target = self.find(route["address"], "xmpp", "xmpp_id", extra["retract"])
            if target is not None and (outgoing or target.get("author") == author):
                with closing(self.connect()) as db, db:
                    db.execute("DELETE FROM protocol_messages WHERE id=?", (target["id"],))
            return
        if "reaction" in extra:
            target = self.find(route["address"], "xmpp", "xmpp_id", extra["reaction_to"])
            if target is None:
                return
            if not extra["reaction"]:
                # An empty set takes the sender's reaction back.
                earlier = self.earlier_reaction(route, target["id"], author, outgoing)
                if not earlier:
                    return
                record = self.message(route, "xmpp-" + stable, removed_text(earlier, target["body"]), outgoing, date,
                                      author=author)
            elif not is_emoji(extra["reaction"]):
                return
            else:
                record = self.message(route, "xmpp-" + stable, reaction_text(extra["reaction"], target["body"]), outgoing,
                                      date, author=author)
                record["reaction"] = extra["reaction"]
            record["reaction_target"] = target["id"]
        else:
            target = self.find(route["address"], "xmpp", "xmpp_id", extra.get("reply_to")) if extra.get("reply_to") else None
            body = reply_text(target["body"], text) if target else text
            record = self.message(route, "xmpp-" + stable, body, outgoing, date, author=author)
            if target:
                record["reply_target"] = target["id"]
            if extra.get("file_url"):
                self.fetch_shared_file(record, extra["file_url"])
        record["xmpp_id"] = message_id
        self.add_incoming(record)

    def fetch_shared_file(self, record, url):
        """Downloads an HTTP File Upload link (HTTPS only, up to 25 MB) so the phone gets the file itself."""
        parts = urlsplit(url)
        if parts.scheme != "https" or not parts.hostname:
            return
        try:
            with self.http.open(Request(url), timeout=60) as response:
                data = response.read(MAX_FILE + 1)
                mime = (response.headers.get_content_type() or "application/octet-stream").lower()
        except Exception:
            return
        if len(data) > MAX_FILE:
            return
        name = safe_name(Path(parts.path).name)
        record["attachment"] = {"kind": kind_for(mime), "name": name, "mime": mime if MIME.fullmatch(mime) else
                                "application/octet-stream", "size": len(data), "duration": 0}
        record["body"] = record["body"].replace(url, "").strip() or name
        self.file_path(record["id"]).write_bytes(data)

    def earlier_reaction(self, route, target, author, outgoing):
        """The emoji this sender last reacted with on a message, so a removal can say which one."""
        with closing(self.connect()) as db:
            for row in db.execute("SELECT payload FROM protocol_messages ORDER BY date DESC, rowid DESC LIMIT 1500"):
                item = json.loads(row[0])
                if item["address"] == route["address"] and item.get("reaction_target") == target \
                        and (item["type"] == 2 if outgoing else item.get("author") == author):
                    return item.get("reaction", "")
        return ""

    def send_xmpp(self, route, message):
        relation = message.get("relation") or {}
        attachment = message.get("attachment")
        file = (str(self.file_path(message["id"])), attachment["name"], attachment["mime"]) if attachment else None
        client = self.client("xmpp")
        reply = relation.get("xmpp_id")
        author = None
        if reply and route.get("group") and relation.get("author"):
            author = route["target"] + "/" + relation["author"]
        if relation.get("kind") == "reaction" and reply:
            # XEP-0444 replaces your whole set: none takes the reaction back, one emoji changes it.
            react = [] if relation.get("removed") else [relation["emoji"]]
            sent = client.send(route["target"], "", route.get("group"), reply=reply, react=react)
        else:
            text = message.get("text") if reply and relation.get("kind") == "reply" else message["body"]
            if attachment and not message.get("text"):
                text = ""
            sent = client.send(route["target"], text, route.get("group"), reply=reply, reply_author=author, file=file)
        return {"xmpp_id": sent}

    # Matrix direct rooms

    def matrix_direct_room(self, user):
        """Finds the existing direct room with a Matrix user, or creates one, like Element's "Start chat"."""
        me = quote(self.matrix["user_id"], safe="")
        direct = self.matrix_request("user/" + me + "/account_data/m.direct", allow_missing=True) or {}
        for room in direct.get(user, []):
            if isinstance(room, str) and ROOM.fullmatch(room):
                return room
        room = self.matrix_request("createRoom", {"is_direct": True, "invite": [user], "preset": "trusted_private_chat"},
                                   method="POST")["room_id"]
        direct.setdefault(user, []).append(room)
        self.matrix_request("user/" + me + "/account_data/m.direct", direct, method="PUT")
        return room
