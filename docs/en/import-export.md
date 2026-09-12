# Import & Export

Open **Settings → Import & Export** to move or back up Agora data.

## Agora Archives

A `.agora` file is a versioned ZIP archive (currently format version 4). Depending on the selected categories it can contain:

- conversations, runs, messages, Tasks, Loops, and related graph data;
- attachments, tool media, and draft media;
- active memory, saved memories, and Skills;
- system prompts;
- application settings (including UI preferences and Automation configurations like Daemon, SMS polling, Heartbeat, and Notification whitelist), and an imported custom font;
- provider API keys and other secrets, only when explicitly selected.

!!! warning "Protect archives that contain secrets"
    Included secrets are stored unencrypted inside the archive. Store and transfer that file as carefully as the original credentials.

Before a native import, Agora loads a preview and lists the categories present. Choose **Merge**, **Replace**, or **Skip** independently for conversations, memories, system prompts, settings, and API keys. An archive version the installed app does not support displays an error and disables the Import confirmation.

Native preview loading, import, and export use non-cancelable circular progress dialogs labeled **Loading…**, **Importing…**, and **Exporting…** respectively.

## ChatGPT and Claude Imports

ChatGPT and Claude export ZIP files can be imported directly. Preview the export and choose which conversations to import.

- **Merge** keeps other existing conversations and imports the selected conversations.
- **Replace** deletes all existing conversations and keeps only the selected imported conversations. Agora shows a second destructive confirmation before it starts.

These imports use a non-cancelable percentage-based linear progress dialog. Claude attachment records may contain metadata without the original attachment bytes, depending on the source export.

## Automatic Backup

WorkManager can create periodic backups with a selected destination, schedule, category set, and retention policy. Android background scheduling is best-effort; battery policy and storage access can affect timing.

See [Privacy & Security](privacy.md).
