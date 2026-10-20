## General native folder API — 19 October 2026

This supersedes the Markdown-only native boundary described below. The Android folder grant is
now the capability: all file types are discoverable, with optional filtering in template code.
The old Alpine/Skills source contract retains its Markdown whitelist. Native Start accepts up to
16 exact relative file paths, resolves each under the persisted tree and stages through ordinary
attachment ownership/processing. Generated artifacts cannot supply permission IDs. Chat file
operations resolve the persisted originating template and verify the selected message/card both
before and after IO. Up to four local read operations may run per viewer; disposal revokes replies.

`listDirectory` supplies bounded metadata pages (200/page, at most 5000 entries inspected), including
empty directories. `readTextFile` and `readFile` provide strict UTF-8 or Base64, bounded to 128000
bytes. Neither listing nor reading calls a model, Shell or executes content. `sourceFilesAuto=false`
allows on-demand browsing without recursive discovery or a stored filename whitelist. Native text
attachments are strict/bounded; images/PDFs use the existing processor, with 100 MB combined native
source budget. Unsupported binary attachments fail before admission rather than becoming text.
The OSCE template filters `.md` in JavaScript; the generic folder-browser example filters nothing.
Copy for AI documents the general bridge instead of assuming clinical conditions or Markdown.

Qualification: F-Droid 3314 / Play 3297 full unit tests, zero failures/errors and five existing skips
per flavor; both debug APKs and Kotlin source-size gate pass. Packaged Chromium tests exercise
unfiltered navigation, local JSON and Base64 operations, multiple selections across folders and the
unchanged OSCE flow. Android resolver tests cover unknown types, empty files/folders, pagination,
revoked grants, exact names, traversal rejection and strict binary/text handling. A staged JSON
attachment test verifies immutable private bytes, correct metadata and cleanup without altering
the source. Stale-card tests deny file access before IO or model admission. Normal/strict docs
builds and 26390 local documentation link targets pass. No paid model calls or physical Android
acceptance tests were performed.

## Native reference folders — 19 October 2026

Android SAF folder grants are device-local opaque bindings; portable templates retain the binding
ID and need reconnecting on another device. Native discovery queries filename/type/ID columns per
folder on IO, with 500 Markdown/5000 total entries/eight depth bounds. Dynamic menus receive only
metadata before execution; Start performs whitelisted, bounded strict-UTF8 reading and retains the
existing attachment/CAS submission path. The current menu snapshot cannot select a subsequently
added undeclared file; reopening discovers it. No shell/model tool is added by folder access.
Copy for AI carries a versioned editable answer/catalogue contract, never provider credentials,
article bodies, permission URIs or Skill prompts. JavaScript listReferenceFiles returns only the
same native-injected snapshot. It exposes no absolute paths, ContentResolver or arbitrary file reads.
The original Zero to Finals examiner and clinical corpus are unchanged. Verification recorded
in ignored work/native-template-folders and the publication handover.

Verification: full F-Droid 3,307 / Play 3,290 tests, zero failures/errors and five existing skips
per flavor. Both debug APKs and Kotlin source-size gate pass. After removing the optional local
file-check UI, both debug APKs and the 55-test template/settings selection pass (two existing
optional-source skips). Packaged Chromium tests cover dynamic categories, uppercase `.MD`, exact
selected paths and the metadata-only native bridge. Eight example/migration Python tests and
normal/strict multilingual documentation builds pass; 26,390 local link targets resolve.
No paid model request or physical Android acceptance test was performed.

> 18 October 2026: The owner has retired the Notes integration. The earlier instruction below
> to retain its restoration is superseded by [the sharing boundary](notes-integration.md).

# Interactive template verification

Verified against the committed e-ink interactive renderer baseline `183f366f`.
The template change is isolated from the separate uncommitted Notes integration checkout.

## Automated results

- F-Droid unit suite: 3,098 tests, zero failures/errors.
- Play unit suite: 3,081 tests, zero failures/errors.
- Both debug APKs assemble successfully; packaged interactive host/sandbox/e-ink assets checked.
- Build-logic tests and `verifyKotlinFileSize` pass with no baseline exceptions.
- Packaged Chromium renderer tests pass, including the 360px-wide saved-interface fixture.
  Topic browsing, count changes and local random selection produce no sendPrompt event;
  Start produces one event with the selected inputs. Reopening resets controls.
- Strict MkDocs builds all 12 locales; template-page local links resolve.
- Staged diff whitespace checks and locale key/placeholder parity pass.

The generic tests exercise atomic CRUD, unsafe metadata/oversized payload rejection, corrupt storage,
merge/replace conflicts, stale-editor protection, exact arbitrary shortcuts, failed/foreign tool
artifacts, opt-in tools, provider projection, real Room local-run admission, composer bypass before
provider preparation, draft preservation, library interaction and archive/fork snapshot preservation.

## Reproduce

Run `./gradlew -p build-logic test`, then:

```sh
./gradlew :app:testFdroidDebugUnitTest :app:testPlayDebugUnitTest \
  :app:assembleFdroidDebug :app:assemblePlayDebug verifyKotlinFileSize --no-daemon --max-workers=2
node scripts/test-interactive-viewer.cjs
mkdocs build --strict
```

The browser script accepts PLAYWRIGHT_MODULE and CHROMIUM_PATH for existing installations.
Native submodules/Android SDK and PRoot packaging prerequisites remain the existing app build inputs.

## Remaining acceptance

No APK installation, physical-device interaction or paid model request was performed. User checks
remain: import/save pickers on Android, `/custom` in new/existing chats, linked-Skill Start confirmation,
physical e-ink presentation and an actual provider session. The Zero to Finals OSCE example includes a private offline pack of 300 Medicine/Surgery
source files. Its compact specialty/condition navigation and controls are tested in the packaged
browser sandbox. BMJ/ECG retrieval remains outside this proof of concept.

The draft PR is stacked on the renderer branch so its diff contains only this feature. For an
upstream submission after renderer PR #156 merges, cherry-pick this feature commit onto updated master.

## Composer slash-menu follow-up

Typing `/` lists all stored templates and further typing filters name/shortcut. A native Compose
preview verifies multiple choices and selection of an imported template with no shortcut. The
composer routes an explicit selected ID through local admission; missing/rejected selections
preserve the draft and never fall through to provider preparation. Busy/frozen inputs and
attachments disable selection. Tests: 23 composer submission and 2 menu tests pass on each flavor
(50 focused checks); the ARM64 F-Droid preview build and source-size check pass. Physical screen
reading diagnosed the original unregistered `/osce` message; no device interaction test or paid
model request was run for this follow-up.

The slash Send path also resolves a unique name match without an assigned shortcut. Exact
registered shortcuts win; ambiguous and unmatched slash commands remain local and preserve
the draft. Follow-up command/menu/composer tests: 28 per flavor (56 total), zero failures/skips;
ARM64 build passes. This reproduces the previously unregistered `/osce` case in host tests.

Saved-template Run now dispatches immediately in both inline and expanded presentations, without
a second confirmation. Three native Compose checks per flavor verify no-dialog single dispatch,
rejection acknowledgement without automatic retry, and retained confirmation for generated answers.
Ownership/prompt validation and the browser's active-user-gesture requirement remain in place.
Both focused flavor runs and the ARM64 replacement APK build pass. A read-only device screenshot
captured the old confirmation; no paid teaching session or physical interaction test was performed.

Interface-scroll/action-row follow-up: 8 native touch tests and 1 action-layout test pass on each
flavor (18 checks). Vertical gestures stay in the renderer even when the outer WebView/child
reports no scroll; outside vertical swipes still scroll chat. Horizontal drawer gestures, slider
claims and cancellation release pass. Both footer actions share one row at 328dp with independent
click actions. ARM64 APK and source-size checks pass. The initial device screenshot was read-only;
physical scroll acceptance remains with the user.

Boundary-handoff follow-up: 12 native touch checks per flavor pass (24 total), including top and
bottom handoff, mid-drag boundary updates, and slider ownership at an otherwise unscrollable edge.
Packaged Chromium tests verify actual root/nested scroll limits, touch panning across pointer
cancellation, touch-end cleanup, existing e-ink paging and bridge isolation. Bounds are reported
only on state changes, with a fresh report at each touch start. The latest helper is verified
byte-for-byte inside the rebuilt ARM64 APK. Official Android WebView/Compose and Chrome scroll
chaining references are recorded in the engineering contract. Physical acceptance remains user-run.

Expanded-save follow-up: the inline card now offers only Expand. Save is available in the expanded
viewer toolbar, retaining native artifact validation and linked-Skill metadata. After success it
is disabled until that viewer is closed/reopened; no save runs on opening or recomposition. Two
native action tests per flavor pass (4 checks), along with the focused F-Droid template tests,
source-size check and ARM64 build. Device installation preserves app data; physical acceptance
remains user-run.

Combined composer/Notes qualification, 15 October 2026:
- The combined preview head is `codex/restore-kalamos-notes`, stacked on the on-demand context,
  voice and template branches. Future device builds must retain the Notes restoration as well.
- F-Droid: 3,203 tests; Play: 3,186 tests; zero failures/errors, five existing skips per flavor.
  The separate real downloaded-pack native check passed without its optional-source skip.
- Both debug builds, ARM64 release, Kotlin source-size validation, packaged sandbox browser checks
  and strict documentation build pass. The installed package remains the same e-ink preview app.
- Default requests omit the global skill catalog. OSCE Run natively includes the examiner plus one
  whitelisted selected Markdown source, before ordinary admission. No model file-read is needed.
- Native popup tests prove the attachment menu preserves editor focus and uses a non-focus-taking
  window in ordinary/e-ink modes. Notes menu entries coexist with that keyboard behavior.
- Local inspection of provider serialization confirmed zero OSCE filenames in ordinary fixed input.
  System text shrank from 13,831 to 2,867 characters. With the shipped vocabulary and safety margin,
  the system estimate is 608 tokens; restored Notes tools remain in the tool-schema payload.
- Temporary inspection/update code is absent from the clean source and installed APK. No model
  request was sent for qualification. Physical Notes/pen/speech acceptance remains user-tested.
