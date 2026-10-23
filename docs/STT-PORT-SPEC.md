# On-device STT port (sherpa-onnx) — follow-up scope

Status: **Not started.** Groundwork (vendored ORT 1.28.2, assistant RecognitionService
stub) is in place; the actual speech-to-text engine, model, permission, and UI entry are
not yet ported from AIOPE.

## Goal
Replace Agora's stub `AgoraRecognitionService` with a real, fully-offline streaming
speech-to-text implementation backed by sherpa-onnx, matching the AIOPE implementation.
This adds voice input to the Layer-1 assistant and (optionally) a chat mic button, with no
network dependency.

## Current state in Agora (verified)
- `app/src/main/java/com/newoether/agora/assistant/AgoraRecognitionService.kt` — intentional
  stub; returns `ERROR_RECOGNIZER_BUSY`. Comment already notes "offline STT (sherpa-onnx, as
  in AIOPE) can replace this later."
- `app/src/main/res/xml/voice_interaction_service.xml` / `recognition_service.xml` — reference
  the stub; manifest declares `.assistant.AgoraRecognitionService` with the
  `android.speech.RecognitionService` intent filter.
- `app/build.gradle.kts` — vendors self-built **ONNX Runtime 1.28.2** (`libonnxruntime.so` +
  `libonnxruntime4j_jni.so`, arm64-v8a) and `app/libs/onnxruntime-1.28.2.jar`, explicitly
  "version-matched to sherpa-onnx's bundled core so a future STT dependency shares ONE
  libonnxruntime.so in-process" (`pickFirsts += "**/libonnxruntime.so"`).
- **Missing:** `RECORD_AUDIO` permission, sherpa-onnx dependency, STT engine, model bootstrap,
  and any UI entry point.

## Reference implementation in AIOPE (directly portable)
- `core-inference/.../SherpaSttEngine.kt` — streaming zipformer transducer wrapper
  (`OnlineRecognizer`): `start/accept/partialText/isEndpoint/reset/close`, 16 kHz mono float PCM.
- `app/.../AiopeRecognitionService.kt` — real `RecognitionService`: `AudioRecord`
  (`VOICE_RECOGNITION`, 16 kHz mono PCM16 -> float), streams partial/final results via the
  platform `Callback` contract (`readyForSpeech`/`beginningOfSpeech`/`partialResults`/`results`
  /`endOfSpeech`/`error`). One utterance per session.
- `core-terminal/.../SherpaSttBootstrap.kt` — downloads + extracts the int8 streaming zipformer
  English model (encoder/decoder/joiner.onnx + tokens.txt) as a tar.gz into
  `filesDir/models/sherpa-stt`, with install marker, size guards, and a pure-Kotlin tar reader.
  Model: `XNet-NGO/deps` release `sherpa-stt-en-kroko` (~52 MB, Apache-2.0, k2-fsa asr-models).
- `feature-chat/.../voice/VoiceInputManager.kt` — drives STT **directly** (not via the system
  recognizer), important for GrapheneOS where no system voice service may be selected.
- Gradle: `com.github.k2-fsa.sherpa-onnx:sherpa-onnx:v1.13.8` (`libs.versions.toml`).

## Port tasks (scoped)
1. **Dependency + native libs.** Add sherpa-onnx (`v1.13.8`) via its Java/JNI jar; ensure its
   `libonnxruntime.so` is deduped against Agora's vendored 1.28.2 (`pickFirst` already present).
   Verify ABI (arm64-v8a only) and the `onnxruntime-genai` coexistence (both dlopen the vendored
   ORT via `OrtGetApiBase`; confirm no second `libonnxruntime.so` sneaks in).
2. **Permission.** Add `<uses-permission android:name="android.permission.RECORD_AUDIO" />`;
   runtime-request it from the assistant onboarding and/or the chat mic entry.
3. **Engine.** Port `SherpaSttEngine.kt` into `com.newoether.agora` (e.g. `api/` or a new
   `stt/` package). Pure port; no behavioral change.
4. **Model bootstrap.** Port `SherpaSttBootstrap.kt` (package-renamed). Decide hosting: reuse the
   `XNet-NGO/deps` release or mirror. Add a Settings entry to download/remove the ~52 MB model
   with progress (mirrors the embedding-model download UX).
5. **RecognitionService.** Replace `AgoraRecognitionService` stub body with the AIOPE
   `AiopeRecognitionService` logic (AudioRecord capture loop + Callback contract). Keep the class
   name/manifest entry.
6. **Direct driver (recommended).** Port `VoiceInputManager` so voice input works even when no
   system recognizer is selected (GrapheneOS). Wire it to the assistant turn
   (`AssistantController.runOnce(requestKind="assistant")`) and/or a chat composer mic button.
7. **Settings/onboarding.** Add an STT toggle + model-install row under Settings > Assistant (or
   a new Voice section). Gate the mic UI on model-installed + permission-granted.
8. **Verification.** `compileFdroidDebugKotlin` + `verifyKotlinFileSize`; unit-test the
   PCM16->float conversion and endpoint/reset flow; on-device smoke test (GrapheneOS Pixel).
   Confirm single `libonnxruntime.so` in the APK (`unzip -l` the APK's `lib/arm64-v8a`).

## Risks / decisions
- **Native lib collisions:** sherpa bundles its own ORT; Agora vendors 1.28.2. Must resolve to
  exactly one `libonnxruntime.so` or the app crashes at load. The build already sets `pickFirst`,
  but verify the chosen lib is ABI/ORT-compatible with sherpa `v1.13.8`.
- **Model hosting/licensing:** confirm redistribution terms for the Kroko/k2-fsa model and whether
  to host under XNet-NGO or ship in-APK (size budget).
- **F-Droid:** downloaded model is fine (not bundled binary), but confirm no proprietary blobs.
- **Scope:** Layer-1 assistant is gesture-invoked today; STT is additive (voice invoke/dictation),
  not a prerequisite. Keep it behind a user toggle.
