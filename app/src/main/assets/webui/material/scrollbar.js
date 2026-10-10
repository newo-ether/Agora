/**
 * The animated scrollbar the phone draws and a browser cannot.
 *
 * Chrome paints `::-webkit-scrollbar-thumb` on the compositor and runs no CSS transition on it, so a
 * native bar changes width and colour on the frame the pointer arrives (measured on Quantum with a
 * headed Chrome: 4 px at rest, a full 12 px bar 80 ms into a 300 ms transition, identical at 210 ms
 * and at the end). The WebUI therefore keeps the native bar for hit-testing and paints the visible
 * one here: a 12 px gutter holds one pill whose width, colour and opacity ride ordinary CSS
 * transitions, while its top edge tracks scroll position exactly.
 *
 * The overlay never receives pointer events, so every native affordance - thumb drag, page jump on
 * the track, wheel and keyboard scrolling - stays with the element it belongs to.
 */
const IDLE_MS = 900;

/**
 * Draws `bar` over the right gutter of the scrollable `host`.
 *
 * Returns the detach function, so a component owns the lifetime of one scroller.
 */
export function attachScrollbar(host, bar) {
  let idleTimer = 0;
  const thumb = bar.firstElementChild;
  // The bar lives beside the scroller, so it copies the box the scroller actually paints: the chat
  // column starts under the top bar and runs under the composer.
  const place = () => {
    const box = host.getBoundingClientRect();
    const parent = bar.offsetParent?.getBoundingClientRect() ?? box;
    bar.style.top = `${Math.round(box.top - parent.top)}px`;
    bar.style.height = `${Math.round(box.height)}px`;
  };
  const paint = () => {
    const scrollable = host.scrollHeight - host.clientHeight;
    bar.classList.toggle("scrollable", scrollable > 1);
    if (scrollable <= 1) return;
    const height = Math.max(28, (host.clientHeight * host.clientHeight) / host.scrollHeight);
    const top = (host.scrollTop / scrollable) * (host.clientHeight - height);
    thumb.style.top = `${Math.round(top)}px`;
    thumb.style.height = `${Math.round(height)}px`;
  };
  const wake = () => {
    paint();
    bar.classList.add("scrolling");
    clearTimeout(idleTimer);
    idleTimer = setTimeout(() => bar.classList.remove("scrolling"), IDLE_MS);
  };
  // A pointerdown inside the gutter is the native thumb being dragged; the overlay cannot see it.
  const pressed = (event) => {
    if (host.getBoundingClientRect().right - event.clientX <= 14) bar.classList.add("dragging");
  };
  const released = () => bar.classList.remove("dragging");
  // Chrome grows a native bar while the pointer sits over the scroller. The overlay never takes
  // pointer events, so the scroller reports that hover here; a touch never counts as one.
  const entered = (event) => { if (event.pointerType !== "touch") bar.classList.add("hover"); };
  const left = () => bar.classList.remove("hover");
  host.addEventListener("scroll", wake, { passive: true });
  host.addEventListener("pointerdown", pressed);
  host.addEventListener("pointerenter", entered);
  host.addEventListener("pointerleave", left);
  window.addEventListener("pointerup", released);
  const observer = new ResizeObserver(() => { place(); paint(); });
  observer.observe(host);
  place();
  paint();
  return () => {
    host.removeEventListener("scroll", wake);
    host.removeEventListener("pointerdown", pressed);
    window.removeEventListener("pointerup", released);
    clearTimeout(idleTimer);
    observer.disconnect();
  };
}
