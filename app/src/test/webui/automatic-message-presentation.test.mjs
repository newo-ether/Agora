import assert from "node:assert/strict";
import fs from "node:fs";
import test from "node:test";
import vm from "node:vm";
import { h } from "../../main/assets/webui/vendor/preact.mjs";
import htm from "../../main/assets/webui/vendor/htm.mjs";

const root = new URL("../../main/assets/webui/", import.meta.url);
const source = name => fs.readFileSync(new URL(name, root), "utf8");
const prepare = code => code.replace(/^import\b[\s\S]*?;\r?\n/gm, "").replace(/\bexport /g, "");
const body = "Subagent 123e4567-e89b-12d3-a456-426614174000 (Alpha) finished (completed):\n\nFull hidden result\nWith another line";
const receipt = (result = {}) => ({ kind: "subagent", subagentResult: {
  name: "Alpha", status: "completed", ...result,
} });
const message = (patch = {}) => ({ id: "row", participant: "USER", text: { markdown: body }, ...patch });

function nodes(tree) {
  if (Array.isArray(tree)) return tree.flatMap(nodes);
  if (!tree || typeof tree !== "object" || !tree.props) return [];
  return [tree, ...nodes(tree.props.children)];
}
const text = tree => Array.isArray(tree) ? tree.map(text).join("")
  : tree?.props ? text(tree.props.children) : typeof tree === "string" ? tree : "";

// Run the production component/functions with the bundled parser and VNodes. The small DOM stand-in
// mounts text refs so the real body effects run; browser geometry and animation are outside this gate.
function harness(name = "UserBubble", language = "en-US") {
  const hooks = [], pending = [], commands = [], copied = [], highlights = [];
  let cursor = 0, dirty, props, tree, refs = new Set();
  function effect(callback, deps) {
    const at = cursor++, previous = hooks[at];
    if (!previous || !deps || deps.some((value, i) => value !== previous.deps?.[i])) {
      const hook = { deps, cleanup: previous?.cleanup };
      hooks[at] = hook;
      pending.push(() => { hook.cleanup?.(); hook.cleanup = callback(); });
    }
  }
  function dom() {
    return { dataset: {}, textContent: "", children: [], focus() {}, contains() { return false; },
      replaceChildren() { this.children = []; this.textContent = ""; },
      append(...children) { this.children.push(...children); },
    };
  }
  const context = vm.createContext({
    html: htm.bind(h), navigator: { language, clipboard: { writeText: async value => copied.push(value) } },
    document: { createElement: dom, documentElement: {} }, CSS: { highlights: new Map() },
    localStorage: { getItem: () => null },
    useRef(initial) { const at = cursor++; return hooks[at] || (hooks[at] = { current: initial }); },
    useState(initial) {
      const at = cursor++, hook = hooks[at] || (hooks[at] = {
        value: typeof initial === "function" ? initial() : initial,
      });
      return [hook.value, next => {
        const value = typeof next === "function" ? next(hook.value) : next;
        if (value !== hook.value) { hook.value = value; dirty = true; }
      }];
    },
    useEffect: effect, useLayoutEffect: effect, useDialog: (_dialog, close) => close,
    highlightSearch(node, search, matches) { highlights.push({ node, search, matches }); },
    MoreMenu() {}, DetailSheet() {}, AttachmentViewer() {}, CircularProgress() {}, Spinner() {},
    sync: { rowCommand(...args) { commands.push(args); return true; }, notify() {} },
  });
  for (const file of ["icons.js", "i18n.js", "message-actions.js", "message-collapse.js", "message-user.js"]) {
    vm.runInContext(prepare(source(file)), context, { filename: file });
  }
  function render(nextProps = props) {
    props = nextProps;
    for (let pass = 0; pass < 10; pass++) {
      cursor = 0; dirty = false;
      tree = context[name](props);
      const current = new Set(nodes(tree).filter(node => typeof node.type === "string" && node.ref).map(node => node.ref));
      for (const ref of refs) if (!current.has(ref)) ref.current = null;
      for (const ref of current) if (!ref.current) ref.current = dom();
      refs = current;
      pending.splice(0).forEach(callback => callback());
      if (!dirty) return tree;
    }
    throw new Error(`${name} did not settle`);
  }
  return {
    render, commands, copied, highlights,
    get tree() { return tree; },
    find(predicate) { const node = nodes(tree).find(predicate); assert.ok(node, `expected ${name} control`); return node; },
    component: component => nodes(tree).find(node => node.type?.name === component),
    value: expression => vm.runInContext(expression, context),
    dispose() { hooks.forEach(hook => hook?.cleanup?.()); },
  };
}
function bubble(patch = {}, language) {
  const f = harness("UserBubble", language);
  f.render({ message: message(), generating: false, matches: [], reduceMotion: true,
    conversationId: "conversation", onDelete() {}, ...patch });
  return f;
}
function openMenu(f) {
  f.find(node => node.type === "button" && node.props["aria-haspopup"] === "menu").props.onClick();
  f.render();
  return nodes(f.component("MoreMenu").props.children);
}
const menuItem = (items, label) => items.find(node => node.type?.name === "RowMenuItem" && node.props.label === label);
const mountedText = f => f.find(node => node.type === "div" && node.props.class === "user-text").ref.current;

test("long ordinary input keeps the full copy body and attachment strip outside its viewport", () => {
  const raw = Array.from({ length: 30 }, (_, index) => `Original line ${index}`).join("\n");
  const f = bubble({ message: message({ text: { markdown: raw },
    attachments: [{ index: 0, type: "file", fileName: "report.txt", unavailable: true }] }) });
  const preview = f.component("CollapsibleMessageBody");
  assert.ok(preview);
  assert.equal(preview.props.messageKey, "row");
  assert.equal(preview.props.searchActive, false);
  assert.equal(mountedText(f).textContent, raw);
  assert.equal(nodes(preview.props.children).some(node => node.props.class === "user-attachments"), false);
  menuItem(openMenu(f), "Copy").props.onClick();
  assert.equal(f.copied[0], raw);
  f.dispose();
});

test("completion timeline has compact metadata only, without a hidden body or expansion action", () => {
  const f = bubble({ message: message({ source: receipt() }), search: { query: "hidden", matches: [], index: 0 },
    matches: [{ key: "body:0", start: 90, endExclusive: 96 }] });
  assert.equal(f.find(node => node.props.class === "user-bubble subagent-result-bubble").type, "div");
  const result = f.component("SubagentResult");
  assert.ok(result);
  assert.equal(nodes(f.tree).some(node => node.ref && node.props.class === "user-text"), false);
  assert.equal(f.highlights.length, 0);
  assert.doesNotMatch(text(f.tree), /Full hidden result|123e4567/);
  assert.equal(nodes(f.tree).some(node => node.props["aria-expanded"] && node.type !== "button"), false);
  const card = harness("SubagentResult");
  const tree = card.render(result.props);
  assert.equal(tree.props.role, "group");
  assert.equal(tree.props["aria-label"], "Subagent");
  assert.equal(text(nodes(tree).find(node => node.props.class === "subagent-result-name")), "Alpha");
  assert.equal(text(nodes(tree).find(node => node.props.class === "subagent-result-status")), "Completed");
  assert.equal(nodes(tree).some(node => node.type === "button" || node.type === "details"), false);
  assert.equal(nodes(tree).some(node => node.props.class === "subagent-result-error"), false);
  f.dispose(); card.dispose();
});

test("statuses stay localized while failure details and codes remain hidden in every state", () => {
  for (const [language, expected] of [["en-US", "Failed"], ["zh-CN", "失败"]]) {
    const f = harness("SubagentResult", language);
    const failed = f.render({ result: { name: "Alpha", status: "failed", errorText: "Quota exceeded", errorCode: "429" } });
    assert.equal(text(nodes(failed).find(node => node.props.class === "subagent-result-status")), expected);
    for (const status of ["failed", "stopped", "completed", "idle", "running"]) {
      const tree = f.render({ result: { name: "Alpha", status, errorText: "Quota exceeded", errorCode: "429" } });
      assert.equal(text(nodes(tree).find(node => node.props.class === "subagent-result-name")), "Alpha");
      assert.ok(text(nodes(tree).find(node => node.props.class === "subagent-result-status")));
      assert.doesNotMatch(text(tree), /Quota exceeded|429/);
      assert.equal(nodes(tree).some(node => ["subagent-result-error", "subagent-result-code"].includes(node.props.class)), false);
    }
    const codeOnly = f.render({ result: { name: "Alpha", status: "failed", errorText: " ", errorCode: "500" } });
    assert.doesNotMatch(text(codeOnly), /500/);
    const unknown = f.render({ result: { name: " ", status: "" } });
    assert.equal(text(unknown).trim(), language === "en-US" ? "Subagent" : "子代理");
    assert.doesNotMatch(text(unknown), /Completed|已完成/);
    f.dispose();
  }
});

test("Copy and existing Select Text retain the exact original multiline receipt, with Info/Delete available", async () => {
  let deleted = 0;
  const f = bubble({ message: message({ source: receipt() }), onDelete: () => deleted++ });
  const items = openMenu(f);
  assert.deepEqual(items.filter(node => node.type?.name === "RowMenuItem").map(node => node.props.label),
    ["Copy", "Edit", "Select Text", "Info", "Delete"]);
  menuItem(items, "Copy").props.onClick();
  await Promise.resolve();
  assert.deepEqual(f.copied, [body]);
  const selecting = menuItem(openMenu(f), "Select Text");
  assert.equal(selecting.props.enabled, true);
  selecting.props.onClick(); f.render();
  f.component("MoreMenu").props.onExited(false); f.render();
  const sheet = f.component("DetailSheet");
  assert.equal(sheet.props.title, "Select Text");
  assert.equal(text(sheet.props.children), body);
  const actions = openMenu(f);
  menuItem(actions, "Info").props.onClick(); f.render();
  assert.ok(f.component("MessageInfoDialog"));
  menuItem(actions, "Delete").props.onClick();
  assert.equal(deleted, 1);
  assert.equal(f.commands.length, 0);
  f.dispose();
});

test("every automatic source retains disabled hover/menu Edit and rejects direct callback admission", () => {
  for (const kind of ["task", "loop", "ask_user", "subagent", "future_kind"]) {
    const f = bubble({ message: message({ source: { kind, askUser: [{ question: "Q", answer: "A" }] } }) });
    const hover = f.component("RowAction");
    assert.equal(hover.props.enabled, false, kind);
    hover.props.onClick(); f.render();
    const edit = menuItem(openMenu(f), "Edit");
    assert.equal(edit.props.enabled, false, kind);
    edit.props.onClick(); f.render();
    assert.equal(f.component("MessageEditDialog"), undefined, kind);
    assert.equal(f.commands.length, 0);
    const control = harness("RowMenuItem");
    assert.equal(control.render(edit.props).props.disabled, true);
    control.dispose(); f.dispose();
  }
});

test("Subagent instructions stay visible and searchable; ask_user keeps its existing question/answer text", () => {
  const matches = [{ key: "body:0", start: 0, endExclusive: 4 }];
  const f = bubble({ message: message({ text: { markdown: "Task instruction" }, source: { kind: "subagent", subagentResult: null } }),
    matches, search: { query: "Task", matches, index: 0 } });
  assert.equal(f.component("SubagentResult"), undefined);
  assert.ok(f.find(node => node.props.class === "user-bubble"));
  assert.equal(mountedText(f).textContent, "Task instruction");
  assert.equal(f.highlights.length, 1);
  assert.equal(f.highlights[0].matches, matches);
  f.dispose();
  const ask = bubble({ message: message({ source: { kind: "ask_user", askUser: [
    { question: "Question", answer: "Answer" }, { question: "Skipped", answer: null },
  ] } }) });
  assert.equal(mountedText(ask).children.map(node => node.textContent).join("|"), "Question|Answer|Skipped|Unanswered");
  assert.equal(ask.value('readableText({ source: { kind: "ask_user", askUser: [{ question: "Q", answer: "A" }] } })'), "Q\nA");
  ask.dispose();
});

test("ordinary typed rows remain editable, while generating rows remain disabled", () => {
  const f = bubble({ message: message({ source: null }) });
  assert.equal(mountedText(f).textContent, body);
  assert.equal(f.component("RowAction").props.enabled, true);
  const edit = menuItem(openMenu(f), "Edit");
  assert.equal(edit.props.enabled, true);
  edit.props.onClick(); f.render();
  const props = f.component("MessageEditDialog").props;
  const dialog = harness("MessageEditDialog");
  dialog.render(props);
  const field = dialog.find(node => node.type === "textarea");
  field.props.onInput({ currentTarget: { value: "Edited task" } }); dialog.render();
  dialog.find(node => node.type === "button" && text(node) === "Save").props.onClick();
  assert.equal(dialog.commands.length, 1);
  assert.equal(dialog.commands[0][0], "edit");
  assert.equal(dialog.commands[0][1], "row");
  assert.equal(dialog.commands[0][2].text, "Edited task");
  const busy = bubble({ generating: true });
  assert.equal(busy.component("RowAction").props.enabled, false);
  assert.equal(menuItem(openMenu(busy), "Edit").props.enabled, false);
  busy.dispose(); dialog.dispose(); f.dispose();
});

test("a source update closes the old editor and rejects retained hover/menu/save callbacks", () => {
  for (const source of [receipt(), { kind: "task" }, { kind: "ask_user", askUser: [] }]) {
    const f = bubble();
    const oldHover = f.component("RowAction").props.onClick;
    const oldMenu = menuItem(openMenu(f), "Edit").props.onClick;
    oldHover(); f.render();
    const dialog = harness("MessageEditDialog");
    dialog.render(f.component("MessageEditDialog").props);
    const save = dialog.find(node => node.type === "button" && text(node) === "Save").props.onClick;
    f.render({ message: message({ source }), generating: false, matches: [], conversationId: "conversation", onDelete() {} });
    assert.equal(f.component("MessageEditDialog"), undefined);
    oldHover(); oldMenu(); f.render(); save();
    assert.equal(f.component("MessageEditDialog"), undefined);
    assert.equal(dialog.commands.length, 0);
    f.dispose(); dialog.dispose();
  }
});

test("an automatic message cannot be saved even if an outdated dialog is invoked directly", () => {
  const f = harness("MessageEditDialog");
  f.render({ message: message({ source: receipt() }), canEdit: () => true, onClose() {} });
  f.find(node => node.type === "button" && text(node) === "Save").props.onClick();
  assert.equal(f.commands.length, 0);
  f.dispose();
});

test("receipt updates preserve attachments and remove the old body ref without exposing hidden search glyphs", () => {
  const f = bubble();
  const oldText = mountedText(f);
  f.render({ message: message({ source: receipt(), attachments: [{ index: 0, type: "file", fileName: "report.txt", unavailable: true }] }),
    generating: false, matches: [], conversationId: "conversation", onDelete() {} });
  assert.ok(f.component("SubagentResult"));
  assert.equal(nodes(f.tree).some(node => node.ref?.current === oldText), false);
  assert.equal(f.find(node => node.props.class === "user-attachments").props["data-body-gap"], true);
  assert.match(text(f.tree), /report\.txt/);
  f.dispose();
});
