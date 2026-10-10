// The open conversation's selected branch, drawn as the app's MessageList and MessageItem.
// Only rows near the screen are watched, so the phone sends just those bodies.
import { useEffect, useLayoutEffect, useRef, useState } from "./vendor/preact-hooks.mjs";
import { html } from "./html.js";
import { CircularProgress, Spinner } from "./material/progress.js";
import { Markdown, highlightSearch } from "./markdown.js";
import {
  icon, ICON_AGORA, ICON_BUILD, ICON_CALL_SPLIT, ICON_CHEVRON_DOWN, ICON_CHEVRON_RIGHT,
  ICON_COMPRESS, ICON_CONTENT_COPY, ICON_DELETE, ICON_EDIT, ICON_ERROR, ICON_IMAGE, ICON_INFO,
  ICON_MORE_VERT, ICON_NEUROLOGY, ICON_QUESTION_ANSWER, ICON_REFRESH, ICON_REPEAT,
  ICON_SCHEDULE, ICON_SELECT_ALL, ICON_SHARE, ICON_STOP_CIRCLE,
} from "./icons.js";
import { MoreMenu } from "./menu.js";
import { t } from "./i18n.js";
import { sync } from "./sync.js";
import { DetailSheet, CardIcon, InfoItem, useCardTitle, canOpenSheetItem } from "./detail-sheet.js";
import { attachScrollbar } from "./material/scrollbar.js";
import { AttachmentViewer } from "./composer.js";

// Rows within one screen above or below stay watched, so scrolling rarely meets a blank row.
const WATCH_MARGIN = "100% 0px";

/**
 * Programmatic bottom motion. Constants mirror the phone's scroll owners so the browser decelerates
 * into the tail instead of teleporting: RobustLazyListScroll.kt (FeedbackScrollSpec,
 * smoothSeekToItem), ChatScrollCoordinator.kt (SendFeedbackScrollSpec, 2 dp minimum step),
 * AbsoluteBottomScroll.kt (animateToAbsoluteBottom settling) and MessageListTailEffects.kt
 * (the attached streaming tail controller).
 */
const BOTTOM_MOTION = {
  seekTauMeasuredSeconds: 0.09,
  seekTauUnmeasuredSeconds: 0.16,
  seekMaxVelocityMeasuredViewports: 16,
  seekMaxVelocityUnmeasuredViewports: 52,
  maximumFrameStepViewportFraction: 0.82,
  minimumStepPx: 2,
  tolerancePx: 1.5,
  stableFrames: 4,
  blockedFrames: 12,
  maximumDurationMs: 30_000,
  sendStartupMs: 240,
  settlingGenerationMs: 700,
  settlingIdleMs: 192,
  settlingTimeoutMs: 1_600,
  settlingStableFrames: 6,
  attachTauSeconds: 0.055,
  attachMaxVelocityPxPerSecond: 2_800,
  attachThresholdPx: 0.5,
};

/** Compose coalescedScrollStep: exponential error-proportional travel inside a velocity cap. */
function coalescedScrollStep(errorPx, elapsedSeconds, timeConstantSeconds, maximumVelocity, minimumStepPx) {
  if (errorPx === 0 || elapsedSeconds <= 0) return 0;
  const fraction = 1 - Math.exp(-elapsedSeconds / Math.max(timeConstantSeconds, 0.001));
  const maximumStep = Math.max(minimumStepPx, maximumVelocity * elapsedSeconds);
  return Math.min(maximumStep, Math.max(-maximumStep, errorPx * fraction));
}

/** Numeric stand-in for a Compose Easing so a per-frame actor can reuse a CSS timing curve. */
function cubicBezierEasing(x1, y1, x2, y2) {
  const cx = 3 * x1;
  const bx = 3 * (x2 - x1) - cx;
  const ax = 1 - cx - bx;
  const cy = 3 * y1;
  const by = 3 * (y2 - y1) - cy;
  const ay = 1 - cy - by;
  const curve = (a, b, c, t) => ((a * t + b) * t + c) * t;
  return (progress) => {
    if (progress <= 0) return 0;
    if (progress >= 1) return 1;
    let t = progress;
    for (let step = 0; step < 8; step += 1) {
      const error = curve(ax, bx, cx, t) - progress;
      if (Math.abs(error) < 1e-6) break;
      const slope = (3 * ax * t + 2 * bx) * t + cx;
      if (Math.abs(slope) < 1e-6) break;
      t -= error / slope;
    }
    return curve(ay, by, cy, Math.min(1, Math.max(0, t)));
  };
}

// Compose FastOutSlowInEasing, the envelope the phone puts on a send scroll.
const fastOutSlowIn = cubicBezierEasing(0.4, 0, 0.2, 1);

// Compose LinearOutSlowInEasing, the curve the retry line reveals its graphemes with.
const linearOutSlowIn = cubicBezierEasing(0, 0, 0.2, 1);


function groupForMessage(message, groupKey) {
  const presentation = message?.presentation;
  if (!presentation) return null;
  if (presentation.compact?.key === groupKey) return presentation.compact;
  return presentation.blocks?.find((block) => block.type === "group" && block.group.key === groupKey)?.group ?? null;
}

function sheetItemsForMessage(message, groupKey) {
  if (!message?.presentation) return [];
  if (groupKey != null) return groupForMessage(message, groupKey)?.items ?? [];
  return message.presentation.blocks
    ?.filter((block) => block.type === "card")
    .map((block) => block.item) ?? [];
}


/** The text a row copies and edits: an ask_user row reads as its questions and answers, as on the phone. */
function readableText(message) {
  const source = message?.source;
  if (source?.kind !== "ask_user") return message?.text?.markdown ?? "";
  return source.askUser
    .map((item) => `${item.question}\n${item.answer ?? t.sourceUnanswered}`)
    .join("\n\n");
}

/** The phone answers a copy with a haptic; the nearest browser answer is the app's own notice. */
async function copyRowText(message) {
  try {
    await navigator.clipboard.writeText(readableText(message));
    sync.notify(t.copied);
  } catch (_) {
    sync.notify(t.copyFailed);
  }
}

/** Select Text stays inside the row: the browser's selection stands in for the phone's text mode. */
function selectRowText(node) {
  if (!node) return;
  const range = document.createRange();
  range.selectNodeContents(node);
  const selection = window.getSelection();
  selection?.removeAllRanges();
  selection?.addRange(range);
}

/** MessageInfoDialog keeps the device calendar in a fixed yyyy-MM-dd HH:mm:ss pattern. */
function formatRowTime(value) {
  const date = new Date(value);
  const pad = (part) => String(part).padStart(2, "0");
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ` +
    `${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`;
}

const SOURCE_LABELS = {
  task: [ICON_SCHEDULE, "sourceTask"],
  loop: [ICON_REPEAT, "sourceLoop"],
  ask_user: [ICON_QUESTION_ANSWER, "sourceAskUser"],
};

/** MessageSourceLabel: a 14 dp icon and labelSmall sit 4 dp above an automatic user bubble. */
function SourceLabel({ source }) {
  const labelled = SOURCE_LABELS[source?.kind];
  if (!labelled) return null;
  return html`
    <div class="message-source">${icon(labelled[0])}<span>${t[labelled[1]]}</span></div>`;
}

/**
 * AskUserAnswerBlocks: each question at 14/20 and 60 %, its answer at the user body, and one blank
 * line between pairs. Nobody answering leaves the localized unanswered label at 60 %.
 */
function fillAskUserPairs(node, items) {
  node.replaceChildren();
  items.forEach((item, index) => {
    if (index > 0) node.append(document.createTextNode("\n\n"));
    const question = document.createElement("span");
    question.className = "ask-user-question";
    question.textContent = item.question;
    const answer = document.createElement("span");
    answer.className = item.answer == null ? "ask-user-answer unanswered" : "ask-user-answer";
    answer.textContent = item.answer ?? t.sourceUnanswered;
    node.append(question, document.createTextNode("\n"), answer);
  });
}

/** One IconButton of a message row: 32 dp, tinted at 60 % of onSurfaceVariant and 30 % when off. */
function RowAction({ path, size, enabled = true, label, onClick }) {
  return html`
    <button class="row-action" type="button" disabled=${!enabled} title=${label} aria-label=${label}
      style=${{ "--row-icon": `${size}px` }} onClick=${onClick}>${icon(path)}</button>`;
}

/** One AgoraDropdownMenuItem; [destructive] paints it with the phone's error colour. */
function RowMenuItem({ path, label, enabled = true, destructive = false, onClick }) {
  return html`
    <button class=${destructive ? "dropdown-item destructive" : "dropdown-item"} role="menuitem"
      type="button" disabled=${!enabled} onClick=${onClick}>${icon(path)}<span>${label}</span></button>`;
}

/**
 * The 32 dp overflow button of a row and the AgoraDropdownMenu it anchors. [items] draws the items
 * with the close action; the menu hands focus back to the button when it was holding it.
 */
function RowMore({ reduceMotion, items }) {
  const anchor = useRef(null);
  const [open, setOpen] = useState(false);
  const [retained, setRetained] = useState(false);
  const close = () => setOpen(false);
  return html`
    <button class="row-action" type="button" ref=${anchor} title=${t.options} aria-label=${t.options}
      aria-haspopup="menu" aria-expanded=${open ? "true" : "false"}
      onClick=${() => { setRetained(true); setOpen(!open); }}>${icon(ICON_MORE_VERT)}</button>
    ${(open || retained) && html`<${MoreMenu} expanded=${open} reduceMotion=${reduceMotion} anchor=${anchor}
      onClose=${close}
      onExited=${(ownedFocus) => { setRetained(false); if (ownedFocus) anchor.current?.focus(); }}>
      ${items(close)}
    </${MoreMenu}>`}`;
}

/** MessageInfoDialog: the phone's own figures, and an em dash where its provider reported none. */
function MessageInfoDialog({ message, onClose }) {
  const dialog = useRef(null);
  useLayoutEffect(() => {
    const node = dialog.current;
    node.showModal();
    return () => node.close();
  }, []);
  const usage = message.usage;
  const lines = [t.timeWithLabel(formatRowTime(message.timestamp))];
  if (message.participant !== "USER") {
    lines.push(t.modelWithLabel(message.modelName || t.unknown));
    lines.push(t.inputWithLabel(usage?.input == null ? t.noFigure
      : usage.cachedInput == null ? t.tokenCount(usage.input)
        : t.tokenCountWithCached(usage.input, usage.cachedInput)));
    lines.push(t.outputWithLabel(usage?.output == null ? t.noFigure : t.tokenCount(usage.output)));
    if (usage?.tokensPerSecond != null) {
      lines.push(t.speedWithLabel(`~${usage.tokensPerSecond.toFixed(1)} token/s`));
    }
  }
  return html`
    <dialog ref=${dialog} class="attachment-editor page-dialog" aria-label=${t.messageInfo}
      onCancel=${(event) => { event.preventDefault(); onClose(); }}
      onClick=${(event) => { if (event.target === dialog.current) onClose(); }}
      onKeyDown=${(event) => event.stopPropagation()}>
      <section><h2>${t.messageInfo}</h2>
        <ul class="message-info-list">
          ${lines.map((line, index) => html`<li key=${index}>${line}</li>`)}
        </ul>
        <footer><button type="button" onClick=${onClose}>${t.close}</button></footer>
      </section></dialog>`;
}

/**
 * The phone's inline editor for a user row: it resends the edited text through the phone's own edit
 * owner, so only Save commits. Escape and the scrim dismiss without sending anything.
 */
function MessageEditDialog({ message, onClose }) {
  const dialog = useRef(null);
  const field = useRef(null);
  const [value, setValue] = useState(readableText(message));
  useLayoutEffect(() => {
    const node = dialog.current;
    node.showModal();
    field.current?.focus();
    return () => node.close();
  }, []);
  function save() {
    if (!value.trim()) return;
    if (sync.rowCommand("edit", message.id, { text: value })) onClose();
  }
  return html`
    <dialog ref=${dialog} class="attachment-editor page-dialog" aria-label=${t.edit}
      onCancel=${(event) => { event.preventDefault(); onClose(); }}
      onClick=${(event) => { if (event.target === dialog.current) onClose(); }}>
      <section><h2>${t.edit}</h2>
        <textarea ref=${field} class="message-edit" rows="6" value=${value} aria-label=${t.edit}
          onInput=${(event) => setValue(event.currentTarget.value)}
          onKeyDown=${(event) => {
            if (event.key === "Enter" && (event.metaKey || event.ctrlKey)) {
              event.preventDefault();
              save();
              return;
            }
            event.stopPropagation();
          }} />
        <footer><button type="button" onClick=${onClose}>${t.cancel}</button>
          <button type="button" disabled=${!value.trim()} onClick=${save}>${t.save}</button></footer>
      </section></dialog>`;
}

/**
 * The phone's own question for a destructive row action. The conversation variant belongs to the
 * phone's conversation owner, so the browser asks it whenever its own branch says the cascade would
 * take every visible row with it, which never under-warns.
 */
function MessageDeleteDialog({ deletesConversation, onClose, onConfirm }) {
  const dialog = useRef(null);
  useLayoutEffect(() => {
    const node = dialog.current;
    node.showModal();
    return () => node.close();
  }, []);
  return html`
    <dialog ref=${dialog} class="attachment-editor page-dialog"
      aria-label=${deletesConversation ? t.deleteConversationTitle : t.deleteMessageTitle}
      onCancel=${(event) => { event.preventDefault(); onClose(); }}
      onClick=${(event) => { if (event.target === dialog.current) onClose(); }}
      onKeyDown=${(event) => event.stopPropagation()}>
      <section><h2>${deletesConversation ? t.deleteConversationTitle : t.deleteMessageTitle}</h2>
        <p>${deletesConversation ? t.deleteConversationFromMessageConfirm : t.deleteMessageConfirm}</p>
        <footer><button type="button" onClick=${onClose}>${t.cancel}</button>
          <button class="destructive" type="button" onClick=${onConfirm}>${t.delete}</button></footer>
      </section></dialog>`;
}

/** UserMessageBubble: text in a primaryContainer bubble, long press (or right click) for its menu. */
function UserBubble({ message, search, matches, reduceMotion, generating, deletesConversation, conversationId }) {
  const text = useRef(null);
  const menuButton = useRef(null);
  const [menuOpen, setMenuOpen] = useState(false);
  const [retainedMenu, setRetainedMenu] = useState(false);
  const [info, setInfo] = useState(false);
  const [editing, setEditing] = useState(false);
  const [confirming, setConfirming] = useState(false);
  const [preview, setPreview] = useState(null);
  const [failedImages, setFailedImages] = useState(new Set());
  const attachments = message.attachments ?? [];
  const media = attachments.filter(item => !item.unavailable && item.type !== "file").map(item => ({
    ...item, name: item.fileName, kind: "source",
    url: `/api/message-attachments/${encodeURIComponent(conversationId)}/${encodeURIComponent(message.id)}/${item.index}`,
  }));
  const askUser = message.source?.kind === "ask_user" ? message.source.askUser : null;
  const visible = readableText(message);
  useLayoutEffect(() => {
    if (askUser) fillAskUserPairs(text.current, askUser);
    else text.current.textContent = visible;
    highlightSearch(text.current, search, matches);
  }, [visible, askUser, search, matches]);
  return html`
    <div class="user-row" onContextMenu=${(event) => {
      event.preventDefault();
      setRetainedMenu(true);
      setMenuOpen(true);
    }}>
      <${SourceLabel} source=${message.source} />
      <div class="user-message-content">
        <div class="user-hover-actions">
          <${RowAction} path=${ICON_EDIT} size=${18} label=${t.edit} enabled=${!generating}
            onClick=${() => setEditing(true)} />
          <button class="row-action" type="button" ref=${menuButton} title=${t.options} aria-label=${t.options}
            aria-haspopup="menu" aria-expanded=${menuOpen} onClick=${() => { setRetainedMenu(true); setMenuOpen(!menuOpen); }}>
            ${icon(ICON_MORE_VERT)}</button>
        </div>
        <div class="user-bubble">
          ${attachments.length > 0 && html`<div class="user-attachments">
            ${attachments.map(item => {
              const index = media.findIndex(current => current.index === item.index);
              const failed = item.unavailable || failedImages.has(item.index);
              return html`<button key=${item.index} class="user-attachment" type="button" title=${item.fileName}
                aria-label=${item.fileName || t.attachments} disabled=${failed || index < 0}
                onClick=${() => setPreview(index)}>
                ${!failed && index >= 0 ? html`<img src=${media[index].url} alt=${item.fileName || t.attachments}
                  onError=${() => setFailedImages(current => new Set([...current, item.index]))} />`
                  : html`<span class="attachment-missing">${icon(ICON_IMAGE)}</span>`}
                <span class="user-attachment-name">${item.fileName}</span>
              </button>`;
            })}
          </div>`}
          <div class="user-text" ref=${text}></div>
        </div>
      </div>
      ${(menuOpen || retainedMenu) && html`<${MoreMenu} expanded=${menuOpen} reduceMotion=${reduceMotion}
        anchor=${menuButton} onClose=${() => setMenuOpen(false)}
        onExited=${(ownedFocus) => {
          setRetainedMenu(false);
          if (ownedFocus) text.current.closest(".user-row").querySelector(".user-bubble")?.focus?.();
        }}>
        <${RowMenuItem} path=${ICON_CONTENT_COPY} label=${t.copy} enabled=${visible.trim().length > 0}
          onClick=${() => { setMenuOpen(false); copyRowText(message); }} />
        <${RowMenuItem} path=${ICON_EDIT} label=${t.edit} enabled=${!generating}
          onClick=${() => { setMenuOpen(false); setEditing(true); }} />
        <${RowMenuItem} path=${ICON_SELECT_ALL} label=${t.selectText} enabled=${visible.trim().length > 0}
          onClick=${() => { setMenuOpen(false); selectRowText(text.current); }} />
        <${RowMenuItem} path=${ICON_INFO} label=${t.info}
          onClick=${() => { setMenuOpen(false); setInfo(true); }} />
        <${RowMenuItem} path=${ICON_DELETE} label=${t.delete} destructive enabled=${!generating}
          onClick=${() => { setMenuOpen(false); setConfirming(true); }} />
      </${MoreMenu}>`}
      ${info && html`<${MessageInfoDialog} message=${message} onClose=${() => setInfo(false)} />`}
      ${preview != null && html`<${AttachmentViewer} viewer=${{ items: media, index: preview }}
        onNavigate=${setPreview} onClose=${() => setPreview(null)} />`}
      ${editing && html`<${MessageEditDialog} message=${message} onClose=${() => setEditing(false)} />`}
      ${confirming && html`<${MessageDeleteDialog} deletesConversation=${deletesConversation}
        onClose=${() => setConfirming(false)}
        onConfirm=${() => { setConfirming(false); sync.rowCommand("delete", message.id); }} />`}
    </div>`;
}


/**
 * The context-compact row. MessageItem draws a pill instead of the summary text: a 32 dp slot with
 * the state mark (18 dp), then labelLarge, on secondaryContainer, 42 dp tall and fully rounded.
 */
function CompactPill({ message, reduceMotion, generating, onInfo, onDelete }) {
  const running = !["SUCCESS", "ERROR", "STOPPED"].includes(message.status);
  const presentation = running ? "running" : message.status.toLowerCase();
  const label = running ? t.compactRunning : message.status === "SUCCESS" ? t.compactDone
    : message.status === "ERROR" ? t.compactError : t.compactStopped;
  return html`
    <div class="compact-row">
      <div class=${`compact-pill ${presentation}`} role="status">
        <span class="compact-mark">
          ${running && html`<${CircularProgress} size=${18} stroke=${2} />`}
          ${message.status === "ERROR" && icon(ICON_ERROR)}
          ${message.status === "STOPPED" && icon(ICON_STOP_CIRCLE, "0 0 24 24", "evenodd")}
          ${message.status === "SUCCESS" && icon(ICON_COMPRESS)}
        </span>
        <span class="compact-label">${label}</span>
      <${RowMore} reduceMotion=${reduceMotion} items=${(close) => html`
        <${RowMenuItem} path=${ICON_INFO} label=${t.info} onClick=${() => { close(); onInfo(); }} />
        <${RowMenuItem} path=${ICON_DELETE} label=${t.delete} destructive enabled=${!generating}
          onClick=${() => { close(); onDelete(); }} />`} />
      </div>
    </div>`;
}

/**
 * AssistantMessageContent's action line: a 44 dp Box under the answer with 12 dp of top padding.
 * Copy and MoreVert are the information group, Regenerate, Fork and Share the terminal group, and
 * both ride one alpha: 320 ms in, 220 ms out, linear.
 */
function AssistantActions({ message, generating, reduceMotion, onFork, onShare, onInfo, onDelete }) {
  const terminalEnabled = !generating;
  const copyTextValue = readableText(message);
  return html`
    <div class="message-actions">
      ${copyTextValue.trim().length > 0 && html`<${RowAction} path=${ICON_CONTENT_COPY} size=${16}
        label=${t.copy} onClick=${() => copyRowText(message)} />`}
      <${RowAction} path=${ICON_REFRESH} size=${19} enabled=${terminalEnabled} label=${t.regenerate}
        onClick=${() => sync.rowCommand("regenerate", message.id)} />
      <${RowAction} path=${ICON_CALL_SPLIT} size=${18} enabled=${terminalEnabled} label=${t.forkFromHere}
        onClick=${onFork} />
      <${RowAction} path=${ICON_SHARE} size=${16} enabled=${terminalEnabled} label=${t.share}
        onClick=${onShare} />
      <${RowMore} reduceMotion=${reduceMotion} items=${(close) => html`
        <${RowMenuItem} path=${ICON_INFO} label=${t.info} onClick=${() => { close(); onInfo(); }} />
        <${RowMenuItem} path=${ICON_DELETE} label=${t.delete} destructive enabled=${terminalEnabled}
          onClick=${() => { close(); onDelete(); }} />`} />
    </div>`;
}


function createGroupExpansionController() {
  const states = new Map();
  const collapsedImageBoundaryKeys = new Set();
  return {
    reset() {
      states.clear();
      collapsedImageBoundaryKeys.clear();
    },
    shouldCollapseForImageBoundary(key, hasImageBoundary) {
      return hasImageBoundary && !collapsedImageBoundaryKeys.has(key);
    },
    claimImageBoundaryCollapse(key, hasImageBoundary) {
      if (!hasImageBoundary || collapsedImageBoundaryKeys.has(key)) return false;
      collapsedImageBoundaryKeys.add(key);
      states.set(key, "INACTIVE");
      return true;
    },
    shouldPresentInitiallyExpanded(key, isActive, enabled) {
      return enabled && isActive && !states.has(key);
    },
    update(key, isActive, enabled) {
      if (collapsedImageBoundaryKeys.has(key)) return null;
      if (!enabled) {
        if (isActive) states.delete(key);
        else states.set(key, "INACTIVE");
        return null;
      }
      switch (states.get(key)) {
        case undefined:
        case "INACTIVE":
          states.set(key, isActive ? "ACTIVE" : "INACTIVE");
          return isActive ? "EXPAND" : null;
        case "ACTIVE":
          if (!isActive) {
            states.set(key, "INACTIVE");
            return "COLLAPSE";
          }
          return null;
        default:
          return null;
      }
    },
  };
}


/** Browser-local expansion memory survives payload eviction and off-screen row hydration. */
function InfoGroup({ group, messageId, display, expansion, expansionController, opensSheet, onOpenSheet, onOpenDetail, appearances, streaming }) {
  const key = `${messageId}:${group.key}`;
  const initiallyActive = !!(display?.autoExpandActiveGroup && group.autoExpansionActive);
  const imageBoundary = group.imageDetailIndex != null;
  const autoExpandEnabled = !!display?.autoExpandActiveGroup;
  const initiallyAutoExpanded = expansionController.shouldPresentInitiallyExpanded(
    key,
    initiallyActive,
    autoExpandEnabled,
  );
  const [expanded, setExpanded] = useState(() =>
    expansionController.shouldCollapseForImageBoundary(key, imageBoundary)
      ? false
      : expansion.get(key) ?? initiallyAutoExpanded,
  );
  const surface = useRef(null);
  const titleNode = useRef(null);
  const title = useCardTitle(group, display?.liveThinking);
  const [widths, setWidths] = useState(null);
  const firstAppearance = useRef(!appearances.has(key) && streaming);
  useEffect(() => { appearances.add(key); }, [key]);
  useEffect(() => {
    const parent = surface.current?.parentElement;
    if (!parent || !titleNode.current) return undefined;
    const measure = () => {
      const text = titleNode.current;
      const canvas = document.createElement("canvas");
      const context = canvas.getContext("2d");
      context.font = getComputedStyle(text).font;
      setWidths({
        collapsed: Math.min(parent.clientWidth, Math.ceil(context.measureText(text.textContent).width) + 80),
        expanded: parent.clientWidth,
      });
    };
    measure();
    const observer = new ResizeObserver(measure);
    observer.observe(parent);
    return () => observer.disconnect();
  }, [title]);
  useEffect(() => {
    if (expansionController.shouldCollapseForImageBoundary(key, imageBoundary)) {
      if (expansionController.claimImageBoundaryCollapse(key, imageBoundary)) {
        expansion.set(key, false);
        setExpanded(false);
      }
      return;
    }
    const action = expansionController.update(key, initiallyActive, autoExpandEnabled);
    if (action === null) return;
    const nextExpanded = action === "EXPAND";
    expansion.set(key, nextExpanded);
    setExpanded(nextExpanded);
  }, [key, initiallyActive, autoExpandEnabled, imageBoundary, expansionController]);
  function toggle() {
    expansion.set(key, !expanded);
    setExpanded(!expanded);
  }
  const targetExpanded = expanded && !opensSheet;
  return html`
    <div class=${`info-group ${targetExpanded ? "expanded" : ""} ${opensSheet ? "sheet-mode" : ""} ${group.precededByAnswer ? "after-answer" : ""} ${group.items.some((item) => item.type === "tool") ? "has-tool" : ""} ${firstAppearance.current ? "entering" : ""}`}
      data-group=${group.key}
      style=${widths == null ? null : { "--card-content-width": `${targetExpanded ? widths.expanded : widths.collapsed}px`, "--card-expanded-width": `${widths.expanded}px` }}>
      <div class="info-group-surface" ref=${surface}
        style=${widths == null ? null : { width: `${targetExpanded ? widths.expanded : widths.collapsed}px` }}>
        <button class="info-group-header" type="button" aria-expanded=${opensSheet ? null : expanded}
          aria-haspopup=${opensSheet ? "dialog" : null}
          onClick=${opensSheet ? () => onOpenSheet?.(messageId, group.key) : toggle}>
          <span class="info-header-icon"><${CardIcon} kind=${group.icon} /></span>
          <span class="info-header-title" ref=${titleNode}>${title}</span>
          <span class="info-disclosure">${icon(ICON_CHEVRON_DOWN)}</span>
        </button>
        <div class="info-group-reveal" inert=${!targetExpanded}>
          <div class="info-group-items">
            ${group.items.map((item) => html`<${InfoItem} key=${item.detailIndex} item=${item} compact
              onClick=${canOpenSheetItem(item)
                ? () => onOpenDetail(messageId, group.key, item.detailIndex) : undefined} />`)}
          </div>
        </div>
      </div>
    </div>`;
}

function InfoCard({ block, messageId, appearances, streaming, onOpenDetail }) {
  const key = `${messageId}:card:${block.item.detailIndex}`;
  const entering = useRef(!appearances.has(key) && streaming);
  useEffect(() => { appearances.add(key); }, [key]);
  return html`
    <div class=${`info-card ${block.groupPosition.toLowerCase()} ${block.precededByAnswer ? "after-answer" : ""} ${block.item.type === "tool" ? "has-tool" : ""} ${entering.current ? "entering" : ""}`}
      data-detail=${block.item.detailIndex}>
      <div class="info-card-surface"><${InfoItem} item=${block.item}
        onClick=${canOpenSheetItem(block.item)
          ? () => onOpenDetail(messageId, null, block.item.detailIndex) : undefined} /></div>
    </div>`;
}


/**
 * AssistantInlineActivity: the one 24 px line a generating turn owns. GenerationActivityDot is an
 * 11 dp circle in onBackground that breathes 0.55-1.30 every second (FastOutSlowIn, reversed),
 * RetryActivityIndicator reveals its label one grapheme at a time at 27 ms per grapheme clamped to
 * 225-600 ms while the dot rides the reveal caret 8 dp behind it, and the terminal line is
 * GenerationTerminalText: ChatType.body at 55% of onSurfaceVariant.
 */
const RETRY_DOT_GAP = 8;
const RETRY_MS_PER_GRAPHEME = 27;
const RETRY_REVEAL_MIN_MS = 225;
const RETRY_REVEAL_MAX_MS = 600;

function RetryActivity({ label, reduceMotion }) {
  const textRef = useRef(null);
  const dotRef = useRef(null);
  const graphemes = typeof Intl !== "undefined" && Intl.Segmenter
    ? Array.from(new Intl.Segmenter(undefined, { granularity: "grapheme" }).segment(label), (part) => part.segment)
    : Array.from(label);
  useEffect(() => {
    const text = textRef.current;
    const dot = dotRef.current;
    if (!text || !dot) return undefined;
    const spans = Array.from(text.querySelectorAll("[data-grapheme]"));
    const count = spans.length;
    if (count === 0) return undefined;
    // Compose measures one caret position per grapheme boundary and interpolates across them.
    const origin = text.getBoundingClientRect();
    const edges = [0].concat(spans.map((span) => span.getBoundingClientRect().right - origin.left));
    const paint = (progress) => {
      for (let index = 0; index < count; index += 1) {
        const alpha = Math.min(1, Math.max(0, progress - index));
        spans[index].style.opacity = alpha >= 1 ? "" : String(alpha);
      }
      const low = Math.min(count - 1, Math.floor(progress));
      const high = Math.min(count, low + 1);
      dot.style.left = `${edges[low] + (edges[high] - edges[low]) * (progress - low) + RETRY_DOT_GAP}px`;
    };
    if (reduceMotion) {
      paint(count);
      return undefined;
    }
    const duration = Math.min(RETRY_REVEAL_MAX_MS,
      Math.max(RETRY_REVEAL_MIN_MS, count * RETRY_MS_PER_GRAPHEME));
    const started = performance.now();
    let frame = 0;
    const tick = () => {
      const ratio = Math.min(1, (performance.now() - started) / duration);
      paint(count * linearOutSlowIn(ratio));
      if (ratio < 1) frame = requestAnimationFrame(tick);
      else paint(count);
    };
    frame = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(frame);
  }, [label, reduceMotion]);
  return html`
    <div class="generation-activity">
      <span class="generation-retry">
        <span class="generation-retry-text" ref=${textRef}>${graphemes.map((grapheme, index) => html`<span key=${index} data-grapheme=${index} style=${reduceMotion ? null : { opacity: 0 }}>${grapheme}</span>`)}</span>
        <span class="generation-dot generation-retry-dot" ref=${dotRef}></span>
      </span>
    </div>`;
}

/** The phone hands this line from activity to terminal text; Stop hides it and keeps the slot. */
function GenerationActivity({ activity, terminal, error, stopping, reduceMotion }) {
  const terminalText = error != null ? error : terminal;
  const active = stopping ? null : activity;
  if (!active && terminalText == null) return null;
  if (active && active.kind === "retry") {
    return html`<${RetryActivity} label=${`${active.retryText || ""}...`} reduceMotion=${reduceMotion} />`;
  }
  return html`<div class=${`generation-activity ${terminalText != null ? "generation-terminal" : ""}`}>${terminalText != null
    ? terminalText
    : html`<span class="generation-dot" role="status" aria-label="Generating"></span>`}</div>`;
}

/** AssistantMessageContent reads the same presentation decisions as the phone. */
function ModelMessage({ message, wrap, display, expansion, expansionController, appearances, streaming, stopping,
  generating, reduceMotion, deletesConversation, onOpenSheet, onOpenDetail, search, matches, onFork, onShare, births }) {
  const presentation = message.presentation;
  const error = message.participant === "ERROR";
  const [info, setInfo] = useState(false);
  const [confirming, setConfirming] = useState(false);
  const dialogs = html`
    ${info && html`<${MessageInfoDialog} message=${message} onClose=${() => setInfo(false)} />`}
    ${confirming && html`<${MessageDeleteDialog} deletesConversation=${deletesConversation}
      onClose=${() => setConfirming(false)}
      onConfirm=${() => { setConfirming(false); sync.rowCommand("delete", message.id); }} />`}`;
  // The compact summary never reads as prose: MessageItem owns a pill for it instead.
  if (message.compact) {
    return html`<div class="model-message">
      <${CompactPill} message=${message} reduceMotion=${reduceMotion} generating=${generating}
        onInfo=${() => setInfo(true)} onDelete=${() => setConfirming(true)} />${dialogs}</div>`;
  }
  let body = null;
  if (presentation?.useTimeline) {
    body = presentation.blocks.map((block) => {
      switch (block.type) {
        case "answer":
          return html`<div class="answer-block" key=${`a${block.index}`}>
            <${Markdown} text=${block.text} wrap=${wrap} search=${search} sourceStart=${block.sourceStart}
              streaming=${streaming && block.streaming} reduceMotion=${reduceMotion} births=${births}
              matches=${matches.filter(match => match.start >= block.sourceStart && match.endExclusive <= block.sourceStart + block.sourceLength)} />
          </div>`;
        case "group":
          return html`<${InfoGroup} key=${block.group.key} group=${block.group}
            messageId=${message.id} display=${display} expansion=${expansion}
            expansionController=${expansionController} onOpenSheet=${onOpenSheet}
            onOpenDetail=${onOpenDetail}
            appearances=${appearances} streaming=${streaming} opensSheet=${presentation.useThinkingSheet} />`;
        case "card":
          return html`<${InfoCard} key=${`card:${block.item.detailIndex}`} block=${block}
            messageId=${message.id} appearances=${appearances} streaming=${streaming}
            onOpenDetail=${onOpenDetail} />`;
        default:
          return null;
      }
    });
  } else {
    body = html`
      ${presentation?.compact && html`<${InfoGroup} group=${presentation.compact} messageId=${message.id}
        display=${display} expansion=${expansion} expansionController=${expansionController}
        onOpenSheet=${onOpenSheet} onOpenDetail=${onOpenDetail}
        appearances=${appearances} streaming=${streaming} opensSheet=${presentation.useThinkingSheet} />`}
      ${presentation?.answer && html`<${Markdown} text=${presentation.answer} wrap=${wrap} search=${search} matches=${matches}
        streaming=${streaming} reduceMotion=${reduceMotion} births=${births} />`}`;
  }
  // assistantActionsVisible: the action line leaves while the answer still streams and returns
  // 320 ms after it settles, never as a disabled ghost.
  const actionsVisible = !error && !streaming && !stopping;
  return html`<div class=${error ? "model-message error" : "model-message"}>${body}<${GenerationActivity}
    activity=${presentation?.inlineActivity} terminal=${presentation?.inlineTerminalText}
    error=${presentation?.errorBarText} stopping=${stopping}
    reduceMotion=${!!display?.reduceMotion} />
    ${presentation?.answerTailVisible && !stopping &&
      html`<div class="message-actions answer-tail"><span class="generation-dot" role="status" aria-label="Generating"></span></div>`}
    ${actionsVisible && html`<${AssistantActions} message=${message} generating=${generating}
      reduceMotion=${reduceMotion} onInfo=${() => setInfo(true)}
      onDelete=${() => setConfirming(true)} onFork=${onFork} onShare=${onShare} />`}
    ${dialogs}</div>`;
}

/**
 * One path row. `first` and `rows` carry the phone's deletion scope: MessageItem computes
 * deletionRemovesEntireConversation over the visible list, so the first row of a branch deletes the
 * conversation and a compact summary only does so when it is the whole conversation.
 */
function Row({ entry, body, wrap, display, expansion, expansionController, appearances, streaming, stopping,
  generating, reduceMotion, first, rows, onOpenSheet, onOpenDetail, search, onPageAction, glyphBirths, conversationId }) {
  const message = body ?? null;
  let births = glyphBirths.get(entry.id);
  if (!births) { births = new Map(); glyphBirths.set(entry.id, births); }
  const matches = search?.matches.filter(match => match.messageId === entry.id) || [];
  const deletesConversation = !!message && (message.compact ? rows === 1 : first);
  let content = html`<div class="row-placeholder"></div>`;
  if (message) {
    content = message.participant === "USER" && !message.compact
      ? html`<${UserBubble} message=${message} search=${search} matches=${matches}
          conversationId=${conversationId}
          reduceMotion=${reduceMotion} generating=${generating}
          deletesConversation=${deletesConversation} />`
      : html`<${ModelMessage} message=${message} wrap=${wrap} display=${display}
          births=${births}
          expansion=${expansion} expansionController=${expansionController}
          appearances=${appearances} streaming=${streaming} stopping=${stopping}
          generating=${generating} reduceMotion=${reduceMotion}
          deletesConversation=${deletesConversation}
          search=${search} matches=${matches}
          onFork=${() => onPageAction?.("fork", entry.id)}
          onShare=${() => onPageAction?.("share", entry.id)}
          onOpenSheet=${onOpenSheet} onOpenDetail=${onOpenDetail} />`;
  }
  return html`<div class="message-row" data-id=${entry.id}>${content}</div>`;
}

/**
 * ChatWelcomeContent: the launcher mark above the product name, typed one glyph at a time.
 *
 * TypewriterText.kt TypewriterMode.TEXT_GRADIENT reveals code point i at (i + 1) * 100 ms at 16 %
 * alpha and reaches full alpha 420 ms later, while the complete string owns the layout throughout.
 * Every glyph is a real span here, so one CSS animation per glyph carries the same curve and the
 * line never re-wraps mid-typing.
 */
const WELCOME_GLYPH_STEP_MS = 100;
// newChatMotionPolicy: newChatEntryId == 1L, so the phone types the welcome once per session and
// shows it static on every later visit.
let welcomeTyped = false;

function ChatWelcome({ reduceMotion }) {
  const animate = !reduceMotion && !welcomeTyped;
  welcomeTyped = true;
  // role="img" keeps the glyph-split spans out of the accessibility tree as one name.
  return html`
    <div class=${`chat-welcome ${animate ? "welcome-animates" : ""}`} role="img" aria-label=${t.welcome}>
      <span class="welcome-mark">${icon(ICON_AGORA, "0 0 240 240")}</span>
      <span class="welcome-text">${[...t.welcome].map((glyph, index) => html`<span
        class="welcome-glyph" style=${animate
          ? { animationDelay: `${(index + 1) * WELCOME_GLYPH_STEP_MS}ms` }
          : null}>${glyph}</span>`)}</span>
    </div>`;
}

export function MessageList({ state, label, onPageAction }) {
  const scroller = useRef(null);
  const bar = useRef(null);
  const visible = useRef(new Set());
  const pinned = useRef(false);
  const bottomMotion = useRef(null);
  const generationActive = useRef(false);
  const search = state.conversationSearch;
  const searchPosition = useRef(null);
  const searchTarget = useRef(null);
  searchTarget.current = search?.matches[search.index]?.messageId ?? null;
  generationActive.current = !!state.generating;
  const cover = useRef(null);
  const [settledOpenId, setSettledOpenId] = useState(null);
  const [retainedCover, setRetainedCover] = useState(false);
  const switching = !!state.openId && (state.openStatus === "loading" ||
    (state.openStatus === "ready" && settledOpenId !== state.openId));
  const covered = switching || retainedCover;
  const expansion = useRef(new Map());
  const expansionController = useRef(createGroupExpansionController());
  const appearances = useRef(new Set());
  const glyphBirths = useRef(new Map());
  const previousOpenId = useRef(state.openId);
  const [sheet, setSheet] = useState(null);
  const sheetWatchedId = sheet?.conversationId === state.openId ? sheet.messageId : null;
  const watchedSheet = useRef(sheetWatchedId);
  watchedSheet.current = sheetWatchedId;
  const ids = state.path.map((entry) => entry.id).join(",");
  const wrap = state.display?.autoWrapCodeBlocks ?? true;
  // An empty new chat replaces the whole body with ChatWelcomeContent, as on the phone.
  const showWelcome = !state.openId && state.path.length === 0;
  const selectedOnPath = sheet && state.openId === sheet.conversationId &&
    state.path.some((entry) => entry.id === sheet.messageId);
  const sheetMessage = selectedOnPath
    ? state.streaming?.id === sheet.messageId ? state.streaming : state.bodies.get(sheet.messageId)
    : null;
  const sheetGroup = sheetMessage && sheet?.groupKey != null
    ? groupForMessage(sheetMessage, sheet.groupKey) : null;
  const sheetItems = sheetMessage ? sheetItemsForMessage(sheetMessage, sheet.groupKey) : [];
  const selectedItem = sheetItems.find((item) => item.detailIndex === sheet?.detailIndex);
  const validSheet = selectedOnPath && (!sheetMessage ||
    ((sheet.groupKey == null || sheetGroup) &&
      (sheet.page !== "detail" || canOpenSheetItem(selectedItem))));

  function openSheet(messageId, groupKey) {
    setSheet({ conversationId: state.openId, messageId, groupKey, page: "list", detailIndex: null });
  }
  function openDetail(messageId, groupKey, detailIndex) {
    setSheet({ conversationId: state.openId, messageId, groupKey, page: "detail", detailIndex });
  }
  useEffect(() => {
    if (!sheet) return;
    if (sheet.conversationId !== state.openId || state.openStatus === "deleted" ||
        state.openStatus === "failed" ||
        (state.openStatus === "ready" && !state.path.some((entry) => entry.id === sheet.messageId)) ||
        (sheetMessage && !validSheet)) setSheet(null);
  }, [sheet, state.openId, state.openStatus, ids, sheetMessage, validSheet]);

  useEffect(() => {
    if (previousOpenId.current === state.openId) return;
    previousOpenId.current = state.openId;
    expansion.current.clear();
    expansionController.current.reset();
    appearances.current.clear();
    glyphBirths.current.clear();
  }, [state.openId]);

  // Chrome runs no CSS transition on its own scrollbar, so the animated bar is drawn beside it; the
  // native bar stays in place and keeps every hit target.
  useEffect(() => attachScrollbar(scroller.current, bar.current), []);

  useEffect(() => {
    const root = scroller.current;
    if (!root) return undefined;
    visible.current = new Set();
    const observer = new IntersectionObserver((entries) => {
      for (const entry of entries) {
        const id = entry.target.dataset.id;
        if (entry.isIntersecting) visible.current.add(id);
        else visible.current.delete(id);
      }
      const watched = new Set(visible.current);
      if (watchedSheet.current) watched.add(watchedSheet.current);
      sync.watch([...new Set([searchTarget.current, ...watched].filter(Boolean))]);
    }, { root, rootMargin: WATCH_MARGIN });
    root.querySelectorAll(".message-row").forEach((row) => observer.observe(row));
    return () => observer.disconnect();
  }, [ids]);

  useEffect(() => {
    const watched = new Set(visible.current);
    if (sheetWatchedId) watched.add(sheetWatchedId);
    sync.watch([...new Set([searchTarget.current, ...watched].filter(Boolean))]);
  }, [sheetWatchedId, searchTarget.current]);

  // The app opens a conversation at its newest message. Row bodies arrive after the path, so
  // this same owner releases the cover after visible bodies and bottom layout have settled.
  useLayoutEffect(() => {
    pinned.current = true;
    setSettledOpenId(null);
  }, [state.openId]);
  useLayoutEffect(() => {
    const root = scroller.current;
    const column = root?.firstElementChild;
    if (!root || !column) return undefined;
    let frame = 0;
    let seekFrame = 0;
    let previousLayout = null;
    const pending = state.openStatus === "ready" && settledOpenId !== state.openId;
    const request = state.scrollRequest;
    let seekRequested = false;
    if (request && state.openStatus === "ready" && state.path.some((entry) => entry.id === request.messageId)) {
      pinned.current = true;
      sync.consumeScroll(request);
      seekRequested = true;
    }

    // One owner drives every programmatic bottom move the way the phone does: a send travels with
    // the feedback controller, growth during a generation uses the short tail correction, and the
    // actor settles on the end sentinel before releasing ownership.
    let motionFrame = 0;
    const bottomError = () => Math.max(0, root.scrollHeight - root.clientHeight - root.scrollTop);
    const scrollBy = (step) => {
      const before = root.scrollTop;
      root.scrollTop += step;
      return Math.abs(root.scrollTop - before);
    };
    // An unhydrated tail row is a 54 px estimate, which is the web stand-in for a target the phone
    // has not composed yet: the seek then travels with the wider unmeasured gains.
    const tailIsMeasured = () => {
      const last = state.path[state.path.length - 1];
      if (!last) return true;
      if (state.streaming?.id === last.id) return true;
      return state.bodies.has(last.id);
    };
    const pump = () => {
      motionFrame = 0;
      const motion = bottomMotion.current;
      if (!pinned.current) {
        bottomMotion.current = null;
        return;
      }
      // Opening stays a hard placement under the switching cover, exactly like scrollToItem.
      if (pending) {
        bottomMotion.current = null;
        root.scrollTop = root.scrollHeight;
        return;
      }
      if (covered) {
        bottomMotion.current = null;
        return;
      }
      const now = performance.now();
      const generating = generationActive.current;
      if (!motion) {
        // Idle growth is not a scroll gesture: the phone detaches too, leaving the tail in place.
        if (!generating || bottomError() <= BOTTOM_MOTION.attachThresholdPx) return;
        bottomMotion.current = { mode: "attach", previous: now };
      }
      const active = bottomMotion.current;
      const elapsed = Math.max(0.001, Math.min(0.05, (now - active.previous) / 1000));
      active.previous = now;
      const height = Math.max(1, root.clientHeight);
      const error = bottomError();
      if (active.mode === "seek") {
        if (now - active.started > BOTTOM_MOTION.maximumDurationMs) {
          bottomMotion.current = null;
          return;
        }
        const measured = tailIsMeasured();
        if (measured && error <= BOTTOM_MOTION.tolerancePx) {
          active.stable += 1;
          if (active.stable >= BOTTOM_MOTION.stableFrames) {
            active.mode = "settle";
            active.settlingStarted = now;
            active.settlingStable = 0;
          } else {
            wake();
            return;
          }
        } else {
          active.stable = 0;
          let step = coalescedScrollStep(
            measured ? error : Math.max(error, height * 0.75),
            elapsed,
            measured ? BOTTOM_MOTION.seekTauMeasuredSeconds : BOTTOM_MOTION.seekTauUnmeasuredSeconds,
            height * (measured
              ? BOTTOM_MOTION.seekMaxVelocityMeasuredViewports
              : BOTTOM_MOTION.seekMaxVelocityUnmeasuredViewports),
            BOTTOM_MOTION.minimumStepPx,
          );
          if (active.startupMs) step *= fastOutSlowIn((now - active.started) / active.startupMs);
          const cap = height * BOTTOM_MOTION.maximumFrameStepViewportFraction;
          step = Math.min(cap, Math.max(-cap, step));
          if (Math.abs(step) > 0.05) {
            active.blocked = scrollBy(step) <= 0.05 ? active.blocked + 1 : 0;
            if (active.blocked >= BOTTOM_MOTION.blockedFrames) {
              bottomMotion.current = null;
              return;
            }
          }
          wake();
          return;
        }
      }
      if (active.mode === "settle") {
        // Late growth or an active generation hands ownership back to the seek, as the phone does.
        if (generating || error > BOTTOM_MOTION.tolerancePx) {
          active.mode = "seek";
          active.started = now;
          active.stable = 0;
          active.blocked = 0;
          wake();
          return;
        }
        active.settlingStable += 1;
        const settlingElapsed = now - active.settlingStarted;
        const minimum = active.generationWasActive
          ? BOTTOM_MOTION.settlingGenerationMs
          : BOTTOM_MOTION.settlingIdleMs;
        if ((settlingElapsed >= minimum && active.settlingStable >= BOTTOM_MOTION.settlingStableFrames) ||
          settlingElapsed >= BOTTOM_MOTION.settlingTimeoutMs) {
          bottomMotion.current = null;
          return;
        }
        wake();
        return;
      }
      if (error > BOTTOM_MOTION.attachThresholdPx) {
        const step = coalescedScrollStep(error, elapsed, BOTTOM_MOTION.attachTauSeconds,
          BOTTOM_MOTION.attachMaxVelocityPxPerSecond, BOTTOM_MOTION.minimumStepPx);
        if (Math.abs(step) > 0.05) scrollBy(step);
      }
      if (bottomError() > BOTTOM_MOTION.attachThresholdPx) wake();
    };
    function wake() {
      if (!motionFrame) motionFrame = requestAnimationFrame(pump);
    }
    const startSeek = () => {
      // A reduced-motion or covered list takes the same instant placement the phone uses.
      if (pending || covered || state.display?.reduceMotion) {
        bottomMotion.current = null;
        root.scrollTop = root.scrollHeight;
        return;
      }
      const now = performance.now();
      bottomMotion.current = {
        mode: "seek",
        startupMs: BOTTOM_MOTION.sendStartupMs,
        started: now,
        previous: now,
        stable: 0,
        blocked: 0,
        generationWasActive: generationActive.current,
      };
      wake();
    };
    if (seekRequested) startSeek();

    const settle = () => {
      frame = 0;
      const viewport = root.getBoundingClientRect();
      const rows = [...column.querySelectorAll(".message-row")].filter((row) => {
        const bounds = row.getBoundingClientRect();
        return bounds.bottom > viewport.top && bounds.top < viewport.bottom;
      });
      if (rows.some((row) => state.streaming?.id !== row.dataset.id && !state.bodies.has(row.dataset.id))) {
        previousLayout = null;
        return;
      }
      const layout = `${root.scrollHeight}:${root.clientHeight}:${root.scrollTop}`;
      if (layout === previousLayout && Math.abs(root.scrollHeight - root.clientHeight - root.scrollTop) <= 1) {
        setSettledOpenId(state.openId);
      } else {
        previousLayout = layout;
        frame = requestAnimationFrame(settle);
      }
    };
    const followBottom = (fromFrame = false) => {
      if (search?.query.trim()) pinned.current = false;
      else searchPosition.current = null;
      if (search && !search.searching && search.matches.length && !covered) {
        const bar = root.closest(".chat").querySelector(".conversation-search-bar");
        if (!bar) return;
        const viewport = root.getBoundingClientRect();
        const top = Math.max(viewport.top, bar.getBoundingClientRect().bottom);
        const bottom = Math.min(viewport.bottom, root.closest(".chat").querySelector(".composer-host").getBoundingClientRect().top);
        const center = (top + bottom) / 2;
        const geometry = new Map();
        root.querySelectorAll("mark[data-search-key]").forEach(mark => {
          for (const rect of mark.getClientRects()) {
            const old = geometry.get(mark.dataset.searchKey);
            geometry.set(mark.dataset.searchKey, {
              top: Math.min(old?.top ?? rect.top, rect.top),
              bottom: Math.max(old?.bottom ?? rect.bottom, rect.bottom),
            });
          }
        });
        const midpoint = bounds => (bounds.top + bounds.bottom) / 2;
        if (search.index < 0) {
          let nearest = -1, distance = Infinity;
          search.matches.forEach((match, index) => {
            const bounds = geometry.get(match.key);
            if (bounds && bounds.bottom > top && bounds.top < bottom) {
              const candidate = Math.abs(midpoint(bounds) - center);
              if (candidate < distance) { nearest = index; distance = candidate; }
            }
          });
          if (nearest < 0) {
            const rows = [...column.querySelectorAll(".message-row")];
            const anchor = rows.reduce((best, row, index) =>
              Math.abs(midpoint(row.getBoundingClientRect()) - center) < best.distance
                ? { index, distance: Math.abs(midpoint(row.getBoundingClientRect()) - center) } : best,
            { index: 0, distance: Infinity }).index;
            const turnIndexes = new Map(state.path.map((entry, index) => [entry.id, index]));
            search.matches.forEach((match, index) => {
              const candidate = Math.abs((turnIndexes.get(match.messageId) ?? Infinity) - anchor);
              if (candidate < distance) { nearest = index; distance = candidate; }
            });
          }
          if (nearest >= 0) sync.selectSearchMatch(nearest, search.revision, true);
        } else {
          const match = search.matches[search.index];
          const bounds = geometry.get(match.key);
          const epoch = `${state.openId}:${search.revision}:${search.positionRevision}`;
          const now = performance.now();
          if (searchPosition.current?.epoch !== epoch) {
            searchPosition.current = { epoch, started: now, previous: now, stable: 0, blocked: 0, done: false };
          }
          const motion = searchPosition.current;
          if (!motion.done && now - motion.started < 30_000) {
            if (fromFrame) {
              const row = [...column.querySelectorAll(".message-row")].find(r => r.dataset.id === match.messageId);
              // Rows whose occurrence is hidden source (math, markers, code) keep the row-rect
              // estimate as their final target, matching Compose centering the turn itself.
              const target = bounds || row?.getBoundingClientRect() || null;
              if (target && now >= search.positionAfter) {
                const error = midpoint(target) - center;
                const dt = Math.max(0.001, Math.min(0.05, (now - motion.previous) / 1000));
                motion.previous = now;
                if (Math.abs(error) <= 1.5) {
                  // Unhydrated rows never finalize: marks may still appear when the body arrives.
                  if (++motion.stable >= 4 && (bounds || state.bodies.has(match.messageId))) motion.done = true;
                } else {
                  motion.stable = 0;
                  const reduced = !!state.display?.reduceMotion;
                  const height = Math.max(1, viewport.height);
                  const maximum = Math.max(1, height * (bounds ? 16 : 52) * dt);
                  const step = reduced ? error : Math.sign(error) * Math.min(
                    Math.abs(error) * (1 - Math.exp(-dt / (bounds ? 0.09 : 0.16))),
                    maximum, height * 0.82,
                  );
                  const before = root.scrollTop;
                  root.scrollTop += step;
                  motion.blocked = Math.abs(root.scrollTop - before) <= 0.05 ? motion.blocked + 1 : 0;
                  if (motion.blocked >= 12) motion.done = true;
                }
              }
            }
            // Seek steps run at most once per frame; ResizeObserver wakeups defer to rAF so
            // coalesced multi-callback frames never exceed the bounded per-frame velocity.
            if (!motion.done && !seekFrame) seekFrame = requestAnimationFrame(() => { seekFrame = 0; followBottom(true); });
          }
        }
      }
      if (pinned.current) wake();
      if (pending && !frame) frame = requestAnimationFrame(settle);
    };
    const release = () => {
      pinned.current = false;
      bottomMotion.current = null;
      cancelAnimationFrame(motionFrame);
      motionFrame = 0;
      if (searchPosition.current) searchPosition.current.done = true;
      cancelAnimationFrame(seekFrame);
      seekFrame = 0;
    };
    const follow = new ResizeObserver(followBottom);
    follow.observe(column);
    follow.observe(root);
    followBottom();
    const inputs = ["wheel", "touchstart", "keydown", "pointerdown"];
    inputs.forEach((type) => root.addEventListener(type, release, { passive: true }));
    return () => {
      follow.disconnect();
      cancelAnimationFrame(frame);
      cancelAnimationFrame(motionFrame);
      cancelAnimationFrame(seekFrame);
      inputs.forEach((type) => root.removeEventListener(type, release));
    };
  }, [state.openId, state.openStatus, ids, state.bodies, state.streaming, settledOpenId, state.scrollRequest, search, covered, state.display?.reduceMotion]);

  useLayoutEffect(() => {
    const node = cover.current;
    if (!node) return undefined;
    setRetainedCover(true);
    const animation = node.animate(
      [{ opacity: getComputedStyle(node).opacity }, { opacity: switching ? 1 : 0 }],
      { duration: 200, easing: "cubic-bezier(0.4, 0, 0.2, 1)", fill: "forwards" },
    );
    animation.onfinish = () => {
      node.style.opacity = switching ? "1" : "0";
      animation.cancel();
      if (!switching) setRetainedCover(false);
    };
    return () => {
      node.style.opacity = getComputedStyle(node).opacity;
      animation.cancel();
    };
  }, [switching]);

  return html`
    <section class="messages" ref=${scroller} aria-label=${label} aria-busy=${covered} inert=${covered}>
      ${showWelcome ? html`<${ChatWelcome} reduceMotion=${!!state.display?.reduceMotion} />`
        : html`<div class="message-column">
        ${state.path.map((entry, index) => html`
          <${Row} key=${entry.id} entry=${entry} wrap=${wrap} display=${state.display}
            search=${search} first=${index === 0} rows=${state.path.length}
            generating=${!!state.generating} reduceMotion=${!!state.display?.reduceMotion}
            onPageAction=${onPageAction}
            expansion=${expansion.current} expansionController=${expansionController.current}
            appearances=${appearances.current} glyphBirths=${glyphBirths.current} conversationId=${state.openId}
            onOpenSheet=${openSheet} onOpenDetail=${openDetail}
            streaming=${state.streaming?.id === entry.id}
            stopping=${!!state.composer?.stopping}
            body=${state.streaming?.id === entry.id ? state.streaming : state.bodies.get(entry.id)} />`)}
      </div>`}
    </section>
    <div class="scrollbar-overlay" ref=${bar} aria-hidden="true"><span></span></div>
    ${covered && html`<div class="conversation-loading-cover" ref=${cover}
      onPointerDown=${(event) => { event.preventDefault(); event.stopPropagation(); }}
      onWheel=${(event) => event.preventDefault()} onContextMenu=${(event) => event.preventDefault()}>
      <div class="conversation-loading-range">
        <${Spinner} size=${48} stroke=${5} label=${label} />
      </div>
    </div>`}
    ${sheet && validSheet && html`<${DetailSheet} key=${`${sheet.conversationId}:${sheet.messageId}:${sheet.groupKey ?? "direct"}`}
      group=${sheetGroup} items=${sheetItems} page=${sheet.page} detailIndex=${sheet.detailIndex}
      selectedItem=${selectedItem} display=${state.display} wrap=${wrap}
      conversationId=${sheet.conversationId} messageId=${sheet.messageId}
      onSelectItem=${(detailIndex) => setSheet({ ...sheet, page: "detail", detailIndex })}
      onBack=${sheet.page === "detail" && sheetGroup
        ? () => setSheet({ ...sheet, page: "list", detailIndex: null }) : null}
      onClose=${() => setSheet(null)} />`}`;
}
