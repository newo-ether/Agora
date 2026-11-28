import assert from "node:assert/strict";
import fs from "node:fs";
import test from "node:test";
import vm from "node:vm";

const groups = [
  { id: "memory", tokens: 20, tools: [{ name: "memory_read", description: "Read memory", tokens: 10 }] },
  { id: "skills", tokens: 30, tools: [{ name: "skill_read", description: "Read a skill", tokens: 15 }] },
  { id: "mcp", tokens: 40, unavailable: "no_tools", tools: [] },
];

function fixture() {
  const sent = [];
  const context = vm.createContext({
    location: { protocol: "http:", host: "localhost" },
    sessionSignedIn: async () => true,
    performance: { now: () => 0 }, t: {},
    setTimeout: () => 0, clearTimeout() {},
    WebSocket: class { static OPEN = 1; },
  });
  const source = fs.readFileSync(new URL("../../main/assets/webui/sync.js", import.meta.url), "utf8");
  vm.runInContext(source.replace(/^import .*;\r?\n/gm, "").replace(/export /g, ""), context);
  context.transport = { readyState: 1, send: text => sent.push(JSON.parse(text)) };
  vm.runInContext(`socket = transport; openSeq = 7; update({ connected: true, connectionId: "browser",
    openId: "root", openStatus: "ready", composer: { conversationId: "root", seq: 7 } })`, context);
  return {
    sent,
    evaluate: code => vm.runInContext(code, context),
    receive(event) { vm.runInContext(`receive(${JSON.stringify(event)}, 0)`, context); },
  };
}

function catalogEvent(command, patch = {}) {
  return {
    type: "session_tools", connectionId: command.connectionId, conversationId: "root",
    seq: command.seq, revision: command.revision, groups,
    enabledGroups: ["memory", "skills"], toolSetTokens: 7, ...patch,
  };
}

function openCatalog(f) {
  const request = f.evaluate("sync.requestSessionTools()");
  assert.equal(request.revision, 1);
  assert.equal(f.evaluate("state.sessionTools.pending"), true);
  const command = f.sent.at(-1);
  assert.equal(command.type, "session_tools");
  f.receive(catalogEvent(command));
  assert.equal(f.evaluate("state.sessionTools.pending"), false);
  return command;
}

function enabled(f) {
  return Array.from(f.evaluate("state.sessionTools.enabledGroups"));
}

test("session tool catalog captures the current target and accepts a failed answer", () => {
  const f = fixture();
  assert.equal(f.evaluate("sync.requestSessionTools()").revision, 1);
  assert.deepEqual(f.sent[0], {
    type: "session_tools", connectionId: "browser", conversationId: "root", seq: 7, revision: 1,
  });
  f.receive({ type: "session_tools", connectionId: "browser", conversationId: "root", seq: 7,
    revision: 1, failed: true, groups: [], enabledGroups: [], toolSetTokens: 0 });
  assert.equal(f.evaluate("state.sessionTools.pending"), false);
  assert.equal(f.evaluate("state.sessionTools.failed"), true);
  assert.equal(f.evaluate("state.sessionTools.groups.length"), 0);
  assert.equal(f.evaluate("sync.toggleSessionToolGroup('memory', false)"), false);
});

test("session tool replies reject revision, connection, sequence and target mismatches", () => {
  const f = fixture();
  f.evaluate("sync.requestSessionTools()");
  const command = f.sent[0];
  const stale = [
    catalogEvent(command, { revision: 0 }),
    catalogEvent(command, { connectionId: "other" }),
    catalogEvent(command, { seq: 6 }),
    catalogEvent(command, { conversationId: "other" }),
  ];
  for (const event of stale) {
    f.receive(event);
    assert.equal(f.evaluate("state.sessionTools.pending"), true);
    assert.equal(f.evaluate("state.sessionTools.groups"), undefined);
  }
  f.receive(catalogEvent(command));
  assert.equal(f.evaluate("state.sessionTools.groups.length"), 3);
  assert.equal(f.evaluate("state.sessionTools.revision"), 1);
});

test("toggles send exact commands and reject unknown or unavailable groups", () => {
  const f = fixture();
  const command = openCatalog(f);
  assert.equal(f.evaluate("sync.toggleSessionToolGroup('unknown', false)"), false);
  assert.equal(f.evaluate("sync.toggleSessionToolGroup('mcp', true)"), false);
  assert.equal(f.sent.length, 1);
  assert.equal(f.evaluate("sync.toggleSessionToolGroup('memory', false)"), true);
  assert.deepEqual(f.sent[1], {
    type: "session_tool_toggle", connectionId: "browser", conversationId: "root", seq: 7,
    revision: command.revision, toolGroup: "memory", enabled: false,
  });
  assert.deepEqual(enabled(f), ["skills"]);
  f.receive(catalogEvent(command, { enabledGroups: ["skills"] }));
  assert.deepEqual(enabled(f), ["skills"]);
});

test("rapid toggles retain the unanswered suffix through every ordered reply", () => {
  for (const operations of [
    [["memory", false], ["skills", false]],
    [["memory", false], ["memory", true]],
  ]) {
    const f = fixture();
    const command = openCatalog(f);
    for (const [group, value] of operations) {
      assert.equal(f.evaluate(`sync.toggleSessionToolGroup(${JSON.stringify(group)}, ${value})`), true);
    }
    const optimistic = enabled(f);
    const firstServerState = operations[0][0] === "memory" && operations[0][1]
      ? ["memory", "skills"] : ["skills"];
    f.receive(catalogEvent(command, { enabledGroups: firstServerState }));
    assert.deepEqual(enabled(f), optimistic,
      "the first reply must not undo the second optimistic operation");
    assert.equal(f.evaluate("sessionToolsToggles.length"), 1);
    f.receive(catalogEvent(command, { enabledGroups: optimistic }));
    assert.deepEqual(enabled(f), optimistic);
    assert.equal(f.evaluate("sessionToolsToggles.length"), 0);
    assert.deepEqual(f.sent.slice(1).map(item => [item.toolGroup, item.enabled]), operations);
  }
});

test("a newer catalog revision discards old optimistic work and stale replies", () => {
  const f = fixture();
  const first = openCatalog(f);
  assert.equal(f.evaluate("sync.toggleSessionToolGroup('memory', false)"), true);
  assert.deepEqual(enabled(f), ["skills"]);
  assert.equal(f.evaluate("sync.requestSessionTools()").revision, 2);
  assert.equal(f.evaluate("sessionToolsToggles.length"), 0);
  assert.equal(f.evaluate("state.sessionTools.pending"), true);
  f.receive(catalogEvent(first, { enabledGroups: [] }));
  assert.equal(f.evaluate("state.sessionTools.pending"), true);
  const second = f.sent.at(-1);
  f.receive(catalogEvent(second, { enabledGroups: ["memory"] }));
  assert.deepEqual(enabled(f), ["memory"]);
  assert.equal(f.evaluate("state.sessionTools.revision"), 2);
});
