# Provider Output and Controls Contract

This is a binding part of the [message generation contract](message-generation.md).
It preserves the requirements of section 8.10; scope and authority are unchanged.

## Complete forwarding and explicit unsupported evidence (2026-09-08)

The owner's following requirements are binding acceptance criteria, not a claim that the current
implementation already satisfies them:

```text
帮我全面核查所有provider，不得有任何一个遗漏，不得有任何一个字段遗漏。每一个provider我必须看到所有字段的完整透传，且必须在最终api request内出现，还必须有效
```

```text
对于不支持的字段，每一个都必须明确标出不支持的source，里面必须看到不支持的声明。如果没有source且没有支持该结论的声明，视为不合格。其他情况，必须透传，不得有任何遗漏借口。
```

1. Inventory every Provider, transport branch, configuration field and final request field. Include
   built-in, custom and synthetic/local paths, nested message/tool fields, and every generation
   caller. No Provider, field or branch may disappear from the audit because it is inconvenient,
   untested, unknown, previously classified as not applicable, or inherited from another adapter.
2. Trace every configured field to the final dispatched API request and show its exact location,
   value, encoding and any transformation. Configuration objects, helper output and UI state alone
   are not wire evidence. Body, headers and URL are distinct request locations; client-local
   controls must show their final request effect rather than being silently excluded. A Local or
   synthetic path must identify its real execution boundary and must not fabricate an HTTP request.
3. Every field must be effective as well as forwarded. Distinguish final production-request capture,
   upstream semantic evidence and actual behavior verification. HTTP success, compilation, a passed
   test count or mere field presence cannot prove effectiveness. Sent-but-ignored, rejected,
   incorrectly transformed and unverified fields must not receive an effective-forwarding pass.
4. Every unsupported-field conclusion requires a directly inspectable source and the exact statement
   in that source establishing non-support, with its Provider, protocol, model/version and operating
   mode scope and retrieval date. Cite each field individually, even when several share a source.
   Documentation silence, absence from a schema or SDK, legacy code, old contracts and previous
   audit conclusions are not an explicit unsupported statement.
5. A missing source or a source without a statement supporting that exact conclusion fails
   acceptance. Mark it evidence-insufficient and unresolved, never supported, unsupported, N/A or
   passed by assumption. It cannot authorize dropping a configured field. Apart from explicitly
   evidenced non-support, complete forwarding remains mandatory; no silent omission, field-removal
   retry, hidden default substitution or display-only setting may replace it.
6. Deliver the complete Provider-by-field report to the owner with no blank/unaccounted cells.
   Record the final request path, actual value/mapping, effectiveness evidence and verification
   status for each field. Negative claims must include their source statement. Any missing field,
   unproven exemption or unverified effect prevents an all-Providers/all-fields completion claim.

This evidence gate takes precedence over earlier applicability, omission and N/A assertions in
[message-generation.md](message-generation.md) and this contract, including the provider-specific
prose below. Those assertions do not themselves establish upstream non-support and must be revalidated
field by field. Persisting this contract does not grant authorization for production/test edits,
credentials, paid requests, Git mutations or deployment.

The owner's repair-scope clarification on 2026-09-08 is:

```text
我让你修provider字段问题！
```

Repair Provider field forwarding, encoding, validation, decoding and continuation replay within the
existing product. Do not turn this work into a new parameter-editor feature, Skill permission
migration or backup-policy redesign. Those unrelated choices are not prerequisites for Provider
field repair. Keep every Provider and unresolved field accounted for; a bounded completed batch
does not establish completion of the full audit or model-side effectiveness.

## Provider controls and output

An official OpenAI Provider or a custom Provider selected as OpenAI-compatible, together with
Responses API enabled, is sufficient to expose both `OpenAI Search` and `Service Tier` in the
conversation UI. No model-name allowlist, capability-discovery request, or extra relay declaration
may suppress those controls. In local chat, the tier choices shown inside the available control
follow the built-in official model support table; an unlisted official model or a custom relay's
model offers all five choices for server-side validation. This is a positive control-availability
rule; it does not redefine any separately supported Service Tier surface outside Responses.

The immutable generation snapshot freezes both choices. When OpenAI Search is enabled, the existing
OpenAI-compatible Responses request includes the native `web_search` tool. When Service Tier is
enabled, that same request includes the selected model's mapped `service_tier` value. The five
normalized values are `auto`, `default`, `flex`, `fast`, and `ultrafast`. Historical `scale` is
normalized to `default`; historical `priority` is normalized to `fast` on read, save, import and
request capture. Changing models never rewrites the stored choice. `auto` and `default` stay as
selected; unsupported `flex` maps to `default`; unsupported `fast` or `ultrafast` maps to a
supported accelerated tier if present, otherwise `default`. Only documented official models have
restricted choices. Offering `ultrafast` for a model does not establish account access: the UI
warns that permission may be required. Chat Completions omits this Responses-only field. The
ordinary Provider owns request serialization; UI visibility must not create a second request path.

Every OpenAI-compatible Chat request forwards captured non-null `temperature`, `max_tokens`,
`top_p`, `frequency_penalty`, and `presence_penalty`, except the documented current Claude models
below, which omit sampling parameters. Thinking resolution is shared by UI and every request
transport through `ModelThinkingCapabilities` and `ThinkingResolution`. IDs are trimmed, lowercased,
stripped to their last provider namespace component and normalized across dotted/dashed versions and
dated snapshots. Model facts refine OPENAI_COMPATIBLE, OPENROUTER, OPENAI, ANTHROPIC and GEMINI;
the QWEN family retains its existing Bailian-specific semantics, including Kimi's off switch.
Unknown IDs retain their existing permissive/family fallback and never become a request rejection.

The owner's 2026-10-26 compatibility table is authoritative for these recognized IDs:

| Model | Thinking off | Enabled efforts | Token budget |
| --- | --- | --- | --- |
| GLM 5.3 / Flash / FlashX | No | low, high, max | No |
| GLM 5.2 | Yes | high, max | No |
| Qwen3.8 27B / Flash-Next / Flash / Max variants / Omni-Flash | Yes | low, medium, xhigh | Exclusive with effort |
| Qwen3.8 2.4T-A95B | No | low, medium, xhigh | Exclusive with effort |
| DeepSeek v4 / v4.1 variants | Yes | low, high, max | No |
| Kimi K3 / K3.1 Pro | No | low, high, max | No |
| Doubao Seed 2.1 variants / Seed Evolving | Yes | low, medium, high | No |
| MiniMax M3.1 Flash | No | low, medium, high, xhigh, max | No |
| MiniMax M3 | Yes | None | No |
| GPT 6.1 Sol | No | low, medium, high, xhigh, max | No |
| Claude Opus 5.5 / Sonnet 5.5 / Fable 5.1 | No | low, medium, high, xhigh, max | No |
| Claude Haiku 5.5 | Yes | low, medium, high, xhigh, max | No |
| Gemini 4 Argon / 3.8 Flash | No | low, medium, high | No |
| Gemini 3.6 Flash / 3.5 Flash / 3.5 Flash Lite | No | minimal, low, medium, high | No |
| Grok 4.7 / 4.6 | No | low, medium, high, xhigh | No |
| Grok 4.5 | No | low, medium, high | No |
| Step 5 Preview / 3.7 Flash | Yes, owner compatibility policy | low, medium, high | No |

Source links and endpoint differences are recorded beside the implementation entries in
`ModelThinkingCapabilityDefaults.kt`. The table is an explicit compatibility policy where catalogs
differ, not a claim that every endpoint advertises identical metadata. Existing GPT 6 Sol/Luna and
Astra behavior is unchanged. Off is a separate control, not an enabled effort. Saved illegal efforts
resolve to the nearest legal level (ties upward); unknown values normalize to the default first.
Forced off on a mandatory-thinking model resolves to its lowest legal effort. Unsupported budgets
are omitted; Qwen effort/budget exclusivity is applied before serialization. Stored settings are not
rewritten and no local rejection is added.

Alibaba Qwen hybrid requests keep their top-level `enable_thinking` and `thinking_budget` fields.
Custom OpenAI-compatible Qwen3.8 Chat and Responses requests additionally send
`chat_template_kwargs.enable_thinking=false` when off. Enabled requests with a resolved effort send
both nested `chat_template_kwargs.reasoning_effort` and top-level `reasoning_effort` with the same
legal value. A budget request omits both effort fields. Other models and the QWEN endpoint do not
receive these template kwargs. Embedded Local already passes `enableThinking` directly through JNI
to llama.cpp's template input; it does not fabricate HTTP kwargs. Remote Strata via LiteLLM uses the
custom OpenAI-compatible transport.

OpenRouter keeps its `reasoning` object; official/custom OpenAI Responses resolve the same model
capability before serializing `reasoning.effort` and summary. Responses forwards supported
temperature, max output tokens, and top-p; it has no native frequency/presence fields in this request
contract. Endpoint errors remain ordinary generation errors without field-removal retries.

Anthropic keeps temperature/top-p only on its legacy request families and has no native
frequency/presence penalty fields. With legacy manual thinking enabled, temperature is omitted and
top-p is forwarded only in the protocol-compatible 0.95–1 range; with thinking off, both captured
values are forwarded. Manual-thinking families use `thinking.type=enabled` plus the captured budget;
adaptive families use `thinking.type=adaptive` plus `output_config.effort`.
Claude Opus/Sonnet 5.5 and Fable 5.1 always serialize adaptive thinking, legal effort and no manual
budget or sampling parameters. Haiku 5.5 retains its off switch with the same effort-only controls.
Older undocumented 5-series IDs retain the permissive fallback. Legacy non-thinking/default-off
families continue omitting `thinking` rather than receiving a current-only disabled shape.

Ollama requests always carry the native top-level `think` control. Ordinary and unknown model names
use a Boolean matching the captured thinking toggle. Native `gpt-oss` model names use the required
`low`/`medium`/`high` effort strings; off or `none` is impossible and fails locally before HTTP.
Ollama continues forwarding temperature, top-p, and max tokens as `options.temperature`,
`options.top_p`, and `options.num_predict`. Frequency/presence penalties are protocol N/A and are
not approximated with Ollama's different repeat-penalty semantics.

Embedded Local requests forward temperature, top-p, max tokens, frequency penalty, and presence
penalty through `LlamaChatEngine` into both text and multimodal llama.cpp generation. Missing
penalties use neutral zero; native sampling applies the configured frequency/presence values with a
neutral repeat penalty rather than dropping or approximating either control.

OpenAI Responses reasoning summaries are public summary content, not raw chain-of-thought. When
thinking is enabled on an official or custom OpenAI-compatible Responses transport, the request opts
into the most detailed available summary with `reasoning.summary = auto`. Summary text deltas enter
the ordinary `ThoughtChunk` path and therefore form normal durable thinking segments and thinking
blocks. Deltas with the same `output_index` and `summary_index` remain contiguous; a change in either
index inserts exactly one blank line between summary parts. Bold text or a Markdown heading in the
current summary part supplies the thinking-card title with its marker removed, matching Gemini.
For models that can disable thinking, off suppresses the summary request. Mandatory-thinking
models instead request their lowest legal effort and its summary. Returned summary events retain
the ordinary shared presentation path.

Provider-hosted tools use non-executing hosted-tool stream events. They may create and settle durable
ordinary tool segments, but they cannot authorize local execution, enter the tool-effect reducer, or
fabricate a tool-result continuation round. Whether a durable hosted segment is presented is an
independent UI policy. Provider semantic termination still owns whether the request succeeded; Stop
and errors use the shared generation settlement.

Tool visibility begins at the canonical creation event, not at execution start. A Provider emits a
`ToolCallUpdate` as soon as a structured Tool block is observable. `GenerationManager` immediately
publishes every newly created segment. For a `ToolCallsRequest`, it upserts every call first and then
publishes one snapshot containing the complete batch before any Tool execution begins. A terminal-only
text-recovery batch follows the same publication rule even though no earlier structured block existed.
The batch executor's later per-call running updates do not own creation visibility. UI placeholders,
delays, retries, or shadow Tool state must not substitute for this overlay publication boundary.

`image_generate` alone uses a 600,000 ms outer execution budget and the same 600,000 ms budget for
its generation request and returned-image download. Every other Tool keeps its ordinary configured
execution timeout. A successful image is persisted through `ToolImageStore` and returned in the
owning `ToolExecutionResult.images`; the overlay copies it to that exact Tool call and
`MessageSegment.toolImages`. No provider-local pending queue, conversation drain, or new
message-level generated-image tail may own current output. Existing `ChatMessage.images` rows remain
read-compatible and retain their legacy full-width renderer, but new generated images never enter
that path.

The visible `image_generate` segment is a hard ordered presentation boundary in Grouped,
ordinary Timeline, and Grouped Bottom Sheet presentation mode. Its current
information card ends immediately after that call. The fixed left-aligned `300 x 300 dp` slot is
rendered next, and every later image call, Tool, Thought, Transcription, or Answer starts after that
slot. Group and slot identity depend only on the append-only segment/detail position, never on the
pending, failed, or successful payload, so lifecycle updates cannot replace, regroup, resize, or
otherwise rewrite the preceding prefix. Multiple image calls establish multiple boundaries in their
original segment order. In Grouped and ordinary Timeline, the information card immediately
before the generated-image slot has exactly `8 dp` of bottom separation. This matches the existing
`8 dp` top separation owned by a later independent Timeline information card. Expanded grouped-card
spacing, the ordinary `2 dp` within-group separation, answer spacing, the Thinking Bottom Sheet, and
the fixed image-slot geometry remain unchanged.

On the boundary's first visible frame, the owning card is presented collapsed and one collapsed
value is committed through the existing expansion map. Bottom Sheet preserves the first
card's existing expansion and appearance identity during this transition. The image-boundary claim
is session-scoped and one-shot: terminal completion does not collapse again, and a later manual
expansion remains effective. The slot owns one draw-only 0.90-to-1.0 scale plus opacity entrance
through the existing segment appearance registry. Its allocated geometry remains fixed while the
contents Crossfade. Pending content is a light-neutral dot matrix over a light-neutral background.
The dot field stays at least `16 dp` inside every slot edge; dot radius uses a fixed physical
falloff from the invisible anchor: distance `0..150 dp` maps linearly to factor `1..0`, clamps beyond
that range, then squares the factor before interpolating the existing minimum and maximum radii. The
anchor's random targets and complete smooth travel stay at least `32 dp` inside every edge. Each
target traversal lasts `1,300 ms`, twice the prior motion speed. Normal motion continues
target-to-target while Pending. The minimum dot radius remains `0.7 dp` and the maximum is `3.9 dp`;
the existing center bounds account for that maximum so complete dot edges still retain the full
`16 dp` inset. Reduced Motion freezes the anchor at the center
without removing the opacity transition. Pending, decoded, and failed content are the only semantic
states of one full-`300 x 300 dp` 200 ms Crossfade owner. A terminal failure fills that complete
slot and shows a centered Material `BrokenImage`; it never first paints a corner-sized failure icon.
For a completed attachment, the Coil request receives the explicit pixel size derived from the
`300 dp` slot so decoding never waits for the painter's first draw; the Pending matrix remains until
decode succeeds, then Crossfades to a `ContentScale.Crop` image that reuses the ordinary media-open
callback. Tool-detail image previews follow the same full-viewport loading/success/failure Crossfade
and use the shared 4 dp indeterminate loading stroke.
Remote `view_image` previews retain this exact presentation but admit their image request only when
the Tool preview is expanded. The square viewport exists before the request starts and remains fixed
through download and decode; the decoded image fades in without changing surrounding geometry.

In the Tool detail Bottom Sheet, an `image_generate` result keeps the same `24 dp` outer horizontal
content padding as ordinary Tool text. Its rounded preview is centered and fills that padded content
width as a `width x width` square. `ContentScale.Crop` center-crops only the source bitmap inside the
square; it does not remove container margins or cap the square by sheet height. Arguments, labels,
result text, and the image therefore share the same outer edges. Non-generation Tool images retain
their existing aspect-ratio sizing and `ContentScale.Fit` behavior.

Local Sandbox and Conch share one shell-tool baseline: foreground command/workdir validation,
bounded retained output and cancellation propagation; bounded typed `file_read`; 1MB UTF-8
`file_write`; backend-native `file_edit`; and home-default glob/grep with the documented caps,
truncation metadata, regex failures, and line-content bounds. Conch durable background jobs,
foreground continuation after the client wait budget, and `file_view_image` are explicit extensions rather
than baseline behavior that Local must imitate. SSH may implement the transport-neutral interfaces,
but it is not the authority for the Local/Conch baseline.

Local Sandbox package installation and upgrade share one dependency download closure. APKINDEX
records are read completely through their blank-line boundary or EOF. Virtual dependency providers
prefer an already-installed candidate present in the index, otherwise the highest repository
provider priority, with stable name ordering for ties. Index order must not pull a competing shell
provider into a transaction. Existing newer packages are never downloaded for downgrade; Alpine's
`apk` remains responsible for validating and applying the complete transaction.

Structured Provider citations follow [citations.md](citations.md). Protocol routers emit structured
citation events rather than answer `TextChunk` or tool events. The existing streaming segment
overlay and bounded checkpoint/terminal persistence retain accepted citation segments for the
identified Run, while Provider history and token/context projection exclude them. Citations do not
create a second generation lifecycle, change semantic termination, or append synthetic source text
to the durable answer. Presentation recognizes a plain proxy artifact formed by `cite` plus one or
more `turn<digits><kind><digits>` Provider source IDs. Complete IDs already present in citation
metadata become the existing adjacent native inline tokens and grouped capsule; a possible trailing
partial artifact is withheld while streaming, and unmatched or malformed artifacts are stripped at
terminal display and copy/export cleanup. Stored answer text, citation identity, numbering, and URL
safety remain unchanged.

A message card with visible tool segments but no real `thought` segment displays only
`Called x tools`. Terminal failed or stopped visible tool segments contribute to that count even
without a result payload; an active group continues to display the current tool name. Message-level
thought duration is a fallback only when at least one thought segment
exists; it must not turn a tool-only card into `Thought for xs, called x tools`.
Gemini keeps its hosted output protocol-local. Candidate `groundingMetadata` becomes a completed,
durable `google_search` hosted block with normalized `results` and full grounding metadata. The shared
UI segment-preparation boundary excludes that exact tool name from the shared Thinking card, grouped
timeline, ordinary timeline, and thinking-detail presentation, so it produces neither a `Google Search` card
nor a `Called x tools` count; generic `web_search`, OpenAI `openai_search`, and other tools remain
visible. This presentation rule does not change request serialization, hosted-tool settlement,
persistence, replay, citation extraction, source order, or failure behavior. An `executableCode` part
starts a visible `code_execution` block displayed as `Code Execution`; the matching
`codeExecutionResult` completes that same block. Code and output are not duplicated into answer text.
Persisted Code Execution segments replay to later Gemini requests as typed executable-code and
code-execution-result model parts in their original order. Multiple pairs remain ordered, and an
unmatched executable-code part leaves a tool in flight so semantic termination fails closed.

If the official service, selected model, or compatible relay rejects `web_search`,
`service_tier`, reasoning summary, or the Responses request itself, that failure is an ordinary
generation error. Persist the provider's bounded error text and render it through the shared neutral
text-only generation terminal presentation. Do not silently retry without the parameter, fall back
to Chat Completions or generic Web Search, auto-disable a setting, show the generated response as an
error, or use a Snackbar-only or parallel error presentation.

Non-success HTTP response parsing is independent of the response Content-Type and is shared by the
OpenAI-compatible, Anthropic, Gemini, and Ollama transports. It accepts canonical nested Provider
envelopes, primitive `error` values, common top-level message fields, and JSON string roots. A
nonblank body that does not match those structures remains visible as trimmed raw text; an empty
body falls back deterministically to its HTTP status. HTTP response failures use the API-error path,
while connection, DNS, TLS, timeout, and other transport failures remain network errors. Persisted
legacy network wrappers reuse the same structured detail extraction so current and historical
presentation cannot drift.

Provider-emitted structured thought events and ordinary text both pass through one shared
Provider-pass normalization boundary before stream accumulation. Native parser authority preserves
typed native tool-call execution and suppresses generic text-rendered tool recovery; it does not
bypass shared incremental thinking-delimiter recovery for text that the native template parser leaves
unclassified. Recovery is incremental across transport chunks, recognizes supported model-emitted
channel forms, and preserves matching delimiters inside Markdown inline or fenced code as literal
text.

A compatible relay that leaves final-answer bytes in its thought field may use a supported unmatched
closing delimiter as the boundary: outside Markdown code, the prefix remains a `ThoughtChunk`, the
delimiter is removed, and the suffix plus later misrouted thought chunks become ordinary text.
Matching is case-insensitive and incremental across transport chunks. This fallback is one-way and
applies only after the wire adapter has already classified content as thought; ordinary text and code
literals are never globally stripped or reclassified. Thought title/signature metadata and the
relative order of tool, usage, citation, retry, and terminal-error events remain intact.

Rows persisted before this normalization use the same narrow condition at the shared Room projection:
only an assistant row with blank durable answer text, no nonblank answer segment, and a nonblank
suffix after a supported close in a thought segment is recovered. UI and Provider-history projection
both split that segment into thought plus answer without mutating Room, so visible history and the
next request cannot drift. A real durable answer always wins and disables compatibility recovery.

## Assistant message generation speed (2026-09-12)
The existing assistant Info dialog also shows average output generation speed in
token/s. It uses reported output tokens and the sum of measured content-arrival
intervals across that message's Provider requests. Monotonic timing begins with
actual text, public thought content or tool-argument content, excludes initial
response wait and local/hosted tool execution gaps, and restarts for a retry.
Repeated cumulative usage snapshots replace rather than add within a request.
The displayed tilde makes the client observation explicit; it is not server decode
telemetry or prefill time. Network buffering can affect the observation.
Missing counts, unmeasured/single-chunk output and legacy/Remote data without
matching timing stay unknown. Do not infer timing from message timestamps,
thinking duration, polling intervals, or text length.
The optional duration follows ordinary Room checkpoint/read and native archive
transport. Schema 31 to 32 adds a nullable column and preserves existing messages.
