"""A small IRC client for the companion: TLS only, SASL PLAIN, and IRCv3 features when the server offers them.

It keeps one connection open (like a bouncer), so the phone never holds an IRC socket. Channel members and
topics are tracked for the phone's IRC channel screen. Replies and reactions use the IRCv3 client tags
+draft/reply and +draft/react when the server supports message-tags, with readable text otherwise.
"""
import base64
from datetime import datetime
import re
import socket
import ssl
import threading
import time

WANTED_CAPS = ("sasl", "message-tags", "server-time", "echo-message", "multi-prefix", "away-notify")
LINE = re.compile(r"^(?:@(?P<tags>\S+) )?(?::(?P<prefix>\S+) )?(?P<command>\S+)(?P<params>.*)$")
MAX_PAYLOAD = 400  # bytes of text per PRIVMSG; servers cut lines at 512 including the prefix


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
    nick = (match["prefix"] or "").split("!", 1)[0]
    return {"tags": tags, "nick": nick, "command": match["command"].upper(), "params": params}


def escape_tag(value):
    return value.replace("\\", "\\\\").replace(";", "\\:").replace(" ", "\\s").replace("\r", "\\r").replace("\n", "\\n")


def split_text(text):
    """Splits on UTF-8 byte limits without breaking characters, one IRC line per paragraph line."""
    for line in text.replace("\r", "").split("\n"):
        data = line.encode()
        while len(data) > MAX_PAYLOAD:
            cut = MAX_PAYLOAD
            while cut and (data[cut] & 0xC0) == 0x80:
                cut -= 1
            space = data.rfind(b" ", 0, cut)
            cut = space if space > MAX_PAYLOAD // 2 else cut
            yield data[:cut].decode()
            data = data[cut:].lstrip(b" ")
        if data:
            yield data.decode()


class IrcClient:
    def __init__(self, account, on_message, on_state, connect=None):
        self.account = account
        self.on_message = on_message  # (target, nick, text, tags, date_ms, outgoing)
        self.on_state = on_state      # (channel, {"topic":..., "members": [...]})
        self.connect_socket = connect or self.open_tls
        self.caps = set()
        self.offered = set()
        self.nick = account["nick"]
        self.channels = {}
        self.sock = None
        self.lock = threading.Lock()
        self.stopped = threading.Event()
        self.ready = threading.Event()
        # A channel LIST in progress: {"rows": [...], "limit": n, "done": Event}.
        self.listing = None
        self.list_lock = threading.Lock()

    def open_tls(self):
        raw = socket.create_connection((self.account["server"], int(self.account.get("port", 6697))), timeout=30)
        return ssl.create_default_context().wrap_socket(raw, server_hostname=self.account["server"])

    def write(self, line):
        if "\r" in line or "\n" in line:
            raise ValueError("Invalid IRC line")
        with self.lock:
            if self.sock is None:
                raise ConnectionError("IRC is reconnecting. Try again in a moment.")
            self.sock.sendall(line.encode()[:510] + b"\r\n")

    def stop(self):
        self.stopped.set()
        try:
            self.write("QUIT :FluidMessages")
        except (OSError, ConnectionError, ValueError):
            pass

    def run(self):
        delay = 5
        while not self.stopped.is_set():
            try:
                self.session()
                delay = 5
            except (OSError, ValueError, ConnectionError):
                pass
            finally:
                self.ready.clear()
                with self.lock:
                    if self.sock is not None:
                        try:
                            self.sock.close()
                        except OSError:
                            pass
                    self.sock = None
            # Back off up to five minutes so a down server doesn't keep the companion busy.
            self.stopped.wait(delay)
            delay = min(delay * 2, 300)

    def session(self):
        sock = self.connect_socket()
        sock.settimeout(300)
        with self.lock:
            self.sock = sock
        self.caps.clear()
        self.offered = set()
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
            self.write("CAP END")
        elif command == "001":
            self.nick = params[0] if params else self.nick
            for channel in self.account.get("channels", []):
                self.write("JOIN " + channel)
            self.ready.set()
        elif command == "322" and len(params) >= 3 and self.listing is not None:
            # RPL_LIST: me, channel, visible users, topic.
            listing = self.listing
            if len(listing["rows"]) < listing["limit"] and (not listing["search"] or listing["search"] in
                                                            (params[1] + " " + (params[3] if len(params) > 3 else "")).lower()):
                users = int(params[2]) if params[2].isdigit() else 0
                listing["rows"].append({"target": params[1], "name": params[1], "members": users,
                                        "topic": (params[3] if len(params) > 3 else "")[:300]})
        elif command in ("323", "416") and self.listing is not None:
            self.listing["done"].set()
        elif command == "433":
            self.nick = self.nick[:28] + "_"
            self.write("NICK " + self.nick)
        elif command in ("PRIVMSG", "NOTICE") and len(params) == 2:
            self.message(message)
        elif command == "TAGMSG" and params:
            self.message(message)
        elif command == "332" and len(params) >= 3:
            self.channel(params[1])["topic"] = params[2]
            self.publish(params[1])
        elif command == "TOPIC" and len(params) == 2:
            self.channel(params[0])["topic"] = params[1]
            self.publish(params[0])
            self.event(params[0], f"{message['nick']} changed the topic to “{params[1]}”", message)
        elif command == "353" and len(params) >= 4:
            members = self.channel(params[2]).setdefault("pending", set())
            members.update(name.lstrip("~&@%+") for name in params[3].split())
        elif command == "366" and len(params) >= 2:
            state = self.channel(params[1])
            state["members"] = set(state.pop("pending", set()))
            self.publish(params[1])
        elif command == "JOIN" and params:
            state = self.channel(params[0])
            state.setdefault("members", set()).add(message["nick"])
            self.publish(params[0])
            if message["nick"] != self.nick:
                self.event(params[0], f"{message['nick']} joined", message)
        elif command == "PART" and params:
            self.channel(params[0]).setdefault("members", set()).discard(message["nick"])
            self.publish(params[0])
            self.event(params[0], f"{message['nick']} left", message)
        elif command == "QUIT":
            for name, state in self.channels.items():
                if message["nick"] in state.get("members", set()):
                    state["members"].discard(message["nick"])
                    self.publish(name)
                    self.event(name, f"{message['nick']} quit", message)
        elif command == "NICK" and params:
            for name, state in self.channels.items():
                if message["nick"] in state.get("members", set()):
                    state["members"].discard(message["nick"])
                    state["members"].add(params[0])
                    self.publish(name)
                    self.event(name, f"{message['nick']} is now {params[0]}", message)
            if message["nick"] == self.nick:
                self.nick = params[0]

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

    def publish(self, name):
        state = self.channel(name)
        self.on_state(state["name"], {"topic": state.get("topic", ""), "members": sorted(state.get("members", set()))})

    def date(self, tags):
        stamp = tags.get("time")
        try:
            return int(datetime.fromisoformat(stamp.replace("Z", "+00:00")).timestamp() * 1000) if stamp else int(time.time() * 1000)
        except ValueError:
            return int(time.time() * 1000)

    def event(self, channel, text, message):
        self.on_message(channel, "", text, {"event": "1", **message["tags"]}, self.date(message["tags"]), False)

    def message(self, message):
        params, nick, tags = message["params"], message["nick"], message["tags"]
        target, text = params[0], params[1] if len(params) > 1 else ""
        if text.startswith("\x01ACTION ") and text.endswith("\x01"):
            text = "/me " + text[8:-1]
        elif text.startswith("\x01"):
            return  # Other CTCP requests are ignored; FluidMessages doesn't answer VERSION or DCC.
        outgoing = nick == self.nick
        # Direct messages are filed under the other person's nick; channel messages under the channel.
        conversation = target if target[:1] in "#&" else (target if outgoing else nick)
        self.on_message(conversation, nick, text, tags, self.date(tags), outgoing)

    def list_channels(self, search="", limit=50, timeout=20):
        """The network's public channels (LIST), filtered by [search], at most [limit]. Big networks send thousands,
        so only the first [limit] matches are kept; the rest of the reply is read and dropped."""
        if not self.ready.wait(20):
            raise ConnectionError("IRC isn’t connected yet")
        search = (search or "").strip().lower()[:100]
        with self.list_lock:
            self.listing = {"rows": [], "limit": max(1, min(int(limit), 200)), "search": search, "done": threading.Event()}
            try:
                # ELIST=M servers filter themselves; others send everything and the filter above applies.
                self.write("LIST" + (" *" + search.replace(" ", "") + "*" if search and "*" not in search else ""))
                self.listing["done"].wait(timeout)
                rows = self.listing["rows"]
            finally:
                self.listing = None
        return sorted(rows, key=lambda row: -row["members"])

    def sync_channels(self, channels):
        """Joins channels added since the connection opened and parts ones that were left, without reconnecting."""
        wanted = {channel.lower(): channel for channel in channels}
        joined = {channel.lower(): channel for channel in self.account.get("channels", [])}
        self.account = {**self.account, "channels": sorted(wanted.values())}
        if not self.ready.is_set():
            return  # Joined on connect (001).
        for key, channel in wanted.items():
            if key not in joined:
                self.write("JOIN " + channel)
        for key, channel in joined.items():
            if key not in wanted:
                self.write("PART " + channel + " :Left in FluidMessages")
                self.channels.pop(key, None)

    def send(self, target, text, reply=None, react=None):
        """Sends text, or a reaction when react is set. Returns the lines' msgid when echo-message gives one."""
        if not self.ready.wait(20):
            raise ConnectionError("IRC isn’t connected yet")
        tags = []
        if "message-tags" in self.caps and reply:
            tags.append("+draft/reply=" + escape_tag(reply))
        if react:
            if "message-tags" not in self.caps or not reply:
                raise ValueError("This IRC server doesn’t support reactions")
            self.write("@" + ";".join(tags + ["+draft/react=" + escape_tag(react)]) + " TAGMSG " + target)
            return
        prefix = "@" + ";".join(tags) + " " if tags else ""
        if text.startswith("/me "):
            self.write(prefix + "PRIVMSG " + target + " :\x01ACTION " + text[4:] + "\x01")
            return
        for line in split_text(text):
            self.write(prefix + "PRIVMSG " + target + " :" + line)
            prefix = ""
