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

INTERNET = ("matrix", "xmpp", "email", "irc")
MATRIX_ROUTES = ("matrix", "xmpp", "irc")
MAX_MAIL = 128 * 1024
MAX_MATRIX = 5 * 1024 * 1024
EMAIL = re.compile(r"[^@\s<>\x00-\x1f]{1,64}@[A-Za-z0-9](?:[A-Za-z0-9.-]{0,251}[A-Za-z0-9])?")
NUMBER = re.compile(r"\+?[0-9][0-9(). -]{2,38}")
ROOM = re.compile(r"![^\s/\x00-\x1f]{1,250}")
ID = re.compile(r"[a-zA-Z0-9_-]{1,90}")

SCHEMA = """
CREATE TABLE IF NOT EXISTS protocol_messages (id TEXT PRIMARY KEY, date INTEGER NOT NULL, payload TEXT NOT NULL);
CREATE INDEX IF NOT EXISTS protocol_message_date ON protocol_messages (date DESC);
CREATE TABLE IF NOT EXISTS protocol_jobs (id TEXT PRIMARY KEY, format TEXT NOT NULL, target TEXT NOT NULL,
    date INTEGER NOT NULL, state TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS protocol_state (key TEXT PRIMARY KEY, value TEXT NOT NULL);
"""


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
    if file.stat().st_size > MAX_MAIL:
        raise ValueError("Protocol configuration is too large")
    result = json.loads(file.read_text())
    if not isinstance(result, dict):
        raise ValueError("Expected a protocol configuration object")
    return result


class Gateway:
    def __init__(self, connect, config):
        self.connect = connect
        self.email = config.get("email") or {}
        self.matrix = config.get("matrix") or {}
        self.routes = self.validate_routes(config.get("routes") or [])
        self.tls = ssl.create_default_context()
        self.http = build_opener(HTTPSHandler(context=self.tls), NoRedirect())
        self.wake = threading.Event()
        if any(route["protocol"] in MATRIX_ROUTES for route in self.routes):
            base = urlsplit(self.matrix["homeserver"])
            if base.scheme != "https" or not base.hostname or base.username or base.password or base.query or base.fragment:
                raise ValueError("Matrix needs an HTTPS homeserver URL")
            self.matrix["homeserver"] = self.matrix["homeserver"].rstrip("/")

    def validate_routes(self, routes):
        if not isinstance(routes, list) or len(routes) > 300:
            raise ValueError("Configure up to 300 protocol routes")
        seen = set()
        output = []
        for route in routes:
            format = route["protocol"]
            address, target = route["address"], route["target"]
            if (not isinstance(address, str) or not (ID.fullmatch(address) or NUMBER.fullmatch(address))
                    or (address, format) in seen or not isinstance(target, str)):
                raise ValueError("Invalid or duplicate protocol route")
            if format == "email":
                if not self.email or not EMAIL.fullmatch(target):
                    raise ValueError("Email routes need mail accounts and one explicit correspondent")
                target = target.lower()
            elif format in MATRIX_ROUTES:
                if not self.matrix or not ROOM.fullmatch(target):
                    raise ValueError("Matrix and bridge routes need an explicit Matrix room ID")
            else:
                raise ValueError("Unknown internet format")
            if any(r["protocol"] == format and r["target"] == target or
                   format in MATRIX_ROUTES and r["protocol"] in MATRIX_ROUTES and r["target"] == target for r in output):
                raise ValueError("Each mail address or room can belong to one route")
            members = route.get("members", [])
            if not isinstance(members, list) or len(members) > 100:
                raise ValueError("Invalid route member list")
            seen.add((address, format))
            output.append({"address": address, "protocol": format, "target": target,
                           "title": bounded(route.get("title"), 120) or address,
                           "members": [bounded(name, 120) for name in members if isinstance(name, str)]})
        return output

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
        return {"messages": messages, "routes": [{key: value for key, value in route.items() if key != "target"}
                for route in self.routes], "enabled": sorted(self.enabled(db))}

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

    def enqueue(self, db, data):
        route, body, message_id = self.validate_send(db, data)
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
                updates = self.send_mail(route, message) if format == "email" else self.send_matrix(route, message)
                message.update(updates or {})
                state, caption = "sent", "Sent"
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
        if previous and re.fullmatch(r"<[^\s<>]{1,240}>", previous["mail_id"]):
            envelope["In-Reply-To"] = previous["mail_id"]
            envelope["References"] = previous["mail_id"]
            if previous.get("mail_subject") and not re.search(r"[\r\n]", previous["mail_subject"]):
                subject = previous["mail_subject"]
                envelope.replace_header("Subject", subject if subject.lower().startswith("re:") else "Re: " + subject)
        envelope.set_content(message["body"] + "\n\nSent with FluidMessages")
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
                    result, parts = mailbox.uid("fetch", str(uid), f"(RFC822.SIZE BODY.PEEK[]<0.{MAX_MAIL}>)")
                    if result != "OK":
                        break
                    part = next((part for part in parts if isinstance(part, tuple)), None)
                    if part is not None:
                        reported = re.search(rb"RFC822.SIZE (\d+)", part[0])
                        if reported and int(reported[1]) <= MAX_MAIL:
                            self.accept_mail(route, epoch, uid, part[1])
                    self.save_state(key, str(uid))

    def accept_mail(self, route, epoch, uid, payload):
        message = BytesParser(policy=policy.default).parsebytes(payload)
        sender = parseaddr(str(message.get("From", "")))[1].lower()
        if (sender != route["target"] or message.get("List-Id") or str(message.get("X-Spam-Flag", "")).lower() == "yes"
                or str(message.get("Auto-Submitted", "no")).lower() != "no"):
            return
        body = message.get_body(preferencelist=("plain",))
        if body is None or not isinstance(text := body.get_content(), str):
            return
        text = text.removesuffix("\n\nSent with FluidMessages\n").strip()
        if not text:
            return
        try:
            date = min(self.time(), int(parsedate_to_datetime(message.get("Date")).timestamp() * 1000))
        except (AttributeError, ValueError, TypeError, OverflowError):
            date = self.time()
        stable = hashlib.sha256(f"{route['target']}:{epoch}:{uid}".encode()).hexdigest()
        record = self.message(route, "mail-" + stable, text, False, max(0, date), author=route["title"])
        record["mail_id"] = bounded(str(message.get("Message-ID", "")), 240)
        record["mail_subject"] = bounded(str(message.get("Subject", "")), 120)
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
        if self.matrix_request("rooms/" + room + "/state/m.room.encryption", allow_missing=True) is not None:
            raise ValueError("Encrypted Matrix rooms require a client with end-to-end encryption")
        response = self.matrix_request("rooms/" + room + "/send/m.room.message/" + message["id"],
                                       {"msgtype": "m.text", "body": message["body"]}, method="PUT")
        return {"event_id": response["event_id"]}

    def receive_matrix(self):
        rooms = {route["target"]: route for route in self.routes if route["protocol"] in MATRIX_ROUTES}
        filter = {"presence": {"types": []}, "room": {"rooms": list(rooms), "timeline": {"limit": 60,
                  "types": ["m.room.message", "m.room.encrypted"]}}}
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
        if event.get("type") != "m.room.message" or event.get("sender") == self.matrix.get("user_id"):
            return
        content = event.get("content", {})
        if content.get("msgtype") not in ("m.text", "m.notice", "m.emote") or not isinstance(content.get("body"), str):
            return
        stable = hashlib.sha256(str(event.get("event_id")).encode()).hexdigest()
        date = event.get("origin_server_ts")
        self.add_incoming(self.message(route, "matrix-" + stable, content["body"], False,
                                       date if type(date) is int else self.time(), author=event.get("sender", "")))

    def receiver(self, operation, interval):
        while True:
            try:
                operation()
            except Exception:
                pass
            time.sleep(interval)

    def start(self):
        if not self.routes:
            return
        threading.Thread(target=self.sender, daemon=True, name="protocol-sender").start()
        if any(route["protocol"] == "email" for route in self.routes):
            threading.Thread(target=self.receiver, args=(self.receive_mail, 60), daemon=True, name="protocol-imap").start()
        if any(route["protocol"] in MATRIX_ROUTES for route in self.routes):
            threading.Thread(target=self.receiver, args=(self.receive_matrix, 5), daemon=True, name="protocol-matrix").start()
