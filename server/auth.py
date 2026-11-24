"""Signing in to FluidMessages Web without typing an access token.

- The owner creates a password on first visit. Only a salted scrypt hash is stored (PBKDF2-SHA256 where
  scrypt isn't available), never the password.
- First-run setup needs the one-time setup code the server prints in its log and keeps in its data folder, or the
  server's access token (FLUID_TOKEN, or a pairing token). A stranger who finds the page can't claim it.
- Browsers get a random session cookie: HttpOnly, SameSite=Strict, and Secure over HTTPS. The server keeps only
  a SHA-256 of each session, so a copy of the database can't be used to sign in.
- Wrong passwords and setup codes are rate-limited per address and overall.
- Phones, desktop apps, and scripts keep using a bearer token: FLUID_TOKEN from the server's settings, or a pairing
  token made in the web app's settings (stored only as a hash).
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
        """For a forgotten password: removes the password and every browser session, and makes a new setup code."""
        self._set(db, "owner", None)
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

    def _new_session(self, db, remember, label=""):
        value = secrets.token_urlsafe(32)
        now = int(self.clock())
        expires = now + (REMEMBER_SECONDS if remember else SHORT_SECONDS)
        db.execute("DELETE FROM auth_sessions WHERE expires<?", (now,))
        db.execute("INSERT INTO auth_sessions VALUES (?, ?, ?, ?, ?, ?)",
                   (_digest(value), now, now, expires, int(bool(remember)), label[:120]))
        # At most 50 browsers signed in; the oldest drops off.
        db.execute("""DELETE FROM auth_sessions WHERE id NOT IN
                      (SELECT id FROM auth_sessions ORDER BY seen DESC LIMIT 50)""")
        return value, bool(remember)

    def session(self, db, value):
        """The session behind a cookie, or None. Use slides the expiry forward, at most once a minute."""
        if not isinstance(value, str) or not 20 <= len(value) <= 100:
            return None
        key = _digest(value)
        row = db.execute("SELECT seen, expires, persistent FROM auth_sessions WHERE id=?", (key,)).fetchone()
        now = int(self.clock())
        if row is None or row[1] < now:
            return None
        if now - row[0] >= 60:
            expires = now + (REMEMBER_SECONDS if row[2] else SHORT_SECONDS)
            db.execute("UPDATE auth_sessions SET seen=?, expires=? WHERE id=?", (now, expires, key))
        return key

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
        self.limiter.success(client)
        self._set(db, "owner", {"password": hash_password(password), "created": int(self.clock() * 1000)})
        self.code_file.unlink(missing_ok=True)
        return self._new_session(db, remember, label)

    def login(self, db, client, password, remember, label=""):
        owner = self._get(db, "owner")
        if owner is None:
            raise Refused(409, "Set up FluidMessages Web first.")
        self._guard(client)
        if not isinstance(password, str) or len(password) > MAX_PASSWORD or not check_password(owner["password"], password):
            self.limiter.miss(client)
            raise Refused(403, "That password isn't right.")
        self.limiter.success(client)
        return self._new_session(db, remember, label)

    def login_with_token(self, db, client, value, remember, label=""):
        self._guard(client)
        if not self.check_token(db, (value or "").strip() if isinstance(value, str) else ""):
            self.limiter.miss(client)
            raise Refused(403, "That access token isn't right.")
        self.limiter.success(client)
        return self._new_session(db, remember, label)

    def change_password(self, db, client, current, password):
        owner = self._get(db, "owner")
        if owner is not None:
            self._guard(client)
            if not isinstance(current, str) or not check_password(owner["password"], current):
                self.limiter.miss(client)
                raise Refused(403, "Your current password isn't right.")
        validate_password(password)
        self._set(db, "owner", {"password": hash_password(password), "created": int(self.clock() * 1000)})
        self.code_file.unlink(missing_ok=True)


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
