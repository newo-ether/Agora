# Interactive HTML Preview

Native Agora chat and browser WebUI recognize completed `html-preview` fenced blocks as interactive HTML cards.
Ordinary `html` fences keep their source and expose a Run action. Unclosed fences never execute.
At a live tail, a closing delimiter needs its completed line; already stable parser blocks and
terminal messages use their existing AST fence-end token. Search retains the original source and
source offsets. Raw HTML outside fences is still literal Markdown text. Remote sessions remain
source-only. No second Markdown parser or generation lifecycle is introduced.

Cards reuse the shared Material 3 Expressive code shell, header and actions defined in
[streaming-markdown.md](streaming-markdown.md), retaining Source/Preview and Copy. Both inline and
fullscreen headers expose Save HTML directly as a download icon. In reading order, inline actions
are Source/Run, Copy, Save HTML, More; fullscreen actions are Source, Reload, Copy, Save HTML, Close.
More and Close stay last, including when whole actions wrap at narrow widths.
The overflow menu contains Reload and Full Screen with 24 dp leading icons; native also exposes Share HTML.
It does not duplicate Save HTML. Browser banners and overflow actions use the same Code,
PlayArrow, Download and Fullscreen icon shapes as Compose. The loading overlay includes the
localized `Loading HTML preview…` text beneath its indicator with a 12px gap in WebUI.
The inline viewport is 360 dp with internal scrolling;
fullscreen fills the safe window inside an 8 dp inset using the same rounded shell. The fixed viewport avoids positive feedback for `vh`
layouts. The native WebView has explicit MATCH_PARENT width and height layout parameters before
attachment. Compose's measured viewport alone is insufficient: its default WRAP_CONTENT parameters
make Chromium force the document layout height to zero and collapse the full-height sandbox frame.
A theme-surface loading overlay covers the entire canvas during image preparation, trusted admission
and document loading. It fades in and out over 180 ms without changing viewport geometry. The native
WebView starts INVISIBLE with the same theme background; only the admitted root's onPageFinished
followed by postVisualStateCallback may reveal it and dismiss the overlay. Probe completion alone
never reveals content. Released or stale visual callbacks are ignored. The overlay blocks canvas
pointer input until its exit completes, without consuming ancestor scrolling, while source/export
controls remain available. Reduced motion uses the shared stationary progress indicator and retains
opacity feedback. Main-frame load errors and unsupported providers leave the existing localized
fallback instead of a permanent loading layer.
Offscreen, background and disposed cards release their WebView and cancel resource work.
Reload, re-entry, fullscreen transitions and process death reset interaction state. There is no
Room or localStorage persistence. Export uses CreateDocument or the existing FileProvider shared
cache, with no shared-storage permission. Original source is exported unchanged.

Generated HTML runs in a sandboxed iframe with scripts allowed but without same-origin, forms,
popups, top navigation, downloads or native interfaces. A unique synthetic HTTPS origin serves only
that preview's wrapper, document and declared raster images. Response-header CSP applies before any
untrusted byte. File/content access, mixed content, storage, permissions, file pickers and automatic
external navigation are disabled. Explicit user HTTP(S) link activation can open the system browser.
Native controls own export; generated pages cannot export or call Agora tools.

HTTP interception alone does not block WebRTC. Before any generated content or remote resource is
served, the same WebView loads trusted opaque control and blocked-policy frames. Without starting
ICE, each constructs an RTCPeerConnection with pool size zero, reads ICE-server configuration,
sets the same configuration and reads it again, then closes. The control must retain one server
in both readings; the blocked response must retain zero in both. Native evaluateJavascript reads
only the trusted wrapper's authenticated result. Only `1,1:0,0` admits that WebView instance;
exceptions, missing callbacks, timeout, disposal and stale callbacks fail closed. Unsupported
providers show localized WebView-update guidance and retain access to source/export controls.
Generated wrapper/document responses enforce Connection-Allowlist with `webrtc=block` as well as
CSP. This is a tested Chromium capability check, not an assumption from a version string.

Only HTTPS static library paths on cdnjs.cloudflare.com, cdn.jsdelivr.net, unpkg.com and esm.sh,
and numeric tile.openstreetmap.org tile paths are permitted. A dedicated credential-free HTTP client
fetches bounded responses and rejects redirects, unexpected types, private addresses and unsupported
methods. No cookies, HTTP auth, referrer or app API credentials are forwarded. Fetch/WebSocket/beacon,
workers, nested frames and arbitrary remote images are blocked by CSP. Approved CDN access is a
restricted network capability, not a proof against every exfiltration channel. Each preview admits
at most 128 total requests, four concurrent resource loads, 8 MiB per network response and 32 MiB
combined resource bytes. Failed attempts count; local streams reserve and enforce their byte limit.
Closing cancels calls and closes local streams. No network fallback is returned to WebView.

Quoted literal `<img src="shell://...">` attributes are rewritten to per-card image capabilities before
rendering. Comments, raw-text elements, other attributes and unquoted sources are not capabilities.
At most 16 distinct paths are admitted, through the existing display-only ShellMarkdownMedia owner.
Only inspected raster formats are served. No wildcard shell endpoint, arbitrary local path, SVG
document or access to message/app storage is exposed. Images have no CORS authorization so script
cannot read their pixels into an export or network request. Raw HTML export preserves shell
references; those require Agora and are not portable browser file URLs.

Browser source/export controls remain available. WebUI uses the same completed-fence admission and
shared code shell, with a 360px inline viewport and the existing retained fullscreen modal. Search
shows the original code and source offsets. Completed running cards remain connected when later
streaming text is appended. Source, reload, fullscreen, offscreen, background and disposal release
the old browsing context and resource capability; re-entry starts fresh.

WebUiHtmlPreview issues an unguessable, session-bound, short-lived resource capability. Source stays
browser-local. Each visible entry loads fresh response-header sandboxed control/blocked documents
and authenticates both opaque frame sources after their initial loads. Only `1,1:0,0` admits a
one-shot parent-source/origin-checked source message. `document.open/write/close` retains the
response's CSP, opaque sandbox and path-bounded Connection-Allowlist with `webrtc=block`.
The generated document becomes visible after its load, behind a 180ms loading fade; reduced motion
uses a stationary shared indicator. Initial trusted probe loads do not reveal the frame. After
admission, only the first `document.write` document load may become ready. Any successor document
load, including a browser error document from blocked navigation, removes the browsing context,
releases its resource capability and shows the existing localized fallback. Failure is terminal
for that entry; only an explicit reload or a normal fresh entry starts another admission.
Unsupported browsers fail closed with Source, exact Copy and Save HTML available. Browser preview
is capability-tested rather than inferred from a version.

Literal HTTPS `script[src]`, `link[href]` and `img[src]` attributes for the approved CDN hosts and
numeric OSM tiles are rewritten using inert parsing of literal resource start tags. Complete
html/head/body tags and attributes, raw-text elements and comments are retained; copy and export
retain the exact original bytes. The server
reuses HtmlPreviewResources URL/type/DNS/redirect/credential limits, request/byte budgets and
cancellation, and forwards no app/session credentials. Resources have CORS authorization for opaque
script/module/image loads, but response framing is denied. Generated fetch, WebSocket, workers,
frames, forms, popups, navigation to app APIs and unrestricted external loads remain blocked.
CSS URL/import syntax, JavaScript-created absolute URLs, module import specifiers and `shell://`
images are not rewritten by WebUI; these references may fail under the restrictive policy. This is
a supported static-resource subset, not full native resource parity. No arbitrary file proxy exists.

## Transparent instructions

Interactive HTML guidance is a normal editable CUSTOM text item in the structured System Prompt.
The editor offers an explicit insert action; duplicate insertion is disabled. Removing the item
removes the instruction from Provider requests. Changes persist only on the editor's existing Save.
The shipped Default includes the guidance; only exact unmodified historical defaults migrate.
Customized prompts are never rewritten and no runtime/settings fallback silently injects guidance.
A persisted `builtInMigrationVersion` records completed migration or an explicit saved edit. Startup
requires both an older version and exact historical contents. Removing the guidance retains that
version, so deletion survives subsequent startups even when the remaining text equals the old default.
The marker is metadata only and is never rendered into Provider requests.

## Ownership and verification

ChatMarkdownCode owns fence integration and source/search/copy continuity. HtmlPreviewCodeContent
owns controls, HtmlPreviewBrowser visibility/lifecycle, HtmlPreviewWebView native browser restrictions,
HtmlPreviewResources request admission and bounded loading, HtmlPreviewDocument documents/policies,
HtmlPreviewProbe trusted admission, HtmlPreviewImages static image capability extraction and
HtmlPreviewExport native export. These are cohesive security or UI side-effect boundaries; they do
not own Provider requests, messages, device configuration, chat lifecycle or a new persistence store.

Exercise completed vs partial fences, manual vs automatic entry, unchanged source/search/copy,
strict URL/type/redirect/credential boundaries, resource budgets, local image capabilities, renderer
cleanup and default/custom prompt migration. Full build remains required. Robolectric verifies native
policy and wiring; a fresh intercepted browser fixture verifies opaque origin, WebRTC admission and
blocking, image pixel isolation and fixed `100vh` geometry. Android phone UI acceptance remains
owner-controlled and is reported separately from build/deployment evidence.
