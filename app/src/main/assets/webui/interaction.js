import { useLayoutEffect, useRef, useState } from "./vendor/preact-hooks.mjs";
import { html } from "./html.js";
import { sync } from "./sync.js";
import { t } from "./i18n.js";
import { icon, ICON_COLLAPSE_CONTENT, ICON_CONTENT_COPY, ICON_EXPAND_CONTENT, ICON_TERMINAL, ICON_HELP } from "./icons.js";
import { ToolDiffHunks, ToolOutput, ToolPath } from "./detail-sheet.js";

const keyOf = item => `${item.kind}:${item.id}`;
const easing = "cubic-bezier(.4,0,.2,1)";
export function tweenProgress(time) {
  let low = 0, high = 1, value = time;
  for (let step = 0; step < 12; step++) {
    value = (low + high) / 2;
    const x = 3 * (1 - value) ** 2 * value * .4 + 3 * (1 - value) * value ** 2 * .2 + value ** 3;
    if (x < time) low = value; else high = value;
  }
  return time === 0 || time === 1 ? time : 3 * (1 - value) * value ** 2 + value ** 3;
}

/** Request state belongs to the process controllers; only drafts and retained visuals live here. */
export function InteractionBar({ state, enter = true }) {
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
    memory = { page: keyOf(shown.items[0]), folded: false, drafts: new Map(), trust: new Map(), keys: new Set() };
    saved.current.set(shown.owner, memory);
  }
  const pages = shown?.owner === owner && items.length ? items : shown?.items || [];
  const index = Math.max(0, pages.findIndex(item => keyOf(item) === memory?.page));
  const current = pages[index];
  const active = !!shown && shown.owner === owner && !!items.length;
  const admitted = useRef(null);
  if (active && enter) admitted.current = shown.owner;
  const visible = active && enter;
  const busy = !visible || !!state.interactionPending;
  const target = sync.interactionTarget();
  function change() { redraw(value => value + 1); }
  function revealStart() {
    if (visible && host.current) host.current.dataset.toolsReveal = owner;
  }
  function expand() {
    if (busy) return;
    memory.folded = false;
    revealStart();
    change();
  }
  function draft(item) {
    const key = keyOf(item);
    if (!memory.drafts.has(key)) memory.drafts.set(key, { choices: [], text: "", own: !item.options?.length });
    return memory.drafts.get(key);
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
    if (visible) {
      const keys = new Set(items.map(keyOf));
      if ([...keys].some(key => !memory.keys.has(key))) revealStart();
      memory.keys = keys;
    } else if (host.current) delete host.current.dataset.toolsReveal;
  }, [owner, visible, signature]);

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
      const live = new Set(items.map(keyOf));
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
    const from = state.progress, to = visible ? 1 : 0, start = performance.now();
    const draw = () => {
      if (visible) delete node.dataset.toolsExiting;
      const spatial = reduced ? to : state.progress;
      node.dataset.toolsProgress = String(spatial);
      node.dataset.toolsOpacity = String(state.progress);
      node.style.height = `${content.offsetHeight * spatial}px`;
      content.style.opacity = String(state.progress);
      content.style.visibility = state.progress === 0 ? "hidden" : "";
      content.style.transform = `scale(${reduced ? 1 : .96 + .04 * state.progress})`;
    };
    const observer = new ResizeObserver(draw);
    observer.observe(content);
    function tick(now) {
      const elapsed = Math.min(1, (now - start) / 180);
      state.progress = from + (to - from) * tweenProgress(elapsed);
      draw();
      if (elapsed < 1) state.frame = requestAnimationFrame(tick);
      else if (!visible) {
        node.dataset.toolsExiting = "false";
        admitted.current = null;
        if (!active) {
          const next = desired.current;
          if (shown.owner === owner && !next) saved.current.delete(shown.owner);
          state.animations.forEach(animation => animation.cancel());
          state.animations = [];
          state.page = null;
          setShown(next);
        }
      }
    }
    tick(start);
    return () => { cancelAnimationFrame(state.frame); observer.disconnect(); };
  }, [shown?.owner, active, visible, reduced]);

  useLayoutEffect(() => {
    const content = body.current, state = motion.current;
    if (!content || !current) return;
    const previous = state.page;
    const fromHeight = state.animations.length ? content.parentElement.offsetHeight : previous?.height;
    const fromTransform = state.animations.length ? getComputedStyle(content).transform : previous?.transform;
    state.animations.forEach(animation => animation.cancel());
    state.animations = [];
    if (previous?.owner === shown.owner && previous.key !== keyOf(current) && active && !reduced && !memory.folded) {
      const outgoing = previous.node.cloneNode(true);
      outgoing.querySelectorAll('input[type="radio"]').forEach(input => input.removeAttribute("name"));
      outgoing.inert = true;
      outgoing.setAttribute("aria-hidden", "true");
      outgoing.classList.add("interaction-outgoing");
      content.parentElement.append(outgoing);
      const sign = index >= previous.index ? 1 : -1;
      const enter = content.animate([{ transform: `translateX(${sign * 100}%)` }, { transform: "translateX(0)" }], { duration: 350, easing });
      const exit = outgoing.animate([{ transform: fromTransform || "translateX(0)" }, { transform: `translateX(${-sign * 100}%)` }], { duration: 350, easing });
      const height = content.parentElement.animate([{ height: `${fromHeight}px` }, { height: `${content.offsetHeight}px` }], { duration: 350, easing });
      state.animations = [enter, exit, height];
      height.onfinish = () => { state.animations = []; };
      exit.onfinish = () => outgoing.remove();
      exit.oncancel = () => outgoing.remove();
    }
    const snapshot = () => {
      state.page = { owner: shown.owner, key: keyOf(current), index, node: content.cloneNode(true), height: content.offsetHeight };
    };
    snapshot();
    const observer = new ResizeObserver(snapshot);
    observer.observe(content);
    return () => observer.disconnect();
  }, [current && keyOf(current), memory?.folded, reduced, shown?.owner]);

  useLayoutEffect(() => {
    const container = card.current, content = full.current, label = capsule.current;
    if (!container || !content || !label) return;
    const state = motion.current, target = memory.folded ? 1 : 0;
    if (state.fold?.owner !== shown.owner) state.fold = { owner: shown.owner, progress: target, frame: 0 };
    const fold = state.fold, from = fold.progress, start = performance.now();
    const draw = () => {
      // Match the shared row's two 12px insets so the slot and rounded card occupy one width.
      const width = Math.max(0, host.current.closest(".tools-scroll").clientWidth - 24);
      content.style.width = `${width}px`;
      container.style.width = `${width + (Math.min(label.offsetWidth, width) - width) * fold.progress}px`;
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
    observer.observe(host.current.closest(".tools-scroll"));
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
  return html`<div class="interaction-host tools-item" ref=${host} data-tools-key="interaction" data-owner=${shown.owner}
    data-tools-expanded=${visible && !memory.folded ? "true" : null}
    data-tools-exiting=${!visible && admitted.current === shown.owner ? "true" : null}>
    <section class=${`interaction-card${memory.folded ? " folded" : ""}`} ref=${card}
      inert=${!visible} aria-hidden=${!visible ? "true" : null} aria-label=${t.interactionTitle}>
      <button type="button" class="interaction-capsule" ref=${capsule} disabled=${busy || !memory.folded}
        aria-hidden=${memory.folded ? null : "true"} tabIndex=${memory.folded ? 0 : -1}
        onClick=${expand}>
        ${icon(current.kind === "shell" ? ICON_TERMINAL : ICON_HELP)}<span>${current.kind === "shell" ? t.interactionApproval : t.interactionTitle}</span>${icon(ICON_EXPAND_CONTENT)}
      </button>
      <div class="interaction-full" ref=${full} inert=${memory.folded || !visible} aria-hidden=${memory.folded ? "true" : null}>
        <header class="interaction-header">${icon(current.kind === "shell" ? ICON_TERMINAL : ICON_HELP)}
          <strong>${current.kind === "shell" ? t.interactionApproval : t.interactionTitle}</strong>
          ${current.kind === "shell" && html`<span class="interaction-server">${current.server}</span>`}
          <span class="interaction-position">${pages.length > 1 ? `${index + 1} / ${pages.length}` : ""}</span>
          <button type="button" class="icon-button interaction-minimize" aria-label=${t.collapse} title=${t.collapse} disabled=${busy}
            onClick=${() => { memory.folded = true; change(); }}>${icon(ICON_COLLAPSE_CONTENT)}</button>
        </header>
        <div class="interaction-pages"><div class="interaction-body" ref=${body}>
          ${current.kind === "shell" ? html`
            <div class="interaction-shell-content">
            ${current.description ? html`<div class="interaction-description">${current.description}</div>` : null}
            ${current.detail?.type === "edit" ? html`<div class="interaction-detail">
                <${ToolPath} path=${current.detail.path} />
                <${ToolDiffHunks} hunks=${current.detail.hunks} replaceAllLabel=${t.replaceAll} />
              </div>`
              : current.detail?.type === "write" ? html`<div class="interaction-detail">
                <${ToolPath} path=${current.detail.path} />
                <${ToolOutput} text=${current.detail.content} />
              </div>`
              : html`<div class="code-block"><div class="code-header"><span class="code-language">CODE</span>
              <button type="button" class="code-copy" aria-label=${t.copy} title=${t.copy}
                onClick=${event => {
                  const code = event.currentTarget.closest(".code-block")?.querySelector("code")?.textContent ?? "";
                  navigator.clipboard?.writeText(code).catch(() => {});
                }}>${icon(ICON_CONTENT_COPY)}</button></div>
              <pre class=${state.display?.autoWrapCodeBlocks ? "wrapped" : ""}><code>${current.summary}</code></pre></div>`}
            </div>
            <label class="interaction-trust"><input type="checkbox" checked=${memory.trust.get(keyOf(current)) || false} disabled=${busy}
              onChange=${event => { memory.trust.set(keyOf(current), event.target.checked); change(); }} />${t.shellTrust}</label>
          ` : html`
            <div class="interaction-question">${current.question}</div>
            <div class="interaction-options" role="group" aria-label=${current.question}>
              ${(current.options || []).map(option => html`<label key=${option} class=${`interaction-option${value.choices.includes(option) ? " selected" : ""}`}>
                <input key=${current.allowMultiple ? "checkbox" : "radio"} type=${current.allowMultiple ? "checkbox" : "radio"} name=${`question-${current.id}`} checked=${value.choices.includes(option)} disabled=${busy}
                  onChange=${event => {
                    value.choices = current.allowMultiple ? value.choices.includes(option) ? value.choices.filter(choice => choice !== option) : [...value.choices, option] : [option];
                    if (!current.allowMultiple) { value.own = false; event.currentTarget.closest('.interaction-body').querySelector('textarea').blur(); }
                    change();
                  }} />
                <span>${option}</span>
              </label>`)}
              ${current.options?.length > 0 && html`<label class=${`interaction-option${value.own ? " selected" : ""}`}>
                <input key=${current.allowMultiple ? "checkbox" : "radio"} type=${current.allowMultiple ? "checkbox" : "radio"} name=${`question-${current.id}`} checked=${value.own} disabled=${busy}
                  onChange=${event => {
                    value.own = current.allowMultiple ? !value.own : true;
                    if (!current.allowMultiple) value.choices = [];
                    focusAnswer.current = value.own;
                    if (!value.own) event.currentTarget.closest('.interaction-body').querySelector('textarea').blur();
                    change();
                  }} />
                <span>${t.customAnswer}</span>
              </label>`}
            </div>
            <div class=${`interaction-answer${value.own ? " open" : ""}`} inert=${!value.own} aria-hidden=${value.own ? null : "true"}
              onTransitionEnd=${event => { if (value.own && event.propertyName === 'grid-template-rows') event.currentTarget.parentElement.scrollTop = event.currentTarget.parentElement.scrollHeight; }}>
              <div><textarea aria-label=${t.customAnswer} placeholder=${current.options?.length ? null : t.customAnswer} value=${value.text} rows="1" disabled=${busy || !value.own}
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
            <button type="button" class="filled-button" disabled=${busy} onClick=${() => sync.interactionCommand("shell_decision", { requestId: current.id, enabled: true, alwaysAllow: memory.trust.get(keyOf(current)) || false }, target)}>${t.allow}</button>
          ` : html`
            ${index === pages.length - 1 && html`<button type="button" class="filled-button" disabled=${busy || !items.some(item => item.kind === "question" && (draft(item).choices.length || draft(item).own && draft(item).text.trim()))} onClick=${submit}>${t.send}</button>`}
          `}
        </footer>
      </div>
    </section>
  </div>`;
}
