# Tool Presentation Contract

This contract defines the user-visible title and summary semantics for every built-in, MCP, and unknown tool. `ToolPresentationResolver` is the canonical lifecycle resolver and `MessageItemToolLabels` is the canonical summary renderer.

`ToolDetailPresentation` owns resource-backed detail content selection and result fields shared by
Compose and the WebUI. It consumes `ToolPresentationResolver` rather than resolving another lifecycle.
Compose retains layout, selection, prefix-aware JSON rendering, and local image loading; browser
projection stays demand-driven within watched message payloads. Failed/stopped content does not
replay completed MCP/search results, and shell exit codes remain command results rather than failures.

## Lifecycle

The visible lifecycle is deliberately small. `CALLING` and `RUNNING` share one active presentation. They must not create separate user-visible states or wording systems. Terminal presentations are completed, empty, failed, stopped, or running in background.

An exit code is a command result, not a tool failure. A completed shell call with any exit code uses `Command returned <code>`. Only transport, protocol, server, rejection, or other failures that prevent a usable command result use the failed presentation.

`shell_job_wait` describes its own action after completion. Its card summary is `Waited for shell job <id>` when the ID is available. The exit code remains a compact detail status. Background summaries do not expose job IDs.

Shell job `stopping` and `settling` are nonterminal background states. `stopped` and `interrupted` are terminal stopped presentations with distinct `Stopped` and `Interrupted` summaries. These server states use the existing terminal model; nonzero command exit codes are still usable results, not transport failures.

## Wording

Display names use title case and contain no lifecycle state. Active summaries use sentence case, present-progressive wording, and a Unicode ellipsis. Completed summaries use sentence case and past tense with no terminal period. Empty summaries explicitly state that no result exists and never masquerade as ordinary completion.

Failed summaries show a concrete error reason directly whenever one is available. Remove generic `Error:` and tool-execution wrapper prefixes from the summary, capitalize the initial natural-language word, and preserve paths, identifiers and the original result/detail text. For example, `Error: command timeout` becomes `Command timeout`. Only failures without a concrete reason use a localized attempted-action fallback such as `Failed to read <path>`.

Execution failure is declared by provider error metadata or a structured protocol error, never by the spelling of successful text. Active memory, saved memory files, and skill file bodies are arbitrary content, including bodies beginning with `Error` or containing JSON error fields. Stopped summaries use past tense. Background summaries state only that the job is running in the background and do not include its ID.

Reliable subjects and counts are shown. An unavailable count is not zero and must use a count-free default. Paths, commands, file names, IDs explicitly required by an action, and user input preserve their original case. Summary text describes lifecycle only. Result content and compact detail status must not replace it.

Known counts use locale-aware Android quantity resources, including singular results. A known web result count remains visible without a query subject. Summary subjects flatten whitespace and use a maximum of 120 characters including a final Unicode ellipsis only when truncated. Full arguments and result text remain unchanged.

For memory and skill reads, effective `names` takes precedence over `name` exactly as in the provider. A single target shows its name; a batch shows `Reading/Read N files`. A streaming count is reliable only once its names array is complete. Independent `file_read` calls retain separate summaries. Empty file content and an empty conversation page explicitly say that no content/messages were returned, not ordinary completion.

The active-memory reader is a distinct display target, not an ordinary saved memory file. Its title is `Read Active Memory`, its active summary is `Reading active memory…`, and its completed summary is `Read active memory`. Empty content and failures without a concrete reason also name active memory. Concrete failures still show the reason directly. This display distinction does not change the registered `active_memory_read` API, read target, execution lifecycle, or ordinary file-read summaries.

Memory/skill rename summaries show old and new names when known; description edits name the description action. `ask_user` distinguishes waiting for answers, queued questions, answered questions and skipped questions using actual returned facts. A mixed set shows answered count against returned question count without claiming every question was answered. Question outcomes are natural sentences with quantity grammar (`Answered 1 question`, `Answered 3 questions`, `Answered 2 of 3 questions`, `Skipped 2 questions`, `Queued 2 questions`), never label-colon-count text. `loop_stop` distinguishes a new stop from an already stopped Loop.

## Detail Bodies
Shell execute, get/wait and stop-job details use the same shell body, including stopped or interrupted
server results. The compact status remains distinct from command output. A failed call with no real
output shows only its failure, not an empty-output placeholder. Successful calls without output use
the localized `(no output)` label. Transport failure never turns arbitrary result text into output.
Memory, Active Memory and Skill reads render stored text literally through the existing file/code
body, never Markdown interpretation. One file and Active Memory start their separate line-number
gutter at 1; multi-file results retain original file separators in one unnumbered code body. Text,
selection and copy remain unchanged. Compose and WebUI consume this same presentation projection.
Empty JSON arrays and objects render the same localized `No content` label in native and WebUI
details, including nested values; do not show the `[]`/`{}` literal or an `(empty)` suffix.
Only complete containers are empty; an open streaming prefix must not claim emptiness or invent
closing syntax. Nested empty values appear
once beside their key, without an ellipsis or an extra child block. Raw tool content, copy data,
and lifecycle semantics stay unchanged; this is shared JSON presentation, not a Todo exception.
`file_grep` clamps integer `context` to the supported 0..10 range instead of rejecting it; a changed
value prefixes model-facing text with `context: N (requested M)`, while structured content retains
the actual backend result. Noninteger values still fail validation.

## MCP Router Presentation

`mcp_list_servers` is a distinct localized list action with no target or argument subject. Active
wording describes listing MCP servers. Completed wording uses the returned page count when known,
never the total directory size or available-tool count; an unknown count uses count-free wording.
An empty page says no MCP servers were returned, without claiming the configured directory is empty.
Concrete failures retain their reason; failures without one use the localized list-server fallback.
The shared summary and ordinary JSON detail owners serve native and WebUI without a new layout.

`mcp_search_tools` is a localized search action with its query as a bounded progressive subject.
Active wording describes searching MCP tools. Completed wording uses the returned item count when
known; a zero-item page explicitly says no matching tools were returned. Counts describe the
returned page, never the entire matching catalog. Its result is ordinary immutable tool history.

`mcp_call_tool` retains that exact protocol name and its original outer arguments. Provider metadata
supplies the actual remote tool title and server target. Streaming metadata may inspect at most
4096 argument characters and only resolve a complete root `tool_id` string. An incomplete ID, a
nested ID, or an ID beyond that prefix does not supply a guessed title. At execution, the provider
resolves the fully parsed exact target and may publish its remote title with `TargetResolved`.
This event updates display metadata only and never creates a separate lifecycle.

Persist the resolved title and target in the existing tool segment and `ToolCallData` projection,
including completed result rows and fallback round construction. Replay, Regenerate and restored
history consume those fields without requiring a connected or still-existing MCP server. Legacy
per-tool MCP names retain their existing read-side title fallback. Native and WebUI consume the
same localized title, lifecycle, summary and detail owners; neither changes protocol identity.

## Phone Use Tools
The eleven `phone_*` tools use the shared Phone kind with localized action titles. Individual
native and WebUI cards use the shared Material Symbols Outlined `mobile_hand` icon, matching
Phone Use Settings and the control notification; mixed tool groups keep their generic group icon.
Active cards use progressive summaries ending in `…`; completed cards describe the finished action in past
tense without terminal punctuation. These summaries do not reuse the overlay's concise status
labels. Concrete structured failures such as `not_claimed`, `occupied`, `released_by_user`,
`permission_lost` and `app_blocked` retain their error reason through the existing resolver.
Native and WebUI use the same resource-backed summary and ordinary JSON/image detail owners.

## Remote Native Tools

Remote tool payloads keep their native name, arguments, results and running state. A transient
Remote hint admits a pure, read-only format mapper at the shared detail boundary. Complete supported
Bash/shell, Read/Write/Edit/MultiEdit, Codex fileChange/apply_patch, Glob/LS/Grep and structured web
results reuse the existing terminal, file, diff, path, match and search components. Unknown tools,
missing required fields, wrong types and incomplete patches retain the original JSON/plain view.
Unconsumed arguments and result metadata remain visible; mapping never mutates the Remote cache,
native execution, Room history or Filo protocol. Running calls may show a complete command, write
content or proposed diff before a result arrives. Native recorded lifecycle remains authoritative:
a failed activity stays failed even when its shell output and exit code can be rendered. Shell format
recognition selects the detail body without changing the recorded state. A nonzero exit code alone
does not declare a failure when the source has not recorded one. Native failures and stopped results
retain their evidence. A read removes a native line gutter only when every returned line supplies
consecutive explicit line numbers. Known arguments/results may use nested JSON string
envelopes, text content blocks, path arrays, Codex argv arrays, stdout/stderr or structured
output wrappers. The read-only mapper unwraps only recognized shapes and retains their
metadata as supplemental JSON. A Read of literal JSON file content preserves that content.
Neither receive nor presentation code cuts serialized JSON; remote preview bounds belong
to Filo's decoded-field projection. Unknown tools and invalid structures keep JSON fallback.
Codex fileChange renders every `changes` entry in order. String and object `kind` forms select
literal full-file added/deleted rows for `add`/`delete`, preserving source prefixes and blank
lines; `update` parses unified diff rows and shows an optional `move_path` from the kind or
change object. Each file is an independent section using the native primary-colored monospace
file-name header and secondary directory, plus a localized operation MetaPill. A move retains both
source and destination headers. Recognized Begin/End Patch, Update/Add/Delete File, Move to,
unified file headers and hunk markers are metadata, never repeated diff body rows. Each hunk uses
the native spacing and optional numbered pill instead of an `@@` title. Empty added/deleted files
still retain their file header and operation. Compose and WebUI consume the same parsed sections;
the browser never reparses patch text. Claude Write/Edit and apply_patch Add File use the same
literal code/diff bodies. Unsupported fields or malformed entries retain the whole original
payload rather than omitting part of a batch.

## Localization

Todo and Subagent tools use explicit built-in kinds and localized action titles, never the unknown-tool
English fallback. Their summaries describe the tool action, not a child's generation state: spawn
starts a child, send queues a message, and wait completes its waiting action without claiming every
child finished. Raw protocol names, keys, arguments and results remain unchanged. Native and WebUI
message cards consume the same app-locale resources; browser chrome retains its existing en/zh scope.

Every locale contains the same summary keys and placeholder types. Languages may reorder indexed placeholders. Running text uses `…`. Terminal text has no final period. A change to lifecycle semantics must update every locale and the presentation contract tests in the same change.
