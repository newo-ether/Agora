package com.newoether.agora.data.repository

import com.newoether.agora.data.DEFAULT_CODE_BLOCK_FONT_SIZE_SP
import com.newoether.agora.data.codeBlockFontSizeSp
import com.newoether.agora.data.saveCodeBlockFontSizeSp
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Hot, eagerly published view of the standalone code-block font size in sp. */
internal fun SettingsRepository.codeBlockFontSizeSpFlow(): StateFlow<Int> =
    publication.hot(settingsManager.codeBlockFontSizeSp, DEFAULT_CODE_BLOCK_FONT_SIZE_SP)

/** Persists the standalone code-block font size in sp, clamped to the supported range. */
fun SettingsRepository.setCodeBlockFontSizeSp(sizeSp: Int) = scope.launch {
    settingsManager.saveCodeBlockFontSizeSp(sizeSp)
}
