import assert from "node:assert/strict";
import fs from "node:fs";
import test from "node:test";
import vm from "node:vm";
import { h } from "../../main/assets/webui/vendor/preact.mjs";
import htm from "../../main/assets/webui/vendor/htm.mjs";

const source = fs.readFileSync(new URL("../../main/assets/webui/detail-sheet.js", import.meta.url), "utf8");
const context = vm.createContext({ html: htm.bind(h) });
vm.runInContext(source.replace(/^import .*;\r?\n/gm, "").replace(/export /g, ""), context);

function elements(node) {
  if (Array.isArray(node)) return node.flatMap(elements);
  if (node == null || typeof node === "boolean") return [];
  if (typeof node !== "object") return [node];
  if (typeof node.type === "function") return elements(node.type(node.props));
  return [node, ...elements(node.props?.children)];
}
function visibleText(nodes) {
  return nodes.filter(node => typeof node === "string" || typeof node === "number").join("");
}

test("file transfer active and failure details retain the shared projection as text", () => {
  for (const [type, text, expectedClass] of [
    ["active", "Transferring Local Sandbox:/source.bin → Quantum:/target.bin…", "tool-active"],
    ["active", "正在传输 Local Sandbox:/source.bin → Quantum:/target.bin…", "tool-active"],
    ["failed", "Destination already exists; overwrite is false", "tool-terminal"],
    ["failed", "Transfer abc commit outcome is unknown; inspect Quantum:/Target.bin before retrying", "tool-terminal"],
    ["failed", "permission denied: /<img src=x onerror=alert(1)>.bin", "tool-terminal"],
    ["failed", "无法确认文件传输是否完成", "tool-terminal"],
  ]) {
    const nodes = elements(context.ToolBody({ body: { type, text } }));
    assert.equal(visibleText(nodes), text);
    assert.ok(nodes.some(node => node.props?.class === expectedClass));
    assert.ok(nodes.every(node => typeof node !== "object" || node.props?.dangerouslySetInnerHTML == null));
    assert.ok(nodes.every(node => typeof node !== "object" || node.type !== "img"));
  }
});

test("file transfer completed details preserve receipt and zero-byte evidence", () => {
  for (const status of ["copied", "unchanged"]) {
    const receipt = JSON.stringify({ ok: true, status, bytes: 0, source_path: "/Source.bin", destination_path: "/Target.bin" });
    const nodes = elements(context.ToolBody({ body: { type: "documents", values: [{ text: receipt }] } }));
    assert.equal(visibleText(nodes), receipt);
    assert.ok(nodes.some(node => node.props?.class === "tool-result-document"));
    assert.ok(nodes.every(node => !["tool-active", "tool-terminal"].includes(node.props?.class)));
  }
});

test("file transfer stopped details remain stopped", () => {
  const text = "Stopped file transfer";
  const nodes = elements(context.ToolBody({ body: { type: "stopped", text } }));
  assert.equal(visibleText(nodes), text);
  assert.ok(nodes.some(node => node.props?.class === "tool-terminal stopped"));
});

test("all file transfer locales retain keys, indexed placeholder types and progressive wording", () => {
  const root = new URL("../../main/res/", import.meta.url);
  const locales = fs.readdirSync(root).filter(name => fs.existsSync(new URL(`${name}/file_transfer_strings.xml`, root)));
  assert.equal(locales.length, 12);
  const expected = new Map([
    ["tool_file_transfer", []],
    ["tool_transferring_file", ["%1$s"]],
    ["tool_transferring_file_default", []],
    ["tool_transferred_file", ["%1$s", "%2$d"]],
    ["tool_transfer_unchanged", ["%1$s"]],
    ["tool_transfer_failed", []],
    ["tool_transfer_unconfirmed", []],
  ]);
  for (const locale of locales) {
    const xml = fs.readFileSync(new URL(`${locale}/file_transfer_strings.xml`, root), "utf8");
    const entries = [...xml.matchAll(/<string name="([^"]+)">([^<]*)<\/string>/g)];
    assert.deepEqual(entries.map(entry => entry[1]).sort(), [...expected.keys()].sort(), locale);
    for (const [, key, value] of entries) {
      assert.deepEqual((value.match(/%\d+\$[sd]/g) ?? []).sort(), expected.get(key), `${locale}:${key}`);
      if (key.startsWith("tool_transferring_")) assert.ok(value.endsWith("…"), `${locale}:${key}`);
    }
  }
});
