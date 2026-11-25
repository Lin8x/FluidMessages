"""A small IRC client for the companion: TLS only, SASL PLAIN, and IRCv3 features when the server offers them.

It keeps one connection open (like a bouncer), so the phone never holds an IRC socket. Channel members (with their
@ and + prefixes) and topics are tracked for the phone's IRC channel screen. Replies and reactions use the IRCv3
client tags +draft/reply and +draft/react when the server supports message-tags, with readable text otherwise.

For daily use it also files service bots (NickServ, ChanServ…) and anyone who messages you as direct chats, puts
server notices and errors in the network's server chat, shows joins, parts, quits, nick changes, kicks, and mode
changes as event lines, marks mentions of your nick, identifies to NickServ when SASL isn't used, falls back to
nick_ when your nick is taken, answers CTCP VERSION and PING only when you allow it, and paces what you send so the
network doesn't disconnect you for flooding.
"""
import base64
from datetime import datetime
import random
import re
import socket
import ssl
import threading
import time

WANTED_CAPS = ("sasl", "message-tags", "server-time", "echo-message", "multi-prefix", "away-notify")
LINE = re.compile(r"^(?:@(?P<tags>\S+) )?(?::(?P<prefix>\S+) )?(?P<command>\S+)(?P<params>.*)$")
MAX_PAYLOAD = 400  # bytes of text per PRIVMSG; servers cut lines at 512 including the prefix
# What the server adds in front of a relayed line besides your nick: "!user@host " and the leading ":".
PREFIX_ROOM = 76
# The server chat: notices from the server itself, errors, and /whois replies with no other chat to go to.
SERVER = "*"
SERVICES = {"nickserv", "chanserv", "memoserv", "operserv", "hostserv", "botserv", "saslserv", "helpserv", "infoserv",
            "statserv", "global", "alis", "q", "x", "authserv", "groupserv"}
# User-mode prefixes in a channel, highest first, and the modes that set them.
PREFIXES = "~&@%+"
PREFIX_MODES = {"q": "~", "a": "&", "o": "@", "h": "%", "v": "+"}
NICK_EDGE = r"[\w\[\]\\`^{|}-]"
# Numerics shown in the chat they're about (or the server chat): errors and replies to commands you typed.
ERRORS = {"401", "402", "403", "404", "405", "406", "411", "412", "421", "432", "433", "436", "437", "441", "442", "443", "461",
          "471", "472", "473", "474", "475", "476", "477", "478", "481", "482", "484", "485", "489", "491", "501", "502"}
WHOIS = {"301", "311", "312", "313", "317", "318", "319", "330", "338", "378", "379", "671", "276", "307", "320"}
CTCP_KNOWN = ("VERSION", "PING", "CLIENTINFO")


def is_service(nick):
    """Network services and common bots, labeled as automated chats."""
    lowered = (nick or "").lower()
    return lowered in SERVICES or lowered.endswith("serv") or lowered.endswith("bot")


def parse(line):
    match = LINE.match(line)
    if not match:
        return None
    tags = {}
    for item in (match["tags"] or "").split(";"):
        if item:
            key, _, value = item.partition("=")
            tags[key] = value.replace("\\:", ";").replace("\\s", " ").replace("\\\\", "\\").replace("\\r", "\r").replace("\\n", "\n")
    params, rest = [], match["params"].lstrip(" ")
    while rest:
        if rest.startswith(":"):
            params.append(rest[1:])
            break
        part, _, rest = rest.partition(" ")
        params.append(part)
        rest = rest.lstrip(" ")
    source = match["prefix"] or ""
    nick = source.split("!", 1)[0]
    return {"tags": tags, "nick": nick, "source": source, "command": match["command"].upper(), "params": params}


def escape_tag(value):
    return value.replace("\\", "\\\\").replace(";", "\\:").replace(" ", "\\s").replace("\r", "\\r").replace("\n", "\\n")


def split_text(text, limit=MAX_PAYLOAD):
    """Splits on UTF-8 byte limits without breaking characters, one IRC line per paragraph line."""
    limit = max(32, limit)
    for line in text.replace("\r", "").split("\n"):
        data = line.encode()
        while len(data) > limit:
            cut = limit
            while cut and (data[cut] & 0xC0) == 0x80:
                cut -= 1
            space = data.rfind(b" ", 0, cut)
            cut = space if space > limit // 2 else cut
            yield data[:cut].decode()
            data = data[cut:].lstrip(b" ")
        if data:
            yield data.decode()


def fallback_nick(nick, tries):
    """nick_, nick__, then nick1…nick9 kept within 30 characters, when the nickname is taken."""
    base = nick.rstrip("_0123456789") or nick
    if tries <= 2:
        return (base[:30 - tries] + "_" * tries)
    return base[:28] + str(tries - 2)


# Reconnecting: 15 s, 30 s, 1 min, 2 min, 5 min, then every 10 min, each ±20% so many clients don't retry together,
# and at most RECONNECTS_PER_HOUR automatic reconnects an hour, so a flaky link never looks like a reconnect flood to
# the network. The phone uses the same schedule (IrcRules.kt).
BACKOFF = (15, 30, 60, 120, 300, 600)
RECONNECTS_PER_HOUR = 5


def backoff(attempt, rand=random.random):
    """Seconds to wait before automatic reconnect number [attempt] (0 is the first after a drop)."""
    base = BACKOFF[min(max(attempt, 0), len(BACKOFF) - 1)]
    return base * (0.8 + 0.4 * rand())


class ReconnectBudget:
    """At most [limit] automatic reconnects in any hour. Reconnecting by hand doesn't count."""

    def __init__(self, limit=RECONNECTS_PER_HOUR, window=3600):
        self.limit, self.window, self.times = limit, window, []

    def allowed_at(self, now):
        """The earliest time (seconds) an automatic reconnect may start: [now], or when the oldest one in the hour ages out."""
        self.times = [t for t in self.times if now - t < self.window]
        return now if len(self.times) < self.limit else self.times[0] + self.window

    def record(self, when):
        self.times.append(when)


def identify_line(account, password):
    """NickServ IDENTIFY with the account name first, which Atheme (Libera.Chat) and Anope both accept."""
    return "PRIVMSG NickServ :IDENTIFY " + (account.get("sasl_user") or account["nick"]) + " " + password


BLOCKED = re.compile(r"k-?lined|g-?lined|z-?lined|d-?lined|banned|not welcome|you are not allowed", re.IGNORECASE)


def needs_sasl(text):
    """Whether a server notice or closing reason says this connection must sign in with SASL first."""
    return bool(re.search(r"\bsasl\b", text or "", re.IGNORECASE)
                and re.search(r"requir|need|must|only|identify|authenticat", text or "", re.IGNORECASE))


def explain(command, text, network, nick, sasl):
    """Why [network] refused or closed the connection, in plain words with what to do, as (text, fatal), or None.
    [fatal]: trying again won't help until you change something or tap Reconnect. The phone says the same
    (IrcRules.explain)."""
    said = (text or "").strip()[:200]
    quote = f" It said: “{said}”" if said else ""
    if command == "ERROR":
        if needs_sasl(said):
            return (f"{network} only lets people signed in to a registered nickname connect from your internet connection. "
                    "This often happens on a VPN, a work or school network, or a cloud server. "
                    + ("Check the account name and password in the IRC account settings." if sasl else
                       "Register your nickname from another connection, then type its password in Password in the IRC account settings.")
                    + quote, True)
        if BLOCKED.search(said):
            return f"{network} blocked this connection.{quote} Try another network, or another internet connection.", True
        if re.search(r"ping timeout|connection reset|broken pipe", said, re.IGNORECASE):
            return f"The connection to {network} dropped.", False
        if "client quit" in said.lower():
            return None
        return f"{network} closed the connection.{quote}", False
    if command in ("465", "463", "464"):
        return (f"{network} won’t let you connect.{quote} " + ("Check the password in the account settings." if command == "464"
                                                                else "Try another network, or another internet connection."), True)
    if command == "432":
        return (f"{network} doesn’t allow the nickname “{nick}”. Pick another one in the account settings: start it with a letter, "
                "and use only letters, numbers, - or _.", True)
    if command in ("433", "436", "437"):
        return f"Every nickname we tried on {network} is taken. Pick another nickname in the IRC account settings.", True
    if command in ("904", "905") and sasl:
        return (f"Signing in to your nickname on {network} didn’t work. Check the account name (the name you registered) "
                f"and password in the IRC account settings.{quote}", False)
    return None


# Join requirements, the same rules as the phone's (IrcJoins.kt): which "you can't join" replies mean you must be
# signed in to your nickname, and which name another channel to join first. scripts/test-local checks both.
JOIN_CODES = {"403", "405", "471", "473", "474", "475", "476", "477", "479", "489", "520"}
MOST_FIRST = 3
SIGN_IN_WAIT = 12.0
_NAME = r"([#&][^\s,:\x07()\"'`<>\[\]]+)"
_FIRST = (re.compile(r"\b(?:must|need to|needs to|have to|has to|required to|please)\s+(?:first\s+)?(?:join|be in|be on|be a member of)\s+"
                     r"(?:channel\s+)?" + _NAME, re.IGNORECASE),
          re.compile(r"\bjoin\s+(?:channel\s+)?" + _NAME + r"\s+(?:first|before)\b", re.IGNORECASE))
_SIGN_IN = re.compile(r"identif|regist|logged in|log in|signed in|sign in|authenticat|services account|\(\+r\)|\+r\b", re.IGNORECASE)
_MUST = re.compile(r"\bneed|\bmust\b|\bonly\b|\brequire|\bhave to\b|\(\+r\)", re.IGNORECASE)
_CHANNEL = re.compile(r"[#&][^\s,\x07]{1,49}")
IRC_FOLD = str.maketrans("[]\\~", "{}|^")


def fold(name):
    """A channel or nick as IRC compares them: case folded by RFC 1459 ([]\\~ are the capitals of {}|^)."""
    return (name or "").lower().translate(IRC_FOLD)


def needs_sign_in(code, text):
    """Whether a join reply means the channel only lets in people signed in to their nickname."""
    text = text or ""
    if code == "477":
        return not re.search(r"support|modes", text, re.IGNORECASE) or bool(_SIGN_IN.search(text))
    return code in JOIN_CODES and code != "475" and bool(_SIGN_IN.search(text)) and bool(_MUST.search(text))


def prerequisite(code, text, channel):
    """The channel a join reply says to join first ("You must join #help first"), or None. Never [channel] itself."""
    if code not in JOIN_CODES:
        return None
    for pattern in _FIRST:
        for match in pattern.finditer(text or ""):
            name = match.group(1).rstrip(".!?;")
            if _CHANNEL.fullmatch(name) and fold(name) != fold(channel):
                return name
    return None


def join_order(channels, first):
    """[channels] in the order to join them: the channels each needs first ([first]) come before it, without repeats
    or loops."""
    done, visiting = {}, set()

    def visit(channel, depth):
        key = fold(channel)
        if key in done or key in visiting or depth > MOST_FIRST:
            return
        visiting.add(key)
        for before in first(channel):
            visit(before, depth + 1)
        visiting.discard(key)
        done[key] = channel

    for channel in channels:
        visit(channel, 0)
    return list(done.values())


def first_for(saved, channel):
    """The channels to join before [channel], from a saved {folded channel: [channels]} map."""
    found = (saved or {}).get(fold(channel)) or []
    return [name for name in found if isinstance(name, str) and _CHANNEL.fullmatch(name)][:MOST_FIRST]


def remember_first(saved, channel, first):
    saved = dict(saved or {})
    now = first_for(saved, channel)
    if fold(first) != fold(channel) and all(fold(name) != fold(first) for name in now):
        saved[fold(channel)] = (now + [first])[-MOST_FIRST:]
    while len(saved) > 200:
        saved.pop(next(iter(saved)))
    return saved


def forget_first(saved, channel, first):
    saved = dict(saved or {})
    left = [name for name in first_for(saved, channel) if fold(name) != fold(first)]
    if left:
        saved[fold(channel)] = left
    else:
        saved.pop(fold(channel), None)
    return saved


def joining_first(first, channel, network):
    return (f"{network} only lets people into {channel} after they join {first}, so FluidMessages joined {first} first. It does "
            "this every time you connect. You can change it in this channel’s details.")


def waiting_for_sign_in(channel):
    return f"{channel} only lets in people who are signed in to their nickname. FluidMessages is signing you in, then it tries again."


def sign_in_needed(channel, saved):
    if saved:
        return (f"{channel} only lets in people who are signed in to their nickname, and signing in didn’t work. Check the account "
                "name and password in the IRC account settings, then try again.")
    return (f"{channel} only lets in people who are signed in to their nickname. Sign in with NickServ, then try again. "
            "Type your password in the NickServ card, or save it as Password in the IRC account settings.")


def join_error(code, channel, network, said, saved=False):
    """Why a channel didn't let you in, in plain words with what to do (IrcChannels.joinError on the phone)."""
    said = (said or "").strip()
    if needs_sign_in(code, said):
        return sign_in_needed(channel, saved)
    return {
        "403": f"{network} has no channel called {channel}. Check the spelling, or browse the channel list.",
        "476": f"{network} has no channel called {channel}. Check the spelling, or browse the channel list.",
        "479": f"{network} has no channel called {channel}. Check the spelling, or browse the channel list.",
        "405": f"You’re in as many channels as {network} allows. Leave one, then try again.",
        "471": f"{channel} is full right now. Try again later.",
        "473": f"{channel} is invite-only: only people someone invited can join. Ask someone in it to invite you. "
               "The invitation shows up in Invitations.",
        "474": f"You can’t join {channel} because its moderators banned you.",
        "475": f"{channel} is locked with a password. Ask someone in it for the password, then join with it.",
        "520": f"{channel} is only for the people who run {network}.",
    }.get(code) or (f"Couldn’t join {channel}." if not said else f"Couldn’t join {channel}. {network} said: “{said[:200]}”")


def error_text(code, about, said):
    """An error reply to something you did, in plain words with what to do (IrcRules.errorText on the phone)."""
    it = about or "that"
    return {
        "401": f"{it} isn’t on the network right now. Check the spelling, or try again later.",
        "406": f"{it} isn’t on the network right now. Check the spelling, or try again later.",
        "402": "That server isn’t part of this network.",
        "403": f"There’s no channel called {it}. Check the spelling, or browse the channel list.",
        "404": f"You can’t write in {it} right now. You may need to join it first, or its moderators only let some people talk.",
        "405": "You’re in as many channels as this network allows. Leave one, then try again.",
        "411": "There was nothing to send. Type a message first.",
        "412": "There was nothing to send. Type a message first.",
        "421": f"The network doesn’t know the command “{it}”. Type /help to see the commands you can use.",
        "432": f"The network doesn’t allow the nickname “{it}”. Start it with a letter, and use only letters, numbers, - or _.",
        "433": f"The nickname {it} is already taken. Pick another one.",
        "436": f"The nickname {it} is already taken. Pick another one.",
        "437": f"{it} can’t be used right now. Try again in a few minutes, or pick another one.",
        "441": f"{it} isn’t in that channel.",
        "442": f"You’re not in {it}. Join it first.",
        "443": f"{it} is already in that channel.",
        "461": "That command is missing something. Type /help to see how to use it.",
        "472": "The network doesn’t know that setting.",
        "501": "The network doesn’t know that setting.",
        "478": f"That list in {it} is full. A moderator has to remove an entry first.",
        "481": "Only the people who run the network can do that.",
        "482": f"Only moderators of {it} can do that. Moderators show with @ before their name.",
        "484": "The network limits what this connection can do, so it can’t do that.",
        "485": "Only the channel’s owner can do that.",
        "489": f"{it} only lets in people on a secure connection.",
        "491": "This network doesn’t let you become one of its staff from here.",
        "502": "You can only change your own settings.",
    }.get(code) or ((said or "").strip() or "The network said no, without a reason.")


NICKSERV_IDENTIFIED = re.compile(r"\b(you are now (identified|recognized|logged in)|password accepted|(you are|you're) (successfully|already) "
                                 r"(identified|logged in)|successfully identified)\b", re.IGNORECASE)
NICKSERV_FAILED = re.compile(r"\b(invalid password|password incorrect|incorrect password|wrong password|identify failed|authentication failed)\b",
                             re.IGNORECASE)


class Refused(ConnectionError):
    """The network said no in a way trying again can't fix (IrcClient.run waits for Reconnect)."""


def keep_joined_channels(channels, keep):
    """The channels rejoined as soon as a connection opens: those set to Keep joined (the default). The others rejoin
    when you open them (IrcClient.ensure_joined)."""
    return [channel for channel in channels if keep(channel)]


class Flood:
    """Paces outgoing lines like most IRC clients: a burst of [burst], then one line every [every] seconds, so a long
    paste or a run of commands doesn't get you disconnected for flooding."""

    def __init__(self, burst=5, every=2.0, clock=time.monotonic, sleep=time.sleep):
        self.burst, self.every, self.clock, self.sleep = burst, every, clock, sleep
        self.tokens, self.at = float(burst), clock()
        self.lock = threading.Lock()

    def wait(self):
        with self.lock:
            now = self.clock()
            self.tokens = min(float(self.burst), self.tokens + (now - self.at) / self.every)
            self.at = now
            if self.tokens < 1:
                self.sleep((1 - self.tokens) * self.every)
                self.at = self.clock()
                self.tokens = 0.0
            else:
                self.tokens -= 1


class IrcClient:
    def __init__(self, account, on_message, on_state, connect=None, on_invite=None, flood=None, keep=None,
                 clock=time.time, rand=random.random, auto=None, first=None, on_first=None):
        self.account = account
        self.on_message = on_message  # (target, nick, text, tags, date_ms, outgoing)
        self.on_state = on_state      # (channel, {"topic":..., "members": [...], "modes": {nick: "@"}})
        self.on_invite = on_invite    # (channel, from_nick)
        self.connect_socket = connect or self.open_tls
        self.caps = set()
        self.offered = set()
        self.wanted = account["nick"]  # The nick you asked for; /nick changes it for this connection.
        self.nick = self.wanted
        self.nick_tries = 0
        self.registered = False
        self.sasl_ok = False
        self.channels = {}
        self.whois_for = {}           # nick (lowercase) → the conversation that asked
        self.sock = None
        self.lock = threading.Lock()
        self.flood = flood or Flood()
        self.ctcp_at = 0.0
        self.stopped = threading.Event()
        self.ready = threading.Event()
        self.wake = threading.Event()   # Reconnect now (the Reconnect button)
        self.dropped = False            # Disconnect: closed by you, so it waits for Connect or Reconnect
        self.keep = keep or (lambda channel: True)
        # Reconnect automatically (on by default): off, it connects only when you tap Reconnect.
        self.auto = auto or (lambda: True)
        self.problem = None             # (text, fatal) from explain(): why the network said no
        self.sasl_notice = ""
        self.joined = set()             # channels (lowercase) this connection is in
        self.clock, self.rand = clock, rand
        self.budget = ReconnectBudget()
        # What the apps show: connected, connecting, reconnecting (with retry_at), or paused by the hourly limit.
        self.status = {"state": "connecting", "network": account.get("server", ""), "retry_at": 0, "error": ""}
        # A channel LIST in progress: {"rows": [...], "limit": n, "done": Event}.
        self.listing = None
        self.list_lock = threading.Lock()
        # Join requirements (join_need, prerequisite): with a password saved, joins wait until you're signed in to your
        # nickname (SIGN_IN_WAIT at most), a channel that needs it tries once more after, and a channel the network
        # says to join after another one gets that one joined first. [first] reads and [on_first] saves that order.
        self.first = first or (lambda channel: [])
        self.on_first = on_first or (lambda channel, before: None)
        self.signed_in = self.sign_in_pending = False
        self.joins_sent = False
        self.after_sign_in, self.retried, self.first_tried = set(), set(), set()
        self.join_errors = {}           # folded channel → (code, text), for join_wait
        self.sessions = 0
        self.sign_in_lock = threading.Lock()

    def open_tls(self):
        raw = socket.create_connection((self.account["server"], int(self.account.get("port", 6697))), timeout=30)
        return ssl.create_default_context().wrap_socket(raw, server_hostname=self.account["server"])

    def write(self, line):
        if "\r" in line or "\n" in line or "\0" in line:
            raise ValueError("Invalid IRC line")
        with self.lock:
            if self.sock is None:
                raise ConnectionError("IRC is reconnecting. Try again in a moment.")
            self.sock.sendall(line.encode()[:510] + b"\r\n")

    def say(self, line):
        """A line you sent (a message or a command), paced to stay under the network's flood limits."""
        self.flood.wait()
        self.write(line)

    def stop(self):
        self.stopped.set()
        self.wake.set()
        try:
            self.write("QUIT :FluidMessages")
        except (OSError, ConnectionError, ValueError):
            pass

    def wait_for_reconnect(self, error="", manual=False):
        """Waits until Reconnect (or stop): after a refusal trying again can't fix, or while Reconnect automatically
        is off. Returns False once stopped."""
        self.status = {**self.status, "state": "disconnected", "retry_at": 0, "error": error, "manual": manual}
        self.wake.wait()
        self.wake.clear()
        self.budget = ReconnectBudget()
        return not self.stopped.is_set()

    def network(self):
        return self.account.get("server", "") or "the network"

    def explain(self, command, text):
        return explain(command, text, self.network(), self.wanted, bool(self.account.get("sasl_password")))

    def run(self):
        attempt = 0
        if not self.auto() and not self.wait_for_reconnect(manual=True):
            return
        while not self.stopped.is_set():
            if self.dropped:
                # Disconnect: nothing connects again until Connect or Reconnect.
                self.dropped = False
                self.wake.clear()
                if not self.wait_for_reconnect(manual=True):
                    break
                attempt = 0
                if self.dropped:
                    continue
            error = ""
            self.problem, self.sasl_notice = None, ""
            self.status = {**self.status, "state": "connecting", "retry_at": 0, "error": "", "manual": False}
            try:
                self.session()
            except (OSError, ValueError, ConnectionError) as problem:
                error = str(problem)[:300]
            finally:
                self.ready.clear()
                with self.lock:
                    if self.sock is not None:
                        try:
                            self.sock.close()
                        except OSError:
                            pass
                    self.sock = None
            if self.stopped.is_set():
                break
            if self.dropped:
                continue
            # Closed before signing in, right after a notice that SASL is needed: that's the reason.
            if self.problem is None and not self.registered and self.sasl_notice:
                self.problem = self.explain("ERROR", self.sasl_notice)
            if self.problem:
                error = self.problem[0]
            if self.problem and self.problem[1]:
                # A nickname the network refuses, SASL needed, a ban: wait for Reconnect instead of retrying.
                if not self.wait_for_reconnect(error):
                    break
                attempt = 0
                continue
            if not self.auto():
                if not self.wait_for_reconnect(error, manual=True):
                    break
                attempt = 0
                continue
            if self.registered:
                attempt = 0  # It was working: start the schedule again.
            delay = self.next_delay(attempt)
            attempt += 1
            if self.wait_to_reconnect(delay, error):
                continue  # Reconnect by hand: not counted.
            self.budget.record(self.clock())

    def next_delay(self, attempt):
        """The backoff with jitter, stretched to respect the hourly reconnect limit."""
        now = self.clock()
        start = max(now + backoff(attempt, self.rand), self.budget.allowed_at(now))
        return start - now

    def wait_to_reconnect(self, delay, error=""):
        """Waits [delay] seconds, or until Reconnect. Returns True when it was Reconnect."""
        paused = delay > BACKOFF[-1] * 1.2
        self.status = {**self.status, "state": "paused" if paused else "reconnecting",
                       "retry_at": int((self.clock() + delay) * 1000), "error": error}
        woken = self.wake.wait(delay)
        self.wake.clear()
        return woken and not self.stopped.is_set()

    def reconnect_now(self):
        """The Reconnect button: tries again at once, outside the hourly limit."""
        self.dropped = False
        self.wake.set()

    def disconnect_now(self):
        """Disconnect: says goodbye to the network and waits for Connect or Reconnect. Its chats stay in the list."""
        self.dropped = True
        self.status = {**self.status, "state": "disconnected", "retry_at": 0, "error": "", "manual": True}
        self.wake.set()  # Leaves a wait to try again.
        try:
            self.write("QUIT :FluidMessages")
        except (OSError, ConnectionError, ValueError):
            pass
        with self.lock:
            if self.sock is not None and hasattr(self.sock, "shutdown"):
                try:
                    self.sock.shutdown(socket.SHUT_RDWR)
                except OSError:
                    pass

    def ensure_joined(self, channel, wait=0.0):
        """Joins a channel that isn't kept joined when you open or write to it."""
        if not self.ready.is_set() or channel.lower() in self.joined or channel[:1] not in "#&":
            return
        self.join_in_order(channel)
        deadline = time.monotonic() + wait
        while wait and channel.lower() not in self.joined and time.monotonic() < deadline:
            time.sleep(0.05)

    def session(self):
        sock = self.connect_socket()
        sock.settimeout(300)
        with self.lock:
            self.sock = sock
        self.caps.clear()
        self.offered = set()
        # Each connection asks for your own nick again, even if the last one fell back to nick_.
        self.nick, self.nick_tries, self.registered, self.sasl_ok = self.wanted, 0, False, False
        self.joined = set()
        self.signed_in = self.sign_in_pending = self.joins_sent = False
        self.after_sign_in, self.retried, self.first_tried = set(), set(), set()
        self.join_errors = {}
        self.sessions += 1
        self.write("CAP LS 302")
        self.write("NICK " + self.nick)
        self.write("USER " + self.account["nick"] + " 0 * :" + self.account.get("real_name", "FluidMessages"))
        buffer = b""
        while not self.stopped.is_set():
            chunk = sock.recv(16384)
            if not chunk:
                raise ConnectionError("IRC connection closed")
            buffer += chunk
            if len(buffer) > 1 << 20:
                raise ValueError("IRC line too long")
            while b"\r\n" in buffer or b"\n" in buffer:
                line, _, buffer = buffer.partition(b"\n")
                self.handle(parse(line.rstrip(b"\r").decode(errors="replace")))

    def handle(self, message):
        if message is None:
            return
        command, params = message["command"], message["params"]
        if command == "PING":
            self.write("PONG :" + (params[0] if params else ""))
        elif command == "CAP" and len(params) >= 3:
            self.capability(params)
        elif command == "AUTHENTICATE" and params == ["+"]:
            user = self.account.get("sasl_user") or self.account["nick"]
            token = base64.b64encode(f"{user}\0{user}\0{self.account['sasl_password']}".encode()).decode()
            self.write("AUTHENTICATE " + token)
        elif command in ("903", "904", "905", "906", "907"):
            self.sasl_ok = self.sasl_ok or command == "903"
            self.signed_in = self.signed_in or command == "903"
            # A wrong SASL account or password: say so in the server chat, then carry on without signing in.
            found = self.explain(command, params[-1] if params else "")
            if found:
                self.event(SERVER, found[0], message, "error")
            self.write("CAP END")
        elif command in ("465", "463", "464"):
            found = self.explain(command, params[-1] if params else "")
            self.problem = found
            self.event(SERVER, found[0], message, "error")
        elif command == "001":
            self.nick = params[0] if params else self.nick
            self.registered = True
            if self.identify():
                # Signing in to NickServ first: channels for signed-in nicknames only would refuse you before it.
                session = self.sessions
                timer = threading.Timer(SIGN_IN_WAIT, lambda: self.sign_in_over() if session == self.sessions else None)
                timer.daemon = True
                timer.start()
            else:
                self.join_kept()
            self.status = {**self.status, "state": "connected", "retry_at": 0, "error": ""}
            self.ready.set()
        elif command == "322" and len(params) >= 3 and self.listing is not None:
            # RPL_LIST: me, channel, visible users, topic.
            listing = self.listing
            if not listing["search"] or listing["search"] in (params[1] + " " + (params[3] if len(params) > 3 else "")).lower():
                users = int(params[2]) if params[2].isdigit() else 0
                rows = listing["rows"]
                rows.append({"target": params[1], "name": params[1], "members": users,
                             "topic": (params[3] if len(params) > 3 else "")[:300]})
                # The busiest are kept, whatever order the network sends them in; the rest are dropped as they come.
                if len(rows) >= listing["limit"] * 4:
                    rows.sort(key=lambda row: -row["members"])
                    del rows[listing["limit"]:]
        elif command in ("323", "416") and self.listing is not None:
            self.listing["done"].set()
        elif command == "432" and not self.registered:
            # A nickname the network doesn't allow: nick_ wouldn't be allowed either, so say why and stop.
            self.problem = self.explain("432", params[-1] if params else "")
            raise Refused(self.problem[0])
        elif command in ("433", "436", "437") and not self.registered:
            # Taken while connecting: try nick_, nick__, then nick1…
            self.nick_tries += 1
            if self.nick_tries > 8:
                self.problem = self.explain("433", "")
                raise Refused(self.problem[0])
            self.nick = fallback_nick(self.wanted, self.nick_tries)
            self.write("NICK " + self.nick)
        elif command == "900":
            self.now_signed_in()  # Signed in to your account (RPL_LOGGEDIN), after SASL or NickServ IDENTIFY.
        elif command in ("PRIVMSG", "NOTICE") and len(params) == 2:
            if command == "NOTICE" and not self.registered and needs_sasl(params[1]):
                self.sasl_notice = params[1]
            if message["nick"].lower() == "nickserv":
                self.nickserv_said(params[1])
            self.message(message)
        elif command == "TAGMSG" and params:
            self.message(message)
        elif command == "332" and len(params) >= 3:
            self.channel(params[1])["topic"] = params[2]
            self.publish(params[1])
        elif command == "TOPIC" and len(params) == 2:
            self.channel(params[0])["topic"] = params[1]
            self.publish(params[0])
            self.event(params[0], f"{message['nick']} changed the topic to “{params[1]}”", message, "topic")
        elif command == "353" and len(params) >= 4:
            state = self.channel(params[2])
            members = state.setdefault("pending", set())
            modes = state.setdefault("pending_modes", {})
            for name in params[3].split():
                bare = name.lstrip(PREFIXES)
                members.add(bare)
                if bare != name:
                    modes[bare] = name[0]  # The highest prefix comes first (multi-prefix).
        elif command == "366" and len(params) >= 2:
            state = self.channel(params[1])
            state["members"] = set(state.pop("pending", set()))
            state["modes"] = dict(state.pop("pending_modes", {}))
            self.publish(params[1])
        elif command == "JOIN" and params:
            if message["nick"] == self.nick:
                self.joined.add(params[0].lower())
            state = self.channel(params[0])
            state.setdefault("members", set()).add(message["nick"])
            self.publish(params[0])
            if message["nick"] != self.nick:
                self.event(params[0], f"{message['nick']} joined", message, "join")
        elif command == "PART" and params:
            self.forget(params[0], message["nick"])
            reason = f" ({params[1]})" if len(params) > 1 and params[1] else ""
            self.event(params[0], f"{message['nick']} left{reason}", message, "part")
        elif command == "KICK" and len(params) >= 2:
            channel, who = params[0], params[1]
            reason = f" ({params[2]})" if len(params) > 2 and params[2] and params[2] != message["nick"] else ""
            self.forget(channel, who)
            if who == self.nick:
                self.event(channel, f"{message['nick']} removed you from {channel}{reason}", message, "kick")
                self.channels.pop(channel.lower(), None)
            else:
                self.event(channel, f"{message['nick']} removed {who}{reason}", message, "kick")
        elif command == "QUIT":
            reason = f" ({params[0]})" if params and params[0] else ""
            for name, state in list(self.channels.items()):
                if message["nick"] in state.get("members", set()):
                    self.forget(name, message["nick"])
                    self.event(state["name"], f"{message['nick']} quit{reason}", message, "quit")
        elif command == "NICK" and params:
            for name, state in self.channels.items():
                if message["nick"] in state.get("members", set()):
                    state["members"].discard(message["nick"])
                    state["members"].add(params[0])
                    modes = state.setdefault("modes", {})
                    if message["nick"] in modes:
                        modes[params[0]] = modes.pop(message["nick"])
                    self.publish(name)
                    self.event(state["name"], f"{message['nick']} is now {params[0]}", message, "nick")
            if message["nick"] == self.nick:
                self.nick = params[0]
                self.wanted = params[0]
        elif command == "MODE" and len(params) >= 2 and params[0][:1] in "#&":
            self.mode(message)
        elif command == "INVITE" and len(params) >= 2:
            if self.on_invite is not None:
                self.on_invite(params[1], message["nick"])
            else:
                self.event(SERVER, f"{message['nick']} invited you to {params[1]}", message, "invite")
        elif command == "341" and len(params) >= 3:
            self.event(params[2], f"Invited {params[1]} to {params[2]}", message, "info")
        elif command in ("305", "306") and len(params) >= 2:
            self.event(SERVER, params[-1], message, "info")
        elif command in WHOIS and len(params) >= 3:
            self.whois_line(message)
        elif command in JOIN_CODES and len(params) >= 3 and params[1][:1] in "#&":
            channel, text = params[1], params[-1]
            if self.join_failed(command, channel, text, message):
                return  # Handled on its own: a channel to join first, or signing in first. It's tried again.
            self.join_errors[fold(channel)] = (command, text)
            # Why, in plain words, in that channel.
            self.event(channel, join_error(command, channel, self.network(), text, self.has_password()), message, "error")
        elif command in ERRORS and len(params) >= 2:
            about = params[1] if len(params) >= 3 else ""
            if command == "433" and self.registered:
                self.event(SERVER, f"The nickname {about} is already taken", message, "error")
                return
            where = about if about[:1] in "#&" or about.lower() in self.whois_for else SERVER
            where = self.whois_for.get(about.lower(), where)
            self.event(where, error_text(command, about, (about + ": " if about and where == SERVER else "") + params[-1]), message, "error")
        elif command == "ERROR" and params:
            self.event(SERVER, "Disconnected: " + params[0], message, "error")
            found = self.explain("ERROR", params[0])
            # The SASL notice before it says more than "Closing Link".
            if found and not found[1] and not self.registered and self.sasl_notice:
                found = self.explain("ERROR", self.sasl_notice)
            if found and (self.problem is None or found[1]):
                self.problem = found

    def identify(self):
        """Identify on connect when SASL didn't sign you in: NickServ IDENTIFY with the NickServ password, or else the
        account's Password (most networks use one password for both). True when IDENTIFY went out."""
        password = self.account.get("nickserv_password") or self.account.get("sasl_password")
        if not password or self.sasl_ok:
            return False
        self.write(identify_line(self.account, password))
        self.sign_in_pending = True
        return True

    def has_password(self):
        return bool(self.account.get("nickserv_password") or self.account.get("sasl_password"))

    # Join requirements

    def join_in_order(self, channel, key="", paced=False):
        """Joins [channel] (with its password [key]) after the channels it needs first."""
        for name in join_order([channel], self.first):
            target = fold(name) == fold(channel)
            if not target and fold(name) in {fold(c) for c in self.joined}:
                continue
            line = "JOIN " + name + (" " + key if target and key else "")
            (self.say if paced else self.write)(line)

    def join_kept(self):
        """Only channels set to Keep joined join on connect, after the channels they need first; the rest join when
        you open them."""
        with self.sign_in_lock:
            if self.joins_sent:
                return
            self.joins_sent = True
        for channel in join_order(keep_joined_channels(self.account.get("channels", []), self.keep), self.first):
            self.write("JOIN " + channel)

    def now_signed_in(self):
        """Signed in to your nickname: joins wait no longer, and channels that wanted it try once more."""
        self.signed_in, self.sign_in_pending = True, False
        if self.registered:
            self.join_kept()
        for channel in list(self.after_sign_in):
            self.after_sign_in.discard(channel)
            if channel not in {fold(c) for c in self.joined} and channel not in self.retried:
                self.retried.add(channel)
                self.join_in_order(channel)

    def nickserv_said(self, text):
        """"You are now identified" signs you in; a wrong password ends the wait."""
        words = re.sub(r"\x03(?:\d{1,2}(?:,\d{1,2})?)?|[\x00-\x1f\x7f]", "", text or "")
        if NICKSERV_FAILED.search(words):
            if self.sign_in_pending:
                self.sign_in_over()
        elif NICKSERV_IDENTIFIED.search(words):
            self.now_signed_in()

    def sign_in_over(self):
        """Signing in didn't happen in time, or the password was wrong: join anyway, and say which channels still
        need it."""
        if self.signed_in:
            return
        self.sign_in_pending = False
        try:
            self.join_kept()
            for channel in list(self.after_sign_in):
                self.event(channel, sign_in_needed(channel, True), {"tags": {}}, "error")
        except (OSError, ConnectionError, ValueError):
            pass

    def join_failed(self, code, channel, text, message):
        """A channel said no. Handles what it can on its own and returns True: a channel named to join first is joined
        first (and remembered); a channel for signed-in nicknames waits for signing in, or tries once more right
        after it. False when the reason should be shown."""
        key = fold(channel)
        before = prerequisite(code, text, channel)
        if before and (key, fold(before)) not in self.first_tried:
            self.first_tried.add((key, fold(before)))
            self.on_first(channel, before)
            self.event(channel, joining_first(before, channel, self.network()), message, "info")
            for name in [before, channel]:
                self.write("JOIN " + name)
            return True
        if not needs_sign_in(code, text):
            return False
        if self.signed_in and key not in self.retried:
            self.retried.add(key)
            self.write("JOIN " + channel)
            return True
        self.after_sign_in.add(key)
        if self.sign_in_pending:
            self.event(channel, waiting_for_sign_in(channel), message, "info")
            return True
        return False

    def join_wait(self, channel, key="", wait=8.0):
        """Joins [channel] now (with its password [key]) and waits for the network's answer: None once in (or no
        answer yet), else (code, plain words) for why it said no. Things it handles on its own (a channel to join
        first, signing in first) keep it waiting."""
        self.connected()
        if key and (len(key) > 100 or re.search(r"[\s,\x00]", key)):
            raise ValueError("A channel password can’t have spaces or commas. Check it and try again.")
        if fold(channel) in {fold(c) for c in self.joined}:
            return None
        if fold(channel) not in {fold(c) for c in self.account.get("channels", [])}:
            self.account = {**self.account, "channels": sorted(self.account.get("channels", []) + [channel], key=str.lower)}
        self.join_errors.pop(fold(channel), None)
        self.join_in_order(channel, key, paced=True)
        deadline = time.monotonic() + (SIGN_IN_WAIT if self.sign_in_pending else wait)
        while time.monotonic() < deadline and self.ready.is_set():
            if fold(channel) in {fold(c) for c in self.joined}:
                return None
            refused = self.join_errors.get(fold(channel))
            if refused:
                return refused[0], join_error(refused[0], channel, self.network(), refused[1], self.has_password())
            time.sleep(0.05)
        return None

    def identify_now(self, password, save=False):
        """The NickServ card's Identify: IDENTIFY over the connection, never a chat message. With [save] the password
        is also used on the next connect. The password is never logged or kept in chat history."""
        if not isinstance(password, str) or not 0 < len(password) <= 300 or any(c in password for c in "\r\n\0 "):
            raise ValueError("Enter your NickServ password, with no spaces")
        self.connected()
        self.say(identify_line(self.account, password))
        if save:
            self.account = {**self.account, "nickserv_password": password}

    def mode(self, message):
        """Channel modes: +o/-o and the other prefixes change who shows as @ or +; each change is one event line."""
        channel, changes, args = message["params"][0], message["params"][1], list(message["params"][2:])
        state = self.channel(channel)
        modes = state.setdefault("modes", {})
        adding, changed = True, False
        for letter in changes:
            if letter in "+-":
                adding = letter == "+"
            elif letter in PREFIX_MODES and args:
                who = args.pop(0)
                prefix = PREFIX_MODES[letter]
                current = modes.get(who, "")
                if adding and (not current or PREFIXES.index(prefix) < PREFIXES.index(current)):
                    modes[who] = prefix
                elif not adding and current == prefix:
                    modes.pop(who, None)
                changed = True
            elif letter in "beIkfljL" and args and (adding or letter in "beIk"):
                args.pop(0)
        if changed:
            self.publish(channel)
        shown = " ".join(message["params"][1:])
        self.event(channel, f"{message['nick']} set mode {shown}", message, "mode")

    def whois_line(self, message):
        params, command = message["params"], message["command"]
        nick = params[1]
        conversation = self.whois_for.get(nick.lower(), SERVER if command != "301" else nick)
        if command == "318":
            self.whois_for.pop(nick.lower(), None)
            return
        rest = params[2:]
        text = {
            "311": lambda: f"{nick} is {rest[0]}@{rest[1]}" + (f" ({rest[-1]})" if rest[-1] else "") if len(rest) >= 2 else "",
            "312": lambda: f"{nick} is on {rest[0]}" + (f" ({rest[1]})" if len(rest) > 1 else ""),
            "317": lambda: f"{nick} has been idle {int(rest[0]) // 60} min" if rest[0].isdigit() else "",
            "319": lambda: f"{nick} is in {rest[-1]}",
            "330": lambda: f"{nick} is signed in as {rest[0]}",
            "301": lambda: f"{nick} is away: {rest[-1]}",
            "671": lambda: f"{nick} is using a secure connection",
            "313": lambda: f"{nick} is a network operator",
        }.get(command, lambda: f"{nick} {rest[-1]}")()
        if text:
            self.event(conversation, text, message, "whois" if command != "301" else "info")

    def capability(self, params):
        action = params[1].upper()
        if action == "LS":
            # CAP LS 302 can span several lines; "*" before the list means more lines follow.
            self.offered.update(item.split("=", 1)[0] for item in params[-1].split())
            if len(params) > 3 and params[2] == "*":
                return
            wanted = [cap for cap in WANTED_CAPS if cap in self.offered and (cap != "sasl" or self.account.get("sasl_password"))]
            if wanted:
                self.write("CAP REQ :" + " ".join(wanted))
            else:
                self.write("CAP END")
        elif action == "ACK":
            self.caps.update(params[-1].split())
            if "sasl" in self.caps and self.account.get("sasl_password"):
                self.write("AUTHENTICATE PLAIN")
            else:
                self.write("CAP END")
        elif action == "NAK":
            self.write("CAP END")

    def channel(self, name):
        return self.channels.setdefault(name.lower(), {"name": name})

    def forget(self, channel, nick):
        if nick == self.nick:
            self.joined.discard(channel.lower())
        state = self.channel(channel)
        state.setdefault("members", set()).discard(nick)
        state.setdefault("modes", {}).pop(nick, None)
        self.publish(channel)

    def publish(self, name):
        state = self.channel(name)
        members = state.get("members", set())
        self.on_state(state["name"], {"topic": state.get("topic", ""), "members": sorted(members),
                                      "modes": {nick: mode for nick, mode in sorted(state.get("modes", {}).items()) if nick in members}})

    def date(self, tags):
        stamp = tags.get("time")
        try:
            return int(datetime.fromisoformat(stamp.replace("Z", "+00:00")).timestamp() * 1000) if stamp else int(time.time() * 1000)
        except ValueError:
            return int(time.time() * 1000)

    def event(self, channel, text, message, kind="info"):
        self.on_message(channel, "", text, {**message["tags"], "event": kind}, self.date(message["tags"]), False)

    def mentions(self, text):
        """Your nick as a word: "fluidtest: hi" and "hi fluidtest!" count, "fluidtesting" doesn't."""
        names = "|".join(re.escape(name) for name in {self.nick, self.wanted, self.account["nick"]} if name)
        return bool(re.search(r"(?<!" + NICK_EDGE + ")(?:" + names + r")(?!" + NICK_EDGE + ")", text, re.IGNORECASE))

    def ctcp(self, nick, request):
        """Answers CTCP VERSION, PING, and CLIENTINFO only when the account allows it (off by default, for privacy),
        with a generic reply and at most once every few seconds. DCC and everything else is ignored."""
        if not self.account.get("ctcp_replies") or not nick or nick == self.nick:
            return
        word, _, value = request.partition(" ")
        word = word.upper()
        if word not in CTCP_KNOWN or time.monotonic() - self.ctcp_at < 5:
            return
        self.ctcp_at = time.monotonic()
        reply = {"VERSION": "FluidMessages", "PING": value[:64], "CLIENTINFO": "ACTION PING VERSION CLIENTINFO"}[word]
        if not re.fullmatch(r"[^\x00-\x1f]*", reply):
            return
        self.say("NOTICE " + nick + " :\x01" + word + (" " + reply if reply else "") + "\x01")

    def message(self, message):
        params, nick, tags = message["params"], message["nick"], message["tags"]
        target, text = params[0], params[1] if len(params) > 1 else ""
        notice = message["command"] == "NOTICE"
        if text.startswith("\x01ACTION ") and text.endswith("\x01"):
            text = "/me " + text[8:-1]
        elif text.startswith("\x01"):
            if not notice:
                self.ctcp(nick, text.strip("\x01"))
            return  # CTCP replies and DCC are never shown.
        outgoing = nick == self.nick
        source = message.get("source", nick)
        extra = {}
        if notice:
            extra["notice"] = "1"
        if not source or "!" not in source and ("." in source or not self.registered):
            # From the server itself (connection notices, server messages): the server chat.
            conversation = SERVER
            nick = source or self.account["server"]
        elif target[:1] in "#&":
            conversation = target
            if not outgoing and self.mentions(text):
                extra["mention"] = "1"
        elif target in ("*", "$*") or target[:1] == "$":
            conversation = SERVER  # Global notices to everyone on the network.
        else:
            # Direct messages are filed under the other person's nick, or the bot's.
            conversation = target if outgoing else nick
            if not outgoing and is_service(nick):
                extra["service"] = "1"
        self.on_message(conversation, nick, text, {**tags, **extra} if extra else tags, self.date(tags), outgoing)

    def list_channels(self, search="", limit=50, timeout=20):
        """The network's public channels (LIST), filtered by [search], busiest first, at most [limit]. Big networks
        send thousands in no useful order, so the [limit] busiest matches are kept and the rest are dropped."""
        if not self.ready.wait(20):
            raise ConnectionError("IRC isn’t connected yet")
        search = (search or "").strip().lower()[:100]
        with self.list_lock:
            self.listing = {"rows": [], "limit": max(1, min(int(limit), 200)), "search": search, "done": threading.Event()}
            try:
                # ELIST=M servers filter themselves; others send everything and the filter above applies.
                self.write("LIST" + (" *" + search.replace(" ", "") + "*" if search and "*" not in search else ""))
                self.listing["done"].wait(timeout)
                rows, most = self.listing["rows"], self.listing["limit"]
            finally:
                self.listing = None
        return sorted(rows, key=lambda row: -row["members"])[:most]

    def sync_channels(self, channels):
        """Joins channels added since the connection opened and parts ones that were left, without reconnecting."""
        wanted = {channel.lower(): channel for channel in channels}
        joined = {channel.lower(): channel for channel in self.account.get("channels", [])}
        self.account = {**self.account, "channels": sorted(wanted.values())}
        if not self.ready.is_set():
            return  # Joined on connect (001).
        for key, channel in wanted.items():
            # While signing in, joins wait (join_kept runs once it's done).
            if key not in joined and (self.joins_sent or not self.sign_in_pending):
                self.join_in_order(channel)
        for key, channel in joined.items():
            if key not in wanted:
                self.write("PART " + channel + " :Left in FluidMessages")
                self.channels.pop(key, None)

    def limit(self, command, target):
        """Bytes of text that fit in one line to [target] once the server adds its prefix."""
        return min(MAX_PAYLOAD, 510 - len((command + " " + target + " :").encode()) - len(self.nick.encode()) - PREFIX_ROOM)

    def connected(self):
        if not self.ready.wait(20):
            raise ConnectionError("IRC isn’t connected yet")

    def send(self, target, text, reply=None, react=None, command="PRIVMSG", plain=False):
        """Sends text, or a reaction when react is set. Long text is split into several lines at word breaks."""
        self.connected()
        tags = []
        if "message-tags" in self.caps and reply:
            tags.append("+draft/reply=" + escape_tag(reply))
        if react:
            if "message-tags" not in self.caps or not reply:
                raise ValueError("This IRC server doesn’t support reactions")
            self.say("@" + ";".join(tags + ["+draft/react=" + escape_tag(react)]) + " TAGMSG " + target)
            return
        prefix = "@" + ";".join(tags) + " " if tags else ""
        if text.startswith("/me ") and not plain:
            for line in split_text(text[4:], self.limit(command, target) - 9):
                self.say(prefix + command + " " + target + " :\x01ACTION " + line + "\x01")
                prefix = ""
            return
        for line in split_text(text, self.limit(command, target)):
            self.say(prefix + command + " " + target + " :" + line)
            prefix = ""

    def change_nick(self, nick):
        self.connected()
        self.wanted = nick
        self.say("NICK " + nick)

    def whois(self, nick, conversation):
        self.connected()
        if len(self.whois_for) > 20:
            self.whois_for.clear()
        self.whois_for[nick.lower()] = conversation
        self.say("WHOIS " + nick)

    def part(self, channel, reason):
        """/part with your reason; the channel comes off this connection's list so it isn't parted twice."""
        self.connected()
        self.say("PART " + channel + " :" + reason)
        self.account = {**self.account, "channels": [c for c in self.account.get("channels", []) if c.lower() != channel.lower()]}
        self.channels.pop(channel.lower(), None)

    def topic(self, channel):
        return self.channels.get(channel.lower(), {}).get("topic", "")

    def raw(self, line):
        """/quote: a raw line you typed, paced like any other."""
        self.connected()
        line = line.strip()
        if not line or len(line.encode()) > 510:
            raise ValueError("Enter one IRC line up to 510 bytes")
        self.say(line)
