# Voice Typing

Open **Settings → Interaction → Voice Typing** and enable the switch. It is off by default.
A microphone with a circular outline appears beside Send.

Tap the microphone, allow microphone permission if Android asks, and speak. Tap Stop to finish;
while a result is pending, tap Cancel to discard it. Recognised text is inserted at the cursor or
replaces the selected text. You can edit it before sending. Dictation never sends a message itself.
Android recognition shows live text while you speak when the selected service supplies partial
results. Revisions update the same words without duplicating them. If you edit the text or move the
cursor, further recognition results leave your edits alone. Cancel retains text already displayed.
Switching chats or leaving the app cancels active dictation. API engines return text after Stop.

## Choose a speech service

- **Android / Google Voice Typing** uses the recognition service selected on your device, including
  Google when installed and selected. It works without opening the keyboard. The service may use
  the network according to its own settings.
- **Android On-Device Recognition** requires an available offline recognizer on Android 12 or newer.
  If it is unavailable, choose another engine or install the device's required speech resources.
- **OpenAI** and **Groq** record a short clip and upload it when you stop. They reuse an existing key
  from the matching provider. You can select another matching saved key and change the model.
- **OpenAI-Compatible Endpoint** supports servers implementing the multipart audio transcription
  endpoint with a JSON `text` response. Set the base URL, model and, if needed, an existing key.
  Custom endpoints do not automatically receive a provider key.

Add or manage credentials in [Providers](provider.md). A transcription model must be supported by
that provider and account; ordinary text-chat models cannot necessarily transcribe audio.

The optional language field accepts a code such as `en`. Leave it blank to use the service default.
API recordings stop after two minutes and are deleted after use or cancellation. Only the clip and
transcription settings are uploaded, without chat history, skills or templates. See
[Privacy & Security](privacy.md).
