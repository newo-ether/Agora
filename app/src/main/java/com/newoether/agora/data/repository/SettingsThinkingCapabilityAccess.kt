package com.newoether.agora.data.repository

import com.newoether.agora.model.ModelThinkingCapabilityOverride
import kotlinx.coroutines.launch

/**
 * Stores or clears one model's thinking capability correction. An empty override removes the entry.
 *
 * This setter lives beside [SettingsRepository] rather than inside it because the repository body is
 * already at the project's file-size limit.
 */
fun SettingsRepository.setThinkingCapabilityOverride(
    key: String,
    override: ModelThinkingCapabilityOverride?,
) = scope.launch {
    settingsManager.modelPreferenceStore.saveThinkingCapabilityOverride(key, override)
}
