# File Server

keywords: file server, fileserver, share files, LAN, local network, HTTP, HTTPS, self-signed certificate, PIN, upload, download, foreground service, port 8080, directory listing, scoped storage

The file server shares a folder from your device over your local network, so other devices on the same Wi-Fi can browse, download, and upload files from a web browser. It runs as a foreground service with its own lightweight HTTP/1.1 server, optionally over HTTPS, with an optional PIN gate.

Open it from **Settings → Network → File Server**.

## Starting the server

1. Pick a **shared directory** — type a path, or tap **Browse…** to choose a folder.
2. Set the **port** (default `8080`).
3. Optionally enable **HTTPS** and/or set a **PIN**.
4. Tap **Start Server**.

While running, the settings page and an ongoing notification show the server URL (e.g. `http://192.168.1.42:8080`). Open that URL in a browser on another device on the same network. Tap **Stop Server** (or the notification's Stop action) to shut it down.

The URL uses the device's detected LAN address. The server keeps Wi-Fi awake while it runs so it stays reachable with the screen off.

## Browsing and uploading

- The browser shows a directory listing with folders first, file sizes, and a parent-folder link.
- Click a file to open or download it.
- Use the **Upload** form at the bottom of any folder page to send files from the other device. Uploads are streamed to disk, up to a **2 GB** cap per request.

## HTTPS

Enabling HTTPS serves over TLS using a **self-signed certificate** generated fresh each time the server starts. Because it is self-signed, the browser will show a security warning the first time; accept it to proceed. It is not a certificate authority–trusted cert and is not pinned.

## PIN access

Setting a PIN (up to 8 digits) gates every request behind a login page. After entering the PIN once, access is remembered via a cookie for that session.

The PIN is a lightweight access gate for a trusted LAN, **not** strong authentication: it is a single shared secret, and over plain HTTP it (and the session cookie) travel in cleartext. Use HTTPS and a PIN together, and only run the server on networks you trust.

## Limitations

- **Writes follow Android scoped storage.** Agora can read most folders you pick but can only *write* (accept uploads) into folders it owns — its own app storage or ones it created. Uploading into a folder owned by another app (for example a folder created by a different app under shared storage) is rejected with a clear message instead of succeeding. Downloads/listing are unaffected.
- **Full-file downloads only** — there is no resume/range support, so a GET returns the whole file from the start.
- **No connection limit** — intended for a few devices on a trusted LAN, not public exposure.
- Path traversal is blocked by stripping `.`/`..` segments from request paths.

See also [Privacy & Security](privacy.md) and [Network Scanner](scanner.md).
