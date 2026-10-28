// The open conversation's selected branch, drawn as the app's MessageList and MessageItem.
// The list is paged by message rounds; every row of the current page is watched, so the phone
// sends all of the page's bodies before the page is drawn.
import { useEffect, useLayoutEffect, useRef, useState } from "./vendor/preact-hooks.mjs";
import { html, useDialog } from "./html.js";
import { CircularProgress, Spinner } from "./material/progress.js";
import { Markdown, highlightSearch } from "./markdown.js";
import {
  icon, ICON_WEB, ICON_BUILD, ICON_CALL_SPLIT, ICON_CHEVRON_DOWN, ICON_CHEVRON_RIGHT,
  ICON_COMPRESS, ICON_COMPACT_ERROR, ICON_CONTENT_COPY, ICON_DELETE, ICON_EDIT, ICON_ERROR, ICON_IMAGE, ICON_INFO,
  ICON_MORE_VERT, ICON_NEUROLOGY, ICON_ACCOUNT_TREE, ICON_QUESTION_ANSWER, ICON_REFRESH, ICON_REPEAT,
  ICON_SCHEDULE, ICON_SELECT_ALL, ICON_SHARE, ICON_STOP_CIRCLE, ICON_LINK,
} from "./icons.js";
import { MoreMenu } from "./menu.js";
import { t } from "./i18n.js";
import { sync } from "./sync.js";
import { DetailSheet, CardIcon, InfoItem, useCardTitle, canOpenSheetItem } from "./detail-sheet.js";
import { attachScrollbar } from "./material/scrollbar.js";
import { AttachmentViewer } from "./attachments.js";
import { bindTailScrollInput, createStreamingTailFollow, coalescedScrollStep } from "./tail-follow.js";


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
};


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
  if (presentation.thinkingCard?.key === groupKey) return presentation.thinkingCard;
  return presentation.blocks?.find((block) => block.type === "group" && block.group.key === groupKey)?.group ?? null;
}

function sheetItemsForMessage(message, groupKey) {
  if (!message?.presentation) return [];
  if (groupKey != null) return groupForMessage(message, groupKey)?.items ?? [];
  return message.presentation.blocks
    ?.filter((block) => block.type === "card")
    .map((block) => block.item) ?? [];
}


/** Copy and Select Text retain the original body, including a compact Subagent receipt's result. */
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
    await navigator.clipboard.writeText(message.presentation?.copyText ?? readableText(message));
    sync.notify(t.copied);
  } catch (_) {
    sync.notify(t.copyFailed);
  }
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
  subagent: [ICON_ACCOUNT_TREE, "sourceSubagent"],
  ask_user: [ICON_QUESTION_ANSWER, "sourceAskUser"],
};

/** MessageSourceLabel: a 14 dp icon and labelSmall sit 4 dp above an automatic user bubble. */
function SourceLabel({ source }) {
  const labelled = SOURCE_LABELS[source?.kind];
  if (!labelled) return null;
  return html`
    <div class="message-source">${icon(labelled[0])}<span>${t[labelled[1]]}</span></div>`;
}

/** Completion display comes from the model; child instructions have no result display metadata. */
function SubagentResult({ result }) {
  const name = result.name.trim() || t.sourceSubagent;
  const status = t.subagentStatuses[result.status];
  return html`<div class="user-text subagent-result" role="group" aria-label=${t.sourceSubagent}>
    <div class="subagent-result-name">${name}</div>
    ${status && html`<div class="subagent-result-status">${status}</div>`}
  </div>`;
}

/**
 * AskUserAnswerBlocks: each question at 14/20 and 60 %, its answer at the user body, and one blank
 * line between pairs. Nobody answering leaves the localized unanswered label at 60 %.
 */
function fillAskUserPairs(node, items) {
  node.replaceChildren();
  // Separate blocks, as on the phone: an inline 14/20 question inside the 24.2 px body would sit on
  // the body's line strut and keep the body's line spacing. style.css adds the blank line.
  let sourceStart = 0;
  items.forEach((item) => {
    const question = document.createElement("div");
    question.className = "ask-user-question";
    question.dataset.sourceStart = String(sourceStart);
    question.textContent = item.question;
    sourceStart += item.question.length + 1;
    const answer = document.createElement("div");
    answer.className = item.answer == null ? "ask-user-answer unanswered" : "ask-user-answer";
    answer.dataset.sourceStart = String(sourceStart);
    answer.textContent = item.answer ?? t.sourceUnanswered;
    sourceStart += answer.textContent.length + 2;
    node.append(question, answer);
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
function RowMore({ reduceMotion, items, enabled = true }) {
  const anchor = useRef(null);
  const [open, setOpen] = useState(false);
  const [retained, setRetained] = useState(false);
  const close = () => setOpen(false);
  useEffect(() => { if (!enabled) setOpen(false); }, [enabled]);
  return html`
    <button class="row-action" type="button" ref=${anchor} title=${t.options} aria-label=${t.options} disabled=${!enabled}
      aria-haspopup="menu" aria-expanded=${open ? "true" : "false"}
      onClick=${() => { setRetained(true); setOpen(!open); }}>${icon(ICON_MORE_VERT)}</button>
    ${(open || retained) && html`<${MoreMenu} expanded=${open && enabled} reduceMotion=${reduceMotion} anchor=${anchor}
      onClose=${close}
      onExited=${(ownedFocus) => { setRetained(false); if (ownedFocus) anchor.current?.focus(); }}>
      ${items(close)}
    </${MoreMenu}>`}`;
}

/** MessageInfoDialog: the phone's own figures, and an em dash where its provider reported none. */
function MessageInfoDialog({ message, onClose: onClosed }) {
  const dialog = useRef(null);
  const onClose = useDialog(dialog, onClosed);
  const usage = message.usage;
  const lines = [t.timeWithLabel(formatRowTime(message.timestamp))];
  if (message.participant !== "USER") {
    lines.push(t.modelWithLabel(message.modelDisplay || message.modelName || t.unknown));
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
function MessageEditDialog({ message, canEdit, onClose: onClosed }) {
  const dialog = useRef(null);
  const onClose = useDialog(dialog, onClosed);
  const field = useRef(null);
  const [value, setValue] = useState(readableText(message));
  useLayoutEffect(() => { field.current?.focus(); }, []);
  function save() {
    if (!canEdit() || message.source || !value.trim()) return;
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
function MessageDeleteDialog({ state, request, onClose: onClosed }) {
  const title = request.compactOnly ? t.deleteCompactTitle
    : request.deletesConversation ? t.deleteConversationTitle : t.deleteMessageTitle;
  const body = request.compactOnly ? t.deleteCompactConfirm
    : request.deletesConversation ? t.deleteConversationFromMessageConfirm : t.deleteMessageConfirm;
  const dialog = useRef(null);
  const sent = useRef(null);
  const [failed, setFailed] = useState(false);
  const pending = !!sent.current && !failed;
  const onClose = useDialog(dialog, () => {
    if (sent.current) sync.confirmationExited(sent.current.actionId);
    onClosed();
  });
  useEffect(() => {
    const result = state.switching?.result;
    if (!sent.current || result?.actionId !== sent.current.actionId) return;
    if (result.success) onClose();
    else setFailed(true);
  }, [state.switching?.result]);
  function close() { if (!pending) onClose(); }
  function confirm() {
    if (pending) return;
    const command = sync.rowCommand("delete", request.messageId, { confirmation: true });
    if (command) { sent.current = command; setFailed(false); }
    else setFailed(true);
  }
  return html`
    <dialog ref=${dialog} class="attachment-editor page-dialog" aria-label=${title}
      onCancel=${event => { event.preventDefault(); close(); }}
      onClick=${event => { if (event.target === dialog.current) close(); }}
      onKeyDown=${event => event.stopPropagation()}>
      <section><h2>${title}</h2><p>${body}</p>
        ${failed && html`<p class="dialog-failure" role="alert">${t.toolStateFailed}</p>`}
        <footer><button type="button" disabled=${pending} onClick=${close}>${t.cancel}</button>
          <button class="destructive" type="button" disabled=${pending} onClick=${confirm}>
            ${pending ? html`<${CircularProgress} size=${20} stroke=${3} label=${t.loading} />` : failed ? t.retry : t.delete}
          </button></footer>
      </section></dialog>`;
}

/** UserMessageBubble: text in a primaryContainer bubble, long press (or right click) for its menu. */
function UserBubble({ message, search, matches, reduceMotion, generating, deletesConversation, conversationId, branchControl, onDelete }) {
  const text = useRef(null);
  const menuButton = useRef(null);
  const [menuOpen, setMenuOpen] = useState(false);
  const [retainedMenu, setRetainedMenu] = useState(false);
  const [info, setInfo] = useState(false);
  const [selecting, setSelecting] = useState(false);
  const [editing, setEditing] = useState(false);
  const editable = !generating && message.source == null;
  const editAllowed = useRef(editable);
  editAllowed.current = editable;
  const canEdit = () => editAllowed.current;
  const edit = () => { if (canEdit()) setEditing(true); };
  useLayoutEffect(() => { if (!editable) setEditing(false); }, [editable]);
  const [preview, setPreview] = useState(null);
  const [failedImages, setFailedImages] = useState(new Set());
  const attachments = message.attachments ?? [];
  const media = attachments.filter(item => !item.unavailable && item.type !== "file").map(item => ({
    ...item, name: item.fileName, kind: "source",
    url: `/api/message-attachments/${encodeURIComponent(conversationId)}/${encodeURIComponent(message.id)}/${item.index}`,
  }));
  const askUser = message.source?.kind === "ask_user" ? message.source.askUser : null;
  const result = message.source?.kind === "subagent" ? message.source.subagentResult : null;
  const visible = readableText(message);
  const hasText = !result && (message.text?.markdown ?? "").length > 0;
  const hasBody = hasText || !!askUser || !!result;
  const askUserKey = JSON.stringify(askUser);
  const searchKey = JSON.stringify([!!search?.query.trim(), matches.map(match =>
    [match.key, match.start, match.endExclusive])]);
  const activeMatch = search?.matches[search.index]?.key;
  useLayoutEffect(() => {
    if (!text.current) return;
    if (askUser) fillAskUserPairs(text.current, askUser);
    else text.current.textContent = visible;
  }, [visible, askUserKey, hasText]);
  useLayoutEffect(() => {
    return text.current ? highlightSearch(text.current, search, matches) : undefined;
  }, [visible, askUserKey, hasText, searchKey]);
  useLayoutEffect(() => {
    const active = CSS.highlights.get("agora-search-active");
    for (const range of CSS.highlights.get("agora-search") ?? []) {
      if (!text.current?.contains(range.startContainer)) continue;
      if (range.searchKey === activeMatch) active?.add(range);
      else active?.delete(range);
    }
  }, [visible, askUserKey, hasText, searchKey, activeMatch]);
  return html`
    <div class="user-row" onContextMenu=${(event) => {
      event.preventDefault();
      setRetainedMenu(true);
      setMenuOpen(true);
    }}>
      <${SourceLabel} source=${message.source} />
      <div class="user-message-content">
        <div class="user-hover-actions">
          <${RowAction} path=${ICON_EDIT} size=${18} label=${t.edit} enabled=${editable}
            onClick=${edit} />
          <button class="row-action" type="button" ref=${menuButton} title=${t.options} aria-label=${t.options}
            aria-haspopup="menu" aria-expanded=${menuOpen} onClick=${() => { setRetainedMenu(true); setMenuOpen(!menuOpen); }}>
            ${icon(ICON_MORE_VERT)}</button>
        </div>
        <div class=${`user-bubble${result ? " subagent-result-bubble" : ""}`}>
          ${attachments.length > 0 && html`<div class="user-attachments" data-body-gap=${hasBody}>
            ${attachments.map(item => {
              const index = media.findIndex(current => current.index === item.index);
              const failed = item.unavailable || failedImages.has(item.index);
              // Captioned media keeps its filename outside the thumbnail's indication and hit box.
              const captioned = item.type === "file" || item.type === "pdf" || item.unavailable;
              const thumbnail = html`<button key=${item.index} class=${`user-attachment${captioned ? " captioned-thumbnail" : ""}`} type="button" title=${item.fileName}
                aria-label=${item.fileName || t.attachments} disabled=${failed || index < 0}
                onClick=${() => setPreview(index)}>
                ${!failed && index >= 0 ? html`<img src=${media[index].url} alt=${item.fileName || t.attachments}
                  onError=${() => setFailedImages(current => new Set([...current, item.index]))} />`
                  : html`<span class="attachment-missing">${icon(ICON_IMAGE)}</span>`}
              </button>`;
              return captioned ? html`<span key=${item.index} class="user-attachment-column">
                ${thumbnail}<span class="user-attachment-name">${item.fileName}</span>
              </span>` : html`<span key=${item.index} class="user-attachment-media">${thumbnail}</span>`;
            })}
          </div>`}
          ${result ? html`<${SubagentResult} result=${result} />`
            : (hasText || askUser) && html`<div class="user-text" ref=${text}></div>`}
        </div>
      </div>
      ${branchControl}
      ${(menuOpen || retainedMenu) && html`<${MoreMenu} expanded=${menuOpen} reduceMotion=${reduceMotion}
        anchor=${menuButton} onClose=${() => setMenuOpen(false)}
        onExited=${(ownedFocus) => {
          setRetainedMenu(false);
          if (ownedFocus) menuButton.current?.focus();
        }}>
        <${RowMenuItem} path=${ICON_CONTENT_COPY} label=${t.copy} enabled=${visible.trim().length > 0}
          onClick=${() => { setMenuOpen(false); copyRowText(message); }} />
        <${RowMenuItem} path=${ICON_EDIT} label=${t.edit} enabled=${editable}
          onClick=${() => { setMenuOpen(false); edit(); }} />
        <${RowMenuItem} path=${ICON_SELECT_ALL} label=${t.selectText} enabled=${visible.trim().length > 0}
          onClick=${() => { setMenuOpen(false); setSelecting(true); }} />
        <${RowMenuItem} path=${ICON_INFO} label=${t.info}
          onClick=${() => { setMenuOpen(false); setInfo(true); }} />
        <${RowMenuItem} path=${ICON_DELETE} label=${t.delete} destructive enabled=${!generating}
          onClick=${() => { setMenuOpen(false); onDelete(); }} />
      </${MoreMenu}>`}
      ${selecting && !retainedMenu && html`<${DetailSheet} title=${t.selectText}
        display=${{ reduceMotion }} focusReturn=${menuButton} onClose=${() => setSelecting(false)}>
        <div class="detail-sheet-select-text">${visible}</div>
      </${DetailSheet}>`}
      ${info && html`<${MessageInfoDialog} message=${message} onClose=${() => setInfo(false)} />`}
      ${preview != null && html`<${AttachmentViewer} viewer=${{ items: media, index: preview }}
        onNavigate=${setPreview} onClose=${() => setPreview(null)} />`}
      ${editing && editable && html`<${MessageEditDialog} message=${message} canEdit=${canEdit} onClose=${() => setEditing(false)} />`}
    </div>`;
}


/**
 * The context-compact row. MessageItem draws a pill instead of the summary text and centres it in
 * the row: a fully rounded Surface at least 42 dp tall with 7 dp of side padding and 7 dp between
 * children, a 32 dp mark slot holding the 18 dp state mark, one labelLarge line, and a trailing 32 dp
 * IconButton. The pill itself owns the click and opens the compact SegmentDetailSheet, so its press
 * wave is the pill shape; the detail button inherits the Surface content colour (onSecondaryContainer,
 * onSurfaceVariant at 80 % when the compact failed) and is never tinted primary. Its menu is the
 * phone's two items, Recompat and Delete.
 */
function CompactPill({ message, reduceMotion, generating, display, wrap, onRecompact, onDelete }) {
  const running = !["SUCCESS", "ERROR", "STOPPED"].includes(message.status);
  const presentation = running ? "running" : message.status.toLowerCase();
  const label = running ? t.compactRunning : message.status === "SUCCESS" ? t.compactDone
    : message.status === "ERROR" ? t.compactError : t.compactStopped;
  // MessageItem: actionsEnabled = compactMessageActionsEnabled(!isLoading, !isStopping, !isCompacting)
  // && !compactInProgress, and it gates Recompact and Delete alike.
  const actionsEnabled = !generating && !running;
  const [detail, setDetail] = useState(false);
  return html`
    <div class="compact-row">
      <div class=${`compact-pill-box ${presentation}`}>
        <button class=${`compact-pill ${presentation}`} type="button" onClick=${() => setDetail(true)}>
          <span class="compact-mark">
            ${running && html`<${CircularProgress} size=${18} stroke=${2} color="var(--md-on-secondary-container)" />`}
            ${message.status === "ERROR" && icon(ICON_COMPACT_ERROR)}
            ${message.status === "STOPPED" && icon(ICON_STOP_CIRCLE, "0 0 24 24", "evenodd")}
            ${message.status === "SUCCESS" && icon(ICON_COMPRESS)}
          </span>
          <span class="compact-label">${label}</span>
        </button>
        <div class="compact-detail">
          <${RowMore} reduceMotion=${reduceMotion} items=${(close) => html`
            <${RowMenuItem} path=${ICON_REFRESH} label=${t.recompact} enabled=${actionsEnabled}
              onClick=${() => { close(); onRecompact(); }} />
            <${RowMenuItem} path=${ICON_DELETE} label=${t.delete} destructive enabled=${actionsEnabled}
              onClick=${() => { close(); onDelete(); }} />`} />
        </div>
      </div>
      ${detail && html`<${DetailSheet} title=${t.compact} display=${display} onClose=${() => setDetail(false)}>
        <div class="detail-sheet-markdown"><${Markdown} text=${message.text} variant="thought" wrap=${wrap}
          streaming=${running} reduceMotion=${reduceMotion} emptyStreamingText=${t.compactStreaming} />
          ${message.presentation?.compactDetailErrorText != null && html`<div class="generation-activity generation-terminal">${message.presentation.compactDetailErrorText}</div>`}
        </div>
      </${DetailSheet}>`}
    </div>`;
}

/**
 * AssistantMessageContent's action line: a 44 dp Box under the answer with 12 dp of top padding.
 * Copy and MoreVert are the information group, Regenerate, Fork and Share the terminal group, and
 * both ride one alpha: 320 ms in, 220 ms out, linear.
 */
function AssistantActions({ message, visible, generating, reduceMotion, onFork, onShare, onInfo, onDelete, branchControl, onSources }) {
  const terminalEnabled = visible && !generating;
  const copyTextValue = message.presentation?.copyText ?? readableText(message);
  const [retainSources, setRetainSources] = useState(visible);
  useLayoutEffect(() => { if (visible) setRetainSources(true); }, [visible]);
  const sources = message.presentation?.sources ?? [];
  return html`
    <div class="message-information" data-visible=${visible} inert=${!visible} aria-hidden=${visible ? null : "true"}
      onTransitionEnd=${event => {
        if (event.target === event.currentTarget && event.propertyName === "opacity" && !visible) setRetainSources(false);
      }}>
      ${(visible || retainSources) && sources.length > 0 && html`<button class="citation-summary" type="button" disabled=${!visible}
        onClick=${onSources}>${icon(ICON_LINK)}<span>${message.presentation.sourcesTitle}</span></button>`}
      <div class="message-actions">
      ${copyTextValue.trim().length > 0 && html`<${RowAction} path=${ICON_CONTENT_COPY} size=${16}
        label=${t.copy} enabled=${visible} onClick=${() => copyRowText(message)} />`}
      <${RowAction} path=${ICON_REFRESH} size=${19} enabled=${terminalEnabled} label=${t.regenerate}
        onClick=${() => sync.rowCommand("regenerate", message.id)} />
      <${RowAction} path=${ICON_CALL_SPLIT} size=${18} enabled=${terminalEnabled} label=${t.forkFromHere}
        onClick=${onFork} />
      <${RowAction} path=${ICON_SHARE} size=${16} enabled=${terminalEnabled} label=${t.share}
        onClick=${onShare} />
      <${RowMore} reduceMotion=${reduceMotion} enabled=${visible} items=${(close) => html`
        <${RowMenuItem} path=${ICON_INFO} label=${t.info} enabled=${visible} onClick=${() => { close(); onInfo(); }} />
        <${RowMenuItem} path=${ICON_DELETE} label=${t.delete} destructive enabled=${terminalEnabled}
          onClick=${() => { close(); onDelete(); }} />`} />
      ${branchControl}
      </div>
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
/**
 * AnimatedTimelineBlockAppearance: a segment that joins a card while it streams fades in (linear
 * 420 ms) and scales from 0.90 (LinearOutSlowIn) once; a tool row is forceOpaque and only scales.
 * The session registry claims the key at the row itself, so a rebuilt list never replays it.
 */
function SegmentAppearance({ appearanceKey, appearances, streaming, opaque, children }) {
  const entering = useRef(!appearances.has(appearanceKey) && streaming);
  useEffect(() => { appearances.add(appearanceKey); }, [appearanceKey]);
  return html`<div class=${`segment-appearance${entering.current ? " entering" : ""}${opaque ? " opaque" : ""}`}>${children}</div>`;
}
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
  const titleMotion = useRef({ text: title, animation: null, outgoing: null });
  useLayoutEffect(() => {
    const motion = titleMotion.current, node = titleNode.current;
    if (motion.text === title || !node) return;
    motion.animation?.cancel();
    motion.outgoing?.remove();
    const outgoing = node.cloneNode(true);
    outgoing.textContent = motion.text;
    outgoing.classList.add("title-outgoing");
    outgoing.setAttribute("aria-hidden", "true");
    outgoing.inert = true;
    node.parentElement.append(outgoing);
    motion.text = title;
    motion.outgoing = outgoing;
    outgoing.animate([{ opacity: 1 }, { opacity: 0 }], { duration: 280, easing: "linear" });
    motion.animation = node.animate([{ opacity: 0 }, { opacity: 1 }], { duration: 280, easing: "linear" });
    motion.animation.onfinish = () => { outgoing.remove(); motion.animation = null; motion.outgoing = null; };
  }, [title]);
  useLayoutEffect(() => () => {
    titleMotion.current.animation?.cancel();
    titleMotion.current.outgoing?.remove();
  }, []);
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
        // collapsedHeaderWidth: 12 + 18 + 8 + title + 4 + 18 + 12 + THINKING_COLLAPSED_WIDTH_ALLOWANCE_DP (6).
        collapsed: Math.min(parent.clientWidth, Math.ceil(context.measureText(text.textContent).width) + 78),
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
  // retainExpandedLayoutDuringFade: the header and items keep the expanded layout width while the
  // card collapses, so the header's press wave is clipped only by the shrinking card, never by a
  // header that already jumped to its collapsed width. The layout is released when the card's
  // width transition settles.
  const [retainExpandedLayout, setRetainExpandedLayout] = useState(targetExpanded);
  useEffect(() => {
    if (targetExpanded) { setRetainExpandedLayout(true); return; }
    const reduced = document.querySelector(".shell")?.dataset.reduceMotion === "true";
    if (reduced || widths == null || widths.collapsed === widths.expanded) setRetainExpandedLayout(false);
  }, [targetExpanded, widths]);
  const layoutExpanded = targetExpanded || retainExpandedLayout;
  const settleWidth = (event) => {
    if (event.target === surface.current && event.propertyName === "width" && !targetExpanded) {
      setRetainExpandedLayout(false);
    }
  };
  return html`
    <div class=${`info-group ${targetExpanded ? "expanded" : ""} ${opensSheet ? "sheet-mode" : ""} ${group.precededByAnswer ? "after-answer" : ""} ${group.items.some((item) => item.type === "tool") ? "has-tool" : ""} ${firstAppearance.current ? "entering" : ""}`}
      data-group=${group.key}
      style=${widths == null ? null : { "--card-content-width": `${layoutExpanded ? widths.expanded : widths.collapsed}px`, "--card-expanded-width": `${widths.expanded}px` }}>
      <div class="info-group-surface" ref=${surface} onTransitionEnd=${settleWidth}
        style=${widths == null ? null : { width: `${targetExpanded ? widths.expanded : widths.collapsed}px` }}>
        <button class="info-group-header" type="button" aria-expanded=${opensSheet ? null : expanded}
          aria-haspopup=${opensSheet ? "dialog" : null}
          onClick=${opensSheet ? () => onOpenSheet?.(messageId, group.key) : toggle}>
          <span class="info-header-icon"><${CardIcon} kind=${group.icon} /></span>
          <span class="info-title-stack"><span class="info-header-title" ref=${titleNode}>${title}</span></span>
          <span class="info-disclosure">${icon(ICON_CHEVRON_DOWN)}</span>
        </button>
        <div class="info-group-reveal" inert=${!targetExpanded}>
          <div class="info-group-items">
            ${group.items.map((item) => html`<${SegmentAppearance} key=${item.detailIndex}
              appearanceKey=${`${messageId}:segment:${item.detailIndex}:${item.type}`}
              appearances=${appearances} streaming=${streaming} opaque=${item.type === "tool"}>
              <${InfoItem} item=${item} compact reduceMotion=${!!display?.reduceMotion}
                onClick=${canOpenSheetItem(item)
                  ? () => onOpenDetail(messageId, group.key, item.detailIndex) : undefined} />
            </${SegmentAppearance}>`)}
          </div>
        </div>
      </div>
    </div>`;
}

function InfoCard({ block, messageId, appearances, streaming, onOpenDetail, display }) {
  const key = `${messageId}:card:${block.item.detailIndex}`;
  const entering = useRef(!appearances.has(key) && streaming);
  useEffect(() => { appearances.add(key); }, [key]);
  return html`
    <div class=${`info-card ${block.groupPosition.toLowerCase()} ${block.precededByAnswer ? "after-answer" : ""} ${block.item.type === "tool" ? "has-tool" : ""} ${entering.current ? "entering" : ""}`}
      data-detail=${block.item.detailIndex}>
      <div class="info-card-surface"><${InfoItem} item=${block.item} reduceMotion=${!!display?.reduceMotion}
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
function GenerationActivity({ activity, terminal, stopping, reduceMotion, precededByCard, failed }) {
  const retainedActivity = useRef(activity);
  if (activity) retainedActivity.current = activity;
  const terminalText = terminal;
  const active = stopping ? null : activity;
  const retainLayout = stopping && activity != null;
  if (!active && !retainLayout && terminalText == null) return null;
  const drawn = active ?? retainedActivity.current;
  return html`<div class=${`generation-inline-slot${precededByCard ? " after-card" : ""}`}>
    <div class="generation-inline-draw" data-hidden=${!active} data-stopping=${stopping}
      aria-hidden=${!active ? "true" : null}>
      ${drawn?.kind === "retry"
        ? html`<${RetryActivity} label=${`${drawn.retryText || ""}...`} reduceMotion=${reduceMotion} />`
        : html`<div class="generation-activity"><span class="generation-dot" role=${active ? "status" : null} aria-label="Generating"></span></div>`}
    </div>
    ${terminalText != null && html`<div class="generation-activity generation-inline-terminal" data-failed=${failed}>${terminalText}</div>`}
  </div>`;
}

/** AssistantMessageContent reads the same presentation decisions as the phone. */
export function ModelMessage({ message, wrap, display, expansion, expansionController, appearances, streaming, stopping,
  generating, reduceMotion, deletesConversation, onOpenSheet, onOpenDetail, search, matches, onFork, onShare, births, branchControl, onDelete }) {
  const presentation = message.presentation;
  const error = message.participant === "ERROR";
  const [info, setInfo] = useState(false);
  const [sources, setSources] = useState(null);
  const [sourceDetail, setSourceDetail] = useState(null);
  const [pendingSource, setPendingSource] = useState(null);
  function activateSource(source) {
    if (source.url && /^https?:\/\//i.test(source.url)) window.open(source.url, "_blank", "noopener,noreferrer");
    else setSourceDetail(source);
  }
  function openCitation(values) {
    if (values.length === 1) activateSource(values[0]);
    else setSources(values);
  }
  const dialogs = html`
    ${info && html`<${MessageInfoDialog} message=${message} onClose=${() => setInfo(false)} />`}
    ${sources && html`<${DetailSheet} display=${display} title=${`${sources.length} ${presentation.sourcesLabel}`}
      closing=${!!pendingSource} onClose=${() => { setSources(null); if (pendingSource) { activateSource(pendingSource); setPendingSource(null); } }}>
      <div class="citation-sources">${sources.map((source, index) => html`<button class="citation-source" type="button" key=${source.id}
        onClick=${() => setPendingSource(source)}><span class="citation-number">${index + 1}</span><span class="citation-source-content">
          <span class="citation-source-title">${source.title}</span>
          ${(source.location || source.fileName) && html`<span class="citation-source-meta">${[source.fileName, source.location].filter(Boolean).join(" - ")}</span>`}
        </span></button>`)}</div>
    </${DetailSheet}>`}
    ${sourceDetail && html`<${DetailSheet} display=${display} title=${sourceDetail.title} onClose=${() => setSourceDetail(null)}>
      <div class="citation-detail">${[sourceDetail.fileName, sourceDetail.location, sourceDetail.excerpt].filter(Boolean).map(text => html`<p>${text}</p>`)}</div>
    </${DetailSheet}>`}`;
  // The compact summary never reads as prose: MessageItem owns a pill for it instead.
  if (message.compact) {
    return html`<div class="model-message">
      <${CompactPill} message=${message} reduceMotion=${reduceMotion} generating=${generating}
        display=${display} wrap=${wrap}
        onRecompact=${() => sync.rowCommand("recompact", message.id)}
        onDelete=${onDelete} />${dialogs}</div>`;
  }
  let body = null;
  if (presentation?.useTimeline) {
    body = presentation.blocks.map((block) => {
      switch (block.type) {
        case "answer":
          return html`<div class="answer-block" key=${`a${block.index}`}>
            <${Markdown} text=${block.text} wrap=${wrap} search=${search} sourceStart=${block.sourceStart}
              streaming=${streaming && block.streaming} reduceMotion=${reduceMotion} births=${births} onCitation=${openCitation}
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
            messageId=${message.id} appearances=${appearances} streaming=${streaming} display=${display}
            onOpenDetail=${onOpenDetail} />`;
        default:
          return null;
      }
    });
  } else {
    body = html`
      ${presentation?.thinkingCard && html`<${InfoGroup} group=${presentation.thinkingCard} messageId=${message.id}
        display=${display} expansion=${expansion} expansionController=${expansionController}
        onOpenSheet=${onOpenSheet} onOpenDetail=${onOpenDetail}
        appearances=${appearances} streaming=${streaming} opensSheet=${presentation.useThinkingSheet} />`}
      ${presentation?.answer && html`<${Markdown} text=${presentation.answer} wrap=${wrap} search=${search} matches=${matches}
        streaming=${streaming} reduceMotion=${reduceMotion} births=${births} onCitation=${openCitation} />`}`;
  }
  // The fixed native action slot owns layout; availability changes only paint and interaction.
  const actionsVisible = !error && !streaming && !stopping;
  return html`<div class=${error ? "model-message error" : "model-message"}>${body}<${GenerationActivity}
    activity=${presentation?.inlineActivity} terminal=${presentation?.inlineTerminalText}
    stopping=${stopping} precededByCard=${presentation?.terminalFollowsCard} failed=${message.status === "ERROR"}
    reduceMotion=${!!display?.reduceMotion} />
    ${presentation?.errorBarText != null && html`<div class=${`generation-activity generation-terminal terminal-bar${presentation.terminalFollowsCard ? " after-card" : ""}`}>${presentation.errorBarText}</div>`}
    ${presentation?.stoppedBar && html`<div class=${`generation-activity generation-terminal terminal-bar stopped-bar${presentation.terminalFollowsCard ? " after-card" : ""}`}>${t.generationStopped}</div>`}
    ${!error && html`<div class="message-action-slot">
      ${streaming && presentation?.answerTailVisible && !stopping &&
        html`<div class="message-actions answer-tail"><span class="generation-dot" role="status" aria-label="Generating"></span></div>`}
      <${AssistantActions} message=${message} visible=${actionsVisible} generating=${generating}
        reduceMotion=${reduceMotion} branchControl=${branchControl} onInfo=${() => setInfo(true)}
        onSources=${() => setSources(presentation.sources)}
        onDelete=${onDelete} onFork=${onFork} onShare=${onShare} />
    </div>`}
    ${dialogs}</div>`;
}

/**
 * One path row. `first` and `rows` carry the phone's deletion scope: MessageItem computes
 * deletionRemovesEntireConversation over the visible list, so the first row of a branch deletes the
 * conversation and a compact summary only does so when it is the whole conversation.
 */
function Row({ entry, body, wrap, display, expansion, expansionController, appearances, streaming, stopping,
  generating, reduceMotion, first, rows, onOpenSheet, onOpenDetail, search, onPageAction, glyphBirths, conversationId, branchEnabled, onDelete }) {
  const message = body ?? null;
  const branchControl = entry.showBranchSelector && entry.totalBranches > 1 && html`
    <div class="branch-selector">
      <button class="branch-previous" type="button" aria-label=${t.previousBranch} title=${t.previousBranch}
        disabled=${!branchEnabled || entry.branchIndex <= 0} onClick=${() => sync.rowCommand("branch", entry.id, { direction: -1 })}>${icon(ICON_CHEVRON_RIGHT)}</button>
      <span>${entry.branchIndex + 1} / ${entry.totalBranches}</span>
      <button type="button" aria-label=${t.nextBranch} title=${t.nextBranch}
        disabled=${!branchEnabled || entry.branchIndex >= entry.totalBranches - 1} onClick=${() => sync.rowCommand("branch", entry.id, { direction: 1 })}>${icon(ICON_CHEVRON_RIGHT)}</button>
    </div>`;
  let births = glyphBirths.get(entry.id);
  if (!births) { births = new Map(); glyphBirths.set(entry.id, births); }
  const matches = search?.matches.filter(match => match.messageId === entry.id) || [];
  const deletesConversation = !!message && (message.compact ? rows === 1 : first);
  let content = html`<div class="row-placeholder"></div>`;
  if (message) {
    content = message.participant === "USER" && !message.compact
      ? html`<${UserBubble} message=${message} search=${search} matches=${matches}
          conversationId=${conversationId} branchControl=${branchControl}
          reduceMotion=${reduceMotion} generating=${generating}
          deletesConversation=${deletesConversation} onDelete=${() => onDelete(message, deletesConversation)} />`
      : html`<${ModelMessage} message=${message} wrap=${wrap} display=${display}
          births=${births} branchControl=${branchControl}
          expansion=${expansion} expansionController=${expansionController}
          appearances=${appearances} streaming=${streaming} stopping=${stopping}
          generating=${generating} reduceMotion=${reduceMotion}
          deletesConversation=${deletesConversation} onDelete=${() => onDelete(message, deletesConversation)}
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
 *
 * The entrance is not this component's policy. chatMainContentTransition plays it through
 * AnimatedContent on every arrival at New Chat, while newChatMotionPolicy only decides whether the
 * typewriter runs; the two shared one class here, which left a second and every later switch to New
 * Chat with no transition at all.
 */
const WELCOME_GLYPH_STEP_MS = 100;
// newChatMotionPolicy: newChatEntryId == 1L, so the phone types the welcome once per session and
// shows it static on every later visit.
let welcomeTyped = false;

function ChatWelcome({ reduceMotion }) {
  const typing = !reduceMotion && !welcomeTyped;
  welcomeTyped = true;
  // role="img" keeps the glyph-split spans out of the accessibility tree as one name.
  return html`
    <div class=${`chat-welcome welcome-enters ${typing ? "welcome-typing" : ""}`} role="img"
      aria-label=${t.welcome}>
      <span class="welcome-mark">${icon(ICON_WEB)}</span>
      <span class="welcome-text">${[...t.welcome].map((glyph, index) => html`<span
        class="welcome-glyph" style=${typing
          ? { animationDelay: `${(index + 1) * WELCOME_GLYPH_STEP_MS}ms` }
          : null}>${glyph}</span>`)}</span>
    </div>`;
}

/**
 * MessageItem's generationLifecycleAppearanceModifier: a row the list has never shown before fades
 * in once over MESSAGE_ENTER_DURATION_MS (320, linear, scale 1). The decision is taken when the row
 * mounts and never replays; a row with a tool segment is forceOpaque (style.css).
 */
function RowEntrance({ enter, exiting, children }) {
  const entering = useRef(enter);
  return html`<div class=${entering.current ? "message-entering" : null} data-branch-exit=${exiting ? "true" : null}
    inert=${exiting} aria-hidden=${exiting ? "true" : null}>${children}</div>`;
}

function PageProgress({ active, edge }) {
  const host = useRef(null);
  useLayoutEffect(() => {
    const node = host.current;
    if (active) node.style.visibility = "visible";
    const animation = node.animate([{ opacity: getComputedStyle(node).opacity }, { opacity: active ? 1 : 0 }],
      { duration: 180, easing: "linear", fill: "forwards" });
    animation.onfinish = () => {
      node.style.opacity = active ? "1" : "0";
      if (!active) node.style.visibility = "hidden";
      animation.cancel();
    };
    return () => { node.style.opacity = getComputedStyle(node).opacity; animation.cancel(); };
  }, [active]);
  return html`<div ref=${host} class=${`message-page-progress ${edge}`} data-loading=${active ? "true" : "false"}
    aria-hidden=${active ? null : "true"}><span><${CircularProgress} size=${32} stroke=${3}
      label=${active ? t.loading : null} /></span></div>`;
}
/** Independent token threshold and round cap; whole rounds are never split. */
const PAGE_TOKEN_WINDOW = 256 * 1024;
const MAX_PAGE_ROUNDS = 10;
const MAX_PAGE_WINDOWS = 3;
const OLDER_PAGE_EDGE_PX = 4;
export function MessageList({ state, label, onPageAction }) {
  const scroller = useRef(null);
  const bar = useRef(null);
  const pinned = useRef(false);
  const bottomMotion = useRef(null);
  const tailFollow = useRef(createStreamingTailFollow());
  const tailInput = useRef(null);
  const tailInputHandlers = useRef(null);
  const bottomButton = useRef(null);
  const bottomSeek = useRef(null);
  const bottomRequested = useRef(false);
  const bottomVisibility = useRef({ openId: null, top: 0, travel: 0, heading: false, near: true });
  const refreshBottom = useRef(null);
  const [bottomVisible, setBottomVisible] = useState(false);
  const generationActive = useRef(false);
  const search = state.conversationSearch;
  const searchPosition = useRef(null);
  const searchTarget = useRef(null);
  searchTarget.current = search?.matches[search.index]?.messageId ?? null;
  generationActive.current = !!state.generating;
  const cover = useRef(null);
  const [settledOpenId, setSettledOpenId] = useState(null);
  const switching = state.switching;
  const covered = !!switching;
  const coveredId = useRef(null);
  const expansion = useRef(new Map());
  const expansionController = useRef(createGroupExpansionController());
  const appearances = useRef(new Set());
  const glyphBirths = useRef(new Map());
  const previousOpenId = useRef(state.openId);
  const [sheet, setSheet] = useState(null);
  const [deleteDialog, setDeleteDialog] = useState(null);
  useEffect(() => {
    if (deleteDialog && (deleteDialog.connectionId !== state.connectionId ||
        deleteDialog.seq !== sync.interactionTarget()?.seq ||
        (deleteDialog.conversationId !== state.openId && !state.switching?.confirmation))) setDeleteDialog(null);
  }, [state.connectionId, state.openId, state.switching, deleteDialog]);
  function requestDelete(message, deletesConversation) {
    const target = sync.interactionTarget();
    if (target) setDeleteDialog({ ...target, messageId: message.id, compactOnly: !!message.compact, deletesConversation });
  }
  const sheetWatchedId = sheet?.conversationId === state.openId ? sheet.messageId : null;
  const watchedSheet = useRef(sheetWatchedId);
  watchedSheet.current = sheetWatchedId;
  const branch = state.branchReplacement;
  // Costs survive body eviction, but belong only to this selected conversation. Unknown rounds
  // hydrate in bounded adjacent batches; missing costs are never treated as free.
  const loadCosts = useRef({ openId: state.openId, values: new Map() });
  if (loadCosts.current.openId !== state.openId) loadCosts.current = { openId: state.openId, values: new Map() };
  const rememberCost = message => {
    if (Number.isFinite(message?.loadTokens)) loadCosts.current.values.set(message.id, Math.max(1, message.loadTokens));
  };
  state.bodies.forEach(rememberCost);
  rememberCost(state.streaming);
  const roundStarts = state.path.reduce((starts, entry, index) => {
    if (index === 0 || entry.participant === "USER") starts.push(index);
    return starts;
  }, []);
  const roundCount = roundStarts.length;
  const roundEnd = (round) => (round + 1 < roundCount ? roundStarts[round + 1] : state.path.length);
  const roundOfId = (id) => {
    const index = id ? state.path.findIndex((entry) => entry.id === id) : -1;
    if (index < 0) return -1;
    let round = 0;
    while (round + 1 < roundCount && roundStarts[round + 1] <= index) round += 1;
    return round;
  };
  const roundCost = (round) => {
    let total = 0;
    for (let index = roundStarts[round]; index < roundEnd(round); index += 1) {
      const cost = loadCosts.current.values.get(state.path[index].id);
      if (cost == null) return null;
      total += cost;
    }
    return total;
  };
  /** Include the first crossing round, stop at ten, or exhaust the remaining complete rounds. */
  const fit = (start, end, keep) => {
    if (keep === "start") end = Math.min(end, start + MAX_PAGE_ROUNDS);
    else start = Math.max(start, end - MAX_PAGE_ROUNDS);
    let first = keep === "start" ? start : end;
    let last = first;
    let tokens = 0;
    const probes = [];
    for (let round = keep === "start" ? start : end - 1;
      round >= start && round < end; round += keep === "start" ? 1 : -1) {
      const cost = roundCost(round);
      if (cost == null) {
        let probe = round;
        let rows = 0;
        // Resolve adjacent unknown costs in bounded batches, never price missing text as zero.
        while (probe >= start && probe < end && rows < 48) {
          const entries = state.path.slice(roundStarts[probe], roundEnd(probe));
          if (rows && rows + entries.length > 48) break;
          probes.push(...entries.map(entry => entry.id));
          rows += entries.length;
          probe += keep === "start" ? 1 : -1;
        }
        if (last > first) break;
        if (keep === "start") last = round + 1; else first = round;
        break;
      }
      if (keep === "start") last = round + 1; else first = round;
      tokens += cost;
      if (tokens > PAGE_TOKEN_WINDOW) break;
    }
    return { start: first, end: last, probes };
  };
  const pageEntries = (page) => (page.end > page.start
    ? state.path.slice(roundStarts[page.start], roundEnd(page.end - 1)) : []);
  /** A page is stored by ids, so it survives rows being added; endId null follows the newest round. */
  const pageRequestFor = (page, keep) => ({
    openId: state.openId,
    startId: state.path[roundStarts[page.start]]?.id ?? null,
    endId: page.end >= roundCount ? null : state.path[roundStarts[page.end]].id,
    lastId: state.path[roundStarts[page.end - 1]]?.id ?? null,
    keep,
  });
  const resolvePage = (request, fill = true) => {
    if (request?.openId !== state.openId) return null;
    const start = roundOfId(request.startId);
    const end = request.endId == null ? roundCount : roundOfId(request.endId);
    if (start < 0 || end <= start) return null;
    if (!fill || request.keep === "closed") return { start, end, probes: [] };
    return request.keep === "start" ? fit(start, roundCount, "start") : fit(0, end, "end");
  };
  const [pageRequest, setPageRequest] = useState(null);
  const retainedPages = useRef({ openId: state.openId, windows: [] });
  if (retainedPages.current.openId !== state.openId) retainedPages.current = { openId: state.openId, windows: [] };
  let windows = retainedPages.current.windows.map(page => resolvePage(page, false)).filter(Boolean);
  const retained = windows.length ? { start: windows[0].start, end: windows.at(-1).end } : null;
  const loading = pageRequest?.openId === state.openId && pageRequest.direction;
  let requested = loading
    ? retained ? pageRequest.direction < 0 ? fit(0, retained.start, "end") : fit(retained.end, roundCount, "start") : fit(0, roundCount, "end")
    : resolvePage(pageRequest) ?? (retained ? { ...retained, probes: [] } : fit(0, roundCount, "end"));
  let targeted = false;
  // Distant explicit targets replace the range; adjacent reader paging keeps its existing windows.
  // A send scrolls to the newest message, so it returns to the tail page with its preceding context.
  for (const [id, tail] of [[searchTarget.current, false], [state.scrollRequest?.messageId, true],
    [sheet?.messageId, false], [branch?.request.oldMessageId, false]]) {
    const round = roundOfId(id);
    if (round >= 0 && (round < requested.start || round >= requested.end) &&
        (!retained || round < retained.start || round >= retained.end)) {
      requested = tail ? fit(0, roundCount, "end") : fit(round, roundCount, "start");
      targeted = true;
    }
  }
  const requestedEntries = pageEntries(requested);
  const requestedReady = requested.probes.length === 0 &&
    requestedEntries.every((entry) => state.streaming?.id === entry.id || state.bodies.has(entry.id));
  const displayedPage = useRef(null);
  const previousPage = retained ?? (displayedPage.current ? resolvePage(displayedPage.current, false) : null);
  let displayed = previousPage ?? requested;
  if (requestedReady) {
    if (loading && retained && !targeted) {
      windows = pageRequest.direction < 0 ? [requested, ...windows] : [...windows, requested];
      if (windows.length > MAX_PAGE_WINDOWS) {
        if (pageRequest.direction < 0) windows.pop(); else windows.shift();
      }
    } else if (!retained || targeted || pageRequest?.keep !== "closed" && !loading) {
      windows = [requested];
    }
    displayed = { start: windows[0]?.start ?? requested.start, end: windows.at(-1)?.end ?? requested.end };
    retainedPages.current.windows = windows.map(page => pageRequestFor(page, "closed"));
  }
  useLayoutEffect(() => {
    if (requestedReady && (loading || targeted || pageRequest?.keep !== "closed" || pageRequest.openId !== state.openId)) {
      setPageRequest(pageRequestFor(displayed, "closed"));
    }
  });
  displayedPage.current = pageRequestFor(displayed, "closed");
  const pageLoading = !requestedReady || (!!state.openId && state.initialToolsReady !== undefined &&
    state.initialToolsReady !== 7 && !["failed", "deleted"].includes(state.openStatus));
  const tailReady = requestedReady && displayed.end === roundCount;
  refreshBottom.current = () => {
    const root = scroller.current;
    if (!root) return;
    const latch = bottomVisibility.current;
    if (latch.openId !== state.openId) {
      Object.assign(latch, { openId: state.openId, top: root.scrollTop, travel: 0, heading: false, near: true });
    }
    const remaining = root.scrollHeight - root.clientHeight - root.scrollTop;
    const olderPage = displayed.end < roundCount;
    latch.near = !olderPage && remaining <= (latch.near ? 96 : 48);
    setBottomVisible(latch.heading && !!state.openId && state.path.length > 0 && !covered &&
      state.openStatus === "ready" && !pageLoading && !latch.near &&
      !bottomRequested.current && !bottomMotion.current &&
      !tailFollow.current.following && !branch && !search?.query.trim());
  };
  const pagePath = pageEntries(displayed);
  const windowStart = displayed.end > displayed.start ? roundStarts[displayed.start] : 0;
  const hasOlderRounds = displayed.start > 0;
  const hasNewerRounds = displayed.end < roundCount;
  const pageKey = `${state.openId}:${displayedPage.current.startId}:${displayedPage.current.endId}`;
  // A page change keeps the reader's view: the first row still on screen that the new page also
  // draws is measured before the change and put back at the same offset after it.
  const pageAnchor = useRef(null);
  const pageInput = useRef({ direction: 0, touchY: null, openId: state.openId });
  const pageAtEdge = useRef(null);
  if (pageInput.current.openId !== state.openId) {
    pageInput.current = { direction: 0, touchY: null, openId: state.openId };
  }
  const anchoredPageKey = useRef(null);
  const pagePaint = useRef(null);
  const pageExits = useRef(null);
  const pagePaintIds = useRef({ openId: state.openId, ids: new Set() });
  if (anchoredPageKey.current !== pageKey) {
    // A layout correction is not another gesture. Only fresh input may cross the next edge.
    pageInput.current.direction = 0;
    const root = scroller.current;
    pageAnchor.current = null;
    pagePaint.current = null;
    if (root && anchoredPageKey.current?.startsWith(`${state.openId}:`)) {
      const kept = new Set(pagePath.map((entry) => entry.id));
      const top = root.getBoundingClientRect().top;
      const rows = [...root.querySelectorAll(".message-column .message-row")];
      const row = rows.find(node => kept.has(node.dataset.id) && node.getBoundingClientRect().bottom > top);
      if (row) pageAnchor.current = { id: row.dataset.id, offset: row.getBoundingClientRect().top - top };
      pagePaint.current = { openId: state.openId, incoming: new Set(pagePath.filter(entry =>
        !pagePaintIds.current.ids.has(entry.id)).map(entry => entry.id)), outgoing: rows.filter(node => {
          const bounds = node.getBoundingClientRect();
          return !kept.has(node.dataset.id) && bounds.bottom > top && bounds.top < top + root.clientHeight;
        }).map(node => ({ node: node.cloneNode(true), bounds: node.getBoundingClientRect() })) };
    }
    anchoredPageKey.current = pageKey;
  }
  pagePaintIds.current = { openId: state.openId, ids: new Set(pagePath.map(entry => entry.id)) };
  useLayoutEffect(() => {
    const root = scroller.current;
    const anchor = pageAnchor.current;
    pageAnchor.current = null;
    if (!root) return;
    const column = root.querySelector(".message-column");
    column?.style.removeProperty("padding-bottom");
    if (!anchor || pinned.current) return;
    const row = [...root.querySelectorAll(".message-row")].find((node) => node.dataset.id === anchor.id);
    if (row) {
      const target = root.scrollTop + row.getBoundingClientRect().top - root.getBoundingClientRect().top - anchor.offset;
      const missing = target - (root.scrollHeight - root.clientHeight);
      // A short replacement needs trailing room, otherwise the browser clamps away the anchor.
      if (column && missing > 0) {
        column.style.paddingBottom = `${parseFloat(getComputedStyle(column).paddingBottom) + missing}px`;
      }
      root.scrollTop = target;
    }
  }, [pageKey]);
  useLayoutEffect(() => {
    const paint = pagePaint.current;
    pagePaint.current = null;
    const root = scroller.current, host = pageExits.current;
    if (!root || !host || !paint || paint.openId !== state.openId) return;
    for (const row of root.querySelectorAll(".message-column .message-row")) {
      if (paint.incoming.has(row.dataset.id)) row.parentElement.animate([{ opacity: 0 }, { opacity: 1 }],
        { duration: 180, easing: "linear" });
    }
    host.replaceChildren();
    if (!paint.outgoing.length) { host.style.top = ""; host.style.height = ""; return; }
    const viewport = root.getBoundingClientRect();
    host.style.top = `${root.scrollTop}px`;
    host.style.height = `${root.clientHeight}px`;
    for (const { node, bounds } of paint.outgoing) {
      node.className = "message-page-snapshot";
      node.removeAttribute("data-id");
      node.setAttribute("inert", "");
      node.setAttribute("aria-hidden", "true");
      Object.assign(node.style, { top: `${bounds.top - viewport.top}px`, left: `${bounds.left - viewport.left}px`,
        width: `${bounds.width}px`, height: `${bounds.height}px` });
      host.append(node);
      const fade = node.animate([{ opacity: 1 }, { opacity: 0 }],
        { duration: 180, easing: "linear", fill: "forwards" });
      fade.onfinish = () => {
        node.remove(); fade.cancel();
        if (!host.childElementCount) { host.style.top = ""; host.style.height = ""; }
      };
    }
  }, [pageKey]);
  // Explicit history requests are not displaced by an out-of-page streaming reply.
  const ids = pagePath.map((entry) => entry.id).join(",");
  const branchVisual = useRef({ id: null, entries: [], bodies: new Map() });
  const branchScroll = useRef(null);
  const visualKeys = useRef(new Map());
  if (branch && branchVisual.current.id !== branch.request.id) {
    const start = state.path.findIndex(entry => entry.id === branch.request.oldMessageId);
    branchVisual.current = { id: branch.request.id, entries: start < 0 ? [] : state.path.slice(start),
      bodies: new Map(state.bodies) };
    if (state.streaming) branchVisual.current.bodies.set(state.streaming.id, state.streaming);
  }
  if (branch?.request.sourceUserMessageId && branch.request.targetUserMessageId) {
    visualKeys.current.set(branch.request.targetUserMessageId,
      visualKeys.current.get(branch.request.sourceUserMessageId) || branch.request.sourceUserMessageId);
  }
  const exitIds = new Set(branch ? branchVisual.current.entries.map(entry => entry.id) : []);
  const renderedPath = branch ? [...pagePath, ...branchVisual.current.entries.filter(entry =>
    !state.path.some(active => active.id === entry.id))] : pagePath;
  const branchTarget = branch?.request.targetUserMessageId;
  const watchedBranch = useRef(branchTarget);
  watchedBranch.current = branchTarget;
  // shouldAnimateMessageLifecycleEntrance: only a row the list has not shown yet, never while the
  // conversation is switching, never a branch-exit row or the edited user message that keeps its
  // visual key. A user row enters when it is the scroll target or the newest user message of a
  // running generation; a model row while it streams or when it answers the scroll target.
  const knownRows = useRef({ openId: state.openId, ids: new Set() });
  if (knownRows.current.openId !== state.openId) knownRows.current = { openId: state.openId, ids: new Set() };
  const lastUserId = [...state.path].reverse().find((entry) => entry.participant === "USER")?.id;
  const entranceTarget = state.scrollRequest?.messageId ?? null;
  const shouldEnter = (entry) => {
    if (covered || knownRows.current.ids.has(entry.id) || exitIds.has(entry.id)) return false;
    if (entry.id === branchTarget) return false;
    if (entry.participant === "USER") return entry.id === entranceTarget || (!!state.generating && entry.id === lastUserId);
    if (entry.participant === "MODEL") {
      return state.streaming?.id === entry.id || (entranceTarget != null && entry.parentId === entranceTarget);
    }
    return false;
  };
  useEffect(() => {
    renderedPath.forEach((entry) => knownRows.current.ids.add(entry.id));
  });
  // calculateTailHolderMinHeightPx: the last turn is at least as tall as the viewport minus the
  // 140 dp top target and the bottom obstruction (bottom bar + 8 dp), less whatever of the tail anchor
  // turn (the newest real user message or Compact) sits above it, so a sent message can reach the
  // top target. The web list is flat, so a spacer after the rows supplies the missing height.
  const tailSpacer = useRef(null);
  const tailAnchorId = (() => {
    for (let index = pagePath.length - 1; index >= 0; index -= 1) {
      const entry = pagePath[index];
      const message = state.streaming?.id === entry.id ? state.streaming : state.bodies.get(entry.id);
      if (entry.participant === "USER" || message?.compact) return entry.id;
    }
    return null;
  })();
  useLayoutEffect(() => {
    const root = scroller.current;
    const spacer = tailSpacer.current;
    if (!root || !spacer) return undefined;
    const column = spacer.parentElement;
    const measure = () => {
      const anchor = tailAnchorId
        ? [...column.querySelectorAll(".message-row")].find((row) => row.dataset.id === tailAnchorId) : null;
      if (!anchor) { spacer.style.height = "0px"; return; }
      const style = getComputedStyle(column);
      const minimum = Math.max(0, root.clientHeight - parseFloat(style.paddingTop) - parseFloat(style.paddingBottom));
      const tail = spacer.getBoundingClientRect().top - anchor.getBoundingClientRect().top;
      const height = Math.max(0, Math.round(minimum - tail));
      if (spacer.style.height !== `${height}px`) spacer.style.height = `${height}px`;
    };
    measure();
    const observer = new ResizeObserver(measure);
    observer.observe(root);
    // Composer/tools clearance changes padding without resizing the column's content box.
    observer.observe(column, { box: "border-box" });
    return () => observer.disconnect();
  }, [tailAnchorId, ids]);
  useLayoutEffect(() => {
    const root = scroller.current;
    const request = branch?.request;
    const rows = [...root.querySelectorAll('.message-row')].filter(row =>
      branchVisual.current.entries.some(entry => entry.id === row.dataset.id));
    if (!request) {
      const animations = rows.map(row => row.animate([{ opacity: getComputedStyle(row).opacity }, { opacity: 1 }],
        { duration: 180, easing: 'linear', fill: 'forwards' }));
      animations.forEach((animation, index) => {
        animation.onfinish = () => { rows[index].style.opacity = ''; animation.cancel(); };
      });
      branchVisual.current = { id: null, entries: [], bodies: new Map() };
      branchScroll.current = null;
      return () => animations.forEach(animation => animation.cancel());
    }
    if (branchVisual.current.entries.some(entry => exitIds.has(entry.id))) pinned.current = false;
    const animations = rows.map(row => row.animate([{ opacity: 1 }, { opacity: 0 }],
      { duration: request.fadeFinished ? 0 : 180, easing: 'linear', fill: 'forwards' }));
    Promise.all(animations.map(animation => animation.finished)).then(() => {
      sync.branchAck('branch_fade', branch);
    }).catch(() => {});
    return () => {
      rows.forEach(row => { row.style.opacity = getComputedStyle(row).opacity; });
      animations.forEach(animation => animation.cancel());
    };
  }, [branch?.request.id]);
  useLayoutEffect(() => {
    const request = branch?.request;
    if (request?.stage !== 'COMMITTED' || !request.scrollFinished ||
        state.path.some(entry => entry.id === request.oldMessageId)) return;
    const frame = requestAnimationFrame(() => sync.branchAck('branch_complete', branch));
    return () => cancelAnimationFrame(frame);
  }, [branch, state.path]);
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
    setSheet({ conversationId: state.openId, messageId, groupKey, page: "list", detailIndex: null, listFirst: true });
  }
  function openDetail(messageId, groupKey, detailIndex) {
    setSheet({ conversationId: state.openId, messageId, groupKey, page: "detail", detailIndex, listFirst: false });
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
    visualKeys.current.clear();
    expansion.current.clear();
    expansionController.current.reset();
    // SegmentAppearanceRegistry is session-scoped and lives above the list, so a segment that has
    // already entered once never replays its appearance - not when the list is rebuilt and not when
    // the same conversation is opened again. Keys carry the message id, so conversations never
    // collide and nothing is cleared on a switch.
    glyphBirths.current.clear();
  }, [state.openId]);

  // Chrome runs no CSS transition on its own scrollbar, so the animated bar is drawn beside it; the
  // native bar stays in place and keeps every hit target.
  useEffect(() => {
    const input = bindTailScrollInput(scroller.current, {
      start: () => tailInputHandlers.current?.start(),
      stop: () => tailInputHandlers.current?.stop(),
    }, bar.current);
    tailInput.current = input;
    return () => { input.dispose(); tailInput.current = null; };
  }, []);
  useEffect(() => attachScrollbar(scroller.current, bar.current, direction => {
    tailInput.current?.scrollbarInput();
    pageInput.current.direction = direction;
    pageAtEdge.current?.();
  }), []);

  // The page and its next unknown round share the existing payload watch. Keep the displayed
  // page subscribed until replacement is ready, and retain both through idle-cache eviction.
  const requestedIds = [...new Set([...requestedEntries.map(entry => entry.id), ...requested.probes])].join(",");
  useEffect(() => {
    const requestedList = requestedIds ? requestedIds.split(",") : [];
    const retained = [...pagePath.map(entry => entry.id), ...requestedList];
    sync.keep(retained);
    sync.watch([...new Set([searchTarget.current, sheetWatchedId, branchTarget, ...retained].filter(Boolean))]);
  }, [requestedIds, ids, sheetWatchedId, searchTarget.current, branchTarget]);

  useLayoutEffect(() => {
    const root = scroller.current;
    const observer = new ResizeObserver(() => refreshBottom.current?.());
    observer.observe(root);
    observer.observe(root.firstElementChild);
    refreshBottom.current();
    return () => observer.disconnect();
  }, [state.openId, ids, covered, pageLoading, state.generating, branch, search]);
  useLayoutEffect(() => {
    const node = bottomButton.current;
    const style = getComputedStyle(node);
    const reduced = !!state.display?.reduceMotion;
    const from = { opacity: style.opacity, transform: reduced ? "none" : style.transform, boxShadow: style.boxShadow };
    const animation = node.animate([from, {
      opacity: bottomVisible ? 1 : 0,
      transform: reduced || bottomVisible ? "scale(1)" : "scale(.6)",
      boxShadow: bottomVisible ? "0 1px 3px #0005, 0 4px 8px 3px #0002" : "0 0 0 transparent",
    }], { duration: 400, easing: "cubic-bezier(.4,0,.2,1)", fill: "forwards" });
    animation.onfinish = () => {
      Object.assign(node.style, { opacity: bottomVisible ? "1" : "0", transform: reduced || bottomVisible ? "scale(1)" : "scale(.6)",
        boxShadow: bottomVisible ? "var(--shadow-4)" : "none" });
      animation.cancel();
    };
    return () => {
      const current = getComputedStyle(node);
      Object.assign(node.style, { opacity: current.opacity, transform: current.transform, boxShadow: current.boxShadow });
      animation.cancel();
    };
  }, [bottomVisible, state.display?.reduceMotion]);
  function seekBottom() {
    if (!bottomVisible) return;
    bottomRequested.current = true;
    setBottomVisible(false);
    if (tailReady) bottomSeek.current?.();
    else setPageRequest(pageRequestFor(fit(0, roundCount, "end"), "end"));
  }

  // The app opens a conversation at its newest message. Row bodies arrive after the path, so
  // this same owner releases the cover after visible bodies and bottom layout have settled.
  useLayoutEffect(() => {
    tailFollow.current.reset();
    bottomMotion.current = null;
    pinned.current = true;
    bottomRequested.current = false;
    setBottomVisible(false);
    setSettledOpenId(null);
  }, [state.openId]);
  useLayoutEffect(() => {
    const root = scroller.current;
    const column = root?.firstElementChild;
    if (!root || !column) return undefined;
    let frame = 0;
    let seekFrame = 0;
    let previousLayout = null;
    const coveredOpen = ["open", "fork"].includes(switching?.command.type);
    const pending = state.openStatus === "ready" && settledOpenId !== state.openId && (!covered || coveredOpen);
    const fading = switching?.phase === "covering" || switching?.phase === "revealing";
    const request = state.scrollRequest;
    let seekRequested = false;
    if (request && requestedReady && state.openStatus === "ready" &&
        pagePath.some((entry) => entry.id === request.messageId)) {
      pinned.current = true;
      sync.consumeScroll(request);
      seekRequested = true;
    }

    // One owner drives every programmatic bottom move the way the phone does: a send travels with
    // the feedback controller, growth during a generation uses the short tail correction, and the
    // actor settles on the end sentinel before releasing ownership.
    let motionFrame = 0;
    const bottomError = () => Math.max(0, root.scrollHeight - root.clientHeight - root.scrollTop);
    const updateTail = (now = performance.now()) => {
      if (covered || branch || search?.query.trim()) bottomMotion.current = null;
      tailFollow.current.update({
        generating: generationActive.current,
        blocked: covered || !!branch || !!search?.query.trim() || !!state.composer?.stopping ||
          !!state.display?.reduceMotion || !tailReady || pageLoading,
        handoff: pending || !!bottomMotion.current,
        stickToBottom: state.display?.stickToBottom ?? true,
      }, now);
      tailFollow.current.proximity(bottomError(), !!tailInput.current?.scrolling, now);
      pinned.current = pending || !!bottomMotion.current || tailFollow.current.following;
    };
    let scrollRemainder = 0;
    const scrollBy = (step) => {
      const before = root.scrollTop;
      // DOM scroll offsets quantize fractional movement; retain only unconsumed subpixels.
      const requested = step + scrollRemainder;
      root.scrollTop += requested;
      const consumed = root.scrollTop - before;
      scrollRemainder = requested - consumed;
      return Math.abs(consumed);
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
      const now = performance.now();
      updateTail(now);
      const motion = bottomMotion.current;
      if (!pinned.current) return;
      if (fading) return;
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
      if (state.display?.reduceMotion && motion) {
        root.scrollTop = root.scrollHeight;
        bottomMotion.current = null;
        updateTail(now);
        return;
      }
      const generating = generationActive.current;
      if (!motion) {
        const step = tailFollow.current.frame(bottomError(), now);
        if (Math.abs(step) > 0.05) scrollBy(step);
        pinned.current = tailFollow.current.following;
        if (pinned.current) wake();
        return;
      }
      const active = motion;
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
          scrollBy(error);
          active.stable += 1;
          if (active.stable >= BOTTOM_MOTION.stableFrames) {
            if (generating) {
              bottomMotion.current = null;
              updateTail(now);
              wake();
              return;
            }
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
    };
    function wake() {
      if (!motionFrame) motionFrame = requestAnimationFrame(() => { pump(); refreshBottom.current?.(); });
    }
    const startSeek = () => {
      scrollRemainder = 0;
      bottomRequested.current = false;
      pinned.current = true;
      // A reduced-motion or covered list takes the same instant placement the phone uses.
      if (pending || covered || state.display?.reduceMotion) {
        bottomMotion.current = null;
        root.scrollTop = root.scrollHeight;
        updateTail();
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
    bottomSeek.current = startSeek;
    if (seekRequested || (bottomRequested.current && tailReady)) {
      bottomRequested.current = false;
      startSeek();
    }

    const settle = () => {
      frame = 0;
      // The opening page may still grow upward; settling earlier would leave the reader at its old offset.
      if (!requestedReady) {
        previousLayout = null;
        return;
      }
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
      const atBottom = Math.max(0, root.scrollHeight - root.clientHeight - root.scrollTop) <= 1;
      if (layout === previousLayout && atBottom) {
        setSettledOpenId(state.openId);
      } else {
        previousLayout = layout;
        if (pinned.current) wake();
        frame = requestAnimationFrame(settle);
      }
    };
    const followBottom = (fromFrame = false) => {
      updateTail();
      if (fading) return;
      if (branch) {
        pinned.current = false;
        bottomMotion.current = null;
        const request = branch.request;
        const row = [...column.querySelectorAll('.message-row')].find(node => node.dataset.id === branchTarget);
        if (!request.scrollFinished && row && state.bodies.has(branchTarget)) {
          if (branchScroll.current?.id !== request.id || branchScroll.current.target !== branchTarget) {
            branchScroll.current = { id: request.id, target: branchTarget, started: performance.now(),
              from: root.scrollTop, distance: row.getBoundingClientRect().top - root.getBoundingClientRect().top - parseFloat(getComputedStyle(column).paddingTop),
              done: false };
          }
          const motion = branchScroll.current;
          if (!motion.done && fromFrame) {
            const progress = state.display?.reduceMotion ? 1 : Math.min(1, (performance.now() - motion.started) / 600);
            root.scrollTop = motion.from + motion.distance * cubicBezierEasing(0.3, 0, 0, 1)(progress);
            if (progress === 1) {
              motion.done = true;
              sync.branchAck('branch_scroll', branch, true);
            }
          }
          if (!motion.done && !seekFrame) seekFrame = requestAnimationFrame(() => { seekFrame = 0; followBottom(true); });
        }
        return;
      }
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
        for (const range of CSS.highlights.get("agora-search") ?? []) {
          if (!root.contains(range.startContainer)) continue;
          for (const rect of range.getClientRects()) {
            const old = geometry.get(range.searchKey);
            geometry.set(range.searchKey, {
              top: Math.min(old?.top ?? rect.top, rect.top),
              bottom: Math.max(old?.bottom ?? rect.bottom, rect.bottom),
            });
          }
        }
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
      if (covered && !coveredOpen) return;
      if (pending && !frame) frame = requestAnimationFrame(settle);
    };
    const release = () => {
      tailFollow.current.userDrag();
      if (branch && !branch.request.scrollFinished && !branchScroll.current?.done) {
        branchScroll.current = { id: branch.request.id, target: branchTarget, done: true };
        sync.branchAck('branch_scroll', branch, false);
      }
      pinned.current = false;
      scrollRemainder = 0;
      bottomRequested.current = false;
      bottomMotion.current = null;
      cancelAnimationFrame(motionFrame);
      motionFrame = 0;
      if (searchPosition.current) searchPosition.current.done = true;
      cancelAnimationFrame(seekFrame);
      seekFrame = 0;
      refreshBottom.current?.();
      pageAtEdge.current?.();
    };
    const handlers = {
      start: release,
      stop: () => { updateTail(); if (pinned.current) wake(); refreshBottom.current?.(); },
    };
    tailInputHandlers.current = handlers;
    const follow = new ResizeObserver(followBottom);
    follow.observe(column);
    follow.observe(root);
    followBottom();
    return () => {
      follow.disconnect();
      cancelAnimationFrame(frame);
      cancelAnimationFrame(motionFrame);
      cancelAnimationFrame(seekFrame);
      if (bottomSeek.current === startSeek) bottomSeek.current = null;
      if (tailInputHandlers.current === handlers) tailInputHandlers.current = null;
    };
  }, [state.openId, state.openStatus, ids, state.bodies, state.streaming, settledOpenId, state.scrollRequest,
    state.generating, state.composer?.stopping, search, branch, covered, switching?.phase, tailReady,
    pageLoading, state.display?.reduceMotion, state.display?.stickToBottom]);

  useLayoutEffect(() => {
    const node = cover.current;
    if (!node || !switching || !["covering", "revealing"].includes(switching.phase)) return undefined;
    const entering = switching.phase === "covering";
    coveredId.current = switching.id;
    const animation = node.animate(
      [{ opacity: getComputedStyle(node).opacity }, { opacity: entering ? 1 : 0 }],
      { duration: 200, easing: "cubic-bezier(0.4, 0, 0.2, 1)", fill: "forwards" },
    );
    animation.onfinish = () => {
      node.style.opacity = entering ? "1" : "0";
      animation.cancel();
      if (entering) sync.covered(switching.id);
      else sync.revealed(switching.id);
    };
    return () => {
      node.style.opacity = getComputedStyle(node).opacity;
      animation.cancel();
    };
  }, [switching?.id, switching?.phase]);
  useLayoutEffect(() => {
    if (!switching || switching.phase !== "settling") return undefined;
    const root = scroller.current;
    let frame = 0, previous = null;
    const check = () => {
      frame = 0;
      const failed = !state.connected || ["failed", "deleted"].includes(state.openStatus);
      const waiting = switching.confirmation || !failed && (pageLoading || !requestedReady || document.fonts.status !== "loaded" ||
        root.querySelector('[data-stream-fading="true"]') ||
        [...root.querySelectorAll(".markdown img")].some(image => !image.complete) ||
        (!!state.openId && state.openStatus !== "ready") || (state.connected && state.composer == null));
      const chat = root.closest(".chat");
      const animations = chat.getAnimations({ subtree: true }).filter(animation => {
        const timing = animation.effect?.getComputedTiming();
        return animation.playState !== "finished" && timing?.endTime !== Infinity &&
          !animation.effect?.target?.closest(".conversation-loading-cover");
      });
      const rects = [...root.querySelectorAll(".message-row,.info-group-surface,.info-group-items,img,.markdown")]
        .map(node => { const r = node.getBoundingClientRect(); return [r.x, r.y, r.width, r.height]; });
      const geometry = JSON.stringify([root.scrollHeight, root.clientHeight, root.scrollTop, rects,
        chat.querySelector(".composer-host")?.getBoundingClientRect().toJSON(),
        getComputedStyle(chat).getPropertyValue("--chat-bottom-inset")]);
      const pendingScroll = bottomMotion.current || (branch && !branchScroll.current?.done) ||
        (!failed && ["open", "fork"].includes(switching.command.type) && state.openId && settledOpenId !== state.openId);
      if (!waiting && !pendingScroll && !animations.length && geometry === previous) sync.settled(switching.id);
      else {
        previous = waiting || animations.length || pendingScroll ? null : geometry;
        frame = requestAnimationFrame(check);
      }
    };
    frame = requestAnimationFrame(check);
    return () => cancelAnimationFrame(frame);
  }, [switching, state.connected, state.openId, state.openStatus, state.composer, state.path,
    state.bodies, state.streaming, pageLoading, requestedReady, settledOpenId, branch]);
  useEffect(() => () => sync.cancelSwitch(coveredId.current), []);

  // chatMainContentTransition replaces one page frame with the other: the frame being left fades out
  // over tween(300) while the new one enters, and a switch between two existing conversations plays
  // nothing at all. The outgoing frame is a frozen snapshot rather than a live subtree, because
  // AnimatedContent stops recomposing an exiting target the moment it is not the current one - a live
  // copy would keep watching rows and reporting sizes for content the user has already left.
  const frameKind = showWelcome ? "welcome" : "thread";
  const exiting = useRef(null);
  const enterFrame = useRef(false);
  const lastFrameKind = useRef(frameKind);
  const exitHost = useRef(null);
  if (lastFrameKind.current !== frameKind) {
    // Read while the DOM still holds the frame being replaced: the render body runs before the patch.
    const live = scroller.current?.firstElementChild;
    exiting.current = live
      ? { node: live.cloneNode(true), top: scroller.current.scrollTop } : null;
    enterFrame.current = frameKind === "thread";
    lastFrameKind.current = frameKind;
  }
  useLayoutEffect(() => {
    const host = exitHost.current;
    const leaving = exiting.current;
    exiting.current = null;
    let fade = null;
    // The exit host is absolute inside the scroller, so while it holds a frame it is part of the
    // scroll range. ChatWelcomeContent sits in no scroll container: arriving at New Chat drops the
    // old offset at once and shows the leaving frame through a clipped, shifted copy at the top, so
    // the welcome never inherits a scroll position or a range it can be dragged through.
    const clear = () => { host.replaceChildren(); host.style.top = ""; host.style.height = ""; host.style.overflow = ""; };
    if (host && leaving) {
      const toWelcome = frameKind !== "thread";
      host.replaceChildren(leaving.node);
      if (toWelcome) {
        if (scroller.current) scroller.current.scrollTop = 0;
        host.style.top = "0px";
        host.style.overflow = "hidden";
        leaving.node.style.transform = `translateY(${-leaving.top}px)`;
      } else {
        host.style.top = `${leaving.top}px`;
      }
      host.style.height = `${scroller.current?.clientHeight ?? 0}px`;
      fade = host.animate([{ opacity: 1 }, { opacity: 0 }],
        { duration: 300, easing: "cubic-bezier(0.4, 0, 0.2, 1)", fill: "forwards" });
      fade.onfinish = () => { clear(); fade.cancel(); };
    } else if (host && frameKind !== "thread" && scroller.current) {
      scroller.current.scrollTop = 0;
    }
    // Returning from New Chat to an existing conversation is fadeIn(tween(300)).
    if (enterFrame.current) {
      enterFrame.current = false;
      scroller.current?.firstElementChild?.animate?.([{ opacity: 0 }, { opacity: 1 }],
        { duration: 300, easing: "cubic-bezier(0.4, 0, 0.2, 1)" });
    }
    return () => { if (fade) { fade.onfinish = null; fade.cancel(); clear(); } };
  }, [frameKind]);

  pageAtEdge.current = () => {
    const root = scroller.current;
    if (!root) return;
    const direction = pageInput.current.direction;
    if (covered || pinned.current || bottomMotion.current || branch || search?.query.trim()) return;
    if (pageLoading || !requestedReady) {
      // Reversing during hydration cancels the pending edge, not the reader's retained windows.
      if (loading && direction && direction !== pageRequest.direction) {
        setPageRequest(pageRequestFor(displayed, "closed"));
      }
      return;
    }
    if (hasOlderRounds && direction < 0 && root.scrollTop <= OLDER_PAGE_EDGE_PX) {
      pageInput.current.direction = 0;
      setPageRequest({ openId: state.openId, direction: -1 });
    } else if (hasNewerRounds && direction > 0 && root.scrollHeight - root.clientHeight - root.scrollTop <= OLDER_PAGE_EDGE_PX) {
      pageInput.current.direction = 0;
      setPageRequest({ openId: state.openId, direction: 1 });
    }
  };
  return html`
    <section class="messages" ref=${scroller} aria-label=${label} aria-busy=${covered} inert=${covered}
      onWheel=${event => { pageInput.current.direction = Math.sign(event.deltaY); pageAtEdge.current?.(); }}
      ontouchstart=${event => { pageInput.current.touchY = event.touches[0]?.clientY ?? null; }}
      ontouchmove=${event => {
        const y = event.touches[0]?.clientY;
        if (y != null && pageInput.current.touchY != null) {
          pageInput.current.direction = Math.sign(pageInput.current.touchY - y);
          pageInput.current.touchY = y;
          pageAtEdge.current?.();
        }
      }}
      onKeyDown=${event => {
        const tag = event.target.tagName;
        if (["INPUT", "TEXTAREA", "SELECT"].includes(tag) || event.target.isContentEditable) return;
        if (["ArrowUp", "PageUp", "Home"].includes(event.key)) pageInput.current.direction = -1;
        else if (["ArrowDown", "PageDown", "End", " "].includes(event.key)) {
          pageInput.current.direction = event.key === " " && event.shiftKey ? -1 : 1;
        }
        pageAtEdge.current?.();
      }}
      onScroll=${(event) => {
        const root = event.currentTarget;
        const latch = bottomVisibility.current;
        const delta = root.scrollTop - latch.top;
        if (!covered && !pageLoading && !bottomMotion.current && !branch && !search?.query.trim()) {
          if (delta) latch.travel = Math.sign(delta) === Math.sign(latch.travel) ? latch.travel + delta : delta;
          if (Math.abs(latch.travel) >= 32) { latch.heading = latch.travel > 0; latch.travel = 0; }
        }
        latch.top = root.scrollTop;
        refreshBottom.current?.();
        pageAtEdge.current?.();
      }}>
      ${showWelcome ? html`<${ChatWelcome} reduceMotion=${!!state.display?.reduceMotion} />`
        : html`<div class="message-column">
        <${PageProgress} key="older-page" edge="older" active=${!covered && !!loading && loading < 0 && pageLoading} />
        ${renderedPath.map((entry, index) => html`
          <${RowEntrance} key=${visualKeys.current.get(entry.id) || entry.id} enter=${shouldEnter(entry)}
            exiting=${exitIds.has(entry.id)}>
          <${Row} entry=${entry} wrap=${wrap} display=${state.display}
            search=${search} first=${windowStart === 0 && index === 0} rows=${state.path.length}
            generating=${!!state.generating} reduceMotion=${!!state.display?.reduceMotion}
            branchEnabled=${state.connected && state.openStatus === "ready" && !covered && !state.generating && !state.pendingAction && !branch}
            onPageAction=${onPageAction} onDelete=${requestDelete}
            expansion=${expansion.current} expansionController=${expansionController.current}
            appearances=${appearances.current} glyphBirths=${glyphBirths.current} conversationId=${state.openId}
            onOpenSheet=${openSheet} onOpenDetail=${openDetail}
            streaming=${state.streaming?.id === entry.id}
            stopping=${!!state.composer?.stopping && state.streaming?.id === entry.id}
            body=${exitIds.has(entry.id) ? branchVisual.current.bodies.get(entry.id)
              : state.streaming?.id === entry.id ? state.streaming : state.bodies.get(entry.id)} /></${RowEntrance}>`)}
        ${!hasNewerRounds && html`<div class="message-tail-minimum" key="tail-minimum" ref=${tailSpacer} aria-hidden="true"></div>`}
        <${PageProgress} key="newer-page" edge="newer" active=${!covered && !!loading && loading > 0 && pageLoading} />
      </div>`}
      <div key="history-exit" class="message-page-exits" ref=${pageExits} aria-hidden="true" inert></div>
      <div key="page-exit" class="chat-frame-exits" ref=${exitHost} aria-hidden="true"></div>
    </section>
    <div class="scrollbar-overlay" ref=${bar} aria-hidden="true"><span></span></div>
    <div class="scroll-bottom-slot" inert=${!bottomVisible} aria-hidden=${!bottomVisible}>
      <button ref=${bottomButton} class="scroll-bottom-button" type="button" tabindex=${bottomVisible ? 0 : -1}
        aria-label=${t.scrollToBottom} title=${t.scrollToBottom} onClick=${seekBottom}>${icon(ICON_CHEVRON_DOWN)}</button>
    </div>
    ${covered && html`<div class="conversation-loading-cover" ref=${cover} data-phase=${switching.phase} data-request=${switching.id}
      onPointerDown=${(event) => { event.preventDefault(); event.stopPropagation(); }}
      onWheel=${(event) => event.preventDefault()} onContextMenu=${(event) => event.preventDefault()}>
      <div class="conversation-loading-range">
        <${Spinner} size=${48} stroke=${5} label=${label} />
      </div>
    </div>`}
    ${deleteDialog && html`<${MessageDeleteDialog} state=${state} request=${deleteDialog}
      onClose=${() => setDeleteDialog(null)} />`}
    ${sheet && validSheet && html`<${DetailSheet} key=${`${sheet.conversationId}:${sheet.messageId}:${sheet.groupKey ?? "direct"}`}
      group=${sheetGroup} items=${sheetItems} page=${sheet.page} detailIndex=${sheet.detailIndex}
      selectedItem=${selectedItem} display=${state.display} wrap=${wrap}
      conversationId=${sheet.conversationId} messageId=${sheet.messageId}
      onSelectItem=${(detailIndex) => setSheet({ ...sheet, page: "detail", detailIndex })}
      onBack=${sheet.page === "detail" && sheet.listFirst
        ? () => setSheet({ ...sheet, page: "list", detailIndex: null }) : null}
      onClose=${() => setSheet(null)} />`}`;
}
