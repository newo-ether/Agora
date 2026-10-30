# Semantic Search Architecture Contract

Status: authoritative development contract, updated 2026-10-29.

This document is required context for changes to embedding-cache reads, semantic conversation search,
RAG ranking, or the search eligibility query. Semantic search must remain bounded by one database
page plus the requested top candidates; corpus growth must not translate into Android heap growth.

## 1. Observable behavior

- Query embedding generation, model selection, API-key resolution, and user-visible tool behavior
  remain owned by the existing RAG/provider path.
- Semantic history ranking excludes Task and child conversations, non-USER/non-MODEL rows,
  empty or below-minimum source text, and synthetic tool/result/Compact rows. Source length follows
  SQLite `LENGTH(TEXT)` character counting, including its first-NUL boundary.
- Cache admission and counts have a separate eligibility rule: any nonempty USER/MODEL source in a
  non-Task conversation is eligible, including short text, whitespace, embedded NUL and children.
  Synthetic tool/result/Compact identities remain excluded. Empty legacy message IDs remain valid
  source identities. History visibility or its minimum length must not narrow cache eligibility.
- Literal search, expanded search context and conversation_read also exclude synthetic Compact
  summaries. Branch traversal may cross these rows but must not return their bodies as messages.
- Global history tools and drawer search exclude child conversations through root-only database
  eligibility, before count, pagination, literal/citation search and semantic ranking. A child must
  never displace a visible root in a limited page or top-K result. Final semantic hydration rechecks
  root eligibility. Existing child content and embeddings remain stored; this read boundary does
  not change cache admission or delete data.
- `conversation_read` admits a child only when its durable `parentConversationId` equals the calling
  generation's conversation ID. Missing caller identity, unrelated roots, child self-reads and sibling
  reads cannot authorize that child. Unauthorized and missing IDs return the same `not_found` result
  before loading topology or message bodies. Ordinary top-level history reads remain available.
  Parent-owned child discovery continues through `agent_list`, never `conversation_list`.
- Every `conversation_list` item includes a JSON boolean `is_generating`. Each call captures one
  current snapshot from the existing generation registry plus active automation ownership, matching
  the native drawer indicator. Idle, completed, failed and stopped owners return false once their
  canonical execution ownership settles. Stored orphaned Runs do not imply live generation. This
  projection must not open conversations, instantiate runtime states or load message bodies, and
  active children remain excluded from the list.
- Compact summaries are also ineligible for cache admission, reconciliation, embedding generation
  and transactional embedding commit. Existing Compact cache rows are excluded from counts/ranking
  and reclaimed by the canonical bounded maintenance path, never by a search or startup scan.
- Similarity is cosine similarity. Candidates must be strictly above the configured RAG threshold,
  ordered by descending score, and limited to the requested count.
- Stable embedding row id is the deterministic tie-breaker for equal scores.
- Invalid dimensions, malformed byte lengths, and non-finite scores are skipped without aborting
  the remaining corpus and without logging message content.

### History search previews and read details

`conversation_search` accepts query and limit and returns ordinary message text with stable message
IDs, timestamps and identity/status fields. It does not expose or interpret `show_tool_calls`,
`show_thinking`, `tool_offset` or `tool_limit`. Former detail arguments are ignored; search does not
decode stored segments or hydrate referenced tool rounds. Its formatted preview remains bounded to
five windows and three messages per window, including when displaying older saved search results
that contain detail fields. Search matching, ranking and cache admission remain unchanged.

Only `conversation_read` accepts `show_tool_calls` and `show_thinking`, both false by default.
It projects stored readable content inside the ordinary message object; it never adds synthetic
tool/result/Compact rows to the message list. Search windows and read pages include ordinary
empty-text assistant checkpoints, so thinking-only or tool-only replies keep their existing positions.
Use search to locate a conversation and message, then read to request its stored details.

`conversation_read` counts ordinary selected-branch messages for offset, limit, total and next_offset.
`after_message_id` overrides offset and continues after that identity on the currently selected
branch. The returned `next_after_message_id` is the last returned ordinary message, even at the
current end so later appends can be followed. Removing earlier messages cannot shift this anchor;
a deleted or unselected anchor returns `cursor_not_found` instead of guessing. This is not a frozen
snapshot: a branch change after a still-shared anchor follows the new selected suffix. Missing rows
between topology and hydration return `history_changed` without advancing. Cancellation propagates.

Tool details have an independent per-message `tool_offset` and `tool_limit` (default 20, maximum
100). `tool_calls_total`, `tool_calls_has_more` and `tool_calls_next_offset` make continuation explicit.
To continue one message, read the same message page with limit 1 and advance only tool_offset.
Only referenced rounds for that tool page are hydrated. The existing reference resolver verifies
conversation/Run/call ownership and committed status; missing, foreign or ambiguous bodies fail
explicitly. Legacy inline segments remain readable. Tool call identity, arguments, result, state,
readable metadata and round commit timestamps are returned without signatures, opaque continuation
items or private image file bodies. Round timestamps are labeled `round_commit`, not injection time.
Thinking uses ordered readable thought segments, falling back to the legacy thoughts field without
duplicating both. Malformed legacy segments report `details_error` on the ordinary message.

`ConversationHistoryProjection.kt` owns the read-only allowlist and tool-detail page hydration for
`conversation_read`. It does not own search projection, branch selection, eligibility or mutation.
Focused tests cover the read-only detail schema, search ignoring former detail arguments without
round hydration, flag-independent read membership, bounded round hydration, legacy data, ownership
failure, cursor deletion/branch changes, cancellation, and visible formatted read details.
Formatted read results retain readable structured results and image transcriptions without exact
duplicate bodies, expose stored-detail parse failures, and identify tool_offset separately from
message pagination when more tool calls remain.

## 2. Bounded data flow

1. ChatDao reads a minimal projection containing only embedding row id, message id, embedding
   bytes, and declared dimension.
2. The DAO first seeks at most 256 model embedding identities through the model-leading index,
   then applies visibility and source-length checks from durable `semantic_sources` metadata to
   those identities. After metadata completion, ranking pages never reopen original message bodies
   or source prefixes. During the once-only bootstrap, explicit search may use the bounded compatibility
   path below. Filtered physical pages advance and yield before continuing to fill the bounded eligible
   page. Physical reads keep their fixed page bound even when only one eligible result remains; surplus
   eligible rows are picked up by the next caller page through its last returned identity. Each SQL
   statement releases its connection; there is no whole-search transaction or corpus-sized filtered
   statement.
3. The selector scores one page at a time directly from the durable BIG_ENDIAN bytes and does not
   allocate a decoded FloatArray for every row.
4. A bounded worst-first top-K heap retains at most the requested result count across all pages.
5. Only the final bounded message-id set is expanded into complete searchable MessageEntity rows.
6. The final expansion revalidates search visibility and minimum source length before returning.

Peak application memory is therefore proportional to one configured page, the cached query vector, the
bounded top-K heap, and the final bounded message set. It is not proportional to embedding-row
count or total cached text.

While legacy metadata is incomplete, explicit semantic ranking keeps old vectors searchable. For
only the at-most-256 embedding identities already selected, a separate read joins original messages
only where the metadata row is missing and applies the original history eligibility and
`LENGTH(text)` minimum. Existing metadata stays authoritative, including ineligible rows; a fallback
must not override it. The query returns only vector identities, bytes and dimensions, never source
bodies or prefixes. It does not run during counts, startup admission, or embedding reconciliation.
Completed metadata selects the metadata-only query. A legacy length check may still evaluate a large
source inside SQLite; the identity cap bounds work per statement, not the size of each source.

### Literal history search

Keyword search shares `HISTORY_MESSAGE_ELIGIBILITY` with semantic pages and final hydration.
It retains literal substring matching (including Chinese, case-insensitive ASCII and escaped LIKE
wildcards), conversation-title matches, and citation-title matches. Literal search does not impose
the semantic minimum text length; existing short nonempty messages remain searchable.

`ChatSearchDao` seeks `(timestamp, id)` newest first through `index_messages_timestamp_id`.
A limited inner query selects at most 128 message identities before the outer query applies shared
visibility, text/title matching and citation eligibility. Queries without ASCII letters use literal
`instr` matching; queries containing ASCII letters retain escaped LIKE for its ASCII case folding.
The fixed citation-segment marker also uses `instr`. Hidden rows advance the cursor but cannot match
or cause text/JSON to be returned. SQL returns only identity, time, a match flag, and eligible citation
JSON; Kotlin decodes citation titles outside the database read. The repository stops
at the requested count, hydrates only winners and rechecks visibility. It runs on IO, checks
cancellation during matching and yields between pages. No whole-search transaction, OFFSET scan,
full-entity corpus load or second citation scan is allowed. A miss still scans bounded pages until
exhaustion; corpus-wide work is cancellable and never one unbounded database statement.

Schema 41 adds only the timestamp/id B-tree index, preserving all messages and existing indexes.
It requires no tokenizer, FTS payload table or message-text backfill. SQLite builds this additive
index once during migration; migration cost is separate from ongoing query latency.


## 3. Ownership

| Owner | Responsibility | Prohibited responsibility |
|---|---|---|
| ChatSearchDao inherited by the sole ChatDao | History eligibility, minimal ranking pages with an incomplete-metadata compatibility path for explicit search, atomic reads of count summaries. | Message/source scans for count presentation, full-corpus semantic list, or ranking policy. |
| SemanticMutationDao and SemanticEmbeddingDao | Maintain derived sources, conversation revisions and cached membership in the owning Room write transaction. | Raw message or embedding writes that bypass summary maintenance. |
| SemanticMetadataDao | Durable source input, per-conversation and per-model summaries, revision guards, legacy cursor commits. | Provider calls or full-body reads during ordinary maintenance. |
| SemanticMetadataWorker | Once-only, resumable legacy metadata bootstrap, independent of model and page lifetime. | Embedding generation, count presentation, or reopening already-derived sources. |
| EmbeddingCacheWorker | Exact work and dirty-conversation reconciliation; bounded provider batches and guarded commits. | Whole-corpus source reconciliation after ordinary message changes, or long provider calls inside Room transactions. |
| RagManager | Post-list admission, durable scheduling, page-demand count lifecycle and ledger-only reminders. | Embedding generation, page-off count refresh, or counts as ledger freshness evidence. |
| ConversationRepository | Forward bounded reads and preserve canonical mutation transactions. | Reassembling pages into one corpus collection. |
| BoundedSemanticEmbeddingSelector | Vector validation, page-by-page scoring, strict threshold, bounded top-K, stable ranking. | Room access, Provider calls, message visibility policy, or cache mutation. |
| RagToolProvider | Query embedding, selector orchestration, final bounded message expansion, tool result projection. | Full-corpus materialization or a second ranking implementation. |

## 4. Failure and concurrency behavior

- A malformed cache row cannot fail the whole search.
- A page must be strictly ordered and advance the keyset; a broken loader fails instead of looping.
- Message deletion or visibility changes between scoring and final expansion may only remove a
  candidate. They must not expose a hidden row.
- Search is read-only. It must not delete/rebuild cache rows, increase the heap limit, or retry the
  complete scan as a correctness mechanism.
- Logs may contain aggregate row counts, invalid-row counts, dimensions, and scores, but no source
  message text, embedding bytes, credentials, or conversation content.

## 5. Cache-count presentation

Each configured Embedding-model row in Conversation Search exposes exactly four and only four
user-visible states. The presentation is a direct projection of whether the aggregate count snapshot
is known, whether the count-loading operation is running, whether the cache worker is running, and,
when counts are known, whether any eligible message still requires an embedding:

| State | Required facts | Visible result |
|---|---|---|
| Loading | No aggregate count snapshot is known, the count-loading operation is running, and no cache worker is running. | Indeterminate loading indicator; no Cache or Re-cache action. |
| Cache | Counts are known, at least one eligible message still requires an embedding, and no cache worker is running. | Exact count status and Cache action. |
| Progress | The cache worker is running. | Cache progress indicator; no Cache or Re-cache action. |
| Re-cache | Counts are known, every eligible message has its required embedding, and no cache worker is running. | Exact count status and Re-cache action. |

Cache-worker activity has priority over count loading: a running cache worker always projects Cache
progress. Every visible row must satisfy exactly one state. `FAILED`, Retry, `QUEUED`, `FINALIZING`,
or any other phase may exist only as internal diagnostic or scheduling detail; none is a fifth
Conversation Search state, label, or action. A count failure retains the last complete snapshot when
one exists. Before the first successful snapshot, the owner must run a replacement bounded count
load rather than expose an undefined or fabricated row state. Loading may be shown only while that
count-loading operation is actually running. If the initial load and one bounded replacement both
fail without a previous snapshot, report a page-level error and suspend the affected cache status
and action projection while no cache worker runs. An explicit page entry may load again; there is
no automatic polling, fabricated Loading, or model-row Failed/Retry action in this case.

`SettingsSearchPage` holds `RagManager.observeCachePresentation()` in its page coroutine. Counts
and WorkManager presentation observers exist only while at least one page subscriber needs them.
The last subscriber leaving cancels the active count or metadata-readiness wait, cancels work
observation and discards the queued successor. Last complete row snapshots remain available. Page
exit never cancels durable metadata or embedding work. The constructor and post-list startup path
must not request counts.

Duplicate page requests share an active refresh. Model-set changes or newly completed work request
at most one successor for the latest configured set. Work observations defer refresh while any
observed cache chain is unfinished; a predecessor finishing with an enqueued or blocked follower
must not launch another count. Once the chain is idle, one refresh satisfies the accumulated demand.
A failed first snapshot is not retried merely because work observations repeat; explicit page entry
owns restarting a failed load.

`ChatSearchDao.getEmbeddingCacheCounts` reads the durable metadata completion row, then sums only
`semantic_conversations.eligibleCount` and the requested models' `semantic_model_conversations.cachedCount`.
The numerator and denominator share one short Room transaction. This visits at most small scalar
summary data proportional to conversations times requested models, O(conversations * models), rather
than messages, source prefixes or embedding rows. Repeated refreshes reuse unchanged summaries.
Requested duplicate models coalesce; models without rows receive explicit zeros. There is no
message/source identity pagination, body-length expression, embedding BLOB read, per-model N+1 count
loop, or count-triggered reconciliation. `cached <= total` follows from transactional membership
maintenance rather than clamping independently scanned results.

Until legacy bootstrap completes, the DAO returns `complete = false`. Its placeholder numbers are
unknown and must not become a visible zero snapshot. The page schedules the unique metadata worker,
retains the last complete counts if present, and waits for the one durable readiness transition
before re-reading summaries. This wait keeps count loading active and remains cancellable on page
exit; it does not poll, scan messages, or tie the durable worker to the page lifetime.

Each model keeps its last complete count snapshot. Here `cached` is the exact number of eligible
messages that currently have a stored embedding for that model. The known cached count versus the
known eligible-message total determines Cache versus Re-cache in this UI: `cached < total` means
Cache and `cached == total` means Re-cache while no cache worker is running. The semantic ledger
remains the durable authority for work admission, exact pending identities, reconciliation, and
completion; it cannot create another visible phase or override the four-state projection.

The progress indicator and the numeric status must describe the same denominator; one row may never
show a percentage from the worker's current batch next to a cached/total pair from the aggregate
snapshot. Outside caching the numeric status is exactly the last complete aggregate snapshot. While
a cache worker runs, the snapshot is not re-queried, so the displayed cached count is that snapshot
plus the processed count of an exact embedding run, clamped to the total; a reconciliation run only
inspects and fingerprint-validates rows and must never move it. The indicator is determinate from
that same pair and states the percentage in the status text. Without a count snapshot there is no
pair, so the indicator stays indeterminate and no number or percentage is shown. Messages merely inspected or fingerprint-validated during
reconciliation must never be presented as newly cached messages or substituted for the cached count.

No timer, polling loop, periodic Worker, or continuously invalidating Room Flow is introduced for
count presentation. Failures log only aggregate diagnostics. Semantic ranking remains governed by the
bounded search path above; count presentation cannot materialize, decode, rank, delete, or rebuild
embedding rows.

## 6. Automatic cache backfill and reminder

`Auto Cache` remains enabled by default and owns incremental indexing of newly persisted eligible
messages. Semantic cache completeness is maintained durably rather than rediscovered by scanning the
message and embedding tables at every launch.

One lightweight ledger row per Embedding model stores whether that model is complete, has exact
pending work, or needs reconciliation. Durable `semantic_sources` rows store bounded source input,
fingerprint, cache eligibility, history eligibility and history length. Each `semantic_conversations`
row owns that conversation's eligible-message total and source revision. Each model/conversation row
owns cached membership and the last successfully indexed conversation revision.

Public message/checkpoint, graph/import/fork, deletion and embedding DAO writes maintain these rows
in the same transaction as the owning mutation. Eligible source changes remove stale vectors, adjust
cached membership and eligible totals, and advance only the affected conversation's source revision.
Run-cascade deletion enumerates descendant Run identities first and seeks messages through the existing
Run-leading index with an IN subquery. It must never join a recursive Run relation by scanning the
entire message table, even when the deleted Run is empty. Production DAO query-plan tests cover
large unrelated payloads, recursive membership, rollback and unrelated-owner preservation.
Moving a source affects its old and new conversations. Changing Task eligibility advances only that
conversation. Run status, title, pin, unread state and backup-watermark-only changes do not dirty
semantic input. History-only changes outside the embedding prefix may update history metadata without
invalidating the embedding. Rollback restores original data, metadata, revisions and counts together.
Whole-conversation message or vector deletion uses owner-scoped bulk SQL and summary changes in that
same transaction; it must not enumerate every message to issue per-message metadata queries.

The active model may also receive exact work identified by model, message, source fingerprint and
revision, so duplicate changes coalesce and old results cannot satisfy new content. Inactive models
become pending and retain their existing per-conversation indexed revisions; ordinary message changes
must not request whole-model reconciliation or dirty untouched conversations. Activation visits only
conversations whose source revision differs from that model's indexed revision. A newly admitted
model has no such completed revisions. Explicit Re-cache or a semantic model change may deliberately
invalidate all of that model's conversation revisions.

Schema 45 gives each model ledger and vector a generation, initially zero for legacy rows.
Re-cache advances the ledger generation and reconcile revision and clears only compact membership
summaries in one short transaction. Search, reuse, fork and membership maintenance recognize only
vectors of the current generation. Model deletion retains a hidden ledger tombstone with monotonic
revisions; re-admission cannot accept responses captured before deletion. A stale configured-model
snapshot from an ordinary message/graph mutation must not revive that tombstone.
The existing maintenance-debt worker physically deletes stale rows through model-leading indexed
identity pages of at most 64 rows, checking the current generation again at DELETE time. Replacing a
vector after a page was read protects that new vector from the old cleanup. Re-cache and deletion
do not synchronously scan or delete vector payloads. Cancellation and process death preserve debt;
retry and duplicate cleanup are safe. Model changes imported through Settings schedule the same debt.

Whole-owner deletion retains one canonical owner-scoped vector deletion and foreign-key cascade;
it must not repeat vector/message deletes or materialize unrelated conversation payloads.
Fork remains atomic and reads/clones vectors in batches of 64 source identities, preserving content,
model generation and new row identities. Large explicit imports, forks and whole-owner deletions
necessarily write their selected target set; these operations are not constant-time transactions.

Schema 42 adds empty source, conversation and model/conversation tables plus a metadata state table,
without copying message bodies or vectors during migration. It seeds a pending state row and marks existing
models for reconciliation. No destructive recreation or synchronous corpus backfill is allowed.
`SemanticMetadataWorker` independently fills legacy metadata with unique KEEP work and a durable
message-ID cursor. It reads at most 16 legacy identities per page, skips sources already maintained
by current writes, reads each missing source outside a write transaction, and commits missing-only
metadata plus the expected cursor in one short transaction. A concurrent update or deletion wins
over the stale bootstrap candidate. Cancellation or process death resumes from the committed cursor;
a superseded cursor reloads durable state. Completion is persistent and ordinary writes do not reset it.

Interactive startup first publishes the conversation-list projection. Only after that list is
visibly available may `RagManager` read the metadata completion row and active model's single ledger
row. These admissions do not enumerate owners, load payloads or count the corpus. Incomplete metadata
admits the one independent bootstrap worker even when Auto Cache is off; that worker makes no
Embedding API or local-model calls. Its completion may wake the active pending cache model only when
Auto Cache is enabled. Model switching, enabling Auto Cache, new model admission, manual Cache and
manual Re-cache share the ledger scheduling path. Re-cache invalidates the model under its shared
mutex before scheduling durable work.

`EmbeddingCacheWorker` is the only cache embedding generator. `RagManager` owns ledger admission,
unique durable scheduling, worker observation, model lifecycle, reminder delivery, and retained
aggregate presentation; it must not hold an in-process cache loop or invoke an embedding engine.
When the ledger is not current and Auto Cache is enabled, exactly one per-model unique worker may run.
A wakeup that arrives while the current worker is running appends one `APPEND_OR_REPLACE` follower so
newly admitted work is not lost behind a stale unique-work KEEP decision. Scheduling and worker-state
observation never hold the model write mutex.

The worker consumes bounded exact-work pages, then reconciles only dirty conversations. It pages
conversation summaries, then source identities within each dirty conversation, with embedding input
read from durable bounded prefixes. Neither phase reopens original message bodies. It limits provider
batch size, yields between pages, and performs network/JNI work outside Room transactions. A vector
commit requires the current source fingerprint, exact-work revision when present, and model reconcile
revision to match. Remote responses map explicit indices back to input order. A provider omitting
every index retains ordered compatibility; mixed, duplicate, missing or out-of-range indices, wrong
cardinality, empty/non-finite vectors and inconsistent dimensions fail the batch without misbinding.
Cancellation propagates and closes the remote Call. One failed vector does not prevent other valid
vectors in that generated batch from committing; unsatisfied work remains durable.

Source fingerprints retain SHA-256 over UTF-8 encoding of Kotlin `text.take(8000)`, where the bound
is UTF-16 units. Persisted input keeps up to 8001 UTF-16 units before UTF-8 encoding, so a surrogate
pair crossing the 8000-unit boundary survives storage; provider input and fingerprints still take
exactly the historical 8000-unit prefix. Full message bodies remain unchanged. Legacy metadata
bootstrap reads original sources once to derive metadata, returning a bounded BLOB prefix and scalar
history length. The separate explicit-search compatibility path may evaluate original eligibility
and length only for missing metadata within its bounded identity page until bootstrap completes.
`SUBSTR(CAST(text AS BLOB), ...)` and `LENGTH(CAST(text AS BLOB))` can materialize the complete
value inside SQLite; bounded output is not proof of bounded internal evaluation. Legacy bootstrap
payload reads therefore stay in the independent cancellable worker, one source at a time outside
write transactions, and never return an unbounded body into CursorWindow. Counts, completed-metadata
ranking pages, fingerprint checks and reconciliation must use derived metadata instead.

First-page and continuation SQL are separate: the initial page has no cursor predicate, and every
continuation uses a direct indexed greater-than predicate rather than a nullable-OR expression.
This applies to legacy message identities, source identities within an owner, and dirty-owner
summaries; a row-count limit alone does not prove that SQLite can seek past prior pages.
Dirty-owner discovery first seeks at most 64 physical conversation IDs, then joins revision summaries
only for those IDs. It advances across clean/empty result pages, checks cancellation, and yields
between pages until the requested dirty-result count or physical end is reached. LIMIT after a dirty
predicate is insufficient: a sparse result could otherwise inspect every clean owner in one SQL call.
The external cursor remains the last returned dirty ID, so eligible rows beyond the requested result
count remain discoverable. Compact summary exhaustion and exact aggregate/freshness checks can still
visit all relevant summaries; message bodies are not reopened and no outer write transaction wraps
discovery.
The first source-ID page has a null cursor so empty legacy IDs remain included. Subsequent cursors
and their validation follow SQLite BINARY unsigned UTF-8 byte order, including supplementary
characters. Filtering must not stop physical cursor advancement or make an empty eligible page look
like corpus exhaustion. Reconciliation has no message-corpus pre-count. Each completed conversation
persists its indexed revision only if both its source revision and the model reconcile revision
still match. Retries skip completed unchanged conversations. A changed source leaves its owner dirty;
a superseding model revision stops the obsolete pass. The ledger becomes current only after metadata
bootstrap, exact work and dirty conversations are all complete. Aggregate equality cannot establish
freshness. Reconciliation inspection counts never become numeric cache progress or per-page
WorkManager progress writes. Ranking continues to check cancellation within and between pages;
double-precision cosine accumulation avoids overflow for finite Float vectors.

The worker emits no uncached, caching, success, completion, partial-failure, or setup-failure
Snackbar. Manual cache and recache actions retain their existing feedback. Deleting a model cancels
its unique work and performs model deletion under the same process-wide model mutex; the mutex entry
is retained so existing and future waiters cannot become concurrent writers.

`Show Uncached Notification` is a separate default-on portable setting consulted only while Auto Cache
is disabled. After the list is visible, the active model's ledger state may emit the generic uncached
reminder directly. It must not request aggregate counts, enumerate sources, or depend on a Settings
page subscriber. The model mutex protects only the bounded eligibility decision; snackbar delivery
occurs after releasing it. Disabling the setting leaves durable work pending and emits no reminder.
The Settings row stays directly below Auto Cache and is hidden while Auto Cache is enabled.

## 7. Required verification

Focused verification must exhaustively cover the four mutually exclusive Conversation Search states:
Loading only while the count-loading worker is actually running with no known snapshot; Cache for
known `cached < total` while no cache worker runs; Progress only while the cache worker is actually
`RUNNING`, with no action; and Re-cache for known `cached == total` while no cache worker runs. It
must reject visible Failed/Retry/Queued/Finalizing states, prove that enqueued or completed work does
not extend Progress, retain the last complete snapshot across count failure, prevent synthetic zero,
and prove that reconciliation inspection counts do not mutate or impersonate cached counts. It must
also cover aggregate count mapping, duplicate models and configured models with no rows, shared page
demand, final-page-exit cancellation, follower-chain coalescing and absence of page-owned N+1 count
loops. Query callbacks must inspect all SQL, including nested `INSERT ... SELECT`, and prove repeated
complete and incomplete count reads visit zero message, source or embedding rows. Count mutation and
rollback tests must use production DAO boundaries rather than raw SQL that bypasses metadata.

Migration/bootstrap coverage must preserve old messages and vectors, show no body copy during schema
migration, resume a committed cursor after cancellation, include empty/Unicode IDs, and preserve new
writes and deletes racing a stale bootstrap page. Metadata completion must unblock exactly one
summary reload; page exit must cancel that wait without cancelling durable work. Test prefix-boundary
surrogates, NUL and whitespace, short cache sources, children and Tasks separately from history minimum
length. Per-conversation worker tests must prove unchanged owners are skipped, inactive models do not
trigger full source rescans, and source/model revision guards reject stale completion. Production
worker concurrency tests must keep foreground reads and writes progressing during a suspended
provider call, cancellation and later resumption without data loss. While bootstrap is incomplete,
explicit search must retain eligible legacy vectors, use original checks only for missing metadata,
preserve root/Task/minimum-length exclusions, and keep the 256-ID physical bound. Completed ranking
must perform no original-message eligibility or length read before final hydration. Neither path may
introduce fallback reads into counts. No original-body count query may return through a
source-contract regression.

Semantic-search verification must still cover the model-leading migration/index, multiple pages,
ranking across page boundaries, strict threshold exclusion, bounded retained candidates, deterministic
equal-score ordering, empty results, dimension/byte-shape corruption, non-finite vectors, and a
source/DAO contract preventing the unbounded full-list hot path from returning.
