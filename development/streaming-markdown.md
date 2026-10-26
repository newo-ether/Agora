# Shared Streaming Markdown UI Contract

This is a binding part of the [message generation contract](message-generation.md).
It preserves the requirements of section 8.7; scope and authority are unchanged.
Tool lifecycle wording and state semantics are defined by [tool-presentation.md](tool-presentation.md).

Ordinary answer Markdown, Thinking Bottom Sheet Markdown, and Compact Bottom Sheet Markdown use one
shared streaming Markdown message UI implementation. That implementation owns the existing
incremental append-only scan, stable/live block split, off-main parsing, long-document update
cadence, and stream-to-terminal renderer continuity. A caller must not keep a second streaming
Markdown algorithm or switch to a different terminal renderer merely because streaming ended.

Native reference definitions are document metadata. Definitions parsed in a later live or stable
block resolve references in earlier stable text without reparsing that text's AST. Definition labels
use the parser's case/whitespace normalization; the first definition wins. Inline/autolink annotation
must not overwrite that document lookup. A non-append reset clears the old definitions, while a
partial live destination updates only the current tail's metadata. Closed blocks are inspected once.
Normal, prepared, virtualized and incremental roots share the same definition-free render projection.
Definitions and their trailing structural separators contribute no rows, text or height, including
inside lists and quotes; separators between visible paragraphs retain their existing spacing.
Projection must preserve the original source, stable ASTs, source offsets, parent relationships and
full glyph birth timeline. Stream-to-terminal settlement keeps the same message subtree and links.
Regression coverage uses the real message component to verify URI activation and equal message
height with 48 trailing definitions, plus prepared and virtualized detail paths, split definitions,
duplicates, reset, fenced literals, nested metadata and ordinary paragraph geometry.
The browser Compact detail uses the existing `DetailSheet` and `Markdown`, with authoritative
running state, thought typography and App Reduced Motion passed into the shared renderer.
The shared browser Markdown accepts an optional emptyStreamingText input. Compact passes the native
localized Context compacting... label only for an actively streaming blank body. The label uses
primary color, the caller's thought typography, 8px top padding and retained 180ms linear fades.
Nonblank content replaces it; terminal blank content does not retain a status. This is shared
renderer presentation, not a Compact-specific renderer, always-visible footer or status authority.
Browser math placeholders expand only in rendered text nodes, never in source attributes,
link attributes, or code literals. Source mapping stays intact; each occurrence creates exactly
one formula node and cannot duplicate surrounding prose or emit HTML attribute fragments.

`ChatMarkdownCodeBlock` also renders standalone raw code with the same code background, padding,
typography and wrapping. Its copy header stays enabled by default. A host that already owns a guarded
copy action may omit that header without changing other consumers or duplicating the code renderer.

Native release builds must preserve JLaTeXMath's `NewCommandMacro` class, public zero-argument
constructor and `executeMacro(TeXParser, String[])` method. Environment expansion, including
`pmatrix`, resolves that handler by reflection; ordinary debug rendering tests do not validate
R8 retention. The minified release must also pass the reported matrix's parsing check.

Standalone/display LaTeX uses that same shared Markdown component graph. It remains start-aligned and
renders at its intrinsic formula width inside a message-width horizontal viewport. A formula wider
than that viewport scrolls horizontally so all content remains inspectable; a formula that fits has
no effective scroll range. Every formula on its own line (display, height-promoted, or an inline
formula wider than 80% of its paragraph width, decided at display time from that paragraph's width)
renders as one single-line selectable Text holding one inline placeholder, so a covering selection
highlights the whole formula. Every formula's alternate text is its exact original source slice,
delimiters included, carried in its `latex://` link, so selection copies the original text and never
a URL. Copied text must equal the original message text. Because a placeholder cannot lay out
alternate text with line breaks, a source's `\n` and `\r` are stored in the alternate text as
U+E000 and U+E001; the chat selection host's clipboard (`FormulaSourceClipboard`) restores them on
copy, and the source parser restores them before resolving the formula. System text actions other
than copy (for example Translate or Speak) receive the stored marks. Formula bitmaps render at `20 sp` and are padded with transparent rows so the TeX math axis
sits on the bitmap's vertical center, which `TextCenter` placeholder alignment puts on the text
center. Other inline LaTeX and ordinary Markdown images retain their existing layout; once a paragraph
splits for a wide formula, its other images use their block presentation and search positions are
not reported for that paragraph.
During active streaming, display-formula scrolling uses the same interaction commit gate as code-block
scrolling so an in-progress gesture is not replaced by a newer parsed Markdown snapshot.

The implementation is only a parameterized UI variant. Its allowed inputs include Markdown
content, streaming state, render context, font/size/color, a publication birth-time glyph timeline, and a
generic animated empty-stream presentation. Every append-growing live text surface uses one
of two rules: a time-only fade with no character-count cap for ordinary/timeline answer
Markdown and plain/code leaves and full detail text; one-line Thought, Tool and Transcription
summaries use the shared muted-tail policy: the last 42 code points in six bands, newest alpha 0.38,
age gain 2 per second and neutral onSurfaceVariant at 60%. An active Thought preview flattens newlines
and shows an ellipsis plus the last 60 Unicode code points when longer; terminal previews use full
text with one-line layout ellipsis. This summary-only rule does not cap full Markdown.
Static titles, terminal labels, Retry, error text, and citation metadata do not replay this stream
animation merely because they share typography.

`ToolSummaryText` uses the canonical `ToolPresentationState` as its only whole-summary Crossfade
identity. Argument, subject, progress, output, and other incremental summary changes inside one state
render directly through the shared muted-tail streaming text; they must not restart a whole-row
Crossfade. A Crossfade is allowed only when the resolved Tool lifecycle state changes. Grouped
and individual Timeline rows use this same owner and contract.

The fade is draw-only. One unchanged full AnnotatedString/Text layout owns shaping, kerning, wrapping,
alignment, semantics, links, citations, selection mapping, search highlights, and code controls while
only glyph paint alpha changes. Terminal settlement must not remove temporary foreground spans,
replace the Text/Markdown implementation, reset the paint origin, or otherwise create a left jump.

Every newly published Unicode code point receives a birth timestamp only on its first visible render
snapshot. Code points published together have the same initial alpha regardless of Provider delta,
ordinal, or position. Input offers, conflated/stale parses, and interaction-held snapshots cannot age
glyphs before their first visible frame. Every new glyph starts
at output alpha zero. Existing glyphs retain their metadata and never replay when another delta
arrives, Markdown is reparsed or promoted, Compose recomposes, LazyColumn evicts or rehydrates a row,
the row scrolls off-screen and back, or generation becomes terminal.

The approved constant is `k = 2.0 s^-1`: `rawAlpha(t) = k * elapsed(t)` and
`alpha(t) = clamp(rawAlpha(t), 0, 1)`. Alpha is zero at birth, 0.2 at 100 ms, 0.5 at 250 ms, and one
at 500 ms. Delta identity and all position encoding, delay fields, spatial bands, positive starting
alpha, count caps, and large/long-document bypasses are forbidden.

Delta metadata may only select the persistent incremental path at renderer entry; it must not reach
the parser worker, tracker, fade samples/specs, or paint. Active generation publishes each answer-delta
list as a point-in-time copy, never an alias of the mutating Provider accumulator.

Only glyphs that have reached output alpha one may be pruned from the tracker, without a count cap and
without changing later output. The finite Welcome/Onboarding typewriter may share the same low-level
stable glyph-paint primitive, but it is not the scope boundary. A caller must not disable the streaming
fade merely to hide a surrounding answering-tail dot. The ordinary message list may own that separate
dot, while Thinking and Compact Bottom Sheets omit it without changing text rendering. Typography or
placeholder differences remain parameterized.

Finalized Thinking Bottom Sheet Markdown is selectable in every rendering branch, including the
virtualized single-segment long-document path. In the WebUI, ordinary scrolling and unrelated
streaming, Composer or payload refreshes must not collapse a selection in unchanged composed text.
Unchanged user/ask_user bodies and unchanged Markdown search projections keep their text nodes;
active search styling updates cannot rebuild otherwise unchanged selected text. Fix the canonical
render/update owner rather than capturing and restoring browser ranges after destructive updates.
Search ranges use the browser's draw-only CSS Highlight registry; search entry, active-hit changes
and dismissal never wrap, split, move or normalize body text. MessageList reads those same ranges
for occurrence geometry, and the existing body presenter releases its ranges on update or unmount.
Selection uses the shared no-auto-scroll selection
host so dragging handles never repositions the conversation. Active streaming content remains
non-selectable; terminal selection moves the same composition through movable content instead of
recreating its Markdown subtree, resetting state, or emitting a zero-height frame.

Generation terminal presentation is not Markdown syntax or renderer state. One stateless shared text
component renders the ordinary answer and detail-sheet error beside the shared Markdown
implementation. It does not subscribe to or own generation lifecycle state.

Typed `GenerationError` remains the domain boundary. Before chat generation or transcription
persists a display error, one Android-resource-backed presenter resolves app-owned categories and
known transport reasons in the current locale. Authentication, rate limit, server/network wrappers,
SSE parse, incomplete stream, output truncation, request validation, cancellation, timeout,
unexpected-error fallback, and tool/transcription/embedding wrappers are resource owned in every
supported locale. Unknown exceptions resolve the first nonblank localized message along their cause
chain, with identity-based cycle protection. The outer message takes priority; when no cause has a
message, the outer exception class name preserves diagnostic identity. API and localized presentation
consume that same resolved detail, without exposing stack traces or request data. Previously persisted
generic strings cannot recover a discarded Throwable and remain readable unchanged. Exact common transport details such as connection closed/refused/reset, unknown
host, and TLS failure are matched case-insensitively and localized. Nonblank Provider/API/server/OS
diagnostic detail remains verbatim inside the localized wrapper unless it is plain prose whose first
lowercase Unicode letter can be title-cased safely; codes, URLs, JSON, and identifiers are not
rewritten. A narrow render-time compatibility normalizer applies the same known-phrase and safe
sentence-case rules to already-persisted strings without mutating Room data. The gray error bar and
later Provider terminal-error projection both use that same compatibility normalizer. A JSON object
may contribute one nonblank human-readable detail in the strict order nested `error.message`,
top-level `message`, then top-level `reason`; JSON escapes are decoded and duplicate envelope fields
are omitted. Malformed JSON, non-object JSON, or an object without one of those supported string
fields remains verbatim. The normalized result is presentation-only for Room, but it is also the
exact Provider-facing error detail in the current Android locale.

Embedded Local context-capacity failures carry the stable code `local_context_capacity` from both
native `context_full` completion and preflight context-exceeded failure through live and final
`MessageSegment.errorCode` persistence. Native callback failures must be classified from the raw
`LlamaGenerationEvent.Failed.message` before localized display formatting; callback-delivered and
thrown `LOCAL_CONTEXT_EXCEEDED:*` failures therefore use the same semantic code. The help action is
eligible only when the last nonblank persisted error segment has that code and the failed message's
own `modelName` begins with `Local:`. It must not infer eligibility from localized error text, the
currently selected model, Ollama, or a remote Provider. Error-only and
partial-answer-plus-error layouts both retain the same eligibility through terminal transition
animation.

An eligible shared gray error bar places an uncontained Primary-colored localized `Learn more...`
action below the selectable error text. Its pressed color directly reuses Markdown links' `180 ms`,
`0.72` alpha, and `FastOutSlowInEasing` contract, with no Surface, capsule, background, indication,
or extra error icon. The shared component alone owns the Dialog open state. Activation opens a
localized limitations Dialog explaining mobile memory/context constraints, common System Prompt,
tool/function, and skill context sources, and the three-dot Low Context Mode location; the existing
localized `OK` action dismisses it. STOPPED and nonqualifying errors keep the ordinary terminal text
without this action or Dialog.

A normal durable MODEL row ending in ERROR or STOPPED remains that exact assistant turn in every
later Provider request whose selected context contains that row. API-only canonicalization
preserves its nonblank partial answer first and appends one terminal annotation to the same assistant
text.
For ERROR, the final gray-error-bar string in the current Android locale is mandatory
Provider-visible request content. API-only canonicalization sends the last nonblank persisted
`error` through `normalizePersistedGenerationErrorText(context, raw)`, then appends that exact result
as `Details:`. Structured envelope fields that the shared gray presentation omits are not separately
appended to Provider context. No request-building, context, projection, or Provider-adapter layer may
substitute a different parser, localization, normalization, or fallback. If the failed MODEL row is
in the selected context, dispatching an API request without that exact displayed error result is
contract-invalid. Only legacy error-only rows without an error segment may use their stored text as
the formatter input. STOPPED appends its stopped annotation even when no partial answer exists.
The API projection normalizes only its transient status to prevent duplicate projection; it never
changes Room. It must not change either terminal row to USER, prepend it to a later user message, or
drop the concrete error. Synthetic tool/result rows and Compact rows retain their dedicated
protocol and terminal contracts.

Ordinary assistant messages render no general-purpose status row. Sending, Thinking, answering,
terminal success/token usage, stopped, and failed labels must not restore that variable-height legacy
row. Its historical position above all Thinking/tool/answer content instead retains exactly one empty,
status-independent 6 dp vertical spacer. This is a fixed height, not a minimum-height threshold, and it
never hosts or alters the current below-Thinking pre-output/Retry activity.
Generation ERROR and STOPPED render text only: no Surface/background, rounded outline, Info icon,
icon gap, or inner container padding. Both use the exact Retry label tokens, `ChatType.body` and
`onSurfaceVariant` at 0.55 alpha, but neither uses Retry's grapheme entrance or active white dot.
ERROR remains full-line, multiline, and selectable with its nonblank detail; STOPPED remains a
localized content-width nonselectable label, including inside selectable detail bodies. Browser
ordinary inline errors, error bars, Compact detail errors and failed Tool/Shell detail text use this
same selection policy without recreating their text on unrelated refreshes. Their existing contextual outer vertical separation remains, and
their durable ERROR versus STOPPED semantics stay distinct. When the immediately preceding visible
Assistant content is a Thinking/Tool/Transcription card, either terminal label receives exactly 12 dp
of top separation. When answer Markdown is the immediately preceding visible content, the established
text-to-terminal separation is exactly 8 dp. Timeline mode derives adjacency from its final visible
segment; the mere existence of an earlier card does not add spacing. Compact capsule error/stopped
chrome and detail-sheet defaults are independent and unchanged.

Generation activity and terminal presentation are resolved from one current assistant-message
snapshot. Body content, Thought/Tool/Transcription visibility, pre-output activity, answer-tail
activity, stopping, stopped, and error state must never be computed from separately collected or
remembered snapshots. Exactly one white-dot owner may draw in a frame: an active ordinary assistant
with no Answer and no visible information card uses the inline slot; an active ordinary assistant
whose last visible output is Answer uses the answer-tail slot; a visible information card, stopping
state, or terminal state uses no white dot. Retry remains part of the inline slot. These predicates
are mutually exclusive by construction rather than coordinated after rendering.

The inline activity and terminal text share one stable final-geometry slot after visible
Thought/Tool/Transcription content and before answer Markdown. On a transition to STOPPED or ERROR,
the terminal label occupies its final coordinate in the first terminal frame. Any outgoing inline dot
may remain only as a draw-only overlay at that coordinate while fading; it contributes no height,
padding, baseline, or sibling position. `Generation Stopped` and error text therefore never begin
below an exiting dot and never move upward as an animation completes. Reduced Motion changes only
draw-time motion or opacity and cannot expose a different layout path.

Pre-output keeps the exact 11 dp dot. Visible Answer activation immediately releases the inline slot,
and the answer-tail dot is the sole source from its first frame at the final anchor. Retry keeps the
localized label, 8 dp gap, measured caret placement, and direct render-layer translation of the same
dot. The answer-tail dot and terminal controls share the reserved 44 dp bottom action slot without a separate LazyColumn child; direct exit paths retain draw content through zero alpha
without `AnimatedVisibility`, expand/shrink layout animation, `animateContentSize`, coordinate
followers, or retained layout height. The fixed action slot remains composed through streaming,
Stop admission, stopping, and terminal settlement, including pre-answer messages; only its drawing
and interaction change. When stopping hides an owned pre-output or Retry activity, the existing
inline presenter retains its 24 dp minimum slot until terminal content takes ownership. WebUI must
keep these same slots in its existing message presenter without a parallel freeze, extra settlement
delay, compensating scroll, or duplicate Markdown subtree. Their alpha-bearing graphics layers use
`CompositingStrategy.ModulateAlpha`, set `clip = false`, and never rasterize a breathing circle into
tight rectangular bounds. Continuous-motion policy may own breathing; it never owns placement or
layout geometry.

Retry still fades its label in by Unicode grapheme at 27 ms per grapheme, bounded to
225-600 ms, with the fast-start, slow-finish `LinearOutSlowInEasing` curve. The entrance plays only
once for one fresh retry-indicator composition. Attempt/label updates inside that episode show the
complete new label without replaying text entrance. Leaving and later re-entering Retry may create a
fresh label-reveal composition. Reduced Motion shows the complete label immediately; the directly
rendered dot keeps the ordinary continuous-motion breathing policy. The label remains ordinary
Markdown body size and semi-transparent gray, and Retry presentation never owns scrolling or
attachment state.

The compact Thinking card is content-width and left-aligned while collapsed, and fills the available
message width while expanded. Its shell extends exactly 4 dp into both sides of the message list's
8 dp content inset, producing symmetric 4 dp screen-side margins in the expanded state. The collapsed
state retains content width and the same 4 dp left edge. One shared start-anchored horizontal-overflow
host must keep the outer message layout at normal width while measuring the inner card shell with
unbounded horizontal constraints. Merely calculating parent width plus 8 dp and applying preferred
`width` or `requiredWidth` directly under a bounded parent is invalid because coercion/centering can
discard or displace the right extension. This external-only rule must not change header or segment
content padding. It must not use card-level `animateContentSize`: an explicit
400 ms width-only transition matches the existing 400 ms vertical expansion/collapse and animates
between the measured localized header width plus a 6 dp anti-ellipsis allowance and the extended
parent maximum width with a fast-start, slow-finish `LinearOutSlowInEasing` curve. The bottom-aligned
WebUI reveal keeps every segment at full measured height; the grid's animated track is aligned at its
block end, and full-height children remain bottom-aligned within that track. The outer reveal alone
owns the vertical clip; the inner track keeps visible overflow so fractional sizing cannot cut
content a second time above the reveal's leading edge. Its inline minimum is zero, so long
Thought/Tool summaries receive the stable expanded width and truncate in their own one-line
ellipsis boxes rather than expanding the grid track beyond the reveal. Fractional track sizing
must not leave unused space below the track while it expands. Flex shrinking must not compete with the
400 ms clip motion or leave a blank region below the moving content. A segment's appearance scale
uses the native center origin; the reveal alone owns its existing bottom alignment and clip.
Resolved Thinking card titles Crossfade over 280ms linear, including live ticks and terminal titles;
unchanged titles do not replay. Tool summaries use the same 280ms linear transition only for a
canonical toolState change, never for an incremental same-state summary update. Outgoing draw text
is inert and accessibility-hidden; Tool tail fade runs only for the current active state outside
that Crossfade. Reduced Motion retains these fades and drops only appearance scale.
The collapsed
target remains capped by the available width. The animated width belongs only to the card shell:
leading header content and expanded content retain a stable target layout width, remain anchored at
`Alignment.TopStart`, and are clipped/revealed by the shell instead of being squeezed, reflowed, or
centered at intermediate widths. Reduced Motion snaps spatial width.

The header uses an 18 dp corner radius, restored 12 dp start by 10 dp vertical padding, an 18 dp icon
slot, an 8 dp icon-title gap, and the accepted local 13 sp / 22 sp SemiBold title. Expanded Thought
and Tool rows use the restored exact 10 dp horizontal content padding. The title row reserves one
exact 4 dp title-to-arrow gap plus the unchanged 26 dp trailing disclosure reservation. The same single 18 dp
`KeyboardArrowDown` is a Surface-local overlay, outside the unbounded/clipped content Row, so its
layout box tracks the visible animated shell's end edge with an exact 8 dp end inset at every width.
No second disclosure exists. The disclosure belongs to the card's local drawing order: it never
paints above the message viewport's blur/fade, top-bar gradient or expanded Composer. Browser card
surfaces keep a persistent local stacking context even when entrance motion ends or the Composer
removes the viewport mask. That single vector rotates to -90 degrees for detail-sheet navigation,
0 degrees while inline-collapsed, and 180 degrees while inline-expanded; spatial motion animates the
rotation and Reduced Motion snaps it. The header icon uses the shared motion-aware 18 dp slot; only
the loading ring is 16 dp while brain, tool, image, and disclosure icons remain 18 dp. The loading
ring appears exactly when the owning message generation is active AND no newer visible message
block exists below that card. Persisted Thought/Tool/Transcription activity never overrides the
card's position. The current tail card stays loading even after its internal segments settle;
a card followed by an answer or another visible block is terminal for header loading and live titles. Once the owning message/Run is terminal, no persisted segment
state may keep the card header loading: in particular, a detached `BACKGROUND_RUNNING` tool keeps
its own tool-row background status but is terminal for card-level generation presentation.
The indicator uses an exact 2 dp stroke. Loading, brain, tool, and image icon changes all remain
targets of the existing Crossfade; no active/static icon change is abrupt. During an active Thought,
only an absent/default `Thinking...` title becomes a once-per-second localized live-duration label
based on the latest snapshot. Live and terminal duration titles share one three-tier breakdown:
seconds below 60 seconds; minutes plus seconds below one hour; hours plus minutes plus seconds at or
above one hour. Terminal tool-count variants use the same breakdown before their unchanged tool-count
suffix. Provider titles and Tool/Transcription titles remain semantic. At every Provider-pass thought boundary, the runtime finishes
authoritative thought timing and changes the in-memory live status from THINKING to SENDING before
publishing that finished-duration snapshot. The UI ticker and Thought-active loading condition stop
at that timing boundary; current-tail loading may continue while generation remains active until an
answer appears below the card or generation terminalizes. Later terminal settlement must not make
the displayed duration decrease.

Answer Markdown and Thinking-segment Markdown use one presentation multiplier of exactly 1.1 for
line height only. It applies to paragraph/body, ordered and unordered lists, tables, H1-H6,
block/inline code, and both streaming plain-text fallbacks. Answer/Thinking Markdown font sizes and
their source `ChatType` tokens remain unchanged; the multiplier belongs to the chat Markdown asset owner.

User-message body text uses the dedicated `ChatType.userBody` token at 15 sp with an exact 24.2 sp
line height, equal to the former 22 sp line height multiplied by 1.1. Branch navigation, the inline
editor, and dropdown-menu typography are unchanged.

A non-editing user bubble owns its action dropdown through long press. The separate action row below
the bubble is absent; the branch selector remains independently visible. The existing Material menu
style contains Copy, Edit, Select Text, Info, and Delete in that order and retains current availability
rules. For Task, Loop, Ask User and Subagent source-tagged user messages, Edit stays visible and
disabled; native and WebUI use the same source rule and cannot reopen a stale editor for those rows.
Subagent completion bodies are omitted from the timeline and remain fully readable through this
existing Select Text sheet, with Copy retaining the same original text; their compact name/status
and gray failure detail presentation is owned by application-ui.md section35.
Selecting Edit on an ordinary unmarked user message enters its existing inline editor and requests focus on its
TextField once the edit branch is composed, so it is immediately ready for typing. This focus request
does not select text, redefine cursor placement, force the IME through a second owner, or alter
composer/search focus policy. Select Text reuses the existing custom Thinking detail-sheet shell with
title `Select Text` and
renders only the raw user message text in the shared no-auto-scroll native selection host. That
sheet-only body copies `ChatType.userBody` with font size reduced from 15 sp to 14 sp while retaining
the shared exact 24.2 sp line height; the user bubble itself remains 15 sp. Its raw content branch uses
12 dp top, 24 dp horizontal, and 32 dp bottom padding so text does not crowd the header divider. It
does not include attachments.

Thinking, Select Text, and Sources share one reusable `SmoothBottomSheet` Compose shell. A small
stable state plus `rememberSmoothBottomSheetState` owns Hidden/Partial/Expanded values; the shell
owns the edge-to-edge Dialog/Surface, 0/0.45/0.94 anchors, 0.9 damping and 350 stiffness snap spring,
interruption, native dim curve, scrim/back dismissal, draggable handle/header, Reduced Motion snap,
and nested-scroll collapse driven by a caller-provided content-at-top predicate. `SegmentDetailSheet`
owns selected-segment navigation, titles/back action, scroll/LazyList state, Markdown/tool/media,
footer/error, and Select Text content. The Sources caller owns its dynamic title, ordered LazyList,
list-top predicate, and pending source activation; selecting a row requests the shell's normal hide
transition and activates that source only after dismissal completes. Extraction preserves the shared
geometry, thresholds, motion, header/divider, and rendering. Image, settings, and composer sheets
remain owned by `MotionAwareModalBottomSheet` and are not migrated.

Ordinary Timeline mode groups each visually consecutive Thought/Tool/Transcription run with the exact
Settings group grammar: 2 dp between surfaces; a single row uses 24 dp corners; the first uses 24 dp
outer-top and 5 dp adjoining-bottom corners; middle rows use 5 dp corners; the last uses 5 dp
adjoining-top and 24 dp outer-bottom corners. All four radii animate when a streamed row changes an
existing row's group position. Each radius uses one monotonic 240 ms `FastOutSlowInEasing` tween,
never a bouncy/overshooting spring, and is clamped to [5 dp, 24 dp] after animation; Reduced Motion
snaps directly to the clamped target. The existing one-shot 420 ms row fade/scale entrance remains
draw-only and independent from later manual or terminal expansion changes. When Auto-Expand Active
Group is enabled, a genuinely new active Grouped card is laid out at its final expanded width,
padding, disclosure state, and content height on its first frame. Its first appearance runs only the
existing Surface-local fade plus 0.90-to-1.0 scale entrance; it must not first compose collapsed, run
a width/content expand transition, start a layout-mutation anchor for that already-final geometry, or
force Tool-containing groups opaque. Historical groups and recomposition/off-screen re-entry do not
replay this entrance. Timeline Thought/Tool/Transcription cards and grouped blocks own exactly one
appearance modifier on their actual overflow-sized Surface; a bounded outer appearance Box and a
second 0.90 scale layer are forbidden because they clip the deliberate 4 dp overflow. Answer-block
appearance ownership remains unchanged.
Group position is resolved from rendered order rather than raw adjacent indices: a nonblank visible
Answer ends the run, while blank Answer, Error, and any other non-rendered segment are transparent to
the previous/next scan. Invalid indices fail closed as a single row. The top/bottom spacing between a
run and surrounding answer content remains unchanged.
Ordinary inline Timeline shells reuse the same start-anchored unbounded host and extend 4 dp into
both sides of the message list's 8 dp inset, matching the expanded Thinking shell's symmetric
4 dp/4 dp outer margins without changing internal padding. They must not rely on bounded-parent
`requiredWidth` overflow. The Thinking segment bottom-sheet list uses the same shapes and 2 dp separation but retains its own
sheet-local 20 dp horizontal inset. The shared Timeline/sheet card row uses 10 dp vertical internal
padding, increasing both presentations without a fixed or minimum height.

The top-level Thinking segment bottom-sheet title uses the same shared semantic/live title resolver as
its compact Thinking card, including default live `Thinking for Ns...`, Provider titles, Tool/
Transcription titles, and terminal duration summaries. A selected detail page retains its own segment
title. Sheet-list segment surfaces use a neutral translucent gray
`surfaceVariant.copy(alpha = 0.25f)` container with unchanged `onSurfaceVariant` text content.
Thought, Tool, and Transcription leading icons use full `primary`; the trailing disclosure arrow
uses neutral gray `onSurfaceVariant` at 0.5 alpha. Inline Timeline and compact Thinking palettes
remain unchanged. The detail-page circular back button alone overrides the shared
`CircularBackButton` container with `surfaceVariant.copy(alpha = 0.25f)`; its foreground and the
global component defaults remain unchanged.

Tool-call display has only Timeline (`timeline`) and Grouped (`grouped_timeline`, default).
The retired `compact` preference normalizes to Grouped. A DataStore migration persists that change
before Settings publication; old `.agora` imports use the same normalized write. Native chat,
Remote and WebUI consume this shared policy. The shared Thinking card and Context Compact capsule,
summary generation and detail presentation remain available under their existing contracts.
The Thinking segment Card/Bottom Sheet setting is visible and effective only while Tool-call display
mode is Grouped. Timeline ignores a persisted Bottom Sheet preference and retains ordinary
inline Timeline presentation; the stored value remains untouched and becomes effective again after
switching back to Grouped. Auto-Expand Active Group is visible and effective only for the
exact Grouped + Card combination. One shared pure display policy owns these applicability decisions so
Settings visibility and message rendering cannot drift. Regardless of that setting, selecting any
ordinary Timeline card or grouped Timeline row always opens the selected segment detail directly.
Only a Grouped card that is actually presented in Bottom Sheet mode opens the segment-list
page first; click intent is passed explicitly and is never recomputed from the raw stored preference.

Slash-qualified MCP/tool display titles preserve `/` and capitalize the following initial,
including Remote names such as `Blender Mcp/execute Code` -> `Blender Mcp/Execute Code`.
Use the shared title formatter for resolved MCP names and generic Remote tool labels; preserve
existing interior casing, routing identity, arguments, and other separator formatting.
MCP router metadata follows tool-presentation.md: inspect at most 4096 argument characters per
stream update and resolve only complete root IDs. Large reverse-order payloads acquire the exact
remote title and server target through the ordinary execution event. Persisted metadata supplies
history display without querying the live registry; protocol tool names and arguments remain intact.

Failed and stopped tool-detail content inside the shared Thinking/Tool bottom-sheet path reuses the
same neutral gray body-text terminal presentation as ordinary message content. Failed text remains
selectable and full-width, but neither state may introduce a `Surface`, rounded background, card, or
Thinking-specific typography. A failed MCP detail renders only that terminal failure; its raw text
and structured result must not be rendered again beneath it. EMPTY/COMPLETED MCP details continue to
render their ordinary result content. Web Search follows this shared Tool terminal contract; only its
EMPTY/COMPLETED result content uses the specialized search-result presentation. Message-level errors
remain owned by the Assistant message and must not be copied into every Segment detail. Destructive
actions, non-sheet validation text, and unboxed image-load failures retain their own semantics.

JSON-shaped Tool arguments and results in that shared Bottom Sheet use the existing prefix-aware JSON
renderer for both active and persisted content. When persistence bounding appends the exact terminal
persistence-truncation marker, the renderer treats only that marker as out-of-band presentation
metadata, parses the retained real JSON prefix, and displays the marker separately below the
structured tree. It never inserts a missing quote, key, value, object/array delimiter, or other JSON
syntax. Arbitrary trailing prose and genuinely impossible JSON prefixes remain invalid and retain the
raw-text fallback.

The same rule applies to the exact terminal Filo preview marker
`[Filo preview truncated; full output remains in Codex.]`. Its preceding JSON prefix
remains a structured tree with partial leaves, and the marker stays separate neutral
metadata. A marker quoted inside valid JSON remains ordinary data. A locally bounded
Remote preview may carry both markers; neither marker may become a fabricated field.

Native Markdown images reserve a square viewport before their file is available,
up to 300 dp and constrained by the current content width. The same dimensions
remain through pending, failed and decoded states. A known intrinsic viewport
lets the shared Markdown renderer measure images as blocks instead of allowing
large images to escape a text line's height. Each image keeps its rounded corners
and the existing fading circular overlay. Load local attachments as files.
Regression coverage must decode real images and compare the entire message and
both image rectangles before, between and after asynchronous loads at phone density
and narrow large-font width; failed placeholders alone do not qualify this behavior.
