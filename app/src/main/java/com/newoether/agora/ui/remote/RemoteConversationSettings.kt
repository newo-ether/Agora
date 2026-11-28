package com.newoether.agora.ui.remote

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.newoether.agora.R
import com.newoether.agora.remote.RemoteState
import com.newoether.agora.ui.common.OpenAiServiceTierControlPanel
import com.newoether.agora.ui.common.ThinkingControlPanel

/** Native unknown values use the original panels without inventing a selected slider tick. */
@Composable
internal fun RemoteThinkingControl(state: RemoteState, enabled: Boolean, onLevelChange: (String) -> Unit) {
    val choices = state.effortChoices
    ThinkingControlPanel(
        enabled = state.selectedEffort != "none", level = state.selectedEffort.orEmpty(),
        budgetEnabled = false, budgetTokens = 4096,
        onEnabledChange = {}, onLevelChange = onLevelChange,
        onBudgetEnabledChange = {}, onBudgetTokensChange = {},
        animateSections = true, availableEfforts = choices, controlsEnabled = enabled,
        showHeader = false, showEnabledToggle = false, showBudgetControls = false,
        settingsRevision = state.settingsRevision, knownSelection = state.selectedEffort in choices,
    )
}

@Composable
internal fun RemoteServiceTierControl(
    state: RemoteState, enabled: Boolean, tierLabels: Map<String, String>, onTierChange: (String?) -> Unit,
) {
    val choices = state.settingsModel?.serviceTiers.orEmpty()
    val tier = state.selectedServiceTier
    OpenAiServiceTierControlPanel(
        enabled = tier != null, tier = tier.orEmpty(),
        onEnabledChange = {}, onTierChange = { onTierChange(it.takeIf(String::isNotEmpty)) },
        availableTiers = listOf("") + choices.filterNot { it.id == "default" }.map { it.id },
        tierLabels = tierLabels + ("" to stringResource(R.string.openai_service_tier_default)),
        controlsEnabled = enabled, showHeader = false, showEnabledToggle = false,
        settingsRevision = state.settingsRevision,
        knownSelection = state.serviceTierKnown && (tier == null || choices.any { it.id == tier }),
    )
}
