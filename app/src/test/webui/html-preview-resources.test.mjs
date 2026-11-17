import assert from "node:assert/strict";
import fs from "node:fs";
import test from "node:test";
import vm from "node:vm";

const source = fs.readFileSync(new URL("../../main/assets/webui/html-preview-resources.js", import.meta.url), "utf8")
  .replace(/\bexport /g, "");
const id = "12345678-1234-1234-1234-123456789abc";
const prefix = `https://agora.test/html-preview/${id}`;

// Explicit inert nodes keep these scanner tests independent of an HTML parser.
// The Chrome integration fixture verifies real template/entity/browser behavior.
function loader(tags = new Map()) {
  const visited = [];
  const document = { createElement(name) {
    assert.equal(name, "template");
    return {
      content: {},
      set innerHTML(markup) {
        visited.push(markup);
        const attributes = tags.get(markup);
        assert.ok(attributes, `Unexpected resource start tag: ${markup}`);
        const values = new Map(Object.entries(attributes));
        const name = markup.match(/^<([a-z]+)/i)[1].toLowerCase();
        this.content.firstElementChild = {
          getAttribute: key => values.get(key) ?? null,
          setAttribute: (key, value) => values.set(key, value),
          removeAttribute: key => values.delete(key),
          get outerHTML() {
            return `<${name}` + [...values].map(([key, value]) => ` ${key}="${value}"`).join("") +
              (name === "script" ? "></script>" : ">");
          },
        };
      },
    };
  } };
  const context = vm.createContext({ document, URL });
  vm.runInContext(source, context, { filename: "html-preview-resources.js" });
  return { context, visited };
}
const { context } = loader();

test("shell declarations accept native supported paths and strict percent decoding", () => {
  for (const value of [
    "shell://Quantum/F:/out/a.png", "SHELL://Quantum/F:/a+b.png",
    "shell://Local Sandbox/home/a b.png", "shell://Local%20Sandbox/home/a%20b.png",
    "shell://Quantum/%E4%B8%AD%E6%96%87.png", " <shell://Quantum//absolute.png> ",
  ]) assert.equal(context.shellLink(value), true, value);
  for (const value of [
    "shell://Quantum", "shell:///path", "shell://Quantum/", "shell://%20/a.png",
    "shell://Quantum/%20", "shell://Quantum/%00.png", "shell://Quantum/%GG.png",
    "shell://Quantum/%FF.png", "shell://Quantum/%C3.png", "https://example.test/a.png",
    "shell://Quantum/" + "x".repeat(8192),
  ]) assert.equal(context.shellLink(value), false, value.slice(0, 100));
});

test("only the first source attribute can declare a quoted image path", () => {
  const quoted = tag => context.quotedSource(tag, 4);
  for (const tag of [
    '<img SRC = "shell://Quantum/F:/a.png">',
    "<img alt='src=unquoted >' src='shell://Quantum/F:/a.png'>",
    '<img hidden src="shell://Quantum/F:/first.png" src=ignored>',
  ]) assert.equal(quoted(tag), true, tag);
  for (const tag of [
    '<img src=shell://Quantum/F:/first.png src="shell://Quantum/F:/second.png">',
    '<img data-src="shell://Quantum/F:/a.png">',
    '<img alt="src=\'shell://Quantum/F:/a.png\'">',
    '<img src="unterminated>',
    '<img srcset="shell://Quantum/F:/a.png 2x">',
  ]) assert.equal(quoted(tag), false, tag);
});

test("declarations skip comments raw text unrelated tags and all text after plaintext", () => {
  const actual = '<IMG alt="x > y" SRC="shell://Quantum/F:/real.png">';
  const { context, visited } = loader(new Map([[actual, { src: "shell://Quantum/F:/real.png" }]]));
  const ignored = '<img src="shell://Quantum/F:/ignored.png">';
  const raw = ["script", "style", "textarea", "title", "xmp", "iframe", "noembed", "noframes"]
    .map(name => `<${name}>${ignored}</${name}>`).join("");
  // A script start tag is parsed only to inspect a possible library src.
  const scripts = '<script></script>';
  const original = context.document.createElement;
  context.document.createElement = name => {
    const template = original(name);
    const descriptor = Object.getOwnPropertyDescriptor(template, "innerHTML");
    Object.defineProperty(template, "innerHTML", { set(markup) {
      if (markup === scripts) this.content.firstElementChild = { getAttribute: () => null };
      else descriptor.set.call(this, markup);
    } });
    return template;
  };
  const html = `<!-- ${ignored} -->${raw}<img-custom src="ignored"><div data-src="ignored"></div>` +
    actual + `<plaintext>${ignored}`;
  assert.deepEqual(Array.from(context.previewImageDeclarations(html)), ["shell://Quantum/F:/real.png"]);
  assert.deepEqual(visited, [actual]);
});

test("declarations deduplicate and stop admitting paths after sixteen distinct images", () => {
  const tags = new Map();
  const all = Array.from({ length: 18 }, (_, index) => {
    const link = `shell://Local%20Sandbox/home/${index}.png`;
    const tag = `<img src="${link}">`;
    tags.set(tag, { src: link });
    return tag;
  });
  const { context } = loader(tags);
  const html = all.join("") + all[0];
  assert.deepEqual(Array.from(context.previewImageDeclarations(html)),
    Array.from({ length: 16 }, (_, index) => `shell://Local%20Sandbox/home/${index}.png`));
});

test("image rewrites require an exact capability under the current card and remove crossorigin", () => {
  const link = "shell://Local%20Sandbox/home/a%20b.png";
  const html = `<img src="${link}" crossorigin="anonymous">`;
  const { context } = loader(new Map([[html, { src: link, crossorigin: "anonymous" }]]));
  const path = `/html-preview/${id}/image/0`;
  assert.equal(context.previewResourceSource(html, prefix, { [link]: path }),
    `<img src="https://agora.test${path}">`);
  for (const forged of [
    path.replace(id, "00000000-0000-0000-0000-000000000000"), path + "?path=/private.png",
    path + "/extra", "https://other.test" + path, `/html-preview/${id}/resource/file.png`,
  ]) assert.equal(context.previewResourceSource(html, prefix, { [link]: forged }), html);
  assert.equal(context.previewResourceSource(html, prefix), html);
});

test("CDN rewrites retain anonymous mode while shell links outside img src stay unchanged", () => {
  const script = '<script src="https://cdn.jsdelivr.net/pkg/index.js">';
  const image = '<img src="https://tile.openstreetmap.org/1/2/3.png">';
  const link = '<link href="shell://Quantum/F:/style.css">';
  const { context } = loader(new Map([
    [script + "</script>", { src: "https://cdn.jsdelivr.net/pkg/index.js" }],
    [image, { src: "https://tile.openstreetmap.org/1/2/3.png" }],
    [link, { href: "shell://Quantum/F:/style.css" }],
  ]));
  assert.equal(context.previewResourceSource(script + "</script>" + image + link, prefix),
    `<script src="${prefix}/resource/cdn.jsdelivr.net/pkg/index.js" crossorigin="anonymous"></script>` +
    `<img src="${prefix}/resource/tile.openstreetmap.org/1/2/3.png" crossorigin="anonymous">` + link);
});
