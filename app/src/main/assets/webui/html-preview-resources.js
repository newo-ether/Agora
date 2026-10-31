// Rewrite only real resource start tags; source/copy/export retain the original document bytes.
const LIBRARIES = new Set(['cdnjs.cloudflare.com', 'cdn.jsdelivr.net', 'unpkg.com', 'esm.sh']);
const RAW_TEXT = new Set(['script', 'style', 'textarea', 'title', 'xmp', 'iframe', 'noembed', 'noframes']);
export function previewResourceSource(source, prefix) {
  const template = document.createElement('template');
  const rewrite = (tag, name) => {
    if (!['script', 'link', 'img'].includes(name)) return tag;
    template.innerHTML = tag + (name === 'script' ? '</script>' : '');
    const node = template.content.firstElementChild;
    const attribute = name === 'link' ? 'href' : 'src';
    const value = node?.getAttribute(attribute);
    if (!value) return tag;
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
    const name = tag.match(/^<([a-z][a-z0-9:-]*)(?=[\s/>])/i)?.[1].toLowerCase();
    output += name ? rewrite(tag, name) : tag;
    cursor = end;
    // Text inside these elements is never parsed as resource tags, even when it contains HTML strings.
    if (RAW_TEXT.has(name)) {
      const close = new RegExp(`</${name}[\\t\\n\\f\\r />]`, 'ig');
      close.lastIndex = cursor;
      const match = close.exec(source);
      const rawEnd = match ? match.index : source.length;
      output += source.slice(cursor, rawEnd); cursor = rawEnd;
    }
  }
  return output;
}
