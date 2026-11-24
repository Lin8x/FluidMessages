"""Internet protocol accounts for the companion.

Accounts come from three places, in this order of precedence:
1. Docker Compose environment variables (FLUID_EMAIL_*, FLUID_MATRIX_*, FLUID_XMPP_*, FLUID_IRC_*). These are
   read-only in the phone app and show as "Set in Docker Compose".
2. Accounts added from the phone, stored in a private JSON file in the data volume.
3. The older FLUID_PROTOCOL_CONFIG file, still read for existing setups.

Several accounts per service are allowed (two Matrix homeservers, a work and a personal mailbox, several IRC
networks). Each has a stable id made from its service and public address, such as "matrix:@alice:example.org".
The file that held one account per service ({"matrix": {...}}) is still read as is; the first save rewrites it as
{"version": 2, "accounts": [...]}, keeping those accounts first and with the same ids.

Secrets never leave the companion: the API reports only whether each secret is set.
"""
import json
import hashlib
import os
from pathlib import Path
import re
import threading

PROTOCOLS = ("email", "matrix", "xmpp", "irc")
HOST = re.compile(r"[A-Za-z0-9](?:[A-Za-z0-9.-]{0,251}[A-Za-z0-9])?")
EMAIL = re.compile(r"[^@\s<>\x00-\x1f]{1,64}@[A-Za-z0-9](?:[A-Za-z0-9.-]{0,251}[A-Za-z0-9])?")
JID = re.compile(r"[^@/\s<>'\"&]{1,1023}@[A-Za-z0-9](?:[A-Za-z0-9.-]{0,251}[A-Za-z0-9])?")
MXID = re.compile(r"@[a-z0-9._=\-/+]{1,255}:[A-Za-z0-9.:-]{1,255}")
NICK = re.compile(r"[A-Za-z\[\]\\`_^{|}][A-Za-z0-9\[\]\\`_^{|}-]{0,31}")
CHANNEL = re.compile(r"[#&][^\s,\x07\x00-\x1f]{1,49}")

# Field: (type, secret, required)
FIELDS = {
    "email": {"address": (str, False, True), "imap_host": (str, False, True), "imap_port": (int, False, False),
              "imap_user": (str, False, False), "imap_password": (str, True, True), "smtp_host": (str, False, True),
              "smtp_port": (int, False, False), "smtp_user": (str, False, False), "smtp_password": (str, True, False),
              "folder": (str, False, False), "subject": (str, False, False), "poll_minutes": (int, False, False)},
    "matrix": {"homeserver": (str, False, True), "user_id": (str, False, True), "access_token": (str, True, True)},
    "xmpp": {"jid": (str, False, True), "password": (str, True, True), "host": (str, False, False), "port": (int, False, False)},
    "irc": {"server": (str, False, True), "port": (int, False, False), "nick": (str, False, True),
            "sasl_user": (str, False, False), "sasl_password": (str, True, False), "channels": (list, False, False),
            "real_name": (str, False, False)},
}
ENV = {
    "email": {"address": "FLUID_EMAIL_ADDRESS", "imap_host": "FLUID_EMAIL_IMAP_HOST", "imap_port": "FLUID_EMAIL_IMAP_PORT",
              "imap_user": "FLUID_EMAIL_USER", "imap_password": "FLUID_EMAIL_PASSWORD", "smtp_host": "FLUID_EMAIL_SMTP_HOST",
              "smtp_port": "FLUID_EMAIL_SMTP_PORT", "smtp_user": "FLUID_EMAIL_SMTP_USER",
              "smtp_password": "FLUID_EMAIL_SMTP_PASSWORD", "folder": "FLUID_EMAIL_FOLDER",
              "poll_minutes": "FLUID_EMAIL_POLL_MINUTES"},
    "matrix": {"homeserver": "FLUID_MATRIX_HOMESERVER", "user_id": "FLUID_MATRIX_USER", "access_token": "FLUID_MATRIX_TOKEN"},
    "xmpp": {"jid": "FLUID_XMPP_JID", "password": "FLUID_XMPP_PASSWORD", "host": "FLUID_XMPP_HOST", "port": "FLUID_XMPP_PORT"},
    "irc": {"server": "FLUID_IRC_SERVER", "port": "FLUID_IRC_PORT", "nick": "FLUID_IRC_NICK", "sasl_user": "FLUID_IRC_SASL_USER",
            "sasl_password": "FLUID_IRC_SASL_PASSWORD", "channels": "FLUID_IRC_CHANNELS", "real_name": "FLUID_IRC_REAL_NAME"},
}


def validate(protocol, values):
    """Returns a clean account or raises ValueError with a message the phone can show."""
    if protocol not in FIELDS or not isinstance(values, dict):
        raise ValueError("Unknown protocol")
    clean = {}
    for name, (kind, _, required) in FIELDS[protocol].items():
        value = values.get(name)
        if value in (None, "", []):
            if required:
                raise ValueError("Enter " + name.replace("_", " "))
            continue
        if kind is int:
            value = int(value)
            if not 1 <= value <= 65535 and name.endswith("port") or name == "poll_minutes" and not 1 <= value <= 1440:
                raise ValueError("Enter a valid " + name.replace("_", " "))
        elif kind is list:
            if isinstance(value, str):
                value = [item.strip() for item in value.split(",") if item.strip()]
            if not isinstance(value, list) or len(value) > 50 or any(not isinstance(item, str) for item in value):
                raise ValueError("Enter up to 50 channels")
        elif not isinstance(value, str) or len(value) > 2048 or "\n" in value or "\r" in value:
            raise ValueError("Invalid " + name.replace("_", " "))
        clean[name] = value
    if protocol == "email":
        if not EMAIL.fullmatch(clean["address"]) or not HOST.fullmatch(clean["imap_host"]) or not HOST.fullmatch(clean["smtp_host"]):
            raise ValueError("Enter an email address and IMAP and SMTP host names")
        clean.setdefault("smtp_password", clean["imap_password"])
    elif protocol == "matrix":
        clean["homeserver"] = clean["homeserver"].rstrip("/")
        if not re.fullmatch(r"https://[A-Za-z0-9.-]+(:\d{1,5})?", clean["homeserver"]) or not MXID.fullmatch(clean["user_id"]):
            raise ValueError("Enter an https:// homeserver and a Matrix ID like @you:example.org")
    elif protocol == "xmpp":
        if not JID.fullmatch(clean["jid"]) or "host" in clean and not HOST.fullmatch(clean["host"]):
            raise ValueError("Enter an XMPP address like you@example.org")
    elif protocol == "irc":
        if not HOST.fullmatch(clean["server"]) or not NICK.fullmatch(clean["nick"]):
            raise ValueError("Enter an IRC server name and a nickname")
        if any(not CHANNEL.fullmatch(channel) for channel in clean.get("channels", [])):
            raise ValueError("Channels start with # or &")
        # Plain-text IRC would expose your messages and password, so only TLS ports are accepted.
        clean.setdefault("port", 6697)
        if clean["port"] in (6667, 194):
            raise ValueError("Use a TLS port such as 6697. FluidMessages doesn’t connect to IRC without TLS.")
    return clean


def identity(protocol, values):
    """An account's public address: what the person sees and what the phone and the web compare. Never a secret."""
    pick = lambda name: values.get(name) if isinstance(values.get(name), str) else ""
    if protocol == "matrix":
        return pick("user_id")
    if protocol == "xmpp":
        return pick("jid").lower()
    if protocol == "email":
        return pick("address").lower()
    if protocol == "irc":
        return (pick("nick") + "@" + pick("server").lower()) if pick("nick") else ""
    return ""


def account_id(protocol, values):
    return protocol + ":" + identity(protocol, values)


def tag(account):
    """A short, stable suffix for an account, used in the thread keys and state of every account but the first."""
    return hashlib.sha256(account.encode()).hexdigest()[:8]


class AccountStore:
    def __init__(self, path, legacy=None, environ=None):
        self.path = Path(path)
        self.legacy = legacy or {}
        self.environ = os.environ if environ is None else environ
        self.lock = threading.Lock()
        self.generation = 0
        self.errors = {}

    def environment(self):
        """One read-only account per service from Docker Compose, as before."""
        result, self.errors = {}, {}
        for protocol, names in ENV.items():
            values = {field: self.environ[variable] for field, variable in names.items() if self.environ.get(variable)}
            if values:
                try:
                    result[protocol] = validate(protocol, values)
                except ValueError as error:
                    # An incomplete Compose account is reported instead of stopping the companion.
                    self.errors[protocol] = "Docker Compose: " + str(error)
        return result

    def saved_list(self):
        """Accounts added from the phone or this web app, oldest first: [{"id", "protocol", "values"}]."""
        if not self.path.is_file():
            return []
        data = json.loads(self.path.read_text())
        if not isinstance(data, dict):
            return []
        if data.get("version") == 2:
            entries = data.get("accounts") if isinstance(data.get("accounts"), list) else []
            return [entry for entry in entries if isinstance(entry, dict) and entry.get("protocol") in PROTOCOLS
                    and isinstance(entry.get("values"), dict) and isinstance(entry.get("id"), str)]
        # The one-account-per-service file from before: same accounts, in the same order, with stable ids.
        return [{"id": account_id(protocol, values), "protocol": protocol, "values": values}
                for protocol, values in data.items() if protocol in PROTOCOLS and isinstance(values, dict)]

    def saved(self):
        """The first saved account of each service, the shape older code reads."""
        result = {}
        for entry in self.saved_list():
            result.setdefault(entry["protocol"], entry["values"])
        return result

    def all(self):
        """Every effective account, in order: Compose, then saved ones, then the old config file's when nothing else
        covers that service. The first account of a service is its primary. Each: {id, protocol, source, values}."""
        output, seen = [], set()

        def add(protocol, values, source, ident=None):
            ident = ident or account_id(protocol, values)
            if ident not in seen:
                seen.add(ident)
                output.append({"id": ident, "protocol": protocol, "source": source, "values": dict(values)})
        for protocol, values in self.environment().items():
            add(protocol, values, "compose")
        for entry in self.saved_list():
            add(entry["protocol"], entry["values"], "phone", entry["id"])
        covered = {entry["protocol"] for entry in output}
        for protocol in PROTOCOLS:
            if protocol not in covered and isinstance(self.legacy.get(protocol), dict):
                add(protocol, self.legacy[protocol], "file")
        return output

    def current(self):
        """The primary account of each service: {protocol: values}."""
        result = {}
        for entry in self.all():
            result.setdefault(entry["protocol"], entry["values"])
        return result

    def get(self, ident):
        return next((entry["values"] for entry in self.all() if entry["id"] == ident), None)

    def of(self, protocol):
        return [entry for entry in self.all() if entry["protocol"] == protocol]

    def primary(self, protocol):
        return next((entry["id"] for entry in self.all() if entry["protocol"] == protocol), "")

    def sources(self):
        """Where each service's primary account comes from, as before."""
        result = {}
        for entry in self.all():
            result.setdefault(entry["protocol"], entry["source"])
        return result

    def redacted(self):
        output, primaries = [], set()
        for entry in self.all():
            protocol, values = entry["protocol"], entry["values"]
            fields = {}
            for name, (_, secret, _) in FIELDS[protocol].items():
                if name in values:
                    fields[name] = {"set": True} if secret else values[name]
            primary = protocol not in primaries
            primaries.add(protocol)
            output.append({"protocol": protocol, "source": entry["source"], "fields": fields, "id": entry["id"],
                           "name": identity(protocol, values), "primary": primary})
        covered = {entry["protocol"] for entry in output}
        for protocol, error in self.errors.items():
            if protocol not in covered:
                output.append({"protocol": protocol, "source": "compose", "fields": {}, "error": error})
        return output

    def save(self, protocol, values, ident=None, add=False):
        """Saves an account. [ident] edits that account; [add] adds another; neither (older phones) replaces the
        service's first saved account, the way the one-account store worked."""
        if protocol not in FIELDS:
            raise ValueError("Unknown protocol")
        environment = self.environment()
        if ident and any(entry["id"] == ident and entry["source"] == "compose" for entry in self.all()) \
                or not ident and not add and protocol in environment:
            raise ValueError("This account is set in Docker Compose. Change it there.")
        with self.lock:
            saved = self.saved_list()
            if ident:
                index = next((i for i, entry in enumerate(saved) if entry["id"] == ident and entry["protocol"] == protocol), None)
                if index is None:
                    raise ValueError("That account was removed. Add it again.")
            elif add:
                index = None
            else:
                index = next((i for i, entry in enumerate(saved) if entry["protocol"] == protocol), None)
            previous = saved[index]["values"] if index is not None else {}
            # Leaving a password blank keeps the one already saved.
            for name, (_, secret, _) in FIELDS[protocol].items():
                if secret and not values.get(name) and previous.get(name):
                    values[name] = previous[name]
            clean = validate(protocol, values)
            new_id = account_id(protocol, clean)
            others = [entry for i, entry in enumerate(saved) if i != index]
            if any(entry["id"] == new_id or account_id(entry["protocol"], entry["values"]) == new_id for entry in others) \
                    or (index is None and new_id in {account_id(p, v) for p, v in environment.items()}):
                raise ValueError("That account is already added.")
            if index is None:
                saved.append({"id": new_id, "protocol": protocol, "values": clean})
            else:
                # Editing keeps the id, so chats stay with the account even if its details change.
                saved[index] = {"id": saved[index]["id"], "protocol": protocol, "values": clean}
            self.write({"version": 2, "accounts": saved})
        return clean

    def delete(self, protocol, ident=None):
        if ident and any(entry["id"] == ident and entry["source"] == "compose" for entry in self.all()) \
                or not ident and protocol in self.environment():
            raise ValueError("This account is set in Docker Compose. Remove it there.")
        with self.lock:
            saved = self.saved_list()
            if ident:
                kept = [entry for entry in saved if entry["id"] != ident]
            else:
                first = next((entry for entry in saved if entry["protocol"] == protocol), None)
                kept = [entry for entry in saved if entry is not first]
            self.write({"version": 2, "accounts": kept})

    def write(self, data):
        self.path.parent.mkdir(parents=True, exist_ok=True)
        temporary = self.path.with_suffix(".tmp")
        descriptor = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(descriptor, "w") as output:
            json.dump(data, output)
        os.replace(temporary, self.path)
        self.generation += 1
