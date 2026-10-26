# Phone Use Contract

Status: owner-approved design, 2026-10-25. Implementation branch `phone-use`.

Phone Use lets an Agora conversation observe and operate the device UI through built-in tools.
It is a tool group, not a chat mode: no home-screen mode switch, no separate prompt, no separate
generation pipeline. A conversation can use it when the group is enabled, ready, and the caller is
admitted by section 5. Every other behavior (Run, Stop, queue, tool rounds, ask_user) stays on the
existing pipelines defined in [message-generation.md](message-generation.md).

## 1. Settings surface

Location: Settings → Tools → `Phone Use` item, immediately after MCP and before Automation, following
[settings-ui-ux.md](settings-ui-ux.md) (collapsing scaffold, `SettingsGroup`, `SettingsItem` with
icon/title/description). The destination, Phone Use switches and working-mode selector use the
Material Symbols Outlined `mobile_hand` icon (24 dp, weight 400, fill 0). The control foreground
notification uses the same geometry. Native and WebUI individual Phone tool cards share it; mixed
tool groups retain the existing generic group icon. App access and Stop keep their own action icons.

Sub-page, top to bottom:

1. Master switch. Off hides every control below and removes all `phone_*` tools from requests.
2. Working mode selector: one `SettingsItem` (icon, title, description) whose trailing primary
   `labelLarge` text shows the current mode; tapping the row opens an `AgoraDropdownMenu` of
   `Accessibility`, `Shizuku`, `Root` with a primary check on the selected item. Same pattern as
   `SearchMethodItem` in `SettingsSearchPage.kt`. Exactly one mode is active. Shared choice values
   and permission statuses use the Appearance reference's 96 dp trailing slot, 4 dp end inset,
   end-aligned primary `labelLarge` text and one-line ellipsis. Granted/missing retains its semantic
   color; changing status or selection never moves the text column or its trailing edge.
3. Permission list for the selected mode. One `SettingsItem` per permission with checking, granted
   or missing status. The selected mode defines the rows immediately, including before the runtime
   is available. Pending Root probes show checking instead of a cached false/missing result, while
   other permission rows resolve immediately. Each page/resume/mode/click refresh owns its results;
   cancelled or old-mode probes cannot update a replacement projection. Status stays in the fixed
   trailing slot with the shared 250 ms crossfade. Tapping an item requests the permission directly
   when the platform allows it, otherwise deep-links to the most specific system page. Refresh only
   on page entry, resume, mode change or permission-item click; no periodic settings probes.
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
only when enabled and ready. Each admitted `phone_*` call takes one permission snapshot at entry.
Claim additionally checks overlay permission and runs one Root grant probe. While held, entries
never check overlay permission or re-probe Root; Root operations establish actual denial themselves.
Settings Root probes are independent and cannot overwrite the grant admitted by claim. Suspended
operation fences check only the lease epoch, exact Run identity and enabled/mode settings. A
transient readiness false while no tool is called never releases a lease.

There is no runtime polling monitor. Release events come from the existing Run termination callback,
`ACTION_SCREEN_OFF` / locked `ACTION_USER_PRESENT`, distinct enabled/mode settings changes,
Accessibility `onUnbind` / `onDestroy`, Shizuku binder death / denied permission result, and actual
platform permission failures. Old Accessibility instances and Shizuku bindings cannot release a
replacement owner. Accessibility instance absence and platform `SecurityException`, Shizuku binder
failure and Root `su` refusal/unavailability retain specific `permission_lost:<reason>` codes.
Ordinary command failure and timeout return tool errors without revoking the lease. Binding and
command-output waits are bounded; Root uses a unique shell-entry marker to distinguish `su` refusal
from a command error after entry, without parsing generic permission-denied text.

Every release logs tag `AgoraPhone` through the shared privacy-aware release diagnostic wrapper,
with only `reason`, `epoch`, `held_ms` and `mode`; logging failure never interrupts lease cleanup.
The in-memory lease
retains the most recent 32 releases. Subsequent tool results and claim refusals include `last_release`
for the latest release other than explicit release, user Stop or Run end. Disabled, mode changed,
missing entry permission and backend failures retain distinct reasons. These diagnostics establish
what released control; they do not establish an OEM-level cause without device evidence.

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
  targeting is preferred in tool descriptions because it survives scaling. For `phone_tap` and
  `phone_long_press`, a supplied node index takes priority and `x`/`y` are ignored, including optional
  coordinate defaults filled by the model. A stale snapshot or invalid node still fails explicitly;
  it never falls back to those coordinates.
- Action tools accept `screenshot` (default `true`); when true the result includes a post-action
  screenshot captured after a short settle delay, saving one round per step.
- Tool descriptions state current-mode limits (Accessibility screenshot rate limit and API 30+,
  `uiautomator dump` latency in Shizuku/Root, `FLAG_SECURE` windows capture black).
- Shizuku/Root UI trees dump to `/data/local/tmp/agora_ui.xml`, read the file and delete it on
  command exit, including failures. Remove any prior file before dumping; never return stale XML.
  Do not depend on an interactive terminal. Retry once only for `could not get idle state`;
  other command/XML failures return `ui_tree_unavailable` with up to 500 characters of raw output.
  Privileged command failures retain bounded stderr/stdout diagnostics, including a nonzero exit
  code; stderr-only output from an otherwise successful command remains available. Nonempty
  successful binary stdout remains unchanged.
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
- Every successful `phone_*` call renews the lease. Lease length: 300 s idle. One cancellable delayed
  task expires the current epoch; claim/completion resets its deadline, without periodic ticks.
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
- Control window: occupies the available inset-safe frame, with only the measured strip and ask
  card in its touch region. The window is opaque while idle. On Android 12+, an ordinary opaque
  full-frame overlay can block underlying manual touches even outside that region; lowering to the
  safe combined-opacity budget before device work enables action-time pass-through. Focusable only
  while an ask text field is focused; device work excludes the entire window from input.

Screen stays on through `FLAG_KEEP_SCREEN_ON` on the effect window; no WakeLock.

### 4.1 Border

- Inward alpha falloff from the screen edges. On API 31+, use all four system RoundedCorner
  radii and centers, translated into the effect view's coordinates. Refresh after attachment,
  Insets, layout and rotation changes, with Display corner data as the window-Inset fallback.
  Valid square/no-corner data never retains the legacy 28 dp fallback. System quarter-circles are
  an approximation of the physical mask, not exact pixel geometry. Color from current theme primary.
- Enter: a 400 ms FastOutSlowIn reveal mask sweeps bottom to top. Inside the mask band alpha ramps
  0 → 1 and glow ramps 0 → 1 → 0. The band edge may be slanted and broken up by Perlin-noise masking.
- Exit: the same reveal reverses over 240 ms from its current progress.
- Optional chromatic split is applied only to the border's own glow (RGB offset). Displacement or
  refraction of underlying app pixels is not possible: an overlay cannot sample other windows, and
  a MediaProjection feedback loop is rejected for latency and power.
- App Reduced Motion: plain 180 ms fade. Disabled system animations snap to the final state.

### 4.2 Capsules and Stop

Initially top-left, below the status bar: duration, status and Stop form one joined strip with
small inner corners and rounded outer ends. A successful claim fades this strip from zero opacity
and scale 0.96 to one over 240 ms FastOutSlowIn, including its first composition. Release reverses
that transition from the current progress. Retain the outgoing epoch's last duration, status and
question presentation until the fade completes; later action/question updates and replacement leases
cannot replace that content. Close the control window's input immediately at release, cancel its
operation-opacity animation and reject old admission waiters. Outgoing Stop, drag and ask inputs
are disabled. Retained drawing never extends or recreates the lease. The existing two windows are
removed after the exit, or synchronously before a replacement epoch attaches. App Reduced Motion
keeps the fade and fixes scale at one; disabled system animations snap.
Stop reuses the existing filled Stop icon at 24 dp,
with the localized `phone_stop` content description and a click target of at least 48 x 48 dp.
Duration/status and Stop share one strip-level press feedback: scale to 1.05 over 120 ms and
blend the opaque theme primaryContainer toward onPrimaryContainer by six percent. Keep that
feedback through dragging; release and pointer cancellation restore the resting scale/color.
Stop has no second scale or ripple owner. Reduced Motion keeps scale at one and retains tint.
Reserve the maximum press size in layout so enlarged drawing stays inside the safe frame.
Capsule Surface/background colors and content colors are opaque; the border glow is independent.
Platform window alpha is separate: the control window rests at one and fades to its operation
alpha before device work. Accessibility keeps alpha one throughout. On Android 12+, ordinary
Shizuku/Root overlay windows share the platform obscuring-opacity budget, normally 80 percent.
For a platform maximum M, the unchanged effect window and the operating control window each use
1 - sqrt(1 - M), with a small rounding margin; at M = 0.8 that is approximately 0.5527 per window,
not 0.8 per window. Capsule paint is opaque before platform composition. Border drawing and its
existing window opacity stay unchanged.
Dragging the duration/status area begins after platform touch slop and keeps the strip inside the
available visible frame; Stop has its own click region.
Status text: `Thinking` while the owning Run streams model output, the action name during a tool
(`Tap`, `Swipe`, `Typing`, ...), `Waiting for you` during ask_user. Stop releases
the lease without stopping the owning Run. The model is told the user revoked control: an in-flight
`phone_*` call returns `released_by_user`, and every later `phone_*` call from the same Run,
including `phone_claim`, returns `released_by_user` with no side effect. A later Run of that
conversation may claim again. User touches on the screen do not interrupt control.

### 4.3 Action visibility and duration
Before tap, long press, swipe, type, key, app launch, screenshot and UI tree work, fade the control
window's LayoutParams.alpha from its current value to the safe operation alpha over 180 ms with
FastOutSlowIn easing. Stop and dragging remain available during that fade. Once safe alpha is reached,
publish FLAG_NOT_TOUCHABLE and NOT_FOCUSABLE on the whole control window, then wait two frames and
recheck the window's operation-ready state before device effects. Revalidate the exact lease/Run
and enabled/mode settings after this suspension, without another platform permission probe. The view stays visible and its own alpha remains one; the
effect window and border remain unchanged. App-list privacy fallback navigation shares this boundary.
On success, error or cancellation, restore input immediately in the operation finally boundary and
fade back to alpha one over 180 ms, without delaying input restoration. A new action or question
retargets the current alpha without jumping to an old endpoint. Removed/replaced overlays cancel
animation and unblock old waiters with an error, without later window writes. Pending ask_user takes
priority: restore question inputs, fade to one and wait before admitting device work until it settles
and safe operation alpha is reached again. Capture keeps input excluded even if a question arrives.
Stop and dragging are unavailable only while the strip passes input through; notification Stop remains
available. App Reduced Motion retains this opacity-only transition; disabled system animations snap.
Duration uses the selected application font with `fontFeatureSettings = "tnum"`, never a separate
monospace face. Verify actual `0:00` and `1:11` shaping widths before claiming tabular support.
Independently measure all ten digits, fill the current colon-preserving format with its widest digit,
and reserve that measured width. Use m:ss, mm:ss and h:mm:ss as elapsed time grows. Per-second
changes retain width; only format carries change it. Duration text updates directly with one Text
node and no crossfade. Format carries retain the model selector's shared IdentityClipWidth (400 ms
FastOutSlowIn), final-width content and start-anchored rounded clip. Status uses that same clip
owner and IdentityLabelCrossfade (200 ms FastOutSlowIn). Reduced Motion snaps spatial changes;
only status retains the label fade.

### 4.4 Ask card

`ask_user` is reused. While the caller holds the lease, its questions are also presented on an
overlay card centered horizontally below the capsules: options plus an in-card text field.
Fade + scale in/out. Submission uses the canonical ask_user submission so the in-app and overlay
surfaces settle the same request exactly once.

### 4.5 Cursor

A solid circle drawn on the effect window at each action point, theme colored.

- Tap: appear, press (shrink), release (spring back), linger ~600 ms, fade out.
- Long press: press and hold with a slow breathing pulse for the duration.
- Swipe: press at start, move along the path with an eased curve matching gesture timing, release
  at end. No trail.
- Reduced Motion: static dot, fade only.

### 4.6 Screenshot hygiene

Before capture, recheck question priority and input exclusion, then hide the cursor and control
view (strip and ask card) for two frames and bitmap capture. Restore capture-only visibility in
finally; the outer operation boundary owns immediate input restoration.
A question arriving during that short capture interval becomes visible after capture cleanup.
The border stays visible and tool descriptions tell the model a thin
colored border is Agora's control indicator.

## 5. Caller admission

- Interactive conversations and their Subagents may claim.
- Task and Loop runs may claim only when `Allow Task and Loop runs` is on. Otherwise `phone_*`
  tools are absent from those requests.

## 6. App access control

Exactly one of: `Off`, `Blocklist`, `Allowlist`, selected with the same dropdown row pattern as
the working mode selector (section 1 item 2). Lists are package names chosen from installed apps.
The app picker follows Image Transcription's Add Model dialog: `surfaceContainer` background, bold
Material title, transparent `SettingsItem` option rows, a leading checkbox and right-side application
name/package column. Package names use `bodySmall`. The whole row owns one checkbox action and the
shared rounded option highlight. A full-width, single-line search field with 16 dp rounded corners
and a 12 dp gap above the list filters by application name or package. Selection persists immediately
through the existing Settings store; Done only dismisses, without a separate Save/Add transaction.
While installed applications load, one full-width Box centers the progress indicator horizontally
with 24 dp vertical padding. The lazy list is composed only after loading completes.

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
- Overlay verification: opaque idle controls, actual intermediate action-opacity frames, admission
  only after safe opacity/input exclusion, immediate input restore and fade back, interrupted return,
  ask priority before/during fade/capture, cancellation/error cleanup, disabled-animation snap,
  trusted-window opacity and stale owner removal. Verify independent system corner
  centers/radii, inset stroke geometry, merged Stop/drag separation and safe placement.
  Native gesture/drawing tests cover the icon's localized accessible button semantics and 48 dp
  target, one shared press scale/tint through drag/Stop/release/cancellation, Reduced Motion,
  maximum-scale safe bounds and opaque capsule paint in light/dark themes. Real drawn frames also
  cover initial/intermediate/settled claim and release opacity, scale, retained outgoing action/ask
  content, disabled outgoing inputs, epoch replacement, interrupted entry, slower border reveal,
  App Reduced Motion fade-only and disabled-system snap.
  Native text measurement reports default-font tnum shaping and proves digit-template stability,
  direct duration text replacement and both format carries through the shared clip owner.
- Permission diagnostics: one entry snapshot, claim-only overlay and Root probe, no periodic
  checks or mid-operation re-probes, settings-probe isolation, mode-filtered platform events,
  Accessibility replacement lifecycle, stale Shizuku disconnects, command/timeout non-release,
  bounded release history and later-result diagnostics. Idle tests cover completion resets, exact
  Run question pause/resume and stale deadlines after reclaim.
- Full project build gate. Device UI behavior (overlay, animation, permissions deep links) is
  separate evidence requiring owner acceptance on the phone.
