// Shared message-detail and Composer settings sheet presentation.
import { useEffect, useLayoutEffect, useRef, useState } from "./vendor/preact-hooks.mjs";
import { html, runSpring } from "./html.js";
import { Spinner } from "./material/progress.js";
import { attachScrollbar } from "./material/scrollbar.js";
import { Markdown, fadeStreamingText } from "./markdown.js";
import { AttachmentViewer } from "./attachments.js";
import { icon, ICON_ACCOUNT_TREE, ICON_BROKEN_IMAGE, ICON_BUILD, ICON_CHECKLIST, ICON_CHEVRON_RIGHT, ICON_IMAGE, ICON_MOBILE_HAND, ICON_NEUROLOGY } from "./icons.js";
const SHEET_BACK_PATH = "M20 11H7.83l5.59-5.59L12 4l-8 8 8 8 1.41-1.41L7.83 13H20v-2z";
// Material Icons.Default.Close, centred on (12, 12). The previous path was offset to the left,
// so the X sat off-centre inside its round button.
const SHEET_CLOSE_PATH = "M19 6.41 17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z";
export function canOpenSheetItem(item) {
  return item?.type === "thought" || item?.type === "transcription" ||
    (item?.type === "tool" && item.toolDetail != null);
}

function ToolDocument({ document }) {
  if (!document) return null;
  return html`<div class="tool-document">
    ${document.text != null ? html`<div class="tool-code tool-plain">${document.text}</div>`
      : document.roots.map((node, index) => html`<${ToolJsonNode} key=${index} node=${node} />`)}
    ${document.marker && html`<div class="tool-muted tool-marker">${document.marker}</div>`}
  </div>`;
}

/** Draw only the shared parser's real prefix nodes; never complete or parse JSON in the browser. */
function ToolJsonNode({ node, depth = 0 }) {
  if (node.emptyText != null) return html`<span class="tool-muted">${node.emptyText}</span>`;
  if (node.type === "scalar") return html`<span class=${`tool-scalar ${node.kind === "STRING" ? "" : "tool-code literal"}`}>
    ${node.kind === "NULL" && node.complete ? "\u2014" : node.content}</span>`;
  if (node.type === "array") return html`<div class="tool-json-array">
    ${node.values.map((value, index) => html`<div class="tool-json-array-row" key=${index}>
      <span class="tool-json-label">${index + 1}</span>
      <div class="tool-json-value"><${ToolJsonNode} node=${value} depth=${depth} /></div>
    </div>`)}
  </div>`;
  return html`<div class="tool-json-object">
    ${node.entries.map((entry, index) => {
      const value = entry.value;
      const block = value?.type === "scalar" && value.kind === "STRING" &&
        (value.content.length > 40 || value.content.includes("\n"));
      const nested = value && value.type !== "scalar" && value.emptyText == null;
      return html`<div class="tool-json-entry" key=${index}>
        <div class="tool-json-row">
          ${(entry.key || entry.keyComplete) && html`<span class="tool-json-label key">${entry.key}</span>`}
          ${value && !block && (nested
            ? html`<span class="tool-muted">${value.type === "object" ? "{\u2026}" : "[\u2026]"}</span>`
            : html`<div class="tool-json-value"><${ToolJsonNode} node=${value} /></div>`)}
        </div>
        ${block && html`<div class="tool-json-block"><${ToolJsonNode} node=${value} /></div>`}
        ${nested && html`<div class="tool-json-nested" style=${{ paddingLeft: `${(depth + 1) * 16}px` }}>
          <${ToolJsonNode} node=${value} depth=${depth + 1} />
        </div>`}
      </div>`;
    })}
  </div>`;
}

function ToolPill({ text, emphasized = false }) {
  return text == null ? null : html`<span class=${`tool-pill ${emphasized ? "emphasized" : ""}`} title=${text}>${text}</span>`;
}

function ToolOutput({ text }) {
  return html`<div class="tool-output tool-code">${text}</div>`;
}

/** File name in primary color, directory as small grey text that keeps its tail visible. */
function ToolPath({ path }) {
  if (!path) return null;
  return html`<div class="tool-path" title=${path.directory || null}>
    <div class="tool-path-name tool-code">${path.name}</div>
    ${path.directory && html`<div class="tool-path-dir"><bdi>${path.directory}</bdi></div>`}
  </div>`;
}

function highlightedText(text, highlights) {
  if (!highlights || highlights.length === 0) return text;
  const parts = [];
  let cursor = 0;
  highlights.forEach(([start, end], index) => {
    if (start < cursor || end <= start) return;
    if (start > cursor) parts.push(text.slice(cursor, start));
    parts.push(html`<mark class="tool-hit" key=${index}>${text.slice(start, end)}</mark>`);
    cursor = end;
  });
  if (cursor < text.length) parts.push(text.slice(cursor));
  return parts;
}

/** Numbered rows with a non-selectable gutter; wrapped text stays aligned with its column. */
function ToolCodeRows({ rows }) {
  return html`<div class="tool-output tool-code-rows">
    ${rows.map((row, index) => row.gap
      ? html`<div class="tool-code-gap" key=${index} aria-hidden="true">\u22ef</div>`
      : html`<div class=${`tool-code-row ${row.context ? "context" : ""}`} key=${index}>
        <span class="tool-gutter" aria-hidden="true">${row.number ?? "\u2014"}</span>
        <span class="tool-code">${highlightedText(row.text ?? "", row.highlights)}</span>
      </div>`)}
  </div>`;
}

/** A string argument (shell command, written file content) shown as code under its key name. */
function ToolCodeArgument({ code }) {
  return html`<div class="tool-code-argument">
    <div class="tool-code-argument-name tool-code">${code.name}</div>
    ${code.rows && code.rows.length > 0 ? html`<${ToolCodeRows} rows=${code.rows} />` : html`<${ToolOutput} text=${code.text} />`}
  </div>`;
}

function ToolDiff({ body }) {
  return html`${body.files?.length ? html`<div class="tool-grep">
      ${body.files.map((file, index) => html`<div key=${index}>
        <div class="tool-meta-row tool-file-meta">
          <${ToolPath} path=${file.path} /><${ToolPill} text=${file.operationLabel} />
        </div>
        ${file.moveTo && html`<div class="tool-file-meta"><${ToolPath} path=${file.moveTo} /></div>`}
        <${ToolDiffHunks} hunks=${file.hunks} replaceAllLabel=${body.replaceAllLabel} />
      </div>`)}
    </div>` : html`
      <div class="tool-meta-row tool-file-meta">
        <${ToolPath} path=${body.path} /><${ToolPill} text=${body.editCount} />
      </div>
      <${ToolDiffHunks} hunks=${body.hunks} replaceAllLabel=${body.replaceAllLabel} />`}
    <div class="tool-muted tool-gap">${body.status}</div>`;
}

function ToolDiffHunks({ hunks, replaceAllLabel }) {
  const sign = { removed: "-", added: "+", context: " " };
  return html`<div class="tool-diff-hunks">
    ${hunks.map((hunk, index) => html`<div key=${index}>
      ${(hunk.index != null || hunk.replaceAll) && html`<div class="tool-meta-row tool-diff-meta">
        ${hunk.index != null && html`<${ToolPill} text=${`#${hunk.index}`} />`}
        ${hunk.replaceAll && html`<${ToolPill} text=${replaceAllLabel} emphasized />`}
      </div>`}
      <div class="tool-output tool-diff">
        ${hunk.lines.map((line, i) => html`<div class=${`tool-diff-line ${line.kind}`} key=${i}>
          <span class="tool-diff-sign" aria-hidden="true">${sign[line.kind] ?? " "}</span>
          <span class="tool-code">${line.text}</span>
        </div>`)}
      </div>
    </div>`)}
  </div>`;
}

function ToolBody({ body }) {
  switch (body.type) {
    case "active":
    case "failed":
      return html`<div class=${body.type === "active" ? "tool-active" : "tool-terminal"}>${body.text}</div>
        ${body.output && html`<div class="tool-gap"><${ToolOutput} text=${body.output} /></div>`}`;
    case "stopped": return html`<div class="tool-terminal stopped">${body.text}</div>`;
    case "muted": return html`<div class="tool-muted">${body.text}</div>`;
    case "documents": return body.values.map((document, index) => html`
      <div class="tool-result-document" key=${index}><${ToolDocument} document=${document} /></div>`);
    case "shell": return html`<div class="tool-meta-row">
        <${ToolPill} text=${body.status} emphasized /><${ToolPill} text=${body.device} />
      </div>
      ${body.error && html`<div class="tool-terminal tool-gap">${body.error}</div>`}
      ${body.output != null && html`<div class="tool-gap"><${ToolOutput} text=${body.output} /></div>`}`;
    case "paths": return html`<div class="tool-paths">
      ${body.values.map((path, index) => html`<${ToolPath} key=${index} path=${path} />`)}
    </div>`;
    case "grep": return html`<div class="tool-grep">
      ${body.groups.map((group, index) => html`<div key=${index}>
        <div class="tool-grep-path"><${ToolPath} path=${group.path} /></div>
        <${ToolCodeRows} rows=${group.rows} />
      </div>`)}
    </div>`;
    case "edit": return html`<${ToolDiff} body=${body} />`;
    case "file": return html`
      ${(body.path || body.lineCount) && html`<div class="tool-meta-row tool-file-meta">
        <${ToolPath} path=${body.path} /><${ToolPill} text=${body.lineCount} />
      </div>`}
      ${!body.content ? html`<div class="tool-muted">${body.emptyText}</div>`
        : body.lines != null ? html`<${ToolCodeRows} rows=${body.lines} />` : html`<${ToolOutput} text=${body.content} />`}
      ${body.truncationText && html`<div class="tool-muted tool-gap">${body.truncationText}</div>`}`;
    case "search": return html`<div class="tool-search-results">
      ${body.results.map((result, index) => {
        const Tag = result.safeUrl ? "a" : "div";
        return html`<${Tag} class="tool-search-result" key=${index} href=${result.safeUrl ?? null}
          target=${result.safeUrl ? "_blank" : null} rel=${result.safeUrl ? "noopener noreferrer" : null}>
          <div class="tool-search-title">${result.title}</div>
          ${result.snippet && html`<div class="tool-search-snippet">${result.snippet}</div>`}
          ${result.url && html`<div class="tool-search-url">${result.url}</div>`}
        </${Tag}>`;
      })}
    </div>`;
    default: return null;
  }
}

function toolImageUrl(conversationId, messageId, detailIndex, image) {
  return `/api/tool-images/${encodeURIComponent(conversationId)}/${encodeURIComponent(messageId)}/${detailIndex}/${image.index}`;
}

function ToolImage({ src, image, detail, full = false, onClick, onState }) {
  const [loadState, setState] = useState("loading");
  const state = image.unavailable ? "failed" : loadState;
  const settle = next => { setState(next); onState?.(next); };
  const aspect = image.width > 0 && image.height > 0 ? Math.max(0.55, Math.min(2.2, image.width / image.height)) : 1;
  const Tag = full ? "div" : "button";
  return html`<${Tag} class=${`tool-image ${detail.squareCrop && !full ? "square" : ""} ${full ? "full" : ""}`}
    type=${full ? null : "button"} disabled=${full ? null : state !== "loaded"}
    onClick=${state === "loaded" ? onClick : null}
    aria-label=${full ? null : detail.imageLabel} data-state=${state} style=${{ aspectRatio: String(aspect) }}>
    ${!image.unavailable && html`<img src=${src} alt=${detail.imageLabel} loading=${full ? "eager" : "lazy"} decoding="async"
      onLoad=${() => settle("loaded")} onError=${() => settle("failed")} />`}
    <span class="tool-image-overlay loading" aria-hidden=${state !== "loading"}>
      <${Spinner} size=${28} stroke=${2} label=${detail.imageLabel} color="inherit" />
    </span>
    <span class="tool-image-overlay failed" aria-hidden=${state !== "failed"}>
      <span class="tool-image-failed" role="img" aria-label=${detail.imageFailedLabel}>${icon(ICON_BROKEN_IMAGE)}</span>
    </span>
  </${Tag}>`;
}

function ToolMediaPreview({ detail, urls, initialIndex, onClose }) {
  const [index, setIndex] = useState(initialIndex);
  return html`<${AttachmentViewer} viewer=${{ index, items: urls.map((url, i) => ({
    url, type: "image", kind: "source", name: detail.imageLabel, index: i,
  })) }} onNavigate=${setIndex} onClose=${onClose} />`;
}

function ToolDetail({ item, conversationId, messageId }) {
  const detail = item.toolDetail;
  const [preview, setPreview] = useState(null);
  const [loads, setLoads] = useState({});
  const urls = detail.images.map((image) => toolImageUrl(conversationId, messageId, item.detailIndex, image));
  const previewIdentity = detail.images.map((image, index) => `${urls[index]}:${!!image.unavailable}`).join("\n");
  useEffect(() => { setPreview(null); }, [previewIdentity]);
  const mediaIndices = detail.images.map((image, index) => !image.unavailable && loads[urls[index]] === "loaded" ? index : null)
    .filter(index => index != null);
  const mediaUrls = mediaIndices.map(index => urls[index]);
  const previewIndex = mediaIndices.indexOf(preview);
  return html`<div class=${`tool-detail ${detail.kind === "WEB_SEARCH" ? "search" : ""}`}>
    ${(detail.arguments || detail.codeArgument) && html`<div class="tool-arguments">
      <div class="tool-section-label">${detail.argumentsLabel}</div>
      ${detail.arguments && html`<${ToolDocument} document=${detail.arguments} />`}
      ${detail.codeArgument && html`<${ToolCodeArgument} code=${detail.codeArgument} />`}
    </div>`}
    ${detail.kind === "MCP" && html`<div class="tool-mcp-meta tool-meta-row">
      <${ToolPill} text="MCP" emphasized /><${ToolPill} text=${detail.mcpDevice} />
    </div>`}
    <div class="tool-section-label result">${detail.resultLabel}</div>
    ${detail.images.length > 0 && html`<div class="tool-images">
      ${detail.images.map((image, index) => html`<${ToolImage} key=${`${urls[index]}:${!!image.unavailable}`} src=${urls[index]}
        image=${image} detail=${detail} onState=${state => setLoads(current => ({ ...current, [urls[index]]: state }))}
        onClick=${() => { if (!image.unavailable && loads[urls[index]] === "loaded") setPreview(index); }} />`)}
    </div>`}
    <${ToolBody} body=${detail.body} />
    ${(detail.supplemental || []).map((document, index) => html`<div class="tool-arguments" key=${index}>
      <${ToolDocument} document=${document} />
    </div>`)}
    ${previewIndex >= 0 && html`<${ToolMediaPreview} detail=${detail} urls=${mediaUrls} initialIndex=${previewIndex} onClose=${() => setPreview(null)} />`}
  </div>`;
}

export function CardIcon({ kind, toolKind }) {
  if (kind === "LOADING") return html`<${Spinner} size=${16} stroke=${2} color="inherit" />`;
  const path = kind === "TOOL"
    ? toolKind?.startsWith("TODO_") ? ICON_CHECKLIST
      : toolKind?.startsWith("SUBAGENT_") ? ICON_ACCOUNT_TREE
      : toolKind === "PHONE" ? ICON_MOBILE_HAND : ICON_BUILD
    : kind === "IMAGE" ? ICON_IMAGE : ICON_NEUROLOGY;
  return icon(path, kind === "THINKING" ? "0 0 960 960" : "0 0 24 24");
}

function liveTitle(group, strings, elapsed) {
  if (group.liveBaseMs == null || !strings) return group.title;
  const seconds = Math.floor((group.liveBaseMs + elapsed) / 1000);
  const hours = Math.floor(seconds / 3600);
  const minutes = Math.floor((seconds % 3600) / 60);
  const remaining = seconds % 60;
  const template = hours ? strings.hours : seconds >= 60 ? strings.minutes : strings.seconds;
  const args = hours ? [hours, minutes, remaining] : seconds >= 60 ? [minutes, remaining] : [seconds];
  let index = 0;
  return template.replace(/%(?:(\d+)\$)?d/g, (_, position) => args[position ? Number(position) - 1 : index++] ?? "");
}

export function useCardTitle(group, strings) {
  const [elapsed, setElapsed] = useState(0);
  useEffect(() => {
    setElapsed(0);
    if (group.liveBaseMs == null) return undefined;
    const started = Date.now();
    const timer = setInterval(() => setElapsed(Date.now() - started), 1000);
    return () => clearInterval(timer);
  }, [group.liveBaseMs]);
  return liveTitle(group, strings, elapsed);
}

/** Activate only segments whose detail projection is available. */
export function InfoItem({ item, compact = false, onClick, reduceMotion = false }) {
  const flat = item.content?.markdown?.replace(/\n/g, " ") ?? "";
  const points = [...flat];
  const text = item.type === "thought" ? item.streaming && points.length > 60 ? `\u2026${points.slice(-60).join("")}` : flat
    : item.type === "transcription" ? flat || "Image transcription is empty." : item.summary;
  const summaryNode = useRef(null);
  const summaryMotion = useRef({ state: item.toolState, animation: null, outgoing: null });
  const births = useRef(new Map());
  const previousText = useRef({ text: "", start: 0 });
  const [fading, redraw] = useState(0);
  useLayoutEffect(() => {
    const node = summaryNode.current, motion = summaryMotion.current;
    if (!node) return;
    const changed = item.type === "tool" && motion.state !== item.toolState;
    const old = previousText.current;
    const previous = [...old.text], next = [...(text ?? "")];
    let start = old.start;
    if (item.type === "thought" && item.streaming && previous.length) {
      const a = old.text.startsWith("\u2026") ? previous.slice(1) : previous;
      const b = text.startsWith("\u2026") ? next.slice(1) : next;
      let overlap = Math.min(a.length, b.length);
      while (overlap && a.slice(-overlap).join("") !== b.slice(0, overlap).join("")) overlap--;
      start += a.length - overlap;
    }
    if (changed) {
      motion.animation?.cancel();
      motion.outgoing?.remove();
      births.current.clear();
      motion.state = item.toolState;
      const outgoing = node.cloneNode(true);
      outgoing.textContent = old.text;
      outgoing.classList.add("summary-outgoing");
      outgoing.setAttribute("aria-hidden", "true");
      outgoing.inert = true;
      node.parentElement.append(outgoing);
      motion.outgoing = outgoing;
      outgoing.animate([{ opacity: 1 }, { opacity: 0 }], { duration: 280, easing: "linear" });
      motion.animation = node.animate([{ opacity: 0 }, { opacity: 1 }], { duration: 280, easing: "linear" });
      motion.animation.onfinish = () => { motion.animation = null; outgoing.remove(); motion.outgoing = null; redraw(v => v + 1); };
    }
    previousText.current = { text: text ?? "", start };
    const active = item.streaming && (item.type !== "tool" || ["calling", "running"].includes((item.toolState ?? "").toLowerCase())) && !motion.animation;
    return fadeStreamingText(node, active, reduceMotion, births.current,
      start - (item.type === "thought" && text?.startsWith("\u2026") ? 1 : 0), "summary", 42, 6, .38);
  }, [text, item.streaming, item.toolState, reduceMotion, fading]);
  useLayoutEffect(() => () => {
    summaryMotion.current.animation?.cancel();
    summaryMotion.current.outgoing?.remove();
  }, []);
  const interactive = typeof onClick === "function";
  const Tag = interactive ? "button" : "div";
  return html`
    <${Tag} class=${`${compact ? "info-item compact-item" : "info-item timeline-item"} ${interactive ? "sheet-item" : ""}`}
      type=${interactive ? "button" : null} data-type=${item.type}
      onClick=${onClick}>
      ${!compact && html`<span class="info-item-icon">
        <${CardIcon} kind=${item.type === "tool" ? "TOOL" : item.type === "transcription" ? "IMAGE" : "THINKING"} toolKind=${item.toolDetail?.kind} />
      </span>`}
      <div class="info-item-text">
        <span class="info-item-title">${item.title}</span>
        ${text && html`<span class="info-summary-stack"><span class="info-item-summary" ref=${summaryNode}>${text}</span></span>`}
      </div>
      ${!compact && interactive && html`<span class="info-item-arrow">${icon(ICON_CHEVRON_RIGHT)}</span>`}
    </${Tag}>`;
}

export function DetailSheet({ group, items, page, detailIndex, selectedItem, conversationId, messageId, display, wrap, onSelectItem, onBack, onClose, title: suppliedTitle, headerTrailing, children, focusReturn, settings = false, closing: closeRequested = false }) {
  const reduceMotion = !!display?.reduceMotion;
  const [anchor, setAnchor] = useState({ fraction: .45 });
  const [settingsHeight, setSettingsHeight] = useState(0);
  const fullFraction = settings ? Math.min(1, settingsHeight / innerHeight) : .94;
  const partialFraction = settings ? Math.min(.5, fullFraction) : .45;
  const expanded = anchor.fraction === .94 || (settings && fullFraction <= .5);
  const [closing, setClosing] = useState(false);
  useLayoutEffect(() => { if (closeRequested) setClosing(true); }, [closeRequested]);
  const motion = useRef({ fraction: 0, fractionVelocity: 0, cancel: null });
  const layer = useRef(null);
  const backdrop = useRef(null);
  useLayoutEffect(() => {
    const node = layer.current;
    node.showModal();
    return () => node.close();
  }, []);
  const dismiss = useRef(onClose);
  dismiss.current = onClose;
  function setExpanded(value) { setAnchor({ fraction: value ? .94 : .45 }); }
  function close() { setClosing(true); }
  function draw(value) {
    if (sheet.current) {
      if (settings) {
        sheet.current.style.height = `${settingsHeight}px`;
        sheet.current.style.transform = `translate(-50%, ${Math.max(0, settingsHeight - value.fraction * innerHeight)}px)`;
      } else sheet.current.style.height = `${Math.max(0, value.fraction) * 100}dvh`;
    }
    if (backdrop.current) backdrop.current.style.backgroundColor = `rgb(0 0 0 / ${Math.max(0, settings ? Math.min(1, value.fraction / (fullFraction || 1)) : value.fraction) * .32})`;
  }
  const closeButton = useRef(null);
  const restoreFocus = useRef(null);
  const sheet = useRef(null);
  const contentBody = useRef(null);
  useEffect(() => attachScrollbar(sheet.current.querySelector(".detail-sheet-content")), []);
  useLayoutEffect(() => {
    if (!settings) return;
    const measure = () => setSettingsHeight(Math.min(innerHeight,
      sheet.current.querySelector(".detail-sheet-handle").offsetHeight + contentBody.current.offsetHeight));
    const observer = new ResizeObserver(measure);
    observer.observe(contentBody.current);
    addEventListener("resize", measure);
    measure();
    return () => { observer.disconnect(); removeEventListener("resize", measure); };
  }, [settings]);
  const dragStart = useRef(null);
  const suppressHandleClick = useRef(false);
  const backAction = useRef(null);
  backAction.current = onBack ?? close;
  const groupTitle = useCardTitle(group ?? { title: "", liveBaseMs: null }, display?.liveThinking);
  const title = suppliedTitle ?? (page === "detail" ? selectedItem?.title : groupTitle);
  // SegmentDetailSheet slides pages only when the reader moves between its list and detail pages;
  // the page the sheet opens on is composed directly, without a slide.
  const firstPage = useRef(page);
  const navigated = useRef(false);
  if (page !== firstPage.current) navigated.current = true;
  useLayoutEffect(() => {
    if (settings && settingsHeight > 0 && fullFraction <= .5) {
      setAnchor(current => current.fraction === .94 ? current : { fraction: .94 });
    }
  }, [settings, settingsHeight]);
  useLayoutEffect(() => {
    const value = motion.current;
    const target = closing ? 0 : expanded ? fullFraction : partialFraction;
    if (reduceMotion) {
      value.fraction = target;
      value.fractionVelocity = 0;
      draw(value);
      if (closing) dismiss.current();
      return;
    }
    value.cancel = runSpring(value, {
      fraction: { target, stiffness: settings ? closing ? 3800 : 700 : 350, damping: settings && closing ? 1 : .9, threshold: .001 },
    }, draw, () => { if (closing) dismiss.current(); });
    return () => { value.cancel?.(); value.cancel = null; };
  }, [anchor, closing, reduceMotion, settingsHeight]);
  useEffect(() => {
    restoreFocus.current = focusReturn?.current ?? document.activeElement;
    closeButton.current?.focus();
    if (settings) sheet.current?.querySelector(".detail-sheet-handle")?.focus();
    const onKey = (event) => {
      if (event.target.closest?.(".tool-media-viewer")) return;
      if (event.key === "Escape") {
        event.preventDefault();
        event.stopPropagation();
        backAction.current();
      } else if (event.key === "Tab") {
        const controls = [...sheet.current?.querySelectorAll("button:not([disabled]), a[href], input:not([disabled]), select:not([disabled]), textarea:not([disabled])") ?? []]
          .filter(node => getComputedStyle(node).visibility !== "hidden" && node.getClientRects().length);
        if (!controls.length) return;
        const target = event.shiftKey ? controls.at(-1) : controls[0];
        const atEdge = event.shiftKey ? document.activeElement === controls[0]
          : document.activeElement === controls.at(-1);
        if (atEdge || !sheet.current?.contains(document.activeElement)) {
          event.preventDefault();
          target.focus();
        }
      }
    };
    document.addEventListener("keydown", onKey, true);
    return () => {
      document.removeEventListener("keydown", onKey, true);
      if (restoreFocus.current?.isConnected) restoreFocus.current.focus();
    };
  }, []);
  useEffect(() => {
    sheet.current?.querySelector(".detail-sheet-content")?.scrollTo(0, 0);
  }, [page, detailIndex]);
  function beginDrag(event) {
    if (closing) return;
    motion.current.cancel?.();
    motion.current.cancel = null;
    motion.current.fractionVelocity = 0;
    dragStart.current = { y: event.clientY, fraction: motion.current.fraction };
    event.currentTarget.setPointerCapture?.(event.pointerId);
  }
  function moveDrag(event) {
    if (!dragStart.current) return;
    motion.current.fraction = Math.max(0, Math.min(fullFraction,
      dragStart.current.fraction + (dragStart.current.y - event.clientY) / innerHeight));
    draw(motion.current);
  }
  function endDrag(event) {
    if (dragStart.current == null) return;
    const delta = event.clientY - dragStart.current.y;
    dragStart.current = null;
    if (Math.abs(delta) > 24) {
      suppressHandleClick.current = true;
      if (delta > 0 && motion.current.fraction <= partialFraction) close();
      else setExpanded(delta < 0 && motion.current.fraction > partialFraction);
    } else {
      setAnchor({ fraction: anchor.fraction });
    }
  }
  function onWheel(event) {
    if (settings && fullFraction <= .5) return;
    const content = sheet.current?.querySelector(".detail-sheet-content");
    if (event.deltaY < 0 && content?.scrollTop === 0 && expanded) {
      event.preventDefault(); setExpanded(false);
    } else if (event.deltaY > 0 && !expanded) {
      event.preventDefault(); setExpanded(true);
    }
  }
  return html`
    <dialog class="detail-sheet-layer" ref=${layer} data-closing=${closing}
      onCancel=${event => { event.preventDefault(); backAction.current(); }}
      onPointerDown=${event => event.stopPropagation()}>
      <div class="detail-sheet-backdrop" ref=${backdrop} aria-hidden="true" onClick=${close}></div>
      <section class=${`detail-sheet ${expanded ? "expanded" : ""}${settings ? " settings-sheet" : ""}`} role="dialog" aria-modal="true"
        aria-label=${title || "Message details"} ref=${sheet} inert=${closing}>
        <button class="detail-sheet-handle" type="button"
          aria-label=${expanded ? "Collapse details" : "Expand details"}
          onPointerDown=${beginDrag} onPointerMove=${moveDrag} onPointerUp=${endDrag}
          onPointerCancel=${() => { dragStart.current = null; setAnchor({ fraction: anchor.fraction }); }}
          onClick=${() => {
            if (suppressHandleClick.current) suppressHandleClick.current = false;
            else if (settings && expanded) close();
            else setExpanded(!expanded);
          }}>
          <span></span>
        </button>
        ${!settings && html`<header class="detail-sheet-header">
          ${page === "detail" && onBack && html`<button class="detail-sheet-icon detail-sheet-back" type="button" aria-label="Back" onClick=${onBack}>
            ${icon(SHEET_BACK_PATH)}
          </button>`}
          <h2>${title || "Message details"}</h2>
          ${headerTrailing != null && html`<span class="detail-sheet-header-trailing">${headerTrailing}</span>`}
          <button class="detail-sheet-icon" type="button" aria-label="Close" ref=${closeButton} onClick=${close}>
            ${icon(SHEET_CLOSE_PATH)}
          </button>
        </header>`}
        <div class="detail-sheet-content" onWheel=${onWheel}>
          ${settings ? html`<div ref=${contentBody}>${children}</div>` : children ?? html`<div class=${`detail-sheet-page ${page === "list" ? "list-page" : "detail-page"}${navigated.current ? "" : " initial"}`} key=${page}>
            ${page === "list" ? html`
              <div class="detail-sheet-list">
                ${items.map((item, index) => html`
                  <div class=${`detail-sheet-list-row ${index === 0 ? "first" : index === items.length - 1 ? "last" : "middle"}`} key=${item.detailIndex}>
                    <${InfoItem} item=${item} reduceMotion=${reduceMotion}
                      onClick=${canOpenSheetItem(item) ? () => onSelectItem(item.detailIndex) : undefined} />
                  </div>`)}
              </div>` : selectedItem?.type === "tool" ? html`
                <${ToolDetail} key=${detailIndex} item=${selectedItem} conversationId=${conversationId} messageId=${messageId} />`
              : selectedItem && html`
              <div class=${`detail-sheet-markdown ${selectedItem.streaming ? "streaming" : ""}`}>
                ${selectedItem.type === "transcription" && !selectedItem.content?.markdown
                  ? html`<p class="detail-sheet-empty">Image transcription is empty.</p>`
                  : html`<${Markdown} text=${selectedItem.content} variant="thought" wrap=${wrap}
                      streaming=${selectedItem.streaming} reduceMotion=${reduceMotion} />`}
              </div>`}
          </div>`}
        </div>
      </section>
    </dialog>`;
}
