/**
 * The Material 3 numbers a component in this directory has to carry into arithmetic.
 *
 * Colours, sizes and radii that only ever get painted live in material.css, which is the single
 * owner of the CSS side. A number belongs here only when a component has to compute with it, and
 * each one quotes the Material 3 1.4.0 token (the version the app resolves through composeBom) or
 * the Compose source line it was read from, so a future bump has exactly one place to check.
 */

/** ProgressIndicatorDefaults and CircularProgressIndicatorTokens (material3 1.4.0). */
export const Circular = {
  /** ProgressIndicatorDefaults.CircularStrokeWidth = ActiveThickness. */
  thickness: 4,
  /** ProgressIndicatorDefaults.CircularIndicatorTrackGapSize = TrackActiveSpace. */
  trackGap: 4,
};

/** SliderTokens and Slider.kt's track drawing (material3 1.4.0). */
export const Slider = {
  /** SliderTokens.StopIndicatorSize: a stop dot is a 4 dp circle. */
  stopSize: 4,
  /**
   * Slider.kt places a stop dot at `lerp(cornerSize, width - cornerSize, fraction)` and cornerSize
   * is half the 16 dp pill track (ActiveTrackHeight), so every stop sits 8 dp inside the track.
   */
  stopInset: 8,
  /**
   * The empty band around the handle: SliderDefaults.ThumbTrackGapSize (ActiveHandleLeadingSpace)
   * plus half of ActiveHandleWidth. Slider.kt skips a stop that falls inside it, and material/slider.js
   * skips emitting one so no dot peeks out beside the handle; material.css leaves the same band
   * transparent in the track paint.
   */
  handleGap: 8,
};
