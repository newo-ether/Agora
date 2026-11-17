import assert from "node:assert/strict";
import fs from "node:fs";
import test from "node:test";
import vm from "node:vm";
const helper = fs.readFileSync(new URL("../../main/assets/webui/tail-follow.js", import.meta.url), "utf8");
const { createStreamingTailFollow, coalescedScrollStep, bindTailScrollInput, TailMode } =
  await import(`data:text/javascript;base64,${Buffer.from(helper).toString("base64")}`);
const availability = (tail, now = 0, values = {}) => tail.update({
  generating: true, blocked: false, handoff: false, ...values,
}, now);
const attached = () => {
  const tail = createStreamingTailFollow();
  availability(tail);
  tail.proximity(0, false, 0);
  return tail;
};

test("generation arms and only an idle viewport within physical bottom 48px attaches", () => {
  const tail = createStreamingTailFollow();
  availability(tail);
  assert.equal(tail.mode, TailMode.ARMED);
  tail.proximity(48.01, false, 0);
  assert.equal(tail.mode, TailMode.ARMED);
  tail.proximity(48, true, 0);
  assert.equal(tail.mode, TailMode.ARMED);
  tail.proximity(48, false, 0);
  assert.equal(tail.mode, TailMode.ATTACHED);
});
test("drag detaches and returning to bottom only reattaches after scrolling ends", () => {
  const tail = attached();
  tail.userDrag();
  availability(tail, 20);
  tail.proximity(0, true, 20);
  assert.equal(tail.mode, TailMode.DETACHED);
  assert.equal(tail.frame(100, 30), 0);
  tail.proximity(0, false, 40);
  assert.equal(tail.mode, TailMode.ATTACHED);
  assert.ok(tail.frame(100, 56) > 0);
});
test("attached correction uses cumulative extent, tau .055 and 2800px/s frame cap", () => {
  const tail = attached();
  assert.ok(Math.abs(tail.frame(10000, 16) - 44.8) < 1e-9);
  assert.equal(tail.frame(10000, 1000), 140);
  assert.ok(Math.abs(coalescedScrollStep(20, .016, .055, 2800, 2) - 20 * (1 - Math.exp(-.016 / .055))) < 1e-9);
  assert.equal(tail.frame(0, 1016), 0);
  assert.ok(tail.following);
});
test("programmatic handoff preserves attachment and suppresses tail corrections", () => {
  const tail = attached();
  availability(tail, 10, { handoff: true });
  assert.equal(tail.mode, TailMode.ATTACHED);
  assert.equal(tail.frame(300, 30), 0);
  availability(tail, 40);
  assert.ok(tail.following);
  assert.ok(tail.frame(300, 56) > 0);
});
for (const blocker of ["Stop", "search", "switch", "branch", "Reduced Motion"]) {
  test(`${blocker} blocks the existing tail owner`, () => {
    const tail = attached();
    availability(tail, 20, { blocked: true, handoff: true });
    tail.proximity(0, false, 20);
    assert.equal(tail.mode, TailMode.DETACHED);
    assert.equal(tail.frame(100, 40), 0);
  });
}
test("phone setting false prevents follow, true can reacquire at bottom", () => {
  const tail = attached();
  availability(tail, 20, { stickToBottom: false });
  tail.proximity(0, false, 20);
  assert.equal(tail.frame(100, 40), 0);
  availability(tail, 50);
  tail.proximity(49, false, 50);
  assert.equal(tail.mode, TailMode.DETACHED);
  tail.proximity(48, false, 60);
  assert.equal(tail.mode, TailMode.ATTACHED);
});
test("generation completion settles at least 700ms and eight stable frames", () => {
  const tail = attached();
  availability(tail, 100, { generating: false });
  assert.equal(tail.mode, TailMode.SETTLING);
  for (let now = 116; now < 800; now += 16) {
    tail.frame(0, now);
    assert.equal(tail.mode, TailMode.SETTLING);
  }
  tail.frame(0, 800);
  assert.equal(tail.mode, TailMode.INACTIVE);
});
test("late terminal growth clears stability and follows until eight stable frames", () => {
  const tail = attached();
  availability(tail, 0, { generating: false });
  for (let now = 16; now < 700; now += 16) tail.frame(0, now);
  assert.ok(tail.frame(200, 704) > 0);
  for (let index = 1; index < 8; index++) {
    tail.frame(0, 704 + index * 16);
    assert.equal(tail.mode, TailMode.SETTLING);
  }
  tail.frame(0, 832);
  assert.equal(tail.mode, TailMode.INACTIVE);
  assert.equal(tail.frame(200, 848), 0);
});
test("unstable terminal layout has a 1600ms cap and drag cancels settlement", () => {
  const tail = attached();
  availability(tail, 100, { generating: false });
  tail.frame(200, 1699);
  assert.equal(tail.mode, TailMode.SETTLING);
  tail.frame(200, 1700);
  assert.equal(tail.mode, TailMode.INACTIVE);
  const dragged = attached();
  availability(dragged, 100, { generating: false });
  dragged.userDrag();
  assert.equal(dragged.frame(200, 120), 0);
});
test("conversation reset clears prior mode and settlement", () => {
  const tail = attached();
  tail.reset();
  assert.equal(tail.mode, TailMode.INACTIVE);
  assert.equal(tail.frame(200, 120), 0);
});

function inputFixture() {
  const listeners = new Map(), windowListeners = new Map(), timers = new Map();
  let starts = 0, stops = 0, serial = 0;
  const target = { closest: () => null };
  const view = { addEventListener: (type, fn) => windowListeners.set(type, fn),
    removeEventListener: type => windowListeners.delete(type) };
  const root = { ownerDocument: { defaultView: view }, scrollTop: 200, scrollHeight: 1000, clientHeight: 500,
    closest: () => null,
    addEventListener: (type, fn) => listeners.set(type, fn), removeEventListener: type => listeners.delete(type) };
  target.parentElement = root;
  const context = vm.createContext({
    setTimeout: fn => { const id = ++serial; timers.set(id, fn); return id; },
    clearTimeout: id => timers.delete(id), getComputedStyle: () => ({ overflowY: "auto" }),
  });
  vm.runInContext(helper.replaceAll("export ", "") + "\nglobalThis.bind = bindTailScrollInput;", context);
  const scrollbarListeners = new Map();
  const scrollbar = { classList: { contains: () => true },
    addEventListener: (type, fn) => scrollbarListeners.set(type, fn),
    removeEventListener: type => scrollbarListeners.delete(type) };
  const input = context.bind(root, { start: () => starts++, stop: () => stops++ }, scrollbar);
  return { input, target, root, listeners, windowListeners, scrollbarListeners,
    get starts() { return starts; }, get stops() { return stops; },
    emit: (type, values = {}) => listeners.get(type)?.({ target, ...values }),
    up: values => windowListeners.get("pointerup")?.(values),
    idle: () => { const pending = [...timers.values()]; timers.clear(); pending.forEach(fn => fn()); },
  };
}
test("ordinary click, selection drag, touch tap, horizontal pan and unrelated key preserve attachment", () => {
  const f = inputFixture();
  f.emit("pointerdown", { pointerType: "mouse", pointerId: 1, clientX: 0, clientY: 0 });
  f.emit("pointermove", { pointerType: "mouse", pointerId: 1, clientX: 0, clientY: 100 });
  f.emit("pointerdown", { pointerType: "touch", pointerId: 2, clientX: 0, clientY: 0 });
  f.up({ pointerId: 2 });
  f.emit("pointerdown", { pointerType: "touch", pointerId: 3, clientX: 0, clientY: 0 });
  f.emit("pointermove", { pointerId: 3, clientX: 80, clientY: 10 });
  f.emit("keydown", { key: "c", ctrlKey: true });
  f.emit("keydown", { key: "Enter" });
  f.emit("wheel", { deltaX: 100, deltaY: 1 });
  f.emit("wheel", { deltaY: 100, ctrlKey: true });
  assert.equal(f.starts, 0);
  f.input.dispose();
  assert.equal(f.listeners.size, 0);
  assert.equal(f.windowListeners.size, 0);
});
test("vertical touch drag detaches before scroll and remains detached through momentum", () => {
  const f = inputFixture();
  f.emit("pointerdown", { pointerType: "touch", pointerId: 1, clientX: 0, clientY: 100 });
  f.emit("pointermove", { pointerId: 1, clientX: 0, clientY: 80 });
  assert.equal(f.starts, 1);
  f.idle();
  assert.equal(f.stops, 0);
  f.up({ pointerId: 1 });
  f.emit("scroll");
  assert.ok(f.input.scrolling);
  f.emit("scrollend");
  assert.equal(f.stops, 1);
  assert.ok(!f.input.scrolling);
  f.input.dispose();
});
test("wheel, scrolling keys and scrollbar intent stop through one idle fallback", () => {
  const f = inputFixture();
  for (const key of ["ArrowUp", "ArrowDown", "PageUp", "PageDown", "Home", "End", " "]) {
    f.emit("keydown", { key });
    assert.ok(f.input.scrolling);
    f.idle();
  }
  f.emit("wheel", { deltaY: -40 }); f.idle();
  f.input.scrollbarInput(); f.idle();
  assert.equal(f.starts, 9);
  assert.equal(f.stops, 9);
  f.input.dispose();
});
test("nested scroll hosts consume their own wheel and control keys", () => {
  const f = inputFixture();
  const nested = { scrollHeight: 500, clientHeight: 100, scrollTop: 200, parentElement: f.root,
    closest: () => null };
  f.emit("wheel", { target: nested, deltaY: 30 });
  f.emit("keydown", { target: { closest: () => ({}) }, key: "ArrowDown" });
  assert.equal(f.starts, 0);
  nested.scrollTop = 400;
  f.emit("wheel", { target: nested, deltaY: 30 });
  assert.equal(f.starts, 1);
  f.input.dispose();
});

const source = fs.readFileSync(new URL("../../main/assets/webui/message-scroll.js", import.meta.url), "utf8")
  .replaceAll("\r\n", "\n");
const start = source.indexOf("  useLayoutEffect(() => {\n    const root = scroller.current;\n    const column = root?.firstElementChild;");
const effectEnd = "    pageLoading, state.display?.reduceMotion, state.display?.stickToBottom]);";
const endMarker = source.indexOf(effectEnd, start);
assert.ok(start > 0 && endMarker > start);
const end = endMarker + effectEnd.length;
function listFixture({ remaining = 0, generating = true, setting = true, stopping = false, reduced = false } = {}) {
  let now = 0, height = 1500, top = 1000 - remaining, sequence = 0, cleanup;
  const frames = new Map();
  const root = { clientHeight: 500, firstElementChild: { querySelectorAll: () => [] },
    get scrollHeight() { return height; }, get scrollTop() { return top; },
    set scrollTop(value) { top = Math.max(0, Math.min(height - 500, value)); } };
  const observers = [];
  const ref = current => ({ current });
  const context = vm.createContext({
    useLayoutEffect: effect => { cleanup = effect(); },
    state: { openId: "a", openStatus: "ready", path: [{ id: "m" }], bodies: new Map([["m", {}]]),
      display: { stickToBottom: setting, reduceMotion: reduced }, composer: { stopping } },
    scroller: ref(root), tailFollow: ref(createStreamingTailFollow()), tailInput: ref({ scrolling: false }),
    tailInputHandlers: ref(null), generationActive: ref(generating), pinned: ref(false),
    bottomMotion: ref(null), bottomSeek: ref(null), bottomRequested: ref(false),
    searchPosition: ref(null), searchTarget: ref(null), branchScroll: ref(null),
    pageInput: ref({}), refreshBottom: ref(() => {}), pageAtEdge: ref(() => {}),
    switching: null, settledOpenId: "a", covered: false, requestedReady: true, tailReady: true,
    pageLoading: false, branch: null, branchTarget: null, search: null, ids: "m", pagePath: [{ id: "m" }],
    sync: { consumeScroll: () => {} }, BOTTOM_MOTION: { seekTauMeasuredSeconds: .09, seekTauUnmeasuredSeconds: .16,
      seekMaxVelocityMeasuredViewports: 16, seekMaxVelocityUnmeasuredViewports: 52,
      maximumFrameStepViewportFraction: .82, minimumStepPx: 2, tolerancePx: 1.5,
      stableFrames: 4, blockedFrames: 12, maximumDurationMs: 30000, sendStartupMs: 240,
      settlingGenerationMs: 700, settlingIdleMs: 192, settlingTimeoutMs: 1600, settlingStableFrames: 6 },
    coalescedScrollStep, fastOutSlowIn: value => Math.max(0, Math.min(1, value)),
    performance: { now: () => now },
    requestAnimationFrame: fn => { const id = ++sequence; frames.set(id, fn); return id; },
    cancelAnimationFrame: id => frames.delete(id),
    ResizeObserver: class { constructor(fn) { observers.push(fn); } observe() {} disconnect() {} },
  });
  const code = source.slice(start, end);
  const run = () => vm.runInContext(code, context);
  run();
  return { context, root, get top() { return top; }, get scheduled() { return frames.size; },
    grow: amount => { height += amount; observers.at(-1)(); },
    render: () => { cleanup(); run(); },
    tick: () => { now += 16; const pending = [...frames.values()]; frames.clear(); pending.forEach(fn => fn()); },
    cleanup: () => cleanup(),
  };
}
test("production MessageList follows growth and settles late terminal growth without another actor", () => {
  const f = listFixture();
  f.grow(200);
  f.tick();
  assert.ok(f.top > 1000 && f.top < 1200);
  assert.equal(f.scheduled, 1);
  f.context.generationActive.current = false;
  f.render();
  f.grow(80);
  for (let i = 0; i < 45; i++) f.tick();
  assert.ok(1280 - f.top <= 1);
  for (let i = 0; i < 10; i++) f.tick();
  assert.equal(f.context.tailFollow.current.mode, TailMode.INACTIVE);
  const top = f.top;
  f.grow(200); f.tick();
  assert.equal(f.top, top);
  f.cleanup();
});
for (const options of [{ setting: false }, { stopping: true }, { reduced: true }]) {
  test(`production MessageList blocks growth: ${JSON.stringify(options)}`, () => {
    const f = listFixture(options);
    f.grow(100); f.tick();
    assert.equal(f.top, 1000);
    f.cleanup();
  });
}
test("setting off during terminal settlement keeps the native already-attached settlement", () => {
  const tail = attached();
  availability(tail, 100, { generating: false });
  availability(tail, 120, { generating: false, stickToBottom: false });
  assert.equal(tail.mode, TailMode.SETTLING);
  assert.ok(tail.frame(100, 136) > 0);
});
test("blocked release reacquires only while idle and physically near bottom", () => {
  const tail = attached();
  availability(tail, 10, { blocked: true });
  availability(tail, 20);
  tail.proximity(80, false, 20);
  assert.equal(tail.mode, TailMode.DETACHED);
  tail.proximity(40, true, 30);
  assert.equal(tail.mode, TailMode.DETACHED);
  tail.proximity(40, false, 40);
  assert.equal(tail.mode, TailMode.ATTACHED);
});
test("scrollbar hold stays detached until pointer release even without movement", () => {
  const f = inputFixture();
  f.scrollbarListeners.get("pointerdown")({ button: 0 });
  f.idle();
  f.emit("scrollend");
  assert.ok(f.input.scrolling);
  assert.equal(f.stops, 0);
  f.up({ pointerId: 4 });
  f.idle();
  assert.ok(!f.input.scrolling);
  assert.equal(f.stops, 1);
  f.input.dispose();
  assert.equal(f.scrollbarListeners.size, 0);
});
test("wheel and keys with no effective scroll do not detach", () => {
  const f = inputFixture();
  f.root.scrollTop = 0;
  f.emit("wheel", { deltaY: -20 });
  f.emit("keydown", { key: "Home" });
  f.root.scrollTop = 500;
  f.emit("wheel", { deltaY: 20 });
  f.emit("keydown", { key: "End" });
  assert.equal(f.starts, 0);
  f.input.dispose();
});
for (const origin of ["bottom button", "send"]) {
  test(`production ${origin} seek remains explicit when setting is off`, () => {
    const f = listFixture({ remaining: 800, setting: false });
    if (origin === "send") {
      f.context.state.scrollRequest = { messageId: "m" };
      f.render();
    } else f.context.bottomSeek.current();
    for (let i = 0; i < 250; i++) f.tick();
    assert.ok(1000 - f.top <= 1.5);
    assert.ok(!f.context.tailFollow.current.following);
    const top = f.top;
    f.grow(200); f.tick();
    assert.equal(f.top, top);
    f.cleanup();
  });
}
test("production detached terminal growth never pulls a historical reader back", () => {
  const f = listFixture();
  f.context.tailInput.current.scrolling = true;
  f.context.tailInputHandlers.current.start();
  f.root.scrollTop = 500;
  f.context.tailInput.current.scrolling = false;
  f.context.tailInputHandlers.current.stop();
  f.context.generationActive.current = false;
  f.render();
  f.grow(400);
  for (let i = 0; i < 120; i++) f.tick();
  assert.equal(f.top, 500);
  assert.equal(f.context.tailFollow.current.mode, TailMode.INACTIVE);
  f.cleanup();
});

test("a new generation takes over an attached terminal settlement", () => {
  for (const handoff of [false, true]) {
    const tail = attached();
    availability(tail, 100, { generating: false });
    assert.equal(tail.mode, TailMode.SETTLING);
    availability(tail, 200, { handoff });
    if (handoff) {
      assert.equal(tail.frame(200, 216), 0);
      availability(tail, 220);
    }
    tail.proximity(0, false, 230);
    assert.equal(tail.mode, TailMode.ATTACHED);
    assert.ok(tail.following);
    assert.ok(tail.frame(200, 246) > 0);
    tail.frame(0, 2000);
    assert.equal(tail.mode, TailMode.ATTACHED);
  }
});
test("production growth follows a new generation started during terminal settlement", () => {
  const f = listFixture();
  f.context.generationActive.current = false;
  f.render();
  for (let i = 0; i < 10; i++) f.tick();
  assert.equal(f.context.tailFollow.current.mode, TailMode.SETTLING);
  f.context.generationActive.current = true;
  f.render();
  f.grow(200);
  f.tick();
  assert.ok(f.top > 1000);
  assert.equal(f.context.tailFollow.current.mode, TailMode.ATTACHED);
  f.cleanup();
});

test("production MessageList drag and idle-bottom handoff preserve historical position", () => {
  const f = listFixture();
  f.context.tailInput.current.scrolling = true;
  f.context.tailInputHandlers.current.start();
  f.root.scrollTop = 600;
  f.grow(100); f.tick();
  assert.equal(f.top, 600);
  f.root.scrollTop = 1100;
  f.context.tailInput.current.scrolling = false;
  f.context.tailInputHandlers.current.stop();
  f.grow(100); f.tick();
  assert.ok(f.top > 1100);
  f.cleanup();
});
