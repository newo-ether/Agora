import { useEffect, useRef, useState } from "./vendor/preact-hooks.mjs";
import { html, useDialog } from "./html.js";
import { sliderStyle } from "./material/slider.js";
import { t } from "./i18n.js";
import { icon, ICON_ARROW_DROP_DOWN } from "./icons.js";
import { sync } from "./sync.js";

export function ConversationEditor({ editor, state, focusReturn, onClose, MoreMenu }) {
  const dialog = useRef(null);
  // ExposedDropdownMenuBox + AgoraExposedDropdownMenu: a read-only outlined field anchors the app's
  // own dropdown at the field's width, never the browser's native option list.
  const modelField = useRef(null);
  const [modelMenu, setModelMenu] = useState(false);
  const [retainedModelMenu, setRetainedModelMenu] = useState(false);
  const advanced = editor.kind === "advanced";
  const info = editor.initial;
  const fields = ["contextWindow", "temperature", "maxTokens", "topP", "frequencyPenalty", "presencePenalty"];
  const [draft, setDraft] = useState(() => Object.fromEntries(fields.map(key => [key, info.overrides?.[key] ?? null])));
  const [model, setModel] = useState(info.modelId || "");
  const [prompt, setPrompt] = useState(info.prompt || "");
  const [retain, setRetain] = useState(String(info.retainCount ?? 0));
  const [error, setError] = useState(null);
  const busy = !!state.pendingAction;
  const dismiss = useDialog(dialog, onClose, focusReturn);
  useEffect(() => { if (!advanced && state.composer.compact?.compacting) dismiss(); }, [state.composer.compact?.compacting]);
  function close() { if (!busy) dismiss(); }
  function apply() {
    if (busy) return;
    if (advanced) {
      if (sync.editorCommand("advanced", { parameters: draft }, editor)) dismiss();
    } else {
      const count = retain === "" ? NaN : Number(retain);
      if (!(model in state.composer.models)) setError(t.compactModelError);
      else if (!prompt.trim()) setError(t.compactPromptError);
      else if (!Number.isInteger(count) || count < 0 || count > 2147483647) setError(t.compactRetainError);
      else if (sync.editorCommand("compact", { modelId: model, text: prompt, retainCount: count }, editor)) dismiss();
    }
  }
  return html`<dialog ref=${dialog} class="attachment-editor conversation-editor" aria-label=${advanced ? t.advancedTitle : t.compactTitle}
    onCancel=${event => { event.preventDefault(); close(); }} onClick=${event => { if (event.target === dialog.current) close(); }}
    onKeyDown=${event => event.stopPropagation()}>
    <section><h2>${advanced ? t.advancedTitle : t.compactTitle}</h2>
    <div class="conversation-editor-body">
      ${advanced ? fields.map(key => {
        const presets = key === "contextWindow" ? info.contextPresets : key === "maxTokens" ? info.maxTokensPresets : null;
        const value = draft[key] ?? state.composer.advanced.defaults[key];
        const bounds = key === "temperature" ? [0, 2] : key === "topP" ? [0, 1] : [-2, 2];
        const effective = value ?? (presets ? 4096 : (bounds[0] + bounds[1]) / 2);
        const index = presets?.reduce((best, item, i) => Math.abs(item - effective) < Math.abs(presets[best] - effective) ? i : best, 0);
        const contextIndex = info.contextPresets.findIndex(item => item === value);
        const label = value == null ? t.unspecified : key === "contextWindow" ? contextIndex >= 0 ? info.contextLabels[contextIndex]
          : draft[key] != null ? info.contextOverrideLabel : state.composer.advanced.contextDefaultLabel
          : presets ? String(value) : Number(value).toFixed(2);
        return html`<div class="advanced-param" data-override=${draft[key] != null} key=${key}>
          <div><label for=${`param-${key}`}>${t.parameters[key]}</label><span>${label}</span>
            ${draft[key] != null && html`<button type="button" disabled=${busy} aria-label=${`${t.reset} ${t.parameters[key]}`}
              onClick=${() => setDraft({ ...draft, [key]: null })}>${t.reset}</button>`}</div>
          <input id=${`param-${key}`} type="range" aria-label=${t.parameters[key]} disabled=${busy}
            style=${sliderStyle(presets ? 0 : bounds[0], presets ? presets.length - 1 : bounds[1], presets ? index : effective, presets ? 1 : "any")}
            min=${presets ? 0 : bounds[0]} max=${presets ? presets.length - 1 : bounds[1]} step=${presets ? 1 : "any"} value=${presets ? index : effective}
            onInput=${event => setDraft({ ...draft, [key]: presets ? presets[Math.round(Number(event.currentTarget.value))] : Number(event.currentTarget.value) })} />
        </div>`;
      }) : html`<div class="outlined-field"><span class="outlined-label" id="compact-model-label">${t.compactModel}</span>
          <button ref=${modelField} class="editor-dropdown-field" type="button" aria-haspopup="menu" aria-expanded=${modelMenu}
            aria-labelledby="compact-model-label" disabled=${busy}
            onClick=${() => { setRetainedModelMenu(true); setModelMenu(!modelMenu); }}>
            <span>${state.composer.models[model] ?? (model || t.selectModel)}</span>
            <span class=${`editor-dropdown-icon ${modelMenu ? "open" : ""}`}>${icon(ICON_ARROW_DROP_DOWN)}</span>
          </button></div>
        ${retainedModelMenu && html`<${MoreMenu} expanded=${modelMenu} reduceMotion=${state.display.reduceMotion}
          anchor=${modelField} matchAnchorWidth=${true} onClose=${() => setModelMenu(false)}
          onExited=${(restoreFocus) => { setRetainedModelMenu(false); if (restoreFocus) modelField.current?.focus(); }}>
          ${Object.keys(state.composer.models).sort().map(id => html`
            <button class="dropdown-item" type="button" role="menuitemradio" aria-checked=${id === model}
              onClick=${() => { setModel(id); setError(null); setModelMenu(false); }}><span>${state.composer.models[id]}</span></button>`)}
        </${MoreMenu}>`}
        <label class="outlined-field"><span class="outlined-label">${t.compactPrompt}</span><textarea rows="3" aria-label=${t.compactPrompt} value=${prompt} disabled=${busy}
          onInput=${event => { setPrompt(event.currentTarget.value); setError(null); }}></textarea></label>
        <label class="outlined-field"><span class="outlined-label">${t.compactRetain}</span><input type="text" inputmode="numeric" aria-label=${t.compactRetain} value=${retain} disabled=${busy}
          onInput=${event => { setRetain(event.currentTarget.value.replace(/\D/g, "")); setError(null); }} /></label>
        ${error && html`<p class="conversation-editor-error" role="alert">${error}</p>`}`}
    </div>
    <footer>${advanced && html`<button class="reset-all" type="button" disabled=${busy} onClick=${() => setDraft(Object.fromEntries(fields.map(key => [key, null])))}>${t.reset}</button>`}
      <button type="button" disabled=${busy} onClick=${close}>${t.cancel}</button>
      <button type="button" disabled=${busy} onClick=${apply}>${advanced ? t.save : t.compact}</button>
    </footer></section></dialog>`;
}
