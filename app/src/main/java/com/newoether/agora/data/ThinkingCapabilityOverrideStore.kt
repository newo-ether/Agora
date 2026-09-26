package com.newoether.agora.data

import com.newoether.agora.model.ModelThinkingCapabilityOverride
import com.newoether.agora.util.DebugLog
import kotlinx.serialization.json.Json

/** Only user-set fields are written, so a stored override never freezes a documented default. */
internal val thinkingCapabilityJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
    explicitNulls = false
}

/** Decodes the persisted override map, treating damaged content as "no overrides". */
internal fun decodeThinkingCapabilityOverrides(
    raw: String?,
): Map<String, ModelThinkingCapabilityOverride> {
    val text = raw?.takeIf { it.isNotBlank() } ?: return emptyMap()
    return try {
        thinkingCapabilityJson.decodeFromString<Map<String, ModelThinkingCapabilityOverride>>(text)
    } catch (e: Exception) {
        DebugLog.e("SettingsManager", "Failed to decode thinkingCapabilityOverrides", e)
        emptyMap()
    }
}
