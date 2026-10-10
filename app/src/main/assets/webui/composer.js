import { useEffect, useLayoutEffect, useRef, useState } from "./vendor/preact-hooks.mjs";
import { html } from "./html.js";
import { CircularProgress, Spinner } from "./material/progress.js";
import { sliderStyle } from "./material/slider.js";
import { t } from "./i18n.js";
import { icon, ICON_ADD, ICON_ARROW_UPWARD, ICON_EXPAND_ALL, ICON_COLLAPSE_ALL, ICON_MORE_VERT, ICON_STOP, ICON_CHECK, ICON_CLOSE, ICON_ATTACH_FILE } from "./icons.js";
import { sync } from "./sync.js";
import { Markdown } from "./markdown.js";
import { InteractionBar } from "./interaction.js";
import { DetailSheet } from "./detail-sheet.js";
import { ICON_NEUROLOGY, ICON_MEMORY, ICON_SPEED, ICON_TERMINAL, ICON_LANGUAGE, ICON_COMPRESS, ICON_TUNE, ICON_CHEVRON_DOWN, ICON_GOOGLE, ICON_OPENAI } from "./icons.js";
import { ICON_IMAGE, ICON_CHEVRON_RIGHT, ICON_CAMERA, ICON_VIDEO, ICON_ERROR, ICON_BROKEN_IMAGE } from "./icons.js";

/** The phone's `contextUsagePercent`: whole percent of the window already used, reserve excluded. */
function contextUsagePercent(estimatedTokens, tokenBudget) {
  return tokenBudget <= 0 ? 0 : Math.round(estimatedTokens * 100 / tokenBudget);
}
/** One part's share of the bar; the bar itself keeps a part that holds tokens visible. */
function contextShare(tokens, tokenBudget) {
  return tokenBudget <= 0 ? 0 : Math.min(100, tokens * 100 / tokenBudget);
}
/**
 * What a paste into the field contributes. Exactly one kind of clipboard item is consumed, an image:
 * text and anything unsupported go back to the platform, as on the phone, so caret, selection, undo,
 * IME and accessibility stay native and a mixed payload types its text at the caret while its images
 * become attachments beside it. A payload that carries no text at all is stopped, because otherwise a
 * browser may leave a bare file name where the image should have gone.
 */
export function splitPaste(data) {
  const files = data?.files ? [...data.files] : [];
  const images = files.filter((file) => file.type?.startsWith("image/"));
  return { images, toPlatform: images.length === 0 || Boolean(data.getData("text/plain")) };
}
const CONTEXT_PART_LABEL = {
  system: t.contextPartSystem, tools: t.contextPartTools, messages: t.contextPartMessages,
  free: t.contextPartFree, reserved: t.contextPartReserved,
};
/** ChatBottomBar: surface card with the text field, the expand button and the controls row. */
export function Composer({ state, MoreMenu, expanded = false, onExpandedChange }) {
  const field = useRef(null);
  const host = useRef(null);
  const sizeMotion = useRef({ height: null, animation: null });
  const composing = useRef(false);
  const modelButton = useRef(null);
  const addButton = useRef(null);
  const picker = useRef(null);
  const pickerTarget = useRef(null);
  const toolsButton = useRef(null);
  const contextButton = useRef(null);
  const [contextOpen, setContextOpen] = useState(false);
  const [retainedContext, setRetainedContext] = useState(false);
  const [toolsOpen, setToolsOpen] = useState(false);
  const [retainedTools, setRetainedTools] = useState(false);
  const [toolPanel, setToolPanel] = useState(null);
  const [advancedThinking, setAdvancedThinking] = useState(false);
  const [slider, setSlider] = useState(null);
  const [editor, setEditor] = useState(null);
  const controls = state.composer?.controls;
  // One connection's own context window, priced on the phone by ConversationContextProjector.
  const context = state.context;
  const contextReady = !!context && context.estimatedLabel != null && context.tokenBudget > 0;
  const contextFraction = contextReady ? Math.min(1, Math.max(0, context.estimatedTokens / context.tokenBudget)) : 0;
  const contextUsage = contextReady ? t.contextUsage(context.estimatedLabel, context.budgetLabel) : null;
  const contextPercent = contextReady ? `${contextUsagePercent(context.estimatedTokens, context.tokenBudget)}%` : null;
  const composerReady = state.connected && !!sync.attachmentTarget();
  const settingsEditable = composerReady && !!controls && !state.pendingAction;
  const capabilityEnabled = settingsEditable && !controls?.lowContextModeEnabled;
  const validPanel = toolPanel && toolPanel.connectionId === state.connectionId && toolPanel.seq === state.composer?.seq &&
    !!controls && (toolPanel.kind === "thinking" || (controls.openAiServiceTierAvailable && state.composer.modelValid));
  useEffect(() => { setToolsOpen(false); setToolPanel(null); setSlider(null); setEditor(null); setContextOpen(false); }, [state.openId, state.connectionId]);
  useEffect(() => { if (!state.pendingAction) setSlider(null); }, [state.pendingAction]);
  useEffect(() => { setSlider(null); }, [state.composer?.modelId]);
  useEffect(() => { if (controls?.displayedThinkingBudgetEnabled) setAdvancedThinking(true); }, [controls?.displayedThinkingBudgetEnabled]);
  function openTool(kind) {
    setToolPanel({ ...sync.attachmentTarget(), kind });
    setAdvancedThinking(!!controls.displayedThinkingBudgetEnabled);
    setToolsOpen(false);
  }
  const validEditor = editor && state.connected && editor.connectionId === state.connectionId && editor.seq === state.composer?.seq && editor.conversationId === state.openId;
  // ComposerToolsMenuContent: enabled = canCompact && !isCompacting, where canCompact is
  // "a conversation, not loading, not switching, not stopping".
  const compactAvailable = settingsEditable && !!state.openId && state.openStatus === "ready" &&
    !state.generating && !state.composer.stopping && !!state.composer.compact && !state.composer.compact.compacting;
  function openEditor(kind) {
    setEditor({ ...sync.attachmentTarget(), conversationId: state.openId, kind, initial: kind === "advanced" ? state.composer.advanced : state.composer.compact });
    setToolsOpen(false);
  }
  const [addOpen, setAddOpen] = useState(false);
  const [retainedAdd, setRetainedAdd] = useState(false);
  const [viewer, setViewer] = useState(null);
  const attachments = state.composer?.attachments ?? [];
  const queue = state.composer?.queue ?? [];
  const queueKey = JSON.stringify(queue.map(item => item.id));
  const queueOwner = JSON.stringify([state.connectionId, state.openId, state.composer?.seq]);
  const queueHost = useRef(null);
  const queueMotion = useRef({ owner: null, key: null, nextId: 0, targetId: null, layers: [], height: 0, animation: null });
  const [queueLayers, setQueueLayers] = useState([]);
  useLayoutEffect(() => {
    const host = queueHost.current;
    const motion = queueMotion.current;
    if (motion.owner !== queueOwner) {
      motion.animation?.cancel();
      motion.animation = null;
      motion.layers.forEach(layer => layer.animation?.cancel());
      motion.owner = queueOwner;
      motion.key = queueKey;
      motion.height = queue.length * 44;
      motion.targetId = queue.length ? ++motion.nextId : null;
      motion.layers = queue.length ? [{ id: motion.targetId, items: queue, alpha: 1, settled: true }] : [];
      host.style.height = `${motion.height}px`;
      setQueueLayers(motion.layers);
      return;
    }
    if (motion.key !== queueKey) {
      // Freeze only the active snapshot; older exits finish on their original deadlines.
      const height = host.getBoundingClientRect().height;
      motion.animation?.cancel();
      motion.animation = null;
      host.style.height = `${height}px`;
      const active = motion.layers.find(layer => layer.id === motion.targetId);
      if (active) {
        const element = host.querySelector(`[data-queue-frame="${active.id}"]`);
        active.alpha = Number(getComputedStyle(element).opacity);
        active.items = active.latestItems;
        active.animation?.cancel();
        active.animation = null;
        active.exiting = true;
        active.settled = false;
        element.style.opacity = String(active.alpha);
      }
      motion.key = queueKey;
      motion.height = queue.length * 44;
      motion.targetId = queue.length ? ++motion.nextId : null;
      motion.layers = motion.layers.filter(layer => layer.alpha > 0);
      if (queue.length) motion.layers.push({ id: motion.targetId, items: queue, alpha: 0 });
      setQueueLayers([...motion.layers]);
      return;
    }
    if (state.display?.reduceMotion) {
      motion.animation?.cancel();
      motion.animation = null;
      host.style.height = `${motion.height}px`;
    } else if (!motion.animation && parseFloat(host.style.height) !== motion.height) {
      const animation = host.animate(
        [{ height: host.style.height }, { height: `${motion.height}px` }],
        { duration: 220, easing: "cubic-bezier(0.4, 0, 0.2, 1)", fill: "both" },
      );
      motion.animation = animation;
      animation.onfinish = () => {
        if (motion.animation !== animation) return;
        host.style.height = `${motion.height}px`;
        motion.animation = null;
        animation.cancel();
      };
    }
    motion.layers.forEach(layer => {
      if (layer.animation || layer.settled) return;
      const element = host.querySelector(`[data-queue-frame="${layer.id}"]`);
      const target = layer.exiting ? 0 : 1;
      const animation = element.animate([{ opacity: layer.alpha }, { opacity: target }],
        { duration: layer.exiting ? 140 : 180, easing: "linear", fill: "both" });
      layer.animation = animation;
      animation.onfinish = () => {
        if (layer.animation !== animation) return;
        element.style.opacity = String(target);
        animation.cancel();
        layer.animation = null;
        layer.alpha = target;
        layer.settled = true;
        if (layer.exiting) {
          motion.layers = motion.layers.filter(item => item !== layer);
          setQueueLayers([...motion.layers]);
        }
      };
    });
  });
  useLayoutEffect(() => () => {
    queueMotion.current.animation?.cancel();
    queueMotion.current.layers.forEach(layer => layer.animation?.cancel());
  }, []);
  const [modelOpen, setModelOpen] = useState(false);
  const [retainedModelMenu, setRetainedModelMenu] = useState(false);
  const modelChoices = Object.entries(state.composer?.models ?? {});
  const selectedModel = state.composer?.modelId;
  const modelLabel = state.composer?.modelValid ? state.composer.models?.[selectedModel] ?? t.selectModel
    : modelChoices.length ? t.selectModel : t.noModel;
  useEffect(() => { setModelOpen(false); }, [state.openId, state.connected]);
  const phase = state.composer?.phase ?? "IDLE";
  const waiting = phase === "WAITING";
  const busy = state.pendingAction || phase !== "IDLE" || state.composer?.stopping;
  const stop = state.generating && !state.text.trim() && !attachments.length;
  const canDrain = !state.generating && !state.text.trim() && !attachments.length && state.composer?.queue?.length;
  const editable = composerReady && !state.pendingAction && phase === "IDLE";
  useEffect(() => { if (!editable) setAddOpen(false); }, [editable]);
  useEffect(() => { setAddOpen(false); }, [state.openId, state.connectionId]);
  const viewedId = viewer?.file?.id ?? viewer?.items?.[viewer.index]?.id;
  const currentViewer = viewer && viewer.connectionId === state.connectionId && viewer.seq === state.composer?.seq &&
    attachments.some(a => a.id === viewedId && !a.unavailable && a.storage === "APP_PRIVATE");
  useEffect(() => { if (viewer && !currentViewer) setViewer(null); }, [viewer, currentViewer]);
  const pending = state.connected && phase === "IDLE" && (attachments.find(a => a.type === "pdf" && a.state === "PROCESSING" && a.pageCount > 0 && a.selectedPages == null)
    ?? attachments.find(a => a.type === "video" && a.state === "PROCESSING" && a.staged && a.frameCount == null));
  function choose(kind) {
    const node = picker.current;
    pickerTarget.current = { target: sync.attachmentTarget(), forced: kind === "videos" ? "video" : kind === "files" ? null : "image" };
    node.accept = kind === "videos" ? "video/*" : kind === "files" ? "*/*" : "image/*";
    node.multiple = kind !== "camera";
    if (kind === "camera") node.setAttribute("capture", "environment"); else node.removeAttribute("capture");
    node.value = "";
    node.click();
    setAddOpen(false);
  }
  function preview(a) {
    const media = a.type === "pdf" ? Array.from({ length: a.pagePreviewCount }, (_, index) => ({ ...a, kind: "page", index }))
      : attachments.filter(item => ["image", "video"].includes(item.type) && item.state === "READY" && !item.unavailable && item.storage === "APP_PRIVATE")
        .map(item => ({ ...item, kind: "source", index: 0 }));
    setViewer({ ...sync.attachmentTarget(), ...(a.type === "file" ? { file: a } : { items: media, index: Math.max(0, media.findIndex(item => item.id === a.id)) }) });
  }
  const actionable = composerReady && !state.pendingAction && !state.composer.stopping &&
    !["loading", "failed", "deleted"].includes(state.openStatus) &&
    (waiting || (phase === "IDLE" && (stop || ((state.text.trim() || attachments.length || canDrain) && state.composer.modelValid))));
  function submit() {
    if (actionable) { if (stop && !busy) sync.stopGeneration(); else sync.submit(); }
  }
  useLayoutEffect(() => {
    const node = host.current;
    const motion = sizeMotion.current;
    const from = motion.animation ? node.getBoundingClientRect().height : motion.height;
    motion.animation?.cancel();
    motion.animation = null;
    const target = node.offsetHeight;
    if (from != null && Math.abs(from - target) > 1 && !state.display?.reduceMotion) {
      const animation = node.animate([{ height: `${from}px` }, { height: `${target}px` }],
        { duration: 400, easing: expanded ? "cubic-bezier(0.15, 0.5, 0.25, 1)" : "cubic-bezier(0.4, 0, 0.2, 1)", fill: "both" });
      motion.animation = animation;
      animation.onfinish = () => {
        if (motion.animation !== animation) return;
        motion.animation = null;
        animation.cancel();
        motion.height = node.offsetHeight;
      };
    }
    motion.height = target;
    const geometry = new ResizeObserver(() => { if (!motion.animation) motion.height = node.offsetHeight; });
    geometry.observe(node);
    return () => geometry.disconnect();
  }, [expanded, state.display?.reduceMotion]);
  useLayoutEffect(() => () => sizeMotion.current.animation?.cancel(), []);
  useLayoutEffect(() => {
    const node = field.current;
    if (expanded) { node.style.height = ""; return; }
    const resize = () => {
      node.style.height = "auto";
      node.style.height = Math.min(node.scrollHeight, 6 * 23 + 24) + "px";
    };
    const geometry = new ResizeObserver(resize);
    geometry.observe(node);
    resize();
    return () => geometry.disconnect();
  }, [state.text, expanded]);
  useEffect(() => {
    if (!state.snackbar) return;
    const id = state.snackbar.id;
    const timer = setTimeout(() => sync.dismissSnackbar(id), 4_000);
    return () => clearTimeout(timer);
  }, [state.snackbar?.id]);
  return html`
    <div class=${`composer-host ${expanded ? "composer-expanded" : ""}`} ref=${host}>
      <${InteractionBar} state=${state} />
      <form class="composer" onSubmit=${(event) => {
        event.preventDefault();
        submit();
      }}>
        <div class="queue-status" ref=${queueHost}>
          ${queueLayers.map(layer => {
            const current = layer.id === queueMotion.current.targetId && queueMotion.current.owner === queueOwner &&
              queueMotion.current.key === queueKey;
            const items = current ? queue : layer.latestItems ?? layer.items;
            if (current) layer.latestItems = items;
            return html`<div class="queue-status-frame" key=${layer.id} data-queue-frame=${layer.id}
              inert=${!current} aria-hidden=${current ? null : "true"}
              style=${{ opacity: layer.alpha }}>
            ${items.map(queued => html`<div class="queued-message" key=${queued.id}>
            <span class="queued-text">${queued.text}</span>
            ${queued.attachmentCount > 0 && html`<span class="queued-attachments" aria-label=${t.attachments}>
              ${icon(ICON_ATTACH_FILE)}${queued.attachmentCount}</span>`}
            <button type="button" aria-label=${t.remove} disabled=${!current || !state.connected}
              onClick=${() => {
                if (current && queueMotion.current.targetId === layer.id && queueMotion.current.owner === queueOwner)
                  sync.removeQueued(queued.id);
              }}>${icon(ICON_CLOSE)}</button>
          </div>`)}
          </div>`;
          })}
        </div>
        ${attachments.length > 0 && html`<div class="attachment-row" aria-label=${t.attachments}>
          ${attachments.map(a => html`<${AttachmentTile} key=${`${state.connectionId}:${state.composer.seq}:${a.id}`} attachment=${a} editable=${editable} onPreview=${() => preview(a)} />`)}
        </div>`}
        <input class="attachment-input" type="file" ref=${picker} onChange=${event => {
          const captured = pickerTarget.current;
          if (captured) sync.uploadFiles([...event.currentTarget.files], captured.forced, captured.target);
          event.currentTarget.value = "";
        }} />
        <div class="composer-field">
          <textarea ref=${field} rows="1" placeholder=${t.askAgora} aria-label=${t.askAgora}
            value=${state.text} onInput=${(event) => sync.edit(event.currentTarget.value)}
            onPaste=${(event) => {
              const { images, toPlatform } = splitPaste(event.clipboardData);
              if (!images.length) return;
              if (!toPlatform) event.preventDefault();
              sync.uploadFiles(images, "image", sync.attachmentTarget());
            }}
            oncompositionstart=${() => { composing.current = true; }} oncompositionend=${() => { composing.current = false; }}
            onKeyDown=${event => {
              if (event.key === "Escape" && expanded) { event.preventDefault(); onExpandedChange(false); return; }
              if (event.key !== "Enter" || event.shiftKey || event.altKey || event.ctrlKey || event.metaKey ||
                  event.isComposing || composing.current || event.keyCode === 229 ||
                  !matchMedia("(hover: hover) and (pointer: fine)").matches) return;
              event.preventDefault();
              if (!event.repeat && !stop && !busy) submit();
            }}></textarea>
          <button class="expand-button" type="button" aria-label=${expanded ? t.collapse : t.expand}
            aria-expanded=${expanded} onPointerDown=${event => event.preventDefault()}
            onClick=${() => { onExpandedChange(!expanded); field.current?.focus(); }}>
            ${icon(expanded ? ICON_COLLAPSE_ALL : ICON_EXPAND_ALL, "0 0 960 960")}
          </button>
        </div>
        <div class="composer-controls">
          <div class="control-group">
            <button ref=${addButton} class="control-icon" type="button" aria-label=${t.addAttachment} disabled=${!editable || !state.connectionId}
              aria-haspopup="menu" aria-expanded=${addOpen} onClick=${() => { setRetainedAdd(true); setAddOpen(!addOpen); }}>
              ${icon(ICON_ADD)}
            </button>
            <button ref=${modelButton} class="model-selector" type="button" aria-label=${t.selectModel}
              aria-haspopup="menu" aria-expanded=${modelOpen} data-valid=${!!state.composer?.modelValid}
              disabled=${!composerReady || !!state.pendingAction}
              onClick=${() => { setRetainedModelMenu(true); setModelOpen(!modelOpen); }}>${modelLabel}</button>
            <button ref=${contextButton} class="control-icon context-ring" type="button"
              aria-label=${contextReady ? `${t.context}: ${contextUsage}` : t.context}
              aria-haspopup="menu" aria-expanded=${contextOpen} disabled=${!contextReady}
              data-over=${context?.overCompactThreshold ? "true" : null}
              onClick=${() => { if (contextReady) { setRetainedContext(true); setContextOpen(!contextOpen); } }}>
              <${CircularProgress} size=${20} stroke=${2.5} value=${contextFraction} />
            </button>
            <button ref=${toolsButton} class="control-icon" type="button" aria-label=${t.tools} disabled=${!settingsEditable}
              aria-haspopup="menu" aria-expanded=${toolsOpen} onClick=${() => { setRetainedTools(true); setToolsOpen(!toolsOpen); }}>
              ${icon(ICON_MORE_VERT)}
            </button>
          </div>
          <button class="send-button" type="submit" aria-label=${waiting ? t.cancel : stop ? t.stop : t.send}
            onPointerDown=${(event) => event.preventDefault()}
            disabled=${!actionable} aria-busy=${busy ? "true" : null}>
            ${busy ? html`<${Spinner} size=${24} stroke=${3} color="inherit" />` : icon(stop ? ICON_STOP : ICON_ARROW_UPWARD)}
          </button>
        </div>
      </form>
    </div>
      ${retainedContext && context && html`<${MoreMenu} expanded=${contextOpen} reduceMotion=${state.display.reduceMotion}
        anchor=${contextButton} above onClose=${() => setContextOpen(false)}
        onExited=${restore => { setRetainedContext(false); if (restore) contextButton.current?.focus(); }}>
        <div class="context-menu">
          <div class="context-menu-head"><span>${contextUsage}</span><span>${contextPercent}</span></div>
          <div class="context-bar">
            ${context.parts.filter(part => part.key === "reserved").map(part => html`<span class="context-reserved"
              data-empty=${part.tokens === 0 ? "true" : null} style=${{ width: `${contextShare(part.tokens, context.tokenBudget)}%` }}></span>`)}
            <span class="context-segments">
              ${context.parts.filter(part => part.key === "system" || part.key === "tools" || part.key === "messages")
                .map(part => html`<i key=${part.key} class=${`context-part context-part-${part.key}${
                  part.key === "messages" && context.overCompactThreshold ? " context-over" : ""}`}
                  data-empty=${part.tokens === 0 ? "true" : null}
                  style=${{ width: `${contextShare(part.tokens, context.tokenBudget)}%` }}></i>`)}
            </span>
          </div>
          <div class="context-legend">
            ${context.parts.map(part => html`<div key=${part.key} class="context-legend-row">
              <span class=${`context-dot context-dot-${part.key}${
                part.key === "messages" && context.overCompactThreshold ? " context-over" : ""}`}></span>
              <span>${CONTEXT_PART_LABEL[part.key] || part.key}</span><span>${part.label}</span>
            </div>`)}
          </div>
        </div>
      </${MoreMenu}>`}
      ${retainedModelMenu && html`<${MoreMenu} expanded=${modelOpen} reduceMotion=${state.display.reduceMotion}
        anchor=${modelButton} above=${true} onClose=${() => setModelOpen(false)}
        onExited=${(restoreFocus) => { setRetainedModelMenu(false); if (restoreFocus) modelButton.current?.focus(); }}>
          ${modelChoices.length ? modelChoices.map(([id, label]) => html`
            <button class="dropdown-item" type="button" role="menuitemradio" aria-checked=${id === selectedModel}
              onClick=${() => { sync.selectModel(id); setModelOpen(false); }}>
              <span class="model-check">${id === selectedModel && icon(ICON_CHECK)}</span><span>${label}</span>
            </button>`) : html`<button class="dropdown-item" type="button" role="menuitem" disabled>${t.noModels}</button>`}
      </${MoreMenu}>`}
      ${retainedAdd && html`<${MoreMenu} expanded=${addOpen} reduceMotion=${state.display.reduceMotion} anchor=${addButton} above
        onClose=${() => setAddOpen(false)} onExited=${restore => { setRetainedAdd(false); if (restore) addButton.current?.focus(); }}>
        ${[["camera", ICON_CAMERA], ["photos", ICON_IMAGE], ["videos", ICON_VIDEO], ["files", ICON_ATTACH_FILE]].map(([kind, path]) => html`
          <button class="dropdown-item" role="menuitem" type="button" disabled=${!editable} onClick=${() => choose(kind)}>${icon(path)}<span>${t[kind]}</span></button>`)}
      </${MoreMenu}>`}
      ${retainedTools && html`<${MoreMenu} expanded=${toolsOpen} reduceMotion=${state.display.reduceMotion} anchor=${toolsButton} above
        onClose=${() => setToolsOpen(false)} onExited=${restore => { setRetainedTools(false); if (restore) toolsButton.current?.focus(); }}>
        ${controls?.showLowContextMode && html`<button class="dropdown-item" role="menuitemcheckbox" aria-checked=${controls.lowContextModeEnabled}
          disabled=${!settingsEditable} onClick=${() => sync.setting("lowContextModeEnabled", !controls.lowContextModeEnabled)}>
          ${icon(ICON_MEMORY)}<span class="tool-label">${t.lowContext}</span><span class="tool-switch" data-checked=${controls.lowContextModeEnabled}></span></button>`}
        <div class="dropdown-item tool-split">
          ${icon(ICON_NEUROLOGY, "0 0 960 960")}<button class="tool-label" role="menuitem" disabled=${!settingsEditable} onClick=${() => openTool("thinking")}>
            ${t.thinking}<small>${!controls?.displayedThinkingEnabled ? t.off : controls.displayedThinkingBudgetEnabled ? t.tokens(controls.displayedThinkingBudgetTokens) : t.efforts[controls.displayedThinkingLevel] ?? controls.displayedThinkingLevel}</small></button>
          <button class="tool-switch" data-checked=${!!controls?.displayedThinkingEnabled} role="menuitemcheckbox" aria-label=${t.thinking}
            aria-checked=${!!controls?.displayedThinkingEnabled} disabled=${!settingsEditable || (!controls?.thinkingCanDisable && controls?.displayedThinkingEnabled)}
            onClick=${() => sync.setting("thinkingEnabled", !controls.displayedThinkingEnabled)}></button>
        </div>
        ${controls?.isGemini && [["codeExecutionEnabled", t.codeExecution, ICON_TERMINAL], ["googleSearchEnabled", t.googleSearch, ICON_GOOGLE]].map(([key, label, path]) => html`
          <button class="dropdown-item" role="menuitemcheckbox" aria-checked=${controls[key]} disabled=${!capabilityEnabled} onClick=${() => sync.setting(key, !controls[key])}>
            ${icon(path)}<span class="tool-label">${label}</span><small class="provider-badge">Gemini</small><span class="tool-switch" data-checked=${controls[key]}></span></button>`)}
        ${controls?.openAiServiceTierAvailable && state.composer.modelValid && html`<div class="dropdown-item tool-split" data-disabled=${!capabilityEnabled}>
          ${icon(ICON_SPEED)}<button class="tool-label" role="menuitem" disabled=${!capabilityEnabled} onClick=${() => openTool("tier")}>
            ${t.serviceTier}<small>${controls.openAiServiceTierEnabled ? t.tiers[controls.displayedServiceTier] : t.off}</small></button>
          <button class="tool-switch" data-checked=${controls.openAiServiceTierEnabled} role="menuitemcheckbox" aria-label=${t.serviceTier}
            aria-checked=${controls.openAiServiceTierEnabled} disabled=${!capabilityEnabled} onClick=${() => sync.setting("openAiServiceTierEnabled", !controls.openAiServiceTierEnabled)}></button>
        </div>`}
        ${[["openAiWebSearchAvailable", "openAiWebSearchEnabled", t.openAiSearch, ICON_OPENAI], ["webSearchAvailable", "webSearchEnabled", t.webSearch, ICON_LANGUAGE], ["shellAvailable", "shellEnabled", t.shell, ICON_TERMINAL]]
          .filter(([available]) => controls?.[available] && (available !== "openAiWebSearchAvailable" || state.composer.modelValid)).map(([, key, label, path]) => html`
          <button class="dropdown-item" role="menuitemcheckbox" aria-checked=${controls[key]} disabled=${!capabilityEnabled} onClick=${() => sync.setting(key, !controls[key])}>
            ${icon(path)}<span class="tool-label">${label}</span><span class="tool-switch" data-checked=${controls[key]}></span></button>`)}
        <button class="dropdown-item" role="menuitem" disabled=${!compactAvailable} onClick=${() => openEditor("compact")}>${icon(ICON_COMPRESS)}<span>${t.compact}</span></button>
        <button class="dropdown-item" role="menuitem" disabled=${!settingsEditable || !state.composer.advanced} onClick=${() => openEditor("advanced")}>${icon(ICON_TUNE)}<span>${t.advanced}</span></button>
      </${MoreMenu}>`}
      ${validPanel && !retainedTools && html`<${DetailSheet} key=${`${toolPanel.connectionId}:${toolPanel.seq}:${toolPanel.kind}`}
        title=${toolPanel.kind === "thinking" ? t.thinking : t.serviceTier} display=${state.display} focusReturn=${toolsButton} onClose=${() => setToolPanel(null)}>
        <div class="tool-settings">
          <div class="tool-setting-header">
            ${icon(toolPanel.kind === "thinking" ? ICON_NEUROLOGY : ICON_SPEED, toolPanel.kind === "thinking" ? "0 0 960 960" : "0 0 24 24")}
            <div><strong>${toolPanel.kind === "thinking" ? t.thinking : t.serviceTier}</strong><p>${toolPanel.kind === "thinking"
              ? !controls.displayedThinkingEnabled ? t.off : controls.displayedThinkingBudgetEnabled ? t.tokens(controls.displayedThinkingBudgetTokens) : t.efforts[controls.displayedThinkingLevel] ?? controls.displayedThinkingLevel : t.tierDescription}</p></div>
            <button class="tool-switch" role="switch" aria-label=${toolPanel.kind === "thinking" ? t.thinking : t.serviceTier}
              aria-checked=${toolPanel.kind === "thinking" ? controls.displayedThinkingEnabled : controls.openAiServiceTierEnabled}
              data-checked=${toolPanel.kind === "thinking" ? controls.displayedThinkingEnabled : controls.openAiServiceTierEnabled}
              disabled=${!settingsEditable || (toolPanel.kind === "thinking" ? !controls.thinkingCanDisable && controls.displayedThinkingEnabled : !capabilityEnabled)}
              onClick=${() => sync.setting(toolPanel.kind === "thinking" ? "thinkingEnabled" : "openAiServiceTierEnabled",
                !(toolPanel.kind === "thinking" ? controls.displayedThinkingEnabled : controls.openAiServiceTierEnabled), toolPanel)}></button>
          </div>
          ${toolPanel.kind === "thinking" ? html`
            <div class="tool-setting-body" data-disabled=${!settingsEditable || !controls.displayedThinkingEnabled || controls.displayedThinkingBudgetEnabled || controls.thinkingEfforts.length < 2}>
              ${icon(ICON_NEUROLOGY, "0 0 960 960")}
              <div class="tool-setting-title"><strong>${t.thinkingEffort}</strong><span>${t.efforts[controls.thinkingEfforts[slider?.key === "effort" ? slider.index : Math.max(0, controls.thinkingEfforts.indexOf(controls.displayedThinkingLevel))]] ?? controls.displayedThinkingLevel}</span></div>
              <p>${t.effortDescription}</p>
              <input type="range" aria-label=${t.thinkingEffort} min="0" max=${Math.max(1, controls.thinkingEfforts.length - 1)} step="1"
                style=${sliderStyle(0, Math.max(1, controls.thinkingEfforts.length - 1), slider?.key === "effort" ? slider.index : Math.max(0, controls.thinkingEfforts.indexOf(controls.displayedThinkingLevel)))}
                value=${slider?.key === "effort" ? slider.index : Math.max(0, controls.thinkingEfforts.indexOf(controls.displayedThinkingLevel))}
                disabled=${!settingsEditable || !controls.displayedThinkingEnabled || controls.displayedThinkingBudgetEnabled || controls.thinkingEfforts.length < 2}
                onInput=${e => setSlider({ key: "effort", index: Number(e.currentTarget.value) })}
                onChange=${e => { const value = controls.thinkingEfforts[Number(e.currentTarget.value)]; if (value === controls.displayedThinkingLevel || !sync.setting("thinkingLevel", value, toolPanel)) setSlider(null); }} />
            </div>
            ${controls.thinkingSupportsBudget && html`<button class="tool-advanced" aria-expanded=${advancedThinking} onClick=${() => setAdvancedThinking(!advancedThinking)}>
              ${advancedThinking ? t.hideAdvanced : t.advanced}${icon(ICON_CHEVRON_DOWN)}</button>
              <div class="tool-budget-section" data-visible=${advancedThinking}>
                <div><div class="tool-setting-header" data-disabled=${!controls.displayedThinkingEnabled}>
                  ${icon(ICON_NEUROLOGY, "0 0 960 960")}
                  <div><strong>${t.useBudget}</strong><p>${t.budgetNote}</p></div><button class="tool-switch" role="switch" aria-label=${t.useBudget}
                    aria-checked=${controls.displayedThinkingBudgetEnabled} data-checked=${controls.displayedThinkingBudgetEnabled}
                    disabled=${!settingsEditable || !controls.displayedThinkingEnabled} onClick=${() => sync.setting("thinkingBudgetEnabled", !controls.displayedThinkingBudgetEnabled, toolPanel)}></button></div>
                ${controls.displayedThinkingBudgetEnabled && html`<div class="tool-setting-body tool-budget-slider" data-disabled=${!controls.displayedThinkingEnabled}>
                  ${icon(ICON_NEUROLOGY, "0 0 960 960")}
                  <div class="tool-setting-title"><strong>${t.budget}</strong><span>${t.tokens(slider?.key === "budget" ? controls.thinkingBudgetPresets[slider.index] : controls.displayedThinkingBudgetTokens)}</span></div>
                  <input type="range" aria-label=${t.budget} min="0" max=${controls.thinkingBudgetPresets.length - 1} step="1"
                    style=${sliderStyle(0, controls.thinkingBudgetPresets.length - 1, slider?.key === "budget" ? slider.index : controls.thinkingBudgetPresets.reduce((best, value, i, values) => Math.abs(value - controls.displayedThinkingBudgetTokens) < Math.abs(values[best] - controls.displayedThinkingBudgetTokens) ? i : best, 0))}
                    value=${slider?.key === "budget" ? slider.index : controls.thinkingBudgetPresets.reduce((best, value, i, values) => Math.abs(value - controls.displayedThinkingBudgetTokens) < Math.abs(values[best] - controls.displayedThinkingBudgetTokens) ? i : best, 0)}
                    disabled=${!settingsEditable || !controls.displayedThinkingEnabled} onInput=${e => setSlider({ key: "budget", index: Number(e.currentTarget.value) })}
                    onChange=${e => { if (!sync.setting("thinkingBudgetTokens", controls.thinkingBudgetPresets[Number(e.currentTarget.value)], toolPanel)) setSlider(null); }} />
                </div>`}</div>
              </div>`}
          ` : html`<div class="tool-setting-body" data-disabled=${!capabilityEnabled || !controls.openAiServiceTierEnabled}>
            ${icon(ICON_SPEED)}
            <div class="tool-setting-title"><strong>${t.serviceTier}</strong><span>${t.tiers[controls.serviceTiers[slider?.key === "tier" ? slider.index : Math.max(0, controls.serviceTiers.indexOf(controls.displayedServiceTier))]]}</span></div>
            <p>${t.tierDescription}</p><input type="range" aria-label=${t.serviceTier} min="0" max=${Math.max(1, controls.serviceTiers.length - 1)} step="1"
              style=${sliderStyle(0, Math.max(1, controls.serviceTiers.length - 1), slider?.key === "tier" ? slider.index : Math.max(0, controls.serviceTiers.indexOf(controls.displayedServiceTier)))}
              value=${slider?.key === "tier" ? slider.index : Math.max(0, controls.serviceTiers.indexOf(controls.displayedServiceTier))}
              disabled=${!capabilityEnabled || !controls.openAiServiceTierEnabled || controls.serviceTiers.length < 2} onInput=${e => setSlider({ key: "tier", index: Number(e.currentTarget.value) })}
              onChange=${e => { const value = controls.serviceTiers[Number(e.currentTarget.value)]; if (value === controls.displayedServiceTier || !sync.setting("openAiServiceTier", value, toolPanel)) setSlider(null); }} />
          </div>`}
        </div>
      </${DetailSheet}>`}
      ${validEditor && !retainedTools && html`<${ConversationEditor} key=${`${editor.connectionId}:${editor.seq}:${editor.kind}`}
        editor=${editor} state=${state} focusReturn=${toolsButton} onClose=${() => setEditor(null)} />`}
      ${pending && html`<${AttachmentEditor} key=${`${state.connectionId}:${state.composer.seq}:${pending.id}`} attachment=${pending} editable=${editable}
        onPreview=${index => setViewer({ ...sync.attachmentTarget(), items: Array.from({ length: pending.pagePreviewCount }, (_, i) => ({ ...pending, kind: "page", index: i })), index })} />`}
      ${currentViewer && html`<${AttachmentViewer} viewer=${viewer}
        onNavigate=${index => setViewer(current => current === viewer ? { ...current, index } : current)}
        onClose=${() => setViewer(current => current === viewer ? null : current)} />`}`;
}

function ConversationEditor({ editor, state, focusReturn, onClose }) {
  const dialog = useRef(null);
  const advanced = editor.kind === "advanced";
  const info = editor.initial;
  const fields = ["contextWindow", "temperature", "maxTokens", "topP", "frequencyPenalty", "presencePenalty"];
  const [draft, setDraft] = useState(() => Object.fromEntries(fields.map(key => [key, info.overrides?.[key] ?? null])));
  const [model, setModel] = useState(info.modelId || "");
  const [prompt, setPrompt] = useState(info.prompt || "");
  const [retain, setRetain] = useState(String(info.retainCount ?? 0));
  const [error, setError] = useState(null);
  const busy = !!state.pendingAction;
  useEffect(() => {
    const node = dialog.current;
    node.showModal();
    return () => { node.close(); focusReturn.current?.focus(); };
  }, []);
  useEffect(() => { if (!advanced && state.composer.compact?.compacting) onClose(); }, [state.composer.compact?.compacting]);
  function close() { if (!busy) onClose(); }
  function apply() {
    if (busy) return;
    if (advanced) {
      if (sync.editorCommand("advanced", { parameters: draft }, editor)) onClose();
    } else {
      const count = retain === "" ? NaN : Number(retain);
      if (!(model in state.composer.models)) setError(t.compactModelError);
      else if (!prompt.trim()) setError(t.compactPromptError);
      else if (!Number.isInteger(count) || count < 0 || count > 2147483647) setError(t.compactRetainError);
      else if (sync.editorCommand("compact", { modelId: model, text: prompt, retainCount: count }, editor)) onClose();
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
      }) : html`<label>${t.compactModel}<select aria-label=${t.compactModel} value=${model} disabled=${busy} onChange=${event => { setModel(event.currentTarget.value); setError(null); }}>
          ${!(model in state.composer.models) && html`<option value=${model}>${model || t.selectModel}</option>`}
          ${Object.keys(state.composer.models).sort().map(id => html`<option value=${id}>${state.composer.models[id]}</option>`)}</select></label>
        <label>${t.compactPrompt}<textarea rows="3" aria-label=${t.compactPrompt} value=${prompt} disabled=${busy}
          onInput=${event => { setPrompt(event.currentTarget.value); setError(null); }}></textarea></label>
        <label>${t.compactRetain}<input type="text" inputmode="numeric" aria-label=${t.compactRetain} value=${retain} disabled=${busy}
          onInput=${event => { setRetain(event.currentTarget.value.replace(/\D/g, "")); setError(null); }} /></label>
        ${error && html`<p class="conversation-editor-error" role="alert">${error}</p>`}`}
    </div>
    <footer>${advanced && html`<button class="reset-all" type="button" disabled=${busy} onClick=${() => setDraft(Object.fromEntries(fields.map(key => [key, null])))}>${t.reset}</button>`}
      <button type="button" disabled=${busy} onClick=${close}>${t.cancel}</button>
      <button type="button" disabled=${busy} onClick=${apply}>${advanced ? t.save : t.compact}</button>
    </footer></section></dialog>`;
}

function AttachmentTile({ attachment: a, editable, onPreview }) {
  const source = a.state === "READY" && !a.unavailable && a.storage === "APP_PRIVATE"
    ? a.type === "image" ? sync.attachmentUrl(a.id, "source") : a.type === "video" && a.framePreviewCount ? sync.attachmentUrl(a.id, "frame") : null : null;
  const [loadedSource, setLoadedSource] = useState(null);
  const [failedSource, setFailedSource] = useState(null);
  const decoded = !!source && loadedSource === source;
  const failed = !!source && failedSource === source;
  const [loading, setLoading] = useState(false);
  const [initial, setInitial] = useState(true);
  useLayoutEffect(() => { const frame = requestAnimationFrame(() => setInitial(false)); return () => cancelAnimationFrame(frame); }, []);
  const busy = a.state === "PROCESSING" || (!!source && !decoded && !failed);
  useEffect(() => { setLoading(false); if (!busy) return; const timer = setTimeout(() => setLoading(true), 200); return () => clearTimeout(timer); }, [busy]);
  const blocked = a.unavailable || a.storage !== "APP_PRIVATE";
  const retry = a.state === "FAILED" || failed;
  const canPreview = !blocked && a.state === "READY" && (a.type === "file" ? a.text != null : a.type === "pdf" ? a.pagePreviewCount > 0 : !source || decoded);
  const fileStyle = a.type !== "video" && a.type !== "image";
  const label = a.type === "pdf" ? "PDF" : (a.name?.includes(".") ? a.name.split(".").at(-1) : a.type).toUpperCase().slice(0, 4);
  return html`<div class="attachment-tile" data-state=${a.unavailable ? "unavailable" : a.state.toLowerCase()}>
    <button class="attachment-preview" type="button" title=${a.name || t.attachments} aria-label=${retry ? t.retry : a.name || t.attachments}
      disabled=${retry ? !editable : !canPreview} onClick=${() => retry ? sync.attachmentCommand("attachment_retry", a.id) : onPreview()}>
      ${source && html`<img key=${source} src=${source} alt="" onLoad=${() => setLoadedSource(source)} onError=${() => setFailedSource(source)} style=${{ opacity: decoded ? 1 : 0 }} />`}
      <span class=${`attachment-status ${fileStyle ? a.type === "pdf" ? "pdf-placeholder" : "file-placeholder" : ""}`} style=${{ opacity: initial || decoded ? 0 : 1 }}>
        ${fileStyle ? html`<small>${label}</small>` : icon(a.type === "video" ? ICON_VIDEO : ICON_IMAGE)}
      </span>
      <span class="attachment-status attachment-progress" style=${{ opacity: !initial && busy && loading ? 1 : 0 }}><${Spinner} size=${24} stroke=${3} /></span>
      <span class="attachment-status attachment-error" style=${{ opacity: !initial && retry ? 1 : 0 }}>${icon(a.type === "image" ? ICON_BROKEN_IMAGE : ICON_ERROR)}</span>
    </button>
    <button class="attachment-remove" type="button" title=${t.remove} aria-label=${t.remove} disabled=${!editable}
      onClick=${() => sync.attachmentCommand("attachment_remove", a.id)}>${icon(ICON_CLOSE)}</button>
  </div>`;
}

function AttachmentEditor({ attachment: a, editable, onPreview }) {
  const dialog = useRef(null);
  const pdf = a.type === "pdf";
  const [pages, setPages] = useState(() => Array.from({ length: Math.min(5, a.pageCount || 0) }, (_, i) => i));
  const [countMode, setCountMode] = useState(true);
  const [count, setCount] = useState(String(a.defaultFrameCount));
  const seconds = Math.floor((a.durationMs || 0) / 1000);
  const [interval, setInterval] = useState(Math.max(1, Math.floor(seconds / a.defaultFrameCount)));
  const frames = countMode ? Number(count) : Math.max(2, Math.min(2147483647, Math.floor(seconds / interval)));
  const sliceMs = countMode ? Math.floor((a.durationMs || 0) / Math.max(2, frames)) : interval * 1000;
  const loading = pdf && a.pagePreviewCount !== a.pageCount;
  const valid = pdf ? pages.length > 0 && !loading : Number.isInteger(frames) && frames >= 2 && frames <= 2147483647;
  function cancel() { if (editable) sync.attachmentCommand("attachment_remove", a.id); }
  useEffect(() => { dialog.current.showModal(); return () => dialog.current?.close(); }, []);
  return html`<dialog ref=${dialog} class="attachment-editor" aria-label=${pdf ? t.pdfTitle : t.videoTitle}
    onCancel=${event => { event.preventDefault(); cancel(); }} onClick=${event => { if (event.target === dialog.current) cancel(); }} onKeyDown=${event => event.stopPropagation()}>
    <section><h2>${pdf ? t.pdfTitle : t.videoTitle}</h2>
    ${pdf ? html`<p>${t.pdfSubtitle(a.pageCount)}</p><div class="attachment-editor-row"><span>${t.pagesSelected(pages.length)}</span>
      <button type="button" disabled=${loading || !editable} onClick=${() => setPages(pages.length === a.pageCount ? [] : Array.from({ length: a.pageCount }, (_, i) => i))}>${pages.length === a.pageCount ? t.deselectAll : t.selectAll}</button></div>
      ${loading ? html`<div class="pdf-loading"><${Spinner} size=${18} stroke=${3} /><p>${t.renderingPages(a.previewDone || 0, a.previewTotal || a.pageCount)}</p></div>` : html`
      <div class="pdf-grid">${Array.from({ length: a.pageCount }, (_, i) => html`<div class="pdf-page" data-selected=${pages.includes(i)} key=${i}>
        <button type="button" aria-label=${t.page(i + 1)} onClick=${() => onPreview(i)}><img src=${sync.attachmentUrl(a.id, "page", i)} alt=${t.page(i + 1)} /><span>${i + 1}</span></button>
        <input type="checkbox" aria-label=${t.page(i + 1)} checked=${pages.includes(i)} disabled=${!editable}
          onChange=${() => setPages(pages.includes(i) ? pages.filter(page => page !== i) : [...pages, i])} />
      </div>`)}</div>`}` : html`<p>${t.duration(`${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, "0")}`)}</p>
      <video class="video-edit-preview" src=${sync.attachmentUrl(a.id, "source")} preload="metadata" muted></video>
      <div class="video-modes">${[true, false].map(mode => html`<button type="button" aria-pressed=${countMode === mode} onClick=${() => setCountMode(mode)}>${mode ? t.byFrameCount : t.byInterval}</button>`)}</div>
      ${countMode ? html`<label>${t.frames(frames || 2)}<input type="number" min="2" max="2147483647" value=${count} aria-invalid=${!valid} onInput=${event => setCount(event.currentTarget.value)} /></label><p>${t.betweenFrames(sliceMs < 1000 ? `${sliceMs}ms` : `${Math.round(sliceMs / 1000)}s`)}</p>`
        : html`<label>${t.interval(interval)}<input type="range" min="1" max=${Math.max(1, Math.min(seconds, 30))} style=${sliderStyle(1, Math.max(1, Math.min(seconds, 30)), interval)} value=${interval} onInput=${event => setInterval(Number(event.currentTarget.value))} /></label><p>${t.frames(frames)}</p>`}`}
    <footer><button type="button" disabled=${!editable} onClick=${cancel}>${t.cancel}</button>
      <button class="filled" type="button" disabled=${!editable || !valid} onClick=${() => sync.attachmentCommand(pdf ? "attachment_pdf" : "attachment_video", a.id,
        pdf ? { pages } : { frameCount: frames, intervalMs: sliceMs })}>${pdf ? t.sendPages(pages.length) : t.extractFrames(frames)}</button></footer>
    </section></dialog>`;
}

export function AttachmentViewer({ viewer, onNavigate, onClose }) {
  const dialog = useRef(null);
  const index = viewer.index || 0;
  const [scale, setScale] = useState(1);
  const file = viewer.file;
  const item = viewer.items?.[index];
  const source = item && (item.url ?? sync.attachmentUrl(item.id, item.kind, item.index));
  const [loadedSource, setLoadedSource] = useState(null);
  const [failedSource, setFailedSource] = useState(null);
  const loaded = !!source && loadedSource === source;
  const failed = !!source && failedSource === source;
  useEffect(() => { dialog.current.showModal(); return () => dialog.current?.close(); }, []);
  function navigate(next) { setScale(1); onNavigate(next); }
  return html`<dialog ref=${dialog} class=${`tool-media-viewer attachment-viewer ${file ? "text-file-viewer" : ""}`} aria-label=${file?.name || item?.name || t.attachments}
    onCancel=${event => { event.preventDefault(); onClose(); }} onKeyDown=${event => {
      event.stopPropagation();
      if (event.key === "Escape") { event.preventDefault(); onClose(); return; }
      if (event.key === "ArrowLeft" && index > 0) navigate(index - 1);
      if (event.key === "ArrowRight" && index < (viewer.items?.length || 0) - 1) navigate(index + 1);
    }}>
    ${file ? html`<div class="text-file-content">${/\.(md|markdown)$/i.test(file.name || "") ? html`<${Markdown} text=${{ markdown: file.text, math: [] }} />` : html`<pre>${file.text}</pre>`}</div>`
      : item?.type === "video" && item.kind === "source" ? html`<video key=${source} src=${source} controls autoplay
        onLoadedData=${() => setLoadedSource(source)} onError=${() => setFailedSource(source)} style=${{ opacity: loaded ? 1 : 0 }}></video>`
      : html`<div class="tool-media-scroll" onDblClick=${() => { if (loaded) setScale(scale === 1 ? 3 : 1); }}><img key=${source} class="attachment-full-image" src=${source}
        alt=${item.name || t.attachments} onLoad=${() => setLoadedSource(source)} onError=${() => setFailedSource(source)}
        style=${{ width: `${scale * 100}%`, height: `${scale * 100}%`, opacity: loaded ? 1 : 0 }} /></div>`}
    ${!file && html`<div class="attachment-viewport-status" style=${{ opacity: !loaded && !failed ? 1 : 0 }}><${Spinner} size=${18} stroke=${3} label=${t.attachments} color="inherit" /></div>
      <div class="attachment-viewport-status" style=${{ opacity: failed ? 1 : 0 }} role=${failed ? "alert" : null}>${icon(item.type === "image" || item.kind === "page" ? ICON_BROKEN_IMAGE : ICON_ERROR)}</div>`}
    <div class="tool-media-controls"><span class="tool-media-count">${file?.name || `${index + 1} / ${viewer.items.length}`}</span>
      <button class="detail-sheet-icon" type="button" title=${t.close} aria-label=${t.close} onClick=${onClose}>${icon(ICON_CLOSE)}</button></div>
    ${!file && viewer.items.length > 1 && html`<button class="tool-media-previous detail-sheet-icon" type="button" aria-label=${t.previous} disabled=${index === 0} onClick=${() => navigate(index - 1)}>${icon(ICON_CHEVRON_RIGHT)}</button>
      <button class="tool-media-next detail-sheet-icon" type="button" aria-label=${t.next} disabled=${index === viewer.items.length - 1} onClick=${() => navigate(index + 1)}>${icon(ICON_CHEVRON_RIGHT)}</button>`}
  </dialog>`;
}
