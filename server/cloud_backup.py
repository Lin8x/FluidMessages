"""Bounded backup transfers to administrator-configured rclone destinations."""
import json
import os
import re
import shutil
import subprocess
import tempfile
import time
import uuid
from pathlib import Path

TARGETS = ("onedrive", "drive", "s3")
MAX_BYTES = 64 * 1024 * 1024 + 64
NAME = re.compile(r"FluidMessages_[0-9]{13}_[0-9a-f]{32}\.fmb")


def remotes():
    """Only configuration on the server chooses an rclone destination."""
    return {
        key: value.rstrip("/")
        for key in TARGETS
        if (value := os.environ.get(f"FLUID_BACKUP_{key.upper()}", ""))
    }


def rclone(*args):
    return subprocess.run(
        ["rclone", *args], stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
        timeout=180, check=True,
    ).stdout


def upload(remote, source, size):
    if not 5 <= size <= MAX_BYTES:
        raise ValueError("Invalid archive size")
    with tempfile.TemporaryDirectory() as scratch:
        file = Path(scratch) / "backup.fmb"
        with file.open("wb") as output:
            remaining = size
            while remaining:
                chunk = source.read(min(remaining, 128 * 1024))
                if not chunk:
                    raise ValueError("Incomplete archive")
                output.write(chunk)
                remaining -= len(chunk)
        with file.open("rb") as content:
            if content.read(4) != b"FMB1":
                raise ValueError("Invalid archive header")
        name = f"FluidMessages_{int(time.time() * 1000):013d}_{uuid.uuid4().hex}.fmb"
        rclone("copyto", "--immutable", "--", str(file), object_path(remote, name))
        return {"name": name}


def download_latest(remote, handler):
    names = json.loads(rclone("lsjson", "--files-only", "--include", "FluidMessages_*.fmb", "--", remote))
    files = [row for row in names if NAME.fullmatch(row.get("Name", ""))
             and 5 <= row.get("Size", -1) <= MAX_BYTES]
    if not files:
        handler.reply(404, {"error": "No backups at this destination"})
        return
    newest = max(files, key=lambda row: row["Name"])
    with tempfile.TemporaryDirectory() as scratch:
        file = Path(scratch) / "backup.fmb"
        rclone("copyto", "--max-transfer", str(MAX_BYTES), "--",
               object_path(remote, newest["Name"]), str(file))
        size = file.stat().st_size
        if size != newest["Size"] or size > MAX_BYTES:
            raise ValueError("Backup changed during download")
        handler.send_response(200)
        handler.send_header("Content-Type", "application/octet-stream")
        handler.send_header("Content-Length", str(size))
        handler.send_header("Cache-Control", "no-store")
        handler.end_headers()
        with file.open("rb") as content:
            shutil.copyfileobj(content, handler.wfile, length=128 * 1024)


def object_path(remote, name):
    return remote + ("" if remote.endswith(":") else "/") + name
