"""Phones asking to connect from the same Wi-Fi (Find my computer on Android), approved here with one click.

A phone that found this computer (lan_announce) sends a hello with its model name and a random secret it keeps. Both
sides then show the same six-digit code, made from that secret and this computer's certificate fingerprint: the
phone from the certificate it actually connected to, this computer from its own. If someone on the Wi-Fi sat in the
middle with another certificate, the codes would differ. Clicking Connect here makes the pairing token
(auth.make_pairing), which only that phone can collect, once, with its secret. Nothing is written to disk; requests
are gone after three quiet minutes or a restart.
"""

import hashlib
import hmac
import secrets
import threading
import time

EXPIRES = 180
MOST = 16
HELLOS_PER_MINUTE = 6


def code(fingerprint, secret):
    """The six digits both sides show, as "123 456"."""
    digest = hashlib.sha256(("fluidmessages-pair:" + fingerprint.replace(":", "").lower() + ":" + secret).encode()).digest()
    number = int.from_bytes(digest[:4], "big") % 1_000_000
    text = "%06d" % number
    return text[:3] + " " + text[3:]


def clean_name(value):
    text = "".join(ch for ch in str(value or "") if ch.isprintable()).strip()[:40]
    return text or "An Android phone"


class Requests:
    def __init__(self, clock=time.time):
        self.clock = clock
        self.lock = threading.Lock()
        self.items = {}
        self.hellos = {}

    def _prune(self):
        now = self.clock()
        for key in [key for key, item in self.items.items() if now - item["seen"] > EXPIRES]:
            del self.items[key]
        self.hellos = {client: [moment for moment in moments if now - moment < 60] for client, moments in self.hellos.items()}
        self.hellos = {client: moments for client, moments in self.hellos.items() if moments}

    def hello(self, client, name, secret, fingerprint):
        """A phone asking to connect. Returns its request id. ValueError for a bad request; OverflowError when busy."""
        if not isinstance(secret, str) or not 32 <= len(secret) <= 128 or not secret.isalnum():
            raise ValueError("The phone's request wasn't right. Update FluidMessages on your phone.")
        if not fingerprint:
            raise ValueError("This computer's HTTPS port isn't on, so phones can't connect this way.")
        with self.lock:
            self._prune()
            moments = self.hellos.setdefault(client, [])
            if len(moments) >= HELLOS_PER_MINUTE:
                raise OverflowError("Too many requests from this phone. Wait a minute and try again.")
            moments.append(self.clock())
            digest = hashlib.sha256(secret.encode()).hexdigest()
            # The same phone asking again (its page reopened) keeps one request.
            for key, item in list(self.items.items()):
                if item["client"] == client or hmac.compare_digest(item["secret"], digest):
                    del self.items[key]
            if len(self.items) >= MOST:
                raise OverflowError("Too many phones are asking to connect right now. Try again in a few minutes.")
            key = secrets.token_hex(12)
            self.items[key] = {"client": client, "name": clean_name(name), "secret": digest, "code": code(fingerprint, secret),
                               "seen": self.clock(), "made": self.clock(), "state": "waiting", "token": None}
            return key

    def wait(self, key, secret):
        """What the phone sees while it waits: waiting, approved (with its token, given once), declined, or gone."""
        with self.lock:
            self._prune()
            item = self.items.get(key) if isinstance(key, str) else None
            if not item or not isinstance(secret, str) \
                    or not hmac.compare_digest(item["secret"], hashlib.sha256(secret.encode()).hexdigest()):
                return {"state": "gone"}
            item["seen"] = self.clock()
            if item["state"] == "approved":
                del self.items[key]
                return {"state": "approved", "token": item["token"]}
            if item["state"] == "declined":
                del self.items[key]
                return {"state": "declined"}
            return {"state": "waiting"}

    def waiting(self):
        """The phones waiting now, newest first, for the Connect your phone screen."""
        with self.lock:
            self._prune()
            now = self.clock()
            shown = [{"id": key, "name": item["name"], "code": item["code"], "seconds": int(now - item["made"])}
                     for key, item in self.items.items() if item["state"] == "waiting"]
        return sorted(shown, key=lambda item: item["seconds"])

    def answer(self, key, allow, make_token):
        """Connect or Not this one. [make_token] makes the pairing token, only when allowed."""
        with self.lock:
            self._prune()
            item = self.items.get(key) if isinstance(key, str) else None
            if not item or item["state"] != "waiting":
                raise ValueError("That phone stopped asking. Open Find my computer on it again.")
            if allow:
                item["token"] = make_token()
                item["state"] = "approved"
            else:
                item["state"] = "declined"
            return item["name"]
