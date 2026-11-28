import { render } from "./vendor/preact.mjs";
import { useState } from "./vendor/preact-hooks.mjs";
import { html } from "./html.js";
import { AttachmentViewer } from "./attachments.js";
import { CircularProgress } from "./material/progress.js";
import { icon, ICON_BROKEN_IMAGE } from "./icons.js";
import { t } from "./i18n.js";

/** Marks a mounted Markdown picture or video as a page of its message's media gallery. */
export function registerMedia(node, { owner, url, type, name }) {
  node.dataset.mediaOwner = owner;
  node.dataset.mediaUrl = url;
  node.dataset.mediaType = type;
  node.dataset.mediaName = name;
}

export function unregisterMedia(node) {
  for (const key of ["mediaOwner", "mediaUrl", "mediaType", "mediaName", "mediaReady"]) delete node.dataset[key];
}

/**
 * Every ready picture and video of the same message document, in reading order, like the native
 * media viewer: one answer split across several Markdown segments still pages as one gallery.
 */
export function galleryFrom(node) {
  const owner = node.dataset.mediaOwner;
  const slots = [...document.querySelectorAll("[data-media-ready]")]
    .filter(slot => slot.dataset.mediaOwner === owner);
  if (!slots.includes(node)) slots.splice(0, slots.length, node);
  return { index: slots.indexOf(node), items: slots.map(slot => ({ url: slot.dataset.mediaUrl,
    type: slot.dataset.mediaType, kind: "source", name: slot.dataset.mediaName || t.attachments })) };
}

/** The viewer a Markdown media card opens, paging through its gallery. */
export function GalleryViewer({ preview, setPreview }) {
  return preview && html`<${AttachmentViewer} viewer=${preview}
    onNavigate=${index => setPreview(current => current && { ...current, index })} onClose=${() => setPreview(null)} />`;
}

function MarkdownImage({ node, url, label }) {
  const [state, setState] = useState(url ? "loading" : "failed");
  const [preview, setPreview] = useState(null);
  const settle = next => {
    if (next === "loaded") node.dataset.mediaReady = ""; else delete node.dataset.mediaReady;
    setState(next);
  };
  return html`<button type="button" class="tool-image markdown-device-image square" data-state=${state}
    aria-label=${label} disabled=${state !== "loaded"} onClick=${() => setPreview(galleryFrom(node))}>
    ${url && html`<img src=${url} alt=${label} onLoad=${() => settle("loaded")} onError=${() => settle("failed")} />`}
    <span class="tool-image-overlay loading"><${CircularProgress} size=${28} stroke=${3} label=${t.loading} /></span>
    <span class="tool-image-overlay failed tool-image-failed" role="img" aria-label=${t.attachmentUnavailable}>${icon(ICON_BROKEN_IMAGE)}</span>
  </button><${GalleryViewer} preview=${preview} setPreview=${setPreview} />`;
}

/** URLs carry message identity; the phone admits only destinations parsed from that message. */
export function mountMarkdownImage(node, conversationId, messageId, sourceStart = 0, document = "answer") {
  const query = new URLSearchParams({ link: node.dataset.shellImage, start: String(sourceStart),
    ordinal: node.dataset.imageOrdinal || "0", document });
  const url = conversationId && messageId ? `/api/markdown-images/${encodeURIComponent(conversationId)}/${encodeURIComponent(messageId)}?${query}` : null;
  const label = node.dataset.imageLabel || t.attachments;
  if (url) registerMedia(node, { owner: JSON.stringify([conversationId, messageId, document]), url, type: "image", name: label });
  render(html`<${MarkdownImage} node=${node} url=${url} label=${label} />`, node);
  return () => {
    render(null, node);
    unregisterMedia(node);
  };
}
