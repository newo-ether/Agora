# Interactive answers

Enable **Settings → Interaction → Interactive answers** to let your model create calculators,
charts, diagrams and other interactive answers. Ask, for example, “Make a bill splitter with
controls for people and tip” or “Show these values in an interactive bar chart.” Your selected
model must support tool calls. Completed answers appear directly in the chat. Use
**Expand interactive view** when you want a separate full-screen view. Slider drags stay with
the control. Swipes over ordinary text or empty space scroll the content or chat normally.

This feature uses Open Intelligent UI's styles and Websandbox. Calculations can run locally;
external visualization libraries and supported images require internet access. Update Android
System WebView if the viewer reports an unsupported browser. A readable answer remains available
when rendering fails. Follow-up buttons show their text for confirmation before sending to the
same conversation; they preserve your Composer draft. Links open in your external browser.

Closing and reopening, or scrolling an answer out of view and back, resets controls. Saved answers travel with native conversation backups and
forks. Public Markdown sharing contains readable fallback text without executable code. Very large
interfaces are refused before saving; keep the complete generated payload under 80,000 UTF-8 bytes.
Agora's existing history-size protections can make old interactive content unavailable. There is
no progressive preview, device profile or editable cross-turn artifact in v1.
Use the [template library](templates.md) to save and reopen reusable interfaces.

## E-ink-aware new answers

With E-ink Mode on, new interactive answers receive a short display-adaptation note: high contrast,
stable layouts, efficient viewport use and fewer meaningful redraws. Controls remain appropriate to the task. Simulations
may use manual steps or intermittent automatic progress; no particular button set is required.
The expanded view and overflowing inline cards offer page controls with overlap.

Answers authored for e-ink adapt when you toggle the setting without resetting their local inputs.
Turning it off restores their ordinary styling/interaction. Previously saved HTML is left unchanged.
The request setting is captured at admission, so toggling during generation affects authoring on the
next request. Offscreen/reopened views still reset controls as described above; state is not persisted.
Generated JavaScript must follow the contract: the app does not rewrite scripts or stop arbitrary timers.
Native backup/fork preserves the optional e-ink marker, while public sharing remains fallback text only.

Display-mode notifications support handlers attached to either document or window, so generated
answers using conventional document events adapt without rewriting their saved JavaScript.
