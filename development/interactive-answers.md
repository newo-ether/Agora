# Interactive answers

Owner request: optional interactive answers inside native Agora, reusing Open Intelligent
UI's browser renderer patterns. Device profiles and template management are outside v1.

## Ownership and flow

Settings DataStore owns `interactive_answers_enabled` (default false); portable settings
export/import/reset includes it. `GenerationRequestBuilder` freezes the value in the existing
`GenerationContext`. The built-in `render_interactive_answer` ToolProvider validates content
and returns ordinary model-facing fallback text plus an explicitly structured artifact.
Existing mailbox, tool-round and Room owners remain authoritative. No separate generation
backend, conversation graph, AG-UI client, Python agent or visualization router is introduced.

The tool accepts title, description, fallback, CSS, HTML, JavaScript functions and ordered
JavaScript expressions. `InteractiveAnswer` is the versioned persisted artifact; it lives in
existing structured segment JSON. Failed, stopped, incomplete, foreign and malformed results
never become executable. Ordinary Markdown HTML stays literal. No Room migration is required.
The transport parser rejects inputs over 1 MiB. The tool additionally rejects argument and
artifact JSON above 80,000 UTF-8 bytes before completion: Agora's generic result capture clips
at 100,000 characters and its aggregate segment budget is 600,000 bytes. These limits are not
bypassed. Existing aggregate-history trimming can make old interactive content unavailable;
the viewer must never execute partial code, and readable tool output remains the fallback.

## Presentation and bridge

A completed artifact renders automatically inside its native chat card, with an optional
Expand interactive view action. Both presentations use `InteractiveAnswerSurface`: one trusted
local host and one packaged Websandbox opaque-origin iframe per visible surface. Chat cards
reserve their last measured height but dispose offscreen browser instances. A ResizeObserver
reports content height; native layout bounds it to 160–900 dp and taller content scrolls inside
the renderer. Closing, scrolling offscreen or removing a branch destroys ports, callbacks and
WebView; returning reconstructs initial content and resets local controls. Selection/branch
removal revokes the expanded viewer. No progressive preview, cross-turn DOM patching or durable
control state. The host anchors its frame to the viewport with fixed positioning; Android
WebView can measure root percentage heights as zero despite a nonzero viewport. DOM/ready
handshakes alone do not prove visible frame bounds. Only drag controls claim exclusive touch ownership: trusted DOM pointerdown on an HTML range
input, role=slider element or explicitly marked data-agora-drag-control element signals the
existing private native port. Scoped touch-action:none prevents browser panning on these controls.
The shared native FrameLayout accepts a claim only during its current real touch sequence,
uses Android requestDisallowInterceptTouchEvent and always clears ownership on UP/CANCEL.
Ordinary page areas retain Chromium's normal vertical scroll/interception decisions, allowing
chat scrolling when the page does not claim the gesture. Buttons retain normal tap handling.
This adds no global drawer switch, DOM geometry cache, touch overlay or alternate event routing. Both presentations share native confirmation and the ordinary follow-up owner.

Reuse upstream theme/form/SVG styles, import map, separated code fields and the final-frame
sandbox pattern. Use Kotlin for Android and plain JavaScript for the host; no maintained
TypeScript application. External libraries/images use upstream's explicit HTTPS domains.
Private files/content URIs, downloads, extra windows and arbitrary frame navigation are blocked.
Only about:blank/about:srcdoc subframe navigation is accepted for the sandbox document;
top-level and external frame navigation remain blocked. The host normalizes omitted optional
CSS/function/expression fields from Kotlin's persisted JSON. Native bootstrap accepts a null
or main-window source and rejects foreign frame sources. Diagnostic logs record renderer stages,
sizes and console severity/line numbers, without generated text or credentials.
Generated code has no direct Android interface, credentials, storage access or native port.
Import-map support is required; unsupported/hung/failed renderers show a readable fallback.

The only functional bridge methods are upstream-compatible `sendPrompt({text})` and
`openLink({url})`. Follow-ups require a user gesture, valid 1–4000-character text and native
confirmation of the exact text. Duplicate pending submissions are refused. Native submission
revalidates one saved artifact in the selected originating conversation and uses ordinary
foreground send/queue admission, preserving Composer text and attachments. Requests never
retarget another chat. HTTPS links open in the external browser after validation.

Native archives and forks preserve artifacts through existing segment serialization. The
public Markdown formatter includes only the readable fallback of this explicitly declared
interactive-answer artifact; generic tool payloads, reasoning and executable code stay private.
This is the artifact-public-content exception to the generic tool omission rule.

## Verification

Cover opt-in/snapshot behavior, malformed/oversized payloads, completed-only recognition,
ordinary persistence and native archive/fork behavior, public fallback-only sharing, owner
revocation, cancellation and existing tool execution identity gates. Browser tests cover a
calculator, SVG, omitted default fields, inline height changes, sandbox isolation, validated actions, repeat open and offline failure, plus
an optional live Chart.js import. Run both flavor unit suites, build-logic tests, Kotlin size
gate and affected APK builds. Browser/JVM checks do not establish physical-device acceptance.
