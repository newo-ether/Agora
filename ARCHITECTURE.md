# Agora Architecture

This document describes the current repository architecture. It intentionally avoids
line counts and exhaustive file inventories because those became stale faster than the
contracts they were meant to explain.

Normative development behavior is indexed by `.harness/PROJECTS.md`. Before changing Agora, read
`development/README.md` and every applicable module contract; for message generation, Run
boundaries, Provider context, Compact, and Regenerate, `development/message-generation.md` is the
authoritative contract when older descriptive prose conflicts.

## 1. System shape

Agora is a single-module Android application built with Kotlin, Jetpack Compose,
coroutines, Room, DataStore, WorkManager, OkHttp, and a small native layer.

```text
Compose UI
   │
   ▼
ChatViewModel ── MessageGenerationController ── ConversationStateRegistry
   │                         │
   │                         ▼
   │                 GenerationManager
   │                    │         │
   │                    │         └── ToolProvider implementations
   │                    ▼
   │               LlmProvider implementations
   │
   ├── ConversationRepository ── Room (v22)
   ├── SettingsRepository ───── DataStore
   └── MemoryManager / attachment files / export and backup files
```

AppContainer owns process-scoped dependencies. Foreground-open automation bridges into the
controller and joins the exact generation Job. Headless automation enters the same direct-only
conversation mailbox and accepted-input graph transaction, while `TaskExecutionEngine` remains a
bounded adapter for headless request building, Provider-effect execution, and the external
body of mailbox-approved Compact effects.

The project uses manual dependency injection through `di/AppContainer.kt`. Shared
providers, repositories, automation coordinators, and the local inference engine are
created once per process. A foreground `ChatViewModel` and headless automation reuse
those instances instead of building competing stacks.

## 2. Source layout

| Path | Responsibility |
|---|---|
| `app/src/main/java/.../ui` | Compose screens, message rendering, settings, tasks, media |
| `app/src/main/java/.../viewmodel` | Conversation state, message generation, branching, import/export |
| `app/src/main/java/.../api` | Provider protocol adapters and streaming event model |
| `app/src/main/java/.../tool` | Memory, RAG, web, shell, image, and automation tools |
| `app/src/main/java/.../data` | Room, DataStore, backup, import/export, attachment ownership |
| `app/src/main/java/.../mcp` | MCP transports, protocol client, server registry, and tool discovery |
| `app/src/main/java/.../automation` | Tasks, loops, scheduling, execution serialization |
| `app/src/main/java/.../service` | Foreground generation and WorkManager entry points |
| `app/src/main/java/.../sandbox` | Shared sandbox interfaces |
| `app/src/fdroid` | PRoot-backed sandbox implementation |
| `app/src/play` | Play flavor implementation without bundled PRoot binaries |
| `app/src/main/cpp` | llama.cpp chat/embedding JNI and PRoot bridge |
| `thirdparty` | Vendored/submodule native dependencies |
| `build-logic` | Android bytecode compatibility transform and repository source-size policy |
| `server` | Optional Agora-related server components; not part of the APK runtime |
| `docs` | User-facing MkDocs documentation |

There are two store flavors, `fdroid` and `play`. The application currently targets
Android API 36, supports API 24 and newer, and builds arm64 native artifacts.

## 3. State ownership

### 3.1 Conversation state

`ChatViewModel` is the UI-facing coordinator, but generation state is no longer a
single global mutable slot:

- `ConversationStateRegistry` owns one `ConversationGenerationState` per conversation.
- `MessageGenerationController` accepts sends, edits, regenerations, stops, and queue
  transitions.
- `ConversationGenerationMirror` projects only the selected conversation's live state
  into UI flows.
- `ConversationExecutionCoordinator` serializes the main foreground/background generation lease
  per conversation while allowing different conversations to proceed independently.
- UI/persistence tokens and Run/pass guards reject some stale callbacks.

The pure reducer is the single authority for in-process Run state. The controller, generation
manager, Stop finalizer, task engine, and Room transactions execute identified effect bodies but
cannot release or retarget a Run without returning the exact result command. A few graph-building
and guidance-storage adapters remain outside the pure core; their bounded ownership is recorded in
`development/baselines/conversation-runtime-refactor-baseline.md`.

Room remains the durable source of truth. The live message is an overlay for the
currently selected branch; it does not become a second durable message graph.

### 3.2 Message tree and branches

Messages form a tree through `parentId`. A conversation stores selected child choices
so the UI can derive one visible path. Tool request/result rows are synthetic graph
nodes and are hidden as standalone chat bubbles.

Branch operations must preserve these invariants:

1. A selected path includes the complete synthetic tool/result closure for each turn,
   including parallel result siblings.
2. Forks copy file-backed attachments into fork-owned storage.
3. Deletion removes a file only after Room confirms that no message or draft still
   references it.
4. Orphan cleanup covers normal message storage and `fork-attachments`.

`ConversationBranchPath`, `ConversationForkShareService`, and
`MessageAttachmentCloneSession` implement these rules.

### 3.3 Orchestration source ownership

The runtime decomposition keeps one directional control path while separating effect bodies:

| Surface | Ownership |
|---|---|
| `ConversationCommandMailbox` | Bounded, sequential command admission for one conversation. |
| `ConversationRuntimeReducer` | Pure transition and effect authority; owns no Android, Room, Provider, Tool, or UI dependency. |
| `ConversationGenerationState` | Applies accepted transitions and owns the per-conversation runtime resources and read-only projections. |
| `GenerationManager` | Routes mailbox-authorized Provider, tool, checkpoint, finalization, and notification effects; returns identified result commands. |
| `MessageGenerationController` | Adapts external Send, Stop, queue, edit, regenerate, Compact, and lifecycle requests to the mailbox/effect contract. |
| `ChatViewModel` | Stable Compose facade over feature controllers and immutable UI projections; it does not decide Run transitions. |

Extracted controllers and executors receive explicit inputs and own only their named side effect or
UI projection. They do not hold a writable `RunState`, release a conversation slot, or call the
reducer directly. `ConversationStateRegistry` owns the process lifetime and deterministic disposal
of the per-conversation runtime state.

## 4. Generation pipeline

`GenerationRequestBuilder` prepares provider configuration, context, tools, memory,
attachments, and optional transcription. `ProviderMessageProjector` is the lossless Room-to-
provider projection for durable text, attachments, and synthetic tool rows; both generation and
Compact consume it. `ConversationContextProjector` uses that same projection plus fixed system/tool
costs for the UI token estimate and roll-out boundary, so the indicator does not maintain a second
message-count model. A Provider owns retry and semantic termination for one stream.
`ProviderPassRunner` collects exactly one such stream and closes it as an identity-bearing
`CompletedText`, `CompletedToolCalls`, `Truncated`, `Failed`, or `Cancelled` outcome. Before network
execution, the mailbox must emit the exact `StartProviderPass` effect; the closed outcome returns as
`ProviderPassCompleted`, and only the reducer's exact `ProviderPassAccepted` effect may be consumed.
Live events may update the streaming overlay, but only an accepted completed-tool outcome can send
`ToolBatchRequested` into the same mailbox. The reducer emits the identified
`ExecuteToolBatch`, `CommitToolRound`, and (only after durable success) `ContinueProviderPass`
effects. The runner adds fail-closed tool metadata validation; it does not replace or weaken
Provider-specific termination validation and retry. `GenerationManager` executes those effect
bodies. Normal Run completion enters `Finalizing`; a mailbox-emitted `FinalizeRun` is executed with
bounded retry and its exact Room result returns as `FinalizationCompleted`, while
`GenerationFinalizer` owns durable Stop finalization and its retry path.

The migrated conversation-runtime slice is authoritative for ordinary foreground/headless Send,
memory-guidance placement, the in-process generation slot, Provider-pass acceptance, normal/Stop
finalization barriers, tool-round gates, and slot release. Pure `ConversationCommand`, `RunState`,
`RunEffect`, and `ConversationRuntimeReducer` types decide
`Idle`/`Recovering`/`Preparing`/`Active`/`Finalizing`/`Stopping`, coroutine settlement, durable
settlement, and continuation. Compact does not own a parallel runtime state: it enters the same
ordinary fresh-Run continuation, Provider, streaming, Stop, settlement, recovery, and queue path.
`ConversationGenerationState` retains the legacy per-conversation monitor, tokens, Job, streams,
and UI flows as an adapter, but its former `SlotPhase` and Stop-barrier Booleans no longer exist.
Every Stop-finalization result echoes conversation, Run, pass, owner, and effect identity; stale
and duplicate results are rejected before either slot or overlay mutation.

Normal finalization and Job completion are independent barriers, just like Stop finalization. A
bound durable Run never becomes Idle from `CoroutineSettled` alone. Either barrier may arrive first;
only the command observing both emits `ReleaseSlot`. If the terminal Room transaction exhausts its
bounded retries, `Finalizing` remains occupied. A later explicit Stop may then take over that failed
finalization and use the STOPPED transaction to restore a consistent durable terminal state.

Each process-scoped conversation state now also owns a bounded 64-entry sequential command
mailbox.
Foreground and headless ordinary Send enter as `SendRequested`: `Idle` becomes `Preparing` and
emit one identified
`PersistAcceptedInput`; the Room transaction returns the exact `InputPersisted` command before the
Run becomes `Active`, while a failed transaction returns `InputPersistenceFailed` and retains
ownership until its coroutine settles. Cancellation before the controller receives the direct
claim emits an exact `SendLaunchAbandoned` command, so an unstarted Send cannot strand the slot.
Another Send while the current Send is preparing or active is accepted by the reducer as
memory-only guidance. At the next legal tool/final/Stop boundary, the origin Run is terminal before
one explicit FIFO guidance lease enters the same normal
`SendRequested` → `PersistAcceptedInput` → `InputPersisted` contract with a fresh Run id. One Room
transaction creates that Run, every guidance item as a distinct USER row, one MODEL placeholder,
and the matching selections. The lease transfers attachment ownership to Room only after that
transaction commits; a pre-commit failure returns the exact batch to the front, while runtime
disposal deletes only files that never became durable. The removed same-Run append/pass-claim
transactions no longer exist.

User Stop enters the same mailbox as `StopRequested`. The accepted transition first revokes the
old UI/persistence tokens, marks the overlay STOPPED, and retains `Stopping` ownership; only then
are that conversation's stream handles and generation Job cancelled. Only the installed Job's
completion hook may report `CoroutineSettled`; reaching a coroutine `finally` is not sufficient.
Job completion re-enters as `CoroutineSettled`, and the identified `GenerationFinalizer` Room
result re-enters as `PersistenceSettled`. Either order is valid, but only the command that observes
both barriers emits `ReleaseSlot`. These handoffs run on the conversation-owned scope and are
non-cancellable once
accepted, so caller/lifecycle cancellation cannot drop a terminal result. Conversation deletion is
separate runtime disposal and does not fabricate a durable user-Stop effect.

If Stop wins while an accepted-input or replacement Room transaction is still committing, the
late exact Run identity is adopted by the existing `Stopping` state. That transition emits one
identified `FinalizeStop`; the coroutine and persistence barriers still both have to settle. The
normal commit race therefore cannot bypass the mailbox or attach work to the stopped Run.

Manual, automatic, and Recompact generation are ordinary fresh-Run continuations. Compact freezes
only its declared model/generation parameters, disables tools and haptics, supplies the Compact
system prompt and message presentation, and then delegates durable graph admission, context/API
path construction, Provider execution, streaming/checkpoints, Stop, settlement, recovery, and
queue release to the ordinary generation owners. Recompact uses the same pipeline from the
target's existing parent and atomically moves only that Compact row to a fresh Run; suffix message
rows remain unchanged.

After a durable tool result, continuation ownership is `Compact -> FIFO queue -> loop`. The queue
pending/claimed check and no-input loop admission are linearized so guidance cannot be duplicated
or lost at Compact settlement. Each admitted operation gets a fresh Run ID; Provider passes and
tool rounds inside that admitted generation retain the same Run. Direct-only automation receives
busy and creates no input.

Startup recovery now uses the same pure runtime contract: Room reads ordered `ACTIVE`/`STOPPING`
snapshots, reduces each through `Recover`, executes the exact `RecoverDurableRun` transaction, and
echoes `RecoveryCompleted`. It never reconstructs a coroutine. The transaction stops in-flight
model/tool UI state and terminalizes that exact live Run as `STOPPED/PROCESS_RECOVERED`; any failed
effect/result assertion rolls the whole recovery transaction back and keeps generation gated for
retry. The superseded broad live-Run id/update queries were removed.

Headless request building/Provider execution remains a bounded Task effect adapter after mailbox admission.
Guidance storage/lease execution remains a bounded controller adapter, but it has no
alternative Run-placement or durable append authority: every drain is a fresh mailbox-approved
normal Send. State-backed foreground and headless tool execution/commit authorization uses the
mailbox. A bounded 256-entry runtime
trace records only sequence, conversation-id digest, Run/pass/effect identity, state/command/effect
types, and timestamp.

```text
accepted send
   │
   ├── optional TRANSCRIBING
   ▼
SENDING
   │
   ├── text delta ───────────────► answer segment
   ├── thought delta ────────────► thought segment / THINKING
   ├── tool-call delta ──────────► one live tool segment / TOOL_CALLING
   │                                  │
   │                                  └── completed request queued
   └── provider stream boundary
                                      │
                                      ▼
                         mailbox-authorized tool batch
                                       │
                                       ├── streamed progress/output
                                       └── authoritative final result
                                       │
                         atomic Room protocol-round commit
                                       │
                         mailbox-authorized provider continuation

terminal: SUCCESS | STOPPED | ERROR
```

The visible message status values are:

`TRANSCRIBING`, `SENDING`, `THINKING`, `TOOL_CALLING`, `SUCCESS`, `STOPPED`, and
`ERROR`.

### 4.1 Streaming contract

All provider adapters normalize their wire protocol into `StreamEvent`:

- `TextChunk`
- `ThoughtChunk`
- `ToolCallUpdate`
- `ToolCallRequest` / `ToolCallsRequest`
- `UsageUpdate`
- `Retrying`
- `Error`

`ToolCallUpdate` contains the accumulated name and arguments known at that point and a
stable `streamKey`. The first delta immediately creates the existing
`Calling tool…` segment. Later deltas update that same segment. There is no separate
pre-tool status.

OpenAI-compatible text responses are also inspected incrementally when tools were
offered. Tagged `<tool_call>` payloads and supported bare JSON tool calls are diverted
into the same streaming tool-call path instead of flashing as answer text or being
lost at end-of-stream.

A completed tool request is executed only after the current provider stream reaches its validated
boundary and the conversation reducer accepts its full effect identity. The complete result batch
must then be accepted before Room can commit the assistant request plus every result, and the next
Provider request requires the matching successful commit result. This keeps parsing, execution,
durability, and continuation as separate owners and prevents a terminal chunk, `[DONE]`, EOF,
duplicate result, or parallel tool call from racing the collector.

### 4.2 Tool execution contract

`ToolProvider.executeEvents()` emits:

- `TargetResolved` for the concrete execution target;
- `Progress` or `OutputDelta` for bounded user-visible streaming output;
- exactly one `Completed` value as the model-facing result.

One-shot tools inherit an adapter that emits only `Completed`. Tool segment lifecycle
uses the existing states:

```text
CALLING → RUNNING → SUCCEEDED | EMPTY | FAILED | STOPPED | BACKGROUND_RUNNING
```

The UI is updated at most every 50 ms for ordinary stream and tool content, with first and terminal
changes emitted immediately. Durable checkpoints are best-effort and cannot cancel a healthy
provider stream.

### 4.3 Stop and terminal ownership

Stopping cancels the active Run and preserves queued guidance. The in-process slot enters
`STOPPING` and remains occupied until both the generation coroutine has unwound and the durable
Stop transaction has succeeded. Either barrier may complete first; only the second releases the
slot. Guidance then enters a fresh Run rather than attaching to the terminal Run. Pending UI
animation work cannot replay after `STOPPED`.

## 5. Rendering and interaction

Compose receives immutable `ChatMessage` snapshots. Streaming markdown uses a
latest-wins two-buffer renderer:

- the current snapshot stays fully visible;
- one incoming snapshot is prepared offscreen;
- updates arriving during the 90 ms fade replace a single pending snapshot;
- promotion and alpha reset happen atomically;
- alpha animates in the graphics layer, so markdown subtrees are not recomposed every
  animation frame;
- a terminal update settles immediately, avoiding a delayed flash after Stop.

The streaming message is rendered once. There is no second tail renderer competing
with the Room-backed list.

Haptics follow interaction meaning:

- direct taps and long presses use discrete feedback where appropriate;
- conversation selection and target-load completion remain separate events;
- generation retains a quiet, low-duty continuous feedback pattern;
- ordinary stream ticks, tool state transitions, and terminal cleanup do not stack
  duplicate pulses on top of it.

## 6. Providers and tools

`LlmProvider` implementations cover OpenAI-compatible endpoints, Anthropic, Gemini,
Ollama, and on-device llama.cpp. Provider-specific parsing stays inside each adapter;
generation code consumes only normalized events. `ProviderPassRunner` is the common one-request
closure boundary: its result carries conversation, owner, Run, durable pass, and effect identity,
and malformed or duplicate completed tool metadata cannot authorize execution.

Tool providers are capability-oriented:

- memory file operations;
- conversation search/RAG;
- web search and fetch;
- remote shell and file operations, including durable Conch jobs;
- discovered MCP server tools;
- image generation;
- persistent Task and conversation Loop automation controls.

Provider signatures are opaque protocol state. A segment records the originating
provider, and signatures must never be replayed into another provider protocol.

## 7. Persistence

Room database version 22 contains six entities:

- `conversations`;
- `runs`;
- `messages`;
- `embeddings`;
- `tasks`;
- `loops`.

Room stores Run ancestry/status/pass state, the conversation graph, selected message and Run
branches, durable streaming checkpoints, automation state, and embedding metadata. The unique
`(conversationId, activeSlot)` index prevents two durable live Runs for one conversation.
`appendToolRoundToRun` is one protocol-atomic transaction: it accepts only the current ACTIVE slot
and expected pass, validates a one-to-one ordered request/result batch, and treats only an exact
complete replay of the same message ids as idempotent. Partial or conflicting replay fails closed.
Migrations are explicit and schema snapshots are committed under `app/schemas`; v16→v17
introduced Runs, and the current chain continues through v22.

The local persistence declarations are split by responsibility without creating competing DAOs:

- `ChatEntities.kt` contains Room entities, converters, query projections, and pure tool-round
  validation;
- `ChatDao.kt` is the sole `@Dao`, extends the two stateless declaration surfaces below, and owns
  common graph/Run/embedding/export declarations plus cross-domain transactions;
- `ChatContextCompactDao.kt` owns only atomic same-row fresh-Run Recompact substitution, target-only
  Compact deletion, necessary graph rewiring, and their narrow declarations;
- `ChatAutomationDao.kt` owns the inherited Task and Loop row declarations;
- `ChatDatabase.kt` is only the v22 Room composition root and migration chain.

DataStore holds user settings, provider/model configuration, API-key references, appearance,
generation defaults, tool toggles, backup settings, and per-conversation overrides. `SecretCrypto`
normally wraps secrets with an Android Keystore AES-256-GCM key, but legacy plaintext is accepted
and encryption failure deliberately falls back to plaintext to avoid data loss; security/privacy
documentation must not describe the envelope as an unconditional invariant.

The filesystem holds processed attachments, fork-owned attachment copies, memory
Markdown files, local models, sandbox files, imports, exports, and backups. File
deletion is intentionally reference-aware because older imports or forks can contain
aliased paths.

Automatic title generation uses compare-and-set against the title observed when work
started. A manual rename or a newer generator always wins.

## 8. Automation

WorkManager and alarms are scheduling entry points. Both Task and Loop acquire resources in the
same order: `AutomationExecutionGate` first, then the automation-priority
`ConversationExecutionCoordinator` lease. A foreground-open Loop delegates to the controller
through a direct-only call and joins its exact Job. Headless Tasks/Loops submit the same
`SendRequested` -> `PersistAcceptedInput` -> `InputPersisted` contract and share the ordinary
USER/MODEL/Run graph writer. Busy is a typed no-input/no-Run outcome; no bridge queues or falls back
to a second writer. Automatic Compact uses the same ordinary fresh-Run continuation and generation
tail in foreground and headless execution. `TaskExecutionEngine` still adapts headless request
construction and Provider-effect execution; Provider outcome acceptance and normal finalization use
the shared mailbox contract.

One-shot schedules preserve explicit past dates so validation can reject them. The
scheduler must not silently reinterpret an expired date as next year.

## 9. Native, sandbox, and remote shell

The native layer exposes:

- on-device chat generation;
- local embeddings;
- the F-Droid PRoot bridge.

The F-Droid sandbox runs commands with concurrent output collection and an actual
wall-clock timeout. The shared glob matcher is implemented without API-26-only
`java.nio.file` APIs so the API 24 minimum remains real.

Remote shell supports Conch HTTP jobs and direct SSH with host-key trust. Conch requests use signed ephemeral-key/AES-GCM application-layer protection when an API key is configured; blank-key servers receive plain JSON and rely on HTTPS for transport confidentiality. Commands start as durable jobs: foreground execution is a bounded wait
on the same process, and a timeout returns its job id instead of killing or replaying it. Separate
list/get/wait/stop/ack operations manage surviving jobs. Terminal jobs are acknowledged only after the
exact tool result is committed to Room; server retention remains the cleanup fallback when an
acknowledgement cannot be delivered. Android-compatible Base64 APIs are used on every supported
SDK.

## 10. Data portability and recovery

`.agora` export/import supports selective categories, including automation configurations (Daemon, SMS, Heartbeat, and Notifications). Third-party importers support
ChatGPT and Claude exports. Auto backup uses WorkManager and configurable retention.

Recovery rules:

- non-terminal messages are checkpointed during generation;
- startup recovery deterministically maps ordered Room snapshots to identified reducer effects and
  repairs interrupted Runs atomically without inventing successful output or resuming a coroutine;
- attachment cleanup occurs only after database ownership changes commit;
- fork cloning rolls back newly created files if graph insertion fails.

## 11. Build and regression gates

Use the repository scripts from the project root:

```powershell
.\build.ps1
```

`build.ps1` is the required release gate because it configures the Android SDK and
runs the repository's expected unit-test/build workflow. Deployment is a separate, explicitly
authorized operation and is not part of the architecture or validation gate.

Every handwritten Kotlin source in main, test, flavor, and build-logic source sets is limited to
999 physical lines by `verifyKotlinFileSize`. The task is wired into Gradle `check`, Android
`preBuild`, `build.ps1`, and CI. Generated/build output, caches, and the vendored `thirdparty` tree
are excluded; the temporary migration baseline is empty. The exact counting and baseline rules are
documented in `development/kotlin-source-size-policy.md`.

High-risk changes require focused tests in addition to the full gate:

- provider termination and incremental tool-call parsing;
- generation ownership, queueing, stopping, and checkpointing;
- reducer legality, both Stop-barrier orders, and stale/duplicate effect rejection;
- latest-wins UI buffer behavior;
- branch path closure and attachment cloning/deletion;
- task schedule boundary behavior;
- API-24-compatible glob and process timeout behavior.

## 12. Architectural invariants

The following are review blockers:

1. Never create a second visible status for tool-call assembly; stream into the existing
   `Calling tool…` segment.
2. Never execute an incomplete tool call or discard a completed one merely because the
   provider used `stop`, `[DONE]`, or EOF.
3. Never allow two writers to finalize the same conversation/run identity.
4. Never render both a live tail and its Room counterpart.
5. Never restart an in-flight UI transition for every token; newest content wins.
6. Never block stream collection on attachment preprocessing or synchronous process
   output reads when avoidable.
7. Never delete attachment paths without querying remaining Room references.
8. Never overwrite a manual title with delayed automatic title generation.
9. Never use an Android API above `minSdk` without a guard or compatible implementation.
10. Never put a raw private origin address into client code or committed configuration.
11. Never treat Provider-pass completion as Run completion when a tool continuation remains;
    queued guidance closes the origin only at its legal boundary and continues through a fresh Run.
12. Never release a stopped Run before both coroutine and durable finalization barriers settle.
13. Never attach queued guidance to a terminal Run or persist it before a legal boundary.
14. Never accept an asynchronous result for an unexpected Run/pass/effect identity.
15. Never delete original messages during Context Compact or split a complete tool round.
