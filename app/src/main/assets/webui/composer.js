import { useEffect, useLayoutEffect, useRef, useState } from "./vendor/preact-hooks.mjs";
import { html } from "./html.js";
import { CircularProgress, Spinner } from "./material/progress.js";
import { AttachmentTile, AttachmentEditor, AttachmentViewer } from "./attachments.js";
import { ConversationEditor } from "./conversation-editor.js";
import { t } from "./i18n.js";
import { icon, ICON_ADD, ICON_ARROW_UPWARD, ICON_EXPAND_ALL, ICON_COLLAPSE_ALL, ICON_MORE_VERT, ICON_STOP, ICON_CHECK, ICON_CLOSE, ICON_ATTACH_FILE } from "./icons.js";
import { sync } from "./sync.js";
import { ChatToolsRow } from "./tools-row.js";
import { ComposerSettings, thinkingSummary } from "./composer-settings.js";
import { ICON_NEUROLOGY, ICON_MEMORY, ICON_SPEED, ICON_TERMINAL, ICON_LANGUAGE, ICON_COMPRESS, ICON_TUNE, ICON_GOOGLE, ICON_OPENAI } from "./icons.js";
import { ICON_IMAGE, ICON_VIDEO } from "./icons.js";

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
/**
 * `TextFieldLineLimits.MultiLine(1, 6)` in ChatComposerLayout.kt, in the units the stylesheet uses:
 * six 23 px lines over the textarea's own 8 px + 16 px vertical padding. style.css caps the field at
 * the same box (`max-height: calc(6 * 23px + 24px)`), so the cap the script measures with and the cap
 * the field paints with are one number, not two that have to be kept in step.
 */
const FIELD_MAX_HEIGHT = 6 * 23 + 24;
/** ChatBottomBar: surface card with the text field, the expand button and the controls row. */
export function Composer({ state, MoreMenu, expanded = false, onExpandedChange }) {
  const field = useRef(null);
  const host = useRef(null);
  const inputClip = useRef(null);
  const inputContent = useRef(null);
  const sizeMotion = useRef({ height: null, target: null, animation: null });
  const outerSpacer = useRef(null);
  const headerSpacer = useRef(null);
  const initialExpanded = useRef(expanded);
  const expandButton = useRef(null);
  const collapseButton = useRef(null);
  const expansionMotion = useRef({ expanded, outer: null, spacer: null, buttons: new Map() });
  // Native keeps the tools overlay anchored to the last measured non-expanded bar.
  useLayoutEffect(() => {
    const node = host.current;
    if (expanded) return;
    const measure = () => {
      if (!node.classList.contains("composer-expanded"))
        node.style.setProperty("--composer-collapsed-height", `${node.getBoundingClientRect().height}px`);
    };
    measure();
    const geometry = new ResizeObserver(measure);
    geometry.observe(node, { box: "border-box" });
    return () => geometry.disconnect();
  }, [expanded]);
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
  const [editor, setEditor] = useState(null);
  const controls = state.composer?.controls;
  // One connection's own context window, priced on the phone by ConversationContextProjector.
  // ChatApp takes it as `projection.usage ?: ContextWindowUsage(0, contextWindow, 0, false)`: a known
  // window always leaves the indicator clickable and a figure that has not landed yet reads as zero,
  // while the figure of the conversation just left is kept by the sync owner until the new one lands.
  const context = state.context;
  const contextBudget = context?.tokenBudget ?? 0;
  const contextEstimated = context?.estimatedTokens ?? 0;
  const contextReady = contextBudget > 0;
  const contextFraction = contextReady ? Math.min(1, Math.max(0, contextEstimated / contextBudget)) : 0;
  const contextUsage = contextReady ? t.contextUsage(context.estimatedLabel ?? "0", context.budgetLabel) : null;
  const contextPercent = contextReady ? `${contextUsagePercent(contextEstimated, contextBudget)}%` : null;
  const composerReady = state.connected && !!sync.attachmentTarget();
  const settingsAvailable = composerReady && !!controls;
  const settingsEditable = settingsAvailable && !state.pendingAction;
  const capabilityAvailable = settingsAvailable && !controls?.lowContextModeEnabled;
  const capabilityEnabled = capabilityAvailable && !state.pendingAction;
  const validPanel = toolPanel && toolPanel.connectionId === state.connectionId && toolPanel.seq === state.composer?.seq &&
    !!controls && (toolPanel.kind === "thinking" || (controls.openAiServiceTierAvailable && state.composer.modelValid));
  useEffect(() => { setToolsOpen(false); setToolPanel(null); setEditor(null); setContextOpen(false); }, [state.openId, state.connectionId]);
  useEffect(() => { if (!contextReady) setContextOpen(false); }, [contextReady]);
  function openTool(kind) {
    setToolPanel({ ...sync.attachmentTarget(), kind });
    setToolsOpen(false);
  }
  const validEditor = editor && state.connected && editor.connectionId === state.connectionId && editor.seq === state.composer?.seq && editor.conversationId === state.openId;
  // ComposerToolsMenuContent: enabled = canCompact && !isCompacting, where canCompact is
  // "a conversation, not loading, not switching, not stopping".
  const compactAvailable = settingsAvailable && !!state.openId && state.openStatus === "ready" &&
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
    // ComposerStatusColumn keys its AnimatedContent on the queued ids alone, so switching to another
    // conversation is an ordinary content change: the old rows fade out (140 ms) while the stack
    // resizes (220 ms). Only the very first snapshot is placed without motion.
    if (motion.owner !== null && motion.owner !== queueOwner) motion.owner = queueOwner;
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
  // ComposerModelSelector keeps one label and crossfades to the next. The label is the phone's
  // identity for both the fade and the clip edge, so two conversations on the same model do not
  // animate, and a model that arrives later moves the edge instead of replacing the row.
  const modelSlot = useRef(null);
  const modelClip = useRef({ identity: null, target: 0, deadline: 0, animation: null });
  const modelFade = useRef({ label: modelLabel, animations: new Map() });
  const [modelFrames, setModelFrames] = useState([{ label: modelLabel, initialAlpha: 1 }]);
  const modelPresentations = modelFrames.some(frame => frame.label === modelLabel) ? modelFrames
    : [...modelFrames, { label: modelLabel, initialAlpha: 0 }];
  useEffect(() => { setModelOpen(false); }, [state.openId, state.connected]);
  const phase = state.composer?.phase ?? "IDLE";
  const waiting = phase === "WAITING";
  const busy = state.pendingAction || phase !== "IDLE" || state.composer?.stopping;
  const actionIcon = busy ? "busy" : state.generating && !state.text.trim() && !attachments.length ? "stop" : "send";
  const actionSlot = useRef(null);
  const actionAnimations = useRef(new Map());
  const [actionFrames, setActionFrames] = useState([{ icon: actionIcon, initialAlpha: 1 }]);
  const actionPresentations = actionFrames.some(frame => frame.icon === actionIcon) ? actionFrames
    : [...actionFrames, { icon: actionIcon, initialAlpha: 0 }];
  useLayoutEffect(() => {
    const animations = actionAnimations.current;
    for (const node of actionSlot.current.querySelectorAll(".send-icon")) {
      const identity = node.dataset.icon;
      const target = identity === actionIcon ? 1 : 0;
      const previous = animations.get(identity);
      if (previous?.target === target) continue;
      const from = getComputedStyle(node).opacity;
      previous?.animation.cancel();
      const animation = node.animate([{ opacity: from }, { opacity: target }], {
        duration: 200, easing: "linear", fill: "both",
      });
      animations.set(identity, { target, animation });
      if (!target) animation.finished.then(() => {
        if (animations.get(identity)?.animation !== animation) return;
        animations.delete(identity);
        setActionFrames(frames => frames.filter(frame => frame.icon !== identity));
      }).catch(() => {});
    }
    if (actionFrames !== actionPresentations) setActionFrames(actionPresentations);
  }, [actionIcon, actionPresentations.length]);
  useLayoutEffect(() => () => {
    actionAnimations.current.forEach(({ animation }) => animation.cancel());
  }, []);
  const stop = state.generating && !state.text.trim() && !attachments.length;
  const canDrain = !state.generating && !state.text.trim() && !attachments.length && state.composer?.queue?.length;
  // ChatBottomBar gates the attachment row and the add menu on nothing but the submission freeze, and
  // isFrozen is the send transaction (`phase != IDLE`) - not generation, not a model switch - so a
  // switch in flight keeps these controls live. The tools IconButton is gated on nothing at all
  // (`menuAnchor(enabled = true)`); only the items inside its menu carry their own gates.
  const editable = composerReady && phase === "IDLE";
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
    node.multiple = true;
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
    if (actionable) {
      if (stop && !busy) sync.stopGeneration();
      else sync.submit();
    }
  }
  useLayoutEffect(() => {
    const motion = expansionMotion.current;
    const changed = motion.expanded !== expanded;
    motion.expanded = expanded;
    if (changed || (state.display?.reduceMotion && motion.outer)) {
      motion.outer?.cancel();
      motion.outer = null;
      outerSpacer.current.style.height = "0px";
      if (expanded && !state.display?.reduceMotion) {
        const animation = outerSpacer.current.animate([{ height: "44px" }, { height: "0px" }],
          { duration: 400, easing: "cubic-bezier(0.15, 0.5, 0.25, 1)", fill: "both" });
        motion.outer = animation;
        animation.onfinish = () => {
          if (motion.outer !== animation) return;
          motion.outer = null;
          animation.cancel();
        };
      }
    }
    if (changed || (state.display?.reduceMotion && motion.spacer)) {
      const node = headerSpacer.current;
      const height = node.getBoundingClientRect().height;
      const opacity = getComputedStyle(node).opacity;
      motion.spacer?.cancel();
      motion.spacer = null;
      node.style.height = expanded ? "44px" : "0px";
      node.style.opacity = expanded ? "1" : "0";
      if (!expanded && height > 0) {
        const animation = node.animate([
          { height: state.display?.reduceMotion ? "44px" : `${height}px`, opacity },
          { height: state.display?.reduceMotion ? "44px" : "0px", opacity: 0 },
        ], { duration: 250, easing: "cubic-bezier(0.4, 0, 0.2, 1)", fill: "both" });
        motion.spacer = animation;
        animation.onfinish = () => {
          if (motion.spacer !== animation) return;
          motion.spacer = null;
          animation.cancel();
        };
      }
    }
    if (!changed) return;
    for (const [node, visible] of [[expandButton.current, !expanded], [collapseButton.current, expanded]]) {
      const opacity = getComputedStyle(node).opacity;
      motion.buttons.get(node)?.cancel();
      motion.buttons.delete(node);
      node.style.opacity = visible ? "1" : "0";
      node.style.visibility = "visible";
      if (node === expandButton.current && !visible) { node.style.visibility = "hidden"; continue; }
      const animation = node.animate([{ opacity }, { opacity: visible ? 1 : 0 }],
        { duration: 250, easing: "cubic-bezier(0.4, 0, 0.2, 1)", fill: "both" });
      motion.buttons.set(node, animation);
      animation.onfinish = () => {
        if (motion.buttons.get(node) !== animation) return;
        motion.buttons.delete(node);
        if (!visible) node.style.visibility = "hidden";
        animation.cancel();
      };
    }
  }, [expanded, state.display?.reduceMotion]);
  useLayoutEffect(() => () => {
    const motion = expansionMotion.current;
    motion.outer?.cancel();
    motion.spacer?.cancel();
    motion.buttons.forEach(animation => animation.cancel());
  }, []);
  function changeExpanded(value) {
    if (expansionMotion.current.outer) return;
    onExpandedChange(value);
    field.current?.focus();
  }
  // Expanded weight supplies exact constraints; collapsed content owns the size tween.
  function fitField() {
    const node = field.current;
    if (!node) return;
    if (expanded) { node.style.height = ""; return; }
    const overflowY = node.style.overflowY;
    node.style.overflowY = "hidden";
    node.style.height = "auto";
    node.style.height = `${Math.min(node.scrollHeight, FIELD_MAX_HEIGHT)}px`;
    node.style.overflowY = overflowY;
  }
  // Only the input/attachment column owns the 400 ms size transition. Queue and controls are
  // siblings, so their geometry cannot be squeezed by an animated total bar height.
  function travel() {
    const node = inputClip.current;
    const content = inputContent.current;
    if (!node || !content) return;
    const motion = sizeMotion.current;
    fitField();
    if (expanded) {
      motion.animation?.cancel();
      motion.animation = null;
      node.style.height = "";
      motion.target = content.getBoundingClientRect().height;
      motion.height = motion.target;
      return;
    }
    const target = content.getBoundingClientRect().height;
    if (motion.target === target && (!state.display?.reduceMotion || !motion.animation)) return;
    const from = motion.animation ? node.getBoundingClientRect().height : motion.height;
    motion.animation?.cancel();
    motion.animation = null;
    motion.target = target;
    motion.height = target;
    node.style.height = `${target}px`;
    if (from == null || Math.abs(from - target) <= 0.5 || state.display?.reduceMotion) return;
    const animation = node.animate([{ height: `${from}px` }, { height: `${target}px` }],
      { duration: 400, easing: "cubic-bezier(0.4, 0, 0.2, 1)", fill: "both" });
    motion.animation = animation;
    animation.onfinish = () => {
      if (motion.animation !== animation) return;
      motion.animation = null;
      animation.cancel();
    };
  }
  const travelRef = useRef(travel);
  useLayoutEffect(() => { travelRef.current = travel; travel(); },
    [expanded, state.text, attachments, state.display?.reduceMotion]);
  useLayoutEffect(() => {
    const geometry = new ResizeObserver(() => travelRef.current());
    geometry.observe(inputContent.current);
    geometry.observe(field.current);
    geometry.observe(queueHost.current);
    geometry.observe(headerSpacer.current);
    geometry.observe(host.current.closest(".chat"));
    const refresh = () => travelRef.current();
    document.fonts.addEventListener("loadingdone", refresh);
    return () => { geometry.disconnect(); document.fonts.removeEventListener("loadingdone", refresh); };
  }, []);
  useLayoutEffect(() => () => sizeMotion.current.animation?.cancel(), []);
  // The slot takes the clip width so the controls after it follow the visible edge, exactly as the
  // phone's Box does; the button inside keeps its measured target width. One 400 ms deadline owns
  // the move: a new target rebases from the current edge inside the same deadline, a new label
  // restarts from where the edge stands, and no correction frame follows the terminal one.
  useLayoutEffect(() => {
    const slot = modelSlot.current;
    if (!slot) return undefined;
    const clip = modelClip.current;
    const measure = () => {
      const label = slot.querySelector(`[data-model-label="${CSS.escape(modelLabel)}"]`);
      if (!label) return;
      // ComposerModelSelector is the only weighted child of ComposerControlGroup, so its room is the
      // space the group itself was given, minus the group's own padding and the fixed buttons beside
      // it. The room comes from the row's constraint and never from the group's measured width,
      // because that width is what this very button sets.
      const group = slot.parentElement;
      const row = group.parentElement;
      const rowStyle = getComputedStyle(row);
      const rowGap = parseFloat(rowStyle.columnGap) || 0;
      const send = [...row.children].find((node) => node !== group);
      const groupRoom = row.clientWidth - parseFloat(rowStyle.paddingLeft) - parseFloat(rowStyle.paddingRight) -
        (send ? send.offsetWidth + rowGap : 0);
      const style = getComputedStyle(group);
      const beside = [...group.children].filter(node => node !== slot);
      const available = groupRoom - parseFloat(style.paddingLeft) - parseFloat(style.paddingRight) -
        beside.reduce((sum, node) => sum + node.offsetWidth, 0);
      // label + 8 dp each side, never under ButtonDefaults.MinWidth (64 dp), and the room caps it.
      const target = Math.min(Math.max(label.scrollWidth + 16, 64), available);
      // The phone caps the Text itself at availableWidth - 16 dp so an over-long name ellipsizes
      // inside the button instead of being cut hard by the clip edge.
      slot.style.setProperty("--model-label-room", `${Math.max(0, available - 16)}px`);
      const initial = clip.identity === null;
      const changed = clip.identity !== modelLabel;
      if (!changed && clip.target === target && (!state.display?.reduceMotion || !clip.animation)) return;
      const from = initial ? target : parseFloat(getComputedStyle(slot).width) || target;
      clip.animation?.cancel();
      clip.animation = null;
      if (changed) clip.deadline = performance.now() + 400;
      clip.identity = modelLabel;
      clip.target = target;
      modelButton.current.style.width = `${target}px`;
      if (initial || state.display?.reduceMotion || performance.now() >= clip.deadline) {
        slot.style.width = `${target}px`;
        clip.deadline = 0;
        return;
      }
      const animation = slot.animate([{ width: `${from}px` }, { width: `${target}px` }],
        { duration: clip.deadline - performance.now(), easing: "cubic-bezier(0.4, 0, 0.2, 1)", fill: "forwards" });
      clip.animation = animation;
      animation.onfinish = () => {
        if (clip.animation !== animation) return;
        slot.style.width = `${clip.target}px`;
        animation.cancel();
        clip.animation = null;
        clip.deadline = 0;
      };
    };
    measure();
    const geometry = new ResizeObserver(measure);
    // The row owns the constraint; the group's own width is what this effect animates, so watching it
    // would re-enter on every clip frame. Each label is watched because its width is the measurement.
    [slot.parentElement.parentElement, ...slot.querySelectorAll(".model-label")].forEach(node =>
      geometry.observe(node, { box: "border-box" }));
    document.fonts.addEventListener("loadingdone", measure);
    return () => { geometry.disconnect(); document.fonts.removeEventListener("loadingdone", measure); };
  }, [modelLabel, modelPresentations.length, state.display?.reduceMotion]);
  // Crossfade(tween(200, FastOutSlowInEasing)): every label keeps its own width while it fades, so an
  // outgoing longer name is cut only by the moving clip and never ellipsizes early.
  useLayoutEffect(() => {
    const fade = modelFade.current;
    if (fade.label === modelLabel) return;
    const labels = [...(modelSlot.current?.querySelectorAll(".model-label") ?? [])];
    for (const label of labels) {
      const alpha = getComputedStyle(label).opacity;
      fade.animations.get(label.dataset.modelLabel)?.cancel();
      label.style.opacity = alpha;
    }
    fade.animations.clear();
    fade.label = modelLabel;
    setModelFrames(modelPresentations);
    for (const label of labels) {
      const identity = label.dataset.modelLabel;
      const animation = label.animate([{ opacity: getComputedStyle(label).opacity },
        { opacity: identity === modelLabel ? 1 : 0 }],
      { duration: 200, easing: "cubic-bezier(0.4, 0, 0.2, 1)", fill: "forwards" });
      fade.animations.set(identity, animation);
      if (identity === modelLabel) animation.onfinish = () => {
        if (fade.animations.get(identity) !== animation) return;
        labels.forEach(node => { node.style.opacity = node === label ? "1" : "0"; });
        fade.animations.forEach(owned => owned.cancel());
        fade.animations.clear();
        setModelFrames([{ label: modelLabel, initialAlpha: 1 }]);
      };
    }
  }, [modelLabel]);
  useLayoutEffect(() => () => {
    modelClip.current.animation?.cancel();
    modelFade.current.animations.forEach(animation => animation.cancel());
  }, []);
  return html`
    <div class=${`composer-host ${expanded ? "composer-expanded" : ""}`} ref=${host}>
      <${ChatToolsRow} state=${state} />
      <div class="composer-expanded-backdrop" aria-hidden="true"></div>
      <div class="composer-outer-spacer" ref=${outerSpacer} aria-hidden="true"></div>
      <form class="composer" onSubmit=${(event) => {
        event.preventDefault();
        submit();
      }}>
        <div class="composer-header-spacer" ref=${headerSpacer} aria-hidden="true"
          style=${{ height: initialExpanded.current ? "44px" : "0px", opacity: initialExpanded.current ? 1 : 0 }}></div>
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
        <div class="composer-input-clip" ref=${inputClip}>
        <div class="composer-input-content" ref=${inputContent}>
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
          <button class="expand-button" ref=${expandButton} type="button" aria-label=${t.expand}
            aria-expanded="false" inert=${expanded} aria-hidden=${expanded ? "true" : null}
            style=${{ opacity: initialExpanded.current ? 0 : 1, visibility: initialExpanded.current ? "hidden" : "visible" }}
            onPointerDown=${event => event.preventDefault()} onClick=${() => changeExpanded(true)}>
            ${icon(ICON_EXPAND_ALL, "0 0 960 960")}
          </button>
        </div>
        </div>
        </div>
        <div class="composer-controls">
          <div class="control-group">
            <button ref=${addButton} class="control-icon" type="button" aria-label=${t.addAttachment}
              aria-haspopup="menu" aria-expanded=${addOpen} onClick=${() => { setRetainedAdd(true); setAddOpen(!addOpen); }}>
              ${icon(ICON_ADD)}
            </button>
            <button class="model-selector" ref=${modelSlot} type="button" aria-label=${t.selectModel}
                aria-haspopup="menu" aria-expanded=${modelOpen} data-valid=${!!state.composer?.modelValid}
                disabled=${!composerReady || !!state.pendingAction}
                onClick=${() => { setRetainedModelMenu(true); setModelOpen(!modelOpen); }}>
              <span ref=${modelButton} class="model-button">
                ${modelPresentations.map(frame => html`<span class="model-label" key=${frame.label}
                  data-model-label=${frame.label} aria-hidden=${frame.label === modelLabel ? null : "true"}
                  style=${{ opacity: frame.initialAlpha }}>${frame.label}</span>`)}
              </span>
            </button>
            <button ref=${contextButton} class="control-icon context-ring" type="button"
              aria-label=${contextReady ? `${t.context}: ${contextUsage}` : t.context}
              aria-haspopup="menu" aria-expanded=${contextOpen && contextReady} disabled=${!contextReady}
              data-over=${context?.overCompactThreshold ? "true" : null}
              onClick=${() => { if (contextReady) { setRetainedContext(true); setContextOpen(!contextOpen); } }}>
              <${CircularProgress} size=${20} stroke=${2.5} value=${contextFraction} />
            </button>
            <button ref=${toolsButton} class="control-icon" type="button" aria-label=${t.tools}
              aria-haspopup="menu" aria-expanded=${toolsOpen} onClick=${() => { setRetainedTools(true); setToolsOpen(!toolsOpen); }}>
              ${icon(ICON_MORE_VERT)}
            </button>
          </div>
          <button class="send-button" type="submit" aria-label=${waiting ? t.cancel : stop ? t.stop : t.send}
            onPointerDown=${(event) => event.preventDefault()}
            disabled=${!actionable} aria-busy=${busy ? "true" : null}>
            <span class="send-icon-slot" ref=${actionSlot} aria-hidden="true">
              ${actionPresentations.map(frame => html`<span class="send-icon" key=${frame.icon}
                data-icon=${frame.icon} style=${{ opacity: frame.initialAlpha }}>
                ${frame.icon === "busy" ? html`<${Spinner} size=${24} stroke=${3} color="inherit" />`
                  : icon(frame.icon === "stop" ? ICON_STOP : ICON_ARROW_UPWARD)}
              </span>`)}
            </span>
          </button>
        </div>
        <button class="expand-button collapse-button" ref=${collapseButton} type="button" aria-label=${t.collapse}
          aria-expanded="true" inert=${!expanded} aria-hidden=${expanded ? null : "true"}
          style=${{ opacity: initialExpanded.current ? 1 : 0, visibility: initialExpanded.current ? "visible" : "hidden" }}
          onPointerDown=${event => event.preventDefault()} onClick=${() => changeExpanded(false)}>
          ${icon(ICON_COLLAPSE_ALL, "0 0 960 960")}
        </button>
      </form>
    </div>
      ${retainedContext && context && html`<${MoreMenu} expanded=${contextOpen && contextReady} reduceMotion=${state.display.reduceMotion}
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
        anchor=${modelSlot} above=${true} onClose=${() => setModelOpen(false)}
        onExited=${(restoreFocus) => { setRetainedModelMenu(false); if (restoreFocus) modelSlot.current?.focus(); }}>
          ${modelChoices.length ? modelChoices.map(([id, label]) => html`
            <button class="dropdown-item" type="button" role="menuitemradio" aria-checked=${id === selectedModel}
              onClick=${() => { sync.selectModel(id); setModelOpen(false); }}>
              <span class="model-check">${id === selectedModel && icon(ICON_CHECK)}</span><span>${label}</span>
            </button>`) : html`<button class="dropdown-item" type="button" role="menuitem" disabled>${t.noModels}</button>`}
      </${MoreMenu}>`}
      ${retainedAdd && html`<${MoreMenu} expanded=${addOpen} reduceMotion=${state.display.reduceMotion} anchor=${addButton} above
        onClose=${() => setAddOpen(false)} onExited=${restore => { setRetainedAdd(false); if (restore) addButton.current?.focus(); }}>
        ${[["photos", ICON_IMAGE], ["videos", ICON_VIDEO], ["files", ICON_ATTACH_FILE]].map(([kind, path]) => html`
          <button class="dropdown-item" role="menuitem" type="button" disabled=${!editable} onClick=${() => choose(kind)}>${icon(path)}<span>${t[kind]}</span></button>`)}
      </${MoreMenu}>`}
      ${retainedTools && html`<${MoreMenu} expanded=${toolsOpen} reduceMotion=${state.display.reduceMotion} anchor=${toolsButton} above
        onClose=${() => setToolsOpen(false)} onExited=${restore => { setRetainedTools(false); if (restore) toolsButton.current?.focus(); }}>
        ${controls?.showLowContextMode && html`<button class="dropdown-item" role="menuitemcheckbox" aria-checked=${controls.lowContextModeEnabled}
          data-pending=${settingsAvailable && !!state.pendingAction} disabled=${!settingsEditable} onClick=${() => sync.setting("lowContextModeEnabled", !controls.lowContextModeEnabled)}>
          ${icon(ICON_MEMORY)}<span class="tool-label">${t.lowContext}</span><span class="tool-switch" data-checked=${controls.lowContextModeEnabled}></span></button>`}
        <div class="dropdown-item tool-split" data-disabled=${!settingsAvailable}>
          ${icon(ICON_NEUROLOGY, "0 0 960 960")}<button class="tool-label" role="menuitem" disabled=${!settingsAvailable} onClick=${() => openTool("thinking")}>
            ${t.thinking}<small>${thinkingSummary(controls)}</small></button>
          <button class="tool-switch" data-checked=${!!controls?.displayedThinkingEnabled} role="menuitemcheckbox" aria-label=${t.thinking}
            aria-checked=${!!controls?.displayedThinkingEnabled} data-pending=${settingsAvailable && !!state.pendingAction && (controls?.thinkingCanDisable || !controls?.displayedThinkingEnabled)} disabled=${!settingsEditable || (!controls?.thinkingCanDisable && controls?.displayedThinkingEnabled)}
            onClick=${() => sync.setting("thinkingEnabled", !controls.displayedThinkingEnabled)}></button>
        </div>
        ${controls?.isGemini && [["codeExecutionEnabled", t.codeExecution, ICON_TERMINAL], ["googleSearchEnabled", t.googleSearch, ICON_GOOGLE]].map(([key, label, path]) => html`
          <button class="dropdown-item" role="menuitemcheckbox" aria-checked=${controls[key]} data-pending=${capabilityAvailable && !!state.pendingAction} disabled=${!capabilityEnabled} onClick=${() => sync.setting(key, !controls[key])}>
            ${icon(path)}<span class="tool-label">${label}</span><small class="provider-badge">Gemini</small><span class="tool-switch" data-checked=${controls[key]}></span></button>`)}
        ${controls?.openAiServiceTierAvailable && state.composer.modelValid && html`<div class="dropdown-item tool-split" data-disabled=${!capabilityAvailable} data-pending=${capabilityAvailable && !!state.pendingAction}>
          ${icon(ICON_SPEED)}<button class="tool-label" role="menuitem" disabled=${!capabilityEnabled} onClick=${() => openTool("tier")}>
            ${t.serviceTier}<small>${controls.openAiServiceTierEnabled ? t.tiers[controls.displayedServiceTier] : t.off}</small></button>
          <button class="tool-switch" data-checked=${controls.openAiServiceTierEnabled} role="menuitemcheckbox" aria-label=${t.serviceTier}
            aria-checked=${controls.openAiServiceTierEnabled} data-pending=${capabilityAvailable && !!state.pendingAction} disabled=${!capabilityEnabled} onClick=${() => sync.setting("openAiServiceTierEnabled", !controls.openAiServiceTierEnabled)}></button>
        </div>`}
        ${[["openAiWebSearchAvailable", "openAiWebSearchEnabled", t.openAiSearch, ICON_OPENAI], ["webSearchAvailable", "webSearchEnabled", t.webSearch, ICON_LANGUAGE], ["shellAvailable", "shellEnabled", t.shell, ICON_TERMINAL]]
          .filter(([available]) => controls?.[available] && (available !== "openAiWebSearchAvailable" || state.composer.modelValid)).map(([, key, label, path]) => html`
          <button class="dropdown-item" role="menuitemcheckbox" aria-checked=${controls[key]} data-pending=${capabilityAvailable && !!state.pendingAction} disabled=${!capabilityEnabled} onClick=${() => sync.setting(key, !controls[key])}>
            ${icon(path)}<span class="tool-label">${label}</span><span class="tool-switch" data-checked=${controls[key]}></span></button>`)}
        <button class="dropdown-item" role="menuitem" disabled=${!compactAvailable} onClick=${() => openEditor("compact")}>${icon(ICON_COMPRESS)}<span>${t.compact}</span></button>
        <button class="dropdown-item" role="menuitem" disabled=${!settingsAvailable || !state.composer.advanced} onClick=${() => openEditor("advanced")}>${icon(ICON_TUNE)}<span>${t.advanced}</span></button>
      </${MoreMenu}>`}
      ${validPanel && !retainedTools && html`<${ComposerSettings} key=${`${toolPanel.connectionId}:${toolPanel.seq}:${toolPanel.kind}`}
        state=${state} target=${toolPanel} focusReturn=${toolsButton} onClose=${() => setToolPanel(null)} />`}
      ${validEditor && !retainedTools && html`<${ConversationEditor} key=${`${editor.connectionId}:${editor.seq}:${editor.kind}`} MoreMenu=${MoreMenu}
        editor=${editor} state=${state} focusReturn=${toolsButton} onClose=${() => setEditor(null)} />`}
      ${pending && html`<${AttachmentEditor} key=${`${state.connectionId}:${state.composer.seq}:${pending.id}`} attachment=${pending} editable=${editable}
        onPreview=${index => setViewer({ ...sync.attachmentTarget(), items: Array.from({ length: pending.pagePreviewCount }, (_, i) => ({ ...pending, kind: "page", index: i })), index })} />`}
      ${currentViewer && html`<${AttachmentViewer} viewer=${viewer}
        onNavigate=${index => setViewer(current => current === viewer ? { ...current, index } : current)}
        onClose=${() => setViewer(current => current === viewer ? null : current)} />`}`;
}
