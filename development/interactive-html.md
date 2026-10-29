# Interactive HTML Preview

Native Agora chat recognizes completed `html-preview` fenced blocks as interactive HTML cards.
Ordinary `html` fences keep their source and expose a Run action. Unclosed fences never execute.
At a live tail, a closing delimiter needs its completed line; already stable parser blocks and
terminal messages use their existing AST fence-end token. Search retains the original source and
source offsets. Raw HTML outside fences is still literal Markdown text. Remote sessions and browser
WebUI do not enable this renderer. No second Markdown parser or generation lifecycle is introduced.

Cards reuse the code block surface and header, expose Source/Preview, Reload, Full Screen,
Save HTML and Share, and retain Copy. The inline viewport is 360 dp with internal scrolling;
fullscreen fills the available safe window. The fixed viewport avoids positive feedback for `vh`
layouts. Offscreen, background and disposed cards release their WebView and cancel resource work.
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
