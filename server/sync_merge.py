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

KEY = re.compile(r"(look|fun|folder|muted|sender|timer|wallpaper|gif|gifoff|pay|sticker|account|protocol):[^\x00-\x1f]{1,300}")
# Settings that follow the phone only while FluidMessages Web uses the phone's settings (Settings → Phone and other
# devices). With "This web app's own" chosen, each side keeps its own look, fun, and timers.
SETTINGS_GROUPS = ("look", "fun", "timer", "wallpaper")
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
ADDRESSED = ("folder", "muted", "sender", "timer", "wallpaper")
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
        chats = hooks["chats"](db)
        places = {}
        for folder in FOLDERS:
            for address in chats.get(folder, []):
                places.setdefault(address, []).append(folder)
        for address, folders in places.items():
            items["folder:" + address] = "+".join(sorted(folders))
        for address in chats.get("muted", []):
            items["muted:" + address] = "on"
        for address, kind in (chats.get("senders") or {}).items():
            items["sender:" + address] = kind
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
        hidden = self.private(db)
        touched = set()
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
            elif kind == "folder":
                wanted = set(value.split("+")) if value else set()
                for folder in FOLDERS:
                    items = [item for item in chats.get(folder, []) if item != name]
                    chats[folder] = items + [name] if folder in wanted else items
            elif kind == "muted":
                items = [item for item in chats.get("muted", []) if item != name]
                chats["muted"] = items + [name] if value == "on" else items
            elif kind == "sender":
                senders = dict(chats.get("senders") or {})
                if value in ("automated", "person"):
                    senders[name] = value
                else:
                    senders.pop(name, None)
                chats["senders"] = senders
            elif kind == "timer":
                seconds = int(value) if value and value.isdigit() else None
                if name == "*":
                    timers["default"] = seconds or 0
                elif seconds is None:
                    timers["chats"].pop(name, None)
                    timers.get("since", {}).pop(name, None)
                else:
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
        if "fun" in touched:
            hooks["save_prefs"](db, prefs)
        if touched & {"folder", "muted", "sender"}:
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

    def view(self, db):
        web = self.web_items(db)
        stamps = self.stamps(db, web)
        pair = _state(db, "merge_pair")
        pending, resolved = _state(db, "merge_pending"), _state(db, "merge_resolved")
        baseline, decisions = _state(db, "merge_baseline"), _state(db, "merge_decisions", {})
        follow = self.gateway.settings(db)["follow_phone"]
        revision = hashlib.sha256(json.dumps([web, pair, baseline, decisions, follow, pending and pending["id"],
                                              resolved and resolved["id"]], sort_keys=True).encode()).hexdigest()[:24]
        return {"supported": 1, "pair": pair, "revision": revision, "follow_phone": follow, "web": web,
                "stamps": {key: stamps[key] for key in web if key in stamps}, "baseline": baseline,
                "decisions": decisions, "pending": pending, "resolved": resolved}

    def summary(self, db):
        """For the inbox snapshot: whether the merge is in use and what waits for a choice."""
        pending = _state(db, "merge_pending")
        return {"active": _state(db, "merge_pair") is not None, "pending": pending}

    # Changes

    def post(self, db, data):
        """The phone's merge result. Refused when the web changed since the phone looked, so nothing is lost."""
        db.execute("BEGIN IMMEDIATE")
        current = self.view(db)
        if data.get("revision") != current["revision"]:
            raise Conflict("FluidMessages Web changed while merging. Try again.")
        apply_web = clean_items(data.get("apply_web") or {}, nullable=True)
        baseline = clean_items(data.get("baseline") or {})
        conflicts = data.get("conflicts") or []
        if not isinstance(conflicts, list) or len(conflicts) > 2000:
            raise ValueError("Invalid conflicts")
        self.apply_web(db, apply_web)
        pair = current["pair"] or secrets.token_hex(12)
        _put(db, "merge_pair", pair)
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
            if web.get(key) == phone or any(entry["key"] == key for entry in found):
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
        return self.view(db)

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
        """While a timer waits for a choice, the phone's regular sync doesn't overwrite the web's side of it."""
        pending = _state(db, "merge_pending")
        timers = privacy.get("disappearing") if isinstance(privacy, dict) else None
        if not pending or not isinstance(timers, dict):
            return
        web = self.gateway.settings(db)["disappearing"]
        for entry in pending["conflicts"]:
            kind, name = entry["key"].split(":", 1)
            if kind != "timer":
                continue
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
