# Remote Sessions

Status: current owner-approved Filo four-source installation and integration, 2026-10-22. Earlier staged
plans and superseded decisions are retained in Git and the single active task log.

## Ownership and protocol

Remote connects Agora to an independent Filo service. Installed sources are Codex Desktop,
Codex CLI, Claude Code and Claude Desktop (Code surface). Filo discovers installed native
clients under the selected Windows account and offers one checkbox per source. Selecting a
source authorizes automatic installation and persistent official registration of its required
Filo components, including the Claude Mod; no manual copy, environment variable or plugin
load step belongs in the normal installation flow. Installation failures must roll back
Filo-owned component changes and cannot advertise a partially installed source as installed.
Installed descriptors remain visible while their native application, Mod or worker is offline;
only uninstalled integrations may be omitted. Status is independently verified for each source
and never inferred from readable history or gateway health. Source selection is an explicit catalog filter and access path;
capabilities reflect implemented and verified operations on that path. The Desktop adapter
supports its original owner for execution and bounded read-only native history for browsing.
Isolated native helpers may handle peripheral operations such as model catalog, rename and
archive. Filo's maintained project and connection contracts govern native qualification.
Never patch native binaries, redirect global backends, write native transcripts or acquire a
competing writer. Failed dispatch cannot fall back to another source, host, queue or fork.
Desktop operations prefer verified Codex Desktop IPC; only an established capability gap
permits an additional unchanged Desktop-provided runtime. Desktop-only use never requires
a separate Codex CLI installation or desktop page/focus/input automation. CLI source access
uses the selected account's existing official shared daemon and never starts or replaces it
while browsing. Embedded/no-daemon CLI sessions do not imply attach/control support.
Claude Code and Claude Desktop initially expose open sessions with the automatically installed
official JavaScript Mod loaded by their supported native plugin mechanism. The terminal requires
Claude Code 2.1.287 or later; Desktop requires its own embedded Code engine 2.1.286 or later.
Component installation alone never establishes native compatibility or online status. Until a
supported native session registers, that installed source stays visible and offline. Only a single
terminal or Desktop surface is admitted; mixed or changing attachments revoke the old access binding.
A running session that cannot reload plugins remains offline until native reload or its next normal start; the
installer never restarts or automates a native client. Claude Mod Send and active Insert
use explicitly non-exact `submitted_observed` receipts. `submitted` means only that the native
API accepted the call. `observed` additionally requires a new same-registration native user
item or turn after submission, with fully matching content and the command's observation ID.
Idle Send submits a native user prompt. Running Insert appends a native user-role note;
its returned row UUID and full text can establish observation. The note enters subsequent
model requests in the loop, without altering an in-flight request or forcing a new request
after completion. Observation alone does not prove model consumption.
This does not prove exact client/message/turn linkage. A 20-second observation timeout becomes
`unconfirmed`; it blocks resend and never causes replay. Independent Mod capabilities include
bounded native model options, current model, main-loop request effort and exact-turn Stop.
Stop requires its result plus the matching native terminal event. Model/effort selections are
session-local rewrites for subsequent main turns; they remain pending until the settled
`turn.step` engine/core trace proves the actual request values. Missing request evidence stays
pending; mismatches fail and expose actual values. Named effort choices come from the native
request API, not a per-model support guarantee. Claude Desktop uses the same Code
Mod implementation but still needs embedded-engine runtime qualification; CLI probes do not
qualify Desktop. Codex CLI and Claude Code never expose remote creation; their source descriptors
and scoped creation routes refuse it before native allocation. Agora also hides CLI New Chat
against an older service that still advertises creation. Existing CLI conversations, including
previously Filo-created work, retain their independently admitted controls.
Claude Desktop Code uses existing Mod-registered conversations and does not support creation;
New Chat stays unavailable through the create capability, without an unsupported-creation
notice in the catalog. Chat/Cowork are not advertised.
Filo-created Codex Desktop tasks may execute on its runtime. An existing idle Desktop task
may be recovered only inside an explicit Send or settings request after verifying the
native account, idle state and absent IPC owner. Recheck ownership before allocation and
preserve native resume writer-lock conflicts. Browsing, subscriptions, active state and
unknown outcomes grant no writer authority. An existing IPC owner always takes precedence.
Filo service stop/crash/kill/removal must not affect normal Codex or accepted native
execution. Independent completion and writer release require actual qualification.

Remote transport, saved connections and transient presentation are independent of Room,
LlmProvider, GenerationManager and ordinary conversation drafts. Codex is durable truth.
Remote image loading preserves the received image bytes, encoding and dimensions. It does not
invoke Agora's model-input image processor or apply Generation image-quality settings. Bounded
viewport decoding remains a display operation only. This owner clarification on 2026-10-27 is
also scoped in [image-processing.md](image-processing.md).
Only the selected session owns Remote history/subscription. Changing selection, connection,
visibility or read generation cancels old reads and rejects late results. In-flight writes
stay bound to their original owner. No automatic POST retry or input replay is allowed.

Protocol 2 uses the shared authenticated encrypted channel and an explicit service address.
Legacy `/v1/*` remains the fixed Codex Desktop projection. Negotiated `extensions.multiAdapter=1`
adds at most four installed source descriptors and `/v1/adapters/{adapterId}/...` routes.
A new client against an old service synthesizes one connection-local legacy Desktop source;
a Claude-only service never pretends to be legacy Codex. Unknown required schemas fail closed.
Create, history, live, idle Send, active Send semantics, Stop, individual settings, model/usage,
rename/archive, uploads and lazy messages are separate capabilities, defaulting unsupported.
Session `updatedAt` is integer Unix seconds. Message/node `timestamp` is integer Unix
milliseconds or `null` when native time is unknown. Unknown time remains unknown in
presentation; client receipt time cannot replace it. Row ordering uses admitted native
identity and topology, never an invented timestamp.
A bounded history snapshot is never presented as complete history; the UI shows no separate
snapshot or read-only notice.
Bearer authentication is private-loopback only; public callers cannot bypass the encrypted
envelope. Codex Desktop idle Send starts a native turn and active Send steers its exact original
turn. Other sources expose only their admitted semantics. Stop requires its native active ID.
HTTP acceptance and native client-ID visibility are separate confirmations; unknown delivery
is reconciled without blind resend.
Native mutations have a 210-second mobile read/whole-call budget, enclosing Filo's
180-second private request, 90-second executor readiness and 60-second protected
creation budgets. Ordinary reads retain their shorter timeout. Cancellation still
closes only the request; a timeout never grants permission to replay accepted input.
Native approvals remain with their owner. Remote supports raw photo/file uploads as defined
below; approval handling, transcript editing, branching and tool-execution controls remain unsupported.

## Canonical identity and source-qualified control

The extension returns opaque `nativeScopeId` and `canonicalSessionRef` in explicit session/page
access metadata. Device connection + verified family/account/store scope + native session identity
owns shared drafts and viewed-turn state. `adapterId` selects access, not durable native identity.
No account names, SIDs, store paths or auth secrets belong in public scope values. Account/store
replacement changes scope. Equal native IDs, cwd, title, URL or message content never prove equality.
A source may intentionally use an isolated scope until continuity can be verified.

An old service has no canonical identity evidence. Preserve its connection-local legacy Desktop
viewed entries; do not automatically copy them into a newly advertised scope. Migration requires
real installation/account continuity evidence, retains the original entries, and is atomic and
idempotent. Without that proof, unread may be reevaluated. Extension toggles retain independent
namespaces. Lossless downgrade through an older APK that drops unknown fields is not qualified.

Runtime, settings and control remain qualified by selected adapter and current access binding.
Canonical sharing does not transfer control. The server revalidates an exact binding and owner/turn
at dispatch. Existing-session scoped mutation JSON carries `accessBinding` inside the encrypted
body; arbitrary HTTP headers are not forwarded by the shared channel. The service removes this
transport field before native settings validation. Source-level creation/uploads have no existing
session binding. They carry the expected descriptor `nativeScopeId` in encrypted JSON, which the
service checks against its freshly verified request scope before admission. Upload chunks and
completion remain bound by their source/scope token. A Desktop-filter row owned only by CLI is read-only there; switching source is explicit.
Send and Stop keep their admitted path, generation, client ID and native turn after navigation.
Never replay or transfer them. Canonical coalescing prevents duplicate admission across source paths.
Every scoped cursor, upload, payload/image reference, SSE and cache key remains source-qualified;
shared device read/cache/stream/upload limits never multiply with configured sources.

Unsupported create/settings/send/Stop/manage controls stay hidden or disabled by explicit capability;
history remains readable and drafts editable. Read-only sources subscribe only when their explicit
live capability permits native observation; read-only history alone never starts a live subscription.
A successful initial detail read may supply a fresh, identity-validated session grant for idle Send
or settings even when `live:false`. Preserve that snapshot's runtime for those explicitly granted
controls; model metadata, catalog grants and a missing detail grant cannot establish control.
If the detail grant adds `live:true`, enter the existing subscription using the same selected owner.
This initial capability discovery is separate from the failed-subscription fallback below.
Native exact acceptance requires the actual client ID, user-message identity and turn identity.
Claude Mod's explicitly labeled non-exact submitted/observed contract above is a separate weaker
claim; a content observation never manufactures exact native linkage.

## Devices and local persistence

Root Back must leave Agora through Android's normal activity behavior, never reopen
a retained page. Task-history return is available only while that exact preview is
the displayed destination; pending, failed, stale and returning previews cannot
intercept Back on the ordinary/new-chat home. Remote Back still unwinds the selected
conversation, device and overlay in their existing order.

Remote sits below Tasks in their original joined drawer group and opens SettingsOverlayHost.
Both drawer buttons use the owner-requested 46 dp height, joined 24/5 dp corner shapes and
2 dp inter-button gap. Their icons, labels, focus clearing and navigation remain unchanged.
Device selection precedes Sessions; Add Device is a separate shared settings page. Its top Help
group offers the independent `What Is Filo?` guide defined by settings-ui-ux.md. Guide Back
restores the same unsaved editor draft; the device list has no guide entry.
Save and discarded Back follow the original return to Devices. Help does not change save semantics.
Devices renders its scaffold and locally saved rows independently of all network checks.
No loading text, bar or overlay belongs here. The original MCP status dot reports
idle/connecting/connected/error. A failed or delayed network check never hides the list.

RemoteConnectionStore owns the atomic no-backup connection file and Keystore-encrypted
tokens. Plaintext fallback and overwriting corrupt storage are prohibited. Local restoration
does not select a device, open history, resume or send. Names survive owner recreation;
old URL-as-title entries use the localized Device label until explicitly edited. Add/Edit
provides a Name input and saves its trimmed value. The configured name renders immediately;
network hostname never replaces it. URLs appear only on the address line. Name edits preserve
credentials and viewed-turn IDs; late connection checks cannot revive removed devices.

Save is the only editor submission action. Offline configurations may be saved; successful
persistence returns to Devices before connecting. Invalid input/storage failure retains the
editor. Back without Save discards it. A submitted save may finish after navigation but
cannot take over the newer page. Duplicate storage actions are fenced. Edit atomically
replaces the selected address/token without colliding with another device; Delete confirms
and removes only that local connection. Failure retains it. The row menu is Edit/Delete.
Fields reuse MCP labeled inputs and gray URL/token examples, with no submitted defaults.
Placeholder text inherits the original field defaults without an extra opacity reduction.
Helper copy explains installing Filo on the target and entering its IP or URL, not Tailscale.
Visible entry/save starts coalesced connection checks; no periodic device-health loop. The
selected visible session catalog additionally observes native Desktop state as specified below.

Load failures use the original application Snackbar, positioned by the original composer
inset owner in chat and the system inset on Devices/Sessions. Preserve the current page and
loaded content. An empty conversation additionally shows neutral localized connection/load
guidance with a safe read/reconnect action; error details remain in Snackbar, without a
permanent timeline error row. Read retry is bound to the current selection and cannot replay
sends, settings or other writes.

Storage and protocol errors stay distinct from connection errors.
Device health is independent of a native session read: an HTTP/service/protocol error must
not mark a reachable device offline. A network read failure checks authenticated info before
changing device health; retries show Connecting, and fresh snapshots restore native readiness.
One interrupted live GET with successful health may recover on the existing three-second
retry before showing a Snackbar. Persistent failures show one notice per failure category
until a fresh snapshot succeeds; true health/authentication failures remain immediate.
Server SSE error events are service failures, not network outages. Recovery never retries
POST, and selection/visibility changes cancel recovery and reject late health results.
Failed live subscriptions may read one bounded snapshot from the same selected native owner
per retry so available history still renders. That snapshot never restores live control
readiness; only a successful live subscription snapshot does. No alternate host is admitted.
Safe failure stages, categories, exception/cause types and HTTP status use the existing
privacy-aware log wrapper and logging preferences; never include error messages or enable
diagnostic capture implicitly.

Diagnostics contain only
random owner identity, operation, elapsed time, counts, exception type and HTTP status.
Never log addresses, credentials, session IDs, bodies or message content. Unsaved secrets,
selected sessions, drafts and submission attempts remain transient and are never replayed.

## Sessions and history

Use the existing settings scaffolds, guarded page transitions and rows. Each catalog page
returns its row statuses in the same HTTP response; do not issue a follow-up status request.
While visible, refresh only catalog pages containing visible rows after the normal interval. The original 18dp/2dp generating circle has priority over
the 8dp unread dot; retain 200ms fades. Real completed-turn identity owns unread state.
Successful visible history read marks that completion viewed in the encrypted local store;
loading/failed reads do not. Never change native Codex read state.
Cache the last confirmed row status by device and session for the Remote owner lifetime.
Navigation and failed/unknown status reads retain it; visible-row reads and real chat snapshots
update it. Removing a device/session or replacing a connection clears its cached entries.
Each catalog response includes exact available row status, active-turn ID, completed-turn ID and
native unread state. Initial entry and later list pages merge those fields together. A directly
observed latest native terminal projects idle even after executor retirement; a missing owner,
missing terminal, bounded-out record or newer start never establishes idle. Visibility
must not immediately trigger another network request. Subsequent polling uses the same catalog
endpoint and its page cursor, never a separate status endpoint. Unknown/failed native status
preserves the last confirmed value; it cannot fabricate idle, completion or execution authority.
This is presentation state only; Filo verifies native ownership/state at dispatch, and Stop requires its exact native turn.

The session catalog title is the configured device name (localized Device fallback), replacing
Sessions. Immediately below it, a horizontally scrollable source selector shows only configured
Codex Desktop, Codex CLI, Claude Code and Claude Desktop integrations. There is one chip per
installed source, never per process. Online sources appear before all other sources. Within each
group, use Codex Desktop, Codex CLI, Claude Code, then Claude Desktop, independent of advertisement
order or current selection. Connecting and unavailable states do not count as Online.
Offline installed sources remain visible with their status;
source selection never follows
availability automatically. Preserve the last explicit configured selection, defaulting to Desktop
when configured, otherwise the first configured source. A selection scopes list and New Chat,
invalidates old reads and never launches a native session. Chips retain their existing 40dp height,
8dp status dot, secondaryContainer/onSecondaryContainer treatment, no outline and bounded ripple.
No visible Online, Connecting or Offline label belongs inside the capsule. Selected semantics
identify the active source. Claude origin can distinguish Terminal and Desktop Code within rows.

Each dot reports its authenticated source status independently of gateway reachability. Legacy
Desktop uses `/v1/info` `desktop.state`: `connected` uses connected, `connecting` connecting,
`unavailable` error, and absent/unknown/disconnected neutral idle. Only a real native connected
snapshot establishes Online. Accessibility includes the localized status and source name.
Remote dots (device rows, source chips and the conversation subtitle) use three colors only:
Online primary, Connecting translucent primary, and the neutral idle gray for every other state,
including error/unavailable; no Remote dot renders red. Accessibility labels keep the real state.
Dot changes use a 200ms opacity Crossfade in fixed geometry, including Reduced Motion.
Chip selection colors animate over 200ms. The chip row applies the drawer list's alpha edge fade
horizontally (40dp, RTL-aware); each edge fades in over 200ms only after the row has scrolled more
than the drawer's 2dp tolerance away from that edge, so a flush edge stays crisp.
A successful gateway, model catalog or history read cannot fabricate native readiness.

The existing device directory owns at most one serial Desktop observer, scoped to the selected,
visible catalog or conversation, including an empty catalog and local New Chat. Initial gateway
admission supplies its first snapshot; later info reads start after the normal three-second interval.
They share the existing two-permit read admission, coalesce around a pending gateway check and never
start session, model or native mutations. Hiding, device/owner selection changes and editor entry
cancel the previous observation; exact client and observation generation reject late results. A new
chat starts only this delayed read observer and creates no native session or immediate request.
Failed observation removes a stale Online claim using neutral idle without hiding history, changing
gateway health or replaying any input. An authenticated `desktop_unavailable` error immediately
marks Desktop unavailable before waiting for fallback history. It supersedes older observation or
pending health results, then resumes the same delayed observer. Explicit reconnect similarly fences
older periodic results before starting its coalesced check. Devices retains entry/save-only gateway checks. The chat
subtitle and empty guidance use the actual Desktop state; a gateway connection in progress/failure
can remove readiness but cannot establish Online. Native read admission, history and control retain
their independent runtime/gateway semantics. One bounded configured-source advertisement and
existing directory observer own source status; no generic provider/plugin registry is introduced.

Sessions keeps the already loaded catalog rows and continuation while a conversation is open.
Back renders that cache immediately; its background refresh replaces the previously loaded page
range atomically, never shrinking it to page one while later pages are pending. Failure retains
the complete cache. Successful visible-page refresh replaces that page's membership, so native
archives disappear instead of surviving a title-only merge; unrelated loaded pages stay present.
The catalog reuses SettingsScrollRetention for its numeric index and offset, keyed by device and
source/native scope. Outgoing transitions cannot overwrite the current page's retained position.
Local drawer rows already use the eager ViewModel snapshot and retained drawer composition;
no second local cache or scroll owner is introduced.

Sessions keeps row detail/More menus and read-only navigation available while rename, archive
or another native mutation is pending. Busy admission belongs to the conflicting mutation,
not the menu trigger or unrelated read-only controls. Opening a menu never submits a mutation.
Sessions automatically loads the next cursor at the laid-out list bottom. Preserve rows
and scroll position, reject repeated cursors, fence late pages and retain explicit error
retry. There is no Refresh or Load More button; pull-to-refresh on the catalog list performs the
existing explicit refresh, and its indicator shows only for that user pull while loading. The
shared Settings scaffold brings it down from the safe page top above the title, toolbar and list
(see settings-ui-ux.md). Switching source or moving between loading, empty and populated states
cross-fades the Sessions group over
200ms; edits within one populated list do not. The Sessions SettingsGroup always remains visible,
including empty, loading and failed reads. An empty loading list shows a 20dp/2dp circular indicator
with the localized Loading label in a standard SettingsItem. Once loading ends, an empty list uses
the saved-Memory empty SettingsItem reference: neutral icon/headline, muted supporting guidance and
64dp minimum height. Errors continue through Snackbar. Existing rows remain visible during refresh;
the empty loading item never replaces populated content. Sessions shows a bottom 4dp progress
bar for populated loading, paging or control work, without duplicating the empty-list circular state:
opacity enter/exit 300ms, fixed thickness,
initially hidden transition state, retained composition until exit finishes. No overlay.

Selecting history preserves its native ID and starts bounded reads and original-owner
subscription. Compose the original input bar immediately, allowing a local draft while
history and native state are pending. There is no history-admission registry, resume POST,
read-only composer notice or Continue this conversation button. Failed operations retain
readable content and report through Snackbar; they do not disable explicit Send retry.
Execution uses the verified original owner, or the explicitly admitted idle recovery
path above; it never takes over an active or unknown native writer.

Opening publishes only the latest bounded packet and settles at its bottom, including when
it contains only tool/thinking records. Never scan older packets for ordinary text or a group
boundary before publication. Older pages load only at the actual upward list edge; each
request publishes one bounded packet and never merges into an existing rendered fragment.
Thinking/Tool Call groups may span pages.
Legacy continuation hints never trigger group completion. Existing live-tail updates still
bridge genuine gaps before merging. Bodies stay in the original bounded LRU. Each node
retains its physical packet bookmark. Page boundaries add no Spacer or gap.
Same-turn assistant fragments omit duplicate message-shell padding and the 6dp/16dp
assistant spacers at their seam. Ordinary adjacent-message spacing stays identical in
total and belongs to the preceding item; the initial inset owns only the first leading
space. Prepending therefore never changes an existing fragment's internal content origin.
Older pages load on demand at the actual list edge; no
initial complete-history scan or per-message network waterfall. Page bodies prime the
original payload LRU before publication. Remote uses its existing byte budget rather than the
ordinary 16-row viewport entry cap, so a packet cannot evict its own small rows during admission.
Completed Markdown is parsed off the UI thread with the original parser, preprocessing and reference
links, then passed to the original renderer on its first frame. Parsed trees count toward the same
8 MiB payload budget; active streaming keeps its existing incremental path. Eviction re-reads bounded native page bookmarks.
Already admitted IDs and presentation-page membership remain resident and immutable when
older pages arrive or body caches are evicted. Existing rendered fragments never merge with
an incoming older fragment. ChatMessage.displayPageId expresses this boundary to the
canonical MessageList turn builder; ordinary null-boundary grouping remains unchanged.
Automatic history paging must compare the authoritative first message ID with the measured
index-zero LazyColumn key. Topology publication, scroll-isolated render publication and
measurement are separate steps; an old layout must not trigger another cursor request.
Regression verification must keep the pointer down across both publication steps, confirm
a real page arrived, and assert the original visible message and coordinates remain stable.
Include fast consecutive responses that otherwise push the anchor outside the lazy key map.
During a held pull at the history edge, page publication remains immediate. The original
OverscrollEffect retains its stretch: the same positive pull cannot start consuming newly
prepended rows and implicitly release it. Reversing the gesture or releasing uses the exact
original effect and physics. No delayed publication or requestScrollToItem correction.
LazyColumn stable keys preserve the current drag/fling position without a second scroll
actor, scrollToItem restoration, delayed correction or structural window trimming.
While an older page loads, the original 20dp/2dp circular indicator appears in the existing
top boundary inset with 300ms opacity enter/exit. Start exit immediately when the page is
published or the list can scroll back away from that boundary, even if a request is pending.
Its fixed slot never adds a list item or
changes content padding, message geometry, keys or scroll position.
The chat subtitle renders Online/Offline/Connecting beside the exact Devices McpStatusDot,
with its shared state colors; a literal bullet glyph is not a status indicator.
Hiding or reconnecting suspends control readiness but preserves the last native generation
presentation. Only a new native snapshot may complete a group and trigger auto-collapse.
Viewport mutation anchors use the actually measured visual key, never an old-layout index
into newly prepended rows. Drag ownership lasts until gesture Stop/Cancel even while held
stationary or generation has ended; hydration/card mutations cannot claim that viewport.
Original MessageList, body observation, Search highlights and scroll owners remain shared.
Explicit Search may read older pages with cancellation, without changing admitted positions.
Search publishes matches from the admitted hydrated bodies before waiting for older pages,
then updates the count in canonical message order as each bounded batch completes. A failed
older read reports through Snackbar and retains known matches; it must not turn visible
highlights into a persistent 0/0 result. Live text revisions refresh their own matches without
reloading unchanged bodies. Query/owner changes cancel stale work. Appending older matches
retains the selected match by identity, and Search never downloads image bytes.
Reconnecting updates the native tail without replacing the historical reading position.
Active-body observation is keyed to its owner/message and begins with the admitted cached
body. Suspending observation is not deletion. The original bounded MessageList payload
cache retains rendered streaming bodies for the transition back to ordinary observation;
returning must not replace the last answer with an empty stub or replay offline text.
A real user or different turn ends an assistant group. Remote DTOs remain separate from
ChatMessage/MessageSegment presentation; only public summaries and tool records are mapped.
Native imageView records expose only an opaque revision until their Tool preview is actually
expanded. That preview first reserves its fixed square geometry, then reads real image bytes by
authenticated message identity and Crossfades from its centered circular loading state to the
decoded image without resizing. Reuse ToolImageStore streaming validation/atomic files, original ToolImageAttachment thumbnails
and the existing root media preview. Image files live only in the private disposable Remote
cache (128MiB/64files, two concurrent image reads). Inline answer images use server-parsed
message/revision/index references and the original Markdown transformer with authenticated
private files; retain original Markdown text for copy/search and reuse root image preview.
Inline images reserve the original generated-image300dp square viewport before download,
with8dp corners and centered Crop. Publish each completed image independently. The overlay
uses the original motion-aware28dp/3dp circular indicator and200ms opacity crossfade on
entry and exit; cached images do not wait for another image. Failed reads settle into the
original broken-image presentation plus Snackbar, never an endless loading state. Geometry
and text remain unchanged through download, decode, failure and retry.
Metadata exposes only the image count. Native and ordinary durable tool images
remain untouched. Each image retains the original 20MiB media-store bound. Search reads text
without fetching images; missing/unsupported images preserve the card and conversation and
report a Snackbar. No base64 image data enters topology/SSE and no arbitrary path read is
exposed. Opening another session or changing the exact message revision cancels old hydration and
rejects stale results. Collapsed cards, body observation, paging and Search never download Tool
images; reopening an expanded preview reuses the private image cache.
Tool progress/results and genuine timing retain native semantics. Trim only terminal CR/LF
in presentation text, not interior whitespace, tool payloads or original cached records.
Supported native tool formats from all four sources reuse the shared detail components through
[tool-presentation.md](tool-presentation.md#remote-native-tools). Incomplete or unsupported payloads
retain their original JSON/plain display; this projection does not change the native records.

## Exact original ChatApp presentation

Reuse MessageList, Markdown/glyph fade, ChatTopBar, composer/TextField, message menus,
ChatScrollCoordinator, ChatLaunchInteractionEffects and bottom-scroll button directly.
No invented Remote renderer, scroll algorithm, keyboard behavior or animation.
Tool-call display follows the shared Timeline/Grouped policy in streaming-markdown.md. A retired
`compact` value resolves to Grouped before rendering; Remote has no independent Compact display branch.
The shared Grouped Thinking card and ordinary Context Compact features retain their existing owners.
The bottom-scroll button must use ordinary ChatApp's canonical visibility owner, including its
scroll-direction latch, not only the lower-level availability predicate. Each conversation starts
with the button hidden. Preserve the existing 32dp directional-travel rule, readiness, near-bottom,
auto-follow and competing-scroll exclusions. Do not add a Remote-only latch or visibility rule.
Unsupported message mutations are hidden; Copy, Select text and Info remain available.
Top More contains Search only, with original highlights and navigation. Absent/ID titles
display New Chat. Remote title subtitle uses the shared Devices status dot beside Online, Offline or Connecting
from the selected device's actual Codex Desktop state. Gateway Connecting/Offline overrides a stale
Desktop snapshot; only authenticated native `connected` establishes Online. It replaces context
usage there. The composer context
indicator retains native telemetry; unknown context shows an empty circular indicator
without a dash or fabricated numeric usage.
Remote context shows only one Used category in the theme's primary color. Reserved, System Prompt,
Tools, Messages and Free breakdown rows are absent: native telemetry supplies no classification.
Do not infer categories or reserves from ordinary-chat defaults. Ordinary ChatApp context breakdown
and compaction-reserve behavior remain unchanged.

The chat loading cover is the original content-area ChatApp block before the composer:
48dp/5dp circle, 200ms opacity. Center it in ChatApp's same measured top-bar/bottom-bar available
range, including IME changes, not in the whole body. Its lifetime is initial history opening/scroll
settling, ended by readiness or a definitive error after the admitted fallback/health reads. Empty
foreground retries reuse this same cover, including local-draft retries; automatic background recovery,
loaded-history recovery and ordinary settings changes do not restart it. The cover itself blocks touch to covered content through its
visible enter/loading/exit lifetime, as required by application-ui.md; a background alone is not
enough. No Remote-wide cover, separate interception layer, or loading cover for ordinary settings
changes/generation is allowed. Devices/Sessions retain their own behavior.

The native active turn plus native user acknowledgement drives streaming presentation.
Never create the assistant dot before input exists. Appended SSE snapshot text supplies
StreamingTextDelta boundaries to the original fade tracker; initial history/rewrites do not
invent token events. No artificial typing timers. A history-first inactive observation cannot
permanently close a card: later authoritative generation reactivates it through the original
expansion animation and layout-mutation owner. Continuous activity never repeats an expansion.
The last card is active only when generation
is active AND no newer block lies below it; stale tool state cannot animate a middle card.
Use the original active-card expansion/collapse. Unknown completed thought duration uses
exact English fallback Thought for a while; when the card contains tools it remains
Thought for a while, called X tools. The fallback only replaces the duration, never the
native tool count. Genuine timing retains original duration text.

Errors and unknown runtime never disable an explicitly supported Send with nonblank input,
except an unresolved non-exact Claude Mod submission, whose resend barrier remains closed.
Unavailable or unsupported source/session operations do not acquire a control capability through
an error or history read. Filo decides whether the original owner can accept an admitted request;
HTTP/SSE failures use stable Filo error
codes to select an actionable Snackbar in the current application locale. Unknown codes and
older servers retain the bounded native detail beside a localized category. Keep the detail
in the transient notice for diagnosis; never log this text or credentials. Every default
error key must exist in every supported locale. Codes never authorize automatic mutation retries.
A rejected or unconfirmed send retains its draft. Exact-receipt sources use a text-only Snackbar
with no action; Claude Mod uses the same existing Snackbar for actual input failure and uncertainty.
An uncertain result says that delivery could not be confirmed, never that non-delivery is proven.
Receipt state remains internal; no input receipt label is shown in the composer.
The next explicit exact-receipt Send admits one fresh attempt with a new client ID after UNKNOWN.
Claude Mod UNKNOWN blocks resend, including a fresh client ID; only later valid observed evidence
can resolve it. SUBMITTING and ACCEPTED coalesce duplicate taps. Refresh, reconnect and navigation never replay
input. Old receipts and late results cannot confirm a newer attempt, clear its draft or report failure.
Reuse original ComposerSendButton: active + empty draft means Stop, text means Send/steer,
submission/Stop settlement means Busy. Keep pending Stop through both HTTP success and
native end/replacement of that exact turn, regardless of arrival order. Failure clears
pending presentation without pretending idle. Exact client-ID confirmation requires the matching
native turn and user-message identity. Claude Mod instead requires its matching accepted command
and a new same-registration native item/turn with full matching text, and remains explicitly non-exact.
Bounded `inputReceipts` carry that evidence independently of the visible page; an
unrelated receipt or an off-page text match cannot acknowledge a send. Native
`completedTurnId` survives executor retirement in runtime and catalog statuses.
`notLoaded` alone cannot settle a turn or clear a pending Stop. Client-ID confirmation clears only unchanged
submitted text, after the original Busy acknowledgement, and emits one original animated
scroll. Navigation and repeated reconciliation cannot duplicate it.

## New Chat, models and settings

New Chat is local until Send: immediate original keyboard, no background creation or immediate
request. Its visible Desktop-status observer follows the delayed info-read contract above.
Its empty body follows the WebUI homepage welcome: the selected source's mark (OpenAI or Claude)
at a fixed 80dp,
16dp mark-to-title spacing, and a 28sp Bold headlineMedium title with 35sp line height and
onBackground color. In an online local draft, confirmed-empty history, or pending/reconnecting empty
body, that title is the selected source's technical product name; never show the central Connecting
headline. A negotiated source that is disconnected but explicitly permits creation keeps this welcome
for its local draft while the reachable gateway and top bar retain their actual status. This does not
grant control over existing history or make an unavailable source ready. An 8dp gap separates the title
from the selected device's configured name in
bodyMedium/onSurfaceVariant, centered with at most two lines and ellipsis. Resolve that name through
remoteDeviceName and the localized Device fallback; never derive it from an address or hostname,
or hard-code a particular device. Device selection/name changes immediately update the subtitle.
Offline and failed-read states retain their localized state headline and existing safe Retry action,
with the same selected-device subtitle. The top bar continues to show actual connection status.
The empty body and Retry do not own an entrance animation. A retained failure takes precedence over
transient Connecting during automatic recovery, keeping the same headline, Retry and geometry.
Explicit retry keeps that body under the original content-area loading cover: one 48dp/5dp circular
indicator, 200ms opacity entrance (including initial composition), then 200ms retained exit.
The existing loading flag spans the foreground read, same-owner fallback and health result; settle
failure before removing the cover. Repeated explicit retries coalesce. A draft retry uses the same
cover even though normal New Chat entry has no cover. Reduced Motion retains opacity and the shared
stationary indicator policy. The cover blocks covered input through entry/loading/exit; the composer
remains independently editable. Background recovery after a definitive result does not restart the
cover or animate/remove Retry. Loaded history stays readable during recovery and does not acquire
an empty-state retry cover. Loaded messages and a missing session remove the empty body immediately.
Keep the block inside the original measured
top-bar/composer range, including IME changes. Measure the actual title, subtitle, gaps and Retry
at their natural height, preserving text and action geometry for large fonts, long names and RTL.
The mark is never shrunk. The whole block is vertically centered in that range with no maximum
height; when it exceeds the available height, ordinary vertical scrolling keeps the mark, every
detail and Retry reachable inside a clipped viewport bounded by that same top-bar/composer range.
The empty body shows no read-only or history-snapshot text. Initial history opening keeps only the
original single centered loading indicator and its gesture barrier. It displays no connection hint,
welcome mark, device subtitle, action or additional scrolling content below that indicator.
Empty guidance never hides loaded messages or replaces the original composer. Explicit reconnect on an offline
local draft performs only the coalesced authenticated device check, preserving its identity, text,
attachments and model/settings choices. Repeated requests coalesce, and late checks retain existing
client/selection fences. Entering New Chat itself starts no immediate check, creation or Send.
Within chat, New Chat replaces the current conversation in place using the original title,
composer and scroll owners; the enclosing settings navigation must not create another page.
The Sessions FAB enters that same local draft. The original attachment plus/menu offers
Photos and Files with raw draft copies; Camera and Videos are absent. Picking does not
upload or create a native task; upload begins only on Send. Model catalog reads admitted by prior navigation
may finish; draft entry/refresh does not start them. Actual model loading says Loading…,
not Model Unavailable. Display cached native defaults or local choices without fabrication.
Automatic model-catalog failures retain the current content and privacy-safe diagnostic,
without another Snackbar on navigation or refresh. Explicit model-menu retry reports its
failure through the existing Snackbar. Successful native metadata reads remain available
for first Send without claiming native Online or creating an empty session.

Both original and Remote model dropdowns share the leading check for the selected model.
Remote always retains the attachment plus, model selector and original bottom-bar details
button, including while loading or using a read-only source. Thinking remains visible and
is independently disabled when its capability, model options or runtime admission is unavailable.
Service Tier is hidden when the selected source does not advertise `serviceTier` settings,
including both Claude sources. An advertised tier row stays visible but disabled while its
model options or current session grant are unavailable. Without a model capability the selector
shows a disabled Model Unavailable entry. The composer shows no read-only or snapshot notice line.
The same capability fence applies to an already-open sheet if capabilities change.
Remote Thinking and Service Tier use plain rows in the original details dropdown. Those rows
open the original bottom sheets and slider panels; neither the dropdown nor panel exposes a
toggle. Remote sheets omit the panels' top header/description item and its spacing.
Native None, when supported, and default tier remain selectable on their sliders.
Unknown current values do not open a falsely preselected panel.
Ordinary ChatApp defaults and panels remain unchanged. Ultra remains Ultra. Unsupported
none/budget/tier options are absent/disabled; unknown current values stay unknown.
Explicit Thinking and Service Tier choices update their presentation optimistically and keep
both controls usable while native confirmation is pending. Native runtime remains authoritative;
this transient projection must not fabricate an applied native setting or overwrite runtime.
For one exact native access binding, serialize setting mutations and merge newer unsent choices
into a bounded latest patch. Shared canonical draft identity never merges pending settings across
source paths. An unresolved old-path mutation blocks conflicting new-path admission; no queued
patch transfers to another source. Omitted fields stay unchanged and explicit `serviceTier:null`
selects native Default.
Each dispatched patch is attempted once. Exact settings sources require native application/read-back
for success; Claude Mod reports accepted submission as pending until a subsequent native request
proves the actual model/effort. Never replay a mutation automatically. Older snapshots or acknowledgements cannot replace a
newer optimistic choice. Failure removes only the affected optimistic choice, restores the last
confirmed native value, settles the original panel feedback gate, and reports through the existing
Snackbar. A stale failure cannot roll back a newer choice or affect a different selected owner.
For Claude Mod, releasing the native settings submission barrier permits the next Send while
request confirmation remains pending, because that next turn supplies the required evidence.
The Thinking panel retains the accepted pending choice for its exact granted access binding;
this presentation never replaces the actual runtime effort. Confirmed or failed request evidence
settles the choice back to actual native values. An exact Claude effort-only grant remains usable
when fresh registration or native resume leaves runtime status unknown; it neither fabricates
an idle state nor grants model changes, Stop or control on another source.
The composer never renders native settings pending/confirmed/failed labels, request receipts,
raw mismatch values, or other diagnostic markers. Confirmation remains internal; setting failures
use the existing Snackbar. Other sources keep Send behind outstanding native application.
Draft choices remain local until first Send. Unknown tier
is distinct from confirmed Default (`serviceTierKnown:true` with null/default).
Native model catalogs define model IDs. For Codex, Remote displays the nonblank native model
name with hyphens replaced by spaces in both the selector and its menu (`GPT-6.1-Sol` becomes
`GPT 6.1 Sol`). Other families retain their native names. A missing catalog entry or blank native
name uses the shared model-alias display fallback; neither path changes the model ID sent to Filo.
CLI session access retains source-level read-only model metadata while execution capabilities
remain bound to the admitted native task. Claude Code reads model metadata from its selected
executable's native initialize response, preserving IDs, labels and effort metadata; Mod aliases
never override that catalog. Claude Mod's named effort controls reflect the official request
rewrite API; they do not assert model-specific support. An admitted existing Claude session with
an exact effort grant keeps request effort controls when its actual runtime model has no catalog
entry or the catalog is unavailable. Prefer advertised named efforts, falling back to the request
API's low/medium/high/xhigh/max enum; never invent an alias mapping or replace the actual model.
A missing session effort grant disables the control. Other sources use their native catalog's
supported efforts, tiers and defaults. Original Claude Mod sessions advertise effort only and
refuse model mutations until a provider-ready native catalog and switching path are qualified.
Claude Desktop's `config.list` aliases are not its true `modelSelectorConfig` catalog; the current
helper has no supported read-only getter or alias resolver for that catalog, so its model catalog
route is unsupported. CLI models must never substitute for Desktop's native directory.
Settings apply to subsequent turns.
Draft choices apply after one native creation and before first Send. If settings fail after
known creation, explicit retry uses the same native ID. Unknown creation/send retains the draft;
the next explicit Send admits a fresh client ID without an acknowledgment step or automatic replay.
The owner-approved creation flow uses native APIs without desktop control, preferring
Desktop IPC and using its bundled runtime when that creation capability is unavailable.
Verify native task identity, initial settings and first-input receipt; an empty task is
not delivery. First-send qualification requires actual production routing and independent native lifetime
evidence; installation status belongs in the delivery record. Desktop page switching is forbidden.

Read-only HTTP requests may recover from a closed pooled connection on a fresh connection.
This includes catalog, history, model, image and event-stream reads. Native mutations
(creation, Send/steer, Stop, settings, rename and archive) must never be automatically
replayed after an ambiguous transport failure. Authentication failures and redirects
retain their existing boundaries. Verify both read recovery and exactly-once write attempts
against a server that closes a warmed connection before response headers.

## Memory limits and verification

Native failed-turn messages use the original assistant error segment and MessageStatus.ERROR,
which render through the original neutral grey GenerationErrorBar/GenerationTerminalText.
Preserve preceding answers and tools, and keep one stable error identity per native turn.
Transport errors remain Snackbar notices. Missing live state must never invent a native error
or mark a failed turn as generating. Error messages do not disable an explicit Send retry.
Errors and delivery uncertainty use the existing Snackbar. Input receipt states are internal only;
no submitting/submitted/observed/non-exact receipt label belongs above the composer.
Native settings confirmation and input receipt states stay internal; normal chat exposes no
request-status or debug lines. UNKNOWN keeps the draft editable and Send blocked; no retry or acknowledgment
button is added. Other sources retain text-only Snackbar delivery feedback and explicit fresh
attempt semantics. Automatic input replay remains prohibited.

Filo bounds conversation pages to 3MiB/128 records and complete response bodies to 4MiB.
Tool arguments/results are decoded before leaf fields are limited (256KiB text target,
384KiB serialized field cap); nested JSON strings remain parseable and truncation is marked.
Android limits SSE lines/HTTP bodies to 4MiB before UTF-8 allocation, including missing delimiters.
Retained payloads use a bounded cache with native replay bookmarks. Eviction cannot make
any valid conversation permanently unenterable; both older and newer content remain readable.
Long text parts preserve native identity, Unicode and internal whitespace. No larger heap or
OutOfMemoryError recovery. Native records remain intact. Layered pages must not duplicate
desktop live turns; off-page native user acknowledgement still supports valid streaming.
Older history does not acquire active-tail presentation or get replaced by incoming SSE.
Paging starts only at the actual list edge, never merely because a huge first bubble is visible.
Prepending primes the bounded Remote body cache before publishing topology. MessageList must
read that prepared body synchronously for its first composition; an asynchronous Flow emission
must not insert an empty, short-lived row into an active drag. The original local caller keeps
its existing hydration path. Verify repeated 300ms pulls with no pause across page arrivals.

Verify auth, exact native capability/owner/turn routing, bounded transport/history,
cancellation, stale results, original renderer bindings, local-first Devices and persistence
races. Use focused tests while iterating, then the full build and matching deployment.
Service startup, unit tests, APK hash/install and actual UI acceptance are distinct evidence.
Physical phone UI acceptance belongs to the owner; no taps/screenshots/UI dumps are implied.

Sessions rows expose the original three-dot management dropdown only when that session's
verified access supports Rename or Archive. Unsupported actions and an empty management entry
are absent, including ordinary Codex CLI sessions without either grant. Supported actions stay
visible but disabled during a pending mutation. Capability loss closes the affected menu/dialog.
Codex Desktop reuses ChatRenameDialog/ChatDeleteConfirmDialog with archive-specific copy. The owner requires
only Codex thread/archive, preserving history and synchronizing the desktop archive
notification. Neither desktop ownership nor generation disables Archive. Update/remove
a row only after confirmed native success; failures use Snackbar and never hide the task.
Permanent deletion, local record deletion and stopping a writer are prohibited. Cancel stale list reads before a mutation;
late results cannot alter another selected device and repeated pending clicks do not resend.

## Raw attachments and delivery gate (2026-09-12)
Remote picks photos and files with the shared attachment menu and preview row. It
never offers video, extracts documents, converts images, or uploads when New Chat
opens. Private draft copies preserve the source bytes. Sending first uploads
bounded 256 KiB chunks, confirms size and SHA-256 on the selected target, then
submits only server-issued attachment identities. Filo resolves those identities
to raw target file references; unconfirmed uploads must not reach native input.
Failures remain snackbar messages and cannot erase a draft or replay a native send.

The Sessions header opens native account Usage; New Chat lives in the bottom-right
FAB. Unknown usage stays unavailable. Reading usage never consumes reset credits.
Inline image blocks reserve the complete square plus 8 dp above and below before
decoding, and keep exactly that geometry after decode.

Owner release gate: all requested Agora features and the complete regression must
pass before any further Agora deployment. Partial feature builds are development
checkpoints only. The Filo runtime assembly, actual encrypted upload/native-input
round trip and owner-visible phone acceptance remain separate required evidence.


## Usage presentation correction (2026-09-13)
Retain the existing Usage bottom-sheet layout and ordering. Use a bold title, semibold
model labels and an 8dp progress bar. Center the circular loading indicator horizontally
and vertically inside the 96dp loading content area below the title. Format window durations into localized days,
hours and remaining minutes without discarding precision. Loading-to-content fades
and sheet height growth animate over the shared 250ms transition; reduced motion
keeps opacity feedback but removes spatial growth. The original modal entrance
remains the sheet owner. The Sessions New Chat FAB is circular and 64dp across, with effective 24dp
end and bottom spacing beyond the system navigation inset, counted only once.
The scrollable list reserves 104dp below its last row: the 64dp action, 24dp
bottom inset and a 16dp clear gap. The final row must scroll fully above the FAB.
Remote exposes the native ultrafast tier when advertised for that device/model, keeps its wire ID unchanged and uses the localized Ultra Fast label. Do not invent availability for another model/account.
While Remote Search is open, matches and count may update as pages arrive without moving the viewport. Automatic positioning is admitted once after input settles; a page, hydration or result refresh cannot renew it. Explicit Previous/Next still positions the requested match. Selection uses match identity, not a stale index into a new list. An ongoing seek must resolve the current index of that identity as pages are prepended.
