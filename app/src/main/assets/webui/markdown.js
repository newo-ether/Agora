// Assistant Markdown, laid out like the app's MessageItemMarkdown on the mikepenz renderer. The
// phone already split math out with the app's own parser: a formula is "\uE000<index>\uE001" in
// the text and its TeX is in `math`. Raw HTML stays literal text, as in the app, and KaTeX builds
// its DOM directly so the page needs no inline style attributes.
import { useLayoutEffect, useMemo, useRef } from "./vendor/preact-hooks.mjs";
import { Marked, Renderer } from "./vendor/marked.esm.js";
import { html } from "./html.js";
import { t } from "./i18n.js";
import { ICON_CONTENT_COPY } from "./icons.js";

const escapeHtml = (text) =>
  text.replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c]);

const SAFE_LINK = /^(https?:|mailto:)/i;
// Nested lists indent 8 dp per level; style.css has one rule per level up to this depth.
const MAX_LIST_DEPTH = 6;
let listDepth = 0;

const marked = new Marked({ gfm: true });
const sourceAttributes = token => `data-source-positions="${token.sourcePositions?.join(",") ?? ""}" data-source-raw="${escapeHtml(token.raw)}"`;
marked.use({
  renderer: {
    text(token) {
      if (token.tokens) return this.parser.parseInline(token.tokens);
      return `<span ${sourceAttributes(token)}>${Renderer.prototype.text.call(this, token)}</span>`;
    },
    codespan(token) {
      return `<code ${sourceAttributes(token)}>${escapeHtml(token.text)}</code>`;
    },
    html: token => `<span ${sourceAttributes(token)}>${escapeHtml(token.text)}</span>`,
    link({ href, title, tokens }) {
      const label = this.parser.parseInline(tokens);
      if (!SAFE_LINK.test(href)) return label;
      const titleAttr = title ? ` title="${escapeHtml(title)}"` : "";
      return `<a href="${escapeHtml(href)}"${titleAttr} target="_blank" rel="noopener noreferrer">${label}</a>`;
    },
    // MarkdownListItems: the marker ("• " or "<n>. ") beside a column that holds the item.
    list(token) {
      const depth = Math.min(listDepth, MAX_LIST_DEPTH);
      listDepth += 1;
      const items = token.items.map((item, index) => {
        const marker = token.ordered ? `${token.start + index}. ` : "• ";
        return `<li><span class="md-marker">${marker}</span>` +
          `<div class="md-item">${this.parser.parse(item.tokens)}</div></li>`;
      }).join("");
      listDepth -= 1;
      const tag = token.ordered ? "ol" : "ul";
      return `<${tag} class="md-list" data-depth="${depth}">${items}</${tag}>`;
    },
    // ChatCodeBlockHeader: the language (when given) and Copy in a SpaceBetween row, then the code.
    code(token) {
      const { text, lang, raw } = token;
      const language = (lang ?? "").trim().split(/\s+/)[0];
      const label = language ? `<span class="code-language">${escapeHtml(language.toUpperCase())}</span>` : "";
      return `<div class="code-block"><div class="code-header">${label}` +
        `<button class="code-copy" type="button" aria-label="${escapeHtml(t.copy)}"></button></div>` +
        `<pre><code ${sourceAttributes(token)} data-source-content-start="${/^\s*(```|~~~)/.test(raw) ? raw.indexOf("\n") + 1 : 0}">${escapeHtml(text)}</code></pre></div>`;
    },
  },
});

const MATH_SLOT = /\uE000(\d+)\uE001/g;
const SPACER = '<div class="md-spacer"></div>';
const lineBreaks = (text) => text.split("\n").length - 1;

/**
 * The app puts one block spacer before every top-level node, and each line break between blocks
 * is a node of its own, so a blank line between two paragraphs is three spacers. Display math
 * is its own paragraph with a blank line on each side, as latexToMarkdown writes it.
 */
function renderHtml(markdown, math, sourceMap = []) {
  const positions = [];
  let source = "", boundary = 0, delta = 0;
  for (let index = 0; index < markdown.length; index++) {
    while (sourceMap[boundary]?.[0] <= index) {
      delta = sourceMap[boundary][1] - sourceMap[boundary][0];
      boundary++;
    }
    const slot = markdown.slice(index).match(/^\uE000(\d+)\uE001/);
    const display = slot && math?.[slot[1]]?.display;
    if (display) { source += "\n\n"; positions.push(index + delta, index + delta); }
    source += markdown[index];
    positions.push(index + delta);
    if (display) {
      for (let part = 1; part < slot[0].length; part++) {
        index++;
        while (sourceMap[boundary]?.[0] <= index) {
          delta = sourceMap[boundary][1] - sourceMap[boundary][0];
          boundary++;
        }
        source += markdown[index];
        positions.push(index + delta);
      }
      source += "\n\n"; positions.push(index + delta, index + delta);
    }
  }
  const tokens = marked.lexer(source);
  function locate(tokens, raw, positions) {
    let cursor = 0;
    for (const token of tokens) {
      const exact = raw.indexOf(token.raw, cursor);
      const selected = [];
      for (let index = 0; index < token.raw.length; index++) {
        const found = exact >= 0 ? exact + index : raw.indexOf(token.raw[index], cursor);
        if (found < 0) break;
        selected.push(positions[found]);
        cursor = found + 1;
      }
      token.sourcePositions = selected;
      locate(token.tokens || token.items || [], token.raw, selected);
      if (token.header) {
        locate([...token.header, ...token.rows.flat()].flatMap(cell => cell.tokens), token.raw, selected);
      }
    }
  }
  locate(tokens, source, positions);
  let out = "";
  let breaks = 0;
  for (const token of tokens) {
    if (token.type === "space") {
      breaks += lineBreaks(token.raw);
      continue;
    }
    listDepth = 0;
    out += SPACER.repeat(breaks + 1) + marked.parser(Object.assign([token], { links: tokens.links }));
    breaks = token.raw.length - token.raw.replace(/\n+$/, "").length;
  }
  out += SPACER.repeat(breaks);
  return out.replace(MATH_SLOT, (_, index) => `<span class="math" data-math="${index}"></span>`);
}

const copyIcon = `<svg viewBox="0 0 24 24" aria-hidden="true"><path d="${ICON_CONTENT_COPY}"/></svg>`;
let fadeIdentity = 0;

/** Draw-only alpha preserves the text nodes, shaping, wrapping, selection and search geometry. */
export function fadeStreamingText(element, streaming, reduceMotion, timeline, sourceStart = 0, variant = "answer") {
  if (!element || reduceMotion) return;
  const published = performance.now();
  const groups = new Map();
  const walker = document.createTreeWalker(element, NodeFilter.SHOW_TEXT, {
    acceptNode: node => node.parentElement.closest(".code-header,.md-marker,.math")
      ? NodeFilter.FILTER_REJECT : NodeFilter.FILTER_ACCEPT,
  });
  let index = sourceStart;
  while (walker.nextNode()) {
    const node = walker.currentNode;
    let offset = 0;
    for (const glyph of node.textContent) {
      const key = `${variant}:${index++}`;
      let birth = timeline.get(key);
      if (birth == null && streaming) { birth = published; timeline.set(key, birth); }
      if (birth != null && published - birth < 500) {
        const color = getComputedStyle(node.parentElement).color;
        const groupKey = `${birth}:${color}`;
        let group = groups.get(groupKey);
        if (!group) { group = { birth, color, ranges: [] }; groups.set(groupKey, group); }
        const range = new Range();
        range.setStart(node, offset);
        range.setEnd(node, offset + glyph.length);
        group.ranges.push(range);
      }
      offset += glyph.length;
    }
  }
  if (!groups.size) return;
  const sheet = new CSSStyleSheet();
  document.adoptedStyleSheets = [...document.adoptedStyleSheets, sheet];
  const owned = [...groups.values()].map(group => {
    const name = `agora-stream-${++fadeIdentity}`;
    CSS.highlights.set(name, new Highlight(...group.ranges));
    const rule = sheet.cssRules[sheet.insertRule(`::highlight(${name}) { color: transparent; }`)];
    return { ...group, name, rule };
  });
  let frame = 0;
  const paint = () => {
    const now = performance.now();
    let active = false;
    for (const group of owned) {
      const alpha = Math.min(1, Math.max(0, (now - group.birth) / 500));
      group.rule.style.color = `color-mix(in srgb, ${group.color} ${alpha * 100}%, transparent)`;
      active ||= alpha < 1;
    }
    if (active) frame = requestAnimationFrame(paint);
  };
  paint();
  return () => {
    cancelAnimationFrame(frame);
    owned.forEach(group => CSS.highlights.delete(group.name));
    document.adoptedStyleSheets = document.adoptedStyleSheets.filter(current => current !== sheet);
  };
}
/** Projects server match identities onto visible glyphs without supplying result counts. */
export function highlightSearch(element, search, matches, sourceStart = 0) {
  element.querySelectorAll("mark[data-search-key]").forEach(mark => mark.replaceWith(...mark.childNodes));
  element.normalize();
  if (!search?.query.trim() || !matches.length) return;
  const walker = document.createTreeWalker(element, NodeFilter.SHOW_TEXT, {
    acceptNode: node => node.parentElement.closest(".code-header,.md-marker,.math") ? NodeFilter.FILTER_REJECT : NodeFilter.FILTER_ACCEPT,
  });
  const nodes = [];
  let node;
  while ((node = walker.nextNode())) nodes.push({ node });
  for (const entry of nodes) {
    const token = entry.node.parentElement.closest("[data-source-positions]");
    const raw = token?.dataset.sourceRaw ?? entry.node.textContent;
    const positions = token ? token.dataset.sourcePositions.split(",").map(Number) : Array.from({ length: raw.length }, (_, index) => index);
    const value = entry.node.textContent;
    let decoded = "", decodedPositions = [], cursor = Number(token?.dataset.sourceContentStart ?? 0);
    for (let index = cursor; index < raw.length; index++) {
      const entity = token?.tagName !== "CODE" && raw.slice(index).match(/^&(?:#\d+|#x[\da-f]+|[a-z]+);/i);
      if (entity) {
        const span = document.createElement("span");
        span.innerHTML = entity[0];
        decoded += span.textContent;
        decodedPositions.push(...Array(span.textContent.length).fill(positions[index]));
        index += entity[0].length - 1;
      } else if (token?.tagName !== "CODE" && raw[index] === "\\" && /[!"#$%&'()*+,\-./:;<=>?@[\]\\^_`{|}~]/.test(raw[index + 1] ?? "")) {
        continue;
      } else {
        decoded += raw[index];
        decodedPositions.push(positions[index]);
      }
    }
    cursor = 0;
    for (let index = 0; index < value.length; index++) {
      const found = decoded.indexOf(value[index], cursor);
      if (found < 0) break;
      cursor = found + 1;
      const position = decodedPositions[found] + sourceStart;
      const match = matches.find(match => position >= match.start && position < match.endExclusive);
      if (!match) continue;
      entry.ranges ??= [];
      const previous = entry.ranges.at(-1);
      if (previous?.key === match.key && previous.end === index) previous.end++;
      else entry.ranges.push({ start: index, end: index + 1, key: match.key });
    }
  }
  for (const entry of nodes) {
    if (!entry.ranges) continue;
    const fragment = document.createDocumentFragment(), value = entry.node.textContent;
    let cursor = 0;
    for (const range of entry.ranges) {
      fragment.append(value.slice(cursor, range.start));
      const mark = document.createElement("mark");
      mark.dataset.searchKey = range.key;
      mark.classList.toggle("active", search.matches[search.index]?.key === range.key);
      mark.textContent = value.slice(range.start, range.end);
      fragment.append(mark);
      cursor = range.end;
    }
    fragment.append(value.slice(cursor));
    entry.node.replaceWith(fragment);
  }
}

/**
 * Renders one WebText. `variant` picks the answer or thought type scale; `wrap` follows the app's
 * code block wrapping setting (off scrolls long lines sideways).
 */
export function Markdown({ text, variant = "answer", wrap = true, search, matches = [], sourceStart = 0,
  streaming = false, reduceMotion = false, births }) {
  const root = useRef(null);
  const localBirths = useRef(new Map());
  const markup = useMemo(() => renderHtml(text.markdown, text.math, text.sourceMap), [text.markdown, text.math, text.sourceMap]);
  useLayoutEffect(() => {
    const element = root.current;
    if (!element) return;
    element.querySelectorAll("[data-math]").forEach((slot) => {
      const math = text.math?.[Number(slot.dataset.math)];
      if (!math) return;
      slot.classList.toggle("display", math.display);
      window.katex?.render(math.tex, slot, { displayMode: math.display, throwOnError: false });
    });
    element.querySelectorAll(".code-copy").forEach((button) => {
      button.innerHTML = copyIcon;
      button.onclick = () => {
        const code = button.closest(".code-block")?.querySelector("code")?.textContent ?? "";
        navigator.clipboard?.writeText(code).catch(() => {});
      };
    });
  }, [markup, text.math]);
  useLayoutEffect(() => { highlightSearch(root.current, search, matches, sourceStart); }, [markup, search, matches, sourceStart]);
  useLayoutEffect(() => {
    return fadeStreamingText(root.current, streaming, reduceMotion,
      births ?? localBirths.current, sourceStart, variant);
  }, [markup, search, matches, sourceStart, streaming, reduceMotion, births]);
  const className = `markdown ${variant}${wrap ? "" : " nowrap"}`;
  return html`<div class=${className} ref=${root} dangerouslySetInnerHTML=${{ __html: markup }} />`;
}
