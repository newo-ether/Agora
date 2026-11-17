import assert from "node:assert/strict";
import fs from "node:fs";
import test from "node:test";
import vm from "node:vm";
import { h } from "../../main/assets/webui/vendor/preact.mjs";
import htm from "../../main/assets/webui/vendor/htm.mjs";

const root = new URL("../../main/assets/webui/", import.meta.url);
const source = name => fs.readFileSync(new URL(name, root), "utf8");
const nodes = tree => Array.isArray(tree) ? tree.flatMap(nodes)
  : tree?.props ? [tree, ...nodes(tree.props.children)] : [];

function tile(attachment) {
  const hooks = [];
  let cursor = 0, previews = 0;
  const context = vm.createContext({
    html: htm.bind(h), icon: () => null, Spinner() {},
    ICON_VIDEO: "video", ICON_ERROR: "error", ICON_BROKEN_IMAGE: "broken", ICON_CLOSE: "close",
    t: { attachments: "Attachments", retry: "Retry", remove: "Remove" },
    sync: { attachmentUrl: (id, kind) => `/owned/${id}/${kind}`, attachmentCommand() {} },
    useRef(value) { const i = cursor++; return hooks[i] ??= { current: value }; },
    useState(value) { const i = cursor++; if (!(i in hooks)) hooks[i] = typeof value === "function" ? value() : value;
      return [hooks[i], next => { hooks[i] = typeof next === "function" ? next(hooks[i]) : next; }]; },
    useLayoutEffect() {},
  });
  vm.runInContext(source("attachments.js").replace(/^import\b[\s\S]*?;\r?\n/gm, "").replace(/\bexport /g, ""), context);
  let tree;
  function render(patch = {}) {
    attachment = { ...attachment, ...patch };
    cursor = 0;
    tree = context.AttachmentTile({ attachment, editable: true, onPreview() { previews++; } });
    return tree;
  }
  return { render, get previews() { return previews; },
    button: () => nodes(tree).find(node => node.props.class === "attachment-preview"),
    decode(success = true) {
      const image = nodes(tree).find(node => node.props.class === "attachment-decode");
      assert.ok(image);
      const element = { isConnected: true };
      image.ref.current = element;
      image.props[success ? "onLoad" : "onError"]({ currentTarget: element });
      return render();
    },
  };
}

for (const storage of ["APP_PRIVATE", "LOCAL_SANDBOX_PENDING", "LOCAL_SANDBOX_RUNTIME"]) {
  test(`${storage} ready image loads its owned source and enables preview only after decode`, () => {
    const f = tile({ id: "image", type: "image", state: "READY", storage });
    const image = nodes(f.render()).find(node => node.props.class === "attachment-decode");
    assert.equal(image.props.src, "/owned/image/source");
    assert.equal(f.button().props.disabled, true);
    f.decode();
    assert.equal(f.button().props.disabled, false);
    f.button().props.onClick();
    assert.equal(f.previews, 1);
    f.render({ unavailable: true });
    assert.equal(f.button().props.disabled, true);
  });
}

test("sandbox text and resolved PDF use ordinary preview readiness gates", () => {
  const f = tile({ id: "text", type: "file", state: "READY", storage: "LOCAL_SANDBOX_PENDING", text: "preview" });
  f.render();
  assert.equal(f.button().props.disabled, false);
  f.button().props.onClick();
  assert.equal(f.previews, 1);
  f.render({ text: null });
  assert.equal(f.button().props.disabled, true);
  f.render({ type: "pdf", pagePreviewCount: 2 });
  assert.equal(f.button().props.disabled, false);
  f.render({ pagePreviewCount: 0 });
  assert.equal(f.button().props.disabled, true);
  f.render({ pagePreviewCount: 2, state: "PROCESSING" });
  assert.equal(f.button().props.disabled, true);
});

test("sandbox video thumbnail resolves its frame and opens after successful decode", () => {
  const f = tile({ id: "video", type: "video", state: "READY", storage: "LOCAL_SANDBOX_PENDING", framePreviewCount: 2 });
  assert.equal(nodes(f.render()).find(node => node.props.class === "attachment-decode").props.src, "/owned/video/frame");
  f.decode();
  assert.equal(f.button().props.disabled, false);
});

function composerPreview(attachments, viewer, statePatch = {}) {
  const code = source("composer.js");
  const start = code.indexOf("  const viewedId =");
  const end = code.indexOf("  const actionable =", start);
  assert.ok(start >= 0 && end > start);
  const context = vm.createContext({ attachments, viewer, phase: "IDLE", useEffect() {},
    state: { connected: true, connectionId: "connection", composer: { seq: 9 }, ...statePatch },
    sync: { attachmentTarget: () => ({ connectionId: "connection", seq: 9 }) },
    setViewer(value) { context.result = value; },
  });
  vm.runInContext(code.slice(start, end), context);
  return { current: vm.runInContext("!!currentViewer", context),
    preview(index) { context.chosen = attachments[index]; vm.runInContext("preview(chosen)", context); return context.result; } };
}

test("composer viewer admits sandbox media and text with exact connection and sequence", () => {
  const attachments = [
    { id: "image", type: "image", state: "READY", storage: "LOCAL_SANDBOX_PENDING" },
    { id: "video", type: "video", state: "READY", storage: "APP_PRIVATE" },
    { id: "blocked", type: "image", state: "READY", storage: "LOCAL_SANDBOX_PENDING", unavailable: true },
    { id: "text", type: "file", state: "READY", storage: "LOCAL_SANDBOX_PENDING", text: "private preview" },
    { id: "pdf", type: "pdf", state: "READY", storage: "LOCAL_SANDBOX_PENDING", pagePreviewCount: 2 },
  ];
  const viewer = { connectionId: "connection", seq: 9, items: [attachments[0]], index: 0 };
  const f = composerPreview(attachments, viewer);
  assert.equal(f.current, true);
  assert.deepEqual(Array.from(f.preview(0).items, item => item.id), ["image", "video"]);
  assert.equal(f.preview(3).file.text, "private preview");
  assert.deepEqual(Array.from(f.preview(4).items, item => item.index), [0, 1]);
  assert.equal(composerPreview(attachments, { ...viewer, seq: 8 }).current, false);
  assert.equal(composerPreview(attachments, viewer, { connectionId: "other" }).current, false);
  assert.equal(composerPreview(attachments.slice(1), viewer).current, false);
});

test("sandbox video viewer expands saved frames while ordinary video keeps source playback", () => {
  const attachments = [
    { id: "sandbox", type: "video", state: "READY", storage: "LOCAL_SANDBOX_PENDING", framePreviewCount: 2 },
    { id: "ordinary", type: "video", state: "READY", storage: "APP_PRIVATE" },
  ];
  const viewer = composerPreview(attachments, null).preview(0);
  assert.deepEqual(Array.from(viewer.items, item => [item.id, item.kind, item.index]), [
    ["sandbox", "frame", 0], ["sandbox", "frame", 1], ["ordinary", "source", 0],
  ]);
  assert.equal(composerPreview(attachments, null).preview(1).index, 2);
});

function userBubble(attachments) {
  const hooks = [];
  let cursor = 0;
  const context = vm.createContext({ html: htm.bind(h), icon: () => null, CSS: {},
    t: { attachments: "Attachments" }, readableText: () => "",
    SourceLabel() {}, RowAction() {}, MoreMenu() {}, RowMenuItem() {}, DetailSheet() {},
    MessageInfoDialog() {}, MessageEditDialog() {}, AttachmentViewer() {},
    ICON_EDIT: "edit", ICON_MORE_VERT: "more", ICON_IMAGE: "image", ICON_CONTENT_COPY: "copy",
    ICON_SELECT_ALL: "select", ICON_INFO: "info", ICON_DELETE: "delete",
    useRef(value) { const i = cursor++; return hooks[i] ??= { current: value }; },
    useState(value) { const i = cursor++; if (!(i in hooks)) hooks[i] = typeof value === "function" ? value() : value;
      return [hooks[i], next => { hooks[i] = typeof next === "function" ? next(hooks[i]) : next; }]; },
    useLayoutEffect() {},
  });
  const code = source("message-user.js");
  vm.runInContext(code.slice(code.indexOf("export function UserBubble")).replace("export ", ""), context);
  function render() {
    cursor = 0;
    return context.UserBubble({ message: { id: "message", attachments }, conversationId: "conversation", matches: [] });
  }
  return { render, viewer: () => nodes(render()).find(node => node.type === context.AttachmentViewer)?.props.viewer };
}

test("sent video frames use image viewer and text files open their saved snapshot", () => {
  const f = userBubble([
    { index: 0, type: "video", fileName: "clip.mp4" },
    { index: 1, type: "file", fileName: "notes.txt", text: "saved preview" },
    { index: 2, type: "file", fileName: "archive.bin" },
  ]);
  let buttons = nodes(f.render()).filter(node => node.props.class?.startsWith("user-attachment ") || node.props.class === "user-attachment");
  buttons[0].props.onClick();
  assert.equal(f.viewer().items[0].kind, "frame");
  assert.equal(f.viewer().items[0].url, "/api/message-attachments/conversation/message/0");
  buttons = nodes(f.render()).filter(node => node.props.class?.startsWith("user-attachment ") || node.props.class === "user-attachment");
  assert.equal(buttons[1].props.disabled, false);
  assert.equal(buttons[2].props.disabled, true);
  buttons[1].props.onClick();
  assert.equal(f.viewer().file.name, "notes.txt");
  assert.equal(f.viewer().file.text, "saved preview");
});
