import assert from "node:assert/strict";
import fs from "node:fs";
import test from "node:test";
import vm from "node:vm";
import { Marked } from "../../main/assets/webui/vendor/marked.esm.js";

// The owner's bare-URL checklist (2026-11-23), mirrored from MarkdownBareUrlProseTest on the phone.
const source = fs.readFileSync(new URL("../../main/assets/webui/markdown.js", import.meta.url), "utf8");
const start = source.indexOf("const LINK_TAIL_PUNCTUATION");
const end = source.indexOf("// Nested lists indent");
const context = {};
vm.runInNewContext(`${source.slice(start, end)}; this.trimAutolinkTails = trimAutolinkTails;`, context);
const marked = new Marked({ gfm: true });

function links(markdown) {
  const tokens = marked.lexer(markdown);
  context.trimAutolinkTails(tokens);
  const found = [];
  marked.walkTokens(tokens, token => {
    if (token.type === "link") found.push([token.text, token.href]);
  });
  return found;
}

const gh = "https://github.com/ccch1mneyyyy/dsh-Go/releases/tag/v1.2.0";
const wiki = "https://en.wikipedia.org/wiki/Foo_(bar)";
const bare = (markdown, ...urls) => assert.deepEqual(links(markdown), urls.map(url => [url, url]), markdown);

test("bare URLs stop at Chinese prose punctuation", () => {
  bare(`全角括号包裹（原始问题）：（${gh}）— 高亮和点击都应止于 \`0\`。`, gh);
  bare(`中文句号收尾：新版在这里 ${gh}。 — 不算链接。`, gh);
  bare("中文逗号接后文：https://example.com/a，然后还有字", "https://example.com/a");
  bare("全角感叹号：https://example.com/b！", "https://example.com/b");
  bare("全角问号：是这个吗 https://example.com/j？", "https://example.com/j");
  bare("直角引号：「https://example.com/g」", "https://example.com/g");
  bare("书名号：《https://example.com/h》", "https://example.com/h");
  bare("带查询参数的： （https://example.com/k?x=1&y=2） — 参数完整", "https://example.com/k?x=1&y=2");
});

test("brackets outside the URL are dropped, balanced ones inside survive", () => {
  bare("半角圆括号：(https://example.com/d) — 尾部", "https://example.com/d");
  bare("方括号：［https://example.com/e］ 与 [https://example.com/f] — 都不算", "https://example.com/e", "https://example.com/f");
  bare(`对照：${wiki} — 整个都是链接`, wiki);
  bare(`括号里套平衡括号：（${wiki}）。`, wiki);
  bare("行尾裸链接： https://example.com/i", "https://example.com/i");
});

test("an explicit link keeps its label and destination", () => {
  assert.deepEqual(links(`下载页在 [这里](${gh})，后面直接跟中文。`), [["这里", gh]]);
});
