"""Settings → Running on this server (running.js): what FluidMessages Web keeps going right now, in plain words.

[facts] reads the gateway's live clients, the loops main.py and the gateway start, Tor, the cloud backup destinations,
the phone pairing, and the queues. It changes nothing. [rows] turns those facts into the page's groups and words and
is pure, for the tests. Times stay as numbers ("at") so the browser shows them in its own time zone."""
import threading
import time

LABELS = {"irc": "IRC", "matrix": "Matrix", "xmpp": "XMPP", "email": "Email"}
GROUPS = (("always", "Always running"), ("schedule", "On a schedule"), ("phone", "Waits for your phone"),
          ("off", "Not running"))
BACKUP_NAMES = {"onedrive": "OneDrive", "drive": "Google Drive", "s3": "S3"}
NOTHING = "Nothing is running in the background"


def facts(gateway, db, tls, tor_route, cloud_backup, now=None):
    """What's running now, read from the gateway, threads, and database."""
    now = now or int(time.time() * 1000)
    connections = gateway.connections()
    accounts = []
    for account in gateway.accounts.redacted():
        if "error" in account:
            continue
        entry = gateway.clients.get(account["id"])
        client = entry[1] if entry else None
        kind, every = None, None
        if client is not None:
            interval = getattr(client, "interval", None)
            if interval is not None and hasattr(client, "operation"):
                kind, every = "poll", interval() if callable(interval) else interval
            else:
                kind = "connection"
        status = connections.get(account["id"]) or {}
        accounts.append({"id": account["id"], "protocol": account["protocol"], "name": account["name"], "client": kind,
                         "every": every, "state": status.get("state", ""), "manual": bool(status.get("manual")),
                         "retry_at": status.get("retry_at") or 0})
    threads = sorted({thread.name for thread in threading.enumerate() if thread.is_alive()})
    tor_on = tor_route.enabled()
    row = db.execute("SELECT updated FROM inbox WHERE id=1").fetchone()
    texts = db.execute("SELECT COUNT(*), MIN(due) FROM jobs WHERE state='scheduled'").fetchone()
    chats_later = db.execute("SELECT COUNT(*), MIN(date) FROM protocol_jobs WHERE state='queued' AND date>?", (now,)).fetchone()
    waiting = db.execute("SELECT COUNT(*) FROM protocol_jobs WHERE state='queued' AND date<=?", (now,)).fetchone()[0]
    return {"now": now, "accounts": accounts, "threads": threads,
            "tor": {"on": tor_on, "proxy": "%s:%d" % tor_route.proxy_address(), "reachable": tor_on and tor_route.ready(1)},
            "backups": sorted(cloud_backup.remotes()), "tls": tls["port"] if tls else None,
            "phone": {"paired": row is not None, "last_sync": row[0] if row else None},
            "scheduled": {"texts": texts[0], "texts_next": texts[1], "chats": chats_later[0], "chats_next": chats_later[1]},
            "waiting": waiting}


def _row(group, ident, icon, name, state, why, opens, at=None, at_label=""):
    row = {"group": group, "id": ident, "icon": icon, "name": name, "state": state, "why": why, "opens": opens}
    if at:
        row["at"], row["at_label"] = at, at_label
    return row


def _every(seconds):
    if seconds < 60:
        return "Checks every %d seconds" % seconds
    minutes = round(seconds / 60)
    return "Checks every minute" if minutes == 1 else "Checks every %d minutes" % minutes


def rows(f):
    """The page: {"summary", "count", "groups": [{"id", "title", "rows": [...]}]}."""
    out = []
    threads = set(f["threads"])
    for account in f["accounts"]:
        label = LABELS.get(account["protocol"], account["protocol"])
        name = label + " · " + account["name"]
        ident = "account-" + account["id"]
        state = account["state"]
        if account["client"] is None:
            out.append(_row("off", ident, "account", name, "Not connected",
                            "Its service is off here, or it waits until a chat uses it.", "accounts"))
        elif account["client"] == "poll" and account["protocol"] == "email":
            out.append(_row("schedule", ident, "account", name, _every(account["every"] or 60),
                            "Email has no open connection, so this server asks for new mail.", "accounts"))
        elif state == "disconnected":
            out.append(_row("off", ident, "account", name,
                            "Disconnected · Reconnect automatically is off" if account["manual"] else "Disconnected",
                            "Connect it again from its server’s Connection section.", "accounts"))
        else:
            words = {"connected": "Connected", "connecting": "Connecting…", "reconnecting": "Reconnecting"}.get(state, "Starting…")
            why = ("Checks for new messages every few seconds." if account["client"] == "poll"
                   else "Stays connected so new messages arrive right away, even with this page closed.")
            out.append(_row("always", ident, "account", name, words, why, "accounts",
                            at=account["retry_at"] if state == "reconnecting" else None, at_label="next try at"))

    if "protocol-sender" in threads:
        waiting = f["waiting"]
        out.append(_row("always", "sender", "ways", "Message sender",
                        ("%d waiting to send" % waiting) if waiting else "Waiting for messages to send",
                        "Sends your internet messages from this server as soon as they’re ready.", "messaging"))
    if f["tls"]:
        out.append(_row("always", "tls", "devices", "HTTPS for phones", "Listening on port %d" % f["tls"],
                        "Phones pair and sync over this encrypted port.", "devices"))
    tor = f["tor"]
    if tor["on"]:
        out.append(_row("always", "tor", "privacy", "Tor",
                        "Connected through Tor" if tor["reachable"] else "Tor isn’t answering at " + tor["proxy"],
                        "Chat services and link previews from this server go through Tor."
                        + ("" if tor["reachable"] else " Until it answers, they wait instead of connecting without it."), "tor"))
    else:
        out.append(_row("off", "tor", "privacy", "Tor", "Off", "This server connects directly, not through Tor.", "tor"))

    if "protocol-clients" in threads:
        out.append(_row("schedule", "supervisor", "account", "Connection check", "Checks every 15 seconds",
                        "Starts, restarts, or stops each account’s connection to match your settings.", "accounts"))
    if "protocol-disappearing" in threads:
        out.append(_row("schedule", "disappearing", "privacy", "Disappearing messages", "Checks every 20 seconds",
                        "Deletes messages from this server when their timer runs out.", "privacy"))
    scheduled = f["scheduled"]
    if scheduled["chats"]:
        out.append(_row("schedule", "send-later", "messaging", "Scheduled messages", "%d scheduled" % scheduled["chats"],
                        "Internet messages you set to send later go from this server at their time.", "messaging",
                        at=scheduled["chats_next"], at_label="next at"))

    phone = f["phone"]
    if phone["paired"]:
        out.append(_row("phone", "phone", "devices", "Your phone", "Last synced" if phone["last_sync"] else "Not synced yet",
                        "Your phone checks in on its own schedule, set in Battery and sync on the phone. "
                        "Nothing runs here between check-ins.", "devices", at=phone["last_sync"], at_label="at"))
    else:
        out.append(_row("off", "phone", "devices", "Your phone", "Not connected", "Connect your phone to see its texts here.",
                        "devices"))
    if scheduled["texts"]:
        out.append(_row("phone", "texts-later", "messaging", "Scheduled texts", "%d waiting for your phone" % scheduled["texts"],
                        "Your phone sends texts, so it picks these up when it next checks in.", "messaging",
                        at=scheduled["texts_next"], at_label="next due at"))
    if f["backups"]:
        out.append(_row("phone", "backups", "backups", "Cloud backups",
                        "Ready for " + ", ".join(BACKUP_NAMES.get(key, key) for key in f["backups"]),
                        "Your phone’s backup schedule uploads through this server. No backup runs here on its own timer.",
                        "backups"))
    else:
        out.append(_row("off", "backups", "backups", "Cloud backups", "No destinations set up",
                        "The server’s administrator can add OneDrive, Google Drive, or S3 with rclone.", "backups"))

    count = sum(1 for row in out if row["group"] in ("always", "schedule"))
    groups = [{"id": ident, "title": title, "rows": [row for row in out if row["group"] == ident]} for ident, title in GROUPS]
    return {"count": count, "summary": NOTHING if count == 0 else "1 thing running" if count == 1 else "%d things running" % count,
            "groups": [group for group in groups if group["rows"]], "now": f["now"]}
