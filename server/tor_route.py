"""Connect through Tor for FluidMessages Web (Settings → Privacy and security), off by default.

With it on, the server's own outgoing connections to chat services (IRC, Matrix, email) and link previews go
through a Tor SOCKS proxy, 127.0.0.1:9050 unless you change it. Host names are sent to Tor unresolved (SOCKS5 with
remote DNS), so this computer never looks them up itself. When the proxy doesn't answer, connections fail with
NOT_READY instead of going direct.

A minimal SOCKS5 CONNECT client (RFC 1928, no sign-in) lives here, so no extra library is needed.

Not routed: XMPP and encrypted Matrix rooms (slixmpp and matrix-nio open their own connections), so XMPP waits
with XMPP_HELD and Matrix uses its plain connection, which goes through Tor. Cloud backups run rclone, outside
this setting. Addresses on your own network (192.168.x.x, .local, Tailscale) connect directly.

Tor per chat: a chat's details choose Tor for its connection (one IRC network, Matrix account, or mailbox), stored as
{host: True/False} in "hosts" and checked before the setting (wanted). chat_choice says whether a chat can use Tor
and why not, in the same words as the phone (TorChat.kt).
"""
import http.client
import ipaddress
import json
import smtplib
import imaplib
import socket
import ssl
import threading
from urllib.request import HTTPSHandler as _HTTPSHandler

DEFAULT_PROXY = "127.0.0.1:9050"
WARNING = ("Tor hides your internet address from chat servers and from anyone watching your network. It makes "
           "connections slower and less reliable, and some servers block Tor. Texts (SMS) and calls over your phone "
           "plan never use Tor.")
NOT_READY = ("Tor isn't running, so FluidMessages Web didn't connect. Start Tor on this computer, or turn off Connect "
             "through Tor in Settings → Privacy and security.")
XMPP_HELD = ("XMPP on FluidMessages Web can't go through Tor yet, so it waits while Connect through Tor is on. Connect "
             "XMPP from your phone instead, or turn off Connect through Tor.")
E2EE_HELD = ("Encrypted Matrix rooms on FluidMessages Web can't go through Tor yet. Turn off Connect through Tor in "
             "Settings → Privacy and security to use this room here.")
INSTALL = ("Install Tor on the computer running FluidMessages Web. On Debian or Ubuntu: sudo apt install tor. On "
           "macOS with Homebrew: brew install tor, then brew services start tor. With Docker, run a Tor container and "
           "set the proxy address to it, for example tor:9050.")

TEXTS = "Texts go over your phone plan, not the internet"
NEARBY = "Nearby doesn't use the internet"
XMPP_SWITCH = ("XMPP on FluidMessages Web can't go through Tor yet, because its library opens its own connections. "
               "Connect XMPP from your phone to use Tor")
E2EE_SWITCH = ("Encrypted Matrix rooms on FluidMessages Web can't go through Tor yet, because their library opens its "
               "own connections")
WORDS = {"tor": "Through Tor", "wait": "Waiting for Tor", "direct": "Not through Tor"}

_lock = threading.Lock()
_state = {"on": False, "proxy": DEFAULT_PROXY, "hosts": {}}
_ready = {"at": 0.0, "value": False}

REPLIES = {1: "Tor couldn't connect", 2: "Tor's rules don't allow that connection", 3: "Tor couldn't reach that network",
           4: "Tor couldn't reach that server", 5: "The server refused the connection through Tor",
           6: "The connection through Tor timed out", 7: "Tor didn't understand the request",
           8: "Tor doesn't support that kind of address"}


class TorError(OSError):
    pass


def configure(on, proxy=None, hosts=None):
    with _lock:
        _state["on"] = bool(on)
        _state["proxy"] = proxy or _state["proxy"] or DEFAULT_PROXY
        if hosts is not None:
            _state["hosts"] = clean_hosts(hosts)
        _ready["at"] = 0.0


def enabled():
    return _state["on"]


def host_key(host):
    return (host or "").strip().lower().strip("[]").rstrip(".")


def clean_hosts(hosts):
    """The stored per-connection choices, {host: bool}, with anything else dropped."""
    if not isinstance(hosts, dict):
        return {}
    return {host_key(name): value for name, value in hosts.items()
            if isinstance(name, str) and host_key(name) and len(name) <= 300 and type(value) is bool}


def hosts():
    return dict(_state["hosts"])


def wants(on, choices, host):
    """Whether a connection to [host] goes through Tor: its own choice from chat details first, then the setting."""
    choice = (choices or {}).get(host_key(host))
    return on if choice is None else choice


def wanted(host):
    return wants(_state["on"], _state["hosts"], host)


def used():
    """Tor is in use for something: the setting, or one connection's own choice."""
    return _state["on"] or any(_state["hosts"].values())


def with_choice(choices, names, through, on):
    """The choices after one connection's switch: its [names] follow [through], or the setting again when it agrees."""
    result = dict(choices or {})
    for name in names:
        key = host_key(name)
        if not key:
            continue
        result.pop(key, None)
        if through != on:
            result[key] = through
    return result


def choose(names, through):
    with _lock:
        _state["hosts"] = with_choice(_state["hosts"], names, through, _state["on"])
        return dict(_state["hosts"])


def account_hosts(protocol, values):
    """The servers an account's connection opens, which its switch changes together (a mailbox has two)."""
    values = values if isinstance(values, dict) else {}
    pick = lambda key: str(values.get(key) or "").strip().lower()
    if protocol == "irc":
        found = [pick("server")]
    elif protocol == "matrix":
        found = [pick("homeserver").removeprefix("https://").removeprefix("http://").split("/")[0].split(":")[0]]
    elif protocol == "xmpp":
        found = [pick("host") or pick("jid").partition("@")[2].split("/")[0]]
    elif protocol == "email":
        found = [pick("imap_host"), pick("smtp_host")]
    else:
        found = []
    return list(dict.fromkeys(name for name in found if name))


def chat_choice(protocol, local=False, encrypted=False, where=""):
    """Whether a chat can use Tor, and why not: (allowed, words). The same rules as the phone's (TorChat.choice)."""
    if protocol in ("sms", "mms", "rcs"):
        return False, TEXTS
    if protocol == "nearby":
        return False, NEARBY
    if protocol == "xmpp":
        return False, XMPP_SWITCH
    if protocol == "matrix" and encrypted:
        return False, E2EE_SWITCH
    if protocol not in ("irc", "matrix", "email"):
        return False, "This chat isn't connected to a server yet"
    if local:
        return False, (where or "This server") + (" is on your own network, which Tor can't reach, so it connects "
                                                  "directly and stays on your network")
    return True, ""


def route(protocol, names, ready_now):
    """"tor", "wait", or "direct": how an account's connection goes right now. XMPP never goes through Tor here."""
    names = [name for name in names if not local_host(name)]
    if not names or not all(wanted(name) for name in names):
        return "direct"
    if protocol == "xmpp":
        return "wait" if enabled() else "direct"
    return "tor" if ready_now else "wait"


def cached_ready():
    """ready(), asked at most every 5 seconds, for the inbox's marks."""
    import time
    now = time.monotonic()
    if now - _ready["at"] > 5:
        _ready["value"], _ready["at"] = ready(1), now
    return _ready["value"]


def connection(protocol, values, where):
    """One account's Tor, for the apps: its route and words, the switch's state, and whether it can be changed."""
    names = account_hosts(protocol, values)
    local = bool(names) and all(local_host(name) for name in names)
    allowed, why = chat_choice(protocol, local, False, where)
    way = route(protocol, names, cached_ready() if used() else False)
    on = bool(names) and all(wanted(name) for name in names)
    return {"route": way, "words": WORDS[way], "on": on and allowed, "allowed": allowed, "why": why, "where": where,
            "local": local, "status": "" if way != "wait" else "Tor isn't running at " + _state["proxy"]}


def signature():
    """Part of a chat client's signature in protocol_gateway, so turning Tor on or off reconnects it."""
    return json.dumps(dict(_state), sort_keys=True)


def signature_for(names):
    """The same, for one account: only its own servers' choices count, so another chat's switch doesn't reconnect it."""
    return json.dumps({"proxy": _state["proxy"], "on": _state["on"], "through": [wanted(name) for name in names]})


def hold_xmpp():
    """What an XMPP account does on FluidMessages Web while Tor is on: wait, with words."""
    raise ConnectionError(XMPP_HELD)


def parse_proxy(value):
    """("127.0.0.1", 9050) from "127.0.0.1:9050", "tor:9050", or "[::1]:9050". Raises ValueError otherwise."""
    if not isinstance(value, str) or not value.strip() or len(value) > 300:
        raise ValueError("Type the Tor proxy address, such as 127.0.0.1:9050")
    text = value.strip()
    host, _, port = text.rpartition(":")
    host = host.strip("[]")
    if not host or not port.isdigit() or not 0 < int(port) < 65536 or any(c.isspace() or c in "/@" for c in host):
        raise ValueError("Type the Tor proxy address as host:port, such as 127.0.0.1:9050")
    return host, int(port)


def proxy_address():
    return parse_proxy(_state["proxy"])


def local_host(host):
    """A host on this computer or your own network, judged from the name alone (nothing is looked up)."""
    name = (host or "").strip().lower().strip("[]").rstrip(".")
    if not name or name.endswith(".onion"):
        return False
    try:
        address = ipaddress.ip_address(name.split("%")[0])
    except ValueError:
        return "." not in name or name.endswith((".local", ".lan", ".home.arpa", ".internal", ".ts.net", ".localhost"))
    return (address.is_private or address.is_loopback or address.is_link_local
            or address in ipaddress.ip_network("100.64.0.0/10"))


def decide(on, ready, local):
    """"direct", "tor", or "wait" (Tor is on but not answering: never direct)."""
    if not on or local:
        return "direct"
    return "tor" if ready else "wait"


def socks5_connect(sock, host, port):
    """The SOCKS5 handshake on a socket already connected to the proxy: no sign-in, then CONNECT by host name, so
    Tor resolves it (remote DNS). Raises TorError with plain words when Tor says no."""
    sock.sendall(b"\x05\x01\x00")
    reply = _read(sock, 2)
    if reply[0] != 5 or reply[1] != 0:
        raise TorError("The Tor proxy didn't accept the connection. Check the proxy address.")
    try:
        literal = ipaddress.ip_address(host)
    except ValueError:
        literal = None
    if literal is None:
        name = host.encode("idna")
        if not 0 < len(name) < 256:
            raise TorError("That server name is too long for Tor")
        target = b"\x03" + bytes([len(name)]) + name
    else:
        target = (b"\x01" if literal.version == 4 else b"\x04") + literal.packed
    sock.sendall(b"\x05\x01\x00" + target + port.to_bytes(2, "big"))
    head = _read(sock, 4)
    if head[0] != 5:
        raise TorError("The Tor proxy answered in a way FluidMessages Web doesn't understand")
    if head[1] != 0:
        raise TorError(REPLIES.get(head[1], "Tor couldn't connect") + ".")
    kind = head[3]
    length = 4 if kind == 1 else 16 if kind == 4 else _read(sock, 1)[0] if kind == 3 else None
    if length is None:
        raise TorError("The Tor proxy answered in a way FluidMessages Web doesn't understand")
    _read(sock, length + 2)
    return sock


def _read(sock, count):
    data = b""
    while len(data) < count:
        chunk = sock.recv(count - len(data))
        if not chunk:
            raise TorError("The Tor proxy closed the connection")
        data += chunk
    return data


def ready(timeout=2):
    """Whether the Tor proxy answers a SOCKS5 greeting."""
    try:
        with socket.create_connection(proxy_address(), timeout=timeout) as sock:
            sock.settimeout(timeout)
            sock.sendall(b"\x05\x01\x00")
            return _read(sock, 2) == b"\x05\x00"
    except (OSError, ValueError):
        return False


def status():
    """What Settings → Privacy and security shows."""
    on, proxy = _state["on"], _state["proxy"]
    reachable = ready()
    return {"on": on, "proxy": proxy, "reachable": reachable, "warning": WARNING, "install": INSTALL,
            "state": "off" if not on else "connected" if reachable else "not_running",
            # Connections whose chat details chose Tor (or not) themselves.
            "chosen": len(_state["hosts"])}


def create_connection(address, timeout=None, source_address=None, **_ignored):
    """socket.create_connection, through Tor while it's on. Never falls back to a direct connection."""
    host, port = address[0], int(address[1])
    way = decide(wanted(host), True, local_host(host))
    if way == "direct":
        return socket.create_connection((host, port), timeout, source_address)
    try:
        sock = socket.create_connection(proxy_address(), timeout=timeout)
    except OSError as error:
        raise TorError(NOT_READY) from error
    try:
        sock.settimeout(timeout)
        return socks5_connect(sock, host, port)
    except BaseException:
        sock.close()
        raise


def mail(standard, host=None):
    """The mail class to use: [standard] (smtplib.SMTP_SSL and the like) when [host] doesn't use Tor, or its routed copy."""
    if not (wanted(host) if host else enabled()):
        return standard
    return {"SMTP_SSL": SMTP_SSL, "SMTP": SMTP, "IMAP4_SSL": IMAP4_SSL, "IMAP4": IMAP4}[standard.__name__]


# Drop-in classes for the standard library, routed through create_connection.

class HTTPSConnection(http.client.HTTPSConnection):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
        self._create_connection = create_connection


class HTTPSHandler(_HTTPSHandler):
    def https_open(self, req):
        return self.do_open(HTTPSConnection, req, context=self._context)


class _SMTPBase(smtplib.SMTP):
    def _get_socket(self, host, port, timeout):
        return create_connection((host, port), timeout, self.source_address)


class SMTP(_SMTPBase):
    pass


class SMTP_SSL(smtplib.SMTP_SSL, _SMTPBase):
    pass


class IMAP4(imaplib.IMAP4):
    def _create_socket(self, timeout):
        return create_connection((self.host or None, self.port), timeout)


class IMAP4_SSL(imaplib.IMAP4_SSL):
    def _create_socket(self, timeout):
        sock = create_connection((self.host or None, self.port), timeout)
        return self.ssl_context.wrap_socket(sock, server_hostname=self.host)
