import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import { createRequire } from "node:module";
import { fileURLToPath } from "node:url";

const { chromium } = createRequire(process.env.AGORA_PLAYWRIGHT_PACKAGE || import.meta.url)("playwright");
const assets = fileURLToPath(new URL("../../main/assets/webui/", import.meta.url));
const origin = "http://agora-thinking.test";
const markup = `<!doctype html><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
  <link rel="stylesheet" href="/style.css"><link rel="stylesheet" href="/material/material.css">
  <div class="shell"><div id="fixture"></div></div>`;
const browser = await chromium.launch({ channel: "chrome", headless: true });
try {
  for (const width of [390, 1280]) for (const reduced of [false, true]) for (const rtl of [false, true]) {
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
    await page.evaluate(async ({ reduced, rtl }) => {
      const { render } = await import("/vendor/preact.mjs");
      const { html } = await import("/html.js");
      const { ComposerSettings } = await import("/composer-settings.js");
      const { sync } = await import("/sync.js");
      // Resolve every production consumer after moving attachment presentation out of Composer.
      await Promise.all([import("/composer.js"), import("/messages.js"), import("/conversation-editor.js")]);
      document.documentElement.dir = rtl ? "rtl" : "ltr";
      document.querySelector(".shell").dataset.reduceMotion = String(reduced);
      window.commands = [];
      window.events = [];
      for (const type of ["keydown", "input", "change"]) document.addEventListener(type, event => {
        if (event.target.type === "range") window.events.push({ type, value: event.target.value, key: event.key });
      }, true);
      window.target = { connectionId: "fixture", conversationId: "root", seq: 7, kind: "thinking" };
      sync.attachmentTarget = () => window.target;
      sync.setting = (...args) => { window.commands.push(args); return true; };
      window.fixtureState = {
        connected: true, pendingAction: 0, display: { reduceMotion: reduced },
        composer: { modelId: "fixture-model", controls: {
          displayedThinkingEnabled: true, displayedThinkingLevel: "high",
          displayedThinkingBudgetEnabled: false, displayedThinkingBudgetTokens: 4096,
          thinkingCanDisable: true, thinkingSupportsBudget: false,
          thinkingEfforts: ["low", "high"], thinkingBudgetPresets: [1024, 2048, 4096, 8192, 16384, 32768],
          serviceTiers: ["auto"], displayedServiceTier: "auto", openAiServiceTierEnabled: false,
        } },
      };
      window.renderFixture = (patch = {}, controls = {}) => {
        Object.assign(window.fixtureState, patch);
        Object.assign(window.fixtureState.composer.controls, controls);
        render(html`<${ComposerSettings} state=${window.fixtureState} target=${window.target}
          onClose=${() => render(null, document.querySelector("#fixture"))} />`, document.querySelector("#fixture"));
      };
      window.renderFixture();
    }, { reduced, rtl });
    await page.locator(".tool-advanced").click();
    const budgetSwitch = page.locator(".tool-budget-content .tool-switch");
    await budgetSwitch.waitFor({ state: "visible" });
    assert.equal(await budgetSwitch.isDisabled(), true);
    assert.equal(await page.locator(".tool-advanced").getAttribute("aria-expanded"), "true");
    assert.equal(await page.evaluate(() => window.commands.length), 0);
    await page.evaluate(() => window.renderFixture({}, {
      thinkingSupportsBudget: true, displayedThinkingBudgetEnabled: true, displayedThinkingBudgetTokens: 6000,
    }));
    const range = page.locator(".tool-budget-slider input");
    await range.waitFor({ state: "visible" });
    await range.scrollIntoViewIfNeeded();
    assert.equal(await range.inputValue(), "2");
    assert.equal(await page.locator(".tool-setting-header > div > p").first().textContent(), "6000 tokens");
    assert.equal(await page.locator(".tool-budget-slider .tool-setting-title span").textContent(), "4096 tokens");
    const geometry = await page.evaluate(() => {
      const root = document.querySelector(".tool-settings"), advanced = document.querySelector(".tool-advanced");
      const header = document.querySelector(".tool-setting-header"), effort = document.querySelector(".tool-setting-body");
      const slider = document.querySelector(".tool-budget-slider input"), title = effort.querySelector("strong");
      return { width: document.querySelector(".detail-sheet").getBoundingClientRect().width,
        rootPadding: getComputedStyle(root).paddingInlineStart,
        advancedHeight: advanced.getBoundingClientRect().height,
        headerGap: effort.getBoundingClientRect().top - header.getBoundingClientRect().bottom,
        sliderHeight: slider.getBoundingClientRect().height,
        titleTracking: getComputedStyle(title).letterSpacing,
        documentOverflow: document.documentElement.scrollWidth > innerWidth };
    });
    assert.ok(geometry.width <= 640);
    assert.equal(geometry.rootPadding, "16px");
    assert.equal(geometry.advancedHeight, 48);
    assert.equal(geometry.headerGap, 32);
    assert.equal(geometry.sliderHeight, 48);
    assert.equal(geometry.titleTracking, "0.5px");
    assert.equal(geometry.documentOverflow, false);
    await page.evaluate(() => window.renderFixture({ pendingAction: 1 }));
    assert.equal(await range.isDisabled(), true);
    assert.equal(await budgetSwitch.isDisabled(), true);
    assert.equal(await page.locator(".tool-advanced").isDisabled(), false);
    assert.equal(await page.locator(".tool-budget-content").evaluate(node => getComputedStyle(node).opacity), "1");
    await page.evaluate(() => window.renderFixture({ pendingAction: 0 }));
    assert.equal(await page.evaluate(() => window.commands.length), 0, "state updates must not submit a slider value");
    await range.focus();
    await page.keyboard.press("End");
    await page.waitForFunction(() => window.commands.length === 1);
    console.log(JSON.stringify(await page.evaluate(() => ({ events: window.events, commands: window.commands }))));
    assert.deepEqual(await page.evaluate(() => window.commands[0]), ["thinkingBudgetTokens", 32768, {
      connectionId: "fixture", conversationId: "root", seq: 7, kind: "thinking",
    }]);
    await page.evaluate(() => window.renderFixture({}, { displayedThinkingBudgetTokens: 32768 }));
    await range.focus();
    await page.keyboard.press("End");
    assert.equal(await page.evaluate(() => window.commands.length), 1);
    await page.evaluate(() => window.renderFixture({}, { thinkingCanDisable: false, thinkingEfforts: [] }));
    assert.equal(await page.locator(".tool-setting-header > .tool-switch").first().isDisabled(), true);
    assert.equal(await page.locator(".tool-setting-body .tool-setting-title span").first().textContent(), "");
    await page.keyboard.press("Escape");
    await page.waitForFunction(() => !document.querySelector(".detail-sheet-layer"));
    assert.deepEqual(errors, []);
    console.log(JSON.stringify({ width, reduced, rtl, geometry, passed: true }));
    await page.close();
  }
} finally {
  await browser.close();
}
