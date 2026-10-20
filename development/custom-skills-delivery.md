# Custom Skills draft delivery

This draft is stacked on codex/share-files-from-apps. The owner's simplified four-field model
supersedes the retired expanded configuration design. The later Before/After workflow group
extends those four main fields without reintroducing dependency/tool forms.

Implemented:
- Shared Settings list/detail editor: name, concise description, shortcut and instructions.
- Reopen/edit/Save/Back and delete. Removed import/export/duplicate actions and tool/folder/child forms.
- Filename references instead of Skill UUIDs/rename maps. Tools, examples, Alpine paths and child
  filenames live directly in Markdown. Reuses existing terminal tools; removed Skill-specific SAF
  and file-wrapper providers.
- Filename-only discovery; selected description/body loading, indexed shortcut lookup and quoted
  filenames. Existing template/composer admission and draft preservation retained.
- Nested invocation snapshots, parent-token validation and cycles/depth/context limits through the
  ordinary tool loop. No required-dependency configuration.
- Packaged create-skill.md and propose_skill: discussion → review card → same native editor → Save.
  No automatic file creation or overwrites from a displayed draft.
- Existing atomic body recovery, stale editor-save rejection and native archive/backup transport.

Still pending: native Pi session discovery/transport/editing (external companion source), human translations and live-model/physical
Android/e-ink acceptance. Those are not prerequisites for the simplified local editor contract.
No device installation, paid model request or physical acceptance has been performed.

Validation of this revision is recorded in the PR after the final checks.

Final validation of the simplified revision:
- F-Droid: 3,276 tests; Play: 3,259 tests; zero failures/errors, five skipped per flavor.
- Build-logic: ten tests passed; Kotlin source-size gate and diff whitespace checks passed.
- Packaged browser viewer checks passed, including sandbox isolation, gesture gates, reopen and
  conditional e-ink presentation. This is computer-side evidence, not device acceptance.
- Both arm64 Skills Preview APKs assembled successfully with the separate .skillspreview ID.
- Automated coverage includes four-field edit/save/cancel, native review of a chat-authored draft,
  completed-only artifact recognition and exact proposal identity, names-only discovery without
  decoding unrelated bodies, filename/rename behavior, invocation limits and composer ownership.

Slash-picker extension:
- `/` combines local templates/Skills with short metadata descriptions and duplicate suppression;
  `/skills` remains a Skills-only filter. Shared themed rows include the e-ink high-contrast variant.
- Optional native `runtime.skills` metadata supports connected Remote suggestions through the same
  UI and existing send path. It follows live-runtime revocation and is never saved to the library.
- The Android receiver is implemented; computer-side discovery/adapters must supply this optional
  metadata. Existing bridges without it remain unchanged. Codex-only connection qualification is
  retained; no claim of native Pi execution is made.

Slash-picker validation:
- Full F-Droid suite: 3,285 tests; Play: 3,268 tests; zero failures/errors, five skipped per flavor.
- Both arm64 preview APKs, build-logic tests and Kotlin size gate passed; diff whitespace clean.
- Inspected regular and e-ink component previews under app/build/reports/custom-skills/.
- Tests cover concise descriptions, typing/filtering, duplicate suppression, unsent selection,
  malformed unrelated Skill bodies, native command transport, optional protocol compatibility,
  incomplete native entries, session/hide/disconnect revocation and read-only fallback exclusion.
- Native agent discovery/dispatch has not been tested against a live computer bridge with this
  extension. No device installation, paid model request or physical/e-ink acceptance was performed.


Template workflow revision:
- Skills own optional Before/After interfaces and a next Skill filename; there is no During phase.
- Before opens locally; explicit complete_skill presents After using the real invocation snapshot.
  User Continue uses normal send admission and provenance; cycles, replay and missing inputs block.
- Folder-backed interfaces list real Markdown filenames through the existing Alpine sandbox;
  only the selected contained file is read at Start. Templates submit data, not routing commands.
- OSCE example packages setup/review HTML and examiner/revision Skills, no clinical corpus.
- Settings reuse the existing editor/dropdowns for regular and e-ink modes. Descriptions cap at
  ten words on new writes; legacy long descriptions and linked-Skill templates remain readable.
- Physical Android/e-ink, live-model quality and computer-side Pi adapters remain unverified.


Final template-workflow validation (17 October 2026):
- Full F-Droid suite: 3,298 tests; Play: 3,281 tests; zero failures/errors, five skipped per flavor.
- Both arm64 Skills Preview APKs built; build-logic's ten tests and Kotlin source-size gate passed.
- Packaged browser checks passed, including folder setup/review, exact selected source submission,
  regular selected-state contrast and black/white e-ink selection. Preview images are generated in
  app/build/reports/interactive-templates/; they do not establish device acceptance.
- Settings tests cover save/reopen of Before/After/continuation choices. Runtime tests cover explicit
  completion snapshots, compact native receipts, provenance, local-only setup, direct and setup-first
  continuation, cycle rejection, stale/repeated gestures, missing sources and directory containment.
- Generated the reference-free OSCE archive and checked that its template/Skill references resolve.
  Its setup identity is separate from the legacy example; Merge still preserves user conflicts.
- Expanded-view metadata uses demand hydration of its exact origin, preserving source folders on
  Save. Library opening captures its conversation before listing to prevent cross-chat retargeting.
- Diff whitespace checks passed. No device installation, paid model calls or physical acceptance.

## Combined device installation

The owner wants one installed app containing the cumulative changes, rather than separate feature
preview apps. Build from this cumulative branch with
`./gradlew -I scripts/combined-device-preview.gradle :app:assembleFdroidEinkPreview`. This opt-in init script keeps the existing
`com.newoether.agora.einkpreview` package identity and debug signature for data-preserving updates,
sets the launcher name to Agora, and includes the F-Droid Alpine integration. The retained package
suffix is an upgrade identity; this build includes Skills, templates, e-ink and all ancestor changes.
Install with `adb install -r` when requested. Do not introduce another per-feature package on the
owner's device. Normal production build configuration is unchanged.


Zero to Finals device migration (17 October 2026): moved all 300 exported article references into
`/sdcard/Zero to Finals/{Medicine,Surgery}/<specialty>/<condition>.md`, with SHA-256 verification
before and after replacing the old article Skills. Alpine uses `/mnt/shared/Zero to Finals`.
Only examiner/revision workflows remain in Skills. The existing setup identity now navigates real
folder levels locally and uses native Before/After/continuation settings. Fresh pre/post native
exports verified no intervening edits and the final reduced library. Recovery backups remain.
Eight Python tests and the nested-menu browser suite pass. Owner confirms Alpine installed/working;
no model exercise or physical panel acceptance is claimed. See zero-to-finals-folder-handover.md.


Restored OSCE layout delivery: compact Medicine/Surgery sections with 18 specialty buttons and
filename-derived counts, followed by condition selection. Applied through template-only native
import after fixing its importability check; installed the cumulative APK in place and inspected
the fresh device menu. Final checks: 3,300 F-Droid / 3,283 Play tests, zero failures/errors; five
skipped per flavor; both combined APKs, size/build-logic, eight Python tests and browser suite pass.
Offline initial tool audit reproduces 1,008 estimated tokens (916 raw + 92 buffer); see
initial-tool-context-audit.md. No paid model requests or exercise-quality acceptance claimed.

Examiner prompt cleanup: removed template/storage implementation narration from the Zero to Finals
Skill (folder layout, local menu mechanics, renames, native routing and review UI). It now says to
use the supplied reference/options and retains the progressive clinical teaching and final
complete_skill call. Reduced 576 → 303 words / 4,024 → 2,119 UTF-8 bytes. Applied to the installed
Skill through a Memories-only native archive, preserving both workflow Skills and their metadata;
the live editor text exactly matches the new source. Selection still uses the existing deterministic
template/native source reader. Eight example/migration tests pass; no app rebuild/model request.

Owner's OSCE simplification supersedes the earlier OSCE-specific chain: revision guidance is now
part of zero-to-finals-osce.md. It keeps Before/setup only; After and next Skill are unset. After
exam feedback it offers focused revision and waits for the learner's request, then continues in
the same conversation/reference. Removed the generated zero-to-finals-revision.md and its OSCE
review template from the installed library. Native editor verifies the merged instructions and
Before = Zero to Finals OSCE / After = None. The generic two-Skill example and app chaining support
remain available for workflows that need them. Migration converter now produces this single-Skill
Zero to Finals configuration, preserving unrelated existing Skills. Eight Python checks pass.

Ordered workflow menu revision (17 October 2026):
- Plus chooses additional templates; minus detaches a step without deleting its library item.
  Each row can be replaced, moved Before/After, or moved up/down within its phase. The next Skill
  remains a separate choice after the final After template. Workflow changes require Skill Save.
- The existing shared template metadata editor is available directly from a selected row. Its
  library save is explicitly separate from workflow Save. Both normal/e-ink modes share controls.
- Native configuration reads previous single-template settings and writes ordered template lists
  when changed. Opened cards carry bounded remaining steps, inputs and source selections. All
  intermediate template submissions remain local; only the final step enters normal AI admission.
- Up to 16 template steps per Skill, 8,000 collected-input characters and 128 KB combined selected
  references. Missing steps/sources fail without silently skipping to an AI request. Existing
  provenance, completion receipts, cycle/depth checks and conversation replay fencing remain owners.
- New labels currently use English fallback text in translated resource catalogues, consistent with
  the earlier draft workflow controls; human translation remains pending.

Validation of ordered workflow steps: full F-Droid 3,306 tests and Play 3,289 tests, zero failures
or errors, five skipped per flavor. Both combined arm64 APKs and the Kotlin size gate passed.
Compose coverage includes editing/reopening, searching, adding, removing, phase movement and
reordering in e-ink mode; runtime tests cover ordered local input/source accumulation, final-only
AI admission, invocation-snapshot After ordering and missing-step rejection. Installed F-Droid
cumulatively over com.newoether.agora.einkpreview; package inventory confirms one Agora app.
Inspected installed e-ink menu and chooser screenshots without modifying the OSCE workflow or
making a provider request. Physical panel acceptance remains with the owner.

Compact workflow UI revision (17 October 2026): screenshot review removed repeated help panels,
separate empty Before/After add cards, verbose move/remove buttons and single-step disabled arrows.
One Templates header has a plus; each selected template has its name/replace action, minus, a short
Before/After chooser and pencil. Reorder arrows appear only for multiple steps in the phase. Next
Skill is one compact row with a short prerequisite when unavailable. Routing/storage are unchanged.
Updated e-ink editor interaction tests and locale resource contract tests pass in both flavors
(12 checks each), and both cumulative arm64 APKs build. Installed over the same Agora package.
Inspected fresh device screenshots in app/build/reports/workflow-compact/{before,after}.png:
the current OSCE workflow fits in one compact panel with no control covered by Documentation.
The original single Before setup and no continuation remain unchanged.

Skills-only invocation and Start-crash mitigation (17 October 2026):
- The local slash picker now reads only Skill metadata and Create Skill. Legacy template
  shortcuts cannot shadow /osce, and templates are not registered in AI capability discovery.
  Template metadata editing no longer offers a shortcut. Library Open remains a local preview;
  a preview cannot initiate an AI request. Skill-owned Before/After sequences remain available.
- Captured Agora logs report IllegalStateException: LayoutNode should be attached to an owner
  in Compose placement/drawing after OSCE Start. The e-ink main chat transition now uses a
  keyed static container instead of retaining AnimatedContent's placement tree. Regular-mode
  animation remains. This is a mitigation pending the owner's device test, not a confirmed
  physical-device fix. A native-view host test repeatedly resizes and swaps setup/chat trees.
- Tokenizer readiness now invalidates the context projection, so the startup fallback estimate
  does not remain stale after the bundled tokenizer loads. The captured 941 System / 1,454 Tools
  versus loaded 608 / 1,008 arithmetic is documented in initial-tool-context-audit.md. Removing
  the template capability directory further reduces the loaded initial tools estimate to 998.
- The owner requested responsibility for device testing. No further app launch, UI interaction,
  screenshot capture or device repro is part of this delivery. Installation alone may update the
  existing package; final OSCE Start behavior and panel acceptance remain unverified by the agent.

Final host validation: full F-Droid 3,312 tests and Play 3,295 tests, zero failures/errors,
five skipped per flavor. Both combined arm64 APKs and the Kotlin source-size gate pass.
Coverage includes Skill-only slash discovery in both display modes, library-preview send rejection,
legacy-shortcut isolation, AI template-tool exclusion, tokenizer readiness and native-view swaps.

Short command and follow-up crash investigation (17 October 2026):
- The owner's screenshot showed `/skill "zero-to-finals-osce.md"` after selecting /osce. Picker
  selection now inserts the configured short command (`/osce `); explicit filename syntax remains
  available for Skills without shortcuts. Existing command admission and native setup routing own
  the send. Both regular/e-ink picker interaction tests assert short-command draft insertion.
- The owner's next attempt produced a different captured fatal stack at 12:04:29: ComposeRuntimeError,
  pending composition has not been applied. It runs from AndroidViewHolder removal through Android
  root focus search, lazy beyond-bounds measurement and reentrant subcomposition. This is a UI-thread
  crash, not an uncaught network exception. It shows the previous static-transition mitigation did
  not resolve all Start-related failures.
- Agora now enables the bundled Compose 1.11.2 embedded-View focus fix before Application.onCreate,
  with the matching R8 constant for optimized builds. Its AndroidViewHolder detach path clears owner
  focus before removing the native view. Upstream reference:
  https://developer.android.com/reference/kotlin/androidx/compose/ui/ComposeUiFlags
- Added host coverage for removing a focused native input from a lazy chat while showing an error,
  and simulated DNS/connection failures in both Chat Completions and Responses. The host focus test
  also passes with the old flag setting: it exercises teardown but does not reproduce the exact
  physical-device failure. Device validation is still necessary and belongs to the owner.
- Only the explicitly requested screenshot and passive logs were captured. The agent did not launch,
  send input to or exercise the app. Installation does not establish device acceptance.

Offline API toast: at actual provider dispatch, and again on a connection-failure event, Agora
checks for an active Android data network. With none, it shows one native long toast per generation:
"No internet connection. Connect to Wi-Fi or mobile data." This is an advisory, not an extra dialog
or a separate request/cancellation owner. Existing provider errors still settle normally. Local
models and explicit loopback API addresses are exempt. The connectivity permission is normal
ACCESS_NETWORK_STATE; no runtime permission dialog is added. Unknown/unavailable connectivity state
does not invent an offline warning. Template browsing and Alpine calls do not execute this hook.
The new message uses the default English resource until translated.

Validation: the short-command/focus changes passed both full suites (F-Droid 3,314 / Play 3,297,
zero failures/errors, five skipped each). Adding the toast initially exposed the locale-key contract
in the full F-Droid run; English fallback entries now exist in every language catalogue. Final
focused validation passes 61 checks per flavor, covering generation integration, error presentation,
short-command selection, native focus teardown, offline API events, toast behavior and all resource
keys. Both cumulative APKs and the Kotlin size gate pass. Final physical-device behavior is not
agent-verified.


Selected file presentation and examiner prefix (17 October 2026): the owner's screenshot confirms
that OSCE Start previously placed the article inside <selected_reference> in the visible message,
and sent only an instruction to invoke the Skill. The examiner body depended on a later model tool
call. Explicit Skill submissions now include the selected instructions from the first request,
including the meticulous clinical examiner role. Before interfaces remain local until final Start;
ordinary requests and slash discovery do not load unrelated Skills. Existing invoke_skill records
continue to own ancestry, frozen invocation configuration and completion hooks.

Selected articles are ordinary ready Markdown attachments with their real filename, preview bytes
and model text metadata. Existing payload construction and provider projection serialize them;
they are no longer pasted into the visible message. Snapshot files use ordinary attachment ownership
and cleanup, with live ownership during admission and reference-aware orphan handling after an
ambiguous Send. Originals on shared storage remain untouched. Ordered intermediate templates keep
path selections local and stage only at final submission. Host coverage checks upfront examiner
instructions, multiple-file admission, absence of article bodies in message text, file preview
bytes, exactly-once provider projection and pre-Send snapshot cleanup.

The owner explicitly confirmed this is the contract for ALL saved Skills, not an OSCE exception.
The implementation lives in shared skillInvocationPrompt and submitTemplateFollowup: direct
composer invocations, final Before submissions and chained targets all prefix their selected
instructions. Every workflow's selected references use the shared attachment batch. Nested Skill
tool calls already return each child's own instructions through the existing invocation owner.
Remote agent runtimes continue to own their native Skill protocol; no local source is fabricated
for an ephemeral remote entry.

Validation of shared Skill requests/attachments: full F-Droid 3,320 tests and Play 3,303 tests,
zero failures/errors, five skipped each. Two additional generic Skill request checks per flavor
pass for direct non-OSCE roles and chained child/parent context. Both cumulative APKs and the Kotlin
size gate pass. Final on-device presentation/exercise behavior remains for the owner to test.
