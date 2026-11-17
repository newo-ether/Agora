// User disclosure keeps the complete text mounted and shares Thinking's vertical timing.
import { useLayoutEffect, useRef, useState } from "./vendor/preact-hooks.mjs";
import { html } from "./html.js";
import { t } from "./i18n.js";

export function CollapsibleMessageBody({ messageKey, searchActive = false, reduceMotion = false, children }) {
  const body = useRef(null);
  const footer = useRef(null);
  const viewport = useRef(null);
  const mutation = useRef(false);
  const [expanded, setExpanded] = useState(false);
  const [size, setSize] = useState(null);
  const overflows = size?.body > 320;
  const collapsed = overflows && !expanded && !searchActive;
  const controlled = overflows && !searchActive;
  const notify = (active) => {
    if (mutation.current === active) return;
    mutation.current = active;
    viewport.current?.dispatchEvent(new CustomEvent("agora-layout-mutation", {
      bubbles: true, detail: { key: `user-disclosure:${messageKey}`, active },
    }));
  };
  useLayoutEffect(() => {
    const node = body.current;
    if (!node) return undefined;
    const measure = () => {
      const next = { body: node.getBoundingClientRect().height,
        footer: footer.current?.getBoundingClientRect().height ?? 0 };
      setSize(old => old?.body === next.body && old?.footer === next.footer ? old : next);
    };
    measure();
    const observer = new ResizeObserver(measure);
    observer.observe(node);
    observer.observe(footer.current);
    return () => observer.disconnect();
  }, [messageKey, controlled]);
  useLayoutEffect(() => {
    if (!mutation.current) return undefined;
    // Retargeted or zero-distance transitions may have no transitionend event.
    if (reduceMotion || searchActive) { notify(false); return undefined; }
    let frame = requestAnimationFrame(function settle() {
      if (viewport.current?.getAnimations().some(animation =>
        animation.playState === "running" && animation.transitionProperty === "height")) {
        frame = requestAnimationFrame(settle);
      } else notify(false);
    });
    return () => cancelAnimationFrame(frame);
  }, [expanded, reduceMotion, searchActive, controlled]);
  useLayoutEffect(() => () => notify(false), [messageKey]);
  const toggle = () => {
    // Capture the reader's anchor before the first changed layout, including reversal.
    if (!reduceMotion) notify(true);
    setExpanded(value => !value);
  };
  const settle = (event) => {
    if (event.target === viewport.current && event.propertyName === "height") notify(false);
  };
  return html`<div class="collapsible-message" data-collapsed=${collapsed}
    data-controlled=${controlled} data-search-active=${searchActive} data-reduce-motion=${reduceMotion} ref=${viewport}
    style=${size == null ? null : { height: `${collapsed ? 320 : size.body + (controlled ? size.footer : 0)}px` }}
    onTransitionEnd=${settle}>
    <div class="message-preview" data-collapsed=${collapsed}>
      <div class="message-preview-body" ref=${body}>${children}</div>
      <button class="message-collapse" type="button" ref=${footer} inert=${!controlled || !expanded}
        aria-hidden=${controlled && expanded ? null : "true"} tabindex=${controlled && expanded ? 0 : -1}
        aria-expanded=${expanded} onClick=${toggle}>${t.collapse}</button>
    </div>
    <button class="message-expand" type="button" inert=${!controlled}
      aria-hidden=${controlled && !expanded ? null : "true"} tabindex=${controlled && !expanded ? 0 : -1}
      aria-expanded=${expanded} onClick=${toggle}>${t.expand}</button>
  </div>`;
}
