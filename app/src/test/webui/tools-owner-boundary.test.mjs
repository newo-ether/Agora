import assert from "node:assert/strict";
import fs from "node:fs";
import test from "node:test";
import vm from "node:vm";
import { ICON_CHECK, ICON_CHECK_CIRCLE } from "../../main/assets/webui/icons.js";
import { h, Fragment } from "../../main/assets/webui/vendor/preact.mjs";
import htm from "../../main/assets/webui/vendor/htm.mjs";

const html = htm.bind(h);
const root = new URL("../../main/assets/webui/", import.meta.url);
const todo = id => [{ content: `Todo ${id}`, status: "pending" }];
const state = (openId, seq, items = todo(openId)) => ({ connected: true, connectionId: "session",
  openId, composer: { seq }, todo: items, display: { reduceMotion: false } });

function harness(name) {
  const hooks = [], pending = [];
  let index = 0, dirty = false, props, tree;
  const context = vm.createContext({
    html, Fragment, ToolItem() {}, DetailSheet() {}, InteractionBar() {}, TodoBar() {}, SubagentsBar() {},
    attachScrollbar() { return () => {}; },
    t: { todoTitle: "Todo", todoProgress: (done, total) => `${done}/${total}`,
      todoStatuses: { pending: "Pending", in_progress: "In progress", completed: "Completed" } }, icon: value => value, ICON_CHECK_CIRCLE,
    useRef(initial) { const at = index++; return hooks[at] || (hooks[at] = { current: initial }); },
    useState(initial) {
      const at = index++, hook = hooks[at] || (hooks[at] = { value: initial });
      return [hook.value, next => {
        const value = typeof next === "function" ? next(hook.value) : next;
        if (value !== hook.value) { hook.value = value; dirty = true; }
      }];
    },
    useLayoutEffect(effect, deps) {
      const at = index++, old = hooks[at];
      if (!old || deps.some((value, i) => value !== old.deps[i])) {
        const hook = { deps, cleanup: old?.cleanup }; hooks[at] = hook;
        pending.push(() => { hook.cleanup?.(); hook.cleanup = effect(); });
      }
    },
  });
  const file = name === "TodoBar" ? "todo.js" : "tools-row.js";
  const code = fs.readFileSync(new URL(file, root), "utf8").replace(/^import .*;\r?\n/gm, "").replace(/export /g, "");
  vm.runInContext(code, context, { filename: file });
  const settlements = [];
  if (name === "ChatToolsRow") context.attachToolsRow = (host, reduced, settle) => {
    settlements.push(settle); return () => {};
  };
  function flush() {
    for (let pass = 0; pass < 12; pass++) {
      dirty = false; index = 0; tree = context[name](props);
      pending.splice(0).forEach(effect => effect());
      if (!dirty) return tree;
    }
    throw new Error(`${name} did not settle`);
  }
  return { settlements, flush,
    render(value, enter = true) { props = { state: value, enter }; return flush(); },
    dispose() { hooks.forEach(hook => hook?.cleanup?.()); },
  };
}
const capsule = tree => tree.props.children[0];

test("Todo sheet keeps its title separate from the live trailing progress count", () => {
  const f = harness("TodoBar");
  const items = Array.from({ length: 4 }, (_, index) => ({ content: `Item ${index}`, status: "pending" }));
  const initial = f.render(state("root", 1, items));
  capsule(initial).props.children.props.onClick();
  const sheet = f.flush().props.children[1];
  assert.equal(sheet.props.title, "Todo");
  assert.equal(sheet.props.headerTrailing, "0/4");
  const updated = f.render(state("root", 1, items.map((item, index) =>
    ({ ...item, status: index === 0 ? "completed" : item.status })))).props.children[1];
  assert.equal(updated.props.title, "Todo");
  assert.equal(updated.props.headerTrailing, "1/4");
  assert.equal(updated.type, sheet.type);
  f.dispose();
});

test("Todo completed status uses the Compose filled CheckCircle and keeps its accessible label", () => {
  const f = harness("TodoBar");
  const initial = f.render(state("root", 1, [{ content: "Finished", status: "completed" }]));
  capsule(initial).props.children.props.onClick();
  const sheet = f.flush().props.children[1];
  const row = sheet.props.children.props.children[0];
  const status = row.props.children[0];
  assert.equal(row.props["data-status"], "completed");
  assert.equal(status.props.role, "img");
  assert.equal(status.props["aria-label"], "Completed");
  assert.equal(status.props.children, ICON_CHECK_CIRCLE);
  assert.notEqual(status.props.children, ICON_CHECK);
  // ConversationTodoBar uses Icons.Rounded.CheckCircle.
  assert.equal(ICON_CHECK_CIRCLE, "M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10s10-4.48 10-10S17.52 2 12 2M9.29 16.29L5.7 12.7a.996.996 0 1 1 1.41-1.41L10 14.17l6.88-6.88a.996.996 0 1 1 1.41 1.41l-7.59 7.59a.996.996 0 0 1-1.41 0");
  f.dispose();
});

function rowTools(tree) {
  const viewport = tree.props.children[0];
  return { viewport, children: viewport.props.children.props.children.slice(0, 3) };
}

test("Todo retains only outgoing content then waits at the latest owner entry gate", () => {
  const f = harness("TodoBar");
  const initial = capsule(f.render(state("root", 1)));
  const outgoing = capsule(f.render(state("middle", 2), false));
  assert.equal(outgoing.key, initial.key);
  assert.equal(outgoing.props.active, false);
  assert.equal(outgoing.props.children.props.disabled, true);
  const latest = capsule(f.render(state("latest", 3), false));
  latest.props.onExited();
  const waiting = capsule(f.flush());
  assert.equal(waiting.props.identity, "session:latest:3");
  assert.equal(waiting.props.active, true);
  assert.equal(waiting.props.enter, false);
  assert.notEqual(waiting.key, initial.key);
  const entered = capsule(f.render(state("latest", 3)));
  assert.equal(entered.key, waiting.key);
  assert.equal(entered.props.enter, true);
  f.dispose();
});

test("same-owner Todo updates retain identity while clear and disconnect release outgoing content", () => {
  for (const patch of [{ todo: [] }, { connected: false }, { openId: null }]) {
    const f = harness("TodoBar");
    const initial = capsule(f.render(state("root", 1)));
    const update = capsule(f.render(state("root", 1, [{ content: "Updated", status: "completed" }])));
    assert.equal(update.key, initial.key);
    assert.equal(update.props.active, true);
    const leaving = capsule(f.render({ ...state("root", 1), ...patch }, false));
    assert.equal(leaving.props.active, false);
    leaving.props.onExited();
    assert.equal(f.flush(), null);
    f.dispose();
  }
});

test("same-family navigation waits for new slots under the retained family gate", () => {
  const f = harness("ChatToolsRow");
  const initial = rowTools(f.render(state("root", 1)));
  f.settlements[0]("session:root:1", initial.viewport.props["data-tools-required"]);
  assert.ok(rowTools(f.flush()).children.every(node => node.props.enter));
  for (const [openId, seq] of [["a", 2], ["b", 3], ["root", 4]]) {
    const selected = rowTools(f.render({ ...state(openId, seq), parentConversationId: "root" }));
    assert.equal(selected.viewport.props["data-tools-family"], "session:root");
    assert.equal(selected.viewport.props["data-tools-enter"], "false");
    assert.ok(selected.children.every(node => !node.props.enter));
    f.settlements[0](`session:${openId}:${seq}`, selected.viewport.props["data-tools-required"]);
    assert.ok(rowTools(f.flush()).children.every(node => node.props.enter));
  }
  assert.equal(f.settlements.length, 1);
  f.dispose();
});

test("returning rapidly to the original family keeps its interrupted exit barrier closed", () => {
  const f = harness("ChatToolsRow");
  const initial = rowTools(f.render(state("root", 1)));
  f.settlements[0]("session:root:1", initial.viewport.props["data-tools-required"]);
  f.flush();
  f.render(state("other", 2));
  const returned = rowTools(f.render(state("root", 3)));
  assert.equal(returned.viewport.props["data-tools-enter"], "false");
  assert.ok(returned.children.every(node => !node.props.enter));
  f.settlements[0]("session:other:2", returned.viewport.props["data-tools-required"]);
  assert.ok(rowTools(f.flush()).children.every(node => !node.props.enter));
  f.settlements[0]("session:root:3");
  assert.ok(rowTools(f.flush()).children.every(node => !node.props.enter));
  f.settlements[0]("session:root:3", returned.viewport.props["data-tools-required"]);
  assert.ok(rowTools(f.flush()).children.every(node => node.props.enter));
  f.dispose();
});

test("initial tool paint waits for complete snapshots and measured slots", () => {
  const f = harness("ChatToolsRow");
  for (const mask of [0, 1, 3]) {
    const row = rowTools(f.render({ ...state("root", 1), initialToolsReady: mask }));
    assert.equal(row.viewport.props["data-tools-enter"], "false");
    assert.equal(row.viewport.props["data-tools-required"], undefined);
    assert.ok(row.children.every(node => !node.props.enter));
  }
  const ready = rowTools(f.render({ ...state("root", 1), initialToolsReady: 7 }));
  assert.ok(ready.children.every(node => !node.props.enter));
  f.settlements[0]("session:root:1", ready.viewport.props["data-tools-required"]);
  assert.ok(rowTools(f.flush()).children.every(node => node.props.enter));
  const child = rowTools(f.render({ ...state("child", 2), parentConversationId: "root", initialToolsReady: 0 }));
  assert.equal(child.viewport.props["data-tools-family"], "session:root");
  assert.ok(child.children.every(node => !node.props.enter));
  assert.equal(f.settlements.length, 1);
  f.dispose();
});

test("initial tool paint stays gated in either loading order including empty results", () => {
  for (const order of [[2, 3, 7], [1, 5, 7]]) for (const emptyTodo of [false, true])
    for (const emptyAgents of [false, true]) for (const grouped of [false, true]) {
      const f = harness("ChatToolsRow");
      const complete = { ...state("root", 1, emptyTodo ? [] : todo("root")),
        subagents: emptyAgents ? [] : [{ id: "child", parentConversationId: "root" }],
        display: { subagentCapsuleDisplayMode: grouped ? "grouped" : "individual" } };
      for (const mask of [0, ...order]) {
        const row = rowTools(f.render({ ...complete, initialToolsReady: mask }));
        assert.equal(row.viewport.props["data-tools-enter"], "false");
        assert.ok(row.children.every(node => !node.props.enter));
        if (mask !== 7) assert.equal(row.viewport.props["data-tools-required"], undefined);
        else {
          assert.deepEqual(JSON.parse(row.viewport.props["data-tools-required"]), [
            ...(emptyTodo ? [] : [["todo", "session:root:1"]]),
            ...(emptyAgents ? [] : [[grouped ? "subagents:session:root" : "subagent:child", "session:root"]]),
          ]);
          f.settlements[0]("session:root:1", row.viewport.props["data-tools-required"]);
          assert.ok(rowTools(f.flush()).children.every(node => node.props.enter));
        }
      }
      const child = rowTools(f.render({ ...state("child", 2), parentConversationId: "root", initialToolsReady: 0 }));
      assert.equal(child.viewport.props["data-tools-family"], "session:root");
      assert.ok(child.children.every(node => !node.props.enter));
      f.dispose();
    }
});

test("ChatToolsRow closes the gate for every replacement and rejects obsolete settlement", () => {
  const f = harness("ChatToolsRow");
  const initial = rowTools(f.render(state("root", 1)));
  assert.ok(initial.children.every(node => !node.props.enter));
  f.settlements[0]("session:root:1", initial.viewport.props["data-tools-required"]);
  assert.ok(rowTools(f.flush()).children.every(node => node.props.enter));
  const middle = rowTools(f.render(state("middle", 2)));
  assert.equal(middle.viewport.props["data-tools-enter"], "false");
  assert.ok(middle.children.every(node => !node.props.enter));
  const reattached = rowTools(f.render({ ...state("middle", 2), display: { reduceMotion: true } }));
  assert.equal(f.settlements.length, 2);
  assert.equal(reattached.viewport.props["data-tools-enter"], "false");
  assert.ok(reattached.children.every(node => !node.props.enter));
  const latest = rowTools(f.render({ ...state("latest", 3), display: { reduceMotion: true } }));
  f.settlements[1]("session:middle:2", reattached.viewport.props["data-tools-required"]);
  assert.ok(rowTools(f.flush()).children.every(node => !node.props.enter));
  f.settlements[1]("session:latest:3", reattached.viewport.props["data-tools-required"]);
  assert.ok(rowTools(f.flush()).children.every(node => !node.props.enter));
  f.settlements[1]("session:latest:3", latest.viewport.props["data-tools-required"]);
  assert.ok(rowTools(f.flush()).children.every(node => node.props.enter));
  assert.equal(f.settlements.length, 2);
  f.dispose();
});
