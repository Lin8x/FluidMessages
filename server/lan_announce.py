"""Lets phones on the same Wi-Fi find FluidMessages Web without typing an address (Find my computer, on Android).

While the phone's HTTPS port listens on the local network (main.py start_tls), this answers multicast DNS (mDNS,
the way printers and TVs are found) for the service type _fluidmessages._tcp: the computer's name, its HTTPS port,
its addresses, and its certificate fingerprint. None of that is secret, and none of it lets anyone in: a phone that
finds the computer still has to be approved on it, after both show the same six-digit code (nearby_pair).

It's a small responder on purpose, with no extra library, so every package (AppImage, Flatpak, COPR, Docker) has it.
It shares UDP port 5353 with the system's own mDNS (Avahi, Bonjour), and answers only questions about itself.
"""

import os
import socket
import struct
import threading
import time

GROUP = "224.0.0.251"
SERVICE = "_fluidmessages._tcp.local"
BROWSE = "_services._dns-sd._udp.local"
TTL = 120
PTR, A, TXT, SRV, ANY = 12, 1, 16, 33, 255
CLASS_IN, FLUSH = 1, 0x8000


def mdns_port():
    """5353, the mDNS port. FLUID_MDNS_PORT changes it for tests; 0 turns announcing off."""
    try:
        return int(os.environ.get("FLUID_MDNS_PORT", "5353"))
    except ValueError:
        return 5353


def label(text, limit=63):
    """A DNS label: UTF-8, at most [limit] bytes, cut at a character boundary."""
    data = text.encode("utf-8")[:limit]
    return data.decode("utf-8", "ignore").encode("utf-8")


def host_label(name):
    """This computer's name as a .local host name: letters, digits and hyphens."""
    cleaned = "".join(ch if ch.isascii() and ch.isalnum() else "-" for ch in name).strip("-").lower()
    return (cleaned or "computer")[:40] + "-fluidmessages"


def encode_name(name):
    out = b""
    for part in name.split("."):
        if part:
            data = label(part)
            out += bytes([len(data)]) + data
    return out + b"\0"


def encode_instance(instance):
    """'<computer name>._fluidmessages._tcp.local': the first label may hold dots and spaces."""
    data = label(instance)
    return bytes([len(data)]) + data + encode_name(SERVICE)


def read_name(packet, offset, depth=0):
    """A name from a DNS packet, following compression pointers. Returns (lowercase name, offset after it)."""
    parts = []
    jumped = None
    hops = 0
    while True:
        if offset >= len(packet):
            raise ValueError("Short packet")
        length = packet[offset]
        if length & 0xC0 == 0xC0:
            if offset + 1 >= len(packet) or hops > 20:
                raise ValueError("Bad pointer")
            pointer = ((length & 0x3F) << 8) | packet[offset + 1]
            if jumped is None:
                jumped = offset + 2
            offset = pointer
            hops += 1
            continue
        offset += 1
        if length == 0:
            break
        parts.append(packet[offset:offset + length].decode("utf-8", "replace"))
        offset += length
    return ".".join(parts).lower(), (jumped if jumped is not None else offset)


def questions(packet):
    """The questions in an mDNS query: [(name, type, unicast reply asked)]. Responses and junk give []."""
    if len(packet) < 12:
        return []
    ident, flags, count = struct.unpack("!HHH", packet[:6])
    if flags & 0x8000:
        return []
    out = []
    offset = 12
    try:
        for _ in range(min(count, 32)):
            name, offset = read_name(packet, offset)
            kind, klass = struct.unpack("!HH", packet[offset:offset + 4])
            offset += 4
            out.append((name, kind, bool(klass & 0x8000)))
    except (ValueError, struct.error):
        return out
    return out


def record(name_bytes, kind, data, ttl, flush=False):
    return name_bytes + struct.pack("!HHIH", kind, CLASS_IN | (FLUSH if flush else 0), ttl, len(data)) + data


def txt_data(values):
    out = b""
    for key, value in values.items():
        item = (key + "=" + value).encode("utf-8")[:255]
        out += bytes([len(item)]) + item
    return out or b"\0"


class Announcement:
    """What's announced: [name] (shown on the phone), [port] (HTTPS), [addresses] (IPv4), [fingerprint] (hex)."""

    def __init__(self, name, port, addresses, fingerprint):
        self.name = (name or "Computer").strip()[:50] or "Computer"
        self.port = int(port)
        self.addresses = [value for value in addresses if value][:6]
        self.fingerprint = fingerprint.replace(":", "").lower()
        self.instance = self.name
        self.host = host_label(self.name) + ".local"

    def matches(self, asked):
        """Whether a question is about this service."""
        instance = (self.instance + "." + SERVICE).lower()
        for name, kind, _ in asked:
            if name == SERVICE and kind in (PTR, ANY):
                return True
            if name == BROWSE and kind in (PTR, ANY):
                return True
            if name == instance and kind in (SRV, TXT, ANY):
                return True
            if name == self.host.lower() and kind in (A, ANY):
                return True
        return False

    def packet(self, ttl=TTL, ident=0, echo=b""):
        """A full answer: PTR, then SRV, TXT and A records, so one packet is enough to connect."""
        service = encode_name(SERVICE)
        instance = encode_instance(self.instance)
        host = encode_name(self.host)
        txt = txt_data({"v": "1", "fp": self.fingerprint, "a": ",".join(self.addresses), "name": self.name})
        answers = [record(service, PTR, instance, ttl), record(encode_name(BROWSE), PTR, service, ttl)]
        extra = [record(instance, SRV, struct.pack("!HHH", 0, 0, self.port) + host, ttl, True),
                 record(instance, TXT, txt, ttl, True)]
        for address in self.addresses:
            try:
                extra.append(record(host, A, socket.inet_aton(address), ttl, True))
            except OSError:
                pass
        questions_count = 1 if echo else 0
        head = struct.pack("!HHHHHH", ident, 0x8400, questions_count, len(answers), 0, len(extra))
        return head + echo + b"".join(answers) + b"".join(extra)


class Announcer:
    """Answers mDNS questions for one Announcement on a background thread until stop()."""

    def __init__(self, announcement, port=None):
        self.announcement = announcement
        self.port = mdns_port() if port is None else port
        self.sock = None
        self.running = False

    def start(self):
        """Starts answering. Returns a problem in plain words, or None."""
        if not self.port:
            return "Announcing on the Wi-Fi is off (FLUID_MDNS_PORT=0)."
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM, socket.IPPROTO_UDP)
        try:
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            if hasattr(socket, "SO_REUSEPORT"):
                try:
                    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEPORT, 1)
                except OSError:
                    pass
            sock.bind(("", self.port))
            sock.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_TTL, 255)
            sock.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_LOOP, 1)
            joined = 0
            for address in self.announcement.addresses or ["0.0.0.0"]:
                try:
                    sock.setsockopt(socket.IPPROTO_IP, socket.IP_ADD_MEMBERSHIP, socket.inet_aton(GROUP) + socket.inet_aton(address))
                    joined += 1
                except OSError:
                    pass
            if not joined:
                sock.setsockopt(socket.IPPROTO_IP, socket.IP_ADD_MEMBERSHIP, socket.inet_aton(GROUP) + socket.inet_aton("0.0.0.0"))
            sock.settimeout(1.0)
        except OSError as error:
            sock.close()
            return "Couldn't announce on the Wi-Fi (%s). Phones can still connect with the QR code or the address." % (error.strerror or error)
        self.sock = sock
        self.running = True
        threading.Thread(target=self._serve, name="fluid-mdns", daemon=True).start()
        threading.Thread(target=self._greet, name="fluid-mdns-hello", daemon=True).start()
        return None

    def _send(self, data, target=None):
        sock = self.sock
        if not sock:
            return
        if target:
            try:
                sock.sendto(data, target)
            except OSError:
                pass
            return
        for address in self.announcement.addresses or [None]:
            try:
                if address:
                    sock.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_IF, socket.inet_aton(address))
                sock.sendto(data, (GROUP, self.port))
            except OSError:
                pass

    def _greet(self):
        # Unasked announcements when it starts (RFC 6762 section 8.3), so a phone already looking sees it at once.
        for wait in (0, 1, 2):
            time.sleep(wait)
            if not self.running:
                return
            self._send(self.announcement.packet())

    def _serve(self):
        while self.running:
            try:
                packet, source = self.sock.recvfrom(9000)
            except socket.timeout:
                continue
            except OSError:
                break
            asked = questions(packet)
            if not asked or not self.announcement.matches(asked):
                continue
            if source[1] != self.port:
                # A one-shot question from an ordinary port: answered straight back, with its id and question.
                end = 12
                try:
                    _, end = read_name(packet, 12)
                    end += 4
                except ValueError:
                    continue
                echo = packet[12:end]
                self._send(self.announcement.packet(ident=struct.unpack("!H", packet[:2])[0], echo=echo), source)
                continue
            if any(unicast for _, _, unicast in asked):
                self._send(self.announcement.packet(), source)
            self._send(self.announcement.packet())

    def stop(self):
        if not self.running:
            return
        # A goodbye (TTL 0), so phones drop it from their list right away.
        self._send(self.announcement.packet(ttl=0))
        self.running = False
        try:
            self.sock.close()
        except OSError:
            pass
        self.sock = None
