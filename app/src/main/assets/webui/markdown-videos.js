import { render } from "./vendor/preact.mjs";
import { useState } from "./vendor/preact-hooks.mjs";
import { html } from "./html.js";
import { AttachmentViewer } from "./attachments.js";
import { icon, ICON_PLAY_ARROW } from "./icons.js";
import { t } from "./i18n.js";

/**
 * MarkdownVideoCard in the browser: the first frame of the stream is the poster, and the tap opens
 * the media viewer's player.
 */
function MarkdownVideo({ url, label, description }) {
  const [preview, setPreview] = useState(false);
  const [poster, setPoster] = useState(false);
  return html`<button type="button" class="markdown-video-card" aria-label=${description} disabled=${!url}
    data-poster=${poster ? "" : null} onClick=${() => setPreview(true)}>
    ${url && html`<video class="markdown-video-poster" src=${`${url}#t=0.1`} preload="metadata" muted playsinline
      disablepictureinpicture disableremoteplayback tabindex="-1" aria-hidden="true"
      onLoadedData=${() => setPoster(true)} onSeeked=${() => setPoster(true)} onError=${() => setPoster(false)}></video>`}
    <span class="markdown-video-scrim" aria-hidden="true"></span>
    <span class="markdown-video-play" aria-hidden="true">${icon(ICON_PLAY_ARROW)}</span>
    <span class="markdown-video-label">${label}</span>
  </button>${preview && html`<${AttachmentViewer}
    viewer=${{ index: 0, items: [{ url, type: "video", kind: "source", name: label }] }}
    onNavigate=${() => {}} onClose=${() => setPreview(false)} />`}`;
}

// The native card names the file, not the alt text.
function fileName(link) {
  const name = link.split(/[?#]/)[0].split("/").pop() || link;
  try { return decodeURIComponent(name); } catch { return name; }
}

/** URLs carry message identity; the phone admits only a parsed destination of that message. */
export function mountMarkdownVideo(node, conversationId, messageId, sourceStart = 0, document = "answer") {
  const link = node.dataset.shellVideo;
  const label = fileName(link) || t.videoTitle;
  const query = new URLSearchParams({ link, start: String(sourceStart), document });
  const url = conversationId && messageId
    ? `/api/markdown-videos/${encodeURIComponent(conversationId)}/${encodeURIComponent(messageId)}?${query}`
    : null;
  render(html`<${MarkdownVideo} url=${url} label=${label} description=${node.dataset.videoLabel || label} />`, node);
  return () => render(null, node);
}
