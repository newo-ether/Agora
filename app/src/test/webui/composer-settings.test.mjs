import assert from "node:assert/strict";
import fs from "node:fs";
import test from "node:test";
import vm from "node:vm";
import { h } from "../../main/assets/webui/vendor/preact.mjs";
import htm from "../../main/assets/webui/vendor/htm.mjs";

const source = fs.readFileSync(new URL("../../main/assets/webui/composer-settings.js", import.meta.url), "utf8");
const controls = {
  thinkingCanDisable: true, thinkingSupportsBudget: true, thinkingEfforts: ["low", "high"],
  thinkingBudgetPresets: [1024, 2048, 4096, 8192, 16384, 32768],
  displayedThinkingEnabled: true, displayedThinkingLevel: "high",
  displayedThinkingBudgetEnabled: false, displayedThinkingBudgetTokens: 4096,
  serviceTiers: ["auto", "priority"], displayedServiceTier: "auto", openAiServiceTierEnabled: true,
};
function fixture(patch = {}, statePatch = {}) {
  const slots = [], effects = [], commands = [];
  let cursor = 0;
  const state = { connected: true, pendingAction: 0, composer: { modelId: "synthetic", controls: { ...controls, ...patch } }, ...statePatch };
  const context = vm.createContext({
    html: htm.bind(h), DetailSheet: () => null, sliderStyle: () => ({}), icon: () => null,
    ICON_NEUROLOGY: "thinking", ICON_SPEED: "tier", ICON_CHEVRON_DOWN: "down",
    t: { off: "Off", thinking: "Thinking", thinkingEffort: "Thinking Effort", efforts: { low: "Low", high: "High" },
      tokens: value => `${value} tokens`, useBudget: "Use Thinking Budget", budget: "Budget (tokens)",
      advanced: "Advanced", hideAdvanced: "Hide advanced", tiers: { auto: "Auto", priority: "Priority" } },
    sync: { attachmentTarget: () => ({ seq: 1 }), setting: (...args) => { commands.push(args); return true; } },
    useState: initial => { const index = cursor++; if (!(index in slots)) slots[index] = typeof initial === "function" ? initial() : initial;
      return [slots[index], value => { slots[index] = value; }]; },
    useEffect: effect => { effects.push(effect); },
    useLayoutEffect: effect => { effects.push(effect); },
  });
  vm.runInContext(source.replace(/^import .*;\r?\n/gm, "").replace(/export /g, ""), context);
  const target = { kind: "thinking", seq: 1, connectionId: "fixture" };
  return { state, commands, target, render() {
    cursor = 0;
    const tree = context.ComposerSettings({ state, target, onClose() {} });
    const nodes = [];
    function visit(node) { if (Array.isArray(node)) return node.forEach(visit);
      if (node && typeof node === "object") { nodes.push(node); visit(node.props?.children); } }
    visit(tree);
    return nodes;
  } };
}
const byLabel = (nodes, label) => nodes.find(node => node.props?.["aria-label"] === label);
const event = value => ({ currentTarget: { value } });

test("unsupported budgets retain the Advanced entry and a disabled switch", () => {
  const f = fixture({ thinkingSupportsBudget: false });
  let nodes = f.render();
  const advanced = nodes.find(node => node.props?.class === "tool-advanced");
  assert.ok(advanced);
  advanced.props.onClick();
  nodes = f.render();
  assert.equal(nodes.find(node => node.props?.class === "tool-advanced").props["aria-expanded"], true);
  assert.equal(byLabel(nodes, "Use Thinking Budget").props.disabled, true);
  assert.equal(f.commands.length, 0);
});

test("non-preset budgets label the nearest stop while preserving the effective summary", () => {
  const f = fixture({ displayedThinkingBudgetEnabled: true, displayedThinkingBudgetTokens: 6000 });
  const nodes = f.render();
  assert.ok(nodes.some(node => node.props?.children === "6000 tokens"));
  assert.ok(nodes.some(node => node.props?.children === "4096 tokens"));
  assert.equal(byLabel(nodes, "Budget (tokens)").props.value, 2);
  assert.equal(f.commands.length, 0);
});

test("budget sliders submit only changed values at gesture completion with the exact target", () => {
  const f = fixture({ displayedThinkingBudgetEnabled: true });
  let budget = byLabel(f.render(), "Budget (tokens)");
  budget.props.onInput(event(4));
  assert.equal(f.commands.length, 0);
  budget = byLabel(f.render(), "Budget (tokens)");
  budget.props.onChange(event(4));
  assert.equal(f.commands.length, 1);
  assert.equal(f.commands[0][0], "thinkingBudgetTokens");
  assert.equal(f.commands[0][1], 16384);
  assert.equal(f.commands[0][2], f.target);
  const unchanged = fixture({ displayedThinkingBudgetEnabled: true });
  byLabel(unchanged.render(), "Budget (tokens)").props.onChange(event(2));
  assert.equal(unchanged.commands.length, 0);
});

test("pending commands fence all writes without capability dimming or hiding Advanced", () => {
  const f = fixture({ displayedThinkingBudgetEnabled: true }, { pendingAction: 7 });
  const nodes = f.render();
  for (const label of ["Thinking", "Use Thinking Budget", "Budget (tokens)"]) {
    assert.equal(byLabel(nodes, label).props.disabled, true);
    assert.equal(byLabel(nodes, label).props["data-pending"], true);
  }
  assert.equal(nodes.find(node => node.props?.class === "tool-budget-content").props["data-disabled"], false);
  assert.ok(nodes.find(node => node.props?.class === "tool-advanced"));
});

test("mandatory thinking cannot be switched off and empty efforts keep an empty disabled label", () => {
  const f = fixture({ thinkingCanDisable: false, thinkingEfforts: [] });
  const nodes = f.render();
  assert.equal(byLabel(nodes, "Thinking").props.disabled, true);
  assert.equal(byLabel(nodes, "Thinking Effort").props.disabled, true);
  const title = nodes.find(node => node.props?.class === "tool-setting-title");
  assert.equal(title.props.children[1].props.children, "");
});
