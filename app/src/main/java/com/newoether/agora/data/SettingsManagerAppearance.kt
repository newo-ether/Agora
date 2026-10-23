package com.newoether.agora.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import com.newoether.agora.model.ThinkingSegmentDisplayModes
import com.newoether.agora.model.ToolCallDisplayModes

/**
 * Appearance/display persistence split out of [SettingsManager] to keep each source file within
 * the project's per-file line budget. [SettingsManager] inherits these members, so every existing
 * call site resolves unchanged. Behavior is identical to the former in-class definitions.
 */
abstract class SettingsManagerAppearanceBase(private val appearanceContext: Context) {
    suspend fun saveThemeMode(mode: String) {
        appearanceContext.dataStore.edit { it[THEME_MODE] = mode }
    }
    suspend fun saveAmoledEnabled(enabled: Boolean) {
        appearanceContext.dataStore.edit { it[AMOLED_ENABLED] = enabled }
    }
    suspend fun saveColorScheme(scheme: String) {
        appearanceContext.dataStore.edit { it[COLOR_SCHEME] = scheme }
    }
    suspend fun saveDynamicColor(enabled: Boolean) {
        appearanceContext.dataStore.edit { it[DYNAMIC_COLOR] = enabled }
    }
    suspend fun saveBlurEffectsEnabled(enabled: Boolean) {
        appearanceContext.dataStore.edit { it[BLUR_EFFECTS_ENABLED] = enabled }
    }
    suspend fun saveReduceMotion(enabled: Boolean) {
        appearanceContext.dataStore.edit { it[REDUCE_MOTION] = enabled }
    }
    suspend fun saveStickToBottom(enabled: Boolean) {
        appearanceContext.dataStore.edit { it[STICK_TO_BOTTOM] = enabled }
    }
    suspend fun saveParseInlineDollarMath(enabled: Boolean) {
        appearanceContext.dataStore.edit { it[PARSE_INLINE_DOLLAR_MATH] = enabled }
    }
    suspend fun saveAutoWrapCodeBlocks(enabled: Boolean) {
        appearanceContext.dataStore.edit { it[AUTO_WRAP_CODE_BLOCKS] = enabled }
    }
    suspend fun saveHapticsEnabled(enabled: Boolean) {
        appearanceContext.dataStore.edit { it[HAPTICS_ENABLED] = enabled }
    }
    suspend fun saveDetailedTokenUsage(enabled: Boolean) {
        appearanceContext.dataStore.edit { it[DETAILED_TOKEN_USAGE] = enabled }
    }
    suspend fun saveToolCallDisplayMode(mode: String) {
        appearanceContext.dataStore.edit { it[TOOL_CALL_DISPLAY_MODE] = ToolCallDisplayModes.normalize(mode) }
    }
    suspend fun saveThinkingSegmentDisplayMode(mode: String) {
        appearanceContext.dataStore.edit {
            it[THINKING_SEGMENT_DISPLAY_MODE] = ThinkingSegmentDisplayModes.normalize(mode)
        }
    }
    suspend fun saveAutoExpandActiveGroup(enabled: Boolean) {
        appearanceContext.dataStore.edit { it[AUTO_EXPAND_ACTIVE_GROUP] = enabled }
    }
    suspend fun saveFontPreference(value: String) {
        appearanceContext.dataStore.edit { it[FONT_PREFERENCE] = value }
    }
    suspend fun saveCustomFontPath(value: String) {
        appearanceContext.dataStore.edit { it[CUSTOM_FONT_PATH] = value }
    }
    suspend fun saveCustomFontName(value: String) {
        appearanceContext.dataStore.edit { it[CUSTOM_FONT_NAME] = value }
    }
    suspend fun saveUserBubbleColor(value: String) {
        appearanceContext.dataStore.edit { it[USER_BUBBLE_COLOR] = value }
    }
    suspend fun saveChatBackground(value: String) {
        appearanceContext.dataStore.edit { it[CHAT_BACKGROUND] = value }
    }
    suspend fun saveChatBackgroundOpacity(value: Float) {
        appearanceContext.dataStore.edit { it[CHAT_BACKGROUND_OPACITY] = value }
    }
    suspend fun saveChatBackgroundVideoMuted(value: Boolean) {
        appearanceContext.dataStore.edit { it[CHAT_BACKGROUND_VIDEO_MUTED] = value }
    }
    suspend fun saveChatBackgroundVideoLoop(value: Boolean) {
        appearanceContext.dataStore.edit { it[CHAT_BACKGROUND_VIDEO_LOOP] = value }
    }
    suspend fun saveSchemeStyle(style: String) {
        appearanceContext.dataStore.edit { it[SCHEME_STYLE] = style }
    }
}
