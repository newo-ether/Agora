## Current native folder contract — 19 October 2026

The Android implementation now supports arbitrary file types, optional recursive catalogues and
on-demand paginated directory navigation. Native source paths are bounded by the persisted folder
grant rather than a fixed Markdown whitelist. The Alpine paragraphs below describe compatibility
behavior only. Current ownership, bounds and verification are in
[interactive-templates-verification.md](interactive-templates-verification.md); the public API is
in the English/Chinese Templates manual. `TemplateFolderApi` owns typed requests and metadata;
`TemplateAndroidSources` owns SAF resolution/reads, `TemplateFileAccess` verifies current card
ownership and `TemplateReferenceAttachments` stages sources through ordinary attachment processing.

# Interactive template library

Templates are user-owned reusable interfaces. The library reuses InteractiveAnswer and its
sandbox; it does not introduce another generator, renderer or provider pipeline.

## Storage and transport

InteractiveTemplate v1 contains a UUID, name, description, a legacy optional shortcut,
optional Alpine source directory, and a validated InteractiveAnswer payload. Legacy linked-Skill
references remain read-compatible; new workflow routing belongs to Skills. No application-specific
shortcut or example is registered automatically. The interface retains the 80,000 UTF-8-byte
persistence limit; a single portable JSON file is bounded to 100,000 bytes. UTF-8 imports are
strictly decoded. Library catalogues are capped at 16 MiB and atomically replaced under one
process-wide mutation fence. Corruption is reported; it never silently clears stored resources.

Save as template in the expanded viewer snapshots a completed generated answer or saved template
card. It revalidates the originating artifact and saves only the reusable interface and source
directory. It strips native workflow routing and injected source/outcome expressions. The inline card offers
only Expand. The expanded Save action blocks repeat taps during saving and after success until the
viewer is reopened. Duplicating
assigns a new identity and clears the shortcut. Metadata edits and interface replacement preserve
identity. Legacy shortcuts remain readable for archive compatibility, but are not exposed in the editor or composer. Editing metadata clears the legacy shortcut. New descriptions are capped at ten words; existing long descriptions remain readable.

A folder-backed template declares `sourceDirectory` under `/mnt/shared` or `/home/agora`.
`TemplateSources` uses the existing enabled Alpine SandboxManager to list up to 500 Markdown
paths, at most five levels deep, and inject only their relative filenames as `agoraSourceFiles`.
Native cards snapshot those paths in `resourceFiles`. A trusted gesture can select at most one
`sourceFiles` path. Native code validates it against that snapshot, checks canonical containment
with a fixed shell-quoted read-only command, then reads only that file (128,000-byte limit).
The final Skill submission stores selected contents as ready file attachments through the existing
message payload path; it does not paste their Markdown into the visible message. Missing, moved,
empty, unauthorized or oversized sources reject submission. Neither HTML nor the
model gets a general storage bridge. Listing and menu interaction need no provider request.
Legacy app-private resource declarations and linkedSkill remain readable through their old resolver.

A Skill may configure Before template, After template and next Skill filename. `SkillTemplateFlow`
is native card metadata, never a library template or HTML authority. Before opens locally; Start
prefixes the selected Skill’s instructions and passes collected inputs plus selected file
attachments through existing foreground send admission. The `invoke_skill` registration remains
the owner of tool-call provenance. `complete_skill` validates a real selected-history invocation token and its
frozen document, then returns the configured After interface as an ordinary tool artifact. It is
explicit completion of the entire Skill, not an assistant-turn/Stop/settlement hook. The summary is
injected as `agoraWorkflowInput`; template code contains no routing. After submission invokes the
configured next Skill (or opens its Before locally first). It carries actual parent provenance and
rejects cycles/depth overflow, stale cards, repeated submissions and changed conversation owners.
Without a next Skill it returns the collected data to the ordinary conversation. The existing
generation, Room graph, tool capability loading, cancellation and queue owners remain authoritative.

The native `templates` category stores `templates/catalog.json`. Older archives without it leave
the library unchanged. Merge preserves ID/shortcut conflicts and reports them; Replace validates
the entire incoming catalogue before replacing only this library. Update replaces matching IDs and adds new templates while retaining entries absent from the file.
Native automatic backups expose
the category alongside other resources. Existing explicit backup selections are not rewritten.

## Opening and provider boundaries

Typing `/` or `/skills` offers Skills only. A Skill can open ordered Before/After templates
through native workflow configuration. Legacy template names and shortcuts cannot dispatch a form
or shadow a Skill. Templates are not registered in the AI tool capability directory.

Settings → Templates is the library entry point; there is no chat drawer Templates action.
It uses the shared Settings list/detail navigation and an explicit-save editor like Skills.
The detail page edits name, short description and source directory separately from the complete
InteractiveAnswer JSON (HTML, CSS, JavaScript and other interface fields). Copy/paste enables a
chat-assisted code round trip without exposing or replacing the template UUID or Skill routing.
Save validates the whole template and compares the original catalogue entry before atomic
replacement. Invalid or stale edits leave the stored template unchanged. Back discards the draft.
Import/export, duplicate, replace from file and delete remain available in the library.

Preview renders the current validated draft in the existing expanded interactive sandbox.
Folder-backed previews list source filenames using TemplateSources, but never read their
contents, add conversation segments, change composer drafts, invoke Skills or admit generation.
The native sandbox rejects sendPrompt before opening follow-up confirmation. Preview is explicitly
labelled; code, metadata and source preparation failures stay visible in the editor.

Skill-owned local opening retains the existing graph placement path below.

Local opening takes the existing conversation execution lock, then commits one immutable
`interactive_template` segment in a completed local content Run. LOCAL_TEMPLATE_OPENED identifies
its origin; it does not fabricate a model completion, provider tool call or active generation slot.
The DAO rejects malformed local content and concurrent live Runs in the same transaction. New-chat
preferences/settings and selected branches use the existing atomic graph insertion boundary.
No provider, Compact or title-generation call is made.

Historical `open_interactive_template` tool results remain decodable, but list/open template
tools are no longer registered for model discovery. Local cards and historical tool-opened cards
use the same artifact decoder and renderer. Provider projection excludes wholly local cards and strips template
structured code from model-facing tool segments; readable tool results remain available.
Completion history retains only a small native token receipt, preserving duplicate rejection without
carrying the review interface or routing settings into provider context.

Template cards snapshot their content, linked Skill and declared resource references. Library edits/deletion do not
change old cards. Existing renderer control reset, owner revocation, Websandbox restrictions and
e-ink presentation still apply. The native browser touch host retains vertical gestures begun
inside an interface while its scroll chain can move in the finger’s direction. A sandbox helper
reports root and touched nested-scroll limits through the validated message bridge, independent of
e-ink eligibility. At a boundary the native host allows chat interception, including mid-drag.
Pointer cancellation during Chromium touch panning retains the active target until touch end.
Sliders keep ownership; horizontal non-control gestures can yield to the drawer.
Only Expand appears below an inline interface; Save lives in the expanded toolbar. A Skill-owned template Run gesture advances its workflow without a second
confirmation. Library previews cannot initiate an AI request. Native artifact provenance selects this behavior for both inline and expanded views;
interface JavaScript cannot select it. Generated answers retain confirmation. Start rechecks card ownership, configured Skill availability and selected source before
normal Send. The existing host still requires an active user gesture and permits only one pending
follow-up; loading or changing controls cannot submit automatically.
Composer drafts remain separate. No arbitrary interface code obtains Android storage access.

## Ownership and checks

- templates/: format, validation, repository and shortcut resolution.
- ui/templates/: user CRUD and file pickers; no provider calls.
- InteractiveTemplateController: local graph placement and existing execution-lock coordination.
- InteractiveTemplateToolProvider: retained decoder/test compatibility; not registered for AI use.
- Existing interactive owners: sandbox, inline/expanded presentation, verification and confirmation.
- Existing archive owners: additive template category and normal backup selection.

Tests cover storage, conflict/failed replacement preservation, local/provider boundaries, real
Room graph admission, composer bypass, library metadata editing, archive/fork preservation and
browser interaction. Both flavor tests, Kotlin size/build-logic checks and APK builds remain required.
Host tests do not establish Android physical-device acceptance.

## Nested-scroll references

Android documents the WebView/Compose nested-scroll limitation in
[Wrap a WebView in Compose](https://developer.android.com/develop/ui/compose/migrate/interoperability-apis/wrap-webview-in-compose#nested-scrolling).
[Compose nested-scroll interoperability](https://developer.android.com/develop/ui/compose/touch-input/scroll/nested-scroll-modifiers)
requires cooperating views; an opaque iframe needs its own geometry report. This implementation uses
that report to select touch interception rather than trusting the outer WebView scroll range.
Chrome describes boundary propagation as
[scroll chaining](https://developer.chrome.com/blog/overscroll-behavior); CSS alone cannot transfer
gestures into Agora’s native chat container.

Tool visibility follows the shared [on-demand capability contract](message-generation.md#on-demand-tool-capabilities). The template library is excluded from that directory; other enabled capabilities retain their normal deferred discovery.

Preview preparation failures remain in the Settings editor with their actionable underlying reason.
Failed preparation never clears the library or mutates a template. Cancellation revokes the editor's
preview work; no detached preview result can change a chat or a saved template.
