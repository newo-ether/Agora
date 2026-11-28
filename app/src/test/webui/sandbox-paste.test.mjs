import assert from "node:assert/strict";
import fs from "node:fs";
import test from "node:test";
import vm from "node:vm";
import { File } from "node:buffer";

const assets = new URL("../../main/assets/webui/", import.meta.url);
function pasteSplit(text, sandbox, files = []) {
  const source = fs.readFileSync(new URL("composer.js", assets), "utf8");
  const segment = source.slice(source.indexOf("export const SANDBOX_PASTE_MIN_CHARS"), source.indexOf("const CONTEXT_PART_LABEL"));
  const context = vm.createContext({ data: { files, getData: () => text }, sandbox });
  vm.runInContext(segment.replace(/export /g, ""), context);
  return vm.runInContext("splitPaste(data, sandbox)", context);
}

test("sandbox paste threshold is inclusive and disabled mode preserves native text", () => {
  assert.equal(pasteSplit("x".repeat(7999), true).pastedText, null);
  assert.equal(pasteSplit("x".repeat(8000), true).pastedText.length, 8000);
  assert.equal(pasteSplit("x".repeat(8000), true).toPlatform, false);
  assert.equal(pasteSplit("x".repeat(9000), false).toPlatform, true);
  const mixed = pasteSplit("short", true, [{ type: "image/png" }]);
  assert.equal(mixed.images.length, 1);
  assert.equal(mixed.toPlatform, true);
});

function fixture(fetch) {
  const downloads = [], revoked = [];
  const context = vm.createContext({
    File, fetch, AbortController, URLSearchParams,
    URL: { createObjectURL(file) { downloads.push(file); return "blob:recovery"; },
      revokeObjectURL(url) { revoked.push(url); } },
    document: { createElement() { return { click() {} }; } },
    t: { fileLoadFailed: "Failed", fileTooLarge: "Too large" },
    performance: { now: () => 0 },
  });
  const source = fs.readFileSync(new URL("sync.js", assets), "utf8");
  vm.runInContext(source.replace(/^import .*;\r?\n/gm, "").replace(/export /g, ""), context);
  const run = code => vm.runInContext(code, context);
  run('openSeq = 1; update({ connected: true, connectionId: "session", openId: "owner", composer: { seq: 1, phase: "IDLE", sandboxAttachments: true } })');
  return { run, context, downloads, revoked, current: () => run("state"),
    settle: () => new Promise(resolve => setImmediate(resolve)) };
}

/** Executes the Composer's real picker/paste callbacks against the production sync module. */
function ingress(f, { sandbox = true } = {}) {
  const source = fs.readFileSync(new URL("composer.js", assets), "utf8");
  const splitStart = source.indexOf("export const SANDBOX_PASTE_MIN_CHARS");
  const splitEnd = source.indexOf("const CONTEXT_PART_LABEL", splitStart);
  vm.runInContext(source.slice(splitStart, splitEnd).replace(/export /g, ""), f.context);
  f.run(`const editable = true; const stateForIngress = ${sandbox};`);
  f.run(`update({ composer: { ...state.composer, sandboxAttachments: stateForIngress } });
    const pickerTarget = { current: { target: sync.attachmentTarget(), forced: stateForIngress ? "sandbox" : "image" } };`);
  function callback(startMarker, endMarker) {
    const start = source.indexOf(startMarker);
    const end = source.indexOf(endMarker, start);
    assert.ok(start >= 0 && end > start);
    return vm.runInContext(`(event => {${source.slice(start + startMarker.length, end)}})`, f.context);
  }
  return {
    pick: callback('ref=${picker} onChange=${event => {', '\n        }} />'),
    paste: callback('onPaste=${(event) => {', '\n            }}'),
  };
}

test("failed paste retains all UTF-8 bytes outside inline text and retries only its owner", async () => {
  const requests = [];
  let ok = false;
  const f = fixture(async (url, request) => { requests.push({ url, request }); return { ok, status: 500 }; });
  f.run('sync.pasteText("原文😀".repeat(3000))');
  await f.settle();
  assert.equal(f.current().text, "");
  const paste = f.current().pendingPastes[0];
  assert.equal(await paste.file.text(), "原文😀".repeat(3000));
  assert.equal(paste.uploading, false);
  f.run('update({ openId: "other" })');
  assert.equal(await f.run(`sync.retryPaste(${paste.id})`), false);
  assert.equal(requests.length, 1);
  f.run('update({ openId: "owner" })');
  ok = true;
  assert.equal(await f.run(`sync.retryPaste(${paste.id})`), true);
  assert.equal(f.current().pendingPastes.length, 0);
  assert.match(requests[1].url, /seq=1/);
  assert.match(requests[1].url, /type=sandbox/);
  assert.equal(requests[1].request.headers["Content-Type"], "application/octet-stream");
});

test("disconnect retains recovery data and New Chat cannot retry into a different workspace", async () => {
  const f = fixture(async () => { throw Object.assign(new Error("Disconnected"), { name: "AbortError" }); });
  f.run('update({ openId: null }); sync.pasteText("x".repeat(8000))');
  await f.settle();
  const id = f.current().pendingPastes[0].id;
  f.run('update({ connected: false, connectionId: null })');
  assert.equal(await f.run(`sync.retryPaste(${id})`), false);
  assert.equal(f.current().pendingPastes.length, 1);
  f.run('openSeq = 2; update({ connected: true, connectionId: "new", composer: { seq: 2, phase: "IDLE", sandboxAttachments: true } })');
  assert.equal(await f.run(`sync.retryPaste(${id})`), false);
  assert.equal(f.current().text, "");
});

for (const origin of ["picker", "clipboard"]) {
  test(`sandbox ${origin} retains original files after failed upload and exposes retry/download/remove`, async () => {
    const requests = [];
    let ok = false;
    const f = fixture(async (url, request) => {
      requests.push({ url, request });
      if (!ok) throw new Error("Offline");
      return { ok: true, status: 202 };
    });
    const handlers = ingress(f);
    const file = new File([new Uint8Array([0, 255, 17, 128])], "original.png", { type: "image/png" });
    if (origin === "picker") {
      const event = { currentTarget: { files: [file], value: "selection" } };
      handlers.pick(event);
      assert.equal(event.currentTarget.value, "");
    } else {
      let prevented = false;
      handlers.paste({ clipboardData: { files: [file], getData: () => "" },
        preventDefault() { prevented = true; } });
      assert.equal(prevented, true);
    }
    assert.equal(f.current().pendingPastes[0].uploading, true);
    await f.settle();
    const pending = f.current().pendingPastes[0];
    assert.equal(pending.file, file);
    assert.equal(pending.conversationId, "owner");
    assert.equal(pending.uploading, false);
    assert.equal(f.current().text, "");
    assert.equal(requests.length, 1);
    assert.match(requests[0].url, /type=sandbox/);
    assert.equal(requests[0].request.body, file);
    f.run(`sync.downloadPaste(${pending.id})`);
    assert.equal(f.downloads[0], file);
    assert.deepEqual(f.revoked, ["blob:recovery"]);
    f.run('openSeq = 2; update({ openId: "other", composer: { ...state.composer, seq: 2 } })');
    assert.equal(await f.run(`sync.retryPaste(${pending.id})`), false);
    assert.equal(requests.length, 1);
    f.run('update({ connected: false, connectionId: null })');
    assert.equal(f.current().pendingPastes[0].file, file);
    f.run('openSeq = 3; update({ connected: true, connectionId: "reconnected", openId: "owner", composer: { ...state.composer, seq: 3 } })');
    ok = true;
    assert.equal(await f.run(`sync.retryPaste(${pending.id})`), true);
    assert.equal(requests[1].request.body, file);
    assert.match(requests[1].url, /reconnected\?seq=3/);
    assert.match(requests[1].url, /type=sandbox/);
    assert.equal(f.current().pendingPastes.length, 0);
    ok = false;
    handlers.paste({ clipboardData: { files: [file], getData: () => "" }, preventDefault() {} });
    await f.settle();
    f.run(`sync.discardPaste(${f.current().pendingPastes[0].id})`);
    assert.equal(f.current().pendingPastes.length, 0);
  });
}

test("picker retains every file for its captured owner after navigation and never retries inline", async () => {
  const requests = [];
  const f = fixture(async (url, request) => { requests.push({ url, request }); return { ok: true, status: 202 }; });
  const handlers = ingress(f);
  const files = [new File(["one"], "one.txt"), new File(["two"], "two.pdf", { type: "application/pdf" })];
  f.run('openSeq = 2; update({ openId: "other", composer: { ...state.composer, seq: 2 } })');
  handlers.pick({ currentTarget: { files, value: "selection" } });
  assert.equal(f.current().pendingPastes.length, 2);
  assert.equal(requests.length, 0);
  assert.ok(f.current().pendingPastes.every(item => item.conversationId === "owner" && !item.uploading));
  const id = f.current().pendingPastes[0].id;
  assert.equal(await f.run(`sync.retryPaste(${id})`), false);
  f.run('openSeq = 3; update({ openId: "owner", composer: { ...state.composer, seq: 3, sandboxAttachments: false } })');
  assert.equal(await f.run(`sync.retryPaste(${id})`), false);
  assert.equal(f.current().pendingPastes[0].file, files[0]);
  f.run('update({ composer: { ...state.composer, sandboxAttachments: true } })');
  assert.equal(await f.run(`sync.retryPaste(${id})`), true);
  assert.equal(requests[0].request.body, files[0]);
  assert.match(requests[0].url, /type=sandbox/);
  assert.equal(f.current().pendingPastes.length, 1);
  assert.equal(f.current().pendingPastes[0].file, files[1]);
});

test("picker batch keeps selection order, removes successful acknowledgements and retains rejected siblings", async () => {
  const files = [new File(["ok"], "ok.txt"), new File(["keep"], "keep.txt")];
  const requests = [];
  let acceptFirst;
  const first = new Promise(resolve => { acceptFirst = resolve; });
  const f = fixture(async (url, request) => {
    requests.push({ url, request });
    if (request.body === files[0]) return first;
    return { ok: false, status: 500 };
  });
  ingress(f).pick({ currentTarget: { files, value: "selection" } });
  assert.equal(f.current().pendingPastes.length, 2);
  assert.equal(requests.length, 1);
  assert.equal(requests[0].request.body, files[0]);
  acceptFirst({ ok: true, status: 202 });
  await f.settle();
  assert.equal(requests.length, 2);
  assert.equal(requests[1].request.body, files[1]);
  assert.equal(f.current().pendingPastes.length, 1);
  assert.equal(f.current().pendingPastes[0].file, files[1]);
  assert.equal(f.current().pendingPastes[0].uploading, false);
});

test("sandbox-disabled picker and clipboard preserve ordinary image upload routing", async () => {
  const requests = [];
  const f = fixture(async (url, request) => { requests.push({ url, request }); return { ok: true, status: 202 }; });
  const handlers = ingress(f, { sandbox: false });
  const file = new File(["image"], "ordinary.png", { type: "image/png" });
  handlers.pick({ currentTarget: { files: [file], value: "selection" } });
  let prevented = false;
  handlers.paste({ clipboardData: { files: [file], getData: () => "short text" },
    preventDefault() { prevented = true; } });
  await f.settle();
  assert.equal(prevented, false);
  assert.equal(requests.length, 2);
  assert.ok(requests.every(request => request.url.includes("type=image")));
  assert.equal(f.current().pendingPastes.length, 0);
});
