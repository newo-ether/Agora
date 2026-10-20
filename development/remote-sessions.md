# Remote Sessions

Pi extension: the owner-authorized PC learning runtime is described in [pi-remote.md](pi-remote.md).
Its Go bridge advertises agent=pi; the existing Codex ownership rules below remain specific to
the Codex adapter. Shared transport, presentation and receipt invariants apply to both.

Status: current owner-approved Filo integration, 2026-09-11. Earlier staged plans and
superseded UI decisions are retained in Git and the single active task log.

## Ownership and protocol

Remote connects Agora to an independent Filo service. The current Codex adapter supports
the original desktop owner for execution and bounded read-only native history for browsing.
Isolated native helpers may handle peripheral operations such as model catalog, rename
and archive. Filo's REDLINES.md and HARNESS.md govern ownership and qualification. Never patch Codex,
redirect its global backend, write native transcripts or acquire a competing writer.
Failed desktop discovery/dispatch cannot fall back to another host, queue or fork.
Every operation prefers verified Codex Desktop IPC; only an established capability
gap permits an additional unchanged Desktop-provided runtime. No separate Codex CLI
installation or desktop page/focus/input automation is allowed. Filo-created tasks may
execute on that runtime. An existing idle task may be recovered only inside an explicit
Send or settings request after verifying the native account, idle state and absent
IPC owner. Recheck ownership before allocation and preserve native resume writer-lock
conflicts. Browsing, subscriptions, active state and unknown outcomes grant no writer
authority. An existing IPC owner always takes precedence.
Filo service stop/crash/kill/removal must not affect normal Codex or accepted native
execution. Independent completion and writer release require actual qualification.

Remote transport, saved connections and transient presentation are independent of Room,
LlmProvider, GenerationManager and ordinary conversation drafts. Codex is durable truth.
Only the selected session owns Remote history/subscription. Changing selection, connection,
visibility or read generation cancels old reads and rejects late results. In-flight writes
stay bound to their original owner. No automatic POST retry or input replay is allowed.

Protocol 2 uses the shared authenticated encrypted channel, an explicit service address,
native-steer delivery and live-messages output. Bearer authentication is private-loopback
only; public callers cannot bypass the encrypted envelope. Idle Send starts a native turn; active Send steers the exact
original turn. Stop requires its native active ID. HTTP acceptance and native client-ID
visibility are separate confirmations; unknown delivery is reconciled without blind resend.
Native mutations have a 210-second mobile read/whole-call budget, enclosing Filo's
180-second private request, 90-second executor readiness and 60-second protected
creation budgets. Ordinary reads retain their shorter timeout. Cancellation still
closes only the request; a timeout never grants permission to replay accepted input.
Native approvals remain with their owner. Remote supports raw photo/file uploads as defined
below; approval handling, transcript editing, branching and tool-execution controls remain unsupported.

## Devices and local persistence

Root Back must leave Agora through Android's normal activity behavior, never reopen
a retained page. Task-history return is available only while that exact preview is
the displayed destination; pending, failed, stale and returning previews cannot
intercept Back on the ordinary/new-chat home. Remote Back still unwinds the selected
conversation, device and overlay in their existing order.

Remote sits below Tasks in their original joined drawer group and opens SettingsOverlayHost.
Both drawer buttons use the owner-requested 46 dp height, joined 24/5 dp corner shapes and
2 dp inter-button gap. Their icons, labels, focus clearing and navigation remain unchanged.
Device selection precedes Sessions; Add Device is a separate shared settings page.
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
Visible entry/save starts coalesced connection checks; no periodic device-health loop.

Load failures use the original application Snackbar, positioned by the original composer
inset owner in chat and the system inset on Devices/Sessions. Preserve the current page and
loaded content; no permanent inline error rows. Read retry is bound to the current selection
and cannot replay sends, settings or other writes.

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
Sessions. Immediately below it, a horizontally scrollable chip row currently contains one
informational Codex Desktop chip with the existing OpenAI icon and an 8dp status dot. The chip
has a 40dp-high capsule shape with secondaryContainer/onSecondaryContainer colors, no outline,
and a bounded press ripple. Press feedback
never navigates, selects a provider, changes connection state or sends a request; the chip retains
its informational accessibility semantics. The dot
reflects the selected device's existing authenticated Filo connection state: connected is Online,
connecting is Connecting, otherwise Offline. It does not claim a separate native Desktop writer
health check. Keep the chip visible during load, failure and native mutations; status uses a
200ms opacity Crossfade in fixed geometry, including Reduced Motion. Do not add discovery,
provider registries, extra requests or inactive future providers for this presentation.

Sessions keeps row detail/More menus and read-only navigation available while rename, archive
or another native mutation is pending. Busy admission belongs to the conflicting mutation,
not the menu trigger or unrelated read-only controls. Opening a menu never submits a mutation.
Sessions automatically loads the next cursor at the laid-out list bottom. Preserve rows
and scroll position, reject repeated cursors, fence late pages and retain explicit error
retry. There is no Refresh or Load More button. Sessions alone shows a bottom 4dp progress
bar for actual loading/paging/control work: opacity enter/exit 300ms, fixed thickness,
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

## Exact original ChatApp presentation

Reuse MessageList, Markdown/glyph fade, ChatTopBar, composer/TextField, message menus,
ChatScrollCoordinator, ChatLaunchInteractionEffects and bottom-scroll button directly.
No invented Remote renderer, scroll algorithm, keyboard behavior or animation.
The bottom-scroll button must use ordinary ChatApp's canonical visibility owner, including its
scroll-direction latch, not only the lower-level availability predicate. Each conversation starts
with the button hidden. Preserve the existing 32dp directional-travel rule, readiness, near-bottom,
auto-follow and competing-scroll exclusions. Do not add a Remote-only latch or visibility rule.
Unsupported message mutations are hidden; Copy, Select text and Info remain available.
Top More contains Search only, with original highlights and navigation. Absent/ID titles
display New Chat. Remote title subtitle uses the shared Devices status dot beside Online, Offline or Connecting
from its real connection/read state, replacing context usage there. The composer context
indicator retains native telemetry; unknown context shows an empty circular indicator
without a dash or fabricated numeric usage.
Remote context shows only one Used category in the theme's primary color. Reserved, System Prompt,
Tools, Messages and Free breakdown rows are absent: native telemetry supplies no classification.
Do not infer categories or reserves from ordinary-chat defaults. Ordinary ChatApp context breakdown
and compaction-reserve behavior remain unchanged.

The chat loading cover is the original content-area ChatApp block before the composer:
48dp/5dp circle, 200ms opacity. Center it in ChatApp's same measured top-bar/bottom-bar available
range, including IME changes, not in the whole body. Its lifetime is initial history opening/scroll
settling, ended by readiness or error. The cover itself blocks touch to covered content through its
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

Errors and unknown runtime never disable an explicit Send with nonblank input. Filo decides
whether the original owner can accept that request; HTTP/SSE failures use stable Filo error
codes to select an actionable Snackbar in the current application locale. Unknown codes and
older servers retain the bounded native detail beside a localized category. Keep the detail
in the transient notice for diagnosis; never log this text or credentials. Every default
error key must exist in every supported locale. Codes do not authorize mutation retries. An unconfirmed previous delivery keeps Send clickable and presents the
existing check action in Snackbar before any new attempt; no automatic retry or duplicate send.
Reuse original ComposerSendButton: active + empty draft means Stop, text means Send/steer,
submission/Stop settlement means Busy. Keep pending Stop through both HTTP success and
native end/replacement of that exact turn, regardless of arrival order. Failure clears
pending presentation without pretending idle. Client-ID confirmation requires the matching native turn and user-message identity.
Bounded `inputReceipts` carry that evidence independently of the visible page; an
unrelated receipt or an off-page text match cannot acknowledge a send. Native
`completedTurnId` survives executor retirement in runtime and catalog statuses.
`notLoaded` alone cannot settle a turn or clear a pending Stop. Client-ID confirmation clears only unchanged
submitted text, after the original Busy acknowledgement, and emits one original animated
scroll. Navigation and repeated reconciliation cannot duplicate it.

## New Chat, models and settings

New Chat is local until Send: immediate original keyboard, no background creation/request.
Within chat, New Chat replaces the current conversation in place using the original title,
composer and scroll owners; the enclosing settings navigation must not create another page.
The Sessions FAB enters that same local draft. The original attachment plus/menu offers
Photos and Files with raw draft copies; Camera and Videos are absent. Picking does not
upload or create a native task; upload begins only on Send. Model catalog reads admitted by prior navigation
may finish; draft entry/refresh does not start them. Actual model loading says Loading…,
not Model Unavailable. Display cached native defaults or local choices without fabrication.

Both original and Remote model dropdowns share the leading check for the selected model.
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
For one native owner, serialize setting mutations and merge newer unsent choices into a bounded
latest patch. Omitted fields stay unchanged and explicit `serviceTier:null` selects native Default.
Each dispatched patch is attempted once and requires native application/read-back for success;
never replay a mutation automatically. Older snapshots or acknowledgements cannot replace a
newer optimistic choice. Failure removes only the affected optimistic choice, restores the last
confirmed native value, settles the original panel feedback gate, and reports through the existing
Snackbar. A stale failure cannot roll back a newer choice or affect a different selected owner.
Keep model switching and Send behind outstanding settings so they cannot overtake the user's
chosen parameters. Draft choices remain local until first Send. Unknown tier
is distinct from confirmed Default (`serviceTierKnown:true` with null/default).
The native model catalog alone defines supported efforts, tiers and defaults.
Settings apply to subsequent turns.
Draft choices apply after one native creation and before first Send. If settings fail after
known creation, explicit retry uses the same native ID. Unknown creation/send stays guarded.
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
No delivery-status text/card belongs above the composer. Rejected/unknown delivery details
use the original Snackbar. Clicking Send with unknown prior delivery shows the existing
check action in that Snackbar; acknowledging it clears the guard without sending any input.
The action is labeled Allow Sending (localized), and the notice explicitly explains that it
only clears the warning and does not send. A new Send tap is still required.

Filo bounds conversation pages to 256KiB/128 records with explicit tool previews. Android
limits SSE lines/HTTP bodies to 1MiB before UTF-8 allocation, including missing delimiters.
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

Sessions rows expose the original three-dot dropdown with Rename and Archive and reuse
ChatRenameDialog/ChatDeleteConfirmDialog with archive-specific copy. The owner requires
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

## Session-only native Skill completion

The ordinary Remote runtime snapshot may optionally carry `skills`: an array of objects with
`name`, short `description`, and exact slash `command`. This is a backward-compatible extension;
missing/empty means no native Skill suggestions, never an invented capability or endpoint.
The computer-side adapter must obtain commands from the selected agent's own session/workspace
registry and preserve its native invocation syntax. Pi/Codex/other agent adapters stay server-owned;
the existing Codex-only connection qualification is unchanged. This checkout implements the Android
consumer, not an unqualified multi-agent execution backend.

Typing `/` in the connected Remote composer filters this metadata locally and fills the selected
native command without sending. Explicit Send follows the existing native Remote input path.
Only the selected live runtime supplies entries. Selection changes, hide, disconnect and read
failure already revoke runtime/control readiness and therefore remove suggestions; old history
packets and read-only fallback snapshots cannot populate the menu. No extra network request,
model call, local SkillManager write, Room entry or archive/cache of these skills is introduced.
Bound to 512 entries, validate single-command tokens, and deduplicate normalized names and commands.
Remote never merges local Skills/templates into native suggestions, preventing double entries and
accidental local invocation. Shared slash row presentation uses five-word descriptions, regular
theme styling, and the established static black/white e-ink controls and list paging.

Bridge payload example (inside the existing selected-session live snapshot):

```json
{"runtime":{"status":"idle","skills":[
  {"name":"clinical-osce","description":"Practice a focused clinical scenario","command":"/skill:clinical-osce"}
]}}
```

The command is illustrative: return the exact command supported by the connected native adapter,
not a syntax invented by Android. Do not reuse another session's directory/catalog. A bridge that
cannot safely dispatch native Skill syntax must omit these entries. Qualification of additional
agents includes their discovery, exact-owner dispatch, Stop, approvals and reconnect semantics;
this metadata extension alone does not authorize changing the Codex-only handshake.
