#!/usr/bin/env python3
"""Agora anonymous crash-report receiver.

Dependency-free (Python standard library only). Listens on a loopback port and is
reverse-proxied by nginx at https://newoether.com/crash. Accepts a single JSON
POST per crash and appends a sanitized record to a JSONL log.

Privacy: only the fields the client sends (stack trace + coarse, non-identifying
environment data) are stored. The client IP is intentionally NOT recorded, to match
the in-app promise that no other information is collected.
"""
import json
import os
import sys
from pathlib import Path
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

sys.path.append(str(Path(__file__).resolve().parent.parent))
from submission_messages import message_for

HOST = "127.0.0.1"
PORT = 8092
MAX_BODY = 64 * 1024              # bytes; mirrors nginx client_max_body_size
MAX_STR = 64 * 1024              # per-field char cap
MAX_LOG_BYTES = 50 * 1024 * 1024  # rotate crashes.jsonl past this size
RATE_LIMIT = 60                   # max accepted reports per ROLLING window
RATE_WINDOW = 60.0                # seconds

DATA_DIR = "/var/lib/agora-crash"
LOG_FILE = os.path.join(DATA_DIR, "crashes.jsonl")
ALLOWED_FIELDS = (
    "trace", "packageName", "appVersion", "versionCode",
    "androidApi", "androidRelease", "device", "ts",
)

_recent = []  # timestamps of recently accepted reports (rolling rate limit)


def _rate_ok():
    now = time.time()
    cutoff = now - RATE_WINDOW
    while _recent and _recent[0] < cutoff:
        _recent.pop(0)
    if len(_recent) >= RATE_LIMIT:
        return False
    _recent.append(now)
    return True


def _rotate_if_needed():
    try:
        if os.path.exists(LOG_FILE) and os.path.getsize(LOG_FILE) > MAX_LOG_BYTES:
            os.replace(LOG_FILE, LOG_FILE + ".1")
    except OSError:
        pass


def _sanitize(data):
    clean = {}
    for k in ALLOWED_FIELDS:
        if k not in data:
            continue
        v = data[k]
        if isinstance(v, str):
            clean[k] = v[:MAX_STR]
        elif isinstance(v, bool):
            clean[k] = v
        elif isinstance(v, (int, float)):
            clean[k] = v
    clean["received_at"] = int(time.time())
    return clean


class Handler(BaseHTTPRequestHandler):
    server_version = "agora-crash/1.0"

    def _send(self, code, payload=None):
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8") if payload is not None else b""
        self.send_response(code)
        if payload is not None:
            self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        if body:
            self.wfile.write(body)

    def do_GET(self):
        # Lightweight health check.
        self._send(200)

    def do_POST(self):
        try:
            length = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            return self._send(400)
        if length <= 0 or length > MAX_BODY:
            return self._send(413)
        if not _rate_ok():
            return self._send(429)
        raw = self.rfile.read(length)
        try:
            data = json.loads(raw.decode("utf-8"))
        except (ValueError, UnicodeDecodeError):
            return self._send(400)
        if not isinstance(data, dict):
            return self._send(400)
        record = _sanitize(data)
        try:
            _rotate_if_needed()
            with open(LOG_FILE, "a", encoding="utf-8") as f:
                f.write(json.dumps(record, ensure_ascii=False) + "\n")
        except OSError:
            return self._send(500)
        message = message_for(data.get("packageName"))
        if message is None:
            self._send(204)
        else:
            self._send(200, {"ok": True, "message": message})

    def log_message(self, *args):
        pass  # stay quiet; systemd journal would otherwise fill with access lines


def main():
    os.makedirs(DATA_DIR, exist_ok=True)
    ThreadingHTTPServer((HOST, PORT), Handler).serve_forever()


if __name__ == "__main__":
    main()
