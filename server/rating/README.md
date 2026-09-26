# Agora rating submission API

This dependency-free Python service implements only Agora's public rating submission endpoint:

```text
POST /api/rating
OPTIONS /api/rating
```

It stores accepted submissions in SQLite. It contains no read, listing, aggregation, dashboard, or administration endpoint. Every GET request returns `405 Method Not Allowed`.

## Installation

```sh
sudo useradd --system --home /nonexistent --shell /usr/sbin/nologin agora-rating
sudo install -d -o agora-rating -g agora-rating -m 0750 /var/lib/agora-rating
sudo install -d -o root -g root -m 0755 /opt/agora-rating
sudo install -o root -g root -m 0755 agora-rating-api.py /opt/agora-rating/
sudo install -o root -g root -m 0644 agora-rating.service /etc/systemd/system/
sudo install -o root -g root -m 0644 ../submission_messages.py /opt/agora-rating/
sudo systemctl daemon-reload
sudo systemctl enable --now agora-rating
```

Copy `nginx-public.location` into the public TLS virtual host, validate the Nginx configuration, then reload Nginx.

## Configuration

| Variable | Default |
| --- | --- |
| `AGORA_RATING_DB` | `/var/lib/agora-rating/ratings.db` |
| `AGORA_RATING_HOST` | `127.0.0.1` |
| `AGORA_RATING_PORT` | `8091` |

No database, submitted record, host identity, domain, certificate, token, or credential is included.

## Optional submission messages

Install `../submission_messages.py` alongside this service's Python entry point.
Set `AGORA_SUBMISSION_MESSAGES` in the service environment to an administrator-managed,
UTF-8 JSON file outside the checkout. Leave it unset to retain the ordinary success response.

The file maps exact runtime package names to a message object, or `null` to omit a message:

```json
{
  "org.example.app": {
    "id": "feedback-2026-01",
    "title": "Thank You",
    "body": "Your submission has been received.",
    "buttonText": "OK"
  }
}
```

The file is read for each accepted submission, so replacing it atomically updates messages
without restarting the receiver. Missing, malformed, unreadable, or oversized configurations
produce no message and never reject an otherwise successful submission. The file is capped at
1 MiB; id/title/body/buttonText limits are 128/200/8000/80 characters. The first three fields
are required nonblank strings; buttonText is optional. Unknown fields are not returned.

The response's optional `message` object is plain text. Clients display a dismissible dialog
after success and remember displayed IDs locally across rating and crash submissions. Use a
new ID for a new message. No message causes the existing success presentation.
Package metadata is client-supplied routing information, not an authentication mechanism.
