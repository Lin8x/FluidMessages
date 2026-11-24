"""Signing in to FluidMessages Web without typing an access token.

- On the computer that runs it, FluidMessages Web opens like any app: no password and no code. Only a request that
  really comes from this computer counts (see from_this_computer below): a loopback connection, or the Docker
  gateway when the server is marked local-only, addressed to localhost, sent by FluidMessages Web's own page, and
  not passed along by a reverse proxy.
- Every other device signs in with a password, which you set the moment you turn on other devices or connect a
  phone. Only a salted scrypt hash is stored (PBKDF2-SHA256 where scrypt isn't available), never the password.
- Reached from another device before any password exists, first-run setup needs the one-time setup code the server
  prints in its log and keeps in its data folder, or the server's access token (FLUID_TOKEN, or a pairing token).
  A stranger who finds the page can't claim it.
- Browsers get a random session cookie: HttpOnly, SameSite=Strict, and Secure over HTTPS. The server keeps only
  a SHA-256 of each session, so a copy of the database can't be used to sign in.
- Wrong passwords and setup codes are rate-limited per address and overall.
- Phones, desktop apps, and scripts keep using a bearer token: FLUID_TOKEN from the server's settings, or a pairing
  token made in the web app's settings (stored only as a hash).
- Privacy and security (privacy.py): a private folder passcode, a decoy password, and an emergency erase passcode,
  each kept only as a salted scrypt hash like the password. Every password sign-in checks all three the same way,
  so a decoy or emergency sign-in takes as long and answers the same as a real one. None of them is ever logged.
"""
import hashlib
import hmac
import ipaddress
import json
import os
import secrets
import threading
import time
from pathlib import Path

REMEMBER_SECONDS = 30 * 86400
SHORT_SECONDS = 12 * 3600
MIN_PASSWORD, MAX_PASSWORD = 8, 1024
CODE_ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"
COMMON = {"password", "password1", "12345678", "123456789", "1234567890", "qwertyui", "qwerty123", "iloveyou",
          "fluidmessages", "letmein1", "11111111", "00000000", "abcdefgh", "abc12345"}

SCHEMA = """
CREATE TABLE IF NOT EXISTS auth_state (key TEXT PRIMARY KEY, value TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS auth_sessions (
    id TEXT PRIMARY KEY, created INTEGER NOT NULL, seen INTEGER NOT NULL, expires INTEGER NOT NULL,
    persistent INTEGER NOT NULL, label TEXT NOT NULL DEFAULT ''
);
"""
# Per-session privacy flags, added to auth_sessions by Auth.migrate: a decoy session (signed in with the decoy
# password, or switched by Quick hide) and whether this session opened the private folder.
SESSION_FLAGS = {"decoy": "INTEGER NOT NULL DEFAULT 0", "private_open": "INTEGER NOT NULL DEFAULT 0"}
MAX_CODE = 1024
CODE_NAMES = {"owner": "your FluidMessages Web password", "decoy": "your decoy password",
              "distress": "your emergency erase passcode", "private_code": "your private folder passcode"}


class Refused(Exception):
    """A sign-in problem to show the person, with the HTTP status to send."""

    def __init__(self, status, message):
        super().__init__(message)
        self.status = status


def _digest(value):
    return hashlib.sha256(value.encode()).hexdigest()


def hash_password(password):
    salt = os.urandom(16)
    if hasattr(hashlib, "scrypt"):
        n, r, p = 2 ** 15, 8, 1
        value = hashlib.scrypt(password.encode(), salt=salt, n=n, r=r, p=p, maxmem=64 * 1024 * 1024, dklen=32)
        return {"scheme": "scrypt", "n": n, "r": r, "p": p, "salt": salt.hex(), "hash": value.hex()}
    iterations = 600_000
    value = hashlib.pbkdf2_hmac("sha256", password.encode(), salt, iterations)
    return {"scheme": "pbkdf2-sha256", "iterations": iterations, "salt": salt.hex(), "hash": value.hex()}


def check_password(stored, password):
    try:
        salt, expected = bytes.fromhex(stored["salt"]), bytes.fromhex(stored["hash"])
        if stored["scheme"] == "scrypt":
            n, r, p = int(stored["n"]), int(stored["r"]), int(stored["p"])
            if not (2 ** 10 <= n <= 2 ** 20 and 1 <= r <= 16 and 1 <= p <= 4):
                return False
            value = hashlib.scrypt(password.encode(), salt=salt, n=n, r=r, p=p, maxmem=256 * 1024 * 1024, dklen=len(expected))
        elif stored["scheme"] == "pbkdf2-sha256":
            iterations = int(stored["iterations"])
            if not 100_000 <= iterations <= 10_000_000:
                return False
            value = hashlib.pbkdf2_hmac("sha256", password.encode(), salt, iterations, len(expected))
        else:
            return False
    except (KeyError, TypeError, ValueError):
        return False
    return hmac.compare_digest(value, expected)


def validate_password(password):
    if not isinstance(password, str) or not MIN_PASSWORD <= len(password) <= MAX_PASSWORD:
        raise Refused(400, f"Use at least {MIN_PASSWORD} characters")
    if password.lower() in COMMON or len(set(password)) < 3:
        raise Refused(400, "That password is too easy to guess. Try a few words together.")


def normalize_code(value):
    return "".join(ch for ch in str(value).upper() if ch.isalnum())


class Limiter:
    """Slows down guessing: after five misses from one address it waits 30 seconds, doubling up to 15 minutes.
    More than 60 misses in 10 minutes from everywhere together pauses password and setup-code sign-in for a minute."""

    def __init__(self, clock=time.time):
        self.clock = clock
        self.lock = threading.Lock()
        self.misses = {}
        self.recent = []

    def wait(self, key):
        now = self.clock()
        with self.lock:
            self.recent = [moment for moment in self.recent if now - moment < 600]
            failures, until = self.misses.get(key, (0, 0))
            wait = max(0, until - now)
            if len(self.recent) >= 60:
                wait = max(wait, 60 - (now - self.recent[-1]))
            return int(wait + .999)

    def miss(self, key):
        now = self.clock()
        with self.lock:
            failures, _ = self.misses.get(key, (0, 0))
            failures += 1
            until = now + min(900, 30 * 2 ** (failures - 5)) if failures >= 5 else 0
            self.misses[key] = (failures, until)
            self.recent.append(now)
            if len(self.misses) > 10_000:
                self.misses = {k: v for k, v in self.misses.items() if v[1] > now}

    def success(self, key):
        with self.lock:
            self.misses.pop(key, None)


class Auth:
    def __init__(self, connect, folder, token="", clock=time.time):
        self.connect = connect
        self.folder = Path(folder)
        self.token = token
        self.clock = clock
        self.limiter = Limiter(clock)

    # State

    def _get(self, db, key):
        row = db.execute("SELECT value FROM auth_state WHERE key=?", (key,)).fetchone()
        return json.loads(row[0]) if row else None

    def _set(self, db, key, value):
        if value is None:
            db.execute("DELETE FROM auth_state WHERE key=?", (key,))
        else:
            db.execute("INSERT OR REPLACE INTO auth_state VALUES (?, ?)", (key, json.dumps(value)))

    def has_owner(self, db):
        return self._get(db, "owner") is not None

    @staticmethod
    def migrate(db):
        columns = {row[1] for row in db.execute("PRAGMA table_info(auth_sessions)")}
        for name, kind in SESSION_FLAGS.items():
            if name not in columns:
                db.execute("ALTER TABLE auth_sessions ADD COLUMN %s %s" % (name, kind))

    # Privacy codes: the private folder passcode, the decoy password, and the emergency erase passcode

    _dummy = None

    @classmethod
    def dummy(cls):
        """A hash of nothing in particular, checked when a code isn't set, so every check costs the same."""
        if cls._dummy is None:
            cls._dummy = hash_password(secrets.token_hex(16))
        return cls._dummy

    def has_secret(self, db, name):
        return self._get(db, name) is not None

    def check_secret(self, db, name, code):
        """Whether code is this privacy code. Always does the full slow check, whether it's set or not."""
        stored = self._get(db, name)
        usable = isinstance(code, str) and 0 < len(code) <= MAX_CODE
        match = check_password(stored["hash"] if stored else self.dummy(), code if usable else "-")
        return bool(stored) and usable and match

    def set_secret(self, db, name, code):
        self._set(db, name, None if code is None else {"hash": hash_password(code), "created": int(self.clock() * 1000)})

    def refuse_reuse(self, db, code, *names):
        """Keeps the password and the privacy codes apart: none may be the same as another."""
        owner = self._get(db, "owner")
        for name in names:
            if name == "owner":
                same = owner is not None and check_password(owner["password"], code)
            else:
                same = self.has_secret(db, name) and self.check_secret(db, name, code)
            if same:
                raise Refused(400, "Use something different from " + CODE_NAMES[name] + ".")

    def guard(self, client):
        """Raises Refused(429) while this address waits after too many wrong passwords or codes."""
        self._guard(client)

    def missed(self, client):
        self.limiter.miss(client)

    def matched(self, client):
        self.limiter.success(client)

    # This computer: opening without a password

    def local_enabled(self, db):
        """On for new installs. Installs that already had a password before this existed keep asking for it, until
        the owner turns it on in Settings."""
        value = self._get(db, "local_access")
        return (not self.has_owner(db)) if value is None else bool(value.get("enabled"))

    def set_local(self, db, enabled):
        self._set(db, "local_access", {"enabled": bool(enabled)})

    def local_session(self, db, label=""):
        """A session for a request already checked to come from this computer."""
        if not self.local_enabled(db):
            raise Refused(403, "Sign in to open FluidMessages Web.")
        # Remembered, so a password made later for other devices doesn't take this computer's access away.
        self.set_local(db, True)
        return self._new_session(db, True, label or "This computer")

    @property
    def code_file(self):
        return self.folder / "setup-code"

    def setup_code(self, db):
        """The one-time setup code, made when there's no owner yet. Kept in a private file so the log and the file agree."""
        if self.has_owner(db):
            self.code_file.unlink(missing_ok=True)
            return None
        try:
            code = normalize_code(self.code_file.read_text())
            if len(code) == 12 and all(ch in CODE_ALPHABET for ch in code):
                return code
        except OSError:
            pass
        code = "".join(secrets.choice(CODE_ALPHABET) for _ in range(12))
        self.folder.mkdir(parents=True, exist_ok=True)
        temporary = self.code_file.with_suffix(".tmp")
        descriptor = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(descriptor, "w") as output:
            output.write(self.pretty(code) + "\n")
        os.replace(temporary, self.code_file)
        return code

    @staticmethod
    def pretty(code):
        return "-".join(code[index:index + 4] for index in range(0, len(code), 4))

    def reset_owner(self, db):
        """For a forgotten password: removes the password and every browser session, and makes a new setup code.
        The decoy password goes too, since it only works next to a password."""
        self._set(db, "owner", None)
        self._set(db, "decoy", None)
        db.execute("DELETE FROM auth_sessions")
        self.code_file.unlink(missing_ok=True)
        return self.setup_code(db)

    # Bearer tokens: FLUID_TOKEN and pairing tokens

    def check_token(self, db, value):
        if not isinstance(value, str) or not 32 <= len(value) <= 256:
            return False
        if self.token and hmac.compare_digest(value.encode(), self.token.encode()):
            return True
        pairing = self._get(db, "pairing")
        return bool(pairing) and hmac.compare_digest(_digest(value), pairing["hash"])

    def make_pairing(self, db):
        value = secrets.token_hex(32)
        self._set(db, "pairing", {"hash": _digest(value), "created": int(self.clock() * 1000)})
        return value

    def remove_pairing(self, db):
        self._set(db, "pairing", None)

    def pairing_status(self, db):
        pairing = self._get(db, "pairing")
        return {"compose": bool(self.token), "web": bool(pairing), "created": pairing["created"] if pairing else None}

    # Sessions

    def _new_session(self, db, remember, label="", decoy=False, value=None):
        value = value or secrets.token_urlsafe(32)
        now = int(self.clock())
        expires = now + (REMEMBER_SECONDS if remember else SHORT_SECONDS)
        db.execute("DELETE FROM auth_sessions WHERE expires<?", (now,))
        db.execute("INSERT OR REPLACE INTO auth_sessions (id, created, seen, expires, persistent, label, decoy, private_open) "
                   "VALUES (?, ?, ?, ?, ?, ?, ?, 0)", (_digest(value), now, now, expires, int(bool(remember)), label[:120], int(bool(decoy))))
        # At most 50 browsers signed in; the oldest drops off.
        db.execute("""DELETE FROM auth_sessions WHERE id NOT IN
                      (SELECT id FROM auth_sessions ORDER BY seen DESC LIMIT 50)""")
        return value, bool(remember)

    def session(self, db, value):
        """The session behind a cookie, or None. Use slides the expiry forward, at most once a minute."""
        found = self.session_info(db, value)
        return found["key"] if found else None

    def session_info(self, db, value):
        """The session's key and its privacy flags, {"key", "decoy", "private_open"}, or None."""
        if not isinstance(value, str) or not 20 <= len(value) <= 100:
            return None
        key = _digest(value)
        row = db.execute("SELECT seen, expires, persistent, decoy, private_open FROM auth_sessions WHERE id=?", (key,)).fetchone()
        now = int(self.clock())
        if row is None or row[1] < now:
            return None
        if now - row[0] >= 60:
            expires = now + (REMEMBER_SECONDS if row[2] else SHORT_SECONDS)
            db.execute("UPDATE auth_sessions SET seen=?, expires=? WHERE id=?", (now, expires, key))
        return {"key": key, "decoy": bool(row[3]), "private_open": bool(row[4])}

    def set_session_flags(self, db, key, **flags):
        for name, value in flags.items():
            if name in SESSION_FLAGS:
                db.execute("UPDATE auth_sessions SET %s=? WHERE id=?" % name, (int(bool(value)), key))

    def lock_private_everywhere(self, db):
        db.execute("UPDATE auth_sessions SET private_open=0")

    def restore_session(self, db, value, remember, label):
        """After an emergency erase emptied every session, puts this browser's back as a decoy session, so the page
        keeps going and shows a harmless inbox."""
        return self._new_session(db, remember, label, decoy=True, value=value)

    def end_session(self, db, value):
        if isinstance(value, str):
            db.execute("DELETE FROM auth_sessions WHERE id=?", (_digest(value),))

    def end_other_sessions(self, db, keep_key):
        return db.execute("DELETE FROM auth_sessions WHERE id IS NOT ?", (keep_key,)).rowcount

    def session_count(self, db):
        return db.execute("SELECT count(*) FROM auth_sessions WHERE expires>=?", (int(self.clock()),)).fetchone()[0]

    # Signing in

    def _guard(self, client):
        wait = self.limiter.wait(client)
        if wait:
            minutes = (wait + 59) // 60
            raise Refused(429, "Too many tries. Wait " + (f"{wait} seconds" if wait < 90 else f"{minutes} minutes") + " and try again.")

    def setup(self, db, client, code, password, remember, label=""):
        if self.has_owner(db):
            raise Refused(409, "FluidMessages Web already has a password. Sign in instead.")
        self._guard(client)
        code = code.strip() if isinstance(code, str) else ""
        expected = self.setup_code(db)
        given = normalize_code(code)
        good = (expected and len(given) == 12 and hmac.compare_digest(given, expected)) or self.check_token(db, code)
        if not good:
            self.limiter.miss(client)
            raise Refused(403, "That setup code isn't right. It's in the server's log and in its data folder (setup-code).")
        validate_password(password)
        self.refuse_reuse(db, password, "distress")
        self.limiter.success(client)
        self._set(db, "owner", {"password": hash_password(password), "created": int(self.clock() * 1000)})
        self.code_file.unlink(missing_ok=True)
        return self._new_session(db, remember, label)

    def login(self, db, client, password, remember, label=""):
        """Signs in with the password, the decoy password, or the emergency erase passcode. All three are checked
        every time, in the same order, so neither the answer nor its timing says which one matched. Returns the
        session value, whether it's remembered, and "real", "decoy", or "distress". A distress session isn't stored
        yet: the caller erases everything first, then restores it as a decoy session (restore_session)."""
        owner = self._get(db, "owner")
        if owner is None:
            raise Refused(409, "Set up FluidMessages Web first.")
        self._guard(client)
        usable = isinstance(password, str) and 0 < len(password) <= MAX_PASSWORD
        given = password if usable else "-"
        real = check_password(owner["password"], given) and usable
        decoy = self.check_secret(db, "decoy", given)
        distress = self.check_secret(db, "distress", given)
        if not (real or decoy or distress):
            self.limiter.miss(client)
            raise Refused(403, "That password isn't right.")
        self.limiter.success(client)
        if real or decoy:
            value, persistent = self._new_session(db, remember, label, decoy=not real)
            return value, persistent, "real" if real else "decoy"
        return secrets.token_urlsafe(32), bool(remember), "distress"

    def login_with_token(self, db, client, value, remember, label=""):
        self._guard(client)
        if not self.check_token(db, (value or "").strip() if isinstance(value, str) else ""):
            self.limiter.miss(client)
            raise Refused(403, "That access token isn't right.")
        self.limiter.success(client)
        return self._new_session(db, remember, label)

    def confirm_password(self, db, client, password):
        """Erase everything asks for the password again when there is one. Misses count like wrong sign-ins."""
        owner = self._get(db, "owner")
        if owner is None:
            return
        self._guard(client)
        if not isinstance(password, str) or len(password) > MAX_PASSWORD or not check_password(owner["password"], password):
            self.limiter.miss(client)
            raise Refused(403, "That password isn't right.")
        self.limiter.success(client)

    def change_password(self, db, client, current, password):
        owner = self._get(db, "owner")
        if owner is not None:
            self._guard(client)
            if not isinstance(current, str) or not check_password(owner["password"], current):
                self.limiter.miss(client)
                raise Refused(403, "Your current password isn't right.")
        validate_password(password)
        self.refuse_reuse(db, password, "decoy", "distress")
        self._set(db, "owner", {"password": hash_password(password), "created": int(self.clock() * 1000)})
        self.code_file.unlink(missing_ok=True)


# A request carrying any of these was passed along by a reverse proxy, load balancer, or tunnel, so whoever sent it
# may be anywhere. Such a request never counts as this computer, even when the proxy itself runs here.
PROXY_HEADERS = ("Forwarded", "X-Forwarded-For", "X-Forwarded-Host", "X-Forwarded-Proto", "X-Forwarded-Port",
                 "X-Forwarded-Server", "X-Forwarded-Prefix", "X-Real-IP", "X-Client-IP", "X-Cluster-Client-IP",
                 "True-Client-IP", "CF-Connecting-IP", "Fastly-Client-IP", "X-ProxyUser-Ip", "Via")


def loopback_name(netloc):
    """localhost, a name under .localhost, 127.x.y.z, or [::1], with or without a port. Browsers resolve these
    names only to this computer, so a page from another site can't reach the server under them (DNS rebinding)."""
    host = (netloc or "").strip().lower()
    if host.startswith("["):
        host = host[1:host.find("]")] if "]" in host else ""
    elif host.count(":") == 1:
        host = host.split(":", 1)[0]
    host = host.rstrip(".")
    if host == "localhost" or host.endswith(".localhost"):
        return True
    try:
        return ipaddress.ip_address(host).is_loopback
    except ValueError:
        return False


# Where a container's gateway can be: private IPv4 ranges (Docker, Podman, Docker Desktop), link-local, and IPv6
# unique-local and link-local. Python's is_private also covers documentation ranges, so they're listed exactly.
CONTAINER_NETWORKS = tuple(ipaddress.ip_network(value) for value in (
    "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "169.254.0.0/16", "fc00::/7", "fe80::/10"))


def local_peer(peer, local_only=False):
    """The connection comes from this computer. With local_only (Docker's 127.0.0.1-only published port), the
    container sees the Docker network's gateway or another private address instead of loopback, so those count."""
    try:
        address = ipaddress.ip_address(str(peer).split("%", 1)[0])
    except ValueError:
        return False
    if address.version == 6 and address.ipv4_mapped:
        address = address.ipv4_mapped
    if address.is_loopback:
        return True
    return bool(local_only) and any(address in network for network in CONTAINER_NETWORKS)


def from_this_computer(peer, headers, local_only=False, need_origin=False):
    """Every condition must hold: a local connection, no proxy headers, a localhost Host (which also blocks DNS
    rebinding), and, when the browser says where the request came from, the same localhost origin."""
    if not local_peer(peer, local_only):
        return False
    if any(headers.get(name) is not None for name in PROXY_HEADERS):
        return False
    host = (headers.get("Host") or "").strip().lower()
    if not loopback_name(host):
        return False
    origin = headers.get("Origin")
    if origin is None:
        return not need_origin
    scheme, _, rest = origin.strip().lower().partition("://")
    return scheme in ("http", "https") and rest.rstrip("/") == host


def client_key(peer, forwarded):
    """Who's knocking, for rate limits. Behind a reverse proxy on this host or network, the proxy's last hop."""
    try:
        address = ipaddress.ip_address(peer)
    except ValueError:
        return peer
    if (address.is_loopback or address.is_private) and forwarded:
        last = forwarded.split(",")[-1].strip()
        try:
            return str(ipaddress.ip_address(last))
        except ValueError:
            return peer
    return str(address)
