"""Combining the phone's settings with FluidMessages Web's when the phone connects.

Both sides describe what they keep as items: a key such as "folder:+15550100" or "look:theme" and a short text
value, or no item at all for the default. The phone runs the three-way merge (SyncMerge.kt) against the last
synced baseline kept here, writes what changed into its own settings, and posts the web's share plus any true
conflicts. Those wait here until someone picks a side, from the phone or from this web app; the other device sees
the result on its next look.

Nothing secret is part of an item: accounts are compared by their public address only (see ACCOUNTS below), chat codes and passwords
never become items, and stickers travel as the same small pictures either side already sends in chats. Chats in the
web's private folder (privacy.py) are left out both ways: the phone keeps seeing their last agreed values.

Older phones never call these endpoints and keep the earlier one-way sync. A phone paired to a different server,
or a server that was erased, has no matching pair id, so its first merge combines instead of deleting.
"""
import hashlib
import json
import re
import secrets
import shutil
import time

import message_requests

KEY = re.compile(r"(look|fun|pref|folder|muted|muteuntil|favorite|sender|timer|wallpaper|gif|gifoff|pay|sticker|account|protocol"
                 r"|request|requests):[^\x00-\x1f]{1,300}")
# Settings that follow the phone only while FluidMessages Web uses the phone's settings (Settings → Phone and other
# devices). With "This web app's own" chosen, each side keeps its own look, fun, inbox choices, and timers.
SETTINGS_GROUPS = ("look", "fun", "pref", "timer", "wallpaper")
STICKER_ID = re.compile(r"[A-Za-z0-9_-]{1,80}")
STICKER_TYPES = {"image/png": "png", "image/webp": "webp", "image/gif": "gif"}
STICKER_LIMIT, STICKER_COUNT = 2_000_000, 300
MAX_ITEMS, MAX_VALUE = 20_000, 2_000
THEMES = ("fluid", "midnight", "lavender", "evergreen", "tideline", "ember", "love", "rosewater")
WALLPAPERS = ("none", "horizon", "aurora", "love", "orchard")
# Web appearance keys, as items, with the default that means "no item".
LOOK = {"theme": "fluid", "radius": "18", "size": "16", "padding": "12", "wallpaper": "none", "searchPosition": "bottom",
        "toolbarFinish": "plain", "density": "standard", "tails": "false", "chromeOpacity": "84", "speed": "220",
        "exactTimes": "false", "combineProtocols": "false", "tagMode": "auto", "encryptionMode": "auto"}
INTS = {"radius", "size", "padding", "chromeOpacity", "speed"}
BOOLS = {"tails", "exactTimes", "combineProtocols"}
# Fun item names and the web's preference keys.
FUN = {"effects": "effects", "reactions": "reactionEffects", "games": "games", "random": "random", "gifs": "gifs",
       "linkPreviews": "linkPreviews"}
FOLDERS = ("pinned", "archived", "blocked")
# Item groups named after a chat's address, which leave a private chat out of the merge.
ADDRESSED = ("folder", "muted", "muteuntil", "favorite", "sender", "timer", "wallpaper", "request")
# Inbox and messaging choices, "pref:<name>", with the value that means "no item" (the default on both sides):
# Settings → Messaging → Counts on tabs and Show community chats, the Communities sort, the inbox filter menu (its
# order, a hidden filter with "-" in front), and Settings → Privacy → Announce setting changes.
PREFS = {"tabCounts": "on", "showCommunityChats": "on", "communitySort": "recent", "announceSettings": "on", "inboxFilters": None}
PREF_SWITCHES = ("tabCounts", "showCommunityChats")
COMMUNITY_SORTS = ("recent", "name", "joined")
# The same filters, in the same order, as main.py INBOX_FILTERS and the phone's InboxFilters.ALL (Requests and
# Automated come from message requests).
INBOX_FILTERS = ("inbox", "unread", "favorites", "groups", "direct", "texts", "matrix", "xmpp", "email", "irc", "requests", "automated",
                 "archived", "blocked")
# Timed mutes. "muted:<key>" = "on" stays what every version compares, for timed mutes too, so an older phone or
# server still sees the chat as muted and nothing it doesn't know ever reaches it. When the mute ends there's no item
# at all, which older versions read as unmuted. How long is a second item, "muteuntil:<key>" = the end in epoch
# milliseconds (no item: forever). Older servers refuse a group they don't know and older phones would take a missing
# item as a change, so these items are only exchanged once both sides say they understand them: the phone asks with
# the X-Fluid-Merge-Items: muteuntil header and posts "mute_until": 1; this server answers "mute_until": 1.
TIMED = "muteuntil"
# Item groups newer than the merge itself, the same way: exchanged only with a phone that names them in
# X-Fluid-Merge-Groups (comma-separated; X-Fluid-Merge-Items stays exactly "muteuntil", since servers from before
# compare it whole) and posts them back in "items"; this server lists the ones it knows in "items". Older servers never answer "items" and refuse groups they don't know; older phones never ask, and would
# read a group they don't know as the web deleting it. So each side only ever sees groups both understand.
# "favorite:<address>" = "on": a starred person's number (its digits and a leading +, see favorite_address) or chat
# address. Phones star contacts by contact id, which means nothing here, so the phone lists a starred contact's numbers.
# Message requests (message_requests.py): "requests:<setting>" for Settings → Requests and spam, and
# "request:<address>" for what you decided about a chat ("accepted", or "deleted:<epoch ms>"). They're optional groups
# too. Phones that name them in X-Fluid-Merge-Groups / "items" get them; so do phones built for the earlier requests
# flag, which ask with the X-Fluid-Merge-Requests: 1 header and post "requests": 1. This server still answers
# "requests": 1 next to "items" for those phones.
REQUESTS = ("request", "requests")
OPTIONAL = (TIMED, "pref", "favorite") + REQUESTS
INTERNET = ("matrix", "xmpp", "email", "irc")
# The phone's CustomMoney.validTemplate: a link an app can open, never javascript:, a file, or an intent.
PAY_TEMPLATE = re.compile(r"(?!(?:javascript|file|content|intent|data|vbscript):)[a-z][a-z0-9+.-]{1,30}:\S*\{address\}\S*")
GIF_URL = re.compile(r"https://[A-Za-z0-9.-]+\.[A-Za-z]{2,}(/\S*)?")


# Accounts. "account:matrix" = the service's first account's address is what older phones and servers compare: the
# phone and the web each connecting a different account is a choice of who connects. With several accounts per
# service, each account also has its own item, "account:matrix:@alice:example.org", whose value says where it
# connects ("web" or "phone"). Only the same account connected on both sides is then a conflict; different accounts
# on each side simply both connect. Phones that understand per-account items ignore "account:<service>" whenever the
# web lists per-account ones, and older phones never compare the per-account items, since neither side copies
# account items.
ACCOUNT_SIDES = ("web", "phone")


def _state(db, key, default=None):
    row = db.execute("SELECT value FROM protocol_state WHERE key=?", (key,)).fetchone()
    return json.loads(row[0]) if row else default


def _put(db, key, value):
    if value is None:
        db.execute("DELETE FROM protocol_state WHERE key=?", (key,))
    else:
        db.execute("INSERT OR REPLACE INTO protocol_state VALUES (?, ?)", (key, json.dumps(value)))


def group(key):
    return key.split(":", 1)[0]


def optional_groups(header=None, posted=None, timed=False, requests=False):
    """The newer item groups a phone understands (OPTIONAL): from its X-Fluid-Merge-Groups / X-Fluid-Merge-Items
    headers, or the "items" it posts. Phones from before "items" only ever said muteuntil (header) or "mute_until": 1
    (post), and phones from the first message requests release said X-Fluid-Merge-Requests: 1 or "requests": 1."""
    names = posted if isinstance(posted, list) else (header or "").split(",")
    found = {name.strip() for name in names if isinstance(name, str)} & set(OPTIONAL)
    if timed:
        found.add(TIMED)
    if requests:
        found.update(REQUESTS)
    return tuple(name for name in OPTIONAL if name in found)


def favorite_address(text):
    """A favorite as an item name: a phone number as its digits (and a leading +), anything else as it is. The
    phone's SyncMerge.favoriteAddress does the same, so one number is one item on both sides."""
    text = text.strip() if isinstance(text, str) else ""
    if re.fullmatch(r"\+?[0-9(][0-9 ().-]{1,38}", text) and sum(char.isdigit() for char in text) >= 3:
        return ("+" if text.startswith("+") else "") + re.sub(r"[^0-9]", "", text)
    return text


def inbox_filters_item(order):
    """The inbox filter menu as one item value, or None for the default (every filter shown, in the usual order)."""
    order = [item for item in order if isinstance(item, str)]
    order = order + [item for item in INBOX_FILTERS if item not in [entry.removeprefix("-") for entry in order]]
    return None if order == list(INBOX_FILTERS) else ",".join(order)


def inbox_filters_value(value):
    """An item value back as the filter menu, or None when it names a filter this server doesn't know."""
    order = value.split(",") if value else []
    ids = [item.removeprefix("-") for item in order]
    if any(item not in INBOX_FILTERS for item in ids) or len(set(ids)) != len(ids) or "-inbox" in order:
        return None
    return order + [item for item in INBOX_FILTERS if item not in ids]


def clean_items(value, nullable=False):
    if not isinstance(value, dict) or len(value) > MAX_ITEMS:
        raise ValueError("Invalid sync items")
    for key, item in value.items():
        if not isinstance(key, str) or not KEY.fullmatch(key):
            raise ValueError("Invalid sync item")
        if not (isinstance(item, str) and len(item) <= MAX_VALUE or nullable and item is None):
            raise ValueError("Invalid sync value")
    return dict(value)


def identity(protocol, fields):
    """An account's public address, which is all the phone and the web compare. Never a password or token."""
    pick = lambda name: fields.get(name) if isinstance(fields.get(name), str) else ""
    if protocol == "matrix":
        return pick("user_id")
    if protocol == "xmpp":
        return pick("jid")
    if protocol == "email":
        return pick("address")
    if protocol == "irc":
        return pick("nick") + "@" + pick("server") if pick("nick") else ""
    return ""


def clean_extras(value):
    """Payment methods and GIF sites added in a browser, kept here so every browser and the phone share them."""
    if not isinstance(value, dict):
        raise ValueError("Invalid web extras")
    money, gifs = value.get("money") or {}, value.get("gifs") or {}
    if not isinstance(money, dict) or not isinstance(gifs, dict):
        raise ValueError("Invalid web extras")
    custom = [{"name": item["name"].strip()[:40], "template": item["template"].strip()} for item in (money.get("custom") or [])[:30]
              if isinstance(item, dict) and isinstance(item.get("name"), str) and item["name"].strip()
              and isinstance(item.get("template"), str) and PAY_TEMPLATE.fullmatch(item["template"].strip())]
    own = [{"name": item["name"].strip()[:30], "search": item["search"].strip()} for item in (gifs.get("own") or [])[:12]
           if isinstance(item, dict) and isinstance(item.get("name"), str) and item["name"].strip()
           and isinstance(item.get("search"), str) and GIF_URL.fullmatch(item["search"].strip())]
    off = [item for item in (gifs.get("off") or [])[:50] if isinstance(item, str) and re.fullmatch(r"[a-z0-9-]{1,40}", item)]
    default = money.get("default") if isinstance(money.get("default"), str) and len(money["default"]) <= 40 else ""
    return {"money": {"custom": custom, "default": default}, "gifs": {"own": own, "off": list(dict.fromkeys(off))}}


class Merge:
    """FluidMessages Web's side of the merge. [hooks] reads and writes the web's own stores (main.py)."""

    def __init__(self, gateway, hooks, clock=time.time):
        self.gateway = gateway
        self.hooks = hooks
        self.clock = clock

    # Stickers, kept as files next to message attachments

    @property
    def sticker_folder(self):
        return self.gateway.files / "stickers"

    def stickers(self, db):
        return _state(db, "stickers", {})

    def sticker_path(self, db, key):
        entry = self.stickers(db).get(key) if isinstance(key, str) and STICKER_ID.fullmatch(key) else None
        return (self.sticker_folder / (key + "." + STICKER_TYPES[entry["mime"]]), entry) if entry else (None, None)

    def add_sticker(self, db, key, mime, source, size):
        if not isinstance(key, str) or not STICKER_ID.fullmatch(key):
            raise ValueError("Invalid sticker")
        if mime not in STICKER_TYPES or not 0 < size <= STICKER_LIMIT:
            raise ValueError("Stickers are PNG, WebP, or GIF pictures up to 2 MB")
        index = self.stickers(db)
        if key not in index and len(index) >= STICKER_COUNT:
            raise ValueError("That's the most stickers FluidMessages Web keeps")
        data = source.read(size)
        if len(data) != size:
            raise ValueError("The sticker didn't arrive in full")
        signatures = {"image/png": (b"\x89PNG",), "image/gif": (b"GIF8",), "image/webp": (b"RIFF",)}
        if not data.startswith(signatures[mime]):
            raise ValueError("That isn't a picture")
        self.sticker_folder.mkdir(parents=True, exist_ok=True)
        for other in self.sticker_folder.glob(key + ".*"):
            other.unlink(missing_ok=True)
        path = self.sticker_folder / (key + "." + STICKER_TYPES[mime])
        temporary = path.with_suffix(".tmp")
        temporary.write_bytes(data)
        temporary.replace(path)
        index[key] = {"mime": mime, "size": size, "date": int(self.clock() * 1000)}
        _put(db, "stickers", index)
        return {"id": key, **index[key]}

    def delete_sticker(self, db, key):
        index = self.stickers(db)
        if key in index:
            path, _ = self.sticker_path(db, key)
            if path:
                path.unlink(missing_ok=True)
            del index[key]
            _put(db, "stickers", index)

    def erase(self):
        shutil.rmtree(self.sticker_folder, ignore_errors=True)

    # The web's items

    def web_items(self, db):
        hooks = self.hooks
        items = {}
        look = hooks["appearance"](db)
        for name, default in LOOK.items():
            value = look.get(name)
            if value is None:
                continue
            text = ("true" if value else "false") if name in BOOLS else str(value)
            if name == "wallpaper" and text == "photo":
                text = "photo"
            if text != default:
                items["look:" + name] = text
        if look.get("mode") in ("light", "dark"):
            items["look:mode"] = look["mode"]
        for address, wallpaper in (look.get("chatWallpapers") or {}).items():
            if wallpaper != "none":
                items["wallpaper:" + address] = wallpaper
        prefs = hooks["prefs"](db)
        for name, key in FUN.items():
            if prefs.get(key) is False:
                items["fun:" + name] = "off"
        for name in PREF_SWITCHES:
            if prefs.get(name) is False:
                items["pref:" + name] = "off"
        if look.get("announceSettings") is False:
            items["pref:announceSettings"] = "off"
        if prefs.get("communitySort") in COMMUNITY_SORTS and prefs["communitySort"] != PREFS["communitySort"]:
            items["pref:communitySort"] = prefs["communitySort"]
        filters = inbox_filters_item(prefs.get("inboxFilters") or [])
        if filters:
            items["pref:inboxFilters"] = filters
        for favorite in hooks["favorites"](db) if "favorites" in hooks else []:
            name = favorite_address(favorite)
            if name and not re.search(r"[\x00-\x1f]", name):
                items["favorite:" + name] = "on"
        chats = hooks["chats"](db)
        places = {}
        for folder in FOLDERS:
            for address in chats.get(folder, []):
                places.setdefault(address, []).append(folder)
        for address, folders in places.items():
            items["folder:" + address] = "+".join(sorted(folders))
        until = chats.get("mute_until") or {}
        now = int(self.clock() * 1000)
        for address in chats.get("muted", []):
            ends = until.get(address, 0) if type(until.get(address, 0)) is int else 0
            if 0 < ends <= now:
                continue  # Its time is up: unmuted, on both sides.
            items["muted:" + address] = "on"
            if ends:
                items[TIMED + ":" + address] = str(ends)
        for address, kind in (chats.get("senders") or {}).items():
            items["sender:" + address] = kind
        items.update(message_requests.merge_items(prefs, chats))
        timers = self.gateway.settings(db)["disappearing"]
        if timers.get("default"):
            items["timer:*"] = str(timers["default"])
        for address, seconds in timers.get("chats", {}).items():
            items["timer:" + address] = str(seconds)
        extras = clean_extras(_state(db, "web_extras", {}))
        for site in extras["gifs"]["own"]:
            items["gif:" + site["name"]] = site["search"]
        for key in extras["gifs"]["off"]:
            items["gifoff:" + key] = "off"
        for method in extras["money"]["custom"]:
            items["pay:" + method["name"]] = method["template"]
        if extras["money"]["default"]:
            items["pay:*"] = extras["money"]["default"]
        for key in self.stickers(db):
            items["sticker:" + key] = "1"
        for account in self.gateway.accounts.redacted():
            name = identity(account.get("protocol"), account.get("fields") or {})
            if not name:
                continue
            if account.get("primary", True):
                items.setdefault("account:" + account["protocol"], name)
            items["account:" + account["protocol"] + ":" + name] = "web"
        enabled = _state(db, "web_enabled")
        if enabled is not None:
            for protocol in INTERNET:
                if protocol not in enabled:
                    items["protocol:" + protocol] = "off"
        hidden = self.private(db)
        if hidden:
            # Chats in the web's private folder stay out of the merge: the phone keeps seeing what it last agreed on
            # for them, so nothing about them changes on either side and nothing new about them leaves the server.
            items = {key: value for key, value in items.items() if not (group(key) in ADDRESSED and hidden(key))}
            for key, value in (_state(db, "merge_baseline") or {}).items():
                if group(key) in ADDRESSED and hidden(key):
                    items[key] = value
        return items

    def private(self, db):
        """is_private(key or address) for the web's private folder (privacy.matcher), or None."""
        finder = self.hooks.get("private")
        return finder(db) if finder else None

    def apply_web(self, db, changes):
        """Writes merged values into the web's stores. A missing value (None) means back to the default."""
        if not changes:
            return
        hooks = self.hooks
        look, prefs, chats = hooks["appearance"](db), hooks["prefs"](db), hooks["chats"](db)
        settings = self.gateway.settings(db)
        timers = json.loads(json.dumps(settings["disappearing"]))
        extras = clean_extras(_state(db, "web_extras", {}))
        enabled = _state(db, "web_enabled")
        favorites = list(hooks["favorites"](db)) if "favorites" in hooks else []
        hidden = self.private(db)
        touched = set()
        now = int(self.clock() * 1000)
        for key, value in changes.items():
            kind, name = key.split(":", 1)
            if hidden and kind in ADDRESSED and hidden(key):
                continue  # A private chat's settings stay as they are on the web.
            touched.add(kind)
            if kind == "look":
                if name == "mode":
                    look["mode"] = value if value in ("light", "dark") else "system"
                elif name in LOOK:
                    if value is None:
                        look.pop(name, None)
                    elif name in INTS:
                        look[name] = int(value) if value.lstrip("-").isdigit() else look.get(name)
                    elif name in BOOLS:
                        look[name] = value == "true"
                    elif name == "wallpaper" and value == "photo":
                        pass  # A photo stays on the device that has it.
                    else:
                        look[name] = value
            elif kind == "wallpaper":
                walls = dict(look.get("chatWallpapers") or {})
                if value is None or value == "none":
                    walls.pop(name, None)
                elif value in WALLPAPERS:
                    walls[name] = value
                look["chatWallpapers"] = walls
            elif kind == "fun" and name in FUN:
                prefs[FUN[name]] = value != "off"
            elif kind == "pref":
                if name == "announceSettings":
                    look["announceSettings"] = value != "off"
                    touched.add("look")
                elif name in PREF_SWITCHES:
                    prefs[name] = value != "off"
                elif name == "communitySort":
                    prefs[name] = value if value in COMMUNITY_SORTS else PREFS[name]
                elif name == "inboxFilters":
                    # A filter from a newer phone that this server doesn't know leaves the web's menu as it is.
                    order = inbox_filters_value(value) if value else list(INBOX_FILTERS)
                    if order:
                        prefs[name] = order
            elif kind == "favorite":
                # The same number however it's written: every spelling of it goes, and on adds it once.
                favorites = [item for item in favorites if favorite_address(item) != name]
                if value == "on":
                    favorites.append(name)
            elif kind == "folder":
                wanted = set(value.split("+")) if value else set()
                for folder in FOLDERS:
                    items = [item for item in chats.get(folder, []) if item != name]
                    chats[folder] = items + [name] if folder in wanted else items
            elif kind == "muted":
                items = [item for item in chats.get("muted", []) if item != name]
                chats["muted"] = items + [name] if value == "on" else items
                if value != "on":
                    chats["mute_until"] = {key: ends for key, ends in (chats.get("mute_until") or {}).items() if key != name}
            elif kind == TIMED:
                until = dict(chats.get("mute_until") or {})
                if value and value.isdigit() and int(value) > 0:
                    until[name] = int(value)
                else:
                    until.pop(name, None)  # Muted forever, if still muted.
                chats["mute_until"] = until
            elif kind == "sender":
                senders = dict(chats.get("senders") or {})
                if value in ("automated", "person"):
                    senders[name] = value
                else:
                    senders.pop(name, None)
                chats["senders"] = senders
            elif kind in REQUESTS:
                message_requests.apply_merge(kind, name, value, prefs, chats)
            elif kind == "timer":
                seconds = int(value) if value and value.isdigit() else None
                # Like a timer changed in the browser, a new one starts now, so earlier messages stay until the phone's
                # next sync brings its own start time.
                if name == "*":
                    if (seconds or 0) != timers.get("default", 0):
                        timers["default_since"] = now
                    timers["default"] = seconds or 0
                elif seconds is None:
                    timers["chats"].pop(name, None)
                    timers.get("since", {}).pop(name, None)
                else:
                    if timers["chats"].get(name) != seconds:
                        timers.setdefault("since", {})[name] = now
                    timers["chats"][name] = seconds
            elif kind == "gif":
                own = [site for site in extras["gifs"]["own"] if site["name"] != name]
                extras["gifs"]["own"] = own + ([{"name": name, "search": value}] if value else [])
            elif kind == "gifoff":
                off = [item for item in extras["gifs"]["off"] if item != name]
                extras["gifs"]["off"] = off + ([name] if value == "off" else [])
            elif kind == "pay":
                if name == "*":
                    extras["money"]["default"] = value or ""
                else:
                    custom = [item for item in extras["money"]["custom"] if item["name"] != name]
                    extras["money"]["custom"] = custom + ([{"name": name, "template": value}] if value else [])
            elif kind == "sticker":
                if value is None:
                    self.delete_sticker(db, name)
            elif kind == "protocol" and name in INTERNET:
                current = set(enabled if enabled is not None else INTERNET)
                if value == "off":
                    current.discard(name)
                else:
                    current.add(name)
                enabled = [item for item in INTERNET if item in current]
            # Accounts: the phone never sends passwords here, so a merge only decides which side connects.
        if touched & {"look", "wallpaper"}:
            hooks["save_appearance"](db, look)
        if touched & {"fun", "pref", "requests"}:
            hooks["save_prefs"](db, prefs)
        if "favorite" in touched and "save_favorites" in hooks:
            hooks["save_favorites"](db, list(dict.fromkeys(favorites)))
        if touched & {"folder", "muted", TIMED, "sender", "request"}:
            hooks["save_chats"](db, chats)
        if "timer" in touched:
            self.gateway.save_settings(db, {"disappearing": timers})
        if touched & {"gif", "gifoff", "pay"}:
            _put(db, "web_extras", clean_extras(extras))
        if "protocol" in touched and enabled is not None:
            _put(db, "web_enabled", enabled)

    # What each device sees

    def stamps(self, db, items):
        """When each web item last changed, as far as this server noticed."""
        seen = _state(db, "merge_seen", {})
        now = int(self.clock() * 1000)
        changed = False
        for key in set(items) | set(seen):
            if key not in seen or seen[key][0] != items.get(key):
                seen[key] = [items.get(key), now]
                changed = True
        if len(seen) > MAX_ITEMS:
            seen = dict(sorted(seen.items(), key=lambda entry: entry[1][1])[-MAX_ITEMS:])
            changed = True
        if changed:
            _put(db, "merge_seen", seen)
        return {key: entry[1] for key, entry in seen.items()}

    def view(self, db, groups=()):
        """What the phone merges against. [groups]: the newer item groups it understands (OPTIONAL, optional_groups)."""
        web = self.web_items(db)
        stamps = self.stamps(db, web)
        web = {key: value for key, value in web.items() if group(key) not in OPTIONAL or group(key) in groups}
        pair = _state(db, "merge_pair")
        pending, resolved = _state(db, "merge_pending"), _state(db, "merge_resolved")
        baseline, decisions = _state(db, "merge_baseline"), _state(db, "merge_decisions", {})
        follow = self.gateway.settings(db)["follow_phone"]
        revision = hashlib.sha256(json.dumps([web, pair, baseline, decisions, follow, pending and pending["id"],
                                              resolved and resolved["id"]], sort_keys=True).encode()).hexdigest()[:24]
        return {"supported": 1, "mute_until": 1, "requests": 1, "items": list(OPTIONAL), "pair": pair, "revision": revision,
                "follow_phone": follow, "web": web,
                "stamps": {key: stamps[key] for key in web if key in stamps}, "baseline": baseline,
                "decisions": decisions, "pending": pending, "resolved": resolved}

    def summary(self, db):
        """For the inbox snapshot: whether the merge is in use and what waits for a choice."""
        pending = _state(db, "merge_pending")
        # [at]: the phone's last merge, which the web's sync guide also waits for: a phone paired only for backups,
        # or without the SMS role, merges settings but never sends texts.
        return {"active": _state(db, "merge_pair") is not None, "pending": pending, "at": _state(db, "merge_at")}

    # Changes

    def post(self, db, data):
        """The phone's merge result. Refused when the web changed since the phone looked, so nothing is lost."""
        db.execute("BEGIN IMMEDIATE")
        timed = optional_groups(posted=data.get("items"), timed=data.get("mute_until") == 1,
                                requests=data.get("requests") == 1)
        current = self.view(db, timed)
        if data.get("revision") != current["revision"]:
            raise Conflict("FluidMessages Web changed while merging. Try again.")
        apply_web = clean_items(data.get("apply_web") or {}, nullable=True)
        baseline = clean_items(data.get("baseline") or {})
        # Groups the phone didn't say it understands were never shown to it, so nothing it sends for them is a change.
        apply_web = {key: value for key, value in apply_web.items() if group(key) not in OPTIONAL or group(key) in timed}
        conflicts = data.get("conflicts") or []
        if not isinstance(conflicts, list) or len(conflicts) > 2000:
            raise ValueError("Invalid conflicts")
        self.apply_web(db, apply_web)
        pair = current["pair"] or secrets.token_hex(12)
        _put(db, "merge_pair", pair)
        _put(db, "merge_at", int(self.clock() * 1000))
        _put(db, "merge_baseline", baseline)
        web = self.web_items(db)
        stamps = self.stamps(db, web)
        found = []
        for item in conflicts:
            if not isinstance(item, dict) or not isinstance(item.get("key"), str) or not KEY.fullmatch(item["key"]):
                raise ValueError("Invalid conflict")
            phone = item.get("phone")
            if phone is not None and (not isinstance(phone, str) or len(phone) > MAX_VALUE):
                raise ValueError("Invalid conflict")
            key = item["key"]
            # The web's value comes from this server, never from what the phone says it is.
            if group(key) in OPTIONAL and group(key) not in timed or web.get(key) == phone or any(entry["key"] == key for entry in found):
                continue
            stamp = item.get("phone_t")
            found.append({"key": key, "phone": phone, "web": web.get(key),
                          "phone_t": stamp if type(stamp) is int and stamp >= 0 else 0, "web_t": stamps.get(key, 0)})
        if found:
            found.sort(key=lambda entry: entry["key"])
            ident = hashlib.sha256(json.dumps([[entry["key"], entry["phone"], entry["web"]] for entry in found]).encode()).hexdigest()[:16]
            before = _state(db, "merge_pending")
            created = before["created"] if before and before["id"] == ident else int(self.clock() * 1000)
            _put(db, "merge_pending", {"id": ident, "created": created, "conflicts": found})
        else:
            _put(db, "merge_pending", None)
        return self.view(db, timed)

    def resolve(self, db, data):
        """A choice for every conflict, from the phone or a browser, whichever opens it first."""
        db.execute("BEGIN IMMEDIATE")
        pending = _state(db, "merge_pending")
        if not pending or data.get("id") != pending["id"]:
            raise Conflict("These differences were already settled or changed. Open them again.")
        choices = data.get("choices")
        if not isinstance(choices, dict) or any(choices.get(entry["key"]) not in ("phone", "web") for entry in pending["conflicts"]):
            raise ValueError("Choose the phone or the web for each difference")
        baseline = _state(db, "merge_baseline", {})
        decisions = _state(db, "merge_decisions", {})
        apply_web, values, picks = {}, {}, {}
        for entry in pending["conflicts"]:
            key, pick = entry["key"], choices[entry["key"]]
            value = entry[pick]
            values[key], picks[key] = value, pick
            if group(key) == "account":
                # Either side keeps its own account details; this only records who connects.
                decisions[key] = {"phone": entry["phone"], "web": entry["web"], "pick": pick}
                continue
            if pick == "phone":
                apply_web[key] = value
            if value is None:
                baseline.pop(key, None)
            else:
                baseline[key] = value
        self.apply_web(db, apply_web)
        _put(db, "merge_baseline", baseline)
        _put(db, "merge_decisions", decisions)
        _put(db, "merge_resolved", {"id": pending["id"], "values": values, "picks": picks, "at": int(self.clock() * 1000)})
        _put(db, "merge_pending", None)
        return self.view(db)

    def hold_timers(self, db, privacy):
        """The phone's regular sync sends all its timers, and doesn't overwrite the web's side of one that waits for a
        choice, or one changed here since the last merge: the phone merges at most once a minute, so a sync can come
        before the merge that would carry the web's change over (or ask, if the phone changed it too)."""
        pending = _state(db, "merge_pending")
        baseline = _state(db, "merge_baseline")
        timers = privacy.get("disappearing") if isinstance(privacy, dict) else None
        if not pending and baseline is None or not isinstance(timers, dict):
            return
        web = self.gateway.settings(db)["disappearing"]
        held = {entry["key"] for entry in (pending or {}).get("conflicts", []) if group(entry["key"]) == "timer"}
        if baseline is not None:
            items = {"timer:" + address: str(seconds) for address, seconds in web.get("chats", {}).items()}
            if web.get("default"):
                items["timer:*"] = str(web["default"])
            held |= {key for key in set(items) | {key for key in baseline if group(key) == "timer"} if items.get(key) != baseline.get(key)}
        for key in sorted(held):
            kind, name = key.split(":", 1)
            if name == "*":
                timers["default"], timers["default_since"] = web.get("default", 0), web.get("default_since", 0)
                continue
            chats, since = dict(timers.get("chats") or {}), dict(timers.get("since") or {})
            if name in web.get("chats", {}):
                chats[name], since[name] = web["chats"][name], web.get("since", {}).get(name, 0)
            else:
                chats.pop(name, None)
                since.pop(name, None)
            timers["chats"], timers["since"] = chats, since


class Conflict(Exception):
    """The merge state moved on; the caller should look again (HTTP 409)."""
