import assert from "node:assert/strict";
import test from "node:test";

// The module's viewer imports read the page language at load; the pure helpers need no DOM.
globalThis.document ??= { documentElement: {} };
globalThis.navigator ??= { language: "en" };
const { embedVideos, linkBarePaths, linkedMediaType, localMediaPath } = await import("../../main/assets/webui/markdown-links.js");

test("local images and videos open in the viewer; other files and devices stay inert", () => {
  assert.equal(linkedMediaType("/home/agora/out/plot.png"), "image");
  assert.equal(linkedMediaType("shell://Local%20Sandbox/home/agora/clip.mp4"), "video");
  assert.equal(linkedMediaType("/storage/emulated/0/Download/a.JPG"), "image");
  assert.equal(linkedMediaType("/home/agora/report.pdf"), null);
  assert.equal(linkedMediaType("/home/agora/icon.svg"), null);
  assert.equal(linkedMediaType("shell://Quantum/F:/a.png"), null);
  assert.equal(linkedMediaType("/home/agora/../etc/a.png"), null);
  assert.equal(linkedMediaType("/etc/a.png"), null);
  assert.equal(linkedMediaType("/sdcard/Download/a.png"), "image");
  assert.equal(linkedMediaType("file:///sdcard/Download/a.mp4"), "video");
  assert.equal(linkedMediaType("/sdcard/a.pdf"), null);
});

test("local media admission folds file: URLs over the readable roots only", () => {
  assert.equal(localMediaPath("file:///home/agora/a.png"), "/home/agora/a.png");
  assert.equal(localMediaPath("file://localhost/sdcard/a%20b.png"), "/sdcard/a b.png");
  assert.equal(localMediaPath("file:/mnt/shared/a.png"), "/mnt/shared/a.png");
  assert.equal(localMediaPath("file:///etc/a.png"), null);
  assert.equal(localMediaPath("/home/agora/"), null);
  assert.equal(localMediaPath("file:///home/agora/../etc/a.png"), null);
});

test("bare media paths become tappable spans with trailing prose punctuation left out", () => {
  const out = linkBarePaths("见 /home/agora/a.png。还有 /home/agora/b.txt");
  assert.match(out, /data-media-link="\/home\/agora\/a\.png">\/home\/agora\/a\.png<\/span>。/);
  assert.doesNotMatch(out, /b\.txt<\/span>/);
  assert.equal(linkBarePaths("x/home/agora/a.png"), "x/home/agora/a.png");
  assert.equal(linkBarePaths("a < b"), "a &lt; b");
});

test("standalone video lines and <video> tags become card nodes outside code", () => {
  const source = [
    "看这里",
    "/home/agora/clip(1).mp4",
    "/home/agora/two words.mp4",
    "[demo](shell://Quantum/F:/v.webm)",
    '<video src="/home/agora/b.mov" controls></video>',
    "```",
    "/home/agora/code.mp4",
    "```",
    "行内 /home/agora/inline.mp4 不变",
  ].join("\n");
  const { text, origin } = embedVideos(source);
  assert.deepEqual(text.split("\n"), [
    "看这里",
    "![](/home/agora/clip%281%29.mp4)",
    "/home/agora/two words.mp4",
    "![](shell://Quantum/F:/v.webm)",
    "![](/home/agora/b.mov)",
    "```",
    "/home/agora/code.mp4",
    "```",
    "行内 /home/agora/inline.mp4 不变",
  ]);
  assert.equal(origin.length, text.length);
  assert.equal(origin[text.indexOf("![](/home/agora/clip")], source.indexOf("/home/agora/clip"));
  assert.equal(origin[text.indexOf("行内")], source.indexOf("行内"));
  assert.equal(embedVideos("plain text"), null);
});
