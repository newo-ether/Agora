// User-capsule disclosure preserves text nodes and masks only the collapsed body.
import { useLayoutEffect, useRef, useState } from "./vendor/preact-hooks.mjs";
import { html } from "./html.js";
import { t } from "./i18n.js";

export function CollapsibleMessageBody({ messageKey, searchActive = false, reduceMotion = false, children }) {
  const body = useRef(null);
  const viewport = useRef(null);
  const animation = useRef(null);
  const startHeight = useRef(null);
  const [expanded, setExpanded] = useState(false);
  const [overflows, setOverflows] = useState(false);
  useLayoutEffect(() => {
    const node = body.current;
    if (!node) return undefined;
    const measure = () => setOverflows(node.getBoundingClientRect().height > 320);
    measure();
    const observer = new ResizeObserver(measure);
    observer.observe(node);
    return () => observer.disconnect();
  }, [messageKey]);
  const collapsed = overflows && !expanded && !searchActive;
  useLayoutEffect(() => {
    const from = startHeight.current;
    startHeight.current = null;
    animation.current?.cancel();
    animation.current = null;
    const node = viewport.current;
    if (node && from != null && !reduceMotion && !searchActive) {
      animation.current = node.animate([
        { height: `${from}px` }, { height: `${node.getBoundingClientRect().height}px` },
      ], { duration: 500, easing: "ease-in" });
    }
    return () => { animation.current?.cancel(); animation.current = null; };
  }, [expanded, searchActive, reduceMotion, messageKey]);
  const toggle = () => {
    startHeight.current = viewport.current?.getBoundingClientRect().height ?? null;
    setExpanded(!expanded);
  };
  return html`<div class="collapsible-message" data-collapsed=${collapsed} ref=${viewport}>
    <div class="message-preview" data-collapsed=${collapsed}>
      <div class="message-preview-body" ref=${body}>${children}</div>
    </div>
    ${overflows && !searchActive && html`<button class="message-expand" type="button"
      aria-expanded=${expanded} onClick=${toggle}>${expanded ? t.collapse : t.expand}</button>`}
  </div>`;
}
