# Semantic Search Architecture Contract

Status: authoritative development contract, 2026-09-15.

This document is required context for changes to embedding-cache reads, semantic conversation search,
RAG ranking, or the search eligibility query. Semantic search must remain bounded by one database
page plus the requested top candidates; corpus growth must not translate into Android heap growth.

## 1. Observable behavior

- Query embedding generation, model selection, API-key resolution, and user-visible tool behavior
  remain owned by the existing RAG/provider path.
- Searchable sources exclude Task conversations, non-USER/non-MODEL rows, blank or short source
  text, and synthetic tool/result/Compact rows.
- Similarity is cosine similarity. Candidates must be strictly above the configured RAG threshold,
  ordered by descending score, and limited to the requested count.
- Stable embedding row id is the deterministic tie-breaker for equal scores.
- Invalid dimensions, malformed byte lengths, and non-finite scores are skipped without aborting
  the remaining corpus and without logging message content.

## 2. Bounded data flow

1. ChatDao reads a minimal projection containing only embedding row id, message id, embedding
   bytes, and declared dimension.
2. The DAO uses stable keyset pagination (id > afterId, ORDER BY id, bounded LIMIT). It never
   materializes the complete model corpus for semantic search.
3. The selector scores one page at a time directly from the durable BIG_ENDIAN bytes and does not
   allocate a decoded FloatArray for every row.
4. A bounded worst-first top-K heap retains at most the requested result count across all pages.
5. Only the final bounded message-id set is expanded into complete searchable MessageEntity rows.
6. The final expansion revalidates search visibility and minimum source length before returning.

Peak application memory is therefore proportional to one configured page, the cached query vector, the
bounded top-K heap, and the final bounded message set. It is not proportional to embedding-row
count or total cached text.

## 3. Ownership

| Owner | Responsibility | Prohibited responsibility |
|---|---|---|
| ChatSearchDao inherited by the sole ChatDao | Eligibility join, minimal projection, deterministic keyset page. | Full-corpus semantic list or score/ranking policy. |
| ConversationRepository | Pass through the bounded page contract. | Reassembling pages into one collection. |
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

`RagManager` must not start an aggregate refresh from its constructor. A refresh begins only after
the conversation list has been published or the Settings page explicitly requests it, and it must
never delay list publication. Duplicate page requests share an active refresh; worker completion or
a configured-model-set change during counting requests at most one successor for the latest set.
A failed initial snapshot is not automatically retried by background work observations; explicit
page entry owns restarting that load. One bounded DAO aggregate
returns cached counts grouped by configured model id while the indexable-message total is read
independently. No query returns message text or embedding blobs, and page entry must not issue N+1
model counts.

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
pending work, or requires bounded reconciliation/initial backfill. Searchable-message admission,
text or eligibility changes, deletion, conversation deletion, fork/import, embedding success, and
embedding invalidation update the semantic ledger and exact work identity in the same durable
transaction as the owning mutation. Each work item is uniquely identified by model and message and
includes the current source fingerprint or revision, so duplicate events coalesce and an embedding
for older text cannot satisfy current content. Inactive models may retain one `needsReconcile` state
instead of multiplying per-message work; activating a new or stale model admits one bounded keyset
reconciliation. Database migration marks affected models for reconciliation without scanning message
content during application entry.

Interactive App startup first publishes the conversation-list projection. Only after that list is
visibly available may `RagManager` admit the active model by reading its single ledger row. This O(1)
check must not execute aggregate counts, enumerate conversations or owners, load message text or
embedding blobs, or instantiate conversation runtime state. Model switching, enabling Auto Cache,
new model admission, manual Cache, and manual Re-cache use the same ledger admission path. Re-cache
invalidates that model under the shared model mutex before scheduling durable work.

`EmbeddingCacheWorker` is the only cache embedding generator. `RagManager` owns ledger admission,
unique durable scheduling, worker observation, model lifecycle, reminder delivery, and retained
aggregate presentation; it must not hold an in-process cache loop or invoke an embedding engine.
When the ledger is not current and Auto Cache is enabled, exactly one per-model unique worker may run.
A wakeup that arrives while the current worker is running appends one `APPEND_OR_REPLACE` follower so
newly admitted work is not lost behind a stale unique-work KEEP decision. Scheduling and worker-state
observation never hold the model write mutex.

The worker consumes bounded exact-work pages or bounded full-reconcile pages, limits embedding batch
size, yields between pages, and commits only when both the database source fingerprint and durable
work revision still match its admitted candidate. Failed or superseded items remain durable work for
a later pass. Worker activity is observed from unique WorkManager state; completion may refresh
presentation but aggregate equality cannot mark the ledger current.

Full reconciliation starts without a full-corpus pre-count and advances until its keyset page is
empty. Completion also requires successful processing and the admitted reconciliation revision to
match. A superseding revision stops the obsolete pass; newer exact work remains pending. Inspection
counts are not published as numeric cache progress, avoiding per-page WorkManager progress writes.

The worker emits no uncached, caching, success, completion, partial-failure, or setup-failure
Snackbar. Manual cache and recache actions retain their existing feedback. Deleting a model cancels
its unique work and performs model deletion under the same process-wide model mutex; the mutex entry
is retained so existing and future waiters cannot become concurrent writers.

`Show Uncached Notification` is a separate default-on portable setting. It is consulted only while
Auto Cache is disabled. After the conversation list is visible, the same one-row ledger check may
request a background aggregate refresh for exact reminder copy. The reminder may be emitted only by
a refresh that includes the target model, and it must not treat a count as freshness evidence.
Disabling the setting leaves work pending and emits no reminder. The Settings row is placed directly
below Auto Cache and is not shown while Auto Cache is enabled.

## 7. Required verification

Focused verification must exhaustively cover the four mutually exclusive Conversation Search states:
Loading only while the count-loading worker is actually running with no known snapshot; Cache for
known `cached < total` while no cache worker runs; Progress only while the cache worker is actually
`RUNNING`, with no action; and Re-cache for known `cached == total` while no cache worker runs. It
must reject visible Failed/Retry/Queued/Finalizing states, prove that enqueued or completed work does
not extend Progress, retain the last complete snapshot across count failure, prevent synthetic zero,
and prove that reconciliation inspection counts do not mutate or impersonate cached counts. It must
also cover aggregate count mapping, configured models with no rows, coalesced refresh, the
model-leading migration/index, and absence of page-owned N+1 count loops. Semantic-search
verification must still cover multiple pages, ranking across page boundaries, strict threshold
exclusion, bounded retained candidates, deterministic equal-score ordering, empty results,
dimension/byte-shape corruption, non-finite vectors, and a source/DAO contract preventing the
unbounded full-list hot path from returning.
