import importlib.util
import json
from contextlib import closing
import os
from pathlib import Path
import sys
import tempfile
import threading
import unittest
from http.client import HTTPConnection
from unittest.mock import patch

ROOT = Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT))
from submission_messages import message_for


def load(name, relative):
    spec = importlib.util.spec_from_file_location(name, ROOT / relative)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


rating = load("rating_receiver", "rating/agora-rating-api.py")
crash = load("crash_receiver", "crash/agora-crash.py")


class SubmissionMessagesTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.config = self.root / "messages.json"
        self.env = patch.dict(os.environ, {"AGORA_SUBMISSION_MESSAGES": str(self.config)})
        self.env.start()
        self.message = {"id": "reply-1", "title": "Thank You", "body": "Received", "buttonText": "OK"}
        self.config.write_text(json.dumps({"org.example.mobile": self.message}), encoding="utf-8")
        rating.DB_PATH = str(self.root / "ratings.db")
        crash.LOG_FILE = str(self.root / "crashes.jsonl")
        crash._recent.clear()

    def tearDown(self):
        self.env.stop()
        self.temp.cleanup()

    def post(self, module, handler, path, body):
        server_type = getattr(module, "ThreadedHTTPServer", None) or module.ThreadingHTTPServer
        server = server_type(("127.0.0.1", 0), handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            connection = HTTPConnection("127.0.0.1", server.server_port, timeout=5)
            connection.request("POST", path, json.dumps(body), {"Content-Type": "application/json"})
            response = connection.getresponse()
            result = response.status, response.read()
            connection.close()
            return result
        finally:
            server.shutdown()
            server.server_close()
            thread.join()

    def test_exact_match_and_disabled_or_invalid_config(self):
        self.assertEqual(self.message, message_for("org.example.mobile"))
        self.assertIsNone(message_for("org.example.mobile.extra"))
        for content in ["{}", "null", "invalid", json.dumps({"org.example.mobile": None}),
                        json.dumps({"org.example.mobile": {"id": 1, "title": "x", "body": "y"}}),
                        " " * (1024 * 1024 + 1)]:
            self.config.write_text(content, encoding="utf-8")
            self.assertIsNone(message_for("org.example.mobile"))
        self.config.unlink()
        self.assertIsNone(message_for("org.example.mobile"))

    def test_rating_stores_submission_and_returns_optional_message(self):
        status, raw = self.post(rating, rating.RatingHandler, "/api/rating",
                                {"rating": 4, "app": "org.example.mobile"})
        self.assertEqual(200, status)
        self.assertEqual(self.message, json.loads(raw)["message"])
        with closing(rating.connect_db()) as conn:
            self.assertEqual((4, "org.example.mobile"),
                             conn.execute("SELECT rating, app FROM ratings").fetchone())
        status, raw = self.post(rating, rating.RatingHandler, "/api/rating",
                                {"rating": 3, "app": "org.example.other"})
        self.assertEqual({"ok": True}, json.loads(raw))
        status, raw = self.post(rating, rating.RatingHandler, "/api/rating",
                                {"rating": 0, "app": "org.example.mobile"})
        self.assertEqual(400, status)
        self.assertNotIn("message", json.loads(raw))

    def test_crash_retains_package_and_legacy_no_message_response(self):
        status, raw = self.post(crash, crash.Handler, "/crash",
                                {"trace": "example", "packageName": "org.example.mobile", "unknown": "ignored"})
        self.assertEqual(200, status)
        self.assertEqual(self.message, json.loads(raw)["message"])
        record = json.loads(Path(crash.LOG_FILE).read_text())
        self.assertEqual("org.example.mobile", record["packageName"])
        self.assertNotIn("unknown", record)
        status, raw = self.post(crash, crash.Handler, "/crash", {"trace": "legacy"})
        self.assertEqual((204, b""), (status, raw))
        self.config.write_text("invalid", encoding="utf-8")
        status, raw = self.post(crash, crash.Handler, "/crash",
                                {"trace": "example", "packageName": "org.example.mobile"})
        self.assertEqual((204, b""), (status, raw))


if __name__ == "__main__":
    unittest.main()
