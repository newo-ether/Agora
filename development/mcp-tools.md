# MCP Tool Discovery and Dispatch

This contract is part of [message generation](message-generation.md). The ordinary generation,
Task, Loop and Subagent tool pipeline owns admission, per-pass authorization, Stop, timeout,
checkpoint, recovery and terminal persistence. MCP does not create a second lifecycle.

## Fixed request prefix

`McpToolProvider` exposes exactly `mcp_list_servers`, `mcp_search_tools`, then `mcp_call_tool`, in
normal tool-enabled contexts. All three definitions are fixed even when the MCP catalog is empty,
disabled, connecting, disconnected or in error. Existing Compact and Local low-context tool
disablement still applies. Schemas are fixed. The `mcp_search_tools` description tells the model
that MCP servers extend Agora and must be searched before it says a capability is unavailable.

Prefix rule (shared with Subagent model IDs and Shell device names): the request prefix may carry
user-edited configuration, never runtime state. When at least one server is enabled, the
`mcp_search_tools` description ends with the enabled servers' configured `id` and display `name`
(blank -> `MCP server`) as a JSON array sorted by ID, at most 32 entries with names cut to 80
characters, plus a `+N more` note. It never contains status, tool counts, tool names, server
instructions, URLs or timestamps. No server enabled keeps the base description. Discovery must not
append tools to the outbound tools array or inject a catalog into the system prompt. Existing prompt
cache key, Anthropic enabled/TTL settings and retry rules remain owned by ordinary request
construction.

The prefix therefore changes only when the user enables, disables, adds, removes or renames a
server. Connection, refresh, catalog and directory changes must not change it. This preserves cache reuse eligibility; it cannot guarantee hits
across model/system/other-tools/TTL changes, rollout, Compact or relay policy. Fixed-cost accounting
counts the three entries. Directory data and discovered schemas are ordinary history result cost.

## Server directory

`mcp_list_servers` takes optional `limit` (default 20, range 1–50) and nonnegative `offset`
(default 0). Project all currently enabled configured servers, including idle, connecting, error
and connected servers with zero available tools. Return only `server_id`, `server_name`, `status`
(`idle`, `connecting`, `connected`, `error`) and `available_tool_count`. A blank display name uses
`MCP server`, never an endpoint URL. Sort by stable server ID, independent of display-name changes
and configuration order. Return `servers`, `returned`, `total`, `next_offset` and a live-state warning.

The existing Registry is the sole owner. Read current settings and snapshot/runtime ownership in
one bounded lock decision. Snapshot publication records its exact configuration, including failed
builds without a runtime. A snapshot for an older configuration is idle with zero available tools
until the current configuration publishes state. Counts use the same availability predicate as
search and dispatch. No network request, connection, refresh, enablement, health check or new state
lifecycle is admitted by listing. Never expose URLs, headers, credentials, raw errors or tool schemas.

Serialize complete entries within the ordinary tool-result limit, including escaping and envelope.
Stop before an entry that would overflow a nonempty page. If one entry cannot fit alone, return an
explicit `server_entry_too_large` item and advance the offset; never truncate an ID or emit partial
JSON. Pagination is over a live local snapshot and may require restarting after changes.

## Tool discovery

`mcp_search_tools` takes required string `query` (empty browses), optional exact `server_id`,
`limit` (default 5, range 1–10), and nonnegative `offset` (default 0). Default global search is the
ordinary capability-first path; listing is optional. A supplied server ID is never widened to a
global search: unknown or disabled IDs return `invalid_server`; an enabled server whose current
state is not connected returns `server_unavailable` plus safe status. Both are error results.
A connected server with zero enabled tools or no keyword matches returns a successful empty page.

Read scope and eligible tools together from the Registry. Include connected tools whose current
server configuration and runtime match and whose server and remote tool are enabled. Keyword
matching covers stable ID, remote name, server name, description and parameter names. Exact IDs
retrieve one definition; exact names rank first; stable ID breaks ties. This is deterministic
keyword search, with no semantic translation claim.

Return stable `tool_id` and `server_id`, server/name/description and the complete original
`input_schema`, plus `returned`, `total` and `next_offset`. Measure serialized JSON including escaping
and envelope against the ordinary tool-result limit. Include complete items only. A page that cannot
fit its next item ends before that item. A definition too large on its own yields an explicit
`schema_too_large` item and advances the offset. Never emit a truncated schema or malformed JSON.
Pagination is over a live snapshot per search; a changing catalog may require a fresh search.

Directory and search results are durable ordinary tool results. Replays, imports, continuations and
Regenerate read stored bytes; catalog changes never recompute, rewrite or prune earlier history.
Ordinary context rollout and Compact keep their existing ownership.

## Dispatch and compatibility

`mcp_call_tool` requires only an exact registry `tool_id` and a JSON-object `arguments` payload.
Reject unknown outer fields, malformed roots, missing/wrong types and double-encoded argument strings.
The fixed schema uses `ToolParameters.schema` to preserve arbitrary nested object values. The selected
MCP server continues validating its tool-specific JSON Schema; Agora does not implement a partial
replacement validator. Never route caller-supplied URLs, credentials or remote names. Do not add a
redundant server ID: the exact tool ID already selects its registered server.

`McpRegistry.execute` resolves the descriptor and runtime in one bounded decision immediately before
dispatch. Re-read current settings, require the exact runtime configuration, connected state, server
enablement and remote-tool enablement. This rejects stale settings before asynchronous reconcile
retires a runtime. Never hold a registry lock during a network call. Existing image storage,
structured results, remote error state and cancellation propagation remain unchanged.

Only the three outer names are newly authorized by a Provider pass. Legacy individual MCP names
remain readable in history and native archives, but must not acquire hidden execution authority.
Keep their stored names, arguments and results unchanged. No database migration is needed.

## Network failures

MCP connection establishment, initialization and catalog reads silently consume Agora's shared
five-retry budget for transient transport/HTTP failures before reporting an error. Retry-After and
cancellation follow the shared generation network policy. Authentication and permanent client
errors never retry, including when reading their error body fails. JSON-RPC IDs correlate responses;
they do not establish tool deduplication. `tools/call` may retry a proven pre-connect failure, but
never an uncertain posted request, response-body interruption or lost SSE session. Session recovery
before tool dispatch remains separate from replaying a tool that may already have run.

Failures from a tool invocation are scoped to that invocation. Once a catalog has connected,
transport, remote execution, parsing, or local image-storage failures during `execute` must not mark
the whole server unavailable or trigger another catalog connection loop. Retain discovery and exact
configuration/enablement admission for later independent calls. `CONNECTED` records an established
catalog, not a promise that every remote request will succeed. Initial connection/catalog failures
and explicit refresh retain their existing connection-state and retry owners.

An expired session reported by `tools/call` invalidates that session under the protocol client's
existing serialization boundary. Report the failed call without replay; the next independent call
initializes a new session. Generic EOF does not prove session expiry and must not reset an otherwise
valid session. Cancellation propagates without changing registry availability.

## Presentation and verification

Argument-aware metadata retains the actual remote tool name/server separately from the protocol
name. Inspect a bounded prefix while streaming and resolve full arguments once at execution. Persist
the resolved title/target with ordinary tool data so disconnected/deleted servers do not change old
cards. Shared native/WebUI presentation owns localized directory and search action wording.

Focused tests cover directory status/configuration freshness, zero-tool and duplicate-name servers,
secret omission, no network/mutation, ranking, pagination, nested schemas, escaped size bounds,
invalid/unavailable scope versus empty matches, current dispatch, rich results and cancellation.
Metadata/replay tests cover partial and reversed fields and large payloads. Production request tests
compare serialized OpenAI Chat/Responses, Anthropic and other schema boundaries before/after MCP
changes and directory/search results, including unchanged keys/TTL/system/history prefixes. Actual
cache-hit evidence requires reported `cached_tokens` or `cache_read_input_tokens` from an authorized
real endpoint run; byte equality or HTTP success alone is not a measured hit.
