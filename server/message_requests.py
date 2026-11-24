"""Message requests and automated senders, FluidMessages Web's side (the phone's is MessageRequests.kt).

A request is an incoming one-to-one conversation from someone you don't know. The browser (requests.js) and the phone
decide which chats are requests with the same rule; this module keeps what both of them share:

- The settings (Settings → Privacy and security → Requests and spam), kept in the web's prefs.
- Your decisions per address, kept in web_chats.requests: "accepted" (it moves to the inbox for good), or
  "deleted:<epoch ms>" (everything up to then is gone; a newer message makes it a request again). Blocking uses the
  blocked folder that already syncs.
- Both reach the phone through the merge (sync_merge.py) as "requests:<setting>" and "request:<address>" items, only
  once both sides say they understand them (see sync_merge.REQUESTS).
- Conversations from people you never linked: IRC private messages and XMPP chats from unknown addresses get their
  own route marked "request", and Matrix or XMPP invitations a route marked "invite". Network services (NickServ and
  the like) and servers never get one.
- The accept, delete, block, and report actions (/api/requests/*). Decoy sessions never reach them (main.py).
"""
import hashlib
import json
import re
import time
from contextlib import closing

# Setting → (default, allowed values). Booleans are True/False; requestNotify is "quiet" or "off".
PREFS = {
    # Filter unknown senders (texts): off by default, like iOS, so delivery and sign-in codes are never missed.
    "requestTexts": (False, (True, False)),
    # Filter unknown senders (chat services): Matrix, XMPP, IRC, and email from people you never talked to.
    "requestChats": (True, (True, False)),
    # Request notifications: a quiet notification, or none at all.
    "requestNotify": ("quiet", ("quiet", "off")),
    # Keep automated messages out of the inbox: they show only under the Automated filter.
    "automatedOut": (False, (True, False)),
    # Don't notify for automated messages.
    "automatedQuiet": (False, (True, False)),
    # Block automated senders. Sign-in codes and alerts come from automated senders, so this is off by default.
    "automatedBlock": (False, (True, False)),
}
# Merge item names ("requests:<name>") for each setting, and the text of each value.
ITEMS = {"texts": "requestTexts", "chats": "requestChats", "notify": "requestNotify", "automatedOut": "automatedOut",
         "automatedQuiet": "automatedQuiet", "automatedBlock": "automatedBlock"}
ACCEPTED = "accepted"
DELETED = re.compile(r"deleted:([0-9]{1,15})")
LIMIT = 2000

# Network services that talk to you without being people: IRC services, bouncer control nicks, and servers.
SERVICE_NICK = re.compile(r"(?i)(nick|chan|memo|oper|host|bot|help|info|stat|sasl|alis|global|group|hist|op)serv|global|\*.*")
MAIL_SERVICE = re.compile(r"(?i)(mailer-daemon|postmaster|mail-daemon)@.*")


def defaults():
    return {key: default for key, (default, _) in PREFS.items()}


def read_prefs(stored):
    """The request settings out of the stored prefs, each one its default when missing or invalid."""
    return {key: stored.get(key) if stored.get(key) in allowed and type(stored.get(key)) is type(default) else default
            for key, (default, allowed) in PREFS.items()}


def clean_pref(key, value):
    default, allowed = PREFS[key]
    if type(value) is not type(default) or value not in allowed:
        raise ValueError("Invalid preferences")
    return value


def clean_decision(value):
    return isinstance(value, str) and (value == ACCEPTED or bool(DELETED.fullmatch(value)))


def clean_decisions(value):
    if not isinstance(value, dict) or len(value) > LIMIT or any(
            not isinstance(key, str) or not 0 < len(key) < 300 or not clean_decision(item) for key, item in value.items()):
        raise ValueError("Invalid chat settings")
    return dict(value)


def network_service(protocol, name):
    """NickServ and friends, a server's own address, or a mail system: never a request."""
    name = name if isinstance(name, str) else ""
    if not name:
        return True
    if protocol == "irc":
        return bool(SERVICE_NICK.fullmatch(name)) or "." in name
    if protocol == "xmpp":
        return "@" not in name  # The server itself, or a component on it.
    if protocol == "matrix":
        local = name.lstrip("@").split(":", 1)[0].lower()
        return ":" not in name or local in ("server", "notices", "server-notices", "_server")
    if protocol == "email":
        return bool(MAIL_SERVICE.fullmatch(name))
    return False


# Merge items

def merge_items(prefs, chats):
    """The web's request items: settings that differ from their default, and each decision."""
    items = {}
    current = read_prefs(prefs)
    for name, key in ITEMS.items():
        value = current[key]
        if value != PREFS[key][0]:
            items["requests:" + name] = value if isinstance(value, str) else "on" if value else "off"
    for address, decision in (chats.get("requests") or {}).items():
        if clean_decision(decision):
            items["request:" + address] = decision
    return items


def apply_merge(kind, name, value, prefs, chats):
    """Writes one merged item into the web's prefs or chats. Returns which store changed, or None."""
    if kind == "requests" and name in ITEMS:
        key = ITEMS[name]
        default, allowed = PREFS[key]
        if value is None:
            prefs[key] = default
        elif isinstance(default, bool):
            prefs[key] = value == "on"
        elif value in allowed:
            prefs[key] = value
        return "prefs"
    if kind == "request":
        decisions = dict(chats.get("requests") or {})
        if clean_decision(value):
            decisions[name] = value
        else:
            decisions.pop(name, None)
        chats["requests"] = decisions
        return "chats"
    return None


# Conversations from people you never linked

def _address(prefix, *parts):
    return prefix + hashlib.sha256("\n".join(parts).lower().encode()).hexdigest()[:16]


def _insert(gateway, route):
    with closing(gateway.connect()) as db, db:
        db.execute("INSERT OR IGNORE INTO protocol_routes (address, protocol, payload) VALUES (?, ?, ?)",
                   (route["address"], route["protocol"], json.dumps(route)))


def inbound_route(gateway, protocol, target, account="", title="", known=False):
    """A one-to-one chat for an IRC nick or XMPP address that wrote first. Marked "request" unless [known] (in your
    XMPP roster, say); the browser and phone then decide with the request rule. None for network services."""
    if protocol not in ("irc", "xmpp", "email") or network_service(protocol, target):
        return None
    account = account or gateway.default_account(protocol)
    draft = {"address": _address("req-", account, protocol, target), "protocol": protocol, "target": target,
             "title": (title or target)[:120], "account": account, "joined": gateway.time()}
    try:
        route = gateway.validate_route(draft, gateway.routes)
    except ValueError:
        return None
    if not known:
        route["request"] = True
    _insert(gateway, route)
    return route


def note_invite(gateway, protocol, account, target, inviter="", title="", direct=True):
    """An invitation: a Matrix room or DM invite, an XMPP subscription request or group chat invite. It shows as a chat
    with Accept and Decline (requests.js and the phone's banner) and counts as a request while that's on.
    Other code that learns of an invite (the gateway's sync, or an invite page) calls this."""
    if not isinstance(target, str) or not target or network_service(protocol, inviter or target):
        return None
    if any(r.get("via", r["protocol"]) == ("matrix" if protocol == "matrix" else protocol) and r["target"].lower() == target.lower()
           and r.get("account", "") == account for r in gateway.routes):
        return None
    address = _address("inv-", account, protocol, target)
    route = {"address": address, "protocol": protocol, "target": target, "via": protocol, "group": not direct,
             "title": (title or inviter or target)[:120], "members": [], "account": account, "joined": gateway.time(),
             "invite": True, "inviter": (inviter or "")[:300]}
    _insert(gateway, route)
    words = (inviter or "Someone") + (" invited you to " + title if title and not direct else " wants to chat with you")
    record = gateway.message(route, "invite-" + address, words, False, gateway.time(), author=inviter or "")
    record["event"] = True
    gateway.add_incoming(record)
    return route


def matrix_invites(gateway, account, synced):
    """Matrix rooms you were invited to, from a /sync answer's rooms.invite."""
    for room, data in ((synced.get("rooms") or {}).get("invite") or {}).items():
        events = ((data or {}).get("invite_state") or {}).get("events") or []
        inviter, title, direct = "", "", False
        for event in events:
            content = event.get("content") if isinstance(event.get("content"), dict) else {}
            if event.get("type") == "m.room.member" and content.get("membership") == "invite":
                inviter = event.get("sender") if isinstance(event.get("sender"), str) else inviter
                direct = direct or content.get("is_direct") is True
            elif event.get("type") == "m.room.name" and isinstance(content.get("name"), str):
                title = content["name"]
        if isinstance(room, str) and room.startswith("!"):
            note_invite(gateway, "matrix", account, room, inviter, title, direct or not title)


# Actions

def _route(gateway, address):
    return next((r for r in gateway.routes if r["address"] == address), None)


def _update_route(db, route, **changes):
    stored = db.execute("SELECT payload FROM protocol_routes WHERE address=? AND protocol=?",
                        (route["address"], route["protocol"])).fetchone()
    if not stored:
        return
    payload = {**json.loads(stored[0]), **changes}
    for key, value in changes.items():
        if value is None:
            payload.pop(key, None)
    db.execute("UPDATE protocol_routes SET payload=? WHERE address=? AND protocol=?",
               (json.dumps(payload), route["address"], route["protocol"]))


def answer_invite(gateway, db, route, accept):
    """Accepts or declines an invitation. Uses the gateway's own invite handling when it has one (the invites page),
    otherwise joins or leaves the Matrix room; an XMPP invite is answered by keeping or removing its chat."""
    # The gateway's Invitations page (chat_features.py) takes an invitation's id; it also removes or keeps this chat.
    waiting = getattr(gateway, "invites", None)
    invite = next((i for i in waiting() if i.get("account", "") == route.get("account", "")
                   and i["target"].lower() == route["target"].lower()), None) if callable(waiting) else None
    if invite is not None:
        return gateway.answer_invite(db, {"id": invite["id"], "accept": accept})
    if route.get("via", route["protocol"]) == "matrix":
        with gateway.using(route.get("account")):
            gateway.matrix_request(("join/" if accept else "rooms/") + _quote(route["target"]) + ("" if accept else "/leave"), {},
                                   method="POST")
    if accept:
        _update_route(db, route, invite=None, inviter=None)
    else:
        db.execute("DELETE FROM protocol_routes WHERE address=? AND protocol=?", (route["address"], route["protocol"]))
    return {"answered": True}


def _quote(value):
    from urllib.parse import quote
    return quote(value, safe="")


def report_matrix(gateway, db, route):
    """Reports the room, or its newest message from them, to the homeserver. False when it has no report endpoint."""
    with gateway.using(route.get("account")):
        try:
            gateway.matrix_request("rooms/" + _quote(route["target"]) + "/report", {"reason": "Spam"}, method="POST")
            return True
        except Exception:
            pass
        newest = None
        for (payload,) in db.execute("SELECT payload FROM protocol_messages ORDER BY date DESC LIMIT 2000"):
            message = json.loads(payload)
            if message.get("address") == route["address"] and message.get("type") == 1 and message.get("event_id"):
                newest = message["event_id"]
                break
        if not newest:
            return False
        try:
            gateway.matrix_request("rooms/" + _quote(route["target"]) + "/report/" + _quote(newest),
                                   {"reason": "Spam", "score": -100}, method="POST")
            return True
        except Exception:
            return False


def act(gateway, db, action, data, chats, save_chats, add_contact=None, now=None):
    """/api/requests/<action>: accept, delete, block, or report. [chats] is web_chats; [save_chats] stores it."""
    address = data.get("address")
    if not isinstance(address, str) or not 0 < len(address) < 300:
        raise ValueError("Choose a chat")
    now = now or int(time.time() * 1000)
    route = _route(gateway, address)
    decisions = dict(chats.get("requests") or {})
    blocked = [item for item in chats.get("blocked", []) if item != address]
    result = {"address": address}
    if action == "accept":
        decisions[address] = ACCEPTED
        chats = {**chats, "blocked": blocked}
        if route and route.get("invite"):
            answer_invite(gateway, db, route, True)
        elif route and route.get("request"):
            _update_route(db, route, request=None)
        if data.get("contact") is True and add_contact:
            result["contact"] = add_contact(address, route, data.get("name"))
    elif action == "delete":
        decisions[address] = "deleted:" + str(now)
        if route and route.get("invite"):
            answer_invite(gateway, db, route, False)
        db.execute("DELETE FROM protocol_messages WHERE json_extract(payload, '$.address')=?", (address,))
        # A chat that started as a request (inbound_route, note_invite) goes too; one you linked yourself stays.
        if route and (route.get("request") or route.get("invite") or address.startswith(("req-", "inv-"))):
            db.execute("DELETE FROM protocol_routes WHERE address=?", (address,))
    elif action in ("block", "report"):
        decisions.pop(address, None)
        chats = {**chats, "blocked": blocked + [address]}
        if route and route.get("invite"):
            if action == "report" and route.get("via", route["protocol"]) == "matrix":
                result["reported"] = report_matrix(gateway, db, route)
            answer_invite(gateway, db, route, False)
        elif action == "report" and route and route.get("via", route["protocol"]) == "matrix":
            result["reported"] = report_matrix(gateway, db, route)
        result.setdefault("reported", False)
    else:
        raise ValueError("Unknown request action")
    chats = {**chats, "requests": decisions}
    save_chats(db, chats)
    result["web_chats"] = chats
    return result
