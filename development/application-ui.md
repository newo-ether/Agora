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

Ordinary ChatApp and Android Remote chat circular loading covers must block touch input to covered
content throughout their visible enter, loading and exit lifetime. Taps, long presses and drags must
not activate or scroll the content beneath them; a painted background alone is not an input barrier.
The cover is the topmost hit target for gestures that start while it is present, but it does not
consume them, so ancestor gestures such as the navigation drawer swipe still work. Immediate cancellation of gestures
already held before the cover appears is not required. Do not dispatch window-wide cancellation
or introduce a global input owner for this cover.
The existing content-area cover owns this exclusion, without a Remote-wide or separate interception
layer. Indicator placement uses ChatApp's measured top-bar and bottom-bar available range, including
IME-driven bottom-bar changes. Remote must reuse that centering rule rather than the full-body center.

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
long-press dropdown. Their trigger icons are 18 dp. Menu shape, row geometry, 12 dp
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

Drawer long press offers a push-pin `Pin` action, replaced by `Unpin` for a pinned conversation.
Below the existing search, navigation and New Chat controls, the same scrolling list shows a `Pinned`
heading and pinned rows before ordinary conversations. Empty Pinned is hidden; ordinary rows have a
`Conversations` heading only when both groups exist. Rows occur once and retain recent-updated-first
order within each group, stable identity, selection, indicators, menus and motion. Search is unchanged.
Room conversation `isPinned` is default false, survives restart and travels with native backups.
Its atomic narrow write advances `dataChangedAt`, not `lastUpdated`, drafts, graph, Run or unread state.
The canonical drawer's numeric anchors and measured counts include section headings. New Chat first
Send still waits for the first recent-updated conversation and scrolls the same list to absolute top,
including Pinned when present. Pin (not Unpin) uses the same feedback scroll to absolute top once the
list shows the row pinned, skipped during search and abandoned after 2 s if the write never lands.
Section headings align with the 16 dp row text inset. Pinned has 8 dp
top space; Conversations has 12 dp to separate it from the last pinned row.

The conversation and search-result lists share one edge-fade state rule. The top edge is treated as reached while item `0` is first visible and its scroll offset is at most `2 dp`. The bottom edge is treated as reached for an empty list, or while the final visible item's end is no more than `2 dp` beyond the viewport end. The corresponding fade remains hidden inside that tolerance and appears only after content crosses it. This tolerance changes state judgment only; it does not add or modify list content padding, outer Drawer padding, list geometry, or programmatic scroll targets.

Ordinary conversation rows animate ordering changes with a `400 ms` placement tween when the shared motion policy allows spatial transitions, and use a `180 ms` deletion-only fade-out. Reduced Motion disables placement travel. Stable conversation keys and the search-result branch's whole-list transition remain unchanged. Each ordinary row Crossfades only its resolved visible title over `200 ms` with `FastOutSlowInEasing`; the row identity, weighted title geometry, ellipsis, selection colors, trailing indicator, and menu values do not participate. Initial title composition is stable, rapid title updates retarget the latest value without queuing, and Reduced Motion retains this opacity-only transition.

Every ordinary-list reorder preserves the numeric `firstVisibleItemIndex` and `firstVisibleItemScrollOffset` captured from the existing Drawer list state. Rows therefore exchange within fixed viewport slots instead of Compose retaining the previous first-visible conversation key at that screen offset. The correction runs only while ordinary conversations are shown, the emitted item count still matches the measured list, the measured anchor still exists, and another key now occupies its numeric index. Initial or empty loading, a count-changing insertion or deletion, and the search-result list retain their existing behavior. If an ordinary reorder arrives during drag, fling, or another programmatic scroll, the numeric request captures that moment's index and offset and cancels the in-progress scroll without delay, retry, resumption, or a second list-state owner.

A New Chat first Send is the sole automatic-top exception. Only after the accepted conversation and first message graph are durable and that conversation is published, the bounded `firstMessageCommitted` event waits until the same still-selected conversation occupies item `0` in the measured ordinary list. It then uses the existing Drawer `LazyListState` and the canonical `animateToAbsoluteTop` feedback controller with `SendFeedbackScrollSpec`, matching Send's scroll-to-bottom startup envelope, adaptive long-distance motion, ease-out, and user-input cancellation. Reduced Motion directly calls `scrollToItem(0)`. A later explicit conversation selection rejects the stale event. No other reorder, title change, insertion, delay, retry, fallback, or second scroll owner may reveal item `0`.

After durable deletion and runtime cleanup of the conversation that was selected when deletion was admitted, the canonical selection owner enters New Chat unless a newer explicit selection targets another conversation. A pending or completed newer conversation selection remains authoritative. Deleting a nonselected conversation or a deletion that fails before cleanup does not change the visible page.

Deletion is issued by one client (the phone UI or, later, a WebUI session). Every other client that still shows the conversation when it is durably deleted first shows the localized notice `This conversation was deleted on another device.` through its ordinary snackbar and then enters New Chat through the same canonical selection path. The issuing client shows no such notice. Deletion is rejected while any client is submitting into that conversation.
The fork confirmation blocks like the delete confirmation: after Fork is tapped its button shows a progress indicator, Cancel, back and outside taps are disabled, and the dialog stays until the result. On success the dialog closes and the issuing client opens the fork; on failure the dialog closes and the failure snackbar is shown.

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

The Android `VideoSliceDialog` frame-count input uses Material3 `OutlinedTextField` with
explicit `16 dp` rounded corners, consistent with the existing dialog fields. Its localized
frame-count label belongs to the field's floating `label` slot, and its localized between-frame
interval hint belongs to `supportingText`; neither is duplicated as a sibling label. Material owns
the outline, focus and error presentation. Digit filtering, defaults, minimum count, invalid-input
Confirm disablement, the numeric keyboard, mode selection, extraction calculations and callbacks
remain unchanged. This presentation rule does not change attachment-import ownership.

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
deletes an unowned file inside the same Room transaction. Exact process-local producer, Composer,
session-draft and pending/claimed queue references are registered in AttachmentFiles before file
creation or publication and checked atomically at unlink. Producers release after canonical draft
handoff or settled cancellation; shared owners release only their own paths. Queue ownership remains
live through failed claims and releases only after durable Room commit or exact-item disposal.
Composer scope completion releases remaining references after child work settles; process restart
cannot restore live references. Exact cleanup debt blocked only by a live reference remains pending
in the existing maintenance worker; Room ownership or successful unlink completes that debt.
Session draft replacement schedules removed paths through the same repository, without retaining
obsolete artifacts until disconnect. Reconciliation must repeat this atomic
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

## 26. Chat context usage popup
The context popup opened from the composer's context ring heads with the usage text
`~used / budget tokens` in `titleSmall`, followed on the same row by the right-aligned usage
percentage in `titleSmall`. The percentage is `round((System + Tools + Messages) / budget * 100)` as a
whole locale-formatted percent; the compaction reserve is never counted as usage. The former
`Context` title and the footer usage line are not shown; `Context` remains the ring's accessibility
label. The composition bar is unchanged. Legend rows are spaced `6 dp` apart, use `bodyMedium` for
both label and value, and carry `10 dp` color dots. Verification covers the percent rounding, reserve
exclusion, and zero-budget result.
## 27. Interaction card exit, lift, and option shape
A card that leaves, including after Send answers every question, keeps the page and fold it showed
until it has finished leaving; the host clears the conversation's saved page and fold only after
the card is gone and only when that conversation has nothing left to answer. The lift applied to the
scroll-to-bottom control has one writer: the measured card height multiplied by the card's own
appear/leave progress (the card's `180 ms` enter/exit tween, snapped under Reduced Motion), so the
lift grows and shrinks with the card and is exactly zero once no card is shown. Question option rows
clip their highlight and ripple to a corner radius of `min(height / 2, 24 dp)`: a capsule for a
single-line option, `24 dp` for a wrapped one. Verification covers the leaving page, the monotonic
lift ending at zero, the post-exit gone callback, and both option radii.
## 28. Composer model selector motion
The Chat bottom-bar model selector follows the section 24 motion language through the same shared
clip owner (`ui/motion/IdentityClipWidth.kt`, also used by the top-bar title). A change of the
displayed label Crossfades the label over `200 ms` with `FastOutSlowInEasing`. The button is always
laid out at its independently measured final width: label width plus `8 dp` padding on each side,
at least the Material button minimum width and at most the space left in the controls capsule. One start-anchored rounded clip
(`50%` corners) cuts the whole button, including its ripple, at the visible edge, which moves over
`400 ms` with `FastOutSlowInEasing`, rebases toward a newer target within the same deadline, and
ends exactly on the latest target. The selector's slot takes the clip width, so the controls after
it follow the visible edge. `animateContentSize` does not participate. Initial composition presents
the final width without motion; Reduced Motion snaps the clip and keeps the Crossfade. Verification
covers the shared owner's deadline, mid-motion rebasing, target-only updates, initial presentation,
and Reduced Motion snap. Each Crossfade label keeps its own width while it fades, so an outgoing
longer label is cut only by the clip; a label ellipsizes only past the space left.
## 29. Composer controls width, user bubble, and small indicators
The composer controls capsule may grow from the bar's inner start edge up to the send button minus a
fixed `14 dp` gap, in ordinary and externally owned conversations alike. Every control in it has a
fixed width except the model selector label, which is the only flexible child; there is no fixed
label cap. User message bubbles (`UserBubbleShape`) give the top-start, top-end, and bottom-start
corners one shared radius, `min(27 dp, half the bubble's smaller side)`, and keep a `6 dp` bottom-end
tail (never larger than that radius). The three large corners always match, including short or
narrow bubbles; `RoundedCornerShape` is not used because it shrinks each side's corner pair on its
own. The radius does not grow with bubble height. A bubble is at least `54 dp` wide (its single-line height), so a
one-character message is a circle; content narrower than that is centered. Content padding is `15 dp` in both reading and edit mode
(the edit field adds only an `8 dp` gap above its indicator and drops the text field's `56 dp`
minimum height, so a single line keeps its indicator `8 dp` below the text); its bottom inset is `4 dp`
because the Cancel/Send buttons already carry `10 dp` of invisible height below their labels, less
`1 dp` tuned by eye. The `15 dp` reading padding makes `27 dp` half of a single-line bubble. Attachments inside the bubble (images, video,
files, PDFs) use `15 dp` corners, equal to the content padding (user choice over the strictly
concentric `12 dp`); the composer attachment preview keeps its own `8 dp`. The `ask_user` interaction
capsule and its Settings toggle use the outlined help icon (a question mark in a circle). In the capsule's question card only the question text is long-press selectable (`NoAutoScrollSelectionContainer`, like message bubbles); option rows and the answer field are not. The drawer
search indicator shows exactly while the newest search runs: a cancelled search never clears it.
The conversation switching overlay keeps its full-body background and centers its indicator between
the top bar and the measured bottom bar, so it follows the IME like the welcome text.
## 30. Dropdown shape
Every dropdown and exposed dropdown goes through `AgoraDropdownMenu`, `AgoraExposedDropdownMenu`, and
`AgoraDropdownMenuItem` (`ui/components/AgoraDropdownMenu.kt`); raw Material menu calls are not used
elsewhere. The wrappers own the geometry and take no shape parameter: every menu has a `24 dp`
corner, and every item's press and hover highlight is clipped to a `24 dp` corner, which is a
capsule at the `48 dp` item height. The highlight is inset `8 dp` from the menu sides, matching the
`8 dp` Material leaves above the first and below the last item, and item content padding drops
from `12 dp` to `4 dp` so item text stays where it was.
## 31. Dialog and sheet option highlight
Option rows in dialogs and bottom sheets use the dropdown item highlight: `Modifier.optionClickable`
(`ui/components/AgoraOptionHighlight.kt`) clips the press and hover ripple to the same `24 dp`
corner, for one- and two-line rows alike. Dialog rows use it directly because dialog content is
already inset from the container. Rows that span a bottom sheet's full width use
`Modifier.sheetOptionClickable`, which also insets the highlight `8 dp` from both sides; those rows
drop their own horizontal padding by `8 dp` (`SETTINGS_ITEM_SHEET_PADDING` for `SettingsItem`) so
content stays where it was. Controls that are already rounded or circular (citation source rows,
segment cards, calendar days) and clicks without an indication are unchanged.
## 32. Bottom sheet corners
Every bottom sheet has the same `28 dp` top corners (Material's extra-large corner), from`
`BOTTOM_SHEET_SHAPE` in `ui/components/DialogWindowEdgeToEdge.kt`. `MotionAwareModalBottomSheet` and`
`SmoothBottomSheet` own the shape and take no shape parameter.
## 33. Secret field visibility
Every secret text input (API keys, passwords, tokens, MCP header values) is masked by default and
has a trailing eye button that shows or hides its value. `ui/components/SecretFieldVisibility.kt` is
the only owner: `rememberSecretVisible()` (plain `remember`, so the value is hidden again whenever its
page or dialog leaves composition), `secretVisualTransformation(visible)`, and
`SecretVisibilityToggle` (open eye while hidden, crossed eye while shown; described as
`secret_show` / `secret_hide`). No other file uses `PasswordVisualTransformation`.
## 34. Composer insets
The non-expanded composer keeps its `28 dp` outer radius. The controls capsule and the send button are
both `48 dp` high (`COMPOSER_CONTROL_HEIGHT`) and sit `10 dp` from the start/end and bottom edges; the
owner chose this size over concentricity with the outer corners. Inside the capsule the `32 dp`
buttons sit `8 dp` from its ends and the `38 dp` model selector is centered, so both stay concentric
with the capsule. The `20 dp` expand icon sits `18 dp` from the top and end edges
(`COMPOSER_CORNER_CONTENT_INSET`); the input text starts `18 dp` from the start edge
(`COMPOSER_TEXT_START_INSET`) and `16 dp` from the top (`COMPOSER_TEXT_TOP_INSET`), and ends `22 dp`
above the controls (`COMPOSER_TEXT_CONTROLS_GAP`). The
TextField's Material `56 dp` minimum height is replaced so a single line leaves no empty band. The expand button's circle is a fade to transparent, so only its icon is placed. The host padding shared by status rows, attachment previews and the
expanded collapse button is unchanged (`4 dp` sides, `8 dp` top). The constants live next to
`CHAT_BOTTOM_BAR_OUTER_RADIUS` in `ChatBottomBar.kt`. The expanded composer is not covered by this rule.
The input's scrollbar track starts level with the expand icon's top (`COMPOSER_CORNER_CONTENT_INSET`
below the composer top), so the thumb is never cut by the rounded corner.
A display formula that fits the message width does not claim horizontal drags, so a swipe over it
still opens the drawer; only an overflowing formula scrolls. The Remote model menu re-reads the
device's model catalog each time it opens.
## 35. Automatic message source
A user bubble the app sent on the user's behalf (a Task run prompt, a Loop cycle prompt, or the
answers to non-blocking `ask_user` questions) shows a label above the bubble, end-aligned: a `14 dp`
icon and the source name in `labelSmall`, both `onSurfaceVariant` (Task: Schedule icon,
`message_source_task`; Loop: Repeat icon, `message_source_loop`; Ask User: QuestionAnswer icon,
`message_source_ask_user`). The label names the source only. An ask_user bubble lists each question
(dimmed to `ASK_USER_DIM_ALPHA` and one size smaller, `14 sp` against the `15 sp` body,
`ASK_USER_QUESTION_FONT_SIZE`) with its answer on the next line, groups separated by a blank line; a
skipped question shows the dimmed `message_source_unanswered`. Only a wrapped question's own lines
are tighter (`20 sp`, `ASK_USER_QUESTION_LINE_HEIGHT`); `AskUserAnswerBlocks` lays questions and
answers out as separate blocks and pads back the space above each question and below its last line,
measured from the font, so the question-to-answer gap, the blank line between groups and the bubble
edges stay what a single `15 sp` / `24.2 sp` text gives. Search highlights map onto each block by its
offset in the stored text; the localized unanswered label is never highlighted. Copy and Select Text use exactly the
displayed text. Typed messages, blocking answers, Compact summaries and messages from before this
feature have no label. Editing and resending a labeled message produces an ordinary unlabeled message.
`ui/chat/message/MessageSourcePresentation.kt` owns the label and the ask_user text.
## 36. WebUI settings
Settings > Network has a WebUI page (`ui/settings/SettingsWebUiPage.kt`). In order it holds the
enable switch, the Set/Change Password action below it, the port (default `8686`, accepted range
`1024`-`65535`), the access addresses, and a Security group. While HTTPS is off the access group
also shows a warning that the connection is plain HTTP; the warning is not shown under HTTPS. The
Security group has an HTTPS switch (on by default); while HTTPS is on it shows the certificate
SHA-256 fingerprint in the mono font (selectable, so it can be compared with the browser's
certificate view) and a Regenerate Certificate action. Regenerating asks for confirmation first,
because every browser must accept the new certificate again. While the certificate is built the
dialog stays open, its Regenerate label is replaced by a `20 dp` spinner (as in the delete
confirmations) and Cancel is disabled; the dialog closes once the new certificate is in place. The
access addresses and the notification use `https://` or `http://` to match the mode. Under HTTPS
the session cookie carries `Secure` on TLS connections. With HTTPS on, every plain HTTP request,
including loopback, receives a temporary `307` redirect to HTTPS on the same configured public port.
The encoded path and query are preserved, and the redirect does not change the request method.
The plaintext pre-routing gate serves no application content, issues no cookies, and performs no
authentication, sync, upload or other business action. Invalid Host authorities or request targets
are rejected rather than used in a redirect. With HTTPS off, ordinary HTTP serving is unchanged.
The self-signed certificate (EC P-256, 10 years, SANs for
`localhost` and the current IPv4 addresses) is kept in `noBackupFilesDir/webui` with its keystore
password sealed by `SecretCrypto`, and is reused until regenerated. The switch is
never grayed out. Without a password its supporting text says one is needed, and tapping it leaves it
off and shows the same message in a snackbar (`webui_password_required`). With a password the
supporting text shows the live status (Off, Starting, Running on a port, or the start error); it
reaches Running as soon as the server listens. The access group lists addresses only while the server
runs and otherwise says they appear then. A password has at least `8` characters; its field uses the
section 33 secret-field owner. Sign-in survives browser close and Agora/WebUI restart until logout
or password change. The existing device-local Settings DataStore stores only session-token digests;
password replacement atomically clears them and rejects a login verified against an older hash.
Logout revokes only its token and ends its open sync/attachment work. No routine server expiry is
added. The persistent HttpOnly/SameSite=Strict cookie is renewed on authenticated checks within the
browser retention ceiling; browser-cleared/expired cookies require sign-in again. Service stop or
port/HTTPS changes do not revoke sessions. Changing the password signs out every browser. A port edit is saved
only after typing pauses for `800 ms` (`PORT_COMMIT_DELAY_MILLIS`); an out-of-range value is not saved.
The browser pages follow the app's look. Inside `AgoraTheme`, `PublishWebUiTheme` hands the resolved
Material color scheme (preset or wallpaper colors, light or dark, AMOLED) and the Appearance font to
the controller; `GET /theme.css` serves them as `--md-<role>` variables and `--app-font`, and
`GET /fonts/app` serves the bundled Mi Outfit or the imported font (none for the system font). The
web styles use the app's type scale and Material 3 metrics: a `28 dp` dialog-like card on
`surfaceContainer`, `16 dp` outlined fields with a floating label and an eye toggle, `40 dp` capsule
buttons. A theme change reaches a browser on its next page load.
After sign-in the browser mirrors the app's chat screen (`assets/webui/shell.js`, `style.css`) and
invents no layout of its own; Settings and Remote pages and drawer entries are absent.
The normal browser message foreground spans the chat viewport behind the composer. One CSS alpha
mask stays opaque above the measured composer-host top, fades over the next 40px, and stays
transparent below it, revealing the existing background rather than a painted color cover.
The existing Shell border-box measurement includes the host's 12px lift once. An equal bottom
content inset preserves row coordinates, numeric scroll range and initial-bottom ownership.
The mask remains with App Blur Effects off or Reduced Motion on; composer, top bar, loading cover,
menus and detail sheets are outside it. Disabled expanded-composer mode is not implemented here.
WebUI Blur Effects and Reduced Motion follow the App's stored preferences only, not the browser or
its operating system's reduced-motion preference. No browser settings or switches are added.
The signed-in shell receives both values through the existing display event and applies updates
without replacing its details or media preview. Reduced Motion snaps spatial transitions and stops
continuous indicators; component-owned color and opacity feedback, including the tool image's
200 ms loading/loaded/failed crossfade, remains. Disabling Blur Effects removes the detail backdrop
blur without changing its scrim, geometry, input ownership, focus, or scroll position.
The browser TopBar keeps its title content on stable final constraints, capped at 260px after the
98px actions and 16px gap. Its existing owner measures the natural title and draws one start-anchored
rounded boundary over 400ms; the same boundary drives the surface, shadow and content reveal.
Brand/conversation ID/title identity changes crossfade title-only snapshots over 200ms. Both use
FastOutSlowIn; initial composition is stable, interruptions retain current values and geometry-only
changes rebase toward the latest target within the original deadline. App Reduced Motion snaps only
the spatial boundary. Outgoing labels are noninteractive and hidden from accessibility. Context
subtitle data remains separate unfinished work, not a fabricated value in this title-motion stage.
WebUI conversation loading uses the circular cover below. Its existing More menu mirrors pinned
Material3 1.4.0 Standard FastSpatial scale (0.8 to 1) and FastEffects opacity (0 to 1), with the
anchor/menu intersection pivot and retained popup through settled exit. Rapid target changes retain
the current values and velocities, not a remounted menu or queued animation. App Reduced Motion
snaps scale and retains opacity feedback. The popup owns covered input and focus through exit;
outside dismissal does not activate the underlying chat. Geometry, item order and enablement remain.
The requested loading overlay is specifically for opening or switching a conversation, matching
ChatApp's content-area cover, measured top/composer available-range centering, 200ms opacity and
touch exclusion through retained exit. It does not add a login-session-check, drawer-list,
per-message hydration, generation or background-reconnect cover. These effects follow the App-only
appearance policy above.
The existing MessageList initial-bottom owner ends this cover only after the selected path arrives,
visible watched row bodies (or the current streaming row) exist and bottom layout settles. It does
not hydrate all history, wait for generation or media downloads, or run another scroll actor. Empty
ready paths settle directly; failed/deleted selections exit, and stale selection work cannot finish
the current cover. The message viewport stays inert and the cover consumes new pointer, context-menu
and wheel input through its retained 200ms exit; top controls, drawer and composer retain ownership.
Its primary ring is 48px with a 5px stroke; App Reduced Motion stops rotation but retains the fade.
Browser visual verification must load the actual App font and theme; empty theme.css or an absent
font resource is not font-parity evidence. Pending chat actions must not be described as a completed
WebUI. The reported
green drawer-row outline is keyboard focus, not conversation selection; the report does not define
a replacement focus style or authorize removing keyboard feedback.

Tasks remains visible but disabled until its browser behavior is approved; other controls the browser
cannot use
yet are shown as in the app but disabled. Tonal surfaces use Compose's `surfaceColorAtElevation` mix (primary over surface at
`(4.5 ln(e + 1) + 2) %`). The top bar is the `ChatTopBar` new-chat state: a `52 dp` row inset
`12 dp` at the sides and `8 dp` above and below over the fading background, a title capsule
(`4 dp` tonal, `4 dp` shadow, at most `260 dp`) with the `44 dp` Menu button (`26 dp` icon) and the
brandTitle wordmark, and a `98 dp` actions capsule with New Chat (`30 dp` icon) and More (`26 dp`).
More opens an `AgoraDropdownMenu`-shaped menu (Search and System Prompt disabled, plus a web-only
Sign Out). The drawer is `ChatDrawerContent`: `min(width, 360 dp)`, `1 dp` tonal, `24 dp` end
corners, `16 x 20 dp` padding, the Conversations title (`25/32` bold), the `44 dp` search capsule,
the Tasks standalone disabled tonal button (`46 dp`, fully rounded), the `42 dp` New Chat button,
and the list below. As in `ChatDrawerHost`, the drawer overlays the chat with a
`32%` scrim up to `960 dp` wide (`DRAWER_MAX_WIDTH + CHAT_APP_WIDTH_THRESHOLD`) and sits beside the
narrowed chat above that; it starts closed and opens from the Menu button. The open modal drawer is
`role="dialog"` with `aria-modal`, the chat behind it is inert, Escape or the scrim closes it, and
focus returns to the Menu button.
The drawer's single progress owns its offset, side-by-side chat inset and modal scrim opacity. It
settles over 300 ms with LinearOutSlowInEasing, or snaps under the App's Reduced Motion setting.
Modal horizontal dragging can take over an in-flight settle at its current visible position;
pressing during a settle freezes that progress, but only horizontal intent claims pointer capture.
An ordinary tap retains its original control's click, and vertical input resumes the same target.
Release follows the Compose velocity-direction rule, or the half-width threshold at rest. Vertical
scrolling, text selection and real horizontal-scroll controls retain their input ownership. Pointer
cancellation releases capture and returns to the existing target. Selecting a conversation closes
only the modal drawer; the desktop side-by-side drawer stays open. Focus and modal input exclusion
remain through the close transition, and window changes preserve the selected conversation.
Modal drawer focus and keyboard admission start at the same layout commit that makes the chat
inert. Escape is available before the first animated frame; Tab stays in the drawer through exit.
Focus returns only after the closed chat is no longer inert; a retained menu keeps keyboard priority.
The composer is the `ChatBottomBar` card: at most `840 dp`, `2 dp` tonal, `8 dp` shadow, `28 dp`
corners, the Ask Agora field (input `16/23`, 6 lines), the `40 dp`
expand button, and the controls row with the `48 dp` control group (attachment, model selector,
tools) and the `48 dp` send button. English and Chinese labels are the app's own strings. The
sign-in page centers its card with flex and caps it at `400 px`: a grid's auto track sized to the
card's max-content and pushed it past a narrow screen once the app font loaded.
The browser's message details mirror `SegmentDetailSheet` for Thought, Transcription, and Tool. In
Grouped/Compact Bottom Sheet mode, the group header opens a segment list; ordinary Timeline
cards and inline Grouped/Compact rows open the selected detail directly. Tool details consume the
shared typed presentation, including lifecycle, shell/file/search results and prefix-aware JSON
nodes; they never parse tool-result envelopes in the browser. Failed/stopped details retain the
shared unboxed neutral terminal text. Persisted tool images are requested only from authenticated
`GET /api/tool-images/{conversationId}/{messageId}/{detailIndex}/{imageIndex}` with original
attachment indices. Each request revalidates message ownership, real-path containment in the private
tool-media store, raster MIME and recorded size; browser paths and inline image bytes are forbidden.
The preview keeps its Compose-sized viewport through loading, failure and decoding, follows square
crop metadata, and opens a full-image viewer. The browser-local sheet uses 45%/94%
viewport anchors, scrim and blur, list/detail back and close, Escape, focus return and Reduced
Motion snap. It stays on the selected message while a streaming frame hands off to its durable
payload, preserving detail scroll and focus; switching conversations or removing the selected
message dismisses it. The sheet keeps the selected message watched even when its row is off screen.
WebUI chat actions (owner decisions, 2026-10-01). Each signed-in sync session is one `ChatClient`
of the process-scoped `ChatRuntime`; browser Send, Stop, New Chat, queue, model and tool actions go
through the same runtime owners as the phone and do not require the app to be in the foreground.
The browser composer draft and the browser New Chat workspace (system prompt and tool toggles
before the first send) belong to that browser session only and never overwrite the phone's
persisted draft or New Chat workspace. An existing conversation's model and settings are shared:
changing them in the browser changes that conversation for the phone too. New Chat creates the
conversation on its first send, the phone's selected conversation stays independent, and Stop may
stop a generation the phone started. Sending during a generation queues as on the phone. The top
gradient blur may be a visual approximation; strict pixel parity with `GradientBlur.kt` is not
required.
The approved browser approximation uses four masked backdrop-blur layers within the top 150px of
the existing chat frame, outside the message alpha mask and below the top-bar and composer controls.
It samples the composed backdrop, not Compose's foreground-only shader, and introduces no cloned
message DOM, snapshot renderer or content cache. App Blur Effects off removes the layers; Reduced
Motion does not change this static effect. The layers never claim pointer, keyboard, selection or
scroll input. The bottom mask, message geometry and existing scroll owners remain unchanged.
The browser consumes the canonical session Composer draft, submission phase and runtime activity
through the existing sync channel. Open sequence and edit acknowledgements fence stale selections
and pending input without a second draft-settlement owner. Text stays editable while waiting or
submitting; accepted clearing preserves later edits and focus. Enter inserts a newline. Generating
with an empty draft shows Stop; a nonempty draft shows Send and enters the ordinary queue. WAITING
can be cancelled without stopping attachment imports. New Chat follows its accepted conversation
only while its original entry remains selected, carrying any later input to that composer.
Reconnection never automatically replays Send or Stop. An interrupted unconfirmed submission keeps
the input and asks the reader to check the conversation before sending again.
Runtime accepted-input scroll requests carry the exact open sequence and committed message ID.
The existing MessageList bottom-follow owner consumes them only when that message is on the ready
path; queue admission alone does not move the reader. User input releases bottom following as before.
The model picker uses the phone's valid-model catalog, provider/API-name order, aliases and provider
name visibility. Existing conversations share one field-specific Room model write with the phone;
it cannot replace drafts, branch selections or other conversation fields. Browser New Chat model
selection remains session-local. Ordered model commands settle before the next browser Send tap.
Queue rows mirror ComposerStatusColumn/QueuedMessageRow: chronological text, attachment count and
exact-ID removal. An idle empty composer sends its remaining queue through the existing runtime
drain; an empty composer during generation still stops. No separate queue execution path is added.
Browser attachment transport uses an authenticated same-origin octet-stream POST, never base64 sync
frames. A random connection ID binds each request to the exact signed-in sync connection and its
Composer owner at admission; the login cookie alone never selects a tab. Selecting another chat
cannot retarget an admitted upload. The 100 MiB limit applies to actual streamed bytes as well as
the size hint. Incomplete transport files are deleted; accepted sources enter the shared MIME
classification, staging and Composer processing owners. Connection close settles its children,
removes its lookup and reclaims abandoned session attachments through the existing reference-aware
cleanup. Queued or sent attachments keep their canonical ownership. Reconnect never replays uploads.
Session-local draft retention uses the shared AttachmentFiles lifecycle in section23; it never
persists a browser draft into the phone's Room draft merely to protect files.
The browser attachment menu follows Camera, Photos, Videos and Files. Camera delegates to a native
file input with capture; the browser decides whether to open the camera directly. Attachment status,
retry/removal, PDF page selection and video slicing use the same Composer owners as Compose.
Preview requests identify the authenticated live connection, captured selection sequence, attachment
ID and artifact index, never a browser-supplied private file path. Files outside app-private storage,
Local Sandbox assets, stale selections, unavailable attachments and revoked sessions are refused.
The same preview stream supports one HTTP byte range for native video playback and seeking. Range
responses preserve authentication, file pinning, recorded-size bounds and revocation cancellation;
they do not buffer a complete file or introduce another download endpoint.
Composer presentation lives in composer.js; shell.js remains the chat frame and popup presenter.
Effective tool controls use the same pure Compose projection of global preferences, provider
availability and per-conversation overrides. Existing-conversation edits transform the canonical
SettingsRepository value instead of replacing unrelated fields; New Chat overrides remain in the
browser session and enter its frozen workspace snapshot. Commands retain the selected sequence and
action acknowledgement; stale and unavailable edits are refused. Settings remain editable during
submission as on Compose; the captured New Chat workspace stays immutable. Acceptance consumes only
the matching session-local settings and preserves any later settings edit.
The tools menu follows Compose row order and visibility. Thinking and Service Tier editors consume
server-resolved model capabilities, displayed values and accepted options; the browser never copies
model/provider policy. Stored choices survive model changes. Sliders submit on gesture completion,
switches apply immediately, and no Save action is shown. Both editors reuse the existing message
DetailSheet presenter through its title/content parameters, including drag, keyboard, focus and
App motion behavior. Advanced uses the explicit-save six-parameter draft/reset contract in
settings-ui-ux.md and preserves current tool preferences. Its defaults and token presets are server
projections, not browser provider policy. Manual Compact uses its configured model, prompt and retain
count in an editor before calling the same MessageGenerationController.compactManual with the
captured conversation ID and Preserve System Prompt preference. It never changes ordinary model or
generation preferences. New Chat cannot Compact; Stop and canonical failure handling remain shared.
Both editors discard on cancellation, dismiss on selection/disconnect, and never replay commands.
While the server runs, a specialUse foreground service (`webui/WebUiService.kt`) shows an ongoing
notification with a Stop action; Stop turns the WebUI setting off. If WebUI was left on, opening the
app starts it again from `MainActivity.onResume`; it is never started from the background.
`webui/WebUiController.kt` owns server state; the page only reads it and calls the controller.
The `webui_*` keys, including the password hash, are device-local and never enter the portable
settings archive.
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
