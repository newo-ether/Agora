# Zero to Finals folder migration handover

## General folder API (19 October 2026)

The native layer no longer filters to Markdown. All file types are discoverable and template
JavaScript owns optional filters. The OSCE example filters `.md` itself. Generic templates can
use `listDirectory` with metadata pagination, `readTextFile` for UTF-8 or `readFile` for Base64,
with no Shell/model call. Setting `sourceFilesAuto=false` skips recursive preloading; empty folders
and empty catalogues are valid. `examples/interactive-templates/folder-browser` demonstrates an
unfiltered Before template. Copy for AI describes the general contract and preserves local bindings.
Native read access is bounded by the selected folder and current originating card; old Alpine
snapshots retain their original whitelist behavior. Start supports 16 native paths, stages immutable
source copies and reuses normal image/PDF/text processing. Listing any file does not guarantee that
the existing converter/model can consume its format. Binary content is never silently coerced to text.

Full suites: F-Droid 3314 / Play 3297, no failures/errors, five existing skips per flavor. Both debug
builds, Kotlin size gate, Chromium general-menu/OSCE checks and strict multilingual docs pass.
The OSCE Skill prompt and clinical corpus are unchanged; physical acceptance remains owner-run.
The ARM64 combined preview was built and installed successfully with `adb install -r` into the
existing `.einkpreview` package on 19 October 2026. SHA-256:
`b49f117938bcbe0444774b36443cde5f36c5dab2e4c6a4c9ec9b7729bb4d0d19`.
The bridge asset was checked inside the APK. `Folder-browser.agora-template.json` is in device
Downloads as an optional example; importing it still requires its own one-time folder connection.
Existing OSCE settings/grants and app data were retained. General API source is commit `31dbc07a`.

## Native Android folders (19 October 2026)

The combined source now supports user-selected Android SAF reference folders on both flavors.
Settings → Templates → edit → Reference folder → Choose folder grants persisted read access.
No all-files permission, Shell tools, sandbox initialization or model request is required. The
same interface can be pointed at any chosen folder; its fresh openings discover Markdown files
and subfolders automatically. Already-open cards retain their immutable catalogue snapshots.
Display labels normalize extensions, hyphens/underscores and an optional exact prefix without
renaming the documents. The Zero to Finals corpus and examiner prompt remain unchanged.

Custom HTML/JavaScript receives `agoraReferences` (`path`, `label`, `categories`) and exact
`agoraSourceFiles`; `Websandbox.connection.remote.listReferenceFiles()` returns that same
metadata snapshot. Only Start resolves a whitelisted selected path through ContentResolver and
attaches its bounded UTF-8 content before the existing Skill request. JavaScript has no generic
filesystem/native bridge. Copy for AI and Paste code support the versioned editable interface
bundle while retaining template identity and local folder connection. Exported templates need
reconnecting on a different device. Legacy Alpine templates remain compatible; choosing a
native folder replaces their sourceDirectory. Global Shell settings are not changed automatically.

The installed OSCE template can be switched by choosing its Android folder. The updated example
also consumes the normalized labels. A private same-ID template-only update is prepared under
ignored build reports for the existing workflow; no clinical articles, settings or credentials
are published. Use the existing template replacement/import UI, then choose its folder once.
Physical Android/e-ink acceptance remains the owner's responsibility.
Full unit suites pass: F-Droid 3,307 / Play 3,290, five existing skips each, no failures/errors.
Both debug builds, final 55-test template/settings selection, Kotlin size gate, packaged browser
checks, eight Python tests and strict multilingual docs pass. The separate file-check control
was removed at the owner's request; selecting a topic then Start performs validation automatically.
The combined ARM64 preview build passes and was installed successfully with `adb install -r`
into the existing `.einkpreview` package. APK SHA-256:
`0a4acf7f192a20f2f3eb03ca82e9b8e5308f61ee203463443626656ec4be3fd5`.
The current host asset was verified inside the APK. No app data was cleared. A same-ID updated
interface JSON is in device Downloads as `OSCE-Android-folder.agora-template.json` for optional
replacement through the existing template row. The existing installed menu already consumes
native `agoraSourceFiles`, so simply connecting its Android folder enables the normal flow.
The updated generic menu also keeps direct Markdown files accessible alongside subfolders,
including uppercase `.MD`; real packaged browser coverage verifies both routes before Start.


## Cumulative Pi update (17 October 2026)

The latest combined device source is `the combined Agora checkout`, branch
`codex/combined-pi-update`, based on Skills commit `7cae515a` plus the Pi Remote integration.
Use that cumulative source for subsequent device updates so Pi is retained. The owner
explicitly requires one app. Keep the same Agora label, `.einkpreview` package and
signature using `scripts/combined-device-preview.gradle` and `adb install -r`.
The separate Pi Lab build is superseded. Earlier source paths below are historical
context for the completed folder migration. See `pi-remote.md` for Pi ownership.


17 October 2026. Original handover below; migration subsequently completed (see completion record). **Owner confirms Alpine Linux is
installed and working.** Do not repeat sandbox installation or treat that as an outstanding task.

## Repository and device

- Work in `the Agora skills checkout`, branch `codex/custom-skills-editor`.
  The chat cwd is Kalamos-final; it is not the target repository.
- Draft PR: https://github.com/josephm1/Agora/pull/8. Latest pushed commit `3950db8b`.
- One installed app: launcher **Agora**, package `com.newoether.agora.einkpreview`,
  version `2.1.0-combined-preview`. Device serial `<BIGME_DEVICE_SERIAL>`, Bigme HiBreak.
  The cumulative Skills branch includes earlier e-ink, templates, Notes, dictation and sharing work.
- Future APK updates must retain this package/signature and use `adb install -r`, not create another
  per-feature app. Reproducible init script: `scripts/combined-device-preview.gradle`.
- Android SDK adb: `$ANDROID_HOME/platform-tools/adb`.
- `run-as` fails because this device reports `/data` permissions 40777. Do not chmod system directories
  or root the device to work around this. Normal native export/import provides access to the data.

## Owner's requested result

Articles must be ordinary editable files, not 300 Skills. Preserve the original downloaded content,
organised by the publisher's Medicine/Surgery index:

```
/sdcard/Zero to Finals/
  Medicine/Cardiology/Chronic Heart Failure.md
  Medicine/<other specialty>/<condition>.md
  Surgery/General Surgery/Appendicitis.md
  Surgery/<other specialty>/<condition>.md
```

Alpine sees the same directory at `/mnt/shared/Zero to Finals`. Android currently reports only
emulated/shared storage mounted, with no separate removable card. This was explained to the owner.
Use meaningful filenames, no file IDs or rename aliases. The Skill directs the examination; its
Before template selects subject → specialty → condition → question count locally. Only Start reads
and passes the chosen article to the model. Explicit Skill completion presents review; user Continue
starts revision. No During phase or clinical corpus embedded in prompts/templates.

## Completed on device

1. Exported only **Memories and Interactive templates**, excluding chats, settings and credentials,
   through Settings → Data Control. Backup remains at
   `/sdcard/Download/Agora_export_2026-10-17.agora`.
2. Pulled that actual installed-data export to the ignored/private directory:
   `app/build/reports/zero-to-finals-migration/before.agora`.
   It contains exactly 300 reference Markdown files, the original `zero-to-finals-osce.md`, one
   linked OSCE template and the manifest. No unrelated memories/Skills were present. The installed
   examiner matched the repository's original examiner byte-for-byte before rewriting.
3. Prepared and copied all 300 references into `/sdcard/Zero to Finals`: 165 Medicine, 135 Surgery,
   across 18 specialty directories. Filenames use catalogue condition titles. Original Markdown,
   source URLs, download dates and clinical content are unchanged.
4. Checked **all 300 device SHA-256 hashes** against the actual installed-data export. Passed.
   Audit: `app/build/reports/zero-to-finals-migration/prepared/migration-audit.json`;
   device checksums: `app/build/reports/zero-to-finals-migration/device-checksums.json`.
5. Prepared the reduced archive and pushed it to
   `/sdcard/Download/zero-to-finals-folder-workflow.agora` (about 6 KB).
   **It has NOT been imported yet. The old 300 article entries remain in Skills.**
6. Located the Alpine controls under Settings → Shell → Enable Local Sandbox → Manage Local Sandbox.
   Local Sandbox was enabled. Management showed installed Alpine aarch64, shared storage mounted at
   `/mnt/shared`, and packages up to date. Owner subsequently confirmed it works.

## Uncommitted repository changes

- `examples/interactive-templates/osce/zero-to-finals-osce.md`: rewrote the original detailed examiner
  for the selected folder reference. Preserved progressive one-question-at-a-time teaching and
  reference-grounded feedback. Removed app-private article-Skill assumptions. Completion explicitly
  uses `complete_skill` only after the entire exercise; native settings own review/continuation.
- `examples/interactive-templates/osce/osce.agora-template.json`: generic filename-driven menu now
  navigates folder levels, so Medicine → Cardiology → condition and Surgery → subsection → condition
  match the actual directories. It submits input JSON and one exact relative filename, with no
  Skill commands or article text. Also supports shallower folders and local Random.
- `scripts/test-interactive-templates.cjs`: browser fixtures use the nested Medicine/Surgery layout,
  check the exact selected path and subject/specialty inputs, no send while selecting, regular
  contrast, black/white e-ink state and review handoff.
- New `examples/interactive-templates/osce/migrate_to_folders.py`: converts an actual Memories/Templates
  export into the named source tree plus reduced native workflow archive. Never edits the input
  backup, downloads articles or uses AI. Rejects missing/unindexed references, unsafe/colliding
  filenames, existing differing destination files and unrelated workflow conflicts. Preserves any
  unrelated exported memories/Skills/templates and metadata.
- New `examples/interactive-templates/osce/test_migrate_to_folders.py`: four passing synthetic-data
  tests for byte preservation, routing, unrelated data retention and failure-before-overwrite.
- This handover file is also new. None of these migration changes has been committed/pushed yet.
  Do not discard them or regenerate from the old flat Skills pack.

## Prepared archive details

Local output:
`app/build/reports/zero-to-finals-migration/prepared/zero-to-finals-folder-workflow.agora`.

It preserves the existing OSCE setup template identity `c361aac1-ef11-4524-9ee0-b4476210421c` and name,
replaces its interface with the hierarchical folder menu, sets sourceDirectory to
`/mnt/shared/Zero to Finals`, clears legacy linkedSkill/shortcut/resourceFiles, and adds the review
interface. Its two Skills are:

- `zero-to-finals-osce.md`: shortcut `osce`, Before = original OSCE template ID, After = review ID
  `197d7681-a9e2-4ec0-9439-a1e12bb0b171`, next = `zero-to-finals-revision.md`.
- `zero-to-finals-revision.md`: uses the existing targeted revision instructions.

New skill metadata contains concise descriptions. No `ztf-*.md` reference articles are in the
reduced archive. All copied articles remain ordinary files on shared storage. Keep the before
backup and original downloaded `.agora` archive for recovery; do not remove them during cleanup.

## Validation completed

- `python3 -m unittest discover -s examples/interactive-templates/osce -p 'test_migrate_to_folders.py'`:
  four tests passed.
- Full packaged browser suite passed with the new nested menu:
  `/tmp/agora-ztf-folder-browser.log`. Uses the existing local Playwright/Chromium runtime:
  `PLAYWRIGHT_MODULE=/path/to/node_modules/playwright`
  and `CHROMIUM_PATH=/path/to/chromium`.
- 300 copied device files matched installed-source hashes. No model requests were made.
- No Kotlin/app runtime source changed in this migration. The earlier combined build had full
  3,298 F-Droid / 3,281 Play tests passing, both APK builds and size/build-logic checks passing.

## Next actions

1. Inspect current device UI before tapping: last observed screen was Sandbox Management, with
   Alpine ready/shared storage mounted. User may have navigated elsewhere since then.
2. Import `/sdcard/Download/zero-to-finals-folder-workflow.agora` through normal Data Control.
   Choose **Replace for Memories and Templates**, not Merge: Merge preserves conflicting old
   entries and would leave the 300 article Skills behind. This reduced archive was made from the
   fresh installed export and preserves all unrelated data (none in this device's snapshot).
   Preview should show two Skills/memories and two templates. Read the native preview and confirm
   it matches before applying. If the owner added data since the snapshot, export again and preserve
   those additions before replacing. No need to ask permission again for the requested migration.
3. Export the resulting Memories/Templates again and check: only the examiner/revision Skills,
   correct concise metadata, no article `ztf-*` Skills, correct folder-backed setup/configuration,
   and no unrelated data loss. Keep the pre-migration backup.
4. Open a **fresh** `/osce` setup, confirm Medicine/Surgery → specialty → conditions list locally.
   Old chat cards are immutable snapshots; don't use an old flat template card to judge the new
   workflow. Check a representative selected path if possible without making a paid model request.
   The owner performs physical e-ink acceptance; they already confirmed Alpine itself works.
5. Update README/delivery documentation, run appropriate example/migration checks and review the
   old `build_pack.py`: it is explicitly legacy and still packages references as Skills. Prevent
   future current instructions from recommending that layout; decide whether to adapt its output
   to the new converter/folder workflow. Do not download articles again or publish the corpus.
6. Commit/push the migration code/docs to existing draft PR #8, update its description with actual
   migration/verification results, and report final device location and remaining acceptance.

Useful native UI helper (host temporary file): `python3 /tmp/agora-ui-dump.py` dumps via adb and
prints labels/bounds from `/tmp/agora-migration-ui.xml`. Read fresh bounds before actions. All
current migration archives/corpus/audits stay in ignored `app/build/reports`, not Git.


## Completion record

Migration resumed and completed on 17 October 2026. A fresh pre-import export matched the original
backup, so no intervening library edits were lost. Imported only Memories/Templates with Replace.
A post-import export (`app/build/reports/zero-to-finals-migration/after.agora`) matches the prepared
workflow, accounting for normal serialization omission of empty resourceFiles. It contains exactly
`zero-to-finals-osce.md`, `zero-to-finals-revision.md` and concise metadata, plus the two configured
templates. No `ztf-*` article Skills remain. All 300 shared reference hashes were reverified after
import. Device recovery exports remain in Downloads; originals were not deleted.

Fresh `/osce` opening produced the local setup and the Medicine → Cardiology condition list on the
device, without a model call. The user confirmed Alpine works; this UI inspection does not establish
clinical exercise quality or physical panel acceptance. No paid model request was made.

Eight network-free Python example/migration tests pass; the packaged browser suite passes with
nested subject/specialty paths, exact selection, no automatic send, regular/e-ink presentation and
review controls. The legacy pack builder now requires an explicit obsolete-format CLI flag;
README recommends folder migration. No Kotlin/runtime change or APK reinstall was needed.


Owner's subsequent layout feedback: restored the original compact Medicine/Surgery sections,
specialty buttons with local article counts, condition grid and Specialties back control. All are
still derived from actual folder paths; there is no embedded article catalogue. Browser coverage
also checks the full 18-specialty / 300-filename layout in regular and e-ink modes. A templates-only
archive was rejected on device; fixed ImportPreview.hasImportableData to include templatesPresent,
with regression coverage. A combined in-place APK update is required for that small importer fix.
The user also requested an exact tools estimate: see initial-tool-context-audit.md and generated
public-schema files under app/build/reports/tool-context. Reproduced 916 raw + 92 buffer = 1008.


Final delivery: the combined APK with the template-only import fix was installed in place.
Imported `zero-to-finals-restored-layout.agora` with Replace for Templates only, then opened a
fresh `/osce` and inspected its expanded device screenshot. Both subject sections and all 18
specialties/counts are visible, matching the compact original design. Left that fresh setup open;
no Start/model request was made. Snapshot: app/build/reports/zero-to-finals-migration/restored-device-layout.png.
Both full suites passed: 3,300 F-Droid and 3,283 Play tests, zero failures/errors, five skipped each.
Both combined arm64 APKs, Kotlin file-size gate and build-logic checks passed. Eight Python tests
and all packaged browser checks passed. No corpus/backup/private conversation data is committed.

Owner subsequently requested removing storage/template explanations from the examiner prompt.
Completed: zero-to-finals-osce.md is now 303 words (formerly 576), retaining the clinical teaching
instructions and explicit final completion signal. Applied via concise-examiner.agora (Memories
only); verified the installed editor matches the source. The local folder/menu selection and
native workflow configuration are unchanged. Editor is open for inspection. Eight Python tests pass.

LATEST OWNER REVISION: the Zero to Finals workflow is now ONE Skill. Revision guidance merged into
the examiner, offered after the exam and performed only on the learner's request in the same chat.
Before remains Zero to Finals OSCE; After = None, no next Skill. Generated revision Skill and OSCE
review template removed from the device using a native replacement of only the known library.
Verified one Skill in Settings, exact merged instructions, and Before/After values in the editor.
`single-osce-skill.agora` is the latest local/device update archive. Previous chain descriptions
above are historical. Converter/tests/README updated; eight Python tests pass. General chaining
support and the generic example remain intact. No model request or APK change was needed.

The cumulative device APK must use the **fdroid** flavor: it contains Alpine and the
shared-storage permission required by folder-backed templates. The Play flavor uses a
no-op sandbox and is not a compatible replacement. The combined-device init script
enables only fdroidEinkPreview. Before in-place installation, compare the saved installed
APK's permissions and signature and verify the full sandbox implementation is packaged.
