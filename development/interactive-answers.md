# Interactive answers

Remote extension: [pi-remote.md](pi-remote.md) defines completed structured artifacts from the
PC Pi runtime and Remote-owned follow-ups. This reuses the existing viewer; it introduces no
AG-UI client or ordinary-generation backend.

Owner request: optional interactive answers inside native Agora, reusing Open Intelligent
UI's browser renderer patterns. Device profiles are outside v1. Reusable template management is described in
[interactive-templates.md](interactive-templates.md).

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
Generated code has no direct Android interface, credentials, unrestricted storage access or native port.
User-owned templates can use the bounded, read-only native folder bridge described below.
Import-map support is required; unsupported/hung/failed renderers show a readable fallback.

The action bridge retains `sendPrompt({text,sourceFiles})` and `openLink({url})`. Saved templates
also expose `listReferenceFiles`, `listDirectory`, `readTextFile` and `readFile`, bounded by the
folder selected in Android settings. See [the template verification contract](interactive-templates-verification.md).
Folder reads are local, read-only operations; generated answers without a native template binding
cannot use them. Follow-ups require a user gesture and valid 1–4000-character text. Generated
answers retain native confirmation; saved Skill templates use their explicit Start gesture. Duplicate pending submissions are refused. Native submission
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

## Conditional e-ink authoring and presentation

Only requests admitted while SettingsRepository.eInkMode is true receive the additional tool
contract. GenerationContext freezes that boolean alongside interactiveAnswersEnabled. The tool
stamps eInkAware from admission (never a model-controlled parameter), before checking the persisted
UTF-8 budget. The optional boolean defaults false; kind/version remain unchanged. Existing artifacts
are not edited or retrofitted. Archives/forks retain the marker through ordinary segment serialization.
Older strict decoders can reject the new optional field and use the stored readable tool fallback.

The shared native Surface/bootstrap carries current eInkMode and fontScale over the existing private
MessagePort. The host installs the app-owned eink-profile.js only inside eligible artifacts and
applies it before generated initialization. Effective style is current mode AND artifact.eInkAware.
Live display updates modify this same iframe, never reconstruct its inputs or restart generation.
Disposed ports and old browser callbacks remain revoked. Native loading feedback is static in e-ink;
expanded dialogs use existing no-dim/no-animation helpers. Unmarked HTML retains its original CSS/JS.

Eligible documents expose data-agora-display="default|eink" on document.documentElement and a
bubbling 'agora:displaychange' CustomEvent with detail.eInkMode, dispatched on document and
observed once by both document and window listeners. The bundled profile maps existing
Open Intelligent UI tokens to black/white, supplies readable base typography, visible focus,
selected/disabled/error cues and 48px controls; it removes decorative CSS motion/shadows. CSS is
scoped to e-ink mode. Meaningful CSS background imagery is retained alongside img/canvas content; avoid decorative
gradients through the conditional authoring contract. There is no image/canvas filtering, timer interception, requestAnimationFrame
patch, external framework dependency, or generated vendor-asset modification. Generated code must
cooperate for canvas/Web Animations/GSAP/D3 behaviour; this is not a universal compliance validator.

The conditional addendum is deliberately concise: mode detection/events, high contrast,
stable layout, meaningful infrequent updates, responsive inputs and preserved state. It does not
prescribe Step/Apply/Kick buttons, require a static final frame, ban timer/animation APIs, impose a
fixed update frequency or add library-specific recipes. User-controlled steps or intermittent
automatic progression may suit the task; controls must retain their purpose and visible feedback.
The existing ordinary tool description remains byte-for-byte identical to PR #154, verified
against its approved default-text fixture. The generic e-ink addendum is appended at its end
only for e-ink request admission; it asks for efficient viewport use and primary visualization,
controls and feedback together, with secondary detail disclosed when useful. No example-specific
widget styling or layout is added. Shared styling, typography, control
sizes and native paging remain host responsibilities. Generated code owns its meaningful update
cadence and document scrolling; the host does not rewrite its loops.

Overflowing eligible surfaces reuse native page controls. Native-only 'display' and 'page' commands
(type, direction='previous|next') use the private port; pageState returns boolean canPrevious/canNext.
Frame paging is immediate by its actual viewport minus three body lines, capped at half a short
viewport. Internal reportViewport/reportPageState callbacks provide presentation metrics only and
carry no send/navigation authority. Existing drag claims, user-gesture actions, confirmation,
allowlists, source validation and full fallback/error handling remain in force.

Verification adds conditional/stamped/snapshot and archive coverage plus actual packaged browser
fixtures for normal/e-ink styles, value/scroll identity on mode changes, sliders, SVG and live Chart.js,
an example Step simulation (not a required control scheme), large text, malformed paging, overlap, legacy isolation and existing security/gesture tests.
Physical panel acceptance remains user-owned. References: Mudita Mindful Design's E Ink Guidelines,
Intent's How to Design for E-Ink Devices, EinkBro and epaper-components; no source/library is copied.

Tool visibility follows the shared [on-demand capability contract](message-generation.md#on-demand-tool-capabilities). Enabled access makes tools discoverable; it does not send their schemas on every ordinary request.

## Native Skill draft review

The owner-requested Skill authoring flow reuses this viewer for completed propose_skill results.
InteractiveAnswer optionally carries a SkillDocument draft, validated under the Skills contract.
The provider supplies escaped deterministic markup and no script; only Skill modify access is
required. Generic render_interactive_answer rejects that field. Completed propose_skill recognition
requires a valid draft. Expanded native Review and save Skill opens the shared four-field editor;
it never exposes storage to the HTML bridge. Exact originating artifact verification precedes the
normal SkillManager create boundary. See [skills.md](skills.md).
