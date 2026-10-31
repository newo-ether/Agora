import { render } from "./vendor/preact.mjs";
import { useState } from "./vendor/preact-hooks.mjs";
import { html } from "./html.js";
import { AttachmentViewer } from "./attachments.js";
import { CircularProgress } from "./material/progress.js";
import { icon, ICON_BROKEN_IMAGE } from "./icons.js";
import { t } from "./i18n.js";

function MarkdownImage({ url, label }) {
  const [state, setState] = useState(url ? "loading" : "failed");
  const [preview, setPreview] = useState(false);
  return html`<button type="button" class="tool-image markdown-device-image square" data-state=${state}
    aria-label=${label} disabled=${state !== "loaded"} onClick=${() => setPreview(true)}>
    ${url && html`<img src=${url} alt=${label} onLoad=${() => setState("loaded")} onError=${() => setState("failed")} />`}
    <span class="tool-image-overlay loading"><${CircularProgress} size=${28} stroke=${3} label=${t.loading} /></span>
    <span class="tool-image-overlay failed tool-image-failed" role="img" aria-label=${t.attachmentUnavailable}>${icon(ICON_BROKEN_IMAGE)}</span>
  </button>${preview && html`<${AttachmentViewer} viewer=${{ index: 0, items: [{ url, type: "image", kind: "source", name: label }] }}
    onNavigate=${() => {}} onClose=${() => setPreview(false)} />`}`;
}

/** URLs carry message identity; the phone admits only destinations parsed from that message. */
export function mountMarkdownImage(node, conversationId, messageId) {
  const url = conversationId && messageId ? `/api/markdown-images/${encodeURIComponent(conversationId)}/${encodeURIComponent(messageId)}?link=${encodeURIComponent(node.dataset.shellImage)}` : null;
  render(html`<${MarkdownImage} url=${url} label=${node.dataset.imageLabel || t.attachments} />`, node);
  return () => render(null, node);
}
