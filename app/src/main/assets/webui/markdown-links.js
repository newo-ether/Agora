// Markdown links to files, aligned with the native MarkdownLinkOpener and the video embedding in
// toRenderableMarkdownText. The browser cannot open files the way the phone does, so a local image
// or video opens in the media viewer through the phone, and any other file stays plain text.
import { render } from "./vendor/preact.mjs";
import { html } from "./html.js";
import { AttachmentViewer } from "./attachments.js";

const VIDEO_EXTENSIONS = new Set(["mp4", "m4v", "webm", "mov", "mkv", "avi", "3gp", "ogv"]);
// Kept equal to linkedMediaMimeType on the phone; SVG stays out because it can script.
const IMAGE_EXTENSIONS = new Set(["png", "jpg", "jpeg", "gif", "webp", "bmp", "avif"]);
// The roots resolveMarkdownDestination reads on the phone, plus `file:` URLs over them.
const SANDBOX_HOME = "/home/agora/";
const LOCAL_ROOTS = [SANDBOX_HOME, "/mnt/shared/", "/storage/emulated/0/", "/storage/self/primary/", "/sdcard/"];
// PATH_DELIMITERS and PATH_TRAILING_CHARS of markdownBarePathAt.
const PATH_DELIMITERS = " \t\r\n\"'`<>()[]{}|，。；：！？、）】》”’";
const PATH_TRAILING = ".,;:!?)]}\"'";

const unwrap = link => link.trim().replace(/^<([\s\S]*)>$/, "$1");

/** Path of a `file:` URL, the same folding fileUrlPath does on the phone. */
function fileUrlPath(raw) {
  const rest = raw.slice("file:".length);
  if (!rest.startsWith("//")) return rest;
  const afterSlashes = rest.slice(2);
  if (afterSlashes.startsWith("/")) return afterSlashes;
  const path = afterSlashes.split("/").slice(1).join("/");
  return path ? `/${path}` : "";
}

function decodePath(value) {
  if (!value.includes("%")) return value;
  try { return decodeURIComponent(value); } catch { return value; }
}

/**
 * The readable-root path this link names, or null. Admission only: the request still carries the
 * original spelling, and the phone resolves it again before reading a byte.
 */
export function localMediaPath(link) {
  const raw = unwrap(link);
  const path = decodePath(/^file:/i.test(raw) ? fileUrlPath(raw) : raw);
  const root = LOCAL_ROOTS.find(candidate => path.startsWith(candidate));
  if (!root || !path.slice(root.length)) return null;
  return path.split("/").includes("..") ? null : path;
}

function extension(raw) {
  const name = raw.split("?")[0].split("#")[0].toLowerCase();
  const dot = name.lastIndexOf(".");
  return dot < 0 ? "" : name.slice(dot + 1);
}

/** "image" or "video" when native would open this link in its media viewer, otherwise null. */
export function linkedMediaType(link) {
  const raw = unwrap(link);
  let path = raw;
  if (/^shell:\/\//i.test(raw)) {
    const rest = raw.slice("shell://".length), slash = rest.indexOf("/");
    if (slash <= 0) return null;
    try {
      if (decodeURIComponent(rest.slice(0, slash)).trim().toLowerCase() !== "local sandbox") return null;
      path = decodeURIComponent(rest.slice(slash));
    } catch { return null; }
    if (!path.startsWith(SANDBOX_HOME)) return null;
  } else if (!localMediaPath(raw)) return null;
  if (path.split("/").includes("..")) return null;
  const type = extension(raw);
  return IMAGE_EXTENSIONS.has(type) ? "image" : VIDEO_EXTENSIONS.has(type) ? "video" : null;
}

// markdownVideoLink: a shell, HTTP(S) or local destination whose name is a video container.
function videoDestination(link) {
  const raw = unwrap(link);
  const supported = /^shell:\/\//i.test(raw) || /^https?:\/\/[^\s/?#]+(?:[/?#]\S*)?$/i.test(raw) ||
    !!localMediaPath(raw);
  return raw && supported && VIDEO_EXTENSIONS.has(extension(raw)) ? raw : null;
}

const fenceMarker = line => {
  const trimmed = line.trim();
  return trimmed.startsWith("```") ? "```" : trimmed.startsWith("~~~") ? "~~~" : null;
};

// The phone's embeddedVideoNode: the renderer reads a plain `](path)` destination only.
const videoNode = destination =>
  `![](${destination.replace(/ /g, "%20").replace(/\(/g, "%28").replace(/\)/g, "%29")})`;

/** Apply sorted, non-overlapping [start, end, text] edits; every new character maps to its edit start. */
function applyEdits(text, origin, edits) {
  if (!edits.length) return { text, origin };
  let out = "", map = [], cursor = 0;
  for (const [start, end, replacement] of edits) {
    out += text.slice(cursor, start);
    map = map.concat(origin.slice(cursor, start));
    out += replacement;
    for (let index = 0; index < replacement.length; index++) map.push(origin[start]);
    cursor = end;
  }
  out += text.slice(cursor);
  return { text: out, origin: map.concat(origin.slice(cursor)) };
}

const HTML_VIDEO = /<video\b[^>]*\/>|<video\b[^>]*>[\s\S]*?<\/video\s*>/gi;
const HTML_SRC = /\bsrc\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+))/i;
const STANDALONE_LINK = /^\[[^\]]*]\(<?([^()\s<>]+)>?\)$/;

// embedHtmlVideoTags: a raw <video src> outside fenced code becomes the video image node.
function htmlVideoEdits(text) {
  if (!/<video/i.test(text)) return [];
  const fenced = [];
  let fence = null, fenceStart = 0, offset = 0;
  for (const line of text.split("\n")) {
    const marker = fenceMarker(line);
    if (marker && !fence) { fence = marker; fenceStart = offset; }
    else if (marker && fence === marker) { fence = null; fenced.push([fenceStart, offset + line.length]); }
    offset += line.length + 1;
  }
  if (fence) fenced.push([fenceStart, text.length]);
  const edits = [];
  for (const match of text.matchAll(HTML_VIDEO)) {
    if (fenced.some(([start, end]) => match.index >= start && match.index <= end)) continue;
    const source = HTML_SRC.exec(match[0]);
    const destination = (source?.slice(1).find(Boolean) ?? "").trim();
    if (destination && videoDestination(destination)) edits.push([match.index, match.index + match[0].length, videoNode(destination)]);
  }
  return edits;
}

function standaloneVideo(line) {
  if (line.startsWith("    ") || line.startsWith("\t")) return null;
  const trimmed = line.trim();
  if (!trimmed) return null;
  const link = STANDALONE_LINK.exec(trimmed);
  if (link) return videoDestination(link[1]);
  if (/\s/.test(trimmed) || "<[!`".includes(trimmed[0]) || trimmed.includes("`") || trimmed.includes("](")) return null;
  return videoDestination(trimmed);
}

// embedStandaloneVideoPaths: a line that is only a video destination becomes the video image node.
function standaloneVideoEdits(text) {
  if (!text.includes("/")) return [];
  const edits = [];
  let fence = null, offset = 0;
  for (const line of text.split("\n")) {
    const marker = fenceMarker(line);
    if (marker) { if (!fence) fence = marker; else if (fence === marker) fence = null; }
    else if (!fence) {
      const destination = standaloneVideo(line);
      if (destination) edits.push([offset, offset + line.length, videoNode(destination)]);
    }
    offset += line.length + 1;
  }
  return edits;
}

/**
 * The phone renders a standalone video line or a `<video>` element as the inline video card. Do
 * the same rewrite here and keep, for every new character, the index it came from.
 */
export function embedVideos(markdown) {
  const tagEdits = htmlVideoEdits(markdown);
  const identity = { text: markdown, origin: null };
  const origin = () => Array.from({ length: markdown.length }, (_, index) => index);
  const tags = tagEdits.length ? applyEdits(markdown, origin(), tagEdits) : identity;
  const lineEdits = standaloneVideoEdits(tags.text);
  if (!lineEdits.length) return tags === identity ? null : tags;
  return applyEdits(tags.text, tags.origin ?? origin(), lineEdits);
}

const escapeHtml = (text) =>
  text.replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c]);

/** Plain text with bare local media paths made tappable, unstyled like the phone's bare paths. */
export function linkBarePaths(text) {
  let out = "", cursor = 0;
  for (let index = 0; index < text.length; index++) {
    if (text[index] !== "/" || (index > 0 && !PATH_DELIMITERS.includes(text[index - 1]))) continue;
    if (!LOCAL_ROOTS.some(root => text.startsWith(root, index))) continue;
    let end = index;
    while (end < text.length && !PATH_DELIMITERS.includes(text[end])) end++;
    while (end > index && PATH_TRAILING.includes(text[end - 1])) end--;
    const path = text.slice(index, end);
    if (!linkedMediaType(path)) continue;
    out += escapeHtml(text.slice(cursor, index)) +
      `<span class="markdown-bare-path" role="button" tabindex="0" data-media-link="${escapeHtml(path)}">${escapeHtml(path)}</span>`;
    cursor = end;
    index = end - 1;
  }
  return cursor ? out + escapeHtml(text.slice(cursor)) : escapeHtml(text);
}

/** Open one linked image or video of the message in the media viewer, streamed by the phone. */
export function openLinkedMedia(link, conversationId, messageId, document = "answer") {
  const type = linkedMediaType(link);
  if (!type || !conversationId || !messageId) return;
  const query = new URLSearchParams({ link, document });
  const url = `/api/markdown-files/${encodeURIComponent(conversationId)}/${encodeURIComponent(messageId)}?${query}`;
  const name = link.split(/[?#]/)[0].split("/").pop() || link;
  const host = window.document.createElement("div");
  window.document.body.append(host);
  const close = () => { render(null, host); host.remove(); };
  render(html`<${AttachmentViewer} viewer=${{ index: 0, items: [{ url, type, kind: "source", name }] }}
    onNavigate=${() => {}} onClose=${close} />`, host);
}
