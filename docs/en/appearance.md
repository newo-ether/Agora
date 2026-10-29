# Appearance

Open **Settings → Appearance** to configure Agora's theme, motion, chat presentation, and font.

## Theme & Color

- **Theme Mode** follows the device or forces light or dark mode.
- **AMOLED** uses a pure white background in light themes and pure black in dark themes. Cards and dialogs retain their color layers, while decorative background blobs and gradients are hidden. It is off by default, applies immediately, and is included in Settings backups.
- **Dynamic Color** is available on Android 12 and newer. While it is enabled, Agora uses the system palette and disables the manual color-scheme and scheme-style selectors.
- **Color Scheme** selects an Agora palette, and **Scheme Style** adjusts how that palette is applied.

## Motion & Feedback

- **Blur Effects** blurs the top of the chat list beneath the toolbar. Disable it to reduce rendering cost.
- **Reduce Motion** reduces continuous and large spatial movement while retaining fades, direct gestures, and useful state feedback.
- **Haptic Feedback** controls supported vibration feedback.

## Chat Display

- **Generation Speed** prints each finished reply's average tokens per second and total generation time beside its action icons. It stays off by default; the same figures are always available from the message's ⋮ → Info dialog.
- **Stick to Bottom** follows new content automatically while a response is generating.
- **Parse $…$ as Inline Math** treats single-dollar expressions as inline math. When disabled, they remain ordinary text; other math delimiters are unchanged.
- **Thinking & Tool Blocks** can use a chronological timeline, a grouped timeline that merges consecutive thinking/tool/image blocks, or a compact layout above the answer.
- **Thinking Segments** chooses whether a segment opens as a card or bottom sheet. This selector is shown only when Thinking & Tool Blocks is not set to Timeline.
- **Auto-Expand Active Group** is available only with Grouped Timeline and Card thinking segments. It expands the current group and collapses it when the model moves on.

## Font

Choose Agora's bundled font, the system font, or a custom font. The custom-file picker appears only for **Custom**. Agora validates imported font files and rejects an invalid file; switching away from Custom removes the imported copy managed by Agora.
