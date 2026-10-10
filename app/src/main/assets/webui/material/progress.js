/**
 * The Material 3 circular progress indicator - the one the phone draws through
 * `CircularProgressIndicator` (material3 1.4.0), which is what every loading affordance in the
 * app's chat composer, drawer and dialogs uses.
 *
 * Everything the indicator is made of lives here, so there is exactly one rotation style and one
 * geometry in the WebUI:
 *
 * - **Determinate** (`value` finite) draws an arc from 12 o'clock plus the track the phone draws by
 *   default (`ProgressIndicatorDefaults.circularDeterminateTrackColor`), separated from the arc by
 *   `CircularIndicatorTrackGapSize` at both ends.
 * - **Indeterminate** draws no track (`circularIndeterminateTrackColor` is transparent) and runs the
 *   phone's own motion: a 1080 degree linear turn, a second turn that steps 90 degrees every
 *   1500 ms, and an arc that breathes between 10% and 87% of a turn. material.css owns those
 *   keyframes; the numbers a browser cannot derive are in tokens.js.
 *
 * `color` matches the composable's own `color` parameter and is only meant for a control that has
 * to follow its own foreground (a filled button, a scrim), because the component otherwise paints
 * `--md-primary` like the phone does.
 */
import { html } from "../html.js";
import { Circular } from "./tokens.js";

export function CircularProgress({ size = 40, stroke = Circular.thickness, label, value = null, color = null }) {
  const determinate = Number.isFinite(value);
  const progress = determinate ? Math.min(1, Math.max(0, value)) : 0;
  // The ring is drawn on a 48 unit box. Compose draws it on (size - strokeWidth) so the stroke
  // never leaves the component's box, and the box scales with the element, hence this ratio.
  const strokeWidth = (stroke * 48) / size;
  const radius = 24 - strokeWidth / 2;
  // Slider-independent: the gap the phone leaves between arc and track, as a share of the 100 unit
  // path. It is measured along the ring, so it depends on the ring's own circumference.
  const gap = ((Circular.trackGap + stroke) / (Math.PI * (size - stroke))) * 100;
  const turn = progress * 100;
  const inset = Math.min(turn, gap);
  const track = Math.max(0, 100 - turn - inset * 2);
  return html`<span class=${`circular-progress${determinate ? " determinate" : ""}`}
    style=${{ width: `${size}px`, height: `${size}px`, color }}
    role=${label ? "progressbar" : null} aria-label=${label} aria-hidden=${label ? null : "true"}
    aria-valuemin=${determinate ? 0 : null} aria-valuemax=${determinate ? 100 : null}
    aria-valuenow=${determinate ? Math.round(progress * 100) : null}>
    <svg viewBox="0 0 48 48" aria-hidden="true"><g>
      ${determinate && html`<circle class="track" cx="24" cy="24" r=${radius} fill="none" pathLength="100"
        stroke="currentColor" stroke-width=${strokeWidth} stroke-linecap="round"
        style=${{ strokeDasharray: `${track} 100`, strokeDashoffset: `${-(turn + inset)}` }} />`}
      <circle class="arc" cx="24" cy="24" r=${radius} fill="none" pathLength="100"
        stroke="currentColor" stroke-width=${strokeWidth} stroke-linecap="round"
        style=${determinate ? { strokeDasharray: `${turn} 100` } : null} />
    </g></svg>
  </span>`;
}

/** The indeterminate form, for a control that is busy but has nothing to count. */
export function Spinner({ size = 40, stroke = Circular.thickness, label, color = null }) {
  return CircularProgress({ size, stroke, label, color, value: null });
}
