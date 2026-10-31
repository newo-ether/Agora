// Assistant presentation consumes the server's timeline, grouped cards and activity decisions.
import { useEffect, useLayoutEffect, useRef, useState } from "./vendor/preact-hooks.mjs";
import { html } from "./html.js";
import { CircularProgress } from "./material/progress.js";
import { Markdown } from "./markdown.js";
import {
  icon, ICON_CHEVRON_DOWN, ICON_COMPRESS, ICON_COMPACT_ERROR, ICON_DELETE, ICON_REFRESH, ICON_STOP_CIRCLE,
} from "./icons.js";
import { t } from "./i18n.js";
import { sync } from "./sync.js";
import { DetailSheet, CardIcon, InfoItem, useCardTitle, canOpenSheetItem } from "./detail-sheet.js";
import { RowMore, RowMenuItem, MessageInfoDialog, AssistantActions } from "./message-actions.js";
import { cubicBezierEasing } from "./message-scroll.js";

// Compose LinearOutSlowInEasing, the curve the retry line reveals its graphemes with.
const linearOutSlowIn = cubicBezierEasing(0, 0, 0.2, 1);

export function groupForMessage(message, groupKey) {
  const presentation = message?.presentation;
  if (!presentation) return null;
  if (presentation.thinkingCard?.key === groupKey) return presentation.thinkingCard;
  return presentation.blocks?.find((block) => block.type === "group" && block.group.key === groupKey)?.group ?? null;
}

export function sheetItemsForMessage(message, groupKey) {
  if (!message?.presentation) return [];
  if (groupKey != null) return groupForMessage(message, groupKey)?.items ?? [];
  return message.presentation.blocks
    ?.filter((block) => block.type === "card")
    .map((block) => block.item) ?? [];
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

export function createGroupExpansionController() {
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
  generating, reduceMotion, deletesConversation, onOpenSheet, onOpenDetail, search, matches, onFork, onShare, births, branchControl, onDelete, conversationId }) {
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
            <${Markdown} text=${block.text} conversationId=${conversationId} messageId=${message.id} wrap=${wrap} search=${search} sourceStart=${block.sourceStart}
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
      ${presentation?.answer && html`<${Markdown} text=${presentation.answer} conversationId=${conversationId} messageId=${message.id} wrap=${wrap} search=${search} matches=${matches}
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
