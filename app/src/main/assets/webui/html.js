import { h } from "./vendor/preact.mjs";
import htm from "./vendor/htm.mjs";

export const html = htm.bind(h);

// Callers own the values and velocities so a new target continues the visible spring.
export function runSpring(values, targets, onFrame, onFinished = () => {}) {
  const started = performance.now();
  let frame = 0;
  const samples = Object.entries(targets).map(([key, spec]) => {
    const { target, stiffness, damping, threshold = 0.01 } = spec;
    const omega = Math.sqrt(stiffness), root = -damping * omega;
    const frequency = omega * Math.sqrt(1 - damping * damping);
    const displacement = values[key] - target, velocity = values[`${key}Velocity`] || 0;
    const position = Math.abs(Math.fround(displacement / threshold));
    const speed = Math.fround(velocity / threshold) * (displacement < 0 ? -1 : 1);
    let duration = 0;
    if (position !== 0 || speed !== 0) {
      if (damping < 1) {
        const coefficient = (speed - root * position) / frequency;
        duration = Math.log(1 / Math.hypot(position, coefficient)) / root;
      } else {
        const coefficient = speed - root * position;
        const first = Math.log(Math.abs(1 / position)) / root;
        const guess = Math.log(Math.abs(1 / coefficient));
        let second = guess;
        for (let i = 0; i < 6; i++) second = guess - Math.log(Math.abs(second / root));
        second /= root;
        duration = !Number.isFinite(first) ? second : !Number.isFinite(second) ? first : Math.max(first, second);
        const inflection = -(root * position + coefficient) / (root * coefficient);
        const extremum = (position + coefficient * inflection) * Math.exp(root * inflection);
        let delta = -1;
        if (inflection > 0 && -extremum >= 1) {
          duration = -2 / root - position / coefficient;
          delta = 1;
        } else if (inflection > 0 && coefficient < 0 && position > 0) duration = 0;
        for (let i = 0; i < 100; i++) {
          const before = duration, decay = Math.exp(root * duration);
          duration -= ((position + coefficient * duration) * decay + delta) /
            ((coefficient * (root * duration + 1) + position * root) * decay);
          if (Math.abs(before - duration) <= 0.001) break;
        }
      }
    }
    return { key, target, omega, damping, frequency, displacement, velocity,
      duration: Math.max(0, Math.trunc(duration * 1000)) };
  });
  function advance(time) {
    const elapsedMs = Math.max(0, Math.trunc(time - started)), elapsed = elapsedMs / 1000;
    for (const { key, target, omega, damping, frequency, displacement, velocity, duration } of samples) {
      const speedKey = `${key}Velocity`;
      if (elapsedMs >= duration) { values[key] = target; values[speedKey] = 0; continue; }
      const decay = Math.exp(-damping * omega * elapsed);
      if (damping === 1) {
        const coefficient = velocity + omega * displacement;
        values[key] = target + decay * (displacement + coefficient * elapsed);
        values[speedKey] = decay * (coefficient - omega * (displacement + coefficient * elapsed));
      } else {
        const coefficient = (velocity + damping * omega * displacement) / frequency;
        const cosine = Math.cos(frequency * elapsed), sine = Math.sin(frequency * elapsed);
        const position = displacement * cosine + coefficient * sine;
        values[key] = target + decay * position;
        values[speedKey] = decay * (-damping * omega * position + frequency * (coefficient * cosine - displacement * sine));
      }
    }
    onFrame(values);
  }
  function tick(time) {
    advance(time);
    if (samples.some(({ key, target }) => values[key] !== target || values[`${key}Velocity`] !== 0)) frame = requestAnimationFrame(tick);
    else onFinished();
  }
  tick(started);
  return () => { cancelAnimationFrame(frame); advance(performance.now()); };
}
