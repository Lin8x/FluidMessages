"""Opt-in Internet messaging for configured correspondents and Matrix rooms."""
from contextlib import closing
from email import policy
from email.message import EmailMessage
from email.parser import BytesParser
from email.utils import make_msgid, parsedate_to_datetime, parseaddr
import hashlib
import imaplib
import json
import os
from pathlib import Path
import re
import smtplib
import ssl
import threading
import time
from urllib.error import HTTPError
from urllib.parse import quote, urlencode, urlsplit
from urllib.request import HTTPRedirectHandler, HTTPSHandler, Request, build_opener

from accounts import AccountStore, CHANNEL, JID, MXID, NICK
import irc_client
import matrix_e2ee
import xmpp_client

INTERNET = ("matrix", "xmpp", "email", "irc")
MATRIX_ROUTES = ("matrix", "xmpp", "irc")
MAX_MAIL = 128 * 1024
MAX_FILE = 25 * 1024 * 1024
# Whole messages up to this size are fetched, so a 25 MB attachment still arrives after base64 encoding.
MAX_MAIL_WITH_FILES = 35 * 1024 * 1024
KINDS = ("photo", "video", "audio", "voice", "file", "contact")
MIME = re.compile(r"[a-z0-9][a-z0-9!#$&^_.+-]{0,62}/[a-z0-9][a-z0-9!#$&^_.+-]{0,62}")
MAX_MATRIX = 5 * 1024 * 1024
EMAIL = re.compile(r"[^@\s<>\x00-\x1f]{1,64}@[A-Za-z0-9](?:[A-Za-z0-9.-]{0,251}[A-Za-z0-9])?")
NUMBER = re.compile(r"\+?[0-9][0-9(). -]{2,38}")
ROOM = re.compile(r"![^\s/\x00-\x1f]{1,250}")
ID = re.compile(r"[a-zA-Z0-9_-]{1,90}")
# Mirrors SmsReaction on Android so folded reactions read the same on every protocol.
REACTION_VERBS = {"👍": "Liked", "❤️": "Loved", "👎": "Disliked", "😆": "Laughed at", "‼️": "Emphasized", "❓": "Questioned"}


def excerpt(text, limit):
    return text if len(text) <= limit else text[:limit] + "…"


def reaction_text(emoji, quote):
    verb = REACTION_VERBS.get(emoji)
    quoted = "“" + excerpt(quote, 700) + "”"
    return f"{verb} {quoted}" if verb else f"Reacted {emoji} to {quoted}"


def reply_text(quote, body):
    return "Reply to “" + excerpt(quote, 240) + "”\n\n" + body


def is_emoji(value):
    return isinstance(value, str) and 0 < len(value) <= 64 and not re.search(r"[\sA-Za-z0-9<>]", value)

SCHEMA = """
CREATE TABLE IF NOT EXISTS protocol_messages (id TEXT PRIMARY KEY, date INTEGER NOT NULL, payload TEXT NOT NULL);
CREATE INDEX IF NOT EXISTS protocol_message_date ON protocol_messages (date DESC);
CREATE TABLE IF NOT EXISTS protocol_jobs (id TEXT PRIMARY KEY, format TEXT NOT NULL, target TEXT NOT NULL,
    date INTEGER NOT NULL, state TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS protocol_state (key TEXT PRIMARY KEY, value TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS protocol_routes (address TEXT NOT NULL, protocol TEXT NOT NULL, payload TEXT NOT NULL,
    PRIMARY KEY (address, protocol));
CREATE TABLE IF NOT EXISTS protocol_uploads (id TEXT PRIMARY KEY, name TEXT NOT NULL, mime TEXT NOT NULL,
    size INTEGER NOT NULL, date INTEGER NOT NULL);
"""


def safe_name(name):
    name = re.sub(r"[\\/:*?\"<>|\x00-\x1f]", "_", name if isinstance(name, str) else "").strip()
    return (name or "attachment")[-120:]


def kind_for(mime):
    if mime.startswith("image/"):
        return "photo"
    if mime.startswith("video/"):
        return "video"
    if mime.startswith("audio/"):
        return "audio"
    return "contact" if mime in ("text/vcard", "text/x-vcard") else "file"



class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def bounded(value, limit):
    return value[:limit] if isinstance(value, str) else ""


def configured():
    path = os.environ.get("FLUID_PROTOCOL_CONFIG")
    if not path:
        return {}
    file = Path(path)
    if file.stat().st_size > 128 * 1024:
        raise ValueError("Protocol configuration is too large")
    result = json.loads(file.read_text())
    if not isinstance(result, dict):
        raise ValueError("Expected a protocol configuration object")
    return result


class Gateway:
    def __init__(self, connect, config, files=None, accounts=None):
        self.connect = connect
        # Attachments live beside the database, one file per message id; uploads wait in a staging folder.
        self.files = Path(files or os.environ.get("FLUID_FILES", "data/protocol-files"))
        (self.files / "uploads").mkdir(parents=True, exist_ok=True)
        self.accounts = accounts or AccountStore(self.files / "accounts.json", legacy=config, environ={})
        self.tls = ssl.create_default_context()
        self.http = build_opener(HTTPSHandler(context=self.tls), NoRedirect())
        self.wake = threading.Event()
        self.clients = {}
        self.legacy_routes = self.validate_routes(config.get("routes") or [])

    @property
    def email(self):
        return self.accounts.current().get("email") or {}

    @property
    def matrix(self):
        return self.accounts.current().get("matrix") or {}

    @property
    def routes(self):
        """Links from the old config file plus the ones added from the phone, stored in the database."""
        with closing(self.connect()) as db:
            rows = db.execute("SELECT payload FROM protocol_routes ORDER BY rowid").fetchall()
        routes = self.legacy_routes + [json.loads(row[0]) for row in rows]
        # Channels listed on the IRC account appear in the inbox without a separate link.
        linked = {r["target"].lower() for r in routes if r["protocol"] == "irc"}
        for channel in (self.accounts.current().get("irc") or {}).get("channels", []):
            if channel.lower() not in linked:
                routes.append({"address": "irc-" + hashlib.sha256(channel.lower().encode()).hexdigest()[:16],
                               "protocol": "irc", "target": channel, "via": "irc", "group": True, "title": channel,
                               "members": []})
        return routes

    def validate_routes(self, routes):
        if not isinstance(routes, list) or len(routes) > 300:
            raise ValueError("Configure up to 300 protocol routes")
        output = []
        for route in routes:
            output.append(self.validate_route(route, output))
        return output

    def validate_route(self, route, existing):
        """Checks one link between a phone conversation and an internet address.

        Targets: an email address; a Matrix room (!room) or person (@user:server); an XMPP address or group
        (room@conference.server); an IRC #channel or nick. A Matrix room for XMPP or IRC means a bridge.
        """
        format, address, target = route.get("protocol"), route.get("address"), route.get("target")
        if (not isinstance(address, str) or not (ID.fullmatch(address) or NUMBER.fullmatch(address))
                or not isinstance(target, str) or any(r["address"] == address and r["protocol"] == format for r in existing)):
            raise ValueError("Invalid or duplicate protocol route")
        via = format
        if format == "email":
            if not EMAIL.fullmatch(target):
                raise ValueError("Enter one email address for this contact")
            target = target.lower()
        elif format == "matrix" or format in ("xmpp", "irc") and target.startswith("!"):
            if not ROOM.fullmatch(target) and not MXID.fullmatch(target):
                raise ValueError("Enter a Matrix ID like @maya:example.org or a room ID")
            via = "matrix"
        elif format == "xmpp":
            if not JID.fullmatch(target):
                raise ValueError("Enter an XMPP address like maya@example.org")
            target = target.lower()
        elif format == "irc":
            if not (CHANNEL.fullmatch(target) or NICK.fullmatch(target)):
                raise ValueError("Enter an IRC #channel or nickname")
        else:
            raise ValueError("Unknown internet format")
        if any(r.get("via", r["protocol"]) == via and r["target"].lower() == target.lower() for r in existing):
            raise ValueError("Each address or room can belong to one conversation")
        members = route.get("members", [])
        if not isinstance(members, list) or len(members) > 100:
            raise ValueError("Invalid route member list")
        group = bool(route.get("group")) or format == "irc" and target[:1] in "#&"
        return {"address": address, "protocol": format, "target": target, "via": via, "group": group,
                "title": bounded(route.get("title"), 120) or address,
                "members": [bounded(name, 120) for name in members if isinstance(name, str)]}

    def add_route(self, db, data):
        route = self.validate_route(data, self.routes)
        if route["via"] == "matrix" and route["target"].startswith("@"):
            route["target"] = self.matrix_direct_room(route["target"])
        db.execute("INSERT INTO protocol_routes (address, protocol, payload) VALUES (?, ?, ?)",
                   (route["address"], route["protocol"], json.dumps(route)))
        self.refresh_clients()
        return {"route": {key: value for key, value in route.items() if key != "via"}}

    def delete_route(self, db, data):
        if any(r["address"] == data.get("address") and r["protocol"] == data.get("protocol") for r in self.legacy_routes):
            raise ValueError("This link is set in the companion’s configuration file")
        db.execute("DELETE FROM protocol_routes WHERE address=? AND protocol=?", (data.get("address"), data.get("protocol")))
        self.refresh_clients()
        return {"deleted": True}

    @staticmethod
    def time():
        return int(time.time() * 1000)

    def enabled(self, db):
        row = db.execute("SELECT value FROM protocol_state WHERE key='enabled'").fetchone()
        return set(json.loads(row[0])) if row else set(INTERNET)

    def snapshot(self, db, enabled=None):
        if enabled is not None:
            if not isinstance(enabled, list) or any(not isinstance(item, str) for item in enabled):
                raise ValueError("Invalid protocol settings")
            db.execute("INSERT OR REPLACE INTO protocol_state VALUES ('enabled', ?) ", (json.dumps(enabled),))
        rows = db.execute("SELECT payload FROM protocol_messages ORDER BY date DESC LIMIT 1500")
        messages, size = [], 0
        for row in rows:
            size += len(row[0].encode())
            if size > 5 * 1024 * 1024:
                break
            messages.append(json.loads(row[0]))
        channels = {}
        for key, value in db.execute("SELECT key, value FROM protocol_state WHERE key LIKE 'channel:%'"):
            channels[key.removeprefix("channel:")] = json.loads(value)
        secure = self.clients.get("matrix", (None, None))[1]
        def shown(route):
            item = {key: value for key, value in route.items() if key != "via"}
            if route.get("via") == "matrix" and secure is not None:
                item["encrypted"] = secure.encrypted(route["target"])
            return item
        return {"messages": messages, "routes": [shown(route) for route in self.routes], "enabled": sorted(self.enabled(db)), "channels": channels,
                "accounts": [{"protocol": account["protocol"], "source": account["source"]}
                             for account in self.accounts.redacted()]}

    def route(self, address, protocol):
        return next((route for route in self.routes if route["address"] == address and route["protocol"] == protocol), None)

    def validate_send(self, db, data):
        address, format, body, request = (data[name] for name in ("address", "protocol", "body", "request"))
        route = self.route(address, format)
        if route is None:
            raise ValueError("This contact has no configured route for that protocol")
        if format not in self.enabled(db):
            raise ValueError("This protocol is turned off on the phone")
        if not isinstance(body, str) or not body.strip() or len(body) > 1600:
            raise ValueError("Enter 1–1,600 characters")
        if not isinstance(request, str) or not ID.fullmatch(request):
            raise ValueError("Invalid message request")
        return route, body, "gateway-" + request

    def relation(self, db, data, route):
        """Validates an optional reply, reaction, or forward and resolves its target in this conversation."""
        value = data.get("relation")
        text = data.get("text", "")
        if not isinstance(text, str) or len(text) > 1600:
            raise ValueError("Enter 1–1,600 characters")
        if value is None:
            return None, text
        kind = value.get("kind") if isinstance(value, dict) else None
        target = value.get("target", "") if isinstance(value, dict) else None
        if kind not in ("reply", "reaction", "forward") or not isinstance(target, str) or len(target) > 120:
            raise ValueError("Invalid message relation")
        emoji = value.get("emoji", "")
        if kind == "reaction" and not is_emoji(emoji):
            raise ValueError("Choose one emoji")
        found = None
        if target:
            row = db.execute("SELECT payload FROM protocol_messages WHERE id=?", (target,)).fetchone()
            item = json.loads(row[0]) if row else None
            if item and item["address"] == route["address"] and item["protocol"] == route["protocol"]:
                found = item
        result = {"kind": kind, "target": target if found else "", "emoji": emoji if kind == "reaction" else ""}
        for key in ("event_id", "mail_id", "mail_subject", "xmpp_id", "irc_msgid", "author"):
            if found and isinstance(found.get(key), str):
                result[key] = found[key]
        return result, text

    def file_path(self, message_id):
        if not isinstance(message_id, str) or not re.fullmatch(r"[A-Za-z0-9_-]{1,120}", message_id):
            raise ValueError("Invalid message")
        return self.files / message_id

    def store_upload(self, db, stream, size, mime, name):
        """Saves a file the phone sends before the message that uses it. Unused uploads expire after a day."""
        if not 0 < size <= MAX_FILE:
            raise ValueError("Files can be up to 25 MB")
        mime = (mime or "").split(";")[0].strip().lower()
        if not MIME.fullmatch(mime):
            mime = "application/octet-stream"
        upload = hashlib.sha256(os.urandom(32)).hexdigest()[:40]
        path = self.files / "uploads" / upload
        remaining = size
        with open(path, "wb") as output:
            while remaining:
                chunk = stream.read(min(remaining, 1 << 16))
                if not chunk:
                    path.unlink(missing_ok=True)
                    raise ValueError("The upload was incomplete")
                output.write(chunk)
                remaining -= len(chunk)
        cutoff = self.time() - 86_400_000
        for (old,) in db.execute("SELECT id FROM protocol_uploads WHERE date<?", (cutoff,)).fetchall():
            (self.files / "uploads" / old).unlink(missing_ok=True)
        db.execute("DELETE FROM protocol_uploads WHERE date<?", (cutoff,))
        db.execute("INSERT INTO protocol_uploads VALUES (?, ?, ?, ?, ?)", (upload, safe_name(name), mime, size, self.time()))
        return {"upload": upload}

    def claim_upload(self, db, value):
        if value is None:
            return None
        if not isinstance(value, dict) or not isinstance(value.get("upload"), str):
            raise ValueError("Invalid attachment")
        row = db.execute("SELECT name, mime, size FROM protocol_uploads WHERE id=?", (value["upload"],)).fetchone()
        if row is None or not (self.files / "uploads" / value["upload"]).is_file():
            raise ValueError("Upload the attachment again")
        kind = value.get("kind") if value.get("kind") in KINDS else kind_for(row[1])
        duration = value.get("duration") if type(value.get("duration")) is int and 0 <= value["duration"] < 86_400_000 else 0
        return {"upload": value["upload"], "kind": kind, "name": row[0], "mime": row[1], "size": row[2], "duration": duration}

    def find(self, address, protocol, key, value):
        """Most recent message in a conversation whose payload field matches, for threading and relations."""
        if not isinstance(value, str) or not value:
            return None
        with closing(self.connect()) as db:
            for row in db.execute("SELECT payload FROM protocol_messages ORDER BY date DESC LIMIT 1500"):
                item = json.loads(row[0])
                if item["address"] == address and item["protocol"] == protocol and item.get(key) == value:
                    return item
        return None

    def enqueue(self, db, data):
        route, body, message_id = self.validate_send(db, data)
        relation, text = self.relation(db, data, route)
        attachment = self.claim_upload(db, data.get("attachment"))
        row = db.execute("SELECT payload FROM protocol_messages WHERE id=?", (message_id,)).fetchone()
        if row:
            existing = json.loads(row[0])
            if any(existing[key] != value for key, value in
                   (("address", route["address"]), ("protocol", route["protocol"]), ("body", body))):
                raise ValueError("Message request already belongs to a different message")
            return {"message": existing}
        now = self.time()
        due = data.get("due", now)
        if type(due) is not int or not now - 60_000 <= due <= now + 366 * 86_400_000:
            raise ValueError("Choose a date within the next year")
        cutoff = now - 60_000
        if db.execute("SELECT count(*) FROM protocol_jobs WHERE date>=?", (cutoff,)).fetchone()[0] >= 30:
            raise ValueError("Too many messages queued. Try again in a minute.")
        message = self.message(route, message_id, body, True, now, "Queued" if due <= now else "Scheduled")
        if attachment:
            upload = attachment.pop("upload")
            message["attachment"] = attachment
            os.replace(self.files / "uploads" / upload, self.file_path(message_id))
            db.execute("DELETE FROM protocol_uploads WHERE id=?", (upload,))
        if relation:
            message["relation"] = relation
            message["text"] = text
            if relation["target"]:
                message[relation["kind"] + "_target"] = relation["target"]
        db.execute("INSERT INTO protocol_messages VALUES (?, ?, ?)", (message_id, now, json.dumps(message)))
        db.execute("INSERT INTO protocol_jobs VALUES (?, ?, ?, ?, 'queued')", (message_id, route["protocol"], route["target"], due))
        self.wake.set()
        return {"message": message}

    def claim(self):
        with closing(self.connect()) as db, db:
            db.execute("BEGIN IMMEDIATE")
            row = db.execute("""SELECT jobs.id, jobs.format, jobs.target, messages.payload FROM protocol_jobs jobs
                JOIN protocol_messages messages ON messages.id=jobs.id
                WHERE jobs.state='queued' AND jobs.date<=? ORDER BY jobs.date, jobs.id LIMIT 1""", (self.time(),)).fetchone()
            if row is None:
                return None
            db.execute("UPDATE protocol_jobs SET state='claimed' WHERE id=?", (row[0],))
            return row

    def sender(self):
        while True:
            row = self.claim()
            if row is None:
                self.wake.wait(5)
                self.wake.clear()
                continue
            key, format, endpoint, payload = row
            message = json.loads(payload)
            try:
                with closing(self.connect()) as db:
                    if format not in self.enabled(db):
                        raise ValueError("Protocol disabled")
                route = self.route(message["address"], format)
                if route is None or route["target"] != endpoint:
                    raise ValueError("Route changed")
                send = {"email": self.send_mail, "matrix": self.send_matrix, "xmpp": self.send_xmpp,
                        "irc": self.send_irc}[route.get("via", format)]
                message.update(send(route, message) or {})
                state, caption = "sent", "Sent"
            except (ValueError, ConnectionError) as error:
                # Short reasons reach the phone's red "Not delivered" notice.
                state, caption = "failed", "Couldn’t send · " + str(error)[:120]
            except Exception:
                state, caption = "failed", "Couldn’t send"
            with closing(self.connect()) as db, db:
                message["status"] = caption
                db.execute("UPDATE protocol_messages SET payload=? WHERE id=?", (json.dumps(message), key))
                db.execute("UPDATE protocol_jobs SET state=? WHERE id=?", (state, key))

    @staticmethod
    def message(route, id, body, outgoing, date, status="", author=""):
        return {"id": id, "address": route["address"], "protocol": route["protocol"], "title": route["title"],
                "body": bounded(body, 8000), "date": date, "type": 2 if outgoing else 1,
                "read": 1 if outgoing else 0, "status": status, "author": bounded(author, 120)}

    def add_incoming(self, message):
        with closing(self.connect()) as db, db:
            db.execute("INSERT OR IGNORE INTO protocol_messages VALUES (?, ?, ?)",
                       (message["id"], message["date"], json.dumps(message)))

    def state(self, key):
        with closing(self.connect()) as db:
            row = db.execute("SELECT value FROM protocol_state WHERE key=?", (key,)).fetchone()
            return row[0] if row else ""

    def save_state(self, key, value):
        with closing(self.connect()) as db, db:
            db.execute("INSERT OR REPLACE INTO protocol_state VALUES (?, ?)", (key, value))

    def send_mail(self, route, message):
        cfg = self.email
        envelope = EmailMessage()
        envelope["From"] = cfg["address"]
        envelope["To"] = route["target"]
        envelope["Message-ID"] = make_msgid(idstring=message["id"])
        envelope["Subject"] = bounded(cfg.get("subject"), 120) or "FluidMessages conversation"
        with closing(self.connect()) as db:
            rows = db.execute("SELECT payload FROM protocol_messages WHERE id<>? ORDER BY date DESC LIMIT 1500",
                              (message["id"],))
            previous = next((item for row in rows if (item := json.loads(row[0]))["address"] == route["address"]
                             and item["protocol"] == "email" and item.get("mail_id")), None)
        relation = message.get("relation") or {}
        if relation.get("mail_id"):
            # Replies and reactions thread onto the exact email they refer to.
            previous = {"mail_id": relation["mail_id"], "mail_subject": relation.get("mail_subject", "")}
        if previous and re.fullmatch(r"<[^\s<>]{1,240}>", previous["mail_id"]):
            envelope["In-Reply-To"] = previous["mail_id"]
            envelope["References"] = previous["mail_id"]
            if previous.get("mail_subject") and not re.search(r"[\r\n]", previous["mail_subject"]):
                subject = previous["mail_subject"]
                envelope.replace_header("Subject", subject if subject.lower().startswith("re:") else "Re: " + subject)
        attachment = message.get("attachment")
        if relation.get("kind") == "reaction" and relation.get("mail_id"):
            # RFC 9078: a reaction is a reply whose only text part is the emoji, marked as a reaction.
            envelope.set_content(relation["emoji"], disposition="reaction")
        elif relation.get("kind") == "reply" and relation.get("mail_id"):
            envelope.set_content(message.get("text", message["body"]) + "\n\nSent with FluidMessages")
        else:
            caption = message.get("text") if attachment else message["body"]
            envelope.set_content((caption or "") + "\n\nSent with FluidMessages")
        if attachment:
            maintype, _, subtype = attachment["mime"].partition("/")
            envelope.add_attachment(self.file_path(message["id"]).read_bytes(), maintype=maintype, subtype=subtype,
                                    filename=attachment["name"])
        with smtplib.SMTP_SSL(cfg["smtp_host"], int(cfg.get("smtp_port", 465)), context=self.tls, timeout=12) as server:
            server.login(cfg.get("smtp_user", cfg["address"]), cfg["smtp_password"])
            server.send_message(envelope, from_addr=cfg["address"], to_addrs=[route["target"]])
        return {"mail_id": str(envelope["Message-ID"]), "mail_subject": str(envelope["Subject"])}

    def receive_mail(self):
        cfg = self.email
        with imaplib.IMAP4_SSL(cfg["imap_host"], int(cfg.get("imap_port", 993)), ssl_context=self.tls, timeout=12) as mailbox:
            mailbox.login(cfg.get("imap_user", cfg["address"]), cfg["imap_password"])
            if mailbox.select(cfg.get("folder", "INBOX"), readonly=True)[0] != "OK":
                return
            validity = mailbox.response("UIDVALIDITY")[1][0]
            if not validity:
                return
            epoch = validity.decode()
            for route in self.routes:
                if route["protocol"] != "email":
                    continue
                key = "mail:" + epoch + ":" + route["target"]
                highest = int(self.state(key) or 0)
                criteria = ('FROM', '"' + route["target"] + '"') if highest == 0 else (
                    'UID', str(highest + 1) + ':*', 'FROM', '"' + route["target"] + '"')
                result, matches = mailbox.uid("search", None, *criteria)
                if result != "OK" or not matches or not matches[0]:
                    continue
                candidates = sorted(int(uid) for uid in matches[0].split() if int(uid) > highest)
                for uid in (candidates[-25:] if highest == 0 else candidates[:25]):
                    result, parts = mailbox.uid("fetch", str(uid), f"(RFC822.SIZE BODY.PEEK[]<0.{MAX_MAIL_WITH_FILES}>)")
                    if result != "OK":
                        break
                    part = next((part for part in parts if isinstance(part, tuple)), None)
                    if part is not None:
                        reported = re.search(rb"RFC822.SIZE (\d+)", part[0])
                        if reported and int(reported[1]) <= MAX_MAIL_WITH_FILES:
                            self.accept_mail(route, epoch, uid, part[1])
                    self.save_state(key, str(uid))

    def accept_mail(self, route, epoch, uid, payload):
        message = BytesParser(policy=policy.default).parsebytes(payload)
        sender = parseaddr(str(message.get("From", "")))[1].lower()
        if (sender != route["target"] or message.get("List-Id") or str(message.get("X-Spam-Flag", "")).lower() == "yes"
                or str(message.get("Auto-Submitted", "no")).lower() != "no"):
            return
        reaction = next((part for part in message.walk() if part.get_content_disposition() == "reaction"), None)
        target = None
        if reaction is not None:
            emoji = reaction.get_content().strip() if isinstance(reaction.get_content(), str) else ""
            replied = str(message.get("In-Reply-To", "")).strip()
            target = self.find(route["address"], "email", "mail_id", replied) if is_emoji(emoji) and replied else None
        attached = None
        if target is not None:
            text = reaction_text(emoji, target["body"])
        else:
            body = message.get_body(preferencelist=("plain",))
            text = body.get_content() if body is not None and isinstance(body.get_content(), str) else ""
            text = text.removesuffix("\n\nSent with FluidMessages\n").strip()
            # The first attachment comes along; the companion keeps it for the phone to download.
            for part in message.iter_attachments():
                data = part.get_payload(decode=True)
                if isinstance(data, bytes) and 0 < len(data) <= MAX_FILE:
                    mime = part.get_content_type() if MIME.fullmatch(part.get_content_type()) else "application/octet-stream"
                    attached = ({"kind": kind_for(mime), "name": safe_name(part.get_filename()), "mime": mime,
                                 "size": len(data), "duration": 0}, data)
                    break
            if not text and attached is None:
                return
            text = text or attached[0]["name"]
        try:
            date = min(self.time(), int(parsedate_to_datetime(message.get("Date")).timestamp() * 1000))
        except (AttributeError, ValueError, TypeError, OverflowError):
            date = self.time()
        stable = hashlib.sha256(f"{route['target']}:{epoch}:{uid}".encode()).hexdigest()
        record = self.message(route, "mail-" + stable, text, False, max(0, date), author=route["title"])
        record["mail_id"] = bounded(str(message.get("Message-ID", "")), 240)
        record["mail_subject"] = bounded(str(message.get("Subject", "")), 120)
        if target is not None:
            record["reaction_target"] = target["id"]
        if attached is not None:
            record["attachment"] = attached[0]
            self.file_path(record["id"]).write_bytes(attached[1])
        self.add_incoming(record)

    def matrix_request(self, path, body=None, allow_missing=False, method=None):
        request = Request(self.matrix["homeserver"] + "/_matrix/client/v3/" + path,
                          data=json.dumps(body).encode() if body is not None else None, method=method,
                          headers={"Authorization": "Bearer " + self.matrix["access_token"], "Content-Type": "application/json"})
        try:
            with self.http.open(request, timeout=17) as response:
                data = response.read(MAX_MATRIX + 1)
                if len(data) > MAX_MATRIX:
                    raise ValueError("Matrix response too large")
                return json.loads(data)
        except HTTPError as error:
            if not allow_missing or error.code != 404:
                raise
            if json.load(error).get("errcode") != "M_NOT_FOUND":
                raise
            return None

    def send_matrix(self, route, message):
        room = quote(route["target"], safe="")
        secure = self.clients.get("matrix", (None, None))[1]
        if secure is None and self.matrix_request("rooms/" + room + "/state/m.room.encryption", allow_missing=True) is not None:
            raise ValueError("This room is end-to-end encrypted. Install matrix-nio[e2e] on the companion to use it.")
        relation = message.get("relation") or {}
        event = relation.get("event_id")
        if relation.get("kind") == "reaction" and event:
            content = {"m.relates_to": {"rel_type": "m.annotation", "event_id": event, "key": relation["emoji"]}}
            if secure is not None:
                return {"event_id": secure.send(route["target"], "m.reaction", content, message["id"])}
            response = self.matrix_request("rooms/" + room + "/send/m.reaction/" + message["id"], content, method="PUT")
            return {"event_id": response["event_id"]}
        content = {"msgtype": "m.text", "body": message["body"]}
        if message.get("attachment"):
            content = self.matrix_media(message, route["target"], secure)
        if relation.get("kind") == "reply" and event and not message.get("attachment"):
            # Matrix v1.13 replies carry no quoted fallback; the relation links the original event.
            content = {"msgtype": "m.text", "body": message.get("text") or message["body"],
                       "m.relates_to": {"m.in_reply_to": {"event_id": event}}}
        if secure is not None:
            return {"event_id": secure.send(route["target"], "m.room.message", content, message["id"])}
        response = self.matrix_request("rooms/" + room + "/send/m.room.message/" + message["id"], content, method="PUT")
        return {"event_id": response["event_id"]}

    def matrix_raw(self, url, data=None, headers=None, method=None, limit=MAX_FILE):
        request = Request(url, data=data, method=method, headers={"Authorization": "Bearer " + self.matrix["access_token"],
                                                                  **(headers or {})})
        with self.http.open(request, timeout=120) as response:
            body = response.read(limit + 1)
            if len(body) > limit:
                raise ValueError("Matrix file too large")
            return body

    def matrix_media(self, message, room=None, secure=None):
        """Uploads to the homeserver's media repository and builds an m.image, m.video, m.audio, or m.file event.

        In encrypted rooms the file is encrypted before upload and the event carries its key ("file" instead of "url").
        """
        attachment = message["attachment"]
        data = self.file_path(message["id"]).read_bytes()
        if secure is not None:
            location = secure.upload(room, data, attachment["mime"], attachment["name"])
        else:
            uploaded = json.loads(self.matrix_raw(self.matrix["homeserver"] + "/_matrix/media/v3/upload?" +
                                                  urlencode({"filename": attachment["name"]}), data,
                                                  {"Content-Type": attachment["mime"]}, "POST", MAX_MATRIX))
            location = {"url": uploaded["content_uri"]}
        msgtype = {"photo": "m.image", "video": "m.video", "audio": "m.audio", "voice": "m.audio"}.get(attachment["kind"], "m.file")
        info = {"mimetype": attachment["mime"], "size": len(data)}
        if attachment.get("duration"):
            info["duration"] = attachment["duration"]
        caption = message.get("text") or ""
        content = {"msgtype": msgtype, "body": caption or attachment["name"], "filename": attachment["name"],
                   **location, "info": info}
        if attachment["kind"] == "voice":
            # MSC3245 marks voice notes so Element and other clients show a voice player.
            content["org.matrix.msc3245.voice"] = {}
            content["org.matrix.msc1767.audio"] = {"duration": attachment.get("duration", 0)}
        return content

    def matrix_download(self, mxc):
        match = re.fullmatch(r"mxc://([A-Za-z0-9.:-]{1,255})/([A-Za-z0-9_-]{1,255})", mxc or "")
        if not match:
            raise ValueError("Invalid media address")
        path = quote(match[1], safe="") + "/" + quote(match[2], safe="")
        try:
            # Authenticated media (Matrix 1.11), with the older endpoint as a fallback for older homeservers.
            return self.matrix_raw(self.matrix["homeserver"] + "/_matrix/client/v1/media/download/" + path)
        except HTTPError as error:
            if error.code not in (400, 404):
                raise
            return self.matrix_raw(self.matrix["homeserver"] + "/_matrix/media/v3/download/" + path)

    def receive_matrix(self):
        rooms = {route["target"]: route for route in self.routes if route.get("via") == "matrix"}
        filter = {"presence": {"types": []}, "room": {"rooms": list(rooms), "timeline": {"limit": 60,
                  "types": ["m.room.message", "m.room.encrypted", "m.reaction"]}}}
        args = {"filter": json.dumps(filter, separators=(",", ":")), "timeout": 10_000}
        if token := self.state("matrix:since"):
            args["since"] = token
        joined = self.matrix_request("sync?" + urlencode(args))
        for room, data in joined.get("rooms", {}).get("join", {}).items():
            if room not in rooms:
                continue
            for event in data.get("timeline", {}).get("events", []):
                self.accept_matrix(rooms[room], event)
        if isinstance(joined.get("next_batch"), str):
            self.save_state("matrix:since", joined["next_batch"])

    def accept_matrix(self, route, event):
        if event.get("sender") == self.matrix.get("user_id") or not isinstance(event.get("event_id"), str):
            return
        content = event.get("content", {})
        if not isinstance(content, dict):
            return
        relates = content.get("m.relates_to") if isinstance(content.get("m.relates_to"), dict) else {}
        stable = hashlib.sha256(event["event_id"].encode()).hexdigest()
        date = event.get("origin_server_ts")
        date = date if type(date) is int else self.time()
        author = event.get("sender", "")
        if event.get("type") == "m.reaction":
            target = self.find(route["address"], route["protocol"], "event_id", relates.get("event_id"))
            if relates.get("rel_type") != "m.annotation" or target is None or not is_emoji(relates.get("key")):
                return
            record = self.message(route, "matrix-" + stable, reaction_text(relates["key"], target["body"]), False, date,
                                  author=author)
            record["reaction_target"] = target["id"]
        elif event.get("type") == "m.room.message" and content.get("msgtype") in ("m.image", "m.video", "m.audio", "m.file") \
                and (isinstance(content.get("url"), str) or isinstance(content.get("file"), dict)
                     and isinstance(content["file"].get("url"), str)):
            info = content.get("info") if isinstance(content.get("info"), dict) else {}
            if type(info.get("size")) is int and info["size"] > MAX_FILE:
                return
            name = safe_name(content.get("filename") or content.get("body"))
            mime = info.get("mimetype") if isinstance(info.get("mimetype"), str) and MIME.fullmatch(info["mimetype"]) \
                else "application/octet-stream"
            data = self.matrix_download(content.get("url") or content["file"]["url"])
            if isinstance(content.get("file"), dict):
                data = matrix_e2ee.decrypt_file(content, data)
            kind = "voice" if "org.matrix.msc3245.voice" in content else kind_for(mime)
            caption = content.get("body") if isinstance(content.get("body"), str) and content.get("body") != name else ""
            record = self.message(route, "matrix-" + stable, caption or name, False, date, author=author)
            record["attachment"] = {"kind": kind, "name": name, "mime": mime, "size": len(data),
                                    "duration": info.get("duration") if type(info.get("duration")) is int else 0}
            self.file_path(record["id"]).write_bytes(data)
        elif event.get("type") == "m.room.message":
            if content.get("msgtype") not in ("m.text", "m.notice", "m.emote") or not isinstance(content.get("body"), str):
                return
            body = content["body"]
            reply = relates.get("m.in_reply_to") if isinstance(relates.get("m.in_reply_to"), dict) else {}
            target = self.find(route["address"], route["protocol"], "event_id", reply.get("event_id")) if reply else None
            if target is not None:
                # Older clients prepend "> <@user> quote" lines; the quote card replaces them.
                lines = body.split("\n")
                while lines and lines[0].startswith(">"):
                    lines.pop(0)
                stripped = "\n".join(lines).strip()
                body = reply_text(target["body"], stripped or body)
            record = self.message(route, "matrix-" + stable, body, False, date, author=author)
            if target is not None:
                record["reply_target"] = target["id"]
        else:
            return
        record["event_id"] = event["event_id"]
        self.add_incoming(record)

    def receiver(self, operation, interval, needed):
        """Runs a poller only while an account and at least one link use it, re-checking as they change."""
        while True:
            try:
                if needed():
                    operation()
            except Exception:
                pass
            time.sleep(interval() if callable(interval) else interval)

    def uses(self, via):
        return lambda: bool(self.accounts.current().get(via)) and any(r.get("via", r["protocol"]) == via for r in self.routes)

    def start(self):
        threading.Thread(target=self.sender, daemon=True, name="protocol-sender").start()
        mail_interval = lambda: 60 * int(self.email.get("poll_minutes", 1))
        threading.Thread(target=self.receiver, args=(self.receive_mail, mail_interval, self.uses("email")),
                         daemon=True, name="protocol-imap").start()
        plain_matrix = lambda: self.uses("matrix")() and "matrix" not in self.clients
        threading.Thread(target=self.receiver, args=(self.receive_matrix, 5, plain_matrix),
                         daemon=True, name="protocol-matrix").start()
        threading.Thread(target=self.supervise, daemon=True, name="protocol-clients").start()

    def supervise(self):
        while True:
            try:
                self.refresh_clients()
            except Exception:
                pass
            time.sleep(15)

    def refresh_clients(self):
        """Starts, restarts, or stops the long-lived IRC, XMPP, and encrypted Matrix sessions to match accounts and links."""
        accounts = self.accounts.current()
        routes = self.routes
        matrix = accounts.get("matrix")
        wanted = json.dumps(matrix, sort_keys=True) if matrix and matrix_e2ee.available() and any(
            r.get("via") == "matrix" for r in routes) else None
        current = self.clients.get("matrix")
        if (current[0] if current else None) != wanted:
            if current:
                current[1].stop()
                del self.clients["matrix"]
            if wanted:
                rooms = lambda: {r["target"] for r in self.routes if r.get("via") == "matrix"}
                secure = matrix_e2ee.MatrixE2ee(matrix, self.files / "matrix-store", rooms, self.accept_secure)
                self.clients["matrix"] = (wanted, secure)
                threading.Thread(target=self.guard, args=(secure.run,), daemon=True, name="protocol-matrix-e2ee").start()
        for protocol in ("irc", "xmpp"):
            account = accounts.get(protocol)
            native = [r for r in routes if r["protocol"] == protocol and r.get("via") == protocol]
            wanted = None
            if account and (native or protocol == "irc" and account.get("channels")):
                rooms = sorted({r["target"] for r in native if r.get("group")})
                wanted = json.dumps([account, rooms], sort_keys=True)
            current = self.clients.get(protocol)
            if current and current[0] == wanted:
                continue
            if current:
                current[1].stop()
                del self.clients[protocol]
            if wanted is None:
                continue
            if protocol == "irc":
                channels = sorted(set(account.get("channels", [])) | set(rooms))
                client = irc_client.IrcClient({**account, "channels": channels}, self.accept_irc, self.channel_state)
            else:
                if not xmpp_client.available():
                    continue
                client = xmpp_client.XmppClient(account, self.accept_xmpp, rooms)
            self.clients[protocol] = (wanted, client)
            threading.Thread(target=client.run, daemon=True, name="protocol-" + protocol).start()

    def guard(self, run):
        try:
            run()
        except Exception:
            # A failed encrypted session falls back to the plain Matrix poller until the next account change.
            entry = self.clients.get("matrix")
            if entry and entry[1].run == run:
                del self.clients["matrix"]

    def accept_secure(self, room_id, event):
        route = next((r for r in self.routes if r.get("via") == "matrix" and r["target"] == room_id), None)
        if route is not None:
            try:
                self.accept_matrix(route, event)
            except Exception:
                pass

    def client(self, protocol):
        entry = self.clients.get(protocol)
        if entry is None:
            raise ConnectionError(protocol.upper() + " isn’t connected. Check the account on your companion.")
        return entry[1]

    # IRC

    def irc_route(self, conversation):
        for route in self.routes:
            if route["protocol"] == "irc" and route.get("via") == "irc" and route["target"].lower() == conversation.lower():
                return route
        return None

    def channel_state(self, channel, state):
        route = self.irc_route(channel)
        if route is not None:
            self.save_state("channel:" + route["address"], json.dumps({"name": channel, **state}))

    def accept_irc(self, conversation, nick, text, tags, date, outgoing):
        route = self.irc_route(conversation)
        # Direct messages from nicks you haven't linked are ignored, the same way unknown email is.
        if route is None or outgoing:
            return
        msgid = tags.get("msgid", "")
        stable = hashlib.sha256((msgid or f"{conversation}:{nick}:{date}:{text}").encode()).hexdigest()
        react, reply = tags.get("+draft/react"), tags.get("+draft/reply")
        target = self.find(route["address"], "irc", "irc_msgid", reply) if reply else None
        if react:
            if target is None or not is_emoji(react):
                return
            record = self.message(route, "irc-" + stable, reaction_text(react, target["body"]), False, date, author=nick)
            record["reaction_target"] = target["id"]
        else:
            body = reply_text(target["body"], text) if target else text
            record = self.message(route, "irc-" + stable, body, False, date, author=nick)
            if target:
                record["reply_target"] = target["id"]
            if tags.get("event"):
                record["event"] = True
        record["irc_msgid"] = msgid
        self.add_incoming(record)

    def send_irc(self, route, message):
        relation = message.get("relation") or {}
        if message.get("attachment"):
            raise ValueError("IRC has no file transfer. Send the file with another protocol.")
        client = self.client("irc")
        reply = relation.get("irc_msgid")
        tagged = reply and "message-tags" in client.caps
        if relation.get("kind") == "reaction":
            if tagged:
                client.send(route["target"], "", reply=reply, react=relation["emoji"])
            else:
                client.send(route["target"], message["body"])
        else:
            client.send(route["target"], message.get("text") if tagged and relation.get("kind") == "reply" else message["body"],
                        reply=reply if tagged else None)
        return {}

    # XMPP

    def xmpp_route(self, conversation):
        for route in self.routes:
            if route["protocol"] == "xmpp" and route.get("via") == "xmpp" and route["target"] == conversation.lower():
                return route
        return None

    def accept_xmpp(self, conversation, author, text, message_id, date, outgoing, extra):
        route = self.xmpp_route(conversation)
        if route is None:
            return
        if outgoing and self.find(route["address"], "xmpp", "xmpp_id", message_id):
            return  # Our own message reflected back by a group or carbon.
        stable = hashlib.sha256(f"{conversation}:{message_id}:{date}".encode()).hexdigest()
        if extra.get("reaction"):
            target = self.find(route["address"], "xmpp", "xmpp_id", extra["reaction_to"])
            if target is None or not is_emoji(extra["reaction"]):
                return
            record = self.message(route, "xmpp-" + stable, reaction_text(extra["reaction"], target["body"]), outgoing, date,
                                  author=author)
            record["reaction_target"] = target["id"]
        else:
            target = self.find(route["address"], "xmpp", "xmpp_id", extra.get("reply_to")) if extra.get("reply_to") else None
            body = reply_text(target["body"], text) if target else text
            record = self.message(route, "xmpp-" + stable, body, outgoing, date, author=author)
            if target:
                record["reply_target"] = target["id"]
            if extra.get("file_url"):
                self.fetch_shared_file(record, extra["file_url"])
        record["xmpp_id"] = message_id
        self.add_incoming(record)

    def fetch_shared_file(self, record, url):
        """Downloads an HTTP File Upload link (HTTPS only, up to 25 MB) so the phone gets the file itself."""
        parts = urlsplit(url)
        if parts.scheme != "https" or not parts.hostname:
            return
        try:
            with self.http.open(Request(url), timeout=60) as response:
                data = response.read(MAX_FILE + 1)
                mime = (response.headers.get_content_type() or "application/octet-stream").lower()
        except Exception:
            return
        if len(data) > MAX_FILE:
            return
        name = safe_name(Path(parts.path).name)
        record["attachment"] = {"kind": kind_for(mime), "name": name, "mime": mime if MIME.fullmatch(mime) else
                                "application/octet-stream", "size": len(data), "duration": 0}
        record["body"] = record["body"].replace(url, "").strip() or name
        self.file_path(record["id"]).write_bytes(data)

    def send_xmpp(self, route, message):
        relation = message.get("relation") or {}
        attachment = message.get("attachment")
        file = (str(self.file_path(message["id"])), attachment["name"], attachment["mime"]) if attachment else None
        client = self.client("xmpp")
        reply = relation.get("xmpp_id")
        author = None
        if reply and route.get("group") and relation.get("author"):
            author = route["target"] + "/" + relation["author"]
        if relation.get("kind") == "reaction" and reply:
            sent = client.send(route["target"], "", route.get("group"), reply=reply, react=relation["emoji"])
        else:
            text = message.get("text") if reply and relation.get("kind") == "reply" else message["body"]
            if attachment and not message.get("text"):
                text = ""
            sent = client.send(route["target"], text, route.get("group"), reply=reply, reply_author=author, file=file)
        return {"xmpp_id": sent}

    # Matrix direct rooms

    def matrix_direct_room(self, user):
        """Finds the existing direct room with a Matrix user, or creates one, like Element's "Start chat"."""
        me = quote(self.matrix["user_id"], safe="")
        direct = self.matrix_request("user/" + me + "/account_data/m.direct", allow_missing=True) or {}
        for room in direct.get(user, []):
            if isinstance(room, str) and ROOM.fullmatch(room):
                return room
        room = self.matrix_request("createRoom", {"is_direct": True, "invite": [user], "preset": "trusted_private_chat"},
                                   method="POST")["room_id"]
        direct.setdefault(user, []).append(room)
        self.matrix_request("user/" + me + "/account_data/m.direct", direct, method="PUT")
        return room
