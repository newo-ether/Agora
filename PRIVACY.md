# Privacy Policy

**Last updated: October 19, 2026**

Agora is a BYOK Android client. It does not operate a relay for chat completions: model requests go from your device to the provider or endpoint you configure.

## Local data

Conversations, message trees, tasks, loops, memories, prompts, attachments, tool media, settings, and imported models are stored in app-managed databases/files on the device. Secret settings normally use an AES-256-GCM envelope backed by the Android Keystore. Legacy values are accepted as plaintext, and encryption failure deliberately falls back to plaintext rather than losing the value, so device storage protection remains important.

When you connect a folder to an interactive template through Android’s folder picker, Agora
retains read-only access to that folder. Template JavaScript can list its files and request bounded
text or binary reads through a native bridge. It does not receive permission URIs or unrestricted
filesystem access. These native file operations do not call an AI provider; files selected for a
Skill request are processed as ordinary attachments before that request.

Clearing app data or uninstalling removes app-managed data unless Android backup or a user-created export preserves it.

## Network destinations

Data leaves the device only through features you use:

- messages and attachments go to the selected AI provider;
- title, transcription, image-generation, and embedding requests go to their selected providers;
- search queries go to the selected web-search service;
- MCP calls go to enabled MCP servers;
- Conch and SSH operations go to configured remote devices;
- release metadata can be checked when the app starts, at most once per day;
- the optional rating form sends only the rating, name, email, and comment you explicitly submit to `https://newoether.com/api/rating`;
- after a crash, one pending report is stored locally and the next launch asks whether to send it to `https://newoether.com/crash`. It contains the stack trace, app/Android version, device manufacturer/model, timestamp, and bounded diagnostic event tags, but no conversation text, credentials, or device identifiers.

Crash reports are never submitted automatically. Agora does not include a general analytics path. Third-party endpoints have their own privacy and retention policies.

## Backups and exports

A `.agora` export is a ZIP archive. If you explicitly include API keys or other secrets, those values are unencrypted inside the archive. Protect and delete exported copies as appropriate.

## Transport and proxy

The configured proxy applies to Agora's shared HTTP-client traffic, not direct SSH, local inference, or processes inside the Alpine sandbox. Conch application-layer encryption requires an API key; a blank-key endpoint uses plain JSON and relies on HTTPS for transport confidentiality.

## Permissions

- **Internet**: provider, search, MCP, update, rating, and remote HTTP connections.
- **Notifications / foreground service**: ongoing generation, automation, and user-visible completion behavior.
- **Files and media**: only when you select/import attachments, models, backups, fonts, or shared sandbox storage.
- **Exact alarms**: optional automation scheduling where supported and explicitly enabled.

## Children, changes, and contact

Agora is not directed to children under 13. This policy may be updated with the repository/application. Questions can be opened at [github.com/newo-ether/Agora](https://github.com/newo-ether/Agora).

## Interactive templates

Templates and shortcut mappings are stored locally and included only in the selected native backup
category. Opening a saved interface locally makes no model request. Confirmed follow-up inputs and
linked Skill instructions use the normal provider flow. Template code stays within the existing
interactive sandbox and is excluded from model-facing template history.
