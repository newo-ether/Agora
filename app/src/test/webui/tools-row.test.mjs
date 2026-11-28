import assert from "node:assert/strict";
import fs from "node:fs";
import test from "node:test";
import vm from "node:vm";
import { readWebUiStyles } from "./webui-test-sources.mjs";
import { h } from "../../main/assets/webui/vendor/preact.mjs";
import htm from "../../main/assets/webui/vendor/htm.mjs";

const html = htm.bind(h);

const root = new URL("../../main/assets/webui/", import.meta.url);
const source = name => fs.readFileSync(new URL(name, root), "utf8");
function load(name, globals) {
  const code = source(name).replace(/^import .*;\r?\n/gm, "").replace(/export /g, "");
  const context = vm.createContext(globals);
  vm.runInContext(code, context, { filename: name });
  return context;
}
function fixture(specs, { width = 240, scroll = 0, reduced = false, rtl = false, entering = true, outgoing = [], family, required } = {}) {
  let now = 0, next = 0, resize, mutate;
  const settled = [], measurements = [];
  const frames = new Map(), handlers = new Map(), windowHandlers = new Map();
  const style = () => ({ setProperty(key, value) { this[key] = value; },
    getPropertyValue(key) { return this[key] || ""; }, removeProperty(key) { delete this[key]; } });
  const chat = { style: style() }, range = { style: { width: "0px" }, hasAttribute: () => false };
  const viewport = {
    style: style(), dataset: { toolsOwner: "session:root:1", toolsEnter: String(entering), toolsFamily: family }, clientWidth: width, scrollLeft: scroll,
    closest: () => chat, getBoundingClientRect: () => ({ left: 0 }),
    addEventListener: (key, callback) => handlers.set(key, callback),
    dispatchEvent: event => handlers.get(event.type)?.(),
    removeEventListener: key => handlers.delete(key),
    get scrollWidth() { return Math.max(width, track.offsetWidth + 24, (parseFloat(range.style.width) || 0) + 12); },
  };
  let nodes;
  const track = {
    style: { width: "", minWidth: "" },
    get children() { return [...nodes, range]; }, lastElementChild: range,
    get offsetWidth() { return parseFloat(this.style.width) || Math.max(width - 24,
      nodes.reduce((sum, node) => sum + node.offsetWidth, 0) + Math.max(0, nodes.length - 1) * 8); },
  };
  if (required !== undefined) viewport.dataset.toolsRequired = JSON.stringify(required);
  viewport.firstElementChild = track;
  function setItems(values) {
    const old = new Map((nodes || []).map(node => [node.dataset.toolsKey, node]));
    nodes = values.map(([key, itemWidth, height = 48]) => Object.assign(old.get(key) || {
      style: { translate: "" }, dataset: { toolsKey: key, toolsProgress: "1", owner: "session:root" },
      hasAttribute: name => name === "data-tools-key",
      get offsetLeft() {
        const index = nodes.indexOf(this);
        const gap = (track.offsetWidth - nodes.reduce((sum, node) => sum + node.offsetWidth, 0) - (nodes.length - 1) * 8) / 2;
        const before = nodes.slice(0, index).reduce((sum, node) => sum + node.offsetWidth + 8, 0);
        return rtl ? track.offsetWidth - gap - before - this.offsetWidth : gap + before;
      },
      get offsetWidth() { return parseFloat(this.style.width) || this.naturalWidth; },
      getBoundingClientRect() {
        const left = 12 + this.offsetLeft - (rtl ? track.offsetWidth + 24 - width : 0)
          - viewport.scrollLeft + (parseFloat(this.style.translate) || 0);
        return { left, right: left + this.offsetWidth };
      },
    }, { naturalWidth: itemWidth, offsetHeight: height, firstElementChild: { style: {}, offsetHeight: height,
      offsetWidth: itemWidth,
      getBoundingClientRect() {
        const owner = nodes.find(node => node.firstElementChild === this);
        const box = owner.getBoundingClientRect();
        return { ...box, width: owner.offsetWidth };
      } } }));
  }
  setItems(specs);
  nodes.forEach(node => {
    if (outgoing.includes(node.dataset.toolsKey)) node.dataset.toolsExiting = "true";
  });
  const context = load("tools-row.js", {
    tweenProgress: load("interaction.js", {}).tweenProgress,
    performance: { now: () => now }, Event: class { constructor(type) { this.type = type; } },
    requestAnimationFrame: callback => { frames.set(++next, callback); return next; },
    cancelAnimationFrame: id => frames.delete(id),
    ResizeObserver: class { constructor(callback) { resize = callback; } observe() {} unobserve() {} disconnect() {} },
    MutationObserver: class { constructor(callback) { mutate = callback; } observe() {} disconnect() {} },
    getComputedStyle: () => ({ direction: rtl ? "rtl" : "ltr" }),
    window: { addEventListener: (key, callback) => windowHandlers.set(key, callback),
      removeEventListener: key => windowHandlers.delete(key) },
  });
  const dispose = context.attachToolsRow(viewport, reduced, (owner, measured) => {
    settled.push(owner); measurements.push([owner, measured]);
  });
  return {
    viewport, chat, range, track, dispose, settled, measurements,
    require(values) { viewport.dataset.toolsRequired = JSON.stringify(values); mutate(); },
    reattach(reduced) { dispose(); return context.attachToolsRow(viewport, reduced, owner => settled.push(owner)); },
    select(owner, family) {
      viewport.dataset.toolsOwner = owner;
      if (family !== undefined) viewport.dataset.toolsFamily = family;
      mutate();
    },
    exiting(keys) {
      nodes.forEach(node => { node.dataset.toolsExiting = keys.includes(node.dataset.toolsKey) ? "true" : "false"; });
      mutate();
    },
    nodes: () => nodes,
    update(values, structural = false) { setItems(values); mutate(structural ? [{ type: "childList" }] : []); },
    resize() { resize(); },
    reveal(owner = "session:root") {
      nodes.find(node => node.dataset.toolsKey === "interaction").dataset.toolsReveal = owner;
      mutate();
    },
    expand(value = true) {
      const node = nodes.find(node => node.dataset.toolsKey === "interaction");
      if (value) { node.dataset.toolsExpanded = "true"; node.dataset.toolsReveal = node.dataset.owner; }
      else delete node.dataset.toolsExpanded;
      mutate();
    },
    pending: () => frames.size,
    bindings: () => handlers.size + windowHandlers.size,
    progress(values) {
      nodes.forEach(node => {
        if (node.dataset.toolsKey in values) node.dataset.toolsProgress = String(values[node.dataset.toolsKey]);
      });
      mutate();
    },
    input(type, event) { (handlers.get(type) || windowHandlers.get(type))?.(event); },
    step(ms) { now += ms; const pending = [...frames.values()]; frames.clear(); pending.forEach(callback => callback(now)); },
  };
}

test("expanded interaction centers through the existing reveal and restores scrolling and edges on collapse", () => {
  for (const reduced of [false, true]) for (const rtl of [false, true]) {
    const f = fixture([["interaction", 150, 160], ["todo", 180], ["subagent:a", 180]],
      { width: 240, scroll: rtl ? -120 : 120, reduced, rtl });
    f.expand(); f.step(16);
    assert.equal(f.viewport.style.overflowX, "hidden");
    assert.equal(f.viewport.style.touchAction, "pan-y");
    assert.equal(f.viewport.style["--tools-edge-left"], "0");
    assert.equal(f.viewport.style["--tools-edge-right"], "0");
    if (!reduced) {
      f.step(100);
      assert.notEqual(Math.abs(f.viewport.scrollLeft), 120, "the original reveal frame advances centering");
      f.input("pointerdown");
    }
    f.update([["interaction", 224, 160], ["todo", 180], ["subagent:a", 180]]);
    f.resize(); f.step(400);
    const card = f.nodes()[0].getBoundingClientRect();
    assert.ok(Math.abs((card.left + card.right) / 2 - 120) <= 1,
      JSON.stringify({ rtl, reduced, card, scroll: f.viewport.scrollLeft }));
    let prevented = false;
    f.input("wheel", { type: "wheel", deltaX: 80, preventDefault() { prevented = true; } });
    assert.equal(prevented, true);
    f.viewport.scrollLeft += rtl ? -60 : 60; f.input("scroll");
    const corrected = f.nodes()[0].getBoundingClientRect();
    assert.ok(Math.abs((corrected.left + corrected.right) / 2 - 120) <= 1);
    f.expand(false); f.step(16); f.step(400);
    assert.equal(f.viewport.style.overflowX, "");
    assert.equal(f.viewport.style.touchAction, "");
    f.viewport.scrollLeft = rtl ? -100 : 100; f.input("scroll");
    assert.equal(f.viewport.style["--tools-edge-left"], "1");
    assert.equal(f.viewport.style["--tools-edge-right"], "1");
    f.input("pointerdown"); f.viewport.scrollLeft = rtl ? -70 : 70;
    f.input("pointerup"); f.step(400);
    assert.equal(f.viewport.scrollLeft, rtl ? -70 : 70);
    f.dispose();
    assert.equal(f.viewport.dataset.toolsExpanded, undefined);
  }
});

test("expanded paint clips only neighboring visuals and restores them on unlock or disposal", () => {
  for (const rtl of [false, true]) {
    const f = fixture([["interaction", 216, 160], ["todo", 120]], { rtl });
    const editor = f.nodes()[0].firstElementChild, visual = f.nodes()[1].firstElementChild;
    let stored = "", writes = 0;
    Object.defineProperty(visual.style, "clipPath", { get: () => stored, set(value) { stored = value; writes++; } });
    f.expand(); f.step(16); f.step(350);
    assert.match(stored, /^inset\(-100% /);
    assert.equal(editor.style.clipPath, undefined, "the rounded editor and its shadow are not clipped");
    const count = writes;
    f.resize(); f.step(16); f.resize(); f.step(16);
    assert.equal(writes, count, "unchanged bounds must not rewrite clip styles");
    f.expand(false); f.step(16);
    assert.equal(stored, "");
    f.expand(); f.step(16); f.step(350);
    assert.notEqual(stored, "");
    f.dispose();
    assert.equal(stored, "");
  }
});

test("admission waits for every owner-matching width and wakes on required mutations", () => {
  const f = fixture([["todo", 100]], { required: [["todo", "session:root"], ["subagent:a", "session:root"]] });
  assert.deepEqual(f.measurements, []);
  f.update([["todo", 100], ["subagent:a", 0]], true);
  assert.deepEqual(f.measurements, []);
  f.nodes()[1].dataset.owner = "session:stale";
  f.nodes()[1].naturalWidth = 120;
  f.resize();
  assert.deepEqual(f.measurements, []);
  f.nodes()[1].dataset.owner = "session:root";
  f.resize();
  assert.deepEqual(f.measurements, [["session:root:1", f.viewport.dataset.toolsRequired]]);
  f.resize();
  assert.equal(f.measurements.length, 1);
  f.require([]); f.step(16);
  assert.deepEqual(f.measurements.at(-1), ["session:root:1", "[]"]);
  f.select("session:child:2", "family"); f.step(16); f.step(16);
  assert.deepEqual(f.measurements.at(-1), ["session:child:2", "[]"]);
  f.dispose();
});

test("loaded-empty tools settle without a mounted capsule in either motion mode", () => {
  for (const reduced of [false, true]) {
    const f = fixture([], { reduced, entering: false, required: [] });
    assert.deepEqual(f.measurements, []);
    f.step(16);
    assert.deepEqual(f.measurements.at(-1), ["session:root:1", "[]"]);
    assert.equal(f.viewport.scrollLeft, 0);
    f.dispose();
  }
});

test("initial render never scrolls and lift is the maximum height, not the sum", () => {
  const f = fixture([["interaction", 224, 160], ["todo", 100], ["agent:a", 120]]);
  assert.equal(f.viewport.scrollLeft, 0);
  assert.equal(f.chat.style["--chat-tools-lift"], "160px");
  assert.equal(f.chat.style["--chat-tools-message-inset"], "168px");
  f.update([]); f.step(16);
  assert.equal(f.chat.style["--chat-tools-lift"], "0px");
  f.dispose();
  assert.equal(f.chat.style["--chat-tools-lift"], undefined);
  assert.equal(f.chat.style["--chat-tools-message-inset"], undefined);
});

test("insertion preserves the anchor and animates placement and scroll", () => {
  const specs = [["agent:a", 120], ["agent:b", 120], ["agent:c", 120]];
  const f = fixture(specs, { scroll: 100 });
  f.update([["todo", 100], ...specs]);
  f.step(16);
  assert.equal(f.viewport.scrollLeft, 100);
  assert.equal(f.nodes()[1].style.translate, "-108px 0");
  f.step(90);
  assert.ok(f.viewport.scrollLeft > 100 && f.viewport.scrollLeft < 208);
  f.resize(); f.step(16); f.step(74);
  assert.equal(f.viewport.scrollLeft, 208);
  assert.equal(f.nodes()[1].style.translate, "");
  f.dispose();
});

test("tail removal retains the old scroll range until the animation settles", () => {
  const f = fixture([["a", 120], ["b", 120], ["c", 120]], { scroll: 152 });
  f.update([["a", 120], ["b", 120]]); f.step(16);
  assert.equal(f.viewport.scrollLeft, 152);
  assert.equal(f.range.style.width, "388px");
  f.step(90);
  assert.ok(f.viewport.scrollLeft > 32 && f.viewport.scrollLeft < 152);
  f.step(90);
  assert.equal(f.viewport.scrollLeft, 32);
  assert.equal(f.range.style.width, "248px");
  f.dispose();
});

test("wheel and pointer takeover cancel programmatic travel", () => {
  for (const type of ["wheel", "pointerdown", "keydown"]) {
    const f = fixture([["a", 120], ["b", 120], ["c", 120]], { scroll: 100 });
    f.update([["todo", 100], ["a", 120], ["b", 120], ["c", 120]]); f.step(16); f.step(40);
    f.input(type); f.viewport.scrollLeft = 73; f.step(140);
    assert.equal(f.viewport.scrollLeft, 73);
    f.dispose();
  }
});

test("Reduced Motion snaps spatial changes including RTL negative scroll", () => {
  for (const rtl of [false, true]) {
    const f = fixture([["a", 120], ["b", 120], ["c", 120]], { scroll: rtl ? -100 : 100, reduced: true, rtl });
    f.update([["todo", 100], ["a", 120], ["b", 120], ["c", 120]]); f.step(16);
    assert.equal(f.viewport.scrollLeft, rtl ? -208 : 208);
    assert.ok(f.nodes().every(node => node.style.translate === ""));
    f.dispose();
  }
});

test("height-only fades keep full paint and publish the logical inset", () => {
  const f = fixture([["todo", 100]]);
  let changes = 0;
  f.viewport.addEventListener("toolsresize", () => changes++);
  f.nodes()[0].offsetHeight = 24; f.nodes()[0].dataset.toolsProgress = ".5";
  f.resize(); f.step(16);
  assert.equal(f.chat.style["--chat-tools-lift"], "24px");
  assert.equal(f.chat.style["--chat-tools-message-inset"], "28px");
  assert.equal(f.viewport.style.height, "64px");
  assert.equal(changes, 1);
  f.dispose();
});

test("the final card's height and clearance share every exit progress through zero", () => {
  for (const [key, natural] of [["todo", 48], ["interaction", 160], ["subagent:a", 48]]) {
    const f = fixture([[key, 120, natural]]);
    let changes = 0;
    f.viewport.addEventListener("toolsresize", () => changes++);
    for (const progress of [.75, .5, .25, .001, 0]) {
      f.progress({ [key]: progress }); f.step(16);
      const height = parseFloat(f.chat.style["--chat-tools-lift"]);
      const inset = parseFloat(f.chat.style["--chat-tools-message-inset"]);
      assert.equal(height, natural * progress);
      assert.equal(inset, (natural + 8) * progress);
      assert.ok(Math.abs(inset - height - 8 * progress) < 1e-10);
    }
    assert.equal(changes, 5);
    f.progress({ [key]: 0 }); f.step(16);
    assert.equal(changes, 5);
    f.dispose();
    assert.equal(changes, 6);
    assert.equal(f.chat.style["--chat-tools-lift"], undefined);
    assert.equal(f.chat.style["--chat-tools-message-inset"], undefined);
  }
});

test("remaining cards retain their own inset and interrupted progress does not restart", () => {
  const f = fixture([["interaction", 224, 160], ["todo", 100], ["subagent:a", 120]]);
  for (const [progress, height, inset] of [[.5, 80, 84], [.25, 48, 56], [0, 48, 56]]) {
    f.progress({ interaction: progress }); f.step(16);
    assert.equal(parseFloat(f.chat.style["--chat-tools-lift"]), height);
    assert.equal(parseFloat(f.chat.style["--chat-tools-message-inset"]), inset);
  }
  f.progress({ interaction: .625 }); f.step(16);
  assert.equal(f.chat.style["--chat-tools-lift"], "100px");
  assert.equal(f.chat.style["--chat-tools-message-inset"], "105px");
  f.progress({ interaction: 0, todo: 0 }); f.step(16);
  assert.equal(f.chat.style["--chat-tools-message-inset"], "56px");
  f.progress({ "subagent:a": .25 }); f.step(16);
  assert.equal(f.chat.style["--chat-tools-message-inset"], "14px");
  f.progress({ "subagent:a": 0 }); f.step(16);
  assert.equal(f.chat.style["--chat-tools-message-inset"], "0px");
  f.dispose();
});

test("Reduced Motion snaps reported height and clearance while retained paint remains", () => {
  const f = fixture([["todo", 100]], { reduced: true });
  f.progress({ todo: 0 }); f.step(16);
  assert.equal(f.chat.style["--chat-tools-lift"], "0px");
  assert.equal(f.chat.style["--chat-tools-message-inset"], "0px");
  assert.equal(f.viewport.style.height, "64px");
  f.progress({ todo: 1 }); f.step(16);
  assert.equal(f.chat.style["--chat-tools-lift"], "48px");
  assert.equal(f.chat.style["--chat-tools-message-inset"], "56px");
  f.dispose();
});

test("fit-to-overflow insertion and interrupted removal retain current placement", () => {
  const centered = fixture([["a", 80], ["b", 80]], { width: 500 });
  centered.update([["todo", 250], ["a", 80], ["b", 80], ["c", 80]]); centered.step(16);
  assert.equal(centered.viewport.scrollLeft, 0);
  assert.notEqual(centered.nodes()[1].style.translate, "0px 0");
  centered.step(180); centered.dispose();

  const f = fixture([["a", 120], ["b", 120], ["c", 120]], { scroll: 100 });
  f.update([["todo", 100], ["a", 120], ["b", 120], ["c", 120]]); f.step(16); f.step(60);
  const before = f.nodes().find(node => node.dataset.toolsKey === "a").getBoundingClientRect().left;
  f.update([["a", 120], ["b", 120], ["c", 120]]); f.step(16);
  const after = f.nodes().find(node => node.dataset.toolsKey === "a").getBoundingClientRect().left;
  assert.ok(Math.abs(before - after) < .01);
  f.step(180);
  assert.ok(f.viewport.scrollLeft >= 0 && f.viewport.scrollLeft <= 152);
  f.dispose();
});

test("removed keys retain their last payload without replacing live updates", () => {
  const context = load("tool-item.js", {});
  const old = [{ id: "a", title: "old" }, { id: "b" }];
  const fresh = [{ id: "b", title: "new" }, { id: "c" }];
  const result = context.retainToolItems(old, fresh);
  assert.deepEqual(Array.from(result, item => item.id), ["a", "b", "c"]);
  assert.equal(result[0], old[0]);
  assert.equal(result[1], fresh[0]);
  assert.equal(old.length, 2);
});

function capsuleHarness(reduced) {
  let now = 0, next = 0, index = 0, pendingEffect, dependencies, cleanup, exits = 0;
  const refs = [], frames = new Map();
  const node = { style: {}, dataset: {} }, visual = { style: {}, offsetHeight: 48 };
  const context = load("tool-item.js", {
    html,
    useRef(initial) { const slot = index++; return refs[slot] || (refs[slot] = { current: initial }); },
    useLayoutEffect(effect, deps) {
      if (!dependencies || deps.some((value, i) => value !== dependencies[i])) {
        pendingEffect = effect; dependencies = deps;
      }
    },
    performance: { now: () => now },
    requestAnimationFrame: callback => { frames.set(++next, callback); return next; },
    cancelAnimationFrame: id => frames.delete(id),
    ResizeObserver: class { observe() {} disconnect() {} },
  });
  function render(active, patch = {}) {
    index = 0;
    const tree = context.ToolItem({ toolKey: "todo", identity: "owner:1", active, reduced,
      onExited: () => exits++, children: null, ...patch });
    tree.ref.current = node;
    tree.props.children.ref.current = visual;
    const marker = tree.props["data-tools-exiting"];
    if (marker === null) delete node.dataset.toolsExiting;
    else node.dataset.toolsExiting = marker;
    if (pendingEffect) { cleanup?.(); cleanup = pendingEffect(); pendingEffect = null; }
    return tree;
  }
  return { node, visual, render, exits: () => exits,
    dispose() { cleanup?.(); },
    step(ms) { now += ms; const pending = [...frames.values()]; frames.clear(); pending.forEach(callback => callback(now)); } };
}

test("capsule interruption keeps timed fades, Reduced Motion scale and inert exit", () => {
  const f = capsuleHarness(false);
  f.render(true); f.step(90);
  assert.equal(f.visual.style.opacity, "0.5");
  assert.equal(f.node.style.height, "24px");
  assert.equal(f.node.dataset.toolsProgress, "0.5");
  const outgoing = f.render(false);
  assert.equal(f.visual.style.opacity, "0.5");
  assert.equal(outgoing.props.inert, true);
  f.step(90);
  assert.equal(f.node.dataset.toolsProgress, "0.25");
  f.render(true);
  assert.equal(f.node.dataset.toolsProgress, "0.25");
  f.step(90);
  assert.equal(f.node.dataset.toolsProgress, "0.625");
  f.render(false); f.step(180);
  assert.equal(f.visual.style.opacity, "0");
  assert.equal(f.node.style.height, "0px");
  assert.equal(f.exits(), 1);
  const reduced = capsuleHarness(true);
  reduced.render(true);
  assert.equal(reduced.node.style.height, "48px");
  assert.equal(reduced.node.dataset.toolsProgress, "1");
  assert.equal(reduced.visual.style.transform, "scale(1)");
  reduced.step(90); reduced.render(false);
  assert.equal(reduced.node.style.height, "0px");
  assert.equal(reduced.node.dataset.toolsProgress, "0");
  assert.equal(reduced.visual.style.opacity, "0.5");
  assert.equal(reduced.exits(), 0);
  reduced.step(180);
  assert.equal(reduced.exits(), 1);
});

test("new capsules wait hidden for the shared exit barrier without blocking retained family nodes", () => {
  for (const reduced of [false, true]) {
    const incoming = capsuleHarness(reduced);
    const hidden = incoming.render(true, { enter: false });
    assert.equal(hidden.props.inert, true);
    assert.equal(incoming.visual.style.opacity, "0");
    assert.equal(incoming.visual.style.visibility, "hidden");
    incoming.step(400);
    assert.equal(incoming.visual.style.opacity, "0");
    assert.equal(incoming.exits(), 0);
    const entered = incoming.render(true);
    assert.equal(entered.props.inert, false);
    assert.equal(incoming.visual.style.opacity, "0");
    incoming.step(180);
    assert.equal(incoming.visual.style.opacity, "1");
    assert.equal(incoming.node.dataset.toolsProgress, "1");
    incoming.dispose();

    const retained = capsuleHarness(reduced);
    retained.render(true, { retain: true }); retained.step(180);
    const sameFamily = retained.render(true, { enter: false, retain: true });
    assert.equal(sameFamily.props.inert, false);
    assert.equal(retained.visual.style.opacity, "1");
    assert.equal(retained.node.dataset.toolsExiting, undefined);
    retained.step(180);
    assert.equal(retained.visual.style.opacity, "1");
    retained.dispose();
  }
});

test("same-selection admission reversal still exits before being readmitted", () => {
  const f = capsuleHarness(false);
  f.render(true); f.step(180);
  f.render(true, { enter: false }); f.step(90);
  assert.equal(f.visual.style.opacity, "0.5");
  assert.equal(f.node.dataset.toolsExiting, "true");
  f.step(90);
  assert.equal(f.visual.style.opacity, "0");
  assert.equal(f.node.dataset.toolsExiting, "false");
  assert.equal(f.exits(), 0);
  f.render(true); f.step(180);
  assert.equal(f.visual.style.opacity, "1");
  f.dispose();
});

test("interaction reveal uses 350ms FastOutSlowIn and harmless measurements do not restart it", () => {
  for (const rtl of [false, true]) {
    const f = fixture([["interaction", 224], ["todo", 100], ["agent:a", 120]], { scroll: rtl ? -120 : 120, rtl });
    f.reveal(); f.step(16);
    assert.equal(Math.abs(f.viewport.scrollLeft), 120);
    f.step(175);
    assert.ok(Math.abs(Math.abs(f.viewport.scrollLeft) - 26.93) < .1);
    f.resize(); f.step(16);
    f.progress({ interaction: .7 }); f.step(16);
    f.step(143);
    assert.equal(f.viewport.scrollLeft, 0);
    assert.equal(f.pending(), 0);
    f.viewport.scrollLeft = rtl ? -80 : 80;
    f.resize(); f.step(16); f.step(400);
    assert.equal(Math.abs(f.viewport.scrollLeft), 80);
    f.dispose();
  }
});

test("interaction reveal wins over insertion anchoring and survives unfolding width changes", () => {
  const agents = [["agent:a", 120], ["agent:b", 120], ["agent:c", 120]];
  const f = fixture(agents, { scroll: 120 });
  f.update([["interaction", 100], ...agents]); f.reveal(); f.step(16);
  f.step(80);
  const before = f.viewport.scrollLeft;
  f.nodes()[0].naturalWidth = 224;
  f.resize(); f.step(16);
  assert.ok(f.viewport.scrollLeft < before);
  f.step(254);
  assert.equal(f.viewport.scrollLeft, 0);
  assert.ok(f.nodes().every(node => node.style.translate === ""));
  f.dispose();
});

test("user input cancels reveals including pending requests and active drag rejects arrival", () => {
  for (const type of ["wheel", "pointerdown", "keydown"]) {
    for (const started of [false, true]) {
      const f = fixture([["interaction", 224], ["a", 120], ["b", 120]], { scroll: 120 });
      f.reveal();
      if (started) { f.step(16); f.step(60); }
      f.input(type); f.viewport.scrollLeft = 73;
      f.nodes()[0].naturalWidth = 210; f.resize(); f.step(16); f.step(400);
      assert.equal(f.viewport.scrollLeft, 73);
      f.dispose();
    }
  }
  const f = fixture([["interaction", 224], ["a", 120], ["b", 120]], { scroll: 120 });
  f.input("pointerdown"); f.reveal(); f.step(16); f.input("pointerup"); f.step(400);
  assert.equal(f.viewport.scrollLeft, 120);
  f.dispose();
});

test("Reduced Motion reveals snap and stale-owner requests cannot scroll", () => {
  for (const reduced of [false, true]) {
    const f = fixture([["interaction", 224], ["a", 120], ["b", 120]], { scroll: 120, reduced });
    f.reveal("session:other"); f.step(16); f.step(400);
    assert.equal(f.viewport.scrollLeft, 120);
    f.reveal(); f.step(16);
    if (reduced) assert.equal(f.viewport.scrollLeft, 0);
    else assert.equal(f.viewport.scrollLeft, 120);
    f.step(350);
    assert.equal(f.viewport.scrollLeft, 0);
    f.dispose();
  }
});

test("detach cancels reveal frames, pending measurements and all input bindings", () => {
  const f = fixture([["interaction", 224], ["a", 120], ["b", 120]], { scroll: 120 });
  f.reveal(); f.step(16); f.step(60); f.resize();
  const atDetach = f.viewport.scrollLeft;
  f.dispose();
  assert.equal(f.pending(), 0);
  assert.equal(f.bindings(), 0);
  assert.equal(f.nodes()[0].dataset.toolsReveal, undefined);
  f.step(400);
  assert.equal(f.viewport.scrollLeft, atDetach);
});

test("reattaching during a closed entry gate settles even without a new owner mutation", () => {
  for (const reduced of [false, true]) {
    const f = fixture([["todo", 100]], { scroll: 80, reduced, entering: false });
    assert.deepEqual(f.settled, []);
    assert.equal(f.viewport.scrollLeft, 0);
    assert.equal(f.range.style.width, "216px");
    f.step(16);
    assert.deepEqual(f.settled, ["session:root:1"]);
    f.dispose();
  }
});

test("reattaching a pending owner still waits for timed exits under Reduced Motion", () => {
  for (const reduced of [false, true]) {
    const f = fixture([["todo", 100], ["subagent:a", 120]],
      { reduced, entering: false, outgoing: ["todo"] });
    assert.deepEqual(f.settled, []);
    f.progress({ todo: 0 }); f.step(16);
    assert.deepEqual(f.settled, [], "zero spatial height does not complete the opacity fade");
    f.exiting([]); f.step(16);
    assert.deepEqual(f.settled, []);
    f.step(16);
    assert.deepEqual(f.settled, ["session:root:1"]);
    assert.equal(f.viewport.scrollLeft, 0);
    assert.ok(f.nodes().every(node => node.style.translate === ""));
    f.dispose();
  }
});

test("owner replacement drops old travel and snaps hidden destination without a ghost range", () => {
  for (const width of [240, 840]) for (const reduced of [false, true]) for (const rtl of [false, true]) {
    const f = fixture([["interaction", width - 24, 160], ["todo", 160], ["subagent:a", 180]],
      { width, scroll: rtl ? -120 : 120, reduced, rtl });
    const painted = f.nodes().map(node => node.getBoundingClientRect());
    f.exiting(["interaction", "todo", "subagent:a"]);
    f.select("session:other:2"); f.step(16);
    assert.deepEqual(f.settled, []);
    assert.deepEqual(f.nodes().map(node => node.getBoundingClientRect()), painted);
    f.update([["todo", 100]]); f.exiting([]); f.step(16);
    assert.deepEqual(f.settled, []);
    f.step(16);
    assert.deepEqual(f.settled, ["session:other:2"]);
    assert.equal(f.viewport.scrollLeft, 0);
    assert.equal(f.range.style.width, `${Math.max(width - 24, 100)}px`);
    assert.equal(f.pending(), 0);
    f.update([["todo", 100], ["subagent:new", 180]]); f.step(16); f.step(180);
    assert.ok(f.nodes().every(node => node.style.translate === ""));
    assert.equal(f.viewport.scrollLeft, 0);
    f.step(400);
    assert.equal(f.viewport.scrollLeft, 0);
    f.dispose();
  }
});

test("replacement waits for every exit and admits only the latest rapid target", () => {
  const f = fixture([["interaction", 224, 160], ["todo", 100], ["subagent:a", 120]], { scroll: 140 });
  f.exiting(["interaction", "todo"]); f.select("session:middle:2"); f.step(16);
  f.select("session:latest:3"); f.step(16);
  assert.deepEqual(f.settled, []);
  f.exiting(["interaction"]); f.step(16);
  assert.deepEqual(f.settled, []);
  f.update([["todo", 180], ["subagent:a", 120]]); f.exiting([]); f.step(16);
  assert.deepEqual(f.settled, []);
  f.step(16);
  assert.deepEqual(f.settled, ["session:latest:3"]);
  assert.equal(f.viewport.scrollLeft, 0);
  assert.equal(f.range.style.width, "308px");
  f.dispose();
});

test("same-family nodes stay keyed while outgoing Interaction and Todo geometry is discarded", () => {
  for (const width of [240, 840]) {
    const f = fixture([["interaction", width - 24, 160], ["todo", 180], ["subagent:a", 120], ["subagent:b", 120]],
      { width, scroll: 180 });
    const retained = f.nodes().slice(2);
    f.exiting(["interaction", "todo"]); f.select("session:child:2"); f.step(16);
    f.progress({ interaction: .5, todo: .5 }); f.step(16);
    assert.deepEqual(f.settled, []);
    assert.equal(f.chat.style["--chat-tools-lift"], "80px");
    f.update([["subagent:a", 120], ["subagent:b", 120]]); f.exiting([]); f.step(16);
    assert.equal(f.nodes()[0], retained[0]); assert.equal(f.nodes()[1], retained[1]);
    assert.equal(f.chat.style["--chat-tools-message-inset"], "56px");
    assert.equal(f.range.style.width, `${Math.max(width - 24, 248)}px`);
    assert.equal(f.viewport.scrollLeft, 0);
    f.update([["interaction", width - 24, 120], ["todo", 100], ["subagent:a", 120], ["subagent:b", 120]]);
    f.step(16); f.step(180);
    assert.equal(f.nodes()[2], retained[0]); assert.equal(f.nodes()[3], retained[1]);
    assert.ok(f.nodes().every(node => node.style.translate === ""));
    if (width === 240) assert.equal(f.nodes()[0].getBoundingClientRect().left, 12);
    f.dispose();
  }
});

test("a replacement cancels an active old placement and later same-owner insertion still animates", () => {
  const agents = [["subagent:a", 120], ["subagent:b", 120], ["subagent:c", 120]];
  const f = fixture(agents, { scroll: 100 });
  f.update([["todo", 100], ...agents]); f.step(16); f.step(40);
  assert.ok(f.nodes().some(node => node.style.translate));
  const painted = f.nodes().map(node => node.getBoundingClientRect().left);
  f.exiting(["todo"]); f.select("session:child:2"); f.step(16);
  assert.deepEqual(f.nodes().map(node => node.getBoundingClientRect().left), painted);
  f.update(agents); f.exiting([]); f.step(16); f.step(16);
  f.resize(); f.step(16);
  f.viewport.scrollLeft = 100; f.input("scroll");
  f.update([["todo", 100], ...agents]); f.step(16);
  assert.equal(f.nodes()[1].style.translate, "-108px 0");
  f.step(180);
  assert.equal(f.viewport.scrollLeft, 208);
  f.dispose();
});

test("cross-family outgoing Todo stays fixed through prefix disposal and hidden layout settles before entry", () => {
  for (const width of [240, 840]) for (const rtl of [false, true]) {
    const f = fixture([["interaction", 224, 160], ["todo", 180], ["subagent:a", 120]],
      { width, scroll: width === 240 ? (rtl ? -120 : 120) : 0, rtl });
    const old = f.nodes()[1];
    const bounds = old.getBoundingClientRect(), scroll = f.viewport.scrollLeft;
    const range = `${Math.max(f.viewport.scrollWidth - 12, f.track.offsetWidth)}px`, trackWidth = f.track.offsetWidth;
    f.exiting(["interaction", "todo", "subagent:a"]); f.select("session:other:2"); f.step(16);
    const placement = old.style.translate;
    for (let frame = 1; frame <= 10; frame++) {
      if (frame === 3) f.update([["todo", 180], ["subagent:a", 120]]);
      f.progress({ todo: 1 - frame / 11 }); f.step(16);
      assert.deepEqual(old.getBoundingClientRect(), bounds, `outgoing frame ${frame}`);
      assert.equal(f.viewport.scrollLeft, scroll);
      assert.equal(f.range.style.width, range);
      assert.equal(f.track.offsetWidth, trackWidth);
      if (frame < 3) assert.equal(old.style.translate, placement);
      assert.deepEqual(f.settled, []);
    }
    f.update([["todo", 100]]); f.nodes()[0].dataset.owner = "session:other";
    f.exiting([]); f.step(16);
    assert.deepEqual(f.settled, [], "destination remains hidden on its first natural layout frame");
    assert.equal(f.viewport.scrollLeft, 0);
    assert.equal(f.track.style.width, "");
    f.nodes()[0].naturalWidth = 140; f.resize(); f.step(16);
    assert.deepEqual(f.settled, [], "changed hidden geometry needs a placed frame of its own");
    const destination = f.nodes()[0].getBoundingClientRect();
    f.step(16);
    assert.deepEqual(f.settled, ["session:other:2"]);
    assert.deepEqual(f.nodes()[0].getBoundingClientRect(), destination);
    assert.equal(f.range.style.width, `${Math.max(width - 24, 140)}px`);
    f.dispose();
  }
});

test("Reduced Motion reattach preserves the frozen exit and hidden placement barrier", () => {
  const f = fixture([["interaction", 224, 160], ["todo", 180], ["subagent:a", 120]], { scroll: 120 });
  f.exiting(["interaction", "todo"]); f.select("session:other:2"); f.step(16);
  const old = f.nodes()[1], rect = old.getBoundingClientRect();
  const range = f.range.style.width;
  const dispose = f.reattach(true);
  assert.equal(f.range.style.width, range);
  f.update([["todo", 180], ["subagent:a", 120]]); f.progress({ todo: 0 }); f.step(16);
  assert.deepEqual(old.getBoundingClientRect(), rect);
  assert.deepEqual(f.settled, []);
  f.update([["todo", 100]]); f.exiting([]); f.step(16);
  assert.deepEqual(f.settled, []);
  f.step(16);
  assert.deepEqual(f.settled, ["session:other:2"]);
  dispose();
});

test("same-family offscreen prefix removal keeps the visible Subagent anchor at every frame", () => {
  for (const rtl of [false, true]) {
    const agents = [["subagent:a", 120], ["subagent:b", 120], ["subagent:c", 120]];
    const f = fixture([["interaction", 224, 160], ["todo", 180], ...agents],
      { scroll: rtl ? -460 : 460, rtl, family: "session:root" });
    f.viewport.dataset.toolsFamily = "session:root";
    f.select("session:root:1", "session:root"); f.step(16);
    const anchor = f.nodes()[2], rect = anchor.getBoundingClientRect();
    f.exiting(["interaction", "todo"]); f.select("session:child:2", "session:root"); f.step(16);
    f.progress({ interaction: .5, todo: .5 }); f.step(16);
    assert.deepEqual(anchor.getBoundingClientRect(), rect);
    f.update(agents); f.step(16);
    for (let frame = 0; frame < 12; frame++) {
      const current = anchor.getBoundingClientRect();
      assert.ok(Math.abs(current.left - rect.left) < 1e-9, `anchor left frame ${frame}`);
      assert.ok(Math.abs(current.right - rect.right) < 1e-9, `anchor right frame ${frame}`);
      f.step(16);
    }
    assert.deepEqual(f.settled, []);
    assert.equal(f.viewport.scrollLeft, rtl ? -40 : 40);
    f.dispose();
  }
});

test("structural prefix disposal preserves the maximum-scroll anchor before another frame", () => {
  for (const rtl of [false, true]) for (const reduced of [false, true]) {
    const agents = Array.from({ length: 16 }, (_, i) => [`subagent:${i}`, 120]);
    const f = fixture([["todo", 180], ...agents], { rtl, reduced, family: "session:root" });
    f.viewport.scrollLeft = (rtl ? -1 : 1) * (f.viewport.scrollWidth - f.viewport.clientWidth);
    f.input("scroll");
    const anchor = f.nodes().at(-1), rect = anchor.getBoundingClientRect();
    f.select("session:child:2", "session:root"); f.step(16);
    f.update(agents, true);
    assert.deepEqual(anchor.getBoundingClientRect(), rect, "DOM disposal is compensated before the next frame");
    for (let frame = 0; frame < 24; frame++) {
      f.step(16);
      assert.ok(Math.abs(anchor.getBoundingClientRect().left - rect.left) < 1e-9);
    }
    assert.equal(Math.abs(f.viewport.scrollLeft), f.viewport.scrollWidth - f.viewport.clientWidth);
    f.dispose();
  }
});

test("same-family visible prefix removal uses the existing placement tween", () => {
  const agents = [["subagent:a", 120], ["subagent:b", 120]];
  const f = fixture([["todo", 180], ...agents], { family: "session:root" });
  f.viewport.dataset.toolsFamily = "session:root";
  f.select("session:root:1", "session:root"); f.step(16);
  const anchor = f.nodes()[1], left = anchor.getBoundingClientRect().left;
  f.select("session:child:2", "session:root"); f.exiting(["todo"]); f.step(16);
  f.progress({ todo: .5 }); f.step(16);
  f.update(agents); f.step(16);
  assert.equal(anchor.getBoundingClientRect().left, left);
  f.step(90);
  assert.ok(anchor.getBoundingClientRect().left < left && anchor.getBoundingClientRect().left > 12);
  f.step(90);
  assert.equal(anchor.getBoundingClientRect().left, 12);
  assert.equal(f.viewport.scrollLeft, 0);
  assert.deepEqual(f.settled, []);
  f.dispose();
});

test("same-family return reinserts parent tools outside the retained anchor at every frame", () => {
  for (const rtl of [false, true]) for (const reduced of [false, true]) {
    const agents = [["subagent:a", 120], ["subagent:b", 120], ["subagent:c", 120]];
    const f = fixture(agents, { scroll: rtl ? -40 : 40, rtl, reduced, family: "session:root" });
    const anchor = f.nodes()[0], rect = anchor.getBoundingClientRect();
    f.select("session:root:2", "session:root");
    f.update([["interaction", 224, 160], ["todo", 180], ...agents]); f.step(16);
    for (let frame = 0; frame < 13; frame++) {
      const current = anchor.getBoundingClientRect();
      assert.ok(Math.abs(current.left - rect.left) < 1e-9, `return anchor left frame ${frame}`);
      assert.ok(Math.abs(current.right - rect.right) < 1e-9, `return anchor right frame ${frame}`);
      assert.equal(f.nodes()[2], anchor);
      f.progress({ interaction: frame / 12, todo: frame / 12 }); f.step(16);
    }
    assert.equal(f.viewport.scrollLeft, rtl ? -460 : 460);
    assert.deepEqual(f.settled, []);
    f.dispose();
  }
});

test("row order, single scroller, shadow, localized label and source icon remain wired", () => {
  const row = source("tools-row.js"), css = readWebUiStyles(), subagents = source("subagents.js");
  const markup = row.slice(row.indexOf('return html`<div class="tools-row"'));
  assert.ok(markup.indexOf("<${InteractionBar}") < markup.indexOf("<${TodoBar}"));
  assert.ok(markup.indexOf("<${TodoBar}") < markup.indexOf("<${SubagentsBar}"));
  assert.ok(source("composer.js").includes("<${ChatToolsRow}"));
  assert.ok(!css.includes(".subagent-row"));
  assert.match(css, /\.tools-scroll \{[^}]*overflow-x: auto/);
  assert.match(css, /\.subagent-capsule \{[^}]*box-shadow: var\(--shadow-4\)/);
  assert.ok(subagents.includes("<small>${t.sourceSubagent}</small>"));
  assert.ok(subagents.includes("ICON_ACCOUNT_TREE"));
  assert.ok(source("message-user.js").includes('subagent: [ICON_ACCOUNT_TREE, "sourceSubagent"]'));
  assert.ok(source("shell.js").includes("composer.getBoundingClientRect().top + toolsInset"));
  assert.ok(!css.includes("--chat-todo-lift"));
});

test("edge masks follow physical overflow in both directions without moving the anchor", () => {
  for (const rtl of [false, true]) for (const reduced of [false, true]) {
    const f = fixture([["todo", 180], ["agent:a", 180]], { rtl, reduced });
    const edge = side => f.viewport.style[`--tools-edge-${side}`];
    assert.equal(edge("left"), rtl ? "1" : "0");
    assert.equal(edge("right"), rtl ? "0" : "1");
    const maximum = f.viewport.scrollWidth - f.viewport.clientWidth;
    f.viewport.scrollLeft = (rtl ? -1 : 1) * maximum / 2;
    const before = f.nodes()[0].getBoundingClientRect().left;
    f.input("scroll");
    assert.equal(edge("left"), "1");
    assert.equal(edge("right"), "1");
    assert.equal(f.nodes()[0].getBoundingClientRect().left, before);
    f.viewport.scrollLeft = (rtl ? -1 : 1) * maximum;
    f.input("scroll");
    assert.equal(edge("left"), rtl ? "0" : "1");
    assert.equal(edge("right"), rtl ? "1" : "0");
    f.update([]); f.step(16); f.step(180);
    assert.equal(edge("left"), "0");
    assert.equal(edge("right"), "0");
    f.dispose();
    assert.equal(edge("left"), undefined);
  }
});

test("fully visible tools have no fade and floating scrollbar remains outside the mask", () => {
  const f = fixture([["agent:a", 100]], { width: 400 });
  assert.equal(f.viewport.style["--tools-edge-left"], "0");
  assert.equal(f.viewport.style["--tools-edge-right"], "0");
  f.dispose();
  const css = readWebUiStyles(), row = source("tools-row.js");
  assert.match(css, /\.tools-scroll \{\s*mask-mode: alpha;\s*mask-image: linear-gradient\(to right/);
  assert.match(css, /--tools-edge-left 200ms cubic-bezier\(\.4, 0, \.2, 1\)/);
  assert.match(row, /<\/div><div class="scrollbar-overlay tools-scrollbar"/);
  assert.match(css, /\.scrollbar-overlay\.scrollbar-horizontal \{ width: 0; height: 12px; \}/);
});
