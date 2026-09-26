#!/usr/bin/env python3
"""Public submission-only rating receiver for Agora."""

import json
from contextlib import closing
import os
import sys
from pathlib import Path
import sqlite3
import socketserver
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, HTTPServer
from urllib.parse import urlparse

sys.path.append(str(Path(__file__).resolve().parent.parent))
from submission_messages import message_for

DB_PATH = os.environ.get("AGORA_RATING_DB", "/var/lib/agora-rating/ratings.db")
LISTEN_ADDR = os.environ.get("AGORA_RATING_HOST", "127.0.0.1")
LISTEN_PORT = int(os.environ.get("AGORA_RATING_PORT", "8091"))
MAX_CONTENT_LENGTH = 64 * 1024
MAX_COMMENT_LENGTH = 10_000


def connect_db():
    conn = sqlite3.connect(DB_PATH)
    conn.execute(
        """CREATE TABLE IF NOT EXISTS ratings (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            rating INTEGER NOT NULL CHECK(rating >= 1 AND rating <= 5),
            app TEXT DEFAULT '',
            name TEXT DEFAULT '',
            email TEXT DEFAULT '',
            comment TEXT DEFAULT '',
            created_at TEXT NOT NULL
        )"""
    )
    return conn


class RatingHandler(BaseHTTPRequestHandler):
    server_version = "agora-rating/1.0"

    def do_OPTIONS(self):
        if self._path() != "/api/rating":
            self._send_json(404, {"error": "not found"})
            return
        self._send_empty(204)

    def do_POST(self):
        if self._path() != "/api/rating":
            self._send_json(404, {"error": "not found"})
            return

        try:
            length = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            self._send_json(400, {"error": "invalid content length"})
            return
        if length <= 0:
            self._send_json(400, {"error": "empty request"})
            return
        if length > MAX_CONTENT_LENGTH:
            self._send_json(413, {"error": "request too large"})
            return

        try:
            body = json.loads(self.rfile.read(length).decode("utf-8"))
        except (ValueError, UnicodeDecodeError):
            self._send_json(400, {"error": "invalid json"})
            return
        if not isinstance(body, dict):
            self._send_json(400, {"error": "json object required"})
            return

        rating = body.get("rating")
        if isinstance(rating, bool) or not isinstance(rating, int) or not 1 <= rating <= 5:
            self._send_json(400, {"error": "rating must be an integer from 1 to 5"})
            return

        app = self._text(body.get("app"), 64)
        name = self._text(body.get("name"), 100)
        email = self._text(body.get("email"), 254)
        comment = self._text(body.get("comment"), MAX_COMMENT_LENGTH)

        try:
            with closing(connect_db()) as conn, conn:
                conn.execute(
                    """INSERT INTO ratings
                       (rating, app, name, email, comment, created_at)
                       VALUES (?, ?, ?, ?, ?, ?)""",
                    (
                        rating,
                        app,
                        name,
                        email,
                        comment,
                        datetime.now(timezone.utc).isoformat(),
                    ),
                )
        except sqlite3.Error:
            self._send_json(500, {"error": "storage failure"})
            return

        response = {"ok": True}
        message = message_for(app)
        if message is not None:
            response["message"] = message
        self._send_json(200, response)

    def do_GET(self):
        self._send_json(405, {"error": "method not allowed"})

    def _path(self):
        return urlparse(self.path).path.rstrip("/") or "/"

    @staticmethod
    def _text(value, limit):
        return value.strip()[:limit] if isinstance(value, str) else ""

    def _cors_headers(self):
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "POST, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type")

    def _send_empty(self, status):
        self.send_response(status)
        self._cors_headers()
        self.send_header("Content-Length", "0")
        self.end_headers()

    def _send_json(self, status, data):
        body = json.dumps(data, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self._cors_headers()
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, fmt, *args):
        pass


class ThreadedHTTPServer(socketserver.ThreadingMixIn, HTTPServer):
    allow_reuse_address = True
    daemon_threads = True


def main():
    os.makedirs(os.path.dirname(DB_PATH), exist_ok=True)
    server = ThreadedHTTPServer((LISTEN_ADDR, LISTEN_PORT), RatingHandler)
    server.serve_forever()


if __name__ == "__main__":
    main()
