// Shared message-detail and Composer settings sheet presentation.
import { useEffect, useLayoutEffect, useRef, useState } from "./vendor/preact-hooks.mjs";
import { html, runSpring } from "./html.js";
import { Spinner } from "./material/progress.js";
import { Markdown } from "./markdown.js";
import { icon, ICON_BUILD, ICON_CHEVRON_RIGHT, ICON_IMAGE, ICON_NEUROLOGY } from "./icons.js";
const SHEET_BACK_PATH = "M20 11H7.83l5.59-5.59L12 4l-8 8 8 8 1.41-1.41L7.83 13H20v-2z";
const SHEET_CLOSE_PATH = "M18.3 5.71 12 12l6.3 6.29-1.41 1.42L10.59 13.41 4.29 19.71 2.88 18.3 9.17 12 2.88 5.7 4.29 4.29 10.59 10.59 16.89 4.29z";
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
      const nested = value && value.type !== "scalar";
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

function ToolBody({ body }) {
  switch (body.type) {
    case "active":
    case "failed":
      return html`<div class=${body.type === "active" ? "tool-active" : "tool-terminal"}>${body.text}</div>
        ${body.output && html`<div class="tool-gap"><${ToolOutput} text=${body.output} /></div>`}`;
    case "stopped": return html`<div class="tool-terminal">${body.text}</div>`;
    case "muted": return html`<div class="tool-muted">${body.text}</div>`;
    case "documents": return body.values.map((document, index) => html`
      <div class="tool-result-document" key=${index}><${ToolDocument} document=${document} /></div>`);
    case "shell": return html`<div class="tool-meta-row">
        <${ToolPill} text=${body.status} emphasized /><${ToolPill} text=${body.device} />
      </div>
      ${body.error && html`<div class="tool-terminal tool-gap">${body.error}</div>`}
      <div class="tool-gap"><${ToolOutput} text=${body.output} /></div>`;
    case "paths": return html`<div class="tool-paths">
      ${body.values.map((path, index) => html`<div class="tool-indexed-line" key=${index}>
        <span class="tool-index">${index + 1}</span><span class="tool-code">${path}</span>
      </div>`)}
    </div>`;
    case "grep": return html`<div class="tool-grep">
      ${body.groups.map((group, index) => html`<div key=${index}>
        <div class="tool-grep-path tool-code">${group.path}</div>
        <div class="tool-matches">${group.matches.map((match, i) => html`<div class="tool-match" key=${i}>
          <span class="tool-line-number">${match.line ?? "\u2014"}</span><span class="tool-code">${match.content}</span>
        </div>`)}</div>
      </div>`)}
    </div>`;
    case "file": return html`
      ${(body.path || body.lineCount) && html`<div class="tool-meta-row tool-file-meta">
        <${ToolPill} text=${body.path} /><${ToolPill} text=${body.lineCount} />
      </div>`}
      ${body.content ? html`<${ToolOutput} text=${body.content} />` : html`<div class="tool-muted">${body.emptyText}</div>`}
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
  return `/api/tool-images/${encodeURIComponent(conversationId)}/${encodeURIComponent(messageId)}/${detailIndex}/${image.index}?v=${encodeURIComponent(image.version)}`;
}

function ToolImage({ src, image, detail, full = false, onClick }) {
  const [state, setState] = useState("loading");
  const aspect = image.width > 0 && image.height > 0 ? Math.max(0.55, Math.min(2.2, image.width / image.height)) : 1;
  const Tag = full ? "div" : "button";
  return html`<${Tag} class=${`tool-image ${detail.squareCrop && !full ? "square" : ""} ${full ? "full" : ""}`}
    type=${full ? null : "button"} disabled=${full ? null : state !== "loaded"} onClick=${onClick}
    aria-label=${full ? null : detail.imageLabel} data-state=${state} style=${{ aspectRatio: String(aspect) }}>
    <img src=${src} alt=${detail.imageLabel} loading=${full ? "eager" : "lazy"} decoding="async"
      onLoad=${() => setState("loaded")} onError=${() => setState("failed")} />
    <span class="tool-image-overlay loading" aria-hidden=${state !== "loading"}>
      <${Spinner} size=${28} stroke=${2} label=${detail.imageLabel} color="inherit" />
    </span>
    <span class="tool-image-overlay failed" aria-hidden=${state !== "failed"}>
      <span class="tool-image-failed" role="img" aria-label=${detail.imageFailedLabel}>${icon(ICON_IMAGE)}</span>
    </span>
  </${Tag}>`;
}

function ToolMediaPreview({ detail, urls, initialIndex, onClose }) {
  const dialog = useRef(null);
  const [index, setIndex] = useState(initialIndex);
  const [scale, setScale] = useState(1);
  useEffect(() => {
    dialog.current.showModal();
    return () => dialog.current?.close();
  }, []);
  function navigate(next) { setIndex(next); setScale(1); }
  return html`<dialog class="tool-media-viewer" ref=${dialog} aria-label=${detail.imageLabel}
    onCancel=${(event) => { event.preventDefault(); onClose(); }}
    onKeyDown=${(event) => {
      event.stopPropagation();
      if (event.key === "ArrowLeft" || event.key === "ArrowRight") {
        event.preventDefault();
        if (event.key === "ArrowLeft" && index > 0) navigate(index - 1);
        if (event.key === "ArrowRight" && index < urls.length - 1) navigate(index + 1);
      }
    }}>
    <div class="tool-media-scroll" onDblClick=${() => setScale((value) => value === 1 ? 3 : 1)}>
      <div class="tool-media-frame" style=${{ width: `${scale * 100}%`, height: `${scale * 100}%` }}>
        <${ToolImage} key=${urls[index]} src=${urls[index]} image=${detail.images[index]} detail=${detail} full />
      </div>
    </div>
    <div class="tool-media-controls">
      ${urls.length > 1 && html`<span class="tool-media-count">${index + 1} / ${urls.length}</span>`}
      <button class="detail-sheet-icon" type="button" aria-label="Close" onClick=${onClose}>${icon(SHEET_CLOSE_PATH)}</button>
    </div>
    ${urls.length > 1 && html`<button class="tool-media-previous detail-sheet-icon" type="button"
      aria-label="Previous image" disabled=${index === 0} onClick=${() => navigate(index - 1)}>${icon(SHEET_BACK_PATH)}</button>
      <button class="tool-media-next detail-sheet-icon" type="button" aria-label="Next image"
        disabled=${index === urls.length - 1} onClick=${() => navigate(index + 1)}>${icon(ICON_CHEVRON_RIGHT)}</button>`}
  </dialog>`;
}

function ToolDetail({ item, conversationId, messageId }) {
  const detail = item.toolDetail;
  const [preview, setPreview] = useState(null);
  const urls = detail.images.map((image) => toolImageUrl(conversationId, messageId, item.detailIndex, image));
  const previewVersion = urls.join("\n");
  useEffect(() => { setPreview(null); }, [previewVersion]);
  return html`<div class=${`tool-detail ${detail.kind === "WEB_SEARCH" ? "search" : ""}`}>
    ${detail.arguments && html`<div class="tool-arguments">
      <div class="tool-section-label">${detail.argumentsLabel}</div><${ToolDocument} document=${detail.arguments} />
    </div>`}
    ${detail.kind === "MCP" && html`<div class="tool-mcp-meta tool-meta-row">
      <${ToolPill} text="MCP" emphasized /><${ToolPill} text=${detail.mcpDevice} />
    </div>`}
    <div class="tool-section-label result">${detail.resultLabel}</div>
    ${detail.images.length > 0 && html`<div class="tool-images">
      ${detail.images.map((image, index) => html`<${ToolImage} key=${urls[index]} src=${urls[index]}
        image=${image} detail=${detail} onClick=${() => setPreview(index)} />`)}
    </div>`}
    <${ToolBody} body=${detail.body} />
    ${preview != null && html`<${ToolMediaPreview} detail=${detail} urls=${urls} initialIndex=${preview} onClose=${() => setPreview(null)} />`}
  </div>`;
}

export function CardIcon({ kind }) {
  if (kind === "LOADING") return html`<${Spinner} size=${16} stroke=${2} color="inherit" />`;
  const path = kind === "TOOL" ? ICON_BUILD : kind === "IMAGE" ? ICON_IMAGE : ICON_NEUROLOGY;
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
export function InfoItem({ item, compact = false, onClick }) {
  const text = item.type === "thought" ? item.content?.markdown?.replace(/\n/g, " ")
    : item.type === "transcription" ? item.content?.markdown?.replace(/\n/g, " ") || "Image transcription is empty."
      : item.summary;
  const interactive = typeof onClick === "function";
  const Tag = interactive ? "button" : "div";
  return html`
    <${Tag} class=${`${compact ? "info-item compact-item" : "info-item timeline-item"} ${interactive ? "sheet-item" : ""}`}
      type=${interactive ? "button" : null}
      onClick=${onClick}>
      ${!compact && html`<span class="info-item-icon">
        <${CardIcon} kind=${item.type === "tool" ? "TOOL" : item.type === "transcription" ? "IMAGE" : "THINKING"} />
      </span>`}
      <div class="info-item-text">
        <span class="info-item-title">${item.title}</span>
        ${text && html`<span class="info-item-summary">${text}</span>`}
      </div>
      ${!compact && interactive && html`<span class="info-item-arrow">${icon(ICON_CHEVRON_RIGHT)}</span>`}
    </${Tag}>`;
}

export function DetailSheet({ group, items, page, detailIndex, selectedItem, conversationId, messageId, display, wrap, onSelectItem, onBack, onClose, title: suppliedTitle, children, focusReturn }) {
  const reduceMotion = !!display?.reduceMotion;
  const [anchor, setAnchor] = useState({ fraction: .45 });
  const expanded = anchor.fraction === .94;
  const [closing, setClosing] = useState(false);
  const motion = useRef({ fraction: 0, fractionVelocity: 0, cancel: null });
  const backdrop = useRef(null);
  const dismiss = useRef(onClose);
  dismiss.current = onClose;
  function setExpanded(value) { setAnchor({ fraction: value ? .94 : .45 }); }
  function close() { setClosing(true); }
  function draw(value) {
    if (sheet.current) sheet.current.style.height = `${Math.max(0, value.fraction) * 100}dvh`;
    if (backdrop.current) backdrop.current.style.backgroundColor = `rgb(0 0 0 / ${Math.max(0, value.fraction) * .32})`;
  }
  const closeButton = useRef(null);
  const restoreFocus = useRef(null);
  const sheet = useRef(null);
  const dragStart = useRef(null);
  const suppressHandleClick = useRef(false);
  const backAction = useRef(null);
  backAction.current = onBack ?? close;
  const groupTitle = useCardTitle(group ?? { title: "", liveBaseMs: null }, display?.liveThinking);
  const title = suppliedTitle ?? (page === "detail" ? selectedItem?.title : groupTitle);
  useLayoutEffect(() => {
    const value = motion.current;
    const target = closing ? 0 : anchor.fraction;
    if (reduceMotion) {
      value.fraction = target;
      value.fractionVelocity = 0;
      draw(value);
      if (closing) dismiss.current();
      return;
    }
    value.cancel = runSpring(value, {
      fraction: { target, stiffness: 350, damping: .9, threshold: .001 },
    }, draw, () => { if (closing) dismiss.current(); });
    return () => { value.cancel?.(); value.cancel = null; };
  }, [anchor, closing, reduceMotion]);
  useEffect(() => {
    restoreFocus.current = focusReturn?.current ?? document.activeElement;
    closeButton.current?.focus();
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
    motion.current.fraction = Math.max(0, Math.min(.94,
      dragStart.current.fraction + (dragStart.current.y - event.clientY) / innerHeight));
    draw(motion.current);
  }
  function endDrag(event) {
    if (dragStart.current == null) return;
    const delta = event.clientY - dragStart.current.y;
    dragStart.current = null;
    if (Math.abs(delta) > 24) {
      suppressHandleClick.current = true;
      if (delta > 0 && motion.current.fraction <= .5) close();
      else setExpanded(delta < 0 && motion.current.fraction > .5);
    } else {
      setAnchor({ fraction: anchor.fraction });
    }
  }
  function onWheel(event) {
    const content = sheet.current?.querySelector(".detail-sheet-content");
    if (event.deltaY < 0 && content?.scrollTop === 0 && expanded) {
      event.preventDefault(); setExpanded(false);
    } else if (event.deltaY > 0 && !expanded) {
      event.preventDefault(); setExpanded(true);
    }
  }
  return html`
    <div class="detail-sheet-layer" data-closing=${closing} onPointerDown=${event => event.stopPropagation()}>
      <div class="detail-sheet-backdrop" ref=${backdrop} aria-hidden="true" onClick=${close}></div>
      <section class=${`detail-sheet ${expanded ? "expanded" : ""}`} role="dialog" aria-modal="true"
        aria-label=${title || "Message details"} ref=${sheet} inert=${closing}>
        <button class="detail-sheet-handle" type="button"
          aria-label=${expanded ? "Collapse details" : "Expand details"}
          onPointerDown=${beginDrag} onPointerMove=${moveDrag} onPointerUp=${endDrag}
          onPointerCancel=${() => { dragStart.current = null; setAnchor({ fraction: anchor.fraction }); }}
          onClick=${() => {
            if (suppressHandleClick.current) suppressHandleClick.current = false;
            else setExpanded(!expanded);
          }}>
          <span></span>
        </button>
        <header class="detail-sheet-header">
          ${page === "detail" && group && html`<button class="detail-sheet-icon detail-sheet-back" type="button" aria-label="Back" onClick=${onBack}>
            ${icon(SHEET_BACK_PATH)}
          </button>`}
          <h2>${title || "Message details"}</h2>
          <button class="detail-sheet-icon" type="button" aria-label="Close" ref=${closeButton} onClick=${close}>
            ${icon(SHEET_CLOSE_PATH)}
          </button>
        </header>
        <div class="detail-sheet-content" onWheel=${onWheel}>
          ${children ?? html`<div class=${`detail-sheet-page ${page === "list" ? "list-page" : "detail-page"}`} key=${page}>
            ${page === "list" ? html`
              <div class="detail-sheet-list">
                ${items.map((item, index) => html`
                  <div class=${`detail-sheet-list-row ${index === 0 ? "first" : index === items.length - 1 ? "last" : "middle"}`} key=${item.detailIndex}>
                    <${InfoItem} item=${item}
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
    </div>`;
}
