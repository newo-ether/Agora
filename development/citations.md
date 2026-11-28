# Provider-Neutral Citation Product Contract

Status: authoritative, 2026-08-14.

This contract owns Agora's complete citation lifecycle. It applies to structured citations from
Providers, compatible relays, supported conversation imports, durable message storage, streaming
projection, message reload, copy/search/selection, and answer rendering.
The WebUI follows this complete native contract as confirmed on 2026-10-14: Kotlin projects the
canonical normalized citation records and Markdown presentation through its existing sync payload;
the browser renders terminal inline/group capsules, the Sources summary and shared source sheet,
safe URL and non-URL details, portable Copy output, and body-search metadata exclusion. It must not
parse Provider-private dialects or become a second normalization, persistence or generation owner.
The canonical Markdown projection optionally tracks original UTF-16 positions through citation
insertion, wrapper replacement, grouping and private-marker removal. The WebUI composes that map
with its existing math source map, so body search after a capsule keeps original message offsets.
Inline placeholder expansion retains each remaining text leaf's position within its Markdown token;
capsule labels never enter body-search ranges.

Citation presentation is answer metadata. It is not Markdown syntax, ordinary answer text, a tool
call, a tool result, or Provider-history content.

## 1. Product boundary

Agora normalizes proven structured Provider citation protocols into one durable representation.
Provider-private marker syntax and IDs are inputs to that normalization only. The generic Markdown
renderer must not learn an OpenAI-, Gemini-, Anthropic-, ChatGPT-, or Claude-private citation
dialect.

Standard Markdown links remain ordinary answer content. They are never reclassified as citations
merely because their URL also appears in citation metadata.

This feature is not an academic bibliography manager, CSL processor, BibTeX database, or Pandoc
citation-language implementation.

## 2. Durable representation and ownership

- Each deduplicated source is persisted as a
  `MessageSegment(type = "citation", content = <versioned citation JSON>)` in the existing segment
  JSON column. No Room column or schema migration is required.
- Version 1 content records the source kind, Provider family, stable source identity, display title,
  optional safe URL, optional file/document metadata, optional Provider-private locator, and one or
  more answer anchors.
- Each anchor uses an answer-relative UTF-16 half-open range `[startIndex, endIndex)` plus
  `citedText`. Provider offsets scoped to an output item, content part, block, or answer phase must
  be rebased while assembling the final answer; they must never be treated as offsets into a later
  concatenated `ChatMessage.text`.
- On reload or projection, a mismatched stored range may be recovered only when the complete bounded
  `citedText` occurs exactly once in the final answer. Zero or multiple exact occurrences are
  ambiguous and remain Sources-only. A missing or invalid range is allowed only as a Sources-only
  citation.
- `ChatMessage.text` remains the Provider answer after private-marker cleanup. Citation projection
  must never append synthetic Markdown links or citation labels to that durable text.
- Citation segments survive ordinary Room reload, branch/fork projection, native graph export and
  import, and backup restore through the existing segment field.
- Older Agora versions may ignore the unknown segment type. New decoding must drop an individually
  malformed citation segment without failing the message or other segments.
- Provider request/history projection, token accounting, Compact input, and tool protocol replay
  exclude citation segments. The original answer text remains the Provider-visible assistant text.

Room remains durable truth. A streaming citation overlay is temporary and cannot become a second
message graph or alter message parentage, Run identity, or generation boundaries.

## 3. Supported normalization inputs

The first complete implementation covers every currently proven structured input:

1. OpenAI-compatible Responses output-text `url_citation` and `file_citation` annotations.
2. Gemini candidate `groundingSupports` linked to `groundingChunks` through chunk indices.
3. Anthropic citation-bearing text blocks and streamed `citations_delta` values.
4. ChatGPT exports whose private-use citation markers can be resolved through exported source
   metadata.
5. Claude exports whose citation DTOs provide a resolvable URL or document/file source.

Gemini segment offsets are UTF-8 byte offsets. Normalization must convert them against the complete
accumulated answer into safe UTF-16 ranges and validate the resulting text. A conversion that lands
inside a UTF-8 sequence, outside the answer, or on mismatched cited text is invalid.

A Provider-private source ID may be retained as bounded non-display metadata when required to
resolve markers or deduplicate one imported source. It must never be rendered, copied, indexed for
search, or exposed as a fallback title or URL.

## 4. Streaming and terminalization

- `StreamEvent` carries citation metadata as a structured event. A Provider must not encode a
  citation as `TextChunk`, append source Markdown to answer text, or create a local tool request.
- The generation pipeline upserts sources and anchors into the current message's citation segments
  while preserving first-source order. Repeated deltas or final metadata must not duplicate a
  source or anchor.
- Provider metadata may arrive after the cited answer text and remains eligible for streaming
  checkpoint persistence. Active generation keeps inline citation presentation hidden. The terminal
  answer projects validated inline source capsules. The Sources summary follows the bottom
  action-control lifecycle and remains hidden while those controls are hidden.
- A trailing streaming suffix that is still a possible parenthesized Markdown-link citation wrapper
  is withheld until it resolves to a structured citation, becomes provably ordinary answer text, or
  the answer terminalizes. A validated wrapper becomes a native capsule only in terminal
  presentation. If the answer terminalizes without matching structured metadata, the original
  ordinary Markdown is restored. Streaming must not expose the raw wrapper before that decision.
- When a streaming projection differs from the terminal projection, the final streaming Markdown
  remains mounted until the message-list mutation anchor is armed. The terminal projection commits
  on the following frame through the same Markdown subtree and adopts its measured terminal size
  immediately. Late terminal citation metadata repeats this same handoff. Crossfading duplicate
  Markdown trees, exposing terminal click targets before commit, and animating ordinary streaming
  growth or the terminal Markdown size change are prohibited.
- The same projection applies after normal completion, user Stop, or a persisted partial failure.
  Valid citations already received remain available in all three terminal states.
- Malformed, unsafe, unsupported, or late citation metadata cannot turn an otherwise valid answer
  into a generation failure and cannot change Provider semantic termination.
- Checkpoint and terminal persistence use the same bounded citation representation. Terminal
  settlement must not lose a citation accepted by the identified active Run or resurrect stale
  citation state from an older checkpoint.

## 5. Anchor validation and marker cleanup

A citation receives an inline capsule only when its range and `citedText` map deterministically to
visible answer content. Validation must reject out-of-bounds, reversed, empty, ambiguous, or
unrecoverable text-mismatched anchors. A mismatched range is recoverable only through the single
exact-`citedText` occurrence rule in section 2. Markdown syntax, links, code, HTML, or parser
transformations that prevent a deterministic source-to-display mapping cause a bottom-sheet-only
fallback; they do not justify splitting the answer into independent Markdown blocks.

A parenthesized Markdown link whose target has the same canonical safe URL as a structured citation
source is Provider presentation syntax when it maps deterministically through an overlapping anchor
or, when no positional evidence identifies a wrapper, through one unique same-URL wrapper. The
anchor may cover the complete wrapper, the Markdown link, the label, or another valid subrange; it
does not need to cover the complete wrapper. Terminal projection replaces every positionally mapped
wrapper, or the unique same-URL fallback wrapper, as one complete range with the native capsule. It
must not preserve the parentheses, link label, or Markdown target and then append a second capsule.
Repeated same-URL wrappers without positional evidence remain ambiguous and receive no inline
capsule. A standard Markdown link without the additional outer parentheses remains ordinary answer
content even when its URL matches a structured citation. For ordinary claim-text anchors, the claim
remains unchanged and the capsule is inserted after it.

Resolvable private-use markers map to their structured source and disappear from visible answer
text. Unresolved OpenAI/ChatGPT `cite`, `filecite`, and equivalent bare `turn...` envelopes are
removed while preserving surrounding answer text and punctuation. Marker cleanup must not alter
ordinary Unicode text or standard Markdown links.

## 6. Answer presentation and interaction

- A resolvable URL citation renders immediately after its supported answer claim as a compact
  native Compose capsule whose visible label is the normalized URL host, for example `openai.com`.
  It must not render the host in parentheses or as synthetic Markdown text. A resolvable non-URL
  citation uses its bounded file name or source title as the inline capsule label.
- The same deduplicated source keeps one stable identity within a message. When two or more source
  capsules would form one maximal adjacent run with no visible answer character between them, render
  exactly one grouped capsule in deterministic first-source order. Its primary label is the first
  source label and its permanently visible suffix is `+N`, where N is the number of additional
  sources: two sources render `<first label> +1`, three render `<first label> +2`. Only the primary
  label may ellipsize; the suffix and grouped source membership must remain stable. Separated anchors
  remain separate capsules. One message exposes at most 99 sources.
- Capsule measurement is final from the first frame and remains stable across recomposition. Long
  primary labels are bounded with ellipsis without clipping the grouped suffix or changing surrounding
  Markdown/message layout. A single-source inline capsule measures only its rendered primary label
  plus symmetric padding; suffix gap and `+N` width exist only when additional sources exist. Every
  inline capsule uses 11 sp / 12 sp SemiBold text, a 22 sp placeholder height, 7 dp inner
  horizontal padding per side (14 dp total), and a transparent 2 dp outer gap on each side included
  in its stable placeholder width. The primary-label cap is 84 dp and a grouped suffix remains
  separated by 4 dp. The Sources summary capsule has a 36 dp minimum height, 16 dp horizontal padding,
  8 dp vertical padding, one 18 dp Link icon before the dynamic count with an 8 dp gap, and semibold
  `labelLarge` text. Icon and text share the existing capsule foreground color. Its external left
  edge extends 4 dp into the message list's 8 dp inset, matching the compact Thinking card's 4 dp
  screen-side margin without changing the capsule's internal padding or right-side geometry.
- Inline/group, numbered-source containers, and bottom-sheet source rows own a draw-only fade from
  alpha `0f` to `1f` over 320 ms with `LinearEasing`. The opacity layer adds no scale, translation,
  delayed data, hidden click target, or remeasurement. The Sources summary has no independent fade,
  scale, measured-height host, retained zero-height slot, or layout-mutation owner. It is composed at
  the same visibility boundary as the bottom information actions and uses that owner's exact alpha,
  including the 320 ms enter, 220 ms exit, `LinearEasing`, and interruption behavior. While a shared
  exit fade is still visible, its fixed content may remain composed but is disabled and exposes no
  semantics; when the shared alpha reaches zero it leaves layout directly. Stable
  message/source/group identity prevents ordinary recomposition or count-label updates from replaying
  capsule transitions. Genuinely new/reappearing inline, numbered, and source-row capsules replay
  their owned opacity fade. A valid Gemini citation first visible around answer terminalization must
  start at zero draw alpha and fade in rather than flash at full opacity.
- Primary inline/group, summary, and numbered-source containers use the thinking-card palette: the
  theme surface color at 2 dp tonal elevation with `primary.copy(alpha = 0.7f)` foreground text.
  Each complete source row in either Sources sheet is transparent at rest. It retains
  `RoundedCornerShape(50)` clipping before `clickable` only to confine ripple feedback to the pill
  outline while preserving its full-row tap target.
- The answer remains one Markdown document. Citation rendering uses inline placeholders and Compose
  inline content inside the existing Markdown text component. Every normal and incremental Markdown
  root must receive the exact citation inline-content map explicitly; an object-replacement character
  without a bound Compose entry is prohibited. It must not render range-separated fragments as
  independent Markdown blocks.
- The always-visible `Sources` section below answer content is prohibited. When and only when the
  bottom message action controls are visible, show one left-aligned native Compose summary capsule
  immediately above them with an 18 dp Link icon, 8 dp gap, and dynamic text `${sourceCount} Sources`.
- Activating the summary capsule opens the one shared Sources bottom sheet containing every
  deduplicated source in first-source order and titled exactly `N Sources`, without emitting haptic
  feedback. Activating a grouped inline capsule opens the same component with only that ordered group
  and the same exact dynamic title. The Sources component uses the shared `SmoothBottomSheet` shell,
  supplies its LazyList top state for nested-scroll handoff, and completes the normal shell hide before
  activating the selected source. The title uses `ChatType.detailTitle`, matching the thinking-segment
  bottom sheet. Every source-row title, including safe URL sources, uses theme `onSurface` rather
  than `primary`; secondary file/location metadata remains `onSurfaceVariant`. Each source number
  uses an `onSurfaceVariant` 12%-alpha neutral gray background and an `onSurfaceVariant` 80%-alpha
  number. Activating a single-source inline capsule retains direct safe-URL or non-URL detail behavior.
  Every sheet source row is an accessible full-row tap target
  whose ripple is pill-clipped, and selecting it retains safe URL or in-app non-URL detail behavior.
  Dismissing either sheet changes no message or citation state.
- Every clickable link rendered in chat answer content uses the theme link/accent color with
  `TextDecoration.None`. Sources-sheet URL titles are the explicit presentation exception: they use
  `onSurface` like non-URL source titles while retaining the same safe link target and activation.
  Pressing and releasing an ordinary Markdown link interpolates only that link's text color between
  the normal accent and its existing 72%-alpha pressed color over 180 ms with
  `FastOutSlowInEasing`; the color-only feedback remains enabled under Reduced Motion.
  Link labels, targets, safe-activation rules, and ordinary Markdown semantics remain unchanged.
  Non-URL file/document sources remain normal text and open the in-app detail surface.
- A safe HTTP(S) source opens through Agora's existing safe-link interaction path. A non-URL
  file/document source opens an in-app detail surface showing available title/file name, location,
  and cited excerpt without inventing a URL.
- Every single-source inline capsule exposes its existing source accessibility label. A grouped
  capsule exposes its source count and primary source context. Alternate text is
  `[<capsule label>]` for a single source and `[<first label> +N]` for a group so text selection
  and non-inline fallback remain intelligible without introducing a parenthesized domain.
- Invalid or ambiguous anchors remain in the summary bottom sheet but receive no inline capsule. A
  source with no safe URL remains useful through its detail surface; it is not silently discarded.

## 7. Copy, search, selection, import, and export

- The message Copy action emits the cleaned original answer followed, when sources exist, by a
  portable Markdown `Sources` list. It includes display titles and safe URLs where available; it
  never includes raw Provider-private IDs.
- In-conversation text search matches only ordinary selected-branch USER and Assistant message body
  text through the existing visible Markdown projection. Citation source titles and all citation
  metadata are excluded, including raw URLs, file IDs, Provider-private IDs, and encoded citation
  JSON.
- Selection operates on visible rendered content. Inline capsule alternate text remains
  `[<capsule label>]`; the dedicated Copy action remains the authoritative portable
  answer-plus-Sources export.
- Native Agora export/import preserves citation segments losslessly subject to the existing bounded
  message policy.
- ChatGPT and Claude importers normalize resolvable exported citations into citation segments.
  Unresolvable private markers are removed without deleting surrounding answer text. Ordinary
  imported Markdown links remain ordinary links.

## 8. Security, normalization, and bounds

- Activatable URLs must pass the existing safe HTTP(S) policy. Unsafe schemes, malformed URLs, and
  missing hosts are retained only as non-clickable descriptive metadata when otherwise useful.
- URL source identity uses a canonical safe URL form. Non-URL identity uses bounded Provider/source
  metadata and must not depend on a visible transient ID.
- Deduplication preserves first appearance and merges later valid anchors into that source.
- Version 1 accepts at most 99 sources per message and at most 32 anchors per source. Additional
  entries are dropped deterministically.
- Bound each title/file-name/location/private-ID field to 512 characters, each URL to 4096
  characters, each `citedText` to 4096 characters, and each excerpt to 8192 characters before JSON
  persistence.
- Failure is per citation. One malformed source, anchor, marker, or JSON segment must not hide the
  answer, discard unrelated valid citations, execute a tool, or fail message decoding.

## 9. Ownership boundaries

| Owner | Responsibility |
|---|---|
| Protocol-local Provider parser/router | Decode official wire fields and emit structured citation events with protocol offsets/metadata. |
| Shared citation normalization utilities | Validate bounds, convert offsets, canonicalize safe URLs, resolve private markers, deduplicate sources/anchors, and encode/decode versioned citation segments. |
| `GenerationManager` and existing streaming segment overlay | Accept identified citation events, checkpoint the bounded segments, and preserve them through terminal settlement. |
| Provider message projection | Exclude citation segments while preserving original assistant text and all existing tool/thought protocol behavior. |
| Importers/exporters | Normalize supported external citations and preserve native citation segments. |
| Existing Markdown/message UI | Project terminal inline source capsules, the action-lifecycle Sources summary capsule and bottom sheet, detail interaction, copy text, body-search highlighting, selection fallback, and accessibility. |

These owners must not create a citation-specific generation lifecycle, Room schema, Provider
adapter hierarchy, Markdown dialect, tool execution path, or branch/history owner.

## 10. Required verification

Changes touching citations must prove:

1. OpenAI URL/file, Gemini UTF-8 grounding, and Anthropic streamed citation normalization.
2. Safe deduplication, source order, repeated-delta idempotence, malformed URL/index/text rejection,
   and the 99-source/32-anchor bounds.
3. Streaming metadata before and after text, normal completion, Stop, partial failure, checkpoint,
   terminal persistence, and reload without duplicate or lost citations.
4. Provider history and token/context projections exclude citation segments while retaining answer
   text.
5. Native export/import and supported ChatGPT/Claude import behavior, including unresolved-marker
   cleanup and ordinary Markdown-link preservation.
6. One-document Markdown projection, deterministic inline placement, unique exact-text recovery
   for content-item-relative OpenAI anchors, ambiguous-match Sources-only fallback, complete
   streaming and terminal replacement of same-source parenthesized Markdown-link wrappers, trailing
   streaming-wrapper withholding without terminal ordinary-link loss, preserved ordinary claim text,
   explicit inline-content binding at both normal and incremental Markdown roots, no visible
   object-replacement glyph, domain/file-label capsules without parenthesized domains, maximal
   adjacent-run grouping with non-ellipsized `+N`, separated-run isolation, content-measured single
   capsules with no absent-suffix reservation, exact action-control lifecycle matching, a 36 dp
   left-aligned dynamic-count summary capsule, thinking-card capsule colors, shared information-action
   alpha with 320 ms enter, 220 ms exit, `LinearEasing`, and identical interruption behavior, no
   Sources scale or measured-height host, 320 ms draw-only opacity fade for initial/reappearing/
   late-Gemini inline and source capsules, unconditional `N Sources` titles with
   thinking-sheet typography, grouped subset-sheet reuse, full-list summary-sheet preservation,
   complete ordered bottom-sheet contents, transparent rows with pill-clipped ripple, safe
   click/detail behavior, selection fallback, Copy output, and accessibility labels.
7. Global citation-title search shares the literal history path in `semantic-search.md`: bounded
   newest-first `(timestamp, id)` keyset pages select identities through the timestamp/id index before
   applying eligibility or loading citation JSON. Enough matches stop the scan immediately; only
   winners are hydrated. Query-plan tests prevent a per-page corpus sort or unindexed scan. Preserve
   title-only semantics, equal-timestamp ordering, literal query behavior and cancellation without
   loading or decoding the whole citation corpus at once. A miss may scan all short pages. The
   additive timestamp/id index is not a citation-title index and does not backfill citation payloads.
8. Complete scoped diff review, `git diff --check`, focused tests, the project full build, and
   deployment of the exact successful APK for owner UI testing.
