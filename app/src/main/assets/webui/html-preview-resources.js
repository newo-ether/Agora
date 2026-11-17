// Rewrite only real resource start tags; source/copy/export retain the original document bytes.
const LIBRARIES = new Set(['cdnjs.cloudflare.com', 'cdn.jsdelivr.net', 'unpkg.com', 'esm.sh']);
const RAW_TEXT = new Set(['script', 'style', 'textarea', 'title', 'xmp', 'iframe', 'noembed', 'noframes']);
const MAX_IMAGES = 16, MAX_LINK_CHARS = 8192;

function shellLink(value) {
  if (value.length > MAX_LINK_CHARS) return false;
  let raw = value.trim();
  if (raw.startsWith('<') && raw.endsWith('>')) raw = raw.slice(1, -1);
  const match = raw.match(/^shell:\/\/([^/]+)\/([\s\S]+)$/i);
  if (!match) return false;
  try {
    const server = decodeURIComponent(match[1]).trim(), path = decodeURIComponent(match[2]);
    return !!server && !!path.trim() && !path.includes('\0');
  } catch { return false; }
}

// HTML keeps the first attribute. Never admit unquoted src or src text inside another value.
function quotedSource(tag, nameEnd) {
  let cursor = nameEnd;
  while (cursor < tag.length - 1) {
    while (cursor < tag.length && /[\s/]/.test(tag[cursor])) cursor++;
    const start = cursor;
    while (cursor < tag.length && !/[\s=/>]/.test(tag[cursor])) cursor++;
    const name = tag.slice(start, cursor).toLowerCase();
    if (cursor === start) return false;
    while (cursor < tag.length && /\s/.test(tag[cursor])) cursor++;
    if (tag[cursor] !== '=') continue;
    cursor++;
    while (cursor < tag.length && /\s/.test(tag[cursor])) cursor++;
    const quote = tag[cursor];
    if (quote === '"' || quote === "'") {
      cursor++;
      const end = tag.indexOf(quote, cursor);
      if (end < 0) return false;
      if (name === 'src') return true;
      cursor = end + 1;
    } else {
      if (name === 'src') return false;
      while (cursor < tag.length && !/[\s>]/.test(tag[cursor])) cursor++;
    }
  }
  return false;
}

function transform(source, prefix, images, declarations) {
  const template = document.createElement('template');
  const rewrite = (tag, name, nameEnd) => {
    if (!['script', 'link', 'img'].includes(name)) return tag;
    template.innerHTML = tag + (name === 'script' ? '</script>' : '');
    const node = template.content.firstElementChild;
    const attribute = name === 'link' ? 'href' : 'src';
    const value = node?.getAttribute(attribute);
    if (!value) return tag;
    if (name === 'img' && quotedSource(tag, nameEnd) && shellLink(value)) {
      if (declarations && !declarations.includes(value) && declarations.length < MAX_IMAGES) declarations.push(value);
      const image = images?.[value];
      if (!image || !/^\/html-preview\/[a-f0-9-]{36}\/image\/[0-9]+$/.test(image) ||
          !image.startsWith(new URL(prefix).pathname + '/image/')) return tag;
      node.setAttribute('src', new URL(image, prefix).href);
      node.removeAttribute('crossorigin');
      return node.outerHTML;
    }
    if (!prefix) return tag;
    try {
      const url = new URL(value);
      if (url.protocol !== 'https:' || url.port || url.username || url.password || url.search || url.hash ||
          (!LIBRARIES.has(url.hostname) && url.hostname !== 'tile.openstreetmap.org')) return tag;
      node.setAttribute(attribute, `${prefix}/resource/${url.hostname}${url.pathname}`);
      node.setAttribute('crossorigin', 'anonymous');
      const result = node.outerHTML;
      return name === 'script' ? result.slice(0, -'</script>'.length) : result;
    } catch { return tag; }
  };
  let output = '', cursor = 0;
  while (cursor < source.length) {
    const start = source.indexOf('<', cursor);
    if (start < 0) { output += source.slice(cursor); break; }
    output += source.slice(cursor, start);
    if (source.startsWith('<!--', start)) {
      const close = source.indexOf('-->', start + 4);
      const end = close < 0 ? source.length : close + 3;
      output += source.slice(start, end); cursor = end; continue;
    }
    let end = start + 1, quote = '';
    for (; end < source.length; end++) {
      const char = source[end];
      if (quote) { if (char === quote) quote = ''; }
      else if (char === '"' || char === "'") quote = char;
      else if (char === '>') { end++; break; }
    }
    const tag = source.slice(start, end);
    const match = tag.match(/^<([a-z][a-z0-9:-]*)(?=[\s/>])/i);
    const name = match?.[1].toLowerCase();
    output += name ? rewrite(tag, name, match[0].length) : tag;
    cursor = end;
    if (name === 'plaintext') { output += source.slice(cursor); break; }
    // Text inside these elements is never parsed as resource tags, even when it contains HTML strings.
    if (RAW_TEXT.has(name)) {
      const close = new RegExp(`</${name}[\\t\\n\\f\\r />]`, 'ig');
      close.lastIndex = cursor;
      const rawEnd = close.exec(source)?.index ?? source.length;
      output += source.slice(cursor, rawEnd); cursor = rawEnd;
    }
  }
  return output;
}

export function previewImageDeclarations(source) {
  const images = [];
  transform(source, null, null, images);
  return images;
}

export function previewResourceSource(source, prefix, images = {}) {
  return transform(source, prefix, images, null);
}
