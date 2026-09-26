"""Optional messages attached to successful submissions."""

import json
import os

MAX_CONFIG_BYTES = 1024 * 1024


def message_for(package_name):
    path = os.environ.get("AGORA_SUBMISSION_MESSAGES")
    if not path or not isinstance(package_name, str) or not package_name:
        return None
    try:
        with open(path, "rb") as stream:
            raw = stream.read(MAX_CONFIG_BYTES + 1)
        if len(raw) > MAX_CONFIG_BYTES:
            return None
        config = json.loads(raw)
        message = config.get(package_name) if isinstance(config, dict) else None
        if not isinstance(message, dict):
            return None
        result = {}
        for key, limit in (("id", 128), ("title", 200), ("body", 8000)):
            value = message.get(key)
            if not isinstance(value, str) or not value.strip() or len(value.strip()) > limit:
                return None
            result[key] = value.strip()
        button = message.get("buttonText")
        if isinstance(button, str) and 0 < len(button.strip()) <= 80:
            result["buttonText"] = button.strip()
        return result
    except (OSError, ValueError, UnicodeError):
        return None
