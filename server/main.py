"""Single-phone LAN FluidMessages Web. Run behind a TLS reverse proxy."""
import hashlib
import ipaddress
import json
import os
import re
import shutil
import socket
import sqlite3
import subprocess
import sys
import threading
import time
import unicodedata
from contextlib import closing
from http.cookies import CookieError, SimpleCookie
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, quote, unquote, urlsplit

import accounts
import auth
import chat_commands
import cloud_backup
import companion_tls
import lan_announce
import link_fetch
import message_requests
import nearby_pair
import privacy
import protocol_gateway
import quick_setup
import running_status
import sync_merge
import tor_route

ROOT = Path(__file__).resolve().parent
DB = Path(os.environ.get("FLUID_DB", "data/fluid.sqlite"))
# Optional. Phones, desktop apps, and scripts can use it as a bearer token; browsers sign in with a password.
TOKEN = os.environ.get("FLUID_TOKEN", "").strip()
# Optional extra origins (like https://messages.example.org) for older browsers behind a proxy that rewrites Host.
ORIGINS = {item.strip().rstrip("/") for item in os.environ.get("FLUID_ORIGINS", "").split(",") if item.strip()}
# Set by the default Docker Compose file together with its 127.0.0.1-only port: only this computer can reach the
# container, which sees the Docker gateway's address instead of loopback. Remove it before exposing the port.
LOCAL_ONLY = os.environ.get("FLUID_LOCAL_ONLY", "").strip().lower() in ("1", "true", "yes", "on")
# The desktop app (desktop/launcher.py) sets this. Its page answers only this computer; "Let my phone connect" opens
# just the HTTPS port to the local network, where a phone signs in with its pairing token.
DESKTOP = os.environ.get("FLUID_DESKTOP", "").strip() == "1"
FILES = {"/": ("index.html", "text/html"), "/app.js": ("app.js", "text/javascript"),
         "/reactions.js": ("reactions.js", "text/javascript"),
         "/style.css": ("style.css", "text/css"), "/icon.webp": ("icon.webp", "image/webp"),
         "/companion.js": ("companion.js", "text/javascript"), "/call.js": ("shared/call.js", "text/javascript"),
         "/cards.js": ("cards.js", "text/javascript"), "/chess.js": ("shared/chess.js", "text/javascript"),
         "/welcome.js": ("welcome.js", "text/javascript"),
         "/qr.js": ("qr.js", "text/javascript"), "/two-step.js": ("two-step.js", "text/javascript"),
         "/editor.js": ("editor.js", "text/javascript"), "/media.js": ("media.js", "text/javascript"),
         "/chats.js": ("chats.js", "text/javascript"), "/look.js": ("look.js", "text/javascript"),
         "/features.css": ("features.css", "text/css"), "/settings.js": ("settings.js", "text/javascript"),
         "/sync-merge.js": ("sync-merge.js", "text/javascript"), "/people.js": ("people.js", "text/javascript"),
         "/privacy.js": ("privacy.js", "text/javascript"), "/servers.js": ("servers.js", "text/javascript"),
         "/server-directory.js": ("server-directory.js", "text/javascript"),
         "/account-setup.js": ("account-setup.js", "text/javascript"),
         "/inbox-filters.js": ("inbox-filters.js", "text/javascript"),
         "/notify.js": ("notify.js", "text/javascript"), "/requests.js": ("requests.js", "text/javascript"),
         "/chat-commands.js": ("chat-commands.js", "text/javascript"), "/invites.js": ("invites.js", "text/javascript"),
         "/connections.js": ("connections.js", "text/javascript"), "/meetings.js": ("meetings.js", "text/javascript"),
         "/chat-features.css": ("chat-features.css", "text/css"), "/tor.js": ("tor.js", "text/javascript"),
         "/quick-setup.js": ("quick-setup.js", "text/javascript"),
         "/running.js": ("running.js", "text/javascript"),
         "/omemo.js": ("omemo.js", "text/javascript"),
         # The sample inbox's fictional portraits, the phone's own (res/drawable-nodpi/sample_*.webp).
         "/samples/maya.webp": ("samples/maya.webp", "image/webp"), "/samples/jordan.webp": ("samples/jordan.webp", "image/webp"),
         # And the photo in the sample Photo Walk group (sample_lakeside.webp).
         "/samples/lakeside.webp": ("samples/lakeside.webp", "image/webp"),
         "/manifest.webmanifest": ("manifest.webmanifest", "application/manifest+json"), "/sw.js": ("sw.js", "text/javascript"),
         "/favicon.ico": ("icons/favicon.ico", "image/x-icon"), "/icons/favicon-32.png": ("icons/favicon-32.png", "image/png"),
         "/icons/icon-192.png": ("icons/icon-192.png", "image/png"), "/icons/icon-512.png": ("icons/icon-512.png", "image/png"),
         "/icons/maskable-192.png": ("icons/maskable-192.png", "image/png"),
         "/icons/maskable-512.png": ("icons/maskable-512.png", "image/png"),
         "/icons/apple-touch-icon.png": ("icons/apple-touch-icon.png", "image/png")}
# Fun and effects, and counts on the tabs, kept for this server (every browser). The phone's sync snapshot never
# overwrites them; the settings merge combines them with the phone's (sync_merge.py "fun:" and "pref:" items).
PREFS = ("effects", "reactionEffects", "games", "random", "gifs", "linkPreviews", "tabCounts", "showCommunityChats")
# Sending GIFs and stickers (Settings → Media, GIFs, and fun), and the ways to call that work from a browser
# (Settings → Calls): Matrix voice and video, and meeting links. Web-only switches, on unless turned off; the merge has
# no items for them, so the phone keeps its own.
WEB_SWITCHES = ("sendGifs", "sendStickers", "callMatrix", "callMeetings")
PREFS += WEB_SWITCHES
# Settings → Accounts → IRC: call and meeting-link buttons in IRC chats. Off unless turned on, since many channels ban
# call and meeting links.
OFF_PREFS = ("ircCalls",)
# Whether the Communities page has shown, once, after the first IRC, Matrix, or XMPP account (welcome.js). Off until then.
OFF_PREFS += ("communitiesIntroSeen",)
# Whether "What is Tor?" has shown, once, the first time Connect through Tor is turned on (tor.js). Off until then.
OFF_PREFS += ("torIntroSeen",)
# Settings → Privacy and security → Only send encrypted messages (quick_setup.py). Off unless turned on.
OFF_PREFS += (quick_setup.PREF,)
# Messages → Communities' sort order, and Settings → Messaging → Inbox filters: which filters the inbox filter menu
# shows, in order. A hidden filter is kept with a "-" in front, so its place survives turning it back on.
COMMUNITY_SORTS = ("recent", "name", "joined")
INBOX_FILTERS = ("inbox", "unread", "favorites", "groups", "direct", "texts", "matrix", "xmpp", "email", "irc", "requests", "automated",
                 "archived", "blocked")


def clean_inbox_filters(value):
    """An ordered list of filter ids, each shown or "-" hidden. Unknown or repeated ids, or hiding Inbox, are refused.
    Filters missing from the list (added in a later version) are appended, shown."""
    if not isinstance(value, list) or len(value) > len(INBOX_FILTERS) or not all(isinstance(item, str) for item in value):
        raise ValueError("Invalid inbox filters")
    ids = [item.removeprefix("-") for item in value]
    if any(item not in INBOX_FILTERS for item in ids) or len(set(ids)) != len(ids) or "-inbox" in value:
        raise ValueError("Invalid inbox filters")
    return list(value) + [item for item in INBOX_FILTERS if item not in ids]


# Settings → Calls → Meeting links: the services you can pick when you send a meeting link (Zoom first), your own saved
# links, the services you added, the one used last, and the Jitsi server. FluidMessages has no account with Zoom,
# Google, or Microsoft, so those three send a link you saved; Jitsi makes a new room each time. A service you added
# ("custom") has a name, an https link, and optionally the host the link must be on. The same rules as meetings.js and
# MeetingServices.kt.
MEETING_SERVICES = {"zoom": True, "meet": True, "teams": True, "jitsi": True}  # on at first
MEETING_LINKS = {
    "zoom": re.compile(r"https://(?:[a-z0-9-]+\.)*zoom\.us/(?:j/\d{9,11}|my/[A-Za-z0-9._-]{1,64})(?:[?#]\S*)?", re.I | re.A),
    "meet": re.compile(r"https://meet\.google\.com/[a-z]{3}-[a-z]{4}-[a-z]{3}(?:[?#]\S*)?", re.I | re.A),
    "teams": re.compile(r"https://teams\.(?:microsoft|live)\.com/(?:l/meetup-join/\S+|meet/[A-Za-z0-9]+(?:[?#]\S*)?)", re.I | re.A)}
MEETING_SERVER = re.compile(r"[A-Za-z0-9.-]{3,253}(:[0-9]{1,5})?", re.A)
MEETING_CUSTOM_LINK = re.compile(r"https://([A-Za-z0-9-]{1,63}(?:\.[A-Za-z0-9-]{1,63})+)(?::\d{1,5})?(?:[/?#]\S*)?", re.A)
MEETING_HOST = re.compile(r"[a-z0-9-]{1,63}(?:\.[a-z0-9-]{1,63})+", re.A)
MEETING_CUSTOM_ID = re.compile(r"c[0-9a-f]{8}", re.A)
MEETING_NAME_MAX, MEETING_CUSTOM_MAX = 32, 10
# The names the built-in services use in a message, which a service you add can't take.
MEETING_BUILT_IN_NAMES = {"zoom", "google meet", "microsoft teams", "teams", "jitsi meet"}
# Settings saved before Google Meet and Teams were on at first have no version. Those two were off unless turned on,
# so they're turned on once; from version 2 on, what you chose stays.
MEETING_VERSION = 2


def default_meetings():
    return {"version": MEETING_VERSION, "on": dict(MEETING_SERVICES), "links": {}, "custom": [], "last": "", "jitsiServer": "meet.jit.si"}


def clean_meeting_custom(items):
    """Your own meeting services, checked: each has an id, a tidy name of 1 to 32 letters that isn't a built-in
    service's or another of yours, an https link, and a host ("" for any) that the link must be on."""
    if not isinstance(items, list) or len(items) > MEETING_CUSTOM_MAX:
        raise ValueError("Invalid meeting links")
    clean, names = [], set()
    for item in items:
        if not isinstance(item, dict) or any(key not in ("id", "name", "link", "host") for key in item):
            raise ValueError("Invalid meeting links")
        key, name, link, host = item.get("id"), item.get("name"), item.get("link"), item.get("host", "")
        if not isinstance(key, str) or not MEETING_CUSTOM_ID.fullmatch(key) or any(key == other["id"] for other in clean):
            raise ValueError("Invalid meeting links")
        if (not isinstance(name, str) or name != " ".join(name.split()) or not 1 <= len(name.encode("utf-16-le")) // 2 <= MEETING_NAME_MAX
                or any(unicodedata.category(char) in ("Cc", "Cf") for char in name)
                or name.lower() in MEETING_BUILT_IN_NAMES or name.lower() in names):
            raise ValueError("That name can't be used for a meeting service")
        if not isinstance(host, str) or host and (len(host) > 253 or not MEETING_HOST.fullmatch(host)):
            raise ValueError("Invalid meeting links")
        match = MEETING_CUSTOM_LINK.fullmatch(link) if isinstance(link, str) and len(link) <= 2000 else None
        on = match and match.group(1).lower()
        if not match or any(char.isspace() for char in link) or host and on != host and not on.endswith("." + host):
            raise ValueError("That isn't a meeting link for that service")
        names.add(name.lower())
        clean.append({"id": key, "name": name, "link": link, "host": host})
    return clean


def clean_meetings(value):
    """Meeting-link choices, checked. Missing parts keep their defaults, so settings saved before a part existed still
    load; anything of the wrong kind, an unknown service, or a link that isn't that service's own is refused."""
    if not isinstance(value, dict) or any(key not in ("version", "on", "links", "custom", "last", "jitsiServer") for key in value):
        raise ValueError("Invalid meeting links")
    if value.get("version", 1) not in (1, MEETING_VERSION) or type(value.get("version", 1)) is not int:
        raise ValueError("Invalid meeting links")
    clean = default_meetings()
    on, links = value.get("on", {}), value.get("links", {})
    if not isinstance(on, dict) or not isinstance(links, dict):
        raise ValueError("Invalid meeting links")
    clean["custom"] = clean_meeting_custom(value.get("custom", []))
    ids = {**MEETING_SERVICES, **{item["id"]: True for item in clean["custom"]}}
    clean["on"].update({item["id"]: True for item in clean["custom"]})
    for key, item in on.items():
        if key not in ids or type(item) is not bool:
            raise ValueError("Invalid meeting links")
        clean["on"][key] = item
    if "version" not in value:
        # From before Google Meet and Teams were on at first: off there meant "never turned on", so they're on now.
        clean["on"]["meet"] = clean["on"]["teams"] = True
    if not any(clean["on"].values()):
        raise ValueError("One meeting service always stays on")
    for key, item in links.items():
        if key not in MEETING_LINKS or not isinstance(item, str) or len(item) > 2000 or not MEETING_LINKS[key].fullmatch(item):
            raise ValueError("That isn't a meeting link for that service")
        clean["links"][key] = item
    last, server = value.get("last", ""), value.get("jitsiServer", "meet.jit.si")
    if last not in ("", *ids) or not isinstance(server, str) or not MEETING_SERVER.fullmatch(server):
        raise ValueError("Invalid meeting links")
    clean["last"], clean["jitsiServer"] = last, server
    return clean


def default_prefs():
    return {**{key: True for key in PREFS}, **{key: False for key in OFF_PREFS}, "communitySort": "recent", "inboxFilters": list(INBOX_FILTERS),
            "meetings": default_meetings(), **message_requests.defaults()}

def appearance_snapshot(value):
    if not isinstance(value, dict):
        return {}
    clean = {}
    for key in ("dark", "exactTimes", "mmsReady", "protocolTags", "protocolPrivacy", "combineProtocols", "tabCounts", "announceSettings",
                "showCommunityChats"):
        if type(value.get(key)) is bool:
            clean[key] = value[key]
    if value.get("communitySort") in COMMUNITY_SORTS:
        clean["communitySort"] = value["communitySort"]
    try:
        clean["inboxFilters"] = clean_inbox_filters(value["inboxFilters"])
    except (KeyError, ValueError):
        pass
    for key, lower, upper in (("radius", 2, 30), ("size", 12, 28), ("padding", 6, 24)):
        if type(value.get(key)) is int and lower <= value[key] <= upper:
            clean[key] = value[key]
    colors = value.get("colors")
    if isinstance(colors, dict):
        clean["colors"] = {
            key: colors[key] for key in ("canvas", "ink", "muted", "surface", "raised", "line", "outline", "action",
                                          "incoming", "incomingInk", "sms", "smsInk",
                                          "automated", "automatedInk", "accent", "accentInk",
                                          "rcs", "rcsInk", "matrix", "matrixInk", "xmpp", "xmppInk", "email", "emailInk", "irc", "ircInk",
                                          "nearby", "nearbyInk")
            if isinstance(colors.get(key), str) and re.fullmatch(r"#[0-9a-fA-F]{6}", colors[key])
        }
    for key in ("tagMode", "encryptionMode"):
        if value.get(key) in ("auto", "always", "off"):
            clean[key] = value[key]
    if isinstance(value.get("enabledProtocols"), list):
        clean["enabledProtocols"] = [key for key in ("sms", "mms", "matrix", "xmpp", "email", "irc")
                                     if key in value["enabledProtocols"]]
    defaults = value.get("protocolDefaults")
    if isinstance(defaults, dict):
        clean["protocolDefaults"] = {key: value for key, value in list(defaults.items())[:2000]
                                     if len(key) <= 150 and value in ("sms", "mms", "matrix", "xmpp", "email", "irc")}
    folders = value.get("folders")
    if isinstance(folders, dict):
        clean["folders"] = {
            key: [entry for entry in folders[key][:2000] if isinstance(entry, str) and len(entry) <= 150]
            for key in ("pinned", "archived", "blocked") if isinstance(folders.get(key), list)
        }
    senders = value.get("senderTypes")
    if isinstance(senders, dict):
        # Whether the phone treats a chat as an automated sender or a person.
        clean["senderTypes"] = {key: kind for key, kind in list(senders.items())[:2000]
                                if isinstance(key, str) and len(key) < 200 and kind in ("automated", "person")}
    return clean


THEMES = ("fluid", "midnight", "lavender", "evergreen", "tideline", "ember", "love", "rosewater")
# Bubble colors, and the background, buttons, and toolbar tints (the phone's canvas, controlColor, and chromeColor).
OWN_COLORS = ("sms", "rcs", "automated", "incoming", "matrix", "xmpp", "email", "irc", "nearby", "canvas", "controls", "chrome")


def clean_web_appearance(value):
    """The web app's own look, used without a phone or when you choose it over the phone's settings."""
    if not isinstance(value, dict):
        raise ValueError("Invalid appearance")
    clean = {"theme": value.get("theme") if value.get("theme") in THEMES else "fluid",
             "mode": value.get("mode") if value.get("mode") in ("system", "light", "dark") else "system"}
    # contrast: Palette contrast, Standard (no key) or Increased (True), as on the phone's Customize colors.
    for key in ("exactTimes", "combineProtocols", "announceSettings", "contrast"):
        if type(value.get(key)) is bool:
            clean[key] = value[key]
    for key in ("tagMode", "encryptionMode"):
        if value.get(key) in ("auto", "always", "off"):
            clean[key] = value[key]
    for key, lower, upper in (("radius", 2, 30), ("size", 12, 28), ("padding", 6, 24)):
        if type(value.get(key)) is int and lower <= value[key] <= upper:
            clean[key] = value[key]
    colors = value.get("colors")
    if isinstance(colors, dict):
        clean["colors"] = clean_colors(colors)
    clean.update(clean_look_extras(value))
    return clean


WALLPAPERS = ("none", "horizon", "aurora", "love", "orchard", "photo")
LOOK_CHOICES = {"wallpaper": WALLPAPERS, "searchPosition": ("top", "bottom"), "toolbarFinish": ("plain", "tinted", "floating"),
                "density": ("compact", "standard", "roomy"), "sendSound": ("silent", "fluid", "pop", "tick"),
                "receiveSound": ("silent", "fluid", "ding", "bubble"),
                "haptics": ("off", "soft", "tap", "confirm", "long")}


def clean_colors(colors):
    return {key: colors[key] for key in OWN_COLORS
            if isinstance(colors.get(key), str) and re.fullmatch(r"#[0-9a-fA-F]{6}", colors[key])}


def clean_look_extras(value):
    """Wallpaper, inbox layout, motion, sounds and their volume, and saved palettes, the same choices as the phone's appearance page."""
    clean = {}
    for key, allowed in LOOK_CHOICES.items():
        if value.get(key) in allowed:
            clean[key] = value[key]
    for key in ("tails", "mutedSounds"):
        if type(value.get(key)) is bool:
            clean[key] = value[key]
    for key, lower, upper in (("chromeOpacity", 25, 100), ("speed", 0, 800), ("soundVolume", 0, 100)):
        if type(value.get(key)) is int and lower <= value[key] <= upper:
            clean[key] = value[key]
    chats = value.get("chatWallpapers")
    if isinstance(chats, dict):
        clean["chatWallpapers"] = {key: item for key, item in list(chats.items())[:2000]
                                   if isinstance(key, str) and len(key) <= 300 and item in WALLPAPERS}
    # Each chat's own theme and colors (conversation details → Theme and wallpaper), like the phone's ChatProfile:
    # a named theme, and outgoing, incoming, and background colors. Kept on this server only; the merge has no item
    # for them, so they never reach the phone. The chat's wallpaper is chatWallpapers, which does sync.
    themes = value.get("chatThemes")
    if isinstance(themes, dict):
        kept = {}
        for key, item in list(themes.items())[:2000]:
            if not isinstance(key, str) or not 0 < len(key) <= 300 or not isinstance(item, dict):
                continue
            entry = {name: item[name] for name in ("outgoing", "incoming", "background")
                     if isinstance(item.get(name), str) and re.fullmatch(r"#[0-9a-fA-F]{6}", item[name])}
            if item.get("theme") in THEMES:
                entry["theme"] = item["theme"]
            if entry:
                kept[key] = entry
        clean["chatThemes"] = kept
    palettes = value.get("palettes")
    if isinstance(palettes, dict):
        clean["palettes"] = {
            name: {"theme": item.get("theme") if item.get("theme") in THEMES else "fluid",
                   "colors": clean_colors(item["colors"]) if isinstance(item.get("colors"), dict) else {},
                   **({"contrast": True} if item.get("contrast") is True else {})}
            for name, item in list(palettes.items())[:20]
            if isinstance(name, str) and 0 < len(name.strip()) <= 40 and isinstance(item, dict)}
    if isinstance(value.get("activePalette"), str) and value["activePalette"] in clean.get("palettes", {}):
        clean["activePalette"] = value["activePalette"]
    return clean


def web_chats(db):
    """Pinned, archived, blocked, and muted chats, and chat codes, kept by FluidMessages Web for every browser."""
    row = db.execute("SELECT value FROM protocol_state WHERE key='web_chats'").fetchone()
    return json.loads(row[0]) if row else {"pinned": [], "archived": [], "blocked": [], "muted": [], "mute_until": {}, "locks": {}}


def clean_web_chats(value):
    if not isinstance(value, dict):
        raise ValueError("Invalid chat settings")
    clean = {}
    for key in ("pinned", "archived", "blocked", "muted"):
        items = value.get(key, [])
        if not isinstance(items, list) or len(items) > 2000 or any(not isinstance(item, str) or len(item) > 300 for item in items):
            raise ValueError("Invalid chat settings")
        clean[key] = list(dict.fromkeys(items))
    if value.get("requests"):
        # Message requests you accepted or deleted, by address (message_requests.py).
        clean["requests"] = message_requests.clean_decisions(value["requests"])
    # When each timed mute ends (epoch milliseconds). A muted chat without one is muted forever, as every mute from
    # before was. Mutes whose time is up are dropped here, lazily: nothing has to run when they end.
    until = value.get("mute_until", {})
    if not isinstance(until, dict) or len(until) > 2000 or any(
            not isinstance(key, str) or len(key) > 300 or type(ends) is not int or ends < 0 for key, ends in until.items()):
        raise ValueError("Invalid chat settings")
    now = int(time.time() * 1000)
    clean["muted"] = [key for key in clean["muted"] if not 0 < until.get(key, 0) <= now]
    until = {key: ends for key, ends in until.items() if ends > now and key in clean["muted"]}
    if until:
        clean["mute_until"] = until
    locks = value.get("locks", {})
    if not isinstance(locks, dict) or len(locks) > 2000:
        raise ValueError("Invalid chat codes")
    clean["locks"] = {}
    for key, lock in locks.items():
        if not isinstance(key, str) or len(key) > 300 or not isinstance(lock, dict):
            raise ValueError("Invalid chat codes")
        # A lock that opens with a computer's screen lock (Windows Hello, Touch ID, or the device PIN) keeps only
        # the browser's credential id for it: no key and no code.
        if "credential" in lock and "salt" not in lock:
            if not isinstance(lock["credential"], str) or not re.fullmatch(r"[A-Za-z0-9_-]{16,1400}", lock["credential"]):
                raise ValueError("Invalid chat codes")
            clean["locks"][key] = {"credential": lock["credential"]}
            continue
        # Only a salted PBKDF2 hash of each chat code is kept, never the code.
        if not all(isinstance(lock.get(part), str) and re.fullmatch(r"[0-9a-f]{32,128}", lock[part]) for part in ("salt", "hash")):
            raise ValueError("Invalid chat codes")
        clean["locks"][key] = {"salt": lock["salt"], "hash": lock["hash"]}
    senders = value.get("senders", {})
    if not isinstance(senders, dict) or len(senders) > 2000 or any(
            not isinstance(key, str) or len(key) >= 200 or kind not in ("automated", "person") for key, kind in senders.items()):
        raise ValueError("Invalid chat settings")
    if senders:
        # Automated sender or person, per chat, as the phone sets it (combined by the merge).
        clean["senders"] = dict(senders)
    return clean


def web_favorites(db):
    """Starred contacts in the Calls tab: one address (number or chat address) per favorite, kept for every browser."""
    row = db.execute("SELECT value FROM protocol_state WHERE key='web_favorites'").fetchone()
    return json.loads(row[0]) if row else []


def clean_web_favorites(value):
    if not isinstance(value, list) or len(value) > 500 or any(
            not isinstance(item, str) or not item.strip() or len(item) > 300 or re.search(r"[\x00-\x1f]", item) for item in value):
        raise ValueError("Invalid favorites")
    return list(dict.fromkeys(value))


# Contacts made in FluidMessages Web: a name, phone numbers, and chat addresses, kept for every browser and fetched by
# the phone (GET /api/contacts), which shows them and, once you allow it, saves them into Android's contacts. They
# never become merge items: older phones would read unknown items as deletions, so they travel on their own endpoint,
# which older phones never call and older servers answer with 404. Nothing secret is part of a contact.
CONTACT_SERVICES = ("matrix", "xmpp", "email", "irc", "sip")
CONTACT_NUMBER = re.compile(r"(?=(?:[^0-9]*[0-9]){3})\+?[0-9][0-9 ().-]{2,39}")
CONTACT_ADDRESS = {
    "matrix": accounts.MXID,
    "xmpp": accounts.JID,
    "email": re.compile(r"[^@\s<>\"'\x00-\x1f]{1,64}@[A-Za-z0-9](?:[A-Za-z0-9-]{0,62}\.)+[A-Za-z]{2,63}"),
    # A nickname, optionally on a network: riley or riley@irc.libera.chat.
    "irc": re.compile(accounts.NICK.pattern + r"(?:@[A-Za-z0-9](?:[A-Za-z0-9.-]{0,251}[A-Za-z0-9])?)?"),
    "sip": re.compile(r"(?:sip:)?[^@\s<>\"'\x00-\x1f]{1,64}@[A-Za-z0-9](?:[A-Za-z0-9.:-]{0,251}[A-Za-z0-9])?"),
}
CONTACT_ID = re.compile(r"wc-[0-9a-f]{16}")
CONTACT_LIMIT = 2000


def web_contacts(db):
    row = db.execute("SELECT value FROM protocol_state WHERE key='web_contacts'").fetchone()
    return json.loads(row[0]) if row else []


def clean_contact(value):
    """A contact's name, numbers, and chat addresses, checked. Raises ValueError with what to fix."""
    if not isinstance(value, dict):
        raise ValueError("Invalid contact")
    name = value.get("name")
    if not isinstance(name, str) or not name.strip():
        raise ValueError("Enter a name")
    if re.search(r"[\x00-\x1f\x7f]", name):
        raise ValueError("Names are up to 100 characters, on one line")
    name = " ".join(name.split())
    if len(name) > 100:
        raise ValueError("Names are up to 100 characters")
    numbers = value.get("numbers") or []
    if not isinstance(numbers, list) or len(numbers) > 10:
        raise ValueError("Add up to 10 phone numbers")
    clean_numbers = []
    for number in numbers:
        if not isinstance(number, str) or not CONTACT_NUMBER.fullmatch(number.strip()):
            raise ValueError("Enter phone numbers with digits, like +1 202 555 0143")
        if number.strip() not in clean_numbers:
            clean_numbers.append(number.strip())
    addresses = value.get("addresses") or []
    if not isinstance(addresses, list) or len(addresses) > 20:
        raise ValueError("Add up to 20 chat addresses")
    clean_addresses = []
    for item in addresses:
        service = item.get("service") if isinstance(item, dict) else None
        address = item.get("address") if isinstance(item, dict) else None
        if service not in CONTACT_SERVICES or not isinstance(address, str):
            raise ValueError("Choose a service for each chat address")
        address = address.strip()
        if service in ("xmpp", "email"):
            address = address.lower()
        if not CONTACT_ADDRESS[service].fullmatch(address):
            raise ValueError({"matrix": "Matrix addresses look like @maya:example.org",
                              "xmpp": "XMPP addresses look like maya@example.org",
                              "email": "Email addresses look like maya@example.org",
                              "irc": "IRC nicknames look like riley, or riley@irc.libera.chat for a network",
                              "sip": "SIP addresses look like maya@sip.example.org"}[service])
        entry = {"service": service, "address": address}
        if entry not in clean_addresses:
            clean_addresses.append(entry)
    return {"name": name, "numbers": clean_numbers, "addresses": clean_addresses}


def save_contact(db, data, hide=None):
    """New contact (no id) or Edit (the contact's id). The server picks ids and times. [hide] is set for a browser that
    hasn't opened the private folder: it never saw those numbers and addresses, so its edit keeps them."""
    contacts = web_contacts(db)
    clean = clean_contact(data)
    now = int(time.time() * 1000)
    ident = data.get("id")
    if ident is None or ident == "":
        if len(contacts) >= CONTACT_LIMIT:
            raise ValueError("That's the most contacts FluidMessages Web keeps")
        contact = {"id": "wc-" + os.urandom(8).hex(), **clean, "created": now, "updated": now}
        contacts.append(contact)
    else:
        found = next((item for item in contacts if item["id"] == ident), None) if isinstance(ident, str) else None
        if found is None:
            raise ValueError("That contact was deleted. Go back and try again.")
        if hide:
            clean["numbers"] += [number for number in found.get("numbers", []) if hide(number) and number not in clean["numbers"]]
            clean["addresses"] += [entry for entry in found.get("addresses", []) if hide(entry["address"]) and entry not in clean["addresses"]]
        found.update(clean)
        found["updated"] = max(now, found.get("updated", 0) + 1)
        contact = found
    db.execute("INSERT OR REPLACE INTO protocol_state VALUES ('web_contacts', ?)", (json.dumps(contacts),))
    return {"contact": contact, "web_contacts": contacts}


def delete_contact(db, data):
    contacts = web_contacts(db)
    ident = data.get("id")
    left = [item for item in contacts if item["id"] != ident]
    if not isinstance(ident, str) or len(left) == len(contacts):
        raise ValueError("That contact is already gone")
    db.execute("INSERT OR REPLACE INTO protocol_state VALUES ('web_contacts', ?)", (json.dumps(left),))
    return {"deleted": ident, "web_contacts": left}


def contacts_view(db):
    """What the phone fetches: every web contact and a revision, so it can tell when something changed."""
    contacts = web_contacts(db)
    return {"supported": 1, "contacts": contacts,
            "revision": hashlib.sha256(json.dumps(contacts, sort_keys=True).encode()).hexdigest()[:16]}


def web_appearance(db):
    row = db.execute("SELECT value FROM protocol_state WHERE key='web_appearance'").fetchone()
    return json.loads(row[0]) if row else {"theme": "fluid", "mode": "system"}


def web_prefs(db):
    row = db.execute("SELECT value FROM protocol_state WHERE key='prefs'").fetchone()
    stored = json.loads(row[0]) if row else {}
    prefs = {key: stored.get(key, True) is not False for key in PREFS}
    prefs.update({key: stored.get(key) is True for key in OFF_PREFS})
    prefs["communitySort"] = stored.get("communitySort") if stored.get("communitySort") in COMMUNITY_SORTS else "recent"
    try:
        prefs["inboxFilters"] = clean_inbox_filters(stored["inboxFilters"])
    except (KeyError, ValueError):
        prefs["inboxFilters"] = list(INBOX_FILTERS)
    try:
        prefs["meetings"] = clean_meetings(stored["meetings"])
    except (KeyError, ValueError):
        prefs["meetings"] = default_meetings()
    # Settings → Privacy and security → Requests and spam.
    prefs.update(message_requests.read_prefs(stored))
    return prefs


def clean_prefs(value, current):
    if not isinstance(value, dict):
        raise ValueError("Invalid preferences")
    clean = {}
    for key, item in value.items():
        if (key in PREFS or key in OFF_PREFS) and type(item) is bool:
            clean[key] = item
        elif key == "communitySort" and item in COMMUNITY_SORTS:
            clean[key] = item
        elif key == "inboxFilters":
            clean[key] = clean_inbox_filters(item)
        elif key == "meetings":
            clean[key] = clean_meetings(item)
        elif key in message_requests.PREFS:
            clean[key] = message_requests.clean_pref(key, item)
        else:
            raise ValueError("Invalid preferences")
    return {**current, **clean}


def delete_for_me(db, data):
    """Delete for me: removes FluidMessages Web's copy of an internet message and its file. Other people keep theirs.
    A message still waiting to send is cancelled first, so the queue never sends something that was deleted."""
    key = data.get("id")
    if not isinstance(key, str) or not re.fullmatch(r"[A-Za-z0-9_-]{1,120}", key):
        raise ValueError("Invalid message")
    if not db.execute("SELECT 1 FROM protocol_messages WHERE id=?", (key,)).fetchone():
        raise ValueError("That message is already gone")
    db.execute("UPDATE protocol_jobs SET state='cancelled' WHERE id=? AND state='queued'", (key,))
    db.execute("DELETE FROM protocol_messages WHERE id=?", (key,))
    try:
        gateway.file_path(key).unlink(missing_ok=True)
    except (ValueError, OSError):
        pass
    return {"deleted": key}


def retry_failed(db, data):
    """Try again on an internet message that couldn't send: the same queued job runs again, with its text, reply, and
    file, as the phone's "Try again" does. Only a failed message can be retried, so nothing is ever sent twice."""
    key = data.get("id")
    if not isinstance(key, str) or not re.fullmatch(r"[A-Za-z0-9_-]{1,120}", key):
        raise ValueError("Invalid message")
    row = db.execute("""SELECT messages.payload FROM protocol_jobs jobs JOIN protocol_messages messages ON messages.id=jobs.id
        WHERE jobs.id=? AND jobs.state='failed'""", (key,)).fetchone()
    if row is None:
        raise ValueError("This message isn’t waiting to be sent again")
    now = int(time.time() * 1000)
    if db.execute("SELECT count(*) FROM protocol_jobs WHERE date>=?", (now - 60_000,)).fetchone()[0] >= 30:
        raise ValueError("Too many messages queued. Try again in a minute.")
    message = json.loads(row[0])
    message["status"] = "Queued"
    db.execute("UPDATE protocol_messages SET payload=? WHERE id=?", (json.dumps(message), key))
    db.execute("UPDATE protocol_jobs SET state='queued', date=? WHERE id=? AND state='failed'", (now, key))
    gateway.wake.set()
    return {"message": message}


def welcomed(db):
    """First-run welcome is done, or this install was in use before it existed."""
    if db.execute("SELECT 1 FROM protocol_state WHERE key='welcomed'").fetchone():
        return True
    return (authority.has_owner(db) or db.execute("SELECT 1 FROM inbox").fetchone() is not None
            or db.execute("SELECT 1 FROM protocol_messages LIMIT 1").fetchone() is not None or bool(gateway.accounts.redacted()))


def settings_view(db):
    """FluidMessages Web's own settings, and whether a phone has synced, so the browser works with or without one."""
    row = db.execute("SELECT updated FROM inbox WHERE id=1").fetchone()
    return {**gateway.settings(db), "web_appearance": web_appearance(db), "prefs": web_prefs(db), "welcomed": welcomed(db),
            "web_chats": web_chats(db), "web_favorites": web_favorites(db), "web_contacts": web_contacts(db), "phone":{"paired": row is not None, "last_sync": row[0] if row else None},
            **merge_snapshot(db)}


def web_extras(db):
    row = db.execute("SELECT value FROM protocol_state WHERE key='web_extras'").fetchone()
    return sync_merge.clean_extras(json.loads(row[0])) if row else None


def merge_snapshot(db):
    """What sync-merge.js needs on every refresh: differences waiting for a choice, and the shared extras."""
    stickers = merger.stickers(db)
    return {"merge": merger.summary(db), "web_extras": web_extras(db),
            "stickers_rev": hashlib.sha256(json.dumps(sorted(stickers)).encode()).hexdigest()[:16]}


def merge_hooks():
    def save_appearance(db, look):
        db.execute("INSERT OR REPLACE INTO protocol_state VALUES ('web_appearance', ?)", (json.dumps(clean_web_appearance(look)),))

    def save_prefs(db, prefs):
        db.execute("INSERT OR REPLACE INTO protocol_state VALUES ('prefs', ?)", (json.dumps(clean_prefs(prefs, web_prefs(db))),))

    def save_chats(db, chats):
        db.execute("INSERT OR REPLACE INTO protocol_state VALUES ('web_chats', ?)", (json.dumps(clean_web_chats(chats)),))

    def save_favorites(db, favorites):
        db.execute("INSERT OR REPLACE INTO protocol_state VALUES ('web_favorites', ?)", (json.dumps(clean_web_favorites(favorites[:500])),))

    return {"appearance": lambda db: dict(web_appearance(db)), "prefs": web_prefs,
            "chats": lambda db: {**{"pinned": [], "archived": [], "blocked": [], "muted": [], "locks": {}}, **web_chats(db)},
            "save_appearance": save_appearance, "save_prefs": save_prefs, "save_chats": save_chats,
            "favorites": web_favorites, "save_favorites": save_favorites,
            # Chats in the web's private folder stay out of the merge, both ways.
            "private": lambda db: privacy.matcher(private_all(db))}


class EraseLimiter:
    """Erase everything on this server: at most 5 tries in 10 minutes from one address, and 20 from everywhere."""

    def __init__(self, clock=time.time):
        self.clock = clock
        self.lock = threading.Lock()
        self.tries = {}
        self.everyone = []

    def allow(self, client):
        now = self.clock()
        with self.lock:
            self.everyone = [moment for moment in self.everyone if now - moment < 600]
            mine = [moment for moment in self.tries.get(client, []) if now - moment < 600]
            if len(mine) >= 5 or len(self.everyone) >= 20:
                self.tries[client] = mine
                return False
            self.tries[client] = mine + [now]
            self.everyone.append(now)
            if len(self.tries) > 10_000:
                self.tries = {key: value for key, value in self.tries.items() if value and now - value[-1] < 600}
            return True


ERASE_LIMIT = EraseLimiter()
MESSAGE_FILE = re.compile(r"[A-Za-z0-9_-]{1,120}")


def erase_files(ids):
    """Attachments (one file per message id), uploads waiting for a message, and the Matrix encryption store. In a
    files folder of its own every message-named file goes; when FLUID_FILES shares the database's folder, only the
    files of messages it knew about, so nothing else in that folder is touched. Links are removed, never followed."""
    root = gateway.files
    if not root.is_dir():
        return
    dedicated = root.resolve() != DB.parent.resolve()
    known = {name for name in ids if isinstance(name, str) and MESSAGE_FILE.fullmatch(name)}
    for entry in root.iterdir():
        if entry.name == "uploads" and entry.is_dir() and not entry.is_symlink():
            for item in entry.iterdir():
                if item.is_symlink() or item.is_file():
                    item.unlink(missing_ok=True)
        elif (entry.name in ("matrix-store", "omemo-store", "stickers")
              or re.fullmatch(r"(matrix|omemo)-store-[0-9a-f]{8}", entry.name)) \
                and entry.is_dir() and not entry.is_symlink():
            shutil.rmtree(entry, ignore_errors=True)
        elif (entry.is_symlink() or entry.is_file()) and (entry.name in known or dedicated and MESSAGE_FILE.fullmatch(entry.name)):
            entry.unlink(missing_ok=True)
    (root / "uploads").mkdir(parents=True, exist_ok=True)


def erase_everything():
    """Settings → Privacy and security → Erase everything on this server. Deletes this server's messages, chats,
    the phone's queued texts, chat accounts and their passwords, settings, pairing tokens, sessions, and the web
    password, then starts over as a new install, so the first-run welcome appears. FLUID_TOKEN, Docker Compose
    accounts, the server's protocol config file, and rclone's cloud destinations are the server admin's and stay."""
    gateway.erase()
    store = gateway.accounts
    with store.lock:
        for path in (store.path, store.path.with_suffix(".tmp")):
            path.unlink(missing_ok=True)
        store.generation += 1
    with closing(connect()) as db:
        db.execute("BEGIN IMMEDIATE")
        ids = [row[0] for row in db.execute("SELECT id FROM protocol_messages UNION SELECT id FROM protocol_uploads")]
        tables = [row[0] for row in db.execute("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'")]
        for table in tables:
            db.execute('DELETE FROM "%s"' % table.replace('"', '""'))
        db.commit()
        # Rewrites the file so deleted rows don't linger in free pages or the write-ahead log.
        db.execute("VACUUM")
        db.execute("PRAGMA wal_checkpoint(TRUNCATE)")
    erase_files(ids)
    gateway.erase()
    with DECOYS.lock:
        DECOYS.sessions.clear()
    authority.code_file.unlink(missing_ok=True)
    with closing(connect()) as db, db:
        code = authority.setup_code(db)
    print("FluidMessages Web: everything on this server was erased. Open it on this computer to start again, or set it up "
          "from another device with this one-time setup code: %s" % authority.pretty(code), flush=True)


# Held while an emergency erase runs. Every signed-in request waits for it, so nothing is read halfway through.
ERASE_GATE = threading.Lock()
DECOYS = privacy.DecoyWorld()


def private_all(db):
    """The chats this session can't see until it opens the private folder: the ones moved there, and every chat on
    a service it hides whole."""
    return list(dict.fromkeys(privacy.private_addresses(db)
                              + privacy.service_addresses(db, privacy.hidden_services(db), gateway.routes)))


def decoy_real_messages(db):
    """Decoy mode → Real chats that stay visible: their messages, as the real inbox has them, without files (a
    decoy session can't open them). Never a private chat, even if it was chosen before it became private."""
    allowed = privacy.matcher(privacy.decoy_allowed(db))
    if allowed is None:
        return []
    hide = privacy.matcher(private_all(db))
    keep = lambda message: allowed(message.get("address")) and not (hide and hide(message.get("address")))
    settings = gateway.settings(db)
    row = db.execute("SELECT content FROM inbox WHERE id=1").fetchone()
    phone = [message for message in gateway.visible(settings, json.loads(row[0]) if row else []) if keep(message)]
    rows = db.execute("SELECT payload FROM protocol_messages ORDER BY date DESC, rowid DESC LIMIT 1500")
    internet = [message for message in gateway.visible(settings, [json.loads(item[0]) for item in rows]) if keep(message)]
    return [{key: value for key, value in message.items() if key not in ("attachment", "expires", "expire_seconds")}
            for message in (phone + internet)[:600]]


DECOYS.real = decoy_real_messages


def wait_for_erase():
    with ERASE_GATE:
        pass


def emergency_erase(session_value, remember, label):
    """The emergency erase passcode: Erase everything's own path, started at once so the answer to the person who
    typed it isn't slower than a normal one. Their browser's session comes back afterward as a decoy session."""
    ERASE_GATE.acquire()

    def run():
        try:
            erase_everything()
            with closing(connect()) as db, db:
                authority.restore_session(db, session_value, remember, label)
        except Exception as error:  # noqa: BLE001 — the gate must open again whatever happens
            print("FluidMessages Web: erase didn't finish (%s)" % type(error).__name__, file=sys.stderr, flush=True)
        finally:
            ERASE_GATE.release()
    threading.Thread(target=run, name="fluid-erase", daemon=True).start()


def connect():
    connection = sqlite3.connect(DB, timeout=10)
    connection.row_factory = sqlite3.Row
    return connection


def load_tor(db):
    """Settings → Privacy and security → Connect through Tor (tor_route), off by default. FLUID_TOR_PROXY sets the first
    proxy address, such as tor:9050 next to a Tor container."""
    row = db.execute("SELECT value FROM protocol_state WHERE key='tor'").fetchone()
    stored = json.loads(row[0]) if row else {}
    tor_route.configure(stored.get("on") is True, stored.get("proxy") or os.environ.get("FLUID_TOR_PROXY") or tor_route.DEFAULT_PROXY,
                        stored.get("hosts") or {})


def save_tor(db, data):
    """Turning it on needs "understood": the warning was shown and accepted, as on the phone."""
    if not isinstance(data, dict) or type(data.get("on", False)) is not bool:
        raise ValueError("Invalid Tor setting")
    proxy = data.get("proxy", tor_route.status()["proxy"])
    tor_route.parse_proxy(proxy)
    on = data.get("on", tor_route.enabled())
    if on and not tor_route.enabled() and data.get("understood") is not True:
        raise ValueError("Turn on “I understand” first")
    db.execute("INSERT OR REPLACE INTO protocol_state VALUES ('tor', ?)",
               (json.dumps({"on": on, "proxy": proxy, "hosts": tor_route.hosts()}),))
    tor_route.configure(on, proxy)
    return tor_route.status()


def save_connection_tor(db, data):
    """A chat's Connect through Tor: one account's connection (IRC network, Matrix account, or mailbox) through Tor or
    not, whatever the setting. XMPP and servers on your own network can't, and say why (tor_route.chat_choice)."""
    ident = data.get("account") if isinstance(data, dict) else None
    if not isinstance(ident, str) or type(data.get("on")) is not bool:
        raise ValueError("Invalid Tor choice")
    entry = next((item for item in gateway.accounts.all() if item["id"] == ident), None)
    if entry is None:
        raise ValueError("That account isn't on FluidMessages Web")
    current = gateway.tor_states().get(ident)
    if current is None or not current["allowed"]:
        raise ValueError((current or {}).get("why") or "This chat can't use Tor")
    chosen = tor_route.choose(tor_route.account_hosts(entry["protocol"], entry["values"]), data["on"])
    status = tor_route.status()
    db.execute("INSERT OR REPLACE INTO protocol_state VALUES ('tor', ?)",
               (json.dumps({"on": status["on"], "proxy": status["proxy"], "hosts": chosen}),))
    # Its connection reconnects with the new route; the others keep theirs. In the background, so the switch answers
    # at once even while a connection takes its time to stop.
    def refresh():
        try:
            gateway.refresh_clients()
        except Exception:
            pass
    threading.Thread(target=refresh, name="tor-refresh", daemon=True).start()
    return gateway.tor_states()[ident]


def protocol_settings(db):
    row = db.execute("SELECT content FROM phone_appearance WHERE id=1").fetchone()
    return json.loads(row["content"]) if row else {}


CSP = ("default-src 'self'; img-src 'self' data: blob:; media-src 'self' blob:; connect-src 'self' https://lichess.org; "
       "object-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'")
COOKIE, SECURE_COOKIE = "fluid-session", "__Host-fluid-session"
# Two-step sign-in's "Remember this browser for 30 days": skips the code after the password (auth.Auth.trust_browser).
TRUST_COOKIE, SECURE_TRUST_COOKIE = "fluid-two-step", "__Host-fluid-two-step"
AUTH_OPEN = ("/api/auth/setup", "/api/auth/login", "/api/auth/token", "/api/auth/logout", "/api/auth/local", "/api/auth/two-step")
STATIC_CACHE = "no-cache"
ICON_CACHE = "public, max-age=86400"

# A Host header worth showing back as an address: a name or IPv4 address, or [IPv6], with an optional port.
SHOWN_HOST = re.compile(r"(?:[a-z0-9](?:[a-z0-9-]{0,62}[a-z0-9])?(?:\.[a-z0-9](?:[a-z0-9-]{0,62}[a-z0-9])?)*|\[[0-9a-f:.]{2,45}\])(?::\d{1,5})?")
# Home and office networks, and private networks such as Tailscale (100.64.0.0/10).
LAN_NETWORKS = tuple(ipaddress.ip_network(value) for value in ("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "100.64.0.0/10"))


def lan_ipv4():
    """This computer's IPv4 addresses on its home network, found without contacting anything: the address the system
    would send from to reach each private range (connecting a UDP socket only picks a route; nothing is sent), and the
    ones its own name resolves to."""
    found = []
    for probe in ("192.168.0.1", "10.0.0.1", "172.16.0.1", "100.100.100.100"):
        try:
            with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
                sock.connect((probe, 9))
                found.append(sock.getsockname()[0])
        except OSError:
            pass
    try:
        found += [info[4][0] for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET)]
    except OSError:
        pass
    keep = []
    for value in found:
        try:
            address = ipaddress.ip_address(value)
        except ValueError:
            continue
        if any(address in network for network in LAN_NETWORKS) and value not in keep:
            keep.append(value)
    # Home Wi-Fi addresses (192.168.x.x) first: the likeliest one for a phone on the same Wi-Fi.
    return sorted(keep, key=lambda value: (not value.startswith("192.168."), ipaddress.ip_address(value) in LAN_NETWORKS[3]))


# The server's own HTTPS for phones (companion_tls): {"port", "fingerprint", "bind"} once it's serving, else None.
TLS = None
TLS_SERVER = None
TLS_LOCK = threading.Lock()
# Find my computer: the mDNS answer phones on the Wi-Fi find this computer by (lan_announce), while the HTTPS port
# listens there, and the phones asking to connect (nearby_pair), approved in Connect your phone.
ANNOUNCER = None
PAIR_REQUESTS = nearby_pair.Requests()


def computer_name():
    """This computer's name, as phones list it: its host name without the domain."""
    return (socket.gethostname() or "").split(".")[0][:50] or "Computer"


def announce():
    """Starts or stops answering phones that look for this computer on the Wi-Fi, to match the HTTPS port."""
    global ANNOUNCER
    if ANNOUNCER:
        ANNOUNCER.stop()
        ANNOUNCER = None
    if not TLS or os.environ.get("FLUID_ANNOUNCE", "1").strip() == "0":
        return
    reach = phone_reach("", False, tls=TLS)
    if not reach["listening"] or reach["container"]:
        return
    bind = TLS.get("bind") or ""
    addresses = lan_ipv4() if bind in ("", "0.0.0.0") else [bind]
    addresses = [value for value in addresses if ipaddress.ip_address(value) not in LAN_NETWORKS[3]] or addresses
    if not addresses:
        return
    announcer = lan_announce.Announcer(lan_announce.Announcement(computer_name(), TLS["port"], addresses, TLS["fingerprint"]))
    problem = announcer.start()
    if problem:
        print("FluidMessages Web: " + problem, flush=True)
        return
    ANNOUNCER = announcer


def phone_access():
    """The desktop app's "Let my phone connect": its HTTPS port listens on the local network. Kept in the data folder."""
    return DESKTOP and (DB.parent / "phone-access").exists()


def tls_bind():
    """Where the HTTPS port listens: everywhere when the desktop app lets phones connect, else where http does."""
    return "0.0.0.0" if phone_access() else os.environ.get("FLUID_BIND", "127.0.0.1")


def phone_reach(page_host, https, bind=None, port=None, local_only=None, container=None, addresses=None, tls=None,
                page_tls=False):
    """The addresses a phone can type to reach this server (Settings → Phone and other devices, the phone guide):
    this page's own address when it isn't localhost, and this computer's network addresses with the port. With the
    server's own HTTPS port on ([tls], companion_tls), those are https:// addresses on that port, marked "pinned": the
    phone trusts them by the certificate's fingerprint (also returned, for the QR code). Without it they're http://,
    and https comes from a reverse proxy in front of the server. [page_tls]: this page came in on the HTTPS port."""
    tls = TLS if tls is None else (tls or None)
    if bind is None:
        # The phone's addresses are on the HTTPS port when it's on, so that port's own listening address counts.
        bind = tls.get("bind") if tls and tls.get("bind") else os.environ.get("FLUID_BIND", "127.0.0.1")
    bind = bind.strip()
    port = int(os.environ.get("FLUID_PORT", 8080)) if port is None else port
    local_only = LOCAL_ONLY if local_only is None else local_only
    container = Path("/.dockerenv").exists() if container is None else container
    try:
        loopback = ipaddress.ip_address(bind).is_loopback
    except ValueError:
        loopback = bind.lower() == "localhost"
    # Only this computer can reach it: bound to 127.0.0.1, or Docker's 127.0.0.1-only port (FLUID_LOCAL_ONLY).
    listening = not (loopback or local_only)
    shown = []
    host = (page_host or "").strip().lower()
    if host and SHOWN_HOST.fullmatch(host) and not auth.loopback_name(host):
        if https:
            shown.append({"url": "https://" + host, "kind": "page", **({"pinned": True} if page_tls and tls else {})})
        elif tls:
            # Opened over http at this name: the phone uses the same name on the HTTPS port instead.
            name = host[:host.index("]") + 1] if host.startswith("[") else host.rsplit(":", 1)[0]
            shown.append({"url": "https://%s:%d" % (name, tls["port"]), "kind": "page", "pinned": True})
        else:
            shown.append({"url": "http://" + host, "kind": "page"})
    # Inside a container its own addresses are Docker's, which a phone can't reach: the page's address is shown instead.
    if listening and not container:
        if bind in ("", "0.0.0.0"):
            ips = lan_ipv4() if addresses is None else addresses
        else:
            ips = [bind]
        for ip in ips:
            url = "https://%s:%d" % (ip, tls["port"]) if tls else "http://%s:%d" % (ip, port)
            if all(item["url"] != url for item in shown):
                try:
                    kind = "private" if ipaddress.ip_address(ip) in LAN_NETWORKS[3] else "wifi"
                except ValueError:
                    kind = "wifi"
                shown.append({"url": url, "kind": kind, **({"pinned": True} if tls else {})})
    return {"addresses": shown, "port": port, "listening": listening, "container": container,
            "https": bool(https), "bind": bind, "tls": {"port": tls["port"], "fingerprint": tls["fingerprint"]} if tls else None,
            "desktop": DESKTOP, "phone_access": phone_access(),
            "nearby": bool(ANNOUNCER and ANNOUNCER.running and tls)}


class Handler(BaseHTTPRequestHandler):
    # A connection that sends nothing for a minute is dropped, so slow clients can't tie up the server.
    timeout = 60
    server_version = "FluidMessages"
    sys_version = ""
    session_key = None
    kind = None
    # Requests on the server's own HTTPS port (TlsHandler).
    tls = False
    # The session's privacy flags (auth.Auth.session_info): a decoy session, and whether it opened the private folder.
    decoy = False
    private_open = False

    def log_message(self, format, *args):
        # Paths only: query strings can hold links from messages or a GIF key, and logs never show those.
        sys.stderr.write("%s %s\n" % (self.command or "-", urlsplit(self.path or "").path[:120]))

    def log_request(self, code="-", size="-"):
        if isinstance(code, int) and code >= 400:
            sys.stderr.write("%s %s %s\n" % (self.command or "-", urlsplit(self.path or "").path[:120], code))

    def security_headers(self):
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Content-Security-Policy", CSP)
        self.send_header("Referrer-Policy", "no-referrer")
        self.send_header("X-Frame-Options", "DENY")
        self.send_header("Cross-Origin-Opener-Policy", "same-origin")
        self.send_header("Cross-Origin-Resource-Policy", "same-origin")
        self.send_header("Permissions-Policy", "camera=(self), microphone=(self), geolocation=(self), display-capture=(), usb=(), payment=()")
        if self.https():
            self.send_header("Strict-Transport-Security", "max-age=31536000")

    def reply(self, status, payload, content_type="application/json", headers=(), cache="no-store", body=True):
        data = payload if isinstance(payload, bytes) else json.dumps(payload).encode()
        self.send_response(status)
        text = content_type.startswith(("text/", "application/json", "application/manifest"))
        self.send_header("Content-Type", content_type + ("; charset=utf-8" if text else ""))
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", cache)
        self.security_headers()
        for name, value in headers:
            self.send_header(name, value)
        self.end_headers()
        if body:
            self.wfile.write(data)

    def static(self, path, body=True):
        """The app's own files. Icons may be cached for a day; the rest is checked each time so updates show at once."""
        name, content_type = FILES[path]
        cache = ICON_CACHE if content_type.startswith("image/") else STATIC_CACHE
        self.reply(200, (ROOT / name).read_bytes(), content_type, cache=cache, body=body)

    # Signing in

    def https(self):
        return self.tls or self.headers.get("X-Forwarded-Proto", "").split(",")[0].strip().lower() == "https" \
            or self.headers.get("Origin", "").startswith("https://")

    def client(self):
        return auth.client_key(self.client_address[0], self.headers.get("X-Forwarded-For", ""))

    def here(self, need_origin=False):
        """The request comes from the computer FluidMessages Web runs on (see auth.from_this_computer). Browsers speak
        HTTP/1.1; nginx passes requests on as HTTP/1.0 unless told otherwise, so those never count, even when a
        proxy on this computer was set up without X-Forwarded-For."""
        return self.request_version == "HTTP/1.1" and auth.from_this_computer(self.client_address[0], self.headers, LOCAL_ONLY, need_origin)

    def cookie(self, names=(SECURE_COOKIE, COOKIE)):
        try:
            jar = SimpleCookie(self.headers.get("Cookie", ""))
        except CookieError:
            return None
        for name in names:
            if name in jar:
                return jar[name].value
        return None

    def set_cookie(self, value, persistent, names=(SECURE_COOKIE, COOKIE)):
        secure = self.https()
        parts = [(names[0] if secure else names[1]) + "=" + value, "Path=/", "HttpOnly", "SameSite=Strict"]
        if secure:
            parts.append("Secure")
        if persistent:
            parts.append("Max-Age=%d" % auth.REMEMBER_SECONDS)
        return ("Set-Cookie", "; ".join(parts))

    def clear_cookies(self):
        return [("Set-Cookie", COOKIE + "=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0"),
                ("Set-Cookie", SECURE_COOKIE + "=; Path=/; HttpOnly; SameSite=Strict; Secure; Max-Age=0")]

    def same_origin(self):
        """Cookie requests that change something must come from this app's own page (CSRF protection). The custom
        header can't be sent from another site without a CORS preflight, which this server never allows."""
        if self.headers.get("X-Fluid-Request") != "1":
            return False
        site = self.headers.get("Sec-Fetch-Site")
        if site:
            # Every current browser says where a request came from, even when a proxy rewrites the Host header.
            return site == "same-origin"
        origin = self.headers.get("Origin")
        if not origin:
            return True
        hosts = {self.headers.get("Host", "").lower(), self.headers.get("X-Forwarded-Host", "").split(",")[0].strip().lower()}
        return urlsplit(origin).netloc.lower() in hosts - {""} or origin.rstrip("/") in ORIGINS

    def authorize(self, changing=False):
        wait_for_erase()
        header = self.headers.get("Authorization", "")
        with closing(connect()) as db, db:
            if header.startswith("Bearer "):
                try:
                    good = authority.bearer(db, self.client(), header[7:])
                except auth.Refused as error:
                    self.reply(error.status, {"error": str(error)})
                    return None
                if good:
                    self.kind = "token"
                    return "token"
                self.reply(401, {"error": "That access token isn't right", "signed_out": True})
                return None
            info = authority.session_info(db, self.cookie())
        if info is None:
            self.reply(401, {"error": "Sign in to FluidMessages Web", "signed_out": True},
                       headers=self.clear_cookies() if self.cookie() else ())
            return None
        if changing and not self.same_origin():
            self.reply(403, {"error": "This request didn't come from FluidMessages Web"})
            return None
        self.session_key = info["key"]
        self.kind = "session"
        self.decoy = info["decoy"]
        self.private_open = info["private_open"] and not info["decoy"]
        # The one central check for decoy sessions: they only ever reach the decoy world (privacy.DecoyWorld), except
        # Show your real inbox, which asks for the private folder passcode.
        if self.decoy and not (self.command == "POST" and self.path == "/api/privacy/reveal"):
            self.decoy_reply()
            return None
        return "session"

    def decoy_reply(self):
        parts = urlsplit(self.path)
        defaults = {"settings": protocol_gateway.Gateway.DEFAULT_SETTINGS, "web_appearance": {"theme": "fluid", "mode": "system"},
                    "prefs": default_prefs(), "enabled": sorted(sync_merge.INTERNET)}
        with closing(connect()) as db:
            if self.command == "GET":
                status, payload = DECOYS.get(db, self.session_key, parts.path, parts.query, defaults, {
                    "https": self.https(), "here": self.here(), "local_only": LOCAL_ONLY, "reach": self.reach(),
                    "xmpp": protocol_gateway.xmpp_client.available(), "two_step": authority.two_step_status(db)})
            elif self.command == "POST" and parts.path in ("/api/schedule", "/api/call", "/api/cancel", "/api/settings",
                                                            "/api/protocols/read", "/api/protocols", "/api/protocols/send"):
                try:
                    data = self.read_json()
                except (ValueError, TypeError) as error:
                    self.reply(400, {"error": str(error)})
                    return
                status, payload = DECOYS.post(db, self.session_key, parts.path, data, defaults,
                                              {"appearance": clean_web_appearance, "prefs": clean_prefs})
            else:
                status, payload = DECOYS.REFUSED
        self.reply(status, payload)

    def private_check(self, db):
        """None when this request may see private chats; otherwise what hides them (privacy.scrub)."""
        return None if self.private_open else privacy.matcher(private_all(db))

    def read_json(self, limit=64_000):
        size = int(self.headers.get("Content-Length", "0"))
        if not 0 < size <= limit:
            raise ValueError("Invalid request size")
        data = json.loads(self.rfile.read(size))
        if not isinstance(data, dict):
            raise ValueError("Expected an object")
        return data

    def auth_request(self, path):
        """Sign-in endpoints, which work before you're signed in."""
        wait_for_erase()
        if self.command == "GET":
            with closing(connect()) as db, db:
                info = authority.session_info(db, self.cookie())
                # "local": this browser is on the computer FluidMessages Web runs on, so it opens without a password.
                # A decoy session sees a server with a password, even after an emergency erase.
                self.reply(200, {"owner": authority.has_owner(db) or bool(info and info["decoy"]), "signed_in": info is not None,
                                 "local": self.here() and authority.local_enabled(db)})
            return
        if not self.same_origin():
            self.reply(403, {"error": "This request didn't come from FluidMessages Web"})
            return
        try:
            # Signing out needs no body; anything sent with it is ignored.
            data = {} if path == "/api/auth/logout" else self.read_json()
            label = (self.headers.get("User-Agent") or "")[:120]
            remember = data.get("remember") is True
            outcome = None
            trusted = None
            with closing(connect()) as db, db:
                if path == "/api/auth/setup":
                    value, persistent = authority.setup(db, self.client(), data.get("code"), data.get("password"), remember, label)
                elif path == "/api/auth/login":
                    # The password, the decoy password, or the emergency erase passcode: answered the same way.
                    value, persistent, outcome = authority.login(db, self.client(), data.get("password"), remember, label,
                                                                 trusted=self.cookie((SECURE_TRUST_COOKIE, TRUST_COOKIE)))
                    if outcome == "two_step":
                        # Not signed in yet: the same page asks for the code next, with this ticket.
                        self.reply(200, {"signed_in": False, "two_step": True, "ticket": value})
                        return
                elif path == "/api/auth/two-step":
                    value, persistent, outcome = authority.finish_two_step(db, self.client(), data.get("ticket"), data.get("code"))
                    if data.get("trust") is True:
                        trusted = authority.trust_browser(db)
                elif path == "/api/auth/token":
                    value, persistent = authority.login_with_token(db, self.client(), data.get("token"), remember, label)
                elif path == "/api/auth/local":
                    if not self.here(need_origin=True):
                        raise auth.Refused(403, "Sign in to open FluidMessages Web on this device.")
                    value, persistent = authority.local_session(db, "This computer")
                else:
                    authority.end_session(db, self.cookie())
                    self.reply(200, {"signed_in": False}, headers=self.clear_cookies())
                    return
            if outcome == "distress":
                emergency_erase(value, persistent, label)
        except auth.Refused as error:
            self.reply(error.status, {"error": str(error)})
        except (ValueError, TypeError) as error:
            self.reply(400, {"error": str(error)})
        else:
            headers = [self.set_cookie(value, persistent)]
            if trusted:
                headers.append(self.set_cookie(trusted, True, (SECURE_TRUST_COOKIE, TRUST_COOKIE)))
            self.reply(200, {"signed_in": True}, headers=headers)

    def pair_open(self):
        """Find my computer on a phone: asking to connect, then waiting for Connect on this computer (nearby_pair).
        Only on the HTTPS port, since the six-digit code is made from its certificate."""
        wait_for_erase()
        try:
            if not self.tls or not TLS:
                raise ValueError("Connect at this computer's https address.")
            data = self.read_json(4000)
            if self.path == "/api/pair/hello":
                key = PAIR_REQUESTS.hello(self.client(), data.get("name"), data.get("secret"), TLS["fingerprint"])
                self.reply(200, {"id": key, "name": computer_name()})
            else:
                self.reply(200, PAIR_REQUESTS.wait(data.get("id"), data.get("secret")))
        except OverflowError as error:
            self.reply(429, {"error": str(error)})
        except (ValueError, TypeError) as error:
            self.reply(400, {"error": str(error)})

    def account_security(self, data):
        """Password, sessions, and the phone's pairing token, once signed in."""
        with closing(connect()) as db, db:
            if self.path == "/api/auth/password":
                authority.change_password(db, self.client(), data.get("current"), data.get("password"))
                authority.end_other_sessions(db, self.session_key)
            elif self.path == "/api/auth/sessions/end-others":
                authority.end_other_sessions(db, self.session_key)
            elif self.path == "/api/auth/pairing":
                token = authority.make_pairing(db)
                return {**self.security_view(db), "token": token}
            elif self.path == "/api/auth/pairing/delete":
                authority.remove_pairing(db)
            elif self.path == "/api/auth/pairing/nearby":
                # Connect (or Not this one) for a phone on the Wi-Fi that asked, from a browser only.
                if self.kind != "session":
                    raise auth.Refused(403, "Connect phones from FluidMessages Web in a browser.")
                name = PAIR_REQUESTS.answer(data.get("id"), data.get("allow") is True, lambda: authority.make_pairing(db))
                return {**self.security_view(db), "connected": name if data.get("allow") is True else None}
            elif self.path == "/api/auth/local-access":
                if type(data.get("enabled")) is not bool:
                    raise ValueError("Invalid setting")
                authority.set_local(db, data["enabled"])
            elif self.path.startswith("/api/auth/two-step/"):
                return self.two_step_change(db, data)
            else:
                raise ValueError("Unknown endpoint")
            return self.security_view(db)

    def two_step_change(self, db, data):
        """Two-step sign-in in Settings, from a browser only: the phone's token never changes how people sign in."""
        if self.kind != "session":
            raise auth.Refused(403, "Change two-step sign-in from FluidMessages Web in a browser.")
        action = self.path[len("/api/auth/two-step/"):]
        # The change first, then the state it leaves.
        if action == "begin":
            setup = authority.begin_two_step(db)
            return {**self.security_view(db), "setup": setup}
        if action in ("confirm", "recovery"):
            make = authority.confirm_two_step if action == "confirm" else authority.new_recovery_codes
            codes = make(db, self.client(), data.get("code"))
            return {**self.security_view(db), "recovery_codes": codes}
        if action == "off":
            authority.turn_off_two_step(db, self.client(), data.get("password"), data.get("code"), here=self.here(need_origin=True))
        elif action == "later":
            authority.skip_two_step(db)
        else:
            raise ValueError("Unknown endpoint")
        return self.security_view(db)

    def security_view(self, db):
        return {"owner": authority.has_owner(db), "sessions": authority.session_count(db),
                "pairing": authority.pairing_status(db), "https": self.https(),
                "two_step": authority.two_step_status(db),
                "local": authority.local_enabled(db), "here": self.here(), "local_only": LOCAL_ONLY, "reach": self.reach()}

    def reach(self):
        """Where a phone can reach this server (phone_reach), from the address this page was opened at."""
        forwarded = self.headers.get("X-Forwarded-Host", "").split(",")[0].strip()
        return phone_reach(forwarded or self.headers.get("Host", ""), self.https(), page_tls=self.tls)

    # Privacy and security: the private folder, decoy mode, and the emergency erase passcode (privacy.py)

    def privacy_status(self, db):
        open_now = self.private_open
        exists = authority.has_secret(db, "private_code")
        hide = self.private_check(db)
        return {"password": authority.has_owner(db),
                "private": {"set": exists, "open": open_now and exists,
                            "count": len(privacy.private_addresses(db)) if open_now and exists else None,
                            # Hide whole services: only once the folder is open, like the chats in it.
                            "services": privacy.hidden_services(db) if open_now and exists else None,
                            "close_code": privacy.close_needs_code(db)},
                "decoy": {"set": authority.has_secret(db, "decoy"), "chats": privacy.decoy_chats(db),
                          "allowed": [item for item in privacy.decoy_allowed(db) if not (hide and hide(item))]},
                "distress": {"set": authority.has_secret(db, "distress")},
                "quick_hide": exists and self.kind == "session"}

    def check_private_code(self, db, code):
        """The private folder passcode, or the emergency erase passcode in its place. Both are always checked, so a
        wrong code, the right one, and the emergency one take the same time. Wrong ones count toward rate limits."""
        client = self.client()
        authority.guard(client)
        distress = authority.check_secret(db, "distress", code)
        match = authority.check_secret(db, "private_code", code)
        if distress:
            return "distress"
        if match:
            authority.matched(client)
            return "match"
        authority.missed(client)
        return "wrong"

    def distress_here(self, db):
        """Erases everything, then keeps this browser going as a decoy session (see emergency_erase)."""
        row = db.execute("SELECT persistent, label FROM auth_sessions WHERE id=?", (self.session_key,)).fetchone()
        return (self.cookie(), bool(row and row[0]), row[1] if row else "")

    def privacy_change(self, data):
        if self.kind != "session":
            raise auth.Refused(403, "Change privacy settings from FluidMessages Web in a browser.")
        path = self.path.removeprefix("/api/privacy/")
        erase_after = None
        client = self.client()
        with closing(connect()) as db, db:
            exists = authority.has_secret(db, "private_code")
            if path in ("private/unlock", "reveal"):
                outcome = self.check_private_code(db, data.get("code"))
                if outcome == "distress":
                    erase_after = self.distress_here(db)
                elif outcome == "match":
                    authority.set_session_flags(db, self.session_key, private_open=path == "private/unlock", decoy=False)
                if path == "private/unlock":
                    # A wrong passcode opens an empty folder rather than an error, as on the phone, so nobody can tell
                    # whether anything is hidden. The emergency passcode looks the same as a wrong one.
                    response = {"open": outcome == "match"}
                elif outcome != "match":
                    response = None
                else:
                    response = {"ok": True}
            elif path == "private/lock":
                authority.set_session_flags(db, self.session_key, private_open=False)
                response = {"open": False}
            elif path == "private/setup":
                if exists and not self.private_open:
                    raise auth.Refused(403, "Open the private folder first")
                code = data.get("code")
                if not isinstance(code, str) or not 4 <= len(code) <= auth.MAX_CODE:
                    raise ValueError("Use at least 4 characters")
                if code != data.get("again"):
                    raise ValueError("The passcodes don't match")
                authority.refuse_reuse(db, code, "owner", "decoy", "distress")
                authority.set_secret(db, "private_code", code)
                if not exists:
                    privacy.save_private(db, [])
                authority.set_session_flags(db, self.session_key, private_open=True)
                self.private_open = True
                response = self.privacy_status(db)
            elif path == "private/move":
                address = privacy.clean_address(data.get("address"))
                if not exists:
                    raise auth.Refused(409, "Set up the private folder first, in Settings → Privacy and security")
                current = privacy.private_addresses(db)
                if data.get("private") is True:
                    privacy.save_private(db, current + [address])
                else:
                    if not self.private_open:
                        raise auth.Refused(403, "Open the private folder first")
                    privacy.save_private(db, [item for item in current if item != address])
                response = self.privacy_status(db)
            elif path in ("private/services", "private/close-code"):
                if not exists:
                    raise auth.Refused(409, "Set up the private folder first, in Settings → Privacy and security")
                if not self.private_open:
                    raise auth.Refused(403, "Open the private folder first")
                if path == "private/services":
                    privacy.save_hidden_services(db, data.get("services"))
                else:
                    privacy.save_close_needs_code(db, data.get("on"))
                response = self.privacy_status(db)
            elif path == "private/off":
                if exists and not self.private_open:
                    raise auth.Refused(403, "Open the private folder first")
                authority.set_secret(db, "private_code", None)
                privacy.save_private(db, None)
                privacy.save_hidden_services(db, None)
                authority.lock_private_everywhere(db)
                self.private_open = False
                response = self.privacy_status(db)
            elif path == "quick-hide":
                if not exists:
                    raise auth.Refused(409, "Set up the private folder first, in Settings → Privacy and security")
                authority.set_session_flags(db, self.session_key, decoy=True, private_open=False)
                response = {"ok": True}
            elif path in ("decoy", "decoy/off"):
                if not authority.has_owner(db):
                    raise auth.Refused(409, "Decoy mode works with a password. Set one in Settings → Phone and other devices first.")
                authority.confirm_password(db, client, data.get("current"))
                if path == "decoy":
                    password = data.get("password")
                    auth.validate_password(password)
                    if password != data.get("again"):
                        raise ValueError("The passwords don't match")
                    authority.refuse_reuse(db, password, "owner", "distress", "private_code")
                    authority.set_secret(db, "decoy", password)
                else:
                    authority.set_secret(db, "decoy", None)
                response = self.privacy_status(db)
            elif path == "decoy/chats":
                privacy.save_decoys(db, data.get("chats"))
                response = self.privacy_status(db)
            elif path == "decoy/allowed":
                # A locked session can't see private chats, so it can't pick one; a private one is dropped anyway.
                privacy.save_decoy_allowed(db, data.get("addresses"), privacy.matcher(private_all(db)))
                response = self.privacy_status(db)
            elif path in ("distress", "distress/off"):
                authority.confirm_password(db, client, data.get("current"))
                if path == "distress":
                    code = data.get("code")
                    if not isinstance(code, str) or not 6 <= len(code) <= auth.MAX_CODE:
                        raise ValueError("Use at least 6 characters")
                    if code != data.get("again"):
                        raise ValueError("The passcodes don't match")
                    if data.get("understood") is not True:
                        raise ValueError("Confirm that this passcode erases everything")
                    authority.refuse_reuse(db, code, "owner", "decoy", "private_code")
                    authority.set_secret(db, "distress", code)
                else:
                    authority.set_secret(db, "distress", None)
                response = self.privacy_status(db)
            else:
                raise ValueError("Unknown endpoint")
        if erase_after:
            emergency_erase(*erase_after)
        if response is None:
            raise auth.Refused(403, "That passcode doesn't match")
        return response

    def do_HEAD(self):
        path = urlsplit(self.path).path
        if path in FILES:
            self.static(path, body=False)
        else:
            self.reply(404, {"error": "Unknown endpoint"}, body=False)

    def do_GET(self):
        path = urlsplit(self.path).path
        if path in FILES:
            self.static(path)
            return
        if path == "/api/auth":
            self.auth_request(path)
            return
        if not self.authorize():
            return
        if path == "/api/auth/security":
            with closing(connect()) as db:
                self.reply(200, self.security_view(db))
            return
        if path == "/api/auth/pairing/nearby":
            if self.kind != "session":
                self.reply(403, {"error": "Connect phones from FluidMessages Web in a browser."})
                return
            self.reply(200, {"phones": PAIR_REQUESTS.waiting(), "nearby": bool(ANNOUNCER and ANNOUNCER.running)})
            return
        if self.path == "/api/backups":
            self.reply(200, {"targets": list(cloud_backup.remotes())})
            return
        match = re.fullmatch(r"/api/backups/(onedrive|drive|s3)/latest", self.path)
        if match:
            self.backup_transfer(match[1], fetching=True)
            return
        if self.path.startswith("/api/gifs?"):
            query = parse_qs(urlsplit(self.path).query)
            pick = lambda name: (query.get(name) or [""])[0][:200]
            # The key comes in a header, so it never sits in an address or a log.
            key = (self.headers.get("X-Fluid-Gif-Key") or pick("key"))[:200]
            # Settings → Media → API address: a compatible server or proxy in place of Tenor's or GIPHY's own.
            base = (self.headers.get("X-Fluid-Gif-Api") or "")[:300]
            self.reply(200, {"results": link_fetch.gif_search(pick("provider"), pick("q"), key, pick("stickers") == "1", base)})
            return
        if self.path.startswith(("/api/preview?url=", "/api/picture?url=")):
            target = unquote(self.path.split("?url=", 1)[1])
            if self.path.startswith("/api/preview"):
                self.reply(200, {"preview": link_fetch.preview(target)})
            else:
                found = link_fetch.picture(target)
                if found:
                    self.reply(200, found[1], found[0])
                else:
                    self.reply(404, {"error": "That picture isn't available"})
            return
        match = re.fullmatch(r"/api/protocols/file\?id=([A-Za-z0-9_-]{1,120})", self.path)
        if match:
            self.send_file(match[1])
            return
        match = re.fullmatch(r"/api/stickers/file\?id=([A-Za-z0-9_-]{1,80})", self.path)
        if match:
            self.send_sticker(match[1])
            return
        with closing(connect()) as db:
            if self.path == "/api/inbox":
                row = db.execute("""
                    SELECT inbox.content, inbox.updated, phone_appearance.content AS appearance
                    FROM inbox LEFT JOIN phone_appearance ON inbox.id=phone_appearance.id WHERE inbox.id=1
                """).fetchone()
                history = gateway.snapshot(db)
                settings = gateway.settings(db)
                phone = gateway.visible(settings, json.loads(row["content"]) if row else [])
                messages = phone + history["messages"]
                # Each disappearing message says when it goes, so the browser can show its timer.
                messages = [{**message, "expires": at} if not message.get("expires") and (at := gateway.expiry(settings, message)) > 0
                            else message for message in messages]
                messages.sort(key=lambda message: message["date"], reverse=True)
                self.reply(200, privacy.scrub({"messages": messages, "routes": history["routes"], "enabled": history["enabled"],
                                 # IRC channels' topics and member lists, for a group's details.
                                 "channels": history["channels"],
                                 # Invitations and contact requests waiting on the Invitations page.
                                 "invites": history["invites"], "connections": history["connections"], "tor": history["tor"],
                                 "accounts": history["accounts"],
                                 "updated": row["updated"] if row else None,
                                 "appearance": json.loads(row["appearance"]) if row and row["appearance"] else {},
                                 # Which settings the browser uses: the phone's (the default) or its own.
                                 "follow_phone": settings["follow_phone"], "web_appearance": web_appearance(db),
                                 "prefs": web_prefs(db), "welcomed": welcomed(db), "web_chats": web_chats(db),
                                 # The private folder's chats, once this session opened it with its passcode.
                                 "private_chats": private_all(db) if self.private_open else [],
                                 "web_favorites": web_favorites(db), "web_contacts": web_contacts(db), **merge_snapshot(db)},
                                 self.private_check(db)))
            elif self.path == "/api/privacy":
                self.reply(200, self.privacy_status(db))
            elif self.path == "/api/tor":
                self.reply(200, tor_route.status())
            elif self.path == "/api/running":
                # Settings → Running on this server (running.js, running_status.py). Read-only.
                self.reply(200, running_status.rows(running_status.facts(gateway, db, TLS, tor_route, cloud_backup)))
            elif self.path.startswith("/api/calls?since="):
                since = self.path.removeprefix("/api/calls?since=")
                self.reply(200, gateway.call_events(int(since) if since.isdigit() else 0))
            elif self.path == "/api/calls/ice":
                self.reply(200, gateway.ice_servers())
            elif self.path == "/api/settings":
                self.reply(200, privacy.scrub(settings_view(db), self.private_check(db)))
            elif self.path == "/api/merge":
                with db:
                    # The phone keeps the full baseline; a browser that hasn't opened the private folder doesn't.
                    # A phone names the newer item groups it understands (timed mutes, inbox choices, favorites,
                    # message requests); older phones never see those items (sync_merge.OPTIONAL). Phones from the
                    # first message requests release say X-Fluid-Merge-Requests: 1 instead of listing them.
                    timed = sync_merge.optional_groups(self.headers.get("X-Fluid-Merge-Items", "") + ","
                                                       + self.headers.get("X-Fluid-Merge-Groups", ""),
                                                       requests=self.headers.get("X-Fluid-Merge-Requests", "") == "1")
                    self.reply(200, merger.view(db, timed) if self.kind == "token" else privacy.scrub(merger.view(db), self.private_check(db)))
            elif self.path == "/api/contacts":
                # The phone fetches these to show and save them; a browser that hasn't opened the private folder doesn't see its numbers.
                self.reply(200, contacts_view(db) if self.kind == "token" else privacy.scrub(contacts_view(db), self.private_check(db)))
            elif self.path == "/api/stickers":
                self.reply(200, {"stickers": [{"id": key, **entry} for key, entry in merger.stickers(db).items()]})
            elif self.path == "/api/protocols/accounts":
                self.reply(200, {"accounts": gateway.accounts.redacted(), "xmpp": protocol_gateway.xmpp_client.available()})
            elif self.path == "/api/protocols/servers":
                self.reply(200, {"servers": gateway.servers()})
            elif self.path == "/api/jobs":
                rows = db.execute("SELECT * FROM jobs ORDER BY id DESC LIMIT 100").fetchall()
                internet = db.execute("""SELECT jobs.id, jobs.date AS due, jobs.state, messages.payload
                    FROM protocol_jobs jobs JOIN protocol_messages messages ON messages.id=jobs.id
                    ORDER BY jobs.date DESC LIMIT 100""")
                result = [dict(row) for row in rows]
                hide = self.private_check(db)
                for job in internet:
                    message = json.loads(job["payload"])
                    # Internet jobs show the chat's title; the private folder goes by the chat's address.
                    if hide and hide(message.get("address")):
                        continue
                    result.append({"id": job["id"], "due": job["due"], "protocol": message["protocol"],
                                   "address": message["title"], "body": message["body"], "state": job["state"]})
                self.reply(200, privacy.scrub(result, hide))
            else:
                self.reply(404, {"error": "Unknown endpoint"})

    def do_PUT(self):
        if not self.authorize(changing=True):
            return
        match = re.fullmatch(r"/api/backups/(onedrive|drive|s3)", self.path)
        if match:
            self.backup_transfer(match[1], fetching=False)
        else:
            self.reply(404, {"error": "Unknown endpoint"})

    def backup_transfer(self, target, fetching):
        remote = cloud_backup.remotes().get(target)
        if not remote:
            self.reply(404, {"error": "Backup destination is not configured"})
            return
        self.connection.settimeout(195)
        try:
            if fetching:
                cloud_backup.download_latest(remote, self)
            else:
                size = int(self.headers.get("Content-Length", "0"))
                self.reply(201, cloud_backup.upload(remote, self.rfile, size, self.headers.get("X-Fluid-Backup-Name")))
        except ValueError:
            self.reply(400, {"error": "Invalid or incomplete backup transfer"})
        except (subprocess.SubprocessError, FileNotFoundError):
            self.reply(502, {"error": "Backup destination could not be reached"})
        except (BrokenPipeError, ConnectionResetError, TimeoutError):
            self.close_connection = True

    def do_POST(self):
        if self.path in ("/api/pair/hello", "/api/pair/wait"):
            self.pair_open()
            return
        if self.path in AUTH_OPEN:
            self.auth_request(self.path)
            return
        kind = self.authorize(changing=True)
        if not kind:
            return
        if self.path == "/api/erase":
            self.erase(kind)
            return
        if self.path == "/api/protocols/upload":
            self.upload()
            return
        if self.path == "/api/stickers/upload":
            self.sticker_upload()
            return
        try:
            data = self.read_json(8_000_000)
            if self.path.startswith("/api/merge"):
                response = self.merge(data)
            elif self.path.startswith("/api/privacy/"):
                response = self.privacy_change(data)
            else:
                response = self.account_security(data) if self.path.startswith("/api/auth/") else self.mutate(data)
        except auth.Refused as error:
            self.reply(error.status, {"error": str(error)})
        except sync_merge.Conflict as error:
            self.reply(409, {"error": str(error)})
        except (KeyError, ValueError, TypeError) as error:
            self.reply(400, {"error": str(error)})
        except (ConnectionError, OSError) as error:
            # A chat server that's down or refused (joining or browsing a server, for example).
            self.reply(502, {"error": protocol_gateway.network_error(error)})
        else:
            self.reply(200, response)

    def erase(self, kind):
        """Erase everything on this server. Only from a signed-in browser (never a phone or access token), with the
        page's own CSRF header, the word ERASE, and the password again when there is one. Rate-limited."""
        if kind != "session":
            self.reply(403, {"error": "Erase everything from FluidMessages Web in a browser."})
            return
        client = self.client()
        if not ERASE_LIMIT.allow(client):
            self.reply(429, {"error": "Too many tries. Wait 10 minutes and try again."})
            return
        try:
            data = self.read_json()
            if data.get("confirm") != "ERASE":
                raise ValueError("Type ERASE to confirm.")
            with closing(connect()) as db, db:
                authority.confirm_password(db, client, data.get("password"))
            erase_everything()
        except auth.Refused as error:
            self.reply(error.status, {"error": str(error)})
        except (ValueError, TypeError) as error:
            self.reply(400, {"error": str(error)})
        else:
            # Every browser is signed out (the sessions are gone); this one also forgets its cookie and stored data.
            self.reply(200, {"erased": True}, headers=[*self.clear_cookies(), ("Clear-Site-Data", '"cache", "storage"')])

    def merge(self, data):
        """Combining the phone's and the web's settings (sync_merge.py). Only the phone posts a merge; either the phone
        or a signed-in browser (with the page's CSRF header, checked in authorize) can settle the differences."""
        with closing(connect()) as db, db:
            if self.path == "/api/merge":
                if self.kind != "token":
                    raise auth.Refused(403, "Only your phone sends a merge.")
                return merger.post(db, data)
            if self.path == "/api/merge/resolve":
                return merger.resolve(db, data)
            if self.path == "/api/merge/stickers/delete":
                merger.delete_sticker(db, data.get("id"))
                return {"stickers": [{"id": key, **entry} for key, entry in merger.stickers(db).items()]}
        raise ValueError("Unknown endpoint")

    def sticker_upload(self):
        self.connection.settimeout(60)
        try:
            size = int(self.headers.get("Content-Length", "0"))
            with closing(connect()) as db, db:
                response = merger.add_sticker(db, self.headers.get("X-Fluid-Sticker", ""), self.headers.get("Content-Type", ""),
                                              self.rfile, size)
        except (ValueError, OSError) as error:
            self.reply(400, {"error": str(error)})
        else:
            self.reply(200, response)

    def send_sticker(self, key):
        with closing(connect()) as db:
            path, entry = merger.sticker_path(db, key)
        if not path or not path.is_file():
            self.reply(404, {"error": "Sticker not found"})
            return
        # Served as a download, like attachments, so it never renders inside this origin by itself.
        self.reply(200, path.read_bytes(), "application/octet-stream",
                   headers=[("Content-Disposition", "attachment; filename=" + key + ".sticker"), ("X-Fluid-Type", entry["mime"])])

    def upload(self):
        self.connection.settimeout(130)
        try:
            size = int(self.headers.get("Content-Length", "0"))
            name = unquote(self.headers.get("X-Fluid-Name", ""))
            with closing(connect()) as db, db:
                response = gateway.store_upload(db, self.rfile, size, self.headers.get("Content-Type"), name)
        except (ValueError, OSError) as error:
            self.reply(400, {"error": str(error)})
        else:
            self.reply(200, response)

    def send_file(self, message_id):
        with closing(connect()) as db:
            row = db.execute("SELECT payload FROM protocol_messages WHERE id=?", (message_id,)).fetchone()
            hide = self.private_check(db)
        if row and hide and hide(json.loads(row[0]).get("address")):
            row = None
        attachment = json.loads(row[0]).get("attachment") if row else None
        try:
            path = gateway.file_path(message_id)
        except ValueError:
            path = None
        if not attachment or path is None or not path.is_file():
            self.reply(404, {"error": "Attachment not found"})
            return
        size = path.stat().st_size
        self.send_response(200)
        # Always a download: the browser never renders received files inside the companion's origin.
        self.send_header("Content-Type", "application/octet-stream")
        self.send_header("Content-Length", str(size))
        self.send_header("Content-Disposition", "attachment; filename*=UTF-8''" + quote(attachment["name"]))
        self.send_header("Cache-Control", "no-store")
        self.security_headers()
        self.end_headers()
        with open(path, "rb") as source:
            shutil.copyfileobj(source, self.wfile)

    def request_action(self, db, data):
        """Accept, delete, block, or block and report a message request (message_requests.py). Decoy sessions never get
        here (authorize), and a browser that hasn't opened the private folder can't act on its chats. The phone acts
        with its token on chats that come through this server."""
        hide = self.private_check(db) if self.kind == "session" else None
        if hide and hide(data.get("address")):
            raise ValueError("Choose a chat")

        def add_contact(address, route, name):
            if route is None and not re.fullmatch(r"\+?[0-9][0-9(). -]{2,38}", address):
                return None
            entry = {"name": (name if isinstance(name, str) and name.strip() else route["title"] if route else address)[:100]}
            if route is None:
                entry["numbers"] = [address]
            else:
                entry["addresses"] = [{"service": route["protocol"], "address": route["target"]}]
            try:
                return save_contact(db, entry, hide).get("contact")
            except (ValueError, KeyError):
                return None

        def save_chats(db, chats):
            db.execute("INSERT OR REPLACE INTO protocol_state VALUES ('web_chats', ?)", (json.dumps(clean_web_chats(chats)),))

        result = message_requests.act(gateway, db, self.path.removeprefix("/api/requests/"), data, web_chats(db), save_chats,
                                      add_contact)
        return privacy.scrub(result, hide)

    def mutate(self, data):
        now = int(time.time() * 1000)
        if self.path == "/api/protocols/join":
            # Saved first, then a few seconds for an IRC network's answer outside the database transaction, so a
            # refusal comes back in plain words (and a channel locked with a password can ask for it on the Join page).
            with closing(connect()) as db, db:
                joined = gateway.join(db, data)
            return gateway.join_outcome(data, joined)
        with closing(connect()) as db, db:
            if self.path == "/api/protocols":
                if self.kind == "session" and data.get("enabled") is not None:
                    # The web's own choice, kept apart from what the phone reports, so a merge can compare them.
                    chosen = data["enabled"]
                    if isinstance(chosen, list):
                        db.execute("INSERT OR REPLACE INTO protocol_state VALUES ('web_enabled', ?)",
                                   (json.dumps([key for key in sync_merge.INTERNET if key in chosen]),))
                return gateway.snapshot(db, data.get("enabled"))
            if self.path == "/api/tor" and self.kind == "session":
                return save_tor(db, data)
            if self.path == "/api/phone-access":
                # Only the person at this computer opens it to the network, never a phone or another device.
                if self.kind != "session" or not self.here(need_origin=True):
                    raise auth.Refused(403, "Turn this on from FluidMessages Web on this computer")
                set_phone_access(bool(data.get("on")))
                return {"reach": self.reach()}
            # A chat's Connect through Tor, from web chat details or the phone's (TorChat).
            if self.path == "/api/tor/connection":
                return save_connection_tor(db, data)
            if self.path == "/api/protocols/send":
                # Only send encrypted messages: the same lock the chat shows decides (quick_setup.py).
                quick_setup.check(web_prefs(db), data.get("protocol"), gateway.route_encrypted(data.get("address"), data.get("protocol")))
                return gateway.enqueue(db, data)
            if self.path == "/api/protocols/command":
                # A slash command typed in an IRC, Matrix, or XMPP chat (chat_features.py).
                return gateway.run_command(db, data)
            if self.path == "/api/protocols/invites":
                # Accept or decline an invitation or contact request.
                return gateway.answer_invite(db, data)
            if self.path == "/api/protocols/joinfirst":
                # A channel's details: stop joining another channel before it (chat_features.forget_join_first).
                return gateway.forget_join_first(db, data)
            if self.path == "/api/protocols/keep":
                # An IRC channel's Keep joined setting.
                return gateway.set_keep(db, data)
            if self.path == "/api/protocols/open":
                # Opening a channel that isn't kept joined rejoins it.
                return gateway.open_chat(data)
            if self.path == "/api/protocols/identify":
                # The NickServ card in an IRC chat (protocol_gateway.identify_nickserv). The password isn't logged.
                return gateway.identify_nickserv(db, data)
            if self.path == "/api/protocols/reconnect":
                # The Disconnected · Reconnect bar.
                return gateway.reconnect(data)
            if self.path == "/api/protocols/disconnect":
                # Disconnect a server (IRC network, Matrix or XMPP account) until you connect it again.
                return gateway.disconnect(db, data)
            if self.path == "/api/protocols/autoreconnect":
                # A server's Reconnect automatically switch (IRC networks, Matrix and XMPP accounts).
                return gateway.set_auto_reconnect(db, data)
            if self.path == "/api/protocols/accounts":
                # Saving replaces the account; a blank password keeps the saved one.
                # "id" edits that account and "add" adds another; neither (older phones) edits the first one.
                ident = data.get("id") if isinstance(data.get("id"), str) and data.get("id") else None
                gateway.accounts.save(data.get("protocol"), dict(data.get("fields") or {}), ident, data.get("add") is True)
                # Connect the new or changed account now, not at the next 15-second check, so Browse works right away.
                threading.Timer(0.2, gateway.refresh_clients).start()
                return {"accounts": gateway.accounts.redacted()}
            if self.path == "/api/protocols/accounts/delete":
                ident = data.get("id") if isinstance(data.get("id"), str) and data.get("id") else None
                gateway.accounts.delete(data.get("protocol"), ident)
                return {"accounts": gateway.accounts.redacted()}
            if self.path == "/api/protocols/servers/spaces":
                return gateway.spaces(data)
            if self.path == "/api/protocols/browse":
                return gateway.browse(data)
            if self.path == "/api/protocols/join":
                return gateway.join(db, data)
            if self.path == "/api/protocols/leave":
                return gateway.leave(db, data)
            if self.path == "/api/protocols/read":
                return gateway.mark_read(db, data)
            if self.path == "/api/protocols/delete":
                return delete_for_me(db, data)
            if self.path == "/api/protocols/retry":
                return retry_failed(db, data)
            if self.path == "/api/protocols/routes":
                return gateway.add_route(db, data)
            if self.path == "/api/calls/send":
                return gateway.send_call(data)
            if self.path == "/api/protocols/members":
                return gateway.change_member(db, data)
            if self.path == "/api/protocols/omemo":
                # An XMPP chat's Security section: OMEMO devices and fingerprints.
                return gateway.omemo_devices(data)
            if self.path == "/api/protocols/omemo/trust":
                return gateway.omemo_trust(data)
            if self.path == "/api/settings":
                # A browser that hasn't opened the private folder never saw its chats, so it can't drop their settings.
                hide = self.private_check(db)
                if hide and isinstance(data.get("web_chats"), dict):
                    data["web_chats"] = privacy.keep_private(web_chats(db), data["web_chats"], hide)
                if hide and isinstance(data.get("web_appearance"), dict):
                    data["web_appearance"] = privacy.keep_private(web_appearance(db), data["web_appearance"], hide)
                if hide and isinstance(data.get("disappearing"), dict):
                    data["disappearing"] = privacy.keep_private(gateway.settings(db)["disappearing"], data["disappearing"], hide)
                if "web_appearance" in data:
                    db.execute("INSERT OR REPLACE INTO protocol_state VALUES ('web_appearance', ?)",
                               (json.dumps(clean_web_appearance(data.pop("web_appearance"))),))
                if "prefs" in data:
                    db.execute("INSERT OR REPLACE INTO protocol_state VALUES ('prefs', ?)",
                               (json.dumps(clean_prefs(data.pop("prefs"), web_prefs(db))),))
                if "web_chats" in data:
                    db.execute("INSERT OR REPLACE INTO protocol_state VALUES ('web_chats', ?)",
                               (json.dumps(clean_web_chats(data.pop("web_chats"))),))
                if "web_favorites" in data:
                    db.execute("INSERT OR REPLACE INTO protocol_state VALUES ('web_favorites', ?)",
                               (json.dumps(clean_web_favorites(data.pop("web_favorites"))),))
                if "web_extras" in data:
                    db.execute("INSERT OR REPLACE INTO protocol_state VALUES ('web_extras', ?)",
                               (json.dumps(sync_merge.clean_extras(data.pop("web_extras"))),))
                if data.pop("welcomed", None) is True:
                    db.execute("INSERT OR REPLACE INTO protocol_state VALUES ('welcomed', 'true')")
                if data.get("reset") is True:
                    db.execute("DELETE FROM protocol_state WHERE key IN ('web_appearance', 'prefs')")
                gateway.save_settings(db, data)
                return privacy.scrub(settings_view(db), hide)
            if self.path.startswith("/api/requests/"):
                return self.request_action(db, data)
            if self.path == "/api/protocols/routes/delete":
                return gateway.delete_route(db, data)
            if self.path == "/api/contacts":
                hide = self.private_check(db) if self.kind == "session" else None
                return privacy.scrub(save_contact(db, data, hide), hide)
            if self.path == "/api/contacts/delete":
                hide = self.private_check(db) if self.kind == "session" else None
                return privacy.scrub(delete_contact(db, data), hide)
            if self.path == "/api/sync":
                messages = data["messages"]
                if not isinstance(messages, list) or len(messages) > 2000:
                    raise ValueError("Expected up to 2,000 messages")
                for message in messages:
                    if not isinstance(message, dict) or any(
                        type(message.get(key)) is not expected
                        for key, expected in {"address": str, "body": str, "date": int, "type": int}.items()
                    ):
                        raise ValueError("Invalid message")
                    if message.get("protocol", "sms") not in ("sms", "mms"):
                        raise ValueError("Invalid phone protocol")
                    if "thumbnail" in message and (not isinstance(message["thumbnail"], str)
                            or len(message["thumbnail"]) > 160_000 or not re.fullmatch(r"[A-Za-z0-9+/=]+", message["thumbnail"])):
                        raise ValueError("Invalid MMS thumbnail")
                phone_privacy = data.get("privacy")
                if isinstance(phone_privacy, dict):
                    merger.hold_timers(db, phone_privacy)
                    gateway.save_settings(db, phone_privacy, from_phone=True)
                db.execute("INSERT OR REPLACE INTO inbox VALUES (1, ?, ?)", (json.dumps(messages), now))
                db.execute("INSERT OR REPLACE INTO phone_appearance VALUES (1, ?)",
                           (json.dumps(appearance_snapshot(data.get("appearance"))),))
                return {"updated": now}
            if self.path == "/api/schedule":
                address, body, due = data["address"], data["body"], data["due"]
                format = data.get("protocol", "sms")
                if format not in ("sms", "mms"):
                    raise ValueError("Choose a carrier protocol")
                # Texts from the browser are never encrypted, so Only send encrypted messages keeps them (quick_setup.py).
                quick_setup.check(web_prefs(db), format, False)
                settings = protocol_settings(db)
                if format not in settings.get("enabledProtocols", ("sms", "mms")):
                    raise ValueError("This protocol is turned off on the phone")
                if format == "mms" and not settings.get("mmsReady"):
                    raise ValueError("Your paired Android app does not support browser MMS")
                if not isinstance(address, str):
                    raise ValueError("Enter a valid phone number")
                numbers = address[4:].split(",") if format == "mms" and address.startswith("mms:") else [address]
                if len(numbers) > 20 or any(not re.fullmatch(r"(?=.*[0-9])[+0-9*#(). -]{1,40}", number) for number in numbers):
                    raise ValueError("Enter valid phone numbers")
                if not isinstance(body, str) or not body.strip() or len(body.encode("utf-16-le")) > 3200:
                    raise ValueError("Enter 1–1,600 characters")
                if data.get("literal") is not True:
                    # Chat commands such as /me or /join never go out as a text (start with // to send one as written).
                    chat_commands.parse(format, body)
                if type(due) is not int or not now - 60_000 <= due <= now + 366 * 86_400_000:
                    raise ValueError("Choose a date within the next year")
                job = db.execute("INSERT INTO jobs (address, body, due, state, protocol) VALUES (?, ?, ?, 'scheduled', ?)",
                                 (address, body, due, format))
                return {"id": job.lastrowid}
            if self.path == "/api/call":
                # Click-to-call: the phone shows a "Tap to call" notification that opens its dialer.
                address = data["address"]
                if not isinstance(address, str) or not re.fullmatch(r"\+?[0-9][0-9(). -]{2,38}", address):
                    raise ValueError("Calls need a phone number")
                if db.execute("SELECT count(*) FROM jobs WHERE protocol='call' AND due>?", (now - 60_000,)).fetchone()[0] >= 5:
                    raise ValueError("Too many call requests. Try again in a minute.")
                job = db.execute("INSERT INTO jobs (address, body, due, state, protocol) VALUES (?, '', ?, 'scheduled', 'call')",
                                 (address, now))
                return {"id": job.lastrowid}
            if self.path == "/api/claim":
                db.execute("BEGIN IMMEDIATE")
                # A call request is only useful right away; after two minutes it expires instead of ringing later.
                db.execute("UPDATE jobs SET state='failed' WHERE protocol='call' AND state='scheduled' AND due<?", (now - 120_000,))
                job = db.execute("SELECT * FROM jobs WHERE state='scheduled' AND due<=? ORDER BY due,id LIMIT 1",
                                 (now,)).fetchone()
                if job:
                    db.execute("UPDATE jobs SET state='claimed' WHERE id=?", (job["id"],))
                return {"job": dict(job) if job else None}
            if self.path == "/api/result":
                state = data["state"]
                if state not in ("submitted", "failed"):
                    raise ValueError("Invalid result")
                db.execute("UPDATE jobs SET state=? WHERE id=? AND state='claimed'", (state, int(data["id"])))
                return {"ok": True}
            if self.path == "/api/cancel":
                key = data["id"]
                if isinstance(key, str) and key.startswith("gateway-"):
                    job = db.execute("UPDATE protocol_jobs SET state='cancelled' WHERE id=? AND state='queued'", (key,))
                    if not job.rowcount:
                        raise ValueError("This message is no longer scheduled")
                    row = db.execute("SELECT payload FROM protocol_messages WHERE id=?", (key,)).fetchone()
                    if row:
                        message = json.loads(row[0])
                        message["status"] = "Cancelled"
                        db.execute("UPDATE protocol_messages SET payload=? WHERE id=?", (json.dumps(message), key))
                    return {"ok": True}
                job = db.execute("UPDATE jobs SET state='cancelled' WHERE id=? AND state='scheduled'",
                                 (int(data["id"]),))
                if not job.rowcount:
                    raise ValueError("This message is no longer scheduled")
                return {"ok": True}
            raise ValueError("Unknown endpoint")


class TlsHandler(Handler):
    tls = True


def start_tls():
    """The phone's HTTPS port (companion_tls), next to the http one. It's skipped, with a plain message, when it's
    turned off, openssl is missing, or the port is taken; the http port works the same either way. Called again when
    the desktop app's "Let my phone connect" changes, to listen at the new address. Returns a problem, or None."""
    global TLS, TLS_SERVER
    with TLS_LOCK:
        port = companion_tls.tls_port(int(os.environ.get("FLUID_PORT", 8080)))
        if not port:
            return "HTTPS for phones is turned off (FLUID_TLS_PORT=0)."
        made = companion_tls.ensure(DB.parent)
        if not made:
            print("FluidMessages Web: HTTPS for phones is off because the openssl command isn't installed. Phones can still "
                  "pair at an http:// address, or through your own https:// address.", flush=True)
            return "HTTPS for phones needs the openssl command, which isn't installed on this computer."
        if TLS_SERVER:
            TLS_SERVER.shutdown()
            TLS_SERVER.server_close()
            TLS_SERVER, TLS = None, None
            announce()
        bind = tls_bind()
        try:
            server = companion_tls.TlsServer((bind, port), TlsHandler, *made)
        except OSError:
            print("FluidMessages Web: HTTPS for phones is off because port %d is in use. Set FLUID_TLS_PORT to another "
                  "port, or to 0 to turn it off." % port, flush=True)
            return "Port %d is already in use by another program." % port
        TLS_SERVER = server
        TLS = {"port": port, "fingerprint": companion_tls.fingerprint(made[0]), "bind": bind}
        threading.Thread(target=server.serve_forever, name="fluid-tls", daemon=True).start()
        announce()
        print("FluidMessages Web: phones pair over https on port %d%s. Certificate fingerprint (SHA-256): %s"
              % (port, " on your local network" if bind == "0.0.0.0" else "", TLS["fingerprint"]), flush=True)
        return None


def set_phone_access(on):
    """Turns the desktop app's "Let my phone connect" on or off, and moves the HTTPS port to match."""
    if not DESKTOP:
        raise ValueError("Only the desktop app has this switch. Set FLUID_BIND to choose where the server listens.")
    flag = DB.parent / "phone-access"
    if on:
        flag.write_text("on\n")
    else:
        flag.unlink(missing_ok=True)
    problem = start_tls()
    if problem and on:
        flag.unlink(missing_ok=True)
        start_tls()
        raise ValueError("Your phone can't connect yet. " + problem)


if __name__ == "__main__":
    if TOKEN and len(TOKEN) < 32:
        raise SystemExit("FLUID_TOKEN is optional. If you set it, use a random string of at least 32 characters.")
    DB.parent.mkdir(parents=True, exist_ok=True)
    with closing(connect()) as db, db:
        db.executescript("""
            PRAGMA journal_mode=WAL;
            CREATE TABLE IF NOT EXISTS inbox (id INTEGER PRIMARY KEY, content TEXT, updated INTEGER);
            CREATE TABLE IF NOT EXISTS phone_appearance (id INTEGER PRIMARY KEY, content TEXT NOT NULL);
            CREATE TABLE IF NOT EXISTS jobs (
                id INTEGER PRIMARY KEY, address TEXT NOT NULL, body TEXT NOT NULL,
                due INTEGER NOT NULL, state TEXT NOT NULL, protocol TEXT NOT NULL DEFAULT 'sms'
            );
        """)
        if not any(column["name"] == "protocol" for column in db.execute("PRAGMA table_info(jobs)")):
            db.execute("ALTER TABLE jobs ADD COLUMN protocol TEXT NOT NULL DEFAULT 'sms'")
        db.executescript(protocol_gateway.SCHEMA)
        db.executescript(auth.SCHEMA)
        auth.Auth.migrate(db)
    authority = auth.Auth(connect, DB.parent, TOKEN)
    if sys.argv[1:] == ["reset-password"]:
        with closing(connect()) as db, db:
            code = authority.reset_owner(db)
        print("FluidMessages Web: the password and every browser sign-in were removed.")
        print("Open FluidMessages Web and enter this one-time setup code to choose a new password: " + authority.pretty(code))
        raise SystemExit(0)
    if sys.argv[1:] == ["reset-two-step"]:
        # Locked out of the authenticator app and without recovery codes: the password alone signs in again.
        with closing(connect()) as db, db:
            authority.reset_two_step(db)
        print("FluidMessages Web: two-step sign-in is off. Sign in with your password, then turn it on again in Settings.")
        raise SystemExit(0)
    with closing(connect()) as db, db:
        code = authority.setup_code(db)
    if code:
        # Shown until a password is set, then it stops working. It's also in the data folder's setup-code file.
        # On this computer the app opens without it; the code is only for setting it up from another device.
        print("FluidMessages Web: open http://localhost:%s on this computer to start. To set it up from another device "
              "instead, use this one-time setup code: %s" % (os.environ.get("FLUID_PORT", "8080"), authority.pretty(code)),
              flush=True)
    files = Path(os.environ.get("FLUID_FILES") or DB.parent / "protocol-files")
    legacy = protocol_gateway.configured()
    account_store = accounts.AccountStore(DB.parent / "protocol-accounts.json", legacy=legacy)
    gateway = protocol_gateway.Gateway(connect, legacy, files, account_store)
    merger = sync_merge.Merge(gateway, merge_hooks())
    with closing(connect()) as db:
        load_tor(db)
    # Listen first, so the page answers at once; chat connections and the phone's HTTPS port come up right after.
    server = ThreadingHTTPServer((os.environ.get("FLUID_BIND", "127.0.0.1"), int(os.environ.get("FLUID_PORT", 8080))), Handler)
    gateway.start()
    threading.Thread(target=start_tls, name="fluid-tls-start", daemon=True).start()
    server.serve_forever()
