import assert from "node:assert/strict";
import fs from "node:fs";
import test from "node:test";
import vm from "node:vm";
import { Marked } from "../../main/assets/webui/vendor/marked.esm.js";

// Mirrors AgoraMarkdownFlavourTest: Chinese emphasis must close after CJK punctuation even though
// a CJK letter continues the sentence, which CommonMark refuses to treat as a closing run.
const source = fs.readFileSync(new URL("../../main/assets/webui/markdown.js", import.meta.url), "utf8");
const start = source.indexOf("const CJK_PUNCTUATION");
const end = source.indexOf("// Nested lists indent");
const context = {};
vm.runInNewContext(`${source.slice(start, end)}; this.cjkEmphasisSource = cjkEmphasisSource;
  this.restoreCjkEmphasis = restoreCjkEmphasis;`, context);

const marked = new Marked({ gfm: true });

function render(markdown) {
  const cjk = context.cjkEmphasisSource(markdown);
  assert.equal(cjk.text.length, markdown.length, "the rewrite must keep every source position");
  const restored = context.restoreCjkEmphasis(marked.parse(cjk.text), cjk.restore);
  const marks = text => text.replace(/[^\u2e00-\u2e7f]/gu, "");
  assert.equal(marks(restored), marks(markdown), `sentinel leaked: ${restored}`);
  return restored.trim();
}

const strong = markdown => (render(markdown).match(/<strong>/g) ?? []).length;

test("CJK punctuation closes strong emphasis before more Chinese text", () => {
  assert.equal(render("比如**中文。**不会"), "<p>比如<strong>中文。</strong>不会</p>");
  assert.equal(render("**一句中文。**不会"), "<p><strong>一句中文。</strong>不会</p>");
  assert.equal(render("前**中文，**后"), "<p>前<strong>中文，</strong>后</p>");
});

test("CJK punctuation closes emphasis and opens the next run", () => {
  assert.equal(strong("**中文。**不会**下一句**。"), 2);
});

test("Latin intra-word delimiters and arithmetic stay literal", () => {
  assert.equal(render("snake_case_name"), "<p>snake_case_name</p>");
  assert.equal(render("2 * 3 * 4"), "<p>2 * 3 * 4</p>");
  // A Latin letter after the run is not the CJK continuation the fix targets.
  assert.equal(render("比如**中文。**abc"), "<p>比如**中文。**abc</p>");
});

test("ordinary Latin emphasis still pairs", () => {
  assert.equal(render("**bold** text"), "<p><strong>bold</strong> text</p>");
  assert.equal(render("*italic* text"), "<p><em>italic</em> text</p>");
});

test("underscores follow the same CJK closing rule", () => {
  assert.equal(render("__中文。__不会"), "<p><strong>中文。</strong>不会</p>");
  assert.equal(render("*中文。*不会"), "<p><em>中文。</em>不会</p>");
});

test("a literal supplemental punctuation mark in the source survives", () => {
  assert.equal(render("比如**中文。**不会 ⸀ 原样"), "<p>比如<strong>中文。</strong>不会 ⸀ 原样</p>");
});
