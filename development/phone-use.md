# Phone Use Contract

Status: owner-approved design, 2026-10-25. Implementation branch `phone-use`.

Phone Use lets an Agora conversation observe and operate the device UI through built-in tools.
It is a tool group, not a chat mode: no home-screen mode switch, no separate prompt, no separate
generation pipeline. A conversation can use it when the group is enabled, ready, and the caller is
admitted by section 5. Every other behavior (Run, Stop, queue, tool rounds, ask_user) stays on the
existing pipelines defined in [message-generation.md](message-generation.md).

## 1. Settings surface

Location: Settings → Tools → `Phone Use` item, following [settings-ui-ux.md](settings-ui-ux.md)
(collapsing scaffold, `SettingsGroup`, `SettingsItem` with icon/title/description).

Sub-page, top to bottom:

1. Master switch. Off hides every control below and removes all `phone_*` tools from requests.
2. Working mode selector using the owner's existing three-capsule chip component:
   `Accessibility`, `Shizuku`, `Root`. Exactly one mode is active.
3. Permission list for the selected mode. One `SettingsItem` per permission with a live granted /
   missing state indicator. Tapping an item requests the permission directly when the platform
   allows it, otherwise deep-links to the most specific system page. State refreshes on resume.
4. Automation access: one switch `Allow Task and Loop runs`, default off (section 5).
5. App access control (section 6).

Required permissions:

| Mode | Permissions |
|---|---|
| All | Display over other apps (`SYSTEM_ALERT_WINDOW`); notifications (Android 13+) for the control foreground notification |
| Accessibility | Agora accessibility service enabled |
| Shizuku | Shizuku installed, running, and permission granted to Agora |
| Root | `su` grant confirmed by a probe command |

Phone Use is `ready` only when every permission of the selected mode is granted. Tools are exposed
to the model only when enabled and ready. Readiness is re-checked at claim time; a lost permission
during control releases control with an explicit error result.

## 2. Tools

Naming follows the built-in rule: domain prefix first, detail after (`phone_*`). See
[message-generation.md](message-generation.md) built-in tool naming.

| Tool | Purpose |
|---|---|
| `phone_claim` | Acquire the global control lease. Required before every other `phone_*` tool. |
| `phone_release` | Release the lease held by the caller. |
| `phone_screenshot` | Capture the screen; returns the image plus its coordinate size. |
| `phone_ui_tree` | Compressed interactive node list with stable per-snapshot indices, bounds, text, role, state. |
| `phone_tap` | Tap by screenshot coordinates or by node index from the latest `phone_ui_tree`. |
| `phone_long_press` | Same targeting as tap, optional duration. |
| `phone_swipe` | Start/end coordinates and duration. |
| `phone_type` | Input text into the focused field. |
| `phone_key` | `back`, `home`, `recents`. |
| `phone_app_launch` | Launch by package name. Subject to section 6. |
| `phone_app_list` | Launchable apps (label + package), filtered by section 6. |

Rules:

- Coordinates in tool arguments are in the space of the latest returned screenshot (long edge
  ≤ 2048 per the existing media limit). The tool layer converts to physical pixels. Node index
  targeting is preferred in tool descriptions because it survives scaling.
- Action tools accept `screenshot` (default `true`); when true the result includes a post-action
  screenshot captured after a short settle delay, saving one round per step.
- Tool descriptions state current-mode limits (Accessibility screenshot rate limit and API 30+,
  `uiautomator dump` latency in Shizuku/Root, `FLAG_SECURE` windows capture black).
- Text input: Accessibility uses `ACTION_SET_TEXT` on the focused node. Shizuku/Root `input text`
  cannot carry non-ASCII; non-ASCII text uses clipboard set + paste, then restores the previous
  clipboard content.
- Any `phone_*` call other than `phone_claim` without a lease held by the caller fails with
  `not_claimed` and performs no side effect.

## 3. Global control lease

One process-wide lease owner. At most one active controller at any time.

- Identity: conversation ID + Run ID. A result is accepted only under the exact identity that
  issued it, per the core stale-result rules.
- `phone_claim` while held by another owner returns `occupied` with the owner conversation title
  and remaining lease time, no side effect. Re-claim by the current owner renews.
- Every successful `phone_*` call renews the lease. Lease length: 300 s idle.
- The idle timer pauses while the owner has a pending `ask_user` shown on the overlay.
- Release on: `phone_release`; idle expiry; user Stop button (overlay or notification, section 4.2); Run Stop or
  terminal settlement of the owning Run; owning conversation deletion; permission loss; screen
  lock; process death (lease is in-memory only and never restored).
- `phone_claim` fails when the device is locked or keyguard is showing.
- The lease decision is one serialized boundary; it is never held across tool execution, network
  or UI suspension.

## 4. Overlay

While a lease is held, Agora shows a foreground service with a `Stop` notification action and two
overlay windows (`TYPE_ACCESSIBILITY_OVERLAY` in Accessibility mode, `TYPE_APPLICATION_OVERLAY`
otherwise):

- Effect window: full screen, `FLAG_NOT_TOUCHABLE | FLAG_NOT_FOCUSABLE`, `FLAG_KEEP_SCREEN_ON`.
  Draws the border and the cursor.
- Control window: wraps only the capsules, Stop button, and ask card; touchable. Focusable only
  while the ask card has a text field focused.

Screen stays on through `FLAG_KEEP_SCREEN_ON` on the effect window; no WakeLock.

### 4.1 Border

- Inward alpha falloff from the screen edges, following the device corner radius. Color from the
  current theme primary.
- Enter: a reveal mask sweeps bottom to top. Inside the mask band alpha ramps 0 → 1 and glow
  ramps 0 → 1 → 0. The band edge may be slanted and broken up by Perlin-noise masking.
- Exit: the same animation reversed.
- Optional chromatic split is applied only to the border's own glow (RGB offset). Displacement or
  refraction of underlying app pixels is not possible: an overlay cannot sample other windows, and
  a MediaProjection feedback loop is rejected for latency and power.
- Reduced Motion: plain fade.

### 4.2 Capsules and Stop

Top-left, below the status bar: a duration capsule (elapsed since claim) joined to a status
capsule; the inner joining corners use a small radius. Status text: `Thinking` while the owning
Run streams model output, the action name during a tool (`Tap`, `Swipe`, `Typing`, ...),
`Waiting for you` during ask_user. On the same row at the right edge: a Stop button that releases
the lease without stopping the owning Run. The model is told the user revoked control: an in-flight
`phone_*` call returns `released_by_user`, and every later `phone_*` call from the same Run,
including `phone_claim`, returns `released_by_user` with no side effect. A later Run of that
conversation may claim again. User touches on the screen do not interrupt control.

### 4.3 Ask card

`ask_user` is reused. While the caller holds the lease, its questions are also presented on an
overlay card centered horizontally below the capsules: options plus an in-card text field.
Fade + scale in/out. Submission uses the canonical ask_user submission so the in-app and overlay
surfaces settle the same request exactly once.

### 4.4 Cursor

A solid circle drawn on the effect window at each action point, theme colored.

- Tap: appear, press (shrink), release (spring back), linger ~600 ms, fade out.
- Long press: press and hold with a slow breathing pulse for the duration.
- Swipe: press at start, move along the path with an eased curve matching gesture timing, release
  at end. No trail.
- Reduced Motion: static dot, fade only.

### 4.5 Screenshot hygiene

Before capture, hide the cursor and the control window (capsules, Stop, ask card), wait one frame,
capture, then restore. The border stays visible and tool descriptions tell the model a thin
colored border is Agora's control indicator.

## 5. Caller admission

- Interactive conversations and their Subagents may claim.
- Task and Loop runs may claim only when `Allow Task and Loop runs` is on. Otherwise `phone_*`
  tools are absent from those requests.

## 6. App access control

Exactly one of: `Off`, `Blocklist`, `Allowlist`. Lists are package names chosen from installed apps.

- Agora itself is exempt from app access control: it never appears in the app picker, cannot be
  listed, and is always allowed in every mode.
- Allowlist always implicitly includes the launcher and SystemUI so navigation remains possible.
- Foreground package is resolved before every action and before every capture: Accessibility from
  window state events and the active root; Shizuku/Root from `dumpsys activity`.
- A disallowed foreground app: no screenshot or UI tree is returned (privacy check precedes
  capture), the requested action is refused, and Agora performs `back`, re-checks, then `home` if
  still disallowed. The result reports `app_blocked` with the package name.
- `phone_app_launch` and `phone_app_list` honor the same rule.

## 7. Verification

- Unit: lease claim/occupied/renew/expiry/pause, stale identity rejection, release triggers,
  admission matrix, app access decisions including implicit allowlist entries, coordinate mapping,
  non-ASCII input path selection.
- Every `phone_*` tool without a lease performs no side effect.
- Full project build gate. Device UI behavior (overlay, animation, permissions deep links) is
  separate evidence requiring owner acceptance on the phone.
