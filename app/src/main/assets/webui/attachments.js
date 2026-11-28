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
  const source = a.state === "READY" && !a.unavailable
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
  const blocked = a.unavailable;
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

// Compose's pager snap: a release faster than this (px/s) pages toward the fling.
const FLING_VELOCITY = 600;
// Height of the browser's video control bar, whose drags scrub instead of paging.
const VIDEO_CONTROLS_HEIGHT = 72;
// Pages kept mounted on each side, so a fast run of swipes lands on already decoded pages.
const MOUNTED_NEIGHBOURS = 2;
// Release velocity is measured over the last stretch of the drag, like Compose's VelocityTracker.
const VELOCITY_WINDOW_MS = 100;
// A playing video hides the close button and the counter with its own idle controls.
const CHROME_IDLE_MS = 3000;

const itemSource = item => item && (item.url ?? sync.attachmentUrl(item.id, item.kind, item.index));
const isVideo = entry => entry?.type === "video" && entry.kind === "source";

/**
 * One page of the viewer. Its element stays mounted while it is within reach of the current page,
 * so paging never refetches it; each page shows its own spinner until it decodes.
 */
function MediaPage({ entry, url, offset, current, zoom, onTap, onPlayback }) {
  const media = useRef(null);
  const [state, setState] = useState("loading");
  const video = isVideo(entry);
  useLayoutEffect(() => {
    const node = media.current;
    if (video || !current || state !== "loaded" || !node) return undefined;
    const host = node.closest(".image-viewport");
    const panzoom = globalThis.Panzoom(node, { minScale: 1, maxScale: 10, contain: "outside",
      canvas: true, panOnlyWhenZoomed: true, pinchAndPan: true, cursor: "grab" });
    const motion = { scale: 1, cancel: null };
    zoom.current = { panzoom, motion };
    const controller = new AbortController();
    const cancel = () => { motion.cancel?.(); motion.cancel = null; };
    host?.addEventListener("wheel", event => { cancel(); panzoom.zoomWithWheel(event); }, { passive: false, signal: controller.signal });
    node.addEventListener("panzoomstart", cancel, { signal: controller.signal });
    const resize = new ResizeObserver(() => { cancel(); panzoom.pan(0, 0, { force: true }); });
    if (host) resize.observe(host);
    return () => {
      cancel(); controller.abort(); resize.disconnect(); panzoom.destroy(); panzoom.resetStyle();
      if (zoom.current?.panzoom === panzoom) zoom.current = null;
    };
  }, [video, current, state]);
  // The current video plays from its start; a neighbour waits paused on its first frame.
  useLayoutEffect(() => {
    const node = media.current;
    if (!video || !node) return;
    if (current) {
      if (node.readyState >= 1) node.currentTime = 0;
      node.play?.()?.catch?.(() => {});
    } else {
      node.pause?.();
      if (node.readyState >= 1) node.currentTime = .1;
    }
  }, [video, current]);
  const playback = playing => { if (current) onPlayback(playing); };
  return html`<div class="attachment-page" data-state=${state} style=${{ transform: `translateX(${offset * 100}%)` }}>
    ${video ? html`<video ref=${media} class="attachment-full-video" src=${`${url}#t=0.1`} preload="metadata"
        playsinline controls=${current} muted=${!current} disablepictureinpicture
        onLoadedData=${() => setState("loaded")} onError=${() => setState("failed")}
        onPlay=${() => playback(true)} onPause=${() => playback(false)} onEnded=${() => playback(false)}></video>`
      : html`<img ref=${media} class="attachment-full-image" src=${url} alt=${entry.name || t.attachments}
        draggable="false" onClick=${onTap}
        onLoad=${() => setState("loaded")} onError=${() => setState("failed")} />`}
    <div class="attachment-page-status" data-visible=${state === "loading"}><${Spinner} stroke=${3} label=${t.loading} /></div>
    <div class="attachment-page-status" data-visible=${state === "failed"} role=${state === "failed" ? "alert" : null}>
      ${icon(entry.type === "image" || entry.kind === "page" ? ICON_BROKEN_IMAGE : ICON_ERROR)}</div>
  </div>`;
}

export function AttachmentViewer({ viewer, onNavigate, onClose: onClosed }) {
  const dialog = useRef(null);
  const onClose = useDialog(dialog, onClosed);
  const index = viewer.index || 0;
  const items = viewer.items || [];
  const viewport = useRef(null);
  const pager = useRef(null);
  const zoom = useRef(null);
  const file = viewer.file;
  const item = items[index];
  // `index` is the page the pager shows; `shift` is a committed page change the layout has not
  // rendered yet; `pending` is the page a running settle will commit when it lands.
  const track = useRef({ x: 0, xVelocity: 0, cancel: null, index, shift: 0, pending: 0 });
  const swipe = useRef(null);
  const video = isVideo(item);
  // The close button and counter follow the same visibility as the video controls: tapping a
  // picture toggles them, a playing video hides them after the controls' idle delay, and any
  // touch or pointer move over a video shows them again.
  const [chrome, setChrome] = useState(true);
  const chromeTimer = useRef(null);
  const playing = useRef(false);
  function showChrome() {
    clearTimeout(chromeTimer.current);
    setChrome(true);
    if (playing.current) chromeTimer.current = setTimeout(() => setChrome(false), CHROME_IDLE_MS);
  }
  function onPlayback(isPlaying) {
    playing.current = isPlaying;
    showChrome();
  }
  function toggleChrome() {
    clearTimeout(chromeTimer.current);
    setChrome(visible => !visible);
  }
  useLayoutEffect(() => () => clearTimeout(chromeTimer.current), []);
  useLayoutEffect(() => {
    // Leaving a video stops its idle timer; the next page keeps the current visibility.
    playing.current = false;
    clearTimeout(chromeTimer.current);
  }, [index]);
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
  function reducedMotion() {
    return dialog.current?.closest(".shell")?.dataset.reduceMotion === "true";
  }
  function paintTrack(x) {
    track.current.x = x;
    // The pager moves inside the clipped viewport, so both neighbours slide in instead of being
    // cut off by the viewport's own bounds.
    if (pager.current) pager.current.style.transform = `translateX(${x}px)`;
  }
  function stopTrack() {
    const cancel = track.current.cancel;
    track.current.cancel = null;
    cancel?.();
  }
  function pageWidth() {
    return viewport.current?.clientWidth || 1;
  }
  // A committed page change: the parent re-renders with the new index, and the layout effect below
  // moves the track by one page in the same frame the pages take their new offsets, so the
  // picture under the finger never jumps.
  function commit(dir) {
    const state = track.current;
    state.pending = 0;
    state.shift += dir;
    state.index += dir;
    onNavigate(state.index);
  }
  useLayoutEffect(() => {
    const state = track.current;
    state.index = index;
    if (state.shift) {
      const by = state.shift * pageWidth();
      state.shift = 0;
      if (swipe.current) swipe.current.base += by;
      paintTrack(state.x + by);
    } else if (!state.cancel && !swipe.current) {
      paintTrack(0);
    }
  }, [index]);
  // One spring owns every settle, starting from the release velocity, like the Compose pager.
  function settleTrack(target, done) {
    stopTrack();
    if (reducedMotion()) { paintTrack(target); done?.(); return; }
    track.current.cancel = runSpring(
      track.current,
      { x: { target, stiffness: 400, damping: 1, threshold: .5 } },
      state => paintTrack(state.x),
      () => { track.current.cancel = null; paintTrack(target); done?.(); },
    );
  }
  function settleTo(dir) {
    if (!dir) { track.current.pending = 0; settleTrack(0); return; }
    track.current.pending = dir;
    settleTrack(-dir * pageWidth(), () => commit(dir));
  }
  // Arrow buttons and the keyboard walk the same pager as a released swipe.
  function step(dir) {
    const state = track.current;
    if (state.cancel || swipe.current || !items[state.index + dir]) return;
    state.xVelocity = 0;
    settleTo(dir);
  }
  function startSwipe(event) {
    if (video) showChrome();
    if (event.touches.length !== 1) { swipe.current = null; return; }
    if ((zoom.current?.panzoom.getScale() ?? 1) > 1.05) { swipe.current = null; return; }
    const [touch] = event.touches;
    // The player's own control bar keeps its scrubbing drag instead of paging.
    if (video && touch.clientY > (viewport.current?.getBoundingClientRect().bottom ?? Infinity) - VIDEO_CONTROLS_HEIGHT) {
      swipe.current = null; return;
    }
    const state = track.current;
    swipe.current = { x: touch.clientX, y: touch.clientY, axis: null, base: state.x, samples: [] };
    // A finger on a moving pager catches it where it is, like Compose's pager: a settle that was
    // heading for the next page commits it first, so the gesture continues from that page.
    if (state.cancel) {
      stopTrack();
      swipe.current.axis = "horizontal";
      if (state.pending) commit(state.pending);
    }
    state.xVelocity = 0;
  }
  function moveSwipe(event) {
    const gesture = swipe.current;
    if (!gesture || event.touches.length !== 1) { swipe.current = null; return; }
    const [touch] = event.touches;
    if (gesture.axis == null) {
      const dx = touch.clientX - gesture.x, dy = touch.clientY - gesture.y;
      if (Math.abs(dx) < 8) return;
      if (Math.abs(dx) < Math.abs(dy) * 1.2) { gesture.axis = "vertical"; return; }
      // The drag starts at the touch slop, so the page does not jump by the slop distance.
      gesture.axis = "horizontal";
      gesture.x = touch.clientX;
    }
    if (gesture.axis !== "horizontal") return;
    if (event.cancelable) event.preventDefault();
    const now = performance.now();
    gesture.samples.push({ time: now, x: touch.clientX });
    while (gesture.samples.length > 2 && now - gesture.samples[0].time > VELOCITY_WINDOW_MS) gesture.samples.shift();
    const width = pageWidth(), at = track.current.index;
    let x = gesture.base + touch.clientX - gesture.x;
    // Past the first or last page the pager drags against resistance instead of showing nothing.
    if ((x > 0 && !items[at - 1]) || (x < 0 && !items[at + 1])) x *= .3;
    paintTrack(Math.max(-width, Math.min(width, x)));
  }
  function releaseVelocity(samples) {
    const first = samples[0], last = samples.at(-1);
    if (!first || first === last || performance.now() - last.time > VELOCITY_WINDOW_MS) return 0;
    return (last.x - first.x) / Math.max(1, last.time - first.time) * 1000;
  }
  function endSwipe() {
    const gesture = swipe.current;
    swipe.current = null;
    const state = track.current;
    if (!gesture || gesture.axis !== "horizontal") { if (!state.cancel && state.x !== 0) settleTo(0); return; }
    const velocity = releaseVelocity(gesture.samples);
    state.xVelocity = velocity;
    // Fraction of a page the pager has moved: positive toward the next page.
    const moved = -state.x / pageWidth();
    let dir;
    if (Math.abs(velocity) >= FLING_VELOCITY) {
      // Compose's snap never passes the page the drag started from: a fling back from a half
      // dragged neighbour returns to the current page instead of reversing onto the other side.
      const forward = velocity < 0;
      dir = forward ? (moved < 0 ? 0 : 1) : (moved > 0 ? 0 : -1);
    } else {
      dir = Math.abs(moved) > .5 ? Math.sign(moved) : 0;
    }
    if (dir && !items[state.index + dir]) dir = 0;
    settleTo(dir);
  }
  const mounted = [];
  for (let at = Math.max(0, index - MOUNTED_NEIGHBOURS); at <= Math.min(items.length - 1, index + MOUNTED_NEIGHBOURS); at++) {
    mounted.push(at);
  }
  return html`<dialog ref=${dialog} class=${`tool-media-viewer attachment-viewer ${file ? "text-file-viewer" : ""}`} aria-label=${file?.name || item?.name || t.attachments}
    onCancel=${event => { event.preventDefault(); onClose(); }} onKeyDown=${event => {
      event.stopPropagation();
      if (event.key === "Escape") { event.preventDefault(); onClose(); return; }
      if (event.key === "ArrowLeft" && index > 0) step(-1);
      if (event.key === "ArrowRight" && index < (viewer.items?.length || 0) - 1) step(1);
    }}>
    ${file ? html`<div class="text-file-content">${/\.(md|markdown)$/i.test(file.name || "") ? html`<${Markdown} text=${{ markdown: file.text, math: [] }} />` : html`<pre>${file.text}</pre>`}</div>`
      : html`<div class="tool-media-scroll image-viewport" ref=${viewport}
        onDblClick=${toggleZoom} onTouchStart=${startSwipe} onTouchMove=${moveSwipe} onTouchEnd=${endSwipe} onTouchCancel=${endSwipe}>
        <div class="attachment-pager" ref=${pager}>
        ${mounted.map(at => {
          const entry = items[at], url = itemSource(entry);
          // Keyed by position and source, so a page keeps its element while the window slides.
          return html`<${MediaPage} key=${`${at}:${url}`} entry=${entry} url=${url} offset=${at - index}
            current=${at === index} zoom=${zoom} onTap=${toggleChrome} onPlayback=${onPlayback} />`;
        })}</div></div>`}
    <div class="tool-media-controls" data-hidden=${!file && !chrome}><span class="tool-media-count">${file?.name || `${index + 1} / ${items.length}`}</span>
      <button class="detail-sheet-icon" type="button" title=${t.close} aria-label=${t.close} onClick=${onClose}>${icon(ICON_CLOSE)}</button></div>
    ${!file && items.length > 1 && html`<button class="tool-media-previous detail-sheet-icon" type="button" data-hidden=${!chrome} aria-label=${t.previous} disabled=${index === 0} onClick=${() => step(-1)}>${icon(ICON_CHEVRON_RIGHT)}</button>
      <button class="tool-media-next detail-sheet-icon" type="button" data-hidden=${!chrome} aria-label=${t.next} disabled=${index === items.length - 1} onClick=${() => step(1)}>${icon(ICON_CHEVRON_RIGHT)}</button>`}
  </dialog>`;
}
