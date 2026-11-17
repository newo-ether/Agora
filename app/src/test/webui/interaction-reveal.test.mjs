import assert from "node:assert/strict";
import fs from "node:fs";
import test from "node:test";
import vm from "node:vm";
import { readWebUiStyles } from "./webui-test-sources.mjs";
import { h } from "../../main/assets/webui/vendor/preact.mjs";
import htm from "../../main/assets/webui/vendor/htm.mjs";

const html = htm.bind(h);
const source = fs.readFileSync(new URL("../../main/assets/webui/interaction.js", import.meta.url), "utf8");
const question = (id, text = `Question ${id}`) => ({ kind: "question", id, groupId: "same-set", question: text, options: [] });
const shell = id => ({ kind: "shell", id, server: "test", summary: "echo test" });
const state = (items, patch = {}) => ({ connected: true, connectionId: "session", openId: "root",
  interactions: items, interactionPending: 0, display: { reduceMotion: true }, ...patch });

function harness(withRow = false) {
  const hooks = [], pending = [], nodes = new Map(), frames = new Map(), reveals = [], commands = [];
  const domRefs = new Set();
  let index = 0, dirty = false, props, tree, now = 0, next = 0;
  const observers = new Set(), handlers = new Map(), windowHandlers = new Map();
  const cssStyle = () => ({ setProperty(key, value) { this[key] = value; },
    getPropertyValue(key) { return this[key] || ""; }, removeProperty(key) { delete this[key]; } });
  const chat = { style: cssStyle() }, range = { style: { width: "0px" }, hasAttribute: () => false };
  let track, viewport, neighbor, disposeRow, mutateRow;
  if (withRow) {
    viewport = { style: cssStyle(), dataset: { toolsOwner: "session:root:1", toolsFamily: "session:root" },
      clientWidth: 400, scrollLeft: 0, closest: () => chat, getBoundingClientRect: () => ({ left: 0 }),
      addEventListener: (key, callback) => handlers.set(key, callback), removeEventListener: key => handlers.delete(key),
      dispatchEvent: event => handlers.get(event.type)?.(),
      get scrollWidth() { return Math.max(400, track.offsetWidth + 16, (parseFloat(range.style.width) || 0) + 16); } };
    neighbor = { style: {}, dataset: { toolsKey: "subagent:a", toolsProgress: "1", owner: "session:root" },
      offsetWidth: 120, firstElementChild: { offsetHeight: 48 }, hasAttribute: () => true,
      get offsetLeft() { return nodes.get("interaction-host tools-item").offsetWidth + 8; },
      getBoundingClientRect() {
        const left = 8 + this.offsetLeft - viewport.scrollLeft + (parseFloat(this.style.translate) || 0);
        return { left, right: left + this.offsetWidth };
      } };
    track = { style: {}, lastElementChild: range,
      get children() { return [nodes.get("interaction-host tools-item"), neighbor, range]; },
      get offsetWidth() { return Math.max(376, nodes.get("interaction-host tools-item").offsetWidth + 128); } };
    viewport.firstElementChild = track;
  }
  function node() {
    return { style: {}, dataset: {}, offsetHeight: 160, offsetWidth: 100, clientWidth: 400,
      classList: { add() {} }, setAttribute() {}, querySelectorAll: () => [],
      querySelector: () => ({ focus() {}, blur() {} }), cloneNode: () => node(),
      closest: () => ({ clientWidth: 400 }), append() {}, remove() {},
      animate: () => ({ cancel() {} }) };
  }
  function walk(value, visit, parent = null) {
    if (Array.isArray(value)) { value.forEach(child => walk(child, visit, parent)); return; }
    if (!value || typeof value !== "object" || !value.props) return;
    visit(value, parent);
    walk(value.props.children, visit, value);
  }
  function commit() {
    domRefs.forEach(ref => { ref.current = null; });
    walk(tree, (vnode, parent) => {
      const key = String(vnode.props.class || vnode.type).replace(/^interaction-card folded$/, "interaction-card");
      if (!nodes.has(key)) {
        const dom = node();
        if (withRow && key.startsWith("interaction-")) {
          Object.defineProperties(dom, {
            offsetWidth: { configurable: true, get() {
              if (key === "interaction-host tools-item") return nodes.get("interaction-card").offsetWidth;
              return parseFloat(this.style.width) || (key === "interaction-full" ? 376 : 100);
            } },
            offsetHeight: { configurable: true, get() {
              return parseFloat(this.style.height) || (key === "interaction-capsule" ? 48 : 160);
            } },
          });
        }
        nodes.set(key, dom);
      }
      const dom = nodes.get(key);
      dom.parentElement = nodes.get(parent?.props.class) || node();
      if (vnode.props["data-owner"]) dom.dataset.owner = vnode.props["data-owner"];
      if (withRow && key === "interaction-host tools-item") {
        dom.dataset.toolsKey = "interaction";
        dom.firstElementChild = nodes.get("interaction-card");
        dom.hasAttribute = () => true;
        dom.offsetLeft = 0;
        dom.closest = () => viewport;
        dom.getBoundingClientRect = () => {
          const left = 8 - viewport.scrollLeft + (parseFloat(dom.style.translate) || 0);
          return { left, right: left + dom.offsetWidth };
        };
      }
      if (vnode.ref) {
        domRefs.add(vnode.ref);
        if (key === "interaction-host tools-item" && !dom.tracked) {
          dom.tracked = true;
          dom.dataset = new Proxy(dom.dataset, { set(target, name, value) {
            target[name] = value;
            if (name === "toolsReveal") { reveals.push(value); mutateRow?.(); }
            return true;
          } });
        }
        vnode.ref.current = dom;
      }
    });
  }
  const context = vm.createContext({
    html, t: { collapse: "Collapse", questionBack: "Back", questionNext: "Next", send: "Send",
      skip: "Skip", allow: "Allow", deny: "Deny", customAnswer: "Answer" },
    icon: value => value, ICON_COLLAPSE_CONTENT: "collapse", ICON_EXPAND_CONTENT: "expand",
    ICON_TERMINAL: "terminal", ICON_HELP: "help",
    sync: { interactionTarget: () => ({ connectionId: props.state.connectionId, conversationId: props.state.openId, seq: 1 }),
      interactionCommand: (type, values, target) => commands.push({ type, values, target }) },
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
      if (!old || !deps || deps.some((value, i) => value !== old.deps?.[i])) {
        const hook = { deps, cleanup: old?.cleanup };
        hooks[at] = hook;
        pending.push(() => { hook.cleanup?.(); hook.cleanup = effect(); });
      }
    },
    performance: { now: () => now },
    requestAnimationFrame: callback => { frames.set(++next, callback); return next; },
    cancelAnimationFrame: id => frames.delete(id),
    ResizeObserver: class {
      constructor(callback) { this.callback = callback; }
      observe() { observers.add(this); }
      unobserve() {}
      disconnect() { observers.delete(this); }
    },
    MutationObserver: class { constructor(callback) { mutateRow = callback; } observe() {} disconnect() {} },
    Event: class { constructor(type) { this.type = type; } },
    window: { addEventListener: (key, callback) => windowHandlers.set(key, callback),
      removeEventListener: key => windowHandlers.delete(key) },
    getComputedStyle: () => ({ transform: "none", direction: "ltr" }),
  });
  vm.runInContext(source.replace(/^import .*;\r?\n/gm, "").replace(/export /g, ""), context);
  if (withRow) {
    const row = fs.readFileSync(new URL("../../main/assets/webui/tools-row.js", import.meta.url), "utf8");
    vm.runInContext(row.replace(/^import .*;\r?\n/gm, "").replace(/export /g, ""), context);
  }
  function flush() {
    for (let passes = 0; passes < 15; passes++) {
      index = 0; dirty = false;
      tree = context.InteractionBar(props);
      commit();
      pending.splice(0).forEach(effect => effect());
      if (!dirty) return tree;
    }
    throw new Error("InteractionBar did not settle");
  }
  function find(predicate) {
    let found;
    walk(tree, vnode => { if (!found && predicate(vnode)) found = vnode; });
    assert.ok(found, "expected control exists");
    return found;
  }
  const resize = () => [...observers].forEach(observer => observer.callback());
  return { reveals, commands, viewport, neighbor, chat,
    render(value, enter = true) {
      props = { state: value, enter };
      const result = flush();
      if (withRow && !disposeRow) {
        nodes.get("interaction-host tools-item").firstElementChild = nodes.get("interaction-card");
        disposeRow = context.attachToolsRow(viewport, !!value.display?.reduceMotion);
      }
      return result;
    },
    host: () => nodes.get("interaction-host tools-item"),
    card: () => [...nodes.entries()].find(([key]) => String(key).startsWith("interaction-card"))?.[1],
    find,
    invoke(control, event) { control.props.onClick(event); flush(); },
    button(label) { return find(vnode => vnode.type === "button" && vnode.props.children === label); },
    capsule() { return find(vnode => vnode.props.class === "interaction-capsule"); },
    fold() { this.invoke(find(vnode => vnode.props.class?.includes("interaction-minimize"))); },
    input(text) { find(vnode => vnode.type === "textarea").props.onInput({ target: { value: text } }); flush(); },
    step(ms) {
      now += ms;
      const callbacks = [...frames.values()]; frames.clear(); callbacks.forEach(callback => callback(now));
      flush();
      if (withRow) resize();
    },
    dispose() { hooks.forEach(hook => hook?.cleanup?.()); disposeRow?.(); },
    pending: () => frames.size,
  };
}

test("new flattened question identities reveal without unfolding or resetting page and draft", () => {
  const f = harness(), first = question(1), second = question(2);
  f.render(state([first]));
  assert.equal(f.reveals.length, 1);
  f.input("retained answer"); f.fold();
  f.render(state([first, second]));
  assert.equal(f.reveals.length, 2);
  assert.equal(f.capsule().props.disabled, false);
  f.invoke(f.capsule());
  assert.equal(f.reveals.length, 3);
  assert.equal(f.find(vnode => vnode.type === "textarea").props.value, "retained answer");
  assert.equal(f.find(vnode => vnode.props.class === "interaction-question").props.children, first.question);
  f.dispose();
});

test("text and status rerenders, reordering, removals and other-owner arrivals do not reveal", () => {
  const f = harness(), first = question(1), second = question(2);
  f.render(state([first, second]));
  f.render(state([{ ...first, question: "Updated text" }, second], { interactionPending: 3 }));
  f.render(state([second, first]));
  f.render(state([first]));
  assert.equal(f.reveals.length, 1);
  f.render(state([question(8)], { openId: "other" }));
  f.invoke(f.capsule());
  assert.equal(f.reveals.length, 1);
  assert.equal(f.capsule().props.disabled, true);
  f.step(180);
  assert.equal(f.reveals.length, 2);
  assert.equal(f.reveals.at(-1), "session:other");
  f.dispose();
});

test("every admitted expand callback reveals even when already expanded, but pending actions do not", () => {
  const f = harness();
  f.render(state([question(1)]));
  f.invoke(f.capsule()); f.invoke(f.capsule());
  assert.equal(f.reveals.length, 3);
  f.render(state([question(1)], { interactionPending: 9 }));
  f.invoke(f.capsule());
  assert.equal(f.reveals.length, 3);
  f.dispose();
});

test("same numeric shell and question IDs reveal independently and keep exact action payloads", () => {
  const f = harness(), q = question(7), s = shell(7);
  f.render(state([q])); f.input("question answer");
  f.render(state([s, q]));
  assert.equal(f.reveals.length, 2);
  assert.equal(f.find(vnode => vnode.type === "textarea").props.value, "question answer");
  f.invoke(f.button("Back"));
  const trust = f.find(vnode => vnode.type === "input" && vnode.props.type === "checkbox");
  trust.props.onChange({ target: { checked: true } });
  f.invoke(f.button("Allow"));
  assert.equal(f.commands.at(-1).type, "shell_decision");
  assert.equal(f.commands.at(-1).values.requestId, 7);
  assert.equal(f.commands.at(-1).values.alwaysAllow, true);
  f.invoke(f.button("Next")); f.invoke(f.button("Send"));
  assert.equal(f.commands.at(-1).type, "question_submit");
  assert.deepEqual(Object.keys(f.commands.at(-1).values.answers), ["7"]);
  assert.equal(f.commands.at(-1).values.answers[7].text, "question answer");
  assert.equal(f.commands.at(-1).values.requestId, undefined);
  f.dispose();
});

test("removing one request kind clears only its draft and trust even while the numeric ID remains", () => {
  const f = harness(), q = question(7), s = shell(7);
  f.render(state([s, q]));
  f.find(vnode => vnode.type === "input" && vnode.props.type === "checkbox").props.onChange({ target: { checked: true } });
  f.invoke(f.button("Next")); f.input("old answer");
  f.render(state([s])); f.render(state([s, q]));
  f.invoke(f.button("Next"));
  assert.equal(f.find(vnode => vnode.type === "textarea").props.value, "");
  f.input("new answer");
  f.render(state([q])); f.render(state([s, q]));
  assert.equal(f.find(vnode => vnode.type === "textarea").props.value, "new answer");
  f.invoke(f.button("Back"));
  assert.equal(f.find(vnode => vnode.type === "input" && vnode.props.type === "checkbox").props.checked, false);
  f.dispose();
});

test("owner replacement keeps latest Interaction hidden until the shared row admits it", () => {
  for (const reduced of [false, true]) {
    const f = harness();
    const initial = state([question(1)], { display: { reduceMotion: reduced } });
    f.render(initial); f.step(180);
    f.render(state([question(2)], { openId: "middle", display: initial.display }), false);
    assert.equal(f.find(vnode => vnode.props.class === "interaction-question").props.children, "Question 1");
    assert.equal(f.capsule().props.disabled, true);
    assert.equal(f.host().dataset.toolsOpacity, "1");
    f.step(90);
    assert.ok(Number(f.host().dataset.toolsOpacity) > 0);
    f.render(state([question(3)], { openId: "latest", display: initial.display }), false);
    f.step(90);
    assert.equal(f.find(vnode => vnode.props.class === "interaction-question").props.children, "Question 3");
    assert.equal(f.host().dataset.toolsOpacity, "0");
    assert.equal(f.reveals.length, 1);
    f.step(180);
    assert.equal(f.card().style.visibility, "hidden");
    assert.equal(f.capsule().props.disabled, true);
    f.render(state([question(3)], { openId: "latest", display: initial.display }));
    assert.equal(f.reveals.at(-1), "session:latest");
    assert.equal(f.host().dataset.toolsOpacity, "0");
    f.step(180);
    assert.equal(f.host().dataset.toolsOpacity, "1");
    f.dispose();
  }
});

test("returning to the admitted Interaction owner retains folded page and exact drafts after exit", () => {
  const f = harness(), items = [shell(7), question(7)];
  f.render(state(items)); f.step(180);
  f.invoke(f.button("Next")); f.input("saved answer"); f.fold();
  f.render(state([question(8)], { openId: "other" }), false); f.step(90);
  f.render(state(items), false); f.step(180);
  assert.equal(f.host().dataset.toolsOpacity, "0");
  f.render(state(items)); f.step(180);
  assert.equal(f.find(vnode => String(vnode.props.class).startsWith("interaction-card")).props.class, "interaction-card folded");
  f.invoke(f.capsule());
  assert.equal(f.find(vnode => vnode.props.class === "interaction-question").props.children, "Question 7");
  assert.equal(f.find(vnode => vnode.type === "textarea").props.value, "saved answer");
  f.dispose();
});

test("returning from a child restores pending parent questions without a new reveal", () => {
  const f = harness(), items = [shell(7), question(7)];
  f.render(state(items)); f.step(180);
  f.invoke(f.button("Next")); f.input("saved parent answer"); f.fold();
  const revealed = f.reveals.length;
  f.render(state([], { openId: "child", parentConversationId: "root" })); f.step(180);
  f.render(state(items)); f.step(180);
  assert.equal(f.reveals.length, revealed, "returning to the same pending requests must not seek logical start");
  assert.equal(f.find(vnode => String(vnode.props.class).startsWith("interaction-card")).props.class, "interaction-card folded");
  f.invoke(f.capsule());
  assert.equal(f.reveals.length, revealed + 1, "explicit expansion still reveals");
  assert.equal(f.find(vnode => vnode.props.class === "interaction-question").props.children, "Question 7");
  assert.equal(f.find(vnode => vnode.type === "textarea").props.value, "saved parent answer");
  f.render(state([...items, question(8)]));
  assert.equal(f.reveals.length, revealed + 2, "an actual new question still reveals");
  f.dispose();
});

test("real interaction fold frames reserve card height and neighbor space without outer cropping", () => {
  const css = readWebUiStyles();
  assert.match(css, /\.interaction-card \{[^}]*border-radius: 24px;[^}]*overflow: hidden/);
  assert.doesNotMatch(css, /\.interaction-host \{[^}]*overflow:\s*hidden/);
  for (const reduced of [false, true]) {
    const f = harness(true);
    f.render(state([question(1)], { display: { reduceMotion: reduced } }));
    f.step(180); f.step(16);
    for (const folded of [true, false]) {
      if (folded) f.fold(); else f.invoke(f.capsule());
      let minimumWidth = Infinity, maximumWidth = 0;
      for (let frame = 0; frame < 26; frame++) {
        f.step(16);
        const card = f.card(), host = f.host();
        const width = card.offsetWidth, height = card.offsetHeight;
        minimumWidth = Math.min(minimumWidth, width); maximumWidth = Math.max(maximumWidth, width);
        assert.equal(host.offsetWidth, width, `slot follows card width at frame ${frame}`);
        assert.ok(parseFloat(f.viewport.style.height) >= height + 16 - 1e-9,
          `viewport ${f.viewport.style.height} contains card ${height}px at frame ${frame}, folded=${folded}, reduced=${reduced}`);
        assert.ok(f.neighbor.getBoundingClientRect().left - host.getBoundingClientRect().right >= 8 - 1e-9,
          `neighbor clears the expanding card at frame ${frame}`);
      }
      if (!reduced) assert.ok(maximumWidth > minimumWidth, "the real fold animation changes geometry");
      assert.equal(f.card().offsetWidth, folded ? 100 : 376);
      assert.equal(f.card().offsetHeight, folded ? 48 : 160);
    }
    f.dispose();
  }
});

test("a fresh mixed deck starts shell-first and disposal cancels retained animation frames", () => {
  const f = harness();
  f.render(state([shell(1), question(1)]));
  assert.ok(f.button("Allow"));
  assert.equal(f.button("Next").props.disabled, false);
  assert.ok(f.pending() > 0);
  f.dispose();
  assert.equal(f.pending(), 0);
});
