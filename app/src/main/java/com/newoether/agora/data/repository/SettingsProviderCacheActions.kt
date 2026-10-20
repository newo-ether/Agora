package com.newoether.agora.data.repository

import com.newoether.agora.util.Constants
import kotlinx.coroutines.launch

/** Provider cache writes share the same Settings/DataStore authority for both provider editors. */
fun SettingsRepository.setAnthropicCacheEnabled(providerId: String, enabled: Boolean) = scope.launch {
    if (providerId == Constants.PROVIDER_ANTHROPIC) settingsManager.saveAnthropicCacheEnabled(enabled)
    else settingsManager.updateCustomProviderCache(providerId, enabled = enabled)
}

fun SettingsRepository.setAnthropicCacheTtl(providerId: String, ttl: String) = scope.launch {
    if (providerId == Constants.PROVIDER_ANTHROPIC) settingsManager.saveAnthropicCacheTtl(ttl)
    else settingsManager.updateCustomProviderCache(providerId, ttl = ttl)
}
