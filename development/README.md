# Agora Development Contracts

Status: authoritative mandatory development entry, 2026-08-13.

Every Agora development task must read this document before planning or editing. It then must read
each module contract whose code, data, UI, persistence, automation, or behavior is in scope. These
documents are executable product/architecture contracts, not optional background material.

## 1. Authority and update rule

Current explicit user requirements have the highest product authority. The applicable documents
under this folder persist those requirements across tasks. Production code, tests, older
architecture prose, and historical behavior must align with them.

When a user changes a contract:

1. update the applicable module contract before or together with implementation;
2. map the requirement to one owning module and focused verification;
3. update conflicting code, tests, and older documentation;
4. do not preserve the conflict through a feature-local exception or a parallel contract.

Runtime source remains evidence of current implementation, but current implementation does not
override an explicit contract.

## 2. Mandatory core contracts

These generation and Room invariants govern Agora-owned conversations. Externally owned
Remote sessions follow [remote-sessions.md](remote-sessions.md), as explicitly requested
by the owner; they reuse the presentation while leaving execution and durable truth in Codex.

- Reuse the ordinary pipeline, state owners, durable transactions, concepts, and objects to the
  maximum practical extent.
- All software behavior must match its core contracts in normal, concurrent, cancelled, failed,
  stopped, recovered, automated, and legacy-compatible paths.
- Room is durable truth. A streaming overlay or UI projection is not a second graph or state owner.
- One conversation has one process lifecycle authority and at most one durable live Run.
- Every newly admitted send/generation receives a fresh identity. Terminal work is never reopened.
- Asynchronous results are accepted only with exact conversation/owner/Run/pass/effect identity.
- Checks and writes that establish one invariant belong in one atomic transaction or one serialized
  decision boundary.
- Pure policies stay pure. UI grouping, context assembly, state transition, Provider execution, and
  persistence are separate responsibilities.
- Fail closed on stale identity, graph drift, unsafe legacy state, missing/cyclic ancestry, partial
  transaction results, and ambiguous ownership.
- Protect user data first: never lose, duplicate, reorder, broaden-delete, or silently overwrite
  messages, queue entries, attachments, branches, or terminal output.

## 3. Concurrency and robustness invariants

1. The conversation mailbox/reducer is the single in-process transition authority.
2. The Room active-slot constraint and transaction predicates are the durable concurrency fence.
3. External Provider/tool work starts only after the exact durable Run binds successfully.
4. Stop and natural completion obey their defined coroutine and persistence barriers.
5. Checkpoint work closes before terminal persistence so an old snapshot cannot resurrect streaming.
6. Queue ownership is explicit; claim failure returns the exact batch and successful commit
   transfers it once.
7. Locks protect only bounded decisions/transactions and are not held across network, Provider,
   tool, UI, or long-running suspension.
8. UI-derived graph targets and selections are re-read at the serialized/transactional boundary.
9. Replacement/deletion transactions modify only their declared target and structurally necessary
   metadata.
10. Cancellation, process death, retries, duplicate callbacks, and out-of-order completion must
    converge to a deterministic durable state.

The design goal is to minimize failure surface and failure modes: one execution path, one state
authority, narrow mutation capabilities, short lock scopes, fresh identities, atomic graph
changes, bounded persistence, and explicit stale-result rejection.

## 4. Global lazy loading and load-performance invariants

Interactive application entry loads only the minimum conversation-list projection required to render
the list. Before the user explicitly opens a conversation, application startup must not load complete
conversation rows, message graphs, Composer drafts, attachment JSON, branch or Run state, generated
media, or any other conversation-owned payload. It must not enumerate conversation owners to decide
whether validation or recovery work exists, validate files or durable references, create per-owner
runtime or Composer sessions, or resume conversation-owned jobs. After that list is visibly available,
the sole automatic data-maintenance exception is the semantic-index ledger check defined in
[semantic-search.md](semantic-search.md): it reads only one lightweight row for the active Embedding
model and may admit one bounded low-priority cache worker. It never authorizes a startup message,
conversation, embedding, attachment, Run, or owner scan.

Explicitly opening one conversation is the admission boundary for its state. That action may load,
deserialize, validate, and recover the selected owner. It also admits ordinary validation of all indexed
child conversations in the selected parent family; a selected child must prove its durable parent
membership before widening that scope. Native and WebUI share one open-recovery boundary. Enumerate
only child IDs, validate one child at a time, and skip active in-process execution or registered
delegation Jobs until their lifecycle owner removes them.
Opening conversation A must not read, validate, restore, cache, or schedule work for unrelated owners.
Unopened families remain dormant after process death. Work already admitted for an opened family may
continue in the same process after navigation, but this never authorizes a global scan or restart.

Load performance is a primary architecture, implementation, and review constraint. Startup and page
entry queries must use narrow projections, avoid unused large columns, and keep database, JSON, file,
CPU, bitmap, memory, and coroutine work demand-driven and bounded. Shared locks must not make the
selected owner's page load wait behind unrelated owners. Verification must prove that startup performs
no conversation validation or recovery, selected-owner work has priority, unrelated owners remain
untouched, and worst-case recovery concurrency and retained state are bounded. Compilation and small
functional tests alone are not load-performance evidence.

## 5. Abstraction and growth principles

- Do not create a new concept, state machine, controller, interface, wrapper, factory, or data
  object when an existing owner plus a parameter can express the behavior safely.
- Extract only for a cohesive invariant, a real side-effect/transaction boundary, or multiple
  genuine consumers.
- This is a mandatory review gate: every proposed abstraction must name which of those three
  conditions it satisfies, why the existing owner plus parameters is insufficient, and which
  existing responsibility or duplication the new abstraction removes. Missing evidence blocks the
  change.
- "Reuse" does not authorize speculative `Descriptor`, `Capabilities`, `Policy`, `Strategy`,
  `Adapter`, configuration-wrapper, or pass-through objects. Do not introduce a data object merely
  to rename, regroup, or shuttle fields already owned safely by an existing object.
- Prefer a direct protocol-local branch or one additional parameter when it is clearer and has one
  owner. Generalize only after real shared behavior exists; do not build an object model for
  hypothetical Providers, transports, models, or future consumers.
- Do not allow a simple controller/manager to accumulate unrelated admission, context, Provider,
  persistence, and UI responsibilities. Split along ownership boundaries, not arbitrary style.
- Prefer generic rules driven by durable fields over feature names, message prefixes, or UI
  location. Type prefixes may select rendering/protocol decoding, not a second lifecycle.
- Compatibility logic stays narrow and read-side/transactional. New writes always obey current
  contracts.
- Comments state ownership and invariants. Tests assert behavior, race/failure outcomes, and
  non-mutation guarantees rather than source spelling.
- Simpler architecture means fewer authorities and paths, not fewer safety checks.

## 6. Universal prohibited behaviors

Never:

- create a parallel generation, queue, Stop, settlement, context, or branch pipeline for a feature;
- infer Provider context from UI generation grouping, or infer Regenerate scope from context
  truncation;
- generate on a terminal/old Run ID;
- accept a stale or partially identified asynchronous result;
- hold a shared lock while awaiting external or long-running work;
- mutate suffix/neighbor messages as an implementation shortcut;
- turn a partial database update into a successful result;
- add a feature-specific merge/boundary exception when a global durable rule exists;
- weaken validators or tests to make an implementation pass;
- claim runtime/UI correctness from compilation alone;
- invent, accept, or refine an unconstrained autogenerated UI merely to produce
  an implementation or satisfy a checklist; every material UI decision must be
  governed by an applicable UI contract, an owner-approved page specification,
  or an established owner-approved reference, and missing authority is a blocker.

## 7. Module contract registry

| Scope | Required module contract |
|---|---|
| Interactive template library, user-defined shortcuts and local opening | [interactive-templates.md](interactive-templates.md) |
| Android sharing boundary and legacy Notes attachment preservation | [notes-integration.md](notes-integration.md) |
| Pi PC sessions, learning interactions and Remote figure transport | [pi-remote.md](pi-remote.md) |
| Interactive answer tools, saved artifacts and sandboxed Android viewer | [interactive-answers.md](interactive-answers.md) |
| External agent devices, existing sessions, independent Remote transport and shared chat presentation | [remote-sessions.md](remote-sessions.md) |
| Conch/Filo encrypted network requests and client read limits | [encrypted-agent-transport.md](encrypted-agent-transport.md) |
| Message generation, Run lifecycle, queue, tools, Compact, Regenerate, message actions/status, or Provider context | [message-generation.md](message-generation.md) |
| Shared streaming Markdown, timeline/detail rendering, selection, media loading, and generation motion | [streaming-markdown.md](streaming-markdown.md) |
| Provider-hosted output, OpenAI-compatible request controls, thinking, service tier, and hosted tools | [provider-output.md](provider-output.md) |
| Embedded llama.cpp FIFO admission, Chat/Embedding residency, identity switching, Stop, or idle offload | [local-model-runtime.md](local-model-runtime.md) |
| Provider structured citations, citation persistence, marker cleanup, answer/source projection, citation copy/search/import/export, or citation accessibility | [citations.md](citations.md) |
| Embedding-cache reads, semantic conversation search, RAG ranking, or search eligibility | [semantic-search.md](semantic-search.md) |
| Generic Web Search providers/settings/tool execution or native provider-hosted web search | [web-search.md](web-search.md) |
| Persistent Skill files, Skill catalog prompt projection, Skill tools/settings, or Skill archive transport | [skills.md](skills.md) |
| Application-level onboarding motion, settings category copy, or other non-message global UI behavior | [application-ui.md](application-ui.md) |
| Native `.agora` archive categories, settings portability, import strategies, secrets, or backup compatibility | [import-export.md](import-export.md) |
| Incoming Android text shares, excerpt provenance and display-only source thumbnails | [shared-text-sharing.md](shared-text-sharing.md) |
| Android composer dictation, speech services and transcription providers | [voice-typing.md](voice-typing.md) |
| Shared Settings page structure, interaction, copy, localization, or documentation entry points | [settings-ui-ux.md](settings-ui-ux.md) |

Add a module document when a user defines durable behavior for another subsystem. Each module
document must describe current code ownership, allowed and forbidden responsibilities, concrete
behavior/state/data flow, concurrency and transaction boundaries, failure behavior, and required
verification. Keep one authority per contract; do not duplicate normative text across modules.

Documents under [`baselines/`](baselines/) are explicitly historical and non-authoritative. They
preserve migration or audit evidence only and never override the module contracts in this registry.

## 8. Development completion gate

The 2026-09-11 owner requirement prohibits TypeScript in Agora, Filo and Conch.
Agora remains Kotlin; Filo moves fully to Go with no Node.js runtime dependency.
The current delivery includes merging Remote into the original Agora branch after
qualification, preserving its existing commits and unrelated uncommitted changes.

The owner requires a sustainable Filo and Agora baseline (2026-09-10). Split maintained
source, tests, resources and contracts by responsibility before files approach 999 lines;
target 700–800 lines or less. Never compress statements or remove useful formatting to
evade this limit. Preserve established behavior throughout structural cleanup. Baseline
equivalence checks and the full regression suite are mandatory after implementation;
every discovered regression blocks delivery until repaired or the change is reverted.
Compiler/cache success, artifact creation, deployment and owner phone UI acceptance
remain separate evidence. Keep the active goal open until its authorized work is complete.

Before completion:

1. re-read this document and every applicable module contract;
2. review the complete task diff against every touched invariant and prohibited behavior;
3. run focused success, race, cancellation, stale-result, rollback, and non-mutation tests;
4. run the project-defined full build gate after the final code change;
5. separate build/deploy evidence from unverified device UI behavior;
6. update module documentation whenever the accepted behavior or ownership changed.
