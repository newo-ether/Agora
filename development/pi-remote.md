# Pi PC integration

Owner request: install and build the PC learning-agent setup with Learn, Anatomy and Anima,
and connect it to the latest Agora copy (Agora-eink). This extends Remote; ordinary Agora
generation and its durable Room graph remain unchanged.

## Ownership

The PC gateway and adapter are Go in the separate Agora-Pi/filo checkout. The external Pi
runtime and its extensions use their own Node runtime; Node/TypeScript is not embedded in
Agora, Filo's gateway, or Conch. Pi 1.3.0 and the skill revisions are pinned in that workspace.
Only bridge-created sessions are controlled. Existing terminal-owned Pi sessions are never
silently resumed by a second writer. Native Pi JSONL remains conversation truth; the bridge
stores task metadata and delivery/control receipts, not a second transcript.

The encrypted gateway forwards to one private Unix-socket worker. That worker owns RPC
processes independently of the public gateway. Gateway restart/disconnect does not terminate
accepted work. Worker termination is a separate failure: saved native history remains, but
in-flight model execution cannot be promised to survive. No accepted mutation is auto-replayed.

## Remote protocol and interactions

Protocol 2 advertises agent=pi with the same bounded Filo HTTP/SSE and shared encrypted channel.
The client accepts only the explicit supported agents codex/pi. Pi model IDs include provider;
unsupported service tiers are omitted. Account subscription usage remains unavailable when
Pi cannot expose it. All ordinary selection, cancellation, receipt and stale-result fences apply.

Pi's standard RPC select/input/editor/confirm requests appear as readable questions in the
selected Remote chat. A composer reply supplies an exact option number, label, text, yes/no,
or cancel. Selection is validated before a receipt is created. Quiz grading stays in the PC
extension. Control replies have bridge-owned native-RPC receipts and reconcile the same
Remote pending-send owner without inventing a model turn.

## Figures

Only a succeeded render_interactive_answer tool may carry structuredResult. It must validate
as the existing InteractiveAnswer and fit the existing 80,000-byte saved-tool budget. Failed,
foreign, incomplete or malformed results remain readable tool output and never execute.
The adapter carries structured data separately from truncated tool previews.

The PC extension can losslessly gzip-pack complete standalone HTML builds into that budget.
The sandbox uses its browser DecompressionStream to reconstruct the same HTML/styles/scripts;
no private file URL, native port or extra network origin is granted. Unsupported/failed browser
initialization preserves a readable fallback. Source builds stay on the PC under artifacts/.

Remote reuses the existing inline cards, expanded viewer, user-gesture checks and exact-text
confirmation. The callback revalidates the selected owner and exact cached artifact, then uses
the existing Remote submission path with a preserveComposer receipt. Draft text and attachments
cannot be sent or cleared by an artifact follow-up. Selection/hydration changes revoke viewers.

## Qualification

Cover genuine RPC message deltas and agent_settled, duplicate sends, invalid options, native
receipt reconciliation, exact Stop identity, archive preservation, bounded Unicode paging,
completed-only artifact decoding and composer preservation. For the owner-requested device update, run the combined ARM64 device variant's unit suite,
the source-size gate and its matching APK build. Do not build additional store variants
for this delivery. Browser fixtures verify full
Anatomy/Anima pages in the actual packaged sandbox; physical-device acceptance remains user-owned.
Do not claim Windows Pi support from the Linux worker or qualify unrelated Codex Desktop
behavior using this adapter's tests. Current execution evidence lives in Agora-Pi/VERIFICATION.md.

## Combined device delivery

The owner requires one cumulative installed Agora app, not a separate Pi edition.
Device updates include the latest cumulative Skills/Notes/templates/sharing/e-ink
branch plus this Remote integration. Use `scripts/combined-device-preview.gradle`,
retain package `com.newoether.agora.einkpreview`, launcher label Agora and the same
signing key, and install with `adb install -r` to preserve app data. Do not create
another per-feature application package. The earlier Pi Lab APK is superseded.
The separate Pi contribution remains draft PR #9; cumulative device assembly
does not merge or close the existing review PRs.

Remote figures use the current shared InteractivePrompt API. Phone-local template source-file
requests are rejected before submission; they cannot silently drop files or acquire access
to PC paths. Ordinary local Skills/templates retain their existing source attachment flow.

The owner explicitly limited this delivery to one HiBreak device variant. Use
`:app:testFdroidEinkPreviewUnitTest verifyKotlinFileSize :app:assembleFdroidEinkPreview`;
additional Play/F-Droid release and debug variants are outside this device-update scope.

Remote's composer reuses the existing optional VoiceTypingButton beside Send with the
shared 48 dp control size and 8 dp gap. Settings → Interaction continues to own its switch
and engine configuration. Voice only edits the selected draft; it cannot auto-send.
Normal-chat voice implementation, settings and stored preferences are retained.

The cumulative device APK must use the **fdroid** flavor: it contains Alpine and the
shared-storage permission required by folder-backed templates. The Play flavor uses a
no-op sandbox and is not a compatible replacement. The combined-device init script
enables only fdroidEinkPreview. Before in-place installation, compare the saved installed
APK's permissions and signature and verify the full sandbox implementation is packaged.
