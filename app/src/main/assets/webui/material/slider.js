/**
 * The Material 3 slider: the pieces CSS cannot read off an `<input type="range">`.
 *
 * A browser knows the value but paints the native widget, and the Material 3 1.4.0 track (the one
 * `Slider` draws on the phone: a 16 dp pill with a 4 x 44 dp handle) needs three things CSS has no
 * access to - where the filled part of the track stops, which stop dots the track shows, and which
 * side of the handle each of those dots is on, because a stop changes colour when the fill runs over
 * it. So every slider passes this its own min / max / value / step, in the order the input lists
 * them, and the result goes straight into `style`. material.css owns the widget itself: sizes,
 * colours, the handle, and the mask that hides whatever falls in the band around the handle.
 */
import { Slider } from "./tokens.js";

/** A 4 dp stop dot at `fraction` of the inset track, in the colour of the track part it sits on. */
function stop(fraction, filled) {
  const r = Slider.stopSize / 2;
  return `radial-gradient(circle ${r}px at ` +
    `calc(${Slider.stopInset}px + (100% - ${Slider.stopInset * 2}px) * ${fraction}) 50%, ` +
    `var(--range-stop-${filled ? "on-active" : "on-inactive"}) 0 ${r - 0.2}px, ` +
    `transparent ${r + 0.2}px 100%)`;
}

/**
 * The style object for one Material 3 slider, from the input's own attributes.
 *
 * `--range-fraction` is the value as a share of the range: the track's filled part and the empty
 * band around the handle are both drawn from it. A whole `step` makes the slider discrete, which is
 * what makes `Slider.kt` draw a stop dot for every value - one per step, the last being the stop at
 * the end of the track every Material 3 slider shows - and each dot is painted in the colour of the
 * track it lies on: `--range-stop-on-active` under the fill, `--range-stop-on-inactive` outside it.
 * A continuous slider (`step="any"`, like the advanced sampling parameters) shows no per-value dot.
 *
 * The stop the handle sits on is left out, the way Slider.kt skips a tick inside the gap around the
 * handle, so nothing floats there: the track gradient itself paints no track across that band.
 */
export function sliderStyle(min, max, value, step = 1) {
  const span = Number(max) - Number(min);
  const position = span > 0 ? Math.min(1, Math.max(0, (Number(value) - Number(min)) / span)) : 0;
  const stops = [];
  const steps = span > 0 && Number.isInteger(step) && step >= 1 ? span / step : 0;
  if (Number.isInteger(steps) && steps > 0) {
    // One stop per value, like Slider.kt's tick fractions. It leaves out the stop the handle is
    // sitting on - the one at the slider's own value - so nothing floats in the gap around it.
    for (let index = 0; index < steps; index++) {
      const fraction = index / steps;
      if (Math.abs(fraction - position) < 0.5 / steps) continue;
      stops.push(stop(fraction, fraction <= position));
    }
  }
  // Slider.kt draws one stop at the far end of the track, in the active track's colour, and only
  // while the inactive track is drawn at all: at the last value the handle covers it, so it is left
  // out there.
  if (position < 1) stops.push(stop(1, false));
  return {
    "--range-fraction": `${Math.round(position * 10000) / 10000}`,
    "--range-stops": stops.join(", "),
  };
}
