package com.newoether.agora.data

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

internal val CODE_BLOCK_FONT_SIZE_SP = intPreferencesKey("code_block_font_size_sp")

internal const val DEFAULT_CODE_BLOCK_FONT_SIZE_SP = 14
internal const val MIN_CODE_BLOCK_FONT_SIZE_SP = 10
internal const val MAX_CODE_BLOCK_FONT_SIZE_SP = 20
internal val CODE_BLOCK_FONT_SIZE_PRESETS = (MIN_CODE_BLOCK_FONT_SIZE_SP..MAX_CODE_BLOCK_FONT_SIZE_SP).toList()

internal fun normalizeCodeBlockFontSizeSp(value: Int?): Int =
    (value ?: DEFAULT_CODE_BLOCK_FONT_SIZE_SP).coerceIn(MIN_CODE_BLOCK_FONT_SIZE_SP, MAX_CODE_BLOCK_FONT_SIZE_SP)

val SettingsManager.codeBlockFontSizeSp: Flow<Int>
    get() = context.dataStore.data.map { normalizeCodeBlockFontSizeSp(it[CODE_BLOCK_FONT_SIZE_SP]) }

suspend fun SettingsManager.saveCodeBlockFontSizeSp(sizeSp: Int) {
    context.dataStore.edit { it[CODE_BLOCK_FONT_SIZE_SP] = normalizeCodeBlockFontSizeSp(sizeSp) }
}
