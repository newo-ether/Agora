# Agora Assistant — Layer 1 Spec (Gemini-replacement, client build)

**Status:** draft v0.1 · **Scope:** ships to the client base · **No OS/privileged changes**
**Package:** `com.newoether.agora` · minSdk 26 · targetSdk 36→37 (see §6)

---

## 1. Goal

Make Agora usable as the device **assistant** — a more capable, BYOK/agentic
replacement for Gemini — using only **normal, user-granted** capabilities. No
system-app status, no signature permissions, no custom OS. Everything here runs
on a stock or GrapheneOS device the user controls and installs onto.

Layer 2 (privileged device control via a signed XNet OS) is explicitly **out of
scope** — a separate personal project.

### What "assistant" means here

Three user-granted capabilities layered on Agora's existing brain (BYOK models,
MCP, local ONNX, shell/ssh tools):

1. **Assistant role** — Agora becomes the system-invoked assistant (home
   gesture / power button), replacing Gemini as the default.
2. **Screen awareness + on-screen action** — via an AccessibilityService, the
   assistant can read screen context and act on the user's behalf.
3. **On-device tool exposure (AppFunctions)** — Agora exposes its capabilities
   as AppFunctions so the system agent can orchestrate them, and consumes other
   apps' AppFunctions (API 37; alpha — see §5).

---

## 2. Capabilities & Android mechanisms (all user-granted)

| Capability | Mechanism | Permission / grant | Min API |
|---|---|---|---|
| Be the default assistant | `VoiceInteractionService` **or** assist `Activity` + `ROLE_ASSISTANT` | `RoleManager` user grant | 26 |
| Invoked by gesture/button | system assist trigger → our assist entry point | (comes with role) | 26 |
| Assistant overlay UI | `SYSTEM_ALERT_WINDOW` (or assist session UI) | user grant | 26 |
| Read screen content | `AccessibilityService` + `canRetrieveWindowContent` | user grant (Accessibility settings) | 26 |
| Act on screen (tap/scroll/text) | AccessibilityService gestures/actions | (same) | 26 |
| Context from current app | Assist `onHandleAssist` / `AssistStructure` | (comes with assist) | 26 |
| Expose Agora tools to agents | AppFunctions (`@AppFunction`) | none (alpha lib) | 37 |
| Foreground presence | FGS (already present) | existing | — |

**None of these require system-app status.** Accessibility + assist role is how
third-party assistants (e.g. Tasker-style, other LLM assistants) operate today.

---

## 3. Architecture

```
            system assist trigger (gesture / power button)
                              │
                     ┌────────▼────────┐
                     │ AgoraAssist     │  VoiceInteractionService /
                     │ (assist entry)  │  assist Activity (ROLE_ASSISTANT)
                     └───┬─────────┬───┘
            screen ctx   │         │  user intent
        ┌────────────────▼──┐   ┌──▼───────────────────┐
        │ AgoraA11yService  │   │ AssistantController   │
        │ (read + act on    │   │ (bridges assist UI to │
        │  screen)          │   │  Agora's agent brain) │
        └───────────────────┘   └──────────┬────────────┘
                                            │
                              existing Agora engine:
                              BYOK models · MCP · tools · ONNX
                                            │
                     ┌──────────────────────▼─────────────────────┐
                     │ AgoraAppFunctions (@AppFunction wrappers)   │  (API 37, guarded)
                     │  expose Agora tools to the system agent     │
                     └─────────────────────────────────────────────┘
```

**Key design rule:** all assistant surfaces (assist entry, accessibility,
AppFunctions) are **thin front ends** that call into a single
`AssistantController`, which reuses Agora's existing agent/tool engine. No tool
logic is duplicated in the assistant layer. This is the same "one controller,
many front ends" rule used in portmux-mcp/switch-mcp.

---

## 4. Components to build

1. **`AgoraAssistService`** — the assist entry point. Options:
   - **A (recommended):** `VoiceInteractionService` + `VoiceInteractionSessionService` → full assist (gesture invoke, `onHandleAssist`, overlay session). More work, the "real assistant" experience.
   - **B (simpler):** an assist `Activity` with `ACTION_ASSIST` intent-filter + `ROLE_ASSISTANT`. Faster, less integrated (no session UI niceties).
   Spec targets **A**, with B as a fallback if VIS proves heavy.
2. **`AgoraAccessibilityService`** — `AccessibilityService` with an XML config
   (`canRetrieveWindowContent=true`, `canPerformGestures=true`). Exposes:
   read current screen tree, find/act on nodes, perform global actions
   (back/home/recents), inject gestures. Wrapped behind `AssistantController`.
3. **`AssistantController`** — bridges assist/accessibility input to Agora's
   agent engine; formats screen context into the model's tool/context input;
   returns actions to execute. Enforces the **confirmation policy** (§7).
4. **`AgoraAppFunctions`** — `@AppFunction`-annotated wrappers over Agora's
   high-value tools (e.g. "ask Agora", "run agent task", "summarize screen").
   Guarded behind API 37 + a feature flag (§5).
5. **Role onboarding UI** — a setup screen guiding the user to grant: assistant
   role, accessibility, overlay. Clear, revocable, explained.

---

## 5. AppFunctions: alpha-guarded

`androidx.appfunctions` is **alpha** (1.0.0-alpha13, Oct 2026). Risk of shipping
alpha to the whole client base. Therefore:

- AppFunctions support is **compile-time optional** (its own Gradle module or
  flavor) and **runtime-gated** on API ≥ 37 and a feature flag (default off for
  the stable client build until the lib stabilizes).
- The assistant-role + accessibility core (API 26+, stable) ships **without**
  depending on AppFunctions. AppFunctions is additive.

---

## 6. SDK / build

- Assistant role + accessibility: work at **minSdk 26** — no change needed.
- AppFunctions: requires **targetSdk 37** and the alpha lib. Bump `targetSdk`
  36 → 37 **only** when enabling the AppFunctions module; the core assistant
  ships at 36.
- No new signing, no flavors beyond the existing `play`/`fdroid` (AppFunctions
  can be a third flavor or a `:appfunctions` module to keep it out of the
  stable build).

---

## 7. Safety & trust (ships to clients — must be conservative)

Agora is BYOK and agentic: model/MCP input is effectively untrusted (prompt
injection, a malicious endpoint, a compromised MCP tool). An assistant that can
**read the screen and act on it** is a powerful surface even without system
privilege. Required mitigations in this layer:

- **Explicit, revocable grants.** Nothing auto-enables. The user grants
  assistant role, accessibility, and overlay separately, each explained.
- **Confirmation for consequential on-screen actions.** Destructive or
  high-impact actions (sending, purchasing, deleting, auth) require a visible
  confirm step; the model cannot silently drive them. Read/summarize is freer;
  act-on-screen is gated.
- **Screen content stays local by default.** Screen context captured via
  accessibility is sensitive. It is sent to the user's chosen model endpoint
  (that is the point) but: (a) never logged, (b) redaction pass for obvious
  secrets (password fields, OTP), (c) a clear indicator when the assistant is
  reading the screen.
- **Kill switch.** A single toggle disables all assistant surfaces at once.
- **No background screen reading.** Accessibility access is used only during an
  active assist session the user invoked, not continuously.

These are product-level guardrails, not OS enforcement — appropriate because
this is a normal app, but necessary because the capability is real.

---

## 8. Privacy / policy

- Update `PRIVACY.md`: accessibility usage, screen-content handling, that it is
  sent to the user-selected model endpoint, and the redaction/retention stance.
- **Play Store:** accessibility-using assistants face strict review; the
  `fdroid`/direct-download flavors are the safer distribution path for the full
  capability. Play flavor may ship the assistant role without the accessibility
  act-on-screen piece if review requires.

---

## 9. Acceptance criteria (Layer 1 v1)

1. User can set Agora as the **default assistant**; the assist gesture/button
   invokes Agora's assist UI.
2. Assist UI can read the **current screen context** and pass it to Agora's
   agent brain; the model can answer questions about what's on screen.
3. With accessibility granted, the assistant can **perform a requested
   on-screen action** (e.g. "scroll down", "tap the send button") — with the
   confirmation policy (§7) enforced for consequential actions.
4. All grants are explained, separately toggleable, and the kill switch works.
5. Core ships at **targetSdk 36**, no alpha deps; AppFunctions is a guarded
   add-on at targetSdk 37.
6. No system-app status, no signature permissions, builds/installs as a normal
   APK on the GrapheneOS test device.

### Out of scope (Layer 1)

- Privileged device control (secure settings, input injection beyond
  accessibility, silent install, controlling other apps directly) — Layer 2,
  personal, signed-OS only.
- Always-on hotword/voice.
- Replacing the lock-screen/power-button assistant on devices that don't allow
  reassigning it.

---

## 10. Implementation status (branch `feat/assistant-layer1`)

**Decision refinement:** the stable core ships **assist-API screen capture
only** (via `VoiceInteractionSession.onHandleAssist` → `AssistStructure`).
The `AccessibilityService` *act-on-screen* piece (§4.2, §9.3) is **deferred** to
a later opt-in: reading the screen through the assist API alone is proven
(ported from the aiope assistant) and lower-risk than an always-granted
accessibility service. This keeps the core conservative for the client base.

### Shipped in this branch (stable core, Option A — VoiceInteractionService)

All under `app/src/main/java/com/newoether/agora/assistant/`:

- `AssistantRole` — `RoleManager` helper (`isAvailable` / `isHeld` /
  `requestIntent`) for `ROLE_ASSISTANT`.
- `AgoraVoiceInteractionService` — always-on VIS that qualifies Agora as the
  device assistant.
- `AgoraVoiceInteractionSessionService` / `AgoraVoiceInteractionSession` — the
  assist session: captures the foreground app's visible text on
  `onHandleAssist` (password/secret fields redacted), then hands off.
- `AgoraRecognitionService` — minimal stub (assistant is invoked by the assist
  gesture, not voice; offline STT can replace this later).
- `AssistInvocation` — launches `AssistantService` (FGS) with the captured
  screen context.
- `AssistantService` — specialUse foreground service that runs one turn through
  the engine (satisfying the FGS-importance gate) and posts the result as a
  notification.
- `AssistantController` — the single bridge; calls
  `TaskExecutionEngine.runOnce(..., requestKind = "assistant")`, inheriting
  Agora's full tool/MCP/sub-agent capability set. No tool logic duplicated.
- `AssistantOverlayService` — optional, user-granted floating invoke button
  (`SYSTEM_ALERT_WINDOW`); tapping it launches an assistant turn.

Resources / manifest:

- `res/xml/voice_interaction_service.xml`, `res/xml/recognition_service.xml`.
- Manifest: 5 assistant services wired (VIS, session, recognition,
  `AssistantService`, `AssistantOverlayService`) + `SYSTEM_ALERT_WINDOW`.

Onboarding UI (§4.5):

- **Settings → Assistant** (`SettingsAssistantPage`, in the Tools group):
  requests the assistant role, toggles the floating button, and drives the
  overlay permission. Grant state is re-checked on return from each system
  dialog.

Build: `:app:assembleFdroidDebug` green at **targetSdk 36**, no alpha deps.

### Still open (Layer 1)

- Acceptance §9.3 (act-on-screen) — deferred with the AccessibilityService.
- A single **kill switch** toggle (§7) that disables all assistant surfaces at
  once (today: unset the role + turn off the overlay).
- Confirmation policy (§7) only applies once act-on-screen lands.
- AppFunctions seam module (§5), guarded, later.
- On-device verification on the GrapheneOS test device.

