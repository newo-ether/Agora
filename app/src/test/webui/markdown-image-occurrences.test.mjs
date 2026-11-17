import assert from "node:assert/strict";
import fs from "node:fs";
import test from "node:test";
import vm from "node:vm";
import { Marked, Renderer } from "../../main/assets/webui/vendor/marked.esm.js";

const source = fs.readFileSync(new URL("../../main/assets/webui/markdown.js", import.meta.url), "utf8")
  .replace(/^import\b[\s\S]*?;\r?\n/gm, "").replace(/\bexport /g, "");
const context = vm.createContext({ Marked, Renderer, html: () => "", t: { copy: "Copy" },
  ICON_CONTENT_COPY: "", htmlPreviewFence: () => null, NodeFilter: { SHOW_TEXT: 4 },
  document: {
    createElement: () => ({ innerHTML: "", content: {} }),
    createTreeWalker: () => ({ nextNode: () => false }),
  },
});
vm.runInContext(source, context, { filename: "markdown.js" });

test("repeated shell destinations have independent stable occurrence ordinals", () => {
  const first = "![old](shell://Quantum/F:/same.png)\n\n";
  const second = "![new](shell://Quantum/F:/same.png)";
  const before = context.renderHtml(first, []);
  const after = context.renderHtml(first + second, []);
  assert.match(before, /data-image-ordinal="0"/);
  assert.deepEqual([...after.matchAll(/data-image-ordinal="(\d+)"/g)].map(match => match[1]), ["0", "1"]);
  assert.equal([...after.matchAll(/data-shell-image="shell:\/\/Quantum\/F:\/same.png"/g)].length, 2);
});

test("invalid shell destinations do not consume ordinals before valid shell and HTTP images", () => {
  const source = "![missing-path](shell://Quantum)\n\n![bad-escape](shell://Quantum/F:/%ZZ.png)\n\n" +
    "![bad-utf8](shell://Quantum/F:/%FF.png)\n\n![empty](shell://Quantum/%20)\n\n" +
    "![valid](shell://Quantum/F:/a.png)\n\n![web](https://example.test/same.png)\n\n" +
    "![web-again](https://example.test/same.png)";
  const markup = context.renderHtml(source, []);
  assert.deepEqual([...markup.matchAll(/data-image-ordinal="(\d+)"/g)].map(match => match[1]), ["0", "1", "2"]);
  assert.match(markup, /data-shell-image="https:\/\/example.test\/same.png"/);
});

test("mixed reference, nested and table images share the supported ordinal set", () => {
  const source = "`![code](https://example.test/ignored.png)`\n\n" +
    "[link](https://example.test/ignored.png)\n\n![unsupported](ftp://example.test/a.png)\n\n" +
    "- ![nested](http://example.test/a.png)\n\n![ref][r]\n\n" +
    "| image |\n| --- |\n| ![table](shell://Quantum/F:/b.png) |\n\n[r]: https://example.test/ref.png";
  const markup = context.renderHtml(source, []);
  assert.deepEqual([...markup.matchAll(/data-image-ordinal="(\d+)"/g)].map(match => match[1]), ["0", "1", "2"]);
  assert.deepEqual([...markup.matchAll(/data-shell-image="([^"]+)"/g)].map(match => match[1]),
    ["http://example.test/a.png", "https://example.test/ref.png", "shell://Quantum/F:/b.png"]);
});

test("reference images and nested images count without ordinary links or code", () => {
  const source = "`![code](shell://Quantum/F:/ignored.png)`\n\n" +
    "[link](shell://Quantum/F:/ignored.png)\n\n" +
    "- ![nested](shell://Quantum/F:/a.png)\n\n![ref][r]\n\n[r]: shell://Quantum/F:/b.png";
  const markup = context.renderHtml(source, []);
  assert.deepEqual([...markup.matchAll(/data-image-ordinal="(\d+)"/g)].map(match => match[1]), ["0", "1"]);
});
