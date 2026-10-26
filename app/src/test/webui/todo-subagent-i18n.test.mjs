import assert from "node:assert/strict";
import fs from "node:fs";
import test from "node:test";
import vm from "node:vm";
import { h } from "../../main/assets/webui/vendor/preact.mjs";
import htm from "../../main/assets/webui/vendor/htm.mjs";

const read = name => fs.readFileSync(new URL(`../../main/assets/webui/${name}`, import.meta.url), "utf8");
const prepare = source => source.replace(/^import .*;\r?\n/gm, "").replace(/export /g, "");
function strings(language) {
  const context = vm.createContext({ navigator: { language }, document: { documentElement: {} } });
  vm.runInContext(prepare(read("i18n.js")) + "\nglobalThis.result = t;", context);
  return { t: context.result, lang: context.document.documentElement.lang };
}

test("browser Todo and Subagent labels keep complete en and zh statuses", () => {
  for (const [language, expectedLang] of [["en-US", "en"], ["zh-CN", "zh"], ["zh-TW", "zh"], ["ja-JP", "en"]]) {
    const { t, lang } = strings(language);
    assert.equal(lang, expectedLang);
    assert.deepEqual(Object.keys(t.todoStatuses).sort(), ["completed", "in_progress", "pending"]);
    assert.deepEqual(Object.keys(t.subagentStatuses).sort(), ["completed", "failed", "idle", "running", "stopped"]);
    for (const label of [t.todoTitle, t.sourceSubagent, t.backToParent, ...Object.values(t.todoStatuses), ...Object.values(t.subagentStatuses)]) {
      assert.equal(typeof label, "string");
      assert.ok(label.length);
      if (lang === "zh") assert.match(label, /[\u3400-\u9fff]/);
    }
    assert.equal(t.todoProgress(1, 3), "1 / 3");
    assert.equal(t.subagentsCount(3), lang === "zh" ? "3 个子代理" : "3 Subagents");
    assert.equal(t.subagentsRunningCount(1), lang === "zh" ? "1 个运行中" : "1 Running");
    assert.equal(t.subagentsRunningCount(2), lang === "zh" ? "2 个运行中" : "2 Running");
  }
  assert.equal(strings("zh").t.sourceSubagent, "子代理");
});

test("tool card icons consume projected kinds without translating protocol identifiers", () => {
  const context = vm.createContext({
    html: htm.bind(h), icon: (path, viewBox) => ({ path, viewBox }),
    ICON_BUILD: "tool", ICON_CHECKLIST: "todo", ICON_ACCOUNT_TREE: "subagent",
    ICON_IMAGE: "image", ICON_MOBILE_HAND: "phone", ICON_NEUROLOGY: "thinking",
  });
  vm.runInContext(prepare(read("detail-sheet.js")), context);
  for (const kind of ["TODO_READ", "TODO_UPDATE"]) {
    assert.equal(context.CardIcon({ kind: "TOOL", toolKind: kind }).path, "todo");
  }
  for (const kind of ["SUBAGENT_SPAWN", "SUBAGENT_SEND", "SUBAGENT_WAIT", "SUBAGENT_LIST", "SUBAGENT_STOP"]) {
    assert.equal(context.CardIcon({ kind: "TOOL", toolKind: kind }).path, "subagent");
  }
  assert.equal(context.CardIcon({ kind: "TOOL", toolKind: "PHONE" }).path, "phone");
  assert.equal(context.CardIcon({ kind: "TOOL", toolKind: "PHONE" }).viewBox, "0 0 24 24");
  for (const kind of [undefined, "UNKNOWN", "MCP", "FILE_READ"]) {
    assert.equal(context.CardIcon({ kind: "TOOL", toolKind: kind }).path, "tool");
  }
  assert.equal(context.CardIcon({ kind: "THINKING" }).viewBox, "0 0 960 960");
});
