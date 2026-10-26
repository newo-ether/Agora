import assert from "node:assert/strict";
import fs from "node:fs";
import test from "node:test";
import vm from "node:vm";
import { Fragment, h } from "../../main/assets/webui/vendor/preact.mjs";
import htm from "../../main/assets/webui/vendor/htm.mjs";

const html = htm.bind(h);
const source = fs.readFileSync(new URL("../../main/assets/webui/subagents.js", import.meta.url), "utf8");
function harness(name, extra = {}) {
  const hooks = [], pending = [], opens = [];
  let index = 0, dirty = false, props, tree;
  const context = vm.createContext({
    html, Fragment, sync: { open: id => opens.push(id) }, DetailSheet() {}, ToolItem() {}, Spinner() {}, icon: value => value,
    ICON_ACCOUNT_TREE: "tree", ICON_ERROR: "error", ICON_PAUSE_CIRCLE_OUTLINE: "pause-circle-outline",
    t: { sourceSubagent: "Subagent", subagentsCount: count => `${count} Subagents`,
      subagentsRunningCount: count => `${count} Running`,
      subagentStatuses: { idle: "Idle", running: "Running", completed: "Completed", failed: "Failed", stopped: "Stopped" } },
    useRef(value) {
      const at = index++;
      return hooks[at] || (hooks[at] = { current: value });
    },
    useState(initial) {
      const at = index++;
      const hook = hooks[at] || (hooks[at] = { value: initial });
      return [hook.value, next => {
        const value = typeof next === "function" ? next(hook.value) : next;
        if (value !== hook.value) { hook.value = value; dirty = true; }
      }];
    },
    useLayoutEffect(effect, deps) {
      const at = index++, old = hooks[at];
      if (!old || deps.some((value, i) => value !== old.deps[i])) {
        const hook = { deps, cleanup: old?.cleanup };
        hooks[at] = hook;
        pending.push(() => { hook.cleanup?.(); hook.cleanup = effect(); });
      }
    },
    ...extra,
  });
  const prepare = code => code.replace(/^import .*;\r?\n/gm, "").replace(/export /g, "");
  vm.runInContext(prepare(fs.readFileSync(new URL("../../main/assets/webui/tool-item.js", import.meta.url), "utf8")), context);
  vm.runInContext(prepare(source), context);
  const flush = () => {
    for (let passes = 0; passes < 10; passes++) {
      dirty = false; index = 0;
      tree = context[name](props);
      if (extra.commit) extra.commit(tree);
      pending.splice(0).forEach(effect => effect());
      if (!dirty) return tree;
    }
    throw new Error("component did not settle");
  };
  return { opens, render(value) { props = value; return flush(); }, flush,
    dispose() { hooks.forEach(hook => hook?.cleanup?.()); } };
}
const agents = [{ id: "a", title: "Alpha", status: "idle" }, { id: "b", title: "Beta", status: "running" }];
const state = (openId, parentConversationId, subagents = agents, seq = 1) => ({
  connected: true, connectionId: "session", openId, parentConversationId, subagents, composer: { seq },
});
const button = vnode => vnode.props.children;
const groupedState = (subagents = agents, patch = {}) => ({
  ...state("root", null, subagents), display: { subagentCapsuleDisplayMode: "grouped" }, ...patch,
});
const group = tree => tree.props.children[0];
const sheet = tree => tree.props.children[1];
const choices = tree => sheet(tree).props.children.props.children;
const title = tree => button(group(tree)).props.children[1].props.children[1].props.title;
const status = tree => button(group(tree)).props.children[0].props.status;

test("grouped mode hides zero, directly opens one, and keeps the capsule key across counts", () => {
  const f = harness("SubagentsBar");
  assert.equal(f.render({ state: groupedState([]) }), null);
  const single = f.render({ state: groupedState(agents.slice(0, 1)) });
  assert.equal(title(single), "Alpha");
  button(group(single)).props.onClick();
  assert.deepEqual(f.opens, ["a"]);
  const multiple = f.render({ state: groupedState() });
  assert.equal(group(multiple).key, group(single).key);
  assert.equal(group(multiple).props.toolKey, group(single).props.toolKey);
  assert.equal(title(multiple), "1 Running");
  button(group(multiple)).props.onClick();
  const opened = f.flush();
  assert.equal(choices(opened).length, 2);
  assert.deepEqual(f.opens, ["a"]);
  const three = f.render({ state: groupedState([...agents, { id: "c", title: "Gamma", status: "idle" }]) });
  assert.equal(title(three), "1 Running");
  assert.equal(group(three).key, group(single).key);
  assert.equal(choices(three).length, 3);
  f.dispose();
});

test("one grouped child keeps its name and direct navigation through every status", () => {
  const f = harness("SubagentsBar");
  let key;
  for (const value of ["idle", "running", "completed", "failed", "stopped"]) {
    const child = { id: "a", title: `Worker ${value}`, status: value };
    const tree = f.render({ state: groupedState([child]) });
    key ??= group(tree).key;
    assert.equal(group(tree).key, key);
    assert.equal(title(tree), child.title);
    assert.equal(status(tree), value);
    button(group(tree)).props.onClick();
    assert.equal(sheet(f.flush()), false);
  }
  assert.deepEqual(f.opens, ["a", "a", "a", "a", "a"]);
  f.dispose();
});
test("grouped counts follow running children and return to the total without replacing the family", () => {
  const f = harness("SubagentsBar");
  const children = [...agents, { id: "c", title: "Gamma", status: "stopped" }];
  const initial = f.render({ state: groupedState(children) });
  assert.equal(title(initial), "1 Running");
  const two = f.render({ state: groupedState(children.map(agent => agent.id === "a" ? { ...agent, status: "running" } : agent)) });
  assert.equal(title(two), "2 Running");
  assert.equal(status(two), "running");
  assert.equal(group(two).key, group(initial).key);
  const terminal = f.render({ state: groupedState(children.map(agent => ({ ...agent, status: "completed" }))) });
  assert.equal(title(terminal), "3 Subagents");
  assert.equal(status(terminal), "idle");
  assert.equal(group(terminal).key, group(initial).key);
  f.dispose();
});
test("grouped status aggregates any running child and uses the tree state for all terminal children", () => {
  const f = harness("SubagentsBar");
  assert.equal(status(f.render({ state: groupedState() })), "running");
  const terminal = agents.map((agent, index) => ({ ...agent, status: index ? "failed" : "stopped" }));
  assert.equal(status(f.render({ state: groupedState(terminal) })), "idle");
  assert.equal(status(f.render({ state: groupedState(terminal.slice(1)) })), "failed");
  f.dispose();
});

test("sheet selects the current child and navigates once after the shared sheet has closed", () => {
  const f = harness("SubagentsBar");
  const parent = f.render({ state: groupedState() });
  assert.equal(button(group(parent)).props["aria-current"], null);
  const childState = groupedState(agents, { openId: "b", parentConversationId: "root" });
  const child = f.render({ state: childState });
  assert.equal(group(child).key, group(parent).key);
  assert.equal(button(group(child)).props["aria-current"], "page");
  button(group(child)).props.onClick();
  let opened = f.flush();
  assert.equal(choices(opened)[0].props["aria-current"], null);
  assert.equal(choices(opened)[1].props["aria-current"], "page");
  choices(opened)[0].props.onClick();
  opened = f.flush();
  assert.equal(sheet(opened).props.closing, true);
  assert.ok(choices(opened).every(choice => choice.props.disabled));
  assert.deepEqual(f.opens, []);
  choices(opened)[1].props.onClick();
  sheet(f.flush()).props.onClose();
  assert.equal(sheet(f.flush()), false);
  assert.deepEqual(f.opens, ["a"]);
  const returned = f.render({ state: groupedState() });
  assert.equal(button(group(returned)).props["aria-current"], null);
  f.dispose();
});

test("sheet closes and pending selection is discarded when family, connection, mode or count invalidates it", () => {
  for (const patch of [
    { openId: "other" }, { connectionId: "other" }, { connected: false }, { openId: null },
    { display: { subagentCapsuleDisplayMode: "individual" } }, { subagents: agents.slice(0, 1) },
  ]) {
    const f = harness("SubagentsBar");
    const initial = f.render({ state: groupedState() });
    button(group(initial)).props.onClick();
    choices(f.flush())[0].props.onClick();
    f.flush();
    const invalidated = f.render({ state: groupedState(agents, patch) });
    assert.equal(sheet(invalidated), false);
    assert.deepEqual(f.opens, []);
    const restored = f.render({ state: groupedState() });
    assert.equal(sheet(restored), false);
    f.dispose();
  }
});

test("outgoing grouped paint is inert until exit and only the latest target is admitted", () => {
  const f = harness("SubagentsBar");
  const initial = f.render({ state: groupedState() });
  const outgoing = f.render({ state: groupedState(agents, { openId: "other" }) });
  assert.equal(group(outgoing).props.active, false);
  assert.equal(button(group(outgoing)).props.disabled, true);
  button(group(outgoing)).props.onClick();
  assert.equal(sheet(f.flush()), false);
  const latest = f.render({ state: groupedState(agents, { openId: "latest" }) });
  group(latest).props.onExited();
  const entered = f.flush();
  assert.notEqual(group(entered).key, group(initial).key);
  assert.equal(group(entered).props.active, true);
  assert.match(group(entered).props.identity, /latest$/);
  assert.deepEqual(f.opens, []);
  f.dispose();
});

test("parent and sibling selection retain capsule keys and update selected state without entrance", () => {
  const f = harness("SubagentsBar");
  const parent = f.render({ state: state("root", null) });
  const keys = Array.from(parent, node => node.key);
  assert.ok(parent.every(node => node.props.active));
  const child = f.render({ state: state("a", "root", agents, 2) });
  assert.deepEqual(Array.from(child, node => node.key), keys);
  assert.ok(child.every(node => node.props.active));
  assert.equal(button(child[0]).props["aria-current"], "page");
  assert.equal(button(child[1]).props["aria-current"], null);
  const sibling = f.render({ state: state("b", "root", agents, 3) });
  assert.deepEqual(Array.from(sibling, node => node.key), keys);
  assert.equal(button(sibling[0]).props["aria-current"], null);
  assert.equal(button(sibling[1]).props["aria-current"], "page");
  button(sibling[0]).props.onClick();
  assert.deepEqual(f.opens, ["a"]);
  f.dispose();
});

test("live payload changes keep keys and removed capsules become inert until their exit", () => {
  const f = harness("SubagentsBar");
  const initial = f.render({ state: state("root", null) });
  const latest = [{ ...agents[1], title: "Updated", status: "completed" }];
  const tree = f.render({ state: state("root", null, latest) });
  assert.equal(tree[0].key, initial[0].key);
  assert.equal(tree[0].props.active, false);
  assert.equal(button(tree[0]).props.disabled, true);
  button(tree[0]).props.onClick();
  assert.deepEqual(f.opens, []);
  assert.match(button(tree[1]).props["aria-label"], /Updated, Completed/);
  tree[0].props.onExited();
  const settled = f.flush();
  assert.equal(settled.length, 1);
  assert.equal(settled[0].key, initial[1].key);
  f.dispose();
});

test("unrelated families retain only outgoing inert paint and enter the latest target", () => {
  const f = harness("SubagentsBar");
  f.render({ state: state("root", null) });
  f.render({ state: state("other", null, [{ id: "c", title: "Other", status: "idle" }]) });
  const outgoing = f.render({ state: state("latest", null, [{ id: "d", title: "Latest", status: "idle" }]) });
  assert.ok(outgoing.every(node => !node.props.active));
  outgoing.forEach(node => button(node).props.onClick());
  assert.deepEqual(f.opens, []);
  outgoing.forEach(node => node.props.onExited());
  const entered = f.flush();
  assert.equal(entered.length, 1);
  assert.equal(entered[0].props.toolKey, "subagent:d");
  assert.equal(entered[0].props.active, true);
  f.dispose();
});

test("disconnect, New Chat and reconnect invalidate the old family even with identical child IDs", () => {
  for (const patch of [{ connected: false }, { openId: null }, { connectionId: "new-session" }]) {
    const f = harness("SubagentsBar");
    const initial = f.render({ state: state("root", null) });
    const outgoing = f.render({ state: { ...state("root", null), ...patch } });
    assert.ok(outgoing.every(node => !node.props.active));
    outgoing.forEach(node => node.props.onExited());
    const settled = f.flush();
    if (patch.connectionId) {
      assert.ok(settled.every(node => node.props.active));
      assert.notEqual(settled[0].key, initial[0].key);
    } else assert.equal(settled, null);
    f.dispose();
  }
});

test("same-family capsules retain admitted node identity through a closed conversation gate", () => {
  const f = harness("SubagentsBar");
  const parent = f.render({ state: state("root", null) });
  const child = f.render({ state: state("a", "root", agents, 2), enter: false });
  assert.deepEqual(Array.from(child, node => node.key), Array.from(parent, node => node.key));
  assert.ok(child.every(node => node.props.active && node.props.retain && !node.props.enter));
  assert.equal(button(child[0]).props["aria-current"], "page");
  const other = f.render({ state: state("other", null, [{ id: "c", title: "Other", status: "idle" }], 3), enter: false });
  assert.ok(other.every(node => !node.props.active));
  other.forEach(node => node.props.onExited());
  const waiting = f.flush();
  assert.equal(waiting[0].props.toolKey, "subagent:c");
  assert.equal(waiting[0].props.enter, false);
  assert.notEqual(waiting[0].key, parent[0].key);
  f.dispose();
});

test("selection colors fade for 250ms without spatial or Reduced Motion overrides", () => {
  const css = fs.readFileSync(new URL("../../main/assets/webui/style.css", import.meta.url), "utf8");
  const declarations = selector => {
    const escaped = selector.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
    const matches = [...css.matchAll(new RegExp(`(?:^|\\n)${escaped}\\s*\\{([^}]*)\\}`, "g"))];
    assert.equal(matches.length, 1, selector);
    return new Map(matches[0][1].split(";").filter(part => part.trim()).map(part => {
      const colon = part.indexOf(":");
      return [part.slice(0, colon).trim(), part.slice(colon + 1).trim()];
    }));
  };
  const normal = declarations(".subagent-capsule");
  const selected = declarations('.subagent-capsule[aria-current="page"]');
  assert.equal(normal.get("--md-component-transition"), "background-color 250ms linear, color 250ms linear");
  assert.equal(normal.get("transition"), "var(--md-component-transition)");
  const material = fs.readFileSync(new URL("../../main/assets/webui/material/material.css", import.meta.url), "utf8");
  assert.match(material, /transition:\s*--md-state[^;]*var\(--md-component-transition, color 0s\)/);
  assert.equal(normal.get("background"), "var(--md-surface-container-high)");
  assert.equal(normal.get("color"), "var(--md-on-surface)");
  assert.equal(selected.get("background"), "var(--md-secondary-container)");
  assert.equal(selected.get("color"), "var(--md-on-secondary-container)");
  assert.equal(normal.get("height"), "48px");
  assert.equal(normal.get("box-shadow"), "var(--shadow-4)");
  assert.equal(declarations(".subagent-text > small").get("transition"), "color 250ms linear");
  assert.doesNotMatch(css, /(?:reduce-motion|prefers-reduced-motion)[^{]*\{[^}]*\.subagent-capsule/);
});

test("idle and completed use the branch icon while failure and stop stay distinct", () => {
  for (const [status, expected] of [["idle", "tree"], ["completed", "tree"], ["failed", "error"], ["stopped", "pause-circle-outline"]]) {
    const f = harness("SubagentStatus", {
      commit(tree) { tree.ref.current = { children: [{ style: {} }] }; },
    });
    const tree = f.render({ status });
    assert.equal(tree.props.children.length, 1);
    assert.equal(tree.props.children[0].props.children, expected, status);
    f.dispose();
  }
});

test("running keeps the fixed-size progress indicator instead of a static icon", () => {
  const f = harness("SubagentStatus", {
    commit(tree) { tree.ref.current = { children: [{ style: {} }] }; },
  });
  const tree = f.render({ status: "running" });
  const progress = tree.props.children[0].props.children;
  assert.equal(progress.type.name, "Spinner");
  assert.equal(progress.props.size, 18);
  assert.equal(progress.props.stroke, 2);
  f.dispose();
});

for (const [component, field, values] of [
  ["SubagentStatus", "status", ["idle", "running", "completed"]],
  ["SubagentTitle", "title", ["Alpha", "2 Subagents", "3 Subagents"]],
]) test(`${field} crossfades continue current opacity across rapid changes`, () => {
  const nodes = new Map(), animations = [];
  const host = { children: [] };
  const f = harness(component, {
    getComputedStyle: node => ({ opacity: node.visible ?? node.style.opacity ?? "0" }),
    commit(tree) {
      const children = tree.props.children;
      const current = new Set(children.map(child => child.key));
      nodes.forEach((node, key) => { node.isConnected = current.has(key); });
      host.children = children.map(child => {
        if (!nodes.has(child.key)) nodes.set(child.key, {
          style: {}, isConnected: true,
          animate(frames, options) {
            const animation = { node: this, frames, options, cancelled: false,
              cancel() { this.cancelled = true; } };
            animations.push(animation);
            return animation;
          },
        });
        return nodes.get(child.key);
      });
      tree.ref.current = host;
    },
  });
  f.render({ [field]: values[0] });
  assert.equal(host.children[0].style.opacity, "1");
  assert.equal(animations.length, 0);
  f.render({ [field]: values[1] });
  assert.equal(host.children.length, 2);
  assert.ok(animations.every(animation => animation.options.duration === 250));
  assert.deepEqual(Array.from(animations[0].frames, frame => frame.opacity), ["1", 0]);
  assert.deepEqual(Array.from(animations[1].frames, frame => frame.opacity), ["0", 1]);
  host.children[0].visible = ".6"; host.children[1].visible = ".4";
  const obsoleteFinish = animations[1].onfinish;
  f.render({ [field]: values[2] });
  assert.ok(animations.slice(0, 2).every(animation => animation.cancelled));
  assert.equal(host.children.length, 3);
  assert.equal(animations[2].frames[0].opacity, ".6");
  assert.equal(animations[3].frames[0].opacity, ".4");
  obsoleteFinish(); f.flush();
  assert.equal(host.children.length, 3);
  animations[4].onfinish(); f.flush();
  assert.equal(host.children.length, 1);
  assert.equal(host.children[0].style.opacity, "1");
  f.dispose();
});
