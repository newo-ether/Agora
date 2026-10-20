# Notes sharing boundary — 18 October 2026

Base: `a0b0c75d`, `codex/combined-pi-update`, `the combined Agora checkout`.

The owner retired Agora's dedicated Kalamos Notes integration. Notes owns its processing and sends
images, Markdown text and files to Agora through the standard Android share sheet.

Removed the Notes attachment-menu commands, editor/inbox activities, launch gate, proposal and
handwriting tools, generation bindings, automatic return, answer/attachment editor actions,
integration preferences and portable settings, plus background worker/receiver/replay/store.
Removed feature-only tests and updated discovery, payload and settings tests to the new boundary.

Ordinary sharing still uses ShareTextActivity, SharedTextInbox/SharedFileInbox and the existing
Composer ownership/CAS path. KOReader excerpt metadata and source covers remain. The Room schema,
conversations, historical tool results and saved files are unchanged. Legacy nullable attachment
metadata and narrow cover/archive readers preserve old notebook bundles and thumbnails, without
registering any foreign editor action or processing entry point. There is no startup purge or scan.

Focused regression coverage:

- Real merged Android manifest: text, single/multiple images and EPUB still resolve to the share
  receiver; retired Notes activities and processing/replay receivers are absent.
- Actual default tool executor exposes no Notes tools or discovery category. Legacy Notes metadata
  grants no tool capability; ordinary discovery retains turn/path/permission isolation.
- Old draft metadata cannot inject a Notes prompt or page images. Generic image/PDF/file/video
  ordering and settled Composer submission ownership continue through existing tests.
- Retired settings are omitted on export and ignored on import without changing other preferences.
- Legacy bundle bytes, cover, text and identity round-trip through archive policy. Saved cover and
  page-image fallbacks remain readable. Existing generic sharing, thumbnail and retry tests remain.

Build/test status is recorded below. Logs are in `work/notes-integration-removal/`.
No installation, physical-device UI/pen testing, or paid provider request is part of this work.

## Qualification

- F-Droid: 3,291 tests, zero failures/errors, five existing skips.
- Play: 3,274 tests, zero failures/errors, five existing skips.
- Build-logic: 10 tests, zero failures/errors/skips.
- Kotlin file-size gate and diff whitespace checks pass.
- Both debug APKs build. Full Android gate: BUILD SUCCESSFUL in 6m 19s.
- Full F-Droid ARM64 combined preview builds: BUILD SUCCESSFUL in 1m 17s.
- Packaged DEX contains no retired Notes package or Notes proposal tool. Standard share activity and
  ProotSandboxManager remain, with ARM64 native libraries and no x86 libraries in the preview.

Reproduction (Android Studio Flatpak OpenJDK 21, existing Android SDK):

```sh
bash ./gradlew :app:testFdroidDebugUnitTest :app:testPlayDebugUnitTest \
  :app:assembleFdroidDebug :app:assemblePlayDebug verifyKotlinFileSize --no-daemon --max-workers=2
bash ./gradlew -p build-logic test --no-daemon --max-workers=1
bash ./gradlew -I scripts/combined-device-preview.gradle :app:assembleFdroidEinkPreview \
  --no-daemon --max-workers=2
```

APK: `kalamos-workspace/artifacts/agora-notes-integration-removal-2026-10-18/Agora-combined.apk`.
SHA-256: `779cc6d5ff26957e01bd9579cbd177cc2381a0729c344818788cff05749ca1a3`.
The APK is prepared, not installed. Physical acceptance remains user-owned.
