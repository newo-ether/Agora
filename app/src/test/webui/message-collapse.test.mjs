import assert from "node:assert/strict";
import fs from "node:fs";
import test from "node:test";
import vm from "node:vm";
import { h } from "../../main/assets/webui/vendor/preact.mjs";
import htm from "../../main/assets/webui/vendor/htm.mjs";

const root = new URL("../../main/assets/webui/", import.meta.url);
const source = fs.readFileSync(new URL("message-collapse.js", root), "utf8")
  .replace(/^import\b[\s\S]*?;\r?\n/gm, "").replace(/\bexport /g, "");
function nodes(tree) {
  if (Array.isArray(tree)) return tree.flatMap(nodes);
  if (!tree || typeof tree !== "object" || !tree.props) return [];
  return [tree, ...nodes(tree.props.children)];
}

// Exercise the production component with stable mounted refs and controlled body geometry.
function fixture({ height = 800, ...initial } = {}) {
  const hooks = [], pending = [], observers = [];
  let cursor = 0, dirty, props = { messageKey: "message", children: h("p", {}, "Original body"), ...initial };
  let tree;
  const events = [], frames = new Map();
  const viewport = { dispatchEvent: event => events.push(event), getAnimations: () => [] };
  const footer = { getBoundingClientRect: () => ({ height: 48 }) };
  const body = { getBoundingClientRect: () => ({ height }) };
  const context = vm.createContext({
    html: htm.bind(h), t: { expand: "Expand", collapse: "Collapse" },
    CustomEvent: class { constructor(type, options) { this.type = type; Object.assign(this, options); } },
    requestAnimationFrame: callback => { frames.set(callback, callback); return callback; },
    cancelAnimationFrame: frame => frames.delete(frame),
    useRef(value) { const at = cursor++; return hooks[at] ??= { current: value }; },
    useState(value) {
      const at = cursor++, hook = hooks[at] ??= { value };
      return [hook.value, next => {
        if (typeof next === "function") next = next(hook.value);
        if (next !== hook.value) { hook.value = next; dirty = true; }
      }];
    },
    useLayoutEffect(callback, deps) {
      const at = cursor++, old = hooks[at];
      if (!old || deps.some((value, index) => value !== old.deps[index])) {
        hooks[at] = { deps, cleanup: old?.cleanup };
        pending.push(() => { old?.cleanup?.(); hooks[at].cleanup = callback(); });
      }
    },
    ResizeObserver: class {
      constructor(callback) { this.callback = callback; observers.push(this); }
      observe(node) { assert.ok(node === body || node === footer); }
      disconnect() { this.disconnected = true; }
    },
  });
  vm.runInContext(source, context, { filename: "message-collapse.js" });
  function render(patch = {}) {
    props = { ...props, ...patch };
    for (let pass = 0; pass < 10; pass++) {
      cursor = 0; dirty = false;
      tree = context.CollapsibleMessageBody(props);
      nodes(tree).filter(node => node.ref).forEach(node => {
        node.ref.current = node.props.class === "collapsible-message" ? viewport :
          node.props.class === "message-collapse" ? footer : body;
      });
      pending.splice(0).forEach(effect => effect());
      if (!dirty) return tree;
    }
    throw new Error("Collapse component did not settle");
  }
  render();
  return {
    render,
    events,
    settle(propertyName = "height") { tree.props.onTransitionEnd({ target: viewport, propertyName }); },
    get tree() { return tree; },
    get control() { return nodes(tree).find(node => node.props.class === "message-expand"); },
    get button() { return nodes(tree).find(node => node.type === "button" && !node.props["aria-hidden"]); },
    get collapsed() { return nodes(tree).find(node => node.props.class === "message-preview").props["data-collapsed"]; },
    get children() { return nodes(tree).find(node => node.props.class === "message-preview-body").props.children; },
    resize(next) { height = next; observers.forEach(observer => observer.callback()); render(); },
    dispose() { hooks.forEach(hook => hook?.cleanup?.()); assert.ok(observers.every(observer => observer.disconnected)); },
  };
}

test("long historical body collapses, expands and retains its exact child subtree", () => {
  const f = fixture(), original = f.children;
  assert.equal(f.collapsed, true);
  assert.equal(f.button.props.children, "Expand");
  assert.equal(f.button.props["aria-expanded"], false);
  f.button.props.onClick(); f.render();
  assert.equal(f.collapsed, false);
  assert.equal(f.children, original);
  assert.equal(f.button.props.children, "Collapse");
  f.button.props.onClick(); f.render();
  assert.equal(f.collapsed, true);
  assert.equal(f.children, original);
  f.dispose();
});

test("production user caller alone owns disclosure while assistant prose, timeline and HTML stay unwrapped", () => {
  const context = vm.createContext({
    html: htm.bind(h),
    useRef: value => ({ current: value }), useState: value => [value, () => {}],
    useLayoutEffect() {}, useEffect() {},
    cubicBezierEasing: () => value => value,
    readableText: message => message.text.markdown,
    CSS: { highlights: new Map() },
    t: { generationStopped: "Generation stopped" },
    icon() {}, Markdown() {}, InfoGroup() {}, InfoCard() {}, GenerationActivity() {},
    AssistantActions() {}, RowAction() {}, DetailSheet() {}, MoreMenu() {},
    ICON_EDIT: "edit", ICON_MORE_VERT: "more", ICON_SCHEDULE: "task", ICON_REPEAT: "loop",
    ICON_ACCOUNT_TREE: "subagent", ICON_QUESTION_ANSWER: "question",
  });
  for (const file of ["message-collapse.js", "message-user.js", "message-model.js"]) {
    const code = fs.readFileSync(new URL(file, root), "utf8")
      .replace(/^import\b[\s\S]*?;\r?\n/gm, "").replace(/\bexport /g, "");
    vm.runInContext(code, context, { filename: file });
  }
  const raw = Array.from({ length: 30 }, (_, index) => `Original line ${index}`).join("\n");
  const props = { message: { id: "row", participant: "USER", text: { markdown: raw } }, matches: [],
    display: { reduceMotion: true }, reduceMotion: true, conversationId: "conversation" };
  const user = context.UserBubble(props);
  const disclosure = nodes(user).find(node => node.type === context.CollapsibleMessageBody);
  assert.ok(disclosure);
  assert.equal(disclosure.props.messageKey, "row");
  assert.equal(disclosure.props.searchActive, false);
  assert.ok(nodes(user).find(node => node.props.class === "user-bubble")
    .props.children.includes(disclosure));
  for (const presentation of [
    { answer: raw, stoppedBar: true },
    { answer: `${raw}\n\n\`\`\`html-preview\n<p>Full preview</p>\n\`\`\`` },
    { useTimeline: true, stoppedBar: true, blocks: [
      { type: "answer", index: 0, text: { markdown: raw }, sourceStart: 0, sourceLength: raw.length },
      { type: "answer", index: 1, text: { markdown: "Final answer" }, sourceStart: raw.length, sourceLength: 12 },
    ] },
  ]) {
    const tree = context.ModelMessage({ ...props,
      message: { ...props.message, participant: "MODEL", status: "STOPPED", presentation } });
    assert.equal(nodes(tree).some(node => node.type === context.CollapsibleMessageBody), false);
    const markdown = nodes(tree).filter(node => node.type?.name === "Markdown");
    assert.equal(markdown.length, presentation.useTimeline ? 2 : 1);
    assert.equal(markdown[0].props.text, presentation.answer ?? presentation.blocks[0].text);
    if (presentation.stoppedBar) {
      const terminal = nodes(tree).find(node => node.props.class?.includes("stopped-bar"));
      assert.ok(terminal);
      assert.equal(terminal.props.children, "Generation stopped");
    }
  }
});

test("search temporarily reveals the full content without overwriting the manual choice", () => {
  const f = fixture();
  f.render({ searchActive: true });
  assert.equal(f.collapsed, false);
  assert.equal(f.button, undefined);
  f.render({ searchActive: false });
  assert.equal(f.collapsed, true);
  f.button.props.onClick(); f.render();
  f.render({ searchActive: true });
  f.render({ searchActive: false });
  assert.equal(f.collapsed, false);
  f.dispose();
});

test("disclosure changes only viewport height and brackets reversals with one layout mutation", () => {
  const f = fixture();
  assert.equal(f.events.length, 0);
  assert.equal(f.tree.props.style.height, "320px");
  f.button.props.onClick(); f.render();
  assert.equal(f.tree.props.style.height, "848px");
  assert.equal(f.events.length, 1);
  assert.equal(f.events[0].type, "agora-layout-mutation");
  assert.equal(f.events[0].detail.active, true);
  // Faster fades must not release the list anchor before the height reveal settles.
  f.settle("opacity");
  f.settle("--message-mask-alpha");
  assert.equal(f.events.length, 1);
  const labels = nodes(f.tree).filter(node => node.type === "button").map(node => node.props.children);
  assert.deepEqual(labels, ["Collapse", "Expand"]);
  f.button.props.onClick(); f.render();
  assert.equal(f.tree.props.style.height, "320px");
  assert.equal(f.events.length, 1);
  f.settle();
  assert.equal(f.events.length, 2);
  assert.equal(f.events[1].detail.active, false);
  assert.equal(f.events[0].detail.key, f.events[1].detail.key);
  f.dispose();
});

test("reduced motion retains overlay labels without admitting spatial mutation", () => {
  const f = fixture({ reduceMotion: true });
  f.button.props.onClick(); f.render();
  assert.equal(f.tree.props.style.height, "848px");
  f.render({ searchActive: true });
  assert.equal(f.control.props.inert, true);
  assert.equal(nodes(f.tree).filter(node => node.type === "button").length, 2);
  assert.equal(f.tree.props.style.height, "800px");
  f.render({ searchActive: false, reduceMotion: false });
  assert.equal(f.events.length, 0);
  f.dispose();
});

test("short bodies have no disclosure and asynchronous media sizing updates overflow", () => {
  const f = fixture({ height: 120 });
  assert.equal(f.button, undefined);
  f.resize(320);
  assert.equal(f.button, undefined);
  f.resize(321);
  assert.equal(f.button.props.children, "Expand");
  f.resize(120);
  assert.equal(f.button, undefined);
  f.dispose();
});
