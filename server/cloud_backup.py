"""Bounded backup transfers to administrator-configured rclone destinations."""
import json
import os
import re
import shutil
import subprocess
import tempfile
import uuid
from pathlib import Path
from datetime import datetime, timezone

TARGETS = ("onedrive", "drive", "s3")
MAX_BYTES = 64 * 1024 * 1024 + 64
NAME = re.compile(r"[A-Za-z0-9][A-Za-z0-9 _.()-]{0,79}\.fmb")


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


def upload(remote, source, size, fixed_name=None):
    if fixed_name is not None and not valid_name(fixed_name):
        raise ValueError("Invalid backup file name")
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
        timestamp = datetime.now(timezone.utc).strftime("%Y-%m-%d_%H-%M-%S-%f")
        name = fixed_name or f"FluidMessages_{timestamp}_{uuid.uuid4().hex[:8]}.fmb"
        rclone("copyto", "--ignore-times" if fixed_name else "--immutable", "--", str(file), object_path(remote, name))
        return {"name": name}


def valid_name(name):
    return NAME.fullmatch(name) is not None and not name[:-4].endswith(".")


def modification(row):
    try:
        return datetime.fromisoformat(row.get("ModTime", "").replace("Z", "+00:00")).timestamp()
    except (TypeError, ValueError, OverflowError):
        return 0


def download_latest(remote, handler):
    entries = json.loads(rclone("lsjson", "--files-only", "--include", "*.fmb", "--", remote))
    files = [row for row in entries if valid_name(row.get("Name", "")) and 5 <= row.get("Size", -1) <= MAX_BYTES]
    files.sort(key=lambda row: (modification(row), row["Name"]), reverse=True)
    with tempfile.TemporaryDirectory() as scratch:
        file = Path(scratch) / "backup.fmb"
        for row in files:
            file.unlink(missing_ok=True)
            rclone("copyto", "--max-transfer", str(MAX_BYTES), "--", object_path(remote, row["Name"]), str(file))
            size = file.stat().st_size
            if size != row["Size"] or size > MAX_BYTES:
                raise ValueError("Backup changed during download")
            with file.open("rb") as content:
                if content.read(4) != b"FMB1":
                    continue
                handler.send_response(200)
                handler.send_header("Content-Type", "application/octet-stream")
                handler.send_header("Content-Length", str(size))
                handler.send_header("Cache-Control", "no-store")
                handler.end_headers()
                content.seek(0)
                shutil.copyfileobj(content, handler.wfile, length=128 * 1024)
                return
    handler.reply(404, {"error": "No backups at this destination"})


def object_path(remote, name):
    return remote + ("" if remote.endswith(":") else "/") + name
