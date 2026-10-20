# Privacy & Security

Agora is a BYOK client. It does not operate a relay for chat completions: requests go from your device to the provider, model server, or tool endpoint you configure.

## Data stored on the device

Conversations, message trees, tasks, loops, memories, prompts, attachments, and tool media are stored in Agora-managed databases/files. Ordinary settings use Android preferences. Secret settings normally use an AES-256-GCM envelope backed by the Android Keystore; legacy plaintext remains readable and encryption failure deliberately stores plaintext rather than losing the value.

Deleting app data or uninstalling the app removes app-managed data unless Android backup or an exported archive preserves it.

A folder connected to an interactive template grants read-only access through Android’s picker.
Template code can browse it and request bounded text/binary reads without an AI call. The bridge
exposes relative paths, not permission URIs or unrestricted storage access. Files selected on Start
use the normal attachment pipeline before the Skill request. See [Templates](templates.md).

## When data leaves the device

Only features you use create their corresponding traffic:

- chat, title, transcription, image, and embedding requests go to their selected model/provider
- web queries go to the selected search provider
- MCP calls go to enabled MCP servers
- Conch or SSH operations go to configured devices
- update checks retrieve release metadata at app startup, at most once per day
- the optional rating form sends only the rating, name, email, and comment you submit to `https://newoether.com/api/rating`
- after a crash, one report is stored locally; on next launch you may explicitly send its stack trace, app/Android version, device manufacturer/model, timestamp, and bounded diagnostic tags to `https://newoether.com/crash`

Crash reports are never uploaded automatically and do not contain conversation text, credentials, or device identifiers. Agora has no general analytics/telemetry path.

## Backups and secrets

A `.agora` export is a ZIP archive. If you explicitly include API keys/secrets, those values are unencrypted inside the archive. Keep that file private and delete copies you no longer need.

## Proxy and transport

The configured network proxy covers shared HTTP-client traffic, not direct SSH, on-device inference, or sandbox process networking. TLS and endpoint trust still matter. Conch application-layer encryption requires an API key; blank-key Conch uses plain JSON and relies on HTTPS for transport confidentiality.

Review the endpoint URLs, server certificates, provider terms, and tool permissions before sending sensitive content.
