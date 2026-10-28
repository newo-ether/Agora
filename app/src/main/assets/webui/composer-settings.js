import { useEffect, useLayoutEffect, useState } from "./vendor/preact-hooks.mjs";
import { html } from "./html.js";
import { DetailSheet } from "./detail-sheet.js";
import { sliderStyle } from "./material/slider.js";
import { t } from "./i18n.js";
import { icon, ICON_NEUROLOGY, ICON_SPEED, ICON_CHEVRON_DOWN } from "./icons.js";
import { sync } from "./sync.js";

export function thinkingSummary(controls) {
  if (!controls?.displayedThinkingEnabled) return t.off;
  if (controls.displayedThinkingBudgetEnabled) return t.tokens(controls.displayedThinkingBudgetTokens);
  return t.efforts[controls.displayedThinkingLevel] ?? controls.displayedThinkingLevel;
}

/** The native control panels' presentation; model policy and accepted values come from sync. */
export function ComposerSettings({ state, target, focusReturn, onClose }) {
  const controls = state.composer.controls;
  const thinking = target.kind === "thinking";
  const available = state.connected && !!sync.attachmentTarget();
  const editable = available && !state.pendingAction;
  const capabilityAvailable = available && !controls.lowContextModeEnabled;
  const enabled = thinking ? controls.displayedThinkingEnabled : controls.openAiServiceTierEnabled;
  const [advanced, setAdvanced] = useState(!!controls.displayedThinkingBudgetEnabled);
  const [slider, setSlider] = useState(null);
  useEffect(() => { if (controls.displayedThinkingBudgetEnabled) setAdvanced(true); }, [controls.displayedThinkingBudgetEnabled]);
  // Reset before inputs become interactive; a passive reset can overwrite the next native input event.
  useLayoutEffect(() => { if (!state.pendingAction) setSlider(null); }, [state.pendingAction]);
  useLayoutEffect(() => { setSlider(null); }, [state.composer.modelId]);
  const effortIndex = slider?.key === "effort" ? slider.index : Math.max(0, controls.thinkingEfforts.indexOf(controls.displayedThinkingLevel));
  const budgetIndex = slider?.key === "budget" ? slider.index : controls.thinkingBudgetPresets.reduce((best, value, index, values) =>
    Math.abs(value - controls.displayedThinkingBudgetTokens) < Math.abs(values[best] - controls.displayedThinkingBudgetTokens) ? index : best, 0);
  const tierIndex = slider?.key === "tier" ? slider.index : Math.max(0, controls.serviceTiers.indexOf(controls.displayedServiceTier));
  const effortAvailable = available && enabled && !controls.displayedThinkingBudgetEnabled && controls.thinkingEfforts.length > 0;
  const budgetAvailable = available && enabled && controls.thinkingSupportsBudget;
  function submit(key, value, current) {
    if (value === current || !sync.setting(key, value, target)) setSlider(null);
  }
  return html`<${DetailSheet} settings title=${thinking ? t.thinking : t.serviceTier} display=${state.display}
    focusReturn=${focusReturn} onClose=${onClose}>
    <div class="tool-settings">
      <div class="tool-setting-header">
        ${icon(thinking ? ICON_NEUROLOGY : ICON_SPEED, thinking ? "0 0 960 960" : "0 0 24 24")}
        <div><strong>${thinking ? t.thinking : t.serviceTier}</strong><p>${thinking ? thinkingSummary(controls) : t.tierDescription}</p></div>
        <button class="tool-switch" role="switch" aria-label=${thinking ? t.thinking : t.serviceTier}
          aria-checked=${enabled} data-checked=${enabled}
          data-pending=${available && !!state.pendingAction && (thinking ? controls.thinkingCanDisable || !enabled : capabilityAvailable)}
          disabled=${!editable || (thinking ? !controls.thinkingCanDisable && enabled : !capabilityAvailable)}
          onClick=${() => sync.setting(thinking ? "thinkingEnabled" : "openAiServiceTierEnabled", !enabled, target)}></button>
      </div>
      ${thinking ? html`
        <div class="tool-setting-body" data-disabled=${!effortAvailable}>
          ${icon(ICON_NEUROLOGY, "0 0 960 960")}
          <div class="tool-setting-title"><strong>${t.thinkingEffort}</strong><span>${t.efforts[controls.thinkingEfforts[effortIndex]] ?? controls.thinkingEfforts[effortIndex] ?? ""}</span></div>
          <p>${t.effortDescription}</p>
          <input type="range" aria-label=${t.thinkingEffort} min="0" max=${Math.max(1, controls.thinkingEfforts.length - 1)} step="1"
            style=${sliderStyle(0, Math.max(1, controls.thinkingEfforts.length - 1), effortIndex)} value=${effortIndex}
            data-pending=${effortAvailable && !!state.pendingAction} disabled=${!editable || !effortAvailable || controls.thinkingEfforts.length < 2}
            onInput=${event => setSlider({ key: "effort", index: Number(event.currentTarget.value) })}
            onChange=${event => submit("thinkingLevel", controls.thinkingEfforts[Number(event.currentTarget.value)], controls.displayedThinkingLevel)} />
        </div>
        <button class="tool-advanced" aria-expanded=${advanced} onClick=${() => setAdvanced(!advanced)}>
          ${advanced ? t.hideAdvanced : t.advanced}${icon(ICON_CHEVRON_DOWN)}</button>
        <div class="tool-budget-section" data-visible=${advanced} inert=${!advanced}>
          <div class="tool-budget-content" data-disabled=${!budgetAvailable}>
            <div class="tool-setting-header">
              ${icon(ICON_NEUROLOGY, "0 0 960 960")}
              <div><strong>${t.useBudget}</strong><p class="budget-note">${t.budgetNote}</p></div>
              <button class="tool-switch" role="switch" aria-label=${t.useBudget}
                aria-checked=${controls.displayedThinkingBudgetEnabled} data-checked=${controls.displayedThinkingBudgetEnabled}
                data-pending=${budgetAvailable && !!state.pendingAction} disabled=${!editable || !budgetAvailable}
                onClick=${() => sync.setting("thinkingBudgetEnabled", !controls.displayedThinkingBudgetEnabled, target)}></button>
            </div>
            <div class="tool-budget-section" data-visible=${controls.displayedThinkingBudgetEnabled} inert=${!controls.displayedThinkingBudgetEnabled}>
              <div><div class="tool-setting-body tool-budget-slider">
                ${icon(ICON_NEUROLOGY, "0 0 960 960")}
                <div class="tool-setting-title"><strong>${t.budget}</strong><span>${t.tokens(controls.thinkingBudgetPresets[budgetIndex])}</span></div>
                <input type="range" aria-label=${t.budget} min="0" max=${controls.thinkingBudgetPresets.length - 1} step="1"
                  style=${sliderStyle(0, controls.thinkingBudgetPresets.length - 1, budgetIndex)} value=${budgetIndex}
                  data-pending=${budgetAvailable && !!state.pendingAction} disabled=${!editable || !budgetAvailable}
                  onInput=${event => setSlider({ key: "budget", index: Number(event.currentTarget.value) })}
                  onChange=${event => submit("thinkingBudgetTokens", controls.thinkingBudgetPresets[Number(event.currentTarget.value)], controls.displayedThinkingBudgetTokens)} />
              </div></div>
            </div>
          </div>
        </div>
      ` : html`<div class="tool-setting-body" data-disabled=${!capabilityAvailable || !enabled}>
        ${icon(ICON_SPEED)}
        <div class="tool-setting-title"><strong>${t.serviceTier}</strong><span>${t.tiers[controls.serviceTiers[tierIndex]]}</span></div>
        <p>${t.tierDescription}</p>
        <input type="range" aria-label=${t.serviceTier} min="0" max=${Math.max(1, controls.serviceTiers.length - 1)} step="1"
          style=${sliderStyle(0, Math.max(1, controls.serviceTiers.length - 1), tierIndex)} value=${tierIndex}
          data-pending=${capabilityAvailable && enabled && !!state.pendingAction}
          disabled=${!editable || !capabilityAvailable || !enabled || controls.serviceTiers.length < 2}
          onInput=${event => setSlider({ key: "tier", index: Number(event.currentTarget.value) })}
          onChange=${event => submit("openAiServiceTier", controls.serviceTiers[Number(event.currentTarget.value)], controls.displayedServiceTier)} />
      </div>`}
    </div>
  </${DetailSheet}>`;
}
