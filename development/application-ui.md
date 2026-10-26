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
Remote initial opening and explicit empty-conversation retry request the canonical cover's 200ms
opacity entrance even on first composition, followed by its retained 200ms exit. The empty body itself
has no entrance fade. Background recovery preserves the failed page and Retry without restarting a
cover, and loaded history remains readable. Ordinary ChatApp's initial visibility policy is unchanged.

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
change the existing Grouped availability rule, the exact Grouped + Card Auto-Expand rule, or
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

Compose and WebUI show the Agora title and a trailing search icon while drawer search is collapsed.
Explicit activation expands the existing capsule to the entire first row; the Agora title fades out
over 180 ms and fades back on return. The search field is not a permanent second row. Its leading return button
collapses it, clears the query and restores the ordinary conversation list. Back while search is
expanded performs that same collapse first, including for an empty query; a subsequent Back uses
normal drawer dismissal. Browser Escape follows the same order. Menus and dialogs retain priority,
and the existing side-by-side drawer dismissal policy stays unchanged. The trailing clear
button only clears the query and leaves the capsule expanded. Only once the drawer is fully closed,
it clears the query, releases field focus and exits expanded search. An interrupted close that stays
visible preserves search; losing field focus alone does not reset it.
The first row and search capsule are 56 dp high. The collapsed search control and persistent leading
Search/Return button are 56 dp square, with a 24 dp icon. The logical end stays fixed while the
capsule expands to 100% of the row over 400 ms LinearOutSlowIn in either direction, starting quickly
and slowing toward its destination, retargeting from the current width if interrupted. Reduced Motion snaps width. Collapsed title typography stays 25/32 bold
with one-line ellipsis and an 8 dp gap to the control. Explicit opening focuses the field; Return
releases input focus. Losing focus alone does not collapse search. The trailing 28 dp progress/clear
slot is inset 14 dp from the capsule's top, bottom and logical end; its 18 dp ring and glyph share
one center. Title fading also snaps under Reduced Motion.
These presentation changes preserve the existing query, result, cancellation and loading owners.

Opening an existing conversation binds covered layout settlement to that destination's scroll
coordinator. Selection publication and switching readiness may arrive in either order; the previous
destination must not begin waiting on its own hydration registry for the incoming page. Completion
still requires the current destination's loaded conversation and stable layout. Context projection
readiness has an eight-second wait bound; reaching it continues layout settlement while accounting
runs on, with rollout disabled until a matching successful projection completes.
Send consumes its scroll request only after the target turn has a measured message index. The final
absolute-bottom sentinel is excluded from this range, so a new turn cannot match the old sentinel
before the list has measured the insertion.

The conversation drawer observes only the conversation fields required by navigation, selection, display, and the system-prompt dialog; it never materializes draft text, draft attachment metadata, or branch-selection blobs for that list. Its first emitted snapshot is loading, distinct from a genuinely empty library, and a motion-aware circular indicator fades in and out over the list area.

Conversation search exposes a separate in-flight state from the moment a nonblank query is accepted through debounce and the existing literal/semantic query. Its circular indicator fades in and out in the search field, does not alter query debounce or ranking, and cancellation, clearing, or failure cannot leave a stuck indicator. The retained prior result may remain visible while a new query is pending.

The drawer's first-list state is not a second conversation authority or a new search architecture; Room remains the durable source and the existing search methods remain authoritative.

The Android drawer's ordinary conversation row shows its existing generation indicator while that
conversation generates or any linked Subagent is running. The parent remains active until both its
own generation and all child activity end, including when only one of several children finishes.
The existing indicator appearance, unread priority and child-row visibility remain unchanged.
`ChatViewModel.generatingConversationIds` consumes the repository's presentation-only union of
foreground generation, automation and registered delegation Jobs, including each active child's
parent ID. Parent membership is observed in one narrow Room query restricted to those active IDs;
idle startup performs no child/Run/message scan, and no per-row query or polling is introduced.

Drawer long press offers a push-pin `Pin` action, replaced by `Unpin` for a pinned conversation, then
`Generate Title`, `Rename` and `Delete` in that order. Active generation alone never disables
these metadata/lifecycle actions. Switching/loading and actual Send-submission exclusion remain
separate guards. Manual title generation uses the selected branch's first USER message under the
shared title-input contract in message-generation.md, including while a reply is streaming. Empty
input makes no Provider call. Repeated requests and late results must not overwrite a newer rename,
newer title decision or deleted conversation, nor interrupt ordinary generation. Delete retains the
existing confirmation and waits for canonical generation/Loop/owned-child cleanup before durable deletion.
The ordinary current-conversation top-right menu also offers `Rename` and `Delete`, after `Share`,
on native and WebUI, reusing these same dialogs and lifecycle actions. Their target and stored title
are captured when requested, including for a child absent from the drawer. Active generation alone
does not disable them; no target, switching/loading, actual Send submission and existing modal
exclusions still apply. Remote's custom menu is unchanged.
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

After durable deletion and runtime cleanup of the selected conversation, the canonical selection owner opens its surviving parent when the deleted conversation was a Subagent; ordinary roots and children with no surviving parent enter New Chat. A pending or completed newer explicit selection remains authoritative, including New Chat. The parent identity is read at deletion admission before the child disappears, and parent availability is rechecked by the existing selection path. Deleting a nonselected conversation or a deletion that fails before cleanup does not change the visible page.

Deletion is issued by one client (the phone UI or a WebUI session). Every other client that still shows the conversation when it is durably deleted first shows the localized notice `This conversation was deleted on another device.` through its ordinary snackbar and then follows the same surviving-parent-or-New-Chat selection rule. The issuing client shows no such notice. Deletion is rejected while any client is submitting into that conversation.
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

Conversation deletion shows the selected-page cover, validates submission and captured topology,
then stops Loop and awaits canonical generation persistence and coroutine settlement outside the
execution lock. It next attempts immediate admission through the existing execution coordinator;
a new owner or live Run rejects deletion and releases only its own cover. Successful admission
revalidates submission/topology and reads the parent identity before storage mutation. Cancellation
still delivers the completion callback and clears only the cancelled request's cover, including
during the cover fade. No second Stop or selection pipeline is introduced.

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
reserved its fixed width. New Chat shows only the menu button inside a `52 dp` circle, without an
Agora wordmark. Entering New Chat shrinks the existing clip to that circle; leaving it expands the
same clip to the resolved conversation title. The menu glyph compensates for the unchanged `5 dp`
start inset of its `44 dp` hit area by shifting `1 dp` left (`1px` in WebUI), aligning its center
with the visible `52 dp` circle. Its button hit area, circular background and title layout stay unchanged.
Existing conversations with an unloaded/blank title
retain the brand fallback. New-chat/brand/conversation-title identity changes Crossfade the title presentation
over `200 ms` with `FastOutSlowInEasing`. The title content is always measured and laid out against
its latest stable final constraints inside a canvas capped at 260 dp; no animated value participates
in the capsule, Crossfade, or Text layout width. An independently measured natural target controls
only one left-anchored rounded drawing clip over `400 ms` with `FastOutSlowInEasing`, so the capsule
background, shadow, and content reveal together while only the visible right edge moves.

The title-motion identity includes the selected conversation and its visible title; the brand state
has one stable identity and New Chat has a separate menu-only identity. Token-subtitle appearance, disappearance, and value changes update the final
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
After successful durable execution and schedule settlement, optional execution-title generation runs
as app-scope post-processing through the existing title owner. It cannot hold the Task reservation,
Running presentation, conversation execution guard or automation gate. Its failure cannot change the
already successful execution, and recovered terminal executions do not start title generation again.

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
Both Compose and WebUI resolve category colors from the current Material theme: System prompt uses
primary, Tools secondary, and Messages tertiary. Free stays surfaceVariant and Reserved outline;
over-threshold Messages uses error. Bar segments and legend dots use the same roles after any preset,
style, dynamic-color, light/dark or AMOLED change. Total-only Remote usage remains primary.
## 27. Interaction card exit, lift, and option shape
A card that leaves, including after Send answers every question, keeps the page and fold it showed
until it has finished leaving; the host clears the conversation's saved page and fold only after
the card is gone and only when that conversation has nothing left to answer. At that same completed
exit, the browser releases the old page drawing record and cancels its page animations. A newly
composed card presents its first question directly; Next/Back within a live card retain their
350 ms page transition. A retained outgoing page is draw-only: its cloned radio controls lose their
name before insertion so browser grouping cannot uncheck the live page. Live question inputs use
radio/checkbox type identity so a type change creates the correct native control rather than
converting checked controls into a radio group. The outgoing page remains inert and
accessibility-hidden until its existing exit finishes. Selected radio dots
and checkbox marks remain painted when Send disables admission and through the card's retained
exit; disabled feedback may dim them but must not turn a checked control into an empty ring.
Ask User and shell-trust rows own the shared hover/press indication; their visual radio/checkbox
has no independent hover or focus halo, matching Compose's noninteractive child controls.
No extra disposal delay or second selection snapshot is introduced. The lift applied to the
scroll-to-bottom control has one writer: the measured card height multiplied by the card's own
appear/leave progress (the card's `180 ms` enter/exit tween, snapped under Reduced Motion), so the
lift grows and shrinks with the card and is exactly zero once no card is shown. Question option rows
clip their highlight and ripple to a corner radius of `min(height / 2, 24 dp)`: a capsule for a
single-line option, `24 dp` for a wrapped one. Verification covers the leaving page, the monotonic
lift ending at zero, the post-exit gone callback, and both option radii.
## 28. Composer model selector motion
The Chat bottom-bar model selector follows the section 24 motion language through the same shared
clip owner (`ui/motion/IdentityClipWidth.kt`, also used by the top-bar title). A change of the
displayed label Crossfades through `ui/motion/IdentityLabelCrossfade.kt` over `200 ms` with
`FastOutSlowInEasing`; Phone Use status consumes that same label transition and shared clip owner.
Phone duration updates text directly and uses only the shared clip for format carries. The button is always
laid out at its independently measured final width: label width plus `8 dp` padding on each side,
at least the Material button minimum width and at most the space left in the controls capsule. One start-anchored rounded clip
(`50%` corners) cuts the whole button, including its ripple, at the visible edge, which moves over
`400 ms` with `FastOutSlowInEasing`, rebases toward a newer target within the same deadline, and
ends exactly on the latest target. The browser's existing model-selector clip is the sole native
button, hit target and Material indication host, so a shrinking clip never truncates feedback at the
smaller incoming content width. Its inner target-width content remains independently measured and
never receives animated text-layout constraints. This owner-approved browser host correction does
not change the Compose implementation. The selector's slot takes the clip width, so the controls after
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
concentric `12 dp`). The rounded mask belongs to the visual thumbnail, never the filename below it;
file captions retain their current width, typography, one-line ellipsis and file-opening click action.
An attachment-only user bubble composes no ordinary body-text row and no attachment-to-text gap.
As in UserMessageBubble, the attachment row adds its 8 dp bottom gap only when raw message text is
nonempty; whitespace is not trimmed to decide this. The ask_user body keeps its existing structured
presentation. Empty-body rendering must not break context menus, search, focus return or editing.
WebUI user-message actions stay beside the bubble when a hover-capable viewport has enough room.
If their combined natural widths do not fit, the actions wrap below the bubble, end-aligned, without
shrinking its existing body width. Touch layouts keep their visible actions below the bubble.
They never precede the bubble vertically; edit/menu behavior and focus ownership remain unchanged.
The composer attachment preview keeps its own `8 dp`. The `ask_user` interaction
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
already inset from the container. Checkbox-list dialog rows follow this same rule, including
export and automatic-backup categories through the shared `CheckRow`; checkbox state/callbacks
and dialog confirmation/dismissal remain unchanged. This applies to every option picker,
including Context's Compact-model chooser; changing which settings page owns the picker
never exempts its rows. Audit dialog/sheet call sites and shared row helpers, not only
files named Dialog. Rows that span a bottom sheet's full width use
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
device's model catalog each time it opens. Remote always retains the bottom-bar details button
and Thinking row; unsupported, loading or unavailable Thinking settings disable the row and its
open-sheet controls. Service Tier visibility and availability follow the selected source and
session capabilities in remote-sessions.md; a source without Service Tier omits that menu row.
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
feature have no label. All user messages carrying Task, Loop, Ask User or Subagent source metadata
are read-only: their existing long-press Edit item remains visible but disabled, and canonical edit
admission rejects them. Ordinary unmarked typed messages retain the existing edit behavior.
Subagent completion receipts use a compact bubble showing only the child name and localized status.
Native and WebUI use the ordinary user bubble's primaryContainer background and paired
onPrimaryContainer text for both the SemiBold name and Medium status. These roles follow the
current light/dark/dynamic theme. Keep the existing bubble shape, font sizes, line heights,
spacing and actions; add no status icon.
Every state, including failed and stopped, hides the result body, failure detail and error code.
They show no inline result preview or expand action; the existing Select Text sheet exposes the
complete original result, and Copy keeps that
same complete text. Full UUID and protocol status wrappers do not occupy the timeline body.
Subagent instructions without completion receipts retain their ordinary visible task text.
The existing MessageSource carries optional structured result display metadata; legacy completion
rows retain a safe compact display without inferring success or failure from generated prose.
`ui/chat/message/MessageSourcePresentation.kt` owns the label and automatic-source presentation.
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
confirmations) and Cancel is disabled; the dialog closes once the new certificate is in place.
The access group retains its localized `Access` heading. Its address item has a localized `Links`
headline in `bodyLarge` Medium with `onSurface` color and `4 dp` separation before the existing
status/description. The headline is present while running, stopped, or without network addresses.
The controller advertises only usable local-access IPv4 addresses: connected LAN interfaces,
local tethering interfaces, and supported peer-access VPN interfaces. Cellular uplinks, proxy-only
TUN interfaces, unrelated virtual adapters, loopback, link-local, unspecified and multicast
addresses are excluded. A private IPv4 range alone does not establish an inbound access link.
The Links item and notification share this filtered address owner; no connectivity probe, polling
loop or server-binding change is introduced.
The access addresses and the notification use `https://` or `http://` to match the mode. Under HTTPS
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
menus and detail sheets are outside it. Expanded Composer uses its background cover instead.
WebUI Blur Effects and Reduced Motion follow the App's stored preferences only, not the browser or
its operating system's reduced-motion preference. No browser settings or switches are added.
The signed-in shell receives both values through the existing display event and applies updates
without replacing its details or media preview. Reduced Motion snaps spatial transitions and stops
continuous indicators; component-owned color and opacity feedback, including the tool image's
200 ms loading/loaded/failed crossfade, remains. The detail sheet uses a flat scrim with no backdrop
blur regardless of Blur Effects; its scrim, geometry, input ownership, focus and scroll position
remain unchanged when that setting changes.
The browser's one-line titles truncate inside their own bounded text box with a visible
ellipsis; the capsule boundary reveals that box rather than truncating unbounded glyph content.
The browser TopBar keeps its title content on stable final constraints, capped at 260px after the
98px actions and 16px gap. Its existing owner measures the natural title and draws one start-anchored
rounded boundary over 400ms; the same boundary drives the surface, shadow and content reveal.
New-chat/brand/conversation ID/title identity changes crossfade title-only snapshots over 200ms.
New Chat has no wordmark and targets a `52px` menu-only circle using that same boundary owner. Both use
FastOutSlowIn; initial composition is stable, interruptions retain current values and geometry-only
changes rebase toward the latest target within the original deadline. App Reduced Motion snaps only
the spatial boundary. Outgoing labels are noninteractive and hidden from accessibility. The context
subtitle uses the same priced context presentation as the composer ring, with the Compose title/subtitle
width and typography. While a new target is unpriced, that presentation retains its last known figure.
WebUI conversation loading uses the circular cover below. Its existing More menu mirrors pinned
Material3 1.4.0 Standard FastSpatial scale (0.8 to 1) and FastEffects opacity (0 to 1), with the
anchor/menu intersection pivot and retained popup through settled exit. Rapid target changes retain
the current values and velocities, not a remounted menu or queued animation. App Reduced Motion
snaps scale and retains opacity feedback. The popup owns covered input and focus through exit;
outside dismissal does not activate the underlying chat. Geometry, item order and enablement remain.
The requested loading overlay covers existing-conversation switching, existing-branch switching,
message deletion, Fork navigation and deletion of the selected conversation. Overlay-triggering
confirmations first replace their action label with the native 20px/3px primary indicator and block
repeat, Cancel, Escape and outside dismissal. The identified cover may fade behind the retained
modal; deletion still waits for opacity 1. The canonical result, not path changes, settles the
confirmation: success exits it through the shared retained dialog motion; delete failure keeps its
original target and restores controls, while Fork failure exits with the existing failure snackbar.
The cover cannot reveal the chat before that confirmation's successful exit and page stabilization.
Explicitly entering New Chat is the exception: the existing selection owner updates
its connection/sequence and uses the existing retained outgoing-page and welcome fades, with no loading
cover or indicator. It cancels a superseded cover intent without replaying its command; authoritative
composer receipts retain their exact identity fences.
The existing sync owner admits one identified covered intent; MessageList completes the native
200ms opacity 0-to-1 before selection, command dispatch or mutation starts. Load and switch occur
only under opacity 1. An identified row receipt names the committed target, while the ordinary path
subscription remains the display authority. Current-page bodies, shared renderer, finite presentation
animations, font metrics, measured composer inset and the existing scroll owner must be ready and
stable before the native 200ms opacity 1-to-0. Neither fade performs programmatic scrolling or begins
another switch. No fixed settling delay, extra scroll actor, optimistic path or durable command replay
is permitted. Empty paths and rejected/no-op mutations use this same barrier. Superseded, disconnected
and unmounted request callbacks cannot dispatch or dismiss a newer cover. Media with reserved stable
geometry does not hold the cover for its download; late geometry changes before release reset stability.
The cover keeps ChatApp's content-area background, 48px/5px primary ring, measured top/composer
available-range centering and content input exclusion through retained exit. It adds no login,
drawer-list, per-message hydration, generation or background-reconnect cover. Top controls, drawer
and composer retain their existing ownership and the App-only appearance policy above.
Its primary ring is 48px with a 5px stroke; App Reduced Motion stops rotation but retains the fade.
Browser visual verification must load the actual App font and theme; empty theme.css or an absent
font resource is not font-parity evidence. Pending chat actions must not be described as a completed
WebUI. The reported
green drawer-row outline is keyboard focus, not conversation selection; the report does not define
a replacement focus style or authorize removing keyboard feedback.

Each ordinary browser drawer row retains its stable conversation ID while its existing virtualized
position owner animates reordered placement over 400ms FastOutSlowIn. The stable layout top is
published immediately; draw-only displacement starts from the currently painted coordinate and
retargets there on interruption. Initial mount and viewport-only virtualization do not animate.
Numeric list scroll offset, section headings, focus, menus and pagination retain their current
owners. App Reduced Motion cancels displacement and presents the stable target; search results
remain outside this ordinary-row placement transition.
The browser conversation and search lists reuse Compose's verticalEdgeFade: a draw-only alpha mask
covers the top and bottom 40px, capped at half the viewport height. Each edge uses the native
first/last-visible-item tolerance and changes its weight over 200ms FastOutSlowIn; reaching an
edge removes only that edge's fade. Empty or fully visible lists have neither fade. Content and
viewport changes refresh those weights without changing layout, hit testing, focus or scroll offset.
The floating scrollbar remains outside the mask, and Reduced Motion retains this opacity feedback.
Each drawer conversation row's browser menu offers `Pin`/`Unpin`, `Generate Title`, `Rename` and
`Delete` in Compose order. Active generation alone does not disable these actions. Generate Title
uses the Refresh icon and remains unavailable while selection is loading or disconnected. It routes directly to
`MessageGenerationController.generateTitle(conversationId, session)`; that existing owner reads title
preferences, calls the model, persists the result and reports notices to the originating browser.
Pin, Rename and Delete use `ConversationLifecycleController`, never the repository, so a browser delete
is the same cross-client transaction as the phone's: refused while a send is admitted, and every client
still showing the conversation follows the surviving-parent-or-New-Chat selection rule. Rename mirrors `ChatRenameDialog` - the field opens
on the stored title and an untouched Save writes nothing - and delete mirrors `ChatDeleteConfirmDialog`:
`CONFIRM`, then `PENDING` with a progress action that cannot be dismissed, then `FAILED` offering
Retry. Only the connection that asked receives that conversation's `conversation_delete` answer, keyed
by its own action id; every other client sees only the shorter list.

Tasks has no browser entry, management state, command or styling until its complete UI is approved.
Existing Task-origin message labels remain. Other controls the browser cannot use yet are shown as in
the app but disabled. Tonal surfaces use Compose's `surfaceColorAtElevation` mix (primary over surface at
`(4.5 ln(e + 1) + 2) %`). The top bar is the `ChatTopBar` new-chat state: a `52 dp` row inset
`12 dp` at the sides and `8 dp` above and below over the fading background, a title capsule
(`4 dp` tonal, `4 dp` shadow, at most `260 dp`) with the `44 dp` Menu button (`26 dp` icon),
which is a menu-only `52 dp` circle in New Chat, and a `98 dp` actions capsule with New Chat (`30 dp` icon) and More (`26 dp`).
More opens an `AgoraDropdownMenu`-shaped menu (Search and System Prompt disabled, plus a web-only
Sign Out). The drawer is `ChatDrawerContent`: `min(width, 360 dp)`, `1 dp` tonal, `24 dp` end
corners, `16 x 20 dp` padding, the Agora title (`25/32` bold) and trailing expandable search
in the same first row as defined in section 16, the `42 dp` New Chat button and the list below. The
search capsule uses the same `56 dp` height and full-row width. Browser conversation rows omit the indicator container
and its 8px gap when neither generation nor unread is visible. A visible indicator keeps its 18px
slot and 8px gap; generation takes priority and unread is hidden for the selected conversation.
This is a WebUI-only exception; native Compose retains its empty indicator reservation.
The browser's trailing details button keeps its
32px circular hit region centered in the 44px row slot. It is inset exactly 4px from the 40px capsule's
top, bottom and logical end, independent of the native title's 16px content padding.
As in `ChatDrawerHost`, the drawer overlays the chat with a
`32%` scrim up to `960 dp` wide (`DRAWER_MAX_WIDTH + CHAT_APP_WIDTH_THRESHOLD`) and sits beside the
narrowed chat above that. The browser persists `drawerPinned`: wide-to-narrow closes the overlay,
narrow-to-wide restores expansion from the pin, narrow toggles change temporary expansion only,
and wide toggles update both the pin and expansion. Refresh preserves the pin. Opening a modal drawer
focuses the panel, never the search field; explicit search activation alone focuses that field.
The open modal drawer is
`role="dialog"` with `aria-modal`, the chat behind it is inert, Escape collapses expanded search
before closing the drawer on a subsequent press, and the scrim closes it directly. Focus returns
to the Menu button after drawer dismissal.
The drawer's single progress owns its offset, side-by-side chat inset and modal scrim opacity and
settles over 300 ms with LinearOutSlowInEasing, or snaps under the App's Reduced Motion setting.
Its list keeps browser DOM proportional to the viewport (six rows of overscan on each side),
independent of the number of loaded conversations. Drawer Room reads only id, title, unread and
pinned fields, ordered pinned-first then recent-update-first with id ties. The connection starts
with 80 rows and one lookahead; reaching the loaded bottom requests another 80. Duplicate requests
for the same loaded limit do not advance again. Each snapshot reconciles that live ordered prefix;
opening a conversation observes only the selected owner and never depends on its page being loaded.
Pinned and Conversations headings share the scroll area. Pinned is hidden when empty; Conversations
appears only when both groups exist. Hover/focus exposes the row menu; context menu also opens it.
Pin/Unpin uses the canonical narrow Room write and never changes recency, drafts or selection.
The bottom loading region shows the shared circular component only during actual pending loading.
The component supports determinate and indeterminate geometry, primary color, accessible labels and
round caps; App Reduced Motion retains a stationary 270-degree open ring. No artificial loading
delay, browser-owned motion preference, complete graph/list scan or extra scroll owner is added.
Arrow keys and Home/End navigate loaded rows while preserving virtualized focus by stable identity.
Drawer search uses the App's manual search method: canonical SemanticSearchService for RAG,
otherwise repository literal search, with 200ms debounce and at most 20 message results. Only a
bounded id/title/unread/pinned query resolves result titles; search does not open or recover owners.
The existing connection owns query cancellation and exact connection/revision fencing. Clearing or
disconnecting removes pending work; reconnect never replays the old query. Results group by
conversation, show its canonical display title, highest positive score and at most two snippets
with case-insensitive highlights. Selecting a result clears search and uses ordinary selection.
Search hides Tasks/New Chat and ordinary pagination; the shared circle follows the current pending
query only. Failure ends loading and exposes an error, never a literal-search fallback or retry.
Current-conversation Search replaces the top capsules with the Compose query/back/count/up/down
surface. One exact connection/open-sequence/query-revision projection calls canonical
scanConversationSearchMatches over eligible selected-path IDs with 64-ID hydration pages. Recall
does not depend on watched payloads. Source keys remain global across Timeline Answer slices;
Thinking/tool/citation/attachment metadata never contributes. Renderers map those keys to visible
body glyphs; MessageList alone picks the nearest visible occurrence, or the closest actual rendered
turn when no occurrence is visible. Multi-node occurrences use their union bounds. One request
epoch seeks through existing row hydration with bounded per-frame feedback and exact-glyph
retargeting; query updates never restart a finished or user-cancelled request. The first query's
automatic position starts after Compose's 300ms query interval; explicit Up/Down start immediately
and do not wrap. App Reduced Motion snaps the estimated and then exact position. Clear, selection,
disconnect and user scroll input cancel the same owner; no second scroll actor is added.
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
The composer host occupies at most `840 dp`; its `8 dp` side insets leave an `824 dp` card with
`2 dp` tonal elevation, `8 dp` shadow and `28 dp` corners. It contains the Ask Agora field (input `16/23`, 6 lines), the `40 dp`
expand button, and the controls row with the `48 dp` control group (attachment, model selector,
tools) and the `48 dp` send button. English and Chinese labels are the app's own strings. The
sign-in page centers its card with flex and caps it at `400 px`: a grid's auto track sized to the
card's max-content and pushed it past a narrow screen once the app font loaded.
The browser's message details mirror `SegmentDetailSheet` for Thought, Transcription, and Tool. In
Grouped Bottom Sheet mode, the group header opens a segment list; ordinary Timeline
cards and inline Grouped rows open the selected detail directly. Tool details consume the
shared typed presentation, including lifecycle, shell/file/search results and prefix-aware JSON
nodes; they never parse tool-result envelopes in the browser. Failed/stopped details retain the
shared unboxed neutral terminal text. Under a generating turn the browser also owns the phone's single
inline activity slot: the server projects `AssistantInlineActivityPresentation` and the page paints the
breathing `GenerationActivityDot` while the turn has produced nothing, the `RetryActivityIndicator`
label with its grapheme reveal and travelling dot while a provider retries, or the terminal text once
the turn ends, all in the same 24 dp slot so the message never shifts. Stop drops the activity while it
is in flight and keeps the terminal text, and App Reduce Motion shows the full retry label with no
breathing. Persisted tool images are requested only from authenticated
`GET /api/tool-images/{conversationId}/{messageId}/{detailIndex}/{imageIndex}` with original
attachment indices. Each request revalidates message ownership, real-path containment in the private
tool-media store, raster MIME and recorded size; browser paths and inline image bytes are forbidden.
The preview keeps its Compose-sized viewport through loading, failure and decoding, follows square
crop metadata, and opens a full-image viewer. The browser-local sheet uses 45%/94%
viewport anchors and a flat scrim whose alpha follows the sheet's rise, without backdrop blur;
list/detail back and close, Escape, focus return and Reduced Motion snap remain. It stays on the selected message while a streaming frame hands off to its durable
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
submitting; accepted clearing preserves later edits and focus. On desktop (primary fine pointer with
hover), Enter sends through the existing action gate and Shift+Enter inserts a newline. Phone Enter,
modified Enter and IME confirmation remain native input; repeated or busy Enter cannot send again.
Empty Enter never invokes Stop. Generating
with an empty draft shows Stop; a nonempty draft shows Send and enters the ordinary queue. WAITING
can be cancelled without stopping attachment imports. New Chat follows its accepted conversation
only while its original entry remains selected, carrying any later input to that composer.
Reconnection never automatically replays Send or Stop. An interrupted unconfirmed submission keeps
the input and asks the reader to check the conversation before sending again.
Runtime accepted-input scroll requests carry the exact open sequence and committed message ID.
The newest ordinary USER or Context Compact is the semantic tail anchor, regardless of Compact's
legacy participant or status. Anchor selection reads the same live-streaming-over-cached body as the
rendered row, so a running Compact reserves the ordinary USER tail minimum before its watched body
arrives. Both use the shared 140px target, measured composer/tools bottom clearance and existing
MessageList spacer and scroll owner; terminal settlement never switches to a different inset rule.
The WebUI Compact row menu contains Recompact and Delete. Recompact is a direct action, not a
configuration dialog: it sends the exact selected Compact ID and uses the existing session editor
capture and `compactManual` owner with the configured Compact model (or current model), prompt,
retain count and Preserve System Prompt preference. The request captures the conversation before
launch; later navigation cannot redirect it. Failures use the ordinary Compact snackbar.
WebUI Regenerate and Edit observe the session's existing `BranchReplacementTransitionCoordinator`.
The protocol forwards its requests and accepts fade, scroll and complete acknowledgements only for
that exact connection, open sequence, conversation and request ID. The connection reader never waits
for an Edit's fade barrier before accepting its acknowledgement. Opening another target or closing
the session aborts the same coordinator; no generation authority moves into the browser.
MessageList retains the outgoing suffix through a 180ms linear fade and committed scroll settlement.
Its existing scroll owner targets the direct generation parent over 600ms with CubicBezier(.3,0,0,1),
leaving the message column's existing 140px top content padding visible, just as native
`scrollToItem(index, 0)` does. Retained suffix removal must not change that settled target;
Reduced Motion snaps only the scroll, while user input cancels it and reports failure. Clearing an
aborted request restores the surviving rows over 180ms linear opacity. Completion requires committed
state, settled scroll and the old target off the active path before retained rows are released.
The existing MessageList bottom-follow owner consumes them only when that message is on the ready
path; queue admission alone does not move the reader. User input releases bottom following as before.
That bottom-follow owner is the list's only programmatic scroll actor: send requests, streaming growth
and settled content all wake the same frame loop instead of adding separate corrections. It follows the
phone controller with a 240ms FastOutSlowIn send start feeding the 0.09s measured and 0.16s unmeasured
exponential seek under the 0.82-viewport per-frame cap. WebUI consumes the App's stored Stick to Bottom
value through the existing display event, including live changes, without a browser override.
Its lifecycle matches native INACTIVE, ARMED, ATTACHED, SETTLING and DETACHED states. A real reader
scroll gesture detaches; an ordinary click, focus, text selection or non-scrolling key does not.
Only after reader scrolling has stopped, on the newest ready page and within 48px of the physical
bottom, may generation following attach again. Search, loading, branch replacement, competing seeks
and Stop keep their existing exclusion. Explicit send/bottom navigation retains its own seek even
when Stick to Bottom is off. Attached growth uses the native 0.055s tail correction capped at
2800px/s. Generation completion enters settlement for at least 700ms and eight stable frames, capped
at 1600ms; detached readers are never pulled back by terminal growth. Opening a conversation stays
an instant placement under the cover. App Reduced Motion blocks streaming auto-follow through the
same native motion policy, while explicit send/bottom seeks snap. A settling phase already started
continues under native rules even if Stick to Bottom is disabled. A new active generation resumes
attachment from an unfinished settling phase through the same lifecycle owner. Paging, anchor compensation and
bottom-button 48px/96px hysteresis remain owned by the same MessageList scroll loop.
WebUI history pages use an independent `262144`-token (256K) crossing threshold and a maximum of
`10` complete rounds per newly loaded window. These are not the conversation's generation context
setting or historical Provider usage. Every initial, older, newer and explicit-bottom load admits
complete rounds until its newly loaded text cost first becomes strictly greater than 262144,
10 rounds have been admitted, or no content remains in that direction, whichever happens first.
The crossing round is included in full. An exact-256K sum admits the next complete round only
when fewer than 10 rounds have been admitted and content remains. Ten short rounds stop below 256K;
a short final remainder loads in full.
The minimum unit is a complete USER-started round, including all assistant/tool/result rows until
the next USER row; a leading non-USER prefix is one round. A single oversized round is always
loaded whole. Page costs use the shared offline text estimator over original source text and
additional Thought/Transcription/tool detail content, without duplicating mirrored answer text.
Compact/provider rollout does not hide historical browser rows. Unknown costs are resolved by
bounded adjacent payload batches on demand; they are never treated as free or priced from reported usage.
The next unknown-cost probe stays within the remaining ten-round allowance and admits at most 48
rows, except that an indivisible round may exceed that row batch. Existing subscription batches
survive watch growth; retained unchanged rows are not resent when a changed selection requires
rebuilding a batch. The 48-row bound applies to each hydration
batch, not page membership. MessageList retains at most three adjacent, complete token windows in
one continuous rendered/subscribed range. Loading the second or third window does not unmount the
windows already present. Only admitting a fourth evicts the window farthest from the loading edge.
The existing watched-page owner keeps these retained rows current and leaves them visible while
bounded payload probes resolve the incoming window. A window's completed round boundaries are
stable: streaming cost changes do not reprice and discard the reader's retained history. The live
newest window follows appended USER/assistant/tool rows without moving a new send to a separate
history page; completed historical edges stay fixed. Accepted-send navigation from distant history
returns to the newest threshold-or-ten-round page, so the sent USER round directly follows its
preceding tail context, and never consumes the request before its target is rendered. Opening settlement waits
for that newest page to be ready, so upward growth cannot leave the reader near the top.
Only an actual unresolved page request shows an edge loading indicator, never the mere existence
of older/newer history. Incoming page content and the indicator fade in/out; idle or cancelled work
cannot leave a spinning indicator. Opacity exit snapshots are inert and do not count as active loads.
Load completion and initial bottom settlement must converge at a fixed viewport without any resize
or extra user input, including content shorter than the viewport and delayed/out-of-order bodies.
Explicit search, detail, branch, bottom and send navigation still use their canonical targets; a distant
jump may replace the range rather than hydrating the intervening history. Evicted bodies use the
existing bounded idle cache. The existing scroll owner preserves a surviving visible row and its
pixel offset across prepending, appending and far-edge eviction, without remounting retained rows.
Only directional reader input
can request an adjacent page; wheel, touch, keyboard and floating-scrollbar input all feed that same
intent in MessageList. Anchor compensation, layout clamping and other programmatic scrolls
cannot feed back into paging. No extra scroll, context-retention or generation owner is added.
Explicit upward history paging remains available during generation. The existing requested-page
and payload-watch owners hydrate that page without letting an active streaming row outside it
replace the reader's page with the newest one. Streaming updates and terminal settlement preserve
the reader's chosen historical window; explicit bottom/send navigation keeps its existing owner.
The model picker uses the phone's valid-model catalog, provider/API-name order, aliases and provider
name visibility. Existing conversations share one field-specific Room model write with the phone;
it cannot replace drafts, branch selections or other conversation fields. Browser New Chat model
selection remains session-local. Ordered model commands settle before the next browser Send tap.
Queue rows mirror ComposerStatusColumn/QueuedMessageRow: chronological text, attachment count and
exact-ID removal. An idle empty composer sends its remaining queue through the existing runtime
drain; an empty composer during generation still stops. No separate queue execution path is added.
The existing browser Composer also owns the queue's presentation transition. Queue membership
changes crossfade the complete chronological stack: 180ms linear entry and 140ms linear exit,
with one bottom-anchored 220ms FastOutSlowIn height transition. Rows retain their 40px height,
20px radius, horizontal inset and 4px gaps, including the final gap above the input. Outgoing
snapshots are inert and hidden from accessibility; only the latest queue can issue exact-ID
removal. Retention is draw-only, not another queue or deferred-command authority. Rapid changes
retarget height and the active snapshot from current values; already-exiting snapshots finish
their original fade deadlines. Unchanged queue IDs do not replay motion when text or attachment
counts update. App Reduced Motion snaps height and retains these opacity transitions. Selection
or connection-owner replacement drops old snapshots immediately, and unmount cancels animations.
The existing Shell measurement and MessageList remain the only inset and scroll owners. Browser
verification covers empty/add/remove/drain, interrupted transitions, owner replacement, focus,
outgoing input exclusion, narrow/wide geometry and the App-only Reduced Motion policy.
The browser's expanded Composer reuses the original field, attachments, queue and controls with no
second draft or modal. Native ChatComposerLayout remains the reference: input-and-attachment size
uses 400ms FastOutSlowIn in both directions. Expansion has a separate outer 44px-to-zero spacer over
400ms cubic-bezier(.15,.5,.25,1); only this spacer's active expansion blocks repeated button actions.
The internal 44px header spacer enters without motion and leaves over 250ms FastOutSlowIn height
and opacity. Separate expand and collapse controls keep their own 250ms FastOutSlowIn opacity:
expand enters on collapse and exits immediately on expansion; collapse fades in and out.
The available area starts at Shell's measured top-capsule inset, so the collapse action,
queue and attachments never overlap the still-active TopBar. Only the input-and-attachment column owns
interruptible 400ms size motion. Collapsed textarea measurement uses its full available text width;
a temporary native scrollbar during height:auto must not add an apparent line before text wraps.
Measurement restores the field's prior overflow mode and preserves six-line capped internal scrolling.
Its measured target content stays top/start-aligned inside the native
20px top-corner clip: attachments precede the TextField, and the expanded field takes the remaining
weighted height. BottomCenter alignment belongs outside the entire layout in ChatComposerSurface,
not inside this input clip. The expanded browser host fills Shell's available top inset to the
viewport bottom. Its full-height Surface gives the whole layout exact constraints, and the weighted
input column fills the remaining height after header, status, controls and insets on every frame.
The input size animator is constrained by that exact height, as in native animateContentSize;
it cannot leave free space above the input while the Surface has already expanded. The input top
tracks the Surface top plus the native header/status/insets. The collapse overlay stays Surface-local.
The expanded backdrop keeps the native 20px transparent lead and 40px fade, capped at 12% and 24%
of the host height. As in native ChatConversationToolsOverlay, the tools keep their last measured
non-expanded Composer anchor during expansion. The expanded backdrop and Surface paint above them
and exclude their covered hit targets; the tools never move above the expanded host into TopBar.
The browser must mirror these responsibilities instead of revealing a
bottom-aligned target through an independently moving window or adding a second field-size animator.
Queue status
keeps its separate 220ms height owner and neither queue rows nor controls may flex-shrink. Queue text
uses labelLarge 13px/18px, attachment counts labelSmall 11px/15px and a 14px attachment glyph.
The outer composer takes their current combined size without a competing height animation, keeping
the Surface and controls against the viewport bottom through typing, accepted draft clear, queue
changes and interruption. App Reduced Motion snaps spatial size/spacer changes and retains the
component-owned button opacity transitions.
Explicit collapse or Escape returns to the six-line field. The browser follows the native Compose
Send contract: accepted draft clearing does not itself collapse the expanded bar. Rejected sends
and later typed input also preserve expansion, selection and focus. The former browser-only
accepted-send auto-collapse exception was cancelled by the owner on 2026-10-14. Shell reads
its existing drawer progress for the single strict >0.5 collapse
threshold, shared by drag and programmatic opening; returning to <=0.5 rearms the next crossing.
Browser Fork and Share use ChatRuntime's canonical ConversationForkShareController. Fork retains
its confirmation and blocks repeat, Cancel, Escape and outside dismissal while pending. Completion
closes it; success opens the copied conversation only if the exact connection/open request remains
current, with the check and navigation committed by the session's existing selection mutex. Share
uses the shared public-content Markdown formatter, never browser-rendered history or private
Thinking/tool content, and presents selectable text, clipboard copy and a Markdown download.
Page actions carry exact conversation, sequence and action identity, reject duplicates and stale
results, and are never replayed after reconnect. Changing selection or losing the connection closes
the presentation without redirecting a completed fork or displaying a stale export.
Browser System Prompt uses the shared Settings catalog and global-default selection. The picker
has a draft radio choice: Save alone applies it; Cancel, Escape and outside dismissal discard it.
Its native option rows use 8px padding and an 8px text gap after a 48px circular radio target,
producing a 64px single-line row. The shared delegated indication chooses the nearest host:
radio presses belong only to the circular target, while text/row presses belong only to the
24px-rounded option row. Input hover/focus must not add a second shadow or press wave.
Existing conversations share a narrow Room prompt write with the phone, advancing dataChangedAt
without changing recency, drafts or branches. New Chat selection stays session-local, enters the
tap-time immutable workspace, and acceptance clears only a still-matching selection. Low Context
Mode disables selection without erasing it. Exact connection/sequence/conversation gates reject
stale edits. The WebUI only selects existing App-owned prompts; it has no prompt creation/editing
entry, editor asset, preview/variable projection or catalog-write command. Creation and editing
remain available in the App, and shared Settings storage is unchanged by this browser reduction.
Shared prompt CRUD transforms the current catalog and its default in one Settings DataStore edit,
never replacing it from a browser or repository observer snapshot. Concurrent accepted additions
and unrelated updates survive each other; malformed stored catalog data rejects CRUD without overwrite.
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
The browser attachment menu follows Photos, Videos and Files; the owner removed its Camera entry.
This browser-only menu difference does not change the native Compose attachment menu. Its Add entry is always
enabled and phase/pending changes do not close it. Inner picker items and uploads retain exact-session
admission; selection/connection changes still invalidate the menu. The browser file inputs keep multi-selection
and their existing MIME/type admission. Attachment status,
retry/removal, PDF page selection and video slicing use the same Composer owners as Compose.
Browser attachment columns match AttachmentPreviewRow: 64px thumbnails, 8px corners and gaps,
5px column top and 8px row bottom/side insets. The remove badge is 18px black at 80% with a 10px
white glyph, offset 5px above and beyond the thumbnail's logical end. File/PDF and unavailable
captions use labelSmall 11px/15px, 2px top spacing, a bounded single-line filename ellipsis and
an error-colored unavailable line. One existing tile owns 200ms FastOutSlowIn thumbnail/status
and caption crossfades; import placeholder/scrim, decode and failure remain in that same viewport.
Only image loading delays its indicator by 200ms, including continuous import-to-decode loading;
other import indicators appear immediately. Source replacement, retry and disposal fence prior
callbacks. Removal, retry and authenticated preview admission retain the existing exact identities.
Preview requests identify the authenticated live connection, captured selection sequence, attachment
ID and artifact index, never a browser-supplied private file path. Files outside app-private storage,
Local Sandbox assets, stale selections, unavailable attachments and revoked sessions are refused.
The same preview stream supports one HTTP byte range for native video playback and seeking. Range
responses preserve authentication, file pinning, recorded-size bounds and revocation cancellation;
they do not buffer a complete file or introduce another download endpoint.
The browser Interaction Bar binds the process AskUserController and ShellConfirmationController
through the existing sync connection. Canonical userInteractions selects visible requests, shell
first then one page per question. Commands carry exact connection, open sequence, conversation,
request and monotonic action identities; stale/duplicate actions never answer another owner.
Choice validation precedes canonical atomic submitAll; Skip and Allow/Deny use the same controllers
as Compose. Session trust belongs only to ShellConfirmationController, never to browser storage.
interaction.js owns presentation drafts, page and fold, not request or answer delivery state.
Send on the last question submits all visible questions, including explicitly unanswered blanks;
it requires at least one answered draft. Skip declines all questions on the card. Custom typed
answers are an option, mutually exclusive with listed options for a single-choice request, with
180ms field reveal. The question body has one200px scrolling bound. Folding morphs the start-aligned
card into its48px capsule over320ms without reflowing content, with half-phase content/label fades.
Outside clicks and Escape never answer/dismiss a request. Removed requests discard their drafts.
One 180ms appear/exit progress owns opacity, bottom-origin .9-to-1 scale and measured-height lift;
the exiting card retains its page/fold and is inert. Owner changes exit before entering the latest
card. Page travel and height use 350ms; App Reduced Motion snaps these spatial transitions.
The card retains the native 8px trailing spacer above the measured composer anchor, in addition
to the composer's 8px painted inset. Page-height motion feeds the existing card and host measurement;
fold/appearance observers must not replace the animated page height with its final content height.
The bar lives inside composer-host: existing Shell measurement remains the sole inset owner and
MessageList remains the sole scroll owner. Disconnect clears projections and never replays decisions.
The existing Shell presenter also owns browser snackbar lifetime, timeout and retained draw payload.
It follows MainActivity's app-owned snackbar, not Material's inverse-color defaults: 12px corners,
surfaceContainerHigh/onSurface, 6dp shadow, bodyMedium, 12px horizontal and 10px vertical outer
spacing above the measured bottom inset plus 2px, and a 28px Close button with an 18px icon.
Entrance and exit use 400ms FastOutSlowIn fade plus center scale 0.8 to 1 and back. App Reduced
Motion removes scale but retains the fade. New notices replace the retained payload after the
native 200ms exit handoff; dismissed content stays inert until its exit finishes. One exact-id
4000ms timeout belongs to this presenter, not Composer, and stale timers cannot dismiss a new notice.
Browser action icons keep one fixed 24px slot inside the existing 48px action button. SEND, STOP and
BUSY crossfade over 200ms LinearEasing, retaining outgoing paint without another action target.
Initial composition is stable; interrupted fades retarget current alpha and App Reduced Motion
keeps this opacity transition. Existing enablement, command admission and 400ms color transitions
remain unchanged. Compact's running circular indicator explicitly uses onSecondaryContainer,
matching the native icon color, without changing the shared primary default or progress rhythm.
Browser Select Text opens the existing shared DetailSheet after its source menu exit, with the
localized title and exact displayed user text, no Markdown parsing or attachments. Its selectable
plain body is 14px/24.2px with 12px top, 24px sides and 32px bottom padding. The bubble is not
selected automatically; sheet motion, input exclusion and close/focus remain with the shared owner.
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
App motion behavior. Their settings mode matches ChatBottomBarOverlayHost: no duplicate title,
Close control or divider; 640px maximum width, 28px top corners, a 32x4px drag handle with 22px
vertical padding, natural content height and a half-viewport partial anchor only for tall content.
Settings entry/anchor motion uses DefaultSpatial (700, 0.9); exit uses FastEffects (3800, 1),
translating the full measured surface rather than clipping its height. Reduced Motion snaps travel.
The body uses 16px insets and a 24px trailing spacer; panel text and rows follow the native
ThinkingControlPanel/OpenAiServiceTierControlPanel, including 32px header-to-slider spacing.
Context composition retains its header spacing and uses 16px from the bar to the legend, with
6px between legend rows. Advanced parameter columns have no extra inter-row grid gap, use 6px
vertical padding and the native labelMedium/labelSmall header geometry.
Shared tool switches use Material3 1.4.0 FastSpatial (stiffness 1400, damping 0.9) for their
16/24px thumb and offset, snapping to 28px while pressed. Menu tracks scale by 0.7. Pending
commands keep their disabled admission and backend-authoritative checked value without dimming
otherwise available controls; genuine capability/connection disabling remains visible.
Advanced opening is independent of an unrelated pending tool toggle: the available entry remains
undimmed and opens its draft, while command submission retains the existing pending admission fence.
Context Compact opening follows the same separation: an unrelated pending tool toggle does not
change the entry's enabled state or color. Genuine Compact unavailability remains disabled, and
its editor submission keeps the existing pending-command and exact-session admission fences.
Shared WebUI scroll hosts use the drawer's floating scrollbar through material/scrollbar.js.
Message details and existing scrollable dialogs, including System Prompt, reuse that paint and
pointer owner for their real scroll boxes. Native bars are hidden without a layout gutter; showing
or hiding a thumb never reserves content width. An overflowing host shows its thumb only on mouse
hover or during an active thumb drag. Scrolling alone, including keyboard and touch scrolling,
does not reveal it. The collapsed Composer's decorative gradient and empty side insets do not
claim pointer input from the message viewport underneath; hover there belongs to that same message
scrollbar. Actual Composer, Interaction Bar, modal and expanded-cover controls retain their own
input ownership. Content/page/viewport changes update the geometry, and the presentation owner
releases every scrollbar binding when its retained modal exits. Wheel, keyboard, touch, focus,
modal input ownership and explicit-save semantics remain with their existing owners.
Disabled menu text/icons/supporting text use their native role at 38% without stacked parent alpha.
Shared switches use the native disabled checked/unchecked track, outline and thumb roles; pending
paint does not turn a genuinely unavailable switch back on. Shared sheet content remains scrollable
without a native scrollbar or gutter, throughout entry, content-size changes and exit.
Reduced Motion snaps switch geometry while retaining its shared press/state-layer policy.
Centered browser AlertDialog content starts after the shared 16px title-to-text-slot gap, with
24px from content to actions. Direct body fields do not add another top margin; labeled fields
keep their native 8px label reserve and caller-owned body/inter-field spacing remains intact.
All centered-dialog outlined inputs, including Rename, Message Edit, Compact and Video Slice,
share the existing FastEffects (3800, 1) label/border color and FastSpatial (1400, 0.9) 1-to-2px
paint-thickness transitions. Focus and blur preserve field geometry and do not use a second native
outline. Reduced Motion snaps thickness but retains color interpolation. Video Slice keeps its
native 16px field corners. Compact's model label stays primary while its menu is expanded.
The shared menu owns scrolling and pointer pan; its exposed variant
matches anchor width and bounds height to available space above/below the anchor with 48px margins.
Keyboard navigation scrolls the focused row into view. Reduced Motion snaps field thickness but
retains color interpolation.
Advanced uses the explicit-save six-parameter draft/reset contract in
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
Every clickable control in the page shows one Material 3 touch feedback owner:
WebUI press waves complete their original 225ms FastOutSlowIn radius and linear centre travel even
when released early, then fade over 150ms linear. A held wave stays at its finished geometry and 10%
alpha until release, without a repeating pulse. An early release uses final alpha immediately but
never snaps radius/centre to their targets. The existing CSS animation completion owns that handoff;
no timeout, retry or second indication owner participates.
`webui/material/ripple.js` delegates press detection once from the entry, and
`webui/material/material.css` paints both parts inside the control's
own background with registered custom properties, or through one pointer-transparent inherited
paint layer above a direct image child so opaque media cannot hide it: a state layer at 8% under the pointer or in focus
and 10% while pressed, plus a wave that grows from the touch point at the pressed alpha and fades
out. Nothing is inserted into a control, so no control gains a wrapper, a stacking context, a new
containing block or overflow clipping, and a disabled control gets neither. Controls that already
transition something list `--md-state` beside it, because transition is a single property. App Reduce
Motion keeps the layer but makes it instant and runs no wave. The earlier per-control hover
backgrounds and the button's own state-layer pseudo-element were removed, so this stays the only
owner of press and hover feedback.
## 37. WebUI Material component layer
Browser centered dialogs use content-driven width with the owner's effective minimum 480px and
maximum 560px, constrained by the viewport minus 16px on each side. This applies to every centered
Dialog, including Compact; the `100vw - 32px` bound overrides both minimum and maximum on narrow
screens, so a dialog never consumes those side margins.
The owner-selected floor supersedes the earlier 320px config_prefDialogWidth measurement as the
browser width target. No fixed 88px subtraction or platform Alert-theme percentage supplies it.
Native 28px corners, 24px content padding and existing field/action spacing remain unchanged.
Shared browser menu/control surfaces, including modal top-layer dropdowns, keep labels and status
nonselectable. The Ask User card's question description is selectable text, including while an answer
command is pending; selecting it never changes an option or submits a response. Option rows and
card actions remain nonselectable controls, while the answer field retains native input selection.
Message/detail bodies and real input/textarea content remain selectable; control
nonselection must not disable their native selection, copy, focus or scrolling.
The sign-in Brand and New Chat welcome use the native WebUI Settings `Icons.Default.Web` browser-window glyph.
All image entries reuse the existing AttachmentViewer, including Tool images and PDF pages.
The pinned Panzoom 4.6.0 runtime and its MIT license are bundled under webui/vendor, never fetched
from a CDN. Panzoom owns pointer pan, focal wheel zoom and touch pinch inside a fixed clipped viewport.
Double-click toggles 1x/3x around the pointer with Compose's non-bouncy medium-low spring
(stiffness 400, damping 1); new input interrupts it and Reduced Motion snaps. Navigation, source
replacement and disposal release the old instance/listeners/animation and restore fit geometry.
All existing full-screen browser previews use the shared `useDialog` retained modal lifecycle
and the native section 11 fade/scale timings, never ordinary dialog vertical translation.
Their loading and failure indicators are draw-only overlays inside the media viewport. They reserve
no layout row, padding or gutter and cannot change media bounds, scroll extent or control positions.
Hover transfers between the media scroll host and its floating scrollbar must not toggle visibility
repeatedly while the pointer stays over that combined input region.
Their black backdrop fills the viewport without scaling and fades independently; closing retains
input ownership and restores focus only after the full content exit. Reduced Motion keeps fades.
The browser Scroll to Bottom lives in MessageList's existing scroll owner. Its per-conversation
32px directional latch starts hidden, shows only while heading toward the tail and available,
and uses native 48px hide/96px re-show proximity hysteresis. Loading, search, branch replacement,
auto-follow and active seeks suppress it. A 48px host centers the 40px circular FAB/24px down icon,
4dp tonal surface/shadow and 400ms FastOutSlowIn fade/scale 0.6; Reduced Motion keeps fade only.
It sits 8px above the measured composer plus the existing InteractionBar's measured-height times
appearance progress, with no second lift animator. Click requests the newest whole-round page,
waits for its watched bodies, then starts the existing cancellable tail seek. It never seeks only
the end of an older page, generates a message or introduces another scroll controller.
Browser existing-branch navigation uses `RunUiProjection` fields in the selected topology event:
user edit controls stay beneath the bubble; assistant regeneration controls belong to the terminal
output action row. Both use native 24px circular arrow controls, 16px mirrored glyphs, labelSmall
index/count and the half-alpha surfaceVariant capsule. Generating/loading/pending commands and
boundary directions are disabled. The existing row command carries exact connection, sequence,
message, anchor, displayed index/count and direction; Session rereads the current selected topology
inside existing owner/execution/queue locks, rejects stale/duplicate/invalid commands and calls
`selectRunBranch`. No graph, draft, recency or generation write and no optimistic browser selection
is allowed; command receipt settles through the existing composer acknowledgement. Serialized
path entries always include numeric branchIndex and totalBranches, including zero and one defaults;
Kotlin decoder defaults are not browser wire values.
Persisted user attachment thumbnails use message occurrence indices, not composer upload ids.
Authenticated same-origin reads resolve only that message's app-private media and never accept a
browser path. Missing media shows a terminal unavailable thumbnail. The shared viewer opens the
tapped occurrence. Streaming answer/code text uses draw-only time alpha (500 ms linear); the answer
tail dot remains independent. Compact rows render their capsule regardless of legacy participant.
The shared browser DetailSheet uses SmoothBottomSheet's fraction spring (350, damping 0.9) for
entry, anchor changes and retained exit. Scrim alpha is 0.32 times that same fraction. Its handle
interrupts the spring; close returns focus only after the sheet reaches zero. Option labels use the
shared Material press owner, including repeated clicks; disabled options never start a wave.
Hover feedback uses an owner-selected 50ms linear transition for both entry and exit.
This supersedes the 15ms Compose-matching timing on 2026-10-15; focus entry keeps its
45ms duration and press ripple timing is unchanged. App Reduced Motion snaps the state layer.
A component keeps its own color or visibility transition through the shared component-transition
input, never a second hover plate. The shared indication composes an optional component-background
paint beneath its wave and state layer. Composer Expand/Collapse use it for their native radial
surface-to-transparent occlusion so indication styling cannot erase that paint; they add no shadow
or solid plate and keep their existing geometry and input behavior.
Compact info rows use Compose's 18 dp clip with 10 dp horizontal and 8 dp vertical inner padding.
The chat top gradient belongs to the message backdrop below the Composer, not to the TopBar control
layer; expanded attachment/status/collapse controls remain above it without arbitrary extra layers.
Answer-tail activity is projected by the existing Compose last-visible-segment predicate and drawn
only in the shared 44 dp bottom action slot. Earlier answer text never keeps a dot below a later
Thinking/Tool/Transcription card. Retry, Stop and terminal states keep their canonical exclusion.
The browser selection owner keeps only the previous model/control/context presentation while a new
selection loads. Its composer sequence stays old, making action targets unavailable until the new
composer arrives. The matching opened acknowledgement never clears the workspace a second time.
WebUI synchronization consumes the ordinary generation cadence without changing it. Branch inputs
contain only structural identity/status, not growing text, timing or token counts, and are deduplicated
before path resolution. A streamed row has no parallel durable payload subscription until its
terminal handoff. Durable entity equality is checked before shared payload decoding. Rendered
thought/answer/tool bodies travel only in presentation; original text remains available for copy.
Every Material primitive the page renders is owned by `webui/material/`, one file per component, and app
code composes it instead of re-implementing it. `tokens.js` holds the numbers a component has to compute
with and quotes the Material 3 1.4.0 token each came from, `progress.js` exports `CircularProgress` and
`Spinner`, `slider.js` exports `sliderStyle`, `ripple.js` is the press-detection owner, and
`material.css` owns the matching CSS. `index.html` loads `material.css` after `style.css` and before
`theme.css`, so a theme override still wins. Duplicated spinner markup, hand-painted arcs and per-call-site
slider math are gone, and a call site restates no Material number.
Circular progress has one owner for both states: a determinate ring paints a `SecondaryContainer` track
under its arc and an indeterminate ring paints no track, matching `CircularProgressIndicatorTokens`. The
indeterminate ring is one global `6000 ms` linear turn, a `90°` linear step over `300 ms` every
`1500 ms`, and an arc growing linearly from `0.1` to `0.87` over `3000 ms` then shrinking with
standard easing. The pinned Compose keyframe easing belongs to the following interval, so its
emphasized-decelerate entry at `300 ms` affects the constant hold, not the preceding step. The
indeterminate start is 3 o'clock; determinate remains at 12 o'clock. Every spinner turns
identically except for the one deliberate slow-duration override on the sign-in page. `sliderStyle`
reproduces `SliderDefaults`: a `16` pill track, a `4` x `44` handle that narrows to `2` while pressed or
focused, an `8` empty band on each side of the handle, and one `4` stop dot per integer step. A stop that
falls inside the handle band is never emitted, and the trailing stop exists only below the maximum and is
painted in the active-track colour, exactly as `drawStopIndicator` does. `step="any"` draws no stops.
Every ordinary browser dialog uses `useDialog` in the existing `html.js` motion utilities for modal
lifetime. Close retains the dialog and its backdrop for the shared 200ms exit, beginning at the
current entry opacity/transform when interrupted. Input and repeated actions are blocked during
exit; native close and parent disposal occur only on animation completion, then focus returns to
the still-connected opener if no newer modal owns it. Pending mutation guards still refuse close.
Reduced Motion snaps this existing window policy. Selection/disconnection invalidation disposes
without replaying a deferred command. Attachment processing and destructive message confirmation
capture their exact action, then issue it after exit, fenced by the existing session target.
A window that appears in place enters with one shared `200 ms` fade plus `12 px` upward move on the
emphasized easing and a linear scrim fade; dropdown menus keep the scale-from-anchor entrance and the
message sheet its horizontal presenter, because those are what the Compose originals animate. App Reduce
Motion snaps all of it.
Stacking is a contract, not a local choice: composer host `1`, chat top bar `2`, drawer scrim `3`, drawer
`4`, popup layer `5`, detail sheet `10`. Browser dropdowns use the existing MoreMenu owner with a
native modal top-layer host, outside ancestor masks, transforms and stacking contexts. That host
remains modal through the menu's retained exit, closes before focus is returned, and uses only the
menu's existing scale/opacity spring, not the generic dialog entrance. A later modal opened from a
menu owns input above that exiting menu. The composer's bottom gradient therefore never paints over the
top-bar title or action capsules; the Compose top bar raises itself to `zIndex(1.5)` above the composer's
`1` occlusion layer for the same reason.
## 38. Conversation Todo Capsule
An opened Agora-owned conversation may have one database-backed Todo list shared across branches.
Native and WebUI show its content-width, 48dp/px capsule in the shared horizontal tools row above
Composer, after User Interaction and before Subagents, with 8dp/px gaps. Empty lists and New Chat
show no capsule. The capsule displays a checklist icon, localized Todo title and completed/total
count. Tapping opens a read-only list through the existing SmoothBottomSheet/DetailSheet; ordered
rows show selectable literal content and accessible pending/in-progress/completed status icons.
Both surfaces use RadioButtonUnchecked for pending, Schedule for in-progress and the filled
CheckCircle for completed at 20dp/px; a standalone checkmark does not represent completed Todo status.
There are no checkbox toggles, edits or browser mutation commands: only the model's ordinary
`todo_update` tool changes the list. Closing a sheet does not modify it.
Typography follows the existing interaction and detail surfaces rather than inherited defaults:
the native capsule title is `titleSmall` with explicit Bold, its count is `labelMedium`, and the
sheet title uses `ChatType.detailTitle` (22sp/28sp Bold). The native sheet count remains
`labelMedium`; its centered header uses the existing 24dp horizontal/12dp vertical title insets
and inset divider. Literal rows retain `bodyMedium` Normal. WebUI matches its established
Interaction Bar metrics (14px/20px Bold title, 12px/16px Medium count), reuses the shared
DetailSheet title, and keeps 14px/20px Normal rows. The sheet header shows the Todo title separately
from its right-aligned completed/total count, using the same 12px/16px Medium count styling as Compose.
Every text tier uses the effective App font.
The native list fills the sheet's remaining content viewport, including blank space below short
lists, so body drags reach SmoothBottomSheet's existing nested-scroll owner. The list protects the
navigation-bar inset and keeps 32dp of scrollable bottom content padding, matching the existing
segment-detail sheet, so the final multiline item can scroll fully above system navigation.
Blank-body dragging
expands, collapses and dismisses the sheet with both normal and Reduced Motion. The shared tools
row retains 8dp/px gaps between items and above Composer on both surfaces.
The capsule enters and exits with a `180ms` opacity transition and subtle `0.96..1` scale from its
bottom center. Changing conversation retains only the outgoing capsule until its fade completes,
then enters the latest selected owner's capsule; rapid changes do not queue obsolete owners.
Same-owner progress updates do not replay the entrance. Outgoing capsules cannot open a sheet.
Reduced Motion retains the timed fades but fixes scale at `1` and snaps the measured height lift;
normal height lift follows the same opacity progress, returning exactly to zero after exit.
Native status icons align their centers to the actual first text line's layout center rather than
the top of the row or the center of a multiline text block. Font scale and wrapping preserve this
alignment, and literal text remains selectable without fixed pixel offsets.
The selected owner's narrow Room observation drives both surfaces. Changing owner, clearing the list
or disconnecting closes the sheet and clears old presentation. Browser events carry the current
conversation and selection sequence; stale events are ignored. Startup and drawer loading do not
observe Todo or scan conversation owners. The shared tools row's maximum item height lifts the
existing scroll button and enters the existing combined bottom-inset measurement, without adding
another timeline scroll owner. Shared shell focus, selection, motion and Reduced Motion behavior stay canonical.

## 39. Subagent Navigation
Ordinary conversations may own one level of isolated child conversations. Native and WebUI place
individual 48dp/px Subagent capsules after Todo in the shared horizontal tools row, with 8dp/px
gaps and the established titleSmall typography. Subagents keep ascending durable creation-time order;
new children append after existing children, and message/title/status updates never reorder them.
Equal creation times use the stable child ID as a deterministic tie-breaker. Native, WebUI and
agent_list consume the same parent-scoped ordering. Each capsule has a 4dp-equivalent shadow, a small
localized Subagent label above its name, and ordinary runtime status. Idle, successfully completed
and SUBAGENT message-source markers use the same AccountTree branch icon, not a completion check;
running uses the existing progress indicator, failed uses ErrorOutline and stopped uses PauseCircleOutline
on native and WebUI. Durable status and accessible completion labels do
not change. Selecting or deselecting a capsule crossfades its background and content colors over 250ms
without replacing its keyed content, moving geometry or replaying entrance/status animation.
Tapping opens the child through canonical selection. Children are excluded from the native
and browser conversation lists and drawer search results, but remain ordinary stored conversations
reachable through their parent's capsules. A child shows its siblings with its own capsule selected.
Its top-left control and native Back return to the parent. Drawer dragging remains available and
wide pinned drawers retain their state. The drawer highlights the parent while a child is open;
selecting that highlighted parent is a navigation no-op, preserving the current child and its state.
The normal narrow-drawer close behavior still applies. Other roots and New Chat keep their ordinary
selection behavior. New Chat and empty/disconnected selections show no capsule row.
The row follows Todo's 180ms fade and bottom-center 0.96..1 scale when its parent family changes,
retaining only the outgoing family's inert capsules before entering the latest selection. Navigation
between a parent and its children or between siblings keeps the same keyed capsules visible and updates
only selection; a selected-conversation change must not synthesize an empty family list. Rapid selection
replaces intermediate unrelated owners; same-family name/status updates do not replay entrance. Each
fixed-size status icon slot crossfades over 250ms, including progress transitions. Reduced Motion fixes
scale at one and snaps the height lift while retaining fades. Height returns exactly to zero after
clear/disposal. Compact and automatic continuation must not transiently report completed while the
same admitted Subagent task is still working; status follows its canonical work lifecycle, not animation.
Settings > Subagents offers Capsule display: Individual or Grouped (default), applied immediately
on native and WebUI. This presentation preference does not change delegation admission or family data.
Individual retains one capsule per child. Grouped hides at zero and keeps direct child navigation
at one; two or more children share a count capsule and open the existing child sheet.
The native grouped capsule displays localized `X Running` while any child has the canonical
`running` status, using only that running count; otherwise it displays localized `X Subagents`
using the total child count, including `1 Subagent` at one. Its plurals cover every supported locale.
Running consumes the existing repository status projection: a durable Run with `activeSlot = 1`
or a registered Subagent worker is `running`, including a worker waiting for generation admission
or crossing Compact/ordinary handoffs. Queued messages alone do not count. The existing progress
indicator follows that same status without changing its crossfade.
When none works, the aggregate uses AccountTree regardless of individual terminal states.
A selected child activates the aggregate; the parent does not.
The grouped entry keeps one stable family key across single/count changes. Its title/count and status
crossfade over250ms, including adding/removing a child, single-to-count transitions and interrupted
updates, without replaying entrance or briefly losing the surface. Selection colors retain the same
250ms transition. Native capsule title/count changes reuse the title capsule's 400ms identity clip:
content keeps stable constraints on a canvas capped at 240dp while the rounded visible edge and
occupied width move together. Interrupted titles retarget from the current edge. The shared row
follows that visible width directly, retaining its existing scroll, placement and lifecycle owners
without adding a second width animation. Reduced Motion retains the 250ms opacity fades and snaps
spatial changes. Individual title changes use the same clip and crossfade with the fixed status slot.
At two or more, tapping the grouped capsule opens the shared SmoothBottomSheet with the existing
Todo-style header and scrollable32dp bottom/navigation inset. Rows show each child's name and status
in the same creation order; the current child has selected colors/semantics. Both surfaces use
24dp/px rounded option backgrounds and ripple, a 24sp/px / 32sp/px Bold sheet title, and
16sp/px / 24sp/px SemiBold child-name titles. The count/status retain their existing label metrics.
Selecting a row invokes
ordinary conversation selection and dismisses the sheet; it never starts or restarts an agent. The
sheet closes if the family/connection becomes invalid, mode changes, or fewer than two children
remain. Inert outgoing capsules cannot open it or navigate. Native and WebUI share these semantics.
Only selected-parent narrow Room projections are observed. Browser events require matching owner
and selection sequence. The shared row's maximum item height feeds the existing FAB lift and combined
bottom measurement. When any User Interaction, Todo or Subagent item is present, the message-list
trailing inset includes the measured shared row height plus an additional 8dp/px of clear space
above it. The extra clearance follows the same item appearance/exit progress as the measured height:
normal motion takes each item's height plus 8dp/px times its current progress, then uses the maximum
across the shared row. Removing the final item smoothly returns the entire message inset to zero;
neither a nonempty flag nor a positive-height check may hard-drop the last 8dp/px. If another item
remains, its space is preserved. Interrupted exits retarget from current progress without a second
height animator or scroll owner. Reduced Motion snaps spatial height and clearance together while
retaining allowed opacity fades. With all three empty, the ordinary inset returns unchanged. This
gap is between the last message and the tools, not between the tools and Composer, and uses the existing single list/inset
owner on native and WebUI. WebUI's negative message-viewport margin cancels only the actual Composer
height. Tools clearance belongs to content padding and must not enlarge the viewport or shift the
foreground mask: subtract the tools portion from the combined bottom inset when locating that mask.
Its opaque boundary stays at the measured Composer host top, independent of tool presence or height.
The shared horizontal viewport uses the drawer's draw-only edge mask:
40dp/px at each physical side, capped at half its width, with 200ms FastOutSlowIn opacity feedback.
An edge fades only while more content exists beyond it; empty and fully visible rows have no mask.
RTL maps the same scroll owner's logical bounds to physical sides. Reduced Motion retains these
opacity changes. Neither masking nor its updates change geometry, hit regions, scroll offsets or
anchor ownership. WebUI reuses the shared floating scrollbar on the horizontal axis, outside the
mask: it reserves no layout gutter and appears only for mouse hover or active dragging when the
row overflows. Its input interrupts the existing shared-row scroll motion through the same owner.
One horizontal scrolling owner covers User Interaction, Todo and all Subagents,
in that order. Expanding Ask User animates the card to the viewport center through the existing
reveal scroll and card-morph progress; the expanded card disables horizontal user scrolling.
Both edge masks fade to zero through their existing 200ms alpha owners. While that expanded
lock is active, WebUI clips only neighboring Todo/Subagent visual layers and their hit regions
to the viewport's inner bounds, 12px from each physical side. This prevents an adjacent capsule
from painting into the outer inset. The editor's physical paint/hit bounds, rounded clip and
shadow stay unchanged, as do capsule layout width, 8px gaps and the sole scroll/motion owner.
The current measurement/scroll/entrance callbacks update this clip only when its bounds change;
unlock, removal and disposal restore the original visual-layer clip. Collapsing immediately
restores horizontal input and the masks again reflect overflow through the same alpha owners.
Wide viewports center the editor independently of following capsules; no second scroll or spatial
animation owner participates. Expanded requests also center on same-family navigation even without
a new-arrival reveal token; their editor lock must never retain an unreachable old scroll position.
Direct removal releases the centering offset through the card's existing appearance/exit progress.
Reduced Motion snaps travel while retaining edge opacity feedback.
Subagents do not create a nested horizontal scroller. Items bottom-align and content
centers when it fits; expanded interaction retains its editor at viewport width. Centering follows
the track's actual occupied width during prefix insertion/removal, rather than its final target
width, so a retained grouped Subagent cannot jump left before moving right on parent return. Within the same
selected conversation, stable keys, animated insertion/removal placement and scroll-range adjustment
preserve the visible reading anchor. Conversation replacement is a strict three-step boundary:
(1) outgoing Interaction/Todo and unrelated-family Subagent content completely finish their existing
inert exit, keeping their layout positions and the shared viewport fixed until no outgoing paint remains;
(2) while incoming content stays invisible, slots and the shared track finish adopting the latest
destination's natural widths and positions directly, without preserving the previous conversation's
anchor, tweening old placement into the new layout, or retaining a shrinking ghost range;
(3) only after that destination layout has been measured and placed may incoming content begin its
existing entrance. These steps cannot overlap. Rapid switches skip intermediate
owners. Parent/child/sibling navigation within one proven family still retains the keyed Subagent
list and its selection/status continuity; that exception does not retain outgoing Todo/Interaction
geometry. Within a proven family, selecting a child is a minimal diff rather than a whole-row
replacement: preserve a visible retained Subagent's screen anchor through selection and animated
removal of offscreen leading items. If an outgoing Todo/Interaction is still visible ahead of
that anchor, fade it while retained capsules smoothly move left as its occupied space closes;
these animations may run together. Keep the existing keyed capsules and make only the natural
minimum geometry change. Do not reset the shared scroller to logical start merely because a sibling
or parent/child was selected. Apply the same rule in both directions: entering a child and returning
to its parent preserve the visible retained Subagent's screen position whenever the shared scroller
is away from logical start, unless the parent opens an expanded interaction editor as described above.
A folded reinserted parent Todo/Interaction reserves its space outside that anchor
without seeking the logical start. A user drag cancels only the current geometry change;
returning to a previously seen parent layout must restore anchor compensation, including
at maximum scroll and with Reduced Motion. Browser DOM insertion/removal must compensate retained
placement before that paint, preserve the old scroll range through the existing tween and adopt
actual rounded scroll offsets without a second jump or restarted placement wave. At logical start, ordinary insertion/removal may naturally move
retained capsules. Reopening an already pending parent request is navigation, not a newly arrived
Interaction reveal; genuinely new requests and explicit expansion retain the existing reveal rule.
The selected conversation's existing loading cover releases only after its stable initial message
projection and first owner-matching Todo, Subagent and AskUser/Shell projections are all ready.
A confirmed empty projection is ready; an unobserved initial placeholder is not. Capture these
initial tool states under the same selection/connection fences before revealing the new page,
so capsules cannot leak a partial initial list or arrive late merely because their first load lagged.
Retained same-family Subagents keep their keyed navigation continuity. Subsequent live tool arrivals
and mutations keep ordinary entry/removal/reveal behavior; opening does not wait for running agents
to finish or for future requests. Reuse the existing loading and projection owners without a timer.
First paint also waits until every loaded slot has mounted, reserved its natural width and reached
its final position in the shared track. Opening a conversation or replacing an unrelated family
keeps the complete incoming row hidden during this work, then plays one shared `180ms` opacity
and bottom-center `0.96..1` scale entrance. Individual capsules do not run visible entry animations
inside that shared entrance; neither Todo nor any Subagent may slide into place after paint begins.
Reduced Motion fixes the shared scale at one and retains its fade. The same progress owns initial
row height and message clearance. During unrelated replacement, outgoing paint keeps its current
shared opacity, scale and clearance until its item exits are invisible; only then may the hidden
destination reset that progress. Loaded-empty projections complete this entry boundary immediately
without a no-content fade; a later live arrival retains its ordinary item animation. Proven same-family navigation never
replays the shared row entrance. A data-ready bit alone cannot release a partial row. Todo measurement readiness
belongs to the rendered conversation owner; a same-family switch cannot reuse the outgoing Todo's
ready width to admit the incoming capsule. Loaded-empty projections require
no capsule node and still release the same existing entry barrier. Finishing the shared reveal or
placement motion rechecks this barrier after releasing its motion state, so an initially nonempty
Interaction deck never waits for an unrelated later mutation to become visible. The shared entrance
settles an expanded editor's center against its final unscaled geometry when its scale completes.
Request drafts, folded/page state, same-owner insertion/removal motion, the sole scroller,
and current height/inset ownership are unchanged. Todo/Subagent capsules retain their natural settled content width,
rounded outline and text layout throughout insertion/removal; animated occupied width must not
squeeze, reflow, truncate or rectangularly crop a capsule. Full interaction editors retain their
natural constraints and bounded paint/hit region. During fold/unfold, the existing rounded card
owns content clipping and size interpolation; the shared slot follows its measured frame width
without a second lagging width tween or rectangular crop. The rounded edge must remain intact
through expansion and collapse, with adjacent capsules laid out outside the same occupied width.
No content may overlap adjacent capsules; resting
shadows remain visible. An overflowing track at its logical start has only the normal 12dp/px outer
inset, without hidden-slot space or centering gaps. First render does not seek a Subagent. A newly
arrived visible User Interaction request (including a new question in an existing card), or an explicit
interaction expand event, moves this same shared scroller to its logical start using a 350ms
FastOutSlowIn curve. This reveal takes priority over geometry-anchor preservation, without unfolding
a minimized card or switching its current request page. Request removal, ordinary recomposition and
other owners' requests do not retrigger it. User input cancels programmatic travel; Reduced Motion
snaps to the start. Shell confirmation remains the first page followed by questions in request order;
request-kind-prefixed identity, drafts and exact independent actions prevent one kind from resolving
the other. Reduced Motion snaps spatial changes and retains permitted fades. Tests cover list/search exclusion without deleting children,
narrow/wide horizontal overflow, interrupted motion, click/return, parent highlight/reselection,
drawer drag/pinning, stale events, clear/disconnect and native measurement release. Phone UI
acceptance remains manual.

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
Material-layer verification covers one component file per primitive with no duplicated call-site markup,
the `style.css` -> `material.css` -> `theme.css` order, the determinate track colour, the shared
indeterminate turn/step/arc durations, one stop dot per integer step with the handle band skipped and no
trailing dot at the maximum, the window entrance duration and easing, and the
`1 / 2 / 3 / 4 / 5 / 10` layer order with the top bar above the composer gradient on both Compose and WebUI.
