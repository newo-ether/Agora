package com.newoether.agora.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.newoether.agora.data.repository.SettingsRepository
import com.newoether.agora.data.repository.setThinkingCapabilityOverride
import com.newoether.agora.model.ModelId
import com.newoether.agora.model.ModelThinkingCapabilities
import com.newoether.agora.model.ModelThinkingCapabilityOverride

/**
 * The stored capability correction for the selected model plus the writer for it.
 *
 * One value instead of a value and a callback keeps the bottom-bar parameter lists short, and keeps
 * the override key in a single place so the editor cannot write under a key the request path does
 * not read.
 */
data class ThinkingCapabilityOverrideBinding(
    val override: ModelThinkingCapabilityOverride? = null,
    val onChange: (ModelThinkingCapabilityOverride?) -> Unit = {},
)

@Composable
internal fun rememberThinkingCapabilityOverrideBinding(
    settings: SettingsRepository,
    selectedModel: String,
): ThinkingCapabilityOverrideBinding {
    val overrides by settings.thinkingCapabilityOverrides.collectAsState()
    val key = remember(selectedModel) {
        val parsed = ModelId.parse(selectedModel)
        ModelThinkingCapabilities.overrideKey(parsed.providerName, parsed.modelName)
    }
    return ThinkingCapabilityOverrideBinding(
        override = overrides[key],
        onChange = { override -> settings.setThinkingCapabilityOverride(key, override) },
    )
}
