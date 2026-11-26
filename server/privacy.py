"""Privacy and security for FluidMessages Web, adapted from the phone (Privacy.java, MainActivity's privacy settings).

- Private folder. Chats moved here leave the inbox, search, and chimes. The server keeps the list of private chats
  (protocol_state "web_private") and a salted scrypt hash of the private folder passcode (auth.py). Until this
  browser session opens the folder with the passcode, every answer that could name a private chat is scrubbed
  here: its messages, routes, folders, codes, timers, wallpapers, scheduled messages, and merge items. Opening lasts
  for the session or until it's locked again. The phone's own private folder never syncs here (protocol_gateway
  hides its chats), and web private chats stay out of the phone↔web merge (sync_merge.py).
  Whole services can be hidden too (Hide whole services, "web_private_services"): every chat on them counts as
  private. "Ask for the passcode to close it" ("web_private_close", on unless turned off) is the browser's to ask.
- Decoy mode. Signing in with the decoy password gives a decoy session (auth.py), and Quick hide switches the
  current one. A decoy session never reaches a real endpoint: main.Handler.authorize sends every request here
  (DecoyWorld), which answers like a fresh FluidMessages Web with a phone connected and only pretend chats.
  Texts "sent" there stay in that session's memory, like the phone's decoy chats, where nothing is ever sent.
  Real chats that stay visible ("web_decoy_allowed") show there as they are, read from the real store by main.py
  (DecoyWorld.real); private chats never do, and what's "sent" in them stays in the session's memory as well.
- Emergency erase passcode. Typed at sign-in, into the private folder prompt, or into Show your real inbox, it
  erases everything with Erase everything's own path (main.erase_everything) and leaves that browser in a decoy
  session, so it looks like a normal, harmless inbox.

The passcodes are checked by auth.Auth, rate-limited with the password, and never logged.
"""
import hashlib
import json
import re
import threading
import time

PRIVATE_KEY, DECOYS_KEY = "web_private", "web_decoys"
SERVICES_KEY, CLOSE_KEY, ALLOWED_KEY = "web_private_services", "web_private_close", "web_decoy_allowed"
# The services a private folder can hide whole, as on the phone's Hide whole services: texts and each internet service.
SERVICES = ("sms", "matrix", "xmpp", "email", "irc")
TEXTS = ("sms", "mms", "rcs", "")
MAX_ALLOWED = 60
MAX_PRIVATE, MAX_DECOY_CHATS, MAX_DECOY_LINES = 2000, 12, 30
# Fictional chats for decoy mode, in the phone's decoy style: a name and a few ordinary lines, each from them or
# from you. 555-01xx numbers are reserved for fiction.
DEFAULT_DECOYS = [
    {"name": "Mom", "lines": [
        {"me": False, "text": "Did you get home ok?"},
        {"me": True, "text": "Yep, just walked in"},
        {"me": False, "text": "Good. Don’t forget dinner on Sunday"},
        {"me": True, "text": "I’ll bring dessert 🍰"}]},
    {"name": "Chris Delgado", "lines": [
        {"me": False, "text": "Still on for the game Thursday?"},
        {"me": True, "text": "Yes! I’ll grab snacks"},
        {"me": False, "text": "Perfect, 7 at my place"}]},
    {"name": "Priya", "lines": [
        {"me": False, "text": "Can you send me that recipe?"},
        {"me": True, "text": "The lemon pasta one?"},
        {"me": False, "text": "Yes that one 😋"},
        {"me": True, "text": "Sending it tonight"}]},
    {"name": "Sunny Side Dental", "lines": [
        {"me": False, "text": "Reminder: your cleaning is Tuesday at 2:30 PM. Reply C to confirm."},
        {"me": True, "text": "C"},
        {"me": False, "text": "Thanks, you’re confirmed. See you soon!"}]},
]
SHORT_CODE = "72240"


# Private chats

def private_addresses(db):
    row = db.execute("SELECT value FROM protocol_state WHERE key=?", (PRIVATE_KEY,)).fetchone()
    value = json.loads(row[0]) if row else []
    return [item for item in value if isinstance(item, str)] if isinstance(value, list) else []


def save_private(db, addresses):
    if addresses is None:
        db.execute("DELETE FROM protocol_state WHERE key=?", (PRIVATE_KEY,))
    else:
        db.execute("INSERT OR REPLACE INTO protocol_state VALUES (?, ?)", (PRIVATE_KEY, json.dumps(list(dict.fromkeys(addresses))[:MAX_PRIVATE])))


def _state(db, key, default):
    row = db.execute("SELECT value FROM protocol_state WHERE key=?", (key,)).fetchone()
    return json.loads(row[0]) if row else default


def _put(db, key, value):
    if value is None:
        db.execute("DELETE FROM protocol_state WHERE key=?", (key,))
    else:
        db.execute("INSERT OR REPLACE INTO protocol_state VALUES (?, ?)", (key, json.dumps(value)))


def hidden_services(db):
    value = _state(db, SERVICES_KEY, [])
    return [item for item in SERVICES if isinstance(value, list) and item in value]


def save_hidden_services(db, services):
    if services is not None and (not isinstance(services, list) or any(item not in SERVICES for item in services)):
        raise ValueError("Choose texts, Matrix, XMPP, Email, or IRC")
    _put(db, SERVICES_KEY, [item for item in SERVICES if item in services] if services else None)


def service_addresses(db, services, routes):
    """Every chat on a hidden service: its route's address, its messages' addresses, and for texts, the phone's."""
    if not services:
        return []
    found = []
    internet = {item for item in services if item != "sms"}
    found += [route["address"] for route in routes if route.get("protocol") in internet and isinstance(route.get("address"), str)]
    for row in db.execute("SELECT payload FROM protocol_messages"):
        message = json.loads(row[0])
        protocol = message.get("protocol") or ""
        if protocol in internet or "sms" in services and protocol in TEXTS:
            found.append(message.get("address"))
    if "sms" in services:
        row = db.execute("SELECT content FROM inbox WHERE id=1").fetchone()
        for message in json.loads(row[0]) if row else []:
            if isinstance(message, dict) and (message.get("protocol") or "") in TEXTS:
                found.append(message.get("address"))
    return list(dict.fromkeys(item for item in found if isinstance(item, str) and item))


def close_needs_code(db):
    """Leaving the open private folder for another folder asks for the passcode again (the phone's privateCloseCode)."""
    return _state(db, CLOSE_KEY, True) is not False


def save_close_needs_code(db, on):
    if type(on) is not bool:
        raise ValueError("Choose on or off")
    _put(db, CLOSE_KEY, None if on else False)


def decoy_allowed(db):
    value = _state(db, ALLOWED_KEY, [])
    return [item for item in value if isinstance(item, str)] if isinstance(value, list) else []


def save_decoy_allowed(db, addresses, is_private):
    """Real chats that stay visible in decoy mode. A private chat is never one of them."""
    if not isinstance(addresses, list) or len(addresses) > MAX_ALLOWED:
        raise ValueError("Choose up to %d chats" % MAX_ALLOWED)
    kept = [clean_address(item) for item in addresses]
    kept = [item for item in dict.fromkeys(kept) if not (is_private and is_private(item))]
    _put(db, ALLOWED_KEY, kept or None)
    return kept


def clean_address(value):
    if not isinstance(value, str) or not 0 < len(value) <= 300 or any(ord(ch) < 32 for ch in value):
        raise ValueError("Choose a chat")
    return value


def _digits(value):
    """A phone number's last ten digits, so "+1 202 555 0123" and "2025550123" are the same chat (like the phone's
    PhoneNumberUtils.compare). None for anything that isn't a phone number."""
    if not re.fullmatch(r"\+?[0-9 ().-]{7,40}", value):
        return None
    digits = re.sub(r"\D", "", value)
    return digits[-10:] if len(digits) >= 7 else None


def matcher(addresses):
    """is_private(text): whether text names a private chat, as an address or as a merge item ("folder:<address>")."""
    exact = set(addresses)
    phones = {number for number in map(_digits, addresses) if number}
    if not exact:
        return None

    def named(value):
        return value in exact or bool(phones) and _digits(value) in phones

    def is_private(value):
        if not isinstance(value, str):
            return False
        if named(value):
            return True
        kind, _, rest = value.partition(":")
        return bool(rest) and kind.isalpha() and named(rest)
    return is_private


def scrub(value, is_private):
    """A copy of an answer with every trace of a private chat removed: list entries and dict keys naming one, and any
    object whose address (or merge key) does. Used while this session hasn't opened the private folder."""
    if is_private is None:
        return value
    names = lambda item: isinstance(item, str) and is_private(item) or isinstance(item, dict) and (
        is_private(item.get("address")) or is_private(item.get("key")))
    if isinstance(value, dict):
        return {key: scrub(item, is_private) for key, item in value.items() if not is_private(key) and not names(item)}
    if isinstance(value, list):
        return [scrub(item, is_private) for item in value if not names(item)]
    return value


def keep_private(old, new, is_private):
    """What a locked session saves never drops what it couldn't see: private chats' folders, codes, wallpapers, and
    timers are carried over from what's stored."""
    if is_private is None:
        return new
    if isinstance(old, dict) and isinstance(new, dict):
        merged = dict(new)
        for key, item in old.items():
            if is_private(key):
                merged[key] = item
            elif key in merged:
                merged[key] = keep_private(item, merged[key], is_private)
        return merged
    if isinstance(old, list) and isinstance(new, list):
        return new + [item for item in old if isinstance(item, str) and is_private(item) and item not in new]
    return new


# Decoy chats

def clean_decoys(value):
    if not isinstance(value, list) or len(value) > MAX_DECOY_CHATS:
        raise ValueError("Up to 12 pretend chats")
    chats, names = [], set()
    for chat in value:
        if not isinstance(chat, dict):
            raise ValueError("Invalid pretend chat")
        name = chat.get("name").strip() if isinstance(chat.get("name"), str) else ""
        if not name or len(name) > 60:
            raise ValueError("Add a name")
        if name.lower() in names:
            raise ValueError("Each pretend chat needs its own name")
        names.add(name.lower())
        lines = chat.get("lines")
        if not isinstance(lines, list) or len(lines) > MAX_DECOY_LINES:
            raise ValueError("Invalid pretend chat")
        kept = [{"me": line.get("me") is True, "text": line["text"].strip()[:1000]} for line in lines
                if isinstance(line, dict) and isinstance(line.get("text"), str) and line["text"].strip()]
        if not kept:
            raise ValueError("Add at least one message")
        chats.append({"name": name, "lines": kept})
    return chats


def decoy_chats(db):
    row = db.execute("SELECT value FROM protocol_state WHERE key=?", (DECOYS_KEY,)).fetchone()
    try:
        return clean_decoys(json.loads(row[0])) if row else [dict(chat) for chat in DEFAULT_DECOYS]
    except ValueError:
        return [dict(chat) for chat in DEFAULT_DECOYS]


def save_decoys(db, chats):
    if chats is None:
        db.execute("DELETE FROM protocol_state WHERE key=?", (DECOYS_KEY,))
    else:
        db.execute("INSERT OR REPLACE INTO protocol_state VALUES (?, ?)", (DECOYS_KEY, json.dumps(clean_decoys(chats))))


def decoy_address(index, name):
    """Fictional numbers in 555-0160…0179; a business-sounding name gets a short code, like a real reminder."""
    if re.search(r"\b(dental|clinic|pharmacy|bank|alerts?|delivery|office)\b", name, re.I):
        return str(int(SHORT_CODE) + index)
    return "+1202555%04d" % (160 + index)


class DecoyWorld:
    """What a decoy session sees: a phone that synced a few minutes ago, the default look, no accounts, and the
    pretend chats. Changes are kept in this session's memory only, and refused where they'd be real."""

    REFUSED = (503, {"error": "Something went wrong. Try again."})

    def __init__(self, clock=time.time):
        self.clock = clock
        # real(db): the real chats that stay visible in decoy mode, as inbox rows (main.decoy_real_messages).
        self.real = None
        self.lock = threading.Lock()
        self.sessions = {}

    def _state(self, key):
        with self.lock:
            if key not in self.sessions:
                if len(self.sessions) > 200:
                    self.sessions.pop(next(iter(self.sessions)))
                now = int(self.clock() * 1000)
                self.sessions[key] = {"started": now, "synced": now - 4 * 60_000, "sent": [], "look": None, "prefs": None, "job": 100}
            return self.sessions[key]

    def forget(self, key):
        with self.lock:
            self.sessions.pop(key, None)

    def messages(self, db, key):
        state = self._state(key)
        start = state["started"]
        rows = []
        for index, chat in enumerate(decoy_chats(db)):
            address = decoy_address(index, chat["name"])
            # Each chat's last line is a little older than the one above it, the way an inbox looks.
            last = start - (25 + index * 140) * 60_000
            for position, line in enumerate(chat["lines"]):
                date = last - (len(chat["lines"]) - 1 - position) * 4 * 60_000
                rows.append({"id": "d%x%x" % (index, position), "address": address, "title": chat["name"], "body": line["text"],
                             "date": date, "type": 2 if line["me"] else 1, "read": 1, "protocol": "sms"})
        if self.real:
            rows += self.real(db)
        titles = {row["address"]: row.get("title", "") for row in rows}
        for number, sent in enumerate(state["sent"]):
            rows.append({"id": "s%x" % number, "address": sent["address"], "title": titles.get(sent["address"], ""), "body": sent["body"],
                         "date": sent["date"], "type": 2, "read": 1, "protocol": sent.get("protocol", "sms")})
        rows.sort(key=lambda row: row["date"], reverse=True)
        return rows

    def look(self, key, defaults):
        state = self._state(key)
        return state["look"] or defaults["web_appearance"], state["prefs"] or defaults["prefs"]

    def settings_view(self, key, defaults):
        look, prefs = self.look(key, defaults)
        state = self._state(key)
        return {**json.loads(json.dumps(defaults["settings"])), "web_appearance": look, "prefs": prefs, "welcomed": True,
                "web_chats": {"pinned": [], "archived": [], "blocked": [], "muted": [], "locks": {}},
                "phone": {"paired": True, "last_sync": state["synced"]}, **self.merge_snapshot()}

    @staticmethod
    def merge_snapshot():
        return {"merge": {"active": False, "pending": None}, "web_extras": None,
                "stickers_rev": hashlib.sha256(b"[]").hexdigest()[:16]}

    @staticmethod
    def status():
        """Privacy and security as a decoy session sees it. Like the phone in decoy mode, the private folder and decoy
        mode stay out of sight, so nobody looking can tell anything is hidden."""
        return {"password": True, "private": None, "decoy": None, "distress": {"set": False}, "quick_hide": False}

    def get(self, db, key, path, query, defaults, context):
        """(status, payload) for a GET from a decoy session."""
        if path == "/api/inbox":
            look, prefs = self.look(key, defaults)
            return 200, {"messages": self.messages(db, key), "routes": [], "enabled": defaults["enabled"], "channels": {},
                         "accounts": [], "updated": self._state(key)["synced"], "appearance": {}, "follow_phone": True,
                         "web_appearance": look, "prefs": prefs, "welcomed": True,
                         "web_chats": {"pinned": [], "archived": [], "blocked": [], "muted": [], "locks": {}}, **self.merge_snapshot()}
        if path == "/api/jobs":
            return 200, []
        if path == "/api/settings":
            return 200, self.settings_view(key, defaults)
        if path == "/api/auth/security":
            return 200, {"owner": True, "sessions": 1, "pairing": {"compose": False, "web": False, "created": None},
                         "https": context["https"], "local": False, "here": context["here"], "local_only": context["local_only"],
                         "reach": context.get("reach")}
        if path == "/api/privacy":
            return 200, self.status()
        if path == "/api/protocols/accounts":
            return 200, {"accounts": [], "xmpp": context["xmpp"]}
        if path == "/api/backups":
            return 200, {"targets": []}
        if path == "/api/stickers":
            return 200, {"stickers": []}
        if path == "/api/contacts":
            return 200, {"supported": 1, "contacts": [], "revision": "0" * 16}
        if path == "/api/merge":
            return 200, {"supported": 1, "pair": None, "revision": "0" * 24, "follow_phone": True, "web": {}, "stamps": {},
                         "baseline": None, "decisions": {}, "pending": None, "resolved": None}
        if path == "/api/calls" and query.startswith("since="):
            return 200, {"events": [], "next": 0}
        if path == "/api/calls/ice":
            return 200, {"iceServers": []}
        if path == "/api/gifs":
            return 200, {"results": []}
        if path == "/api/preview":
            return 200, {"preview": None}
        if path == "/api/picture":
            return 404, {"error": "That picture isn't available"}
        if path == "/api/protocols/file":
            return 404, {"error": "Attachment not found"}
        if path == "/api/stickers/file":
            return 404, {"error": "Sticker not found"}
        if path.startswith("/api/backups/"):
            return 404, {"error": "Backup destination is not configured"}
        return 404, {"error": "Unknown endpoint"}

    def post(self, db, key, path, data, defaults, clean):
        """(status, payload) for a POST from a decoy session. Nothing reaches the real server or the phone."""
        state = self._state(key)
        now = int(self.clock() * 1000)
        if path == "/api/schedule":
            address, body = data.get("address"), data.get("body")
            if not isinstance(address, str) or not re.fullmatch(r"(?=.*[0-9A-Za-z])[+0-9A-Za-z*#(). -]{1,40}", address):
                return 400, {"error": "Enter a valid phone number"}
            if not isinstance(body, str) or not body.strip() or len(body) > 1600:
                return 400, {"error": "Enter 1–1,600 characters"}
            with self.lock:
                state["sent"] = (state["sent"] + [{"address": address, "body": body, "date": now}])[-200:]
                state["synced"] = now
                state["job"] += 1
                return 200, {"id": state["job"]}
        if path == "/api/protocols/send":
            # Only in a real chat that stays visible; it stays in this session's memory, like the decoy texts.
            address, protocol, body = data.get("address"), data.get("protocol"), data.get("body")
            if not isinstance(address, str) or not isinstance(body, str) or not body.strip() or len(body) > 8000 \
                    or protocol not in ("matrix", "xmpp", "email", "irc") or not any(
                        row["address"] == address and row.get("protocol") == protocol for row in (self.real(db) if self.real else [])):
                return self.REFUSED
            with self.lock:
                state["sent"] = (state["sent"] + [{"address": address, "body": body, "date": now, "protocol": protocol}])[-200:]
                return 200, {"message": {"address": address, "protocol": protocol, "body": body, "date": now, "type": 2}}
        if path == "/api/call":
            with self.lock:
                state["job"] += 1
                return 200, {"id": state["job"]}
        if path == "/api/cancel":
            return 400, {"error": "This message is no longer scheduled"}
        if path in ("/api/protocols/read", "/api/protocols"):
            return 200, {"ok": True} if path.endswith("read") else {"messages": [], "routes": [], "enabled": defaults["enabled"]}
        if path == "/api/settings":
            try:
                if "web_appearance" in data:
                    state["look"] = clean["appearance"](data["web_appearance"])
                if "prefs" in data:
                    state["prefs"] = clean["prefs"](data["prefs"], self.look(key, defaults)[1])
                if data.get("reset") is True:
                    state["look"] = state["prefs"] = None
            except (ValueError, TypeError) as error:
                return 400, {"error": str(error)}
            return 200, self.settings_view(key, defaults)
        return self.REFUSED
