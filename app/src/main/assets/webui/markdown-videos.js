import { render } from "./vendor/preact.mjs";
import { useState } from "./vendor/preact-hooks.mjs";
import { html } from "./html.js";
import { galleryFrom, GalleryViewer, registerMedia, unregisterMedia } from "./markdown-images.js";
import { icon, ICON_PLAY_ARROW } from "./icons.js";
import { t } from "./i18n.js";

/**
 * MarkdownVideoCard in the browser: the first frame of the stream is the poster, and the tap opens
 * the message's media viewer on this video, paging with the answer's pictures and other videos.
 */
function MarkdownVideo({ node, url, label, description }) {
  const [preview, setPreview] = useState(null);
  const [poster, setPoster] = useState(false);
  return html`<button type="button" class="markdown-video-card" aria-label=${description} disabled=${!url}
    data-poster=${poster ? "" : null} onClick=${() => setPreview(galleryFrom(node))}>
    ${url && html`<video class="markdown-video-poster" src=${`${url}#t=0.1`} preload="metadata" muted playsinline
      disablepictureinpicture disableremoteplayback tabindex="-1" aria-hidden="true"
      onLoadedData=${() => setPoster(true)} onSeeked=${() => setPoster(true)} onError=${() => setPoster(false)}></video>`}
    <span class="markdown-video-scrim" aria-hidden="true"></span>
    <span class="markdown-video-play" aria-hidden="true">${icon(ICON_PLAY_ARROW)}</span>
    <span class="markdown-video-label">${label}</span>
  </button><${GalleryViewer} preview=${preview} setPreview=${setPreview} />`;
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
  if (url) {
    registerMedia(node, { owner: JSON.stringify([conversationId, messageId, document]), url, type: "video", name: label });
    // The stream is opened on demand, so a video pages from the start like the native viewer.
    node.dataset.mediaReady = "";
  }
  render(html`<${MarkdownVideo} node=${node} url=${url} label=${label} description=${node.dataset.videoLabel || label} />`, node);
  return () => { render(null, node); unregisterMedia(node); };
}
