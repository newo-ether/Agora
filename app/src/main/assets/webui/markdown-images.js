import { render } from "./vendor/preact.mjs";
import { useState } from "./vendor/preact-hooks.mjs";
import { html } from "./html.js";
import { AttachmentViewer } from "./attachments.js";
import { CircularProgress } from "./material/progress.js";
import { icon, ICON_BROKEN_IMAGE } from "./icons.js";
import { t } from "./i18n.js";

/**
 * Every loaded picture of the same message document, in reading order, like the native media
 * viewer: one answer split across several Markdown segments still pages as one gallery.
 */
function galleryFrom(node) {
  const owner = node.dataset.imageOwner;
  const slots = [...document.querySelectorAll(".markdown-image-slot[data-image-loaded]")]
    .filter(slot => slot.dataset.imageOwner === owner);
  if (!slots.includes(node)) slots.splice(0, slots.length, node);
  return { index: slots.indexOf(node), items: slots.map(slot => ({ url: slot.dataset.imageUrl, type: "image",
    kind: "source", name: slot.dataset.imageLabel || t.attachments })) };
}

function MarkdownImage({ node, url, label }) {
  const [state, setState] = useState(url ? "loading" : "failed");
  const [preview, setPreview] = useState(null);
  const settle = next => {
    if (next === "loaded") node.dataset.imageLoaded = ""; else delete node.dataset.imageLoaded;
    setState(next);
  };
  return html`<button type="button" class="tool-image markdown-device-image square" data-state=${state}
    aria-label=${label} disabled=${state !== "loaded"} onClick=${() => setPreview(galleryFrom(node))}>
    ${url && html`<img src=${url} alt=${label} onLoad=${() => settle("loaded")} onError=${() => settle("failed")} />`}
    <span class="tool-image-overlay loading"><${CircularProgress} size=${28} stroke=${3} label=${t.loading} /></span>
    <span class="tool-image-overlay failed tool-image-failed" role="img" aria-label=${t.attachmentUnavailable}>${icon(ICON_BROKEN_IMAGE)}</span>
  </button>${preview && html`<${AttachmentViewer} viewer=${preview}
    onNavigate=${index => setPreview(current => current && { ...current, index })} onClose=${() => setPreview(null)} />`}`;
}

/** URLs carry message identity; the phone admits only destinations parsed from that message. */
export function mountMarkdownImage(node, conversationId, messageId, sourceStart = 0, document = "answer") {
  const query = new URLSearchParams({ link: node.dataset.shellImage, start: String(sourceStart),
    ordinal: node.dataset.imageOrdinal || "0", document });
  const url = conversationId && messageId ? `/api/markdown-images/${encodeURIComponent(conversationId)}/${encodeURIComponent(messageId)}?${query}` : null;
  if (url) {
    node.dataset.imageUrl = url;
    node.dataset.imageOwner = JSON.stringify([conversationId, messageId, document]);
  }
  render(html`<${MarkdownImage} node=${node} url=${url} label=${node.dataset.imageLabel || t.attachments} />`, node);
  return () => {
    render(null, node);
    delete node.dataset.imageLoaded;
  };
}
