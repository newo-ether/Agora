# Application UI Contract

Status: authoritative development contract, 2026-08-15.

This document owns durable application-level UI behavior that is not part of message generation,
citations, semantic search, or Web Search. Current explicit user requirements override older
presentation code and translations.

The minimum supported Android version is Android 8.0 (API 26). Android 7 (API 24/25) is no longer
supported. The default Mi Outfit family retains one variable font resource with its five explicit
weights; every supported platform must apply those variation settings.

## Global English UI title capitalization
English title-like UI copy must use conventional Title Case. This is a hard UI constraint, not a
page-specific preference. It applies to page and sheet titles, section and group headings, setting
row headlines, dialog titles, menu commands, action labels, and other standalone labels that name a
surface or command. Major words are capitalized; articles, coordinating conjunctions, and short
prepositions remain lowercase unless they are the first or last word. Approved examples include
`Service Tier`, `Developer Options`, `Stick to Bottom`, and `Import from Claude`.

Technical acronyms remain uppercase, including MCP, API, URL, HTTP, SSE, SSH, PDF, and GGUF.
Product names and deliberately mixed-case technical names retain their official casing. Descriptions,
helper text, placeholders, body copy, and status sentences use sentence case instead. Non-English
locales follow their native casing and punctuation conventions.

Call sites must consume correctly authored resources. They must not apply a generic runtime title-case
transformation, because that would corrupt acronyms, product names, user-authored names, and locale
rules. Focused resource or source-contract tests must pin English title values for audited surfaces
and preserve locale key parity.

## 1. Motion ownership and accessibility

Ordinary and Remote chat share the existing message presentation. `ChatComposerLayout`
owns the original input field, sizing, expansion and layout; `ComposerSendButton`
owns the original action drawing. Callers supply content slots and callbacks.
Ordinary chat retains its existing draft/import/submission owners. Remote connection
data and delivery never enter ordinary Room, Provider or generation lifecycle owners.
Remote must not copy message bubbles, Markdown rendering, input drawing or scroll logic.

Application UI motion consumes the shared Agora motion policy. Spatial press, size, and scale motion
must snap to the stable resting presentation when Reduced Motion disables spatial transitions.
Opacity-only transitions may remain only where their owning component contract allows them.

A screen may reuse an established motion language directly without creating another global animation
owner. Interaction state stays local to the interactive control and must not alter navigation,
validation, persistence, or completion semantics.

Every overlay blocks haptics originating from the chat beneath it, including a continuous answer
texture and asynchronous send acknowledgements. Settings, Tasks, Remote, text/media previews,
modal sheets, dialogs, and menus retain this exclusion until they finish covering the chat.
The overlay's own interaction feedback remains available. Covered acknowledgements are consumed
silently and are never replayed on return. Closing a nested preview restores the still-present
underlying overlay; an already-exited surface must not regain ownership. A drawer covering Chat
also suspends background chat feedback, while a side-by-side drawer leaves Chat visible.

## 2. Onboarding primary action

The onboarding Continue/Get Started action preserves its full-width role, page validation, paging,
completion callback, enabled state, colors, and label semantics.

The action has no custom press-driven size, inset, or content-scale animation. It remains at its
stable geometry of 32 dp horizontal inset, 48 dp height, and 1f content scale while pressed and at
rest. The ordinary Material Button indication remains available, but the action does not own a
`MutableInteractionSource`, pressed-state collector, spring, tween, or other custom press-motion
state. Its existing outer layout, shape, color, enabled state, navigation, and completion behavior
remain unchanged.

## 3. Settings category copy

The Generation Settings category description names only its actual category content and is the direct
localized equivalent of `LLM parameters`. It must not mention the context window. This copy change
does not remove or relocate Context Settings, alter the Generation Settings destination, or change
any stored generation parameter.

The default resource and every supported locale must define the same key set. App-owned strings are
localized in the current Android locale; hard-coded English must not replace resource-backed UI copy.

## 4. Chat composer dropdown icon parity

The chat-bottom attachment `+` dropdown and tools `...` dropdown use explicit 24 dp leading
icons/images in every menu row, matching the Material default size used by the user-message
long-press dropdown. Their 16 dp trigger icons remain unchanged. Menu shape, row geometry, 12 dp
icon-label gap, labels, badges, switches, ordering, enablement, and click behavior remain unchanged.

The monochrome Google Search and OpenAI Search provider icons inherit the dropdown's current Compose
content color. They therefore remain legible across light and dark themes and retain inherited
disabled-state alpha; neither row hard-codes a light or dark tint. Provider artwork, icon size,
spacing, labels, badges, switches, availability, ordering, and interaction remain unchanged.

## 5. Chat bottom-bar answer fade

In normal, non-expanded composer mode, the existing 40 dp vertical fade is an alpha mask on the
conversation foreground, not a separately painted background-color cover. Its zero lead, normal-only
12 dp host lift, measured bottom-bar height, and animated composer-expansion spacer place the mask at
the same screen coordinates as the existing fade without moving the chat-bottom Surface or changing
`bottomBarHeightPx`. The mask uses offscreen `DstIn` composition: conversation pixels stay opaque above
the fade, become transparent through the 40 dp band, and remain transparent behind the composer, so
the one actual `AnimatedBlobBackground` below is revealed pixel-for-pixel even while it moves. Normal
mode must not sample, duplicate, freeze, or paint over that dynamic background. List/answer padding,
IME/navigation insets, composer-expansion spacer ownership, and scroll ownership remain unchanged.
Expanded composer mode receives no lift and retains its exact background-color cover with 20 dp
compact-at-screen-top gradient geometry.

The top gradient blur and bottom alpha mask share one viewport graphics layer. The layer records
the existing `DstIn` mask before applying the remembered horizontal/vertical RenderEffect chain;
ChatApp must not stack a second offscreen mask layer inside a blur layer. The blur retains its
full-resolution 9-tap passes, 5 dp maximum scale, 150 dp top falloff, and sub-0.5-pixel bypass.
Blur Effects off and Android below API 33 retain the same mask without constructing RuntimeShader.
The mask caches its brush and stops until drawing size or fade geometry changes. Content redraws
must not reconstruct them, and composer geometry changes must not recompile the blur shaders.

## 6. MCP page-entry refresh

Entering the MCP Settings page submits exactly one refresh request for every enabled server with a
nonblank URL, except a server already in CONNECTING state. The page delegates through the ViewModel to
the process-wide `McpRegistry`; it does not create another connection authority. Public refresh entry
points return without holding the Registry lock or constructing or closing a client on the caller
thread. Runtime, client, and transport construction, replacement, close, and connection work run on
the Registry's IO dispatcher under the process-wide AppContainer `appScope`, so page destruction does
not cancel an accepted refresh.

Page-entry requests are single-flight per exact server configuration. A second page-entry refresh
coalesces with an active connection or pending build for that configuration. Every build receives a
monotonic generation ticket, and installation plus snapshot publication require the ticket, current
configuration, enabled state, nonblank URL, and runtime identity to remain current. A removed,
disabled, or replaced configuration therefore fences out stale build, connection, and error results;
stale clients are closed without replacing the newer runtime or snapshot.

Recomposition and navigation within the page's editor do not retrigger refresh. No timer, delay loop,
WorkManager job, alarm, service, background observer, or periodic polling participates. Existing
Settings reconciliation, snapshot StateFlow, retry backoff, manual refresh, and runtime identity
checks remain authoritative.

## 7. Localized category and Thinking-segment labels

The default resource and all eleven supported locale directories define localized values for
`context_title`, `context_desc`, `thinking_segment_display_mode`,
`thinking_segment_display_mode_desc`, `thinking_segment_display_card`,
`thinking_segment_display_bottom_sheet`, and `thinking_segments_title`. Localized resources must
not retain the default English text for those keys, and placeholder sets remain identical.

## 7. Appearance token-detail cleanup

Appearance does not expose the obsolete Detailed token usage toggle. ChatApp, MessageList,
MessageItem, and AssistantMessageContent do not collect or thread that unused UI value. The existing
stored preference key and settings import/export compatibility remain readable and writable so the UI
cleanup creates no migration or archive incompatibility.

Message Info reports Provider usage for the complete Run. Every completed Provider request in a
multi-round tool loop contributes its reported input, cached-input, cache-write-input,
uncached-input, output, reasoning, and total counts. Multiple cumulative usage snapshots emitted by
one request replace that request's prior snapshot instead of being added twice. If any constituent
request omits a breakdown category, the aggregate category remains unknown rather than becoming a
fabricated zero.

## 8. Image-transcription model chooser

The primary image-transcription model chooser lists only currently enabled concrete models. It does
not inject a synthetic `No model`/null-selection row. A previously persisted null value remains
compatible: the settings summary may still show its existing no-model fallback, and nullable
settings persistence/import behavior remains unchanged.

## 9. Appearance Thinking-segment row order

When the Thinking segment display setting is available, Appearance places it immediately below the
Thought and Tool Blocks display setting and before Auto-Expand Active Group. Reordering must not
change the existing Grouped/Compact availability rule, the exact Grouped + Card Auto-Expand rule, or
any stored/effective display-mode behavior.

## 10. Settings destination rows without redundant arrows

Top-level Settings category cards do not render a right-arrow icon; the entire existing card remains
the navigation target with unchanged grouping, padding, labels, descriptions, colors, and spacing.
The Terminal page's enabled-only Manage sandbox row likewise omits only its trailing Chevron while
preserving the row click destination and the separate Sandbox enable Switch. Provider Settings omits
right arrows from built-in Provider rows, custom Provider rows, and Local Models. Custom Provider rows
retain their protocol badge but omit the spacer that existed solely between that badge and its arrow.
No destination, summary, tint, enablement, persistence, or other trailing control changes.

## 11. Full-screen text-file preview typography

The full-screen Markdown-file preview renders its content with the current effective App font from
`MaterialTheme.typography`; it does not replace that font with a hard-coded mono or system family.
Markdown body/list/table text, H1-H6, block code, and inline code preserve their current font sizes and
use exactly 1.1 times their source line height. H1-H6 are explicitly Bold. Link text inherits the
containing paragraph typography.

The ordinary-text preview also uses the current effective App font while retaining its exact 13 sp
font size and 20 sp line height. The already-bold filename overlay, close control, selection, scrolling,
HTML handling, link behavior, Markdown components, content padding, and file-type routing remain
unchanged.

Every full-screen preview subtype enters and exits through one of two shared top-level transition
hosts: the media host covers loading, single video, PDF, mixed image/video paging, and single image;
the text host covers Markdown and ordinary text. With spatial transitions enabled, both hosts use the
same entrance of a 220 ms fade plus a 300 ms center scale from 0.96f to 1f with
`FastOutSlowInEasing`, and the same exit of a 180 ms fade plus a 220 ms center scale from 1f to
0.96f with `FastOutLinearInEasing`. Reduced Motion retains only the corresponding timed fades.

The hosts keep their last payload through exit and release the top-level presentation owner only after
the transition settles. A confirmed video page alone retains the viewer-internal 400 ms player fade
before handing off to the shared top-level exit. Image, PDF, loading, and unresolved media pages hand
off immediately without a pager-owned delay. The mixed-media pager has no duplicate close timer or
second `onClose` owner. Media decoding, pager navigation, gestures, payload routing, shared exit
transitions, and Reduced Motion remain unchanged.

## 12. PDF page rasterization

PDF page rasterization uses the existing framework `PdfRenderer` owner for both selected pages sent
as model attachments and all-page full-screen preview generation. Every newly allocated
`ARGB_8888` page bitmap is initialized to opaque white before
`PdfRenderer.Page.render` receives it. This produces a deterministic white paper background for
PDF regions that do not paint an explicit background and prevents JPEG encoding from flattening
transparent black pixels into a black page that hides correctly rendered black glyphs.

Both consumers share one bitmap-initialization path. The change does not alter page dimensions,
1536 px long-edge scaling, JPEG quality 80, filename/storage ownership, selected-page filtering and
ordering, preview page limits, progress callbacks, cancellation cleanup, page-count behavior,
PDF-authored colors or backgrounds, viewer motion, or attachment/LLM routing. A different PDF engine
or dependency is not introduced without separate evidence of a rendering defect that remains after
opaque-white initialization.

## 13. Full-screen media window layering

A media preview opened from any Dialog-backed Bottom Sheet owns a subsequently created full-screen,
edge-to-edge Dialog window. Window order is source Bottom Sheet below media viewer below the
viewer-owned Image Actions Bottom Sheet created by long press. Compose `zIndex` is never treated as a
cross-window ordering mechanism. Closing the viewer reveals the still-owned source sheet unless that
sheet independently dismissed.

The media Dialog draws one full-size, unscaled black backdrop, disables system window dimming, and
owns that backdrop's alpha through the same retained visibility transition. The backdrop fades from
transparent to black on entry and black to transparent on exit while the media-content layer keeps the
existing shared fade/scale transition. Closing therefore reveals the underlying owner continuously
instead of holding a fully black frame until Dialog destruction, while a content scale below 1f still
cannot expose the square corners of a scaled black rectangle. Exact transition durations/easings,
last-payload retention, Reduced Motion, pager gestures, and confirmed-video-only close waiting remain
unchanged. The long-press Image Actions sheet remains a modal window created after and above the media
Dialog. Its system window dim is disabled so the sheet-owned animated scrim is the only black overlay;
long press must not introduce a one-frame opaque dim flash before the scrim fade.

## 14. Composer clipboard images

The Chat composer TextField participates in Compose Foundation receive-content dispatch for
`image/*`. A clipboard paste may contribute one or multiple URI-backed images. Handled image items
immediately enter the existing `ChatComposerState.onPickImages` private-copy, progress, rejection,
preview, removal, draft, and send lifecycle; transient clipboard URIs are never persisted as the
attachment's durable path.

Only supported image URI items are consumed. Text and every unsupported clipboard item are returned
to the TextField/platform so native text paste, cursor replacement, selection, undo, IME, and
accessibility behavior remain intact. A mixed clipboard payload can therefore insert its text at the
current selection and add its images as attachments. MIME resolution is defensive and copy failure
uses localized existing/new attachment rejection presentation without crashing or leaving a phantom
attachment.

## 16. Drawer conversation-list loading and search progress

Opening an existing conversation binds covered layout settlement to that destination's scroll
coordinator. Selection publication and switching readiness may arrive in either order; the previous
destination must not begin waiting on its own hydration registry for the incoming page. Completion
still requires the current destination's loaded conversation, context projection and stable layout.
Send consumes its scroll request only after the target turn has a measured message index. The final
absolute-bottom sentinel is excluded from this range, so a new turn cannot match the old sentinel
before the list has measured the insertion.

The conversation drawer observes only the conversation fields required by navigation, selection, display, and the system-prompt dialog; it never materializes draft text, draft attachment metadata, or branch-selection blobs for that list. Its first emitted snapshot is loading, distinct from a genuinely empty library, and a motion-aware circular indicator fades in and out over the list area.

Conversation search exposes a separate in-flight state from the moment a nonblank query is accepted through debounce and the existing literal/semantic query. Its circular indicator fades in and out in the search field, does not alter query debounce or ranking, and cancellation, clearing, or failure cannot leave a stuck indicator. The retained prior result may remain visible while a new query is pending.

The drawer's first-list state is not a second conversation authority or a new search architecture; Room remains the durable source and the existing search methods remain authoritative.

The conversation and search-result lists share one edge-fade state rule. The top edge is treated as reached while item `0` is first visible and its scroll offset is at most `2 dp`. The bottom edge is treated as reached for an empty list, or while the final visible item's end is no more than `2 dp` beyond the viewport end. The corresponding fade remains hidden inside that tolerance and appears only after content crosses it. This tolerance changes state judgment only; it does not add or modify list content padding, outer Drawer padding, list geometry, or programmatic scroll targets.

Ordinary conversation rows animate ordering changes with a `400 ms` placement tween when the shared motion policy allows spatial transitions, and use a `180 ms` deletion-only fade-out. Reduced Motion disables placement travel. Stable conversation keys and the search-result branch's whole-list transition remain unchanged. Each ordinary row Crossfades only its resolved visible title over `200 ms` with `FastOutSlowInEasing`; the row identity, weighted title geometry, ellipsis, selection colors, trailing indicator, and menu values do not participate. Initial title composition is stable, rapid title updates retarget the latest value without queuing, and Reduced Motion retains this opacity-only transition.

Every ordinary-list reorder preserves the numeric `firstVisibleItemIndex` and `firstVisibleItemScrollOffset` captured from the existing Drawer list state. Rows therefore exchange within fixed viewport slots instead of Compose retaining the previous first-visible conversation key at that screen offset. The correction runs only while ordinary conversations are shown, the emitted item count still matches the measured list, the measured anchor still exists, and another key now occupies its numeric index. Initial or empty loading, a count-changing insertion or deletion, and the search-result list retain their existing behavior. If an ordinary reorder arrives during drag, fling, or another programmatic scroll, the numeric request captures that moment's index and offset and cancels the in-progress scroll without delay, retry, resumption, or a second list-state owner.

A New Chat first Send is the sole automatic-top exception. Only after the accepted conversation and first message graph are durable and that conversation is published, the bounded `firstMessageCommitted` event waits until the same still-selected conversation occupies item `0` in the measured ordinary list. It then uses the existing Drawer `LazyListState` and the canonical `animateToAbsoluteTop` feedback controller with `SendFeedbackScrollSpec`, matching Send's scroll-to-bottom startup envelope, adaptive long-distance motion, ease-out, and user-input cancellation. Reduced Motion directly calls `scrollToItem(0)`. A later explicit conversation selection rejects the stale event. No other reorder, title change, insertion, delay, retry, fallback, or second scroll owner may reveal item `0`.

After durable deletion and runtime cleanup of the conversation that was selected when deletion was admitted, the canonical selection owner enters New Chat unless a newer explicit selection targets another conversation. A pending or completed newer conversation selection remains authoritative. Deleting a nonselected conversation or a deletion that fails before cleanup does not change the visible page.

Conversation deletion from both the Drawer and Task execution history, message-subtree deletion and
Compact deletion share the same confirmation flow. Clicking Delete
keeps the dialog visible and replaces the Delete action text with a loading indicator throughout
the pending operation. It blocks repeat confirmation, Cancel, Back, and outside dismissal.
Only successful completion closes the dialog; rejection or failure keeps it open and restores its
controls with the original target. The selected-page transition may proceed behind the dialog, but
does not replace the dialog as the interaction blocker. Late results cannot reopen a finished dialog.

If a message action would remove every durable message in the current tree, including the
single-Compact case, its action is presented as `Delete Conversation` and the confirmation explicitly
warns that the whole conversation will be removed. The dialog freezes the exact message-id topology
visible when it opens; confirmation uses canonical conversation deletion only if Room still matches
that topology. A later graph change rejects the stale confirmation, keeps the dialog open, and emits
no destructive-success haptic.
Deletion classification and confirmation ID collection run only when the existing delete action
opens that confirmation, never as per-row message composition work. The confirmation retains its
copied topology after failed or rejected admission; reopening through a new delete action captures
the then-current topology. Ordinary message rendering performs no deletion graph traversal.

Conversation deletion attempts immediate admission through the existing conversation execution
coordinator after the selected-page cover is visible. If generation already owns or awaits that
conversation, deletion reports failure and releases its own cover without waiting for generation
to finish or stopping it. Successful admission validates the captured topology under that same
ownership before storage mutation. Cancellation still delivers the completion callback and clears
only the cancelled request's cover, including cancellation during the cover fade.

## 17. Model alias display fallback

A model alias is presentation text, never a model identity. An explicit nonblank alias stored under
the complete model ID remains authoritative. When none exists, the shared model-display resolver
derives a human-readable fallback from the API model name without persisting it. Provider requests,
routing, capabilities, pricing, history, import/export, grouping, deduplication, and settings keys
continue to use the complete original model ID.

Inference removes a provider path for display and recognizes only bounded family-specific suffixes:
the exact `:batch` and `:free` variants, a terminal Claude `fast` serving marker, valid Claude snapshot
dates and version grammar, Amazon Nova's terminal API revision, and an exact terminal Gemini
`preview` marker. A nonterminal Gemini `preview` token and every generic-family `preview` token remain.
Core family, tier, size, speed, capability, version, and every DeepSeek numeric token remain. Unknown
or malformed names receive separator/case humanization without deleting ambiguous tokens such as a
date, number, `vN`, `latest`, `fast`, or colon suffix. The resolver is deterministic, case-insensitive
for recognized grammar, and idempotent for its formatted output.

Qwen tokens with an immediately adjacent numeric version insert one display space between the brand
and version, so `qwen3.8` becomes `Qwen 3.8`. This brand-specific spacing does not change the raw ID
or silently alter the formatting of other brand-number tokens.

Exact standalone tokens use their approved product casing: `glm` becomes `GLM`, `mimo` becomes
`MiMo`, `minimax` becomes `MiniMax`, and `a3b`, `e4b`, `a70b`, `oss`, and `tts` become `A3B`, `E4B`,
`A70B`, `OSS`, and `TTS`. Matching is case-insensitive but does not rewrite substrings or establish a
generic rule for unknown abbreviations or letter-number-letter tokens.

Settings Models renders the resolved alias as every model row's headline and the raw API model name
as supporting text, so distinct IDs remain distinguishable even when serving variants share one
fallback. Search matches provider/raw ID, explicit alias, and inferred fallback without coalescing
results. Existing-model alias editors are seeded with the resolved fallback when no explicit alias
exists. Saving that seed unchanged does not materialize it in DataStore; editing it creates an
explicit alias, while clearing an explicit alias restores fallback behavior. The new-custom-model
form remains blank until the user enters an alias.

Provider-name visibility is a separate preference keyed by the complete model ID. Rename and the
custom model editor place a Show Provider name switch below the alias field. Save commits both values in one DataStore edit,
while Cancel, Back and outside dismissal commit neither. Clearing or changing an alias never changes
that switch. Complete-name surfaces append the current Provider name only when the preference is on;
alias-only labels in Provider-grouped model lists retain their existing presentation.

The shared Provider-name switch row uses a white primary label, 8 dp of outer top spacing, and a
16 dp rounded-rectangle clip around its toggleable ripple. The top gap is outside the hit region.
The ripple and its single toggleable hit region extend 8 dp beyond each horizontal edge of the row's
original layout bounds. Matching inner padding preserves the switch position and surrounding spacing
in both layout directions. The title and description share an additional 8 dp start inset and retain
12 dp end spacing toward the switch; text wraps naturally within that inset area.

New models default to showing the Provider. A one-time Preferences DataStore migration preserves
existing presentation by recording off for existing nonblank explicit aliases; all other model IDs
default on. Subsequent display never infers visibility from alias presence. The initialization runs
before settings reads or writes, does not scan messages, and requires no Room schema migration.
The setting follows model identity remapping/replacement/deletion and portable Settings transport.

## 18. Models sync progress and attempt fingerprint

The Models page starts automatic full-provider model sync only when the current provider fingerprint
differs from the last persisted attempted fingerprint. A full sync presents no in-progress snackbar.
Its existing sync card owns progress feedback in place: the 24 dp refresh icon crossfades to a 24 dp
circular progress indicator and the idle headline crossfades to localized `Syncing...`, both over
250 ms without shifting the row. The existing result snackbar remains the only snackbar and appears
after a completed sync with the success, no-provider, completed, or failure outcome.

The full-sync controller captures the provider fingerprint when it admits the attempt and persists
that exact fingerprint after every non-cancelled attempt, including attempts with provider-specific
or global errors. It does not recompute the fingerprint after provider work. Cancellation clears the
single in-flight flag, emits no result, and does not persist the attempted fingerprint. Onboarding's
single-provider fetch remains outside this full-sync presentation and fingerprint lifecycle.

## 19. Notification permission and background-execution settings

Android 13+ notification permission is requested only after onboarding has completed and the main
Chat navigation has entered composition. Immediately before that request, the existing notification
owner creates both app channels. When that entry will show the system notification-permission dialog,
the Chat composer withholds only its initial automatic focus until the activity-result callback
reports that the dialog has closed, whether permission was granted or denied. Chat launch content
still appears on its existing schedule while the dialog is present. Already-authorized devices and
Android 12 or earlier retain the ordinary initial-focus timing; no timeout or fixed delay guesses when
the permission dialog disappeared. The ongoing generation-status channel remains low importance,
unbadged, and explicitly silent. The response-completion channel is created at high importance with
the system default notification sound and vibration so a newly created channel is eligible for
audible heads-up presentation. Existing channel IDs and user/device channel choices are never
deleted, recreated, migrated, or overridden; builder priority remains the pre-Android-8 counterpart.

Automation Settings places Exact Execution, Wake Lock, and Battery Optimization together in one
localized Background Execution category, in that order. Wake Lock is an app-owned, persisted,
portable, default-off switch. When enabled, one Agora-owned partial lock spans only the actual shared
Task or Loop execution boundary and is released on success, early return, failure, and cancellation.
The copy states that WorkManager already keeps normal scheduled work awake and that the extra lock may
increase battery use.

Battery Optimization remains a status-bearing action row rather than an app-owned switch. It reads
`PowerManager.isIgnoringBatteryOptimizations` on entry and resume. The F-Droid flavor alone may
declare `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` and, while not exempt, launch the package-specific
system confirmation after verifying the permission and resolvable intent. Play, unavailable direct
confirmation, launch failure, and already-exempt state use the general management screen. Neither
path claims to control OEM autostart/background restrictions, background data, or exact-alarm access;
supporting copy keeps those layers distinct.

## 20. Local Sandbox outcome feedback

Local Sandbox install, remove, upgrade, and reset outcomes are process-local buffered one-shot events.
An outcome produced while no UI collector exists remains queued for the next collector. Pending
outcomes retain production order, and each outcome is consumed by one collector exactly once. An
Activity recreation must not replay an outcome that the previous collector already consumed. Display
is interrupting: consuming an outcome dismisses and replaces the Snackbar still on screen instead of
waiting for it, so the newest outcome is always the visible one.

The Sandbox manager and its transient queue share the process lifetime owned by `AppContainer`'s
flavor factory. Foreground ViewModels, generation tools, and headless Task/Loop execution borrow the
same F-Droid manager and must not cancel or close it when a consumer lifecycle ends. Package
install, remove, and upgrade work therefore remains available to later consumers in the same process.
Only an explicit Sandbox reset may cancel the manager scope, and reset must replace that scope before
continuing. The queue is not persisted or restored after process death, mirrored through a durable
flag, or represented as retained UI state. The Play flavor exposes an empty outcome stream.

## 21. Tasks Once date-picker mode transition

The Tasks Once date picker uses Material3's modal `DatePickerDialog` at its stable 568 dp container
height. Material3 remains the sole owner of `DatePickerState.displayMode`, selected-date state,
calendar/input `AnimatedContent`, focus, keyboard interaction, and the mode-toggle transition. Agora
does not mirror the mode, delay or retry keyboard handoff, or animate the Dialog window's
wrap-content height. Confirmation, cancellation, selectable-date validation, formatting, colors, and
schedule persistence remain unchanged.

## 22. Debug test-model visibility

When Developer Options and Debug Model are enabled, the existing `debug` model participates in the
canonical Chat-enabled model set and uses the display alias `Debug`. Every ordinary Chat model
chooser consuming that set, including manual Compact, receives the same model and alias collection;
manual Compact has no private injection or separate policy. The hidden Debug Provider remains a
generation-only implementation detail and never appears in Provider Settings, provider editors,
Models Settings, Tasks, Context Settings, title generation, transcription settings, or another
configuration surface. No new UI, Provider configuration, API-key field, or model-list architecture
is introduced for this test model.

## 23. Conversation-owned attachment import and pre-acceptance Send

Every Composer attachment enters one durable, conversation-owned import lifecycle at selection
time. The attachment tile appears immediately, Agora copies the source into app-private staging,
and all required image normalization, video frame extraction, PDF rendering, ordinary-file text
reading, or Local Sandbox copying begins before Send. `PROCESSING`, `READY`, and `FAILED` are
persisted with the draft for both ordinary conversations and the New Chat workspace. Navigating to
another conversation does not cancel or transfer work; returning shows the same live state. After
process death, a conversation that has not been explicitly opened remains dormant. When the user
opens that owner, `PROCESSING` restarts from its immutable private staged source, `FAILED` remains
retryable, and an unavailable staged source becomes `FAILED`. A legacy draft without import state
remains `READY` only when it already names a complete canonical private artifact; an incomplete
legacy row is upgraded to `PROCESSING` and re-enters staging/import instead of being silently
omitted by Send. The existing `unavailable` value remains reserved for backup/import restoration
when the attachment resource cannot be restored and never represents import failure.

An image's `READY` private path is its final normalized artifact. The immutable staged image remains
separate until the `READY` draft write succeeds, then becomes reclaimable. A crash after output
creation but before that write therefore restarts from the original staged bytes rather than
compressing the output again. `READY` video frames, selected PDF page images, bounded ordinary-file
text, and Local Sandbox paths are likewise complete import results. Send performs no decoding,
scaling, compression, frame extraction, PDF rendering, file text read, or additional ownership copy.
Provider file reads, Base64, JSON serialization, and upload remain request encoding after accepted
input and are not attachment import work.

Each attachment tile has one canonical fixed-64-dp presentation Crossfade with a 200 ms duration.
Its keyed states include a neutral initial frame, unavailable, import loading/failure, ready
file/PDF/video placeholder, and Coil media loading/success/error. The thumbnail, placeholder, scrim,
error action, and indeterminate progress indicator all render inside that owner; none may hard-swap
outside it. A newly inserted `PROCESSING` attachment starts at the neutral frame so the loading
indicator visibly crossfades in. Loading-to-ready, loading-to-failed, failed-to-retry/loading, media
decode success/error, and every progress-indicator appearance/disappearance crossfade without a
layout jump. Tapping the failure overlay retries the complete import from private staging. Failed
attachments do not disable Send and are excluded from the accepted result. Attachment admission owns
one selection haptic after the processing tile is inserted; decode/import completion, rejection, and
failure do not emit a second haptic.

Composer image attachments and durable message media thumbnails initially suppress their progress
indicator. It becomes visible only after 200 ms of continuous loading, through the existing 200 ms
presentation Crossfade. Image import-to-decode processing shares the same delay for one Composer
attachment. Fast success/error never displays a progress indicator; image requests and painters remain
active during the waiting period. Loading completion, failure, retry, source-identity replacement and
composition disposal reset or cancel the keyed visibility timer. Ordinary file/PDF import feedback,
full-screen media and tool previews retain their existing timing.

Durable message-bubble thumbnails and the full-screen image viewport use the same explicit media
loading/success/failure semantics. Their image remains composed under one fixed-geometry, full-size
200 ms Crossfade owner. Composer attachments, message thumbnails, tool image previews and full-screen
media share a primary-colored 3 dp indeterminate stroke. Image failures use the Material BrokenImage
icon, preserving the surface's existing retry/close actions. Success reveals the image without a
layout change, and failure replaces the whole viewport with its error presentation; no corner icon,
blank thumbnail, hard swap, or zero-size proxy may leave a failed image looking indefinitely active.

Tapping Send freezes that draft owner's exact text, tap-ordered model/settings snapshot, and
attachment membership. The TextField remains enabled and editable in every submission and generation
phase; add/remove and retry actions remain protected until the request leaves its pre-acceptance
lifecycle. `WAITING` waits for every frozen attachment's processing coroutine to exit, then
preserves Composer order while retaining only `READY` results; zero successful attachments is valid
when the frozen text independently permits Send. Tapping the spinning Send control during `WAITING`
cancels only that Send request, keeps attachment processing alive, and releases the deletion lock.
`SUBMITTING` and accepted-clear recovery are non-cancellable and render one coherent neutral 3 dp
busy indicator with disabled action semantics. Across actionable and non-actionable transitions, the
container's primary/surface-variant colors and the content's on-primary/on-surface-variant colors each
interpolate with the historical 400 ms tween. The icon Crossfade remains an independent 200 ms linear
transition. Failure before accepted input returns the frozen request to `IDLE`.

Authoritative acceptance clears only the frozen text and attachment membership. Revision-aware
settlement preserves text typed after the tap, including a TextField edit that becomes visible before
its asynchronous draft observer reaches the controller. A durable acceptance whose draft clear fails
enters a non-resendable accepted-clear state and exposes a clear-only Retry; it never resubmits the
already durable input. Direct and queued acceptance do not clear focus, hide the IME, or collapse an
expanded Composer. Accepted draft clearing is state-only and does not own presentation focus.
Keyboard and Composer dismissal remain owned by explicit navigation, user gestures, and the drawer
greater-than-`0.5` threshold described below.

Switching conversations cannot cancel, redirect, duplicate, or clear the frozen request. From Send
tap through authoritative acceptance and exact-owner clearing, Delete Conversation is disabled for
the origin and the controller rejects deletion races below the dialog. A New Chat request inserts a
read barrier at tap time, so its immutable workspace includes every model, system-prompt, and
conversation-setting write queued before the tap and excludes later workspace edits. Its conditional
Room consumption matches the tap-time workspace plus the attachment states that actually settled
before acceptance. If newer workspace metadata survives that transaction, accepted clearing removes
only the sent draft fields and preserves the newer metadata. The request selects its newly created
conversation only if the user still occupies the originating New Chat workspace;
otherwise it appears in the list without taking focus or haptic confirmation. A process restart
leaves durable drafts and
attachment imports dormant until their exact owner is explicitly opened, then restores that owner
without automatically replaying an unaccepted Send request.

Attachment paging preserves occurrence identity. Send emits successful attachment artifacts and
metadata in one traversal of Composer order. Composer and durable-message viewers assign pager
indices while constructing their filtered media sequence; they never recover an occurrence with
`indexOf` on a URI or path, so duplicate values and mixed attachment types open the tapped item.
The current preview target initializes the viewer in its first composition. Retained content is used
only for exit; every new open owns a fresh request identity, while pager navigation preserves that
identity. Late Close/Navigate callbacks from an earlier request cannot change the current preview.
Multi-item and PDF pagers keep their composition while each child resolves its media type.

Attachment cleanup verifies current message, conversation-draft and New Chat draft references and
deletes an unowned file inside the same Room transaction. Reconciliation must repeat this atomic
check immediately before unlinking each candidate. Candidate queries use the covering attachment
index; canonical full paths determine ownership. Unreadable candidate metadata prevents deletion.
Draft persistence schedules removed durable paths once; transient removals remain explicitly owned
by the Composer. Schema 31 builds the covering index once on upgrade without rewriting message or
attachment content.

## Local Low Context controls

When the selected model belongs to the embedded `Local` Provider, the Chat bottom bar's three-dot
menu shows a conversation/New Chat `Low Context Mode` Switch. A null conversation value inherits
the default-off, device-local value from `Provider > Local > Advanced`; an explicit menu change
stores that conversation/workspace override. Selecting Ollama, a custom Provider, or a remote
Provider hides and ignores this control without deleting its stored value.

While Low Context Mode is effective, menu rows that add Provider or tool capability are disabled and
use the standard Material disabled presentation: Gemini Code Execution and Google Search, OpenAI
Service Tier and native Search, generic Web Search, and Shell. Thinking, Context Compact, Advanced
Settings, the model selector, and context usage remain available. The Chat top-bar System Prompt row
is disabled and gray but retains the selected prompt for later restoration. The Local Advanced
default uses the standard whole-row `SettingsItem` toggle with the localized Low Context title and
`Enable Low Context Mode by default` meaning; it is not a portable Settings import/export value.

## 24. Chat top-bar title capsule motion

The normal Chat top bar keeps its title capsule start-anchored after the trailing actions capsule has
reserved its fixed width. Brand/conversation-title identity changes Crossfade the title presentation
over `200 ms` with `FastOutSlowInEasing`. The title content is always measured and laid out against
its latest stable final constraints inside a canvas capped at 260 dp; no animated value participates
in the capsule, Crossfade, or Text layout width. An independently measured natural target controls
only one left-anchored rounded drawing clip over `400 ms` with `FastOutSlowInEasing`, so the capsule
background, shadow, and content reveal together while only the visible right edge moves.

The title-motion identity includes the selected conversation and its visible title; the brand state
has one stable identity. Token-subtitle appearance, disappearance, and value changes update the final
layout directly without starting, cancelling, or restarting identity motion. During an active identity
clip, the same owner continuously absorbs the latest measured token target by rebasing from the current
visible boundary over the remaining portion of the original `400 ms` deadline. It does not queue a new
motion, restart the clock, or finish at an obsolete target. The first terminal frame is exactly the
stable latest boundary, with no post-animation spatial correction. Initial composition presents stable
content without an entrance transition. A newer title interrupts in-flight clip and opacity motion and
retargets the same owners from their current values without queuing. Reduced Motion snaps the clip
boundary while retaining the component-owned `200 ms` opacity Crossfade.
`animateContentSize` does not participate. The existing 180 dp internal title maximum, 20 dp trailing
title padding, 16 dp actions gap, 98 dp actions width, ellipsis, icon geometry, search transition,
title resolution, click behavior, and persistence remain unchanged.

## 25. Task History return continuity and drawer focus threshold

Each task-list card has a 24 dp leading Repeat icon, matching the existing Tasks entry glyph,
with primary tint, vertical centering and 16 dp spacing before the text. The icon is decorative;
the existing card click action and trailing controls retain their ownership and behavior.

Task-list cards Crossfade their Last Run status line over 200 ms between Loading, Running, the last-run
timestamp, and Never Run. Before the first real execution-history snapshot, the line shows localized
Loading rather than treating missing data as empty; a known Running state retains priority. The Flow
is remembered per task identity and ViewModel, and later updates retain the last emitted snapshot.
Each transition snapshot includes its own text and running-status color;
unchanged countdown ticks do not restart this transition. Reduced Motion retains this opacity-only
feedback. The card's controls and task execution lifecycle are not part of this transition.

Task execution-history rows render Failed status and its timestamp with the neutral
`onSurfaceVariant` color at 70% alpha for a more muted appearance. Success retains `primary`;
the global theme error role remains available
for other feedback, including destructive actions and input validation.

A conversation opened from a Task execution log is a transient preview owned by that exact task and
execution conversation. The preview first observes its selected destination before reacting to later
navigation. Once observed, entering New Chat or successfully selecting a forked/different conversation
ends the preview immediately, restores the Chat top-left hamburger, and enables the drawer. A failed
or cancelled fork leaves the selected preview unchanged and retains the Task return action.

Toolbar or system Back admission changes the preview to `RETURNING` and immediately starts a forced,
non-haptic restoration of the captured pre-preview conversation or New Chat, even when the currently
published destination still equals that origin. This supersedes a pending history selection before
its late load can vibrate or publish stale state. Return ownership clears only after both the Tasks
overlay fully covers Chat and that exact restoration generation is observed settled with no selection
switch in progress. Overlay callbacks, selection results, and subsequent history taps are generation
fenced; rapid replacement keeps the original captured origin. While a transient preview or return is
visible, the Chat top-left action cannot fall through to the hamburger.

If restoration fails because its origin disappeared, loading/projection fails, or a newer navigation
supersedes it, the existing switching coordinator reports that exact request's failure once. The
matching return generation releases preview ownership after the Tasks overlay covers Chat and
resumes live history reconciliation without replacing the selection owner's current destination.
A stale failure cannot release a newer preview or return, and no retry, timeout, or replacement
conversation is created to settle a failed return.
Return generations remain monotonic across task changes and session clearing within the same editor
ViewModel lifetime, so callbacks from a previous task cannot collide with a new task's return.

The active Task editor session retains only the exact execution-summary list that was rendered when an
execution was opened, together with its existing numeric scroll position. When Tasks is recomposed
during return, that task-bound snapshot seeds the first frame and the LazyColumn starts at the retained
position. Room remains authoritative and continues collecting in parallel. While the overlay enters,
live results are buffered; after entry completes, the page presents the latest Room list. Existing
stable execution conversation IDs and Compose structural equality perform reconciliation. Agora adds
no manual list-diff engine, durable history cache, duplicate repository flow, page-wide composition
retention, or cross-task/process snapshot restoration. Switching tasks, clearing the editor session,
or process death discards the snapshot.

Drawer-driven Chat focus loss and full-screen Composer collapse use one strict progress threshold.
Progress at or below `0.5` preserves focus and expanded state. A false-to-true crossing of
`progress > 0.5` triggers the effect once; remaining above the threshold does not repeat it, and
closing back to `0.5` or below rearms the next opening. Dragged and programmatic openings share this
state. Existing Back handling remains independent. Direct and queued Send preserve focus, IME
visibility, and expanded Composer state.

## 15. Verification

Focused verification must cover the onboarding action's fixed 32 dp inset and 48 dp height, absence
of custom press-size/inset/content-scale state, and unchanged action semantics, Generation Settings description, locale key/value
parity for the Context and Thinking-segment labels, absence of the removed context-window wording,
24 dp leading-icon parity across both chat-bottom dropdowns without resizing their triggers,
theme-adaptive Google Search and OpenAI Search icon color without fixed light/dark tint, absence
of the Detailed token usage Appearance row and dead chat-side parameter threading, the Tool Blocks ->
Thinking segment -> Auto-Expand Appearance row order with unchanged predicates, the normal-only
0 dp gradient lead with unchanged 40 dp width and 20 dp expanded behavior, and scoped Settings-arrow
absence with preserved category/Sandbox/Provider click destinations, Sandbox Switch, and custom
protocol badge. PDF rasterization verification must cover one shared opaque-white bitmap initializer
used by both render paths, initialization before every framework page render, and unchanged
scaling/JPEG/page-selection/progress/cancellation behavior. Full-screen text-preview verification
must cover current App-font inheritance in
both Markdown and ordinary-text paths, exact 1.1 Markdown line-height scaling, explicit Bold H1-H6,
unchanged Markdown font sizes, and the unchanged 13 sp / 20 sp ordinary-text metrics. It must also
cover both shared full-screen transition hosts, the exact fade/scale durations and easings, Reduced
Motion's fade-only fallback, last-payload retention, release only after settled exit, confirmed-video
close waiting, immediate non-video handoff, and absence of a duplicate pager close delay. Media
verification also covers Dialog-over-sheet ordering, viewer-owned action-sheet ordering, unscaled
full-screen backdrop, and no scale-below-one corner exposure. Composer verification covers single and
multiple image URI paste, mixed image/text pass-through, unsupported content pass-through, immediate
private-copy routing, and failure cleanup. Model-alias verification covers explicit precedence, all
approved family-specific suffixes, generic preservation of ambiguous tokens, casing/separator
normalization, idempotence, inferred search, duplicate-display preservation, raw-ID supporting text,
and unchanged-fallback non-persistence. Models-sync verification covers absence of an in-progress
snackbar, the two 250 ms in-card crossfades, localized `Syncing...`, persistence of the admitted
fingerprint after provider and global errors, no end-of-sync fingerprint recomputation, and
cancellation without fingerprint persistence or completion presentation. Drawer edge-fade
verification covers the shared conversation/search owner, empty-list behavior, item `0` and final-item
identity, and the exact `0 dp`, `2 dp`, and above-`2 dp` top/bottom boundaries without adding content
padding or changing scroll targets. Drawer reorder verification covers the `400 ms` normal-motion
placement tween, the `180 ms` deletion fade, Reduced Motion's absent placement travel, numeric
first-index/offset retention across ordinary reorders, cancellation of an active ordinary reorder
scroll, and unchanged initial-load, count-changing insertion or deletion, and search-list behavior.
Drawer first-Send verification separately covers the durable published event, both current-conversation
guards, readiness at item `0`, canonical `SendFeedbackScrollSpec`, Reduced Motion direct positioning,
user-input cancellation, and absence of delay, retry, fallback, or another auto-top trigger. Drawer
Composer-dismiss verification covers the strict greater-than-`0.5` boundary, one effect per threshold
crossing, rearming below the boundary, shared drag/programmatic state, and unchanged Back behavior.
Direct-Send verification covers the absence of any acceptance-owned focus, IME, or expanded-Composer
presentation side channel while preserving accepted-clear state and confirmation haptics. Send-control
verification also requires exactly two 400 ms color tweens for container and content while retaining
the independent shared 200 ms LinearEasing icon Crossfade, true enabled semantics, and 3 dp busy stroke.
Task History verification covers target observation before preview settlement, New Chat and successful-
fork hamburger restoration, failed-fork retention, task-bound first-frame execution seeding, numeric
scroll restoration, post-enter Room reconciliation through stable conversation IDs, and snapshot reset
on task switch, session clear, and process recreation without a manual diff or durable shadow cache.
Drawer title verification covers one `200 ms` `FastOutSlowInEasing` Crossfade keyed only by the resolved
visible title, stable initial composition, latest-value interruption, retained Reduced Motion opacity,
and unchanged row geometry, ellipsis, colors, indicators, and menu values. Tasks Once verification covers the fixed 568 dp Material modal
height, Material3 ownership of display mode and keyboard interaction, and absence of shadow mode,
delay, retry, or window-size animation. Debug-model verification covers one canonical Chat-enabled
model/alias set shared by ordinary Chat and manual Compact while every Provider Settings and other
configuration surface remains free of Debug Provider/model integration. Notification/background-execution verification covers the
post-onboarding request boundary, channel creation before permission launch, callback-driven initial
composer focus after permission-dialog dismissal, launch-content independence from permission state,
absence of timeout-based dismissal guessing, silent low-importance generation status,
high-importance audible/vibrating response completion, shared Exact Execution and Battery
Optimization grouping, resume-time exemption-state refresh, the general system settings intent,
absence of direct exemption permission, and localized resource parity. Local Sandbox outcome
verification covers emission before collection, ordered pending outcomes, one-time sequential display
and consumption, absence of replay after collector recreation, every install/remove/upgrade/reset success and failure
path, the empty Play stream, and the absence of persistence or retained UI state. The project-defined full build
gate remains required after final code or resource changes. Chat top-bar verification covers the
independently measured natural target, stable final-layout canvas, one interruptible `400 ms` rounded
clip owner shared by background, shadow, and content, the `200 ms` title-only Crossfade, fixed start
alignment, identity-only animation keys, token-only updates that continuously rebase the active clip
toward the latest target within its original deadline, a first terminal frame equal to the stable
boundary with no post-animation correction, initial stable presentation, Reduced Motion clip snap,
absence of animated layout width and `animateContentSize`, and unchanged title/actions geometry.
