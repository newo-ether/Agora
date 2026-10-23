package com.newoether.agora.data.repository

import com.newoether.agora.data.SettingsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Appearance/display setters split out of [SettingsRepository] to keep each source file within the
 * project's per-file line budget. [SettingsRepository] inherits [settingsManager] and [scope] from
 * here, so every existing call site (and the module-internal access to those two properties)
 * resolves unchanged. Behavior is identical to the former in-class definitions.
 */
abstract class SettingsRepositoryAppearanceBase(
    internal val settingsManager: SettingsManager,
    internal val scope: CoroutineScope,
) {
    fun setThemeMode(mode: String) = scope.launch { settingsManager.saveThemeMode(mode) }
    fun setAmoledEnabled(enabled: Boolean) = scope.launch { settingsManager.saveAmoledEnabled(enabled) }
    fun setColorScheme(scheme: String) = scope.launch { settingsManager.saveColorScheme(scheme) }
    fun setDynamicColor(enabled: Boolean) = scope.launch { settingsManager.saveDynamicColor(enabled) }
    fun setBlurEffectsEnabled(enabled: Boolean) = scope.launch { settingsManager.saveBlurEffectsEnabled(enabled) }
    fun setReduceMotion(enabled: Boolean) = scope.launch { settingsManager.saveReduceMotion(enabled) }
    fun setStickToBottom(enabled: Boolean) = scope.launch { settingsManager.saveStickToBottom(enabled) }
    fun setParseInlineDollarMath(enabled: Boolean) =
        scope.launch { settingsManager.saveParseInlineDollarMath(enabled) }
    fun setAutoWrapCodeBlocks(enabled: Boolean) = scope.launch { settingsManager.saveAutoWrapCodeBlocks(enabled) }
    fun setHapticsEnabled(enabled: Boolean) = scope.launch { settingsManager.saveHapticsEnabled(enabled) }
    fun setDetailedTokenUsage(enabled: Boolean) =
        scope.launch { settingsManager.saveDetailedTokenUsage(enabled) }
    fun setToolCallDisplayMode(mode: String) = scope.launch { settingsManager.saveToolCallDisplayMode(mode) }
    fun setThinkingSegmentDisplayMode(mode: String) =
        scope.launch { settingsManager.saveThinkingSegmentDisplayMode(mode) }

    fun setAutoExpandActiveGroup(enabled: Boolean) =
        scope.launch { settingsManager.saveAutoExpandActiveGroup(enabled) }
    fun setSchemeStyle(style: String) = scope.launch { settingsManager.saveSchemeStyle(style) }
    fun setFontPreference(value: String) = scope.launch { settingsManager.saveFontPreference(value) }
    fun setCustomFontPath(value: String) = scope.launch { settingsManager.saveCustomFontPath(value) }
    fun setCustomFontName(value: String) = scope.launch { settingsManager.saveCustomFontName(value) }
    fun setUserBubbleColor(value: String) = scope.launch { settingsManager.saveUserBubbleColor(value) }
    fun setChatBackground(value: String) = scope.launch { settingsManager.saveChatBackground(value) }
    fun setChatBackgroundOpacity(v: Float) = scope.launch { settingsManager.saveChatBackgroundOpacity(v) }
    fun setChatBackgroundVideoMuted(v: Boolean) = scope.launch { settingsManager.saveChatBackgroundVideoMuted(v) }
    fun setChatBackgroundVideoLoop(v: Boolean) = scope.launch { settingsManager.saveChatBackgroundVideoLoop(v) }
}
