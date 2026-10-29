# Message Generation Architecture Contract

Status: authoritative development contract, 2026-08-13.

This document is required context for every Agora development task. It defines two global and
orthogonal message contracts. Features such as Compact consume these contracts; they must not
create parallel feature-specific definitions.

All software behavior must conform to these contracts, including normal execution, concurrency,
Room transactions, UI projection, errors, Stop/cancellation, recovery, automation, tools, queue
handoff, and legacy compatibility. Conflicting old code, tests, or documentation must be corrected;
they do not authorize a feature-local exception or parallel contract.

Implementation should reuse the existing ordinary pipeline, concepts, state owners, and objects to
the greatest practical extent. Robustness is the primary design objective: contract correctness,
identity fencing, failure atomicity, deterministic recovery, and cancellation safety outrank
cosmetic abstraction. Add an abstraction only for a cohesive invariant, a real side-effect
boundary, or multiple genuine consumers.

## 1. Terms and strict separation

A **generation boundary** identifies the visible messages produced by one generation. It is used
only to locate Regenerate scope and to decide ownership of per-generation status and bottom action
controls.

A **context boundary** identifies the oldest message included in one Provider request. It is used
only by ordinary context/API-path assembly.

The generation-boundary resolver must never select, truncate, reorder, or assemble Provider
context. The context assembler must never infer UI action ownership or Regenerate range.

## 2. Global generation-boundary contract

1. Every real USER message starts a generation boundary, regardless of neighboring message types
   or legacy Run identity.
2. All messages produced inside one generation share one nonblank `runId`. A Run is one indivisible
   generation group; no boundary may be created inside it.
3. A change to a different nonblank `runId` starts a new generation group. Protocol rows,
   Compact rows, and ordinary assistant rows all participate in this Run grouping.
4. Every actual send/generation admission creates a fresh Run ID. It must never reactivate an old
   Run. This includes the Send button, one claimed FIFO queue drain, automatic/manual Compact,
   Recompact, and Regenerate. Provider passes and tool rounds that continue the same admitted
   generation remain inside that generation's Run.
5. Blank legacy Run IDs do not authorize destructive guessing. A real USER remains a hard boundary;
   new writes must always use a nonblank fresh Run ID.

`MessageGenerationBoundaryResolver` is the shared owner of this definition. Compact, Delete,
Recompact, Regenerate, rendering, and status presentation may consume it but may not add local
boundary exceptions.

## 3. UI and Regenerate consequences

- Every real USER message always owns its bottom action controls.
- One Run group owns one generation status presentation.
- The last ordinary assistant output in a Run group owns that group's assistant action controls.
- Adjacent assistants from different Runs remain independently actionable even when an intervening
  Compact is deleted.
- Ordinary Regenerate targets only the selected generation group, creates a fresh Run, and does not
  absorb an adjacent Run.
- Same-position replacement is an output-target option, not a new generation contract. Recompact
  creates a fresh Run and replaces only the selected Compact row at its existing message ID and
  parent. It must not create a branch, clear suffix messages, rewrite descendants, or mutate any
  other message.
- Deleting a Compact deletes only that row and reparents only its direct message children to the
  deleted row's former parent. It must not merge surviving generation groups.

### 3.1 Conversation recency

`Conversation.lastUpdated` records the time of the most recent durable conversation-tree mutation
caused directly by a manual user operation. Both conditions are required: the operation must be
manual, and it must actually modify the durable conversation tree. The stored value is the mutation
time, not an earlier intent, queue, or UI-event time.

Manual Send, Edit, Regenerate, message deletion, Compact, Recompact, and Compact deletion update
conversation recency when their durable tree mutation commits. A manually queued Send does not
update recency when it enters the queue; it updates recency only when the queued input is actually
sent and committed to the tree, using that commit-time timestamp.

Branch selection does not update recency because it changes only the selected view through an
existing tree. Conversation-title edits, automatic title generation, and manual title generation do
not update recency because they do not modify the conversation tree. No automatic Compact lifecycle
step updates recency, including Compact creation, generation, settlement, handoff, or continuation.
Task and Loop tree writes never update recency, including Task `Run Now`, because the later durable
writes are automation-owned rather than direct manual tree edits.

## 4. Global context-boundary contract

Automatically sourced Task, Loop, Ask User and Subagent user messages cannot be edited. The shared
edit service rejects both source-tagged visible inputs before replacement admission and durable
source-tagged inputs at the serialized boundary, before cloning or creating a Run. UI availability
is not the mutation fence. Ordinary unmarked user inputs keep canonical edit behavior. Optional
Subagent completion display metadata remains presentation-only; full stored result text, Provider
input, completion receipts and one-shot delivery semantics do not change.

For every ordinary Provider request:

1. Start at the request's latest selected parent message.
2. Walk upward through the durable `parentId` chain.
3. The nearest Compact on that chain whose generation ended normally and without error
   (`MessageStatus.SUCCESS`) is the context boundary.
4. That successful Compact is the topmost context message. Older ancestors are excluded.
5. Failed, stopped, in-flight, missing, or off-branch Compact rows are not context boundaries.
6. If no successful Compact exists on the selected ancestor chain, context continues to the oldest
   reachable ancestor.
7. Provider projection converts the successful Compact capsule into a transient USER summary whose
   text is exactly `<context_summary>\nSUMMARY\n</context_summary>`. The marker exists only in the
   prepared API request; Room, UI projection, context usage, and retained-message projection keep
   the durable raw summary. If no real USER message follows that Compact and no existing API-only
   initial USER prompt terminates the request, shared request preparation appends `Please continue.`
   as API-only USER input. Each ordinary USER message keeps its own canonical identity, payload
   and position, including adjacent input after the Compact. Anthropic/Gemini wire adapters may
   place those ordered payloads in separate content blocks/parts of one role envelope without
   concatenating text or mutating canonical messages, Room rows or receipt ownership.
   A Compact generation may echo the request-only wrapper. Every streaming UI snapshot, Room
   checkpoint, and terminal message must normalize complete or partial wrapper markers before
   publication or persistence, including both `ChatMessage.text` and answer segment content.

`GenerationApiPathBuilder` and the ordinary Provider message projection own this contract.
Manual/automatic Compact and Recompact use this same ordinary path from their requested graph
position. `MessageGenerationBoundaryResolver` has no role here.

## 5. Shared generation lifecycle

Compact is an ordinary generation with only the declared minimal differences: message identity and
UI, haptic exclusion, selected model and generation parameters, tools disabled, Compact system
prompt, and one frozen API-only Compact invocation. It reuses ordinary admission, fresh Run
creation, context/API-path assembly, Provider execution, streaming/checkpoints, Stop/cancellation,
terminal settlement, recovery, and queue release.

Compact's frozen request parameters are separate from the ordinary conversation preferences.
Manual Compact, automatic Compact, and Recompact use their selected model in the request snapshot
and output message's model identity, but must not overwrite the conversation's ordinary model,
System Prompt, or saved generation controls. The shared admission transaction accepts an explicit
optional conversation-model update: no update retains the current stored value atomically, including
an unset value. Ordinary admissions continue to commit their selected conversation model. Neither
the UI nor a caller may copy and restore a previous model after Compact admission or settlement.

The Compact invocation is appended by the shared pre-Provider request projection as the final USER
message. It is request-only configuration: it participates in exact token accounting but is never
written to Room, rendered as a visible message, assigned a Run boundary, or used to alter durable
parentage. The configured Compact summary instructions remain the complete system prompt; the final
USER turn only invokes that behavior. A saved custom Compact prompt replaces the built-in system
prompt in full, with no hidden prefix, suffix, or mandatory guardrail added by Agora. This legacy
shape is the `Preserve System Prompt = off` mode. With Preserve on (the default), the compaction
request instead keeps the conversation's ordinary resolved system prompt, captured for the compact
model at admission, and the Compact Prompt moves to the head of the final USER message followed by
a blank line and the built-in Compact invocation. The built-in
default therefore owns its provenance, task-state, prior-summary reconciliation, language, fidelity,
and anti-recursion rules, while a custom prompt intentionally assumes responsibility for all of
those semantics. The API-only invocation is tagged as application-generated control input and must
not be represented by the built-in default as human intent, pending work, or the next action. This
guarantees a valid terminal input role even when the durable Compact parent is an Assistant message.

After a durable tool result and a successful Compact, continuation priority is:

`Compact SUCCESS -> FIFO queued user message -> loop`

The queue claim/check and loop admission must remain linearized so guidance is neither duplicated
nor lost. A non-successful or anomalous Compact is a hard automatic-handoff boundary: it starts
neither queued generation nor loop generation.

Foreground-service ownership is best-effort process-priority assistance for in-process generation,
not a Run or Provider admission prerequisite. `GenerationManager` attempts to acquire Agora's
foreground-service lease when execution is not externally managed. An unavailable or rejected
start records that no lease was acquired and generation continues through the same canonical path;
it must not create a terminal error, retry, delay, alternate execution path, or shadow lifecycle.
Completion releases the lease only when acquisition actually succeeded. Task and Loop Workers keep
using their externally managed WorkManager foreground execution. Process death does not recreate a
coroutine or Provider stream. During interactive App startup, orphaned durable Runs remain dormant:
Agora must not enumerate conversations or Runs, instantiate per-conversation runtime state, or run
recovery validation. The ordinary orphaned-Run recovery contract begins only after the user explicitly
opens a conversation. `ConversationOpenRecovery` owns that shared Native/WebUI admission boundary:
recover the selected owner first, then validate all indexed children of its proven parent family one
at a time through the same ordinary recovery transaction. Narrow parent and child-ID reads establish
scope; no child payload mass load or runtime-session creation is permitted. Recheck child membership
under its process execution lock and the same Room transaction; skip a held execution lock and any
registered delegation Job, including lazy admission and cancelled-job cleanup until manager removal.
Cancellation stops the family walk; a child failure is reported without aborting its siblings or the
selected page. Unrelated owners stay dormant. Validation never starts generation or creates/replays
completion delivery. Ordinary recovery retains `STOPPED` with `PROCESS_RECOVERED`. In the same
transaction, family validation updates only the child status cache using the payload-free exact
terminal projection and status CAS; completion JSON, IDs and delivery receipts stay unchanged. A Run
captured live before recovery and confirmed `PROCESS_RECOVERED` afterward proves a fresh interruption
and forces failed even over a synthetic old cache. Without that evidence, an unordered synthetic
admission failure is preserved; later success replaces an old failure only with proven Run ordering.

The optional Automation Wake Lock is a default-off execution-side lease, not a Run, admission,
queue, Worker, foreground-service, or recovery owner. Both Task and Loop entry paths acquire it only
around the shared `TaskExecutionEngine` execution boundary after serialized admission and release it
with structured `finally` on every success, busy/early result, failure, and cancellation. Acquisition
failure records diagnostics but does not reject, retry, or fork execution. WorkManager retains its
independent scheduled-work wake ownership; the app setting must not create a second lifecycle or keep
the device awake between executions.

Foreground Chat terminal attention is conversation-aware. A conversation is visible only while the
app is foreground, Chat owns top-level presentation, and that exact conversation is selected;
Settings, Tasks, media/PDF preview, and text preview therefore hide it, while the drawer does not.
After durable settlement, ordinary SUCCESS or ERROR marks an invisible source conversation unread
and posts its stable per-conversation notification. A visible source conversation keeps neither.
Queued/continuation SUCCESS remains an interim boundary and does not notify. Compact SUCCESS may
mark unread but never notifies; Compact ERROR marks unread and notifies; STOPPED stays silent. Error
notifications use the complete formatted generation error rather than replacing available detail
with a generic failure body. The durable read boundary runs only for a foreground, presented Chat
conversation; after its unread write succeeds, it cancels that conversation's stable notification
without affecting any other conversation. Headless Task/Loop execution retains its independent
foreground/background notification policy, but Compact SUCCESS never posts a completion notification
in either path; Compact ERROR handling is unchanged.

The continuous answering haptic texture has one side-effect owner: `AnsweringHapticEffect`. It may
run only while haptics are enabled, the app is foreground, Chat owns top-level presentation, and the
selected runtime snapshot exposes an ordinary active MODEL message in `SENDING` state with an active
Answer segment. `ConversationSelectionController` is the single binding owner for selected
conversation identity and that conversation's canonical runtime snapshot: every ID publication first
reads the target registry snapshot synchronously, publishes that bound snapshot, invalidates the old
collector, and accepts later emissions only for the still-current ID and binding generation. New Chat
publishes the neutral snapshot when it clears the selected ID. The Room-load-delayed render mirror and
an independently sampled ID/snapshot pair must never drive answering haptics. Compact, a terminal or
non-Answer message, Settings, Tasks, media/PDF preview, text preview, app background, or disabling
haptics makes the texture ineligible and must stop it immediately, including when the effect leaves
composition. The drawer remains part of Chat presentation and does not suppress the texture.
Citation metadata never ends an Answer phase; an actual tool or thought segment still does, even
when its renderer hides it. Discrete confirmation, interruption and deletion feedback may pause the
actuator but do not revoke the current Chat effect's texture request. Its existing pending resume
must be cancelled when that effect stops, so a stale callback cannot revive an inactive texture.
Generation code and overlay code must not start or stop the waveform independently or introduce a
second haptic owner, shadow state, delay, compensation, or fallback path.

## 6. Review blockers

A change is invalid if it:

- uses generation boundaries to assemble Provider context;
- uses context boundaries to merge UI generations or choose Regenerate scope;
- reuses or reactivates a terminal Run for a new send/generation;
- adds a Compact-specific boundary or generation lifecycle;
- adds a Provider descriptor, capabilities/policy object, adapter layer, wrapper, interface, or
  factory without a demonstrated cohesive invariant, real side-effect boundary, or multiple
  genuine stable consumers and without removing an existing responsibility or duplication;
- mutates suffix/neighbor messages during same-position replacement;
- merges actions or status across different Run IDs;
- treats a non-successful Compact as a context boundary;
- omits, summarizes, redacts, caps, truncates, drops, or replaces any information from the complete
  formatted generation error in a later Provider request whose selected context includes the failed
  MODEL row.

Focused tests must cover each rule, including legacy blank IDs, protocol rows, failed/stopped
Compact rows, nearest-successful-ancestor selection, fresh-Run Recompact, suffix isolation, and
concurrent queue-versus-loop admission.

## 7. Module boundaries and responsibilities

| Module | Owns | Must not own |
|---|---|---|
| `ChatMessage`, `RunEntity`, status/identity models | Durable vocabulary and small identity predicates. | UI grouping, context walks, orchestration, or side effects. |
| `MessageGenerationBoundaryResolver` | Pure visible-range grouping for one generation by real USER and Run identity. | Context assembly, graph writes, branch mutation, Provider payloads, or Compact-specific rules. |
| `RunUiProjection` | Pure action/status/branch-control presentation derived from generation boundaries. | Generation admission, context selection, or database mutation. |
| `ConversationRuntimeReducer` + `ConversationGenerationState` + mailbox | The single in-process authority for one conversation's slot, accepted effects, Stop barriers, and stale-result rejection. | Provider protocol parsing, Room transaction bodies, or feature-specific context logic. |
| `ConversationExecutionCoordinator` | Per-conversation execution serialization around admitted work. | A second Run state, queue ownership, or result acceptance. |
| `GenerationRequestBuilder` | One immutable admission snapshot: selected model, Provider registry, generation parameters, tools/memory/attachment policy. | Provider execution or live settings reads after admission. |
| `GenerationApiPathBuilder` | Read-only durable parent-chain walk, nearest-successful-Compact context boundary, Provider message projection input, and fixed Provider config. | UI generation boundaries, Run creation, queue policy, or writes. |
| `StandardGenerationContinuationLauncher` | The ordinary fresh-Run graph transaction for a continuation and the optional same-row output target. | A Compact lifecycle, Provider implementation, context reconstruction, or old-Run restart. |
| `BoundRunGenerationLauncher` | Binding one already-created fresh Run to the shared generation tail and identified callbacks. | Run graph creation, boundary inference, or terminal writes outside shared settlement. |
| `GenerationManager` | Mailbox-authorized Provider/tool execution, streaming overlay/checkpoints, generic bounded final text projection, and terminal-effect request. | Branch selection, UI grouping, Compact policy, queue reordering, or independent lifecycle state. |
| `GenerationTerminalSettlementController` and finalization executors | Identified terminal Room effects and two-barrier settlement integration. | Admission, context assembly, or action presentation. |
| `ConversationRegenerationService` | Resolve one global generation boundary, revalidate it under the normal lock, and request a fresh ordinary generation branch. | Custom grouping, suffix-wide deletion, or context construction. |
| `ConversationCompactController` | Adapt Compact model/config/system prompt/tool disablement, message identity/UI target, retained-summary text projection, and call the ordinary launcher. | A Provider runner, state machine, old-Run restart, custom context path, or custom queue lifecycle. |
| `ContextCompactor` | Threshold/retention calculations and pure Compact text formatting helpers only. | Provider transport, streaming, settlement, Run ownership, or graph mutation. |
| `ChatContextCompactDao` through the sole `ChatDao` | Atomic same-row fresh-Run substitution and target-only Compact deletion/necessary rewiring. | Context semantics, UI boundaries, Provider decisions, or broad graph reconstruction. |
| queue guidance state + `queueMutationMutex` | FIFO ownership, claim revision, exact front requeue, and linearized queue-versus-loop admission. | Holding locks during Provider/network work or attaching guidance to an old Run. |

Room remains the durable source of truth. The streaming overlay is a temporary projection of one
durable message identity and cannot become a parallel message graph.

## 8. Binding module behavior

### 8.1 Ordinary Send and queue drain

- Composer pre-acceptance work freezes an immutable draft-owner, destination, tap-ordered
  model/settings snapshot, accepted text, and ordered `READY` attachment membership before it invokes
  ordinary admission. New Chat admission inserts a workspace-store read barrier, so the snapshot
  includes all writes ordered before the tap and excludes later writes. Conditional singleton
  consumption combines those tap-time fields with the attachment states settled before acceptance;
  a surviving newer workspace row loses only the accepted draft fields. Conversation selection may
  change while that work waits, but accepted input uses only the frozen identity and never re-reads
  the visible conversation or current model to choose its destination.
- The TextField remains enabled throughout pre-acceptance and generation. Authoritative settlement is
  revision-aware: it clears only the accepted text and attachment membership, preserving any text
  typed after the tap even when that visible edit reaches the controller after freeze begins.
- One accepted Send creates one fresh Run, durable USER input, and MODEL placeholder atomically. A
  durable acceptance whose draft clear fails enters a non-resendable clear-only recovery state.
- Enabled automatic title generation starts once after the first New Chat Send is durably accepted
  and published, before ordinary reply execution. Durable-commit reconciliation preserves that
  trigger; reply failure or Stop does not suppress it. Existing-conversation sends do not retrigger.
  Automatic and manual title requests freeze only the selected branch's first USER payload before
  settings/provider waits, including available attachment text/metadata. They never substitute a
  later USER or include assistant output. Shared single-flight and conditional title writes preserve
  manual renames/deletion; title work remains independent of the ordinary Run lifecycle.
- Direct and queued Send acceptance preserve focus, IME visibility, and expanded Composer state.
  Accepted draft clearing owns no presentation dismissal. Explicit navigation, user gestures, and
  the drawer threshold retain the dismissal ownership defined in application-ui.md.
- One claimed FIFO drain enters the same Send transaction and creates a fresh Run. It clears the
  current pending batch once and persists every retained queued entry as its own ordered USER
  message, with unchanged identity, text, attachments and source metadata. Adjacent entries are
  never concatenated, including typed input, ask_user answer messages and Subagent completions.
  The complete batch starts one model request/reply cycle, not one request per queued entry;
  the last retained entry still selects the generation snapshot for that shared Run.
- Input queued while another generation owns the slot stays memory-owned until a legal boundary.
- Claim failure returns the exact batch to the front; durable success transfers ownership exactly
  once. No item may be lost, duplicated, reordered, or attached to the terminal origin Run.
- Normal completion and Stop settlement emit one shared process queue-drain signal. UI owner
  detachment/rebinding may hand that signal off, but it cannot replace it with a Stop-only callback
  or discard a pending FIFO batch.

### 8.2 Regenerate

- Locate scope only through `MessageGenerationBoundaryResolver`.
- Revalidate the visible boundary against the durable graph while serialized.
- The selected boundary's terminal ordinary assistant is the target; an adjacent different Run is
  outside scope.
- Ordinary Regenerate creates a fresh Run and branch using the boundary's real USER source, or the
  first assistant's parent when no real USER is present.
- Its visual transition scrolls to that direct generation parent. An ordinary assistant target still
  resolves to its parent, while an explicit Compact target remains anchored on the Compact itself.
  This presentation rule does not change generation boundaries, Provider context, Run parentage, or
  branch selection.
- It must not use the generation boundary to assemble Provider context. The ordinary API-path
  builder does that from the new request parent.

### 8.3 Recompact

- Recompact is ordinary same-position regeneration with a Compact output target.
- It creates a fresh Run and never reactivates the target's terminal Run.
- The target message ID and parent stay fixed. Only the target row's generation-owned fields,
  including its fresh Run identity, may change.
- No new message branch or selected-message edge is created. Every suffix/descendant message row,
  order, parent, content, status, model, and Run identity remains unchanged.
- The old independently owned Compact Run is substituted atomically in the Run graph; failure rolls
  the whole replacement back.

### 8.4 Compact deletion

- Delete exactly the selected Compact row.
- Reparent only direct message children to the deleted row's former parent.
- Preserve every other message field and never delete a suffix subtree.
- Repair only selections and dedicated Run ancestry necessary to keep surviving graphs valid.
- Surviving messages are still grouped by the global Run contract; deletion cannot merge Runs.

### 8.5 Ordinary message-branch deletion

Ordinary message-branch deletion removes one selected message and its complete structural subtree.
It is the default deletion path for every non-Compact message.

1. Collect the root message and every structural descendant by BFS through parent→child edges. The
   root is included. No message outside this subtree may be deleted, reparented, or mutated.
2. A Run may be deleted only when every message it owns belongs to the deleted subtree **and** every
   Run reachable through its parentRunId descendants is equally removable (cascade-safe). A Run that
   retains at least one surviving message stays intact; it still owns the shared boundary USER or
   other retained rows. Only the topmost cascade-safe Runs are passed to the durable transaction;
   their Run descendants are deleted transitively.
3. Message branch selection repair: for each selection edge pointing at a deleted message, resolve a
   replacement from the surviving siblings of the same participant, excluding synthetic tool/result
   rows. Prefer the immediate surviving later sibling first; fall back to the immediate surviving
   earlier sibling only when no later sibling exists. Remove the selection when neither exists.
   Selection edges whose parent is itself deleted are removed unconditionally. Message and Run
   selections must not diverge; Run selection repair uses the same next-then-previous ordering among
   Run siblings.
4. The complete plan (deleted message IDs, root Run IDs to delete, repaired message selections,
   repaired Run selections) commits in one Room `@Transaction`. The transaction deletes embeddings
   for all deleted messages, updates conversation selections and recency, deletes wholly-empty Runs,
   and finally deletes the message rows. Failure at any step rolls back the entire transaction.
5. Attachment files referenced by deleted messages are cleaned up only after the transaction commits
   successfully. A failed or rolled-back transaction must never orphan or delete files.
6. Ordinary message-branch deletion requires the conversation to be idle: no active Run may exist.
   This is verified both before acquiring the queue mutation mutex and again under the conversation
   execution lock. A generation that starts between these two checks causes the deletion to abort
   without side effects.

Ordinary message-branch deletion must not reparent children of the root (that is Compact deletion
semantics), must not merge surviving generation groups across different Runs, and must not mutate
any field of any message outside the deleted subtree.

### 8.6 Tool-result continuation priority

After a durable tool result:

1. evaluate and run automatic Compact;
2. require that exact Compact message to settle durably with `MessageStatus.SUCCESS`;
3. drain pending or already-claimed FIFO user guidance;
4. admit the no-input loop only if neither exists.

The final queue check and loop admission are one linearized decision. Compact settlement cannot
clear the queue before this decision. ERROR, STOPPED, cancellation, setup/launch failure, missing
message/status, stale identity, or any other Compact anomaly stops this automatic chain. Pending or
claimed guidance remains owned and ordered but is not automatically invoked or cleared after that
failure; only a later explicit user action may resume ordinary queue admission.

A caller with an already-scheduled ordinary continuation marks successful terminalization as
`CONTINUATION_PENDING`, including an automatic Compact that will hand off again. This never reopens
its Run or grants tool authority. Accepted reducer transitions may retain the existing Phone Use
resource epoch until the admitted successor binds; see [phone-use.md](phone-use.md) section 3.
The existing runtime resource owner retains the exact scheduling token before execution. Empty
queue drains respect that obligation, while queue-versus-continuation admission remains serialized
by `queueMutationMutex`. Guidance winning this decision transfers the cleanup obligation to the
ordinary queue; failed or abandoned continuation releases only its original unclaimed resource.

### 8.7 Compact UI

Compact may own a capsule renderer, message label/menu, haptic exclusion, and stable presentation.
Every durable Compact is an independent message and owns exactly one standalone LazyColumn item.
The canonical message-list grouping must end and emit any active ordinary USER/assistant turn before
the Compact, emit a singleton turn keyed by the Compact message ID, and leave no active turn that
could absorb a following message. Participant compatibility values, Run association,
SENDING/THINKING/TOOL_CALLING/terminal/error status, and blank summary text never permit a Compact
to merge with a preceding or following turn. This UI item boundary does not change Room identity,
generation boundaries, Provider context, or Compact rendering semantics.
Its outer minimum height/padding and 32 dp icon/action slots remain stable across
SENDING/THINKING/terminal/error transitions. The palette, leading icon, and label are keyed to one
`ContextCompactPillPresentation` transition; palette values interpolate and the leading icon
crossfades inside its fixed slot. The label uses that same transition's `AnimatedContent`: its content
fades while one `SizeTransform` animates directly between the outgoing and incoming label sizes.
No outer `animateContentSize` may wrap retained Crossfade children, and ordinary recomposition,
message-content changes, menu changes, or parent layout changes must not become a second size owner.
Reduced Motion snaps the label size while retaining the allowed opacity transition. The capsule Row
uses a 7 dp horizontal inset and 7 dp spacing between each slot so its 32 dp leading icon slot and 32
dp overflow-action touch target are visually balanced around the text. Both slots render an 18 dp
glyph; minimum height, menu behavior, and action enablement remain unchanged. UI specialization
cannot redefine generation or context contracts.

The final real-USER or standalone Compact turn is the semantic tail anchor. The physical final rendered
turn holds the remaining tail-region minimum height; these are separate responsibilities. Before
assigning that minimum, subtract the measured content height from the semantic anchor itself through
every turn before the physical holder, and clamp the remainder at zero. The holder then contributes
the larger of that remainder or its own content height, so the complete anchored region is exactly
the larger of the base viewport minimum or its actual content. Therefore
`[USER, ASSISTANT, COMPACT, ASSISTANT]` remains positioned from Compact while Compact and every later
standalone turn stay adjacent and all unused capacity appears only after the complete tail. This rule
does not merge turns or change message identity, ordering, grouping, scrolling, or search.

When the Compact detail Bottom Sheet is open and the ordinary durable message is
SENDING/answering with no real Markdown output, it shows the localized equivalent of
`Context compacting...` in the Material primary color. The placeholder enters and leaves with fade animations. Its shared
empty-stream rendering receives an 8 dp internal top inset so the status line does not crowd the
Bottom Sheet divider. As soon as real output exists, the placeholder fades out and the body renders
normally.

A terminal Compact error remains visible in both locations:

The WebUI projects the Compact detail error through the existing Kotlin message-presentation owner,
using the same `assistantErrorContent` resolver as Compose, including legacy Compact participants.
The browser renders that projected value beside its existing Markdown with the shared selectable
neutral terminal text. It does not parse errors from summary text or infer failure from empty output.
The Compact capsule's 18px error glyph is the filled Compose `Icons.Default.Error`; attachment and
media error glyphs are unaffected.

- the detail Bottom Sheet places the shared neutral-gray generation error bar beside the Markdown
  body;
- the capsule uses a theme-derived neutral-gray palette independently of the neutral terminal bar,
  without changing its bounds, and shows an error icon plus the localized equivalent of
  `Compact error`. Its container uses alpha-adjusted `surfaceVariant`, its icon uses
  `onSurfaceVariant`, and its text uses alpha-adjusted `onSurfaceVariant`; a semantic error color or
  hard-coded gray is forbidden.

A stopped Compact is a non-error terminal presentation. Its capsule keeps the same stable bounds,
shows a stopped icon plus the localized equivalent of `Compact stopped`, and emits no Snackbar. A
failed Compact may emit only the persisted ordinary generation error segment; generated answer/summary text is never
an error channel. Missing error detail uses a localized short fallback.

All app-owned Compact settings, delete/recompact actions, boundary messages, streaming/status chrome,
and known preflight/launch failure reasons must resolve through Android resources in the current
locale. Domain owners carry a semantic `CompactFailureReason`, an optional nonblank external detail,
and the affected message identity; they do not manufacture user-facing English. One narrow
presentation resolver is shared by the manual and automatic UI consumers. Nonblank Provider or
persisted error detail remains verbatim diagnostic content and is never translated. Internal
invariant/debug exceptions are not user-visible resources.

Both terminal presentations derive from the ordinary durable message status/error fields. They do
not own a Compact state machine or infer failure from missing text.

### 8.8 Shared streaming Markdown UI

The complete binding requirements are maintained in [Shared Streaming Markdown UI Contract](streaming-markdown.md).
Read that contract for every change in this scope.

### 8.9 Empty output and automatic handoff

Provider completion with no answer, thought, follow-up, guidance, or other successful output is an
ordinary generation error. Terminal persistence must include a nonblank error value so every
consumer can render the shared error bar.

Agora-owned network requests share `NetworkRetryPolicy`: one initial attempt and five retries.
Classification inspects exception types and their cause chains, with message matching only as a
fallback. EOF, premature stream termination, socket reset/abort/closed/broken pipe, timeouts,
connect/DNS failures, interrupted TLS connections and transient HTTP/2 resets retry. HTTP
408/425/429/500/502/503/504/529 retry. Authentication, certificate validation, permanent client
errors, exhausted account quota/billing and cancellation never retry, even when their message
contains a transport diagnostic. Temporary rate limits remain transient.

Every network Provider uses its existing single retry loop and emits `Retrying(n, 5)` before each
retry; only exhaustion publishes a final error. This includes ordinary chat, Subagents, Task, Loop,
Compact, titles and image transcription. Transient failures retry both before and after current-pass
Answer, Thought, Tool or hosted-tool output. Typed parse/read failures follow the same rule.
Each replay explicitly resets only the failed request attempt: restore its complete pre-request
output/citation/signature/timing/usage baseline, discard its unaccepted local calls and parser state,
and force the restored checkpoint through the existing writer before waiting. Previously completed
tool passes remain unchanged; local tools from the failed attempt never gain execution authority.
The exhausted final attempt keeps its partial output and reports one error. Direct consumers reset
only their current request/image, preserving completed images. Actual model token-cap outcomes
remain terminal unless an independent response-read failure occurred. Formatting-only output stays
held by the existing normalizer and is discarded on retry.

Backoff uses equal jitter within 0.5–1, 1–2, 2–4, 4–8 and 8–16 seconds. Retry-After seconds or HTTP
dates are a minimum delay and are never shortened. Stop cancels requests and backoff. A retry stays
on the same endpoint; known permanent HTTP failures do not trigger OpenAI endpoint fallback.
Other Agora model-list, MCP, web/search, image-download and tool/control requests use the same
budget silently, without new UI messages. Side-effectful requests replay only after a proven
pre-send connection failure or reliable deduplication; an unknown execution outcome is terminal.
Filo and Remote transport keep their separate contract and are excluded from this retry policy.

Every Compact Run success-gates all automatic handoff, not only the no-input loop and regardless of
whether the caller is foreground UI, Task, or Loop. Its ordinary launcher installs the queue-release
suppression before the generation Job can start. Durable
SUCCESS removes exactly that Run's suppression before settlement, allowing the ordinary queue
release and then the existing queue-before-loop decision. ERROR, STOPPED, cancellation, missing or
still-active status, setup failure, launch failure, stale identity, and exceptions leave the
suppression in place; settlement consumes it without starting another Provider request.

Consecutive origin-Run and Compact-Run suppressions are counted. A single boolean is invalid because
the origin release and very fast Compact completion can settle in either order and would otherwise
consume each other's decision. Failure never clears, drops, duplicates, or reorders queued user
input; it leaves that input pending for a later explicit user action.

### 8.10 Provider-hosted output and OpenAI-compatible controls

The complete binding requirements, including complete forwarding, explicit unsupported evidence,
and the owner's repair-scope clarification, are maintained in the
[Provider Output and Controls Contract](provider-output.md).
Read that contract for every change in this scope.

### 8.11 Provider reuse and mandatory minimum-abstraction rule

Official endpoints and compatible relays reuse the existing Provider implementation selected by the
wire protocol. A relay carrying Claude or Gemini models through an OpenAI-compatible wire contract
uses the OpenAI path; model branding must not select a second lifecycle or an Anthropic/Gemini
transport. Endpoint, authentication, and proven compatibility differences should remain constructor
parameters, existing configuration fields, or narrow overrides whenever those mechanisms are
sufficient.

Provider work must not create a general object model merely to make OpenAI, Anthropic, and Gemini
look structurally identical. Their request encoding, authentication, stream state machine,
signature/history replay, and terminal proof may remain direct protocol-local code. Reuse is
required at the existing generation lifecycle, semantic `StreamEvent`, message/tool projection, and
proven shared utility boundaries; wire-level uniformity is not a goal.

The following are binding review blockers:

- Do not add `ProviderDescriptor`, `ProviderCapabilities`, transport/policy/strategy objects,
  adapter layers, wrapper configs, factories, or interfaces by default. A proposed name or diagram
  is not evidence that an abstraction is needed.
- Do not move existing booleans or fields into a new data object merely to make the configuration
  appear cleaner. One owner and one consumer should normally remain a direct field, parameter, or
  protocol-local condition.
- A new object or interface is allowed only when the task record and review identify a cohesive
  invariant it owns, a real external/transactional side-effect boundary it isolates, or multiple
  genuine stable consumers. They must also state why the existing owner plus parameters is unsafe or
  insufficient and which existing responsibility or duplication will be removed.
- A refactor that only adds indirection, pass-through calls, mirrored types, mapping layers, or
  speculative extension points is invalid. Net object growth requires an explicit reduction in
  ownership ambiguity, duplicated behavior, or failure surface.
- Capability handling should stay as the smallest direct check in the owning Provider/configuration
  path until several real features need the exact same rule. Unknown relay behavior fails closed;
  that alone does not justify a capability framework.

### 8.12 Conversation share projection

Every conversation-sharing mode—whole conversation, selected visible messages, and one assistant
generation—uses one public-content formatter. The exported Markdown must omit every structured
`thought` segment, every `tool` segment and all of its names, arguments, progress, results, images,
and protocol metadata, legacy `MessageEntity.thoughts`, and synthetic tool/result protocol rows.
This is a read-only projection rule: sharing never deletes, rewrites, or weakens durable history,
Provider context, tool continuation state, or fork graph completeness.

The formatter preserves the selected visible branch and established ordering, completion checks,
conversation title, user text and attachment summaries, assistant answer and transcription content,
and error content. Inline text versus Markdown-file transport, share selection, and Android chooser
behavior remain transport/UI concerns and may not reintroduce private Thinking or tool payloads.

### 8.13 Conversation Todo tools
`TodoToolProvider` exposes `todo_read` and `todo_update` through the ordinary tool executor for an
opened conversation. One optional `conversations.todoJson` value owns the complete ordered list;
it is conversation-scoped, not branch-scoped or reconstructed from tool history. Each item has
nonblank `content` and a `pending`, `in_progress`, or `completed` status. `todo_update` replaces the
whole list and an empty array clears it. Todo does not schedule automation or create a Task/Loop.
The executor supplies the authorized `RunEffectIdentity`, never Provider arguments. A single Room
statement accepts a mutation only for the exact conversation and live ACTIVE Run/pass, active slot
and absent Stop request, and advances `dataChangedAt` without touching conversation recency, drafts,
messages or branches. Stale, stopping and terminal writes fail without mutation. Compact disables
these tools with its other tools. Only explicit selected-owner observation may load Todo; startup
and drawer queries never read or enumerate Todo payloads. Native and browser capsules are read-only
projections described in application-ui.md. Required checks cover replacement/clear, stale Run/pass,
Stop-before-write, unrelated-owner isolation, selected-only observation and archive compatibility.

### 8.13a Ask User waiting

`AskUserController` remains the exact-request waiter and answer owner. Blocking calls use one
configured timeout for the whole question set, captured in `GenerationContext` at admission.
`AskUserToolProvider` delegates that wait to the controller. The default is five minutes; Settings
provides 1, 2, 5, 10, 15, 30 and 60 minute presets. The generic tool execution deadline does not
shorten this budget. Expiry returns already submitted answers in question order and marks all
remaining questions unanswered, removes those exact requests/waiters, and ignores late answers.
It never invents approval or a selected option. The controller serializes answer claim/completion
with the expiry snapshot and exact-request removal in one bounded decision; no suspending wait
holds that lock. Stop/cancellation propagates normally and cleans
all questions in the call without delivering a tool result or queued message. Non-blocking calls
return immediately and retain their existing deferred-answer delivery without this timer. Settings
changes affect future captured contexts only; no global timer, request state owner or restart is added.

### 8.13b Built-in tool name compatibility

Built-in definitions use namespace_action protocol names: the domain comes first and the detail
after (`memory_read`, `task_create`, `phone_tap`). Every new built-in tool must follow this rule and
join an existing domain prefix when one applies; never put the verb first. `BuiltInToolNames` is the sole legacy alias
map. Execution canonicalizes the called name and frozen authorized names before permission checks
and provider dispatch. Historical Room rows stay unchanged. Immutable request projection canonicalizes
call/result names, segment names and Responses function_call.name before validation, preserving IDs,
arguments, results, signatures and opaque provider fields. Every provider, including custom request
resolvers and local templates, consumes that projection. Native tool presentation accepts both names;
external Remote tool names retain their external meaning. Internal localized resource keys are not
protocol names and remain stable.

Memory entries belong to Agora's named internal store, while file_* tools target device files.
Memory read/edit path and missing-entry errors suggest file_read with server. Bare Markdown disk reads
remain legal and receive an additive memory_read hint. Active-memory replace mode and its default,
all argument schemas and edit operations remain unchanged. Only exact unmodified built-in prompt
versions are migrated; custom prompts and stored conversation history are never renamed in place.

## 8.14 Isolated Subagents
Subagents are ordinary conversations with indexed `parentConversationId` ownership, reachable through
parent-family navigation and using the canonical generation, tools, Stop and branch pipelines.
Parent-scoped child lists use stable ascending conversation creation time and child ID, so later
messages, title edits and status changes never reorder existing children. One root
may create multiple children; a child never receives any subagent-control tools. New spawn candidates
are the configured Subagent model set intersected with globally enabled complete model IDs, without
inferred metadata. Missing, null and explicitly empty model sets all allow no new spawn; no
fallback to globally enabled models is permitted. Existing explicit sets remain intact. Candidate
IDs and a valid optional recommended ID are frozen with the parent
request. The recommendation is only a tool-description hint: model_id remains required, and an explicit
valid choice is never overridden. Spawn also rechecks current global/configured membership before
creating a child. Changes do not cancel or alter admitted child work, follow-up delivery, wait or Stop.
Child context contains only its explicit task and its own subsequent messages. Conversation-history tools are disabled in
children so they cannot read the parent's history indirectly. Other tool access and conversation
settings inherit the parent's admitted access, subject to current global permission switches.
`SubagentManager` owns launch/cancellation and completion-outbox delivery, never Run state.
Per-parent total capacity defaults to 16 (range 1–16); simultaneous child generation defaults to 4
(range 1–16, no greater than total). Neither has an unlimited value. Total counts all currently linked
children, including completed, failed and stopped children, and is checked in the same Room transaction
as parent Run/pass authorization, child creation and settings transfer. Concurrent spawn cannot exceed it.
Deleting a child frees total capacity; lowering the setting never deletes or stops existing children.
The shared execution coordinator admits child generation using durable parent ownership across initial
spawn, ordinary follow-up FIFO drain, direct/manual generation and continuations. Parent generation and
unrelated families do not consume its allowance. `agent_spawn` atomically reserves an allowance before
creating a child; at capacity it returns a tool error explaining the current count/limit and `agent_wait`,
without creating a child, settings transfer, worker or waiting request. `agent_send_message` reserves the
same allowance before preparing or queueing a follow-up: an idle child's wake-up is rejected at capacity,
while an already admitted child retains its existing allowance. The ordinary FIFO lease owns that
reservation until durable admission transfers it to generation, or dismissal/disposal releases it; failed
claims retain it with the exact requeued input. Creation/preparation failure and cancellation release
untransferred reservations, including a worker cancelled before its lazy body starts.
Existing workers retain one allowance across Compact/ordinary handoffs; nested generation reuses it.
Release follows the canonical durable terminal and installed-Job barriers. The coordinator's admitted
child IDs also drive agent_list/capsule running and the parent-list loading projection, including
pre-Run wake-up and handoff gaps. Lowering concurrency leaves admitted work intact and rejects new
tool-requested children until below the limit. Direct/manual generation and ordinary continuations keep
their cancellation-safe event-driven admission; increasing the limit wakes those existing waiters.
No second input queue, polling worker, startup scan or automatic process-death restart is introduced.
Terminal completion payloads keep generated `text` separate from `errorText` and optional `errorCode`
read from the exact terminal message's persisted error segment. Engine failures carry that terminal
message ID even after the streaming overlay is cleared; completion projection never substitutes a
chronological message or partial answer for it. Failed completions apply the same
error-text formatter and localized missing-detail fallback as the child timeline's gray terminal
text, so hiding diagnostics in the receipt bubble never hides them from the parent model. Generated
partial answers must not supply that fallback. agent_list demand-loads available
diagnostics only for its failed owned children; ordinary capsule observation stays payload-free.
agent_wait exposes `error` and
`error_code`; automatic completion queue messages include the same detail without replacing the
answer or changing receipt identity. Legacy error-only text remains readable; partial answers alone
must never be classified as error detail. conversation_read exposes each selected-branch message's
message_id, status, run_id and run_sequence alongside text/time, and the same structured error detail
when present. This does not authorize unrelated-owner reads, receipt replay or status inference from prose.
Compact settlement is not a Subagent completion: it creates no completion outbox or parent-queue
input, and successful Compact emits no system completion notification. While a delegation worker
owns the task, intermediate ordinary Run settlements also do not publish task results; the worker
publishes the final task outcome once after its engine call settles. Standalone ordinary child
completion still uses the committed terminal outbox, but standalone Compact does not. Successful
Runs yielding to queued guidance or an ordinary continuation persist `CONTINUATION_PENDING` as their
terminal reason. They remain closed Runs but cannot become Subagent completion results, even when
their output is empty or partial and the delegation worker has already retired. Only the final
non-continuation outcome may publish the task result.
`agent_spawn`, `agent_send_message`, `agent_wait`, `agent_list`, `agent_stop` and `agent_delete`
require an exact live parent Run/pass. `agent_delete` exists only when the frozen context also has
`subagentDeleteEnabled` (global default off). It never stops work: a child that is running, has a
live Run, generation slot or queued messages, or holds an undelivered completion is rejected with
an explicit error. Accepted deletion uses `ConversationLifecycleController`'s idle-Subagent path
under the child's conversation lock with ownership rechecked at commit; every client still showing
the child receives the localized parent-deletion notice and settles to the surviving parent.
`agent_send_message` accepts follow-ups for idle or busy children through the
child's existing visible FIFO message queue and returns an explicit queued item receipt. Every
model-originated spawn prompt and follow-up carries the existing `subagent` message source; manually
typed child input carries no automatic source. This source distinction survives each separate FIFO
message, claim/rollback, durable graph admission and API projection, and does not create a completion
receipt for an instruction. It never
interrupts or injects into an active Provider/tool pass. Idle acceptance requests the ordinary drain;
busy acceptance waits for the canonical Run boundary. Queue removal/send-now, separate FIFO messages,
fresh-Run batch admission, failure rollback and Stop/Compact ownership follow the ordinary queue rules. Child tool isolation
and the parent's admitted permission restrictions apply to the queued generation, not just direct
launch. No manager-owned second queue, polling worker, direct-send fallback or startup restart is
introduced. Stop cancels actual work and waits for ordinary terminal settlement. A wait
that returns a result acknowledges that exact completion through its committed ordinary tool result,
so it cannot also be automatically delivered. Otherwise completed results enter the parent's existing
visible FIFO message queue as ordinary input tagged `subagent`, not a hidden direct engine invocation.
They share the queue's pending/claimed ownership, remove/send-now controls, single-request fresh-Run
batch drain, rollback and Stop/Compact handoff rules. Typed input, ask_user answer messages and
Subagent results remain separate FIFO messages with their original source metadata and exact receipts.
All completion receipts in the admitted batch commit atomically with its messages and fresh Run.
Completion delivery requests the ordinary queue drain after releasing the queue mutex only when it
has added at least one new result and the parent is idle. Busy parents wait for canonical settlement;
completion delivery never interrupts active work. Repeated events or reopening with results already
pending or claimed do not request another drain. The ordinary drain remains the sole admission owner,
including FIFO batch transfer, fresh-Run creation and Stop/Compact handoff permission.
Repeated delivery events cannot duplicate pending or claimed entries; removing a queued result must
not silently reinsert it. Failures before admission preserve recoverable ownership; failures after
admission never resend that input. No parallel queue, direct-generation fallback or delayed retry is
introduced. There are no startup owner scans or automatic process-death restarts. Opening a parent may
offer its still-pending outbox to the same queue; imported pending results are treated as delivered to
avoid unsolicited execution.
Parent deletion cancels child execution and Loops before atomically deleting the owned graph and
scheduling attachment cleanup. Fork creates an independent conversation, not another linked child.
Required tests cover stale identity, one-level isolation, concurrent completion/wait, Stop settlement,
process death/receipt atomicity, parent deletion, unrelated-owner preservation, archive roundtrip,
Compact success suppression, and exactly one final result after Compact/ordinary Run handoffs.

## 9. Context assembly contract in module terms

Global `todoEnabled` and `subagentToolsEnabled` preferences default on and are frozen with other
permission switches when a generation context is captured. Request preparation and context-display
projection may narrow delegation access for children but must never turn a disabled permission on.
Compact narrows both to false. Disabling these tools does not delete lists, cancel admitted children,
remove existing navigation or suppress ordinary completion delivery.

`GenerationApiPathBuilder` receives one immutable durable snapshot and a requested parent ID. It
walks only that parent chain, stops at the first successful Compact encountered upward, expands
protocol side chains without duplication, and projects Room entities once. The shared immediate
pre-Provider projection then applies image/user-template transforms and appends an optional frozen
API-only initial USER prompt. For Compact that prompt is mandatory and therefore the final request
item is USER even when the durable parent is Assistant; ordinary requests without such a configured
prompt remain unchanged.

The API-only USER prompt is counted as fixed request cost so threshold and rollout accounting cannot
omit bytes that dispatch sends. It is appended only to the initial Provider request and is not part
of retained-message calculation, Room history, generation-boundary grouping, or later tool rounds.

A Compact preflight may call this read-only ordinary builder to calculate retained-summary text.
That projection is not authoritative input for execution and cannot replace or suppress the
ordinary Provider request rebuild inside the shared generation tail.

A branch-selection change, missing parent, or corrupt chain must fail closed or produce only the
reachable safe prefix. It must never jump to a Compact on another branch.

### 9.1 Immutable materialization and single rollout ownership

The Room read side may optimize payload materialization, but it may not select a different context.
One immutable read transaction captures the selected-branch state, payload-free message topology,
and every full row reachable from the requested parent or selected visible path. The transaction
walks the same durable parent chain, applies the nearest-SUCCESS-Compact boundary regardless of
summary text content, and expands the same run-matched tool/result side chains in established order.
Once those rows are captured and validated, pure tool-round materialization and Provider/status
formatting run outside the transaction to keep unrelated durable mutations from waiting on CPU work.
Only full payload rows outside that canonical path may be omitted. There is no pre-rollout,
overscan, payload-size product limit, topology-retry approximation, or alternate Compact boundary.

Conversation UI payload residency is a separate projection optimization. Payload-free topology owns
durable ordering and structural fields. The one active generation row is overlaid and rendered
directly from one atomic current render snapshot; it must not be observed through a remembered
single-value Flow, historical row hydration, or a payload cache. Composed historical rows may observe
and hydrate their full payload by stable message identity. JSON decoding and display projection occur
off the main thread. A bounded LRU may retain completed display projections, but it is never
authoritative state, never bridges a terminal transition, and never changes topology, edit identity,
or Provider-visible materialization. The top-right current-conversation search derives eligible
ordinary USER and MODEL IDs from the complete payload-free selected path, reads those payloads in
fixed 64-ID pages, restores selected-path order within each page, and retains only lightweight match
ranges. Its one matching surface is display-projected message body text: Tool/result/Compact rows,
Thinking and Tool segments, citation/source metadata, and attachment metadata never enter candidates
or counts. Canonical result order is selected-path root-to-leaf, then source range ascending within
each message. Each independently rendered Timeline Answer slice retains the global match identity
for only the source ranges inside that slice. When results first arrive, the exact visible occurrence
nearest the usable message-viewport center becomes active; Up selects the adjacent visual occurrence
above, Down selects the adjacent visual occurrence below, and neither direction wraps at an end.
`MessageList` remains the only scroll owner: it receives the canonical rendered turn order, accepts
exact glyph geometry only for the active measurement epoch, and centers that exact occurrence in
LazyColumn-local coordinates between the top bar and composer through its single progressive seek.
Search result recall is independent of LazyColumn composition and payload-cache residency;
jumping to a match continues through the existing stable message identity and per-row hydration path.
Semantic-search reads keep their separate bounded payload projection. LazyColumn eviction or
rehydration may change object lifetime only; it must not change content, generation state, or
glyph-birth metadata.

An open Thinking-segment Bottom Sheet is owned above the LazyColumn and stores only durable message
identity plus segment-selection mode. It never stores a copied row payload, observes the payload LRU,
or depends on the source item remaining composed. Its authoritative payload order is the current
generation snapshot, the atomic render-store payload retained for streaming-to-terminal handoff, then
a direct Room observation by message ID. Scrolling, item disposal, payload eviction, or future chunk
offload therefore cannot close, freeze, clear, or delay an already-open sheet. Group/list-first mode
recomputes its complete current Thought/Tool/Transcription index set from every authoritative snapshot,
so segments created after opening appear immediately without resetting the sheet's list/detail page.
A direct single-segment sheet keeps its selected stable detail index and dismisses only when the
message or selected authoritative segment is actually removed.

After Room projection, Provider preparation remains the only rollout authority. No DAO, loader,
Compact controller, UI projector, transcription stage, or automation caller may remove an older
eligible row because of a token estimate before that shared boundary. This optimization therefore
changes database materialization and object lifetime only; it does not change Provider-visible
ordering, attachment projection, protocol validation, context selection, or failure semantics.

### 9.2 Ordinary-generation system prompt ownership
Gemini serializes the compiled system prompt under the canonical REST JSON field
`systemInstruction`, shared by ordinary chat and dedicated internal generations. It must emit
only that field spelling, never both protobuf and JSON aliases; absent prompts omit the field.

For ordinary conversation generation, the complete Provider-visible system prompt is owned by the
user-selected structured System Prompt template. The request builder may compile that template and
resolve only the predefined variables that the user explicitly placed in it. It must not append,
prepend, wrap, or otherwise inject Active Memory, the Skill catalog, runtime metadata, tool guidance,
application instructions, or any other hidden text outside the template.
Access settings grant capabilities; they do not grant prompt-injection authority. Enabling Active
Memory or Skill access may make the corresponding predefined variable resolvable and may expose the
authorized tools, but content enters the ordinary system prompt only where the user placed that
variable. Removing a variable from the template must remove that content from the dispatched system
prompt without a construction-layer fallback.
The shipped default begins with one custom text item containing Response, Tools, Memory, Skills,
Past conversations, Web, Devices, Subagents and automation, and Asking the user in that order.
These sections are static, including when their tools are disabled or Compact preserves the ordinary
system prompt. A normal editable HTML preview guidance text item follows that first item, as specified
by [interactive-html.md](interactive-html.md). The explicit Skill catalog block follows, then Active
Memory with their existing variables and surrounding text. Tool availability never trims these
sections or injects them into custom templates. Custom text and whitespace remain verbatim.
`GenerationPromptRendering` compiles request and preview values without changing stored items.
`GenerationContextAccounting` uses the dispatch tool-definition source for fixed cost/composition
and Compact accounting. Preview compilation occurs once in its captured snapshot; it does not need
another tool-dependent rendering pass. The request resolver uses one compiled prompt instance for
rollout cost and wire output. Startup recognizes the exact default that used conditional guide
variables plus all four older known defaults. Any changed title, system text or User/Assistant
wrapper prevents replacement. Retired predefined guide items in customized templates are retained
as removable unknown variables and compile to empty in both previews and requests. They are absent
from the picker and example values. Literal custom text is never stripped. Historical factories
remain exact and independent of future default text changes.

Every predefined variable in the structured System Prompt is late-bound for each actual outbound
Provider API request. The stored template contains only ordered text and variable identities. Editing,
saving, conversation creation, context preview, queue admission, Run/message graph admission, and
construction of an immutable generation snapshot must not persist or freeze a resolved value for a
later request.
Immediately before each initial request, tool-continuation request, and transport retry is serialized,
the request path must read the variable's current authorized value and compile the complete system
prompt for that request. A later request in the same Run may therefore resolve different values.
Exception for prompt-cache stability: `{active_memory}` and `{skill_catalog}` are read once when the
Run's prompt template is captured and reused unchanged for every request of that Run (initial,
tool-continuation and retry). Edits or access changes made during the Run apply from the next Run.
The stored template still never persists a resolved value.
`{current_model_id}` resolves once per dispatch from the model selected for that request. The legacy
`{model_id}` name remains a read-only compatibility alias for the same value. `{message_model_id}` is
message-scoped: ordinary User and Assistant wrappers resolve it independently from each durable
message's model identity, using an empty value when none exists. It is not a request-wide substitute.
Editor previews may use explicit example values only for presentation and must never persist them as
resolved prompt content.
Startup migrations preserve user-edited System Prompt content and all message wrappers. Only a
complete match to a known unmodified built-in template permits replacement with a newer default;
the presence of a legacy runtime tag alone never authorizes replacing a stored template. Legacy
wrapper storage may be normalized without changing its resolved content.
Context rollout and token accounting for a dispatched request must consume the exact late-bound system
prompt instance that the transport serializes. They must not estimate from an earlier resolution and
then dispatch a newly resolved prompt. Provider adapters receive the compiled prompt and must not
resolve variables, restore omitted content, or append their own system text.
This contract applies only to ordinary conversation generation. Dedicated internal generations,
including Context Compact and title generation, continue to use their own explicitly configured
special-purpose prompts and are outside this subsection.

Embedded Local Low Context Mode is the sole ordinary-generation exception to the structured Prompt
projection above. Its effective value is the nullable conversation/New Chat override when present,
otherwise the device-local Local Provider default. It applies only when the admitted Provider is the
embedded `Local` Provider; Ollama, custom Providers, and every remote Provider ignore the value.
When effective, admission and context projection treat the current structured System Prompt as
completely empty: they do not capture, compile, or resolve its template, predefined variables, Active
Memory, Skill catalog, or User/Assistant prepend/postpend items. The frozen ordinary request has no
system prompt, no prompt resolver, no ordinary tool definitions, and no Provider-native search or
code-execution tool declaration. Fixed-cost/context estimation must use that exact empty-prompt,
empty-tools request shape. The underlying settings and selected Prompt remain stored, so disabling
the mode restores normal projection. Compact and other dedicated internal generations keep their
own prompts and declared tool policy.

### 9.3 Canonical history and soft token window

The shared Provider preparation order is deterministic:

1. remove non-successful Compact rows from Provider history and project the nearest successful
   Compact as the established USER summary boundary;
2. deduplicate durable IDs, project terminal generation status on the same assistant turn, validate
   tool protocol fail-closed, remove empty normal turns, and merge consecutive ordinary assistant
   rows while preserving every distinct ordinary USER message boundary;
3. treat one tool request and all consecutive result rows as one indivisible protocol unit while
   every ordinary canonical message is one unit;
4. scan complete units newest-to-oldest until the estimated message budget is reached, retaining at
   least the newest complete unit and one normal USER anchor even when that legal suffix exceeds the
   estimate;
5. start the dispatched history at its first normal USER and never keep an older ordinary row while
   dropping a later ordinary row on that selected branch.

The configured context value is a soft estimated request budget, not a hard byte limit.
The shared default is `262,144` tokens (`256K`, using 1,024 tokens per K). It applies when no valid
context budget is stored; explicit global/conversation values and legacy normalization remain
authoritative. This default does not increase an embedded Local model's configured `nCtx`.

Fixed request cost consists of the system prompt, complete enabled tool schemas, and the optional
API-only initial USER prompt. MCP uses the three fixed entries in [mcp-tools.md](mcp-tools.md);
directory data and discovered schemas are history result cost. Fixed cost is subtracted exactly once before
history rollout. The initial prompt is excluded from retained-history selection and appended
exactly once afterward as the final USER request item. It is never merged into Room history,
persisted, rendered, or treated as a durable retention anchor.

### 9.4 Complete conservative token accounting

Token accounting runs only after the same Provider-visible projection used by dispatch. It includes
all projected ordinary text, attachment file text, stored image transcription, user templates,
terminal annotations, tool names, arguments, results, signatures, tool-call reasoning content,
opaque continuation JSON, system prompts, complete app tool schemas, enabled Provider-native tool
descriptors, and the optional API-only initial USER prompt. Every Provider-visible image path is counted, including each image attachment, PDF
page, video frame, assistant-generated image projected to the latest USER, and tool-result image
projected to its synthetic USER turn.

Exact tokenization and visual-token pricing vary by selected model and custom Provider. Image
processing, preview/dispatch identity and dimension-based estimation follow
[image-processing.md](image-processing.md). Every image is estimated from its own transmitted
artifact dimensions and actual request detail/resolution mode using the most accurate verified
model rule available; unknown rules remain explicitly heuristic. The former fixed per-image rule
is superseded. The estimator must not omit a Provider-visible category. Display-only citations,
tool progress, presentation metadata and attachment metadata that is not serialized are excluded.

The Chat top-bar token subtitle and Bottom Bar context indicator report the same full selected canonical
context estimate plus fixed request cost; neither surface replaces that number with the already-retained
Provider window or with a sum of historical message usage. The top-bar subtitle is absent when no
canonical usage is available. When visible, it shows `~used / budget tokens`, reusing the Bottom Bar's
localized context-usage resource and `ContextBudget.compactLabel` for both numbers. The subtitle retains
the existing title measurement, clipping, and motion ownership; it never calculates context itself.
The rollout projection maps the shared canonical window back to one
contiguous eligible durable suffix on the selected branch, including complete protocol units.
Automatic Compact eligibility and retained verbatim text consume the complete selected canonical path,
not an already-rolled Provider suffix.

The UI projection reloads whenever the visible conversation ID or exact durable
`selectedBranchesJson`, selected model, normalized context budget, durable message projection, or
request-configuration invalidation input changes. The existing projector publishes one
identity-fenced state: loading may carry only the previous canonical usage to prevent a transient zero
presentation, but it has no retained IDs; a completed success may contain a valid empty retained set;
and a completed failure has neither usage nor retained IDs. A superseded request may never publish
over a newer identity.

Rollout may consume a projection only when it is completed, not loading, not failed, and its
conversation ID and `selectedBranchesJson` exactly match the visible conversation. Branch-switch
and deletion covers wait up to eight seconds for matching completion, then continue through the
existing graph/layout settlement even if accounting is still pending. Timeout releases only this
wait and does not cancel canonical accounting or authorize rollout. Matching failure is completion
for cover release only: rollout stays disabled and presentation remains neutral; failure or loading
must never be interpreted as an empty all-rolled-out context. Explicit invalidation changes the UI
request revision even when Room has already emitted an equal graph, so a discarded in-flight result
is always followed by a fresh projection request.

Context rollout visualization is presentation-only. A MODEL message in `SENDING`, `THINKING`,
`TOOL_CALLING`, or `TRANSCRIBING` is generation-in-progress and must remain at normal opacity even
when its durable ID is not yet present in the retained-history projection. After that message reaches
a terminal status, the existing canonical retained-history projection determines its rollout
presentation. Only a message already classified as rolled out receives the legacy whole-message
`Modifier.alpha(0.38f)` presentation, so its complete rendered subtree dims together. This visual
rule never inserts the in-progress row into Provider history, token accounting, Compact input, or
retained-message calculation.

## 10. Concurrency and failure-safety principles

1. **Single process authority.** Only the conversation mailbox/reducer accepts lifecycle
   transitions. Controllers execute accepted effects; they do not maintain shadow state.
2. **One durable live Run.** The Room active-slot constraint and transactional preconditions are
   mandatory. A fresh Run is inserted only when no other live Run exists.
3. **Fresh identity per admission.** Run IDs are generated before the durable transaction and never
   reused to restart terminal work.
4. **Identity fencing.** Asynchronous Provider, tool, checkpoint, Stop, and finalization results are
   accepted only for the expected conversation, owner token, Run, pass, and effect ID.
5. **Durable-before-external.** Provider execution begins only after the Run/message graph commits
   and the process state binds that exact Run.
6. **Terminal-before-handoff.** Compact, queue, or loop continuation starts only after the origin
   Run's legal terminal boundary. Checkpoint writers close before terminal persistence. A worker
   awaiting settlement tracks its exact generation owner through both durable and installed-Job
   barriers; a successor's claimed slot must not extend that wait while it needs the worker's lock.
7. **Short lock scope.** Queue mutexes and graph/selection locks protect only decisions and
   transactions; never hold them across Provider streams, tool execution, or UI waits.
8. **Revalidation.** UI-derived targets, parent links, terminal status, Run ownership, and selected
   edges are re-read inside the serialized/transactional boundary.
9. **Atomic replacement.** Same-row fresh-Run substitution either updates the target and Run graph
   completely or changes nothing. Non-target message rows are immutable inputs.
10. **Cancellation robustness.** Cancellation cannot strand a SENDING row, lose a claimed queue
    lease, reopen a terminal Run, or bypass both coroutine and durable settlement barriers. A Stop
    persistence failure keeps the slot occupied; only after that exact failure is recorded may a
    later Stop reissue the same finalization effect identity. Concurrent duplicates and stale
    identities remain rejected. If cancellation or failure is delivered at a suspending Run-graph
    commit boundary, the owner must re-read the exact proposed Run before treating the transaction
    as uncommitted or allowing the process slot to release.
11. **Bounded persistence.** Final transforms may change only declared presentation text and the
    shared persistence guard is reapplied afterward. For an aggregate whose trimmable JSON-string
    payload already proves it exceeds the byte budget, that guard measures escaped UTF-8 payload
    bytes, derives fixed metadata/JSON overhead from a placeholder projection, performs all
    largest-field-first reductions in memory, and encodes the bounded aggregate once. It must not
    repeatedly serialize an unbounded checkpoint aggregate. The exact encoded UTF-8 bound remains
    authoritative, and protected Provider continuation state fails explicitly rather than becoming
    SQL NULL. Once a tool round commits, the root timeline stores only its `toolRoundId` and
    `toolCallId` reference in that exact position, not another copy of the tool body. The live overlay
    may keep the display body, but every checkpoint and terminal/Stop write uses the same reference
    projection. References are marked only after the identified round transaction succeeds; an
    uncommitted call retains its complete checkpoint/rollback behavior. Selected-message hydration
    reads only the referenced immutable protocol rows, verifies conversation/Run/call ownership, and
    restores bodies in reference order without importing opaque continuation into display state.
    Provider history keeps using ordinary protocol rows exactly once. Missing/ambiguous bodies and
    oversized reference metadata fail explicitly, never silently discard history. Legacy inline
    roots remain readable. Fork remaps references with message identity; native archives preserve
    both references and protocol rows without materializing a full root aggregate.
12. **Fail closed.** Missing/cyclic parents, shared legacy Runs that cannot be substituted safely,
    selection drift, stale identity, and partial transaction results reject the operation rather
    than guessing or broadening mutation scope.

## 11. Abstraction and growth principles

- Prefer an existing ordinary owner over a new feature layer. Configuration data and target
  parameters are preferred to another controller/state machine.
- Keep a rule pure when it is pure. Boundary and presentation policies should be deterministic
  functions with focused tests.
- Extract a module only when it owns a cohesive invariant, a real external/transactional side
  effect boundary, or multiple genuine consumers. Do not create pass-through wrappers, type
  aliases, one-call factories, or speculative interfaces.
- Conversely, do not let a simple owner grow into unrelated responsibilities. If a file starts
  mixing admission, context, Provider execution, persistence, and UI policy, split along the
  ownership table above rather than by arbitrary line count.
- Durable fields such as `parentId`, `runId`, status, and selected edges drive generic policy.
  Message prefixes may identify presentation/protocol types but must not create parallel lifecycle
  semantics.
- Compatibility handling belongs at the narrow read/transaction boundary and must not pollute the
  normal path. New writes obey current contracts; unsafe legacy states fail closed.
- Comments explain ownership and invariants, not a second algorithm. Tests assert observable
  contracts, not source spelling.
- No architecture claim is complete until focused concurrency/failure tests, both flavor unit
  suites, the project source-size/architecture gates, and the required build succeed.

## 12. Required verification ownership

| Contract | Minimum focused proof |
|---|---|
| Generation grouping | Real USER hard boundaries; same-Run protocol/assistant rows remain one group; every Run transition separates groups; blank legacy IDs are safe. |
| Action/status projection | Every real USER has actions; only each Run group's terminal ordinary assistant has assistant actions/status; different Runs remain separate. |
| Fresh-Run admission | Send, queue drain, Compact, Recompact, and Regenerate never restart a terminal Run. |
| Context boundary | Parent-chain nearest successful Compact wins; closer ERROR/STOPPED/SENDING Compact is ignored; off-branch Compact is unreachable. |
| Recompact isolation | Same message ID/parent, fresh Run, unchanged selections and byte-for-byte unchanged non-target message rows/suffix. |
| Delete isolation | Target-only delete, direct-child reparent, unchanged surviving rows, independent Run presentation. |
| Priority | Only Compact SUCCESS permits handoff; then pending and already-claimed queue guidance beat loop and the no-guidance path admits loop once. ERROR/STOPPED/cancellation/anomaly starts neither. |
| Request terminal role | Compact dispatch appends one non-durable initial USER invocation after an Assistant or tool-result parent; provider-visible input ends USER and fixed token accounting includes it. |
| Provider-hosted output | OpenAI-compatible Chat requests serialize applicable numeric and model-specific thinking controls; Responses requests serialize the five normalized `service_tier` values after mapping the stored tier to the actual model's supported choices and serialize enabled `web_search` plus reasoning summaries; mandatory-thinking models resolve off to their lowest legal setting before HTTP; summary indices preserve part boundaries and headings supply titles; OpenAI Search and Gemini Google Search/Code Execution settle display-only tool blocks without local execution; Gemini Code Execution replays typed parts and fails closed when a result is missing. |
| Races and failures | Stop before/after bind, consecutive origin/Compact release suppressions in both settlement orders, selection drift, missing target/status, transaction rollback, stale callbacks, checkpoint-versus-terminal ordering, and queue claim failure. |
| UI stability | Compact row/pill vertical bounds do not change across progress and terminal content; entrance is draw-only and does not alter apparent vertical spacing; message and Thinking Tool terminal text reuse the shared neutral body-text tokens and alpha without Segment error cards. |
