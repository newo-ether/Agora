import assert from "node:assert/strict";
import test from "node:test";
import { installFileDrop, routeFileDrop } from "../../main/assets/webui/file-drop.js";

const target = { connectionId: "session", seq: 7, conversationId: "owner" };

function recorder(sandboxAttachments, liveTarget = target) {
  const calls = [];
  return {
    calls,
    route: (types, files = [{ name: "photo.png" }]) => routeFileDrop({
      types, files, sandboxAttachments, target: liveTarget,
      retain: (dropped, owner) => calls.push(["retain", dropped, owner]),
      upload: (dropped, forcedType, owner) => calls.push(["upload", dropped, forcedType, owner]),
    }),
  };
}

test("a file drop uses the sandbox ingress in sandbox mode", () => {
  const r = recorder(true);
  assert.equal(r.route(["Files"]), true);
  assert.deepEqual(r.calls, [["retain", [{ name: "photo.png" }], target]]);
});

test("a file drop uses the normal upload path outside sandbox mode", () => {
  const r = recorder(false);
  assert.equal(r.route(["Files"]), true);
  assert.deepEqual(r.calls, [["upload", [{ name: "photo.png" }], null, target]]);
});

test("a text-only drag is left to the browser", () => {
  const r = recorder(false);
  assert.equal(r.route(["text/plain"], []), false);
  assert.equal(r.calls.length, 0);
});

test("a file drop without a live conversation is cancelled but not queued", () => {
  const r = recorder(false, null);
  assert.equal(r.route(["Files"]), true);
  assert.equal(r.calls.length, 0);
});

test("installing the drop listener cancels navigation and removes both listeners", () => {
  const listeners = new Map();
  const document = {
    addEventListener: (name, handler) => listeners.set(name, handler),
    removeEventListener: (name) => listeners.delete(name),
  };
  let sandboxAttachments = false;
  const calls = [];
  const cleanup = installFileDrop({
    document,
    getSandboxAttachments: () => sandboxAttachments,
    attachmentTarget: () => target,
    retain: () => calls.push("retain"),
    upload: () => calls.push("upload"),
  });
  assert.deepEqual([...listeners.keys()].sort(), ["dragover", "drop"]);

  let prevented = 0;
  const over = { dataTransfer: { types: ["Files"] }, preventDefault: () => prevented++ };
  listeners.get("dragover")(over);
  const drop = { dataTransfer: { types: ["Files"], files: [{ name: "a.txt" }] }, preventDefault: () => prevented++ };
  listeners.get("drop")(drop);
  assert.equal(prevented, 2);
  assert.deepEqual(calls, ["upload"]);

  sandboxAttachments = true;
  listeners.get("drop")({ dataTransfer: { types: ["Files"], files: [{ name: "b.txt" }] }, preventDefault() {} });
  assert.deepEqual(calls, ["upload", "retain"]);

  cleanup();
  assert.equal(listeners.size, 0);
});
