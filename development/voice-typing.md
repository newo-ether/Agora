# Voice Typing

Status: authoritative contract for optional Android composer dictation.

## Behavior and ownership

- Settings → Interaction owns the default-off Voice Typing switch. Enabling it adds one microphone
  immediately beside Send with a 1 dp circular outline. Both controls reserve the shared 48 dp
  composer control size, with an 8 dp gap so their outlines and touch bounds remain separate. Android's selected recognition service is the default engine; the device
  may select Google. This uses SpeechRecognizer, not a keyboard's private microphone action.
- The compact SettingsGroup reuses shared settings controls. Engine, existing provider key, endpoint,
  model and optional language apply immediately. API-only fields appear only for API engines.
- A microphone tap requests Android microphone permission when necessary, then starts one session
  without showing the keyboard. Stop finishes recognition; Cancel discards pending recognition while retaining text already visible.
- Android requests partial results and displays each hypothesis in the draft as it arrives. Revised
  hypotheses and the final result replace the same dictated span without duplication. Manual edits
  or cursor movement stop further revisions; cancelled sessions retain already-visible text.
  Services may omit partial callbacks; API clip transcription still returns text after Stop.
- Final text replaces the current draft selection (or its live dictated span). It never sends a message, admits a generation,
  attaches a recording, or adds context to an AI request. Send is unavailable during dictation;
  an already-running generation can still be stopped.
- ChatBottomBar keys VoiceTypingButton by composer owner and New Chat entry identity. Switching
  drafts, freezing submission, disabling/changing the setting, disposal or leaving the foreground
  cancels the session. Generation guards reject stale service/network callbacks. VoiceDraftTarget
  independently rejects insertion into a cancelled or unavailable draft.

Remote/Pi chats reuse the same VoiceTypingButton, shared setting and speech engines.
The control is keyed to the selected Remote draft owner and writes only that owner's
TextFieldState. Navigation, unavailable ownership and submission cancel recording through
the existing component. Dictation blocks Send, never auto-submits, and keeps an existing
remote turn's Stop action available. No separate Remote voice setting or recorder is added.

The side-effect boundaries are AndroidVoiceRecognizer (main-thread platform recognizer lifecycle),
ApiVoiceRecorder (private temporary recording and upload lifecycle), and AudioTranscriptionClient
(cancellable speech-only HTTP request). VoiceTypingSettingsStore owns atomic preference updates
without growing the general SettingsRepository. VoiceTypingConfig is the portable settings record;
VoiceDraftTarget owns the single guarded text mutation. None owns conversation execution or Room.

## Engines and credentials

- Android mode uses the selected installed recognition service. Its own network/offline policy
  applies. Explicit on-device mode requires Android 12+ and an available on-device recognizer;
  failure never silently falls back to cloud recognition.
- OpenAI defaults to `gpt-4o-mini-transcribe`; Groq to `whisper-large-v3-turbo`. Both use multipart
  `/audio/transcriptions` with file, model, JSON response format and optional language. Compatible
  mode implements the same protocol with an editable endpoint/model; no speculative adapter layer.
- Fixed provider modes reuse only matching existing API key records, preferring the active key.
  Compatible mode requires explicit selection to reuse a credential and also supports no-key local
  endpoints. Settings stores only the key ID. A missing explicitly selected credential fails rather
  than falling back to another key.
- API calls use the shared HTTP client's proxy configuration, disable redirects, require HTTPS for
  remote hosts and permit HTTP only on loopback. Errors never expose credentials or response bodies.
  A user-edited endpoint receives the selected key; Compatible mode never automatically selects a credential.

## Data, cancellation and portability

API mode records AAC/M4A in app-private cache for at most two minutes/20 MiB. Stop uploads that clip
only; no history, skills, templates or prompt are included. Completion/failure/cancellation deletes
the clip. HTTP cancellation follows coroutine cancellation. Process death can leave a private cache
file for Android to reclaim; no durable recording library or background recorder is created.

PortableSettingsArchive includes the secret-free `voiceTypingConfig`. Old archives omit it without
changing a Merge target; Replace removes the preference and returns to default-off. Credentials
remain governed by the existing opt-in API Keys archive category. A key ID absent on the destination
must be selected again. No audio/transcript enters portable Settings.

## Verification

Focused tests cover default-off and invalid configuration, provider-key boundaries, endpoint
validation, selection-aware insertion, cancelled/frozen draft non-mutation, Settings controls and
hidden credentials, multipart speech requests and redirect refusal. Portable settings tests cover
export, restore and Replace reset. Run both F-Droid and Play unit suites and source-size validation;
build the ARM64 release for installation separately. No live provider call is needed for these tests.
Physical microphone quality, Google-service availability and device UI acceptance belong to the user.

## Platform references

- [Android SpeechRecognizer](https://developer.android.com/reference/android/speech/SpeechRecognizer)
- [OpenAI transcription API](https://developers.openai.com/api/reference/resources/audio/subresources/transcriptions/methods/create)
- [Groq speech-to-text](https://console.groq.com/docs/speech-to-text)

## 20 October 2026 — OpenAI mini transcription verification

The OpenAI engine is labelled GPT-4o mini transcribe and selects `gpt-4o-mini-transcribe` by
default. A local HTTP protocol fixture verifies that exact model ID, multipart recording and
`response_format=json`, matching the [official transcription API](https://developers.openai.com/api/reference/resources/audio/subresources/transcriptions/methods/create).
OpenAI lists an estimated $0.003/minute (about $0.18/hour) in its
[pricing table](https://developers.openai.com/api/docs/pricing). Prices are documentation,
not hardcoded UI promises. Existing saved engine choices are not silently changed.
No paid request or microphone recording was used for automated verification.
