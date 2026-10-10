import { useLayoutEffect, useRef, useState } from "./vendor/preact-hooks.mjs";
import { html } from "./html.js";
import { sync } from "./sync.js";
import { t } from "./i18n.js";
import { icon, ICON_CHEVRON_DOWN, ICON_CHEVRON_RIGHT, ICON_TERMINAL, ICON_HELP } from "./icons.js";

const keyOf = item => `${item.kind}:${item.id}`;
const easing = "cubic-bezier(.4,0,.2,1)";
function tweenProgress(time) {
  let low = 0, high = 1, value = time;
  for (let step = 0; step < 12; step++) {
    value = (low + high) / 2;
    const x = 3 * (1 - value) ** 2 * value * .4 + 3 * (1 - value) * value ** 2 * .2 + value ** 3;
    if (x < time) low = value; else high = value;
  }
  return time === 0 || time === 1 ? time : 3 * (1 - value) * value ** 2 + value ** 3;
}

/** Request state belongs to the process controllers; only drafts and retained visuals live here. */
export function InteractionBar({ state }) {
  const owner = `${state.connectionId || ""}:${state.openId || ""}`;
  const items = state.connected ? state.interactions : [];
  const desired = useRef(null);
  desired.current = items.length ? { owner, items } : null;
  const [shown, setShown] = useState(desired.current);
  const [, redraw] = useState(0);
  const saved = useRef(new Map());
  const focusAnswer = useRef(false);
  const host = useRef(null), card = useRef(null), body = useRef(null), full = useRef(null), capsule = useRef(null);
  const motion = useRef({ progress: 0, frame: 0, page: null, animations: [], fold: null });
  const reduced = !!state.display?.reduceMotion;
  const signature = items.map(keyOf).join("|");
  let memory = shown && saved.current.get(shown.owner);
  if (shown && !memory) {
    memory = { page: keyOf(shown.items[0]), folded: false, drafts: new Map(), trust: new Map() };
    saved.current.set(shown.owner, memory);
  }
  const pages = shown?.owner === owner && items.length ? items : shown?.items || [];
  const index = Math.max(0, pages.findIndex(item => keyOf(item) === memory?.page));
  const current = pages[index];
  const active = !!shown && shown.owner === owner && !!items.length;
  const busy = !active || !!state.interactionPending;
  const target = sync.interactionTarget();
  function change() { redraw(value => value + 1); }
  function draft(item) {
    if (!memory.drafts.has(item.id)) memory.drafts.set(item.id, { choices: [], text: "", own: !item.options?.length });
    return memory.drafts.get(item.id);
  }
  function move(delta) {
    if (busy || !pages[index + delta]) return;
    memory.page = keyOf(pages[index + delta]);
    change();
  }
  function submit() {
    const answers = Object.fromEntries(items.filter(item => item.kind === "question").map(item => {
      const value = draft(item), text = value.own ? value.text.trim() : "";
      return [item.id, { choices: value.choices, text: text || null, answered: !!(text || value.choices.length) }];
    }));
    sync.interactionCommand("question_submit", { answers }, target);
  }

  useLayoutEffect(() => {
    if (!focusAnswer.current) return;
    focusAnswer.current = false;
    const field = body.current?.querySelector('textarea');
    field?.focus({ preventScroll: true });
    if (reduced && body.current) body.current.scrollTop = body.current.scrollHeight;
  });

  useLayoutEffect(() => {
    for (const key of saved.current.keys()) {
      if (!key.startsWith(`${state.connectionId || ""}:`)) saved.current.delete(key);
    }
    if (!shown && desired.current) setShown(desired.current);
    if (shown?.owner === owner && items.length) {
      if (shown.items !== items) setShown({ owner, items });
      const live = new Set(items.map(item => item.id));
      for (const id of memory.drafts.keys()) if (!live.has(id)) memory.drafts.delete(id);
      for (const id of memory.trust.keys()) if (!live.has(id)) memory.trust.delete(id);
      if (!items.some(item => keyOf(item) === memory.page)) memory.page = keyOf(items[0]);
    }
  }, [owner, signature, shown, state.connectionId]);

  useLayoutEffect(() => {
    const node = host.current, content = card.current;
    if (!node || !content) return;
    const state = motion.current;
    cancelAnimationFrame(state.frame);
    const from = state.progress, to = active ? 1 : 0, start = performance.now();
    const draw = () => {
      node.style.height = `${(content.offsetHeight + 8) * state.progress}px`;
      content.style.opacity = String(state.progress);
      content.style.transform = `scale(${.9 + .1 * state.progress})`;
    };
    const observer = new ResizeObserver(draw);
    observer.observe(content);
    function tick(now) {
      const elapsed = reduced ? 1 : Math.min(1, (now - start) / 180);
      state.progress = from + (to - from) * tweenProgress(elapsed);
      draw();
      if (elapsed < 1) state.frame = requestAnimationFrame(tick);
      else if (!active) {
        const next = desired.current;
        if (shown.owner === owner && !next) saved.current.delete(shown.owner);
        setShown(next);
      }
    }
    tick(start);
    return () => { cancelAnimationFrame(state.frame); observer.disconnect(); };
  }, [shown?.owner, active, reduced]);

  useLayoutEffect(() => {
    const content = body.current, state = motion.current;
    if (!content || !current) return;
    const previous = state.page;
    state.animations.forEach(animation => animation.cancel());
    state.animations = [];
    if (previous?.owner === shown.owner && previous.key !== keyOf(current) && active && !reduced && !memory.folded) {
      const outgoing = previous.node.cloneNode(true);
      outgoing.inert = true;
      outgoing.setAttribute("aria-hidden", "true");
      outgoing.classList.add("interaction-outgoing");
      content.parentElement.append(outgoing);
      const sign = index >= previous.index ? 1 : -1;
      const enter = content.animate([{ transform: `translateX(${sign * 100}%)` }, { transform: "translateX(0)" }], { duration: 350, easing });
      const exit = outgoing.animate([{ transform: previous.transform || "translateX(0)" }, { transform: `translateX(${-sign * 100}%)` }], { duration: 350, easing });
      const height = content.parentElement.animate([{ height: `${previous.height}px` }, { height: `${content.offsetHeight}px` }], { duration: 350, easing });
      state.animations = [enter, exit, height];
      exit.onfinish = () => outgoing.remove();
      exit.oncancel = () => outgoing.remove();
    }
    state.page = { owner: shown.owner, key: keyOf(current), index, node: content.cloneNode(true), height: content.offsetHeight };
    return () => {
      state.page.height = content.parentElement.offsetHeight;
      state.page.transform = getComputedStyle(content).transform;
      state.page.node = content.cloneNode(true);
      state.animations.forEach(animation => animation.cancel());
    };
  }, [current && keyOf(current), memory?.folded, reduced, shown?.owner]);

  useLayoutEffect(() => {
    const container = card.current, content = full.current, label = capsule.current;
    if (!container || !content || !label) return;
    const state = motion.current, target = memory.folded ? 1 : 0;
    if (state.fold?.owner !== shown.owner) state.fold = { owner: shown.owner, progress: target, frame: 0 };
    const fold = state.fold, from = fold.progress, start = performance.now();
    const draw = () => {
      content.style.width = `${host.current.clientWidth}px`;
      container.style.width = `${host.current.clientWidth + (Math.min(label.offsetWidth, host.current.clientWidth) - host.current.clientWidth) * fold.progress}px`;
      container.style.height = `${content.offsetHeight + (48 - content.offsetHeight) * fold.progress}px`;
      content.style.opacity = String(Math.max(0, 1 - fold.progress * 2));
      label.style.opacity = String(Math.max(0, fold.progress * 2 - 1));
    };
    function tick(now) {
      const elapsed = reduced ? 1 : Math.min(1, (now - start) / 320);
      fold.progress = from + (target - from) * tweenProgress(elapsed);
      draw();
      if (elapsed < 1 && from !== target) fold.frame = requestAnimationFrame(tick);
    }
    tick(start);
    const observer = new ResizeObserver(draw);
    observer.observe(content);
    observer.observe(host.current);
    observer.observe(label);
    return () => {
      cancelAnimationFrame(fold.frame);
      observer.disconnect();
    };
  }, [shown?.owner, memory?.folded, reduced]);

  useLayoutEffect(() => () => {
    cancelAnimationFrame(motion.current.frame);
    motion.current.animations.forEach(animation => animation.cancel());
    cancelAnimationFrame(motion.current.fold?.frame);
  }, []);

  if (!shown || !current) return null;
  const value = current.kind === "question" ? draft(current) : null;
  return html`<div class="interaction-host" ref=${host} data-owner=${shown.owner}>
    <section class=${`interaction-card${memory.folded ? " folded" : ""}`} ref=${card}
      inert=${!active} aria-hidden=${!active ? "true" : null} aria-label=${t.interactionTitle}>
      <button type="button" class="interaction-capsule" ref=${capsule} disabled=${busy || !memory.folded}
        aria-hidden=${memory.folded ? null : "true"} tabIndex=${memory.folded ? 0 : -1}
        onClick=${() => { memory.folded = false; change(); }}>
        ${icon(current.kind === "shell" ? ICON_TERMINAL : ICON_HELP)}<span>${current.kind === "shell" ? t.interactionApproval : t.interactionTitle}</span>${icon(ICON_CHEVRON_RIGHT)}
      </button>
      <div class="interaction-full" ref=${full} inert=${memory.folded || !active} aria-hidden=${memory.folded ? "true" : null}>
        <header class="interaction-header">${icon(current.kind === "shell" ? ICON_TERMINAL : ICON_HELP)}
          <strong>${current.kind === "shell" ? t.shellConfirm : t.interactionTitle}</strong>
          ${current.kind === "shell" && html`<span class="interaction-server">${current.server}</span>`}
          <span class="interaction-position">${pages.length > 1 ? `${index + 1} / ${pages.length}` : ""}</span>
          <button type="button" class="icon-button" aria-label=${t.collapse} title=${t.collapse} disabled=${busy}
            onClick=${() => { memory.folded = true; change(); }}>${icon(ICON_CHEVRON_DOWN)}</button>
        </header>
        <div class="interaction-pages"><div class="interaction-body" ref=${body}>
          ${current.kind === "shell" ? html`
            <pre class=${state.display?.autoWrapCodeBlocks ? "wrapped" : ""}>${current.summary}</pre>
            <label class="interaction-trust"><input type="checkbox" checked=${memory.trust.get(current.id) || false} disabled=${busy}
              onChange=${event => { memory.trust.set(current.id, event.target.checked); change(); }} />${t.shellTrust}</label>
          ` : html`
            <div class="interaction-question">${current.question}</div>
            <div class="interaction-options" role="group" aria-label=${current.question}>
              ${(current.options || []).map(option => html`<label key=${option} class=${`interaction-option${value.choices.includes(option) ? " selected" : ""}`}>
                <input type=${current.allowMultiple ? "checkbox" : "radio"} name=${`question-${current.id}`} checked=${value.choices.includes(option)} disabled=${busy}
                  onChange=${event => {
                    value.choices = current.allowMultiple ? value.choices.includes(option) ? value.choices.filter(choice => choice !== option) : [...value.choices, option] : [option];
                    if (!current.allowMultiple) { value.own = false; event.currentTarget.closest('.interaction-body').querySelector('textarea').blur(); }
                    change();
                  }} />
                <span>${option}</span>
              </label>`)}
              ${current.options?.length > 0 && html`<label class=${`interaction-option${value.own ? " selected" : ""}`}>
                <input type=${current.allowMultiple ? "checkbox" : "radio"} name=${`question-${current.id}`} checked=${value.own} disabled=${busy}
                  onChange=${event => {
                    value.own = current.allowMultiple ? !value.own : true;
                    if (!current.allowMultiple) value.choices = [];
                    focusAnswer.current = value.own;
                    if (!value.own) event.currentTarget.closest('.interaction-body').querySelector('textarea').blur();
                    change();
                  }} />
                <span>${t.answerHint}</span>
              </label>`}
            </div>
            <div class=${`interaction-answer${value.own ? " open" : ""}`} inert=${!value.own} aria-hidden=${value.own ? null : "true"}
              onTransitionEnd=${event => { if (value.own && event.propertyName === 'grid-template-rows') event.currentTarget.parentElement.scrollTop = event.currentTarget.parentElement.scrollHeight; }}>
              <div><textarea aria-label=${t.answerHint} placeholder=${t.answerHint} value=${value.text} rows="2" disabled=${busy || !value.own}
                onInput=${event => { value.text = event.target.value; change(); }} /></div>
            </div>
          `}
        </div></div>
        <footer class="interaction-actions">
          ${current.kind === "question" && html`<button type="button" class="text-button" disabled=${busy}
            onClick=${() => sync.interactionCommand("question_submit", { answers: Object.fromEntries(items.filter(item => item.kind === "question").map(item => [item.id, { answered: false }])) }, target)}>${t.skip}</button>`}
          <span class="interaction-action-space"></span>
          ${index > 0 && html`<button type="button" class="text-button" disabled=${busy} onClick=${() => move(-1)}>${t.questionBack}</button>`}
          ${index < pages.length - 1 && html`<button type="button" class=${current.kind === "question" ? "filled-button" : "text-button"} disabled=${busy} onClick=${() => move(1)}>${t.questionNext}</button>`}
          ${current.kind === "shell" ? html`
            <button type="button" class="text-button deny" disabled=${busy} onClick=${() => sync.interactionCommand("shell_decision", { requestId: current.id, enabled: false }, target)}>${t.deny}</button>
            <button type="button" class="filled-button" disabled=${busy} onClick=${() => sync.interactionCommand("shell_decision", { requestId: current.id, enabled: true, alwaysAllow: memory.trust.get(current.id) || false }, target)}>${t.allow}</button>
          ` : html`
            ${index === pages.length - 1 && html`<button type="button" class="filled-button" disabled=${busy || !items.some(item => item.kind === "question" && (draft(item).choices.length || draft(item).own && draft(item).text.trim()))} onClick=${submit}>${t.send}</button>`}
          `}
        </footer>
      </div>
    </section>
  </div>`;
}
