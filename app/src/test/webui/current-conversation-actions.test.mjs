import assert from "node:assert/strict";
import fs from "node:fs";
import test from "node:test";
import vm from "node:vm";
import { h } from "../../main/assets/webui/vendor/preact.mjs";
import htm from "../../main/assets/webui/vendor/htm.mjs";

const root = new URL("../../main/assets/webui/", import.meta.url);
const source = name => fs.readFileSync(new URL(name, root), "utf8");
const prepare = code => code.replace(/^import\b[\s\S]*?;\r?\n/gm, "").replace(/\bexport /g, "");
const rawTitle = "  Child stored title\t";
const chatState = (patch = {}) => ({
  connected: true, connectionId: "session", openId: "child", openStatus: "ready",
  parentConversationId: "parent", generating: true, pendingAction: 0, switching: null,
  pageAction: null, conversationDelete: null, conversationDeleteResult: null,
  composer: { seq: 3, phase: "IDLE", controls: {} },
  subagents: [{ id: "child", title: rawTitle, status: "running" }],
  conversations: [{ id: "parent", title: "Parent", generating: true }],
  display: { reduceMotion: true }, searchQuery: "", searchResults: [],
  searchLoading: false, searchFailed: false, listLoading: false, listHasMore: false,
  conversationSearch: null, snackbar: null, ...patch,
});

function nodes(tree) {
  if (Array.isArray(tree)) return tree.flatMap(nodes);
  if (!tree || typeof tree !== "object" || !tree.props) return [];
  return [tree, ...nodes(tree.props.children)];
}
const text = tree => Array.isArray(tree) ? tree.map(text).join("")
  : tree?.props ? text(tree.props.children) : typeof tree === "string" ? tree : "";
const menuItems = tree => nodes(tree).filter(node => node.props.role === "menuitem");

// Execute production components and the bundled HTM parser. Browser geometry effects stay outside
// these render/command tests; only the small dialog effects are mounted when requested.
function harness(name, { state = chatState(), effects = false } = {}) {
  const hooks = [], pending = [], commands = [], focuses = [];
  let cursor = 0, props, tree, dirty, currentState = state, actionId = 20;
  function effect(callback, deps) {
    const at = cursor++, previous = hooks[at];
    if (!previous || !deps || deps.some((value, i) => value !== previous.deps?.[i])) {
      const hook = { deps, cleanup: previous?.cleanup };
      hooks[at] = hook;
      if (effects) pending.push(() => { hook.cleanup?.(); hook.cleanup = callback(); });
    }
  }
  const context = vm.createContext({
    html: htm.bind(h), navigator: { language: "en-US" }, document: { documentElement: {} },
    window: { matchMedia: () => ({ matches: false }) },
    localStorage: { getItem: () => null },
    useSync: () => currentState,
    useRef(initial) {
      const at = cursor++;
      return hooks[at] || (hooks[at] = { current: initial });
    },
    useState(initial) {
      const at = cursor++, hook = hooks[at] || (hooks[at] = {
        value: typeof initial === "function" ? initial() : initial,
      });
      return [hook.value, next => {
        const value = typeof next === "function" ? next(hook.value) : next;
        if (value !== hook.value) { hook.value = value; dirty = true; }
      }];
    },
    useMemo: callback => callback(), useEffect: effect, useLayoutEffect: effect,
    useDialog: (_dialog, close) => close,
    CircularProgress() {}, MessageList() {}, Composer() {}, attachScrollbar() {},
    postJson: async () => { commands.push({ type: "logout" }); },
    sync: {
      interactionTarget: () => currentState.connected && currentState.connectionId ? {
        connectionId: currentState.connectionId, seq: currentState.composer.seq,
        conversationId: currentState.openId,
      } : null,
      renameConversation: (conversationId, title) => commands.push({ type: "rename", conversationId, title }),
      deleteConversation: conversationId => {
        const command = { type: "delete", conversationId, actionId: ++actionId };
        commands.push(command);
        return command;
      },
      confirmationExited: id => commands.push({ type: "confirmationExited", actionId: id }),
      open: conversationId => commands.push({ type: "open", conversationId }),
      pin: (conversationId, enabled) => commands.push({ type: "pin", conversationId, enabled }),
      generateTitle: conversationId => commands.push({ type: "title", conversationId }),
      conversationSearch: query => commands.push({ type: "search", query }),
    },
  });
  for (const file of ["icons.js", "i18n.js", "menu.js", "shell.js"]) {
    vm.runInContext(prepare(source(file)), context, { filename: file });
  }
  const render = (nextProps = props) => {
    props = nextProps;
    for (let pass = 0; pass < 10; pass++) {
      cursor = 0; dirty = false;
      tree = context[name](props);
      for (const node of nodes(tree)) {
        if (typeof node.type === "string" && node.ref && !node.ref.current) {
          const dom = { value: "", focus() { focuses.push(dom); } };
          node.ref.current = dom;
        }
      }
      pending.splice(0).forEach(callback => callback());
      if (!dirty) return tree;
    }
    throw new Error(`${name} did not settle`);
  };
  return {
    render, commands, focuses,
    get tree() { return tree; },
    get state() { return currentState; },
    stateTo(value) { currentState = value; },
    find(predicate) {
      const found = nodes(tree).find(predicate);
      assert.ok(found, `expected ${name} control`);
      return found;
    },
    component(componentName) { return this.find(node => node.type?.name === componentName); },
    strings: vm.runInContext("t", context),
    value: expression => vm.runInContext(expression, context),
  };
}

function shell(state = chatState()) {
  const f = harness("Shell", { state });
  f.render({ onSignedOut() {} });
  return f;
}
function topBar(s) {
  const f = harness("TopBar", { state: s.state });
  f.render(s.component("TopBar").props);
  return f;
}
function openMenu(f) {
  f.find(node => node.type === "button" && node.props["aria-haspopup"] === "menu").props.onClick();
  f.render();
  return f.component("MoreMenu").props;
}
function updateTopBar(f, s, state) {
  s.stateTo(state); s.render();
  f.stateTo(state); f.render(s.component("TopBar").props);
}

// The drawer intentionally contains only the parent. Top-right actions must not use its ID/title.
test("current child actions freeze the exact open ID and raw stored title, including blank titles", () => {
  for (const title of [rawTitle, "", "   "]) {
    for (const action of ["onRename", "onDelete"]) {
      const s = shell(chatState({ subagents: [{ id: "child", title, status: "running" }] }));
      const top = s.component("TopBar").props;
      assert.equal(top.conversationId, "child");
      assert.equal(top.storedTitle, title);
      assert.equal(top.title, title.trim() || null);
      assert.equal(s.component("DrawerContent").props.openId, "parent");
      const f = topBar(s), menu = openMenu(f);
      updateTopBar(f, s, { ...s.state, subagents: [{ id: "child", title: "Later title" }] });
      menu[action](); s.render();
      if (action === "onRename") {
        const request = s.component("ConversationRenameDialog").props.conversation;
        assert.equal(request.conversationId, "child");
        assert.equal(request.title, title);
        assert.ok(request.anchor);
      } else {
        s.component("ConversationDeleteDialog").props.onConfirm(); s.render();
        assert.equal(s.commands.at(-1).conversationId, "child");
      }
    }
  }
});

test("root actions keep the stored title instead of the trimmed top-bar label", () => {
  const s = shell(chatState({ openId: "parent", parentConversationId: null,
    conversations: [{ id: "parent", title: "  Root  " }], subagents: [] }));
  const f = topBar(s);
  openMenu(f).onRename(); s.render();
  assert.equal(s.component("ConversationRenameDialog").props.conversation.title, "  Root  ");
  assert.equal(s.component("ConversationRenameDialog").props.conversation.conversationId, "parent");
});

test("running alone permits Rename/Delete while Search/Fork/Share keep their running exclusion", () => {
  const s = shell(), f = topBar(s), menu = openMenu(f);
  assert.equal(menu.conversationActionsEnabled, true);
  assert.equal(menu.pageActionsEnabled, false);
  const popup = harness("MoreMenu");
  const items = menuItems(popup.render(menu));
  assert.deepEqual(items.map(item => text(item)), ["Search", "System Prompt", "Fork Conversation", "Share", "Rename", "Delete", "Sign Out"]);
  for (const index of [0, 2, 3]) assert.equal(items[index].props.disabled, true);
  for (const index of [4, 5]) assert.equal(items[index].props.disabled, false);
  assert.match(items[5].props.class, /destructive/);
  assert.equal(nodes(items[4]).find(node => node.type === "path").props.d, popup.value("ICON_EDIT"));
  assert.equal(nodes(items[5]).find(node => node.type === "path").props.d, popup.value("ICON_DELETE"));
});

test("current actions are disabled for disconnected, loading, New Chat and actual pending work", () => {
  const cases = [
    { connected: false }, { openStatus: "loading" }, { openStatus: "failed" },
    { openStatus: "deleted" }, { openId: null, openStatus: null },
    { switching: { id: 1 } }, { pendingAction: 7 }, { pageAction: { type: "share" } },
    { conversationDelete: { actionId: 7 } },
    { composer: { seq: 3, phase: "SUBMITTING" } },
    { composer: { seq: 3, phase: "ACCEPTED_PENDING_CLEAR" } },
  ];
  for (const patch of cases) {
    const s = shell(chatState(patch)), f = topBar(s), menu = openMenu(f);
    assert.equal(s.component("TopBar").props.conversationActionsEnabled, false, JSON.stringify(patch));
    assert.equal(menu.conversationActionsEnabled, false, JSON.stringify(patch));
    const popup = harness("MoreMenu"), items = menuItems(popup.render(menu));
    assert.equal(items[4].props.disabled, true);
    assert.equal(items[5].props.disabled, true);
    menu.onRename(); menu.onDelete(); s.render();
    assert.equal(nodes(s.tree).some(node => /Conversation(?:Rename|Delete)Dialog/.test(node.type?.name)), false);
  }
  const waiting = shell(chatState({ composer: { seq: 3, phase: "WAITING" } }));
  assert.equal(openMenu(topBar(waiting)).conversationActionsEnabled, true);
});

test("navigation and reconnection reject old callbacks without retargeting the outgoing menu", () => {
  for (const patch of [
    { openId: "parent", parentConversationId: null }, { openId: null, openStatus: null },
    { connectionId: "replacement" }, { composer: { seq: 4, phase: "IDLE" } },
    { connected: false }, { switching: { id: 4 } }, { pendingAction: 9 },
  ]) {
    const s = shell(), f = topBar(s), oldMenu = openMenu(f);
    updateTopBar(f, s, { ...s.state, ...patch });
    assert.equal(f.component("MoreMenu").props.conversationActionsEnabled, false);
    oldMenu.onRename(); oldMenu.onDelete(); s.render();
    assert.equal(nodes(s.tree).some(node => /Conversation(?:Rename|Delete)Dialog/.test(node.type?.name)), false);
    assert.equal(s.commands.length, 0);
  }
  const s = shell(), f = topBar(s), oldMenu = openMenu(f);
  f.stateTo({ ...s.state, openId: "parent" });
  oldMenu.onRename(); oldMenu.onDelete(); s.render();
  assert.equal(nodes(s.tree).some(node => /Conversation(?:Rename|Delete)Dialog/.test(node.type?.name)), false);
});

test("retained exit callbacks cannot clear a reopened menu or steal its focus", () => {
  const s = shell(), f = topBar(s), first = openMenu(f);
  first.onClose(); f.render();
  const exiting = f.component("MoreMenu").props;
  assert.equal(exiting.expanded, false);
  assert.equal(exiting.conversationActionsEnabled, false);
  first.onRename(); s.render();
  assert.equal(nodes(s.tree).some(node => node.type?.name === "ConversationRenameDialog"), false);
  const reopened = openMenu(f);
  exiting.onExited(true); f.render();
  assert.equal(f.component("MoreMenu").props.expanded, true);
  assert.equal(f.focuses.length, 0);
  first.onDelete(); s.render();
  assert.equal(nodes(s.tree).some(node => node.type?.name === "ConversationDeleteDialog"), false);
  reopened.onClose(); f.render();
  f.component("MoreMenu").props.onExited(true); f.render();
  assert.equal(nodes(f.tree).some(node => node.type?.name === "MoreMenu"), false);
  assert.equal(f.focuses.length, 1);
});

test("an admitted action closes once and stale callbacks cannot replace its captured dialog", () => {
  const s = shell(), f = topBar(s), menu = openMenu(f);
  menu.onRename(); menu.onDelete(); s.render();
  assert.equal(s.component("ConversationRenameDialog").props.conversation.conversationId, "child");
  assert.equal(nodes(s.tree).some(node => node.type?.name === "ConversationDeleteDialog"), false);
  s.stateTo({ ...s.state, openId: "parent", parentConversationId: null }); s.render();
  assert.equal(s.component("ConversationRenameDialog").props.conversation.conversationId, "child");
});

test("top-right Delete reuses exact-actionId pending, failure and retry with the frozen target", () => {
  const s = shell(), f = topBar(s);
  openMenu(f).onDelete(); s.render();
  let dialog = s.component("ConversationDeleteDialog").props;
  assert.equal(dialog.pending, false);
  dialog.onConfirm(); s.render();
  const first = s.commands.at(-1);
  assert.equal(first.conversationId, "child");
  s.stateTo({ ...s.state, conversationDelete: first }); s.render();
  assert.equal(s.component("ConversationDeleteDialog").props.pending, true);
  s.stateTo({ ...s.state, openId: "parent", conversationDelete: null,
    conversationDeleteResult: { actionId: first.actionId + 100, success: true } }); s.render();
  dialog = s.component("ConversationDeleteDialog").props;
  assert.equal(dialog.complete, false);
  assert.equal(dialog.failed, false);
  s.stateTo({ ...s.state, conversationDeleteResult: { actionId: first.actionId, success: false } }); s.render();
  dialog = s.component("ConversationDeleteDialog").props;
  assert.equal(dialog.failed, true);
  dialog.onConfirm(); s.render();
  const retry = s.commands.at(-1);
  assert.equal(retry.conversationId, "child");
  assert.notEqual(retry.actionId, first.actionId);
  assert.equal(s.component("ConversationDeleteDialog").props.failed, false);
  s.stateTo({ ...s.state, conversationDeleteResult: { actionId: first.actionId, success: true } }); s.render();
  assert.equal(s.component("ConversationDeleteDialog").props.complete, false);
  s.stateTo({ ...s.state, conversationDeleteResult: { actionId: retry.actionId, success: true } }); s.render();
  assert.equal(s.component("ConversationDeleteDialog").props.complete, true);
  assert.equal(s.commands.some(command => command.type === "open"), false);
});

test("Rename seeds raw text, untouched Save writes nothing, edited Save keeps the captured owner", () => {
  for (const edited of [false, true]) {
    let closed = 0;
    const f = harness("ConversationRenameDialog", { effects: true });
    f.render({ conversation: { conversationId: "child", title: rawTitle }, onClose: () => closed++ });
    const input = f.find(node => node.type === "input");
    assert.equal(input.ref.current.value, rawTitle);
    f.stateTo(chatState({ openId: "parent" }));
    if (edited) { input.ref.current.value = "Edited"; input.props.onInput(); }
    f.find(node => node.type === "button" && text(node) === "Save").props.onClick();
    assert.equal(closed, 1);
    assert.deepEqual(f.commands, edited ? [{ type: "rename", conversationId: "child", title: "Edited" }] : []);
  }
});

test("pending Delete blocks dismissal and only exact completion exits its confirmation", () => {
  let closed = 0;
  const f = harness("ConversationDeleteDialog", { effects: true });
  const props = { pending: true, failed: false, complete: false, actionId: 42,
    onConfirm() {}, onClose: () => closed++ };
  f.render(props);
  const dialog = f.find(node => node.type === "dialog");
  assert.ok(nodes(f.tree).filter(node => node.type === "button").every(node => node.props.disabled));
  dialog.props.onCancel({ preventDefault() {} });
  dialog.props.onClick({ target: dialog.ref.current });
  assert.equal(closed, 0);
  f.render({ ...props, pending: false, failed: true });
  assert.equal(text(f.find(node => node.type === "button" && node.props.class === "destructive")), "Retry");
  assert.equal(closed, 0);
  f.render({ ...props, pending: false, complete: true });
  assert.equal(closed, 1);
  assert.deepEqual(f.commands, [{ type: "confirmationExited", actionId: 42 }]);
});

test("drawer row actions still use their own ID and custom MoreMenu children replace defaults", () => {
  const s = shell(), requests = [], f = harness("DrawerContent");
  f.render({ ...s.component("DrawerContent").props,
    onRequestRename: request => requests.push(request), onRequestDelete: request => requests.push(request) });
  const anchor = {}, row = f.component("ConversationRow");
  row.props.onMenu(row.props.conversation, anchor); f.render();
  const menu = f.component("MoreMenu").props;
  const popup = harness("MoreMenu"), items = menuItems(popup.render(menu));
  assert.deepEqual(items.map(item => text(item)), ["Pin", "Generate Title", "Rename", "Delete"]);
  assert.ok(items.every(item => item.props.disabled === false));
  items[2].props.onClick(); items[3].props.onClick();
  assert.equal(requests.length, 2);
  assert.ok(requests.every(request => request.conversationId === "parent" && request.title === "Parent" && request.anchor === anchor));
  const directRow = harness("ConversationRow"), selected = [];
  directRow.render({ conversation: row.props.conversation, selected: false,
    onSelect: id => selected.push(id), onMenu: (_conversation, target) => requests.push({ anchor: target }) });
  directRow.find(node => node.type === "button" && node.props.class === "conversation-row").props.onClick();
  assert.deepEqual(selected, ["parent"]);
  directRow.find(node => node.type === "button" && node.props.class === "conversation-more").props.onClick({ currentTarget: anchor });
  assert.equal(requests.at(-1).anchor, anchor);
  const custom = h("button", { role: "menuitem" }, "Custom action");
  assert.deepEqual(menuItems(popup.render({ ...menu, children: custom })).map(item => text(item)), ["Custom action"]);
});

test("drawer row keeps generation ahead of unread and clears progress after activity ends", () => {
  const f = harness("ConversationRow");
  const row = { id: "parent", title: "Parent", generating: true, unread: true };
  const props = { conversation: row, selected: false, onSelect() {}, onMenu() {} };
  for (const selected of [false, true]) {
    f.render({ ...props, selected });
    const progress = f.component("CircularProgress");
    assert.equal(progress.props.size, 18);
    assert.equal(progress.props.stroke, 2);
    assert.equal(nodes(f.tree).filter(node => node.props.class === "unread-dot").length, 0);
  }
  f.render({ ...props, conversation: { ...row, generating: false } });
  assert.equal(nodes(f.tree).filter(node => node.type?.name === "CircularProgress").length, 0);
  assert.equal(nodes(f.tree).filter(node => node.props.class === "unread-dot").length, 1);
  f.render({ ...props, selected: true, conversation: { ...row, generating: false } });
  assert.equal(nodes(f.tree).filter(node => node.props.class === "conversation-indicator").length, 0);
});
test("Sign Out remains the final default action and uses the existing sign-out owner", async () => {
  const s = shell(), f = topBar(s), menu = openMenu(f), popup = harness("MoreMenu");
  let signedOut = 0;
  f.render({ ...s.component("TopBar").props, onSignedOut: () => signedOut++ });
  const last = menuItems(popup.render(f.component("MoreMenu").props)).at(-1);
  assert.equal(text(last), "Sign Out");
  assert.equal(last.props.disabled, undefined);
  await last.props.onClick();
  assert.equal(signedOut, 1);
  assert.deepEqual(f.commands, [{ type: "logout" }]);
  assert.equal(menu.conversationActionsEnabled, true);
});
