# Optional E-ink Mode

E-ink Mode lives in **Settings → Appearance → Theme & Color**. It defaults off and applies
immediately. Disabling it restores your usual theme, colour, blur, and motion preferences.
It does not change your providers, conversations, tools, model requests, or saved messages.

The Android interface uses white surfaces, black text and outlined controls. Menus and dialogs
appear immediately, progress indicators remain still, and visible replies update in one-second
batches. Completion, stop, errors and approval requests remain immediate. Images and documents
retain their original content. E-ink mode does not send any additional network requests.

Use the visible Previous Page and Next Page buttons to move by a viewport with three lines of overlap
(capped at half the viewport on short screens).
Jump to Latest is explicit: generated replies do not keep moving your reading position.
The arrows point up/down. The far-left Reading Mode button hides the header, composer and
system bars while keeping paging and Jump to Latest. Return to Chat or Android Back restores chat
without losing your draft or stopping generation. The controls run left to right: reader toggle, Jump to Latest, page up, page down.
Touch scrolling remains available. Search requires its Search button or the keyboard Search action.
Message menus and image zoom have visible controls alongside the familiar gestures.

This contribution covers Android, including Android Remote presentation. The browser WebUI retains
its own existing theme and behaviour. No manufacturer SDK or privileged refresh API is required.

## Ownership and compatibility

The setting uses the existing SettingsManager/SettingsRepository and portable archive allowlist.
Older MERGE archives retain the current choice; older REPLACE archives restore the default off.
The existing theme and motion owners supply effective display overrides. Shared Material drawing
helpers preserve native action semantics and normal-mode arguments; they serve multiple Android
screens rather than defining another navigation or application runtime. AndroidX owns the Material
primitives, so a theme alone cannot replace their disabled alpha, ripple drawing or overlay motion.
The helpers centralize those repeated drawing overrides and forward the original actions and data.

Reply batching is disposable presentation state in the existing scroll-isolation boundary. Only
one latest snapshot is pending. Identity/lifecycle changes and cancellation remove it. Generation,
providers, tool execution and Room must never read this projection. Visible page controls use the
existing list/scroll owners and measured viewport geometry.

## Bigme acceptance

The user performs physical-device testing. Check the following on the preview APK:

- Toggle on/off in a new and an existing conversation; confirm theme restoration and data retention.
- Read a long reply while generation continues; confirm stable position and comfortable updates.
- Exercise Previous/Next, Jump to Latest, Back, keyboard opening, and increased Android font size.
- Open settings categories, provider/model lists, tasks, menus and dialogs; inspect contrast and ghosting.
- Check code, tables, mathematics, images and previews for clipping and usable controls.
- Stop a running reply, test a failed provider request, and respond to a tool approval.
- Background/foreground the app and switch conversations during generation.

A screenshot, emulator or successful build cannot establish physical panel quality. Use the device's
existing refresh settings for this portable version. Firmware-specific changes require a later,
separately qualified adapter; this release does not claim to control waveforms or full-panel cleaning.

## Future handwriting contribution

Use canonical Kalamos Writing as the integration reference: persist local ink first, recognize into
an editable composer draft, and submit only after explicit Send through Agora's existing pipeline.
Keep device pen/refresh lifecycle below the editor and preserve one scoped stroke owner, measured
panel bounds, controller readiness, serialized snapshots, and hard-close buffer clearing.
Do not copy Notes-owned MyScript certificates or depend on the Notes application module.
A separately distributed reusable component needs its own reviewed licensing and packaging boundary;
this app must not acquire a permanent sibling-checkout or source-copy dependency.

Additional references:

- [Mudita E Ink Guidelines](https://zeroheight.com/956ff055a/p/577bfc-e-ink-guidelines)
- [Intent: How to Design for E-Ink Devices](https://www.withintent.com/blog/e-ink-design/)
- [Hermes Notebook](https://github.com/lEWFkRAD/hermes-agents-guide-to-the-galaxy)
- [Moling](https://github.com/xiaoxi668v-prog/moling)

Handwriting, recognition dependencies and vendor pen APIs are intentionally absent from this UI PR.
