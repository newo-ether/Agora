import { useLayoutEffect, useRef, useState } from "./vendor/preact-hooks.mjs";
import { html, useDialog, runSpring } from "./html.js";
import "./vendor/panzoom.min.js";
import { Spinner } from "./material/progress.js";
import { sliderStyle } from "./material/slider.js";
import { t } from "./i18n.js";
import { icon, ICON_CLOSE, ICON_VIDEO, ICON_ERROR, ICON_BROKEN_IMAGE, ICON_CHEVRON_RIGHT } from "./icons.js";
import { sync } from "./sync.js";
import { Markdown } from "./markdown.js";

export function AttachmentTile({ attachment: a, editable, onPreview }) {
  const source = a.state === "READY" && !a.unavailable && a.storage === "APP_PRIVATE"
    ? a.type === "image" ? sync.attachmentUrl(a.id, "source") : a.type === "video" && a.framePreviewCount ? sync.attachmentUrl(a.id, "frame") : null : null;
  const decoder = useRef(null);
  const host = useRef(null);
  const delay = useRef({ timer: null, source: null });
  const motion = useRef({ serial: 0, key: null, animations: new Map() });
  const [layers, setLayers] = useState([{ id: 0, presentation: "initial" }]);
  const [loadedSource, setLoadedSource] = useState(null);
  const [failedSource, setFailedSource] = useState(null);
  const decoded = !!source && loadedSource === source;
  const failed = !!source && failedSource === source;
  const [loading, setLoading] = useState(false);
  const blocked = a.unavailable || a.storage !== "APP_PRIVATE";
  const presentation = blocked ? "unavailable" : a.state === "PROCESSING" ? "import-loading"
    : a.state === "FAILED" ? "import-failed" : a.type === "file" || a.type === "pdf" ? "file"
    : a.type === "video" && !source ? "video" : failed ? "media-error" : decoded ? "media-success" : "media-loading";
  const busy = presentation === "import-loading" || presentation === "media-loading";
  const retry = !blocked && (presentation === "import-failed" || presentation === "media-error");
  const showLoading = busy && (a.type !== "image" || loading);
  useLayoutEffect(() => { setLoadedSource(null); setFailedSource(null); }, [source]);
  useLayoutEffect(() => {
    const current = delay.current;
    const replaced = current.source && current.source !== source;
    current.source = source;
    if (!busy || a.type !== "image" || replaced) {
      clearTimeout(current.timer);
      current.timer = null;
      setLoading(false);
    }
    if (busy && a.type === "image" && (!loading || replaced) && !current.timer) {
      current.timer = setTimeout(() => { current.timer = null; setLoading(true); }, 200);
    }
  });
  useLayoutEffect(() => {
    const current = motion.current;
    const key = JSON.stringify([presentation, showLoading, source, a.name, a.type]);
    if (current.key === key) return;
    current.key = key;
    setLayers(previous => [...previous, { id: ++current.serial, presentation, showLoading,
      source, name: a.name, type: a.type }]);
  }, [presentation, showLoading, source, a.name, a.type]);
  useLayoutEffect(() => {
    const current = motion.current;
    for (const node of host.current.querySelectorAll("[data-attachment-layer]")) {
      const id = Number(node.dataset.attachmentLayer);
      const visible = id === current.serial;
      const previous = current.animations.get(node);
      if (previous?.visible === visible) continue;
      const opacity = getComputedStyle(node).opacity;
      previous?.animation.cancel();
      node.style.opacity = visible ? "1" : "0";
      const animation = node.animate([{ opacity }, { opacity: visible ? 1 : 0 }],
        { duration: 200, easing: "cubic-bezier(0.4, 0, 0.2, 1)", fill: "both" });
      current.animations.set(node, { animation, visible });
      animation.onfinish = () => {
        if (current.animations.get(node)?.animation !== animation) return;
        animation.cancel();
        if (!visible) {
          current.animations.delete(node);
          setLayers(previousLayers => previousLayers.filter(layer => layer.id !== id));
        }
      };
    }
  }, [layers]);
  useLayoutEffect(() => () => {
    clearTimeout(delay.current.timer);
    motion.current.animations.forEach(({ animation }) => animation.cancel());
    motion.current.animations.clear();
  }, []);
  const canPreview = !blocked && a.state === "READY" && (a.type === "file" ? a.text != null : a.type === "pdf" ? a.pagePreviewCount > 0 : !source || decoded);
  return html`<div class="attachment-tile" ref=${host} data-state=${presentation}>
    ${source && html`<img key=${source} class="attachment-decode" ref=${decoder} src=${source} alt=""
      onLoad=${event => { if (decoder.current === event.currentTarget && event.currentTarget.isConnected) setLoadedSource(source); }}
      onError=${event => { if (decoder.current === event.currentTarget && event.currentTarget.isConnected) setFailedSource(source); }} />`}
    <div class="attachment-layers" aria-hidden="true">
      ${layers.map(layer => {
        const importing = layer.presentation === "import-loading";
        const error = layer.presentation === "import-failed" || layer.presentation === "media-error";
        const file = importing || error || layer.presentation === "file" || layer.presentation === "unavailable";
        const label = layer.type === "pdf" ? "PDF" : ((layer.name?.split(".").slice(1).at(-1) || layer.type || "FILE").toUpperCase().slice(0, 4));
        return html`<div key=${layer.id} class="attachment-layer" data-attachment-layer=${layer.id} data-presentation=${layer.presentation}
          style=${{ opacity: 0 }}>
          <div class="attachment-visual">
            ${file ? layer.type === "video" && layer.presentation !== "unavailable" ? html`<span class="attachment-status">${icon(ICON_VIDEO)}</span>`
              : html`<span class=${`attachment-status ${layer.type === "pdf" ? "pdf-placeholder" : "file-placeholder"}`}><small>${label}</small></span>`
              : layer.presentation === "video" ? html`<span class="attachment-status">${icon(ICON_VIDEO)}</span>` : null}
            ${layer.presentation === "media-success" && html`<img src=${layer.source} alt="" />
              ${layer.type === "video" && html`<span class="attachment-play">${icon("M8 5v14l11-7z")}</span>`}`}
            ${(importing || error) && html`<span class=${`attachment-status ${error ? "attachment-error" : "attachment-progress"}`}>
              ${error ? icon(layer.type === "image" ? ICON_BROKEN_IMAGE : ICON_ERROR) : layer.showLoading ? html`<${Spinner} size=${24} stroke=${3} />` : null}
            </span>`}
            ${layer.presentation === "media-loading" && layer.showLoading && html`<span class="attachment-status"><${Spinner} size=${24} stroke=${3} /></span>`}
          </div>
          <div class="attachment-caption">
            ${(layer.presentation === "unavailable" || layer.type === "file" || layer.type === "pdf") && layer.name && html`<span class="attachment-filename">${layer.name}</span>`}
            ${layer.presentation === "unavailable" && html`<span class="attachment-unavailable">${t.attachmentUnavailable}</span>`}
          </div>
        </div>`;
      })}
    </div>
    <button class="attachment-preview" type="button" title=${a.name || t.attachments} aria-label=${retry ? t.retry : a.name || t.attachments}
      disabled=${retry ? !editable : !canPreview} onClick=${() => retry ? sync.attachmentCommand("attachment_retry", a.id) : onPreview()}></button>
    <button class="attachment-remove" type="button" title=${t.remove} aria-label=${t.remove} disabled=${!editable}
      onClick=${() => sync.attachmentCommand("attachment_remove", a.id)}>${icon(ICON_CLOSE)}</button>
  </div>`;
}

export function AttachmentEditor({ attachment: a, editable, onPreview }) {
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
  const exitAction = useRef(null);
  const target = sync.attachmentTarget();
  const dismiss = useDialog(dialog, () => {
    if (exitAction.current && target) sync.attachmentCommand(exitAction.current.type, a.id,
      exitAction.current.config, target);
  });
  function finish(type, config = {}) {
    if (!editable) return;
    exitAction.current = { type, config };
    dismiss();
  }
  function cancel() { finish("attachment_remove"); }
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
      <button class="filled" type="button" disabled=${!editable || !valid} onClick=${() => finish(pdf ? "attachment_pdf" : "attachment_video",
        pdf ? { pages } : { frameCount: frames, intervalMs: sliceMs })}>${pdf ? t.sendPages(pages.length) : t.extractFrames(frames)}</button></footer>
    </section></dialog>`;
}

export function AttachmentViewer({ viewer, onNavigate, onClose: onClosed }) {
  const dialog = useRef(null);
  const onClose = useDialog(dialog, onClosed);
  const index = viewer.index || 0;
  const viewport = useRef(null);
  const image = useRef(null);
  const zoom = useRef(null);
  const file = viewer.file;
  const item = viewer.items?.[index];
  const source = item && (item.url ?? sync.attachmentUrl(item.id, item.kind, item.index));
  const [loadedSource, setLoadedSource] = useState(null);
  const [failedSource, setFailedSource] = useState(null);
  const loaded = !!source && loadedSource === source;
  const failed = !!source && failedSource === source;
  useLayoutEffect(() => {
    const host = viewport.current, node = image.current;
    if (!host || !node || !loaded) return;
    const panzoom = globalThis.Panzoom(node, { minScale: 1, maxScale: 10, contain: "outside",
      canvas: true, panOnlyWhenZoomed: true, pinchAndPan: true, cursor: "grab" });
    const motion = { scale: 1, cancel: null };
    zoom.current = { panzoom, motion };
    const controller = new AbortController();
    const cancel = () => { motion.cancel?.(); motion.cancel = null; };
    const wheel = event => { cancel(); panzoom.zoomWithWheel(event); };
    host.addEventListener("wheel", wheel, { passive: false, signal: controller.signal });
    node.addEventListener("panzoomstart", cancel, { signal: controller.signal });
    const resize = new ResizeObserver(() => { cancel(); panzoom.pan(0, 0, { force: true }); });
    resize.observe(host);
    return () => {
      cancel(); controller.abort(); resize.disconnect(); panzoom.destroy(); panzoom.resetStyle();
      zoom.current = null;
    };
  }, [source, loaded]);
  function toggleZoom(event) {
    const current = zoom.current;
    if (!current) return;
    current.motion.cancel?.();
    const scale = current.panzoom.getScale();
    const target = scale <= 1.05 ? 3 : 1;
    const point = { clientX: event.clientX, clientY: event.clientY };
    if (dialog.current.closest(".shell")?.dataset.reduceMotion === "true") {
      current.panzoom.zoomToPoint(target, point); return;
    }
    current.motion.scale = scale;
    current.motion.scaleVelocity = 0;
    current.motion.cancel = runSpring(current.motion,
      { scale: { target, stiffness: 400, damping: 1, threshold: .001 } },
      value => current.panzoom.zoomToPoint(value.scale, point),
      () => { current.motion.cancel = null; });
  }
  function navigate(next) { onNavigate(next); }
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
      : html`<div key=${source} class="tool-media-scroll image-viewport" ref=${viewport} onDblClick=${toggleZoom}><img ref=${image} class="attachment-full-image" src=${source}
        alt=${item.name || t.attachments} draggable="false" onLoad=${() => setLoadedSource(source)} onError=${() => setFailedSource(source)}
        style=${{ opacity: loaded ? 1 : 0 }} /></div>`}
    ${!file && html`<div class="attachment-viewport-status" style=${{ opacity: !loaded && !failed ? 1 : 0 }}><${Spinner} size=${18} stroke=${3} label=${t.attachments} color="inherit" /></div>
      <div class="attachment-viewport-status" style=${{ opacity: failed ? 1 : 0 }} role=${failed ? "alert" : null}>${icon(item.type === "image" || item.kind === "page" ? ICON_BROKEN_IMAGE : ICON_ERROR)}</div>`}
    <div class="tool-media-controls"><span class="tool-media-count">${file?.name || `${index + 1} / ${viewer.items.length}`}</span>
      <button class="detail-sheet-icon" type="button" title=${t.close} aria-label=${t.close} onClick=${onClose}>${icon(ICON_CLOSE)}</button></div>
    ${!file && viewer.items.length > 1 && html`<button class="tool-media-previous detail-sheet-icon" type="button" aria-label=${t.previous} disabled=${index === 0} onClick=${() => navigate(index - 1)}>${icon(ICON_CHEVRON_RIGHT)}</button>
      <button class="tool-media-next detail-sheet-icon" type="button" aria-label=${t.next} disabled=${index === viewer.items.length - 1} onClick=${() => navigate(index + 1)}>${icon(ICON_CHEVRON_RIGHT)}</button>`}
  </dialog>`;
}
