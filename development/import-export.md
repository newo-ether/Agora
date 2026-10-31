# Import and Export Contract

Status: authoritative product, persistence, and compatibility contract, updated 2026-10-18 for
version 6 automatic-input source metadata. Version 5 introduced conversation-granular archives and
incremental automatic backups, with export assembly streaming the spool one conversation at a time
after the v5 materialization OOM regression.

This document owns native `.agora` archives, portable settings, import strategies, secret transport,
and automatic-backup compatibility. Public manuals describe the user workflow; this contract defines
the exact development boundary. Current explicit user requirements override older archives or prose.

## 1. Canonical owners

- `NativeBackupFormat` owns archive version support and stable entry names.
- `DataExporter` owns category selection, manifest emission, and archive writing.
- `DataImporter` owns archive validation, category decisions, ordered restoration, and partial-error
  reporting.
- `PortableSettingsArchive` is the only allowlist for portable `settings.json` fields.
- `SettingsManager.resetPortableSettingsForImport` is the exact Settings `REPLACE` reset boundary.
- `NativeBackupSecretsPolicy` is the only owner of opt-in secret capture and restoration.
- Conversation, attachment, Memory, Skill, System Prompt, and custom-font owners retain their normal
  persistence and conflict rules during transport; import/export does not create shadow stores.
- Unreadable conversation or draft image, video, PDF, and file references remain in their original
  attachment order as typed unavailable placeholders. Placeholders retain the filename when known,
  carry no device URI/path, and are never exposed as readable or previewable content.

Adding a DataStore key does not make it portable. A setting enters an archive only after this
contract, export, restore, Replace reset, and focused compatibility tests are updated together.

Conversation `createdAt` is stable portable metadata. New conversations record creation time once;
message, title and status writes preserve it. Subagent capsules and agent_list use ascending
`createdAt`, then child ID, shared across native and WebUI. Schema 38 to 39 fills legacy rows once
from the minimum stored update, Run-start and message times, retaining every graph and receipt;
this is an approximation when no original creation record exists. That migration advances each
`dataChangedAt` watermark so incremental backups cannot copy a payload missing the new field.
Native archives preserve explicit creation times. Older archives without this optional field use
the earlier of their stored update and available Run-start times without loading message bodies.

Conversation `isPinned` is portable conversation metadata, not a Settings field. Native conversation
export and import preserve it; archives without the field default to false. Pin/Unpin advances the
conversation's `dataChangedAt` watermark atomically, so incremental backups cannot reuse a stale pin
value. Pinning does not change `lastUpdated`, the message graph, drafts or device-local unread state.

Conversation `todoJson` is also portable conversation metadata. Native export, import and fork keep
its complete ordered list independently of branch selection. Missing fields in older archives mean
no Todo; an empty list normalizes to SQL null. Known malformed contents or statuses reject the
conversation category during staging, before graph mutation. Accepted Todo tool writes advance the
conversation's `dataChangedAt` atomically, so incremental backups cannot reuse an obsolete list.
Todo is not part of portable Settings, message history, device-local unread state or public sharing.

Conversation subagent ownership and completion-outbox fields are portable metadata. Native export
preserves the indexed parent link and exact completion. Import validates a one-level parent forest
before graph mutation, rejects missing/self/nested parents, inconsistent result identity and unknown
terminal statuses, and marks restored results delivered so opening imported data never starts an
unsolicited generation. The terminal display cache is independent of the immutable completion status:
explicit-open validation may repair it without changing completion JSON, result identity or receipts.
A repaired terminal cache without a completion is valid and creates no outbox on restore.
Older archives have no parent or completion. Fork remains an independent root conversation.
Completion JSON optionally carries `errorText` and `errorCode` separately from generated answer text;
export/import preserve these diagnostics and exact receipts. Missing legacy diagnostic fields remain
null. No historical delivered completion is rewritten or redelivered merely to add error detail.

Version 6 message `sourceJson` carries automatic Task/Loop/ask_user/Subagent provenance, including
exact Subagent completion receipts and optional compact-result display metadata (name, status,
errorText and errorCode). Export retains them with their message; old sources without the optional
summary remain readable without replay or historical rewrite. Import accepts only readable
source data on USER rows and re-encodes it canonically; unreadable data, unknown source kinds, or a
non-USER source becomes null without removing the message. Older messages without this field have
no source. These are durable receipts, not portable pending queue entries or live execution handles.
Selected child
deletion removes only that child graph; selected parent deletion removes its owned children too.
Deleting or replacing conversation rows removes their Todo and completion metadata with the rows.
Neither conversation content nor source metadata belongs in portable Settings.

## 2. Archive envelope and categories

The native format is a ZIP containing `manifest.json`. The manifest records
`agora_export_version`, app version, export time, selected category keys, and whether a secret payload
is present. Current code writes version 6 and accepts versions 1 through 6. Version 6 retains the
version 5 per-conversation layout and adds optional message `sourceJson`. An unsupported version is
rejected before category restoration.

| Category | Stable payload boundary |
| --- | --- |
| `conversations` | Version 5+: `conv/index.json`, one `conv/items/<hex-conversation-id>.json` per conversation, and `conv/tasks.json`, plus archive-safe image, video, and draft media entries. Versions 1-4: a single `conversations.json`. Conversation-scoped settings travel with their conversation, not in `settings.json`. |
| `memories` | Active Memory, Memory database Markdown/metadata, and Skill database Markdown/metadata below `memories/`. |
| `system_prompts` | `system_prompts.json`. The active prompt reference remains a portable Settings field and is resolved against imported/current prompt identity. |
| `settings` | `settings.json` containing only the allowlist below, plus `custom_font/font` when the selected custom font is readable. |
| `api_keys` | Plain JSON `api_keys.json`, emitted only when the API Keys category and explicit include-secrets choice are both active. |

The archive never treats a manifest category as proof that its payload is valid. Missing, malformed,
or incompatible entries produce category errors without reinterpreting another entry as a fallback.
Archive validation completes before any category mutation or resource extraction. Entry names must be
relative forward-slash paths with no empty, `.` or `..` segment, drive prefix, backslash ambiguity,
or duplicate/colliding file-directory identity. Conversation graph payloads are unbounded streamed
database payloads: preview and import never materialize the complete graph in memory, but opening the
archive still streams every conversation payload fully and verifies its declared size and CRC before
any mutation. For version 5 this applies to `conv/index.json`, each `conv/items/` entry, and
`conv/tasks.json` individually. All remaining
non-resource entries, which current import paths may materialize wholly in memory, are limited to
256 MiB expanded aggregate metadata and receive the same streamed size and CRC verification.

Conversation image/video/draft resources, legacy `images/` and `videos/` resources, and the recognized
custom-font entry are storage-backed resources. Normal attachment resources have no fixed byte or
entry-count ceiling when storage is sufficient; there is no standalone total ZIP entry-count limit.
A seekable SAF source is opened directly through its file descriptor, so preview/import does not
create or retain a whole-archive copy in application cache. A provider that cannot expose random
access is rejected with an instruction to download/select a local file; import does not fall back to
a cache duplicate or a second streaming authority. Every selected resource extraction preflights
destination capacity, checks space again while streaming, and deletes partial files on failure. The
custom-font owner retains its separate 64 MiB limit. Sandbox and proot payloads remain excluded rather
than becoming resources.

A conversation export reads conversation settings before entering the database, then opens an
independent `ChatDatabase` instance and captures Conversations, Runs, paged Messages, Tasks, Loops,
and every raw media reference into a temporary typed JSONL spool inside one DEFERRED read
transaction. While capture runs, the spool writer records a byte-range slice for every conversation
plus one slice per task block, so archive assembly later revisits stored records through random
access instead of re-scanning the spool or re-materializing the graph. The independent instance uses dedicated background-priority executors and never
occupies the process Room transaction executor, connection pool, or executors. Under WAL, foreground
generation may continue committing atomic checkpoints: a checkpoint committed before the export
snapshot is established is included, later checkpoints are excluded, and no partial transaction is
visible. The read transaction performs no destination, ZIP, or media I/O. Only after it returns may
export open the destination, read media, rewrite archive paths, and emit the `conversations` category
payload (`conversations.json` for versions 1-4; `conv/index.json`, per-conversation
`conv/items/<hex-id>.json`, and `conv/tasks.json` for version 5). The
snapshot database and its executors are released on success, failure, and coroutine cancellation.
The spool is deleted on success, failure, and coroutine cancellation. Import terminalizes an
archived active Run as recovered STOPPED while retaining its last committed message checkpoint.
Import retains a terminal Run's `CONTINUATION_PENDING` reason without restarting work or turning the
intermediate handoff into a Subagent completion. Existing Runs keep their stored reason unchanged.
Tool timeline references (`toolRoundId` plus `toolCallId` inside segment JSON) travel with the
unchanged protocol-row identities and bodies. Export/import must not expand them into a root row
or discard their referenced tool/result rows; selected-message hydration restores display order
through the ordinary generation projection. Legacy inline timelines remain supported.

## 2a. Version 5 conversation-granular archives and incremental backups

Version 5 splits the `conversations` category per conversation. `conv/index.json` lists every
exported conversation with its `dataChangedAt` watermark, its `conv/items/<hex-id>.json` entry path,
and the media entries it references. Each item entry holds that conversation's own
`conversations`, `runs`, `messages`, and `loops` arrays. Tasks are conversation-independent and live
once in `conv/tasks.json`.

Export assembles the archive by streaming the spool rather than materializing it. The spool index
holds only slice offsets and per-conversation `dataChangedAt` watermarks; the archive writer revisits
one conversation at a time, reading its slices through random access, copying media, and emitting
that conversation's item entry before moving on. Run and loop rows pass through verbatim; message
rows are decoded, archive-path rewritten, and re-encoded lazily as their entry is written. Export
memory therefore stays bounded by a single spool record, the media-reference dedup map, and slice
metadata, never by the size of the database or of any single conversation. Baseline raw copies use
the same per-conversation flow: an unchanged conversation's item entry and media entries are copied
byte-for-byte from the baseline archive without touching the spool. Both version 5 and version 6 use
this layout; only a current-version baseline is reusable, so an older archive cannot omit newly added
portable fields.

Automatic backups reuse the most recent valid `Agora_backup_*.agora` file as an optional baseline. A
conversation whose `dataChangedAt` watermark is unchanged is raw-copied byte-for-byte from the
baseline item entry, and its media entries are raw-copied without rescanning; changed or new
conversations are re-exported. A baseline that is missing, corrupt, unreadable, older than the current
format, or structurally invalid is rejected before writing and the export falls back to a complete
current-version archive. Writing goes to a `.tmp` file and is published by an atomic rename; failure
or cancellation deletes the temporary file and never disturbs existing backups.

Import normalizes both shapes to one graph view before any mutation: version 1-4 streams
`conversations.json` directly, while version 5+ merges index, items, and tasks into one temporary
spool file in the application cache. The spool is deleted when the source closes, including failure
and cancellation paths. Merging fails closed: duplicate conversation ids, index entry paths that do
not match `conv/items/<hex-id>.json` for that id, missing item entries, a missing tasks entry, or a
malformed index are category errors that leave no spool behind. From the merged spool onward,
preview counts, headers, run planning, message import, media restoration, and strategy semantics
are the same code paths for versions 1 through 6. Restoring an incremental current-version archive is
equivalent to restoring a full archive containing the same graph.

## 3. Portable `settings.json` allowlist

The following JSON field names are the complete current portable allowlist.

| Group | Portable fields |
| --- | --- |
| Model selection | `selectedModel`, `customModels`, `enabledModels`, `modelAliases`, `modelProviderNames` |
| Context | `contextTokenBudget`, `visualizeContextRollout`, `contextCompactEnabled`, `contextCompactModel`, `contextCompactPrompt`, `contextCompactRetainCount`, `contextCompactThresholdPercent` |
| Provider and reasoning | `codeExecutionEnabled`, `googleSearchEnabled`, `thinkingEnabled`, `thinkingLevel`, `thinkingBudgetEnabled`, `thinkingBudgetTokens`, `openAiServiceTierEnabled`, `openAiServiceTier`, `openAiResponsesApiEnabled`, `anthropicCacheEnabled`, `anthropicCacheTtl`, `providerBaseUrls` |
| Title generation | `titleGenerationEnabled`, `titleGenerationModel`, `titleGenerationPrompt`, `titleGenerationNotificationsEnabled` |
| Tool access | `accessPastConversations`, `accessSavedMemories`, `accessActiveMemory`, `accessSkills`, `todoEnabled`, `subagentToolsEnabled`, `subagentDeleteEnabled`, `subagentMaxTotal`, `subagentMaxConcurrent`, `subagentEnabledModels`, `subagentRecommendedModel`, `subagentCapsuleDisplayMode` |
| Interaction | `askUserEnabled`, `askUserTimeoutMinutes` |
| Search and embedding | `ragSearchEnabled`, `modelSearchMethod`, `manualSearchMethod`, `remoteEmbeddingModels`, `activeRemoteEmbeddingModelId`, `searchContextWindow`, `searchMatchLimit`, `ragThreshold`, `autoCacheEnabled`, `showUncachedNotification` |
| Language, Web Search, and image generation | `appLanguage`, `webSearchEnabled`, `webSearchProvider`, `webSearchNumResults`, `webSearchBaseUrl`, `imageGenEnabled`, `imageGenModel`, `imageGenSize`, `autoUpdateCheck` |
| Image quality | `imageQualityMode`, `imageQualityMaxLongEdge`, `imageQualityJpegQuality` |
| Image transcription | `imageTranscriptionEnabled`, `imageTranscriptionEnabledModels`, `imageTranscriptionModel`, `imageTranscriptionBatchSize`, `imageTranscriptionPrompt` |
| Shell, automation, custom Providers, and MCP | `shellEnabled`, `shellConfirmEnabled`, secret-free `shellDevices`, `automationToolsEnabled`, `exactExecutionEnabled`, `customProviders`, secret-free `mcpServers` |
| Proxy | `proxyEnabled`, `proxyType`, `proxyHost`, `proxyPort`, `proxyUsername`, `proxyBypass` |
| Appearance | `showDocumentationFab`, `themeMode`, `amoledEnabled`, `colorScheme`, `dynamicColor`, `blurEffectsEnabled`, `reduceMotion`, `stickToBottom`, `parseInlineDollarMath`, `hapticsEnabled`, `detailedTokenUsage`, `toolCallDisplayMode`, `thinkingSegmentDisplayMode`, `autoExpandActiveGroup`, `schemeStyle` |
| Custom font | `fontPreference`; `customFontName` only when `custom_font/font` is included. The device file path is never portable. |
| Generation defaults | Nullable `defaultTemperature`, `defaultMaxTokens`, `defaultTopP`, `defaultFrequencyPenalty`, `defaultPresencePenalty` |
| Prompt selection | Nullable `activeSystemPromptId` |

Nullable fields are deliberately emitted as JSON null when unset. Their presence distinguishes
"clear this portable value" from an older archive that has no opinion about the field.
`anthropicCacheEnabled` and `anthropicCacheTtl` carry the built-in Anthropic Provider's boolean and
5m/1h string. MERGE preserves absent fields; REPLACE clears both keys so an older archive restores
the on/1h defaults. Custom Providers carry the same fields in their composite records and retain
the existing record replacement and stable-identity remapping rules. Missing custom fields use
on/1h defaults, including when an imported record replaces a matching local record. Explicit invalid
cache values are rejected before any Settings reset or restore write. These fields contain no
secrets; their request and UI semantics are owned by `settings-ui-ux.md`.

`imageQualityMode` is a string normalized to `light`, `balanced`, `high` or `custom`; unknown
strings resolve to `balanced`. `imageQualityMaxLongEdge` and `imageQualityJpegQuality` are
non-string JSON integers storing retained custom values, even while a preset is active. They
clamp to 128..8192 and 1..100 respectively, with defaults 2048 and 80. Export reads one settings
snapshot and writes all three fields. MERGE preserves each absent field; a partial import patches
only present fields together in one DataStore edit. REPLACE clears all three keys before restore,
so older archives use Balanced and the default custom values. Explicit wrong JSON types are
rejected before any reset or restore write. These settings contain no secrets or paths and never
reprocess existing artifacts. Runtime/UI behavior is owned by `image-processing.md` and
`settings-ui-ux.md`.

`modelProviderNames` maps complete model IDs to booleans independently of aliases. False explicitly
hides the Provider suffix; absent model IDs default to true. Export preserves both values. Import
remaps the keys through the same custom-Provider identity policy as aliases. MERGE preserves this
setting when the field is absent and preserves unrelated IDs when present. REPLACE resets the map
to an initialized empty map, including for older archives; restarting must not reinterpret imported
aliases as a request to hide Provider names. This preference contains no credentials or file paths.

`amoledEnabled` is a default-false boolean. MERGE leaves it unchanged when absent; REPLACE clears
its portable key before restore, so older archives resolve to false without redefining Theme Mode semantics.

`accessSkillsModify` has no independent archive field. Settings `REPLACE` clears its explicit
device value so the restored setting follows the portable `accessSkills` compatibility fallback;
`MERGE` leaves an existing explicit modify value unchanged.

`askUserEnabled` defaults to true. `askUserTimeoutMinutes` is a secret-free integer selected from
1, 2, 5, 10, 15, 30 and 60 minutes, default 5. Stored reads, saves and imported integers outside
these presets normalize to 5. MERGE preserves either absent field. REPLACE clears both keys before
restoring present fields, so older archives restore enabled/five-minute defaults. Export writes the
effective values. These preferences never restore pending questions or restart an admitted wait.

`todoEnabled` and `subagentToolsEnabled` are default-true portable booleans. MERGE preserves each
local preference when its field is absent. REPLACE clears both keys before restoring present fields,
so older archives resolve to true. They control tool admission, not conversation Todo contents or
subagent ownership, and contain no credentials or device paths.
`subagentDeleteEnabled` is a default-false portable boolean with the same MERGE/REPLACE rules;
older archives resolve to false. It controls only `agent_delete` admission.

`subagentMaxTotal` and `subagentMaxConcurrent` are secret-free integer limits per parent, with
range 1–16 for both and defaults 16 and 4. There is no unlimited value. Stored reads, saves and
imports clamp integers to these ranges and cap concurrency at the normalized total. Export writes
the effective integer values. MERGE preserves absent fields except that lowering an imported total
also clamps the retained concurrency. A present concurrency alone is bounded by the retained total.
REPLACE clears both keys before restore, so older archives use 16/4; a restored total below 4 also
bounds the default concurrency. Present integer fields are saved together in one DataStore edit,
with any absent counterpart resolved inside that edit. Lowering limits or restoring them never
stops, deletes, or rewrites existing children; execution admission belongs to `message-generation.md`.

`subagentEnabledModels` may retain raw unconfigured null/absent state for archive compatibility,
but null/absent and an explicitly empty set all yield no new spawn candidates. None inherits globally
enabled models. Existing explicit sets are preserved. It uses complete
portable model IDs and the same Provider identity remapping as other model sets. `subagentRecommendedModel`
is a nullable complete model ID, remapped and accepted only if currently available in the effective
Subagent list. MERGE leaves absent fields untouched and unions a present configured set with an already
configured local set; explicit null does not erase local configuration. REPLACE clears both keys, so
older missing fields restore an effectively empty candidate list and no recommendation. Restoring an explicitly
empty set under REPLACE keeps it empty. Existing children and their model IDs are never rewritten by
these global preferences.

`toolCallDisplayMode` writes only `timeline` or `grouped_timeline` (default). Legacy `compact`
imports normalize to `grouped_timeline` through the ordinary Settings write in both MERGE and REPLACE.
An absent field remains untouched in MERGE and resolves to the default after REPLACE. Device-local
legacy preferences migrate transactionally before publication, and exports contain the resolved value.
Thinking Card/Bottom Sheet preferences remain independent; Context Compact data and settings are unchanged.
`subagentCapsuleDisplayMode` is a secret-free presentation string: `individual` or `grouped` (default).
Unknown, invalid or absent values normalize to `grouped`; an explicitly saved `individual` remains
unchanged. Export writes the resolved value. MERGE leaves an absent field unchanged; REPLACE clears
its key before restoring, so old archives without it resolve to `grouped`. This setting
never changes child ownership, order, generation state, tool access, or the model list.

Only remote Embedding configurations are portable. They are stripped of API keys and local file
paths. Local Embedding configurations and all GGUF paths remain device-local. MERGE preserves a
surviving active model when neither the current nor legacy active-reference field is present, even
when remote model records are imported. A present active-reference field also applies without a
model-list field and may select only a surviving remote record. An explicit null or invalid remote
reference clears a remote selection while retaining a surviving device-local selection. REPLACE
without a valid remote reference likewise retains only a surviving device-local selection.

## 4. Device-local exclusions

The following state must not enter `settings.json`, must not be restored from it, and must survive a
Settings `REPLACE` import unless a separate selected category owns it:

- Local Chat model records, GGUF paths, multimodal projector paths, Local Embedding model records,
  and imported model files.
- `local_model_idle_retention_minutes` / Local model idle retention. It persists in this device's
  DataStore, defaults to five minutes, and is not exported, imported, or cleared by Replace.
- `local_low_context_mode_enabled` / the Local Provider's Low Context Mode default. It persists in
  this device's DataStore, defaults off, and is not exported, imported, or cleared by Replace.
  Nullable conversation/New Chat overrides are separate conversation-scoped settings and therefore
  travel only with their conversation; a null override continues to inherit the destination device's
  current Local Provider default.
- Sandbox enabled/shared-storage state, sandbox files, and pending/runtime Sandbox attachment state.
- Developer Options, first-launch/onboarding/rating state, message counters, and other installation
  lifecycle metadata.
- Automatic-backup enabled state, schedule, destination, retention, last-run timestamp, and derived
  model-fetch/cache fingerprints.
- API keys, active API-key IDs, Web Search keys, proxy password, Shell credentials, remote Embedding
  keys, and MCP headers; these belong only to the explicit secret category.
- Conversation-scoped settings; these travel only with an exported conversation.
- Custom-font filesystem paths. The font bytes use the dedicated Settings-category entry.

Derived Provider/model catalogs and endpoint-resolution probes are never restored. Replace invalidates
derived portable model caches so stale discovery results cannot masquerade as imported configuration.

## 5. Secret transport

The secret payload contains Provider API-key records and active IDs, Web Search API keys, proxy
password, Shell API keys, SSH passwords, private-key content and key passphrases keyed by stable
device ID, remote Embedding API keys keyed
by model ID, and MCP headers keyed by server ID. Version 1-3 name-keyed Shell API keys are accepted
only for legacy compatibility.

`api_keys.json` is plain JSON inside the ZIP; Android Keystore envelopes are not portable. It is
therefore emitted only after explicit include-secrets selection. Structural Settings payloads always
strip those values. Secret restore ignores orphan records that have no matching structural owner and
reports warnings rather than inventing devices, models, or servers.

SSH authentication mode (`password` by default, or `private_key`) and the imported key's display
filename are structural Shell fields. Key content and its passphrase live only in the existing
Keystore-encrypted device configuration locally and the opt-in secret payload in a native archive.
All structural exports strip password, private key and passphrase, including legacy-style copies.
Older archives without the mode remain password-authenticated. Settings-only imports cannot inject
private-key material; existing secrets follow the same matching-device preservation rules as passwords.
Secret MERGE retains unmatched key/passphrase values, while secret REPLACE clears omitted values.
No private filesystem path or SAF URI is transported, and secret restore creates no key files.

## 6. Import strategies

Every archive category has an independent decision:

- `SKIP` performs no mutation for that category.
- `MERGE` preserves unrelated local state and adds or updates only imported identities according to
  the category owner. For Settings, only fields present in the archive are applied.
- `REPLACE` replaces only the selected category. It never broadens into an unselected category or
  device-local state.

Settings `REPLACE` first clears exactly the portable preference allowlist, then restores fields
present in the archive. Fields absent from an older archive resolve to current defaults. Device-local
exclusions are intentionally not cleared. Composite records such as custom Providers, remote
Embedding models, Shell devices, MCP servers, and custom fonts use their dedicated merge/identity and
secret-remapping rules rather than raw map replacement.

Secret `MERGE` retains unmatched local secrets and merges imported records by stable or semantic
identity. Secret `REPLACE` clears replaceable secret values for existing structural owners when the
archive omits them, but it does not create an owner for an orphan secret.

ChatGPT and Claude imports expose `MERGE` and `REPLACE` for the selected conversations. `MERGE`
preserves every unrelated local conversation and adds only imported graph identities that are not
already present. `REPLACE` deletes every existing conversation graph and leaves only the selected
imported conversations. The external replacement validates the complete selected graph before any
delete and performs the delete plus Conversation, Run, and Message writes in one Room transaction.
The unified Settings UI must present a second destructive confirmation before starting external
`REPLACE`, explicitly stating that all existing conversations will be deleted and only the selected
imported conversations will remain.

The native `.agora` preview separates each visible category decision block by 16 dp while retaining
the existing 4 dp label-to-strategy-control gap inside a block. After the user selects an archive,
reading, validating, and generating its preview shows a non-dismissible modal titled `Loading…`
until the preview is ready or the operation fails. The preview choices appear only after that loading
state clears. After the user confirms Import, actual native restoration shows a separate modal titled
`Importing…`; after the user selects an export destination, native export shows the corresponding
`Exporting…` modal until success or failure. All three native modals use the same centered,
indeterminate, motion-aware circular progress indicator, reject Back and outside-click dismissal,
and expose no cancel action. Preview-loading state is distinct from actual import progress and clears
on success, invalid or empty archives, failure, and cancellation. Third-party Claude/GPT imports
retain their determinate percentage presentation.

Native graph import finishes archive decoding, media-reference restoration, graph validation and
selection serialization before taking the Room writer. Prepared messages are streamed through a
private temporary application-cache file, with bounded object retention, then replayed in batches
inside the existing single atomic graph transaction. The file is deleted on success, failure and
cancellation; it is an internal transient representation, never an accepted external archive format.
The prepared file uses one locked channel. Android unlinks the open file immediately, so process death
also releases its storage; hosts that cannot unlink open files retain the lock and reclaim abandoned
unlocked prepared files at the next import. Concurrent active imports and unrelated cache files are
excluded from that cleanup. Object serialization resets its handle table after each message, and
archive JSON/media decoding stays outside the writer. Prepared-file reads and typed-object replay
remain inside the atomic transaction, whose total cost scales with the imported graph.
The final transaction still checks MERGE identities and commits graphs, settings-transfer records
and maintenance debt together. A late batch failure rolls back earlier batches and REPLACE deletions.
This removes archive/media preparation from writer occupancy without introducing partial imports.

Legacy custom-Provider normalization preserves physical stable-ID remapping and the existing
`legacyNames` resume marker. Conversation and historical message references are found through
model-leading indexes and rewritten in transactions of at most 64 rows; each message batch updates
only its affected backup watermarks. The marker is cleared only after all batches and remaining
configuration references finish. Cancellation may leave completed batches and retains the marker
for retry. Provider deletion, name reuse and import mapping keep their existing identity meaning;
permanent name aliases are not introduced.

## 7. Legacy compatibility and failure behavior

- Unknown JSON fields are ignored; known fields are normalized and validated by their current owner.
- Legacy `maxContextWindow` maps to `contextTokenBudget`.
- Legacy `activeEmbeddingModelId` is accepted when the current
  `activeRemoteEmbeddingModelId` field is absent.
- Legacy `active_system_prompt_id` maps to `activeSystemPromptId`.
- Archives before version 4 may restore `extra_settings.json` and the legacy custom-font layout.
- Provider/model references are remapped when stable custom-Provider identities are allocated or
  reused. Active references are accepted only when their target survives import.
- A category failure is reported with its category and does not convert malformed input into a
  successful default. Temporary custom-font files are deleted when installation fails.
- Missing individual attachment resources do not fail an otherwise valid export or automatic backup.
  The successful result reports the total unavailable-resource count, and restore keeps each durable
  placeholder disabled with its type, filename, and relative attachment order intact.
- Import must never delete or overwrite data outside the selected category and strategy boundary.

## 8. Required verification

Focused tests for any archive or setting change must prove:

1. export and restore use the documented JSON name and compatible type;
2. Settings `REPLACE` clears the portable key and preserves every device-local exclusion;
3. `MERGE` leaves absent fields unchanged and explicit null clears nullable portable fields;
4. structural Settings export contains no secret or device-local path;
5. opt-in secrets round-trip without creating orphan structural owners;
6. supported old versions and aliases restore deterministically, while unsupported versions fail;
7. archive category selection cannot mutate an unselected category;
8. conversation media, Memory/Skill files, System Prompts, and custom fonts keep their owner-specific
   conflict, cleanup, and rollback behavior;
9. archive validation rejects unsafe or duplicate paths before mutation, leaves streamed
   conversation graph payloads (including the per-item version 5 entries) without a fixed byte cap
   while verifying their size and CRC, caps aggregate
   in-memory non-resource metadata at 256 MiB, requires direct seekable-source access without a
   whole-cache duplicate, and enforces destination capacity before and during resource copy without
   a standalone resource entry-count limit;
10. unreadable attachment resources preserve order, type, and filename as disabled placeholders, and
   successful manual and automatic backups report the complete unavailable-resource count;
11. version 5+ merging fails closed on duplicate ids, entry-path mismatches, missing item or tasks
    entries, and malformed indexes, deleting the temporary spool; unchanged-watermark conversations
    and their media are raw-copied from a valid baseline, and any invalid or older-format baseline
    falls back to a complete current-version archive; a merged incremental archive produces the same
    preview counts and graph headers as the equivalent legacy payload;
12. the default and every maintained public manual remain consistent with this contract.

The project full build remains required after implementation changes. Build success alone does not
prove SAF access, large-archive streaming, device storage, or user-visible conflict handling.
