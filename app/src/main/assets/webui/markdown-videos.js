import { render } from "./vendor/preact.mjs";
import { useState } from "./vendor/preact-hooks.mjs";
import { html } from "./html.js";
import { AttachmentViewer } from "./attachments.js";
import { icon, ICON_PLAY_ARROW } from "./icons.js";
import { t } from "./i18n.js";

function MarkdownVideo({ url, label }) {
  const [preview, setPreview] = useState(false);
  return html`<button type="button" class="markdown-video-card" aria-label=${label} disabled=${!url}
    onClick=${() => setPreview(true)}>
    <span class="markdown-video-play">${icon(ICON_PLAY_ARROW)}</span>
    <span class="markdown-video-label">${label}</span>
  </button>${preview && html`<${AttachmentViewer}
    viewer=${{ index: 0, items: [{ url, type: "video", kind: "source", name: label }] }}
    onNavigate=${() => {}} onClose=${() => setPreview(false)} />`}`;
}

/** URLs carry message identity; the phone admits only a parsed destination of that message. */
export function mountMarkdownVideo(node, conversationId, messageId, sourceStart = 0, document = "answer") {
  const label = node.dataset.videoLabel || t.videoTitle;
  const query = new URLSearchParams({ link: node.dataset.shellVideo, start: String(sourceStart), document });
  const url = conversationId && messageId
    ? `/api/markdown-videos/${encodeURIComponent(conversationId)}/${encodeURIComponent(messageId)}?${query}`
    : null;
  render(html`<${MarkdownVideo} url=${url} label=${label} />`, node);
  return () => render(null, node);
}
