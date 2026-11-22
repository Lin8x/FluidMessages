"""Single-phone LAN companion. Run behind a TLS reverse proxy."""
import hmac
import json
import os
import re
import sqlite3
import subprocess
import time
from contextlib import closing
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

import cloud_backup
import protocol_gateway

ROOT = Path(__file__).resolve().parent
DB = Path(os.environ.get("FLUID_DB", "data/fluid.sqlite"))
TOKEN = os.environ.get("FLUID_TOKEN", "")
FILES = {"/": ("index.html", "text/html"), "/app.js": ("app.js", "text/javascript"),
         "/style.css": ("style.css", "text/css")}

def appearance_snapshot(value):
    if not isinstance(value, dict):
        return {}
    clean = {}
    for key in ("dark", "exactTimes", "mmsReady", "protocolTags", "protocolPrivacy", "combineProtocols", "protocolAuto"):
        if type(value.get(key)) is bool:
            clean[key] = value[key]
    for key, lower, upper in (("radius", 2, 30), ("size", 12, 28), ("padding", 6, 24)):
        if type(value.get(key)) is int and lower <= value[key] <= upper:
            clean[key] = value[key]
    colors = value.get("colors")
    if isinstance(colors, dict):
        clean["colors"] = {
            key: colors[key] for key in ("canvas", "ink", "muted", "surface", "raised", "line", "outline", "action",
                                          "incoming", "incomingInk", "sms", "smsInk",
                                          "automated", "automatedInk", "accent", "accentInk",
                                          "matrix", "matrixInk", "xmpp", "xmppInk", "email", "emailInk", "irc", "ircInk")
            if isinstance(colors.get(key), str) and re.fullmatch(r"#[0-9a-fA-F]{6}", colors[key])
        }
    if isinstance(value.get("enabledProtocols"), list):
        clean["enabledProtocols"] = [key for key in ("sms", "mms", "matrix", "xmpp", "email", "irc")
                                     if key in value["enabledProtocols"]]
    defaults = value.get("protocolDefaults")
    if isinstance(defaults, dict):
        clean["protocolDefaults"] = {key: value for key, value in list(defaults.items())[:2000]
                                     if len(key) <= 150 and value in ("sms", "mms", "matrix", "xmpp", "email", "irc")}
    folders = value.get("folders")
    if isinstance(folders, dict):
        clean["folders"] = {
            key: [entry for entry in folders[key][:2000] if isinstance(entry, str) and len(entry) <= 150]
            for key in ("pinned", "archived", "blocked") if isinstance(folders.get(key), list)
        }
    return clean


def connect():
    connection = sqlite3.connect(DB, timeout=10)
    connection.row_factory = sqlite3.Row
    return connection


def protocol_settings(db):
    row = db.execute("SELECT content FROM phone_appearance WHERE id=1").fetchone()
    return json.loads(row["content"]) if row else {}


class Handler(BaseHTTPRequestHandler):
    def reply(self, status, payload, content_type="application/json"):
        data = payload if isinstance(payload, bytes) else json.dumps(payload).encode()
        self.send_response(status)
        self.send_header("Content-Type", content_type + "; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Content-Security-Policy", "default-src 'self'; img-src 'self' data:; frame-ancestors 'none'; base-uri 'none'; form-action 'self'")
        self.end_headers()
        self.wfile.write(data)

    def authorize(self):
        header = self.headers.get("Authorization", "")
        if not hmac.compare_digest(header.encode(), ("Bearer " + TOKEN).encode()):
            self.reply(401, {"error": "Enter the server pairing token"})
            return False
        return True

    def do_GET(self):
        if self.path in FILES:
            name, content_type = FILES[self.path]
            self.reply(200, (ROOT / name).read_bytes(), content_type)
            return
        if not self.authorize():
            return
        if self.path == "/api/backups":
            self.reply(200, {"targets": list(cloud_backup.remotes())})
            return
        match = re.fullmatch(r"/api/backups/(onedrive|drive|s3)/latest", self.path)
        if match:
            self.backup_transfer(match[1], fetching=True)
            return
        with closing(connect()) as db:
            if self.path == "/api/inbox":
                row = db.execute("""
                    SELECT inbox.content, inbox.updated, phone_appearance.content AS appearance
                    FROM inbox LEFT JOIN phone_appearance ON inbox.id=phone_appearance.id WHERE inbox.id=1
                """).fetchone()
                history = gateway.snapshot(db)
                messages = (json.loads(row["content"]) if row else []) + history["messages"]
                messages.sort(key=lambda message: message["date"], reverse=True)
                self.reply(200, {"messages": messages, "routes": history["routes"], "enabled": history["enabled"],
                                 "updated": row["updated"] if row else None,
                                 "appearance": json.loads(row["appearance"]) if row and row["appearance"] else {}})
            elif self.path == "/api/jobs":
                rows = db.execute("SELECT * FROM jobs ORDER BY id DESC LIMIT 100").fetchall()
                internet = db.execute("""SELECT jobs.id, jobs.date AS due, jobs.state, messages.payload
                    FROM protocol_jobs jobs JOIN protocol_messages messages ON messages.id=jobs.id
                    ORDER BY jobs.date DESC LIMIT 100""")
                result = [dict(row) for row in rows]
                for job in internet:
                    message = json.loads(job["payload"])
                    result.append({"id": job["id"], "due": job["due"], "protocol": message["protocol"],
                                   "address": message["title"], "body": message["body"], "state": job["state"]})
                self.reply(200, result)
            else:
                self.reply(404, {"error": "Unknown endpoint"})

    def do_PUT(self):
        if not self.authorize():
            return
        match = re.fullmatch(r"/api/backups/(onedrive|drive|s3)", self.path)
        if match:
            self.backup_transfer(match[1], fetching=False)
        else:
            self.reply(404, {"error": "Unknown endpoint"})

    def backup_transfer(self, target, fetching):
        remote = cloud_backup.remotes().get(target)
        if not remote:
            self.reply(404, {"error": "Backup destination is not configured"})
            return
        self.connection.settimeout(195)
        try:
            if fetching:
                cloud_backup.download_latest(remote, self)
            else:
                size = int(self.headers.get("Content-Length", "0"))
                self.reply(201, cloud_backup.upload(remote, self.rfile, size, self.headers.get("X-Fluid-Backup-Name")))
        except ValueError:
            self.reply(400, {"error": "Invalid or incomplete backup transfer"})
        except (subprocess.SubprocessError, FileNotFoundError):
            self.reply(502, {"error": "Backup destination could not be reached"})
        except (BrokenPipeError, ConnectionResetError, TimeoutError):
            self.close_connection = True

    def do_POST(self):
        if not self.authorize():
            return
        try:
            size = int(self.headers.get("Content-Length", "0"))
            if not 0 < size <= 8_000_000:
                raise ValueError("Invalid request size")
            data = json.loads(self.rfile.read(size))
            if not isinstance(data, dict):
                raise ValueError("Expected an object")
            response = self.mutate(data)
        except (KeyError, ValueError, TypeError) as error:
            self.reply(400, {"error": str(error)})
        else:
            self.reply(200, response)

    def mutate(self, data):
        now = int(time.time() * 1000)
        with closing(connect()) as db, db:
            if self.path == "/api/protocols":
                return gateway.snapshot(db, data.get("enabled"))
            if self.path == "/api/protocols/send":
                return gateway.enqueue(db, data)
            if self.path == "/api/sync":
                messages = data["messages"]
                if not isinstance(messages, list) or len(messages) > 2000:
                    raise ValueError("Expected up to 2,000 messages")
                for message in messages:
                    if not isinstance(message, dict) or any(
                        type(message.get(key)) is not expected
                        for key, expected in {"address": str, "body": str, "date": int, "type": int}.items()
                    ):
                        raise ValueError("Invalid message")
                    if message.get("protocol", "sms") not in ("sms", "mms"):
                        raise ValueError("Invalid phone protocol")
                    if "thumbnail" in message and (not isinstance(message["thumbnail"], str)
                            or len(message["thumbnail"]) > 160_000 or not re.fullmatch(r"[A-Za-z0-9+/=]+", message["thumbnail"])):
                        raise ValueError("Invalid MMS thumbnail")
                db.execute("INSERT OR REPLACE INTO inbox VALUES (1, ?, ?)", (json.dumps(messages), now))
                db.execute("INSERT OR REPLACE INTO phone_appearance VALUES (1, ?)",
                           (json.dumps(appearance_snapshot(data.get("appearance"))),))
                return {"updated": now}
            if self.path == "/api/schedule":
                address, body, due = data["address"], data["body"], data["due"]
                format = data.get("protocol", "sms")
                if format not in ("sms", "mms"):
                    raise ValueError("Choose a carrier protocol")
                settings = protocol_settings(db)
                if format not in settings.get("enabledProtocols", ("sms", "mms")):
                    raise ValueError("This protocol is turned off on the phone")
                if format == "mms" and not settings.get("mmsReady"):
                    raise ValueError("Your paired Android app does not support browser MMS")
                if not isinstance(address, str):
                    raise ValueError("Enter a valid phone number")
                numbers = address[4:].split(",") if format == "mms" and address.startswith("mms:") else [address]
                if len(numbers) > 20 or any(not re.fullmatch(r"(?=.*[0-9])[+0-9*#(). -]{1,40}", number) for number in numbers):
                    raise ValueError("Enter valid phone numbers")
                if not isinstance(body, str) or not body.strip() or len(body.encode("utf-16-le")) > 3200:
                    raise ValueError("Enter 1–1,600 characters")
                if type(due) is not int or not now - 60_000 <= due <= now + 366 * 86_400_000:
                    raise ValueError("Choose a date within the next year")
                job = db.execute("INSERT INTO jobs (address, body, due, state, protocol) VALUES (?, ?, ?, 'scheduled', ?)",
                                 (address, body, due, format))
                return {"id": job.lastrowid}
            if self.path == "/api/claim":
                db.execute("BEGIN IMMEDIATE")
                job = db.execute("SELECT * FROM jobs WHERE state='scheduled' AND due<=? ORDER BY due,id LIMIT 1",
                                 (now,)).fetchone()
                if job:
                    db.execute("UPDATE jobs SET state='claimed' WHERE id=?", (job["id"],))
                return {"job": dict(job) if job else None}
            if self.path == "/api/result":
                state = data["state"]
                if state not in ("submitted", "failed"):
                    raise ValueError("Invalid result")
                db.execute("UPDATE jobs SET state=? WHERE id=? AND state='claimed'", (state, int(data["id"])))
                return {"ok": True}
            if self.path == "/api/cancel":
                key = data["id"]
                if isinstance(key, str) and key.startswith("gateway-"):
                    job = db.execute("UPDATE protocol_jobs SET state='cancelled' WHERE id=? AND state='queued'", (key,))
                    if not job.rowcount:
                        raise ValueError("This message is no longer scheduled")
                    row = db.execute("SELECT payload FROM protocol_messages WHERE id=?", (key,)).fetchone()
                    if row:
                        message = json.loads(row[0])
                        message["status"] = "Cancelled"
                        db.execute("UPDATE protocol_messages SET payload=? WHERE id=?", (json.dumps(message), key))
                    return {"ok": True}
                job = db.execute("UPDATE jobs SET state='cancelled' WHERE id=? AND state='scheduled'",
                                 (int(data["id"]),))
                if not job.rowcount:
                    raise ValueError("This message is no longer scheduled")
                return {"ok": True}
            raise ValueError("Unknown endpoint")


if __name__ == "__main__":
    if len(TOKEN) < 32:
        raise SystemExit("Set FLUID_TOKEN to a random string of at least 32 characters")
    DB.parent.mkdir(parents=True, exist_ok=True)
    with closing(connect()) as db, db:
        db.executescript("""
            PRAGMA journal_mode=WAL;
            CREATE TABLE IF NOT EXISTS inbox (id INTEGER PRIMARY KEY, content TEXT, updated INTEGER);
            CREATE TABLE IF NOT EXISTS phone_appearance (id INTEGER PRIMARY KEY, content TEXT NOT NULL);
            CREATE TABLE IF NOT EXISTS jobs (
                id INTEGER PRIMARY KEY, address TEXT NOT NULL, body TEXT NOT NULL,
                due INTEGER NOT NULL, state TEXT NOT NULL, protocol TEXT NOT NULL DEFAULT 'sms'
            );
        """)
        if not any(column["name"] == "protocol" for column in db.execute("PRAGMA table_info(jobs)")):
            db.execute("ALTER TABLE jobs ADD COLUMN protocol TEXT NOT NULL DEFAULT 'sms'")
        db.executescript(protocol_gateway.SCHEMA)
    gateway = protocol_gateway.Gateway(connect, protocol_gateway.configured())
    gateway.start()
    ThreadingHTTPServer((os.environ.get("FLUID_BIND", "127.0.0.1"), int(os.environ.get("FLUID_PORT", 8080))), Handler).serve_forever()
