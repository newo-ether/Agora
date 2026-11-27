import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import { createRequire } from "node:module";
import { fileURLToPath } from "node:url";

const { chromium } = createRequire(process.env.AGORA_PLAYWRIGHT_PACKAGE || import.meta.url)("playwright");
const assets = fileURLToPath(new URL("../../main/assets/webui/", import.meta.url));
const origin = "http://agora-session-tools.test";
const markup = `<!doctype html><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
  <link rel="stylesheet" href="/style.css"><link rel="stylesheet" href="/material/material.css">
  <div class="shell"><div id="fixture"></div></div>`;
const longDescription = "Read the active memory and return its complete contents. This description is deliberately long enough to wrap beyond two lines in the narrow tool row so expansion and clipping can be measured in a real browser.";
const browser = await chromium.launch({ channel: "chrome", headless: true });
try {
  for (const width of [390, 1280]) for (const reduced of [false, true]) {
    const page = await browser.newPage({ viewport: { width, height: 900 } });
    const errors = [];
    page.on("pageerror", error => errors.push(error.message));
    await page.route(`${origin}/**`, async route => {
      const name = new URL(route.request().url()).pathname;
      if (name === "/") return route.fulfill({ contentType: "text/html", body: markup });
      const file = path.join(assets, name);
      if (!file.startsWith(assets) || !fs.existsSync(file)) return route.fulfill({ status: 404, body: "" });
      return route.fulfill({ contentType: name.endsWith(".css") ? "text/css" : "text/javascript", body: fs.readFileSync(file) });
    });
    await page.goto(origin);
    await page.evaluate(async ({ reduced, longDescription }) => {
      const { render } = await import("/vendor/preact.mjs");
      const { html } = await import("/html.js");
      const { SessionTools } = await import("/session-tools.js");
      const { ConversationEditor } = await import("/conversation-editor.js");
      const { sync } = await import("/sync.js");
      const { ICON_ADD_PHOTO_ALTERNATE, ICON_MCP } = await import("/icons.js");
      document.querySelector(".shell").dataset.reduceMotion = String(reduced);
      window.expectedIcons = { image: ICON_ADD_PHOTO_ALTERNATE, mcp: ICON_MCP };
      window.render = render;
      window.commands = [];
      window.fixtureState = {
        display: { reduceMotion: reduced },
        sessionTools: {
          connectionId: "browser", conversationId: "root", seq: 7, revision: 1,
          pending: false, failed: false, enabledGroups: ["memory", "image"], toolSetTokens: 5,
          groups: [
            { id: "memory", tokens: 30, tools: [
              { name: "active_memory_read", description: longDescription, tokens: 10 },
              { name: "memory_read", description: "Read a saved memory entry.", tokens: 20 },
            ] },
            { id: "image", tokens: 20, tools: [{ name: "image_generate", description: "Generate an image.", tokens: 20 }] },
            { id: "mcp", tokens: 40, unavailable: "no_tools", tools: [{ name: "ignored", description: "Unavailable", tokens: 40 }] },
          ],
        },
      };
      window.renderTools = () => render(html`<${SessionTools} state=${window.fixtureState}
        focusReturn=${null} onClose=${() => {}} />`, document.querySelector("#fixture"));
      sync.toggleSessionToolGroup = (group, enabled) => {
        window.commands.push([group, enabled]);
        const selected = new Set(window.fixtureState.sessionTools.enabledGroups);
        if (enabled) selected.add(group); else selected.delete(group);
        window.fixtureState.sessionTools.enabledGroups = [...selected];
        window.renderTools();
        return true;
      };
      sync.editorCommand = () => true;
      window.renderCompact = () => {
        const state = {
          pendingAction: 0, display: { reduceMotion: reduced },
          composer: { models: { model: "Fixture Model" }, compact: { preserveContext: true, compacting: false } },
        };
        const editor = { kind: "compact", initial: { modelId: "model", prompt: "Summarize the context", retainCount: 4 } };
        render(html`<${ConversationEditor} editor=${editor} state=${state} focusReturn=${null}
          MoreMenu=${() => null} onClose=${() => {}} />`, document.querySelector("#fixture"));
      };
      window.renderTools();
    }, { reduced, longDescription });
    await page.locator(".session-tools-body").waitFor();
    await page.waitForTimeout(450);
    assert.equal(await page.locator(".detail-sheet-header h2").textContent(), "Tools");
    assert.equal(await page.locator(".detail-sheet-header-trailing").textContent(), "3 / 4 (55 tokens)");
    assert.equal(await page.locator(".session-tools-group").count(), 3);
    assert.equal(await page.locator(".session-tools-group.unavailable .session-tools-group-meta").textContent(), "No tools available");
    assert.equal(await page.locator(".session-tools-group.unavailable [role='switch']").count(), 0);
    assert.equal(await page.locator(".session-tools-group.unavailable .session-tools-group-toggle").count(), 0);
    const initialStyle = await page.evaluate(() => {
      const title = getComputedStyle(document.querySelector(".session-tools-group-title"));
      const meta = getComputedStyle(document.querySelector(".session-tools-group-meta"));
      const paths = [...document.querySelectorAll(".session-tools-group")].map(group => ({
        id: group.querySelector(".session-tools-group-title").textContent,
        path: group.querySelector(".session-tools-icon path")?.getAttribute("d"),
      }));
      return { title: [title.fontSize, title.lineHeight, title.fontWeight],
        meta: [meta.fontSize, meta.lineHeight, meta.fontWeight], paths,
        chevron: getComputedStyle(document.querySelector(".session-tools-chevron")).transitionDuration,
        toolsTransition: getComputedStyle(document.querySelector(".session-tools-tools")).transitionDuration,
        overflow: document.documentElement.scrollWidth > innerWidth };
    });
    assert.deepEqual(initialStyle.title, ["16px", "24px", "500"]);
    assert.deepEqual(initialStyle.meta, ["12px", "16px", "500"]);
    assert.equal(initialStyle.paths.find(item => item.id === "Image Generation").path,
      await page.evaluate(() => window.expectedIcons.image));
    assert.equal(initialStyle.paths.find(item => item.id === "MCP").path,
      await page.evaluate(() => window.expectedIcons.mcp));
    assert.equal(initialStyle.chevron, reduced ? "0s" : "0.4s");
    assert.equal(initialStyle.toolsTransition, reduced ? "0s, 0.4s" : "0.4s, 0.4s");
    assert.equal(initialStyle.overflow, false);

    const memoryGroup = page.locator(".session-tools-group").first();
    await memoryGroup.locator(".session-tools-group-toggle").click();
    assert.equal(await memoryGroup.locator(".session-tools-group-toggle").getAttribute("aria-expanded"), "true");
    assert.equal(await memoryGroup.locator(".session-tools-group-meta").textContent(), "2 tools (30 tokens)");
    const tool = memoryGroup.locator(".session-tools-tool").first();
    const description = tool.locator(".session-tools-tool-description");
    const collapsedHeight = await description.evaluate(node => node.getBoundingClientRect().height);
    assert.equal(collapsedHeight, 32);
    await tool.click();
    const during = await description.evaluate(node => ({
      animations: node.getAnimations().length, overflow: getComputedStyle(node).overflow,
      height: node.getBoundingClientRect().height, fullHeight: node.scrollHeight,
    }));
    assert.equal(during.overflow, "hidden", "the expanding text must stay clipped to its animated height");
    if (reduced) {
      assert.equal(during.animations, 0);
      assert.ok(during.height > collapsedHeight);
    } else {
      assert.ok(during.animations > 0);
      await page.waitForTimeout(450);
    }
    assert.equal(await tool.getAttribute("aria-expanded"), "true");
    assert.ok(await description.evaluate(node => node.getBoundingClientRect().height) > collapsedHeight);
    assert.equal(await description.evaluate(node => getComputedStyle(node).overflow), "hidden");
    const descriptionStyle = await description.evaluate(node => {
      const style = getComputedStyle(node); return [style.fontSize, style.lineHeight];
    });
    assert.deepEqual(descriptionStyle, ["12px", "16px"]);

    await memoryGroup.locator("[role='switch']").click();
    assert.deepEqual(await page.evaluate(() => window.commands), [["memory", false]]);
    assert.equal(await memoryGroup.locator("[role='switch']").getAttribute("aria-checked"), "false");
    assert.equal(await page.locator(".detail-sheet-header-trailing").textContent(), "1 / 4 (25 tokens)");

    await page.evaluate(() => {
      window.render(null, document.querySelector("#fixture"));
      window.renderCompact();
    });
    const dialog = page.locator("dialog[data-editor='compact']");
    await dialog.waitFor({ state: "visible" });
    const compact = await page.evaluate(() => {
      const title = document.querySelector("dialog[data-editor='compact'] h2");
      const support = document.querySelector(".compact-model-section > p.supporting");
      const titleStyle = getComputedStyle(title), supportStyle = getComputedStyle(support);
      const probe = document.createElement("div");
      probe.style.color = "var(--md-on-surface-variant)";
      document.body.appendChild(probe);
      const expectedColor = getComputedStyle(probe).color;
      probe.remove();
      return { title: [titleStyle.color, titleStyle.fontSize, titleStyle.lineHeight, titleStyle.fontWeight],
        support: [supportStyle.fontSize, supportStyle.lineHeight, supportStyle.letterSpacing,
          supportStyle.marginTop, supportStyle.marginLeft, supportStyle.marginRight],
        expectedColor, wrappedField: !!document.querySelector(".compact-model-section > .outlined-field"),
        modelDisabled: document.querySelector(".editor-dropdown-field").disabled };
    });
    assert.deepEqual(compact.title, [compact.expectedColor, "23px", "29px", "700"]);
    assert.deepEqual(compact.support, ["11px", "16px", "0.4px", "4px", "16px", "16px"]);
    assert.equal(compact.wrappedField, true);
    assert.equal(compact.modelDisabled, true);
    assert.deepEqual(errors, []);
    console.log(JSON.stringify({ width, reduced, initialStyle, during, compact, passed: true }));
    await page.close();
  }
} finally {
  await browser.close();
}
